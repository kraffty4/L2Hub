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
# Тайлы-схемы интерьеров (светлые линии на чёрном) и пустота вокруг мира — пергамент с чернилами, как туман войны
# в Civilization VI (решение автора 2026-10-23): схема — от DARK_SHARE пикселей темнее DARK_LEVEL.
PAPER, INK = (222, 205, 168), (88, 66, 42)
DARK_LEVEL, DARK_SHARE = 28, 0.8
# Швы: тон краёв соседних тайлов подводится друг к другу на SEAM_RAMP px вглубь; разница больше SEAM_MAX — настоящая
# граница (берег, схема), её не трогаем.
SEAM_BAND, SEAM_RAMP, SEAM_MAX = 8, 160, 48


def sync() -> bool:
    """Подложка из папки данных — в ui/maps, если её там нет (после обновления программы). Есть ли она в окне."""
    if (UI_MAPS / "index.json").is_file():
        return True
    if not (STORE / "index.json").is_file():
        return False
    shutil.copytree(STORE, UI_MAPS, dirs_exist_ok=True)
    return True


def build(images, out: Path, note=lambda text, frac=None: None) -> dict:
    """images — [(x, y, открыть() → файл картинки)]; пишет out/tiles/*.webp, overview.webp, index.json.
    Идёт рядами: в памяти два ряда тайлов (текущий и следующий) — чтобы свести швы вправо и вниз."""
    from PIL import Image  # только здесь: остальному лаунчеру Pillow не нужен

    xs, ys = [t[0] for t in images], [t[1] for t in images]
    bounds = {"min": {"x": min(xs), "y": min(ys)}, "max": {"x": max(xs), "y": max(ys)}}
    (out / "tiles").mkdir(parents=True, exist_ok=True)
    cols, rows = bounds["max"]["x"] - bounds["min"]["x"] + 1, bounds["max"]["y"] - bounds["min"]["y"] + 1
    overview = Image.new("RGB", (cols * OVERVIEW_PX, rows * OVERVIEW_PX), PAPER)
    openers = {(x, y): opener for x, y, opener in images}
    names, parchment = [], set()

    def load_row(y: int) -> dict:
        row = {}
        for x in sorted(k[0] for k in openers if k[1] == y):
            with openers[(x, y)]() as f:
                img = Image.open(f).convert("RGB")
            if img.size != (BLOCK_PX, BLOCK_PX):
                img = img.resize((BLOCK_PX, BLOCK_PX), Image.LANCZOS)
            if _is_schematic(img):
                img = _parchment(img)
                parchment.add((x, y))
            row[x] = img
        return row

    cur = load_row(bounds["min"]["y"])
    for y in range(bounds["min"]["y"], bounds["max"]["y"] + 1):
        nxt = load_row(y + 1) if y < bounds["max"]["y"] else {}
        for x, img in cur.items():
            if x + 1 in cur and not {(x, y), (x + 1, y)} & parchment:
                _seam(img, cur[x + 1])
            if x in nxt and not {(x, y), (x, y + 1)} & parchment:
                cur[x], nxt[x] = _seam_down(img, nxt[x])
        for x, img in cur.items():
            img.save(out / "tiles" / f"{x}_{y}.webp", quality=80, method=4)
            overview.paste(img.resize((OVERVIEW_PX, OVERVIEW_PX), Image.LANCZOS),
                           ((x - bounds["min"]["x"]) * OVERVIEW_PX, (y - bounds["min"]["y"]) * OVERVIEW_PX))
            names.append(f"{x}_{y}")
            note(f"тайлов {len(names)} из {len(images)}", 0.5 + 0.5 * len(names) / len(images))
        cur = nxt
    overview.save(out / "overview.webp", quality=82, method=4)
    index = {"source": PAGE, "blockPx": BLOCK_PX, "blockSize": BLOCK_SIZE, "mid": MID, **bounds, "overviewPx": OVERVIEW_PX,
             "tiles": names, "parchment": sorted(f"{x}_{y}" for x, y in parchment)}
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    return index


def _is_schematic(img) -> bool:
    hist = img.convert("L").resize((90, 90)).histogram()
    return sum(hist[:DARK_LEVEL]) / (90 * 90) >= DARK_SHARE


def _parchment(img):
    """Схема интерьера → чернила на пергаменте: чёрный фон становится бумагой, светлые линии — чернилами, плюс зерно."""
    from PIL import Image, ImageFilter, ImageOps

    drawn = ImageOps.colorize(ImageOps.invert(img.convert("L")), black=INK, white=PAPER)
    grain = ImageOps.colorize(Image.effect_noise(img.size, 18).filter(ImageFilter.GaussianBlur(1.5)), black=INK, white=PAPER)
    return Image.blend(drawn, grain, 0.1)


def _seam(a, b) -> None:
    """Шов «a слева, b справа», на месте: разница тона у шва по рядам (сглаженная вдоль шва) делится пополам и плавно
    гаснет на SEAM_RAMP px вглубь каждого тайла. Текстура остаётся, пропадает ступенька цвета."""
    from PIL import Image, ImageChops, ImageStat

    w, h = a.size
    edge_a = a.crop((w - SEAM_BAND, 0, w, h)).resize((1, h // 30), Image.BOX).resize((1, h), Image.BILINEAR)
    edge_b = b.crop((0, 0, SEAM_BAND, h)).resize((1, h // 30), Image.BOX).resize((1, h), Image.BILINEAR)
    if max(ImageStat.Stat(ImageChops.difference(edge_a, edge_b)).mean) > SEAM_MAX:
        return
    up = ImageChops.subtract(edge_b, edge_a).point(lambda v: v // 2).resize((SEAM_RAMP, h))
    down = ImageChops.subtract(edge_a, edge_b).point(lambda v: v // 2).resize((SEAM_RAMP, h))
    ramp = Image.linear_gradient("L").transpose(Image.Transpose.TRANSPOSE).resize((SEAM_RAMP, h))
    ramp_a = Image.merge("RGB", (ramp, ramp, ramp))  # 0 вглубь тайла → 255 у шва
    ramp_b = ramp_a.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    band = a.crop((w - SEAM_RAMP, 0, w, h))
    band = ImageChops.subtract(ImageChops.add(band, ImageChops.multiply(up, ramp_a)), ImageChops.multiply(down, ramp_a))
    a.paste(band, (w - SEAM_RAMP, 0))
    band = b.crop((0, 0, SEAM_RAMP, h))
    band = ImageChops.subtract(ImageChops.add(band, ImageChops.multiply(down, ramp_b)), ImageChops.multiply(up, ramp_b))
    b.paste(band, (0, 0))


def _seam_down(a, b):
    """Шов «a сверху, b снизу»: тот же _seam на отражённых по диагонали тайлах."""
    from PIL import Image

    t = Image.Transpose.TRANSPOSE
    at, bt = a.transpose(t), b.transpose(t)
    _seam(at, bt)
    return at.transpose(t), bt.transpose(t)


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
