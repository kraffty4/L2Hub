package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.l2jmobius.gameserver.entity.Location;

/**
 * L2Hub: описание напарника-фантома из data/L2HubPhantoms.xml.<br>
 * Уровень у напарника не фиксирован: класс и шмот выбираются по уровню, до которого его подтягивают.
 */
public class PhantomTemplate
{
	/** Смены профессии: с какого уровня берётся следующий класс ветки. */
	private static final int[] CLASS_TIER_LEVELS =
	{
		20,
		40,
		76
	};

	public enum Role
	{
		HEALER,
		BUFFER,
		TANK,
		DAMAGE
	}

	/**
	 * Шмот одного грейда: надевается с minLevel. Ноль — слот пустой.
	 */
	public static class Gear
	{
		public final String grade;
		public final int minLevel;
		public final int rhand;
		public final int lhand;
		public final int head;
		public final int chest;
		public final int legs;
		public final int gloves;
		public final int feet;
		public final int shots;

		public Gear(String grade, int minLevel, int rhand, int lhand, int head, int chest, int legs, int gloves, int feet, int shots)
		{
			this.grade = grade;
			this.minLevel = minLevel;
			this.rhand = rhand;
			this.lhand = lhand;
			this.head = head;
			this.chest = chest;
			this.legs = legs;
			this.gloves = gloves;
			this.feet = feet;
			this.shots = shots;
		}

		public int[] items()
		{
			return new int[]
			{
				chest,
				legs,
				head,
				gloves,
				feet,
				rhand,
				lhand
			};
		}
	}

	private final String _name;
	private final Role _role;
	private final boolean _female;
	private final byte _face;
	private final byte _hairStyle;
	private final byte _hairColor;
	private final int[] _classes;
	private final Location _home;
	private final List<Gear> _gear;
	private final List<String> _aliases;
	private final boolean _hunter;
	private final float _social;
	private float _temper = 1;

	public PhantomTemplate(String name, Role role, boolean female, byte face, byte hairStyle, byte hairColor, int[] classes, Location home, List<Gear> gear, String aliases, boolean hunter, float social)
	{
		_name = name;
		_hunter = hunter;
		_social = Math.max(0, social);
		final List<String> names = new ArrayList<>();
		names.add(name.toLowerCase());
		for (String alias : aliases.toLowerCase().replace('ё', 'е').split(";"))
		{
			if (!alias.isBlank())
			{
				names.add(alias.strip());
			}
		}
		_aliases = Collections.unmodifiableList(names);
		_role = role;
		_female = female;
		_face = face;
		_hairStyle = hairStyle;
		_hairColor = hairColor;
		_classes = classes;
		_home = home;
		final List<Gear> sorted = new ArrayList<>(gear);
		sorted.sort(Comparator.comparingInt(g -> g.minLevel));
		_gear = Collections.unmodifiableList(sorted);
	}

	public String getName()
	{
		return _name;
	}

	public Role getRole()
	{
		return _role;
	}

	/**
	 * @return {@code true} — охотник (этап 5): живёт сам, качается с 1 уровня ({@link PhantomHunterManager}); {@code false} — напарник, которого зовут через .buddy
	 */
	public boolean isHunter()
	{
		return _hunter;
	}

	/**
	 * @return общительность охотника — множитель шанса заговорить первым ({@link PhantomInitiative}): 1 — обычный, больше — болтун, меньше — замкнутый
	 */
	public float getSocial()
	{
		return _social;
	}

	/**
	 * @return вспыльчивость охотника (патч 0037) — множитель шанса ответить «пнх» на предъяву за моба вместо «сорян»: 1 — обычный, больше — горячий, меньше — мягкий
	 */
	public float getTemper()
	{
		return _temper;
	}

	void setTemper(float temper)
	{
		_temper = Math.max(0, temper);
	}

	/**
	 * @return имя и прозвища в нижнем регистре, «ё» заменена на «е»: по ним в чате понятно, к кому обращаются
	 */
	public List<String> getAliases()
	{
		return _aliases;
	}

	public boolean isFemale()
	{
		return _female;
	}

	public byte getFace()
	{
		return _face;
	}

	public byte getHairStyle()
	{
		return _hairStyle;
	}

	public byte getHairColor()
	{
		return _hairColor;
	}

	/**
	 * @return стартовый класс ветки (из него создаётся персонаж)
	 */
	public int getStartClassId()
	{
		return _classes[0];
	}

	/**
	 * @param level уровень персонажа
	 * @return класс ветки, положенный на этом уровне
	 */
	public int getClassId(int level)
	{
		int tier = 0;
		while ((tier < CLASS_TIER_LEVELS.length) && (level >= CLASS_TIER_LEVELS[tier]))
		{
			tier++;
		}
		return _classes[Math.min(tier, _classes.length - 1)];
	}

	/**
	 * @param level уровень персонажа
	 * @return шмот самого высокого грейда, доступного на этом уровне, или {@code null}
	 */
	public Gear getGear(int level)
	{
		Gear result = null;
		for (Gear gear : _gear)
		{
			if (gear.minLevel <= level)
			{
				result = gear;
			}
		}
		return result;
	}

	public List<Gear> getAllGear()
	{
		return _gear;
	}

	/**
	 * @return точка у гейткипера Гирана: здесь появляется новый фантом, если его поднимают без GM рядом
	 */
	public Location getHome()
	{
		return _home;
	}
}
