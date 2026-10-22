# L2Hub

Личный лаунчер локальных серверов Lineage II. Задумка — как La2offline, только с выбором хроники: выбрал хронику, нажал «Играть», лаунчер поднял базу, логин- и гейм-сервер и запустил клиент. Можно бегать одному или позвать друзей.

**Статус (2026-10-22):** из окна ставятся и запускаются Interlude, Gracia Epilogue и High Five (L2J Mobius с модами: охотники-фантомы и напарники, рейды и PvP-ивенты охотников, чемпионы и суперчемпионы, баффер и GM-шоп, поручения дня, сезонные события по календарю), Classic и Essence (с теми же фантомами, клиент 4game), C4 (L2Solo и вариант L2JLisvus) и Gracia Final на копии La2offline; ещё три десятка хроник Mobius — «как есть». В окне: «Мой мир» (кто где и чем занят, карта), режимы сервера и выключатели модов, календарь событий, геодата по кнопке, бэкапы, друзья (Radmin, Tailscale, ZeroTier, локальная сеть), обновления, отзывы и голоса за хроники. Есть установщик и портативный zip (`docs/RELEASE.md`).

**Версия:** 0.4.x — готовы 4 хроники из 7, которые составят 1.0: Interlude, High Five, Gracia Epilogue, Gracia Final, C4, Classic, Essence (Classic и Essence играбельны, C4 ждёт входа автора). История — [CHANGELOG.md](CHANGELOG.md).

## Клиент игры

L2Hub клиенты игры не содержит, не распространяет и ссылок на них не даёт. Нужен чистый клиент нужной хроники и система (патч) с протоколом, который принимает сервер. Где искать — загугли «есть ли сайты со всеми клиентами л2?»: поисковики и ассистенты подскажут фан-сайты с клиентами и чистыми патчами. Скачанное — чужие файлы: проверь антивирусом. Системы с защитой приватных серверов (Strix, SmartGuard и подобные) не подходят.

| Хроника | Протокол клиента |
|---|---|
| Interlude | 746 |
| Gracia Epilogue | 152 |
| High Five | 273 (сервер принимает 267, 268, 271, 273) |
| Gracia Final (La2offline) | 83–183 |
| C4 Scions of Destiny (L2Solo) | 656 |
| Classic — Tales Untold | 286, официальный клиент 4game EU той же сборки |
| Essence — Samurai Crow | 542, официальный клиент 4game EU той же сборки |

## Запуск

- Лаунчер: `launcher\run.cmd` (окно без консоли) или из `launcher\`: `.venv\Scripts\python -m l2hub` (с `--debug` — инструменты разработчика).
  - L2Hub живёт одной копией: повторный запуск показывает уже открытое окно, в том числе спрятанное в трей. Место и размер окна запоминаются.
  - Настройки самого L2Hub — щелчок по версии в шапке: что делает крестик (спросить, в трей, остановить и выйти, выйти и оставить сервер), уведомление «гейм-сервер упал», показ хроник вне плана 1.0. Хранятся в `data/launcher.json`.
- Без окна, для проверки: из `launcher\` — `.venv\Scripts\python -m l2hub.cli state|check|start|stop ct26_highfive`.
- Карта мира в «Мой мир» и «Пульт и карта» на главной: подложка — тайлы [npetrovski/l2-world-map](https://github.com/npetrovski/l2-world-map) (лицензии нет, по сути карта NCSoft — только локально, не в git). Один раз: `git clone --depth 1 https://github.com/npetrovski/l2-world-map downloads/l2-world-map`, затем из корня `uv run --no-project --with pillow python -X utf8 tools/world_map_tiles.py` — webp ляжет в `launcher\l2hub\ui\maps` (в `.gitignore`).
- Дев-режим (тот же интерфейс в браузере): из `launcher\` — `.venv\Scripts\python -m l2hub.devserver`, затем `http://127.0.0.1:8770/?dev` (именно 127.0.0.1: с `localhost` devserver отвечает 403). Второй экземпляр — с портом аргументом: `… -m l2hub.devserver 8772`.
- Зависимости ставятся `uv sync` в `launcher\` (Python 3.12, pywebview, psutil; для сборки exe — PyInstaller в dev-группе).
- Собрать exe и установщик: из `launcher\` — `.venv\Scripts\python packaging\build.py --installer` → `dist\L2Hub\` (`L2Hub.exe`, `l2hub-cli.exe`) и `dist\L2Hub-<версия>-setup.exe`; нужен Inno Setup 6. Собранный L2Hub берёт данные из папки, выбранной в установщике (`%LOCALAPPDATA%\L2Hub\settings.json`), запуск из исходников — всегда из репозитория. Что готово и чего нет — `docs/RELEASE.md`, «Программа и установщик».

- План, архитектура, фазы и открытые вопросы — [docs/PLAN.md](docs/PLAN.md)
- Какие хроники есть и чем их закрывать — [docs/CHRONICLES.md](docs/CHRONICLES.md)
- C4 на L2Solo (Node.js, свой эмулятор с ботами): установка, обновление, запуск — [docs/L2SOLO.md](docs/L2SOLO.md)
- Правила для агентов — [AGENTS.md](AGENTS.md)

Серверная часть — в основном [L2J Mobius](https://gitlab.com/MobiusDevelopment/L2J_Mobius) (по сборке на хронику). Клиенты игры проект не содержит и не распространяет.
