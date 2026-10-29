"""Патч «Самопроверка: моды малого мира» для хроники Mobius: patches/<id>/0032-l2hub-selftest-small-world.patch.

Поверх самопроверки модов 0015 и 0024 (data/scripts/custom/L2HubSelfTest/L2HubSelfTest.java; High Five и Gracia Epilogue —
у Interlude, Classic и Essence поверх неё слой перевода, якоря другие) — новые пункты в log/L2HubSelfTest.json, их без правок
покажет «Проверить всё» («Моды L2Hub»):
  - «Олимпиада малого мира» (0025) — с какого числа записавшихся матч, период героев и сколько до его конца, идут ли матчи;
  - «Семь печатей малого мира» (0026) — открыты ли катакомбы и некрополи, от скольких Фестиваль тьмы, какой сейчас период;
  - «Эпики малого мира» (0028) — включены ли; пересчитан ли респаун (загруженный интервал Антараса сверяется с GrandBoss.ini
    и процентом), ожидание Антараса и Валакаса, порог к Белету, какие эпики живы;
  - «Фейки в городах» (0031) — включены ли, сколько их в мире из расписания data/L2HubTownFakes.xml;
  - «Гробницы малого мира» (0033), «Разлом малого мира» (0035), «Мастер дворянства» (0034) — только у хроник, где мод
    наложен (ключ SmallWorldSepulchers, SmallWorldRift в General.ini, TownNoblesse в L2HubTown.ini дерева): включён ли, от
    скольких пати, сколько в мире менеджеров гробниц, Rift Post и мастеров, уровень и цена дворянства.
Наложен ли мод: пометки «L2Hub (малый мир)» в его конфиге (0025, 0026), поле SMALL_WORLD_EPICS в GrandBossConfig (0028),
файл расписания (0031); нет — пункт скажет «не наложен». Только конфиги, файлы и рефлексия: компиляция не зависит от классов,
которых у хроники может не быть. Переводы строк скрипта сохраняются. Патч проверяется наложением на копию дерева байт в байт.
Нет у хроники Семи печатей (Classic, Essence) — нет и пункта; нет Валакаса и Белета в GrandBossConfig — пункт эпиков без них.

    python -X utf8 tools/l2hub_selftest_small.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_selftest_small.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0032 или поверх скрипта лежат патчи с бóльшим номером — они снимаются с копии дерева, патч
собирается заново, поздние ложатся обратно (tools/patch_layers.py). Кроме слоя языка (Interlude, Classic, Essence: 0099 выше 0032): он
оборачивает в tr() каждую русскую строку, поверх новых строк его не наложить. --write снимает его с дерева со всех его файлов,
после этого — python -X utf8 tools/l2hub_lang.py <id> --write (переводы новых строк — в tools/l2hub_lang/en.json; пометку
«L2Hub (малый мир)» туда не класть: проба ищет её в конфиге, tr() без перевода оставляет её русской).
"""
import sys
from pathlib import Path

import patch_layers
from l2hub_selftest_world import build_patch, tree

ROOT = Path(__file__).resolve().parents[1]
NAME = "0032-l2hub-selftest-small-world.patch"
SCRIPT = "dist/game/data/scripts/custom/L2HubSelfTest/L2HubSelfTest.java"
MARK = "L2Hub 0032"
LANGUAGE = "-l2hub-language.patch"  # слой языка (tools/l2hub_lang.py): у Interlude, Classic и Essence — 0099, выше 0032
SEVEN_SIGNS = "java/org/l2jmobius/gameserver/mechanics/sevensigns/SevenSigns.java"
GRAND_BOSS_CONFIG = "java/org/l2jmobius/gameserver/config/GrandBossConfig.java"

