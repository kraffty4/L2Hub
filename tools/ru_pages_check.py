"""Сверка русских страниц диалогов с английскими: падает при любом расхождении. Без сервера, дерево только читает.

  python -X utf8 tools/ru_pages_check.py --tree <дерево> [--lang ru] [--list <файл партии>] [--tm <файл>] [--glossary <файл>] [--shared] [-v]
  python -X utf8 tools/ru_pages_check.py --selftest

Дерево — папка, в которой лежит dist/game/data или game/data (servers/<id>/<сборка>, servers/<id>/server), либо сама data.
Русская страница data/lang/ru/data/<путь> сверяется с английской data/<путь>. Код возврата 1 — есть ошибка, 0 — чисто.
С --list проверяются только страницы партии, и каждой из них обязана быть русская пара; без него — все страницы data/lang/ru/data.

На странице:
  нет английской пары: сервер такую страницу не покажет (getHtmlPath ищет английскую);
  кодировка, BOM, битые символы; переводы строк те же, что у английской (CRLF, LF, конец файла);
  теги и команды те же и в том же порядке: action, bypass, ширина, картинки. Меняться может только подпись кнопки (value);
  длина после чистки HtmCache не больше 17200: длиннее сервер обрежет (AbstractHtmlPacket.setHtml);
в каждом куске текста (между блочными тегами и переводами строк; font, b, i, u внутри куска не режут):
  те же %подстановки%, сущности (&$123;, &nbsp;) и числа;
  нет кириллицы там, где в английском есть слова, или есть латиница не из глоссария: кусок не переведён;
  термин глоссария в английском куске требует основу этого термина в русском;
по всем страницам:
  разделённые тексты: один английский кусок переведён по-разному, страница-близнец с тем же английским текстом осталась без перевода;
  --tm: кусок не совпадает с памятью переводов: значит, английская страница изменилась после перевода (или страницу правили руками).

--shared печатает, что общего у переведённых страниц с остальными, куда кнопки ведут на английские страницы и фразы, разрезанные между страницами.
Это не ошибки, а швы перевода: в код возврата не входят.
"""
import argparse
import re
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PAGE_LIMIT = 17200  # AbstractHtmlPacket.setHtml: длиннее — сервер обрежет
PAGE_ROOTS = ("html", "scripts")

TOKEN = re.compile(r"<[^<>]*>|\r\n|\n|\r|\t")
TAG_NAME = re.compile(r"<\s*/?\s*([a-zA-Z0-9]+)")
BUTTON_VALUE = re.compile(r'(<button\b[^<>]*?\bvalue=")([^"]*)(")', re.I)
PLACEHOLDER = re.compile(r"%[A-Za-z0-9_]+%")
ENTITY = re.compile(r"&[$#@]?[A-Za-z0-9]+;")
NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
LATIN = re.compile(r"[A-Za-z][A-Za-z'\-]*")
CYRILLIC = re.compile(r"[А-Яа-яЁё]")
INLINE = {"font", "b", "i", "u"}
# Что клиент точно получит символами, которые мы готовы ставить; остальное — чаще всего битая кодировка.
ALLOWED = set(map(chr, range(32, 127))) | set("\r\n\t") | {chr(c) for c in range(0x410, 0x450)} | set("Ёё«»—–·…№")
# Служебные сокращения и буквы грейдов: латиницей в любом тексте.
ALWAYS_LATIN = {"SP", "XP", "HP", "MP", "CP", "PvP", "PK", "NPC", "A", "B", "C", "D", "S"}
GLOSSARY = REPO / "docs" / "RU_GLOSSARY.md"


# ---------- Разбор страницы ----------

def data_root(tree: Path) -> Path:
    for sub in ("dist/game/data", "game/data", "."):
        if (tree / sub / "html").is_dir():
            return (tree / sub).resolve()
    raise SystemExit(f"{tree}: не вижу data/html")


def read(path: Path) -> str:
    return path.read_bytes().decode("utf-8", "surrogateescape")


