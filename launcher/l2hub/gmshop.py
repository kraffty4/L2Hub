"""Редактор цен GM-шопа: правки цен поверх сборки отдельным слоем (docs/GM_SHOP_PRICES.md, раздел «Редактор»).

Списки сборки лежат в исходниках (servers/<id>/<сборка>/dist/...), их перезаписывает «Пересобрать». Правки — в data/gmshop/<id>.json, вне git:
{"prices": {"<список>": {"<ключ строки>": цена}}, "applied": ["<список>", ...]}. apply() собирает файл сервера из списка сборки и правок, байт в байт
по всему, кроме цены. Гейм читает мультиселлы при старте, поэтому apply() зовёт servers.start_part перед запуском гейма. Покупки охотников
(PhantomHunterOutfit.gmShopGoods) читают те же файлы и идут за правками сами.

Ключ строки — «<id вещи>x<штук>», у повторов в списке ещё «#<номер>». Правится только строка, где платят одной аденой за одну вещь; остальное — просмотр.
Хроника подключается записью в gm_shops.json. Правила предупреждений — как в tools/multisell_prices.py, плюс «пачка против штук».
"""

import functools
import json
import math
import os
import re
import threading
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

from . import catalog, i18n, paths

ADENA = 57
MAX_PRICE = 99_900_000_000  # MaxAdena Mobius по умолчанию: дороже в окне мультиселла не купить
MIN_FACTOR, MAX_FACTOR = 0.01, 1000
PACK_SLACK = 1.01  # цены округлены до трёх значащих цифр: коробка за 128 000 при штуках на 127 600 — не повод для предупреждения
SEARCH_LIMIT = 300
HIDDEN = "Без кнопки в шопе"

CATALOG = paths.PACKAGE / "gm_shops.json"
COMMENT = re.compile(rb"<!--.*?-->", re.S)
ROW = re.compile(rb"<item\b.*?</item>", re.S)
TAG = re.compile(rb"<(ingredient|production)\b[^>]*>")
ATTR = re.compile(rb'(\w+)="([^"]*)"')
NPC = re.compile(rb"<npc>\s*(\d+)\s*</npc>")
BUTTON = re.compile(r"<button\b[^>]*>", re.I)
ACTION = re.compile(r'action="([^"]*)"')
VALUE = re.compile(r'value="([^"]*)"')
CHAT = re.compile(r"npc_%objectId%_Chat (\d+)")
LIST = re.compile(r"npc_%objectId%_multisell (\d+)")
RESTORE = re.compile(r'<skill id="(\d+)".*?</skill>', re.S)
RESTORE_ITEM = re.compile(r'<effect name="Restoration">\s*<itemId>(\d+)</itemId>\s*<itemCount>(\d+)</itemCount>', re.S)

_templates: dict[Path, tuple[tuple, dict]] = {}
_parsed: dict[Path, tuple[tuple, list]] = {}
_lock = threading.RLock()


def _locked(fn):
    """Окно зовёт Api из разных потоков: две быстрые правки подряд не должны затереть друг друга в слое."""
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        with _lock:
            return fn(*args, **kwargs)
    return wrapper


def spec(p: dict) -> dict | None:
    """Запись хроники в gm_shops.json или None: редактора у неё нет."""
    return json.loads(CATALOG.read_text(encoding="utf-8"))["chronicles"].get(p["id"])


def layer_path(cid: str) -> Path:
    return paths.DATA / "gmshop" / f"{cid}.json"


def read_layer(cid: str) -> dict:
    path = layer_path(cid)
    try:
        layer = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        layer = {}
    return {"prices": layer.get("prices", {}), "applied": layer.get("applied", [])}


