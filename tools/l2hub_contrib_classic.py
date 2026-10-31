"""Те же правки вклада, что в tools/l2hub_contrib.py, для Classic и Essence: код Mobius там другой, поэтому и патчи свои.

  observer — наблюдатель Олимпиады и обзорных кристаллов, смысл тот же; обычное наблюдение здесь входит через setObserving(true).
  war      — объявление войны игроком: условия из PhantomClanWars.eligible. У Classic в Mobius зашит 5-й уровень, он остаётся нижней границей
             (PhantomCompat.warLevelFloor), ключ HunterClanWarLevel его не опускает; у Essence зашит 3-й, как на High Five.
  board    — кланы на доске Alt+B (PhantomClanBoard): те же правки, что у High Five и Epilogue.
  sets     — свои наборы бафов (PhantomBufferSets): правки High Five, питомец здесь — summon(player), getSummon в этих хрониках нет.
  sale     — продажа лута с доски (PhantomLootSell): список покупки здесь ProductList, торговец Merchant, окно у Classic и Essence разное (SALE_WINDOW).

  python -X utf8 tools/l2hub_contrib_classic.py <classic_tales|ess_crow> <патч>           проверка: патч собирается и ложится байт в байт
  python -X utf8 tools/l2hub_contrib_classic.py <classic_tales|ess_crow> <патч> --write   записать patches/<id>/<файл патча>; дерево не трогает, патч наложит сборка
"""
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import l2hub_contrib as contrib  # noqa: E402
import patch_layers  # noqa: E402

ROOT = patch_layers.ROOT
OBSERVER_FILE, OBSERVER_EDITS = contrib.PATCHES["observer"]
WAR_FILE = contrib.PATCHES["war"][0]
PLEDGE_WAR = contrib.PLEDGE_WAR
WAR_LEVEL = {"classic_tales": 5, "ess_crow": 3}


def observer(cid: str) -> list:
    """Правки H5, кроме двух: обычное наблюдение здесь входит через setObserving, а над isInvul в Creature стоит @Override — новый метод идёт выше аннотации."""
    keep = [edit for edit in OBSERVER_EDITS if not (isinstance(edit[1], str) and ("AbnormalType.HIDE" in edit[1] or edit[0] == contrib.CREATURE))]
    return keep + [
        (contrib.PLAYER, "AbnormalType.HIDE);\n\t\t\n\t\tsetObserving(true);\n", "AbnormalType.HIDE);\n\t\t\n\t\trememberObserverPrior();\n\t\tsetObserving(true);\n", 1),
        (contrib.CREATURE, "\t@Override\n\tpublic boolean isInvul()\n",
         "\t/** L2Hub: только выставленная через setInvul неуязвимость, без телепорта и эффектов. */\n\tpublic boolean isInvulSet()\n\t{\n\t\treturn _isInvul;\n\t}\n\t\n\t@Override\n\tpublic boolean isInvul()\n", 1),
    ]


def war_condition(prefix: str, variable: str, level: int) -> tuple:
    anchor = re.compile(prefix + rf"\(\({variable}\.getLevel\(\) < {level}\) \|\| \({variable}\.getMembersCount\(\) < PlayerConfig\.ALT_CLAN_MEMBERS_FOR_WAR\)\)\n" + contrib.WAR_MESSAGE)
    return (PLEDGE_WAR, anchor, prefix.replace("\\", "").replace("(", "") + rf"(!PhantomClanWars.eligible({variable}))\n\1player.sendMessage(PhantomClanWars.requirements());", 1)


def war(cid: str) -> list:
    level = WAR_LEVEL[cid]
    return [
        (PLEDGE_WAR, "import org.l2jmobius.gameserver.config.PlayerConfig;\n", "", 1),
        (PLEDGE_WAR, "import org.l2jmobius.gameserver.entity.clan.enums.ClanWarState;\n",
         "import org.l2jmobius.gameserver.entity.clan.enums.ClanWarState;\nimport org.l2jmobius.gameserver.managers.phantom.PhantomClanWars;\n", 1),
        war_condition(r"if ", "clanDeclaringWar", level),
        war_condition(r"else if ", "clanDeclaredWar", level),
    ]


def board(cid: str) -> list:
    """Врезка та же, что у High Five и Epilogue: файлы LF, якоря совпали."""
    return contrib.PATCHES["board"][1]


def sets(cid: str) -> list:
    return [(rel, anchor, repl.replace("player.getSummon()", "summon(player)"), times) for rel, anchor, repl, times in contrib.PATCHES["sets"][1]]


