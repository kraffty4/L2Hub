'use strict';

// ---------- Мост к Python ----------
// В окне лаунчера — window.pywebview.api; с ?dev — тот же Api через l2hub.devserver.
const DEV = new URLSearchParams(location.search).has('dev');
const apiReady = DEV ? Promise.resolve() : new Promise((resolve) => {
  if (window.pywebview && window.pywebview.api) return resolve();
  window.addEventListener('pywebviewready', () => resolve(), { once: true });
});
const api = new Proxy({}, {
  get: (_, name) => async (...args) => {
    await apiReady;
    if (DEV) {
      const r = await fetch('/api/' + name, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) });
      const j = await r.json();
      if ('error' in j && !('result' in j)) throw new Error(j.error);
      return j.result;
    }
    return window.pywebview.api[name](...args);
  },
});

// ---------- Общее ----------
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
let toastTimer;
function toast(text, err = false) {
  const t = $('toast');
  t.textContent = text;
  t.classList.toggle('err', err);
  t.classList.add('on');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('on'), err ? 6000 : 3800);
}
function store(key, value) { try { localStorage.setItem('l2hub.' + key, value); } catch (e) { /* без хранилища тоже работаем */ } }
function load(key) { try { return localStorage.getItem('l2hub.' + key); } catch (e) { return null; } }
function fmtUptime(s) { if (s == null) return ''; const m = Math.floor(s / 60); return m < 60 ? `${m} мин` : `${Math.floor(m / 60)} ч ${m % 60} мин`; }

const ICON = {
  play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 4.5v15l12.5-7.5z"/></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="6" y="6" width="12" height="12" rx="1.5"/></svg>',
  get: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>',
  find: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="6"/><path d="M20 20l-4.5-4.5"/></svg>',
};

// ---------- Состояние интерфейса ----------
let catalog = [];
let selected = load('selected') || 'ct26_highfive';
let filter = 'all';
let query = '';
const collapsed = new Set(['Goddess of Destruction', 'После GoD', 'Classic', 'Essence']);
const profiles = {};
let last = { components: null, job: null, running: null };
let lastJobKey = '';
// Настройки самого L2Hub (data/launcher.json, l2hub.appsettings) — крестик и трей, список хроник.
let appSet = { closeRunning: 'ask', closeIdle: 'exit', notifyCrash: true, rememberWindow: true, showUnplanned: true, checkUpdates: true, language: 'auto' };
// Без «вне плана» в списке только семь хроник плана 1.0 ("plan": true в chronicles.json) и запущенная, если она не из плана.
const visibleChron = (c) => appSet.showUnplanned || c.plan || c.id === last.running;
// Раздел L2Hub (Хроники, Мой мир, Друзья, L2Hub) и вкладка страницы хроники — переживают перезапуск окна.
let section = load('section') || 'chron';
let ctab = load('ctab') || 'over';
let lastRun = null; // state() запущенной хроники, когда выбрана другая: панель сеанса и «Мой мир»
let tick = 0;
// «Мой мир» — раздел: чей мир смотреть, решает world.js (window.worldId), состояние берёт отсюда.
window.compsOf = (id) => (id === selected ? last.components : id === last.running && lastRun ? lastRun.components : null);

const byId = (id) => catalog.find((c) => c.id === id);
const stOf = (c) => (c.installed ? 'ready' : c.server);
const busy = () => last.job && !last.job.done;

// ---------- Список хроник ----------
// Подпись в списке: у C4 полное имя хроники игроку незнакомо — после него короткое (просьба автора 2026-10-20).
// Имя в chronicles.json не трогаем: по нему подписи в других местах и имя хроники у мозгов фейков.
const LIST_SUFFIX = { c4: 'C4' };
const listName = (c) => (c.variant ? `${c.name} · ${c.variant}` : LIST_SUFFIX[c.id] ? `${c.name} (${LIST_SUFFIX[c.id]})` : c.name);
function renderList() {
  const box = $('chronList');
  box.innerHTML = '';
  const full = appSet.showUnplanned;
  const q = full ? query.trim().toLowerCase() : '';
  const f = full ? filter : 'all';
  const shown = catalog.filter(visibleChron);
  const hidden = catalog.length - shown.length;
  document.querySelector('.sidebar').classList.toggle('unplanned', full);
  $('unplannedCount').textContent = `${catalog.filter((c) => !c.plan).length} хроник: Prelude, C1–C3, C5, Kamael, Hellbound, Freya, ветка GoD…`;
  $('hiddenNote').innerHTML = hidden ? `${hidden} хроник вне плана скрыто · <a id="showHidden">показать</a>` : '';
  if (hidden) $('showHidden').onclick = (e) => { e.stopPropagation(); openListPop(); };
  const eras = [...new Set(shown.map((c) => c.era))];
  for (const era of eras) {
    const all = shown.filter((c) => c.era === era);
    const items = all.filter((c) => (f === 'all' || stOf(c) === f || (f === 'mobius' && ['own', 'l2solo'].includes(c.server) && !c.installed)) && (!q || `${c.name} ${c.short}`.toLowerCase().includes(q)));
    if (!items.length) continue;
    const ready = all.filter((c) => c.installed).length;
    const isCol = full && collapsed.has(era) && !q && f === 'all';
    const h = document.createElement('button');
    h.className = 'era' + (isCol ? ' collapsed' : '');
    h.innerHTML = `<svg class="chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>${esc(era)}<span class="cnt">${ready ? ready + ' из ' : ''}${all.length}</span>`;
    h.onclick = () => { collapsed.has(era) ? collapsed.delete(era) : collapsed.add(era); renderList(); };
    box.appendChild(h);
    if (isCol) continue;
    for (const c of items) {
      const b = document.createElement('button');
      b.className = `chron st-${stOf(c)}` + (c.id === selected ? ' sel' : '');
      const thumb = c.cover ? `<span class="thumb" style="background-image:url(assets/cover_${c.cover}.jpg)"></span>` : `<span class="thumb">${esc(c.short)}</span>`;
      const runningHere = last.running === c.id;
      const metaText = c.installed ? (runningHere ? 'работает' : 'готова') : c.server === 'own' ? 'своя сборка' : c.server === 'mobius' ? 'есть сервер Mobius' : c.server === 'l2solo' ? 'есть сервер L2Solo' : 'сервера пока нет';
      b.innerHTML = `${thumb}<span class="t"><span class="n">${esc(listName(c))}</span><span class="m">${metaText}</span></span><i class="sdot ${runningHere ? 'run' : stOf(c)}"></i>`;
      b.onclick = () => select(c.id);
      box.appendChild(b);
    }
  }
  if (!box.children.length) box.innerHTML = '<div class="empty">Ничего не нашлось.</div>';
}

async function select(id) {
  if (id !== selected && Object.keys(edits).length) {
    const n = Object.keys(edits).length;
    const ok = await askModal({ title: 'Несохранённые правки', ok: 'Отменить правки',
      body: `<p>В настройках ${esc(byId(selected)?.name || selected)} несохранённых правок: ${n}. Отменить их и перейти к другой хронике?</p>` });
    if (!ok) return;
    edits = {};
  }
  selected = id;
  store('selected', id);
  closeMenu();
  const c = byId(id);
  if (c && c.installed && !profiles[id]) profiles[id] = await api.profile(id);
  renderList();
  renderMain();
  await poll();
  showCtab(ctab);
}

// ---------- Главный экран ----------
function renderMain() {
  const c = byId(selected);
  if (!c) return;
  const p = profiles[c.id];
  const ban = $('banner');
  ban.style.backgroundImage = c.cover ? `url(assets/cover_${c.cover}.jpg)` : 'none';
  ban.style.filter = c.installed ? 'none' : 'saturate(.55) brightness(.75)';
  ban.querySelector('.ph')?.remove();
  if (!c.cover) {
    const ph = document.createElement('div');
    ph.className = 'ph';
    ph.innerHTML = `<b>${esc(c.name)}</b><span>Обложка появится при наполнении хроники</span>`;
    ban.prepend(ph);
  }
  $('eraLabel').textContent = c.era;
  $('title').textContent = c.name;
  $('title').classList.toggle('long', c.name.length > 14);  // «Scions of Destiny» не режется кнопками справа
  const chips = [];
  if (p) {
    chips.push(p.build.replace('L2J_Mobius_', 'Mobius ').replaceAll('_', ' '));
    if (p.client.protocol) chips.push(`Протокол ${p.client.protocol}`);
    if (p.progression) chips.push(`Рейты ${p.progression}`); // L2Solo: пресет x1 / x10 / x50
    const geo = last.components?.game?.geo_regions;
    if (geo) chips.push(`Геодата ${geo} регионов`);
    $('subline').innerHTML = `Сервер <b>${esc(p.build)}</b> · клиент ` + (p.client.path ? `<b>${esc(p.client.path)}</b>`
      : '<b>не указан</b> <button class="linkbtn" id="pickClient">указать папку</button>');
    $('pickClient')?.addEventListener('click', () => pickClient(c.id));
  } else if (c.server === 'own') {
    $('subline').innerHTML = 'Публичного сервера нет — <b>своя сборка</b>: «Подключить» сделает копию твоей и запустит её без её лаунчера (например, La2offline)';
  } else if (c.server === 'mobius') {
    $('subline').innerHTML = `Сборка <b>${esc(c.build)}</b> есть в L2J Mobius — «Установить» скачает и соберёт её с модами L2Hub`;
  } else if (c.server === 'l2solo') {
    $('subline').innerHTML = 'Сервер — <b>L2Solo</b> (Node.js, 1700 ботов с LLM): «Установить» скачает и поставит его, клиент — их';
  } else {
    $('subline').innerHTML = 'Публичного сервера нет — можно <b>подключить свою сборку</b>: «Подключить» сделает её копию и запустит без её лаунчера';
  }
  $('chips').innerHTML = chips.map((t) => `<span class="chip">${esc(t)}</span>`).join('');
  renderPlay();
  renderHealth();
}

// «Пульт и карта» на главной: мир хроники в «Мой мир» — пульт L2Solo у C4, охотники и разум у хроник с фантомами.
function openPanel(screen) {
  const first = window.worldEntry?.(selected);
  if (!first) return toast('У этой хроники пока нет пульта.');
  window.openWorldFor(selected, screen || first);
  showSection('world');
}
$('panelBtn').onclick = () => openPanel();

function renderPlay() {
  const c = byId(selected);
  $('panelBtn').hidden = !(c?.installed && window.worldEntry?.(c.id));
  const split = $('split');
  split.className = 'split' + (busy() ? ' busy' : '');
  if (!c.installed) {
    split.classList.add('ghost');
    $('play').innerHTML = ICON.get + (['mobius', 'l2solo'].includes(c.server) ? 'Установить' : 'Подключить');
  } else if (last.running === c.id && last.components?.client?.state === 'on') {
    split.classList.add('ingame');
    $('play').innerHTML = ICON.play + 'В игре';
  } else if (last.running === c.id) {
    $('play').innerHTML = ICON.play + 'Запустить клиент';
  } else {
    $('play').innerHTML = ICON.play + 'Играть';
  }
}

const LABEL = { off: 'выключен', starting: 'запускается…', on: 'работает', bad: 'порт занят', other: 'порт у другой хроники', nokey: 'нет ключа API' };
// Где слушает часть: адреса из сокетов процесса (state → listen), пока не запущена — порт.
function listenAt(c) {
  const addrs = [...new Set((c.listen || []).map(a => (a === '0.0.0.0' || a === '::' ? 'все сети' : a)))];
  if (!addrs.length) return `порт ${c.port}`;
  return addrs.map(a => (a === 'все сети' ? `все сети · ${c.port}` : `${a}:${c.port}`)).join(', ');
}

function tile(name, s, lines) {
  return `<div class="tile"><div class="top"><span class="dot ${s === 'off' ? '' : s}"></span>${name}</div><div class="st">${LABEL[s] || s}</div>${lines.filter(Boolean).map((l) => `<div class="det" title="${esc(l)}">${esc(l)}</div>`).join('')}</div>`;
}

