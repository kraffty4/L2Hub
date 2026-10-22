"""Процессы хроники: база (наша MariaDB или своя база сборки-комбайна), логин, гейм и клиент.

Логин и гейм запускаются со своей скрытой консолью: так им можно адресно послать Ctrl+C,
и гейм через shutdown-хук сохранит игроков и мир. PID пишутся в data/run/<id>.json —
тот же формат, что у scripts/server_start.ps1.
"""

import ctypes
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import psutil

from . import catalog, l2solo, paths

JARS = {"login": "LoginServer.jar", "game": "GameServer.jar"}
STOP_TIMEOUT = {"game": 120, "login": 30}

# Сборки-комбайны (своя база и своя Java внутри, например La2offline) — профиль с "type": "bundle" и видом
# "bundle": "<вид>" из bundles.json. Работаем с копией сервера в servers/<id>/server и поднимаем части напрямую,
# без лаунчера сборки; как именно — описание вида: exe базы и частей, батники с аргументами Java, папка данных базы.
# Прежний профиль Gracia Final автора — "type": "la2offline" — читается как вид la2offline. Разбор — docs/LA2OFFLINE.md.
LEGACY_BUNDLE_TYPES = {"la2offline"}


def bundle_kinds() -> dict:
    return json.loads((paths.PACKAGE / "bundles.json").read_text(encoding="utf-8"))["kinds"]


def bundle(p: dict) -> dict | None:
    """Описание сборки-комбайна профиля из bundles.json; None — наш сервер (Mobius, L2Solo)."""
    kind = p.get("bundle") if p.get("type") == "bundle" else p.get("type") if p.get("type") in LEGACY_BUNDLE_TYPES else None
    if not kind:
        return None
    kinds = bundle_kinds()
    if kind not in kinds:
        raise RuntimeError(f"Вид сборки {kind!r} из профиля {p.get('id')} не описан в bundles.json")
    return kinds[kind]


def own_db(p: dict) -> bool:
    """Своя база внутри сборки (комбайн, runtime own); у видов на нашей Java (runtime ours) база — наша MariaDB."""
    b = bundle(p)
    return bool(b) and b.get("runtime", "own") == "own"


L2J2_FILES = {"login": "loginserver.properties", "game": "server.properties", "general": "General.properties"}


def config_file(p: dict, which: str) -> str:
    """Имя конфига у семейства l2j2: из описания вида (files: login, game, general), иначе — как у La2offline."""
    b = bundle(p) or {}
    return (b.get("files") or {}).get(which) or L2J2_FILES[which]


def config_family(p: dict) -> str:
    """Семейство конфигов сервера: mobius — *.ini Mobius, l2j2 — *.properties старых L2J 2.0."""
    b = bundle(p)
    return b["config"] if b else "mobius"


def geodata_dir(p: dict) -> Path:
    """Папка геодаты гейма: у вида со своим ключом (bundles.json, geodata: файл, ключ, умолчание) — из конфига сборки
    (относительный путь — от папки game), иначе game/data/geodata, как у Mobius. У L2Solo — data/Geodata."""
    if l2solo.is_l2solo(p):
        return catalog.server_dir(p) / "data" / "Geodata"
    b = bundle(p) or {}
    if spec := b.get("geodata"):
        f = catalog.server_dir(p) / spec[0]
        m = re.search(rf"^\s*{re.escape(spec[1])}\s*=[ \t]*(.*?)\s*$", f.read_text(encoding="utf-8", errors="replace"), re.MULTILINE) if f.is_file() else None
        value = Path((m[1] if m and m[1] else spec[2]))
        return value if value.is_absolute() else catalog.server_dir(p) / "game" / value
    return catalog.server_dir(p) / "game" / "data" / "geodata"


def part_exe(p: dict, part: str) -> Path:
    """Чем запущен логин или гейм: наша JDK (Mobius и виды на нашей Java), своя Java сборки-комбайна."""
    if own_db(p):
        return catalog.server_dir(p) / bundle(p)["parts"][part]["exe"]
    return paths.JAVA


