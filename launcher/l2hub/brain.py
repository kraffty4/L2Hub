"""Мозги фейковых игроков: HTTP-сервис рядом с сервером Mobius.

Сервер (FakePlayerBrain.java, патч 0005) шлёт POST /chat с обстановкой. Сервис собирает промпт из характера
фейка (game/data/L2HubFakePersonas.json сборки), памяти разговора с этим игроком (data/brain/<id>.sqlite)
и зовёт языковую модель с OpenAI-совместимым API (по умолчанию DeepSeek). Ответ — text/plain: реплика
или пустая строка (фейк промолчал). Ошибка (нет ключа, модель не ответила) — HTTP 503/502: сервер тогда
отвечает заготовками.

Ключ API — data/brain/deepseek.key (одна строка) или переменная DEEPSEEK_API_KEY. В git не попадает.
Настройки — profiles/<id>.json, раздел brain: port, model, baseUrl, maxTokens, alphabet (умолчания); поверх — выбор
из лаунчера в data/brain/settings.json: облако (api, ключ) или своя модель (local: LM Studio, Ollama), адрес, модель,
длина ответа, цена облака. Файл перечитывается на ходу. Расход токенов — data/brain/usage.json.

Напарники-фантомы (патч 0010) шлют "kind": "phantom" с ролью, состоянием и пати; каналы whisper, party и event
(реплика на событие в игре). Ответ может кончаться тегом приказа [[FOLLOW]] и т. п. — сервер выполнит его, если говорил
владелец напарника. События хранятся в памяти разговора с ролью event.

Мозги-стратег охотников (патч 0019) шлют "kind": "strategy" с состоянием охотника и списком доступных вариантов; ответ —
«[[ТЕГ]] мысль» или «[[AFK 15]] мысль». Тега из списка нет — HTTP 502, сервер тогда живёт по правилам.

Запуск:   python -m l2hub.brain <id>
Проверка: python -m l2hub.brain <id> --test <фейк> "<что сказать>" [--echo]
          python -m l2hub.brain <id> --test <напарник> "<что сказать>" --phantom [--event] [--order "<что сделано>"]
          python -m l2hub.brain <id> --test <охотник> - --strategy [--echo]
  --echo — без модели: ответ «эхо: …», чтобы проверить связку с сервером, не тратя запросы.
"""

import argparse
import json
import os
import random
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import catalog, paths

BRAIN_DIR = paths.DATA / "brain"
KEY_FILE = BRAIN_DIR / "deepseek.key"
DEFAULTS = {"port": 8771, "model": "deepseek-flash", "baseUrl": "https://api.deepseek.com", "maxTokens": 120, "alphabet": "auto"}
# Выбор модели из лаунчера (вне git): api — облако с ключом (DeepSeek и любое OpenAI-совместимое), local — своя модель
# на этом ПК (LM Studio, Ollama: тоже OpenAI-совместимый API, без ключа). Сервис перечитывает файл на ходу.
SETTINGS_FILE = BRAIN_DIR / "settings.json"
USAGE_FILE = BRAIN_DIR / "usage.json"
MODES = ("api", "local")
LOCAL_DEFAULT = {"baseUrl": "http://127.0.0.1:1234/v1", "model": ""}
KEY_NAME_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,64}\.key$")
_usage_lock = threading.Lock()


def load_settings(profile_brain: dict) -> dict:
    """Настройки мозгов: data/brain/settings.json поверх раздела brain профиля (оттуда — модель и адрес API по умолчанию)."""
    base = dict(DEFAULTS, **profile_brain)
    s = {"mode": "api", "api": {"baseUrl": base["baseUrl"], "model": base["model"], "keyFile": KEY_FILE.name},
         "local": dict(LOCAL_DEFAULT), "maxTokens": int(base["maxTokens"]), "priceIn": 0.0, "priceOut": 0.0}
    try:
        saved = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        saved = {}
    for k in ("mode", "maxTokens", "priceIn", "priceOut"):
        if k in saved:
            s[k] = saved[k]
    for k in MODES:
        s[k].update({kk: v for kk, v in (saved.get(k) or {}).items() if kk in s[k]})
    if s["mode"] not in MODES:
        s["mode"] = "api"
    if not KEY_NAME_RE.match(str(s["api"]["keyFile"])):
        s["api"]["keyFile"] = KEY_FILE.name
    return s


def read_key(settings: dict) -> str:
    """Ключ API: переменная DEEPSEEK_API_KEY или файл ключа в data/brain (кладёт автор, в git не попадает)."""
    if os.environ.get("DEEPSEEK_API_KEY"):
        return os.environ["DEEPSEEK_API_KEY"].strip()
    path = BRAIN_DIR / settings["api"]["keyFile"]
    return path.read_text(encoding="utf-8").strip() if path.exists() else ""


def list_models(base_url: str, key: str = "", timeout: float = 6) -> list[str]:
    """Модели сервера (GET /models): у LM Studio — загруженные, у Ollama — скачанные, у DeepSeek — доступные по ключу."""
    req = urllib.request.Request(base_url.rstrip("/") + "/models", headers={"Authorization": f"Bearer {key}"} if key else {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read().decode("utf-8"))
    return sorted(m["id"] for m in data.get("data", []) if m.get("id"))


def record_usage(mode: str, model: str, usage: dict) -> None:
    """Расход токенов по дням и моделям — data/brain/usage.json (для счётчика в лаунчере)."""
    day = datetime.now().strftime("%Y-%m-%d")
    with _usage_lock:
        try:
            data = json.loads(USAGE_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {"days": {}}
        row = data.setdefault("days", {}).setdefault(day, {}).setdefault(f"{mode}:{model}", {"requests": 0, "in": 0, "out": 0})
        row["requests"] += 1
        row["in"] += int(usage.get("prompt_tokens") or 0)
        row["out"] += int(usage.get("completion_tokens") or 0)
        tmp = USAGE_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(USAGE_FILE)


def usage_summary(settings: dict) -> dict:
    """Сегодня, за 7 дней и за всё время: запросы, токены, примерная цена облака по ценам из настроек ($ за 1 млн токенов)."""
    try:
        days = json.loads(USAGE_FILE.read_text(encoding="utf-8")).get("days", {})
    except (OSError, ValueError):
        days = {}
    today = datetime.now().strftime("%Y-%m-%d")
    week = sorted(days)[-7:]

    def total(keys):
        out = {"requests": 0, "in": 0, "out": 0, "cost": 0.0}
        for d in keys:
            for name, row in days.get(d, {}).items():
                for k in ("requests", "in", "out"):
                    out[k] += row.get(k, 0)
                if name.startswith("api:"):
                    out["cost"] += (row.get("in", 0) * float(settings.get("priceIn") or 0) + row.get("out", 0) * float(settings.get("priceOut") or 0)) / 1e6
        out["cost"] = round(out["cost"], 4)
        return out

    return {"today": total([today]), "week": total(week), "all": total(days)}
# Алфавит ответов (profiles/<id>.json, brain.alphabet): клиенты без русского чата не дают писать кириллицей,
# но могут её показывать. translit — для клиентов, которые и показать не могут: кириллица в ответе транслитерируется.
ALPHABET_RULES = {
    "auto": "Если пишут транслитом (русский латиницей) — отвечай тоже транслитом. ",
    "cyrillic": "Тебе часто пишут транслитом (русский латиницей), потому что у собеседника не набирается кириллица; "
                "ты его отлично понимаешь, а отвечаешь всегда по-русски кириллицей. ",
    "translit": "У собеседника игра не показывает русские буквы: отвечай по-русски, но только латиницей (транслитом), без кириллицы. ",
}
TRANSLIT = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
                    ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f",
                     "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"]))


def translit(text: str) -> str:
    out = []
    for ch in text:
        low = ch.lower()
        if low in TRANSLIT:
            t = TRANSLIT[low]
            out.append(t.capitalize() if ch != low and t else t)
        else:
            out.append(ch)
    return "".join(out)
