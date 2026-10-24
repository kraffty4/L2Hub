"""Патч «Летопись мира» для хроники Mobius: patches/<id>/0023-l2hub-chronicle.patch.

Скрипт data/scripts/custom/L2HubChronicle (эталон — tools/l2hub_chronicle/L2HubChronicle.java): лента заметных событий в
log/L2HubChronicle.json для лаунчера — убиты РБ, суперчемпион, эпик (кем), эпик встал, итоги TvT/Deathmatch/CtF, начало и
конец сезонного события, нашествие эльпи, профессия и уровни 20/40/52/61/76 у охотников и игроков (кроме секретного Танатоса), итоги дуэлей с охотниками
(PhantomNews модуля фантомов).
Нужен патч суперчемпиона 0020 (SuperChampion); ивенты и эльпи — по желанию (нет скрипта — нет записей). Один новый файл, переводы строк — как у
скриптов хроники. Классы ядра — как у Interlude (Containers, ConsumerEventListener, OnAttackableKill, OnPlayerLevelChanged,
OnPlayerProfessionChange, GrandBossManager, LongTimeEvent, Party); где у хроники иначе — сборка скажет, что поправить в её копии.

    python -X utf8 tools/l2hub_chronicle.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_chronicle.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0023 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import patch_layers

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent / "l2hub_chronicle"
NAME = "0023-l2hub-chronicle.patch"
SCRIPT = "dist/game/data/scripts/custom/L2HubChronicle/L2HubChronicle.java"
EOL_FROM = "dist/game/data/scripts/handlers/MasterHandler.java"  # переводы строк скриптов хроники


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def eol(data: bytes) -> bytes:
    return b"\r\n" if data.count(b"\r\n") > data.count(b"\n") // 2 else b"\n"


def originals(cid: str, src: Path) -> tuple[dict[str, bytes | None], list[Path], dict[str, bytes | None]]:
    if not (src / SCRIPT).is_file():
        return {SCRIPT: None}, [], {}
    if not (ROOT / "patches" / cid / NAME).is_file():
        sys.exit(f"{SCRIPT} уже есть в дереве, а patches/{cid}/{NAME} нет — нечем снять")
    peeled, upper = patch_layers.peel(cid, src, NAME, [SCRIPT])
    print(f"{cid}: прежний {NAME} снят с копии дерева" + (f" (и поздние: {', '.join(p.name for p in upper)})" if upper else "")
          + ", патч собирается заново")
    return {SCRIPT: peeled.get(SCRIPT)}, upper, peeled


def changes(base: dict[str, bytes | None], src: Path) -> dict[str, tuple[bytes | None, bytes]]:
    if base[SCRIPT] is not None:
        sys.exit(f"{SCRIPT} есть и без патча — чужой файл?")
    if not (src / "java/org/l2jmobius/gameserver/entity/actor/SuperChampion.java").is_file():
        sys.exit("нет SuperChampion.java — сначала патч суперчемпиона 0020")
    nl = eol((src / EOL_FROM).read_bytes())
    script = (HERE / "L2HubChronicle.java").read_bytes().replace(b"\r\n", b"\n").replace(b"\n", nl)
    return {SCRIPT: (None, script)}


def build_patch(plan: dict) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        (t / "a").mkdir()
        (t / "b").mkdir()
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                if data is not None:
                    (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                    (t / side / rel).write_bytes(data)
        patch = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=",
                                "--dst-prefix=", "a", "b"], cwd=t, capture_output=True).stdout
        for rel, (old, _) in plan.items():
            if old is None:  # новый файл: git пишет «b/… b/…», в шапке нужна «a/… b/…»
                patch = patch.replace(f"diff --git b/{rel} b/{rel}".encode(), f"diff --git a/{rel} b/{rel}".encode())
        chk = t / "chk"
        shutil.copytree(t / "a", chk)
        subprocess.run(["git", "init", "-q"], cwd=chk, check=True)
        (t / "p.patch").write_bytes(patch)
        subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn", str(t / "p.patch")], cwd=chk, check=True)
        bad = [rel for rel, (_, new) in plan.items() if (chk / rel).read_bytes() != new]
    return patch, bad


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = tree(cid)
    base, upper, peeled = originals(cid, src)
    plan = changes(base, src)
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack({rel: new for rel, (_, new) in plan.items()}, upper, peeled)
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт")
    if write:
        (ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
