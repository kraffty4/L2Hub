"""Поиск клиентов Lineage II по дискам (docs/RELEASE.md, главный принцип: пользователь только достаёт клиент, остальное
делает лаунчер). Клиент — папка, в которой есть система: подпапка с L2.exe или L2.bin (system, system_ru, La2system…).
Обходятся только локальные диски, на несколько уровней вглубь, без системных и облачных папок; на всё — предел времени.
Хронику по клиенту не определяем: у старых L2.exe нет версии в ресурсах, у клиентов 4game она одна на всех."""

import ctypes
import os
import string
import time
from pathlib import Path

from . import catalog

EXES = ("L2.exe", "L2.bin")
MAX_DEPTH = 5           # D:\Games\LA2\Server\<сборка>\La2system — пятый уровень
TIME_LIMIT = 25.0       # секунд на все диски
SKIP = {"windows", "program files", "program files (x86)", "programdata", "$recycle.bin", "system volume information",
        "appdata", "windowsapps", "node_modules", ".git", ".venv", "__pycache__", "recovery", "perflogs", "msocache",
        "$windows.~bt", "$windows.~ws", "onedrive", "servers", "runtime", "downloads"}
FS_OK = {"NTFS", "REFS", "EXFAT", "FAT32", "FAT"}
# Облачные диски выдают себя за локальные (Google Drive для ПК — FAT32 с такой меткой): обход — минуты подкачки.
CLOUD_LABELS = ("google drive", "onedrive", "dropbox", "yandex", "icloud", "mega")
DRIVE_FIXED = 3

k32 = ctypes.WinDLL("kernel32", use_last_error=True)


def drives() -> list[str]:
    """Локальные диски с обычной файловой системой, без облачных (Google Drive и т. п. — по метке тома)."""
    out = []
    for letter in string.ascii_uppercase:
        root = f"{letter}:\\"
        if not os.path.exists(root) or k32.GetDriveTypeW(root) != DRIVE_FIXED:
            continue
        fs, label = ctypes.create_unicode_buffer(32), ctypes.create_unicode_buffer(64)
        if not k32.GetVolumeInformationW(root, label, 64, None, None, None, fs, 32) or fs.value.upper() not in FS_OK:
            continue
        if not any(c in label.value.lower() for c in CLOUD_LABELS):
            out.append(root)
    return out


def _systems(d: Path) -> list[str]:
    """Подпапки d, где лежит exe клиента."""
    out = []
    try:
        for e in os.scandir(d):
            if e.is_dir(follow_symlinks=False) and any(os.path.isfile(os.path.join(e.path, x)) for x in EXES):
                out.append(e.name)
    except OSError:
        pass
    return sorted(out)


def find(limit: float = TIME_LIMIT) -> dict:
    """Клиенты на дисках: путь, системы (подпапки с exe), какая хроника уже на нём. timeout — обход не успел до конца."""
    start = time.monotonic()
    used = {}
    for p in catalog.load_profiles().values():
        path = (p.get("client") or {}).get("path")
        if path:
            used.setdefault(os.path.normcase(os.path.normpath(path)), []).append(p["id"])
    found, timeout = [], False
    # По уровням сразу на всех дисках: клиенты обычно неглубоко (D:\Games\…), и предел времени не съест один диск.
    queue = [(Path(r), 0) for r in drives()]
    while queue:
        if time.monotonic() - start > limit:
            timeout = True
            break
        d, depth = queue.pop(0)
        systems = _systems(d)
        if systems:
            found.append({"path": str(d), "systems": systems, "used": used.get(os.path.normcase(os.path.normpath(str(d))), [])})
            continue  # внутри клиента другого клиента не ищем
        if depth >= MAX_DEPTH:
            continue
        try:
            subs = [Path(e.path) for e in os.scandir(d) if e.is_dir(follow_symlinks=False) and e.name.lower() not in SKIP
                    and not e.name.startswith(("$", "."))]
        except OSError:
            continue
        queue.extend((s, depth + 1) for s in subs)
    return {"items": found, "timeout": timeout, "seconds": round(time.monotonic() - start, 1)}