function renderHealth() {
  const c = byId(selected);
  const job = last.job && last.job.chronicle === selected ? last.job : null;
  $('bar').style.width = job && !job.done ? Math.round(job.progress * 100) + '%' : '0';
  if (!c.installed || !last.components) {
    // Идёт установка (мастер) — шаг задачи вместо «не установлена».
    $('hDot').className = job && !job.done ? 'dot starting' : 'dot';
    $('hSum').textContent = job && !job.done ? job.step : c.server === 'none' ? 'Сервера для этой хроники пока нет' : 'Хроника не установлена';
    $('hSub').textContent = job && !job.done ? `· установка, сделано ${Math.round(job.progress * 100)} %` : '';
    $('tiles').innerHTML = ['База данных', 'Логин-сервер', 'Гейм-сервер', 'Клиент'].map((n) => tile(n, 'off', ['—'])).join('');
    renderDock();
    return;
  }
  const k = last.components;
  const db = k.db, lg = k.login, gm = k.game, cl = k.client;
  // Порт у другой запущенной хроники (порты общие) — для этой хроники сервер просто выключен.
  const core = [db.state, lg.state, gm.state].map((s) => (s === 'other' ? 'off' : s));
  let sum, dot;
  const other = last.running && last.running !== selected; // база общая, порты логина и гейма держит другая хроника
  if (job && !job.done) { sum = job.step; dot = 'starting'; }
  else if (other && [lg.state, gm.state].every((s) => s === 'other' || s === 'off')) { sum = 'Сервер выключен'; dot = ''; }
  else if (core.every((s) => s === 'on')) { sum = 'Всё работает'; dot = 'on'; }
  else if (core.some((s) => s === 'bad')) { sum = 'Есть проблема — открой «Проверить всё»'; dot = 'bad'; }
  else if (core.some((s) => s === 'starting')) { sum = 'Запуск…'; dot = 'starting'; }
  else if (core.every((s) => s === 'off')) { sum = 'Сервер выключен'; dot = ''; }
  else { sum = 'Работает частично'; dot = 'starting'; }
  $('hDot').className = 'dot ' + dot;
  $('hSum').textContent = sum;
  const sub = [];
  if (gm.state === 'on') sub.push(gm.online != null ? `онлайн ${gm.online}` : '', gm.registered ? `на логине как ${gm.registered}` : '',
    gm.bots != null ? `ботов ${gm.bots}` : '', gm.rates ? `рейты ${gm.rates}` : '');
  if (gm.state === 'on' && cl.state !== 'on') sub.push('клиент не запущен');
  if (other && !(job && !job.done)) sub.push(`сейчас работает ${byId(last.running)?.name || last.running}`);
  $('hSub').textContent = sub.filter(Boolean).length ? '· ' + sub.filter(Boolean).join(' · ') : '';
  $('tiles').innerHTML = [
    tile('База данных', db.state, [db.where || `${db.engine || 'MariaDB'} · 127.0.0.1:${db.port}`]), // where — у L2Solo: SQLite внутри сервера
    tile('Логин-сервер', lg.state, [listenAt(lg), lg.pid ? `PID ${lg.pid} · ${fmtUptime(lg.uptime_s)}` : '']),
    tile('Гейм-сервер', gm.state, [listenAt(gm) + (gm.geo_regions ? ` · мир ${gm.geo_regions} регионов` : ''), gm.pid ? `куча ${gm.heap || '—'} · ${fmtUptime(gm.uptime_s)}` : '']),
    tile('Клиент', cl.state, [`протокол ${cl.protocol ?? '—'}`, cl.pid ? `PID ${cl.pid} · ${fmtUptime(cl.uptime_s)}` : '']),
    k.brain ? tile('Мозги фейков', k.brain.state === 'on' && !k.brain.key ? 'nokey' : k.brain.state, [
      k.brain.state === 'on' ? `${k.brain.model} · характеров ${k.brain.personas ?? 0}` : `127.0.0.1:${k.brain.port}`,
      k.brain.state !== 'on' ? '' : !k.brain.key ? 'фейки отвечают заготовками' : `ответов ${k.brain.replies ?? 0}` + (k.brain.lastError ? ' · была ошибка' : ''),
    ]) : '',
  ].join('');
  renderDock();
}

// Панель сеанса: одна хроника на всех (порты общие), поэтому и состояние одно — видно из любого раздела.
function renderDock() {
  const job = last.job && !last.job.done ? last.job : null;
  const run = last.running;
  const comps = run ? window.compsOf(run) : null;
  const gm = comps?.game || {};
  const name = (id) => byId(id)?.name || id;
  $('dockRun').classList.toggle('idle', !run && !job);
  $('dockDot').className = 'dot ' + (job ? 'starting' : run ? (gm.state === 'on' ? 'on' : 'starting') : '');
  $('dockName').textContent = job ? name(job.chronicle) : run ? name(run) : 'Ничего не запущено';
  const meta = [];
  if (!job && run) {
    meta.push(gm.state === 'on' ? 'работает' : gm.state === 'starting' ? 'гейм запускается' : 'без гейма');
    if (gm.uptime_s) meta.push(fmtUptime(gm.uptime_s));
    if (gm.online != null) meta.push(`в игре ${gm.online}`);
  }
  $('dockMeta').textContent = meta.length ? '· ' + meta.join(' · ') : '';
  if (job) $('footLog').textContent = job.step;
  else if (run) {
    const pr = profiles[run];
    $('footLog').textContent = pr ? `логин ${pr.ports.login} · гейм ${pr.ports.game}` : '';
  } else $('footLog').textContent = 'Выбери хронику и жми «Играть».';
  $('dockBar').style.width = job ? Math.round(job.progress * 100) + '%' : '0';
  $('railPip').classList.toggle('on', !!run);
  $('dockClient').hidden = !run || !!job || comps?.client?.state === 'on';
  $('dockLog').hidden = !run;
  $('dockStop').hidden = !run || !!job;
}
$('dockClient').onclick = () => last.running && call(api.launch_client(last.running));
$('dockLog').onclick = async () => {
  showSection('chron');
  if (last.running && last.running !== selected) await select(last.running);
  showCtab('log');
};
$('dockStop').onclick = () => last.running && call(api.stop(last.running));

async function poll() {
  try {
    last = await api.state(selected);
    // Запущена другая хроника — её состояние для панели сеанса и «Мой мир»: раз в 3 с хватит.
    if (last.running && last.running !== selected) { if (!lastRun || lastRun.id !== last.running || tick % 3 === 0) lastRun = { id: last.running, ...(await api.state(last.running)) }; }
    else lastRun = null;
    tick++;
  } catch (e) {
    $('footLog').textContent = 'Ядро не отвечает: ' + e.message;
    return;
  }
  const job = last.job;
  if (job && job.done) {
    const key = `${job.chronicle}|${job.action}|${job.step}|${job.error}`;
    if (key !== lastJobKey) {
      lastJobKey = key;
      if (job.error) toast(job.cancelled ? job.error : `Не получилось: ${job.error}`, !job.cancelled);
      else if (job.action === 'start') toast(`${byId(job.chronicle)?.name}: запущено.`);
      else if (job.action === 'stop') toast(`${byId(job.chronicle)?.name}: остановлено, мир сохранён.`);
      else if (job.action === 'restart') toast('Гейм-сервер перезапущен.');
      else if (job.action === 'backup') toast(`${byId(job.chronicle)?.name}: бэкап базы готов.`);
      else if (job.action === 'restore') toast(`${byId(job.chronicle)?.name}: база откатана, прежняя сохранена бэкапом «перед откатом».`);
      else if (job.action === 'pack') toast(`${byId(job.chronicle)?.name}: клиент для друзей собран — «Друзья» → «Открыть папку».`);
      else if (job.action === 'build') toast(`${byId(job.chronicle)?.name}: сервер собран с модулем фантомов.`);
      else if (job.action === 'install') toast(`${byId(job.chronicle)?.name}: сервер установлен. Осталось указать папку клиента.`);
      else if (job.action === 'geodata') toast(`${byId(job.chronicle)?.name}: геодата готова — подхватится при следующем запуске сервера.`);
      else if (job.action === 'update') { toast('Установщик запущен — L2Hub закрывается, откроется уже новым.'); if (!DEV) setTimeout(() => api.close(), 1500); }
      else if (job.action === 'maps') { toast('Подложка карты готова.'); window.worldMapReload?.(); }
      else if (job.action === 'client') toast(`${byId(job.chronicle)?.name}: язык клиента переключён — со следующего запуска клиента.`);
      if (job.action === 'client' && !job.error) api.profile(job.chronicle).then((pr) => { profiles[job.chronicle] = pr; if (job.chronicle === selected) { renderMain(); renderNet(); } });
      if (job.action === 'install' && !job.error) afterInstall(job.chronicle);
      if (['backup', 'restore', 'start'].includes(job.action) && ctab === 'backups') renderBackups();
      if (job.action === 'pack' && section === 'friends') renderFriends();
      if (job.action === 'geodata' && job.chronicle === selected && ctab === 'over') renderOverviewCards();
    }
  }
  renderList();
  renderPlay();
  renderHealth();
  if (byId(selected)?.installed) renderMain();
  if (ctab === 'over' && tick % 15 === 1) renderOverviewCards();
  if (window.worldTick) window.worldTick(); // «Мой мир»: шапка, свежесть, смена хроники и сервера
}

// ---------- Действия ----------
async function call(promise) {
  const r = await promise;
  if (r && r.ok === false) toast(r.error, true);
  await poll();
  return r;
}

