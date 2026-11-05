// Цены GM-шопа — вкладка в настройках хроники (Api.gm_shop*, l2hub/gmshop.py, docs/GM_SHOP_PRICES.md, раздел «Редактор»).
// Правки лежат слоем в data/gmshop, переживают «Пересобрать» и сразу пишутся в файлы сервера; гейм читает их при старте.
// Вкладка есть только у хроник с записью в gm_shops.json.

const GM = { id: null, tree: [], root: null, pick: { kind: 'all' }, open: new Set(), query: '', only: '', preview: null, seq: 0, edited: 0, warnings: 0, modeGrades: {}, restart: false, loaded: false };

const gmMoney = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');

// Разделы деревом: страницы шопа обходятся вширь, поэтому «Оружие / D грейд» в списке далеко от «Оружие».
function gmBuildTree(tree) {
  const root = { path: [], title: '', lists: [], kids: new Map() };
  for (const e of tree) {
    let node = root;
    e.path.forEach((word, i) => {
      if (!node.kids.has(word)) node.kids.set(word, { path: e.path.slice(0, i + 1), title: word, lists: [], kids: new Map() });
      node = node.kids.get(word);
    });
    node.lists.push(e);
  }
  // Страницы «1/11, 7/11, 2/11»: кнопки идут в две колонки, по порядку читать их удобнее.
  const sortPages = (node) => {
    if (node.lists.length && node.lists.every((e) => /^\d+\/\d+$/.test(e.title))) node.lists.sort((a, b) => parseInt(a.title, 10) - parseInt(b.title, 10));
    node.kids.forEach(sortPages);
  };
  sortPages(root);
  return root;
}

const gmNodeLists = (node) => [...node.lists, ...[...node.kids.values()].flatMap(gmNodeLists)];
const gmNodeKey = (node) => node.path.join('\u0001');

function gmFindNode(key) {
  const walk = (node) => {
    if (gmNodeKey(node) === key) return node;
    for (const kid of node.kids.values()) {
      const hit = walk(kid);
      if (hit) return hit;
    }
    return null;
  };
  return GM.root ? walk(GM.root) : null;
}

// Что выбрано слева: весь шоп, группа или один раздел — на это действуют таблица, множитель и снятие правок.
function gmPicked() {
  if (GM.pick.kind === 'list') {
    const e = GM.tree.find((x) => x.list === GM.pick.list);
    if (e) return { lists: [e], title: [...e.path, e.title].join(' / ') };
  }
  if (GM.pick.kind === 'group') {
    const node = gmFindNode(GM.pick.key);
    if (node) return { lists: gmNodeLists(node), title: node.path.join(' / ') };
  }
  return { lists: GM.tree, title: 'Весь шоп' };
}

function gmMarks(lists) {
  const edited = lists.reduce((sum, e) => sum + e.edited, 0);
  const warns = lists.reduce((sum, e) => sum + e.warnings, 0);
  return `${edited ? `<span class="chg">● ${edited}</span>` : ''}${warns ? `<span class="gm-w">! ${warns}</span>` : ''}`;
}

