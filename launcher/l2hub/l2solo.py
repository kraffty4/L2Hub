"""L2Solo — эмулятор C4 (протокол 656) на Node.js, github.com/pmbstyle/L2Solo; профиль с "type": "l2solo".

Их код не патчим и в наш репозиторий не кладём: servers/<id> — git-клон, обновление — git pull и npm install
(установка, обновление и откат — docs/L2SOLO.md). Лаунчер запускает их обёртку scripts/run-server.js — её же
зовёт их веб-лаунчер (npm start): она проверяет геодату и поднимает src/NodeL2. Логин и гейм — один процесс,
база — встроенная SQLite (tmp/nodel2.sqlite и рядом tmp/nodel2.history.sqlite), MariaDB не нужна.

Сервер работает в скрытой консоли, его вывод — в tmp/logs/l2hub-<id>.log. Остановка — Ctrl+C в эту консоль:
обёртка и сервер ловят SIGINT, сервер сливает буфер персонажей, гасит воркеры и закрывает SQLite
(shutdown в src/NodeL2.js; через 15 с он выходит сам, обёртка добивает его через 18 с).

Наши настройки — слой data/l2solo/<id>.ini из профиля: адреса, порты, LLM. Сервер читает его через
L2NODE_SHARED_CONFIG_FILE поверх config/default.ini, а их config/local.ini — поверх слоя: правки автора там главнее.

Бэкапы мира — их «Saved Games» (tmp/saves) их же кодом: l2solo_saves.js вызывает scripts/saved-games.js.
Ядро не зависит от окна: проверка — python -m l2hub.cli state|check|start|stop c4.
"""

import ctypes
import json
import os
import re
import shutil
import sqlite3
import subprocess
import time
from datetime import datetime
from pathlib import Path

import psutil

from . import brain, catalog, health, l2ini, paths, runtimes, servers

TYPE = "l2solo"
LAYER_DIR = paths.DATA / "l2solo"
TOOL = paths.PACKAGE / "l2solo_tool.js"     # их код на Node: сохранения, вайп, проверка LLM
RU_OVERLAY = paths.PACKAGE / "l2solo_ru.js"  # русский оверлей: подгружается в их процессы через NODE_OPTIONS
LOG_TIME = paths.PACKAGE / "l2solo_logtime.js"  # время в строках лога сервера, тоже через NODE_OPTIONS
LLM_USAGE = paths.PACKAGE / "l2solo_usage.js"  # расход LLM ботов — в data/l2solo/<id>.usage.json
LANGUAGES = {"en": "английский (как у L2Solo)", "ru": "русский (оверлей L2Hub)"}
RU_MARK = "L2Hub RU ::"                     # строки оверлея в логе сервера
PRESETS = ("x1", "x10", "x50")              # рейты L2Solo (src/GameServer/ProgressionRates.js)
# Болтовня ботов: пресет поверх частот [BotPopulation] из профиля (раздел "config"). Ответы игроку не трогаются.
CHATTER = {"off": "молчат: только отвечают игроку", "quiet": "тихо: вдвое реже, чем в профиле",
           "normal": "как в профиле", "l2solo": "как у L2Solo: без наших частот"}
CHATTER_SWITCHES = ("globalChatEnabled", "marketTradeChatEnabled", "partyRecruitmentChatEnabled", "clanChatEnabled",
                    "chatReactionsEnabled", "ambientScenesEnabled")  # src/GameServer/Bot/Population/PopulationConfig.js
MODES_FILE = paths.PACKAGE / "l2solo_modes.json"  # режимы сервера C4 (рейты, злость мира, заточка) — как server_modes.json у Mobius
# Ручки пульта поверх их config/default.ini: «раздел.ключ» → (тип, мин, макс). Выбор автора — "world" в <id>.settings.json,
# в слой — при запуске (layer_sections). Всё читается их сервером при старте.
TUNING = {
    "BotPopulation.pvpAggression": (float, 0, 1),  # src/GameServer/Bot/Population/PopulationConfig.js
    "General.questRewardRate": (float, 0.1, 100),  # ProgressionRates.js: множится на пресет; в default.ini его нет
    **{f"Enchant.{k}": (int, 0, 100) for k in ("weaponChance", "armorChance", "accessoryChance", "blessedWeaponChance",
                                                "blessedArmorChance", "blessedAccessoryChance", "crystalWeaponChance",
                                                "crystalArmorChance", "crystalAccessoryChance")},  # C4EnchantRules.js
    **{f"Enchant.{k}": (int, 0, 30) for k in ("safeMax", "safeMaxFull", "maxWeapon", "maxArmor", "maxAccessory")},
    # Население ботов (PopulationConfig.js, PopulationSeedPlanner.js): новых заселяют волнами новичков в стартовых деревнях,
    # пока их меньше потолка; заселённых не убирают. Потолок у них самих — 2000.
    "BotPopulation.maxPlayingPopulation": (int, 0, 2000),
    "BotPopulation.starterBotsPerRace": (int, 0, 200),
    # General.maxLevel сюда не выведен: ниже 78 их код срезает уровень и опыт уже прокачанных — ботов при заселении
    # (GeneratedColdSeeder.ensureCharacter), игрока при следующем опыте (ExperienceReward), — и подъём потолка их не вернёт.
}
TUNING_DEFAULTS = {"General.questRewardRate": 1.0}  # чего нет в их default.ini — как в их коде
# Ручки-выбор: «раздел.ключ» → {значение: что значит}; первое — как у L2Solo.
CHOICES = {
    # src/GameServer/Quest/BeginnerReward.js: заряды за квесты-награды деревень (257, 260, 265, 273, 293) — раз на персонажа.
    "General.newbieRewardPolicy": {"strict": "как у L2Solo: только первому персонажу аккаунта",
                                   "grant": "и персонажам, созданным до этого правила у L2Solo",
                                   "always": "каждому персонажу, и твинкам тоже"},
}
AGGRESSION = {"peace": (0.0, "мирный: боты первыми не нападают, самооборона остаётся"), "normal": (0.5, "как у L2Solo"),
              "angry": (1.0, "злой: нападают и мстят чаще, бьются до последнего")}
WIPE_SCOPES = {"bots": "боты", "players": "игроки", "all": "весь мир"}
WRAPPER = "scripts/run-server.js"
NODE_MIN = (22, 15)       # README L2Solo; в package.json — 22.5, но они сами просят 22.15+
GEODATA_FILES = 203       # scripts/geodata-bootstrap.js, DEFAULT_FILE_COUNT
START_TIMEOUT = 600       # мир с ботами грузится долго, а первый запуск ещё и заселяет его
STOP_TIMEOUT = 60
KEEP_AUTO = 3             # сохранение — полная копия мира, поэтому авто-сохранений держим меньше, чем дампов MariaDB
SAVE_PREFIX = "L2Hub: "   # имя сохранения, которое сделал лаунчер: «L2Hub: start» и т. п.
BOT_ACCOUNTS = "bot\\_%"  # аккаунты ботов — bot_* (BoardRules.isBotAccount), игроки — все остальные
ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
FLUSHED = "flushing buffered character state"
STOP_FAILURES = re.compile(r"final buffered flush failed|graceful SQLite close failed|cold simulation drain failed|shutdown left \d+ unwritten")
STOP_MARK = "L2Hub: остановка — "
_cache: dict[tuple, tuple[float, object]] = {}


def is_l2solo(p: dict) -> bool:
    return p.get("type") == TYPE


def _cached(key: tuple, ttl: float, fn):
    hit = _cache.get(key)
    if hit and time.monotonic() - hit[0] < ttl:
        return hit[1]
    value = fn()
    _cache[key] = (time.monotonic(), value)
    return value


# ---------- Пути, Node.js, настройки ----------

def server_dir(p: dict) -> Path:
    return catalog.server_dir(p)


def log_path(p: dict, previous: bool = False) -> Path:
    return server_dir(p) / "tmp" / "logs" / f"l2hub-{p['id']}{'.previous' if previous else ''}.log"


def layer_path(p: dict) -> Path:
    return LAYER_DIR / f"{p['id']}.ini"


def node_exe(p: dict) -> Path | None:
    """Node.js: путь из профиля ("node"), портативный runtime/node или установленный в системе."""
    for c in (p.get("node"), paths.RUNTIME / "node" / "node.exe", shutil.which("node")):
        if c and Path(c).is_file():
            return Path(c).resolve()
    return None


