"""Сеть хроники: как друзья доходят до сервера на этом ПК.

Способы (KINDS): local — только этот ПК (всё на 127.0.0.1); radmin, tailscale, zerotier — VPN-сети; lan — локальная
сеть (Wi-Fi, роутер); internet — напрямую через интернет на внешний (белый) адрес роутера, без VPN-программ: порты
логина и гейма пробрасываются на роутере по UPnP (upnp.py) или вручную (docs/FRIENDS.md, «Способ „Интернет“»).
Выделенный сервер на арендованной машине — в планах: L2Hub поднимает сервер только на этом ПК.
Способ один на все хроники (решение автора 2026-10-20) — его ставит Api.set_network_all во все установленные.

Режим живёт в самих конфигах сервера, отдельного файла нет:
- Mobius: логин и гейм слушают 127.0.0.1 или все сети (0.0.0.0); ipconfig.xml отдаёт клиенту адрес гейма
  по его подсети: с этого ПК — 127.0.0.1, из сети способа (Radmin 26.0.0.0/8, сеть адаптера Tailscale, ZeroTier
  или локальной сети) — адрес этого ПК в ней, остальным — тот же адрес. У «Интернета»: из локальной сети —
  адрес этого ПК в ней (заход с внешнего адреса изнутри сети роутеры умеют не все), остальным — внешний адрес.
- La2offline (L2J 2.0.6): Hostname в .properties («*» — все сети). Логин отдаёт клиентам из «внутренних» сетей
  (10, 172.16, 192.168, 127) InternalHostname гейма, остальным — ExternalHostname. Во внешний ставится адрес способа;
  во внутренний — тоже он, если адрес частный (локальная сеть, обычно ZeroTier), иначе «*», как в оригинале;
  у «Интернета» внешний — адрес роутера, внутренний — адрес этого ПК в локальной сети.
Сервер читает конфиги при старте: новый режим работает после перезапуска.

Друзьям нужен их L2.ini с адресом этого ПК в сети способа — friend_l2ini() собирает его из L2.ini клиента профиля.
"""

import ipaddress
import re
import shutil
import socket
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

import psutil

from . import catalog, clientsys, configs, l2ini, paths, servers, upnp

KINDS = {"local": "Только этот ПК", "radmin": "Radmin VPN", "tailscale": "Tailscale", "zerotier": "ZeroTier", "lan": "Локальная сеть",
         "internet": "Интернет"}
PORTS = {"логин": 2106, "гейм": 7777}  # общие у всех профилей (profiles/*.json, "ports")
RADMIN = ipaddress.ip_network("26.0.0.0/8")
TAILSCALE = ipaddress.ip_network("100.64.0.0/10")  # CGNAT, его раздаёт Tailscale
L2J_INTERNAL = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")]
# Виртуальные адаптеры — не локальная сеть: Hyper-V, WSL, Docker, виртуалки, Bluetooth.
VIRTUAL = re.compile(r"vEthernet|VirtualBox|VMware|Hyper-V|WSL|Docker|Bluetooth|Npcap|Loopback", re.IGNORECASE)
LOCAL = "127.0.0.1"
FRIENDS = paths.DATA / "friends"

IPCONFIG_HEAD = ('<?xml version="1.0" encoding="UTF-8"?>',)
IPCONFIG_ROOT = '<gameserver address="{0}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../data/xsd/ipconfig.xsd">'


def _kind(adapter: str, ip: ipaddress.IPv4Address) -> str | None:
    name = adapter.lower()
    if ip in RADMIN or "radmin" in name:
        return "radmin" if ip in RADMIN else None
    if ip in TAILSCALE or "tailscale" in name:
        return "tailscale"
    if "zerotier" in name:
        return "zerotier"
    if ip.is_private and not VIRTUAL.search(adapter):
        return "lan"
    return None


def candidates() -> list[dict]:
    """Адреса этого ПК по способам на поднятых адаптерах: [{kind, address, network, adapter}]."""
    stats = psutil.net_if_stats()
    out = []
    for name, addrs in psutil.net_if_addrs().items():
        if not (stats.get(name) and stats[name].isup):
            continue
        for a in addrs:
            if a.family != socket.AF_INET:
                continue
            ip = ipaddress.ip_address(a.address)
            if ip.is_loopback or ip.is_link_local:
                continue
            kind = _kind(name, ip)
            if kind:
                net = ipaddress.ip_interface(f"{a.address}/{a.netmask}").network if a.netmask else ipaddress.ip_network(f"{a.address}/32")
                out.append({"kind": kind, "address": a.address, "network": str(net), "adapter": name})
    return out


