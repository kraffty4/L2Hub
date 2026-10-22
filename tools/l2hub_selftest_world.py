"""Патч «Самопроверка: моды живого мира» для хроники Mobius: patches/<id>/0024-l2hub-selftest-world.patch.

Поверх самопроверки модов 0015 (data/scripts/custom/L2HubSelfTest/L2HubSelfTest.java, общая у Interlude, High Five,
Gracia Epilogue; у Classic и Essence — свой вариант с теми же якорями) — новые пункты в log/L2HubSelfTest.json, их без правок
покажет «Проверить всё» («Моды L2Hub»):
  - «Суперчемпионы» (0020) — включены ли, шанс, сколько живых (SuperChampion.alive() — рефлексией);
  - «Мир сейчас: .world» (0022) — команда .world есть, log/L2HubWorld.json свежий;
  - «Нашествие эльпи» (L2HubEvents.ini: Elpies, ElpiesSchedule) — включено ли и когда следующий старт;
  - «Летопись мира» (0023) — скрипт на месте, log/L2HubChronicle.json (или папка log) доступен для записи;
  - сезонные события — «L2Day по 30.11.2026» (последний день, как в окне и .world), а не «до 01.12.2026».
Только конфиги, файлы и рефлексия: где мода нет, пункт скажет «не наложен», компиляция не зависит от необязательных классов.
Переводы строк скрипта сохраняются. Патч проверяется наложением на копию дерева байт в байт.

    python -X utf8 tools/l2hub_selftest_world.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_selftest_world.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0024 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import patch_layers

ROOT = Path(__file__).resolve().parents[1]
NAME = "0024-l2hub-selftest-world.patch"
SCRIPT = "dist/game/data/scripts/custom/L2HubSelfTest/L2HubSelfTest.java"
MARK = "L2Hub 0024"

PROBES_ANCHOR = '\t\tprobe(checks, "Сезонные события", L2HubSelfTest::seasonalEvents);\n'
PROBES = (PROBES_ANCHOR
          + '\t\tprobe(checks, "Суперчемпионы", L2HubSelfTest::superChampions); // L2Hub 0024: моды живого мира\n'
          + '\t\tprobe(checks, "Мир сейчас: .world", L2HubSelfTest::worldInfo);\n'
          + '\t\tprobe(checks, "Нашествие эльпи", L2HubSelfTest::elpies);\n'
          + '\t\tprobe(checks, "Летопись мира", L2HubSelfTest::chronicle);\n')
DATE_ANCHOR = 'active.add(script.getName() + " до " + new SimpleDateFormat("dd.MM.yyyy").format(event.getEndDate()));\n'
DATE = ('active.add(script.getName() + " по " + new SimpleDateFormat("dd.MM.yyyy").format(new java.util.Date(event.getEndDate().getTime() - 1))); '
        '// L2Hub 0024: конец окна — начало следующего дня, пишем последний\n')
METHODS_ANCHOR = "\tprivate static String onOff(boolean value)\n"
METHODS = """	// ---------- L2Hub 0024: моды живого мира — суперчемпион, «Мир сейчас», эльпи, летопись ----------

	private static Properties customIni(String name) throws Exception
	{
		final Properties properties = new Properties();
		final Path path = Paths.get("./config/Custom/" + name);
		if (Files.isRegularFile(path))
		{
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
			{
				properties.load(reader);
			}
		}
		return properties;
	}

	private static boolean isTrue(Properties properties, String key, String defaultValue)
	{
		return "true".equalsIgnoreCase(properties.getProperty(key, defaultValue).trim());
	}

	private static Check superChampions() throws Exception
	{
		final Properties config = customIni("ChampionMonsters.ini");
		if (config.getProperty("SuperChampionEnable") == null)
		{
			return new Check("", false, "нет ключей SuperChampion* в ChampionMonsters.ini — мод 0020 не наложен");
		}
		if (!isTrue(config, "ChampionEnable", "False") || !isTrue(config, "SuperChampionEnable", "False"))
		{
			return new Check("", true, "выключены (" + (isTrue(config, "ChampionEnable", "False") ? "SuperChampionEnable" : "ChampionEnable") + " = False)");
		}
		final Object alive = Class.forName("org.l2jmobius.gameserver.entity.actor.SuperChampion").getMethod("alive").invoke(null);
		return new Check("", true, "вкл, шанс " + config.getProperty("SuperChampionFrequency", "?").trim() + " %, живых в мире " + ((alive instanceof java.util.Collection<?> list) ? list.size() : "?"));
	}

	private static Check worldInfo() throws Exception
	{
		if (VoicedCommandHandler.getInstance().getHandler("world") == null)
		{
			return new Check("", false, "нет команды .world — мод 0022 не наложен или скрипт L2HubWorldInfo не загрузился");
		}
		final Path file = Paths.get("./log/L2HubWorld.json");
		if (!Files.isRegularFile(file))
		{
			return new Check("", false, ".world есть, но log/L2HubWorld.json не записан");
		}
		final long age = (System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()) / 1000;
		return new Check("", age < 180, ".world есть, L2HubWorld.json обновлён " + age + " с назад");
	}

	private static Check elpies() throws Exception
	{
		if (ScriptManager.getInstance().getScript("Elpies") == null)
		{
			return new Check("", false, "нет скрипта Elpies");
		}
		final Properties config = customIni("L2HubEvents.ini");
		if (config.getProperty("Elpies") == null)
		{
			return new Check("", true, "расписания L2Hub нет (ключей Elpies* в L2HubEvents.ini нет) — только командой GM");
		}
		if (!isTrue(config, "Elpies", "False"))
		{
			return new Check("", true, "выключено (Elpies = False в L2HubEvents.ini)");
		}
		final String pattern = config.getProperty("ElpiesSchedule", "").trim();
		if (pattern.isEmpty())
		{
			return new Check("", true, "вкл, расписания нет — только командой GM");
		}
		final long next = System.currentTimeMillis() + new org.l2jmobius.commons.time.SchedulingPattern(pattern).getDelayToNextFromNow();
		final String when = new SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date(((next + 30000) / 60000) * 60000));
		return new Check("", true, "вкл, " + pattern + ", следующий старт " + when + (isTrue(config, "ElpiesOnlyWithPlayers", "True") ? ", только при живом игроке" : ""));
	}

	private static Check chronicle() throws Exception
	{
		if (!Files.isRegularFile(Paths.get("./data/scripts/custom/L2HubChronicle/L2HubChronicle.java")))
		{
			return new Check("", false, "нет скрипта L2HubChronicle — мод 0023 не наложен");
		}
		final Path file = Paths.get("./log/L2HubChronicle.json");
		if (!Files.isRegularFile(file))
		{
			final boolean writable = Files.isWritable(file.toAbsolutePath().getParent());
			return new Check("", writable, "записей пока нет, папка log " + (writable ? "доступна для записи" : "недоступна для записи!"));
		}
		long entries = 0;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
		{
			if (line.startsWith("{\\"time\\":"))
			{
				entries++;
			}
		}
		final boolean writable = Files.isWritable(file);
		return new Check("", writable, "log/L2HubChronicle.json " + (writable ? "доступен для записи" : "только для чтения!") + ", записей " + entries);
	}

