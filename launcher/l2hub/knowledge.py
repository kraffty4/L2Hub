"""База знаний охотников («Разум 2.0», docs/MIND.md, раздел 3): кусок под охотника в запрос к мозгам.

Файл базы — game/data/L2HubHunterKnowledge.json сервера (пишет tools/hunter_knowledge.py из датапака сборки, в сервер его кладёт
сборка из модуля фантомов). В запрос попадает только своё: учебник с правилами опыта этой сборки, свой спот и споты из вариантов
стратега, шмот своего и следующего грейда, следующая профа; в разговоре — ещё споты и рейд-боссы под его уровень.
Цена профы — из PhantomHunters.ini сервера (HunterProfessionPrices), рейт опыта — из его Rates.ini (RateXp, его меняют режимы
сервера): это меняет автор, база этого не знает.
"""

import json
import math
import re
from pathlib import Path

FILE = "L2HubHunterKnowledge.json"
SPOTS_NEAR = 4      # спотов под уровень в разговоре
RAIDS_NEAR = 4      # рейд-боссов под уровень в разговоре
SPOT_RE = re.compile(r"на споте (.+?) \(мобы")
ARMOR_RU = {"HEAVY": "тяжёлые", "LIGHT": "лёгкие", "MAGIC": "робы"}
# Общие истины игрока: верны для всех сборок Mobius; то, что от сборки зависит (опыт, смерть, грейды), берётся из базы.
RULES = [
    "С сосами урон заметно выше: без них мобы падают дольше и опыта в час меньше.",
    "Агрессивные мобы нападают сами: на агро-споте трудно сесть отдохнуть и легко собрать паровоз.",
    "В пати мобы падают быстрее, опыт делится между теми, кто рядом; с хилером можно брать мобов посильнее.",
    "Рейд-босса в одиночку не убить — нужна пати или рейд.",
]
# «Где качаться?» (docs/PHANTOMS.md, 4.147; docs/MIND.md, «Где качаться»): совет новичку — только когда об этом спросили.
ASK_SPOT = re.compile(r"(?i)(?<![а-яёa-z])(по)?кач(?!еств)|(?<![а-яёa-z])ап(?:н|а[тю])|спот|где\s+(лучше\s+)?(фарм|бить|мобы)|куда\s+(идти|пойти|бежать|лучше)"
                      r"|фарм(ить|лю)?\s+где|where\b.*\b(level|lvl|farm|grind|xp|exp)|\bspots?\b|\bgrind")
ADVICE_SPOTS = 3     # мест в совете
# Роль по классу — как HuntSpotData.role в ядре: саммонер, иначе PhantomBufferSets.classRole (танк, кинжал, лук, хил, маг, воин).
ROLE_CLASSES = {
    "summoner": {"WARLOCK", "ARCANA_LORD", "ELEMENTAL_SUMMONER", "ELEMENTAL_MASTER", "PHANTOM_SUMMONER", "SPECTRAL_MASTER"},
    "tank": {"KNIGHT", "PALADIN", "DARK_AVENGER", "ELVEN_KNIGHT", "TEMPLE_KNIGHT", "PALUS_KNIGHT", "SHILLIEN_KNIGHT", "PHOENIX_KNIGHT",
             "HELL_KNIGHT", "EVA_TEMPLAR", "SHILLIEN_TEMPLAR"},
    "dagger": {"ROGUE", "TREASURE_HUNTER", "ADVENTURER", "ELVEN_SCOUT", "PLAINS_WALKER", "WIND_RIDER", "ASSASSIN", "ABYSS_WALKER", "GHOST_HUNTER"},
    "archer": {"HAWKEYE", "SAGITTARIUS", "SILVER_RANGER", "MOONLIGHT_SENTINEL", "PHANTOM_RANGER", "GHOST_SENTINEL", "ARBALESTER", "TRICKSTER"},
    "healer": {"CLERIC", "BISHOP", "CARDINAL", "PROPHET", "HIEROPHANT", "ORACLE", "ELDER", "EVA_SAINT", "SHILLIEN_ORACLE", "SHILLIEN_ELDER",
               "SHILLIEN_SAINT", "ORC_SHAMAN", "OVERLORD", "DOMINATOR", "WARCRYER", "DOOMCRYER"},
}
ROLE_RU = {"summoner": "саммонера", "tank": "танка", "dagger": "кинжальщика", "archer": "лучника", "healer": "хила", "mage": "мага", "fighter": "воина"}
# ЧаВо новичков (docs/MIND.md, «ЧаВо новичков»): ответы из базы (ключ faq, tools/hunter_faq/<id>.json), сверенные с датапаком.
# Подбор по словам: редкие основы весят больше (idf), до трёх ближайших вопросов — какой из них про то, решает модель.
FAQ_PICK = 3         # вопросов-кандидатов в запрос
FAQ_MIN = 2.0        # совпало веса основ не меньше этого
FAQ_COVER = 0.34     # и не меньше этой доли веса вопроса
FAQ_NEAR = 0.5       # кандидаты не слабее этой доли лучшего
FAQ_STOP = set("""где как что это на в с а и ли можно мне у до по за чтоб чтобы какои какая какие какую каком какого куда когда почему
зачем сколько надо нужно нужен нужна тоже уже еще вообще хоть его ее их он она они я ты вы мы то же бы не ни да нет или так там тут
здесь есть был была было от из к о об при для без про чем чего кто весь все брать взять делать дальше самому сам свои свой мои мой
твои твой стать стал стала""".split())
FAQ_LAT = [("shch", "щ"), ("sch", "щ"), ("zh", "ж"), ("kh", "х"), ("ch", "ч"), ("sh", "ш"), ("ts", "ц"), ("yu", "ю"), ("ya", "я"),
           ("yo", "е"), ("ye", "е")]
