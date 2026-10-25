"""Состояние хроники и проверка «всё ли работает»: процессы, порты, логи, база, клиент."""

import json
import re
import time
import urllib.request
from collections.abc import Callable
from pathlib import Path

import psutil

from . import backups, catalog, clientsys, l2ini, l2solo, network, paths, servers

_online_cache: dict[str, tuple[float, int | None]] = {}


def _tail(path: Path, size: int = 200_000) -> str:
    if not path.exists():
        return ""
    with path.open("rb") as f:
        f.seek(0, 2)
        n = f.tell()
        f.seek(max(0, n - size))
        return f.read().decode("utf-8", "replace")


def log_path(p: dict, part: str, kind: str = "java") -> Path:
    return servers.log_file(p, part, kind)


def log_tail(p: dict, part: str, lines: int = 80) -> list[str]:
    if l2solo.is_l2solo(p):
        return l2solo.log_tail(p, lines)  # у L2Solo логин и гейм — один процесс и один лог
    out = []
    for line in _tail(log_path(p, part)).splitlines()[-lines:]:
        # Строка лога Mobius и L2J 2.0.6: дата\tуровень\tпоток\tкласс\tсообщение — оставляем время и сообщение.
        cols = line.split("\t")
        if len(cols) >= 5:
            out.append(f"{cols[0][11:19]} {cols[-1]}")
        elif line.strip():
            out.append(line)
    return out


def _proc_info(proc: psutil.Process | None) -> dict:
    if not proc:
        return {}
    try:
        with proc.oneshot():
            return {"pid": proc.pid, "mem_mb": round(proc.memory_info().rss / 2**20), "uptime_s": int(time.time() - proc.create_time())}
    except psutil.Error:
        return {"pid": proc.pid}


def _online(p: dict) -> int | None:
    cached = _online_cache.get(p["id"])
    if cached and time.monotonic() - cached[0] < 5:
        return cached[1]
    try:
        value = int(servers.db_query(p, f"SELECT COUNT(*) FROM characters WHERE online=1 AND {servers.not_phantom_sql(p)}")[0][0])
    except (RuntimeError, IndexError, ValueError, OSError):
        value = None
    _online_cache[p["id"]] = (time.monotonic(), value)
    return value


def game_facts(p: dict) -> dict:
    text = _tail(log_path(p, "game"))
    facts = {}
    if m := list(re.finditer(r"(?:GeoEngine|GeoData): Loaded (\d+) regions", text)):
        facts["geo_regions"] = int(m[-1].group(1))
    # Строки у Mobius и L2J 2.0.6 La2offline чуть разные: «Server 1: Bartz» и «Server 1 : Bartz», «loaded» и «Loaded».
    if m := list(re.finditer(r"Registered on login as Server (\d+)\s*:\s*(\S+)", text)):
        facts["registered"] = f"Server {m[-1].group(1)}: {m[-1].group(2)}"
    if m := list(re.finditer(r"Server loaded in (\d+) seconds", text, re.I)):
        facts["loaded_s"] = int(m[-1].group(1))
    if m := list(re.finditer(r"using (\d+) of (\d+) MB total memory", text)):
        facts["heap"] = f"{m[-1].group(1)} / {m[-1].group(2)} МБ"
    elif m := list(re.finditer(r"free memory (\d+) Mb of (\d+) Mb", text)):
        free, total = int(m[-1].group(1)), int(m[-1].group(2))
        facts["heap"] = f"{total - free} / {total} МБ"
    return facts


def _held_by_other(p: dict, part: str) -> str | None:
    """Какая другая хроника держит тот же порт этой части (логин или гейм), если держит."""
    for q in catalog.load_profiles().values():
        if q["id"] != p["id"] and q["ports"].get(part) == p["ports"][part] and servers.part_process(q, part):
            return q["id"]
    return None


LOOPBACK = {"127.0.0.1", "::1"}
WILDCARD = {"0.0.0.0", "::"}


def _own_addrs(heard: list[tuple[str, int | None]], proc: psutil.Process | None) -> list[str]:
    """Адреса, которые слушает сам процесс части; если PID не совпал (сокет у дочернего процесса) — все слушатели порта."""
    own = sorted({ip for ip, pid in heard if proc and pid == proc.pid})
    return own or sorted({ip for ip, _ in heard})


