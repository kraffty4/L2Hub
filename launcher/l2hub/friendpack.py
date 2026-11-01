"""Пакет клиента для друзей: zip клиента профиля, готовый к запуску по сети способа (Radmin, Tailscale, ZeroTier, локальная,
«Интернет» — внешний адрес роутера хоста).

Что внутри (верхняя папка — как у клиента хоста, например «LA2 H5»):
- все папки клиента, кроме систем: из них берётся только та, из которой запускается клиент профиля
  (system_ru у H5, La2system у GF), остальные папки с «system» в имени не берутся;
- папка системы в пакете называется так, где её L2.ini ищет классы (Paths=../<папка>/*.u,
  network.client_system_name): у H5 автора клиент запускается из копии system_ru, а классы берёт из соседней
  ../System, поэтому в пакете содержимое system_ru лежит в папке system — без неё клиент падает на первой текстуре;
- в папке системы — L2.ini с адресом хоста в сети способа (network.state) вместо 127.0.0.1; у клиентов 4game
  (Classic, Essence) L2.ini на ключе NCSoft остаётся штатным, а адрес — в открытом L2Hub.ini (clientsys), Play.bat
  запускает L2.exe -INI=.\\L2Hub.ini;
- без личного и временного: логи, раскладка окон, тайминги, кэш шейдеров, скриншоты (и папка Screenshot в корне клиента);
- README.txt и Play.bat в корне;
- у системы с ловушкой локали (clientsys.needs_ru_locale, docs/RU_CHAT.md, «Капкан») — три localization: сам
  localization.ini и localization_en.ini с Language=1, localization_ru.ini — исходный с Language=8. Play.bat при каждом
  запуске ставит нужный по языку Windows друга, а Language=1 по умолчанию не даёт упасть и при запуске L2.exe напрямую;
- кроме клиентов 4game — ChatPaste.ps1 в корне: Ctrl+V в чате (clientsys, docs/RU_CHAT.md, «Вставка в чат»). Play.bat запускает
  его и клиент без повышенных прав: окну администратора скрипт символы не пошлёт.
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
SKIP_DIRS = {"screenshot", "screenshots"}  # верхние папки клиента: сюда клиент кладёт снимки экрана
LOCALIZATION = ("localization.ini", "localization_en.ini", "localization_ru.ini")  # у системы с ловушкой локали их пишет build
_addresses: dict[Path, tuple[tuple[int, int], str | None]] = {}  # pack_address: архив → (его время и размер, адрес)


def pack_path(p: dict) -> Path:
    client = catalog.client_exe(p).parent.parent
    return network.FRIENDS / p["id"] / f"{client.name.replace(' ', '_')}.zip"


def pack_address(p: dict) -> str | None:
    """ServerAddr внутри собранного пакета или None, если его не прочесть. Оглавление архива читается не мгновенно,
    а окно перерисовывается часто: ответ держится, пока не изменится сам архив."""
    pack = pack_path(p)
    st = pack.stat()
    mark = (st.st_mtime_ns, st.st_size)
    seen = _addresses.get(pack)
    if seen and seen[0] == mark:
        return seen[1]
    system = catalog.client_exe(p).parent
    inside = f"{system.parent.name}/{network.client_system_name(p)}/{clientsys.INI if clientsys.is_4game(p) else 'L2.ini'}"
    try:
        with zipfile.ZipFile(pack) as z:
            address = l2ini.server_addr_of(z.read(inside))
    except (OSError, KeyError, ValueError, zipfile.BadZipFile):
        address = None
    _addresses[pack] = (mark, address)
    return address


def _files(p: dict, target: str, skip: tuple[str, ...] = ()):
    """(путь на диске, путь в архиве) — что идёт в пакет; папка системы профиля кладётся под именем target.
    Ini с адресом (L2.ini, у клиентов 4game — L2Hub.ini) не берётся: в пакет пишется свой, с адресом хоста.
    skip — ещё файлы папки системы (имена в нижнем регистре), которые build пишет сам."""
    system = catalog.client_exe(p).parent
    ini = clientsys.INI.lower() if clientsys.is_4game(p) else "l2.ini"
    client = system.parent
    for top in sorted(client.iterdir()):
        if top.is_dir() and top.name.lower() in SKIP_DIRS:
            continue
        if top.is_dir() and "system" in top.name.lower() and top != system:
            continue
        items = [top] if top.is_file() else sorted(f for f in top.rglob("*") if f.is_file())
        for f in items:
            if f.parent == system and (SKIP_FILES.match(f.name) or f.name.lower() == ini or f.name.lower() in skip):
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


def _join_internet(address: str) -> list[str]:
    """«Интернет»: ставить ничего не нужно — сервер хоста открыт в интернет на внешнем адресе его роутера."""
    return [f"1. Ничего ставить не нужно: сервер хоста открыт в интернет, адрес {address}.",
            "   Играй с домашнего интернета: мобильный в части регионов пускает только по «белым спискам»."]


def _locale_note(target: str) -> list[str]:
    """README системы с ловушкой локали: что Play.bat делает сам и как писать по-русски на нерусской Windows."""
    return [
        "Чат по-русски работает только на Windows с русским языком системы. На другом языке клиент в русском режиме",
        "падает с ошибкой Runtime Error, поэтому Play.bat при каждом запуске сам выбирает режим: на нерусской Windows",
        "игра работает, но в чат набирается только латиница.",
        "Как писать по-русски: Win+R, intl.cpl, вкладка «Дополнительно», «Изменить язык системы», Русский, перезагрузка.",
        f"Запускай через Play.bat: {target}\\L2.exe напрямую режим не переключает.",
        "",
    ]


def _paste_note() -> list[str]:
    """README: вставка в чат через ChatPaste.ps1."""
    return [
        "Ctrl+V в чате вставляет текст из буфера обмена, если игра запущена через Play.bat. Перевод строки станет пробелом,",
        f"сообщение отправляешь сам. Этим занимается {clientsys.CHAT_PASTE} рядом с Play.bat, обычный текстовый скрипт:",
        "без него игра работает, только без вставки. На нерусской Windows кириллица в чат может не вставиться.",
        "",
    ]


def _readme(p: dict, address: str, target: str, kind: str | None, locale_switch: bool = False, paste: bool = False) -> str:
    client = catalog.client_exe(p).parent.parent
    ini = clientsys.INI if clientsys.is_4game(p) else "L2.ini"
    title = next((c["name"] for c in catalog.load_catalog() if c["id"] == p["id"]), p["id"])
    how = network.label(kind)
    return "\r\n".join([
        f"Lineage II {title} — клиент для игры на сервере хоста (L2Hub), сеть: {how}.",
        "",
        *(_join_internet(address) if kind == "internet" else JOIN.get(kind, [f"1. Подключись к сети, где у хоста адрес {address}."])),
        f"2. Распаковать архив, например в C:\\Games (получится C:\\Games\\{client.name}).",
        "3. Запуск — Play.bat." if paste else
        "3. Запуск — Play.bat. Windows спросит права администратора — разреши." if locale_switch else
        f"3. Запуск — Play.bat (или {target}\\L2.exe). Windows спросит права администратора — разреши.",
        "4. Логин и пароль — любые новые: аккаунт создастся сам при первом входе.",
        "",
        f"Адрес сервера уже прописан в {target}\\{ini}: {address} (адрес хоста, {how}).",
        f"Если хост сменит адрес или способ, он пришлёт новый {target}\\{ini} — положить с заменой.",
        "",
        *(_locale_note(target) if locale_switch else []),
        *(_paste_note() if paste else []),
        *_bypass(address, target, kind),
    ])


def _bypass(address: str, target: str, kind: str | None) -> list[str]:
    """README: игра рядом с обходом блокировок (docs/FRIENDS.md, «Друг с Zapret и VPN»). Игре не нужны ни Zapret, ни VPN —
    им надо сказать, чтобы не трогали адрес хоста; у Radmin это вся его сеть. Локальную сеть они и так не трогают."""
    if kind == "lan":
        return []
    net = "26.0.0.0/8" if kind == "radmin" else address
    return [
        "Если у тебя стоит Zapret (обход для Discord и YouTube) или VPN для сайтов — игре они не нужны, пусть её не трогают:",
        f"- Zapret: в его папке открой lists\\ipset-exclude.txt (так у сборки Flowseal), допиши отдельной строкой {net}",
        "  и перезапусти Zapret (service.bat или как ты его запускаешь). Не пускает и так — выключи в нём Game Filter.",
        "- VPN: в настройках найди исключения (раздельное туннелирование, split tunneling, «напрямую», direct/bypass)",
        f"  и добавь туда {net} или программу {target}\\L2.exe. Иначе игра пойдёт через VPN — пинг и обрывы.",
        f"- Игре нужны порты TCP 2106 (логин) и 7777 (мир) на адресе {address}.",
        "",
    ]


def _play_bat(target: str, run: str, locale_switch: bool, paste: bool = False) -> str:
    """Play.bat: только ASCII и CRLF. locale_switch — система с ловушкой локали: localization.ini ставится по языку Windows.
    Локаль берётся из реестра, а в выводе reg имя значения локализовано: ищем само значение, а не разбираем столбцы.
    paste — вставка в чат: клиент без повышенных прав (манифест L2.exe требует администратора, RunAsInvoker это снимает, как в лаунчере)."""
    lines = ["@echo off", f'cd /d "%~dp0{target}"']
    if locale_switch:
        locales = " ".join(f"{lcid:08X}" for lcid in clientsys.RU_LOCALES)
        lines += [
            "rem L2.exe with Language=8 runs only on Russian or Korean system locale",
            f'reg query "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Nls\\Locale" /ve | findstr "{locales}" >nul',
            "if errorlevel 1 (copy /y localization_en.ini localization.ini >nul) else (copy /y localization_ru.ini localization.ini >nul)",
        ]
    if paste:
        lines += [
            "rem Ctrl+V in chat: the script cannot post to an elevated client window",
            "set __COMPAT_LAYER=RunAsInvoker",
            f'start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0{clientsys.CHAT_PASTE}"',
        ]
    lines.append(f'start "" {run}')
    return "\r\n".join(lines) + "\r\n"


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
    locale_switch = clientsys.needs_ru_locale(system)
    paste = clientsys.chat_paste_on(p)
    out = pack_path(p)
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_name(out.name + ".tmp")
    files = 0
    raw = 0
    with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
        for src, arc in _files(p, target, LOCALIZATION if locale_switch else ()):
            stored = arc.split("/")[1].lower() in STORE_DIRS
            z.write(src, arc, compress_type=zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED, compresslevel=None if stored else 1)
            files += 1
            raw += src.stat().st_size
        top = system.parent.name
        z.writestr(f"{top}/{target}/{ini_name}", friend_ini, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr(f"{top}/README.txt", "\ufeff" + _readme(p, address, target, net["kind"], locale_switch, paste), compress_type=zipfile.ZIP_DEFLATED)
        z.writestr(f"{top}/Play.bat", _play_bat(target, run, locale_switch, paste), compress_type=zipfile.ZIP_DEFLATED)
        if paste:
            z.writestr(f"{top}/{clientsys.CHAT_PASTE}", clientsys.chat_paste_script().read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
            files += 1
        if locale_switch:
            latin = clientsys.latin_localization(system)
            russian = (system / "localization.ini").read_bytes()
            for name, data in zip(LOCALIZATION, (latin, latin, russian)):
                z.writestr(f"{top}/{target}/{name}", data, compress_type=zipfile.ZIP_DEFLATED)
                files += 1
    if out.exists():
        backups.to_recycle_bin(out)  # прежний пакет
    tmp.replace(out)
    return {"path": str(out), "size": out.stat().st_size, "files": files + 3, "raw": raw, "address": address, "system": target}
