// Полоски нагрузки (docs/design/PULT.md, раздел 3): колонка на вкладке настроек и четыре маленькие в нижней панели.
// Серое — другие программы, сплошное — наши процессы (Api.machine_live, monitor.py, раз в секунду), штрих — прогноз того,
// чего ещё нет (Api.machine_budget, machine.py), тень — что изменится от щелчка. Прогноза может не быть: у хроники нет замеров
// или в сборке нет machine.py — тогда полоски показывают только то, что занято сейчас.
// Цифры меняются каждую секунду: подписи к ним переводим сами (T), а узлы помечены translate="no", иначе переводчик
// копил бы в кэше каждую новую строку с числом.
// Грузится раньше app.js, сверху — только document; нить опроса стартует по load.
(() => {
  const el = (id) => document.getElementById(id);
  const T = (s) => window.L2I18N?.tr(s) ?? s;
  const RES = [['cpu', 'ЦПУ', 'ЦПУ'], ['ram', 'ОЗУ', 'ОЗУ'], ['vram', 'Видеопамять', 'Видео'], ['disk', 'Диск', 'Диск']];
  const PARTS = { game: 'Гейм-сервер', login: 'Логин', db: 'База', client: 'Клиент', brain: 'Мозги' };
  const SOLID = new Set(['measured', 'group']); // замер: остальное — оценка и черновик, в строке цены с «≈»
  const HISTORY = 120; // секунд в графике
  const BUDGET_EVERY = 60; // с: свободное место и конфиги могли поменяться
  const TOP_OPEN = 5, TOP_TIGHT = 30; // с между обходами всех процессов: полоска развёрнута, памяти впритык; остальное время обхода нет
  const TIGHT = 70, OVER = 100; // те же пороги, что в machine.py
  const DISK_LOW = 5; // % от диска: меньше свободного — впритык, даже если прогноз влезает

  let live = null;
  let budget = null;
  let budgetFor = null;
  let budgetAt = 0;
  let preview = null;
  let openRes = null;
  let busy = false;
  let verdictHtml = null;
  let noClient = false;
  const hist = { cpu: [], ram: [], vram: [] };

  const fmt = (d) => new Intl.NumberFormat(window.L2I18N?.locale || 'ru-RU', { minimumFractionDigits: d, maximumFractionDigits: d });
  const F = {};
  const num = (x, d = 1) => (F[d] ||= fmt(d)).format(x);
  const big = (x) => num(x, x >= 100 ? 0 : 1);
  const level = (p) => (p >= OVER ? 'over' : p >= TIGHT ? 'tight' : 'ok');

  const ours = (part) => live?.ours?.find((p) => p.part === part);
  const resOf = (r) => budget?.resources?.find((x) => x.id === r);
  const unit = (r) => (r === 'cpu' ? 'ядер' : 'ГБ');

  // ---------- Слои ----------
  function have(r) {
    if (r === 'cpu') return resOf('cpu')?.have || live.cpu.cores;
    if (r === 'ram') return live.ram.totalGb;
    if (r === 'vram') return live.gpu?.totalGb || 0;
    return live.disk.totalGb;
  }

  // Прогноз — то из таблицы, чего ещё нет живьём: сервер запущен — он уже в сплошном, остаётся клиент.
  function planned(r) {
    const res = resOf(r);
    if (!res?.parts) return [];
    if (r === 'disk') {
      const installed = res.have > (budget.machine?.freeGb ?? res.have);
      const logs = (res.logsMbPerHour || 0) * 24 / 1024;
      return [...res.parts.filter((p) => !(p.id === 'base' && installed)), ...(logs ? [{ id: 'logs', title: 'Логи за сутки', value: logs, source: budget.hunters?.source || 'draft' }] : [])];
    }
    return res.parts.filter((p) => (p.id === 'client' ? !noClient && !ours('client') : !ours('game')));
  }

  function delta(r) {
    if (!preview || !budget) return 0;
    let sum = 0;
    if (preview.hunters != null && budget.hunters) {
      const n = preview.hunters - budget.hunters.count;
      sum += r === 'disk' ? (n * (budget.hunters.logMbPerHour || 0) * 24) / 1024 : n * (budget.hunters.perHunter?.[r] || 0);
    }
    for (const [id, on] of Object.entries(preview.switches || {})) {
      const c = budget.switches?.[id];
      if (c && c.on !== on) sum += (on ? 1 : -1) * (c.cost?.[r] || 0);
    }
    return sum;
  }

  function layers(r) {
    const h = have(r);
    let o = 0, s = 0;
    if (r === 'cpu') {
      const k = h / live.cpu.threads; // поток в единицах полоски: ядра с поправкой на скорость
      o = live.cpu.othersThreads * k;
      s = live.cpu.oursThreads * k;
    } else if (r === 'ram') {
      o = live.ram.othersGb;
      s = live.ram.oursGb;
    } else if (r === 'vram') {
      o = live.gpu?.usedGb || 0;
    } else {
      o = live.disk.usedGb;
    }
    const d = planned(r).reduce((a, p) => a + p.value, 0);
    return { o, s, d, g: delta(r), have: h, total: o + s + d };
  }

  // Диск судим по тому, влезает ли нужное в свободное: 80 % занятого на большом диске — ещё сотни гигабайт.
  function levelOf(r, seg) {
    if (!seg.have) return 'ok';
    const up = Math.max(seg.g, 0);
    if (r !== 'disk') return level(((seg.total + up) * 100) / seg.have);
    const free = live.disk.freeGb;
    const need = seg.d + up;
    return need >= free ? 'over' : need * 100 >= free * TIGHT || free * 100 < seg.have * DISK_LOW ? 'tight' : 'ok';
  }

  // ---------- Рисование ----------
  const barHtml = (id, cls = '') => `<div class="lb ${cls}" id="${id}" data-cls="${cls}"><i class="o"></i><i class="s"></i><i class="d"></i><i class="g"></i></div>`;

  function setBar(id, { o = 0, s = 0, d = 0, g = 0, have: h }, lv) {
    const bar = el(id);
    if (!bar) return;
    const pct = (x) => (h > 0 ? Math.min(100, Math.max(0, (x * 100) / h)) : 0);
    const a = pct(o), b = pct(o + s), c = pct(o + s + d);
    const [oI, sI, dI, gI] = bar.children;
    oI.style.width = `${a}%`;
    sI.style.left = `${a}%`;
    sI.style.width = `${b - a}%`;
    dI.style.left = `${b}%`;
    dI.style.width = `${c - b}%`;
    if (g > 0) { gI.className = 'g up'; gI.style.left = `${c}%`; gI.style.width = `${pct(o + s + d + g) - c}%`; }
    else if (g < 0) { const f = pct(o + s + d + g); gI.className = 'g down'; gI.style.left = `${f}%`; gI.style.width = `${c - f}%`; }
    else { gI.className = 'g'; gI.style.width = '0'; }
    bar.className = `lb ${bar.dataset.cls} ${lv || (h > 0 ? level(((o + s + d + Math.max(g, 0)) * 100) / h) : 'ok')}`;
  }

  function build() {
    const sub = (id, title) => `<div class="lsub" id="sub-${id}" hidden><span>${title}</span><span id="v-${id}" translate="no"></span></div><div class="lsub-b" id="subb-${id}" hidden>${barHtml(`lb-${id}`, 'thin')}</div>`;
    el('pload').innerHTML = `<div class="pload-h">Нагрузка на ПК</div>
      <div class="llegend"><span><i class="k o"></i>другие программы</span><span><i class="k s"></i>наше</span><span><i class="k d"></i>прогноз</span></div>
      ${RES.map(([id, title]) => `<button class="lres" data-res="${id}" aria-expanded="false"><span class="lres-h"><b>${title}</b><span id="v-${id}" translate="no"></span></span>${barHtml(`lb-${id}`)}</button>
        ${id === 'ram' ? sub('heap', 'куча гейма') : id === 'vram' ? sub('util', 'загрузка видеокарты') : ''}<div class="lparts" id="parts-${id}" translate="no" hidden></div>`).join('')}
      <div class="lv ok" id="lverdict"></div>
      <p class="phint" id="lnote" hidden></p>
      <div class="lwhatif" id="lwhatif" hidden><div class="lw-h">А что, если</div>
        <div class="lw-r"><span>Клиент на этом же ПК</span><button class="switch" role="switch" aria-checked="true" id="lClient" aria-label="Клиент на этом же ПК"></button></div></div>`;
    el('pload').querySelectorAll('.lres').forEach((b) => (b.onclick = () => {
      openRes = openRes === b.dataset.res ? null : b.dataset.res;
      draw();
    }));
    el('lClient').onclick = () => {
      noClient = !noClient;
      store('loadNoClient', noClient ? '1' : '0');
      draw();
    };
    el('dockBars').innerHTML = RES.map(([id, , short]) => `<span class="dbar"><small>${short}</small>${barHtml(`dlb-${id}`, 'mini')}</span>`).join('');
    el('dockBars').onclick = () => {
      showSection('chron');
      showCtab('set');
      document.querySelector('.tab[data-tab="basic"]').click();
    };
  }

  function spark(r) {
    const a = hist[r];
    if (!a || a.length < 2) return '';
    const pts = (k) => a.map((p, i) => `${(i * 200) / (HISTORY - 1)},${28 - Math.min(1, p[k]) * 26}`).join(' ');
    return `<svg class="spark" viewBox="0 0 200 28" preserveAspectRatio="none"><polyline points="${pts('all')}" class="all"/><polyline points="${pts('ours')}" class="ours"/></svg><div class="spark-t"><span>${T('последние')} ${a.length} ${T('с')}</span></div>`;
  }

  const partRow = (title, value, cls = '') => `<div class="${cls}"><span title="${esc(title)}">${esc(title)}</span><span>${value}</span></div>`;

  function partsHtml(r) {
    const seg = layers(r);
    const u = T(unit(r));
    const k = r === 'cpu' ? have('cpu') / live.cpu.threads : 1;
    let html = spark(r);
    if (r === 'cpu' || r === 'ram') {
      html += live.ours.map((p) => partRow(T(PARTS[p.part] || p.name), `${num(r === 'ram' ? p.gb : p.threads * k, r === 'ram' ? 1 : 2)} ${u}`)).join('');
    }
    html += planned(r).map((p) => partRow(`${T(p.title)}`, `${SOLID.has(p.source) ? '' : '≈ '}${num(p.value, p.value < 0.1 ? 2 : 1)} ${u}`, 'draft')).join('');
    if (r === 'cpu' || r === 'ram') {
      html += partRow(T('Другие программы'), `${num(seg.o, r === 'cpu' ? 2 : 1)} ${u}`, 'other');
      const top = live.top?.[r] || [];
      html += top.map((p) => partRow(p.name, `${num(r === 'ram' ? p.gb : p.threads * k, r === 'ram' ? 1 : 2)} ${u}`, 'other far')).join('');
    }
    if (r === 'vram') {
      html += partRow(T('Занято сейчас всеми'), `${num(seg.o)} ${u}`, 'other');
      if (live.gpu?.source === 'counter') html += partRow(T('Загрузку этой видеокарты не читаем'), '', 'other');
    }
    if (r === 'disk') {
      html += partRow(T('Свободно'), `${big(live.disk.freeGb)} ${u}`, 'other');
      if (live.disk.mbs != null) html += partRow(T('Диски сейчас'), `${num(live.disk.mbs)} ${T('МБ/с')}`, 'other');
      const logs = resOf('disk')?.logsMbPerHour;
      if (logs) html += partRow(T('Логи'), `≈ ${num(logs, 0)} ${T('МБ в час')}`, 'draft');
    }
    return html || partRow(T('Пока никто не занимает'), '');
  }

  function heapNow() {
    const run = last?.running === selected ? last : (typeof lastRun !== 'undefined' ? lastRun : null);
    const hs = run?.components?.game?.heapState;
    const hunters = preview?.hunters != null && budget?.hunters ? (preview.hunters - budget.hunters.count) * (budget.hunters.perHunter?.heapLive || 0) : 0;
    if (ours('game') && hs) return { s: hs.afterGcMb / 1024, g: hunters, have: hs.maxMb / 1024 };
    const h = resOf('ram')?.heap;
    return h?.have && !ours('game') ? { s: 0, d: h.used, g: hunters, have: h.have } : null;
  }

  function verdict(L) {
    const hunters = budget?.hunters;
    if (hunters && !ours('game') && hunters.count > hunters.recommended && hunters.recommended > 0) {
      return `<div class="lvt bad">${T('Этому ПК рекомендуем до')} ${hunters.recommended} ${T('пешек, сейчас')} ${hunters.count}. ${T('Запустить можно, но мир будет тормозить.')}</div><button class="sbtn mini" id="lFit">${T('Поставить')} ${hunters.recommended}</button>`;
    }
    const lv = RES.map(([r]) => levelOf(r, L[r]));
    const name = live.top?.ram?.[0];
    if (levelOf('ram', L.ram) !== 'ok' && L.ram.o > L.ram.s + L.ram.d && name) {
      return `<div class="lvt warn">${T('Память заняли другие программы:')} ${esc(name.name)}, ${num(name.gb)} ${T('ГБ')}. ${T('Закрой лишнее, если сервер будет тормозить.')}</div>`;
    }
    if (lv.includes('over')) return `<div class="lvt bad">${T('Что-то не влезает: разверни полоску и посмотри, кто её занял.')}</div>`;
    if (lv.includes('tight')) return `<div class="lvt warn">${T('Впритык, но работать будет.')}</div>`;
    return `<div class="lvt ok">${T(budget?.mark ? 'Хватает на всё, что включено.' : 'Запас есть. Это то, что занято сейчас.')}</div>`;
  }

  function draw() {
    if (!live?.ok || !el('lb-cpu')) return;
    const L = {};
    const tips = [];
    for (const [r, title] of RES) {
      const seg = layers(r);
      L[r] = seg;
      setBar(`lb-${r}`, seg, levelOf(r, seg));
      setBar(`dlb-${r}`, seg, levelOf(r, seg));
      const g = Math.abs(seg.g) >= 0.005 ? ` <em class="${seg.g > 0 ? 'up' : 'down'}">${seg.g > 0 ? '+' : '−'}${num(Math.abs(seg.g), Math.abs(seg.g) < 0.1 ? 2 : 1)}</em>` : '';
      const text = seg.have ? `${big(seg.total)} ${T('из')} ${big(seg.have)} ${T(unit(r))}` : T('нет данных');
      el(`v-${r}`).innerHTML = text + g;
      tips.push(`${T(title)}: ${text}`);
      const open = openRes === r;
      const box = el(`parts-${r}`);
      box.hidden = !open;
      const btn = el('pload').querySelector(`.lres[data-res="${r}"]`);
      btn.classList.toggle('open', open);
      btn.setAttribute('aria-expanded', open);
      if (open) box.innerHTML = partsHtml(r);
    }
    el('dockBars').title = tips.join('\n');
    const heap = heapNow();
    el('sub-heap').hidden = el('subb-heap').hidden = !heap;
    if (heap) {
      setBar('lb-heap', heap);
      el('v-heap').textContent = `${num((heap.s || 0) + (heap.d || 0) + heap.g)} ${T('из')} ${num(heap.have, 0)} ${T('ГБ')}`;
    }
    const util = live.gpu?.util;
    el('sub-util').hidden = el('subb-util').hidden = util == null;
    if (util != null) {
      setBar('lb-util', { s: util, have: 100 });
      el('v-util').textContent = `${util} %`;
    }
    const html = verdict(L);
    if (html !== verdictHtml) {
      verdictHtml = html;
      el('lverdict').innerHTML = html;
      const fit = el('lFit');
      if (fit) fit.onclick = () => window.setHuntersMax?.(budget.hunters.recommended);
    }
    const hasClient = resOf('cpu')?.parts?.some((p) => p.id === 'client');
    el('lwhatif').hidden = !hasClient;
    el('lClient').setAttribute('aria-checked', !noClient);
    const note = budget?.note || (budget ? '' : 'Прогноза нет: показано только то, что занято сейчас.');
    el('lnote').hidden = !note;
    if (note && el('lnote').dataset.note !== note) {
      el('lnote').dataset.note = note;
      el('lnote').textContent = note;
    }
  }

  // ---------- Данные ----------
  async function refreshBudget() {
    const id = selected;
    budgetFor = id;
    budgetAt = Date.now();
    const r = await api.machine_budget(id).catch(() => null);
    if (id !== selected) return;
    budget = r?.ok ? r : null;
    draw();
  }

  function remember() {
    const add = (r, all, mine, h) => {
      if (!h) return;
      hist[r].push({ all: all / h, ours: mine / h });
      if (hist[r].length > HISTORY) hist[r].shift();
    };
    add('cpu', live.cpu.pct, (live.cpu.oursThreads * 100) / live.cpu.threads, 100);
    add('ram', live.ram.usedGb, live.ram.oursGb, live.ram.totalGb);
    add('vram', live.gpu?.usedGb || 0, 0, live.gpu?.totalGb || 0);
  }

  async function tick() {
    if (busy || document.hidden || typeof selected === 'undefined') return; // свёрнутое окно не тратит машину игрока
    busy = true;
    try {
      const topEvery = openRes === 'cpu' || openRes === 'ram' ? TOP_OPEN : live?.ok && levelOf('ram', layers('ram')) !== 'ok' ? TOP_TIGHT : 0;
      const r = await api.machine_live(last?.running || selected, topEvery);
      if (r?.ok) {
        live = r;
        remember();
        draw();
      }
    } catch { /* ядро не ответило: poll в app.js скажет об этом */ } finally {
      busy = false;
    }
    if (selected !== budgetFor || Date.now() - budgetAt > BUDGET_EVERY * 1000) refreshBudget();
  }

  // Цена в строке настройки: самый заметный ресурс. Строки нет в таблице — метки нет, не мерили — не выдумываем.
  function chip(id) {
    const c = budget?.switches?.[id];
    if (!c || !live?.ok) return '';
    const parts = [['cpu', 'ЦПУ', (v) => `+${num(v, 2)}`], ['ram', 'ОЗУ', (v) => (v < 1 ? `+${Math.round(v * 1024)} ${T('МБ')}` : `+${num(v)} ${T('ГБ')}`)], ['disk', 'Диск', (v) => `+${num(v, 2)} ${T('ГБ')}`]]
      .map(([r, title, show]) => ({ title, show, v: c.cost?.[r] || 0, share: (c.cost?.[r] || 0) / (have(r) || 1) }))
      .sort((a, b) => b.share - a.share);
    const top = parts[0];
    const note = c.note ? ` title="${esc(c.note)}"` : '';
    if (!top.v) return `<i class="cost free"${note}>${T('даром')}</i>`;
    return `<i class="cost${SOLID.has(c.source) ? '' : ' draft'}"${note}>${SOLID.has(c.source) ? '' : '≈ '}${T(top.title)} ${top.show(top.v)}</i>`;
  }

  window.L2Load = {
    refresh: refreshBudget,
    preview(p) { preview = p; draw(); },
    chip,
  };

  window.addEventListener('load', () => {
    noClient = load('loadNoClient') === '1';
    build();
    tick();
    setInterval(tick, 1000);
  });
})();
