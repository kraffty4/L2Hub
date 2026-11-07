"""Пересобирает phantoms/<id>/glue.patch — врезки модуля фантомов в файлы Mobius — из рабочего дерева исходников.

Врезки правятся прямо в servers/<id>/<сборка>/ (файлы Mobius), потом этот инструмент считает патч. Порядок — как у
сборщика (l2hub/builder.py: copy_module, затем моды, затем glue.patch):
  база  = чистые исходники Mobius (phantoms/<id>/base.json: архив в downloads/) + модуль phantoms/core и phantoms/<id>
          + моды patches/<id>/*.patch (моды могут править и файлы модуля, как 0512 Эпилога);
  glue  = база -> рабочее дерево по файлам Mobius, которые ещё отличаются;
и проверяет, что с нуля (база + glue) получается то же дерево байт в байт.

Код самого модуля в дереве не правят — его источник phantoms/ (и моды хроники поверх него); файл модуля, который
в дереве разошёлся с модулем после модов, инструмент называет и останавливается. Новый файл вне модуля и вне патчей —
тоже ошибка: ему место в phantoms/ или в патче мода.

Дерево берётся из servers/ папки данных: корень репозитория или L2HUB_HOME, если задан (как у лаунчера) — так
проверяют свежую сборку на тестовой папке данных, не трогая миры автора.

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
sys.path.insert(0, str(REPO / "launcher"))
from l2hub.patching import PatchError, apply_patch  # noqa: E402  движок сборщика, без зависимостей лаунчера

HOME = Path(os.environ["L2HUB_HOME"]).resolve() if os.environ.get("L2HUB_HOME") else REPO  # где лежит servers/<id>
WORK = REPO / "servers" / "_glue"  # servers/ в .gitignore


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


def lay_module(tree: Path, module: dict[str, Path]) -> None:
    """Модуль в дерево, как copy_module сборщика: до модов, чтобы моды могли править и его файлы."""
    for rel, f in module.items():
        (tree / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(f, tree / rel)


def apply(tree: Path, patches) -> None:
    """Движок сборщика (l2hub/patching.py): байт в байт, как при «Пересобрать». git apply строже к формату и
    отказывается от патчей, которые сборщик кладёт (0507 Эпилога без строк diff --git)."""
    for p in patches:
        try:
            apply_patch(tree, p)
        except PatchError as e:
            raise SystemExit(f"не наложился {p.name}: {e}") from None


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
    tree = HOME / "servers" / cid / meta["build"]
    if not tree.is_dir():
        sys.exit(f"нет дерева {tree}")
    glue_path = REPO / "phantoms" / cid / "glue.patch"
    mods = sorted((REPO / "patches" / cid).glob("*.patch"))
    module = module_files(cid)
    cur = files(tree)

    base = WORK / cid / "base"
    extract_base(cid, meta["build"], REPO / meta["archive"], base)
    lay_module(base, module)
    apply(base, mods)
    base_files = files(base)

    # Файл модуля в дереве — как модуль после модов хроники (у большинства хроник моды файлов модуля не трогают).
    # При пересборке glue это ошибка (правку в дереве потеряем); при --check — предупреждение: дерево собрано
    # со старым модулем, glue сверяется по файлам Mobius.
    wrong = [rel for rel in module if rel in cur and cur[rel].read_bytes() != base_files[rel].read_bytes()]
    stray = sorted(rel for rel in cur if rel not in base_files)
    if (wrong or stray) and not check_only:
        rm(WORK / cid)
        if wrong:
            print("Файлы модуля в дереве разошлись с phantoms/ и модами — их правят в phantoms/ или в патче мода, а не в servers/:")
            print("\n".join(f"  {r}" for r in wrong))
        if stray:
            print("Новые файлы вне модуля и патчей — перенеси в phantoms/ или в патч мода:")
            print("\n".join(f"  {r}" for r in stray))
        return 1
    glue_files = sorted(rel for rel in cur if rel in base_files and rel not in module and base_files[rel].read_bytes() != cur[rel].read_bytes())
    glue = make_patch({r: base_files[r].read_bytes() for r in glue_files}, {r: cur[r].read_bytes() for r in glue_files})

    if check_only:
        if wrong or stray:
            print(f"Внимание: дерево собрано не с текущим модулем и модами (файлов модуля другие — {len(wrong)}, "
                  f"лишних — {len(stray)}); пересобери хронику. glue сверяется по файлам Mobius.")
        try:
            apply_patch(base, glue_path)
            lays = "glue.patch ложится на базу с модулем и модами"
        except PatchError as e:
            lays = f"glue.patch НЕ ложится на базу с модулем и модами: {e}"
        same = glue_path.exists() and glue_path.read_bytes() == glue
        print(lays)
        print(("glue.patch совпадает с деревом" if same else "glue.patch НЕ совпадает с деревом") + f": файлов Mobius {len(glue_files)}")
        rm(WORK / cid)
        return 0 if same and lays.startswith("glue.patch ложится") else 1

    glue_path.write_bytes(glue)
    print(f"glue.patch: файлов Mobius {len(glue_files)}, {len(glue)} байт")

    # Проверка с нуля в порядке сборщика: база + модуль + моды + glue == дерево
    t = WORK / cid / "check"
    extract_base(cid, meta["build"], REPO / meta["archive"], t)
    lay_module(t, module)
    apply(t, mods + [glue_path])
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
