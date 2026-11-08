"""Проверенные точки из списка H5, плотные пачки и отдельные усиленные мобы с аденой ×2.

Читает только датапак и геодату, пишет отдельные XML модуля. Рейты и исходные NPC не меняет.
"""
import argparse
import copy
import math
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

import town_npcs as tn


# Уровень здесь — ориентир выбора. В каталог всегда попадает фактический уровень NPC.
# Повторяющиеся строки прежнего исследования объединены по локации.
AREAS = [
    ("cruma", "Башня Крумы", "Cruma Tower", 42, None, (17776, 113968, -11671), "solo", 55),
    ("alligator", "Остров Аллигаторов", "Alligator Island", 43, "Alligator Island", None, "solo", 85),
    ("mirrors", "Лес Зеркал", "Forest of Mirrors", 44, "Forest of Mirrors", None, "solo", 85),
    ("spores", "Море Спор", "Sea of Spores", 46, "Sea Of Spores", None, "spoil", 85),
    ("glory", "Равнины Славы", "Plains of Glory", 48, "Plains Of Glory", None, "spoil", 85),
    ("timak", "Аванпост Тимак", "Timak Outpost", 44, "Timak Outpost", None, "solo", 85),
    ("labyrinth", "Ледяной Лабиринт", "Frozen Labyrinth", 56, None, (112214, -100762, -3640), "spoil", 85),
    ("cemetery", "Кладбище", "Cemetery", 54, "Cemetery", None, "undead", 85),
    ("massacre", "Поля Бойни", "Fields of Massacre", 61, "Fields Of Massacre", None, "undead", 85),
    ("toi", "Башня Дерзости", "Tower of Insolence", 64, "Tower Of Insolence", None, "party", 85),
    ("saints", "Долина Святых", "Valley of Saints", 65, "Valley Of Saints", None, "mage", 85),
    ("argos", "Стена Аргоса", "Wall of Argos", 71, "Wall Of Argos", None, "spoil", 85),
    ("swamp", "Раскалённые Топи", "Blazing Swamp", 68, "Blazing Swamp", None, "spoil", 85),
    ("springs", "Горячие Источники", "Hot Springs", 75, "Hot Springs", None, "spoil", 85),
    ("pavel", "Заброшенная Мастерская", "Pavel Ruins", 73, "Pavel Ruins", None, "solo", 85),
    ("silent", "Долина Безмолвия", "Silent Valley", 75, "Silent Valley", None, "spoil", 85),
    ("tomb", "Императорская Гробница", "Imperial Tomb", 77, "Imperial Tomb", None, "party", 85),
    ("varka", "Казармы Варка Силенос", "Varka Silenos Barracks", 78, "Varka Slenos Outpost", None, "party", 85),
    ("ketra", "Аванпост Орков Кетра", "Ketra Orc Outpost", 78, "Ketra Orc Outpost", None, "party", 85),
    ("forge", "Кузница Богов", "Forge of the Gods", 78, "Forge of the Gods", None, "party", 85),
    ("giants", "Пещера Великанов", "Giant's Cave", 81, "Giants Cave", None, "party", 85),
    ("monastery", "Монастырь Безмолвия", "Monastery of Silence", 83, "Solina", None, "party", 85),
    ("hellbound", "Хеллбаунд — первые области", "Hellbound — first areas", 81, None, (-11272, 236464, -3248), "conditional", 85),
    ("dragon", "Долина Драконов", "Dragon Valley", 83, "Dragon Valley", None, "party", 85),
    ("stakato", "Гнездо Стакато", "Stakato Nest", 83, "Spiked Stakato", None, "party", 85),
    ("antharas", "Логово Антараса", "Antharas's Lair", 83, None, (131557, 114509, -3712), "party", 85),
    ("annihilation", "Семя Уничтожения", "Seed of Annihilation", 85, None, (-181217, 186711, -10528), "conditional", 85),
]
ADENA_AREAS = {"alligator", "massacre", "springs", "giants"}
PACKS_PER_AREA = 12
ENTRY_CLEARANCE = 900


