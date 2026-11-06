"""Место на диске хроники Mobius и удаление (решение автора 2026-11-06, просьба пользователя; docs/SPACE.md).

Что хроника занимает: сервер (исходники, сборка, папка server), база мира в MariaDB, бэкапы базы, копии настроек,
файлы старых миров после вайпов, клиент для друзей. Убрать можно по частям, переустановить базу или удалить хронику
целиком, чтобы освободить место и потом поставить заново кнопкой «Установить».

Насовсем ничего не удаляется: файлы и папки уходят в Корзину (backups.to_recycle_bin; не влезает — Windows спросит),
база перед сносом снимается бэкапом. Только у наших серверов Mobius (wipe.check) и только у выключенного сервера (wipe.blocker).
"""

import os
from pathlib import Path

from . import backups, builder, catalog, paths, servers, wipe

PARTS = (("server", "Сервер: исходники и сборка"), ("database", "База мира"), ("backups", "Бэкапы базы"),
         ("configs", "Копии настроек"), ("wipes", "Старые миры после вайпов"), ("friends", "Клиент для друзей"))
TRASHABLE = ("backups", "configs", "wipes", "friends")


def check(p: dict) -> None:
    """Только наш Mobius, имя базы в профиле совпадает с конфигом логина: снести чужую базу нельзя."""
    wipe.check(p)


def blocker(p: dict) -> str | None:
    return wipe.blocker(p)


def _folder(p: dict, part: str) -> Path:
    cid = p["id"]
    return {"server": paths.SERVERS / cid, "backups": backups.backup_dir(p), "configs": paths.BACKUPS / "config" / cid,
            "wipes": wipe.wipe_root(p), "friends": paths.DATA / "friends" / cid}[part]


def _size(path: Path) -> tuple[int, int]:
    """Байт и файлов в папке. Ссылки и junction не обходятся: их цель — не эта хроника."""
    if path.is_file():
        return path.stat().st_size, 1
    total = files = 0
    stack = [str(path)]
    while stack:
        try:
            with os.scandir(stack.pop()) as it:
                for e in it:
                    if e.is_symlink() or e.is_junction():
                        continue
                    if e.is_dir(follow_symlinks=False):
                        stack.append(e.path)
                    elif e.is_file(follow_symlinks=False):
                        total += e.stat(follow_symlinks=False).st_size
                        files += 1
        except OSError:
            continue
    return total, files


def _db_size(p: dict) -> int | None:
    """Размер базы мира по information_schema; MariaDB выключена — None (поднимать её ради цифры незачем)."""
    if not servers.db_running():
        return None
    rows = wipe._sql(f"SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{p['database']}'")
    return int(rows[0][0]) if rows else 0


def _backup_files(p: dict) -> list[Path]:
    """Файлы бэкапов этой базы (по имени, как в списке «Бэкапов»); backups.log остаётся."""
    d = backups.backup_dir(p)
    return [d / b["name"] for b in backups.list_backups(p)]


def state(p: dict) -> dict:
    """Что занимает хроника: части с размером и путём. Сервер с сотней тысяч файлов считается несколько секунд."""
    check(p)
    out = []
    for part, title in PARTS:
        if part == "database":
            size = _db_size(p)
            out.append({"id": part, "title": title, "size": size, "files": None, "path": p["database"], "exists": True})
            continue
        folder = _folder(p, part)
        if part == "backups":
            items = _backup_files(p)
            size, files = sum(f.stat().st_size for f in items), len(items)
        else:
            size, files = _size(folder) if folder.exists() else (0, 0)
        out.append({"id": part, "title": title, "size": size, "files": files, "path": str(folder), "exists": files > 0})
    return {"parts": out, "blocker": blocker(p), "wipes": len(wipe.list_wipes(p))}


