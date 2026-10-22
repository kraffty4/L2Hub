'use strict';
// Расход LLM ботов L2Solo для L2Hub. Их шлюз (OpenRouterGateway) берёт global.fetch при каждом запросе к модели:
// прелоад (NODE_OPTIONS=--require, l2hub/l2solo.py) читает usage из ответа и раз в 30 с и при выходе дописывает его в
// файл L2HUB_LLM_USAGE_FILE (data/l2solo/<id>.usage.json) в формате data/brain/usage.json:
//   {"days": {"ГГГГ-ММ-ДД": {"l2solo:<модель>": {"requests": …, "in": …, "out": …}}}}
// Только главный поток: запросы к модели идут из него. Их код не меняется; ошибка здесь — запрос идёт как был.

const fs = require('fs');
const { isMainThread } = require('worker_threads');

const file = process.env.L2HUB_LLM_USAGE_FILE;

if (isMainThread && file && typeof global.fetch === 'function') {
    const pending = new Map();  // «день|модель» → {requests, in, out}
    const pad = (n) => String(n).padStart(2, '0');
    const today = () => {
        const d = new Date();
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    };

    const add = (model, usage) => {
        const key = `${today()}|${model || '?'}`;
        const row = pending.get(key) || { requests: 0, in: 0, out: 0 };
        row.requests += 1;
        row.in += Number(usage.prompt_tokens) || 0;
        row.out += Number(usage.completion_tokens) || 0;
        pending.set(key, row);
    };

    const flush = () => {
        if (!pending.size) return;
        try {
            let data = { days: {} };
            try {
                data = JSON.parse(fs.readFileSync(file, 'utf8'));
            } catch {
                // файла ещё нет или он битый — начинаем заново
            }
            data.days = data.days || {};
            for (const [key, add] of pending) {
                const [day, model] = key.split('|');
                const row = ((data.days[day] = data.days[day] || {})[`l2solo:${model}`] ||= { requests: 0, in: 0, out: 0 });
                row.requests += add.requests;
                row.in += add.in;
                row.out += add.out;
            }
            const tmp = `${file}.${process.pid}.tmp`;
            fs.writeFileSync(tmp, JSON.stringify(data, null, 1), 'utf8');
            fs.renameSync(tmp, file);
            pending.clear();
        } catch {
            // учёт не должен мешать серверу: попробуем при следующем сбросе
        }
    };

    const fetch = global.fetch;
    global.fetch = async function (url, init, ...rest) {
        const response = await fetch.call(this, url, init, ...rest);
        try {
            if (init && typeof init.body === 'string' && init.body.includes('"messages"') && response.ok) {
                const model = JSON.parse(init.body).model;
                response.clone().json().then((data) => {
                    if (data && data.usage) add(data.model || model, data.usage);
                }).catch(() => {});
            }
        } catch {
            // ответ как есть
        }
        return response;
    };

    setInterval(flush, 30000).unref();
    process.on('exit', flush);
}