$('play').onclick = () => {
  if (busy()) return;
  const c = byId(selected);
  if (!c.installed) {
    return installFlow(c);
  }
  if (!profiles[c.id]?.client?.path) return pickClient(c.id);
  if (last.running === c.id) {
    if (last.components?.client?.state === 'on') return toast('Клиент уже запущен.');
    return call(api.launch_client(c.id));
  }
  return call(api.play(c.id, true));
};
// Мастер установки (Api.install_plan / install): сервер Mobius — рантаймы, исходники на коммите, сборка, база; own —
// «Подключить свою сборку»: копия сборки-комбайна по описанию вида из bundles.json, для любой хроники.
async function installFlow(c, wantOwn = false) {
  const plan = await api.install_plan(c.id, wantOwn);
  if (!plan.ok) return toast(plan.error, true);
  const gb = (n) => `${(n / 2 ** 30).toFixed(1).replace('.', ',')} ГБ`;
  const own = plan.folder === 'bundle';
  const intro = own ? (c.installed ? `<p class="note">${esc(c.name)} уже стоит — своя сборка встанет рядом вторым сервером, отдельной строкой в списке.</p>` : '') + `<p>Своя сборка сервера ${esc(c.name)} — укажи её папку: L2Hub сделает копию и запустит её сам, без лаунчера сборки. Сейчас узнаёт:</p>
      <ul class="plan-steps">${plan.kinds.map((k) => `<li>${esc(k)}</li>`).join('')}</ul>
      <div class="row2"><input id="instFolder" class="field" style="flex:1" placeholder="папка сборки, например D:\\Games\\Server"><button class="sbtn" id="instPick">Выбрать…</button></div>
      <p class="note">Другие семейства L2J (свои jar на нашей Java и MariaDB) — следующими шагами. Дальше по шагам:</p>`
    : `<p>Поставим сервер <b>${esc(plan.build)}</b> ${c.server === 'l2solo' ? 'с оверлеем L2Hub поверх их кода (русский у ботов, учёт расхода LLM)' : plan.mods ? 'с модами L2Hub' : '— как есть в L2J Mobius, без модов L2Hub (они пока только у хроник плана 1.0)'}. По шагам:</p>`;
  // C4 (L2Solo): их клиент протокола 656 и client mod не раздаём — ссылки на их раздачу, качаешь сам.
  const cl = plan.client ? `<p class="note">Клиент — их: ${esc(plan.client.client.title)}, ${esc(plan.client.client.file)} (${gb(plan.client.client.size)}), и ${esc(plan.client.mod.title)}. Мы их не раздаём — качаешь с их Google Drive:</p>
      <div class="row2"><button class="sbtn" data-l2s="client">Клиент C4 от L2Solo</button><button class="sbtn" data-l2s="mod">Client mod</button></div>` : '';
  const body = intro + `<ul class="plan-steps">${plan.steps.map((s) => `<li>${esc(s)}</li>`).join('')}</ul>${cl}
    <p class="note">${plan.download ? `Скачать — около ${mb(plan.download)}; в` : 'В'}сё ляжет в ${esc(plan.home)} (свободно ${gb(plan.free)}). Клиент игры — твой: папку укажешь после установки.</p>`
    + (plan.homeBad ? `<p class="note bad-text">${esc(plan.homeBad)}</p>` : '')
    // Проверки мастера (docs/RELEASE.md): память — серверу Mobius 4–6 ГБ и клиенту 2–4; место — сервер с базой 3–6 ГБ.
    + (plan.ramGB && plan.ramGB < 14 ? `<p class="note bad-text">Памяти ${plan.ramGB} ГБ: серверу нужно 4–6 ГБ и клиенту ещё 2–4 — меньше 16 ГБ будет тесно, на время игры закрой лишнее.</p>` : '')
    + (plan.free && plan.free < 10 * 2 ** 30 ? `<p class="note bad-text">Свободно меньше 10 ГБ: сервер с базой и бэкапами займёт 3–6 ГБ, геодата — ещё до 2 ГБ.</p>` : '');
  const asked = askModal({ title: `${own ? 'Подключить' : 'Установить'} ${esc(c.name)}`, body, ok: own ? 'Подключить' : 'Установить' });
  if (own) $('instPick').onclick = async () => { const r = await api.pick_folder(); if (r.ok) $('instFolder').value = r.path; else if (!r.cancelled) toast(r.error, true); };
  $('uiModalBox').querySelectorAll('[data-l2s]').forEach((b) => (b.onclick = () => call(api.l2solo_client_open(b.dataset.l2s))));
  if (!(await asked)) return;
  call(own ? api.install(c.id, $('instFolder').value.trim()) : api.install(c.id));
}
async function afterInstall(id) {
  catalog = await api.catalog();
  profiles[id] = await api.profile(id);
  if (selected === id) { renderList(); renderMain(); }
}
// Папка клиента: сначала — клиенты, найденные на дисках (Api.find_clients, clients.py), выбор системы щелчком;
// «Другая папка…» — окно выбора папки Windows. В профиль пишется папка клиента и exe выбранной системы.
async function pickClient(id) {
  const box = $('uiModalBox');
  box.className = 'modal';
  const close = () => $('uiModal').classList.remove('on');
  const done = async (r) => {
    if (r.cancelled) return;
    if (!r.ok) return toast(r.error, true);
    close();
    profiles[id] = await api.profile(id);
    toast(`Клиент: ${r.path}${r.warn ? ` — ${r.warn}` : ''}`, Boolean(r.warn));  // warn — у C4: не их клиент или нет client mod
    renderMain();
    // Клиент 4game на штатной system — сразу предложить русский (решение автора 2026-10-21: язык выбирает пользователь).
    const lang = await api.client_language(id).catch(() => null);
    if (lang?.supported && lang.current === 'en' && !lang.ruReady && await askModal({ title: 'Русский в клиенте?', ok: 'Сделать русский',
      body: '<p>Клиент 4game запускается на английском (EU). Лаунчер может сделать рядом system_ru — копию системы с языком Russia: чат и ввод кириллицей, меню остаются английскими. Штатная system не меняется; переключить потом — «Настройки сервера» → «Клиент и сеть».</p>' })) {
      const res = await api.set_client_language(id, 'ru');
      if (!res.ok) return toast(res.error, true);
      toast('Делаю system_ru — прогресс в строке состояния.');
      await poll();
    }
  };
  const draw = async (refresh) => {
    box.innerHTML = `<h2>Клиент ${esc(byId(id)?.name || id)}</h2><p class="note">Ищу клиенты на дисках — до 25 секунд…</p>`;
    $('uiModal').classList.add('on');
    const r = await api.find_clients(refresh);
    const rows = r.items.map((c) => `<div class="client-row"><div class="client-path">${esc(c.path)}${c.used.length ? `<small>уже у: ${esc(c.used.join(', '))}</small>` : ''}</div>
        <div class="row2">${c.systems.map((s) => `<button class="sbtn" data-dir="${esc(c.path + '\\' + s)}">${esc(s)}</button>`).join('')}</div></div>`).join('');
    box.innerHTML = `<h2>Клиент ${esc(byId(id)?.name || id)}</h2>
      <p class="note">Найдено на дисках за ${String(r.seconds).replace('.', ',')} с${r.timeout ? ' (не всё — вышло время)' : ''}. Выбери систему клиента — папку с L2.exe: обычно <b>system</b>, для русского ввода — <b>system_ru</b>.</p>
      ${rows || '<p class="note">Клиентов не нашлось — укажи папку сам.</p>'}
      <div class="row2" style="margin-top:12px"><button class="sbtn" data-a="other">Другая папка…</button><button class="sbtn" data-a="again">Искать заново</button><span class="spacer" style="flex:1"></span><button class="sbtn" data-a="no">Отмена</button></div>`;
    box.querySelectorAll('[data-dir]').forEach((b) => (b.onclick = async () => done(await api.set_client_path(id, b.dataset.dir))));
    box.querySelector('[data-a="other"]').onclick = async () => done(await api.pick_client(id));
    box.querySelector('[data-a="again"]').onclick = () => draw(true);
    box.querySelector('[data-a="no"]').onclick = close;
  };
  draw(false);
}
$('more').onclick = (e) => {
  e.stopPropagation();
  if ($('menu').classList.toggle('on')) placeMenu();
};
// Меню вниз, если влезает в окно; иначе вверх, куда больше места; не влезает и туда — прокрутка.
function placeMenu() {
  const m = $('menu');
  m.classList.remove('up');
  m.style.maxHeight = '';
  const area = document.querySelector('.body').getBoundingClientRect();
  const split = $('split').getBoundingClientRect();
  const need = m.getBoundingClientRect().height;
  const below = area.bottom - split.bottom - 12;
  const above = split.top - area.top - 12;
  if (need <= below) return;
  if (above > below) m.classList.add('up');
  if (need > Math.max(above, below)) m.style.maxHeight = Math.max(above, below) + 'px';
}
function closeMenu() { $('menu').classList.remove('on'); }
document.addEventListener('click', closeMenu);
window.addEventListener('resize', closeMenu);
$('menu').querySelectorAll('button').forEach((b) => (b.onclick = async (e) => {
  e.stopPropagation();
  closeMenu();
  const c = byId(selected);
  const a = b.dataset.act;
  if (a === 'own') return installFlow(c, true);  // у установленной — второй сервер рядом (вариант)
  if (!c.installed) return toast('Сначала установи хронику.');
  if (a === 'server') return call(api.play(c.id, false));
  if (a === 'db') return call(api.play(c.id, false, true));
  if (a === 'client') return call(api.launch_client(c.id));
  if (a === 'restart') return call(api.restart_game(c.id));
  if (a === 'stop') return last.running ? call(api.stop(last.running)) : toast('Сервер не запущен.');
  if (a === 'logs') return showCtab('log');
  if (a === 'kill') {
    const r = await api.kill(c.id);
    toast(r.killed.length ? `Жёстко завершено: ${r.killed.join(', ')}` : 'Наших процессов не было.');
    return poll();
  }
}));

// ---------- Выдвижные панели ----------
let drawer = null;
let logTimer = null;
let logPart = 'game';
function openDrawer(name) {
  closeDrawer();
  drawer = $('drawer-' + name);
  drawer.classList.add('on');
  $('scrim').classList.add('on');
  if (name === 'diag') renderDiag();
}
function closeDrawer() {
  if (drawer) drawer.classList.remove('on');
  drawer = null;
  $('scrim').classList.remove('on');
}
document.querySelectorAll('[data-open]').forEach((b) => (b.onclick = () => {
  if (!byId(selected).installed) return toast('Сначала установи хронику.');
  openDrawer(b.dataset.open);
}));
document.querySelectorAll('[data-close]').forEach((b) => (b.onclick = closeDrawer));
$('scrim').onclick = closeDrawer;
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeDrawer(); closeListPop(); closeMenu(); } });

// ---------- Разделы L2Hub ----------
function showSection(name) {
  if (!['chron', 'world', 'friends', 'app'].includes(name)) name = 'chron';
  section = name;
  store('section', name);
  document.querySelectorAll('#rail [data-sec]').forEach((b) => b.classList.toggle('on', b.dataset.sec === name));
  $('sec-friends').classList.toggle('on', name === 'friends');
  $('sec-app').classList.toggle('on', name === 'app');
  closeDrawer(); closeMenu(); closeListPop();
  if (name === 'world') window.openWorld(); else window.closeWorld?.();
  if (name === 'friends') renderFriends();
  if (name === 'app') renderAppPage();
}
document.querySelectorAll('[data-sec]').forEach((b) => (b.onclick = () => showSection(b.dataset.sec)));

// ---------- Вкладки страницы хроники ----------
function showCtab(name) {
  const c = byId(selected);
  if (name !== 'over' && !c?.installed) name = 'over';
  if (name === 'set' && profiles[selected]?.type === 'l2solo') { // их ini не правим из окна: настройки L2Solo — в его пульте
    openPanel('panel');
    return;
  }
  ctab = name;
  store('ctab', name);
  document.querySelectorAll('.ctab').forEach((t) => t.classList.toggle('on', t.dataset.ctab === name));
  document.querySelectorAll('.cpane').forEach((x) => x.classList.toggle('on', x.id === 'cpane-' + name));
  $('main').classList.toggle('compact', name !== 'over');
  clearInterval(logTimer);
  if (name === 'over') renderOverviewCards();
  if (name === 'set') { $('setTitle').textContent = 'Настройки · ' + c.name; loadSchema(); }
  if (name === 'chars') renderChars();
  if (name === 'backups') renderBackups();
  if (name === 'log') { refreshLogs(); logTimer = setInterval(refreshLogs, 2000); }
}
document.querySelectorAll('.ctab').forEach((t) => (t.onclick = () => {
  if (!byId(selected)?.installed) return toast('Сначала установи хронику.');
  showCtab(t.dataset.ctab);
}));

// Карточки «Обзора»: живой мир, сеть, бэкап — по клику ведут в свой раздел или вкладку.
let cardsFor = null;
async function renderOverviewCards() {
  const box = $('ocards');
  const c = byId(selected);
  const id = selected;
  if (!c) { box.innerHTML = ''; return; }
  const votes = () => (c.plan || !fbEnabled ? null : api.votes().catch(() => null));
  if (!c.installed) {
    cardsFor = null;
    if (c.plan || !fbEnabled) { box.innerHTML = ''; return; }
    const v = await votes();
    if (id !== selected || byId(id)?.installed) return;
    box.innerHTML = wantCard(c, v);
    bindWant();
    return;
  }
  if (cardsFor !== id) box.innerHTML = '';
  const [h, net, bk, st, v, g] = await Promise.all([
    api.hunters(id).catch(() => null), api.network_state(id).catch(() => null), api.backup_list(id).catch(() => null),
    api.selftest(id).catch(() => null), votes(), api.geodata_status(id).catch(() => null)]);
  if (id !== selected) return;
  cardsFor = id;
  const card = (key, title, dot, big, m) => `<button class="ocard" data-go="${key}"><h3><span class="dot ${dot}"></span>${title}</h3><div class="big">${big}</div><div class="m">${m}</div><span class="go">${{ world: 'Мой мир →', friends: 'Друзья →', backups: 'Бэкапы →', diag: 'Проверить всё →' }[key]}</span></button>`;
  const cards = [wantCard(c, v)];
  if (h && h.ok) {
    const n = h.items?.length || 0;
    cards.push(card('world', 'Живой мир', last.running === id ? 'on' : '', `<b>${n}</b>охотников`, last.running === id ? 'мир живёт, пока сервер работает' : 'мир стоит — сервер выключен'));
  } else {
    cards.push(card('world', 'Живой мир', '', 'Без фантомов', esc(h?.error || 'модуль фантомов в этой сборке не стоит')));
  }
  if (net && !net.unsupported) {
    const mode = net.mode === 'local' ? 'Только этот ПК' : net.mode === 'friends' ? esc(net.label) : 'Настроено вручную';
    const sub = net.mode === 'friends' ? `адрес для друзей ${esc(net.address || '—')}` : net.mode === 'local' ? 'логин и гейм — 127.0.0.1' : 'проверь в «Друзьях»';
    cards.push(card('friends', 'Сеть', net.mode === 'friends' && !net.addressOk ? 'bad' : 'on', mode, sub + (net.restartNeeded ? ' · нужен перезапуск' : '')));
  } else {
    cards.push(card('friends', 'Сеть', '', 'Только этот ПК', esc(net?.unsupported || '')));
  }
  const b0 = bk?.items?.[0];
  cards.push(card('backups', 'Бэкап базы', b0 ? 'on' : '', b0 ? esc(b0.when) : 'Бэкапов нет', b0 ? `${esc(b0.labelText)} · всего ${bk.items.length}` : esc(bk?.blocker || 'снимется сам перед запуском')));
  // Самопроверка модов L2Hub (health.selftest): сервер пишет её через минуту после старта; сезонные события — оттуда же.
  if (st) {
    const season = st.seasonal ? `<br>события: ${esc(st.seasonal)}` : '';
    if (!st.checks) cards.push(card('diag', 'Моды L2Hub', 'starting', 'Проверяю…', 'самопроверка через минуту после старта'));
    else {
      const bad = st.bad.length ? `<span class="bad-text">${st.bad.map((c) => `${esc(c.name)} — ${esc(c.detail)}`).join('; ')}</span>`
        : `в порядке · ${st.running ? 'сейчас' : 'прошлый запуск'}, ${esc((st.time || '').slice(11, 16))}`;
      cards.push(card('diag', 'Моды L2Hub', st.bad.length ? 'bad' : 'on', `<b>${st.okCount}</b>из ${st.checks.length}`, bad + season));
    }
  }
  cards.push(geoCard(id, g));
  box.innerHTML = cards.join('');
  box.querySelectorAll('[data-go]').forEach((b) => (b.onclick = () => (b.dataset.go === 'backups' ? showCtab('backups')
    : b.dataset.go === 'diag' ? openDrawer('diag') : showSection(b.dataset.go))));
  bindWant();
  bindGeo(id);
}

