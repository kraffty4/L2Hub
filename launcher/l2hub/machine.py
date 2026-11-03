"""Что потянет эта машина: сколько есть ЦПУ, ОЗУ, диска и кто сколько из них берёт, сколько охотников рекомендовать, пора ли перезапускать сервер (docs/PHANTOMS.md, раздел 4.132).
Ядро без окна. Стоимость — machine_costs.json, из замеров на копии мира High Five: куча и ядра ограничивались, а не угадывались. Ничего не запрещает: только советует.
"""
from __future__ import annotations

import json
import shutil
import time
from pathlib import Path

import psutil

from . import catalog, paths, servers

LOOP = 200_000
HEAP_STATUS = "L2HubHeap.json"
HEAP_FRESH = 180  # с: старше — сервер его не пишет
SOON_PERCENT = 70
HINT_PERCENT = 85  # как RestartHintHeapPercent по умолчанию: на куче 4 ГБ при 87 % ещё без остановок, при 91 % мир встал
TIGHT_PERCENT, OVER_PERCENT = 70, 100
TOP_SEARCH = 3000  # дальше этого числа охотников рекомендацию не ищем

_speed: float | None = None


def table() -> dict:
    t = json.loads((paths.PACKAGE / "machine_costs.json").read_text(encoding="utf-8"))
    for g in t.pop("switchGroups", []):  # замерены вместе: каждый выключатель группы получает её итог
        for sid in g["ids"]:
            t["switches"].setdefault(sid, {k: v for k, v in g.items() if k != "ids"})
    return t


def speed(reference: float | None = None) -> float:
    """Скорость одного ядра относительно машины замеров: лучший из трёх коротких циклов, считается один раз за запуск. Python и Java не одно и то же, но порядок даёт."""
    global _speed
    if _speed is None:
        best = 1e9
        for _ in range(3):
            t = time.perf_counter()
            x = 0
            for i in range(LOOP):
                x += i * i % 7
            best = min(best, time.perf_counter() - t)
        _speed = round((reference or table()["reference"]["loopSec"]) / best, 2)
    return _speed


def facts(p: dict) -> dict:
    """ОЗУ, ядра, куча гейма и свободное место там, где лежит сервер."""
    try:
        free = shutil.disk_usage(catalog.server_dir(p)).free
    except OSError:
        free = 0
    return {"ramGb": round(psutil.virtual_memory().total / 2 ** 30, 1), "cores": psutil.cpu_count(logical=False) or psutil.cpu_count() or 1,
            "threads": psutil.cpu_count() or 1, "heapGb": servers.game_heap_gb(), "freeGb": round(free / 2 ** 30, 1), "speed": speed()}


