"""Патч «Язык текстов L2Hub» для хроники Mobius: patches/<id>/0099-l2hub-language.patch.

Наши моды пишут игроку и лаунчеру по-русски: окно .world («Мир сейчас»), «Летопись» (log/L2HubChronicle.json), объявления
эльпи и суперчемпиона, самопроверка модов (log/L2HubSelfTest.json). Здесь у них появляется английский: ключ L2HubLanguage
(ru | en) в config/Custom/L2HubWorld.ini — общем ini модуля фантомов; лаунчер пишет его по языку своего окна.

Как вставляется (одинаково у всех хроник, по какому бы варианту файла ни шло):
  - каждый русский строковый литерал в коде (не в комментариях, не в строках лога LOGGER) оборачивается в tr("…");
  - в начало класса — поле L2HUB_ENGLISH (читает L2HubLanguage), таблица L2HUB_EN «русская строка → английская» и tr();
    строки в коде остаются русскими, чего в таблице нет — остаётся по-русски (как переводчик окна лаунчера);
  - суперчемпион: текст объявления берётся из SuperChampionAnnounceText (у нас — русский); при en и русском тексте в ini
    — английский текст Mobius по умолчанию;
  - сезонные события (LongTimeEvent): объявления начала и конца из config.xml событий идут через tr(), в таблицу класса —
    русские тексты этих config.xml;
  - окна доски Alt+B (BOARD_HTML): английского оригинала у них нет — у Mobius другие кнопки, — поэтому английская копия
    получается из русской по таблице (текст между тегами и value=, ключ — без пробелов и &nbsp; по краям) и лежит на месте
    окна, а русская уезжает в data/lang/ru (многоязычность включает patches/<id>/0030, tools/l2hub_lang_html.py).
Таблица — tools/l2hub_lang/en.json {русская строка (как в рантайме): английская}; одна на все хроники.
Остальные окна-html (окно моба по Shift+клику, диалоги событий) здесь не переводятся — они в 0030. Окна модуля фантомов
(.buddy, .hunters, .daily, .raid) — PhantomText.java в phantoms/core, tools/phantoms_lang.py.

    python -X utf8 tools/l2hub_lang.py <id>           — собрать и проверить патч (в дерево не пишет), сколько строк без перевода
    python -X utf8 tools/l2hub_lang.py <id> --todo F  — заодно строки без перевода — в JSON-список F
    python -X utf8 tools/l2hub_lang.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0099 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
Номер 0099 — последним: слой оборачивает в tr() строки всех наших патчей самопроверки и модов, которые ниже него.
"""
import html
import json
import re
import sys
from pathlib import Path

import l2hub_olympiad_small as small
import l2hub_lang_html
import patch_layers

NAME = "0099-l2hub-language.patch"  # у хроники уже есть *-l2hub-language.patch под другим номером — берётся он
MARK = "L2Hub: язык наших текстов"
TABLE = small.ROOT / "tools" / "l2hub_lang" / "en.json"
# Файлы модов (путь от корня исходников хроники; glob — где путь у хроник разный).
TARGETS = [
    "dist/game/data/scripts/custom/L2HubWorldInfo/L2HubWorldInfo.java",
    "dist/game/data/scripts/custom/L2HubChronicle/L2HubChronicle.java",
    "dist/game/data/scripts/custom/L2HubSelfTest/L2HubSelfTest.java",
    "dist/game/data/scripts/**/events/Elpies/Elpies.java",
    "java/org/l2jmobius/gameserver/entity/actor/SuperChampion.java",
    "java/org/l2jmobius/gameserver/mechanics/script/LongTimeEvent.java",
    "dist/game/data/scripts/handlers/bypass/communityboard/L2HubBoard.java",
]
BOARD_HTML = "dist/game/data/html/CommunityBoard/Custom/**/*.html"
EVENT_CONFIGS = "dist/game/data/scripts/events/*/config.xml"
MSG_OLD = 'final String msgText = d.getAttributes().getNamedItem("text").getNodeValue();'
MSG_NEW = 'final String msgText = tr(d.getAttributes().getNamedItem("text").getNodeValue());'
CYR = re.compile(r"[А-Яа-яЁё]")
ANNOUNCE_OLD = "ChampionMonstersConfig.SUPER_CHAMPION_ANNOUNCE_TEXT.replace("
ANNOUNCE_NEW = ("(L2HUB_ENGLISH && ChampionMonstersConfig.SUPER_CHAMPION_ANNOUNCE_TEXT.matches(\"(?s).*[\\\\u0400-\\\\u04FF].*\") ? "
                "\"Legendary Champion %name% (%level%) has appeared near %town%!\" : ChampionMonstersConfig.SUPER_CHAMPION_ANNOUNCE_TEXT).replace(")


def files_of(src: Path) -> list[str]:
    out = []
    for pattern in TARGETS:
        found = sorted(p.relative_to(src).as_posix() for p in src.glob(pattern)) if "*" in pattern else ([pattern] if (src / pattern).is_file() else [])
        out += found
    return out


