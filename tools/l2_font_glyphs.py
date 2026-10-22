"""Глифы шрифта клиента Lineage II: какие знаки есть в шрифте и как они нарисованы (например, есть ли кириллица).

Шрифт — пара файлов: `.gly` в system (таблица прямоугольников глифов по диапазонам Юникода) и `.utx` в systextures
(атлас). Читается только незашифрованный `.utx` (Unreal-пакет с сигнатурой C1832A9E: так у шрифтов чата мода L2Solo);
`.utx` с заголовком Lineage2Ver… сначала расшифровать. Атлас — DXT3, DXT5 или P8 (подбирается по размеру данных).

    python -X utf8 tools/l2_font_glyphs.py <шрифт.utx> <шрифт.gly> <лист.png>

Пишет лист глифов по диапазонам (подпись — код знака) и печатает диапазоны. Формат `.gly`: int32 ширина и высота
атласа, int32 число диапазонов; на диапазон — int32 первый код, int32 число знаков и по 16 байт на знак
(int32 x, ширина, y, высота). Нужен Pillow.
"""
import struct
import sys
from pathlib import Path

from PIL import Image, ImageDraw


def compact_index(d: bytes, p: int) -> tuple[int, int]:
    b0 = d[p]
    p += 1
    v = b0 & 0x3F
    if b0 & 0x40:
        shift = 6
        while True:
            b = d[p]
            p += 1
            v |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
    return (-v if b0 & 0x80 else v), p


def atlas(utx: Path, width: int, height: int) -> Image.Image:
    """Ищет в пакете массив пикселей атласа по его длине (compact index перед данными) и декодирует."""
    d = utx.read_bytes()
    if d[:4] != b"\xc1\x83\x2a\x9e":
        sys.exit(f"{utx.name}: не Unreal-пакет без шифрования (заголовок {d[:16]!r})")
    for size, decoder in ((width * height, ("bcn", 2)), (width * height, "P8"), (width * height // 2, ("bcn", 1))):
        for p in range(len(d) - size):
            try:
                n, q = compact_index(d, p)
            except IndexError:
                break
            if n == size and q + size <= len(d):
                data = d[q:q + size]
                if decoder == "P8":
                    return Image.frombytes("L", (width, height), data).convert("RGBA")
                return Image.frombytes("RGBA", (width, height), data, decoder[0], decoder[1])
    sys.exit(f"{utx.name}: не нашёл данных атласа {width}×{height}")


def main(utx: Path, gly: Path, out: Path) -> None:
    g = gly.read_bytes()
    width, height, count = struct.unpack_from("<iii", g, 0)
    p = 12
    ranges = []
    for _ in range(count):
        first, n = struct.unpack_from("<ii", g, p)
        p += 8
        ranges.append((first, [struct.unpack_from("<iiii", g, p + 16 * i) for i in range(n)]))
        p += 16 * n
    for first, rects in ranges:
        print(f"U+{first:04X}–U+{first + len(rects) - 1:04X}: {len(rects)} знаков")
    img = atlas(utx, width, height)
    glyphs = [(first + i, r) for first, rects in ranges for i, r in enumerate(rects) if r[1] > 0 and r[3] > 0]
    if len(glyphs) > 2048:
        print(f"знаков {len(glyphs)} — на листе первые 2048")
        glyphs = glyphs[:2048]
    cw = max(max(r[1] for _, r in glyphs) + 6, 30)  # подпись — четыре цифры кода
    ch = max(r[3] for _, r in glyphs) + 14
    rows = (len(glyphs) + 15) // 16
    sheet = Image.new("RGBA", (16 * cw, rows * ch), (30, 30, 30, 255))
    draw = ImageDraw.Draw(sheet)
    for i, (code, (x, w, y, h)) in enumerate(glyphs):
        cx, cy = (i % 16) * cw, (i // 16) * ch
        sheet.alpha_composite(img.crop((x, y, x + w, y + h)), (cx + 3, cy + 12))
        draw.text((cx + 1, cy), f"{code:04X}", fill=(255, 200, 0, 255))
    sheet.resize((sheet.width * 2, sheet.height * 2), Image.NEAREST).save(out)
    print(f"лист — {out}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