PROBES_ANCHOR = '\t\tprobe(checks, "Летопись мира", L2HubSelfTest::chronicle);\n'
SEVEN_SIGNS_PROBE = '\t\tprobe(checks, "Семь печатей малого мира", L2HubSelfTest::sevenSigns);\n'
PROBES = (PROBES_ANCHOR
          + '\t\tprobe(checks, "Олимпиада малого мира", L2HubSelfTest::olympiad); // L2Hub 0032: моды малого мира\n'
          + SEVEN_SIGNS_PROBE
          + '\t\tprobe(checks, "Эпики малого мира", L2HubSelfTest::epics);\n'
          + '\t\tprobe(checks, "Фейки в городах", L2HubSelfTest::townFakes);\n')
METHODS_ANCHOR = "\tprivate static String onOff(boolean value)\n"
METHODS = r"""	// ---------- L2Hub 0032: моды малого мира — олимпиада, семь печатей, эпики, фейки в городах ----------

	private static final String[] SEVEN_SIGNS_PERIODS =
	{
		"подготовка",
		"соревнование",
		"подсчёт итогов",
		"действие печатей"
	};

	private static Properties ini(String path) throws Exception
	{
		final Properties properties = new Properties();
		final Path file = Paths.get(path);
		if (Files.isRegularFile(file))
		{
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
			{
				properties.load(reader);
			}
		}
		return properties;
	}

	/** Мод малого мира наложен: над изменёнными строками конфига — его пометки «L2Hub (малый мир)» (0025, 0026). */
	private static boolean smallWorldMarks(String config) throws Exception
	{
		final Path path = Paths.get("./config/" + config);
		return Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).contains("L2Hub (малый мир)");
	}

	/** Статическое поле класса сервера; null — такого класса или поля у хроники нет. */
	private static Object staticField(String className, String field)
	{
		try
		{
			return Class.forName(className).getField(field).get(null);
		}
		catch (ReflectiveOperationException e)
		{
			return null;
		}
	}

	/** Метод без аргументов у одиночки сервера (getInstance()). */
	private static Object instanceCall(String className, String method) throws Exception
	{
		final Class<?> type = Class.forName(className);
		return type.getMethod(method).invoke(type.getMethod("getInstance").invoke(null));
	}

	private static String duration(long millis)
	{
		final long minutes = Math.max(0, millis) / 60000;
		return (minutes >= 2880) ? ((minutes / 1440) + " дн. " + ((minutes / 60) % 24) + " ч") : ((minutes / 60) + " ч " + (minutes % 60) + " мин");
	}

	private static Check olympiad() throws Exception
	{
		if (!smallWorldMarks("Olympiad.ini"))
		{
			return new Check("", false, "в Olympiad.ini нет пометок «L2Hub (малый мир)» — мод 0025 не наложен");
		}
		final String config = "org.l2jmobius.gameserver.config.OlympiadConfig";
		final String olympiad = "org.l2jmobius.gameserver.mechanics.olympiad.Olympiad";
		// У хроник без OlympiadUseCustomPeriodSettings (Classic, Essence) период — сразу OlympiadPeriod.
		final String period = Boolean.FALSE.equals(staticField(config, "OLYMPIAD_USE_CUSTOM_PERIOD_SETTINGS")) ? "MONTH (OlympiadUseCustomPeriodSettings = False)" : String.valueOf(staticField(config, "OLYMPIAD_PERIOD"));
		final String rules = "матч, как только записались: по классу " + staticField(config, "OLYMPIAD_CLASSED") + ", без класса " + staticField(config, "OLYMPIAD_NONCLASSED") + "; период героев " + period;
		if (Boolean.FALSE.equals(staticField(config, "OLYMPIAD_ENABLED")))
		{
			return new Check("", true, "выключена (OlympiadEnabled = False в Olympiad.ini); " + rules);
		}
		final boolean matches = Boolean.TRUE.equals(instanceCall(olympiad, "inCompPeriod"));
		final long end = ((Number) instanceCall(olympiad, "getMillisToOlympiadEnd")).longValue();
		// Период кончился — Mobius считает героев (validation), новый период начнётся после подсчёта.
		final boolean validation = Boolean.TRUE.equals(instanceCall(olympiad, "isOlympiadEnd")) || (end <= 0);
		return new Check("", true, rules + (validation ? ", сейчас подсчёт героев" : (", до конца нынешнего " + duration(end))) + "; матчи сейчас " + (matches ? "идут" : "не идут"));
	}

	private static Check sevenSigns() throws Exception
	{
		if (!smallWorldMarks("Feature.ini"))
		{
			return new Check("", false, "в Feature.ini нет пометок «L2Hub (малый мир)» — мод 0026 не наложен");
		}
		final String config = "org.l2jmobius.gameserver.config.FeatureConfig";
		final String signs = "org.l2jmobius.gameserver.mechanics.sevensigns.SevenSigns";
		final int period = ((Number) instanceCall(signs, "getCurrentPeriod")).intValue();
		final long change = ((Number) instanceCall(signs, "getMilliToPeriodChange")).longValue();
		return new Check("", true, "катакомбы — " + (Boolean.TRUE.equals(staticField(config, "ALT_SEVENSIGNS_OPEN_CATACUMBS")) ? "открыты всем" : "по печатям") + ", некрополи — " + (Boolean.TRUE.equals(staticField(config, "ALT_SEVENSIGNS_OPEN_NECROPOLIS")) ? "открыты всем" : "по печатям") + ", Фестиваль тьмы — от " + staticField(config, "ALT_FESTIVAL_MIN_PLAYER") + "; сейчас " + (((period >= 0) && (period < SEVEN_SIGNS_PERIODS.length)) ? SEVEN_SIGNS_PERIODS[period] : ("период " + period)) + ", до смены " + duration(change));
	}

	private static Check epics() throws Exception
	{
		final String config = "org.l2jmobius.gameserver.config.GrandBossConfig";
		final Object enabled = staticField(config, "SMALL_WORLD_EPICS");
		if (enabled == null)
		{
			return new Check("", false, "нет SmallWorldEpics в GrandBossConfig — мод 0028 не наложен");
		}
		final Set<String> alive = new TreeSet<>();
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Attackable boss) && boss.getClass().getSimpleName().equals("GrandBoss") && !boss.isDead())
			{
				alive.add(boss.getName());
			}
		}
		final String bosses = alive.isEmpty() ? "живых эпиков в мире нет" : ("живы: " + String.join(", ", alive));
		if (!Boolean.TRUE.equals(enabled))
		{
			return new Check("", true, "выключены (SmallWorldEpics = False) — эпики как у Mobius; " + bosses);
		}
		// Пересчёт GrandBossConfig: загруженный интервал Антараса — SmallWorldEpicsIntervalPercent % от записанного в GrandBoss.ini.
		final int percent = ((Number) staticField(config, "SMALL_WORLD_EPICS_INTERVAL_PERCENT")).intValue();
		final int official = Integer.parseInt(ini("./config/GrandBoss.ini").getProperty("IntervalOfAntharasSpawn", "264").trim());
		final int antharas = ((Number) staticField(config, "ANTHARAS_SPAWN_INTERVAL")).intValue();
		final int expected = Math.max(1, Math.round((official * percent) / 100f));
		return new Check("", antharas == expected, "вкл: респаун — " + percent + " % официального (Антарас — раз в " + antharas + " ч вместо " + official + ((antharas == expected) ? "" : (", а должно быть " + expected + " — пересчёт не сработал")) + "), ожидание Антараса и Валакаса — " + staticField(config, "ANTHARAS_WAIT_TIME") + " мин, к Белету — от " + staticField(config, "BELETH_MIN_PLAYERS") + "; " + bosses);
	}

	private static Check townFakes() throws Exception
	{
		final Path roster = Paths.get("./data/L2HubTownFakes.xml");
		if (!Files.isRegularFile(roster))
		{
			return new Check("", false, "нет data/L2HubTownFakes.xml — мод 0031 не наложен");
		}
		if (!isTrue(customIni("FakePlayers.ini"), "EnableFakePlayers", "False"))
		{
			return new Check("", true, "выключены (EnableFakePlayers = False в FakePlayers.ini)");
		}
		final String density = customIni("L2HubTownFakes.ini").getProperty("TownFakesDensity", "100").trim();
		if (density.equals("0"))
		{
			return new Check("", true, "выключены (TownFakesDensity = 0 в L2HubTownFakes.ini)");
		}
		final Set<Integer> ids = new HashSet<>();
		final Matcher matcher = Pattern.compile("npcId=\"(\\d+)\"").matcher(Files.readString(roster, StandardCharsets.UTF_8));
		while (matcher.find())
		{
			ids.add(Integer.parseInt(matcher.group(1)));
		}
		int present = 0;
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof org.l2jmobius.gameserver.entity.actor.Npc npc) && ids.contains(npc.getId()))
			{
				present++;
			}
		}
		if ((present == 0) && (ScriptManager.getInstance().getScript("L2HubTownFakes") == null))
		{
			return new Check("", false, "скрипт L2HubTownFakes не загрузился — причина в логе сервера");
		}
		return new Check("", present > 0, "в мире " + present + " из " + ids.size() + " по расписанию (плотность " + density + " %; утром, вечером и ночью выходят не все)");
	}

"""
# Пункты модов 0033–0035 — только у хроник, где мод наложен (по ключу в конфиге дерева): у прочих патч прежний байт в байт.
EXTRA_HEAD = r"""	// ---------- L2Hub 0032: гробницы, Разлом и мастер дворянства малого мира (0033–0035) ----------

	/** Сколько NPC с этими id сейчас в мире. */
	private static int npcsInWorld(int... ids)
	{
		int count = 0;
		for (WorldObject object : World.getVisibleObjects())
		{
			if (object instanceof org.l2jmobius.gameserver.entity.actor.Npc npc)
			{
				for (int id : ids)
				{
					if (npc.getId() == id)
					{
						count++;
						break;
					}
				}
			}
		}
		return count;
	}

"""
EXTRA = [  # (ключ в конфиге дерева, конфиг, строка probe, метод)
    ("SmallWorldSepulchers", "dist/game/config/General.ini",
     '\t\tprobe(checks, "Гробницы малого мира", L2HubSelfTest::sepulchers);\n', r"""	private static Check sepulchers() throws Exception
	{
		final String config = "org.l2jmobius.gameserver.config.GeneralConfig";
		final Object enabled = staticField(config, "SMALL_WORLD_SEPULCHERS");
		if (enabled == null)
		{
			return new Check("", false, "нет SmallWorldSepulchers в GeneralConfig — мод 0033 не наложен");
		}
		if (ScriptManager.getInstance().getScript("FourSepulchers") == null)
		{
			return new Check("", false, "скрипт FourSepulchers не загрузился — причина в логе сервера");
		}
		final int managers = npcsInWorld(31921, 31922, 31923, 31924);
		final String where = "менеджеров гробниц в мире " + managers + " из 4";
		if (!Boolean.TRUE.equals(enabled))
		{
			return new Check("", managers > 0, "выключены (SmallWorldSepulchers = False) — пати от четырёх, как у Mobius; " + where);
		}
		return new Check("", managers > 0, "вкл: пати от " + staticField(config, "SMALL_WORLD_SEPULCHERS_MIN_PARTY") + ", квест 620 и пропуск — только у живых; " + where);
	}

"""),
    ("SmallWorldRift", "dist/game/config/General.ini",
     '\t\tprobe(checks, "Разлом малого мира", L2HubSelfTest::rift);\n', r"""	private static Check rift() throws Exception
	{
		final String config = "org.l2jmobius.gameserver.config.GeneralConfig";
		final Object enabled = staticField(config, "SMALL_WORLD_RIFT");
		if (enabled == null)
		{
			return new Check("", false, "нет SmallWorldRift в GeneralConfig — мод 0035 не наложен");
		}
		final int posts = npcsInWorld(31488, 31489, 31490, 31491, 31492, 31493);
		final String rules = "пати от " + staticField(config, "RIFT_MIN_PARTY_SIZE") + ", вход новичков — " + staticField(config, "RIFT_ENTER_COST_RECRUIT") + " фрагментов; Rift Post в мире: " + posts;
		if (!Boolean.TRUE.equals(enabled))
		{
			return new Check("", posts > 0, "выключен (SmallWorldRift = False) — фрагменты платит каждый, как у Mobius; " + rules);
		}
		return new Check("", posts > 0, "вкл: фрагменты платят только живые; " + rules);
	}

"""),
    ("TownNoblesse", "dist/game/config/Custom/L2HubTown.ini",
     '\t\tprobe(checks, "Мастер дворянства", L2HubSelfTest::noblesse);\n', r"""	private static Check noblesse() throws Exception
	{
		final String config = "org.l2jmobius.gameserver.config.custom.NoblessMasterConfig";
		final Properties town = customIni("L2HubTown.ini");
		if (town.getProperty("TownNoblesse") == null)
		{
			return new Check("", false, "нет TownNoblesse в L2HubTown.ini — мод 0034 не наложен");
		}
		if (!isTrue(town, "TownNoblesse", "False"))
		{
			return new Check("", true, "выключен (TownNoblesse = False в L2HubTown.ini)");
		}
		if (!Boolean.TRUE.equals(staticField(config, "NOBLESS_MASTER_ENABLED")))
		{
			return new Check("", false, "TownNoblesse включён, а в NoblessMaster.ini Enabled = False — мастер не ответит");
		}
		final int masters = npcsInWorld(((Number) staticField(config, "NOBLESS_MASTER_NPCID")).intValue());
		final int itemId = ((Number) staticField(config, "NOBLESS_MASTER_ITEM_ID")).intValue();
		final String count = String.format(java.util.Locale.ROOT, "%,d", ((Number) staticField(config, "NOBLESS_MASTER_ITEM_COUNT")).longValue()).replace(',', ' ');
		return new Check("", masters > 0, "в мире " + masters + " (бафферов " + npcsInWorld(50008) + "), дворянство с " + staticField(config, "NOBLESS_MASTER_LEVEL_REQUIREMENT") + "-го уровня за " + count + ((itemId == 57) ? " адены" : (" предмета " + itemId)));
	}

"""),
]