def literals(code: str) -> list[tuple[int, int]]:
    """Строковые литералы Java вне комментариев: [(начало, конец)] — с кавычками. Текстовые блоки и символы пропускаются."""
    spans, i, n = [], 0, len(code)
    while i < n:
        c = code[i]
        if code.startswith("//", i):
            i = code.find("\n", i)
            i = n if i < 0 else i
        elif code.startswith("/*", i):
            i = code.find("*/", i + 2)
            i = n if i < 0 else i + 2
        elif code.startswith('"""', i):
            i = code.find('"""', i + 3)
            i = n if i < 0 else i + 3
        elif c == "'":
            j = i + 1
            while j < n and code[j] != "'":
                j += 2 if code[j] == "\\" else 1
            i = j + 1
        elif c == '"':
            j = i + 1
            while j < n and code[j] != '"':
                j += 2 if code[j] == "\\" else 1
            spans.append((i, j + 1))
            i = j + 1
        else:
            i += 1
    return spans


def unescape(body: str) -> str:
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == "u" and len(m.group(1)) == 5
                  else {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\"}.get(m.group(1), m.group(1)), body)


def escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")


def translate(code: str, table: dict[str, str]) -> tuple[str, list[str], list[str]]:
    """Обернуть русские литералы в tr(); вернуть код, использованные строки и строки без перевода."""
    out, last, used = [], 0, []
    for start, end in literals(code):
        body = code[start + 1:end - 1]
        if not CYR.search(body):
            continue
        line = code[code.rfind("\n", 0, start) + 1:start]
        if "LOGGER." in line or re.search(r"\bcase\s*$", line) or line.rstrip().endswith("@"):
            continue
        out.append(code[last:start] + "tr(" + code[start:end] + ")")
        last = end
        used.append(unescape(body))
    out.append(code[last:])
    seen = list(dict.fromkeys(used))
    return "".join(out), seen, [s for s in seen if s not in table]


def helper(nl: str, indent: str, entries: list[tuple[str, str]]) -> list[str]:
    rows = [f'{indent}\tjava.util.Map.entry("{escape(ru)}", "{escape(en)}")' for ru, en in entries]
    table = ([f"{indent}private static final java.util.Map<String, String> L2HUB_EN = java.util.Map.ofEntries("]
             + [r + ("," if k < len(rows) - 1 else ");") for k, r in enumerate(rows)]) if rows else \
        [f"{indent}private static final java.util.Map<String, String> L2HUB_EN = java.util.Map.of();"]
    return [
        f"{indent}// {MARK} (tools/l2hub_lang.py): L2HubLanguage в config/Custom/L2HubWorld.ini — ru или en, лаунчер пишет его",
        f"{indent}// по языку окна. Строки в коде — по-русски, tr() при en подменяет их английскими из L2HUB_EN; чего там нет — по-русски.",
        f"{indent}private static final boolean L2HUB_ENGLISH = l2hubEnglish();",
        *table,
        indent,
        f"{indent}private static boolean l2hubEnglish()",
        f"{indent}{{",
        f"{indent}\tfinal java.util.Properties config = new java.util.Properties();",
        f"{indent}\ttry (java.io.Reader reader = java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get(\"./config/Custom/L2HubWorld.ini\"), java.nio.charset.StandardCharsets.UTF_8))",
        f"{indent}\t{{",
        f"{indent}\t\tconfig.load(reader);",
        f"{indent}\t}}",
        f"{indent}\tcatch (Exception e)",
        f"{indent}\t{{",
        f"{indent}\t\treturn false;",
        f"{indent}\t}}",
        f"{indent}\treturn \"en\".equalsIgnoreCase(config.getProperty(\"L2HubLanguage\", \"ru\").trim());",
        f"{indent}}}",
        indent,
        f"{indent}private static String tr(String text)",
        f"{indent}{{",
        f"{indent}\treturn L2HUB_ENGLISH ? L2HUB_EN.getOrDefault(text, text) : text;",
        f"{indent}}}",
        indent,
    ]


def event_texts(src: Path) -> list[str]:
    """Русские объявления сезонных событий из их config.xml (атрибут text)."""
    out = []
    for f in sorted(src.glob(EVENT_CONFIGS)):
        for m in re.finditer(r'\btext="([^"]*)"', f.read_text(encoding="utf-8")):
            text = html.unescape(m.group(1))
            if CYR.search(text) and text not in out:
                out.append(text)
    return out


def edit(rel: str, data: bytes, table: dict[str, str], events: list[str]) -> tuple[bytes, list[str], list[str]]:
    nl = "\r\n" if b"\r\n" in data else "\n"
    code = data.decode("utf-8")
    if re.search(r"\btr\s*\(", code):
        sys.exit(f"{rel}: уже есть tr( — имя занято")
    code, used, missing = translate(code, table)
    if rel.endswith("SuperChampion.java"):
        if code.count(ANNOUNCE_OLD) != 1:
            sys.exit(f"{rel}: не нашёл {ANNOUNCE_OLD}")
        code = code.replace(ANNOUNCE_OLD, ANNOUNCE_NEW)
    if rel.endswith("LongTimeEvent.java"):
        if code.count(MSG_OLD) != 1:
            sys.exit(f"{rel}: не нашёл {MSG_OLD}")
        code = code.replace(MSG_OLD, MSG_NEW)
        used += [t for t in events if t not in used]
        missing += [t for t in events if t not in table and t not in missing]
    lines = code.split(nl)
    head = next(i for i, l in enumerate(lines) if re.match(r"^public (final )?class \w+", l))
    brace = next(i for i in range(head, len(lines)) if lines[i].strip() == "{")
    entries = [(ru, table[ru]) for ru in used if ru in table]
    lines = lines[:brace + 1] + helper(nl, "\t", entries) + lines[brace + 1:]
    return nl.join(lines).encode("utf-8"), used, missing


def lang_path(rel: str) -> str:
    return "dist/game/data/lang/ru/" + rel[len("dist/game/"):]


def html_english(text: str, table: dict[str, str]) -> tuple[str, list[str], list[str]]:
    """Английская копия русского окна; вернуть её, использованные строки и строки без перевода (остаются по-русски)."""
    used = []

    def segment(s: str) -> str:
        if not CYR.search(s):
            return s
        m = re.fullmatch(r"((?:\s|&nbsp;)*)(.*?)((?:\s|&nbsp;)*)", s, re.S)
        used.append(m.group(2))
        return m.group(1) + table.get(m.group(2), m.group(2)) + m.group(3)

    text = re.sub(r'(\bvalue\s*=\s*")([^"]*)(")', lambda m: m.group(1) + segment(m.group(2)) + m.group(3), text, flags=re.I)
    text = re.sub(r"(>)([^<>]*)(<)", lambda m: m.group(1) + segment(m.group(2)) + m.group(3), text)
    seen = list(dict.fromkeys(used))
    return text, seen, [s for s in seen if s not in table]


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    todo = sys.argv[sys.argv.index("--todo") + 1] if "--todo" in sys.argv else None
    table = json.loads(TABLE.read_text(encoding="utf-8")) if TABLE.is_file() else {}
    src = small.tree(cid)
    own = sorted((small.ROOT / "patches" / cid).glob("*-l2hub-language.patch"))
    name = own[0].name if own else NAME
    files = files_of(src)
    if not files:
        sys.exit(f"{cid}: нет файлов модов L2Hub")
    pages = sorted(p.relative_to(src).as_posix() for p in src.glob(BOARD_HTML))
    moved = [lang_path(rel) for rel in pages]
    current = {rel: (src / rel).read_bytes() for rel in files + pages}
    upper, peeled = [], {}
    if any(MARK.encode() in data for data in current.values()):
        if not (small.ROOT / "patches" / cid / name).is_file():
            sys.exit(f"в дереве уже есть «{MARK}», а patches/{cid}/{name} нет — нечем снять")
        peeled, upper = patch_layers.peel(cid, src, name, files + pages + moved)
        current = {rel: peeled[rel] for rel in files + pages}
        print(f"{cid}: прежний {name} снят с копии дерева, патч собирается заново")
    new, missing, total = {}, [], 0
    events = event_texts(src)
    for rel in files:
        new[rel], used, miss = edit(rel, current[rel], table, events)
        total += len(used)
        missing += [m for m in miss if m not in missing]
        print(f"  {rel.split('/')[-1]}: строк {len(used)}, без перевода {len(miss)}")
    plan = {rel: (current[rel], data) for rel, data in new.items()}
    for rel in pages:  # файлов data/lang/ru под текущим деревом нет: после снятия прежнего слоя — None, а до первого прогона их и не было
        text = current[rel].decode("utf-8")
        if CYR.search(text):
            english, used, miss = html_english(text, table)
            total += len(used)
            missing += [m for m in miss if m not in missing]
            print(f"  {rel.split('CommunityBoard/')[-1]}: строк {len(used)}, без перевода {len(miss)}")
            plan[rel] = (current[rel], english.encode("utf-8"))
            plan[lang_path(rel)] = (None, current[rel])
            new[rel], new[lang_path(rel)] = plan[rel][1], plan[lang_path(rel)][1]
    if todo:
        Path(todo).write_text(json.dumps(missing, ensure_ascii=False, indent=0), encoding="utf-8")
    patch, bad = l2hub_lang_html.build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled)
    same = (small.ROOT / "patches" / cid / name).is_file() and (small.ROOT / "patches" / cid / name).read_bytes() == patch
    print(f"{cid}: файлов {len(new)}, строк {total}, без перевода {len(missing)}; наложение на копию — байт в байт"
          + ("; патч тот же, что в patches/" if same else "; патч новый против patches/"))
    if write:
        (small.ROOT / "patches" / cid / name).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{name} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
