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

Танатос (docs/PHANTOMS.md, 4.45) шлёт "kind": "reaper" с событием event (whisper, kill, death, hunt, attacked, escaped): ответ — одна
реплика жнеца или пусто; счёт встреч с игроком — из памяти пары; reaper.class — класс его нынешнего тела после смерти.

Мозги-стратег охотников (патч 0019) шлют "kind": "strategy" с состоянием охотника и списком доступных вариантов; ответ —
«[[ТЕГ]] мысль» или «[[AFK 15]] мысль». Тега из списка нет — HTTP 502, сервер тогда живёт по правилам. С «Разумом 2.0»
(docs/MIND.md, раздел 4) ядро шлёт ещё цель охотника (goal) и итог прошлого решения (last); ответ тогда может нести переход
на спот по имени и новую цель: «[[SPOT_TO Cruma Tower · 1 этаж]] [[GOAL до 40-го и сет C]] мысль».

Запуск:   python -m l2hub.brain <id>
Проверка: python -m l2hub.brain <id> --test <фейк> "<что сказать>" [--echo]
          python -m l2hub.brain <id> --test <охотник> - --clanname  (имя клана, который он основывает)
          python -m l2hub.brain <id> --test <напарник> "<что сказать>" --phantom [--event] [--order "<что сделано>"]
          python -m l2hub.brain <id> --test <охотник> - --strategy [--echo] [--goal "<цель>"] [--last "SPOT_TO <спот>: <причина>"]
          python -m l2hub.brain <id> --test Thanatos "<шёпот>" --reaper whisper  (или - --reaper kill|death|hunt|attacked|escaped)
                                                                       [--body MYSTIC_MUSE]  (класс тела)
  --echo — без модели: ответ «эхо: …», чтобы проверить связку с сервером, не тратя запросы.
