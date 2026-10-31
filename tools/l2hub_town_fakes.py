"""Патч «Фейки в городах» для хроники Mobius: patches/<id>/0031-town-fakes.patch.

Игроки-NPC Mobius (шаблоны с fakePlayer, как Evi у гейткипера Гирана) во всех городах и деревнях хроники. Только новые файлы:
  stats/npcs/custom/l2hub_town_fakes.xml — шаблоны: раса, класс по уровню своей ветки, шмот грейда (у новичков — стартовый),
                                            заточка, шапки и уши, титулы, лавки (продажа, скупка, крафт), прозвища для чата;
  data/L2HubTownFakes.xml                 — кто в каком городе, роль, места на утро и день, вечер и ночь (точки по геодате рядом с
                                            гейткипером, складом, лавками, гильдией, олимпиадой, рыбаками; ходокам — маршруты, где
                                            по геодате можно пройти по прямой); rank — для плотности, shift — сдвиг распорядка;
  data/L2HubTownFakesChat.xml             — заготовки реплик ru и en (эталон — tools/l2hub_town_fakes/);
  data/L2HubTownFakePersonas.json         — характеры для мозгов фейков (launcher/l2hub/brain.py);
  config/Custom/L2HubTownFakes.ini        — плотность, частота реплик, реакции, мозги;
  data/scripts/custom/L2HubTownFakes      — скрипт (эталон — tools/l2hub_town_fakes/L2HubTownFakes.java): распорядок, ходьба,
                                            реплики, реакции на события мира, крючок для других модулей.
Работает под EnableFakePlayers (config/Custom/FakePlayers.ini): выключено — Mobius не грузит шаблоны фейков, скрипт молчит.
Роли и сколько их в городе — ROSTER и ROLES ниже; имена — NAMES; характеры — TRAITS, STYLES, BIO. Результат детерминирован.

    python -X utf8 tools/l2hub_town_fakes.py <id>           — собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_town_fakes.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
    -v — отчёт по городам; --dump <папка> — файлы патча в папку (посмотреть).
Наложен прежний patches/<id>/0031 — он снимается с копии дерева, и патч собирается заново (tools/patch_layers.py).
Геодата — развёрнутого сервера (servers/<id>/server/game/data/geodata).
"""
import json
import math
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fake_players as fp  # noqa: E402
import patch_layers  # noqa: E402
import town_npcs as tn  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
HERE = Path(__file__).resolve().parent / "l2hub_town_fakes"
NAME = "0031-town-fakes.patch"
SCRIPT = "dist/game/data/scripts/custom/L2HubTownFakes/L2HubTownFakes.java"
NPCS = "dist/game/data/stats/npcs/custom/l2hub_town_fakes.xml"
POPULATION = "dist/game/data/L2HubTownFakes.xml"
CHAT = "dist/game/data/L2HubTownFakesChat.xml"
PERSONAS = "dist/game/data/L2HubTownFakePersonas.json"
INI = "dist/game/config/Custom/L2HubTownFakes.ini"
FILES = [SCRIPT, NPCS, POPULATION, CHAT, PERSONAS, INI]
EOL_FROM = "dist/game/data/scripts/handlers/MasterHandler.java"  # переводы строк скриптов хроники
NPC_ID_BASE = 80300  # 80300–80599; у tools/fake_players.py — 80100+, Evi — 80000
NPC_ID_MAX = 80599

# Города: id гейткипера → (вид, en, ru, «в …», «у …», уровни жителей, раса стартовой деревни).
TOWNS = {
    30006: ("start", "Talking Island", "Говорящий остров", "на Говорящем острове", "у Говорящего острова", (1, 20), "HUMAN"),
    30146: ("start", "Elven Village", "Эльфийская деревня", "в Эльфийской деревне", "у Эльфийской деревни", (1, 20), "ELF"),
    30134: ("start", "Dark Elven Village", "Деревня тёмных эльфов", "в Деревне тёмных эльфов", "у Деревни тёмных эльфов", (1, 20), "DARK_ELF"),
    30576: ("start", "Orc Village", "Деревня орков", "в Деревне орков", "у Деревни орков", (1, 20), "ORC"),
    30540: ("start", "Dwarven Village", "Деревня гномов", "в Деревне гномов", "у Деревни гномов", (1, 20), "DWARF"),
    32163: ("start", "Kamael Village", "Деревня камаэлей", "в Деревне камаэлей", "у Деревни камаэлей", (1, 20), "KAMAEL"),
    30320: ("village", "Gludin", "Глудин", "в Глудине", "у Глудина", (10, 30), None),
    30256: ("town", "Gludio", "Глудио", "в Глудио", "у Глудио", (15, 40), None),
    30059: ("town", "Dion", "Дион", "в Дионе", "у Диона", (20, 45), None),
    30080: ("capital", "Giran", "Гиран", "в Гиране", "у Гирана", (35, 65), None),
    30899: ("town", "Heine", "Хейне", "в Хейне", "у Хейне", (45, 70), None),
    30233: ("village", "Hunters Village", "Деревня охотников", "в Деревне охотников", "у Деревни охотников", (50, 70), None),
    30177: ("town", "Oren", "Орен", "в Орене", "у Орена", (40, 65), None),
    30848: ("capital", "Aden", "Аден", "в Адене", "у Адена", (55, 78), None),
    31275: ("town", "Goddard", "Годдард", "в Годдарде", "у Годдарда", (60, 80), None),
    31320: ("town", "Rune", "Руна", "в Руне", "у Руны", (60, 80), None),
    31964: ("town", "Schuttgart", "Шутгарт", "в Шутгарте", "у Шутгарта", (60, 80), None),
}
# Кто живёт в городе каждого вида (порядок — порядок id и имён).
ROSTER = {
    "start": ["newbie", "newbie", "newbie", "talker", "afk", "trader", "nightowl"],
    "village": ["trader", "buyer", "talker", "newbie", "lfp", "afk", "runner"],
    "town": ["trader", "trader", "buyer", "crafter", "afk", "talker", "talker", "newbie", "lfp", "runner", "stroller", "nightowl"],
}
ROSTER["capital"] = ROSTER["town"] + ["trader", "buyer", "showoff", "showoff", "talker"]

# Роли: где (якоря — NPC города: gk гейткипер, wh склад, shop лавки, master гильдия, oly олимпиада, fish рыбаки), сидит ли, ходит ли,
# и есть ли в игре утром и днём, вечером, ночью: 100 — всегда, 0 — нет, иначе шанс в процентах (у каждого фейка свой бросок).
ROLES = {
    "trader": dict(anchors=["shop", "gk", "wh"], seated=True, store=1, day=100, evening=100, night=50),
    "buyer": dict(anchors=["wh", "shop"], seated=True, store=3, day=100, evening=100, night=30),
    "crafter": dict(anchors=["wh", "shop"], seated=True, store=5, day=100, evening=60, night=0),
    "afk": dict(anchors=["wh", "gk"], seated=True, day=100, evening=100, night=100),
    "talker": dict(anchors=["gk"], night_anchors=["wh", "gk"], day=100, evening=100, night=30),
    "newbie": dict(route=["gk", "shop", "wh", "master"], run=True, day=100, evening=30, night=0),
    "lfp": dict(anchors=["gk"], day=50, evening=100, night=70),
    "runner": dict(route=["gk", "wh", "shop"], run=True, day=100, evening=70, night=0),
    "showoff": dict(anchors=["gk"], evening_anchors=["oly", "gk"], day=50, evening=100, night=70),
    "stroller": dict(route=["gk", "shop", "master", "oly", "fish", "wh"], run=False, day=100, evening=100, night=0),
    "nightowl": dict(anchors=["fish", "gk", "wh"], day=0, evening=20, night=100),
}
ROLE_RU = {"trader": "торговец на лавке", "buyer": "скупщик на лавке", "crafter": "крафтер на лавке", "afk": "AFK у склада",
           "talker": "болтун у гейткипера", "newbie": "новичок", "lfp": "ищет пати", "runner": "бегает по делам",
           "showoff": "понторез", "stroller": "гуляка", "nightowl": "полуночник"}