FAQ_LAT1 = dict(zip("abvgdezijklmnoprstufhcyxwq", "абвгдезиийклмнопрстуфхцыксвк"))
FAQ_EN = {"grade": "грейд", "siege": "осада", "craft": "крафт", "drop": "дроп", "success": "успех", "warrior": "воин", "auto": "авто",
          "poison": "яд", "bleed": "кровоток", "oly": "олимп", "olympiad": "олимп", "lvl": "лвл", "level": "лвл", "sub": "саб",
          "main": "основа", "buff": "баф", "nobless": "нобл", "noble": "нобл", "pk": "пк", "trade": "обмен", "spoil": "спойл"}
FAQ_SYN = {"соск": "сос", "сосо": "сос", "сс": "сос", "бсс": "сос", "сосы": "сос", "профе": "проф", "дворя": "нобл", "уров": "лвл",
           "левел": "лвл", "олимп": "оли", "олли": "оли", "бафф": "баф", "мейн": "основ", "лвла": "лвл", "академ": "акад", "трансф": "транс"}
FAQ_ORD = {"1": "#1", "перв": "#1", "2": "#2", "втор": "#2", "3": "#3", "трет": "#3"}
FAQ_GRADE = {"д": "@d", "d": "@d", "ц": "@c", "c": "@c", "б": "@b", "b": "@b", "ы": "@s", "s": "@s"}
FAQ_ENDS = sorted("ами ями ого его ому ему ыми ими ой ей ий ый ая яя ое ее ую юю ом ем ам ям ах ях ов ев ы и а я о е у ю ь й".split(),
                  key=len, reverse=True)
FAQ_WORD = re.compile(r"[a-zа-я0-9]+")
# Вопрос к самому фейку («ты нобл?», «какой у тебя саб») — не ЧаВо; «не знаешь, где…», «подскажи» — ЧаВо.
FAQ_ABOUT_YOU = re.compile(r"(?i)(?<![а-яёa-z])(ты|тебя|тебе|твой|твоя|твои|ty|tebya|tebe)(?![а-яёa-z])")
FAQ_ASK_YOU = re.compile(r"(?i)знаешь|подскаж|скажи|в курсе|znaesh|podskaj")


def _faq_lat(w: str) -> str:
    for a, b in FAQ_LAT:
        w = w.replace(a, b)
    return "".join(FAQ_LAT1.get(ch, ch) for ch in w)


def _faq_stem(w: str) -> str | None:
    if w in FAQ_GRADE:
        return FAQ_GRADE[w]
    if w.isascii() and w.isalpha():
        w = FAQ_EN.get(w) or _faq_lat(w)
    w = w.replace("й", "и")
    if w.isdigit():
        return FAQ_ORD.get(w)
    if len(w) < 2 or w in FAQ_STOP:
        return None
    for e in FAQ_ENDS:
        if w.endswith(e) and len(w) - len(e) >= 3:
            w = w[:-len(e)]
            break
    for k, v in FAQ_ORD.items():
        if not k.isdigit() and w.startswith(k):
            return v
    for k in sorted(FAQ_SYN, key=len, reverse=True):
        if w.startswith(k):
            return FAQ_SYN[k]
    return w[:5]


