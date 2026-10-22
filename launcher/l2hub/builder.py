"""Сборка и настройка серверов Mobius на Python (шаг 2 релиза, docs/RELEASE.md) — то же, что scripts/db_init.ps1,
server_setup.ps1 и server_build.ps1, но без PowerShell, 7-Zip и git. Пишет в paths.HOME, патчи и модуль фантомов
берёт из paths.APP.

Патчи накладываются байт в байт, без преобразования переводов строк (как git apply с core.autocrlf=false вне
репозитория): патчи к CRLF-файлам Mobius и так хранятся в CRLF (AGENTS.md). Патч с LF-строками к CRLF-файлу здесь
не наложится, а не перепишет файл в LF, как git apply внутри репозитория.
"""

import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import uuid
import zipfile
from collections.abc import Callable
from datetime import datetime
from pathlib import Path

from . import catalog, paths, servers

Out = Callable[[str], None]


# ---------- Патчи ----------

HUNK_RE = re.compile(rb"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


class PatchError(RuntimeError):
    pass


class FilePatch:
    """Один файл из патча: старый и новый путь (None — /dev/null) и ханки (строка в старом, в новом, старые строки, новые)."""

    def __init__(self, old: str | None, new: str | None):
        self.old, self.new = old, new
        self.hunks: list[tuple[int, int, list[bytes], list[bytes]]] = []

    @property
    def path(self) -> str:
        return self.new or self.old


def _patch_path(raw: bytes) -> str | None:
    p = raw.split(b"\t", 1)[0].rstrip(b"\r\n").decode("utf-8")
    if p == "/dev/null":
        return None
    return p[2:] if p[:2] in ("a/", "b/") else p


def parse_patch(data: bytes) -> list[FilePatch]:
    lines = data.splitlines(keepends=True)
    out, i = [], 0
    while i < len(lines):
        if not (lines[i].startswith(b"--- ") and i + 1 < len(lines) and lines[i + 1].startswith(b"+++ ")):
            i += 1
            continue
        fp = FilePatch(_patch_path(lines[i][4:]), _patch_path(lines[i + 1][4:]))
        out.append(fp)
        i += 2
        while i < len(lines) and (m := HUNK_RE.match(lines[i])):
            old_n = int(m[2]) if m[2] is not None else 1
            new_n = int(m[4]) if m[4] is not None else 1
            old, new, last = [], [], None
            i += 1
            while i < len(lines) and (len(old) < old_n or len(new) < new_n or lines[i].startswith(b"\\")):
                ln = lines[i]
                tag, body = ln[:1], ln[1:]
                if ln in (b"\n", b"\r\n"):  # пустая строка контекста без пробела
                    tag, body = b" ", ln
                if tag == b"\\":  # «\ No newline at end of file» — у предыдущей строки нет перевода строки
                    for block in last or ():
                        block[-1] = block[-1].rstrip(b"\r\n")
                elif tag == b" ":
                    old.append(body)
                    new.append(body)
                    last = (old, new)
                elif tag == b"-":
                    old.append(body)
                    last = (old,)
                elif tag == b"+":
                    new.append(body)
                    last = (new,)
                else:
                    raise PatchError(f"непонятная строка в ханке {fp.path}: {ln[:60]!r}")
                i += 1
            fp.hunks.append((int(m[1]), int(m[3]), old, new))
    return out


def _find(lines: list[bytes], block: list[bytes], near: int) -> int:
    """Где в файле стоит блок: ближайшее к ожидаемой строке точное совпадение, -1 — нигде."""
    n = len(block)
    for d in range(len(lines) + 1):
        for pos in (near - d, near + d) if d else (near,):
            if 0 <= pos <= len(lines) - n and lines[pos:pos + n] == block:
                return pos
    return -1


def apply_file(content: bytes | None, fp: FilePatch, reverse: bool = False) -> bytes | None:
    """Наложить (или снять) патч одного файла; None на выходе — файл удалён. Не сошлось — PatchError."""
    creates, deletes = fp.old is None, fp.new is None
    if reverse:
        creates, deletes = deletes, creates
    if creates and content is not None:
        raise PatchError(f"{fp.path}: файл уже есть")
    if not creates and content is None:
        raise PatchError(f"{fp.path}: файла нет")
    lines = content.splitlines(keepends=True) if content else []
    delta = 0
    for old_start, new_start, old, new in fp.hunks:
        a, b, start = (new, old, new_start) if reverse else (old, new, old_start)
        # Пустой блок (только вставка): «-10,0» — вставить после 10-й строки.
        near = max(start - 1 + delta, 0) if a else min(start + delta, len(lines))
        pos = _find(lines, a, near) if a else near
        if pos < 0:
            raise PatchError(f"{fp.path}: ханк @@ -{start} не сошёлся")
        lines[pos:pos + len(a)] = b
        delta += len(b) - len(a)
    if deletes:
        if lines:
            raise PatchError(f"{fp.path}: после удаления остались строки")
        return None
    return b"".join(lines)


def apply_patch(root: Path, patch: Path, reverse: bool = False, check: bool = False) -> None:
    """Весь патч на дерево root: сначала всё в памяти, потом на диск — не сошёлся один файл, не тронут ни один."""
    results = {}
    for fp in parse_patch(patch.read_bytes()):
        src = root / fp.path  # переименований в наших патчах нет: старый и новый путь совпадают
        current = results[fp.path] if fp.path in results else (src.read_bytes() if src.is_file() else None)
        results[fp.path] = apply_file(current, fp, reverse)
    if check:
        return
    for rel, data in results.items():
        target = root / rel
        if data is None:
            target.unlink(missing_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)


def patch_files(patch: Path) -> set[str]:
    return {fp.path for fp in parse_patch(patch.read_bytes())}


def applied_patches(patches: list[Path], src: Path, work: Path) -> set[str]:
    """Какие патчи уже наложены — проверка стопкой, как в server_build.ps1: копия задетых файлов, патчи снимаются
    с конца, и каждый проверяется против состояния сразу после себя (поздний патч может менять строки раннего)."""
    tmp = work / f".patchcheck_{uuid.uuid4().hex}"
    try:
        for rel in set().union(*(patch_files(p) for p in patches)):
            if (src / rel).is_file():
                (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src / rel, tmp / rel)
        tmp.mkdir(parents=True, exist_ok=True)
        applied = set()
        for patch in reversed(patches):
            try:
                apply_patch(tmp, patch, reverse=True)
                applied.add(patch.name)
            except PatchError:
                pass
        return applied
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# ---------- Модуль фантомов ----------

def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()  # как Get-FileHash: манифест общий со scripts/


def copy_module(cid: str, src: Path, base: Path, out: Out) -> list[str]:
    """phantoms/core и phantoms/<id> (java/, dist/) — в исходники, байт в байт. Файл в исходниках правили мимо модуля
    (не совпадает ни с модулем, ни с прошлой копией из .module_manifest.json) — его копия уходит в .module_overwritten."""
    module = paths.PHANTOMS / cid
    if not module.is_dir():
        return []
    manifest_path = base / ".module_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig")) if manifest_path.is_file() else {}
    stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    new_manifest, keys = {}, []
    for root in (paths.PHANTOMS / "core", module):
        for top in ("java", "dist"):
            for f in sorted((root / top).rglob("*")) if (root / top).is_dir() else ():
                if not f.is_file():
                    continue
                rel = f.relative_to(root)
                key = rel.as_posix()
                to = src / rel
                digest = _sha(f)
                if to.is_file():
                    tree = _sha(to)
                    if tree != digest and key in manifest and tree != manifest[key]:
                        keep = base / ".module_overwritten" / stamp / rel
                        keep.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(to, keep)
                        out(f"ВНИМАНИЕ: {key} правили в исходниках, а не в phantoms\\ — правка перезаписана модулем, копия: {keep}")
                to.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, to)
                keys.append(key)
                new_manifest[key] = digest
    manifest_path.write_text(json.dumps(new_manifest, indent=4), encoding="utf-8")
    out(f"Модуль фантомов в исходниках: файлов {len(keys)}")
    return keys


