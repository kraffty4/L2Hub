"""Пересобирает phantoms/<id>/glue.patch — врезки модуля фантомов в файлы Mobius — из рабочего дерева исходников.

Врезки правятся прямо в servers/<id>/<сборка>/ (файлы Mobius), потом этот инструмент считает патч:
  база  = чистые исходники Mobius (phantoms/<id>/base.json: архив в downloads/) + моды patches/<id>/*.patch;
  glue  = база -> рабочее дерево по файлам Mobius, которые ещё отличаются;
и проверяет, что с нуля (база + модуль phantoms/core и phantoms/<id> + glue) получается то же дерево байт в байт.

Код самого модуля в дереве не правят — его источник phantoms/; файл модуля, который в дереве разошёлся с phantoms/,
инструмент называет и останавливается. Новый файл вне модуля и вне патчей — тоже ошибка: ему место в phantoms/
или в патче мода.

Использование:
  python -X utf8 tools/phantoms_glue.py <id>            пересобрать glue.patch
  python -X utf8 tools/phantoms_glue.py <id> --check    только проверить, что glue.patch совпадает с деревом
"""
import json
import os
import shutil
import stat
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "servers" / "_glue"  # servers/ в .gitignore; git apply изнутри репозитория — с его .gitattributes


def rm(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path, onexc=lambda f, x, e: (os.chmod(x, stat.S_IWRITE), f(x)))


def git(*args, cwd=REPO) -> bytes:
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)}: {r.stderr.decode('utf-8', 'replace')}")
    return r.stdout


def files(root: Path) -> dict[str, Path]:
    """Файлы дерева без служебных папок (.patchcheck_*, .module_*)."""
    out = {}
    for f in root.rglob("*"):
        rel = f.relative_to(root)
        if f.is_file() and not any(p.startswith(".") for p in rel.parts):
            out[rel.as_posix()] = f
    return out


def module_files(cid: str) -> dict[str, Path]:
    out = {}
    for top in (REPO / "phantoms" / "core", REPO / "phantoms" / cid):
        for sub in ("java", "dist"):
            if (top / sub).exists():
                for f in (top / sub).rglob("*"):
                    if f.is_file():
                        out[f.relative_to(top).as_posix()] = f
    return out


def extract_base(cid: str, build: str, archive: Path, dst: Path) -> None:
    rm(dst)
    with zipfile.ZipFile(archive) as z:
        prefix = next((n[: -len("build.xml")] for n in z.namelist() if n.endswith(f"{build}/build.xml")), None)
        if prefix is None:
            raise SystemExit(f"в {archive} нет {build}/build.xml")
        for n in z.namelist():
            if n.startswith(prefix) and not n.endswith("/"):
                out = dst / n[len(prefix):]
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(z.read(n))


def apply(tree: Path, patches) -> None:
    rel = tree.relative_to(REPO).as_posix()
    for p in patches:
        r = subprocess.run(["git", "apply", f"--directory={rel}", str(p)], cwd=REPO, capture_output=True)
        if r.returncode != 0:
            raise SystemExit(f"не наложился {p.name}: {r.stderr.decode('utf-8', 'replace')}")


def make_patch(base: dict[str, bytes], target: dict[str, bytes]) -> bytes:
    """Патч base -> target во временном git без преобразования строк: CRLF-файлы дают CRLF-строки в патче."""
    g = WORK / "_git"
    rm(g)
    g.mkdir(parents=True)
    git("init", "-q", cwd=g)
    git("config", "core.autocrlf", "false", cwd=g)
    git("config", "core.safecrlf", "false", cwd=g)
    for rel, data in base.items():
        (g / rel).parent.mkdir(parents=True, exist_ok=True)
        (g / rel).write_bytes(data)
    git("add", "-A", cwd=g)
    git("-c", "user.name=l2hub", "-c", "user.email=l2hub@localhost", "commit", "-q", "--allow-empty", "-m", "base", cwd=g)
    for rel, data in target.items():
        (g / rel).parent.mkdir(parents=True, exist_ok=True)
        (g / rel).write_bytes(data)
    git("add", "-A", cwd=g)
    out = git("diff", "--cached", "--no-color", "--no-renames", "--full-index", "-U3", cwd=g)
    rm(g)
    return out


def main() -> int:
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and sys.argv[2] != "--check"):
        sys.exit(__doc__)
    cid, check_only = sys.argv[1], len(sys.argv) == 3
    meta = json.loads((REPO / "phantoms" / cid / "base.json").read_text(encoding="utf-8"))
    tree = REPO / "servers" / cid / meta["build"]
    glue_path = REPO / "phantoms" / cid / "glue.patch"
    mods = sorted((REPO / "patches" / cid).glob("*.patch"))
    module = module_files(cid)
    cur = files(tree)

    wrong = [rel for rel, f in module.items() if rel in cur and cur[rel].read_bytes() != f.read_bytes()]
    if wrong:
        print("Файлы модуля в дереве разошлись с phantoms/ — их правят в phantoms/, а не в servers/:")
        print("\n".join(f"  {r}" for r in wrong))
        return 1

    base = WORK / cid / "base"
    extract_base(cid, meta["build"], REPO / meta["archive"], base)
    apply(base, mods)
    base_files = files(base)
    stray = sorted(rel for rel in cur if rel not in module and rel not in base_files)
    if stray:
        print("Новые файлы вне модуля и патчей — перенеси в phantoms/ или в патч мода:")
        print("\n".join(f"  {r}" for r in stray))
        return 1
    glue_files = sorted(rel for rel in cur if rel not in module and base_files[rel].read_bytes() != cur[rel].read_bytes())
    glue = make_patch({r: base_files[r].read_bytes() for r in glue_files}, {r: cur[r].read_bytes() for r in glue_files})

    if check_only:
        same = glue_path.exists() and glue_path.read_bytes() == glue
        print(("glue.patch совпадает с деревом" if same else "glue.patch НЕ совпадает с деревом") + f": файлов Mobius {len(glue_files)}")
        rm(WORK / cid)
        return 0 if same else 1

    glue_path.write_bytes(glue)
    print(f"glue.patch: файлов Mobius {len(glue_files)}, {len(glue)} байт")

    # Проверка с нуля: база + моды + модуль + glue == дерево
    t = WORK / cid / "check"
    extract_base(cid, meta["build"], REPO / meta["archive"], t)
    apply(t, mods)
    for rel, f in module.items():
        (t / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, t / rel)
    apply(t, [glue_path])
    got = files(t)
    diff = sorted(r for r in cur if r not in got or got[r].read_bytes() != cur[r].read_bytes())
    extra = sorted(r for r in got if r not in cur)
    rm(WORK / cid)
    if diff or extra:
        print(f"ПРОВЕРКА НЕ ПРОШЛА: отличий {len(diff)}, лишних {len(extra)}")
        print("\n".join(f"  {r}" for r in (diff + extra)[:20]))
        return 1
    print("Проверка: с нуля получается то же дерево байт в байт")
    return 0


if __name__ == "__main__":
    sys.exit(main())
