'use strict';
// «Мой мир» для L2Solo (C4): их карта мира (WorldObserver) во фрейме и пульт — рейты, LLM, вайп. То же, что в их
// веб-лаунчере (`npm start`) и на карте, но из окна L2Hub. Регистрация — window.worldKinds.l2solo, контракт — в начале
// world.js; ядро — Api.l2solo_* (l2hub/l2solo.py), их код зовёт l2hub/l2solo_tool.js. Разбор — docs/L2SOLO.md.
(function () {
  const P = {};    // хроника → l2solo_panel()
  const ERR = {};  // хроника → текст ошибки панели
  const ICON = {
    map: '<path d="M9 4L3 6.5v13.5l6-2.5 6 2.5 6-2.5V4l-6 2.5z"/><path d="M9 4v13.5M15 6.5V20"/>',
    panel: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
  };
  const RATE_NOTE = { x1: 'как на официале', x10: 'быстрее в 10 раз', x50: 'почти без гринда' };
  const U = {};    // хроника → l2solo_update_check(): что нового у авторов L2Solo
  const CHATTER_SHORT = { off: 'молчат', quiet: 'тихо', normal: 'обычно', l2solo: 'как у L2Solo' };
  const AGG_SHORT = { peace: 'мирный', normal: 'как есть', angry: 'злой' };
  const QUESTS = [1, 2, 3, 5];
  // Заточка — раздел [Enchant] L2Solo (C4EnchantRules.js): шансы по видам свитков, безопасная и потолок.
  const ENCHANT = [
    ['Обычный свиток, %', [['weaponChance', 'оружие'], ['armorChance', 'броня'], ['accessoryChance', 'бижутерия']]],
    ['Благословлённый, %', [['blessedWeaponChance', 'оружие'], ['blessedArmorChance', 'броня'], ['blessedAccessoryChance', 'бижутерия']]],
    ['Кристальный, %', [['crystalWeaponChance', 'оружие'], ['crystalArmorChance', 'броня'], ['crystalAccessoryChance', 'бижутерия']]],
    ['Безопасная заточка', [['safeMax', 'до +'], ['safeMaxFull', 'полный сет, до +']]],
    ['Потолок (0 — без потолка)', [['maxWeapon', 'оружие'], ['maxArmor', 'броня'], ['maxAccessory', 'бижутерия']]],
  ];
  // Команды L2Solo в обычном чате игры (README L2Solo, «In-Game Commands») — коротко и по-русски.
  const COMMANDS = [
    ['Пати с ботами', [['/invite', 'на боте в цели — взять в спутники'], ['.bp', 'найти ботов и позвать в пати; дальние догонят'],
      ['.joinparty [имя]', 'возглавить пати ботов рядом или названного бота'], ['.bf', 'друзья и постоянная пати'],
      ['.leave · .kick имя', 'отпустить всех спутников или одного']]],
    ['Спутникам — словами в чат', [['follow · wait', 'за мной / стой и жди'], ['hunt', 'охотиться'], ['heal · buff', 'полечить / бафнуть'],
      ['.b', 'пульт спутников'], ['.bs [имя]', 'состояние ботов или одного']]],
    ['Сервис', [['.buff', 'на боте в цели — платный баф'], ['.sell', 'продать всё неэкипированное за 50 %'],
      ['.afksell · .afkbuy', 'AFK-лавка продажи / скупки в мирной зоне'], ['.afkstop', 'закрыть AFK-лавку'],
      ['.trade', 'обмен с ботом в цели'], ['.go', 'PvP на арене'], ['.admin', 'меню GM']]],
  ];
  // Население ботов — раздел [BotPopulation] (PopulationConfig.js): потолок и волна новичков на расу; ключ, подпись, максимум.
  const POPULATION = [['maxPlayingPopulation', 'ботов в мире, не больше', 2000], ['starterBotsPerRace', 'новичков в волне, на расу', 200]];
  const NEWBIE = 'General.newbieRewardPolicy';
  const NEWBIE_SHORT = { strict: 'первому', grant: 'и старым', always: 'всем' };
  // SP с мобов и спойл отдельно от пресета (ProgressionRates.js): ключ, подпись. Выше их x50 итог SP — предупреждение: SP у них без потолка.
  const RATES = [['General.spRate', 'SP с мобов'], ['General.spoilRate', 'спойл']];
  const SP_WARN = 50;
  const DRAFT = {};  // хроника → {заточка, население или рейты: набранные, но не сохранённые поля}
  const restartNote = (ctx) => (ctx.running ? 'после перезапуска сервера' : 'со следующего запуска');
  const localNote = (t, key) => (t[key].source === 'local' ? ' · <span class="bad-text">задано в их local.ini — он главнее</span>' : '');

  // Режим сервера C4 (l2solo_modes.json): пресет рейтов, злость мира и заточка разом — как «Режим сервера» у Mobius.
  function modeCard(ctx, p, busy) {
    const active = p.modes.find((m) => m.active);
    const btns = p.modes.map((m) => `<button data-c4mode="${m.id}" class="${m.active ? 'on' : ''}" title="${esc(m.note)}">${esc(m.title)}</button>`).join('');
    return `<div class="card wide"><h3>Режим сервера<span class="src">${active ? esc(active.title) : 'свои настройки'}</span></h3>
      <div class="seg modes" id="l2sModes">${btns}</div>
      <div class="note">${esc(active ? active.note : 'Рейты, злость мира и заточка — свои, правятся ниже')}; ${restartNote(ctx)}.</div>
      ${p.settingsPending ? `<div class="note w-note-warn">Настройки поменялись после запуска сервера — заработают после перезапуска.<div class="row2"><button class="sbtn" data-restart ${busy ? 'disabled' : ''}>Перезапустить сервер</button><span class="w-muted" style="font-size:12.5px">мир сохранится, игрока выкинет на полминуты</span></div></div>` : ''}</div>`;
  }

  // Злость мира (pvpAggression) и квесты xN (questRewardRate, множится на пресет рейтов).
  function worldCard(ctx, p) {
    const t = p.tuning;
    const local = (key) => localNote(t, key);
    const agg =Object.entries(p.aggressionLevels).map(([k, title]) => `<button data-agg="${k}" class="${k === p.aggression ? 'on' : ''}" title="${esc(title)}">${AGG_SHORT[k] || k}</button>`).join('');
    const q = t['General.questRewardRate'].value;
    const quests = QUESTS.map((n) => `<button data-quest="${n}" class="${q === n ? 'on' : ''}">x${n}</button>`).join('');
    return `<div class="card"><h3>Злость мира и квесты</h3>
      <div class="opt"><div class="l">Злость мира<small>${esc(p.aggressionLevels[p.aggression] || `своё: pvpAggression = ${t['BotPopulation.pvpAggression'].value}`)}${local('BotPopulation.pvpAggression')}</small></div><span class="seg" id="l2sAgg">${agg}</span></div>
      <div class="opt"><div class="l">Награды квестов<small>опыт, SP, адена и предметы; x${q} × пресет ${esc(p.progression)}${QUESTS.includes(q) ? '' : ' (своё)'}${local('General.questRewardRate')}</small></div><span class="seg" id="l2sQuest">${quests}</span></div>
      <div class="w-muted" style="font-size:12.5px;margin-top:6px">Мирный — боты первыми не нападают, самооборона остаётся; злой — нападают и мстят чаще. Всё — ${restartNote(ctx)}.</div></div>`;
  }

  function enchantCard(ctx, p) {
    const t = p.tuning;
    const groups = ENCHANT.map(([title, keys]) => `<div class="mode-group">${esc(title)}</div><div class="rates" style="grid-template-columns:repeat(3,minmax(0,1fr))">${keys.map(([k, label]) => {
      const key = `Enchant.${k}`;
      return `<div class="num"><label>${esc(label)}</label><input type="number" min="0" max="${k.endsWith('Chance') ? 100 : 30}" data-ench="${key}" value="${t[key].value ?? ''}"${t[key].source === 'local' ? ' title="Задано в их local.ini — он главнее пульта"' : ''}></div>`;
    }).join('')}</div>`).join('');
    const custom = ENCHANT.some(([, keys]) => keys.some(([k]) => t[`Enchant.${k}`].source === 'hub'));
    return `<div class="card"><h3>Заточка<span class="src">${custom ? 'своя' : 'как у L2Solo'}</span></h3>${groups}
      <div class="row2" style="margin-top:8px"><button class="sbtn primary" id="l2sEnchSave" disabled>Сохранить</button><button class="sbtn" id="l2sEnchReset" ${custom ? '' : 'disabled'}>Как у L2Solo</button>
        <span class="w-muted" style="font-size:12.5px">${restartNote(ctx)}</span></div></div>`;
  }

  // Заряды новичка (General.newbieRewardPolicy) и население ботов: потолок и волна новичков в стартовых деревнях.
  function botsCard(ctx, p) {
    const t = p.tuning;
    const levels = p.choices?.[NEWBIE];
    if (!levels || !t[NEWBIE]) return '';
    const newbie = Object.keys(levels).map((k) => `<button data-newbie="${k}" class="${k === t[NEWBIE].value ? 'on' : ''}" title="${esc(levels[k])}">${NEWBIE_SHORT[k] || k}</button>`).join('');
    const fields = POPULATION.map(([k, label, max]) => {
      const key = `BotPopulation.${k}`;
      return `<div class="num"><label>${esc(label)}</label><input type="number" min="0" max="${max}" data-pop="${key}" value="${t[key].value ?? ''}"${t[key].source === 'local' ? ' title="Задано в их local.ini — он главнее пульта"' : ''}></div>`;
    }).join('');
    const custom = POPULATION.some(([k]) => t[`BotPopulation.${k}`].source === 'hub');
    return `<div class="card"><h3>Новички и население</h3>
      <div class="opt"><div class="l">Заряды новичку<small>6000 зарядов души или 3000 духа за квесты-награды деревень, раз на персонажа; ${esc(levels[t[NEWBIE].value] || t[NEWBIE].value)}${localNote(t, NEWBIE)}</small></div><span class="seg" id="l2sNewbie">${newbie}</span></div>
      <div class="mode-group">Население ботов<span class="w-muted"> · ${custom ? 'своё' : 'как у L2Solo'}</span></div>
      <div class="rates" style="grid-template-columns:repeat(2,minmax(0,1fr))">${fields}</div>
      <div class="row2" style="margin-top:8px"><button class="sbtn primary" id="l2sPopSave" disabled>Сохранить</button><button class="sbtn" id="l2sPopReset" ${custom ? '' : 'disabled'}>Как у L2Solo</button>
        <span class="w-muted" style="font-size:12.5px">${restartNote(ctx)}</span></div>
      <div class="w-muted" style="font-size:12.5px;margin-top:6px">Новых ботов заселяют волнами новичков в стартовых деревнях, пока их меньше потолка; уже заселённые остаются. 0 — новых не заселять.</div></div>`;
  }

  // Рейты SP и спойла: множатся на пресет; без них SP идёт за опытом, спойл — за шансом дропа.
  function ratesCard(ctx, p) {
    const t = p.tuning;
    if (!RATES.every(([k]) => t[k])) return '';
    const total = (k) => +(t[k].value * Number(p.progression.slice(1))).toFixed(2);
    const fields = RATES.map(([k, label]) => `<div class="num"><label>${esc(label)}</label><input type="number" min="0.1" max="10" step="0.1" data-mul="${k}" value="${t[k].value ?? ''}"${t[k].source === 'local' ? ' title="Задано в их local.ini — он главнее пульта"' : ''}></div>`).join('');
    const custom = RATES.some(([k]) => t[k].source === 'hub');
    const sp = total(RATES[0][0]);
    return `<div class="card"><h3>Рейты SP и спойла<span class="src">${custom ? 'свои' : 'как у L2Solo'}</span></h3>
      <div class="rates" style="grid-template-columns:repeat(2,minmax(0,1fr))">${fields}</div>
      <div class="w-muted" style="font-size:12.5px;margin-top:6px">Итог с пресетом ${esc(p.progression)}: SP x${sp}, спойл x${total(RATES[1][0])}. Множатся на пресет, от 0,1 до 10. Квесты — своей ручкой; спойл выше 100 % шанса даёт больше предметов. Накопленное не пересчитывается.</div>
      ${sp > SP_WARN ? `<div class="note w-note-warn">SP x${sp} — выше их x50. У L2Solo SP без потолка, а рейдбосс даёт больше миллиона × рейт: накопленное быстро уйдёт за предел счётчика. Сначала минус в окне игры, потом персонаж не войдёт в мир.</div>` : ''}
      <div class="row2" style="margin-top:8px"><button class="sbtn primary" id="l2sMulSave" disabled>Сохранить</button><button class="sbtn" id="l2sMulReset" ${custom ? '' : 'disabled'}>Как у L2Solo</button>
        <span class="w-muted" style="font-size:12.5px">${restartNote(ctx)}</span></div></div>`;
  }

  function commandsCard() {
    const cols = COMMANDS.map(([title, rows]) => `<div style="min-width:0"><div class="mode-group">${esc(title)}</div>${rows.map(([c, d]) =>
      `<div class="w-t2" style="font-size:12.5px;line-height:1.45;margin:0 0 6px"><span class="w-cmd">${esc(c)}</span> ${esc(d)}</div>`).join('')}</div>`).join('');
    return `<div class="card wide"><h3>Команды L2Solo — пишутся в обычный чат игры</h3><div class="w-grid w-g3">${cols}</div>
      <div class="w-muted" style="font-size:12.5px;margin-top:8px">Боты помнят, как к ним относишься: обиженные в пати не пойдут, друзья соберутся в постоянную. Полный список — README L2Solo.</div></div>`;
  }

  // Расход LLM ботов L2Solo из нашего учёта (panel().llm.usage): запросы и деньги за сегодня, неделю и всё время.
  function usageLine(u) {
    const one = (t, x) => `${t} ${x.requests}${u.priced ? ` · $${x.cost.toFixed(3)}` : ''}`;
    return [one('сегодня', u.today), one('неделя', u.week), one('всего', u.all)].join(' · ');
  }

  // Что русский оверлей написал в лог текущего запуска (l2solo.ru_status).
  function ruNote(p) {
    const ru = p.ru;
    if (!ru || !ru.loaded) return '';
    const parts = [];
    if (ru.phrases) parts.push(`фраз по-русски ${ru.phrases[0]} из ${ru.phrases[1]}`);
    if (ru.llm) parts.push('LLM отвечает по-русски');
    if (ru.input === 'cyrillic') parts.push('кириллица от игрока доходит');
    if (ru.input === 'latin1') parts.push('игрок пишет латиницей');
    if (ru.errors?.length) parts.push(`<span class="bad-text">ошибки: ${esc(ru.errors[0])}</span>`);
    return parts.length ? ` · ${parts.join(' · ')}` : '';
  }

  function updView(chron) {
    const u = U[chron];
    if (!u) return '<div class="w-muted" style="font-size:12.5px;margin-bottom:8px">Проверка ходит в их GitHub (git fetch).</div>';
    if (!u.ok) return `<div class="note w-note-bad">${esc(u.error)}</div>`;
    const head = `<div class="w-kv" style="margin-bottom:8px"><span>Сейчас</span><span>${esc(u.head)}</span>`
      + `<span>Новых у авторов</span><span>${u.detached ? 'код откачен на старый коммит' : u.behind || 'нет — всё свежее'}</span></div>`;
    const warn = u.dirty?.length ? `<div class="note w-note-warn">Правки в их файлах (${esc(u.dirty.slice(0, 3).join(', '))}) — обновление откажет.</div>`
      : u.ahead ? `<div class="note w-note-warn">Свои коммиты поверх их ветки — обновлять руками (docs/L2SOLO.md).</div>` : '';
    const list = u.commits?.length ? `<ul class="w-muted" style="font-size:12.5px;margin:0 0 8px;padding-left:18px">${u.commits.slice(0, 8).map((c) => `<li>${esc(c)}</li>`).join('')}</ul>` : '';
    return head + warn + list;
  }

  async function load(chron) {
    try {
      const r = await api.l2solo_panel(chron);
      if (r.ok) { P[chron] = r; delete ERR[chron]; } else ERR[chron] = r.error;
    } catch (e) { ERR[chron] = e.message || String(e); }
  }

  const mb = (n) => (n >= 2 ** 30 ? `${(n / 2 ** 30).toFixed(1)} ГБ` : `${Math.round(n / 2 ** 20)} МБ`).replace('.', ',');
  const offCard = (title, text) => `<div class="card" style="max-width:640px;margin:40px auto;text-align:center;padding:26px">
      <div style="font-family:var(--serif);font-size:20px;color:var(--gold-hi)">${title}</div>
      <p class="w-t2" style="line-height:1.6;font-size:14px">${text}</p>
      <div class="row2" style="justify-content:center"><button class="sbtn primary" data-act="start">Запустить мир без клиента</button></div></div>`;

  // ---------- Карта мира ----------
  function mapView(main, ctx, p) {
    if (!ctx.running || !p.mapUrl || !p.mapUp) {
      main.innerHTML = offCard('Карта мира — пока работает сервер',
        `Карта L2Solo (WorldObserver) показывает мир вживую: боты и игроки, пати, кланы, рейтинги, рейд-боссы, рынок и база предметов. ${ctx.running ? 'Сервер ещё грузит мир — карта откроется сама.' : 'Запусти сервер — карта появится здесь.'}`);
      return;
    }
    main.classList.add('flush');
    // Фрейм не пересоздаём при обновлениях раздела: иначе карта перезагружалась бы каждые 10 секунд.
    if (main.querySelector('iframe.l2s-map')?.dataset.src === p.mapUrl) return;
    main.innerHTML = `<div style="display:flex;flex-direction:column;flex:1;min-width:0">
      <div class="row2" style="padding:8px 14px;margin:0;border-bottom:1px solid var(--line)"><span class="w-muted" style="font-size:12.5px">Карта L2Solo · ${esc(p.mapUrl)} · по-английски, это их интерфейс</span><span class="spacer" style="flex:1"></span>
        <button class="sbtn" id="l2sMapReload">Обновить</button><button class="sbtn" id="l2sMapOpen">Открыть в браузере</button></div>
      <iframe class="l2s-map" data-src="${esc(p.mapUrl)}" src="${esc(p.mapUrl)}" style="flex:1;width:100%;border:0;background:#0b0d12" title="Карта мира L2Solo"></iframe></div>`;
    $('l2sMapReload').onclick = () => { main.querySelector('iframe.l2s-map').src = p.mapUrl; };
    $('l2sMapOpen').onclick = () => call(api.l2solo_open_map(ctx.chron));
  }

  // ---------- Пульт ----------
  function panelView(main, ctx, p) {
    const busy = last.job && !last.job.done;
    const rates = p.presets.map((r) => `<button data-rate="${r}" class="${r === p.progression ? 'on' : ''}" title="${RATE_NOTE[r] || ''}">${r}</button>`).join('');
    const w = p.world;
    const llm = p.llm;
    const scopes = Object.entries(p.wipeScopes).map(([k, t]) => `<button class="sbtn" data-wipe="${k}" ${p.wipeBlocker || busy ? 'disabled' : ''}>${esc(t)}</button>`).join('');
    main.innerHTML = `<div class="w-title"><h3 class="w-h">Пульт L2Solo</h3><span class="w-pill">${ctx.running ? `работает · рейты ${esc(p.livePreset || '—')}` : 'сервер выключен'}</span></div>
      <div class="w-grid w-g2">
        <div class="card wide"><h3>Код L2Solo</h3>
          <div class="w-t2" style="font-size:13px;line-height:1.5;margin-bottom:8px">Обновление от авторов L2Solo: сохранение мира, <span class="w-cmd">git pull</span>, <span class="w-cmd">npm install</span> — только у выключенного сервера. Базу их код мигрирует сам при следующем запуске.</div>
          <div id="l2sUpd">${updView(ctx.chron)}</div>
          <div class="row2"><button class="sbtn" id="l2sUpdCheck" ${busy ? 'disabled' : ''}>Проверить обновление</button>
            <button class="sbtn primary" id="l2sUpdGo" ${busy || ctx.running || !(U[ctx.chron]?.behind) ? 'disabled' : ''} title="${ctx.running ? 'Сначала останови сервер' : ''}">Обновить</button></div>
        </div>
        ${p.modes ? modeCard(ctx, p, busy) : ''}
        <div class="card"><h3>Рейты</h3>
          <div class="opt"><div class="l">Пресет опыта, SP, адены и дропа<small>x1 — ${RATE_NOTE.x1}, x10 и x50 — быстрее; меняется в любой момент</small></div><span class="seg" id="l2sRates">${rates}</span></div>
          ${p.restartForRates ? `<div class="note w-note-warn">Сервер работает на ${esc(p.livePreset)}, выбрано ${esc(p.progression)} — заработает после перезапуска.<div class="row2"><button class="sbtn" id="l2sRestart" ${busy ? 'disabled' : ''}>Перезапустить сервер</button><span class="w-muted" style="font-size:12.5px">мир сохранится, игрока выкинет на полминуты</span></div></div>`
            : `<div class="w-muted" style="font-size:12.5px">${ctx.running ? 'Новые рейты — после перезапуска сервера.' : 'Применится при следующем запуске.'}</div>`}
        </div>
        <div class="card"><h3>Боты и LLM</h3>
          ${llm.configured ? `<div class="opt"><div class="l">Разговоры ботов через LLM<small>${esc(llm.model || '')} · ключ ${llm.key ? '<span class="ok-text">есть</span>' : '<span class="bad-text">нет</span>'}; выкл — боты говорят заготовками</small></div>
            <button class="switch" role="switch" id="l2sLlm" aria-checked="${llm.enabled}"></button></div>
          <div class="row2"><button class="sbtn" id="l2sLlmTest" ${llm.enabled ? '' : 'disabled'} title="Один настоящий запрос их кодом">Проверить связь</button><span class="w-muted" style="font-size:12.5px">${ctx.running ? 'вкл/выкл — после перезапуска' : 'применится при запуске'}</span></div>`
            : '<div class="note">В профиле нет раздела <span class="w-cmd">llm</span> — боты говорят заготовками.</div>'}
          ${p.botLanguages ? `<div class="opt" style="margin-top:10px"><div class="l">Язык ботов<small>${esc(p.botLanguages[p.botLanguage] || p.botLanguage)}${ruNote(p)}</small></div>
            <span class="seg" id="l2sLang">${Object.keys(p.botLanguages).map((k) => `<button data-lang="${k}" class="${k === p.botLanguage ? 'on' : ''}" title="${esc(p.botLanguages[k])}">${k}</button>`).join('')}</span></div>
          ${p.restartForLanguage ? '<div class="note w-note-warn">Язык поменяется после перезапуска сервера.</div>' : ''}` : ''}
          ${p.chatterLevels ? `<div class="opt" style="margin-top:10px"><div class="l">Болтовня ботов<small>${esc(p.chatterLevels[p.chatter] || p.chatter)}; ${ctx.running ? 'после перезапуска сервера' : 'со следующего запуска'}</small></div>
            <span class="seg" id="l2sChatter">${Object.keys(p.chatterLevels).map((k) => `<button data-chat="${k}" class="${k === p.chatter ? 'on' : ''}" title="${esc(p.chatterLevels[k])}">${CHATTER_SHORT[k] || k}</button>`).join('')}</span></div>` : ''}
          ${llm.usage ? `<div class="w-kv" style="margin-top:10px;font-size:12.5px"><span>Расход LLM ботов</span><span>${usageLine(llm.usage)}</span></div>` : ''}
          <div class="w-muted" style="font-size:12.5px;margin-top:8px">Частоты по видам болтовни — раздел <span class="w-cmd">config</span> профиля, таблица — docs/L2SOLO.md.</div>
        </div>
        ${p.tuning ? worldCard(ctx, p) + enchantCard(ctx, p) + botsCard(ctx, p) + ratesCard(ctx, p) : ''}
        <div class="card"><h3>Мир</h3><div class="w-kv">
          ${w.exists ? `<span>Игроков</span><span>${w.players ?? '—'}</span><span>Ботов</span><span>${w.bots ?? '—'}</span><span>База мира</span><span>${mb(w.size)}</span>` : '<span>Мир</span><span>ещё не создан</span>'}
        </div><div class="w-muted" style="font-size:12.5px;margin-top:8px">Сохранения мира — в «Сервисе», лаунчер снимает их сам перед каждым запуском.</div></div>
        <div class="card"><h3>Вайп</h3>
          <div class="w-t2" style="font-size:13px;line-height:1.5;margin-bottom:8px">Стереть персонажей и аккаунты — их же кодом, как в их лаунчере. Перед вайпом лаунчер снимет сохранение мира: откатить можно в «Сервисе».</div>
          <div class="row2">${scopes}</div>
          ${p.wipeBlocker ? `<div class="w-muted" style="font-size:12.5px;margin-top:6px">${esc(p.wipeBlocker)}</div>` : ''}
        </div>
        ${commandsCard()}
      </div>`;
    // Пульт C4 перерисовывается раз в 10 секунд — набранные, но не сохранённые поля (заточка, население, рейты) не терять.
    const numFields = (attr, saveId) => {
      const inputs = [...main.querySelectorAll(`[data-${attr}]`)];
      const key = (inp) => inp.dataset[attr];
      const drafts = (DRAFT[ctx.chron] ||= {});
      const draft = drafts[attr] || {};
      inputs.forEach((inp) => { if (key(inp) in draft) { inp.value = draft[key(inp)]; inp.classList.add('changed'); } });
      const changes = () => Object.fromEntries(inputs.filter((inp) => inp.value !== String(p.tuning[key(inp)].value ?? ''))
        .map((inp) => [key(inp), inp.value === '' ? null : Number(inp.value)]));
      const sync = () => {
        const ch = changes();
        drafts[attr] = Object.fromEntries(Object.entries(ch).map(([k, v]) => [k, v ?? '']));
        inputs.forEach((inp) => inp.classList.toggle('changed', key(inp) in ch));
        if ($(saveId)) $(saveId).disabled = !Object.keys(ch).length;
      };
      inputs.forEach((inp) => (inp.oninput = sync));
      if (inputs.length) sync();
      return changes;
    };
    const enchChanges = numFields('ench', 'l2sEnchSave');
    const popChanges = numFields('pop', 'l2sPopSave');
    const mulChanges = numFields('mul', 'l2sMulSave');
    const worldSet = async (changes, what) => {
      const r = await api.l2solo_set_world(ctx.chron, changes);
      if (!r.ok) return toast(r.error, true);
      delete DRAFT[ctx.chron];
      toast(`${what} — ${r.restart ? 'после перезапуска сервера' : 'со следующего запуска'}${r.local.length ? `; в их local.ini задано своё (${r.local.join(', ')}) — оно главнее` : ''}.`);
      await load(ctx.chron); ctx.rerender();
    };
    if ($('l2sEnchSave')) $('l2sEnchSave').onclick = () => worldSet(enchChanges(), 'Заточка сохранена');
    if ($('l2sEnchReset')) $('l2sEnchReset').onclick = () => worldSet(Object.fromEntries(ENCHANT.flatMap(([, keys]) => keys.map(([k]) => [`Enchant.${k}`, null]))), 'Заточка как у L2Solo');
    if ($('l2sPopSave')) $('l2sPopSave').onclick = () => worldSet(popChanges(), 'Население ботов сохранено');
    if ($('l2sPopReset')) $('l2sPopReset').onclick = () => worldSet(Object.fromEntries(POPULATION.map(([k]) => [`BotPopulation.${k}`, null])), 'Население ботов как у L2Solo');
    if ($('l2sMulSave')) $('l2sMulSave').onclick = () => worldSet(mulChanges(), 'Рейты SP и спойла сохранены');
    if ($('l2sMulReset')) $('l2sMulReset').onclick = () => worldSet(Object.fromEntries(RATES.map(([k]) => [k, null])), 'Рейты SP и спойла как у L2Solo');
    main.querySelectorAll('#l2sNewbie button').forEach((b) => (b.onclick = () => {
      if (b.dataset.newbie === p.tuning[NEWBIE].value) return;
      worldSet({ [NEWBIE]: b.dataset.newbie === 'strict' ? null : b.dataset.newbie }, `Заряды новичку: ${NEWBIE_SHORT[b.dataset.newbie]}`);
    }));
    main.querySelectorAll('#l2sQuest button').forEach((b) => (b.onclick = () => {
      const n = Number(b.dataset.quest);
      if (n === p.tuning['General.questRewardRate'].value) return;
      worldSet({ 'General.questRewardRate': n === 1 ? null : n }, `Награды квестов x${n}`);
    }));
    main.querySelectorAll('#l2sAgg button').forEach((b) => (b.onclick = async () => {
      if (b.dataset.agg === p.aggression) return;
      const r = await api.l2solo_set_aggression(ctx.chron, b.dataset.agg);
      if (!r.ok) return toast(r.error, true);
      toast(`Злость мира: ${AGG_SHORT[r.aggression]} — ${r.restart ? 'после перезапуска сервера' : 'со следующего запуска'}${r.local.length ? '; в их local.ini задано своё — оно главнее' : ''}.`);
      await load(ctx.chron); ctx.rerender();
    }));
    main.querySelectorAll('[data-c4mode]').forEach((b) => (b.onclick = async () => {
      const m = p.modes.find((x) => x.id === b.dataset.c4mode);
      if (!m || m.active) return;
      if (!(await askModal({ title: `Режим «${esc(m.title)}»`, ok: 'Включить',
        body: `<p>${esc(m.note)}.</p><p class="note">Пресет рейтов, рейты SP и спойла, злость мира, квесты и заточка перепишутся разом — ${restartNote(ctx)}. Свои значения потом — полями ниже.</p>` }))) return;
      const r = await api.l2solo_set_mode(ctx.chron, m.id);
      if (!r.ok) return toast(r.error, true);
      delete DRAFT[ctx.chron];
      toast(`Режим «${r.title}» — ${r.restart ? 'после перезапуска сервера' : 'со следующего запуска'}${r.local.length ? `; в их local.ini задано своё (${r.local.length}) — оно главнее` : ''}.`);
      await load(ctx.chron); ctx.rerender();
    }));
    main.querySelectorAll('[data-restart]').forEach((b) => (b.onclick = () => call(api.restart_game(ctx.chron))));
    main.querySelectorAll('#l2sChatter button').forEach((b) => (b.onclick = async () => {
      if (b.dataset.chat === p.chatter) return;
      const r = await api.l2solo_set_chatter(ctx.chron, b.dataset.chat);
      if (!r.ok) return toast(r.error, true);
      toast(`Болтовня ботов: ${p.chatterLevels[r.chatter]}${r.restart ? ' — после перезапуска сервера' : ' — со следующего запуска'}.`);
      await load(ctx.chron); ctx.rerender();
    }));
    main.querySelectorAll('#l2sLang button').forEach((b) => (b.onclick = async () => {
      if (b.dataset.lang === p.botLanguage) return;
      const r = await api.l2solo_set_language(ctx.chron, b.dataset.lang);
      if (!r.ok) return toast(r.error, true);
      toast(`Язык ботов — ${p.botLanguages[r.botLanguage]}${r.restart ? ', после перезапуска сервера' : ', со следующего запуска'}.`);
      await load(ctx.chron); ctx.rerender();
    }));
    $('l2sUpdCheck').onclick = async (e) => {
      e.currentTarget.disabled = true;
      e.currentTarget.textContent = 'Проверяю…';
      U[ctx.chron] = await api.l2solo_update_check(ctx.chron);
      ctx.rerender();
    };
    $('l2sUpdGo').onclick = async () => {
      const u = U[ctx.chron];
      const ok = await askModal({ title: 'Обновить L2Solo?', ok: 'Обновить',
        body: `<p>Новых коммитов у авторов: <b>${u.behind}</b>. Перед обновлением лаунчер снимет сохранение мира «перед обновлением» — откатить можно в «Сервисе».</p>` });
      if (!ok) return;
      delete U[ctx.chron];
      await call(api.l2solo_update(ctx.chron));
    };
    main.querySelectorAll('#l2sRates button').forEach((b) => (b.onclick = async () => {
      if (b.dataset.rate === p.progression) return;
      const r = await api.l2solo_set_progression(ctx.chron, b.dataset.rate);
      if (!r.ok) return toast(r.error, true);
      toast(`Рейты ${r.progression}${r.restart ? ' — после перезапуска сервера' : ' — со следующего запуска'}.`);
      await load(ctx.chron); ctx.rerender();
    }));
    if ($('l2sRestart')) $('l2sRestart').onclick = () => call(api.restart_game(ctx.chron));
    if ($('l2sLlm')) $('l2sLlm').onclick = async () => {
      const r = await api.l2solo_set_llm(ctx.chron, !llm.enabled);
      if (!r.ok) return toast(r.error, true);
      toast(`LLM ботов ${r.enabled ? 'включён' : 'выключен'}${r.restart ? ' — после перезапуска сервера' : ''}.`);
      await load(ctx.chron); ctx.rerender();
    };
    if ($('l2sLlmTest')) $('l2sLlmTest').onclick = async (e) => {
      e.currentTarget.disabled = true;
      const r = await api.l2solo_llm_test(ctx.chron);
      if (!r.ok) toast(`Не достучался: ${r.error}`, true);
      else if (!r.enabled) toast('LLM в настройках сервера выключен.', true);
      else if (r.reason !== 'success') toast(`Модель не ответила: ${r.reason} за ${r.ms} мс (лимит у L2Solo — ${r.timeoutMs} мс).`, true);
      else toast(`${r.model} ответил за ${r.ms} мс: ${r.reply}`);
      ctx.rerender();
    };
    main.querySelectorAll('[data-wipe]').forEach((b) => (b.onclick = async () => {
      const scope = b.dataset.wipe;
      const pre = await api.l2solo_wipe_preview(ctx.chron, scope);
      if (!pre.ok) return toast(pre.error, true);
      const ok = await askModal({ title: `Вайп: ${esc(p.wipeScopes[scope])}?`, ok: 'Вайпнуть',
        body: `<p>В ${esc(ctx.name)} сотрутся персонажей <b>${pre.characters}</b> и аккаунтов <b>${pre.accounts}</b>.</p>
          <p class="w-muted" style="font-size:13px">Перед вайпом лаунчер снимет сохранение мира «перед вайпом» — откатить можно в «Сервисе», только у выключенного сервера.</p>` });
      if (!ok) return;
      await call(api.l2solo_wipe(ctx.chron, scope, scope));
    }));
  }

  window.worldKinds = window.worldKinds || {};
  window.worldKinds.l2solo = {
    note: 'L2Solo · боты',
    screens: [['map', 'Карта мира', ICON.map], ['panel', 'Пульт', ICON.panel]],
    enter: (screen, ctx) => load(ctx.chron),
    render(screen, main, ctx) {
      const p = P[ctx.chron];
      if (ERR[ctx.chron]) { main.innerHTML = `<div class="note w-note-bad" style="max-width:640px">Пульт L2Solo не ответил: ${esc(ERR[ctx.chron])}</div>`; return; }
      if (!p) { main.innerHTML = '<div class="w-muted" style="font-size:13px">Загружаю…</div>'; return; }
      (screen === 'panel' ? panelView : mapView)(main, ctx, p);
    },
    async tick(ctx) {
      const before = JSON.stringify(P[ctx.chron]);
      await load(ctx.chron);
      if (JSON.stringify(P[ctx.chron]) !== before) ctx.rerender();
    },
  };
})();
