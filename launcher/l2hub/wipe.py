"""Вайп мира хроники Mobius: мир с нуля, учётки и настройки остаются (docs/WIPE.md).

Мир живёт в базе и в нескольких файлах рядом с ней. База не чистится по списку таблиц: рядом заливается чистая из SQL
установщика сборки (как в builder.server_setup), и все таблицы гейма разом подменяются одним RENAME TABLE. Таблицы логина
(sql/login: учётки и регистрация гейма на логине) не трогаются. Так нельзя забыть таблицу, статичные строки (замки, форты,
гварды) возвращаются сами, а ошибка в SQL или в бэкапе ловится до первого изменения мира.

Бэкап before-wipe проверяется до вайпа настоящей заливкой рядом и сверкой числа строк по всем таблицам. Файлы вне базы не
удаляются, а уезжают в data/backups/wipe/<id>/<время>/. Откат (undo) подменяет базу таблицами из того же дампа, файлы
возвращаются копией; то, что успело накопиться в новом мире, остаётся в бэкапе before-restore и в папке <время>_undo_<время>.

Без подтверждения вайп — холостой прогон (preview): считает, заливает чистую базу рядом и сверяет столбцы, мир не меняет.
"""

import hashlib
import json
import re
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

from . import backups, builder, catalog, paths, servers

NEW, CHECK, OLD = "__wipe_new", "__wipe_check", "__wipe_old"
LAST = "last"
CREATE_RE = re.compile(r"CREATE TABLE(?: IF NOT EXISTS)?\s+`?(\w+)`?", re.IGNORECASE)
DUMP_END = b"-- Dump completed"
# Отметки «одноразовая команда из PhantomHunters.ini уже выполнена». Без них новый мир счёл бы старые номера невыполненными
# и на первом тике запустил бы сброс ALL и самопроверку, что осталась в ini. Это не состояние мира: переезжают в новую базу.
KEEP_VARIABLES = ("L2HubHuntersResetId", "L2HubHuntersSelfTestId")
SUMMARY = (("clan_data", "кланов"), ("items", "предметов"), ("character_skills", "навыков персонажей"), ("clan_wars", "войн кланов"), ("crests", "эмблем"),
           ("olympiad_nobles", "дворян олимпиады"), ("heroes", "героев"), ("seven_signs", "участников Семи Печатей"), ("auction", "аукционов клан-холлов"),
           ("global_variables", "переменных мира"))


# ---------- База ----------

def _sql(sql: str, db: str | None = None, timeout: int = 600) -> list[list[str]]:
    """Запрос через stdin: RENAME всех таблиц длиннее, чем можно передать в командной строке Windows."""
    cmd = [str(paths.MARIA_BIN / "mariadb.exe"), f"--defaults-extra-file={paths.CLIENT_CNF}", "--default-character-set=utf8mb4", "-N", "-B"]
    r = subprocess.run([*cmd, db] if db else cmd, input=sql.encode("utf-8"), capture_output=True, timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW)
    if r.returncode:
        raise RuntimeError(f"MariaDB: {r.stderr.decode('utf-8', 'replace').strip()[-500:]}")
    return [line.split("\t") for line in r.stdout.decode("utf-8", "replace").splitlines() if line]


def _exists(db: str) -> bool:
    return bool(_sql(f"SELECT 1 FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{db}'"))


def _tables(db: str) -> list[str]:
    return [r[0] for r in _sql(f"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='{db}' AND TABLE_TYPE='BASE TABLE'")]


def _counts(db: str) -> dict[str, int]:
    """Точное число строк по всем таблицам базы."""
    names = _tables(db)
    if not names:
        return {}
    return {r[0]: int(r[1]) for r in _sql(" UNION ALL ".join(f"SELECT '{t}', COUNT(*) FROM `{db}`.`{t}`" for t in names))}


def _columns(db: str) -> dict[tuple[str, str], str]:
    return {(r[0], r[1]): r[2] for r in _sql(f"SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='{db}'")}


def _side(p: dict, suffix: str) -> str:
    return p["database"] + suffix


def _drop_side(db: str) -> None:
    if not db.endswith((NEW, CHECK, OLD)):
        raise RuntimeError(f"{db} — не служебная база вайпа")  # DROP DATABASE здесь только для своих трёх имён
    _sql(f"DROP DATABASE IF EXISTS `{db}`")


