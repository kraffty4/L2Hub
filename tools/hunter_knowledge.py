"""База знаний охотников для мозгов («Разум 2.0», docs/MIND.md, раздел 3): факты своей сборки, без выдумок.

Пишет в модуль фантомов L2HubHunterKnowledge.json — его читает launcher/l2hub/brain.py и кладёт охотнику рядом с игроком
кусок под него в запрос (Brain.knowledge). Что внутри:
  exp       — правило опыта за разницу уровней, как его считает сервер (Attackable.calculateExpAndSp сборки)
  levels    — опыт до уровня (stats/players/experience.xml); deathLoss — сколько % уровня снимает смерть (experienceLoss.xml)
  grades    — грейды: с какого уровня (скилл Expertise; нет его — таблица охотников GRADE_LEVELS), дешёвые сеты брони
              по типу с ценой магазина, разброс цен обычного оружия, сосы
  classes   — ветки профессий (PlayerClass.java и stats/players/classList.xml): имя, маг ли, куда дальше
  spots     — споты охотников (L2HubHuntSpots.xml) по имени: мобы рядом с уровнем и агрессией, опыт и HP моба, адена
              и ценный дроп за убийство, спойл, рейд-боссы рядом
  raids     — рейд-боссы мира (без эпиков) с уровнем и ориентиром
Результат детерминирован. Обычно его пишет tools/fake_players.py вместе со спотами; отдельно, по готовым спотам модуля:
  python -X utf8 -I tools/hunter_knowledge.py <папка data сборки> <папка data модуля>
"""
import collections
import json
import math
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fake_players as fp  # noqa: E402

MOB_NEAR = 500          # мобы спота: в радиусе спота плюс столько
MOB_NEAR_Z = 500
MOB_LEVELS = 3          # и уровнем не дальше чем на столько от разброса спота
SPOT_MOBS = 4           # мобов в справке спота
SPOT_DROPS = 3          # ценного дропа и спойла
RAID_NEAR = 5000        # рейд-босс «рядом со спотом»
RAID_NEAR_Z = 1500
RAID_PLACE = 8000       # ориентир рейд-босса — ближайший спот или точка телепорта не дальше этого
RAID_SKIP_DIRS = {"Custom"}
SETS_PER_TYPE = 3       # дешёвых сетов на тип брони
DROP_MAX_KILLS = 1000   # дроп реже, чем раз на столько убийств, в справку не идёт: за час охотник бьёт сотню-другую мобов
SPOIL_MIN = 1.0         # и спойл с шансом ниже этого, %
CELL = 4096             # сетка для поиска мобов у спота


def _float(text, default=0.0) -> float:
    try:
        return float(text)
    except (TypeError, ValueError):
        return default


def exp_rule(build: Path) -> dict:
    """Как сервер режет опыт за разницу уровней — по коду Attackable.calculateExpAndSp сборки (у CT, Classic и Essence он разный)."""
    src = next((f for f in sorted((build / "java").rglob("Attackable.java")) if "actor" in f.parts), None)
    if src is None:
        return {"rule": "unknown"}
    text = src.read_text(encoding="utf-8")
    start = text.find("double[] calculateExpAndSp(")
    body = text[start:text.find("calculateOverhitExp", start)] if start >= 0 else ""
    if "MONSTER_EXP_MAX_LEVEL_DIFFERENCE" in body:
        # CT: опыт целиком, пока разница меньше MonsterExpMaxLevelDifference (Rates.ini), дальше — ноль.
        ini = (build / "dist/game/config/Rates.ini").read_text(encoding="utf-8", errors="replace")
        m = re.search(r"^\s*MonsterExpMaxLevelDifference\s*=\s*(\d+)", ini, re.M)
        return {"rule": "window", "max": int(m.group(1)) if m else 11}
    m = re.search(r"levelDiff\s*>\s*(\d+)\)", body)
    if m and "switch (levelDiff)" in body:
        # Classic: персонаж выше моба на from+1 и больше — опыт умножается по таблице.
        cases = {int(a): float(b) for a, b in re.findall(r"case\s+(\d+):\s*\{?\s*mul\s*=\s*([\d.]+)", body)}
        default = re.search(r"default:\s*\{?\s*mul\s*=\s*([\d.]+)", body)
        return {"rule": "grey", "from": int(m.group(1)) + 1, "mul": {str(k): v for k, v in sorted(cases.items())},
                "else": float(default.group(1)) if default else 0.0}
    m = re.search(r"\(charLevel\s*-\s*getLevel\(\)\)\s*>\s*(\d+)", body)
    if m:
        # Essence: опыт целиком, пока моб ниже не больше чем на max уровней.
        return {"rule": "below", "max": int(m.group(1))}
    return {"rule": "unknown"}


