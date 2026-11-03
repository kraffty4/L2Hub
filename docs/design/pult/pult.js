// Макет «Пульт»: данные выключателей читает из server_modes.json, всё считает здесь же, никуда не пишет.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const ru = (x, d = 1) => (Math.round(x * 10 ** d) / 10 ** d).toString().replace('.', ',');
const ASSETS = '../../../launcher/l2hub/ui/assets/';

const THEMES = [
  ['gold', 'Золото Адена'], ['parchment', 'Пергамент'], ['elven', 'Эльфийский лес'], ['darkelf', 'Тёмные эльфы'], ['orc', 'Орда орков'], ['freya', 'Лёд Фрейи'], ['graphite', 'Графит'],
  ['player98', 'Плеер 98', 'retro'], ['gray2000', 'Серый 2000', 'retro'], ['hill2001', 'Холм 2001', 'retro'], ['gloss2004', 'Глянец 2004', 'retro'], ['panels', 'Синие панели', 'retro'],
];
const CHRONS = [
  ['Chaotic Chronicle', [['c4', 'Scions of Destiny (C4)', 'c4_login'], ['interlude', 'Interlude', 'interlude_login']]],
  ['Chaotic Throne', [['gracia_final', 'Gracia Final', 'gracia_final_login'], ['epilogue', 'Gracia Epilogue', 'epilogue_login'], ['ct26_highfive', 'High Five', 'ct26_highfive_login']]],
  ['Classic', [['classic_tales', 'Tales Untold', 'classic_login']]],
  ['Essence', [['ess_crow', 'Samurai Crow', 'essence_login']]],
];
const NATIVE_CHRONS = new Set(['classic_tales', 'ess_crow']);

// Предлагаемое дерево настроек. Все 63 выключателя — ровно по разу (проверка в конце файла).
const TREE = [
  { head: 'Как играется', pages: [
    { id: 'mode', title: 'Режим и рейты', about: 'Готовый набор одной кнопкой. Рейты и выключатели перепишутся разом, прежние конфиги уйдут в бэкап.', kind: 'mode' },
    { id: 'rules', title: 'Правила игры', about: 'Как устроена игра для всех персонажей. Это ключи конфигов, они применяются кнопкой «Сохранить».', keys: [['Автоподбор дропа', 'AutoLoot', 'bool', 1], ['Автоподбор хербов', 'AutoLootHerbs', 'bool', 1], ['Автоизучение скиллов', 'AutoLearnSkills', 'bool', 1], ['Саб-класс без квеста', 'AltSubClassWithoutQuests', 'bool', 1], ['Максимум сабов', 'MaxSubclass', 'num', 3], ['Стартовая адена', 'StartingAdena', 'num', 0], ['Виталити', 'EnableVitality', 'bool', 1], ['Олимпиада', 'OlympiadEnabled', 'bool', 1], ['Перерождения', 'RebirthAllow', 'bool', 0], ['Свадьбы', 'AllowWedding', 'bool', 0], ['Удалять персонажа через, дней', 'DeleteCharAfterDays', 'num', 7]] },
  ] },
  { head: 'Живой мир', pages: [
    { id: 'people', title: 'Население', about: 'Сколько народу в мире и как он живёт по времени. Здесь главный рычаг нагрузки.', kind: 'people', subs: [['Фейки в городах', ['fakes', 'fakestores', 'fakemarket']], ['Ритм жизни', ['dailyrhythm', 'regulars', 'absences', 'offlineworld', 'welcome']]] },
    { id: 'hunters', title: 'Поведение охотников', short: 'Охотники', about: 'Что охотники делают рядом с тобой и друг с другом.', subs: [['Общение', ['gestures', 'favors', 'farwhisper', 'hunterfriends', 'gifts', 'stories', 'petition']], ['Торговля', ['chatflow', 'huntertrade', 'hunterenchant', 'scams']], ['Конфликты', ['spotdisputes', 'lootfights']]] },
    { id: 'clans', title: 'Кланы охотников', short: 'Кланы', about: 'Жизнь кланов: от титулов и эмблем до драм и настоящих осад.', subs: [['Внутри клана', ['clantitles', 'clancrests', 'clandrama']], ['Ты и кланы', ['claninvite', 'clanrecruit', 'clanwarreply']], ['Кланы между собой', ['clanally', 'clantop', 'sieges']]] },
    { id: 'bosses', title: 'Боссы и монстры', about: 'Кто бродит по миру и кто за ним охотится.', subs: [['Монстры', ['champions', 'superchampion']], ['Рейд-боссы', ['rbannounce', 'raids', 'raidrivals', 'raidwatch', 'raidtakebusy']]] },
    { id: 'small', title: 'Малый мир', about: 'Правила, смягчённые под маленький онлайн: то, что на официале требовало толпу.', subs: [['', ['smallepics', 'smallclans', 'sepulchers', 'rift']]] },
  ] },
  { head: 'Удобства', pages: [
    { id: 'town', title: 'Город', about: 'NPC и наборы, которые экономят время.', subs: [['У гейткипера', ['buffer', 'gmshop']], ['Новичку', ['starterkits']], ['Бафы', ['durations']], ['Мастера', ['classmaster', 'noblesse']]] },
    { id: 'helpers', title: 'Помощники', about: 'Что упрощает игру персонажу.', subs: [['', ['autopotions', 'offlineplay', 'freemounts', 'transmog', 'unstuck', 'npcinfo']]], keys: [['Автобой .play', 'EnableAutoPlay', 'bool', 1], ['Оффлайн-торговля', 'OfflineTradeEnable', 'bool', 1]] },
    { id: 'events', title: 'Ивенты', about: 'Что случается по расписанию.', subs: [['Каждый день', ['daily', 'elpies']], ['PvP', ['pvpevents', 'hunterpvp']], ['Сезонные', ['calendar']]], fold: 'Календарь и расписание PvP' },
    { id: 'native', title: 'Родное Classic и Essence', short: 'Родное', about: 'Штатные механики этих хроник. Страницы нет там, где их нет.', only: NATIVE_CHRONS, subs: [['', ['attendance', 'lcoin', 'huntpass', 'magiclamp']]] },
  ] },
  { head: 'Служебное', pages: [
    { id: 'entry', title: 'Вход и права GM', short: 'Вход и GM', about: 'Что видит игрок при входе и какие права у GM.', subs: [['Вход', ['licence']]], keys: [['Окно новостей при входе', 'ShowServerNews', 'bool', 0], ['Новые персонажи — в Гиран', 'CustomStartingLocation', 'bool', 0], ['GM входит невидимым', 'GMStartupInvisible', 'bool', 0], ['GM входит бессмертным', 'GMStartupInvulnerable', 'bool', 0], ['GM без личных сообщений', 'GMStartupSilence', 'bool', 0], ['Все игроки — админы', 'EverybodyHasAdminRights', 'bool', 0]] },
    { id: 'gmshop', title: 'Цены GM-шопа', stub: 'Сюда встаёт редактор цен GM-шопа из соседней сессии: дерево разделов слева заменяется поиском и фильтрами. Он уже есть отдельной вкладкой, переезжает без переделки.' },
    { id: 'net', title: 'Клиент и сеть', stub: 'Прежняя вкладка «Клиент и сеть» без изменений: папка клиента, порты, база, язык.' },
    { id: 'keys', title: 'Все ключи', badge: '1895', stub: 'Прежняя вкладка «Все настройки»: файлы, поиск, «только изменённые». Обычный пользователь сюда не ходит, поэтому она последняя в списке.' },
  ] },
];
const PAGES = TREE.flatMap((g) => g.pages);