HISTORY = 12            # реплик разговора с игроком в промпте
# Сжатие памяти (приём L2Solo, docs/PHANTOMS.md, раздел 4.10): реплики пары старше последних HISTORY модель сворачивает
# в заметку «что я помню о нём» — в промпт идут заметка и последние HISTORY реплик. Строки в lines не удаляются.
SUMMARY_EVERY = 12      # столько старых реплик сверх HISTORY накопилось — обновить заметку
SUMMARY_MAX = 600       # символов в заметке
SUMMARY_TOKENS = 400    # ответ модели на заметку
SUMMARY_RETRY = 600     # с: заметка не вышла (ошибка, лимит) — эту пару столько не трогать
MAX_REPLY = 160         # символов в реплике
RATE_PER_MINUTE = 40    # запросов к модели в минуту на весь сервер
LLM_TIMEOUT = 15
LOCAL_TIMEOUT = 18      # своя модель на ПК бывает медленнее; сервер ждёт мозги 20 с (FakePlayerBrainTimeout)

# Напарники-фантомы (патч 0010): запрос с "kind": "phantom". Сервер сам выполняет понятные приказы; в остальном
# модель может дописать тег приказа — сервер выполнит его, только если говорил владелец напарника.
TAG_RE = re.compile(r"\[\[([A-Z]+)\]\]")
TAG_HELP = {"FOLLOW": "идти за ним следом", "STAY": "стоять на месте и ждать", "ASSIST": "бить его цель",
            "BUFF": "перебафать пати", "HEAL": "подлечить его", "RES": "поднять павшего", "SIT": "сесть отдохнуть",
            "STAND": "встать", "LEAVE": "уйти из пати совсем — когда он прощается или отпускает тебя («пока», «на сегодня всё», «свободен»)",
            "HUNT": "качать его тут: самому бить и добивать всех мобов вокруг него, держась рядом, — и когда он сидит («качаемся тут», «фармим», «бей всех», «качай меня», «сижу, качай»)",
            "LEAD": "вести кач самому: качаться как на своём споте, а он идёт за тобой («веди», «паровозиком», «я за тобой»)"}
ROLE_TAGS = {"HEALER": ["HEAL", "RES"], "BUFFER": ["BUFF", "HEAL", "RES"], "TANK": ["ASSIST", "HUNT", "LEAD"], "DAMAGE": ["ASSIST", "HUNT", "LEAD"]}
ROLE_JOB = {"HEALER": "хилер: лечишь и поднимаешь пати, в драку не лезешь",
            "BUFFER": "баффер: держишь бафы на всей пати, в драку не лезешь; немного умеешь хилить и ресать",
            "TANK": "танк: держишь мобов на себе", "DAMAGE": "дамагер: бьёшь цель напарника"}
CHANNEL_RU = {"whisper": "пишет тебе в личку (шёпот)", "party": "пишет в чат пати — это видят все в пати",
              "say": "обращается к тебе в общем чате рядом, это слышат все вокруг",
              "overheard": ("говорит в общем чате рядом — не тебе, тебя он не называл: ты просто стоишь неподалёку и слышишь. "
                            "Встревай, только если тебе правда есть что сказать по теме, как прохожий игрок; иначе — <молчу>")}
# Настроение охотника (патч 0026): сервер считает его из состояния — HP и MP, недавние драки и смерти, чем занят.
MOOD_RU = {"tired": "ты вымотан — мало HP или маны или сидишь отдыхаешь; отвечаешь короче и без энтузиазма",
           "guarded": "ты настороже — недавно на тебя нападали или тебя убили; с незнакомыми суховат и недоверчив",
           "focused": "ты занят делом — отвечаешь по делу, не растекаешься",
           "restless": "тебе скучно — мобов нет или сидишь без дела; не прочь поболтать",
           "sociable": "ты в настроении поболтать"}
# Отношения охотника к игроку (патч 0032): сервер присылает обиду, благодарность и последние поступки игрока.
# Поступок — (он, она).
REASON_RU = {"killed": ("убил тебя", "убила тебя"), "attacked": ("нападал на тебя", "нападала на тебя"),
             "contested": ("отжал у тебя моба", "отжала у тебя моба"),
             "apologized": ("извинился, что отжал моба, и ушёл на другой спот", "извинилась, что отжала моба, и ушла на другой спот"),
             "insulted": ("послал тебя, когда ты предъявил за моба", "послала тебя, когда ты предъявил за моба"),
             "healed": ("хилил тебя в бою, когда у тебя было мало HP", "хилила тебя в бою, когда у тебя было мало HP"),
             "revived": ("реснул тебя", "реснула тебя"),
             "saved": ("спас тебя — добил моба, который тебя добивал", "спасла тебя — добила моба, который тебя добивал"),
             "hunted": ("качался с тобой в пати", "качалась с тобой в пати"),
             "duel_win": ("победил тебя на дуэли", "победила тебя на дуэли"),
             "duel_loss": ("проиграл тебе дуэль", "проиграла тебе дуэль")}
REASON_SHORT = {"killed": "убил", "attacked": "нападал", "contested": "отжал моба", "apologized": "извинился", "insulted": "послал", "healed": "хилил", "revived": "реснул", "saved": "спас", "hunted": "качались вместе", "duel_win": "победил на дуэли", "duel_loss": "проиграл дуэль"}


def times_word(n: int) -> str:
    """«1 раз», «3 раза», «5 раз»."""
    return f"{n} раза" if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14) else f"{n} раз"


def relation_text(rel: dict | None, female: bool, grudge: int = 0) -> str:
    """Что охотник помнит о поступках игрока: враг, друг, сложно или почти забылось — с причинами и давностью.
    Без блока relation (старый сервер) — только «убивал тебя N раз»."""
    he, him, to_him = ("Она", "неё", "ей") if female else ("Он", "него", "ему")
    if not rel:
        return f"{he} убивал тебя ({grudge} раз) — ты это помнишь и держишь обиду, но остаёшься собой." if grudge else ""
    facts = "; ".join(f"{REASON_RU[r['type']][1 if female else 0]} ({ago_words(int(r.get('ago', 0)))})"
                      for r in rel.get("reasons", []) if r.get("type") in REASON_RU)
    if not facts:
        return ""
    kills = int(rel.get("kills") or 0)
    if rel.get("enemy"):
        return (f"Твоё отношение: враг. Ты помнишь, что {he.lower()}: {facts}." + (f" Всего {'убивала' if female else 'убивал'} тебя {times_word(kills)}." if kills > 1 else "")
                + f" Ты держишь на {him} обиду — это слышно в тоне: сухо, с подколом или с претензией, без дружелюбия, но ты остаёшься собой.")
    if rel.get("friend"):
        return (f"Твоё отношение: друг. Ты помнишь, что {he.lower()}: {facts}. Ты {to_him} благодарен — это слышно в тоне: тепло, "
                "можешь припомнить добро по-свойски.")
    if float(rel.get("grudge") or 0) >= 1 and float(rel.get("gratitude") or 0) >= 1:
        return f"Твоё отношение: сложное. Ты помнишь, что {he.lower()}: {facts}. Было и плохое, и хорошее — ты насторожен, но не враждуешь."
    return f"Было, что {he.lower()}: {facts} — давно или по мелочи, почти забылось."


# Мозги-стратег охотников (патч 0019): запрос с "kind": "strategy". Сервер сам решает, какие варианты доступны (есть ли
# куда сменить спот, что продать), и выполняет только их; модель выбирает один и говорит мысль от лица охотника.
STRATEGY_PLAYER = "#strategy"   # «собеседник» решений стратега в памяти: в игровых именах # не бывает
STRATEGY_HISTORY = 3            # прошлых решений в промпте
STRATEGY_RATE_PER_MINUTE = 20   # запросов стратега в минуту на весь сервер: остальное из RATE_PER_MINUTE — разговорам
STRATEGY_RE = re.compile(r"\[\[\s*([A-Z_]+)(?:[\s:]+(\d{1,3}))?\s*\]\]")
STRATEGY_HELP = {"HUNT": "качаться дальше тут", "SPOT_UP": "сменить спот на мобов посильнее",
                 "SPOT_DOWN": "сменить спот на мобов послабее",
                 "TOWN": "сходить в город: сдать хлам, купить сосы, банки и шмот, ребафнуться — и обратно",
                 "STORE": "сходить в город и сесть в лавку в Гиране — продать ценный дроп",
                 "PARTY": "поискать пати: крикнуть «лфп» и качаться дальше, пока не позовут",
                 "AFK": "отдохнуть в городе N минут: посидеть афк",
                 "RAID": "собрать рейд охотников на рейд-босса своего уровня и повести его — долго и опасно, зато лут и слава"}