def level_tables(data: Path) -> tuple[list[int], list[float]]:
    """Опыт до уровня (индекс — уровень) и % уровня, который снимает смерть."""
    levels, loss = {}, {}
    for e in ET.parse(data / "stats/players/experience.xml").getroot().iter("experience"):
        levels[int(e.get("level"))] = int(e.get("tolevel"))
    for e in ET.parse(data / "stats/players/experienceLoss.xml").getroot().iter("experience"):
        loss[int(e.get("level"))] = float(e.get("val"))
    top = max(levels)
    return [levels.get(i, 0) for i in range(top + 1)], [loss.get(i, 0.0) for i in range(top + 1)]


def grade_levels(d: fp.Data) -> tuple[list[tuple[str, int]], str]:
    """С какого уровня грейд: по уровням скилла Expertise в деревьях классов; у Classic и Essence его нет — таблица охотников."""
    names = [g for g, _ in fp.GRADE_LEVELS if g != "NONE"]
    expertise = [sid for sid, s in d.skills.items() if (s["name"] or "").startswith("Expertise") and "Rune" not in (s["name"] or "")]
    got = {}
    for tree in d.trees.values():
        for sid, slv, glv in tree:
            if sid in expertise and slv <= len(names):
                got[names[slv - 1]] = min(glv, got.get(names[slv - 1], 999))
    if got:
        return [("NONE", 1)] + sorted(got.items(), key=lambda x: x[1]), "expertise"
    return list(fp.GRADE_LEVELS), "hunters"


