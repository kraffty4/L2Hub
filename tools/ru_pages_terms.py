"""Сведение терминов облачного перевода: правки памяти переводов и строки глоссария.

Зовёт ru_pages_cloud.py collect сразу после сбора ответов: collect переписывает память и таблицу облачного
перевода сырыми ответами, а здесь на них заново накладываются решения. Вручную — только на свежем выводе collect:
  python -X utf8 tools/ru_pages_terms.py <память.tsv> <глоссарий из collect>
Оба файла правятся на месте. Правка памяти применяется только к кускам, в английском которых есть термин.
Каждое правило обязано сработать столько раз, сколько записано: иначе память поменялась и правило надо смотреть заново.
"""
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "ru_pages" / "cloud"))
from part import ENDING, stem_of  # noqa: E402  основы — как у переводчика в облаке

MARK = "## Имена и термины облачного перевода"

# (английский термин, русский шаблон, замена, сколько раз)
RULES = [
    ("Guild President", r"\bПрезидент Гильдии", "Глава Гильдии", 2),
    ("Guild President", r"\bПрезидента Гильдии", "Главы Гильдии", 2),
    ("Guild President", r"\bПрезиденту Гильдии", "Главе Гильдии", 1),
    ("Guild President", r"\bПрезидентом Гильдии", "Главой Гильдии", 3),
    ("Pet Manager", r"\bМенеджер питомцев", "Смотритель Питомцев", 1),
    ("Pet Manager", r"\bМенеджером питомцев", "Смотрителем Питомцев", 2),
    ("Beacon Tower", r"\bна Маячной Башне", "на Маяке", 1),
    ("Dark Cloud Mansion", r"\bТемных Туч\b", "Темного Облака", 4),
    ("Test of the Lord", r"\bВластелина\b", "Владыки", 1),
    ("Dubabah", r"\bДубаба\b", "Дубабах", 1),
    ("Violet", r"\bВиолетта\b", "Вайолет", 2),
    ("Gaze of Abyss", r"\bВзглядом Бездны", "Взором Бездны", 2),
    ("Bloody Axe", r"\bЗнак Кровавого Топора", "Bloody Axe Insignia", 1),
    ("Bloody Axe", r"\bКровавый Топор\b", "Bloody Axe", 1),
    ("Bloody Axe", r"\bКровавого Топора\b", "Bloody Axe", 3),
    ("Wings of Sand", r"\bКрылья Песка\b", "Wings of Sand", 1),
    ("Dragon's Tooth", r"\bЗуб Дракона\b", "Dragon's Tooth", 1),
    ("Claymore", r"\bмастером клеймора\b", "мастером Claymore", 1),
    ("Wooden Arrow", r"\bДеревянными Стрелами\b", "Wooden Arrow", 1),
    ("In Search of the Nest", r'"In Search of the Nest"', '"В поисках Гнезда"', 1),
    ("Lakin", r"\bLakin\b", "Лакина", 1),
    ("Helena Blackbird", r"\bЕлен(\w*) Блэкберд", r"Хелен\1 Блэкберд", 1),
    ("Celestiel", r"\bСелестиэл", "Целестиэл", 1),
    ("Colin", r"\bКоллин", "Колин", 7),
    ("Crystal Caverns", r"\bКристальн", "Хрустальн", 1),
    ("Duning", r"\bДаннинг", "Дунинг", 3),
    ("Lillian", r"\bЛиллиан", "Лилиан", 3),
    ("Matras", r"\bМатрас", "Мэтрас", 1),
    ("Tardyon", r"\bТардьон", "Тардион", 1),
    ("Varanka", r"\bВарангк", "Варанк", 1),
    ("Wilbert", r"\bВиль?берт", "Уилберт", 3),
    ("Baenedes", r"\bБенедес", "Баэнедес", 1),
    ("Duda-Mara", r"\bDuda-Mara\b", "Дуда-Мара", 6),
    ("Haniho", r"\bХенри\b", "Ханихо", 1),
    ("Karna", r"\bКарина\b", "Карна", 1),
    ("Basillia", r"\bБазиллия\b", "Базилия", 1),
    ("Mudaha", r"\bМухарк продолжает атаковать!", "Мудаха продолжает атаковать!", 1),
    ("Shyslassys", r"\bШислассис", "Шисласис", 1),
    ("Elmore-Aden", r"\bЭльмореден\b", "Эльмор-Аден", 13),  # королевство, не империя Эльмореден
    ("Hidden Truth", r"\bСкрыт(\w*) Истин", r"Сокрыт\1 Истин", 1),
    # часть L1 (Codex): к форме, сведённой в частях 00-05
    ("Aetonic", r"\bЭйтоник", "Этоник", 2),
    ("Avant-Garde", r"\bАвангард\b", "Avant-Garde", 5),
    ("Baylor", r"\bБайлор", "Бейлор", 1),
    ("Catacomb of Dark Omens", r"\bТемного Предзнаменования\b", "Темных Знамений", 7),
    ("Chamber of Delusion", r"\bПалата Иллюзий", "Чертог Иллюзий", 2),
    ("Chamber of Delusion", r"\bПалаты Иллюзий", "Чертога Иллюзий", 1),
    ("Delusion Chamber", r"\bЗал(\w*) Иллюзий", r"Чертог\1 Иллюзий", 6),
    ("Chromatic Highlands", r"\bРазноцветном Нагорье", "Хроматических Высотах", 1),
    ("Cional", r"\bПрефект Кастор\b", "Префект Сиональ", 1),
    ("Death Penalty", r"\b([Шш]траф\w*) смерти", r"\1 за смерть", 6),
    ("Eilhalder", r"\bЭйльхальдер", "Эйлхальдер", 2),
    ("Fairen", r"\bФэйрен", "Фейрен", 2),
    ("Fantasy Isle", r"\bОстров(\w*) Фантазий", r"Остров\1 Грез", 13),
    ("Herbiel", r"\bГербиэл", "Хербиэл", 2),
    ("Holst", r"\bХольст", "Холст", 2),
    ("Pathfinder", r"\bСледопыта\b", "Первопроходца", 1),
    ("Rakan", r"\bЛакан", "Ракан", 1),
    ("Revolutionaries of Dusk", r"\b(Революционер\w*) Заката\b", r"\1 Сумерек", 34),
    ("Revolutionary of Dusk", r"\b(Революционер\w*) Заката\b", r"\1 Сумерек", 2),
    ("Revolutionary Troops of Dusk", r"\b(Революционер\w*) Заката\b", r"\1 Сумерек", 1),
    ("Richlin", r"\bРихлин", "Ричлин", 1),
    # здесь прав L1: «Рим» в частях - транслитерация вместо «окраины»
    ("Rim Kamaloka", r"\bв Рим Камалоке\b", "в Окрестностях Камалоки", 1),
    ("Rim Kamaloka", r"\bРим Камалок[ау]\b", "Окрестности Камалоки", 3),
    ("Sighardt", r"\bСейгардт", "Сигхардт", 1),
    ("Sophia", r"\bСофи(?=(?:я|и|ю|ей)\b)", "Софь", 2),
    ("Steelrazor", r"\bСтальной Бритвы\b", "Стального Лезвия", 4),
    ("Territory War", r"\bза Территории\b", "за Земли", 4),
    ("Wahkan", r"\bВахкан", "Вакан", 1),
    ("Windmill Hill", r"\bХолм(\w*) Ветряных Мельниц", r"Холм\1 Мельниц", 1),
    # сверка всей игры с L1: имена и классы L1 к глоссарию (классы у L1 - по локализации Иннова)
    ("Dreadnought", r"\bПолководец\b", "Дредноут", 1),
    ("Hierophant", r"\bАпостол\b", "Иерофант", 1),
    ("Sword Muse", r"\bМенестрель\b", "Муза Клинка", 1),
    ("Doombringer", r"\bКаратель\b", "Вестник Рока", 2),
    ("Trickster", r"\bДиверсант\b", "Трикстер", 2),
    ("Pathfinders", r"\bСледопыт(ы|ами|ам|ах)\b", r"Первопроходц\1", 3),
    ("keltirs", r"\b([Кк])елтир", r"\1ельтир", 22),
    ("Keltir", r"\b([Кк])елтир", r"\1ельтир", 1),
    ("Rayla", r"\bРейл(\w*)", r"Райл\1", 1),
    ("Irene", r"\bИрен\b", "Ирэн", 1),
    ("Kathaway", r"\bКатавей\b", "Кэтуэй", 1),
    ("Sentinel", r"\bСтраж (Эриэль|Трионелл)\b", r"Часовой \1", 2),  # не титулы Ghost/Moonlight Sentinel
    ("Vlasty", r"\bВласти\b", "Влэсти", 2),
    ("Warehouse Freightman", r"\bСмотритель Склада\b", "Складской Перевозчик", 3),
    ("Brukurse", r"\bБрукерс", "Брукурс", 2),
    ("Maddy", r"\bМадди\b", "Мэдди", 1),
    ("Herz", r"\bГерц\b", "Херц", 1),
    ("Seer Henri", r"\bПровидец Анри\b", "Провидица Хенри", 1),
    ("Beres", r"\bБелефа\b", "Береса", 1),  # было чужое имя
    ("Hestia", r"\bХестия\b", "Гестия", 1),
    ("Elrokhians", r"\bэлроки\b", "элрокианы", 2),
    ("Kookaburras", r"\bо Kookaburra\b", "о кукабаррах", 1),
    ("human", r"\bстать его живым щитом\b", "стать людским щитом для него", 1),
    ("Ifrit", r'"Ifrit"', '"Ифрит"', 1),
    ("Phoenix", r'"Phoenix"', '"Феникс"', 1),
    ("Salamander", r'"Salamander"', '"Саламандра"', 1),
    ("Leonard", r"\bLeonard\b", "Леонард", 1),
    ("Faded Visitor's Mark", r"\bVisitor's Mark, Faded Visitor's Mark\b", "Знак Посетителя, Выцветший Знак Посетителя", 1),
]