def _cut(text: str, start: str, end: str) -> str:
    """Кусок text от start (включительно) до end (без него); оба — ровно по разу."""
    if text.count(start) != 1 or text.count(end) != 1 or text.index(start) > text.index(end):
        sys.exit(f"генератор: нет куска «{start.strip()[:50]}» — METHODS правили?")
    return text[:text.index(start)] + text[text.index(end):]


def _once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        sys.exit(f"генератор: «{old[:50]}» в METHODS не ровно один раз")
    return text.replace(old, new)


def for_chronicle(src: Path) -> tuple[str, str]:
    """PROBES и METHODS под хронику: без Семи печатей, Валакаса и Белета, если их у хроники нет (Classic, Essence)."""
    probes, methods = PROBES, METHODS
    if not (src / SEVEN_SIGNS).is_file():
        probes = _once(probes, SEVEN_SIGNS_PROBE, "")
        methods = _once(methods, "олимпиада, семь печатей, эпики", "олимпиада, эпики")
        methods = _cut(methods, "\tprivate static final String[] SEVEN_SIGNS_PERIODS", "\tprivate static Properties ini(")
        methods = _cut(methods, "\tprivate static Check sevenSigns()", "\tprivate static Check epics()")
    bosses = (src / GRAND_BOSS_CONFIG).read_text(encoding="utf-8") if (src / GRAND_BOSS_CONFIG).is_file() else ""
    if "VALAKAS_WAIT_TIME" not in bosses:
        methods = _once(methods, "ожидание Антараса и Валакаса — ", "ожидание Антараса — ")
    if "BELETH_MIN_PLAYERS" not in bosses:
        methods = _once(methods, '" мин, к Белету — от " + staticField(config, "BELETH_MIN_PLAYERS") + "; "', '" мин; "')
    extra = [(probe, method) for key, config, probe, method in EXTRA
             if (src / config).is_file() and key in (src / config).read_text(encoding="utf-8")]
    if extra:
        probes += "".join(probe for probe, _ in extra)
        methods += EXTRA_HEAD + "".join(method for _, method in extra)
    return probes, methods