// Заглушки цен: ядра, ГБ, МБ логов в час. Настоящие снимет замер (machine_costs.json, source: measured).
const COST = {
  fakes: { cpu: 0.05, ram: 0.15 }, fakestores: { cpu: 0.04, ram: 0.05 }, fakemarket: { cpu: 0.03, ram: 0.03 }, chatflow: { cpu: 0.03, log: 10 }, sieges: { cpu: 0.12, ram: 0.1 }, raids: { cpu: 0.08 }, raidwatch: { cpu: 0.03 }, regulars: { cpu: 0.06 },
  stories: { log: 2 }, champions: { cpu: 0.02, ram: 0.02 }, superchampion: { cpu: 0.01 }, elpies: { cpu: 0.06, ram: 0.7 }, offlineplay: { cpu: 0.02 }, pvpevents: { cpu: 0.03 }, hunterpvp: { cpu: 0.03 }, hunterenchant: { cpu: 0.01 }, clandrama: { log: 1 },
};
const PC = { a: { cores: 6, speed: 1, ram: 32, heap: 8, vram: 16, free: 300 }, b: { cores: 4, speed: 0.75, ram: 16, heap: 6, vram: 8, free: 120 }, c: { cores: 2, speed: 0.5, ram: 8, heap: 4, vram: 2, free: 40 } };
const HEAP_LIMIT = (h) => (h >= 8 ? 1000 : h >= 6 ? 500 : h >= 5 ? 219 : 50);
const TICK = [[223, 176], [504, 232], [704, 307], [1000, 395]]; // docs/PHANTOMS.md, раздел 4.120
const tickMs = (n, speed) => {
  let a = TICK[0], b = TICK[1];
  for (let i = 0; i < 3; i++) if (n >= TICK[i][0]) { a = TICK[i]; b = TICK[i + 1]; }
  return Math.max(60, a[1] + ((n - a[0]) * (b[1] - a[1])) / (b[0] - a[0])) / speed;
};

const S = { on: {}, hunters: 219, top: 219, page: 'people', chron: 'ct26_highfive', pc: 'a', running: false, client: true, brain: 'cloud', q: '', openRes: null, edits: 0 };
let SW = {}; let MODES = []; let hover = null;