# Каноническая форма в глоссарии; основа None — посчитать по форме, иначе своя (несколько через запятую)
CANON = {
    "Alvah": ("Альва", None), "Baenedes": ("Баэнедес", None), "Beacon Tower": ("Маяк", None),
    "Bloody Axe": ("=", None), "Buryun": ("Бурьюн", None), "Cardien": ("Кардиен", None),
    "Celestiel": ("Целестиэль", None), "Chairen": ("Чайрен", None), "Claymore": ("=", None),
    "Colin": ("Колин", None), "Crystal Caverns": ("Хрустальные Пещеры", None),
    "Dark Cloud Mansion": ("Особняк Темного Облака", None), "Dinn": ("Дин", None),
    "Dion Castle Village": ("деревня у Замка Дион", "дион"), "Dragon's Tooth": ("=", None),
    "Dubabah": ("Дубабах", None), "Duda-Mara": ("Дуда-Мара", None), "Duning": ("Дунинг", None),
    "Entien": ("Энтиен", None), "Exarion": ("Эксарион", None), "Gaze of Abyss": ("Взор Бездны", None),
    "Grizzly Bear": ("Медведь Гризли", None), "Guild President": ("Глава Гильдии", None),
    "Helena Blackbird": ("Хелена Блэкберд", None), "Helmut": ("Хелмут", None),
    "In Search of the Nest": ("В поисках Гнезда", None), "Kunai": ("Кунай", None), "Lakin": ("Лакин", None),
    "Lawrence": ("Лоренс", None), "Lillian": ("Лилиан", None), "Linus": ("Линус", None),
    "Matras": ("Мэтрас", None), "Mimyu": ("Мимю", None), "Nyakuri": ("Ньякури", None), "Pabel": ("Пабел", None),
    "Pet Manager": ("Смотритель Питомцев", None), "Raien": ("Райен", None), "Raymond": ("Раймонд", None),
    "Seer": ("Провидец", "провид"), "Singsing": ("Сингсинг", None),
    "Slay the Enemy Commander": ("Убить Вражеского Командира", None), "Tardyon": ("Тардион", None),
    "Test of the Lord": ("Испытание Владыки", None), "Varanka": ("Варанка", None), "Violet": ("Вайолет", None),
    "Wilbert": ("Уилберт", None), "Wings of Sand": ("=", None), "Wooden Arrow": ("=", None), "Woodrow": ("Вудроу", None),
    "Haniho": ("Ханихо", None), "Seer Haniho": ("Провидица Ханихо", "провидиц"),
    # имена из части 00, которые текст пишет иначе: правится строка, текст прав
    "Karna": ("Карна", None), "Dufner": ("Дафнер", None), "Basillia": ("Базилия", None), "Rouke": ("Рук", None),
    "Cadmus": ("Кадмус", "кадм"),
    # латиница в списках монстров законна
    "Platinum": ("Платиновый", "платинов, platinum"), "Hallate": ("Халлат", "халлат, hallate"),
    # обычные слова: во всём тексте синонимы допустимы (пристань, посторонние, покровитель)
    "relic": ("реликвия", ""), "wharf": ("причал", ""), "outsider": ("чужак", ""), "sponsor": ("спонсор", ""),
    "apprentice": ("ученик", ""), "Reed": ("Рид", ""),
    "Berry": ("ягода", ""),
    "Collector": ("Собиратель", "собирател, коллекцион"), "dinosaur": ("динозавр", "динозавр, завр, dinosaur"),
    # монстры и NPC без имени собственного: правило - латиница, но в прозе их перевели или транслитерировали
    "Dwarf Adventurer": ("=", "-"), "Kranrots": ("=", "-"), "Silver Fairy": ("=", "-"), "Chest of Shyslassys": ("=", "-"),
    "Delu Lizardman": ("=", "-"), "Leto Lizardman Overlords": ("=", "-"), "Lienriks": ("=", "-"), "Ol Mahum Novices": ("=", "-"),
    "Soul Coffins": ("=", "-"),
    # разнобой из последних файлов части 04
    "Dr. Chaos": ("Доктор Хаос", None), "Hidden Truth": ("Сокрытая Истина", None), "Kbaldir": ("Кбалдир", None),
    # разнобой части L1 (Codex): с правилом выше
    "Aetonic": ("Этоник", None), "Avant-Garde": ("=", None), "Baylor": ("Бейлор", None),
    "Catacomb of Dark Omens": ("Катакомбы Темных Знамений", None), "Chamber of Delusion": ("Чертог Иллюзий", None),
    "Delusion Chamber": ("Чертог Иллюзий", None), "Chromatic Highlands": ("Хроматические Высоты", None),
    "Cional": ("Сиональ", None), "Death Penalty": ("штраф за смерть", None), "Eilhalder": ("Эйлхальдер", None),
    "Fairen": ("Фейрен", None), "Fantasy Isle": ("Остров Грез", None), "Fantasy Isle Paddies": ("Падди с Острова Грез", None),
    "Herbiel": ("Хербиэль", None), "Holst": ("Холст", None), "Pathfinder": ("Первопроходец", None),
    "Pathfinders": ("Первопроходцы", None), "Rakan": ("Ракан", None), "Revolutionaries of Dusk": ("Революционеры Сумерек", None),
    "Richlin": ("Ричлин", None), "Rim Kamaloka": ("Окрестности Камалоки", None), "Sighardt": ("Сигхардт", None),
    "Sophia": ("Софья", None), "Steelrazor": ("Стальное Лезвие", None), "Territory War": ("Битва за Земли", None),
    "Wahkan": ("Вакан", None), "Windmill Hill": ("Холм Мельниц", None),
    # разнобой части L1 без правила: регистр и окончания, или L1 оставил латиницей (латиница сверку проходит)
    "Breka's Stronghold": ("Крепость Брека", None), "Buffalo": ("Буйвол", None),
    "Catacomb of the Apostate": ("Катакомбы Отступника", None), "Catacomb of the Heretic": ("Катакомбы Еретика", None),
    "Catacomb of the Witch": ("Катакомбы Ведьмы", None), "Crokian": ("крокиан", None), "Crokians": ("крокианы", None),
    "Darion's Badges": ("Знаки Дариона", None), "Deinonychus": ("дейноних", None), "Drake": ("Дрейк", None),
    "Fishing King Championship Tournament": ('Чемпионат "Король Рыбалки"', None), "Garvarentz": ("Гарваренц", None),
    "Golden Ram Coins": ("Монеты Золотого Овна", None), "Ifrit": ("Ифрит", None), "Isle of Prayer": ("Остров Молитв", None),
    "Kakai": ("Какай", None), "Ketra Orc": ("Орк Кетры", None), "Lavasaurus": ("лавазавр", None), "Leonard": ("Леонард", None),
    "Liane": ("Лиана", None), "Lunargent": ("лунаргент", None),
    "Faded Visitor's Mark": ("Выцветший Знак Посетителя", None), "Visitor's Mark": ("Знак Посетителя", None),
    "Marks of Betrayal": ("Знаки Предательства", None), "Mixing Stone": ("Смесительный Камень", None),
    "Mother Nornil": ("Матерь Норнил", None), "Mucrokians": ("мукрокианы", None),
    "Necropolis of Sacrifice": ("Некрополь Жертвоприношений", None), "Nephilim": ("нефилим", None), "Nidrah": ("Нидра", None),
    "Noctisse": ("Ноктисса", None), "Nornil's Cave": ("Пещера Норнила", None), "Nornil's Garden": ("Сад Норнила", None),
    "Phoenix": ("Феникс", None), "Roy the Cat": ("Кот Рой", None), "Salamander": ("Саламандра", ""),
    "Seal Stones": ("Камни Печати", None), "Sione": ("Сионе", None), "Soul Breaker": ("Крушитель Душ", None),
    "Spine Mine": ("Хребтовая Шахта", None), "Spine Mines": ("Хребтовые Шахты", None), "Statue of Future": ("=", "-"),
    "Sub Class": ("сабкласс", "сабкласс, дополнительн"), "Supplementary Magic": ("Вспомогательная Магия", None),
    "Support Magic": ("Поддерживающая Магия", None), "Tower of Naia": ("Башня Наи", None), "Utuku Orcs": ("Орки Утуку", None),
    "Varka Silenos": ("Силены Варки", None), "Varka Silenos Outpost": ("Аванпост Силенов Варки", None),
    "Windawood Manor": ("Поместье Виндавуд", None), "Wyrm": ("Вирм", None), "Wyvern": ("Виверна", ""),
    "Clan Hall": ("Холл клана", "холл, обител, клан"),
    # строки таблицы L1, которые сверка применила к тексту частей 00-05: там переведено
    "Star Stones": ("Звездные Камни", None), "Iron": ("Айрон", "айрон, желез"),
    # предметы смешивания: в L1 латиницей рядом с Blood Root и Volcanic Ash, в части 03 переведены
    "Wyrm's Blood": ("=", "-"), "Moonstone Shards": ("=", "-"),
    "Violet": ("Вайолет", "вайолет, фиолет"), "subclass": ("сабкласс", "сабкласс, subclass, дополнительн"),
    # интерфейс клиента и монстры в прозе: латиница разрешена, в L1 переведено
    "World Map": ("=", "-"), "Character Info": ("=", "-"), "POV": ("=", "-"), "Ol Mahum Patrols": ("=", "-"),
    "Blood Offering": ("=", "-"),
}
# Чего не было в таблице, но нужно
EXTRA = {"Bloody Axe Insignia": ("=", None), "Breaker": ("=", "-"), "Elmore-Aden": ("Эльмор-Аден", "эльмор-аден")}
NOTE = {"Seer": "сведено: Провидица - по полу NPC", "Haniho": "исправлено: было Хенри", "Seer Haniho": "исправлено: было Провидица Хенри",
        "Karna": "исправлено: было Карина", "Dufner": "исправлено: было Дюфнер", "Basillia": "сведено с текстом",
        "Rouke": "исправлено: было Рурк", "Cadmus": "склоняют и Кадм", "Platinum": "в списках монстров латиницей",
        "Hallate": "в списках монстров латиницей", "relic": "обычное слово, синонимы допустимы", "wharf": "обычное слово, синонимы допустимы",
        "outsider": "обычное слово, синонимы допустимы", "sponsor": "обычное слово, синонимы допустимы",
        "apprentice": "обычное слово, синонимы допустимы", "Reed": "обычное слово, сверка не проверяет",
        "Berry": "исправлено: было Беррос; обычное слово, сверка не проверяет", "Collector": "Клуб Коллекционеров",
        "dinosaur": "в тексте и вид: Тираннозавр"}
