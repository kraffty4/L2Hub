"""Патч «Окна L2Hub по языку» для хроники Mobius: patches/<id>/0030-l2hub-lang-html.patch.

Наши русские окна-html — переводы окон Mobius (окно моба по Shift+клику из 0014, диалоги сезонных событий из 0016) —
лежали на месте английских, и при L2HubLanguage = en игрок всё равно видел русские. Здесь — штатная многоязычность Mobius
(config/Custom/MultilingualSupport.ini, data/lang/<язык>/…):
  - русская версия каждого такого окна переезжает в dist/game/data/lang/ru/<путь от game>, на место возвращается английский
    оригинал Mobius (каким он был до патча, который сделал окно русским);
  - MultilingualSupport.ini: MultiLangEnable True, в MultiLangAllowed добавлен ru;
  - MultilingualSupportConfig.java: язык по умолчанию — L2HubLanguage из config/Custom/L2HubWorld.ini (его пишет лаунчер по
    языку окна), MultiLangDefault — запасной. У персонажей язык в базе пустой — им достаётся язык по умолчанию; .lang
    переключает свой;
  - самопроверка (L2HubSelfTest): русское окно моба ищется в data/lang/ru.
Окна доски Alt+B сюда не входят: английского оригинала у них нет, их русская версия уезжает в data/lang/ru в слое 0099
(tools/l2hub_lang.py), а без этого патча она не найдётся — он нужен каждой хроники, где есть 0099.

    python -X utf8 tools/l2hub_lang_html.py <id>           — собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_lang_html.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
Наложен прежний patches/<id>/0030 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
"""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import l2hub_olympiad_small as small
import patch_layers

NAME = "0030-l2hub-lang-html.patch"  # у хроники уже есть *-l2hub-lang-html.patch под другим номером — берётся он
MARK = "L2Hub: окна по языку"
HTML = ["dist/game/data/html/mods/NpcView/*.htm", "dist/game/data/scripts/events/*/*.htm"]
INI = "dist/game/config/Custom/MultilingualSupport.ini"
CONFIG = "java/org/l2jmobius/gameserver/config/custom/MultilingualSupportConfig.java"
SELFTEST = "dist/game/data/scripts/custom/L2HubSelfTest/L2HubSelfTest.java"
NPC_VIEW_OLD = 'Paths.get("./data/html/mods/NpcView/DropList.htm")'
NPC_VIEW_NEW = 'Paths.get("./data/lang/ru/data/html/mods/NpcView/DropList.htm") /* L2Hub: окна по языку — русское окно в data/lang/ru */'
CYR = re.compile(r"[А-Яа-яЁё]")
DEFAULT_OLD = 'MULTILANG_DEFAULT = config.getString("MultiLangDefault", "en").toLowerCase();'


def lang_path(rel: str) -> str:
    return "dist/game/data/lang/ru/" + rel[len("dist/game/"):]


def build_patch(plan: dict[str, tuple[bytes | None, bytes]]) -> tuple[bytes, list[str]]:
    """Как small.build_patch, но старое None — новый файл (окна в data/lang/ru)."""
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        for rel, (old, new) in plan.items():
            for side, data in (("a", old), ("b", new)):
                (t / side / rel).parent.mkdir(parents=True, exist_ok=True)
                if data is not None:
                    (t / side / rel).write_bytes(data)
        patch = subprocess.run(["git", "-c", "core.autocrlf=false", "diff", "--no-index", "--full-index", "--src-prefix=",
                                "--dst-prefix=", "a", "b"], cwd=t, capture_output=True).stdout
        # Новый файл --no-index пишет как «diff --git b/X b/X» — стандартный вид «a/X b/X» (его ищет patch_layers.touched).
        patch = re.sub(rb"(?m)^diff --git b/(\S+) b/\1$", rb"diff --git a/\1 b/\1", patch)
        chk = t / "chk"
        shutil.copytree(t / "a", chk)
        subprocess.run(["git", "init", "-q"], cwd=chk, check=True)
        (t / "p.patch").write_bytes(patch)
        subprocess.run(["git", "-c", "core.autocrlf=false", "apply", "--whitespace=nowarn", str(t / "p.patch")], cwd=chk, check=True)
        bad = [rel for rel, (_, new) in plan.items() if (chk / rel).read_bytes() != new]
    return patch, bad


def edit_ini(data: bytes) -> bytes:
    nl = "\r\n" if b"\r\n" in data else "\n"
    text = data.decode("utf-8")
    text, n = re.subn(r"(?m)^MultiLangEnable = \w+", f"# {MARK} (tools/l2hub_lang_html.py): наши русские окна — в data/lang/ru, язык по умолчанию —{nl}"
                      f"# L2HubLanguage из L2HubWorld.ini (MultiLangDefault ниже — запасной).{nl}MultiLangEnable = True", text)
    if n != 1:
        sys.exit(f"{INI}: нет MultiLangEnable")
    m = re.search(r"(?m)^MultiLangAllowed = (.*)$", text)
    if not m:
        sys.exit(f"{INI}: нет MultiLangAllowed")
    langs = [x.strip() for x in m.group(1).strip().split(";") if x.strip()]
    if "ru" not in langs:
        text = text[:m.start()] + "MultiLangAllowed = " + ";".join(langs + ["ru"]) + text[m.end():]
    return text.encode("utf-8")


