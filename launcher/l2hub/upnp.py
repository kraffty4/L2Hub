"""UPnP роутера для способа «Интернет» (docs/FRIENDS.md, «Способ „Интернет“»): внешний адрес и проброс портов.

Только стандартная библиотека и только локальная сеть: роутер ищется по SSDP (239.255.255.250:1900) с адресов
этого ПК в локальной сети, описание и SOAP — HTTP к самому роутеру, мимо системного прокси. Сторонних сайтов
«узнай свой IP» нет: внешний адрес говорит роутер (GetExternalIPAddress).

Серый адрес — когда роутер сам за NAT: внешний адрес роутера из 100.64.0.0/10 (CGNAT провайдера) или частный
(роутер за другим роутером). Тогда друзьям снаружи до этого ПК не достучаться, проброс портов не поможет.

Порты пробрасываются на адрес этого ПК в той сети, где роутер (TCP, бессрочно, описание «L2Hub …»). Закрываются
только свои: чужие пробросы (другой ПК, другое описание) не трогаются.
"""

import ipaddress
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from xml.sax.saxutils import escape

SSDP = ("239.255.255.250", 1900)
SEARCH = ("urn:schemas-upnp-org:service:WANIPConnection:1", "urn:schemas-upnp-org:service:WANIPConnection:2",
          "urn:schemas-upnp-org:service:WANPPPConnection:1", "urn:schemas-upnp-org:device:InternetGatewayDevice:1")
SERVICES = ("WANIPConnection", "WANPPPConnection")
CGNAT = ipaddress.ip_network("100.64.0.0/10")
DESC = "L2Hub"
NO_ENTRY = 714  # NoSuchEntryInArray: такого проброса нет
CONFLICT = 718  # ConflictInMappingEntry: порт уже проброшен на другое устройство
_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # роутер — мимо прокси и VPN-прокси системы
_cache: dict = {}


class UpnpError(RuntimeError):
    def __init__(self, code: int, text: str):
        super().__init__(f"UPnP {code}: {text}")
        self.code = code


@dataclass
class Gateway:
    router: str  # адрес роутера в локальной сети
    local_ip: str  # адрес этого ПК, с которого роутер виден
    control: str  # controlURL службы WANIPConnection или WANPPPConnection
    service: str  # её serviceType


def _private(host: str) -> bool:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return ip.is_private and not ip.is_loopback


def _locations(sources: list[str], timeout: float) -> list[tuple[str, str]]:
    """SSDP M-SEARCH с каждого адреса этого ПК в локальной сети: [(LOCATION, адрес отправителя)] без повторов.
    Отправитель и хост LOCATION — только частные адреса: описание качается с роутера, а не откуда скажут."""
    found: dict[str, str] = {}
    for src in sources:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        try:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(src))
            s.bind((src, 0))
            for st in SEARCH:
                s.sendto((f"M-SEARCH * HTTP/1.1\r\nHOST: {SSDP[0]}:{SSDP[1]}\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {st}\r\n\r\n").encode(), SSDP)
            end = time.monotonic() + timeout
            while (left := end - time.monotonic()) > 0:
                s.settimeout(left)
                try:
                    data, (sender, _) = s.recvfrom(4096)
                except (socket.timeout, OSError):
                    break
                for line in data.decode("latin-1").split("\r\n"):
                    k, sep, v = line.partition(":")
                    if sep and k.strip().lower() == "location":
                        loc = v.strip()
                        host = urllib.parse.urlsplit(loc).hostname or ""
                        if loc.startswith("http://") and _private(sender) and _private(host):
                            found.setdefault(loc, sender)
        except OSError:
            continue
        finally:
            s.close()
    return list(found.items())


def _services(location: str) -> list[tuple[str, str]]:
    """Из описания устройства: [(serviceType, полный controlURL)] служб WANIPConnection и WANPPPConnection."""
    xml = _HTTP.open(location, timeout=4).read()
    root = ET.fromstring(xml)
    base = next((e.text.strip() for e in root.iter() if e.tag.endswith("URLBase") and e.text), location)
    out = []
    for svc in root.iter():
        if not svc.tag.endswith("}service"):
            continue
        kids = {c.tag.rsplit("}", 1)[-1]: (c.text or "").strip() for c in svc}
        if any(name in kids.get("serviceType", "") for name in SERVICES) and kids.get("controlURL"):
            out.append((kids["serviceType"], urllib.parse.urljoin(base, kids["controlURL"])))
    return out


