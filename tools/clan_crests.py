"""Эмблемы кланов охотников (docs/PHANTOMS.md, раздел 4.61): геральдика из простых полей и фигур, картинки свои, ничего чужого.

Генератор детерминирован: номер эмблемы → хеш → поле, фигура и цвета, тот же вывод байт в байт на любой машине. Формат — как шлёт клиент: DDS DXT1,
заголовок 128 байт. Клиент показывает клановую эмблему 16×12 в текстуре 16×16 с пустым нижним рядом блоков, Essence — 24×12 в текстуре 32×16 с пустыми ещё и правыми столбцами. Размер — по хронике (CRESTS).

  python -X utf8 tools/clan_crests.py <id>                     записать phantoms/<id>/dist/game/data/ClanCrests/crest_NNN.dds
  python -X utf8 tools/clan_crests.py <id> --check             сверить с лежащими файлами
  python -X utf8 tools/clan_crests.py <id> --preview out.png   ещё и лист для просмотра (рамка 8 пикселей на пиксель)
"""
import hashlib
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COUNT = 256
# хроника: (ширина текстуры, высота текстуры, видимая ширина, видимая высота). Новая хроника — запись здесь: что принимает её клиент, смотреть в RequestSetPledgeCrest.
CRESTS = {
    "ct26_highfive": (16, 16, 16, 12),
    "epilogue": (16, 16, 16, 12),
    "interlude": (16, 16, 16, 12),  # предел пакета 256 байт
    "classic_tales": (16, 16, 16, 12),  # предел пакета 256 байт
    "ess_crow": (32, 16, 24, 12),  # предел пакета 384 байта, Mobius пишет про BMP 24×12
}

TINCTURES = [
    (222, 178, 60),  # золото
    (222, 222, 228),  # серебро
    (168, 32, 38),  # червлень
    (36, 68, 160),  # лазурь
    (38, 118, 62),  # зелень
    (98, 44, 130),  # пурпур
    (26, 26, 32),  # чернь
    (28, 128, 128),  # бирюза
    (204, 102, 28),  # оранжевый
    (92, 150, 212),  # небесный
    (104, 28, 42),  # кровавый
    (92, 102, 118),  # сталь
]


def luma(color: tuple[int, int, int]) -> float:
    return 0.3 * color[0] + 0.59 * color[1] + 0.11 * color[2]


# Поля: две краски, x и y от 0 до 1.
FIELDS = [
    lambda x, y: False,  # сплошное
    lambda x, y: x >= 0.5,  # надвое столбом
    lambda x, y: y >= 0.5,  # надвое поясом
    lambda x, y: x > y,  # перевязь
    lambda x, y: x + y > 1,  # левая перевязь
    lambda x, y: (x >= 0.5) != (y >= 0.5),  # начетверо
    lambda x, y: y > 1 - abs(x - 0.5) * 1.6 - 0.25,  # стропило
    lambda x, y: int(y * 4) % 2 == 1,  # пояса
    lambda x, y: int(x * 4) % 2 == 1,  # столбы
    lambda x, y: (int(x * 4) + int(y * 3)) % 2 == 1,  # шахматы
    lambda x, y: abs(x - y) < 0.16 or abs(x + y - 1) < 0.16,  # косой крест
    lambda x, y: abs(x - 0.5) < 0.13 or abs(y - 0.5) < 0.17,  # крест
    lambda x, y: y < 0.28,  # глава
    lambda x, y: x < 0.13 or x > 0.87 or y < 0.17 or y > 0.83,  # кайма
]