def tags_of(page: str) -> list[str]:
    """Скелет: все теги подряд, у кнопки подпись заменена меткой."""
    return [BUTTON_VALUE.sub(r"\1\3", m[0]) for m in re.finditer(r"<[^<>]*>", page)]


def eols_of(page: str) -> list[str]:
    return re.findall(r"\r\n|\n|\r", page)


def blocks_of(page: str) -> list[str]:
    """Куски текста по порядку: граница — блочный тег, перевод строки, табуляция; font, b, i, u остаются внутри куска. Подпись кнопки — свой кусок."""
    out, cur, pos = [], [], 0
    for m in TOKEN.finditer(page):
        cur.append(page[pos:m.start()])
        pos = m.end()
        name = TAG_NAME.match(m[0])
        if name and name[1].lower() in INLINE:
            cur.append(m[0])
            continue
        out.append("".join(cur))
        cur = []
        if m[0].startswith("<"):
            value = BUTTON_VALUE.search(m[0])
            if value:
                out.append(value[2])
    cur.append(page[pos:])
    out.append("".join(cur))
    return out


def strip_markup(block: str) -> str:
    return ENTITY.sub(" ", PLACEHOLDER.sub(" ", re.sub(r"<[^<>]*>", " ", block)))


def has_words(block: str) -> bool:
    return bool(re.search(r"[A-Za-zА-Яа-яЁё]", strip_markup(block)))


def norm(block: str) -> str:
    return " ".join(block.split())


def cleaned(page: str) -> str:
    """То, что HtmCache держит в памяти и отдаёт клиенту."""
    return re.sub(r"[\t\n]", "", re.sub(r"(?s)<!--.*?-->", "", page))


# ---------- Глоссарий ----------

def term_pattern(term: str) -> re.Pattern:
    """Термин с окончаниями -s, -es; записанный строчными ищется без учёта регистра. Сокращение вроде Aren't — не термин Aren."""
    return re.compile(rf"(?<![A-Za-z]){re.escape(term)}(?:s|es)?(?![A-Za-z])(?!['’](?:t|ll|re|ve|d|m)\b)", re.I if term.islower() else 0)


class Glossary:
    """Таблицы docs/RU_GLOSSARY.md: | English | Русский | Основа | ... |. Русский «=» — термин остаётся латиницей, с основой «-» — латиница только разрешена. Основа — через запятую, без неё термин не проверяется."""

    def __init__(self, path: Path | None):
        self.terms: list[tuple[str, re.Pattern, list[str] | None]] = []
        self.latin = set(ALWAYS_LATIN)
        if path is None or not path.is_file():
            return
        for line in path.read_text(encoding="utf-8").splitlines():
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if not line.lstrip().startswith("|") or len(cells) < 3 or cells[0] in ("English", "") or set(cells[0]) <= {"-", " ", ":"}:
                continue
            en, ru, stem = cells[0].strip("`"), cells[1], cells[2]
            if ru == "=":
                self.latin.update(LATIN.findall(en))
            stems = [s.strip().lower().replace("ё", "е") for s in stem.split(",") if s.strip()]
            if ru == "=" and stem.strip() == "-":
                self.terms.append((en, term_pattern(en), None))  # латиница разрешена, перевод не запрещён; вложенный термин закрывает
                continue
            if ru == "=":
                stems = [en.lower()]
            if stems:
                self.terms.append((en, term_pattern(en), stems))
        self.terms.sort(key=lambda t: -len(t[0]))  # длинный термин закрывает вложенный: Talking Island Village раньше Talking Island

    def missing(self, en: str, ru: str) -> list[str]:
        text = strip_markup(en)
        plain = strip_markup(ru)
        target = plain.lower().replace("ё", "е")
        out = []
        for term, pattern, stems in self.terms:
            if pattern.search(text):
                text = pattern.sub(" ", text)
                # термин, перенесённый латиницей как в английском (имя монстра в списке), не ошибка глоссария
                if stems is not None and not any(s in target for s in stems) and not pattern.search(plain):
                    out.append(f"{term} -> {'/'.join(stems)}")
        return out


# ---------- Сверка ----------