def _holder(heard: list[tuple[str, int | None]]) -> str:
    """Чей процесс держит порт: «java.exe, PID 1234»."""
    pid = next((pid for _, pid in heard if pid), None)
    try:
        return f"{psutil.Process(pid).name()}, PID {pid}" if pid else "неизвестный процесс"
    except psutil.Error:
        return f"PID {pid}"


def addr_text(addrs: list[str]) -> str:
    """Адреса сокетов по-человечески: 127.0.0.1, «все сети (::)»."""
    return ", ".join(f"все сети ({a})" if a in WILDCARD else a for a in addrs) or "—"


def state(p: dict) -> dict:
    if l2solo.is_l2solo(p):
        return l2solo.state(p)
    ports = p["ports"]
    db_on = servers.db_running(ports["db"])
    comps = {"db": {"state": "on" if db_on else "off", "port": ports["db"], "engine": servers.db_engine(p)}}
    for part in ("login", "game"):
        proc = servers.part_process(p, part)
        heard = servers.listeners(ports[part])
        other = None
        if proc and heard:
            st = "on"
        elif proc:
            st = "starting"
        elif heard and (other := _held_by_other(p, part)):
            st = "other"  # порты у хроник общие: этот держит другая запущенная хроника
        elif heard:
            st = "bad"  # порт занят чужим процессом
        else:
            st = "off"
        comps[part] = {"state": st, "port": ports[part], **_proc_info(proc), **({"other": other} if other else {})}
        if st == "on":
            comps[part]["listen"] = _own_addrs(heard, proc)
        elif st == "bad":
            comps[part]["holder"] = _holder(heard)
    client = servers.part_process(p, "client")
    comps["client"] = {"state": "on" if client else "off", "protocol": p["client"].get("protocol"), **_proc_info(client)}
    if comps["game"]["state"] == "on":
        comps["game"].update(game_facts(p))
        if db_on:
            comps["game"]["online"] = _online(p)
    if servers.brain_port(p):
        comps["brain"] = brain_state(p)
    return comps


def brain_state(p: dict) -> dict:
    port = servers.brain_port(p)
    proc = servers.part_process(p, "brain")
    info = {}
    if proc:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=0.5) as r:
                info = json.loads(r.read().decode("utf-8"))
        except (OSError, ValueError):
            info = {}
    st = "on" if info else ("starting" if proc else "off")
    return {"state": st, "port": port, **_proc_info(proc), **{k: v for k, v in info.items() if k != "ok"}}


def _game_config(p: dict, name: str) -> str:
    f = catalog.server_dir(p) / "game" / "config" / name
    return f.read_text(encoding="utf-8", errors="replace") if f.is_file() else ""


def _config_value(text: str, key: str) -> str | None:
    m = re.search(rf"^\s*{re.escape(key)}\s*=[ \t]*(.*?)\s*$", text, re.MULTILINE)
    return m.group(1) if m else None


def _accepted_protocols(p: dict) -> tuple[str, Callable[[int], bool]] | None:
    """Какие протоколы клиента принимает гейм: список у Mobius (Server.ini), диапазон у старых L2J 2.0 (server.properties)."""
    if servers.config_family(p) == "l2j2":
        text = _game_config(p, servers.config_file(p, "game"))
        lo, hi = _config_value(text, "MinProtocolRevision"), _config_value(text, "MaxProtocolRevision")
        if not (lo and hi and lo.isdigit() and hi.isdigit()):
            return None
        a, b = int(lo), int(hi)
        return f"{a}–{b}", lambda x: a <= x <= b
    m = re.search(r"^\s*AllowedProtocolRevisions\s*=\s*([\d;\s]+)", _game_config(p, "Server.ini"), re.MULTILINE)
    allowed = [int(x) for x in m.group(1).replace(" ", "").split(";") if x] if m else []
    return (";".join(map(str, allowed)), allowed.__contains__) if allowed else None


