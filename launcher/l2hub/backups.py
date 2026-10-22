"""Бэкапы базы хроники в data/backups/db/<id>/.

Mobius (MariaDB) — дамп mariadb-dump: <база>_<время>_<метка>.sql. Снимается и на работающей базе
(--single-transaction, таблицы InnoDB); если MariaDB выключена, она поднимается на время дампа.
Сборки-комбайны со своей базой (bundles.json; La2offline — MySQL 5.0 в ThinApp) — zip папки данных целиком:
<база>_<время>_<метка>.zip, только пока своя база выключена. Дамп клиентом MariaDB 13 в MySQL 5.0 обратно не зальётся (utf8mb4 и прочее),
а копия папки — ровно то, что лежало на диске.
L2Solo (SQLite) — их «Saved Games» в servers/<id>/tmp/saves их же кодом (l2solo.py, l2solo_saves.js), только пока
сервер выключен; авто-сохранений «start» хранится последние l2solo.KEEP_AUTO — это полные копии мира.

Метки лаунчера — LABELS. «start» снимается сам перед каждым запуском, таких хранится последние
KEEP_AUTO, старые уходят в Корзину. Остальные бэкапы, в том числе сделанные руками с любой меткой
(before-phantoms и т.п.), лежат, пока их не уберёт автор.
"""

import ctypes
import re
import subprocess
import zipfile
from ctypes import wintypes
from datetime import datetime
from pathlib import Path

from . import l2solo, paths, servers

NAME_RE = re.compile(r"^(?P<db>\w+?)_(?P<stamp>\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d)_(?P<label>[\w-]+)\.(?P<ext>sql|zip)$")
LABELS = {"manual": "вручную", "start": "перед запуском", "before-restore": "перед откатом", "before-reset": "перед сбросом охотников",
          "before-wipe": "перед вайпом", "before-update": "перед обновлением"}
KEEP_AUTO = 10
LOG = "backups.log"


def backup_dir(p: dict) -> Path:
    return paths.BACKUPS / "db" / p["id"]


def _log(p: dict, message: str) -> None:
    d = backup_dir(p)
    d.mkdir(parents=True, exist_ok=True)
    with (d / LOG).open("a", encoding="utf-8") as f:
        f.write(f"{datetime.now():%Y-%m-%d %H:%M:%S}  {message}\n")


def last_log(p: dict) -> str:
    f = backup_dir(p) / LOG
    lines = f.read_text(encoding="utf-8").splitlines() if f.is_file() else []
    return lines[-1] if lines else ""


def list_backups(p: dict) -> list[dict]:
    if l2solo.is_l2solo(p):
        return l2solo.list_saves(p, LABELS)
    items = []
    d = backup_dir(p)
    for f in d.iterdir() if d.is_dir() else []:
        m = NAME_RE.match(f.name)
        if not m or m["db"] != p["database"] or not f.is_file():
            continue
        day, clock = m["stamp"].split("_")
        items.append({"name": f.name, "time": m["stamp"], "when": f"{day} {clock.replace('-', ':')}", "label": m["label"],
                      "labelText": LABELS.get(m["label"], m["label"]), "size": f.stat().st_size})
    return sorted(items, key=lambda x: x["time"], reverse=True)


def blocker(p: dict) -> str | None:
    """Почему бэкап сейчас нельзя снять; None — можно."""
    if l2solo.is_l2solo(p):
        return l2solo.save_blocker(p)
    if servers.own_db(p) and servers.db_running(p["ports"]["db"]):
        return "Своя база сборки работает, а её папку можно копировать только выключенной. Останови сервер."
    return None