# ---------- Исходники Mobius на закреплённом коммите ----------

GITLAB_COMMITS = "https://gitlab.com/api/v4/projects/MobiusDevelopment%2FL2J_Mobius/repository/commits"


def pins() -> dict:
    return json.loads((paths.PACKAGE / "mobius_pins.json").read_text(encoding="utf-8"))


def latest_commit(build: str) -> dict:
    """Последний коммит master, задевший папку сборки, — для хроник вне плана: у них закреплённого нет, этот
    закрепляется в их профиле при установке ("source")."""
    import urllib.parse  # noqa: PLC0415 — сеть нужна только мастеру
    import urllib.request  # noqa: PLC0415

    url = f"{GITLAB_COMMITS}?ref_name=master&path={urllib.parse.quote(build)}&per_page=1"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "L2Hub"}), timeout=30) as r:
        data = json.loads(r.read().decode("utf-8"))
    if not data:
        raise RuntimeError(f"В L2J Mobius нет папки {build}")
    return {"build": build, "commit": data[0]["id"], "date": data[0]["committed_date"][:10]}


def commits_since(build: str, date: str) -> int:
    """Сколько коммитов master задело папку сборки после даты закреплённого (до 100: дальше — «100+»)."""
    import urllib.parse  # noqa: PLC0415
    import urllib.request  # noqa: PLC0415

    url = f"{GITLAB_COMMITS}?ref_name=master&path={urllib.parse.quote(build)}&since={date}T23:59:59Z&per_page=100"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "L2Hub"}), timeout=30) as r:
        return len(json.loads(r.read().decode("utf-8")))


