'use strict';
// Время в начале строк лога L2Solo для L2Hub. Своего лога у L2Solo нет: лаунчер пишет вывод сервера в
// tmp/logs/l2hub-<id>.log (l2hub/l2solo.py), а их строки — без времени. Прелоад (NODE_OPTIONS=--require) ставит
// «ЧЧ:ММ:СС » в начало каждой строки stdout и stderr. Только в главном потоке: вывод воркеров идёт через него же.
// Их код не меняется; ошибка здесь — вывод как был.

const { isMainThread } = require('worker_threads');

const pad = (n) => String(n).padStart(2, '0');
const stamp = () => {
    const d = new Date();
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())} `;
};

function timestamped(stream) {
    const write = stream.write;
    let lineStart = true;
    stream.write = function (chunk, encoding, callback) {
        try {
            const text = typeof chunk === 'string' ? chunk
                : Buffer.isBuffer(chunk) ? chunk.toString(typeof encoding === 'string' ? encoding : 'utf8') : null;
            if (text) {
                const now = stamp();
                const out = (lineStart ? now : '') + text.replace(/\n(?=[^\n])/g, '\n' + now);
                lineStart = text.endsWith('\n');
                return typeof encoding === 'function' ? write.call(this, out, encoding) : write.call(this, out, 'utf8', callback);
            }
        } catch {
            // вывод как был
        }
        return write.call(this, chunk, encoding, callback);
    };
}

if (isMainThread) {
    timestamped(process.stdout);
    timestamped(process.stderr);
}
