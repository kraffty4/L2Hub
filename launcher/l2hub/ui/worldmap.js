'use strict';

// Карта мира для «Мой мир»: подложка — тайлы ui/maps (tools/world_map_tiles.py, в .gitignore), поверх — точки персонажей.
// Перетаскивание мышью, колесо — зум к курсору, двойной щелчок — ближе. Описание тайлов приходит из Api.world_map:
// так у Api одна точка правды о подложке, а картинки идут по относительному пути maps/.
window.WorldMap = (() => {
  // Окно отдаёт интерфейс своим http-сервером с корнем в ui/, devserver — тоже из ui/: путь один.
  const BASE = 'maps/';
  const TILE_MIN_SCALE = 0.2; // мельче — хватает обзорной картинки (128 px на тайл)
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

  function create(host, meta, { tip } = {}) {
    host.innerHTML = '<div class="wm"><div class="wm-world"><img class="wm-over" alt="" draggable="false"><div class="wm-tiles"></div></div>'
      + '<div class="wm-pts"></div><div class="wm-ctl"><button data-z="in" title="Ближе">+</button><button data-z="out" title="Дальше">−</button><button data-z="fit" title="Весь мир">⤢</button></div><div class="wm-tip"></div></div>';
    const root = host.firstChild;
    const world = root.querySelector('.wm-world');
    const tilesBox = root.querySelector('.wm-tiles');
    const ptsBox = root.querySelector('.wm-pts');
    const tipBox = root.querySelector('.wm-tip');
    const bp = meta.blockPx;
    const cols = meta.max.x - meta.min.x + 1;
    const rows = meta.max.y - meta.min.y + 1;
    const W = cols * bp;
    const H = rows * bp;
    const have = new Set(meta.tiles);
    const shown = new Map(); // «x_y» → img
    let s = 0.05, tx = 0, ty = 0, points = [], raf = 0;
    const over = root.querySelector('.wm-over');
    over.src = BASE + 'overview.webp';
    over.style.width = W + 'px';
    over.style.height = H + 'px';
    world.style.width = W + 'px';
    world.style.height = H + 'px';

    const toPx = (x, y) => [(x / meta.blockSize + meta.mid.x - meta.min.x) * bp, (y / meta.blockSize + meta.mid.y - meta.min.y) * bp];
    const clampView = () => {
      const w = root.clientWidth, h = root.clientHeight;
      const mw = W * s, mh = H * s;
      tx = mw <= w ? (w - mw) / 2 : Math.min(0, Math.max(w - mw, tx));
      ty = mh <= h ? (h - mh) / 2 : Math.min(0, Math.max(h - mh, ty));
    };
    const fit = () => {
      s = Math.min(root.clientWidth / W, root.clientHeight / H) || 0.05;
      clampView();
      draw();
    };
    const zoomAt = (cx, cy, k) => {
      const ns = Math.min(2.5, Math.max(Math.min(root.clientWidth / W, root.clientHeight / H) * 0.9, s * k));
      tx = cx - (cx - tx) * (ns / s);
      ty = cy - (cy - ty) * (ns / s);
      s = ns;
      clampView();
      draw();
    };
    function draw() {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        world.style.transform = `translate(${tx}px, ${ty}px) scale(${s})`;
        drawTiles();
        drawPoints();
      });
    }
    function drawTiles() {
      const need = new Set();
      if (s >= TILE_MIN_SCALE) {
        const w = root.clientWidth, h = root.clientHeight, step = bp * s;
        const c0 = Math.max(0, Math.floor(-tx / step)), c1 = Math.min(cols - 1, Math.floor((w - tx) / step));
        const r0 = Math.max(0, Math.floor(-ty / step)), r1 = Math.min(rows - 1, Math.floor((h - ty) / step));
        for (let c = c0; c <= c1; c++) for (let r = r0; r <= r1; r++) {
          const key = `${c + meta.min.x}_${r + meta.min.y}`;
          if (have.has(key)) need.add(key);
        }
      }
      for (const [key, img] of shown) if (!need.has(key)) { img.remove(); shown.delete(key); }
      for (const key of need) {
        if (shown.has(key)) continue;
        const [x, y] = key.split('_').map(Number);
        const img = document.createElement('img');
        img.className = 'wm-tile';
        img.draggable = false;
        img.style.left = (x - meta.min.x) * bp + 'px';
        img.style.top = (y - meta.min.y) * bp + 'px';
        img.style.width = img.style.height = bp + 'px';
        img.src = `${BASE}tiles/${key}.webp`;
        tilesBox.appendChild(img);
        shown.set(key, img);
      }
    }
    function drawPoints() {
      const w = root.clientWidth, h = root.clientHeight;
      const labels = s >= 0.3;
      let html = '';
      points.forEach((p, i) => {
        const [px, py] = toPx(p.x, p.y);
        const sx = tx + px * s, sy = ty + py * s;
        if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) return;
        const cls = `wm-pt k-${p.kind}${p.online === false && !p.live ? ' off' : ''}`;
        html += `<i class="${cls}" data-i="${i}" style="left:${sx.toFixed(1)}px;top:${sy.toFixed(1)}px">${labels || p.kind === 'player' ? `<b>${esc(p.name)}</b>` : ''}</i>`;
      });
      ptsBox.innerHTML = html;
    }

    // Мышь: перетаскивание, колесо, двойной щелчок, подсказка по точке.
    let drag = null;
    root.addEventListener('pointerdown', (e) => {
      if (e.target.closest('.wm-ctl')) return;
      drag = { x: e.clientX, y: e.clientY, tx, ty };
      root.setPointerCapture(e.pointerId);
      root.classList.add('grab');
    });
    root.addEventListener('pointermove', (e) => {
      if (drag) {
        tx = drag.tx + (e.clientX - drag.x);
        ty = drag.ty + (e.clientY - drag.y);
        clampView();
        draw();
        return;
      }
      const dot = e.target.closest('.wm-pt');
      if (!dot) { tipBox.classList.remove('on'); return; }
      const p = points[+dot.dataset.i];
      const r = root.getBoundingClientRect();
      tipBox.innerHTML = tip ? tip(p) : esc(p.name);
      tipBox.style.left = Math.min(r.width - 230, e.clientX - r.left + 14) + 'px';
      tipBox.style.top = Math.max(6, e.clientY - r.top - 10) + 'px';
      tipBox.classList.add('on');
    });
    const endDrag = () => { drag = null; root.classList.remove('grab'); };
    root.addEventListener('pointerup', endDrag);
    root.addEventListener('pointercancel', endDrag);
    root.addEventListener('pointerleave', () => tipBox.classList.remove('on'));
    root.addEventListener('wheel', (e) => {
      e.preventDefault();
      const r = root.getBoundingClientRect();
      zoomAt(e.clientX - r.left, e.clientY - r.top, Math.pow(1.0015, -e.deltaY));
    }, { passive: false });
    root.addEventListener('dblclick', (e) => {
      if (e.target.closest('.wm-ctl')) return;
      const r = root.getBoundingClientRect();
      zoomAt(e.clientX - r.left, e.clientY - r.top, 2);
    });
    root.querySelector('.wm-ctl').addEventListener('click', (e) => {
      const z = e.target.closest('[data-z]')?.dataset.z;
      if (z === 'fit') return fit();
      if (z) zoomAt(root.clientWidth / 2, root.clientHeight / 2, z === 'in' ? 1.6 : 1 / 1.6);
    });
    const ro = new ResizeObserver(() => { clampView(); draw(); });
    ro.observe(root);
    requestAnimationFrame(fit);

    return {
      setPoints(list) { points = list || []; draw(); },
      // Показать точку: зум и центр на ней.
      focus(name) {
        const p = points.find((x) => x.name === name);
        if (!p) return;
        const [px, py] = toPx(p.x, p.y);
        s = Math.max(s, 0.6);
        tx = root.clientWidth / 2 - px * s;
        ty = root.clientHeight / 2 - py * s;
        clampView();
        draw();
      },
      fit,
      alive: () => root.isConnected,
      destroy() { ro.disconnect(); cancelAnimationFrame(raf); host.innerHTML = ''; },
    };
  }
  return { create };
})();