function gmRenderTree() {
  const box = $('gmTree');
  box.innerHTML = '';
  const add = (html, cls, click) => {
    const b = document.createElement('button');
    b.className = cls;
    b.innerHTML = html;
    b.onclick = click;
    box.appendChild(b);
    return b;
  };
  const picked = (kind, value) => GM.pick.kind === kind && (kind === 'all' || GM.pick[kind === 'list' ? 'list' : 'key'] === value);
  const choose = (pick) => { GM.pick = pick; GM.query = ''; $('gmSearch').value = ''; GM.preview = null; gmRenderTree(); gmRenderTools(); gmLoadRows(); };
  const rows = (lists) => lists.reduce((sum, e) => sum + e.rows, 0);
  add(`Весь шоп${gmMarks(GM.tree)}<span class="c">${rows(GM.tree)}</span>`, 'fileb' + (picked('all') && !GM.query ? ' on' : ''), () => choose({ kind: 'all' }));
  const listButton = (e, depth) => {
    const b = add(`${esc(e.title)}${gmMarks([e])}<span class="c">${e.rows}</span>`, 'fileb' + (picked('list', e.list) && !GM.query ? ' on' : ''), () => choose({ kind: 'list', list: e.list }));
    b.style.paddingLeft = `${8 + depth * 12}px`;
  };
  const walk = (node, depth) => {
    node.lists.forEach((e) => listButton(e, depth));
    for (const kid of node.kids.values()) {
      const key = gmNodeKey(kid);
      const isOpen = GM.open.has(key);
      const all = gmNodeLists(kid);
      const b = add(`<span class="gm-caret">${isOpen ? '▾' : '▸'}</span>${esc(kid.title)}${gmMarks(all)}<span class="c">${rows(all)}</span>`, 'fileb gm-group' + (picked('group', key) && !GM.query ? ' on' : ''), (ev) => {
        if (ev.target.classList.contains('gm-caret')) {
          if (isOpen) GM.open.delete(key); else GM.open.add(key);
          gmRenderTree();
          return;
        }
        GM.open.add(key);
        choose({ kind: 'group', key });
      });
      b.style.paddingLeft = `${8 + depth * 12}px`;
      if (isOpen) walk(kid, depth + 1);
    }
  };
  walk(GM.root, 0);
}

function gmRenderTools() {
  const box = $('gmTools');
  const search = Boolean(GM.query.trim());
  box.hidden = search;
  if (search) return;
  const { lists, title } = gmPicked();
  const edited = lists.reduce((sum, e) => sum + e.edited, 0);
  box.innerHTML = `<span class="gm-title">${esc(title)}</span>
    <label class="gm-mul">Множитель <input class="field" id="gmFactor" placeholder="1,5" value="${esc(GM.factor || '')}"></label>
    <button class="sbtn" id="gmCount">Посчитать</button>
    ${edited ? `<button class="sbtn" id="gmReset">Снять правки (${edited})</button>` : ''}`;
  $('gmFactor').oninput = (e) => { GM.factor = e.target.value; };
  $('gmFactor').onkeydown = (e) => { if (e.key === 'Enter') gmCount(); };
  $('gmCount').onclick = gmCount;
  if (edited) $('gmReset').onclick = gmReset;
}

async function gmCount() {
  const id = GM.id;
  const { lists } = gmPicked();
  const r = await api.gm_shop_multiply(id, lists.map((e) => e.list), GM.factor || '', true);
  if (id !== selected) return;
  if (!r.ok) return toast(r.error, true);
  GM.preview = { factor: GM.factor, lists: lists.map((e) => e.list), ...r };
  gmRenderPreview();
}

function gmRenderPreview() {
  const box = $('gmPreview');
  const p = GM.preview;
  box.hidden = !p;
  if (!p) { box.innerHTML = ''; return; }
  const shown = p.changes.slice(0, 8);
  box.innerHTML = `<div>Изменится строк: <b>${p.total}</b>, новых предупреждений: <b>${p.newWarningsTotal}</b>. Цены округляются до трёх значащих цифр.</div>
    ${shown.map((c) => `<div class="gm-chg"><span>${esc(c.name)}${c.count > 1 ? ` ×${c.count}` : ''}</span><span>${gmMoney(c.old)} → ${gmMoney(c.new)}</span></div>`).join('')}
    ${p.total > shown.length ? `<div class="gm-more">и ещё ${p.total - shown.length}</div>` : ''}
    ${p.newWarnings.slice(0, 5).map((w) => `<div class="hint">${esc(w.name)} · ${esc(w.section)}: ${esc(w.text)}</div>`).join('')}
    ${p.newWarningsTotal > 5 ? `<div class="gm-more">и ещё предупреждений: ${p.newWarningsTotal - 5}</div>` : ''}
    <div class="row2"><button class="sbtn primary" id="gmApply"${p.total ? '' : ' disabled'}>Применить</button><button class="sbtn" id="gmCancel">Отмена</button></div>`;
  $('gmCancel').onclick = () => { GM.preview = null; gmRenderPreview(); };
  $('gmApply').onclick = async () => {
    const r = await api.gm_shop_multiply(GM.id, p.lists, p.factor, false);
    if (!r.ok) return toast(r.error, true);
    GM.preview = null;
    toast(`Цены изменены: ${r.total}.`);
    await gmReload(r.restart);
  };
}

