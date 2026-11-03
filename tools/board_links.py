"""Мёртвые кнопки доски Alt+B и GM-шопа, без сервера: куда ведёт каждый bypass и action в страницах и в коде доски.

  python -X utf8 tools/board_links.py [<id>]                  по умолчанию ct26_highfive; код возврата 1, если есть DEAD
  python -X utf8 tools/board_links.py [<id>] --all            ещё и OFF, GM и INFO
  python -X utf8 tools/board_links.py [<id>] --buttons        каждая найденная кнопка и её вердикт
  python -X utf8 tools/board_links.py [<id>] --tree <папка>   другая копия дерева: проба патча, пока servers/ занят

Только читает дерево собранной хроники (servers/<id>/<сборка>, как tools/multisell_prices.py) и модуль фантомов phantoms/. Порядок проверки
повторяет сервер: RequestBypassToServer, затем CommunityBoardHandler.getHandler (совпадение по началу без учёта регистра), затем разбор
внутри обработчика (command.equals, command.startsWith, case, args[0]).

Вердикты:
  DEAD   нажатие ничего не сделает: нет обработчика, страницы, мультиселла, команды внутри обработчика;
  OFF    обработчик выключен настройкой, кнопка оживёт вместе с ней;
  GM     кнопка только для админа: у обычного игрока команда молча отвергается;
  INFO   страница, на которую никто не ссылается, или расхождение перевода;
  ok     цель есть.

Что код не видит: кнопки, текст которых целиком собирается в переменной. Такие попадают в строку «не разобрано» с файлом и номером.
Живой проход по кнопкам делает самопроверка доски (HuntersSelfTest = BOARD), эта проверка её не заменяет: она только про «есть ли цель».
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# Кнопки, которые шлёт сам клиент: верхние вкладки окна. Их строки сервер передаёт в пакете ShowBoard.
SHOW_BOARD = "java/org/l2jmobius/gameserver/network/serverpackets/ShowBoard.java"
REQUEST_BYPASS = "java/org/l2jmobius/gameserver/network/clientpackets/RequestBypassToServer.java"
BOARD_DIR = "dist/game/data/scripts/handlers/bypass/communityboard"
SCRIPTS = "dist/game/data/scripts"

# Код, который рисует страницы доски, кроме самих обработчиков. Файлы модуля фантомов ищутся в phantoms/core и phantoms/<id>.
BOARD_MODULES = ("PhantomUi", "PhantomHunterUi", "PhantomClanBoard", "PhantomEventBoard", "PhantomBufferSets", "PhantomLootSell")

# Команда доски, где она разбирается дальше: подкоманды ищутся в этих файлах (args[N].equalsIgnoreCase("x"), case "x", arg.equals("x")).
SUBCOMMANDS = {
    "_bbshubbuff": ("L2HubBoard", "PhantomBufferSets"),
    "_bbshubshop": ("L2HubBoard",),
    "_bbshubbuddy": ("L2HubBoard", "PhantomUi", "PhantomCompanionManager"),
    "_bbshubhunters": ("L2HubBoard", "PhantomHunterUi"),
    "_bbshubwars": ("L2HubBoard", "PhantomClanBoard"),
    "_bbshubclan": ("L2HubBoard", "PhantomClanBoard"),
    "_bbshubevents": ("L2HubBoard", "PhantomEventBoard"),
    "_bbshubspots": ("L2HubBoard",),
    "_bbshubworld": ("L2HubBoard",),
    "_bbshubplay": ("L2HubBoard",),
    "_bbshubkits": ("L2HubBoard", "L2HubKits"),
    "_bbshubfind": ("L2HubBoard", "L2HubFind"),
    "_bbshubraids": ("L2HubBoard", "L2HubRaids"),
    "_bbshubkarma": ("L2HubBoard", "L2HubKarma"),
}

# Помощники, у которых действие кнопки приходит аргументом: файл, функция, номер аргумента, чем дополняется начало.
EMITTERS = (
    ("L2HubBoard", "button", 1, ""),
    ("L2HubBoard", "worldRow", 2, ""),
    ("L2HubKits", "L2HubBoard.button", 1, ""),
    ("L2HubFind", "L2HubBoard.button", 1, ""),
    ("L2HubRaids", "L2HubBoard.button", 1, ""),
    ("L2HubKarma", "L2HubBoard.button", 1, ""),
    ("PhantomLootSell", "button", 1, ""),
    ("PhantomClanBoard", "button", 1, "!raw"),
    ("PhantomEventBoard", "button", 1, "!raw"),
    ("PhantomHunterUi", "boardButton", 1, "!raw"),
    ("PhantomBufferSets", "button", 1, "_bbshubbuff "),
    ("PhantomUi", "cell", 3, "_bbshubbuddy "),
    ("PhantomUi", "row", 5, "_bbshubbuddy "),
)

# Параметр помощника, который задаёт вызывающий код (L2HubBoard: "bypass " + HUNTERS и т.п.).
PARAMS = {
    "PhantomHunterUi": {"bypass": "bypass _bbshubhunters"},
    "PhantomLootSell": {"shop": "_bbshubshop"},
}

ACTION = re.compile(r'\baction\s*=\s*"([^"]*)"', re.I)
JAVA_TOKEN = re.compile(r'''
    (?P<comment>//[^\n]*|/\*.*?\*/)
  | (?P<string>"(?:\\.|[^"\\\n])*")
  | (?P<char>'(?:\\.|[^'\\\n])+')
  | (?P<word>[A-Za-z_][\w.]*)
  | (?P<num>\d[\w.]*)
  | (?P<space>\s+)
  | (?P<op>.)
''', re.X | re.S)


def unescape(literal: str) -> str:
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t", "r": "\r"}.get(m.group(1), m.group(1)), literal[1:-1])


def tokens_of(path: Path) -> list[tuple[str, str, int]]:
    """Токены java-файла без комментариев: вид, значение, строка."""
    text = path.read_bytes().decode("utf-8", "replace")
    out, line = [], 1
    for m in JAVA_TOKEN.finditer(text):
        kind, value = m.lastgroup, m.group()
        if kind not in ("comment", "space"):
            out.append((kind, unescape(value) if kind == "string" else value, line))
        line += value.count("\n")
    return out


def constants_of(tokens) -> dict[str, str]:
    """static final String NAME = "x" [+ "y"]: константы файла."""
    out = {}
    for i, (kind, value, _) in enumerate(tokens):
        if kind == "word" and value == "String" and i + 3 < len(tokens) and tokens[i + 2][1] == "=" and tokens[i + 3][0] == "string":
            parts, k = [tokens[i + 3][1]], i + 4
            while k + 1 < len(tokens) and tokens[k][1] == "+" and tokens[k + 1][0] == "string":
                parts.append(tokens[k + 1][1])
                k += 2
            if tokens[k][1] == ";" and any(t[1] == "final" for t in tokens[max(0, i - 3):i]):
                out[tokens[i + 1][1]] = "".join(parts)
    return out


def command_arrays(text: str) -> list[str]:
    """Тела массивов команд обработчика: массив назван в getCommandList. HomeBoard собирает список из двух, это тоже видно по именам."""
    body = re.search(r"getCommandList\(\)\s*\{(.*?)\n\t\}", text, re.S)
    if not body:
        return []
    out = []
    for m in re.finditer(r"String\[\]\s+(\w+)\s*=\s*\{(.*?)\};", text, re.S):
        if re.search(r"\b" + m.group(1) + r"\b", body.group(1)):
            out.append(re.sub(r"//[^\n]*", "", m.group(2)))
    return out


class Tree:
    def __init__(self, cid: str, root: Path | None = None):
        meta = json.loads((REPO / "phantoms" / cid / "base.json").read_text(encoding="utf-8"))
        self.cid = cid
        self.root = root or REPO / "servers" / cid / meta["build"]
        self.data = self.root / "dist/game/data"
        self.html = self.data / "html"
        self.ru = self.data / "lang/ru/data/html"
        self.findings: list[tuple[str, str, str]] = []
        self.buttons: list[tuple[str, str, str, str]] = []
        self.unparsed: list[str] = []
        self.linked_pages: set[Path] = set()
        self.linked_lists: set[int] = set()
        self.ini = self.load_ini()
        self.boards = self.load_boards()
        self.non_html = self.load_non_html()
        self.bypass_commands = self.load_handlers(f"{SCRIPTS}/handlers/bypass", IGNORE=("communityboard",))
        self.voiced = self.load_voiced()
        self.admin = self.load_admin()
        self.multisell = self.load_multisell()
        self.shop_npc = int(self.ini.get("CommunityShopNpc", "0"))
        self.java_cache: dict[Path, list] = {}

    # ---------- настройки и реестры ----------

    def load_ini(self) -> dict[str, str]:
        out = {}
        for path in sorted((self.root / "dist/game/config").rglob("*.ini")):
            text = path.read_bytes().decode("utf-8", "replace").replace("\\\r\n", "").replace("\\\n", "")
            for line in text.splitlines():
                line = line.strip()
                if line and line[0] not in "#!" and "=" in line:
                    key, value = line.split("=", 1)
                    out.setdefault(key.strip(), value.strip())
        return out

    def config_on(self, field: str) -> tuple[bool, str]:
        """Поле класса настроек (CommunityBoardConfig.X) — ключ ini и его значение."""
        cls, _, name = field.rpartition(".")
        hits = list(self.root.glob(f"java/org/l2jmobius/gameserver/config/**/{cls}.java"))
        if not hits:
            return True, field
        m = re.search(name + r'\s*=\s*\w+\.getBoolean\("(\w+)",\s*(\w+)\)', hits[0].read_bytes().decode("utf-8", "replace"))
        if not m:
            return True, field
        value = self.ini.get(m.group(1), m.group(2))
        return value.lower() == "true", m.group(1)

    def load_boards(self) -> dict[str, dict]:
        """Обработчики доски из MasterHandler: команды, условия из CUSTOM_COMMANDS и разбор внутри onCommand."""
        master = (self.root / SCRIPTS / "handlers/MasterHandler.java").read_bytes().decode("utf-8", "replace")
        boards = {}
        for name in re.findall(r"import handlers\.bypass\.communityboard\.(\w+);", master):
            if f"{name}.class" not in master:
                continue
            tokens = tokens_of(self.root / BOARD_DIR / f"{name}.java")
            const = constants_of(tokens)
            commands = {}
            delegated = set()
            text = (self.root / BOARD_DIR / f"{name}.java").read_bytes().decode("utf-8", "replace")
            for block in command_arrays(text):
                for item in block.split(","):
                    item = item.strip()
                    cond = re.fullmatch(r'(.*?)\?\s*"(\w+)"\s*:\s*null', item, re.S)
                    if cond:
                        commands[cond.group(2)] = [f.strip() for f in cond.group(1).strip().split("&&")]
                    elif item.startswith('"'):
                        commands[item.strip('"')] = []
                    elif item in const:
                        commands[const[item]] = []
                    elif item.endswith(".COMMAND"):
                        other = constants_of(tokens_of(self.root / BOARD_DIR / f"{item.split('.')[0]}.java"))
                        commands[other["COMMAND"]] = []
                        delegated.add(other["COMMAND"])
            boards[name] = {"commands": commands, "delegated": delegated, "exact": set(), "prefix": set(), "first": set(), "text": text, "const": const}
            self.parse_dispatch(boards[name], tokens, const)
        return boards

    @staticmethod
    def parse_dispatch(board: dict, tokens, const):
        """Что onCommand реально принимает. Нет ни одного сравнения: принимает всё, что дошло (MailBoard, MemoBoard)."""
        for i, (kind, value, _) in enumerate(tokens):
            if kind != "word":
                continue
            arg = None
            if i + 2 < len(tokens) and tokens[i + 1][1] == "(":
                next_kind, next_value, _ = tokens[i + 2]
                arg = next_value if next_kind == "string" else const.get(next_value)
            if arg is None:
                continue
            if value == "command.equals":
                board["exact"].add(arg)
            elif value == "command.startsWith":
                board["prefix"].add(arg)
            elif value == "args[0].equalsIgnoreCase":
                board["first"].add(arg)
        # args[0] разбирается токенайзером как args, [, 0, ], .equalsIgnoreCase — отдельный проход
        for i in range(len(tokens) - 6):
            if tokens[i][1] == "args" and tokens[i + 2][1] == "0" and tokens[i + 4][1] in (".", ".equalsIgnoreCase"):
                k = i + 5
                while k < len(tokens) and tokens[k][1] != "(":
                    k += 1
                value_kind, value, _ = tokens[k + 1]
                resolved = value if value_kind == "string" else const.get(value)
                if resolved is None and value.endswith(".COMMAND"):
                    resolved = "@" + value
                if resolved:
                    board["first"].add(resolved)
        for i in range(len(tokens) - 1):
            if tokens[i][1] == "case" and tokens[i + 1][0] == "string":
                board["first"].add(tokens[i + 1][1])

    def load_non_html(self) -> tuple[str, ...]:
        text = (self.root / REQUEST_BYPASS).read_bytes().decode("utf-8", "replace")
        block = re.search(r"_possibleNonHtmlCommands\s*=\s*\{(.*?)\};", text, re.S)
        return tuple(re.findall(r'"([^"]+)"', block.group(1)))

    def load_handlers(self, rel: str, IGNORE=()) -> set[str]:
        """Обработчики bypass: все COMMANDS из папки handlers/bypass, кроме доски."""
        out = set()
        for path in (self.root / rel).rglob("*.java"):
            if any(part in path.parts for part in IGNORE):
                continue
            text = path.read_bytes().decode("utf-8", "replace")
            for block in command_arrays(text):
                out.update(c.lower() for c in re.findall(r'"([^"]+)"', block))
        return out

    def java_files(self):
        yield from (self.root / SCRIPTS).rglob("*.java")
        for sub in ("core", self.cid):
            yield from (REPO / "phantoms" / sub / "java").rglob("*.java")

    def load_voiced(self) -> dict[str, Path]:
        out = {}
        for path in self.java_files():
            text = path.read_bytes().decode("utf-8", "replace")
            if "IVoicedCommandHandler" not in text or "getCommandList" not in text:
                continue
            for block in command_arrays(text):
                for word in re.findall(r'"([^"]+)"', block):
                    out.setdefault(word, path)
        return out

    def load_admin(self) -> set[str]:
        out = set()
        for path in self.java_files():
            text = path.read_bytes().decode("utf-8", "replace")
            if "IAdminCommandHandler" in text:
                out.update(re.findall(r'"(admin_\w+)"', text))
        return out

    def load_multisell(self) -> dict[int, dict]:
        """Как MultisellData.load: корень multisell и custom при CustomMultisellLoad, без вложенных папок дальше; custom перекрывает корень."""
        out = {}
        dirs = [self.data / "multisell"]
        if self.ini.get("CustomMultisellLoad", "false").lower() == "true":
            dirs.append(self.data / "multisell/custom")
        for folder in dirs:
            for path in sorted(folder.glob("*.xml")):
                try:
                    root = ET.parse(path).getroot()
                except ET.ParseError as error:
                    self.findings.append(("DEAD", f"{self.rel(path)}", f"мультиселл не разбирается: {error}"))
                    continue
                npcs = {int(n.text) for n in root.findall("npcs/npc")}
                out[int(path.stem)] = {"npcs": npcs, "items": len(root.findall("item")), "path": path}
        return out

    def rel(self, path: Path) -> str:
        for base in (self.root, REPO):
            try:
                return path.relative_to(base).as_posix()
            except ValueError:
                pass
        return str(path)

    # ---------- проверка одной команды ----------

    def report(self, verdict: str, where: str, button: str, why: str = ""):
        self.buttons.append((verdict, where, button, why))
        if verdict != "ok":
            self.findings.append((verdict, where, f"{button}: {why}"))

    def board_of(self, command: str):
        """CommunityBoardHandler.getHandler: команда начинается с команды обработчика, регистр не важен."""
        low = command.lower()
        for name, board in self.boards.items():
            for known, cond in board["commands"].items():
                if low.startswith(known.lower()):
                    return name, board, known, cond
        return None

    def accepted(self, board: dict, command: str) -> bool:
        """Дойдёт ли команда до какой-нибудь ветки onCommand этого обработчика."""
        if not (board["exact"] or board["prefix"] or board["first"]):
            return True
        first = re.split(r"\s", command, maxsplit=1)[0]
        if command in board["exact"] or any(command.startswith(p) for p in board["prefix"]):
            return True
        return any(first.lower() == f.lower() for f in board["first"]) or ((first in board["delegated"]) and any(f.startswith("@") for f in board["first"]))

    def check_action(self, action: str, where: str, lang_page: Path | None = None):
        action = action.strip()
        if not action:
            return  # пустая кнопка-заглушка листалки
        low = action.lower()
        if low.startswith("url ") or low.startswith("url!"):
            return self.report("ok", where, action)  # вкладка внутри окна клиента
        if low.startswith("write"):
            return self.check_write(action, where)
        if low.startswith("link "):
            path = self.html / action[5:].strip().split("#")[0]
            return self.report("ok" if path.is_file() else "DEAD", where, action, "" if path.is_file() else "нет страницы")
        if not low.startswith("bypass"):
            return self.report("DEAD", where, action, "нет слова bypass: клиент не отправит серверу ничего")
        if not low.startswith("bypass "):
            return self.report("DEAD", where, action, "нет пробела после bypass: сервер ждёт «bypass команда», проверено только по разбору кэша действий, не клиентом")
        command = action[7:].strip()
        if command.startswith("-h "):
            command = command[3:].strip()
        self.check_command(command, where)

    def check_write(self, action: str, where: str):
        """Write Topic|Post|Mail|Notice: RequestBBSwrite → handleWriteCommand. Остальное: «is not implemented yet»."""
        url = action.split()[1] if " " in action else action
        handler = (self.root / "java/org/l2jmobius/gameserver/handler/CommunityBoardHandler.java").read_bytes().decode("utf-8", "replace")
        body = handler[handler.index("void handleWriteCommand"):]
        if url in re.findall(r'case "(\w+)":', body):
            self.report("ok", where, action)
        else:
            self.report("DEAD", where, action, "RequestBBSwrite ответит «not implemented»: такого url нет в handleWriteCommand")

    def check_command(self, command: str, where: str):
        if command.startswith("admin_"):
            word = command.split()[0]
            if word.lower() in self.admin:
                return self.report("GM", where, command, "только GM: обычному игроку сервер откажет")
            return self.report("DEAD", where, command, "нет такой админ-команды")
        if command.startswith("npc_"):
            return self.report("DEAD", where, command, "npc_ с доски: рядом нет NPC, RequestBypassToServer не найдёт объект")
        if command.startswith("voice "):
            return self.check_voice(command, where)

        found = self.board_of(command)
        if found:
            return self.check_board(command, where, found)
        word = command.split()[0] if command.split() else command
        if word.lower() in self.bypass_commands:  # BypassHandler.getHandler: первое слово целиком
            return self.report("ok", where, command)
        self.report("DEAD", where, command, "ни одна доска и ни один обработчик bypass не берут такую команду")

    def check_voice(self, command: str, where: str):
        word = command.split()[1] if len(command.split()) > 1 else ""
        if "voice" not in self.bypass_commands:
            return self.report("DEAD", where, command, "нет обработчика bypass voice")
        if not word.startswith(".") or word[1:] not in self.voiced:
            return self.report("DEAD", where, command, f"нет голосовой команды {word}")
        words = command.split()
        if len(words) > 2 and words[2] != "*" and not words[2].startswith("$") and not words[2].isdigit():
            handler = self.voiced[word[1:]]
            texts = [handler.read_bytes().decode("utf-8", "replace")]
            sibling = handler.with_name(handler.name.replace("Command", ""))
            if sibling.is_file():
                texts.append(sibling.read_bytes().decode("utf-8", "replace"))
            if not any(f'"{words[2]}' in text for text in texts):
                return self.report("DEAD", where, command, f"в разборе {handler.name} нет «{words[2]}»")
        self.report("ok", where, command)

    def check_board(self, command: str, where: str, found):
        name, board, known, cond = found
        for field in cond:
            on, key = self.config_on(field)
            if not on:
                return self.report("OFF", where, command, f"{name}: {key} выключен")
        if not self.accepted(board, command):
            return self.report("DEAD", where, command, f"{name} берёт команду по {known}, но ни одна ветка onCommand её не разбирает")
        # Цели внутри команды
        if command.startswith("_bbstop;"):
            return self.check_page_target(command[8:], where, command, custom=True, need_ext=True)
        m = re.match(r"_bbs(?:exc)?multisell;(\d+),([\w/]+)", command)
        if m:
            problem = self.multisell_problem(int(m.group(1)), board_list=False)
            if problem:
                return self.report("DEAD", where, command, problem)
            return self.check_page_target(m.group(2) + ".html", where, command, custom=True)
        m = re.match(r"_bbs(?:sell|heal);([\w/]+)", command)
        if m:
            return self.check_page_target(m.group(1) + ".html", where, command, custom=True)
        m = re.match(r"_bbsbuff;(\d+),\d+;([\w/]+)", command)
        if m:
            allowed = self.ini.get("CommunityAvailableBuffs", "").split(",")
            if m.group(1) not in allowed:
                return self.report("DEAD", where, command, f"баф {m.group(1)} не в CommunityAvailableBuffs")
            return self.check_page_target(m.group(2) + ".html", where, command, custom=True)
        m = re.match(r"_bbsteleport;(\w+)", command)
        if m:
            names = {t.split(",")[0].strip() for t in self.ini.get("CommunityTeleportList", "").split(";")}
            if m.group(1) not in names:
                return self.report("DEAD", where, command, f"{m.group(1)} нет в CommunityTeleportList")
            return self.report("ok", where, command)
        m = re.match(r"_bbshubshop ms (\d+)", command)
        if m:
            problem = self.multisell_problem(int(m.group(1)), board_list=True)
            return self.report("DEAD" if problem else "ok", where, command, problem or "")
        if command.lower().startswith("_bbshubevents medals"):
            value = re.search(r"int MEDAL_SHOP\s*=\s*(\d+)", self.boards["L2HubBoard"]["text"])
            problem = self.multisell_problem(int(value.group(1)), board_list=True) if value else "нет MEDAL_SHOP"
            if value:
                self.linked_lists.add(int(value.group(1)))
            return self.report("DEAD" if problem else "ok", where, command, problem or "")
        m = re.match(r"_bbshubshop (\d+)$", command)
        if m:
            return self.check_shop_page(int(m.group(1)), where, command)
        self.check_subcommand(command, where)

    def check_page_target(self, rel: str, where: str, command: str, custom: bool, need_ext: bool = False):
        if need_ext and not rel.endswith(".html"):
            return self.report("DEAD", where, command, "HomeBoard открывает только *.html")
        path = self.html / "CommunityBoard" / ("Custom" if custom else "") / rel
        if path.is_file():
            self.linked_pages.add(path)
            return self.report("ok", where, command)
        self.report("DEAD", where, command, f"нет страницы {self.rel(path)}")

    def multisell_problem(self, list_id: int, board_list: bool) -> str | None:
        entry = self.multisell.get(list_id)
        if entry is None:
            return f"нет мультиселла {list_id}"
        if entry["items"] == 0:
            return f"мультиселл {list_id} пуст"
        if board_list and entry["npcs"] and self.shop_npc not in entry["npcs"]:
            return f"мультиселл {list_id} не открыт NPC {self.shop_npc} (в списке {sorted(entry['npcs'])[:6]}): separateAndSendFromBoard откажет"
        return None

    def check_shop_page(self, page: int, where: str, command: str):
        path = self.html / "merchant" / (f"{self.shop_npc}-{page}.htm" if page else f"{self.shop_npc}.htm")
        if path.is_file():
            self.linked_pages.add(path)
            return self.report("ok", where, command)
        self.report("DEAD", where, command, f"нет страницы {path.name}: L2HubBoard.shop молча откатится на главную GM-шопа")

    def check_subcommand(self, command: str, where: str):
        """Подкоманда: первое слово после команды, если оно не число и не подставляется ($var, *)."""
        words = command.split()
        first = words[0].lower()
        files = SUBCOMMANDS.get(first)
        if files is None or len(words) < 2:
            return self.report("ok", where, command)
        sub = words[1]
        if sub == "*" or sub.startswith("$") or sub.isdigit():
            return self.report("ok", where, command)
        known = self.subwords(first, files)
        if sub.lower() in known:
            return self.report("ok", where, command)
        self.report("DEAD", where, command, f"подкоманды «{sub}» нет в разборе ({', '.join(files)})")

    def source(self, stem: str) -> Path | None:
        for path in (self.root / BOARD_DIR / f"{stem}.java", REPO / "phantoms/core/java/org/l2jmobius/gameserver/managers/phantom" / f"{stem}.java"):
            if path.is_file():
                return path
        return None

    def subwords(self, command: str, files) -> set[str]:
        """Слова, которые сравниваются в разборе команды: equalsIgnoreCase("x"), equals("x"), case "x", startsWith("x"); и значения enum-вкладок охотников."""
        known = set()
        for stem in files:
            path = self.source(stem)
            if path is None:
                continue
            text = path.read_bytes().decode("utf-8", "replace")
            known.update(w.lower() for w in re.findall(r'(?:equalsIgnoreCase|equals|startsWith)\("([\w_]+)"\)', text))
            known.update(w.lower() for w in re.findall(r'case "([\w_]+)"', text))
            known.update(w.lower() for w in re.findall(r'"([\w_]+)"\.(?:equals|equalsIgnoreCase)\(', text))
            known.update(w.lower() for w in re.findall(r'"([\w_]+)",\s*Order\.', text))
            for block in re.findall(r"enum \w+\s*\{(.*?)\}", text, re.S):
                known.update(w.lower() for w in re.findall(r"^\s*([A-Z][A-Z_0-9]*)\s*[,(;]?", block, re.M))
        return known

    # ---------- страницы ----------

    def scan_html(self):
        """Страницы доски обоих языков и страницы GM-шопа. Форма кнопки в GM-шопе проверяется отдельно: доска переписывает только Chat и multisell."""
        pages = sorted((self.html / "CommunityBoard").rglob("*.htm*")) + sorted((self.ru / "CommunityBoard").rglob("*.htm*"))
        for path in pages:
            text = path.read_bytes().decode("utf-8", "replace")
            for m in ACTION.finditer(text):
                action = m.group(1)
                where = f"{self.rel(path)}:{text.count(chr(10), 0, m.start()) + 1}"
                if re.fullmatch(r"bypass (?:-h )?%\w+%", action):  # вся команда из подстановки: цель проверяется там, где её собирают
                    self.report("ok", where, action)
                    continue
                self.check_action(re.sub(r"%\w+%", "1", action), where)
        self.scan_shop()

    def scan_size(self):
        """HtmlUtil.sendCBHtml: страница длиннее 3 × 16250 символов заменяется на «HTML was too long»."""
        limit = 16250 * 3
        navigation = (self.html / "CommunityBoard/Custom/navigation.html").read_bytes().decode("utf-8", "replace")
        pages = list((self.html / "CommunityBoard").rglob("*.htm*")) + list((self.html / "merchant").glob(f"{self.shop_npc}*.htm")) + list((self.ru / "merchant").glob(f"{self.shop_npc}*.htm"))
        sized = sorted(((len(p.read_bytes().decode("utf-8", "replace")) + len(navigation), p) for p in pages), key=lambda x: -x[0])
        self.largest = (sized[0][0], self.rel(sized[0][1]), limit)
        for size, path in sized:
            if size >= limit:
                self.findings.append(("DEAD", self.rel(path), f"с навигацией {size} символов: сервер пришлёт «HTML was too long»"))

    def scan_shop(self):
        folders = [self.html / "merchant", self.ru / "merchant"]
        if not any(f.is_dir() and list(f.glob(f"{self.shop_npc}*.htm")) for f in folders):
            self.findings.append(("DEAD", f"html/merchant/{self.shop_npc}.htm", "у GM-шопа нет страниц"))
            return
        chat = re.compile(r"bypass (?:-h )?npc_%objectId%_Chat (\d+)$")
        ms = re.compile(r"bypass (?:-h )?npc_%objectId%_multisell (\d+)$")
        for folder in folders:
            for path in sorted(folder.glob(f"{self.shop_npc}*.htm")):
                stem = path.stem
                page_id = int(stem.split("-")[1]) if "-" in stem else 0
                text = path.read_bytes().decode("utf-8", "replace")
                for m in ACTION.finditer(text):
                    action = m.group(1).strip()
                    where = f"{self.rel(path)}:{text.count(chr(10), 0, m.start()) + 1}"
                    hit = chat.fullmatch(action)
                    if hit:
                        self.check_board(f"_bbshubshop {hit.group(1)}", where, self.board_of("_bbshubshop"))
                        continue
                    hit = ms.fullmatch(action)
                    if hit:
                        self.check_board(f"_bbshubshop ms {hit.group(1)} {page_id}", where, self.board_of("_bbshubshop"))
                        continue
                    if action.startswith("bypass voice "):  # голосовой команде не нужен NPC: и с доски, и из окна уходит как есть
                        self.check_action(action, where)
                        continue
                    self.report("DEAD", where, action, "L2HubBoard.shop переписывает только npc_%objectId%_Chat и _multisell: на доске кнопка останется мёртвой")

    def scan_orphans(self):
        """Страницы, на которые не ведёт ни одна кнопка и ни одна строка кода доски."""
        code = "\n".join(board["text"] for board in self.boards.values())
        for path in sorted((self.html / "CommunityBoard").rglob("*.html")):
            rel = path.relative_to(self.html / "CommunityBoard").as_posix()
            short = rel.removeprefix("Custom/")
            if path in self.linked_pages or rel in code or f'/{short}"' in code or short in code:
                continue
            if "/" in rel and not rel.startswith("Custom/"):
                continue
            self.findings.append(("INFO", self.rel(path), "на страницу не ссылается ни кнопка, ни код доски"))

        shop = self.html / "merchant"
        for path in sorted(shop.glob(f"{self.shop_npc}-*.htm")):
            if path not in self.linked_pages:
                self.findings.append(("INFO", self.rel(path), "страница GM-шопа, на которую нет кнопки"))
        linked = self.linked_lists | {int(b[2].split()[2]) for b in self.buttons if b[2].startswith("_bbshubshop ms ")}
        lost = sorted(i for i, e in self.multisell.items() if self.shop_npc in e["npcs"] and i not in linked)
        if lost:
            self.findings.append(("INFO", f"multisell/, {len(lost)} шт.", f"открыты для NPC {self.shop_npc}, но на страницах нет кнопки: {', '.join(map(str, lost[:40]))}"))

    def scan_lang(self):
        """lang/ru: тот же набор кнопок, что в английской странице: иначе кнопка есть только на одном языке."""
        root = self.ru / "CommunityBoard"
        for path in sorted(root.rglob("*.htm*")):
            base = self.html / "CommunityBoard" / path.relative_to(root)
            if not base.is_file():
                self.findings.append(("INFO", self.rel(path), "перевод есть, английской страницы нет"))
                continue
            ru = sorted(ACTION.findall(path.read_bytes().decode("utf-8", "replace")))
            en = sorted(ACTION.findall(base.read_bytes().decode("utf-8", "replace")))
            if ru != en:
                only = sorted(set(ru) ^ set(en))
                self.findings.append(("INFO", self.rel(path), f"кнопки расходятся с английской страницей: {only[:6]}"))

    def scan_multisell_items(self):
        """Предметы списков, на которые есть кнопка: нет шаблона — строка не покупается."""
        known = set()
        for path in sorted((self.data / "stats/items").rglob("*.xml")):
            for item in ET.parse(path).getroot().findall("item"):
                known.add(int(item.get("id")))
        linked = self.linked_lists | {int(b[2].split()[2]) for b in self.buttons if b[2].startswith("_bbshubshop ms ")}
        for list_id in sorted(linked):
            entry = self.multisell.get(list_id)
            if entry is None:
                continue
            root = ET.parse(entry["path"]).getroot()
            missing = sorted({int(n.get("id")) for n in root.iter() if n.tag in ("ingredient", "production") and int(n.get("id")) > 0 and int(n.get("id")) not in known})
            if missing:
                self.findings.append(("DEAD", self.rel(entry["path"]), f"в списке предметы без шаблона: {missing[:10]}"))

    def scan_admin(self):
        """Страницы //admin: у кнопки admin_x есть обработчик и строка прав, у link — страница. Отдельный счёт: это не игрок, код возврата не меняет."""
        root = self.html / "admin"
        rights = {"admin_" + c.removeprefix("admin_") for c in re.findall(r'command="(\w+)"', (self.root / "dist/game/config/AdminCommands.xml").read_bytes().decode("utf-8", "replace"))}
        self.admin_dead: dict[str, list[str]] = {}
        self.admin_buttons = 0
        for path in sorted(root.rglob("*.htm*")):
            text = path.read_bytes().decode("utf-8", "replace")
            for m in ACTION.finditer(text):
                action = m.group(1).strip()
                where = f"{self.rel(path)}:{text.count(chr(10), 0, m.start()) + 1}"
                self.admin_buttons += 1
                link = re.fullmatch(r"link (\S+?)(#\S*)?", action)
                if link:
                    target = self.html / link.group(1)
                    if not target.is_file():
                        self.admin_dead.setdefault(f"{action}: нет страницы", []).append(where)
                    continue
                hit = re.match(r"bypass (?:-h )?(admin_\w+)", action, re.I)
                if not hit:
                    continue
                word = hit.group(1).lower()
                if word not in self.admin:
                    self.admin_dead.setdefault(f"{hit.group(1)}: нет обработчика", []).append(where)
                elif word not in rights:
                    self.admin_dead.setdefault(f"{hit.group(1)}: нет строки в AdminCommands.xml, доступно только высшему уровню", []).append(where)

    # ---------- код ----------

    def scan_show_board(self):
        """Верхние вкладки окна: их клиент шлёт сам, строки приходят в пакете ShowBoard."""
        path = self.root / SHOW_BOARD
        text = path.read_bytes().decode("utf-8", "replace")
        for m in re.finditer(r'writeString\("(bypass [^"]+)"\)', text):
            self.check_action(m.group(1), f"{self.rel(path)}:{text.count(chr(10), 0, m.start()) + 1}")

    def expression(self, tokens, start: int, const, stop_at_comma: bool = True) -> tuple[str, int]:
        """Строка-шаблон из выражения от позиции start: литералы и константы как есть, всё остальное — звёздочка. Склеивает «+» и .append(."""
        out = []
        depth = 0
        i = start
        while i < len(tokens):
            kind, value, _ = tokens[i]
            if kind == "string":
                out.append(value)
            elif value in ("+",):
                pass
            elif value == "(":
                depth += 1
                if not out or out[-1] != "*":
                    out.append("*")
            elif value == ")":
                if depth == 0:
                    break
                depth -= 1
            elif value == "," and depth == 0 and stop_at_comma:
                break
            elif value in (";", "?", ":") and depth == 0:
                break
            elif depth == 0:
                if kind == "word" and value in const:
                    out.append(const[value])
                elif kind == "word" and value.endswith(".COMMAND"):
                    other = self.source(value.split(".")[0])
                    out.append(constants_of(tokens_of(other)).get("COMMAND", "*") if other else "*")
                elif not out or out[-1] != "*":
                    out.append("*")
            i += 1
        return "".join(out), i

    def scan_java(self):
        stems = [p.stem for p in (self.root / BOARD_DIR).glob("*.java")] + list(BOARD_MODULES)
        for stem in stems:
            path = self.source(stem)
            if path is None:
                self.unparsed.append(f"{stem}: файл не найден")
                continue
            tokens = tokens_of(path)
            const = constants_of(tokens) | PARAMS.get(stem, {})
            self.scan_literals(path, tokens, const)
            self.scan_emitters(stem, path, tokens, const)

    def scan_literals(self, path: Path, tokens, const):
        """Литералы с action=\"…\": текст до закрывающей кавычки, куски между ними — звёздочки. Склейка идёт через «+» и .append(."""
        for i, (kind, value, line) in enumerate(tokens):
            if kind != "string" or 'action="' not in value:
                continue
            where = f"{self.rel(path)}:{line}"
            pieces = [value]
            k = i + 1
            # Вперёд, пока кнопка не закрыта. Литерал может быть разбит переменными и вызовами.
            while k < len(tokens) and not re.search(r'action="[^"]*"[^"]*$', "".join(pieces).rsplit('action="', 1)[0][-0:] + 'action="' + "".join(pieces).rsplit('action="', 1)[1]) and k < i + 40 and tokens[k][1] != ";":
                t_kind, t_value, _ = tokens[k]
                if t_kind == "string":
                    pieces.append(t_value)
                elif t_kind == "word" and t_value in const:
                    pieces.append(const[t_value])
                elif t_value not in ("+", ")", "(", ".append", "append", ".") and not pieces[-1].endswith("*"):
                    pieces.append("*")
                k += 1
            text = "".join(pieces)
            for m in re.finditer(r'action="([^"]*)', text):
                action = m.group(1)
                if not action:
                    continue
                if action.startswith(("*", "%s", "bypass *", "bypass %s")):
                    continue  # помощник: действие приходит аргументом, смотрит scan_emitters
                self.check_action(action.replace("%s", "*"), where)

    def scan_emitters(self, stem: str, path: Path, tokens, const):
        """Помощники кнопок: button(label, ДЕЙСТВИЕ, width) и им подобные, плюс константы вида "bypass _bbsx" в выражениях."""
        for file_stem, function, position, prefix in EMITTERS:
            if file_stem != stem:
                continue
            for i, (kind, value, line) in enumerate(tokens):
                if value != function or tokens[i + 1][1] != "(" or tokens[i - 1][1] in ("String",):
                    continue
                k, depth, arg = i + 2, 0, 0
                while k < len(tokens) and arg < position:
                    if tokens[k][1] == "(":
                        depth += 1
                    elif tokens[k][1] == ")":
                        depth -= 1
                    elif tokens[k][1] == "," and depth == 0:
                        arg += 1
                    k += 1
                if arg < position:
                    continue
                template, _ = self.expression(tokens, k, const)
                where = f"{self.rel(path)}:{line}"
                self.check_template(template, prefix, where)

    def check_template(self, template: str, prefix: str, where: str):
        if prefix == "!raw":  # действие уже со словом bypass
            prefix = ""
        text = prefix + template
        if text.startswith("bypass "):
            text = text[7:]
        if text.strip() == "*":
            return  # помощник переслал свой параметр дальше: кнопку видно в месте вызова
        if text.startswith("*"):
            self.unparsed.append(f"{where}: действие начинается с переменной ({template[:50]})")
            return
        self.check_command(text.strip(), where)

    # ---------- итог ----------

    def run(self):
        self.scan_show_board()
        self.scan_html()
        self.scan_java()
        self.scan_lang()
        self.scan_orphans()
        self.scan_size()
        self.scan_multisell_items()
        self.scan_admin()


