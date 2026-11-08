package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.AutoPotionsConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.network.serverpackets.ExAutoSoulShot;
import org.l2jmobius.gameserver.taskmanagers.AutoPotionTaskManager;

/** Личные автопоты отдельно от автобоя и ярлык «Автобой» на панели: клик — пуск/стоп, свечение — бой идёт. */
public final class X10PlayerTools implements IVoicedCommandHandler
{
	private static final Logger LOGGER = Logger.getLogger(X10PlayerTools.class.getName());
	private static final String ENABLED = "L2HubAutoPotions";
	/** Предмет-ярлык автобоя; клиент рисует его по своим DAT, свечение — как у авто-сосок. */
	public static final int AUTOPLAY_ITEM = 40003;
	private static final String AUTOPLAY_BAR = "L2HubAutoPlayBarV1";
	private static final Map<Integer, Boolean> GLOW = new ConcurrentHashMap<>();
	private static final Map<Integer, long[]> EARNED = new ConcurrentHashMap<>();

	static void start()
	{
		VoicedCommandHandler.getInstance().registerHandler(new X10PlayerTools());
		ItemHandler.getInstance().registerHandler(new L2HubAutoPlayToggle());
		ThreadPool.scheduleAtFixedRate(X10PlayerTools::tick, 2000, 1000);
	}

	/** Обработчик предмета 40003 (handler в L2HubX10Currency.xml): обычный клик переключает автобой. */
	public static final class L2HubAutoPlayToggle implements org.l2jmobius.gameserver.handler.IItemHandler
	{
		@Override
		public boolean onItemUse(org.l2jmobius.gameserver.entity.actor.Playable playable, org.l2jmobius.gameserver.entity.item.instance.Item item, boolean forceUse)
		{
			if (!(playable instanceof Player player)) { return false; }
			autoPlay(player, !player.isAutoPlaying());
			return true;
		}
	}

	/** Пуск/стоп той же командой, что кнопки окна (первый пуск сам включает атаку), но без открытия окна. */
	static void autoPlay(Player player, boolean on)
	{
		if (player == null || PhantomManager.getInstance().isPhantom(player)) { return; }
		final IVoicedCommandHandler play = VoicedCommandHandler.getInstance().getHandler("play");
		if (play == null)
		{
			player.sendMessage(text("Автобой выключен в настройках сервера.", "Auto combat is disabled on this server."));
			glow(player, false);
			return;
		}
		play.onCommand("play", player, (on ? "start" : "stop") + " quiet");
		glow(player, player.isAutoPlaying());
		player.sendMessage(player.isAutoPlaying() ? text("Автобой запущен. Повторный клик по ярлыку — стоп.", "Auto combat started. Click the shortcut again to stop.") : text("Автобой остановлен.", "Auto combat stopped."));
	}

	static void refreshGlow(Player player)
	{
		if (player.isAutoPlaying()) { glow(player, true); }
	}

	private static void glow(Player player, boolean on)
	{
		GLOW.put(player.getObjectId(), on);
		player.sendPacket(new ExAutoSoulShot(AUTOPLAY_ITEM, on ? 1 : 0));
	}

	/** Порог банок из прежнего окна автобоя пишется в новые автопоты, чтобы поле в окне работало. */
	public static void setThreshold(Player player, String type, int percent)
	{
		if (!managesPotions(player)) { return; }
		player.getVariables().set("L2HubAutoPot_" + type, Math.clamp(percent, 0, 95));
		player.getVariables().storeMe();
	}

	public static int threshold(Player player, String type)
	{
		return percent(player, type);
	}

