"""Режимы сервера («Хардкор», «x10», «PvP-крафт», «PvP», «Живой сервер») и выключатели модов — задачи
автора 2026-10-21 (docs/PLAN.md, «Настройки сервера в окне»). Таблица — server_modes.json; пишет configs.save_edits
с бэкапом конфигов, как страница «Все ключи». Только Mobius: у сборок-комбайнов и L2Solo свои конфиги."""

import json
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from . import catalog, configs, gmshop, l2solo, machine, paths, servers

ADENA = 57


def table() -> dict:
    return json.loads((paths.PACKAGE / "server_modes.json").read_text(encoding="utf-8"))


def _matches(p: dict, record: dict) -> bool:
    chronicle = p.get("chronicle") or p["id"]
    return (not record.get("chronicles") or chronicle in record["chronicles"]) and chronicle not in record.get("excludeChronicles", [])


def pages(p: dict) -> list[dict]:
    """Контролы хроники не показываются в соседних сборках даже при совпавшем имени INI-ключа."""
    return [{**page, "subs": [sub for sub in page.get("subs", []) if _matches(p, sub)]}
            for page in table()["pages"] if _matches(p, page)]


def supported(p: dict) -> str | None:
    """Почему режимов и выключателей у хроники нет; None — есть."""
    if l2solo.is_l2solo(p):
        return "У C4 (L2Solo) рейты — в пульте «Мой мир», свои выключатели — там же."
    if servers.config_family(p) != "mobius":
        return "У своей сборки режимов и выключателей нет: они знают ключи Mobius. Рейты и правила — на этих страницах, всё остальное — в «Все ключи»."
    return None


def _read(p: dict, file: str, key: str, part: str = "game") -> str | None:
    try:
        path = configs._locate(catalog.server_dir(p), part, file)
    except (FileNotFoundError, ValueError):
        return None
    text = path.read_text(encoding="utf-8-sig", errors="replace")
    if path.suffix.lower() == ".xml":  # выключатель на атрибутах XML-конфига (ClassMaster.xml)
        return configs.xml_attr(text, key)
    m = re.search(rf"^\s*{re.escape(key)}\s*=[ \t]*(.*?)\s*$", text, re.MULTILINE)
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


DYNAMIC_EXP_FILE = "DynamicExpRates.xml"
DYNAMIC_EXP_TAG = re.compile(r"<!--.*?-->|<dynamic\b[^>]*>", re.S)


def _dynamic_exp_data(raw: bytes, bands: list | None, max_level: int | None = None) -> bytes:
    """Рейты только в существующих строках dynamic; комментарии, BOM и переводы строк остаются на месте.

    Выше max_level (MaximumPlayerLevel) — всегда 1 и 1: в файле Mobius есть уровень на один выше предела (H5 — 87 при 85),
    а DynamicExpRateData пишет не единичные строки в массив длиной по пределу — запись туда роняет разбор всего файла,
    и опыт по уровням молча не включается. Строки 1/1 Mobius пропускает."""
    if max_level is None:  # предела нет в конфиге — как у всех хроник Mobius: последний уровень файла выше предела
        levels = [int(x) for x in re.findall(r'<dynamic\b[^>]*\blevel\s*=\s*"(\d+)"', raw.decode("utf-8-sig"))]
        max_level = max(levels) - 1 if levels else None

    def replace(m: re.Match) -> str:
        tag = m.group()
        if tag.startswith("<!--"):
            return tag
        found = re.search(r'\blevel\s*=\s*"(\d+)"', tag)
        if not found:
            return tag
        level = int(found.group(1))
        exp, sp = 1, 1
        for start, band_exp, band_sp in bands or []:
            if start > level or (max_level is not None and level > max_level):
                break
            exp, sp = band_exp, band_sp
        for key, value in (("exp", exp), ("sp", sp)):
            pattern = re.compile(rf'(\s{key}\s*=\s*")[^"]*(")')
            if not pattern.search(tag):
                raise ValueError(f"В {DYNAMIC_EXP_FILE} у уровня {level} нет {key}")
            tag = pattern.sub(lambda x: x.group(1) + str(value) + x.group(2), tag, count=1)
        return tag

    text = DYNAMIC_EXP_TAG.sub(replace, raw.decode("utf-8-sig"))
    return (b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b"") + text.encode("utf-8")


