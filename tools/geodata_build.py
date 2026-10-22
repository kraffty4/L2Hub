"""Геодата L2J из карт клиента: l2mapconv (runtime/l2mapconv, MIT, github.com/m49n/l2mapconv-public) по регионам, в несколько процессов.

У Mobius для Classic и Essence геодаты нет; l2mapconv строит её из Maps/*.unr клиента (проверен автором утилиты на
Essence Samurai Crow EU P542, у нас работает и на клиенте Classic EU 286). Геодата получается из клиента пользователя —
как тайлы карты, её не раздаём.

Использование:
  python -X utf8 -I tools/geodata_build.py <папка клиента> <папка geodata сервера> [--jobs N] [--maps 22_22 23_22 ...]
Регионы — все Maps/XX_YY.unr клиента в границах мира Mobius (X 11–28, Y 10–26), кроме уже готовых XX_YY.l2j в папке
назначения. Каждый процесс — своё задание --geodata-job в data/geodata_build/<имя папки назначения>/<время>-<номер>/;
готовые .l2j переносятся в папку назначения. Повторный запуск достраивает недостающее.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXE = ROOT / "runtime" / "l2mapconv" / "l2mapconv.exe"
X_RANGE, Y_RANGE = range(11, 29), range(10, 27)
SETTINGS = {
    "actor_height": 48, "actor_radius": 16, "max_walkable_angle": 45.5,
    "min_walkable_climb": 2, "max_walkable_climb": 16, "cell_size": 16, "cell_height": 1,
}


def regions(client: Path) -> list[str]:
    out = []
    for f in sorted((client / "Maps").glob("*.unr")):
        m = re.fullmatch(r"(\d+)_(\d+)", f.stem)
        if m and int(m[1]) in X_RANGE and int(m[2]) in Y_RANGE:
            out.append(f.stem)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(prog="geodata_build")
    ap.add_argument("client", type=Path)
    ap.add_argument("dest", type=Path)
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--maps", nargs="*")
    args = ap.parse_args()

    dest = args.dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    # Задания — рядом с данными L2Hub, по имени профиля (servers/<id>/…) или папки назначения.
    name = dest.parts[dest.parts.index("servers") + 1] if "servers" in dest.parts else dest.name
    work = ROOT / "data" / "geodata_build" / name
    # Прерванный прошлый запуск: готовые регионы (l2mapconv публикует файл целиком) — в папку назначения.
    for f in sorted(work.glob("*/*.l2j")):
        if not (dest / f.name).exists():
            shutil.move(str(f), dest / f.name)
            print("из прошлого запуска:", f.name)
    todo = [m for m in (args.maps or regions(args.client)) if not (dest / f"{m}.l2j").exists()]
    if not todo:
        print("всё уже построено")
        return 0
    stamp = time.strftime("%Y-%m-%d_%H-%M-%S")
    chunks = [todo[i::args.jobs] for i in range(min(args.jobs, len(todo)))]
    procs = []
    for i, maps in enumerate(chunks):
        job = work / f"{stamp}-{i}"
        job.mkdir(parents=True)
        (job / "request.json").write_text(json.dumps({
            "schema_version": 2, "kind": "geodata", "job_id": job.name,
            "client_root": str(args.client.resolve()).replace("\\", "/"), "maps": maps,
            "outputs": {"l2j": True, "client_dat": False, "navmesh": False}, "settings": SETTINGS,
        }, indent=1), encoding="utf-8")
        log = open(job / "run.log", "w", encoding="utf-8")
        procs.append((job, maps, subprocess.Popen([str(EXE), "--geodata-job", str(job)], stdout=log, stderr=subprocess.STDOUT, cwd=job)))
        print(f"задание {job.name}: {len(maps)} регионов")
    print(f"всего {len(todo)} регионов, процессов {len(procs)}")

    failed = 0
    for job, maps, proc in procs:
        code = proc.wait()
        built = sorted(job.rglob("*.l2j"))
        for f in built:
            if not (dest / f.name).exists():
                shutil.move(str(f), dest / f.name)
        missing = [m for m in maps if not (dest / f"{m}.l2j").exists()]
        failed += len(missing)
        print(f"{job.name}: код {code}, готово {len(maps) - len(missing)}/{len(maps)}" + (f", нет: {' '.join(missing)}" if missing else ""))
    print("готово" if not failed else f"не построено регионов: {failed} (повторный запуск достроит)")
    return 0 if not failed else 3


if __name__ == "__main__":
    sys.exit(main())
