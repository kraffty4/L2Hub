"""«Общество» в «Мой мир» — страница сервера, как у старых серверов: топы, кланы, войны, замки, герои, вражда и дружба.

Только чтение. Пока работает база, данные из неё; снимок последнего чтения лежит в data/society/<id>.json, чтобы
у выключенного мира страница не пустела. Без базы и без снимка — то, что сервер оставил в L2HubHunters.json.
Секретных фантомов (kind не hunter и не companion) здесь нет нигде: ни в топах, ни в кланах, ни в связях.
"""
from __future__ import annotations

import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from . import catalog, paths, servers

TIES_MAX = 15        # строк в списках вражды и дружбы
STATUS_FRESH = 60    # с: старше — сервер L2HubHunters.json не пишет, из него не видно, кто в сети
WAR_ACTIVE = {"0", "1", "2"}  # ClanWarState у Classic и Essence: DECLARATION, BLOOD_DECLARATION, MUTUAL; у остальных в таблице только идущие войны


def _rows(p: dict, *queries: str) -> list[list[str]] | None:
    """Первый запрос, который прошёл: столбцы у хроник разные. Ни один не прошёл (базы нет, старая схема) — None."""
    for sql in queries:
        try:
            return servers.db_query(p, sql)
        except (RuntimeError, OSError, subprocess.TimeoutExpired):
            continue
    return None


def _int(value: str | None) -> int:
    try:
        return int(value or 0)
    except ValueError:
        return 0


def _phantom_kinds(game: Path) -> dict[str, str]:
    """Имя фантома → kind из L2HubPhantoms.xml."""
    data = game / "data" / "L2HubPhantoms.xml"
    if not data.is_file():
        return {}
    return {el.get("name"): el.get("kind", "companion") for el in ET.parse(data).getroot().iter("phantom")}


