"""Подложка карты «Мой мир»: тайлы npetrovski/l2-world-map (те же, что у карты L2Solo) → webp и обзорная картинка.

Источник — карта мира из клиента Lineage II, собранная сообществом; лицензии у репозитория нет, поэтому в программу
и в репозиторий она не входит (docs/RELEASE.md, «Ненаш контент»): лаунчер скачивает её по кнопке пользователя —
архив репозитория на закреплённом коммите — и собирает подложку у него. Готовая подложка хранится в папке данных
(data/maps: обновление программы её не трогает) и копируется туда, откуда её отдаёт окно, — ui/maps (http-сервер окна
с корнем ui/). Из консоли — tools/world_map_tiles.py (из клона в downloads/).

Тайл {x}_{y} — квадрат 32768×32768 игровых единиц, 900 px; x 11–26, y 10–26, середина мира — 20_18 (игровые 0,0 —
левый верхний угол тайла 20_18). Варианты {x}_{y}_1.jpg не берутся. Кроме тайлов — overview.webp (весь мир по 128 px
на тайл, для мелкого масштаба) и index.json с границами."""

import io
import json
import re
import shutil
import urllib.request
import zipfile
from pathlib import Path

from . import __version__, backups, paths

COMMIT = "0751a218798b1b759ff3555c7b48a025df49e332"
PAGE = "https://github.com/npetrovski/l2-world-map"
URL = f"{PAGE}/archive/{COMMIT}.zip"
STORE = paths.DATA / "maps"
UI_MAPS = paths.UI / "maps"
BLOCK_PX, BLOCK_SIZE, MID = 900, 32768, {"x": 20, "y": 18}
OVERVIEW_PX = 128
TILES = 193  # тайлов {x}_{y}.jpg на закреплённом коммите (без вариантов _1)
NAME = re.compile(r"(?:^|/)Maps/(\d+)_(\d+)\.jpg$", re.IGNORECASE)


def sync() -> bool:
    """Подложка из папки данных — в ui/maps, если её там нет (после обновления программы). Есть ли она в окне."""
    if (UI_MAPS / "index.json").is_file():
        return True
    if not (STORE / "index.json").is_file():
        return False
    shutil.copytree(STORE, UI_MAPS, dirs_exist_ok=True)
    return True


def build(images, out: Path, note=lambda text, frac=None: None) -> dict:
    """images — [(x, y, открыть() → файл картинки)]; пишет out/tiles/*.webp, overview.webp, index.json."""
    from PIL import Image  # только здесь: остальному лаунчеру Pillow не нужен

    xs, ys = [t[0] for t in images], [t[1] for t in images]
    bounds = {"min": {"x": min(xs), "y": min(ys)}, "max": {"x": max(xs), "y": max(ys)}}
    (out / "tiles").mkdir(parents=True, exist_ok=True)
    cols, rows = bounds["max"]["x"] - bounds["min"]["x"] + 1, bounds["max"]["y"] - bounds["min"]["y"] + 1
    overview = Image.new("RGB", (cols * OVERVIEW_PX, rows * OVERVIEW_PX), (14, 12, 10))
    names = []
    for i, (x, y, opener) in enumerate(sorted(images)):
        with opener() as f:
            img = Image.open(f).convert("RGB")
        if img.size != (BLOCK_PX, BLOCK_PX):
            img = img.resize((BLOCK_PX, BLOCK_PX), Image.LANCZOS)
        img.save(out / "tiles" / f"{x}_{y}.webp", quality=80, method=4)
        overview.paste(img.resize((OVERVIEW_PX, OVERVIEW_PX), Image.LANCZOS),
                       ((x - bounds["min"]["x"]) * OVERVIEW_PX, (y - bounds["min"]["y"]) * OVERVIEW_PX))
        names.append(f"{x}_{y}")
        note(f"тайлов {i + 1} из {len(images)}", 0.5 + 0.5 * (i + 1) / len(images))
    overview.save(out / "overview.webp", quality=82, method=4)
    index = {"source": PAGE, "blockPx": BLOCK_PX, "blockSize": BLOCK_SIZE, "mid": MID, **bounds, "overviewPx": OVERVIEW_PX, "tiles": names}
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    return index


def install(note=lambda text, frac=None: None, cancelled=lambda: False) -> int:
    """Скачать архив (если его ещё нет), собрать подложку в data/maps и положить в ui/maps. Сколько тайлов."""
    archive = paths.HOME / "downloads" / f"l2-world-map-{COMMIT[:12]}.zip"
    if not archive.is_file() or not zipfile.is_zipfile(archive):
        archive.parent.mkdir(parents=True, exist_ok=True)
        part = archive.with_name(archive.name + ".part")
        req = urllib.request.Request(URL, headers={"User-Agent": f"L2Hub/{__version__}"})
        with urllib.request.urlopen(req, timeout=60) as r, part.open("wb") as f:  # noqa: S310 — адрес закреплён здесь
            done = 0
            while chunk := r.read(1 << 20):
                if cancelled():
                    raise RuntimeError("Скачивание остановлено")
                f.write(chunk)
                done += len(chunk)
                note(f"скачано {done / 2**20:.0f} МБ", min(0.45, done / (170 * 2**20) * 0.45))
        if not zipfile.is_zipfile(part):
            part.unlink(missing_ok=True)
            raise RuntimeError("Скачался не zip — попробуй ещё раз")
        part.replace(archive)
    tmp = STORE.with_name("maps.build")
    _away(tmp)  # недоделанная прошлая сборка
    with zipfile.ZipFile(archive) as z:
        images = [(int(m[1]), int(m[2]), lambda n=n: io.BytesIO(z.read(n))) for n in z.namelist() if (m := NAME.search(n))]
        if len(images) < TILES:
            raise RuntimeError(f"В архиве {len(images)} тайлов, ждали {TILES}")
        index = build(images, tmp, note)
    _away(STORE)
    tmp.replace(STORE)
    _away(UI_MAPS)
    sync()
    return len(index["tiles"])


def _away(path: Path) -> None:
    """Прежняя подложка — в Корзину, а не насовсем (правило проекта)."""
    if path.exists():
        backups.to_recycle_bin(path)
