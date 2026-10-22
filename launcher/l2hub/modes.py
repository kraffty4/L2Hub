"""Режимы сервера («Хардкор», «PvP-крафт», «PvP») и выключатели модов (баффер, GM-шоп, чемпионы, ивенты) — задачи
автора 2026-10-21 (docs/PLAN.md, «Настройки сервера в окне»). Таблица — server_modes.json; пишет configs.save_edits
с бэкапом конфигов, как экран «Все настройки». Только Mobius: у сборок-комбайнов и L2Solo свои конфиги."""

import json
import re

from . import catalog, configs, l2solo, paths, servers

ADENA = 57


def table() -> dict:
    return json.loads((paths.PACKAGE / "server_modes.json").read_text(encoding="utf-8"))


def supported(p: dict) -> str | None:
    """Почему режимов и выключателей у хроники нет; None — есть."""
    if l2solo.is_l2solo(p):
        return "У C4 (L2Solo) рейты — в пульте «Мой мир», свои выключатели — там же."
    if servers.config_family(p) != "mobius":
        return "У своей сборки настройки — только в «Все настройки»: режимы знают ключи Mobius."
    return None


def _read(p: dict, file: str, key: str, part: str = "game") -> str | None:
    try:
        path = configs._locate(catalog.server_dir(p), part, file)
    except (FileNotFoundError, ValueError):
        return None
    m = re.search(rf"^\s*{re.escape(key)}\s*=[ \t]*(.*?)\s*$", path.read_text(encoding="utf-8-sig", errors="replace"), re.MULTILINE)
    return m[1] if m else None


def _adena(value: str | None, mult) -> str:
    """DropAmountMultiplierByItemId: заменить множитель адены (57), остальные предметы не трогать."""
    items = [x for x in (value or "").split(";") if x.strip() and not x.strip().startswith(f"{ADENA},")]
    return ";".join([f"{ADENA},{mult}", *items])


def _adena_now(value: str | None) -> str:
    m = re.search(rf"(?:^|;)\s*{ADENA},([\d.]+)", value or "")
    return m[1] if m else "1"


def _num(v: str | None) -> float | None:
    try:
        return float(v.rstrip(".")) if v else None
    except ValueError:
        return None


def _present(p: dict, s: dict) -> dict[str, str]:
    """Ключи выключателя, которые есть в сборке, и их значения. Выключатель бывает на несколько ключей разом
    (ShowNpcLevel, ShowNpcAggression, AltGameViewNpc) и в конфигах логина ("part": "login")."""
    out = {}
    for key in s.get("keys") or [s["key"]]:
        v = _read(p, s["file"], key, s.get("part", "game"))
        if v is not None:
            out[key] = v
    return out


def _edits(s: dict, keys, value: bool) -> list[dict]:
    return [{"part": s.get("part", "game"), "file": s["file"], "key": k, "value": "True" if value else "False"} for k in keys]


def switches(p: dict) -> list[dict]:
    out = []
    for s in table()["switches"]:
        vals = _present(p, s)
        out.append({**s, "keys": s.get("keys") or [s["key"]], "part": s.get("part", "game"), "available": bool(vals),
                    "value": bool(vals) and all(v.strip().lower() == "true" for v in vals.values())})
    return out


def rates_now(p: dict) -> dict:
    """Текущие рейты по группам (xp, sp, adena, drop, spoil): по первому ключу группы."""
    t = table()["rates"]
    out = {}
    for group, keys in t.items():
        if group == "file":
            continue
        first = keys[0]
        if first == "@adena":
            out[group] = _num(_adena_now(_read(p, t["file"], "DropAmountMultiplierByItemId")))
        else:
            out[group] = _num(_read(p, t["file"], first))
    return out


def state(p: dict) -> dict:
    """Режимы с отметкой активного (все его рейты и выключатели совпадают) и выключатели модов."""
    if why := supported(p):
        return {"ok": False, "error": why}
    sw = {s["id"]: s for s in switches(p)}
    now = rates_now(p)
    modes = []
    for m in table()["modes"]:
        same_rates = all(now.get(g) == float(v) for g, v in m["rates"].items() if now.get(g) is not None)
        same_sw = all(sw[k]["value"] == v for k, v in m["switches"].items() if sw.get(k, {}).get("available"))
        same_set = all((cur := _read(p, f, k)) is None or cur.strip().lower() == str(v).lower()
                       for f, keys in m.get("settings", {}).items() for k, v in keys.items())
        modes.append({"id": m["id"], "title": m["title"], "note": m["note"], "active": same_rates and same_sw and same_set})
    return {"ok": True, "modes": modes, "switches": list(sw.values()), "rates": now,
            "restart": servers.part_process(p, "game") is not None}


def _save(p: dict, edits: list[dict]) -> dict:
    return configs.save_edits(catalog.server_dir(p), paths.BACKUPS / "config" / p["id"], edits)


def set_switch(p: dict, switch_id: str, value: bool) -> dict:
    s = next((x for x in table()["switches"] if x["id"] == switch_id), None)
    keys = _present(p, s) if s else {}
    if not keys:
        raise ValueError(f"В этой сборке нет выключателя «{switch_id}»")
    _save(p, _edits(s, keys, value))
    return {"id": switch_id, "value": bool(value)}