def main() -> int:
    argv = sys.argv[1:]
    root = None
    if "--tree" in argv:
        at = argv.index("--tree")
        root = Path(argv[at + 1])
        del argv[at:at + 2]
    args = [a for a in argv if not a.startswith("--")]
    tree = Tree(args[0] if args else "ct26_highfive", root)
    tree.run()

    if "--buttons" in sys.argv:
        for verdict, where, button, why in tree.buttons:
            print(f"{verdict:4} {where}  {button}{'  # ' + why if why else ''}")
        print()

    order = {"DEAD": 0, "OFF": 1, "GM": 2, "INFO": 3}
    shown = {"DEAD"} if "--all" not in sys.argv else set(order)
    seen = set()
    for verdict, where, text in sorted(tree.findings, key=lambda f: (order[f[0]], f[1])):
        if verdict in shown and (verdict, where, text) not in seen:
            seen.add((verdict, where, text))
            print(f"{verdict:4} {where}\n     {text}")
    counts = {}
    for verdict, *_ in tree.buttons:
        counts[verdict] = counts.get(verdict, 0) + 1
    print(f"\nкнопок {len(tree.buttons)}: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    print(f"мультиселлов загружено {len(tree.multisell)}, обработчиков доски {len(tree.boards)}, голосовых команд {len(tree.voiced)}")
    print(f"самая длинная страница {tree.largest[0]} символов из {tree.largest[2]}: {tree.largest[1]}")
    print(f"//admin: кнопок {tree.admin_buttons}, команд и страниц без цели {len(tree.admin_dead)}, нажатий на них {sum(map(len, tree.admin_dead.values()))}")
    if "--all" in sys.argv:
        for text, places in sorted(tree.admin_dead.items()):
            print(f"  {text}  [{len(places)}: {places[0]}]")
    if tree.unparsed:
        print(f"не разобрано {len(tree.unparsed)}:")
        for line in tree.unparsed:
            print("  " + line)
    return 1 if any(f[0] == "DEAD" for f in tree.findings) else 0


if __name__ == "__main__":
    sys.exit(main())