def log_file(p: dict, part: str, kind: str = "java") -> Path:
    """Текущий лог части: java — основной, error — ошибки. У старых L2J логи с ротацией (у La2offline текущий — *.log.0)."""
    b = bundle(p)
    name = f"{kind}0.log" + (b.get("logSuffix", "") if b else "")
    return catalog.server_dir(p) / part / "log" / name


# ---------- Общее ----------

def listeners(port: int) -> list[tuple[str, int | None]]:
    """Кто слушает TCP-порт: [(адрес, PID)] из таблицы соединений Windows (0,8 мс, PID видны без админа).
    Не стучимся connect-ом: L2Solo пишет каждую пробу в лог как ECONNRESET, а сервер, привязанный
    к адресу Radmin, на 127.0.0.1 не ответил бы вовсе. "::" — сокет IPv6 на все сети (Java на Windows
    слушает так и IPv4)."""
    return [(c.laddr.ip, c.pid) for c in psutil.net_connections("tcp")
            if c.status == psutil.CONN_LISTEN and c.laddr.port == port]


def port_open(port: int) -> bool:
    return bool(listeners(port))


def wait_port(port: int, timeout: float, closed: bool = False, alive=None) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if port_open(port) != closed:
            return True
        if alive is not None and not alive():
            return False
        time.sleep(0.5)
    return False