// Геодата (geodata.py, geodata.json): скачать у источника или построить из карт клиента. Сборка из клиента — часы,
// поэтому у задачи есть «Остановить»: готовые регионы остаются, следующий запуск продолжит с места.
const fmtMin = (n) => (n >= 90 ? `${Math.round(n / 60)} ч` : `${n} мин`);
function geoCard(id, g) {
  if (!g?.ok || (!g.source && !g.files)) return '';
  const job = last.job && last.job.chronicle === id && last.job.action === 'geodata' && !last.job.done ? last.job : null;
  const src = g.source;
  let dot = 'on', big = `<b>${g.files}</b>регионов`, m = 'мобы обходят стены и ходят по рельефу', btn = '';
  if (job) {
    dot = 'starting';
    big = src?.kind === 'client' ? 'Строю…' : 'Скачиваю…';
    m = esc(job.step);
    btn = `<button class="sbtn" id="geoStop"${job.cancelled ? ' disabled' : ''}>${job.cancelled ? 'Останавливаю…' : 'Остановить'}</button>`;
  } else if (src && g.files < (g.expected || 1)) {
    dot = 'bad';
    big = g.files ? `<b>${g.files}</b>из ${g.expected}` : 'Нет';
    if (src.kind === 'download') {
      m = `Без неё мобы ходят сквозь стены. Источник — ${esc(src.title)}${g.size ? `, ${Math.round(g.size / 2 ** 20)} МБ` : ''}.`;
      btn = `<button class="sbtn primary" id="geoGet">${g.files ? 'Докачать' : 'Скачать'}</button>`;
    } else if (!g.client) {
      m = 'Без неё мобы ходят сквозь стены. Строится из карт твоего клиента — сначала укажи его папку.';
    } else {
      m = `Без неё мобы ходят сквозь стены. Строится из карт твоего клиента: около ${fmtMin(g.minutes)} в ${g.jobs} процессах, по 2 ГБ памяти на каждый. Можно остановить — продолжит с места.`;
      btn = `<button class="sbtn primary" id="geoGet">${g.files ? 'Достроить' : 'Построить'}</button>`;
    }
  }
  return `<div class="ocard geo"><h3><span class="dot ${dot}"></span>Геодата</h3><div class="big">${big}</div><div class="m">${m}</div>${btn ? `<div class="want-row">${btn}</div>` : ''}</div>`;
}
function bindGeo(id) {
  const get = $('geoGet');
  if (get) get.onclick = async () => {
    get.disabled = true;
    const r = await api.geodata_get(id);
    if (!r.ok) { get.disabled = false; return toast(r.error, true); }
    toast('Геодата пошла — прогресс в строке состояния хроники и здесь.');
    await poll();
    renderOverviewCards();
  };
  const stop = $('geoStop');
  if (stop) stop.onclick = async () => {
    stop.disabled = true;
    const r = await api.cancel_job();
    if (!r.ok) return toast(r.error, true);
    toast('Останавливаю — готовые регионы сохранятся.');
  };
}

// ---------- Голоса и отзывы (feedback.py, docs/FEEDBACK.md) ----------
// Приёмник настроен (feedback.json) — есть карточка «Хочу эту хронику» и кнопка «Отзыв»; узнаём при старте, без сети.
let fbEnabled = false;

// «Хочу эту хронику» у хроник вне плана: голос за то, чтобы её довели до ума — охотники, моды, наши настройки.
// Голый сервер любой поставит и сам (автор, 2026-10-21). Голос — за хронику, у вариантов — за их основную.
function wantCard(c, v) {
  if (c.plan || !v?.enabled) return '';
  const base = c.base || c.id;
  const mine = v.mine.includes(base);
  const n = v.votes[base] || 0;
  const what = c.server === 'none'
    ? 'Сервера для неё пока нет. Если хронику хотят многие — найдём сборку и доведём до ума: охотники, моды, наши настройки.'
    : 'Сервер ставится и сейчас, но голый: без охотников, модов и наших настроек. До ума доведём первой ту, которую хотят больше.';
  const count = n ? `уже хотят: <b>${n}</b>${mine ? ', и ты тоже' : ''}` : 'пока никто — будь первым';
  return `<div class="ocard want"><h3><span class="dot ${mine ? 'on' : ''}"></span>Хочу эту хронику</h3><div class="m">${what}</div>
    <div class="want-row"><button class="sbtn${mine ? '' : ' primary'}" id="wantBtn" data-id="${esc(base)}" data-on="${mine ? 0 : 1}">${mine ? 'Отозвать голос' : 'Хочу эту хронику'}</button><span class="m">${count}</span></div>
    ${v.error ? `<div class="m bad-text">Счётчик не обновился: ${esc(v.error)}</div>` : ''}</div>`;
}
function bindWant() {
  const b = $('wantBtn');
  if (!b) return;
  b.onclick = async () => {
    b.disabled = true;
    const on = b.dataset.on === '1';
    const r = await api.vote(b.dataset.id, on);
    if (!r.ok) { b.disabled = false; return toast(r.error, true); }
    toast(on ? `Голос учтён — хотят уже ${r.count}. Спасибо!` : 'Голос отозван.');
    renderOverviewCards();
  };
}

// Отзыв автору: баг или идея, контакт и отчёт — по желанию. Черновик живёт до удачной отправки.
let fbDraft = { kind: 'bug', text: '', contact: '' };
async function feedbackFlow() {
  if (!fbEnabled) return toast('Приём отзывов в этой версии L2Hub не настроен.', true);
  const c = byId(selected);
  const ask = askModal({ title: 'Баг или идея', ok: 'Отправить', body: `
    <div class="seg fb-kind" id="fbKind"><button data-v="bug">Баг</button><button data-v="idea">Идея</button></div>
    <textarea class="field fb-text" id="fbText" maxlength="4000"></textarea>
    <input class="field fb-contact" id="fbContact" maxlength="200" placeholder="Как ответить: Telegram, Discord, почта — не обязательно">
    <label class="fb-check"><input type="checkbox" id="fbReport"><span>Приложить отчёт: версия, «Проверить всё», хвосты логов — без паролей и ключей</span></label>
    <p class="note">Уйдёт автору L2Hub: текст, контакт, хроника (${c ? esc(listName(c)) : '—'}), версия L2Hub и случайный номер установки, с галкой — ещё отчёт.</p>` });
  const kinds = $('fbKind').querySelectorAll('button');
  const yes = $('uiModalBox').querySelector('[data-a="yes"]');
  const setKind = (k) => {
    fbDraft.kind = k;
    kinds.forEach((b) => b.classList.toggle('on', b.dataset.v === k));
    $('fbText').placeholder = k === 'bug' ? 'Что делал и что пошло не так: хроника, кнопка, текст ошибки — всё пригодится.' : 'Что добавить или сделать иначе.';
    $('fbReport').checked = k === 'bug';
  };
  const sync = () => { fbDraft.text = $('fbText').value; fbDraft.contact = $('fbContact').value; yes.disabled = !fbDraft.text.trim(); };
  kinds.forEach((b) => (b.onclick = () => setKind(b.dataset.v)));
  setKind(fbDraft.kind);
  $('fbText').value = fbDraft.text;
  $('fbContact').value = fbDraft.contact;
  $('fbText').oninput = sync;
  $('fbContact').oninput = sync;
  sync();
  $('fbText').focus();
  if (!(await ask)) return;
  const attach = $('fbReport').checked;
  toast(attach ? 'Собираю отчёт и отправляю…' : 'Отправляю…');
  const r = await api.send_feedback(fbDraft.kind, fbDraft.text, fbDraft.contact, c ? c.id : '', attach);
  if (!r.ok) return toast(`${r.error}. Текст сохранён — нажми «Отзыв» ещё раз.`, true);
  fbDraft = { kind: fbDraft.kind, text: '', contact: fbDraft.contact };
  toast(`Отправлено, спасибо!${r.report ? ' Отчёт ушёл вместе с отзывом.' : ''}`);
}
$('railFeedback').onclick = feedbackFlow;
$('fbOpen').onclick = feedbackFlow;

// Свой диалог вместо confirm() — как в «Мой мир».
function askModal({ title, body, ok }) {
  return new Promise((resolve) => {
    const box = $('uiModalBox');
    box.className = 'modal';
    box.innerHTML = `<h2>${title}</h2>${body}<div class="row2"><button class="sbtn" data-a="no">Отмена</button><button class="sbtn primary" data-a="yes">${ok}</button></div>`;
    $('uiModal').classList.add('on');
    const done = (v) => { $('uiModal').classList.remove('on'); resolve(v); };
    box.querySelector('[data-a="no"]').onclick = () => done(false);
    box.querySelector('[data-a="yes"]').onclick = () => done(true);
  });
}

$('makeReport').onclick = async (e) => {
  const b = e.currentTarget;
  b.disabled = true;
  b.textContent = 'Собираю…';
  const r = await api.make_report();
  b.disabled = false;
  b.textContent = 'Собрать';
  if (!r.ok) return toast(r.error, true);
  toast(`Отчёт готов: ${r.name}`);
};

// ---------- Настройки L2Hub (версия в шапке, шестерёнка над списком) ----------
// Обновления L2Hub (Api.update_check / update_install, updates.py): GitHub Releases, установщик со сверкой SHA-256.
let upd = null;
function renderUpdate(r) {
  upd = r;
  if (!$('updText')) return;
  $('versionTag').classList.toggle('upd', Boolean(r?.newer));
  const b = $('updBtn');
  if (!r?.enabled) { $('updText').textContent = 'проверка выключена: репозиторий не задан (updates.json)'; b.textContent = 'Проверить'; return; }
  if (r.error) { $('updText').textContent = r.error; b.textContent = 'Проверить'; return; }
  if (!r.newer) { $('updText').textContent = `у тебя последняя — ${r.current}`; b.textContent = 'Проверить'; return; }
  $('updText').textContent = `вышла ${r.latest}${r.size ? ` · ${mb(r.size)}` : ''}${r.canInstall ? '' : ' — скачай со страницы релиза'}`;
  b.textContent = r.canInstall ? 'Обновить' : 'Страница релиза';
}
$('updBtn').onclick = async () => {
  if (upd?.newer && upd.canInstall) {
    if (!(await askModal({ title: `Обновить до ${esc(upd.latest)}`, ok: 'Обновить',
      body: `<p>Скачаю установщик, сверю SHA-256 с релизом и запущу его: L2Hub закроется и откроется уже новым. Папка данных — серверы, базы, бэкапы — не трогается.</p>${upd.notes ? `<p class="note" style="white-space:pre-wrap;max-height:180px;overflow:auto">${esc(upd.notes)}</p>` : ''}` }))) return;
    const r = await api.update_install();
    return r.ok ? toast('Качаю обновление — прогресс в строке состояния.') : toast(r.error, true);
  }
  if (upd?.newer && upd.page) return call(api.update_page());
  $('updText').textContent = 'проверяю…';
  renderUpdate(await api.update_check(true));
};

async function renderAppPage() {
  renderAppSettings();
  api.update_check(false).then(renderUpdate);
  const r = await api.about();
  $('aboutVer').textContent = `L2Hub ${r.version}`;
  $('aboutPlan').innerHTML = r.plan.map((x) => `<span class="${x.ready ? 'ready' : x.playable ? 'play' : ''}" title="${esc(x.status)}">${x.ready ? '✓ ' : ''}${esc(x.name)}</span>`).join('')
    + `<span style="border:0">готово ${r.plan.filter((x) => x.ready).length} из ${r.plan.length}</span>`;
  $('aboutNews').innerHTML = r.news.map((n) => `<li><b>${esc(n.version)}</b><span>${esc(n.text)}</span></li>`).join('');
}
function renderAppSettings() {
  document.querySelectorAll('[data-set]').forEach((b) => b.setAttribute('aria-checked', String(!!appSet[b.dataset.set])));
  document.querySelectorAll('[data-seg]').forEach((s) => s.querySelectorAll('button').forEach((b) => b.classList.toggle('on', appSet[s.dataset.seg] === b.dataset.v)));
}
async function saveApp(patch) {
  const r = await api.save_app_settings(patch);
  if (!r.ok) return toast(r.error, true);
  appSet = r.settings;
  if ('language' in patch) return L2I18N.use(appSet.language); // окно перегружается на новом языке (ui/i18n.js)
  renderAppSettings();
  renderList();
  if (!visibleChron(byId(selected))) select(catalog.find((c) => c.id === 'ct26_highfive') ? 'ct26_highfive' : catalog.find(visibleChron).id);
}
document.querySelectorAll('[data-set]').forEach((b) => (b.onclick = (e) => { e.stopPropagation(); saveApp({ [b.dataset.set]: !appSet[b.dataset.set] }); }));
document.querySelectorAll('[data-seg]').forEach((s) => s.querySelectorAll('button').forEach((b) => (b.onclick = () => saveApp({ [s.dataset.seg]: b.dataset.v }))));
function openListPop() { $('listPop').classList.add('on'); $('listOpts').classList.add('on'); }
function closeListPop() { $('listPop').classList.remove('on'); $('listOpts').classList.remove('on'); }
$('listOpts').onclick = (e) => { e.stopPropagation(); $('listPop').classList.contains('on') ? closeListPop() : openListPop(); };
$('listPop').onclick = (e) => e.stopPropagation();
document.addEventListener('click', closeListPop);

