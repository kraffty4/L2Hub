// Вкладка «Настройки сервера» (docs/design/PULT.md, разделы 1 и 2): дерево страниц слева, по центру страница или результаты
// поиска, справа нагрузка (monitor.js; на прежних панелях её нет, им нужна ширина). Страницы — данные: pages в server_modes.json (Api.server_modes, l2hub/modes.py). Ключи, рейты и «Сохранить» —
// общие с app.js (KEYS, edits, setVal, control). «Клиент и сеть», «Все ключи» и «Цены GM-шопа» — прежние панели,
// их кнопки в дереве остаются вкладками .tab; страницы этого файла живут на панели basic.
// Грузится раньше app.js: окно зовёт renderBasic сразу после первого ответа сервера. Сверху — только document, остальное
// из app.js доступно уже при вызове.
(() => {
  const el = (id) => document.getElementById(id);
  const OTHER = { id: 'other', head: 'Служебное', title: 'Прочее', about: 'Выключатели, которым ещё не нашлось места в дереве.', subs: [] };
  const RATE_SHOWN = 30; // ключей в результатах поиска: дальше — «Все ключи»
  let state = null;      // ответ Api.server_modes выбранной хроники
  let pages = [];        // страницы этой сборки: только те, где есть хоть одна строка
  let page = null;
  let activeTab = 'basic';
  let query = '';
  let drawnFor = null;   // какая страница сейчас на экране: другая — прокрутка в начало
  let eventSection = 'catalog';
  let eventFilter = 'all';
  const opened = new Set(); // раскрытые заметки и свёртки переживают перерисовку после щелчка

  const norm = (s) => String(s ?? '').toLowerCase().normalize('NFD').replace(/̈/g, ''); // без точек над ё: ищем и «щелкнуть»
  // В английском окне строки в данных всё равно русские: ищем и по переводу.
  const said = (s) => (window.L2I18N?.lang === 'en' ? `${s} ${window.L2I18N.tr(s)}` : s);

  function build(r) {
    const sw = Object.fromEntries((r.switches || []).filter((s) => s.available).map((s) => [s.id, s]));
    const coveredBySwitch = (key) => Object.values(sw).some((s) => s.part === key.part && s.keys.includes(key.key) && s.file.split(/[\\/]/).pop() === key.file.split(/[\\/]/).pop());
    const placed = new Set();
    const all = r.pages.map((p) => {
      const absent = [];
      const subs = (p.subs || []).map((x) => {
        for (const id of x.ids || []) {
          placed.add(id);
          if (!sw[id]) absent.push(id);
        }
        // Ключ уже стоит выключателем на этой же сборке — второй строки не надо.
        const scopedKey = (key) => x.file ? Object.values(KEYS).find((k) => k.part === 'game' && k.key === key && (k.file === x.file || k.file.endsWith('/' + x.file) || k.file.endsWith('\\' + x.file))) : findKey(key);
        const keys = (x.keys || []).map(([key, title, choices, text, meta]) => ({ k: scopedKey(key), title, choices, text: text && scopedKey(text), ...meta }))
          .filter((x2) => x2.k && !coveredBySwitch(x2.k));
        return { title: x.title || '', note: x.note || x.about, rows: (x.ids || []).filter((id) => sw[id]).map((id) => sw[id]), keys };
      }).filter((x) => x.rows.length || x.keys.length);
      return { ...p, subs, absent: absent.map((id) => r.switches.find((s) => s.id === id)?.title).filter(Boolean),
        rates: rateList(p.rates), fine: rateList(p.fine) };
    });
    const rest = Object.values(sw).filter((s) => !placed.has(s.id));
    if (rest.length) all.push({ ...OTHER, subs: [{ title: '', rows: rest, keys: [] }], absent: [], rates: [], fine: [] });
    pages = all.filter((p) => p.subs.length || p.rates.length || p.fine.length || (p.kind === 'eventHub' && all.some((c) => c.parent === p.id && c.subs.length)) || (p.kind === 'mode' && r.modes?.length) || (p.kind === 'people' && r.huntersMax));
    const saved = String(load('setPage') || '').replace(/^x10-(general|farm|defence|hunt|rewards)$/, (_, name) => `event-${name === 'general' ? 'rules' : name}`);
    if (!pages.some((p) => p.id === page)) page = pages.some((p) => p.id === saved) ? saved : pages.find((p) => !p.parent)?.id;
    const current = pages.find((p) => p.id === page);
    if (current?.parent === 'events') eventSection = current.section;
  }
  // @adena — множитель адены внутри DropAmountMultiplierByItemId.
  const rateKey = (key) => findKey(key === '@adena' ? 'DropAmountMultiplierByItemId' : key);
  const rateList = (list) => (list || []).filter(([key]) => rateKey(key));
  const rowsOf = (p) => p.subs.flatMap((x) => x.rows);
  const swOf = (id) => state.switches.find((s) => s.id === id);

  // ---------- Дерево ----------
  function drawNav() {
    const visible = pages.filter((p) => !p.parent);
    const heads = [...new Set(visible.map((p) => p.head)), 'Служебное'];
    el('pnavTree').innerHTML = [...new Set(heads)].map((h) => `<div class="pnav-h">${esc(h)}</div>` + visible.filter((p) => p.head === h).map((p) => {
      const rows = rowsOf(p);
      const count = rows.length > 1 ? `<span class="c">${rows.filter((s) => s.value).length}/${rows.length}</span>` : '';
      const on = activeTab === 'basic' && !query && (p.id === page || pages.find((x) => x.id === page)?.parent === p.id);
      return `<button class="${on ? 'on' : ''}" data-p="${p.id}" title="${esc(p.title)}"${on ? ' aria-current="page"' : ''}><span>${esc(p.short || p.title)}</span>${count}</button>`;
    }).join('')).join('');
    el('pnavTree').querySelectorAll('[data-p]').forEach((b) => (b.onclick = () => openPage(b.dataset.p)));
  }

  function openPage(id) {
    page = id;
    const p = pages.find((x) => x.id === id);
    if (p?.parent === 'events') eventSection = p.section;
    store('setPage', id);
    clearQuery();
    if (activeTab !== 'basic') document.querySelector('.tab[data-tab="basic"]').click();
    else draw();
  }

  function clearQuery() {
    query = '';
    el('setSearch').value = '';
  }

  // ---------- Строки ----------
  function swRow(s) {
    const open = opened.has(s.id);
    const tags = (s.restart ? `<i title="${s.restart === 'login' ? 'Применится после перезапуска логина' : 'Применится после перезапуска сервера'}">перезапуск</i>` : '')
      + (s.brain ? '<i title="Работает только с мозгами">мозги</i>' : '');
    return `<div class="prow${open ? ' open' : ''}" data-id="${s.id}"><button class="prow-m" aria-expanded="${open}"><span class="prow-t">${esc(s.title)}</span><span class="prow-n">${esc(s.note)}</span>${s.technical === false ? '' : `<span class="prow-k">${esc(s.file)} · ${esc(s.keys.join(', '))}</span>`}</button>
      <span class="prow-tags">${window.L2Load?.chip(s.id) ?? ''}${tags}</span><button class="switch" role="switch" aria-checked="${s.value}" aria-label="${esc(s.title)}" data-sw="${s.id}"></button></div>`;
  }

  // Строка ключа: разметка здесь, управление вешает bind — ему нужны обработчики, а не текст.
  const slots = [];
  function keyRow(x) {
    slots.push(x);
    return `<div class="prow key${x.k.id in edits ? ' edited' : ''}" data-slot="${slots.length - 1}"><div class="prow-m"><span class="prow-t">${esc(x.title)}</span>${x.hint ? `<span class="prow-n">${esc(x.hint)}</span>` : ''}<span class="prow-c" hidden></span></div><span class="prow-ctl"></span></div>${x.text ? `<textarea class="field prow-text" data-text="${slots.length - 1}" spellcheck="false"></textarea>` : ''}`;
  }

  function fillKey(row, x) {
    const mark = () => row.classList.toggle('edited', x.k.id in edits);
    const k = x.k;
    let ctl;
    if (x.choices) {
      ctl = document.createElement('select');
      ctl.className = 'knum';
      ctl.innerHTML = x.choices.map((c) => `<option${c === valOf(k) ? ' selected' : ''}>${esc(c)}</option>`).join('');
      ctl.onchange = () => { setVal(k, ctl.value); mark(); };
    } else if (k.type === 'bool' || k.secret || x.plain || !['int', 'float'].includes(k.type)) {
      ctl = control(k, mark);
    } else {
      ctl = document.createElement('input');
      ctl.className = 'field knum';
      ctl.type = 'number';
      const scale = x.scale || 1;
      ctl.value = x.scale ? Number(valOf(k)) / scale : valOf(k);
      if (x.min !== undefined) ctl.min = x.min;
      if (x.max !== undefined) ctl.max = x.max;
      if (x.step !== undefined) ctl.step = x.step;
      ctl.oninput = () => {
        const value = x.scale && ctl.value !== '' ? String(Number((Number(ctl.value) * scale).toFixed(8))) : ctl.value;
        setVal(k, value); mark();
      };
    }
    ctl.setAttribute('aria-label', x.title);
    ctl.title ||= x.hint || k.key;
    row.querySelector('.prow-ctl').appendChild(ctl);
    const c = row.querySelector('.prow-c');
    c.hidden = !!x.hint;
    c.textContent = x.plain ? k.file : k.key;
    if (x.plain && k.description) {
      const d = document.createElement('span');
      d.className = 'prow-n';
      d.textContent = d.title = k.description;
      row.querySelector('.prow-m').appendChild(d);
    }
  }

  function rateCell([key, label]) {
    const k = rateKey(key);
    const adena = key === '@adena';
    const cur = adena ? (valOf(k).match(/(?:^|;)57,([\d.]+)/) || [])[1] || '1' : valOf(k);
    const cell = document.createElement('div');
    cell.className = 'num';
    cell.title = adena ? 'DropAmountMultiplierByItemId · 57' : key;
    cell.innerHTML = `<label>${esc(label)}</label><input value="${esc(cur)}" inputmode="decimal">`;
    const inp = cell.querySelector('input');
    inp.classList.toggle('changed', k.id in edits);
    inp.oninput = () => {
      setVal(k, adena ? valOf(k).replace(/(^|;)57,[\d.]+/, `$157,${inp.value}`) : inp.value);
      inp.classList.toggle('changed', k.id in edits);
    };
    return cell;
  }
  const rateGrid = (list, cls) => {
    const grid = document.createElement('div');
    grid.className = `rates ${cls}`;
    grid.append(...list.map(rateCell));
    return grid;
  };

  const fold = (id, title) => `<button class="pfold${opened.has(id) ? ' open' : ''}" data-fold="${id}" aria-expanded="${opened.has(id)}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M6 9l6 6 6-6"/></svg>${esc(title)}</button><div class="pfold-body${opened.has(id) ? ' open' : ''}" id="fold-${id}"></div>`;

  // ---------- Страница ----------
  function modeBlock() {
    if (!state.ok) return `<div class="note">${esc(state.error)}</div>`;
    const active = state.modes.some((m) => m.active);
    const line = (active ? '' : 'Рейты и моды сейчас свои: правятся ниже и во «Все ключи».') + (state.restart ? ' Сервер работает — новое применится после перезапуска гейма.' : '');
    return `<div class="mcards">${state.modes.map((m) => `<button class="mcard${m.active ? ' on' : ''}" data-mode="${m.id}" title="${esc(m.note)}"><b>${esc(m.title)}</b><span>${esc(m.short)}</span></button>`).join('')}</div>${line ? `<p class="phint">${esc(line.trim())}</p>` : ''}`;
  }

  function huntersBlock() {
    const hm = state.huntersMax;
    if (!hm) return '';
    const selectedCount = hm.configured > 0 ? hm.configured : hm.count;
    const m = hm.machine;
    const room = m ? `На этой машине ОЗУ ${String(m.ramGb).replace('.', ',')} ГБ, ${m.threads} потоков, куча гейма ${hm.heapGb} ГБ: рекомендуем до ${m.recommended}.` : `Куча гейма на этой машине — ${hm.heapGb} ГБ.`;
    return `<div class="phunters${opened.has('hunters-note') ? ' open' : ''}"><div class="phunters-h"><span class="prow-t">${esc(hm.title)}</span><input class="field knum" id="huntersMax" value="${selectedCount}" inputmode="numeric" aria-label="${esc(hm.title)}"><small>из ${hm.top} в таблице${hm.roster ? ` · всего персонажей ${hm.roster.templates}` : ''}</small></div>
      <input type="range" id="huntersRange" min="1" max="${hm.top}" value="${hm.count}" aria-label="${esc(hm.title)}">
      ${hm.tableMismatch ? `<div class="note bad-text">Сохранён лимит ${selectedCount}, но в XML доступно ${hm.top} пешек. Настройка сохранена; нужно обновить таблицу персонажей.</div>` : ''}
      <button class="prow-m" id="huntersNote"><span class="prow-n"><span>${esc(hm.note)}</span> <span>${esc(room)}</span></span></button>
      ${(m?.warnings || []).map((w) => `<div class="note bad-text">${esc(w)}</div>`).join('')}${m && !m.fits ? `<button class="sbtn mini" id="huntersFit">Поставить ${m.recommended}</button>` : ''}</div>`;
  }

  function subsHtml(p) {
    return p.subs.map((x) => `${x.title ? `<h4 class="psub">${esc(x.title)}</h4>` : ''}${x.note ? `<p class="phint">${esc(x.note)}</p>` : ''}<div class="pgroup">${x.rows.map(swRow).join('')}${x.keys.map(keyRow).join('')}</div>`).join('');
  }

  // Один пункт дерева: растущий каталог живёт внутри раздела, карточки используют те же ключи и API.
  function drawEvents(box, current) {
    const root = pages.find((p) => p.id === 'events');
    const children = pages.filter((p) => p.parent === 'events');
    const tabs = [['catalog', 'Каталог событий'], ['rules', 'Общие правила'], ['contracts', 'Задания'], ['rewards', 'Награды и валюты']]
      .filter(([key]) => children.some((p) => p.section === key));
    if (!tabs.some(([key]) => key === eventSection)) eventSection = tabs[0]?.[0];
    const detail = current.parent === 'events' ? current : children.find((p) => p.section === eventSection);
    slots.length = 0;
    box.innerHTML = `<div class="event-hub"><div class="ppage-h"><h2>${esc(root.title)}</h2></div><p class="pabout">${esc(root.about)}</p>
      <nav class="event-tabs" aria-label="Разделы ивентов">${tabs.map(([key, title]) => `<button class="sbtn mini${key === eventSection ? ' on' : ''}" data-event-section="${key}" aria-pressed="${key === eventSection}">${esc(title)}</button>`).join('')}</nav><div id="eventContent"></div></div>`;
    const content = el('eventContent');
    if (eventSection === 'catalog' && current.id === 'events') {
      const categories = [['all', 'Все'], ['pve', 'PvE'], ['pvp', 'PvP'], ['seasonal', 'Сезонные']];
      const engine = swOf('event-engine');
      content.innerHTML = (engine?.available ? `<div class="event-engine">${swRow(engine)}</div>` : '')
        + `<div class="event-filters" aria-label="Виды ивентов">${categories.map(([key, title]) => `<button class="sbtn mini${key === eventFilter ? ' on' : ''}" data-event-filter="${key}" aria-pressed="${key === eventFilter}">${esc(title)}</button>`).join('')}</div><div class="event-cards"></div>`;
      const list = children.filter((p) => p.section === 'catalog' && (eventFilter === 'all' || p.category === eventFilter));
      content.querySelector('.event-cards').innerHTML = list.map((p) => {
        const s = swOf(p.eventSwitch);
        const paused = p.eventGroup && !swOf(p.eventGroup)?.value;
        const status = !s?.value ? 'Выключен в настройках' : paused ? 'Общая очередь выключена' : 'Включён в настройках';
        const summary = p.subs.flatMap((s) => s.keys).filter((x) => /^(Farm|Defence|Hunt)(IntervalMinutes|Minutes|Coins)$/.test(x.k.key));
        return `<article class="event-card"><div class="event-card-head"><h3>${esc(p.title)}</h3>${s ? `<button class="switch" role="switch" aria-checked="${s.value}" aria-label="${esc(s.title)}" data-sw="${s.id}"></button>` : ''}</div>
          <span class="event-status">${esc(status)}</span><p>${esc(p.about)}</p>${summary.length ? `<dl>${summary.map((x) => `<div><dt>${esc(x.title)}</dt><dd>${esc(valOf(x.k))}</dd></div>`).join('')}</dl>` : ''}<button class="sbtn event-configure" data-event-open="${p.id}">${esc('Настроить')}</button></article>`;
      }).join('') || `<p class="phint">${esc('В этой категории нет доступных событий.')}</p>`;
    } else if (detail) {
      content.innerHTML = `${detail.section === 'catalog' ? `<button class="sbtn mini" data-event-back>${esc('К каталогу')}</button>` : ''}<h3 class="event-detail-title">${esc(detail.title)}</h3><p class="pabout">${esc(detail.about)}</p>${subsHtml(detail)}${detail.calendar ? '<div id="eventCalendar"></div>' : ''}`;
      if (detail.calendar) renderCalendar(el('eventCalendar'), selected, detail.calendar);
    }
  }

  function drawPage(box) {
    const p = pages.find((x) => x.id === page);
    if (!p) { box.innerHTML = `<div class="note">${esc(state.error || 'У этой сборки настроек на страницах нет: всё — в «Все ключи».')}</div>`; return; }
    if (p.kind === 'eventHub' || p.parent === 'events') { drawEvents(box, p); return; }
    const rows = rowsOf(p);
    const onN = rows.filter((s) => s.value).length;
    const canOn = rows.some((s) => !s.value && s.bulk !== false);
    slots.length = 0;
    box.innerHTML = `<div class="ppage-h"><h2>${esc(p.title)}</h2>${rows.length > 1 && p.bulk !== false ? `<span class="c">${onN} из ${rows.length}</span><span class="spacer"></span><button class="sbtn mini" data-all="1"${canOn ? '' : ' disabled'}>Включить все</button><button class="sbtn mini" data-all="0"${onN ? '' : ' disabled'}>Выключить все</button>` : ''}</div>
      ${p.about && (p.kind !== 'mode' || state.ok) ? `<p class="pabout">${esc(p.about)}</p>` : ''}
      ${p.kind === 'mode' ? modeBlock() : ''}${p.kind === 'people' ? huntersBlock() : ''}
      <div id="rateMain"></div>${subsHtml(p)}${p.fine.length ? fold('fine', 'Тонкие рейты: группа, шансы, рейды, хербы, квесты, манор') : ''}
      ${p.kind === 'events' ? fold('cal', 'Календарь и расписание PvP') : ''}
      ${p.absent.length ? `<p class="phint pabsent">В этой сборке пока нет: ${esc(p.absent.join(', '))}.</p>` : ''}`;
    if (p.rates.length) {
      const main = el('rateMain');
      main.innerHTML = `<h4 class="psub">Рейты</h4>`;
      main.append(rateGrid(p.rates, 'five'));
      // Таблица опыта по уровням главнее RateXp и RateSp: без подписи поле «Опыт» врёт.
      if (state?.rates?.dynamicExp) main.insertAdjacentHTML('beforeend', `<p class="phint">${esc('Опыт и SP — по уровням (DynamicExpRates.xml), поля выше для них не действуют: x15 → x1.')}</p>`);
    }
    if (p.fine.length) el('fold-fine').append(rateGrid(p.fine, ''));
    if (p.kind === 'events' && opened.has('cal')) renderCalendar(el('fold-cal'), selected);
  }

  function drawSearch(box) {
    const words = norm(query).split(/\s+/).filter(Boolean);
    const hit = (...parts) => { const hay = norm(parts.join(' ')); return words.every((w) => hay.includes(w)); };
    slots.length = 0;
    const shown = new Set(state.switches.filter((s) => s.available).flatMap((s) => s.keys));
    let total = 0;
    const groups = [];
    for (const p of pages) {
      const rows = rowsOf(p).filter((s) => hit(said(s.title), said(s.note), s.file, s.keys.join(' ')));
      const keys = p.subs.flatMap((x) => x.keys);
      keys.forEach((x) => { shown.add(x.k.key); if (x.text) shown.add(x.text.key); });
      const keyHits = keys.filter((x) => hit(said(x.title), said(x.hint), x.k.key, x.k.file, x.k.description));
      const rates = [...p.rates, ...p.fine];
      rates.forEach(([key]) => shown.add(rateKey(key).key));
      const rateHits = rates.filter(([key, label]) => hit(said(label), rateKey(key).key));
      if (!rows.length && !keyHits.length && !rateHits.length) continue;
      total += rows.length + keyHits.length + rateHits.length;
      groups.push({ p, html: `<h4 class="psub"><button class="psub-go" data-p="${p.id}">${esc(p.title)}</button></h4>${rateHits.length ? `<div data-rates="${p.id}"></div>` : ''}<div class="pgroup">${rows.map(swRow).join('')}${keyHits.map(keyRow).join('')}</div>`, rateHits });
    }
    const rest = Object.values(KEYS).filter((k) => !shown.has(k.key) && hit(k.key, k.file, k.description));
    total += rest.length;
    const restHtml = rest.length ? `<h4 class="psub">Все ключи</h4><div class="pgroup">${rest.slice(0, RATE_SHOWN).map((k) => keyRow({ k, title: k.key, plain: true })).join('')}</div>${rest.length > RATE_SHOWN ? `<p class="phint">Показано ${RATE_SHOWN} из ${rest.length}. <button class="w-link" id="openKeys">Открыть в «Все ключи»</button></p>` : ''}` : '';
    box.innerHTML = `<div class="ppage-h"><h2>Поиск</h2><span class="c">найдено: ${total}</span></div>${groups.map((g) => g.html).join('')}${restHtml}${total ? '' : '<div class="empty">Ничего не нашлось.</div>'}`;
    for (const g of groups) if (g.rateHits.length) box.querySelector(`[data-rates="${g.p.id}"]`).append(rateGrid(g.rateHits, ''));
    box.querySelectorAll('.psub-go').forEach((b) => (b.onclick = () => openPage(b.dataset.p)));
    const more = el('openKeys');
    if (more) more.onclick = () => {
      const ks = el('keySearch');
      ks.value = query;
      ks.oninput({ target: ks });
      document.querySelector('.tab[data-tab="all"]').click();
    };
  }

  function draw() {
    const box = el('basic');
    if (!state) return;
    const key = query ? '?' : `${page}:${eventSection}:${eventFilter}`;
    const top = box.scrollTop;
    if (query) drawSearch(box); else drawPage(box);
    box.scrollTop = key === drawnFor ? top : 0;
    drawnFor = key;
    bind(box);
    drawNav();
  }

  // ---------- Действия ----------
  async function toggle(b) {
    const id = selected;
    const s = swOf(b.dataset.sw);
    const on = b.getAttribute('aria-checked') !== 'true';
    b.setAttribute('aria-checked', on);
    const res = await api.set_mod_switch(id, s.id, on);
    if (!res.ok) { b.setAttribute('aria-checked', !on); return toast(res.error, true); }
    toast(`${s.title}: ${on ? 'вкл' : 'выкл'}${res.restart ? ` — после перезапуска ${s.part === 'login' ? 'логина' : 'гейма'}` : ''}.`);
    await loadSchema();
  }

  async function setAll(on) {
    const id = selected;
    const list = rowsOf(pages.find((p) => p.id === page)).filter((s) => s.value !== on && (!on || s.bulk !== false));
    if (!list.length) return;
    const res = await api.set_mod_switches(id, Object.fromEntries(list.map((s) => [s.id, on])));
    if (!res.ok) return toast(res.error, true);
    toast(`${on ? 'Включено' : 'Выключено'}: ${res.changed.length}${res.restart ? ' — после перезапуска гейма' : ''}.`);
    await loadSchema();
  }

  async function applyMode(modeId) {
    const id = selected;
    const m = state.modes.find((x) => x.id === modeId);
    if (Object.keys(edits).length) return toast('Сначала сохрани или отмени правки ниже.', true);
    if (!(await askModal({ title: `Режим «${esc(m.title)}»`, ok: 'Включить', body: `<p>${esc(m.note)}.</p><p class="note">${m.keepsRates ? 'Выключатели перепишутся разом' : 'Рейты и выключатели перепишутся разом'}, прежние конфиги — в бэкап (data\\backups\\config). Вернуть свои — «Все ключи» или другой режим.</p>` }))) return;
    const res = await api.apply_server_mode(id, m.id);
    if (!res.ok) return toast(res.error, true);
    toast(`Режим «${res.title}»: правок ${res.changed}${res.skipped.length ? `, в сборке нет: ${res.skipped.join(', ')}` : ''}${res.restart ? '. Перезапусти гейм-сервер, чтобы применить.' : '.'}`);
    await loadSchema();
  }

  async function setHunters(value) {
    const res = await api.set_hunters_max(selected, value);
    if (!res.ok) { toast(res.error, true); return res; }
    toast(`Пешек в мире: ${res.count} из ${res.top}${res.cut ? ' — больше в таблице нет' : ''}. Сервер применит сам, за 10 секунд.`);
    await loadSchema();
    return res;
  }

  function bindHunters() {
    const num = el('huntersMax');
    if (!num) return;
    const hm = state.huntersMax;
    const range = el('huntersRange');
    const commit = async (value) => {
      window.L2Load?.preview(null);
      const res = await setHunters(value);
      if (!res.ok) num.value = range.value = hm.count;
    };
    range.oninput = () => {
      num.value = range.value;
      window.L2Load?.preview({ hunters: +range.value });
    };
    range.onchange = () => commit(range.value);
    num.onchange = () => commit(num.value);
    el('huntersNote').onclick = () => {
      const open = !opened.has('hunters-note');
      if (open) opened.add('hunters-note'); else opened.delete('hunters-note');
      num.closest('.phunters').classList.toggle('open', open);
    };
    const fit = el('huntersFit');
    if (fit) fit.onclick = () => commit(hm.machine.recommended);
  }

  function bind(box) {
    box.querySelectorAll('[data-event-section]').forEach((b) => (b.onclick = () => { eventSection = b.dataset.eventSection; openPage('events'); }));
    box.querySelectorAll('[data-event-filter]').forEach((b) => (b.onclick = () => { eventFilter = b.dataset.eventFilter; draw(); }));
    box.querySelectorAll('[data-event-open]').forEach((b) => (b.onclick = () => openPage(b.dataset.eventOpen)));
    box.querySelectorAll('[data-event-back]').forEach((b) => (b.onclick = () => { eventSection = 'catalog'; openPage('events'); }));
    box.querySelectorAll('[data-sw]').forEach((b) => (b.onclick = () => toggle(b)));
    // Тень на полосках: что изменится от щелчка.
    const shade = (node, switches) => {
      node.onmouseenter = () => window.L2Load?.preview({ switches: switches() });
      node.onmouseleave = () => window.L2Load?.preview(null);
    };
    box.querySelectorAll('.prow[data-id]').forEach((row) => shade(row, () => {
      const b = row.querySelector('[data-sw]');
      return { [b.dataset.sw]: b.getAttribute('aria-checked') !== 'true' };
    }));
    box.querySelectorAll('[data-mode]').forEach((b) => shade(b, () => state.modes.find((m) => m.id === b.dataset.mode)?.switches || {}));
    box.querySelectorAll('.prow[data-id] .prow-m').forEach((b) => (b.onclick = () => {
      const row = b.closest('.prow');
      const open = row.classList.toggle('open');
      b.setAttribute('aria-expanded', open);
      if (open) opened.add(row.dataset.id); else opened.delete(row.dataset.id);
    }));
    box.querySelectorAll('.prow[data-slot]').forEach((row) => fillKey(row, slots[+row.dataset.slot]));
    box.querySelectorAll('[data-text]').forEach((t) => {
      const k = slots[+t.dataset.text].text;
      t.value = valOf(k);
      t.oninput = () => setVal(k, t.value);
    });
    box.querySelectorAll('[data-all]').forEach((b) => (b.onclick = () => setAll(b.dataset.all === '1')));
    box.querySelectorAll('[data-mode]').forEach((b) => (b.onclick = () => applyMode(b.dataset.mode)));
    box.querySelectorAll('[data-fold]').forEach((b) => (b.onclick = () => {
      const id = b.dataset.fold;
      const open = !opened.has(id);
      if (open) opened.add(id); else opened.delete(id);
      b.classList.toggle('open', open);
      b.setAttribute('aria-expanded', open);
      el(`fold-${id}`).classList.toggle('open', open);
      if (id === 'cal' && open) renderCalendar(el('fold-cal'), selected);
    }));
    bindHunters();
  }

  // Прежние панели — вкладки .tab: у «Всех ключей» и шопа свой поиск, общий над ними лишний.
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => {
    activeTab = t.dataset.tab;
    if (activeTab !== 'basic') clearQuery();
    el('viewSettings').classList.toggle('own-search', activeTab === 'all' || activeTab === 'gmshop');
    el('viewSettings').classList.toggle('no-load', activeTab !== 'basic');
    if (activeTab !== 'basic') drawNav();
  }));
  el('setSearch').oninput = (e) => {
    query = e.target.value.trim();
    if (activeTab === 'basic') draw();
    else document.querySelector('.tab[data-tab="basic"]').click();
  };

  window.setHuntersMax = setHunters;

  // Имя прежнее: его зовут loadSchema, вкладка basic и «Отменить правки» в app.js.
  window.renderBasic = async function renderBasic() {
    const id = selected;
    const [r] = await Promise.all([api.server_modes(id).catch(() => null), window.L2Load?.refresh()]); // цены в строках — из прогноза
    if (id !== selected) return;
    if (!r) { el('basic').innerHTML = '<div class="note">Не удалось прочитать настройки сервера.</div>'; return; }
    state = r;
    build(r);
    draw();
  };
})();