class Report:
    def __init__(self):
        self.errors: list[tuple[str, str]] = []
        self.kinds = Counter()

    def add(self, page: str, kind: str, what: str) -> None:
        self.errors.append((page, f"{kind}: {what}"))
        self.kinds[kind] += 1


def multiset_diff(a: list[str], b: list[str]) -> str:
    x, y = Counter(a), Counter(b)
    return f"нет {sorted((x - y).elements())}, лишнее {sorted((y - x).elements())}"


def check_page(rel: str, en_raw: bytes, ru_raw: bytes, glossary: Glossary, rep: Report, pairs: dict) -> None:
    if ru_raw.startswith(b"\xef\xbb\xbf"):
        rep.add(rel, "кодировка", "BOM в начале файла: HtmCache отдаст его клиенту")
    try:
        ru = ru_raw.decode("utf-8")
    except UnicodeDecodeError as e:
        rep.add(rel, "кодировка", f"не UTF-8: {e}")
        return
    en = en_raw.decode("utf-8", "surrogateescape")
    bad = sorted({c for c in ru if c not in ALLOWED and c not in en})
    if bad:
        rep.add(rel, "символы", f"вне набора: {bad[:10]}")
    if eols_of(en) != eols_of(ru):
        c_en, c_ru = Counter(eols_of(en)), Counter(eols_of(ru))
        rep.add(rel, "переводы строк", f"английская {dict(c_en)}, русская {dict(c_ru)}")
    if bool(re.search(r"[\r\n]$", en)) != bool(re.search(r"[\r\n]$", ru)):
        rep.add(rel, "переводы строк", "разный конец файла")
    if len(cleaned(ru)) > PAGE_LIMIT:
        rep.add(rel, "длина", f"{len(cleaned(ru))} знаков, сервер обрежет на {PAGE_LIMIT}")
    t_en, t_ru = tags_of(en), tags_of(ru)
    if t_en != t_ru:
        i = next((i for i, (a, b) in enumerate(zip(t_en, t_ru)) if a != b), min(len(t_en), len(t_ru)))
        rep.add(rel, "теги", f"тег {i + 1} из {len(t_en)}/{len(t_ru)}: {t_en[i] if i < len(t_en) else '(конец)'} <> {t_ru[i] if i < len(t_ru) else '(конец)'}")
        return  # куски уже не выровнять
    b_en, b_ru = blocks_of(en), blocks_of(ru)
    if len(b_en) != len(b_ru):
        rep.add(rel, "куски", f"{len(b_en)} и {len(b_ru)}: не совпала разбивка переводами строк")
        return
    for a, b in zip(b_en, b_ru):
        na, nb = norm(a), norm(b)
        if not na and not nb:
            continue
        if bool(na) != bool(nb):
            rep.add(rel, "куски", f"пустой с одной стороны: {na[:60]!r} | {nb[:60]!r}")
            continue
        for kind, pattern in (("подстановки", PLACEHOLDER), ("сущности", ENTITY)):
            x, y = pattern.findall(a), pattern.findall(b)
            if Counter(x) != Counter(y):
                rep.add(rel, kind, f"{multiset_diff(x, y)} в {nb[:60]!r}")
        x, y = NUMBER.findall(strip_markup(a)), NUMBER.findall(strip_markup(b))
        if Counter(x) != Counter(y):
            rep.add(rel, "числа", f"{multiset_diff(x, y)} в {nb[:60]!r}")
        words_en = LATIN.findall(strip_markup(a))
        words_ru = LATIN.findall(strip_markup(b))
        if words_en and not CYRILLIC.search(strip_markup(b)) and not all(w in glossary.latin for w in words_ru):
            rep.add(rel, "без перевода", repr(nb[:80]))
        else:
            alien = [w for w in words_ru if w not in glossary.latin]
            if alien:
                rep.add(rel, "латиница", f"{sorted(set(alien))[:6]} в {nb[:60]!r}")
        for miss in glossary.missing(a, b):
            rep.add(rel, "глоссарий", f"{miss} в {nb[:70]!r}")
        if has_words(a):
            pairs[na].add(nb)


