'use strict';
// Действия с L2Solo их же кодом — для лаунчера L2Hub (l2hub/l2solo.py): сохранения мира (scripts/saved-games.js),
// вайп (scripts/world-wipe.js) и пробный запрос к LLM их шлюзом (OpenRouterGateway). Сохранения и вайп — только у
// выключенного сервера: блокировку базы saved-games.js берёт сам, для вайпа её берём, как их веб-лаунчер.
//
//   node l2solo_tool.js <папка сервера> saves-list
//   node l2solo_tool.js <папка сервера> saves-create <база мира> <база истории> <имя>
//   node l2solo_tool.js <папка сервера> saves-load <база мира> <база истории> <id>
//   node l2solo_tool.js <папка сервера> wipe-preview <база мира> <bots|players|all>
//   node l2solo_tool.js <папка сервера> wipe <база мира> <bots|players|all>
//   node l2solo_tool.js <папка сервера> llm-test
//
// Конфиг — как у сервера: слой L2Hub в L2NODE_SHARED_CONFIG_FILE, ключ LLM — в L2NODE_AI_API_KEY.
// Ответ — одна строка JSON в stdout, последней: {"ok": true, "value": …} или {"ok": false, "error": "…"}.

const path = require('path');

const [serverDir, operation, ...args] = process.argv.slice(2);
process.chdir(serverDir); // их модули считают пути от корня сервера
const scripts = (name) => require(path.join(serverDir, 'scripts', name));
const savesDir = path.join(serverDir, 'tmp', 'saves');

async function llmTest() {
    require(path.join(serverDir, 'src', 'Global'));
    const gateway = invoke('GameServer/Bot/AI/OpenRouterGateway');
    const cfg = gateway.config();
    if (!cfg.enabled) return { enabled: false };
    const startedAt = Date.now();
    const result = await gateway.request({
        messages: [
            { role: 'system', content: 'You are a Lineage 2 player bot. Reply with JSON only: {"say": string}.' },
            { role: 'user', content: 'A player says: hi, are you there?' }
        ]
    });
    return { enabled: true, ok: result.ok, reason: result.reason, ms: Date.now() - startedAt, model: cfg.model,
        timeoutMs: cfg.timeoutMs, usage: result.usage, reply: result.data ? JSON.stringify(result.data).slice(0, 200) : null };
}

function wipe(databasePath, scope, preview) {
    const WorldWipe = scripts('world-wipe.js');
    if (preview) return WorldWipe.preview(scope);
    const release = scripts('database-access.js').acquireDatabaseAccess(databasePath);
    return WorldWipe.wipe(scope).finally(release);
}

(async () => {
    const SavedGames = () => scripts('saved-games.js');
    switch (operation) {
    case 'saves-list': return SavedGames().list(savesDir);
    case 'saves-create': return SavedGames().run({ operation: 'create', databasePath: args[0], historyPath: args[1], savesDir, name: args[2] });
    case 'saves-load': return SavedGames().run({ operation: 'load', databasePath: args[0], historyPath: args[1], savesDir, id: args[2] });
    case 'wipe-preview': return wipe(args[0], args[1], true);
    case 'wipe': return wipe(args[0], args[1], false);
    case 'llm-test': return llmTest();
    default: throw new Error(`неизвестная операция: ${operation}`);
    }
})().then(
    (value) => { console.log(JSON.stringify({ ok: true, value })); process.exit(0); },
    (error) => { console.log(JSON.stringify({ ok: false, error: error.message })); process.exit(1); }
);
