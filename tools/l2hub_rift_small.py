"""Патч «Разлом малого мира» для хроники Mobius: patches/<id>/0035-rift-small-world.patch.

Разлом измерений (Dimensional Rift) у Mobius пускает пати от RiftMinPartySize (у Interlude — от двоих), но за вход
DimensionalRiftManager.start проверяет фрагменты измерений (Dimensional Fragment, 7079) у каждого в пати и списывает их с
каждого. Напарнику и охотнику лут не выпадает (PhantomPartyRules.takesLoot), фрагментов у них нет — одиночку с
напарником Rift Post не пускает («нет фрагментов»). Разбор — docs/INTERLUDE.md, «Разлом малого мира». Здесь — выключатель
SmallWorldRift (лаунчер, «Настройки сервера», «Мир»; режим «Хардкор» выключает; поручение оркестратора 2026-10-26):
  game/config/General.ini — раздел L2Hub: SmallWorldRift True;
  GeneralConfig.java — читает раздел;
  managers/DimensionalRiftManager.java — при SmallWorldRift фрагменты проверяются и списываются только у живых: фантомы
    (PhantomManager.isPhantom) в обоих циклах по пати пропускаются. Остальное как у Mobius: пати, комната ожидания, цена.

    python -X utf8 tools/l2hub_rift_small.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_rift_small.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0035 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import re
import sys

import l2hub_olympiad_small as small
import patch_layers

NAME = "0035-rift-small-world.patch"
MARK = "L2Hub (малый мир)"
INI = "dist/game/config/General.ini"
CONFIG = "java/org/l2jmobius/gameserver/config/GeneralConfig.java"
MANAGER = "java/org/l2jmobius/gameserver/managers/DimensionalRiftManager.java"
SECTION = [
    "# ---------------------------------------------------------------------------",
    f"# {MARK}: Разлом измерений малого мира (tools/l2hub_rift_small.py)",
    "# ---------------------------------------------------------------------------",
    "# Выключатель «Разлом малого мира» в лаунчере («Настройки сервера», «Мир»; режим «Хардкор» выключает). True — фрагменты",
    "# измерений (Dimensional Fragment) за вход в Разлом проверяются и списываются только у живых: напарник и охотник идут без",
    "# них. False — как у Mobius: фрагменты нужны каждому в пати.",
    "SmallWorldRift = True",
]
FIELDS = [
    f"	// {MARK}: выключатель «Разлом малого мира» (tools/l2hub_rift_small.py).",
    "	public static boolean SMALL_WORLD_RIFT;",
]
LOAD = [
    f"		// {MARK}: Разлом измерений — фрагменты только у живых.",
    "		SMALL_WORLD_RIFT = config.getBoolean(\"SmallWorldRift\", false);",
]
LOOP = "for (Player p : party.getMembers())"
FRAGMENT = "i = p.getInventory().getItemByItemId(DIMENSIONAL_FRAGMENT_ITEM_ID);"
IMPORTS = {  # после какой строки — какая
    "import org.l2jmobius.gameserver.entity.spawns.Spawn;": "import org.l2jmobius.gameserver.managers.phantom.PhantomManager;",
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


def edit_manager(data: bytes) -> bytes | None:
    """В проверке и в списании фрагментов (два цикла по пати в start) фантомы пропускаются."""
    nl, lines = lines_of(data)
    loops = [i for i, l in enumerate(lines)
             if l.strip() == "{" and lines[i - 1].strip() == LOOP and i + 1 < len(lines) and lines[i + 1].strip() == FRAGMENT]
    if len(loops) != 2 or any(sum(l.strip() == key for l in lines) != 1 for key in IMPORTS):
        return None
    out = []
    for i, line in enumerate(lines):
        out.append(line)
        if i in loops:
            indent = lines[i + 1][:len(lines[i + 1]) - len(lines[i + 1].lstrip())]
            what = "проверка" if i == loops[0] else "списание"
            out += [f"{indent}// {MARK}: фрагменты ({what}) — только у живых; напарник и охотник идут без них.",
                    f"{indent}if (GeneralConfig.SMALL_WORLD_RIFT && PhantomManager.getInstance().isPhantom(p))",
                    f"{indent}{{",
                    f"{indent}\tcontinue;",
                    f"{indent}}}",
                    indent]
        if line.strip() in IMPORTS:
            out.append(IMPORTS[line.strip()])
    return nl.join(out).encode("utf-8")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = small.tree(cid)
    files = [INI, CONFIG, MANAGER]
    for rel in files:
        if not (src / rel).is_file():
            sys.exit(f"{cid}: нет {rel} — Разлома у хроники нет, менять нечего")
    current = {rel: (src / rel).read_bytes() for rel in files}
    upper, peeled = [], {}
    if any(MARK.encode() in data and b"SmallWorldRift" in data for data in current.values()):
        if not (small.ROOT / "patches" / cid / NAME).is_file():
            sys.exit(f"в дереве уже есть «SmallWorldRift», а patches/{cid}/{NAME} нет — нечем снять")
        peeled, upper = patch_layers.peel(cid, src, NAME, files)
        current = {rel: peeled[rel] for rel in files}
        print(f"{cid}: прежний {NAME} снят с копии дерева, патч собирается заново")
    manager = edit_manager(current[MANAGER])
    if manager is None:
        sys.exit(f"{cid}: в {MANAGER} нет ожидаемого входа (два цикла по пати с фрагментами 7079) — другой код")
    new = {INI: edit_ini(current[INI]), CONFIG: edit_config(current[CONFIG]), MANAGER: manager}
    plan = {rel: (current[rel], data) for rel, data in new.items()}
    patch, bad = small.build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled)
    print(f"{cid}: фрагменты Разлома — только у живых; файлов {len(new)}; наложение на копию — байт в байт")
    if write:
        (small.ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