def curve(points: list, x: float, slope: float | None = None) -> float:
    """Кусочно-линейно по точкам замеров [[число, значение], …]; правее последней — по slope или по наклону последнего отрезка."""
    if x <= points[0][0]:
        return points[0][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    (x0, y0), (x1, y1) = points[-2], points[-1]
    return y1 + (x - x1) * (slope if slope is not None else (y1 - y0) / (x1 - x0))


def live_gb(n: int, t: dict) -> float:
    """Занятая куча гейма после сборки при n охотниках в мире, ГБ: мир с геодатой плюс охотники и места, где они качаются."""
    h = t["hunter"]
    return curve(h["liveCurve"], n, h.get("liveSlope"))


def core_factor(cores: int, t: dict) -> float:
    """Во сколько раз тик медленнее, чем на машине замеров с тем же ядром, когда ядер столько: замер с маской на все процессы сервера. Меньше замеренных — как у самого малого."""
    factors = {int(k): v for k, v in t["limits"]["coreFactor"].items()}
    known = [c for c in sorted(factors) if c <= cores]
    return factors[known[-1]] if known else factors[min(factors)]


def tick_ms(n: int, cores: int, core_speed: float, t: dict) -> float:
    """Средний тик охотников на этой машине, мс: тик однопоточный, растёт с их числом и обратно скорости ядра."""
    h = t["hunter"]
    return curve(h["tickCurve"], n, h.get("tickSlope")) * core_factor(cores, t) / max(core_speed, 0.05)


def heap_limit(heap_gb: int, t: dict | None = None) -> int:
    """Сколько охотников влезет в кучу: занятое после сборки не выше heapComfort от неё, дальше ZGC начинает останавливать мир."""
    t = t or table()
    room = t["limits"]["heapComfort"] * heap_gb
    n = 0
    while n < TOP_SEARCH and live_gb(n + 10, t) <= room:
        n += 10
    return n


def cpu_limit(cores: int, core_speed: float, t: dict | None = None) -> int:
    """Сколько охотников успеет тик: средний тик не длиннее tickComfort от периода, иначе охотники думают реже, чем задумано."""
    t = t or table()
    room = t["limits"]["tickComfort"] * t["limits"]["tickPeriodMs"]
    n = 0
    while n < TOP_SEARCH and tick_ms(n + 10, cores, core_speed, t) <= room:
        n += 10
    return n


def recommend(f: dict, t: dict | None = None) -> tuple[int, str]:
    """Рекомендуемый предел охотников и что его ограничило: "heap" или "cpu"."""
    by_heap, by_cpu = heap_limit(f["heapGb"], t), cpu_limit(f["cores"], f["speed"], t)
    return (by_heap, "heap") if by_heap <= by_cpu else (by_cpu, "cpu")


def assess(p: dict, count: int, top: int) -> dict:
    """Для строки «Охотников в мире» и предупреждения перед стартом: сейчас в мире count из top."""
    t = table()
    f = facts(p)
    limit, why = recommend(f, t)
    rec = min(limit, top)
    warnings = []
    if count > rec:
        warnings.append(_too_many(count, rec, why, f))
    if f["freeGb"] < t["limits"]["lowDiskGb"]:
        per_day = count * t["hunter"]["logMbPerHour"] * 24 + t["hunter"]["dbMbPerHour"] * count * 24
        warnings.append(f"На диске с сервером свободно {_num(f['freeGb'])} ГБ. При {count} охотниках логи и база прибавляют около {round(per_day)} МБ в сутки, "
                        f"логи растут до {t['limits']['logCeilingGb']} ГБ, плюс бэкапы базы: освободи место, пока сервер не упёрся в него.")
    return {**f, "recommended": rec, "limitedBy": why, "fits": count <= rec, "warnings": warnings}


def _num(v) -> str:
    return str(v).replace(".", ",")


def _level(percent: float | None, tight: float = TIGHT_PERCENT) -> str:
    return "none" if percent is None else "over" if percent >= OVER_PERCENT else "tight" if percent >= tight else "ok"


def _worst(*levels: str) -> str:
    order = ["none", "ok", "tight", "over"]
    return max(levels, key=order.index)


def _resource(rid: str, title: str, unit: str, have, parts: list[dict], key: str, **extra) -> dict:
    """Одна полоска: сколько есть, кто сколько берёт. percent больше 100 — не влезло; have None — считать не от чего."""
    rows = [{"id": x["id"], "title": x["title"], "value": round(x[key], 3), "source": x["source"]} for x in parts if x.get(key)]
    used = round(sum(r["value"] for r in rows), 3)
    percent = round(used * 100 / have) if have else None
    return {"id": rid, "title": title, "unit": unit, "have": have, "used": used, "percent": percent, "level": _level(percent), "parts": rows, **extra}


def budget(p: dict, hunters: int | None = None, switches: dict | None = None) -> dict:
    """Полоски как в настройках графики: что есть у машины и что берут база сервера, охотники и выключатели.
    hunters и switches ({id: bool}) — «а что, если»: ничего не пишут, только считают; без них — как сейчас в конфигах.
    У ЦПУ и ОЗУ есть узкое место внутри: tick — тик охотников (однопоточный, он и тормозит мир раньше, чем кончатся ядра), heap — занятая куча гейма.
    level полоски — худший из её и узкого места. Окно может считать и само по hunters.curves и switches[id].cost, не спрашивая на каждый щелчок.
    Значения source: measured — замерено отдельно, group — замерено вместе с группой выключателей, estimated — оценено, draft — черновик, unmeasured — не мерилось.
    effect выключателя: number — есть числа в cost, none — эффект в замере не вышел из шума, unknown — не мерилось."""
    from . import modes  # noqa: PLC0415 — modes сам зовёт machine

    t = table()
    f = facts(p)
    chronicle = p.get("chronicle") or p["id"]
    mark = t["chronicles"].get(chronicle)
    hm = modes.hunters_max(p)
    count = 0 if not hm else hm["count"] if hunters is None else min(max(0, int(hunters)), hm["top"])
    on = {s["id"]: s["value"] for s in modes.switches(p) if s["available"]} if not modes.supported(p) else {}
    on.update({k: bool(v) for k, v in (switches or {}).items() if k in on})
    h = t["hunter"]
    costs = {}
    parts = []
    live = tick = None
    if mark:
        b = t["base"]
        live0 = live_gb(0, t)
        parts.append({"id": "base", "title": b["title"], "source": b["source"], "cpu": curve(h["cpuCurve"], 0), "ram": b["ram"], "disk": b["disk"], "heapLive": live0})
        parts.append({"id": "heap", "title": t["heap"]["title"], "source": t["heap"]["source"], "ram": f["heapGb"]})
        if hm:
            parts.append({"id": "hunters", "title": h["title"], "source": h["source"], "cpu": curve(h["cpuCurve"], count, h.get("cpuSlope")) - curve(h["cpuCurve"], 0),
                          "heapLive": live_gb(count, t) - live0, "disk": h["dbMbPerHour"] * count * 24 * 30 / 1024})
        tick = tick_ms(count, f["cores"], f["speed"], t)
        for s in modes.switches(p):
            c = t["switches"].get(s["id"])
            if c and s["available"]:
                costs[s["id"]] = {"title": s["title"], "on": on.get(s["id"], False), "source": c["source"], "effect": c.get("effect", "number"),
                                  "cost": {k: c[k] for k in ("cpu", "ram", "disk", "heapLive", "tickMs") if k in c}, "note": c.get("note", "")}
                if on.get(s["id"]):
                    parts.append({"id": s["id"], "title": s["title"], "source": c["source"], **{k: c.get(k, 0) for k in ("cpu", "ram", "disk", "heapLive")}})
                    tick += c.get("tickMs", 0) * core_factor(f["cores"], t) / max(f["speed"], 0.05)
        parts.extend(t["extras"])
        live = sum(x.get("heapLive", 0) for x in parts)
    logs_hour = h["logMbPerHour"] * count
    disk_have = f["freeGb"] + (t["base"]["disk"] if catalog.server_dir(p).is_dir() else 0)  # сервер уже стоит: его место в свободном не считается
    cpu_have = round(f["cores"] * f["speed"], 2)
    limit, why = recommend(f, t)
    rec = min(limit, hm["top"]) if hm else 0
    heap_pct = round(live * 100 / f["heapGb"]) if live is not None and f["heapGb"] else None
    period = t["limits"]["tickPeriodMs"]
    tick_pct = round(tick * 100 / period) if tick is not None else None
    cpu = _resource("cpu", "ЦПУ", "ядер", cpu_have, parts, "cpu", note=f"{f['cores']} ядер, скорость ядра {_num(f['speed'])} от машины замеров: {t['reference']['title']}",
                    tick={"haveMs": period, "usedMs": None if tick is None else round(tick), "percent": tick_pct, "comfort": round(t["limits"]["tickComfort"] * 100),
                          "note": "Тик охотников однопоточный: он тормозит мир раньше, чем кончатся ядра."})
    cpu["level"] = _worst(cpu["level"], _level(tick_pct, t["limits"]["tickComfort"] * 100))
    ram = _resource("ram", "ОЗУ", "ГБ", f["ramGb"], parts, "ram",
                    heap={"have": f["heapGb"], "used": None if live is None else round(live, 2), "percent": heap_pct, "comfort": round(t["limits"]["heapComfort"] * 100),
                          "note": "Куча гейма занята всегда целиком: ZGC берёт выданное. Тесно бывает внутри неё — после сборки мира и охотников."})
    ram["level"] = _worst(ram["level"], _level(heap_pct, t["limits"]["heapComfort"] * 100))
    resources = [
        cpu,
        ram,
        _resource("disk", "Диск", "ГБ", round(disk_have, 1), parts, "disk", logsMbPerHour=round(logs_hour, 1), logCeilingGb=t["limits"]["logCeilingGb"]),
        {"id": "vram", "title": "Видеопамять", "unit": "ГБ", "have": None, "used": 0, "percent": None, "level": "none", "parts": [], "note": "Серверу не нужна. Займут её локальные нейронки, их проверка записана в план отдельно."},
    ]
    a = assess(p, count, hm["top"]) if hm else {"warnings": []}
    curves = {"heapLiveGb": h["liveCurve"], "heapLiveSlope": h.get("liveSlope"), "tickMs": h["tickCurve"], "tickSlope": h.get("tickSlope"),
              "cpu": h["cpuCurve"], "cpuSlope": h.get("cpuSlope"), "coreFactor": core_factor(f["cores"], t), "speed": f["speed"]}
    return {"ok": True, "chronicle": chronicle, "mark": mark, "machine": f, "resources": resources, "switches": costs,
            "hunters": None if not hm else {"count": count, "top": hm["top"], "recommended": rec, "limitedBy": why, "source": h["source"], "curves": curves,
                                            "logMbPerHour": h["logMbPerHour"], "dbMbPerHour": h["dbMbPerHour"]},
            "warnings": a["warnings"], "note": "" if mark == "measured" else "Для этой хроники замеров нет: показано только то, что есть у машины." if not mark else "Числа взяты с High Five: эта хроника устроена так же, но отдельно не мерилась."}


def fresh_default(p: dict, out=print) -> None:
    """Новый мир (базы ещё не было): на слабой машине сразу столько охотников, сколько рекомендуем. Мир с базой не трогаем: там только предупреждение, число выбирает хозяин."""
    from . import modes  # noqa: PLC0415 — modes сам зовёт machine

    hm = modes.hunters_max(p)
    if hm and hm["machine"]["recommended"] < hm["top"]:
        modes.set_hunters_max(p, hm["machine"]["recommended"])
        out(f"Охотников в мире: {hm['machine']['recommended']} из {hm['top']}, по возможностям этой машины. Поменять — «Настройки сервера».")


def _too_many(count: int, rec: int, why: str, f: dict) -> str:
    if why == "heap":
        return (f"Сейчас {count} охотников, а этой машине рекомендуем до {rec}: куча гейма {f['heapGb']} ГБ, мир с геодатой занимает в ней около 3 ГБ, "
                "каждая сотня охотников — ещё около 0,25 ГБ. Дальше сервер встаёт на сборку мусора, тик тянется секундами, а запись в базу "
                "начинает падать от нехватки памяти. Больше памяти даёт только больше ОЗУ.")
    return (f"Сейчас {count} охотников, а этой машине рекомендуем до {rec}: процессор не успевает обдумать всех за тик, мир будет запаздывать. "
            "Запустить можно, просто будет тормозить.")


def _hint_percent(p: dict) -> int:
    """С какого процента занятой кучи сервер просит перезапуск (RestartHintHeapPercent в PhantomHunters.ini)."""
    from . import modes  # noqa: PLC0415 — modes сам зовёт machine

    try:
        return int(modes._read(p, "PhantomHunters.ini", "RestartHintHeapPercent") or HINT_PERCENT)
    except ValueError:
        return HINT_PERCENT


def heap_state(p: dict) -> dict | None:
    """Занятая куча после сборки и подсказка о перезапуске — из файла сервера (PhantomRestartHint); нет файла или он старый — None."""
    f: Path = catalog.server_dir(p) / "game" / "log" / HEAP_STATUS
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
        if time.time() - f.stat().st_mtime > HEAP_FRESH:
            return None
        used, top, up = int(data["afterGcMb"]), int(data["maxMb"]), int(data["uptimeSec"])
    except (OSError, ValueError, KeyError, TypeError):
        return None
    percent = used * 100 // top if top else 0
    hot = _hint_percent(p)
    level = "now" if data.get("hot") else "soon" if percent >= SOON_PERCENT else "ok"
    text = ""
    if level == "now":
        text = f"Память сервера занята на {percent} % после сборки ({used} из {top} МБ), работает {up // 3600} ч. Перезапусти его, когда будет удобно: скоро мир начнёт тормозить, а потом может упасть."
    elif level == "soon":
        text = f"Память сервера занята на {percent} % после сборки ({used} из {top} МБ), работает {up // 3600} ч. Пока терпимо; с {hot} % пора перезапускать."
    return {"afterGcMb": used, "maxMb": top, "percent": percent, "uptimeH": up // 3600, "level": level, "text": text}