def sale_packet(rel: str) -> list:
    """То же, что contrib.sale_packet: другие только типы торговца и списка. Условия PhantomLootSell.canUse проверяет сам пакет: открытое окно не разрешение."""
    target = "\t\tfinal WorldObject target = player.getTarget();\n\t\tMerchant merchant = null;\n"
    return [
        (rel, "import org.l2jmobius.gameserver.managers.PunishmentManager;\n",
         f"import org.l2jmobius.gameserver.managers.PunishmentManager;\nimport {contrib.PHANTOM_PKG}.PhantomLootSell;\n", 1),
        (rel, target, contrib.SALE_CHECK + target, 1),
        (rel, "!player.isGM() && (_listId != CUSTOM_CB_SELL_LIST)", "!player.isGM() && !boardSale && (_listId != CUSTOM_CB_SELL_LIST)", 2),
        (rel, "final ProductList buyList = BuyListData.getInstance().getBuyList(_listId);",
         "final ProductList buyList = boardSale ? new ProductList(_listId) : BuyListData.getInstance().getBuyList(_listId);", 1),
    ]


# Окно как у Merchant этой хроники: Classic — BuyList и список продажи, Essence — три вида ExBuySellList.
SALE_WINDOW = {
    "classic_tales": """						final ProductList list = new ProductList(PhantomLootSell.LIST_ID);
						player.sendPacket(new BuyList(list, player, 0));
						player.sendPacket(new ExBuySellList(player, false));
""",
    "ess_crow": """						final ProductList list = new ProductList(PhantomLootSell.LIST_ID);
						player.sendPacket(new ExBuySellList(list, player, 0));
						player.sendPacket(new ExBuySellList(player, false));
						player.sendPacket(new ExBuySellList());
""",
}
SALE_IMPORTS = {
    "classic_tales": "import org.l2jmobius.gameserver.network.serverpackets.BuyList;\nimport org.l2jmobius.gameserver.network.serverpackets.ExBuySellList;\n",
    "ess_crow": "import org.l2jmobius.gameserver.network.serverpackets.ExBuySellList;\n",
}


def sale(cid: str) -> list:
    return (sale_packet(contrib.SELL_ITEM) + sale_packet(contrib.REFUND_ITEM)
            + contrib.sale_board(SALE_WINDOW[cid], "import org.l2jmobius.gameserver.mechanics.buylist.ProductList;\n", SALE_IMPORTS[cid]))


PATCHES = {
    "observer": (OBSERVER_FILE, observer),
    "war": (WAR_FILE, war),
    "board": (contrib.PATCHES["board"][0], board),
    "sets": (contrib.PATCHES["sets"][0], sets),
    "sale": (contrib.PATCHES["sale"][0], sale),
}


def tree(cid: str) -> Path:
    """Дерево, равное стопке патчей: из исходников репозитория или из папки данных L2HUB_HOME (сборка в ней свежая)."""
    home = Path(os.environ.get("L2HUB_HOME", ROOT))
    found = [p for p in (home / "servers" / cid).glob("L2J_Mobius_*") if p.is_dir()]
    if len(found) != 1:
        sys.exit(f"{cid}: ожидалась одна папка L2J_Mobius_* в {home}, найдено {len(found)}")
    return found[0]


def main() -> None:
    if len(sys.argv) < 3 or sys.argv[1] not in WAR_LEVEL or sys.argv[2] not in PATCHES:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    name, make = PATCHES[sys.argv[2]]
    edits = make(cid)
    files = sorted({rel for rel, *_ in edits})
    src = tree(cid)
    try:
        peeled, upper = patch_layers.peel(cid, src, name, files)
    except SystemExit:  # дерево ещё без этого патча: снимать нечего
        peeled, upper = {rel: (src / rel).read_bytes() for rel in files}, []
    if upper:
        sys.exit(f"поздние патчи трогают те же файлы, снять их здесь не предусмотрено: {', '.join(p.name for p in upper)}")
    base = {rel: peeled[rel] for rel in files}
    if sys.argv[2] == "sale" and b"PhantomBufferSets" not in base[contrib.BOARD]:  # дерево ещё без наборов: их импорт стоит рядом, контекст продажи считается поверх
        base = contrib.changes(base, sets(cid))
    new = contrib.changes(base, edits)
    patch, bad = contrib.build_patch(base, new)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт")
    if write:
        (ROOT / "patches" / cid / name).write_bytes(patch)
        print(f"записано: patches/{cid}/{name}")


if __name__ == "__main__":
    main()