async function renderDiag() {
  $('diagList').innerHTML = '<h3>Проверяю…</h3>';
  const rows = await api.diagnostics(selected);
  const mark = { ok: '✓', warn: '!', bad: '✕', na: '–' };
  $('diagList').innerHTML = '<h3>Результат</h3>' + rows.map((r) =>
    `<div class="check"><span class="ic ${r.status}">${mark[r.status]}</span><div>${esc(r.title)}${r.detail ? `<small>${esc(r.detail)}</small>` : ''}${r.hint ? `<em>${esc(r.hint)}</em>` : ''}</div></div>`).join('');
}
$('diagRefresh').onclick = renderDiag;

async function refreshLogs() {
  const lines = await api.log_tail(selected, logPart, 120);
  const pre = $('logs');
  const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 20;
  pre.textContent = lines.join('\n') || 'Лог пуст.';
  if (atBottom) pre.scrollTop = pre.scrollHeight;
}
$('logSeg').querySelectorAll('button').forEach((b) => (b.onclick = () => {
  logPart = b.dataset.part;
  $('logSeg').querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
  refreshLogs();
}));
document.querySelectorAll('[data-path]').forEach((b) => (b.onclick = () => call(api.open_path(selected, b.dataset.path))));

async function renderChars() {
  const box = $('chars');
  box.innerHTML = '<div class="empty">Загружаю…</div>';
  const r = await api.characters(selected);
  if (!r.ok) { box.innerHTML = `<div class="note">${esc(r.error)}</div>`; return; }
  if (!r.items.length) { box.innerHTML = `<div class="note">Персонажей пока нет.${r.note ? ' ' + esc(r.note) : ''}</div>`; return; }
  box.innerHTML = r.note ? `<div class="note">${esc(r.note)}</div>` : '';
  for (const ch of r.items) {
    const row = document.createElement('div');
    row.className = 'list-row';
    row.innerHTML = `<span>${esc(ch.name)} <small>· ${esc(ch.account)} · ${ch.level} ур.${ch.online ? ' · в игре' : ''}</small></span>`;
    if (r.readonly) { box.appendChild(row); continue; } // L2Solo: права не меняются, .admin открыт всем
    const sel = document.createElement('select');
    sel.innerHTML = Object.entries(r.levels).map(([lvl, n]) => `<option value="${lvl}"${+lvl === ch.access ? ' selected' : ''}>${esc(n)} (${lvl})</option>`).join('');
    sel.onchange = async () => {
      const res = await api.set_access(selected, ch.name, +sel.value);
      if (res.ok) toast(`${ch.name}: права обновлены.`); else { toast(res.error, true); renderChars(); }
    };
    row.appendChild(sel);
    box.appendChild(row);
  }
}

// ---------- Друзья: способы игры по сети (один на все хроники) ----------
// Способы — network.KINDS; «Выделенный сервер» — в планах: L2Hub поднимает сервер только на этом ПК.
const WAYS = [
  { kind: 'local', name: 'Только этот ПК', text: 'Друзья не подключатся: логин и гейм слушают 127.0.0.1.' },
  { kind: 'radmin', name: 'Radmin VPN', text: 'Своя сеть с именем и паролем, radmin-vpn.com. Проверено с пацанами.', site: 'radmin-vpn.com' },
  { kind: 'tailscale', name: 'Tailscale', text: 'Друзья входят в твою сеть по приглашению, tailscale.com.', site: 'tailscale.com' },
  { kind: 'zerotier', name: 'ZeroTier', text: 'Сеть по ID, участников одобряешь сам, zerotier.com.', site: 'zerotier.com' },
  { kind: 'lan', name: 'Локальная сеть', text: 'Друг в той же квартире: тот же Wi-Fi или роутер, без VPN.' },
];
const HOWTO = {
  radmin: ['Поставь Radmin VPN (radmin-vpn.com), создай сеть — имя и пароль придумай. Здесь выбери Radmin VPN и перезапусти сервер. При первом запуске Windows спросит про брандмауэр для Java — разреши и для частных, и для общественных сетей (сеть Radmin Windows обычно считает общественной).',
    'Radmin VPN → «Присоединиться к сети» по имени и паролю от тебя.'],
  tailscale: ['Поставь Tailscale (tailscale.com), войди и пригласи друзей в свою сеть из админки Tailscale. Здесь выбери Tailscale и перезапусти сервер; брандмауэр для Java — разреши.',
    'Поставить Tailscale и войти по твоему приглашению.'],
  zerotier: ['Поставь ZeroTier One (zerotier.com), создай сеть в my.zerotier.com, подключись к ней и одобри друзей. Здесь выбери ZeroTier и перезапусти сервер; брандмауэр для Java — разреши.',
    'ZeroTier One → «Join New Network» по ID твоей сети; ждать, пока ты одобришь.'],
  lan: ['Здесь выбери локальную сеть и перезапусти сервер; брандмауэр для Java — разреши для частных сетей. Адрес этого ПК в сети может смениться после перезагрузки роутера — тогда выбери способ заново.',
    'Подключиться к тому же Wi-Fi или роутеру.'],
};
let netView = null;
let packChron = load('packChron');

async function renderFriends() {
  const r = await api.network_overview();
  netView = r;
  const mine = r.items.filter((x) => x.supported);
  const common = r.common;
  // Сейчас: один способ во всех хрониках или по-разному.
  let now;
  if (!mine.length) now = '<div class="note">Нет хроник, где можно настроить сеть.</div>';
  else if (common && common.mode === 'friends') now = `<b>${esc(common.label)}</b> · друзьям отдаётся <span class="w-cmd">${esc(common.address)}</span> · во всех хрониках`;
  else if (common && common.mode === 'local') now = '<b>Только этот ПК</b> · во всех хрониках. Выбери ниже, как друзья подключаются.';
  else now = '<b>Хроники настроены по-разному</b> — выбери способ ниже, он встанет во все.';
  const warn = mine.filter((x) => x.mode === 'friends' && !x.addressOk).length
    ? '<div class="note bad-text" style="margin-top:8px">У этого ПК сейчас нет адреса из настроек — сеть выключена или адрес сменился. Включи её или выбери способ заново.</div>' : '';
  const restart = mine.filter((x) => x.restartNeeded).map((x) => x.name);
  $('netNow').innerHTML = `<div class="netnow-in"><span class="dot ${common?.mode === 'friends' ? 'on' : ''}"></span><span>${now}</span></div>${warn}`
    + (restart.length ? `<div class="note bad-text" style="margin-top:8px">Сеть поменялась после запуска: перезапусти ${esc(restart.join(', '))}, чтобы применить.</div>` : '');
  // Карточки способов.
  const cards = WAYS.map((w) => {
    const found = r.candidates.filter((c) => c.kind === w.kind);
    const active = common && (w.kind === 'local' ? common.mode === 'local' : common.mode === 'friends' && common.kind === w.kind);
    let status, btns;
    if (w.kind === 'local') {
      status = 'всегда доступно';
      btns = active ? '<span class="way-on">выбрано</span>' : '<button class="sbtn" data-way="local">Выбрать</button>';
    } else if (!found.length) {
      status = `<span class="no">не найден${w.site ? ` — поставь с ${esc(w.site)} и подключись к сети` : ' — нет адаптера в частной сети'}</span>`;
      btns = '';
    } else {
      status = `адрес этого ПК: ${found.map((c) => `<span class="w-cmd" title="${esc(c.adapter)}">${esc(c.address)}</span>`).join(' ')}`;
      btns = found.map((c) => (active && common.address === c.address ? '<span class="way-on">выбрано</span>'
        : `<button class="sbtn primary" data-way="friends" data-kind="${w.kind}" data-addr="${esc(c.address)}">${found.length > 1 ? esc(c.address) : 'Включить везде'}</button>`)).join(' ');
    }
    return `<div class="way${active ? ' on' : ''}${!found.length && w.kind !== 'local' ? ' off' : ''}"><h4>${esc(w.name)}</h4><p>${esc(w.text)}</p><div class="way-st">${status}</div><div class="way-act">${btns}</div></div>`;
  }).join('') + `<div class="way plan"><h4>Выделенный сервер <span class="badge-plan">для богатых · в планах</span></h4>
    <p>Сервер круглосуточно на арендованной машине (VPS): друзья играют и без тебя. Нужно: аренда Windows-сервера, перенос хроники и базы, открытые порты 2106 и 7777, L2.ini друзьям с его адресом.</p>
    <div class="way-st">L2Hub пока поднимает сервер только на этом ПК.</div></div>`;
  $('netWays').innerHTML = cards;
  $('netWays').querySelectorAll('[data-way]').forEach((b) => (b.onclick = () => setWay(b.dataset.way, b.dataset.addr || null, b.dataset.kind || null)));
  // Хроники.
  $('netChrons').innerHTML = r.items.map((x) => {
    const st = !x.supported ? `<span class="no">${esc(x.why || 'не поддерживается')}</span>`
      : x.mode === 'other' ? '<span class="warnc">настроено вручную</span>'
        : x.restartNeeded ? '<span class="warnc">нужен перезапуск</span>' : '<span class="yes">✓</span>';
    const how = !x.supported ? '—' : x.mode === 'local' ? 'Только этот ПК' : x.mode === 'friends' ? `${esc(x.label)} · ${esc(x.address)}` : 'вручную';
    return `<div class="list-row"><span>${esc(x.name)}${x.running ? ' <small>· работает</small>' : ''}<small style="display:block">${how}</small></span>${st}</div>`;
  }).join('');
  // Для друзей: хроника, для которой собрать.
  const sel = $('packChron');
  if (!mine.some((x) => x.id === packChron)) packChron = (mine.find((x) => x.id === last.running) || mine.find((x) => x.id === selected) || mine[0])?.id;
  sel.innerHTML = mine.map((x) => `<option value="${x.id}"${x.id === packChron ? ' selected' : ''}>${esc(x.name)}</option>`).join('');
  const pc = mine.find((x) => x.id === packChron);
  $('friendIni').innerHTML = !pc ? '' : (pc.pack ? `<div class="note">Клиент целиком: ${esc(pc.pack.path)} · ${mb(pc.pack.size)} · ${esc(pc.pack.time)}.</div>` : '<div class="note">Клиент целиком ещё не собран.</div>')
    + (pc.friendIni ? `<div class="note">${esc(pc.friendIni.split('\\').pop())}: ${esc(pc.friendIni)} — друзьям в ${esc(pc.clientDir)}\\${esc(pc.friendIni.split('\\').pop())} их клиента${pc.friendIni.endsWith('L2Hub.ini') ? ' (клиент 4game: запуск — L2.exe -INI=.\\L2Hub.ini)' : ', с заменой'}.</div>` : '');
  // Как подключить — под выбранный способ.
  const kind = common?.mode === 'friends' ? common.kind : pc?.mode === 'friends' ? pc.kind : null;
  const how = HOWTO[kind];
  $('netHowTo').innerHTML = how
    ? `<div class="note"><b>Ты.</b> ${esc(how[0])} Собери L2.ini или клиент целиком и отправь друзьям.</div>
       <div class="note"><b>Друзья.</b> ${esc(how[1])} Клиент — той же хроники и с той же системой, что у тебя; присланный L2.ini — с заменой в папку системы клиента (свой сохранить рядом). Аккаунт создаётся сам при первом входе — логин и пароль любые новые.</div>`
    : '<div class="note">Выбери способ выше — здесь появится, что сделать тебе и друзьям.</div>';
  renderPlayers();
}
// Кто играет: аккаунты живых игроков из баз всех хроник, что сейчас видно (Api.players), — без зашитых имён.
async function renderPlayers() {
  const r = await api.players();
  const ago = (ms) => {
    if (!ms) return '';
    const min = Math.round((Date.now() - ms) / 60000);
    return min < 2 ? 'только что' : min < 60 ? `${min} мин назад` : min < 48 * 60 ? `${Math.round(min / 60)} ч назад` : `${Math.round(min / 1440)} дн назад`;
  };
  const rows = r.items.map((a) => {
    const chars = a.chars.map((ch) => `${ch.online ? '<b>' : ''}${esc(ch.name)} ${ch.level}${ch.online ? '</b>' : ''} <small>${esc(ch.chron)}</small>`).join(' · ');
    const seen = a.online ? '<span class="yes">в игре</span>' : esc(ago(a.last));
    return `<div class="friend"><div class="ava">${esc(a.account.slice(0, 1).toUpperCase())}</div>`
      + `<div class="who">${esc(a.account)}<small>${chars}</small></div>`
      + `<div class="seen">${seen}${a.from ? `<small>${esc(a.from)}</small>` : ''}</div></div>`;
  }).join('');
  const hidden = r.hidden.length ? `<div class="note" style="margin-top:8px">Не видно — база выключена: ${esc(r.hidden.join(', '))}.</div>` : '';
  $('whoPlays').innerHTML = (rows || '<div class="note">Пока никто не заходил — или базы хроник выключены.</div>') + hidden;
}
async function setWay(mode, address, kind) {
  const r = await api.set_network_all(mode, address, kind);
  if (!r.ok) return toast(r.error, true);
  const restart = r.done.filter((x) => x.restart).map((x) => x.name);
  toast((mode === 'local' ? 'Только этот ПК — во всех хрониках.' : `${r.label}: ${r.address} — во всех хрониках.`)
    + (restart.length ? ` Перезапусти ${restart.join(', ')}, чтобы применить.` : '')
    + (r.failed.length ? ` Не вышло: ${r.failed.map((x) => x.name).join(', ')}.` : ''), r.failed.length > 0);
  renderFriends();
}
$('packChron').onchange = (e) => { packChron = e.target.value; store('packChron', packChron); renderFriends(); };
$('friendPackMake').onclick = () => packChron && call(api.make_client_pack(packChron));
$('friendIniMake').onclick = async () => {
  if (!packChron) return;
  const r = await api.make_friend_l2ini(packChron);
  if (!r.ok) return toast(r.error, true);
  toast(`${r.path.split('\\').pop()} для друзей собран.`);
  renderFriends();
};
$('friendDir').onclick = () => packChron && call(api.open_path(packChron, 'friends'));