def scan_english(data: Path) -> dict[str, Path]:
    """Все английские страницы."""
    out = {}
    for sub in PAGE_ROOTS:
        for f in (data / sub).rglob("*"):
            if f.is_file() and f.suffix.lower() in (".htm", ".html"):
                out[f.relative_to(data).as_posix()] = f
    return out


def page_key(page: str) -> str:
    return norm(cleaned(page).replace("\r", " "))


def check_tree(data: Path, lang: str, listed: list[str] | None, glossary: Glossary, tm: dict[str, str] | None, rep: Report) -> tuple[dict, dict, dict]:
    """Вернёт (английские страницы, русские страницы, куски): для отчёта о швах."""
    ru_root = data / "lang" / lang / "data"
    russian = {}
    if ru_root.is_dir():
        for f in ru_root.rglob("*"):
            if f.is_file() and f.suffix.lower() in (".htm", ".html"):
                russian[f.relative_to(ru_root).as_posix()] = f
    if listed is not None:
        for rel in listed:
            if rel not in russian:
                rep.add(rel, "нет страницы", "в партии есть, в lang нет")
        russian = {r: f for r, f in russian.items() if r in set(listed)}
    english = scan_english(data)
    pairs: dict[str, set[str]] = defaultdict(set)
    for rel, f in sorted(russian.items()):
        en = data / rel
        if not en.is_file():
            rep.add(rel, "нет пары", "английской страницы нет: сервер такую страницу не покажет")
            continue
        check_page(rel, en.read_bytes(), f.read_bytes(), glossary, rep, pairs)
    # Разделённые тексты: один английский кусок переведён по-разному
    for en_text, ru_set in sorted(pairs.items()):
        if len(ru_set) > 1:
            rep.add("(разделённый текст)", "разный перевод", f"{en_text[:70]!r} -> " + " | ".join(repr(s[:60]) for s in sorted(ru_set)))
    # Страница-близнец: тот же английский текст на другой странице, а там перевода нет
    twins = defaultdict(list)
    for rel, f in english.items():
        twins[page_key(read(f))].append(rel)
    done = set(russian)
    for rel in sorted(done):
        if rel in english:
            for other in twins[page_key(read(english[rel]))]:
                if other not in done and (listed is None or other not in listed):
                    rep.add(other, "близнец без перевода", f"тот же английский текст, что у {rel}")
    if tm is not None:
        for rel, f in sorted(russian.items()):
            en = data / rel
            if not en.is_file():
                continue
            for a, b in zip(blocks_of(read(en)), blocks_of(read(f))):
                na, nb = norm(a), norm(b)
                if has_words(a) and strip_markup(a).strip() and re.search(r"[A-Za-z]", strip_markup(a)):
                    if na not in tm:
                        rep.add(rel, "нет в памяти", f"{na[:70]!r}: английская страница изменилась после перевода?")
                    elif tm[na] != nb:
                        rep.add(rel, "разошлось с памятью", f"{nb[:70]!r}, в памяти {tm[na][:70]!r}")
    return english, russian, pairs


# ---------- Швы: что общего с непереведённым ----------

LINK = re.compile(r'npc_%objectId%_Link ([^"\s]+)')
CHAT = re.compile(r'npc_%objectId%_Chat (\d+)')
QUEST_PAGE = re.compile(r'(?:Quest|Script) (Q\d+_[A-Za-z0-9_]+) ([^"\s]+\.html?)')
SCRIPT = re.compile(r'bypass(?: -h)? (?:npc_%objectId%_)?Script ([A-Za-z0-9_]+)\b(?! [^"\s]+\.html?)')
FULL_STOP = re.compile(r"""[.!?:;)\]"'*>]\s*$""")  # конец фразы; многоточие проверяется отдельно: на нём фразы чаще всего и обрываются