def _save_dynamic_exp(path: Path, bands: list | None, max_level: int | None = None) -> bool:
    raw = path.read_bytes()
    new = _dynamic_exp_data(raw, bands, max_level)
    if new == raw:
        return False
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(new)
    os.replace(tmp, path)
    return True


def _dynamic_exp_now(raw: bytes) -> bool:
    for m in DYNAMIC_EXP_TAG.finditer(raw.decode("utf-8-sig")):
        tag = m.group()
        if tag.startswith("<!--"):
            continue
        values = [re.search(rf'\s{key}\s*=\s*"([^"]*)"', tag) for key in ("exp", "sp")]
        if all(values) and any(_num(x.group(1)) != 1 for x in values):
            return True
    return False


def _settings(p: dict, mode: dict) -> dict[str, dict[str, str]]:
    """Прочие ключи режима — {файл: {ключ: значение}}: общие и свои у хроники ("chronicles": {id: {файл: {…}}}).
    Своё у хроники — то, где официал хроник расходится: Vitality у Classic в «Хардкоре» выключена, у Essence и H5 — нет."""
    out = {f: dict(keys) for f, keys in mode.get("settings", {}).items()}
    for f, keys in mode.get("chronicles", {}).get(p.get("chronicle") or p["id"], {}).items():
        out.setdefault(f, {}).update(keys)
    return out


def _present(p: dict, s: dict) -> dict[str, str]:
    """Ключи выключателя, которые есть в сборке, и их значения. Выключатель бывает на несколько ключей разом
    (ShowNpcLevel, ShowNpcAggression, AltGameViewNpc) и в конфигах логина ("part": "login")."""
    out = {}
    if not _matches(p, s):
        return out
    for key in s.get("keys") or [s["key"]]:
        v = _read(p, s["file"], key, s.get("part", "game"))
        if v is not None:
            out[key] = v
    return out


def _edits(p: dict, s: dict, keys, value: bool) -> list[dict]:
    # Выключатель не только True/False: "on"/"off" в таблице — значения ключа (UnstuckInterval: 1 с или 300 с).
    v = s.get("on", "True") if value else s.get("off", "False")
    edits = [{"part": s.get("part", "game"), "file": s["file"], "key": k, "value": v} for k in keys]
    if value and s.get("classPrices"):
        edits.append({"part": s.get("part", "game"), "file": s["file"], "parent": "classMaster", "block": "classChangeOption",
                      "value": _class_options(p, s["classPrices"])})
    return edits


CLASS_GROUPS = ("FIRST_CLASS_GROUP", "SECOND_CLASS_GROUP", "THIRD_CLASS_GROUP")


def _class_options(p: dict, default: str) -> str:
    """Цены мастера профессий (ClassMaster.xml Mobius) — как у охотников (HunterProfessionPrices в PhantomHunters.ini: игрок
    платит столько же); нет модуля фантомов — default из таблицы. Первая цена — 1-я профа (20-й), вторая — 2-я, третья — 3-я."""
    raw = _read(p, "PhantomHunters.ini", "HunterProfessionPrices") or default
    prices = [int(b) for _, b in sorted((int(a), b) for a, b in re.findall(r"(\d+)\s*:\s*(\d+)", raw))][:len(CLASS_GROUPS)]
    return "".join(f'\t\t<classChangeOption name="{f"{price:,} Adena" if price else "Free"}">\n\t\t\t<appliesTo>\n\t\t\t\t<category>{group}</category>\n\t\t\t</appliesTo>\n'
                   + (f'\t\t\t<conditions>\n\t\t\t\t<item id="57" count="{price}" />\n\t\t\t</conditions>\n' if price else "")
                   + "\t\t</classChangeOption>\n" for group, price in zip(CLASS_GROUPS, prices))


