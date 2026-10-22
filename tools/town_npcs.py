"""Расстановка кастомных NPC (баффер, GM-шоп) рядом с гейткиперами городов Mobius.

Точки подбираются по геодате .l2j (тот же формат и та же математика, что в GeoEngine Mobius):
ровное проходимое место сбоку от гейткипера, не у стены и не вплотную к другим NPC.
Результат — файл спавнов Mobius.

Использование:
  python -X utf8 -I tools/town_npcs.py <папка data сборки> <папка geodata> <out.xml> [--switches]
--switches — баффер и шоп в отдельных группах спавна с выключателями TownBuffer и TownGmShop
(config/Custom/L2HubTown.ini, скрипт custom/L2HubTown). Только для Classic и Essence: у CT нет групп спавна.
Пример для High Five:
  python -X utf8 -I tools/town_npcs.py servers/ct26_highfive/L2J_Mobius_CT_2.6_HighFive/dist/game/data
      servers/ct26_highfive/server/game/data/geodata
      servers/ct26_highfive/L2J_Mobius_CT_2.6_HighFive/dist/game/data/spawns/Custom/L2HubTownNpcs.xml
"""
import math
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Гейткиперы городов и деревень High Five (id из спавнов Mobius).
GATEKEEPERS = {
    30006: "Talking Island Village", 30146: "Elven Village", 30134: "Dark Elven Village",
    30576: "Orc Village", 30540: "Dwarven Village", 32163: "Kamael Village",
    30320: "Gludin Village", 30256: "Town of Gludio", 30059: "Town of Dion",
    30080: "Town of Giran", 30899: "Town of Heine", 30233: "Hunters Village",
    30177: "Town of Oren", 30848: "Town of Aden", 31275: "Town of Goddard",
    31320: "Town of Rune", 31964: "Town of Schuttgart",
}
# Кого ставить: id NPC, подпись и углы относительно взгляда гейткипера (градусы) в порядке предпочтения:
# баффер — справа, шоп — слева; если сторона упирается в стену, NPC встаёт с другой стороны.
SIDE = (90, 60, 120, 45, 135, 30, 150)
PLACED = [
    (50008, "Scheme Buffer", SIDE + tuple(-a for a in SIDE)),
    (50010, "GM Shop", tuple(-a for a in SIDE) + SIDE),
]
# Ключи L2HubTown.ini и имена групп спавна при --switches.
SWITCHES = {50008: "TownBuffer", 50010: "TownGmShop"}
DISTANCES = (80, 100, 120, 140, 60)
RESPAWN = 60

# Геодата, как в GeoEngine.java / IRegion / IBlock
WORLD_MIN_X, WORLD_MIN_Y = -655360, -589824
CELL = 16
REGION_CELLS = 2048
BLOCK_CELLS = 8
NSWE_ALL = 0x0F

MAX_STEP_DZ = 16     # перепад между соседними клетками пути
MAX_POINT_DZ = 32    # перепад между гейткипером и точкой
CLEAR_RADIUS = 2     # клеток вокруг точки без стен (32 единицы)
NPC_GAP = 45         # до других NPC
PLACED_GAP = 60      # между нашими NPC


