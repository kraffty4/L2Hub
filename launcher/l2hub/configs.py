"""Настройки сборки: схема из .ini (Mobius) или .properties (L2J 2.0.6 La2offline) и запись правок с бэкапом.

Схема — файл -> разделы -> ключи с описанием, значением и умолчанием. Описание ключа — блок
комментариев прямо над ним; строка «Default: ...» в нём даёт умолчание; заголовки вида
«# ----- / # Название / # -----» дают разделы. Тип выводится из значения.
"""

import re
import shutil
from datetime import datetime
from pathlib import Path

DEFAULT_RE = re.compile(r"^\s*(?:Default|По умолчанию):\s*(.*)$", re.IGNORECASE)  # второе — у La2offline
DASHES_RE = re.compile(r"^#\s*(?:-{5,}|={5,})\s*$")  # рамка заголовка раздела: «-----» у Mobius, «=====» у L2J 2.0.6
# Доступом к базе управляет лаунчер: эти файлы в схему не попадают и не правятся.
SKIP_FILES = {"Database.ini"}
# Значения таких ключей не выгружаются и не правятся через схему.
SECRET_RE = re.compile(r"password|secret|token|^StatusPW$", re.IGNORECASE)  # StatusPW — пароль телнета L2J 2.0.6
NOT_SECRET = {"AllowChangePassword"}
# Конфиги по семейству сервера (servers.config_family); формат один: «# описание», «Ключ = значение».
PATTERNS = {"l2j2": "*.properties"}  # старые L2J 2.0 — у сборок-комбайнов вроде La2offline
COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)


def _outside_comments(text: str, matches) -> list:
    """Совпадения вне <!-- … --> (в комментариях конфигов Mobius лежат примеры с теми же тегами)."""
    spans = [m.span() for m in COMMENT_RE.finditer(text)]
    return [m for m in matches if not any(a <= m.start() < b for a, b in spans)]


def xml_attr(text: str, key: str) -> str | None:
    """Значение первого атрибута key="…" в XML-конфиге (ClassMaster.xml: classChangeEnabled); нет — None."""
    found = _outside_comments(text, re.finditer(rf'\b{re.escape(key)}="([^"]*)"', text))
    return found[0].group(1) if found else None


def _set_xml_attr(text: str, key: str, value: str) -> str:
    found = _outside_comments(text, re.finditer(rf'\b{re.escape(key)}="([^"]*)"', text))
    if not found:
        raise KeyError(key)
    m = found[0]
    return text[:m.start(1)] + value.replace('"', "&quot;") + text[m.end(1):]


def _set_xml_block(text: str, parent: str, block: str, value: str) -> str:
    """Все <block>…</block> внутри <parent …> (вне комментариев) — заменить на value сразу после открывающего тега."""
    head = _outside_comments(text, re.finditer(rf"<{re.escape(parent)}\b[^>]*>[ \t]*\r?\n?", text))
    if not head:
        raise KeyError(parent)
    newline = "\r\n" if "\r\n" in text else "\n"
    old = _outside_comments(text, re.finditer(rf"[ \t]*<{re.escape(block)}\b.*?</{re.escape(block)}>[ \t]*\r?\n?", text, re.DOTALL))
    for m in reversed(old):
        text = text[:m.start()] + text[m.end():]
    at = head[0].end()
    return text[:at] + value.replace("\n", newline) + text[at:]


def pattern(p: dict) -> str:
    from . import servers  # noqa: PLC0415 — servers тянет catalog и l2solo; configs остаётся лёгким

    return PATTERNS.get(servers.config_family(p), "*.ini")


def is_secret(key: str) -> bool:
    return bool(SECRET_RE.search(key)) and key not in NOT_SECRET


def _continues(line: str) -> bool:
    """Строка ключа продолжается на следующей, если кончается нечётным числом «\\» (как в Java Properties)."""
    s = line.rstrip()
    return (len(s) - len(s.rstrip("\\"))) % 2 == 1


def _logical_value(lines: list[str], i: int, first: str) -> tuple[str, int]:
    """Значение ключа со строками-продолжениями и индекс последней строки, которую оно заняло."""
    parts = [first]
    while _continues(parts[-1]) and i + 1 < len(lines):
        parts[-1] = parts[-1].rstrip()[:-1]
        i += 1
        parts.append(lines[i].lstrip())
    return "".join(parts), i


def infer_type(value: str) -> str:
    v = value.strip()
    if v.lower() in ("true", "false"):
        return "bool"
    if re.fullmatch(r"-?\d+", v):
        return "int"
    if re.fullmatch(r"-?\d+\.\d*", v):
        return "float"
    if ";" in v or ("," in v and " " not in v):
        return "list"
    return "string"


def parse_ini(path: Path) -> dict:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    sections = []
    current = {"title": "", "keys": []}
    comments: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        if DASHES_RE.match(line):
            # Заголовок раздела: строка тире, строки с названием, снова тире.
            title_lines = []
            j = i + 1
            while j < len(lines) and lines[j].startswith("#") and not DASHES_RE.match(lines[j]):
                title_lines.append(lines[j].lstrip("#").strip())
                j += 1
            if j < len(lines) and DASHES_RE.match(lines[j]):
                if current["keys"] or current["title"]:
                    sections.append(current)
                current = {"title": " ".join(t for t in title_lines if t), "keys": []}
                comments = []
                i = j + 1
                continue
        if line.startswith("#"):
            comments.append(line.lstrip("#").strip())
        elif not line.strip():
            comments = []
        elif "=" in line:
            key, _, value = line.partition("=")
            key = key.strip()
            value, i = _logical_value(lines, i, value)
            default = None
            desc = []
            for c in comments:
                m = DEFAULT_RE.match(c)
                if m:
                    default = m.group(1).strip()
                elif c:
                    desc.append(c)
            secret = is_secret(key)
            current["keys"].append({
                "key": key,
                "value": "" if secret else value.strip(),
                "secret": secret,
                "default": default,
                "type": infer_type(value),
                "description": " ".join(desc),
            })
            comments = []
        i += 1
    if current["keys"] or current["title"]:
        sections.append(current)
    return {"file": path.name, "sections": sections}