def _drop_empty(db: str) -> None:
    """После неудачной подмены: если вдруг в базе что-то есть, её не удаляем, новый вайп на ней откажет и об этом скажет."""
    if not _tables(db):
        _drop_side(db)


def _create_side(p: dict, suffix: str) -> str:
    """Пустая служебная база с той же кодировкой по умолчанию, что у базы хроники: столбцы без своей кодировки её наследуют."""
    db = _side(p, suffix)
    _drop_side(db)
    rows = _sql(f"SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{p['database']}'")
    if not rows:
        raise RuntimeError(f"В MariaDB нет базы {p['database']}: вайпать нечего")
    _sql(f"CREATE DATABASE `{db}` DEFAULT CHARACTER SET {rows[0][0]} COLLATE {rows[0][1]}")
    return db


def _swap(db: str, new: str, old: str, keep: set[str]) -> None:
    """Подмена одним оператором: RENAME TABLE атомарен, мир либо старый, либо новый целиком."""
    clauses = [f"`{db}`.`{t}` TO `{old}`.`{t}`" for t in _tables(db) if t not in keep]
    clauses += [f"`{new}`.`{t}` TO `{db}`.`{t}`" for t in _tables(new) if t not in keep]
    _sql("RENAME TABLE " + ",\n".join(clauses))


def _guard_objects(db: str) -> None:
    """Процедуры, события, триггеры и представления RENAME не переносит. У Mobius их нет; появились — этот способ не подходит."""
    row = _sql(f"SELECT (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='{db}'), (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='{db}'), "
               f"(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='{db}'), (SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA='{db}')")[0]
    if any(int(n) for n in row):
        raise RuntimeError(f"В базе {db} есть процедуры, события, триггеры или представления ({'/'.join(row)}): подмена таблиц их не перенесёт, вайп отменён")


# ---------- Установщик и проверки ----------

def check(p: dict) -> None:
    """Только наш сервер Mobius, и база в профиле — та же, что в конфиге логина: вайпнуть не ту базу нельзя."""
    if p.get("type") != "mobius" or servers.own_db(p):
        raise RuntimeError(f"Вайп этим инструментом — только для хроник Mobius, а {p['id']} — {p.get('type')}. У L2Solo свой вайп в окне.")
    if not re.fullmatch(r"\w+", p["database"]):
        raise RuntimeError(f"Странное имя базы в профиле: {p['database']!r}")
    if (name := builder.database_name(p["id"])) != p["database"]:
        raise RuntimeError(f"В профиле база {p['database']}, а в конфиге логина {name}: вайп отменён, сначала разберись, какая настоящая")


def blocker(p: dict) -> str | None:
    """Почему нельзя трогать мир сейчас; None — можно. Порты общие у всех профилей: занятый порт — чей-то работающий сервер."""
    running = [part for part in ("game", "login", "brain") if servers.part_process(p, part)]
    if running:
        return f"Сначала останови сервер: работает {', '.join(running)}."
    busy = [str(p["ports"][part]) for part in ("login", "game") if servers.port_open(p["ports"][part])]
    if busy:
        return f"Заняты порты {', '.join(busy)}: работает какой-то сервер. Сначала останови его."
    return None


def _installer(p: dict) -> tuple[list[Path], set[str]]:
    """SQL гейма и имена таблиц логина: их не трогаем. Таблицы логина — учётки и gameservers, где лежит hexid: без него гейм не зарегистрируется."""
    root = catalog.server_dir(p) / "db_installer" / "sql"
    login, game = sorted((root / "login").glob("*.sql")), sorted((root / "game").glob("*.sql"))
    if not login or not game:
        raise RuntimeError(f"Нет SQL установщика в {root}")
    keep = {m[1].lower() for f in login for m in CREATE_RE.finditer(f.read_text(encoding="utf-8", errors="replace"))}
    if not {"accounts", "gameservers"} <= keep:
        raise RuntimeError(f"В {root / 'login'} нет таблиц accounts и gameservers")
    return game, keep


def _build_new(p: dict, game: list[Path]) -> str:
    """Чистая база рядом и отметки одноразовых команд. Мир ещё не тронут: ошибка в SQL здесь ничего не ломает."""
    new = _create_side(p, NEW)
    _sql("".join(f"source {f.as_posix()};\n" for f in game), new)
    db = p["database"]
    if {"global_variables"} <= set(_tables(db)) & set(_tables(new)):
        names = ", ".join(f"'{v}'" for v in KEEP_VARIABLES)
        _sql(f"INSERT INTO `{new}`.`global_variables` (`var`, `value`) SELECT `var`, `value` FROM `{db}`.`global_variables` WHERE `var` IN ({names})")
    return new


