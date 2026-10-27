"""Патч «Мастер дворянства у гейткиперов» для хроники Mobius: patches/<id>/0034-noblesse-master.patch.

Дворянство у Mobius — цепочка квестов Possessor of a Precious Soul: саб-класс (Fate's Whisper и Mimir's Elixir: четыре РБ и
кровь Баюма) и Barakiel 80-го уровня пати. Одиночке оно закрыто, а без него закрыта и олимпиада малого мира (0025). Решение
автора 2026-10-26 (docs/PLAN.md, «Решения»): мастер дворянства Mobius (custom/NoblessMaster, NPC 1003000 Kadmos) у
гейткиперов, с 76-го уровня за 10 000 000 адены; выключатель TownNoblesse (лаунчер, «Настройки сервера», «Мир»; обычный
режим, «PvP-крафт» и «PvP» включают, «Хардкор» выключает). Саб без квестов в обычном режиме — не надо (автор).
  game/config/Custom/NoblessMaster.ini — Enabled True, LevelRequirement 76, ItemCount 10000000 (адена), над строкой — что
    было у Mobius;
  game/config/Custom/L2HubTown.ini — ключ TownNoblesse (True);
  custom/L2HubTown/L2HubTown.java — при TownNoblesse ставит мастера у каждого гейткипера с баффером: по другую сторону
    баффера от GM-шопа (за шопом — менеджер L2 Day), потом по бокам и наискосок; точка по геодате, городские фейки не в
    счёт; нет места нигде — город пропускается с предупреждением в логе. Спавна в файле нет: выключили — мастера нет;
  custom/NoblessMaster/1003000*.htm — уровень и цена в окне — из тех же LEVEL и PRICE, что в ini; русские окна —
    data/lang/ru/data/scripts/custom/NoblessMaster/ (многоязычность 0030).

    python -X utf8 tools/l2hub_noblesse_master.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_noblesse_master.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0034 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import l2hub_olympiad_small as small
import patch_layers

NAME = "0034-noblesse-master.patch"
LEVEL, PRICE = 76, 10_000_000
NOBLESS_INI = "dist/game/config/Custom/NoblessMaster.ini"
TOWN_INI = "dist/game/config/Custom/L2HubTown.ini"
TOWN = "dist/game/data/scripts/custom/L2HubTown/L2HubTown.java"
HTML = "dist/game/data/scripts/custom/NoblessMaster/"
RU = "dist/game/data/lang/ru/data/scripts/custom/NoblessMaster/"
VALUES = {  # ключ NoblessMaster.ini → (значение, почему)
    "Enabled": ("True", "мастер дворянства у гейткиперов, ставит его L2HubTown при TownNoblesse (решение автора 2026-10-26)"),
    "LevelRequirement": (str(LEVEL), "с 76-го уровня (решение автора 2026-10-26)"),
    "ItemCount": (str(PRICE), "за 10 000 000 адены (решение автора 2026-10-26)"),
}
PRICE_EN = f"{PRICE:,}"
PRICE_RU = f"{PRICE:,}".replace(",", " ")
TOWN_KEY = [
    "# L2Hub (малый мир): мастер дворянства (NoblessMaster Mobius, NPC 1003000) у гейткиперов — по другую сторону баффера",
    f"# от GM-шопа; дворянство с {LEVEL}-го уровня за {PRICE_RU} адены (config/Custom/NoblessMaster.ini). Решение автора 2026-10-26.",
    "# Default: True",
    "TownNoblesse = True",
]
RU_HTML = {
    "1003000.htm": ["<html><body>", "Мастер дворянства:<br>", "<br>", f"С {LEVEL}-го уровня я могу сделать тебя дворянином<br1>",
                    f"за {PRICE_RU} адены.<br>", "<br>", "Дворянину открыта олимпиада, а сабкласс берётся без квестов.<br>", "<br>",
                    "<center>", "<a action=\"bypass -h Script NoblessMaster noblesse\">Стать дворянином</a><br>", "<br>", "</center>",
                    "</body></html>"],
    "1003000-1.htm": ["<html><body>", "Мастер дворянства:<br>", "Поздравляю!<br1>", "Теперь ты дворянин.<br>", "</body></html>"],
    "1003000-2.htm": ["<html><body>", "Мастер дворянства:<br>", f"Нужен хотя бы {LEVEL}-й уровень!", "</body></html>"],
    "1003000-3.htm": ["<html><body>", "Мастер дворянства:<br>", "Ты уже дворянин.", "</body></html>"],
    "1003000-4.htm": ["<html><body>", "Мастер дворянства:<br>", "Погоди-ка...<br1>", f"А где плата? Нужно {PRICE_RU} адены.", "</body></html>"],
}

SPAWN = [
    "	/**",
    "	 * Мастер дворянства (NoblessMaster Mobius, 1003000) — у каждого гейткипера рядом с баффером. Сначала по другую сторону",
    "	 * баффера от GM-шопа (за шопом стоит менеджер L2 Day), потом по бокам и наискосок от шопа: точка по геодате — перепад не",
    "	 * больше 32, от баффера дойти можно, рядом нет другого NPC (городские фейки бродят и не в счёт). Нет места нигде — город",
    "	 * пропускается с предупреждением, сколько точек отбито и чем.",
    "	 * @return сколько поставлено",
    "	 */",
    "	private static int spawnNoblesse()",
    "	{",
    "		int placed = 0;",
    "		for (Spawn bufferSpawn : new ArrayList<>(SpawnTable.getInstance().getSpawns(TOWN_BUFFER)))",
    "		{",
    "			final Npc buffer = bufferSpawn.getLastSpawn();",
    "			if (buffer == null)",
    "			{",
    "				continue;",
    "			}",
    "			Npc shop = null;",
    "			for (Spawn shopSpawn : SpawnTable.getInstance().getSpawns(TOWN_SHOP))",
    "			{",
    "				final Npc candidate = shopSpawn.getLastSpawn();",
    "				if ((candidate != null) && (buffer.calculateDistance2D(candidate) < 400) && ((shop == null) || (buffer.calculateDistance2D(candidate) < buffer.calculateDistance2D(shop))))",
    "				{",
    "					shop = candidate;",
    "				}",
    "			}",
    "			if ((shop == null) || (buffer.calculateDistance2D(shop) < 1))",
    "			{",
    "				LOGGER.warning(\"L2HubTown: у баффера \" + buffer.getX() + \", \" + buffer.getY() + \" нет GM-шопа рядом — мастер дворянства не поставлен.\");",
    "				continue;",
    "			}",
    "			final double gap = buffer.calculateDistance2D(shop);",
    "			final double ux = (buffer.getX() - shop.getX()) / gap;",
    "			final double uy = (buffer.getY() - shop.getY()) / gap;",
    "			// {вдоль «от шопа», поперёк, во сколько расстояний баффер—шоп}: от шопа, по бокам, наискосок от шопа",
    "			final double[][] tries = { { 1, 0, 1 }, { 1, 0, 1.5 }, { 1, 0, 2 }, { 0, 1, 1 }, { 0, -1, 1 }, { 0, 1, 1.5 }, { 0, -1, 1.5 }, { 0.7071, 0.7071, 1 }, { 0.7071, -0.7071, 1 }, { 0.7071, 0.7071, 1.5 }, { 0.7071, -0.7071, 1.5 } };",
    "			int steep = 0;",
    "			int wall = 0;",
    "			int busy = 0;",
    "			boolean done = false;",
    "			for (double[] t : tries)",
    "			{",
    "				final int x = buffer.getX() + (int) Math.round(((t[0] * ux) - (t[1] * uy)) * gap * t[2]);",
    "				final int y = buffer.getY() + (int) Math.round(((t[0] * uy) + (t[1] * ux)) * gap * t[2]);",
    "				final int z = GeoEngine.getInstance().getHeight(x, y, buffer.getZ());",
    "				if (Math.abs(z - buffer.getZ()) > 32)",
    "				{",
    "					steep++;",
    "					continue;",
    "				}",
    "				if (!GeoEngine.getInstance().canMoveToTarget(buffer.getX(), buffer.getY(), buffer.getZ(), x, y, z, buffer.getInstanceId()))",
    "				{",
    "					wall++;",
    "					continue;",
    "				}",
    "				if (!World.getVisibleObjectsInRange(buffer, Npc.class, 600, npc -> !npc.isFakePlayer() && (npc.calculateDistance2D(x, y, z) < NOBLESSE_GAP)).isEmpty())",
    "				{",
    "					busy++;",
    "					continue;",
    "				}",
    "				try",
    "				{",
    "					final Spawn spawn = new Spawn(NOBLESSE_MASTER);",
    "					spawn.setXYZ(x, y, z);",
    "					spawn.setHeading(buffer.getHeading());",
    "					spawn.setAmount(1);",
    "					spawn.setRespawnDelay(60);",
    "					SpawnTable.getInstance().addSpawn(spawn);",
    "					spawn.init();",
    "					placed++;",
    "				}",
    "				catch (Exception e)",
    "				{",
    "					LOGGER.warning(\"L2HubTown: мастер дворянства не поставлен у \" + x + \", \" + y + \": \" + e.getMessage());",
    "				}",
    "				done = true;",
    "				break;",
    "			}",
    "			if (!done)",
    "			{",
    "				LOGGER.warning(\"L2HubTown: мастеру дворянства нет места у баффера \" + buffer.getX() + \", \" + buffer.getY() + \": из \" + tries.length + \" точек перепад высоты у \" + steep + \", не дойти — \" + wall + \", занято — \" + busy + \".\");",
    "			}",
    "		}",
    "		return placed;",
    "	}",
    "	",
]
# Правки L2HubTown.java: (якорь — строка целиком, без отступа; что вставить после неё или чем заменить)
TOWN_AFTER = {
    "import org.l2jmobius.gameserver.data.SpawnTable;": ["import org.l2jmobius.gameserver.entity.World;"],
    "import org.l2jmobius.gameserver.entity.spawns.Spawn;": ["import org.l2jmobius.gameserver.geoengine.GeoEngine;"],
    "private static final int TOWN_SHOP = 50010;": ["	private static final int NOBLESSE_MASTER = 1003000;", "	private static final int NOBLESSE_GAP = 40;"],
    "private final boolean _shop;": ["	private final boolean _noblesse;"],
    "_shop = shop;": ["		_noblesse = noblesse;"],
    "final boolean shop = isOn(\"TownGmShop\");": ["		final boolean noblesse = isOn(\"TownNoblesse\");"],
}
TOWN_REPLACE = {
    "private L2HubTown(boolean buffer, boolean shop)": "private L2HubTown(boolean buffer, boolean shop, boolean noblesse)",
    "if (!buffer || !shop)": "if (!buffer || !shop || noblesse)",
    "new L2HubTown(buffer, shop);": "new L2HubTown(buffer, shop, noblesse);",
    "* @param key TownBuffer или TownGmShop": "* @param key TownBuffer, TownGmShop или TownNoblesse",
}
RUN_ANCHOR = "if (!_buffer)"  # перед снятием баффера: мастер встаёт от баффера и шопа, их точки нужны до remove
RUN_BLOCK = [
    "		if (_noblesse)",
    "		{",
    "			LOGGER.info(\"L2HubTown: TownNoblesse = True — мастер дворянства у гейткиперов: \" + spawnNoblesse() + \" NPC.\");",
    "		}",
]
REMOVE_ANCHOR = "private static int remove(int npcId)"


def lines_of(data: bytes) -> tuple[str, list[str]]:
    nl = "\r\n" if b"\r\n" in data else "\n"
    return nl, data.decode("utf-8").split(nl)


def edit_town(data: bytes) -> bytes:
    nl, lines = lines_of(data)
    for anchor in [*TOWN_AFTER, *TOWN_REPLACE, RUN_ANCHOR, REMOVE_ANCHOR]:
        if sum(l.strip() == anchor for l in lines) != 1:
            sys.exit(f"в {TOWN} нет ровно одной строки «{anchor}» — другой L2HubTown")
    out = []
    for line in lines:
        s = line.strip()
        indent = line[:len(line) - len(s)]
        if s == RUN_ANCHOR:
            out += RUN_BLOCK
        if s == REMOVE_ANCHOR:
            out += SPAWN[:-1] + ["\t"]
        out.append(indent + TOWN_REPLACE[s] if s in TOWN_REPLACE else line)
        out += TOWN_AFTER.get(s, [])
    return nl.join(out).encode("utf-8")


def edit_town_ini(data: bytes) -> bytes:
    nl, lines = lines_of(data)
    if lines and lines[-1] == "":
        lines.pop()
    return nl.join(lines + TOWN_KEY + [""]).encode("utf-8")


def edit_html(name: str, data: bytes) -> bytes:
    nl, lines = lines_of(data)
    text = nl.join(lines)
    if name == "1003000.htm":
        old = f"If you are at least level 80,<br>{nl}I can promote you to Noblesse.<br>"
        if text.count(old) != 1:
            sys.exit(f"{HTML}{name}: другой текст окна")
        text = text.replace(old, f"If you are at least level {LEVEL},<br>{nl}I can promote you to Noblesse<br1>{nl}for {PRICE_EN} adena.<br>")
    elif name == "1003000-2.htm":
        if text.count("at least level 80!") != 1:
            sys.exit(f"{HTML}{name}: другой текст окна")
        text = text.replace("at least level 80!", f"at least level {LEVEL}!")
    elif name == "1003000-4.htm":
        old = "Where is my payment?"
        if text.count(old) != 1:
            sys.exit(f"{HTML}{name}: другой текст окна")
        text = text.replace(old, f"Where is my payment? It is {PRICE_EN} adena.")
    return text.encode("utf-8")


def build(plan: dict[str, tuple[bytes | None, bytes]]) -> tuple[bytes, list[str]]:
    """Как small.build_patch, но с новыми файлами (old = None): у них нет стороны «a»."""
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        (t / "a").mkdir()
        (t / "b").mkdir()
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                if data is not None:
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
    src = small.tree(cid)
    htmls = [HTML + n for n in RU_HTML]
    files = [NOBLESS_INI, TOWN_INI, TOWN] + htmls
    for rel in files:
        if not (src / rel).is_file():
            sys.exit(f"{cid}: нет {rel} — нет мастера дворянства Mobius или городских NPC L2Hub")
    ru = [RU + n for n in RU_HTML]
    current: dict[str, bytes | None] = {rel: (src / rel).read_bytes() for rel in files}
    current.update({rel: (src / rel).read_bytes() if (src / rel).is_file() else None for rel in ru})
    upper, peeled = [], {}
    if b"TownNoblesse" in current[TOWN_INI]:
        if not (small.ROOT / "patches" / cid / NAME).is_file():
            sys.exit(f"в дереве уже есть TownNoblesse, а patches/{cid}/{NAME} нет — нечем снять")
        peeled, upper = patch_layers.peel(cid, src, NAME, files + ru)
        current = {rel: peeled.get(rel) for rel in files + ru}
        print(f"{cid}: прежний {NAME} снят с копии дерева, патч собирается заново")
    if any(current[rel] is not None for rel in ru):
        sys.exit(f"{cid}: русские окна мастера уже есть ({RU}) — не наши")
    nl_nobless, _ = lines_of(current[NOBLESS_INI])
    nobless, done = small.edit(current[NOBLESS_INI], VALUES)
    if len(done) != len(VALUES):
        sys.exit(f"{cid}: в {NOBLESS_INI} не все ключи или значения уже такие: {done}")
    new = {NOBLESS_INI: nobless, TOWN_INI: edit_town_ini(current[TOWN_INI]), TOWN: edit_town(current[TOWN])}
    for rel in htmls:
        changed = edit_html(rel.rsplit("/", 1)[1], current[rel])
        if changed != current[rel]:
            new[rel] = changed
    nl_ru = "\n"  # русские окна многоязычности (0030) — с LF
    for name, body in RU_HTML.items():
        new[RU + name] = (nl_ru.join(body) + nl_ru).encode("utf-8")
    plan = {rel: (current[rel], data) for rel, data in new.items()}
    patch, bad = build(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack(new, upper, peeled) if upper else new
    print(f"{cid}: {', '.join(done)}; TownNoblesse; окна — уровень {LEVEL}, цена {PRICE_EN}; файлов {len(new)}; наложение на копию — байт в байт")
    if write:
        (small.ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
