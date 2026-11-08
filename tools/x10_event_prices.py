"""Сверить магазин ивентов x10 с действующим GM-шопом, без изменения сервера.

Запускать из окружения launcher с L2HUB_HOME тестовой среды. --live-shop задаёт
папку установленного GM-шопа только для чтения. В отчёт не попадают профили и секреты.
"""
import argparse
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path

from l2hub import catalog, gmshop

# Только обычный S: без hero, эпика, sealed, S80/S84 и вариантов с SA.
ITEMS = (858, 889, 920, 959, 960, *range(6364, 6387), 6579, 6580, 7575)


def npc_only_items(folder: Path) -> set[int]:
    rejected = set()
    for file in folder.glob("*.xml"):
        for item in ET.parse(file).getroot().findall("item"):
            values = {node.get("name"): node.get("val", "") for node in item.findall("set")}
            icon, name = values.get("icon", "").lower(), item.get("name", "").lower()
            if values.get("for_npc", "").lower() == "true" or any(part in icon for part in ("weapon_monster", "weapon_mon_", "shield_mon_")) or name.startswith(("npc only", "monster only", "exclusive to monsters", "for monster", "for npc")):
                rejected.add(int(item.get("id")))
    return rejected


def audit(live_shop: Path, coin_value: int) -> dict:
    shop = gmshop.Shop(catalog.profile("ct26_highfive"), "en")
    rejected = npc_only_items(gmshop._source("ct26_highfive") / "dist/game/data/stats/items")
    if set(ITEMS) & rejected:
        raise ValueError(f"В ассортимент попали вещи только для NPC: {sorted(set(ITEMS) & rejected)}")
    prices = {}
    for lid in shop.titles:
        path = live_shop / f"{lid}.xml"
        if not path.is_file():
            continue
        rows, _ = gmshop.parse_list(path.read_bytes())
        for row in rows:
            if row["price"] is not None:
                value = row["price"] / row["count"]
                prices[row["item"]] = min(prices.get(row["item"], math.inf), value)
    rows = []
    for iid in ITEMS:
        if iid not in prices:
            raise ValueError(f"Нет действующей цены GM-шопа для {iid}; каталог не создан")
        item = shop.items[iid]
        rows.append(dict(id=iid, name=item["name"], category="supplies" if iid in (959, 960) else "gear",
                         level=1 if iid in (959, 960) else 76, adena=int(prices[iid]),
                         coins=max(1, math.ceil(prices[iid] / coin_value))))
    return dict(referenceAdenaPerCoin=coin_value, npcOnlyItemsRejected=True, notes="Reference for relative prices only; no coin-to-adena exchange.", items=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live-shop", type=Path, required=True)
    parser.add_argument("--coin-value", type=int, default=200_000)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--catalog", type=Path)
    args = parser.parse_args()
    if args.coin_value <= 0:
        parser.error("--coin-value должен быть положительным")
    report = audit(args.live_shop, args.coin_value)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.catalog:
        root = ET.Element("shop", referenceAdenaPerCoin=str(args.coin_value))
        for row in report["items"]:
            ET.SubElement(root, "item", {key: str(row[key]) for key in ("id", "name", "category", "level", "adena", "coins")})
        ET.indent(root, space="\t")
        args.catalog.parent.mkdir(parents=True, exist_ok=True)
        args.catalog.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")
    print(f"Проверено {len(report['items'])} товаров; отчёт: {args.report}")


if __name__ == "__main__":
    main()
