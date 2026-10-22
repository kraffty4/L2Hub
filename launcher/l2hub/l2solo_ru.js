'use strict';
// Русский оверлей L2Solo для L2Hub. Лаунчер подгружает его в процессы сервера (NODE_OPTIONS=--require, l2hub/l2solo.py),
// когда в профиле "botLanguage": "ru". Их файлы не меняем — подменяем в памяти:
//   1. фразы каталога src/GameServer/Bot/AI/BotSpeechTemplates.js (catalog, voices) — по английскому тексту фразы;
//   2. строки вне каталога — в пакете чата Network/Response/Speak.js (регулярка по всему тексту, data.say);
//   3. запросы к LLM (global.fetch их шлюза) — замены в промптах и указание писать игроку по-русски.
// Перевод — l2solo_ru.json рядом. Фразы, которых нет в переводе (новые после обновления L2Solo), остаются английскими;
// покрытие — строкой «L2Hub RU ::» в логе сервера, её показывает «Проверить всё». Ошибка оверлея — строка в лог,
// сервер работает дальше по-английски. Прелоад попадает и в воркеры (холодные боты), и в обёртку run-server.js.

const fs = require('fs');
const path = require('path');
const Module = require('module');
const { isMainThread } = require('worker_threads');

const TAG = 'L2Hub RU ::';
const say = (...args) => console.info(TAG, ...args);
const warned = new Set();
const warnOnce = (what, error) => {
    if (warned.has(what)) return;
    warned.add(what);
    say(`ошибка оверлея (${what}), дальше по-английски: ${error?.message || error}`);
};

let data;
try {
    data = JSON.parse(fs.readFileSync(path.join(__dirname, 'l2solo_ru.json'), 'utf8'));
} catch (error) {
    if (isMainThread) say(`перевод не прочитан, боты говорят по-английски: ${error.message}`);
    return;
}

// ---------- 1. Каталог фраз ----------

const PLACEHOLDER = /\{(\w+)\}/g;
const placeholders = (s) => (String(s).match(PLACEHOLDER) || []).sort().join(',');
const speech = new Map(Object.entries(data.speech || {}).filter(([en, ru]) => placeholders(en) === placeholders(ru)));

function translateCatalog(templates) {
    let total = 0;
    let done = 0;
    const tr = (s) => {
        total += 1;
        const ru = speech.get(s);
        if (ru === undefined) return s;
        done += 1;
        return ru;
    };
    // voices['local.death'] = voices['global.death'] — один массив под двумя ключами: правим каждый массив один раз.
    const seen = new Set();
    for (const variants of Object.values(templates.catalog || {})) {
        if (!Array.isArray(variants) || seen.has(variants)) continue;
        seen.add(variants);
        variants.forEach((v, i) => {
            if (typeof v === 'string') variants[i] = tr(v);
            else if (v && typeof v === 'object') {  // { when, yes, no }
                if (typeof v.yes === 'string') v.yes = tr(v.yes);
                if (typeof v.no === 'string') v.no = tr(v.no);
            }
        });
    }
    for (const variants of Object.values(templates.voices || {})) {
        if (!Array.isArray(variants) || seen.has(variants)) continue;
        seen.add(variants);
        for (const v of variants) if (Array.isArray(v) && typeof v[1] === 'string') v[1] = tr(v[1]);  // [голос, фраза]
    }
    if (isMainThread) say(`фразы ботов по-русски: ${done} из ${total}`);
    return templates;
}

// ---------- 2. Строки вне каталога: пакет чата ----------

const CYRILLIC = /[Ѐ-ӿ]/;
const fragments = data.fragments || {};
const rules = (data.say || []).flatMap(([re, ru]) => {
    try { return [[new RegExp(re), ru]]; } catch { return []; }
});

function translateLine(text) {
    if (!text || CYRILLIC.test(text)) return text;
    for (const [re, ru] of rules) {
        const m = re.exec(text);
        if (m) return ru.replace(/\$(\d)/g, (_, n) => fragments[m[+n] ?? ''] ?? m[+n] ?? '');
    }
    return text;
}