async function gmReset() {
  const { lists, title } = gmPicked();
  const edited = lists.reduce((sum, e) => sum + e.edited, 0);
  if (!(await askModal({ title: 'Снять правки цен', ok: 'Снять', body: `<div class="note">${esc(title)}: правок ${edited}. Цены вернутся к ценам сборки.</div>` }))) return;
  const r = await api.gm_shop_reset(GM.id, GM.pick.kind === 'all' ? null : lists.map((e) => e.list));
  if (!r.ok) return toast(r.error, true);
  toast(`Снято правок: ${r.reset}.`);
  await gmReload(r.restart);
}

async function gmLoadRows() {
  const id = GM.id;
  const seq = ++GM.seq;
  const search = GM.query.trim();
  const lists = search ? null : gmPicked().lists.map((e) => e.list);
  const r = await api.gm_shop_rows(id, lists, search, GM.only);
  if (id !== selected || seq !== GM.seq) return;
  if (!r.ok) { $('gmRows').innerHTML = `<div class="empty">${esc(r.error)}</div>`; return; }
  gmRenderRows(r, Boolean(search) || !(gmPicked().lists.length === 1));
}

function gmRenderRows(r, showSection) {
  const box = $('gmRows');
  const top = box.scrollTop;
  const focused = document.activeElement?.dataset?.gmKey;
  if (!r.rows.length) { box.innerHTML = '<div class="empty">Ничего не нашлось.</div>'; return; }
  const table = document.createElement('table');
  table.className = 'gm-table';
  table.innerHTML = '<thead><tr><th>Вещь</th><th class="gm-num">Цена, адена</th><th class="gm-num">В сборке</th><th></th></tr></thead>';
  const body = document.createElement('tbody');
  for (const row of r.rows) {
    const tr = document.createElement('tr');
    tr.className = row.edited ? 'edited' : '';
    const name = `<div class="gm-name">${esc(row.name)}${row.count > 1 ? ` <span class="gm-count">×${gmMoney(row.count)}</span>` : ''} <span class="gm-id">${row.item}</span></div>`
      + (showSection ? `<div class="gm-sec">${esc(row.section)}</div>` : '')
      + row.warnings.map((w) => `<div class="hint">${esc(w)}</div>`).join('');
    const left = document.createElement('td');
    left.innerHTML = name;
    const price = document.createElement('td');
    price.className = 'gm-num';
    const base = document.createElement('td');
    base.className = 'gm-num gm-base';
    const undo = document.createElement('td');
    if (row.editable) {
      const input = document.createElement('input');
      input.className = 'field' + (row.edited ? ' chg' : '');
      input.value = gmMoney(row.price);
      input.dataset.gmKey = `${row.list}/${row.key}`;
      input.onkeydown = (e) => { if (e.key === 'Enter') input.blur(); };
      input.onchange = () => gmSetPrice(row, input);
      price.appendChild(input);
      if (row.edited) {
        base.textContent = gmMoney(row.base);
        const b = document.createElement('button');
        b.className = 'gm-undo';
        b.title = 'Вернуть цену сборки';
        b.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5"/></svg>';
        b.onclick = () => gmSetPrice(row, null);
        undo.appendChild(b);
      }
    } else {
      price.innerHTML = `<span class="gm-swap">обмен: ${esc(row.pay)}</span>`;
    }
    tr.append(left, price, base, undo);
    body.appendChild(tr);
  }
  table.appendChild(body);
  box.innerHTML = '';
  box.appendChild(table);
  if (r.total > r.rows.length) box.insertAdjacentHTML('beforeend', `<div class="empty">Показано ${r.rows.length} из ${r.total}. Уточни поиск или выбери раздел.</div>`);
  box.scrollTop = top;
  if (focused) box.querySelector(`[data-gm-key="${CSS.escape(focused)}"]`)?.focus();
}

