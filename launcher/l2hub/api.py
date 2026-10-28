"""Мост интерфейса и Python: всё, что вызывает окно через window.pywebview.api.

Долгие действия (запуск, остановка) идут фоновой задачей; интерфейс раз в секунду спрашивает
state() и показывает этап, прогресс и состояние компонентов.
"""

import hashlib
import ipaddress
import json
import os
import re
import shutil
import subprocess
import threading
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

import psutil

from . import __version__, appsettings, backups, brain, builder, catalog, clients, clientsys, configs, feedback, friendpack, geodata, health, i18n, l2solo, modes, network, paths, report, runtimes, servers, updates, worldtiles

CHAR_NAME_RE = re.compile(r"^[A-Za-z0-9_\-А-Яа-яЁё]{1,35}$")  # кириллица — для имён на La2offline
ACCESS_LEVELS = {0: "Игрок", 70: "Admin", 100: "Master"}  # Mobius; у другой сборки — "accessLevels" в профиле


def _access_levels(p: dict) -> dict[int, str]:
    return {int(k): v for k, v in p["accessLevels"].items()} if p.get("accessLevels") else ACCESS_LEVELS


# Охотники-фантомы: режим в PhantomHunters.ini (сервер перечитывает файл на ходу).
HUNTER_MODES = ("OFF", "ALWAYS", "WITH_PLAYERS")
HUNTERS_INI = "PhantomHunters.ini"
HUNTERS_STATUS = "L2HubHunters.json"
# Конфиги гейма, которые сервер перечитывает на ходу: остальные — только при старте (ConfigLoader).
LIVE_CONFIGS = {HUNTERS_INI}
# Мозги-стратег охотников (патч 0019): ключи того же PhantomHunters.ini; «выкл» — HunterStrategy = False.
STRATEGY_WHEN = ("WITH_PLAYERS", "ALWAYS")
# Числа стратега: подпись для ошибки, пределы и умолчание сервера (PhantomHuntersConfig).
STRATEGY_NUMBERS = {"HunterStrategyPerHour": ("решений в час", 1, 60, 6), "HunterStrategyPerMinute": ("запросов в минуту", 1, 60, 6),
                    "HunterStrategySayChance": ("шанс сказать вслух", 0, 100, 20)}
# Токенов на вход и выход на одно решение (docs/PHANTOMS.md, 4.3) — пока в usage.json мало запросов для своего среднего.
STRATEGY_TOKENS = (800, 30)
BRAIN_URL_RE = re.compile(r"^https?://[^\s/]+(/[^\s]*)?$")
L2SOLO_NO_FRIENDS = "Режим друзей для L2Solo пока не подключён: сервер слушает только этот ПК (127.0.0.1)."


def _ini_value(path, key: str, default: str = "") -> str:
    m = re.search(rf"^\s*{re.escape(key)}\s*=[ \t]*(.*?)\s*$", path.read_text(encoding="utf-8-sig"), flags=re.M)
    return m.group(1) if m else default


def _ini_values(path) -> dict:
    """Все «ключ = значение» файла; нет файла — пусто."""
    try:
        return dict(re.findall(r"^\s*([A-Za-z0-9_.]+)\s*=[ \t]*(.*?)\s*$", path.read_text(encoding="utf-8-sig"), flags=re.M))
    except OSError:
        return {}


def _tokens_per_request() -> tuple[int, int, int]:
    """Среднее токенов на вход и выход на запрос мозгов по data/brain/usage.json и число запросов; мало данных — оценка из STRATEGY_TOKENS."""
    try:
        days = json.loads(brain.USAGE_FILE.read_text(encoding="utf-8")).get("days", {})
    except (OSError, ValueError):
        days = {}
    rows = [row for day in days.values() for row in day.values()]
    requests = sum(r.get("requests", 0) for r in rows)
    if requests < 10:
        return (*STRATEGY_TOKENS, requests)
    return round(sum(r.get("in", 0) for r in rows) / requests), round(sum(r.get("out", 0) for r in rows) / requests), requests


def _strategy_info(p: dict, game, hunters: int) -> dict | None:
    """Настройки мозгов-стратега из PhantomHunters.ini и всё для оценки цены в час; сборка без патча 0019 — None."""
    custom = game / "config" / "Custom"
    v = _ini_values(custom / HUNTERS_INI)
    if "HunterStrategy" not in v:
        return None
    brain_on = (bool(servers.brain_port(p))
                and _ini_values(custom / "FakePlayers.ini").get("FakePlayerBrain", "True").lower() == "true"
                and _ini_values(custom / "PhantomParty.ini").get("PhantomBrain", "True").lower() == "true")
    s = brain.load_settings(p.get("brain", {}))
    tokens_in, tokens_out, requests = _tokens_per_request()
    when = v.get("HunterStrategyWhen", "WITH_PLAYERS").upper()
    out = {"when": "OFF" if v["HunterStrategy"].lower() != "true" else when if when in STRATEGY_WHEN else "WITH_PLAYERS",
           "brainOn": brain_on, "hunters": hunters, "rateCap": brain.STRATEGY_RATE_PER_MINUTE,
           "tokensIn": tokens_in, "tokensOut": tokens_out, "requests": requests,
           "mode": s["mode"], "priceIn": float(s.get("priceIn") or 0), "priceOut": float(s.get("priceOut") or 0)}
    for key, (*_, default) in STRATEGY_NUMBERS.items():
        try:
            out[key] = int(v.get(key, ""))
        except ValueError:
            out[key] = default
    return out


def _class_names(game) -> dict[int, str]:
    """classId → имя класса из данных сервера (stats/players/classList.xml)."""
    return {int(c.get("classId")): c.get("name") for c in ET.parse(game / "data" / "stats" / "players" / "classList.xml").getroot().iter("class")}


def _secret_phantoms(p: dict) -> set[str]:
    """Имена секретных фантомов (kind не hunter и не companion — Танатос, решение автора 2026-10-23): в окне их нет."""
    data = catalog.server_dir(p) / "game" / "data" / "L2HubPhantoms.xml"
    if not data.is_file():
        return set()
    return {el.get("name") for el in ET.parse(data).getroot().iter("phantom") if el.get("kind") not in (None, "hunter", "companion")}


def _hunters_status(game) -> dict | None:
    """Живое состояние охотников от сервера (game/log/L2HubHunters.json, пишется раз в 10 с, патч 0018); старше минуты — нет."""
    path = game / "log" / HUNTERS_STATUS
    try:
        if time.time() - path.stat().st_mtime > 60:
            return None
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _world_now(game) -> dict | None:
    """«Мир сейчас» от сервера (game/log/L2HubWorld.json, скрипт custom/L2HubWorldInfo, патч 0022, раз в минуту):
    живые суперчемпионы, сезонные события, ближайшие PvP-ивенты и эльпи. Старше 3 минут — нет: сервер не пишет."""
    path = game / "log" / "L2HubWorld.json"
    try:
        if time.time() - path.stat().st_mtime > 180:
            return None
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _player_rows(p: dict) -> list[tuple] | None:
    """Персонажи живых игроков хроники: (аккаунт, имя, уровень, в игре, последний вход в мс, последний IP).
    IP — из accounts, если логин хранит аккаунты в той же базе; базы хроники ещё нет — None."""
    chars = f"SELECT c.account_name, c.char_name, c.level, c.online, c.lastAccess, {{ip}} FROM characters c {{join}} WHERE c.{servers.not_phantom_sql(p)}"
    for ip, join in (("a.lastIP", "LEFT JOIN accounts a ON a.login = c.account_name"), ("''", "")):
        try:
            rows = servers.db_query(p, chars.format(ip=ip, join=join))
        except (RuntimeError, subprocess.TimeoutExpired):
            continue
        return [(acc, name, lvl, on == "1", int(last) if last.isdigit() else 0, "" if addr == "NULL" else addr)
                for acc, name, lvl, on, last, addr in rows]
    return None


def _where_from(ip: str) -> str:
    """Откуда заходили — по адресу последнего входа: этот ПК, Radmin VPN, Tailscale, локальная сеть."""
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return ""
    if a.is_loopback:
        return "с этого ПК"
    if a in ipaddress.ip_network("26.0.0.0/8"):
        return "Radmin VPN"
    if a in ipaddress.ip_network("100.64.0.0/10"):
        return "Tailscale"
    return "локальная сеть или ZeroTier" if a.is_private else ip


_tls = threading.local()  # задача, в потоке которой идёт шаг: job_note и job_cancelled — для долгих шагов (геодата)


def job_note(text: str, frac: float | None = None) -> None:
    """Подробность и доля внутри текущего шага задачи: «построено 37 из 233»."""
    if job := getattr(_tls, "job", None):
        job.detail = text
        if frac is not None:
            job.frac = max(0.0, min(1.0, frac))


def job_cancelled() -> bool:
    job = getattr(_tls, "job", None)
    return bool(job and job.cancelled)


class Job:
    def __init__(self, chronicle_id: str, action: str, steps: list):
        self.chronicle_id = chronicle_id
        self.action = action
        self.steps = steps          # [(текст этапа, функция)]
        self.index = 0
        self.step = ""
        self.detail = ""            # job_note: подробность внутри шага
        self.frac = 0.0             # job_note: доля шага
        self.cancelled = False      # Api.cancel_job: долгий шаг сам смотрит job_cancelled()
        self.error = None
        self.done = False

    def run(self):
        _tls.job = self
        try:
            for i, (label, fn) in enumerate(self.steps):
                self.index, self.step, self.detail, self.frac = i, label, "", 0.0
                fn()
        except Exception as e:  # noqa: BLE001 — ошибку показываем пользователю как есть
            self.error = str(e) or e.__class__.__name__
            if not isinstance(e, geodata.Cancelled):
                traceback.print_exc()
        finally:
            self.done = True

    def as_dict(self) -> dict:
        return {
            "chronicle": self.chronicle_id, "action": self.action,
            "step": f"{self.step} {self.detail}" if self.detail and not self.done else self.step,
            "progress": (self.index + (1 if self.done else self.frac)) / max(1, len(self.steps)),
            "error": self.error, "done": self.done, "cancelled": self.cancelled,
        }