def apply_mode(p: dict, mode_id: str) -> dict:
    """Режим целиком: рейты групп и выключатели. Ключей, которых в сборке нет, — пропуск со списком."""
    if why := supported(p):
        raise ValueError(why)
    t = table()
    mode = next((m for m in t["modes"] if m["id"] == mode_id), None)
    if not mode:
        raise ValueError(f"Нет режима {mode_id}")
    rf = t["rates"]["file"]
    edits, skipped = [], []
    for group, mult in mode["rates"].items():
        for key in t["rates"][group]:
            if key == "@adena":
                cur = _read(p, rf, "DropAmountMultiplierByItemId")
                if cur is None:
                    skipped.append("DropAmountMultiplierByItemId")
                else:
                    edits.append({"part": "game", "file": rf, "key": "DropAmountMultiplierByItemId", "value": _adena(cur, mult)})
            elif _read(p, rf, key) is None:
                skipped.append(key)
            else:
                edits.append({"part": "game", "file": rf, "key": key, "value": str(mult)})
    for sid, value in mode["switches"].items():
        s = next(x for x in t["switches"] if x["id"] == sid)
        if keys := _present(p, s):
            edits.extend(_edits(s, keys, value))
        else:
            skipped.append(f"{s['file']}: {', '.join(s.get('keys') or [s['key']])}")
    # Прочие ключи режима (удобства: автоподбор, автоизучение скиллов, бесплатные маунты) — {файл: {ключ: значение}}.
    for file, keys in mode.get("settings", {}).items():
        for key, value in keys.items():
            if _read(p, file, key) is None:
                skipped.append(f"{file}: {key}")
            else:
                edits.append({"part": "game", "file": file, "key": key, "value": str(value)})
    _save(p, edits)
    return {"mode": mode_id, "title": mode["title"], "changed": len(edits), "skipped": skipped}


# ---------- Календарь событий (L2HubEvents.ini, патч 0016 каждой хроники) ----------

CAL_FILE = "L2HubEvents.ini"
# Ключи расписаний рядом с окнами событий (не окна): PvP-ивенты (0019) и нашествие эльпи (Interlude, 0021). Выключатели
# PvpEvents и Elpies — в «Моды и ивенты» (server_modes.json); в карточке календаря — остальные.
PVP_KEYS = {"PvpEvents": "bool", "PvpOnlyWithPlayers": "bool", "PvpMinLevel": "int", "TvT": "cron", "Deathmatch": "cron", "CtF": "cron",
            "Elpies": "bool", "ElpiesSchedule": "cron", "ElpiesMinutes": "int", "ElpiesOnlyWithPlayers": "bool"}
WINDOW = re.compile(r"^(?:(\d{2}) (\d{2})(?: \d{4})?-(\d{2}) (\d{2})(?: \d{4})?|Off)?$", re.IGNORECASE)
CRON = re.compile(r"^(?:\S+(?:\s+\S+){4})?$")


def calendar(p: dict) -> dict:
    """Окна сезонных событий и расписание PvP-ивентов из L2HubEvents.ini: ключ, значение и комментарий над ним."""
    if why := supported(p):
        return {"ok": False, "error": why}
    try:
        path = configs._locate(catalog.server_dir(p), "game", CAL_FILE)
    except (FileNotFoundError, ValueError):
        return {"ok": False, "error": "В этой сборке календаря событий L2Hub нет"}
    events, pvp, note = [], {}, []
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        s = line.strip()
        if s.startswith("#"):
            t = s.lstrip("#").strip()
            if t and not set(t) <= {"-"} and not t.startswith("Default:"):
                note.append(t)
            continue
        m = re.match(r"^([A-Za-z]\w*)\s*=\s*(.*?)\s*$", s)
        if m and m[1] in PVP_KEYS:
            pvp[m[1]] = {"value": m[2], "type": PVP_KEYS[m[1]], "note": " ".join(note)}
        elif m and m[1] != "Enabled":
            events.append({"key": m[1], "value": m[2], "note": " ".join(note)})
        note = []
    return {"ok": True, "events": events, "pvp": pvp, "restart": servers.part_process(p, "game") is not None}


def _window_ok(v: str) -> bool:
    m = WINDOW.match(v)
    if not m:
        return False
    return not m[1] or all(1 <= int(d) <= 31 and 1 <= int(mo) <= 12 for d, mo in ((m[1], m[2]), (m[3], m[4])))


def set_calendar(p: dict, values: dict) -> dict:
    """Записать окна событий и расписание PvP: {ключ: значение}; формат проверяется до записи, бэкап — как у всех правок."""
    cal = calendar(p)
    if not cal["ok"]:
        raise ValueError(cal["error"])
    known = {e["key"] for e in cal["events"]}
    edits = []
    for key, value in values.items():
        v = str(value).strip()
        if key in known:
            if not _window_ok(v):
                raise ValueError(f"{key}: «{v}» — нужно «дд ММ-дд ММ», «дд ММ гггг-дд ММ гггг», Off или пусто")
            v = "Off" if v.lower() == "off" else v
        elif key in cal["pvp"]:
            kind = PVP_KEYS[key]
            if kind == "bool" and v not in ("True", "False") or kind == "int" and not v.isdigit() or kind == "cron" and not CRON.match(v):
                raise ValueError(f"{key}: «{v}» — " + {"bool": "True или False", "int": "число", "cron": "пять полей cron (минуты часы день месяц день_недели) или пусто"}[kind])
        else:
            raise ValueError(f"В календаре нет ключа {key}")
        edits.append({"part": "game", "file": CAL_FILE, "key": key, "value": v})
    if edits:
        _save(p, edits)
    return {"changed": len(edits)}
