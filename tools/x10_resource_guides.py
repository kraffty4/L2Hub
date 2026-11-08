"""Подсказки сборщика: реальные дроп/спойл, спавны и проверенные точки охоты H5."""
import argparse
import math
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

MATERIALS = {1865, 1867, 1868, 1869, 1870, 1872}


def generate(data: Path, spots_file: Path, output: Path):
    monsters = {}
    for file in (data / "stats/npcs").glob("*.xml"):
        for npc in ET.parse(file).getroot().findall("npc"):
            if npc.get("type") != "Monster":
                continue
            # Подсказка по обычному дропу доступна любому классу; спойл тоже
            # двигает прогресс, но не является обязательным для этой точки.
            drops = {int(item.get("id")) for item in npc.findall(".//dropLists/drop//item") if item.get("id", "").isdigit()} & MATERIALS
            if drops:
                monsters[int(npc.get("id"))] = int(npc.get("level")), drops
    regions = defaultdict(list)
    for spot in ET.parse(spots_file).getroot().findall("spot"):
        regions[spot.get("region")].append(spot.attrib)
    found = defaultdict(dict)
    for file in (data / "spawns").rglob("*.xml"):
        root = ET.parse(file).getroot()
        if root.get("enabled", "true").lower() == "false":
            continue
        for spawn in root.findall("spawn"):
            territory = spawn.find("territory")
            nodes = [] if territory is None else territory.findall("node")
            for npc in spawn.findall("npc"):
                info = monsters.get(int(npc.get("id", "0")))
                if info is None:
                    continue
                if npc.get("x") is not None and npc.get("y") is not None:
                    x, y, z = int(npc.get("x")), int(npc.get("y")), int(npc.get("z", "0"))
                elif nodes:
                    x = sum(int(n.get("x")) for n in nodes) / len(nodes)
                    y = sum(int(n.get("y")) for n in nodes) / len(nodes)
                    z = (int(territory.get("minZ")) + int(territory.get("maxZ"))) / 2
                else:
                    continue
                options = [s for s in regions[file.parent.name] if abs(int(s["z"]) - z) <= 600 and abs(int(s["level"]) - info[0]) <= 6]
                if not options:
                    continue
                spot = min(options, key=lambda s: math.hypot(int(s["x"]) - x, int(s["y"]) - y))
                if math.hypot(int(spot["x"]) - x, int(spot["y"]) - y) > 2500:
                    continue
                for item in info[1]:
                    found[item][spot["x"], spot["y"]] = spot
    root = ET.Element("guides")
    for item in sorted(MATERIALS):
        if not found[item]:
            raise ValueError(f"Нет подтверждённых мест добычи {item}")
        selected = {}
        for level in (1, *range(5, 86, 5)):
            spot = min(found[item].values(), key=lambda s: abs(int(s["level"]) - level) + (100 if int(s["min"]) > level + 3 else 0))
            selected[spot["x"], spot["y"]] = spot
        for spot in selected.values():
            ET.SubElement(root, "source", item=str(item), **{k: spot[k] for k in ("name", "region", "level", "min", "max", "x", "y", "z")})
    ET.indent(root, space="\t")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")
    print(f"Verified material guides: {len(root)} destinations for {len(MATERIALS)} materials")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path)
    parser.add_argument("spots", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.data, args.spots, args.output)
