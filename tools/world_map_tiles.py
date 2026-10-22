"""Подложка карты мира для лаунчера: тайлы npetrovski/l2-world-map → launcher/l2hub/ui/maps (webp, в .gitignore).

Источник — github.com/npetrovski/l2-world-map (те же тайлы, что у карты L2Solo): лицензии у репозитория нет, по сути
это карта из клиента NCSoft. Поэтому только локально у автора: в репозиторий и друзьям не кладётся (AGENTS.md),
выбор автора 2026-10-20. Клон — downloads/l2-world-map (git clone --depth 1).

Тайл {x}_{y} — квадрат 32768×32768 игровых единиц, 900 px; x 11–26, y 10–26, середина мира — 20_18
(игровые 0,0 — левый верхний угол тайла 20_18). Варианты {x}_{y}_1.jpg и т. п. не берутся: их не берёт и сам
просмотрщик репозитория. Кроме тайлов — overview.webp: весь мир одной картинкой по 128 px на тайл, для
мелкого масштаба, и index.json с границами.

Запуск (из корня репо): uv run --no-project --with pillow python -X utf8 tools/world_map_tiles.py
"""

import json
import re
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "downloads" / "l2-world-map" / "Maps"
# Рядом с интерфейсом: окно отдаёт его своим http-сервером с корнем в ui/, путь «maps/…» один и в окне, и в devserver.
OUT = ROOT / "launcher" / "l2hub" / "ui" / "maps"
BLOCK_PX, BLOCK_SIZE, MID = 900, 32768, {"x": 20, "y": 18}
OVERVIEW_PX = 128
NAME = re.compile(r"^(\d+)_(\d+)\.jpg$", re.IGNORECASE)


def main() -> None:
    if not SRC.is_dir():
        sys.exit(f"Нет {SRC}: git clone --depth 1 https://github.com/npetrovski/l2-world-map downloads/l2-world-map")
    tiles = sorted((int(m[1]), int(m[2]), f) for f in SRC.iterdir() if (m := NAME.match(f.name)))
    xs, ys = [t[0] for t in tiles], [t[1] for t in tiles]
    bounds = {"min": {"x": min(xs), "y": min(ys)}, "max": {"x": max(xs), "y": max(ys)}}
    (OUT / "tiles").mkdir(parents=True, exist_ok=True)
    cols, rows = bounds["max"]["x"] - bounds["min"]["x"] + 1, bounds["max"]["y"] - bounds["min"]["y"] + 1
    overview = Image.new("RGB", (cols * OVERVIEW_PX, rows * OVERVIEW_PX), (14, 12, 10))
    names = []
    for x, y, f in tiles:
        img = Image.open(f).convert("RGB")
        if img.size != (BLOCK_PX, BLOCK_PX):
            img = img.resize((BLOCK_PX, BLOCK_PX), Image.LANCZOS)
        img.save(OUT / "tiles" / f"{x}_{y}.webp", quality=80, method=4)
        overview.paste(img.resize((OVERVIEW_PX, OVERVIEW_PX), Image.LANCZOS),
                       ((x - bounds["min"]["x"]) * OVERVIEW_PX, (y - bounds["min"]["y"]) * OVERVIEW_PX))
        names.append(f"{x}_{y}")
    overview.save(OUT / "overview.webp", quality=82, method=4)
    index = {"source": "https://github.com/npetrovski/l2-world-map", "blockPx": BLOCK_PX, "blockSize": BLOCK_SIZE, "mid": MID,
             **bounds, "overviewPx": OVERVIEW_PX, "tiles": names}
    (OUT / "index.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    size = sum(f.stat().st_size for f in (OUT / "tiles").iterdir()) + (OUT / "overview.webp").stat().st_size
    print(f"тайлов {len(names)}, мир {cols}×{rows}, {size / 2**20:.1f} МБ → {OUT}")


if __name__ == "__main__":
    main()
