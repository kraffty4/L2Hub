"""Очередь и проверка перевода одной части. Только стандартная библиотека Python 3.10+, дерево сервера не нужно.

  python tools/part.py next <часть>           следующий файл части с его текстом, или СТОП, если часть готова
  python tools/part.py done <часть> <файл>    проверка файла, коммит, push и следующий файл. При ошибках код 1 и дальше не идёт
  python tools/part.py check <часть> [файл]   только проверка, код 1 при любой ошибке
  python tools/part.py status                 сводка по всем частям
  python tools/part.py selftest               проверка самой проверки
"""
import json
import re
import shutil
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLACEHOLDER = re.compile(r"%[A-Za-z0-9_]+%")
ENTITY = re.compile(r"&[$#@]?[A-Za-z0-9]+;")
TAG = re.compile(r"<[^<>]*>")
NUMBER = re.compile(r"\d+(?:[.,]\d+)*")
LATIN = re.compile(r"[A-Za-z][A-Za-z'\-]*")
CYRILLIC = re.compile(r"[А-Яа-я]")
ANSWER = re.compile(r"(\d+)\s+(.+)$")
WORD = re.compile(r"[A-Za-z][A-Za-z'\-]*")
ENDING = re.compile(r"(?:ия|ья|ие|ье|ей|ий|ый|ой|ая|яя|а|я|о|е|и|ы|й|ь|у|ю)$")
BANNED = set("ёЁ«»—–…")
ALLOWED = set(map(chr, range(32, 127))) | {chr(c) for c in range(0x410, 0x450)} | {"№", "·"}
ALWAYS_LATIN = {"SP", "XP", "HP", "MP", "CP", "PvP", "PK", "NPC", "A", "B", "C", "D", "S"}
LONG = 150  # с этой длины сверяется объём: перевод втрое короче или вдвое длиннее оригинала — почти всегда пересказ
MIN_RATIO, MAX_RATIO = 0.4, 2.2


def strip_markup(text: str) -> str:
    return ENTITY.sub(" ", PLACEHOLDER.sub(" ", TAG.sub(" ", text)))


def stem_of(ru: str) -> str:
    words = re.findall(r"[а-я]+", ru.lower())
    if not words:
        return ""
    word = max(words, key=len)
    cut = ENDING.sub("", word)
    return cut if len(cut) >= 2 else word  # слабая проверка короткого имени лучше ложной ошибки на падеже: Ева, Еву


class Glossary:
    """Термины: таблицы docs/RU_GLOSSARY.md, ответы части 00 (имена), terms.tsv текущей части. Русский «=» — термин остаётся латиницей."""

    def __init__(self, root: Path, part: str | None):
        self.latin = set(ALWAYS_LATIN)
        self.by_word: dict[str, list[tuple[str, re.Pattern, list[str]]]] = {}
        table = root / "docs" / "RU_GLOSSARY.md"
        if table.is_file():
            for line in table.read_text(encoding="utf-8").splitlines():
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if not line.lstrip().startswith("|") or len(cells) < 3 or cells[0] in ("English", "") or set(cells[0]) <= {"-", " ", ":"}:
                    continue
                en = cells[0].strip("`")
                stems = [s.strip().lower() for s in cells[2].split(",") if s.strip()]
                self.add(en, cells[1], stems)
        names = root / "parts" / "00"
        if names.is_dir() and part != "00":
            ids = json.loads((names / "segments.json").read_text(encoding="utf-8"))
            for f in sorted((names / "answers").glob("*.tsv")):
                for line in f.read_text(encoding="utf-8").splitlines():
                    m = ANSWER.match(line)
                    if m and m[1] in ids:
                        self.add(ids[m[1]], m[2].strip(), [stem_of(m[2])])
        if part:
            terms = root / "parts" / part / "terms.tsv"
            if terms.is_file():
                for line in terms.read_text(encoding="utf-8").splitlines():
                    if "\t" in line:
                        en, ru = line.split("\t", 1)
                        self.add(en.strip(), ru.strip(), [stem_of(ru)])

    def add(self, en: str, ru: str, stems: list[str]) -> None:
        if ru == "=":
            self.latin.update(LATIN.findall(en))
            stems = [en.lower()]
        stems = [s for s in stems if s]
        if not stems or not WORD.search(en):
            return
        flags = re.I if en.islower() else 0
        pattern = re.compile(rf"(?<![A-Za-z]){re.escape(en)}(?:s|es)?(?![A-Za-z])", flags)
        self.by_word.setdefault(WORD.search(en)[0].lower(), []).append((en, pattern, stems))

    def missing(self, en: str, ru: str) -> list[str]:
        text = strip_markup(en)
        target = strip_markup(ru).lower().replace("ё", "е")
        words = set()
        for w in WORD.findall(text):
            w = w.lower()
            words.update((w, re.sub(r"(?:'s|es|s)$", "", w)))
        found = [t for w in words for t in self.by_word.get(w, ())]
        out = []
        for term, pattern, stems in sorted(found, key=lambda t: -len(t[0])):  # длинный термин закрывает вложенный
            if pattern.search(text):
                text = pattern.sub(" ", text)
                if not any(s in target for s in stems):
                    out.append(f"{term} -> {'/'.join(stems)}")
        return out


