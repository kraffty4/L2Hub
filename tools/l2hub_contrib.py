"""Патчи по вкладу пользователя: правки Mobius, что не входят в модуль фантомов. Код свой, java-агент вклада не берём.

  observer — наблюдатель Олимпиады и обзорных кристаллов: Mobius при входе ставит невидимость и неуязвимость, а при выходе снимает их
             только у не-GM: GM, вошедший без них, оставался невидимым и неуязвимым. Теперь состояние запоминается при первом входе (смена
             арены его не переписывает), игрок выходит обычным, как и раньше, GM — каким вошёл.
  war      — объявление войны игроком: уровень из HunterClanWarLevel вместо прошитого, у клана охотников число членов не важно (PhantomClanWars.eligible).
  sets     — наборы бафов на доске: кнопка «Мои наборы» и команды scheme_* в _bbshubbuff (PhantomBufferSets). Номер 0101, как board.
  sale     — продажа лута с доски (PhantomLootSell): своя страница, окно торговца с пустым списком, в пакетах продажи и возврата свой номер списка и проверка
             условий. Номер 0102. У H5 ExBuySellList без списка, у Epilogue со списком. У Interlude окно SellList, а номер списка у любой продажи 0, поэтому доска
             отличается от торговца по цели рядом; RequestRefundItem там нет. Classic и Essence — свой код окна, сюда не подходят.
  board    — кланы на доске Alt+B: _bbshubwars и _bbshubclan (PhantomClanBoard) и две кнопки в навигации. Номер 0100, поверх языкового слоя 0099: своих
             строк в Java нет, кнопки сразу в обеих версиях окна (html — английская, lang/ru — русская). Строка навигации легла бы внутрь сплошного
             куска 0099, и его пришлось бы пересобирать; при пересборке 0099 patch_layers снимает и возвращает этот патч сам.

  python -X utf8 tools/l2hub_contrib.py <id> <патч>           проверка: патч собирается и ложится байт в байт
  python -X utf8 tools/l2hub_contrib.py <id> <патч> --write   записать patches/<id>/<файл патча> и дерево
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import patch_layers  # noqa: E402

ROOT = patch_layers.ROOT
PLAYER = "java/org/l2jmobius/gameserver/entity/actor/Player.java"
CREATURE = "java/org/l2jmobius/gameserver/entity/actor/Creature.java"
PLEDGE_WAR = "java/org/l2jmobius/gameserver/network/clientpackets/RequestStartPledgeWar.java"
BOARD = "dist/game/data/scripts/handlers/bypass/communityboard/L2HubBoard.java"
NAV_EN = "dist/game/data/html/CommunityBoard/Custom/navigation.html"
NAV_RU = "dist/game/data/lang/ru/data/html/CommunityBoard/Custom/navigation.html"
SELL_ITEM = "java/org/l2jmobius/gameserver/network/clientpackets/RequestSellItem.java"
REFUND_ITEM = "java/org/l2jmobius/gameserver/network/clientpackets/RequestRefundItem.java"
PHANTOM_PKG = "org.l2jmobius.gameserver.managers.phantom"

METHODS = """	/** L2Hub: состояние до наблюдения; смена арены его не переписывает. */
	private void rememberObserverPrior()
	{
		if (!_observerMode)
		{
			_observerPriorInvisible = isInvisible();
			_observerPriorInvul = isInvulSet();
		}
	}

	/** L2Hub: игрок выходит обычным, GM — каким вошёл: раньше он оставался невидимым и неуязвимым, даже если входил без этого. */
	private void restoreObserverPrior()
	{
		setInvisible(isGM() && _observerPriorInvisible);
		setInvul(isGM() && _observerPriorInvul);
	}