class Region:
    def __init__(self, path: Path):
        self.data = path.read_bytes()
        self.offsets = []  # смещение каждого из 256*256 блоков
        pos = 0
        d = self.data
        for _ in range(256 * 256):
            self.offsets.append(pos)
            kind = d[pos]
            if kind == 0:
                pos += 3
            elif kind == 1:
                pos += 1 + 64 * 2
            elif kind == 2:
                pos += 1
                for _ in range(64):
                    pos += 1 + d[pos] * 2
            else:
                raise ValueError(f"{path.name}: неизвестный тип блока {kind}")

    @staticmethod
    def _decode(v: int) -> tuple[int, int]:
        h = v & 0xFFF0
        if h >= 0x8000:
            h -= 0x10000
        return h >> 1, v & 0x0F

    def cell(self, gx: int, gy: int, z: int) -> tuple[int, int]:
        """(высота, NSWE) ближайшего к z слоя клетки."""
        block = ((gx // BLOCK_CELLS) % 256) * 256 + ((gy // BLOCK_CELLS) % 256)
        pos = self.offsets[block]
        d = self.data
        kind = d[pos]
        idx = (gx % BLOCK_CELLS) * BLOCK_CELLS + (gy % BLOCK_CELLS)
        if kind == 0:
            return struct.unpack_from("<h", d, pos + 1)[0], NSWE_ALL
        if kind == 1:
            return self._decode(struct.unpack_from("<H", d, pos + 1 + idx * 2)[0])
        pos += 1
        for _ in range(idx):
            pos += 1 + d[pos] * 2
        layers = [self._decode(struct.unpack_from("<H", d, pos + 1 + i * 2)[0]) for i in range(d[pos])]
        return min(layers, key=lambda layer: abs(layer[0] - z))


class Geo:
    def __init__(self, folder: Path):
        self.folder = folder
        self.regions: dict[tuple[int, int], Region | None] = {}

    def cell(self, x: int, y: int, z: int) -> tuple[int, int] | None:
        gx, gy = (x - WORLD_MIN_X) // CELL, (y - WORLD_MIN_Y) // CELL
        key = (gx // REGION_CELLS, gy // REGION_CELLS)
        if key not in self.regions:
            f = self.folder / f"{key[0]}_{key[1]}.l2j"
            self.regions[key] = Region(f) if f.exists() else None
        region = self.regions[key]
        return region.cell(gx, gy, z) if region else None


def _dir(dx: int, dy: int) -> int:
    """Биты NSWE направления шага между клетками (Cell.java: E=1, W=2, S=4, N=8)."""
    return (1 if dx > 0 else 2 if dx < 0 else 0) | (4 if dy > 0 else 8 if dy < 0 else 0)


def check_point(geo: Geo, x0, y0, z0, x, y) -> int | None:
    """Высота точки, если до неё можно дойти от гейткипера по ровному и вокруг нет стен.
    Шаг из клетки разрешён, если в ней открыто направление движения — как в GeoEngine.canMoveToTarget."""
    steps = max(1, int(math.dist((x0, y0), (x, y)) // (CELL / 2)))
    cur = ((x0 - WORLD_MIN_X) // CELL, (y0 - WORLD_MIN_Y) // CELL)
    cur_cell = geo.cell(x0, y0, z0)
    for i in range(1, steps + 1):
        px, py = round(x0 + (x - x0) * i / steps), round(y0 + (y - y0) * i / steps)
        nxt = ((px - WORLD_MIN_X) // CELL, (py - WORLD_MIN_Y) // CELL)
        if nxt == cur:
            continue
        d = _dir(nxt[0] - cur[0], nxt[1] - cur[1])
        c = geo.cell(px, py, cur_cell[0])
        if c is None or (cur_cell[1] & d) != d or abs(c[0] - cur_cell[0]) > MAX_STEP_DZ:
            return None
        cur, cur_cell = nxt, c
    prev_z = cur_cell[0]
    if abs(prev_z - z0) > MAX_POINT_DZ:
        return None
    for dx in range(-CLEAR_RADIUS, CLEAR_RADIUS + 1):
        for dy in range(-CLEAR_RADIUS, CLEAR_RADIUS + 1):
            c = geo.cell(x + dx * CELL, y + dy * CELL, prev_z)
            if c is None or c[1] != NSWE_ALL or abs(c[0] - prev_z) > MAX_STEP_DZ:
                return None
    return prev_z


def load_spawns(data: Path) -> list[dict]:
    out = []
    for f in (data / "spawns").rglob("*.xml"):
        for npc in ET.parse(f).getroot().iter("npc"):
            if npc.get("x") is not None:
                out.append({"id": int(npc.get("id")), "x": int(npc.get("x")), "y": int(npc.get("y")),
                            "z": int(npc.get("z")), "heading": int(npc.get("heading", "0")), "file": f})
    return out


def main(data: Path, geodata: Path, out: Path, switches: bool = False) -> int:
    geo = Geo(geodata)
    # Формат спавнов по схеме датапака: у CT respawnDelay в секундах и обязательный enabled у <list>,
    # у Classic и Essence — respawnTime с единицами и без enabled (Mobius проверяет XML по xsd).
    old_format = "respawnDelay" in (data / "xsd/spawns.xsd").read_text(encoding="utf-8")
    respawn = f'respawnDelay="{RESPAWN}"' if old_format else f'respawnTime="{RESPAWN}sec"'
    list_attrs = 'enabled="true" ' if old_format else ""
    spawns = [s for s in load_spawns(data) if s["file"].resolve() != out.resolve()]
    lines, report, failed = [], [], []
    by_npc = {npc_id: [] for npc_id, _, _ in PLACED}
    for gk_id, town in GATEKEEPERS.items():
        gk = [s for s in spawns if s["id"] == gk_id]
        if len(gk) != 1:
            failed.append(f"{town}: гейткипер {gk_id} найден {len(gk)} раз")
            continue
        gk = gk[0]
        x0, y0, z0, heading = gk["x"], gk["y"], gk["z"], gk["heading"]
        c = geo.cell(x0, y0, z0)
        if c is None:
            failed.append(f"{town}: нет геодаты")
            continue
        z0 = c[0]  # высота гейткипера по геодате
        facing = heading * 360 / 65536
        near = [s for s in spawns if math.dist((s["x"], s["y"]), (x0, y0)) < 400 and abs(s["z"] - z0) < 200]
        placed = []

        def find_spot(sx, sy, sz, angles):
            for dist in DISTANCES:
                for rel in angles:
                    a = math.radians(facing + rel)
                    x, y = round(x0 + dist * math.cos(a)), round(y0 + dist * math.sin(a))
                    if any(math.dist((s["x"], s["y"]), (x, y)) < NPC_GAP for s in near):
                        continue
                    if any(math.dist((p[0], p[1]), (x, y)) < PLACED_GAP for p in placed):
                        continue
                    z = check_point(geo, sx, sy, sz, x, y)
                    if z is not None:
                        return (x, y, z, rel, dist)
            return None

        for npc_id, title, angles in PLACED:
            spot = find_spot(x0, y0, z0, angles)
            if not spot:
                # Гейткипер на постаменте (Classic, Essence): от него ступенька выше MAX_STEP_DZ — путь от открытой земли перед ним.
                for r in (48, 64, 80, 96):
                    for rel in (0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180):
                        a = math.radians(facing + rel)
                        sx, sy = round(x0 + r * math.cos(a)), round(y0 + r * math.sin(a))
                        c = geo.cell(sx, sy, z0)
                        if c and c[1] == NSWE_ALL and abs(c[0] - z0) <= MAX_POINT_DZ:
                            spot = find_spot(sx, sy, c[0], angles)
                        if spot:
                            break
                    if spot:
                        break
            if not spot:
                failed.append(f"{town}: не нашлось места для {title}")
                continue
            placed.append(spot)
            x, y, z, rel, dist = spot
            lines.append(f'\t\t<npc id="{npc_id}" x="{x}" y="{y}" z="{z}" heading="{heading}" {respawn} /> <!-- {title}: {town} -->')
            by_npc[npc_id].append("\t" + lines[-1])
            report.append(f"{town:<24} {title:<14} {rel:+4d}° {dist:>3} от гейткипера, z {z} (гейткипер {z0})")

    xml = "\n".join([
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<list {list_attrs}xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../../xsd/spawns.xsd">',
        "\t<!-- L2Hub: баффер и GM-шоп у гейткиперов городов. Сгенерировано tools/town_npcs.py. -->",
        *(switch_spawn(by_npc) if switches else ['\t<spawn name="L2HubTownNpcs">', *lines, "\t</spawn>"]),
        "</list>",
        "",
    ])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(xml.encode("utf-8"))
    print("\n".join(report))
    print(f"\n{len(lines)} NPC -> {out}")
    if failed:
        print("НЕ РАССТАВЛЕНО:\n  " + "\n  ".join(failed))
        return 1
    return 0


def switch_spawn(by_npc: dict) -> list:
    """Спавн с выключателями (--switches): у каждого NPC своя группа, которую сам Mobius не спавнит;
    скрипт custom/L2HubTown спавнит группы, включённые в config/Custom/L2HubTown.ini (имя группы — ключ)."""
    out = ['\t<spawn name="L2HubTownNpcs" ai="L2HubTown">']
    for npc_id, _, _ in PLACED:
        out += [f'\t\t<group name="{SWITCHES[npc_id]}" spawnByDefault="false">', *by_npc[npc_id], "\t\t</group>"]
    return out + ["\t</spawn>"]


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--switches"]
    if len(args) != 3:
        sys.exit(__doc__)
    sys.exit(main(Path(args[0]), Path(args[1]), Path(args[2]), "--switches" in sys.argv))