def config_files(server: Path, part: str, glob: str = "*.ini") -> list[Path]:
    cfg = server / part / "config"
    files = sorted(cfg.glob(glob)) + sorted((cfg / "Custom").glob(glob))
    return [f for f in files if f.name not in SKIP_FILES]


def build(server: Path, glob: str = "*.ini") -> dict:
    result = {}
    for part in ("game", "login"):
        result[part] = [
            dict(parse_ini(f), group="Custom" if f.parent.name == "Custom" else "Core")
            for f in config_files(server, part, glob)
        ]
    return result


def _locate(server: Path, part: str, file: str) -> Path:
    if part not in ("game", "login") or file in SKIP_FILES or "/" in file or "\\" in file:
        raise ValueError(f"Нельзя править {part}/{file}")
    for candidate in (server / part / "config" / file, server / part / "config" / "Custom" / file):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"Нет файла {part}/{file}")


def ensure_value(server: Path, backup_root: Path, part: str, file: str, key: str, value: str, comment: str = "") -> bool:
    """Ключ в .ini равен value: есть — правится как в save_edits, нет — дописывается в конец файла (с комментарием над ним).
    Уже так или файла нет — ничего не трогается (False). Перед записью — бэкап файла, как у save_edits."""
    try:
        path = _locate(server, part, file)
    except FileNotFoundError:
        return False
    text = path.read_bytes().decode("utf-8-sig")
    m = re.search(rf"^\s*{re.escape(key)}\s*=[ \t]*(.*?)\s*$", text, re.MULTILINE)
    if m:
        if m.group(1) == value:
            return False
        save_edits(server, backup_root, [{"part": part, "file": file, "key": key, "value": value}])
        return True
    raw = path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    target = backup_root / datetime.now().strftime("%Y-%m-%d_%H-%M-%S") / path.relative_to(server)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
    tail = (newline if raw and not raw.endswith(b"\n") else "") + newline
    tail += "".join(f"# {c}{newline}" for c in comment.splitlines()) + f"{key} = {value}{newline}"
    path.write_bytes(raw + tail.encode("utf-8"))
    return True


def save_edits(server: Path, backup_root: Path, edits: list[dict]) -> dict:
    """Пишет правки {part, file, key, value} в .ini. Комментарии и порядок строк не трогаются,
    меняется только значение в строке ключа. Оригиналы изменённых файлов копируются в backup_root.
    В .xml-конфиге key — атрибут (первый вне комментариев); правка {part, file, parent, block, value} заменяет все блоки
    <block> внутри <parent> на value (ClassMaster.xml: цены профессий)."""
    by_file: dict[tuple[str, str], list[dict]] = {}
    for e in edits:
        if is_secret(e.get("key", "")):
            raise ValueError(f"Ключ {e['key']} правится только лаунчером")
        by_file.setdefault((e["part"], e["file"]), []).append(e)

    stamp_dir = backup_root / datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    n = 2
    while stamp_dir.exists():  # две записи в одну секунду — вторая не должна затереть бэкап первой
        stamp_dir = stamp_dir.with_name(f"{stamp_dir.name.split('~')[0]}~{n}")
        n += 1
    planned = []
    for (part, file), items in by_file.items():
        path = _locate(server, part, file)
        raw = path.read_bytes()
        bom = raw.startswith(b"\xef\xbb\xbf")
        text = raw.decode("utf-8-sig")
        if path.suffix.lower() == ".xml":
            for e in items:
                value = str(e["value"])
                try:
                    text = _set_xml_block(text, e["parent"], e["block"], value) if e.get("block") else _set_xml_attr(text, e["key"], value.strip())
                except KeyError as err:
                    raise KeyError(f"В {part}/{file} нет {err.args[0]}") from err
            planned.append((path, part, text, bom))
            continue
        newline = "\r\n" if "\r\n" in text else "\n"
        lines = text.split(newline)
        for e in items:
            value = str(e["value"]).replace("\r", " ").replace("\n", " ").strip()
            pattern = re.compile(rf"^(\s*{re.escape(e['key'])}\s*=[ \t]*)(.*)$")
            for idx, line in enumerate(lines):
                m = pattern.match(line)
                if m:
                    # Значение с продолжениями «\» заменяется одной строкой.
                    _, last = _logical_value(lines, idx, m.group(2))
                    lines[idx:last + 1] = [m.group(1) + value]
                    break
            else:
                raise KeyError(f"В {part}/{file} нет ключа {e['key']}")
        planned.append((path, part, newline.join(lines), bom))

    # Сначала все проверки, потом бэкап и запись: либо применяются все правки, либо ни одной.
    for path, part, new_text, bom in planned:
        rel = path.relative_to(server)
        target = stamp_dir / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    for path, part, new_text, bom in planned:
        path.write_bytes((b"\xef\xbb\xbf" if bom else b"") + new_text.encode("utf-8"))
    return {"saved": len(edits), "files": len(planned), "backup": str(stamp_dir)}