def _is_on(s: dict, v: str) -> bool:
    v = v.strip()
    if "on" not in s:
        return v.lower() == "true"
    # anyOn: числовой выключатель включён при любом значении, кроме "off": свои 24 строки в час — тоже поток.
    return v != s.get("off") if s.get("anyOn") else v == s["on"]


def switches(p: dict) -> list[dict]:
    out = []
    for s in table()["switches"]:
        vals = _present(p, s)
        out.append({**s, "keys": s.get("keys") or [s["key"]], "part": s.get("part", "game"), "available": bool(vals),
                    "value": bool(vals) and all(_is_on(s, v) for v in vals.values())})
    return out


def rates_now(p: dict) -> dict:
    """Текущие рейты по группам (xp, sp, adena, drop, spoil): по первому ключу группы; отдельно — опыт по уровням."""
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
    try:
        path = configs._locate(catalog.server_dir(p), "game", DYNAMIC_EXP_FILE)
    except (FileNotFoundError, ValueError):
        out["dynamicExp"] = False
    else:
        out["dynamicExp"] = _dynamic_exp_now(path.read_bytes())
    return out


def state(p: dict) -> dict:
    """Режимы с отметкой активного (все его рейты и выключатели совпадают), выключатели модов и страницы вкладки настроек.
    Страницы — и там, где режимов нет: у своей сборки они показывают рейты и правила по ключам."""
    if why := supported(p):
        return {"ok": False, "error": why, "pages": pages(p), "switches": []}
    sw = {s["id"]: s for s in switches(p)}
    now = rates_now(p)
    modes = []
    for m in table()["modes"]:
        same_rates = all(now.get(g) == float(v) for g, v in m["rates"].items() if now.get(g) is not None)
        same_sw = all(sw[k]["value"] == v for k, v in m["switches"].items() if sw.get(k, {}).get("available"))
        same_set = all((cur := _read(p, f, k)) is None or cur.strip().lower() == str(v).lower()
                       for f, keys in _settings(p, m).items() for k, v in keys.items())
        modes.append({"id": m["id"], "title": m["title"], "short": m["short"], "note": m["note"], "active": same_rates and same_sw and same_set, "keepsRates": not m["rates"], "switches": m["switches"]})
    return {"ok": True, "pages": pages(p), "modes": modes, "switches": list(sw.values()), "rates": now, "huntersMax": hunters_max(p),
            "restart": servers.part_process(p, "game") is not None}


def _save(p: dict, edits: list[dict]) -> dict:
    return configs.save_edits(catalog.server_dir(p), paths.BACKUPS / "config" / p["id"], edits)


def sync_language(p: dict, lang: str) -> None:
    """Язык текстов наших модов в игре — L2HubLanguage (ru | en) в L2HubWorld.ini модуля фантомов — по языку интерфейса
    L2Hub, перед стартом гейма: применяется с запуска. Нет ключа — дописывается (сервер без ключа читает ru); у сборок без
    модуля фантомов файла нет — ничего. Сбой записи старт не ломает."""
    if p.get("type") != "mobius":
        return
    try:
        configs.ensure_value(catalog.server_dir(p), paths.BACKUPS / "config" / p["id"], "game", "L2HubWorld.ini", "L2HubLanguage", lang,
                             "Язык текстов модов L2Hub в игре: ru | en. Пишет лаунчер по языку интерфейса перед запуском.")
    except (OSError, ValueError, KeyError):
        pass


