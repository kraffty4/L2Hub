"""Модель экономики охотников по данным датапака High Five: новые правила (патч 0021) против старых (0017–0020).

Опыт и доход считаются на одно убийство: на каждом уровне охотник бьёт мобов спота своего уровня (уровень моба L-3..L, как pickSpot),
медиана по всем монстрам датапака. Время охоты идёт по убийствам: --kills-per-hour с сосами, без сосов — в --no-shot-speed раз медленнее.
От времени зависят походы в город (сосы, банки, телепорт), квесты и пересчёт в часы; деньги на уровень от скорости почти не зависят.

Правила — как в PhantomHunterOutfit и PhantomHunterManager:
- поход в город: раз в --town-minutes (интервал 120, лавка, смерти — в среднем), раньше — если кончились стрелы, сдаёт квест,
  или сосов меньше 20 % запаса и бюджета хватает на 100 (не чаще раза в 10 минут);
- в городе по порядку: сдать квест, сдать хлам, профа (дорос и хватает — платит; нет — копит), шмот по частям и бижа после сета
  (с запасом на 300 сосов, 500 стрел, телепорт и отложенное на профу; старое — NPC за полцены), стрелы на всю адену,
  банки (четверть свободной адены, копит на профу — десятая всей), сосы, телепорт на спот с 40-го;
- новые правила: адена ×--adena-rate (Rates.ini), шанс дропа вещей охотнику ×--drop-chance, сосы — не больше --shot-budget %
  свободной адены (свободная — всё, кроме цены профы, до которой дорос; меньше чем на 100 сосов — не берёт, бьёт без них),
  профы за адену, квесты-симуляция, порог лавки по уровню (у пачек — цена всей пачки за поход);
- старые правила: x1, сосы на половину оставшейся адены, профы бесплатно, квестов нет, порог лавки 20 000 за штуку.

Хлам — NPC за полцены (вариант «лавки» — дорогое по полной цене, верхняя оценка: продаётся ли, зависит от живых покупателей).
Не моделируются: смерти, время в городе и в лавке, удачный дроп шмота, слабость без профы, пати.

Запуск: python -X utf8 -I tools/hunter_economy.py <папка data сборки> [--kills-per-hour 180] [--shots 3] [--adena-scale 1] …
(все ключи — --help)
"""
import argparse
import re
import statistics
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

GRADE_LEVEL = {"NONE": 0, "D": 1, "C": 2, "B": 3, "A": 4, "S": 5, "S80": 6, "S84": 7}
EXPERTISE = [(84, 7), (80, 6), (76, 5), (61, 4), (52, 3), (40, 2), (20, 1), (1, 0)]
SOULSHOTS = [1835, 1463, 1464, 1465, 1466, 1467, 1467, 1467]
SPIRITSHOTS = [3947, 3948, 3949, 3950, 3951, 3952, 3952, 3952]
ARROWS = [17, 1341, 1342, 1343, 1344, 1345, 1345, 1345]
SLOTS = ["rhand", "chest", "legs", "head", "gloves", "feet", "lhand"]
# Бижа: шея, две серьги, два кольца — самая дешёвая обычная своего грейда (как PhantomHunterOutfit.jewels).
JEWEL_KIND = {"neck": 0, "rear;lear": 1, "rfinger;lfinger": 2}
JEWEL_SLOTS = [0, 1, 1, 2, 2]
JEWEL_SKIP = re.compile(r" - |Commendation|Event|Shadow|Common|Limited|Sealed|Olympiad|Hero|Lord|Test|Monster|Pvp|PvP|Retail|Baguette|Valakas|Antharas|Baium|Zaken|Orfen|Core|Queen Ant|Frintezza|Beleth|Freya")
HEALING_POTIONS = [(20, 1060), (40, 1061), (999, 1539)]  # хилка своего уровня: до 20, до 40, дальше
SHOT_RESERVE = 300
AMMO_RESERVE = 500
AMMO_LOAN = 300
RESTOCK_MIN = 100
AMMO_COUNT = 3000
POTION_COUNT = 30
LOW_SUPPLIES = 0.2  # сосов меньше 20 % запаса — повод в город
TOWN_SOFT_COOLDOWN = 10  # минут: по сосам — не чаще
OLD_STORE_MIN = 20000
# Замеры 2026-10-18 на 14–22 уровнях (медиана по охотникам): старые правила — ~680 адены в час чистыми в кармане;
# новые правила — ~6 500 адены в час подобрано на споте (earnedPerHour из L2HubHunters.json, первые ~10 минут после старта).
REAL_BAND, REAL_OLD_NET, REAL_NEW_ADENA = (14, 22), 680, 6500
BANDS = [(1, 9), (10, 19), (20, 29), (30, 39), (40, 51), (52, 60), (61, 75), (76, 84)]


