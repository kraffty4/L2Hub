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
  .venv\\Scripts\\python -m l2hub.cli deploy-configs interlude [--prev <zip>] — конфиги собранного архива в сервер
                                                                  (так делает scripts\\server_build.ps1; build — сам)
  .venv\\Scripts\\python -m l2hub.cli dbinit                      — однократно: данные MariaDB и пароль root
  .venv\\Scripts\\python -m l2hub.cli wipe interlude              — вайп мира Mobius: без --confirm только предпросмотр (docs/WIPE.md)
  .venv\\Scripts\\python -m l2hub.cli wipe interlude --confirm interlude
  .venv\\Scripts\\python -m l2hub.cli wipe interlude --undo [время] [--confirm interlude] — откат вайпа
  .venv\\Scripts\\python -m l2hub.cli gmshop ct26_highfive        — разделы GM-шопа, правки и предупреждения (gmshop.py)
  .venv\\Scripts\\python -m l2hub.cli gmshop ct26_highfive show <список> | find <текст> | warn
  .venv\\Scripts\\python -m l2hub.cli gmshop ct26_highfive set <список> <ключ> <цена|-> — правка цены или её снятие
  .venv\\Scripts\\python -m l2hub.cli gmshop ct26_highfive mul <список,список> <множитель> [--apply] — без --apply только предпросмотр
  .venv\\Scripts\\python -m l2hub.cli gmshop ct26_highfive reset [<список,список>] | apply
"""

import argparse
import json
import sys
import time
from pathlib import Path

from . import builder, catalog, gmshop, wipe
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


def gmshop_command(p: dict, sub: str | None, words: list[str], do_apply: bool) -> int:
    def lists(text: str) -> list[int]:
        return [int(x) for x in text.split(",") if x]

    def show(rows: dict) -> None:
        for r in rows["rows"]:
            price = r["price"] if r["editable"] else f"обмен: {r['pay']}"
            print(f" {r['list']:>9} {r['key']:<16} {r['name'][:44]:<44} {price}" + (f" (сборка {r['base']})" if r["edited"] else "") + f"  [{r['section']}]")
            for w in r["warnings"]:
                print(f"     ! {w}")
        print(f"строк {rows['total']}" + (f", показано {rows['limit']}" if rows["total"] > rows["limit"] else ""))

    try:
        if not sub:
            s = gmshop.state(p)
            for e in s["tree"]:
                print(f" {e['list']:>9} {' / '.join([*e['path'], e['title']])[:60]:<60} строк {e['rows']:<4} правок {e['edited']:<4} предупреждений {e['warnings']}")
            print(f"разделов {len(s['tree'])}, правок {s['edited']}, предупреждений {s['warnings']}")
        elif sub == "show":
            show(gmshop.rows(p, lists(words[0])))
        elif sub == "find":
            show(gmshop.rows(p, None, " ".join(words)))
        elif sub == "warn":
            show(gmshop.rows(p, only="warn"))
        elif sub == "set":
            print(gmshop.set_prices(p, [{"list": words[0], "key": words[1], "price": None if words[2] == "-" else words[2]}]))
        elif sub == "mul":
            r = gmshop.multiply(p, lists(words[0]), words[1], preview=not do_apply)
            for c in r["changes"][:40]:
                print(f" {c['list']:>9} {c['key']:<16} {c['name'][:44]:<44} {c['old']} -> {c['new']}")
            for w in r["newWarnings"][:20]:
                print(f"     ! {w['name']} [{w['section']}]: {w['text']}")
            print(f"строк {r['total']}, новых предупреждений {r['newWarningsTotal']}, " + ("применено" if r["applied"] else "только предпросмотр"))
        elif sub == "reset":
            print(gmshop.reset(p, lists(words[0]) if words else None))
        elif sub == "apply":
            print(gmshop.apply(p))
        else:
            print(__doc__)
            return 1
    except (ValueError, IndexError) as e:
        print(f"ОШИБКА: {e}")
        return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(prog="l2hub.cli")
    ap.add_argument("command", choices=["catalog", "state", "check", "start", "stop", "restart", "log", "backups", "backup", "restore", "pack",
                                           "fetch", "build", "setup", "dbinit", "deploy-configs", "wipe", "gmshop"])
    ap.add_argument("chronicle", nargs="?")
    ap.add_argument("name", nargs="?", help="файл бэкапа для restore, подкоманда gmshop")
    ap.add_argument("rest", nargs="*", help="слова подкоманды gmshop")
    ap.add_argument("--apply", action="store_true", help="gmshop mul: применить, а не только показать")
    ap.add_argument("--no-client", action="store_true")
    ap.add_argument("--prev", help="deploy-configs: копия архива прошлой сборки, снятая до Ant")
    ap.add_argument("--confirm", help="wipe: повтор имени хроники, без него только предпросмотр")
    ap.add_argument("--undo", nargs="?", const=wipe.LAST, help="wipe: откат; можно указать время вайпа, иначе — последний")
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
    if args.command == "deploy-configs":
        builder.deploy_built(catalog.profile(args.chronicle), Path(args.prev) if args.prev else None)
        return 0
    if args.command == "wipe":
        return wipe.command(catalog.profile(args.chronicle), args.confirm, args.undo)
    if args.command == "gmshop":
        return gmshop_command(catalog.profile(args.chronicle), args.name, args.rest, args.apply)
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