// ---------- Бэкапы базы ----------
const BACKUPS_SHOWN = 12;
const mb = (n) => `${(n / 2 ** 20).toFixed(1).replace('.', ',')} МБ`;

async function renderBackups() {
  const box = $('backups');
  const r = await api.backup_list(selected);
  box.innerHTML = r.blocker ? `<div class="note">${esc(r.blocker)}</div>` : '';
  if (!r.items.length) box.insertAdjacentHTML('beforeend', '<div class="note">Бэкапов базы пока нет.</div>');
  for (const b of r.items.slice(0, BACKUPS_SHOWN)) {
    const row = document.createElement('div');
    row.className = 'list-row';
    row.innerHTML = `<span>${esc(b.when)} <small>· ${esc(b.labelText)} · ${mb(b.size)}</small></span>`;
    const btn = document.createElement('button');
    btn.className = 'sbtn';
    btn.textContent = 'Откатить';
    btn.onclick = () => restoreBackup(b);
    row.appendChild(btn);
    box.appendChild(row);
  }
  if (r.items.length > BACKUPS_SHOWN) box.insertAdjacentHTML('beforeend', `<div class="note">И ещё ${r.items.length - BACKUPS_SHOWN} — в папке бэкапов.</div>`);
}

async function restoreBackup(b) {
  const name = byId(selected)?.name || selected;
  if (!confirm(`Откатить базу ${name} к бэкапу от ${b.when} (${b.labelText})?\n\nТекущая база сначала сохранится бэкапом «перед откатом».`)) return;
  await call(api.restore_backup(selected, b.name));
}
$('backupNow').onclick = () => call(api.make_backup(selected));

// ---------- Настройки ----------
let KEYS = {};
let FILES = [];
let edits = {};
let curFile = 'game/Rates.ini';
let onlyChanged = false;
let keyQuery = '';

const valOf = (k) => (k.id in edits ? edits[k.id] : k.value);
const norm = (v) => String(v ?? '').trim().toLowerCase().replace(/\.0*$/, '');
const isNonDefault = (k) => k.default !== null && k.default !== undefined && !/[a-zа-я]{4,}\s/i.test(k.default) && norm(k.value) !== norm(k.default);
const findKey = (name, part = 'game') => Object.values(KEYS).find((k) => k.key === name && k.part === part);


async function loadSchema() {
  const schema = await api.settings_schema(selected);
  FILES = [...schema.game.map((f) => ({ ...f, part: 'game' })), ...schema.login.map((f) => ({ ...f, part: 'login' }))];
  KEYS = {};
  for (const f of FILES) for (const s of f.sections) for (const k of s.keys) {
    const id = `${f.part}/${f.file}/${k.key}`;
    KEYS[id] = { ...k, id, file: f.file, part: f.part, section: s.title, group: f.group };
  }
  // Файл рейтов у сборок называется по-разному (Rates.ini, rates.properties): нет выбранного — открываем рейты или первый.
  if (!FILES.some((f) => `${f.part}/${f.file}` === curFile)) {
    const f = FILES.find((x) => x.part === 'game' && /^rates\./i.test(x.file)) || FILES[0];
    if (f) curFile = `${f.part}/${f.file}`;
  }
  $('allBadge').textContent = Object.keys(KEYS).length;
  $('keySearch').placeholder = `Поиск по ${Object.keys(KEYS).length} настройкам: имя или описание`;
  renderBasic(); renderFiles(); renderKeys(); renderNet(); updatePending();
}

function setVal(k, v) {
  if (String(v) === String(k.value)) delete edits[k.id]; else edits[k.id] = String(v);
  updatePending();
}
function updatePending() {
  const n = Object.keys(edits).length;
  const running = last.running === selected;
  $('pendingInfo').textContent = n ? `Несохранённых правок: ${n}.${running ? ' Сервер работает — применятся после перезапуска гейма.' : ' Применятся при следующем запуске.'}` : 'Изменений нет.';
}

function control(k, onChange) {
  const v = valOf(k);
  if (k.secret) { const s = document.createElement('span'); s.className = 'kv-secret'; s.textContent = 'правит лаунчер'; return s; }
  if (k.type === 'bool') {
    const b = document.createElement('button');
    b.className = 'switch'; b.setAttribute('role', 'switch');
    b.setAttribute('aria-checked', String(v).toLowerCase() === 'true');
    b.onclick = () => { const nv = b.getAttribute('aria-checked') !== 'true'; b.setAttribute('aria-checked', nv); setVal(k, nv ? 'True' : 'False'); onChange && onChange(); };
    return b;
  }
  const i = document.createElement('input');
  i.className = 'field'; i.value = v;
  i.oninput = () => { setVal(k, i.value); onChange && onChange(); };
  return i;
}

const BASIC = [
  { title: 'Рейты', rates: [
    ['RateXp', 'Опыт'], ['RateSp', 'SP'], ['RatePartyXp', 'Опыт в группе'], ['RatePartySp', 'SP в группе'],
    ['@adena', 'Адена с мобов'], ['DeathDropAmountMultiplier', 'Дроп: количество'], ['DeathDropChanceMultiplier', 'Дроп: шанс'], ['SpoilDropAmountMultiplier', 'Спойл: количество'],
    ['SpoilDropChanceMultiplier', 'Спойл: шанс'], ['RaidDropAmountMultiplier', 'Рейды: количество'], ['RaidDropChanceMultiplier', 'Рейды: шанс'], ['HerbDropAmountMultiplier', 'Хербы'],
    ['RateQuestRewardXP', 'Квесты: опыт'], ['RateQuestRewardSP', 'Квесты: SP'], ['RateQuestRewardAdena', 'Квесты: адена'], ['RateDropManor', 'Манор'],
    // Ключи L2J 2.0.6 (La2offline, rates.properties); у Mobius их нет — карточка показывает те, что нашлись.
    ['RateDropAdena', 'Адена с мобов'], ['RateDropItems', 'Дроп'], ['RateDropSpoil', 'Спойл'], ['RateRaidDropItems', 'Рейды: дроп'],
    ['RateDropQuest', 'Квесты: дроп'], ['RateQuestsReward', 'Квесты: награда'], ['RateCommonHerbs', 'Хербы'], ['PetXpRate', 'Опыт питомцев'] ] },
  { title: 'Игра', opts: [
    ['StartingAdena', 'Стартовая адена'], ['AutoLearnSkills', 'Автоизучение скиллов'], ['AltSubClassWithoutQuests', 'Саб-класс без квеста'], ['MaxSubclass', 'Максимум сабов'],
    ['AutoLoot', 'Автоподбор дропа'], ['AutoLootHerbs', 'Автоподбор хербов'], ['EnableVitality', 'Виталити'], ['GlobalChat', 'Глобальный чат', ['ON', 'OFF', 'GM', 'GLOBAL']],
    ['DeleteCharAfterDays', 'Удалять персонажа через, дней'], ['OlympiadEnabled', 'Олимпиада'] ] },
  { title: 'Помощники для соло', opts: [
    ['EnableFakePlayers', 'Боты-игроки'], ['EnableAutoPlay', 'Автобой .play'], ['EnableOfflinePlayCommand', 'Оффлайн-фарм .offlineplay'], ['OfflineTradeEnable', 'Оффлайн-торговля'],
    ['ChampionEnable', 'Мобы-чемпионы'], ['RebirthAllow', 'Перерождения'], ['EnableFreeStrider', 'Бесплатный страйдер'], ['EnableFreeWyvern', 'Бесплатная виверна'], ['AllowWedding', 'Свадьбы'] ] },
  { title: 'GM', opts: [
    ['EverybodyHasAdminRights', 'Все игроки — админы'], ['GMStartupInvisible', 'GM входит невидимым'], ['GMStartupInvulnerable', 'GM входит бессмертным'],
    ['GMStartupSilence', 'GM без личных сообщений'], ['GMStartupDietMode', 'GM без перевеса'], ['CustomStartingLocation', 'Новые персонажи — в Гиран'] ] },
  { title: 'При входе в игру', wide: true, welcome: true },
];

// Режим сервера и выключатели модов (Api.server_modes, l2hub/modes.py, server_modes.json) — сверху «Основного».
async function renderModes() {
  const host = $('modesBox');
  if (!host) return;
  const id = selected;
  const r = await api.server_modes(id).catch(() => null);
  if (id !== selected || !$('modesBox')) return;
  if (!r || !r.ok) { host.innerHTML = r?.error ? `<div class="card wide"><h3>Режим сервера</h3><div class="note">${esc(r.error)}</div></div>` : ''; return; }
  const active = r.modes.find((m) => m.active);
  const btns = r.modes.map((m) => `<button data-mode="${m.id}" class="${m.active ? 'on' : ''}" title="${esc(m.note)}">${esc(m.title)}</button>`).join('')
    + `<button data-mode="custom" class="${active ? '' : 'on'}" title="Все настройки сборки — рейты и всё остальное по ключам">Настроить самому</button>`;
  const avail = r.switches.filter((s) => s.available);
  const groups = [...new Set(avail.map((s) => s.group))];
  const sw = groups.map((g) => `<div class="mode-group">${esc(g)}</div>` + avail.filter((s) => s.group === g).map((s) =>
    `<div class="opt"><div class="l">${esc(s.title)}<small>${esc(s.note)}</small></div><button class="switch" role="switch" aria-checked="${s.value}" data-sw="${s.id}"></button></div>`).join('')).join('');
  const missing = r.switches.filter((s) => !s.available).map((s) => s.title);
  host.innerHTML = `<div class="card wide"><h3>Режим сервера<span class="src">${active ? esc(active.title) : 'свои настройки'}</span></h3>
      <div class="seg modes" id="modeSeg">${btns}</div>
      <div class="note" id="modeNote">${esc(active ? active.note : 'Рейты и моды — свои: правятся ниже и во «Все настройки».')}${r.restart ? ' Сервер работает — новое применится после перезапуска гейма.' : ''}</div></div>
    <div class="card wide"><h3>Моды и ивенты</h3>${sw || '<div class="note">У этой сборки выключателей нет.</div>'}
      ${missing.length ? `<div class="note" style="margin-top:6px">В этой сборке пока нет: ${esc(missing.join(', '))}.</div>` : ''}</div>`;
  // Ключ уже стоит выключателем выше (ChampionEnable и т. п.) — его строку из карточек ниже убираем, чтобы не было двух.
  const swKeys = new Set(avail.flatMap((s) => s.keys));
  $('basic').querySelectorAll('.opt small').forEach((sm) => {
    if (!swKeys.has(sm.textContent) || sm.closest('#modesBox')) return;
    const card = sm.closest('.card');
    sm.closest('.opt').remove();
    if (card && !card.querySelector('.opt')) card.remove();
  });
  host.querySelectorAll('[data-mode]').forEach((b) => (b.onclick = async () => {
    const m = r.modes.find((x) => x.id === b.dataset.mode);
    if (!m) return document.querySelector('.tab[data-tab="all"]').click();
    if (Object.keys(edits).length) return toast('Сначала сохрани или отмени правки ниже.', true);
    if (!(await askModal({ title: `Режим «${esc(m.title)}»`, ok: 'Включить', body: `<p>${esc(m.note)}.</p><p class="note">Рейты и выключатели перепишутся разом, прежние конфиги — в бэкап (data\\backups\\config). Вернуть свои — «Все настройки» или другой режим.</p>` }))) return;
    const res = await api.apply_server_mode(id, m.id);
    if (!res.ok) return toast(res.error, true);
    toast(`Режим «${res.title}»: правок ${res.changed}${res.skipped.length ? `, в сборке нет: ${res.skipped.join(', ')}` : ''}${res.restart ? '. Перезапусти гейм-сервер, чтобы применить.' : '.'}`);
    await loadSchema();
  }));
  renderCalendar(host, id);
  host.querySelectorAll('[data-sw]').forEach((b) => (b.onclick = async () => {
    const on = b.getAttribute('aria-checked') !== 'true';
    const res = await api.set_mod_switch(id, b.dataset.sw, on);
    if (!res.ok) return toast(res.error, true);
    const s = avail.find((x) => x.id === b.dataset.sw);
    toast(`${s.title}: ${on ? 'вкл' : 'выкл'}${res.restart ? ` — после перезапуска ${s.part === 'login' ? 'логина' : 'гейма'}` : ''}.`);
    await loadSchema();
  }));
}

