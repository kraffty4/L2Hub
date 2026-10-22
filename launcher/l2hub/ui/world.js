'use strict';

// «Мой мир» (docs/WORLD_CONTROL.md): обзор, население, напарники, разум и правила мира — поверх тех же методов Api.
// Всё внутри IIFE: app.js держит общие $, esc, api, toast, call, poll, last, byId, fmtUptime, worldId, compsOf, а имена вроде edits уже заняты.
(() => {
  const W = {
    open: false, screen: 'overview', chron: null, lastRunning: null,
    h: null, hErr: null, hBusy: false, timer: null,       // hunters(): охотники, режим, стратег, свежесть
    comps: null, compsErr: null,                           // companions()
    brain: null, brainErr: null, brainEdit: null, models: [], // brain_settings() и правки модели до «Сохранить»
    strat: null,                                           // правки стратега до «Сохранить»
    schema: null, schemaErr: null, restart: null, crGroup: 'activity',
    editsBy: {},                                           // хроника → несохранённые правки «Создателя»: переживают смену разделов
    filter: 'ALL', query: '', sort: 'level', sel: null,
    mods: null, modsErr: null,                             // phantoms_status(): где стоит модуль и свежий ли он
    map: null, mapErr: null, mapView: null, mapOffline: false, // world_map(): точки на карте; mapView — WorldMap.create
    mapKinds: new Set(['hunter', 'store', 'companion', 'player', 'superchampion', 'raidboss', 'epic']),
  };
  // Миры со своим интерфейсом вместо охотников и напарников (C4 — L2Solo, ui/l2solo.js). Регистрация по полю server
  // из chronicles.json: window.worldKinds.l2solo = {
  //   note: 'подпись в выборе мира', screens: [['map', 'Карта мира', '<path d="…"/>'], …],
  //   enter(screen, ctx) → Promise — загрузить данные экрана (при входе и когда сервер встал или лёг),
  //   render(screen, main, ctx) — нарисовать экран в main, tick(ctx) → Promise — раз в 10 с, пока раздел открыт (необязательно) }.
  // ctx = { chron, name, running, comps, rerender } — rerender() перерисовывает экран с сохранением прокрутки.
  window.worldKinds = window.worldKinds || {};
  // Хроника без фантомов и без своего интерфейса (Gracia Final, Essence) — мир «только карта».
  const MAP_ONLY = { note: 'только карта', screens: [['map', 'Карта', '<path d="M3 6.5l6-3 6 3 6-3v14l-6 3-6-3-6 3z"/><path d="M9 3.5v14M15 6.5v14"/>']],
    enter: () => loadMap(), render: (screen, main) => mapView(main), tick: () => loadMap() };
  const phantomWorld = (id) => (W.mods?.items || []).some((x) => x.id === id && x.world && x.installed);
  const kindOf = (id) => window.worldKinds[byId(id)?.server] || (W.mods && byId(id)?.installed && !phantomWorld(id) ? MAP_ONLY : undefined);
  const ctx = () => ({ chron: W.chron, name: byId(W.chron)?.name || W.chron, running: running(), comps: window.compsOf(W.chron), rerender: () => W.open && render(true) });
  // Чей мир смотреть: выбранный здесь, иначе запущенной хроники с миром, иначе первый мир. Миры — хроники с phantoms/<id>
  // и установленные хроники, у чьего сервера есть свой интерфейс мира.
  const worlds = () => [
    ...(W.mods?.items || []).filter((x) => x.world && x.installed),
    ...catalog.filter((c) => c.installed && window.worldKinds[c.server]).map((c) => ({ id: c.id, name: c.name, kind: c.server })),
    ...(W.mods ? catalog.filter((c) => c.installed && !window.worldKinds[c.server] && !phantomWorld(c.id)).map((c) => ({ id: c.id, name: c.name, kind: 'map' })) : []),
  ];
  window.worldId = () => {
    const has = (id) => worlds().some((x) => x.id === id);
    let pick = null;
    try { pick = localStorage.getItem('l2hub.world'); } catch (e) { /* без хранилища — без выбора */ }
    if (pick && has(pick)) return pick;
    if (last.running && has(last.running)) return last.running;
    if (worlds().length) return worlds()[0].id;
    return last.running && byId(last.running)?.installed ? last.running : 'ct26_highfive';
  };
  function pickWorld(id) {
    try { localStorage.setItem('l2hub.world', id); } catch (e) { /* без хранилища — до перезапуска окна */ }
    reset(id);
    enter(W.screen);
  }
  // С главной («Пульт и карта»): мир этой хроники на нужном экране; раздел открывает app.js (showSection('world')).
  window.openWorldFor = (id, screen) => {
    try { localStorage.setItem('l2hub.world', id); } catch (e) { /* без хранилища — до перезапуска окна */ }
    if (W.chron !== id) reset(id);
    if (screen) W.screen = screen;
  };
  // Есть ли у хроники мир (модуль фантомов или свой интерфейс) и какой экран открыть первым.
  window.worldEntry = (id) => {
    const K = kindOf(id);
    if (K && byId(id)?.installed) return K.screens[0][0];
    return worlds().some((x) => x.id === id) ? 'map' : null;
  };
  const edits = () => (W.editsBy[W.chron] ||= {});

  // ---------- Мелочи ----------
  const PH = { HUNT: 'На каче', TOWN: 'В городе', STORE: 'В лавке', RECRUIT: 'Идёт в пати', PARTY: 'В пати', DEAD: 'Убит' };
  // Занятие поверх фазы — activity в L2HubHunters.json (docs/PHANTOMS.md, «Занятие…» после 4.34): pvp > raid > seasonal.
  const ACT_STAGE = { REGISTERED: 'записан', ARENA: 'на арене', GATHER: 'сбор', FIGHT: 'бой', LOOT: 'лут', GOING: 'идёт к NPC', AT_NPC: 'у NPC' };
  const actText = (a) => !a ? '' : a.kind === 'pvp' ? `${a.title}: ${ACT_STAGE[a.stage] || a.stage}${a.stage === 'ARENA' ? `, убийств ${a.kills ?? 0}, смертей ${a.deaths ?? 0}` : ''}`
    : a.kind === 'raid' ? `рейд на ${a.superChampion ? 'легендарного чемпиона ' : ''}${a.title}${a.bossLevel ? ` (${a.bossLevel} ур.)` : ''}: ${ACT_STAGE[a.stage] || a.stage}`
      : `${a.title}: ${ACT_STAGE[a.stage] || a.stage}${a.npc ? `, ${a.npc}` : ''}`;
  // Настроение охотника (патч 0026): сервер считает его из состояния.
  const MOOD = { tired: 'устал', guarded: 'настороже', focused: 'занят делом', restless: 'скучает', sociable: 'хочет поболтать', calm: 'спокоен' };
  // Отношения охотника к живым игрокам (патч 0032): враг, друг или так, и последние поступки.
  const REL_KIND = { enemy: 'враг', friend: 'друг', neutral: 'знакомый' };
  const REL_WHY = { killed: 'убил', attacked: 'нападал', healed: 'хилил', revived: 'реснул', saved: 'спас', hunted: 'качались вместе', contested: 'отжал моба', apologized: 'извинился', insulted: 'послал' };
  const relationsText = (list) => (list && list.length
    ? list.map((r) => `${esc(r.name)} — ${REL_KIND[r.kind] || r.kind}${r.kills > 1 ? `, убил ${r.kills}` : ''}${r.reasons?.length ? ` (${[...new Set(r.reasons)].map((x) => REL_WHY[x] || x).join(', ')})` : ''}`).join('<br>')
    : '—');
  const DECISION = { HUNT: 'качаться тут', SPOT_UP: 'спот выше', SPOT_DOWN: 'спот ниже', TOWN: 'в город', STORE: 'в лавку', PARTY: 'искать пати' };
  const decisionText = (d) => { const a = /^AFK\s+(\d+)$/.exec(d || ''); return a ? `отдых ${a[1]} мин` : DECISION[d] || d; };
  // 1 234 567 → «1,2кк», 12 345 → «12к» — как говорят в игре.
  const money = (n) => (n == null ? '—' : n >= 1e6 ? `${(n / 1e6).toFixed(1).replace('.', ',')}кк` : n >= 1e3 ? `${Math.floor(n / 1e3)}к` : String(n));
  const tokens = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1).replace('.', ',')} млн` : n >= 1e3 ? `${Math.round(n / 1e3)} тыс.` : String(n));
  const usd = (x) => `$${x.toFixed(x < 1 ? 3 : 2)}`;
  const hhmm = (ms) => new Date(ms).toLocaleTimeString(L2I18N.locale, { hour: '2-digit', minute: '2-digit' });
  function ago(ms) { const m = Math.floor((Date.now() - ms) / 60000); return m < 1 ? 'только что' : m < 60 ? `${m} мин назад` : `${Math.floor(m / 60)} ч ${m % 60} мин назад`; }
  function median(a) { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; }
  const pill = (cls, html) => `<span class="w-pill ${cls}">${html}</span>`;
  const phaseKey = (x) => (x.phase === 'RECRUIT' || (x.party && x.phase === 'HUNT') ? 'PARTY' : x.phase);
  // Пати охотника: лидер — «ведёт пати: A, B», участник — «пати у Лидера».
  const partyText = (x) => {
    if (!x.party) return '';
    const others = (x.partyMembers || []).filter((n) => n !== x.name && n !== x.party);
    return x.party === x.name ? `ведёт пати${others.length ? `: ${others.join(', ')}` : ''}` : `пати у ${x.party}${others.length ? ` (ещё ${others.join(', ')})` : ''}`;
  };
  const running = () => last.running === W.chron;
  const supported = () => !(W.h && W.h.ok === false);
  const liveAge = () => (W.h && W.h.liveAt ? Math.round((Date.now() - W.h.liveAt) / 1000) : null);
  const stale = () => running() && W.h && W.h.ok && (liveAge() === null || liveAge() > 25);
  const ICONS = {
    overview: '<path d="M4 13h6V4H4zM14 20h6v-9h-6zM4 20h6v-4H4zM14 4v4h6V4z"/>',
    people: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.6-3.6 3.2-5.5 6.5-5.5s5.9 1.9 6.5 5.5"/><circle cx="17" cy="9" r="2.6"/><path d="M16.5 14.6c2.6.2 4.4 1.9 5 4.9"/>',
    comp: '<path d="M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"/><path d="M9 12l2 2 4-4"/>',
    mind: '<path d="M9 4a3 3 0 0 0-3 3v.5A3 3 0 0 0 4 10.3 3 3 0 0 0 5.5 15 3 3 0 0 0 9 19.5V4zM15 4a3 3 0 0 1 3 3v.5a3 3 0 0 1 2 2.8 3 3 0 0 1-1.5 4.7A3 3 0 0 1 15 19.5V4z"/>',
    creator: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
    module: '<path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M4 7.5l8 4.5 8-4.5M12 12v9"/>',
    map: '<path d="M3 6.5l6-3 6 3 6-3v14l-6 3-6-3-6 3z"/><path d="M9 3.5v14M15 6.5v14"/>',
    chronicle: '<path d="M6 3h11a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6z"/><path d="M6 3a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2"/><path d="M9 8h7M9 12h7M9 16h4"/>',
  };
  const svg = (k) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">${ICONS[k]}</svg>`;

  // Свой диалог вместо confirm(): resolve(true) — подтвердили.
  function ask({ title, body, ok, danger = false }) {
    return new Promise((resolve) => {
      const box = $('uiModalBox');
      box.className = 'modal' + (danger ? ' danger' : '');
      box.innerHTML = `<h2>${title}</h2>${body}<div class="row2"><button class="sbtn" data-a="no">Отмена</button><button class="sbtn ${danger ? 'danger' : 'primary'}" data-a="yes">${ok}</button></div>`;
      $('uiModal').classList.add('on');
      const done = (v) => { $('uiModal').classList.remove('on'); resolve(v); };
      box.querySelector('[data-a="no"]').onclick = () => done(false);
      box.querySelector('[data-a="yes"]').onclick = () => done(true);
      $('uiModal').onclick = (e) => { if (e.target.id === 'uiModal') done(false); };
      box.querySelector('[data-a="no"]').focus();
    });
  }
  const modalOpen = () => $('uiModal').classList.contains('on');

  // ---------- Загрузка данных ----------
  async function loadHunters() {
    if (W.hBusy) return; // один запрос за раз
    W.hBusy = true;
    const chron = W.chron;
    try {
      const r = await api.hunters(chron);
      if (chron !== W.chron) return;
      W.h = r; W.hErr = null;
      if (r.strategy && !W.strat) W.strat = stratFrom(r.strategy);
    } catch (e) {
      if (chron === W.chron) W.hErr = e.message || String(e);
    } finally {
      W.hBusy = false;
    }
  }
  async function loadComps() {
    try { W.comps = await api.companions(W.chron); W.compsErr = null; } catch (e) { W.compsErr = e.message || String(e); }
  }
  async function loadBrain() {
    try {
      const r = await api.brain_settings(W.chron);
      W.brain = r; W.brainErr = null;
      if (r.ok && !brainDirty()) W.brainEdit = JSON.parse(JSON.stringify(r.settings));
    } catch (e) { W.brainErr = e.message || String(e); }
  }
  async function loadSchema() {
    try { W.schema = await api.settings_schema(W.chron); W.schemaErr = null; } catch (e) { W.schemaErr = e.message || String(e); }
  }
  async function loadMap() {
    const chron = W.chron;
    try {
      const r = await api.world_map(chron, W.mapOffline);
      if (chron !== W.chron) return;
      W.map = r; W.mapErr = r.ok ? null : r.error;
    } catch (e) { if (chron === W.chron) W.mapErr = e.message || String(e); }
  }
  async function loadChron() {
    const chron = W.chron;
    try {
      const r = await api.world_chronicle(chron);
      if (chron !== W.chron) return;
      W.chronicle = r; W.chronErr = r.ok ? null : r.error;
    } catch (e) { if (chron === W.chron) W.chronErr = e.message || String(e); }
  }
  async function loadMods() {
    try { W.mods = await api.phantoms_status(); W.modsErr = null; } catch (e) { W.modsErr = e.message || String(e); }
  }
  async function loadRestart() {
    try { W.restart = await api.restart_needed(W.chron); } catch (e) { W.restart = null; }
  }

  // Данные раздела: при входе — что ещё не загружено; раз в 10 с — охотники (так сервер пишет L2HubHunters.json).
  async function enter(screen) {
    const K = kindOf(W.chron);
    if (K) { // свой интерфейс мира: экраны и данные — у него
      if (screen !== 'module' && !K.screens.some(([k]) => k === screen)) screen = K.screens[0][0];
      W.screen = screen;
      render();
      if (screen === 'module') await loadMods();
      else await Promise.resolve(K.enter?.(screen, ctx())).catch(() => null);
      if (W.open && W.screen === screen) render();
      return;
    }
    if (screen !== 'module' && !SCREENS.some(([k]) => k === screen)) screen = 'overview';
    W.screen = screen;
    render();
    const jobs = [];
    if (!W.h || ['overview', 'people', 'mind'].includes(screen)) jobs.push(loadHunters());
    if (screen === 'overview' || screen === 'comp') jobs.push(loadComps());
    if (screen === 'overview' && !W.brain) jobs.push(loadBrain());
    if (screen === 'mind' && (!W.brain || !brainDirty())) jobs.push(loadBrain());
    if (screen === 'creator') { if (!W.schema) jobs.push(loadSchema()); jobs.push(loadRestart()); }
    if (screen === 'module') jobs.push(loadMods());
    if (screen === 'map') jobs.push(loadMap());
    if (screen === 'chronicle') jobs.push(loadChron());
    await Promise.all(jobs);
    if (W.open && W.screen === screen) render();
  }
  async function tick10() {
    if (!W.open) return;
    const K = kindOf(W.chron);
    if (K) {
      if (W.screen !== 'module' && K.tick) { await Promise.resolve(K.tick(ctx())).catch(() => null); render(true); }
      return;
    }
    if (W.screen === 'map') { await loadMap(); if (W.open) render(true); return; }
    if (W.screen === 'chronicle') { await loadChron(); if (W.open) render(true); return; }
    await loadHunters();
    if (W.open && ['overview', 'people'].includes(W.screen)) render(true);
    else renderHead();
  }

  // ---------- Открыть, закрыть, смена хроники и сервера ----------
  function reset(chron) {
    Object.assign(W, { chron, h: null, hErr: null, comps: null, compsErr: null, brain: null, brainErr: null, brainEdit: null, models: [],
      strat: null, schema: null, schemaErr: null, restart: null, sel: null, lastRunning: last.running === chron, map: null, mapErr: null, chronicle: null, chronErr: null });
  }
  // «Мой мир» — раздел L2Hub, а не вид хроники: чей мир смотреть, решает app.js (window.worldId()).
  window.openWorld = async () => {
    W.open = true;
    $('viewWorld').classList.add('on');
    clearInterval(W.timer);
    W.timer = setInterval(tick10, 10000);
    // Список миров — до выбора мира: иначе на первом открытии выбор метнулся бы на тот, что виден раньше.
    if (!W.mods) { $('worldMain').innerHTML = loadingView(); await loadMods(); if (!W.open) return; }
    const id = window.worldId();
    if (W.chron !== id) reset(id);
    enter(W.screen);
  };
  function closeWorld() {
    W.open = false;
    clearInterval(W.timer);
    $('viewWorld').classList.remove('on');
  }
  window.closeWorld = closeWorld;
  // Раз в секунду из poll(): шапка, свежесть; сменилась хроника или сервер встал/лёг — перечитать.
  // Подложка скачалась (задача maps) — карту заново.
  window.worldMapReload = async () => { W.map = null; W.mapView = null; await loadMap(); render(); };
  window.worldTick = () => {
    if (!W.open || !W.mods) return; // список миров ещё грузится — выбор мира подождёт
    if (W.chron !== window.worldId()) { reset(window.worldId()); enter(W.screen); return; }
    const now = running();
    if (now !== W.lastRunning) {
      W.lastRunning = now;
      const K = kindOf(W.chron);
      if (K) Promise.resolve(W.screen !== 'module' && K.enter?.(W.screen, ctx())).catch(() => null).then(() => W.open && render(true));
      else Promise.all([loadHunters(), loadComps(), W.screen === 'creator' ? loadRestart() : null]).then(() => W.open && render(true));
    }
    renderHead();
  };
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modalOpen()) $('uiModalBox').querySelector('[data-a="no"]')?.click();
  });

  // ---------- Шапка и навигация ----------
  function renderHead() {
    const c = byId(W.chron);
    $('worldChron').textContent = c ? `· ${c.name}` : '';
    const job = last.job;
    let srv;
    if (job && !job.done && job.chronicle === W.chron) srv = pill('warn', `<i class="dot starting"></i>${esc(job.step || 'сервер меняет состояние…')}`);
    else if (!supported()) srv = pill('', '<i class="dot"></i>без живого мира');
    else if (running()) {
      const g = window.compsOf(W.chron)?.game || {};
      srv = pill('', `<i class="dot on"></i>сервер работает${g.uptime_s ? ` · ${fmtUptime(g.uptime_s)}` : ''}${g.online != null ? ` · живых ${g.online}` : ''}`);
    } else srv = pill('', '<i class="dot"></i>сервер выключен — мир стоит');
    setHtml('worldSrv', srv);
    let fresh = '';
    if (!kindOf(W.chron) && supported() && running()) {
      const age = liveAge();
      if (W.hErr) fresh = pill('bad', `нет ответа: ${esc(W.hErr)}`);
      else if (!W.h) fresh = pill('', 'загружаю…');
      else if (age === null) fresh = pill('warn', 'сервер ещё не прислал состояние охотников');
      else fresh = age <= 25 ? pill('', `живые данные · обновлено ${age} с назад`) : pill('warn', `данные устарели: сервер молчит ${age} с`);
    }
    setHtml('worldFresh', fresh);
    renderNav();
  }
  function setHtml(id, html) { const el = $(id); if (el.dataset.html !== html) { el.dataset.html = html; el.innerHTML = html; return true; } return false; }
  const SCREENS = [['overview', 'Обзор'], ['map', 'Карта'], ['people', 'Население'], ['chronicle', 'Летопись'], ['comp', 'Напарники'], ['mind', 'Разум'], ['creator', 'Создатель']];
  const WSTATE = { fresh: 'модуль свежий', stale: 'модуль устарел — пересобрать', notbuilt: 'модуль не собран' };
  function renderNav() {
    const dirty = Object.keys(edits()).length > 0;
    const sel = worlds().map((x) => {
      const c = byId(x.id);
      const thumb = c?.cover ? `<span class="thumb" style="background-image:url(assets/cover_${c.cover}.jpg)"></span>` : '<span class="thumb"></span>';
      const live = last.running === x.id ? '<i class="dot on"></i>' : '';
      const note = x.kind ? esc((window.worldKinds[x.kind] || (x.kind === 'map' ? MAP_ONLY : null))?.note || '') : WSTATE[x.state] || '';
      return `<button data-w="${x.id}" class="wpick${x.id === W.chron ? ' on' : ''}">${thumb}<span><span class="n">${esc(x.name)}</span><span class="m">${live}${note}</span></span></button>`;
    }).join('');
    const K = kindOf(W.chron);
    const own = K ? K.screens.map(([k, t, icon]) => `<button data-s="${k}" class="${k === W.screen ? 'on' : ''}"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">${icon || ICONS.overview}</svg>${esc(t)}</button>`).join('') : '';
    const html = (sel ? `<div class="wsel">${sel}</div><div class="nav-h">Мир ${esc(byId(W.chron)?.name || '')}</div>` : '') + own + (K ? [] : SCREENS).map(([k, t]) => {
      let extra = '';
      if (k === 'people' && W.h?.ok) extra = `<span class="cnt">${W.h.items.length}</span>`;
      if (k === 'comp' && W.comps?.ok) extra = `<span class="cnt">${W.comps.items.length}</span>`;
      if (k === 'mind' && supported() && W.h) extra = `<span class="cnt"><i class="dot ${mindDot()}"></i></span>`;
      if (k === 'creator' && dirty) extra = '<span class="unsaved" title="Есть несохранённые правки"></span>';
      return `<button data-s="${k}" class="${k === W.screen ? 'on' : ''}">${svg(k)}${t}${extra}</button>`;
    }).join('') + `<div class="nav-h">Для всех миров</div><button data-s="module" class="${W.screen === 'module' ? 'on' : ''}">${svg('module')}Модуль фантомов${W.mods?.items?.some((x) => x.state === 'stale') ? '<span class="unsaved" title="Где-то модуль устарел"></span>' : ''}</button>`
      + '<div class="later">Позже: «Общество» (связи и кланы ботов) и «Летопись» (история мира) — см. docs/WORLD_CONTROL.md.</div>';
    if (setHtml('worldNav', html)) {
      $('worldNav').querySelectorAll('button[data-s]').forEach((b) => (b.onclick = () => enter(b.dataset.s)));
      $('worldNav').querySelectorAll('button[data-w]').forEach((b) => (b.onclick = () => b.dataset.w !== W.chron && pickWorld(b.dataset.w)));
    }
  }

  // ---------- Тело раздела ----------
  // keep — периодическое обновление: сохранить фокус, курсор и прокрутку, чтобы список не прыгал.
  function render(keep = false) {
    if (!W.open) return;
    renderHead();
    const main = $('worldMain');
    const state = keep ? saveUi(main) : null;
    main.className = 'w-main';
    const K = kindOf(W.chron);
    if (W.screen === 'module') moduleView(main);
    else if (K) K.render(W.screen, main, ctx());
    else if (W.screen === 'map') mapView(main);
    else if (W.h && !supported()) main.innerHTML = unsupportedView();
    else if (!W.h && !W.hErr && W.screen !== 'creator') main.innerHTML = loadingView();
    else if (W.screen === 'chronicle') chronicleView(main);
    else ({ overview: overviewView, people: peopleView, comp: compView, mind: mindView, creator: creatorView })[W.screen](main);
    bindCommon(main);
    if (state) restoreUi(main, state);
  }
  function saveUi(main) {
    const a = document.activeElement;
    return { id: a && main.contains(a) ? a.id : null, start: a?.selectionStart, end: a?.selectionEnd,
      scroll: [...main.querySelectorAll('[data-keep]')].map((el) => [el.dataset.keep, el.scrollTop]) };
  }
  function restoreUi(main, s) {
    for (const [k, top] of s.scroll) { const el = main.querySelector(`[data-keep="${k}"]`); if (el) el.scrollTop = top; }
    if (s.id) { const el = $(s.id); if (el) { el.focus(); if (s.start != null && el.setSelectionRange) el.setSelectionRange(s.start, s.end); } }
  }
  function unsupportedView() {
    const c = byId(W.chron);
    return `<div class="card" style="max-width:640px;margin:40px auto;text-align:center;padding:26px">
      <div style="font-family:var(--serif);font-size:20px;color:var(--gold-hi)">В ${esc(c?.name || W.chron)} нет живого мира</div>
      <p class="w-t2" style="line-height:1.6;font-size:14px">Охотников, напарников и мозгов в этой сборке нет. Сервер отвечает: <span class="w-cmd">${esc(W.h.error)}</span></p>
      <p class="w-muted" style="font-size:13px">Живой мир есть там, где стоит модуль фантомов — выбери мир слева.</p></div>`;
  }

  // ---------- Карта мира (worldmap.js) ----------
  const MAP_KINDS = [['hunter', 'Охотники'], ['store', 'Лавки'], ['companion', 'Напарники'], ['player', 'Игроки'], ['superchampion', 'Суперчемпионы'], ['raidboss', 'Рейд-боссы'], ['epic', 'Эпики']];
  const KIND_NAME = { hunter: 'охотник', store: 'лавка', companion: 'напарник', player: 'игрок', superchampion: 'суперчемпион', raidboss: 'рейд-босс', epic: 'эпик' };
  function mapTip(p) {
    if (p.kind === 'raidboss' || p.kind === 'epic') return `<b>${esc(p.name)}</b> <span class="w-muted">${p.grand ? 'эпик' : 'рейд-босс'} · ${p.level} ур.</span>${p.town ? `<br>у ${esc(p.town)}` : ''}<br>жив`;
    if (p.kind === 'superchampion') return `<b>${esc(p.name)}</b> <span class="w-muted">суперчемпион · ${p.level} ур.</span>${p.town ? `<br>у ${esc(p.town)}` : ''}${p.since ? `<br>живёт ${ago(p.since).replace(' назад', '')}` : ''}`;
    const where = [p.spot, p.town].filter(Boolean).join(' · ');
    return `<b>${esc(p.name)}</b> <span class="w-muted">${KIND_NAME[p.kind] || p.kind}${p.level ? ` · ${p.level} ур.` : ''}</span>`
      + (p.phase ? `<br>${esc(PH[p.phase] || p.phase)}${where ? ` · ${esc(where)}` : ''}` : '')
      + (p.activity ? `<br><b>${esc(actText(p.activity))}</b>` : '')
      + (p.party ? `<br>пати: ${esc(p.party)}` : '')
      + (p.owner ? `<br>напарник у ${esc(p.owner)}` : '')
      + (p.live ? '' : `<br><span class="w-muted">${p.online === false ? 'не в игре — где вышел' : 'место на последнем сохранении'}</span>`);
  }
  function mapView(main) {
    main.className = 'w-main flush';
    const d = W.map;
    if (W.mapErr) { main.innerHTML = `<div style="padding:16px 18px">${errorView(W.mapErr)}</div>`; return; }
    if (!d) { main.innerHTML = `<div style="padding:16px 18px">${loadingView()}</div>`; return; }
    if (!d.tiles) {
      // Подложку не раздаём (лицензии нет) — «Скачать» собирает её у пользователя (Api.world_tiles_get, worldtiles.py).
      const job = last.job && last.job.action === 'maps' && !last.job.done ? last.job : null;
      main.innerHTML = `<div style="padding:16px 18px"><div class="note">${esc(d.note)}</div>${d.tilesSource ? `<div class="row2" style="margin-top:10px">${job
        ? `<span class="note">${esc(job.step)}</span>` : '<button class="sbtn primary" id="wmGet">Скачать подложку</button>'}</div>` : ''}</div>`;
      const b = main.querySelector('#wmGet');
      if (b) b.onclick = async () => { b.disabled = true; const r = await api.world_tiles_get(); if (!r.ok) { b.disabled = false; toast(r.error, true); } else toast('Качаю подложку карты — прогресс в строке состояния.'); };
      return;
    }
    const counts = Object.fromEntries(MAP_KINDS.map(([k]) => [k, d.points.filter((p) => p.kind === k).length]));
    const src = !d.db ? 'база выключена — точек нет' : d.live ? 'живые координаты от сервера' : 'места — из базы, на последнем сохранении персонажа';
    const bar = `<div class="wm-bar">${MAP_KINDS.map(([k, t]) => `<button class="wm-f k-${k}${W.mapKinds.has(k) ? ' on' : ''}" data-mk="${k}"><i></i>${t} <span>${counts[k]}</span></button>`).join('')}
      <label class="wm-off"><input type="checkbox" data-moff ${W.mapOffline ? 'checked' : ''}> и не в игре</label>
      <input class="wm-find" data-mfind placeholder="Найти персонажа" list="wmNames"><datalist id="wmNames">${d.points.map((p) => `<option value="${esc(p.name)}">`).join('')}</datalist>
      <span class="wm-src">${esc(src)}</span></div>`;
    let wrap = main.querySelector('.wm-wrap');
    if (!wrap || wrap.dataset.chron !== W.chron || !W.mapView?.alive()) {
      W.mapView?.destroy();
      main.innerHTML = `<div class="wm-wrap" data-chron="${esc(W.chron)}"><div class="wm-head"></div><div class="wm-host"></div></div>`;
      wrap = main.querySelector('.wm-wrap');
      W.mapView = window.WorldMap.create(wrap.querySelector('.wm-host'), d.tiles, { tip: mapTip });
    }
    const head = wrap.querySelector('.wm-head');
    const focused = document.activeElement?.dataset?.mfind !== undefined;
    if (!focused) head.innerHTML = bar; // не сбивать ввод в поиске при обновлении раз в 10 с
    W.mapView.setPoints(d.points.filter((p) => W.mapKinds.has(p.kind)));
    head.querySelectorAll('[data-mk]').forEach((b) => (b.onclick = () => {
      W.mapKinds.has(b.dataset.mk) ? W.mapKinds.delete(b.dataset.mk) : W.mapKinds.add(b.dataset.mk);
      mapView(main);
    }));
    const off = head.querySelector('[data-moff]');
    if (off) off.onchange = async () => { W.mapOffline = off.checked; await loadMap(); if (W.open) mapView(main); };
    const find = head.querySelector('[data-mfind]');
    if (find) find.onchange = () => W.mapView.focus(find.value.trim());
  }

  // ---------- Модуль фантомов: где стоит и свежий ли в сборке ----------
  const MSTATE = { fresh: ['yes', '✓ совпадает с phantoms/'], stale: ['warnc', '! устарел'], notbuilt: ['warnc', 'не собран'], plan: ['no', '—'], no: ['no', 'нельзя'] };
  function moduleView(main) {
    if (W.modsErr) { main.innerHTML = errorView(W.modsErr); return; }
    if (!W.mods) { main.innerHTML = loadingView(); return; }
    const rows = W.mods.items.map((x) => {
      const [cls, label] = MSTATE[x.state] || ['', x.state];
      const busyNow = last.job && !last.job.done;
      const runningX = last.running === x.id;
      const act = x.world && x.installed
        ? `<button class="sbtn" data-build="${x.id}" ${busyNow || runningX ? 'disabled' : ''} title="${runningX ? 'Сначала останови сервер' : 'scripts\\server_build.ps1 -Id ' + x.id}">Пересобрать</button>` : '';
      const built = x.world ? (x.state === 'notbuilt' ? 'ещё не собирали' : `${esc(x.builtAt || '')}${x.changed ? ` · изменено файлов: ${x.changed}` : ''}`) : '';
      return `<tr class="${x.world ? '' : 'off'}"><td><span class="n">${esc(x.name)}</span><small>${esc(x.build || x.server)}</small></td>
        <td>${x.world ? `<span class="yes">✓ ${x.data}</span><small>файлов данных</small>` : '<span class="no">—</span>'}</td>
        <td>${x.world ? `<span class="yes">✓ ${x.glue}</span><small>файлов Mobius</small>` : '<span class="no">—</span>'}</td>
        <td><span class="${cls}">${label}</span><small>${x.world ? built : esc(x.why)}</small>${x.changedFiles?.length ? `<small class="w-cmd">${x.changedFiles.map(esc).join('<br>')}</small>` : ''}</td>
        <td>${act}</td></tr>`;
    }).join('');
    main.innerHTML = `<div class="w-title"><h3 class="w-h">Модуль фантомов</h3><span class="w-pill">phantoms/core · файлов ${W.mods.coreFiles}</span></div>
      <div class="w-t2" style="font-size:13px;line-height:1.55;margin:4px 0 12px;max-width:820px">Один код охотников, напарников и мозгов на все хроники Mobius. Хроника получает модуль при сборке сервера:
        код — из <span class="w-cmd">core</span>, данные мира — свои (<span class="w-cmd">phantoms/&lt;id&gt;/dist</span>), врезки в Mobius — <span class="w-cmd">glue.patch</span>.
        «Свежий» — файлы модуля совпадают с теми, что сборка положила в сервер (<span class="w-cmd">.module_manifest.json</span>).</div>
      <div class="card" style="padding:4px 8px"><table class="mt"><tr><th>Хроника</th><th>Данные мира</th><th>Врезки</th><th>В сборке сервера</th><th></th></tr>${rows}</table></div>
      <div class="note" style="margin-top:12px;max-width:820px">Пересборка — тот же <span class="w-cmd">scripts\\server_build.ps1</span>, что из консоли: копирует модуль в исходники, накладывает патчи и собирает Ant. Сервер этой хроники должен быть выключен. Ход — в панели внизу, вывод — <span class="w-cmd">data\\logs\\server_build_&lt;id&gt;.log</span>.</div>`;
    main.querySelectorAll('[data-build]').forEach((b) => (b.onclick = async () => {
      const name = byId(b.dataset.build)?.name || b.dataset.build;
      const ok = await ask({ title: `Пересобрать ${esc(name)}?`, ok: 'Пересобрать',
        body: `<p>Сборка скопирует модуль фантомов в исходники ${esc(name)}, наложит патчи и соберёт сервер. Это займёт время; запускать хронику, пока идёт сборка, нельзя.</p>` });
      if (!ok) return;
      await call(api.rebuild_module(b.dataset.build));
    }));
  }
  function loadingView() {
    return `<div class="w-muted" style="font-size:13px;margin-bottom:10px">Загружаю мир…</div><div class="w-grid w-g4">${'<div class="card"><div class="w-skel" style="width:40%"></div><div class="w-skel" style="margin-top:10px;width:70%"></div></div>'.repeat(4)}</div>
      <div class="card" style="margin-top:12px">${'<div class="w-skel" style="margin:9px 0"></div>'.repeat(8)}</div>`;
  }
  function errorView(text) {
    return `<div class="note w-note-bad" style="max-width:640px">Не удалось получить данные мира: ${esc(text)}.<br>Показываю пусто, а не старое — чтобы не выдать прошлое за настоящее.</div>
      <div class="row2"><button class="sbtn" data-act="retry">Повторить</button></div>`;
  }
  function serverOffNote() {
    const job = last.job;
    if (job && !job.done && job.chronicle === W.chron) return `<div class="note w-note-warn">Сервер меняет состояние: ${esc(job.step || '')}. Охотники выйдут в мир, когда гейм-сервер загрузится.</div>`;
    return `<div class="note">Сервер выключен — мир стоит: охотники не качаются, напарники спят. ${W.h?.db ? 'Уровни и классы — из базы.' : 'База тоже выключена — уровней не видно.'}
      <div class="row2"><button class="sbtn primary" data-act="start">Запустить мир без клиента</button><span class="w-muted" style="font-size:12.5px">то же, что «Играть» → «Только сервер»</span></div></div>`;
  }
  function bindCommon(root) {
    root.querySelectorAll('[data-act="start"]').forEach((b) => (b.onclick = () => call(api.play(W.chron, false))));
    root.querySelectorAll('[data-act="retry"]').forEach((b) => (b.onclick = () => { W.hErr = null; enter(W.screen); }));
  }

  // ---------- Обзор ----------
  function brainComp() { return running() ? window.compsOf(W.chron)?.brain || { state: 'off' } : { state: 'off' }; }
  function mindDot() {
    const b = brainComp();
    return b.state === 'off' ? '' : b.state === 'nokey' || b.lastError ? 'nokey' : 'on';
  }
  // «Летопись» (Api.world_chronicle, game/log/L2HubChronicle.json, патч 0023 c4-interlude): лента заметных событий,
  // новые сверху, по дням; фильтр по виду. Текст готов к показу — сервер пишет его по-русски.
  const CHRON_KINDS = [['all', 'Всё'], ['boss', 'Боссы', ['raid_killed', 'super_killed', 'epic_killed', 'epic_spawned']], ['pvp', 'PvP', ['pvp_result']],
    ['events', 'События', ['seasonal_start', 'seasonal_end', 'elpies']], ['growth', 'Рост', ['level', 'profession']]];
  const CHRON_MARK = { raid_killed: '⚔', super_killed: '★', epic_killed: '☠', epic_spawned: '◆', pvp_result: '⚑', seasonal_start: '✦', seasonal_end: '✧', elpies: '❀', level: '▲', profession: '♛' };
  function chronicleView(main) {
    if (W.chronErr) { main.innerHTML = errorView(W.chronErr); return; }
    const r = W.chronicle;
    if (!r) { main.innerHTML = loadingView(); return; }
    if (!r.entries) { main.innerHTML = `<div class="note">${esc(r.note)}</div>`; return; }
    const f = CHRON_KINDS.find(([k]) => k === (W.chronFilter || 'all'));
    const list = r.entries.filter((e) => !f[2] || f[2].includes(e.kind)).slice().reverse();
    const chips = CHRON_KINDS.map(([k, t, kinds]) => `<button data-cf="${k}" class="${k === (W.chronFilter || 'all') ? 'on' : ''}">${t} <span class="w-muted">${kinds ? r.entries.filter((e) => kinds.includes(e.kind)).length : r.entries.length}</span></button>`).join('');
    let day = '';
    const rows = list.map((e) => {
      const d = new Date(e.time).toLocaleDateString(L2I18N.locale, { day: 'numeric', month: 'long' });
      const head = d !== day ? `<div class="mode-group">${(day = d)}</div>` : '';
      return `${head}<div class="w-chron k-${esc(e.kind)}"><span class="w-chron-t">${new Date(e.time).toLocaleTimeString(L2I18N.locale, { hour: '2-digit', minute: '2-digit' })}</span><span class="w-chron-m">${CHRON_MARK[e.kind] || '·'}</span><span>${esc(e.text)}${e.where ? ` <span class="w-muted">· ${esc(e.where)}</span>` : ''}</span></div>`;
    }).join('');
    main.innerHTML = `<div class="card"><h3>Летопись мира <span class="r w-muted">${r.entries.length ? `последние ${r.entries.length}` : ''}</span></h3>
      <div class="seg w-chron-f">${chips}</div>
      <div class="w-chron-list" data-keep="chron">${rows || '<div class="note">Пока пусто — летопись пишется по ходу игры: убитые боссы, ивенты, уровни охотников.</div>'}</div></div>`;
    main.querySelectorAll('[data-cf]').forEach((b) => (b.onclick = () => { W.chronFilter = b.dataset.cf; render(true); }));
  }

  // «Мир сейчас» (game/log/L2HubWorld.json, патч 0022 c4-interlude): суперчемпионы, сезонные события, ближайшие ивенты.
  const hhmmDay = (ms) => { const d = new Date(ms); const same = d.toDateString() === new Date().toDateString();
    return `${same ? '' : d.toLocaleDateString(L2I18N.locale, { day: '2-digit', month: '2-digit' }) + ' '}${d.toLocaleTimeString(L2I18N.locale, { hour: '2-digit', minute: '2-digit' })}`; };
  function worldNowCard(wn) {
    if (!wn) return '';
    const sc = wn.superChampions || {};
    const alive = (sc.alive || []).slice().sort((a, b) => a.level - b.level);
    const scTxt = !sc.enabled ? 'выключены' : alive.length ? alive.slice(0, 8).map((x) => `${esc(x.name)} <span class="w-muted">${x.level} · ${esc(x.town || '')}</span>`).join('<br>')
      + (alive.length > 8 ? `<br><span class="w-muted">и ещё ${alive.length - 8}</span>` : '') : 'сейчас нет';
    const ddmm = (ms) => new Date(ms).toLocaleDateString(L2I18N.locale, { day: '2-digit', month: '2-digit' });
    const seas = (wn.seasonal || []).map((e) => `${e.active ? '<b>' : ''}${esc(e.title.split(':')[0])}${e.active ? '</b> — идёт' : ''} <span class="w-muted">${e.active ? `до ${ddmm(e.end - 1)}` : `с ${ddmm(e.start)}`}</span>`).join('<br>') || 'нет';
    const pvp = wn.schedule?.pvp, elp = wn.schedule?.elpies;
    const next = [...(pvp?.enabled ? (pvp.events || []) : []).filter((e) => e.next).map((e) => [e.next, e.id]), ...(elp?.enabled && elp.next ? [[elp.next, 'Эльпи']] : [])].sort((a, b) => a[0] - b[0]);
    const nextTxt = next.length ? next.slice(0, 4).map(([t, n]) => `${esc(n)} <span class="w-muted">${hhmmDay(t)}</span>`).join('<br>') + ((pvp?.onlyWithPlayers || elp?.onlyWithPlayers) ? '<br><span class="w-muted">стартуют при живом игроке</span>' : '')
      : 'ничего не запланировано';
    const rbs = wn.raidBosses || [];
    const rbAlive = rbs.filter((x) => !x.grand && x.alive);
    const bands = [[1, 39], [40, 59], [60, 75], [76, 99]].map(([a, b]) => [a, b, rbAlive.filter((x) => x.level >= a && x.level <= b).length]).filter((x) => x[2]);
    const soon = rbs.filter((x) => !x.grand && !x.alive && x.respawnAt).sort((a, b) => a.respawnAt - b.respawnAt).slice(0, 4);
    const grand = rbs.filter((x) => x.grand).sort((a, b) => a.level - b.level);
    const rbCard = rbs.length ? `<div class="w-grid w-g2" style="margin-top:12px">
      <div class="card w-stat"><h3>Рейд-боссы <span class="r w-muted">живых ${rbAlive.length} из ${rbs.filter((x) => !x.grand).length}</span></h3><div class="s" style="line-height:1.6">${
        bands.map(([a, b, n]) => `${a}–${b === 99 ? '…' : b} ур.: <b>${n}</b>`).join(' · ') || 'живых нет'}${soon.length ? `<br><span class="w-muted">скоро встанут:</span> ${soon.map((x) => `${esc(x.name)} <span class="w-muted">${x.level} · ${hhmmDay(x.respawnAt)}</span>`).join(', ')}` : ''}<br><span class="w-muted">живые — на карте</span></div></div>
      <div class="card w-stat"><h3>Эпики</h3><div class="s" style="line-height:1.6">${grand.map((x) => `${esc(x.name)} <span class="w-muted">${x.level} · ${x.alive ? 'жив' : x.respawnAt ? `встанет ${hhmmDay(x.respawnAt)}` : 'спит — ждёт входа'}</span>`).join('<br>') || 'нет'}</div></div></div>` : '';
    // Олимпиада (блок olympiad, c4-interlude): только когда соревнования идут или есть герои — пустой карточки не надо.
    const oly = wn.olympiad;
    const olyCard = oly?.enabled && (oly.competition || oly.heroes?.length) ? `<div class="card w-stat" style="margin-top:12px"><h3>Олимпиада <span class="r w-muted">${oly.competition ? 'соревнования идут' : ''}</span></h3>
      ${oly.daily ? `<div class="kvline"><span>Соревнования</span><span>каждый день ${esc(oly.daily)}</span></div>` : ''}
      ${oly.periodEnd ? `<div class="kvline"><span>Период до</span><span>${ddmm(oly.periodEnd)}</span></div>` : ''}
      ${oly.nobles != null ? `<div class="kvline"><span>Дворян</span><span>${oly.nobles}${oly.registered != null ? ` · записались ${oly.registered}` : ''}</span></div>` : ''}
      ${oly.heroes?.length ? `<div class="kvline"><span>Герои</span><span>${oly.heroes.map(esc).join(', ')}</span></div>` : ''}</div>` : '';
    return rbCard + olyCard + `<div class="w-grid w-g3" style="margin-top:12px">
      <div class="card w-stat"><h3>Суперчемпионы <span class="r w-muted">${sc.enabled ? alive.length : ''}</span></h3><div class="s" style="line-height:1.6">${scTxt}</div></div>
      <div class="card w-stat"><h3>Сезонные события</h3><div class="s" style="line-height:1.6">${seas}</div></div>
      <div class="card w-stat"><h3>Ближайшие ивенты</h3><div class="s" style="line-height:1.6">${nextTxt}</div></div></div>`;
  }

  function overviewView(main) {
    if (W.hErr) { main.innerHTML = errorView(W.hErr); return; }
    const h = W.h;
    const items = h.items;
    const live = h.live;
    const phases = { HUNT: 0, TOWN: 0, STORE: 0, PARTY: 0, DEAD: 0 };
    items.forEach((x) => { if (x.phase) phases[phaseKey(x)]++; });
    const inWorld = items.filter((x) => x.phase).length;
    const started = items.filter((x) => x.level != null).length;
    const b = brainComp();
    const st = h.strategy;
    const brainTxt = b.state === 'off' ? 'мозги спят вместе с сервером' : b.state === 'nokey' ? 'нет ключа API' : b.lastError ? 'не отвечает' : 'на связи';
    const comps = W.comps?.ok ? W.comps.items : [];
    const modeSeg = ['OFF', 'ALWAYS', 'WITH_PLAYERS'].map((m) => `<button data-mode="${m}" class="${h.mode === m ? 'on' : ''}">${{ OFF: 'выкл', ALWAYS: 'всегда', WITH_PLAYERS: 'с игроками' }[m]}</button>`).join('');
    let html = running() ? '' : serverOffNote() + '<div style="height:12px"></div>';
    if (stale() && live) html += `<div class="note w-note-warn" style="margin-bottom:12px">Сервер ${liveAge()} с не обновлял L2HubHunters.json. Цифры ниже — с того момента.</div>`;
    html += `<div class="w-grid w-g4 w-top">
      <div class="card w-stat"><h3>Охотники</h3><div class="v">${live ? inWorld : h.db ? started : '—'}<small> / ${items.length}</small></div>
        <div class="s">${live ? 'в мире сейчас' : !h.db ? 'база выключена — уровней не видно' : started ? 'начали путь (из базы)' : 'ещё не начинали'}</div>
        <div class="row2"><span class="seg" id="worldMode">${modeSeg}</span></div></div>
      <div class="card w-stat"><h3>Напарники</h3><div class="v">${W.comps?.ok ? comps.length : '—'}</div>
        <div class="s">${comps.map((c) => esc(c.name)).join(', ') || (W.comps && !W.comps.ok ? esc(W.comps.error) : '…')}</div>
        <div class="s2">${comps.some((c) => c.online) ? 'кто-то в мире' : comps.length ? 'не в мире — зовут в игре' : ''}</div></div>
      <div class="card w-stat"><h3>Разум <span class="r"><i class="dot ${mindDot()}"></i></span></h3><div class="v" style="font-size:17px;line-height:1.35">${esc(b.model || W.brain?.settings?.[W.brain.settings.mode]?.model || '—')}</div>
        <div class="s">${brainTxt}${b.lastReplyAt ? ` · ответ в ${esc(b.lastReplyAt)}` : ''}</div>
        <div class="s2">стратег: ${st ? { OFF: 'выключен', WITH_PLAYERS: 'с игроками', ALWAYS: 'всегда' }[st.when] : 'нет в сборке'}${b.decisions != null ? ` · решений с запуска: ${b.decisions}` : ''}</div></div>
      <div class="card w-stat"><h3>Игроки</h3><div class="v">${h.players ?? '—'}</div>
        <div class="s">${!running() ? 'сервер выключен' : h.players ? 'живых в игре' : 'живых нет'}</div>
        <div class="s2">охотники ${{ OFF: 'выключены', ALWAYS: 'живут всегда', WITH_PLAYERS: 'живут только с игроками' }[h.mode]}</div></div>
    </div>`;
    html += worldNowCard(h.world);
    if (live) {
      const total = Object.values(phases).reduce((a, n) => a + n, 0) || 1;
      const bar = Object.entries(phases).filter(([, n]) => n).map(([p, n]) => `<i class="w-ph-${p}" style="width:${(n / total) * 100}%" title="${PH[p]}: ${n}"></i>`).join('');
      const leg = Object.entries(phases).map(([p, n]) => `<button data-f="${p}"><i class="w-sw w-ph-${p}"></i>${PH[p]} <b>${n}</b></button>`).join('');
      const earned = items.map((x) => x.earnedPerHour || 0).filter((x) => x > 0);
      const buckets = {};
      items.forEach((x) => { if (x.level) { const k = Math.floor((x.level - 1) / 5); buckets[k] = (buckets[k] || 0) + 1; } });
      const keys = Object.keys(buckets).map(Number);
      let hist = '';
      if (keys.length) {
        const max = Math.max(...Object.values(buckets));
        for (let k = Math.min(...keys); k <= Math.max(...keys); k++) hist += `<div class="bar"><em>${buckets[k] || ''}</em><i style="height:${((buckets[k] || 0) / max) * 80}px"></i><small>${k * 5 + 1}–${k * 5 + 5}</small></div>`;
      }
      const recent = items.filter((x) => x.strategy).sort((a, c) => c.strategy.at - a.strategy.at).slice(0, 5);
      const parties = {};
      items.forEach((x) => { if (x.party && x.phase !== 'DEAD') parties[x.party] = x.partyMembers && x.partyMembers.length ? x.partyMembers : [...(parties[x.party] || [x.party]), x.name]; });
      const hunterNames = new Set(items.map((x) => x.name));
      const partyRows = Object.entries(parties).map(([lead, members]) => `<div class="it"><span class="who">${esc(lead)}</span>${hunterNames.has(lead) ? '' : ' <span class="w-muted">(живой игрок)</span>'} → ${members.filter((n) => n !== lead).map(esc).join(', ') || '—'}</div>`).join('');
      html += `<div class="w-grid w-g2" style="margin-top:12px">
        <div class="card"><h3>Чем занят мир <span class="r"><button class="w-link" data-f="ALL">все охотники →</button></span></h3><div class="w-occ">${bar}</div><div class="w-legend">${leg}</div></div>
        <div class="card"><h3>Экономика</h3><div class="w-kv">
          <span>Адены у всех</span><span>${money(items.reduce((s, x) => s + (x.adena || 0), 0))}</span>
          <span>Доход с охоты, медиана</span><span>${earned.length ? `~${money(median(earned))}/ч` : '—'}</span>
          <span>Квестов в работе</span><span>${items.filter((x) => x.quest).length}</span>
          <span>Копят на профу</span><span>${items.filter((x) => x.professionDue).length}</span>
          <span>Лавок в Гиране</span><span>${items.filter((x) => x.store).length}</span></div></div>
      </div>
      <div class="w-grid w-g2" style="margin-top:12px">
        <div class="card"><h3>Уровни охотников</h3><div class="w-hist">${hist || '<span class="w-muted">—</span>'}</div></div>
        <div class="card"><h3>Последние решения стратега${st && st.when === 'OFF' ? ' <span class="r w-muted">стратег выключен</span>' : ''}</h3>
          <div class="w-feed">${recent.length ? recent.map((x) => `<div class="it"><span class="who">${esc(x.name)}</span> <span class="w-dec">${esc(decisionText(x.strategy.decision))}</span> <span class="w-muted">${ago(x.strategy.at)}</span>${x.strategy.thought ? `<br><span class="th">«${esc(x.strategy.thought)}»</span>` : ''}</div>`).join('') : '<span class="w-muted">Пока никто не решал.</span>'}</div></div>
      </div>
      <div class="card" style="margin-top:12px"><h3>Пати сейчас <span class="r w-muted">${Object.keys(parties).length}</span></h3><div class="w-feed">${partyRows || '<span class="w-muted">Никто ни с кем не в пати.</span>'}</div></div>`;
    } else if (running() && !started) {
      html += '<div class="card" style="margin-top:12px"><div class="w-blank">Охотники ещё не начинали: после сброса они выходят с 1 уровня в течение 10 секунд.</div></div>';
    }
    main.innerHTML = html;
    main.querySelectorAll('[data-f]').forEach((b2) => (b2.onclick = () => { W.filter = b2.dataset.f; enter('people'); }));
    main.querySelectorAll('#worldMode button').forEach((b2) => (b2.onclick = async () => {
      const r = await api.set_hunters_mode(W.chron, b2.dataset.mode);
      if (!r.ok) return toast(r.error, true);
      toast({ OFF: 'Охотники уйдут из мира.', ALWAYS: 'Охотники живут всегда.', WITH_PLAYERS: 'Охотники живут, пока в игре есть игроки.' }[b2.dataset.mode] + (running() ? ' Сервер подхватит за 10 секунд.' : ''));
      await loadHunters(); render(true);
    }));
  }

  // ---------- Население ----------
  function peopleView(main) {
    main.className = 'w-main flush';
    if (W.hErr) { main.className = 'w-main'; main.innerHTML = errorView(W.hErr); return; }
    const h = W.h;
    const counts = { ALL: h.items.length };
    h.items.forEach((x) => { const p = phaseKey(x) || 'NONE'; counts[p] = (counts[p] || 0) + 1; });
    const chips = [['ALL', 'Все'], ['HUNT', 'На каче'], ['TOWN', 'В городе'], ['STORE', 'В лавке'], ['PARTY', 'В пати'], ['DEAD', 'Убиты']]
      .filter(([k]) => k === 'ALL' || counts[k] || W.filter === k)
      .map(([k, t]) => `<button class="fchip ${W.filter === k ? 'on' : ''}" data-f="${k}">${k !== 'ALL' ? `<i class="w-sw w-ph-${k}"></i> ` : ''}${t} <small>${counts[k] || 0}</small></button>`).join('');
    const rows = h.items.filter((x) => (W.filter === 'ALL' || phaseKey(x) === W.filter) && x.name.toLowerCase().includes(W.query.toLowerCase()));
    const sorters = { level: (a, b) => (b.level || 0) - (a.level || 0) || a.name.localeCompare(b.name), name: (a, b) => a.name.localeCompare(b.name),
      earned: (a, b) => (b.earnedPerHour || 0) - (a.earnedPerHour || 0), adena: (a, b) => (b.adena || 0) - (a.adena || 0) };
    rows.sort(sorters[W.sort]);
    const sel = h.items.find((x) => x.name === W.sel) || null;
    const where = (x) => (x.activity ? actText(x.activity) : x.phase === 'TOWN' ? x.town : x.phase === 'STORE' ? `лавка, ${x.town}` : x.phase === 'PARTY' ? partyText(x) : x.phase === 'RECRUIT' ? 'идёт к позвавшему' : x.phase === 'DEAD' ? `убит${x.spot ? `, ${x.spot}` : ''}` : x.spot && x.party ? `${x.spot} · ${partyText(x)}` : x.spot) || 'выбирает спот';
    const tbody = rows.map((x) => `<tr data-n="${esc(x.name)}" class="${x.name === W.sel ? 'sel' : ''}">
        <td><span class="nm">${esc(x.name)}</span></td><td class="w-n">${x.level ?? '—'}</td><td>${esc(x.cls) || '<span class="w-muted">—</span>'}</td>
        <td title="${x.phase ? `${PH[x.phase]}: ${esc(where(x))}` : ''}">${x.phase ? `<span class="w-phase"><i class="w-sw w-ph-${x.phase}"></i>${esc(where(x))}</span>` : '<span class="w-muted">нет данных</span>'}</td>
        <td class="w-n">${x.adena != null ? money(x.adena) : '—'}</td><td class="w-n">${x.earnedPerHour ? money(x.earnedPerHour) : '—'}</td>
        <td>${x.strategy ? `<span class="w-dec" title="«${esc(x.strategy.thought)}»">${esc(decisionText(x.strategy.decision))}</span>` : '<span class="w-muted">—</span>'}</td></tr>`).join('');
    const anyStarted = h.items.some((x) => x.level != null);
    const table = anyStarted || !running()
      ? `<table><colgroup><col style="width:15%"><col style="width:7%"><col style="width:17%"><col style="width:26%"><col style="width:11%"><col style="width:11%"><col style="width:13%"></colgroup>
          <thead><tr><th>Имя</th><th class="w-n">Ур.</th><th>Профессия</th><th>Где и чем занят</th><th class="w-n">Адена</th><th class="w-n" title="Доход с охоты в час">В час</th><th>Стратег</th></tr></thead><tbody>${tbody}</tbody></table>${rows.length ? '' : '<div class="w-blank">Никого не нашлось — сбрось фильтр или поиск.</div>'}`
      : '<div class="w-blank">Охотники ещё не начинали путь.<br>Они выходят с 1 уровня в стартовых деревнях своих рас — после сброса это 10 секунд.</div>';
    main.innerHTML = `<div class="w-pop">
      <div class="w-pop-l">
        <div class="w-tools"><input class="search" id="worldQuery" placeholder="Найти по имени" value="${esc(W.query)}"><div class="w-chips">${chips}</div><span class="spacer"></span>
          <select id="worldSort"><option value="level">по уровню</option><option value="name">по имени</option><option value="earned">по доходу</option><option value="adena">по адене</option></select></div>
        ${running() ? '' : `<div style="padding:10px 14px 0">${serverOffNote()}</div>`}
        ${stale() && h.live ? `<div style="padding:10px 14px 0"><div class="note w-note-warn">Данные устарели: сервер ${liveAge()} с не обновлял состояние. Строки приглушены, пока не придут свежие.</div></div>` : ''}
        <div class="w-tbl ${stale() ? 'stale' : ''}" data-keep="tbl">${table}</div>
        <div class="w-tools" style="border-top:1px solid var(--line);border-bottom:0"><span class="w-muted" style="font-size:12.5px">${rows.length} из ${h.items.length} · обновляется раз в 10 с</span><span class="spacer"></span><button class="sbtn danger" id="worldResetAll">Сбросить всех</button></div>
      </div>
      <div class="w-pop-r" data-keep="card">${sel ? hunterCard(sel, h) : '<div class="w-blank">Выбери охотника в списке — здесь появится его карточка: что делает, экономика, квест, лавка и мысли стратега.</div>'}</div>
    </div>`;
    $('worldSort').value = W.sort;
    $('worldQuery').oninput = (e) => { W.query = e.target.value; render(true); };
    $('worldSort').onchange = (e) => { W.sort = e.target.value; render(true); };
    main.querySelectorAll('[data-f]').forEach((b) => (b.onclick = () => { W.filter = b.dataset.f; render(true); }));
    main.querySelectorAll('tbody tr').forEach((tr) => (tr.onclick = () => { W.sel = tr.dataset.n; render(true); }));
    $('worldResetAll').onclick = () => resetHunters(null);
    main.querySelectorAll('[data-reset]').forEach((b) => (b.onclick = () => resetHunters(b.dataset.reset)));
  }
  function hunterCard(x, h) {
    const st = h.strategy;
    const q = x.quest ? (x.quest.ready ? `${x.quest.level} ур. — сделан, несёт сдавать` : `${x.quest.level} ур. — до ${hhmm(x.quest.until)}`) : '—';
    let next = '—';
    if (x.nextThink && st && st.when !== 'OFF') {
      const m = Math.ceil((x.nextThink - Date.now()) / 60000);
      next = st.when === 'WITH_PLAYERS' && !h.players ? 'ждёт живых игроков' : m <= 0 ? (x.phase === 'HUNT' ? 'думает…' : 'когда вернётся на спот') : `через ${m} мин`;
    }
    const facts = [
      ['Уровень', x.level ?? 'ещё не начинал'], ['Профессия', esc(x.cls) || '—'],
      ['Занятие', x.phase ? `<span class="w-phase"><i class="w-sw w-ph-${x.phase}"></i>${PH[x.phase]}</span>` : '<span class="w-muted">нет данных</span>'],
      ...(x.activity ? [['Сейчас', `<b>${esc(actText(x.activity))}</b>${x.activity.since ? ` <span class="w-muted">· ${ago(x.activity.since)}</span>` : ''}`
        + (x.activity.place ? `<br>сбор: ${esc(x.activity.place)}${x.activity.leader ? ', ведёт он' : ''}` : '')
        + (x.activity.result ? `<br><span class="w-muted">${esc(x.activity.result)}</span>` : '')]] : []),
      ['Настроение', MOOD[x.mood] || '—'], ['Отношения', relationsText(x.relations)],
      ['Спот', esc(x.spot) || '—'], ['Ближайший город', esc(x.town) || '—'], ['Пати', esc(partyText(x)) || '—'],
      ['Адена', x.adena != null ? money(x.adena) : '—'], ['Доход с охоты', x.earnedPerHour ? `~${money(x.earnedPerHour)}/ч` : '—'],
      ['Квест', q], ['На профу', x.professionDue ? `не хватает ${money(x.professionDue)}` : x.phase ? 'не копит' : '—'],
    ];
    // store.kind: sell — продаёт добычу, buy — скупка «Куплю …» (docs/PHANTOMS.md, 4.29); без kind — продажа, как раньше.
    const buy = x.store?.kind === 'buy';
    const store = x.store ? `<div class="card" style="margin-top:12px"><h3>${buy ? 'Скупка' : 'Лавка'} в Гиране <span class="r w-muted">до ${hhmm(x.store.until)}</span></h3><div class="w-t2" style="font-size:13px">«${esc(x.store.title)}»${buy ? ' — покупает' : ''}</div>
      <div class="w-goods">${x.store.items.map((i) => `${buy ? 'куплю ' : ''}${esc(i.name)}${i.count > 1 ? ` ×${i.count}` : ''} — ${money(i.price)}`).join('<br>')}</div></div>` : '';
    const think = x.strategy ? `<div class="card" style="margin-top:12px"><h3>Стратег <span class="r w-muted">${ago(x.strategy.at)}</span></h3>
      <span class="w-dec">${esc(decisionText(x.strategy.decision))}</span>${x.strategy.thought ? `<div class="w-t2" style="font-size:13px;margin-top:6px;font-style:italic;line-height:1.5">«${esc(x.strategy.thought)}»</div>` : ''}
      <div class="w-muted" style="font-size:12px;margin-top:6px">следующее решение: ${next}</div></div>` : '';
    return `<div class="w-card-h"><span class="big">${esc(x.name)}</span><span class="w-muted">${x.level ? `${x.level} ур.` : ''}</span></div>
      <div class="w-facts">${facts.map(([k, v]) => `<span>${k}</span><span>${v}</span>`).join('')}</div>${store}${think}
      <div class="note" style="margin-top:12px;font-size:12px">Здесь только то, что сервер отдаёт в L2HubHunters.json: опыта в %, HP, обид и шмота там нет.</div>
      ${x.level != null ? `<div class="row2" style="margin-top:12px"><button class="sbtn danger" data-reset="${esc(x.name)}">Сбросить прогресс</button></div>` : ''}`;
  }
  async function resetHunters(name) {
    const n = W.h.items.length;
    const ok = await ask({
      title: name ? `Сбросить ${esc(name)}?` : 'Сбросить всех охотников?', danger: true, ok: name ? `Сбросить ${esc(name)}` : 'Сбросить всех',
      body: `<p>${name ? `<b>${esc(name)}</b> удалится` : `<b>Все ${n} охотников</b> удалятся`} из базы и ${name ? 'начнёт' : 'начнут'} заново с 1 уровня: уровень, профессия, адена, шмот и квесты пропадут.</p>
        <p class="w-small">Перед сбросом лаунчер сам снимет бэкап базы <span class="w-cmd">before-reset</span> — откат: «Сервис» → «Бэкапы базы», только на остановленном сервере. ${running() ? 'Сервер запущен — сброс за 10 секунд, без рестарта.' : 'Сервер выключен — сброс выполнится при старте.'}</p>`,
    });
    if (!ok) return;
    const r = await api.reset_hunters(W.chron, name ? [name] : ['ALL']);
    if (!r.ok) return toast(r.error, true);
    toast(running() ? 'Сброс — в течение 10 секунд.' : 'Сброс выполнится при старте сервера.');
    setTimeout(() => tick10(), 11000);
  }

  // ---------- Напарники ----------
  // Что умеют — по нынешним патчам 0008–0011 и 0022 (ROLE_JOB в brain.py, PhantomOrders).
  const COMP_INFO = {
    HEALER: { job: 'Лечит пати, поднимает павших, в драку не лезет; садится на MP.', can: ['хил по просьбе и сам, когда у своих мало HP', 'рес павших', 'идёт следом, ждёт по команде, садится и встаёт', 'реплики на события: твой уровень, смерть, рес, отдых'] },
    BUFFER: { job: 'Держит бафы на всей пати, немного хилит и ресает, в драку не лезет.', can: ['баф пати по просьбе и при вступлении', 'немного хила и рес', 'идёт следом, ждёт по команде, садится и встаёт', 'реплики на события: твой уровень, смерть, рес, отдых'] },
    TANK: { job: 'Держит мобов на себе.', can: ['бьёт твою цель', 'идёт следом и ждёт по команде'] },
    DAMAGE: { job: 'Бьёт цель напарника.', can: ['бьёт твою цель', 'идёт следом и ждёт по команде'] },
  };
  function compView(main) {
    if (W.compsErr) { main.innerHTML = errorView(W.compsErr); return; }
    if (!W.comps) { main.innerHTML = loadingView(); return; }
    if (!W.comps.ok) { main.innerHTML = `<div class="note">${esc(W.comps.error)}</div>`; return; }
    const cards = W.comps.items.map((c) => {
      const info = COMP_INFO[c.role] || { job: '', can: [] };
      return `<div class="card w-comp"><div class="w-card-h"><span class="big">${esc(c.name)}</span><span class="role">${{ HEALER: 'хилер', BUFFER: 'баффер', TANK: 'танк', DAMAGE: 'дамагер' }[c.role] || esc(c.role)}</span><span class="spacer"></span>
        ${c.online ? '<span class="w-pill"><i class="dot on"></i>в мире</span>' : '<span class="w-pill"><i class="dot"></i>не в мире</span>'}</div>
        <div class="w-facts"><span>Сейчас</span><span>${c.level ? `${c.level} ур. · ${esc(c.cls)}` : W.comps.db ? 'ещё не создан' : 'база выключена'}</span>
        <span>Путь класса</span><span>${esc(c.classPath.join(' → ')) || '—'}</span>
        <span>Имена в чате</span><span>${esc([c.name, ...c.aliases].join(', '))}</span></div>
        <div class="w-t2" style="font-size:13px;margin-top:10px;line-height:1.5">${esc(info.job)}</div><ul>${info.can.map((t) => `<li>${esc(t)}</li>`).join('')}</ul>
        ${c.traits ? `<div class="w-muted" style="font-size:12.5px;margin-top:8px;line-height:1.5;font-style:italic">«${esc(c.traits)}»</div>` : ''}</div>`;
    }).join('');
    main.innerHTML = `<div class="w-grid" style="grid-template-columns:repeat(2,minmax(0,1fr))">${cards}</div>
      <div class="card" style="margin-top:12px"><h3>Как с ними играть — всё в игре</h3>
        <div class="w-t2" style="font-size:13.5px;line-height:1.8">Позвать: <span class="w-cmd">.buddy</span> или Alt+B → «Напарники» → «Позвать» — прибежит, подтянется до твоего уровня и вступит в пати.<br>
        Приказы словами в чате пати, шёпотом или в белый чат рядом: <span class="w-cmd">за мной</span> <span class="w-cmd">стой</span> <span class="w-cmd">бафни</span> <span class="w-cmd">хил</span> <span class="w-cmd">рес</span> <span class="w-cmd">сядь</span> <span class="w-cmd">встань</span> <span class="w-cmd">пока</span>. Одному — по имени: <span class="w-cmd">Митяй, бафни</span>.<br>
        Поведение (опыт, лут, дистанции, реплики) — в «Создателе», группа «Поведение напарников».</div>
        <div class="note" style="margin-top:10px">Из лаунчера напарниками не управляют: они живут только рядом с тобой в игре. Здесь — справка и состояние; уровень и класс — из базы.</div></div>`;
  }

  // ---------- Разум ----------
  const STRAT_KEYS = ['when', 'HunterStrategyPerHour', 'HunterStrategyPerMinute', 'HunterStrategySayChance'];
  const stratFrom = (st) => Object.fromEntries(STRAT_KEYS.map((k) => [k, st[k]]));
  const stratDirty = () => W.strat && W.h?.strategy && STRAT_KEYS.some((k) => String(W.strat[k]) !== String(W.h.strategy[k]));
  const BRAIN_KEYS = (s) => [s.mode, s.maxTokens, s.priceIn, s.priceOut, s.api.baseUrl, s.api.model, s.local.baseUrl, s.local.model].map(String).join('|');
  const brainDirty = () => W.brain?.ok && W.brainEdit && BRAIN_KEYS(W.brainEdit) !== BRAIN_KEYS(W.brain.settings);
  function mindView(main) {
    if (W.brainErr) { main.innerHTML = errorView(W.brainErr); return; }
    if (!W.brain) { main.innerHTML = loadingView(); return; }
    if (!W.brain.ok) { main.innerHTML = `<div class="note">${esc(W.brain.error)}</div>`; return; }
    const b = brainComp();
    const s = W.brainEdit;
    const st = W.h?.strategy;
    if (st && !W.strat) W.strat = stratFrom(st);
    let state;
    if (!running()) state = ['var(--off)', 'Мозги спят вместе с сервером', 'Сервис мозгов поднимается вместе с сервером. Модель и тариф можно менять и сейчас.'];
    else if (b.state === 'nokey' || (s.mode === 'api' && !W.brain.key)) state = ['var(--warn)', 'Мир живёт без LLM: нет ключа', `Охотники качаются, торгуют и ходят в пати по правилам, фейки и напарники отвечают заготовками. Положи ключ одной строкой в data\\brain\\${esc(s.api.keyFile)} — подхватится со следующей реплики.`];
    else if (b.state === 'off') state = ['var(--bad)', 'Сервис мозгов не отвечает', 'Мир не остановился: решают правила, реплики — заготовки. Перезапуск сервера поднимет мозги заново.'];
    else if (b.lastError) state = ['var(--bad)', 'LLM недоступна', `Последняя ошибка: <span class="w-cmd">${esc(b.lastError)}</span>. Мир не остановился: решают правила, реплики — заготовки.`];
    else if (st && st.when === 'OFF') state = ['var(--warn)', 'LLM на связи, стратег выключен', 'Фейки и напарники говорят через модель, охотники решают по правилам — это не остановка мира.'];
    else {
      const think = !st ? '' : st.when === 'ALWAYS' ? ' Стратег думает всегда.' : W.h.players ? ` Стратег думает: живых игроков — ${W.h.players}.` : ' Стратег ждёт живых игроков: без них решают правила, запросы не тратятся.';
      state = ['var(--ok)', `LLM на связи: ${esc(b.model || '')}`, `${b.lastReplyAt ? `Последний ответ в ${esc(b.lastReplyAt)}, ` : ''}ошибок с запуска — ${b.errors ?? 0}.${think}`];
    }
    const m = s.mode;
    const part = s[m];
    const u = W.brain.usage;
    const urow = (t, x) => `<span>${t}</span><span>${x.requests}</span><span>${tokens(x.in + x.out)}</span><span>${x.cost ? usd(x.cost) : '—'}</span>`;
    let stratCard = '<div class="card"><h3>Стратегия охотников</h3><div class="note">В этой сборке нет мозгов-стратега (патч 0019).</div></div>';
    if (st) {
      const perH = Number(W.strat.HunterStrategyPerHour), perM = Math.min(Number(W.strat.HunterStrategyPerMinute), st.rateCap);
      let cost = 'Выключен — запросов к модели нет.';
      if (W.strat.when !== 'OFF' && perH >= 1 && perM >= 1) {
        const n = Math.min(perH * st.hunters, perM * 60);
        cost = `Потолок: ≈ ${n} решений в час на ${st.hunters} охотников · ≈ ${tokens(n * (st.tokensIn + st.tokensOut))} токенов`;
        if (st.mode === 'local') cost += ' · своя модель — бесплатно';
        else if (st.priceIn || st.priceOut) cost += ` · ≈ ${usd((n * (st.tokensIn * st.priceIn + st.tokensOut * st.priceOut)) / 1e6)} в час игры`;
        cost += W.strat.when === 'WITH_PLAYERS' ? '. Без живых игроков — ноль.' : '.';
      }
      const num = (label, hint, key, lo, hi) => `<div class="opt"><div class="l">${label}<small>${hint}</small></div><input class="field ${String(W.strat[key]) !== String(st[key]) ? 'chg' : ''}" style="width:64px" data-sk="${key}" value="${esc(W.strat[key])}" inputmode="numeric" data-lo="${lo}" data-hi="${hi}"></div>`;
      stratCard = `<div class="card"><h3>Стратегия охотников</h3>
        <div class="opt"><div class="l">Когда думать</div><span class="seg" id="worldStratWhen">${[['OFF', 'выкл'], ['WITH_PLAYERS', 'с игроками'], ['ALWAYS', 'всегда']].map(([k, t]) => `<button data-w="${k}" class="${W.strat.when === k ? 'on' : ''}">${t}</button>`).join('')}</span></div>
        ${num('Решений в час на охотника', '1–60, по умолчанию 6', 'HunterStrategyPerHour', 1, 60)}
        ${num('Запросов в минуту на всех', `1–60; мозги дают стратегу до ${st.rateCap}`, 'HunterStrategyPerMinute', 1, 60)}
        ${num('Сказать мысль вслух, %', '0–100, если рядом живой игрок', 'HunterStrategySayChance', 0, 100)}
        <div class="row2"><button class="sbtn primary" id="worldStratSave" ${stratDirty() ? '' : 'disabled'}>Сохранить</button><span class="hint">${stratDirty() ? 'не сохранено' : ''}</span><span class="w-muted" style="font-size:12px">сервер подхватит на ходу</span></div>
        <div class="note" style="margin-top:10px">${cost}</div></div>`;
    }
    main.innerHTML = `<div class="w-state"><span class="ic" style="border-color:${state[0]}"><i class="dot" style="background:${state[0]}"></i></span><div><b>${state[1]}</b><p>${state[2]}</p></div></div>
      <div class="w-grid w-g3">
        <div class="card"><h3>Модель <span class="r seg" id="worldBrainMode"><button data-m="api" class="${m === 'api' ? 'on' : ''}">облако</button><button data-m="local" class="${m === 'local' ? 'on' : ''}">своя</button></span></h3>
          <div class="opt"><div class="l">Адрес<small>${m === 'api' ? 'OpenAI-совместимый API' : 'LM Studio :1234, Ollama :11434'}</small></div></div>
          <input class="field" style="width:100%" id="worldBrainUrl" value="${esc(part.baseUrl)}">
          ${m === 'local' ? '<div class="row2" style="margin-top:6px"><button class="sbtn" data-preset="http://127.0.0.1:1234/v1">LM Studio</button><button class="sbtn" data-preset="http://127.0.0.1:11434/v1">Ollama</button></div>' : ''}
          <div class="opt"><div class="l">Модель<small>${m === 'api' ? 'например deepseek-flash' : 'пусто — первая загруженная'}</small></div></div>
          <input class="field" style="width:100%" id="worldBrainModel" list="worldModelList" value="${esc(part.model)}"><datalist id="worldModelList">${W.models.map((x) => `<option value="${esc(x)}">`).join('')}</datalist>
          ${m === 'api' ? `<div class="opt"><div class="l">Ключ<small>${esc(s.api.keyFile)} в папке мозгов; сам ключ здесь не показывается</small></div>${W.brain.key ? '<span class="ok-text">есть</span>' : '<span class="bad-text">нет</span>'}</div>` : ''}
          <div class="opt"><div class="l">Длина ответа<small>токенов, 16–1024</small></div><input class="field" style="width:70px" id="worldBrainTokens" value="${esc(s.maxTokens)}"></div>
          <div class="row2"><button class="sbtn" id="worldBrainModels">Список моделей</button><button class="sbtn" id="worldBrainTest" title="Один настоящий запрос к модели">Проверить</button><button class="sbtn primary" id="worldBrainSave" ${brainDirty() ? '' : 'disabled'}>Сохранить</button></div>
          <div class="w-muted" style="font-size:12px;margin-top:8px">${brainDirty() ? '<span class="hint">не сохранено · </span>' : ''}Применяется со следующей реплики, без перезапуска.</div></div>
        ${stratCard}
        <div class="card"><h3>Расходы</h3>
          <div class="w-usage"><span class="h"></span><span class="h">запросов</span><span class="h">токенов</span><span class="h">цена</span>${urow('Сегодня', u.today)}${urow('Неделя', u.week)}${urow('Всё время', u.all)}</div>
          <div class="opt" style="margin-top:8px"><div class="l">Тариф, $ за 1 млн токенов<small>вход / выход — с сайта сервиса</small></div><input class="field" style="width:56px" id="worldPriceIn" value="${esc(s.priceIn || '')}"><input class="field" style="width:56px" id="worldPriceOut" value="${esc(s.priceOut || '')}"></div>
          ${st ? `<div class="opt"><div class="l">В среднем на запрос<small>${st.requests >= 10 ? `по usage.json, ${st.requests} запросов` : 'оценка, запросов пока мало'}</small></div><span class="w-t2">${st.tokensIn} / ${st.tokensOut}</span></div>` : ''}
          <div class="opt"><div class="l">С запуска сервиса мозгов<small>ответов · молчал · ошибок · решений</small></div><span class="w-t2">${b.state === 'off' ? '—' : `${b.replies ?? '—'} · ${b.silent ?? '—'} · ${b.errors ?? '—'} · ${b.decisions ?? '—'}`}</span></div>
          <div class="w-muted" style="font-size:12px;margin-top:8px">Лимитов в деньгах сервер не знает: есть потолки запросов (40 в минуту на всё${st ? `, стратегу — до ${st.rateCap}` : ''}). Тариф нужен для оценки цены.</div></div>
      </div>`;
    const edit = () => { renderHead(); };
    main.querySelectorAll('#worldBrainMode button').forEach((x) => (x.onclick = () => { W.brainEdit.mode = x.dataset.m; render(true); }));
    main.querySelectorAll('[data-preset]').forEach((x) => (x.onclick = () => { W.brainEdit.local.baseUrl = x.dataset.preset; render(true); }));
    $('worldBrainUrl').onchange = (e) => { W.brainEdit[m].baseUrl = e.target.value.trim(); render(true); };
    $('worldBrainModel').onchange = (e) => { W.brainEdit[m].model = e.target.value.trim(); render(true); };
    $('worldBrainTokens').onchange = (e) => { W.brainEdit.maxTokens = Number(e.target.value) || 0; render(true); };
    $('worldPriceIn').onchange = (e) => { W.brainEdit.priceIn = Number(e.target.value.replace(',', '.')) || 0; render(true); };
    $('worldPriceOut').onchange = (e) => { W.brainEdit.priceOut = Number(e.target.value.replace(',', '.')) || 0; render(true); };
    $('worldBrainModels').onclick = async () => {
      const r = await api.brain_models(W.chron, W.brainEdit.mode, W.brainEdit[W.brainEdit.mode].baseUrl);
      if (!r.ok) return toast(r.error, true);
      W.models = r.models;
      render(true);
      toast(r.models.length ? `Моделей: ${r.models.length} — выбери в поле «Модель».` : 'Сервер ответил, но моделей нет: загрузи модель.', !r.models.length);
    };
    $('worldBrainTest').onclick = async () => {
      if (brainDirty()) return toast('Сначала сохрани правки модели: проверка идёт по сохранённым настройкам.', true);
      toast('Спрашиваю модель…');
      const r = await api.brain_test(W.chron);
      if (!r.ok) return toast(`Не ответила: ${r.error}`, true);
      toast(`${r.model}: «${r.reply}» за ${(r.ms / 1000).toFixed(1)} с`);
      await loadBrain(); render(true);
    };
    $('worldBrainSave').onclick = async () => {
      const r = await api.save_brain_settings(W.chron, W.brainEdit);
      if (!r.ok) return toast(r.error, true);
      toast(W.brainEdit.mode === 'api' ? 'Сохранил: мозги в облаке.' : 'Сохранил: своя модель на этом ПК.');
      W.brainEdit = null;
      await Promise.all([loadBrain(), loadHunters()]);
      render(true);
    };
    if (st) {
      main.querySelectorAll('#worldStratWhen button').forEach((x) => (x.onclick = () => { W.strat.when = x.dataset.w; render(true); }));
      main.querySelectorAll('[data-sk]').forEach((i) => (i.onchange = () => { W.strat[i.dataset.sk] = i.value.trim(); render(true); }));
      $('worldStratSave').onclick = async () => {
        const r = await api.set_hunter_strategy(W.chron, W.strat);
        if (!r.ok) return toast(r.error, true);
        toast({ OFF: 'Стратег выключен: охотники живут по правилам.', WITH_PLAYERS: 'Сохранил: стратег думает, пока в игре есть игроки.', ALWAYS: 'Сохранил: стратег думает всегда.' }[W.strat.when] + (running() ? ' Сервер подхватит на ходу.' : ''));
        W.strat = null;
        await loadHunters();
        render(true);
      };
    }
    edit();
  }

  // ---------- Создатель ----------
  // Метаданные — здесь: в ini нет диапазонов и признака «на ходу». На ходу сервер перечитывает только PhantomHunters.ini (раз в 10 с),
  // остальное — после перезапуска гейм-сервера. Показываются только ключи, которые есть в схеме этой сборки.
  const GROUPS = [['activity', 'Активность охотников'], ['economy', 'Экономика и снабжение'], ['progress', 'Охота и прогрессия'],
    ['players', 'Взаимодействие с игроками'], ['companions', 'Поведение напарников'], ['ai', 'Параметры ИИ']];
  const META = {
    HuntersMode: ['activity', 'Когда живут охотники', 'enum', ['OFF', 'ALWAYS', 'WITH_PLAYERS'], 'OFF — их нет в мире, ALWAYS — пока запущен сервер, WITH_PLAYERS — только пока в игре есть живой игрок.'],
    HuntersIdleTimeout: ['activity', 'Уходят из мира без игроков через, с', 'int', [0, null], 'Только для «с игроками»: столько ждут после выхода последнего игрока.'],
    HuntersPerSpot: ['activity', 'Охотников на спот', 'int', [1, null], 'Больше не ставится, пока есть свободные споты того же уровня.'],
    HunterTownInterval: ['activity', 'Заход в город раз в, мин', 'int', [1, null], 'Даже если сосов и места хватает.'],
    HunterRespawnDelay: ['activity', 'Лежит убитым, с', 'int', [0, null], 'Потом возвращается в город.'],
    HunterReviveWait: ['activity', 'Ждёт реса, если рядом живой, с', 'int', [0, null], 'Живой игрок (не враг) в 1500 от трупа — лежит дольше: вдруг реснёт. На каст реса ждёт до конца в любом случае. 0 — не ждать.'],
    DropAmountMultiplierByItemId: ['economy', 'Адена с мобов (для всех)', 'list', null, 'Rates.ini: «57,3» — адена ×3. Касается и живых игроков.'],
    HunterDropChanceMultiplier: ['economy', 'Шанс вещей с мобов охотников', 'float', [0, null], 'Только охотникам — чтобы было что ставить в лавку.'],
    HunterShotBudget: ['economy', 'Бюджет на сосы, % свободной адены', 'int', [0, 100], 'Мало денег — бьёт без сосов, как новичок.'],
    HunterShotCount: ['economy', 'Запас сосов', 'int', [0, null], 'Покупает в городе по цене магазина.'],
    HunterAmmoCount: ['economy', 'Запас стрел у лучников', 'int', [0, null], 'Покупаются первыми: без них лук не стреляет.'],
    HunterPotionCount: ['economy', 'Запас хилок', 'int', [0, null], 'AutoUse пьёт их на 40 % HP.'],
    HunterTeleportFee: ['economy', 'Телепорт с 40-го уровня', 'int', [0, null], 'До 40-го бесплатно. Та же цена у телепорта игрока на спот охотника с Alt+B.'],
    HunterProfessionPrices: ['economy', 'Цена профессии', 'list', null, '«уровень:цена;…» — не накопил, качается без профы и копит.'],
    HunterStoreMinPrices: ['economy', 'Порог вещи для лавки', 'list', null, '«уровень:адена;…» — дороже порога не сдаёт NPC, а продаёт в Гиране.'],
    HunterMaxStores: ['economy', 'Лавок одновременно', 'int', [0, null], ''],
    HunterStoreMinMinutes: ['economy', 'Лавка сидит от, мин', 'int', [1, null], ''],
    HunterStoreMaxMinutes: ['economy', 'Лавка сидит до, мин', 'int', [1, null], ''],
    RateXp: ['progress', 'Опыт (для всех)', 'int', [1, null], 'Rates.ini — касается и живых игроков.'],
    RateSp: ['progress', 'SP (для всех)', 'int', [1, null], 'Rates.ini.'],
    DeathDropChanceMultiplier: ['progress', 'Шанс дропа (для всех)', 'float', [0, null], 'Rates.ini.'],
    DeathDropAmountMultiplier: ['progress', 'Количество дропа (для всех)', 'float', [0, null], 'Rates.ini.'],
    HunterQuests: ['progress', 'Квесты-симуляция', 'bool', null, 'На уровнях из списка берёт квест, через 15–25 мин сдаёт в городе.'],
    HunterQuestLevels: ['progress', 'Уровни квестов', 'list', null, 'Через запятую.'],
    HunterQuestMinutes: ['progress', 'Длительность квеста, мин', 'list', null, '«от,до».'],
    HunterQuestReward: ['progress', 'Награда квеста', 'int', [0, null], 'Награда = это × уровень² адены (40 → 16 тыс. на 20-м).'],
    HunterLfm: ['players', 'Отклик на «лфм»', 'bool', null, 'Слышат «лфм / нужен хил / танк / дд» и просятся в пати.'],
    PhantomGeneralChat: ['players', 'Белый чат рядом', 'bool', null, 'Напарники из пати отвечают вслух, охотник — по имени.'],
    PhantomOverhearChance: ['players', 'Охотник встревает сам, %', 'int', [0, 100], 'Без имени и только если рядом нет других живых.'],
    PhantomOverhearCooldown: ['players', 'Один охотник встревает не чаще, с', 'int', [0, null], ''],
    PhantomOverhearPlayerCooldown: ['players', 'На одного игрока не чаще, с', 'int', [0, null], ''],
    FakePlayerBrainSayRange: ['players', 'Фейк отвечает в белый чат на расстоянии', 'int', [0, null], '0 — выключить.'],
    HunterInitiative: ['players', 'Охотник заговаривает первым', 'bool', null, 'Заметил живого игрока рядом — иногда сам здоровается или говорит по ситуации. Без мозгов молчит.'],
    HunterInitiativeRange: ['players', 'Замечает игрока на расстоянии', 'int', [100, null], 'Игрок должен побыть рядом пару секунд: пролёт мимо не в счёт.'],
    HunterInitiativeChanceKnown: ['players', 'Шанс со знакомым, %', 'int', [0, 100], 'Знакомый — кто уже писал этому охотнику. × общительность охотника (0,2–1,5).'],
    HunterInitiativeChanceStranger: ['players', 'Шанс с незнакомым, %', 'int', [0, 100], '× общительность охотника.'],
    HunterInitiativeHunterCooldown: ['players', 'Один охотник заговаривает первым не чаще, с', 'int', [0, null], 'С кем угодно.'],
    HunterInitiativePairCooldown: ['players', 'С тем же игроком — не чаще, с', 'int', [0, null], 'Переживает рестарт сервера.'],
    HunterInitiativePlayerGap: ['players', 'К одному игроку любой охотник — не чаще, с', 'int', [0, null], 'Двое разом не здороваются.'],
    HunterInitiativePlayerPerHour: ['players', 'Обращений к одному игроку в час', 'int', [0, null], 'От всех охотников вместе; 0 — выключить.'],
    HunterInitiativeAnswerWait: ['players', 'Ждёт ответа, с', 'int', [10, null], 'Не ответил — этот охотник больше не заговаривает с ним первым, пока игрок сам не напишет.'],
    HunterInitiativeRecentTalk: ['players', 'Говорили недавно — молчит, с', 'int', [0, null], 'Разговор только что был — первым не лезет.'],
    HunterChatQuiet: ['players', 'Тишина в округе после реплики, с', 'int', [0, null], 'Сказал кто-то из фантомов вслух — другие рядом молчат с непрошеным: мысли вслух, «о, опять ты», встрять, заговорить первым. Ответы игроку не ждут. 0 — выключить.'],
    HunterChatSpeakerGap: ['players', 'Сам говоривший молчит, с', 'int', [0, null], 'Следующая непрошеная реплика того же фантома — не раньше.'],
    HunterChatTopicGap: ['players', 'Та же тема рядом — не чаще, с', 'int', [0, null], 'Например, две мысли вслух подряд от соседей по споту.'],
    HunterRelations: ['players', 'Отношения охотников к игрокам', 'bool', null, 'Обида (убил, нападал) и благодарность (хилил, реснул, спас, качались в пати) копятся и остывают. Враг — отказ в пати и «о, опять ты», друг — заговаривает чаще. Выкл — как раньше: убил хоть раз — враг навсегда.'],
    HunterRelationHalfLife: ['players', 'Обида и благодарность остывают вдвое за, ч', 'int', [0, null], 'Часы игры охотника: пока сервер выключен, не остывают. 0 — не остывают.'],
    HunterNeighbors: ['players', 'Охотники помогают и спорят между собой', 'bool', null, 'Хилеры-охотники хилят и ресают соседей-охотников (врагам — нет); «отжал моба» — обида тому, кто стал бить чужого моба (охотнику или тебе).'],
    HunterBanter: ['players', 'Перепалки и приветствия охотников', 'bool', null, 'Встретились враги — подкалывают друг друга, друзья — здороваются. Только если рядом живой игрок и вокруг тихо. Две реплики модели на встречу.'],
    HunterBanterChance: ['players', 'Шанс перепалки при встрече, %', 'int', [0, 100], '× общительность охотника.'],
    HunterBanterCooldown: ['players', 'Одна пара охотников — не чаще, с', 'int', [0, null], 'Бросок тоже: не выпало — молчат до следующего раза.'],
    HunterParties: ['players', 'Пати охотников между собой', 'bool', null, 'Друзья собираются, враги — никогда, остальные — бросок. Лидер качается на своём споте, остальные ходят с ним. Ушёл лидер с кача или время вышло — пати распускается.'],
    HunterPartyMax: ['players', 'Охотников в такой пати', 'int', [2, 9], 'Вместе с лидером.'],
    HunterPartyChance: ['players', 'Шанс собраться: незнакомые и «сложные», %', 'int', [0, 100], '× общительность того, кто зовёт.'],
    HunterPartyChanceFriend: ['players', 'Шанс собраться: друзья, %', 'int', [0, 100], ''],
    HunterPartyMinutes: ['players', 'Пати охотников живёт, мин', 'list', null, '«от,до».'],
    HunterBrawls: ['players', 'Махач охотников-врагов', 'bool', null, 'После порога обиды враги дерутся. Зачинщик бьёт, только если второй ответит (флаг, ПвП без кармы), иначе отходит. Угрозы и итог — вслух при живом зрителе.'],
    HunterBrawlGrudge: ['players', 'Невозможно терпеть — с обиды', 'int', [1, null], 'Убил — +3, напал — +1, отжал моба — +1. Остывает вдвое за 72 ч игры.'],
    HunterBrawlChance: ['players', 'Шанс сорваться при поводе, %', 'int', [0, 100], 'Повод — отжал моба или встреча врагов.'],
    HunterBrawlCooldown: ['players', 'Одна пара дерётся не чаще, с', 'int', [0, null], ''],
    HunterBrawlLevelGap: ['players', 'Разница уровней для махача, не больше', 'int', [0, null], 'На сильного не лезет, слабого не бьёт.'],
    HunterBrawlWitness: ['players', 'Махач только при живом зрителе', 'bool', null, 'Живой игрок в 2000 — драка должна быть видна.'],
    HunterContestRudeChance: ['players', 'Шанс «пнх» на предъяву за моба, %', 'int', [0, 100], '× вспыльчивость (0,5–1,5); врагу чаще, другу реже. Иначе «сорян» и уходит на другой спот.'],
    HunterRelationEnemy: ['players', 'Враг — с обиды', 'int', [1, null], 'И если она больше благодарности. Убил — +3, напал — +1. Два реса перевешивают одно убийство.'],
    HunterMood: ['players', 'Настроение охотников', 'bool', null, 'Из состояния, без модели: устал, настороже, занят, скучает, хочет поболтать. Усталый и настороженный не встревают, скучающий заговаривает чаще; мозги говорят в настроении.'],
    PhantomPartyExpShare: ['companions', 'Напарник берёт долю опыта', 'bool', null, 'Выкл — весь опыт живым.'],
    PhantomPartyLootShare: ['companions', 'Напарник берёт долю лута', 'bool', null, ''],
    PhantomCatchUpRange: ['companions', 'Отстал дальше — телепорт к тебе', 'int', [0, null], 'Игровые единицы.'],
    PhantomAssistRange: ['companions', 'Бьёт твою цель в радиусе', 'int', [0, null], ''],
    PhantomDefendRange: ['companions', 'Защищает пати в радиусе', 'int', [0, null], ''],
    PhantomOwnerLogoutGrace: ['companions', 'Ждёт вышедшего владельца, с', 'int', [0, null], ''],
    PhantomEventCooldown: ['companions', 'Реплика на событие не чаще, с', 'int', [0, null], ''],
    PhantomEventsPerMinute: ['companions', 'Реплик на события в минуту', 'int', [0, null], 'На всех напарников; сверх — заготовки или молчание.'],
    PhantomBrain: ['ai', 'Напарники говорят через мозги', 'bool', null, 'Выкл — только заготовки и приказы.'],
    FakePlayerBrain: ['ai', 'Фейки говорят через мозги', 'bool', null, 'Выкл — заготовки.'],
    FakePlayerBrainTimeout: ['ai', 'Ждать ответа мозгов, с', 'int', [1, null], 'Не дождался — заготовка.'],
  };
  function schemaIndex() {
    const idx = {};
    for (const f of W.schema?.game || []) for (const sec of f.sections) for (const k of sec.keys) if (!(k.key in idx)) idx[k.key] = { ...k, file: f.file };
    return idx;
  }
  function invalid(k, v) {
    const [, , type, range] = META[k];
    if (type === 'int' && !/^-?\d+$/.test(v)) return 'целое число';
    if (type === 'float' && !/^-?\d+(\.\d+)?$/.test(v)) return 'число, дробь через точку';
    if ((type === 'int' || type === 'float') && range) {
      const n = Number(v);
      if (n < range[0] || (range[1] != null && n > range[1])) return range[1] != null ? `от ${range[0]} до ${range[1]}` : `не меньше ${range[0]}`;
    }
    if (type === 'list' && /[\r\n]/.test(v)) return 'одной строкой';
    return '';
  }
  function creatorView(main) {
    main.className = 'w-main flush';
    if (W.schemaErr) { main.className = 'w-main'; main.innerHTML = errorView(W.schemaErr); return; }
    if (!W.schema) { main.className = 'w-main'; main.innerHTML = loadingView(); return; }
    const idx = schemaIndex();
    const E = edits();
    const keys = Object.keys(META).filter((k) => idx[k]);
    if (!keys.length) { main.className = 'w-main'; main.innerHTML = '<div class="note">В конфигах этой сборки нет правил живого мира — они появляются с патчами охотников и напарников High Five.</div>'; return; }
    const inGroup = (g) => keys.filter((k) => META[k][0] === g);
    if (!inGroup(W.crGroup).length) W.crGroup = GROUPS.find(([g]) => inGroup(g).length)[0];
    const groups = GROUPS.filter(([g]) => inGroup(g).length).map(([g, t]) => {
      const ch = inGroup(g).filter((k) => k in E).length;
      return `<button data-g="${g}" class="${g === W.crGroup ? 'on' : ''}">${t}${ch ? `<span class="chg">● ${ch}</span>` : `<span class="c">${inGroup(g).length}</span>`}</button>`;
    }).join('');
    const list = inGroup(W.crGroup).map((k) => {
      const [, label, type, range, hint] = META[k];
      const f = idx[k];
      const v = k in E ? E[k] : f.value;
      const bad = k in E ? invalid(k, E[k]) : '';
      const cls = `field ${k in E ? 'chg' : ''} ${bad ? 'bad' : ''}`;
      let ctl;
      if (type === 'bool') ctl = `<button class="switch" role="switch" aria-checked="${String(v).toLowerCase() === 'true'}" data-k="${k}"></button>`;
      else if (type === 'enum') ctl = `<select data-k="${k}" class="${cls}">${range.map((o) => `<option ${o === String(v).toUpperCase() ? 'selected' : ''}>${o}</option>`).join('')}</select>`;
      else ctl = `<input class="${cls}" data-k="${k}" value="${esc(v)}" ${type === 'int' ? 'inputmode="numeric"' : ''}>`;
      const rangeTxt = range && type !== 'enum' ? (range[1] != null ? `${range[0]}–${range[1]}` : `≥ ${range[0]}`) : type === 'list' ? 'список' : type === 'bool' ? 'да / нет' : '';
      return `<div class="w-param ${k in E ? 'edited' : ''}"><div><div class="lb">${esc(label)}</div>${hint || f.description ? `<div class="ds">${esc(hint || f.description)}</div>` : ''}
        <div class="meta"><code>${k}</code><span>${esc(f.file)}</span>${rangeTxt ? `<span>${rangeTxt}</span>` : ''}${f.file === 'PhantomHunters.ini' ? '<span class="live">● на ходу, за ~10 с</span>' : '<span class="rst">● после перезапуска гейм-сервера</span>'}${bad ? `<span style="color:var(--bad)">нужно: ${bad}</span>` : ''}</div></div>
        <div class="ctl">${ctl}</div></div>`;
    }).join('');
    const n = Object.keys(E).length;
    const later = Object.keys(E).filter((k) => idx[k] && idx[k].file !== 'PhantomHunters.ini').length;
    const anyBad = Object.keys(E).some((k) => invalid(k, E[k]));
    const files = W.restart?.running ? W.restart.files : [];
    main.innerHTML = `<div class="w-cr"><div class="w-cr-groups">${groups}
        <div class="note" style="margin-top:12px;font-size:12px">Стратег охотников — в «Разуме». Служебное (сброс, самопроверки, аккаунт фантомов) и остальные ключи — в «Настройки» → «Все настройки».</div></div>
      <div class="w-cr-r">
        ${files.length ? `<div class="w-rst note w-note-warn">Сохранено, но ждёт перезапуска гейм-сервера: ${files.map((f) => `<span class="w-cmd">${esc(f)}</span>`).join(' ')}.<div class="row2"><button class="sbtn" id="worldRestart">Перезапустить гейм-сервер</button><span class="w-muted" style="font-size:12.5px">игроки и охотники вылетят примерно на полминуты</span></div></div>` : ''}
        <div class="w-cr-list" data-keep="cr">${list}</div>
        <div class="w-cr-foot"><span class="info ${n ? 'dirty' : ''}">${n ? `Не сохранено: ${n}${later ? ` · после перезапуска: ${later}` : ''}${anyBad ? ' · есть ошибки' : ''}. Правки не пропадут при переходе между разделами.` : 'Изменений нет.'}</span>
          <button class="sbtn" id="worldDiscard" ${n ? '' : 'disabled'}>Отменить правки</button><button class="sbtn primary" id="worldSave" ${n && !anyBad ? '' : 'disabled'}>Сохранить</button></div>
      </div></div>`;
    main.querySelectorAll('[data-g]').forEach((b) => (b.onclick = () => { W.crGroup = b.dataset.g; render(); }));
    const set = (k, val) => { if (String(val) === String(idx[k].value)) delete E[k]; else E[k] = val; render(true); };
    main.querySelectorAll('input[data-k]').forEach((i) => (i.onchange = () => set(i.dataset.k, i.value.trim())));
    main.querySelectorAll('select[data-k]').forEach((s) => (s.onchange = () => set(s.dataset.k, s.value)));
    main.querySelectorAll('.switch[data-k]').forEach((s) => (s.onclick = () => {
      const cur = String(idx[s.dataset.k].value);
      const on = s.getAttribute('aria-checked') !== 'true';
      set(s.dataset.k, cur === cur.toLowerCase() ? String(on) : on ? 'True' : 'False'); // регистр — как в файле
    }));
    $('worldDiscard').onclick = () => { for (const k of Object.keys(E)) delete E[k]; render(true); };
    $('worldSave').onclick = async () => {
      const list2 = Object.entries(E).map(([key, value]) => ({ part: 'game', file: idx[key].file, key, value }));
      const r = await api.save_settings(W.chron, list2);
      if (!r.ok) return toast(`Не сохранено: ${r.error}`, true);
      const live = list2.filter((e) => e.file === 'PhantomHunters.ini').length;
      for (const k of Object.keys(E)) delete E[k];
      await Promise.all([loadSchema(), loadRestart(), loadHunters()]);
      toast(`Сохранено: ${r.saved}. ${live ? `На ходу — ${live}. ` : ''}${r.saved - live ? `После перезапуска — ${r.saved - live}. ` : ''}Бэкап конфигов: ${r.backup}`);
      render(true);
    };
    const rs = $('worldRestart');
    if (rs) rs.onclick = async () => {
      const ok = await ask({ title: 'Перезапустить гейм-сервер?', ok: 'Перезапустить',
        body: '<p>Мир сохранится и поднимется заново примерно за полминуты: игроки и охотники вылетят и вернутся, текущие квесты охотников начнутся заново.</p>' });
      if (!ok) return;
      await call(api.restart_game(W.chron));
      W.restart = null;
      render(true);
    };
  }
  loadMods(); // список миров сразу: по нему кнопка «Пульт и карта» на главной
})();