def _schema_diff(db: str, new: str, keep: set[str]) -> list[str]:
    """Чем столбцы чистой базы отличаются от живой. Код сборки рассчитан на установщик, поэтому это предупреждение, а не отказ."""
    live, fresh = _columns(db), _columns(new)
    live = {k: v for k, v in live.items() if k[0] not in keep and k[0] in {t for t, _c in fresh}}
    fresh = {k: v for k, v in fresh.items() if k[0] not in keep and k[0] in {t for t, _c in live}}
    out = [f"{t}.{c}: только в живой базе" for t, c in sorted(set(live) - set(fresh))]
    out += [f"{t}.{c}: только в установщике" for t, c in sorted(set(fresh) - set(live))]
    out += [f"{t}.{c}: {live[(t, c)]} станет {fresh[(t, c)]}" for t, c in sorted(set(live) & set(fresh)) if live[(t, c)] != fresh[(t, c)]]
    return out


def _verify_dump(p: dict, name: str, expected: dict[str, int]) -> None:
    """Бэкап годен, только если заливается и даёт те же строки: заливка рядом и сверка по всем таблицам."""
    src = backups.backup_dir(p) / name
    with src.open("rb") as f:
        f.seek(max(0, src.stat().st_size - 200))
        if DUMP_END not in f.read():
            raise RuntimeError(f"Бэкап {name} оборван: в конце нет отметки mariadb-dump. Вайп отменён, мир не тронут.")
    check_db = _create_side(p, CHECK)
    try:
        backups._import({"database": check_db}, src)
        got = _counts(check_db)
    finally:
        _drop_side(check_db)
    if got != expected:
        wrong = sorted(t for t in set(got) | set(expected) if got.get(t) != expected.get(t))
        raise RuntimeError(f"Бэкап {name} не сошёлся с базой по таблицам {', '.join(wrong[:10])}. Вайп отменён, мир не тронут.")


# ---------- Файлы вне базы ----------

def world_files(p: dict) -> list[Path]:
    """Всё, что относится к миру вне базы (опись — docs/WIPE.md). Журналы сервера и usage.json мозгов остаются."""
    log = catalog.server_dir(p) / "game" / "log"
    brain, society = paths.DATA / "brain", paths.DATA / "society"
    cid = p["id"]
    found = [*sorted(log.glob("L2Hub*")), log / "olympiad.csv", brain / f"{cid}.sqlite", brain / f"{cid}.sqlite-wal", brain / f"{cid}.sqlite-shm", brain / f"{cid}.sqlite-journal",
             brain / f"{cid}.log", society / f"{cid}.json", society / f"{cid}_forum.json"]
    return [f for f in found if f.is_file()]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def _describe(files: list[Path]) -> list[dict]:
    return [{"path": f.relative_to(paths.HOME).as_posix(), "size": f.stat().st_size, "sha256": _sha256(f)} for f in files]


def _stash(items: list[dict], folder: Path, moved: list[tuple[Path, Path]]) -> None:
    for item in items:
        src, dst = paths.HOME / item["path"], folder / "files" / item["path"]
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
        moved.append((src, dst))


def _unstash(moved: list[tuple[Path, Path]]) -> None:
    for src, dst in reversed(moved):
        shutil.move(str(dst), str(src))


def wipe_root(p: dict) -> Path:
    return paths.BACKUPS / "wipe" / p["id"]


def _stamp() -> str:
    return datetime.now().strftime("%Y-%m-%d_%H-%M-%S")


def _write_manifest(folder: Path, manifest: dict) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    tmp = folder / "manifest.json.tmp"
    tmp.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(folder / "manifest.json")


def list_wipes(p: dict) -> list[dict]:
    """Прошлые вайпы, новые первыми."""
    root = wipe_root(p)
    out = []
    for f in sorted(root.glob("*/manifest.json"), reverse=True) if root.is_dir() else []:
        try:
            manifest = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if manifest.get("kind") == "wipe" and manifest.get("state") != "failed":  # неудачный вайп мир не менял, откатывать нечего
            out.append(manifest)
    return out


# ---------- Предпросмотр ----------