""".replace("\n\n", "\n\t\n")

WAR_MESSAGE = r"(\t\t\{\n\t\t\t)player\.sendPacket\(SystemMessageId\.A_CLAN_WAR_CAN_[A-Z0-9_]+\);"



def nav_row_interlude(label: str, bypass: str) -> str:
    return (f'<button value="{label}" action="bypass {bypass}" width=114 height=30 '
            'back="L2UI_CH3.Button.bigbutton2_down" fore="L2UI_CH3.Button.bigbutton2"><br>\n')


def nav_row(label: str, bypass: str) -> str:
    return (f'<tr>\n\t<td><button value="{label}" action="bypass {bypass}" width=200 height=30 '
            'back="L2UI_CT1.OlympiadWnd_DF_Fight1None_Down" fore="L2UI_CT1.OlympiadWnd_DF_Fight1None"></td>\n</tr>\n')


SALE_CHECK = """		// L2Hub: продажа лута с доски (PhantomLootSell). Открытие окна сделку не разрешает: условия проверяются здесь.
		final boolean boardSale = PhantomLootSell.isList(_listId);
		if (boardSale && !PhantomLootSell.canUse(player))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}

"""


def sale_packet(rel: str) -> list:
    """Правки пакета сделки: импорт, проверка, свой список вместо BuyListData."""
    target = "\t\tfinal WorldObject target = player.getTarget();\n\t\tCreature merchant = null;\n"
    return [
        (rel, "import org.l2jmobius.gameserver.managers.PunishmentManager;\n",
         f"import org.l2jmobius.gameserver.managers.PunishmentManager;\nimport {PHANTOM_PKG}.PhantomLootSell;\n", 1),
        (rel, target, SALE_CHECK + target, 1),
        (rel, "!player.isGM() && (_listId != CUSTOM_CB_SELL_LIST)", "!player.isGM() && !boardSale && (_listId != CUSTOM_CB_SELL_LIST)", 2),
        (rel, "final BuyListHolder buyList = BuyListData.getInstance().getBuyList(_listId);",
         "final BuyListHolder buyList = boardSale ? new BuyListHolder(_listId) : BuyListData.getInstance().getBuyList(_listId);", 1),
    ]


SALE_CHECK_INTERLUDE = """		// L2Hub: продажа лута с доски (PhantomLootSell). Номер списка 0 у любой продажи: настоящий торговец — цель рядом, иначе это доска, и условия проверяются здесь.
		if (!player.isGM() && (_listId == CUSTOM_CB_SELL_LIST) && !((target instanceof Merchant) && player.isInsideRadius3D(target, INTERACTION_DISTANCE)) && !PhantomLootSell.canUse(player))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
"""

SALE_WINDOW = """						final BuyListHolder list = new BuyListHolder(PhantomLootSell.LIST_ID);
						player.sendPacket(new BuyList(list, player.getAdena(), 0));
						player.sendPacket(new ExBuySellList(%s));