def _status(game: Path) -> dict:
    """L2HubHunters.json любой давности: связи и уровни переживают выключение сервера."""
    try:
        return json.loads((game / "log" / "L2HubHunters.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _kind(name: str, account: str, phantom_account: str, kinds: dict[str, str]) -> str:
    """hunter, companion или player; секретные отсеяны раньше."""
    return kinds.get(name) or ("companion" if account == phantom_account else "player")


def _live_phantoms(game: Path, status: dict) -> set[str]:
    """Фантомы в сети: в базе у них online не ставится, знает только свежий файл сервера."""
    try:
        if time.time() - (game / "log" / "L2HubHunters.json").stat().st_mtime > STATUS_FRESH:
            return set()
    except OSError:
        return set()
    return {h["name"] for h in status.get("hunters") or [] if h.get("name") and h.get("online")} | {c["name"] for c in status.get("companions") or [] if c.get("name")}


def _twinks(p: dict) -> set[str]:
    """Гномы-твинки охотников (docs/PHANTOMS.md, 4.91): персонажи аккаунта фантомов, но не напарники и в люди не идут."""
    rows = _rows(p, "SELECT value FROM global_variables WHERE var = 'L2HubTwinks'") or []
    return {pair.split(":")[-1] for row in rows for pair in row[0].split(";") if ":" in pair}


def _from_db(p: dict, classes: dict[int, str], kinds: dict[str, str], live: set[str]) -> dict | None:
    chars = _rows(p, "SELECT charId, char_name, level, classid, pvpkills, pkkills, clanid, online, account_name, nobless FROM characters")
    if chars is None:
        return None
    account = servers.phantom_account(p)
    secret = {n for n, k in kinds.items() if k not in ("hunter", "companion")} | _twinks(p)
    people, by_id, members = [], {}, {}
    for char_id, name, level, cls, pvp, pk, clan_id, online, acc, noble in chars:
        if name in secret:
            continue
        row = {"name": name, "level": _int(level), "cls": classes.get(_int(cls), ""), "pvp": _int(pvp), "pk": _int(pk), "clan": "", "online": online == "1" or name in live,
               "noble": noble == "1", "kind": _kind(name, acc, account, kinds)}
        people.append(row)
        by_id[char_id] = row
        if clan_id not in ("0", "NULL", ""):
            members.setdefault(clan_id, []).append(row)
    clans, names = [], {}
    for clan_id, name, level, rep, castle, ally, leader in _rows(p, "SELECT clan_id, clan_name, clan_level, reputation_score, hasCastle, ally_name, leader_id FROM clan_data") or []:
        team = members.get(clan_id, [])
        if not team:  # клан из одних секретных или распавшийся: на странице ему нечего делать
            continue
        for m in team:
            m["clan"] = name
        names[clan_id] = name
        boss = by_id.get(leader)
        clans.append({"name": name, "level": _int(level), "rep": _int(rep), "members": len(team), "online": sum(m["online"] for m in team), "leader": boss["name"] if boss else "",
                      "ally": "" if ally in ("NULL", "") else ally, "castleId": _int(castle), "castle": "", "wars": []})
    wars = []
    for a, b, kills_a, kills_b, state in _rows(p, "SELECT clan1, clan2, clan1Kill, clan2Kill, state FROM clan_wars", "SELECT clan1, clan2, NULL, NULL, NULL FROM clan_wars") or []:
        if a in names and b in names and (state == "NULL" or state in WAR_ACTIVE):
            wars.append({"a": names[a], "b": names[b], "killsA": None if kills_a == "NULL" else _int(kills_a), "killsB": None if kills_b == "NULL" else _int(kills_b)})
    castle_owner = {c["castleId"]: c for c in clans if c["castleId"]}
    castles = []
    for cid, name, siege, tax in _rows(p, "SELECT id, name, siegeDate, taxPercent FROM castle", "SELECT id, name, siegeDate, NULL FROM castle") or []:
        owner = castle_owner.get(_int(cid))
        if owner:
            owner["castle"] = name
        castles.append({"name": name, "owner": owner["name"] if owner else "", "siege": _int(siege), "tax": None if tax == "NULL" else _int(tax)})
    heroes = []
    for char_id, cls, count, played in _rows(p, "SELECT charId, class_id, count, played FROM heroes") or []:
        row = by_id.get(char_id)
        if row:
            heroes.append({"name": row["name"], "cls": classes.get(_int(cls), ""), "count": _int(count), "now": played == "1", "kind": row["kind"], "clan": row["clan"]})
    for c in clans:
        del c["castleId"]
        c["wars"] = [w["b"] if w["a"] == c["name"] else w["a"] for w in wars if c["name"] in (w["a"], w["b"])]
    return {"people": people, "clans": clans, "wars": wars, "castles": castles, "heroes": heroes}


def _from_status(status: dict, classes: dict[int, str], kinds: dict[str, str]) -> dict | None:
    """Без базы: уровни и кланы из того, что сервер оставил в файле; PvP, замков и войн тут нет."""
    secret = {n for n, k in kinds.items() if k not in ("hunter", "companion")}
    people, clans = [], {}
    for h in status.get("hunters") or []:
        name = h.get("name")
        if not name or h.get("secret") or name in secret or h.get("level") is None:
            continue
        people.append({"name": name, "level": h["level"], "cls": classes.get(h.get("classId"), ""), "pvp": None, "pk": None, "clan": h.get("clan") or "", "online": False, "noble": False, "kind": "hunter"})
        if h.get("clan"):
            c = clans.setdefault(h["clan"], {"name": h["clan"], "level": h.get("clanLevel") or 0, "rep": None, "members": 0, "online": 0, "leader": "", "ally": "", "castle": "", "wars": []})
            c["members"] += 1
            if h.get("clanLeader"):
                c["leader"] = name
    if not people:
        return None
    return {"people": people, "clans": list(clans.values()), "wars": [], "castles": [], "heroes": []}


def _ties(status: dict, kinds: dict[str, str]) -> dict:
    """Самые сильные вражда и дружба из отношений охотников. Пара считается один раз, сила — сумма с обеих сторон."""
    secret = {n for n, k in kinds.items() if k not in ("hunter", "companion")}
    hunters = {h["name"] for h in status.get("hunters") or [] if h.get("name") and not h.get("secret")}
    pairs: dict[tuple[str, str, str], dict] = {}
    for h in status.get("hunters") or []:
        name = h.get("name")
        if not name or h.get("secret") or name in secret:
            continue
        for r in h.get("relations") or []:
            other, kind = r.get("name"), r.get("kind")
            if not other or other in secret or kind not in ("enemy", "friend"):
                continue
            key = (*sorted((name, other)), kind)
            row = pairs.setdefault(key, {"a": key[0], "b": key[1], "score": 0.0, "kills": 0, "reasons": [], "living": ""})
            row["score"] += float(r.get("grudge" if kind == "enemy" else "gratitude") or 0)
            row["kills"] += int(r.get("kills") or 0)
            row["reasons"] += [x for x in r.get("reasons") or [] if x not in row["reasons"]]
            if other not in hunters and other not in kinds:
                row["living"] = other
    def best(kind: str) -> list[dict]:
        rows = [row for key, row in pairs.items() if key[2] == kind]
        rows.sort(key=lambda x: (bool(x["living"]), x["score"]), reverse=True)  # отношения с живым — всегда сверху
        for row in rows:
            row["score"] = round(row["score"], 1)
        return rows[:TIES_MAX]
    return {"enemies": best("enemy"), "friends": best("friend")}


def snapshot(p: dict, classes: dict[int, str]) -> dict:
    """Страница целиком. live — данные из базы прямо сейчас; иначе time — когда снят снимок."""
    game = catalog.server_dir(p) / "game"
    kinds = _phantom_kinds(game)
    status = _status(game)
    saved = paths.HOME / "data" / "society" / f"{p['id']}.json"
    data = _from_db(p, classes, kinds, _live_phantoms(game, status)) if servers.db_running(p["ports"]["db"]) else None
    if data is not None:
        data = {**data, "ties": _ties(status, kinds), "time": int(time.time() * 1000)}
        saved.parent.mkdir(parents=True, exist_ok=True)
        saved.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        return {"ok": True, "live": True, **data}
    try:
        return {"ok": True, "live": False, **json.loads(saved.read_text(encoding="utf-8"))}
    except (OSError, ValueError):
        pass
    data = _from_status(status, classes, kinds)
    if data is None:
        return {"ok": True, "live": False, "empty": True}
    return {"ok": True, "live": False, "partial": True, "ties": _ties(status, kinds), **data}
