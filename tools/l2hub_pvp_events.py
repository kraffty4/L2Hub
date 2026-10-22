"""Патч «PvP-ивенты по расписанию» для хроники Mobius: patches/<id>/0019-l2hub-pvp-events.patch.

Хронико-независимо (Interlude, Gracia Epilogue, High Five): эталон скрипта расписания — tools/l2hub_pvp_events/
L2HubPvpEvents.java, ключи для config/Custom/L2HubEvents.ini — tools/l2hub_pvp_events/ini_block.txt. В дереве хроники:
  - новый data/scripts/custom/L2HubPvpEvents/L2HubPvpEvents.java (переводы строк — как у скриптов ивентов хроники:
    CRLF у CT, LF у Classic и Essence);
  - в custom/events TvT, Deathmatch и CtF константа MINIMUM_PARTICIPANT_LEVEL = N берёт порог через
    L2HubPvpEvents.minLevel(N) — у хроник N разный, поэтому патч считается по дереву, а не один на всех; проверка
    участников перед ареной (isOnlineInt() != 1) — через L2HubPvpEvents.offline: фантомов без клиента не выкидывать;
    в TvT и CtF onEnterZone/onExitZone — только свои участники (общие зоны Колизея, NPE у TvT во время CtF);
  - блок ключей дописывается в конец L2HubEvents.ini (его создаёт патч календаря 0016).
Переводы строк каждого файла сохраняются. Патч проверяется наложением на копию исходного дерева байт в байт.

    python -X utf8 tools/l2hub_pvp_events.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_pvp_events.py <id> --write   — собрать патч в patches/<id>/ и наложить изменения на дерево
Наложен прежний patches/<id>/0019 — он снимается с копии дерева, и патч собирается заново (обновление после правки эталона);
поздние патчи на те же файлы (у Interlude — 0021 в L2HubEvents.ini) снимаются тоже и ложатся обратно (tools/patch_layers.py).
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import patch_layers

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent / "l2hub_pvp_events"
NAME = "0019-l2hub-pvp-events.patch"
SCRIPT = "dist/game/data/scripts/custom/L2HubPvpEvents/L2HubPvpEvents.java"
INI = "dist/game/config/Custom/L2HubEvents.ini"
EVENTS = ["dist/game/data/scripts/custom/events/TeamVsTeam/TvT.java",
          "dist/game/data/scripts/custom/events/Deathmatch/Deathmatch.java",
          "dist/game/data/scripts/custom/events/CaptureTheFlag/CtF.java"]
LEVEL = re.compile(rb"private static final int MINIMUM_PARTICIPANT_LEVEL = (\d+);")
CALL = b"private static final int MINIMUM_PARTICIPANT_LEVEL = custom.L2HubPvpEvents.L2HubPvpEvents.minLevel(%s); // L2Hub: PvpMinLevel"
OFFLINE = b"if ((participant == null) || (participant.isOnlineInt() != 1))"
OFFLINE_CALL = "if (custom.L2HubPvpEvents.L2HubPvpEvents.offline(participant)) // L2Hub: фантомы без клиента — в игре".encode()
# TvT и CtF делят мирные зоны Колизея (базы команд), а их onEnterZone/onExitZone смотрят только на isOnEvent и команду:
# вход участника CtF в базу ловит и TvT — телепорт к PVP_WORLD == null даёт NPE (прогон CtF epilogue-h5 2026-10-22),
# а при идущем TvT он кидал бы участников CtF на свои точки. Только свои участники: PVP_WORLD есть и игрок в PLAYER_LIST.
ZONE_SIGNATURES = (b"public void onEnterZone(Creature creature, ZoneType zone)\n\t{\n\t\tif (creature.isPlayable())\n",
                   b"public void onExitZone(Creature creature, ZoneType zone)\n\t{\n\t\tif (creature.isPlayer())\n")
ZONE_TAIL = b"\t\t{\n\t\t\tfinal Player player = creature.asPlayer();\n\t\t\tif (player.isOnEvent())\n"
ZONE_GUARD = ("\t\t{\n\t\t\tfinal Player player = creature.asPlayer();\n\t\t\tif (player.isOnEvent() && (PVP_WORLD != null) && "
              "PLAYER_LIST.contains(player)) // L2Hub: зоны Колизея общие у TvT и CtF — только свои участники\n").encode()
ZONE_SCRIPTS = (EVENTS[0], EVENTS[2])  # TvT, CtF


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def crlf(text: bytes) -> bytes:
    return text.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")


def originals(cid: str, src: Path) -> tuple[dict[str, bytes | None], list[Path], dict[str, bytes | None]]:
    """Файлы до патча: из дерева или, если прежний patches/<id>/0019 уже наложен, — копия дерева со снятыми им и поздними
    патчами на те же файлы (patch_layers.peel). Возвращает (файлы до патча, поздние патчи, снятое состояние для restack)."""
    files = [SCRIPT, *EVENTS, INI]
    if not (src / SCRIPT).is_file():
        return {SCRIPT: None} | {rel: (src / rel).read_bytes() for rel in files[1:]}, [], {}
    if not (ROOT / "patches" / cid / NAME).is_file():
        sys.exit(f"{SCRIPT} уже есть в дереве, а patches/{cid}/{NAME} нет — нечем снять")
    peeled, upper = patch_layers.peel(cid, src, NAME, files)
    print(f"{cid}: прежний {NAME} снят с копии дерева" + (f" (и поздние: {', '.join(p.name for p in upper)})" if upper else "")
          + ", патч собирается заново")
    return {rel: peeled[rel] for rel in files}, upper, peeled


def changes(base: dict[str, bytes | None]) -> dict[str, tuple[bytes | None, bytes]]:
    """{путь: (было или None, стало)} по файлам до патча."""
    out: dict[str, tuple[bytes | None, bytes]] = {}
    if base[SCRIPT] is not None:
        sys.exit(f"{SCRIPT} есть и без патча — чужой файл?")
    # Переводы строк — как у скриптов ивентов хроники: у CT — CRLF, у Classic и Essence — LF.
    script = (HERE / "L2HubPvpEvents.java").read_bytes()
    out[SCRIPT] = (None, crlf(script) if b"\r\n" in base[EVENTS[0]] else script.replace(b"\r\n", b"\n"))
    for rel in EVENTS:
        old = base[rel]
        if len(LEVEL.findall(old)) != 1:
            sys.exit(f"{rel}: строка MINIMUM_PARTICIPANT_LEVEL = N не найдена ровно один раз")
        if old.count(OFFLINE) != 1:
            sys.exit(f"{rel}: проверка участников перед ареной (isOnlineInt() != 1) не найдена ровно один раз")
        new = LEVEL.sub(lambda m: CALL % m.group(1), old).replace(OFFLINE, OFFLINE_CALL)
        if rel in ZONE_SCRIPTS:
            nl = b"\r\n" if b"\r\n" in old else b"\n"
            for signature in ZONE_SIGNATURES:
                anchor = (signature + ZONE_TAIL).replace(b"\n", nl)
                if new.count(anchor) != 1:
                    sys.exit(f"{rel}: обработчик {signature.split(b'(')[0].decode()} не найден ровно один раз")
                new = new.replace(anchor, (signature + ZONE_GUARD).replace(b"\n", nl))
        out[rel] = (old, new)
    old = base[INI]
    if b"PvpEvents" in old:
        sys.exit(f"{INI}: ключи PvP уже есть")
    block = (HERE / "ini_block.txt").read_bytes()
    if b"\r\n" in old:
        block = crlf(block)
    out[INI] = (old, old.rstrip(b"\r\n") + (b"\r\n" if b"\r\n" in old else b"\n") + block)
    return out


def build_patch(cid: str, plan: dict) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
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
    plan = changes(base)
    patch, bad = build_patch(cid, plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack({rel: new for rel, (_, new) in plan.items()}, upper, peeled)  # поздние патчи — обратно
    levels = {Path(rel).stem: LEVEL.search(old).group(1).decode() for rel, (old, _) in plan.items() if rel in EVENTS}
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, пороги скриптов {levels}, наложение на копию — байт в байт"
          + (f"; поздние патчи ложатся поверх: {', '.join(p.name for p in upper)}" if upper else ""))
    if write:
        (ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