def trash(p: dict, part: str, names: list[str] | None = None, out=print) -> dict:
    """Часть хроники — в Корзину: бэкапы (все или names), копии настроек, файлы старых миров, клиент для друзей."""
    check(p)
    if part not in TRASHABLE:
        raise ValueError(f"Так убрать нельзя: {part}")
    if reason := blocker(p):
        raise RuntimeError(reason)
    if part == "backups":
        files = _backup_files(p)
        if names is not None:
            known = {f.name: f for f in files}
            missing = [n for n in names if n not in known]
            if missing:
                raise ValueError(f"Нет такого бэкапа: {', '.join(missing)}")
            files = [known[n] for n in names]
        for f in files:
            out(f"В Корзину: {f.name}")
            backups.to_recycle_bin(f)
        backups._log(p, f"в Корзину из окна: {', '.join(f.name for f in files) or 'ничего'}")
        return {"part": part, "count": len(files)}
    folder = _folder(p, part)
    if not folder.exists():
        return {"part": part, "count": 0}
    out(f"В Корзину: {folder}")
    backups.to_recycle_bin(folder)
    backups._log(p, f"в Корзину из окна: {folder}")
    return {"part": part, "count": 1}


def _trash_world_files(p: dict, out) -> int:
    """Файлы мира вне базы (wipe.world_files: память мозгов, общество, журналы модов) — в Корзину вместе со старой базой."""
    files = wipe.world_files(p)
    for f in files:
        backups.to_recycle_bin(f)
    if files:
        out(f"Файлы мира в Корзину: {len(files)}")
    return len(files)


def _drop(p: dict, out) -> None:
    db = p["database"]
    if (name := builder.database_name(p["id"])) != db:  # check() уже сверял, но между вызовами конфиг могли поменять
        raise RuntimeError(f"В профиле база {db}, а в конфиге логина {name}: снос отменён")
    out(f"Сношу базу {db}…")
    wipe._sql(f"DROP DATABASE IF EXISTS `{db}`")


def reinstall_db(p: dict, confirm: str | None, out=print) -> dict:
    """База с нуля из SQL установщика сборки, как при установке: учётки тоже. Сначала бэкап «перед переустановкой базы»."""
    check(p)
    if confirm != p["id"]:
        raise ValueError("переустановка базы не подтверждена")
    if reason := blocker(p):
        raise RuntimeError(reason)
    srv = catalog.server_dir(p)
    if not any((srv / "db_installer" / "sql" / "game").glob("*.sql")):
        raise RuntimeError(f"Нет SQL установщика в {srv / 'db_installer' / 'sql'}: базу не из чего ставить")
    result: dict = {}

    def run() -> None:
        out("Бэкап перед переустановкой базы…")
        result["backup"] = backups.make(p, "before-reinstall")["name"]
        result["files"] = _trash_world_files(p, out)
        _drop(p, out)
        builder.fill_database(p, srv, p["database"], out)

    backups._with_mariadb(run)
    backups._log(p, f"база переустановлена из окна: бэкап {result['backup']}, файлов мира в Корзину: {result['files']}")
    return result


def remove(p: dict, confirm: str | None, with_db: bool, with_backups: bool, out=print) -> dict:
    """Удалить хронику, чтобы освободить место и потом поставить заново: сервер и профиль — в Корзину.
    with_db — и базу мира (перед сносом — бэкап «перед удалением хроники»), with_backups — и бэкапы базы.
    Без with_db мир остаётся в MariaDB: новая установка его подхватит (server_setup не трогает существующую базу)."""
    check(p)
    if confirm != p["id"]:
        raise ValueError("удаление хроники не подтверждено")
    if reason := blocker(p):
        raise RuntimeError(reason)
    result: dict = {"db": False, "backups": 0}
    if with_db:
        def drop() -> None:
            out("Бэкап перед удалением хроники…")
            result["backup"] = backups.make(p, "before-remove")["name"]
            result["files"] = _trash_world_files(p, out)
            _drop(p, out)
        backups._with_mariadb(drop)
        result["db"] = True
    if with_backups:
        result["backups"] = trash(p, "backups", out=out)["count"]
    server = _folder(p, "server")
    if server.exists():
        out(f"Сервер в Корзину: {server} — у большой сборки это несколько минут…")
        backups.to_recycle_bin(server)
    profile = paths.PROFILES / f"{p['id']}.json"
    if profile.is_file():
        backups.to_recycle_bin(profile)
    backups._log(p, f"хроника удалена из окна: база {'снесена' if result['db'] else 'осталась'}, бэкапов в Корзину: {result['backups']}")
    return result
