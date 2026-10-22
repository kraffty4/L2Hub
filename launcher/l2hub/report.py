"""«Собрать отчёт» (docs/RELEASE.md): zip для разбора проблемы — версия, система, рантаймы, «Проверить всё» и хвосты
логов каждой установленной хроники. Без секретов: файлы с ними не берём (data/client.cnf, data/brain/*.key, конфиги
Database.ini), журналы разговоров ботов — тоже (это переписка игрока), а в собранном вычищаем строки с паролями,
ключи вида sk-… и имя пользователя Windows в путях. Кладётся в data/reports, никуда не отправляется."""

import json
import os
import platform
import re
import shutil
import time
import zipfile
from pathlib import Path

import psutil

from . import __version__, catalog, health, l2solo, paths, runtimes, servers

TAIL = 256 * 1024  # с конца каждого лога: свежее — самое нужное, а отчёт должен влезать в issue
SCRUB = [
    (re.compile(r"(?im)^(\s*[\w.-]*pass(?:word)?[\w.-]*\s*[=:]\s*)\S.*$"), r"\1***"),
    (re.compile(r"(?i)(password=)[^&\s\"']+"), r"\1***"),
    (re.compile(r"sk-[A-Za-z0-9_-]{12,}"), "sk-***"),
    (re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._-]{12,}"), r"\1***"),
]


def scrub(text: str) -> str:
    for rx, repl in SCRUB:
        text = rx.sub(repl, text)
    user = os.environ.get("USERNAME")
    if user:
        text = re.sub(rf"(?i)(\\Users\\){re.escape(user)}\b", r"\1<user>", text)
    pc = os.environ.get("COMPUTERNAME")
    if pc:
        text = re.sub(rf"(?i)\b{re.escape(pc)}\b", "<pc>", text)
    return text


def _tail(path: Path) -> str | None:
    try:
        with path.open("rb") as f:
            size = f.seek(0, 2)
            f.seek(max(size - TAIL, 0))
            data = f.read()
    except OSError:
        return None
    text = data.decode("utf-8", errors="replace")
    return (f"[… первые {size - TAIL} байт пропущены …]\n" if size > TAIL else "") + text


def _logs(p: dict) -> dict[str, Path]:
    """Логи хроники: имя в отчёте → файл."""
    cid = p["id"]
    if l2solo.is_l2solo(p):
        out = {"server.log": l2solo.log_path(p), "server.previous.log": l2solo.log_path(p, previous=True)}
    else:
        out = {f"{part}/{kind}.log": servers.log_file(p, part, kind) for part in ("game", "login") for kind in ("java", "error")}
        out["game/L2HubSelfTest.json"] = catalog.server_dir(p) / "game" / "log" / "L2HubSelfTest.json"
    out["brain.stderr.log"] = paths.DATA / "brain" / f"{cid}.stderr.log"
    out["server_build.log"] = paths.DATA / "logs" / f"server_build_{cid}.log"
    out["l2solo.update.log"] = paths.DATA / "l2solo" / f"{cid}.update.log"
    return out


def build(running: str | None = None) -> Path:
    stamp = time.strftime("%Y-%m-%d_%H-%M-%S")
    out = paths.DATA / "reports" / f"L2Hub-report-{stamp}.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    profiles = catalog.load_profiles()
    summary = {
        "l2hub": __version__, "frozen": paths.FROZEN, "time": stamp, "windows": platform.platform(),
        "ramGB": round(psutil.virtual_memory().total / 2**30, 1),
        "home": str(paths.HOME), "app": str(paths.APP), "freeGB": round(shutil.disk_usage(paths.HOME.anchor).free / 2**30, 1),
        "runtimes": runtimes.status() + runtimes.status("l2solo"), "running": running, "installed": sorted(profiles),
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("summary.json", scrub(json.dumps(summary, ensure_ascii=False, indent=2)))
        for cid, p in profiles.items():
            z.writestr(f"{cid}/profile.json", scrub(json.dumps(p, ensure_ascii=False, indent=2)))
            try:
                lines = [f"{c['status']:<4} {c['title']}: {c['detail']}" + (f"  → {c['hint']}" if c.get("hint") else "")
                         for c in health.diagnostics(p)]
            except Exception as e:  # noqa: BLE001 — отчёт собирается и тогда, когда диагностика сама падает
                lines = [f"диагностика упала: {type(e).__name__}: {e}"]
            z.writestr(f"{cid}/check.txt", scrub("\n".join(lines) + "\n"))
            for name, path in _logs(p).items():
                if (text := _tail(path)) is not None:
                    z.writestr(f"{cid}/{name}", scrub(text))
        # Лог MariaDB назван по имени компьютера — в отчёте без него.
        for i, err in enumerate(sorted(paths.DB_DIR.glob("*.err")) if paths.DB_DIR.is_dir() else ()):
            if (text := _tail(err)) is not None:
                z.writestr(f"mariadb/mariadb{i or ''}.err", scrub(text))
    return out