def parse_strategy(raw: str, fpc: str, options: list[str], afk: list[int]) -> tuple[str, int, str]:
    """Тег из доступных и мысль из ответа модели: «[[SPOT_UP]] мысль», «[[AFK 15]] мысль» (и без скобок: «HUNT мысль»).
    Тега из списка нет — ValueError: это мусор."""
    text = " ".join(raw.split())
    m = next((m for m in STRATEGY_RE.finditer(text) if m.group(1) in options), None)
    if m is None:
        m = re.match(r"^\W*([A-Z_]{3,9})\b(?:[\s:]+(\d{1,3}))?", text)
        if m is None or m.group(1) not in options:
            raise ValueError(f"в ответе нет тега из {', '.join(options)}: {text[:80]!r}")
    tag = m.group(1)
    minutes = max(afk[0], min(afk[1], int(m.group(2) or 15))) if tag == "AFK" else 0
    thought = Brain.clean(STRATEGY_RE.sub(" ", text[:m.start()] + " " + text[m.end():]), fpc).lstrip("|-—–:. ").strip()
    return tag, minutes, thought[:100]


def ago_words(seconds: int) -> str:
    """Давность словами для промпта: «только что», «20 минут назад», «5 часов назад», «вчера», «4 дня назад»."""
    minutes, hours, days = seconds // 60, seconds // 3600, seconds // 86400
    if minutes < 2:
        return "только что"
    if hours < 1:
        return f"{minutes} мин назад"
    if days < 1:
        return f"{hours} ч назад"
    return "вчера" if days == 1 else f"{days} дн. назад"


def phantom_tags(req: dict) -> list[str]:
    """Теги, которые модели можно ставить: только владельцу напарника, не в ответ на событие, не на подслушанное и не когда заговаривает сам."""
    if req.get("channel") in ("event", "overheard", "initiative", "banter", "retort_sorry", "retort_rude") or req.get("order") or not req.get("player", {}).get("owner"):
        return []
    return ["FOLLOW", "STAY", *ROLE_TAGS.get(req.get("phantom", {}).get("role", ""), []), "SIT", "STAND", "LEAVE"]


def _readable(enum_name: str) -> str:
    return enum_name.replace("_", " ").title() if enum_name else ""


