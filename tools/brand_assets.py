"""Собирает логотип L2Hub «Врата»: SVG знака и надписи, PNG, ICO и превью репозитория.

Запуск (из корня репо; шрифт Cinzel лежит в downloads/fonts/cinzel, см. docs/design/BRAND.md):
    uv run --no-project --with fonttools --with uharfbuzz --with pillow python -X utf8 tools/brand_assets.py [--social]

Растр рисует Edge в headless-режиме, поэтому SVG и PNG выглядят одинаково.
Результат: docs/design/brand/ (исходники и превью) и launcher/l2hub/ui/assets/ (файлы для окна).
"""
import argparse
import io
import pathlib
import subprocess
import tempfile

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from PIL import Image
import uharfbuzz as hb

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT = ROOT / "downloads" / "fonts" / "cinzel" / "Cinzel-wght.ttf"
BRAND = ROOT / "docs" / "design" / "brand"
ASSETS = ROOT / "launcher" / "l2hub" / "ui" / "assets"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# Палитра окна (styles.css) и оттенки металла.
BG, BG_HI = "#120d09", "#251c13"
BRONZE_DK, BRONZE, BRONZE_HI = "#4a341c", "#7a6038", "#a8834a"
GOLD, GOLD_HI, GOLD_LT = "#c9a052", "#e2c27d", "#f6e3b4"
INK = "#1a1009"


# ---------- шрифт ----------

def load_font(weight):
    font = instancer.instantiateVariableFont(TTFont(FONT), {"wght": weight})
    buf = io.BytesIO()
    font.save(buf)
    return font, hb.Font(hb.Face(buf.getvalue()))


def text_path(font, hbfont, text, tracking=0):
    """Контур строки в координатах шрифта (ось y вниз, базовая линия y=0) и её ширина."""
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {"kern": True, "liga": True})
    glyphs, order = font.getGlyphSet(), font.getGlyphOrder()
    x, d = 0, []
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        pen = SVGPathPen(glyphs)
        glyphs[order[info.codepoint]].draw(TransformPen(pen, (1, 0, 0, -1, x + pos.x_offset, -pos.y_offset)))
        d.append(pen.getCommands())
        x += pos.x_advance + tracking
    return " ".join(d), x - tracking


def glyph_bounds(font, hbfont, text):
    """Габариты контура (xmin, ymin, xmax, ymax) в координатах text_path."""
    from fontTools.pens.boundsPen import BoundsPen
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {"kern": True})
    glyphs, order = font.getGlyphSet(), font.getGlyphOrder()
    x, box = 0, None
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        pen = BoundsPen(glyphs)
        glyphs[order[info.codepoint]].draw(TransformPen(pen, (1, 0, 0, -1, x + pos.x_offset, -pos.y_offset)))
        if pen.bounds:
            b = pen.bounds
            box = b if box is None else (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))
        x += pos.x_advance
    return box


# ---------- знак «Врата» ----------

def pointed_arch(x0, x1, spring, apex):
    """Стрельчатая арка из двух дуг: от (x0, spring) через вершину к (x1, spring).

    Центры дуг лежат на линии пят, поэтому арка острая только при подъёме больше полупролёта.
    """
    half, rise = (x1 - x0) / 2, spring - apex
    assert rise > half, "подъём арки меньше полупролёта: выйдет купол, а не стрельчатая арка"
    r = (half ** 2 + rise ** 2) / (2 * half)
    return f"A{r:.3f} {r:.3f} 0 0 1 {x0 + half:.3f} {apex:.3f} A{r:.3f} {r:.3f} 0 0 1 {x1:.3f} {spring:.3f}"


def mark_parts(small):
    """Геометрия знака в поле 100×100. small — упрощённый вариант для 16–32 px."""
    if small:
        # Рамка тоньше, проём шире, без шпиля: на 16 px главное — светлый проём и тёмная двойка.
        o = dict(x0=5, x1=95, spring=62, apex=2, base=96)
        i = dict(x0=20, x1=80, spring=64, apex=18)
        caps, bases, finial = [], [], None
    else:
        o = dict(x0=13, x1=87, spring=58, apex=6, base=89)
        i = dict(x0=30, x1=70, spring=58, apex=23)
        caps = [(8, 55, 25, 6), (67, 55, 25, 6)]
        bases = [(7, 86, 27, 7), (66, 86, 27, 7)]
        finial = (50, 6, 50, 0, 5)
    frame = (f"M{o['x0']} {o['base']} V{o['spring']} " + pointed_arch(o['x0'], o['x1'], o['spring'], o['apex'])
             + f" V{o['base']} Z "
             + f"M{i['x0']} {o['base']} V{i['spring']} " + pointed_arch(i['x0'], i['x1'], i['spring'], i['apex'])
             + f" V{o['base']} Z")
    door = (f"M{i['x0']} {o['base']} V{i['spring']} " + pointed_arch(i['x0'], i['x1'], i['spring'], i['apex'])
            + f" V{o['base']} Z")
    return o, i, frame, door, caps, bases, finial


