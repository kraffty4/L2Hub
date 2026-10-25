"""Словарь английского интерфейса L2Hub: выгрузка русских строк окна и ядра, слияние переводов.

Исходные строки — русские, прямо в коде (app.js, world.js, index.html, сообщения l2hub/*.py, подписи из *.json).
Переводчик окна (launcher/l2hub/ui/i18n.js) меняет их в DOM по словарю launcher/l2hub/ui/i18n/en.js:
ключ — русская строка без лишних пробелов, {0}, {1} — подстановки (из ${…} шаблонов JS и {…} f-строк Python).
Разметка внутри строк режется по тегам: каждый текстовый кусок — свой ключ, как текстовый узел в DOM.

    python -X utf8 tools/i18n_extract.py              # обновить ключи словаря (переводы сохраняются), сводка
    python -X utf8 tools/i18n_extract.py --todo F     # непереведённые ключи — в JSON-список F
    python -X utf8 tools/i18n_extract.py --merge F    # переводы из JSON-объекта F {ru: en} — в словарь, с проверкой
    python -X utf8 tools/i18n_extract.py --prune      # заодно убрать ключи, которых в коде больше нет
    python -X utf8 tools/i18n_extract.py --seen F     # строки из JSON-списка F — в ui/i18n/seen.json (см. ниже)

Строки, которых нет в коде, но окно их показывает (комментарии конфигов сервера в календаре событий, тексты
из L2HubWorld.json и т. п.), видно в дев-режиме: L2I18N.missing в консоли окна с ?lang=en. Их список — в seen.json,
выгрузка берёт их ключами наравне с кодом.
"""

import argparse
import ast
import html
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PKG = ROOT / "launcher" / "l2hub"
UI = PKG / "ui"
DICT = UI / "i18n" / "en.js"
SEEN = UI / "i18n" / "seen.json"
HEAD = "// Английский словарь окна L2Hub: ключ — русская строка, {0}, {1} — подстановки. Ключи — tools/i18n_extract.py.\nwindow.L2_EN = "
JS = ["app.js", "world.js", "worldmap.js", "l2solo.js"]
# Ядро, чьи сообщения видны в окне. cli.py — консоль агентов, brain.py и knowledge.py — промпты моделей: не интерфейс.
PY_SKIP = {"cli.py", "brain.py", "knowledge.py", "devserver.py", "__init__.py", "l2ini.py"}
# l2solo_ru.json — перевод речи ботов L2Solo, а не окно.
JSON_SKIP = {"mobius_pins.json", "runtimes.json", "l2solo_pin.json", "updates.json", "feedback.json", "l2solo_ru.json"}
CYR = re.compile(r"[А-Яа-яЁё]")
PH = re.compile(r"\{\d+\}")
TAG = re.compile(r"<(?:[^>\"'{]|\"[^\"]*\"|'[^']*'|\{\d+\})*>")
ATTR = re.compile(r"\b(?:title|placeholder|aria-label|alt)=\"([^\"]*)\"")


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def clean(s: str) -> str:
    """Без точки-разделителя и тире по краям: «· {0} ч {1} мин» и «{0} ч {1} мин» — один ключ (i18n.js срезает так же)."""
    return re.sub(r"^[·—–]\s+|\s+[·—–]$", "", norm(s))


def renumber(s: str) -> str:
    n = iter(range(1000))
    return PH.sub(lambda _: "{%d}" % next(n), s)


def pieces(text: str, markup: bool = True) -> list[str]:
    """Строка (возможно, с разметкой) → ключи: текстовые куски между тегами и подсказки в атрибутах тегов."""
    out = []
    if markup:
        for tag in TAG.findall(text):
            out += ATTR.findall(tag)
        out += TAG.split(text)
    else:  # текст страницы, уже без тегов: «<номер коммита>» в нём — не тег
        out = [text]
    keys = []
    for p in out:
        p = clean(html.unescape(p))
        if not CYR.search(p):
            continue
        # Подстановки по краям — это соседние узлы или числа: «{0} ботов» оставляем, а «{0}» в начале и конце
        # куска не обрезаем — иначе «{0} с» и «с» станут одним ключом и потеряют смысл.
        keys.append(renumber(p))
    return keys