def source_pin(cid: str, build: str | None = None) -> dict:
    """Чем собирать хронику: закреплённый коммит из mobius_pins.json, иначе — последний коммит её папки в master."""
    return pins()["chronicles"].get(cid) or latest_commit(build)


def source_archive(pin: dict) -> tuple[str, Path]:
    """Адрес архива GitLab с одной папкой хроники на коммите и куда его класть (downloads/)."""
    url = f"{pins()['repo']}/-/archive/{pin['commit']}/L2J_Mobius-{pin['commit']}.zip?path={pin['build']}"
    return url, paths.HOME / "downloads" / f"{pin['build']}_{pin['commit'][:8]}.zip"


def fetch_sources(cid: str, out: Out = print, progress=None, pin: dict | None = None) -> Path:
    """Скачать исходники хроники (если ещё нет) и разложить в servers/<id>/<сборка>. SHA в комментарии zip
    (его пишет git archive) сверяется с ожидаемым: не тот коммит — ошибка, ничего не распаковано."""
    import urllib.request  # noqa: PLC0415 — сеть нужна только мастеру

    pin = pin or pins()["chronicles"][cid]
    url, archive = source_archive(pin)
    dst = paths.SERVERS / cid / pin["build"]
    if dst.exists():
        raise RuntimeError(f"Исходники уже разложены: {dst}")
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        part = archive.with_suffix(".part")
        out(f"Скачиваю исходники {pin['build']} ({pin['commit'][:8]}, {pin['date']})…")
        req = urllib.request.Request(url, headers={"User-Agent": "L2Hub"})
        with urllib.request.urlopen(req, timeout=60) as r, part.open("wb") as f:
            total, done = int(r.headers.get("Content-Length") or 0), 0
            while chunk := r.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if progress:
                    progress(done, total)
        part.replace(archive)
    with zipfile.ZipFile(archive) as z:
        commit = z.comment.decode("ascii", "replace").strip()
        if commit != pin["commit"]:
            raise RuntimeError(f"В {archive.name} коммит {commit or '(нет)'}, а ждали {pin['commit']}")
        prefix = next((n[: -len("build.xml")] for n in z.namelist() if n.endswith(f"{pin['build']}/build.xml")), None)
        if prefix is None:
            raise RuntimeError(f"В {archive.name} нет {pin['build']}/build.xml")
        for name in z.namelist():
            if name.startswith(prefix) and not name.endswith("/"):
                to = dst / name[len(prefix):]
                to.parent.mkdir(parents=True, exist_ok=True)
                to.write_bytes(z.read(name))
    out(f"Исходники на месте: {dst}")
    return dst