def _local_ip(router: str) -> str:
    """Адрес этого ПК, через который идёт путь к роутеру (UDP connect пакетов не шлёт)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((router, SSDP[1]))
        return s.getsockname()[0]
    finally:
        s.close()


def soap(gw: Gateway, action: str, args: dict | None = None) -> dict:
    """Вызов SOAP службы роутера: ответ — {имя поля: значение}; ошибка UPnP — UpnpError с её кодом."""
    body = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="{gw.service}">'
            + "".join(f"<{k}>{escape(str(v))}</{k}>" for k, v in (args or {}).items())
            + f"</u:{action}></s:Body></s:Envelope>")
    req = urllib.request.Request(gw.control, data=body.encode("utf-8"), method="POST",
                                 headers={"Content-Type": 'text/xml; charset="utf-8"', "SOAPAction": f'"{gw.service}#{action}"'})
    try:
        xml = _HTTP.open(req, timeout=5).read()
    except urllib.error.HTTPError as e:
        root = ET.fromstring(e.read() or b"<x/>")
        code = next((int(x.text) for x in root.iter() if x.tag.endswith("errorCode") and (x.text or "").strip().isdigit()), e.code)
        text = next((x.text for x in root.iter() if x.tag.endswith("errorDescription") and x.text), e.reason)
        raise UpnpError(code, str(text)) from None
    resp = next((e for e in ET.fromstring(xml).iter() if e.tag.endswith(f"{action}Response")), None)
    return {c.tag.rsplit("}", 1)[-1]: (c.text or "").strip() for c in (resp if resp is not None else [])}


def discover(sources: list[str], timeout: float = 2.0) -> tuple[Gateway | None, str]:
    """Роутер с UPnP IGD (вместе с внешним адресом) или None и почему не нашёлся."""
    if not sources:
        return None, "у этого ПК нет адреса в локальной сети"
    locs = _locations(sources, timeout)
    if not locs:
        return None, "роутер не ответил по UPnP — включи UPnP в настройках роутера или пробрось порты вручную"
    why = "у роутера нет службы WANIPConnection — UPnP для проброса портов выключен или не поддерживается"
    for loc, sender in locs:
        try:
            services = _services(loc)
        except (OSError, ET.ParseError, ValueError):
            continue
        for service, control in services:
            gw = Gateway(sender, _local_ip(sender), control, service)
            try:
                soap(gw, "GetExternalIPAddress")
            except (OSError, UpnpError, ET.ParseError) as e:
                why = f"роутер не отдал внешний адрес: {e}"
                continue
            return gw, ""
    return None, why


def external(gw: Gateway) -> str:
    return soap(gw, "GetExternalIPAddress").get("NewExternalIPAddress", "")


def grey(address: str) -> str:
    """Почему внешний адрес роутера не годится для входа снаружи, или пусто — адрес белый."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return "роутер не знает внешний адрес — нет подключения к интернету?"
    if ip in CGNAT:
        return "серый адрес (CGNAT провайдера): снаружи до роутера не достучаться — нужен белый адрес у провайдера"
    if ip.is_private:
        return "роутер стоит за другим роутером (двойной NAT): пробрось порты и на нём или включи там режим моста"
    if not ip.is_global:
        return "роутер отдал служебный адрес — внешний адрес неизвестен"
    return ""


def mapping(gw: Gateway, port: int) -> dict | None:
    """Проброс внешнего TCP-порта или None, если его нет."""
    try:
        r = soap(gw, "GetSpecificPortMappingEntry", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": "TCP"})
    except UpnpError as e:
        if e.code == NO_ENTRY:
            return None
        raise
    return {"client": r.get("NewInternalClient", ""), "port": int(r.get("NewInternalPort") or 0),
            "enabled": r.get("NewEnabled", "1") in ("1", "true"), "desc": r.get("NewPortMappingDescription", "")}


def open_port(gw: Gateway, port: int, what: str) -> None:
    """Проброс TCP-порта на этот ПК. Порт уже проброшен на другое устройство — ошибка с его адресом."""
    m = mapping(gw, port)
    if m and m["client"] == gw.local_ip and m["port"] == port and m["enabled"]:
        return
    if m and m["client"] != gw.local_ip:
        raise RuntimeError(f"порт {port} на роутере уже проброшен на {m['client']} ({m['desc'] or 'без описания'}) — освободи его в роутере")
    try:
        soap(gw, "AddPortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": "TCP", "NewInternalPort": port,
                                    "NewInternalClient": gw.local_ip, "NewEnabled": 1, "NewPortMappingDescription": f"{DESC} {what}",
                                    "NewLeaseDuration": 0})
    except UpnpError as e:
        if e.code == CONFLICT:
            raise RuntimeError(f"порт {port} на роутере занят другим устройством — освободи его в роутере") from None
        raise


def close_port(gw: Gateway, port: int) -> bool:
    """Снять свой проброс (на этот ПК и с описанием L2Hub). Чужой не трогается. True — снят."""
    m = mapping(gw, port)
    if not m or m["client"] != gw.local_ip or not m["desc"].startswith(DESC):
        return False
    soap(gw, "DeletePortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": "TCP"})
    return True


def probe(sources: list[str], ports: dict[str, int], force: bool = False, ttl: float = 60.0) -> dict:
    """Что видно с роутера: {found, why, router, localIp, external, grey, ports: {порт: {"what", "open", "mine", "client"}}, at}.
    Кэш на ttl секунд: SSDP ждёт ответов ~2 с, а окно «Друзей» перерисовывается часто."""
    if not force and _cache.get("at") and time.time() - _cache["at"] < ttl and _cache.get("sources") == sources:
        return _cache["result"]
    gw, why = discover(sources)
    out = {"found": bool(gw), "why": why, "router": None, "localIp": None, "external": None, "grey": "", "ports": {}, "at": time.time()}
    if gw:
        out.update(router=gw.router, localIp=gw.local_ip)
        try:
            out["external"] = external(gw)
            out["grey"] = grey(out["external"])
            for what, port in ports.items():
                m = mapping(gw, port)
                out["ports"][str(port)] = {"what": what, "open": bool(m and m["enabled"]), "mine": bool(m and m["client"] == gw.local_ip),
                                           "client": m["client"] if m else None}
        except (OSError, UpnpError, ET.ParseError) as e:
            out["why"] = f"роутер ответил с ошибкой: {e}"
    _cache.update(at=out["at"], sources=sources, result=out, gateway=gw)
    return out


def cached() -> dict | None:
    """Последний probe без похода в сеть (или None)."""
    return _cache.get("result")


def gateway(sources: list[str]) -> Gateway:
    """Роутер для проброса: из кэша probe или заново. Нет — RuntimeError с причиной."""
    gw = _cache.get("gateway") if _cache.get("sources") == sources else None
    if gw is None:
        gw, why = discover(sources)
        if gw is None:
            raise RuntimeError(why)
    return gw
