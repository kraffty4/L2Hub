// Английский интерфейс (docs/RELEASE.md, «Английский»). Исходные строки окна и ядра — русские, прямо в коде;
// перевод — словарь i18n/en.js (ключ — русская строка, {0}, {1} — подстановки; ключи выгружает tools/i18n_extract.py).
// Переводчик сидит поверх DOM: MutationObserver меняет русские текстовые узлы и подсказки на английские, поэтому код
// окна строки в t() не оборачивает. Не трогает то, что под translate="no" (речь ботов, журналы), <pre>, <code>, поля ввода.
// Язык — ?lang= в адресе окна (ставит __main__.py по настройке «Язык интерфейса»); смена — перезагрузка страницы.
(function () {
  const params = new URLSearchParams(location.search);
  const want = params.get('lang') || (params.has('dev') ? 'ru' : 'auto');
  const lang = want === 'en' || (want === 'auto' && !/^ru\b/i.test(navigator.language || '')) ? 'en' : 'ru';
  const CYR = /[А-Яа-яЁё]/;
  const exact = new Map();
  const pats = [];
  for (const [ru, en] of Object.entries(lang === 'en' ? window.L2_EN || {} : {})) {
    if (!en) continue;
    if (!/\{\d+\}/.test(ru)) { exact.set(ru, en); continue; }
    const order = [];
    const src = ru.split(/(\{\d+\})/).map((p) => {
      const m = /^\{(\d+)\}$/.exec(p);
      if (m) { order.push(+m[1]); return '([\\s\\S]*?)'; } // пустая тоже: ${x ? '…' : ''}
      return p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }).join('');
    pats.push({ re: new RegExp(`^${src}$`), order, en, weight: ru.replace(/\{\d+\}/g, '').length });
  }
  pats.sort((a, b) => b.weight - a.weight); // сначала длинные шаблоны: «{0} ботов онлайн» раньше «{0} ботов»

  const cache = new Map();
  const missing = new Set();
  const SEPS = ['. ', ' · ', ' — ', ' – ', '; ', ': ', ', ', ' / ']; // сначала предложения: «А — б. В — г.»

  // Русская строка → английская или null, если целиком не перевести: тогда узел остаётся русским (лучше, чем
  // полурусская каша), а строка попадает в L2I18N.missing — для словаря. Порядок: целиком, по шаблону (подстановки
  // переводятся тоже), края («· …», «… —», «…», точка в конце), части через разделители, число и слова.
  function find(s, depth) {
    const hit = exact.get(s);
    if (hit !== undefined) return hit;
    const dot = exact.get(`${s}.`); // предложение, отрезанное от следующего по «. », — ключ с точкой
    if (dot !== undefined && dot.endsWith('.')) return dot.slice(0, -1);
    if (depth > 5) return null;
    for (const p of pats) {
      const m = p.re.exec(s);
      if (!m) continue;
      // Короткий шаблон («{0}к», «у {0}», «{0} по {1}») — только для чисел и латиницы: иначе он съест чужую фразу.
      if (p.weight < 5 && m.slice(1).some((g) => CYR.test(g))) continue;
      const groups = p.order.map((_, k) => part(m[k + 1], depth));
      if (!groups.includes(null)) return p.en.replace(/\{(\d+)\}/g, (_, i) => groups[p.order.indexOf(+i)]);
    }
    let m;
    if ((m = /^([·—–]\s+)([\s\S]+)$/.exec(s))) return wrap(m[1], part(m[2], depth), ''); // ключи без них (i18n_extract, clean)
    if ((m = /^([\s\S]+?)(\s+[·—–])$/.exec(s))) return wrap('', part(m[1], depth), m[2]);
    if ((m = /^«([\s\S]+)»$/.exec(s))) return wrap('“', part(m[1], depth), '”');
    if ((m = /^([\s\S]+?)([.:!?…]+)$/.exec(s)) && (m[1] = part(m[1], depth)) !== null) return m[1] + m[2];
    if ((m = /^([\s\S]+?)(\s*\([^А-Яа-яЁё()]*\))$/.exec(s))) return wrap('', part(m[1], depth), m[2]); // «Игрок (0)»
    for (const sep of SEPS) {
      if (!s.includes(sep)) continue;
      const xs = s.split(sep).map((x) => part(x, depth));
      if (!xs.includes(null)) return xs.join(sep);
    }
    if ((m = /^([-+−~]?[\d\s.,:%×x]+)\s+(\S[\s\S]*)$/.exec(s))) return wrap(`${m[1]} `, part(m[2], depth), '');
    return null;
  }
  const wrap = (a, x, b) => (x === null ? null : a + x + b);
  function part(v, depth) { // кусок строки: без кириллицы — как есть, иначе перевод или null
    if (!CYR.test(v)) return v;
    const m = /^(\s*)([\s\S]*?)(\s*)$/.exec(v);
    return wrap(m[1], find(m[2], depth + 1), m[3]);
  }
  function tr(s) {
    if (lang !== 'en' || typeof s !== 'string' || !CYR.test(s)) return s;
    const m = /^(\s*)([\s\S]*?)(\s*)$/.exec(s);
    const core = m[2].replace(/\s+/g, ' ');
    let out = cache.get(core);
    if (out === undefined) {
      out = find(core, 0);
      cache.set(core, out);
      if (out === null && core.length > 1) missing.add(core); // одна буква — инициал имени в кружке, не текст
    }
    return out === null ? s : m[1] + out + m[3];
  }

  window.tr = tr;
  window.L2I18N = {
    lang, want, tr, missing,
    locale: lang === 'en' ? 'en-GB' : 'ru-RU', // даты и время: toLocaleDateString(L2I18N.locale, …)
    // Сменить язык: в адрес окна — ?lang=, страница перегружается (окно, выбранная хроника и раздел — из localStorage).
    use(next) {
      const p = new URLSearchParams(location.search);
      p.set('lang', next);
      location.replace(`${location.pathname}?${p}`);
    },
  };
  if (lang !== 'en') return;

  document.documentElement.lang = 'en';
  // Текст внутри этих тегов не переводится (ввод, логи, код), а подсказки и placeholder у них — да.
  const RAW = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'PRE', 'CODE']);
  const ATTRS = ['title', 'placeholder', 'aria-label', 'alt'];
  const mute = (el) => !el || el.closest('[translate="no"]');
  function text(node) {
    const el = node.parentElement;
    if (!CYR.test(node.data) || mute(el) || RAW.has(el.tagName)) return;
    const t = tr(node.data);
    if (t !== node.data) node.data = t;
  }
  function attrs(el) {
    for (const a of ATTRS) {
      const v = el.getAttribute(a);
      if (v && CYR.test(v)) { const t = tr(v); if (t !== v) el.setAttribute(a, t); }
    }
  }
  function walk(root) {
    if (root.nodeType === 3) return text(root);
    if (root.nodeType !== 1 || mute(root)) return;
    attrs(root);
    if (RAW.has(root.tagName)) return;
    const w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => (n.nodeType === 1 && n.getAttribute('translate') === 'no' ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
    });
    for (let n = w.nextNode(); n; n = w.nextNode()) {
      if (n.nodeType === 1) attrs(n);
      else if (CYR.test(n.data) && !RAW.has(n.parentElement.tagName)) { const t = tr(n.data); if (t !== n.data) n.data = t; }
    }
  }
  new MutationObserver((list) => {
    for (const m of list) {
      if (m.type === 'childList') m.addedNodes.forEach(walk);
      else if (m.type === 'characterData') text(m.target);
      else if (!mute(m.target)) attrs(m.target);
    }
  }).observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  document.addEventListener('DOMContentLoaded', () => walk(document.documentElement));
  const native = { confirm: window.confirm.bind(window), alert: window.alert.bind(window) };
  const lines = (msg) => String(msg).split('\n').map(tr).join('\n');
  window.confirm = (msg) => native.confirm(lines(msg));
  window.alert = (msg) => native.alert(lines(msg));
})();