def sync_mind(p: dict) -> None:
    """«Расширенное обдумывание» из «Разума» (настройки мозгов, docs/MIND.md) — в PhantomHunters.ini модуля фантомов: сервер
    перечитывает его на ходу и помечает мозгам охотников рядом с живым игроком (focus). Нет модуля фантомов — ничего;
    сбой записи старт не ломает."""
    if p.get("type") != "mobius":
        return
    from . import brain  # noqa: PLC0415 — brain запускается и отдельным сервисом, держим его импорт здесь

    ext = brain.load_settings(p.get("brain", {}))["extended"]
    keys = (("HunterMindExtended", "True" if ext["enabled"] else "False",
             "Расширенное обдумывание (L2Hub, «Разум»): пешки рядом с живым игроком думают моделью с размышлением. Пишет лаунчер."),
            ("HunterFocusMax", str(int(ext["focusMax"])), "Сколько пешек рядом с игроком думают расширенно — ближайшие. Пишет лаунчер."))
    try:
        for key, value, note in keys:
            configs.ensure_value(catalog.server_dir(p), paths.BACKUPS / "config" / p["id"], "game", "PhantomHunters.ini", key, value, note)
    except (OSError, ValueError, KeyError):
        pass


def set_switch(p: dict, switch_id: str, value: bool) -> dict:
    s = next((x for x in table()["switches"] if x["id"] == switch_id), None)
    keys = _present(p, s) if s else {}
    if not keys:
        raise ValueError(f"В этой сборке нет выключателя «{switch_id}»")
    _save(p, _edits(p, s, keys, value))
    return {"id": switch_id, "value": bool(value)}


def set_switches(p: dict, values: dict) -> dict:
    """Несколько выключателей разом — «Включить все» и «Выключить все» на странице настроек: одна запись и один бэкап
    конфигов, а не по бэкапу на выключатель. Чего в сборке нет — мимо, как в режимах."""
    t = {x["id"]: x for x in table()["switches"]}
    edits, changed = [], []
    for sid, value in values.items():
        s = t.get(sid)
        if s and (keys := _present(p, s)):
            edits.extend(_edits(p, s, keys, bool(value)))
            changed.append(sid)
    if edits:
        _save(p, edits)
    return {"changed": changed}


def hunters_in_table(p: dict) -> int:
    """Сколько охотников в таблице сервера (L2HubPhantoms.xml, пишет tools/fake_players.py); нет файла — 0."""
    return hunter_roster(p)["hunters"]


def hunter_roster(p: dict) -> dict:
    """Только сохранённый XML, без JVM/БД: уникальные имена как в PhantomData.

    Это число записей, а не обещание онлайна: штатная загрузка ещё проверяет классы и экипировку.
    Последняя запись того же имени заменяет предыдущую. Комментарии не считаются персонажами.
    """
    source = catalog.server_dir(p) / "game" / "data" / "L2HubPhantoms.xml"
    try:
        root = ET.parse(source).getroot()
        people = {row.get("name", "").lower(): row.get("kind", "companion")
                  for row in root.findall("phantom") if row.get("name")}
    except (OSError, ET.ParseError):
        return {"hunters": 0, "companions": 0, "templates": 0, "readable": False}
    return {"hunters": sum(kind == "hunter" for kind in people.values()),
            "companions": sum(kind not in ("hunter", "reaper") for kind in people.values()),
            "templates": len(people), "readable": True}


def hunters_max(p: dict) -> dict | None:
    """Строка «Охотников в мире» (docs/PHANTOMS.md, раздел 4.120): count — сколько живёт в мире, 0 в ключе значит все из таблицы.
    None — у хроники такой строки нет (server_modes.json, huntersMax.chronicles) или в сборке нет ключа."""
    n = table().get("huntersMax")
    if not n or (p.get("chronicle") or p["id"]) not in n["chronicles"]:
        return None
    raw, roster = _read(p, n["file"], n["key"]), hunter_roster(p)
    top = roster["hunters"]
    if raw is None or not top:
        return None
    # Как читает сервер: не число или 0 — все из таблицы, больше таблицы — тоже все.
    count = (min(int(raw), top) if raw.isdigit() else 0) or top
    # Сохранённый лимит больше таблицы окно показывает как есть, ключ при чтении не меняется (у всех хроник).
    return {**n, "count": count, "top": top, "heapGb": servers.game_heap_gb(), "machine": machine.assess(p, count, top),
            "configured": int(raw) if raw.isdigit() else 0, "roster": roster, "tableMismatch": raw.isdigit() and int(raw) > top}