NEAR_RU = {"gk": "у ГК", "wh": "у склада", "shop": "у лавок", "master": "у гильдии", "oly": "у олимпиады", "fish": "у рыбаков", "square": "на площади"}
ANCHOR_RADIUS = 3500
ANCHOR_DZ = 600

# Ветки классов по расам: цепочка от стартового класса, набор оружия (fake_players.KITS или RAPIER ниже), пол (None — любой).
BRANCHES = {
    "HUMAN": [([0, 1, 2, 88], "dual", None), ([0, 1, 3, 89], "pole", None), ([0, 4, 5, 90], "tank", None), ([0, 4, 6, 91], "tank", None),
              ([0, 7, 8, 93], "dagger", None), ([0, 7, 9, 92], "bow", None), ([10, 11, 12, 94], "mage", None), ([10, 11, 13, 95], "mage", None),
              ([10, 11, 14, 96], "mage", None), ([10, 15, 16, 97], "healer", None), ([10, 15, 17, 98], "healer", None)],
    "ELF": [([18, 19, 20, 99], "tank", None), ([18, 19, 21, 100], "tank", None), ([18, 22, 23, 101], "dagger", None), ([18, 22, 24, 102], "bow", None),
            ([25, 26, 27, 103], "mage", None), ([25, 26, 28, 104], "mage", None), ([25, 29, 30, 105], "healer", None)],
    "DARK_ELF": [([31, 32, 33, 106], "tank", None), ([31, 32, 34, 107], "dual", None), ([31, 35, 36, 108], "dagger", None),
                 ([31, 35, 37, 109], "bow", None), ([38, 39, 40, 110], "mage", None), ([38, 39, 41, 111], "mage", None), ([38, 42, 43, 112], "healer", None)],
    "ORC": [([44, 45, 46, 113], "bigsword", None), ([44, 47, 48, 114], "fist", None), ([49, 50, 51, 115], "mage", None), ([49, 50, 52, 116], "mage", None)],
    "DWARF": [([53, 54, 55, 117], "blunt", None), ([53, 56, 57, 118], "blunt", None)],
    "KAMAEL": [([123, 125, 127, 131], "ancient", "M"), ([123, 125, 128, 132], "rapier", "M"), ([124, 126, 129, 133], "rapier", "F"),
               ([124, 126, 130, 134], "crossbow", "F")],
}
ATTACK_FALLBACK = {"CROSSBOW": "BOW", "RAPIER": "SWORD", "ANCIENTSWORD": "SWORD"}
KITS = dict(fp.KITS, rapier=dict(weapon=("RAPIER", "rhand", False), armor="LIGHT", shield=False, ai="FIGHTER", atk="RAPIER", aspd=500, range=40))
# Доли рас среди жителей городов (в стартовых деревнях — раса деревни, крафтеры — гномы).
RACE_WEIGHTS = [("HUMAN", 30), ("ELF", 18), ("DARK_ELF", 18), ("ORC", 14), ("DWARF", 12), ("KAMAEL", 8)]

# Ники по расе и полу с русским прозвищем (по нему фейка зовут в общем чате). Латиница и цифры, до 16 знаков.
NAMES = {
    ("HUMAN", "M"): "Sanyok:санёк Kolyan:колян Tolik:толик Grishka:гришка Dimon:димон Lyoha:лёха Maksimka:максимка Temych:тёмыч Zhekan:жекан "
                    "Vitalya:виталя Serega:серёга Borodach:бородач Kirpich:кирпич Rexar:рексар Paladin007:паладин DarkKnightRu:даркнайт "
                    "Gendalf:гендальф Vasyan:васян Petrovich:петрович Stepan:степан Ronin:ронин Arthas:артас Lordik:лордик "
                    "Nagibator:нагибатор Tankist:танкист Baton:батон Shurik:шурик Edik:эдик Fedya:федя Zhora:жора Valera:валера "
                    "Igorek:игорёк Mishanya:мишаня Antoha:антоха Kostyan:костян",
    ("HUMAN", "F"): "Ksyu:ксю Masyanya:масяня Alenka:алёнка Lisichka:лисичка Belka:белка Katyusha:катюша Nastya:настя Sonechka:сонечка "
                    "Marusya:маруся Lera:лера Yulka:юлька Dashka:дашка Witchy:ведьмочка Rozochka:розочка Ledi:леди Princessa:принцесса "
                    "Valkiriya:валькирия Sweety:свити Vika:вика Olya:оля Tanyusha:танюша Ninel:нинель",
    ("ELF", "M"): "Faelin:фаэлин Elandor:эландор Lirien:лириэн Silvanor:сильванор Aeron:эйрон Thalion:талион Legolasik:леголасик "
                  "Elrondik:элрондик Vetrogon:ветрогон Listik:листик Lunnyi:лунный Ariel:ариэль Celebor:келебор Feanor:феанор Glorfin:глорфин "
                  "Elrin:элрин Mirandil:мирандиль Sindar:синдар Veles:велес Zelenyi:зелёный",
    ("ELF", "F"): "Lirael:лираэль Elandra:эландра Arwenka:арвенка Sylvie:сильви Elfiyka:эльфийка Melodi:мелоди Nimfa:нимфа Rosinka:росинка "
                  "Zvezdochka:звёздочка Aelita:аэлита Galadriel:галадриэль Luthien:лютиэн Evenstar:эвенстар Fialka:фиалка Snezhinka:снежинка "
                  "Liana:лиана Miriel:мириэль Tindomiel:тиндомиэль",
    ("DARK_ELF", "M"): "Drowzy:дроузи Nightshade:найтшейд Mrakobes:мракобес Shadowfax:шэдоуфакс Vexor:вексор Zlodey:злодей Morok:морок "
                       "Kaelthas:кельтас Darkness:даркнесс Noctis:ноктис Ravenhold:рейвенхолд Sumrak:сумрак Ilidan:иллидан Mortis:мортис "
                       "Tenebris:тенебрис Grimm:гримм Vorlok:ворлок Chernysh:черныш Sinister:синистер Abyssal:абиссал",
    ("DARK_ELF", "F"): "Vexa:векса Lilith:лилит Morgana:моргана Nocturna:ноктюрна Vedma:ведьма Shilen:шилен Tenka:тенька Raven:рэйвен "
                       "Blackrose:блэкроуз Darkie:дарки Medusa:медуза Ezhevika:ежевика Viperia:випера Nyx:никс Kassandra:кассандра "
                       "Seline:селин Zmeyka:змейка Ofeliya:офелия",
    ("ORC", "M"): "Grom:гром Urgash:ургаш Brutus:брутус Thrall:тралл Kabanchik:кабанчик Muromets:муромец Bugai:бугай Krusher:крашер "
                  "Gorlum:горлум Zverugan:зверюга Rykach:рыкач Mordobil:мордобил Shkaf:шкаф Torbjorn:торбьёрн Oglobla:оглобля "
                  "Gorynych:горыныч Kolun:колун Bulldozer:бульдозер Durgash:дургаш Mamont:мамонт",
    ("ORC", "F"): "Grizelda:гризельда Urka:урка Klykastaya:клыкастая Bersa:берса Tigritsa:тигрица Valkira:валькира Shamanka:шаманка "
                  "Gorgona:горгона Bronya:броня Ogonyok:огонёк Rychalka:рычалка Medvedica:медведица",
    ("DWARF", "M"): "Borin:борин Gimlinator:гимлинатор Tolstyak:толстяк Molotok:молоток Kuvalda:кувалда Bolt:болт Gaika:гайка Zubilo:зубило "
                    "Ryzhiy:рыжий Baryzhnik:барыжник Kopatel:копатель Durin:дурин Balin:балин Kladovshik:кладовщик Spoiler:споилер "
                    "Kirka:кирка Nakovalnya:наковальня Bomburr:бомбур Grunt:грунт Tachka:тачка",
    ("DWARF", "F"): "Gaykushka:гаечка Pyshka:пышка Shesterenka:шестерёнка Kopilka:копилка Ryzhulya:рыжуля Bulochka:булочка Iskorka:искорка "
                    "Pchelka:пчёлка Monetka:монетка Zvonochek:звоночек Kubyshka:кубышка Krasotka:красотка",
    ("KAMAEL", "M"): "Kael:каэль Ravenwing:рэйвенвинг Odnokryl:однокрыл Soulreaver:соулривер Fenrir:фенрир Krylatyi:крылатый "
                     "Seraph:серафим Azazel:азазель Nephilim:нефилим Grifon:грифон Yastreb:ястреб Berserk:берсерк",
    ("KAMAEL", "F"): "Kaela:каэла Valkyr:валькир Krylyshko:крылышко Seraphine:серафина Arbaletchitsa:арбалетчица Strela:стрела "
                     "Vorona:ворона Ezhka:ёжка",
}
NAMES = {k: [tuple(x.split(":")) for x in v.split()] for k, v in NAMES.items()}

