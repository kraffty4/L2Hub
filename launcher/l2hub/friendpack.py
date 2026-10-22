"""Пакет клиента для друзей: zip клиента профиля, готовый к запуску по сети способа (Radmin, Tailscale, ZeroTier, локальная).

Что внутри (верхняя папка — как у клиента хоста, например «LA2 H5»):
- все папки клиента, кроме систем: из них берётся только та, из которой запускается клиент профиля
  (system_ru у H5, La2system у GF), остальные папки с «system» в имени не берутся;
- папка системы в пакете называется так, где её L2.ini ищет классы (Paths=../<папка>/*.u,
  network.client_system_name): у H5 автора клиент запускается из копии system_ru, а классы берёт из соседней
  ../System, поэтому в пакете содержимое system_ru лежит в папке system — без неё клиент падает на первой текстуре;
- в папке системы — L2.ini с адресом хоста в сети способа (network.state) вместо 127.0.0.1; у клиентов 4game
  (Classic, Essence) L2.ini на ключе NCSoft остаётся штатным, а адрес — в открытом L2Hub.ini (clientsys), Play.bat
  запускает L2.exe -INI=.\\L2Hub.ini;
- без личного и временного: логи, раскладка окон, тайминги, кэш шейдеров, скриншоты;
- README.txt и Play.bat в корне.
Звуки и музыка почти не сжимаются — кладутся как есть, остальное — deflate 1 (быстро, ~0,6 от исходного).
Архив — data/friends/<id>/<папка клиента>.zip; прежний уходит в Корзину. Клиенты NCSoft никуда не выкладываются:
архив автор передаёт друзьям сам.
"""

import re
import zipfile
from pathlib import Path

from . import backups, catalog, clientsys, l2ini, network

SKIP_FILES = re.compile(r"(?i)^(.*\.log|windowsinfo\.ini|s_info\.ini|l2compiledshader\.bin|shot\d+\.(bmp|jpg|png))$")
STORE_DIRS = {"sounds", "music", "voice"}  # ogg/uax — почти несжимаемы


def pack_path(p: dict) -> Path:
    client = catalog.client_exe(p).parent.parent
    return network.FRIENDS / p["id"] / f"{client.name.replace(' ', '_')}.zip"


def _files(p: dict, target: str):
    """(путь на диске, путь в архиве) — что идёт в пакет; папка системы профиля кладётся под именем target.
    Ini с адресом (L2.ini, у клиентов 4game — L2Hub.ini) не берётся: в пакет пишется свой, с адресом хоста."""
    system = catalog.client_exe(p).parent
    ini = clientsys.INI.lower() if clientsys.is_4game(p) else "l2.ini"
    client = system.parent
    for top in sorted(client.iterdir()):
        if top.is_dir() and "system" in top.name.lower() and top != system:
            continue
        items = [top] if top.is_file() else sorted(f for f in top.rglob("*") if f.is_file())
        for f in items:
            if f.parent == system and (SKIP_FILES.match(f.name) or f.name.lower() == ini):
                continue
            rel = f.relative_to(client).as_posix()
            if top == system:
                rel = target + rel[len(system.name):]
            yield f, f"{client.name}/{rel}"


# Первый шаг README — как попасть в одну сеть с хостом, по способу (network.KINDS).
JOIN = {
    "radmin": ["1. Radmin VPN: https://www.radmin-vpn.com/ — поставить, «Сеть» → «Присоединиться к сети»,",
               "   имя и пароль сети спроси у хоста."],
    "tailscale": ["1. Tailscale: https://tailscale.com/download — поставить и войти по приглашению хоста",
                  "   (он пришлёт ссылку из своей админки Tailscale)."],
    "zerotier": ["1. ZeroTier One: https://www.zerotier.com/download/ — поставить, «Join New Network»,",
                 "   ID сети спроси у хоста; он должен одобрить тебя в своей сети."],
    "lan": ["1. Подключись к той же сети, что и хост: тот же Wi-Fi или роутер."],
}


def _readme(p: dict, address: str, target: str, kind: str | None) -> str:
    client = catalog.client_exe(p).parent.parent
    ini = clientsys.INI if clientsys.is_4game(p) else "L2.ini"
    title = next((c["name"] for c in catalog.load_catalog() if c["id"] == p["id"]), p["id"])
    how = network.label(kind)
    return "\r\n".join([
        f"Lineage II {title} — клиент для игры на сервере хоста (L2Hub), сеть: {how}.",
        "",
        *JOIN.get(kind, [f"1. Подключись к сети, где у хоста адрес {address}."]),
        f"2. Распаковать архив, например в C:\\Games (получится C:\\Games\\{client.name}).",
        f"3. Запуск — Play.bat (или {target}\\L2.exe). Windows спросит права администратора — разреши.",
        "4. Логин и пароль — любые новые: аккаунт создастся сам при первом входе.",
        "",
        f"Адрес сервера уже прописан в {target}\\{ini}: {address} (адрес хоста, {how}).",
        f"Если хост сменит адрес или способ, он пришлёт новый {target}\\{ini} — положить с заменой.",
        "",
    ])


def build(p: dict) -> dict:
    net = network.state(p)
    address = net["address"] if net["mode"] == "friends" else None
    if not address:
        raise RuntimeError("Сначала выбери в «Друзьях», как друзья подключаются: адрес сервера в пакете — адрес этого ПК в той сети.")
    system = catalog.client_exe(p).parent
    target = network.client_system_name(p)
    four = clientsys.is_4game(p)
    raw_ini = (system / "L2.ini").read_bytes()
    friend_ini = clientsys.plain_ini(raw_ini, address) if four else l2ini.with_server_addr(raw_ini, address)
    ini_name = clientsys.INI if four else "L2.ini"
    run = f"L2.exe -INI=.\\{clientsys.INI}" if four else "L2.exe"
    out = pack_path(p)
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_name(out.name + ".tmp")
    files = 0
    raw = 0
    with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
        for src, arc in _files(p, target):
            stored = arc.split("/")[1].lower() in STORE_DIRS
            z.write(src, arc, compress_type=zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED, compresslevel=None if stored else 1)
            files += 1
            raw += src.stat().st_size
        top = system.parent.name
        z.writestr(f"{top}/{target}/{ini_name}", friend_ini, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr(f"{top}/README.txt", "\ufeff" + _readme(p, address, target, net["kind"]), compress_type=zipfile.ZIP_DEFLATED)
        z.writestr(f"{top}/Play.bat", f'@echo off\r\ncd /d "%~dp0{target}"\r\nstart "" {run}\r\n', compress_type=zipfile.ZIP_DEFLATED)
    if out.exists():
        backups.to_recycle_bin(out)  # прежний пакет
    tmp.replace(out)
    return {"path": str(out), "size": out.stat().st_size, "files": files + 3, "raw": raw, "address": address, "system": target}