def edit_config(data: bytes) -> bytes:
    nl = "\r\n" if b"\r\n" in data else "\n"
    lines = data.decode("utf-8").split(nl)
    at = next((i for i, l in enumerate(lines) if l.strip() == DEFAULT_OLD), None)
    if at is None:
        sys.exit(f"{CONFIG}: нет «{DEFAULT_OLD}»")
    block = [
        f"\t\t// {MARK} (tools/l2hub_lang_html.py): язык по умолчанию — L2HubLanguage (ru | en) из config/Custom/L2HubWorld.ini,",
        "\t\t// его пишет лаунчер по языку окна; MultiLangDefault — запасной.",
        "\t\tfinal String l2hubLanguage = l2hubLanguage();",
        "\t\tif (!l2hubLanguage.isEmpty())",
        "\t\t{",
        "\t\t\tMULTILANG_DEFAULT = l2hubLanguage;",
        "\t\t}",
    ]
    end = max(i for i, l in enumerate(lines) if l == "}")
    helper = [
        "\t",
        "\tprivate static String l2hubLanguage()",
        "\t{",
        "\t\tfinal java.util.Properties config = new java.util.Properties();",
        "\t\ttry (java.io.Reader reader = java.nio.file.Files.newBufferedReader(java.nio.file.Paths.get(\"./config/Custom/L2HubWorld.ini\"), java.nio.charset.StandardCharsets.UTF_8))",
        "\t\t{",
        "\t\t\tconfig.load(reader);",
        "\t\t}",
        "\t\tcatch (Exception e)",
        "\t\t{",
        "\t\t\treturn \"\";",
        "\t\t}",
        "\t\treturn config.getProperty(\"L2HubLanguage\", \"\").trim().toLowerCase();",
        "\t}",
    ]
    out = lines[:at + 1] + block + lines[at + 1:end] + helper + lines[end:]
    return nl.join(out).encode("utf-8")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    src = small.tree(cid)
    own = sorted((small.ROOT / "patches" / cid).glob("*-l2hub-lang-html.patch"))
    name = own[0].name if own else NAME
    for rel in (INI, CONFIG):
        if not (src / rel).is_file():
            sys.exit(f"{cid}: нет {rel}")
    pages = sorted({p.relative_to(src).as_posix() for g in HTML for p in src.glob(g)})
    moved = sorted({p.relative_to(src).as_posix() for p in src.glob("dist/game/data/lang/ru/**/*.htm")})
    files = [INI, CONFIG] + ([SELFTEST] if (src / SELFTEST).is_file() else []) + pages + moved
    current = {rel: (src / rel).read_bytes() if (src / rel).is_file() else None for rel in files}
    upper, peeled = [], {}
    applied = any((data is not None) and MARK.encode() in data for data in current.values())
    if applied and not (small.ROOT / "patches" / cid / name).is_file():
        sys.exit(f"в дереве уже есть «{MARK}», а patches/{cid}/{name} нет — нечем снять")
    if applied or patch_layers.later(cid, name, files):
        # Снять с копии дерева свой патч и поздние, что трогают те же файлы (у Classic — 0099 языка после 0030), и собрать заново.
        peeled, upper = patch_layers.peel(cid, src, name, files)
        current = {rel: peeled.get(rel) for rel in files}
        print(f"{cid}: сняты с копии дерева {'свой ' + name + ' и ' if applied else ''}поздние патчи: {[p.name for p in upper]}")
    # Английский оригинал окна — каким оно было до первого нашего патча, который его тронул (до него в дереве — Mobius).
    russian = [rel for rel in pages if (current.get(rel) is not None) and CYR.search(current[rel].decode("utf-8", "replace"))]
    patches = sorted((small.ROOT / "patches" / cid).glob("*.patch"))
    plan, done, originals = {}, 0, {}
    for rel in russian:
        first = next((p for p in patches if p.name != name and rel in patch_layers.touched(p)), None)
        if first is None:
            continue
        if first.name not in originals:  # снять первый патч целиком — со всеми его файлами (поздние слои над ними — тоже)
            originals[first.name] = patch_layers.peel(cid, src, first.name, sorted(patch_layers.touched(first)))[0]
        before = originals[first.name]
        original = before.get(rel)
        if (original is None) or CYR.search(original.decode("utf-8", "replace")):
            continue
        plan[rel] = (current[rel], original)
        plan[lang_path(rel)] = (current.get(lang_path(rel)), current[rel])
        done += 1
    if not done:
        sys.exit(f"{cid}: русских окон с английским оригиналом нет")
    plan[INI] = (current[INI], edit_ini(current[INI]))
    plan[CONFIG] = (current[CONFIG], edit_config(current[CONFIG]))
    if (SELFTEST in current) and current[SELFTEST] and NPC_VIEW_OLD.encode() in current[SELFTEST] and \
            any(r.startswith("dist/game/data/html/mods/NpcView/") for r in russian):
        plan[SELFTEST] = (current[SELFTEST], current[SELFTEST].replace(NPC_VIEW_OLD.encode(), NPC_VIEW_NEW.encode()))
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    final = patch_layers.restack({rel: new for rel, (_, new) in plan.items()}, upper, peeled)
    print(f"{cid}: окон в data/lang/ru — {done}, файлов в патче {len(plan)}; наложение на копию — байт в байт")
    if write:
        (small.ROOT / "patches" / cid / name).write_bytes(patch)
        for rel, data in final.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{name} и дерево {src.relative_to(small.ROOT)}")


if __name__ == "__main__":
    main()
