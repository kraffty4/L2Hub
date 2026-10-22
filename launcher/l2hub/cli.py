"""Команды лаунчера без окна — для проверки и для отладки.

Примеры (из папки launcher):
  .venv\\Scripts\\python -m l2hub.cli state ct26_highfive
  .venv\\Scripts\\python -m l2hub.cli start ct26_highfive --no-client
  .venv\\Scripts\\python -m l2hub.cli check ct26_highfive
  .venv\\Scripts\\python -m l2hub.cli stop ct26_highfive
  .venv\\Scripts\\python -m l2hub.cli backups gracia_final        — список бэкапов базы
  .venv\\Scripts\\python -m l2hub.cli backup gracia_final         — бэкап сейчас
  .venv\\Scripts\\python -m l2hub.cli restore gracia_final <файл> — откат к бэкапу
  .venv\\Scripts\\python -m l2hub.cli pack ct26_highfive          — клиент для друзей (zip в data\\friends)
  .venv\\Scripts\\python -m l2hub.cli fetch interlude             — исходники Mobius на закреплённом коммите (mobius_pins.json)
  .venv\\Scripts\\python -m l2hub.cli build interlude             — сборка из Python (builder.py; сервер остановлен)
  .venv\\Scripts\\python -m l2hub.cli setup interlude             — база и локальный режим свежей сборки
  .venv\\Scripts\\python -m l2hub.cli dbinit                      — однократно: данные MariaDB и пароль root
"""

import argparse
import json
import sys
import time

from . import builder, catalog
from .api import Api


def wait_job(api: Api, chronicle_id: str) -> int:
    last = None
    while True:
        job = api.state(chronicle_id)["job"]
        if job and job["step"] != last:
            last = job["step"]
            print(f"  {job['step']}")
        if job and job["done"]:
            if job["error"]:
                print(f"ОШИБКА: {job['error']}")
                return 1
            return 0
        time.sleep(0.5)


def main() -> int:
    ap = argparse.ArgumentParser(prog="l2hub.cli")
    ap.add_argument("command", choices=["catalog", "state", "check", "start", "stop", "restart", "log", "backups", "backup", "restore", "pack",
                                           "fetch", "build", "setup", "dbinit"])
    ap.add_argument("chronicle", nargs="?")
    ap.add_argument("name", nargs="?", help="файл бэкапа для restore")
    ap.add_argument("--no-client", action="store_true")
    args = ap.parse_args()
    api = Api()
    if args.command == "catalog":
        for c in api.catalog():
            print(f"{c['era']:<24} {c['name']:<26} {c['server']:<11} {'установлена' if c['installed'] else ''}")
        return 0
    if args.command == "dbinit":
        builder.db_init()
        return 0
    if not args.chronicle:
        ap.error("нужно имя хроники")
    if args.command == "fetch":
        builder.fetch_sources(args.chronicle)
        return 0
    if args.chronicle not in catalog.load_profiles():
        print(f"Хроника {args.chronicle} не установлена — установленные: {', '.join(sorted(catalog.load_profiles())) or 'нет'}")
        return 1
    if args.command in ("build", "setup"):
        if api.running() == args.chronicle:
            print("Сначала останови сервер этой хроники: сборка меняет его jar и данные.")
            return 1
        p = catalog.profile(args.chronicle)
        (builder.server_build if args.command == "build" else builder.server_setup)(p)
        return 0
    if args.command == "state":
        print(json.dumps(api.state(args.chronicle), ensure_ascii=False, indent=1))
    elif args.command == "check":
        for c in api.diagnostics(args.chronicle):
            mark = {"ok": "✓", "warn": "!", "bad": "✕", "na": "–"}[c["status"]]
            print(f" {mark} {c['title']}: {c['detail']}" + (f"  → {c['hint']}" if c["hint"] else ""))
    elif args.command == "log":
        print("\n".join(api.log_tail(args.chronicle)))
    elif args.command == "backups":
        r = api.backup_list(args.chronicle)
        for b in r["items"]:
            print(f" {b['time']}  {b['labelText']:<24} {b['size'] / 2**20:7.1f} МБ  {b['name']}")
        print(f"всего {len(r['items'])}; {r['blocker'] or 'бэкап можно снять сейчас'}; журнал: {r['last'] or '—'}")
    else:
        if args.command == "restore" and not args.name:
            ap.error("нужно имя файла бэкапа")
        action = {"start": lambda: api.play(args.chronicle, with_client=not args.no_client),
                  "stop": lambda: api.stop(args.chronicle),
                  "restart": lambda: api.restart_game(args.chronicle),
                  "backup": lambda: api.make_backup(args.chronicle),
                  "restore": lambda: api.restore_backup(args.chronicle, args.name),
                  "pack": lambda: api.make_client_pack(args.chronicle)}[args.command]
        r = action()
        if not r["ok"]:
            print(r["error"])
            return 1
        return wait_job(api, args.chronicle)
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