def node_version(exe: Path) -> tuple[int, ...] | None:
    def run():
        try:
            out = subprocess.run([str(exe), "-v"], capture_output=True, text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        m = re.match(r"v(\d+)\.(\d+)\.(\d+)", out.strip())
        return tuple(map(int, m.groups())) if m else None
    return _cached(("node", str(exe)), 300, run)


def _parse_ini(text: str) -> dict[str, dict[str, str]]:
    out: dict[str, dict[str, str]] = {}
    section = out.setdefault("", {})
    for line in text.splitlines():
        s = line.strip()
        if not s or s[0] in ";#":
            continue
        if m := re.fullmatch(r"\[([^\]]+)\]", s):
            section = out.setdefault(m.group(1), {})
        elif "=" in s:
            key, value = s.split("=", 1)
            section[key.strip()] = value.strip()
    return out


def config(p: dict) -> dict[str, dict[str, str]]:
    """Настройки, как их соберёт сервер при следующем запуске (src/Global.js): config/default.ini, наш слой, их
    config/local.ini. Слой — из профиля и пульта (layer_sections), а не из файла: файла до первого запуска нет."""
    server = server_dir(p)

    def ini(f: Path) -> dict:
        return _parse_ini(f.read_text(encoding="utf-8", errors="replace")) if f.is_file() else {}
    merged: dict[str, dict[str, str]] = {}
    for layer in (ini(server / "config" / "default.ini"), layer_sections(p), ini(server / "config" / "local.ini")):
        for section, values in layer.items():
            merged.setdefault(section, {}).update(values)
    return merged


def db_files(p: dict) -> tuple[Path, Path]:
    """База мира и база истории (src/HistoryStore.js, pathFor)."""
    db = config(p).get("Database", {})
    world = (server_dir(p) / (db.get("path") or "tmp/nodel2.sqlite")).resolve()
    if db.get("historyPath"):
        return world, (server_dir(p) / db["historyPath"]).resolve()
    name = world.name[:-len(".sqlite")] if world.name.endswith(".sqlite") else world.name
    return world, world.with_name(name + ".history.sqlite")


def layer_sections(p: dict) -> dict[str, dict[str, str]]:
    """Наш слой настроек: адреса и порты, LLM — из профиля и пульта, свои ключи — раздел "config" профиля."""
    ports = p["ports"]
    sections: dict[str, dict] = {
        # Только этот ПК: логин и гейм слушают 127.0.0.1 (у L2Solo по умолчанию — все сети, 0.0.0.0).
        "AuthServer": {"hostname": "127.0.0.1", "port": ports["login"]},
        "GameServer": {"hostname": "127.0.0.1", "port": ports["game"]},
    }
    if "map" in ports:
        sections["WorldObserver"] = {"hostname": "127.0.0.1", "port": ports["map"]}
    llm = p.get("llm") or {}
    if llm and llm_enabled(p):
        # Секция [AI] — любой OpenAI-совместимый адрес; ключ идёт переменной L2NODE_AI_API_KEY (_env), в файл не пишется.
        sections["AI"] = {"enabled": "true", "apiUrl": llm["apiUrl"], "model": llm["model"],
                          "partyRouterModel": llm.get("partyRouterModel", llm["model"]), **(llm.get("options") or {})}
    for section, values in (p.get("config") or {}).items():
        sections.setdefault(section, {}).update(values)
    for key, value in world_overrides(p).items():  # ручки пульта: злость мира, квесты, заточка
        section, name = key.split(".", 1)
        sections.setdefault(section, {})[name] = value
    if "BotPopulation" in sections or chatter(p) == "off":
        sections["BotPopulation"] = _chatter_config(p, sections.get("BotPopulation") or {})
    return {section: {k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in values.items()}
            for section, values in sections.items()}


def _frequency(key: str) -> str | None:
    """Ключ частоты болтовни: интервал (мс) или шанс; остальное в [BotPopulation] пресет не трогает."""
    if key.endswith(("IntervalMs", "CooldownMs")):
        return "interval"
    if key.endswith("Chance"):
        return "chance"
    return None


def _chatter_config(p: dict, values: dict) -> dict:
    """[BotPopulation] слоя по пресету болтовни: quiet — интервалы ×2 и шансы ×0,5 от профиля, l2solo — без наших
    частот (их default.ini), off — каналы болтовни выключены; normal — как в профиле."""
    level = chatter(p)
    out = dict(values)
    if level == "l2solo":
        out = {k: v for k, v in out.items() if not _frequency(k)}
    elif level == "quiet":
        for k, v in values.items():
            if _frequency(k) == "interval":
                out[k] = int(float(v) * 2)
            elif _frequency(k) == "chance":
                out[k] = round(float(v) / 2, 6)
    elif level == "off":
        out.update({k: False for k in CHATTER_SWITCHES})
    return out


def _layer_text(p: dict) -> str:
    sections = layer_sections(p)
    lines = [f"; Слой L2Hub для {p['id']}: L2Solo читает его через L2NODE_SHARED_CONFIG_FILE поверх config/default.ini.",
             f"; Лаунчер перезаписывает его при каждом запуске из profiles/{p['id']}.json — правки здесь потеряются.",
             f"; Свои настройки — в {p['server']}/config/local.ini: он главнее этого слоя.", ""]
    for section, values in sections.items():
        lines.append(f"[{section}]")
        lines += [f"{k} = {v}" for k, v in values.items()]
        lines.append("")
    return "\n".join(lines)


def write_layer(p: dict) -> Path:
    """Пишет слой в data/l2solo/<id>.ini. Перезаписывается при каждом запуске — руками в нём не править."""
    path = layer_path(p)
    _write_file(path, _layer_text(p))
    return path


def live_layer_path(p: dict) -> Path:
    """Копия слоя, с которым поднят сервер: сам слой переписывает и l2solo_tool.js (проверка LLM и т. п.)."""
    return LAYER_DIR / f"{p['id']}.live.ini"


def settings_pending(p: dict) -> bool:
    """Сервер работает, а слой из пульта уже другой (ручки, режим, болтовня, LLM) — нужен перезапуск."""
    try:
        return live_layer_path(p).read_text(encoding="utf-8") != _layer_text(p)
    except OSError:
        return False


def _write_file(path: Path, text: str) -> None:
    """Запись через временный файл. Одинаковое содержимое не переписывается: окно, дев-сервер и консоль — разные
    процессы и пишут одни и те же файлы; у каждого свой временный файл, а занятый файл (WinError 32) — повтор."""
    data = text.encode("utf-8")
    try:
        if path.read_bytes() == data:
            return
    except OSError:
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    tmp.write_bytes(data)
    for attempt in range(20):
        try:
            tmp.replace(path)
            return
        except PermissionError:
            try:
                if path.read_bytes() == data:  # соседний процесс уже записал то же самое
                    tmp.unlink(missing_ok=True)
                    return
            except OSError:
                pass
            if attempt == 19:
                tmp.unlink(missing_ok=True)
                raise
            time.sleep(0.05)


def settings_path(p: dict) -> Path:
    return LAYER_DIR / f"{p['id']}.settings.json"


def user_settings(p: dict) -> dict:
    """Выбор автора из пульта (рейты, LLM вкл/выкл) поверх профиля из git; data/ в git не входит."""
    try:
        return json.loads(settings_path(p).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save_user_settings(p: dict, **changes) -> None:
    _write_file(settings_path(p), json.dumps({**user_settings(p), **changes}, ensure_ascii=False, indent=1))


def progression(p: dict) -> str:
    return user_settings(p).get("progression") or p.get("progression") or "x1"


def llm_enabled(p: dict) -> bool:
    return bool(user_settings(p).get("llmEnabled", (p.get("llm") or {}).get("enabled", False)))


def chatter(p: dict) -> str:
    level = user_settings(p).get("chatter") or p.get("chatter") or "normal"
    return level if level in CHATTER else "normal"


def set_chatter(p: dict, level: str) -> dict:
    if level not in CHATTER:
        raise ValueError(f"болтовня ботов — одно из {', '.join(CHATTER)}")
    _save_user_settings(p, chatter=level)
    return {"chatter": level, "restart": bool(server_process(p))}


def world_overrides(p: dict) -> dict:
    """Ручки пульта, выбранные автором (только ключи из TUNING и CHOICES)."""
    return {k: v for k, v in (user_settings(p).get("world") or {}).items() if k in TUNING or k in CHOICES}


def _number(raw, kind):
    try:
        return kind(float(raw))
    except (TypeError, ValueError):
        return None


def _choice(raw, key: str):
    """Значение ручки-выбора; чего нет в списке — None (их код тогда берёт своё по умолчанию)."""
    value = str(raw).strip().lower() if raw is not None else None
    return value if value in CHOICES[key] else None


def _value(raw, key: str):
    return _choice(raw, key) if key in CHOICES else _number(raw, TUNING[key][0])


def _same(a, b) -> bool:
    if isinstance(a, str) or isinstance(b, str):
        return a == b
    return abs(float(a) - float(b)) < 1e-9


def tuning(p: dict) -> dict:
    """Ручки пульта: значение, с которым поднимется сервер (их default.ini → наш слой → их local.ini), и откуда оно:
    hub — выбор в пульте, local — их config/local.ini (он главнее нашего слоя), l2solo — как у L2Solo."""
    merged = config(p)
    local_file = server_dir(p) / "config" / "local.ini"
    local = _parse_ini(local_file.read_text(encoding="utf-8", errors="replace")) if local_file.is_file() else {}
    over = world_overrides(p)
    out = {}
    for key in [*TUNING, *CHOICES]:
        section, name = key.split(".", 1)
        value = _value(merged.get(section, {}).get(name), key)
        out[key] = {"value": _default(key) if value is None else value,
                    "source": "local" if name in local.get(section, {}) else "hub" if key in over else "l2solo"}
    return out


def _default(key: str):
    """Чего нет в настройках — как в их коде: у выбора — первое значение."""
    return next(iter(CHOICES[key])) if key in CHOICES else TUNING_DEFAULTS.get(key)


def _baseline(p: dict, key: str):
    """Значение ручки без выбора в пульте: раздел config профиля, иначе их default.ini, иначе как в их коде."""
    section, name = key.split(".", 1)
    default = server_dir(p) / "config" / "default.ini"
    raw = (p.get("config") or {}).get(section, {}).get(name)
    if raw is None and default.is_file():
        raw = _parse_ini(default.read_text(encoding="utf-8", errors="replace")).get(section, {}).get(name)
    value = _value(raw, key)
    return _default(key) if value is None else value


def _check(key: str, value):
    if key in CHOICES:
        choice = _choice(value, key)
        if choice is None:
            raise ValueError(f"{key}: одно из {', '.join(CHOICES[key])}")
        return choice
    if key not in TUNING:
        raise ValueError(f"нет такой настройки: {key}")
    kind, lo, hi = TUNING[key]
    number = _number(value, kind)
    if number is None or not lo <= number <= hi:
        raise ValueError(f"{key}: нужно число от {lo} до {hi}")
    return number


def set_world(p: dict, changes: dict) -> dict:
    """Ручки пульта: {"раздел.ключ": число или None — вернуть как у L2Solo}. Применится при следующем запуске сервера.
    Значение, равное тому, что и так было бы (_baseline), в слой не пишется: слой меняется, только когда меняется мир."""
    world = world_overrides(p)
    for key, value in changes.items():
        if value is None or value == "":
            if key not in TUNING and key not in CHOICES:
                raise ValueError(f"нет такой настройки: {key}")
            world.pop(key, None)
            continue
        number = _check(key, value)
        base = _baseline(p, key)
        if base is not None and _same(number, base):
            world.pop(key, None)
        else:
            world[key] = number
    _save_user_settings(p, world=world)
    t = tuning(p)
    return {"tuning": t, "local": [k for k in changes if t[k]["source"] == "local"], "restart": bool(server_process(p))}


def aggression(p: dict) -> str | None:
    """Злость мира кнопкой: peace / normal / angry или None, если pvpAggression своё (local.ini, профиль)."""
    value = tuning(p)["BotPopulation.pvpAggression"]["value"]
    return next((k for k, (v, _t) in AGGRESSION.items() if value is not None and abs(value - v) < 1e-9), None)


def set_aggression(p: dict, level: str) -> dict:
    if level not in AGGRESSION:
        raise ValueError(f"злость мира — одно из {', '.join(AGGRESSION)}")
    return {"aggression": level, **set_world(p, {"BotPopulation.pvpAggression": AGGRESSION[level][0]})}


def _modes() -> list[dict]:
    return json.loads(MODES_FILE.read_text(encoding="utf-8"))["modes"]


def modes(p: dict) -> list[dict]:
    """Режимы сервера C4 (l2solo_modes.json): активный — у которого совпали пресет рейтов и все ручки."""
    values = {k: v["value"] for k, v in tuning(p).items()}
    return [{"id": m["id"], "title": m["title"], "note": m["note"],
             "active": progression(p) == m["progression"]
             and all(values.get(k) is not None and _same(values[k], v) for k, v in m["world"].items())}
            for m in _modes()]


def set_mode(p: dict, mode_id: str) -> dict:
    """Режим целиком: пресет рейтов и все ручки режима (прежний выбор ручек заменяется)."""
    m = next((x for x in _modes() if x["id"] == mode_id), None)
    if not m:
        raise ValueError(f"нет режима {mode_id}")
    if m["progression"] not in PRESETS or any(k not in TUNING and k not in CHOICES for k in m["world"]):
        raise ValueError(f"l2solo_modes.json: режим {mode_id} — неизвестный пресет или ключ")
    world = {k: _check(k, v) for k, v in m["world"].items()}
    _save_user_settings(p, progression=m["progression"],
                        world={k: v for k, v in world.items() if (b := _baseline(p, k)) is None or not _same(v, b)})
    t = tuning(p)
    return {"title": m["title"], "local": [k for k in m["world"] if t[k]["source"] == "local"], "restart": bool(server_process(p))}


def bot_language(p: dict) -> str:
    """Язык ботов: en — как у L2Solo, ru — наш оверлей l2solo_ru.js (фразы, строки чата и указание языка для LLM)."""
    lang = user_settings(p).get("botLanguage") or p.get("botLanguage") or "en"
    return lang if lang in LANGUAGES else "en"


def set_bot_language(p: dict, lang: str) -> dict:
    if lang not in LANGUAGES:
        raise ValueError(f"язык ботов — одно из {', '.join(LANGUAGES)}")
    _save_user_settings(p, botLanguage=lang)
    return {"botLanguage": lang, "restart": bool(server_process(p))}


def usage_path(p: dict) -> Path:
    return LAYER_DIR / f"{p['id']}.usage.json"


def llm_usage(p: dict) -> dict:
    """Расход LLM ботов L2Solo (пишет l2solo_usage.js): сегодня, 7 дней, всё — запросы, токены и цена по ценам
    из настроек мозгов (data/brain/settings.json, $ за 1 млн токенов)."""
    try:
        days = json.loads(usage_path(p).read_text(encoding="utf-8")).get("days", {})
    except (OSError, ValueError):
        days = {}
    settings = brain.load_settings(p.get("brain") or {})
    price_in, price_out = float(settings.get("priceIn") or 0), float(settings.get("priceOut") or 0)

    def total(keys):
        out = {"requests": 0, "in": 0, "out": 0}
        for d in keys:
            for row in days.get(d, {}).values():
                for k in out:
                    out[k] += row.get(k, 0)
        out["cost"] = round((out["in"] * price_in + out["out"] * price_out) / 1e6, 4)
        return out
    return {"today": total([datetime.now().strftime("%Y-%m-%d")]), "week": total(sorted(days)[-7:]), "all": total(days),
            "priced": bool(price_in or price_out)}


def _llm_key(p: dict) -> str:
    """Ключ для [AI]: файл в data/brain (кладёт автор). Не печатается и в слой не пишется."""
    f = paths.DATA / "brain" / (p.get("llm") or {}).get("keyFile", "deepseek.key")
    return f.read_text(encoding="utf-8").strip() if f.is_file() else ""


def _require(script: Path) -> str:
    """--require для NODE_OPTIONS: путь прямыми слэшами — NODE_OPTIONS понимает кавычки, а обратный слэш в них — нет."""
    path = script.as_posix()
    return f'--require "{path}"' if " " in path else f"--require {path}"


def _env(p: dict, layer: Path, server: bool = False) -> dict:
    """Среда их процессов Node. server — запуск самого сервера: тогда ещё время в строках лога (l2solo_logtime.js);
    l2solo_tool.js его не получает — он отвечает строкой JSON в stdout."""
    env = {**os.environ, "L2NODE_SHARED_CONFIG_FILE": str(layer)}
    for name in ("L2NODE_CONFIG_FILE", "L2NODE_AI_API_KEY", "OPENROUTER_API_KEY"):
        env.pop(name, None)  # чужие переменные среды не должны подменять профиль
    env["L2NODE_PROGRESSION_RATE"] = progression(p)  # x1, x10, x50 — так же задаёт рейты их веб-лаунчер
    if p.get("llm") and llm_enabled(p) and (key := _llm_key(p)):
        env["L2NODE_AI_API_KEY"] = key
    # Наши прелоады — во все их процессы Node (обёртка, сервер, воркеры); их код не меняется.
    preload = [LOG_TIME] if server else []
    if server and p.get("llm") and llm_enabled(p):
        preload.append(LLM_USAGE)
        env["L2HUB_LLM_USAGE_FILE"] = str(usage_path(p))
    if bot_language(p) == "ru":
        preload.append(RU_OVERLAY)
    if preload:
        options = " ".join(_require(s) for s in preload)
        env["NODE_OPTIONS"] = f"{env['NODE_OPTIONS']} {options}" if env.get("NODE_OPTIONS") else options
    return env


def install_problem(p: dict) -> str | None:
    """Чего не хватает для запуска; None — всё на месте."""
    server = server_dir(p)
    exe = node_exe(p)
    if not (server / WRAPPER).is_file():
        return f"Нет L2Solo в {p['server']}: git clone https://github.com/pmbstyle/L2Solo.git {p['server']} (docs/L2SOLO.md)"
    if not exe:
        return "Нет Node.js: поставь Node.js 22.15+ или положи портативный в runtime\\node"
    v = node_version(exe)
    if not v or v < NODE_MIN:
        return f"Node.js {'.'.join(map(str, v)) if v else '?'} старый, L2Solo нужен 22.15+: {exe}"
    if not (server / "node_modules").is_dir():
        return f"Не установлены зависимости: npm install --ignore-scripts в {p['server']}"
    if geodata_count(p) < GEODATA_FILES:
        return f"Нет геодаты: npm run geodata в {p['server']} (209 МиБ с l2solo.com)"
    return None


def geodata_count(p: dict) -> int:
    d = server_dir(p) / "data" / "Geodata"
    return _cached(("geo", str(d)), 30, lambda: len(list(d.glob("*.l2j"))) if d.is_dir() else 0)


# ---------- Процесс ----------

def server_process(p: dict) -> psutil.Process | None:
    """Обёртка run-server.js этой хроники, если она жива; сервер NodeL2 — её дочерний процесс."""
    exe = node_exe(p)
    proc = servers.process(servers.read_pids(p["id"]).get("server"), exe) if exe else None
    try:
        return proc if proc and any(a.replace("\\", "/").endswith(WRAPPER) for a in proc.cmdline()) else None
    except psutil.Error:
        return None


def _forget(p: dict) -> None:
    pids = servers.read_pids(p["id"])
    pids.pop("server", None)
    servers._write_pids(p["id"], pids)


def _family(proc: psutil.Process) -> list[psutil.Process]:
    try:
        return proc.children(recursive=True)
    except psutil.Error:
        return []


def _kill_tree(procs: list[psutil.Process]) -> None:
    for pr in procs:
        try:
            pr.kill()
        except psutil.Error:
            pass
    psutil.wait_procs(procs, timeout=10)


def _proc_info(proc: psutil.Process | None) -> dict:
    """PID обёртки, а память — вместе с сервером: обёртка весит мегабайты, мир держит дочерний NodeL2."""
    if not proc:
        return {}
    try:
        rss = sum(pr.memory_info().rss for pr in [proc, *_family(proc)])
        return {"pid": proc.pid, "mem_mb": round(rss / 2**20), "uptime_s": int(time.time() - proc.create_time())}
    except psutil.Error:
        return {"pid": proc.pid}


def start(p: dict) -> int:
    """Поднимает L2Solo и ждёт, пока откроются логин и гейм."""
    if proc := server_process(p):
        return proc.pid
    if problem := install_problem(p):
        raise RuntimeError(problem)
    for part in ("login", "game"):
        if servers.port_open(p["ports"][part]):
            raise RuntimeError(f"Порт {p['ports'][part]} занят: его держит другая хроника или программа. Останови её и запусти снова.")
    layer = write_layer(p)
    _write_file(live_layer_path(p), _layer_text(p))
    log = log_path(p)
    log.parent.mkdir(parents=True, exist_ok=True)
    if log.exists():
        shutil.copyfile(log, log_path(p, previous=True))
    # Как servers._hidden_console: сброс «игнорировать Ctrl+C», скрытая своя консоль — ей и пошлём Ctrl+C при остановке.
    # Вывод — в файл: у L2Solo нет своего лога, а консоль скрыта.
    ctypes.windll.kernel32.SetConsoleCtrlHandler(None, False)
    si = subprocess.STARTUPINFO()
    si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    si.wShowWindow = 0  # SW_HIDE
    node = node_exe(p)
    with log.open("wb") as out:
        out.write(f"L2Hub: запуск {datetime.now():%Y-%m-%d %H:%M:%S} · {node} {WRAPPER} · слой {layer}\n".encode("utf-8"))
        out.flush()
        proc = subprocess.Popen([str(node), WRAPPER], cwd=str(server_dir(p)), env=_env(p, layer, server=True), startupinfo=si,
                                creationflags=subprocess.CREATE_NEW_CONSOLE, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT)
    pids = servers.read_pids(p["id"])
    pids["server"] = proc.pid
    servers._write_pids(p["id"], pids)
    deadline = time.monotonic() + START_TIMEOUT
    for part in ("login", "game"):
        port = p["ports"][part]
        if not servers.wait_port(port, max(1.0, deadline - time.monotonic()), alive=lambda: proc.poll() is None):
            if proc.poll() is not None:
                _forget(p)
                raise RuntimeError(f"L2Solo завершился при запуске{_last_error(p)}. Лог — {log}")
            raise RuntimeError(f"L2Solo не открыл порт {port} за {START_TIMEOUT // 60} мин. Лог — {log}")
    return proc.pid


def stop(p: dict) -> str:
    """Ctrl+C в консоль сервера и ожидание: graceful — вышел сам, killed — пришлось завершить жёстко, gone — не работал."""
    proc = server_process(p)
    if not proc:
        _forget(p)
        return "gone"
    family = _family(proc)
    log = log_path(p)
    mark = log.stat().st_size if log.exists() else 0
    helper = subprocess.run(paths.ctrlc_command(proc.pid), creationflags=subprocess.CREATE_NO_WINDOW, timeout=20)
    how = "graceful"
    try:
        if helper.returncode != 0:
            raise psutil.TimeoutExpired(0)
        proc.wait(STOP_TIMEOUT)
    except psutil.TimeoutExpired:
        how = "killed"
        _kill_tree([proc, *family])
    _, alive = psutil.wait_procs(family, timeout=10)
    if alive:
        how = "killed"
        _kill_tree(alive)
    _forget(p)
    text = _read(log, mark)
    failures = [ANSI_RE.sub("", ln).strip() for ln in text.splitlines() if STOP_FAILURES.search(ln)]
    saved = how == "graceful" and FLUSHED in text and not failures
    verdict = "штатно, мир сохранён" if saved else ("жёстко: сервер не вышел сам" if how == "killed" else "с ошибками сохранения")
    with log.open("ab") as out:
        out.write(f"{STOP_MARK}{verdict} ({datetime.now():%Y-%m-%d %H:%M:%S})\n".encode("utf-8"))
    if not saved:
        detail = f": {'; '.join(failures[:3])}" if failures else ""
        raise RuntimeError(f"L2Solo остановлен {verdict}{detail} — последние минуты игры могли не сохраниться. Лог — {log}")
    return how


def kill(p: dict) -> list[str]:
    """Аварийно: жёстко завершает сервер (обёртку и NodeL2) и клиент этой хроники."""
    done = []
    if proc := server_process(p):
        _kill_tree([proc, *_family(proc)])
        done.append("server")
    if client := servers.part_process(p, "client"):
        client.kill()
        done.append("client")
    servers._write_pids(p["id"], {})
    return done


# ---------- Лог ----------

def _read(path: Path, start: int = 0, size: int | None = None) -> str:
    if not path.exists():
        return ""
    with path.open("rb") as f:
        f.seek(0, 2)
        n = f.tell()
        f.seek(max(start, n - size) if size else start)
        return f.read().decode("utf-8", "replace")


def _clean(text: str) -> list[str]:
    return [ANSI_RE.sub("", ln).rstrip() for ln in text.splitlines() if ln.strip()]


def log_tail(p: dict, lines: int = 80) -> list[str]:
    return _clean(_read(log_path(p), size=200_000))[-lines:]


def _last_error(p: dict) -> str:
    errors = [ln for ln in _clean(_read(log_path(p), size=50_000)) if re.search(r"(?i)error|failed|cannot|EADDRINUSE", ln)]
    return f": {errors[-1].strip()[:200]}" if errors else ""


def facts(p: dict) -> dict:
    """Что сервер написал о себе в лог текущего запуска."""
    text = "\n".join(_clean(_read(log_path(p), size=2_000_000)))
    out = {}
    if m := list(re.finditer(r"Progress\s+:: preset (\S+) \| exp x(\S+)", text)):
        out["rates"] = f"{m[-1].group(1)} (опыт x{m[-1].group(2)})"
        out["preset"] = m[-1].group(1)
    if m := list(re.finditer(r"Build Revision: \.+ (\S+)", text)):
        out["revision"] = m[-1].group(1)
    return out


def ru_status(p: dict) -> dict:
    """Что русский оверлей написал в лог текущего запуска: покрытие фраз, LLM, кириллица от игрока, ошибки."""
    lines = [ln.split(RU_MARK, 1)[1].strip() for ln in _clean(_read(log_path(p), size=2_000_000)) if RU_MARK in ln]
    out: dict = {"loaded": bool(lines)}
    for ln in lines:
        if m := re.match(r"фразы ботов по-русски: (\d+) из (\d+)", ln):
            out["phrases"] = [int(m.group(1)), int(m.group(2))]
        elif ln.startswith("LLM:"):
            out["llm"] = True
        elif ln.startswith("кириллица от игрока"):
            out["input"] = "cyrillic"
        elif ln.startswith("игрок пишет латиницей"):
            out["input"] = "latin1"
        elif ln.startswith(("ошибка оверлея", "перевод не прочитан")):
            out.setdefault("errors", []).append(ln)
    return out


def last_stop(p: dict) -> str | None:
    """Как остановился прошлый запуск — по логу: наша отметка, штатный выход по SIGINT или ничего (аварийно)."""
    text = "\n".join(_clean(_read(log_path(p), size=200_000)))
    if not text:
        return None
    if STOP_MARK in text:
        return text.rsplit(STOP_MARK, 1)[1].split("\n", 1)[0]
    if FLUSHED in text:
        return "штатно (остановлен не из L2Hub)"
    return "аварийно: в логе нет штатной остановки"


# ---------- База ----------

def _query(p: dict, sql: str, args: tuple = ()) -> list[tuple] | None:
    """Короткий запрос к базе мира только на чтение; WAL позволяет читать и у работающего сервера. Нет базы — None."""
    world, _ = db_files(p)
    if not world.is_file():
        return None
    con = sqlite3.connect(world.as_uri() + "?mode=ro", uri=True, timeout=2)
    try:
        return con.execute(sql, args).fetchall()
    finally:
        con.close()


def online(p: dict) -> int | None:
    def count():
        try:
            rows = _query(p, "SELECT COUNT(*) FROM characters WHERE isOnline=1 AND username NOT LIKE ? ESCAPE '\\'", (BOT_ACCOUNTS,))
        except sqlite3.Error:
            return None
        return rows[0][0] if rows else None
    return _cached(("online", p["id"]), 5, count)


def bots(p: dict) -> dict:
    """Боты мира: всего и сейчас в игре (горячие и холодные — для базы одинаково «в игре»)."""
    def count():
        try:
            rows = _query(p, "SELECT COUNT(*), SUM(isOnline) FROM characters WHERE username LIKE ? ESCAPE '\\'", (BOT_ACCOUNTS,))
        except sqlite3.Error:
            return {}
        return {"bots": rows[0][0], "botsOnline": rows[0][1] or 0} if rows else {}
    return _cached(("bots", p["id"]), 5, count)


def map_url(p: dict) -> str:
    return f"http://127.0.0.1:{p['ports']['map']}/observer/"


def characters(p: dict) -> dict:
    """Персонажи игроков (без ботов). Права GM в L2Solo не нужны: .admin открыт всем, поэтому только чтение."""
    try:
        rows = _query(p, "SELECT name, username, level, isGM, isOnline FROM characters WHERE username NOT LIKE ? ESCAPE '\\' ORDER BY id DESC",
                      (BOT_ACCOUNTS,))
    except sqlite3.Error as e:
        return {"ok": False, "error": f"База L2Solo не читается: {e}"}
    if rows is None:
        return {"ok": False, "error": "Мира ещё нет: запусти сервер, и L2Solo создаст базу."}
    return {"ok": True, "readonly": True, "note": "Права GM в L2Solo не нужны: админка .admin открыта всем.",
            "levels": {0: "Игрок", 1: "GM"},
            "items": [{"name": n, "account": a, "level": int(lvl), "access": int(gm), "online": bool(on)} for n, a, lvl, gm, on in rows]}


# ---------- Состояние и проверка ----------

def _held_by_other(p: dict, part: str) -> str | None:
    for q in catalog.load_profiles().values():
        if q["id"] != p["id"] and q["ports"].get(part) == p["ports"][part] and servers.part_process(q, part):
            return q["id"]
    return None


def _size(n: int) -> str:
    return f"{n / 2**30:.1f} ГБ".replace(".", ",") if n >= 2**30 else f"{n / 2**20:.0f} МБ"


def state(p: dict) -> dict:
    proc = server_process(p)
    info = _proc_info(proc)
    world, history = db_files(p)
    size = sum(f.stat().st_size for f in (world, history) if f.is_file())
    rel = world.relative_to(server_dir(p)).as_posix() if world.is_relative_to(server_dir(p)) else str(world)
    comps = {"db": {"state": "on" if proc else "off", "engine": "SQLite",
                    "where": f"SQLite в сервере · {rel}" + (f" · {_size(size)}" if size else " · мира ещё нет")}}
    family = {proc.pid, *(c.pid for c in _family(proc))} if proc else set()
    for part in ("login", "game"):
        port = p["ports"][part]
        heard = servers.listeners(port)
        other = None
        if proc and heard:
            st = "on"
        elif proc:
            st = "starting"
        elif heard and (other := _held_by_other(p, part)):
            st = "other"
        elif heard:
            st = "bad"
        else:
            st = "off"
        comps[part] = {"state": st, "port": port, **info, **({"other": other} if other else {})}
        if st == "on":
            # Сокет держит дочерний NodeL2, а не обёртка: адреса — по всему дереву процессов сервера.
            comps[part]["listen"] = sorted({ip for ip, pid in heard if pid in family}) or sorted({ip for ip, _ in heard})
        elif st == "bad":
            comps[part]["holder"] = health._holder(heard)
    client = servers.part_process(p, "client")
    comps["client"] = {"state": "on" if client else "off", "protocol": p["client"].get("protocol"), **_proc_info(client)}
    comps["game"]["geo_regions"] = geodata_count(p)
    if comps["game"]["state"] == "on":
        f = facts(p)
        comps["game"].update(f, heap=f"{info.get('mem_mb', '—')} МБ", online=online(p), **bots(p))
        if "map" in p["ports"]:
            comps["game"]["map"] = map_url(p)
    return comps


def _git(p: dict) -> str | None:
    def run():
        try:
            r = subprocess.run(["git", "-C", str(server_dir(p)), "log", "-1", "--format=%h %cs %s"], capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
        except (OSError, subprocess.SubprocessError):
            return None
        return r.stdout.strip() or None
    return _cached(("git", p["id"]), 60, run)


def diagnostics(p: dict, backups_info: dict) -> list[dict]:
    """Проверки L2Solo: ok — порядок, warn — стоит глянуть, bad — сломано, na — неприменимо сейчас."""
    st = state(p)
    cfg = config(p)
    checks = []

    def add(status, title, detail="", hint=""):
        checks.append({"status": status, "title": title, "detail": detail, "hint": hint})

    exe = node_exe(p)
    v = node_version(exe) if exe else None
    if not exe:
        add("bad", "Node.js", "не найден", "Поставь Node.js 22.15+ или положи портативный в runtime\\node")
    else:
        add("ok" if v and v >= NODE_MIN else "bad", "Node.js", f"{'.'.join(map(str, v)) if v else '?'} · {exe}",
            "" if v and v >= NODE_MIN else "L2Solo нужен Node.js 22.15+")
    commit = _git(p)
    add("ok" if commit else "warn", "Код L2Solo", f"{p['server']} · {commit or 'не git-клон'}", "" if commit else "Нужен git clone — docs/L2SOLO.md")
    deps = (server_dir(p) / "node_modules").is_dir()
    add("ok" if deps else "bad", "Зависимости npm", "node_modules на месте" if deps else "нет node_modules",
        "" if deps else f"npm install --ignore-scripts в {p['server']}")
    geo = geodata_count(p)
    add("ok" if geo >= GEODATA_FILES else "bad", "Геодата", f"регионов: {geo} из {GEODATA_FILES}", "" if geo >= GEODATA_FILES else "npm run geodata")

    names = {"login": "логина", "game": "гейма"}
    for part in ("login", "game"):
        c = st[part]
        if c["state"] == "on":
            add("ok", f"Порт {names[part]} {health.addr_text(c.get('listen', []))}:{c['port']}", f"слушает, PID {c.get('pid', '—')}")
        elif c["state"] == "other":
            other = next((x["name"] for x in catalog.load_catalog() if x["id"] == c["other"]), c["other"])
            add("na", f"Порт {names[part]} {c['port']}", f"сейчас его держит {other}", "Порты у хроник общие: останови ту, чтобы запустить эту")
        elif c["state"] == "bad":
            add("bad", f"Порт {names[part]} {c['port']}", f"занят чужим процессом: {c.get('holder', '?')}", "Закрой программу, которая заняла порт")
        elif c["state"] == "starting":
            add("warn", f"Порт {names[part]} {c['port']}", "сервер грузит мир, порт ещё не открыт")
        else:
            add("na", f"Порт {names[part]} {c['port']}", "сервер выключен")

    hosts = {cfg.get("AuthServer", {}).get("hostname"), cfg.get("GameServer", {}).get("hostname")}
    live = sorted({a for part in ("login", "game") for a in st[part].get("listen", [])})
    if live:
        # Сервер работает — верим сокетам, а не конфигу.
        local = set(live) <= health.LOOPBACK
        add("ok" if local else "warn", "Сеть", f"{'только этот ПК: ' if local else ''}логин и гейм слушают {health.addr_text(live)}",
            "" if local else f"Адреса переопределены в {p['server']}\\config\\local.ini — там же их и вернуть")
    elif not layer_path(p).is_file():
        add("na", "Сеть", "слой настроек L2Hub появится при первом запуске: логин и гейм — только 127.0.0.1")
    elif hosts == {"127.0.0.1"}:
        add("ok", "Сеть", "только этот ПК: логин и гейм слушают 127.0.0.1")
    else:
        add("warn", "Сеть", f"логин и гейм слушают {', '.join(sorted(h or '?' for h in hosts))}",
            f"Адреса переопределены в {p['server']}\\config\\local.ini — там же их и вернуть")

    proto = p["client"].get("protocol")
    server_proto = cfg.get("General", {}).get("protocol")
    if proto and server_proto:
        ok = str(proto) == server_proto
        add("ok" if ok else "bad", "Протокол клиента", f"{p['client'].get('system', '')} · протокол {proto}; сервер принимает {server_proto}",
            "" if ok else "L2Solo понимает только клиент C4 протокола 656")
    l2ini_path = Path(p["client"]["path"]) / Path(p["client"]["exe"]).parent / "L2.ini"
    if not p["client"].get("path"):
        # Свежая установка из окна: клиент L2Solo не раздаём, его ставит пользователь (client_sources — где взять).
        add("warn", "Клиент", "папка клиента не указана", f"Скачай {pin()['client']['title']} и client mod по ссылкам из мастера, "
            "затем укажи папку клиента")
    elif l2ini_path.exists():
        try:
            addr = l2ini.server_addr(l2ini_path)
            add("ok" if addr == "127.0.0.1" else "warn", "Адрес сервера в L2.ini клиента", f"ServerAddr={addr}",
                "" if addr == "127.0.0.1" else "Клиент будет стучаться не на этот ПК")
        except ValueError as e:
            add("warn", "Адрес сервера в L2.ini клиента", f"не прочитать: {e}")
    else:
        add("bad", "Клиент", f"нет {l2ini_path}", "Клиент C4 — docs/L2SOLO.md, раздел «Клиент»")

    world, history = db_files(p)
    if world.is_file():
        size = sum(f.stat().st_size for f in (world, history) if f.is_file())
        try:
            chars = _query(p, "SELECT COUNT(*), SUM(username LIKE ? ESCAPE '\\') FROM characters", (BOT_ACCOUNTS,))[0]
            add("ok", "Мир (SQLite)", f"{_size(size)} · персонажей {chars[0]}, из них ботов {chars[1] or 0}")
        except sqlite3.Error as e:
            add("warn", "Мир (SQLite)", f"{_size(size)} · не читается: {e}")
    else:
        add("na", "Мир (SQLite)", "ещё не создан: появится при первом запуске")

    ai = cfg.get("AI") or {}
    llm_on = (ai.get("enabled") or cfg.get("OpenRouter", {}).get("enabled", "false")).lower() == "true"
    if llm_on:
        model = ai.get("model") or cfg.get("OpenRouter", {}).get("model", "?")
        key = bool(_llm_key(p)) if p.get("llm") and llm_enabled(p) else bool(ai.get("apiKey") or cfg.get("OpenRouter", {}).get("apiKey"))
        spent = llm_usage(p)
        day, week = spent["today"], spent["week"]
        money = f", ${week['cost']:.2f}" if spent["priced"] else ""
        add("ok" if key else "warn", "LLM ботов", f"{model} · ключ {'есть' if key else 'не найден'} · запросов сегодня {day['requests']}, "
            f"за 7 дней {week['requests']}{money}", "" if key else "Без ключа боты говорят заготовками")
    else:
        add("na", "LLM ботов", "выключен: боты говорят заготовками, без запросов к модели")

    add("ok", "Болтовня ботов", CHATTER[chatter(p)])

    lang = bot_language(p)
    if lang == "en":
        add("na", "Язык ботов", LANGUAGES["en"], "Русский — \"botLanguage\": \"ru\" в профиле (docs/L2SOLO.md)")
    elif not RU_OVERLAY.is_file():
        add("bad", "Язык ботов", f"русский, но нет {RU_OVERLAY.name}", "Оверлей входит в L2Hub — переустанови лаунчер")
    elif st["game"]["state"] != "on":
        add("na", "Язык ботов", f"{LANGUAGES['ru']} — включится при запуске")
    else:
        ru = ru_status(p)
        if not ru["loaded"]:
            add("warn", "Язык ботов", "русский, но оверлей не загрузился: в логе нет строк L2Hub RU", "Перезапусти сервер из L2Hub")
        else:
            done, total = ru.get("phrases", [0, 0])
            detail = f"фраз по-русски {done} из {total}" + (" · LLM по-русски" if ru.get("llm") else "")
            if ru.get("input") == "cyrillic":
                detail += " · кириллица от игрока доходит"
            elif ru.get("input") == "latin1":
                detail += " · игрок пишет кракозябрами (cp1251 как Latin-1)"
            hint = "; ".join(ru.get("errors", [])) or ("Новые фразы L2Solo после обновления — по-английски, пока не переведены"
                                                       if total and done < total else "")
            add("warn" if ru.get("errors") or (total and done < total) else "ok", "Язык ботов", detail, hint)

    if st["game"]["state"] == "off" and (how := last_stop(p)):
        add("ok" if how.startswith("штатно") else "warn", "Прошлая остановка", how,
            "" if how.startswith("штатно") else "Мир мог потерять последние минуты; перед запуском лаунчер снимет сохранение")
    if "map" in p["ports"]:
        add("ok" if st["game"]["state"] == "on" else "na", "Веб-карта мира", map_url(p) +
            ("" if st["game"]["state"] == "on" else " — когда сервер работает"))

    # ECONNRESET — клиент закрыл соединение с логином, так бывает при каждом входе; это не ошибка сервера.
    errors = [ln for ln in log_tail(p, 2000) if re.search(r"Error:|EADDRINUSE|Unhandled|uncaught", ln) and "ECONNRESET" not in ln]
    add("warn" if errors else "ok", "Ошибки в логе сервера", errors[-1][:160] if errors else f"{log_path(p).name}: ошибок нет",
        f"Открой лог в Сервисе ({len(errors)} строк)" if errors else "")

    saved, last = backups_info["items"], backups_info["last"]
    if "ОШИБКА" in last:
        add("warn", "Сохранения мира", last, f"Журнал — data\\backups\\db\\{p['id']}\\backups.log")
    elif saved:
        b = saved[0]
        add("ok", "Сохранения мира", f"всего {len(saved)}, последнее — {b['when']} ({b['labelText']})")
    else:
        add("warn" if world.is_file() else "na", "Сохранения мира", "ни одного",
            "Лаунчер сохраняет мир сам перед каждым запуском" if world.is_file() else "")
    return checks


# ---------- Шаги для окна ----------

def play_steps(p: dict, with_client: bool, auto_backup) -> list:
    steps = [("Сохраняю мир перед запуском…", lambda: auto_backup(p)),
             ("L2Solo грузит мир и ботов…", lambda: start(p))]
    if with_client:
        steps.append(("Запускаю клиент…", lambda: servers.start_client(p)))
    return steps


def stop_steps(p: dict) -> list:
    # Сначала клиент: персонаж выходит из мира как обычно, потом сервер сливает остальное на диск.
    return [("Закрываю клиент…", lambda: servers.stop_client(p)),
            ("Сохраняю мир и останавливаю L2Solo…", lambda: stop(p))]


def restart_steps(p: dict) -> list:
    return [("Сохраняю мир и останавливаю L2Solo…", lambda: stop(p)),
            ("L2Solo грузит мир и ботов…", lambda: start(p))]


# ---------- Сохранения мира (их Saved Games) ----------

def saves_dir(p: dict) -> Path:
    return server_dir(p) / "tmp" / "saves"


def _tool(p: dict, operation: str, *args: str, timeout: float = 900, what: str = "L2Solo", config: bool = False):
    """Операция l2solo_tool.js (их код на Node). config — операция читает конфиг сервера (вайп, LLM): тогда слой L2Hub
    сначала обновляется; сохранениям пути к базе передаются явно, слой им не нужен."""
    exe = node_exe(p)
    if not exe:
        raise RuntimeError(f"Нет Node.js — {what} делается их же кодом на Node")
    layer = write_layer(p) if config else layer_path(p)
    r = subprocess.run([str(exe), str(TOOL), str(server_dir(p)), operation, *map(str, args)], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout, env=_env(p, layer), cwd=str(server_dir(p)),
                       creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        out = json.loads(r.stdout.strip().splitlines()[-1])
    except (IndexError, ValueError):
        raise RuntimeError(f"{what}: {ANSI_RE.sub('', r.stderr or r.stdout).strip()[-300:] or 'нет ответа'}") from None
    if not out.get("ok"):
        raise RuntimeError(f"{what}: {out.get('error')}")
    return out["value"]


def _saves(p: dict, operation: str, arg: str | None = None):
    if operation == "list":
        return _tool(p, "saves-list", what="сохранения L2Solo")
    world, history = db_files(p)
    return _tool(p, f"saves-{operation}", world, history, arg or "", what="сохранения L2Solo")


def list_saves(p: dict, labels: dict) -> list[dict]:
    """Сохранения в формате бэкапов лаунчера; name — id сохранения, label — метка L2Hub или their (сделано в их лаунчере)."""
    if not saves_dir(p).is_dir():
        return []
    items = []
    for s in _cached(("saves", p["id"]), 3, lambda: _saves(p, "list")):
        local = datetime.fromisoformat(s["createdAt"].replace("Z", "+00:00")).astimezone()
        label = s["name"][len(SAVE_PREFIX):] if s["name"].startswith(SAVE_PREFIX) else "their"
        items.append({"name": s["id"], "time": local.strftime("%Y-%m-%d_%H-%M-%S"), "when": local.strftime("%Y-%m-%d %H:%M:%S"),
                      "label": label, "labelText": labels.get(label, s["name"]), "size": s["sizeBytes"]})
    return sorted(items, key=lambda x: x["time"], reverse=True)


def save_blocker(p: dict) -> str | None:
    if server_process(p):
        return "L2Solo работает: мир сохраняется только у выключенного сервера (так устроены их сохранения). Останови сервер."
    if not db_files(p)[0].is_file():
        return "Мира ещё нет: запусти сервер хотя бы раз."
    return None


def make_save(p: dict, label: str) -> dict:
    s = _saves(p, "create", SAVE_PREFIX + label)
    _cache.pop(("saves", p["id"]), None)
    return {"name": s["id"], "size": s["sizeBytes"]}


def save_path(p: dict, save_id: str) -> Path:
    if not re.fullmatch(r"[0-9a-f-]{36}", save_id):
        raise ValueError(f"неверный id сохранения: {save_id}")
    return saves_dir(p) / save_id


def load_save(p: dict, save_id: str) -> None:
    save_path(p, save_id)  # проверка id
    _saves(p, "load", save_id)
    _cache.pop(("saves", p["id"]), None)


# ---------- Пульт: карта мира, рейты, LLM, вайп (то, что есть в их веб-лаунчере и WorldObserver) ----------

def panel(p: dict) -> dict:
    """Всё для пульта C4 в окне: карта мира (их WorldObserver), рейты, LLM, мир и можно ли вайпать."""
    proc = server_process(p)
    world, history = db_files(p)
    llm = p.get("llm") or {}
    live = facts(p).get("preset") if proc else None
    out = {"ok": True, "running": bool(proc), "progression": progression(p), "presets": list(PRESETS), "livePreset": live,
           "restartForRates": bool(live and live != progression(p)),
           "llm": {"configured": bool(llm), "enabled": llm_enabled(p), "model": llm.get("model"), "key": bool(_llm_key(p)),
                   "usage": llm_usage(p)},
           "chatter": chatter(p), "chatterLevels": CHATTER,
           "botLanguage": bot_language(p), "botLanguages": LANGUAGES, "ru": (ru := ru_status(p) if proc else None),
           "restartForLanguage": bool(ru and ru["loaded"] != (bot_language(p) == "ru")),
           "world": {"exists": world.is_file(), "size": sum(f.stat().st_size for f in (world, history) if f.is_file())},
           "wipeScopes": WIPE_SCOPES, "wipeBlocker": save_blocker(p),
           "tuning": tuning(p), "aggression": aggression(p), "aggressionLevels": {k: t for k, (_v, t) in AGGRESSION.items()},
           "choices": CHOICES,
           "modes": modes(p), "settingsPending": bool(proc) and settings_pending(p)}
    if "map" in p["ports"]:
        out.update(mapUrl=map_url(p), mapUp=bool(proc) and servers.port_open(p["ports"]["map"]))
    if world.is_file():
        try:
            rows = _query(p, "SELECT SUM(username NOT LIKE ? ESCAPE '\\'), SUM(username LIKE ? ESCAPE '\\') FROM characters",
                          (BOT_ACCOUNTS, BOT_ACCOUNTS))
            out["world"].update(players=rows[0][0] or 0, bots=rows[0][1] or 0)
        except sqlite3.Error:
            pass
    return out


def set_progression(p: dict, rate: str) -> dict:
    if rate not in PRESETS:
        raise ValueError(f"рейты — одно из {', '.join(PRESETS)}")
    _save_user_settings(p, progression=rate)
    return {"progression": rate, "restart": bool(server_process(p))}


def set_llm(p: dict, enabled: bool) -> dict:
    if not p.get("llm"):
        raise ValueError(f"в profiles/{p['id']}.json нет раздела llm")
    _save_user_settings(p, llmEnabled=bool(enabled))
    return {"enabled": bool(enabled), "restart": bool(server_process(p))}


def llm_test(p: dict) -> dict:
    """Один запрос их шлюзом с настройками, как у сервера; при выключенном LLM — {"enabled": False}."""
    return _tool(p, "llm-test", timeout=60, what="проверка LLM", config=True)


def _wipe_check(p: dict, scope: str) -> None:
    if scope not in WIPE_SCOPES:
        raise ValueError(f"что вайпать — одно из {', '.join(WIPE_SCOPES)}")
    if reason := save_blocker(p):
        raise RuntimeError(reason)


def wipe_preview(p: dict, scope: str) -> dict:
    """Сколько персонажей и аккаунтов уйдёт: {scope, characters, accounts}."""
    _wipe_check(p, scope)
    return _tool(p, "wipe-preview", db_files(p)[0], scope, what="вайп L2Solo", config=True)


def wipe(p: dict, scope: str) -> dict:
    """Вайп их кодом (scripts/world-wipe.js) у выключенного сервера. Сохранение перед ним снимает вызывающий (backups)."""
    _wipe_check(p, scope)
    return _tool(p, "wipe", db_files(p)[0], scope, what="вайп L2Solo", config=True)


# ---------- Обновление L2Solo: git pull и npm install у выключенного сервера, мир сохраняется перед этим ----------

def update_log_path(p: dict) -> Path:
    return LAYER_DIR / f"{p['id']}.update.log"


def _exec(cmd: list, cwd: Path, timeout: float = 120, env: dict | None = None) -> str:
    try:
        r = subprocess.run([str(c) for c in cmd], cwd=str(cwd), capture_output=True, text=True, encoding="utf-8", errors="replace",
                           timeout=timeout, stdin=subprocess.DEVNULL, env=env, creationflags=subprocess.CREATE_NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as e:
        raise RuntimeError(f"{Path(str(cmd[0])).name} {cmd[1]}: {e}") from None
    if r.returncode:
        raise RuntimeError(f"{Path(str(cmd[0])).name} {cmd[1]}: {ANSI_RE.sub('', r.stderr or r.stdout).strip()[-300:] or f'код {r.returncode}'}")
    return r.stdout


def _run(p: dict, *cmd, timeout: float = 120) -> str:
    return _exec(list(cmd), server_dir(p), timeout)


def _npm(p: dict) -> Path:
    exe = node_exe(p)
    for c in ((exe.parent / "npm.cmd") if exe else None, shutil.which("npm.cmd"), shutil.which("npm")):
        if c and Path(c).is_file():
            return Path(c)
    raise RuntimeError("Нет npm: он ставится вместе с Node.js")


def update_check(p: dict, fetch: bool = True) -> dict:
    """Есть ли новый код у авторов: git fetch и коммиты, которых у нас нет. dirty — правки в их файлах (обновление
    их не трогает и откажет), ahead — наши коммиты поверх их ветки, detached — код откачен на старый коммит."""
    if not (server_dir(p) / ".git").exists():
        raise RuntimeError(f"{p['server']} — не git-клон L2Solo: обновлять нечего (docs/L2SOLO.md, «Установка с нуля»)")
    branch = _run(p, "git", "rev-parse", "--abbrev-ref", "HEAD").strip()
    head = _run(p, "git", "log", "-1", "--format=%h %cs %s").strip()
    dirty = [ln.strip() for ln in _run(p, "git", "status", "--porcelain", "--untracked-files=no").splitlines() if ln.strip()]
    out = {"head": head, "branch": branch, "dirty": dirty, "running": bool(server_process(p)), "behind": 0, "ahead": 0, "commits": []}
    if branch == "HEAD":
        out["detached"] = True
        return out
    if fetch:
        _run(p, "git", "fetch", "--quiet", timeout=180)
    upstream = _run(p, "git", "rev-parse", "--abbrev-ref", "@{u}").strip()
    commits = [ln for ln in _run(p, "git", "log", "--format=%h %cs %s", f"HEAD..{upstream}").splitlines() if ln.strip()]
    out.update(upstream=upstream, behind=len(commits), commits=commits[:30],
               ahead=int(_run(p, "git", "rev-list", "--count", f"{upstream}..HEAD").strip() or 0))
    return out


def update_steps(p: dict, save) -> list:
    """Шаги обновления для окна: проверка, сохранение мира (save(p) — backups.make с меткой before-update), git pull
    --ff-only, npm install --ignore-scripts — если поменялись package.json или package-lock.json. Базу L2Solo
    мигрирует сам при следующем запуске; откат — docs/L2SOLO.md, «Обновление». Журнал — data/l2solo/<id>.update.log."""
    state: dict = {}

    def check():
        if server_process(p):
            raise RuntimeError("L2Solo работает: код обновляется только у выключенного сервера. Останови его.")
        info = update_check(p)
        if info.get("detached"):
            raise RuntimeError(f"Код L2Solo откачен на {info['head']} — сначала вернись на ветку: git -C {p['server']} checkout main")
        if info["dirty"]:
            raise RuntimeError(f"В {p['server']} правки в их файлах ({', '.join(info['dirty'][:3])}): обновление их не трогает. "
                               "Убери правки или обнови руками (docs/L2SOLO.md)")
        if info["ahead"]:
            raise RuntimeError(f"В {p['server']} свои коммиты поверх {info['upstream']}: обновление только перемоткой, обнови руками")
        state.update(info, old=_run(p, "git", "rev-parse", "HEAD").strip())

    def backup():
        if state["behind"] and db_files(p)[0].is_file():
            state["save"] = save(p)["name"]

    def pull():
        if not state["behind"]:
            return
        _run(p, "git", "pull", "--ff-only", "--quiet", timeout=600)
        state["new"] = _run(p, "git", "rev-parse", "HEAD").strip()
        changed = _run(p, "git", "diff", "--name-only", state["old"], state["new"]).split()
        state["npm"] = bool({"package.json", "package-lock.json"} & set(changed))
        _cache.pop(("git", p["id"]), None)

    def npm():
        if state.get("npm") or not (server_dir(p) / "node_modules").is_dir():
            _run(p, _npm(p), "install", "--ignore-scripts", "--no-audit", "--no-fund", timeout=1800)
            state["npm"] = True
        line = (f"{datetime.now():%Y-%m-%d %H:%M:%S} | {state['old'][:8]} → {state.get('new', state['old'])[:8]} | "
                f"коммитов {state['behind']} | npm install {'да' if state.get('npm') else 'нет'} | "
                f"сохранение {state.get('save', '—')}\n")
        update_log_path(p).parent.mkdir(parents=True, exist_ok=True)
        with update_log_path(p).open("a", encoding="utf-8") as f:
            f.write(line)

    return [("Проверяю обновления L2Solo…", check), ("Сохраняю мир перед обновлением…", backup),
            ("Скачиваю новый код L2Solo (git pull)…", pull), ("Ставлю зависимости (npm install)…", npm)]


def update_history(p: dict, lines: int = 10) -> list[str]:
    f = update_log_path(p)
    return f.read_text(encoding="utf-8").splitlines()[-lines:] if f.is_file() else []


# ---------- Установка C4 с нуля (мастер «Установить» в окне; docs/L2SOLO.md, «Установка из окна») ----------

def pin() -> dict:
    """Закреплённый коммит L2Solo, их клиент и client mod (ссылки и отпечатки) — l2solo_pin.json."""
    return json.loads((paths.PACKAGE / "l2solo_pin.json").read_text(encoding="utf-8"))


def _node_ok(p: dict) -> bool:
    exe = node_exe(p)
    v = node_version(exe) if exe else None
    return bool(v and v >= NODE_MIN)


def _node_env(p: dict) -> dict:
    """Среда для npm и их скриптов: Node, которым ставим, — первым в PATH (npm.cmd и его дочерние процессы)."""
    exe = node_exe(p)
    return {**os.environ, "PATH": f"{exe.parent}{os.pathsep}{os.environ.get('PATH', '')}"} if exe else dict(os.environ)


def client_sources() -> dict:
    """Где взять клиент и мод (их Google Drive из README L2Solo): название, ссылка, файл, размер, SHA-256."""
    pn = pin()
    return {k: {f: pn[k][f] for f in ("title", "url", "file", "size", "sha256")} for k in ("client", "mod")}


def install_plan(template: dict) -> dict:
    """Что сделает установка C4 из шаблона профиля (profiles/c4.json программы): шаги словами и сколько скачать.
    Код L2Solo — git clone на закреплённом коммите (обновления из окна работают) или, без git, архив GitHub по SHA;
    зависимости npm, геодата C4 их же скриптом; клиент и мод не раздаём — client — где их взять."""
    pn = pin()
    p = {**template, "node": None}
    steps, size = [], 0
    if not _node_ok(p):
        node = runtimes.catalog()["node"]
        steps.append(f"{node['title']} — {node['size'] / 2**20:.0f} МБ с nodejs.org")
        size += node["size"]
    code = server_dir(p) / WRAPPER
    if not code.is_file():
        if shutil.which("git"):
            steps.append(f"код L2Solo с GitHub (git clone) — около {pn['sizes']['clone'] / 2**20:.0f} МБ, коммит {pn['commit'][:8]} от {pn['date']}")
            size += pn["sizes"]["clone"]
        else:
            steps.append(f"архив кода L2Solo с GitHub — около {pn['sizes']['archive'] / 2**20:.0f} МБ, коммит {pn['commit'][:8]} "
                         f"от {pn['date']}; без git обновлять L2Solo из окна нельзя — поставь Git, если нужно")
            size += pn["sizes"]["archive"]
    if not (server_dir(p) / "node_modules").is_dir():
        steps.append(f"зависимости npm (npm install --ignore-scripts) — около {pn['sizes']['npm'] / 2**20:.0f} МБ")
        size += pn["sizes"]["npm"]
    if geodata_count(p) < GEODATA_FILES:
        steps.append(f"геодата C4 с l2solo.com — {pn['sizes']['geodata'] / 2**20:.0f} МБ, распакуется около 940 МБ")
        size += pn["sizes"]["geodata"]
    steps.append("профиль хроники; клиент — следующим шагом: «Укажи папку клиента»")
    return {"ok": True, "build": f"L2Solo {pn['commit'][:8]} от {pn['date']}", "mods": True, "steps": steps, "download": size,
            "client": client_sources()}


def _fetch_code(p: dict, out) -> None:
    """Код L2Solo на закреплённом коммите: git clone и ветка на коммите (git pull потом обновит) или архив GitHub."""
    pn = pin()
    dst = server_dir(p)
    if (dst / WRAPPER).is_file():
        out(f"Код L2Solo уже на месте: {dst}")
        return
    if dst.exists() and any(dst.iterdir()):
        raise RuntimeError(f"{dst} не пустая, а L2Solo в ней нет — разберись руками, затирать не буду")
    dst.parent.mkdir(parents=True, exist_ok=True)
    if git := shutil.which("git"):
        out("Клонирую L2Solo с GitHub…")
        _exec([git, "clone", "--quiet", "--branch", pn["branch"], pn["repo"] + ".git", dst.name], dst.parent, timeout=1800)
        _exec([git, "reset", "--hard", "--quiet", pn["commit"]], dst, timeout=120)
        head = _exec([git, "rev-parse", "HEAD"], dst).strip()
        if head != pn["commit"]:
            raise RuntimeError(f"L2Solo: после клона HEAD {head}, а ждали {pn['commit']}")
        out(f"Код L2Solo: {pn['commit'][:8]} от {pn['date']} (ветка {pn['branch']}, обновления — из окна)")
        return
    import urllib.request  # noqa: PLC0415 — сеть нужна только установке
    import zipfile  # noqa: PLC0415

    archive = paths.HOME / "downloads" / f"L2Solo-{pn['commit'][:8]}.zip"
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        part = archive.with_suffix(".part")
        out("Скачиваю архив кода L2Solo с GitHub…")
        req = urllib.request.Request(f"{pn['repo']}/archive/{pn['commit']}.zip", headers={"User-Agent": "L2Hub"})
        with urllib.request.urlopen(req, timeout=60) as r, part.open("wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        part.replace(archive)
    with zipfile.ZipFile(archive) as z:
        commit = z.comment.decode("ascii", "replace").strip()
        if commit != pn["commit"]:
            raise RuntimeError(f"В {archive.name} коммит {commit or '(нет)'}, а ждали {pn['commit']}")
        top = z.namelist()[0].split("/", 1)[0] + "/"
        tmp = dst.with_name(f".{dst.name}.unpack")
        shutil.rmtree(tmp, ignore_errors=True)
        for name in z.namelist():
            if name.startswith(top) and not name.endswith("/"):
                to = tmp / name[len(top):]
                to.parent.mkdir(parents=True, exist_ok=True)
                to.write_bytes(z.read(name))
    if not (tmp / WRAPPER).is_file():
        shutil.rmtree(tmp, ignore_errors=True)
        raise RuntimeError(f"В {archive.name} нет {WRAPPER}")
    if dst.exists():
        dst.rmdir()  # пустая — проверено выше
    tmp.replace(dst)
    out(f"Код L2Solo: {pn['commit'][:8]} от {pn['date']} (из архива, без git)")


def install_profile(template: dict) -> dict:
    """Профиль C4 из шаблона программы: клиент — пустой (следующим шагом), LLM выключен, если ключа ещё нет."""
    prof = json.loads(json.dumps(template))
    prof["client"] = {**prof["client"], "path": ""}
    prof.pop("node", None)
    if prof.get("llm") and not (paths.DATA / "brain" / prof["llm"].get("keyFile", "deepseek.key")).is_file():
        prof["llm"]["enabled"] = False  # ключ кладёт пользователь; включить — пульт C4
    return prof


def install_steps(template: dict, out=print) -> list:
    """Шаги установки C4 для задачи окна (Api._start_job): Node, код, npm, геодата, профиль. Уже сделанное пропускается,
    поэтому прерванную установку можно повторить."""
    p = {**template, "node": None}

    def node():
        if not _node_ok(p):
            runtimes.install("node", out=out)

    def deps():
        if not (server_dir(p) / "node_modules").is_dir():
            _exec([_npm(p), "install", "--ignore-scripts", "--no-audit", "--no-fund"], server_dir(p), timeout=1800, env=_node_env(p))

    def geodata():
        if geodata_count(p) < GEODATA_FILES:
            _exec([node_exe(p), "scripts/ensure-geodata.js"], server_dir(p), timeout=3600, env=_node_env(p))
            _cache.pop(("geo", str(server_dir(p) / "data" / "Geodata")), None)
            if geodata_count(p) < GEODATA_FILES:
                raise RuntimeError(f"Геодата не встала: регионов {geodata_count(p)} из {GEODATA_FILES}")

    def profile():
        paths.PROFILES.mkdir(parents=True, exist_ok=True)
        (paths.PROFILES / f"{template['id']}.json").write_text(json.dumps(install_profile(template), ensure_ascii=False, indent=2) + "\n",
                                                                encoding="utf-8", newline="\n")

    return [("Ставлю Node.js…", node), ("Скачиваю код L2Solo…", lambda: _fetch_code(p, out)), ("Ставлю зависимости npm…", deps),
            ("Скачиваю геодату C4…", geodata), ("Сохраняю профиль хроники…", profile)]


def _sha256(path: Path) -> str:
    import hashlib  # noqa: PLC0415

    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def client_check(folder: str) -> dict:
    """Папка клиента C4: можно указать сам клиент или его system. ok — есть system\\L2.exe; l2solo — это их клиент
    (L2.exe их репака), mod — стоит client mod (C4Chat.dll). warn — что не так, но играть можно."""
    pn = pin()
    exe = pn["client"]["exe"]
    for root in (Path(folder), Path(folder).parent):
        if (root / exe).is_file():
            ours = _sha256(root / exe) in pn["client"]["exe_sha256"]
            mod = (root / pn["mod"]["marker"]).is_file()
            warn = []
            if not ours:
                warn.append("это не клиент L2Solo: L2Solo понимает только протокол 656, другой клиент C4 может не войти")
            if not mod:
                warn.append("нет L2Solo client mod: без него старое окно чата без кириллицы — поставь мод поверх клиента")
            return {"ok": True, "path": str(root), "exe": exe, "l2solo": ours, "mod": mod, "warn": "; ".join(warn) or None}
    return {"ok": False, "error": f"В {folder} нет {exe}: укажи папку клиента C4, где лежит system", "where": client_sources()}
