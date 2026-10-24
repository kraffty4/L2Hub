"""База знаний охотников («Разум 2.0», docs/MIND.md, раздел 3): кусок под охотника в запрос к мозгам.

Файл базы — game/data/L2HubHunterKnowledge.json сервера (пишет tools/hunter_knowledge.py из датапака сборки, в сервер его кладёт
сборка из модуля фантомов). В запрос попадает только своё: учебник с правилами опыта этой сборки, свой спот и споты из вариантов
стратега, шмот своего и следующего грейда, следующая профа; в разговоре — ещё споты и рейд-боссы под его уровень.
Цена профы — из PhantomHunters.ini сервера (HunterProfessionPrices), рейт опыта — из его Rates.ini (RateXp, его меняют режимы
сервера): это меняет автор, база этого не знает.
"""

import json
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
        return f"Профа: ты {c['name']}; следующая {when} у мастера классов за {adena(price)} адены: {' или '.join(c['next'])}."

    def near_spots(self, kb: dict, level: int, skip: set[str]) -> str:
        found = []
        for name, s in kb.get("spots", {}).items():
            mid = (s["min"] + s["max"]) / 2
            if name not in skip and s.get("exp") and level - 4 <= mid <= level + 2:
                found.append((-s["count"], name, s))
        found.sort(key=lambda x: (x[0], x[1]))
        # Одно место — один раз: «Devils Isle · восток» и «Devils Isle · юг» — это Devils Isle, берётся самый людный кусок.
        places, picked = set(), []
        for _, name, s in found:
            place = name.split(" · ")[0]
            if place not in places and place not in {x.split(" · ")[0] for x in skip if x}:
                places.add(place)
                picked.append((name, s))
        if not picked:
            return ""
        return "Ещё споты под твой уровень: " + "; ".join(
            f"{name} ({s['region']}, {s['min']}-{s['max']}" + (", агр" if s.get("aggro", 0) >= 0.5 else "") + ")"
            for name, s in picked[:SPOTS_NEAR]) + "."

    def near_raids(self, kb: dict, level: int) -> str:
        found = sorted((r for r in kb.get("raids", []) if level - 2 <= r[1] <= level + 5), key=lambda r: (abs(r[1] - level - 1), r[1], r[0]))
        if not found:
            return ""
        return "Рейд-боссы твоего уровня: " + "; ".join(f"{n} ({lv}" + (f", у {place})" if place else ")") for n, lv, place in found[:RAIDS_NEAR]) + "."

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