def diff(a: list[str], b: list[str]) -> str:
    x, y = Counter(a), Counter(b)
    return f"нет {sorted((x - y).elements())}, лишнее {sorted((y - x).elements())}"


def check_pair(en: str, ru: str, glossary: Glossary, skip_glossary: bool = False) -> list[str]:
    errors = []
    if "\t" in ru:
        errors.append("табуляция в переводе")
    for kind, pattern in (("подстановки", PLACEHOLDER), ("сущности", ENTITY)):
        x, y = pattern.findall(en), pattern.findall(ru)
        if Counter(x) != Counter(y):
            errors.append(f"{kind}: {diff(x, y)}")
    x, y = TAG.findall(en), TAG.findall(ru)
    if x != y:
        errors.append(f"теги и их порядок: было {x}, стало {y}")
    x, y = NUMBER.findall(strip_markup(en)), NUMBER.findall(strip_markup(ru))
    if Counter(x) != Counter(y):
        errors.append(f"числа: {diff(x, y)}")
    bad = sorted(set(strip_markup(ru)) & BANNED)
    if bad:
        errors.append(f"запрещённые символы {bad}: вместо них е, обычные кавычки, дефис с пробелами, три точки")
    other = sorted({c for c in strip_markup(ru) if c not in ALLOWED and c not in en and c not in BANNED})
    if other:
        errors.append(f"символы вне набора: {other[:10]}")
    words_en = LATIN.findall(strip_markup(en))
    words_ru = LATIN.findall(strip_markup(ru))
    alien = sorted({w for w in words_ru if w not in glossary.latin})
    if words_en and not CYRILLIC.search(strip_markup(ru)) and alien:
        errors.append("нет перевода")
    elif alien:
        errors.append(f"латиница вне глоссария: {alien[:8]}. Если так и надо, строка в terms.tsv части: слово, табуляция, =")
    if len(en) >= LONG and not MIN_RATIO <= len(ru) / len(en) <= MAX_RATIO:
        errors.append(f"объём {len(ru)} знаков при {len(en)} в оригинале: это пересказ, нужен перевод целиком")
    if not skip_glossary:
        for miss in glossary.missing(en, ru):
            errors.append(f"глоссарий: {miss}")
    return errors


def check_name(en: str, ru: str) -> list[str]:
    if ru == "=":
        return []
    errors = []
    bad = sorted(set(ru) & BANNED)
    if bad:
        errors.append(f"запрещённые символы {bad}")
    if LATIN.search(ru):
        errors.append("латиница в имени: либо полностью по-русски, либо один знак =")
    if not CYRILLIC.search(ru):
        errors.append("нет перевода")
    return errors