"""


def sale_packet_interlude() -> list:
    target = "\t\tfinal WorldObject target = player.getTarget();\n\t\tCreature merchant = null;\n"
    return [
        (SELL_ITEM, "import org.l2jmobius.gameserver.managers.PunishmentManager;\n",
         f"import org.l2jmobius.gameserver.managers.PunishmentManager;\nimport {PHANTOM_PKG}.PhantomLootSell;\n", 1),
        (SELL_ITEM, target, target + SALE_CHECK_INTERLUDE, 1),
    ]


def sale_board(window: str, before_skill: str, after_skill: str) -> list:
    """Правки доски: страница и кнопка. window — как у хроники открывается продажа, остальное — её импорты."""
    return [
        (BOARD, "import org.l2jmobius.gameserver.cache.HtmCache;\n", "import org.l2jmobius.commons.threads.ThreadPool;\nimport org.l2jmobius.gameserver.cache.HtmCache;\n", 1),
        (BOARD, f"import {PHANTOM_PKG}.PhantomUi;\n", f"import {PHANTOM_PKG}.PhantomLootSell;\nimport {PHANTOM_PKG}.PhantomUi;\n", 1),
        (BOARD, "import org.l2jmobius.gameserver.mechanics.skill.Skill;\n",
         before_skill + "import org.l2jmobius.gameserver.mechanics.skill.Skill;\n" + after_skill, 1),
        (BOARD, "\t * _bbshubshop [страница] | ms &lt;мультиселл&gt; &lt;страница&gt;\n", "\t * _bbshubshop [страница] | ms &lt;мультиселл&gt; &lt;страница&gt; | sell\n", 1),
        (BOARD, "\tprivate static String shop(Player player, String[] args)\n\t{\n", """	private static String shop(Player player, String[] args)
	{
		if ((args.length > 1) && args[1].equalsIgnoreCase("sell"))
		{
			if (canUse(player) && PhantomLootSell.canUse(player))
			{
				ThreadPool.schedule(() ->
				{
					if (PhantomLootSell.canUse(player))
					{
""" + window + """					}
				}, 100);
			}
			return page(player, PhantomLootSell.title(), PhantomLootSell.content(SHOP));
		}
""", 1),
        (BOARD, '\t\treturn page(player, tr("GM-шоп"), content);\n', '\t\tcontent = button(PhantomLootSell.label(), SHOP + " sell", 170) + "<br>" + content;\n\t\treturn page(player, tr("GM-шоп"), content);\n', 1),
    ]


BOARD_BRANCHES = """		else if (args[0].equalsIgnoreCase(HUNTERS))
		{
			html = hunters(player, args);
		}
"""

# патч: (файл патча, правки). Правка: (файл, якорь — строка или re.Pattern, замена, сколько раз якорь встречается).
PATCHES = {
    "observer": ("0038-observer-prior-state.patch", [
        (PLAYER, "\tprivate boolean _observerMode = false;\n",
         "\tprivate boolean _observerMode = false;\n\tprivate boolean _observerPriorInvisible;\n\tprivate boolean _observerPriorInvul;\n", 1),
        (PLAYER, "AbnormalType.HIDE);\n\t\t_observerMode = true;\n", "AbnormalType.HIDE);\n\t\trememberObserverPrior();\n\t\t_observerMode = true;\n", 1),
        (PLAYER, "\t\t_observerMode = true;\n\t\tsetTarget(null);\n\t\tsetInvul(true);\n",
         "\t\trememberObserverPrior();\n\t\t_observerMode = true;\n\t\tsetTarget(null);\n\t\tsetInvul(true);\n", 1),
        (PLAYER, "\t\tif (!isGM())\n\t\t{\n\t\t\tsetInvisible(false);\n\t\t\tsetInvul(false);\n\t\t}\n", "\t\trestoreObserverPrior();\n", 2),
        (PLAYER, "\tpublic void setLastLocation()\n", METHODS + "\tpublic void setLastLocation()\n", 1),
        (CREATURE, "\tpublic boolean isInvul()\n",
         "\t/** L2Hub: только выставленная через setInvul неуязвимость, без телепорта и эффектов. */\n\tpublic boolean isInvulSet()\n\t{\n\t\treturn _isInvul;\n\t}\n\t\n\tpublic boolean isInvul()\n", 1),
    ]),
    "war": ("0039-clan-war-requirements.patch", [
        (PLEDGE_WAR, "import org.l2jmobius.gameserver.config.PlayerConfig;\n", "", 1),
        (PLEDGE_WAR, "import org.l2jmobius.gameserver.entity.clan.ClanAccess;\n",
         "import org.l2jmobius.gameserver.entity.clan.ClanAccess;\nimport org.l2jmobius.gameserver.managers.phantom.PhantomClanWars;\n", 1),
        (PLEDGE_WAR, re.compile(r"if \(\(playerClan\.getLevel\(\) < 3\) \|\| \(playerClan\.getMembersCount\(\) < PlayerConfig\.ALT_CLAN_MEMBERS_FOR_WAR\)\)\n" + WAR_MESSAGE),
         r"if (!PhantomClanWars.eligible(playerClan))\n\1player.sendMessage(PhantomClanWars.requirements());", 1),
        (PLEDGE_WAR, re.compile(r"else if \(\(clan\.getLevel\(\) < 3\) \|\| \(clan\.getMembersCount\(\) < PlayerConfig\.ALT_CLAN_MEMBERS_FOR_WAR\)\)\n" + WAR_MESSAGE),
         r"else if (!PhantomClanWars.eligible(clan))\n\1player.sendMessage(PhantomClanWars.requirements());", 1),
    ]),
    "sale": ("0102-loot-sale.patch", sale_packet(SELL_ITEM) + sale_packet(REFUND_ITEM)),
    "sets": ("0101-buffer-sets.patch", [
        (BOARD, f"import {PHANTOM_PKG}.PhantomHunterUi;\n", f"import {PHANTOM_PKG}.PhantomBufferSets;\nimport {PHANTOM_PKG}.PhantomHunterUi;\n", 1),
        (BOARD, "\t * _bbshubbuff [категория] | set fighter|mage [pet] | one &lt;id&gt; &lt;категория&gt; [pet] | heal &lt;категория&gt; | cancel &lt;категория&gt;\n",
         "\t * _bbshubbuff [категория] | set fighter|mage [pet] | one &lt;id&gt; &lt;категория&gt; [pet] | heal &lt;категория&gt; | cancel &lt;категория&gt; | schemes, scheme_* — свои наборы ({@link PhantomBufferSets})\n", 1),
        (BOARD, """		final String action = args.length > 1 ? args[1].toLowerCase() : "";
		switch (action)
""", """		final String action = args.length > 1 ? args[1].toLowerCase() : "";
		if (action.equals("schemes") || action.startsWith("scheme_"))
		{
			return page(player, PhantomBufferSets.title(), PhantomBufferSets.handle(player, args, !action.equals("scheme_cast") || canUse(player), player.getSummon()));
		}
		switch (action)
""", 1),
        (BOARD, '		final StringBuilder sb = new StringBuilder(12288);\n		sb.append("<table width=520><tr>");\n',
         '		final StringBuilder sb = new StringBuilder(12288);\n		sb.append(button(PhantomBufferSets.title(), BUFF + " schemes", 150)).append("<br>");\n		sb.append("<table width=520><tr>");\n', 1),
    ]),
    "board": ("0100-clan-board.patch", [
        (BOARD, f"import {PHANTOM_PKG}.PhantomHunterUi;\n", f"import {PHANTOM_PKG}.PhantomClanBoard;\nimport {PHANTOM_PKG}.PhantomHunterUi;\n", 1),
        (BOARD, re.compile(r"( \* <li>_bbshubhunters [^\n]*\n)"),
         r"\1 * <li>_bbshubwars [all|hunters|players] [страница] … — кланы и объявление войны, _bbshubclan … — набор в клан ({@link PhantomClanBoard})</li>\n", 1),
        (BOARD, '\tprivate static final String HUNTERS = "_bbshubhunters";\n',
         '\tprivate static final String HUNTERS = "_bbshubhunters";\n\tprivate static final String CLAN_WARS = "_bbshubwars";\n\tprivate static final String CLAN_RECRUIT = "_bbshubclan";\n', 1),
        (BOARD, "\t\tBUDDY,\n\t\tHUNTERS\n\t};", "\t\tBUDDY,\n\t\tHUNTERS,\n\t\tCLAN_WARS,\n\t\tCLAN_RECRUIT\n\t};", 1),
        (BOARD, BOARD_BRANCHES, BOARD_BRANCHES + """		else if (args[0].equalsIgnoreCase(CLAN_WARS))
		{
			html = page(player, PhantomClanBoard.warsTitle(), canUse(player) ? PhantomClanBoard.getInstance().wars(player, args) : PhantomClanBoard.busy());
		}
		else if (args[0].equalsIgnoreCase(CLAN_RECRUIT))
		{
			html = page(player, PhantomClanBoard.recruitTitle(), canUse(player) ? PhantomClanBoard.getInstance().recruit(player, args) : PhantomClanBoard.busy());
		}
""", 1),
        (NAV_EN, '<tr>\n\t<td><button value="Drop search"', nav_row("Clan wars", "_bbshubwars") + nav_row("Clan recruiting", "_bbshubclan") + '<tr>\n\t<td><button value="Drop search"', 1),
        (NAV_RU, '<tr>\n\t<td><button value="Поиск дропа"', nav_row("Войны кланов", "_bbshubwars") + nav_row("Набор в клан", "_bbshubclan") + '<tr>\n\t<td><button value="Поиск дропа"', 1),
    ]),
}


def tree(cid: str) -> Path:
    found = [p for p in (ROOT / "servers" / cid).glob("L2J_Mobius_*") if p.is_dir()]
    if len(found) != 1:
        sys.exit(f"{cid}: ожидалась одна папка L2J_Mobius_*, найдено {len(found)}")
    return found[0]


def changes(base: dict[str, bytes], edits: list) -> dict[str, bytes]:
    new = dict(base)
    missing = []
    for rel, anchor, repl, times in edits:
        crlf = b"\r\n" in new[rel]
        if crlf and new[rel].count(b"\n") != new[rel].count(b"\r\n"):
            missing.append(f"{Path(rel).name}: переводы строк вперемешку, правка переписала бы их")
            continue
        text = new[rel].decode("utf-8").replace("\r\n", "\n") if crlf else new[rel].decode("utf-8")
        found = len(anchor.findall(text)) if isinstance(anchor, re.Pattern) else text.count(anchor)
        if found != times:
            shown = anchor.pattern if isinstance(anchor, re.Pattern) else anchor.strip().splitlines()[0]
            missing.append(f"{Path(rel).name}: якорь «{shown[:80]}» найден {found} раз вместо {times}")
            continue
        text = anchor.sub(repl, text) if isinstance(anchor, re.Pattern) else text.replace(anchor, repl)
        new[rel] = (text.replace("\n", "\r\n") if crlf else text).encode("utf-8")
    if missing:
        sys.exit("врезки не легли (у этой хроники код другой — перенести руками):\n  " + "\n  ".join(missing))
    return new


def build_patch(base: dict[str, bytes], new: dict[str, bytes]) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for side, files in (("a", base), ("b", new)):
            for rel, data in files.items():
                (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                (t / side / rel).write_bytes(data)
        patch = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=", "--dst-prefix=", "a", "b"],
                               cwd=t, capture_output=True).stdout
        chk = t / "chk"
        shutil.copytree(t / "a", chk)
        subprocess.run(["git", "init", "-q"], cwd=chk, check=True)
        (t / "p.patch").write_bytes(patch)
        subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn", str(t / "p.patch")], cwd=chk, check=True)
        return patch, [rel for rel, data in new.items() if (chk / rel).read_bytes() != data]


def main() -> None:
    if len(sys.argv) < 3 or sys.argv[2] not in PATCHES:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    name, edits = PATCHES[sys.argv[2]]
    if sys.argv[2] == "sale":
        if cid == "interlude":
            edits = sale_packet_interlude() + sale_board("\t\t\t\t\t\tplayer.sendPacket(new SellList(player));\n", "", "import org.l2jmobius.gameserver.network.serverpackets.SellList;\n")
        else:
            edits = edits + sale_board(SALE_WINDOW % {"ct26_highfive": "player, false", "epilogue": "player, list, false"}.get(cid, "?"),
                                       "import org.l2jmobius.gameserver.mechanics.buylist.BuyListHolder;\n",
                                       "import org.l2jmobius.gameserver.network.serverpackets.BuyList;\nimport org.l2jmobius.gameserver.network.serverpackets.ExBuySellList;\n")
    if sys.argv[2] == "sets" and cid == "interlude":
        # Страница баффера у Interlude уже: та же врезка, другая ширина таблицы.
        edits = [(rel, anchor.replace("width=520", "width=480"), repl.replace("width=520", "width=480"), times) for rel, anchor, repl, times in edits]
    if sys.argv[2] == "board" and cid == "interlude":
        # Меню Interlude — столбец кнопок без таблицы, текстур L2UI_CT1 в его клиенте нет.
        edits = [e for e in edits if e[0] not in (NAV_EN, NAV_RU)] + [
            (NAV_EN, '<button value="Drop search"', nav_row_interlude("Clan wars", "_bbshubwars") + nav_row_interlude("Clan recruiting", "_bbshubclan") + '<button value="Drop search"', 1),
            (NAV_RU, '<button value="Поиск дропа"', nav_row_interlude("Войны кланов", "_bbshubwars") + nav_row_interlude("Набор в клан", "_bbshubclan") + '<button value="Поиск дропа"', 1),
        ]
    files = sorted({rel for rel, *_ in edits})
    src = tree(cid)
    peeled, upper = patch_layers.peel(cid, src, name, files)
    base = {rel: peeled[rel] for rel in files}
    new = changes(base, edits)
    patch, bad = build_patch(base, new)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled)
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт"
          + (f"; поздние патчи ложатся поверх: {', '.join(p.name for p in upper)}" if upper else ""))
    if write:
        (ROOT / "patches" / cid / name).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{name} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