class Brain:
    def __init__(self, chronicle_id: str, echo: bool = False):
        p = catalog.profile(chronicle_id)
        self.id = chronicle_id
        # Название хроники для промптов — из каталога (профиль interlude — «Interlude», ct26_highfive — «High Five»).
        self.chronicle_name = next((c["name"] for c in catalog.load_catalog() if c["id"] == chronicle_id), chronicle_id)
        self.profile_brain = p.get("brain", {})
        self.settings_mtime = None
        self.refresh()
        self.echo = echo
        # Характеры фейков (патч 0006) и напарников-фантомов (патч 0007); оба пишет tools/fake_players.py.
        data = catalog.server_dir(p) / "game" / "data"
        self.personas_files = [data / "L2HubFakePersonas.json", data / "L2HubPhantomPersonas.json"]
        self.personas = self._load_personas()
        BRAIN_DIR.mkdir(parents=True, exist_ok=True)
        self.db_path = BRAIN_DIR / f"{chronicle_id}.sqlite"
        self.log_path = BRAIN_DIR / f"{chronicle_id}.log"
        with self._db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS lines (id INTEGER PRIMARY KEY, fpc TEXT, player TEXT, role TEXT, channel TEXT, text TEXT, ts REAL)")
            db.execute("CREATE INDEX IF NOT EXISTS lines_pair ON lines (fpc, player, ts)")
            db.execute("CREATE TABLE IF NOT EXISTS summaries (fpc TEXT, player TEXT, text TEXT, through_id INTEGER, ts REAL, PRIMARY KEY (fpc, player))")
        self.locks: dict[tuple[str, str], threading.Lock] = {}
        self.locks_guard = threading.Lock()
        self.summarizing: set[tuple[str, str]] = set()
        self.summary_retry: dict[tuple[str, str], float] = {}
        self.calls = deque()
        self.strategy_calls = deque()
        self.test_tags: dict[str, int] = {}
        self.stats = {"requests": 0, "replies": 0, "silent": 0, "errors": 0, "lastError": "", "lastReplyAt": "", "decisions": 0, "summaries": 0}

    # ---------- данные ----------

    def _load_personas(self) -> dict:
        personas = {}
        for f in self.personas_files:
            if f.exists():
                personas.update(json.loads(f.read_text(encoding="utf-8")).get("personas", {}))
        return personas

    def _db(self) -> sqlite3.Connection:
        return sqlite3.connect(self.db_path, timeout=10)

    def refresh(self) -> None:
        """Перечитать настройки из лаунчера, если файл изменился: модель меняется без перезапуска сервиса."""
        try:
            mtime = SETTINGS_FILE.stat().st_mtime
        except OSError:
            mtime = 0
        if mtime == self.settings_mtime:
            return
        self.settings_mtime = mtime
        self.settings = load_settings(self.profile_brain)
        active = self.settings[self.settings["mode"]]
        self.cfg = dict(DEFAULTS, **self.profile_brain)
        self.cfg.update(mode=self.settings["mode"], model=active["model"], baseUrl=active["baseUrl"], maxTokens=int(self.settings["maxTokens"]))

    def key(self) -> str:
        return read_key(self.settings)

    def ready(self) -> bool:
        """Можно звать модель: у облака есть ключ, локальной ключ не нужен."""
        return self.echo or self.cfg["mode"] == "local" or bool(self.key())

    def health(self) -> dict:
        self.refresh()
        return {"ok": True, "mode": self.cfg["mode"], "model": self.cfg["model"] or "(загруженная)", "baseUrl": self.cfg["baseUrl"], "key": self.ready(),
                "echo": self.echo, "personas": len(self.personas), "usage": usage_summary(self.settings)["today"], **self.stats}

    def _history(self, fpc: str, player: str) -> list[tuple[str, str]]:
        with self._db() as db:
            rows = db.execute("SELECT role, text FROM lines WHERE fpc = ? AND player = ? ORDER BY ts DESC LIMIT ?", (fpc, player, HISTORY)).fetchall()
        return list(reversed(rows))

    def _remember(self, fpc: str, player: str, role: str, channel: str, text: str) -> None:
        with self._db() as db:
            db.execute("INSERT INTO lines (fpc, player, role, channel, text, ts) VALUES (?, ?, ?, ?, ?, ?)", (fpc, player, role, channel, text, time.time()))

    def _met_before(self, fpc: str, player: str) -> bool:
        with self._db() as db:
            return db.execute("SELECT 1 FROM lines WHERE fpc = ? AND player = ? LIMIT 1", (fpc, player)).fetchone() is not None

    def acquaintance(self, fpc: str, player: str) -> dict:
        """Знакомы ли (игрок сам что-то писал этому фантому) и сколько секунд назад была последняя строка пары; без модели.
        Строк одного фантома (события, ПвП, приветствие без ответа) для знакомства мало."""
        with self._db() as db:
            last, said = db.execute("SELECT MAX(ts), COALESCE(SUM(role = 'player'), 0) FROM lines WHERE fpc = ? AND player = ?", (fpc, player)).fetchone()
        return {"met": bool(said), "ago": int(time.time() - last) if last else -1}

    def _log(self, line: str) -> None:
        with self.log_path.open("a", encoding="utf-8") as f:
            f.write(f"{datetime.now():%Y-%m-%d %H:%M:%S} {line}\n")

    # ---------- заметка о давних разговорах ----------

    def _summary(self, fpc: str, player: str) -> tuple[str, int]:
        """Заметка пары и id последней свёрнутой в неё строки; нет заметки — ("", 0)."""
        with self._db() as db:
            row = db.execute("SELECT text, through_id FROM summaries WHERE fpc = ? AND player = ?", (fpc, player)).fetchone()
        return (row[0], row[1]) if row else ("", 0)

    def _stale_lines(self, fpc: str, player: str, through_id: int) -> list[tuple]:
        """Реплики пары старше последних HISTORY, которых ещё нет в заметке: (id, role, text, ts)."""
        with self._db() as db:
            edge = db.execute("SELECT id FROM lines WHERE fpc = ? AND player = ? ORDER BY id DESC LIMIT 1 OFFSET ?", (fpc, player, HISTORY - 1)).fetchone()
            if edge is None:
                return []
            return db.execute("SELECT id, role, text, ts FROM lines WHERE fpc = ? AND player = ? AND id > ? AND id < ? ORDER BY id",
                              (fpc, player, through_id, edge[0])).fetchall()

    def summary_prompt(self, fpc: str, player: str, old: str, stale: list[tuple]) -> str:
        p = self.personas.get(fpc, {})
        said = {"player": player, "fpc": fpc, "event": "в игре"}
        lines = [f"[{datetime.fromtimestamp(ts):%d.%m %H:%M}] {said.get(role, role)}: {text}" for _id, role, text, ts in stale]
        return "\n".join(x for x in [
            f"Ты ведёшь память персонажа {fpc} — игрока Lineage II на маленьком сервере для своих — о его знакомом {player}.",
            f"Характер {fpc}: {p['traits']}." if p.get("traits") else "",
            f"Прежняя заметка: {old}" if old else "Прежней заметки нет.",
            "Новые реплики их разговора по времени («в игре» — что случилось, а не чьи-то слова):",
            *lines,
            f"Перепиши заметку целиком с учётом новых реплик: что {fpc} знает и помнит о {player} — кто он, что между ними было "
            f"(драки, пати, просьбы, обещания, шутки), как {fpc} к нему относится. Только то, что есть в прежней заметке и в репликах, ничего не выдумывай. "
            f"От третьего лица, по-русски, без кавычек и без вступлений, не длиннее {SUMMARY_MAX} символов: "
            "старое и неважное ужимай, обиды, обещания и долги сохраняй.",
        ] if x)

    def maybe_summarize(self, fpc: str, player: str) -> None:
        """После реплики: старых реплик пары накопилось SUMMARY_EVERY — обновить заметку в фоне, не задерживая ответ."""
        key = (fpc, player)
        if self.echo or not player or player == STRATEGY_PLAYER:
            return
        with self.locks_guard:
            if key in self.summarizing or self.summary_retry.get(key, 0) > time.monotonic():
                return
            self.summarizing.add(key)
        threading.Thread(target=self.summarize, args=(fpc, player), daemon=True).start()

    def summarize(self, fpc: str, player: str, force: bool = False) -> str:
        """Свернуть старые реплики пары в заметку; force — даже если их меньше SUMMARY_EVERY. Возвращает новую заметку или ""."""
        key = (fpc, player)
        try:
            old, through = self._summary(fpc, player)
            stale = self._stale_lines(fpc, player, through)
            if len(stale) < (1 if force else SUMMARY_EVERY):
                return ""
            if not self._rate_ok():
                raise RuntimeError("слишком много запросов в минуту")
            started = time.monotonic()
            raw = self.call_llm([{"role": "system", "content": self.summary_prompt(fpc, player, old, stale)},
                                 {"role": "user", "content": "Новая заметка:"}], max_tokens=SUMMARY_TOKENS, temperature=0.3)
            text = " ".join(raw.split()).strip().strip('"«»“”').strip()[:SUMMARY_MAX]
            if not text:
                raise RuntimeError("модель вернула пустую заметку")
            with self._db() as db:
                db.execute("INSERT OR REPLACE INTO summaries (fpc, player, text, through_id, ts) VALUES (?, ?, ?, ?, ?)",
                           (fpc, player, text, stale[-1][0], time.time()))
            self.stats["summaries"] += 1
            self._log(f"[summary] {fpc} о {player}: +{len(stale)} реплик => {text!r} ({time.monotonic() - started:.1f} с)")
            return text
        except Exception as e:  # noqa: BLE001 — заметка необязательна: без неё в промпте просто последние реплики
            self.summary_retry[key] = time.monotonic() + SUMMARY_RETRY
            self._log(f"[summary] {fpc} о {player}: не вышло: {e}")
            return ""
        finally:
            with self.locks_guard:
                self.summarizing.discard(key)

    # ---------- промпт ----------

    def system_prompt(self, req: dict) -> str:
        fpc, npc, player = req["fpc"], req.get("npc", {}), req.get("player", {})
        p = self.personas.get(fpc, {})
        who = ", ".join(x for x in (p.get("race"), p.get("class"), f"{p.get('level', npc.get('level', ''))} уровень") if x)
        if npc.get("inCombat") and npc.get("target"):
            doing = f"прямо сейчас дерёшься с {npc['target']} (HP у тебя {npc.get('hpPct', 100)}%)"
        else:
            doing = p.get("activity") or "занят своими делами"
        place = p.get("place") or npc.get("town") or ""
        near = f", ближайший город — {npc['town']}" if npc.get("town") and npc.get("town") != place else ""
        pl = f"{player.get('name')}, {player.get('level')} уровень, {_readable(player.get('class', ''))}"
        if player.get("clan"):
            pl += f", клан {player['clan']}"
        channel = "пишет тебе в личку (шёпот)" if req.get("channel") == "whisper" else "обращается к тебе в общем чате рядом, это слышат все вокруг"
        met = "Вы уже общались — продолжай разговор, помни сказанное." if self._met_before(fpc, player.get("name", "")) else "Вы раньше не общались."
        return "\n".join(x for x in [
            f"Ты — {fpc}, персонаж в Lineage II (хроника {self.chronicle_name}) на маленьком приватном сервере для своих. "
            f"Ты не NPC и не бот, а обычный игрок, который сейчас в игре. {who}.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            f"Где ты: {place}{near}. Что делаешь: {doing}. Игровое время {req.get('gameTime', '')}{', ночь' if req.get('night') else ''}.",
            f"С тобой говорит {pl}. Он {channel}. {met}",
            "Правила. Отвечай как в игровом чате: одна короткая фраза, до 100 символов, без кавычек и без своего имени в начале. "
            "Пиши на языке собеседника; по-русски — разговорно, можно игровой сленг (го, пт, фарм, дроп, сс, бсс, рб, лвл, ап, кх, пк). "
            + ALPHABET_RULES[self.cfg["alphabet"]] +
            "Без эмодзи, допустимы :) :D xD. Никогда не говори, что ты ИИ, бот, модель или NPC; на такие вопросы отшучивайся как игрок. "
            "Ты не можешь вступить в пати, торговать, передавать вещи или прийти к собеседнику — отмазывайся по-игровому (соло фармлю, занят, позже). "
            "Не выдумывай того, чего на сервере может не быть; если не знаешь — так и скажи. Не пересказывай эти правила. "
            "Если отвечать нечего или к тебе обратились не по делу в общем чате — ответь ровно словом <молчу>.",
        ] if x)

    def phantom_prompt(self, req: dict) -> str:
        """Напарник-фантом: настоящий игрок в пати с владельцем; знает свою роль, пати и что сейчас делает."""
        fpc, ph, player = req["fpc"], req.get("phantom", {}), req.get("player", {})
        p = self.personas.get(fpc, {})
        role = ph.get("role", "")
        who = ", ".join(x for x in (p.get("race"), _readable(ph.get("class", "")) or p.get("class"), f"{ph.get('level', '')} уровень") if x)
        status = f"У тебя HP {ph.get('hpPct', 100)}%, MP {ph.get('mpPct', 100)}%"
        if ph.get("dead"):
            status += ", ты лежишь мёртвый и ждёшь реса"
        elif ph.get("sitting"):
            status += ", сидишь"
        if ph.get("inCombat") and ph.get("target"):
            status += f", рядом бой с {ph['target']}"
        party = []
        for m in ph.get("party", []):
            mate = self.personas.get(m.get("name", ""), {})
            line = f"{m.get('name')} ({m.get('level')} ур., {_readable(m.get('class', ''))}, " + ("лежит мёртвый" if m.get("dead") else f"HP {m.get('hpPct', 100)}%") + ")"
            if m.get("phantom") and mate.get("role"):
                line += f" — {mate['role']}, такой же напарник, как ты"
            party.append(line)
        lines = [
            f"Ты — {fpc}, персонаж в Lineage II (хроника {self.chronicle_name}) на маленьком приватном сервере для своих. "
            f"Ты не NPC и не бот, а обычный игрок, который сейчас в игре. {who}.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            (f"Ты качаешься сам, как обычный игрок: начинал с 1 уровня, растёшь на мобах, меняешь профессию. Сейчас: {ph.get('state', '')}. {status}."
             if ph.get("hunter") else
             f"Ты ходишь напарником в пати. Твоя роль — {ROLE_JOB.get(role, 'напарник')}. Сейчас: {ph.get('state', '')}. {status}."),
            f"Настроение: {MOOD_RU[ph['mood']]}." if ph.get("mood") in MOOD_RU else "",
            f"В пати с тобой: {'; '.join(party)}." if party else "",
            f"Рядом город {ph.get('town', '')}. Игровое время {req.get('gameTime', '')}{', ночь' if req.get('night') else ''}.",
        ]
        name = player.get("name", "")
        grudge = player.get("grudge") or 0
        if req.get("channel") == "event":
            where = "в общий чат — тебя слышат все рядом, и он тоже" if ph.get("hunter") else "в чат пати"
            lines.append(f"Сейчас случилось: {req.get('message', '')}. Скажи об этом одну короткую реплику {where}, как живой игрок "
                         f"(обращаешься к {name}); если тут нечего сказать — ответь ровно словом <молчу>.")
        elif req.get("channel") == "initiative":
            # Охотник заговаривает первым (патч 0025): правила сервера уже разрешили, модель только пишет фразу или молчит.
            he = "Она" if player.get("female") else "Он"
            known = self.acquaintance(fpc, name)
            if known["met"]:
                past = (f"Вы знакомы: {he.lower()} уже писал(а) тебе, последний раз — {ago_words(known['ago'])}. Что было раньше — в твоей заметке о "
                        f"{'ней' if player.get('female') else 'нём'} (если есть) и в репликах вашего разговора: "
                        "вспоминай только то, что там правда есть, и не выдумывай общих воспоминаний.")
            elif (player.get("relation") or {}).get("reasons"):
                past = f"Вы раньше не разговаривали, но по игре ты {'её' if player.get('female') else 'его'} помнишь."
            else:
                past = f"Вы не знакомы и раньше не разговаривали — не делай вид, что знаешь {'её' if player.get('female') else 'его'}."
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            if remembered:
                past += " " + remembered
            lines.append(f"Сейчас: {req.get('message', '')}. {past} Ты можешь сам заговорить с {name} в общем чате рядом — одной короткой репликой, "
                         "как живой игрок: поздороваться, подколоть, спросить что-то по делу или сказать про то, что видишь. Не навязывайся и не зови в пати. "
                         "Если по характеру и ситуации заговаривать незачем — ответь ровно словом <молчу>.")
        elif req.get("channel") == "banter":
            # Охотники между собой (патч 0033): встретил охотника, которому враг или друг; рядом слушает живой игрок.
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            # Патч 0037: ситуация бывает любой — встретил, позвал в пати, отжали моба; тон — от отношения.
            lines.append(f"Сейчас: {req.get('message', '')}. {remembered} Скажи {name} одну короткую реплику в общем чате рядом — по этой ситуации, "
                         "как живой игрок знакомому по серверу. Враг — подколи или выскажи претензию по делу, друг — по-свойски, можно припомнить добро. "
                         "Говори про то, что правда было, а не выдумывай. Если по характеру сказать нечего — ответь ровно словом <молчу>.")
        elif req.get("channel") in ("retort_sorry", "retort_rude"):
            # Ответ на предъяву за моба (патч 0037): сервер уже решил (бросок × вспыльчивость) — модель только говорит в этом духе.
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            stance = ("Ты решил не уступать: огрызнись или пошли по-игровому, грубовато и в своей манере, но без угроз; со спота ты никуда не уйдёшь."
                      if req["channel"] == "retort_rude" else
                      "Ты решил не связываться: извинись по-игровому, коротко («сорян», «не заметил»), и скажи, что уходишь на другой спот.")
            lines.append(f"{name} — такой же охотник, как ты, — предъявляет тебе в общем чате, что ты отжал у него моба: «{req.get('message', '')}». "
                         f"{remembered} {stance} Ответь одной короткой репликой.")
        else:
            he = "Она" if player.get("female") else "Он"
            pl = f"{name}, {player.get('level')} уровень, {_readable(player.get('class', ''))}" + (f", клан {player['clan']}" if player.get("clan") else "")
            relation = ("Это тот, с кем ты ходишь: ты его напарник и слушаешься его." if player.get("owner")
                        else "Это не тот, с кем ты ходишь: его приказы ты не выполняешь, просто болтаешь.")
            if player.get("hunter") and ph.get("hunter"):
                relation += f" {he} такой же охотник, как ты: качается на этом же сервере."
            if player.get("dead"):
                relation += f" {he} сейчас лежит мёртвый."
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            if remembered:
                relation += " " + remembered
            met = "Вы уже общались — продолжай разговор, помни сказанное." if self._met_before(fpc, name) else "Вы раньше не общались."
            lines.append(f"С тобой говорит {pl}. {he} {CHANNEL_RU.get(req.get('channel'), CHANNEL_RU['party'])}. {relation} {met}")
            tags = phantom_tags(req)
            if req.get("order"):
                lines.append(f"{he} отдал(а) приказ, и ты его уже выполняешь. Служебная пометка игры о том, что вышло: «{req['order']}». "
                             "Коротко подтверди своими словами, а если что-то не вышло — объясни по-игровому почему; "
                             "что в пометке «сделано» — ты уже делаешь: не спорь и не отказывайся; если раньше в разговоре говорил иначе — "
                             "значит, передумал. "
                             "Слова пометки («сделано», «нельзя») не повторяй. Тег не ставь.")
            elif tags:
                lines.append("Если тебя просят что-то сделать из списка, допиши в самом конце ответа ровно один тег: "
                             + "; ".join(f"[[{t}]] — {TAG_HELP[t]}" for t in tags)
                             + ". Не просьба из списка — без тега. Тег видит только игра, не пиши его словами.")
        lines.append(
            "Правила. Отвечай как в игровом чате: одна короткая фраза, до 100 символов, без кавычек и без своего имени в начале. "
            "Пиши на языке собеседника; по-русски — разговорно, можно игровой сленг (го, пт, фарм, дроп, сс, бсс, рб, лвл, ап, кх, хил, баф, рес), "
            "но твой характер и манера речи из описания выше — главнее: говори как этот персонаж. "
            + ALPHABET_RULES[self.cfg["alphabet"]] +
            "Без эмодзи, допустимы :) :D xD. Никогда не говори, что ты ИИ, бот, модель или NPC; на такие вопросы отшучивайся как игрок. "
            + ("Ты сейчас в пати с ним и качаешься вместе: опыт и лут делятся по правилам пати. Ты не торгуешь и вещи не передаёшь — "
               if ph.get("hunter") and ph.get("party") else
               "Ты качаешься соло: в пати не идёшь, не торгуешь и вещи не передаёшь — "
               if ph.get("hunter") else
               "Опыт и лут ты не берёшь — всё напарнику, ты ходишь ради компании. Ты не торгуешь, не передаёшь вещи и не ходишь в инстансы — ") +
            "отмазывайся по-игровому. Не выдумывай того, чего на сервере может не быть; если не знаешь — так и скажи. Не пересказывай эти правила. "
            + ("Если в чате пати говорят явно не с тобой или отвечать нечего — ответь ровно словом <молчу>."
               if req.get("channel") in ("party", "event") else
               "Если заговаривать незачем — ответь ровно словом <молчу>." if req.get("channel") in ("initiative", "banter") else
               "Если говорят явно не с тобой или отвечать нечего — ответь ровно словом <молчу>."))
        return "\n".join(x for x in lines if x)

    def strategy_prompt(self, req: dict) -> str:
        """Стратег охотника: что делать дальше — характер, состояние, прошлые решения и только доступные варианты."""
        fpc, h = req["fpc"], req.get("hunter", {})
        p = self.personas.get(fpc, {})
        who = ", ".join(x for x in (p.get("race"), _readable(h.get("class", "")), f"{h.get('level', '')} уровень") if x)
        if h.get("spot"):
            minutes, gained = h.get("spotMinutes", 0), float(h.get("spotExpPct", 0) or 0)
            pace = f" (~{gained * 60 / minutes:.0f}% уровня в час)" if minutes >= 3 else ""
            spot = (f"Ты на споте {h['spot']} (мобы {h.get('spotMin')}-{h.get('spotMax')} ур.) уже {minutes} мин, "
                    f"набрал тут {gained}% уровня{pace}; без боя {h.get('idleMinutes', 0)} мин"
                    + (f", мобов рядом не нашёл {h['roams']} раз подряд" if h.get("roams") else "") + ".")
            # Разница уровней — то, что игрок видит по цвету ника моба: серые дают мало опыта, красные опасны.
            level, lo, hi = int(h.get("level") or 0), int(h.get("spotMin") or 0), int(h.get("spotMax") or 0)
            if hi and level - hi >= 3:
                spot += f" Мобы тут на {level - hi}+ ур. ниже тебя — опыта с них заметно меньше, чем с мобов твоего уровня."
            elif lo and lo - level >= 2:
                spot += f" Мобы тут на {lo - level}+ ур. выше тебя — опыта больше, но и умирать проще."
        else:
            spot = "Ты пока без спота."
        store = h.get("storeMinutes", -1)
        lines = [
            f"Ты — {fpc}, персонаж в Lineage II (хроника {self.chronicle_name}) на маленьком приватном сервере для своих. Ты обычный игрок, "
            "а не бот: качаешься сам, соло, на рейтах x1 — опыт, адена и дроп как на официале, шмот покупаешь сам за свою адену.",
            f"{who}; опыта набрано {h.get('expPct', 0)}% уровня; HP {h.get('hpPct', 100)}%, MP {h.get('mpPct', 100)}%.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            spot,
            f"Смертей за последний час: {h['deathsHour']}." if h.get("deathsHour") else "За последний час ни разу не умирал.",
            f"В кармане {h.get('money', h.get('adena', 0))} адены. Шмот: {h.get('gear', '').rstrip('.')}.",
            f"Расходники: {h.get('supplies', '')}. Инвентарь заполнен на {h.get('inventoryPct', 0)}%.",
            f"Ценный дроп на продажу: {h['drop']}." if h.get("drop") else "Ценного дропа на продажу нет.",
            f"В городе был {h.get('townMinutes', 0)} мин назад, " + (f"лавку ставил {store} мин назад." if store >= 0 else "лавку ещё не ставил."),
        ]
        grudges = h.get("grudges") or []
        if grudges:
            lines.append("Тебя убивали: " + ", ".join(f"{g.get('name')} — {g.get('count')} раз" + (" (сейчас в игре)" if g.get("online") else "")
                                                   for g in grudges) + ".")
        # Отношения (патч 0032): враги и те, кто помогал, — с поступками; в игре ли сейчас.
        rels = h.get("relations") or []
        for kind, title in (("enemy", "Твои враги"), ("friend", "Тебе помогали")):
            chosen = [r for r in rels if r.get("kind") == kind]
            if chosen:
                lines.append(f"{title}: " + ", ".join(
                    f"{r.get('name')} ({', '.join(REASON_SHORT.get(x, x) for x in r.get('reasons', []))})" + (" — сейчас в игре" if r.get("online") else "")
                    for r in chosen) + ".")
        lines.append(f"Живых игроков на сервере сейчас: {req.get('realPlayers', 0)}. Игровое время {req.get('gameTime', '')}{', ночь' if req.get('night') else ''}.")
        past = self._past_decisions(fpc)
        if past:
            lines.append("Твои прошлые решения: " + "; ".join(past) + ".")
        lo, hi = (req.get("afk") or [5, 30])[:2]
        options = []
        for o in req.get("options", []):
            tag, note = o.get("tag", ""), o.get("note", "")
            if tag == "AFK":
                options.append(f"[[AFK N]] — отдохнуть в городе N минут ({lo}–{hi}): посидеть афк")
            elif tag in STRATEGY_HELP:
                options.append(f"[[{tag}]] — {STRATEGY_HELP[tag]}" + (f": {note}" if note else ""))
        lines.append("Реши, что делаешь дальше. Можно только одно из этого:\n" + "\n".join(options))
        alphabet = "по-русски латиницей (транслитом)" if self.cfg["alphabet"] == "translit" else "по-русски"
        lines.append(
            "Решай как живой игрок с твоим характером: смотри на опыт и смерти на споте, деньги, расходники, шмот и дроп. "
            "Поход в город, смена спота и афк стоят времени, телепорт с 40 уровня платный; без причины не дёргайся, но и не стой там, где плохо. "
            f"Ответ — одна строка: сначала тег из списка, потом через пробел короткая мысль от первого лица {alphabet}, в твоей манере речи, "
            "до 80 символов, как в игровом чате, без кавычек. Формат: [[ТЕГ]] мысль; для отдыха — [[AFK 15]] мысль. "
            "Никогда не говори, что ты ИИ, бот или модель.")
        return "\n".join(x for x in lines if x)

    def _past_decisions(self, fpc: str) -> list[str]:
        with self._db() as db:
            rows = db.execute("SELECT text, ts FROM lines WHERE fpc = ? AND player = ? ORDER BY ts DESC LIMIT ?",
                              (fpc, STRATEGY_PLAYER, STRATEGY_HISTORY)).fetchall()
        now = time.time()
        return [f"{max(1, int((now - ts) // 60))} мин назад — {text}" for text, ts in reversed(rows)]

    def messages(self, req: dict) -> list[dict]:
        prompt = self.phantom_prompt(req) if req.get("kind") == "phantom" else self.system_prompt(req)
        name = req.get("player", {}).get("name", "")
        summary, _ = self._summary(req["fpc"], name)
        if summary:
            prompt += f"\nТвоя заметка о давних разговорах с {name} (что ты помнишь, коротко): {summary}"
        msgs = [{"role": "system", "content": prompt}]
        for role, text in self._history(req["fpc"], name):
            if role == "event":
                msgs.append({"role": "user", "content": f"(в игре: {text})"})
            else:
                msgs.append({"role": "user" if role == "player" else "assistant", "content": text})
        if req.get("channel") in ("event", "initiative", "banter"):
            msgs.append({"role": "user", "content": f"(в игре: {req.get('message', '')})"})
        elif req.get("order"):
            # Приказ уже выполнен сервером: факт — рядом с репликой, последним. Иначе модель держится своих прошлых слов из истории
            # («паровозом не вожу») сильнее, чем пометки в системном промпте.
            msgs.append({"role": "user", "content": f"{req.get('message', '')}\n(в игре: {req['order']}; это уже происходит — "
                                                    "подтверди в своём характере, даже если раньше говорил иначе)"})
        else:
            msgs.append({"role": "user", "content": req.get("message", "")})
        return msgs

    # ---------- модель ----------

    def _rate_ok(self) -> bool:
        now = time.monotonic()
        while self.calls and now - self.calls[0] > 60:
            self.calls.popleft()
        if len(self.calls) >= RATE_PER_MINUTE:
            return False
        self.calls.append(now)
        return True

    def _strategy_rate_ok(self) -> bool:
        """Стратегу — не больше STRATEGY_RATE_PER_MINUTE из общего лимита: разговорам с игроками остаётся всегда."""
        now = time.monotonic()
        while self.strategy_calls and now - self.strategy_calls[0] > 60:
            self.strategy_calls.popleft()
        if len(self.strategy_calls) >= STRATEGY_RATE_PER_MINUTE or not self._rate_ok():
            return False
        self.strategy_calls.append(now)
        return True

    def call_llm(self, msgs: list[dict], max_tokens: int | None = None, temperature: float = 1.0) -> str:
        if self.echo:
            return "эхо: " + msgs[-1]["content"]
        self.refresh()
        local = self.cfg["mode"] == "local"
        key = "" if local else self.key()
        if not local and not key:
            raise RuntimeError(f"нет ключа API: положи его в {BRAIN_DIR / self.settings['api']['keyFile']} или DEEPSEEK_API_KEY")
        model = self.cfg["model"]
        if local and not model:
            # Модель не выбрана — первая загруженная (LM Studio отдаёт только загруженные).
            found = list_models(self.cfg["baseUrl"])
            if not found:
                raise RuntimeError(f"локальная модель не загружена: {self.cfg['baseUrl']}")
            model = found[0]
        body = {"model": model, "messages": msgs, "max_tokens": max_tokens or self.cfg["maxTokens"], "temperature": temperature}
        if "deepseek" in self.cfg["baseUrl"]:
            body["thinking"] = {"type": "disabled"}  # параметр DeepSeek: без «размышлений», быстрее и дешевле
        headers = {"Content-Type": "application/json"}
        if key:
            headers["Authorization"] = f"Bearer {key}"
        req = urllib.request.Request(self.cfg["baseUrl"].rstrip("/") + "/chat/completions", data=json.dumps(body).encode("utf-8"), headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=LOCAL_TIMEOUT if local else LLM_TIMEOUT) as r:
                data = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            raise RuntimeError(f"модель ответила HTTP {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            raise RuntimeError(f"модель недоступна ({self.cfg['baseUrl']}): {e.reason}") from e
        record_usage(self.cfg["mode"], model, data.get("usage") or {})
        return data["choices"][0]["message"]["content"] or ""

    @staticmethod
    def clean(reply: str, fpc: str) -> str:
        text = " ".join(reply.split())
        text = re.sub(rf"^\s*{re.escape(fpc)}\s*[:：-]\s*", "", text, flags=re.IGNORECASE)
        text = text.strip().strip('"«»“”').strip()
        if not text or "<молчу>" in text.lower():
            return ""
        return text[:MAX_REPLY]

    # ---------- запрос ----------

    def decide(self, req: dict) -> str:
        """Стратег охотника: «[[ТЕГ]] мысль» или «[[AFK 15]] мысль». Мусор, лимит, ошибка модели — исключение (HTTP 502):
        сервер тогда живёт по правилам. Самопроверка сервера: test=garbage — мусор без модели, test=random — случайный вариант."""
        fpc, h = req["fpc"], req.get("hunter", {})
        options = [o.get("tag") for o in req.get("options", []) if o.get("tag") in STRATEGY_HELP]
        if not options:
            raise RuntimeError(f"стратег {fpc}: нет вариантов")
        afk = list((req.get("afk") or [5, 30])[:2])
        test = req.get("test", "")
        self.stats["decisions"] += 1
        started = time.monotonic()
        if test == "garbage":
            raw = "ну хз, посмотрим"
        elif test == "random" or self.echo:
            # Проверка выполнения: каждый вариант по очереди — сначала те, что выпадали реже.
            least = min(self.test_tags.get(t, 0) for t in options)
            tag = random.choice([t for t in options if self.test_tags.get(t, 0) == least])
            self.test_tags[tag] = self.test_tags.get(tag, 0) + 1
            raw = f"[[{tag}{f' {afk[0]}' if tag == 'AFK' else ''}]] проверка: {STRATEGY_HELP[tag]}"
        else:
            if not self._strategy_rate_ok():
                raise RuntimeError(f"стратег {fpc}: слишком много запросов в минуту")
            raw = self.call_llm([{"role": "system", "content": self.strategy_prompt(req)}, {"role": "user", "content": "Что делаешь дальше?"}])
        try:
            tag, minutes, thought = parse_strategy(raw, fpc, options, afk)
        except ValueError as e:
            raise RuntimeError(f"стратег {fpc}: {e}{f' [{test}]' if test else ''}") from e
        if self.cfg["alphabet"] == "translit":
            thought = translit(thought)
        reply = f"[[{tag}{f' {minutes}' if tag == 'AFK' else ''}]] {thought}".strip()
        if not test and not self.echo:
            self._remember(fpc, STRATEGY_PLAYER, "strategy", "strategy", reply)
        self._log(f"[strategy] {fpc} ({h.get('level')} ур., {h.get('spot') or 'без спота'}, {h.get('money', h.get('adena'))}): "
                  f"{'/'.join(options)} => {reply!r}{f' [{test}]' if test else ''} ({time.monotonic() - started:.1f} с)")
        return reply

    def chat(self, req: dict) -> str:
        if req.get("kind") == "strategy":
            return self.decide(req)
        if req.get("kind") == "acquaintance":
            # Охотник решает, заговорить ли первым (патч 0025): только память, без модели, вне лимитов и статистики.
            return json.dumps(self.acquaintance(req["fpc"], req.get("player", {}).get("name", "")))
        fpc, player = req["fpc"], req.get("player", {}).get("name", "")
        self.stats["requests"] += 1
        with self.locks_guard:
            lock = self.locks.setdefault((fpc, player), threading.Lock())
        with lock:  # реплики одному игроку — по очереди, чтобы история не перепуталась
            if not self._rate_ok():
                raise RuntimeError("слишком много запросов в минуту")
            started = time.monotonic()
            msgs = self.messages(req)
            raw = self.call_llm(msgs)
            # Тег приказа напарника: первый из разрешённых, остальные теги вырезаются.
            tags = phantom_tags(req) if req.get("kind") == "phantom" else []
            tag = next((t for t in TAG_RE.findall(raw) if t in tags), "")
            reply = self.clean(TAG_RE.sub(" ", raw), fpc)
            if self.cfg["alphabet"] == "translit":
                reply = translit(reply)
            event = req.get("channel") in ("event", "initiative", "banter")
            # Подслушанное и своё молчаливое «заговорить первым» без ответа не запоминаются: иначе фантом «уже общался» бы с тем, кому не сказал ни слова.
            if reply or req.get("channel") not in ("overheard", "initiative", "banter"):
                self._remember(fpc, player, "event" if event else "player", req.get("channel", ""), req.get("message", ""))
            if reply:
                self._remember(fpc, player, "fpc", req.get("channel", ""), reply)
                self.stats["replies"] += 1
                self.stats["lastReplyAt"] = datetime.now().strftime("%H:%M:%S")
            else:
                self.stats["silent"] += 1
            order = f" приказ: {req['order']}" if req.get("order") else ""
            mood = f" [{req['phantom']['mood']}]" if req.get("phantom", {}).get("mood") else ""
            self._log(f"[{req.get('channel')}] {player} -> {fpc}{mood}: {req.get('message', '')!r}{order} => {reply!r}"
                      f"{' [[' + tag + ']]' if tag else ''} ({time.monotonic() - started:.1f} с)")
        self.maybe_summarize(fpc, player)
        return f"{reply} [[{tag}]]".strip() if tag else reply


def make_handler(brain: Brain):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # без вывода в консоль на каждый запрос
            pass

        def _send(self, code: int, body: str, ctype: str = "text/plain; charset=utf-8") -> None:
            data = body.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                self._send(200, json.dumps(brain.health(), ensure_ascii=False), "application/json; charset=utf-8")
            else:
                self._send(404, "")

        def do_POST(self):
            if self.path != "/chat":
                self._send(404, "")
                return
            try:
                req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8"))
                self._send(200, brain.chat(req))
            except Exception as e:  # noqa: BLE001 — любая ошибка: сервер ответит заготовкой
                brain.stats["errors"] += 1
                brain.stats["lastError"] = f"{datetime.now():%H:%M:%S} {e}"
                brain._log(f"ошибка: {e}")
                self._send(503 if "ключа" in str(e) else 502, str(e))

    return Handler


def main() -> int:
    ap = argparse.ArgumentParser(prog="l2hub.brain")
    ap.add_argument("chronicle")
    ap.add_argument("--test", nargs=2, metavar=("FPC", "MESSAGE"))
    ap.add_argument("--player", default="Tester")
    ap.add_argument("--echo", action="store_true")
    ap.add_argument("--phantom", action="store_true", help="FPC — напарник-фантом в пати с игроком (чат пати)")
    ap.add_argument("--event", action="store_true", help="с --phantom: MESSAGE — описание события, а не реплика игрока")
    ap.add_argument("--channel", choices=("party", "say", "overheard", "initiative", "banter", "retort_sorry", "retort_rude"), default="party",
                    help="с --phantom: откуда реплика — чат пати, общий чат с обращением, подслушанное в общем чате "
                         "или initiative: охотник заговаривает первым, MESSAGE — что он видит («рядом с тобой остановился …»)")
    ap.add_argument("--order", default="", help="с --phantom: что сервер уже сделал по приказу («сделано: идёшь за ним следом»)")
    ap.add_argument("--strategy", action="store_true", help="FPC — охотник: спросить стратега, что делать дальше (MESSAGE не нужен)")
    ap.add_argument("--mood", choices=sorted(MOOD_RU), help="с --phantom: настроение охотника, как его присылает сервер")
    ap.add_argument("--relation", choices=("enemy", "friend", "mixed", "faded", "rival", "pal"),
                    help="с --phantom: отношение охотника к игроку, как его присылает сервер (патч 0032): враг, друг, сложно, почти забылось; "
                         "rival и pal — собеседник тоже охотник (патч 0033): трижды отжал моба / качались вместе и реснул")
    ap.add_argument("--summarize", metavar="FPC", help="свернуть старые реплики пары FPC — --player в заметку сейчас, даже если их мало")
    args = ap.parse_args()
    brain = Brain(args.chronicle, echo=args.echo)
    if args.summarize:
        old, through = brain._summary(args.summarize, args.player)
        stale = brain._stale_lines(args.summarize, args.player, through)
        print(f"--- прежняя заметка (до строки {through}) ---\n{old or '(нет)'}")
        print(f"--- старых реплик сверх последних {HISTORY}: {len(stale)} ---")
        if stale:
            print(brain.summarize(args.summarize, args.player, force=True) or "(не вышло — см. data/brain/" + args.chronicle + ".log)")
        return 0
    if args.test and args.strategy:
        fpc = args.test[0]
        req = {"kind": "strategy", "fpc": fpc, "test": "cli", "realPlayers": 1, "gameTime": "14:20", "night": False, "afk": [5, 30],
               "hunter": {"level": 34, "class": "ARTISAN", "race": "DWARF", "role": "DAMAGE", "mage": False, "adena": 412000, "money": "412к",
                          "expPct": 63.5, "hpPct": 88, "mpPct": 40,
                          "gear": "надето: оружие D, нагрудник D, штаны D, шлем NG, перчатки NG, ботинки D, бижа 0/5; до сета D не хватает: "
                                  "шлем (~49к), перчатки (~36к) — всего ~85к; дальше — C с 40 ур.",
                          "supplies": "сосы D 340/2000, банок 4/30", "drop": "Mithril Gloves, Elven Earring — всего ~96к по цене магазина",
                          "inventoryPct": 46, "spot": "Cruma Marshlands", "spotMin": 30, "spotMax": 35, "spotMinutes": 55, "spotExpPct": 6.2,
                          "idleMinutes": 0, "roams": 0, "deathsHour": 2, "townMinutes": 48, "storeMinutes": -1, "town": "Town of Dion",
                          "relations": [{"name": args.player, "kind": "enemy", "grudge": 3, "gratitude": 0, "kills": 1, "online": True, "reasons": ["killed"]},
                                        {"name": "Ebalay", "kind": "friend", "grudge": 0, "gratitude": 4, "kills": 0, "online": False,
                                         "reasons": ["revived", "healed"]}]},
               "options": [{"tag": "HUNT", "note": "Cruma Marshlands (мобы 30-35 ур.)"}, {"tag": "SPOT_UP", "note": "Cruma Tower (мобы 34-38 ур.)"},
                           {"tag": "SPOT_DOWN", "note": "Execution Grounds (мобы 28-31 ур.)"}, {"tag": "TOWN", "note": "Town of Dion"},
                           {"tag": "STORE", "note": "Mithril Gloves, Elven Earring — всего ~96к по цене магазина"},
                           {"tag": "PARTY", "note": "крикнуть в торговый чат"}, {"tag": "AFK", "note": ""}]}
        print("--- system prompt ---")
        print(brain.strategy_prompt(req))
        print("--- решение ---")
        print(brain.decide(req))
        return 0
    if args.test:
        fpc, message = args.test
        req = {"fpc": fpc, "channel": "whisper", "message": message, "gameTime": "14:20", "night": False,
               "player": {"name": args.player, "level": 52, "class": "TREASURE_HUNTER", "race": "HUMAN", "clan": ""},
               "npc": {"level": brain.personas.get(fpc, {}).get("level", 40), "hpPct": 100, "inCombat": False, "town": "Town of Giran"}}
        if args.phantom:
            role = {"хилер": "HEALER", "баффер": "BUFFER"}.get(brain.personas.get(fpc, {}).get("role", ""), "DAMAGE")
            req.update(kind="phantom", channel="event" if args.event else args.channel, order=args.order)
            req["player"].update(owner=args.channel not in ("overheard", "initiative", "banter", "retort_sorry", "retort_rude"), hpPct=100, dead=False, distance=90)
            if args.relation:
                req["player"]["relation"] = {
                    "enemy": {"grudge": 6, "gratitude": 0, "kills": 2, "enemy": True, "friend": False,
                              "reasons": [{"type": "killed", "ago": 3 * 3600}, {"type": "killed", "ago": 86400}]},
                    "friend": {"grudge": 0, "gratitude": 5, "kills": 0, "enemy": False, "friend": True,
                               "reasons": [{"type": "revived", "ago": 1800}, {"type": "healed", "ago": 2 * 86400}]},
                    "mixed": {"grudge": 3, "gratitude": 3, "kills": 1, "enemy": False, "friend": False,
                              "reasons": [{"type": "revived", "ago": 600}, {"type": "killed", "ago": 86400}]},
                    "faded": {"grudge": 0.6, "gratitude": 0, "kills": 1, "enemy": False, "friend": False,
                              "reasons": [{"type": "killed", "ago": 9 * 86400}]},
                    "rival": {"grudge": 3, "gratitude": 0, "kills": 0, "enemy": True, "friend": False,
                              "reasons": [{"type": "contested", "ago": 600}, {"type": "contested", "ago": 5400}, {"type": "contested", "ago": 86400}]},
                    "pal": {"grudge": 0, "gratitude": 5, "kills": 0, "enemy": False, "friend": True,
                            "reasons": [{"type": "revived", "ago": 1200}, {"type": "hunted", "ago": 7200}]}}[args.relation]
                req["player"]["grudge"] = req["player"]["relation"]["kills"]
                req["player"]["hunter"] = args.relation in ("rival", "pal")
            hunter = bool(brain.personas.get(fpc, {}).get("hunter"))
            req["phantom"] = {"role": role, "hunter": hunter, "level": 52, "mood": args.mood or "",
                              "state": "качаешься на споте Cruma Marshlands (мобы 30-35 ур.)" if hunter else f"в пати с {args.player}: идёшь за ним следом",
                              "class": "BISHOP" if role == "HEALER" else "PROPHET", "hpPct": 100, "mpPct": 80, "inCombat": False,
                              "sitting": False, "dead": False, "target": "", "town": "Town of Giran",
                              "party": [{"name": args.player, "level": 52, "class": "TREASURE_HUNTER", "hpPct": 100, "dead": False, "phantom": False}]}
        print("--- system prompt ---")
        print(brain.messages(req)[0]["content"])
        print("--- ответ ---")
        print(brain.chat(req) or "(промолчал)")
        return 0
    server = ThreadingHTTPServer(("127.0.0.1", int(brain.cfg["port"])), make_handler(brain))
    brain._log(f"старт: порт {brain.cfg['port']}, модель {brain.cfg['model']}, характеров {len(brain.personas)}, ключ {'есть' if brain.key() else 'НЕТ'}{', echo' if args.echo else ''}")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    if sys.stdout:
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