def mark_svg_body(font, hbfont, small=False, uid="m"):
    """Содержимое <svg> знака в поле 100×100 (defs + фигуры)."""
    o, i, frame, door, caps, bases, finial = mark_parts(small)
    # Цифра 2 из Cinzel: по высоте проёма, нижним краем на пороге.
    d2, _ = text_path(font, hbfont, "2")
    bx0, by0, bx1, by1 = glyph_bounds(font, hbfont, "2")
    target_h = (o['base'] - i['apex']) * (0.74 if small else 0.62)
    s = target_h / (by1 - by0)
    tx = 50 - (bx0 + bx1) / 2 * s
    ty = o['base'] - (4 if small else 5) - by1 * s
    finial_path = ""
    if finial:
        fx, ftop, _, ftip, fw = finial
        finial_path = f'<path d="M{fx - fw / 2} {ftop + 3} L{fx} {ftip} L{fx + fw / 2} {ftop + 3} Z"/>'
    rects = "".join(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="1"/>' for x, y, w, h in caps + bases)
    sw = 3.2 if small else 1.6
    return f"""<defs>
  <linearGradient id="{uid}-metal" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#d9b56b"/><stop offset=".5" stop-color="#9c7740"/><stop offset="1" stop-color="{BRONZE_DK}"/>
  </linearGradient>
  <linearGradient id="{uid}-rim" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="{GOLD_LT}"/><stop offset="1" stop-color="{GOLD}"/>
  </linearGradient>
  <radialGradient id="{uid}-glow" cx="50" cy="{o['base'] - 18}" r="{(o['base'] - i['apex']) * 0.85}" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#fff6d8"/><stop offset=".45" stop-color="{GOLD_HI}"/><stop offset="1" stop-color="#a8702a"/>
  </radialGradient>
</defs>
<path d="{door}" fill="url(#{uid}-glow)" stroke="#2a1a0c" stroke-width="{4 if small else 2.6}"/>
<path d="{d2}" transform="translate({tx:.3f} {ty:.3f}) scale({s:.5f})" fill="{INK}"/>
<g fill="url(#{uid}-metal)" stroke="url(#{uid}-rim)" stroke-width="{sw / 2}" stroke-linejoin="round">
  <path d="{frame}" fill-rule="evenodd"/>
  {rects}
  {finial_path}
</g>"""


def mark_svg(font, hbfont, small=False, size=None):
    attrs = f' width="{size}" height="{size}"' if size else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"{attrs}>'
            + mark_svg_body(font, hbfont, small, "s" if small else "m") + "</svg>\n")


def icon_svg(font, hbfont, small=False, size=None):
    """Знак на скруглённой плитке — иконка окна и ярлыка."""
    attrs = f' width="{size}" height="{size}"' if size else ""
    # Мелкой плитке рамка не нужна: бронзовый пиксель по краю съедает место у знака.
    pad, inner = (3, 94) if small else (13, 74)
    border = 0 if small else 1.4
    stroke = f' stroke="{BRONZE}" stroke-width="{border}"' if border else ""
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"{attrs}>
<defs><linearGradient id="tile" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{BG_HI}"/><stop offset="1" stop-color="{BG}"/></linearGradient></defs>
<rect x="{border / 2}" y="{border / 2}" width="{100 - border}" height="{100 - border}" rx="{16 if small else 20}" fill="url(#tile)"{stroke}/>
<svg x="{pad}" y="{pad}" width="{inner}" height="{inner}" viewBox="0 0 100 100">{mark_svg_body(font, hbfont, small, "is" if small else "i")}</svg>
</svg>
"""


def lockup_svg(font, hbfont, mark_font, mark_hb):
    """Знак + надпись «L2Hub» в одну строку."""
    d, width = text_path(font, hbfont, "L2Hub", tracking=20)
    x0, y0, x1, y1 = glyph_bounds(font, hbfont, "L")
    cap = y1 - y0                       # высота прописной (в единицах шрифта, y вниз: y0 < 0)
    mark_h = cap * 1.7
    gap = cap * 0.3
    total_w = mark_h + gap + width
    vb_h = mark_h
    base_y = mark_h - (mark_h - cap) / 2 - mark_h * 0.02   # базовая линия чуть ниже центра знака
    # Градиент в координатах контура (до translate): прописные от -cap до базовой линии 0.
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_w:.1f} {vb_h:.1f}">
<defs><linearGradient id="word" x1="0" y1="{-cap:.1f}" x2="0" y2="0" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="{GOLD_LT}"/><stop offset=".5" stop-color="{GOLD_HI}"/><stop offset="1" stop-color="{GOLD}"/>
</linearGradient></defs>
<svg x="0" y="0" width="{mark_h:.1f}" height="{mark_h:.1f}" viewBox="0 0 100 100">{mark_svg_body(mark_font, mark_hb, False, "l")}</svg>
<path d="{d}" transform="translate({mark_h + gap:.1f} {base_y:.1f})" fill="url(#word)"/>
</svg>
""", total_w / vb_h