// Календарь сезонных событий и расписание PvP-ивентов (Api.event_calendar, modes.calendar, L2HubEvents.ini):
// окно каждого события полосой на год и строкой «дд ММ-дд ММ»; PvpEvents — выключателем выше, здесь его нет.
const DAYS_IN = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
const dayOfYear = (d, m) => DAYS_IN.slice(0, m - 1).reduce((s, x) => s + x, 0) + d - 1;
const WINDOW_RE = /^(\d{2}) (\d{2})(?: \d{4})?-(\d{2}) (\d{2})(?: \d{4})?$/;
const windowOk = (v) => { const m = WINDOW_RE.exec(v); return v === '' || /^off$/i.test(v) || (!!m && +m[1] >= 1 && +m[1] <= 31 && +m[2] >= 1 && +m[2] <= 12 && +m[3] >= 1 && +m[3] <= 31 && +m[4] >= 1 && +m[4] <= 12); };
function calBar(v) {
  const m = WINDOW_RE.exec(v || '');
  if (!m) return `<div class="cal-bar ${/^off$/i.test(v || '') ? 'off' : 'def'}" title="${/^off$/i.test(v || '') ? 'выключено' : 'как в config.xml события'}"></div>`;
  const a = dayOfYear(+m[1], +m[2]), b = dayOfYear(+m[3], +m[4]);
  const seg = (x, y) => `<i style="left:${(x / 365 * 100).toFixed(2)}%;width:${Math.max(0.8, (y - x) / 365 * 100).toFixed(2)}%"></i>`;
  return `<div class="cal-bar">${b > a ? seg(a, b) : seg(a, 365) + (b > 0 ? seg(0, b) : '')}</div>`;
}
async function renderCalendar(host, id) {
  const r = await api.event_calendar(id).catch(() => null);
  if (id !== selected || !host.isConnected || !r?.ok || !r.events.length) return;
  const name = (e) => { const i = e.note.indexOf(':'); return i > 0 && i < 40 ? [e.note.slice(0, i), e.note.slice(i + 1).trim()] : [e.key, e.note]; };
  const rows = r.events.map((e) => { const [n, d] = name(e); return `<div class="cal-row"><div class="cal-name" title="${esc(e.key)}: ${esc(e.note)}">${esc(n)}<small>${esc(d || e.key)}</small></div>
    ${calBar(e.value)}<input class="field cal-in" data-cal="${esc(e.key)}" data-was="${esc(e.value)}" value="${esc(e.value)}" placeholder="как в Mobius" spellcheck="false"></div>`; }).join('');
  const PVP = { PvpOnlyWithPlayers: 'Только при живом игроке', PvpMinLevel: 'Минимальный уровень', TvT: 'TvT — расписание', Deathmatch: 'Deathmatch — расписание', CtF: 'CtF — расписание' };
  const ELP = { ElpiesSchedule: 'Расписание', ElpiesMinutes: 'Длительность, мин', ElpiesOnlyWithPlayers: 'Только при живом игроке' };
  const rowsOf = (labels) => Object.entries(labels).filter(([k]) => r.pvp[k]).map(([k, t]) => { const x = r.pvp[k];
    const input = x.type === 'bool' ? `<select class="field cal-in" data-cal="${k}" data-was="${esc(x.value)}"><option${x.value === 'True' ? ' selected' : ''}>True</option><option${x.value === 'False' ? ' selected' : ''}>False</option></select>`
      : `<input class="field cal-in" data-cal="${k}" data-was="${esc(x.value)}" value="${esc(x.value)}" placeholder="${x.type === 'cron' ? 'не проводить' : ''}" spellcheck="false">`;
    return `<div class="cal-row pvp"><div class="cal-name">${t}<small>${esc(x.note || (x.type === 'cron' ? 'минуты часы день месяц день_недели' : ''))}</small></div><div></div>${input}</div>`; }).join('');
  const pvp = rowsOf(PVP), elp = rowsOf(ELP);
  const card = document.createElement('div');
  card.className = 'card wide cal-card';
  card.innerHTML = `<h3>Календарь событий<span class="src">L2HubEvents.ini</span></h3>
    <div class="note">Окно — «дд ММ-дд ММ» каждый год (конец — начало указанного дня; конец не позже начала — через Новый год, «01 01-01 01» — весь год), «дд ММ гггг-дд ММ гггг» — один раз, Off — выключено, пусто — как в Mobius. Действует со следующего запуска гейма.</div>
    <div class="cal-row cal-head"><div></div><div class="cal-months">${'ЯФМАМИИАСОНД'.split('').map((c) => `<span>${c}</span>`).join('')}</div><div></div></div>
    ${rows}${pvp ? `<div class="mode-group">PvP-ивенты</div>${pvp}` : ''}${elp ? `<div class="mode-group">Нашествие эльпи</div>${elp}` : ''}
    <div class="cal-foot"><span class="cal-state" id="calState"></span><button class="sbtn" id="calReset" disabled>Отменить</button><button class="sbtn primary" id="calSave" disabled>Сохранить календарь</button></div>`;
  host.querySelector('.cal-card')?.remove();
  host.appendChild(card);
  const inputs = [...card.querySelectorAll('[data-cal]')];
  const changed = () => inputs.filter((i) => i.value.trim() !== i.dataset.was);
  const sync = () => {
    let bad = 0;
    inputs.forEach((i) => {
      const isWin = !(i.dataset.cal in r.pvp);
      const ok = isWin ? windowOk(i.value.trim()) : true;
      i.classList.toggle('bad', !ok);
      i.classList.toggle('chg', i.value.trim() !== i.dataset.was);
      if (!ok) bad++;
      if (isWin && ok) i.previousElementSibling.outerHTML = calBar(i.value.trim());
    });
    const n = changed().length;
    $('calSave').disabled = !n || bad > 0;
    $('calReset').disabled = !n;
    $('calState').textContent = bad ? `ошибок в окнах: ${bad}` : n ? `изменено: ${n}` : '';
  };
  inputs.forEach((i) => (i.oninput = i.onchange = sync));
  $('calReset').onclick = () => { inputs.forEach((i) => (i.value = i.dataset.was)); sync(); };
  $('calSave').onclick = async () => {
    const values = Object.fromEntries(changed().map((i) => [i.dataset.cal, i.value.trim()]));
    const res = await api.save_calendar(id, values);
    if (!res.ok) return toast(res.error, true);
    toast(`Календарь: правок ${res.changed}${res.restart ? ' — после перезапуска гейма' : ''}. Прежний файл — в бэкапе конфигов.`);
    await loadSchema();
  };
}

function renderBasic() {
  const box = $('basic');
  box.innerHTML = '<div class="modes-box" id="modesBox"></div>';
  renderModes();
  for (const g of BASIC) {
    // Подпись — файлы, где ключи карточки нашлись в этой сборке (.ini у Mobius, .properties у La2offline); не нашлось ни одного — карточки нет.
    const names = g.rates ? g.rates.map(([key]) => (key === '@adena' ? 'DropAmountMultiplierByItemId' : key))
      : g.opts ? g.opts.map(([key]) => key) : ['ScreenWelcomeMessageEnable', 'ScreenWelcomeMessageText', 'ShowServerNews'];
    const found = names.map((n) => findKey(n)).filter(Boolean);
    if (!found.length) continue;
    const src = [...new Set(found.map((k) => k.file))].join(' · ');
    const card = document.createElement('div');
    card.className = 'card' + (g.wide || g.rates ? ' wide' : '');
    card.innerHTML = `<h3>${g.title}<span class="src">${esc(src)}</span></h3>`;
    if (g.rates) {
      const grid = document.createElement('div');
      grid.className = 'rates';
      for (const [key, label] of g.rates) {
        const cell = document.createElement('div');
        cell.className = 'num';
        if (key === '@adena') {
          const k = findKey('DropAmountMultiplierByItemId');
          if (!k) continue;
          const cur = (valOf(k).match(/(?:^|;)57,([\d.]+)/) || [])[1] || '1';
          cell.innerHTML = `<label>${label}</label><input value="${esc(cur)}"><small>DropAmountMultiplierByItemId · 57</small>`;
          const inp = cell.querySelector('input');
          inp.classList.toggle('changed', k.id in edits);
          inp.oninput = () => { setVal(k, valOf(k).replace(/(^|;)57,[\d.]+/, `$157,${inp.value}`)); inp.classList.toggle('changed', k.id in edits); };
        } else {
          const k = findKey(key);
          if (!k) continue;
          cell.innerHTML = `<label>${label}</label><input value="${esc(valOf(k))}"><small>${key}</small>`;
          const inp = cell.querySelector('input');
          inp.classList.toggle('changed', k.id in edits);
          inp.oninput = () => { setVal(k, inp.value); inp.classList.toggle('changed', k.id in edits); };
        }
        grid.appendChild(cell);
      }
      card.appendChild(grid);
    } else if (g.opts) {
      for (const [key, label, choices] of g.opts) {
        const k = findKey(key);
        if (!k) continue;
        const row = document.createElement('div');
        row.className = 'opt';
        row.innerHTML = `<span class="l">${label}<small>${key}</small></span>`;
        if (choices) {
          const s = document.createElement('select');
          s.innerHTML = choices.map((c) => `<option${c === valOf(k) ? ' selected' : ''}>${c}</option>`).join('');
          s.onchange = () => setVal(k, s.value);
          row.appendChild(s);
        } else if (k.type === 'bool') {
          row.appendChild(control(k));
        } else {
          const i = document.createElement('input');
          i.type = 'number'; i.value = valOf(k);
          i.oninput = () => setVal(k, i.value);
          row.appendChild(i);
        }
        card.appendChild(row);
      }
    } else if (g.welcome) {
      const en = findKey('ScreenWelcomeMessageEnable'), tx = findKey('ScreenWelcomeMessageText'), news = findKey('ShowServerNews');
      if (en && tx) {
        const row = document.createElement('div'); row.className = 'opt';
        row.innerHTML = '<span class="l">Сообщение на экране при входе<small>ScreenWelcomeMessageEnable · ScreenWelcomeMessageText</small></span>';
        row.appendChild(control(en)); card.appendChild(row);
        const t = document.createElement('textarea'); t.className = 'field'; t.value = valOf(tx);
        t.oninput = () => setVal(tx, t.value); card.appendChild(t);
      }
      if (news) {
        const row = document.createElement('div'); row.className = 'opt';
        row.innerHTML = '<span class="l">Окно новостей при входе<small>ShowServerNews · data/html/servnews.htm</small></span>';
        row.appendChild(control(news)); card.appendChild(row);
      }
    }
    box.appendChild(card);
  }
}

function renderFiles() {
  const box = $('files');
  box.innerHTML = '';
  const groups = [['Основные', (f) => f.part === 'game' && f.group === 'Core'], ['Кастомные фичи', (f) => f.part === 'game' && f.group === 'Custom'], ['Логин-сервер', (f) => f.part === 'login']];
  for (const [title, pred] of groups) {
    const files = FILES.filter(pred);
    if (!files.length) continue;
    box.insertAdjacentHTML('beforeend', `<div class="grp">${title}</div>`);
    for (const f of files) {
      const id = `${f.part}/${f.file}`;
      const ks = Object.values(KEYS).filter((k) => k.part === f.part && k.file === f.file);
      const pending = ks.filter((k) => k.id in edits).length;
      const b = document.createElement('button');
      b.className = 'fileb' + (id === curFile && !keyQuery ? ' on' : '');
      b.innerHTML = `${esc(f.file.replace(/\.(ini|properties)$/, ''))}${pending ? `<span class="chg">● ${pending}</span>` : ''}<span class="c">${ks.length}</span>`;
      b.onclick = () => { curFile = id; keyQuery = ''; $('keySearch').value = ''; renderFiles(); renderKeys(); };
      box.appendChild(b);
    }
  }
}