def preview(p: dict) -> dict:
    """Холостой прогон: что уйдёт, что останется, заливается ли чистая база. Мир не меняет."""
    check(p)
    out: dict = {"blocker": blocker(p)}

    def run() -> None:
        db = p["database"]
        game, keep = _installer(p)
        _guard_objects(db)
        before = _counts(db)
        if not before:
            raise RuntimeError(f"В базе {db} нет таблиц: вайпать нечего")
        new = _build_new(p, game)
        try:
            fresh = _counts(new)
            schema = _schema_diff(db, new, keep)
        finally:
            _drop_side(new)
        people = _sql(f"SELECT char_name, level, account_name, accesslevel FROM characters WHERE {servers.not_phantom_sql(p)} ORDER BY level DESC", db)
        files = world_files(p)
        out.update(id=p["id"], database=db, before=before, keep={t: n for t, n in before.items() if t in keep}, fresh={t: n for t, n in fresh.items() if t not in keep},
                   gone=sorted(set(before) - set(fresh) - keep), added=sorted(set(fresh) - set(before) - keep), schema=schema, people=people,
                   files=[{"path": f.relative_to(paths.HOME).as_posix(), "size": f.stat().st_size} for f in files], leftover=_exists(_side(p, OLD)))

    backups._with_mariadb(run)
    return out


def _render_preview(r: dict) -> list[str]:
    before = r["before"]
    world = {t: n for t, n in before.items() if t not in r["keep"]}
    people, total = r["people"], before.get("characters", 0)
    lines = [f"Вайп мира {r['id']}, база {r['database']}. Это предпросмотр: мир не менялся.", "",
             r["blocker"] or "Сервер выключен, вайпать можно.", "", "Уйдёт из мира (копия — в бэкапе before-wipe):",
             f"  людей: {len(people)}", *[f"    {x[0]}, уровень {x[1]}, учётка {x[2]}" + (f", GM {x[3]}" if int(x[3]) > 0 else "") for x in people],
             f"  охотников, напарников и прочих фантомов: {total - len(people)}"]
    lines += [f"  {label}: {before[t]}" for t, label in SUMMARY if t in before]
    lines += [f"  всего: таблиц {len(world)}, строк {sum(world.values())}", "", "Останется как есть:",
              "  " + ", ".join(f"{t} {n}" for t, n in sorted(r["keep"].items())), "  настройки сервера в config, журналы сервера, ключи и настройки мозгов, бэкапы", "",
              "Файлы вне базы уедут в " + (paths.BACKUPS / "wipe" / r["id"] / "<время>").as_posix() + ":"]
    lines += [f"  {f['path']}, {f['size']} байт" for f in r["files"]] or ["  нет"]
    lines += ["", f"Чистая база из установщика залилась: таблиц {len(r['fresh'])}, строк {sum(r['fresh'].values())}."]
    if r["gone"]:
        lines.append(f"Таблицы, которых нет в установщике, уйдут без замены: {', '.join(r['gone'])}")
    if r["added"]:
        lines.append(f"Таблицы, которых не было в живой базе, появятся: {', '.join(r['added'])}")
    lines += [f"Столбцы расходятся, станет как в установщике ({len(r['schema'])}):", *[f"  {x}" for x in r["schema"][:30]]] if r["schema"] else ["Столбцы совпадают."]
    if r["leftover"]:
        lines.append(f"Осталась база {r['database']}{OLD} от прерванного вайпа: новый вайп откажет, сначала откат.")
    lines += ["", f"Выполнить:  python -m l2hub.cli wipe {r['id']} --confirm {r['id']}", f"Откат после: python -m l2hub.cli wipe {r['id']} --undo --confirm {r['id']}"]
    return lines


# ---------- Вайп ----------

def wipe(p: dict, confirm: str | None, out=print) -> dict:
    """Вайп. confirm должен повторить id хроники — защита от случайного вызова. Сервер должен быть выключен."""
    check(p)
    if confirm != p["id"]:
        raise RuntimeError(f"Вайп не подтверждён: нужно --confirm {p['id']}")
    if reason := blocker(p):
        raise RuntimeError(reason)
    result: dict = {}
    backups._with_mariadb(lambda: result.update(_wipe(p, out)))
    return result