def expertise(level):
    return next(g for lvl, g in EXPERTISE if level >= lvl)


def level_map(value):
    """«уровень:значение;…» → [(уровень, значение)] по возрастанию."""
    pairs = []
    for pair in value.split(";"):
        parts = pair.strip().split(":")
        if len(parts) == 2 and parts[0].strip().isdigit() and parts[1].strip().isdigit():
            pairs.append((int(parts[0]), int(parts[1])))
    return sorted(pairs)


def floor_value(pairs, level, default):
    found = [v for lvl, v in pairs if lvl <= level]
    return found[-1] if found else default


def load(data: Path):
    items = {}
    for f in (data / "stats/items").glob("*.xml"):
        # Только предметы верхнего уровня: внутри капсул (Fast Growth Pack и т. п.) есть вложенные <item id=…> без цены.
        for it in ET.parse(f).getroot().findall("item"):
            sets = {x.get("name"): x.get("val") for x in it.findall("set")}
            items[int(it.get("id"))] = dict(name=it.get("name") or "", type=it.get("type"), price=int(sets.get("price", "0") or 0),
                                             grade=GRADE_LEVEL.get(sets.get("crystal_type", "NONE"), 0), wtype=sets.get("weapon_type"),
                                             sellable=sets.get("is_sellable", "true") != "false", tradable=sets.get("is_tradable", "true") != "false",
                                             quest=sets.get("is_questitem") == "true", stackable=sets.get("is_stackable") == "true",
                                             bodypart=sets.get("bodypart"))
    exp = {int(e.get("level")): int(e.get("tolevel")) for e in ET.parse(data / "stats/players/experience.xml").getroot().iter("experience")}

    mobs = defaultdict(list)  # уровень -> [(опыт, адена x1, [(предмет, среднее число, шанс x1)])]
    for f in (data / "stats/npcs").glob("*.xml"):
        for n in ET.parse(f).getroot().iter("npc"):
            if n.get("type") != "Monster":
                continue
            acq = n.find("acquire")
            mexp = int(acq.get("exp", "0")) if acq is not None else 0
            if mexp <= 0:
                continue
            adena = 0.0
            drops = []
            for group in n.iter("group"):
                gchance = float(group.get("chance", "100")) / 100
                for it in group.iter("item"):
                    amount = (int(it.get("min", "1")) + int(it.get("max", "1"))) / 2
                    chance = float(it.get("chance", "0")) / 100 * gchance
                    iid = int(it.get("id"))
                    if iid == 57:
                        adena += amount * chance
                        continue
                    t = items.get(iid)
                    if not t or t["price"] <= 0 or t["quest"] or not t["sellable"]:
                        continue
                    drops.append((iid, amount, chance))
            mobs[int(n.get("level", "0"))].append((mexp, adena, drops))

    # Каталог бижи [грейд][шея, серьга, кольцо]: самая дешёвая обычная.
    jewels = [[0, 0, 0] for _ in range(8)]
    for iid, t in items.items():
        kind = JEWEL_KIND.get(t["bodypart"])
        if kind is None or t["price"] <= 0 or not t["sellable"] or not t["tradable"] or t["quest"] or JEWEL_SKIP.search(t["name"]):
            continue
        g = min(7, t["grade"])
        if not jewels[g][kind] or t["price"] < items[jewels[g][kind]]["price"]:
            jewels[g][kind] = iid
    return items, exp, mobs, jewels