function plan(on, hunters, st = S) {
  const pc = PC[st.pc];
  const sum = (k) => Object.keys(on).filter((id) => on[id] && COST[id]?.[k]).reduce((a, id) => a + COST[id][k], 0);
  const parts = { cpu: [], ram: [], disk: [], vram: [] };
  const add = (res, title, v, draft) => v && parts[res].push({ title, v, draft });
  add('cpu', 'Сервер без охотников', 0.3); add('cpu', `Охотники, ${hunters}`, 0.004 * hunters); add('cpu', 'Моды', sum('cpu'), true);
  add('ram', 'Сервер без охотников', 5); add('ram', `Охотники, ${hunters}`, 0.002 * hunters); add('ram', 'Моды', sum('ram'), true);
  add('disk', 'Сервер', 2.3); add('disk', 'Логи за сутки', ((0.2 * hunters + sum('log')) * 24) / 1024);
  if (st.client) { add('cpu', 'Клиент', 1, true); add('ram', 'Клиент', 3, true); add('vram', 'Клиент', 1, true); }
  if (st.brain === 'local') { add('cpu', 'Мозги LM Studio', 1, true); add('ram', 'Мозги LM Studio', 2, true); add('vram', 'Мозги LM Studio', 19.7, true); }
  const total = (r) => parts[r].reduce((a, p) => a + p.v, 0);
  const solid = (r) => parts[r].filter((p) => !p.draft).reduce((a, p) => a + p.v, 0);
  return {
    pc, parts, total, solid, hunters,
    have: { cpu: pc.cores * pc.speed, ram: pc.ram, disk: pc.free, vram: pc.vram },
    heap: (3100 + 1.3 * hunters) / 1024 + sum('ram') * 0.5, tick: tickMs(hunters, pc.speed), startSec: 2 * hunters, logH: 0.2 * hunters + sum('log'),
    rec: Math.min(HEAP_LIMIT(pc.heap), Math.floor((1000 * pc.speed) / 0.56)),
  };
}
const level = (p) => (p >= 100 ? 'over' : p >= 70 ? 'tight' : 'ok');

// Живая нагрузка: /live отдаёт serve.py. На простом http.server её нет: тогда полоски — только прогноз.
const RES = [['cpu', 'ЦПУ'], ['ram', 'ОЗУ'], ['vram', 'Видеопамять'], ['disk', 'Диск']];
let LIVE = null;
const HIST = { cpu: [], ram: [], vram: [] };
const isLive = () => S.pc === 'live' && LIVE;
const oursRunning = () => !!LIVE && LIVE.ours.some((p) => /java/i.test(p.name) && p.gb > 0.5);
const clientRunning = () => !!LIVE && LIVE.ours.some((p) => /^l2\./i.test(p.name));

async function pollLive() {
  try {
    const r = await fetch('/live');
    const d = r.ok ? await r.json() : null;
    if (!d?.ok) return;
    const first = !LIVE;
    LIVE = d;
    if (first) {
      PC.live = { cores: d.cores, speed: 1, ram: d.ramTotal, heap: Math.max(4, Math.min(8, Math.floor((d.ramTotal * 3) / 8))), vram: d.gpu?.vramTotal || 0, free: d.diskFree };
      $('mbPc').insertAdjacentHTML('afterbegin', `<option value="live">Этот ПК, живьём</option>`);
      $('mbPc').value = 'live';
      S.pc = 'live';
      $('mbRunWrap').hidden = true;
      renderAll();
    }
    PC.live.free = d.diskFree;
    if (d.gpu) PC.live.vram = d.gpu.vramTotal;
    const P = plan(S.on, S.hunters);
    for (const r2 of ['cpu', 'ram', 'vram']) { const a = HIST[r2]; a.push(actual(r2, P)); if (a.length > 120) a.shift(); }
    renderLoad();
  } catch { /* нет /live — обычный макет */ }
}

// Что на самом деле занято сейчас: другие программы и наши процессы. В единицах полоски.
function actual(r, P) {
  if (!LIVE) return { others: 0, ours: 0 };
  const sum = (k) => LIVE.ours.reduce((a, p) => a + p[k], 0);
  if (r === 'cpu') {
    const ours = sum('cpu') / 100;
    return { ours, others: Math.max(0, ((LIVE.cpuPct / 100) * LIVE.threads - ours) * (P.have.cpu / LIVE.threads)) };
  }
  if (r === 'ram') { const ours = sum('gb'); return { ours, others: Math.max(0, LIVE.ramUsed - ours) }; }
  if (r === 'vram') return { ours: 0, others: LIVE.gpu?.vramUsed || 0 };
  return { ours: 0, others: 0 };
}

function layers(P, r, H) {
  const have = P.have[r];
  const forecast = P.total(r);
  let o = 0, s = P.solid(r), d = forecast - s;
  if (isLive()) {
    const a = actual(r, P);
    o = a.others;
    if (r !== 'disk' && oursRunning()) {
      s = a.ours;
      d = P.parts[r].filter((p) => p.draft && p.title !== 'Моды' && !(p.title === 'Клиент' && clientRunning())).reduce((x, p) => x + p.v, 0);
    }
  }
  return { o, s, d, g: H ? H.total(r) - forecast : 0, have, total: o + s + d };
}

function PB(id, extra = '') { return `<div class="pb ${extra}" id="${id}" data-x="${extra}"><i class="o"></i><i class="s"></i><i class="d"></i><i class="g"></i><span class="seg-mask"></span></div>`; }

