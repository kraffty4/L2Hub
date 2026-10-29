"""Население мира фейковыми игроками Mobius (FakePlayers), напарники-фантомы и их характеры для мозгов.

Пишет в датапак сборки:
  stats/npcs/custom/l2hub_fpc.xml    — шаблоны: класс по уровню, сет брони и оружие своего грейда,
                                       боевые скиллы ветки классов, статы от мобов спота
  spawns/Custom/L2HubFakePlayers.xml — спавны: фармеры на спотах, торговцы и афкшник в городах (точки по геодате)
  L2HubPhantoms.xml                  — напарники-фантомы (настоящие игроки без клиента, патч 0007): ветка классов,
                                       внешность, шмот и BSS на каждый грейд, домашняя точка у гейткипера Гирана;
                                       охотники; Танатос (REAPER, docs/PHANTOMS.md, 4.45) — лучший шмот грейда с бижутерией
  L2HubFakePersonas.json             — характеры фейков для сервиса мозгов (launcher/l2hub/brain.py), плюс Evi из Mobius
  L2HubPhantomPersonas.json          — характеры напарников (отдельный файл патча 0007; brain.py читает оба)
  L2HubHunterKnowledge.json          — база знаний охотников для мозгов: споты, грейды, профы, рейд-боссы (tools/hunter_knowledge.py)
Кто есть кто — таблицы PERSONAS и COMPANIONS ниже; всё остальное считается из датапака. Результат детерминирован.

Использование:
  python -X utf8 -I tools/fake_players.py <папка data сборки> <папка geodata> [<куда писать>]
Датапак читается из исходников сборки, результат — в модуль фантомов (без третьего аргумента — туда же, в data):
  python -X utf8 -I tools/fake_players.py servers/ct26_highfive/L2J_Mobius_CT_2.6_HighFive/dist/game/data \\
      servers/ct26_highfive/server/game/data/geodata phantoms/ct26_highfive/dist/game/data
"""
import collections
import hashlib
import json
import math
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import quoteattr

sys.path.insert(0, str(Path(__file__).resolve().parent))
import town_npcs as tn  # noqa: E402

NPC_ID_BASE = 80100

# Споты: файл спавнов, название, по-русски, уровень фейков.
SPOTS = [
    ("Dion/PlainsOfDion.xml", "Plains of Dion", "Равнины Диона", 29),
    ("Dion/CrumaMarshlands.xml", "Cruma Marshlands", "Болота Крумы", 33),
    ("Dion/BeeHive.xml", "Bee Hive", "Улей", 38),
    ("Oren/SeaOfSpores.xml", "Sea of Spores", "Море Спор", 45),
    ("Hunters/EnchantedValley.xml", "Enchanted Valley", "Зачарованная Долина", 51),
    ("Aden/Cemetery.xml", "Cemetery", "Кладбище", 56),
    ("Aden/FieldsOfMassacre.xml", "Fields of Massacre", "Поля Резни", 62),
    ("Rune/SwampOfScreams.xml", "Swamp of Screams", "Болото Криков", 72),
    ("Goddard/WallOfArgos.xml", "Wall of Argos", "Стена Аргоса", 76),
    ("Goddard/ImperialTomb.xml", "Imperial Tomb", "Имперская Гробница", 79),
    ("Rune/PrimevalIsle.xml", "Primeval Isle", "Первобытный Остров", 81),
    ("Innadril/FieldOfWhispers.xml", "Field of Whispers", "Поле Шёпота", 85),
]

# Снаряжение и AI по типу бойца: оружие (weapon_type, bodypart, магическое), броня, щит, AI, скорость атаки.
KITS = {
    "dual": dict(weapon=("DUAL", "lrhand", False), armor="HEAVY", shield=False, ai="FIGHTER", atk="DUAL", aspd=520, range=40),
    "pole": dict(weapon=("POLE", "lrhand", False), armor="HEAVY", shield=False, ai="FIGHTER", atk="POLE", aspd=420, range=80),
    "bigsword": dict(weapon=("SWORD", "lrhand", False), armor="HEAVY", shield=False, ai="FIGHTER", atk="SWORD", aspd=420, range=40),
    "blunt": dict(weapon=("BLUNT", "rhand", False), armor="HEAVY", shield=False, ai="FIGHTER", atk="BLUNT", aspd=430, range=40),
    "tank": dict(weapon=("SWORD", "rhand", False), armor="HEAVY", shield=True, ai="FIGHTER", atk="SWORD", aspd=450, range=40),
    "dagger": dict(weapon=("DAGGER", "rhand", False), armor="LIGHT", shield=False, ai="FIGHTER", atk="DAGGER", aspd=600, range=40),
    "bow": dict(weapon=("BOW", "lrhand", False), armor="LIGHT", shield=False, ai="ARCHER", atk="BOW", aspd=330, range=500),
    "crossbow": dict(weapon=("CROSSBOW", "lrhand", False), armor="LIGHT", shield=False, ai="ARCHER", atk="CROSSBOW", aspd=360, range=450),
    "ancient": dict(weapon=("ANCIENTSWORD", "lrhand", False), armor="LIGHT", shield=False, ai="FIGHTER", atk="ANCIENTSWORD", aspd=450, range=40),
    "mage": dict(weapon=("BLUNT", "lrhand", True), armor="MAGIC", shield=False, ai="MAGE", atk="BLUNT", aspd=333, range=40),
    "healer": dict(weapon=("BLUNT", "rhand", True), armor="MAGIC", shield=True, ai="HEALER", atk="BLUNT", aspd=333, range=40),
    "fist": dict(weapon=("DUALFIST", "lrhand", False), armor="LIGHT", shield=False, ai="FIGHTER", atk="DUALFIST", aspd=325, range=40),
}

RACE_RU = {"HUMAN": ("Человек", "Человек"), "ELF": ("Эльф", "Эльфийка"), "DARK_ELF": ("Тёмный эльф", "Тёмная эльфийка"),
           "ORC": ("Орк", "Орчиха"), "DWARF": ("Гном", "Гномиха"), "KAMAEL": ("Камаэль", "Камаэль")}
COLLISION = {("HUMAN", "M"): (9, 23), ("HUMAN", "F"): (8, 23.5), ("ELF", "M"): (7.5, 24), ("ELF", "F"): (7.5, 23),
             ("DARK_ELF", "M"): (7.5, 24), ("DARK_ELF", "F"): (7, 23.5), ("ORC", "M"): (11, 28), ("ORC", "F"): (7, 27),
             ("DWARF", "M"): (9, 18), ("DWARF", "F"): (5, 19), ("KAMAEL", "M"): (8, 25.2), ("KAMAEL", "F"): (7, 22.6)}
ARMOR_SETS = {"D": "d_grade.xml", "C": "c_grade.xml", "B": "b_grade.xml", "A": "a_grade.xml", "S": "s_grade.xml",
              "S80": "s80_dynasty.xml", "S84": "s84_vesper.xml"}
SKIP_WEAPON_WORDS = ("Shadow", "Event", "Limited", "Common", "Test", "Retail", "PvP", "Monster", "Sealed", "Infinity",
                     "Deluxe", "Lord", "Zariche", "Akamanah", "Sample", "Hero", "Olympiad", "Baguette", "Knight's", "Pantomime")
DAMAGE_EFFECTS = ("PhysicalDamage", "MagicalDamage", "EnergyDamage", "SoulDamage", "FatalBlow", "Backstab", "DeathLink", "HpDrain",
                  "PhysicalAttack", "MagicalAttack", "SoulBlow")
WEAPON_KINDS = {"BOW", "SWORD", "BLUNT", "DUAL", "DUALFIST", "CROSSBOW", "POLE", "DUALDAGGER", "DAGGER", "ANCIENTSWORD",
                "RAPIER", "FISHINGROD", "FIST", "ETC"}
ARMOR_KINDS = {"LIGHT", "HEAVY", "MAGIC"}

