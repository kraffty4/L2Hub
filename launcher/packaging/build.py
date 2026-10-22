"""Сборка стандалона L2Hub: PyInstaller (L2Hub.spec) и ресурсы программы рядом с exe — то, что paths.APP ждёт
в собранном виде: патчи, модуль фантомов, скрипты сборки, шаблоны профилей, CHANGELOG и карта хроник.

Из launcher/:  .venv\\Scripts\\python packaging\\build.py [--installer] [--portable]  → dist\\L2Hub\\, с --installer —
dist\\L2Hub-<версия>-setup.exe (Inno Setup 6, L2Hub.iss), с --portable — dist\\L2Hub-<версия>-portable.zip (метка
portable.txt: данные — в home рядом с программой, paths.PORTABLE). build/ и dist/ — не в git.
Данные (runtime, servers, data) в сборку не входят: собранный L2Hub берёт их из папки данных — paths.HOME
(%LOCALAPPDATA%\\L2Hub\\settings.json, {"home": "C:\\\\L2Hub"}; её спрашивает установщик).
"""

import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
LAUNCHER = HERE.parent
REPO = LAUNCHER.parent
DIST = LAUNCHER / "dist" / "L2Hub"
DIRS = ("patches", "phantoms", "scripts", "profiles")
FILES = ("LICENSE", "CHANGELOG.md", "docs/CHRONICLES.md", "THIRD_PARTY.md")
# Пакеты внутри exe, чьи лицензии требуют текст рядом с программой (THIRD_PARTY.md) — в dist\L2Hub\licenses\<пакет>.
LICENSED = ("pywebview", "pythonnet", "clr_loader", "bottle", "proxy_tools", "cffi", "pycparser", "psutil", "pillow", "typing_extensions", "pyinstaller")
# Старая серия патчей H5 — только история, не накладывается (AGENTS.md).
SKIP = shutil.ignore_patterns("history", "__pycache__")


def main() -> int:
    subprocess.run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--log-level", "WARN",
                    "--distpath", str(LAUNCHER / "dist"), "--workpath", str(LAUNCHER / "build"), str(HERE / "L2Hub.spec")],
                   cwd=LAUNCHER, check=True)
    for d in DIRS:
        shutil.copytree(REPO / d, DIST / d, ignore=SKIP, dirs_exist_ok=True)
    for f in FILES:
        (DIST / f).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / f, DIST / f)
    licenses()
    size = sum(f.stat().st_size for f in DIST.rglob("*") if f.is_file())
    print(f"готово: {DIST} — {size / 2**20:.0f} МБ")
    version = re.search(r'__version__ = "([^"]+)"', (LAUNCHER / "l2hub" / "__init__.py").read_text(encoding="utf-8"))[1]
    if "--installer" in sys.argv:
        subprocess.run([str(iscc()), "/Q", f"/DAppVersion={version}", str(HERE / "L2Hub.iss")], check=True)
        setup = LAUNCHER / "dist" / f"L2Hub-{version}-setup.exe"
        print(f"установщик: {setup} — {setup.stat().st_size / 2**20:.0f} МБ")
    if "--portable" in sys.argv:
        out = portable(version)
        print(f"портативный zip: {out} — {out.stat().st_size / 2**20:.0f} МБ")
    return 0


def licenses() -> None:
    """Тексты лицензий пакетов из LICENSED: файлы LICENSE*/COPYING* из их dist-info (у новых — в dist-info/licenses)."""
    site = Path(sys.prefix) / "Lib" / "site-packages"
    for name in LICENSED:
        info = next(iter(sorted(site.glob(f"{name}-*.dist-info"))), None)
        if not info:
            raise SystemExit(f"нет {name} в {site}: лицензию не положить")
        texts = [f for f in info.rglob("*") if f.is_file() and re.match(r"(?i)^(licen[cs]e|copying|notice)", f.name)]
        out = DIST / "licenses" / name
        out.mkdir(parents=True, exist_ok=True)
        for f in texts:
            shutil.copy2(f, out / f.name)
        if not texts:  # у части пакетов текст только в METADATA (License:) — кладём его
            meta = (info / "METADATA").read_text(encoding="utf-8", errors="replace")
            (out / "METADATA-license.txt").write_text("\n".join(l for l in meta.splitlines() if l.startswith(("Name:", "Version:", "License", "Classifier: License"))) + "\n", encoding="utf-8")


PORTABLE_NOTE = """L2Hub — портативная версия.

Пока рядом с L2Hub.exe лежит этот файл, данные (Java, MariaDB, серверы, база, бэкапы, профили) — в подпапке home
рядом с программой, а не там, где их держит установленный L2Hub. Удалить этот файл — L2Hub возьмёт данные
установленного (папку из %LOCALAPPDATA%\\L2Hub\\settings.json).

Путь к папке — только латиница и цифры, без пробелов и кириллицы (например, C:\\Games\\L2Hub): на других
ломаются Java и MariaDB. Места нужно много: одна хроника с базой — 3–6 ГБ.
"""


def portable(version: str) -> Path:
    """dist\\L2Hub-<версия>-portable.zip: та же папка L2Hub с меткой portable.txt (paths.PORTABLE)."""
    out = LAUNCHER / "dist" / f"L2Hub-{version}-portable.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(DIST.rglob("*")):
            if f.is_file():
                z.write(f, f"L2Hub/{f.relative_to(DIST).as_posix()}")
        z.writestr("L2Hub/portable.txt", PORTABLE_NOTE.replace("\n", "\r\n").encode("utf-8-sig"))
    return out


def iscc() -> Path:
    """Компилятор Inno Setup 6: установка на пользователя (winget --scope user) или в Program Files."""
    bases = (Path(os.environ.get("LOCALAPPDATA", "")) / "Programs", Path(os.environ.get("ProgramFiles(x86)", "")),
             Path(os.environ.get("ProgramFiles", "")))
    for base in bases:
        exe = base / "Inno Setup 6" / "ISCC.exe"
        if exe.is_file():
            return exe
    raise SystemExit("Нет Inno Setup 6: winget install --id JRSoftware.InnoSetup -e --scope user")


if __name__ == "__main__":
    sys.exit(main())
