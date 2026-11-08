"""Собрать слабых ивентовых мобов без дропа/опыта из параметров H5, без изменения исходников Mobius."""
import argparse
import copy
import statistics
import xml.etree.ElementTree as ET
from pathlib import Path


def generate(data: Path, output: Path):
    candidates = []
    for file in (data / "stats/npcs").glob("*.xml"):
        for npc in ET.parse(file).getroot().findall("npc"):
            hp = npc.find("stats/vitals")
            attack = npc.find("stats/attack")
            acquire = npc.find("acquire")
            if npc.get("type") != "Monster" or hp is None or attack is None or acquire is None:
                continue
            if int(acquire.get("exp", "0")) <= 0 or npc.find("minions") is not None:
                continue
            multiplier = npc.find("skillList/skill[@id='4408']")
            if multiplier is not None and multiplier.get("level") != "1":
                continue
            candidates.append(npc)
    root = ET.Element("list", {"xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance", "xsi:noNamespaceSchemaLocation": "../../../xsd/npcs.xsd"})
    for level in (1, *range(5, 86, 5)):
        distance = min(abs(int(n.get("level")) - level) for n in candidates)
        pool = [n for n in candidates if abs(int(n.get("level")) - level) == distance]
        median = statistics.median(float(n.find("stats/vitals").get("hp")) for n in pool)
        source = min(pool, key=lambda n: abs(float(n.find("stats/vitals").get("hp")) - median))
        npc = ET.SubElement(root, "npc", id=str(900500 + level), displayId=source.get("id"), level=str(level), type="Monster", name="Event Monster", usingServerSideName="true", title="Event", usingServerSideTitle="true")
        ET.SubElement(npc, "acquire", exp="0", sp="0")
        stats = copy.deepcopy(source.find("stats"))
        stats.find("vitals").set("hp", str(max(50, round(float(stats.find("vitals").get("hp")) * 0.6))))
        stats.find("vitals").set("hpRegen", "0")
        for key in ("physical", "magical"):
            if key in stats.find("attack").attrib:
                stats.find("attack").set(key, str(max(1, round(float(stats.find("attack").get(key)) * 0.2))))
        npc.append(stats)
        ET.SubElement(npc, "status", undying="false", canBeSown="false")
        ET.SubElement(npc, "ai", aggroRange="600", clanHelpRange="0", isAggressive="true")
        collision = source.find("collision")
        if collision is not None:
            npc.append(copy.deepcopy(collision))
    hq = ET.SubElement(root, "npc", id="900600", displayId="35062", level="1", type="Folk", name="Defence Headquarters", usingServerSideName="true", title="Integrity 100%", usingServerSideTitle="true")
    ET.SubElement(hq, "acquire", exp="0", sp="0")
    hq_stats = copy.deepcopy(root[0].find("stats"))
    hq_stats.find("vitals").set("hp", "100000")
    hq_stats.find("vitals").set("hpRegen", "0")
    hq.append(hq_stats)
    ET.SubElement(hq, "status", undying="true", canBeSown="false")
    ET.SubElement(hq, "ai", aggroRange="0", clanHelpRange="0", isAggressive="false")
    ET.SubElement(hq, "collision").extend([ET.Element("radius", normal="25"), ET.Element("height", normal="40")])
    ET.indent(root, space="\t")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")
    print(f"Generated {len(root)} event templates without ordinary drops or XP")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.data, args.output)