def originals(cid: str, src: Path) -> tuple[dict[str, bytes | None], list[Path], dict[str, bytes | None]]:
    if not (src / SCRIPT).is_file():
        sys.exit(f"нет {SCRIPT} — сначала самопроверка 0015")
    current = (src / SCRIPT).read_bytes()
    own = (ROOT / "patches" / cid / NAME).is_file()
    if MARK.encode() in current:
        if not own:
            sys.exit(f"в {SCRIPT} уже есть «{MARK}», а patches/{cid}/{NAME} нет — нечем снять")
    elif own:
        sys.exit(f"patches/{cid}/{NAME} есть, а в {SCRIPT} нет «{MARK}» — дерево собрано без него?")
    elif not patch_layers.later(cid, NAME, [SCRIPT]):
        return {SCRIPT: current}, [], {}
    peeled, upper = patch_layers.peel(cid, src, NAME, [SCRIPT])
    print(f"{cid}: " + (f"прежний {NAME} снят с копии дерева" if own else "первый прогон")
          + (f", поздние сняты: {', '.join(p.name for p in upper)}" if upper else "") + ", патч собирается заново")
    return {SCRIPT: peeled[SCRIPT]}, upper, peeled


def changes(base: dict[str, bytes | None], src: Path) -> dict[str, tuple[bytes | None, bytes]]:
    old = base[SCRIPT]
    if MARK.encode() in old:
        sys.exit(f"{SCRIPT}: «{MARK}» есть и без патча — правили руками?")
    if b"L2Hub 0024" not in old:
        sys.exit(f"{SCRIPT}: нет «L2Hub 0024» — сначала самопроверка живого мира (tools/l2hub_selftest_world.py)")
    nl = "\r\n" if b"\r\n" in old else "\n"
    new = old
    probes, methods = for_chronicle(src)
    for anchor, repl in ((PROBES_ANCHOR, probes), (METHODS_ANCHOR, methods + METHODS_ANCHOR)):
        a, r = anchor.replace("\n", nl).encode(), repl.replace("\n", nl).encode()
        if new.count(a) != 1:
            sys.exit(f"{SCRIPT}: якорь «{anchor.strip()[:60]}» найден {new.count(a)} раз — самопроверка у хроники другая")
        new = new.replace(a, r)
    return {SCRIPT: (old, new)}


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = tree(cid)
    base, upper, peeled = originals(cid, src)
    plan = changes(base, src)
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    new = {rel: data for rel, (_, data) in plan.items()}
    language = [p for p in upper if p.name.endswith(LANGUAGE)]
    if language:
        # Слой языка не кладётся обратно: его файлы уходят в дерево без него, и его пересобирает tools/l2hub_lang.py.
        for rel in sorted(patch_layers.touched(language[0]) - set(new)):
            if peeled.get(rel) is None:
                sys.exit(f"{language[0].name}: без него нет {rel} — снять слой языка не выйдет")
            new[rel] = peeled[rel]
    final = patch_layers.restack(new, [p for p in upper if p not in language], peeled)
    own = ROOT / "patches" / cid / NAME
    same = own.is_file() and own.read_bytes() == patch
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт"
          + ("; патч тот же, что в patches/" if same else "; патч новый против patches/"))
    if write:
        (ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(ROOT)}")
    if language:
        print(f"{cid}: слой языка {language[0].name} " + ("снят с дерева" if write else "при --write снимется с дерева")
              + f" — затем: python -X utf8 tools/l2hub_lang.py {cid} --write")


if __name__ == "__main__":
    main()