function setPb(id, { o = 0, s = 0, d = 0, g = 0, have }) {
  const el = $(id);
  if (!el) return;
  const pct = (x) => (have > 0 ? Math.min(100, Math.max(0, (x * 100) / have)) : 0);
  const a = pct(o), b = pct(o + s), c = pct(o + s + d);
  const [oI, sI, dI, gI] = el.children;
  oI.style.width = `${a}%`;
  sI.style.left = `${a}%`; sI.style.width = `${b - a}%`;
  dI.style.left = `${b}%`; dI.style.width = `${c - b}%`;
  if (g > 0) { const e = pct(o + s + d + g); gI.className = 'g up'; gI.style.left = `${c}%`; gI.style.width = `${e - c}%`; }
  else if (g < 0) { const f = pct(o + s + d + g); gI.className = 'g down'; gI.style.left = `${f}%`; gI.style.width = `${c - f}%`; }
  else { gI.className = 'g'; gI.style.width = '0'; }
  const lv = have > 0 ? level(((o + s + d + Math.max(g, 0)) * 100) / have) : 'ok';
  el.className = `pb ${el.dataset.x} ${lv}`;
}

function buildLoad() {
  const sub = (id, t) => `<div class="lsub"><span>${t}</span><span id="v-${id}"></span></div>${PB(`pb-${id}`, 'thin')}`;
  $('pload').innerHTML = `<div class="pload-h">Нагрузка на ПК<button class="w-link" id="canRun">Что потянет</button></div>
    <div class="llegend" id="llegend"><span><i class="k o"></i>другие программы</span><span><i class="k s"></i>наше</span><span><i class="k d"></i>прогноз</span></div>
    ${RES.map(([id, t]) => `<button class="lres" data-res="${id}"><div class="lres-h"><b>${t}</b><span id="v-${id}"></span></div>${PB(`pb-${id}`)}</button>
      ${id === 'cpu' ? sub('tick', 'тик мира') : id === 'ram' ? sub('heap', 'куча гейма') : ''}<div class="lparts" id="parts-${id}" hidden></div>`).join('')}
    <div class="lline"><span>Логи</span><span id="v-log"></span></div>
    <div class="lline"><span>Старт мира</span><span id="v-start"></span></div>
    <div class="lline" id="ioLine" hidden><span>Диск сейчас</span><span id="v-io"></span></div>
    <div id="verdict"></div>
    <div class="lwhatif">
      <div class="lw-h">А что, если</div>
      <div class="lw-r"><span>Клиент на этом же ПК</span><button class="switch" role="switch" aria-checked="true" id="wClient"></button></div>
      <div class="lw-r"><span>Мозги</span><select id="wBrain"><option value="off">выключены</option><option value="cloud">в облаке</option><option value="local">LM Studio на ПК</option></select></div>
    </div>`;
  $('wBrain').value = S.brain;
  $('wClient').onclick = () => { S.client = !S.client; $('wClient').setAttribute('aria-checked', S.client); renderAll(); };
  $('wBrain').onchange = (e) => { S.brain = e.target.value; renderAll(); };
  $('canRun').onclick = openDrawer;
  document.querySelectorAll('.lres').forEach((b) => (b.onclick = () => { S.openRes = S.openRes === b.dataset.res ? null : b.dataset.res; renderLoad(); }));
  $('dockBars').innerHTML = RES.map(([id, t]) => `<span class="dbar"><small>${t === 'Видеопамять' ? 'Видео' : t}</small>${PB(`dpb-${id}`, 'mini')}</span>`).join('');
}

function sparkline(r) {
  const a = HIST[r];
  if (!a || a.length < 2) return '';
  const have = plan(S.on, S.hunters).have[r] || 1;
  const pts = (k) => a.map((p, i) => `${(i * 200) / 119},${28 - Math.min(1, (k(p) / have)) * 26}`).join(' ');
  return `<svg class="spark" viewBox="0 0 200 28" preserveAspectRatio="none"><polyline points="${pts((p) => p.others + p.ours)}" class="all"/><polyline points="${pts((p) => p.ours)}" class="ours"/></svg><div class="spark-t">последние ${a.length} с</div>`;
}

function renderParts(id, P, fmt, unit) {
  const box = $(`parts-${id}`);
  const open = S.openRes === id;
  box.hidden = !open;
  document.querySelector(`.lres[data-res="${id}"]`).classList.toggle('open', open);
  if (!open) return;
  const row = (t, v, cls = '') => `<div class="${cls}"><span>${esc(t)}</span><span>${v}</span></div>`;
  let html = isLive() ? sparkline(id) : '';
  if (isLive() && oursRunning() && (id === 'cpu' || id === 'ram')) {
    html += LIVE.ours.map((p) => row(p.name, id === 'ram' ? `${ru(p.gb)} ГБ` : `${ru(p.cpu / 100)} ядра`)).join('');
  } else {
    html += P.parts[id].slice().sort((a, b) => b.v - a.v).map((p) => row(p.title, `${fmt(p.v)} ${unit}`, p.draft ? 'draft' : '')).join('');
  }
  if (isLive() && id === 'ram') html += LIVE.top.map((p) => row(p.name, `${ru(p.gb)} ГБ`, 'other')).join('');
  if (isLive() && id === 'cpu') html += row('Другие программы', `${ru(actual('cpu', P).others)} ядра`, 'other');
  if (isLive() && id === 'vram') html += row('Занято сейчас всеми', `${ru(actual('vram', P).others)} ГБ`, 'other') + row('Загрузка видеокарты', `${Math.round(LIVE.gpu?.util || 0)} %`, 'other');
  box.innerHTML = html || row('Пока никто не занимает', '');
}

