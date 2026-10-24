"""Патч «Суперчемпион» для хроники Mobius: patches/<id>/0020-super-champion.patch.

Третий уровень чемпионов над красными и синими (патч 0002, record ChampionMonstersConfig.Tier) — мини-РБ: ключи SuperChampion*
в config/Custom/ChampionMonsters.ini, эффекты над моделью, объявление при появлении, список живых для охотников. Логика — в
общем классе entity/actor/SuperChampion.java (эталон — tools/l2hub_super_champion/), в файлы Mobius врезаны только вызовы:
  - ChampionMonstersConfig: CHAMPION_SUPER = loadTier(…, "SuperChampion", …), SUPER_CHAMPION_ENABLE, _EFFECTS, _ANNOUNCE, _ANNOUNCE_TEXT;
  - Attackable.setChampion: SuperChampion.onChampion(this, champion) — эффекты;
  - Spawn (бросок шанса чемпиона): сначала суперчемпион, затем красный, затем синий; суперчемпиону — SuperChampion.announce;
  - ключи (tools/l2hub_super_champion/ini_block.txt) дописываются в конец ChampionMonsters.ini.
Якоря — код патча 0002 у Interlude, High Five и Gracia Epilogue. У Classic и Essence шанс чемпиона бросается не в Spawn —
генератор скажет, какой якорь не найден; там врезку в место броска перенести руками (docs/INTERLUDE.md, «Суперчемпион»).
Переводы строк каждого файла сохраняются. Патч проверяется наложением на копию исходного дерева байт в байт.

    python -X utf8 tools/l2hub_super_champion.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_super_champion.py <id> --write   — собрать патч в patches/<id>/ и наложить изменения на дерево
Наложен прежний patches/<id>/0020 — он снимается с копии дерева, и патч собирается заново (обновление после правки эталона);
поздние патчи на те же файлы снимаются тоже и ложатся обратно (tools/patch_layers.py).
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import patch_layers

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent / "l2hub_super_champion"
NAME = "0020-super-champion.patch"
NEW = "java/org/l2jmobius/gameserver/entity/actor/SuperChampion.java"
INI = "dist/game/config/Custom/ChampionMonsters.ini"
CONFIG = "java/org/l2jmobius/gameserver/config/custom/ChampionMonstersConfig.java"
ATTACKABLE = "java/org/l2jmobius/gameserver/entity/actor/Attackable.java"
SPAWN = "java/org/l2jmobius/gameserver/entity/spawns/Spawn.java"
FILES = [NEW, INI, CONFIG, ATTACKABLE, SPAWN]

# (файл, якорь, замена) — текст с LF; в CRLF-файлах переводы строк подставляются по файлу. Каждый якорь — ровно один раз.
EDITS = [
    (CONFIG, "import java.util.List;\n", "import java.util.List;\nimport java.util.logging.Logger;\n"),
    (CONFIG, "import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;\n",
     "import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;\nimport org.l2jmobius.gameserver.mechanics.skill.AbnormalVisualEffect;\n"),
    (CONFIG, '	private static final String CHAMPION_MONSTERS_CONFIG_FILE = "./config/Custom/ChampionMonsters.ini";\n',
     '	private static final String CHAMPION_MONSTERS_CONFIG_FILE = "./config/Custom/ChampionMonsters.ini";\n'
     "	private static final Logger LOGGER = Logger.getLogger(ChampionMonstersConfig.class.getName());\n"),
    (CONFIG, "	public static Tier CHAMPION_BLUE;\n",
     "	public static Tier CHAMPION_BLUE;\n"
     "	// L2Hub: суперчемпион — третий уровень (ключи SuperChampion*), эффекты и объявление — entity/actor/SuperChampion.\n"
     "	public static Tier CHAMPION_SUPER;\n"
     "	public static boolean SUPER_CHAMPION_ENABLE;\n"
     "	public static AbnormalVisualEffect[] SUPER_CHAMPION_EFFECTS = new AbnormalVisualEffect[0];\n"
     "	public static String SUPER_CHAMPION_ANNOUNCE;\n"
     "	public static String SUPER_CHAMPION_ANNOUNCE_TEXT;\n"),
    (CONFIG, '		CHAMPION_BLUE = loadTier(config, "ChampionBlue", Team.BLUE, 2, 2, 2, "");\n',
     '		CHAMPION_BLUE = loadTier(config, "ChampionBlue", Team.BLUE, 2, 2, 2, "");\n'
     '		SUPER_CHAMPION_ENABLE = config.getBoolean("SuperChampionEnable", false);\n'
     '		CHAMPION_SUPER = loadTier(config, "SuperChampion", Team.RED, 20, 20, 10, "");\n'
     '		SUPER_CHAMPION_EFFECTS = superChampionEffects(config.getString("SuperChampionEffects", "BIG_BODY,ULTIMATE_DEFENCE,MAGIC_SQUARE"));\n'
     '		SUPER_CHAMPION_ANNOUNCE = config.getString("SuperChampionAnnounce", "Region");\n'
     '		SUPER_CHAMPION_ANNOUNCE_TEXT = config.getString("SuperChampionAnnounceText", "Legendary Champion %name% (%level%) has appeared near %town%!");\n'),
    (CONFIG, "List.copyOf(items));\n\t}\n}\n",
     "List.copyOf(items));\n\t}\n\n"
     "\t/**\n"
     "\t * L2Hub: эффекты суперчемпиона — имена AbnormalVisualEffect через запятую; неизвестные пропускаются с предупреждением.\n"
     "\t */\n"
     "\tprivate static AbnormalVisualEffect[] superChampionEffects(String names)\n"
     "\t{\n"
     "\t\tfinal List<AbnormalVisualEffect> effects = new ArrayList<>();\n"
     "\t\tfor (String name : names.split(\",\"))\n"
     "\t\t{\n"
     "\t\t\tif (name.isBlank())\n"
     "\t\t\t{\n"
     "\t\t\t\tcontinue;\n"
     "\t\t\t}\n"
     "\t\t\ttry\n"
     "\t\t\t{\n"
     "\t\t\t\teffects.add(AbnormalVisualEffect.valueOf(name.trim()));\n"
     "\t\t\t}\n"
     "\t\t\tcatch (IllegalArgumentException e)\n"
     "\t\t\t{\n"
     "\t\t\t\tLOGGER.warning(\"ChampionMonsters.ini: SuperChampionEffects — нет эффекта \" + name.trim() + \".\");\n"
     "\t\t\t}\n"
     "\t\t}\n"
     "\t\treturn effects.toArray(new AbnormalVisualEffect[0]);\n"
     "\t}\n"
     "}\n"),
    (ATTACKABLE, "\t\t_champion = champion;\n",
     "\t\t_champion = champion;\n\t\tSuperChampion.onChampion(this, champion); // L2Hub: суперчемпион — эффекты над моделью\n"),
    (SPAWN, "import org.l2jmobius.gameserver.entity.actor.Npc;\n",
     "import org.l2jmobius.gameserver.entity.actor.Npc;\nimport org.l2jmobius.gameserver.entity.actor.SuperChampion;\n"),
    (SPAWN, "\t\t// Set champion on next spawn: сначала шанс красного, затем синего.\n",
     "\t\t// Set champion on next spawn: сначала шанс суперчемпиона (L2Hub), затем красного, затем синего.\n"),
    (SPAWN,
     "\t\t\tfinal double roll = Rnd.nextDouble() * 100;\n"
     "\t\t\tif (roll < ChampionMonstersConfig.CHAMPION_RED.frequency())\n"
     "\t\t\t{\n"
     "\t\t\t\tnpc.asAttackable().setChampion(ChampionMonstersConfig.CHAMPION_RED);\n"
     "\t\t\t}\n"
     "\t\t\telse if (roll < (ChampionMonstersConfig.CHAMPION_RED.frequency() + ChampionMonstersConfig.CHAMPION_BLUE.frequency()))\n",
     "\t\t\tfinal double roll = Rnd.nextDouble() * 100;\n"
     "\t\t\tfinal double superFrequency = ChampionMonstersConfig.SUPER_CHAMPION_ENABLE ? ChampionMonstersConfig.CHAMPION_SUPER.frequency() : 0;\n"
     "\t\t\tif (roll < superFrequency)\n"
     "\t\t\t{\n"
     "\t\t\t\tnpc.asAttackable().setChampion(ChampionMonstersConfig.CHAMPION_SUPER);\n"
     "\t\t\t\tSuperChampion.announce(npc.asAttackable());\n"
     "\t\t\t}\n"
     "\t\t\telse if (roll < (superFrequency + ChampionMonstersConfig.CHAMPION_RED.frequency()))\n"
     "\t\t\t{\n"
     "\t\t\t\tnpc.asAttackable().setChampion(ChampionMonstersConfig.CHAMPION_RED);\n"
     "\t\t\t}\n"
     "\t\t\telse if (roll < (superFrequency + ChampionMonstersConfig.CHAMPION_RED.frequency() + ChampionMonstersConfig.CHAMPION_BLUE.frequency()))\n"),
]


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def eol(data: bytes) -> bytes:
    return b"\r\n" if data.count(b"\r\n") > data.count(b"\n") // 2 else b"\n"


def originals(cid: str, src: Path) -> tuple[dict[str, bytes | None], list[Path], dict[str, bytes | None]]:
    """Файлы до патча: из дерева или, если прежний patches/<id>/0020 уже наложен, — копия дерева со снятыми им и поздними
    патчами на те же файлы (patch_layers.peel). Возвращает (файлы до патча, поздние патчи, снятое состояние для restack)."""
    if not (src / NEW).is_file():
        return {NEW: None} | {rel: (src / rel).read_bytes() for rel in FILES[1:]}, [], {}
    if not (ROOT / "patches" / cid / NAME).is_file():
        sys.exit(f"{NEW} уже есть в дереве, а patches/{cid}/{NAME} нет — нечем снять")
    peeled, upper = patch_layers.peel(cid, src, NAME, FILES)
    print(f"{cid}: прежний {NAME} снят с копии дерева" + (f" (и поздние: {', '.join(p.name for p in upper)})" if upper else "")
          + ", патч собирается заново")
    return {rel: peeled[rel] for rel in FILES}, upper, peeled


# Эффекты суперчемпиона хроники, если не как в ini_block.txt: (значение SuperChampionEffects, комментарий над ним).
# Особые эффекты (тип 1 в AbnormalVisualEffect) H5 и Epilogue шлют клиенту в пакете NPC; Невит — только в клиенте H5.
EFFECTS_BY_ID = {
    "ct26_highfive": ("BIG_BODY,ULTIMATE_DEFENCE,NAVIT_ADVENT,MAGIC_SQUARE",
                      "# High Five: NAVIT_ADVENT — золотое сияние Благословения Невита (особый эффект, клиент H5 его рисует)."),
}


def changes(base: dict[str, bytes | None], cid: str) -> dict[str, tuple[bytes | None, bytes]]:
    """{путь: (было или None, стало)} по файлам до патча."""
    if base[NEW] is not None:
        sys.exit(f"{NEW} есть и без патча — чужой файл?")
    new = {rel: base[rel] for rel in FILES[1:]}
    missing = []
    for rel, anchor, repl in EDITS:
        nl = eol(new[rel])
        a, b = anchor.replace("\n", "\r\n" if nl == b"\r\n" else "\n").encode(), repl.replace("\n", nl.decode()).encode()
        if new[rel].count(a) != 1:
            missing.append(f"{rel}: якорь «{anchor.strip().splitlines()[0][:70]}» найден {new[rel].count(a)} раз")
            continue
        new[rel] = new[rel].replace(a, b)
    if missing:
        sys.exit("врезки не легли (у этой хроники код чемпионов другой — перенести руками):\n  " + "\n  ".join(missing))
    if b"SuperChampion" in base[INI]:
        sys.exit(f"{INI}: ключи SuperChampion уже есть")
    block = (HERE / "ini_block.txt").read_bytes()
    if cid in EFFECTS_BY_ID:
        value, note = EFFECTS_BY_ID[cid]
        key = b"SuperChampionEffects = BIG_BODY,ULTIMATE_DEFENCE,MAGIC_SQUARE"
        if block.count(key) != 1:
            sys.exit("ini_block.txt: строка SuperChampionEffects не та — поправь EFFECTS_BY_ID")
        block = block.replace(key, f"{note}\nSuperChampionEffects = {value}".encode())
    nl = eol(base[INI])
    block = block.replace(b"\r\n", b"\n").replace(b"\n", nl)
    new[INI] = base[INI].rstrip(b"\r\n") + nl + block
    template = (HERE / "SuperChampion.java").read_bytes().replace(b"\r\n", b"\n")
    out: dict[str, tuple[bytes | None, bytes]] = {NEW: (None, template.replace(b"\n", eol(base[ATTACKABLE])))}
    out |= {rel: (base[rel], new[rel]) for rel in FILES[1:]}
    return out


def build_patch(plan: dict) -> tuple[bytes, list[str]]:
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                if data is not None:
                    (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                    (t / side / rel).write_bytes(data)
        r = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=",
                            "--dst-prefix=", "a", "b"], cwd=t, capture_output=True)
        patch = r.stdout
        for rel, (old, _) in plan.items():
            if old is None:  # новый файл: git пишет «b/… b/…», в шапке нужна «a/… b/…»
                patch = patch.replace(f"diff --git b/{rel} b/{rel}".encode(), f"diff --git a/{rel} b/{rel}".encode())
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
    plan = changes(base, cid)
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack({rel: new for rel, (_, new) in plan.items()}, upper, peeled)  # поздние патчи — обратно
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт"
          + (f"; поздние патчи ложатся поверх: {', '.join(p.name for p in upper)}" if upper else ""))
    if write:
        (ROOT / "patches" / cid / NAME).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{NAME} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
