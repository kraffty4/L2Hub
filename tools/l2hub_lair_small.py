"""Патч «напарник в логово эпика» для хроники Mobius: patches/<id>/0036-lair-companion-small-world.patch.

Логово эпика типа BossZone (Валакас; у Interlude и Epilogue ещё Фринтеза, у Epilogue и H5 — Белет) пускает только тех, кого
внёс скрипт (allowPlayerEntry: Heart of Volcano у Валакаса — одного игрока, на 30 с), остальных телепортирует в город.
Напарник идёт за хозяином в пати и догоняет его телепортом (PhantomCompanionManager, дальше PhantomCatchUpRange), а логово
его выкидывает, и он прыгает к хозяину снова — по кругу, раз в секунду (проверено прогоном 2026-10-28, docs/INTERLUDE.md,
«Напарник в логове эпика»). Здесь — под выключателем «Эпики малого мира» (SmallWorldEpics, патч 0028; поручение
оркестратора 2026-10-28):
  entity/zone/type/BossZone.java — с SmallWorldEpics фантом (PhantomManager.isPhantom) входит, если в его пати есть живой
    игрок, которого логово пустило (он в списке зоны) или GM в логове; так же — слуга такого фантома. Фантома без такой
    пати логово выкидывает, как у Mobius. Новых ключей нет.

    python -X utf8 tools/l2hub_lair_small.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_lair_small.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0036 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import sys

import l2hub_olympiad_small as small
import patch_layers

NAME = "0036-lair-companion-small-world.patch"
MARK = "L2Hub (малый мир)"
CONFIG = "java/org/l2jmobius/gameserver/config/GrandBossConfig.java"
ZONE = "java/org/l2jmobius/gameserver/entity/zone/type/BossZone.java"
HELPER = "isPhantomOfAllowedParty"
IMPORTS = {  # после какой строки — какая
    "import org.l2jmobius.gameserver.GameServer;": "import org.l2jmobius.gameserver.config.GrandBossConfig;",
    "import org.l2jmobius.gameserver.managers.ZoneManager;": "import org.l2jmobius.gameserver.managers.phantom.PhantomManager;",
}
SUMMON_CHECK = "if (getSettings().getPlayersAllowed().contains(player.getObjectId()) || player.isGM())"
METHOD = [
    "\t/**",
    f"\t * {MARK}, tools/l2hub_lair_small.py: с «Эпиками малого мира» ({{@code SmallWorldEpics}}) фантом — напарник или охотник — входит в логово",
    "\t * вместе с живым игроком из своей пати, которого логово пустило (NPC или скрипт эпика внёс его в список), или с GM в логове. Иначе напарника,",
    "\t * догоняющего хозяина телепортом, логово выкидывает в город, и он снова прыгает к хозяину — по кругу. Фантома без такой пати логово",
    "\t * не пускает, как и раньше.",
    "\t * @param player кто входит",
    "\t * @return пустить",
    "\t */",
    f"\tprivate boolean {HELPER}(Player player)",
    "\t{",
    "\t\tif (!GrandBossConfig.SMALL_WORLD_EPICS || !player.isInParty() || !PhantomManager.getInstance().isPhantom(player))",
    "\t\t{",
    "\t\t\treturn false;",
    "\t\t}",
    "\t\t",
    "\t\tfor (Player member : player.getParty().getMembers())",
    "\t\t{",
    "\t\t\tif ((member != player) && !PhantomManager.getInstance().isPhantom(member) && (getSettings().getPlayersAllowed().contains(member.getObjectId()) || (member.isGM() && isCharacterInZone(member))))",
    "\t\t\t{",
    "\t\t\t\treturn true;",
    "\t\t\t}",
    "\t\t}",
    "\t\t",
    "\t\treturn false;",
    "\t}",
    "\t",
]


def lines_of(data: bytes) -> tuple[str, list[str]]:
    nl = "\r\n" if b"\r\n" in data else "\n"
    return nl, data.decode("utf-8").split(nl)


def block(indent: str, comment: str) -> list[str]:
    return [f"{indent}// {MARK}: {comment}",
            f"{indent}if ({HELPER}(player))",
            f"{indent}{{",
            f"{indent}\treturn;",
            f"{indent}}}",
            indent]


def edit_zone(data: bytes) -> bytes | None:
    """onEnter: после «GM — пускать» — фантом пати пущенного игрока; в ветке слуги — то же до выброса хозяина; метод — перед onExit."""
    nl, lines = lines_of(data)
    strip = [l.strip() for l in lines]
    try:
        enter = strip.index("protected void onEnter(Creature creature)")
        gm = next(i for i in range(enter, len(lines)) if strip[i] == "if (player.isGM())")
        summon = next(i for i in range(gm, len(lines)) if strip[i] == SUMMON_CHECK)
        leave = strip.index("protected void onExit(Creature creature)")
    except (ValueError, StopIteration):
        return None
    if strip[gm + 1:gm + 5] != ["{", "return;", "}", ""] or strip[leave - 1] != "@Override" or not (gm < summon < leave):
        return None
    if any(strip.count(key) != 1 for key in IMPORTS) or strip.count(SUMMON_CHECK) != 1:
        return None
    out = []
    for i, line in enumerate(lines):
        if i == summon:
            out += block(line[:len(line) - len(line.lstrip())], "слуга такого фантома — тоже (tools/l2hub_lair_small.py).")
        if i == leave - 1:
            out += METHOD
        out.append(line)
        if i == gm + 4:
            out += block(lines[gm][:len(lines[gm]) - len(lines[gm].lstrip())], "фантом из пати живого игрока, которого логово пустило, входит с ним (напарник не прыгает по кругу).")
        if strip[i] in IMPORTS:
            out.append(IMPORTS[strip[i]])
    return nl.join(out).encode("utf-8")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = small.tree(cid)
    for rel in (CONFIG, ZONE):
        if not (src / rel).is_file():
            sys.exit(f"{cid}: нет {rel} — логов BossZone у хроники нет, менять нечего")
    if b"SMALL_WORLD_EPICS" not in (src / CONFIG).read_bytes():
        sys.exit(f"{cid}: в {CONFIG} нет SMALL_WORLD_EPICS — сначала «Эпики малого мира» (0028, tools/l2hub_epics_small.py)")
    current = (src / ZONE).read_bytes()
    upper, peeled = [], {}
    if HELPER.encode() in current:
        if not (small.ROOT / "patches" / cid / NAME).is_file():
            sys.exit(f"в дереве уже есть {HELPER}, а patches/{cid}/{NAME} нет — нечем снять")
        peeled, upper = patch_layers.peel(cid, src, NAME, [ZONE])
        current = peeled[ZONE]
        print(f"{cid}: прежний {NAME} снят с копии дерева, патч собирается заново")
    zone = edit_zone(current)
    if zone is None:
        sys.exit(f"{cid}: в {ZONE} нет ожидаемого входа (onEnter с GM, ветка слуги, onExit) — другой код")
    new = {ZONE: zone}
    patch, bad = small.build_patch({ZONE: (current, zone)})
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled)
    print(f"{cid}: фантом из пати пущенного игрока входит в логово BossZone; наложение на копию — байт в байт")
    if write:
        (small.ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
