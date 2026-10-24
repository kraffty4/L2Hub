"""Патч «Эпики малого мира» для хроники Mobius: patches/<id>/0028-epics-small-world.patch.

Эпики Mobius рассчитаны на сервер, где на Антараса идут командным каналом раз в 11 дней. В одиночной игре с охотниками
до них не дойти: интервалы респауна — от полутора до 11 дней, Антарас и Валакас после первого входа ждут 20–30 минут, к
Фринтезе (старая, Interlude и Epilogue) пускают только лидера командного канала из 4–5 пати, к Белету — канал от 36 человек.
Разбор — docs/INTERLUDE.md, «Эпики малого мира». Здесь — выключатель SmallWorldEpics (лаунчер, «Настройки сервера», «Мир»;
режим «Хардкор» выключает), официальные ключи хроники не меняются:
  game/config/GrandBoss.ini — раздел L2Hub: SmallWorldEpics True, SmallWorldEpicsIntervalPercent 25 (интервал и случайная
    добавка респауна — 25 % официальных), SmallWorldEpicsWaitTime 5 (ожидание Антараса и Валакаса, минут),
    SmallWorldEpicsMinPlayers 2 (порог командного канала к Белету);
  GrandBossConfig.java — читает раздел и при SmallWorldEpics пересчитывает поля *_SPAWN_INTERVAL, *_SPAWN_RANDOM,
    *_WAIT_TIME и BELETH_MIN_PLAYERS, какие есть у хроники;
  ai/bosses/Frintezza/Frintezza.java (старая Фринтеза с командным каналом 4–5 пати, если есть) — при SmallWorldEpics
    вход лидеру пати без канала (заходит его пати; канал — как раньше, но без требования 4–5 пати).
Силу эпиков патч не трогает: при SmallWorldEpics её режет ядро фантомов (PhantomEpics, docs/PHANTOMS.md, раздел 4.43) —
Королева, Ядро, Орфен и Закен: урон по ним ×2,5 (как 40 % HP), свита урезана.

    python -X utf8 tools/l2hub_epics_small.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_epics_small.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0028 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import re
import sys

import l2hub_olympiad_small as small
import patch_layers

NAME = "0028-epics-small-world.patch"
MARK = "L2Hub (малый мир)"
INI = "dist/game/config/GrandBoss.ini"
CONFIG = "java/org/l2jmobius/gameserver/config/GrandBossConfig.java"
FRINTEZZA = "dist/game/data/scripts/ai/bosses/Frintezza/Frintezza.java"
SECTION = [
    "# ---------------------------------------------------------------------------",
    f"# {MARK}: эпики малого мира (tools/l2hub_epics_small.py)",
    "# ---------------------------------------------------------------------------",
    "# Выключатель «Эпики малого мира» в лаунчере («Настройки сервера», «Мир»; режим «Хардкор» выключает). True — интервал",
    "# и случайная добавка респауна эпиков выше — в процентах SmallWorldEpicsIntervalPercent, ожидание Антараса и Валакаса",
    "# после первого входа — не дольше SmallWorldEpicsWaitTime минут, к старой Фринтезе — лидеру пати без командного канала,",
    "# к Белету — канал от SmallWorldEpicsMinPlayers человек. Новый интервал — со следующей смерти босса.",
    "SmallWorldEpics = True",
    "# Интервал респауна в процентах от официального. Range 1-100",
    "SmallWorldEpicsIntervalPercent = 25",
    "# Ожидание Антараса и Валакаса после первого входа, минут.",
    "SmallWorldEpicsWaitTime = 5",
    "# Порог командного канала для входа к Белету.",
    "SmallWorldEpicsMinPlayers = 2",
]
FIELDS = [
    f"	// {MARK}: выключатель «Эпики малого мира» (tools/l2hub_epics_small.py).",
    "	public static boolean SMALL_WORLD_EPICS;",
    "	public static int SMALL_WORLD_EPICS_INTERVAL_PERCENT;",
    "	public static int SMALL_WORLD_EPICS_WAIT_TIME;",
    "	public static int SMALL_WORLD_EPICS_MIN_PLAYERS;",
]
CC_OLD = ("if ((!player.isInParty() || !player.getParty().isLeader(player)) || (player.getParty().getCommandChannel() == null) || "
          "(player.getParty().getCommandChannel().getLeader() != player))")
CC_NEW = ("if ((!player.isInParty() || !player.getParty().isLeader(player)) || ((player.getParty().getCommandChannel() == null) ? "
          "!GrandBossConfig.SMALL_WORLD_EPICS : (player.getParty().getCommandChannel().getLeader() != player)))")
SIZE_OLD = ("else if ((player.getParty().getCommandChannel().getParties().size() < 4) || "
            "(player.getParty().getCommandChannel().getParties().size() > 5))")
SIZE_NEW = ("else if (!GrandBossConfig.SMALL_WORLD_EPICS && ((player.getParty().getCommandChannel().getParties().size() < 4) || "
            "(player.getParty().getCommandChannel().getParties().size() > 5)))")
LOOP_OLD = "for (Party party : cc.getParties())"
LOOP_NEW = "for (Party party : (cc != null) ? cc.getParties() : List.of(player.getParty()))"


def lines_of(data: bytes) -> tuple[str, list[str]]:
    nl = "\r\n" if b"\r\n" in data else "\n"
    return nl, data.decode("utf-8").split(nl)


def edit_ini(data: bytes) -> bytes:
    nl, lines = lines_of(data)
    if lines and lines[-1] == "":
        lines.pop()
    return nl.join(lines + [""] + SECTION + [""]).encode("utf-8")


def edit_config(data: bytes) -> tuple[bytes, list[str]]:
    nl, lines = lines_of(data)
    text = nl.join(lines)
    fields = re.findall(r"^\t\t(\w+_SPAWN_(?:INTERVAL|RANDOM)) = config\.getInt\(", text, re.M)
    waits = re.findall(r"^\t\t(\w+_WAIT_TIME) = config\.getInt\(", text, re.M)
    beleth = "BELETH_MIN_PLAYERS" in re.findall(r"^\t\t(BELETH_MIN_PLAYERS) = config\.getInt\(", text, re.M)
    if not fields:
        sys.exit(f"в {CONFIG} нет полей *_SPAWN_INTERVAL — другой загрузчик")
    last_field = max(i for i, l in enumerate(lines) if re.match(r"^\tpublic static \w+ \w+;$", l))
    load = next(i for i, l in enumerate(lines) if l.strip() == "public static void load()")
    end = next(i for i in range(load + 2, len(lines)) if lines[i] == "\t}")
    body = [
        f"\t\t// {MARK}: с выключателем — интервал и случайная добавка респауна эпиков выше в процентах от официальных,",
        "\t\t// ожидание Антараса и Валакаса не дольше SmallWorldEpicsWaitTime минут, порог канала к Белету — SmallWorldEpicsMinPlayers.",
        "\t\tSMALL_WORLD_EPICS = config.getBoolean(\"SmallWorldEpics\", false);",
        "\t\tSMALL_WORLD_EPICS_INTERVAL_PERCENT = Math.max(1, Math.min(100, config.getInt(\"SmallWorldEpicsIntervalPercent\", 25)));",
        "\t\tSMALL_WORLD_EPICS_WAIT_TIME = Math.max(1, config.getInt(\"SmallWorldEpicsWaitTime\", 5));",
        "\t\tSMALL_WORLD_EPICS_MIN_PLAYERS = Math.max(1, config.getInt(\"SmallWorldEpicsMinPlayers\", 2));",
        "\t\tif (SMALL_WORLD_EPICS)",
        "\t\t{",
        *[f"\t\t\t{f} = smallWorld({f});" for f in fields],
        *[f"\t\t\t{w} = Math.min({w}, SMALL_WORLD_EPICS_WAIT_TIME);" for w in waits],
        *(["\t\t\tBELETH_MIN_PLAYERS = Math.min(BELETH_MIN_PLAYERS, SMALL_WORLD_EPICS_MIN_PLAYERS);"] if beleth else []),
        "\t\t}",
    ]
    helper = [
        "\t",
        "\t/**",
        f"\t * {MARK}: часы респауна в процентах SmallWorldEpicsIntervalPercent, не меньше часа.",
        "\t */",
        "\tprivate static int smallWorld(int hours)",
        "\t{",
        "\t\treturn Math.max(1, Math.round((hours * SMALL_WORLD_EPICS_INTERVAL_PERCENT) / 100f));",
        "\t}",
    ]
    out = lines[:last_field + 1] + ["\t"] + FIELDS + lines[last_field + 1:end] + body + [lines[end]] + helper + lines[end + 1:]
    done = [f"{len(fields)} интервалов", f"ожидание {len(waits)}"] + (["Белет"] if beleth else [])
    return nl.join(out).encode("utf-8"), done


def edit_frintezza(data: bytes) -> bytes | None:
    text = data.decode("utf-8")
    if not all(text.count(s) == 1 for s in (CC_OLD, SIZE_OLD, LOOP_OLD)):
        return None
    nl = "\r\n" if "\r\n" in text else "\n"
    m = re.search(r"(?m)^([ \t]*)" + re.escape(CC_OLD), text)
    note = f"{m.group(1)}// {MARK}: с SmallWorldEpics — лидеру пати и без командного канала, без требования 4–5 пати (заходит его пати).{nl}"
    text = text[:m.start()] + note + text[m.start():]
    for old, new in ((CC_OLD, CC_NEW), (SIZE_OLD, SIZE_NEW), (LOOP_OLD, LOOP_NEW)):
        text = text.replace(old, new)
    return text.encode("utf-8")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = small.tree(cid)
    for rel in (INI, CONFIG):
        if not (src / rel).is_file():
            sys.exit(f"{cid}: нет {rel}")
    files = [INI, CONFIG] + ([FRINTEZZA] if (src / FRINTEZZA).is_file() else [])
    current = {rel: (src / rel).read_bytes() for rel in files}
    upper, peeled = [], {}
    if any(MARK.encode() in data for data in current.values()):
        if not (small.ROOT / "patches" / cid / NAME).is_file():
            sys.exit(f"в дереве уже есть «{MARK}», а patches/{cid}/{NAME} нет — нечем снять")
        peeled, upper = patch_layers.peel(cid, src, NAME, files)
        current = {rel: peeled[rel] for rel in files}
        print(f"{cid}: прежний {NAME} снят с копии дерева, патч собирается заново")
    new = {INI: edit_ini(current[INI])}
    new[CONFIG], done = edit_config(current[CONFIG])
    if FRINTEZZA in files:
        fr = edit_frintezza(current[FRINTEZZA])
        if fr is not None:
            new[FRINTEZZA] = fr
            done.append("Фринтеза без канала")
    plan = {rel: (current[rel], data) for rel, data in new.items()}
    patch, bad = small.build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled)
    print(f"{cid}: {', '.join(done)}; файлов {len(new)}; наложение на копию — байт в байт")
    if write:
        (small.ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