def _network_check(p: dict, net: dict, st: dict) -> tuple[str, str, str]:
    """«Сеть»: режим из конфигов против адресов, которые логин и гейм слушают на самом деле (сокеты их процессов).
    2026-10-20 гейм Interlude без патча 0001 слушал «::», а проверка по конфигам горела зелёным.
    Пока сервер выключен, сокетов нет — судим по конфигам и так и пишем."""
    if net["mode"] == "friends" and not net["addressOk"]:
        now = ", ".join(f"{c['address']} ({network.label(c['kind'])})" for c in net["candidates"]) or "ни одной сети для друзей"
        return ("bad", f"режим друзей ({net['label']}) на {net['address']}, а у этого ПК сейчас: {now}",
                f"Запусти {net['label']} и подключись к своей сети; адрес сменился — выбери способ заново в разделе «Друзья»")
    live = {part: st[part]["listen"] for part in ("login", "game") if st[part].get("listen")}
    said = {"login": "логин", "game": "гейм"}

    def heard(parts: dict) -> str:
        return "; ".join(f"{said[x]} слушает {addr_text(a)}" for x, a in parts.items())

    if net["mode"] == "other":
        return ("warn", f"настроено вручную: логин — {net['listen']['login']}, гейм — {net['listen']['game']}"
                + (f"; по сокетам: {heard(live)}" if live else ""),
                "Выбери способ в разделе «Друзья»: «Только этот ПК» или как друзья подключаются")
    text = "только этот ПК" if net["mode"] == "local" else f"друзья, {net['label']}: {net['address']}"
    if not live:
        return "ok", f"{text} — по конфигам; сокеты проверятся, когда сервер запущен", ""
    if net["mode"] == "local":
        wrong = {x: a for x, a in live.items() if set(a) - LOOPBACK}
    else:
        wrong = {x: a for x, a in live.items() if not set(a) & (WILDCARD | {net["address"]})}
    if not wrong:
        return "ok", f"{text} · {heard(live)}", ""
    if servers.config_family(p) == "l2j2":
        fix = "Сверь адреса в loginserver.properties и server.properties сборки."
    elif "game" in wrong and not (paths.PATCHES / p["id"] / "0001-gameserver-bind-hostname.patch").exists():
        fix = f"В patches/{p['id']} нет 0001-gameserver-bind-hostname: без него Mobius слушает гейм на всех сетях, что бы ни стояло в GameserverHostname."
    else:
        fix = "Режим меняли при работающем сервере? Перезапусти логин и гейм."
    if net["mode"] == "local":
        return "warn", f"режим «только этот ПК», а {heard(wrong)}", "Сервер открыт локальной сети и VPN-сетям. " + fix
    return "bad", f"друзья не подключатся: {heard(wrong)}", fix


SELFTEST = "L2HubSelfTest.json"
SEASONAL = "Сезонные события"  # проверка-календарь событий Mobius (Interlude, 0.4.25): detail — что идёт и до когда


def selftest(p: dict) -> dict | None:
    """Самопроверка модов L2Hub (game/log/L2HubSelfTest.json, скрипт custom/L2HubSelfTest): None — у сборки её нет;
    checks None — сервер работает, но свежего отчёта ещё нет (пишется через минуту после старта гейма). Отчёт старше
    запущенного гейма не берём: он от прошлого запуска."""
    path = catalog.server_dir(p) / "game" / "log" / SELFTEST
    if not path.is_file():
        return None
    game = servers.part_process(p, "game")
    try:
        fresh = not game or path.stat().st_mtime >= game.create_time()
        report = json.loads(path.read_text(encoding="utf-8")) if fresh else None
    except (OSError, ValueError, psutil.Error):
        report = None
    out = {"path": str(path), "running": bool(game), "time": None, "checks": None, "bad": [], "okCount": 0, "seasonal": None}
    if report:
        checks = report.get("checks") or []
        out.update(time=report.get("time"), checks=checks, bad=[c for c in checks if not c.get("ok")],
                   okCount=sum(1 for c in checks if c.get("ok")),
                   seasonal=next((c.get("detail") for c in checks if c.get("name") == SEASONAL), None))
    elif not game:
        return None
    return out