	/** Ярлык выдаётся один раз и ложится в свободную ячейку второй панели; убранный игроком с панели не возвращается. */
	private static void ensureToggle(Player player)
	{
		if (ItemData.getInstance().getTemplate(AUTOPLAY_ITEM) == null) { return; }
		var item = player.getInventory().getItemByItemId(AUTOPLAY_ITEM);
		if (item == null)
		{
			item = org.l2jmobius.gameserver.managers.phantom.PhantomCompat.addItem(player, org.l2jmobius.gameserver.entity.item.enums.ItemProcessType.REWARD, AUTOPLAY_ITEM, 1, player);
			if (item == null) { return; }
		}
		if (player.getVariables().getBoolean(AUTOPLAY_BAR, false)) { return; }
		player.getVariables().set(AUTOPLAY_BAR, true);
		for (int page : new int[] {1, 0})
		{
			for (int slot = 0; slot < 12; slot++)
			{
				if (player.getShortcut(slot, page) != null) { continue; }
				final var shortcut = new org.l2jmobius.gameserver.entity.actor.holders.player.Shortcut(slot, page, org.l2jmobius.gameserver.entity.actor.enums.player.ShortcutType.ITEM, item.getObjectId(), -1, 1);
				player.registerShortcut(shortcut);
				player.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.ShortcutRegister(shortcut));
				player.getVariables().storeMe();
				return;
			}
		}
		player.getVariables().storeMe();
	}

	public static boolean autoPotionsEnabled(Player player)
	{
		return player != null && AutoPotionsConfig.AUTO_POTIONS_ENABLED && player.getVariables().getBoolean(ENABLED, false);
	}

	/** Для живого игрока новые автопоты полностью заменяют старые слоты автобоя, включая состояние «выкл». */
	public static boolean managesPotions(Player player)
	{
		return X10World.enabled() && player != null && !PhantomManager.getInstance().isPhantom(player);
	}

	public static boolean isManagedPotion(int itemId)
	{
		return allowed("hp").contains(itemId) || allowed("mp").contains(itemId) || allowed("cp").contains(itemId);
	}

	@Override public String[] getCommandList() { return new String[] {"autopots"}; }

	@Override public boolean onCommand(String command, Player player, String params)
	{
		return potions(player, params);
	}

	public static boolean potions(Player player, String params)
	{
		if (player == null) { return false; }
		AutoPotionTaskManager.getInstance().remove(player);
		final String[] args = params == null ? new String[0] : params.strip().split("\\s+");
		if (args.length > 0)
		{
			if (args[0].equals("toggle") || args[0].equals("on") || args[0].equals("off"))
			{
				player.getVariables().set(ENABLED, args[0].equals("on") || (!args[0].equals("off") && !player.getVariables().getBoolean(ENABLED, false)));
			}
			else if (args.length >= 3 && Set.of("hp", "mp", "cp").contains(args[0]))
			{
				try
				{
					final int percent = Integer.parseInt(args[1]);
					final int itemId = Integer.parseInt(args[2]);
					if (percent >= 0 && percent <= 95 && (itemId == 0 || allowed(args[0]).contains(itemId)))
					{
						player.getVariables().set("L2HubAutoPot_" + args[0], percent);
						player.getVariables().set("L2HubAutoPotItem_" + args[0], itemId);
					}
				}
				catch (NumberFormatException ignored) { }
			}
			player.getVariables().storeMe();
		}
		final StringBuilder out = new StringBuilder("<html><body><font color=\"LEVEL\">").append(text("Автопоты", "Auto potions")).append("</font><br>")
			.append(text("Работают независимо от автобоя. Расходуют только твои банки с обычным откатом. Порог 0% выключает тип.", "Independent of auto combat. Use only your own potions and their normal reuse. Set 0% to disable a type.")).append("<br>")
			.append(X10Text.button(text("Автопоты: ", "Auto potions: ") + text(autoPotionsEnabled(player) ? "вкл" : "выкл", autoPotionsEnabled(player) ? "on" : "off"), "bypass voice .autopots toggle", 210)).append("<br>");
		if (!AutoPotionsConfig.AUTO_POTIONS_ENABLED) { out.append(text("Отключены владельцем сервера.", "Disabled by the server owner.")).append("<br>"); }
		for (String type : new String[] {"hp", "mp", "cp"})
		{
			final int percent = percent(player, type);
			final int selected = player.getVariables().getInt("L2HubAutoPotItem_" + type, 0);
			out.append("<font color=\"LEVEL\">").append(type.toUpperCase()).append(" · ").append(percent).append("%</font><br1><table width=270><tr><td width=140><combobox var=\"p_").append(type)
				.append("\" width=65 list=\"").append(percent).append(";0;20;30;40;50;60;70;80;90;95\"></td><td>")
				.append(X10Text.button(text("Порог", "Threshold"), "bypass voice .autopots " + type + " $p_" + type + " " + selected, 110)).append("</td></tr></table>");
			out.append(X10Text.button(text("Автовыбор из рюкзака", "Select from inventory"), "bypass voice .autopots " + type + " " + percent + " 0", 230)).append("<br1>");
			for (int id : allowed(type))
			{
				final long count = player.getInventory().getInventoryItemCount(id, -1);
				final var item = ItemData.getInstance().getTemplate(id);
				if (item != null && count > 0) { out.append(X10Text.button((selected == id ? "[+] " : "") + item.getName() + " ×" + count, "bypass voice .autopots " + type + " " + percent + " " + id, 250)).append("<br1>"); }
			}
			out.append("<br>");
		}
		player.sendPacket(new NpcHtmlMessage(out.append("</body></html>").toString()));
		return true;
	}

	private static int percent(Player player, String type)
	{
		return Math.clamp(player.getVariables().getInt("L2HubAutoPot_" + type, type.equals("hp") ? AutoPotionsConfig.AUTO_HP_PERCENTAGE : type.equals("mp") ? AutoPotionsConfig.AUTO_MP_PERCENTAGE : AutoPotionsConfig.AUTO_CP_PERCENTAGE), 0, 95);
	}

	private static Set<Integer> allowed(String type)
	{
		final Set<Integer> result = new LinkedHashSet<>();
		for (int id : type.equals("hp") ? AutoPotionsConfig.AUTO_HP_ITEM_IDS : type.equals("mp") ? AutoPotionsConfig.AUTO_MP_ITEM_IDS : AutoPotionsConfig.AUTO_CP_ITEM_IDS) { result.add(id); }
		return result;
	}

	private static void use(Player player, String type, double current)
	{
		if (current >= percent(player, type)) { return; }
		final int selected = player.getVariables().getInt("L2HubAutoPotItem_" + type, 0);
		for (int id : allowed(type))
		{
			if (selected > 0 && selected != id) { continue; }
			final var item = player.getInventory().getItemByItemId(id);
			if (item == null || item.getCount() <= 0 || player.getItemRemainingReuseTime(item.getObjectId()) > 0 || !item.getTemplate().checkCondition(player, player, false)) { continue; }
			if (item.getTemplate().getSkills() != null && java.util.Arrays.stream(item.getTemplate().getSkills()).anyMatch(s -> player.hasSkillReuse(s.getSkill().getReuseHashCode()) || !s.getSkill().checkCondition(player, player, false))) { continue; }
			final var handler = ItemHandler.getInstance().getHandler(item.getEtcItem());
			if (handler != null && handler.onItemUse(player, item, false))
			{
				if (item.getReuseDelay() > 0) { player.addTimeStampItem(item, item.getReuseDelay()); }
				return;
			}
		}
	}

	static void earned(Player player, long coins, long style)
	{
		final long[] earned = EARNED.computeIfAbsent(player.getObjectId(), id -> new long[3]);
		synchronized (earned) { earned[0] += Math.max(0, coins); earned[1] += Math.max(0, style); }
	}

	static void earnedAdena(Player player, long count)
	{
		final long[] earned = EARNED.computeIfAbsent(player.getObjectId(), id -> new long[3]);
		synchronized (earned) { earned[2] += Math.max(0, count); }
	}

	private static void tick()
	{
		for (Player player : World.getPlayers())
		{
			if (!player.isOnline() || PhantomManager.getInstance().isPhantom(player)) { continue; }
			try
			{
				if (autoPotionsEnabled(player) && player.getLevel() >= AutoPotionsConfig.AUTO_POTION_MIN_LEVEL && !player.isAlikeDead() && !player.isTeleporting() && !player.isControlBlocked() && !player.isInStoreMode() && (AutoPotionsConfig.AUTO_POTIONS_IN_OLYMPIAD || !player.isInOlympiadMode()))
				{
					AutoPotionTaskManager.getInstance().remove(player);
					if (AutoPotionsConfig.AUTO_HP_ENABLED) { use(player, "hp", player.getCurrentHpPercent()); }
					if (AutoPotionsConfig.AUTO_MP_ENABLED) { use(player, "mp", player.getCurrentMpPercent()); }
					if (AutoPotionsConfig.AUTO_CP_ENABLED) { use(player, "cp", player.getMaxCp() > 0 ? player.getCurrentCp() * 100 / player.getMaxCp() : 100); }
				}
				ensureToggle(player);
				X10Style.tick(player);
				// Бой мог остановиться сам (смерть, телепорт, окно): свечение ярлыка всегда повторяет реальное состояние.
				if (GLOW.getOrDefault(player.getObjectId(), false) != player.isAutoPlaying())
				{
					glow(player, player.isAutoPlaying());
				}
			}
			catch (Exception e) { LOGGER.log(Level.WARNING, "Player tools tick failed", e); }
		}
		final Set<Integer> online = World.getPlayers().stream().filter(Player::isOnline).map(Player::getObjectId).collect(java.util.stream.Collectors.toSet());
		GLOW.keySet().retainAll(online); EARNED.keySet().retainAll(online);
	}
}