async function gmSetPrice(row, input) {
  const price = input ? input.value.replace(/\s/g, '') : null;
  const r = await api.gm_shop_set(GM.id, [{ list: row.list, key: row.key, price }]);
  if (!r.ok) {
    toast(r.error, true);
    if (input) input.value = gmMoney(row.price);
    return;
  }
  await gmReload(r.restart);
}

function gmRenderInfo() {
  $('gmBadge').textContent = GM.edited || '';
  // Каждая фраза — свой текстовый узел: переводчик окна (i18n.js) ищет в словаре узел целиком.
  $('gmInfo').innerHTML = [Object.keys(GM.modeGrades).length ? '<span>Режим x10: A ×5, S и выше ×20 — поверх цен ниже</span>' : '',
    GM.edited ? `<span>Правок: ${GM.edited}.</span>` : '', GM.warnings ? `<span class="gm-w">Предупреждений: ${GM.warnings}.</span>` : '',
    '<span>Правки лежат отдельно от сборки и переживают «Пересобрать», покупки охотников идут за ними.</span>',
    GM.restart ? '<b>Сервер работает: в игре цены сменятся после перезапуска гейма.</b>' : '<span>В игре цены сменятся при следующем запуске гейма.</span>'].join(' ');
}

// Счётчики и таблица заново: одна цена меняет предупреждения и в других строках (пачка против штук).
async function gmReload(restart) {
  const id = GM.id;
  const r = await api.gm_shop(id);
  if (id !== selected) return;
  if (!r.ok) return toast(r.error, true);
  gmTakeState(r);
  if (restart !== undefined) GM.restart = restart;
  gmRenderTree(); gmRenderTools(); gmRenderPreview(); gmRenderInfo();
  await gmLoadRows();
}

function gmTakeState(r) {
  GM.tree = r.tree;
  GM.root = gmBuildTree(r.tree);
  GM.edited = r.edited;
  GM.warnings = r.warnings;
  GM.modeGrades = r.modeGrades || {};
  GM.restart = r.restart;
}

// Зовёт loadSchema: сменилась хроника — вкладка есть только там, где есть запись в gm_shops.json.
async function gmShopRefresh() {
  const id = selected;
  const r = await api.gm_shop(id).catch(() => null);
  if (id !== selected) return;
  const tab = $('gmTab');
  const has = Boolean(r?.ok && r.supported && r.tree.length);
  tab.hidden = !has;
  if (!has && tab.classList.contains('on')) document.querySelector('.tab[data-tab="basic"]').click();
  if (!has) { GM.id = null; return; }
  if (GM.id !== id) { GM.id = id; GM.pick = { kind: 'all' }; GM.open = new Set(); GM.query = ''; GM.only = ''; GM.preview = null; GM.loaded = false; $('gmSearch').value = ''; }
  gmTakeState(r);
  gmRenderInfo();
  if (tab.classList.contains('on')) gmOpen();
}

function gmOpen() {
  if (!GM.id) return;
  gmRenderTree(); gmRenderTools(); gmRenderPreview(); gmRenderInfo();
  gmLoadRows();
}

function gmSwitch(id, value) {
  $(id).onclick = () => {
    GM.only = GM.only === value ? '' : value;
    $('gmOnlyEdited').setAttribute('aria-checked', GM.only === 'edited');
    $('gmOnlyWarn').setAttribute('aria-checked', GM.only === 'warn');
    gmLoadRows();
  };
}
gmSwitch('gmOnlyEdited', 'edited');
gmSwitch('gmOnlyWarn', 'warn');

$('gmSearch').oninput = (e) => {
  GM.query = e.target.value;
  GM.preview = null;
  clearTimeout(GM.timer);
  GM.timer = setTimeout(() => { gmRenderTree(); gmRenderTools(); gmRenderPreview(); gmLoadRows(); }, 250);
};

document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => {
  const on = t.dataset.tab === 'gmshop';
  $('viewSettings').classList.toggle('gm-on', on);
  if (on) gmOpen();
}));
gmShopRefresh();