def seams(data: Path, english: dict, russian: dict, pairs: dict) -> None:
    shared = Counter()
    untranslated_pages = [f for r, f in english.items() if r not in russian]
    for f in untranslated_pages:
        for block in set(norm(b) for b in blocks_of(read(f))):
            if block in pairs:
                shared[block] += 1
    print(f"\nШвы. Кусков партии, которые есть и на непереведённых страницах: {len(shared)} из {len(pairs)}. Самые частые:")
    for block, n in shared.most_common(15):
        print(f"  {n:6d}  {block[:90]}")
    outgoing = defaultdict(set)
    for rel, f in russian.items():
        page = read(f)
        folder = rel.rsplit("/", 1)[0]
        base = re.match(r"(\d+)", rel.rsplit("/", 1)[1])
        for m in LINK.finditer(page):
            outgoing[f"html/{m[1]}"].add(rel)
        if base:
            for m in CHAT.finditer(page):
                outgoing[f"{folder}/{base[1]}-{m[1]}.htm" if m[1] != "0" else f"{folder}/{base[1]}.htm"].add(rel)
        for m in QUEST_PAGE.finditer(page):
            outgoing[f"scripts/quests/{m[1]}/{m[2]}"].add(rel)
    left = sorted(t for t in outgoing if t in english and t not in russian)
    print(f"\nКнопки переведённых страниц ведут на английские: {len(left)}")
    for t in left:
        print(f"  {t}  <- {sorted(outgoing[t])[0]}" + (f" и ещё {len(outgoing[t]) - 1}" if len(outgoing[t]) > 1 else ""))
    scripts = defaultdict(set)
    for rel, f in russian.items():
        for m in SCRIPT.finditer(read(f)):
            scripts[m[1]].add(rel)
    print(f"\nКнопки запускают скрипты, их страницы и тексты этот обход не видит: {len(scripts)}")
    for name in sorted(scripts):
        print(f"  {name}  <- {sorted(scripts[name])[0]}" + (f" и ещё {len(scripts[name]) - 1}" if len(scripts[name]) > 1 else ""))
    # Фраза, разрезанная между страницами: страница с одной кнопкой обрывается, следующая начинается со строчной или с многоточия
    cut = []
    for rel, f in english.items():
        page = read(f)
        folder = rel.rsplit("/", 1)[0]
        base = re.match(r"(\d+)", rel.rsplit("/", 1)[1])
        targets = [f"scripts/quests/{m[0]}/{m[1]}" for m in QUEST_PAGE.findall(page)]
        targets += [f"html/{m}" for m in LINK.findall(page)]
        if base:
            targets += [f"{folder}/{base[1]}-{m}.htm" for m in CHAT.findall(page) if m != "0"]
        if len(targets) != 1 or targets[0] not in english:
            continue
        text = [strip_markup(b).strip() for b in blocks_of(page.split("<a ", 1)[0]) if has_words(b)]
        follow = [strip_markup(b).strip() for b in blocks_of(read(english[targets[0]])) if has_words(b)]
        follow = [b for b in follow if not re.fullmatch(r"[^.!?]*:", b)]  # без строки с именем NPC
        if text and follow and (not FULL_STOP.search(text[-1]) or text[-1].endswith("...")) and re.match(r"[a-z]|\.\.\.", follow[0]):
            cut.append((rel, targets[0]))
    print(f"\nФразы, разрезанные между страницами, по всей игре: {len(cut)}")
    for a, b in cut[:20]:
        print(f"  {a} -> {b}")
    print("Разметка клиента в английских страницах, текста которой нет на сервере: <fstring> "
          + str(sum(1 for f in english.values() if "<fstring" in read(f).lower())) + " страниц")


# ---------- Самопроверка самого скрипта ----------

