/**
 * Приёмник голосов «Хочу эту хронику» и отзывов из лаунчера L2Hub (docs/FEEDBACK.md, клиент — launcher/l2hub/feedback.py).
 * Google Apps Script, привязанный к таблице автора: листы «Голоса» и «Отзывы» создаёт сам, об отзыве пишет письмо
 * владельцу таблицы, отчёт (zip без паролей и ключей) кладёт в папку «L2Hub — отчёты» на его Диске, доступ — только у него.
 * Развернуть: «Начать развёртывание» → «Новое развёртывание» → «Веб-приложение», запуск от своего имени, доступ — все.
 * Telegram — по желанию: свойства скрипта TG_TOKEN и TG_CHAT (вписывает автор сам, в код и в лаунчер они не попадают).
 */

const VOTES = 'Голоса';
const FEEDBACK = 'Отзывы';
const REPORTS = 'L2Hub — отчёты';
const MAX_TEXT = 4000;
const MAX_REPORT = 8 * 1024 * 1024;              // байт zip
const LIMITS = { vote: 60, feedback: 5 };        // запросов в час с одной установки
const KINDS = { bug: 'Баг', idea: 'Идея' };

function doGet(e) {
  const action = (e && e.parameter && e.parameter.action) || 'votes';
  if (action === 'votes') return json_({ ok: true, votes: counts_() });
  if (action === 'ping') return json_({ ok: true });
  return json_({ ok: false, error: 'нет такого действия' });
}

function doPost(e) {
  let req;
  try {
    req = JSON.parse(e.postData.contents);
  } catch (err) {
    return json_({ ok: false, error: 'запрос не JSON' });
  }
  const install = String(req.install || '');
  if (!/^[0-9a-f]{32}$/.test(install)) return json_({ ok: false, error: 'нет номера установки' });
  if (req.action !== 'vote' && req.action !== 'feedback') return json_({ ok: false, error: 'нет такого действия' });
  if (!limit_(req.action, install)) return json_({ ok: false, error: 'слишком часто — попробуй через час' });
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    return json_(req.action === 'vote' ? vote_(req, install) : feedback_(req, install));
  } finally {
    lock.releaseLock();
  }
}

function vote_(req, install) {
  const chronicle = String(req.chronicle || '');
  if (!/^[a-z0-9_]{1,40}$/.test(chronicle)) return { ok: false, error: 'нет такой хроники' };
  const sh = sheet_(VOTES, ['Время', 'Хроника', 'Установка', 'Версия L2Hub']);
  const rows = sh.getDataRange().getValues();
  let found = -1;
  for (let i = 1; i < rows.length; i++) {
    if (rows[i][1] === chronicle && rows[i][2] === install) { found = i + 1; break; }
  }
  const on = req.on !== false;
  if (on && found < 0) sh.appendRow([new Date(), chronicle, install, cell_(req.version, 20)]);
  if (!on && found > 0) sh.deleteRow(found);
  CacheService.getScriptCache().remove('counts');
  return { ok: true, on: on, count: counts_()[chronicle] || 0 };
}

function feedback_(req, install) {
  const kind = KINDS[req.kind] ? req.kind : 'idea';
  const text = String(req.text || '').trim().slice(0, MAX_TEXT);
  if (!text) return { ok: false, error: 'пустой отзыв' };
  const chronicle = cell_(req.chronicle, 40);
  let link = '';
  if (req.report && req.report.data) {
    const bytes = Utilities.base64Decode(String(req.report.data));
    if (bytes.length > MAX_REPORT) return { ok: false, error: 'отчёт больше 8 МБ' };
    const name = String(req.report.name || 'report.zip').replace(/[^\w.-]/g, '_').slice(0, 80);
    link = folder_().createFile(Utilities.newBlob(bytes, 'application/zip', name)).getUrl();
  }
  const sh = sheet_(FEEDBACK, ['Время', 'Вид', 'Текст', 'Контакт', 'Хроника', 'Версия L2Hub', 'Установка', 'Отчёт']);
  sh.appendRow([new Date(), KINDS[kind], cell_(text, MAX_TEXT), cell_(req.contact, 200), chronicle, cell_(req.version, 20), install, link]);
  const subject = `L2Hub: ${KINDS[kind].toLowerCase()}${chronicle ? ' — ' + chronicle : ''}`;
  const body = `${text}\n\nКонтакт: ${req.contact || '—'}\nХроника: ${chronicle || '—'}\nL2Hub ${req.version || '?'}\n`
    + (link ? `Отчёт: ${link}\n` : '') + `Таблица: ${SpreadsheetApp.getActiveSpreadsheet().getUrl()}`;
  MailApp.sendEmail(Session.getEffectiveUser().getEmail(), subject, body);
  telegram_(`${subject}\n\n${body}`);
  return { ok: true };
}

function counts_() {
  const cache = CacheService.getScriptCache();
  const hit = cache.get('counts');
  if (hit) return JSON.parse(hit);
  const out = {};
  const rows = sheet_(VOTES, ['Время', 'Хроника', 'Установка', 'Версия L2Hub']).getDataRange().getValues();
  for (let i = 1; i < rows.length; i++) out[rows[i][1]] = (out[rows[i][1]] || 0) + 1;
  cache.put('counts', JSON.stringify(out), 60);
  return out;
}

function limit_(kind, install) {
  const cache = CacheService.getScriptCache();
  const key = `${kind}:${install}`;
  const n = Number(cache.get(key) || 0);
  if (n >= LIMITS[kind]) return false;
  cache.put(key, String(n + 1), 3600);
  return true;
}

function sheet_(name, head) {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sh = book.getSheetByName(name);
  if (!sh) {
    sh = book.insertSheet(name);
    sh.appendRow(head);
    sh.setFrozenRows(1);
  }
  return sh;
}

function folder_() {
  const it = DriveApp.getFoldersByName(REPORTS);
  return it.hasNext() ? it.next() : DriveApp.createFolder(REPORTS);
}

// Текст в ячейку: обрезка и апостроф перед =, +, -, @ — иначе таблица примет отзыв за формулу.
function cell_(value, max) {
  const s = String(value == null ? '' : value).slice(0, max);
  return /^[=+\-@]/.test(s) ? "'" + s : s;
}

function telegram_(text) {
  const props = PropertiesService.getScriptProperties();
  const token = props.getProperty('TG_TOKEN');
  const chat = props.getProperty('TG_CHAT');
  if (!token || !chat) return;
  try {
    UrlFetchApp.fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
      method: 'post', payload: { chat_id: chat, text: text.slice(0, 4000) }, muteHttpExceptions: true,
    });
  } catch (err) {
    // письмо уже ушло — Telegram не обязателен
  }
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