function renderLoad() {
  if (!$('pb-cpu')) buildLoad();
  const P = plan(S.on, S.hunters);
  const H = hover ? plan(hover.on, hover.hunters ?? S.hunters) : null;
  const fmt = (x) => ru(x);
  const L = {};
  for (const [id] of RES) {
    const seg = layers(P, id, H);
    L[id] = seg;
    setPb(`pb-${id}`, seg);
    setPb(`dpb-${id}`, seg);
    const unit = id === 'cpu' ? (Math.round(seg.have) === 1 ? 'ядра' : 'ядер') : 'ГБ';
    const dEl = Math.abs(seg.g) >= 0.005 ? ` <em class="${seg.g > 0 ? 'up' : 'down'}">${seg.g > 0 ? '+' : '−'}${ru(Math.abs(seg.g), Math.abs(seg.g) < 0.1 ? 2 : 1)}</em>` : '';
    $(`v-${id}`).innerHTML = seg.have ? `${fmt(seg.total)} из ${fmt(seg.have)} ${unit}${dEl}` : 'нет данных';
    renderParts(id, P, fmt, unit);
  }
  setPb('pb-tick', { s: P.tick, g: H ? H.tick - P.tick : 0, have: 1000 });
  $('v-tick').textContent = `${Math.round(P.tick)} из 1000 мс`;
  setPb('pb-heap', { s: P.heap, g: H ? H.heap - P.heap : 0, have: P.pc.heap });
  $('v-heap').textContent = `${ru(P.heap)} из ${P.pc.heap} ГБ`;
  $('v-log').textContent = `${Math.round(P.logH)} МБ в час`;
  $('v-start').textContent = `${Math.floor(P.startSec / 60)} мин ${P.startSec % 60} с`;
  $('ioLine').hidden = !isLive();
  if (isLive()) $('v-io').textContent = `${ru(LIVE.diskMbs)} МБ/с`;
  $('llegend').hidden = !isLive();

  const over = S.hunters > P.rec;
  const lv = RES.map(([r]) => (L[r].have ? level((L[r].total * 100) / L[r].have) : 'ok'));
  const othersEat = isLive() && L.ram.o > L.ram.s + L.ram.d && lv[1] !== 'ok';
  const verdict = over ? `<div class="lv bad">Этому ПК рекомендуем до ${P.rec} охотников, сейчас ${S.hunters}. Запустить можно, но мир будет тормозить. <button class="sbtn" id="fit">Поставить ${P.rec}</button></div>`
    : L.vram.have && L.vram.total > L.vram.have ? '<div class="lv bad">Модель мозгов не влезет в видеопамять целиком: часть уйдёт в ОЗУ, и думать они будут в разы медленнее.</div>'
      : othersEat ? `<div class="lv warn">Память съели другие программы: ${esc(LIVE.top[0]?.name || '')}, ${ru(LIVE.top[0]?.gb || 0)} ГБ. Закрой лишнее перед запуском.</div>`
        : lv.includes('over') ? '<div class="lv bad">Что-то не влезает: разверни полоску и посмотри, кто её съел.</div>'
          : lv.includes('tight') ? '<div class="lv warn">Впритык, но работать будет.</div>' : '<div class="lv ok">Хватает на всё, что включено.</div>';
  if ($('verdict').dataset.html !== verdict) {
    $('verdict').dataset.html = verdict;
    $('verdict').innerHTML = verdict;
    if ($('fit')) $('fit').onclick = () => { S.hunters = P.rec; renderAll(); };
  }
  renderDock(P);
}

function renderDock(P) {
  const run = isLive() ? oursRunning() : S.running;
  $('dockDot').className = 'dot' + (run ? ' on' : '');
  $('dockName').textContent = run ? 'Сервер работает' : 'Ничего не запущено';
  $('dockRun').className = 'drun' + (run ? '' : ' idle');
  $('dockMeta').textContent = run && LIVE ? `· наши процессы ${ru(LIVE.ours.reduce((a, p) => a + p.gb, 0))} ГБ` : '';
}

function costChip(id) {
  const c = COST[id];
  if (!c) return '<span class="cost free">даром</span>';
  const P = plan(S.on, S.hunters);
  const shares = [['ЦПУ', (c.cpu || 0) / P.have.cpu, `+${ru(c.cpu || 0, 2)}`], ['ОЗУ', (c.ram || 0) / P.have.ram, `+${Math.round((c.ram || 0) * 1024)} МБ`], ['Диск', c.log ? 0.0001 : 0, `+${c.log} МБ/ч`]];
  const top = shares.sort((a, b) => b[1] - a[1])[0];
  const heavy = (c.cpu || 0) >= 0.1 || (c.ram || 0) >= 0.5;
  return `<span class="cost ${heavy ? 'heavy' : ''}">${top[0]} ${top[2]}</span>`;
}