def hunters(data: Path, items):
    result = []
    for ph in ET.parse(data / "L2HubPhantoms.xml").getroot().iter("phantom"):
        if ph.get("kind") != "hunter":
            continue
        gear = []
        for g in ph.iter("gear"):
            pieces = {s: int(g.get(s)) for s in SLOTS if g.get(s)}
            gear.append(dict(grade=GRADE_LEVEL[g.get("grade")], min=int(g.get("minLevel")), pieces=pieces))
        mage = ph.get("role") == "HEALER" or any(items.get(int(g.get("rhand", "0")), {}).get("name", "").find("Staff") >= 0 for g in ph.iter("gear")) or "3947" in [g.get("shots") for g in ph.iter("gear")]
        result.append(dict(name=ph.get("name"), gear=gear, mage=mage))
    return result


class Rules:
    """Набор правил экономики: новые (0021) или старые (0017–0020)."""

    def __init__(self, args, new):
        self.new = new
        self.name = "новые правила" if new else "старые правила"
        self.adena_rate = args.adena_rate if new else 1
        self.drop_mult = args.drop_chance if new else 1
        self.shot_budget = args.shot_budget / 100 if new else 0.5
        self.professions = level_map(args.professions) if new else []
        self.quest_levels = sorted(int(x) for x in args.quest_levels.split(",") if x.strip().isdigit()) if new and args.quest_reward > 0 else []
        minutes = [int(x) for x in args.quest_minutes.split(",") if x.strip().isdigit()] or [20]
        self.quest_minutes = statistics.mean(minutes[:2])
        self.quest_reward = args.quest_reward
        self.store_min = level_map(args.store_min) if new else [(1, OLD_STORE_MIN)]


class Model:
    def __init__(self, args, items, exp, mobs, jewels):
        self.args, self.items, self.exp, self.mobs, self.jewels = args, items, exp, mobs, jewels
        self.cache = {}

    def per_kill(self, level, rules, store_mode):
        """(опыт, адена, хлам/2, с лавками) на убийство: медианы по мобам L-3..L с множителями правил."""
        key = (level, rules.new, store_mode)
        if key in self.cache:
            return self.cache[key]
        pool = [m for lvl in range(max(1, level - 3), level + 1) for m in self.mobs.get(lvl, [])]
        result = None
        if pool:
            threshold = floor_value(rules.store_min, level, 10 ** 12)
            kills_per_trip = self.args.kills_per_hour * self.args.town_minutes / 60
            rows = []
            for mexp, adena, drops in pool:
                junk = store = 0.0
                for iid, amount, chance in drops:
                    t = self.items[iid]
                    value = amount * min(1.0, chance * rules.drop_mult) * t["price"]
                    junk += value / 2
                    # Новые правила: у пачек в лавку идёт вся пачка, накопленная за поход; старые — по цене штуки.
                    good = (value * kills_per_trip >= threshold) if (rules.new and t["stackable"]) else t["price"] >= threshold
                    store += value if good else value / 2
                rows.append((mexp, adena * rules.adena_rate, junk, store))
            result = tuple(statistics.median(r[i] for r in rows) for i in range(4))
        self.cache[key] = result
        return result