function renderKeys() {
  const box = $('keysList');
  box.innerHTML = '';
  const q = keyQuery.trim().toLowerCase();
  let list = Object.values(KEYS).filter((k) => (q ? `${k.key} ${k.description}`.toLowerCase().includes(q) : `${k.part}/${k.file}` === curFile));
  if (onlyChanged) list = list.filter((k) => isNonDefault(k) || k.id in edits);
  if (!list.length) { box.innerHTML = '<div class="empty">Ничего не нашлось.</div>'; return; }
  const shown = list.slice(0, 250);
  let sec = null;
  for (const k of shown) {
    const head = q ? `${k.part === 'login' ? 'Логин · ' : ''}${k.file}` : k.section;
    if (head !== sec) { sec = head; if (head) box.insertAdjacentHTML('beforeend', `<div class="sec-title">${esc(head)}</div>`); }
    const row = document.createElement('div');
    const mark = () => { row.className = 'kv' + (isNonDefault(k) ? ' changed' : '') + (k.id in edits ? ' edited' : ''); };
    mark();
    const left = document.createElement('div');
    left.innerHTML = `<div class="k">${esc(k.key)}${q ? `<span class="f"> · ${esc(k.file)}</span>` : ''}</div>${k.description ? `<div class="d">${esc(k.description)}</div>` : ''}${k.default != null ? `<div class="dv">по умолчанию: ${esc(k.default)}</div>` : ''}`;
    const ctl = document.createElement('div');
    ctl.appendChild(control(k, () => { mark(); renderFiles(); }));
    const rst = document.createElement('button');
    rst.className = 'reset';
    rst.title = k.id in edits ? 'Отменить правку' : 'Вернуть по умолчанию';
    rst.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5"/></svg>';
    rst.onclick = () => {
      if (k.id in edits) delete edits[k.id];
      else if (k.default != null) setVal(k, k.default);
      updatePending(); renderKeys(); renderFiles();
    };
    row.append(left, ctl, rst);
    box.appendChild(row);
  }
  if (list.length > shown.length) box.insertAdjacentHTML('beforeend', `<div class="empty">Показано ${shown.length} из ${list.length}. Уточни поиск.</div>`);
}
$('keySearch').oninput = (e) => { keyQuery = e.target.value; renderFiles(); renderKeys(); };
$('onlyChanged').onclick = (e) => { onlyChanged = !onlyChanged; e.currentTarget.setAttribute('aria-checked', onlyChanged); renderKeys(); };

function renderNet() {
  const p = profiles[selected];
  const rows = (pairs) => pairs.map(([a, b]) => `<div class="kvline"><span>${a}</span><span>${esc(b)}</span></div>`).join('');
  $('net').innerHTML = `
    <div class="card"><h3>Клиент</h3>${rows([['Папка', p.client.path], ['Запуск', p.client.exe], ['Система', p.client.system || '—'], ['Протокол', p.client.protocol || '—']])}
      <div class="row2"><button class="sbtn" data-path2="client">Открыть папку клиента</button></div></div>
    <div class="card"><h3>Сервер</h3>${rows([['Сборка', p.build], ['Папка', p.server], ['База', p.database], ['Порты', `логин ${p.ports.login}, гейм ${p.ports.game}, база ${p.ports.db}`]])}
      <div class="row2"><button class="sbtn" data-path2="config">Открыть конфиги</button></div></div>
    <div class="card wide" id="langCard" hidden></div>
    <div class="card wide"><h3>Как поменять</h3><div class="note">Папку клиента — кнопкой ниже или «указать» в шапке хроники. Игра с друзьями — раздел «Друзья». Всё остальное — профиль хроники <span style="font-family:var(--mono)">profiles\\${esc(selected)}.json</span>.</div>
      <div class="row2"><button class="sbtn" id="netPick">Указать другую папку клиента…</button></div></div>`;
  $('net').querySelectorAll('[data-path2]').forEach((b) => (b.onclick = () => call(api.open_path(selected, b.dataset.path2))));
  $('netPick').onclick = () => pickClient(selected);
  renderLang(selected);
  renderFresh(selected);
}

// Свежесть сборки Mobius (Api.mobius_freshness): закреплённый коммит против master — строкой в карточке «Сервер».
async function renderFresh(id) {
  const r = await api.mobius_freshness(id).catch(() => null);
  const card = [...document.querySelectorAll('#net .card')].find((c) => c.querySelector('h3')?.textContent === 'Сервер');
  if (id !== selected || !card || !r || (r.ok && !r.supported)) return;
  const text = !r.ok ? esc(r.error) : !r.behind ? `свежая — ${esc(r.pinned.date || r.pinned.commit)}`
    : `закреплена ${esc(r.pinned.date || r.pinned.commit)}, в master новее на ${r.behind >= 100 ? '100+' : r.behind} коммит(ов) — ${esc(r.latest.date)}`;
  const line = document.createElement('div');
  line.className = 'kvline';
  line.title = r.ok && r.behind ? 'Обновление сборки — пока через агента: наши патчи и врезки фантомов проверяются на новом коммите.' : '';
  line.innerHTML = `<span>Mobius</span><span>${text}</span>`;
  card.querySelector('.row2')?.before(line);
}

// Язык клиента 4game (Classic, Essence; Api.client_language, l2hub/clientsys.py): штатная system — английский (EU),
// system_ru — копия с языком Russia: ввод кириллицей; тексты русские, только если в неё положены тексты той же сборки.
async function renderLang(id) {
  const r = await api.client_language(id).catch(() => null);
  const box = $('langCard');
  if (id !== selected || !box || !r?.supported) return;
  box.hidden = false;
  const lang = r.current;
  box.innerHTML = `<h3>Язык клиента<span class="src">${lang === 'ru' ? 'system_ru' : 'system'}</span></h3>
    <div class="seg modes" id="langSeg"><button data-lang="ru" class="${lang === 'ru' ? 'on' : ''}">Русский</button><button data-lang="en" class="${lang === 'en' ? 'on' : ''}">English</button></div>
    <div class="note">${!r.client ? 'Сначала укажи папку клиента.' : lang === 'ru'
      ? 'Клиент запускается из system_ru — копии штатной системы с языком Russia: чат и ввод — кириллицей. Тексты меню — те, что лежат в system_ru: у EU-клиента своих русских нет, остаются английские.'
      : `Клиент запускается из штатной system (EU, английский). ${r.ruReady ? 'Русская system_ru уже есть — переключение мгновенное.' : 'Русский — лаунчер сделает system_ru, копию системы с языком Russia (штатная не меняется), это займёт минуту.'}`}</div>`;
  box.querySelectorAll('[data-lang]').forEach((b) => (b.onclick = async () => {
    if (b.dataset.lang === lang) return;
    const res = await api.set_client_language(id, b.dataset.lang);
    if (!res.ok) return toast(res.error, true);
    toast(b.dataset.lang === 'ru' && !r.ruReady ? 'Делаю system_ru — прогресс в строке состояния.' : 'Переключаю язык клиента…');
    await poll();
  }));
}

document.querySelectorAll('.tab').forEach((t) => (t.onclick = () => {
  document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('on', x === t));
  document.querySelectorAll('.pane').forEach((p) => p.classList.toggle('on', p.id === 'pane-' + t.dataset.tab));
  if (t.dataset.tab === 'basic') renderBasic();
  if (t.dataset.tab === 'all') { renderFiles(); renderKeys(); }
}));
$('discardBtn').onclick = () => { edits = {}; renderBasic(); renderFiles(); renderKeys(); updatePending(); };
$('saveBtn').onclick = async () => {
  const list = Object.entries(edits).map(([id, value]) => { const k = KEYS[id]; return { part: k.part, file: k.file, key: k.key, value }; });
  if (!list.length) return toast('Изменений нет.');
  const r = await api.save_settings(selected, list);
  if (!r.ok) return toast(`Не сохранено: ${r.error}`, true);
  edits = {};
  await loadSchema();
  toast(`Сохранено ${r.saved} правок в ${r.files} файл(ах). Бэкап: ${r.backup}` + (last.running === selected ? '. Перезапусти гейм-сервер, чтобы применить.' : ''));
};

// ---------- Окно ----------
$('wMin').onclick = () => (DEV ? toast('В браузере окно не сворачивается.') : api.minimize());
$('wMax').onclick = () => (DEV ? toast('В браузере окно не разворачивается.') : api.toggle_maximize());
// Двойной щелчок по шапке — развернуть или вернуть, как у обычного окна.
document.querySelector('.titlebar').addEventListener('dblclick', (e) => {
  if (e.target.closest('button') || DEV) return;
  api.toggle_maximize();
});
// Края безрамочного окна: тянет Python (winframe.start_resize), пока зажата кнопка мыши.
document.querySelectorAll('.rz').forEach((el) => (el.onpointerdown = (e) => {
  if (DEV || e.button !== 0) return;
  e.preventDefault();
  api.start_resize(el.dataset.rz);
}));
// Крестик: что делать — из настроек L2Hub (closeRunning / closeIdle); «ask» — окно с выбором.
$('wClose').onclick = async () => {
  const running = await api.running();
  if (!running) return appSet.closeIdle === 'tray' ? closeAs('tray') : closeAs('exit');
  if (appSet.closeRunning === 'ask') return askClose();
  closeAs(appSet.closeRunning);
};
// Окно выбора — и из трея («Выйти»), где спрашиваем всегда, что бы ни стояло в настройках.
async function askClose() {
  const running = await api.running();
  if (!running) return closeAs('exit');
  const gm = last.running === running && last.components ? last.components.game : null;
  const up = gm && gm.uptime_s ? ` ${fmtUptime(gm.uptime_s)}` : '';
  $('closeText').textContent = `${byId(running)?.name || running} работает${up}. Куда деть L2Hub?`;
  $('cmRemember').checked = false;
  $('closeModal').classList.add('on');
}
window.askClose = askClose;
async function closeAs(kind) {
  if (DEV) return toast(`В браузере окно не закрывается (выбрано: ${kind}).`);
  if (kind === 'tray') {
    const r = await api.hide_to_tray();
    return r.ok ? null : toast(r.error, true);
  }
  if (kind === 'stop') {
    const running = await api.running();
    if (running) {
      await api.stop(running);
      while (true) { await new Promise((r) => setTimeout(r, 700)); const s = await api.state(running); last = s; renderHealth(); if (!s.job || s.job.done) break; }
    }
  }
  api.close();
}
async function closeChoice(kind) {
  if ($('cmRemember').checked) await saveApp({ closeRunning: kind });
  $('closeModal').classList.remove('on');
  closeAs(kind);
}
$('cmCancel').onclick = () => $('closeModal').classList.remove('on');
$('cmTray').onclick = () => closeChoice('tray');
$('cmStop').onclick = () => closeChoice('stop');
$('cmLeave').onclick = () => closeChoice('leave');

// ---------- Старт ----------
$('chronSearch').oninput = (e) => { query = e.target.value; renderList(); };
document.querySelectorAll('.fchip').forEach((b) => (b.onclick = () => {
  filter = b.dataset.f;
  document.querySelectorAll('.fchip').forEach((x) => x.classList.toggle('on', x === b));
  renderList();
}));

(async function init() {
  catalog = await api.catalog();
  appSet = await api.app_settings();
  fbEnabled = await api.feedback_enabled().catch(() => false);
  if (appSet.checkUpdates) setTimeout(async () => {
    const r = await api.update_check(false).catch(() => null);
    renderUpdate(r);
    if (r?.newer) toast(`Вышла L2Hub ${r.latest} — «L2Hub» → «Обновления».`);
  }, 4000);
  document.querySelectorAll('.fb-only').forEach((el) => (el.style.display = fbEnabled ? '' : 'none'));
  if (DEV) document.title = 'L2Hub · дев-режим';
  last.running = await api.running();
  if (!byId(selected) || !visibleChron(byId(selected))) selected = last.running || 'ct26_highfive';
  const ver = `v${await api.version()}`; // единственное место версии — l2hub/__init__.py
  $('versionTag').textContent = ver;
  $('aboutVer').textContent = `L2Hub ${ver.slice(1)}`;
  $('footVer').textContent = DEV ? `${ver} · дев-режим` : ver;
  await select(selected);
  showSection(section);
  setInterval(poll, 1000);
})();