class Part:
    def __init__(self, root: Path, name: str):
        self.root, self.name = root, name
        self.dir = root / "parts" / name
        if not self.dir.is_dir():
            raise SystemExit(f"нет части {name}")
        self.index = json.loads((self.dir / "index.json").read_text(encoding="utf-8"))
        self.chunks = self.index["chunks"]
        self.ids = json.loads((self.dir / "segments.json").read_text(encoding="utf-8"))
        self.skip = set()
        exceptions = self.dir / "exceptions.txt"
        if exceptions.is_file():
            self.skip = {m[1] for line in exceptions.read_text(encoding="utf-8").splitlines() if (m := re.match(r"(\d+)", line))}
        self.glossary = Glossary(root, name)

    def answer_path(self, chunk: dict) -> Path:
        return self.dir / "answers" / f"{chunk['file']}.tsv"

    def check_chunk(self, chunk: dict) -> list[str]:
        path = self.answer_path(chunk)
        if not path.is_file():
            return [f"{path.relative_to(self.root).as_posix()}: нет файла ответа"]
        errors = []
        seen = set()
        try:
            text = path.read_bytes().decode("utf-8")
        except UnicodeDecodeError as e:
            return [f"{path.name}: не UTF-8: {e}"]
        for number, line in enumerate(text.splitlines(), 1):
            if not line.strip():
                continue
            m = ANSWER.match(line)
            if not m:
                errors.append(f"строка {number}: нужно «номер перевод», а тут {line[:60]!r}")
                continue
            n, ru = int(m[1]), m[2].strip()
            if not chunk["first"] <= n <= chunk["last"]:
                errors.append(f"строка {number}: кусок {n} не из этого файла ({chunk['first']}-{chunk['last']})")
                continue
            if n in seen:
                errors.append(f"кусок {n}: два перевода")
            seen.add(n)
            en = self.ids[str(n)]
            found = check_name(en, ru) if self.index["kind"] == "names" else check_pair(en, ru, self.glossary, str(n) in self.skip)
            errors += [f"кусок {n}: {e}" for e in found]
        lost = [n for n in range(chunk["first"], chunk["last"] + 1) if n not in seen]
        if lost:
            errors.append(f"нет переводов, {len(lost)} шт.: {lost[:20]}")
        return errors


def cmd_next(name: str) -> int:
    part = Part(ROOT, name)
    total = len(part.chunks)
    for i, chunk in enumerate(part.chunks, 1):
        errors = part.check_chunk(chunk)
        if not errors:
            continue
        print(f"Часть {name}: файл {chunk['file']} ({i} из {total}), куски {chunk['first']}-{chunk['last']}, {chunk['chars']} знаков.")
        print(f"Ответ: parts/{name}/answers/{chunk['file']}.tsv, затем: python tools/part.py done {name} {chunk['file']}")
        if part.answer_path(chunk).is_file():
            print("Ответ уже есть, но с ошибками, исправь их:")
            for e in errors[:40]:
                print("  " + e)
            if len(errors) > 40:
                print(f"  и ещё {len(errors) - 40}")
            return 0
        print()
        print((part.dir / "chunks" / f"{chunk['file']}.txt").read_text(encoding="utf-8"))
        return 0
    print(f"Часть {name} готова: файлов {total}, ошибок нет.")
    print("СТОП. Заверши сессию. Следующую часть не бери.")
    return 0


