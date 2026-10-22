package handlers.chat.commands.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;
import org.l2jmobius.gameserver.managers.phantom.PhantomData;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomTemplate;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * L2Hub: фантомы — персонажи игроков без клиента.
 * <ul>
 * <li>//phantom list — напарники и кто из них в мире</li>
 * <li>//phantom spawn &lt;имя|роль|all&gt; [уровень] — поставить рядом с GM; уровень по умолчанию — уровень GM</li>
 * <li>//phantom despawn &lt;имя|роль|all&gt; — убрать из мира (персонаж остаётся в базе)</li>
 * <li>//phantom reload — перечитать Phantoms.ini и L2HubPhantoms.xml</li>
 * </ul>
 * Ответы GM — по-английски: системные сообщения клиента с латиницей отображаются надёжно.
 */
public class AdminPhantom implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_phantom"
	};

	private static final int SPAWN_DISTANCE = 90;

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		final StringTokenizer st = new StringTokenizer(command, " ");
		st.nextToken();
		final String action = st.hasMoreTokens() ? st.nextToken().toLowerCase() : "list";
		if (!PhantomsConfig.PHANTOMS_ENABLED && !action.equals("reload"))
		{
			activeChar.sendSysMessage("Phantoms are disabled: EnablePhantoms in config/Custom/Phantoms.ini.");
			return false;
		}

		switch (action)
		{
			case "list":
			{
				list(activeChar);
				return true;
			}
			case "spawn":
			{
				return spawn(activeChar, st);
			}
			case "despawn":
			{
				return despawn(activeChar, st);
			}
			case "reload":
			{
				PhantomsConfig.load();
				PhantomData.getInstance().load();
				activeChar.sendSysMessage("Phantoms reloaded: " + PhantomData.getInstance().getTemplates().size() + " companions.");
				return true;
			}
			default:
			{
				activeChar.sendSysMessage("Usage: //phantom list | spawn <name|role|all> [level] | despawn <name|role|all> | reload");
				return false;
			}
		}
	}

	private void list(Player activeChar)
	{
		activeChar.sendSysMessage("Phantoms (" + PhantomData.getInstance().getTemplates().size() + "):");
		for (PhantomTemplate template : PhantomData.getInstance().getTemplates())
		{
			final Player phantom = PhantomManager.getInstance().getPhantom(template);
			if (phantom == null)
			{
				activeChar.sendSysMessage(template.getName() + " (" + template.getRole() + "): not in the world");
			}
			else
			{
				activeChar.sendSysMessage(template.getName() + " (" + template.getRole() + "): level " + phantom.getLevel() + " " + phantom.getPlayerClass().name() + ", " + phantom.getX() + " " + phantom.getY() + " " + phantom.getZ() + (phantom.isDead() ? ", dead" : ""));
			}
		}
	}

	private boolean spawn(Player activeChar, StringTokenizer st)
	{
		if (!st.hasMoreTokens())
		{
			activeChar.sendSysMessage("Usage: //phantom spawn <name|role|all> [level]");
			return false;
		}

		final List<PhantomTemplate> targets = resolve(st.nextToken());
		if (targets.isEmpty())
		{
			activeChar.sendSysMessage("No such phantom. See //phantom list.");
			return false;
		}

		int level = activeChar.getLevel();
		if (st.hasMoreTokens())
		{
			try
			{
				level = Integer.parseInt(st.nextToken());
			}
			catch (NumberFormatException e)
			{
				activeChar.sendSysMessage("Level must be a number.");
				return false;
			}
		}

		if (activeChar.getInstanceId() != 0)
		{
			activeChar.sendSysMessage("Phantoms cannot be spawned inside an instance.");
			return false;
		}

		int index = 0;
		for (PhantomTemplate template : targets)
		{
			final Player phantom = PhantomManager.getInstance().spawn(template, near(activeChar, index++), level);
			if (phantom == null)
			{
				activeChar.sendSysMessage(template.getName() + ": spawn failed, see the game server log.");
			}
			else
			{
				activeChar.sendSysMessage(template.getName() + ": level " + phantom.getLevel() + " " + phantom.getPlayerClass().name() + ", " + phantom.getAllSkills().size() + " skills.");
			}
		}
		return true;
	}

	private boolean despawn(Player activeChar, StringTokenizer st)
	{
		if (!st.hasMoreTokens())
		{
			activeChar.sendSysMessage("Usage: //phantom despawn <name|role|all>");
			return false;
		}

		final List<PhantomTemplate> targets = resolve(st.nextToken());
		if (targets.isEmpty())
		{
			activeChar.sendSysMessage("No such phantom. See //phantom list.");
			return false;
		}

		for (PhantomTemplate template : targets)
		{
			activeChar.sendSysMessage(template.getName() + (PhantomManager.getInstance().despawn(template) ? ": despawned." : ": was not in the world."));
		}
		return true;
	}

	/**
	 * Имя напарника, роль (HEALER, BUFFER, ...) или all.
	 */
	private static List<PhantomTemplate> resolve(String key)
	{
		final List<PhantomTemplate> result = new ArrayList<>();
		final PhantomTemplate byName = PhantomData.getInstance().getTemplate(key);
		if ((byName != null) && !byName.isHunter()) // охотниками управляет PhantomHunterManager
		{
			result.add(byName);
			return result;
		}

		for (PhantomTemplate template : PhantomData.getInstance().getCompanions())
		{
			if (key.equalsIgnoreCase("all") || template.getRole().name().equalsIgnoreCase(key))
			{
				result.add(template);
			}
		}
		return result;
	}

	/**
	 * Точка сбоку от GM, на земле по геодате и с проходом от GM; фантом смотрит на GM.
	 */
	private static Location near(Player activeChar, int index)
	{
		final double facing = (activeChar.getHeading() * 2 * Math.PI) / 65536;
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double angle = facing + Math.toRadians(60 + (index * 50) + (attempt * 45));
			final int x = activeChar.getX() + (int) (Math.cos(angle) * SPAWN_DISTANCE);
			final int y = activeChar.getY() + (int) (Math.sin(angle) * SPAWN_DISTANCE);
			final int z = GeoEngine.getInstance().getHeight(x, y, activeChar.getZ());
			if (PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), activeChar.getX(), activeChar.getY(), activeChar.getZ(), x, y, z, activeChar.getInstanceId()))
			{
				return new Location(x, y, z, LocationUtil.calculateHeadingFrom(x, y, activeChar.getX(), activeChar.getY()));
			}
		}
		return new Location(activeChar.getX(), activeChar.getY(), activeChar.getZ(), activeChar.getHeading());
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
