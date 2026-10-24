"""Обновления L2Hub (docs/RELEASE.md, «Обновления»): последняя версия — GitHub Releases репозитория из updates.json
(для проверок — L2HUB_UPDATES_API, полный адрес ответа releases/latest). Установщик скачивается в downloads/updates,
сверяется с SHA256SUMS.txt того же релиза (его пишет .github/workflows/release.yml) и запускается тихо, а L2Hub
закрывается: установщик заменит программу, папка данных не трогается.

Ставить умеет только установленный L2Hub: у портативного и у запуска из исходников — только «что вышло» и ссылка.
Запрос к GitHub — без данных пользователя."""

import hashlib
import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

from . import __version__, i18n, paths

CONFIG = paths.PACKAGE / "updates.json"
API = "https://api.github.com/repos/{repo}/releases/latest"
TIMEOUT = 15
# Строки-переключатели языка в заметках релиза: в окне они не нужны.
LANG_LINES = {"Русский", "English", "Русский · English"}


def repo() -> str:
    try:
        return json.loads(CONFIG.read_text(encoding="utf-8")).get("repo") or ""
    except (OSError, ValueError):
        return ""


def _api() -> str:
    return os.environ.get("L2HUB_UPDATES_API") or (API.format(repo=repo()) if repo() else "")


def _get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": f"L2Hub/{__version__}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:  # noqa: S310 — адрес из updates.json
        return r.read()


def version_tuple(v: str) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def notes_text(body: str, lang: str) -> str:
    """Заметки релиза для окна обновления: часть на языке окна и без разметки. Заметки пишутся так: русская часть,
    строка «---», английская (docs/GITHUB.md); одной частью — она и показывается."""
    parts = re.split(r"^\s*---\s*$", (body or "").replace("\r\n", "\n"), flags=re.MULTILINE)
    text = parts[1] if lang == "en" and len(parts) > 1 else parts[0]
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\*\*|__|`", "", text)
    lines = [ln.rstrip() for ln in text.split("\n") if ln.strip() not in LANG_LINES]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def latest() -> dict:
    data = json.loads(_get(_api()))
    assets = {a["name"]: a for a in data.get("assets", [])}
    setup = next((a for n, a in assets.items() if n.endswith("-setup.exe")), None)
    return {"version": str(data.get("tag_name", "")).lstrip("v"), "page": data.get("html_url"),
            "notes": notes_text(data.get("body") or "", i18n.lang())[:3000],
            "setup": setup and {"name": setup["name"], "url": setup["browser_download_url"], "size": setup.get("size")},
            "sums": (assets.get("SHA256SUMS.txt") or {}).get("browser_download_url")}


def can_install() -> bool:
    """Ставить поверх умеет только установленный L2Hub: не портативный и не из исходников."""
    return paths.FROZEN and not paths.PORTABLE.is_file()


def check() -> dict:
    if not _api():
        return {"enabled": False, "current": __version__}
    out = {"enabled": True, "current": __version__}
    try:
        lt = latest()
    except urllib.error.HTTPError as e:
        return {**out, "error": "Релизов пока нет" if e.code == 404 else f"GitHub ответил {e.code}"}
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        return {**out, "error": f"Нет связи с GitHub: {getattr(e, 'reason', e)}"}
    newer = version_tuple(lt["version"]) > version_tuple(__version__)
    return {**out, "latest": lt["version"], "newer": newer, "page": lt["page"], "notes": lt["notes"],
            "canInstall": newer and can_install() and bool(lt["setup"] and lt["sums"]), "size": (lt["setup"] or {}).get("size")}


def download(note=lambda text, frac=None: None, cancelled=lambda: False) -> Path:
    """Установщик последнего релиза в downloads/updates, сверенный по SHA256SUMS.txt; уже скачанный и целый — не качаем."""
    lt = latest()
    setup = lt["setup"]
    if not setup or not lt["sums"]:
        raise RuntimeError("В релизе нет установщика или SHA256SUMS.txt")
    sums = _get(lt["sums"]).decode("utf-8", errors="replace")
    expected = next((m[1].lower() for m in re.finditer(r"^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$", sums, re.MULTILINE) if m[2] == setup["name"]), None)
    if not expected:
        raise RuntimeError(f"В SHA256SUMS.txt нет {setup['name']}")
    out = paths.HOME / "downloads" / "updates" / setup["name"]
    if out.is_file() and _sha256(out) == expected:
        return out
    out.parent.mkdir(parents=True, exist_ok=True)
    part = out.with_name(out.name + ".part")
    req = urllib.request.Request(setup["url"], headers={"User-Agent": f"L2Hub/{__version__}"})
    with urllib.request.urlopen(req, timeout=60) as r, part.open("wb") as f:  # noqa: S310
        size, done = setup.get("size") or 0, 0
        while chunk := r.read(1 << 20):
            if cancelled():
                raise RuntimeError("Скачивание остановлено")
            f.write(chunk)
            done += len(chunk)
            note(f"скачано {done / 2**20:.0f}" + (f" из {size / 2**20:.0f} МБ" if size else " МБ"), done / size if size else None)
    if _sha256(part) != expected:
        part.unlink(missing_ok=True)
        raise RuntimeError("Контрольная сумма установщика не сошлась — файл битый или подменён, ничего не ставлю")
    part.replace(out)
    return out


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def launch(setup: Path) -> None:
    """Тихая установка поверх: папку данных установщик берёт из прежнего settings.json, задачи — прежние; /UPDATE=1 —
    после установки он сам запустит новый L2Hub (L2Hub.iss, IsUpdate)."""
    subprocess.Popen([str(setup), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/UPDATE=1"], close_fds=True,  # noqa: S603 — сверенный по SHA-256 установщик
                     creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
