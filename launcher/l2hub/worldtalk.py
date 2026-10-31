"""Что слышно на сервере: мозги знают то, что правда случилось в мире (docs/MIND.md, «Слухи мира»).

Источник — файлы, которые пишет сам сервер в game/log: летопись, охотники, мир, рынок. Только чтение.
Каждый ник знает свою часть новостей: выбор стабилен и зависит от ника, поэтому двое рассказывают разное,
а всезнаек нет. Модель получает строки для промпта; ни сервер, ни окно отсюда ничего не узнают.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

NEWS_ROWS = 4       # новостей в промпте разговора
NEWS_DAYS = 14      # новость старше забыта
NEWS_HEARD = 45     # % новостей дошли до этого ника; про себя и свой клан он знает всегда
NEWS_OWN = 5        # прибавка к весу новости про себя и свой клан
# Что люди обсуждают и насколько это громко. Вида нет в списке — о нём не говорят (в том числе служебные записи вроде итога простоя).
NEWS_KINDS = {"siege_taken": 9, "siege_end": 8, "siege_start": 8, "epic_raid": 9, "clan_war": 8, "enchant": 7, "raid_killed": 7, "clan": 7, "pk": 7, "quarrel": 7,
              "siege_register": 6, "left": 6, "pvp_result": 5, "gone": 5, "back": 5, "scam": 5, "sevensigns": 4, "drop_fight": 3, "elpies": 3, "level": 1, "profession": 1, "super_killed": 1}
NEWS_QUIET = 2      # вес меньше — обыденность: в промпте не больше одной такой строки
TOP_PEOPLE = 3
TOP_CLANS = 2
CIRCLE_ROWS = 3     # знакомых в промпте
ABOUT_NAMES = 3     # имён в одном слухе о собеседнике
RAID_RANGE = 8      # уровней вокруг своего у живых РБ
RAID_ROWS = 3
PRICE_ROWS = 3
BLOCK_MAX = 800     # символов в блоке без вступления
SENTENCE_MAX = 120  # символов в пересказе чужой заметки

# Слово «охотник» — внутреннее название проекта, люди так друг друга не зовут. Hunters Village — название места.
HUNTER_WORD = re.compile(r"\b[Оо]хотник(?:а|ов|и|ам|ами|ах|у|ом|е)?\b\s*|\bhunters?\s+|\bHunters?\s+(?!Village)")
ASK_RAID = re.compile(r"(?i)(?<![а-яёa-z])(рб|rb|рейд|raid|босс|boss)")
ASK_TOP = re.compile(r"(?i)(?<![а-яёa-z])(топ|top)|прокач|сильн|главн")
ASK_EVENT = re.compile(r"(?i)ивент|эвент|event|твт|tvt|ctf|deathmatch|эльп|elpi|сезон")
ASK_PRICE = re.compile(r"(?i)цен[аеуыо]|поч[её]м|стоит|дорог|деш[её]в|price|wts|wtb|купл|прода")
LATIN_WORD = re.compile(r"[a-z]{3,}")
HELP_REASONS = ("healed", "revived", "saved", "gifted", "clan_help", "buffed", "sold")


def stable(text: str) -> int:
    """0–99, от запуска к запуску одно и то же (hash() в Python каждый раз другой)."""
    return hashlib.md5(text.encode("utf-8")).digest()[0] * 100 // 256


def clean(text: str) -> str:
    """Текст записи летописи — как его сказал бы игрок."""
    return re.sub(r"\s{2,}", " ", HUNTER_WORD.sub("", text)).strip()


def when(seconds: float) -> str:
    """Давность новости словами: точнее людям и не надо."""
    hours = seconds / 3600
    if hours < 1:
        return "только что"
    if hours < 24:
        return "сегодня" if hours > 5 else "пару часов назад"
    return "вчера" if hours < 48 else f"{int(hours // 24)} дн. назад"


def at(ms: float) -> str:
    """Время будущего события: сегодня — часы, позже — ещё и дата."""
    moment = datetime.fromtimestamp(ms / 1000)
    return f"{moment:%H:%M}" if moment.date() == datetime.now().date() else f"{moment:%d.%m %H:%M}"


class WorldTalk:
    def __init__(self, log_dir: Path, reasons: dict[str, str]):
        self.log_dir = log_dir
        self.reasons = reasons
        self.cache: dict[str, tuple[float, dict]] = {}

    def _load(self, name: str) -> dict:
        """JSON сервера: перечитывается, только когда файл сменился. Файла нет или он пишется прямо сейчас — прошлая копия или пусто."""
        path = self.log_dir / name
        try:
            mtime = path.stat().st_mtime
            if name not in self.cache or self.cache[name][0] != mtime:
                self.cache[name] = (mtime, json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            pass
        return self.cache[name][1] if name in self.cache else {}

    def _hunters(self) -> list[dict]:
        """Охотники без скрытых: у тех в строке только имя, уровень и метка, и о них никто не говорит."""
        return [x for x in self._load("L2HubHunters.json").get("hunters") or [] if not x.get("secret") and x.get("name")]

    def _hidden(self) -> set[str]:
        return {x.get("name") for x in self._load("L2HubHunters.json").get("hunters") or [] if x.get("secret")}

    def _row(self, fpc: str) -> dict:
        return next((x for x in self._hunters() if x["name"] == fpc), {})

    def news(self, fpc: str, clan: str = "", limit: int = NEWS_ROWS) -> list[str]:
        """Что дошло до этого ника: громкое и свежее выше, обыденности — не больше одной строки."""
        now = time.time()
        hidden = self._hidden()
        rows = []
        for e in self._load("L2HubChronicle.json").get("entries") or []:
            weight = NEWS_KINDS.get(e.get("kind"))
            age = now - float(e.get("time") or 0) / 1000
            if not weight or age > NEWS_DAYS * 86400:
                continue
            text = clean(str(e.get("text") or ""))
            if not text or any(name in text for name in hidden):
                continue
            own = fpc in text or bool(clan and clan in text)
            if not own and stable(f"{fpc}:{e.get('time')}") >= NEWS_HEARD:
                continue
            score = (weight + (NEWS_OWN if own else 0)) / (1 + age / 86400)
            rows.append((score, weight, f"{text} ({when(max(age, 0))})"))
        rows.sort(key=lambda r: -r[0])
        out, quiet = [], False
        for _, weight, text in rows:
            if weight < NEWS_QUIET:
                if quiet:
                    continue
                quiet = True
            out.append(text)
            if len(out) == limit:
                break
        return out

    def top(self) -> str:
        """Кого знают все: самые прокачанные и самые большие кланы, вместе с живыми игроками."""
        people = [x for x in self._hunters() if x.get("level")]
        people += [p for p in self._load("L2HubHunters.json").get("players") or [] if p.get("name") and p.get("level")]
        if not people:
            return ""
        people.sort(key=lambda x: -int(x["level"]))
        best = ", ".join(f"{x['name']} ({x['level']}" + (f", клан {x['clan']}" if x.get("clan") else "") + ")" for x in people[:TOP_PEOPLE])
        clans = Counter(x["clan"] for x in people if x.get("clan")).most_common(TOP_CLANS)
        return f"Самые прокачанные: {best}." + (" Самые большие кланы: " + ", ".join(f"{c} ({n} чел.)" for c, n in clans) + "." if clans else "")

    def close(self, fpc: str) -> list[str]:
        """С кем ник делится новостями: друзья и соклановцы."""
        row = self._row(fpc)
        names = [r["name"] for r in row.get("relations") or [] if r.get("kind") == "friend" and r.get("name")]
        if row.get("clan"):
            names += [x["name"] for x in self._hunters() if x.get("clan") == row["clan"] and x["name"] != fpc]
        return list(dict.fromkeys(names))

    def circle(self, fpc: str, skip: str = "") -> str:
        """Друзья и враги ника среди людей мира: о них он говорит охотнее всего."""
        hidden = self._hidden()
        rows = [r for r in self._row(fpc).get("relations") or []
                if r.get("kind") in ("friend", "enemy") and r.get("name") and r["name"] != skip and r["name"] not in hidden]
        rows.sort(key=lambda r: -(float(r.get("grudge") or 0) + float(r.get("gratitude") or 0)))
        parts = []
        for r in rows[:CIRCLE_ROWS]:
            what = [self.reasons[x] for x in dict.fromkeys(r.get("reasons") or []) if x in self.reasons][:2]
            parts.append(f"{r['name']} — {'друг' if r['kind'] == 'friend' else 'враг'}" + (f" ({', '.join(what)})" if what else "") + (", сейчас в игре" if r.get("online") else ""))
        return "Твои знакомые: " + "; ".join(parts) + "." if parts else ""

    def about(self, fpc: str, player: str) -> str:
        """Репутация собеседника: что он делал другим. Это правда из отношений сервера, а не выдумка модели."""
        if not player:
            return ""
        killed, stole, helped = [], [], []
        for x in self._hunters():
            if x["name"] == fpc:
                continue
            for r in x.get("relations") or []:
                if r.get("name") != player:
                    continue
                reasons = r.get("reasons") or []
                if "killed" in reasons:
                    killed.append(x["name"])
                elif "contested" in reasons:
                    stole.append(x["name"])
                elif any(h in reasons for h in HELP_REASONS):
                    helped.append(x["name"])
        parts = [f"{word} {', '.join(names[:ABOUT_NAMES])}" for word, names in
                 (("убивал", killed), ("отжимал мобов у", stole), ("помогал", helped)) if names]
        return f"Про {player} слышно: {'; '.join(parts)}." if parts else ""

    def asked(self, message: str, level: int) -> str:
        """Ответ на вопрос о мире берётся из настоящих данных, поэтому блок появляется только когда о нём спросили."""
        out = []
        world = self._load("L2HubWorld.json")
        if ASK_RAID.search(message) and level:
            alive = [b for b in world.get("raidBosses") or [] if b.get("alive") and not b.get("grand") and abs(int(b.get("level") or 0) - level) <= RAID_RANGE]
            alive.sort(key=lambda b: abs(int(b.get("level") or 0) - level))
            if alive:
                out.append("Живые РБ около твоего уровня: " + ", ".join(f"{b['name']} ({b['level']}, {b.get('town', '?')})" for b in alive[:RAID_ROWS]) + ".")
        if ASK_EVENT.search(message):
            soon = []
            schedule = world.get("schedule") or {}
            now = time.time() * 1000
            if (schedule.get("pvp") or {}).get("enabled"):
                soon += [f"{e.get('title')} в {at(e['next'])}" for e in schedule["pvp"].get("events") or [] if (e.get("next") or 0) > now]
            elpies = schedule.get("elpies") or {}
            if elpies.get("enabled") and (elpies.get("next") or 0) > now:
                soon.append(f"нашествие эльпи в {at(elpies['next'])}")
            soon += [f"идёт сезонное {re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', str(s.get('title') or s.get('id')))}" for s in world.get("seasonal") or [] if s.get("active")]
            if soon:
                out.append("По расписанию: " + ", ".join(soon) + ".")
        if ASK_PRICE.search(message):
            items = self._load("L2HubMarket.json").get("items") or []
            words = LATIN_WORD.findall(message.lower())
            named = [i for i in items if any(w in str(i.get("name")).lower() for w in words)]
            rows = named or sorted(items, key=lambda i: -abs(int(i.get("percent") or 0)))
            if rows:
                out.append("Цены по последним сделкам: " + ", ".join(f"{i.get('name')} около {int(i.get('average') or 0)} адены ({int(i.get('percent') or 0):+d} % к обычной)"
                                                                      for i in rows[:PRICE_ROWS]) + ".")
        return " ".join(out)

    def text(self, fpc: str, clan: str = "", player: str = "", message: str = "", level: int = 0, heard: str = "") -> str:
        """Блок для промпта. Пусто — сервер ещё ничего не написал, и модель об этом не узнает."""
        news = self.news(fpc, clan)
        # Сначала то, о чём спросили и что касается собеседника: не влезло — теряется общее, а не ответ.
        asked_top = bool(ASK_TOP.search(message))
        parts = [
            self.top() if asked_top else "",
            self.asked(message, level),
            self.about(fpc, player),
            heard,
            "Новости: " + "; ".join(news) + "." if news else "",
            self.circle(fpc, player),
            "" if asked_top else self.top(),
        ]
        body, room = [], BLOCK_MAX
        for part in parts:
            if part and len(part) < room:
                body.append(part)
                room -= len(part) + 1
        if not body:
            return ""
        return ("Что слышно на сервере. Это правда было, а чего здесь нет — того ты не знаешь и не выдумываешь. "
                "Говори об этом только к слову или если спросят, своими словами и не списком: " + " ".join(body))

    @staticmethod
    def retold(text: str) -> str:
        """Чужая заметка в пересказе: первая фраза, без хвоста."""
        first = re.split(r"(?<=[.!?])\s", text.strip(), maxsplit=1)[0]
        return first[:SENTENCE_MAX]