"""


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def originals(cid: str, src: Path) -> tuple[dict[str, bytes | None], list[Path], dict[str, bytes | None]]:
    if not (src / SCRIPT).is_file():
        sys.exit(f"нет {SCRIPT} — сначала самопроверка 0015")
    current = (src / SCRIPT).read_bytes()
    if MARK.encode() not in current:
        return {SCRIPT: current}, [], {}
    if not (ROOT / "patches" / cid / NAME).is_file():
        sys.exit(f"в {SCRIPT} уже есть «{MARK}», а patches/{cid}/{NAME} нет — нечем снять")
    peeled, upper = patch_layers.peel(cid, src, NAME, [SCRIPT])
    print(f"{cid}: прежний {NAME} снят с копии дерева" + (f" (и поздние: {', '.join(p.name for p in upper)})" if upper else "")
          + ", патч собирается заново")
    return {SCRIPT: peeled[SCRIPT]}, upper, peeled


def changes(base: dict[str, bytes | None]) -> dict[str, tuple[bytes | None, bytes]]:
    old = base[SCRIPT]
    if MARK.encode() in old:
        sys.exit(f"{SCRIPT}: «{MARK}» есть и без патча — правили руками?")
    nl = "\r\n" if b"\r\n" in old else "\n"
    new = old
    for anchor, repl in ((PROBES_ANCHOR, PROBES), (DATE_ANCHOR, DATE), (METHODS_ANCHOR, METHODS + METHODS_ANCHOR)):
        a, r = anchor.replace("\n", nl).encode(), repl.replace("\n", nl).encode()
        if new.count(a) != 1:
            sys.exit(f"{SCRIPT}: якорь «{anchor.strip()[:60]}» найден {new.count(a)} раз — самопроверка у хроники другая")
        new = new.replace(a, r)
    return {SCRIPT: (old, new)}


def build_patch(plan: dict) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                (t / side / rel).write_bytes(data)
        patch = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=",
                                "--dst-prefix=", "a", "b"], cwd=t, capture_output=True).stdout
        chk = t / "chk"
        shutil.copytree(t / "a", chk)
        subprocess.run(["git", "init", "-q"], cwd=chk, check=True)
        (t / "p.patch").write_bytes(patch)
        subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn", str(t / "p.patch")], cwd=chk, check=True)
        bad = [rel for rel, (_, new) in plan.items() if (chk / rel).read_bytes() != new]
    return patch, bad


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = tree(cid)
    base, upper, peeled = originals(cid, src)
    plan = changes(base)
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack({rel: new for rel, (_, new) in plan.items()}, upper, peeled)
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт")
    if write:
        (ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