# Фигуры: третья краска поверх поля, по центру.
CHARGES = [
    None,
    lambda x, y: (x - 0.5) ** 2 + (y - 0.5) ** 2 * 0.56 < 0.055,  # круг
    lambda x, y: 0.024 < (x - 0.5) ** 2 + (y - 0.5) ** 2 * 0.56 < 0.075,  # кольцо
    lambda x, y: abs(x - 0.5) + abs(y - 0.5) * 0.75 < 0.27,  # ромб
    lambda x, y: (abs(x - 0.5) < 0.07 and abs(y - 0.5) < 0.34) or (abs(y - 0.5) < 0.09 and abs(x - 0.5) < 0.26),  # крестик
    lambda x, y: (abs(x - 0.5) * 4 + 1) * (abs(y - 0.5) * 3 + 1) < 1.9,  # звезда
    lambda x, y: 0.2 < y < 0.8 and abs(x - 0.5) < (y - 0.2) * 0.5,  # клин вверх
    lambda x, y: 0.2 < y < 0.8 and abs(x - 0.5) < (0.8 - y) * 0.5,  # клин вниз
    lambda x, y: abs(x - 0.5) < 0.2 and abs(y - 0.5) < 0.26,  # щиток
    lambda x, y: abs(y - 0.5) < 0.1 and abs(x - 0.5) < 0.36,  # полоса
]


def design(index: int, salt: int = 0) -> tuple:
    digest = hashlib.sha256(f"l2hub-clan-crest-{index}-{salt}".encode()).digest()
    field, charge = digest[0] % len(FIELDS), digest[1] % len(CHARGES)
    first, second, third = (TINCTURES[digest[i] % len(TINCTURES)] for i in (2, 3, 4))
    return field, charge, first, second, third


def readable(field: int, charge: int, first: tuple, second: tuple, third: tuple) -> bool:
    """Краски различимы: поле — друг от друга, фигура — от обеих."""
    if field != 0 and abs(luma(first) - luma(second)) < 38:
        return False
    if charge == 0:
        return True
    if abs(luma(third) - luma(first)) < 50:
        return False
    return field == 0 or abs(luma(third) - luma(second)) >= 50


def picture(chosen: tuple, width: int, height: int) -> list[list[tuple]]:
    field, charge, first, second, third = chosen
    rows = []
    for py in range(height):
        row = []
        for px in range(width):
            x, y = (px + 0.5) / width, (py + 0.5) / height
            color = second if FIELDS[field](x, y) else first
            if CHARGES[charge] is not None and CHARGES[charge](x, y):
                color = third
            row.append(color)
        rows.append(row)
    return rows


def rgb565(color: tuple) -> int:
    return ((color[0] >> 3) << 11) | ((color[1] >> 2) << 5) | (color[2] >> 3)


def unpack565(value: int) -> tuple:
    r, g, b = value >> 11, (value >> 5) & 63, value & 31
    return (r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)