# Титулы (латиница: не все клиенты рисуют кириллицу в титуле). AFK — всегда, остальным — с шансом TITLE_CHANCE.
TITLES = {
    "afk": ["afk", "brb", "zzz", "AFK 5 min", "coffee", "ne trogat"],
    "trader": ["Trader", "Best prices", "Shop"],
    "buyer": ["Buyer", "Mats wanted"],
    "crafter": ["Crafter", "Craft 100%", "Maestro"],
    "showoff": ["Legend", "Top 1", "Untouchable", "PvP King"],
    "": ["Tank", "Farmer", "noob", "LF clan", "Lucky", "OnlyPvE", "Spoil pls", "Hunter", "Wanderer", "Nightwalker"],
}
TITLE_CHANCE = 25
# Шапки, уши и маски: только эти имена (без ивентовых, тестовых и «Shadow Item»).
HAIR_NAMES = {"Party Mask", "Red Party Mask", "Cat Ears", "Raccoon Ears", "Rabbit Ears", "Rabbit Ear", "Pirate's Eye Patch", "Pirate's Eyepatch",
              "Monocle", "Lady's Hair Pin", "Maiden's Hairpin", "Daisy Hairpin", "Forget-me-not Hairpin", "Little Angel Wings",
              "Red Horn of Victory", "Fairy Antennae", "Party Hat", "Blue Party Hat", "Feathered Hat", "Chapeau", "Artisan's Goggles",
              "Teddy Bear Hat", "Piggy Hat", "Jester Hat", "Wizard Hat", "Dapper Cap", "Romantic Chapeau", "Leather Cap", "First Mate's Hat",
              "Angel Halo", "Demon Circlet", "Pirate Hat", "Top Hat", "Sheep Hat", "Stylish Straw Hat", "Lineage II Baseball Cap",
              "Valkyrie Hat", "Half Face Mask", "Black Half Mask", "Gold-rimmed Glasses", "Ribbon Hairband", "Laborer Hat"}
SHOWOFF_HAIR = ("Angel Halo", "Demon Circlet", "Top Hat", "Feathered Hat", "Romantic Chapeau")
HAIR_CHANCE = 35
SHOWOFF_NAME_COLOR = 0x00DDFF  # BGR: золотисто-жёлтый

# Вывески лавок ({g} — грейд: D, C, B, A, S), до 29 знаков.
STORES = {
    1: ["WTS {g} mats", "WTS SS {g} / BSS {g}", "WTS EW{g} EA{g}", "WTS {g} armor parts", "WTS recipes 60%", "WTS cheap {g} weapons"],
    3: ["WTB Coarse Bone Powder", "WTB Animal Bone", "WTB Varnish", "WTB Stem", "WTB Cokes", "WTB EW{g}", "WTB Asofe", "WTB Mithril Ore",
        "WTB Thread", "WTB Iron Ore"],
    5: ["Craft SS {g} 100%", "Craft BSS {g}", "Craft mats 100%", "Craft Cokes/Asofe", "Craft {g} armor, your mats"],
}

# Торговые ряды: площадь Гирана, как на живом сервере (PhantomMarket.GIRAN_SQUARE). Дополнительные жители, у которых лавка настоящая
# (PhantomStores), селятся после всех городов: id и имена остальных не сдвигаются. Без TownFakesStores их нет в мире.
ROW_TOWN = 30080
ROW_SQUARE = (82698, 148638, -3470)
ROW_RADIUS = 700
ROW_ROSTER = ["trader", "trader", "buyer"] * 8 + ["trader", "trader"]
ROW_FIRST_SLOT = 100
ROW_LEVEL_MIN = 20  # в рядах есть и дешёвый грейд D, поэтому не с 40, как у остальных торговцев
ROW_STORES = {1: STORES[1] + ["WTS {g} armor", "WTS {g} jewels", "WTS dyes"], 3: STORES[3]}

# Что лежит в лавке под вывеской. Заряды и заточки — по id, они у хроник общие; остальное — по типу, грейду и цене.
GRADE_TAG = {"NONE": "D", "S80": "S", "S84": "S", "R": "S", "R95": "S", "R99": "S"}
SHOT_IDS = {"SS": [1463, 1464, 1465, 1466, 1467], "SpS": [2510, 2511, 2512, 2513, 2514], "BSS": [3948, 3949, 3950, 3951, 3952],
            "EW": [955, 951, 947, 729, 959], "EA": [956, 952, 948, 730, 960]}
GRADE_INDEX = {"D": 0, "C": 1, "B": 2, "A": 3, "S": 4}
PART_RX = re.compile(r"Design|Part|Fragment|Pattern|Fabric|Lining|Texture|Temper|Blade|Edge|Stockings|Gauntlet|Head|Shaft|Gemstone|Mold|Ornament|Pendant|Earring Gem|Ring Gem")
MATERIAL_PRICES = {"D": (20, 500), "C": (100, 1500), "B": (300, 4000), "A": (1000, 12000), "S": (3000, 100000)}
PART_PRICES = {"D": (500, 3000), "C": (2000, 8000), "B": (5000, 25000), "A": (15000, 80000), "S": (40000, 400000)}
NOT_FOR_SALE_RX = re.compile(r"Shadow|Event|Test|Common Item|Monster|Agathion|Formal|Wedding|Bracelet|Talisman|Cloak|Hat|Circlet")


def for_sale(d: fp.Data, it: dict) -> bool:
    """Обычная вещь, которую можно выставить: есть цена магазина, передаётся, не квестовая, не для NPC, без срока жизни."""
    return (int(it.get("price") or 0) > 0 and it.get("is_tradable", "true") != "false" and it.get("is_questitem", "false") != "true"
            and not d.npc_only(it) and "time" not in it and "duration" not in it and not NOT_FOR_SALE_RX.search(it["name"] or ""))