def _write_layer(cid: str, layer: dict) -> None:
    layer["prices"] = {k: v for k, v in layer["prices"].items() if v}
    path = layer_path(cid)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(layer, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8", newline="\n")
    os.replace(tmp, path)


def _source(cid: str) -> Path:
    from . import builder  # builder импортирует servers, servers — этот модуль
    return builder.source_dir(cid)


# ---------- Разбор ----------


def parse_list(raw: bytes) -> tuple[list[dict], set[int]]:
    """Строки списка и его NPC. У строки с ценой — price и span: где в файле стоит число. Комментарии маскируются, смещения — как в файле."""
    masked = COMMENT.sub(lambda m: b" " * len(m.group()), raw)
    rows, seen = [], Counter()
    for block in ROW.finditer(masked):
        pay, got = [], []
        for tag in TAG.finditer(block.group()):
            attrs = {m.group(1): m for m in ATTR.finditer(tag.group())}
            if b"id" not in attrs:
                continue
            count = attrs.get(b"count")
            entry = {"id": int(attrs[b"id"].group(2)), "count": int(count.group(2)) if count else 1, "at": None}
            if count:
                entry["at"] = (block.start() + tag.start() + count.start(2), block.start() + tag.start() + count.end(2))
            (pay if tag.group(1) == b"ingredient" else got).append(entry)
        if not got:
            continue
        name = f"{got[0]['id']}x{got[0]['count']}"
        seen[name] += 1
        row = {"key": name if seen[name] == 1 else f"{name}#{seen[name]}", "item": got[0]["id"], "count": got[0]["count"], "pay": pay, "got": got, "price": None, "span": None}
        if (len(pay) == 1) and (pay[0]["id"] == ADENA) and (len(got) == 1) and pay[0]["at"]:
            row["price"], row["span"] = pay[0]["count"], pay[0]["at"]
        rows.append(row)
    return rows, {int(n) for n in NPC.findall(masked)}


def _load(path: Path) -> tuple[list[dict], set[int]]:
    stat = path.stat()
    stamp = (stat.st_mtime_ns, stat.st_size)
    hit = _parsed.get(path)
    if hit and hit[0] == stamp:
        return hit[1]
    parsed = parse_list(path.read_bytes())
    _parsed[path] = (stamp, parsed)
    return parsed


def merge(raw: bytes, rows: list[dict], prices: dict) -> tuple[bytes, list[str]]:
    """Файл списка с правками. Вернёт ещё ключи правок, которых в списке больше нет."""
    spans = []
    known = set()
    for row in rows:
        known.add(row["key"])
        if row["key"] in prices and row["span"]:
            spans.append((row["span"], int(prices[row["key"]])))
    out = raw
    for (start, end), price in sorted(spans, reverse=True):
        out = out[:start] + str(price).encode("ascii") + out[end:]
    return out, [k for k in prices if k not in known]


def templates(p: dict, s: dict) -> dict:
    """id вещи → {name, price, contents}. contents — что выдаёт пачка: [(id, штук)], без лотерей с шансом."""
    server = catalog.server_dir(p)
    folder, skills = server / s["items"], server / s["skills"]
    files = sorted(folder.glob("*.xml"))
    stamp = (len(files), max((f.stat().st_mtime_ns for f in files), default=0))
    hit = _templates.get(folder)
    if hit and hit[0] == stamp:
        return hit[1]
    restores = {}
    for f in sorted(skills.glob("*.xml")):
        text = f.read_text(encoding="utf-8", errors="replace")
        if "Restoration" in text:
            for m in RESTORE.finditer(text):
                found = RESTORE_ITEM.search(m.group())
                if found:
                    restores[int(m.group(1))] = (int(found.group(1)), int(found.group(2)))
    items = {}
    for f in files:
        for item in ET.parse(f).getroot().findall("item"):
            price, action = 0, ""
            for one in item.findall("set"):
                if one.get("name") == "price":
                    price = int(one.get("val"))
                elif one.get("name") == "default_action":
                    action = one.get("val")
            contents = []
            for got in item.findall("capsuled_items/item"):
                if (got.get("chance") in ("100", "100.0")) and (got.get("min") == got.get("max")):
                    contents.append((int(got.get("id")), int(got.get("min"))))
            if action == "CAPSULE":  # у надетых талисманов такое же умение: они не пачка
                for skill in item.findall("skills/skill"):
                    if int(skill.get("id")) in restores:
                        contents.append(restores[int(skill.get("id"))])
            items[int(item.get("id"))] = {"name": item.get("name") or "?", "price": price, "contents": contents}
    _templates[folder] = (stamp, items)
    return items


def list_files(p: dict, s: dict) -> dict[int, Path]:
    """Номер списка → файл в исходниках: как у MultisellData, позже загруженная папка custom важнее."""
    src = _source(p["id"]) / "dist"
    files = {}
    for folder in reversed(s["folders"]):
        for f in (src / folder).glob("*.xml"):
            if f.stem.isdigit():
                files[int(f.stem)] = f
    return files


def _pages(p: dict, s: dict, lang: str) -> dict[int, str]:
    server = catalog.server_dir(p)
    out = {}
    for folder in (s["pages"]["en"], s["pages"].get(lang) or s["pages"]["en"]):  # чего нет на языке — с английской страницы
        for f in (server / folder).glob(f"{s['npc']}*.htm"):
            m = re.fullmatch(rf"{s['npc']}(?:-(\d+))?\.htm", f.name)
            if m:
                out[int(m.group(1) or 0)] = f.read_text(encoding="utf-8", errors="replace")
    return out


def sections(p: dict, s: dict, lang: str) -> list[dict]:
    """Разделы шопа по кнопкам страниц, обходом от главной: что и где лежит для игрока."""
    pages = _pages(p, s, lang)
    out, seen, queue = [], set(), [(0, [])]
    visited = {0}
    while queue:
        page, path = queue.pop(0)
        for button in BUTTON.findall(pages.get(page, "")):
            action, value = ACTION.search(button), VALUE.search(button)
            if not action or not value:
                continue
            chat, shop = CHAT.search(action.group(1)), LIST.search(action.group(1))
            if chat and (int(chat.group(1)) not in visited) and (int(chat.group(1)) in pages):
                visited.add(int(chat.group(1)))
                queue.append((int(chat.group(1)), path + [value.group(1)]))
            elif shop and (int(shop.group(1)) not in seen):
                seen.add(int(shop.group(1)))
                out.append({"list": int(shop.group(1)), "title": value.group(1), "path": path, "hidden": False})
    return out


class Shop:
    """Цены хроники на сейчас: списки сборки, правки, разделы."""

    def __init__(self, p: dict, lang: str | None = None):
        self.p = p
        self.s = spec(p)
        if not self.s:
            raise ValueError(f"У хроники {p['id']} нет редактора цен (gm_shops.json).")
        self.layer = read_layer(p["id"])
        self.files = list_files(p, self.s)
        self.items = templates(p, self.s)
        self.lang = lang or i18n.lang()
        self.lists = {lid: _load(f) for lid, f in self.files.items()}
        self.tree = [e for e in sections(p, self.s, self.lang) if e["list"] in self.files]
        listed = {e["list"] for e in self.tree}
        for lid in sorted(self.files):
            if (lid not in listed) and (self.s["npc"] in self.lists[lid][1]):
                self.tree.append({"list": lid, "title": str(lid), "path": [HIDDEN], "hidden": True})
        self.titles = {e["list"]: e for e in self.tree}

    def price(self, lid: int, row: dict, prices: dict | None = None) -> int | None:
        """Цена строки с учётом правок; у строки без цены None."""
        if row["price"] is None:
            return None
        chosen = (prices if prices is not None else self.layer["prices"]).get(str(lid), {})
        return int(chosen.get(row["key"], row["price"]))

    def lowest(self, prices: dict | None = None) -> dict[int, float]:
        """Самая низкая цена штуки по спискам шопа, где платят одной аденой. Списки, которых у шопа нет (600014 и другие), не в счёт: там не купить."""
        shop: dict[int, float] = {}
        for lid, (rows, _) in self.lists.items():
            if lid not in self.titles:
                continue
            for row in rows:
                value = self.price(lid, row, prices)
                if value is not None:
                    shop[row["item"]] = min(shop.get(row["item"], math.inf), value / max(row["count"], 1))
        return shop

    def warnings(self, prices: dict | None = None) -> dict[tuple[int, str], list[str]]:
        """Предупреждения по строкам: скупка NPC, пачка против штук, обмены с прибылью."""
        shop = self.lowest(prices)
        items = self.items
        out: dict[tuple[int, str], list[str]] = {}
        for lid, (rows, _) in self.lists.items():
            if lid not in self.titles:
                continue
            for row in rows:
                found = []
                sale = sum((items.get(g["id"], {}).get("price", 0) // 2) * g["count"] for g in row["got"] if g["id"] != ADENA)
                got = sum(g["count"] for g in row["got"] if g["id"] == ADENA)
                value = self.price(lid, row, prices)
                if value is not None:
                    if (sale + got) > value:
                        found.append(_money("Дешевле скупки NPC: сдашь за {0} адены, купил и сдал с прибылью.", sale + got))
                    pack = self.pack_value(row, shop)
                    if pack is not None and value > pack * PACK_SLACK:
                        found.append(_money("Пачка дороже штук: штуками выйдет {0} адены.", pack))
                else:
                    adena = sum(i["count"] for i in row["pay"] if i["id"] == ADENA)
                    goods = [i for i in row["pay"] if i["id"] != ADENA]
                    full = sum((items.get(i["id"], {}).get("price", 0) or shop.get(i["id"], math.inf)) * i["count"] for i in goods)
                    if goods and (sale + got) > (adena + full):
                        found.append(_money("Обмен с прибылью: сдашь полученное за {0} адены, это дороже всего, что отдал.", sale + got))
                    elif not goods and adena and (sale + got) > adena:
                        found.append(_money("Дешевле скупки NPC: сдашь за {0} адены, купил и сдал с прибылью.", sale + got))
                if found:
                    out[(lid, row["key"])] = found
        return out

    def pack_value(self, row: dict, shop: dict[int, float]) -> int | None:
        """Сколько то же самое стоит штуками, если всё содержимое пачки продаётся в шопе."""
        contents = self.items.get(row["item"], {}).get("contents")
        if not contents:
            return None
        total = 0.0
        for item, count in contents:
            if item not in shop:
                return None
            total += shop[item] * count
        return math.ceil(total * row["count"])

    def row_view(self, lid: int, row: dict, warnings: dict) -> dict:
        price = self.price(lid, row)
        section = self.titles.get(lid, {})
        return {
            "list": lid, "key": row["key"], "item": row["item"], "name": self.items.get(row["item"], {}).get("name", "?"), "count": row["count"],
            "price": price, "base": row["price"], "edited": (price is not None) and (price != row["price"]), "editable": row["span"] is not None,
            "pay": None if row["span"] else ", ".join(f"{self.items.get(i['id'], {}).get('name', i['id'])} x{i['count']}" for i in row["pay"]),
            "section": " / ".join([*section.get("path", []), section.get("title", str(lid))]), "warnings": warnings.get((lid, row["key"]), []),
        }


def _money(text: str, value: int) -> str:
    return i18n.t(text).format(f"{value:,}".replace(",", " "))


# ---------- Что видит окно ----------


def state(p: dict, lang: str | None = None) -> dict:
    """Разделы шопа со счётчиками правок и предупреждений."""
    shop = Shop(p, lang)
    warnings = shop.warnings()
    tree = []
    for entry in shop.tree:
        lid = entry["list"]
        rows = shop.lists[lid][0]
        tree.append({**entry, "rows": len(rows), "edited": len(shop.layer["prices"].get(str(lid), {})), "warnings": sum(1 for r in rows if (lid, r["key"]) in warnings)})
    return {"tree": tree, "edited": sum(len(v) for v in shop.layer["prices"].values()), "warnings": len(warnings)}


def rows(p: dict, list_ids: list[int] | None = None, query: str = "", only: str = "") -> dict:
    """Строки разделов или поиск по всему шопу: часть названия или номер вещи. only: edited — только правки, warn — только с предупреждением."""
    shop = Shop(p)
    warnings = shop.warnings()
    words = query.lower().split()
    wanted = set(list_ids) if list_ids else set(shop.titles)
    out = []
    total = 0
    for lid in [e["list"] for e in shop.tree if e["list"] in wanted]:
        for row in shop.lists[lid][0]:
            name = shop.items.get(row["item"], {}).get("name", "?").lower()
            if words and not all((w == str(row["item"])) or (w in name) for w in words):
                continue
            view = shop.row_view(lid, row, warnings)
            if (only == "edited" and not view["edited"]) or (only == "warn" and not view["warnings"]):
                continue
            total += 1
            if len(out) < SEARCH_LIMIT:
                out.append(view)
    return {"rows": out, "total": total, "limit": SEARCH_LIMIT}


def round_price(value: float) -> int:
    """Три значащие цифры, как у цен шопа; меньше тысячи — до целого."""
    value = max(1, round(value))
    if value < 1000:
        return value
    step = 10 ** (len(str(value)) - 3)
    return round(value / step) * step


def _validate(price) -> int:
    if isinstance(price, bool) or not isinstance(price, (int, float, str)):
        raise ValueError(i18n.t("Цена — целое число адены."))
    try:
        value = int(str(price).replace(" ", ""))
    except ValueError:
        raise ValueError(i18n.t("Цена — целое число адены.")) from None
    if not 1 <= value <= MAX_PRICE:
        raise ValueError(i18n.t("Цена — от 1 до {0} адены.").format(f"{MAX_PRICE:,}".replace(",", " ")))
    return value


def _store(shop: Shop, prices: dict) -> None:
    shop.layer["prices"] = prices
    _write_layer(shop.p["id"], shop.layer)
    apply(shop.p)


@_locked
def set_prices(p: dict, edits: list[dict]) -> dict:
    """Правки строк: {"list", "key", "price"}; price None или равная цене сборки снимает правку."""
    shop = Shop(p)
    prices = {k: dict(v) for k, v in shop.layer["prices"].items()}
    changed = 0
    for edit in edits:
        lid = int(edit["list"])
        row = next((r for r in shop.lists.get(lid, ([], None))[0] if r["key"] == edit["key"]), None)
        if row is None or row["span"] is None:
            raise ValueError(i18n.t("В списке {0} нет такой строки с ценой в адене: {1}.").format(lid, edit["key"]))
        chosen = prices.setdefault(str(lid), {})
        value = None if edit.get("price") in (None, "") else _validate(edit["price"])
        if value is None or value == row["price"]:
            changed += 1 if chosen.pop(row["key"], None) is not None else 0
        elif chosen.get(row["key"]) != value:
            chosen[row["key"]] = value
            changed += 1
    _store(shop, prices)
    return {"changed": changed}


@_locked
def multiply(p: dict, list_ids: list[int], factor, preview: bool = True) -> dict:
    """Множитель на разделы: новая цена округляется до трёх значащих цифр. preview — посчитать и показать, что станет, ничего не меняя."""
    try:
        factor = float(str(factor).replace(",", "."))
    except ValueError:
        raise ValueError(i18n.t("Множитель — число, например 1,5.")) from None
    if not MIN_FACTOR <= factor <= MAX_FACTOR:
        raise ValueError(i18n.t("Множитель — от {0} до {1}.").format(MIN_FACTOR, MAX_FACTOR))
    shop = Shop(p)
    prices = {k: dict(v) for k, v in shop.layer["prices"].items()}
    changes = []
    for lid in list_ids:
        for row in shop.lists.get(lid, ([], None))[0]:
            if row["span"] is None:
                continue
            old = shop.price(lid, row)
            new = min(round_price(old * factor), MAX_PRICE)
            if new == old:
                continue
            chosen = prices.setdefault(str(lid), {})
            if new == row["price"]:
                chosen.pop(row["key"], None)
            else:
                chosen[row["key"]] = new
            changes.append({"list": lid, "key": row["key"], "name": shop.items.get(row["item"], {}).get("name", "?"), "count": row["count"], "old": old, "new": new})
    was = shop.warnings()
    now = shop.warnings(prices)
    added = []
    for (lid, key), text in now.items():
        if (lid, key) not in was:
            item = int(key.split("x")[0])
            section = shop.titles.get(lid, {})
            added.append({"name": shop.items.get(item, {}).get("name", "?"), "section": " / ".join([*section.get("path", []), section.get("title", str(lid))]), "text": text[0]})
    if not preview:
        _store(shop, prices)
    return {"changes": changes[:SEARCH_LIMIT], "total": len(changes), "newWarnings": added[:50], "newWarningsTotal": len(added), "applied": not preview}


@_locked
def reset(p: dict, list_ids: list[int] | None = None) -> dict:
    """Снять правки разделов; без разделов — все."""
    shop = Shop(p)
    prices = {k: dict(v) for k, v in shop.layer["prices"].items()}
    gone = 0
    for lid in list_ids if list_ids else [int(k) for k in prices]:
        gone += len(prices.pop(str(lid), {}))
    _store(shop, prices)
    return {"reset": gone}


# ---------- Запись в сервер ----------


@_locked
def apply(p: dict, out=print) -> dict:
    """Файлы сервера = списки сборки + правки. Трогает только списки с правками и те, где правки были: файл, который правили руками мимо слоя, остаётся."""
    s = spec(p)
    cid = p["id"]
    if not s or not layer_path(cid).is_file():
        return {"written": 0, "missing": []}
    layer = read_layer(cid)
    files = list_files(p, s)
    server = catalog.server_dir(p)
    src = _source(cid) / "dist"
    written, missing = 0, []
    for lid in sorted({int(k) for k in layer["prices"] if layer["prices"][k]} | {int(k) for k in layer["applied"]}):
        f = files.get(lid)
        if f is None:
            missing += [f"{lid}: {key}" for key in layer["prices"].get(str(lid), {})]
            continue
        raw = f.read_bytes()
        rows, _ = parse_list(raw)
        merged, gone = merge(raw, rows, layer["prices"].get(str(lid), {}))
        missing += [f"{lid}: {key}" for key in gone]
        to = server / f.relative_to(src)
        if not to.is_file() or to.read_bytes() != merged:
            to.parent.mkdir(parents=True, exist_ok=True)
            to.write_bytes(merged)
            written += 1
    if missing:
        out(i18n.t("GM-шоп: правки без строки в сборке, не применились: {0}").format(", ".join(missing[:10])))
    layer["applied"] = sorted(int(k) for k in layer["prices"] if layer["prices"][k])
    _write_layer(cid, layer)
    return {"written": written, "missing": missing}