def diagnostics(p: dict) -> list[dict]:
    """Список проверок: ok — порядок, warn — стоит глянуть, bad — сломано, na — неприменимо сейчас."""
    if l2solo.is_l2solo(p):
        return l2solo.diagnostics(p, {"items": backups.list_backups(p), "last": backups.last_log(p)})
    st = state(p)
    checks = []

    def add(status, title, detail="", hint=""):
        checks.append({"status": status, "title": title, "detail": detail, "hint": hint})

    names = {"db": "базы", "login": "логина", "game": "гейма"}
    net = network.state(p)
    for part in ("db", "login", "game"):
        c = st[part]
        port = c["port"]
        if c["state"] == "on" and part == "db":
            # Адреса — из таблицы сокетов, а не из конфигов. База и MariaDB, и MySQL La2offline запускается с bind-address=127.0.0.1.
            addrs = sorted({ip for ip, _ in servers.listeners(port)})
            exposed = [a for a in addrs if a not in LOOPBACK]
            add("warn" if exposed else "ok", f"Порт базы {port}", f"слушает {addr_text(addrs)} · {c['engine']}",
                "База открыта не только этому ПК: проверь bind-address у базы" if exposed else "")
        elif c["state"] == "on":
            add("ok", f"Порт {names[part]} {port}", f"слушает {addr_text(c['listen'])} · PID {c.get('pid', '—')}")
        elif c["state"] == "other":
            other = next((x["name"] for x in catalog.load_catalog() if x["id"] == c["other"]), c["other"])
            add("na", f"Порт {names[part]} {port}", f"сейчас его держит {other}", "Порты у хроник общие: останови ту, чтобы запустить эту")
        elif c["state"] == "bad":
            add("bad", f"Порт {names[part]} {port}", f"занят чужим процессом: {c.get('holder', '—')}", "Закрой программу, которая заняла порт, или останови её через Сервис")
        elif c["state"] == "starting":
            add("warn", f"Порт {names[part]} {port}", "процесс запущен, порт ещё не открыт")
        else:
            add("na", f"Порт {names[part]} {port}", "сервер выключен")

    facts = game_facts(p)
    if st["game"]["state"] == "on":
        server_cfg = f"game/config/{servers.config_file(p, 'game')}" if servers.config_family(p) == "l2j2" else "game/config/Server.ini"
        add("ok" if facts.get("registered") else "bad", "Гейм зарегистрирован на логине", facts.get("registered", "в логе нет регистрации"),
            "" if facts.get("registered") else f"Проверь LoginHost и LoginPort в {server_cfg}")

    geo = facts.get("geo_regions")
    geo_files = len(list(servers.geodata_dir(p).glob("*.l2j")))
    if servers.config_family(p) == "l2j2" and _config_value(_game_config(p, servers.config_file(p, "general")), "GeoData") == "0":
        add("na", "Геодата", "выключена (GeoData = 0 в General.properties), как в оригинале сборки: мобы ходят сквозь стены")
    elif geo:
        add("ok", "Геодата", f"загружено регионов: {geo}" if st["game"]["state"] == "on" else f"в прошлом запуске загружено регионов: {geo}")
    else:
        add("warn" if geo_files == 0 else "na", "Геодата", f"файлов .l2j: {geo_files}",
            "Без геодаты мобы ходят сквозь стены — скачать или построить: «Обзор» → «Геодата»" if geo_files == 0 else "")

    proto = p["client"].get("protocol")
    accepted = _accepted_protocols(p)
    if proto and accepted:
        ok = accepted[1](proto)
        add("ok" if ok else "bad", "Протокол клиента", f"{p['client'].get('system', '')} · протокол {proto}; сервер принимает {accepted[0]}",
            "" if ok else "Нужна система клиента с протоколом из списка сервера")

    l2ini_path = catalog.client_ini(p)
    title = f"Адрес сервера в {l2ini_path.name} клиента"  # у клиентов 4game — открытый L2Hub.ini (clientsys)
    if l2ini_path.exists():
        try:
            addr = l2ini.server_addr(l2ini_path)
            add("ok" if addr == "127.0.0.1" else "warn", title, f"ServerAddr={addr}",
                "" if addr == "127.0.0.1" else "Клиент будет стучаться не на этот ПК")
        except ValueError as e:
            add("warn", title, f"не прочитать: {e}")
    else:
        four = clientsys.is_4game(p)
        add("bad", "Клиент", f"нет {l2ini_path}" if p["client"].get("path") else "папка клиента не указана",
            "Укажи папку клиента заново — L2Hub.ini лаунчер сделает сам («Клиент и сеть» → «Указать другую папку клиента»)" if four and p["client"].get("path")
            else "Укажи папку клиента: на главной — «указать папку» рядом с «клиент не указан»")

    status, detail, hint = _network_check(p, net, st)
    add(status, "Сеть", detail, hint)

    err = log_path(p, "game", "error")
    if err.exists() and err.stat().st_size > 0:
        add("warn", "Ошибки в логе гейма", f"{err.name}: {err.stat().st_size} байт", "Открой лог в Сервисе")
    else:
        add("ok", "Ошибки в логе гейма", f"{err.name} пустой")

    # Самопроверка модов L2Hub (скрипт custom/L2HubSelfTest, Interlude): сервер пишет её через минуту после старта.
    st_report = selftest(p)
    if st_report and st_report["checks"] is not None:
        bad = st_report["bad"]
        when = "" if st_report["running"] else f" (в прошлом запуске, {st_report['time']})"
        add("warn" if bad else "ok", "Моды L2Hub", f"{st_report['okCount']} из {len(st_report['checks'])} в порядке{when}"
            + (": " + "; ".join(f"{c['name']} — {c['detail']}" for c in bad[:2]) if bad else ""),
            f"Подробности — {st_report['path']}" if bad else "")
    elif st_report:
        add("na", "Моды L2Hub", "самопроверка через минуту после старта сервера")

    if "brain" in st:
        b = st["brain"]
        if b["state"] == "on" and not b.get("key"):
            add("warn", "Мозги фейков", "сервис работает, но нет ключа API — фейки отвечают заготовками",
                "Положи ключ DeepSeek одной строкой в data\\brain\\deepseek.key — подхватится сразу, без перезапуска")
        elif b["state"] == "on":
            # Предупреждение — только когда модель отвалилась (llmOk = False до следующего удачного запроса); кривой ответ
            # стратега (bad) и свой лимит — не сбой модели. Сервис мозгов до 0.4.265 llmOk не знает: по lastError, как раньше.
            down = b.get("llmOk") is False or ("llmOk" not in b and bool(b.get("lastError")))
            detail = f"{b.get('model')} · характеров {b.get('personas', 0)} · ответов {b.get('replies', 0)}"
            if b.get("lastError"):
                detail += f" · {'последняя ошибка' if down else 'был сбой модели'}: {b['lastError']}"
            if b.get("bad"):
                detail += f" · неудачных ответов {b['bad']}"
            add("warn" if down else "ok", "Мозги фейков", detail, "Журнал — data\\brain\\" + p["id"] + ".log" if down or b.get("bad") else "")
        elif st["game"]["state"] == "on":
            add("warn", "Мозги фейков", "сервис не запущен — фейки отвечают заготовками", f"Смотри {servers.brain_stderr(p)}")
        else:
            add("na", "Мозги фейков", "сервер выключен")

    if st["db"]["state"] == "on":
        try:
            gms = servers.db_query(p, "SELECT char_name, accesslevel FROM characters WHERE accesslevel > 0 ORDER BY accesslevel DESC")
            add("ok", "Права GM", ", ".join(f"{n} ({lvl})" for n, lvl in gms) or "GM нет")
        except RuntimeError as e:
            add("bad", "База персонажей", str(e))

    saved = backups.list_backups(p)
    last = backups.last_log(p)
    where = f"data\\backups\\db\\{p['id']}\\{backups.LOG}"
    if "ОШИБКА" in last:
        add("warn", "Бэкапы базы", last, f"Журнал — {where}")
    elif saved:
        b = saved[0]
        add("ok", "Бэкапы базы", f"всего {len(saved)}, последний — {b['when']} ({b['labelText']})")
    else:
        add("warn", "Бэкапы базы", "ни одного", "Сделай бэкап в «Сервисе» или просто запусти сервер — бэкап снимется сам")
    return checks
