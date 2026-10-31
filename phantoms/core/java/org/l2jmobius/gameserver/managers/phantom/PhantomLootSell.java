package org.l2jmobius.gameserver.managers.phantom;

import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;

/**
 * L2Hub: продажа лута с доски Alt+B (docs/PHANTOMS.md, раздел 4.60; по вкладу пользователя): окно торговца со своим пустым списком покупки, вне списка 423 Mobius.
 * Цену считает сам пакет продажи по справочной цене предмета. Открытие окна — не разрешение на сделку: условия {@link #canUse} проверяет и пакет.
 */
public final class PhantomLootSell
{
	/** Свой номер списка: в данных Mobius такого нет, в пакете сделки он значит «продажа с доски». */
	public static final int LIST_ID = 50049997;
	private static final Path TOWN = Path.of("./config/Custom/L2HubTown.ini");

	private PhantomLootSell()
	{
	}

	public static boolean isList(int id)
	{
		return id == LIST_ID;
	}

	/**
	 * GM-шоп доски включён ({@code TownGmShop}), и игрок мог бы им пользоваться. Мёртвым и с открытой лавкой нельзя при любых настройках доски.
	 * @return можно продавать; причину отказа пишет доска, пакет отказывает молча
	 */
	public static boolean canUse(Player player)
	{
		if ((player == null) || !player.isOnline() || !shopOn() || !CommunityBoardConfig.CUSTOM_CB_ENABLED || !CommunityBoardConfig.COMMUNITYBOARD_ENABLE_MULTISELLS || player.isAlikeDead() || player.isInStoreMode())
		{
			return false;
		}
		if (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (PhantomCompat.karma(player) > 0))
		{
			return false;
		}
		if (CommunityBoardConfig.COMMUNITYBOARD_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		return !CommunityBoardConfig.COMMUNITYBOARD_COMBAT_DISABLED || !(player.isCastingNow() || player.isInCombat() || player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || player.isOnEvent());
	}

	/**
	 * Как выключатель на доске: нет файла или ключа — включено.
	 */
	private static boolean shopOn()
	{
		if (!Files.isRegularFile(TOWN))
		{
			return true;
		}
		try (Reader reader = Files.newBufferedReader(TOWN, StandardCharsets.UTF_8))
		{
			final Properties properties = new Properties();
			properties.load(reader);
			return !"false".equalsIgnoreCase(properties.getProperty("TownGmShop", "True").trim());
		}
		catch (Exception e)
		{
			return true;
		}
	}

	/**
	 * @return заголовок страницы
	 */
	public static String title()
	{
		return tr("Продажа лута");
	}

	/**
	 * @return подпись кнопки в GM-шопе
	 */
	public static String label()
	{
		return tr("Продать лут");
	}

	/**
	 * @param shop команда GM-шопа
	 * @return содержимое страницы
	 */
	public static String content(String shop)
	{
		return "<br>" + tr("В торговом окне выбери вещи и количество, затем подтверди продажу.") + "<br><br>" + tr("Выкуп — по обычным ценам NPC. Купить здесь нечего.") + "<br><br>" + button(tr("Открыть продажу"), shop + " sell", 170) + "<br>" + button(tr("В GM-шоп"), shop, 150);
	}

	private static String button(String label, String action, int width)
	{
		return "<button value=\"" + label + "\" action=\"bypass " + action + "\" width=" + width + " height=25 back=\"" + (width > 90 ? PhantomCompat.WIDE_BUTTON_BACK : PhantomCompat.BUTTON_BACK) + "\" fore=\"" + (width > 90 ? PhantomCompat.WIDE_BUTTON_FORE : PhantomCompat.BUTTON_FORE) + "\">";
	}
}