# ---------- растр через Edge ----------

def render(items, out_dir):
    """items: [(svg_text, w, h, out_path)]. Рисует все на одной странице и режет кадры."""
    pad = 8
    x, y, row_h, page_w = pad, pad, 0, 1400
    placed = []
    for svg, w, h, out in items:
        if x + w + pad > page_w:
            x, y, row_h = pad, y + row_h + pad, 0
        placed.append((svg, w, h, out, x, y))
        x += w + pad
        row_h = max(row_h, h)
    page_h = y + row_h + pad
    blocks = "".join(f'<div style="position:absolute;left:{px}px;top:{py}px;width:{w}px;height:{h}px">'
                     f'{svg.replace("<svg ", f"<svg width=\"{w}\" height=\"{h}\" ", 1)}</div>'
                     for svg, w, h, out, px, py in placed)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        html = tmp / "page.html"
        html.write_text(f'<!doctype html><html><head><meta charset="utf-8"><style>html,body{{margin:0;background:transparent}}'
                        f'svg{{display:block}}</style></head><body>{blocks}</body></html>', encoding="utf-8")
        shot = tmp / "shot.png"
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--default-background-color=00000000", f"--user-data-dir={tmp / 'profile'}",
                        f"--window-size={page_w},{page_h}", f"--screenshot={shot}", html.as_uri()],
                       check=True, capture_output=True, timeout=120)
        page = Image.open(shot).convert("RGBA")
    for svg, w, h, out, px, py in placed:
        page.crop((px, py, px + w, py + h)).save(out)
    return [p[3] for p in placed]


def render_page(html, w, h, out):
    """Снимок готовой HTML-страницы w×h."""
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        page = tmp / "page.html"
        page.write_text(html, encoding="utf-8")
        shot = tmp / "shot.png"
        subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                        "--virtual-time-budget=5000", f"--user-data-dir={tmp / 'profile'}",
                        f"--window-size={w},{h}", f"--screenshot={shot}", page.as_uri()],
                       check=True, capture_output=True, timeout=120)
        return Image.open(shot).convert("RGB").crop((0, 0, w, h))


# Обложки для превью репозитория — по порядку хроник; число — где по ширине обложки главный объект.
SOCIAL_COVERS = [("C4", "cover_c4_login.jpg", .47), ("Interlude", "cover_interlude_login.jpg", .56),
                 ("Gracia Final", "cover_gracia_final_login.jpg", .53), ("Epilogue", "cover_epilogue_login.jpg", .5),
                 ("High Five", "cover_ct26_highfive_login.jpg", .34), ("Classic", "cover_classic_login.jpg", .3),
                 ("Essence", "cover_essence_login.jpg", .5)]


