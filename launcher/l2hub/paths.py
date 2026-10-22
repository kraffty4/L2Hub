"""Пути L2Hub: программа и данные раздельно (docs/RELEASE.md, «Программа и установщик»).

APP  — ресурсы программы, только чтение: патчи, модуль фантомов, скрипты сборки, шаблоны профилей, доки.
       Из исходников — корень репозитория; в собранном exe (PyInstaller) — папка рядом с ним.
HOME — данные, сюда пишем: runtime/, servers/, data/, downloads/, profiles/. Путь — переменная L2HUB_HOME,
       иначе у собранного exe — <папка программы>\\home, если рядом с exe лежит portable.txt (портативный zip),
       или %LOCALAPPDATA%\\L2Hub\\settings.json ({"home": "C:\\L2Hub"}, пишет установщик),
       а из исходников — корень репозитория: у автора APP и HOME совпадают и всё лежит как раньше.
HOME — латиница без пробелов: Java, Ant и MariaDB ломаются на кириллице и пробелах (check_home).
"""

import json
import os
import re
import sys
from pathlib import Path

PACKAGE = Path(__file__).resolve().parent
FROZEN = getattr(sys, "frozen", False)
APP = Path(sys.executable).resolve().parent if FROZEN else PACKAGE.parents[1]

SETTINGS = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / "L2Hub" / "settings.json"
PORTABLE = APP / "portable.txt"  # метка портативного zip (build.py --portable)
HOME_RE = re.compile(r"^[A-Za-z]:\\[A-Za-z0-9_.\\-]*$")


def _home() -> Path:
    if os.environ.get("L2HUB_HOME"):
        return Path(os.environ["L2HUB_HOME"])
    if not FROZEN:
        return APP  # из исходников — всегда репозиторий: установленный L2Hub со своим settings.json его не уводит
    if PORTABLE.is_file():
        # Портативный zip: данные рядом с программой, в своей подпапке — в самой папке программы лежат шаблоны профилей.
        return APP / "home"
    try:
        home = json.loads(SETTINGS.read_text(encoding="utf-8")).get("home")
        if home:
            return Path(home)
    except (OSError, ValueError):
        pass
    return APP


def check_home(path: Path) -> str | None:
    """Почему папка данных не годится, или None: только латиница, цифры, точка, дефис и подчёркивание, без пробелов."""
    if not HOME_RE.match(str(path)):
        return "Папка данных — только латиница и цифры без пробелов (на кириллице и пробелах ломаются Java и MariaDB)."
    return None


HOME = _home()
ROOT = HOME  # прежнее имя: всё, что пишется, — от HOME

RUNTIME = HOME / "runtime"
JAVA = RUNTIME / "jdk-27" / "bin" / "java.exe"
MARIA_BIN = RUNTIME / "mariadb" / "bin"

DATA = HOME / "data"
DB_DIR = DATA / "mariadb"
CLIENT_CNF = DATA / "client.cnf"   # пароль root MariaDB; не в git
RUN_DIR = DATA / "run"             # PID запущенных процессов, общий формат со scripts/
BACKUPS = DATA / "backups"

SERVERS = HOME / "servers"
PROFILES = HOME / "profiles"
UI = PACKAGE / "ui"

# Служебные процессы окна в собранном виде — консольный exe рядом с L2Hub.exe (packaging/entry_cli.py).
CLI_EXE = APP / "l2hub-cli.exe"


def ctrlc_command(pid: int) -> list[str]:
    """Команда «Ctrl+C в консоль процесса»: из исходников — скрипт ctrlc.py, в собранном виде — l2hub-cli ctrlc."""
    if FROZEN:
        return [str(CLI_EXE), "ctrlc", str(pid)]
    return [sys.executable, str(PACKAGE / "ctrlc.py"), str(pid)]


# Ресурсы программы (только чтение).
PATCHES = APP / "patches"
PHANTOMS = APP / "phantoms"
SCRIPTS = APP / "scripts"
DOCS = APP / "docs"
CHANGELOG = APP / "CHANGELOG.md"