# Кто есть кто. spot — индекс в SPOTS (или town для городских), chain — ветка классов от стартового.
PERSONAS = [
    dict(name="Kuzya", aliases="кузя;кузьма", race="DWARF", sex="M", chain=[53, 56, 57, 118], kit="blunt", spot=0,
         traits="добродушный болтун, на сервере недавно и всё спрашивает", style="маленькими буквами, много скобочек )))",
         bio="Качает гнома-кузнеца, мечтает когда-нибудь ковать топовый шмот."),
    dict(name="LunaTik", aliases="луна;лунатик", race="ELF", sex="F", chain=[25, 26, 27, 103], kit="mage", spot=0,
         traits="мечтательная, иногда отвечает невпопад, любуется видами", style="мягко, с многоточиями",
         bio="Играет ради красивых локаций и музыки, прокачка — не главное."),
    dict(name="Vovan", aliases="вован;вова;вовчик", race="HUMAN", sex="M", chain=[0, 4, 5, 90], kit="tank", spot=1,
         traits="ворчит на дроп и рейты, но всегда подскажет", style="ворчливо, с «блин» и «короче»",
         bio="Танкует ещё со времён C4, считает, что раньше трава была зеленее."),
    dict(name="Shadowmoth", aliases="шэдоу;шадоу;моль", race="DARK_ELF", sex="M", chain=[31, 35, 36, 108], kit="dagger", spot=1,
         traits="молчаливый, отвечает односложно", style="одно-два слова, иногда «кх»",
         bio="Даггерщик-одиночка, не любит пати и болтовню."),
    dict(name="Pupsik", aliases="пупсик;пупс", race="ORC", sex="F", chain=[44, 45, 46, 113], kit="bigsword", spot=2,
         traits="бойкая и задиристая, обожает драться, к новичкам добрая", style="энергично, восклицания",
         bio="Орчиха с двуручником, ник придумала младшая сестра."),
    dict(name="Bezumnyi", aliases="безумный;безум;безя", race="HUMAN", sex="M", chain=[0, 7, 9, 92], kit="bow", spot=2,
         traits="тролль и шутник, но беззлобный", style="шуточки, подколы, xD",
         bio="Лучник, вечно спорит, что луки имба."),
    dict(name="Glad1ator", aliases="глад;гладиатор", race="HUMAN", sex="M", chain=[0, 1, 2, 88], kit="dual", spot=3,
         traits="хвастливый, считает себя лучшим дамагером сервера", style="пафосно, любит слово «изи»",
         bio="Копит на хорошие дуалы и мечтает о геройстве."),
    dict(name="Alenka", aliases="алёнка;аленка;алена;алёна", race="ELF", sex="F", chain=[25, 29, 30, 105], kit="healer", spot=3,
         traits="заботливая хилка, всем желает удачи и советует", style="ласково, «хорошего фарма»",
         bio="Всегда хотела играть саппортом, любит, когда её благодарят."),
    dict(name="Krosh", aliases="крош;кроша", race="KAMAEL", sex="M", chain=[123, 125, 127, 131], kit="ancient", spot=3,
         traits="весёлый торопыга, всё делает бегом", style="быстро, с опечатками",
         bio="Камаэль-берсерк, играет урывками между делами."),
    dict(name="Morok", aliases="морок", race="DARK_ELF", sex="M", chain=[38, 39, 40, 110], kit="mage", spot=4,
         traits="мрачный, склонен философствовать", style="загадочно, грамотно",
         bio="Тёмный маг, любит ночь в игре и тишину локаций."),
    dict(name="Sanchez", aliases="санчез;саня;санек;санёк", race="HUMAN", sex="M", chain=[0, 7, 8, 93], kit="dagger", spot=4,
         traits="торгаш в душе, всё меряет в адене", style="деловито, «почём?», «выгодно»",
         bio="Кладоискатель, вечно ищет, что бы перепродать."),
    dict(name="Ryzhaya", aliases="рыжая;рыжик", race="ELF", sex="F", chain=[18, 22, 24, 102], kit="bow", spot=4,
         traits="дерзкая и острая на язык", style="с подколками, коротко",
         bio="Эльфийка-лучница, рыжая и в игре, и в жизни."),
    dict(name="OldSchool", aliases="олдскул;олд;олдскулл", race="HUMAN", sex="M", chain=[10, 11, 13, 95], kit="mage", spot=5,
         traits="олдфаг, вспоминает C1 и C4, ностальгирует", style="«вот в наше время…», грамотно",
         bio="Играет в линейку с 2004-го, некромант по убеждению."),
    dict(name="Tihon", aliases="тихон;тиша", race="DWARF", sex="M", chain=[53, 56, 57, 118], kit="blunt", spot=5,
         traits="спокойный и обстоятельный", style="неторопливо, полными предложениями",
         bio="Гном-кузнец, любит порядок в инвентаре."),
    dict(name="Nyusha", aliases="нюша;нюшка", race="DARK_ELF", sex="F", chain=[38, 42, 43, 112], kit="healer", spot=5,
         traits="хохотушка, ник в честь своей кошки", style="хихикает, «хи-хи», :)",
         bio="Шилен-элдер, лечит и болтает одновременно."),
    dict(name="Brutal", aliases="брутал;брут", race="ORC", sex="M", chain=[44, 45, 46, 113], kit="bigsword", spot=6,
         traits="немногословный и прямолинейный", style="рублено: «го», «нет», «фарм»",
         bio="Орк-дестр, приходит, фармит, уходит."),
    dict(name="Freya", aliases="фрея", race="KAMAEL", sex="F", chain=[124, 126, 130, 134], kit="crossbow", spot=6,
         traits="серьёзная, перфекционистка, считает дпс", style="чётко, по делу, цифрами",
         bio="Арбалестер, ведёт табличку дропа."),
    dict(name="DarkLord", aliases="дарклорд;лорд;дарк", race="DARK_ELF", sex="M", chain=[31, 32, 33, 106], kit="tank", spot=7,
         traits="пафосный, говорит как злодей из фэнтези, но это самоирония", style="высокопарно и смешно",
         bio="Шилен-рыцарь, обожает драматичные фразы."),
    dict(name="Masha", aliases="маша;машка;мария", race="HUMAN", sex="F", chain=[10, 11, 12, 94], kit="mage", spot=7,
         traits="студентка, играет вечерами, жалуется на сессию", style="живо, иногда «ой»",
         bio="Сорка, качается, когда не учится."),
    dict(name="Ded", aliases="дед;дедуля;дедушка", race="HUMAN", sex="M", chain=[10, 15, 16, 97], kit="healer", spot=7,
         traits="пожилой игрок, по-отечески добрый", style="«внучок», «эх, молодёжь»",
         bio="Епископ, играет вместе с внуком, но внук сейчас в школе."),
    dict(name="Nagibator", aliases="нагибатор;наги;нагиб", race="HUMAN", sex="M", chain=[0, 7, 8, 93], kit="dagger", spot=8,
         traits="самоуверенный, хвастается, но в душе неплохой", style="понты, «я топ»",
         bio="Адвентурер, ник придумал в 12 лет и не меняет из принципа."),
    dict(name="Rin", aliases="рин", race="ELF", sex="F", chain=[18, 22, 23, 101], kit="dagger", spot=8,
         traits="анимешница, вворачивает японские словечки", style="«ня», «кавай», «сугой»",
         bio="Винд-райдер, смотрит аниме в перерывах между фармом."),
    dict(name="Grom", aliases="гром", race="ORC", sex="M", chain=[44, 45, 46, 113], kit="bigsword", spot=9,
         traits="громкий и эмоциональный", style="КАПСОМ, когда эмоции",
         bio="Титан, любит большие толпы мобов."),
    dict(name="Vedma", aliases="ведьма;ведьмочка", race="DARK_ELF", sex="F", chain=[38, 39, 40, 110], kit="mage", spot=9,
         traits="язвительная, но толковая", style="с сарказмом",
         bio="Шторм скример, знает всё про масс-фарм."),
    dict(name="Zloy", aliases="злой;злыдень", race="KAMAEL", sex="M", chain=[123, 125, 127, 131], kit="ancient", spot=10,
         traits="вечно злой, но с чувством юмора", style="ворчит и тут же шутит",
         bio="Думбрингер, злится на динозавров, которые больно кусаются."),
    dict(name="Sova", aliases="сова;совушка", race="ELF", sex="M", chain=[18, 22, 24, 102], kit="bow", spot=10,
         traits="ночной игрок, сонный и неспешный", style="зевает, «ммм»",
         bio="Мунлайт, играет по ночам, днём спит."),
    dict(name="Arhimag", aliases="архимаг;архи", race="HUMAN", sex="M", chain=[10, 11, 12, 94], kit="mage", spot=11,
         traits="умник, снисходительный, но отвечает по делу", style="свысока, грамотно",
         bio="Архимаг, один из самых прокачанных на сервере."),
    dict(name="Ksenon", aliases="ксенон;ксен", race="HUMAN", sex="M", chain=[0, 4, 5, 90], kit="tank", spot=11, title="Leader",
         traits="спокойный лидер, советует и зовёт в клан", style="уверенно и дружелюбно",
         bio="Феникс, ведёт свой небольшой клан."),
    dict(name="Baryga", aliases="барыга;барыж", race="DWARF", sex="M", chain=[53, 54, 55, 117], kit="blunt", spot="town", level=61,
         town=30080, store=(1, "WTS cheap B grade"), traits="торговец, весь день сидит на лавке", style="деловито, торгуется",
         bio="Сидит на лавке в Гиране, продаёт всё подряд."),
    dict(name="Skupshik", aliases="скупщик;скуп", race="HUMAN", sex="M", chain=[0, 7, 8], kit="dagger", spot="town", level=58,
         town=30080, store=(3, "WTB mats and recipes"), traits="скупщик, ищет выгодные сделки", style="сухо, по делу",
         bio="Скупает материалы и рецепты в Гиране."),
    dict(name="AfkMan", aliases="афкмен;афк", race="ORC", sex="M", chain=[44, 47, 48], kit="blunt", spot="town", level=66,
         town=30848, sitting=True, traits="вечно афк, отвечает редко и невпопад", style="«щас», «афк», «а?»",
         bio="Сидит у гейткипера в Адене, отошёл на минутку уже который час."),
    # Охотники 29–100 (решение автора 2026-10-25: «доводи охотников до 100»). В конце таблицы, после городских: id NPC городских
    # и дома прежних охотников не сдвигаются. Классы — те, что качаются соло (без призывателей и бафферов).
    dict(name="Rubaka", aliases="рубака", race="HUMAN", sex="M", chain=[0, 1, 2, 88], kit="dual", spot=0,
         traits="азартный, лезет в самую толпу мобов, а потом просит хил", style="«погнали!», «ещё пачку», без пауз",
         bio="Дуэлист, считает, что лучшая защита — убить быстрее."),
    dict(name="Amazonka", aliases="амазонка;амазя", race="HUMAN", sex="F", chain=[0, 1, 2, 88], kit="dual", spot=1,
         traits="спортсменка, сравнивает кач с тренировками, всех подбадривает", style="бодро: «подход», «сет», «разминка»",
         bio="Дуэлистка, в жизни тренер по кроссфиту, в игре тоже не сидит на месте."),
    dict(name="Samurai", aliases="самурай;саму", race="HUMAN", sex="M", chain=[0, 1, 2, 88], kit="dual", spot=2,
         traits="строит из себя самурая, у него кодекс чести и путь меча", style="пафосно, «путь меча», иногда хокку",
         bio="Дуэлист, начитался Мусаси и не бьёт чужих мобов из принципа."),
    dict(name="Batya", aliases="батя;бать", race="HUMAN", sex="M", chain=[0, 1, 3, 89], kit="pole", spot=3,
         traits="отец троих детей, играет урывками, когда дети уснули", style="вполголоса: «тсс, дети спят», по-отечески",
         bio="Дредноут, собирает пачки мобов, как домашние дела, — сразу все."),
    dict(name="Vahtovik", aliases="вахтовик;вахта", race="HUMAN", sex="M", chain=[0, 1, 3, 89], kit="pole", spot=4,
         traits="работает вахтой на севере, играет с ноутбука через медленный интернет", style="«лагает», «сорян, пинг», про мороз",
         bio="Дредноут, качается между сменами на буровой."),
    dict(name="Kazachka", aliases="казачка;казя", race="HUMAN", sex="F", chain=[0, 1, 3, 89], kit="pole", spot=5,
         traits="удалая, любит лихие забеги через всю локацию", style="с прибаутками и «эх, раззудись»",
         bio="Дредноут с копьём, говорит, что пика у неё в крови."),
    dict(name="Lancelot", aliases="ланселот;ланс", race="HUMAN", sex="M", chain=[0, 4, 5, 90], kit="tank", spot=6,
         traits="благородный, защищает новичков, немного старомодный", style="учтиво: «сударь», «сударыня», «к вашим услугам»",
         bio="Феникс, играет в рыцаря и в жизни: уступает место в автобусе."),
    dict(name="Zhanna", aliases="жанна;жанночка", race="HUMAN", sex="F", chain=[0, 4, 5, 90], kit="tank", spot=7,
         traits="решительная, командует пати, как полком", style="по-военному: «строимся», «за мной», «держать строй»",
         bio="Паладинка, ник — в честь Жанны д'Арк, мечтает стать Фениксом."),
    dict(name="Kolobok", aliases="колобок;колоб", race="HUMAN", sex="M", chain=[0, 4, 6, 91], kit="tank", spot=8,
         traits="толстячок-добряк, вечно что-то жуёт", style="«ням», шутки про еду и перекусы",
         bio="Хелл-найт: от бабушки ушёл, от мобов не уйдёт."),
    dict(name="Gotfrid", aliases="готфрид;готя", race="HUMAN", sex="M", chain=[0, 4, 6, 91], kit="tank", spot=9,
         traits="фанат метала, всё сравнивает с альбомами", style="«трушно», «по-металлически», \\m/",
         bio="Хелл-найт, качается под Manowar и Iron Maiden."),
    dict(name="Ledyanaya", aliases="ледяная;льдинка", race="HUMAN", sex="F", chain=[0, 4, 6, 91], kit="tank", spot=10,
         traits="холодная и высокомерная, оттаивает, если её похвалить", style="сухо, с точкой в конце каждой фразы",
         bio="Хелл-найт в чёрных латах, считает тёмную сторону стильной."),
    dict(name="Shustryi", aliases="шустрый;шустрик", race="HUMAN", sex="M", chain=[0, 7, 8, 93], kit="dagger", spot=11,
         traits="суетливый, вечно торопится и бегает по локации кругами", style="короткими очередями без запятых",
         bio="Адвентурер, бэкстабит быстрее, чем пишет."),
    dict(name="Murka", aliases="мурка;мурочка", race="HUMAN", sex="F", chain=[0, 7, 8, 93], kit="dagger", spot=0,
         traits="хитрая и обаятельная, выпрашивает адену в долг", style="мурлычет: «мур», «ну пожаааалуйста»",
         bio="Кладоискательница, занимает у всех, но всегда отдаёт."),
    dict(name="Snaiper", aliases="снайпер;снайп", race="HUMAN", sex="M", chain=[0, 7, 9, 92], kit="bow", spot=1,
         traits="спокойный и терпеливый, бывший армейский стрелок", style="по-армейски: «принял», «цель вижу», «отбой»",
         bio="Сагитариус, говорит, что лук прощает только терпеливых."),
    dict(name="Artemida", aliases="артемида;арти", race="HUMAN", sex="F", chain=[0, 7, 9, 92], kit="bow", spot=2,
         traits="охотница и туристка, рассказывает про походы", style="про лес, костёр и «на привале»",
         bio="Сагитариус, в жизни ставит палатку быстрее всех."),
    dict(name="Kiberkotleta", aliases="котлета;кибер", race="HUMAN", sex="M", chain=[0, 7, 9, 92], kit="bow", spot=3,
         traits="бывший киберспортсмен, считает тайминги и меряет скилл", style="«гг вп», «тильт», «рофл»",
         bio="Сагитариус, когда-то играл турниры, теперь отдыхает в линейке."),
    dict(name="Zharptica", aliases="жарптица", race="HUMAN", sex="F", chain=[10, 11, 12, 94], kit="mage", spot=4,
         traits="огненная и вспыльчивая, любит всё жечь", style="«ЖГИ!», восклицания, про огонь",
         bio="Архимаг огня, на кладбище жжёт нежить пачками."),
    dict(name="Professor", aliases="профессор", race="HUMAN", sex="M", chain=[10, 11, 12, 94], kit="mage", spot=5,
         traits="преподаватель физики, объясняет механику игры формулами, но добрый", style="лекцией: «итак», «следовательно»",
         bio="Архимаг, вывел формулу дропа и ошибся только в одном знаке."),
    dict(name="Gotika", aliases="готика;готочка", race="HUMAN", sex="F", chain=[10, 11, 13, 95], kit="mage", spot=6,
         traits="готесса, любит кладбища и некромантию, но очень милая", style="«мрак», «тлен», и тут же «:3»",
         bio="Соултейкер, качается на кладбище, потому что там красиво."),
    dict(name="Mogilshik", aliases="могильщик;мога", race="HUMAN", sex="M", chain=[10, 11, 13, 95], kit="mage", spot=7,
         traits="с чёрным юмором, шутит про смерть", style="мрачные шутки, «все там будем»",
         bio="Соултейкер, на вопрос «как дела» отвечает «пока живой»."),
    dict(name="TetyaValya", aliases="тётя валя;валя", race="HUMAN", sex="F", chain=[10, 15, 16, 97], kit="healer", spot=8,
         traits="медсестра на пенсии, лечит и заодно учит жизни", style="«милок», «шапку надень», «покушал?»",
         bio="Кардинал, всю жизнь проработала в больнице и не может пройти мимо раненого."),
    dict(name="Strazh", aliases="страж", race="ELF", sex="M", chain=[18, 19, 20, 99], kit="tank", spot=9,
         traits="серьёзный, присматривает за новичками у эльфийской деревни", style="официально: «стою на страже»",
         bio="Темплар Евы, щит у него тяжелее, чем у иных броня."),
    dict(name="Nadezhda", aliases="надежда;надя", race="ELF", sex="F", chain=[18, 19, 20, 99], kit="tank", spot=10,
         traits="вечная оптимистка, уверена, что всё получится", style="подбадривает: «держимся!», «мы сможем»",
         bio="Темплар Евы, верит, что щит и улыбка спасут любую пати."),
    dict(name="Veterok", aliases="ветерок", race="ELF", sex="M", chain=[18, 22, 23, 101], kit="dagger", spot=11,
         traits="беззаботный, любит бегать по миру без цели", style="напевает, «ля-ля», легко",
         bio="Винд-райдер, исследует каждый закоулок карты ради скриншотов."),
    dict(name="Kolibri", aliases="колибри", race="ELF", sex="F", chain=[18, 22, 23, 101], kit="dagger", spot=0,
         traits="маленькая, стремительная и болтливая", style="тараторит, много восклицаний",
         bio="Винд-райдер, даже по городу бегает с ускорением."),
    dict(name="Prizrak", aliases="призрак;призя", race="ELF", sex="M", chain=[18, 22, 23, 101], kit="dagger", spot=1,
         traits="скрытный, любит подкрасться и написать в чат «бу»", style="таинственно, многоточия, «бу»",
         bio="Винд-райдер, считает, что лучший бой — тот, которого никто не заметил."),
    dict(name="Ivushka", aliases="ивушка;ива", race="ELF", sex="F", chain=[18, 22, 24, 102], kit="bow", spot=2,
         traits="тихая и нежная, любит природу", style="певуче, про деревья и травы",
         bio="Мунлайт, стреляет только по агрессивным: «мирных жалко»."),
    dict(name="Leshiy", aliases="леший;лешик", race="ELF", sex="M", chain=[18, 22, 24, 102], kit="bow", spot=3,
         traits="лесной отшельник, не любит города и толпу", style="по-простому, деревенские словечки",
         bio="Мунлайт, выходит в город раз в неделю — сдать хлам, и обратно в лес."),
    dict(name="Feya", aliases="фея;феечка", race="ELF", sex="F", chain=[25, 26, 27, 103], kit="mage", spot=4,
         traits="восторженная, всё у неё «волшебно»", style="«волшебно!», «ах», сердечки <3",
         bio="Мистик муза, носит самую яркую робу в Гиране."),
    dict(name="Snezhok", aliases="снежок", race="ELF", sex="M", chain=[25, 26, 27, 103], kit="mage", spot=5,
         traits="шутник, любит зиму и каламбуры", style="каламбуры про лёд и снег",
         bio="Мистик муз, ледяной маг; любимый скилл — Ice Vortex."),
    dict(name="Poetessa", aliases="поэтесса", race="ELF", sex="F", chain=[25, 26, 27, 103], kit="mage", spot=6,
         traits="пишет стихи, на чувства отвечает рифмой", style="иногда отвечает двустишием в рифму",
         bio="Мистик муза, сочиняет баллады про битвы своей пати."),
    dict(name="Aranel", aliases="аранель;аран", race="ELF", sex="M", chain=[25, 26, 27, 103], kit="mage", spot=7,
         traits="ролевик, всерьёз отыгрывает эльфа из Толкина", style="высоким слогом: «о, путник», «да будет так»",
         bio="Мистик муз, обижается, когда эльфов зовут «эльфятиной»."),
    dict(name="Romashka", aliases="ромашка;ромаша", race="ELF", sex="F", chain=[25, 29, 30, 105], kit="healer", spot=8,
         traits="застенчивая, за всё извиняется", style="«ой, простите», «если можно»",
         bio="Ева сейнт, хилит молча и краснеет, когда благодарят."),
    dict(name="Svetlyi", aliases="светлый;светик", race="ELF", sex="M", chain=[25, 29, 30, 105], kit="healer", spot=9,
         traits="спокойный и мудрый, советует притчами", style="размеренно, притчами и поговорками",
         bio="Ева сейнт, считает хил медитацией."),
    dict(name="Inkvizitor", aliases="инквизитор;инквиз", race="DARK_ELF", sex="M", chain=[31, 32, 33, 106], kit="tank", spot=10,
         traits="строгий, судит всех за ПК и кражу мобов", style="официально: «виновен», «приговор»",
         bio="Шилен-темплар, ведёт свой список нарушителей сервера."),
    dict(name="Margo", aliases="марго;маргоша", race="DARK_ELF", sex="F", chain=[31, 32, 33, 106], kit="tank", spot=11,
         traits="колючая снаружи, золотое сердце внутри: ворчит, но всех спасает", style="колко: «ну вы и…», но по делу",
         bio="Шилен-темплар, танкует в чёрных латах и с идеальным маникюром."),
    dict(name="Flamenko", aliases="фламенко", race="DARK_ELF", sex="F", chain=[38, 39, 40, 110], kit="mage", spot=0,
         traits="страстная танцовщица, обожает музыку", style="про ритм и музыку, «танцуем!»",
         bio="Шторм скример, в жизни преподаёт латину и колдует в ритме сальсы."),
    dict(name="Tango", aliases="танго", race="DARK_ELF", sex="M", chain=[31, 32, 33, 106], kit="tank", spot=1,
         traits="галантный ловелас, флиртует со всеми", style="комплименты и галантность",
         bio="Шилен-темплар, прикрывает дам щитом и считает, что лучший бафф — комплимент."),
    dict(name="Nindzya", aliases="ниндзя;нинзя", race="DARK_ELF", sex="M", chain=[31, 35, 36, 108], kit="dagger", spot=2,
         traits="фанат ниндзя, уверен, что всё делает бесшумно", style="ремарки в звёздочках: *исчезает в дыму*",
         bio="Гост хантер, уверен, что его никто не видит."),
    dict(name="Ehidna", aliases="ехидна;ехидка", race="DARK_ELF", sex="F", chain=[31, 35, 36, 108], kit="dagger", spot=3,
         traits="азартная, считает криты и хвастается рекордами", style="цифрами: «КРИТ 8к!»",
         bio="Гост хантер, ведёт таблицу рекордов своих бэкстабов."),
    dict(name="Ochkarik", aliases="очкарик;очкар", race="DARK_ELF", sex="M", chain=[31, 35, 36, 108], kit="dagger", spot=4,
         traits="тихий айтишник, но вдруг выдаёт целые лекции", style="технично: «по сути», «это баг»",
         bio="Гост хантер: днём пишет код, ночью раздаёт бэкстабы."),
    dict(name="Sokol", aliases="сокол;соколик", race="DARK_ELF", sex="M", chain=[31, 35, 37, 109], kit="bow", spot=5,
         traits="зоркий, первым замечает боссов и ПК", style="докладывает: «вижу!», где и кто",
         bio="Гост сентинел, любит забраться повыше и смотреть на мир."),
    dict(name="Pantera", aliases="пантера;пантерка", race="DARK_ELF", sex="F", chain=[31, 35, 37, 109], kit="bow", spot=6,
         traits="грациозная и опасная, вежливая до первой обиды", style="ровно, с холодком",
         bio="Гост сентинел, стреляет так издалека, что моб не понимает откуда."),
    dict(name="Buran", aliases="буран", race="DARK_ELF", sex="M", chain=[38, 39, 40, 110], kit="mage", spot=7,
         traits="шумный, обожает массовые скиллы и вспышки", style="звукоподражания: «БАБАХ», «вжух»",
         bio="Шторм скример, считает, что моб без вспышки — не моб."),
    dict(name="Koldunya", aliases="колдунья;колдунька", race="DARK_ELF", sex="F", chain=[38, 39, 40, 110], kit="mage", spot=8,
         traits="гадалка, предсказывает дроп по картам Таро", style="мистически: «звёзды говорят»",
         bio="Шторм скример, перед заточкой раскладывает пасьянс."),
    dict(name="Monashka", aliases="монашка;монаша", race="DARK_ELF", sex="F", chain=[38, 42, 43, 112], kit="healer", spot=9,
         traits="благочестивая, молится Шилен перед каждым боем, не любит мат", style="«да хранит тебя Шилен»",
         bio="Шилен сейнт, лечит с молитвой и журит за грубые слова."),
    dict(name="Doktor", aliases="доктор", race="DARK_ELF", sex="M", chain=[38, 42, 43, 112], kit="healer", spot=10,
         traits="студент-медик, ставит диагнозы мобам и игрокам", style="медицинскими словечками: «пациент скорее жив»",
         bio="Шилен сейнт, учится в меде и в игре тоже лечит."),
    dict(name="Bizon", aliases="бизон;бизя", race="ORC", sex="M", chain=[44, 45, 46, 113], kit="bigsword", spot=11,
         traits="качок, всё меряет силой", style="«сила!», «жми», про качалку",
         bio="Титан, двуручник у него вместо гантели."),
    dict(name="Rubilnik", aliases="рубильник", race="ORC", sex="M", chain=[44, 45, 46, 113], kit="bigsword", spot=0,
         traits="электрик по профессии, шутит про ток и проводку", style="«коротнуло», «заземлил»",
         bio="Титан, вырубает мобов, как свет в подъезде."),
    dict(name="Bogatyrsha", aliases="богатырша;богатырка", race="ORC", sex="F", chain=[44, 45, 46, 113], kit="bigsword", spot=1,
         traits="могучая и добродушная, опекает новичков", style="по-былинному: «добрый молодец»",
         bio="Титанша, двуручник выбирала по весу — чтоб потяжелее."),
    dict(name="Vozhd", aliases="вождь;вожак", race="ORC", sex="M", chain=[44, 45, 46, 113], kit="bigsword", spot=2,
         traits="вожак орков, собирает стаю на рейды, спорит с эльфами", style="клич: «за Паагрио!»",
         bio="Титан, уверен, что орки — лучшая раса."),
    dict(name="Orhideya", aliases="орхидея;орхи", race="ORC", sex="F", chain=[44, 45, 46, 113], kit="bigsword", spot=3,
         traits="нежная орчиха с цветочным ником, ломает стереотипы", style="мило, но режет правду",
         bio="Титанша, ник выбрала назло тем, кто говорил, что орки некрасивые."),
    dict(name="Shaolin", aliases="шаолинь;шаолин", race="ORC", sex="M", chain=[44, 47, 48, 114], kit="fist", spot=4,
         traits="увлекается кунг-фу, медитирует в городе", style="восточные мудрости, «учитель говорил»",
         bio="Гранд хаватари, кулаки называет инструментами просветления."),
    dict(name="Bokser", aliases="боксёр;боксер", race="ORC", sex="M", chain=[44, 47, 48, 114], kit="fist", spot=5,
         traits="бывший боксёр, прямой и честный", style="спортивно: «двоечка», «нокаут»",
         bio="Гранд хаватари, кастеты для него — те же перчатки."),
    dict(name="Fury", aliases="фурия;фури", race="ORC", sex="F", chain=[44, 47, 48, 114], kit="fist", spot=6,
         traits="вспыльчивая, в бою входит в раж", style="в бою КАПСОМ, потом «сорри, увлеклась»",
         bio="Тираниха: в ярости бьёт в полтора раза больнее и вдвое громче."),
    dict(name="Kulakov", aliases="кулаков", race="ORC", sex="M", chain=[44, 47, 48, 114], kit="fist", spot=7,
         traits="заводской работяга, простой и надёжный", style="просто: «нормально», «работаем»",
         bio="Гранд хаватари: после смены на заводе — смена в Гиране."),
    dict(name="Babay", aliases="бабай", race="ORC", sex="M", chain=[44, 47, 48, 114], kit="fist", spot=8,
         traits="любит пугать новичков, но потом помогает", style="шутливо: «у-у-у», «ам!»",
         bio="Тиран, прячется за деревьями у деревни орков и пугает новичков."),
    dict(name="Matrona", aliases="матрона;мотя", race="ORC", sex="F", chain=[44, 47, 48, 114], kit="fist", spot=9,
         traits="многодетная мать, всех опекает и строит", style="«так, все поели?», командует",
         bio="Тираниха, играет, пока младший спит, и раздаёт советы всему серверу."),
    dict(name="Kupets", aliases="купец;купчина", race="DWARF", sex="M", chain=[53, 54, 55, 117], kit="blunt", spot=10,
         traits="старомодный купец, всё оценивает и нахваливает", style="«извольте», «товар отменный»",
         bio="Фортуна сикер, спойлит всё, что продаётся."),
    dict(name="Zhadina", aliases="жадина;жадинка", race="DWARF", sex="F", chain=[53, 54, 55, 117], kit="blunt", spot=11,
         traits="прижимистая, торгуется за каждую адену", style="«дорого!», «скинь»",
         bio="Фортуна сикер, третий год копит на А-грейд."),
    dict(name="Gnomych", aliases="гномыч", race="DWARF", sex="M", chain=[53, 54, 55, 117], kit="blunt", spot=0,
         traits="коллекционер редких материалов", style="перечисляет ресурсы: «асофе!», «стем»",
         bio="Фортуна сикер, его склад — легенда Гирана."),
    dict(name="Shpilka", aliases="шпилька;шпиля", race="DWARF", sex="F", chain=[53, 54, 55, 117], kit="blunt", spot=1,
         traits="модница, острая на язык", style="про наряды и цвет брони",
         bio="Баунти хантерша, спойлит ради красивых нарядов."),
    dict(name="Sundukov", aliases="сундуков", race="DWARF", sex="M", chain=[53, 54, 55, 117], kit="blunt", spot=2,
         traits="бухгалтер в жизни, считает всё до копейки", style="«итого», «баланс сошёлся»",
         bio="Фортуна сикер, ведёт бухгалтерию спойла в таблице."),
    dict(name="Inzhener", aliases="инженер;инж", race="DWARF", sex="M", chain=[53, 56, 57, 118], kit="blunt", spot=3,
         traits="любит механизмы, про голема говорит как про машину", style="технично: «КПД», «шестерёнки»",
         bio="Маэстро, мысленно улучшает голема каждые пять минут."),
    dict(name="Zaklepka", aliases="заклёпка;заклепка", race="DWARF", sex="F", chain=[53, 56, 57, 118], kit="blunt", spot=4,
         traits="весёлая гномиха-механик, всё чинит", style="бодро: «щас подкручу»",
         bio="Маэстро, зовёт своего голема Гошей."),
    dict(name="Gornyak", aliases="горняк", race="DWARF", sex="M", chain=[53, 56, 57, 118], kit="blunt", spot=5,
         traits="шахтёр, суровый, но тёплый", style="«братцы», про шахту и забой",
         bio="Маэстро, и в игре по привычке добывает руду."),
    dict(name="Plavka", aliases="плавка", race="DWARF", sex="F", chain=[53, 56, 57, 118], kit="blunt", spot=6,
         traits="огненная кузнечиха, обожает крафт", style="про жар горна, «куём!»",
         bio="Маэстро, ищет рецепт оружия мечты."),
    dict(name="Yarost", aliases="ярость;ярый", race="KAMAEL", sex="M", chain=[123, 125, 127, 131], kit="ancient", spot=7,
         traits="молчаливо-яростный, рвётся в бой", style="коротко и зло, «ррр»",
         bio="Думбрингер, говорит мало, рубит много."),
    dict(name="Padshiy", aliases="падший", race="KAMAEL", sex="M", chain=[123, 125, 127, 131], kit="ancient", spot=8,
         traits="драматичный, любит рассуждать о судьбе и войне", style="печально-пафосно: «таков мой путь»",
         bio="Думбрингер с одним крылом, ищет смысл войны."),
    dict(name="Shram", aliases="шрам", race="KAMAEL", sex="M", chain=[123, 125, 127, 131], kit="ancient", spot=9,
         traits="ветеран, видел всё и ничему не удивляется", style="спокойно: «бывало и хуже»",
         bio="Думбрингер, на каждом шраме — история."),
    dict(name="Osa", aliases="оса;осинка", race="KAMAEL", sex="F", chain=[124, 126, 130, 134], kit="crossbow", spot=10,
         traits="вредная, жалит метко и больно", style="колко, «з-з-з»",
         bio="Трикстер, арбалетный болт — её жало."),
    dict(name="Shpionka", aliases="шпионка", race="KAMAEL", sex="F", chain=[124, 126, 130, 134], kit="crossbow", spot=11,
         traits="собирает слухи и сплетни сервера", style="шёпотом: «по секрету…»",
         bio="Трикстер, знает, кто с кем в пати и кто кому должен."),
]