function row(id) {
  const s = SW[id];
  if (!s) return '';
  const restart = /перезапуск/i.test(s.note);
  const brain = /Только с мозгами/i.test(s.note);
  return `<div class="prow" data-id="${id}"><button class="prow-m"><span class="prow-t">${esc(s.title)}</span><span class="prow-n">${esc(s.note)}</span></button>
    <span class="prow-tags">${restart ? '<i>перезапуск</i>' : ''}${brain ? '<i>мозги</i>' : ''}</span>${costChip(id)}
    <button class="switch" role="switch" aria-checked="${!!S.on[id]}" data-sw="${id}"></button></div>`;
}

function keyRow([title, key, type, val]) {
  const ctl = type === 'bool' ? `<button class="switch" role="switch" aria-checked="${!!val}" data-key></button>` : `<input class="field knum" value="${val}" data-key>`;
  return `<div class="prow key"><div class="prow-m"><span class="prow-t">${esc(title)}</span><span class="prow-k">${esc(key)}</span></div><span class="prow-tags"></span>${ctl}</div>`;
}

function pageIds(p) { return (p.subs || []).flatMap(([, ids]) => ids); }

function renderNav() {
  $('pnav').innerHTML = TREE.map((g) => {
    const items = g.pages.filter((p) => !p.only || p.only.has(S.chron)).map((p) => {
      const ids = pageIds(p);
      const n = ids.length ? `<span class="c">${ids.filter((i) => S.on[i]).length}/${ids.length}</span>` : p.badge ? `<span class="c">${p.badge}</span>` : '';
      return `<button class="${p.id === S.page && !S.q ? 'on' : ''}" data-p="${p.id}"><span>${esc(p.short || p.title)}</span>${n}</button>`;
    }).join('');
    return `<div class="pnav-h">${esc(g.head)}</div>${items}`;
  }).join('');
  document.querySelectorAll('#pnav button').forEach((b) => (b.onclick = () => { S.page = b.dataset.p; S.q = ''; $('q').value = ''; renderAll(); }));
}

function activeMode() {
  return MODES.find((m) => m.id !== 'live' && Object.entries(m.switches).every(([id, v]) => !SW[id] || !!S.on[id] === v)) || null;
}

function modeOn(m) { const on = { ...S.on }; for (const [id, v] of Object.entries(m.switches)) on[id] = v; return on; }

function renderModePage() {
  const act = activeMode();
  const cards = MODES.map((m) => `<button class="mcard ${act?.id === m.id ? 'on' : ''}" data-m="${m.id}"><b>${esc(m.title)}</b><span>${esc(m.note.split(/[:—]/)[m.id === 'hardcore' ? 0 : 1] || m.note).trim().slice(0, 84)}</span></button>`).join('');
  const R = act?.rates || {};
  const v = (k) => R[k] ?? 1;
  const rate = (t, k, val) => `<div class="num"><label>${t}</label><input value="${val ?? v(k)}" data-key></div>`;
  return `<div class="mcards">${cards}</div>
    <h4 class="psub">Рейты</h4>
    <div class="rates five">${rate('Опыт', 'xp')}${rate('SP', 'sp')}${rate('Адена', 'adena')}${rate('Дроп', 'drop')}${rate('Спойл', 'spoil')}</div>
    <button class="pfold" data-fold="fineRates"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M6 9l6 6 6-6"/></svg>Тонкие рейты: группа, шансы, рейды, хербы, квесты, манор</button>
    <div class="pfold-body" id="fineRates"><div class="rates">${[['Опыт в группе', 1], ['SP в группе', 1], ['Дроп: шанс', 1], ['Спойл: шанс', 1], ['Рейды: количество', 1], ['Рейды: шанс', 1], ['Хербы', 1], ['Квесты: опыт', 1], ['Квесты: SP', 1], ['Квесты: адена', 1], ['Манор', 1]].map(([t, x]) => rate(t, '', x)).join('')}</div></div>`;
}

function renderPeople(p) {
  const P = plan(S.on, S.hunters);
  const pos = (S.hunters - 10) / (S.top - 10) * 100;
  return `<div class="phunters"><div class="prow-t">Охотников в мире <b id="hn">${S.hunters}</b> <small>из ${S.top} в таблице</small></div>
      <input type="range" id="hr" min="10" max="${S.top}" value="${S.hunters}" style="--p:${pos}%">
      <div class="phint">Рекомендуем этому ПК: до ${P.rec}. Применится на ходу, без перезапуска. Лишние выходят и ждут в базе: их кланы и память целы.</div></div>`;
}

