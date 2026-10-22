# Айдентика L2Hub

Логотип, иконка, превью репозитория и обложки хроник. Стиль — Classic с читаемостью Modern (`docs/PLAN.md`, «Решения», 2026-10-16). Логотип и мотивы обложек выбрал автор 2026-10-18.

Всё своё: ни официального арта NCSoft, ни логотипа Lineage II, ни их персонажей. На картинках нет надписей и имён, боссы нарисованы по мотиву.

## Логотип «Врата»

Стрельчатая арка из бронзы, в светящемся проёме — тёмная цифра 2. Смысл: одна дверь во все хроники. Надпись «L2Hub» — Cinzel Bold (строчные у Cinzel — капитель, как в шапке окна), переведена в кривые: SVG не зависит от шрифтов.

Концепты Codex — `brand/2026-10-18_logo_concept_{1_gate,2_seal,3_astrolabe,4_crest}.png`. Выбран первый. Финальный знак не растр Codex, а перерисованный вектор (`tools/brand_assets.py`), чтобы на 16 px он был резким.

| Файл | Что |
|---|---|
| `brand/logo.svg`, `ui/assets/logo.svg` | знак + надпись в одну строку |
| `ui/assets/logo.png` | то же, PNG 960 px по ширине, прозрачный фон |
| `brand/logo_mark.svg` | знак для крупных размеров (от 40 px): капители, базы, шпиль |
| `brand/logo_mark_small.svg`, `ui/assets/logo_mark.svg` | упрощённый знак для 16–32 px: тоньше рамка, крупнее двойка |
| `brand/icon.svg`, `brand/icon_small.svg` | знак на тёмной скруглённой плитке (иконка приложения) |
| `brand/icon.ico`, `ui/assets/icon.ico` | иконка окна и ярлыка: 16, 20, 24, 32, 40, 48, 64, 96, 128, 256 |
| `ui/assets/icon_256.png`, `ui/assets/icon_512.png` | иконка PNG |
| `brand/png/` | все растровые размеры знака и иконки |
| `brand/social_preview_1280x640.jpg` | превью репозитория GitHub |

Шрифты окна (с 2026-10-20) лежат в `ui/assets/fonts` рядом с лицензиями OFL, интернет окну не нужен:

| Файл | Шрифт | Где |
|---|---|---|
| `cinzel-var.woff2` | Cinzel, переменная жирность | латиница заголовков |
| `philosopher-400.woff2`, `philosopher-700.woff2` | Philosopher | кириллица заголовков: у Cinzel её нет, браузер берёт русские буквы из следующего шрифта в `--serif` |
| `inter-var.woff2` | Inter, переменные `opsz` и `wght` | весь остальной текст |

Это подмножества оригиналов: латиница, Latin-1, кириллица, пунктуация, стрелки, №. Оригиналы — `downloads/fonts/<шрифт>/` (не в git), с `github.com/google/fonts`, `ofl/<шрифт>/`. Пересобрать — `fontTools.subset` (`--flavor=woff2`, с `brotli`). Philosopher выбрал автор из трёх кандидатов: ещё были Cormorant SC и Forum, сравнение — экран «Шрифты» в `docs/design/shell_v3/index.html`.

Палитра — из `launcher/l2hub/ui/styles.css`: фон `#120d09`–`#251c13`, бронза `#4a341c` / `#7a6038` / `#a8834a`, золото `#c9a052` / `#e2c27d` / `#f6e3b4`, тёмная двойка `#1a1009`.

Пересобрать всё (из корня репо):

```
uv run --no-project --with fonttools --with uharfbuzz --with pillow python -X utf8 tools/brand_assets.py --social
```

Растр рисует Edge в headless-режиме. Шрифт: `downloads/fonts/cinzel/Cinzel-wght.ttf` (не в git) — Cinzel, лицензия SIL OFL 1.1, из `github.com/google/fonts`, `ofl/cinzel/Cinzel[wght].ttf`; там же `OFL.txt`. Превью репозитория собирается из обложек `ui/assets` (слоган — Palatino Linotype из Windows: у Cinzel нет кириллицы). Загрузить его — GitHub → Settings → General → Social preview.

## Обложки хроник

С 2026-10-20 обложки — в духе экрана входа клиента своей хроники (решение автора: «все меняй на новые»). Спокойный широкий план места: пейзаж и архитектура, без героев на переднем плане, без текста и логотипов, символы свои. Главное — вверху по центру; низ темнее, там название и кнопка «Играть».

Откуда мотивы:
- **C4, Interlude, Gracia Final, High Five** — снимки экрана входа своих клиентов (`<игры>\…`; клиенты не менялись, снимки в репо не кладутся).
- **Gracia Epilogue, Classic** — экраны входа по поиску в интернете.
- **Essence** — единого экрана входа у неё нет (меняется с обновлениями и ивентами), поэтому прежний сюжет в той же манере.

1942×809 JPG. Исходники Codex (JPG 95 %) — `covers/2026-10-20_cover_<id>_login.jpg`, лист с баннерами и миниатюрами — `covers/2026-10-20_covers_login_sheet.jpg`. В окне — те же картинки, пережатые до 88 %.