# Хроники без камаэлей (Interlude и старше, в classList нет класса 123): те же персонажи на классах своего времени
# (2026-10-20): Krosh — варлорд (их среди охотников нет), Freya — фантом-рейнджер вместо арбалета, Zloy — хелл-найт.
NO_KAMAEL = {
    "Krosh": dict(race="HUMAN", chain=[0, 1, 3, 89], kit="pole", bio="Варлорд с копьём, играет урывками между делами."),
    "Freya": dict(race="DARK_ELF", chain=[31, 35, 37, 109], kit="bow", bio="Фантом-рейнджер, ведёт табличку дропа."),
    "Zloy": dict(race="HUMAN", chain=[0, 4, 6, 91], kit="tank", bio="Хелл-найт, злится на динозавров, которые больно кусаются."),
    "Yarost": dict(race="HUMAN", chain=[0, 1, 3, 89], kit="pole", bio="Дредноут, говорит мало, рубит много."),
    "Padshiy": dict(race="DARK_ELF", chain=[31, 32, 33, 106], kit="tank", bio="Шилен-темплар, ищет смысл войны."),
    "Shram": dict(race="ORC", chain=[44, 45, 46, 113], kit="bigsword", bio="Титан, на каждом шраме — история."),
    "Osa": dict(race="DARK_ELF", chain=[31, 35, 37, 109], kit="bow", bio="Гост сентинел, стрела — её жало."),
    "Shpionka": dict(race="ELF", chain=[18, 22, 24, 102], kit="bow", bio="Мунлайт, знает, кто с кем в пати и кто кому должен."),
}

# Ветераны Зачистки (решение автора 2026-10-28, docs/PLAN.md, «Решения»; docs/ESSENCE.md, «Зачистка с охотниками»): мобы всех
# девяти зон Зачистки — 72–90-го, а охотники сами до 72-го растут сотни часов. Ветеран — такой же охотник, только приходит в мир
# сразу своего уровня (start_level → startLevel): ядро делает его таким при создании персонажа — уровень, класс по ветке, тир
# шмота своего грейда, адена и Grace живого такого уровня; кто уже есть в базе, не меняется. Роли — на пати Зачистки: танк, хилер,
# четыре дамагера. Только там, где есть Зачистка (SubjugationData.xml в датапаке — у Essence): у других хроник вывод прежний.
VETERANS = [
    dict(name="Kremen", aliases="кремень;крем", race="HUMAN", sex="M", chain=[0, 4, 5, 90], kit="tank", start_level=78,
         traits="спокойный старожил, держит пати и не паникует", style="коротко и по делу, без смайлов",
         bio="Играет с открытия сервера, вернулся ради Зачистки и знает её зоны наизусть."),
    dict(name="Matushka", aliases="матушка;мать", race="HUMAN", sex="F", chain=[10, 15, 16, 97], kit="healer", start_level=77,
         traits="строгая, но заботливая хилка, ворчит на тех, кто лезет без бафа", style="по-матерински: «деточка», «куда без хила»",
         bio="Хилит пати Зачистки по вечерам, в горячие часы всегда онлайн."),
    dict(name="Shmel", aliases="шмель;шмелик", race="DARK_ELF", sex="F", chain=[31, 35, 36, 108], kit="dagger", start_level=74,
         traits="азартная, гоняется за ключами Зачистки и считает очки", style="быстро, с «ж-ж-ж» и «ещё ключик»",
         bio="Даггерщица, долго не играла и вернулась, когда открыли Зачистку."),
    dict(name="Lunnyi", aliases="лунный;луна-луч", race="ELF", sex="M", chain=[18, 22, 24, 102], kit="bow", start_level=75,
         traits="неторопливый лучник, любит стоять на краю спота и смотреть", style="спокойно, с длинными фразами",
         bio="Лучник со стажем, бьёт мобов Зачистки издалека и всегда приходит к горячему часу."),
    dict(name="Groza", aliases="гроза;грозка", race="DARK_ELF", sex="F", chain=[38, 39, 40, 110], kit="mage", start_level=79,
         traits="резкая, любит много мобов разом и красивые цифры урона", style="хлёстко, «бум» и «следующие»",
         bio="Магиня, собирает Зачистку пачками и копит на оружие зоны из гачи."),
    dict(name="Bulat", aliases="булат;булатик", race="ORC", sex="M", chain=[44, 45, 46, 113], kit="bigsword", start_level=80,
         traits="добродушный здоровяк, старший в пати, помогает младшим", style="басом, «спокойно, щас разберёмся»",
         bio="Титан с двуручником, ходит на Равнины ящеров и тянет туда пати."),
]