function renderPage() {
  const box = $('page');
  if (S.q) { renderSearch(box); bindPage(); return; }
  const p = PAGES.find((x) => x.id === S.page) || PAGES[0];
  const ids = pageIds(p);
  const onN = ids.filter((i) => S.on[i]).length;
  let html = `<div class="ppage-h"><h2>${esc(p.title)}</h2>${ids.length > 1 ? `<span class="c">${onN} из ${ids.length}</span><span class="spacer"></span><button class="sbtn mini" data-all="1">Включить все</button><button class="sbtn mini" data-all="0">Выключить все</button>` : ''}</div>`;
  if (p.about) html += `<p class="pabout">${esc(p.about)}</p>`;
  if (p.stub) html += `<div class="note">${esc(p.stub)}</div>`;
  if (p.kind === 'mode') html += renderModePage();
  if (p.kind === 'people') html += renderPeople(p);
  for (const [t, group] of p.subs || []) html += `${t ? `<h4 class="psub">${esc(t)}</h4>` : ''}<div class="pgroup">${group.map(row).join('')}</div>`;
  if (p.keys) html += `<h4 class="psub">Ключи конфигов</h4><div class="pgroup">${p.keys.map(keyRow).join('')}</div>`;
  if (p.fold) html += `<button class="pfold" data-fold="cal"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M6 9l6 6 6-6"/></svg>${esc(p.fold)}</button><div class="pfold-body" id="cal"><div class="note">Прежняя карточка календаря: окно каждого события полосой на год, расписание PvP-ивентов. Сейчас она занимает пол-экрана, здесь свёрнута.</div></div>`;
  box.innerHTML = html;
  box.scrollTop = 0;
  bindPage();
}

function renderSearch(box) {
  const q = S.q.toLowerCase();
  const hits = Object.values(SW).filter((s) => `${s.title} ${s.note} ${s.key || ''} ${(s.keys || []).join(' ')} ${s.file}`.toLowerCase().includes(q));
  const byPage = PAGES.map((p) => [p, hits.filter((s) => pageIds(p).includes(s.id))]).filter(([, h]) => h.length);
  box.innerHTML = `<div class="ppage-h"><h2>Поиск</h2><span class="c">выключателей: ${hits.length}</span></div>`
    + byPage.map(([p, h]) => `<h4 class="psub">${esc(p.title)}</h4><div class="pgroup">${h.map((s) => row(s.id)).join('')}</div>`).join('')
    + `<h4 class="psub">Ключи конфигов</h4><div class="note">В настоящем окне здесь же идут совпадения из всех 1895 ключей: имя, файл, описание. В макете ключей нет.</div>`;
}

function bindPage() {
  const box = $('page');
  box.querySelectorAll('[data-sw]').forEach((b) => (b.onclick = () => { S.on[b.dataset.sw] = !S.on[b.dataset.sw]; hover = null; renderAll(true); }));
  box.querySelectorAll('.prow[data-id]').forEach((r) => {
    r.onmouseenter = () => { hover = { on: { ...S.on, [r.dataset.id]: !S.on[r.dataset.id] } }; renderLoad(); };
    r.onmouseleave = () => { hover = null; renderLoad(); };
    r.querySelector('.prow-m').onclick = () => r.classList.toggle('open');
  });
  box.querySelectorAll('[data-all]').forEach((b) => (b.onclick = () => {
    const p = PAGES.find((x) => x.id === S.page);
    for (const id of pageIds(p)) if (SW[id]) S.on[id] = b.dataset.all === '1';
    renderAll(true);
  }));
  box.querySelectorAll('.mcard').forEach((b) => {
    const m = MODES.find((x) => x.id === b.dataset.m);
    b.onmouseenter = () => { hover = { on: modeOn(m) }; renderLoad(); };
    b.onmouseleave = () => { hover = null; renderLoad(); };
    b.onclick = () => { S.on = modeOn(m); hover = null; renderAll(); };
  });
  box.querySelectorAll('[data-fold]').forEach((b) => (b.onclick = () => { b.classList.toggle('open'); $(b.dataset.fold).classList.toggle('open'); }));
  box.querySelectorAll('[data-key]').forEach((k) => {
    const touch = () => { S.edits += 1; $('pendN').textContent = S.edits; $('pfoot').hidden = false; };
    if (k.classList.contains('switch')) k.onclick = () => { k.setAttribute('aria-checked', k.getAttribute('aria-checked') !== 'true'); touch(); };
    else k.oninput = touch;
  });
  const hr = $('hr');
  if (hr) {
    hr.oninput = () => { S.hunters = +hr.value; $('hn').textContent = S.hunters; hr.style.setProperty('--p', `${((S.hunters - 10) / (S.top - 10)) * 100}%`); renderLoad(); };
    hr.onchange = () => renderNav();
  }
}

function openDrawer() {
  const P = plan(S.on, S.hunters);
  const rows = [
    ['В одиночку, 50 охотников', '8 ГБ', '2', 'нет', 'куча 4 ГБ: на пределе'],
    ['Живой мир, 219 охотников', '16 ГБ', '4', 'нет', 'куча 6 ГБ, запас есть'],
    ['Всё сразу и клиент', '32 ГБ', '6', 'нет', 'куча 8 ГБ, замер до 1000'],
    ['Мозги на LM Studio', '32 ГБ', '6', '20 ГБ', 'модель 19,7 ГБ, проверка впереди'],
  ];
  $('drawerBody').innerHTML = `<div class="note"><b>Этот ПК:</b> ${P.pc.cores} ядер, ОЗУ ${P.pc.ram} ГБ, видеопамять ${P.pc.vram} ГБ, свободно ${P.pc.free} ГБ. Рекомендуем до <b>${P.rec}</b> охотников.</div>
    <div class="sec"><h3>Требования</h3><table class="rq"><tr><th>Как играешь</th><th>ОЗУ</th><th>Ядер</th><th>Видео</th><th></th></tr>${rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</table>
    <p class="phint">Черновик: память и охотники из замеров, остальное уточнит замер модов. Та же таблица уходит в README и в карточку хроники.</p></div>
    <div class="sec"><h3>Как считаем</h3><p class="phint">Куча гейма — 3/8 ОЗУ, от 4 до 8 ГБ. Скорость ядра меряем коротким циклом при запуске. Ничего не запрещаем: только советуем.</p></div>`;
  $('scrim').classList.add('on');
  $('drawer').classList.add('on');
}

