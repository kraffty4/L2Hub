"""Движок патчей сборщика: unified diff накладывается байт в байт, без преобразования переводов строк (как git apply
с core.autocrlf=false вне репозитория). Без зависимостей — его берут и сборщик (builder.py), и инструменты в tools/
(phantoms_glue.py, ru_pages.py), которым не нужен весь лаунчер."""

import re
from pathlib import Path

HUNK_RE = re.compile(rb"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


class PatchError(RuntimeError):
    pass


class FilePatch:
    """Один файл из патча: старый и новый путь (None — /dev/null) и ханки (строка в старом, в новом, старые строки, новые)."""

    def __init__(self, old: str | None, new: str | None):
        self.old, self.new = old, new
        self.hunks: list[tuple[int, int, list[bytes], list[bytes]]] = []

    @property
    def path(self) -> str:
        return self.new or self.old


def _patch_path(raw: bytes) -> str | None:
    p = raw.split(b"\t", 1)[0].rstrip(b"\r\n").decode("utf-8")
    if p == "/dev/null":
        return None
    return p[2:] if p[:2] in ("a/", "b/") else p


def parse_patch(data: bytes) -> list[FilePatch]:
    lines = data.splitlines(keepends=True)
    out, i = [], 0
    while i < len(lines):
        if not (lines[i].startswith(b"--- ") and i + 1 < len(lines) and lines[i + 1].startswith(b"+++ ")):
            i += 1
            continue
        fp = FilePatch(_patch_path(lines[i][4:]), _patch_path(lines[i + 1][4:]))
        out.append(fp)
        i += 2
        while i < len(lines) and (m := HUNK_RE.match(lines[i])):
            old_n = int(m[2]) if m[2] is not None else 1
            new_n = int(m[4]) if m[4] is not None else 1
            old, new, last = [], [], None
            i += 1
            while i < len(lines) and (len(old) < old_n or len(new) < new_n or lines[i].startswith(b"\\")):
                ln = lines[i]
                tag, body = ln[:1], ln[1:]
                if ln in (b"\n", b"\r\n"):  # пустая строка контекста без пробела
                    tag, body = b" ", ln
                if tag == b"\\":  # «\ No newline at end of file» — у предыдущей строки нет перевода строки
                    for block in last or ():
                        block[-1] = block[-1].rstrip(b"\r\n")
                elif tag == b" ":
                    old.append(body)
                    new.append(body)
                    last = (old, new)
                elif tag == b"-":
                    old.append(body)
                    last = (old,)
                elif tag == b"+":
                    new.append(body)
                    last = (new,)
                else:
                    raise PatchError(f"непонятная строка в ханке {fp.path}: {ln[:60]!r}")
                i += 1
            fp.hunks.append((int(m[1]), int(m[3]), old, new))
    return out


def _find(lines: list[bytes], block: list[bytes], near: int) -> int:
    """Где в файле стоит блок: ближайшее к ожидаемой строке точное совпадение, -1 — нигде."""
    n = len(block)
    for d in range(len(lines) + 1):
        for pos in (near - d, near + d) if d else (near,):
            if 0 <= pos <= len(lines) - n and lines[pos:pos + n] == block:
                return pos
    return -1


def apply_file(content: bytes | None, fp: FilePatch, reverse: bool = False) -> bytes | None:
    """Наложить (или снять) патч одного файла; None на выходе — файл удалён. Не сошлось — PatchError."""
    creates, deletes = fp.old is None, fp.new is None
    if reverse:
        creates, deletes = deletes, creates
    if creates and content is not None:
        raise PatchError(f"{fp.path}: файл уже есть")
    if not creates and content is None:
        raise PatchError(f"{fp.path}: файла нет")
    lines = content.splitlines(keepends=True) if content else []
    delta = 0
    for old_start, new_start, old, new in fp.hunks:
        a, b, start = (new, old, new_start) if reverse else (old, new, old_start)
        # Пустой блок (только вставка): «-10,0» — вставить после 10-й строки.
        near = max(start - 1 + delta, 0) if a else min(start + delta, len(lines))
        pos = _find(lines, a, near) if a else near
        if pos < 0:
            raise PatchError(f"{fp.path}: ханк @@ -{start} не сошёлся")
        lines[pos:pos + len(a)] = b
        delta += len(b) - len(a)
    if deletes:
        if lines:
            raise PatchError(f"{fp.path}: после удаления остались строки")
        return None
    return b"".join(lines)


def apply_patch(root: Path, patch: Path, reverse: bool = False, check: bool = False) -> None:
    """Весь патч на дерево root: сначала всё в памяти, потом на диск — не сошёлся один файл, не тронут ни один."""
    results = {}
    for fp in parse_patch(patch.read_bytes()):
        src = root / fp.path  # переименований в наших патчах нет: старый и новый путь совпадают
        current = results[fp.path] if fp.path in results else (src.read_bytes() if src.is_file() else None)
        results[fp.path] = apply_file(current, fp, reverse)
    if check:
        return
    for rel, data in results.items():
        target = root / rel
        if data is None:
            target.unlink(missing_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)


def patch_files(patch: Path) -> set[str]:
    return {fp.path for fp in parse_patch(patch.read_bytes())}