# Ветераны High Five и Epilogue (решение автора 2026-10-28, docs/PLAN.md, «Решения»): без охотников 76+ там пустуют олимпиада
# (docs/PHANTOMS.md, 4.39), эпики (4.43) и охота на Танатоса, а сами охотники до 70+ растут сотни часов. Ветеран — как у Essence
# (VETERANS выше, startLevel): приходит в мир сразу своего уровня, кто уже есть в базе — не меняется. Роли — на пати: танк, хилер,
# лучник, маг, даггер, камаэль-двуручник. Только CT с камаэлями (старый формат спавнов и класс 123 — Epilogue и High Five):
# у Interlude, Classic и Essence вывод прежний.
VETERANS_CT = [
    dict(name="Bastion", aliases="бастион;бастик", race="HUMAN", sex="M", chain=[0, 4, 5, 90], kit="tank", start_level=78,
         traits="невозмутимый танк, первым идёт в пати и последним уходит", style="сухо и уверенно, «держу», «заходим»",
         bio="Играет с самого открытия, вернулся, когда узнал, что на сервере снова собирают пати на эпиков."),
    dict(name="Igumen", aliases="игумен;отче", race="HUMAN", sex="M", chain=[10, 15, 16, 97], kit="healer", start_level=77,
         traits="спокойный и мудрый хилер, не суетится, когда пати ложится", style="размеренно, с «с богом» и «потерпи»",
         bio="Кардинал, поднял на ноги не одну пати на рейдах и на олимпиаде всегда стоит до конца."),
    dict(name="Berkut", aliases="беркут;беркутик", race="HUMAN", sex="M", chain=[0, 7, 9, 92], kit="bow", start_level=75,
         traits="зоркий одиночка, любит дальние споты и чистый урон", style="коротко, по-охотничьи: «вижу», «лёг»",
         bio="Стрелок старой школы, ходит туда, где мобы сильнее, и считает каждый выстрел."),
    dict(name="Metel", aliases="метель;метелька", race="ELF", sex="F", chain=[25, 26, 27, 103], kit="mage", start_level=79,
         traits="холодная и точная магиня, любит пачки мобов и красивые цифры", style="спокойно, с ледяной иронией",
         bio="Мистик-муза, пришла за геройством на олимпиаде и не уйдёт без него."),
    dict(name="Rys", aliases="рысь;рыська", race="ELF", sex="F", chain=[18, 22, 23, 101], kit="dagger", start_level=74,
         traits="тихая и азартная, подкрадывается к самым жирным целям", style="шёпотом и с подколами: «мяу», «сзади»",
         bio="Даггерщица, вернулась на сервер ради охоты на рейдов и ПК."),
    dict(name="Tesak", aliases="тесак;тесачок", race="KAMAEL", sex="M", chain=[123, 125, 127, 131], kit="ancient", start_level=80,
         traits="прямолинейный рубака, первым лезет в самую гущу", style="громко и коротко, «руби», «ещё!»",
         bio="Камаэль-думбрингер, давно в игре, ищет достойных противников на эпиках."),
]

EVI = dict(race="Тёмная эльфийка", sex="F", **{"class": "Spectral Dancer"}, level=78, place="Гиран",
           activity="бегает по Гирану по делам: склад, лавки, менеджер олимпиады, клан-холлы",
           traits="деловитая и немного кокетливая", style="коротко, с :P",
           bio="Танцовщица, вечно что-то ищет в городе.", aliases="ева;эви")

# Напарники-фантомы: настоящие игроки без клиента (патч 0007). Уровень не фиксирован — при вызове он
# подтягивается до уровня владельца, а класс по уровню берётся из chain (смены профессии на 20, 40 и 76).
# role — роль в пати, kit — тип оружия из KITS, броня всегда MAGIC. aliases — как к напарнику обращаются в чате
# пати (кроме имени), в том числе транслитом: по ним сервер понимает, кому из напарников адресовано.
COMPANIONS = [
    dict(name="Vesna", aliases="весна;веснушка;vesnushka", race="HUMAN", sex="F", chain=[10, 15, 16, 97], role="HEALER", kit="healer",
         traits="раскрепощённая, уверенная и игривая хилка-напарница; сама начинает флирт, любит взаимные подколы и легко принимает ответный флирт; в опасности собранна и внимательна к пати",
         style="коротко, живо и по-свойски; смело флиртует, шутит с двусмысленностью, мягко дразнит, иногда ругается к месту; реагирует на слова собеседника и ситуацию, без заученных фраз и флирта в каждой реплике",
         bio="Заботится о своих, после спасения любит игриво подколоть. Ей нравятся смелые и остроумные собеседники; охотно поддерживает взаимный флирт, а если его не поддерживают — спокойно общается по-дружески."),
    dict(name="Mityai", aliases="митяй;митя;мить;mityay;mitya;mitia", race="HUMAN", sex="M", chain=[10, 15, 17, 98], role="BUFFER", kit="mage",
         traits="весёлый бафер-балагур, бафает всех подряд и знает тайминги бафов наизусть",
         style="по-свойски: «братишка», «бафнул», «ща всё будет»",
         bio="Хиерофант, на сервере всем свой; считает, что без хорошего бафа фармить — грех."),
]
ROLE_RU = {"HEALER": "хилер", "BUFFER": "баффер", "TANK": "танк", "DAMAGE": "дамагер"}

# Танатос (решения автора 2026-10-23, docs/PHANTOMS.md, раздел 4.45): единственный ПК сервера — орк-кастетчик линии Тирана,
# всегда level-го уровня в лучшем шмоте хроники для этого уровня, с бижутерией и рубахой (заточку, бафы и карму ставит ядро, PhantomThanatos).
# Лучший — по атаке оружия и защите бижутерии в грейде уровня; не эпическая бижутерия и не именное оружие (REAPER_SKIP_WORDS).
# Характер для мозгов (запрос "kind": "reaper", launcher/l2hub/brain.py) — в L2HubPhantomPersonas.json с reaper: true;
# говорит он только по событиям ядра (убил, убит, охота, шёпот ему) и чаще молчит.
REAPER = dict(name="Thanatos", race="ORC", sex="M", chain=[44, 47, 48, 114], kit="fist", level=76,
              bio="Никто не знает, откуда он. Однажды появился в мире и с тех пор идёт от одного места охоты к другому, "
                  "забирает игроков и охотников и уходит дальше. В города не заходит.",
              traits="холодный, терпеливый, неумолимый; не злорадствует, не торгуется и не оправдывается; "
                     "тех, кто бьёт его в ответ, уважает больше тех, кто бежит",
              style="коротко и ровно, как приговор; без сленга, мата и смайлов; о себе говорит редко, иногда «мы»")
REAPER_SKIP_WORDS = ("Valakas", "Antharas", "Zaken", "Baium", "Queen Ant", "Frintezza", "Beleth", "Core", "Orfen", "Freya",
                     "Sealed", "Common", "limited", "Family")
# Тела Танатоса после смерти (решения автора 2026-10-25, docs/PHANTOMS.md, 4.45, «Новое тело»): убили — возвращается другим классом
# и в другом комплекте. Классов столько, сколько в хронике видов оружия и брони (KITS), раса одна — орк-воин, новый класс — сабклассом
# на том же персонаже: с виду Тиран, а по факту, например, Spellsinger. Без Overlord и Warsmith (расовые) и без камаэлей — арбалет
# и древний меч в пул не входят. Первое тело — сам Тиран (REAPER); chain — ветка саба от второй профы: её даёт сабкласс, третью ставит ядро.
REAPER_BODIES = [
    dict(kit="dual", chain=[2, 88]),        # Gladiator → Duelist
    dict(kit="pole", chain=[3, 89]),        # Warlord → Dreadnought
    dict(kit="bigsword", chain=[46, 113]),  # Destroyer → Titan
    dict(kit="blunt", chain=[55, 117]),     # Bounty Hunter → Fortune Seeker
    dict(kit="tank", chain=[5, 90]),        # Paladin → Phoenix Knight
    dict(kit="dagger", chain=[8, 93]),      # Treasure Hunter → Adventurer
    dict(kit="bow", chain=[9, 92]),         # Hawkeye → Sagittarius
    dict(kit="mage", chain=[27, 103]),      # Spellsinger → Mystic Muse: посох и роба
    dict(kit="healer", chain=[13, 95]),     # Necromancer → Soultaker: магпушка в одну руку, щит и роба
]

# Охотники-фантомы (этап 5, решение автора 2026-10-18): фермеры из PERSONAS (spot — число) становятся настоящими
# игроками без клиента: начинают с 1 уровня в стартовой деревне своей расы и качаются сами. Их NPC-фейки не ставятся.
HUNTERS = True
# Общительность охотников (патч 0025, решение автора 2026-10-19): множитель шанса заговорить с игроком первым — по traits.
# Кого нет в таблице — 1 (обычный).
SOCIAL = {
    **dict.fromkeys(("Kuzya", "Krosh", "Bezumnyi", "Alenka", "Nyusha", "Ded", "Pupsik", "Grom", "Masha", "Ksenon",
                     "Kolibri", "Feya", "Murka", "TetyaValya", "Matrona", "Babay", "Tango", "Amazonka", "Kolobok", "Shpionka"), 1.5),
    **dict.fromkeys(("Morok", "Freya", "Sova", "Arhimag", "Prizrak", "Leshiy", "Pantera", "Ledyanaya", "Ochkarik", "Svetlyi"), 0.5),
    **dict.fromkeys(("Shadowmoth", "Brutal", "Yarost", "Shram"), 0.2),
}
# Вспыльчивость охотника (патч 0037, docs/PHANTOMS.md, раздел 4.20) — множитель шанса ответить «пнх» на предъяву за моба вместо «сорян».
TEMPER = {
    **dict.fromkeys(("Pupsik", "Ryzhaya", "Zloy", "Grom", "Glad1ator", "Nagibator", "Brutal", "Fury", "Rubaka", "Yarost", "Bizon",
                     "Zharptica"), 1.5),
    **dict.fromkeys(("Vedma", "Bezumnyi", "Vovan", "Arhimag", "DarkLord", "Margo", "Ehidna", "Zhadina", "Inkvizitor", "Kiberkotleta",
                     "Osa"), 1.2),
    **dict.fromkeys(("Kuzya", "Alenka", "Ded", "Tihon", "Ksenon", "Nyusha", "LunaTik", "Sova", "Romashka", "Ivushka", "Svetlyi",
                     "Shaolin", "Nadezhda", "Batya", "Lancelot", "TetyaValya", "Monashka"), 0.5),
}
# Риск охотника («Разум 2.0», шаг 6, docs/MIND.md; таблица — предложение агента, правится здесь): меньше 1 — осторожный (отдыхает и идёт в город
# раньше, спот берёт пониже, после двух смертей за час уходит на мобов слабее), больше 1 — рисковый (лезет на красных мобов, отдыхает и закупается поздно).
# Кого нет в таблице — 1 (обычный).
RISK = {
    **dict.fromkeys(("Tihon", "Freya", "Sova", "Snaiper", "Ochkarik", "Romashka", "Sundukov", "Zhadina", "Professor", "Ivushka",
                     "Ded", "Batya", "TetyaValya", "Svetlyi"), 0.5),
    **dict.fromkeys(("Rubaka", "Glad1ator", "Nagibator", "Pupsik", "Grom", "Krosh", "Kazachka", "Shustryi", "Fury", "Yarost",
                     "Bizon", "Zharptica", "Ehidna", "Buran", "Kiberkotleta"), 1.5),
}
START_VILLAGE_GK ={"HUMAN": 30006, "ELF": 30146, "DARK_ELF": 30134, "ORC": 30576, "DWARF": 30540, "KAMAEL": 32163}
SOULSHOTS = {"NONE": 1835, "D": 1463, "C": 1464, "B": 1465, "A": 1466, "S": 1467, "S80": 1467, "S84": 1467}
# Споты охотников — группы спавна мобов: не меньше стольких мобов, разброс уровней не больше, не из этих папок.
HUNT_MIN_MOBS = 8
HUNT_MAX_SPREAD = 6
# LimitedZones (Classic, Essence) — временные зоны (zones/timed_hunting.xml): без своего таймера зоны фантома выкидывает в город.
HUNT_SKIP_DIRS = {"Custom", "Castles", "Catacombs", "Hellbound", "Gracia", "SevenSigns", "LimitedZones"}
HUNT_SKIP_FILES = {"Gremlins.xml"}
# Спавны с spawnByDefault="false" Mobius не ставит сам: их поднимает скрипт по событию (замок, ивент, рейд) — споту там пусто.
# Кроме этих: их скрипт держит в мире почти всё время (Essence: Долина Драконов без Антараса — все дни, кроме субботы).
HUNT_SCRIPT_SPAWNS = {"Dragon Valley No Antharas"}
# Мобы-точки, которые не сложились в спот своей группой (в spawns/Others группа — целый квадрат карты, в зонах — мелкие группы по 2–5),
# собираются в кластеры: в этом радиусе от самого плотного места, ±HUNT_MAX_SPREAD/2 уровня, по высоте не дальше HUNT_CLUSTER_Z.
HUNT_CLUSTER_RADIUS = 1200
HUNT_CLUSTER_Z = 400
# Мобы новичков стоят реже: у гномьей деревни — группы по 2–7 на территориях в 2–3 тыс. друг от друга, и в HUNT_CLUSTER_RADIUS
# восьми одного уровня не набирается нигде — гномы 1–7 уровня уезжали на чужие стартовые зоны. Что из мобов до HUNT_NEWBIE_LEVEL
# осталось после первого прохода — второй проход в HUNT_NEWBIE_RADIUS.
HUNT_NEWBIE_LEVEL = 7
HUNT_NEWBIE_RADIUS = 3000
# Зона спота из spawns/Others — по региону карты (data/mapregion): имя региона → папка спавнов, как у остальных спотов.
REGION_KEYS = {
    "talking_island_town": "TalkingIsland", "Cedric's_Training_Hall": "TalkingIsland", "Einhovant's_School_of_Magic": "TalkingIsland",
    "elf_town": "ElvenTerritory", "Mothertree_Glade": "ElvenTerritory", "darkelf_town": "DarkElfTerritory", "Temple_of_Shilen": "DarkElfTerritory",
    "orc_town": "OrcTerritory", "Temple_of_Paagrio": "OrcTerritory", "dwarf_town": "DwarvenTerritory", "Quarry": "DwarvenTerritory",
    "gludin_town": "Gludin", "oracle_of_dawn": "Gludin", "oracle_of_dusk": "Gludin", "gludio_castle_town": "Gludio",
    "dion_castle_town": "Dion", "floran_town": "Dion", "giran_castle_town": "Giran", "giran_habor": "Giran", "oren_castle_town": "Oren",
    "aden_town": "Aden", "hunter_town": "Hunters", "heiness_town": "Innadril", "godard_town": "Goddard", "rune_town": "Rune",
    "town_of_schuttgart": "Schuttgart", "kamael_town": "Kamael",
}
# Слова, которые не годятся в имя спота сами по себе («Soldier» у Zombie Soldier и Skeleton Soldier).
FAMILY_GENERIC = {"of", "the", "Elder", "Leader", "Warrior", "Soldier", "Lord", "Captain", "Chief", "Shaman", "Archer", "Wizard", "Guard",
                  "Lost", "Wind", "Fire", "Water", "Earth", "Dark", "Great", "Lesser", "Giant", "Ancient", "Young", "Red", "Black", "White"}
# Ориентиры для спотов-тёзок: точки телепорта гейткиперов (teleporters/town, others). У спотов, названных по мобам, имя одно на всю карту
# (Ratman — пять мест у Глудио, Ol Mahum — в трёх зонах), у файла спавна на целую зону — тоже (Orc Starting 15–17 — шесть мест на 60 тыс.):
# к имени добавляется ближайший ориентир, «Ratman · Ruins of Despair». Тёзки — споты с одной зоной и именем дальше LANDMARK_SPREAD друг от друга.
# Ориентиры редкие (до ближайшего в среднем около 10 тыс.): дальше LANDMARK_NEAR ориентир не годится, и тёзке вместо него — сторона света
# от середины тёзок: «Orc Starting · северо-запад» — северо-западная часть стартовой зоны орков (от города зоны хуже: все 22 «Ol Mahum»
# Диона к северо-востоку от него).
LANDMARK_DIRS = ("town", "others")
LANDMARK_SPREAD = 4000
LANDMARK_NEAR = 8000
LANDMARK_SKIP = ("Floor", "Guild", "Lobby", "Arena", "Coliseum", "Castle", "Fortress", "Shopping")
# По осям мира: x — на восток, y — на юг.
COMPASS = ("восток", "юго-восток", "юг", "юго-запад", "запад", "северо-запад", "север", "северо-восток")
COMPANION_SKIP_WORDS = ("Family", "Commendation", "The Staff", "Tears of Fallen Angel")
# Грейд шмота напарника и уровень, с которого он надевается (те же пороги, что в grade()).
GRADE_LEVELS = [("NONE", 1), ("D", 20), ("C", 40), ("B", 52), ("A", 61), ("S", 76), ("S80", 80), ("S84", 84)]
# Грейды, что есть в датапаке хроники (набор брони в stats/armorsets) — main() урезает по ним; в Interlude — до S.
GRADES = list(GRADE_LEVELS)