def faq_stems(text: str) -> set[str]:
    """Основы слов для подбора ЧаВо: транслит и английские слова — по-русски, окончания срезаны, «2»/«вторая» и «б»/«b» — одно."""
    return {s for s in (_faq_stem(w) for w in FAQ_WORD.findall((text or "").lower().replace("ё", "е"))) if s}


def adena(n: int | float) -> str:
    """412000 → 412к, 1510200 → 1.5кк, 90 → 90."""
    n = int(n)
    if n < 10_000:
        return str(n)
    if n < 1_000_000:
        return f"{round(n / 1000)}к"
    return f"{n / 1_000_000:.1f}".rstrip("0").rstrip(".") + "кк"


def spot_from_state(state: str) -> str:
    """Имя спота из состояния охотника в разговоре («качаешься на споте X (мобы 30-35 ур.)»)."""
    m = SPOT_RE.search(state or "")
    return m.group(1) if m else ""


class Knowledge:
    def __init__(self, data: Path, config: Path):
        self.path = data / FILE
        self.hunters_ini = config / "Custom" / "PhantomHunters.ini"
        self.rates_ini = config / "Rates.ini"
        self._kb, self._kb_mtime = None, None
        self._ini: dict[Path, tuple[float, str]] = {}

    def kb(self) -> dict | None:
        """База, перечитанная при смене файла; нет файла или он битый — None (мозги тогда без знаний)."""
        try:
            mtime = self.path.stat().st_mtime
        except OSError:
            return None
        if mtime != self._kb_mtime:
            try:
                self._kb = json.loads(self.path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                self._kb = None
            self._kb_mtime = mtime
        return self._kb

    def _ini_value(self, path: Path, key: str) -> str:
        """Значение ключа из ini сервера (файл перечитывается при смене); нет файла или ключа — пусто."""
        try:
            mtime = path.stat().st_mtime
        except OSError:
            return ""
        if self._ini.get(path, (None,))[0] != mtime:
            self._ini[path] = (mtime, path.read_text(encoding="utf-8", errors="replace"))
        m = re.search(rf"^\s*{key}\s*=\s*(.+?)\s*$", self._ini[path][1], re.M)
        return m.group(1) if m else ""

    def prices(self) -> list[tuple[int, int]]:
        """[(уровень, цена профы)] из HunterProfessionPrices = 20:50000;40:1000000;76:10000000."""
        return sorted((int(a), int(b)) for a, b in re.findall(r"(\d+)\s*:\s*(\d+)", self._ini_value(self.hunters_ini, "HunterProfessionPrices")))

    def rate_xp(self) -> float:
        try:
            return float(self._ini_value(self.rates_ini, "RateXp") or 1)
        except ValueError:
            return 1.0

    # ---------- куски ----------

    def _exp_mul(self, kb: dict, diff: int) -> float:
        """Доля опыта за моба при разнице «уровень персонажа − уровень моба», как считает сервер этой сборки."""
        rule = kb.get("exp", {})
        if rule.get("rule") == "window":
            return 1.0 if abs(diff) < rule["max"] else 0.0
        if rule.get("rule") == "grey":
            return 1.0 if diff < rule["from"] else rule["mul"].get(str(diff), rule["else"])
        if rule.get("rule") == "below":
            return 0.0 if diff > rule["max"] else 1.0
        return 1.0

    def _rules(self, kb: dict, level: int) -> list[str]:
        rule = kb.get("exp", {})
        out = []
        if rule.get("rule") == "window":
            out.append(f"Опыт с моба полный, пока он не дальше {rule['max'] - 1} ур. от тебя в любую сторону; дальше — опыта ноль.")
        elif rule.get("rule") == "grey":
            steps = ", ".join(f"{k} — {round(v * 100)}%" for k, v in rule["mul"].items())
            out.append(f"Моб ниже тебя на {rule['from']}+ ур. даёт меньше опыта (на сколько ур. ниже — доля опыта): {steps}, ещё ниже — "
                       f"{round(rule['else'] * 100)}%. Моб выше тебя даёт опыт полностью.")
        elif rule.get("rule") == "below":
            out.append(f"Опыт с моба полный, пока он ниже тебя не больше чем на {rule['max']} ур.; ещё ниже — опыта ноль.")
        loss = kb.get("deathLoss") or []
        if 0 < level < len(loss) and loss[level]:
            out.append(f"Смерть от моба снимает {loss[level]:g}% опыта твоего уровня.")
        return out + RULES

    def _need(self, kb: dict, level: int) -> int:
        levels = kb.get("levels") or []
        return levels[level + 1] - levels[level] if 0 < level < len(levels) - 1 else 0

    def spot_line(self, kb: dict, name: str, level: int) -> str:
        s = kb.get("spots", {}).get(name)
        if not s:
            return ""
        need = self._need(kb, level)
        mobs, gained, total = [], 0.0, 0
        for mob_name, mob_level, aggro, count, exp, _hp in s["mobs"]:
            mobs.append(f"{mob_name} {mob_level}" + (" агр" if aggro else "") + ("" if exp else " без опыта"))
            gained += count * exp * self._exp_mul(kb, level - mob_level) * self.rate_xp()
            total += count
        aggro = s.get("aggro", 0)
        parts = [f"мобы: {', '.join(mobs)}", "все агрессивные" if aggro >= 0.9 else "мирные" if aggro == 0 else f"агрессивных ~{round(aggro * 100)}%"]
        if need and total:
            pct = gained / total / need * 100
            parts.append("опыта тебе с них нет" if pct == 0 else f"тебе ~{pct:.2f}% уровня за моба" if pct < 0.1 else f"тебе ~{pct:.1f}% уровня за моба")
        if s.get("adena"):
            parts.append(f"адена ~{adena(s['adena'])} за моба")
        if s.get("drop"):
            parts.append("дроп: " + ", ".join(f"{n} (~{adena(p)}, раз в ~{k} мобов)" for n, p, k in s["drop"]))
        if s.get("spoil"):
            parts.append("спойл: " + ", ".join(f"{n} (~{adena(p)}, {c:g}%)" for n, p, c in s["spoil"]))
        if s.get("raids"):
            parts.append("рядом РБ: " + ", ".join(f"{n} ({lv})" for n, lv in s["raids"]))
        return f"Спот {name} ({s['region']}, мобы {s['min']}-{s['max']} ур.): " + "; ".join(parts) + "."

    def gear_line(self, kb: dict, level: int, mage: bool) -> str:
        """Свой и следующий грейд; сеты под его броню (маг — робы), оружие, сосы (магу не нужны: у него BSS)."""
        grades = kb.get("grades") or []
        cur = next((g for g in reversed(grades) if g["level"] <= level), None)
        nxt = next((g for g in grades if g["level"] > level), None)
        if cur is None:
            return ""
        # Сеты — того грейда, к которому он готовится: следующего, если до него 5 ур. или меньше.
        show = nxt if nxt and nxt["level"] - level <= 5 else cur
        head = f"Грейды: сейчас твой {cur['grade']}" + (f", следующий {nxt['grade']} — с {nxt['level']} ур." if nxt else ".")
        kinds = ["MAGIC"] if mage else ["HEAVY", "LIGHT"]
        sets = "; ".join(f"{ARMOR_RU[k]} — " + ", ".join(f"{n} ~{adena(p)}" for n, p in show["sets"][k])
                         for k in kinds if show["sets"].get(k))
        parts = [head]
        if sets:
            parts.append(f"Сеты {show['grade']} (цена магазина за весь сет): {sets}.")
        if show.get("weapons"):
            lo, mid = show["weapons"]
            parts.append(f"Оружие {show['grade']} — от ~{adena(lo)} до ~{adena(mid)}.")
        if not mage and show.get("shot"):
            parts.append(f"{show['shot'][0]} — {show['shot'][1]} адены за штуку.")
        return " ".join(parts)

    def class_line(self, kb: dict, level: int, cls: str) -> str:
        c = kb.get("classes", {}).get(cls)
        if not c or not c["next"]:
            return ""
        prices = self.prices()
        if c["tier"] >= len(prices):
            return ""
        at, price = prices[c["tier"]]
        when = f"с {at} ур." + (f" (тебе ещё {at - level} ур.)" if level < at else " — ты уже дорос")
        cost = f" у мастера классов за {adena(price)} адены" if price else ", даром (дают сразу на уровне)"  # 0 — у Essence
        return f"Профа: ты {c['name']}; следующая {when}{cost}: {' или '.join(c['next'])}."

    def near(self, kb: dict, level: int, skip: set[str]) -> list[tuple[str, dict]]:
        """Споты под уровень (мобы с опытом, середина — от −4 до +2 ур.), самые людные первыми; одно место — один раз:
        «Devils Isle · восток» и «Devils Isle · юг» — это Devils Isle, берётся самый людный кусок. Места из skip — мимо."""
        found = []
        for name, s in kb.get("spots", {}).items():
            mid = (s["min"] + s["max"]) / 2
            if name not in skip and s.get("exp") and level - 4 <= mid <= level + 2:
                found.append((-s["count"], name, s))
        found.sort(key=lambda x: (x[0], x[1]))
        places, picked = {x.split(" · ")[0] for x in skip if x}, []
        for _, name, s in found:
            place = name.split(" · ")[0]
            if place not in places:
                places.add(place)
                picked.append((name, s))
        return picked

    def choices(self, kb: dict, level: int, skip: set[str], count: int) -> list[str]:
        """Споты для перехода по решению стратега ([[SPOT_TO]]): те, куда ядро поведёт, — он их не перерос и ему не рано
        (уровень от min−4 до max+3 мобов спота, docs/PHANTOMS.md, 4.3, «Протокол v1»), мобы с опытом; ближе к его уровню —
        первыми, одно место — один раз, места из skip — мимо."""
        found = sorted((abs((s["min"] + s["max"]) / 2 - level), -s["count"], name) for name, s in kb.get("spots", {}).items()
                       if name not in skip and s.get("exp") and s["min"] - 4 <= level <= s["max"] + 3)
        places, picked = {x.split(" · ")[0] for x in skip if x}, []
        for _, _, name in found:
            place = name.split(" · ")[0]
            if place not in places:
                places.add(place)
                picked.append(name)
        return picked[:count]

    @staticmethod
    def spot_brief(name: str, s: dict) -> str:
        """«Cruma Tower · 1 этаж (Dion, 34-38, агр)»."""
        return f"{name} ({s['region']}, {s['min']}-{s['max']}" + (", агр" if s.get("aggro", 0) >= 0.5 else "") + ")"

    def near_spots(self, kb: dict, level: int, skip: set[str]) -> str:
        picked = self.near(kb, level, skip)[:SPOTS_NEAR]
        return ("Ещё споты под твой уровень: " + "; ".join(self.spot_brief(n, s) for n, s in picked) + ".") if picked else ""

    def near_raids(self, kb: dict, level: int) -> str:
        found = sorted((r for r in kb.get("raids", []) if level - 2 <= r[1] <= level + 5), key=lambda r: (abs(r[1] - level - 1), r[1], r[0]))
        if not found:
            return ""
        return "Рейд-боссы твоего уровня: " + "; ".join(f"{n} ({lv}" + (f", у {place})" if place else ")") for n, lv, place in found[:RAIDS_NEAR]) + "."

    @staticmethod
    def role(kb: dict, cls: str) -> str:
        """Роль собеседника для спотов из гайдов: по имени класса (как в ядре), иначе маг или воин по базе классов."""
        for role, names in ROLE_CLASSES.items():
            if cls in names:
                return role
        return "mage" if kb.get("classes", {}).get(cls, {}).get("mage") else "fighter"

    def advice(self, message: str, level: int, cls: str) -> str:
        """Совет «где качаться» собеседнику его уровня и роли — только когда спросили (ASK_SPOT). Места, куда ядро пустит
        (как choices: уровень от min−4 до max+3), с опытом; сперва те, что гайды игроков советуют его роли, потом те, о которых
        гайды молчат, советуемые только другим ролям — нет. Одно место — один раз, ближе к его уровню — первыми. Нет базы — пусто."""
        kb = self.kb()
        if not kb or level <= 0 or not ASK_SPOT.search(message or ""):
            return ""
        role = self.role(kb, cls)
        ranked = []
        for name, s in kb.get("spots", {}).items():
            if not s.get("exp") or not s["min"] - 4 <= level <= s["max"] + 3:
                continue
            roles = s.get("guide", "").split()
            if roles and role not in roles:
                continue
            ranked.append((0 if roles else 1, abs((s["min"] + s["max"]) / 2 - level), -s["count"], name, s))
        ranked.sort(key=lambda x: x[:4])
        places, picked = set(), []
        for tier, _, _, name, s in ranked:
            place = name.split(" · ")[0]
            if place not in places and len(picked) < ADVICE_SPOTS:
                places.add(place)
                picked.append((tier, name, s))
        if not picked:
            return ""
        def brief(name: str, s: dict) -> str:
            return self.spot_brief(name, s)[:-1] + (", лучше в пати" if "party" in s.get("guide", "").split() else "") + ")"
        guided = [brief(n, s) for t, n, s in picked if t == 0]
        other = [brief(n, s) for t, n, s in picked if t == 1]
        parts = []
        if guided:
            parts.append(f"игроки в гайдах советуют {ROLE_RU[role]} на {level} ур.: " + "; ".join(guided))
        if other:
            parts.append(("ещё подходят по уровню: " if guided else f"на {level} ур. подходят: ") + "; ".join(other))
        return ("Если собеседник спрашивает, где качаться: " + ". ".join(parts)
                + ". Посоветуй одно-два места своими словами, как игрок; других мест не придумывай.")

    def _faq_index(self, kb: dict) -> tuple[list[tuple[dict, list[set[str]]]], dict[str, float]]:
        """Основы вопросов ЧаВо и вес основ (idf), один раз на версию базы."""
        if getattr(self, "_faq_for", None) is not kb:
            entries = [(e, [faq_stems(x) for x in (e["q"], *e.get("v", []))]) for e in kb.get("faq", [])]
            df: dict[str, int] = {}
            for _, texts in entries:
                for s in set().union(*texts):
                    df[s] = df.get(s, 0) + 1
            self._faq, self._faq_for = (entries, {s: math.log((len(entries) + 1) / c) for s, c in df.items()}), kb
        return self._faq

    def faq(self, message: str) -> str:
        """ЧаВо новичков под вопрос собеседника: до FAQ_PICK ближайших вопросов с ответами, сверенными с датапаком.
        Нет ЧаВо в базе, вопрос про самого фейка или ничего не похоже — пусто."""
        kb = self.kb()
        if not kb or not kb.get("faq") or not message or (FAQ_ABOUT_YOU.search(message) and not FAQ_ASK_YOU.search(message)):
            return ""
        entries, weight = self._faq_index(kb)
        said = faq_stems(message)
        scored = []
        for n, (e, texts) in enumerate(entries):
            best = 0.0
            for ts in texts:
                total = sum(weight.get(s, 0) for s in ts) or 1.0
                got = sum(weight.get(s, 0) for s in said & ts)
                if got >= FAQ_MIN and got / total >= FAQ_COVER:
                    best = max(best, got * got / total)
            if best:
                scored.append((-best, n, e))
        if not scored:
            return ""
        scored.sort(key=lambda x: x[:2])
        picked = [e for score, _, e in scored[:FAQ_PICK] if score <= scored[0][0] * FAQ_NEAR]
        return ("Если собеседник спрашивает о чём-то из этого, вот как на этом сервере (проверено; ответь своими словами коротко, "
                "как игрок, чисел и имён не меняй; спрашивает о другом — не используй): "
                + " ".join(f"«{e['q']}» — {e['a']}" for e in picked))

    def text(self, level: int, cls: str, spots: list[str], mage: bool | None = None, chat: bool = False) -> str:
        """Кусок базы под охотника. mage не известен (разговор) — по классу; chat — для разговора: ещё споты и рейд-боссы
        под его уровень. Нет базы — пусто."""
        kb = self.kb()
        if not kb or level <= 0:
            return ""
        if mage is None:
            mage = bool(kb.get("classes", {}).get(cls, {}).get("mage"))
        lines = self._rules(kb, level)
        seen = []
        for name in spots:
            if name and name not in seen:
                seen.append(name)
                lines.append(self.spot_line(kb, name, level))
        if chat:
            lines += [self.near_spots(kb, level, set(seen)), self.near_raids(kb, level)]
        lines += [self.gear_line(kb, level, mage), self.class_line(kb, level, cls)]
        body = "\n".join(f"- {x}" for x in lines if x)
        return ("Что ты знаешь об игре на этом сервере (это правда, опирайся на неё; чего тут нет — не выдумывай):\n" + body) if body else ""