def store_goods(d: fp.Data, sign: str, g: str) -> list[int]:
    """id вещей под вывеску-шаблон sign (STORES, ROW_STORES) и грейд g. Пусто — лавка так и останется вывеской."""
    items = {i: it for i, it in d.items.items() if for_sale(d, it)}
    shots = [i for key in SHOT_KEYS.get(sign, ()) for i in [SHOT_IDS[key][GRADE_INDEX[g]]] if i in items]
    if SHOT_KEYS.get(sign):
        return shots
    if sign.startswith("WTB "):
        word = sign[4:]
        return sorted(i for i, it in items.items() if it["name"] == word)[:1]
    of_grade = lambda it: GRADE_TAG.get(it.get("crystal_type", "NONE"), it.get("crystal_type", "NONE")) == g and it.get("crystal_type", "NONE") != "NONE"
    between = lambda it, prices: prices[0] <= int(it["price"]) <= prices[1]
    material = {i: it for i, it in items.items() if it.get("etcitem_type") == "MATERIAL"}
    jewel = ("neck", "rear;lear", "rfinger;lfinger")
    if sign == "WTS {g} mats":
        return sorted(i for i, it in material.items() if not PART_RX.search(it["name"] or "") and between(it, MATERIAL_PRICES[g]))
    if sign == "WTS {g} armor parts":
        return sorted(i for i, it in material.items() if PART_RX.search(it["name"] or "") and between(it, PART_PRICES[g]))
    if sign == "WTS recipes 60%":
        recipes = sorted(i for i, it in items.items() if it.get("etcitem_type") == "RECIPE")
        return [i for i in recipes if "60%" in items[i]["name"]] or recipes
    if sign == "WTS cheap {g} weapons":
        found = sorted((i for i, it in items.items() if it["type"] == "Weapon" and d.plain(it) and of_grade(it)), key=lambda i: (int(items[i]["price"]), i))
        return sorted(found[: max(1, len(found) // 2)])
    if sign == "WTS {g} armor":
        return sorted(i for i, it in items.items() if it["type"] == "Armor" and d.plain(it) and of_grade(it) and it.get("bodypart") not in jewel)
    if sign == "WTS {g} jewels":
        return sorted(i for i, it in items.items() if it["type"] == "Armor" and d.plain(it) and of_grade(it) and it.get("bodypart") in jewel)
    if sign == "WTS dyes":
        return sorted(i for i, it in items.items() if it.get("etcitem_type") == "DYE")
    return []


SHOT_KEYS = {"WTS SS {g} / BSS {g}": ("SS", "SpS", "BSS"), "WTS EW{g} EA{g}": ("EW", "EA"), "WTB EW{g}": ("EW",)}


# Характеры для мозгов (launcher/l2hub/brain.py: race, class, level, place, activity, traits, style, bio, aliases).
TRAITS = {
    "trader": ["хитрый торгаш, торгуется за каждую адену", "добродушный барыга, любит поболтать о ценах",
               "серьёзный торговец, считает всё до последней адены"],
    "buyer": ["перекупщик, всегда ищет выгоду", "терпеливый скупщик, знает цены на все маты", "ворчливый скупщик: все задирают цены"],
    "crafter": ["гордый крафтер, хвастается шансом крафта", "немногословный мастер, уважает тех, кто носит свои маты",
                "весёлый крафтер, шутит про перегруз"],
    "afk": ["вечно отошедший игрок: отвечает редко и невпопад", "афкшник, который «на минутку» отошёл час назад"],
    "talker": ["болтун и сплетник, знает все новости сервера", "ворчун-ветеран, вечно сравнивает с C4",
               "дружелюбный советчик, любит помогать новичкам", "весельчак, шутит по любому поводу"],
    "newbie": ["новичок, всё спрашивает и всему удивляется", "робкий новичок, боится мобов крупнее зайца",
               "восторженный новичок, ему всё нравится"],
    "lfp": ["скучает соло и ищет пати", "командный игрок, без пати не качается"],
    "runner": ["вечно спешит, говорит коротко", "деловой фармер, у него всё по расписанию"],
    "showoff": ["понторез, хвастается шмотом и заточкой", "надменный топ сервера, смотрит на всех свысока"],
    "stroller": ["мечтатель, гуляет по городу ради красоты", "созерцатель, любит музыку городов и закаты"],
    "nightowl": ["полуночник, фармит по ночам, днём спит", "сова-задрот, ночью ему лучше всего"],
}
STYLES = ["маленькими буквами, со скобочками )))", "коротко, по делу", "с игровым сленгом: го, пт, фарм, дроп",
          "ворчливо, с «блин» и «короче»", "восторженно, много восклицаний", "лениво, растягивая слова", "вежливо и чуть старомодно",
          "с подколками и шутками", "односложно", "с многоточиями…"]
BIO = {
    "trader": ["Играет торговцем: весь день на лавке, вечером считает выручку.", "Бывший фармер, понял, что на торговле зарабатывает больше."],
    "buyer": ["Скупает маты для знакомого крафтера и для себя.", "Перекупщик: купил дешевле — продал дороже."],
    "crafter": ["Ремесленник, мечтает скрафтить топовую пушку.", "Крафтит на заказ, знает все рецепты своего грейда."],
    "afk": ["Зашёл в игру, сел у склада и ушёл по делам.", "Держит персонажа онлайн «чтобы был»."],
    "talker": ["Стоит у гейткипера и знает всё про всех.", "Старожил сервера, любит рассказывать, как было раньше."],
    "newbie": ["Первый раз в Lineage II, друг посоветовал.", "Только что создал персонажа и изучает мир."],
    "lfp": ["Не любит качаться соло, ищет компанию.", "Хочет в постоянную пати, но пока не берут."],
    "runner": ["Бегает между спотом, складом и магазинами.", "Фармит без остановки, в городе — только по делам."],
    "showoff": ["Топ сервера по шмоту, приходит в город показаться.", "Всё выфармил сам и не даёт об этом забыть."],
    "stroller": ["Гуляет по городам, любит атмосферу.", "Играет ради мира, а не ради уровня."],
    "nightowl": ["Работает днём, играет ночью.", "Студент, который ночью фармит вместо учёбы."],
}
ACTIVITY = {
    "trader": "сидишь на личной лавке {where} {in}: {store}",
    "buyer": "сидишь на лавке скупки {where} {in}: {store}",
    "crafter": "сидишь на лавке крафта {where} {in}: {store}",
    "afk": "стоишь афк {where} {in}, отошёл от компа",
    "talker": "стоишь {where} {in} и болтаешь",
    "newbie": "бегаешь по городу ({town}) и изучаешь, что где",
    "lfp": "стоишь {where} {in} и ищешь пати",
    "runner": "бегаешь {in} между складом и магазинами, вернулся с фарма",
    "showoff": "стоишь {where} {in} и красуешься шмотом",
    "stroller": "гуляешь {in}",
    "nightowl": "ночами стоишь {where} {in}, днём спишь",
}

INI_TEXT = """# ---------------------------------------------------------------------------
# Фейки в городах (L2Hub)
# ---------------------------------------------------------------------------
# Игроки-NPC во всех городах и деревнях: у каждого роль (торговец, скупщик и крафтер на лавке, AFK у склада, болтун
# у гейткипера, новичок, ищет пати, бегает по делам, понторез, гуляка, полуночник), распорядок по игровому времени
# (утро и день, вечер, ночь — ночью их меньше), короткие реплики в чате и реакции на события мира.
# Население и места — data/L2HubTownFakes.xml (tools/l2hub_town_fakes.py), реплики — data/L2HubTownFakesChat.xml.
# Работают только при EnableFakePlayers = True (config/Custom/FakePlayers.ini).

# Плотность, %: какая доля жителей выходит в мир (0 — без фейков в городах, 100 — все).
TownFakesDensity = 100

# Реплики в общем и торговом чате: не чаще раза в столько минут в одном городе (0 — молчат).
# Говорят, только если рядом живой игрок.
TownFakesChatMinutes = 3

# Реакции на события мира: суперчемпион, рейд охотников, эпик, сезонное событие, PvP-ивент.
TownFakesReactions = True

# Реплики пишут мозги фейков (FakePlayerBrain в FakePlayers.ini); False — только заготовки.
TownFakesBrain = True

# Лавки торговцев и скупщиков настоящие: по щелчку — окно личной лавки, вещи и адена настоящие, товар и цены конечны (PhantomStores),
# а на площади Гирана сидят дополнительные торговые ряды. False — только вывески, как раньше, и рядов нет.
TownFakesStores = True

# Память рынка: средняя цена по сделкам, спрос и дефицит сдвигают цены лавок при пополнении, торговцы обсуждают это в торговом чате,
# фейки торгуют между собой, файл log/L2HubMarket.json. Цены магазинов не меняются. False — цены лавок только от цены магазина и характера. Без TownFakesStores не работает.
TownFakesMarketMemory = True

# Самопроверка лавок при старте, пишет в лог «лавки, самопроверка: ...». Только на копии базы: двигает персонажа и меняет его вещи.
TownFakesStoreTest = False
"""


def tree(cid: str) -> Path:
    base = ROOT / "servers" / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if (d / "build.xml").is_file():
            return d
    sys.exit(f"нет исходников с build.xml в {base}")


def eol(data: bytes) -> bytes:
    return b"\r\n" if data.count(b"\r\n") > data.count(b"\n") // 2 else b"\n"


def roll(name: str, salt: str, percent: int) -> bool:
    return percent >= 100 or (percent > 0 and fp.rnd(name, salt) % 100 < percent)


def heading_to(x, y, tx, ty) -> int:
    return round(math.degrees(math.atan2(ty - y, tx - x)) * 65536 / 360) % 65536


class Town:
    def __init__(self, gk_id: int, gk: dict, geo: tn.Geo, spawns: list, npcs: dict):
        self.gk_id = gk_id
        self.kind, self.en, self.ru, self.ru_in, self.ru_near, self.levels, self.race = TOWNS[gk_id]
        self.x, self.y = gk["x"], gk["y"]
        self.z = geo.cell(gk["x"], gk["y"], gk["z"])[0]
        near = [s for s in spawns if math.dist((s["x"], s["y"]), (self.x, self.y)) < ANCHOR_RADIUS and abs(s["z"] - self.z) < ANCHOR_DZ]
        self.anchors = {"gk": (self.x, self.y, self.z)}
        for kind in ("wh", "shop", "master", "oly", "fish"):
            found = [s for s in near if anchor_kind(npcs.get(s["id"])) == kind]
            if found:  # ближайший к гейткиперу (у Essence бывают двойные спавны — равенство решают id и координаты)
                s = min(found, key=lambda s: (math.dist((s["x"], s["y"]), (self.x, self.y)), s["id"], s["x"], s["y"]))
                c = geo.cell(s["x"], s["y"], s["z"])
                self.anchors[kind] = (s["x"], s["y"], c[0] if c else s["z"])


def anchor_kind(npc: ET.Element | None) -> str | None:
    if npc is None:
        return None
    t, title, name = npc.get("type") or "", npc.get("title") or "", npc.get("name") or ""
    if t == "Warehouse" and "Warehouse" in title:
        return "wh"
    if t == "Merchant" and title in ("Grocer", "Blacksmith", "Weapon Merchant", "Armor Merchant", "Jeweler", "Accessory Merchant",
                                     "Magic Trader", "Wizard Equipment Merchant", "Trader", "Weapon Trader", "Armor Trader"):
        return "shop"
    if t.startswith("VillageMaster") or (t == "Trainer") or (title in ("Master", "Grand Master", "High Priest", "Grand Magister")):
        return "master"
    if t == "OlympiadManager" or name in ("Grand Olympiad Manager", "Olympiad Manager", "Monument of Heroes"):
        return "oly"
    if t == "Fisherman" or title == "Fishing Guild Member":
        return "fish"
    return None


def place_near(geo, spawns, anchor, taken, name, rmin, rmax) -> tuple[int, int, int] | None:
    """Точка у якоря (fake_players.place_near); якорь на постаменте — от открытой земли перед ним, как tools/town_npcs.py."""
    ax, ay, az = anchor
    try:
        return fp.place_near(geo, spawns, ax, ay, az, taken, name, rmin, rmax)
    except ValueError:
        pass
    for r in (48, 64, 80, 96):
        for a in range(0, 360, 30):
            sx, sy = round(ax + r * math.cos(math.radians(a))), round(ay + r * math.sin(math.radians(a)))
            c = geo.cell(sx, sy, az)
            if c and c[1] == tn.NSWE_ALL and abs(c[0] - az) <= tn.MAX_POINT_DZ:
                try:
                    return fp.place_near(geo, spawns, sx, sy, c[0], taken, name, rmin, rmax)
                except ValueError:
                    continue
    return None


def existing_names(src: Path) -> set[str]:
    """Имена фейков, фантомов и Танатоса этой сборки (кроме своих): шёпот по имени ушёл бы не тому."""
    data = src / "dist/game/data"
    names = set()
    for f in (data / "stats/npcs/custom").glob("*.xml"):
        if f.name != Path(NPCS).name:
            names |= {n.get("name") for n in ET.parse(f).getroot().iter("npc") if n.find("fakePlayer") is not None}
    ph = data / "L2HubPhantoms.xml"
    if ph.is_file():
        names |= {el.get("name") for el in ET.parse(ph).getroot().iter() if el.get("name")}
    return {n.lower() for n in names if n}


def taken_ids(src: Path) -> set[int]:
    ids = set()
    for f in (src / "dist/game/data/stats/npcs").rglob("*.xml"):
        if f.name != Path(NPCS).name:
            ids |= {int(m) for m in re.findall(rb'<npc id="(\d+)"', f.read_bytes())}
    return ids


def hair_items(d: fp.Data) -> list[tuple[int, str, str]]:
    """(id, слот fakePlayer, имя) шапок из HAIR_NAMES: у одного имени — меньший id."""
    slot = {"hair": "equipHair", "hairall": "equipHair", "dhair": "equipHair", "hair2": "equipHair2"}
    out = {}
    for i, it in sorted(d.items.items()):
        if it["type"] == "Armor" and it.get("bodypart") in slot and it["name"] in HAIR_NAMES and it["name"] not in out:
            out[it["name"]] = (i, slot[it["bodypart"]], it["name"])
    return sorted(out.values())


def pinned_names(cid: str, name: str) -> dict[int, tuple[str, str]]:
    """Кто уже живёт: {id: (имя, роль)} из текущего патча. Иначе новый охотник в датапаке переименовал бы половину города: имена берутся из пула по очереди."""
    patch = ROOT / "patches" / cid / name
    if not patch.is_file():
        return {}
    found = re.findall(rb'(?m)^\+\s*<fake npcId="(\d+)" name="([^"]+)" role="(\w+)"', patch.read_bytes())
    return {int(i): (n.decode("utf-8"), r.decode("ascii")) for i, n, r in found}


def build(cid: str, src: Path, pins: dict[int, tuple[str, str]]) -> tuple[dict[str, bytes], list[str]]:
    """Новые файлы патча {путь: содержимое} и отчёт."""
    data = src / "dist/game/data"
    geodata = ROOT / "servers" / cid / "server/game/data/geodata"
    if not geodata.is_dir():
        sys.exit(f"нет геодаты {geodata}")
    d = fp.Data(data)
    geo = tn.Geo(geodata)
    fp.GRADES = [(g, lvl) for g, lvl in fp.GRADE_LEVELS if g == "NONE" or d.armor_sets().get(g)]
    top_level = 85 if any(g == "S80" for g, _ in fp.GRADES) else 80
    spawns = tn.load_spawns(data)
    by_id = {}
    for s in spawns:
        by_id.setdefault(s["id"], []).append(s)
    npcs = {}
    for f in (data / "stats/npcs").rglob("*.xml"):
        if f.name != Path(NPCS).name:
            for n in ET.parse(f).getroot().iter("npc"):
                npcs[int(n.get("id"))] = n
    kamael = 123 in d.classes
    races = [(r, w) for r, w in RACE_WEIGHTS if r != "KAMAEL" or kamael]
    used_names = existing_names(src)
    busy_ids = taken_ids(src)
    hairs = hair_items(d)
    xsd = (data / "xsd/npcs.xsd").read_text(encoding="utf-8")
    report, towns, fakes = [], [], []
    taken: list[tuple] = []
    npc_id = NPC_ID_BASE
    pinned = {n for n, _ in pins.values()}
    known_names = {n[0]: n for names in NAMES.values() for n in names}
    goods_cache: dict[tuple[str, str], list[int]] = {}

    def settle(town, local, roster, first=0, row=False):
        """Поселить жителей города: roster — роли по порядку, first — номер первого (от него зависят расы и ветки), row — торговые ряды."""
        nonlocal npc_id
        gk_id = town.gk_id
        lo, hi = town.levels
        for slot, role in enumerate(roster, first):
            seed = f"{cid}:{gk_id}:{slot}:{role}"
            r = dict(ROLES[role], anchors=["square"]) if row else ROLES[role]
            # Раса, пол, ветка
            if role == "crafter":
                race = "DWARF"
            elif town.race and (role == "newbie" or fp.rnd(seed, "native") % 100 < 70):
                race = town.race
            else:
                total = sum(w for _, w in races)
                roll_ = fp.rnd(seed, "race") % total
                race = next(rc for rc, w in races if (roll_ := roll_ - w) < 0)
            branches = [b for b in BRANCHES[race] if all(c in d.classes for c in b[0])]
            if role == "crafter":
                branches = [b for b in branches if b[0][1] == 56] or branches
            chain, kit_key, sex = fp.pick(branches, seed, "branch")
            sex = sex or ("F" if fp.rnd(seed, "sex") % 100 < 45 else "M")
            # Имя
            pin = pins.get(npc_id + 1)
            if pin and pin[1] == role and pin[0] in known_names and pin[0].lower() not in used_names:
                name, alias = known_names[pin[0]]
            else:  # новый житель или прежнее имя занял охотник: шёпот по имени ушёл бы не тому
                free = lambda n: n[0].lower() not in used_names and n[0] not in pinned
                pool = [n for n in NAMES[(race, sex)] if free(n)]
                if not pool:  # свои кончились — ник с той же расой другого пола, затем любой (в игре так и бывает)
                    pool = [n for key in sorted(NAMES) for n in NAMES[key] if key[0] == race and free(n)]
                if not pool:
                    pool = [n for key in sorted(NAMES) for n in NAMES[key] if free(n)]
                if not pool:
                    sys.exit("кончились имена — добавь в NAMES")
                name, alias = fp.pick(pool, seed, "name")
                if pin:
                    report.append(f"{town.en}: {pin[0]} ({pin[1]}) → {name} ({role})")
            used_names.add(name.lower())
            # Уровень, класс
            if role == "newbie":
                level = lo + fp.rnd(name, "lvl") % max(1, min(hi, lo + 9) - lo)
            elif row:
                level = ROW_LEVEL_MIN + fp.rnd(name, "lvl") % (top_level - ROW_LEVEL_MIN - 1)
            elif role in ("trader", "buyer", "crafter"):
                level = 40 + fp.rnd(name, "lvl") % (top_level - 40 - 1)
            elif role == "showoff":
                level = max(hi, 76) + fp.rnd(name, "lvl") % max(1, top_level - max(hi, 76) + 1)
            else:
                level = lo + fp.rnd(name, "lvl") % max(1, hi - lo + 1)
            level = max(1, min(level, top_level))
            tier = fp.tier_index(level)
            chain = chain[: tier + 1]
            class_id = chain[-1]
            class_name = d.classes[class_id][0]
            kit = dict(KITS[kit_key])
            if race == "KAMAEL":
                kit["armor"] = "LIGHT"
            # Шмот
            gr = fp.grade(level)
            if level < 20:
                gear = d.initial_gear(chain[0])
                weapon = gear.get("rhand")
                armor = {k: v for k, v in gear.items() if k in ("head", "chest", "legs", "gloves", "feet")}
                shield = gear.get("lhand")
                enchant = 0
            else:
                armor = d.armor_set(gr, kit["armor"], name)
                if role == "showoff":
                    weapon = d.best_weapon(gr, kit)
                    enchant = 16 + fp.rnd(name, "ench") % 5
                else:
                    try:
                        weapon = d.weapon(gr, kit, name)
                    except ValueError:
                        weapon = d.hunter_weapon(gr, kit, name)
                    enchant = 0 if level < 40 else fp.rnd(name, "ench") % (4 if level < 61 else 8 if level < 76 else 13)
                shield = armor.get("shield") if kit["shield"] else None
            attrs = [f'classId="{class_id}"', f'hair="{fp.rnd(name, "hair") % ((6 if sex == "F" else 4) + 1)}"',
                     f'hairColor="{fp.rnd(name, "hc") % 4}"', f'face="{fp.rnd(name, "face") % 3}"']
            if weapon:
                attrs.append(f'equipRHand="{weapon}"')
            if shield:
                attrs.append(f'equipLHand="{shield}"')
            for slot_, key in (("head", "equipHead"), ("chest", "equipChest"), ("legs", "equipLegs"), ("gloves", "equipGloves"), ("feet", "equipFeet")):
                if slot_ in armor:
                    attrs.append(f'{key}="{armor[slot_]}"')
            hair = None
            if role == "showoff":
                hair = next((h for want in SHOWOFF_HAIR for h in hairs if h[2] == want), None)
            elif role != "newbie" and roll(name, "hairc", HAIR_CHANCE):
                hair = fp.pick(hairs, name, "hairi")
            if hair:
                attrs.append(f'{hair[1]}="{hair[0]}"')
            if enchant:
                attrs.append(f'weaponEnchantLevel="{enchant}" armorEnchantLevel="{min(enchant, 6 if role != "showoff" else 8)}"')
            if role == "showoff":
                attrs.append(f'nameColor="{SHOWOFF_NAME_COLOR}"')
                if town.kind == "capital" and slot == ROSTER[town.kind].index("showoff"):
                    attrs.append('hero="true"')
            store = ""
            goods = []
            if r.get("seated"):
                attrs.append('sitting="true"')
            if r.get("store"):
                g = GRADE_TAG.get(gr, gr)
                sign = fp.pick((ROW_STORES if row else STORES)[r["store"]], name, "store")
                store = sign.replace("{g}", g)
                attrs.append(f'privateStoreType="{r["store"]}" privateStoreMessage={quoteattr(store)}')
                if r["store"] in (1, 3):
                    goods = goods_cache.setdefault((sign, g), store_goods(d, sign, g))
            attrs = [a for a in attrs if f'name="{a.split("=")[0]}"' in xsd]
            pool_t = TITLES.get(role) if role in ("afk", "showoff") else (TITLES.get(role) if roll(name, "tit", TITLE_CHANCE) else None)
            if pool_t is None and roll(name, "tit2", TITLE_CHANCE) and role not in ("newbie",):
                pool_t = TITLES[""]
            title = fp.pick(pool_t, name, "title") if pool_t else ""
            # Места на части суток
            spots = {}
            route = r.get("route")
            home = None
            if route:
                pts = []
                # Точки — у NPC города; путь между ними сервер ищет сам (PathFinding в GeoEngine.ini у всех хроник включён).
                for kind in route:
                    if kind in town.anchors:
                        p = place_near(geo, local, town.anchors[kind], taken + pts, f"{name}:{kind}", 60, 200)
                        if p:
                            pts.append((*p, kind))
                if len(pts) >= 2:
                    home = ("route", pts)
                    taken.extend(p[:3] for p in pts)
            if home is None:
                for kind in dict.fromkeys((r.get("anchors") or []) + ["gk"]):  # нет своих якорей или места у них — у гейткипера
                    if kind in town.anchors:
                        p = place_near(geo, local, town.anchors[kind], taken, name, 80 if r.get("seated") else 60, ROW_RADIUS if row else 260)
                        if p:
                            ax, ay, _ = town.anchors[kind]
                            home = ("stand", (*p, kind, heading_to(p[0], p[1], ax, ay)))
                            taken.append(p)
                            break
            if home is None:
                report.append(f"{town.en}: {name} ({role}) — не нашлось места, пропущен")
                continue

            def alt(anchors_key):
                for kind in r.get(anchors_key) or []:
                    if kind in town.anchors and kind != (home[1][3] if home[0] == "stand" else None):
                        p = place_near(geo, local, town.anchors[kind], taken, f"{name}:{anchors_key}", 60, 260)
                        if p:
                            taken.append(p)
                            ax, ay, _ = town.anchors[kind]
                            return ("stand", (*p, kind, heading_to(p[0], p[1], ax, ay)))
                return home

            for phase in ("day", "evening", "night"):
                if roll(name, phase, r[phase]):
                    spots[phase] = alt(f"{phase}_anchors") if r.get(f"{phase}_anchors") else home
            if not spots:
                spots["day"] = home
            npc_id += 1
            if npc_id > NPC_ID_MAX:
                sys.exit("кончились id NPC 80300–80499")
            if npc_id in busy_ids:
                sys.exit(f"id {npc_id} уже занят другим NPC")
            radius, height = fp.COLLISION[(race, sex)]
            where_kind = home[1][0][3] if home[0] == "route" else home[1][3]
            fakes.append(dict(npc_id=npc_id, name=name, alias=alias, race=race, sex=sex, level=level, class_name=class_name, kit=kit_key, kit_d=kit,
                              role=role, town=town, attrs=attrs, title=title, store=store, spots=spots, radius=radius, height=height,
                              where=where_kind, seed=seed, gr=gr, goods=goods, row=row))

    for gk_id in TOWNS:
        gks = by_id.get(gk_id, [])
        if len(gks) != 1 or geo.cell(gks[0]["x"], gks[0]["y"], gks[0]["z"]) is None:
            report.append(f"{TOWNS[gk_id][1]}: нет в этой хронике (гейткипер {gk_id} найден {len(gks)} раз) — пропущен")
            continue
        town = Town(gk_id, gks[0], geo, spawns, npcs)
        # Спавны вокруг города: точки фейков — не дальше ANCHOR_RADIUS + 360 от гейткипера (у Essence в мире 32 тыс. спавнов).
        local = [s for s in spawns if math.dist((s["x"], s["y"]), (town.x, town.y)) < ANCHOR_RADIUS + 1000]
        towns.append(town)
        settle(town, local, ROSTER[town.kind])
    for town in towns:
        if town.gk_id == ROW_TOWN:
            cell = geo.cell(ROW_SQUARE[0], ROW_SQUARE[1], ROW_SQUARE[2])
            town.anchors["square"] = (ROW_SQUARE[0], ROW_SQUARE[1], cell[0] if cell else ROW_SQUARE[2])
            local = [s for s in spawns if math.dist((s["x"], s["y"]), ROW_SQUARE[:2]) < ROW_RADIUS + 1000]
            settle(town, local, ROW_ROSTER, ROW_FIRST_SLOT, True)
    # Ранги для плотности: в каждом городе — равномерно по 0–99 в случайном (детерминированном) порядке. Ряды — своим счётом: у остальных ранги не меняются.
    for town in towns:
        for row in (False, True):
            mine = sorted((f for f in fakes if f["town"] is town and f["row"] is row), key=lambda f: fp.rnd(f["name"], "rank"))
            for i, f in enumerate(mine):
                f["rank"] = (i * 100 + 50) // len(mine)
                f["shift"] = fp.rnd(f["name"], "shift") % 91
    stores = [f for f in fakes if f["role"] in ("trader", "buyer")]
    empty = sorted({f["store"] for f in stores if not f["goods"]})
    if empty:
        report.append(f"вывески без товара: {', '.join(empty)}")
    report.append(f"лавок {len(stores)}, из них в рядах {sum(1 for f in stores if f['row'])}; вывесок {len(goods_cache)}: " + "; ".join(
        f"{sign.replace('{g}', g)} {len(ids)}" for (sign, g), ids in sorted(goods_cache.items())))
    return render(cid, src, d, towns, fakes), report + summary(towns, fakes)


def render(cid: str, src: Path, d: fp.Data, towns: list, fakes: list) -> dict[str, bytes]:
    xsd = (src / "dist/game/data/xsd/npcs.xsd").read_text(encoding="utf-8")
    block = next((b for b in re.findall(r"<xs:simpleType.*?</xs:simpleType>", xsd, re.S) if 'value="DUALFIST"' in b), "")
    attack_types = set(re.findall(r'<xs:enumeration value="(\w+)"', block))
    npc_xml = ['<?xml version="1.0" encoding="UTF-8"?>',
               '<list xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../../../xsd/npcs.xsd">',
               "\t<!-- L2Hub: фейки в городах. Сгенерировано tools/l2hub_town_fakes.py, правки — в его таблицах. -->"]
    for f in fakes:
        title = f' title={quoteattr(f["title"])}' if f["title"] else ""
        npc_xml.append(f'\t<!-- {f["name"]}: {f["class_name"]} {f["level"]}, {ROLE_RU[f["role"]]}, {f["town"].ru} -->')
        npc_xml.append(f'\t<npc id="{f["npc_id"]}" level="{f["level"]}" type="Folk" name="{f["name"]}"{title}>')
        npc_xml.append(f'\t\t<race>{f["race"]}</race>')
        npc_xml.append(f'\t\t<sex>{"FEMALE" if f["sex"] == "F" else "MALE"}</sex>')
        stats = fp.stats_xml(d.npcs[20555], f["kit"], f["kit_d"], f["level"]).rstrip("\n")
        atk = f["kit_d"]["atk"]
        if attack_types and atk not in attack_types:  # у Epilogue в npcs.xsd нет оружия камаэлей — ближайший разрешённый тип
            stats = stats.replace(f'type="{atk}"', f'type="{ATTACK_FALLBACK.get(atk, "SWORD")}"')
        npc_xml.append(stats)
        npc_xml.append('\t\t<status talkable="false" undying="true" attackable="false" />')
        npc_xml.append(f'\t\t<fakePlayer {" ".join(f["attrs"])} />')
        npc_xml.append(f'\t\t<collision>\n\t\t\t<radius normal="{f["radius"]}" />\n\t\t\t<height normal="{f["height"]}" />\n\t\t</collision>')
        npc_xml.append(f'\t\t<parameters>\n\t\t\t<param name="aliases" value="{f["alias"]}" />\n\t\t</parameters>')
        npc_xml.append("\t</npc>")
    npc_xml += ["</list>", ""]

    pop = ['<?xml version="1.0" encoding="UTF-8"?>',
           "<!-- L2Hub: фейки в городах (скрипт custom/L2HubTownFakes). Сгенерировано tools/l2hub_town_fakes.py.",
           "     rank — порядок для плотности (TownFakesDensity: в мире те, у кого rank меньше), shift — сдвиг распорядка в игровых минутах.",
           "     day — утро и день 6:00–18:00, evening — вечер 18:00–24:00, night — ночь 0:00–6:00; нет элемента — в эту часть суток не в игре.",
           "     Место — точка (стоит или сидит, heading — куда смотрит) или маршрут из point (ходит по кругу, run — бегом).",
           "     goods — id вещей под вывеску лавки (PhantomStores), row — торговые ряды: без TownFakesStores их нет в мире. -->",
           "<list>"]
    for town in towns:
        pop.append(f'\t<town id="{town.gk_id}" en={quoteattr(town.en)} ru={quoteattr(town.ru)} ruIn={quoteattr(town.ru_in)} '
                   f'ruNear={quoteattr(town.ru_near)} x="{town.x}" y="{town.y}" z="{town.z}">')
        for f in (f for f in fakes if f["town"] is town):
            store = f' store={quoteattr(f["store"])}' if f["store"] else ""
            goods = f' goods="{",".join(map(str, f["goods"]))}"' if f["goods"] else ""
            row = ' row="1"' if f["row"] else ""
            pop.append(f'\t\t<fake npcId="{f["npc_id"]}" name="{f["name"]}" role="{f["role"]}" cls={quoteattr(f["class_name"])} '
                       f'rank="{f["rank"]}" shift="{f["shift"]}"{store}{goods}{row}>')
            for phase in ("day", "evening", "night"):
                spot = f["spots"].get(phase)
                if spot is None:
                    continue
                if spot[0] == "stand":
                    x, y, z, kind, h = spot[1]
                    pop.append(f'\t\t\t<{phase} near="{kind}" x="{x}" y="{y}" z="{z}" heading="{h}" />')
                else:
                    pts = spot[1]
                    run = "true" if ROLES[f["role"]].get("run") else "false"
                    pop.append(f'\t\t\t<{phase} near="{pts[0][3]}" run="{run}">')
                    pop += [f'\t\t\t\t<point x="{x}" y="{y}" z="{z}" /> <!-- {kind} -->' for x, y, z, kind in pts]
                    pop.append(f"\t\t\t</{phase}>")
            pop.append("\t\t</fake>")
        pop.append("\t</town>")
    pop += ["</list>", ""]

    personas = {}
    for f in fakes:
        town, role = f["town"], f["role"]
        where = NEAR_RU.get(f["where"], "")
        activity = ACTIVITY[role].format(where=where, **{"in": town.ru_in}, town=town.ru, store=f["store"])
        race_ru = fp.RACE_RU[f["race"]][1 if f["sex"] == "F" else 0]
        personas[f["name"]] = dict(race=race_ru, sex=f["sex"], **{"class": f["class_name"]}, level=f["level"], place=town.ru,
                                   activity=activity, traits=fp.pick(TRAITS[role], f["name"], "traits"),
                                   style=fp.pick(STYLES, f["name"], "style"), bio=fp.pick(BIO[role], f["name"], "bio"),
                                   aliases=f["alias"], role=ROLE_RU[role], townFake=True)
    nl = eol((src / EOL_FROM).read_bytes())
    script = (HERE / "L2HubTownFakes.java").read_bytes().replace(b"\r\n", b"\n").replace(b"\n", nl)
    chat = (HERE / "L2HubTownFakesChat.xml").read_bytes().replace(b"\r\n", b"\n")
    ini_nl = eol((src / "dist/game/config/Custom/FakePlayers.ini").read_bytes()).decode()
    return {
        SCRIPT: script,
        NPCS: "\n".join(npc_xml).encode("utf-8"),
        POPULATION: "\n".join(pop).encode("utf-8"),
        CHAT: chat,
        PERSONAS: (json.dumps(personas, ensure_ascii=False, indent=1) + "\n").encode("utf-8"),
        INI: INI_TEXT.replace("\n", ini_nl).encode("utf-8"),
    }


def summary(towns: list, fakes: list) -> list[str]:
    out = []
    for town in towns:
        mine = [f for f in fakes if f["town"] is town]
        counts = {p: sum(1 for f in mine if p in f["spots"]) for p in ("day", "evening", "night")}
        routes = sum(1 for f in mine if any(s[0] == "route" for s in f["spots"].values()))
        out.append(f"{town.en:<20} фейков {len(mine):>2} (день {counts['day']:>2}, вечер {counts['evening']:>2}, ночь {counts['night']:>2}), "
                   f"ходоков {routes}, якоря: {', '.join(k for k in ('wh', 'shop', 'master', 'oly', 'fish') if k in town.anchors)}")
    total = {p: sum(1 for f in fakes if p in f["spots"]) for p in ("day", "evening", "night")}
    out.append(f"всего {len(fakes)} (день {total['day']}, вечер {total['evening']}, ночь {total['night']}), "
               f"id {fakes[0]['npc_id']}–{fakes[-1]['npc_id']}" if fakes else "фейков нет")
    return out


def build_patch(plan: dict) -> tuple[bytes, list[str]]:
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


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid, write, patch_only = sys.argv[1], "--write" in sys.argv, "--patch-only" in sys.argv
    verbose = "-v" in sys.argv
    src = tree(cid)
    own = sorted((ROOT / "patches" / cid).glob("*-town-fakes.patch"))
    name = own[0].name if own else NAME
    applied = any((src / rel).is_file() for rel in FILES)
    if applied and not (ROOT / "patches" / cid / name).is_file():
        sys.exit(f"в дереве уже есть файлы фейков в городах, а patches/{cid}/{name} нет — нечем снять")
    current = {rel: None for rel in FILES}
    if patch_only:  # дерево могло быть собрано с другой версией патча: снимать нечего, все файлы патча — новые
        upper = patch_layers.later(cid, name, FILES)
        applied = False
    elif applied:
        peeled, upper = patch_layers.peel(cid, src, name, FILES)
        current = {rel: peeled.get(rel) for rel in FILES}
    else:
        upper = []
    if upper:
        sys.exit(f"поздние патчи трогают файлы фейков: {[p.name for p in upper]} — так не задумано")
    if any(v is not None for v in current.values()):
        sys.exit(f"файлы фейков есть и без патча: {[k for k, v in current.items() if v is not None]}")
    files, report = build(cid, src, pinned_names(cid, name))
    plan = {rel: (None, data) for rel, data in files.items()}
    patch, bad = build_patch(plan)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    print("\n".join(report if verbose else [line for line in report if "пропущен" in line] + report[-1:]))
    print(f"{cid}: файлов в патче {len(plan)}, наложение на копию — байт в байт")
    if "--dump" in sys.argv:  # посмотреть файлы, не трогая дерево: --dump <папка>
        out = Path(sys.argv[sys.argv.index("--dump") + 1])
        for rel, data in files.items():
            (out / rel).parent.mkdir(parents=True, exist_ok=True)
            (out / rel).write_bytes(data)
    if patch_only:
        (ROOT / "patches" / cid / name).write_bytes(patch)
        print(f"записано: patches/{cid}/{name} (дерево не тронуто)")
    if write:
        (ROOT / "patches" / cid / name).write_bytes(patch)
        for rel, data in files.items():
            (src / rel).parent.mkdir(parents=True, exist_ok=True)
            (src / rel).write_bytes(data)
        print(f"записано: patches/{cid}/{name} и дерево {src.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