def _wipe(p: dict, out) -> dict:
    db, old = p["database"], _side(p, OLD)
    if _exists(old):
        raise RuntimeError(f"Осталась база {old} от прерванного вайпа. Сначала откат: python -m l2hub.cli wipe {p['id']} --undo --confirm {p['id']}")
    _guard_objects(db)
    game, keep = _installer(p)
    stamp = _stamp()
    folder = wipe_root(p) / stamp
    out("Заливаю чистую базу рядом…")
    new = _build_new(p, game)
    try:
        fresh = {t: n for t, n in _counts(new).items() if t not in keep}
        out("Бэкап before-wipe…")
        backup = backups.make(p, "before-wipe")["name"]
        before = _counts(db)
        out("Проверяю бэкап заливкой рядом…")
        _verify_dump(p, backup, before)
        items = _describe(world_files(p))
        manifest = {"kind": "wipe", "id": p["id"], "database": db, "stamp": stamp, "state": "wiping", "backup": backup, "keep": sorted(keep), "before": before, "fresh": fresh, "files": items}
        _write_manifest(folder, manifest)
        if reason := blocker(p):
            raise RuntimeError(reason)
        out(f"Подменяю мир: файлов {len(items)}, таблиц {len(fresh)}…")
        moved: list[tuple[Path, Path]] = []
        try:
            _stash(items, folder, moved)
            _create_side(p, OLD)
            _swap(db, new, old, keep)
        except Exception:
            _unstash(moved)  # RENAME атомарен: база осталась старой, файлы возвращаются к ней
            _drop_empty(old)
            manifest["state"] = "failed"
            _write_manifest(folder, manifest)
            raise
    finally:
        _drop_side(new)
    after = _counts(db)
    expected = {**fresh, **{t: n for t, n in before.items() if t in keep}}
    if after != expected:
        wrong = sorted(t for t in set(after) | set(expected) if after.get(t) != expected.get(t))
        raise RuntimeError(f"Сверка после вайпа не сошлась по таблицам {', '.join(wrong[:10])}. Откат: python -m l2hub.cli wipe {p['id']} --undo {stamp} --confirm {p['id']}")
    _drop_side(old)  # всё это есть в проверенном дампе
    manifest.update(state="done", after=after)
    _write_manifest(folder, manifest)
    backups._log(p, f"вайп мира {stamp}: бэкап {backup}, файлов в {folder.name}: {len(items)}")
    return {"stamp": stamp, "backup": backup, "folder": str(folder), "files": len(items), "rows": sum(after.values())}


# ---------- Откат ----------

def _find(p: dict, stamp: str | None) -> dict:
    items = list_wipes(p)
    found = items[0] if items and stamp in (None, LAST) else next((m for m in items if m["stamp"] == stamp), None)
    if not found:
        raise RuntimeError(f"Нет такого вайпа {p['id']}: {stamp or 'ни одного'}. Есть: {', '.join(m['stamp'] for m in items) or 'ничего'}")
    return found


def _check_stash(p: dict, manifest: dict) -> None:
    """Бэкап и файлы вайпа на месте и не менялись: откат начнётся, только если вернуть есть что."""
    if not (backups.backup_dir(p) / manifest["backup"]).is_file():
        raise RuntimeError(f"Нет бэкапа {manifest['backup']}: откатывать не из чего")
    folder = wipe_root(p) / manifest["stamp"] / "files"
    for item in manifest["files"]:
        f = folder / item["path"]
        if not f.is_file() or _sha256(f) != item["sha256"]:
            raise RuntimeError(f"Файл вайпа пропал или изменился: {f}")


def undo_plan(p: dict, stamp: str | None) -> list[str]:
    check(p)
    m = _find(p, stamp)
    _check_stash(p, m)
    return [f"Откат вайпа {m['stamp']} ({p['id']}, состояние {m['state']}). Это предпросмотр: мир не менялся.", "", blocker(p) or "Сервер выключен, откатывать можно.", "",
            f"База вернётся к {m['backup']}: таблиц {len(m['before'])}, строк {sum(m['before'].values())}.",
            f"Вернутся файлы: {len(m['files'])}.",
            "Новый мир не пропадёт: база — в бэкапе before-restore, файлы — в папке <время вайпа>_undo_<время>.",
            "Учётки вернутся такими, какими были до вайпа. Созданные после него и новые пароли останутся только в before-restore.", "",
            f"Выполнить: python -m l2hub.cli wipe {p['id']} --undo {m['stamp']} --confirm {p['id']}"]