# ---------- JS ----------

REGEX_BEFORE = set("(,=:[!&|?{};+-*%<>~^")
ESC = {"n": "\n", "t": "\t", "r": "\r", "b": "", "f": "", "v": "", "0": ""}


def unescape(raw: str) -> str:
    def one(m: re.Match) -> str:
        e = m.group(1)
        if e[0] == "u":
            return chr(int(e[2:-1] if e[1] == "{" else e[1:], 16))
        if e[0] == "x":
            return chr(int(e[1:], 16))
        return ESC.get(e, e)

    return re.sub(r"\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])", one, raw)


def js_strings(src: str) -> list[str]:
    """Строки и шаблоны JS со вложенными ${…}: шаблон → текст с {n}, строки внутри ${…} — отдельно."""
    found = []

    def read_quote(i: int, q: str) -> tuple[str, int]:
        buf = []
        i += 1
        while i < len(src) and src[i] != q:
            if src[i] == "\\":
                buf.append(src[i:i + 2])
                i += 2
                continue
            buf.append(src[i])
            i += 1
        return unescape("".join(buf)), i + 1

    def read_template(i: int) -> tuple[str, int]:
        buf, n = [], 0
        i += 1
        while i < len(src) and src[i] != "`":
            if src[i] == "\\":
                m = re.compile(r"\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])").match(src, i)
                buf.append(unescape(m.group(0)))
                i = m.end()
            elif src.startswith("${", i):
                start = i + 2
                i = scan(start, "}")
                if re.search(r"</?[a-z][^<>]*>", src[start:i - 1]):
                    buf.append("<x>")  # подстановка с разметкой — граница текстовых узлов, как тег
                else:
                    buf.append("{%d}" % n)
                    n += 1
            else:
                buf.append(src[i])
                i += 1
        return "".join(buf), i + 1

    def scan(i: int, until: str | None) -> int:
        depth, prev = 0, "("
        while i < len(src):
            c = src[i]
            if until and c == until and depth == 0:
                return i + 1
            if src.startswith("//", i):
                i = src.find("\n", i)
                i = len(src) if i < 0 else i
                continue
            if src.startswith("/*", i):
                i = src.index("*/", i) + 2
                continue
            if c in "'\"":
                val, i = read_quote(i, c)
                found.append(val)
                prev = "a"
                continue
            if c == "`":
                val, i = read_template(i)
                found.append(val)
                prev = "a"
                continue
            if c == "/" and prev in REGEX_BEFORE:
                i += 1
                cls = False
                while i < len(src) and (src[i] != "/" or cls):
                    if src[i] == "\\":
                        i += 1
                    elif src[i] == "[":
                        cls = True
                    elif src[i] == "]":
                        cls = False
                    i += 1
                i += 1
                prev = "a"
                continue
            if c in "{([":
                depth += 1
            elif c in "})]":
                depth -= 1
            if not c.isspace():
                prev = c
                if c.isalnum() or c in "_$":
                    # return /re/ и typeof /re/ — редкость; слово — как операнд
                    j = i
                    while j < len(src) and (src[j].isalnum() or src[j] in "_$"):
                        j += 1
                    word = src[i:j]
                    prev = "(" if word in ("return", "typeof", "case") else "a"
                    i = j
                    continue
            i += 1
        return i

    scan(0, None)
    return found


# ---------- HTML ----------

class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.texts, self.scripts, self._script = [], [], False

    def handle_starttag(self, tag, attrs):
        self._script = tag in ("script", "style")
        for k, v in attrs:
            if k in ("title", "placeholder", "aria-label", "alt") and v:
                self.texts.append(v)

    def handle_endtag(self, tag):
        self._script = False

    def handle_data(self, data):
        (self.scripts if self._script else self.texts).append(data)


# ---------- Python ----------