NOTE["Elmore-Aden"] = "королевство; не путать с империей Эльмореден"
NOTE["Bloody Axe Insignia"] = "предмет, латиницей"
NOTE["Breaker"] = "умение Soul Breaker: латиницей, как в клиенте"
NOTE.update({"Clan Hall": "в L1 - обитель клана, клановая обитель: шов", "Rim Kamaloka": "исправлено: было Рим Камалока",
             "Cional": "исправлено: в L1 было Кастор", "Avant-Garde": "латиницей; в L1 было Авангард",
             "Spine Mine": "в L1 - Хребтовые Рудники", "Spine Mines": "в L1 - Хребтовые Рудники",
             "Salamander": "обычное слово, сверка не проверяет", "Wyvern": "обычное слово, сверка не проверяет; в L1 латиницей",
             "Statue of Future": "латиница разрешена, не проверяется"})
for _en in ("Dwarf Adventurer", "Kranrots", "Silver Fairy", "Chest of Shyslassys", "Delu Lizardman", "Leto Lizardman Overlords", "Lienriks", "Ol Mahum Novices", "Soul Coffins"):
    NOTE[_en] = "латиница разрешена: в прозе переведено"


def fix(tm_path: Path, gl_path: Path) -> int:
    """Правила по памяти, каноника и нормализация таблицы облачного перевода. 1 — правило сработало не столько раз или разнобой без решения."""
    lines = tm_path.read_text(encoding="utf-8").splitlines()
    bad = 0
    for term, pat, rep, want in RULES:
        rx_en = re.compile(r"\b" + re.escape(term) + r"\b")
        rx = re.compile(pat)
        n = 0
        for i, line in enumerate(lines):
            en, sep, ru = line.partition("\t")
            if sep and rx_en.search(en):
                ru2, k = rx.subn(rep, ru)
                if k:
                    lines[i] = en + "\t" + ru2
                    n += k
        if n != want:
            print(f"правило {term!r} {pat!r}: сработало {n}, ждали {want}")
            bad += 1
    tm_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    text = gl_path.read_text(encoding="utf-8")
    head, mark, table = text.partition(MARK)
    if not mark:
        print("в глоссарии нет таблицы облачного перевода")
        return 1
    english = re.sub(r"<[^<>]*>", " ", "\n".join(l.partition("\t")[0] for l in lines))
    # слова, которые в английском тексте бывают и строчными (не в bypass, путях и сокращениях)
    words = re.findall(r"(?<![A-Za-z'_/.0-9-])[A-Za-z]+(?![A-Za-z'_/.0-9-])", english)
    low, cap = Counter(w for w in words if w.islower()), Counter(w.lower() for w in words if w[:1].isupper())
    # английский Mobius небрежен с регистром (kasha bear, beleth): обычное слово — где строчных хотя бы четверть
    common = {w for w, n in low.items() if n >= 2 and n * 4 >= n + cap[w]}
    rules_by_term = [(re.compile(r"\b" + re.escape(t) + r"\b"), re.compile(p), r) for t, p, r, _ in RULES]

    def stems_all(ru: str) -> str:
        """Основы всех слов перевода: годится любая, поэтому Торговцем Ародином проходит по ародин. Беглые гласные: торговец/торговц, замок/замк, котел/котл."""
        out = []
        for w in re.findall(r"[а-я]+", ru.lower().replace("ё", "е")):
            if len(w) < 3 or w in ("для", "при", "над", "под", "без", "или"):
                continue
            s = ENDING.sub("", re.sub(r"(?:ами|ями|ов|ев|ам|ям|ах|ях)$", "", w) if len(w) > 5 else w)  # Големов -> голем
            s = s if len(s) >= 2 else w  # как stem_of: слабая проверка короткого имени лучше ложной ошибки на падеже (Ева, Евы)
            out.append(s)
            for a, b in (("ец", "ц"), ("ок", "к"), ("ел", "л"), ("ень", "н"), ("ен", "н")):
                if s.endswith(a) and len(s) - len(a) + len(b) >= 3:
                    out.append(s[: -len(a)] + b)
        return ", ".join(dict.fromkeys(out))

    out, seen, left = [], set(), []
    dropped, plain = [], []
    for row in table.split("\n"):
        cells = [c.strip() for c in row.strip().strip("|").split("|")] if row.startswith("| ") else None
        if not cells or len(cells) != 4 or cells[0] in ("English", "") or set(cells[0]) <= {"-", " ", ":"}:
            out.append(row)
            continue
        en, ru, stem, note = cells
        if en in CANON:
            ru, custom = CANON[en]
            stem = custom if custom is not None else ("" if ru == "=" else stems_all(ru))
            note = NOTE.get(en, "сведено" + (": было " + note.split(": ", 1)[1] if note.startswith("разнобой: ") else ""))
            seen.add(en)
        else:
            if note.startswith("разнобой"):
                left.append(en)
            for rx_en, rx, rep in rules_by_term:
                if rx_en.search(en):
                    ru = rx.sub(rep, ru)
            # мусор из ответов: обрывки слов, буквы, «перевод» тем же английским, латиница для строчного слова
            junk = len(en) <= 2 or en.endswith("-") or en.startswith("-") or not re.match(r"[A-Za-z]", en)
            if ru != "=" and (junk or ru.lower() == en.lower()):
                dropped.append(en)
                continue
            if ru == "=" and (junk or en.islower() or all(w.lower() in common for w in re.findall(r"[A-Za-z]+", en))):
                stem, note = "-", "латиница разрешена, не проверяется"  # обрывок или обычные слова: в прозе их переводят
                plain.append(en)
            elif " " not in en and en[:1].isupper() and en.lower() in common:
                stem, note = "", "обычное слово, сверка не проверяет"
                plain.append(en)
            elif ru != "=":
                stem = stems_all(ru)
                if en.islower():
                    stem = ", ".join(filter(None, [stem, en.lower()]))  # строчное имя монстра бывает и латиницей
        if en in EXTRA:
            seen.add(en)
        out.append(f"| {en} | {ru} | {stem} | {note} |")
    print(f"выброшено строк-мусора {len(dropped)}: {dropped[:40]}")
    print(f"обычных слов без проверки {len(plain)}: {plain[:60]}")
    for en, (ru, stem) in EXTRA.items():
        if en not in seen:
            out.insert(len(out) - 1 if out and out[-1] == "" else len(out), f"| {en} | {ru} | {stem or ''} | {NOTE.get(en, '')} |")
    gl_path.write_text(head + mark + "\n".join(out), encoding="utf-8", newline="\n")
    missing = sorted(set(CANON) - seen)
    print(f"правил с другим числом срабатываний: {bad}; строк глоссария сведено {len(seen)}; нет в таблице: {missing}; разнобой без решения: {left}")
    return 1 if bad or left else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(fix(Path(sys.argv[1]), Path(sys.argv[2])))
