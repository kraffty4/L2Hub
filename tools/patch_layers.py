"""Слои патчей хроники для генераторов (tools/l2hub_pvp_events.py, tools/l2hub_super_champion.py).

Генератор пересобирает свой патч patches/<id>/<name> поверх дерева, где он уже наложен. Если после него наложены патчи
с бóльшим номером, которые трогают те же файлы (у Interlude 0021 дописывает блок Elpies в L2HubEvents.ini после 0019),
снять только свой с дерева нельзя: контекст уже другой. Здесь — снять с копии дерева поздние патчи (сверху вниз) и свой,
а после сборки нового патча наложить поздние обратно на результат: так --write не теряет их правки.
"""
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def touched(patch: Path) -> set[str]:
    return {m.decode() for m in re.findall(rb"(?m)^diff --git a/(\S+) b/", patch.read_bytes())}


def later(cid: str, name: str, files: list[str]) -> list[Path]:
    """Патчи хроники после name (по имени, как их накладывает сборка), которые трогают те же файлы."""
    want = set(files)
    return [p for p in sorted((ROOT / "patches" / cid).glob("*.patch")) if p.name > name and touched(p) & want]


def _git_apply(cwd: Path, patch: Path, reverse: bool = False) -> subprocess.CompletedProcess:
    args = ["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn"] + (["-R"] if reverse else []) + [str(patch)]
    return subprocess.run(args, cwd=cwd, capture_output=True)


def peel(cid: str, src: Path, name: str, files: list[str]) -> tuple[dict[str, bytes | None], list[Path]]:
    """Копия дерева, с которой сняты поздние патчи (сверху вниз) и сам name: {путь: содержимое или None — файла нет}
    для файлов генератора и всех файлов поздних патчей (они нужны restack), и сами поздние патчи."""
    upper = later(cid, name, files)
    own = ROOT / "patches" / cid / name
    paths = set(files) | {rel for p in [own, *upper] for rel in touched(p)}
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for rel in paths:
            if (src / rel).is_file():
                (t / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src / rel, t / rel)
        subprocess.run(["git", "init", "-q"], cwd=t, check=True)
        for patch in [*reversed(upper), own]:
            r = _git_apply(t, patch, reverse=True)
            if r.returncode != 0:
                raise SystemExit(f"{patch.relative_to(ROOT)} не снимается с копии дерева: {r.stderr.decode().strip()}")
        return {rel: ((t / rel).read_bytes() if (t / rel).is_file() else None) for rel in paths}, upper


def restack(new: dict[str, bytes], upper: list[Path], peeled: dict[str, bytes | None]) -> dict[str, bytes]:
    """Поздние патчи — обратно поверх нового содержимого файлов генератора (остальные их файлы — из peeled).
    Возвращает итог для файлов генератора. Если поздний патч не ложится — стоп: его надо пересобрать."""
    if not upper:
        return new
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for rel, data in {**peeled, **new}.items():
            if data is None:
                continue
            (t / rel).parent.mkdir(parents=True, exist_ok=True)
            (t / rel).write_bytes(data)
        subprocess.run(["git", "init", "-q"], cwd=t, check=True)
        for patch in upper:
            r = _git_apply(t, patch)
            if r.returncode != 0:
                raise SystemExit(f"{patch.relative_to(ROOT)} не ложится поверх нового патча — пересобрать и его: {r.stderr.decode().strip()}")
        return {rel: (t / rel).read_bytes() for rel in new}
