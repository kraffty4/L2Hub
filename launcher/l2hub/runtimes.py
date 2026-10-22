"""Рантаймы серверов Mobius — Java, MariaDB, Ant: скачать официальный ZIP, сверить контрольную сумму, разложить
в paths.RUNTIME без верхней папки архива (docs/RELEASE.md, мастер первого запуска). Список и суммы — runtimes.json."""

import hashlib
import json
import shutil
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

from . import paths


def catalog() -> dict:
    data = json.loads((paths.PACKAGE / "runtimes.json").read_text(encoding="utf-8"))
    return {k: v for k, v in data.items() if not k.startswith("_")}


def status(kind: str = "mobius") -> list[dict]:
    """Что из рантаймов на месте для серверов вида kind ("mobius", "l2solo" — поле only в runtimes.json, без него —
    mobius): id, название, размер скачивания, стоит ли."""
    return [{"id": k, "title": r["title"], "size": r["size"], "installed": (paths.RUNTIME / r["dir"] / r["check"]).is_file()}
            for k, r in catalog().items() if r.get("only", "mobius") == kind]


def _digest(path: Path, algo: str) -> str:
    h = hashlib.new(algo)
    with path.open("rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def install(rid: str, out=print, progress=None) -> None:
    """Скачать (если архива ещё нет или он битый), сверить сумму и распаковать. Уже стоит — ничего не делает."""
    r = catalog()[rid]
    target = paths.RUNTIME / r["dir"]
    if (target / r["check"]).is_file():
        out(f"{r['title']}: уже стоит")
        return
    algo = "sha512" if "sha512" in r else "sha256"
    archive = paths.HOME / "downloads" / urllib.parse.unquote(r["url"].rsplit("/", 1)[1])
    if not archive.is_file() or _digest(archive, algo) != r[algo]:
        archive.parent.mkdir(parents=True, exist_ok=True)
        part = archive.with_name(archive.name + ".part")
        out(f"Скачиваю {r['title']} — {r['size'] / 2**20:.0f} МБ…")
        req = urllib.request.Request(r["url"], headers={"User-Agent": "L2Hub"})
        with urllib.request.urlopen(req, timeout=60) as resp, part.open("wb") as f:
            done = 0
            while chunk := resp.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if progress:
                    progress(done, r["size"])
        if _digest(part, algo) != r[algo]:
            part.unlink(missing_ok=True)
            raise RuntimeError(f"{r['title']}: контрольная сумма не сошлась — архив битый или подменён, скачай заново")
        part.replace(archive)
    out(f"Распаковываю {r['title']}…")
    paths.RUNTIME.mkdir(parents=True, exist_ok=True)
    tmp = paths.RUNTIME / f".{r['dir']}.unpack"
    shutil.rmtree(tmp, ignore_errors=True)
    with zipfile.ZipFile(archive) as z:
        z.extractall(tmp)
    # В архивах одна верхняя папка (jdk-27+35, mariadb-13.0.2-winx64, apache-ant-1.10.18) — её содержимое и есть рантайм.
    tops = list(tmp.iterdir())
    root = tops[0] if len(tops) == 1 and tops[0].is_dir() else tmp
    if not (root / r["check"]).is_file():
        shutil.rmtree(tmp, ignore_errors=True)
        raise RuntimeError(f"{r['title']}: в архиве нет {r['check']}")
    if target.exists():
        raise RuntimeError(f"{target} уже есть, но без {r['check']} — разберись руками, затирать не буду")
    root.replace(target)
    shutil.rmtree(tmp, ignore_errors=True)
    out(f"{r['title']}: готово")