def radmin_addresses() -> list[str]:
    """Адреса этого ПК в сети Radmin VPN (26.0.0.0/8) на поднятых адаптерах."""
    return [c["address"] for c in candidates() if c["kind"] == "radmin"]


def _range_kind(address: str) -> str:
    """Способ по самому адресу, когда адаптера с ним сейчас нет (VPN выключен)."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return "custom"
    return ("radmin" if ip in RADMIN else "tailscale" if ip in TAILSCALE else "lan" if ip.is_private
            else "internet" if ip.version == 4 and ip.is_global else "custom")


# ---------- Интернет: внешний адрес роутера и проброс портов ----------

def lan_sources() -> list[str]:
    """Адреса этого ПК в локальной сети — с них ищется роутер."""
    return [c["address"] for c in candidates() if c["kind"] == "lan"]


def internet_probe(force: bool = False) -> dict:
    """Роутер по UPnP: внешний адрес, серый ли он, проброшены ли порты (upnp.probe, кэш минуту) + адреса этого ПК в локальной сети."""
    return {**upnp.probe(lan_sources(), PORTS, force=force), "lan": lan_sources(), "need": PORTS}


def internet_cached() -> dict | None:
    """Последний ответ роутера по UPnP без похода в сеть (или None — ещё не спрашивали)."""
    return upnp.cached()


def _internet_lan() -> tuple[str, str] | None:
    """(адрес, сеть) этого ПК в локальной сети, за роутером: тот, с которого виден роутер по UPnP, иначе первый."""
    lan = [c for c in candidates() if c["kind"] == "lan"]
    seen = (upnp.cached() or {}).get("localIp")
    c = next((c for c in lan if c["address"] == seen), lan[0] if lan else None)
    return (c["address"], c["network"]) if c else None


def internet_ports(open_: bool) -> dict:
    """Открыть или закрыть на роутере по UPnP порты логина и гейма. Закрываются только свои (на этот ПК, «L2Hub …»).
    Нет UPnP — {"ok": False, "error": почему}: тогда порты пробрасываются в роутере вручную."""
    try:
        gw = upnp.gateway(lan_sources())
        done = []
        for what, port in PORTS.items():
            if open_:
                upnp.open_port(gw, port, what)
                done.append(port)
            elif upnp.close_port(gw, port):
                done.append(port)
    except (RuntimeError, OSError, ET.ParseError) as e:
        return {"ok": False, "error": str(e)}
    upnp.probe(lan_sources(), PORTS, force=True)  # окно «Друзей» видит новые пробросы
    return {"ok": True, "ports": done, "localIp": gw.local_ip}


IPIFY = "https://api.ipify.org"


def lookup_external() -> str:
    """Внешний адрес через ipify.org — только по кнопке в окне (сторонний сервис узнаёт адрес), когда роутер без UPnP.
    Мимо системного прокси; VPN в режиме TUN подменит адрес на свой — окно просит выключить его на время."""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    address = opener.open(IPIFY, timeout=6).read(64).decode("ascii", "replace").strip()
    if _range_kind(address) != "internet":
        raise RuntimeError(f"ipify.org ответил не внешним IPv4: {address[:40]!r}")
    return address


def label(kind: str | None) -> str:
    return KINDS.get(kind or "", "свой адрес")


def config_mtime(p: dict) -> float:
    """Когда последний раз менялись сетевые конфиги: старше запуска логина и гейма — значит, они уже работают с ними."""
    files = [catalog.server_dir(p) / part / "config" / name for part, name in _files(p).values()]
    if not servers.config_family(p) == "l2j2":
        files.append(_ipconfig(p))
    return max((f.stat().st_mtime for f in files if f.is_file()), default=0.0)


def _all(p: dict) -> str:
    return "*" if servers.config_family(p) == "l2j2" else "0.0.0.0"


def _files(p: dict) -> dict:
    """Где лежат ключи сети: (часть, файл) для логина, гейма и внешнего адреса La2offline."""
    if servers.config_family(p) == "l2j2":
        return {"login": ("login", servers.config_file(p, "login")), "game": ("game", servers.config_file(p, "game"))}
    return {"login": ("login", "Server.ini"), "game": ("game", "Server.ini")}


def _value(p: dict, which: str, key: str) -> str | None:
    part, name = _files(p)[which]
    text = (catalog.server_dir(p) / part / "config" / name).read_text(encoding="utf-8-sig", errors="replace")
    for line in text.splitlines():
        k, sep, v = line.partition("=")
        if sep and k.strip() == key and not k.lstrip().startswith("#"):
            return v.strip()
    return None


def _ipconfig(p: dict) -> Path:
    return catalog.server_dir(p) / "game" / "config" / "ipconfig.xml"


def _ipconfig_hosts(p: dict) -> tuple[str | None, dict[str, str]]:
    f = _ipconfig(p)
    if not f.is_file():
        return None, {}
    root = ET.parse(f).getroot()
    return root.get("address"), {d.get("subnet"): d.get("address") for d in root.iter("define")}


def state(p: dict) -> dict:
    """Режим по конфигам: local, friends (адрес для друзей и его способ) или other (настроено руками иначе)."""
    login, game = _value(p, "login", "LoginserverHostname"), _value(p, "game", "GameserverHostname")
    if servers.config_family(p) == "l2j2":
        external = _value(p, "game", "ExternalHostname")
        addr = external if external and external not in (LOCAL, "*") else None
    else:
        default, _ = _ipconfig_hosts(p)
        addr = default if default and default != LOCAL else None
    if login == LOCAL and game == LOCAL:
        mode = "local"
    elif login == _all(p) and game == _all(p) and addr:
        mode = "friends"
    else:
        mode = "other"
    cands = candidates()
    kind = None
    if mode == "local":
        kind = "local"
    elif addr:
        kind = next((c["kind"] for c in cands if c["address"] == addr), None) or _range_kind(addr)
    if kind == "internet":
        # Внешний адрес — на роутере, не на адаптере: сверяется с последним ответом роутера по UPnP, если он был.
        seen = (upnp.cached() or {}).get("external")
        ok = not seen or seen == addr
    else:
        ok = mode != "friends" or any(c["address"] == addr for c in cands)
    return {"mode": mode, "kind": kind, "label": label(kind), "address": addr, "addressOk": ok,
            "candidates": cands, "radmin": [c["address"] for c in cands if c["kind"] == "radmin"],
            "listen": {"login": login, "game": game}, "allNets": _all(p)}


def _l2j_internal(address: str) -> bool:
    ip = ipaddress.ip_address(address)
    return any(ip in n for n in L2J_INTERNAL)


def apply(p: dict, mode: str, address: str | None = None, kind: str | None = None) -> dict:
    """Переключить режим: local или friends (адрес этого ПК в сети способа; без адреса — первый найденный адрес
    способа kind; у internet — внешний адрес роутера: свой или по UPnP). Старое имя режима radmin — то же, что
    friends + radmin. Бэкап — как у настроек. Порты на роутере здесь не трогаются — это internet_ports."""
    if mode == "radmin":
        mode, kind = "friends", "radmin"
    net = lan = None
    if mode == "friends" and kind == "internet":
        # Внешний адрес роутера: свой (введён или узнан) или от роутера по UPnP. Изнутри сети — адрес в локальной сети.
        address = (address or "").strip() or (internet_probe().get("external") or "")
        if _range_kind(address) != "internet":
            raise RuntimeError(f"«{address or 'пусто'}» — не внешний адрес: нужен белый IPv4 роутера (не 10.x, 172.16–31.x, 192.168.x и не 100.64–127.x).")
        lan = _internet_lan()
        if not lan:
            raise RuntimeError("У этого ПК нет адреса в локальной сети: «Интернет» идёт через роутер, ПК должен быть подключён к нему.")
    elif mode == "friends":
        cands = candidates()
        if address is None:
            c = next((c for c in cands if c["kind"] == kind), None)
            if not c:
                raise RuntimeError(f"Не нашёл адрес «{label(kind)}» на этом ПК: программа запущена и подключена к своей сети?")
        else:
            c = next((c for c in cands if c["address"] == address), None)
            if not c:
                raise RuntimeError(f"У этого ПК нет адреса {address}: сеть выключена или адрес сменился.")
        address, kind, net = c["address"], c["kind"], c["network"]
    elif mode == "local":
        address, kind = None, "local"
    else:
        raise ValueError(f"Неизвестный режим сети: {mode}")
    listen = _all(p) if mode == "friends" else LOCAL
    (lp, lf), (gp, gf) = _files(p)["login"], _files(p)["game"]
    edits = [{"part": lp, "file": lf, "key": "LoginserverHostname", "value": listen},
             {"part": gp, "file": gf, "key": "GameserverHostname", "value": listen}]
    if servers.config_family(p) == "l2j2":
        inside = lan[0] if lan else address if address and _l2j_internal(address) else "*"
        edits.append({"part": gp, "file": gf, "key": "ExternalHostname", "value": address or LOCAL})
        edits.append({"part": gp, "file": gf, "key": "InternalHostname", "value": inside})
    backup_root = paths.BACKUPS / "config" / p["id"]
    result = configs.save_edits(catalog.server_dir(p), backup_root, edits)
    if not servers.config_family(p) == "l2j2":
        _write_ipconfig(p, address, net, kind, Path(result["backup"]), lan)
    return {"mode": mode, "kind": kind, "label": label(kind), "address": address, "backup": result["backup"]}


def _write_ipconfig(p: dict, address: str | None, net: str | None, kind: str | None, backup_dir: Path,
                    lan: tuple[str, str] | None = None) -> None:
    f = _ipconfig(p)
    if f.is_file():
        target = backup_dir / f.relative_to(catalog.server_dir(p))
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, target)
    if address and lan:
        lines = [*IPCONFIG_HEAD,
                 f"<!-- L2Hub, режим друзей ({label(kind)}): с этого ПК клиенту отдаётся {LOCAL}, из локальной сети {lan[1]} — {lan[0]}, "
                 f"остальным — внешний адрес роутера {address}. -->",
                 IPCONFIG_ROOT.format(address),
                 f'\t<define subnet="127.0.0.0/8" address="{LOCAL}" />',
                 f'\t<define subnet="{lan[1]}" address="{lan[0]}" />',
                 "</gameserver>"]
    elif address:
        lines = [*IPCONFIG_HEAD,
                 f"<!-- L2Hub, режим друзей ({label(kind)}): с этого ПК клиенту отдаётся {LOCAL}, из сети {net} и остальным — {address}. -->",
                 IPCONFIG_ROOT.format(address),
                 f'\t<define subnet="127.0.0.0/8" address="{LOCAL}" />',
                 f'\t<define subnet="{net}" address="{address}" />',
                 "</gameserver>"]
    else:
        lines = [*IPCONFIG_HEAD,
                 f"<!-- L2Hub, локальный режим: клиентам отдаётся только {LOCAL}. -->",
                 IPCONFIG_ROOT.format(LOCAL),
                 f'\t<define subnet="127.0.0.0/8" address="{LOCAL}" />',
                 "</gameserver>"]
    f.write_bytes(("\r\n".join(lines) + "\r\n").encode("utf-8"))


def client_system_name(p: dict) -> str:
    """Как называется папка системы у друга: та, где L2.ini клиента ищет классы (Paths=../<папка>/*.u).
    У H5 автора клиент запускается из копии system_ru, а классы берёт из ../System — у друга это одна папка system."""
    system = catalog.client_exe(p).parent
    if not (system / "L2.ini").is_file():
        return system.name  # клиент не указан (свежая установка) — раздел «Друзья» не должен из-за этого падать
    name = l2ini.packages_dir(system / "L2.ini") or system.name
    return next((d.name for d in system.parent.iterdir() if d.is_dir() and d.name.lower() == name.lower()), name)


def friend_l2ini(p: dict, address: str | None = None) -> Path:
    """L2.ini для друзей: L2.ini клиента профиля с ServerAddr = адрес этого ПК в сети способа. Кладётся в data/friends/<id>/."""
    st = state(p)
    address = address or (st["address"] if st["mode"] == "friends" else None)
    if not address:
        raise RuntimeError("Сначала выбери в «Друзьях», как друзья подключаются: в L2.ini пишется адрес этого ПК в той сети.")
    src = catalog.client_exe(p).parent / "L2.ini"
    out = FRIENDS / p["id"]
    out.mkdir(parents=True, exist_ok=True)
    if clientsys.is_4game(p):
        # Клиент 4game: L2.ini на ключе NCSoft не перешифровать — другу открытый L2Hub.ini с адресом хоста.
        target = out / clientsys.INI
        target.write_bytes(clientsys.plain_ini(src.read_bytes(), address))
        (out / "README.txt").write_text(
            f"{clientsys.INI} для друзей — {p['id']}, адрес сервера {address} ({label(_range_kind(address) if st['address'] != address else st['kind'])}).\r\n"
            f"Положить в папку системы клиента: {client_system_name(p)}\\{clientsys.INI} (штатный L2.ini не трогать).\r\n"
            f"Запуск — {client_system_name(p)}\\L2.exe -INI=.\\{clientsys.INI} (Play.bat из пакета L2Hub делает так же).\r\n", encoding="utf-8-sig", newline="")
        return target
    target = out / "L2.ini"
    target.write_bytes(l2ini.with_server_addr(src.read_bytes(), address))
    (out / "README.txt").write_text(
        f"L2.ini для друзей — {p['id']}, адрес сервера {address} ({label(_range_kind(address) if st['address'] != address else st['kind'])}).\r\n"
        f"Положить с заменой в папку системы клиента: {client_system_name(p)}\\L2.ini.\r\n"
        f"Перед заменой сохрани свой L2.ini рядом — так проще вернуть.\r\n", encoding="utf-8-sig", newline="")
    return target