def _hidden_console(args: list, cwd: Path) -> subprocess.Popen:
    # Флаг «игнорировать Ctrl+C» наследуется детьми даже с новой консолью. Если его выставил тот,
    # кто запустил лаунчер (так бывает в консолях агентов), JVM молча глотает Ctrl+C от ctrlc.py
    # и не сохраняет мир при остановке. Сбрасываем флаг у себя — дети получат обычную обработку.
    ctypes.windll.kernel32.SetConsoleCtrlHandler(None, False)
    si = subprocess.STARTUPINFO()
    si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    si.wShowWindow = 0  # SW_HIDE
    return subprocess.Popen(
        [str(a) for a in args], cwd=str(cwd), startupinfo=si,
        creationflags=subprocess.CREATE_NEW_CONSOLE,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def _no_window(args: list, timeout: float = 30) -> subprocess.CompletedProcess:
    return subprocess.run(
        [str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW,
    )


# ---------- PID ----------

def _run_file(chronicle_id: str) -> Path:
    return paths.RUN_DIR / f"{chronicle_id}.json"


def read_pids(chronicle_id: str) -> dict:
    f = _run_file(chronicle_id)
    if not f.exists():
        return {}
    try:
        return json.loads(f.read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, OSError):
        return {}


def _write_pids(chronicle_id: str, pids: dict) -> None:
    paths.RUN_DIR.mkdir(parents=True, exist_ok=True)
    pids = {k: v for k, v in pids.items() if v}
    f = _run_file(chronicle_id)
    if pids:
        f.write_text(json.dumps(pids, indent=2), encoding="utf-8")
    elif f.exists():
        f.unlink()


def process(pid, expected_exe: Path | None = None) -> psutil.Process | None:
    """Живой процесс с этим PID, если он действительно наш (exe совпадает с ожидаемым)."""
    if not pid:
        return None
    try:
        p = psutil.Process(int(pid))
        if not p.is_running() or p.status() == psutil.STATUS_ZOMBIE:
            return None
        if expected_exe is not None and Path(p.exe()).resolve() != expected_exe.resolve():
            return None
        return p
    except (psutil.Error, ValueError, OSError):
        return None


def part_process(p: dict, part: str) -> psutil.Process | None:
    if l2solo.is_l2solo(p) and part in JARS:
        return l2solo.server_process(p)  # логин и гейм L2Solo — один процесс Node.js
    if part == "brain":
        proc = process(read_pids(p["id"]).get(part))
        try:
            cmd = proc.cmdline() if proc else []
        except psutil.Error:
            return None
        # Из исходников — python -m l2hub.brain, в собранном виде — l2hub-cli.exe brain (start_brain).
        ours = BRAIN_MODULE in cmd or (len(cmd) > 1 and Path(cmd[0]).name.lower() == paths.CLI_EXE.name and cmd[1] == "brain")
        return proc if ours else None
    expected = part_exe(p, part) if part in JARS else catalog.client_exe(p)
    return process(read_pids(p["id"]).get(part), expected)


# ---------- База ----------

def db_running(port: int = 3306) -> bool:
    return port_open(port)


def db_engine(p: dict) -> str:
    return bundle(p)["db"]["engine"] if own_db(p) else "MariaDB"


def bundle_db_data(p: dict) -> Path:
    """Папка данных своей базы сборки-комбайна (у La2offline — виртуальный C:\\MySQL\\Data в песочнице ThinApp, там же *.err)."""
    return catalog.server_dir(p) / bundle(p)["db"]["data"]


def _bundle_cmd(p: dict, args: list[str]) -> list:
    """Команда из описания вида: первый элемент — exe относительно папки server, {port} — порт базы профиля."""
    port = str(p["ports"]["db"])
    return [catalog.server_dir(p) / args[0], *(a.replace("{port}", port) for a in args[1:])]


def start_db(p: dict | None = None) -> None:
    if p and own_db(p):
        _start_bundle_db(p)
        return
    if db_running():
        return
    if not (paths.DB_DIR / "my.ini").exists():
        raise RuntimeError("База не инициализирована: запусти scripts\\db_init.ps1")
    _hidden_console([paths.MARIA_BIN / "mariadbd.exe", f"--defaults-file={paths.DB_DIR / 'my.ini'}"], paths.DB_DIR)
    if not wait_port(3306, 60):
        raise RuntimeError("MariaDB не поднялась за 60 секунд, смотри data\\mariadb\\*.err")


def stop_db(p: dict | None = None) -> None:
    if p and own_db(p):
        _stop_bundle_db(p)
        return
    if not db_running():
        return
    _no_window([paths.MARIA_BIN / "mariadb-admin.exe", f"--defaults-extra-file={paths.CLIENT_CNF}", "shutdown"], timeout=60)
    if not wait_port(3306, 30, closed=True):
        raise RuntimeError("MariaDB не остановилась за 30 секунд")


def _start_bundle_db(p: dict) -> None:
    port = p["ports"]["db"]
    if db_running(port):
        return
    # Как в описании вида: у La2offline — без --console, с которым её запускал он сам, чтобы ошибки шли в *.err в
    # папке данных, а не в скрытую консоль; root без пароля, поэтому база слушает только этот ПК.
    _hidden_console(_bundle_cmd(p, bundle(p)["db"]["start"]), catalog.server_dir(p))
    if not wait_port(port, 60):
        raise RuntimeError(f"База сборки не поднялась за 60 секунд, смотри *.err в {bundle_db_data(p)}")


def _stop_bundle_db(p: dict) -> None:
    port = p["ports"]["db"]
    if not db_running(port):
        return
    _no_window(_bundle_cmd(p, bundle(p)["db"]["stop"]), timeout=60)
    if not wait_port(port, 60, closed=True):
        raise RuntimeError("База сборки не остановилась за 60 секунд")


def _db_client(p: dict) -> list:
    if own_db(p):
        b = bundle(p)
        # Клиент MariaDB к своей базе сборки — с ключами из описания вида (у MySQL 5.0: без TLS и с utf8, а не utf8mb4).
        return [paths.MARIA_BIN / "mariadb.exe", *(a.replace("{port}", str(p["ports"]["db"])) for a in b["db"]["client"])]
    return [paths.MARIA_BIN / "mariadb.exe", f"--defaults-extra-file={paths.CLIENT_CNF}"]


def db_query(p: dict | str, sql: str) -> list[list[str]]:
    """Запрос к базе профиля p; вместо профиля можно имя базы в MariaDB (так зовут старые вызовы)."""
    if isinstance(p, str):
        p = {"database": p}
    r = _no_window([*_db_client(p), p["database"], "-N", "-B", "-e", sql], timeout=10)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "ошибка запроса к базе")
    return [line.split("\t") for line in r.stdout.splitlines() if line]


# Аккаунт фантомов — персонажей без клиента (патч 0007, ключ PhantomAccount в Phantoms.ini). Это не люди:
# в онлайне и в списке персонажей их не показываем. Другое имя — в профиле: "phantoms": {"account": "..."}.
PHANTOM_ACCOUNT = "l2hub_phantom"


def phantom_account(p: dict) -> str:
    """Аккаунт фантомов (напарники и охотники); в профиле можно переопределить: "phantoms": {"account": ...}."""
    account = p.get("phantoms", {}).get("account", PHANTOM_ACCOUNT)
    if not re.fullmatch(r"[A-Za-z0-9_]{1,45}", account):
        raise ValueError(f"неверное имя аккаунта фантомов в профиле: {account!r}")
    return account


def not_phantom_sql(p: dict) -> str:
    """Условие SQL для таблицы characters: персонаж не фантом."""
    return f"account_name <> '{phantom_account(p)}'"


# ---------- Логин и гейм ----------

def _part_command(p: dict, part: str, d: Path) -> list:
    b = bundle(p)
    if b and b.get("runtime") == "ours":
        # Вид на нашей Java (bundles.json): ключи JVM, classpath и main-класс части — из описания вида; cwd — папка части.
        spec = b["parts"][part]
        return [paths.JAVA, *spec.get("jvm", []), "-cp", spec["cp"], spec["main"]]
    if b:
        # Аргументы Java — из строки запуска батника сборки (у La2offline «start /b ..\jre\bin\<exe> ...»):
        # правки автора в батнике учитываются.
        bat = catalog.server_dir(p) / b["parts"][part]["bat"]
        m = re.search(b["batLine"], bat.read_text(encoding="cp1251", errors="replace"), re.M | re.I)
        if not m:
            raise RuntimeError(f"В {bat} нет строки запуска (шаблон batLine в bundles.json)")
        return [part_exe(p, part), *m.group(1).split()]
    java_args = (d / "java.cfg").read_text(encoding="utf-8").splitlines()[0].split()
    return [paths.JAVA, *java_args, "-jar", f"../libs/{JARS[part]}"]


def start_part(p: dict, part: str) -> int:
    if part_process(p, part):
        return read_pids(p["id"])[part]
    d = catalog.server_dir(p) / part
    proc = _hidden_console(_part_command(p, part, d), d)
    pids = read_pids(p["id"])
    pids[part] = proc.pid
    _write_pids(p["id"], pids)
    port = p["ports"][part]
    if not wait_port(port, 180, alive=lambda: proc.poll() is None):
        raise RuntimeError(f"{part}: процесс завершился или не открыл порт {port}, смотри {log_file(p, part)}")
    return proc.pid


def graceful_stop(proc: psutil.Process, timeout: float) -> str:
    """Ctrl+C в консоль процесса и ожидание выхода; если не вышел — жёсткое завершение."""
    helper = subprocess.run(paths.ctrlc_command(proc.pid), creationflags=subprocess.CREATE_NO_WINDOW, timeout=20)
    if helper.returncode == 0:
        try:
            proc.wait(timeout)
            return "graceful"
        except psutil.TimeoutExpired:
            pass
    try:
        proc.kill()
        proc.wait(10)
    except psutil.Error:
        pass
    return "killed"


def stop_part(p: dict, part: str) -> str:
    proc = part_process(p, part)
    result = graceful_stop(proc, STOP_TIMEOUT[part]) if proc else "gone"
    pids = read_pids(p["id"])
    pids.pop(part, None)
    _write_pids(p["id"], pids)
    return result


def kill_all(p: dict) -> list[str]:
    """Аварийно: жёстко завершает только процессы этой хроники по их PID."""
    if l2solo.is_l2solo(p):
        return l2solo.kill(p)
    done = []
    for part in ("client", "game", "login", "brain"):
        proc = part_process(p, part)
        if proc:
            proc.kill()
            done.append(part)
    _write_pids(p["id"], {})
    return done


# ---------- Клиент ----------

def start_client(p: dict) -> int:
    if part_process(p, "client"):
        return read_pids(p["id"])["client"]
    exe = catalog.client_exe(p)
    if not exe.exists():
        raise RuntimeError(f"Нет клиента: {exe}")
    # L2.exe требует администратора в манифесте (requireAdministrator, наследство GameGuard):
    # из обычного процесса CreateProcess падает с WinError 740. RunAsInvoker запускает клиент
    # с правами лаунчера — без UAC, и лаунчер может проверить и закрыть его по PID.
    env = {**os.environ, "__COMPAT_LAYER": "RunAsInvoker"}
    # Клиентам 4game главный ini передаётся аргументом (-INI=.\L2Hub.ini, tools/l2ini_plain.py).
    args = [str(exe), *p["client"].get("args", [])]
    try:
        proc = subprocess.Popen(args, cwd=str(exe.parent), env=env)
    except OSError as e:
        raise RuntimeError(f"Клиент не запустился: {e}") from e
    pids = read_pids(p["id"])
    pids["client"] = proc.pid
    _write_pids(p["id"], pids)
    return proc.pid


def stop_client(p: dict) -> None:
    proc = part_process(p, "client")
    if proc:
        proc.terminate()
        try:
            proc.wait(10)
        except psutil.TimeoutExpired:
            proc.kill()
    pids = read_pids(p["id"])
    pids.pop("client", None)
    _write_pids(p["id"], pids)


# ---------- Мозги фейков ----------

BRAIN_MODULE = "l2hub.brain"


def brain_port(p: dict) -> int | None:
    """Порт сервиса мозгов фейков; None — у профиля мозгов нет (нет раздела brain)."""
    return (p.get("brain") or {}).get("port")


def brain_stderr(p: dict) -> Path:
    return paths.DATA / "brain" / f"{p['id']}.stderr.log"


def start_brain(p: dict) -> int | None:
    """Поднимает l2hub.brain. Не мешает игре: если мозги не встали, фейки отвечают заготовками."""
    port = brain_port(p)
    if not port:
        return None
    if part_process(p, "brain"):
        return read_pids(p["id"])["brain"]
    if paths.FROZEN:
        cmd, cwd = [str(paths.CLI_EXE), "brain", p["id"]], paths.APP
    else:
        # Базовый интерпретатор, а не python.exe из .venv: в uv тот — трамплин, который запускает настоящий
        # интерпретатор дочерним процессом, и лаунчер держал бы не тот PID. Мозгам хватает стандартной библиотеки.
        python = Path(getattr(sys, "_base_executable", sys.executable)).with_name("python.exe")
        cmd, cwd = [str(python), "-m", BRAIN_MODULE, p["id"]], paths.PACKAGE.parent
    log = brain_stderr(p)
    log.parent.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, "PYTHONPATH": str(paths.PACKAGE.parent), "PYTHONUTF8": "1"}
    with log.open("ab") as err:
        proc = subprocess.Popen(cmd, cwd=str(cwd), env=env,
                                creationflags=subprocess.CREATE_NO_WINDOW, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err)
    pids = read_pids(p["id"])
    pids["brain"] = proc.pid
    _write_pids(p["id"], pids)
    wait_port(port, 15, alive=lambda: proc.poll() is None)
    return proc.pid


def stop_brain(p: dict) -> None:
    proc = part_process(p, "brain")
    if proc:
        proc.terminate()
        try:
            proc.wait(5)
        except psutil.TimeoutExpired:
            proc.kill()
    pids = read_pids(p["id"])
    pids.pop("brain", None)
    _write_pids(p["id"], pids)