def farm_point(geo, x0, y0, z0, x, y):
    """Проходимый склон: проверяем каждый переход, не требуем городской ровной площадки."""
    current = geo.cell(x0, y0, z0)
    if current is None:
        return None
    cell = ((x0 - tn.WORLD_MIN_X) // tn.CELL, (y0 - tn.WORLD_MIN_Y) // tn.CELL)
    steps = max(1, round(math.dist((x0,y0),(x,y)) / (tn.CELL / 2)))
    for i in range(1, steps + 1):
        px,py = round(x0+(x-x0)*i/steps),round(y0+(y-y0)*i/steps)
        next_cell = ((px - tn.WORLD_MIN_X) // tn.CELL, (py - tn.WORLD_MIN_Y) // tn.CELL)
        if next_cell == cell:
            continue
        direction=tn._dir(next_cell[0]-cell[0],next_cell[1]-cell[1])
        target=geo.cell(px,py,current[0])
        if target is None or current[1]&direction != direction or abs(target[0]-current[0]) > tn.MAX_STEP_DZ:
            return None
        cell,current=next_cell,target
    target=geo.cell(x,y,current[0])
    return target[0] if target is not None and target[1] == 15 and abs(target[0]-z0) <= 600 else None


def ordinary(npc):
    acquire = npc.find("acquire")
    return npc.get("type") == "Monster" and acquire is not None and float(acquire.get("exp", "0")) > 0 and npc.find("minions") is None and "Quest" not in npc.get("title", "")


def elite(source):
    """Меняем количество адены, а не шанс/группу: сервер применит свой текущий рейт один раз."""
    npc = copy.deepcopy(source)
    npc.set("id", str(920000 + int(source.get("id"))))
    npc.set("displayId", source.get("displayId", source.get("id")))
    npc.set("usingServerSideTitle", "true")
    npc.set("title", "Adena x2")
    npc.find("stats/vitals").set("hp", f"{float(source.find('stats/vitals').get('hp')) * 2:.6f}")
    for key in ("physical", "magical"):
        attack = npc.find("stats/attack")
        if key in attack.attrib:
            attack.set(key, f"{float(attack.get(key)) * 1.5:.6f}")
    adena = npc.findall(".//dropLists/drop//item[@id='57']")
    if not adena:
        raise ValueError("Нет обычного дропа адены: " + source.get("id"))
    for item in adena:
        for key in ("min", "max"):
            item.set(key, str(int(item.get(key)) * 2))
    ai = npc.find("ai")
    if ai is None:
        ai = ET.SubElement(npc, "ai")
    ai.set("isAggressive", "true")
    ai.set("aggroRange", "350")
    ai.set("clanHelpRange", "300")
    clan_list = ai.find("clanList")
    if clan_list is not None:
        ai.remove(clan_list)
    ET.SubElement(ET.SubElement(ai, "clanList"), "clan").text = "L2HubAdena"
    return npc


def generate(data, geodata, spots_file, output):
    monsters = {int(n.get("id")): n for file in sorted((data / "stats/npcs").glob("*.xml")) for n in ET.parse(file).getroot().findall("npc") if ordinary(n)}
    geo = tn.Geo(geodata)
    points = []
    for file in sorted((data / "spawns").rglob("*.xml")):
        root = ET.parse(file).getroot()
        if root.get("enabled", "true") == "false" or "Custom" in file.relative_to(data / "spawns").parts:
            continue
        for spawn in root.iter("spawn"):
            terr = spawn.find("territory")
            nodes = [] if terr is None else terr.findall("node")
            for npc in spawn.findall("npc"):
                iid = int(npc.get("id", "0"))
                if iid not in monsters:
                    continue
                if npc.get("x") is not None:
                    point = (int(npc.get("x")), int(npc.get("y")), int(npc.get("z")))
                elif nodes:
                    point = (round(sum(int(n.get("x")) for n in nodes) / len(nodes)), round(sum(int(n.get("y")) for n in nodes) / len(nodes)), (int(terr.get("minZ")) + int(terr.get("maxZ"))) // 2)
                else:
                    continue
                points.append((iid, *point))
    hell_file = data / "scripts/ai/areas/Hellbound/hellboundSpawns.xml"
    for npc in ET.parse(hell_file).getroot().iter("npc"):
        iid = int(npc.get("id", "0"))
        if iid in monsters:
            for loc in npc.iter("location"):
                points.append((iid, int(loc.get("x")), int(loc.get("y")), int(loc.get("z"))))
    buckets = defaultdict(list)
    for p in dict.fromkeys(points):
        buckets[p[1] // 4096, p[2] // 4096].append(p)

    def nearby(x, y, z, radius):
        return [p for bx in range((x - radius) // 4096, (x + radius) // 4096 + 1) for by in range((y - radius) // 4096, (y + radius) // 4096 + 1) for p in buckets[bx, by] if math.hypot(p[1] - x, p[2] - y) <= radius and abs(p[3] - z) < 600]

    def ground(p):
        # Центр территории в датапаке может попасть на стену; ищем открытую
        # клетку рядом. Для готовых точечных спавнов обычно хватает dx=dy=0.
        for distance in (0, 32, 64, 128, 256):
            for dx, dy in ((0, 0), (distance, 0), (-distance, 0), (0, distance), (0, -distance)):
                cell = geo.cell(p[1] + dx, p[2] + dy, p[3])
                if cell is not None and abs(cell[0] - p[3]) <= 600 and cell[1] == 15:
                    return (p[0], p[1] + dx, p[2] + dy, cell[0])
        return None

    def complete_packs(packs, avoid=None):
        original = list(packs)
        for distance in (300, 600, 900, 1200, 1600):
            for step in range(24):
                if len(packs) >= PACKS_PER_AREA:
                    return packs
                p = original[step % len(original)]
                angle = step * math.tau / 24
                x, y = round(p[1] + distance * math.cos(angle)), round(p[2] + distance * math.sin(angle))
                z = farm_point(geo, *p[1:], x, y)
                if z is not None and (avoid is None or math.dist((x,y),avoid[:2]) >= ENTRY_CLEARANCE) and all(math.dist((x, y), q[1:3]) >= 280 for q in packs):
                    packs.append((p[0], x, y, z))
        return packs

    def spread(candidates):
        selected = [candidates[0]]
        while len(selected) < PACKS_PER_AREA:
            choices = [p for p in candidates if p not in selected]
            if not choices:
                break
            point = max(choices, key=lambda p: min(math.dist(p[1:3], q[1:3]) for q in selected))
            if min(math.dist(point[1:3], q[1:3]) for q in selected) < 280:
                break
            selected.append(point)
        return complete_packs(selected)

    def safe_entry(center, packs):
        # Ищем открытый подход с геодатой и прямым проходом; добавленные мобы
        # не стоят в зоне агро вокруг точки телепорта. Обычные спавны также учитываются.
        native = nearby(*center, 4500)
        anchor = min(packs, key=lambda p: math.dist(p[1:3], center[:2]))[1:]
        best = None
        for radius in (0, 600, 1000, 1400, 1800, 2200, 2800):
            for step in range(32):
                angle = step * math.tau / 32
                x, y = round(center[0] + radius * math.cos(angle)), round(center[1] + radius * math.sin(angle))
                z = farm_point(geo, *center, x, y)
                if z is None:
                    z = farm_point(geo, *anchor, x, y)
                if z is None or min(math.dist((x,y), p[1:3]) for p in packs) < ENTRY_CLEARANCE:
                    continue
                clearance = min((math.dist((x,y), p[1:3]) for p in native if abs(p[3]-z)<400), default=1500)
                if best is None or clearance > best[0]:best = (clearance, x, y, z)
                if clearance >= ENTRY_CLEARANCE:
                    return x,y,z
        if best is None:
            # На узком участке заранее освобождаем проверенный вход, а не ставим
            # вокруг него пачки. Дополняем спавны по тем же NPC и слою геодаты.
            fixed = ground((0, *center))
            if fixed is None:
                fixed = min(packs, key=lambda p: math.dist(p[1:3], center[:2]))
            entry = fixed[1:]
            ids = {p[0] for p in packs}
            candidates = [ground(p) for p in native if p[0] in ids and math.dist(p[1:3], entry[:2]) >= ENTRY_CLEARANCE]
            candidates = [p for p in candidates if p is not None]
            if not candidates:
                raise ValueError('Нет спавнов вдали от входа: '+str(center))
            packs[:] = spread(candidates)
            for _ in range(5):
                packs[:] = [p for p in packs if math.dist(p[1:3], entry[:2]) >= ENTRY_CLEARANCE]
                if len(packs) >= PACKS_PER_AREA:
                    return entry
                packs[:] = complete_packs(packs, entry)
            raise ValueError('Не удалось освободить вход: '+str(center)+'; паков='+str(len(packs)))
        return best[1:]

    hunts = list(ET.parse(spots_file).getroot())
    root = ET.Element("spots")
    templates = {}
    chosen = {}
    for key, ru, en, target, pattern, seed, role, max_player in AREAS:
        options = [s for s in hunts if pattern and pattern.lower() in s.get("name").lower()]
        options.sort(key=lambda s: abs(int(s.get("level")) - target) * 100 - min(50, int(s.get("mobs"))))
        if options:
            center = tuple(int(options[0].get(k)) for k in ("x", "y", "z"))
            lo, hi = int(options[0].get("min")), int(options[0].get("max"))
        else:
            center = seed
            lo, hi = target - 5, target + 5
        pool = [p for p in nearby(*center, 2500) if lo <= int(monsters[p[0]].get("level")) <= hi]
        if not pool:
            pool = [p for p in nearby(*center, 6000) if abs(int(monsters[p[0]].get("level")) - target) <= 8]
        if not pool:
            raise ValueError("Нет реальных спавнов для " + key)
        pool.sort(key=lambda p: math.hypot(p[1] - center[0], p[2] - center[1]) + abs(int(monsters[p[0]].get("level")) - target) * 100)
        candidates = []
        for p in pool:
            fixed = ground(p)
            if fixed is not None:
                candidates.append(fixed)
            if len(candidates) >= 300:
                break
        if not candidates:
            raise ValueError("Нет точки с геодатой для " + key)
        # Точка паков — реальный спавн; вход берём из уже проверенного спота
        # или рядом с первым спавном на том же слое геодаты.
        if not options or math.hypot(center[0] - candidates[0][1], center[1] - candidates[0][2]) > 1800:
            center = candidates[0][1:]
        if key == "cruma":
            center = seed
        packs = spread(candidates)
        center = safe_entry(center, packs)
        levels = [int(monsters[p[0]].get("level")) for p in packs]
        node = ET.SubElement(root, "spot", id=key, kind="recommended", ru=ru, en=en, min=str(min(levels)), max=str(max(levels)), maxPlayer=str(max_player), role=role, conditional=str(role == "conditional").lower(), **dict(zip(("x", "y", "z"), map(str, center))))
        for iid, x, y, z in packs:
            ET.SubElement(node, "pack", npc=str(iid), x=str(x), y=str(y), z=str(z), count="3")
        chosen[key] = center
        print(f"{key}: NPC {min(levels)}-{max(levels)}, {len(packs)} packs", flush=True)

        if key not in ADENA_AREAS:
            continue
        alternatives = [s for s in options if math.hypot(int(s.get("x")) - center[0], int(s.get("y")) - center[1]) > 2000]
        if not alternatives:
            raise ValueError("Нет отдельного участка для адены " + key)
        alt = alternatives[0]
        ac = tuple(int(alt.get(k)) for k in ("x", "y", "z"))
        pool = sorted(nearby(*ac, 2500), key=lambda p: math.hypot(p[1] - ac[0], p[2] - ac[1]))
        candidates = []
        for p in pool:
            source = monsters[p[0]]
            if abs(int(source.get("level")) - target) > 5 or not source.findall(".//dropLists/drop//item[@id='57']"):
                continue
            fixed = ground(p)
            if fixed:
                candidates.append(fixed)
            if len(candidates) == 300:
                break
        if not candidates:
            raise ValueError("Нет паков с обычным дропом адены для " + key)
        packs = spread(candidates)
        ac = safe_entry(ac, packs)
        levels = [int(monsters[p[0]].get("level")) for p in packs]
        node = ET.SubElement(root, "spot", id="adena_" + key, kind="adena", ru=ru, en=en, min=str(min(levels)), max=str(max(levels)), maxPlayer="85", role="party", conditional="false", **dict(zip(("x", "y", "z"), map(str, ac))))
        for iid, x, y, z in packs:
            clone = templates.setdefault(iid, elite(monsters[iid]))
            ET.SubElement(node, "pack", npc=clone.get("id"), source=str(iid), x=str(x), y=str(y), z=str(z), count="6")
    output.mkdir(parents=True, exist_ok=True)
    ET.indent(root, space="\t")
    (output / "L2HubX10FarmSpots.xml").write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")
    npcs = ET.Element("list", {"xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance", "xsi:noNamespaceSchemaLocation": "../../../xsd/npcs.xsd"})
    npcs.extend(templates[k] for k in sorted(templates))
    ET.indent(npcs, space="\t")
    npc_file = output / "stats/npcs/custom/L2HubX10Adena.xml"
    npc_file.parent.mkdir(parents=True, exist_ok=True)
    npc_file.write_bytes(ET.tostring(npcs, encoding="utf-8", xml_declaration=True) + b"\n")
    print(f"Farm catalogue: {len(AREAS)} recommended, {len(ADENA_AREAS)} adena areas, {len(templates)} elite NPC templates")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path)
    parser.add_argument("geodata", type=Path)
    parser.add_argument("spots", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.data, args.geodata, args.spots, args.output)