def social_preview(lockup):
    """Превью репозитория GitHub 1280×640: косые полосы обложек семи хроник и логотип."""
    import base64
    w, h, n, k = 1280, 640, len(SOCIAL_COVERS), 46       # k — половина наклона полосы по x
    sw = w / n
    cinzel = base64.b64encode(FONT.read_bytes()).decode()
    slices, labels, lines = [], [], []
    for idx, (name, file, focus) in enumerate(SOCIAL_COVERS):
        x0, x1 = idx * sw, (idx + 1) * sw
        cx = (x0 + x1) / 2
        img_w = 1942 * h / 809
        poly = f"{x0 + k - 2:.1f}px 0, {x1 + k + 2:.1f}px 0, {x1 - k + 2:.1f}px {h}px, {x0 - k - 2:.1f}px {h}px"
        slices.append(f'<div class="sl" style="clip-path:polygon({poly});background-image:url({(ASSETS / file).as_uri()});'
                      f'background-position:{cx - img_w * focus:.1f}px 0"></div>')
        labels.append(f'<div class="lb" style="left:{cx - k * 0.62 - 80:.1f}px">{name}</div>')
        if idx:
            lines.append(f'<line x1="{x0 + k}" y1="0" x2="{x0 - k}" y2="{h}"/>')
    svg_logo = lockup.replace("<svg ", '<svg width="720" ', 1)
    html = f"""<!doctype html><html><head><meta charset="utf-8"><style>
@font-face {{ font-family: Cinzel; src: url(data:font/ttf;base64,{cinzel}) format("truetype"); font-weight: 400 900; }}
html,body {{ margin:0; width:{w}px; height:{h}px; overflow:hidden; background:{BG}; }}
.sl {{ position:absolute; inset:0; background-size:auto {h}px; background-repeat:no-repeat; filter:saturate(.9); }}
.shade {{ position:absolute; inset:0;
  background: radial-gradient(ellipse 52% 46% at 50% 47%, rgba(14,10,7,.93) 0, rgba(14,10,7,.82) 45%, rgba(14,10,7,.35) 100%),
              linear-gradient(to bottom, rgba(14,10,7,.55), rgba(14,10,7,0) 22%, rgba(14,10,7,0) 70%, rgba(14,10,7,.9)); }}
svg.lines {{ position:absolute; inset:0; }}
svg.lines line {{ stroke:{GOLD}; stroke-opacity:.55; stroke-width:1.5; }}
.frame {{ position:absolute; inset:14px; border:1px solid rgba(201,160,82,.45); border-radius:6px; }}
.center {{ position:absolute; left:0; right:0; top:178px; display:flex; flex-direction:column; align-items:center; gap:22px; }}
.center svg {{ display:block; filter: drop-shadow(0 6px 24px rgba(0,0,0,.8)); }}
.tag {{ font-family:"Palatino Linotype", Georgia, serif; font-size:30px; letter-spacing:.22em; color:{GOLD_HI};
  text-transform:uppercase; text-shadow:0 2px 12px #000; }}
.rule {{ width:420px; height:1px; background:linear-gradient(90deg, transparent, {GOLD}, transparent); }}
.lb {{ position:absolute; bottom:30px; width:160px; text-align:center; font-family:Cinzel; font-weight:700; font-size:15px;
  letter-spacing:.12em; color:{GOLD_HI}; text-shadow:0 1px 6px #000, 0 0 2px #000; }}
</style></head><body>
{''.join(slices)}
<div class="shade"></div>
<svg class="lines" width="{w}" height="{h}">{''.join(lines)}</svg>
<div class="frame"></div>
<div class="center">{svg_logo}<div class="rule"></div><div class="tag">Семь хроник — одна кнопка</div></div>
{''.join(labels)}
</body></html>"""
    out = BRAND / "social_preview_1280x640.jpg"
    render_page(html, w, h, out).save(out, quality=90, optimize=True)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--social", action="store_true", help="собрать превью репозитория 1280×640 из обложек")
    args = ap.parse_args()

    BRAND.mkdir(parents=True, exist_ok=True)
    font, hbfont = load_font(700)

    big, small = mark_svg(font, hbfont), mark_svg(font, hbfont, small=True)
    tile, tile_small = icon_svg(font, hbfont), icon_svg(font, hbfont, small=True)
    lockup, ratio = lockup_svg(font, hbfont, font, hbfont)
    for name, svg in {"logo_mark.svg": big, "logo_mark_small.svg": small, "icon.svg": tile,
                      "icon_small.svg": tile_small, "logo.svg": lockup}.items():
        (BRAND / name).write_text(svg, encoding="utf-8")

    png = BRAND / "png"
    png.mkdir(exist_ok=True)
    ico_sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
    items = [(tile_small if s <= 32 else tile, s, s, png / f"icon_{s}.png") for s in ico_sizes + [512]]
    items += [(small, s, s, png / f"mark_{s}.png") for s in (16, 22, 24, 32)]
    items += [(big, s, s, png / f"mark_{s}.png") for s in (44, 64, 128, 256, 512)]
    lw = 960
    items += [(lockup, lw, round(lw / ratio), png / "logo_960.png")]
    render(items, png)

    frames = [Image.open(png / f"icon_{s}.png") for s in ico_sizes]
    frames[-1].save(BRAND / "icon.ico", format="ICO", sizes=[(s, s) for s in ico_sizes], append_images=frames[:-1])

    # Файлы для окна.
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "logo.svg").write_text(lockup, encoding="utf-8")
    (ASSETS / "logo_mark.svg").write_text(small, encoding="utf-8")
    (ASSETS / "icon.ico").write_bytes((BRAND / "icon.ico").read_bytes())
    for s in (256, 512):
        (ASSETS / f"icon_{s}.png").write_bytes((png / f"icon_{s}.png").read_bytes())
    (ASSETS / "logo.png").write_bytes((png / "logo_960.png").read_bytes())
    if args.social:
        print("превью репозитория:", social_preview(lockup))
    print("готово:", BRAND, ASSETS)


if __name__ == "__main__":
    main()