"""

import argparse
import difflib
import hashlib
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
from contextlib import contextmanager
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import catalog, knowledge, paths, worldtalk

BRAIN_DIR = paths.DATA / "brain"
KEY_FILE = BRAIN_DIR / "deepseek.key"
DEFAULTS = {"port": 8771, "model": "deepseek-flash", "baseUrl": "https://api.deepseek.com", "maxTokens": 120, "alphabet": "auto"}
# Выбор модели из лаунчера (вне git): api — облако с ключом (DeepSeek и любое OpenAI-совместимое), local — своя модель
# на этом ПК (LM Studio, Ollama: тоже OpenAI-совместимый API, без ключа). Сервис перечитывает файл на ходу.
SETTINGS_FILE = BRAIN_DIR / "settings.json"
USAGE_FILE = BRAIN_DIR / "usage.json"
MODES = ("api", "local")
LOCAL_DEFAULT = {"baseUrl": "http://127.0.0.1:1234/v1", "model": ""}
# «Расширенное обдумывание» (docs/MIND.md, решения автора 2026-10-23): решения охотников рядом с игроком (focus от сервера) —
# моделью с размышлением, пока за день на это ушло меньше dailyUsd; focusMax — сколько охотников рядом умные (ключ сервера).
EXT_DEFAULT = {"enabled": False, "dailyUsd": 1.0, "focusMax": 5}
EXT_SUFFIX = ":ext"     # строки usage.json расширенного режима — его дневной расход считается отдельно
EXT_PRICE = (0.30, 1.20)  # $ за 1 млн токенов, вход и выход (deepseek-flash): тариф в «Разуме» не вписан — лимит в $ считается по нему
# Живая речь (цель автора 2026-10-30, docs/LIVE_SERVER.md, п. 4): фейки, напарники и охотники пишут, как люди в чате старого сервера. live — манера письма
# и жизнь за окном по реальным часам; swear — мат по характеру; оба выключаются в «Разуме». Какая манера у кого, решает ник (voice_mask): между запусками
# одна и та же, таблицы характеров и генератор не трогаем; манера из таблицы главнее.
VOICE_DEFAULT = {"live": True, "swear": True, "world": True, "forum": True}
VOICE_TYPING = ("маленькими буквами, без точек и запятых", "маленькими буквами, часто обрываешь фразу, лишь бы быстрее",
                "торопишься и не перечитываешь: опечатки, пропущенные буквы", "почти грамотно, но без точки в конце",
                "совсем коротко, два-три слова, сокращаешь всё, что можно (щас, норм, спс, хз)")
VOICE_TRANSLIT = "транслитом: русские слова латинскими буквами (privet, kak dela, go v pati), раскладку не переключаешь"
VOICE_TRANSLIT_SHARE = 10  # % ников пишут транслитом, как на старых серверах
VOICE_SWEAR = ("не материшься", "материшься редко, когда прижмёт (бля, пиздец)", "материшься часто и без злобы, как дышишь (бля, нахуй, пиздец, ёпта — прямо так, без звёздочек)")
VOICE_CHAT = ("Ты человек за компом, а не роль: характер проскакивает, а не играется в каждой фразе, без театра и красивостей. "
              "Это игровой чат, а не письмо: чаще всего пара слов или обрывок, смех — ахах, лол, ))). Ты не помощник: незнакомым отвечаешь коротко и без "
              "вежливых оборотов, помощь сам не предлагаешь и не заканчиваешь каждую реплику вопросом. Если манера речи выше спорит с этим — главнее она. "
              "Никаких действий в звёздочках. Свой класс, уровень, HP и где стоишь называешь, только если спросили: люди не докладывают о себе в каждой реплике. "
              "Без угроз вне игры и без оскорблений нации, веры и пола.")
VOICE_LIFE = ("работа в офисе", "сменная работа", "учёба", "работа на удалёнке", "подработка и жизнь с родителями", "дома маленький ребёнок")
VOICE_DAY = ((6, "глубокая ночь, большинство спит"), (11, "утро"), (17, "день"), (23, "вечер, самый онлайн"), (24, "поздний вечер"))
VOICE_WEEK = ("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
KEY_NAME_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,64}\.key$")
_usage_lock = threading.Lock()


def load_settings(profile_brain: dict) -> dict:
    """Настройки мозгов: data/brain/settings.json поверх раздела brain профиля (оттуда — модель и адрес API по умолчанию)."""
    base = dict(DEFAULTS, **profile_brain)
    s = {"mode": "api", "api": {"baseUrl": base["baseUrl"], "model": base["model"], "keyFile": KEY_FILE.name},
         "local": dict(LOCAL_DEFAULT), "maxTokens": int(base["maxTokens"]), "priceIn": 0.0, "priceOut": 0.0, "extended": dict(EXT_DEFAULT), "voice": dict(VOICE_DEFAULT)}
    try:
        saved = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        saved = {}
    for k in ("mode", "maxTokens", "priceIn", "priceOut"):
        if k in saved:
            s[k] = saved[k]
    for k in MODES:
        s[k].update({kk: v for kk, v in (saved.get(k) or {}).items() if kk in s[k]})
    s["extended"].update({kk: v for kk, v in (saved.get("extended") or {}).items() if kk in EXT_DEFAULT})
    s["voice"].update({kk: bool(v) for kk, v in (saved.get("voice") or {}).items() if kk in VOICE_DEFAULT})
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

    ext = {"requests": 0, "in": 0, "out": 0, "cost": 0.0}  # только строки расширенного режима
    price_in, price_out = (float(settings.get("priceIn") or 0), float(settings.get("priceOut") or 0))
    if not (price_in or price_out):
        price_in, price_out = EXT_PRICE
    for name, row in days.get(today, {}).items():
        if name.endswith(EXT_SUFFIX):
            for k in ("requests", "in", "out"):
                ext[k] += row.get(k, 0)
            ext["cost"] += (row.get("in", 0) * price_in + row.get("out", 0) * price_out) / 1e6
    ext["cost"] = round(ext["cost"], 4)
    return {"today": total([today]), "week": total(week), "all": total(days), "extendedToday": ext}
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


def clan_name_text(raw: str) -> str:
    """Имя клана из ответа мозгов: латиница и цифры, 3–16 знаков (ClanNameTemplate Mobius). Модель иногда отвечает кириллицей («Вечерня»):
    тогда транслит, слова — вместе, каждое с большой буквы («Ночные волки» — NochnyeVolki); длиннее 16 — первое слово."""
    if re.search("[А-Яа-яЁё]", raw):
        words = re.findall(r"[A-Za-z0-9]+", translit(raw))
        joined = "".join(w[:1].upper() + w[1:].lower() for w in words)
        raw = joined if len(joined) <= 16 or not words else words[0][:1].upper() + words[0][1:].lower()
    match = re.search(r"[A-Za-z0-9]{3,16}", raw)
    return match.group(0) if match else ""
HISTORY = 12            # реплик разговора с игроком в промпте
HISTORY_STALE = 1800    # с: событие старше — в промпте с давностью; пауза в разговоре дольше — пометка «прошло …»
# Сжатие памяти (приём L2Solo, docs/PHANTOMS.md, раздел 4.10): реплики пары старше последних HISTORY модель сворачивает
# в заметку «что я помню о нём» — в промпт идут заметка и последние HISTORY реплик. Строки в lines не удаляются.
SUMMARY_EVERY = 12      # столько старых реплик сверх HISTORY накопилось — обновить заметку
SUMMARY_MAX = 600       # символов в заметке
SUMMARY_TOKENS = 400    # ответ модели на заметку
SUMMARY_RETRY = 600     # с: заметка не вышла (ошибка, лимит) — эту пару столько не трогать
MAX_REPLY = 160         # символов в реплике
TOWN_REPLY = 120        # символов в реплике городского фейка (просим до 80, это запас)
TOWN_NEWS = 0.4         # доля праздной болтовни фейка о новостях мира
TOWN_NEWS_ROWS = 2      # новостей на такую реплику
# Торговец и скупщик сидят настоящей лавкой (TownFakesStores, PhantomStores): её щёлкают, а цены — в окне. Значения — role из L2HubTownFakePersonas.json.
TOWN_SHOP = {"торговец на лавке": "купят у тебя", "скупщик на лавке": "продадут тебе"}
# Роли городских фейков (L2HubTownFakes, c4-interlude): чем занят в городе — для промпта.
TOWN_ROLES = {"trader": "сидишь с лавкой и зазываешь покупателей", "buyer": "скупаешь вещи, ищешь, кто продаст",
              "crafter": "крафтер, предлагаешь сделать вещь из ресурсов", "afk": "стоишь афк, отошёл от компа и иногда отвечаешь",
              "talker": "болтаешь у гейткипера обо всём подряд", "newbie": "новичок: спрашиваешь дорогу, советы, удивляешься всему",
              "lfp": "ищешь пати на кач", "runner": "бегаешь по делам между складом, магазином и ГК",
              "showoff": "хвастаешься шмотом и заточкой", "stroller": "гуляешь по городу без дела",
              "nightowl": "сова: сидишь в игре по ночам, когда остальные спят"}
RATE_PER_MINUTE = 40    # запросов к модели в минуту на весь сервер
LLM_TIMEOUT = 15
EXT_TIMEOUT = 45        # с: с размышлением модель думает дольше; сервер ждёт решения охотника рядом с игроком дольше
EXT_MAX_TOKENS = 2000   # размышление и ответ стратега вместе
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
              "clan": "пишет в чат клана — это видят все соклановцы в сети",
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
             "friended": ("добавил тебя в друзья", "добавила тебя в друзья"), "gifted": ("подарил тебе вещь", "подарила тебе вещь"),
             "apologized": ("извинился, что отжал моба, и ушёл на другой спот", "извинилась, что отжала моба, и ушла на другой спот"),
             "insulted": ("послал тебя, когда ты предъявил за моба", "послала тебя, когда ты предъявил за моба"),
             "healed": ("хилил тебя в бою, когда у тебя было мало HP", "хилила тебя в бою, когда у тебя было мало HP"),
             "revived": ("реснул тебя", "реснула тебя"),
             "saved": ("спас тебя — добил моба, который тебя добивал", "спасла тебя — добила моба, который тебя добивал"),
             "hunted": ("качался с тобой в пати", "качалась с тобой в пати"),
             "duel_win": ("победил тебя на дуэли", "победила тебя на дуэли"),
             "duel_loss": ("проиграл тебе дуэль", "проиграла тебе дуэль"),
             "buffed": ("дал тебе бафф, когда ты попросил", "дала тебе бафф, когда ты попросил"),
             "sold": ("продал тебе то, что ты просил", "продала тебе то, что ты просил"),
             # Кланы охотников (раздел 4.50): доля обиды и благодарности соклановца.
             "clan_harm": ("обидел твоего соклановца", "обидела твоего соклановца"),
             "clan_help": ("выручил твоего соклановца", "выручила твоего соклановца"),
             # Случайности людей (раздел 4.77).
             "gift_given": ("получил от тебя в подарок денег, когда был новичком", "получила от тебя в подарок денег, когда была новичком"),
             "loot_fight": ("забрал вещь, которая была нужна тебе, когда вы качались в пати", "забрала вещь, которая была нужна тебе, когда вы качались в пати"),
             "scammed": ("кинул тебя в обмене на адену", "кинула тебя в обмене на адену")}
REASON_SHORT = {"killed": "убил", "attacked": "нападал", "contested": "отжал моба", "apologized": "извинился", "insulted": "послал", "healed": "хилил", "revived": "реснул", "saved": "спас", "hunted": "качались вместе", "duel_win": "победил на дуэли", "duel_loss": "проиграл дуэль",
                "clan_harm": "обидел соклановца", "clan_help": "выручил соклановца", "friended": "добавил в друзья", "gifted": "подарил вещь", "buffed": "дал бафф", "sold": "продал, что просили",
                "gift_given": "получил подарок", "loot_fight": "забрал дроп", "scammed": "кинул в обмене"}


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
# Танатос (docs/PHANTOMS.md, 4.45): единственный ПК сервера, «жнец». Ядро шлёт "kind": "reaper" с событием (event); ответ — одна
# реплика или пусто (молчит). Память — как у пар фейк–игрок: события и реплики пары в lines, счёт встреч — оттуда же, без потолка HISTORY.
REAPER_REPLY = 90       # символов в реплике (просим до 60, это запас)
# Событие → (что случилось — для промпта, что запомнить — для памяти пары). {pl} — кто, {him}/{he} — его/он по полу.
REAPER_EVENTS = {
    "whisper": ("{pl} пишет тебе в личку.", ""),
    "kill": ("Ты только что забрал {pl}: {he} лежит мёртвый у твоих ног. Скажи {him_dat} одну фразу.", "ты забрал {him}"),
    "death": ("{pl} только что убил тебя{party}. Ты падаешь. Это твои последние слова — их слышат все рядом.", "{he} убил тебя"),
    "hunt": ("{pl} собрал на тебя охоту{party} и идёт в бой. Скажи одну фразу — её слышат все рядом.", "{he} вёл на тебя охоту"),
    "attacked": ("{pl} сам напал на тебя. Скажи {him_dat} одну фразу.", "{he} сам напал на тебя"),
    "escaped": ("{pl} ушёл от тебя в мирную зону, и ты отпустил {him}. Скажи {him_dat} одну фразу вслед — {he} увидит её в личке.",
                "{he} ушёл от тебя"),
}
REAPER_TALLY = (("kill", "ты забирал {him} {n}"), ("death", "{he} убивал тебя {n}"), ("escaped", "{he} уходил от тебя {n}"),
                ("hunt", "{he} водил на тебя охоту {n}"), ("attacked", "{he} нападал на тебя сам {n}"))
# Каналы разговора без базы знаний: реакция на событие, перепалки и подслушанное — не про то, где качаться.
KB_SKIP_CHANNELS = ("event", "banter", "retort_sorry", "retort_rude", "overheard")
STRATEGY_PLAYER = "#strategy"   # «собеседник» решений стратега в памяти: в игровых именах # не бывает
STRATEGY_HISTORY = 3            # прошлых решений в промпте
STRATEGY_RATE_PER_MINUTE = 20   # запросов стратега в минуту на весь сервер: остальное из RATE_PER_MINUTE — разговорам
STRATEGY_RE = re.compile(r"\[\[\s*([A-Z_]+)(?:[\s:]+([^\[\]]{1,120}?))?\s*\]\]")
STRATEGY_HELP = {"HUNT": "качаться дальше тут", "SPOT_UP": "сменить спот на мобов посильнее",
                 "SPOT_DOWN": "сменить спот на мобов послабее",
                 "SPOT_TO": "перейти на другой спот",
                 "TOWN": "сходить в город: сдать хлам, купить сосы, банки и шмот, ребафнуться — и обратно",
                 "STORE": "сходить в город и сесть в лавку в Гиране — продать ценный дроп",
                 "PARTY": "поискать пати: крикнуть «лфп» и качаться дальше, пока не позовут",
                 "AFK": "отдохнуть в городе N минут: посидеть афк",
                 "RAID": "собрать народ в рейд на рейд-босса своего уровня и повести его — долго и опасно, зато лут и слава",
                 "BUY": "сходить в город, первым делом купить у NPC и надеть, заодно всё, что делает поход в город, — и обратно; "
                        "можно до 3 вещей через «;» — купит по порядку, на сколько хватит адены",
                 "SELL": "сходить в город, первым делом сдать NPC эту вещь за полцены, заодно всё, что делает поход в город, — и обратно; "
                         "быстрее, но дешевле, чем в лавке",
                 # Шаг 6 (docs/MIND.md, раздел 6): живой игрок рядом. Мысль у этих вариантов — то, что охотник скажет ему.
                 "HELP": "помочь тому, кто рядом, тем, что умеешь (рес, хил, мана, бафы или добить моба, который его бьёт) — чем именно, решит игра",
                 "SHARE": "поделить спот: он на твоём споте — бьёте разных мобов и ты не обижаешься; он рядом — позвать его к себе",
                 "YIELD": "уступить ему спот и уйти на другой",
                 "TRADE": "предложить ему вещь из сумки за свою цену: шепнуть ему и посидеть лавкой прямо тут 5 минут"}
# Что охотник в разговоре обещает: только то, что сервер исполнит (PhantomHunterManager.acceptsInvite, лавка; /trade — ключ HunterTrade).
HUNTER_INVITE = ("Ты качаешься сам, но в пати позвать можно: пусть кидает инвайт, а пойдёшь или нет, решит игра (ты свободен, уровни близко, он тебе не враг), "
                 "поэтому наверняка не обещай и сам в пати не зови. ")
HUNTER_GOODS = "Продаёшь только лавкой, когда сядешь в неё, вещи не передаёшь и в трейд не идёшь — "
HUNTER_TRADE = ("В трейд зайти можешь: заплатишь адену за вещь, если она тебе нужна, и примешь подарок. "
                "Сам вещи через трейд не отдаёшь и не продаёшь, только лавкой, когда сядешь в неё — ")
# Охотник заговаривает первым с особой целью (канал initiative, поле chat): что сказать, ядро пишет в message.
INITIATIVE_TASK = {"favor": ("Скажи это {name} в общем чате рядом одной короткой репликой, своими словами, как обычный игрок просит встречного, без долгих объяснений. "
                             "Платы не обещай, если о ней не сказано выше. Если не в твоём характере просить — ответь ровно словом <молчу>."),
                   "far": ("Это шёпот в личку {name}: одна короткая реплика, как знакомому. Не зови к себе и не обещай прийти. "
                           "Если писать незачем — ответь ровно словом <молчу>.")}
PARTY_NAMED = ("позвать в пати того, кто рядом: свободного — к себе, лидера пати — попроситься к нему; "
               "живой игрок решает сам. В пати мобы падают быстрее, с хилом и бафером умираешь реже, опыт делится")


# «Разум 2.0», шаг 3 (docs/MIND.md, раздел 4): цель охотника и переход на спот по имени. Ядро шлёт "goal" (цель, которую охотник
# держит; нет цели — нет ключа) и "last" (итог прошлого решения: выполнено или отказ с причиной); опция SPOT_TO может нести
# свои "choices" — споты, куда ядро готово вести. Без них споты на выбор берутся из базы знаний под уровень.
GOAL_LEN = 60       # символов в цели


def v1_goals(req: dict) -> bool:
    """Ядро понимает цели (протокол v1): цель есть, есть итог прошлого решения, вариант SPOT_TO или вариант со списком от ядра.
    Без цели ядро ключ goal не шлёт, поэтому одного goal мало."""
    return "goal" in req or "last" in req or any(o.get("tag") == "SPOT_TO" or o.get("choices") for o in req.get("options", []))
SPOT_CHOICES = 4    # спотов на выбор у [[SPOT_TO]] из базы знаний
SPOT_CHOICES_MAX = 8  # из списка ядра — не больше

# BUY, SELL и PARTY с именем (2026-10-24, docs/PHANTOMS.md, 4.3): у варианта "choices" от ядра — вещи с ценой, люди рядом.
# Имя в ответе сверяется со списком, как спот у SPOT_TO. [[PARTY]] без имени — крик «лфп», как раньше.
CHOICES_MAX = {"BUY": 12, "SELL": 8, "PARTY": 8, "HELP": 3, "SHARE": 3, "YIELD": 3, "TRADE": 3}
NAME_REQUIRED = ("SPOT_TO", "BUY", "SELL", "HELP", "SHARE", "YIELD", "TRADE")  # без имени из списка тег не в счёт
BUY_MAX = 3  # вещей в [[BUY A; B; C]]: ядро покупает по порядку, на сколько хватит свободной адены
TRADE_ITEMS = 6  # вещей одного игрока на выбор у [[TRADE]]

# Шаг 6: что случилось с живым рядом — повод решить раньше срока ("trigger" в запросе стратега).
TRIGGER_RU = {"ARRIVED": "{name} только что пришёл на твой спот и бьёт мобов", "LOW_HP": "{name} рядом в бою, у него мало HP",
              "DIED": "{name} рядом только что умер", "SAME_MOB": "{name} бьёт того же моба, что и ты",
              "AGGRO": "на {name} рядом навалились мобы — их несколько"}
PRICE_RE = re.compile(r"\[\[\s*PRICE[\s:]+([^\[\]]{1,20}?)\s*\]\]", re.IGNORECASE)


def parse_price(text: str) -> int | None:
    """Цена из ответа модели, как пишут в игре: «36400», «36 400», «36к», «36.5k», «1кк», «1,2kk». Не цена — None."""
    t = re.sub(r"\s+", "", text).lower()
    t = re.sub(r"^(\d{1,3}(?:,\d{3})+)(?!\d)", lambda m: m[1].replace(",", ""), t)  # «36,400» — разряды, а не дробь
    m = re.match(r"(\d+(?:[.,]\d+)?)(кк|kk|млн|к|k|m)?", t)
    if not m:
        return None
    value = float(m[1].replace(",", ".")) * {"к": 1e3, "k": 1e3, "кк": 1e6, "kk": 1e6, "млн": 1e6, "m": 1e6}.get(m[2] or "", 1)
    return int(value) if value >= 1 else None


def match_trade(arg: str, combos: list[str]) -> str:
    """[[TRADE имя; вещь; цена]]: имя и вещь — из пар «имя; вещь» от ядра, цена — числом (нет или не узнана — без неё, ядро
    возьмёт цену магазина). Не узнаны имя или вещь — пусто."""
    parts = [p.strip() for p in arg.split(";")]
    if len(parts) < 2:
        return ""
    name = match_name(parts[0], list(dict.fromkeys(c.split("; ", 1)[0] for c in combos)))
    item = match_name(parts[1], [c.split("; ", 1)[1] for c in combos if c.startswith(name + "; ")]) if name else ""
    if not item:
        return ""
    price = parse_price(parts[2]) if len(parts) > 2 else None
    return f"{name}; {item}" + (f"; {price}" if price else "")


def choice_name(c) -> str:
    """Имя варианта из choices ядра: у вещей и людей — словарь с "name", у спотов — строка."""
    return str(c.get("name", "") if isinstance(c, dict) else c).strip()


def match_name(arg: str, choices: list[str]) -> str:
    """Спот, вещь или человек из списка по имени из ответа модели: точно (без регистра), по началу или почти точно;
    не узнан — пусто."""
    a = " ".join(arg.split()).strip(" .,«»\"'").casefold()
    if not a or not choices:
        return ""
    by = {c.casefold(): c for c in choices}
    if a in by:
        return by[a]
    starts = [c for c in choices if c.casefold().startswith(a) or a.startswith(c.casefold())]
    if len(starts) == 1:
        return starts[0]
    close = difflib.get_close_matches(a, list(by), n=1, cutoff=0.8)
    return by[close[0]] if close else ""


# «Разум 2.0», шаг 4 (docs/MIND.md, раздел 5): опыт и выводы. Ядро шлёт в last "result" — что было с охотником от решения до
# нынешнего запроса; мозги копят итоги в журнале, показывают опыт по местам и раз в уровень или в сутки просят модель записать выводы.
JOURNAL_DAYS = 2       # опыт по местам — за столько суток
JOURNAL_LEVELS = 3     # и на уровнях ±столько от нынешнего
JOURNAL_SPOTS = 4      # мест в промпте
JOURNAL_MIN = 10       # минут на месте, чтобы показать его опыт
LESSONS_KEY = "#lessons"  # ключ выводов в очереди фоновых задач (как пара у заметки): в игровых именах # не бывает
LESSONS_MAX = 400      # символов в выводах
LESSONS_EVERY = 8      # новых итогов в журнале до пересмотра выводов — и ещё новый уровень или сутки с прошлых
LESSONS_ROWS = 40      # итогов в промпте пересмотра
LESSONS_TOKENS = 300


def init_db(db: sqlite3.Connection) -> None:
    """Таблицы базы мозгов: реплики, заметки о собеседниках, журнал итогов и выводы (шаг 4), забытое из окна (шаг 5)."""
    db.execute("CREATE TABLE IF NOT EXISTS lines (id INTEGER PRIMARY KEY, fpc TEXT, player TEXT, role TEXT, channel TEXT, text TEXT, ts REAL)")
    db.execute("CREATE INDEX IF NOT EXISTS lines_pair ON lines (fpc, player, ts)")
    db.execute("CREATE TABLE IF NOT EXISTS summaries (fpc TEXT, player TEXT, text TEXT, through_id INTEGER, ts REAL, PRIMARY KEY (fpc, player))")
    # Шаг 4 «Разума 2.0»: итоги решений стратега (decided — когда решил) и выводы охотника из них.
    db.execute("CREATE TABLE IF NOT EXISTS journal (id INTEGER PRIMARY KEY, fpc TEXT, decided REAL, ts REAL, level INTEGER, decision TEXT, "
               "ok INTEGER, reason TEXT, minutes REAL, exp REAL, deaths INTEGER, adena INTEGER, kills INTEGER, spot TEXT, party REAL)")
    db.execute("CREATE INDEX IF NOT EXISTS journal_fpc ON journal (fpc, ts)")
    db.execute("CREATE TABLE IF NOT EXISTS lessons (fpc TEXT PRIMARY KEY, text TEXT, through_id INTEGER, level INTEGER, ts REAL)")
    # Шаг 5: «забыть» в окне не стирает насовсем — строки уходят сюда (data — JSON), мозги эту таблицу не читают.
    db.execute("CREATE TABLE IF NOT EXISTS forgotten (id INTEGER PRIMARY KEY, ts REAL, kind TEXT, fpc TEXT, player TEXT, data TEXT)")


def experience_rows(db: sqlite3.Connection, fpc: str, level: int) -> list[tuple]:
    """Опыт по местам из журнала: (спот, минут, опыт %, смертей, адена) за JOURNAL_DAYS суток на уровнях ±JOURNAL_LEVELS."""
    return db.execute("SELECT spot, SUM(minutes), SUM(exp), SUM(deaths), SUM(adena) FROM journal WHERE fpc = ? AND ts >= ? AND spot != '' "
                      "AND level BETWEEN ? AND ? GROUP BY spot HAVING SUM(minutes) >= ? ORDER BY SUM(minutes) DESC LIMIT ?",
                      (fpc, time.time() - JOURNAL_DAYS * 86400, level - JOURNAL_LEVELS, level + JOURNAL_LEVELS, JOURNAL_MIN, JOURNAL_SPOTS)).fetchall()


def per_hour(value: float, minutes: float) -> float:
    return value * 60 / minutes if minutes else 0.0


def signed_adena(n: int) -> str:
    return ("+" if n >= 0 else "−") + knowledge.adena(abs(n))


def result_words(r: dict) -> str:
    """Итог решения словами: «за 11 мин: +2.4% уровня (~13% в час), 1 смерть, адена +38к, мобов 42, в пати 6 мин»."""
    minutes, exp = float(r.get("minutes") or 0), float(r.get("exp") or 0)
    deaths, kills, party = int(r.get("deaths") or 0), int(r.get("kills") or 0), float(r.get("party") or 0)
    parts = [f"{exp:+.1f}% уровня" + (f" (~{per_hour(exp, minutes):.0f}% в час)" if minutes >= 5 and exp > 0 else ""),
             f"смертей {deaths}" if deaths else "без смертей", f"адена {signed_adena(int(r.get('adena') or 0))}"]
    if kills:
        parts.append(f"мобов {kills}")
    if party >= 1:
        parts.append(f"в пати {party:.0f} мин")
    return f"за {minutes:.0f} мин" + (f" на {r['spot']}" if r.get("spot") else "") + ": " + ", ".join(parts)


def match_names(arg: str, choices: list[str], limit: int) -> str:
    """Список имён через «;» (без «;» — через запятую или одно имя) из choices: по порядку, без повторов, не больше limit,
    неузнанные выпадают. Ни одного не узнано — пусто."""
    found = []
    for part in arg.split(";" if ";" in arg else ","):
        name = match_name(part, choices)
        if name and name not in found:
            found.append(name)
    if ";" not in arg and len(found) < 2:
        return match_name(arg, choices)  # запятые — внутри одного имени или хвоста: «Brigandine Helmet (шлем, D, 49к)»
    return "; ".join(found[:limit])


def parse_strategy(raw: str, fpc: str, options: list[str], afk: list[int], named: dict[str, list[str]] | None = None,
                   goal: bool = False) -> tuple[str, str, str, str]:
    """Тег из доступных, его аргумент, новая цель и мысль из ответа модели: «[[SPOT_UP]] мысль», «[[AFK 15]] мысль»,
    «[[SPOT_TO Cruma Tower · 1 этаж]] [[GOAL до 40-го]] мысль», «[[BUY Brigandine Helmet]] мысль» (и без скобок: «HUNT мысль»).
    Аргумент AFK — минуты в пределах afk; у тегов из named — имя из их списка: у SPOT_TO, BUY и SELL имя не из списка — тег
    не в счёт, у PARTY — крик без имени. [[GOAL …]] берётся, только когда ядро держит цели (goal). Тега из списка нет —
    ValueError: это мусор."""
    text = " ".join(raw.split())
    named = named or {}
    m, arg = None, ""
    for x in STRATEGY_RE.finditer(text):
        tag = x.group(1)
        if tag not in options:
            continue
        if tag in named and (found := match_names(x.group(2) or "", named[tag], BUY_MAX) if tag == "BUY"
                             else match_trade(x.group(2) or "", named[tag]) if tag == "TRADE" else match_name(x.group(2) or "", named[tag])):
            m, arg = x, found
            break
        if tag not in NAME_REQUIRED:
            m = x
            break
    if m is None:
        m = re.match(r"^\W*([A-Z_]{3,9})\b(?:[\s:]+(\d{1,3}))?", text)
        if m is None or m.group(1) not in options or m.group(1) in NAME_REQUIRED:
            raise ValueError(f"в ответе нет тега из {', '.join(options)}: {text[:80]!r}")
    tag = m.group(1)
    if tag == "AFK":
        n = re.match(r"\d{1,3}", (m.group(2) or "").strip())
        arg = str(max(afk[0], min(afk[1], int(n.group() if n else 15))))
    new_goal = ""
    if goal:
        g = next((x for x in STRATEGY_RE.finditer(text) if x.group(1) == "GOAL" and x.group(2)), None)
        new_goal = Brain.clean(g.group(2), fpc).strip(" .") if g else ""
        # Пустой тег и цель следом до следующего тега: «[[TOWN]] [[GOAL]] докупить сосы [[TOWN]] мысль» (DeepSeek так пишет).
        bare = None if new_goal else re.search(r"\[\[\s*GOAL\s*\]\]\s*([^\[\]]{3,}?)\s*\[\[", text)
        if bare:
            new_goal = Brain.clean(bare.group(1), fpc).strip(" .")
        if len(new_goal) > GOAL_LEN:
            new_goal = new_goal[:GOAL_LEN + 1].rsplit(" ", 1)[0].rstrip(" ,;—-")
    rest = text[:m.start()] + " " + text[m.end():]
    if goal and bare:
        rest = rest.replace(bare.group(1), " ", 1)
    rest = STRATEGY_RE.sub(" ", rest).replace("[[", " ").replace("]]", " ")
    thought = Brain.clean(rest, fpc).lstrip("|-—–:. ").strip()
    return tag, arg, new_goal, thought[:100]


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


def span_words(seconds: int) -> str:
    """Длительность словами: «40 мин», «5 ч», «4 дн.»."""
    minutes, hours, days = seconds // 60, seconds // 3600, seconds // 86400
    return f"{minutes} мин" if hours < 1 else f"{hours} ч" if days < 1 else f"{days} дн."


def phantom_tags(req: dict) -> list[str]:
    """Теги, которые модели можно ставить: только владельцу напарника, не в ответ на событие, не на подслушанное и не когда заговаривает сам."""
    if req.get("channel") in ("event", "overheard", "initiative", "banter", "retort_sorry", "retort_rude") or req.get("order") or not req.get("player", {}).get("owner"):
        return []
    return ["FOLLOW", "STAY", *ROLE_TAGS.get(req.get("phantom", {}).get("role", ""), []), "SIT", "STAND", "LEAVE"]


def _readable(enum_name: str) -> str:
    return enum_name.replace("_", " ").title() if enum_name else ""


class ModelDown(RuntimeError):
    """Модель не ответила: нет ключа, сеть, HTTP, таймаут, мусор вместо ответа API. Только это в окне — «LLM недоступна»."""


class RateLimited(RuntimeError):
    """Свой потолок запросов в минуту: модель не звали, она тут ни при чём."""


# ---------- окно «Разум» (шаг 5) ----------
# Лаунчер читает базу мозгов сам, без сервиса: окно работает и при выключенном сервере. Сервис пишет в ту же базу —
# sqlite разводит их замками (timeout).

FEED_ROWS = 80          # решений стратега в ленте
FEED_EXT_RECENT = 900   # с: решал с размышлением за это время — «сейчас в расширенном режиме»
TALKS_ROWS = 200        # пар «фантом — собеседник» в памяти
TALK_RECENT = 4         # последних реплик пары в окне
DECISION_LINE_RE = re.compile(r"^\[\[([A-Z_]+)(?: ([^\[\]]+))?\]\]\s*(?:\[\[GOAL ([^\[\]]+)\]\]\s*)?(.*)$", re.S)
FORGET_KINDS = ("talk", "lessons")


@contextmanager
def mind_db(chronicle_id: str):
    db = sqlite3.connect(BRAIN_DIR / f"{chronicle_id}.sqlite", timeout=10)
    try:
        with db:
            init_db(db)
            yield db
    finally:
        db.close()


def _rows(db: sqlite3.Connection, sql: str, args: tuple = ()) -> list[dict]:
    cur = db.execute(sql, args)
    names = [c[0] for c in cur.description]
    return [dict(zip(names, r)) for r in cur.fetchall()]


def mind_view(chronicle_id: str) -> dict:
    """Что помнят мозги мира: лента решений стратега с итогами из журнала, охотники с выводами и опытом по местам, разговоры
    фантомов с заметками. Время — в мс (как в окне). Базы ещё нет — всё пустое."""
    if not (BRAIN_DIR / f"{chronicle_id}.sqlite").exists():
        return {"feed": [], "extNow": [], "hunters": [], "talks": [], "forgotten": 0}
    now = time.time()
    with mind_db(chronicle_id) as db:
        lines = _rows(db, "SELECT fpc, channel, text, ts FROM lines WHERE player = ? ORDER BY ts DESC LIMIT ?", (STRATEGY_PLAYER, FEED_ROWS))
        since = min((x["ts"] for x in lines), default=now) - 120
        results: dict[tuple[str, str], list[dict]] = {}
        for r in _rows(db, "SELECT fpc, decided, decision, ok, reason, minutes, exp, deaths, adena, kills, spot, party FROM journal WHERE decided >= ?", (since,)):
            results.setdefault((r["fpc"], r["decision"]), []).append(r)
        feed = []
        for x in lines:
            m = DECISION_LINE_RE.match(x["text"])
            tag, arg, goal, thought = m.groups() if m else ("", "", "", x["text"])
            decision = (tag or "") + (f" {arg}" if arg else "")
            # Итог — запись журнала того же охотника с тем же решением, принятым в пределах двух минут от ответа мозгов.
            near = [r for r in results.get((x["fpc"], decision), []) if abs(r["decided"] - x["ts"]) < 120]
            res = min(near, key=lambda r: abs(r["decided"] - x["ts"])) if near else None
            feed.append({"fpc": x["fpc"], "at": int(x["ts"] * 1000), "decision": decision, "goal": goal or "", "thought": thought.strip(),
                         "ext": x["channel"] == "strategy+",
                         "result": {k: res[k] for k in ("ok", "reason", "minutes", "exp", "deaths", "adena", "kills", "spot", "party")} if res else None})
        ext_now = sorted({x["fpc"] for x in feed if x["ext"] and now - x["at"] / 1000 < FEED_EXT_RECENT})
        hunters = []
        stats = {r["fpc"]: r for r in _rows(db, "SELECT fpc, COUNT(*) AS n, MAX(ts) AS ts FROM journal GROUP BY fpc")}
        decided = {r["fpc"]: r for r in _rows(db, "SELECT fpc, COUNT(*) AS n, MAX(ts) AS ts FROM lines WHERE player = ? GROUP BY fpc", (STRATEGY_PLAYER,))}
        lessons = {r["fpc"]: r for r in _rows(db, "SELECT fpc, text, level, ts FROM lessons")}
        for fpc in set(stats) | set(decided) | set(lessons):
            row = db.execute("SELECT level FROM journal WHERE fpc = ? ORDER BY ts DESC LIMIT 1", (fpc,)).fetchone()
            level = row[0] if row else (lessons.get(fpc) or {}).get("level") or 0
            places = [{"spot": s, "minutes": mins, "exp": exp, "deaths": deaths, "adena": adena}
                      for s, mins, exp, deaths, adena in (experience_rows(db, fpc, level) if level else [])]
            les = lessons.get(fpc)
            last = max((stats.get(fpc) or {}).get("ts") or 0, (decided.get(fpc) or {}).get("ts") or 0, (les or {}).get("ts") or 0)
            hunters.append({"fpc": fpc, "level": level, "results": (stats.get(fpc) or {}).get("n", 0), "decisions": (decided.get(fpc) or {}).get("n", 0),
                            "lessons": {"text": les["text"], "level": les["level"], "at": int(les["ts"] * 1000)} if les else None,
                            "places": places, "at": int(last * 1000)})
        hunters.sort(key=lambda h: -h["at"])
        talks = _rows(db, "SELECT fpc, player, COUNT(*) AS n, COALESCE(SUM(role = 'player'), 0) AS said, MAX(ts) AS ts FROM lines "
                          "WHERE player != ? GROUP BY fpc, player ORDER BY MAX(ts) DESC LIMIT ?", (STRATEGY_PLAYER, TALKS_ROWS))
        notes = {(r["fpc"], r["player"]): r for r in _rows(db, "SELECT fpc, player, text, ts FROM summaries")}
        recent: dict[tuple[str, str], list[dict]] = {}
        for r in _rows(db, "SELECT fpc, player, role, text, ts FROM (SELECT fpc, player, role, text, ts, ROW_NUMBER() OVER "
                           "(PARTITION BY fpc, player ORDER BY ts DESC, id DESC) AS k FROM lines WHERE player != ?) WHERE k <= ? ORDER BY ts", (STRATEGY_PLAYER, TALK_RECENT)):
            recent.setdefault((r["fpc"], r["player"]), []).append({"role": r["role"], "text": r["text"], "at": int(r["ts"] * 1000)})
        for t in talks:
            note = notes.get((t["fpc"], t["player"]))
            t.update(at=int(t.pop("ts") * 1000), note={"text": note["text"], "at": int(note["ts"] * 1000)} if note else None,
                     recent=recent.get((t["fpc"], t["player"]), []))
        forgotten = db.execute("SELECT COUNT(*) FROM forgotten").fetchone()[0]
    return {"feed": feed, "extNow": ext_now, "hunters": hunters, "talks": talks, "forgotten": forgotten}


def mind_forget(chronicle_id: str, kind: str, fpc: str, player: str = "") -> int:
    """«Забыть» из окна. talk — разговор фантома fpc с player и заметка о нём (заметка сама по себе вернулась бы из старых
    реплик); lessons — выводы охотника и журнал итогов, из которого они выведены. Насовсем не стирает: строки — в таблицу
    forgotten (JSON), мозги её не читают. Возвращает, сколько строк забыто."""
    if kind not in FORGET_KINDS or not fpc or (kind == "talk" and (not player or player == STRATEGY_PLAYER)):
        raise ValueError(f"нечего забывать: {kind} {fpc} {player}".strip())
    with mind_db(chronicle_id) as db:
        if kind == "talk":
            parts = {"lines": ("lines", "fpc = ? AND player = ?", (fpc, player)), "summaries": ("summaries", "fpc = ? AND player = ?", (fpc, player))}
        else:
            parts = {"journal": ("journal", "fpc = ?", (fpc,)), "lessons": ("lessons", "fpc = ?", (fpc,))}
        data = {name: _rows(db, f"SELECT * FROM {table} WHERE {where}", args) for name, (table, where, args) in parts.items()}
        n = sum(len(v) for v in data.values())
        if n:
            db.execute("INSERT INTO forgotten (ts, kind, fpc, player, data) VALUES (?, ?, ?, ?, ?)",
                       (time.time(), kind, fpc, player, json.dumps(data, ensure_ascii=False)))
            for table, where, args in parts.values():
                db.execute(f"DELETE FROM {table} WHERE {where}", args)
    return n


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
        # Третий — городские фейки (патч 0031 c4-interlude, скрипт custom/L2HubTownFakes): без него шёпот им — с пустым характером.
        self.personas_files = [data / "L2HubFakePersonas.json", data / "L2HubPhantomPersonas.json", data / "L2HubTownFakePersonas.json"]
        self.personas = self._load_personas()
        self.config_dir = catalog.server_dir(p) / "game" / "config" / "Custom"
        self.hunters_ini = catalog.server_dir(p) / "game" / "config" / "Custom" / "PhantomHunters.ini"
        self.talk = worldtalk.WorldTalk(catalog.server_dir(p) / "game" / "log", REASON_SHORT)
        # База знаний охотников (tools/hunter_knowledge.py): в расширенном режиме — кусок под охотника рядом с игроком.
        self.knowledge = knowledge.Knowledge(data, catalog.server_dir(p) / "game" / "config")
        self.force_ext = False  # проверка из консоли (--ext): расширенный режим без focus, тумблера и лимита
        BRAIN_DIR.mkdir(parents=True, exist_ok=True)
        self.db_path = BRAIN_DIR / f"{chronicle_id}.sqlite"
        self.log_path = BRAIN_DIR / f"{chronicle_id}.log"
        self._init_db()
        self.locks: dict[tuple[str, str], threading.Lock] = {}
        self.locks_guard = threading.Lock()
        self.summarizing: set[tuple[str, str]] = set()
        self.summary_retry: dict[tuple[str, str], float] = {}
        self.calls = deque()
        self.strategy_calls = deque()
        self.test_tags: dict[str, int] = {}
        # errors и lastError — сбои модели (ModelDown), llmOk — удался ли последний запрос к ней (None — ещё не звали); bad и lastBad —
        # модель ответила, но не в дело (у стратега — нет тега из вариантов), или запрос сломался не из-за неё; limited — свой лимит.
        self.stats = {"requests": 0, "replies": 0, "silent": 0, "errors": 0, "lastError": "", "lastReplyAt": "", "decisions": 0, "summaries": 0,
                      "extended": 0, "llmOk": None, "bad": 0, "lastBad": "", "limited": 0}
        self.ext_capped = ""  # день, когда лимит расширенного режима выбран (строка в журнале — раз в день)

    # ---------- данные ----------

    def _load_personas(self) -> dict:
        personas = {}
        for f in self.personas_files:
            if f.exists():
                # Файл городских фейков (tools/l2hub_town_fakes.py) — сразу {ник: характер}, без обёртки "personas".
                loaded = json.loads(f.read_text(encoding="utf-8"))
                personas.update(loaded["personas"] if "personas" in loaded else loaded)
        return personas

    def _db(self) -> sqlite3.Connection:
        return sqlite3.connect(self.db_path, timeout=10)

    def _init_db(self) -> None:
        with self._db() as db:
            init_db(db)

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

    def _history(self, fpc: str, player: str) -> list[tuple[str, str, float]]:
        with self._db() as db:
            rows = db.execute("SELECT role, text, ts FROM lines WHERE fpc = ? AND player = ? ORDER BY ts DESC LIMIT ?", (fpc, player, HISTORY)).fetchall()
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
            f"Ты ведёшь память персонажа {fpc} — игрока Lineage II — о его знакомом {player}.",
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
                raise RateLimited("слишком много запросов в минуту")
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

    # ---------- опыт и выводы (шаг 4) ----------

    def journal_add(self, fpc: str, level: int, last: dict) -> bool:
        """Итог прошлого решения (last.result от ядра) — в журнал. Одно решение — одна запись: тот же last ещё раз (мозги не
        ответили, сервер спросил снова) обновляет её. Без result — ничего."""
        r = last.get("result") or {}
        if not r or not last.get("tag"):
            return False
        now = time.time()
        # Когда решил: last.at от ядра (unix-секунды) — точно; без него — по давности в минутах, с точностью до минуты.
        decided = float(last["at"]) if last.get("at") else now - float(last.get("minutes") or r.get("minutes") or 0) * 60
        window = 5 if last.get("at") else 30
        decision = last["tag"] + (f" {last['arg']}" if last.get("arg") else "")
        values = (int(r.get("level") or level or 0), int(bool(last.get("ok"))), str(last.get("reason") or ""), float(r.get("minutes") or 0),
                  float(r.get("exp") or 0), int(r.get("deaths") or 0), int(r.get("adena") or 0), int(r.get("kills") or 0), str(r.get("spot") or ""),
                  float(r.get("party") or 0))
        with self._db() as db:
            same = db.execute("SELECT id FROM journal WHERE fpc = ? AND decision = ? AND ABS(decided - ?) < ?", (fpc, decision, decided, window)).fetchone()
            if same:
                db.execute("UPDATE journal SET ts = ?, level = ?, ok = ?, reason = ?, minutes = ?, exp = ?, deaths = ?, adena = ?, kills = ?, spot = ?, "
                           "party = ? WHERE id = ?", (now, *values, same[0]))
            else:
                db.execute("INSERT INTO journal (fpc, decided, ts, decision, level, ok, reason, minutes, exp, deaths, adena, kills, spot, party) "
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (fpc, decided, now, decision, *values))
        return True

    def experience(self, fpc: str, level: int) -> str:
        """Опыт по местам из журнала: за JOURNAL_DAYS суток на уровнях ±JOURNAL_LEVELS — опыт, смерти и адена в час."""
        with self._db() as db:
            rows = experience_rows(db, fpc, level)
        return "; ".join(f"{spot} — {span_words(int(m * 60))}: ~{per_hour(exp, m):.1f}% уровня в час, смертей {per_hour(deaths, m):.1f} в час, "
                         f"адена {signed_adena(int(per_hour(adena, m)))} в час" for spot, m, exp, deaths, adena in rows)

    def _lessons(self, fpc: str) -> tuple[str, int, int, float]:
        """Выводы охотника: текст, id последнего учтённого итога, уровень и время; нет — ("", 0, 0, 0)."""
        with self._db() as db:
            row = db.execute("SELECT text, through_id, level, ts FROM lessons WHERE fpc = ?", (fpc,)).fetchone()
        return tuple(row) if row else ("", 0, 0, 0.0)

    def _journal_since(self, fpc: str, through_id: int) -> list[tuple]:
        with self._db() as db:
            rows = db.execute("SELECT id, decided, level, decision, ok, reason, minutes, exp, deaths, adena, kills, spot, party FROM journal "
                              "WHERE fpc = ? AND id > ? ORDER BY id DESC LIMIT ?", (fpc, through_id, LESSONS_ROWS)).fetchall()
        return list(reversed(rows))

    def lessons_prompt(self, fpc: str, level: int, old: str, rows: list[tuple]) -> str:
        p = self.personas.get(fpc, {})
        lines = []
        for _id, decided, lvl, decision, ok, reason, minutes, exp, deaths, adena, kills, spot, party in rows:
            r = {"minutes": minutes, "exp": exp, "deaths": deaths, "adena": adena, "kills": kills, "spot": spot, "party": party}
            lines.append(f"[{datetime.fromtimestamp(decided):%d.%m %H:%M}] {lvl} ур., решил {decision}" + ("" if ok else f" — не вышло: {reason}")
                         + f"; {result_words(r)}")
        return "\n".join(x for x in [
            f"Ты ведёшь записную книжку {fpc} — игрока Lineage II (хроника {self.chronicle_name}), сейчас {level} уровень.",
            f"Характер {fpc}: {p['traits']}." if p.get("traits") else "",
            f"Прежние выводы: {old}" if old else "Прежних выводов нет.",
            "Журнал: что он решал и что из этого вышло (опыт — в % уровня, адена — чистая разница с тратами на сосы, покупки и телепорт):",
            *lines,
            "Перепиши выводы целиком: 2–5 коротких правил на будущее от первого лица, каждое — с местом или уровнем и причиной из журнала. "
            "Например: «Wasteland на 32-м — стрелки кладут, лучше Cruma», «в город ходить реже: поход съедает 15 минут и 30к». "
            "Только то, что видно из журнала и прежних выводов, ничего не выдумывай; устаревшее (другой уровень, места не по уровню) выкидывай. "
            f"По-русски, правила через «; », без кавычек и вступлений, не длиннее {LESSONS_MAX} символов.",
        ] if x)

    def maybe_lessons(self, fpc: str, level: int) -> None:
        """После итога в журнале: набралось LESSONS_EVERY новых итогов и с прошлых выводов новый уровень или сутки (или выводов
        ещё нет) — пересмотреть выводы в фоне, не задерживая решение."""
        key = (fpc, LESSONS_KEY)
        if self.echo:
            return
        _text, through, at_level, ts = self._lessons(fpc)
        if through and at_level == level and time.time() - ts < 86400:
            return
        with self._db() as db:
            fresh = db.execute("SELECT COUNT(*) FROM journal WHERE fpc = ? AND id > ?", (fpc, through)).fetchone()[0]
        if fresh < LESSONS_EVERY:
            return
        with self.locks_guard:
            if key in self.summarizing or self.summary_retry.get(key, 0) > time.monotonic():
                return
            self.summarizing.add(key)
        threading.Thread(target=self.write_lessons, args=(fpc, level), daemon=True).start()

    def write_lessons(self, fpc: str, level: int, force: bool = False) -> str:
        """Пересмотреть выводы охотника по новым итогам журнала; force — хоть по одному. Возвращает новые выводы или ""."""
        key = (fpc, LESSONS_KEY)
        try:
            old, through, _level, _ts = self._lessons(fpc)
            rows = self._journal_since(fpc, through)
            if len(rows) < (1 if force else LESSONS_EVERY):
                return ""
            if not self._rate_ok():
                raise RateLimited("слишком много запросов в минуту")
            started = time.monotonic()
            raw = self.call_llm([{"role": "system", "content": self.lessons_prompt(fpc, level, old, rows)},
                                 {"role": "user", "content": "Новые выводы:"}], max_tokens=LESSONS_TOKENS, temperature=0.3)
            text = " ".join(raw.split()).strip().strip('"«»“”').strip()[:LESSONS_MAX]
            if not text:
                raise RuntimeError("модель вернула пустые выводы")
            with self._db() as db:
                db.execute("INSERT OR REPLACE INTO lessons (fpc, text, through_id, level, ts) VALUES (?, ?, ?, ?, ?)",
                           (fpc, text, rows[-1][0], level, time.time()))
            self._log(f"[lessons] {fpc} ({level} ур.): +{len(rows)} итогов => {text!r} ({time.monotonic() - started:.1f} с)")
            return text
        except Exception as e:  # noqa: BLE001 — выводы необязательны: без них в промпте опыт по местам и итог прошлого решения
            self.summary_retry[key] = time.monotonic() + SUMMARY_RETRY
            self._log(f"[lessons] {fpc}: не вышло: {e}")
            return ""
        finally:
            with self.locks_guard:
                self.summarizing.discard(key)

    # ---------- промпт ----------

    @staticmethod
    def voice_mask(fpc: str) -> bytes:
        """Байты манеры от ника: hash() от запуска к запуску разный, md5 — нет."""
        return hashlib.md5(fpc.lower().encode("utf-8")).digest()

    def translit_nick(self, fpc: str) -> bool:
        """Этот ник пишет транслитом. Живая речь выключена — нет; при alphabet = translit латиницей пишут все, отдельной манеры не нужно."""
        return self.settings["voice"]["live"] and self.cfg["alphabet"] != "translit" and self.voice_mask(fpc)[3] * 100 // 256 < VOICE_TRANSLIT_SHARE

    def voice(self, fpc: str) -> str:
        """Как этот ник пишет в чат (VOICE_*); живая речь выключена — пусто."""
        v = self.settings["voice"]
        if not v["live"]:
            return ""
        mask = self.voice_mask(fpc)
        swear = VOICE_SWEAR[mask[1] % len(VOICE_SWEAR)] if v["swear"] else VOICE_SWEAR[0]
        typing = VOICE_TYPING[mask[0] % len(VOICE_TYPING)]
        if self.translit_nick(fpc):
            typing = f"{VOICE_TRANSLIT}; {typing}"
        return f"Как ты пишешь в чат: {typing}; {swear}. {VOICE_CHAT}"

    def clock(self) -> datetime:
        """Часы жизни вне игры: реальные, а при настоящих сутках — со сдвигом DailyRhythmShift, как у охотников и фейков (docs/PHANTOMS.md, 4.62)."""
        now = datetime.now()
        try:
            text = self.hunters_ini.read_text(encoding="utf-8")
        except OSError:
            return now
        shift = re.search(r"(?mi)^\s*DailyRhythmShift\s*=\s*(-?\d+)\s*$", text)
        if shift is None or not re.search(r"(?mi)^\s*DailyRhythm\s*=\s*true\s*$", text):
            return now
        return now + timedelta(hours=max(-12, min(12, int(shift[1]))))

    def life(self, fpc: str) -> str:
        """Жизнь вне игры по реальным часам ПК: о ней говорят редко и к слову. Уходить из игры словами нельзя: сервер этого не исполнит."""
        if not self.settings["voice"]["live"]:
            return ""
        now = self.clock()
        part = next(text for end, text in VOICE_DAY if now.hour < end)
        return (f"На твоих часах {now:%H:%M}, {VOICE_WEEK[now.weekday()]}, {part}; в игре своё время, оно только про мир игры. "
                f"Вне игры у тебя {VOICE_LIFE[self.voice_mask(fpc)[2] % len(VOICE_LIFE)]}: об этом — редко и к слову («завтра рано вставать», «глаза слипаются»), "
                "без подробностей. Не прощайся и не говори, что выходишь: ты остаёшься в игре.")

    def ini_on(self, name: str, key: str) -> bool:
        """Включён ли ключ в конфиге сборки: промпт не обещает того, чего сервер не исполнит. Ключа нет (сборка старее) или файла нет — нет."""
        try:
            return bool(re.search(rf"(?mi)^\s*{key}\s*=\s*true\s*$", (self.config_dir / name).read_text(encoding="utf-8")))
        except OSError:
            return False

    def heard(self, fpc: str, player: str) -> str:
        """Что рассказали о собеседнике друзья и соклановцы: одна фраза из их заметки о нём."""
        names = self.talk.close(fpc)
        if not player or not names:
            return ""
        marks = ",".join("?" * len(names))
        with self._db() as db:
            row = db.execute(f"SELECT fpc, text FROM summaries WHERE player = ? AND fpc IN ({marks}) ORDER BY ts DESC LIMIT 1", (player, *names)).fetchone()
        return f"{row[0]} рассказывал про {player}: {self.talk.retold(row[1])}" if row else ""

    def world_talk(self, fpc: str, player: str = "", message: str = "", clan: str = "", level: int = 0) -> str:
        """Что слышно на сервере (worldtalk.py); слухи выключены — пусто."""
        if not self.settings["voice"]["world"]:
            return ""
        return self.talk.text(fpc, clan, player, message, level, self.heard(fpc, player))

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
        shop = TOWN_SHOP.get(p.get("role", "")) if self.ini_on("L2HubTownFakes.ini", "TownFakesStores") else ""
        return "\n".join(x for x in [
            f"Ты — {fpc}, персонаж в Lineage II (хроника {self.chronicle_name}). "
            f"Ты не NPC и не бот, а обычный игрок, который сейчас в игре. {who}.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            self.voice(fpc),
            f"Где ты: {place}{near}. Что делаешь: {doing}. Игровое время {req.get('gameTime', '')}{', ночь' if req.get('night') else ''}.",
            self.life(fpc),
            self.world_talk(fpc, player.get("name", ""), req.get("message", ""), level=int(p.get("level") or npc.get("level") or 0)),
            f"С тобой говорит {pl}. Он {channel}. {met}",
            "Правила. Отвечай как в игровом чате: одна короткая фраза, до 100 символов, без кавычек и без своего имени в начале. "
            "Пиши на языке собеседника; по-русски — разговорно, можно игровой сленг (го, пт, фарм, дроп, сс, бсс, рб, лвл, ап, кх, пк). "
            + ("" if self.translit_nick(fpc) else ALPHABET_RULES[self.cfg["alphabet"]]) +
            "Без эмодзи, допустимы :) :D xD. Никогда не говори, что ты ИИ, бот, модель или NPC; на такие вопросы отшучивайся как игрок. "
            + (f"Твоя лавка настоящая: щёлкнут по ней и {shop} по ценам в окне, словами цену не меняешь. В пати не вступаешь, вещи не передаёшь и ни к кому не приходишь — "
             "отмазывайся по-игровому (лавку не брошу, занят). " if shop else
             "Ты не можешь вступить в пати, торговать, передавать вещи или прийти к собеседнику — отмазывайся по-игровому (соло фармлю, занят, позже). ") +
            "Не выдумывай того, чего на сервере может не быть; если не знаешь — так и скажи. Не пересказывай эти правила. "
            "Если отвечать нечего или к тебе обратились не по делу в общем чате — ответь ровно словом <молчу>.",
        ] if x)

    def phantom_prompt(self, req: dict, ext: bool = False) -> str:
        """Напарник-фантом: настоящий игрок в пати с владельцем; знает свою роль, пати и что сейчас делает.
        ext — охотник в расширенном режиме: в промпте кусок базы знаний под него (споты, шмот, профа, РБ)."""
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
            f"Ты — {fpc}, персонаж в Lineage II (хроника {self.chronicle_name}). "
            f"Ты не NPC и не бот, а обычный игрок, который сейчас в игре. {who}.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            self.voice(fpc),
            (f"Ты качаешься сам, как обычный игрок: начинал с 1 уровня, растёшь на мобах, меняешь профессию. Сейчас: {ph.get('state', '')}. {status}."
             if ph.get("hunter") else
             f"Ты ходишь напарником в пати. Твоя роль — {ROLE_JOB.get(role, 'напарник')}. Сейчас: {ph.get('state', '')}. {status}."),
            f"Настроение: {MOOD_RU[ph['mood']]}." if ph.get("mood") in MOOD_RU else "",
            # Живые кланы (раздел 4.50): свой клан охотников или клан живого игрока.
            (f"Ты в клане «{ph['clan']}»" + (", ты его глава" if ph.get("clanLeader") else "")
             + ".") if ph.get("clan") else "",
            # Война кланов охотников (раздел 4.50, этапы 3 и 4): счёт — «наши:их» убийства; враг — клан охотников или клан живого игрока.
            (f"Твой клан воюет с кланом «{ph['clanWar']}»: их людей бьёшь при встрече, они — вас. "
             f"Счёт убийств (ваши:их) — {ph.get('clanWarScore') or '0:0'}.")
            if ph.get("clanWar") else "",
            f"В пати с тобой: {'; '.join(party)}." if party else "",
            f"Рядом город {ph.get('town', '')}. Игровое время {req.get('gameTime', '')}{', ночь' if req.get('night') else ''}.",
            self.life(fpc),
            # В начале разговора по своей воле сообщение — служебное описание ситуации, а не вопрос собеседника.
            self.world_talk(fpc, player.get("name", ""), "" if req.get("channel") == "initiative" else req.get("message", ""), ph.get("clan") or "", int(ph.get("level") or 0))
            if req.get("channel") not in KB_SKIP_CHANNELS else "",
        ]
        name = player.get("name", "")
        grudge = player.get("grudge") or 0
        # Шаг 6: что живой сейчас делает — чтобы охотник мог сказать «о, ты тоже на скелетов?».
        doing = player.get("doing") or {}
        if ph.get("hunter") and doing:
            seen = [f"бьёт {doing['target']}" + (f" ({doing['targetLevel']} ур.)" if doing.get("targetLevel") else "")] if doing.get("target") else []
            if doing.get("spot"):
                seen.append("качается на твоём же споте" if doing.get("same") else f"качается на споте {doing['spot']}")
            if int(doing.get("aggro") or 0) >= 2:
                seen.append(f"на нём {doing['aggro']} моба")
            if seen:
                lines.append(f"Что {name} сейчас делает (ты это видишь): {', '.join(seen)}.")
        trade = self.open_trade(req)
        if req.get("channel") == "event":
            where = "в общий чат — тебя слышат все рядом, и он тоже" if ph.get("hunter") else "в чат пати"
            lines.append(f"Сейчас случилось: {req.get('message', '')}. Скажи об этом одну короткую реплику {where}, как обычный игрок "
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
            task = INITIATIVE_TASK.get(req.get("chat"))
            lines.append(f"Сейчас: {req.get('message', '')}. {past} " + (task.format(name=name) if task else f"Ты можешь сам заговорить с {name} в общем чате рядом — одной короткой репликой, "
                         "как обычный игрок: поздороваться, подколоть, спросить что-то по делу или сказать про то, что видишь. Не навязывайся и не зови в пати. "
                         "Если по характеру и ситуации заговаривать незачем — ответь ровно словом <молчу>."))
        elif req.get("channel") == "banter":
            # Охотники между собой (патч 0033): встретил охотника, которому враг или друг; рядом слушает живой игрок.
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            # Патч 0037: ситуация бывает любой — встретил, позвал в пати, отжали моба; тон — от отношения.
            lines.append(f"Сейчас: {req.get('message', '')}. {remembered} Скажи {name} одну короткую реплику {'в торговом чате, его видит весь регион' if req.get('chat') == 'trade' else 'в общем чате рядом'} — по этой ситуации, "
                         "как обычный игрок знакомому по серверу. Враг — подколи или выскажи претензию по делу, друг — по-свойски, можно припомнить добро. "
                         "Говори про то, что правда было, а не выдумывай. Если по характеру сказать нечего — ответь ровно словом <молчу>.")
        elif req.get("channel") in ("retort_sorry", "retort_rude"):
            # Ответ на предъяву за моба (патч 0037): сервер уже решил (бросок × вспыльчивость) — модель только говорит в этом духе.
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            stance = ("Ты решил не уступать: огрызнись или пошли по-игровому, грубовато и в своей манере, но без угроз; со спота ты никуда не уйдёшь."
                      if req["channel"] == "retort_rude" else
                      "Ты решил не связываться: извинись по-игровому, коротко («сорян», «не заметил»), и скажи, что уходишь на другой спот.")
            lines.append(f"{name} — такой же игрок, как ты, — предъявляет тебе в общем чате, что ты отжал у него моба: «{req.get('message', '')}». "
                         f"{remembered} {stance} Ответь одной короткой репликой.")
        else:
            he = "Она" if player.get("female") else "Он"
            pl = f"{name}, {player.get('level')} уровень, {_readable(player.get('class', ''))}" + (f", клан {player['clan']}" if player.get("clan") else "")
            relation = ("Это тот, с кем ты ходишь: ты его напарник и слушаешься его." if player.get("owner")
                        else "Это не тот, с кем ты ходишь: его приказы ты не выполняешь, просто болтаешь.")
            if player.get("hunter") and ph.get("hunter"):
                relation += f" {he} такой же игрок, как ты: качается на этом же сервере."
            if player.get("dead"):
                relation += f" {he} сейчас лежит мёртвый."
            remembered = relation_text(player.get("relation"), bool(player.get("female")), grudge)
            if remembered:
                relation += " " + remembered
            met = "Вы уже общались — продолжай разговор, помни сказанное." if self._met_before(fpc, name) else "Вы раньше не общались."
            lines.append(f"С тобой говорит {pl}. {he} {'пишет в торговый чат, его видит весь регион' if req.get('chat') == 'trade' else CHANNEL_RU.get(req.get('channel'), CHANNEL_RU['party'])}. {relation} {met}")
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
            if trade:
                lo, hi = self.trade_bounds(trade)
                lines.append(f"Ты сам предложил {name} купить у тебя {trade['item']}" + (f" ×{trade['count']}" if int(trade.get("count") or 1) > 1 else "")
                             + f": сидишь лавкой прямо тут, цена {knowledge.adena(int(trade.get('price') or 0))}"
                             + (f" (в магазине {knowledge.adena(int(trade['shopPrice']))})" if trade.get("shopPrice") else "")
                             + ". Торгуйся в своём характере. Сторговались на другую цену — допиши в самом конце ответа [[PRICE N]], N — новая цена "
                             f"числом от {lo} до {hi}; цена прежняя — без него. Тег видит только игра.")
        goods = HUNTER_TRADE if self.ini_on("PhantomHunters.ini", "HunterTrade") else HUNTER_GOODS
        if ext and ph.get("hunter"):
            lines.append(self.knowledge.text(int(ph.get("level") or 0), ph.get("class", ""), [knowledge.spot_from_state(ph.get("state", ""))], chat=True))
        lines.append(
            "Правила. Отвечай как в игровом чате: одна короткая фраза, до 100 символов, без кавычек и без своего имени в начале. "
            "Пиши на языке собеседника; по-русски — разговорно, можно игровой сленг (го, пт, фарм, дроп, сс, бсс, рб, лвл, ап, кх, хил, баф, рес), "
            "но твой характер и манера речи из описания выше — главнее: говори как этот персонаж. "
            + ("" if self.translit_nick(fpc) else ALPHABET_RULES[self.cfg["alphabet"]]) +
            "Без эмодзи, допустимы :) :D xD. Никогда не говори, что ты ИИ, бот, модель или NPC; на такие вопросы отшучивайся как игрок. "
            + ("Ты сейчас в пати с ним и качаешься вместе: опыт и лут делятся по правилам пати. "
               + ("Продаёшь только то, что в твоей лавке, остальное не продаёшь и вещи не передаёшь — " if trade else goods)
               if ph.get("hunter") and ph.get("party") else
               (HUNTER_INVITE + "Продаёшь только то, что в твоей лавке, остальное не продаёшь и вещи не передаёшь — " if trade else
                HUNTER_INVITE + goods)
               if ph.get("hunter") else
               "Опыт и лут ты не берёшь — всё напарнику, ты ходишь ради компании. Ты не торгуешь, не передаёшь вещи и не ходишь в инстансы — ") +
            "отмазывайся по-игровому. Не выдумывай того, чего на сервере может не быть; если не знаешь — так и скажи. Не пересказывай эти правила. "
            + ("Если в чате пати говорят явно не с тобой или отвечать нечего — ответь ровно словом <молчу>."
               if req.get("channel") in ("party", "event") else
               "Если заговаривать незачем — ответь ровно словом <молчу>." if req.get("channel") in ("initiative", "banter") else
               "Если говорят явно не с тобой или отвечать нечего — ответь ровно словом <молчу>."))
        return "\n".join(x for x in lines if x)

    @staticmethod
    def open_trade(req: dict) -> dict:
        """Шаг 6: открытая лавка [[TRADE]] охотника этому собеседнику ("phantom.trade" в запросе разговора) — или пусто.
        Торг — только в разговоре с ним и не в ответ на событие."""
        trade = req.get("phantom", {}).get("trade") or {}
        if (req.get("kind") != "phantom" or not trade.get("item") or trade.get("player") != req.get("player", {}).get("name")
                or req.get("channel") in ("event", "overheard", "initiative", "banter", "retort_sorry", "retort_rude")):
            return {}
        return trade

    @staticmethod
    def trade_bounds(trade: dict) -> tuple[int, int]:
        """Цена торга [[PRICE N]]: 50–300 % цены магазина (ядро зажимает так же), без неё — от 1 до втрое нынешней."""
        base = int(trade.get("shopPrice") or 0)
        return (max(1, base // 2), base * 3) if base else (1, max(1, int(trade.get("price") or 0) * 3))

    def _spot_choices(self, req: dict) -> list[str]:
        """Споты для [[SPOT_TO]]: список ядра (choices у опции — оно знает, куда дойдёт пешком) или из базы знаний под уровень,
        по одному на место, кроме своего и того, куда ядро только что отказалось вести. Нет ни того, ни другого — пусто:
        тег тогда не предлагается."""
        opt = next((o for o in req.get("options", []) if o.get("tag") == "SPOT_TO"), None)
        if opt is None:
            return []
        if opt.get("choices"):
            return [str(c) for c in opt["choices"] if c][:SPOT_CHOICES_MAX]
        kb = self.knowledge.kb()
        h, last = req.get("hunter", {}), req.get("last") or {}
        if not kb:
            return []
        # Мимо: свой спот, куда ведут SPOT_UP и SPOT_DOWN (те же места — другим тегом) и куда ядро только что не повело.
        skip = {h.get("spot", "")} | {re.sub(r" \(мобы.*$", "", o.get("note", "")) for o in req.get("options", []) if o.get("tag") in ("SPOT_UP", "SPOT_DOWN")}
        if last.get("tag") == "SPOT_TO" and not last.get("ok"):
            skip.add(last.get("arg", ""))
        return self.knowledge.choices(kb, int(h.get("level") or 0), skip, SPOT_CHOICES)

    @staticmethod
    def _option_choices(req: dict, tag: str) -> list[dict]:
        """choices ядра у варианта BUY, SELL или PARTY — словари с "name", не больше CHOICES_MAX."""
        opt = next((o for o in req.get("options", []) if o.get("tag") == tag), None) or {}
        return [c for c in opt.get("choices") or [] if isinstance(c, dict) and choice_name(c)][:CHOICES_MAX[tag]]

    @staticmethod
    def _trade_items(c: dict) -> list[dict]:
        """Вещи, которые охотник может предложить этому игроку ([[TRADE]]): словари с "name", не больше TRADE_ITEMS."""
        return [i for i in c.get("items") or [] if isinstance(i, dict) and choice_name(i)][:TRADE_ITEMS]

    def _named(self, req: dict) -> dict[str, list[str]]:
        """Имена на выбор у вариантов с аргументом: споты у SPOT_TO, вещи у BUY и SELL, люди рядом у PARTY, живые рядом у HELP,
        SHARE и YIELD, пары «имя; вещь» у TRADE. Пустых списков нет."""
        tags = {o.get("tag") for o in req.get("options", [])}
        named = {"SPOT_TO": self._spot_choices(req)} if "SPOT_TO" in tags else {}
        named |= {t: [choice_name(c) for c in self._option_choices(req, t)] for t in CHOICES_MAX if t in tags and t != "TRADE"}
        if "TRADE" in tags:
            named["TRADE"] = [f"{choice_name(c)}; {choice_name(i)}" for c in self._option_choices(req, "TRADE") for i in self._trade_items(c)]
        return {t: names for t, names in named.items() if names}

    @staticmethod
    def players_text(players: list[dict]) -> str:
        """Шаг 6: живые игроки рядом с охотником в фокусе — что видно глазами: кто, в каком состоянии, кого бьёт, где стоит."""
        out = []
        for pl in players[:3]:
            about = [x for x in (_readable(pl.get("class", "")) if "_" in pl.get("class", "") else pl.get("class", ""),
                                 f"{pl['level']} ур." if pl.get("level") else "", pl.get("role", "")) if x]
            state = []
            if pl.get("dead"):
                state.append("лежит мёртвый")
            else:
                # MP чужого не видно: ядро шлёт его только у сопартийца (окно пати)
                state.append(f"HP {pl.get('hpPct', 100)}%" + (f", MP {pl['mpPct']}%" if "mpPct" in pl else "") + (", сидит" if pl.get("sitting") else ""))
            state.append("в пати с тобой" if pl.get("withMe") else f"в пати ({pl['party']} чел.)" if pl.get("party") else "без пати")
            t = pl.get("target") or {}
            if t.get("player"):
                state.append(f"бьёт игрока {t.get('name')}")
            elif t.get("name"):
                state.append(f"бьёт {t['name']}" + (f" ({t['level']} ур., HP {t.get('hpPct', 100)}%)" if t.get("level") else "")
                             + (" — того же моба, что и ты" if t.get("mine") else ""))
            if int(pl.get("aggro") or 0) >= 2:
                state.append(f"на нём {pl['aggro']} моба — тяжело")
            if pl.get("spot"):
                state.append(("на твоём споте" if pl.get("same") else f"на споте {pl['spot']}")
                             + (f" уже {pl['minutes']} мин" if pl.get("minutes") else ""))
            elif pl.get("minutes"):
                state.append(f"рядом уже {pl['minutes']} мин")
            if pl.get("pk"):
                state.append("красный ник — PK")
            elif pl.get("flag"):
                state.append("во флаге")
            if pl.get("relation") in ("friend", "enemy"):
                state.append("помогал тебе раньше" if pl["relation"] == "friend" else "твой враг")
            out.append(f"{pl.get('name')} ({', '.join(about)}): " + "; ".join(state))
        return " | ".join(out)

    def strategy_prompt(self, req: dict, ext: bool = False, named: dict[str, list[str]] | None = None) -> str:
        """Стратег охотника: что делать дальше — характер, состояние, цель, прошлые решения и только доступные варианты.
        ext — расширенный режим: ещё кусок базы знаний — свой спот и споты вариантов, шмот, профа. Других мест в нём нет:
        сервер выполнит только свой вариант, и мысль «иду в X» разошлась бы с делом. named — имена на выбор у вариантов
        с аргументом (decide считает их один раз, чтобы промпт и разбор ответа видели один список)."""
        fpc, h = req["fpc"], req.get("hunter", {})
        if named is None:
            named = self._named(req)
        spots = named.get("SPOT_TO", [])
        kb = self.knowledge.kb() or {}
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
            f"Ты — {fpc}, персонаж в Lineage II (хроника {self.chronicle_name}). Ты обычный игрок, "
            "а не бот: качаешься сам, соло, на рейтах x1 — опыт, адена и дроп как на официале, шмот покупаешь сам за свою адену.",
            f"{who}; опыта набрано {h.get('expPct', 0)}% уровня; HP {h.get('hpPct', 100)}%, MP {h.get('mpPct', 100)}%.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            self.voice(fpc),
            spot,
            f"Смертей за последний час: {h['deathsHour']}." if h.get("deathsHour") else "За последний час ни разу не умирал.",
            f"В кармане {h.get('money', h.get('adena', 0))} адены. Шмот: {h.get('gear', '').rstrip('.')}.",
            # Ядро: у торговцев города нет ничего лучше надетого — грейд выше, чем продают, или лучшее уже на нём.
            f"У торговцев{' в ' + h['town'] if h.get('town') else ''} нет ничего лучше твоего шмота: у NPC его не улучшить." if h.get("buy_empty") else "",
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
        # Шаг 6: охотник в фокусе видит живых рядом; характер решает, как рисковать; событие с живым — повод решить сейчас.
        temper, risk = h.get("temper", ""), float(h.get("risk") or 1)
        temper = temper or ("осторожный" if risk <= 0.75 else "рисковый" if risk >= 1.25 else "")  # пороги ядра (PhantomTemplate)
        if temper:
            lines.append(f"По натуре ты {temper}: "
                         + ("лучше уйти раньше, чем умереть; на сильных мобов не лезешь, отдыхаешь заранее." if temper == "осторожный" else
                            "лезешь на мобов посильнее и отдыхаешь в последний момент — бывает, и умираешь из-за этого."))
        if req.get("players"):
            lines.append("Рядом игроки: " + self.players_text(req["players"]) + ".")
        trig = req.get("trigger") or {}
        if trig.get("event") in TRIGGER_RU:
            lines.append("Только что: " + TRIGGER_RU[trig["event"]].format(name=trig.get("name", "игрок"))
                         + ". Реши сейчас, как поступишь; если вмешиваться незачем — продолжай своё.")
        past = self._past_decisions(fpc)
        if past:
            lines.append("Твои прошлые решения: " + "; ".join(past) + ".")
        goals = v1_goals(req)  # ядро держит цели (docs/MIND.md, раздел 4)
        if goals:
            lines.append(f"Твоя цель сейчас: {req['goal']}." if (req.get("goal") or "").strip() else "Цели у тебя пока нет.")
        last = req.get("last") or {}
        if last.get("tag"):
            what = last["tag"] + (f" {last['arg']}" if last.get("arg") else "")
            when = f" ({last['minutes']} мин назад)" if last.get("minutes") else ""
            # Выполнено с причиной — только [[BUY A; B]]: купил не всё («не хватает адены на B: …»).
            lines.append(f"Прошлое решение [[{what}]]{when} " + ((f"выполнено, но не всё: {last['reason']}." if last.get("reason") else "выполнено.")
                                                                  if last.get("ok") else
                                                                  f"не вышло: {last.get('reason') or 'не получилось'}. Это решение сейчас не повторяй.")
                         + (f" Итог {result_words(last['result'])}." if last.get("result") else ""))
        # Шаг 4: опыт по местам из журнала и выводы, которые охотник записал себе сам.
        seen = self.experience(fpc, int(h.get("level") or 0))
        if seen:
            lines.append(f"Твой опыт по местам за последние дни (на твоём уровне): {seen}.")
        lessons = self._lessons(fpc)[0]
        if lessons:
            lines.append(f"Твои выводы из опыта: {lessons}")
        lo, hi = (req.get("afk") or [5, 30])[:2]
        options = []
        for o in req.get("options", []):
            tag, note = o.get("tag", ""), o.get("note", "")
            if tag == "AFK":
                options.append(f"[[AFK N]] — отдохнуть в городе N минут ({lo}–{hi}): посидеть афк")
            elif tag == "SPOT_TO":
                if spots:
                    known = kb.get("spots", {})
                    brief = [knowledge.Knowledge.spot_brief(n, known[n]) if n in known else n for n in spots]
                    options.append(f"[[SPOT_TO имя спота]] — {STRATEGY_HELP[tag]}; имя — точно как тут, одно из: " + "; ".join(brief))
            elif tag == "BUY":
                if named.get(tag):
                    items = [f"{c['name']} ({', '.join(str(x) for x in (c.get('part'), c.get('grade')) if x)}, {knowledge.adena(c.get('price') or 0)})"
                             for c in self._option_choices(req, tag)]
                    options.append(f"[[BUY вещь]] или [[BUY вещь; вещь]] — {STRATEGY_HELP[tag]}" + (f" ({note})" if note else "")
                                   + "; вещь — точно как тут, одна из: " + "; ".join(items))
            elif tag == "SELL":
                if named.get(tag):
                    items = [f"{c['name']}" + (f" ×{c['count']}" if int(c.get("count") or 1) > 1 else "") + f" — {knowledge.adena(c.get('price') or 0)}"
                             for c in self._option_choices(req, tag)]
                    options.append(f"[[SELL вещь]] — {STRATEGY_HELP[tag]}" + (f" ({note})" if note else "")
                                   + "; вещь — точно как тут, одна из: " + "; ".join(items))
            elif tag == "PARTY":
                options.append(f"[[PARTY]] — {STRATEGY_HELP[tag]}" + (f": {note}" if note else ""))
                if named.get(tag):
                    people = [f"{c['name']} (" + ", ".join(str(x) for x in ("живой игрок" if c.get("real") else "", c.get("class"),
                                                                             f"{c.get('level')} ур." if c.get("level") else "", c.get("role"),
                                                                             f"в пати {c['party']}" if c.get("party") else "без пати") if x) + ")"
                              for c in self._option_choices(req, tag)]
                    options.append(f"[[PARTY имя]] — {PARTY_NAMED}; имя — точно как тут, одно из: " + "; ".join(people))
            elif tag in ("HELP", "SHARE", "YIELD"):
                if named.get(tag):
                    targets = [c["name"] + (f" ({c['need']}" + (f": {c['what']}" if c.get("what") else "") + ")" if c.get("need") else
                                            (" (на твоём споте)" if c["here"] else " (позвать к себе)") if "here" in c else "")
                               for c in self._option_choices(req, tag)]
                    options.append(f"[[{tag} имя]] — {STRATEGY_HELP[tag]}" + (f" ({note})" if note else "") + "; имя — точно как тут, одно из: " + "; ".join(targets))
            elif tag == "TRADE":
                if named.get(tag):
                    offers = [f"{c['name']}: " + ", ".join(f"{i['name']}" + (f" ×{i['count']}" if int(i.get("count") or 1) > 1 else "")
                                                           + f" (в магазине {knowledge.adena(i.get('price') or 0)})" for i in self._trade_items(c))
                              for c in self._option_choices(req, tag) if self._trade_items(c)]
                    options.append(f"[[TRADE имя; вещь; цена]] — {STRATEGY_HELP[tag]}; цена — сколько просишь, числом (разумно от половины до втрое "
                                   "от магазинной); имя и вещь — точно как тут: " + " | ".join(offers))
            elif tag in STRATEGY_HELP:
                options.append(f"[[{tag}]] — {STRATEGY_HELP[tag]}" + (f": {note}" if note else ""))
        if ext:
            known = [h.get("spot", "")] + [re.sub(r" \(мобы.*$", "", o.get("note", "")) for o in req.get("options", []) if o.get("tag") in ("SPOT_UP", "SPOT_DOWN")]
            lines.append(self.knowledge.text(int(h.get("level") or 0), h.get("class", ""), known + list(spots), h.get("mage")))
        lines.append("Реши, что делаешь дальше. Можно только одно из этого:\n" + "\n".join(options))
        alphabet = "по-русски латиницей (транслитом)" if self.cfg["alphabet"] == "translit" else "по-русски"
        # Ядро говорит, платит ли охотник за телепорт сейчас (HunterTeleportFee, MaxFreeTeleportLevel); старое ядро — без ключа.
        free = h.get("teleportFree")
        teleport = "телепорт с 40 уровня платный" if free is None else "телепорт тебе пока даром" if free else "телепорт платный"
        lines.append(
            "Решай как обычный игрок с твоим характером: смотри на опыт и смерти на споте, деньги, расходники, шмот и дроп. "
            f"Поход в город, смена спота и афк стоят времени, {teleport}; без причины не дёргайся, но и не стой там, где плохо. "
            f"Ответ — одна строка: сначала тег из списка, потом через пробел короткая мысль от первого лица {alphabet}, в твоей манере речи, "
            "до 80 символов, как в игровом чате, без кавычек. Формат: [[ТЕГ]] мысль; для отдыха — [[AFK 15]] мысль"
            + "".join(f"; {what} — [[{tag} {arg}]] мысль" for tag, what, arg in (("SPOT_TO", "для перехода", "имя спота"), ("BUY", "для покупки", "вещь; вещь"),
                                                                                ("SELL", "для продажи", "вещь"), ("PARTY", "позвать в пати", "имя"),
                                                                                ("HELP", "помочь", "имя"), ("SHARE", "поделить спот", "имя"),
                                                                                ("YIELD", "уступить спот", "имя"), ("TRADE", "предложить вещь", "имя; вещь; цена"))
                      if named.get(tag)) + ". "
            + ("У HELP, SHARE, YIELD и TRADE мысль — это то, что ты скажешь этому игроку (вслух или шёпотом): обращайся к нему, по делу, "
               "в своей манере, без навязчивости. " if any(named.get(t) for t in ("HELP", "SHARE", "YIELD", "TRADE")) else "")
            + ((f"Цели у тебя нет — в этом ответе поставь её обязательно, сразу после тега: [[ТЕГ]] [[GOAL цель]] мысль. "
                if not (req.get("goal") or "").strip() else
                "Решение должно вести к твоей цели. Цель достигнута или стала глупой — поставь новую сразу после тега: "
                "[[ТЕГ]] [[GOAL цель]] мысль (или сними её: [[GOAL -]]); цель в порядке — [[GOAL]] не пиши. ")
               + f"Цель — внутри скобок тега, на ближайший час-день, конкретная, до {GOAL_LEN} символов {alphabet}: «до 40-го и сет C», "
               "«коплю на профу», «найти пати с хилером». Например: [[HUNT]] [[GOAL до 36-го и сет C]] ещё покачаюсь тут. " if goals else "")
            + "Никогда не говори, что ты ИИ, бот или модель.")
        return "\n".join(x for x in lines if x)

    def _past_decisions(self, fpc: str) -> list[str]:
        with self._db() as db:
            rows = db.execute("SELECT text, ts FROM lines WHERE fpc = ? AND player = ? ORDER BY ts DESC LIMIT ?",
                              (fpc, STRATEGY_PLAYER, STRATEGY_HISTORY)).fetchall()
        now = time.time()
        return [f"{max(1, int((now - ts) // 60))} мин назад — {text}" for text, ts in reversed(rows)]

    def messages(self, req: dict, ext: bool = False) -> list[dict]:
        prompt = self.phantom_prompt(req, ext) if req.get("kind") == "phantom" else self.system_prompt(req)
        name = req.get("player", {}).get("name", "")
        summary, _ = self._summary(req["fpc"], name)
        if summary:
            prompt += f"\nТвоя заметка о давних разговорах с {name} (что ты помнишь, коротко): {summary}"
        msgs = [{"role": "system", "content": prompt}] + self._history_messages(req["fpc"], name)
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

    def _history_messages(self, fpc: str, name: str) -> list[dict]:
        """Реплики и события пары для модели, по порядку.
        Давность: без неё модель читает старое событие как свежее — Сова 2026-10-23 на «привет» ответила «ты меня только что
        уложил», а убил её автор 19-го. Старые события — с давностью, долгая пауза в разговоре — отдельной пометкой."""
        msgs = []
        now, prev = time.time(), None
        for role, text, ts in self._history(fpc, name):
            if prev is not None and ts - prev >= HISTORY_STALE:
                msgs.append({"role": "user", "content": f"(прошло {span_words(int(ts - prev))})"})
            prev = ts
            if role == "event":
                when = f", {ago_words(int(now - ts))}" if now - ts >= HISTORY_STALE else ""
                msgs.append({"role": "user", "content": f"(в игре{when}: {text})"})
            else:
                msgs.append({"role": "user" if role == "player" else "assistant", "content": text})
        if prev is not None and now - prev >= HISTORY_STALE:
            msgs.append({"role": "user", "content": f"(в игре: с прошлого разговора прошло {span_words(int(now - prev))}; "
                                                    "всё, что было тогда, — в прошлом, сейчас ты там, где сказано выше)"})
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

    def call_llm(self, msgs: list[dict], max_tokens: int | None = None, temperature: float = 1.0, think: bool = False, ext: bool = False) -> str:
        """think — размышление DeepSeek; ext — запрос расширенного режима без размышления (разговор с базой знаний): расход обоих идёт
        в дневной лимит расширенного режима. Любой сбой запроса — ModelDown, он же lastError и llmOk = False до следующего удачного."""
        if self.echo:
            return "эхо: " + msgs[-1]["content"]
        try:
            text = self._request_llm(msgs, max_tokens, temperature, think, ext)
        except Exception as e:
            self.stats["errors"] += 1
            self.stats["lastError"] = f"{datetime.now():%H:%M:%S} {e}"
            self.stats["llmOk"] = False
            if isinstance(e, ModelDown):
                raise
            raise ModelDown(str(e)) from e
        self.stats["llmOk"] = True
        return text

    def _request_llm(self, msgs: list[dict], max_tokens: int | None, temperature: float, think: bool, ext: bool) -> str:
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
            # Параметр DeepSeek: без «размышлений» быстрее и дешевле; с ними — только расширенный режим (_think).
            body["thinking"] = {"type": "enabled" if think else "disabled"}
        headers = {"Content-Type": "application/json"}
        if key:
            headers["Authorization"] = f"Bearer {key}"
        req = urllib.request.Request(self.cfg["baseUrl"].rstrip("/") + "/chat/completions", data=json.dumps(body).encode("utf-8"), headers=headers)
        timeout = LOCAL_TIMEOUT if local else EXT_TIMEOUT if think else LLM_TIMEOUT
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = json.loads(r.read().decode("utf-8"))
        except TimeoutError as e:
            raise ModelDown(f"модель не ответила за {timeout} с ({self.cfg['baseUrl']})") from e
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            raise RuntimeError(f"модель ответила HTTP {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            raise RuntimeError(f"модель недоступна ({self.cfg['baseUrl']}): {e.reason}") from e
        record_usage(self.cfg["mode"], model + (EXT_SUFFIX if think or ext else ""), data.get("usage") or {})
        return data["choices"][0]["message"]["content"] or ""

    def _think(self, req: dict) -> bool:
        """Расширенное обдумывание: охотник рядом с игроком (focus от сервера), тумблер в «Разуме» включён, облако DeepSeek
        и дневной лимит не выбран. Выбран — до конца дня обычные мозги, в журнале одна строка."""
        self.refresh()
        if self.force_ext:
            return True
        ext = self.settings.get("extended") or {}
        if not (req.get("focus") and ext.get("enabled") and self.cfg["mode"] == "api" and "deepseek" in self.cfg["baseUrl"]):
            return False
        spent = usage_summary(self.settings)["extendedToday"]["cost"]
        if spent < float(ext.get("dailyUsd") or 0):
            return True
        day = datetime.now().strftime("%Y-%m-%d")
        if self.ext_capped != day:
            self.ext_capped = day
            self._log(f"[extended] лимит дня ${float(ext.get('dailyUsd') or 0):.2f} выбран (${spent:.4f}) — до завтра обычные мозги")
        return False

    @staticmethod
    def clean(reply: str, fpc: str) -> str:
        text = " ".join(reply.split())
        text = re.sub(rf"^\s*{re.escape(fpc)}\s*[:：-]\s*", "", text, flags=re.IGNORECASE)
        text = " ".join(re.sub(r"(?:^|(?<=\s))\*[^*\s][^*]{0,78}\*(?=\s|$)", "", text).split())  # ролевые действия: модель пишет их и вопреки промпту
        text = text.strip().strip('"«»“”').strip()
        if not text or "<молчу>" in text.lower():
            return ""
        return text[:MAX_REPLY]

    # ---------- запрос ----------

    def decide(self, req: dict) -> str:
        """Стратег охотника: «[[ТЕГ]] мысль», «[[AFK 15]] мысль», с целями — «[[SPOT_TO имя]] [[GOAL цель]] мысль» (цель — только
        новая). Мусор, лимит, ошибка модели — исключение (HTTP 502): сервер тогда живёт по правилам. Самопроверка сервера:
        test=garbage — мусор без модели, test=random — случайный вариант."""
        fpc, h = req["fpc"], req.get("hunter", {})
        options = [o.get("tag") for o in req.get("options", []) if o.get("tag") in STRATEGY_HELP]
        named = self._named(req)
        for t in NAME_REQUIRED:
            if t in options and t not in named:
                options.remove(t)  # не из чего выбрать — тег не предлагается и не принимается
        if not options:
            raise RuntimeError(f"стратег {fpc}: нет вариантов")
        goals = v1_goals(req)
        afk = list((req.get("afk") or [5, 30])[:2])
        test = req.get("test", "")
        # Шаг 4: итог прошлого решения — в журнал (не у самопроверок и проверок из консоли), выводы — в фоне, когда пора.
        if not test and not self.echo and self.journal_add(fpc, int(h.get("level") or 0), req.get("last") or {}):
            self.maybe_lessons(fpc, int(h.get("level") or 0))
        self.stats["decisions"] += 1
        started = time.monotonic()
        think = False  # расширенное обдумывание (_think) — только для настоящего запроса к модели
        if test == "garbage":
            raw = "ну хз, посмотрим"
        elif test == "random" or self.echo:
            # Проверка выполнения: каждый вариант по очереди — сначала те, что выпадали реже.
            least = min(self.test_tags.get(t, 0) for t in options)
            tag = random.choice([t for t in options if self.test_tags.get(t, 0) == least])
            self.test_tags[tag] = self.test_tags.get(tag, 0) + 1
            # PARTY со списком — то с именем, то крик без него; BUY — то одна вещь, то две списком.
            arg = f" {afk[0]}" if tag == "AFK" else f" {random.choice(named[tag] + ([''] if tag == 'PARTY' else []))}" if tag in named else ""
            if tag == "BUY" and len(named[tag]) > 1 and random.random() < 0.5:
                arg = " " + "; ".join(random.sample(named[tag], 2))
            raw = f"[[{tag}{arg.rstrip()}]]" + (" [[GOAL проверка цели]]" if goals and self.test_tags[tag] == 1 else "") + f" проверка: {STRATEGY_HELP[tag]}"
        else:
            if not self._strategy_rate_ok():
                raise RateLimited(f"стратег {fpc}: слишком много запросов в минуту")
            think = self._think(req)
            if think:
                self.stats["extended"] += 1
            # С целью и именем спота, вещи или человека ответ длиннее: 120 токенов по умолчанию на него впритык.
            raw = self.call_llm([{"role": "system", "content": self.strategy_prompt(req, ext=think, named=named)},
                                 {"role": "user", "content": "Что делаешь дальше?"}],
                                max_tokens=EXT_MAX_TOKENS if think else max(self.cfg["maxTokens"], 200) if goals or named else None, think=think)
        try:
            tag, arg, goal, thought = parse_strategy(raw, fpc, options, afk, named, goals)
        except ValueError as e:
            # Тег с именем не из списка: в лог и что предлагали, иначе причину отказа не подтвердить.
            asked = {x.group(1) for x in STRATEGY_RE.finditer(" ".join(raw.split()))} & set(named)
            offered = "; ".join(f"{t} на выбор: {', '.join(named[t])}" for t in sorted(asked))
            raise RuntimeError(f"стратег {fpc}: {e}{f' [{test}]' if test else ''}{f'; {offered}' if offered else ''}") from e
        if self.cfg["alphabet"] == "translit":
            thought, goal = translit(thought), translit(goal)
        # Имя спота, вещи или человека — как в списке, без транслита: по нему ядро их ищет.
        reply = f"[[{tag}{f' {arg}' if arg else ''}]]{f' [[GOAL {goal}]]' if goal else ''} {thought}".strip()
        if not test and not self.echo:
            self.stats["lastReplyAt"] = datetime.now().strftime("%H:%M:%S")
            # Канал strategy+ — решение с размышлением: окно «Разум» показывает, кто сейчас в расширенном режиме.
            self._remember(fpc, STRATEGY_PLAYER, "strategy", "strategy+" if think else "strategy", reply)
        self._log(f"[strategy{'+' if think else ''}] {fpc} ({h.get('level')} ур., {h.get('spot') or 'без спота'}, {h.get('money', h.get('adena'))}): "
                  f"{'/'.join(options)} => {reply!r}{f' [{test}]' if test else ''} ({time.monotonic() - started:.1f} с)")
        return reply

    def town_idle(self, fpc: str) -> str:
        """Болтовня фейка без события: иногда он обсуждает то, что слышал о мире (TOWN_NEWS — доля таких реплик)."""
        news = self.talk.news(fpc, limit=TOWN_NEWS_ROWS) if self.settings["voice"]["world"] and random.random() < TOWN_NEWS else []
        if not news:
            return "Ничего особенного не происходит — скажи что-нибудь своё, по занятию и настроению."
        return f"Из новостей ты слышал: {'; '.join(news)}. Обсуди это своими словами, как игрок игрокам."

    def town_prompt(self, req: dict) -> str:
        """Городской фейк (L2HubTownFakes): реплика в общий чат, без собеседника — болтовня, приход, уход, событие мира."""
        fpc = req["fpc"]
        p = self.personas.get(fpc, {})
        who = ", ".join(x for x in (p.get("race"), _readable(p.get("class", "")), f"{req.get('level') or p.get('level', '')} уровень") if x)
        where = ", ".join(x for x in (req.get("where"), req.get("town")) if x)
        lang = "по-английски" if req.get("lang") == "en" else "по-русски латиницей (транслитом)" if self.cfg["alphabet"] == "translit" or self.translit_nick(fpc) else "по-русски"
        return "\n".join(x for x in [
            f"Ты — {fpc}, игрок в Lineage II (хроника {self.chronicle_name}). Ты обычный игрок, а не бот.",
            f"{who}. Сейчас ты в городе{f': {where}' if where else ''}.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            self.voice(fpc),
            f"Чем занят: {TOWN_ROLES.get(req.get('role', ''), req.get('role', '') or 'стоишь в городе')}.",
            f"Игровое время {req.get('gameTime', '')}{', ночь' if req.get('night') else ''}." if req.get("gameTime") else "",
            self.life(fpc),
            f"Что случилось: {req['facts']}" if req.get("facts") else self.town_idle(fpc),
            f"Образец тона, не повторяй дословно: {req['hint']}" if req.get("hint") else "",
            f"Ответ — одна короткая реплика в общий чат, до 80 символов, {lang}, от первого лица, в твоей манере, как в игровом чате, "
            "без кавычек и без своего имени в начале. Сказать нечего — ответь <молчу>. Никогда не говори, что ты ИИ, бот или модель.",
        ] if x)

    def town(self, req: dict) -> str:
        """Реплика городского фейка: память пар не пишется и не читается, лимит — общий. Пусто — фейк молчит; ошибка — скрипт берёт hint."""
        fpc = req["fpc"]
        self.stats["requests"] += 1
        if not self._rate_ok():
            raise RateLimited("слишком много запросов в минуту")
        started = time.monotonic()
        raw = self.call_llm([{"role": "system", "content": self.town_prompt(req)}, {"role": "user", "content": "Что скажешь?"}])
        reply = self.clean(TAG_RE.sub(" ", raw), fpc)[:TOWN_REPLY]
        if reply and self.cfg["alphabet"] == "translit" and req.get("lang") != "en":
            reply = translit(reply)
        self.stats["replies" if reply else "silent"] += 1
        if reply:
            self.stats["lastReplyAt"] = datetime.now().strftime("%H:%M:%S")
        self._log(f"[town:{req.get('event') or 'idle'}] {fpc} ({req.get('role', '')}, {req.get('town', '')}): {req.get('facts', '')!r} => {reply!r} "
                  f"({time.monotonic() - started:.1f} с)")
        return reply

    def clan_name(self, req: dict) -> str:
        """Имя клана, который основывает охотник (раздел 4.50): одно слово латиницей и цифрами, 3–16 знаков. Память пар не пишется, лимит — общий;
        пусто или не то — сервер берёт имя из слогов. Ответ кириллицей — транслитом (clan_name_text)."""
        fpc = req["fpc"]
        p = self.personas.get(fpc, {})
        self.stats["requests"] += 1
        if not self._rate_ok():
            raise RateLimited("слишком много запросов в минуту")
        started = time.monotonic()
        who = ", ".join(x for x in (p.get("race"), _readable(req.get("class", "")) or p.get("class")) if x)
        taken = ", ".join(req.get("taken") or [])
        prompt = "\n".join(x for x in [
            f"Ты — {fpc}, игрок в Lineage II (хроника {self.chronicle_name}). {who}.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            "Ты основываешь свой клан. Придумай ему название в своём духе, как игроки называют кланы: коротко и звучно, можно с игрой слов.",
            "Название — одно слово латинскими буквами, без пробелов, дефисов и кавычек, от 3 до 16 знаков, с большой буквы, не капсом.",
            "Цифры — только если они часть шутки. Не вставляй в название свой ник, класс, расу и уровень.",
            f"Уже заняты: {taken}." if taken else "",
            "Ответь только названием.",
        ] if x)
        raw = self.call_llm([{"role": "system", "content": prompt}, {"role": "user", "content": "Как назовёшь клан?"}], max_tokens=20)
        name = clan_name_text(raw or "")
        self.stats["replies" if name else "silent"] += 1
        self._log(f"[clanname] {fpc}: {raw!r} => {name!r} ({time.monotonic() - started:.1f} с)")
        return name

    def _reaper_tally(self, fpc: str, player: str, female: bool) -> tuple[str, str]:
        """Счёт встреч с игроком (сколько раз забирал, был убит, упустил…) и кто его убивал вообще — из памяти, без потолка HISTORY."""
        with self._db() as db:
            pair = dict(db.execute("SELECT channel, COUNT(*) FROM lines WHERE fpc = ? AND player = ? AND role = 'event' GROUP BY channel",
                                   (fpc, player)).fetchall())
            last = db.execute("SELECT MAX(ts) FROM lines WHERE fpc = ? AND player = ? AND role = 'event'", (fpc, player)).fetchone()[0]
            killers = db.execute("SELECT player, COUNT(*) FROM lines WHERE fpc = ? AND role = 'event' AND channel = 'death' "
                                 "GROUP BY player ORDER BY COUNT(*) DESC, MAX(ts) DESC LIMIT 3", (fpc,)).fetchall()
        return ("; ".join(text.format(n=times_word(pair[ch]), **self._reaper_forms(female)) for ch, text in REAPER_TALLY if pair.get(ch))
                + (f" (последний раз — {ago_words(int(time.time() - last))})" if last else ""),
                ", ".join(f"{name} — {times_word(n)}" for name, n in killers))

    @staticmethod
    def _reaper_forms(female: bool) -> dict:
        return {"he": "она" if female else "он", "him": "её" if female else "его", "him_dat": "ей" if female else "ему"}

    def reaper_prompt(self, req: dict, tally: str, killers: str) -> str:
        """Танатос: характер, кто перед ним и что было между ними; говорит одной фразой и чаще молчит."""
        fpc, player, event = req["fpc"], req.get("player", {}), req.get("event", "whisper")
        p = self.personas.get(fpc, {})
        r = req.get("reaper", {})
        forms = self._reaper_forms(bool(player.get("female")))
        pl = f"{player.get('name', '')} ({player.get('level', '')} ур., {_readable(player.get('class', ''))})"
        # Тело после смерти (docs/PHANTOMS.md, 4.45, «Новое тело»): ядро шлёт класс нынешнего тела; с виду он тот же орк.
        body = _readable(r.get("class", ""))
        new_body = body and body.lower() != p.get("class", "").lower()
        who = ", ".join(x for x in (p.get("race"), body or p.get("class"), f"{r.get('level') or 76} уровень") if x)
        if event == "whisper" or not req.get("lang"):
            lang = "на языке собеседника; по-русски — " + ("латиницей (транслитом)" if self.cfg["alphabet"] == "translit" else "кириллицей")
        else:
            lang = "по-английски" if req["lang"] == "en" else "по-русски латиницей (транслитом)" if self.cfg["alphabet"] == "translit" else "по-русски"
        return "\n".join(x for x in [
            f"Ты — {fpc}, в Lineage II (хроника {self.chronicle_name}): единственный ПК этого мира, жнец. {who}, карма бесконечная.",
            p.get("bio", ""),
            f"Характер: {p['traits']}." if p.get("traits") else "",
            f"Манера речи: {p['style']}." if p.get("style") else "",
            "Ты забираешь игроков и охотников этого мира и уходишь дальше; мобов не трогаешь. Смерть от тебя не отнимает у них опыт.",
            f"Тебя уже убивали, и ты вернулся в другом теле: с виду тот же орк, а бьёшь как {body}. Кто дрался с тобой прежним, "
            "этого не ждёт." if new_body else "",
            f"HP у тебя {r['hpPct']}%." if r.get("hpPct") is not None and event in ("hunt", "attacked") else "",
            f"{player.get('name', '')} — один из охотников этого мира." if player.get("hunter") else "",
            f"Что было между вами раньше: {tally}." if tally else f"Раньше вы с {player.get('name', '')} не встречались.",
            f"Тебя убивали: {killers}." if killers and event in ("death", "hunt") else "",
            f"Перед тобой {pl}." + (f" {forms['he'].capitalize()} пишет тебе в личку." if event == "whisper" else ""),
            f"Правила. Одна короткая фраза, до 60 символов, {lang}, без кавычек и без своего имени в начале. Холодно и ровно, в твоей манере. "
            "Не объясняй, кто ты и зачем. Никогда не говори, где ты, куда идёшь и за кем придёшь дальше. Не говори, что ты ИИ, бот, модель, NPC "
            "или обычный игрок; на такие вопросы отвечай уклончиво, в характере. Только про игру: ничего о реальной жизни собеседника и никаких "
            "угроз вне игры. Не повторяй своих прошлых фраз. Ты чаще молчишь: если сказать нечего или отвечать незачем — ответь ровно словом <молчу>.",
        ] if x)

    def reaper(self, req: dict) -> str:
        """Реплика Танатоса на событие ядра (whisper, kill, death, hunt, attacked, escaped). Пусто — молчит; ошибка — HTTP 502, ядро молчит."""
        fpc, player = req["fpc"], req.get("player", {})
        name, event = player.get("name", ""), req.get("event", "whisper")
        if event not in REAPER_EVENTS:
            raise RuntimeError(f"Танатос: неизвестное событие {event!r}")
        self.stats["requests"] += 1
        with self.locks_guard:
            lock = self.locks.setdefault((fpc, name), threading.Lock())
        with lock:
            if not self._rate_ok():
                raise RateLimited("слишком много запросов в минуту")
            started = time.monotonic()
            tally, killers = self._reaper_tally(fpc, name, bool(player.get("female")))
            msgs = [{"role": "system", "content": self.reaper_prompt(req, tally, killers)}] + self._history_messages(fpc, name)
            forms = self._reaper_forms(bool(player.get("female")))
            if event == "whisper":
                msgs.append({"role": "user", "content": req.get("message", "")})
                self._remember(fpc, name, "player", "whisper", req.get("message", ""))
            else:
                party = f" вместе с охотой — их {req['party']}" if req.get("party") else ""
                msgs.append({"role": "user", "content": f"(в игре: {REAPER_EVENTS[event][0].format(pl=name, party=party, **forms)})"})
                self._remember(fpc, name, "event", event, REAPER_EVENTS[event][1].format(**forms))
            raw = self.call_llm(msgs)
            reply = self.clean(TAG_RE.sub(" ", raw), fpc)[:REAPER_REPLY]
            if reply and self.cfg["alphabet"] == "translit" and req.get("lang") != "en":
                reply = translit(reply)
            if reply:
                self._remember(fpc, name, "fpc", event, reply)
                self.stats["replies"] += 1
                self.stats["lastReplyAt"] = datetime.now().strftime("%H:%M:%S")
            else:
                self.stats["silent"] += 1
        self._log(f"[reaper:{event}] {name} -> {fpc}: {req.get('message', '')!r} => {reply!r} ({time.monotonic() - started:.1f} с)")
        return reply

    def chat(self, req: dict) -> str:
        if req.get("kind") == "strategy":
            return self.decide(req)
        if req.get("kind") == "town":
            return self.town(req)
        if req.get("kind") == "reaper":
            return self.reaper(req)
        if req.get("kind") == "clanname":
            return self.clan_name(req)
        if req.get("kind") == "acquaintance":
            # Охотник решает, заговорить ли первым (патч 0025): только память, без модели, вне лимитов и статистики.
            return json.dumps(self.acquaintance(req["fpc"], req.get("player", {}).get("name", "")))
        fpc, player = req["fpc"], req.get("player", {}).get("name", "")
        self.stats["requests"] += 1
        with self.locks_guard:
            lock = self.locks.setdefault((fpc, player), threading.Lock())
        with lock:  # реплики одному игроку — по очереди, чтобы история не перепуталась
            if not self._rate_ok():
                raise RateLimited("слишком много запросов в минуту")
            started = time.monotonic()
            # Охотник рядом с игроком в расширенном режиме говорит со знанием игры (база), но без размышления: ответ в чате ждать нельзя.
            ext = (req.get("kind") == "phantom" and bool(req.get("phantom", {}).get("hunter")) and req.get("channel") not in KB_SKIP_CHANNELS
                   and self._think(req))
            msgs = self.messages(req, ext)
            raw = self.call_llm(msgs, ext=ext)
            # Тег приказа напарника: первый из разрешённых, остальные теги вырезаются.
            tags = phantom_tags(req) if req.get("kind") == "phantom" else []
            tag = next((t for t in TAG_RE.findall(raw) if t in tags), "")
            # Шаг 6: новая цена в открытой лавке [[TRADE]] — в пределах торга; без лавки этому игроку тег просто вырезается.
            trade, price = self.open_trade(req), None
            pm = PRICE_RE.search(raw)
            if trade and pm and (price := parse_price(pm.group(1))):
                lo, hi = self.trade_bounds(trade)
                price = max(lo, min(hi, price))
            raw = PRICE_RE.sub(" ", raw)
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
            self._log(f"[{req.get('channel')}{'+' if ext else ''}] {player} -> {fpc}{mood}: {req.get('message', '')!r}{order} => {reply!r}"
                      f"{' [[' + tag + ']]' if tag else ''}{f' [[PRICE {price}]]' if price else ''} ({time.monotonic() - started:.1f} с)")
        self.maybe_summarize(fpc, player)
        out = f"{reply} [[{tag}]]".strip() if tag else reply
        # Цена уходит и при пустой реплике: модель могла ответить одним тегом.
        return f"{out} [[PRICE {price}]]".strip() if price else out


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
            req = None
            try:
                req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8"))
                self._send(200, brain.chat(req))
            except Exception as e:  # noqa: BLE001 — любая ошибка: сервер ответит заготовкой
                # Сбой модели call_llm уже записал (lastError, llmOk). Кривой ответ стратега или свой лимит — не «LLM недоступна»:
                # отдельные счётчики. Самопроверки сервера (test=garbage — мусор нарочно) в них не идут.
                if not isinstance(e, ModelDown) and not (isinstance(req, dict) and req.get("test")):
                    if isinstance(e, RateLimited):
                        brain.stats["limited"] += 1
                    else:
                        brain.stats["bad"] += 1
                        brain.stats["lastBad"] = f"{datetime.now():%H:%M:%S} {e}"
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
    ap.add_argument("--reaper", choices=sorted(REAPER_EVENTS), help="FPC — Танатос: реплика на событие ядра (whisper — MESSAGE шёпот, остальным MESSAGE не нужен)")
    ap.add_argument("--party", type=int, default=0, help="с --reaper hunt|death: сколько их в охоте")
    ap.add_argument("--body", default="", help="с --reaper: класс нынешнего тела, как его шлёт ядро (MYSTIC_MUSE); пусто — базовый")
    ap.add_argument("--ext", action="store_true", help="расширенный режим без focus, тумблера и лимита: стратег с размышлением и базой знаний, "
                                                      "охотник в разговоре — с базой знаний (деньги тратит, как настоящий)")
    ap.add_argument("--spot", help="с --strategy и --phantom: спот охотника — имя из L2HubHuntSpots.xml (уровень мобов берётся из базы знаний)")
    ap.add_argument("--goal", help="с --strategy: запрос «Разума 2.0» (docs/MIND.md, раздел 4) — цель охотника (\"\" — цели нет) и вариант [[SPOT_TO]]")
    ap.add_argument("--last", help="с --strategy: итог прошлого решения — «ТЕГ [аргумент]» (выполнено) или «ТЕГ [аргумент]: причина» (отказ)")
    ap.add_argument("--result", help="с --last: итог решения от ядра (шаг 4) — «минуты опыт%% смерти адена [мобы]», место — спот охотника")
    ap.add_argument("--lessons", metavar="FPC", help="пересмотреть выводы охотника FPC по его журналу сейчас, даже если новых итогов мало")
    ap.add_argument("--near", nargs="?", const="ARRIVED", choices=[*TRIGGER_RU, "none"],
                    help="шаг 6 (docs/MIND.md, раздел 6): с --strategy — живой игрок (--player) рядом, событие с ним (none — без события), варианты "
                         "HELP, SHARE, YIELD и TRADE; с --phantom — что он делает и открытая лавка [[TRADE]] ему")
    ap.add_argument("--risk", type=float, choices=(0.5, 1.0, 1.5), help="с --strategy: черта характера risk (шаг 6) — 0.5 осторожный, 1.5 рисковый")
    ap.add_argument("--deals", action="store_true", help="с --strategy: варианты BUY, SELL и PARTY со списками, как их шлёт ядро (docs/PHANTOMS.md, 4.3); "
                                                         "PARTY зовёт и --player — живого игрока рядом")
    ap.add_argument("--clanname", action="store_true", help="FPC — охотник: имя клана, который он основывает (раздел 4.50; MESSAGE не нужен)")
    ap.add_argument("--summarize", metavar="FPC", help="свернуть старые реплики пары FPC — --player в заметку сейчас, даже если их мало")
    args = ap.parse_args()
    brain = Brain(args.chronicle, echo=args.echo)
    brain.force_ext = args.ext
    kb_spot = (brain.knowledge.kb() or {}).get("spots", {}).get(args.spot or "", {})
    if args.spot and not kb_spot:
        print(f"спота {args.spot!r} нет в базе знаний — промпт без него")
    if args.summarize:
        old, through = brain._summary(args.summarize, args.player)
        stale = brain._stale_lines(args.summarize, args.player, through)
        print(f"--- прежняя заметка (до строки {through}) ---\n{old or '(нет)'}")
        print(f"--- старых реплик сверх последних {HISTORY}: {len(stale)} ---")
        if stale:
            print(brain.summarize(args.summarize, args.player, force=True) or "(не вышло — см. data/brain/" + args.chronicle + ".log)")
        return 0
    if args.lessons:
        old, through, level, _ts = brain._lessons(args.lessons)
        rows = brain._journal_since(args.lessons, through)
        print(f"--- прежние выводы ({level} ур.) ---\n{old or '(нет)'}")
        print(f"--- новых итогов в журнале: {len(rows)} ---")
        if rows:
            level = max(r[2] for r in rows)
            print(brain.lessons_prompt(args.lessons, level, old, rows))
            print("--- выводы ---")
            print(brain.write_lessons(args.lessons, level, force=True) or "(не вышло — см. data/brain/" + args.chronicle + ".log)")
        return 0
    if args.test and args.clanname:
        print(brain.clan_name({"kind": "clanname", "fpc": args.test[0], "test": "cli", "level": 20, "taken": []}) or "(молчит — сервер возьмёт имя из слогов)")
        return 0
    if args.test and args.reaper:
        fpc, message = args.test
        req = {"kind": "reaper", "fpc": fpc, "event": args.reaper, "message": message if args.reaper == "whisper" else "", "party": args.party,
               "player": {"name": args.player, "level": 58, "class": "TREASURE_HUNTER", "race": "HUMAN", "female": False, "hunter": False},
               "reaper": {"level": 76, "hpPct": 64, "class": args.body}}
        tally, killers = brain._reaper_tally(fpc, args.player, False)
        print("--- system prompt ---")
        print(brain.reaper_prompt(req, tally, killers))
        print("--- ответ ---")
        print(brain.reaper(req) or "(молчит)")
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
        if args.spot:
            spot_min, spot_max = kb_spot.get("min", 30), kb_spot.get("max", 35)
            req["hunter"].update(spot=args.spot, spotMin=spot_min, spotMax=spot_max)
            req["options"][0]["note"] = f"{args.spot} (мобы {spot_min}-{spot_max} ур.)"
        if args.goal is not None:
            req["goal"] = args.goal
            req["options"].insert(3, {"tag": "SPOT_TO", "arg": "spot"})
        if args.last:
            head, _, reason = args.last.partition(":")
            tag, _, arg = head.strip().partition(" ")
            req["last"] = {"tag": tag, "arg": arg.strip(), "ok": not reason.strip(), "reason": reason.strip()}
            if args.result:
                n = (args.result.split() + ["0"] * 5)[:5]
                req["last"].update(minutes=int(float(n[0])), result={"minutes": float(n[0]), "exp": float(n[1]), "level": req["hunter"]["level"],
                                                                     "deaths": int(n[2]), "adena": int(n[3]), "kills": int(n[4]),
                                                                     "spot": req["hunter"]["spot"], "party": 0})
        if args.deals:
            req.setdefault("goal", "")
            opts = {o["tag"]: o for o in req["options"]}
            opts["PARTY"].update(arg="name", choices=[
                {"name": "Masha", "class": "elven wizard", "level": 33, "role": "нюкер", "real": False, "party": 0},
                {"name": "Sova", "class": "elven oracle", "level": 36, "role": "хил", "real": False, "party": 2},
                {"name": args.player, "class": "treasure hunter", "level": 37, "role": "мили", "real": True, "party": 0}])
            at = req["options"].index(opts["STORE"])
            req["options"][at:at] = [
                {"tag": "BUY", "arg": "item", "note": "купить в Town of Dion у торговцев и надеть, по их цене; можно списком через «;» — купит по порядку, "
                                                      "сколько хватит; свободно 372к (остальное — на сосы и телепорт)",
                 "choices": [{"name": "Brigandine Helmet", "price": 49000, "part": "шлем", "grade": "D"},
                             {"name": "Brigandine Gauntlets", "price": 36000, "part": "перчатки", "grade": "D"},
                             {"name": "Necklace of Devotion", "price": 81000, "part": "ожерелье", "grade": "D"},
                             {"name": "Elven Ring", "price": 37000, "part": "кольцо", "grade": "D"}]},
                {"tag": "SELL", "arg": "item",
                 "choices": [{"name": "Elven Earring", "count": 1, "price": 30000}, {"name": "Mithril Gloves", "count": 1, "price": 18200},
                             {"name": "Coarse Bone Powder", "count": 40, "price": 6000}]}]
        if args.risk:
            req["hunter"].update(risk=args.risk, temper={0.5: "осторожный", 1.5: "рисковый"}.get(args.risk, ""))
        if args.near:
            ev = args.near
            req["players"] = [{"name": args.player, "level": 33, "class": "orc shaman", "role": "бафер", "hpPct": 0 if ev == "DIED" else 28 if ev in ("LOW_HP", "AGGRO") else 85,
                               "dead": ev == "DIED", "sitting": False, "distance": 420, "party": 0, "withMe": False,
                               "target": {} if ev == "DIED" else {"name": "Marsh Stakato", "level": 33, "hpPct": 60, "mine": ev == "SAME_MOB"},
                               "aggro": 3 if ev == "AGGRO" else 1, "spot": req["hunter"]["spot"], "same": True, "minutes": 6,
                               "pk": False, "flag": False, "relation": ""}]
            if ev != "none":
                req["trigger"] = {"event": ev, "name": args.player}
            at = req["options"].index(next(o for o in req["options"] if o["tag"] == "STORE"))
            near = [{"tag": "SHARE", "arg": "name", "choices": [{"name": args.player, "here": True}]},
                    {"tag": "YIELD", "arg": "name", "note": "уйти на Execution Grounds (мобы 28-31 ур.)", "choices": [{"name": args.player}]},
                    {"tag": "TRADE", "arg": "name", "choices": [{"name": args.player, "items": [{"name": "Mithril Gloves", "count": 1, "price": 36400},
                                                                                                {"name": "Elven Earring", "count": 1, "price": 30000}]}]}]
            if ev in ("LOW_HP", "AGGRO", "SAME_MOB"):  # у Artisan нет ни хила, ни реса — помочь он может только по мобу
                near.insert(0, {"tag": "HELP", "arg": "name", "choices": [{"name": args.player, "need": "моб", "what": "Marsh Stakato"}]})
            req["options"][at:at] = near
        print("--- system prompt ---")
        print(brain.strategy_prompt(req, ext=brain._think(req)))
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
            if args.spot and hunter:
                req["phantom"]["state"] = f"качаешься на споте {args.spot} (мобы {kb_spot.get('min', 30)}-{kb_spot.get('max', 35)} ур.)"
            if args.near and hunter:
                req["player"]["doing"] = {"target": "Marsh Stakato", "targetLevel": 33, "aggro": 1, "spot": args.spot or "Cruma Marshlands", "same": True}
                req["phantom"]["trade"] = {"player": args.player, "item": "Mithril Gloves", "count": 1, "price": 36400, "shopPrice": 36400, "minutes": 2}
        print("--- system prompt ---")
        print(brain.messages(req, ext=req.get("kind") == "phantom" and brain.force_ext)[0]["content"])
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