function renderChrons() {
  $('chronList').innerHTML = CHRONS.map(([era, list]) => `<div class="era">${esc(era)}</div>` + list.map(([id, name, cover]) => `<button class="chron ${id === S.chron ? 'sel' : ''}" data-c="${id}" data-cover="${cover}" title="${esc(name)}"><span class="thumb" style="background-image:url(${ASSETS}cover_${cover}.jpg)"></span><span class="t"><span class="n">${esc(name)}</span><span class="m">готова</span></span><i class="sdot ready"></i></button>`).join('')).join('');
  document.querySelectorAll('.chron').forEach((b) => (b.onclick = () => {
    S.chron = b.dataset.c;
    $('title').textContent = b.title;
    setCover(b.dataset.cover);
    if (S.page === 'native' && !NATIVE_CHRONS.has(S.chron)) S.page = 'people';
    renderChrons(); renderAll();
  }));
}
function setCover(c) { $('banner').style.backgroundImage = `url(${ASSETS}cover_${c}.jpg)`; }

function renderAll(keepScroll) {
  const top = $('page').scrollTop;
  renderNav(); renderPage(); renderLoad();
  if (keepScroll) $('page').scrollTop = top;
}

function setTheme(id) {
  const t = THEMES.find((x) => x[0] === id);
  document.documentElement.dataset.theme = id;
  document.documentElement.dataset.chrome = t[2] ? id : '';
  document.querySelectorAll('#mbThemes button').forEach((b) => b.classList.toggle('on', b.dataset.t === id));
}

async function start() {
  const d = await (await fetch('../../../launcher/l2hub/server_modes.json')).json();
  for (const s of d.switches) SW[s.id] = s;
  MODES = d.modes;
  for (const id of ['pvpcraft', 'live']) Object.assign(S.on, d.modes.find((m) => m.id === id).switches);
  const placed = PAGES.flatMap(pageIds);
  const missing = Object.keys(SW).filter((id) => !placed.includes(id));
  const twice = placed.filter((id, i) => placed.indexOf(id) !== i);
  const ghost = placed.filter((id) => !SW[id]);
  if (missing.length || twice.length || ghost.length) console.error('Дерево настроек расходится с server_modes.json', { missing, twice, ghost });
  window.PULT_CHECK = { switches: Object.keys(SW).length, placed: placed.length, missing, twice, ghost };

  const groups = [THEMES.filter((t) => !t[2]), THEMES.filter((t) => t[2])];
  $('mbThemes').innerHTML = groups.map((g, i) => (i ? '<i class="mb-sep">раритет:</i>' : '') + g.map(([id, t]) => `<button data-t="${id}">${esc(t)}</button>`).join('')).join('');
  document.querySelectorAll('#mbThemes button').forEach((b) => (b.onclick = () => { setTheme(b.dataset.t); location.hash = b.dataset.t; }));
  setTheme(THEMES.some((t) => t[0] === location.hash.slice(1)) ? location.hash.slice(1) : 'gold');

  $('mbPc').onchange = (e) => { S.pc = e.target.value; renderAll(); };
  $('mbRun').onchange = (e) => { S.running = e.target.checked; renderAll(); };
  $('q').oninput = (e) => { S.q = e.target.value.trim(); renderNav(); renderPage(); };
  $('discard').onclick = () => { S.edits = 0; $('pfoot').hidden = true; renderAll(); };
  $('save').onclick = () => { S.edits = 0; $('pfoot').hidden = true; };
  $('drawerClose').onclick = $('scrim').onclick = () => { $('scrim').classList.remove('on'); $('drawer').classList.remove('on'); };
  $('dockBars').onclick = () => document.querySelector('.ctab[data-ctab="set"]').click();
  document.querySelectorAll('.ctab').forEach((b) => (b.onclick = () => {
    document.querySelectorAll('.ctab').forEach((x) => x.classList.toggle('on', x === b));
    const set = b.dataset.ctab === 'set';
    $('banner').classList.toggle('strip', b.dataset.ctab !== 'over');
    $('secChron').classList.toggle('in-set', set);
    $('pult').classList.toggle('on', set);
    $('cpaneOther').classList.toggle('on', !set);
    $('otherNote').textContent = b.dataset.ctab === 'over' ? 'Обзор без изменений: большая обложка, состояние сервера и карточки. Сверху обложка раскрывается только здесь.' : 'Эта вкладка не меняется, только получает больше места: обложка свёрнута в полоску.';
  }));
  setCover('ct26_highfive_login');
  buildLoad();
  renderChrons();
  renderAll();
  pollLive();
  setInterval(pollLive, 1000);
}
start();