def set_hunters_max(p: dict, value) -> dict:
    """Записать, сколько охотников в мире. Вся таблица и больше — 0 в ключе: появятся новые в таблице, войдут и они.
    PhantomHunters.ini сервер перечитывает на ходу, перезапуск не нужен."""
    n = hunters_max(p)
    if n is None:
        raise ValueError("В этой сборке нет настройки числа пешек")
    v = str(value).strip()
    if not v.isdigit() or int(v) < 1:
        raise ValueError(f"Пешек в мире: нужно целое число от 1 до {n['top']}")
    if int(v) > n["top"]:
        raise ValueError(f"В таблице только {n['top']} пешек. Выбранный лимит не изменён; сначала обнови таблицу персонажей.")
    count = min(int(v), n["top"])
    _save(p, [{"part": "game", "file": n["file"], "key": n["key"], "value": str(0 if count == n["top"] else count)}])
    return {"count": count, "top": n["top"], "cut": int(v) > n["top"]}


def apply_mode(p: dict, mode_id: str) -> dict:
    """Режим целиком: рейты, выключатели, опыт по уровням и множители GM-шопа. Отсутствующее в сборке — в skipped."""
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
    # Выключателя нет в сборке (чужая хроника: L2 Pass есть только у Essence) — молча мимо: окно его и не показывает.
    for sid, value in mode["switches"].items():
        s = next(x for x in t["switches"] if x["id"] == sid)
        if keys := _present(p, s):
            edits.extend(_edits(p, s, keys, value))
    # Прочие ключи режима (удобства: автоподбор, автоизучение скиллов, бесплатные маунты) — {файл: {ключ: значение}}, с ключами хроники.
    for file, keys in _settings(p, mode).items():
        for key, value in keys.items():
            if _read(p, file, key) is None:
                skipped.append(f"{file}: {key}")
            else:
                edits.append({"part": "game", "file": file, "key": key, "value": str(value)})
    _save(p, edits)
    # Опыт по уровням и цены GM-шопа — часть рейтов: «Живой сервер» рейты не трогает и сочетается с другими режимами.
    if mode["rates"]:
        try:
            path = configs._locate(catalog.server_dir(p), "game", DYNAMIC_EXP_FILE)
        except (FileNotFoundError, ValueError):
            skipped.append(DYNAMIC_EXP_FILE)
        else:
            cap = _num(_read(p, "Player.ini", "MaximumPlayerLevel"))
            _save_dynamic_exp(path, mode.get("dynamicExp"), int(cap) if cap else None)
        gmshop.set_mode_grades(p, mode.get("gmshopGrades"))
    return {"mode": mode_id, "title": mode["title"], "changed": len(edits), "skipped": skipped}


# ---------- Календарь событий (L2HubEvents.ini, патч 0016 каждой хроники) ----------

CAL_FILE = "L2HubEvents.ini"
# Ключи расписаний рядом с окнами событий (не окна): PvP-ивенты (0019) и нашествие эльпи (Interlude, 0021). Выключатели
# PvpEvents и Elpies — на странице «Ивенты» (server_modes.json); в карточке календаря — остальные.
PVP_KEYS = {"PvpEvents": "bool", "PvpOnlyWithPlayers": "bool", "PvpMinLevel": "int", "TvTMinParticipants": "int", "TvTMaxParticipants": "int", "TvTReservedSlots": "int", "TvTFightMinutes": "int", "TvT": "cron", "Deathmatch": "cron", "CtF": "cron",
			"PvpRegistrationMinutes": "int", "CtFFightMinutes": "int", "DeathmatchFightMinutes": "int",
            "PvpLevelGap": "int", "PvpRewardMobs": "int", "PvpRewardWinnerFactor": "int", "PvpRewardScrolls": "bool", "PvpRewardMedals": "int", "PvpRewardMedalItem": "int",
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