def make(p: dict, label: str) -> dict:
    reason = blocker(p)
    if reason:
        raise RuntimeError(reason)
    if l2solo.is_l2solo(p):
        r = l2solo.make_save(p, label)
        _log(p, f"сохранение L2Solo {r['name']} ({label}), {r['size']} байт")
        return r
    d = backup_dir(p)
    d.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    if servers.own_db(p):
        target = d / f"{p['database']}_{stamp}_{label}.zip"
        _zip_dir(servers.bundle_db_data(p), target)
    else:
        target = d / f"{p['database']}_{stamp}_{label}.sql"
        _with_mariadb(lambda: _dump(p, target))
    _log(p, f"бэкап {target.name}, {target.stat().st_size} байт")
    return {"name": target.name, "size": target.stat().st_size}


def auto(p: dict) -> None:
    """Бэкап перед запуском и ротация. Запуску не мешает: ошибка уходит в backups.log и в «Проверить всё»."""
    try:
        if reason := blocker(p):
            _log(p, f"авто-бэкап пропущен: {reason}")
            return
        make(p, "start")
        rotate(p)
    except Exception as e:  # noqa: BLE001 — любая ошибка бэкапа не должна мешать игре
        _log(p, f"ОШИБКА авто-бэкапа: {e}")


def rotate(p: dict) -> list[str]:
    """Старые бэкапы «перед запуском» сверх KEEP_AUTO — в Корзину."""
    moved = []
    keep = l2solo.KEEP_AUTO if l2solo.is_l2solo(p) else KEEP_AUTO
    for b in [b for b in list_backups(p) if b["label"] == "start"][keep:]:
        to_recycle_bin(l2solo.save_path(p, b["name"]) if l2solo.is_l2solo(p) else backup_dir(p) / b["name"])
        moved.append(b["name"])
    if moved:
        _log(p, f"в Корзину: {', '.join(moved)}")
    return moved


def restore(p: dict, name: str) -> dict:
    """Откат базы к бэкапу. Сервер хроники должен быть остановлен; текущая база сначала сохраняется бэкапом before-restore."""
    if l2solo.is_l2solo(p):
        return _restore_l2solo(p, name)
    m = NAME_RE.match(name or "")
    src = backup_dir(p) / name if m else None
    if not m or m["db"] != p["database"] or not src.is_file():
        raise RuntimeError(f"Нет такого бэкапа: {name}")
    if servers.part_process(p, "game") or servers.part_process(p, "login"):
        raise RuntimeError("Сначала останови сервер: база откатывается только у выключенного гейма и логина.")
    if (m["ext"] == "zip") != servers.own_db(p):
        raise RuntimeError(f"{name} не подходит к базе этой хроники")
    safety = make(p, "before-restore")
    if servers.own_db(p):
        _restore_dir(p, src)
    else:
        _with_mariadb(lambda: _import(p, src))
    _log(p, f"откат к {name}; прежняя база — {safety['name']}")
    return {"restored": name, "safety": safety["name"]}


def _restore_l2solo(p: dict, name: str) -> dict:
    if not any(b["name"] == name for b in list_backups(p)):
        raise RuntimeError(f"Нет такого сохранения: {name}")
    if l2solo.server_process(p):
        raise RuntimeError("Сначала останови сервер: мир откатывается только у выключенного L2Solo.")
    safety = make(p, "before-restore")
    l2solo.load_save(p, name)
    _log(p, f"откат к сохранению L2Solo {name}; прежний мир — {safety['name']}")
    return {"restored": name, "safety": safety["name"]}


# ---------- MariaDB (Mobius) ----------

def _with_mariadb(fn) -> None:
    """Выполнить fn при работающей MariaDB; если она была выключена, после — снова выключить."""
    was_on = servers.db_running()
    if not was_on:
        servers.start_db()
    try:
        fn()
    finally:
        if not was_on:
            servers.stop_db()


