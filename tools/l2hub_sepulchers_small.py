"""Патч «Четыре гробницы малого мира» для хроники Mobius: patches/<id>/0033-sepulchers-small-world.patch.

Четыре гробницы (Imperial Tomb, квест 620 Four Goblets, с 74-го уровня) у Mobius рассчитаны на сервер, где пати
собирается из живых: вход — лидеру пати не меньше чем из четверых (PARTY_MEMBER_COUNT в FourSepulchers.tryEnter), и у
каждого в пати должны быть начатый квест 620 и пропуск (Entrance Pass to the Sepulcher). Одиночке с напарником туда не
попасть: напарник и охотник — фантомы, квеста и пропуска у них нет. Разбор — docs/INTERLUDE.md, «Четыре гробницы малого
мира». Здесь — выключатель SmallWorldSepulchers (лаунчер, «Настройки сервера», «Мир»; режим «Хардкор» выключает):
  game/config/General.ini — раздел L2Hub: SmallWorldSepulchers True, SmallWorldSepulchersMinParty 2 (пати от скольких);
  GeneralConfig.java — читает раздел;
  ai/areas/ImperialTomb/FourSepulchers/FourSepulchers.java — при SmallWorldSepulchers пати от SmallWorldSepulchersMinParty,
    а квест 620, пропуск и вес проверяются только у живых: фантомы (PhantomManager.isPhantom) идут без них. Внутри всё
    как у Mobius: пропуск забирается у тех, у кого он есть, брошь и печати дают только тем, у кого начат квест.
Окно менеджера при слишком малой пати по-прежнему пишет «не меньше четырёх» (html Mobius не меняется).

    python -X utf8 tools/l2hub_sepulchers_small.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_sepulchers_small.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0033 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import re
import sys

import l2hub_olympiad_small as small
import patch_layers

NAME = "0033-sepulchers-small-world.patch"
MARK = "L2Hub (малый мир)"
INI = "dist/game/config/General.ini"
CONFIG = "java/org/l2jmobius/gameserver/config/GeneralConfig.java"
SCRIPT = "dist/game/data/scripts/ai/areas/ImperialTomb/FourSepulchers/FourSepulchers.java"
SECTION = [
    "# ---------------------------------------------------------------------------",
    f"# {MARK}: Четыре гробницы малого мира (tools/l2hub_sepulchers_small.py)",
    "# ---------------------------------------------------------------------------",
    "# Выключатель «Гробницы малого мира» в лаунчере («Настройки сервера», «Мир»; режим «Хардкор» выключает). True — в Четыре",
    "# гробницы пускают пати от SmallWorldSepulchersMinParty (у Mobius — от 4), а квест 620 и пропуск нужны только живым:",
    "# напарник и охотник идут без них. False — как у Mobius.",
    "SmallWorldSepulchers = True",
    "# Пати от скольких человек, считая напарников и охотников. Range 2-9",
    "SmallWorldSepulchersMinParty = 2",
]
FIELDS = [
    f"	// {MARK}: выключатель «Гробницы малого мира» (tools/l2hub_sepulchers_small.py).",
    "	public static boolean SMALL_WORLD_SEPULCHERS;",
    "	public static int SMALL_WORLD_SEPULCHERS_MIN_PARTY;",
]
LOAD = [
    f"		// {MARK}: Четыре гробницы — пати от SmallWorldSepulchersMinParty, квест и пропуск — только у живых.",
    "		SMALL_WORLD_SEPULCHERS = config.getBoolean(\"SmallWorldSepulchers\", false);",
    "		SMALL_WORLD_SEPULCHERS_MIN_PARTY = Math.max(2, Math.min(9, config.getInt(\"SmallWorldSepulchersMinParty\", 2)));",
]
SIZE_OLD = "if (!player.isInParty() || (party.getMemberCount() < PARTY_MEMBER_COUNT))"
SIZE_NEW = ("if (!player.isInParty() || (party.getMemberCount() < (GeneralConfig.SMALL_WORLD_SEPULCHERS ? "
            "GeneralConfig.SMALL_WORLD_SEPULCHERS_MIN_PARTY : PARTY_MEMBER_COUNT)))")
QUEST = "final QuestState qs = mem.getQuestState(Q00620_FourGoblets.class.getSimpleName());"
LOOP = "for (Player mem : party.getMembers())"
IMPORTS = {  # после какой строки — какая
    "import org.l2jmobius.gameserver.config.PlayerConfig;": "import org.l2jmobius.gameserver.config.GeneralConfig;",
    "import org.l2jmobius.gameserver.managers.ZoneManager;": "import org.l2jmobius.gameserver.managers.phantom.PhantomManager;",
}


def lines_of(data: bytes) -> tuple[str, list[str]]:
    nl = "\r\n" if b"\r\n" in data else "\n"
    return nl, data.decode("utf-8").split(nl)


def edit_ini(data: bytes) -> bytes:
    nl, lines = lines_of(data)
    if lines and lines[-1] == "":
        lines.pop()
    return nl.join(lines + [""] + SECTION + [""]).encode("utf-8")


def edit_config(data: bytes) -> bytes:
    nl, lines = lines_of(data)
    last_field = max(i for i, l in enumerate(lines) if re.match(r"^\tpublic static \w+(<[^>]*>)? \w+;$", l))
    load = next(i for i, l in enumerate(lines) if l.strip() == "public static void load()")
    end = next(i for i in range(load + 2, len(lines)) if lines[i] == "\t}")
    out = lines[:last_field + 1] + ["\t"] + FIELDS + lines[last_field + 1:end] + ["\t\t"] + LOAD + lines[end:]
    return nl.join(out).encode("utf-8")


def edit_script(data: bytes) -> bytes | None:
    """Пати от SmallWorldSepulchersMinParty; в проверке квеста, пропуска и веса фантомы пропускаются."""
    nl, lines = lines_of(data)
    sizes = [i for i, l in enumerate(lines) if l.strip() == SIZE_OLD]
    # Квест 620 проверяется и в награде за босса (onKill) — нужен тот, что сразу в цикле по пати при входе.
    quests = [i for i, l in enumerate(lines) if l.strip() == QUEST and lines[i - 2].strip() == LOOP and lines[i - 1].strip() == "{"]
    if len(sizes) != 1 or len(quests) != 1 or any(sum(l.strip() == key for l in lines) != 1 for key in IMPORTS):
        return None
    q = quests[0]
    out = []
    for i, line in enumerate(lines):
        indent = line[:len(line) - len(line.lstrip())]
        if i == sizes[0]:
            out.append(f"{indent}// {MARK}: с SmallWorldSepulchers — пати от SmallWorldSepulchersMinParty (с напарником), а не от четырёх.")
            out.append(indent + SIZE_NEW)
            continue
        if i == q:
            out += [f"{indent}// {MARK}: квест 620, пропуск и вес — только у живых; напарник и охотник идут без них.",
                    f"{indent}if (GeneralConfig.SMALL_WORLD_SEPULCHERS && PhantomManager.getInstance().isPhantom(mem))",
                    f"{indent}{{",
                    f"{indent}\tcontinue;",
                    f"{indent}}}",
                    indent]
        out.append(line)
        if line.strip() in IMPORTS:
            out.append(IMPORTS[line.strip()])
    return nl.join(out).encode("utf-8")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = small.tree(cid)
    files = [INI, CONFIG, SCRIPT]
    for rel in files:
        if not (src / rel).is_file():
            sys.exit(f"{cid}: нет {rel} — гробниц у хроники нет, менять нечего")
    current = {rel: (src / rel).read_bytes() for rel in files}
    upper, peeled = [], {}
    if any(MARK.encode() in data and b"SmallWorldSepulchers" in data for data in current.values()):
        if not (small.ROOT / "patches" / cid / NAME).is_file():
            sys.exit(f"в дереве уже есть «SmallWorldSepulchers», а patches/{cid}/{NAME} нет — нечем снять")
        peeled, upper = patch_layers.peel(cid, src, NAME, files)
        current = {rel: peeled[rel] for rel in files}
        print(f"{cid}: прежний {NAME} снят с копии дерева, патч собирается заново")
    script = edit_script(current[SCRIPT])
    if script is None:
        sys.exit(f"{cid}: в {SCRIPT} нет ожидаемого входа (пати от PARTY_MEMBER_COUNT, цикл с квестом 620) — другой скрипт")
    new = {INI: edit_ini(current[INI]), CONFIG: edit_config(current[CONFIG]), SCRIPT: script}
    plan = {rel: (current[rel], data) for rel, data in new.items()}
    patch, bad = small.build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled)
    print(f"{cid}: пати от SmallWorldSepulchersMinParty, квест и пропуск — только у живых; файлов {len(new)}; наложение на копию — байт в байт")
    if write:
        (small.ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