# ---------- Сборка ----------

def source_dir(cid: str) -> Path:
    base = paths.SERVERS / cid
    for d in sorted(base.iterdir()) if base.is_dir() else ():
        if d.is_dir() and (d / "build.xml").is_file():
            return d
    raise RuntimeError(f"Нет исходников с build.xml в {base}")


def chronicle_patches(cid: str) -> list[Path]:
    """Порядок как в server_build.ps1: моды хроники из patches/<id> по имени, затем врезки модуля фантомов."""
    d = paths.PATCHES / cid
    patches = sorted(d.glob("*.patch"), key=lambda f: f.name) if d.is_dir() else []
    glue = paths.PHANTOMS / cid / "glue.patch"
    return patches + ([glue] if glue.is_file() else [])


def ant(src: Path, log: Path) -> None:
    env = {**os.environ, "JAVA_HOME": str(paths.JAVA.parents[1])}
    with log.open("wb") as f:
        r = subprocess.run([str(paths.RUNTIME / "ant" / "bin" / "ant.bat"), "-f", str(src / "build.xml")], cwd=src, env=env,
                           stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
    if r.returncode:
        raise RuntimeError(f"Сборка упала (код {r.returncode}), смотри {log}")


def server_build(p: dict, out: Out = print) -> None:
    """Исходники + модуль фантомов + патчи → Ant → jar-ы и данные в развёрнутый сервер (или распаковка целиком)."""
    cid = p["id"]
    base = paths.SERVERS / cid
    src = source_dir(cid)
    module = copy_module(cid, src, base, out)

    patches = chronicle_patches(cid)
    if patches:
        applied = applied_patches(patches, src, base)
        for patch in patches:
            if patch.name in applied:
                out(f"{patch.name}: уже наложен")
                continue
            try:
                apply_patch(src, patch)
            except PatchError as e:
                raise RuntimeError(f"Патч не наложился: {patch.name} — {e}") from None
            out(f"{patch.name}: наложен")

    ant(src, base / "build.log")
    archive = base / "build" / f"{src.name}.zip"
    srv = base / "server"
    if not srv.is_dir():
        with zipfile.ZipFile(archive) as z:
            z.extractall(srv)
        out(f"Сборка готова и распакована в {srv}; дальше — настройка (server_setup)")
        return

    with zipfile.ZipFile(archive) as z:
        jars = [n for n in z.namelist() if n.startswith("libs/") and n.endswith(".jar")]
        for name in jars:
            (srv / "libs" / Path(name).name).write_bytes(z.read(name))
    out(f"Сборка готова, jar-ы обновлены в {srv / 'libs'} ({len(jars)})")

    # Данные из патчей и модуля Ant кладёт только в архив — копируем в развёрнутый сервер. Конфиг: нового в сервере
    # нет — копируется; есть и отличается — там правки автора, только напоминание.
    touched = {rel[5:] for patch in patches for rel in patch_files(patch) if rel.startswith("dist/")}
    touched |= {key[5:] for key in module if key.startswith("dist/")}
    for rel in sorted(touched):
        frm, to = src / "dist" / rel, srv / rel
        if not frm.is_file():
            continue
        if re.match(r"^(game|login)/data/", rel):
            if to.is_file() and _sha(to) == _sha(frm):
                continue
            to.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(frm, to)
            out(f"Данные обновлены: {rel}")
        elif re.match(r"^(game|login)/config/", rel):
            if not to.is_file():
                to.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(frm, to)
                out(f"Новый конфиг скопирован в сервер: {rel}")
            elif _sha(to) != _sha(frm):
                out(f"Конфиг в сервере отличается от сборки (там правки автора) — новые ключи перенести вручную: {rel}")


# ---------- База ----------

PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"


def _edit_lines(path: Path, pattern: str, repl) -> None:
    """Регуляркой по строкам, с сохранением кодировки байтов и переводов строк файла."""
    data = path.read_bytes().decode("utf-8", errors="surrogateescape")
    path.write_bytes(re.sub(pattern, repl, data, flags=re.MULTILINE).encode("utf-8", errors="surrogateescape"))


def db_init(out: Out = print) -> None:
    """Однократно: данные MariaDB в data/mariadb, пароль root — случайный, в data/client.cnf; слушает только 127.0.0.1."""
    if paths.DB_DIR.exists():
        raise RuntimeError(f"Уже инициализировано: {paths.DB_DIR}")
    paths.DATA.mkdir(parents=True, exist_ok=True)
    password = "".join(secrets.choice(PASSWORD_CHARS) for _ in range(24))
    r = subprocess.run([str(paths.MARIA_BIN / "mariadb-install-db.exe"), f"--datadir={paths.DB_DIR}", f"--password={password}", "--port=3306"],
                       capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
    if r.returncode:
        raise RuntimeError(f"mariadb-install-db завершился с кодом {r.returncode}: {r.stderr.decode('utf-8', 'replace')[-500:]}")
    _edit_lines(paths.DB_DIR / "my.ini", r"^\[mysqld\][ \t]*(\r?\n)",
                r"[mysqld]\1bind-address=127.0.0.1\1character-set-server=utf8mb4\1collation-server=utf8mb4_unicode_ci\1")
    paths.CLIENT_CNF.write_text(f"[client]\nhost=127.0.0.1\nport=3306\nuser=root\npassword={password}\n", encoding="utf-8")
    out(f"Готово: {paths.DB_DIR}")


def db_password() -> str:
    for line in paths.CLIENT_CNF.read_text(encoding="utf-8-sig").splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise RuntimeError(f"Нет пароля в {paths.CLIENT_CNF}")


def _mysql(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([str(paths.MARIA_BIN / "mariadb.exe"), f"--defaults-extra-file={paths.CLIENT_CNF}", *args],
                          capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)


IPCONFIG_LOCAL = """<?xml version="1.0" encoding="UTF-8"?>
<!-- L2Hub, локальный режим: клиентам отдаётся только 127.0.0.1. Вторая подсеть — тоже на 127.0.0.1: гейм C1 (Mobius
     C1_HarbingersOfWar, AuthRequest) берёт третий адрес списка и без неё падает при регистрации на логине. -->
<gameserver address="127.0.0.1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../data/xsd/ipconfig.xsd">
\t<define subnet="127.0.0.0/8" address="127.0.0.1" />
\t<define subnet="169.254.0.0/16" address="127.0.0.1" />
</gameserver>
"""


# ---------- Своя сборка-комбайн (bundles.json) ----------

def detect_bundle(folder: str | Path) -> tuple[str, Path] | None:
    """Вид сборки-комбайна и её папка server. Указать можно саму сборку, её server или папку раздачи на уровень выше
    (у La2offline — «La2offline 4\\server» рядом с архивами и La2system): ищем файлы detect каждого вида."""
    base = Path(folder)
    subs = [x for d in sorted(base.iterdir()) if d.is_dir() for x in (d, d / "server")] if base.is_dir() else []
    for root in (base, base / "server", *subs):
        for kid, k in servers.bundle_kinds().items():
            if all((root / f).is_file() for f in k["detect"]):
                return kid, root
    return None


def jar_java(jar: Path) -> int | None:
    """Какая Java нужна jar-у: версия байткода первого класса (major 52 — Java 8, 65 — Java 21, 71 — Java 27)."""
    try:
        with zipfile.ZipFile(jar) as z:
            name = next((n for n in z.namelist() if n.endswith(".class")), None)
            if not name:
                return None
            head = z.read(name)[:8]
    except (OSError, zipfile.BadZipFile):
        return None
    return int.from_bytes(head[6:8], "big") - 44 if head[:4] == b"\xca\xfe\xba\xbe" else None


def runtime_java() -> int | None:
    """Major-версия нашей JDK из runtime/jdk-*/release (JAVA_VERSION="27")."""
    release = paths.JAVA.parents[1] / "release"
    m = re.search(r'^JAVA_VERSION="(\d+)', release.read_text(encoding="utf-8", errors="replace"), re.MULTILINE) if release.is_file() else None
    m = m or re.search(r"(\d+)$", paths.JAVA.parents[1].name)  # ещё не скачана — та, что поставит мастер (jdk-27)
    return int(m[1]) if m else None


def _prop(path: Path, key: str) -> str | None:
    m = re.search(rf"^\s*{re.escape(key)}\s*=[ \t]*(.*?)\s*$", path.read_text(encoding="utf-8", errors="replace"), re.MULTILINE)
    return m[1] if m else None


def import_bundle(cid: str, folder: str | Path, out: Out = print) -> dict:
    """Копия server сборки в servers/<id>/server (оригинал не трогаем), локальный режим по описанию вида (у La2offline —
    логин и гейм только на 127.0.0.1, телнет выключен, как у автора 2026-10-18) и профиль: имя базы и порты — из
    конфигов самой сборки (ключи read вида), остальное — из вида."""
    found = detect_bundle(folder)
    if not found:
        raise RuntimeError(f"В {folder} нет сборки, которую L2Hub знает (bundles.json)")
    kid, src = found
    kind = servers.bundle_kinds()[kid]
    dst = paths.SERVERS / cid / "server"
    if dst.exists():
        raise RuntimeError(f"Сервер уже есть: {dst}")
    out(f"Копирую сервер сборки ({kind['title']}): файлов {sum(1 for f in src.rglob('*') if f.is_file())}…")
    shutil.copytree(src, dst)
    for old, new in kind.get("rename", {}).items():
        # Части у L2Hub — папки game и login (у Lisvus гейм — gameserver): пути внутри части относительные, rename их не ломает.
        if (dst / old).is_dir() and not (dst / new).exists():
            (dst / old).rename(dst / new)
    if kind.get("runtime") == "ours" and kind.get("setup") != "mobius":
        # Вид на нашей Java и MariaDB по правилам (Lisvus): своя база l2hub_<id>, настройка — setup_rules.
        proto = _prop(dst / kind["protocol"][0], kind["protocol"][1]) if kind.get("protocol") else None
        out(f"Сборка скопирована: {dst}")
        return {"id": cid, "type": "bundle", "bundle": kid, "build": kind["title"].split(" — ")[0], "server": f"servers/{cid}/server",
                "database": f"l2hub_{cid}", "ports": {"db": 3306, **kind["ports"]},
                "client": {"path": "", "exe": kind.get("client", {}).get("exe", "system\\L2.exe"), "system": "",
                           "protocol": int(proto) if proto and proto.isdigit() else None},
                "source": {"imported": str(src), "kind": kid, "java": jar_java(src / kind["jar"]) if kind.get("jar") else None}}
    if kind.get("runtime") == "ours":
        # Сборка на нашей Java и MariaDB (семейство Mobius): дальше — как свежая сборка из исходников (server_setup).
        m = re.search(r"^\s*AllowedProtocolRevisions\s*=\s*(\d+)", (dst / "game" / "config" / "Server.ini").read_text(encoding="utf-8", errors="replace"), re.MULTILINE)
        out(f"Сборка скопирована: {dst}")
        return {"id": cid, "type": "mobius", "build": src.parent.name if src.name.lower() == "server" else src.name,
                "server": f"servers/{cid}/server", "database": database_name(cid), "ports": {"db": 3306, "login": 2106, "game": 7777},
                "client": {"path": "", "exe": "system\\L2.exe", "system": "", "protocol": int(m[1]) if m else None},
                "source": {"imported": str(src), "kind": kid, "java": jar_java(src / kind["jar"]) if kind.get("jar") else None}}
    for rel, key, value in kind.get("local", []):
        if (dst / rel).is_file():
            _edit_lines(dst / rel, rf"^(\s*{key}\s*=)[^\r\n]*", rf"\g<1> {value}")
    read = {k: _prop(dst / f, key) for k, (f, key) in kind.get("read", {}).items() if (dst / f).is_file()}
    url = re.search(r"//[^/:]+(?::(\d+))?/([^?\s]+)", read.get("url") or "")
    ports = {"db": int(url[1]) if url and url[1] else kind["db"]["port"],
             "login": int(read["login"]) if (read.get("login") or "").isdigit() else kind["ports"]["login"],
             "game": int(read["game"]) if (read.get("game") or "").isdigit() else kind["ports"]["game"]}
    out(f"Сборка подключена: {dst}")
    return {"id": cid, "type": "bundle", "bundle": kid, "build": kind["title"].split(" — ")[0], "server": f"servers/{cid}/server",
            "database": url[2] if url else cid, "ports": ports, "accessLevels": kind.get("accessLevels", {}),
            "client": {"path": "", "exe": kind.get("client", {}).get("exe", "system\\L2.exe"), "system": ""},
            "source": {"imported": str(src), "kind": kid}}


def set_key(path: Path, key: str, value: str) -> None:
    """Значение ключа «Ключ = значение» в конфиге — с сохранением кодировки и переводов строк файла."""
    _edit_lines(path, rf"^(\s*{re.escape(key)}\s*=)[^\r\n]*", lambda m: f"{m[1]} {value}")


def setup_rules(p: dict, out: Out = print) -> None:
    """Настройка сборки на нашей MariaDB по описанию вида (bundles.json, ключ database): база (только если её ещё
    нет — часть SQL сборок делает DROP TABLE), все *.sql из папки sql по имени, адрес базы, логин и пароль в конфигах
    частей, ключи локального режима (local)."""
    kind = servers.bundle(p)
    spec = kind["database"]
    srv = catalog.server_dir(p)
    db = p["database"]
    if not servers.port_open(3306):
        raise RuntimeError("MariaDB не запущена")
    if _mysql("-N", "-e", f"SHOW DATABASES LIKE '{db}'").stdout.strip():
        out(f"База {db} уже есть — заливку пропускаю")
    else:
        r = _mysql("-e", f"CREATE DATABASE `{db}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        if r.returncode:
            raise RuntimeError(f"Не удалось создать базу {db}: {r.stderr.decode('utf-8', 'replace')}")
        files = sorted((srv / spec["sql"]).glob("*.sql"), key=lambda f: f.name)
        for sql in files:
            r = _mysql(db, "-e", f"source {sql.as_posix()}")
            if r.returncode:
                raise RuntimeError(f"Ошибка в {spec['sql']}/{sql.name}: {r.stderr.decode('utf-8', 'replace')[-500:]}")
        out(f"База {db}: залито файлов — {len(files)}")
    keys = spec["keys"]
    url, password = spec["url"].replace("{db}", db), db_password()
    for rel in spec["files"]:
        cfg = srv / rel
        _edit_lines(cfg, rf"^(\s*{keys['url']}\s*=)[^\r\n]*", lambda _m: f"{_m[1]} {url}")
        _edit_lines(cfg, rf"^(\s*{keys['user']}\s*=)[^\r\n]*", r"\g<1> root")
        _edit_lines(cfg, rf"^(\s*{keys['password']}\s*=)[^\r\n]*", lambda _m: f"{_m[1]} {password}")
    for rel, key, value in kind.get("local", []):
        if (srv / rel).is_file():
            _edit_lines(srv / rel, rf"^(\s*{key}\s*=)[^\r\n]*", rf"\g<1> {value}")
    out(f"Сборка {p['id']} настроена: база {db}, локальный режим")


def database_name(cid: str) -> str:
    """Имя базы из Database.ini логина развёрнутого сервера."""
    ini = paths.SERVERS / cid / "server" / "login" / "config" / "Database.ini"
    m = re.search(r"^\s*URL\s*=\s*jdbc:mysql://[^/]+/([^?\s]+)", ini.read_text(encoding="utf-8", errors="replace"), re.MULTILINE)
    if not m:
        raise RuntimeError(f"Не нашёл имя базы в {ini}")
    return m[1]


def rename_database(cid: str, new: str, out: Out = print) -> None:
    """Своё имя базы в Database.ini логина и гейма: у разных сборок Mobius оно бывает одинаковым (Classic, Essence),
    а заливать SQL в чужую базу нельзя — server_setup её пропустит как «уже есть»."""
    old = database_name(cid)
    for part in ("login", "game"):
        _edit_lines(paths.SERVERS / cid / "server" / part / "config" / "Database.ini",
                    rf"^(\s*URL\s*=\s*jdbc:mysql://[^/]+/){re.escape(old)}(?=[?\s]|$)", rf"\g<1>{new}")
    out(f"База {old} уже занята другой хроникой — у этой своя: {new}")


def generic_profile(cid: str, build: str, pin: dict) -> dict:
    """Профиль хроники Mobius вне плана — без шаблона: из развёрнутого сервера. Клиент указывается после установки;
    протокол — первый из AllowedProtocolRevisions гейма (его ждёт сервер)."""
    srv = paths.SERVERS / cid / "server"
    text = (srv / "game" / "config" / "Server.ini").read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^\s*AllowedProtocolRevisions\s*=\s*(\d+)", text, re.MULTILINE)
    return {"id": cid, "type": "mobius", "build": build, "server": f"servers/{cid}/server", "database": database_name(cid),
            "ports": {"db": 3306, "login": 2106, "game": 7777},
            "client": {"path": "", "exe": "system\\L2.exe", "system": "", "protocol": int(m[1]) if m else None},
            "source": {"commit": pin["commit"], "date": pin["date"], "mods": False}}


def server_setup(p: dict, out: Out = print) -> None:
    """База и SQL установщика (только если базы ещё нет), доступ к базе, локальный режим и запуск без GUI.
    Повторно — безопасно: существующая база не трогается, конфиги перезаписываются."""
    srv = paths.SERVERS / p["id"] / "server"
    if not srv.is_dir():
        raise RuntimeError(f"Нет распакованной сборки: {srv}")
    if not servers.port_open(3306):
        raise RuntimeError("MariaDB не запущена")
    db_configs = [srv / "login" / "config" / "Database.ini", srv / "game" / "config" / "Database.ini"]
    m = re.search(r"^\s*URL\s*=\s*jdbc:mysql://[^/]+/([^?\s]+)", db_configs[0].read_text(encoding="utf-8", errors="replace"), re.MULTILINE)
    if not m:
        raise RuntimeError(f"Не нашёл имя базы в {db_configs[0]}")
    db = m[1]
    if _mysql("-N", "-e", f"SHOW DATABASES LIKE '{db}'").stdout.strip():
        out(f"База {db} уже есть — заливку пропускаю")  # часть SQL установщика делает DROP TABLE
    else:
        r = _mysql("-e", f"CREATE DATABASE `{db}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        if r.returncode:
            raise RuntimeError(f"Не удалось создать базу {db}: {r.stderr.decode('utf-8', 'replace')}")
        count = 0
        for part in ("login", "game"):
            for sql in sorted((srv / "db_installer" / "sql" / part).glob("*.sql"), key=lambda f: f.name):
                r = _mysql(db, "-e", f"source {sql.as_posix()}")
                if r.returncode:
                    raise RuntimeError(f"Ошибка в {part}\\{sql.name}: {r.stderr.decode('utf-8', 'replace')[-500:]}")
                count += 1
        out(f"База {db}: залито файлов — {count}")

    # Доступ к базе: 127.0.0.1 вместо localhost (MariaDB слушает только IPv4) и пароль из data/client.cnf.
    password = db_password()
    for cfg in db_configs:
        _edit_lines(cfg, r"^(\s*URL\s*=\s*jdbc:mysql://)localhost/", r"\g<1>127.0.0.1/")
        _edit_lines(cfg, r"^(\s*Password\s*=)[^\r\n]*", lambda _m: _m[1] + " " + password)
    # Локальный режим и запуск без GUI.
    for part, file, key, value in (("login", "Server.ini", "LoginserverHostname", "127.0.0.1"),
                                   ("game", "Server.ini", "GameserverHostname", "127.0.0.1"),
                                   ("login", "Interface.ini", "EnableGUI", "False"),
                                   ("game", "Interface.ini", "EnableGUI", "False")):
        _edit_lines(srv / part / "config" / file, rf"^(\s*{key}\s*=)[^\r\n]*", rf"\g<1> {value}")
    # Адреса для клиентов: с ipconfig.xml сервер не ходит за внешним IP на checkip.amazonaws.com.
    (srv / "game" / "config" / "ipconfig.xml").write_text(IPCONFIG_LOCAL, encoding="utf-8")
    out(f"Сборка {p['id']} настроена")