| Хроника | Файл в `ui/assets` | Мотив |
|---|---|---|
| C4 | `cover_c4_login.jpg` | солнечная гавань островного королевства, галеоны у причала, белая крепость на зелёной горе, кучевые облака |
| Interlude | `cover_interlude_login.jpg` | тропа в первобытных джунглях: лианы, папоротники, светящиеся грибы, лучи сквозь кроны, череп древнего зверя, арка вдали |
| Gracia Final | `cover_gracia_final_login.jpg` | парящий город-гавань над морем облаков, воздушный корабль у пристани, скалы с голубыми жилами, крепость в дымке |
| Gracia Epilogue | `cover_epilogue_login.jpg` | симметричный готический зал, алый столп света над алтарём, бирюзовые огни в жаровнях, колючие побеги по стенам |
| High Five | `cover_ct26_highfive_login.jpg` | багровая равнина под дождём, красная луна со своим узором, одинокое осеннее дерево — по экрану входа клиента автора (система R.C-TEAM показывает его) |
| Classic | `cover_classic_login.jpg` | летнее утро: мельница на холме, луга, деревня у реки, замок вдали, море и маяк |
| Essence | `cover_essence_login.jpg` | закатное нагорье, врата из камня и бивней, четыре духа стихий в облаках, шпиль в чёрно-фиолетовом пламени |

Прежние обложки (2026-10-18: `cover_c4.jpg`, `cover_interlude_v2.jpg`, `cover_gracia_final.jpg`, `cover_epilogue.jpg`, `cover_ct26_highfive_v2.jpg`, `cover_classic_v2.jpg`, `cover_essence.jpg`; их исходники — `covers/2026-10-18_cover_*.jpg`) и совсем старые (`cover_interlude.jpg`, `cover_ct26_highfive.jpg`, `cover_classic.jpg`, `cover_ertheia.jpg`) лежат как были: убирать — по команде автора. Сравнить старые и новые в окне — макет `shell_v3`, переключатель «Обложки».

## Промпты Codex

Лежат файлами: `brand/prompts/concept_*.txt` (логотип) и `covers/prompts/cover_*_login.txt` (обложки 2026-10-20; прежние — `cover_*.txt`, у Epilogue — второй, удачный вариант). Запуск — по одному процессу на промпт, параллельно:

```
codex exec --skip-git-repo-check --ephemeral -s read-only -C <папка> -o <ответ>.txt - < prompt.txt
```

Модель `gpt-6.1-sol`, `CODEX_HOME=%CODEX_HOME%`. Картинка ложится в `.codex-claude\generated_images\<session id>\`, а session id печатается в начале вывода `codex exec`. Codex сам выбрал размеры: 1536×1024 для концептов, около 1942×809 для обложек (подгоняются до 1942×809 обрезкой по центру).

Общая часть промпта обложки — формат 12:5, стиль серии, правила композиции (главное в средней полосе, левый низ спокойный, силуэт читается на 46×30) и запреты (оригинальный дизайн, без копий чужого арта, без текста и логотипов). Дальше — сцена хроники.

## Как подключить в окно

Код лаунчера правит другая сессия, поэтому здесь только план. Проверка — `python -m l2hub.devserver` в браузере, потом окно.

1. Знак в шапке, `ui/index.html`: вместо инлайн-`<svg class="crest" …>` (щит) —
   `<img class="crest pywebview-drag-region" src="assets/logo_mark.svg" alt="" draggable="false">`.
   Стиль `.titlebar .crest` (22×22) уже подходит. Текст «L2Hub» и тег версии остаются: это и есть надпись логотипа тем же Cinzel.
2. Иконка вкладки в devserver, `<head>`: `<link rel="icon" href="assets/icon_256.png">`.
3. Иконка окна и панели задач, `l2hub/__main__.py`: `webview.start(..., icon=str(paths.UI / "assets" / "icon.ico"))`. В pywebview 6.2.1 WinForms ставит её форме (`platforms/winforms.py`, `_state['icon']`), хотя в докстринге написано «только GTK/QT». Без неё берётся иконка `python.exe`. Чтобы панель задач не склеивала окно с Python, до создания окна:
   `ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("L2Hub.Launcher")`.
4. Ярлык на рабочем столе — на `run.cmd`, иконка `launcher\l2hub\ui\assets\icon.ico`.
5. Обложки, `l2hub/chronicles.json`, поле `cover`:
   - `interlude` → `interlude_v2`; `ct26_highfive` → `ct26_highfive_v2`; `classic_10` → `classic_v2`;
   - добавить `"cover": "c4"` у `c4`, `"cover": "epilogue"` у `epilogue`, `"cover": "essence"` у сборки Essence, выбранной для 1.0 (или у всех `ess_*`);
   - по желанию автора — `classic_v2` у остальных `classic_*`.
6. README и About: `docs/design/brand/logo.svg` — SVG в кривых, на GitHub отображается без шрифтов.