def py_strings(src: str) -> list[str]:
    tree = ast.parse(src)
    docs = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                docs.add(id(first.value))
    out = []
    inner = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.JoinedStr):
            buf, n = [], 0
            for v in node.values:
                if isinstance(v, ast.Constant):
                    buf.append(str(v.value))
                    inner.add(id(v))
                else:
                    buf.append("{%d}" % n)
                    n += 1
            out.append("".join(buf))
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in docs and id(node) not in inner:
            out.append(node.value)
    return out


def json_strings(v) -> list[str]:
    if isinstance(v, str):
        return [v]
    if isinstance(v, list):
        return [s for x in v for s in json_strings(x)]
    if isinstance(v, dict):
        return [s for k, x in v.items() if not k.startswith("_") for s in json_strings(x)]  # "_comment" — для людей
    return []


def collect() -> list[str]:
    keys = []
    for name in JS:
        keys += [k for s in js_strings((UI / name).read_text(encoding="utf-8")) for k in pieces(s)]
    page = Page()
    page.feed((UI / "index.html").read_text(encoding="utf-8"))
    keys += [k for s in page.texts for k in pieces(s, markup=False)]
    keys += [k for src in page.scripts for s in js_strings(src) for k in pieces(s)]
    for f in sorted(PKG.glob("*.py")):
        if f.name not in PY_SKIP:
            keys += [k for s in py_strings(f.read_text(encoding="utf-8")) for k in pieces(s)]
    for f in sorted(PKG.glob("*.json")):
        if f.name not in JSON_SKIP:
            keys += [k for s in json_strings(json.loads(f.read_text(encoding="utf-8"))) for k in pieces(s)]
    if SEEN.exists():
        keys += [k for s in json.loads(SEEN.read_text(encoding="utf-8")) for k in pieces(s, markup=False)]
    seen = {}
    for k in keys:
        if PH.sub("", k).strip(" .,:;—·()«»!?-") and len(k) < 600 and "А-Я" not in k and not k.startswith("^"):  # регулярки — не текст
            seen.setdefault(k, None)
    return list(seen)


def load() -> dict:
    if not DICT.exists():
        return {}
    text = DICT.read_text(encoding="utf-8")
    return json.loads(text[text.index("= ") + 2:].rstrip().rstrip(";"))


def save(d: dict) -> None:
    DICT.parent.mkdir(parents=True, exist_ok=True)
    DICT.write_bytes((HEAD + json.dumps(d, ensure_ascii=False, indent=0) + ";\n").encode("utf-8"))


def check(ru: str, en: str) -> str | None:
    if sorted(PH.findall(ru)) != sorted(PH.findall(en)):
        return "подстановки не совпадают"
    if CYR.search(en):
        return "кириллица в переводе"
    return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--todo")
    ap.add_argument("--merge")
    ap.add_argument("--prune", action="store_true")
    ap.add_argument("--seen")
    a = ap.parse_args()
    if a.seen:
        old = json.loads(SEEN.read_text(encoding="utf-8")) if SEEN.exists() else []
        add = [norm(x) for x in json.loads(Path(a.seen).read_text(encoding="utf-8")) if norm(x) not in old]
        SEEN.write_bytes((json.dumps(old + add, ensure_ascii=False, indent=0) + "\n").encode("utf-8"))
        print(f"seen.json: +{len(add)}")
    d = load()
    keys = collect()
    if a.merge:
        bad = 0
        for ru, en in json.loads(Path(a.merge).read_text(encoding="utf-8")).items():
            ru, en = clean(ru), clean(en or "")
            why = check(ru, en) if en else "пусто"
            if why:
                bad += 1
                print(f"  ✕ {why}: {ru!r} → {en!r}")
            elif ru in d or ru in keys:
                d[ru] = en
        print(f"слияние: отклонено {bad}")
    new = {k: d.get(k, "") for k in keys}
    if not a.prune:
        new.update({k: v for k, v in d.items() if k not in new})
    save(new)
    todo = [k for k, v in new.items() if not v]
    print(f"ключей {len(new)}, из них в коде {len(keys)}, без перевода {len(todo)}, шаблонов {sum(1 for k in new if PH.search(k))}")
    if a.todo:
        Path(a.todo).write_text(json.dumps(todo, ensure_ascii=False, indent=0), encoding="utf-8")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