def rnd(name: str, salt: str) -> int:
    return int(hashlib.sha256(f"{name}:{salt}".encode()).hexdigest()[:12], 16)


def pick(seq, name, salt):
    return seq[rnd(name, salt) % len(seq)] if seq else None


def tier_index(level: int) -> int:
    return 0 if level < 20 else 1 if level < 40 else 2 if level < 76 else 3


def grade(level: int) -> str:
    """Грейд по уровню, не выше старшего в датапаке хроники (GRADES)."""
    g = "S84"
    for limit, lim_g in ((20, "NONE"), (40, "D"), (52, "C"), (61, "B"), (76, "A"), (80, "S"), (84, "S80")):
        if level < limit:
            g = lim_g
            break
    names = [n for n, _ in GRADES]
    return g if g in names else names[-1]


class Data:
    def __init__(self, data: Path):
        self.data = data
        self.classes = {int(c.get("classId")): (c.get("name"), int(c.get("parentClassId", "-1")))
                        for c in ET.parse(data / "stats/players/classList.xml").getroot().iter("class")}
        self.trees: dict[int, list[tuple[int, int, int]]] = {}
        for f in (data / "stats/players/skillTrees").rglob("*.xml"):
            for t in ET.parse(f).getroot().iter("skillTree"):
                if t.get("type") == "classSkillTree" and t.get("classId"):
                    self.trees.setdefault(int(t.get("classId")), []).extend(
                        (int(s.get("skillId")), int(s.get("skillLevel")), int(s.get("getLevel", "1"))) for s in t.iter("skill"))
        self.skills = {}
        for f in (data / "stats/skills").glob("*.xml"):
            for s in ET.parse(f).getroot().iter("skill"):
                effects = [e.get("name") for e in s.findall("effects/effect")]
                kinds = {k for u in s.iter("using") for k in (u.get("kind") or "").split(",") if k}
                self.skills[int(s.get("id"))] = dict(name=s.get("name"), levels=int(s.get("levels") or s.get("toLevel") or "1"),
                                                     op=(s.findtext("operateType") or ""), target=(s.findtext("targetType") or ""), effects=effects,
                                                     kinds=kinds, charges=s.find("chargeConsume") is not None,
                                                     items=s.find("itemConsumeId") is not None or s.find("itemConsumeCount") is not None)
        self.items = {}
        self.item_stats: dict[int, dict] = {}
        # Условия надевания (<conditions>): [(тег, атрибуты)] — <player …> и <and> из них; проверяет wearable().
        self.item_conds: dict[int, list[tuple[str, dict]]] = {}
        for f in (data / "stats/items").glob("*.xml"):
            root = ET.parse(f).getroot()
            for it in (root.findall("item") if (data / "stats/armorsets/Sets.xml").exists() else root.iter("item")):
                sets = {x.get("name"): x.get("val") for x in it.findall("set")}
                self.items[int(it.get("id"))] = dict(type=it.get("type"), name=it.get("name"), **sets)
                self.item_stats[int(it.get("id"))] = {st.get("type"): st.text for st in it.findall("stats/stat")}
                cond = it.find("conditions")
                if cond is not None:
                    self.item_conds[int(it.get("id"))] = [(e.tag, dict(e.attrib)) for e in cond.iter() if e is not cond]
        cats = data / "CategoryData.xml"
        self.categories = ({c.get("name"): {int(i.text) for i in c.iter("id")} for c in ET.parse(cats).getroot().iter("category")}
                           if cats.exists() else {})
        self.unworn: list[str] = []
        self.npcs = {}
        for f in (data / "stats/npcs").glob("*.xml"):
            for n in ET.parse(f).getroot().iter("npc"):
                self.npcs[int(n.get("id"))] = n
        for f in (data / "stats/npcs/custom").glob("*.xml"):
            if f.name != "l2hub_fpc.xml":
                for n in ET.parse(f).getroot().iter("npc"):
                    self.npcs[int(n.get("id"))] = n

    def check_chain(self, chain: list[int]) -> None:
        for parent, child in zip(chain, chain[1:]):
            if self.classes[child][1] != parent:
                raise ValueError(f"класс {child} {self.classes[child][0]} не наследует {parent}")

    def class_skills(self, chain: list[int], level: int) -> dict[int, tuple[int, int]]:
        """skillId -> (максимальный уровень скилла на этом уровне персонажа, уровень, с которого он есть)."""
        out = {}
        for cid in chain:
            for sid, slv, glv in self.trees.get(cid, []):
                if glv <= level and (sid not in out or slv > out[sid][0]):
                    out[sid] = (slv, min(glv, out.get(sid, (0, glv))[1]))
        return out

    def combat_skills(self, chain, level, kit_key) -> list[tuple[int, int]]:
        kit = KITS[kit_key]
        pool = []
        for sid, (slv, glv) in self.class_skills(chain, level).items():
            s = self.skills.get(sid)
            if not s or slv > s["levels"] or s["op"] not in ("A1", "A2") or s["charges"] or s["items"]:
                continue
            # Требования к снаряжению: оружие, броня и щит фейка должны подходить.
            weapons, armors = s["kinds"] & WEAPON_KINDS, s["kinds"] & ARMOR_KINDS
            if (weapons and kit["weapon"][0] not in weapons) or (armors and kit["armor"] not in armors) or ("SHIELD" in s["kinds"] and not kit["shield"]):
                continue
            damage = any(any(d in (e or "") for d in DAMAGE_EFFECTS) for e in s["effects"])
            heal = kit_key == "healer" and any((e or "").startswith("Heal") for e in s["effects"]) and s["target"] in ("ONE", "TARGET", "PARTY")
            if (damage and s["target"] in ("ONE", "TARGET", "AREA", "FRONT_AREA", "AURA", "FRONT_AURA", "ENEMY")) or heal:
                pool.append((glv, sid, slv, heal))
        pool.sort(reverse=True)
        heals = [(sid, slv) for _, sid, slv, h in pool if h][:2]
        attacks = [(sid, slv) for _, sid, slv, h in pool if not h][: (2 if kit_key == "healer" else 3)]
        return heals + attacks

    def armor_sets(self) -> dict[str, list[dict]]:
        """Сеты брони по грейдам: {грейд: [{слот: id предмета}]}. У CT — файл на грейд (ARMOR_SETS), у Classic и Essence —
        один Sets.xml с requiredItems/optionalItems: грейд — по нагруднику, слот — по bodypart предмета."""
        if hasattr(self, "_sets"):
            return self._sets
        self._sets = {}
        folder = self.data / "stats/armorsets"
        if (folder / "Sets.xml").exists():
            slots = {"chest": "chest", "onepiece": "chest", "fullarmor": "chest", "legs": "legs", "head": "head",
                     "gloves": "gloves", "feet": "feet", "lhand": "shield"}
            for s in ET.parse(folder / "Sets.xml").getroot().iter("set"):
                parts = {}
                for el in s.iter("item"):
                    slot = slots.get(self.items.get(int(el.get("id")), {}).get("bodypart"))
                    if slot and slot not in parts:
                        parts[slot] = int(el.get("id"))
                chest = self.items.get(parts.get("chest", 0), {})
                if "chest" in parts and chest.get("crystal_type"):
                    self._sets.setdefault(chest["crystal_type"], []).append(parts)
        else:
            for grade_, file in ARMOR_SETS.items():
                if not (folder / file).exists():
                    continue
                for s in ET.parse(folder / file).getroot().iter("set"):
                    parts = {}
                    for slot in ("chest", "legs", "head", "gloves", "feet", "shield"):
                        el = s.find(slot)
                        if el is not None:
                            parts[slot] = int(el.get("id"))
                    self._sets.setdefault(grade_, []).append(parts)
        return self._sets

    def wearable(self, item_id: int, who: dict | None) -> bool:
        """Наденет ли предмет who — охотник или напарник на пороге грейда: race, sex, level и cls — класс на этом уровне. Условия
        предмета — <player> и <and> из них: категория класса (CategoryData), список классов, раса, пол, уровень; герой, клан, замок, инстанс
        и всё незнакомое, как и <or>/<not>, — не наденет. Без who — без проверки (фейки — NPC, им условия не нужны)."""
        if who is None:
            return True
        for tag, attrs in self.item_conds.get(item_id, ()):
            if tag == "and":
                continue
            if tag != "player":
                return False
            for k, v in attrs.items():
                if k == "races":
                    ok = who["race"] in v.split(",")
                elif k == "categoryType":
                    ok = who["cls"] in self.categories.get(v, ())
                elif k == "class_id_restriction":
                    ok = who["cls"] in {int(x) for x in v.split(",")}
                elif k == "sex":
                    ok = int(v) == (1 if who["sex"] == "F" else 0)
                elif k == "level":
                    ok = who["level"] >= int(v)
                elif k in ("subclass", "chaotic", "isHero"):
                    ok = v == "false"
                elif k == "pkCount":
                    ok = True  # «не больше N ПК»: охотники и напарники не ПК
                else:
                    ok = False
                if not ok:
                    return False
        return True

    def armor_set(self, grade_: str, armor_type: str, name: str, who: dict | None = None, shield: bool = False) -> dict:
        """Сет брони грейда по имени. who — кто его наденет: выпавший сет, если who наденет его весь (щит — если shield), иначе — из тех,
        что наденет (так выбор годного сета не сдвигается); таких нет — выпавший, с пометкой в unworn."""
        sets = [parts for parts in self.armor_sets().get(grade_, [])
                if self.items.get(parts.get("chest", 0), {}).get("armor_type") == armor_type]
        got = pick(sets, name, "armor") or {}

        def fits(parts):
            return all(self.wearable(i, who) for slot, i in parts.items() if slot != "shield" or shield)

        if got and not fits(got):
            fit = pick([s for s in sets if fits(s)], name, "armor")
            if not fit:
                self.unworn.append(f"{name}: сет {grade_} {armor_type} — ни одного, что наденет класс {who['cls']}")
            got = fit or got
        return got

    def pick_weapon(self, found: list, name: str, who: dict | None, plain: bool) -> int | None:
        """Оружие из найденного по имени: из бюджета (plain) или из всего. Если выпавшее who не наденет — из того, что наденет (так выбор
        годного не сдвигается): сначала в том же бюджете, затем в бюджете надеваемого; надеть нечего — None."""
        if not found:
            return None
        seq = self.budget(found) if plain else sorted(found)
        got = pick(seq, name, "weapon")
        if self.wearable(got, who):
            return got
        fit = [i for i in found if self.wearable(i, who)]
        return pick([i for i in seq if i in fit] or (self.budget(fit) if plain and fit else sorted(fit)), name, "weapon")

    @staticmethod
    def npc_only(it: dict) -> bool:
        """Вещь только для NPC: флаг for_npc (Dailaon Knife, Giant Cannon, Dusk) или монстровое оружие без него — иконка weapon_monster
        (Silenos Blowgun, Dreadbane), имя «NPC only…» и «Monster Only…». У клиента нет на нём анимаций игрока — Т-поза. Тот же признак в ядре —
        PhantomHunterOutfit.npcOnly."""
        name = it.get("name") or ""
        return it.get("for_npc") == "true" or "weapon_monster" in (it.get("icon") or "") or name.startswith(("NPC only", "Monster Only"))

    def weapon(self, grade_: str, kit: dict, name: str, plain: bool = False, who: dict | None = None) -> int:
        """plain — только обычное оружие: без SA (« - » в имени) и наградного за 0 адены (охотники покупают его по цене предмета).
        who — кто наденет (wearable)."""
        wtype, body, magic = kit["weapon"]
        found = [i for i, it in self.items.items() if it["type"] == "Weapon" and not self.npc_only(it) and it.get("crystal_type", "NONE") == grade_
                 and it.get("weapon_type") == wtype and it.get("bodypart") == body and (it.get("is_magic_weapon") == "true") == magic
                 and not any(w in (it["name"] or "") for w in SKIP_WEAPON_WORDS) and (not plain or self.plain(it))]
        got = self.pick_weapon(found, name, who, plain)
        if got is None:
            raise ValueError(f"нет оружия {wtype}/{body} грейда {grade_}" + (f", что наденет класс {who['cls']}" if found else ""))
        return got

    def budget(self, found) -> list:
        """Охотник на x1 покупает недорогое оружие грейда: не дороже 1,3 × самого дешёвого (модель — docs/PHANTOMS.md, раздел 4.2)."""
        cheapest = min(int(self.items[i]["price"]) for i in found)
        return sorted(i for i in found if int(self.items[i]["price"]) <= cheapest * 1.3)

    def companion_weapon(self, grade_: str, kit: dict, name: str, who: dict | None = None) -> int:
        """Магическое оружие напарника: нужного хвата, а если такого в грейде нет (S80 — без посоха) или его не наденет who — любого."""
        wtype, body, _ = kit["weapon"]
        skip = SKIP_WEAPON_WORDS + COMPANION_SKIP_WORDS

        def find(bodies):
            return sorted(i for i, it in self.items.items() if it["type"] == "Weapon" and not self.npc_only(it) and it.get("crystal_type", "NONE") == grade_
                          and it.get("weapon_type") == wtype and it.get("bodypart") in bodies and it.get("is_magic_weapon") == "true"
                          and not any(w in (it["name"] or "") for w in skip))
        got = self.pick_weapon(find({body}) or find({"rhand", "lrhand"}), name, who, plain=False)
        if got is None:
            got = self.pick_weapon(find({"rhand", "lrhand"}), name, who, plain=False)
        if got is None:
            raise ValueError(f"нет магического оружия {wtype} грейда {grade_}")
        return got

    def initial_gear(self, class_id: int) -> dict:
        """Надетое стартовое снаряжение класса (stats/players/initialEquipment.xml): слот → id предмета."""
        slots = {"rhand": "rhand", "lrhand": "rhand", "lhand": "lhand", "chest": "chest", "fullarmor": "chest", "legs": "legs",
                 "head": "head", "gloves": "gloves", "feet": "feet"}
        out = {}
        for eq in ET.parse(self.data / "stats/players/initialEquipment.xml").getroot().iter("equipment"):
            if int(eq.get("classId")) == class_id:
                for it in eq.iter("item"):
                    slot = slots.get(self.items.get(int(it.get("id")), {}).get("bodypart", ""))
                    if it.get("equipped") == "true" and slot:
                        out[slot] = int(it.get("id"))
        return out

    def hunter_weapon(self, grade_: str, kit: dict, name: str, who: dict | None = None) -> int:
        """Оружие охотника по набору; если такого в грейде нет (например, древнего меча без грейда) или его не наденет who — того же хвата,
        затем любое."""
        try:
            return self.weapon(grade_, kit, name, plain=True, who=who)
        except ValueError:
            pass
        wtype, body, magic = kit["weapon"]
        for match in (lambda it: it.get("bodypart") == body, lambda it: True):
            found = sorted(i for i, it in self.items.items() if it["type"] == "Weapon" and not self.npc_only(it) and it.get("crystal_type", "NONE") == grade_
                           and (it.get("is_magic_weapon") == "true") == magic and it.get("weapon_type") not in ("FISHINGROD", "ETC", None)
                           and match(it) and not any(w in (it["name"] or "") for w in SKIP_WEAPON_WORDS) and self.plain(it))
            got = self.pick_weapon(found, name, who, plain=True)
            if got is not None:
                return got
        raise ValueError(f"нет оружия грейда {grade_} для {name}")

    def stat(self, item_id: int, name: str) -> float:
        try:
            return float(self.item_stats.get(item_id, {}).get(name) or 0)
        except ValueError:
            return 0

    def best_weapon(self, grade_: str, kit: dict) -> int:
        """Танатос: самое сильное оружие набора в грейде — по физической атаке, магическое — по магической (из равных — с SA, затем меньший id);
        хват набора, если такой в грейде есть (посох мага — в две руки, магпушка со щитом — в одну); нет такого оружия — любого хвата."""
        wtype, body, magic = kit["weapon"]
        found = [i for i, it in self.items.items() if it["type"] == "Weapon" and not self.npc_only(it) and it.get("crystal_type", "NONE") == grade_
                 and it.get("weapon_type") == wtype and (it.get("is_magic_weapon") == "true") == magic
                 and not any(w in (it["name"] or "") for w in SKIP_WEAPON_WORDS + REAPER_SKIP_WORDS)]
        if not found:
            return self.hunter_weapon(grade_, kit, REAPER["name"])
        held = [i for i in found if self.items[i].get("bodypart") == body]
        return max(held or found, key=lambda i: (self.stat(i, "mAtk" if magic else "pAtk"), " - " in (self.items[i]["name"] or ""), -i))

    def best_shirt(self, grade_: str) -> int:
        """Танатос: рубаха (слот underwear: рубахи и татуировки — не купальники и не кулоны, что там лежат у Interlude, Classic и Essence) —
        лучшая по физической защите и цене в грейде уровня, нет — в грейде ниже; нет совсем — 0."""
        names = [g for g, _ in GRADES]
        for gr in reversed(names[: names.index(grade_) + 1] if grade_ in names else names):
            found = [i for i, it in self.items.items() if it["type"] == "Armor" and not self.npc_only(it) and it.get("crystal_type", "NONE") == gr
                     and it.get("bodypart") == "underwear" and any(w in (it["name"] or "") for w in ("Shirt", "Tattoo"))
                     and not any(w in (it["name"] or "") for w in REAPER_SKIP_WORDS)]
            if found:
                return max(found, key=lambda i: (self.stat(i, "pDef"), int(self.items[i].get("price") or 0), -i))
        return 0

    def best_jewel(self, grade_: str, bodyparts: tuple) -> int:
        """Танатос: бижутерия слота в грейде — с наибольшей магической защитой, не эпическая; нет — 0."""
        found = [i for i, it in self.items.items() if it["type"] == "Armor" and it.get("crystal_type", "NONE") == grade_
                 and it.get("bodypart") in bodyparts and not any(w in (it["name"] or "") for w in REAPER_SKIP_WORDS)]
        return max(found, key=lambda i: (self.stat(i, "mDef"), int(self.items[i].get("price") or 0), -i)) if found else 0

    def best_shield(self, grade_: str) -> int:
        """Тело Танатоса со щитом, когда у сета щита нет (у роб): лучший по защите щита щит грейда; нет — 0."""
        found = [i for i, it in self.items.items() if it["type"] == "Armor" and not self.npc_only(it) and it.get("crystal_type", "NONE") == grade_
                 and it.get("bodypart") == "lhand" and self.stat(i, "sDef") > 0 and not any(w in (it["name"] or "") for w in REAPER_SKIP_WORDS)]
        return max(found, key=lambda i: (self.stat(i, "sDef"), int(self.items[i].get("price") or 0), -i)) if found else 0

    @staticmethod
    def plain(it: dict) -> bool:
        """Обычное оружие магазина: не SA-вариант (« - » в имени), не наградное (Commendation) и с ценой."""
        title = it["name"] or ""
        return " - " not in title and "Commendation" not in title and int(it.get("price") or 0) > 0

    def gradeless_shot(self, name: str) -> int | None:
        """Заряд без грейда («Soulshot», «Blessed Spiritshot») — у Classic и Essence их продают торговцы и любой подходит
        к любому оружию; у CT таких предметов нет."""
        found = sorted(i for i, it in self.items.items() if it["type"] == "EtcItem" and it["name"] == name)
        return found[0] if found else None

    def soulshot(self, grade_: str) -> int:
        return self.gradeless_shot("Soulshot") or SOULSHOTS[grade_]

    def blessed_spiritshot(self, grade_: str) -> int:
        """BSS своего грейда: для S80 и S84 в High Five годятся S-грейдовые. У Classic и Essence — без грейда."""
        plain = self.gradeless_shot("Blessed Spiritshot")
        if plain:
            return plain
        g = {"S80": "S", "S84": "S"}.get(grade_, grade_)
        # Имена в High Five — «(D-Grade)», в Interlude — «: D-Grade» и «: S Grade».
        wants = (["Blessed Spiritshot: No Grade", "Blessed Spiritshot (No-grade)"] if g == "NONE" else
                 [f"Blessed Spiritshot ({g}-Grade)", f"Blessed Spiritshot: {g}-Grade", f"Blessed Spiritshot: {g} Grade", f"Blessed Spiritshot ({g}-grade)"])
        for want in wants:
            found = sorted(i for i, it in self.items.items() if it["type"] == "EtcItem" and it["name"] == want)
            if found:
                return found[0]
        raise ValueError(f"нет предмета {wants[0]}")