EN_PAGE = (
    '<html><body>Darin:<br>\r\n'
    'Give this to <font color="LEVEL">Roxxy</font> in Talking Island Village, %name%. It costs 30 Adena &$57;.<br>\r\n'
    '<a action="bypass -h Quest Q00001_LettersOfLove 30048-02.html">I will do it.</a><br>\r\n'
    '<button action="bypass -h npc_%objectId%_Chat 1" value="Back" width=70 height=21>\r\n'
    '</body></html>\r\n'
)
RU_PAGE = (
    '<html><body>Дарин:<br>\r\n'
    'Отнесите это <font color="LEVEL">Рокси</font> в деревню Говорящего Острова, %name%. Цена 30 аден &$57;.<br>\r\n'
    '<a action="bypass -h Quest Q00001_LettersOfLove 30048-02.html">Я сделаю это.</a><br>\r\n'
    '<button action="bypass -h npc_%objectId%_Chat 1" value="Назад" width=70 height=21>\r\n'
    '</body></html>\r\n'
)
MUTATIONS = {
    "теги: убран br": lambda s: s.replace("Дарин:<br>", "Дарин:"),
    "теги: новый font": lambda s: s.replace("Цена", '<font color="LEVEL">Цена</font>'),
    "теги: изменён bypass": lambda s: s.replace("30048-02.html", "30048-03.html"),
    "теги: изменена ширина кнопки": lambda s: s.replace("width=70", "width=90"),
    "подстановки: потеряна": lambda s: s.replace(", %name%", ""),
    "подстановки: опечатка": lambda s: s.replace("%name%", "%имя%"),
    "сущности: потеряна": lambda s: s.replace(" &$57;", ""),
    "числа: изменено": lambda s: s.replace("30 аден", "31 аден"),
    "переводы строк: LF вместо CRLF": lambda s: s.replace("\r\n", "\n"),
    "переводы строк: конец файла": lambda s: s[:-2],
    "кодировка: BOM": lambda s: "﻿" + s,
    "символы: битая кодировка": lambda s: s.replace("Дарин", "Ð”Ð°Ñ€Ð¸Ð½"),
    "без перевода: кусок остался английским": lambda s: s.replace("Я сделаю это.", "I will do it."),
    "латиница: кусок переведён наполовину": lambda s: s.replace("Я сделаю это.", "Я do это."),
    "без перевода: подпись кнопки": lambda s: s.replace('value="Назад"', 'value="Back"'),
    "глоссарий: другой перевод термина": lambda s: s.replace("Говорящего Острова", "Острова Разговоров"),
    "глоссарий: имя выпало": lambda s: s.replace("Рокси", "ей"),
    "длина: страница длиннее лимита": lambda s: s.replace("Я сделаю это.", "Я сделаю это." + "я" * PAGE_LIMIT),
}


