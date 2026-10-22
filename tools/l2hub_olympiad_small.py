"""Патч «Олимпиада малого мира» для хроники Mobius: patches/<id>/0025-olympiad-small-world.patch.

Олимпиада у Mobius рассчитана на сервер с сотнями игроков: матчи начинаются при 5 (по классу) и 9 (без класса)
записавшихся, герои — раз в месяц. В одиночной игре с охотниками это не набрать. В dist/game/config/Olympiad.ini:
  OlympiadClassedParticipants 2, OlympiadNonClassedParticipants 2 — матч, как только записались двое;
  OlympiadUseCustomPeriodSettings True, OlympiadPeriod WEEK, OlympiadPeriodMultiplier 1 — герои каждую неделю
  (очки — раз в полнедели); у хроник без OlympiadUseCustomPeriodSettings (Classic, Essence) — только OlympiadPeriod;
  OlympiadCompetitionDays 1,2,3,4,5,6,7 — матчи каждый день (у Classic и Essence по умолчанию только пятница и суббота).
Над каждой изменённой строкой — комментарий, что было у Mobius. Чего у хроники нет — пропускается.
Охотники на олимпиаде (дворянство, запись, бой) — ядро фантомов (epilogue-h5), здесь только настройки.
Новое значение в развёрнутом сервере — со следующего периода олимпиады (конец текущего записан в базе); сразу — //endolympiad.

    python -X utf8 tools/l2hub_olympiad_small.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_olympiad_small.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0025 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import patch_layers

ROOT = Path(__file__).resolve().parents[1]
NAME = "0025-olympiad-small-world.patch"
INI = "dist/game/config/Olympiad.ini"
MARK = "L2Hub (малый мир)"
VALUES = {  # ключ → (значение, почему)
    "OlympiadClassedParticipants": ("2", "матч по классу, как только записались двое"),
    "OlympiadNonClassedParticipants": ("2", "матч без класса, как только записались двое"),
    "OlympiadUseCustomPeriodSettings": ("True", "свой период — ниже"),
    "OlympiadPeriod": ("WEEK", "герои каждую неделю, очки раз в полнедели"),
    "OlympiadPeriodMultiplier": ("1", "одна неделя"),
    "OlympiadCompetitionDays": ("1,2,3,4,5,6,7", "матчи каждый день, а не только в пятницу и субботу"),
}


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def edit(text: bytes, values: dict[str, tuple[str, str]] = VALUES) -> tuple[bytes, list[str]]:
    """Значения малого мира в ini; комментарий над строкой — что было у Mobius (генератор Семи печатей берёт ту же правку)."""
    nl = b"\r\n" if b"\r\n" in text else b"\n"
    done = []
    for key, (value, why) in values.items():
        rx = re.compile(rb"(?m)^" + key.encode() + rb"[ \t]*=[ \t]*([^\r\n]*)(\r?)$")
        m = rx.search(text)
        if not m:
            continue
        was = m.group(1).decode("utf-8").strip()
        if was == value:
            continue
        line = f"# {MARK}: было {was} — {why}.".encode() + nl + f"{key} = {value}".encode()
        text = text[:m.start()] + line + text[m.end() - len(m.group(2)):]
        done.append(f"{key} {was}→{value}")
    return text, done


def originals(cid: str, src: Path, ini: str = INI, name: str = NAME, mark: str = MARK) -> tuple[dict[str, bytes], list[Path], dict[str, bytes | None]]:
    current = (src / ini).read_bytes()
    if mark.encode() not in current:
        return {ini: current}, [], {}
    if not (ROOT / "patches" / cid / name).is_file():
        sys.exit(f"в {ini} уже есть «{mark}», а patches/{cid}/{name} нет — нечем снять")
    peeled, upper = patch_layers.peel(cid, src, name, [ini])
    print(f"{cid}: прежний {name} снят с копии дерева, патч собирается заново")
    return {ini: peeled[ini]}, upper, peeled


def build_patch(plan: dict) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                (t / side / rel).write_bytes(data)
        patch = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=",
                                "--dst-prefix=", "a", "b"], cwd=t, capture_output=True).stdout
        chk = t / "chk"
        shutil.copytree(t / "a", chk)
        subprocess.run(["git", "init", "-q"], cwd=chk, check=True)
        (t / "p.patch").write_bytes(patch)
        subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn", str(t / "p.patch")], cwd=chk, check=True)
        bad = [rel for rel, (_, new) in plan.items() if (chk / rel).read_bytes() != new]
    return patch, bad


def run(doc: str, ini: str, name: str, values, mark: str = MARK) -> None:
    """Командная строка генератора над одним файлом хроники: <id> [--write] (олимпиада, Семь печатей, порядок входа на стадион).
    values — ключи ini {ключ: (значение, почему)} или своя правка: функция bytes → (bytes, [что сделано]); mark — метка правки в файле."""
    if len(sys.argv) < 2:
        sys.exit(doc)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = tree(cid)
    if not (src / ini).is_file():
        sys.exit(f"нет {ini}")
    base, upper, peeled = originals(cid, src, ini, name, mark)
    new, done = values(base[ini]) if callable(values) else edit(base[ini], values)
    if not done:
        sys.exit(f"{cid}: менять нечего — ключей нет или значения уже такие")
    plan = {ini: (base[ini], new)}
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack({ini: new}, upper, peeled)
    print(f"{cid}: {', '.join(done)}; наложение на копию — байт в байт")
    if write:
        (ROOT / "patches" / cid / name).write_bytes(patch)
        (src / ini).write_bytes(final[ini])
        print(f"записано: patches/{cid}/{name} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    run(__doc__, INI, NAME, VALUES)