def undo(p: dict, stamp: str | None, confirm: str | None, out=print) -> dict:
    """Откат: confirm повторяет id хроники. База и файлы возвращаются к состоянию перед вайпом, число строк сверяется с записанным при вайпе."""
    check(p)
    if confirm != p["id"]:
        raise RuntimeError(f"Откат не подтверждён: нужно --confirm {p['id']}")
    if reason := blocker(p):
        raise RuntimeError(reason)
    manifest = _find(p, stamp)
    _check_stash(p, manifest)
    result: dict = {}
    backups._with_mariadb(lambda: result.update(_undo(p, manifest, out)))
    return result


def _undo(p: dict, manifest: dict, out) -> dict:
    db, old, stamp = p["database"], _side(p, OLD), manifest["stamp"]
    src = backups.backup_dir(p) / manifest["backup"]
    out("Заливаю бэкап рядом и сверяю строки…")
    new = _create_side(p, NEW)
    try:
        backups._import({"database": new}, src)
        got = _counts(new)
        if got != manifest["before"]:
            wrong = sorted(t for t in set(got) | set(manifest["before"]) if got.get(t) != manifest["before"].get(t))
            raise RuntimeError(f"Бэкап {src.name} не сошёлся с записью вайпа по таблицам {', '.join(wrong[:10])}. Откат отменён, мир не тронут.")
        out("Бэкап нового мира before-restore…")
        safety = backups.make(p, "before-restore")["name"]
        _verify_dump(p, safety, _counts(db))
        current = _describe(world_files(p))
        folder = wipe_root(p) / f"{stamp}_undo_{_stamp()}"
        _write_manifest(folder, {"kind": "undo", "id": p["id"], "of": stamp, "safety": safety, "files": current})
        if reason := blocker(p):
            raise RuntimeError(reason)
        out("Возвращаю мир…")
        moved: list[tuple[Path, Path]] = []
        try:
            _stash(current, folder, moved)
            _create_side(p, OLD)
            _swap(db, new, old, set())
        except Exception:
            _unstash(moved)
            _drop_empty(old)
            raise
    finally:
        _drop_side(new)
    stash = wipe_root(p) / stamp / "files"
    for item in manifest["files"]:
        target = paths.HOME / item["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(stash / item["path"], target)  # копией: папка вайпа остаётся, откат можно повторить
        if _sha256(target) != item["sha256"]:
            raise RuntimeError(f"Файл вернулся с другой суммой: {target}")
    after = _counts(db)
    if after != manifest["before"]:
        wrong = sorted(t for t in set(after) | set(manifest["before"]) if after.get(t) != manifest["before"].get(t))
        raise RuntimeError(f"Сверка после отката не сошлась по таблицам {', '.join(wrong[:10])}. Новый мир — в {safety}.")
    _drop_side(old)  # новый мир есть в проверенном дампе before-restore
    backups._log(p, f"откат вайпа {stamp}: база {manifest['backup']}; новый мир — {safety}, файлы — {folder.name}")
    return {"stamp": stamp, "safety": safety, "folder": str(folder), "tables": len(after), "rows": sum(after.values()), "files": len(manifest["files"])}


# ---------- Команда ----------

def command(p: dict, confirm: str | None, undo_stamp: str | None) -> int:
    """python -m l2hub.cli wipe <id> [--confirm <id>] [--undo [время]]."""
    try:
        if undo_stamp is not None and confirm is None:
            print("\n".join(undo_plan(p, undo_stamp)))
        elif undo_stamp is not None:
            r = undo(p, undo_stamp, confirm)
            print(f"Откат готов: мир как перед вайпом {r['stamp']}, таблиц {r['tables']}, строк {r['rows']} (сошлось с записью вайпа), файлов {r['files']}.")
            print(f"Новый мир сохранён: база — {r['safety']}, файлы — {r['folder']}")
        elif confirm is None:
            print("\n".join(_render_preview(preview(p))))
        else:
            r = wipe(p, confirm)
            print(f"Вайп готов: {r['stamp']}, в новой базе строк {r['rows']}, файлов вне базы убрано {r['files']}.")
            print(f"Старый мир: база — {r['backup']}, файлы — {r['folder']}")
            print(f"Откат: python -m l2hub.cli wipe {p['id']} --undo {r['stamp']} --confirm {p['id']}")
    except (RuntimeError, OSError, subprocess.SubprocessError) as e:
        print(f"ОШИБКА: {e}")
        return 1
    return 0