class Api:
    def __init__(self):
        self._window = None  # приватное: pywebview не обходит поля с «_» при генерации JS-обвязки
        self._tray = None    # l2hub.tray.Tray, ставит __main__.py; в devserver его нет
        self._geometry = None  # последнее «обычное» место и размер окна — пишет __main__.py по событиям окна
        self._stop_requested_at = 0.0  # когда из L2Hub просили остановить: трей не принимает это за падение
        self._install_pins: dict = {}  # хроника → коммит Mobius, который показал install_plan: install ставит его же
        self._clients = None  # найденные на дисках клиенты (find_clients): обход долгий — один раз за сеанс
        self._job: Job | None = None
        self._update = None  # updates.check() за сеанс: GitHub не дёргаем на каждое открытие настроек
        self._fresh: dict = {}  # mobius_freshness: хроника → (когда спросили, ответ) — GitLab не чаще раза в час
        self._lock = threading.Lock()

    # ---------- Окно ----------

    def minimize(self):
        self._window.minimize()

    def toggle_maximize(self):
        # Состояние — у самой формы: окно разворачивают и Win+↑, и прилипание к краю, не только наша кнопка.
        if self._window_state() == "Maximized":
            self._window.restore()
        else:
            from . import winframe  # Win32 и WinForms — только в окне
            winframe.fit_maximized(self._window)  # по рабочей области, а не поверх панели задач
            self._window.maximize()

    def start_resize(self, edge: str):
        """Тянуть окно за край или угол (края .rz в index.html): у безрамочного окна нет своей рамки для этого."""
        if self._window_state() != "Normal":
            return
        from . import winframe
        winframe.start_resize(self._window, edge, self._window.min_size)

    def _window_state(self) -> str:
        """Normal, Maximized или Minimized — WindowState формы WinForms строкой (.NET-объекты в Api не держим)."""
        form = getattr(self._window, "native", None)
        return str(form.WindowState) if form is not None else "Normal"

    def close(self):
        self._save_window()
        if self._tray:
            self._tray.dispose()
        self._window.destroy()

    def hide_to_tray(self) -> dict:
        if not self._tray:
            return {"ok": False, "error": "Трея нет: L2Hub открыт не в своём окне."}
        self._save_window()
        self._tray.hide_window()
        return {"ok": True}

    def _save_window(self) -> None:
        try:
            if appsettings.load()["rememberWindow"] and self._geometry:
                appsettings.save({"window": {**self._geometry, "maximized": self._window_state() == "Maximized"}})
        except (OSError, ValueError):
            pass  # не запомнили окно — не повод не закрываться

    # ---------- Настройки L2Hub ----------

    def app_settings(self) -> dict:
        return {k: v for k, v in appsettings.load().items() if k != "window"}

    def save_app_settings(self, patch: dict) -> dict:
        try:
            saved = appsettings.save({k: v for k, v in patch.items() if k != "window"})
        except ValueError as e:
            return {"ok": False, "error": str(e)}
        return {"ok": True, "settings": {k: v for k, v in saved.items() if k != "window"}}

    # ---------- Каталог и состояние ----------

    def version(self) -> str:
        return __version__

    def about(self, news: int = 6) -> dict:
        """«О программе»: версия, план 1.0 из таблицы docs/CHRONICLES.md (там же решается «готова») и последние
        строки CHANGELOG.md — источники те же, что для людей, своей копии в коде нет."""
        plan, items = [], []
        try:
            text = (paths.DOCS / "CHRONICLES.md").read_text(encoding="utf-8")
            table = text.split("## Популярность и список хроник для 1.0", 1)[1]
            for row in re.findall(r"^\|\s*\d+\s*\|\s*([^|]+?)\s*\|[^|]*\|\s*([^|]+?)\s*\|\s*$", table, re.MULTILINE):
                plan.append({"name": row[0], "status": row[1].replace("**", ""), "ready": "**готова**" in row[1],
                             "playable": "играбельна" in row[1] or "**готова**" in row[1]})
        except (OSError, IndexError):
            pass
        try:
            log = paths.CHANGELOG.read_text(encoding="utf-8")
            for m in re.finditer(r"^- \*\*(\d+\.\d+\.\d+)\*\* · (\S+) — (.+)$", log, re.MULTILINE):
                items.append({"version": m.group(1), "date": m.group(2), "text": m.group(3)})
                if len(items) >= news:
                    break
        except OSError:
            pass
        return {"version": __version__, "plan": plan, "news": items}

    def catalog(self) -> list[dict]:
        return catalog.load_catalog()

    def profile(self, chronicle_id: str) -> dict:
        return catalog.profile(chronicle_id)

    def running(self) -> str | None:
        """Какая хроника сейчас запущена (по логину или гейму), если какая-то запущена."""
        for p in catalog.load_profiles().values():
            if servers.part_process(p, "game") or servers.part_process(p, "login"):
                return p["id"]
        return None

    def state(self, chronicle_id: str) -> dict:
        job = self._job.as_dict() if self._job else None
        p = catalog.load_profiles().get(chronicle_id)
        return {"components": health.state(p) if p else None, "job": job, "running": self.running()}

    def diagnostics(self, chronicle_id: str) -> list[dict]:
        return health.diagnostics(catalog.profile(chronicle_id))

    def selftest(self, chronicle_id: str) -> dict | None:
        """Самопроверка модов L2Hub для карточки «Обзора» (health.selftest); None — у сборки её нет."""
        return health.selftest(catalog.profile(chronicle_id))

    def log_tail(self, chronicle_id: str, part: str = "game", lines: int = 80) -> list[str]:
        return health.log_tail(catalog.profile(chronicle_id), part, lines)

    # ---------- Действия ----------

    def _start_job(self, chronicle_id: str, action: str, steps: list) -> dict:
        with self._lock:
            if self._job and not self._job.done:
                if self._job.action == "geodata":
                    return {"ok": False, "error": "Идёт геодата — дождись или останови её в «Обзоре» хроники."}
                return {"ok": False, "error": "Подожди: сервер ещё меняет состояние."}
            other = self.running()
            if action.startswith("start") and other and other != chronicle_id:
                return {"ok": False, "error": f"Сначала останови {other}: одновременно работает одна хроника."}
            self._job = Job(chronicle_id, action, steps)
            threading.Thread(target=self._job.run, daemon=True).start()
            return {"ok": True}

    def play(self, chronicle_id: str, with_client: bool = True, only_db: bool = False) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            if only_db:
                return {"ok": False, "error": "У L2Solo база встроена в сервер (SQLite): отдельно она не запускается."}
            return self._start_job(chronicle_id, "start", l2solo.play_steps(p, with_client, backups.auto))
        steps = [("Запускаю базу…", lambda: servers.start_db(p))]
        # Бэкап перед запуском: дамп MariaDB — когда она уже поднята, папку La2offline — пока его база выключена.
        steps.insert(0 if servers.own_db(p) else 1, ("Бэкап базы…", lambda: backups.auto(p)))
        if not only_db and servers.brain_port(p):
            steps.append(("Будим мозги фейков…", lambda: servers.start_brain(p)))
        if not only_db and p.get("type") == "mobius":
            # До старта гейма: L2HubLanguage и «Расширенное обдумывание» (HunterMindExtended) — в конфиги модов.
            steps.append(("Язык модов в игре…", lambda: (modes.sync_language(p, i18n.lang()), modes.sync_mind(p))))
        if not only_db and network.state(p).get("kind") == "internet":
            # Роутер мог забыть проброс после перезагрузки. Нет UPnP — порты проброшены вручную, молча дальше.
            steps.append(("Порты на роутере…", lambda: network.internet_ports(True)))
        if not only_db:
            steps += [
                ("Запускаю логин-сервер…", lambda: servers.start_part(p, "login")),
                ("Гейм-сервер грузит мир…", lambda: servers.start_part(p, "game")),
            ]
        if with_client and not only_db:
            steps.append(("Запускаю клиент…", lambda: servers.start_client(p)))
        return self._start_job(chronicle_id, "start", steps)

    def stop(self, chronicle_id: str, stop_db: bool = True) -> dict:
        self._stop_requested_at = time.monotonic()
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return self._start_job(chronicle_id, "stop", l2solo.stop_steps(p))
        steps = [
            ("Сохраняю мир и останавливаю гейм…", lambda: servers.stop_part(p, "game")),
            ("Останавливаю логин…", lambda: servers.stop_part(p, "login")),
            ("Закрываю клиент…", lambda: servers.stop_client(p)),
        ]
        if servers.brain_port(p):
            steps.append(("Усыпляю мозги фейков…", lambda: servers.stop_brain(p)))
        if stop_db:
            steps.append(("Останавливаю базу…", lambda: servers.stop_db(p)))
        return self._start_job(chronicle_id, "stop", steps)

    def restart_game(self, chronicle_id: str) -> dict:
        self._stop_requested_at = time.monotonic()
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return self._start_job(chronicle_id, "restart", l2solo.restart_steps(p))
        return self._start_job(chronicle_id, "restart", [
            ("Сохраняю мир и останавливаю гейм…", lambda: servers.stop_part(p, "game")),
            ("Язык модов в игре…", lambda: (modes.sync_language(p, i18n.lang()), modes.sync_mind(p))),
            ("Гейм-сервер грузит мир…", lambda: servers.start_part(p, "game")),
        ])

    def launch_client(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        if not servers.part_process(p, "game"):
            return {"ok": False, "error": "Гейм-сервер не запущен."}
        try:
            return {"ok": True, "pid": servers.start_client(p)}
        except RuntimeError as e:
            return {"ok": False, "error": str(e)}

    def kill(self, chronicle_id: str) -> dict:
        self._stop_requested_at = time.monotonic()
        return {"ok": True, "killed": servers.kill_all(catalog.profile(chronicle_id))}

    # ---------- Карта мира ----------

    def world_map(self, chronicle_id: str, offline: bool = False) -> dict:
        """Точки на карте мира: охотники, лавки, напарники, живые игроки. Координаты — из L2HubHunters.json сервера,
        когда он их пишет (x, y у охотника, массивы players и companions), иначе — из базы: позиция на последнем
        сохранении персонажа; offline — и те, кто не в игре (где вышли). Подложка — ui/maps (tools/world_map_tiles.py)."""
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return {"ok": False, "error": "У C4 своя карта — карта мира L2Solo в «Мой мир»."}
        worldtiles.sync()  # подложка из папки данных — в ui/maps, если её стёрло обновление программы
        index = worldtiles.UI_MAPS / "index.json"
        tiles = json.loads(index.read_text(encoding="utf-8")) if index.is_file() else None
        game = catalog.server_dir(p) / "game"
        status = _hunters_status(game) if not servers.bundle(p) else None
        hunters = {h["name"]: h for h in (status or {}).get("hunters", [])}
        kinds, secret = {}, set()
        data = game / "data" / "L2HubPhantoms.xml"
        if data.is_file():
            # Фантомы не «hunter» и не напарники (kind="reaper" — Танатос, решение автора 2026-10-23) — секретные: на карте их нет.
            for el in ET.parse(data).getroot().iter("phantom"):
                kind = el.get("kind", "companion")
                if kind in ("hunter", "companion"):
                    kinds[el.get("name")] = kind
                else:
                    secret.add(el.get("name"))
        points, live = {}, False
        db = servers.db_running(p["ports"]["db"])
        if db:
            account = servers.phantom_account(p)
            where = "" if offline else " WHERE online=1"
            for name, level, cls, x, y, acc, online in servers.db_query(p, f"SELECT char_name, level, classid, x, y, account_name, online FROM characters{where}"):
                if name in secret:
                    continue
                kind = kinds.get(name) or ("companion" if acc == account else "player")
                points[name] = {"kind": kind, "name": name, "level": int(level), "classId": int(cls), "x": int(x), "y": int(y),
                                "live": False, "online": online == "1"}
        for name, h in hunters.items():  # сервер знает охотников лучше базы: фаза, спот, лавка, а с координатами — и место
            if name in secret or h.get("secret"):
                continue
            pt = points.setdefault(name, {"kind": "hunter", "name": name, "level": h.get("level"), "classId": h.get("classId"), "live": False})
            pt.update(phase=h.get("phase"), spot=h.get("spot"), town=h.get("town"), party=h.get("party"), activity=h.get("activity"),
                      kind="store" if h.get("phase") == "STORE" else "hunter")
            if "x" in h and "y" in h:
                pt.update(x=h["x"], y=h["y"], live=True)
                live = True
        world = _world_now(game) if not servers.bundle(p) else None
        for sc in ((world or {}).get("superChampions") or {}).get("alive") or []:  # суперчемпионы — с карты «Мир сейчас»
            if sc.get("x") or sc.get("y"):
                points[f"sc:{sc.get('objectId')}"] = {"kind": "superchampion", "name": sc.get("name"), "level": sc.get("level"), "x": sc["x"], "y": sc["y"],
                                                     "town": sc.get("town"), "since": sc.get("since"), "live": True}
                live = True
        for rb in (world or {}).get("raidBosses") or []:  # живые рейд-боссы и эпики — оттуда же
            if rb.get("alive") and (rb.get("x") or rb.get("y")):
                points[f"rb:{rb.get('npcId')}"] = {"kind": "epic" if rb.get("grand") else "raidboss", "name": rb.get("name"), "level": rb.get("level"), "x": rb["x"], "y": rb["y"],
                                                  "town": rb.get("town"), "grand": bool(rb.get("grand")), "live": True}
        for key, kind in (("players", "player"), ("companions", "companion")):
            for o in (status or {}).get(key, []):
                if "x" in o and "y" in o:
                    points.setdefault(o["name"], {"kind": kind, "name": o["name"]}).update({**o, "kind": kind, "live": True})
                    live = True
        items = [pt for pt in points.values() if "x" in pt and "y" in pt and (pt["x"] or pt["y"])]
        return {"ok": True, "tiles": tiles, "points": items, "live": live, "db": db, "running": self.running() == chronicle_id,
                "note": None if tiles else "Подложки карты нет — её можно скачать: картинки карты мира из клиента Lineage II, собранные сообществом "
                                           f"({worldtiles.PAGE}, лицензии у них нет, поэтому в L2Hub она не входит). Около 170 МБ, соберётся у тебя за минуту-две.",
                "tilesSource": None if tiles else worldtiles.PAGE}

    def world_chronicle(self, chronicle_id: str) -> dict:
        """«Летопись» мира (game/log/L2HubChronicle.json, патч 0023 c4-interlude): последние ~200 заметных событий —
        убитые РБ и суперчемпионы, эпики, итоги PvP-ивентов, сезонные события, эльпи, уровни и профессии. История —
        без срока годности: файл переживает перезапуск сервера."""
        p = catalog.profile(chronicle_id)
        path = catalog.server_dir(p) / "game" / "log" / "L2HubChronicle.json"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {"ok": True, "entries": None, "note": "Летописи у этой сборки пока нет: её пишет сервер с модом «Летопись» (сейчас — Interlude)."}
        except (OSError, ValueError) as e:
            return {"ok": False, "error": f"Не прочитать летопись: {e}"}
        return {"ok": True, "entries": data.get("entries") or [], "time": data.get("time")}

    def world_tiles_get(self) -> dict:
        """«Скачать подложку» в «Мой мир»: архив npetrovski/l2-world-map → data/maps и ui/maps (worldtiles.py)."""
        return self._start_job("", "maps", [("Подложка карты:", lambda: worldtiles.install(job_note, job_cancelled))])

    # ---------- Модуль фантомов (phantoms/, phantoms/README.md) ----------

    def phantoms_status(self) -> dict:
        """Где стоит модуль фантомов и свежий ли он в сборке: хэши файлов phantoms/core и phantoms/<id> (java, dist)
        против servers/<id>/.module_manifest.json, который пишут builder.py и scripts/server_build.ps1 (SHA-256, как Get-FileHash)."""
        root = paths.PHANTOMS

        def hashes(d: Path) -> dict[str, str]:
            out = {}
            for top in ("java", "dist"):
                for f in (d / top).rglob("*") if (d / top).is_dir() else ():
                    if f.is_file():
                        out[f.relative_to(d).as_posix()] = hashlib.sha256(f.read_bytes()).hexdigest().upper()
            return out

        core = hashes(root / "core")
        items = []
        for c in catalog.load_catalog():
            if not c.get("plan"):
                continue
            row = {"id": c["id"], "name": c["name"], "server": c["server"], "build": c.get("build"), "installed": c["installed"]}
            mod = root / c["id"]
            if c["server"] == "own":
                row.update(state="no", why="своя сборка без исходников — модуль под неё не поставить")
            elif c["server"] == "l2solo":
                row.update(state="no", why="чужой код, его не патчим; у L2Solo свои боты")
            elif not mod.is_dir():
                row.update(state="plan", why="Mobius — модуль можно перенести" if c["server"] == "mobius" else "сервера пока нет")
            else:
                files = {**core, **hashes(mod)}
                glue = mod / "glue.patch"
                row.update(world=True, data=len(list((mod / "dist").rglob("*.*"))) if (mod / "dist").is_dir() else 0,
                           glue=len(re.findall(r"^\+\+\+ b/", glue.read_text(encoding="utf-8", errors="replace"), re.MULTILINE)) if glue.is_file() else 0)
                manifest = paths.SERVERS / c["id"] / ".module_manifest.json"
                if not manifest.is_file():
                    row.update(state="notbuilt", why="модуль есть, в сборку сервера ещё не ставился")
                else:
                    built = json.loads(manifest.read_text(encoding="utf-8-sig"))
                    changed = [k for k, v in files.items() if built.get(k) != v]
                    removed = [k for k in built if k not in files]
                    row.update(builtAt=time.strftime("%Y-%m-%d %H:%M", time.localtime(manifest.stat().st_mtime)),
                               changed=len(changed) + len(removed), changedFiles=(changed + removed)[:8],
                               state="fresh" if not changed and not removed else "stale",
                               why="совпадает с phantoms/" if not changed and not removed else "в phantoms/ правки после сборки")
            items.append(row)
        return {"coreFiles": len(core), "items": items}

    def rebuild_module(self, chronicle_id: str) -> dict:
        """Пересобрать сервер с модулем фантомов — builder.server_build (то же, что scripts/server_build.ps1 из консоли,
        но без PowerShell, 7-Zip и git). Сервер должен быть выключен: сборка меняет его jar и данные.
        Вывод — data/logs/server_build_<id>.log."""
        p = catalog.profile(chronicle_id)
        if servers.part_process(p, "game") or servers.part_process(p, "login"):
            return {"ok": False, "error": "Сначала останови сервер этой хроники: сборка меняет его jar и данные."}
        log = paths.DATA / "logs" / f"server_build_{chronicle_id}.log"

        def build():
            log.parent.mkdir(parents=True, exist_ok=True)
            with log.open("w", encoding="utf-8") as f:
                def out(line: str) -> None:
                    f.write(line + "\n")
                    f.flush()
                try:
                    builder.server_build(p, out)
                except Exception as e:
                    out(f"ОШИБКА: {e}")
                    raise RuntimeError(f"сборка не прошла: {e} — лог {log}") from None
        return self._start_job(chronicle_id, "build", [("Собираю сервер с модулем фантомов…", build)])

    # ---------- Установка хроники (мастер, docs/RELEASE.md) ----------

    def install_plan(self, chronicle_id: str, own: bool = False) -> dict:
        """Что сделает «Установить»: шаги и сколько скачать. Любая хроника со сборкой Mobius: из плана 1.0 — с модами
        L2Hub по шаблону профиля (profiles/<id>.json рядом с программой) на закреплённом коммите, остальные — сервер
        Mobius как есть на последнем коммите своей папки; он закрепляется в профиле ("source"). own — «Подключить свою
        сборку» (любая хроника; у хроник без сервера в каталоге — единственный путь): сборка-комбайн из bundles.json.
        У установленной хроники own — второй сервер рядом: вариант <хроника>_<вид> (у C4 — L2Solo и L2JLisvus)."""
        if chronicle_id in catalog.load_profiles() and not own:
            return {"ok": False, "error": "Хроника уже установлена."}
        c = next((x for x in catalog.load_catalog() if x["id"] == chronicle_id), None)
        place = {"home": str(paths.HOME), "homeBad": paths.check_home(paths.HOME), "free": shutil.disk_usage(paths.HOME.anchor).free,
                 "ramGB": round(psutil.virtual_memory().total / 2**30)}
        if c and (own or c["server"] in ("own", "none")):
            # Чужие сборки не раздаём: пользователь приносит свою, лаунчер делает копию и запускает её без её лаунчера.
            kinds = [k["title"] + (f" ({k['examples']})" if k.get("examples") else "") for k in servers.bundle_kinds().values()]
            return {"ok": True, "folder": "bundle", "build": "своя сборка", "mods": False, "download": 0, "kinds": kinds,
                    "steps": ["копия папки server твоей сборки — оригинал не трогаем",
                              "локальный режим по описанию сборки: сервер слушает только этот ПК",
                              "профиль: имя базы и порты — из конфигов самой сборки"], **place}
        if c and c["server"] == "l2solo":
            # C4 — L2Solo (их код не патчим): Node, код на закреплённом коммите, npm, геодата — l2solo.install_plan.
            template = paths.APP / "profiles" / f"{chronicle_id}.json"
            if not template.is_file():
                return {"ok": False, "error": f"Нет шаблона профиля {template.name} в программе."}
            return {**l2solo.install_plan(json.loads(template.read_text(encoding="utf-8"))), **place}
        if not c or c["server"] != "mobius" or not c.get("build"):
            return {"ok": False, "error": "Для этой хроники сервера пока нет."}
        template = paths.APP / "profiles" / f"{chronicle_id}.json"
        mods = chronicle_id in builder.pins()["chronicles"] and template.is_file()
        try:
            pin = builder.source_pin(chronicle_id, c["build"])
        except Exception as e:  # noqa: BLE001 — сеть, GitLab: показать как есть
            return {"ok": False, "error": f"Не узнал свежий коммит {c['build']} на GitLab: {e}"}
        self._install_pins[chronicle_id] = pin
        missing = [r for r in runtimes.status() if not r["installed"]]
        has_src = (paths.SERVERS / chronicle_id / pin["build"]).is_dir()
        steps = [f"{r['title']} — {r['size'] / 2**20:.0f} МБ" for r in missing]
        if not has_src:
            steps.append(f"исходники {pin['build']} с GitLab — около 25 МБ, коммит {pin['commit'][:8]} от {pin['date']}")
        steps += ["сборка сервера (Ant) — несколько минут"] + ([] if paths.DB_DIR.exists() else ["своя база MariaDB, только на этом ПК"])
        steps += ["база хроники и локальный режим сервера"]
        return {"ok": True, "build": pin["build"], "mods": mods, "steps": steps,
                "download": sum(r["size"] for r in missing) + (0 if has_src else 25 * 2**20), **place}

    def install(self, chronicle_id: str, folder: str | None = None) -> dict:
        """Установить хронику по плану install_plan; folder — папка своей сборки («Подключить свою сборку»)."""
        c = next((x for x in catalog.load_catalog() if x["id"] == chronicle_id), None)
        own = bool(folder) or bool(c and c["server"] in ("own", "none"))
        plan = self.install_plan(chronicle_id, own) if own or chronicle_id not in self._install_pins else None
        if plan and not plan["ok"]:
            return plan
        if chronicle_id in catalog.load_profiles() and not own:
            return {"ok": False, "error": "Хроника уже установлена."}
        if bad := paths.check_home(paths.HOME):
            return {"ok": False, "error": bad}
        if self.running():
            return {"ok": False, "error": "Сначала останови запущенную хронику: установка поднимает базу."}
        if own and chronicle_id in catalog.load_profiles():
            # Хроника уже стоит — своя сборка встаёт рядом вариантом: свой профиль, своя база, своя строка в списке.
            found = builder.detect_bundle(folder) if folder else None
            if not found:
                return self._install_own(chronicle_id, folder)  # объяснит, чего не хватает
            base = (c or {}).get("base") or chronicle_id
            variant = f"{base}_{found[0]}"
            if variant in catalog.load_profiles():
                return {"ok": False, "error": f"Вариант {variant} уже установлен."}
            return self._install_own(variant, folder, chronicle=base)
        if own:
            return self._install_own(chronicle_id, folder)
        if c and c["server"] == "l2solo":
            template = json.loads((paths.APP / "profiles" / f"{chronicle_id}.json").read_text(encoding="utf-8"))
            return self._start_job(chronicle_id, "install", l2solo.install_steps(template))
        pin = self._install_pins[chronicle_id]
        template = paths.APP / "profiles" / f"{chronicle_id}.json"
        mods = chronicle_id in builder.pins()["chronicles"] and template.is_file()
        base = {"id": chronicle_id, "type": "mobius"}
        prof: dict = {}
        steps = [(f"Скачиваю {r['title']}…", lambda rid=r["id"]: runtimes.install(rid))
                 for r in runtimes.status() if not r["installed"]]
        if not (paths.SERVERS / chronicle_id / pin["build"]).is_dir():
            steps.append(("Скачиваю исходники Mobius…", lambda: builder.fetch_sources(chronicle_id, pin=pin)))
        steps.append(("Собираю сервер — несколько минут…", lambda: builder.server_build(base)))

        def make_profile():
            if mods:
                t = json.loads(template.read_text(encoding="utf-8"))
                prof.update(t, client={**t["client"], "path": ""})  # клиент — следующим шагом: «Укажи папку клиента»
            else:
                taken = {q.get("database") for q in catalog.load_profiles().values()}
                if builder.database_name(chronicle_id) in taken:
                    builder.rename_database(chronicle_id, f"l2hub_{chronicle_id}")
                prof.update(builder.generic_profile(chronicle_id, pin["build"], pin))
        steps.append(("Готовлю профиль хроники…", make_profile))
        db_was_up = servers.db_running()
        if not paths.DB_DIR.exists():
            steps.append(("Создаю базу MariaDB…", builder.db_init))
        steps += [("Запускаю базу…", lambda: servers.start_db(base)),
                  ("Заливаю базу хроники и настраиваю сервер…", lambda: builder.server_setup(base))]

        def save_profile():
            paths.PROFILES.mkdir(parents=True, exist_ok=True)
            (paths.PROFILES / f"{chronicle_id}.json").write_text(json.dumps(prof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
            if not db_was_up:
                servers.stop_db(base)
        steps.append(("Сохраняю профиль хроники…", save_profile))
        return self._start_job(chronicle_id, "install", steps)

    def _install_own(self, chronicle_id: str, folder: str | None, chronicle: str | None = None) -> dict:
        """«Подключить свою сборку»: копия, локальный режим и профиль по описанию вида из bundles.json.
        chronicle — хроника, к которой встаёт вариант сервера (профиль chronicle_id = <хроника>_<вид>)."""
        found = builder.detect_bundle(folder) if folder else None
        if not found:
            known = "; ".join(k["title"] for k in servers.bundle_kinds().values())
            return {"ok": False, "error": f"В {folder or 'этой папке'} нет сборки, которую L2Hub знает. Знает: {known}. "
                                          "Другие семейства L2J — следующими шагами."}
        kid, src = found
        kind = servers.bundle_kinds()[kid]
        prof: dict = {}

        def save_profile():
            if chronicle:
                prof["chronicle"] = chronicle
            paths.PROFILES.mkdir(parents=True, exist_ok=True)
            (paths.PROFILES / f"{chronicle_id}.json").write_text(json.dumps(prof, ensure_ascii=False, indent=2) + "\n",
                                                                 encoding="utf-8", newline="\n")
        if kind.get("runtime") != "ours":
            # У сборки-комбайна своя база, но онлайн, персонажей и права GM лаунчер читает клиентом MariaDB из рантаймов.
            steps = [("Скачиваю MariaDB (её клиентом лаунчер читает базу сборки)…", lambda: runtimes.install("mariadb"))
                     for r in runtimes.status() if r["id"] == "mariadb" and not r["installed"]]
            steps += [("Копирую сервер сборки…", lambda: prof.update(builder.import_bundle(chronicle_id, folder))),
                      ("Сохраняю профиль хроники…", save_profile)]
            return self._start_job(chronicle_id, "install", steps)

        # Сборка на нашей Java и MariaDB: байткод должен быть не новее нашей JDK — иначе не запустится.
        need, ours = builder.jar_java(src / kind["jar"]) if kind.get("jar") else None, builder.runtime_java()
        if need and ours and need > ours:
            return {"ok": False, "error": f"Сборке нужна Java {need}, а у L2Hub — Java {ours}: такую сборку пока не запустить."}
        base = {"id": chronicle_id, "type": "mobius"}
        db_was_up = servers.db_running()
        steps = [(f"Скачиваю {r['title']}…", lambda rid=r["id"]: runtimes.install(rid)) for r in runtimes.status() if not r["installed"]]
        steps.append(("Копирую сервер сборки…", lambda: prof.update(builder.import_bundle(chronicle_id, folder))))

        mobius = kind.get("setup") == "mobius"

        def own_database():
            taken = {q.get("database") for q in catalog.load_profiles().values()}
            if mobius and prof["database"] in taken:
                builder.rename_database(chronicle_id, f"l2hub_{chronicle_id}")
                prof["database"] = f"l2hub_{chronicle_id}"
        steps.append(("Проверяю имя базы…", own_database))
        if not paths.DB_DIR.exists():
            steps.append(("Создаю базу MariaDB…", builder.db_init))
        # Mobius — как свежая сборка из исходников; другие виды (Lisvus) — по правилам описания вида.
        steps += [("Запускаю базу…", lambda: servers.start_db(base)),
                  ("Заливаю базу хроники и настраиваю сервер…", lambda: builder.server_setup(base) if mobius else builder.setup_rules(prof))]

        def shared_geodata():
            # Вариант сервера хроники: своей геодаты у сборки нет, а у основного сервера той же хроники она есть
            # (у C4 — скачанная для L2Solo) — тот же формат XX_YY.l2j, путь прописывается в конфиг сборки.
            spec = kind.get("geodata")
            main = catalog.load_profiles().get(chronicle) if chronicle else None
            if not spec or not main or any(servers.geodata_dir(prof).glob("*.l2j")):
                return
            src_dir = servers.geodata_dir(main)
            if any(src_dir.glob("*.l2j")):
                builder.set_key(catalog.server_dir(prof) / spec[0], spec[1], src_dir.as_posix())
        steps.append(("Геодата хроники…", shared_geodata))

        def finish():
            save_profile()
            if not db_was_up:
                servers.stop_db(base)
        steps.append(("Сохраняю профиль хроники…", finish))
        return self._start_job(chronicle_id, "install", steps)

    def l2solo_client_open(self, what: str) -> dict:
        """Страница их раздачи клиента C4 или client mod (ссылки из l2solo_pin.json) — в браузере. Мы не раздаём."""
        src = l2solo.client_sources().get(what)
        if not src:
            return {"ok": False, "error": f"Нет такой раздачи: {what}"}
        os.startfile(src["url"])  # noqa: S606 — адрес из нашего l2solo_pin.json
        return {"ok": True}

    def pick_folder(self) -> dict:
        """Окно выбора папки Windows — для мастера (папка La2offline); в devserver нет."""
        if not self._window:
            return {"ok": False, "error": "Выбор папки — только в окне L2Hub; в дев-режиме впиши путь руками."}
        import webview  # noqa: PLC0415 — только в окне

        picked = self._window.create_file_dialog(webview.FileDialog.FOLDER)
        return {"ok": True, "path": picked[0]} if picked else {"ok": False, "cancelled": True}

    def find_clients(self, refresh: bool = False) -> dict:
        """Клиенты Lineage II на локальных дисках (clients.py) — для «указать клиент»; обход — до 25 с, кэш на сеанс окна."""
        if refresh or self._clients is None:
            self._clients = clients.find()
        return {"ok": True, **self._clients}

    def pick_client(self, chronicle_id: str) -> dict:
        """Папка клиента через окно выбора папки Windows (только в окне, не в devserver)."""
        if not self._window:
            return {"ok": False, "error": "Выбор папки — только в окне L2Hub."}
        import webview  # noqa: PLC0415 — только в окне

        picked = self._window.create_file_dialog(webview.FileDialog.FOLDER)
        if not picked:
            return {"ok": False, "cancelled": True}
        return self.set_client_path(chronicle_id, picked[0])

    def set_client_path(self, chronicle_id: str, folder: str) -> dict:
        """Записать в профиль папку клиента. Можно указать и сам клиент, и его system: ищем exe профиля
        (system\\L2.exe и т. п.), затем L2.bin — у части клиентов вместо L2.exe."""
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            # Клиент C4 — их (протокол 656) с их client mod: l2solo.client_check узнаёт его по отпечатку и предупреждает.
            r = l2solo.client_check(folder)
            if r["ok"]:
                data = json.loads((paths.PROFILES / f"{chronicle_id}.json").read_text(encoding="utf-8"))
                data["client"].update(path=r["path"], exe=r["exe"])
                (paths.PROFILES / f"{chronicle_id}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
            return r
        if clientsys.is_4game(p):
            return self._set_4game_client(chronicle_id, p, Path(folder))
        exe = p["client"]["exe"]
        here = Path(folder)
        # Указали саму папку системы с L2.exe (system, La2system, system_ru…) — она и есть система клиента, и она
        # главнее exe из профиля: рядом в клиенте может лежать другая система (у La2offline — штатная system NCSoft).
        own_system = [(here.parent, f"{here.name}\\{name}") for name in ("L2.exe", "L2.bin") if (here / name).is_file()]
        tries = own_system + [(root, cand) for root in (here, here.parent) for cand in (exe, str(Path(exe).with_name("L2.bin")))]
        for root, candidate in tries:
            if (root / candidate).is_file():
                data = json.loads((paths.PROFILES / f"{chronicle_id}.json").read_text(encoding="utf-8"))
                data["client"].update(path=str(root), exe=candidate)
                # LF, как в репозитории: у автора профили — в git.
                (paths.PROFILES / f"{chronicle_id}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
                return {"ok": True, "path": str(root), "exe": candidate}
        return {"ok": False, "error": f"В {folder} нет {exe}: укажи папку клиента, где лежит system."}

    def _write_client(self, chronicle_id: str, **fields) -> None:
        path = paths.PROFILES / f"{chronicle_id}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["client"].update(fields)
        # LF, как в репозитории: у автора профили — в git.
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

    def _set_4game_client(self, chronicle_id: str, p: dict, here: Path) -> dict:
        """Клиент 4game (Classic, Essence): папка клиента или его системы. Система — та, что в профиле (system_ru
        у русского), если она есть в этом клиенте, иначе штатная system; L2Hub.ini лаунчер делает сам из её L2.ini."""
        if (here / "L2.exe").is_file() and (here / "L2.ini").is_file():
            root, system = here.parent, here.name
        else:
            root = here
            want = Path(p["client"].get("exe") or "system\\L2.exe").parent.name or "system"
            system = want if (here / want / "L2.exe").is_file() else "system"
        if not (root / system / "L2.exe").is_file():
            return {"ok": False, "error": f"В {here} нет system\\L2.exe — укажи папку клиента 4game, где лежит system."}
        try:
            clientsys.ensure_ini(root / system)
        except (OSError, ValueError) as e:
            return {"ok": False, "error": f"Не получилось сделать {clientsys.INI}: {e}"}
        fields = {"path": str(root), "exe": f"{system}\\L2.exe", "ini": f"{system}\\{clientsys.INI}"}
        if fields["exe"] != p["client"].get("exe"):
            fields["system"] = clientsys.describe(system)
        self._write_client(chronicle_id, **fields)
        return {"ok": True, "path": str(root), "exe": f"{system}\\L2.exe"}

    def client_language(self, chronicle_id: str) -> dict:
        """Язык клиента 4game: system (EU, английский) или system_ru (русский; clientsys.make_system_ru)."""
        p = catalog.profile(chronicle_id)
        if not clientsys.is_4game(p):
            return {"ok": True, "supported": False}
        c = p["client"]
        root = Path(c.get("path") or "")
        return {"ok": True, "supported": True, "client": bool(c.get("path")), "current": clientsys.language(Path(c["exe"]).parent.name),
                "ruReady": bool(c.get("path")) and (root / "system_ru" / "L2.exe").is_file()}

    def set_client_language(self, chronicle_id: str, lang: str) -> dict:
        p = catalog.profile(chronicle_id)
        if lang not in ("ru", "en") or not clientsys.is_4game(p):
            return {"ok": False, "error": "Язык выбирается только у клиентов 4game (Classic, Essence): ru или en"}
        if not p["client"].get("path"):
            return {"ok": False, "error": "Сначала укажи папку клиента"}
        root = Path(p["client"]["path"])
        system = "system_ru" if lang == "ru" else "system"
        steps = []
        if lang == "ru" and not (root / system / "L2.exe").is_file():
            # Копия штатной system с языком Russia: ввод кириллицей, тексты — Europe (своих русских у EU-клиента нет).
            steps.append(("Делаю system_ru — копию системы с русским языком…", lambda: clientsys.make_system_ru(root, base="Europe", out=job_note)))
        steps += [(f"Готовлю {clientsys.INI}…", lambda: clientsys.ensure_ini(root / system)),
                  ("Записываю профиль…", lambda: self._write_client(chronicle_id, exe=f"{system}\\L2.exe", ini=f"{system}\\{clientsys.INI}",
                                                                    system=clientsys.describe(system)))]
        return self._start_job(chronicle_id, "client", steps)

    # ---------- Персонажи и права GM ----------

    def characters(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return l2solo.characters(p)
        if not servers.db_running(p["ports"]["db"]):
            return {"ok": False, "error": "База выключена — запусти сервер или «Только базу»."}
        rows = servers.db_query(p, f"SELECT char_name, account_name, level, accesslevel, online FROM characters WHERE {servers.not_phantom_sql(p)} ORDER BY lastAccess DESC")
        return {"ok": True, "levels": _access_levels(p), "items": [
            {"name": n, "account": a, "level": int(lvl), "access": int(acc), "online": online == "1"} for n, a, lvl, acc, online in rows]}

    def set_access(self, chronicle_id: str, char_name: str, level: int) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return {"ok": False, "error": "В L2Solo права GM не нужны: админка .admin открыта всем."}
        level = int(level)
        if not CHAR_NAME_RE.match(char_name) or level not in _access_levels(p):
            return {"ok": False, "error": "Неверное имя персонажа или уровень."}
        rows = servers.db_query(p, f"SELECT online FROM characters WHERE char_name='{char_name}'")
        if not rows:
            return {"ok": False, "error": f"Персонажа {char_name} нет."}
        if rows[0][0] == "1":
            return {"ok": False, "error": f"{char_name} в игре: сервер затрёт права при сохранении. Выйди персонажем и повтори."}
        servers.db_query(p, f"UPDATE characters SET accesslevel={level} WHERE char_name='{char_name}'")
        return {"ok": True}

    # ---------- Охотники-фантомы ----------

    def hunters(self, chronicle_id: str) -> dict:
        """Режим охотников и их прогресс: уровень и класс из базы (если она запущена)."""
        p = catalog.profile(chronicle_id)
        game = catalog.server_dir(p) / "game"
        ini = game / "config" / "Custom" / HUNTERS_INI
        data = game / "data" / "L2HubPhantoms.xml"
        if not ini.is_file() or not data.is_file():
            return {"ok": False, "error": "В этой сборке нет охотников-фантомов."}
        phantoms = list(ET.parse(data).getroot().iter("phantom"))
        names = [el.get("name") for el in phantoms if el.get("kind") == "hunter"]
        # Секретные (kind="reaper" — Танатос, решение автора 2026-10-23): в окне только строка-глюк последней в «Населении» (world.js,
        # secretRows) — без имени и данных, с еле видным уровнем. Есть, пока охотники не выключены: без них его нет и в мире.
        secret = [el for el in phantoms if el.get("kind") not in (None, "hunter", "companion")]
        classes = _class_names(game)
        items = {n: {"name": n, "level": None, "cls": ""} for n in names}
        db = servers.db_running(p["ports"]["db"])
        running = self.running() == chronicle_id
        players = None
        if db and names:
            rows = servers.db_query(p, f"SELECT char_name, level, classid FROM characters WHERE account_name='{servers.phantom_account(p)}'")
            for name, level, class_id in rows:
                if name in items:
                    items[name].update(level=int(level), cls=classes.get(int(class_id), class_id))
            if running:
                players = int(servers.db_query(p, f"SELECT COUNT(*) FROM characters WHERE online=1 AND {servers.not_phantom_sql(p)}")[0][0])
        live = _hunters_status(game) if running else None
        # Когда сервер последний раз писал состояние: окно видит и свежесть, и «перестал отвечать» (файл старше минуты — live нет).
        live_at = None
        if running:
            try:
                live_at = int((game / "log" / HUNTERS_STATUS).stat().st_mtime * 1000)
            except OSError:
                pass
        for h in (live or {}).get("hunters", []):
            item = items.get(h.get("name"))
            if item is None or not h.get("online"):
                continue
            item.update(level=h.get("level", item["level"]), cls=classes.get(h.get("classId"), item["cls"]), adena=h.get("adena", 0), phase=h.get("phase", ""),
                        spot=h.get("spot", ""), town=h.get("town", ""), party=h.get("party", ""), partyMembers=h.get("partyMembers") or [], store=h.get("store"),
                        strategy=h.get("strategy"), nextThink=h.get("nextThink"), mood=h.get("mood", ""), relations=h.get("relations") or [],
                        earnedPerHour=h.get("earnedPerHour"), quest=h.get("quest"), professionDue=h.get("professionDue"),
                        activity=h.get("activity"),  # PvP-ивент, рейд, сезонное событие — docs/PHANTOMS.md, после 4.34
                        goal=h.get("goal", ""),  # цель от мозга-стратега, docs/MIND.md, «Шаг 3 — как сделано»
                        # клан и одиночка — docs/PHANTOMS.md, 4.50
                        clan=h.get("clan", ""), clanLevel=h.get("clanLevel"), clanLeader=bool(h.get("clanLeader")), hunterClan=bool(h.get("hunterClan")),
                        clanWar=h.get("clanWar", ""), clanWarScore=h.get("clanWarScore", ""),  # война кланов — 4.50, этапы 3 и 4
                        clanWarPlayer=bool(h.get("clanWarPlayer")),
                        loner=bool(h.get("loner")))
        mode = _ini_value(ini, "HuntersMode", "ALWAYS").upper()
        live_secret = {h.get("name"): h for h in (live or {}).get("hunters", []) if h.get("secret")}
        hidden = [] if mode == "OFF" else [
            {"name": "", "secret": True, "level": live_secret.get(el.get("name"), {}).get("level") or next(
                (int(g.get("minLevel")) for g in el.iter("gear") if (g.get("minLevel") or "").isdigit()), None)} for el in secret]
        return {"ok": True, "mode": mode, "db": db, "running": running, "live": live is not None,
                "liveAt": live_at, "players": players, "strategy": _strategy_info(p, game, len(names)), "world": _world_now(game),
                "items": sorted(items.values(), key=lambda x: (-(x["level"] or 0), x["name"])) + hidden}

    def companions(self, chronicle_id: str) -> dict:
        """Напарники-фантомы, только чтение: роль, прозвища, путь класса и характер — из данных сервера, уровень и класс — из базы.
        Управляют ими в игре (.buddy, Alt+B, чат), из окна — нечем."""
        p = catalog.profile(chronicle_id)
        game = catalog.server_dir(p) / "game"
        data = game / "data" / "L2HubPhantoms.xml"
        if not data.is_file():
            return {"ok": False, "error": "В этой сборке нет напарников-фантомов."}
        classes = _class_names(game)
        try:
            personas = json.loads((game / "data" / "L2HubPhantomPersonas.json").read_text(encoding="utf-8")).get("personas", {})
        except (OSError, ValueError):
            personas = {}
        items = []
        for el in ET.parse(data).getroot().iter("phantom"):
            if el.get("kind", "companion") != "companion":  # охотники — в «Населении», секретные (Танатос) — нигде
                continue
            per = personas.get(el.get("name"), {})
            items.append({"name": el.get("name"), "role": el.get("role", ""), "aliases": [a for a in el.get("aliases", "").split(";") if a],
                          "classPath": [classes.get(int(i), i) for i in el.get("classes", "").split(",") if i.strip().isdigit()],
                          "race": per.get("race", ""), "traits": per.get("traits", ""), "activity": per.get("activity", ""),
                          "level": None, "cls": "", "online": False})
        db = servers.db_running(p["ports"]["db"])
        if db and items:
            names = {i["name"]: i for i in items}
            for name, level, class_id, online in servers.db_query(p, f"SELECT char_name, level, classid, online FROM characters WHERE account_name='{servers.phantom_account(p)}'"):
                if name in names:
                    names[name].update(level=int(level), cls=classes.get(int(class_id), class_id), online=online == "1")
        return {"ok": True, "db": db, "items": items}

    def restart_needed(self, chronicle_id: str) -> dict:
        """Конфиги гейма, сохранённые после старта гейм-сервера: сервер прочтёт их только после перезапуска.
        PhantomHunters.ini не в счёт — его сервер перечитывает на ходу."""
        p = catalog.profile(chronicle_id)
        proc = servers.part_process(p, "game")
        try:
            started = proc.create_time() if proc else None
        except Exception:  # noqa: BLE001 — процесс успел завершиться
            started = None
        if started is None:
            return {"ok": True, "running": False, "files": []}
        server = catalog.server_dir(p)
        cfg = server / "game" / "config"
        files = [f.relative_to(cfg).as_posix() for f in configs.config_files(server, "game", configs.pattern(p))
                 if f.name not in LIVE_CONFIGS and f.stat().st_mtime > started]
        return {"ok": True, "running": True, "files": files}

    def set_hunter_strategy(self, chronicle_id: str, settings: dict) -> dict:
        """Мозги-стратег: when — OFF (выключен), WITH_PLAYERS или ALWAYS, и частоты. Сервер перечитывает PhantomHunters.ini на ходу."""
        try:
            when = str(settings.get("when", "")).upper()
            if when != "OFF" and when not in STRATEGY_WHEN:
                raise ValueError(f"неизвестный режим {when}")
            edits = [("HunterStrategy", "False" if when == "OFF" else "True")]
            if when != "OFF":
                edits.append(("HunterStrategyWhen", when))
            for key, (label, lo, hi, _) in STRATEGY_NUMBERS.items():
                value = str(settings.get(key, "")).strip()
                if not re.fullmatch(r"\d{1,3}", value) or not lo <= int(value) <= hi:
                    raise ValueError(f"{label} — целое от {lo} до {hi}")
                edits.append((key, str(int(value))))
        except (KeyError, TypeError, ValueError) as e:
            return {"ok": False, "error": f"Не сохранил: {e}"}
        return self.save_settings(chronicle_id, [{"part": "game", "file": HUNTERS_INI, "key": k, "value": val} for k, val in edits])

    def set_hunters_mode(self, chronicle_id: str, mode: str) -> dict:
        if mode not in HUNTER_MODES:
            return {"ok": False, "error": f"Неизвестный режим: {mode}"}
        return self.save_settings(chronicle_id, [{"part": "game", "file": HUNTERS_INI, "key": "HuntersMode", "value": mode}])

    def reset_hunters(self, chronicle_id: str, names: list[str]) -> dict:
        """Сброс прогресса: сервер удалит охотников из базы, и они начнут с 1 уровня. Запущен — в течение 10 с, нет — при старте."""
        if names != ["ALL"] and (not names or not all(CHAR_NAME_RE.match(n) for n in names)):
            return {"ok": False, "error": "Неверные имена охотников."}
        p = catalog.profile(chronicle_id)
        ini = catalog.server_dir(p) / "game" / "config" / "Custom" / HUNTERS_INI
        if not ini.is_file():
            return {"ok": False, "error": "В этой сборке нет охотников-фантомов."}
        try:
            backups.make(p, "before-reset")  # сервер удалит охотников из базы — откат будет к этому бэкапу
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": f"Сброс отменён: не получился бэкап базы ({e})."}
        next_id = int(_ini_value(ini, "HuntersResetId", "0") or 0) + 1
        return self.save_settings(chronicle_id, [{"part": "game", "file": HUNTERS_INI, "key": "HuntersReset", "value": ",".join(names)},
                                                 {"part": "game", "file": HUNTERS_INI, "key": "HuntersResetId", "value": str(next_id)}])

    # ---------- Мозги фейков ----------

    def brain_settings(self, chronicle_id: str) -> dict:
        """Какая модель отвечает за фейков и фантомов (облако или своя), есть ли ключ, расход."""
        p = catalog.profile(chronicle_id)
        if not servers.brain_port(p):
            return {"ok": False, "error": "У этой хроники нет мозгов фейков."}
        s = brain.load_settings(p.get("brain", {}))
        return {"ok": True, "settings": s, "key": bool(brain.read_key(s)), "keyPath": str(brain.BRAIN_DIR / s["api"]["keyFile"]),
                "usage": brain.usage_summary(s)}

    def save_brain_settings(self, chronicle_id: str, settings: dict) -> dict:
        """Сохранить выбор модели: сервис мозгов подхватит его со следующей реплики, без перезапуска."""
        try:
            clean = {"mode": settings["mode"], "maxTokens": int(settings["maxTokens"]),
                     "priceIn": float(settings.get("priceIn") or 0), "priceOut": float(settings.get("priceOut") or 0)}
            if clean["mode"] not in brain.MODES:
                raise ValueError("режим — api или local")
            if not 16 <= clean["maxTokens"] <= 1024:
                raise ValueError("длина ответа — от 16 до 1024 токенов")
            if not (0 <= clean["priceIn"] <= 1000 and 0 <= clean["priceOut"] <= 1000):
                raise ValueError("цена — от 0 до 1000 $ за миллион токенов")
            for mode in brain.MODES:
                part = settings.get(mode) or {}
                url, model = str(part.get("baseUrl", "")).strip(), str(part.get("model", "")).strip()
                if not BRAIN_URL_RE.match(url):
                    raise ValueError(f"адрес {mode}: нужен http://… или https://…")
                if len(model) > 120 or any(c in model for c in "\r\n\t"):
                    raise ValueError(f"модель {mode}: слишком длинное имя")
                clean[mode] = {"baseUrl": url, "model": model}
            key_file = str((settings.get("api") or {}).get("keyFile") or brain.KEY_FILE.name)
            if not brain.KEY_NAME_RE.match(key_file):
                raise ValueError("файл ключа — имя вида deepseek.key")
            clean["api"]["keyFile"] = key_file
            ext = settings.get("extended") or {}
            clean["extended"] = {"enabled": bool(ext.get("enabled")), "dailyUsd": float(ext.get("dailyUsd", brain.EXT_DEFAULT["dailyUsd"])),
                                 "focusMax": int(ext.get("focusMax", brain.EXT_DEFAULT["focusMax"]))}
            if not 0 <= clean["extended"]["dailyUsd"] <= 100:
                raise ValueError("лимит в день — от 0 до 100 $")
            if not 1 <= clean["extended"]["focusMax"] <= 10:
                raise ValueError("умных охотников рядом — от 1 до 10")
        except (KeyError, TypeError, ValueError) as e:
            return {"ok": False, "error": f"Не сохранил: {e}"}
        brain.BRAIN_DIR.mkdir(parents=True, exist_ok=True)
        tmp = brain.SETTINGS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(clean, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(brain.SETTINGS_FILE)
        # Тумблер «Расширенное обдумывание» — и серверам: PhantomHunters.ini перечитывается на ходу (у работающего мира — сразу).
        for pid in catalog.load_profiles():
            modes.sync_mind(catalog.profile(pid))
        return {"ok": True}

    def brain_models(self, chronicle_id: str, mode: str, base_url: str) -> dict:
        """Модели по адресу (GET /models): что загружено в LM Studio или Ollama, что доступно по ключу в облаке."""
        if mode not in brain.MODES or not BRAIN_URL_RE.match(base_url or ""):
            return {"ok": False, "error": "Нужен адрес вида http://127.0.0.1:1234/v1"}
        p = catalog.profile(chronicle_id)
        key = brain.read_key(brain.load_settings(p.get("brain", {}))) if mode == "api" else ""
        try:
            return {"ok": True, "models": brain.list_models(base_url, key)}
        except Exception as e:  # noqa: BLE001 — сеть, HTTP, не тот сервер
            hint = " Запущен ли LM Studio (Developer → Start Server) или Ollama?" if mode == "local" else ""
            return {"ok": False, "error": f"Не достучался до {base_url}: {e}.{hint}"}

    def brain_test(self, chronicle_id: str) -> dict:
        """Пробный запрос к выбранной модели по сохранённым настройкам: ответ и время."""
        started = time.monotonic()
        try:
            b = brain.Brain(chronicle_id)
            reply = b.call_llm([{"role": "system", "content": "Ты — проверка связи игрового сервера. Отвечай очень коротко, по-русски."},
                                {"role": "user", "content": "Связь есть? Ответь двумя-тремя словами."}])
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": str(e)}
        return {"ok": True, "reply": " ".join(reply.split())[:200] or "(пустой ответ)", "ms": int((time.monotonic() - started) * 1000),
                "mode": b.cfg["mode"], "model": b.cfg["model"] or "(загруженная)"}

    def mind_view(self, chronicle_id: str) -> dict:
        """«Разум»: лента решений стратега с итогами, опыт и выводы охотников, разговоры фантомов — из базы мозгов, и без сервера."""
        p = catalog.profile(chronicle_id)
        if not servers.brain_port(p):
            return {"ok": False, "error": "У этой хроники нет мозгов фейков."}
        try:
            v = brain.mind_view(chronicle_id)
            secret = _secret_phantoms(p)
        except Exception as e:  # noqa: BLE001 — база занята дольше таймаута, битая
            return {"ok": False, "error": f"База мозгов не читается: {e}"}
        # Секретных — ни в ленте, ни в выводах, ни в памяти (их разговоры с игроками — тоже).
        v["feed"] = [x for x in v["feed"] if x["fpc"] not in secret]
        v["extNow"] = [x for x in v["extNow"] if x not in secret]
        v["hunters"] = [x for x in v["hunters"] if x["fpc"] not in secret]
        v["talks"] = [x for x in v["talks"] if x["fpc"] not in secret and x["player"] not in secret]
        return {"ok": True, **v}

    def mind_forget(self, chronicle_id: str, kind: str, fpc: str, player: str = "") -> dict:
        """«Забыть» разговор фантома с игроком (talk) или опыт охотника (lessons): строки уходят в архив базы мозгов, не стираются."""
        p = catalog.profile(chronicle_id)
        if not servers.brain_port(p):
            return {"ok": False, "error": "У этой хроники нет мозгов фейков."}
        try:
            if {fpc, player} & _secret_phantoms(p):
                raise ValueError("такого в памяти нет")
            return {"ok": True, "lines": brain.mind_forget(chronicle_id, kind, fpc, player)}
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": f"Не забыл: {e}"}

    # ---------- Пульт L2Solo (C4): карта мира, рейты, LLM, вайп ----------

    def _l2solo(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        if not l2solo.is_l2solo(p):
            raise ValueError(f"{chronicle_id} — не L2Solo")
        return p

    def l2solo_panel(self, chronicle_id: str) -> dict:
        """Пульт C4: адрес их карты мира (WorldObserver), поднята ли она, рейты, LLM, мир и можно ли его вайпать."""
        try:
            return l2solo.panel(self._l2solo(chronicle_id))
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_open_map(self, chronicle_id: str) -> dict:
        """Их карту мира — в браузере по умолчанию: там ей просторнее, чем во фрейме окна."""
        try:
            p = self._l2solo(chronicle_id)
            if "map" not in p["ports"] or not servers.port_open(p["ports"]["map"]):
                return {"ok": False, "error": "Карта мира работает, пока работает сервер L2Solo."}
            os.startfile(l2solo.map_url(p))  # noqa: S606 — адрес наш: 127.0.0.1 и порт из профиля
            return {"ok": True}
        except (KeyError, ValueError, OSError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_progression(self, chronicle_id: str, rate: str) -> dict:
        """Рейты x1 / x10 / x50: применятся при следующем запуске сервера."""
        try:
            return {"ok": True, **l2solo.set_progression(self._l2solo(chronicle_id), rate)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_llm(self, chronicle_id: str, enabled: bool) -> dict:
        """LLM ботов вкл/выкл (модель и ключ — из профиля): применится при следующем запуске сервера."""
        try:
            return {"ok": True, **l2solo.set_llm(self._l2solo(chronicle_id), enabled)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_language(self, chronicle_id: str, lang: str) -> dict:
        """Язык ботов en / ru (русский оверлей l2solo_ru.js): применится при следующем запуске сервера."""
        try:
            return {"ok": True, **l2solo.set_bot_language(self._l2solo(chronicle_id), lang)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_chatter(self, chronicle_id: str, level: str) -> dict:
        """Болтовня ботов: off / quiet / normal / l2solo (пресет поверх частот профиля) — применится при следующем запуске."""
        try:
            return {"ok": True, **l2solo.set_chatter(self._l2solo(chronicle_id), level)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_world(self, chronicle_id: str, changes: dict) -> dict:
        """Ручки пульта C4: {"раздел.ключ": число или None} — квесты, заточка (l2solo.TUNING); после перезапуска."""
        try:
            return {"ok": True, **l2solo.set_world(self._l2solo(chronicle_id), changes)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_aggression(self, chronicle_id: str, level: str) -> dict:
        """Злость мира C4: peace / normal / angry (pvpAggression 0 / 0.5 / 1) — после перезапуска сервера."""
        try:
            return {"ok": True, **l2solo.set_aggression(self._l2solo(chronicle_id), level)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_set_mode(self, chronicle_id: str, mode: str) -> dict:
        """Режим сервера C4 (l2solo_modes.json): пресет рейтов, злость мира и заточка разом — после перезапуска."""
        try:
            return {"ok": True, **l2solo.set_mode(self._l2solo(chronicle_id), mode)}
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}

    def l2solo_update_check(self, chronicle_id: str) -> dict:
        """Есть ли новый код L2Solo у авторов (git fetch): behind — сколько коммитов, commits — их список."""
        try:
            p = self._l2solo(chronicle_id)
            return {"ok": True, **l2solo.update_check(p), "history": l2solo.update_history(p)}
        except Exception as e:  # noqa: BLE001 — git, сеть: показываем как есть
            return {"ok": False, "error": str(e)}

    def l2solo_update(self, chronicle_id: str) -> dict:
        """Обновить L2Solo у выключенного сервера: сохранение мира, git pull, npm install (docs/L2SOLO.md, «Обновление»)."""
        try:
            p = self._l2solo(chronicle_id)
        except (KeyError, ValueError) as e:
            return {"ok": False, "error": str(e)}
        if self.running() == chronicle_id:
            return {"ok": False, "error": "Сначала останови сервер: L2Solo обновляется только выключенным."}
        return self._start_job(chronicle_id, "update", l2solo.update_steps(p, lambda q: backups.make(q, "before-update")))

    def l2solo_llm_test(self, chronicle_id: str) -> dict:
        try:
            return {"ok": True, **l2solo.llm_test(self._l2solo(chronicle_id))}
        except Exception as e:  # noqa: BLE001 — сеть, Node, конфиг: показываем как есть
            return {"ok": False, "error": str(e)}

    def l2solo_wipe_preview(self, chronicle_id: str, scope: str) -> dict:
        try:
            return {"ok": True, **l2solo.wipe_preview(self._l2solo(chronicle_id), scope)}
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": str(e)}

    def l2solo_wipe(self, chronicle_id: str, scope: str, confirm: str) -> dict:
        """Вайп мира: confirm должен повторить scope — защита от случайного вызова. Перед вайпом — сохранение мира."""
        try:
            p = self._l2solo(chronicle_id)
            if confirm != scope:
                raise ValueError("вайп не подтверждён")
            l2solo.wipe_preview(p, scope)  # те же проверки до запуска задачи: сервер выключен, мир есть, scope верный
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": str(e)}
        return self._start_job(chronicle_id, "wipe", [("Сохраняю мир перед вайпом…", lambda: backups.make(p, "before-wipe")),
                                                      (f"Вайп: {l2solo.WIPE_SCOPES[scope]}…", lambda: l2solo.wipe(p, scope))])

    # ---------- Бэкапы базы ----------

    def backup_list(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        return {"ok": True, "items": backups.list_backups(p), "blocker": backups.blocker(p), "running": self.running() == chronicle_id,
                "last": backups.last_log(p), "keepAuto": backups.KEEP_AUTO}

    def make_backup(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        reason = backups.blocker(p)
        if reason:
            return {"ok": False, "error": reason}
        return self._start_job(chronicle_id, "backup", [("Делаю бэкап базы…", lambda: backups.make(p, "manual"))])

    def restore_backup(self, chronicle_id: str, name: str) -> dict:
        p = catalog.profile(chronicle_id)
        if self.running() == chronicle_id:
            return {"ok": False, "error": "Сначала останови сервер: база откатывается только у выключенной хроники."}
        if not any(b["name"] == name for b in backups.list_backups(p)):
            return {"ok": False, "error": f"Нет такого бэкапа: {name}"}
        return self._start_job(chronicle_id, "restore", [("Бэкаплю текущую базу и откатываю…", lambda: backups.restore(p, name))])

    # ---------- Друзья: сеть Radmin VPN ----------

    def network_state(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            cfg = l2solo.config(p)
            listen = {"login": cfg.get("AuthServer", {}).get("hostname", "?"), "game": cfg.get("GameServer", {}).get("hostname", "?")}
            return {"ok": True, "mode": "local" if set(listen.values()) == {"127.0.0.1"} or not l2solo.layer_path(p).is_file() else "custom",
                    "listen": listen, "radmin": network.radmin_addresses(), "address": None, "addressOk": False,
                    "running": self.running() == chronicle_id, "restartNeeded": False, "friendIni": None, "clientDir": "system",
                    "pack": None, "unsupported": L2SOLO_NO_FRIENDS}
        ini = network.FRIENDS / p["id"] / (clientsys.INI if clientsys.is_4game(p) else "L2.ini")
        pack = friendpack.pack_path(p)
        pack_info = {"path": str(pack), "size": pack.stat().st_size, "time": time.strftime("%Y-%m-%d %H:%M", time.localtime(pack.stat().st_mtime))} if pack.is_file() else None
        procs = [pr for pr in (servers.part_process(p, "login"), servers.part_process(p, "game")) if pr]
        try:
            restart = bool(procs) and network.config_mtime(p) > min(pr.create_time() for pr in procs)
        except Exception:  # noqa: BLE001 — процесс успел завершиться
            restart = False
        return {"ok": True, **network.state(p), "running": self.running() == chronicle_id, "restartNeeded": restart,
                "friendIni": str(ini) if ini.is_file() else None, "clientDir": network.client_system_name(p), "pack": pack_info}

    def set_network(self, chronicle_id: str, mode: str, address: str | None = None, kind: str | None = None) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return {"ok": False, "error": L2SOLO_NO_FRIENDS}
        try:
            r = network.apply(p, mode, address, kind)
        except (RuntimeError, ValueError, KeyError, FileNotFoundError) as e:
            return {"ok": False, "error": str(e)}
        return {"ok": True, **r, "restart": self.running() == chronicle_id}

    def network_overview(self) -> dict:
        """«Друзья»: адреса этого ПК по способам и режим каждой установленной хроники. Способ — один на все
        (решение автора 2026-10-20); common — он, если все поддерживающие хроники настроены одинаково."""
        items = []
        for c in catalog.load_catalog():
            if not c["installed"]:
                continue
            r = self.network_state(c["id"])
            items.append({"id": c["id"], "name": c["name"], "supported": not r.get("unsupported"), "why": r.get("unsupported"),
                          "mode": r["mode"], "kind": r.get("kind"), "label": r.get("label"), "address": r.get("address"),
                          "addressOk": r.get("addressOk"), "running": r["running"], "restartNeeded": r["restartNeeded"],
                          "friendIni": r.get("friendIni"), "pack": r.get("pack"), "clientDir": r.get("clientDir")})
        mine = [x for x in items if x["supported"]]
        same = {(x["mode"], x["kind"], x["address"]) for x in mine}
        common = dict(zip(("mode", "kind", "address"), next(iter(same)))) if len(same) == 1 else None
        if common:
            common["label"] = network.label(common["kind"])
        # «Интернет»: последний ответ роутера (без похода в сеть — его делает internet_probe по запросу окна).
        return {"candidates": network.candidates(), "items": items, "common": common,
                "internet": network.internet_cached(), "lan": network.lan_sources(), "ports": network.PORTS}

    def internet_probe(self) -> dict:
        """«Интернет»: спросить роутер по UPnP — внешний адрес, серый ли он, проброшены ли порты (~2 с)."""
        return {"ok": True, **network.internet_probe(force=True)}

    def internet_ports(self, open_: bool = True) -> dict:
        """«Интернет»: открыть или закрыть порты логина и гейма на роутере по UPnP."""
        return network.internet_ports(open_)

    def internet_lookup(self) -> dict:
        """«Интернет» без UPnP: внешний адрес через ipify.org — только по кнопке, где окно называет сервис."""
        try:
            return {"ok": True, "address": network.lookup_external()}
        except (OSError, RuntimeError) as e:
            return {"ok": False, "error": f"ipify.org не ответил: {getattr(e, 'reason', e)}"}

    def set_network_all(self, mode: str, address: str | None = None, kind: str | None = None) -> dict:
        """Один способ на все установленные хроники (кроме L2Solo: у него режима друзей пока нет).
        «Интернет» заодно открывает порты на роутере по UPnP, уход с него — закрывает свои."""
        was_internet = any(self.network_state(c["id"]).get("kind") == "internet" for c in catalog.load_catalog() if c["installed"])
        done, failed = [], []
        for c in catalog.load_catalog():
            if not c["installed"]:
                continue
            p = catalog.profile(c["id"])
            if l2solo.is_l2solo(p):
                continue
            try:
                r = network.apply(p, mode, address, kind)
                done.append({"id": c["id"], "name": c["name"], "restart": self.running() == c["id"]})
            except (RuntimeError, ValueError, KeyError, FileNotFoundError) as e:
                failed.append({"id": c["id"], "name": c["name"], "error": str(e)})
        if not done:
            return {"ok": False, "error": failed[0]["error"] if failed else "Нет хроник, где можно сменить сеть."}
        ports = None
        if r["kind"] == "internet":
            ports = network.internet_ports(True)
        elif was_internet:
            ports = network.internet_ports(False)
        return {"ok": True, "mode": r["mode"], "kind": r["kind"], "label": r["label"], "address": r["address"], "done": done, "failed": failed,
                "ports": ports}

    def make_client_pack(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return {"ok": False, "error": L2SOLO_NO_FRIENDS}
        if network.state(p)["mode"] != "friends":
            return {"ok": False, "error": "Сначала выбери в «Друзьях», как друзья подключаются: в пакет пишется адрес этого ПК в той сети."}
        return self._start_job(chronicle_id, "pack", [("Собираю клиент для друзей — несколько гигабайт, пара минут…", lambda: friendpack.build(p))])

    def mobius_freshness(self, chronicle_id: str) -> dict:
        """Насколько закреплённая сборка Mobius отстала от master: дата закреплённого и свежего коммита папки сборки
        и сколько коммитов после закреплённого. Обновлять — пока через агента: патчи и врезки фантомов проверяются
        на новом коммите (mobius_pins.json, tools/phantoms_glue.py --check). Ответ GitLab кэшируется на час."""
        p = catalog.profile(chronicle_id)
        if p.get("type") != "mobius":
            return {"ok": True, "supported": False}
        pin = builder.pins()["chronicles"].get(chronicle_id) or p.get("source") or {}
        build = pin.get("build") or p.get("build")
        if not pin.get("commit") or not build:
            return {"ok": True, "supported": False}
        cached = self._fresh.get(chronicle_id)
        if cached and time.time() - cached[0] < 3600:
            return cached[1]
        try:
            latest = builder.latest_commit(build)
            behind = 0 if latest["commit"] == pin["commit"] else builder.commits_since(build, pin["date"])
        except (OSError, ValueError, RuntimeError) as e:
            return {"ok": False, "error": f"GitLab не ответил: {getattr(e, 'reason', e)}"}
        out = {"ok": True, "supported": True, "build": build, "pinned": {"commit": pin["commit"][:8], "date": pin.get("date")},
               "latest": {"commit": latest["commit"][:8], "date": latest["date"]}, "behind": behind}
        self._fresh[chronicle_id] = (time.time(), out)
        return out

    def make_friend_l2ini(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        if l2solo.is_l2solo(p):
            return {"ok": False, "error": L2SOLO_NO_FRIENDS}
        try:
            return {"ok": True, "path": str(network.friend_l2ini(p))}
        except (RuntimeError, ValueError, OSError) as e:
            return {"ok": False, "error": str(e)}

    def players(self) -> dict:
        """«Кто играет» в «Друзьях»: живые аккаунты всех хроник, чью базу сейчас видно (базы не поднимает),
        с персонажами, последним входом и откуда заходили. Фантомы и боты L2Solo не в счёт."""
        accounts, hidden = {}, []
        for c in catalog.load_catalog():
            if not c["installed"]:
                continue
            p = catalog.profile(c["id"])
            if l2solo.is_l2solo(p):
                r = l2solo.characters(p)
                rows = [(x["account"], x["name"], x["level"], x["online"], 0, "") for x in r["items"]] if r["ok"] else None
            elif servers.db_running(p["ports"]["db"]):
                rows = _player_rows(p)
            else:
                rows = None
            if rows is None:
                hidden.append(c["name"])
                continue
            for account, name, level, online, last, ip in rows:
                a = accounts.setdefault(account.lower(), {"account": account, "chars": [], "online": False, "last": 0, "ip": ""})
                a["chars"].append({"name": name, "level": int(level), "chron": c["name"], "online": bool(online)})
                a["online"] |= bool(online)
                if last >= a["last"]:
                    a["last"], a["ip"] = last, ip or a["ip"]
        items = sorted(accounts.values(), key=lambda a: (not a["online"], -a["last"]))
        for a in items:
            a["from"] = _where_from(a.pop("ip"))
            a["chars"].sort(key=lambda ch: (not ch["online"], -ch["level"]))
        return {"ok": True, "items": items, "hidden": hidden}

    def make_report(self) -> dict:
        """«Собрать отчёт»: zip в data/reports (report.py) и папка с ним в Проводнике — только в окне."""
        try:
            out = report.build(self.running())
        except Exception as e:  # noqa: BLE001 — показать, что не так, а не молча упасть
            return {"ok": False, "error": f"Отчёт не собрался: {e}"}
        if self._window:
            subprocess.Popen(["explorer", "/select,", str(out)])  # noqa: S603, S607 — путь наш
        return {"ok": True, "path": str(out), "name": out.name}

    # ---------- Геодата (geodata.py) ----------

    def geodata_status(self, chronicle_id: str) -> dict:
        """Карточка «Геодата»: файлов .l2j, сколько ждём, откуда берём (скачать или из клиента), оценка времени."""
        return {"ok": True, **geodata.status(catalog.profile(chronicle_id))}

    def geodata_get(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        src = geodata.source(p)
        if not src:
            return {"ok": False, "error": "Откуда брать геодату для этой хроники, L2Hub не знает"}
        if src["kind"] == "download":
            steps = [("Геодата:", lambda: geodata.download(p, job_note, job_cancelled))]
        else:
            steps = [("Геодата из клиента:", lambda: geodata.build(p, job_note, job_cancelled))]
        return self._start_job(chronicle_id, "geodata", steps)

    # ---------- Обновления L2Hub (updates.py) ----------

    def update_check(self, force: bool = False) -> dict:
        """Что вышло на GitHub Releases: один запрос за сеанс, «Проверить» — заново."""
        if force or self._update is None:
            self._update = updates.check()
        return {"ok": True, **self._update}

    def update_page(self) -> dict:
        """Страница последнего релиза в браузере — только адрес из ответа GitHub, только https://github.com/."""
        page = (self._update or {}).get("page") or ""
        if not page.startswith("https://github.com/"):
            return {"ok": False, "error": "Страницы релиза нет — сначала «Проверить»"}
        os.startfile(page)  # noqa: S606 — адрес релиза с GitHub
        return {"ok": True}

    def update_install(self) -> dict:
        if self.running():
            return {"ok": False, "error": "Сначала останови сервер: установщик заменит программу, а с ней — служебные процессы окна."}
        if not updates.can_install():
            return {"ok": False, "error": "Обновлять поверх умеет установленный L2Hub; портативный — скачай заново со страницы релиза."}
        got: dict = {}
        steps = [("Скачиваю новую версию L2Hub:", lambda: got.update(path=updates.download(job_note, job_cancelled))),
                 ("Запускаю установщик…", lambda: updates.launch(got["path"]))]
        return self._start_job("", "update", steps)

    def cancel_job(self) -> dict:
        """Остановить долгую задачу (геодату, скачивание обновления): шаг сам смотрит job_cancelled() и сохраняет сделанное."""
        job = self._job
        if not job or job.done or job.action not in ("geodata", "update"):
            return {"ok": False, "error": "Останавливать нечего"}
        job.cancelled = True
        return {"ok": True}

    # ---------- Голоса и отзывы (feedback.py) ----------

    def feedback_enabled(self) -> bool:
        """Приёмник настроен — в окне есть «Хочу эту хронику» и «Отзыв». Без сети."""
        return bool(feedback.url())

    def votes(self, refresh: bool = False) -> dict:
        """Счётчики «Хочу эту хронику» и свои голоса; enabled — приёмник настроен (feedback.json)."""
        return {"ok": True, **feedback.votes(bool(refresh))}

    def vote(self, chronicle_id: str, on: bool = True) -> dict:
        if not any(c["id"] == chronicle_id and not c.get("variant") for c in catalog.load_catalog()):
            return {"ok": False, "error": f"Нет хроники {chronicle_id}"}
        try:
            return {"ok": True, **feedback.vote(chronicle_id, bool(on))}
        except RuntimeError as e:
            return {"ok": False, "error": str(e)}

    def send_feedback(self, kind: str, text: str, contact: str = "", chronicle_id: str = "", attach: bool = False) -> dict:
        try:
            return {"ok": True, **feedback.send(kind, text, contact, chronicle_id, bool(attach), self.running())}
        except (ValueError, RuntimeError, OSError) as e:
            return {"ok": False, "error": str(e)}

    # ---------- Папки ----------

    def open_path(self, chronicle_id: str, what: str) -> dict:
        p = catalog.profile(chronicle_id)
        server = catalog.server_dir(p)
        solo = l2solo.is_l2solo(p)
        target = {
            "logs": l2solo.log_path(p).parent if solo else server / "game" / "log",
            "config": server / "config" if solo else server / "game" / "config",
            "client": catalog.client_exe(p).parent.parent,
            "backups": l2solo.saves_dir(p) if solo else paths.BACKUPS,
            "friends": network.FRIENDS / p["id"],
            "brain": brain.BRAIN_DIR,
        }.get(what)
        if not target:
            return {"ok": False, "error": f"Неизвестная папка: {what}"}
        target.mkdir(parents=True, exist_ok=True)
        os.startfile(target)  # noqa: S606 — открываем папку в проводнике
        return {"ok": True}

    # ---------- Настройки ----------

    def settings_schema(self, chronicle_id: str) -> dict:
        p = catalog.profile(chronicle_id)
        return configs.build(catalog.server_dir(p), configs.pattern(p))

    def server_modes(self, chronicle_id: str) -> dict:
        """Режимы сервера и выключатели модов (modes.py, server_modes.json) для «Основного» в настройках."""
        return modes.state(catalog.profile(chronicle_id))

    def set_mod_switch(self, chronicle_id: str, switch_id: str, value: bool) -> dict:
        try:
            r = modes.set_switch(catalog.profile(chronicle_id), switch_id, bool(value))
        except (ValueError, KeyError, FileNotFoundError) as e:
            return {"ok": False, "error": str(e)}
        return {"ok": True, **r, "restart": self.running() == chronicle_id}

    def apply_server_mode(self, chronicle_id: str, mode_id: str) -> dict:
        try:
            r = modes.apply_mode(catalog.profile(chronicle_id), mode_id)
        except (ValueError, KeyError, FileNotFoundError) as e:
            return {"ok": False, "error": str(e)}
        return {"ok": True, **r, "restart": self.running() == chronicle_id}

    def event_calendar(self, chronicle_id: str) -> dict:
        """Календарь сезонных событий и расписание PvP-ивентов (L2HubEvents.ini) для карточки в «Основном»."""
        return modes.calendar(catalog.profile(chronicle_id))

    def save_calendar(self, chronicle_id: str, values: dict) -> dict:
        try:
            r = modes.set_calendar(catalog.profile(chronicle_id), values)
        except (ValueError, KeyError, FileNotFoundError) as e:
            return {"ok": False, "error": str(e)}
        return {"ok": True, **r, "restart": self.running() == chronicle_id}

    def save_settings(self, chronicle_id: str, edits: list[dict]) -> dict:
        p = catalog.profile(chronicle_id)
        try:
            return {"ok": True, **configs.save_edits(catalog.server_dir(p), paths.BACKUPS / "config" / chronicle_id, edits)}
        except (ValueError, KeyError, FileNotFoundError) as e:
            return {"ok": False, "error": str(e)}