def cmd_done(name: str, file: str) -> int:
    code = cmd_check(name, file)
    if code:
        return code
    steps = (["add", f"parts/{name}"], ["commit", "-q", "-m", f"часть {name}, файл {file.zfill(3)}"], ["push", "-q", "-u", "origin", "HEAD"])
    for step in steps:
        r = subprocess.run(["git", *step], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode:
            print(f"git {step[0]}: код {r.returncode}: {(r.stdout + r.stderr).strip()[:300]}")
            break
    return cmd_next(name)


def cmd_check(name: str, file: str | None) -> int:
    part = Part(ROOT, name)
    chunks = [c for c in part.chunks if file is None or c["file"] == file.zfill(3)]
    if not chunks:
        raise SystemExit(f"нет файла {file} в части {name}")
    errors = []
    for chunk in chunks:
        errors += [f"{chunk['file']}: {e}" for e in part.check_chunk(chunk)]
    for e in errors[:60]:
        print(e)
    if len(errors) > 60:
        print(f"и ещё {len(errors) - 60}")
    print(f"Часть {name}: файлов проверено {len(chunks)}, ошибок {len(errors)}")
    return 1 if errors else 0


def cmd_status() -> int:
    for d in sorted((ROOT / "parts").iterdir()):
        if not (d / "index.json").is_file():
            continue
        index = json.loads((d / "index.json").read_text(encoding="utf-8"))
        done = [c for c in index["chunks"] if (d / "answers" / f"{c['file']}.tsv").is_file()]
        print(f"{d.name}: файлов {len(done)} из {len(index['chunks'])}, знаков {sum(c['chars'] for c in done)} из {sum(c['chars'] for c in index['chunks'])}  {index['title']}")
    return 0


def selftest() -> int:
    good = {
        1: ("Bring 10 Wolf Pelts to <font color=\"LEVEL\">Darin</font> for %name%.", "Принесите 10 шкур волка <font color=\"LEVEL\">Дарину</font> для %name%."),
        2: ("Ask about the Airship Controller.", "Спросить об Airship Controller."),
        3: ("Quest", "Квест"),
        4: ("Spend 2,000 adena &$123; now.", "Потратьте 2,000 аден &$123; сейчас."),
    }
    spoiled = [
        (1, "Принесите шкуры волка <font color=\"LEVEL\">Дарину</font> для %name%.", "числа"),
        (1, "Принесите 10 шкур волка <font color=\"FF0000\">Дарину</font> для %name%.", "теги"),
        (1, "Принесите 10 шкур волка <font color=\"LEVEL\">Дарину</font> для вас.", "подстановки"),
        (1, "Принесите 10 шкур волка <font color=\"LEVEL\">Рокси</font> для %name%.", "глоссарий"),
        (2, "Спросить об Airship Controller и Roxxy.", "латиница"),
        (2, "Ask about the Airship Controller, please.", "нет перевода"),
        (2, "Спросить о диспетчере.", "глоссарий"),
        (3, "Quest", "нет перевода"),
        (3, "Квёст", "запрещённые"),
        (3, "Квест — да", "запрещённые"),
        (4, "Потратьте 2,000 аден сейчас.", "сущности"),
    ]
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "docs").mkdir()
        (root / "docs" / "RU_GLOSSARY.md").write_text("| English | Русский | Основа |\n|---|---|---|\n| Darin | Дарин | дарин |\n| Airship Controller | = | |\n| adena | адена | аден |\n", encoding="utf-8")
        d = root / "parts" / "01"
        (d / "answers").mkdir(parents=True)
        (d / "segments.json").write_text(json.dumps({str(k): v[0] for k, v in good.items()}), encoding="utf-8")
        (d / "index.json").write_text(json.dumps({"title": "t", "kind": "text", "chunks": [{"file": "001", "first": 1, "last": 4, "chars": 10}]}), encoding="utf-8")
        answers = d / "answers" / "001.tsv"

        def run(table: dict) -> list[str]:
            answers.write_text("".join(f"{k} {v}\n" for k, v in table.items()), encoding="utf-8")
            part = Part(root, "01")
            return part.check_chunk(part.chunks[0])

        fails = 0
        clean = run({k: v[1] for k, v in good.items()})
        if clean:
            print("чистый ответ не прошёл:", clean)
            fails += 1
        for n, ru, expect in spoiled:
            table = {k: v[1] for k, v in good.items()}
            table[n] = ru
            found = run(table)
            if not any(expect in e for e in found):
                print(f"не поймано {expect}: {ru!r}, вывод {found}")
                fails += 1
        table = {k: v[1] for k, v in good.items() if k != 4}
        if not any("нет переводов" in e for e in run(table)):
            print("не поймано: нет номера")
            fails += 1
        table = {k: v[1] for k, v in good.items()}
        table[9] = "лишний"
        if not any("не из этого файла" in e for e in run(table)):
            print("не поймано: чужой номер")
            fails += 1
        (d / "terms.tsv").write_text("Roxxy\t=\n", encoding="utf-8")
        table = {k: v[1] for k, v in good.items()}
        table[2] = "Спросить об Airship Controller и Roxxy."
        if any("латиница" in e for e in run(table)):
            print("terms.tsv не сработал")
            fails += 1
        shutil.rmtree(d / "answers")
    print("Самопроверка: " + ("все случаи сработали" if not fails else f"провалов {fails}"))
    return 1 if fails else 0


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:]
    if args[:1] == ["next"] and len(args) == 2:
        return cmd_next(args[1])
    if args[:1] == ["done"] and len(args) == 3:
        return cmd_done(args[1], args[2])
    if args[:1] == ["check"] and len(args) in (2, 3):
        return cmd_check(args[1], args[2] if len(args) == 3 else None)
    if args == ["status"]:
        return cmd_status()
    if args == ["selftest"]:
        return selftest()
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
