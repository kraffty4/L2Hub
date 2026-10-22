"""Язык интерфейса L2Hub: русский или английский (настройка «Язык интерфейса», data/launcher.json).

Окно переводит себя само (ui/i18n.js поверх DOM, словарь ui/i18n/en.js). Здесь — то же для того, что рисует
Python мимо окна: меню и всплывашки трея. Словарь общий, порядок поиска — как в i18n.js.
"""

import ctypes
import json
import re

from . import appsettings, paths

CYR = re.compile(r"[А-Яа-яЁё]")
SEPS = (". ", " · ", " — ", "; ", ": ", ", ")
_dict: tuple[dict, list] | None = None


def lang() -> str:
    """ru | en: «auto» — по языку интерфейса Windows (русский — ru, любой другой — en)."""
    want = appsettings.load()["language"]
    if want != "auto":
        return want
    try:
        return "ru" if ctypes.windll.kernel32.GetUserDefaultUILanguage() & 0x3FF == 0x19 else "en"
    except (AttributeError, OSError):
        return "ru"


def _load() -> tuple[dict, list]:
    global _dict
    if _dict is None:
        try:
            text = (paths.UI / "i18n" / "en.js").read_text(encoding="utf-8")
            d = json.loads(text[text.index("= ") + 2:].rstrip().rstrip(";"))
        except (OSError, ValueError):
            d = {}
        exact = {k: v for k, v in d.items() if v and not re.search(r"\{\d+\}", k)}
        pats = []
        for ru, en in d.items():
            if en and re.search(r"\{\d+\}", ru):
                parts = re.split(r"(\{\d+\})", ru)
                order = [int(p[1:-1]) for p in parts if re.fullmatch(r"\{\d+\}", p)]
                src = "".join("(.*?)" if re.fullmatch(r"\{\d+\}", p) else re.escape(p) for p in parts)
                pats.append((len(re.sub(r"\{\d+\}", "", ru)), re.compile(f"^{src}$", re.S), order, en))
        pats.sort(key=lambda x: -x[0])
        _dict = (exact, pats)
    return _dict


def _find(s: str, depth: int = 0) -> str | None:
    """Перевод целиком или None — как find() в i18n.js: полурусской каши не бывает, непереведённое остаётся русским."""
    exact, pats = _load()
    if s in exact:
        return exact[s]
    if exact.get(s + ".", "").endswith("."):
        return exact[s + "."][:-1]
    if depth > 5:
        return None
    for weight, rx, order, en in pats:
        m = rx.match(s)
        if not m:
            continue
        if weight < 5 and any(CYR.search(g) for g in m.groups()):
            continue  # короткий шаблон — только для чисел и латиницы, как в i18n.js
        groups = [_part(g, depth) for g in m.groups()]
        if None not in groups:
            return re.sub(r"\{(\d+)\}", lambda g: groups[order.index(int(g.group(1)))], en)
    if m := re.fullmatch(r"([·—–]\s+)(.+)", s, re.S):
        return _wrap(m[1], _part(m[2], depth), "")
    if m := re.fullmatch(r"(.+?)(\s+[·—–])", s, re.S):
        return _wrap("", _part(m[1], depth), m[2])
    if (m := re.fullmatch(r"(.+?)([.:!?…]+)", s, re.S)) and (x := _part(m[1], depth)) is not None:
        return x + m[2]
    if m := re.fullmatch(r"(.+?)(\s*\([^А-Яа-яЁё()]*\))", s, re.S):
        return _wrap("", _part(m[1], depth), m[2])
    for sep in SEPS:
        if sep in s:
            xs = [_part(x, depth) for x in s.split(sep)]
            if None not in xs:
                return sep.join(xs)
    if m := re.fullmatch(r"([-+~]?[\d\s.,:%]+)\s+(\S.*)", s, re.S):
        return _wrap(f"{m[1]} ", _part(m[2], depth), "")
    return None


def _wrap(a: str, x: str | None, b: str) -> str | None:
    return None if x is None else a + x + b


def _part(v: str, depth: int) -> str | None:
    if not CYR.search(v):
        return v
    m = re.fullmatch(r"(\s*)(.*?)(\s*)", v, re.S)
    return _wrap(m[1], _find(m[2], depth + 1), m[3])


def t(s: str) -> str:
    """Русская строка интерфейса → на языке интерфейса (чего нет в словаре — остаётся по-русски)."""
    if not s or not CYR.search(s) or lang() != "en":
        return s
    return _find(re.sub(r"\s+", " ", s).strip()) or s