// Чат игрока не переводим, а смотрим, дошла ли кириллица: клиент C4 рисует её в чате мода, но набирает ли — проверка автора.
let inbound = null;
const noticed = new Set();
function noteInput(text) {
    if (CYRILLIC.test(text) && !noticed.has('cyr')) {
        noticed.add('cyr');
        say(`кириллица от игрока дошла до сервера: «${text.slice(0, 24)}»`);
    } else if (/[À-ÿ]{3}/.test(text) && !noticed.has('latin1')) {
        noticed.add('latin1');
        say(`игрок пишет латиницей с диакритикой — похоже на кириллицу в cp1251, понятую как Latin-1: «${text.slice(0, 24)}»`);
    }
}

function wrapResponseSpeak(speak) {
    return function (actor, packetData, ...rest) {
        try {
            if (packetData && typeof packetData.text === 'string') {
                if (inbound && actor && actor === inbound.actor) noteInput(packetData.text);
                else {
                    const text = translateLine(packetData.text);
                    if (text !== packetData.text) packetData = { ...packetData, text };
                }
            }
        } catch (error) {
            warnOnce('чат', error);
        }
        return speak.call(this, actor, packetData, ...rest);
    };
}

function wrapRequestSpeak(speak) {
    return function (session, ...rest) {
        const previous = inbound;
        inbound = session;
        try {
            return speak.call(this, session, ...rest);
        } finally {
            inbound = previous;
        }
    };
}

// ---------- Подмена модулей при загрузке ----------

const TARGETS = [
    [/[\\/]Bot[\\/]AI[\\/]BotSpeechTemplates\.js$/, translateCatalog],
    [/[\\/]Network[\\/]Response[\\/]Speak\.js$/, wrapResponseSpeak],
    [/[\\/]Network[\\/]Request[\\/]Speak\.js$/, wrapRequestSpeak],
];
const patched = new Set();
const load = Module._load;
Module._load = function (request, parent, isMain) {
    const exports = load.apply(this, arguments);
    if (typeof request !== 'string' || !/(BotSpeechTemplates|Speak)(\.js)?$/.test(request)) return exports;
    let file;
    try { file = Module._resolveFilename(request, parent, isMain); } catch { return exports; }
    if (patched.has(file)) return exports;  // в кэше уже наша обёртка
    for (const [re, patch] of TARGETS) {
        if (!re.test(file)) continue;
        patched.add(file);
        try {
            const out = patch(exports);
            if (out !== exports && require.cache[file]) require.cache[file].exports = out;
            return out;
        } catch (error) {
            warnOnce(path.basename(file), error);
            return exports;
        }
    }
    return exports;
};

// ---------- 3. LLM: их шлюз берёт global.fetch при каждом запросе ----------

const llm = data.llm || {};
let llmNoted = false;

function patchBody(text) {
    const body = JSON.parse(text);
    if (!Array.isArray(body.messages)) return text;
    const swap = (s) => (llm.replace || []).reduce((acc, [en, ru]) => (acc.includes(en) ? acc.split(en).join(ru) : acc), s);
    const walk = (v) => (typeof v === 'string' ? swap(v)
        : Array.isArray(v) ? v.map(walk)
            : v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, walk(x)])) : v);
    const out = walk(body);  // промпты и описания полей схемы ответа
    const system = out.messages.find((m) => m && m.role === 'system');
    if (system && typeof system.content === 'string') system.content += '\n\n' + llm.directive;
    else if (system && Array.isArray(system.content)) system.content.push({ type: 'text', text: llm.directive });
    else out.messages.unshift({ role: 'system', content: llm.directive });
    if (!llmNoted) {
        llmNoted = true;
        say('LLM: ответы игроку — по-русски (указание языка добавлено в запрос)');
    }
    return JSON.stringify(out);
}

if (typeof global.fetch === 'function' && llm.directive) {
    const fetch = global.fetch;
    global.fetch = function (url, init, ...rest) {
        try {
            if (init && typeof init.body === 'string' && init.body.includes('"messages"')) init = { ...init, body: patchBody(init.body) };
        } catch (error) {
            warnOnce('LLM', error);
        }
        return fetch.call(this, url, init, ...rest);
    };
}
