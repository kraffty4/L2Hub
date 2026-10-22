"""Геодата хроник (docs/RELEASE.md, «Ненаш контент»): скачать архив проекта L2J Geodata на закреплённом коммите или
построить из карт клиента пользователя утилитой l2mapconv (MIT, runtimes.json). Источники — geodata.json. Без геодаты
сервер работает, но мобы ходят сквозь стены. В релиз геодату не кладём: её берут у источника или строят у себя.

Сборка из клиента — то же, что tools/geodata_build.py (агентам — из консоли): регионы Maps/XX_YY.unr в границах мира
Mobius, задания --geodata-job по процессам в data/geodata_build/<id>/, готовые .l2j — в папку геодаты сервера.
Прерванный запуск продолжается с места: готовые регионы не строятся заново."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

import psutil

from . import paths, runtimes, servers

X_RANGE, Y_RANGE = range(11, 29), range(10, 27)
SETTINGS = {
    "actor_height": 48, "actor_radius": 16, "max_walkable_angle": 45.5,
    "min_walkable_climb": 2, "max_walkable_climb": 16, "cell_size": 16, "cell_height": 1,
}
EXE = paths.RUNTIME / "l2mapconv" / "l2mapconv.exe"
MINUTES_PER_REGION = 2      # на один процесс, замер на Classic 2026-10-21
GB_PER_JOB = 2              # памяти на процесс l2mapconv
L2J = re.compile(r"(?:^|/)geodata/(\d+_\d+\.l2j)$")


class Cancelled(RuntimeError):
    pass


def table() -> dict:
    return json.loads((paths.PACKAGE / "geodata.json").read_text(encoding="utf-8"))


def source(p: dict) -> dict | None:
    """Откуда геодата этой хроники: {"kind": "download", …источник} или {"kind": "client"}; None — не знаем."""
    t = table()
    sid = t["chronicles"].get(p["id"])
    if sid == "client":
        return {"kind": "client", "title": "из карт твоего клиента (l2mapconv)"}
    return {"kind": "download", "id": sid, **t["sources"][sid]} if sid else None


def regions(client: Path) -> list[str]:
    """Регионы клиента в границах мира Mobius: Maps/XX_YY.unr."""
    out = []
    for f in sorted((client / "Maps").glob("*.unr")) if (client / "Maps").is_dir() else ():
        m = re.fullmatch(r"(\d+)_(\d+)", f.stem)
        if m and int(m[1]) in X_RANGE and int(m[2]) in Y_RANGE:
            out.append(f.stem)
    return out


def jobs() -> int:
    """Процессов l2mapconv по свободной памяти (около 2 ГБ на процесс) и ядрам: от 1 до 6."""
    free = psutil.virtual_memory().available / 2**30
    return max(1, min(6, int((free - 2) // GB_PER_JOB), (os.cpu_count() or 2) - 1))


def status(p: dict) -> dict:
    d = servers.geodata_dir(p)
    out = {"files": len(list(d.glob("*.l2j"))) if d.is_dir() else 0, "source": None}
    src = source(p)
    if not src:
        return out
    out["source"] = {"kind": src["kind"], "title": src["title"]}
    if src["kind"] == "download":
        out.update(expected=src["regions"], size=src.get("size"))
    else:
        client = Path((p.get("client") or {}).get("path") or "")
        maps = regions(client) if str(client) not in ("", ".") else []
        n = jobs()
        left = len([m for m in maps if not (d / f"{m}.l2j").is_file()])
        out.update(expected=len(maps) or None, client=bool(maps), jobs=n, tool=EXE.is_file(),
                   minutes=-(-left * MINUTES_PER_REGION // n) if left else 0)
    return out


def _digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def download(p: dict, note=lambda text, frac=None: None, cancelled=lambda: False) -> int:
    """Скачать архив источника (если его ещё нет) и разложить geodata/*.l2j в папку геодаты сервера. Сколько файлов."""
    src = source(p)
    if not src or src["kind"] != "download":
        raise RuntimeError("Для этой хроники геодату не скачивают")
    dest = servers.geodata_dir(p)
    dest.mkdir(parents=True, exist_ok=True)
    name = urllib.parse.unquote(src["url"].rsplit("/", 1)[1])
    archive = paths.HOME / "downloads" / "geodata" / f"{src['id']}-{name}"
    if not archive.is_file() or (src.get("sha256") and _digest(archive) != src["sha256"]):
        archive.parent.mkdir(parents=True, exist_ok=True)
        part = archive.with_name(archive.name + ".part")
        size = src.get("size")
        req = urllib.request.Request(src["url"], headers={"User-Agent": "L2Hub"})
        with urllib.request.urlopen(req, timeout=60) as resp, part.open("wb") as f:  # noqa: S310 — адрес из geodata.json
            done = 0
            while chunk := resp.read(1 << 20):
                if cancelled():
                    raise Cancelled("Скачивание остановлено")
                f.write(chunk)
                done += len(chunk)
                note(f"скачано {done / 2**20:.0f}" + (f" из {size / 2**20:.0f} МБ" if size else " МБ"), done / size * 0.9 if size else None)
        if src.get("sha256") and _digest(part) != src["sha256"]:
            part.unlink(missing_ok=True)
            raise RuntimeError("Контрольная сумма архива не сошлась — архив битый или подменён, попробуй ещё раз")
        part.replace(archive)
    with zipfile.ZipFile(archive) as z:
        members = [(n, m[1]) for n in z.namelist() if (m := L2J.search(n))]
        if len(members) < src["regions"]:
            raise RuntimeError(f"В архиве {len(members)} регионов, ждали {src['regions']}")
        for i, (member, fname) in enumerate(members):
            target = dest / fname
            if not target.is_file():
                tmp = target.with_name(fname + ".part")
                with z.open(member) as r, tmp.open("wb") as w:
                    shutil.copyfileobj(r, w, 1 << 20)
                tmp.replace(target)
            note(f"распаковано {i + 1} из {len(members)}", 0.9 + 0.1 * (i + 1) / len(members))
    return len(members)


def build(p: dict, note=lambda text, frac=None: None, cancelled=lambda: False) -> int:
    """Построить геодату из карт клиента профиля; l2mapconv ставится сам (runtimes.json). Сколько регионов готово."""
    client = Path((p.get("client") or {}).get("path") or "")
    if not regions(client):
        raise RuntimeError("Нужен клиент с картами Maps/*.unr — укажи папку клиента")
    if not EXE.is_file():
        runtimes.install("l2mapconv", out=lambda s: note(s), progress=lambda d, t: note(f"l2mapconv: скачано {d / 2**20:.0f} из {t / 2**20:.0f} МБ"))
    return build_dir(client, servers.geodata_dir(p), paths.DATA / "geodata_build" / p["id"], note, cancelled)


def _kill(proc: subprocess.Popen) -> None:
    try:
        for c in psutil.Process(proc.pid).children(recursive=True):
            c.kill()
        proc.kill()
    except psutil.Error:
        pass


def build_dir(client: Path, dest: Path, work: Path, note=lambda text, frac=None: None, cancelled=lambda: False,
              maps: list[str] | None = None, n_jobs: int | None = None) -> int:
    dest.mkdir(parents=True, exist_ok=True)
    # Прерванный прошлый запуск: готовые регионы (l2mapconv пишет файл целиком) — в папку назначения.
    for f in sorted(work.glob("*/**/*.l2j")):
        if not (dest / f.name).exists():
            shutil.move(str(f), dest / f.name)
    todo = [m for m in (maps or regions(client)) if not (dest / f"{m}.l2j").exists()]
    if not todo:
        return len(list(dest.glob("*.l2j")))
    n = min(n_jobs or jobs(), len(todo))
    stamp = time.strftime("%Y-%m-%d_%H-%M-%S")
    procs = []
    for i in range(n):
        chunk = todo[i::n]
        job = work / f"{stamp}-{i}"
        job.mkdir(parents=True)
        (job / "request.json").write_text(json.dumps({
            "schema_version": 2, "kind": "geodata", "job_id": job.name,
            "client_root": str(client.resolve()).replace("\\", "/"), "maps": chunk,
            "outputs": {"l2j": True, "client_dat": False, "navmesh": False}, "settings": SETTINGS,
        }, indent=1), encoding="utf-8")
        with (job / "run.log").open("w", encoding="utf-8") as log:
            procs.append((job, chunk, subprocess.Popen([str(EXE), "--geodata-job", str(job)], stdout=log, stderr=subprocess.STDOUT, cwd=job,
                                                       creationflags=subprocess.CREATE_NO_WINDOW)))
    stopped = False
    while any(proc.poll() is None for _, _, proc in procs):
        if cancelled():
            stopped = True
            for _, _, proc in procs:
                _kill(proc)
            break
        built = sum(len(list(job.rglob("*.l2j"))) for job, _, _ in procs)
        note(f"построено {built} из {len(todo)}, процессов {n}", built / len(todo))
        time.sleep(3)
    for job, _, proc in procs:
        proc.wait()
        for f in sorted(job.rglob("*.l2j")):
            if not (dest / f.name).exists():
                shutil.move(str(f), dest / f.name)
    missing = [m for m in todo if not (dest / f"{m}.l2j").exists()]
    done = len(todo) - len(missing)
    if stopped:
        raise Cancelled(f"Геодата остановлена: построено {done} из {len(todo)}, следующий запуск продолжит с этого места")
    if missing:
        raise RuntimeError(f"Не построено регионов: {len(missing)} из {len(todo)} — запусти ещё раз, готовые останутся; журналы — {work}")
    return len(list(dest.glob("*.l2j")))