def _dump(p: dict, target: Path) -> None:
    tmp = target.with_name(target.name + ".tmp")
    with tmp.open("wb") as out:
        r = subprocess.run([str(paths.MARIA_BIN / "mariadb-dump.exe"), f"--defaults-extra-file={paths.CLIENT_CNF}",
                            "--single-transaction", "--routines", "--events", "--hex-blob", p["database"]],
                           stdout=out, stderr=subprocess.PIPE, timeout=600, creationflags=subprocess.CREATE_NO_WINDOW)
    if r.returncode != 0:
        tmp.unlink(missing_ok=True)  # недописанный дамп
        raise RuntimeError(f"mariadb-dump: {r.stderr.decode('utf-8', 'replace').strip()}")
    tmp.replace(target)


def _import(p: dict, src: Path) -> None:
    # Дамп без CREATE DATABASE: таблицы из него пересоздаются (DROP TABLE IF EXISTS), таблицы, которых в нём нет, остаются.
    with src.open("rb") as inp:
        r = subprocess.run([str(paths.MARIA_BIN / "mariadb.exe"), f"--defaults-extra-file={paths.CLIENT_CNF}", p["database"]],
                           stdin=inp, capture_output=True, timeout=600, creationflags=subprocess.CREATE_NO_WINDOW)
    if r.returncode != 0:
        raise RuntimeError(f"Откат не прошёл: {r.stderr.decode('utf-8', 'replace').strip()}")


# ---------- Папка данных (La2offline) ----------

def _zip_dir(src: Path, target: Path) -> None:
    if not (src / "ibdata1").is_file():
        raise RuntimeError(f"В {src} нет ibdata1 — это не папка данных MySQL")
    tmp = target.with_name(target.name + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(src.rglob("*")):
            if f.is_file() and f.suffix != ".pid":  # PID-файлы — от прошлого запуска, в бэкапе не нужны
                z.write(f, f.relative_to(src).as_posix())
    tmp.replace(target)


def _restore_dir(p: dict, src: Path) -> None:
    data = servers.bundle_db_data(p)
    fresh = data.with_name("Data.restoring")
    if fresh.exists():
        to_recycle_bin(fresh)  # остаток прерванного отката
    with zipfile.ZipFile(src) as z:
        for info in z.infolist():
            if info.filename.startswith(("/", "\\")) or ".." in Path(info.filename).parts:
                raise RuntimeError(f"Подозрительный путь в архиве: {info.filename}")
        z.extractall(fresh)
    if not (fresh / "ibdata1").is_file():
        raise RuntimeError(f"В {src.name} нет ibdata1 — это не бэкап папки данных")
    old = data.with_name(f"Data.replaced-{datetime.now():%Y-%m-%d_%H-%M-%S}")
    data.rename(old)
    fresh.rename(data)
    to_recycle_bin(old)  # её копия — бэкап before-restore


# ---------- Корзина ----------

class _SHFILEOPSTRUCTW(ctypes.Structure):
    _fields_ = [("hwnd", wintypes.HWND), ("wFunc", wintypes.UINT), ("pFrom", wintypes.LPCWSTR), ("pTo", wintypes.LPCWSTR),
                ("fFlags", ctypes.c_uint16), ("fAnyOperationsAborted", wintypes.BOOL), ("hNameMappings", ctypes.c_void_p),
                ("lpszProgressTitle", wintypes.LPCWSTR)]


def to_recycle_bin(path: Path) -> None:
    """В Корзину, а не насовсем. Если файл в Корзину не влезает, Windows спросит, а не удалит молча."""
    FO_DELETE, FOF_SILENT, FOF_NOCONFIRMATION, FOF_ALLOWUNDO, FOF_NOERRORUI, FOF_WANTNUKEWARNING = 3, 0x4, 0x10, 0x40, 0x400, 0x4000
    op = _SHFILEOPSTRUCTW(wFunc=FO_DELETE, pFrom=str(Path(path).resolve()) + "\0",
                          fFlags=FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI | FOF_WANTNUKEWARNING)
    rc = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op))
    if rc != 0 or op.fAnyOperationsAborted:
        raise OSError(f"Не убрал в Корзину {path} (код {rc})")