class Sim:
    """Один охотник от 1 до 85 по одному набору правил."""

    def __init__(self, model, h, rules, store_mode):
        self.m, self.items, self.args = model, model.items, model.args
        self.h, self.rules, self.store_mode = h, rules, store_mode
        self.worn = dict(h["gear"][0]["pieces"])  # стартовый шмот
        self.jewels = [0] * len(JEWEL_SLOTS)
        self.cash = self.bag = 0.0  # адена в кармане; хлам в сумке (по цене сдачи)
        self.shots = self.ammo = self.potions = 0.0
        self.tier = 0
        self.quest = self.quest_done = 0
        self.quest_left = 0.0
        self.since = 0.0  # минут охоты с прошлого города
        self.level = 1
        self.bought = []  # (уровень, грейд, слот)
        self.profs = {}  # ступень -> уровень покупки
        self.stats = defaultdict(lambda: defaultdict(float))  # уровень -> минуты, доход, сосы, квесты, …
        self.cash_at = {}

    # ---------- Расходники ----------

    def weapon(self):
        return self.items.get(self.worn.get("rhand", 0), {})

    def ranged(self):
        return self.weapon().get("wtype") in ("BOW", "CROSSBOW")

    def shot_id(self, grade=None):
        g = min(7, self.weapon().get("grade", 0) if grade is None else grade)
        return (SPIRITSHOTS if self.h["mage"] else SOULSHOTS)[g]

    def price(self, iid):
        return self.items[iid]["price"]

    def due(self):
        """Цена профы, до которой дорос, но не купил; 0 — такой нет."""
        tiers = self.rules.professions
        return tiers[self.tier][1] if self.tier < len(tiers) and self.level >= tiers[self.tier][0] else 0

    def shot_budget(self):
        free = max(0.0, self.cash - self.due()) if self.rules.new else self.cash
        return free * self.rules.shot_budget

    def can_restock(self):
        need = min(self.price(self.shot_id()), self.price(ARROWS[min(7, self.weapon().get("grade", 0))]) if self.ranged() else 10 ** 9)
        return self.shot_budget() >= need * RESTOCK_MIN

    def supplies_left(self):
        left = min(1.0, self.shots / max(1, self.args.shot_count))
        return min(left, self.ammo / AMMO_COUNT) if self.ranged() else left

    # ---------- Квесты ----------

    def take_quest(self):
        if self.quest:
            return
        best = max((q for q in self.rules.quest_levels if self.quest_done < q <= self.level), default=0)
        if best:
            self.quest, self.quest_left = best, self.rules.quest_minutes

    # ---------- Охота ----------

    def run(self):
        exp = self.m.exp
        for level in range(1, 85):
            self.level = level
            self.take_quest()
            pk = self.m.per_kill(level, self.rules, self.store_mode)
            need = exp.get(level + 1, 0) - exp.get(level, 0)
            if pk is None or need <= 0:
                self.cash_at[level] = self.cash
                continue
            left = need / pk[0]
            while left > 1e-6:
                if self.needs_town():
                    self.town()
                left -= self.hunt(left, pk)
            self.cash_at[level] = self.cash
        return self

    def hunt(self, left, pk):
        """Охота до ближайшего события: конец уровня, город, кончились сосы, квест сделан. @return убийств"""
        a = self.args
        spk = a.mage_shots if self.h["mage"] and a.mage_shots is not None else a.shots
        armed = self.shots >= 1
        kpm = a.kills_per_hour / 60 * (1 if armed else a.no_shot_speed)
        cands = [left, max(0.0, a.town_minutes - self.since) * kpm]
        if self.quest and self.quest_left > 0:
            cands.append(self.quest_left * kpm)
        if armed:
            cands.append(self.shots / spk)
            low = LOW_SUPPLIES * a.shot_count
            if self.shots > low:
                cands.append((self.shots - low) / spk)
            elif self.since < TOWN_SOFT_COOLDOWN:
                cands.append((TOWN_SOFT_COOLDOWN - self.since) * kpm)
        if self.ranged() and self.ammo > 0:
            cands.append(self.ammo / spk)
        step = min([c for c in cands if c > 1e-6] or [left])
        minutes = step / kpm
        st = self.stats[self.level]
        _, adena, junk, store = pk
        scale = a.adena_scale
        loot = (store if self.store_mode else junk) * scale
        self.cash += step * adena * scale
        self.bag += step * loot
        st["gross"] += step * (adena * scale + loot)
        st["adena"] += step * adena * scale
        st["minutes"] += minutes
        if armed:
            self.shots = max(0.0, self.shots - step * spk)
            st["armed"] += minutes
        if self.ranged():
            self.ammo = max(0.0, self.ammo - step * spk)
        self.potions = max(0.0, self.potions - minutes / 60 * a.potions_per_hour)
        self.since += minutes
        if self.quest and self.quest_left > 0:
            self.quest_left = max(0.0, self.quest_left - minutes)
        return step

    def needs_town(self):
        if self.since < 1:
            return False
        if (self.ranged() and self.ammo <= 0) or self.since >= self.args.town_minutes or (self.quest and self.quest_left <= 0):
            return True
        return self.since >= TOWN_SOFT_COOLDOWN and self.supplies_left() < LOW_SUPPLIES and self.can_restock()

    # ---------- Город ----------

    def spend(self, amount, what):
        self.cash -= amount
        self.stats[self.level][what] += amount

    def town(self):
        a, st = self.args, self.stats[self.level]
        self.since = 0.0
        st["trips"] += 1
        if self.quest and self.quest_left <= 0:
            reward = self.rules.quest_reward * self.quest * self.quest
            self.cash += reward
            st["quests"] += reward
            self.quest_done, self.quest = self.quest, 0
        self.cash += self.bag
        self.bag = 0.0

        # Профа — первой; не накопил — копит, остальное только со свободной адены.
        while (d := self.due()) > 0 and self.cash >= d:
            self.spend(d, "profs")
            self.tier += 1
            self.profs[self.tier] = self.level
        reserve = self.due()
        self.buy_gear(reserve)

        # Стрелы — на всю адену; нет ни стрел, ни денег — пачка в долг.
        if self.ranged():
            p = self.price(ARROWS[min(7, self.weapon().get("grade", 0))])
            n = max(0, min(AMMO_COUNT - int(self.ammo), int(self.cash // p)))
            if n:
                self.ammo += n
                self.spend(n * p, "ammo")
            elif self.ammo <= 0:
                self.ammo += AMMO_LOAN

        # Банки: четверть свободной адены (копит на профу — десятая всей).
        free = max(0.0, self.cash - reserve)
        budget = free / 4 if free > 0 else self.cash / 10
        pid = next(i for lvl, i in HEALING_POTIONS if self.level < lvl)
        n = max(0, min(POTION_COUNT - int(self.potions), int(budget // self.price(pid))))
        if n:
            self.potions += n
            self.spend(n * self.price(pid), "potions")

        # Сосы: новые — бюджет HunterShotBudget % свободной адены и не меньше чем на 100; старые — половина оставшейся.
        p = self.price(self.shot_id())
        budget = self.shot_budget()
        n = max(0, min(a.shot_count - int(self.shots), int(budget // p)))
        if n and (not self.rules.new or budget >= p * RESTOCK_MIN):
            self.shots += n
            self.spend(n * p, "shots")

        # Телепорт обратно на спот с 40-го — сколько есть.
        if self.level >= 40:
            self.spend(min(self.cash, a.teleport_fee), "teleport")
        self.take_quest()

    def reserve_for(self, grade, ranged):
        g = min(7, grade)
        shot = (SPIRITSHOTS if self.h["mage"] else SOULSHOTS)[g]
        return SHOT_RESERVE * self.price(shot) + (AMMO_RESERVE * self.price(ARROWS[g]) if ranged else 0) + (self.args.teleport_fee if self.level >= 40 else 0)

    def buy_gear(self, profession_reserve):
        items = self.items
        old_shot, old_ammo = self.shot_id(), ARROWS[min(7, self.weapon().get("grade", 0))]
        grades = sorted((g for g in self.h["gear"] if g["min"] <= self.level and g["grade"] <= expertise(self.level)), key=lambda x: -x["grade"])
        stopped = False
        for slot in SLOTS:
            cur = self.worn.get(slot)
            cur_grade = items[cur]["grade"] if cur else -1
            saving = False
            for g in grades:
                iid = g["pieces"].get(slot)
                if not iid:
                    continue
                if items[iid]["grade"] <= cur_grade or cur == iid:
                    break
                saving = True
                ranged = items[g["pieces"]["rhand"]].get("wtype") in ("BOW", "CROSSBOW") if slot == "rhand" and g["pieces"].get("rhand") else self.ranged()
                if self.cash - self.reserve_for(g["grade"], ranged) - profession_reserve < items[iid]["price"]:
                    continue
                self.spend(items[iid]["price"], "gear")
                if cur:
                    self.cash += items[cur]["price"] / 2
                self.worn[slot] = iid
                self.bought.append((self.level, g["grade"], slot))
                saving = False
                break
            if saving:
                stopped = True
                break

        # Броня собрана — бижа своего грейда.
        if not stopped and grades:
            g = min(7, grades[0]["grade"])
            reserve = self.reserve_for(g, self.ranged()) + profession_reserve
            for i, kind in enumerate(JEWEL_SLOTS):
                jid = next((self.m.jewels[x][kind] for x in range(g, -1, -1) if self.m.jewels[x][kind]), 0)
                cur = self.jewels[i]
                if not jid or (cur and items[cur]["grade"] >= items[jid]["grade"]):
                    continue
                if self.cash - reserve < items[jid]["price"]:
                    break
                self.spend(items[jid]["price"], "gear")
                if cur:
                    self.cash += items[cur]["price"] / 2
                self.jewels[i] = jid

        # Новое оружие — сосы и стрелы прошлого грейда NPC за полцены.
        if self.shot_id() != old_shot and self.shots:
            self.cash += self.shots * self.price(old_shot) / 2
            self.shots = 0.0
        if self.ranged() and ARROWS[min(7, self.weapon().get("grade", 0))] != old_ammo and self.ammo:
            self.cash += self.ammo * self.price(old_ammo) / 2
            self.ammo = 0.0

    # ---------- Итоги ----------

    def minutes_to(self, level):
        return sum(self.stats[l]["minutes"] for l in range(1, level))

    def band(self, a, b):
        """Полоса уровней: доход/ч, сосы/ч, квесты, в кармане на конце, чистыми с 1-го, доля времени с сосами, часы, походов/ч, телепорт/ч."""
        st = [self.stats[l] for l in range(a, b + 1)]
        hours = sum(s["minutes"] for s in st) / 60
        net = sum(s["gross"] + s["quests"] - s["shots"] - s["ammo"] - s["potions"] - s["teleport"] for l in range(1, b + 1) for s in [self.stats[l]])
        return (sum(s["gross"] for s in st) / max(hours, 1e-9), sum(s["shots"] + s["ammo"] for s in st) / max(hours, 1e-9),
                sum(s["quests"] for s in st), self.cash_at.get(b, 0.0), net, sum(s["armed"] for s in st) / max(hours * 60, 1e-9), hours,
                sum(s["trips"] for s in st) / max(hours, 1e-9), sum(s["teleport"] for s in st) / max(hours, 1e-9))


def first_level(bought, grade, slots=None, h=None):
    got = [b for b in bought if b[1] == grade and (slots is None or b[2] in slots)]
    if not got:
        return None
    if slots is None:
        # Весь набор грейда: все его вещи куплены (у цельного доспеха штанов нет).
        want = next((set(g["pieces"]) for g in h["gear"] if g["grade"] == grade), set())
        return max(b[0] for b in got) if want <= {b[2] for b in got} else None
    return min(b[0] for b in got)


def money(v):
    v = round(v)
    if abs(v) >= 10 ** 6:
        return f"{v / 10 ** 6:.1f}кк"
    if abs(v) >= 10 ** 4:
        return f"{v / 1000:.0f}к"
    return f"{v:,}"


def columns(sim, rules):
    h = sim.h
    cols = [sim.profs.get(t) if rules.new else lvl for t, lvl in ((1, 20), (2, 40), (3, 76))]
    for grade in (1, 2, 3):
        cols += [first_level(sim.bought, grade, ("rhand",)), first_level(sim.bought, grade, None, h)]
    cols += [first_level(sim.bought, 4, ("rhand",)), first_level(sim.bought, 5, ("rhand",))]
    return cols


HEADER = f"{'охотник':<12} {'1 пр':>5} {'2 пр':>5} {'3 пр':>5} {'D ор.':>5} {'D сет':>5} {'C ор.':>5} {'C сет':>5} {'B ор.':>5} {'B сет':>5} {'A ор.':>5} {'S ор.':>5}"


def medians(sims, rules):
    rows = [[c if c is not None else 99 for c in columns(s, rules)] for s in sims]
    return [statistics.median(r[i] for r in rows) for i in range(len(rows[0]))]


def main():
    ap = argparse.ArgumentParser(description="Модель экономики охотников: новые правила (0021) против старых.")
    ap.add_argument("data", type=Path)
    ap.add_argument("--kills-per-hour", type=float, default=180, help="убийств в час с сосами (допущение)")
    ap.add_argument("--no-shot-speed", type=float, default=0.65, help="скорость убийств без сосов, доля от скорости с сосами (допущение)")
    ap.add_argument("--shots", type=float, default=3, help="сосов (и стрел у лучника) на убийство (допущение)")
    ap.add_argument("--mage-shots", type=float, default=None, help="BSS на убийство у магов (по умолчанию — как --shots)")
    ap.add_argument("--town-minutes", type=float, default=60, help="минут охоты между обычными походами в город (допущение)")
    ap.add_argument("--potions-per-hour", type=float, default=4, help="выпитых банок в час (допущение)")
    ap.add_argument("--adena-scale", type=float, default=1, help="поправка дохода с убийства к датапаку (1 — как в датапаке)")
    ap.add_argument("--adena-rate", type=float, default=3, help="Rates.ini: DropAmountMultiplierByItemId 57")
    ap.add_argument("--drop-chance", type=float, default=2, help="HunterDropChanceMultiplier")
    ap.add_argument("--shot-budget", type=float, default=30, help="HunterShotBudget, %% свободной адены")
    ap.add_argument("--shot-count", type=int, default=2000, help="HunterShotCount")
    ap.add_argument("--professions", default="20:50000;40:1000000;76:10000000", help="HunterProfessionPrices")
    ap.add_argument("--quest-levels", default="6,10,14,17,20,23,26,30,34,38,42,46,50,55,60,65,70,75,80", help="HunterQuestLevels")
    ap.add_argument("--quest-minutes", default="15,25", help="HunterQuestMinutes (берётся среднее)")
    ap.add_argument("--quest-reward", type=int, default=40, help="HunterQuestReward (× уровень²; 0 — квестов нет)")
    ap.add_argument("--store-min", default="1:2000;20:5000;40:20000;52:50000;61:100000;76:300000", help="HunterStoreMinPrices")
    ap.add_argument("--teleport-fee", type=int, default=15000, help="HunterTeleportFee")
    args = ap.parse_args()
    items, exp, mobs, jewels = load(args.data)
    model = Model(args, items, exp, mobs, jewels)
    new, old = Rules(args, True), Rules(args, False)

    print(f"Доход на убийство по уровню охотника (мобы L-3..L, медиана по всем монстрам датапака); справа — новые правила (адена ×{args.adena_rate:g}, шанс вещей ×{args.drop_chance:g}):")
    print(" ур.   опыт  адена  хлам/2  с лавками | адена  хлам/2  с лавками  убийств на уровень")
    for level in (5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80):
        o, n = model.per_kill(level, old, True), model.per_kill(level, new, True)
        oj, nj = model.per_kill(level, old, False)[2], model.per_kill(level, new, False)[2]
        need = exp.get(level + 1, 0) - exp.get(level, 0)
        print(f" {level:>3} {o[0]:>6.0f} {o[1]:>6.0f} {oj:>7.0f} {o[3]:>10.0f} | {n[1]:>5.0f} {nj:>7.0f} {n[3]:>10.0f} {need / o[0]:>12,.0f}")

    hs = hunters(args.data, items)
    results = {}
    for store_mode in (False, True):
        for rules in (new, old):
            results[(rules.new, store_mode)] = [Sim(model, h, rules, store_mode).run() for h in hs]

    for store_mode in (False, True):
        sims = results[(True, store_mode)]
        print()
        print("Вариант:", "дорогой дроп продаётся в лавке по полной цене" if store_mode else "весь дроп — NPC за полцены", "— на каком уровне купил (новые правила)")
        print(HEADER)
        for s in sims:
            print(f"{s.h['name']:<12} " + " ".join(f"{(c if c is not None else '—'):>5}" for c in columns(s, new)))
        print(f"{'медиана':<12} " + " ".join(f"{c:>5.0f}" for c in medians(sims, new)) + "   (99 — не купил до 85)")
        print(f"{'было (старые)':<12}" + " ".join(f"{c:>5.0f}" for c in medians(results[(False, store_mode)], old)) + "   (профы тогда бесплатно)")

        for rules in (new, old):
            sims = results[(rules.new, store_mode)]
            print()
            print(f"По полосам уровней, {rules.name}, медиана по охотникам (чистыми — доход + квесты − сосы, стрелы, банки, телепорты с 1-го уровня):")
            print(f" {'уровни':<7} {'часов':>6} {'доход/ч':>8} {'сосы/ч':>7} {'с сосами':>8} {'город/ч':>7} {'тп/ч':>6} {'квесты':>7} {'чистыми':>8} {'в кармане':>9}")
            for a, b in BANDS:
                rows = [s.band(a, b) for s in sims]
                med = [statistics.median(r[i] for r in rows) for i in range(9)]
                print(f" {f'{a}–{b}':<7} {med[6]:>6.1f} {money(med[0]):>8} {money(med[1]):>7} {med[5]:>7.0%} {med[7]:>7.1f} {money(med[8]):>6} {money(med[2]):>7} {money(med[4]):>8} {money(med[3]):>9}")

    print()
    for rules in (new, old):
        sims = results[(rules.new, False)]
        hours = {lvl: statistics.median(s.minutes_to(lvl) for s in sims) / 60 for lvl in (20, 40, 52, 61, 76, 85)}
        print(f"Время ({rules.name}, NPC) при {args.kills_per_hour:.0f} убийствах в час с сосами и ×{args.no_shot_speed:g} без них (допущение): " + ", ".join(f"до {k} — {v:,.0f} ч" for k, v in hours.items()))

    # Сверка с замерами на 14–22: прирост адены в кармане в час (старые правила) и подобранная адена в час (новые).
    a, b = REAL_BAND
    hours = lambda s: sum(s.stats[l]["minutes"] for l in range(a, b + 1)) / 60
    old_net = statistics.median((s.cash_at.get(b, 0) - s.cash_at.get(a - 1, 0)) / max(hours(s), 1e-9) for s in results[(False, False)])
    new_adena = statistics.median(sum(s.stats[l]["adena"] for l in range(a, b + 1)) / max(hours(s), 1e-9) for s in results[(True, False)])
    print(f"Сверка с замерами 2026-10-18 на {a}–{b} ур.: старые правила — в кармане {old_net:+,.0f}/ч (замер ~{REAL_OLD_NET:+,}/ч),"
          f" новые — подбирает адены {new_adena:,.0f}/ч (замер ~{REAL_NEW_ADENA:,}/ч): модель выше замера в {new_adena / REAL_NEW_ADENA:.1f} раза по адене")


if __name__ == "__main__":
    main()
