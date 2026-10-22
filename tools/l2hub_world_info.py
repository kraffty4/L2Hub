"""Патч «Мир сейчас» для хроники Mobius: patches/<id>/0022-l2hub-world-info.patch.

Скрипт data/scripts/custom/L2HubWorldInfo (эталон — tools/l2hub_world_info/L2HubWorldInfo.java): команда .world — окно
«Мир сейчас» (живые суперчемпионы рядом по уровню, сезонные события, ближайшие PvP-ивенты и нашествие эльпи) и
log/L2HubWorld.json для лаунчера раз в минуту. Нужны патчи суперчемпиона 0020 (SuperChampion) и календаря 0016
(L2HubEvents.ini); PvP-ивенты 0019 и Elpies — по желанию (нет ключей — в окне «выключены»). В дереве хроники:
  - новый dist/game/data/scripts/custom/L2HubWorldInfo/L2HubWorldInfo.java (переводы строк — как у скриптов хроники);
  - кнопка «Мир» (bypass voice .world) после кнопки «Рейд» (.raid) в CommunityBoard/Custom/navigation.html доски Alt+B,
    если такая доска есть; нет — патч без неё, кнопку добавить руками в свою доску.
Классы ядра в скрипте — как у Interlude (MapRegionData, LongTimeEvent, ScriptManager, VoicedCommandHandler); у хроники,
где они называются иначе, сборка скажет, что поправить в её копии.
Переводы строк каждого файла сохраняются. Патч проверяется наложением на копию исходного дерева байт в байт.

    python -X utf8 tools/l2hub_world_info.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_world_info.py <id> --write   — собрать патч в patches/<id>/ и наложить изменения на дерево
Наложен прежний patches/<id>/0022 — он снимается с копии дерева, и патч собирается заново (обновление после правки эталона);
поздние патчи на те же файлы снимаются тоже и ложатся обратно (tools/patch_layers.py).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import patch_layers

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent / "l2hub_world_info"
NAME = "0022-l2hub-world-info.patch"
SCRIPT = "dist/game/data/scripts/custom/L2HubWorldInfo/L2HubWorldInfo.java"
NAV = "dist/game/data/html/CommunityBoard/Custom/navigation.html"
EOL_FROM = "dist/game/data/scripts/handlers/MasterHandler.java"  # переводы строк скриптов хроники
RAID = b'action="bypass voice .raid"'
BUTTON = ('<button value="Мир" action="bypass voice .world" width=114 height=30 back="L2UI_CH3.Button.bigbutton2_down" '
          'fore="L2UI_CH3.Button.bigbutton2"><br>').encode()


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def eol(data: bytes) -> bytes:
    return b"\r\n" if data.count(b"\r\n") > data.count(b"\n") // 2 else b"\n"


def originals(cid: str, src: Path) -> tuple[dict[str, bytes | None], list[Path], dict[str, bytes | None]]:
    """Файлы до патча: из дерева или, если прежний patches/<id>/0022 уже наложен, — копия дерева со снятыми им и поздними
    патчами на те же файлы (patch_layers.peel). Возвращает (файлы до патча, поздние патчи, снятое состояние для restack)."""
    files = [SCRIPT, NAV]
    if not (src / SCRIPT).is_file():
        return {SCRIPT: None, NAV: (src / NAV).read_bytes() if (src / NAV).is_file() else None}, [], {}
    if not (ROOT / "patches" / cid / NAME).is_file():
        sys.exit(f"{SCRIPT} уже есть в дереве, а patches/{cid}/{NAME} нет — нечем снять")
    peeled, upper = patch_layers.peel(cid, src, NAME, [f for f in files if (src / f).is_file()])
    print(f"{cid}: прежний {NAME} снят с копии дерева" + (f" (и поздние: {', '.join(p.name for p in upper)})" if upper else "")
          + ", патч собирается заново")
    return {SCRIPT: peeled.get(SCRIPT), NAV: peeled.get(NAV)}, upper, peeled


def changes(base: dict[str, bytes | None], src: Path) -> dict[str, tuple[bytes | None, bytes]]:
    """{путь: (было или None, стало)} по файлам до патча."""
    if base[SCRIPT] is not None:
        sys.exit(f"{SCRIPT} есть и без патча — чужой файл?")
    for need in ("java/org/l2jmobius/gameserver/entity/actor/SuperChampion.java", "dist/game/config/Custom/L2HubEvents.ini"):
        if not (src / need).is_file():
            sys.exit(f"нет {need} — сначала патчи суперчемпиона 0020 и календаря 0016")
    nl = eol((src / EOL_FROM).read_bytes())
    script = (HERE / "L2HubWorldInfo.java").read_bytes().replace(b"\r\n", b"\n").replace(b"\n", nl)
    out: dict[str, tuple[bytes | None, bytes]] = {SCRIPT: (None, script)}
    nav = base[NAV]
    if nav is None or nav.count(RAID) != 1:
        print(f"{NAV}: {'нет файла' if nav is None else 'нет кнопки «Рейд» (.raid)'} — кнопку «Мир» (bypass voice .world) добавить в свою доску руками")
        return out
    if b"voice .world" in nav:
        sys.exit(f"{NAV}: кнопка .world уже есть")
    line_end = nav.index(b"\n", nav.index(RAID)) + 1
    out[NAV] = (nav, nav[:line_end] + BUTTON + eol(nav) + nav[line_end:])
    return out


def build_patch(plan: dict) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        (t / "a").mkdir()  # только новый файл (доски нет) — «было» пустое, но каталог для diff нужен
        (t / "b").mkdir()
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                if data is not None:
                    (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                    (t / side / rel).write_bytes(data)
        r = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=",
                            "--dst-prefix=", "a", "b"], cwd=t, capture_output=True)
        patch = r.stdout
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
    final = patch_layers.restack({rel: new for rel, (_, new) in plan.items()}, upper, peeled)  # поздние патчи — обратно
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт"
          + (f"; поздние патчи ложатся поверх: {', '.join(p.name for p in upper)}" if upper else ""))
    if write:
        (ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