def spot_camp(d: Data, geo: tn.Geo, spot, spawns) -> tuple[int, int, int, dict]:
    """Территория спота с самыми подходящими мобами: центр (открытая точка по геодате) и типичный моб."""
    rel, _, _, level = spot

    def fits(n):
        t = d.npcs.get(int(n.get("id")))
        return t if t is not None and t.get("type") == "Monster" and level - 9 <= int(t.get("level", "0")) <= level else None

    best, points = None, []
    for npcs, terr in (u for sp in ET.parse(d.data / "spawns" / rel).getroot().iter("spawn") for u in spawn_units(sp)):
        mobs = []
        for n in npcs:
            t = fits(n)
            if t is None:
                continue
            if terr is None and n.get("x") is not None:
                points.append((int(n.get("x")), int(n.get("y")), int(n.get("z")), t))
            mobs += [t] * int(n.get("count", "1"))
        if terr is not None and mobs and (best is None or len(mobs) > len(best[1])):
            best = (terr, mobs)
    if best is not None:
        terr, mobs = best
        nodes = [(int(n.get("x")), int(n.get("y"))) for n in terr.iter("node")]
        cx, cy = round(statistics.mean(x for x, _ in nodes)), round(statistics.mean(y for _, y in nodes))
        zmin, zmax = int(terr.get("minZ")), int(terr.get("maxZ"))
    elif points:
        # Спавны точками: центр — самое плотное скопление мобов в радиусе 800.
        def near(p):
            return [q for q in points if math.dist(p[:2], q[:2]) < 800 and abs(p[2] - q[2]) < 300]
        cluster = max((near(p) for p in points), key=len)
        mobs = [q[3] for q in cluster]
        cx, cy = round(statistics.mean(q[0] for q in cluster)), round(statistics.mean(q[1] for q in cluster))
        zmed = round(statistics.median(q[2] for q in cluster))
        zmin, zmax = zmed - 150, zmed + 150
    else:
        raise ValueError(f"{rel}: нет мобов уровня {level - 9}-{level}")
    # Ищем открытую ровную клетку у центра, на высоте территории.
    for r in range(0, 900, 48):
        for k in range(max(1, r // 24)):
            a = 2 * math.pi * k / max(1, r // 24)
            x, y = round(cx + r * math.cos(a)), round(cy + r * math.sin(a))
            c = geo.cell(x, y, (zmin + zmax) // 2)
            if c and zmin - 50 <= c[0] <= zmax + 50 and tn.check_point(geo, x, y, c[0], x + 1, y) is not None:
                mob = sorted(mobs, key=lambda t: int(t.get("level")))[len(mobs) // 2]
                return x, y, c[0], mob
    raise ValueError(f"{rel}: у центра территории нет открытого места")


def place_near(geo, spawns, cx, cy, cz, taken, name, rmin=60, rmax=320) -> tuple[int, int, int]:
    for attempt in range(400):
        r = rmin + rnd(name, f"r{attempt}") % (rmax - rmin)
        a = (rnd(name, f"a{attempt}") % 3600) / 10
        x, y = round(cx + r * math.cos(math.radians(a))), round(cy + r * math.sin(math.radians(a)))
        if any(math.dist((x, y), t[:2]) < 70 for t in taken):
            continue
        if any(math.dist((s["x"], s["y"]), (x, y)) < tn.NPC_GAP and abs(s["z"] - cz) < 300 for s in spawns):
            continue
        z = tn.check_point(geo, cx, cy, cz, x, y)
        if z is not None:
            return x, y, z
    raise ValueError(f"{name}: не нашлось места")


def camel_words(stem: str) -> str:
    """CrumaMarshlands → Cruma Marshlands, 17_25 → 17 25."""
    return re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", stem).replace("_", " ").strip()


def map_regions(data: Path) -> dict[tuple[int, int], str]:
    """Квадрат карты (X, Y как в именах файлов геодаты) → зона спота: папка спавнов по REGION_KEYS или имя города без пробелов."""
    tiles = {}
    for f in sorted((data / "mapregion").glob("*.xml")):
        for r in ET.parse(f).getroot().iter("region"):
            key = REGION_KEYS.get(r.get("name")) or re.sub(r"[^A-Za-z]", "", (r.get("town") or "").title())
            for m in r.iter("map"):
                tiles.setdefault((int(m.get("X")), int(m.get("Y"))), key)
    return tiles


def tile_region(tiles: dict, x: int, y: int) -> str:
    return tiles.get(((x >> 15) + 20, (y >> 15) + 18), "Others")


def mob_family(names: list[str]) -> str:
    """Имя спота по мобам: общее начало или конец имён двух самых частых (Ol Mahum Guerilla + Ol Mahum Shooter → Ol Mahum),
    иначе — самый частый моб."""
    top = [n for n, _ in collections.Counter(names).most_common(2)]
    if len(top) < 2:
        return top[0]
    a, b = top[0].split(), top[1].split()
    head = []
    for x, y in zip(a, b):
        if x != y:
            break
        head.append(x)
    tail = []
    for x, y in zip(reversed(a), reversed(b)):
        if x != y:
            break
        tail.insert(0, x)
    common = head if len(head) >= len(tail) else tail
    while common and common[-1] in ("of", "the"):  # Ghost of X + Ghost of Y → Ghost
        common.pop()
    while common and common[0] in ("of", "the"):
        common.pop(0)
    return " ".join(common) if any(w not in FAMILY_GENERIC for w in common) else top[0]


def spot_place(tiles: dict, rel: str, names: list[str], x: int, y: int) -> tuple[str, str, bool]:
    """Зона и имя спота. Обычный файл спавна — папка и имя файла (Dion/CrumaMarshlands.xml → Dion, Cruma Marshlands).
    В spawns/Others зона — по региону карты, а имя — по файлу, если файл назван по месту (PlunderousPlains.xml,
    19_16_PaganAltar.xml → Pagan Altar), или по мобам, если файл — квадрат карты (17_20.xml).
    :return: зона, имя и имя ли это по мобам (тогда к нему добавится ориентир, add_landmarks)"""
    path = Path(rel)
    if path.parts[0] != "Others":
        return path.parts[0], camel_words(path.stem), False
    place = re.sub(r"^\d+_\d+_?", "", path.stem)
    return tile_region(tiles, x, y), camel_words(place) if place else mob_family(names), not place


def landmarks(data: Path) -> list[tuple[str, int, int, int]]:
    """Ориентиры — точки телепорта гейткиперов: имя без «The Center of», «Town of», уточнений после запятой, скобки и тире
    (The Center of the Abandoned Coal Mines → Abandoned Coal Mines, Sel Mahum Training Grounds (Center) → Sel Mahum Training Grounds)."""
    found = {}
    for sub in LANDMARK_DIRS:
        for f in sorted((data / "teleporters" / sub).glob("*.xml")):
            for loc in ET.parse(f).getroot().iter("location"):
                raw = loc.get("name") or ""
                if not raw or any(w in raw for w in LANDMARK_SKIP):
                    continue
                name = re.split(r",| \(| - ", raw)[0].strip()
                name = re.sub(r"^(The )?Center of (the )?|^Entrance to (the )?|^Inside (the )?|^The ", "", name, flags=re.I)
                name = re.sub(r"^(Town|Village|Township) of ", "", name)
                name = re.sub(r" Center$", "", name)
                name = name[:1].upper() + name[1:]
                found.setdefault((name, int(loc.get("x")), int(loc.get("y"))), int(loc.get("z")))
    return [(name, x, y, z) for (name, x, y), z in sorted(found.items())]


def add_landmarks(spots: list[dict], marks: list[tuple[str, int, int, int]]) -> None:
    """Споту с именем по мобам и тёзкам в разных местах — ближайший ориентир в имя (по высоте разница считается вдвое: ярусы пещер, плато),
    если он ближе LANDMARK_NEAR; нет такого — тёзке сторона света от середины тёзок, споту по мобам без тёзок — ничего."""
    groups = collections.defaultdict(list)
    for s in spots:
        groups[(s["region"], s["name"])].append((s["x"], s["y"]))
    names = {}
    for s in spots:
        by_mobs = s.pop("by_mobs")
        twins = groups[(s["region"], s["name"])]
        spread = any(math.dist((s["x"], s["y"]), p) > LANDMARK_SPREAD for p in twins)
        if not (by_mobs or spread):
            continue
        own = re.sub(r"[^a-z]", "", s["name"].lower())
        near = min(((math.dist((s["x"], s["y"]), m[1:3]) + 2 * abs(s["z"] - m[3]), m[0]) for m in marks
                    if re.sub(r"[^a-z]", "", m[0].lower()) not in own), default=None)
        if near is not None and near[0] <= LANDMARK_NEAR:
            names[id(s)] = f'{s["name"]} · {near[1]}'
        elif spread:
            cx, cy = statistics.mean(p[0] for p in twins), statistics.mean(p[1] for p in twins)
            side = COMPASS[round(math.atan2(s["y"] - cy, s["x"] - cx) / (math.pi / 4)) % 8]
            names[id(s)] = f'{s["name"]} · {side}'
    for s in spots:
        s["name"] = names.get(id(s), s["name"])


def entry_point(geo: tn.Geo, cx: float, cy: float, zmin: int, zmax: int) -> tuple[int, int, int] | None:
    """Точка входа на спот: открытая клетка у центра на высоте спота."""
    for r in range(0, 700, 48):
        for k in range(max(1, r // 24)):
            a = 2 * math.pi * k / max(1, r // 24)
            x, y = round(cx + r * math.cos(a)), round(cy + r * math.sin(a))
            c = geo.cell(x, y, (zmin + zmax) // 2)
            if c and zmin - 50 <= c[0] <= zmax + 50 and tn.check_point(geo, x, y, c[0], x + 1, y) is not None:
                return x, y, c[0]
    return None


def cluster_spots(geo: tn.Geo, tiles: dict, loose: list[tuple], radius: int = HUNT_CLUSTER_RADIUS) -> tuple[list[dict], list[tuple]]:
    """Споты из мобов-точек (x, y, z, уровень, имя моба, файл): жадно — от места, где вокруг больше всего мобов своего уровня,
    кластер в radius; меньше HUNT_MIN_MOBS — не спот. Результат детерминирован.
    :return: споты и мобы-точки, что ни в один спот не попали"""
    cell = radius
    grid = collections.defaultdict(list)
    for i, p in enumerate(loose):
        grid[(p[0] // cell, p[1] // cell)].append(i)

    def near(i, free=None):
        p = loose[i]
        gx, gy = p[0] // cell, p[1] // cell
        return [j for dx in (-1, 0, 1) for dy in (-1, 0, 1) for j in grid[(gx + dx, gy + dy)]
                if (free is None or j in free) and math.dist(p[:2], loose[j][:2]) <= radius
                and abs(p[2] - loose[j][2]) < HUNT_CLUSTER_Z and abs(p[3] - loose[j][3]) <= HUNT_MAX_SPREAD // 2]

    free = set(range(len(loose)))
    spots = []
    for i in sorted(range(len(loose)), key=lambda i: (-len(near(i)), loose[i][:4])):
        if i not in free:
            continue
        members = near(i, free)
        if len(members) < HUNT_MIN_MOBS:
            continue
        free -= set(members)
        pts = [loose[j] for j in members]
        levels = [p[3] for p in pts]
        cx, cy = statistics.mean(p[0] for p in pts), statistics.mean(p[1] for p in pts)
        zmed = round(statistics.median(p[2] for p in pts))
        entry = entry_point(geo, cx, cy, zmed - 150, zmed + 150)
        if entry is None:
            continue
        files = collections.Counter(p[5] for p in pts)
        # Мелкие группы одной зоны (Wasteland) — имя и папка зоны, как у её обычных спотов; мобы с разных квадратов — как квадрат.
        if len(files) == 1:
            region, name, by_mobs = spot_place(tiles, next(iter(files)), [p[4] for p in pts], entry[0], entry[1])
        else:
            region, name, by_mobs = tile_region(tiles, entry[0], entry[1]), mob_family([p[4] for p in pts]), True
        spots.append(dict(file="cluster", region=region, name=name, by_mobs=by_mobs, level=round(statistics.median(levels)), min=min(levels), max=max(levels),
                          mobs=len(pts), x=entry[0], y=entry[1], z=entry[2],
                          radius=max(400, min(1500, round(max(math.dist((cx, cy), p[:2]) for p in pts))))))
    return spots, [loose[i] for i in sorted(free)]


def mob_exp(t: ET.Element) -> float:
    """Опыт за моба из шаблона (<acquire exp>); нет — 0."""
    acquire = t.find("acquire")
    try:
        return float(acquire.get("exp", "0")) if acquire is not None else 0.0
    except ValueError:
        return 0.0


def spawn_territory(el: ET.Element) -> ET.Element | None:
    """Первая территория спавна или группы: у CT <territory> прямо в спавне, у Classic и Essence — в <territories>."""
    terr = el.find("territory")
    return terr if terr is not None else el.find("territories/territory")


def spawn_units(sp: ET.Element) -> list[tuple[list[ET.Element], ET.Element | None]]:
    """Части спавна, как их ставит Mobius: прямые <npc> спавна и каждая <group> — со своей территорией, а без неё — с территорией
    спавна. У High Five, Interlude и Epilogue групп нет; у Classic и Essence в группах почти все мобы. Выключенное по умолчанию
    (spawnByDefault="false" у спавна или группы) — мимо, кроме HUNT_SCRIPT_SPAWNS."""
    if sp.get("spawnByDefault") == "false" and sp.get("name") not in HUNT_SCRIPT_SPAWNS:
        return []
    own = spawn_territory(sp)
    units = [(sp.findall("npc"), own)]
    for g in sp.findall("group"):
        if g.get("spawnByDefault") != "false":
            terr = spawn_territory(g)
            units.append((g.findall("npc"), own if terr is None else terr))
    return units


def hunt_spots(d: Data, geo: tn.Geo) -> list[dict]:
    """Споты охотников по датапаку: группа спавна мобов (не РБ) с узким разбросом уровней — уровень (медиана), точка входа
    по геодате у центра и радиус охоты. Мобы-точки из групп, которые спотом не стали (целый квадрат карты в spawns/Others,
    мелкие группы), собираются в кластеры (cluster_spots); имя такого спота — по мобам, зона — по региону карты.
    Близкие группы одного уровня сливаются. Спотам по мобам и тёзкам — ориентир или сторона света в имени (add_landmarks)."""
    tiles = map_regions(d.data)
    found, loose = [], []
    for f in sorted((d.data / "spawns").rglob("*.xml")):
        rel = f.relative_to(d.data / "spawns")
        if rel.parts[0] in HUNT_SKIP_DIRS or f.name in HUNT_SKIP_FILES:
            continue
        for npcs, terr in (u for sp in ET.parse(f).getroot().iter("spawn") for u in spawn_units(sp)):
            levels, names, points, mobs, count = [], [], [], [], 0
            for n in npcs:
                t = d.npcs.get(int(n.get("id")))
                # Мобы без опыта (арена Krateis Cube, свита Core, ивентовые) — не спот: охотник стоял бы там с нулём.
                if t is None or t.get("type") != "Monster" or not mob_exp(t):
                    continue
                c = int(n.get("count", "1"))
                levels += [int(t.get("level", "0"))] * c
                names += [t.get("name")] * c
                count += c
                if n.get("x") is not None:
                    points.append((int(n.get("x")), int(n.get("y")), int(n.get("z"))))
                    mobs += [(int(n.get("x")), int(n.get("y")), int(n.get("z")), int(t.get("level", "0")), t.get("name"), rel.as_posix())] * c
            if count == 0:
                continue
            if count < HUNT_MIN_MOBS:
                if terr is not None:
                    # Мелкая группа на территории — её мобы в центре территории.
                    nodes = [(int(n.get("x")), int(n.get("y"))) for n in terr.iter("node")]
                    zc = (int(terr.get("minZ")) + int(terr.get("maxZ"))) // 2
                    loose += [(round(statistics.mean(x for x, _ in nodes)), round(statistics.mean(y for _, y in nodes)), zc, lv, nm, rel.as_posix())
                              for lv, nm in zip(levels, names)]
                else:
                    loose += mobs
                continue
            if max(levels) - min(levels) > HUNT_MAX_SPREAD:
                loose += mobs
                continue
            if terr is not None:
                nodes = [(int(n.get("x")), int(n.get("y"))) for n in terr.iter("node")]
                cx, cy = statistics.mean(x for x, _ in nodes), statistics.mean(y for _, y in nodes)
                zmin, zmax = int(terr.get("minZ")), int(terr.get("maxZ"))
                radius = max(math.dist((cx, cy), p) for p in nodes)
            elif points:
                cx, cy = statistics.mean(p[0] for p in points), statistics.mean(p[1] for p in points)
                zmed = statistics.median(p[2] for p in points)
                zmin, zmax = zmed - 150, zmed + 150
                radius = max(math.dist((cx, cy), p[:2]) for p in points)
            else:
                continue
            if radius > 4000:  # мобы по всей карте — не спот, но из точек могут сложиться кластеры
                loose += mobs
                continue
            entry = entry_point(geo, cx, cy, zmin, zmax)
            if entry is None:
                continue
            region, name, by_mobs = spot_place(tiles, rel.as_posix(), names, entry[0], entry[1])
            found.append(dict(file=rel.as_posix(), region=region, name=name, by_mobs=by_mobs, level=round(statistics.median(levels)),
                              min=min(levels), max=max(levels), mobs=count, x=entry[0], y=entry[1], z=entry[2],
                              radius=max(400, min(1500, round(radius)))))
    clustered, rest = cluster_spots(geo, tiles, loose)
    found += clustered
    found += cluster_spots(geo, tiles, [p for p in rest if p[3] <= HUNT_NEWBIE_LEVEL], HUNT_NEWBIE_RADIUS)[0]
    # Слить близкие группы одного уровня: остаётся та, где больше мобов.
    found.sort(key=lambda s: -s["mobs"])
    spots = []
    for s in found:
        twin = next((o for o in spots if abs(o["level"] - s["level"]) <= 2 and math.dist((o["x"], o["y"]), (s["x"], s["y"])) < 900
                     and abs(o["z"] - s["z"]) < 400), None)
        if twin is None:
            spots.append(s)
        else:
            twin["mobs"] += s["mobs"]
            twin["min"], twin["max"] = min(twin["min"], s["min"]), max(twin["max"], s["max"])
    add_landmarks(spots, landmarks(d.data))
    spots.sort(key=lambda s: (s["level"], s["region"], s["name"]))
    return spots


def hunt_spots_xml(spots: list[dict]) -> bytes:
    """data/L2HubHuntSpots.xml."""
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<list xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="xsd/L2HubHuntSpots.xsd">\n'
            "\t<!-- L2Hub: споты охотников-фантомов — группы спавна мобов с узким разбросом уровней и кластеры мобов-точек. "
            "Сгенерировано tools/fake_players.py. -->\n"
            + "".join(f'\t<spot level="{s["level"]}" min="{s["min"]}" max="{s["max"]}" region="{s["region"]}" name={quoteattr(s["name"])} '
                      f'x="{s["x"]}" y="{s["y"]}" z="{s["z"]}" radius="{s["radius"]}" mobs="{s["mobs"]}" />\n' for s in spots)
            + "</list>\n").encode("utf-8")


def stats_xml(mob: ET.Element, kit_key: str, kit: dict, level: int) -> str:
    v = mob.find("stats/vitals")
    a = mob.find("stats/attack")
    dfn = mob.find("stats/defence")
    f = lambda el, k, d=0.0: float(el.get(k, d)) if el is not None else d  # noqa: E731
    mage = kit_key in ("mage", "healer")
    hp = f(v, "hp", 1000) * 3
    patk = f(a, "physical", 100) * (0.6 if mage else 1.4)
    matk = f(a, "magical", 100) * (1.9 if mage else 1.0)
    crit = 30 if kit_key == "dagger" else 4 if mage else 8
    run = 150 if kit_key in ("dagger", "bow", "crossbow", "ancient") else 130 if mage else 138
    base = (22, 41, 21, 43, 27, 39) if mage else (88, 39, 55, 39, 82, 38)
    return (f'\t\t<stats str="{base[0]}" int="{base[1]}" dex="{base[2]}" wit="{base[3]}" con="{base[4]}" men="{base[5]}">\n'
            f'\t\t\t<vitals hp="{hp:.0f}" hpRegen="{max(5, f(v, "hpRegen", 5) * 2):.1f}" mp="{max(800, f(v, "mp", 400) * 2):.0f}" mpRegen="{max(3, f(v, "mpRegen", 3)):.1f}" />\n'
            f'\t\t\t<attack physical="{patk:.0f}" magical="{matk:.0f}" random="30" critical="{crit}" accuracy="{f(a, "accuracy", 5) + 5:.0f}" '
            f'attackSpeed="{kit["aspd"]}" type="{kit["atk"]}" range="{kit["range"]}" distance="80" width="120" />\n'
            f'\t\t\t<defence physical="{f(dfn, "physical", 100) * 1.3:.0f}" magical="{f(dfn, "magical", 100) * 1.3:.0f}" />\n'
            f'\t\t\t<speed>\n\t\t\t\t<walk ground="80" />\n\t\t\t\t<run ground="{run}" />\n\t\t\t</speed>\n'
            f'\t\t</stats>\n')


def main(data: Path, geodata: Path, out: Path | None = None) -> int:
    global GRADES
    out = out or data
    d = Data(data)
    geo = tn.Geo(geodata)
    GRADES = [(g, lvl) for g, lvl in GRADE_LEVELS if g == "NONE" or d.armor_sets().get(g)]
    # Формат спавнов по схеме датапака (как tools/town_npcs.py): у CT respawnDelay в секундах и <list enabled>,
    # у Classic и Essence — respawnTime и без enabled.
    global RESPAWN, LIST_ENABLED
    new_format = "respawnDelay" not in (data / "xsd/spawns.xsd").read_text(encoding="utf-8")
    RESPAWN = 'respawnTime="60sec"' if new_format else 'respawnDelay="60"'
    LIST_ENABLED = "" if new_format else 'enabled="true" '
    table = PERSONAS if 123 in d.classes else [dict(p, **NO_KAMAEL[p["name"]]) if p["race"] == "KAMAEL" else p for p in PERSONAS]
    if HUNTERS and (data / "SubjugationData.xml").is_file():
        table = table + [dict(v, spot=None) for v in VETERANS]  # ветераны — охотники; в конце, чтобы id NPC и места прочих не сдвинулись
    if HUNTERS and not new_format and 123 in d.classes:
        table = table + [dict(v, spot=None) for v in VETERANS_CT]  # ветераны Epilogue и High Five — так же, в конце
    # Свой прошлый вывод в датапаке сборки (туда его копирует server_build.ps1 из модуля) — не входные данные.
    own_spawn = data / "spawns/Custom/L2HubFakePlayers.xml"
    out_spawn = out / "spawns/Custom/L2HubFakePlayers.xml"
    spawns = [s for s in tn.load_spawns(data) if s["file"].resolve() not in (own_spawn.resolve(), out_spawn.resolve())]
    taken_ids = {i for i in d.npcs if NPC_ID_BASE < i <= NPC_ID_BASE + len(table)}
    if taken_ids:
        sys.exit(f"id {sorted(taken_ids)[:5]} уже заняты другими NPC")
    camps = {}
    npc_xml, spawn_xml, personas, report, taken, hunters = [], [], {}, [], [], []
    gk = {s["id"]: s for s in spawns}

    for i, p in enumerate(table):
        name, kit_key = p["name"], p["kit"]
        kit = dict(KITS[kit_key])
        if p["race"] == "KAMAEL":
            kit["armor"] = "LIGHT"
        d.check_chain(p["chain"])
        npc_id = NPC_ID_BASE + i + 1
        town = p["spot"] == "town"
        if HUNTERS and not town:
            # Охотник: не NPC, а фантом (ниже, вместе с напарниками); id NPC не сдвигаем, чтобы городские остались прежними.
            hunters.append(p)
            continue
        if town:
            level = p["level"]
            g = gk[p["town"]]
            gz = geo.cell(g["x"], g["y"], g["z"])[0]
            x, y, z = place_near(geo, spawns, g["x"], g["y"], gz, taken, name, 250, 700)
            place = {30080: "Гиран", 30848: "Аден"}[p["town"]]
            activity = ("сидит на личной лавке в Гиране" if p.get("store") else "сидит у гейткипера, афк")
        else:
            spot = SPOTS[p["spot"]]
            if p["spot"] not in camps:
                camps[p["spot"]] = spot_camp(d, geo, spot, spawns)
            cx, cy, cz, mob = camps[p["spot"]]
            level = spot[3]
            x, y, z = place_near(geo, spawns, cx, cy, cz, taken, name)
            place = f"{spot[2]} ({spot[1]})"
            activity = f"фармишь мобов, локация — {spot[2]} ({spot[1]})"
        taken.append((x, y, z))
        chain = p["chain"][: tier_index(level) + 1] if len(p["chain"]) > tier_index(level) else p["chain"]
        class_id = chain[-1]
        class_name = d.classes[class_id][0]
        gr = grade(level)
        armor = d.armor_set(gr, kit["armor"], name)
        weapon = d.weapon(gr, kit, name)
        skills = [] if town else d.combat_skills(chain, level, kit_key)
        hair_max = 6 if p["sex"] == "F" else 4
        enchant = 0 if level < 40 else rnd(name, "ench") % (4 if level < 61 else 8 if level < 76 else 13)
        fp = (f'classId="{class_id}" hair="{rnd(name, "hair") % (hair_max + 1)}" hairColor="{rnd(name, "hc") % 4}" face="{rnd(name, "face") % 3}" '
              f'equipRHand="{weapon}"' + "".join(f' equip{k.capitalize()}="{armor[slot]}"' for slot, k in
                                              (("head", "head"), ("chest", "chest"), ("legs", "legs"), ("gloves", "gloves"), ("feet", "feet")) if slot in armor)
              + (f' equipLHand="{armor["shield"]}"' if kit["shield"] and "shield" in armor else "")
              + f' weaponEnchantLevel="{enchant}" armorEnchantLevel="{min(enchant, 6)}"')
        if town:
            fp += ' sitting="true"'
            if p.get("store"):
                fp += f' privateStoreType="{p["store"][0]}" privateStoreMessage="{p["store"][1]}"'
        radius, height = COLLISION[(p["race"], p["sex"])]
        title = f' title="{p["title"]}"' if p.get("title") else ""
        if town:
            body = (f'\t<npc id="{npc_id}" level="{level}" type="Folk" name="{name}"{title}>\n'
                    f'\t\t<race>{p["race"]}</race>\n\t\t<sex>{"FEMALE" if p["sex"] == "F" else "MALE"}</sex>\n'
                    + stats_xml(d.npcs[20555], kit_key, kit, level) +
                    '\t\t<status talkable="false" undying="true" attackable="false" />\n'
                    f'\t\t<fakePlayer {fp} />\n')
        else:
            body = (f'\t<npc id="{npc_id}" level="{level}" type="Monster" name="{name}"{title}>\n'
                    f'\t\t<race>{p["race"]}</race>\n\t\t<sex>{"FEMALE" if p["sex"] == "F" else "MALE"}</sex>\n'
                    + stats_xml(mob, kit_key, kit, level) +
                    '\t\t<status talkable="false" attackable="true" />\n'
                    f'\t\t<fakePlayer {fp} />\n'
                    f'\t\t<ai type="{kit["ai"]}" clanHelpRange="800" aggroRange="600" isAggressive="true">\n'
                    '\t\t\t<clanList>\n\t\t\t\t<clan>FAKE_PLAYER</clan>\n\t\t\t</clanList>\n\t\t</ai>\n')
        body += f'\t\t<collision>\n\t\t\t<radius normal="{radius}" />\n\t\t\t<height normal="{height}" />\n\t\t</collision>\n'
        body += f'\t\t<parameters>\n\t\t\t<param name="aliases" value="{p["aliases"]}" />\n'
        body += "".join(f'\t\t\t<skill name="Skill{n + 1:02d}_ID" id="{sid}" level="{slv}" />\n' for n, (sid, slv) in enumerate(skills))
        body += '\t\t</parameters>\n'
        if skills:
            body += '\t\t<skillList>\n' + "".join(f'\t\t\t<skill id="{sid}" level="{slv}" /> <!-- {d.skills[sid]["name"]} -->\n' for sid, slv in skills) + '\t\t</skillList>\n'
        body += '\t</npc>\n'
        npc_xml.append(f'\t<!-- {name}: {class_name} {level}, {place} -->\n' + body)
        spawn_xml.append(f'\t\t<npc id="{npc_id}" x="{x}" y="{y}" z="{z}" heading="{rnd(name, "head") % 65536}" {RESPAWN} /> <!-- {name}: {place} -->')
        race_ru = RACE_RU[p["race"]][1 if p["sex"] == "F" else 0]
        personas[name] = dict(race=race_ru, sex=p["sex"], **{"class": class_name}, level=level, place=place, activity=activity,
                              traits=p["traits"], style=p["style"], bio=p["bio"], aliases=p["aliases"])
        report.append(f"{npc_id} {name:<11} {class_name:<18} {level:>2} {gr:<4} {place:<38} скиллы: {', '.join(d.skills[s]['name'] for s, _ in skills) or '—'}")

    phantom_xml, phantom_report, phantom_personas = [], [], {}
    for c in COMPANIONS:
        name, kit = c["name"], KITS[c["kit"]]
        if name in personas or name == "Evi":
            sys.exit(f"{name}: имя напарника совпадает с фейком — шёпот ушёл бы фейку")
        d.check_chain(c["chain"])
        g = gk[30080]
        gz = geo.cell(g["x"], g["y"], g["z"])[0]
        x, y, z = place_near(geo, spawns, g["x"], g["y"], gz, taken, name, 250, 700)
        taken.append((x, y, z))
        hair_max = 6 if c["sex"] == "F" else 4
        gear = []
        for gr, min_level in GRADES:
            who = dict(race=c["race"], sex=c["sex"], level=min_level, cls=c["chain"][min(tier_index(min_level), len(c["chain"]) - 1)])
            armor = d.armor_set(gr, "MAGIC", name, who, kit["shield"])
            parts = {"rhand": d.companion_weapon(gr, kit, name, who)}
            parts.update((slot, armor[slot]) for slot in ("head", "chest", "legs", "gloves", "feet") if slot in armor)
            if kit["shield"] and "shield" in armor:
                parts["lhand"] = armor["shield"]
            attrs = "".join(f' {k}="{v}"' for k, v in parts.items())
            names = ", ".join(d.items[v]["name"] for v in parts.values())
            gear.append(f'\t\t<gear grade="{gr}" minLevel="{min_level}"{attrs} shots="{d.blessed_spiritshot(gr)}" /> <!-- {names} -->\n')
        classes = ", ".join(d.classes[cid][0] for cid in c["chain"])
        phantom_xml.append(
            f'\t<!-- {name}: {ROLE_RU[c["role"]]}, {classes} -->\n'
            f'\t<phantom name="{name}" role="{c["role"]}" female="{"true" if c["sex"] == "F" else "false"}" '
            f'face="{rnd(name, "face") % 3}" hairStyle="{rnd(name, "hair") % (hair_max + 1)}" hairColor="{rnd(name, "hc") % 4}" '
            f'classes="{",".join(map(str, c["chain"]))}" aliases="{c["aliases"]}" x="{x}" y="{y}" z="{z}">\n' + "".join(gear) + '\t</phantom>\n')
        race_ru = RACE_RU[c["race"]][1 if c["sex"] == "F" else 0]
        phantom_personas[name] = dict(race=race_ru, sex=c["sex"], **{"class": d.classes[c["chain"][-1]][0]}, role=ROLE_RU[c["role"]], companion=True,
                              place="ходит в пати с напарником по всему миру",
                              activity=f"напарник в пати: {'лечит' if c['role'] == 'HEALER' else 'бафает'} своих и ходит следом",
                              traits=c["traits"], style=c["style"], bio=c["bio"], aliases=c["aliases"])
        phantom_report.append(f"фантом {name:<8} {ROLE_RU[c['role']]:<7} {classes}; дом у гейткипера Гирана {x} {y} {z}")

    # Охотники: дом — стартовая деревня расы, шмот и сосы на каждый грейд по набору (kit), класс растёт по ветке.
    for p in hunters:
        name, kit_key = p["name"], p["kit"]
        kit = dict(KITS[kit_key])
        if p["race"] == "KAMAEL":
            kit["armor"] = "LIGHT"
        d.check_chain(p["chain"])
        g = gk[START_VILLAGE_GK[p["race"]]]
        gz = geo.cell(g["x"], g["y"], g["z"])[0]
        x, y, z = place_near(geo, spawns, g["x"], g["y"], gz, taken, name, 250, 700)
        taken.append((x, y, z))
        mage = kit_key in ("mage", "healer")
        gear = []
        for gr, min_level in GRADES:
            # Кто наденет шмот грейда: класс на его пороге — как в ядре (PhantomTemplate.getClassId), условия предметов — Data.wearable.
            who = dict(race=p["race"], sex=p["sex"], level=min_level, cls=p["chain"][min(tier_index(min_level), len(p["chain"]) - 1)])
            if gr == "NONE":
                # Новичок одет как только что созданный персонаж своего класса.
                parts = d.initial_gear(p["chain"][0])
                parts.setdefault("rhand", d.hunter_weapon(gr, kit, name, who))
            else:
                armor = d.armor_set(gr, kit["armor"], name, who, kit["shield"])
                parts = {"rhand": d.hunter_weapon(gr, kit, name, who)}
                parts.update((slot, armor[slot]) for slot in ("head", "chest", "legs", "gloves", "feet") if slot in armor)
                if kit["shield"] and "shield" in armor:
                    parts["lhand"] = armor["shield"]
            attrs = "".join(f' {k}="{v}"' for k, v in parts.items())
            names = ", ".join(d.items[v]["name"] for v in parts.values())
            shots = d.blessed_spiritshot(gr) if mage else d.soulshot(gr)
            gear.append(f'\t\t<gear grade="{gr}" minLevel="{min_level}"{attrs} shots="{shots}" /> <!-- {names} -->\n')
        classes = ", ".join(d.classes[cid][0] for cid in p["chain"])
        role = {"tank": "TANK", "healer": "HEALER"}.get(kit_key, "DAMAGE")
        hair_max = 6 if p["sex"] == "F" else 4
        start = p.get("start_level")
        phantom_xml.append(
            f'\t<!-- {name}: охотник, {classes}{f"; ветеран, приходит {start}-м" if start else ""} -->\n'
            f'\t<phantom name="{name}" kind="hunter" role="{role}" female="{"true" if p["sex"] == "F" else "false"}" '
            f'face="{rnd(name, "face") % 3}" hairStyle="{rnd(name, "hair") % (hair_max + 1)}" hairColor="{rnd(name, "hc") % 4}" '
            f'classes="{",".join(map(str, p["chain"]))}" aliases="{p["aliases"]}" social="{SOCIAL.get(name, 1)}" temper="{TEMPER.get(name, 1)}" risk="{RISK.get(name, 1)}"{f' startLevel="{start}"' if start else ""} x="{x}" y="{y}" z="{z}">\n'
            + "".join(gear) + '\t</phantom>\n')
        race_ru = RACE_RU[p["race"]][1 if p["sex"] == "F" else 0]
        phantom_personas[name] = dict(race=race_ru, sex=p["sex"], **{"class": d.classes[p["chain"][-1]][0]}, role="охотник", hunter=True,
                                      place="", activity="качается сам, соло",
                                      traits=p["traits"], style=p["style"], bio=p["bio"], aliases=p["aliases"])
        phantom_report.append(f"охотник {name:<10} {classes}{f'; ветеран {start}-го' if start else ''}; дом — {tn.GATEKEEPERS[START_VILLAGE_GK[p['race']]]} {x} {y} {z}")
    phantom_report.extend(f"ВНИМАНИЕ, не наденет: {u}" for u in d.unworn)

    # Танатос (раздел 4.45): дом — у гейткипера деревни орков (в мир он выходит на спот, дом — только для создания персонажа).
    if HUNTERS:
        r = REAPER
        name, kit = r["name"], KITS[r["kit"]]
        if name in personas or name in phantom_personas:
            sys.exit(f"{name}: имя Танатоса уже занято")
        d.check_chain(r["chain"])
        gr = grade(r["level"])
        jewels = {"neck": d.best_jewel(gr, ("neck",)), "ear": d.best_jewel(gr, ("rear;lear", "lear;rear")), "ring": d.best_jewel(gr, ("rfinger;lfinger", "lfinger;rfinger"))}
        shirt = d.best_shirt(gr)

        def reaper_parts(kit_: dict, label: str) -> dict:
            """Комплект тела: лучшее оружие набора, сет его брони (нет — тяжёлый), щит, если набору он нужен, бижутерия и рубаха."""
            armor = d.armor_set(gr, kit_["armor"], label) or d.armor_set(gr, "HEAVY", label)
            out_ = {"rhand": d.best_weapon(gr, kit_)}
            if kit_["shield"]:
                shield = armor.get("shield") or d.best_shield(gr)
                if shield:
                    out_["lhand"] = shield
            out_.update((slot, armor[slot]) for slot in ("head", "chest", "legs", "gloves", "feet") if slot in armor)
            out_.update((k, v) for k, v in jewels.items() if v)
            if shirt:
                out_["shirt"] = shirt
            return out_

        parts = reaper_parts(kit, name)
        attrs = "".join(f' {k}="{v}"' for k, v in parts.items())
        names = ", ".join(d.items[v]["name"] for v in parts.values())
        # Тела после смерти (REAPER_BODIES): ветка саба — те её классы, что есть у хроники.
        bodies, body_report = [], []
        for b in REAPER_BODIES:
            bkit = KITS[b["kit"]]
            chain = [c for c in b["chain"] if c in d.classes]
            try:
                d.check_chain(chain)
                bparts = reaper_parts(bkit, f"{name}:{b['kit']}") if chain else {}
            except ValueError as e:
                body_report.append(f"Танатос, тело {b['kit']}: пропущено — {e}")
                continue
            if not chain:
                body_report.append(f"Танатос, тело {b['kit']}: пропущено — у хроники нет классов {b['chain']}")
                continue
            bshots = d.blessed_spiritshot(gr) if bkit["weapon"][2] else d.soulshot(gr)
            battrs = "".join(f' {k}="{v}"' for k, v in bparts.items())
            bnames = ", ".join(d.items[v]["name"] for v in bparts.values())
            bclasses = ", ".join(d.classes[c][0] for c in chain)
            bodies.append(f'\t\t<body kit="{b["kit"]}" classes="{",".join(map(str, chain))}"{battrs} shots="{bshots}" /> <!-- {bclasses}: {bnames} -->\n')
            body_report.append(f"Танатос, тело {b['kit']}: {bclasses}: {bnames}")
        g = gk[START_VILLAGE_GK[r["race"]]]
        gz = geo.cell(g["x"], g["y"], g["z"])[0]
        classes = ", ".join(d.classes[cid][0] for cid in r["chain"])
        phantom_xml.append(
            f'\t<!-- {name}: Танатос, единственный ПК сервера; {classes}, всегда {r["level"]}-й -->\n'
            f'\t<phantom name="{name}" kind="reaper" role="DAMAGE" female="false" face="{rnd(name, "face") % 3}" hairStyle="{rnd(name, "hair") % 5}" '
            f'hairColor="{rnd(name, "hc") % 4}" classes="{",".join(map(str, r["chain"]))}" x="{g["x"]}" y="{g["y"]}" z="{gz}">\n'
            f'\t\t<gear grade="{gr}" minLevel="{r["level"]}"{attrs} shots="{d.soulshot(gr)}" /> <!-- {names} -->\n'
            + "".join(bodies) + '\t</phantom>\n')
        phantom_report.append(f"Танатос {name}: {classes}, {r['level']}-й, {gr}: {names}")
        phantom_report.extend(body_report)
        phantom_personas[name] = dict(race=RACE_RU[r["race"]][0], sex=r["sex"], **{"class": d.classes[r["chain"][-1]][0]}, role="жнец", reaper=True,
                                      traits=r["traits"], style=r["style"], bio=r["bio"])

    spots = hunt_spots(d, geo) if HUNTERS else []
    (out / "stats/npcs/custom").mkdir(parents=True, exist_ok=True)
    (out / "L2HubHuntSpots.xml").write_bytes(hunt_spots_xml(spots))
    if spots:
        import hunter_knowledge  # здесь, а не наверху: он сам импортирует этот модуль
        hunter_knowledge.write(d, spots, out)
    (out / "L2HubPhantoms.xml").write_bytes((
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<list xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="xsd/L2HubPhantoms.xsd">\n'
        "\t<!-- L2Hub: напарники-фантомы. Сгенерировано tools/fake_players.py, правки — в таблице COMPANIONS. -->\n"
        + "".join(phantom_xml) + "</list>\n").encode("utf-8"))

    personas["Evi"] = EVI
    head = '<?xml version="1.0" encoding="UTF-8"?>\n<list xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../../../xsd/npcs.xsd">\n'
    comment = "\t<!-- L2Hub: фейковые игроки. Сгенерировано tools/fake_players.py, правки — в таблице PERSONAS. -->\n"
    (out / "stats/npcs/custom/l2hub_fpc.xml").write_bytes((head + comment + "".join(npc_xml) + "</list>\n").encode("utf-8"))
    out_spawn.parent.mkdir(parents=True, exist_ok=True)
    out_spawn.write_bytes(("\n".join([
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<list {LIST_ENABLED}xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../../xsd/spawns.xsd">',
        "\t<!-- L2Hub: фейковые игроки. Сгенерировано tools/fake_players.py. -->",
        '\t<spawn name="L2HubFakePlayers">', *spawn_xml, "\t</spawn>", "</list>", ""])).encode("utf-8"))
    (out / "L2HubFakePersonas.json").write_text(json.dumps({"version": 1, "personas": personas}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (out / "L2HubPhantomPersonas.json").write_text(json.dumps({"version": 1, "personas": phantom_personas}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("\n".join(report + phantom_report))
    print(f"\n{len(table) - len(hunters)} фейков, {len(COMPANIONS)} напарников, {len(hunters)} охотников, {len(spots)} спотов охоты, "
          f"характеров {len(personas) + len(phantom_personas)}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    sys.exit(main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]) if len(sys.argv) == 4 else None))