def grades(d: fp.Data) -> tuple[list[dict], str]:
    table, source = grade_levels(d)
    out = []
    for g, lvl in table:
        sets = collections.defaultdict(list)
        for parts in d.armor_sets().get(g, []):
            chest = d.items.get(parts.get("chest", 0), {})
            # « - » — варианты сета под класс (Dynasty Breast Plate - Bard): тот же сет, в справке лишние.
            if not chest.get("armor_type") or " - " in (chest.get("name") or "") or any(w in (chest.get("name") or "") for w in fp.SKIP_WEAPON_WORDS):
                continue
            price = sum(int(d.items.get(i, {}).get("price") or 0) for slot, i in parts.items() if slot != "shield")
            if price > 0:
                sets[chest["armor_type"]].append((price, chest["name"]))
        weapons = sorted(int(it["price"]) for it in d.items.values() if it["type"] == "Weapon" and it.get("crystal_type", "NONE") == g
                         and fp.Data.plain(it) and it.get("weapon_type") not in ("FISHINGROD", "ETC", None)
                         and not any(w in (it["name"] or "") for w in fp.SKIP_WEAPON_WORDS))
        if not sets and not weapons:
            continue
        shot = None
        try:
            sid = d.soulshot(g)
            shot = [d.items[sid]["name"], int(d.items[sid].get("price") or 0)] if sid in d.items else None
        except KeyError:
            pass
        out.append({"grade": g, "level": lvl,
                    "sets": {kind: [[name, price] for price, name in sorted(found)[:SETS_PER_TYPE]] for kind, found in sorted(sets.items())},
                    # Цены оружия — от 10-го процентиля до медианы: в грейде попадаются предметы-заглушки за 1 адену.
                    "weapons": [weapons[len(weapons) // 10], round(statistics.median(weapons))] if weapons else [],
                    "shot": shot})
    return out, source


def classes(d: fp.Data, build: Path) -> dict:
    """Ветки профессий по имени класса из enum PlayerClass (так его шлёт сервер): имя, маг ли, ступень, куда дальше."""
    src = next(iter(sorted((build / "java").rglob("PlayerClass.java"))), None)
    if src is None:
        return {}
    enum = {}
    for name, cid, mage in re.findall(r"^\s*([A-Z][A-Z0-9_]*)\((\d+),\s*(true|false)", src.read_text(encoding="utf-8"), re.M):
        enum[int(cid)] = (name, mage == "true")
    children = collections.defaultdict(list)
    for cid, (_, parent) in d.classes.items():
        if parent >= 0:
            children[parent].append(cid)

    def tier(cid: int) -> int:
        parent = d.classes[cid][1]
        return 0 if parent < 0 else 1 + tier(parent)

    out = {}
    for cid, (title, _) in sorted(d.classes.items()):
        if cid not in enum:
            continue
        name, mage = enum[cid]
        out[name] = {"name": title, "mage": mage, "tier": tier(cid),
                     "next": [d.classes[c][0] for c in sorted(children.get(cid, []))]}
    return out


def mob_facts(d: fp.Data, npc: ET.Element) -> dict:
    """Что игрок узнаёт о мобе: уровень, агрессия, опыт и HP, адена и ожидаемая ценность дропа и спойла за убийство."""
    ai = npc.find("ai")
    aggro = ai is not None and ai.get("isAggressive") != "false" and int(ai.get("aggroRange") or 0) > 0
    acquire = npc.find("acquire")
    vitals = npc.find("stats/vitals")
    adena, drops, spoil = 0.0, {}, {}
    for group in npc.findall("dropLists/drop/group"):
        gc = _float(group.get("chance"), 100) / 100
        for it in group.findall("item"):
            iid = int(it.get("id"))
            per_kill = gc * _float(it.get("chance")) / 100 * (int(it.get("min", "1")) + int(it.get("max", "1"))) / 2
            if iid == 57:
                adena += per_kill
            elif iid in d.items:
                drops[iid] = drops.get(iid, 0) + per_kill
    for it in npc.findall("dropLists/drop/item"):  # дроп без групп
        iid = int(it.get("id"))
        per_kill = _float(it.get("chance")) / 100 * (int(it.get("min", "1")) + int(it.get("max", "1"))) / 2
        if iid == 57:
            adena += per_kill
        elif iid in d.items:
            drops[iid] = drops.get(iid, 0) + per_kill
    for it in npc.findall("dropLists/spoil/item"):
        iid = int(it.get("id"))
        if iid in d.items:
            spoil[iid] = spoil.get(iid, 0) + _float(it.get("chance")) / 100 * (int(it.get("min", "1")) + int(it.get("max", "1"))) / 2
    return {"name": npc.get("name"), "level": int(npc.get("level", "0")), "aggro": aggro,
            "exp": int(_float(acquire.get("exp") if acquire is not None else 0)),
            "hp": round(_float(vitals.get("hp") if vitals is not None else 0)), "adena": adena, "drops": drops, "spoil": spoil}


def spawn_points(data: Path, skip_dirs: set[str]) -> list[tuple[int, int, int, int, int]]:
    """(x, y, z, id NPC, сколько) всех спавнов; без точки — в центре территории группы, как у спотов охотников."""
    out = []
    for f in sorted((data / "spawns").rglob("*.xml")):
        rel = f.relative_to(data / "spawns")
        if rel.parts[0] in skip_dirs:
            continue
        for sp in ET.parse(f).getroot().iter("spawn"):
            terr = sp.find(".//territory")
            centre = None
            if terr is not None:
                nodes = [(int(n.get("x")), int(n.get("y"))) for n in terr.iter("node")]
                if nodes:
                    centre = (round(statistics.mean(x for x, _ in nodes)), round(statistics.mean(y for _, y in nodes)),
                              (int(terr.get("minZ", "0")) + int(terr.get("maxZ", "0"))) // 2)
            for n in sp.iter("npc"):  # и внутри <group>: так лежат рейд-боссы Classic (RaidbossSpawns.xml)
                if n.get("x") is not None:
                    out.append((int(n.get("x")), int(n.get("y")), int(n.get("z")), int(n.get("id")), int(n.get("count", "1"))))
                elif centre is not None:
                    out.append((*centre, int(n.get("id")), int(n.get("count", "1"))))
    return out


def raid_sql(build: Path) -> list[tuple[int, int, int, int, int]]:
    """У CT рейд-боссы спавнятся не из XML, а из таблицы raidboss_spawnlist (db_installer сборки); у Classic и Essence её нет."""
    sql = build / "dist/db_installer/sql/game/raidboss_spawnlist.sql"
    if not sql.exists():
        return []
    rows = re.findall(r"^\((\d+),(-?\d+),(-?\d+),(-?\d+),", sql.read_text(encoding="utf-8", errors="replace"), re.M)
    return [(int(x), int(y), int(z), int(nid), 1) for nid, x, y, z in rows]


def _top(items: dict, d: fp.Data, n: int, chance_word: str) -> list[list]:
    """Самое ценное по ожиданию за убийство: [имя, цена магазина, сколько убийств на штуку (дроп) или % (спойл)]."""
    least = 1 / DROP_MAX_KILLS if chance_word == "kills" else SPOIL_MIN / 100
    scored = sorted(((per_kill * int(d.items[iid].get("price") or 0), iid, per_kill) for iid, per_kill in items.items()
                     if int(d.items[iid].get("price") or 0) > 0 and per_kill >= least), reverse=True)[:n]
    if chance_word == "kills":
        return [[d.items[iid]["name"], int(d.items[iid]["price"]), max(1, round(1 / per_kill))] for _, iid, per_kill in scored if per_kill > 0]
    return [[d.items[iid]["name"], int(d.items[iid]["price"]), round(min(per_kill, 1) * 100, 1)] for _, iid, per_kill in scored]


def spots_and_raids(d: fp.Data, spots: list[dict]) -> tuple[dict, list]:
    facts = {}
    grid = collections.defaultdict(list)
    raids = []
    for x, y, z, nid, count in spawn_points(d.data, fp.HUNT_SKIP_DIRS | RAID_SKIP_DIRS):
        npc = d.npcs.get(nid)
        if npc is None or npc.get("type") != "Monster":
            continue
        grid[(x // CELL, y // CELL)].append((x, y, z, nid, count))
    for x, y, z, nid, _ in spawn_points(d.data, RAID_SKIP_DIRS) + raid_sql(d.data.parents[2]):
        npc = d.npcs.get(nid)
        if npc is not None and npc.get("type") == "RaidBoss":
            raids.append((x, y, z, npc.get("name"), int(npc.get("level", "0"))))
    raids = sorted(set(raids), key=lambda r: (r[4], r[3], r[0], r[1]))

    def mob(nid: int) -> dict:
        if nid not in facts:
            facts[nid] = mob_facts(d, d.npcs[nid])
        return facts[nid]

    by_name: dict[str, dict] = {}
    for s in spots:
        reach = s["radius"] + MOB_NEAR
        counts = collections.Counter()
        for gx in range((s["x"] - reach) // CELL, (s["x"] + reach) // CELL + 1):
            for gy in range((s["y"] - reach) // CELL, (s["y"] + reach) // CELL + 1):
                for x, y, z, nid, count in grid.get((gx, gy), ()):
                    if abs(z - s["z"]) <= MOB_NEAR_Z and math.dist((x, y), (s["x"], s["y"])) <= reach \
                            and s["min"] - MOB_LEVELS <= mob(nid)["level"] <= s["max"] + MOB_LEVELS:
                        counts[nid] += count
        near = sorted({(r[3], r[4]) for r in raids if abs(r[2] - s["z"]) <= RAID_NEAR_Z and math.dist(r[:2], (s["x"], s["y"])) <= RAID_NEAR},
                      key=lambda r: (r[1], r[0]))
        entry = by_name.setdefault(s["name"], {"region": fp.camel_words(s["region"]), "min": s["min"], "max": s["max"],
                                               "counts": collections.Counter(), "raids": set()})
        entry["min"], entry["max"] = min(entry["min"], s["min"]), max(entry["max"], s["max"])
        entry["counts"].update(counts)
        entry["raids"].update(near)

    out = {}
    for name in sorted(by_name):
        e = by_name[name]
        counts = e.pop("counts")
        total = sum(counts.values())
        if not total:
            continue
        # Мобы одного имени (разные id с тем же именем и уровнем) — вместе; свита боссов без опыта (Susceptor у Core) — отдельно.
        mobs = collections.OrderedDict()
        for nid, count in sorted(counts.items(), key=lambda x: (-x[1], x[0])):
            f = mob(nid)
            key = (f["name"], f["level"], f["exp"] > 0)
            if key in mobs:
                mobs[key][3] += count
            else:
                mobs[key] = [f["name"], f["level"], int(f["aggro"]), count, f["exp"], f["hp"]]
        drops, spoil, adena, exp = collections.Counter(), collections.Counter(), 0.0, 0.0
        for nid, count in counts.items():
            f, share = mob(nid), count / total
            adena += f["adena"] * share
            exp += f["exp"] * share
            for iid, v in f["drops"].items():
                drops[iid] += v * share
            for iid, v in f["spoil"].items():
                spoil[iid] += v * share
        aggro = sum(count for nid, count in counts.items() if mob(nid)["aggro"]) / total
        out[name] = {"region": e["region"], "min": e["min"], "max": e["max"], "mobs": sorted(mobs.values(), key=lambda m: (-m[3], m[1], m[0]))[:SPOT_MOBS],
                     "count": total, "aggro": round(aggro, 2), "exp": round(exp), "adena": round(adena),
                     "drop": _top(drops, d, SPOT_DROPS, "kills"), "spoil": _top(spoil, d, SPOT_DROPS, "pct"),
                     "raids": [list(r) for r in sorted(e["raids"], key=lambda r: (r[1], r[0]))]}
    return out, raids


def raid_places(raids: list, spots: list[dict], marks: list[tuple[str, int, int, int]]) -> list[list]:
    """[имя, уровень, ориентир]: ближайший спот охотников или точка телепорта (по высоте разница вдвое), если ближе RAID_PLACE."""
    points = [(s["name"], s["x"], s["y"], s["z"]) for s in spots] + list(marks)
    out, seen = [], set()
    for x, y, z, name, level in raids:
        near = min(((math.dist((x, y), p[1:3]) + 2 * abs(z - p[3]), p[0]) for p in points), default=None)
        place = near[1] if near and near[0] <= RAID_PLACE else ""
        if (name, level, place) not in seen:
            seen.add((name, level, place))
            out.append([name, level, place])
    return out


def build(d: fp.Data, spots: list[dict]) -> dict:
    build_dir = d.data.parents[2]
    levels, loss = level_tables(d.data)
    grade_list, grade_source = grades(d)
    spot_facts, raids = spots_and_raids(d, spots)
    return {"version": 1,
            "comment": "L2Hub: база знаний охотников для мозгов (brain.py). Сгенерировано tools/hunter_knowledge.py из датапака сборки.",
            "exp": exp_rule(build_dir), "levels": levels, "deathLoss": loss,
            "grades": grade_list, "gradeSource": grade_source,
            "classes": classes(d, build_dir), "spots": spot_facts, "raids": raid_places(raids, spots, fp.landmarks(d.data))}


def write(d: fp.Data, spots: list[dict], out: Path) -> dict:
    """L2HubHunterKnowledge.json в out; LF, ключи по порядку — вывод совпадает байт в байт, пока не изменились данные."""
    kb = build(d, spots)
    (out / "L2HubHunterKnowledge.json").write_bytes((json.dumps(kb, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
    return kb


def read_spots(path: Path) -> list[dict]:
    """Споты из готового L2HubHuntSpots.xml модуля."""
    ints = ("level", "min", "max", "x", "y", "z", "radius", "mobs")
    return [{k: int(v) if k in ints else v for k, v in s.attrib.items()} for s in ET.parse(path).getroot().iter("spot")]


def main(data: Path, out: Path) -> int:
    d = fp.Data(data)
    kb = write(d, read_spots(out / "L2HubHuntSpots.xml"), out)
    size = (out / "L2HubHunterKnowledge.json").stat().st_size
    empty = sorted(name for name, s in kb["spots"].items() if not s["exp"])
    if empty:
        print(f"споты, где мобы не дают опыта ({len(empty)}): {', '.join(empty)}")
    print(f"опыт: {kb['exp']}; грейдов {len(kb['grades'])} ({kb['gradeSource']}), классов {len(kb['classes'])}, "
          f"спотов {len(kb['spots'])}, рейд-боссов {len(kb['raids'])}; {size // 1024} КБ")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2])))
