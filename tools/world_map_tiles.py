"""Подложка карты мира для лаунчера: тайлы npetrovski/l2-world-map → launcher/l2hub/ui/maps (webp, в .gitignore).

Источник — github.com/npetrovski/l2-world-map (те же тайлы, что у карты L2Solo): лицензии у репозитория нет, по сути
это карта из клиента NCSoft. Поэтому только локально у автора: в репозиторий и друзьям не кладётся (AGENTS.md),
выбор автора 2026-10-20. Клон — downloads/l2-world-map (git clone --depth 1).

Тайл {x}_{y} — квадрат 32768×32768 игровых единиц, 900 px; x 11–26, y 10–26, середина мира — 20_18
(игровые 0,0 — левый верхний угол тайла 20_18). Варианты {x}_{y}_1.jpg и т. п. не берутся: их не берёт и сам
просмотрщик репозитория. Кроме тайлов — overview.webp: весь мир одной картинкой по 128 px на тайл, для
мелкого масштаба, и index.json с границами.

Сборка — та же, что у кнопки в окне (l2hub/worldtiles.build: пергамент вместо схем-интерьеров, сведённые швы).
Запуск (из корня репо): uv run --project launcher python -X utf8 tools/world_map_tiles.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "launcher"))
from l2hub import worldtiles  # noqa: E402

SRC = ROOT / "downloads" / "l2-world-map" / "Maps"
# Рядом с интерфейсом: окно отдаёт его своим http-сервером с корнем в ui/, путь «maps/…» один и в окне, и в devserver.
OUT = ROOT / "launcher" / "l2hub" / "ui" / "maps"
NAME = re.compile(r"^(\d+)_(\d+)\.jpg$", re.IGNORECASE)


def main() -> None:
    if not SRC.is_dir():
        sys.exit(f"Нет {SRC}: git clone --depth 1 https://github.com/npetrovski/l2-world-map downloads/l2-world-map")
    images = [(int(m[1]), int(m[2]), lambda f=f: f.open("rb")) for f in SRC.iterdir() if (m := NAME.match(f.name))]
    index = worldtiles.build(images, OUT, lambda text, frac=None: print(f"\r{text}", end="", flush=True))
    size = sum(f.stat().st_size for f in (OUT / "tiles").iterdir()) + (OUT / "overview.webp").stat().st_size
    print(f"\nтайлов {len(index['tiles'])}, из них пергамент {len(index['parchment'])}, {size / 2**20:.1f} МБ → {OUT}")


if __name__ == "__main__":
    main()