def selftest() -> int:
    bad = 0

    def run(tree: Path, listed=None, tm=None) -> Report:
        gl = Glossary(None)
        for en, stem in (("Talking Island Village", "говорящ"), ("Roxxy", "рокси"), ("Adena", "аден")):
            gl.terms.append((en, term_pattern(en), [stem]))
        rep = Report()
        check_tree(tree, "ru", listed, gl, tm, rep)
        return rep

    def tree(pages: dict[str, str]) -> Path:
        root = Path(tempfile.mkdtemp(prefix="ru_pages_check_"))
        (root / "html").mkdir()
        (root / "scripts").mkdir()
        for rel, text in pages.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(text.encode("utf-8"))
        return root

    def expect(name: str, rep: Report, must_fail: bool, kind: str | None = None) -> None:
        nonlocal bad
        good = bool(rep.errors) == must_fail and (kind is None or kind in rep.kinds)
        print(("ok    " if good else "WRONG ") + name + ("" if good else f": {rep.errors[:3]}"))
        bad += 0 if good else 1

    en_rel, ru_rel = "html/default/30048.htm", "lang/ru/data/html/default/30048.htm"
    expect("чистая пара проходит", run(tree({en_rel: EN_PAGE, ru_rel: RU_PAGE})), False)
    for name, mutate in MUTATIONS.items():
        changed = mutate(RU_PAGE)
        assert changed != RU_PAGE, name
        expect(name, run(tree({en_rel: EN_PAGE, ru_rel: changed})), True, name.split(":")[0])
    expect("нет пары: русская страница без английской", run(tree({"html/default/1.htm": EN_PAGE, ru_rel: RU_PAGE})), True, "нет пары")
    expect("нет страницы: партия требует перевод", run(tree({en_rel: EN_PAGE}), listed=[en_rel]), True, "нет страницы")
    other = "html/guard/30049.htm"
    other_en = EN_PAGE.replace("Darin", "Bonnie")
    other_ru = RU_PAGE.replace("Дарин", "Бонни")
    expect("разделённый кусок переведён одинаково", run(tree({en_rel: EN_PAGE, ru_rel: RU_PAGE, other: other_en, "lang/ru/data/" + other: other_ru})), False)
    expect("разный перевод: разделённый кусок", run(tree({en_rel: EN_PAGE, ru_rel: RU_PAGE, other: other_en, "lang/ru/data/" + other: other_ru.replace("Я сделаю это.", "Сделаю.")})), True, "разный перевод")
    expect("близнец без перевода: страница с тем же текстом", run(tree({en_rel: EN_PAGE, ru_rel: RU_PAGE, other: EN_PAGE})), True, "близнец без перевода")
    aren = term_pattern("Aren")
    good = not aren.search("Aren't you afraid?") and bool(aren.search("Go to Aren's house.")) and bool(aren.search("Aren"))
    print(("ok    " if good else "WRONG ") + "глоссарий: Aren't - не термин Aren, Aren's - термин")
    bad += 0 if good else 1
    g = Glossary(None)
    g.terms = [("Teleport Device", term_pattern("Teleport Device"), None), ("Teleport", term_pattern("Teleport"), ["телепорт"]), ("orc", term_pattern("orc"), ["орк"])]
    g.terms.sort(key=lambda t: -len(t[0]))
    good = (not g.missing("Teleport Device:", "Teleport Device:") and not g.missing("Hunt Balor Orc Fighters.", "Охотьтесь на Balor Orc Fighters.")
            and g.missing("Use the Teleport.", "Используйте устройство.") == ["Teleport -> телепорт"] and g.missing("An orc came.", "Пришел гоблин.") == ["orc -> орк"])
    print(("ok    " if good else "WRONG ") + "глоссарий: латиница как в английском и строка «= -» не ошибка, пропуск перевода - ошибка")
    bad += 0 if good else 1
    expect("близнец с переводом проходит", run(tree({en_rel: EN_PAGE, ru_rel: RU_PAGE, other: EN_PAGE, "lang/ru/data/" + other: RU_PAGE})), False)
    expect("нет в памяти: английская страница изменилась", run(tree({en_rel: EN_PAGE, ru_rel: RU_PAGE}), tm={}), True, "нет в памяти")
    print(f"Самопроверка: {'ошибок ' + str(bad) if bad else 'все случаи сработали'}")
    return 1 if bad else 0


def load_tm(path: Path) -> dict[str, str]:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and "\t" in line:
            en, ru = line.split("\t", 1)
            out[norm(en)] = norm(ru)
    return out


def load_list(path: Path) -> list[str]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            out.append(line)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tree", type=Path)
    ap.add_argument("--lang", default="ru")
    ap.add_argument("--list", type=Path, help="файл партии: страницы относительно data/")
    ap.add_argument("--tm", type=Path, help="память переводов: сверить, что страницы совпадают с ней")
    ap.add_argument("--glossary", type=Path, default=GLOSSARY)
    ap.add_argument("--shared", action="store_true", help="показать швы с непереведённым")
    ap.add_argument("-v", action="store_true", help="все ошибки, а не первые 60")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if not args.tree:
        ap.error("нужен --tree или --selftest")
    data = data_root(args.tree)
    rep = Report()
    glossary = Glossary(args.glossary)
    english, russian, pairs = check_tree(data, args.lang, load_list(args.list) if args.list else None, glossary, load_tm(args.tm) if args.tm else None, rep)
    print(f"Страниц сверено: {len(russian)}, кусков текста: {len(pairs)}, терминов глоссария: {len(glossary.terms)}, ошибок: {len(rep.errors)}")
    for kind, n in rep.kinds.most_common():
        print(f"  {kind}: {n}")
    for page, what in rep.errors if args.v else rep.errors[:60]:
        print(f"{page}: {what}")
    if args.shared:
        seams(data, english, russian, pairs)
    return 1 if rep.errors else 0


if __name__ == "__main__":
    sys.exit(main())
