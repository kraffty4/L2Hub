"""Перевод страниц диалогов по памяти переводов: куски текста — в память, русские страницы — из английских подстановкой кусков.

  python -X utf8 tools/ru_pages.py dump  --tree <дерево> --list <партия> [--tm <память>] --out <файл>   куски без перевода, с контекстом и номерами
  python -X utf8 tools/ru_pages.py merge --dump <файл>.json --ids <файл> [--ids ...] --tm <память>      строки «номер перевод» — в память
  python -X utf8 tools/ru_pages.py build --tree <дерево> --list <партия> --tm <память> --out <папка>    страницы в <папка>/dist/game/data/lang/ru/data/...
  python -X utf8 tools/ru_pages.py stats --tree <дерево> --list <партия> --tm <память>                  знаки, куски, повторы
  python -X utf8 tools/ru_pages.py base  --archive <архив Mobius> --patches <папка> --before 0220 --out <папка>   база с модами: только dist/game/data
  python -X utf8 tools/ru_pages.py patch --base <база> --tree <папка с dist> --header <файл> --out <патч>          патч из новых файлов с проверкой наложения

base и patch берут разбор патчей из launcher/l2hub/builder.py: запускать питоном из launcher/.venv.

Страница получается из английской заменой только кусков текста, поэтому теги, команды и переводы строк совпадают сами. Куски те же, что сверяет
tools/ru_pages_check.py: граница — блочный тег и перевод строки, font, b, i, u остаются внутри куска. Один и тот же английский кусок в памяти один.
Куда класть готовое и как считать патч — docs/RU_PAGES.md.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ru_pages_check import BUTTON_VALUE, INLINE, PLACEHOLDER, TAG_NAME, TOKEN, data_root, load_list, load_tm, norm, read, strip_markup

SPEAKER = re.compile(r"(\d{5,7})")


def parts_of(page: str) -> list[tuple[bool, str]]:
    """Страница как чередование: (True, кусок текста) и (False, всё остальное). Склейка даёт исходную страницу байт в байт."""
    out: list[tuple[bool, str]] = []
    pos, start = 0, 0

    def flush(end: int) -> None:
        raw = page[start:end]
        core = raw.strip(" ")
        if not translatable(core):
            out.append((False, raw))
            return
        left = len(raw) - len(raw.lstrip(" "))
        right = len(raw) - len(raw.rstrip(" "))
        if left:
            out.append((False, raw[:left]))
        out.append((True, core))
        if right:
            out.append((False, raw[len(raw) - right:]))

    for m in TOKEN.finditer(page):
        name = TAG_NAME.match(m[0])
        if name and name[1].lower() in INLINE:
            continue
        flush(m.start())
        value = BUTTON_VALUE.search(m[0]) if m[0].startswith("<") else None
        if value and translatable(value[2]):
            out.append((False, page[m.start():m.start() + value.start(2)]))
            out.append((True, value[2]))
            out.append((False, page[m.start() + value.end(2):m.end()]))
        else:
            out.append((False, m[0]))
        start = m.end()
    flush(len(page))
    return out


def translatable(block: str) -> bool:
    return bool(re.search(r"[A-Za-z]", strip_markup(block)))


def npc_gender(data: Path) -> dict[int, tuple[str, str]]:
    out = {}
    for f in (data / "stats" / "npcs").rglob("*.xml"):
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'<npc id="(\d+)"[^>]*?name="([^"]*)"[^>]*>(.*?)</npc>', text, re.S):
            sex = re.search(r'sex="?([A-Z]+)"?|<sex>([A-Z]+)</sex>', m[3])
            out[int(m[1])] = (m[2], (sex[1] or sex[2]) if sex else "?")
    return out


def cmd_dump(args) -> int:
    data = data_root(args.tree)
    tm = load_tm(args.tm) if args.tm and args.tm.is_file() else {}
    genders = npc_gender(data)
    seen: dict[str, int] = {}
    lines, n_pages = [], 0
    for rel in load_list(args.list):
        page = read(data / rel)
        speaker = SPEAKER.match(rel.rsplit("/", 1)[1])
        who = genders.get(int(speaker[1])) if speaker else None
        head = f"## {rel}" + (f"  ({who[0]}, {who[1]})" if who else "")
        body = []
        for is_text, chunk in parts_of(page):
            if not is_text:
                continue
            key = norm(chunk)
            if key in tm:
                continue
            if key in seen:
                body.append(f"[{seen[key]}] (повтор)")
                continue
            seen[key] = len(seen) + 1
            before = page[:page.find(chunk)]
            link = before.rstrip().endswith('">') and "<a " in before[before.rfind("<"):]
            body.append(f"[{seen[key]}]{' (кнопка)' if link else ''} {key}")
        if body:
            n_pages += 1
            lines += [head, *body, ""]
    args.out.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    args.out.with_suffix(".json").write_text(json.dumps({str(v): k for k, v in seen.items()}, ensure_ascii=False, indent=0), encoding="utf-8", newline="\n")
    print(f"страниц с новыми кусками {n_pages}, кусков {len(seen)}, знаков {sum(map(len, seen))} -> {args.out}")
    return 0


def cmd_merge(args) -> int:
    ids = json.loads(args.dump.read_text(encoding="utf-8"))
    tm = load_tm(args.tm) if args.tm.is_file() else {}
    added = 0
    for path in args.ids:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            m = re.match(r"(\d+)\s+(.+)$", line)
            if not m:
                raise SystemExit(f"{path}:{number}: нужно «номер перевод»")
            n, ru = m[1], m[2]
            if n.strip() not in ids:
                raise SystemExit(f"{path}:{number}: нет куска {n}")
            en = ids[n.strip()]
            if en in tm and tm[en] != norm(ru):
                print(f"заменён перевод: {en[:70]}")
            tm[en] = norm(ru)
            added += 1
    left = [k for k in ids if ids[k] not in tm]
    args.tm.parent.mkdir(parents=True, exist_ok=True)
    args.tm.write_text("".join(f"{en}\t{tm[en]}\n" for en in sorted(tm)), encoding="utf-8", newline="\n")
    print(f"принято {added}, в памяти {len(tm)}, без перевода {len(left)}: {left[:30]}")
    return 0


def cmd_build(args) -> int:
    data = data_root(args.tree)
    tm = load_tm(args.tm)
    root = args.out / "dist" / "game" / "data" / "lang" / "ru" / "data"
    missing: list[str] = []
    written = skipped = ours = same = 0
    for rel in load_list(args.list):
        if (data / "lang" / "ru" / "data" / rel).exists():
            ours += 1  # русская страница уже есть в базе, из наших модов: её правят там
            continue
        raw = (data / rel).read_bytes()
        page = raw.decode("utf-8", "surrogateescape")
        out = []
        holes = []
        for is_text, chunk in parts_of(page):
            if not is_text:
                out.append(chunk)
                continue
            ru = tm.get(norm(chunk))
            if ru is None:
                holes.append(f"{rel}: {norm(chunk)[:80]}")
                out.append(chunk)
            else:
                out.append(ru)
        if holes and args.partial:
            skipped += 1  # страница с дырой остаётся английской целиком: куски двух языков в одном окне хуже
            continue
        missing += holes
        result = "".join(out).encode("utf-8", "surrogateescape")
        if result == raw:
            same += 1  # переводить нечего (подстановки, строки клиента, имена латиницей): сервер и так отдаст английскую
            continue
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(result)
        written += 1
    print(f"страниц {written}, без перевода кусков {len(missing)}, русская уже есть в базе {ours}, совпала с английской {same}" + (f", оставлено английскими {skipped}" if args.partial else ""))
    for line in missing[:30]:
        print("  " + line)
    return 1 if missing else 0


def cmd_stats(args) -> int:
    data = data_root(args.tree)
    tm = load_tm(args.tm) if args.tm and args.tm.is_file() else {}
    total_en = Counter()
    pages = load_list(args.list)
    for rel in pages:
        for is_text, chunk in parts_of(read(data / rel)):
            if is_text:
                total_en[norm(chunk)] += 1
    all_chars = sum(len(k) * n for k, n in total_en.items())
    uniq_chars = sum(map(len, total_en))
    ru_chars = sum(len(tm.get(k, "")) for k in total_en)
    print(f"страниц {len(pages)}")
    print(f"кусков всего {sum(total_en.values())}, без повторов {len(total_en)}")
    print(f"английских знаков всего {all_chars}, без повторов {uniq_chars}")
    print(f"русских знаков без повторов {ru_chars}" + (f", в {ru_chars / uniq_chars:.2f} раза длиннее" if uniq_chars else ""))
    return 0


def builder_module():
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "launcher"))
    from l2hub import builder
    return builder


def cmd_base(args) -> int:
    """Рабочее дерево сервера не читается: там чужие незалитые правки."""
    builder = builder_module()
    prefix = "dist/game/data/"
    with zipfile.ZipFile(args.archive) as z:
        root = next(n[: -len("build.xml")] for n in z.namelist() if n.endswith(f"/{args.build}/build.xml") or n == f"{args.build}/build.xml")
        for n in z.namelist():
            rel = n[len(root):]
            if n.startswith(root) and rel.startswith(prefix) and not n.endswith("/"):
                out = args.out / rel
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(z.read(n))
    count = 0
    for patch in sorted(args.patches.glob("*.patch")):
        if patch.name >= args.before:
            continue
        count += 1
        result = {}
        for fp in builder.parse_patch(patch.read_bytes()):
            if fp.path.startswith(prefix):
                f = args.out / fp.path
                current = result[fp.path] if fp.path in result else (f.read_bytes() if f.is_file() else None)
                result[fp.path] = builder.apply_file(current, fp)
        for rel, data in result.items():
            f = args.out / rel
            if data is None:
                f.unlink(missing_ok=True)
            else:
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_bytes(data)
    print(f"база в {args.out}: наложено патчей {count}, до {args.before}")
    return 0


def cmd_patch(args) -> int:
    """Временный git вне репозитория и без преобразования строк: CRLF-файлы дают CRLF-строки в патче. Только новые файлы: английские страницы партия не меняет."""
    builder = builder_module()
    files = [f for f in (args.tree / "dist").rglob("*") if f.is_file()]
    bad = [f.relative_to(args.tree).as_posix() for f in files if (args.base / f.relative_to(args.tree)).exists()]
    if bad:
        print(f"Файлы уже есть в базе, новым патчем их не добавить: {bad[:10]}")
        return 1

    def git(*a: str, cwd: Path) -> bytes:
        return subprocess.run(["git", "-c", "core.autocrlf=false", "-c", "core.safecrlf=false", "-c", "core.longpaths=true", *a], cwd=cwd, check=True, capture_output=True).stdout

    with tempfile.TemporaryDirectory(prefix="ru_pages_") as tmp:
        g = Path(tmp)
        git("init", "-q", cwd=g)
        for f in files:
            target = g / f.relative_to(args.tree)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(f.read_bytes())
        git("add", "-A", cwd=g)
        body = git("diff", "--cached", "--no-color", "--no-renames", "--full-index", "-U3", cwd=g)
    header = args.header.read_bytes().rstrip(b"\r\n")
    args.out.write_bytes(header + b"\n\n" + body)
    wrong = [fp.path for fp in builder.parse_patch(args.out.read_bytes()) if builder.apply_file(None, fp) != (args.tree / fp.path).read_bytes()]
    print(f"{args.out.name}: файлов {len(files)}, размер {args.out.stat().st_size}, не сошлось при наложении {len(wrong)}")
    for path in wrong[:20]:
        print("  " + path)
    return 1 if wrong else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("dump")
    p.add_argument("--tree", type=Path, required=True)
    p.add_argument("--list", type=Path, required=True)
    p.add_argument("--tm", type=Path)
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(fn=cmd_dump)
    p = sub.add_parser("merge")
    p.add_argument("--dump", type=Path, required=True)
    p.add_argument("--ids", type=Path, action="append", required=True)
    p.add_argument("--tm", type=Path, required=True)
    p.set_defaults(fn=cmd_merge)
    p = sub.add_parser("build")
    p.add_argument("--tree", type=Path, required=True)
    p.add_argument("--list", type=Path, required=True)
    p.add_argument("--tm", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--partial", action="store_true", help="страницы с непереведёнными кусками не писать, а не падать: перевод идёт частями")
    p.set_defaults(fn=cmd_build)
    p = sub.add_parser("stats")
    p.add_argument("--tree", type=Path, required=True)
    p.add_argument("--list", type=Path, required=True)
    p.add_argument("--tm", type=Path)
    p.set_defaults(fn=cmd_stats)
    p = sub.add_parser("base")
    p.add_argument("--archive", type=Path, required=True)
    p.add_argument("--build", default="L2J_Mobius_CT_2.6_HighFive")
    p.add_argument("--patches", type=Path, required=True)
    p.add_argument("--before", required=True)
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(fn=cmd_base)
    p = sub.add_parser("patch")
    p.add_argument("--base", type=Path, required=True)
    p.add_argument("--tree", type=Path, required=True)
    p.add_argument("--header", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(fn=cmd_patch)
    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
