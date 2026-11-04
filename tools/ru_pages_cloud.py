"""Пакет перевода для облачных сессий и обратный сбор ответов. Порядок и цены — docs/RU_PAGES.md, «Перевод в облаке».

  python -X utf8 tools/ru_pages_cloud.py build --base <база с модами> --archive <архив Mobius> --tm <память> --glossary docs/RU_GLOSSARY.md --out <папка пакета>
  python -X utf8 tools/ru_pages_cloud.py collect --pkg <папка пакета> --tm <память> --glossary-in docs/RU_GLOSSARY.md --glossary-out <файл>

build кладёт в пакет только куски, которые есть в чистом архиве: наш текст из модов туда не попадает и переводится здесь. Куски не пересекаются между частями,
поэтому параллельные сессии не конфликтуют. Часть 00 — имена: она идёт первой, остальные проверяются по её ответам.
collect принимает готовые файлы каждой части (проверка tools/part.py), дописывает память переводов и общий глоссарий и печатает расхождения терминов между частями.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ru_pages import SPEAKER, parts_of
from ru_pages_check import load_tm, norm, strip_markup

HERE = Path(__file__).resolve().parent
TEMPLATES = HERE / "ru_pages" / "cloud"
SECTIONS = ["scripts/quests", "scripts/village_master", "html/default", "html/guard", "html/trainer", "html/merchant"]
SKIP = ("html/admin/",)  # окна GM, игроку их не показывают
QUESTS_PART = 900_000
CHUNK = 25_000
CONTEXT_MIN, CONTEXT_MAX = 40, 100
NAMES_CHUNK = 400
NAME = re.compile(r"[A-Z][a-z'\-]+(?:(?: of the | of | the | de | von | )[A-Z][a-z'\-]+)*")


def pages_of(data: Path) -> list[str]:
    out = []
    for sub in ("html", "scripts"):
        for f in (data / sub).rglob("*"):
            if f.is_file() and f.suffix.lower() in (".htm", ".html"):
                out.append(f.relative_to(data).as_posix())
    return sorted(out)


def clean_segments(archive: Path, build: str) -> set[str]:
    """Куски чистого Mobius: всё остальное написали мы."""
    out = set()
    with zipfile.ZipFile(archive) as z:
        root = next(n[: -len("build.xml")] for n in z.namelist() if n.endswith(f"{build}/build.xml"))
        for prefix in (f"{root}dist/game/data/html/", f"{root}dist/game/data/scripts/"):
            for n in z.namelist():
                if n.startswith(prefix) and n.lower().endswith((".htm", ".html")):
                    page = z.read(n).decode("utf-8", "surrogateescape")
                    out.update(norm(chunk) for is_text, chunk in parts_of(page) if is_text)
    return out


def npc_table(data: Path) -> dict[str, tuple[int, str, str]]:
    """Имя NPC -> (id, пол, титул); и id -> то же, для заголовков страниц."""
    table = {}
    for f in (data / "stats" / "npcs").rglob("*.xml"):
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'<npc id="(\d+)"([^>]*)>(.*?)</npc>', text, re.S):
            head = m[2]
            name = re.search(r'name="([^"]*)"', head)
            if not name:
                continue
            title = re.search(r'title="([^"]*)"', head)
            sex = re.search(r'sex="?([A-Z]+)"?|<sex>([A-Z]+)</sex>', m[3])
            table[m[1]] = (name[1], (sex[1] or sex[2]) if sex else "?", title[1] if title else "")
    return table


def unit_of(rel: str) -> str:
    """Что нельзя резать между частями: квест целиком, страницу целиком."""
    if rel.startswith("scripts/quests/"):
        return "/".join(rel.split("/")[:3])
    return rel


def gather(data: Path, clean: set[str], tm: dict[str, str]) -> tuple[list[tuple[str, list[tuple[str, str]]]], Counter]:
    """Страницы в порядке перевода: (путь, строки). Строка — ('new', кусок) или ('ctx', кусок). Каждый кусок новый только один раз на весь пакет."""
    ours = data / "lang" / "ru" / "data"  # русская страница из наших модов уже есть, переводить незачем
    pages = [p for p in pages_of(data) if not p.startswith(SKIP) and not (ours / p).exists()]
    ordered = []
    for section in SECTIONS:
        ordered += [p for p in pages if p.startswith(section + "/")]
    done = set(ordered)
    ordered += [p for p in pages if p not in done]
    assigned: set[str] = set()
    out = []
    stats = Counter()
    for rel in ordered:
        page = (data / rel).read_bytes().decode("utf-8", "surrogateescape")
        lines = []
        shown = set()
        for is_text, chunk in parts_of(page):
            if not is_text:
                continue
            key = norm(chunk)
            if any(0xDC80 <= ord(c) <= 0xDCFF for c in key):
                stats["битая кодировка"] += 1
                continue
            if key in shown:
                continue
            shown.add(key)
            if key not in clean:
                stats["наш текст, не в пакете"] += len(key)
                continue
            if key in tm or key in assigned:
                lines.append(("ctx", key))
                continue
            assigned.add(key)
            before = page[:page.find(chunk)]
            button = before.endswith('value="') or (before.rstrip().endswith('">') and "<a " in before[before.rfind("<"):])
            lines.append(("btn" if button else "new", key))
        if any(kind != "ctx" for kind, _ in lines):
            out.append((rel, lines))
    return out, stats


def new_chars(lines: list[tuple[str, str]]) -> int:
    return sum(len(k) for kind, k in lines if kind != "ctx")


def split_parts(pages: list) -> list[dict]:
    """Части: квесты поровну примерно по QUESTS_PART, остальные разделы основного списка одной частью, всё прочее — L1."""
    quests = [p for p in pages if p[0].startswith("scripts/quests/")]
    main = [p for p in pages if any(p[0].startswith(s + "/") for s in SECTIONS[1:])]
    in_main = {p[0] for p in quests} | {p[0] for p in main}
    later = [p for p in pages if p[0] not in in_main]
    total = sum(new_chars(p[1]) for p in quests)
    count = max(1, round(total / QUESTS_PART))
    target = total / count
    parts = []
    units = defaultdict(list)
    for p in quests:
        units[unit_of(p[0])].append(p)
    cur, size = [], 0
    for unit in sorted(units):
        cur += units[unit]
        size += sum(new_chars(p[1]) for p in units[unit])
        if size >= target and len(parts) < count - 1:
            parts.append({"name": f"{len(parts) + 1:02d}", "title": f"квесты {cur[0][0].split('/')[2]} .. {cur[-1][0].split('/')[2]}", "pages": cur})
            cur, size = [], 0
    if cur:
        parts.append({"name": f"{len(parts) + 1:02d}", "title": f"квесты {cur[0][0].split('/')[2]} .. {cur[-1][0].split('/')[2]}", "pages": cur})
    parts.append({"name": f"{len(parts) + 1:02d}", "title": "village_master, html/default, guard, trainer, merchant", "pages": main})
    parts.append({"name": "L1", "title": "потом: всё остальное, кроме html/admin. Не запускать без решения автора", "pages": later})
    return parts


def write_part(out: Path, part: dict, npcs: dict) -> dict:
    d = out / "parts" / part["name"]
    (d / "chunks").mkdir(parents=True)
    (d / "answers").mkdir()
    chunks, ids = [], {}
    buf: list[str] = []
    first, size = 1, 0

    def flush() -> None:
        nonlocal buf, size, first
        if not buf:
            return
        chunks.append({"file": f"{len(chunks) + 1:03d}", "first": first, "last": len(ids), "chars": size, "body": "\n".join(buf) + "\n"})
        buf, size, first = [], 0, len(ids) + 1

    for rel, lines in part["pages"]:
        if size >= CHUNK:
            flush()
        speaker = SPEAKER.match(rel.rsplit("/", 1)[1])
        who = npcs.get(speaker[1]) if speaker else None
        buf.append(f"## {rel}" + (f"  ({who[0]}, {who[1]})" if who else ""))
        for kind, key in lines:
            if kind == "ctx":
                if len(key) >= CONTEXT_MIN:
                    buf.append("~ " + key[:CONTEXT_MAX])
                continue
            ids[str(len(ids) + 1)] = key
            size += len(key)
            buf.append(f"[{len(ids)}]{' (кнопка)' if kind == 'btn' else ''} {key}")
        buf.append("")
    flush()
    write_chunks(d, part, chunks, ids, "text")
    return {"name": part["name"], "title": part["title"], "chars": sum(c["chars"] for c in chunks), "files": len(chunks), "segments": len(ids), "pages": len(part["pages"])}


def write_chunks(d: Path, part: dict, chunks: list[dict], ids: dict, kind: str) -> None:
    name = part["name"]
    total = len(chunks)
    for c in chunks:
        head = f"# Часть {name}, файл {c['file']} из {total:03d}. Ответ: parts/{name}/answers/{c['file']}.tsv. Переводить только эту часть, следующую не брать.\n\n"
        (d / "chunks" / f"{c['file']}.txt").write_text(head + c.pop("body"), encoding="utf-8", newline="\n")
    (d / "index.json").write_text(json.dumps({"title": part["title"], "kind": kind, "chunks": chunks}, ensure_ascii=False, indent=0), encoding="utf-8", newline="\n")
    (d / "segments.json").write_text(json.dumps(ids, ensure_ascii=False, indent=0), encoding="utf-8", newline="\n")
    (d / "PART.md").write_text(
        f"# Часть {name}: {part['title']}\n\n"
        "**СТОП: сессия переводит только эту часть и заканчивает работу. Следующую часть сама не берёт, даже если осталось время и она свободна.**\n\n"
        f"Файлов {total}, знаков {sum(c['chars'] for c in chunks)}. Порядок работы — `AGENTS.md`. Очередной файл — `python tools/part.py next {name}`.\n",
        encoding="utf-8", newline="\n")


def glossary_terms(path: Path) -> set[str]:
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if line.lstrip().startswith("|") and len(cells) >= 3 and cells[0] not in ("English", ""):
            out.add(cells[0].strip("`").lower())
    return out


def names_part(out: Path, pages: list, npcs: dict, glossary: Path) -> dict:
    """Часть 00: имена, которые встречаются в разных частях. Параллельные сессии иначе транслитерируют их каждая по-своему."""
    known = glossary_terms(glossary)
    caps, lower, sample = Counter(), Counter(), {}
    for rel, lines in pages:
        for kind, key in lines:
            if kind == "ctx":
                continue
            text = " ".join(strip_markup(key).split())
            for word in re.findall(r"\b[a-z][a-z'\-]+\b", text):
                lower[word] += 1
            for m in NAME.finditer(text):
                head = text[:m.start()].rstrip()
                name = re.sub(r"'s$", "", re.sub(r"^The ", "", m[0]))
                at_start = not head or head[-1] in ".!?:\"("
                if (at_start and " " not in name) or name.startswith("I'"):
                    continue  # первое слово фразы с заглавной ещё не имя
                caps[name] += 1
                sample.setdefault(name, text[max(0, m.start() - 25):m.end() + 25])
    by_name = {}
    for number, (name, sex, title) in npcs.items():
        by_name.setdefault(name, (sex, title))
    rows = []
    for name, n in caps.items():
        if name.lower() in known or any(w.lower() in ("i", "ii", "iii") for w in name.split()):
            continue
        is_npc = name in by_name
        if n < (2 if is_npc else 4):
            continue
        if " " not in name and lower[name.lower()] >= n:
            continue  # обычное слово, а не имя
        rows.append((name, n, is_npc))
    rows.sort(key=lambda r: (-r[1], r[0]))
    ids, chunks, buf, first = {}, [], [], 1
    for name, n, is_npc in rows:
        ids[str(len(ids) + 1)] = name
        extra = f" | NPC {by_name[name][0]}{', ' + by_name[name][1] if by_name[name][1] else ''}" if is_npc else ""
        buf.append(f"[{len(ids)}] {name} | {n}{extra} | {sample[name]}")
        if len(buf) == NAMES_CHUNK:
            chunks.append({"file": f"{len(chunks) + 1:03d}", "first": first, "last": len(ids), "chars": sum(map(len, buf)), "body": "\n".join(buf) + "\n"})
            buf, first = [], len(ids) + 1
    if buf:
        chunks.append({"file": f"{len(chunks) + 1:03d}", "first": first, "last": len(ids), "chars": sum(map(len, buf)), "body": "\n".join(buf) + "\n"})
    part = {"name": "00", "title": "имена: идёт первой, остальные проверяются по её ответам"}
    d = out / "parts" / "00"
    (d / "chunks").mkdir(parents=True)
    (d / "answers").mkdir()
    write_chunks(d, part, chunks, ids, "names")
    return {"name": "00", "title": part["title"], "chars": sum(c["chars"] for c in chunks), "files": len(chunks), "segments": len(ids), "pages": 0}


def samples(tm: dict[str, str], count: int = 30) -> str:
    rows = sorted((hashlib.sha1(en.encode()).hexdigest(), en, ru) for en, ru in tm.items() if 80 <= len(en) <= 320 and "<" not in en)
    lines = ["# Образцы готового перевода", "", "Пробная партия: так звучит перевод. Читать один раз в начале сессии.", ""]
    for _, en, ru in rows[:count]:
        lines += [f"- {en}", f"  - {ru}", ""]
    return "\n".join(lines)


def cmd_build(args) -> int:
    out = args.out
    if out.exists() and any(out.iterdir()):
        raise SystemExit(f"{out} не пуста: сборка пакета не перезаписывает то, что там есть")
    data = args.base / "dist" / "game" / "data"
    tm = load_tm(args.tm)
    print("чистый архив...", flush=True)
    clean = clean_segments(args.archive, args.build)
    npcs = npc_table(data)
    print(f"кусков в архиве {len(clean)}, NPC {len(npcs)}, память {len(tm)}", flush=True)
    pages, stats = gather(data, clean, tm)
    parts = split_parts(pages)
    (out / "docs").mkdir(parents=True)
    (out / "tools").mkdir()
    (out / "tm").mkdir()
    (out / "parts").mkdir()
    rows = [names_part(out, pages, npcs, args.glossary)]
    for part in parts:
        rows.append(write_part(out, part, npcs))
    text = args.glossary.read_text(encoding="utf-8")
    head = "# Глоссарий русских диалогов\n\nОдин термин везде переводится одинаково, `tools/part.py` проверяет основу термина в русском куске. Править этот файл нельзя: новые термины — в `terms.tsv` своей части (`AGENTS.md`).\n\n"
    body = text[text.index("## Правила языка"):]
    body = body.replace("`tools/ru_pages.py dump` пишет его у страницы", "он указан в заголовке страницы").replace(" Шрифты клиента High Five английские (`docs/RU_CHAT.md`), на ASCII и основной кириллице ничего не теряем. В клиенте это не смотрели.", "")
    (out / "docs" / "RU_GLOSSARY.md").write_text(head + body, encoding="utf-8", newline="\n")
    (out / "docs" / "SAMPLES.md").write_text(samples(tm), encoding="utf-8", newline="\n")
    shutil.copyfile(args.tm, out / "tm" / "tm_ct26_highfive.tsv")
    shutil.copyfile(TEMPLATES / "part.py", out / "tools" / "part.py")
    shutil.copyfile(TEMPLATES / "AGENTS.md", out / "AGENTS.md")
    (out / "CLAUDE.md").write_text("@AGENTS.md\n", encoding="utf-8", newline="\n")
    (out / ".gitattributes").write_text("* text=auto eol=lf\n", encoding="utf-8", newline="\n")
    lines = ["# Части", "", "Часть 00 идёт первой: её ответы нужны остальным для сверки имён. L1 — «потом», только по решению автора.", "",
             "| Часть | Что | Знаков | Файлов | Кусков | Как запустить |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['name']} | {r['title']} | {r['chars']} | {r['files']} | {r['segments']} | Переведи часть {r['name']} по AGENTS.md: только её, потом остановись. |")
    (out / "parts" / "INDEX.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print("\n".join(lines[4:]))
    for key, value in stats.items():
        print(f"{key}: {value}")
    return 0


def cmd_collect(args) -> int:
    """Ответы частей в память переводов. Файл с ошибками не принимается; в части, где готово не всё, берутся только чистые файлы."""
    tool = args.pkg / "tools" / "part.py"
    tm = load_tm(args.tm) if args.tm.is_file() else {}
    before = len(tm)
    terms: dict[str, Counter] = defaultdict(Counter)
    rejected = 0
    for d in sorted((args.pkg / "parts").iterdir()):
        if not (d / "index.json").is_file():
            continue
        index = json.loads((d / "index.json").read_text(encoding="utf-8"))
        ids = json.loads((d / "segments.json").read_text(encoding="utf-8"))
        accepted = 0
        for chunk in index["chunks"]:
            answer = d / "answers" / f"{chunk['file']}.tsv"
            if not answer.is_file():
                continue
            r = subprocess.run([sys.executable, "-X", "utf8", str(tool), "check", d.name, chunk["file"]], capture_output=True, text=True, encoding="utf-8")
            if r.returncode:
                print(f"{d.name}/{chunk['file']}: не принят\n{r.stdout[:600]}")
                rejected += 1
                continue
            accepted += 1
            for line in answer.read_text(encoding="utf-8").splitlines():
                m = re.match(r"(\d+)\s+(.+)$", line)
                if not m:
                    continue
                if index["kind"] == "names":
                    terms[ids[m[1]]][m[2].strip()] += 1
                else:
                    tm[ids[m[1]]] = norm(m[2])
        print(f"{d.name}: принято файлов {accepted} из {len(index['chunks'])}")
        extra = d / "terms.tsv"
        if extra.is_file():
            for line in extra.read_text(encoding="utf-8").splitlines():
                if "\t" in line:
                    en, ru = line.split("\t", 1)
                    terms[en.strip()][ru.strip()] += 1
    args.tm.write_text("".join(f"{en}\t{tm[en]}\n" for en in sorted(tm)), encoding="utf-8", newline="\n")
    conflicts = {en: c for en, c in terms.items() if len(c) > 1}
    from ru_pages_terms import MARK, fix, stem_of
    head = args.glossary_in.read_text(encoding="utf-8").partition("\n" + MARK)[0]  # прежняя таблица облака собирается заново
    table = head.rstrip("\n") + f"\n\n{MARK}\n\n| English | Русский | Основа | Замечание |\n|---|---|---|---|\n"
    for en in sorted(terms):
        ru = terms[en].most_common(1)[0][0]
        table += f"| {en} | {ru} | {'' if ru == '=' else stem_of(ru)} | {'разнобой: ' + ' / '.join(terms[en]) if en in conflicts else ''} |\n"
    args.glossary_out.write_text(table, encoding="utf-8", newline="\n")
    print(f"в памяти было {before}, стало {len(tm)}; терминов {len(terms)}, разнобой {len(conflicts)}; файлов не принято {rejected}")
    for en, c in list(conflicts.items())[:40]:
        print(f"  {en}: {dict(c)}")
    unsettled = fix(args.tm, args.glossary_out)  # решения по терминам (tools/ru_pages_terms.py): без них сбор откатил бы их к сырым ответам
    return 1 if rejected or unsettled else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("build")
    p.add_argument("--base", type=Path, required=True)
    p.add_argument("--archive", type=Path, required=True)
    p.add_argument("--build", default="L2J_Mobius_CT_2.6_HighFive")
    p.add_argument("--tm", type=Path, required=True)
    p.add_argument("--glossary", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(fn=cmd_build)
    p = sub.add_parser("collect")
    p.add_argument("--pkg", type=Path, required=True)
    p.add_argument("--tm", type=Path, required=True)
    p.add_argument("--glossary-in", type=Path, required=True)
    p.add_argument("--glossary-out", type=Path, required=True)
    p.set_defaults(fn=cmd_collect)
    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
