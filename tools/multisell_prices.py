"""Ищет в multisell/custom хроники товары дешевле скупки: купил за адену, сдал NPC дороже — бесконечные деньги.

Скупка NPC — цена предмета из stats/items, делённая пополам (RequestSellItem, SellList). Считается по дереву собранной хроники.

  python -X utf8 tools/multisell_prices.py <id>            отчёт
  python -X utf8 tools/multisell_prices.py <id> --fix      поднять цены в дереве: скупка, округлённая вверх

--fix правит только адену в файлах дерева, байт в байт по остальному. Правку в servers/ не держат без патча: после неё собрать
patches/<id>/ (AGENTS.md, «Исходники Mobius в servers/ не в git»).

Что считается:
  дешевле скупки     платишь только адену, а NPC за всё полученное даёт больше;
  обмен с прибылью   платишь адену и вещи, и даже по полной цене вещей выходит дешевле скупки полученного;
  скупка дороже цены получаешь адену за вещи больше их полной цены: купил у NPC, сдал сюда.
"""
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ADENA = 57


def tree_of(cid: str) -> Path:
    meta = json.loads((REPO / "phantoms" / cid / "base.json").read_text(encoding="utf-8"))
    return REPO / "servers" / cid / meta["build"] / "dist" / "game" / "data"


def items_of(data: Path) -> dict[int, tuple[str, int]]:
    out = {}
    for f in sorted((data / "stats" / "items").glob("*.xml")):
        for item in ET.parse(f).getroot().iter("item"):
            price = 0
            for s in item.findall("set"):
                if s.get("name") == "price":
                    price = int(s.get("val"))
            out[int(item.get("id"))] = (item.get("name") or "?", price)
    return out


def round_up(value: int) -> int:
    """Вверх до круглого: две значащие цифры."""
    if value < 100:
        return value
    step = 10 ** (len(str(value)) - 2)
    return math.ceil(value / step) * step


def main() -> int:
    args = sys.argv[1:]
    if not args or args[0].startswith("-") or len(args) > 2 or (len(args) == 2 and args[1] != "--fix"):
        sys.exit(__doc__)
    cid, fix = args[0], len(args) == 2
    data = tree_of(cid)
    items = items_of(data)
    folder = data / "multisell" / "custom"
    cheap, trade, buyback, entries = [], [], [], 0
    for f in sorted(folder.glob("*.xml")):
        raw = f.read_bytes()
        changed = raw
        for number, entry in enumerate(ET.fromstring(raw).findall("item"), 1):
            entries += 1
            ingredients = [(int(i.get("id")), int(i.get("count", 1))) for i in entry.findall("ingredient")]
            productions = [(int(p.get("id")), int(p.get("count", 1))) for p in entry.findall("production")]
            unknown = [i for i, _ in ingredients + productions if (i not in items) and (i != ADENA) and (i > 0)]
            if unknown:
                continue
            pay = sum(c for i, c in ingredients if i == ADENA)
            full = sum(items.get(i, ("", 0))[1] * c for i, c in ingredients if i != ADENA)
            has_goods = any(i != ADENA for i, _ in ingredients)
            got = sum(c for i, c in productions if i == ADENA)
            sale = sum((items.get(i, ("", 0))[1] // 2) * c for i, c in productions if i != ADENA)
            names = ", ".join(sorted({items.get(i, ("?", 0))[0] for i, _ in productions if i != ADENA}))[:70]
            if not has_goods and (pay > 0) and ((sale + got) > pay):
                cheap.append((f.name, number, pay, sale + got, names))
                if fix:
                    new = round_up(sale + got)
                    old_tag = re.compile(rb'(<ingredient\s+[^>]*?)count="' + str(pay).encode() + rb'"([^>]*?id="57"[^>]*>)|(<ingredient\s+id="57"[^>]*?)count="' + str(pay).encode() + rb'"')
                    found = list(old_tag.finditer(changed))
                    if not found:
                        print(f"  {f.name} №{number}: не нашёл строку с {pay}, правь руками")
                        continue
                    # Несколько товаров с той же ценой: строку берём по порядку записи среди записей файла.
                    blocks = list(re.finditer(rb"<item\b.*?</item>", changed, re.S))
                    block = blocks[number - 1]
                    part = old_tag.sub(lambda m: m.group(0).replace(b'count="' + str(pay).encode() + b'"', b'count="' + str(new).encode() + b'"'), block.group(0), count=1)
                    changed = changed[:block.start()] + part + changed[block.end():]
            elif has_goods and ((sale + got) > (pay + full)):
                trade.append((f.name, number, pay, full, sale + got, names))
            elif has_goods and (got > 0) and (got > full):
                buyback.append((f.name, number, full, got, names))
        if fix and (changed != raw):
            f.write_bytes(changed)
    print(f"{cid}: файлов {len(list(folder.glob('*.xml')))}, записей {entries}")
    print(f"дешевле скупки: {len(cheap)}")
    for name, number, pay, sale, what in cheap:
        print(f"  {name} №{number}: цена {pay}, скупка {sale}" + (f" -> {round_up(sale)}" if fix else "") + f" · {what}")
    print(f"обмен с прибылью: {len(trade)}")
    for name, number, pay, full, sale, what in trade:
        print(f"  {name} №{number}: адена {pay} и вещи на {full}, скупка {sale} · {what}")
    print(f"скупка дороже цены: {len(buyback)}")
    for name, number, full, got, what in buyback:
        print(f"  {name} №{number}: вещи на {full}, даёт {got}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