def block_palette(c0: int, c1: int) -> list[tuple]:
    a, b = unpack565(c0), unpack565(c1)
    return [a, b, tuple((2 * p + q) // 3 for p, q in zip(a, b)), tuple((p + 2 * q) // 3 for p, q in zip(a, b))]


def encode_block(pixels: list[tuple]) -> bytes:
    """Блок DXT1: две самые частые краски; c0 > c1 — режим четырёх цветов без прозрачности."""
    counts: dict[tuple, int] = {}
    for pixel in pixels:
        counts[pixel] = counts.get(pixel, 0) + 1
    top = sorted(counts, key=lambda color: (-counts[color], color))[:2]
    c0, c1 = rgb565(top[0]), rgb565(top[-1])
    if c0 < c1:
        c0, c1 = c1, c0
    if c0 == c1:
        return struct.pack("<HHI", c0, c1, 0)
    palette = block_palette(c0, c1)
    bits = 0
    for i, pixel in enumerate(pixels):
        nearest = min(range(4), key=lambda k: sum((p - q) ** 2 for p, q in zip(palette[k], pixel)))
        bits |= nearest << (2 * i)
    return struct.pack("<HHI", c0, c1, bits)


def dds(chosen: tuple, width: int, height: int, visible_width: int, visible: int) -> bytes:
    rows = picture(chosen, visible_width, visible)
    body = bytearray()
    for by in range(0, height, 4):
        for bx in range(0, width, 4):
            if by >= visible or bx >= visible_width:
                body += bytes(8)  # невидимый блок: чёрный, как у клиента
            else:
                body += encode_block([rows[by + j][bx + i] for j in range(4) for i in range(4)])
    header = bytearray(128)
    struct.pack_into("<4sIIIII", header, 0, b"DDS ", 124, 0x81007, height, width, len(body))
    struct.pack_into("<II4sI", header, 76, 32, 4, b"DXT1", 24)
    struct.pack_into("<I", header, 108, 0x1000)
    return bytes(header) + bytes(body)


def decode(data: bytes, width: int, height: int) -> list[list[tuple]]:
    rows = [[(0, 0, 0)] * width for _ in range(height)]
    at = 128
    for by in range(0, height, 4):
        for bx in range(0, width, 4):
            c0, c1, bits = struct.unpack_from("<HHI", data, at)
            at += 8
            palette = block_palette(c0, c1)
            for j in range(4):
                for i in range(4):
                    rows[by + j][bx + i] = palette[(bits >> (2 * (j * 4 + i))) & 3]
    return rows


def png(path: Path, rows: list[list[tuple]]) -> None:
    raw = b"".join(b"\0" + bytes(v for pixel in row for v in pixel) for row in rows)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", len(rows[0]), len(rows), 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def sheet(path: Path, crests: list[bytes], width: int, height: int, visible_width: int, visible: int, columns: int = 16, scale: int = 4) -> None:
    gap = 4
    cell_w, cell_h = visible_width * scale + gap, visible * scale + gap
    lines = (len(crests) + columns - 1) // columns
    rows = [[(50, 50, 54)] * (columns * cell_w + gap) for _ in range(lines * cell_h + gap)]
    for n, data in enumerate(crests):
        image = decode(data, width, height)
        ox, oy = gap + (n % columns) * cell_w, gap + (n // columns) * cell_h
        for py in range(visible * scale):
            for px in range(visible_width * scale):
                rows[oy + py][ox + px] = image[py // scale][px // scale]
    png(path, rows)


def build(cid: str) -> list[bytes]:
    width, height, visible_width, visible = CRESTS[cid]
    crests, seen = [], set()
    salt = 0
    index = 0
    while len(crests) < COUNT:
        chosen = design(index, salt)
        salt += 1
        if readable(*chosen):
            data = dds(chosen, width, height, visible_width, visible)
            if data not in seen:
                seen.add(data)
                crests.append(data)
                index += 1
                salt = 0
    return crests


def main() -> None:
    args = sys.argv[1:]
    if not args or args[0] not in CRESTS:
        sys.exit(__doc__)
    cid = args[0]
    out = ROOT / "phantoms" / cid / "dist" / "game" / "data" / "ClanCrests"
    crests = build(cid)
    if "--check" in args:
        bad = [n for n, data in enumerate(crests) if not (out / f"crest_{n:03}.dds").is_file() or (out / f"crest_{n:03}.dds").read_bytes() != data]
        extra = len(list(out.glob("*.dds"))) - len(crests) if out.is_dir() else 0
        sys.exit(f"{cid}: не совпали {len(bad)}, лишних файлов {extra}" if bad or extra else print(f"{cid}: {len(crests)} эмблем совпали байт в байт"))
    out.mkdir(parents=True, exist_ok=True)
    for n, data in enumerate(crests):
        (out / f"crest_{n:03}.dds").write_bytes(data)
    print(f"{cid}: {len(crests)} эмблем по {len(crests[0])} байт в {out.relative_to(ROOT)}")
    if "--preview" in args:
        sheet(Path(args[args.index("--preview") + 1]), crests, *CRESTS[cid])


if __name__ == "__main__":
    main()
