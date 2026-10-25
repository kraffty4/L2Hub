# Classic в L2Hub

Создан 2026-10-20. Профиль `classic_tales` (`profiles/classic_tales.json`), сборка `L2J_Mobius_Classic_3.5_TalesUntold` из свежего `master` Mobius, протокол 286. Хроника из списка 1.0: играбельна (пробный вход агента 2026-10-21, русский ввод через `system_ru`), пока без наших модов и фантомов.

## Почему Tales Untold

У Mobius девять сборок Classic. `AllowedProtocolRevisions` в `game/config/Server.ini` (`master`, 2026-10-20): Classic 1.0 — 19, Age of Splendor — 28, Saviors — 64, Zaken — 110, Antharas — 140, Secret of Empire — 166, Saviors 2.9.5 — 196, The Kamael — 228, Tales Untold — 286. Все девять правятся одинаково активно (57–58 коммитов с 2026-07-01). У всех `ServerListType = Classic`.

С обновления Epic Tale of Aden клиенты 4game единые: в одном клиенте данные основной ветки, Classic (`*_Classic*.dat`, `*_Classic.unr`, `L2text_classic`) и Essence (`*_ClassicAden*`). Какие данные грузить, клиент решает по типу сервера в списке серверов. Поэтому сборки Classic у Mobius идут парами с основной веткой на одном протоколе: Antharas и Salvation — 140, Secret of Empire и Fafurion — 166, The Kamael и Prelude of War — 228, Tales Untold, Homunculus и Essence Dwelling of Spirits — 286.

Решение автора 2026-10-20: Tales Untold — самая свежая Classic у Mobius, и под неё нашёлся целый официальный клиент с совпадающим протоколом.

### Клиент Melior (не подошёл)

`<загрузки браузера>\Melior Client.rar` (RAR5, 14,4 ГБ, 4511 файлов, 29,9 ГиБ) — клиент приватного сервера Melior, скачан автором. Осмотрен списком (`7z l`), не распаковывался:
- папки `system` нет: ни одного exe, dll, ini — только данные (`Textures`, `SysTextures`, `Maps`, `StaticMeshes`, `Animations`, `Sounds`, `Voice`, `music`, `Video`, `L2text`, `L2text_classic`);
- это данные единого клиента 4game (236 карт основной ветки и 85 `*_Classic.unr`) примерно на конец 2023 года, сверху файлы Melior 2024 года (`L2Melior.utx`, `R33T.utx`, `ui*.ugx`, своё видео входа `login_classic.usm`);
- на сайте сервера: «Server Base Version: Kamael 4.0», «Client Version: 447 Shinemaker». Систему Melior раздаёт отдельным патчем (EN, KR, RU) и своим апдейтером, с привязкой к железу (`.lockHwid`) — то есть со своей защитой.

Протокол 447 у Mobius принимают только основная ветка `L2J_Mobius_11.3_Shinemaker` и `L2J_Mobius_Essence_07.3_SevenSigns`; Classic-сборки кончаются на 286. Classic у Melior — их собственный датапак на клиенте 447, Mobius такого не умеет.

Другие отброшенные варианты:
- «Lineage II – Tale Of Aden – Prelude of War Client» на архив клиентов EU (12,4 ГБ) — по оглавлению снимок 2019-08/09 (`L2.exe` 2018 года, `L2.bin` нет), протокол старше 228; к The Kamael пришлось бы ставить отдельную систему «Prelude of War (NA/EU228)» поверх более старых данных;
- «Classic» 9,33 ГБ на l2elo.com — `Classic.rar` с rutracker, торрент 2014 года, протокол неизвестен.

## Что скачано

- Исходники Mobius: GitLab `MobiusDevelopment/L2J_Mobius`, ветка `master`, архив папки `L2J_Mobius_Classic_3.5_TalesUntold` (`-/archive/master/L2J_Mobius-master.zip?path=L2J_Mobius_Classic_3.5_TalesUntold`), 21,1 МБ, 10 263 файла → `downloads/L2J_Mobius_Classic_3.5_TalesUntold_2026-10-20.zip`, распакованы в `servers/classic_tales/L2J_Mobius_Classic_3.5_TalesUntold`.
- Клиент: архив клиентов EU, «Lineage II – Essence – Dwelling of Spirits – EU 286», 12 846 274 230 байт (zip, 3675 файлов) → `<загрузки>\Lineage II - Essence - Dwelling of Spirits - EU-P286.zip`, клиент — в `<игры>\Lineage2-Classic`. Перед скачиванием оглавление проверено Range-запросом хвоста архива: центральный каталог на месте, внутри `system`, данные Classic, основной ветки и Essence.
- Геодата не скачивалась: в `dist/game/data/geodata` Mobius лежит только `Readme.txt`. Сервер работает и без неё.

## Сервер

- Сборка — `scripts/server_build.ps1 -Id classic_tales`: JDK 27 (`build.xml` требует Java 27), 71 с. Итог распакован в `servers/classic_tales/server`.
- База — `scripts/server_setup.ps1 -Id classic_tales`: `l2jmobiusclassic` в общей MariaDB, 109 SQL-файлов. Логин и гейм — на `127.0.0.1`, `ipconfig.xml` только с `127.0.0.1`. Аккаунт создаётся при первом входе (`AutoCreateAccounts = True`).
- `Server.ini`: `AllowedProtocolRevisions = 286`, `ServerListType = Classic`, `RequestServerID = 1`. Память — в `game/java.cfg`, как у Mobius по умолчанию, `-Xmx4g -Xms2g` с ZGC; лаунчер поднимает до 3/8 ОЗУ в пределах 4–8 ГБ (`servers.game_heap_gb`, с 0.5.2), на 32 ГБ — 8 ГБ.
- Патч `patches/classic_tales/0001-gameserver-bind-hostname.patch` — тот же, что у Essence: гейм слушает `GameserverHostname`, а не все интерфейсы. `GameServer.java` с LF, патч тоже.
- Пробный старт без клиента 2026-10-20: мир за 93 с, стоп за 5 с, `error0.log` пуст.
- Соглашение Innova на входе выключено (решение автора 2026-10-21): `ShowLicence = False` в `login/config/Server.ini`. Откат — `ShowLicence = True`; исходный файл — `data/backups/config/classic_tales/2026-10-21_19-15-48/`. На свежем сервере — руками (или мастер установки).
- В логе гейма одна ошибка: `LogisticOfficer: Found addFirstTalkId for non existing NPC: 34148!`. Скрипт крепости орков (`ai/others/OrcFortress`) Mobius перенёс из Essence, а шаблона NPC 34148 (Supply Officer, квартирмейстер владельца крепости) в датапаке Classic нет; в данных клиента Classic (`NpcName_Classic-eu.dat`, `Npcgrp_Classic.dat`) его тоже нет. Шаблон не добавляем: клиент такого NPC не покажет. Ошибка безвредна, NPC нужен только клану — владельцу крепости (группа `orc_fortress_owner`, `spawnByDefault="false"`).

## Клиент

`<игры>\Lineage2-Classic` — единый клиент 4game EU: папка `ADEU-P286-D20210203-F-200527-210208-1`, 3675 файлов, 23,5 ГиБ. Сборка `L2.exe`, `Engine.dll`, `Core.dll`, `NWindow.dll` — 2021-02-03 (заголовки PE). Распакован в `<игры>\_unpack_classic286\` и 2026-10-21 переименован на место; пустая папка распаковки — в Корзине.

Осмотр 2026-10-21:
- подписи: `L2.exe` (= `L2.bin` байт в байт), `Engine.dll`, `Core.dll`, `NWindow.dll`, `aegisty.bin`, `game_presence-32.dll` — NCSOFT, действительны; остальное — Microsoft, Valve, Adobe, Awesomium, Bandisoft или без подписи (движок Unreal, звук, CEF); `Frost\frostUpdater.exe` — Innova; `gameManager\gameManager.exe` — компонент лаунчера 4game (Innova, подпись не проверяется);
- драйверов `.sys` нет; `bb.dll`, `YetiLoaderDLL.dll` и NC-платформы, как у Essence 542, ещё нет; от GameGuard — только `npkcrypt.dll` при `NOGG=true`;
- Defender (`system`, `gameManager`) угроз не нашёл;
- `system\L2.ini` — ключ NCSoft, `[Auth] ServerAddr=geo-ad.l2.eu`, `ExternalLogin=true`, `CmdLineLogin=true`, `FrostModule=true`, автологин `zodiac` — та же история, что у Essence 542.

Подключение — как у Essence: `L2Hub.ini` собран `tools/l2ini_plain.py` (`ServerAddr=127.0.0.1`, вход окном клиента, Frost выключен), лаунчер запускает `L2.exe -INI=.\L2Hub.ini`. Штатный `L2.ini` не тронут. «Проверить всё» 2026-10-21 — всё зелёное, кроме геодаты.

### Русский ввод

Тексты Classic-части в этом EU-клиенте и так русские: `NpcName_Classic-eu.dat` и соседние файлы Classic у Innova локализованы по-русски (имена NPC и мобов в игре — «Гремлин», «Помощник Новичков»), меню и системные сообщения — английские. Шрифт кириллицу показывает, а набирать её клиент не даёт: с языком `9=Europe` (`system\Localization.ini`) фильтр ввода её выкидывает — как у High Five (`docs/RU_CHAT.md`).

Решение — то же, что у H5, без чужих файлов: `python -X utf8 -I tools/client_system_ru.py "<игры>\Lineage2-Classic" --base Europe` копирует `system` в `system_ru` (312 МБ) и в копии ставит `Language=8` (Russia), а раздел `[Russia]` направляет на тексты, шрифты и текстуры `[Europe]` (коды `-eu`). У клиентов 4game `Localization.ini` — `Lineage2Ver111` без 20-байтного хвоста с CRC, утилита это различает. Профиль запускает `system_ru\L2.exe`. Откат — в профиле `client.exe` и `client.ini` обратно на `system\…`, `system_ru` — в Корзину.

### Пробный вход 2026-10-21

Агент под замком портов (сервер из консоли, клиент запущен напрямую, ввод — эмуляцией клавиатуры и мыши в окне клиента, снимки окна):
- логин: учётка `l2hubtest` создана сама (`AutoCreateAccounts`), протокол логина сошёлся;
- соглашение Innova (`ShowLicence = True` в `login/config/Server.ini`, у всех профилей Mobius так) агент за автора не принимал: на время теста `ShowLicence = False`, после — вернул `True`;
- выбор сервера: «Sieghardt» в группе «Essence and Classic servers»;
- гейм принял протокол 286, создан эльф-воин `Probnik` (камаэли в выборе рас есть), вход в мир у Древа матери эльфов — туториал, мобы, NPC; инвентарь и стартовые предметы с иконками, пустых предметов не видно;
- чат: из `system` кириллица не набирается (`Привет, мир!` → `, !`), из `system_ru` — набирается («Привет, мир!»), кроме буквы Ё — её фильтр языка Russia тоже выкидывает;
- `[AutoLogOn]` в открытом ini (`IsL2AutoLogOn=True` с логином и паролем) на этом клиенте не срабатывает — поля логина пустые;
- в логе гейма только известная ошибка LogisticOfficer, `error0.log` пуст. Учётка `l2hubtest` с персонажем `Probnik` осталась в базе `l2jmobiusclassic` (автору не видна: другой аккаунт).

## Геодата (2026-10-21)

У Mobius геодаты Classic нет, готовые сборки на форумах — сливы платных паков с неясной лицензией. Строим её сами из карт своего клиента: [l2mapconv](https://github.com/m49n/l2mapconv-public) (MIT, релиз `p542-preview-20261005`, проверен автором утилиты на Essence Samurai Crow EU P542; на нашем Classic EU 286 тоже работает). Релиз — `runtime/l2mapconv/` (51 МБ zip, SHA256 сверен с `SHA256SUMS.txt`, Defender чисто, драйверов нет; внутри — сам exe, рантайм MSVC и Java для экспериментального навмеша).

- `python -X utf8 -I tools/geodata_build.py "<игры>\Lineage2-Classic" servers/classic_tales/server/game/data/geodata --jobs 6` — все `Maps/XX_YY.unr` клиента в границах мира Mobius (X 11–28, Y 10–26): 233 региона. Пакетный режим l2mapconv (`--geodata-job`, только L2J, без `_conv.dat`), задания — в `data/geodata_build/classic_tales/`. Регион — около 2 минут и 2 ГБ памяти на процесс; 6 процессов — 55 минут, 233 региона без ошибок, 1,7 ГБ. Сервер: `GeoEngine: Loaded 233 regions`, мир за 33 с.
- Порог подъёма не строже ритейла: распределение перепадов высот между соседними клетками (открыт проход или закрыт) у нашей `22_22` такое же, как у геодаты L2J H5 и Interlude.
- Сверка Гирана (`22_22`) с геодатой H5 (L2J Geodata): медиана расхождения высот 0, 91 % клеток в пределах 16, проходимость совпадает на 96 % (для сравнения Interlude против H5 — 99,8 %). Расхождения — в основном многоэтажность: генератор видит крыши и мосты отдельными слоями.
- Геодата получается из клиента пользователя, как тайлы карты, — её не раздаём; в публичной версии лаунчер строит её сам.

## Моды High Five (2026-10-21)

Перенесены четыре мода H5 — `patches/classic_tales/` (имена как у H5 и Epilogue): за основу — патчи Epilogue (в LF: у Classic все файлы LF), отказы `git apply` доперенесены под API Classic.
- `0002-champion-tiers` — два уровня чемпионов. У Classic чемпион назначается в `Attackable.onRespawn`, а не в `Spawn`; атака, скорость и реген — через «финализаторы» статов (`mechanics/stats/finalizers`), а не `CreatureStat`; титул — в `Creature.getTitle`, отдельного `AbstractNpcInfo` нет.
- `0004-buffer-max-level` — бафы схем-баффера на максимальном уровне по данным скиллов Classic (`toLevel`): у Classic уровней больше, чем у H5 (Blessed Body — 12, Haste — 4, Wind Walk — 6). Скилла 1352 (Elemental Protection) в данных Classic нет — это недоделка списка Mobius, оставлен как есть.
- `0012-community-board` — **отложен** в `patches/classic_tales/pending/` (сборка его не накладывает): окна напарников и охотников на доске — из модуля фантомов, и без него скрипт доски не компилируется, а с ним падает весь список скриптов Mobius (проверено 2026-10-21: `Failed to execute script list!`). Вернуть в `patches/classic_tales/` вместе с `phantoms/classic_tales/`. Что в нём — доска Alt+B: баффер, GM-шоп (NPC 50010 «Mirellas», 125 мультиселлов), телепорт, напарники, охотники, поиск дропа. Мультиселл с доски — через `MultisellListHolder`/`PreparedMultisellListHolder` Classic; питомец — `getPet()` или первый из слуг (их у Classic может быть несколько); снятие бафов — `stopEffects(...)`, как в родных диспелах; карма — отрицательная репутация. Меню — родные текстуры Classic `HtmlWnd_DF_*`. В телепорте Classic 12 городов (нет Годдарда, Руны, Шутгарта, Хейна, деревни камаэлей).
- `0014-npc-view-ru` — окно моба по Shift+клику по-русски.
- `0003-town-buffer-gmshop` — схем-баффер (50008) и GM-шоп (50010) у гейткиперов 13 городов (Хейна, Руны, Шутгарта и камаэлей в Classic нет), 26 NPC; точки — `tools/town_npcs.py` по геодате Classic. У Classic гейткиперы Глудио, Орена, Годдарда и деревни Охотников стоят на постаментах (ступенька 24 при пороге 16) — `town_npcs.py` теперь ищет путь от открытой земли перед гейткипером, если от него самого не сойти (на H5, Interlude и Epilogue этот путь не срабатывает: вывод тот же байт в байт). Формат спавнов — по схеме датапака: у Classic и Essence `respawnTime="60sec"` и `<list>` без `enabled`, у CT как раньше. Выключатели (2026-10-21, для окна лаунчера) — `game/config/Custom/L2HubTown.ini`, `TownBuffer` и `TownGmShop` (по умолчанию `True`; `False` — NPC не появляются со следующего запуска гейма). Устроено группами спавна Mobius: `town_npcs.py --switches` кладёт баффер и шоп в группы `TownBuffer` и `TownGmShop` с `spawnByDefault="false"`, а скрипт `custom/L2HubTown` при активации спавна спавнит включённые группы (имя группы — ключ ini). Ядро не тронуто. У CT групп спавна нет — там этот способ не подходит.
- Самопроверка `0015` — после того как соседняя сессия закоммитит её у Epilogue.

Конфиги сервера (бэкап — `data/backups/config/classic_tales/2026-10-21_18-55-35/`): `ChampionMonsters.ini` и `CommunityBoard.ini` — собранные (в сервере были как в архиве Mobius); `NPC.ini` — `AltGameViewNpc`, `ShowNpcLevel`, `ShowNpcAggression` включены; `Player.ini` — `EnableModifySkillDuration = True`, `SkillDurationList` — 75 бафов баффера по 10800 с.

## Календарь сезонных событий (`0016`, 2026-10-21)

Врезка в `LongTimeEvent` — дословно как у Interlude (`docs/INTERLUDE.md`, «События Mobius и календарь L2Hub»): окно события берётся из `game/config/Custom/L2HubEvents.ini` по имени папки события, `Enabled = False` — всё как в `config.xml` Mobius, `Off` — выключено. Своё — только ini.

У Classic 9 событий Mobius (`data/scripts/events`), даты в их `config.xml` — 2017–2022 (идёт только сбор букв, до 2034). Все NPC событий клиент знает (сверено по `NpcName_Classic-eu.dat`: Шеф-повар Обезьянка, Дед Мороз, Кот Орион, Коко Брауни и т. д.). Расписание по умолчанию (предложение агента): сбор букв — круглый год; Happy Hours — январь, The Power of Love — февраль, Chef Monkey — март, Ева-предсказательница — апрель–май, Watermelon Ninja — июнь–август, Squash — сентябрь–октябрь, Total Recall — ноябрь, Merry Squashmas — декабрь. Окна NPC событий не переводились (тексты клиента у Classic и так русские, а htm событий — английские).

## Суперчемпион и PvP-ивенты (2026-10-21)

**Суперчемпион** (`0020-super-champion`) — третий уровень чемпионов над красными и синими, мини-РБ, по рецепту Interlude (`docs/INTERLUDE.md`, «Суперчемпион»: шанс 0,1 %, HP x20, атака x3, опыт x20, объявление в регионе). Общий класс `entity/actor/SuperChampion.java` и врезки в `ChampionMonstersConfig` — как у Interlude (генератор `tools/l2hub_super_champion.py`). Своё у Classic и Essence:
- шанс чемпиона бросается в `Attackable.onRespawn`, а не в `Spawn`: суперчемпион — первым, затем красный и синий; эффекты над моделью ставит и снимает тот же `onRespawn`;
- объявление — в `Spawn` сразу после `spawnMe`: в `onRespawn` у моба ещё прежние координаты;
- эффекты над моделью у Classic и Essence живут в `EffectList` и пересобираются из бафов при каждом бафе и дебафе — врезка `SuperChampion.keepEffects` в `EffectList.updateEffectList` возвращает их в новый набор (иначе первый же дебаф игрока снял бы «золото»);
- «золото» подобрано по клиенту: таблица эффектов `system\AbnormalDefaultEffect.dat` (Lineage2Ver413, расшифровка — `tools/l2ini_decode.py`) — 429 эффектов у клиента Classic, 1086 у Essence; старые `BIG_BODY` и `ULTIMATE_DEFENCE` клиент рисует сам (их зовут скиллы датапака). Classic: `BIG_BODY,ULTIMATE_DEFENCE,H_GOLD_STAR3_AVE,MAGIC_SQUARE` (крупнее, свечение Ultimate Defense, золотые звёзды и белый круг под ногами «Magic Circle» — с 2026-10-25, как у H5, где его выбрал автор; умолчание в `ChampionMonstersConfig` — `BIG_BODY,ULTIMATE_DEFENCE,MAGIC_SQUARE`, как у остальных хроник); Essence — ниже, в `docs/ESSENCE.md`. Примерить на цели в игре: `//ave_abnormal` (без имени — список);
- награда — `91663,30` (L-Coin: с мобов не падает, тратится в магазине L-Coin) вместо медалей Interlude; предложение агента, правится `SuperChampionRewardItems`.
- Ключи — блок `SuperChampion*` в конце `game/config/Custom/ChampionMonsters.ini`, выключатель окна — `SuperChampionEnable`.

**PvP-ивенты по расписанию** (`0019-l2hub-pvp-events`) — тот же патч, что у Interlude, H5 и Epilogue (`docs/INTERLUDE.md`, «PvP-ивенты по расписанию»): TvT, Deathmatch и CtF Mobius по крону из `game/config/Custom/L2HubEvents.ini` (`PvpEvents`, по умолчанию выключено), порог уровня `PvpMinLevel` (у скриптов Mobius 76 и 85), фантомы без клиента не выкидываются перед ареной. Собран генератором `tools/l2hub_pvp_events.py` без правок, кроме одной: новый скрипт теперь пишется с переводами строк скриптов ивентов хроники (у Classic и Essence — LF; у CT — CRLF, как было).

Развёрнутые `ChampionMonsters.ini`, `L2HubEvents.ini` и `PhantomHunters.ini` были без правок автора — заменены собранными, копии — `data/backups/config/classic_tales/2026-10-21_23-42-16/`.

**Проверка 2026-10-21** (старт и 5 минут мира; на время теста `PvpEvents = True`, `PvpOnlyWithPlayers = False`, `Deathmatch = */2 * * * *`, TvT и CtF пусто, потом ключи вернул): суперчемпионов на старте — 30 у Classic (29 284 спавна) и 25 у Essence (40 145), объявления с городом региона по-русски; Deathmatch стартовал по расписанию, записались 12 охотников (уровни 1–8, порог `PvpMinLevel = 1`), бились на арене, следующий старт во время боя — «пропущен — идёт PvP-ивент». Возврат с арены в окно теста не попал (бой дольше). `error0.log` пуст; кроме известных OrcFortress у Classic, предупреждений нет. Эффекты суперчемпиона глазами не проверены — нужен клиент.

## Сезонные события с охотниками, Elpies, самопроверка (2026-10-22)

- **Охотники на сезонных событиях** — ядро `PhantomSeasonal` (epilogue-h5, `docs/PHANTOMS.md`, 4.34) работает у Classic и Essence без правок: NPC событий — из `config.xml`, обмен — по мультиселлам. Сейчас идёт Squash: Puss the Cat (31860) в 13 городах и деревнях, мультиселлы 90318600–90318601 (нектар → семена арбуза); у Essence ещё Адель (Храм Воды, 34456) и Леона (подземелье, 15985) в Гиране и Адене. Сбор букв у Classic и Essence — окно клиента без NPC: охотникам буквы только падают. Проверено 2026-10-22 (15 минут мира): у Essence 6 визитов к Puss the Cat, двое выменяли «10 Nectar → 1 Prime Watermelon's Seed»; у Classic за то же время визитов не было (охотники не дошли до городских дел — у H5 первые визиты тоже были минут через 12).
- **Elpies по расписанию** (`0021-elpies-schedule`) — часть Interlude `0021` про Elpies (`docs/INTERLUDE.md`, «L2 Day в больших городах и Elpies по расписанию»; L2 Day у Classic и Essence нет): ключи `Elpies`, `ElpiesSchedule`, `ElpiesMinutes`, `ElpiesOnlyWithPlayers` в конце `L2HubEvents.ini`, город — ближайший к живому игроку, объявления по-русски, исправлено падение Mobius при старте по расписанию. У Classic и Essence Elpies считает эльпи счётчиком, а не набором, — врезки под их код. Проверено (временно `ElpiesSchedule = */3 * * * *`, `ElpiesOnlyWithPlayers = False`): нашествия в Hunters Village и Oren по 10 минут, следующее — только после конца предыдущего.
- **Самопроверка модов** (`0015-l2hub-selftest`) — скрипт H5 и Epilogue: через минуту после старта `game/log/L2HubSelfTest.json` и «Моды L2Hub» в «Проверить всё». Своё у Classic и Essence одно: списки мультиселлов в `MultisellData._multisells`, а не `_entries` (скрипт пробует оба). Проверено: 12 из 13 пунктов зелёные до правки мультиселлов.
- **Самопроверка модов малого мира** (`0032-l2hub-selftest-small-world`, генератор c4-interlude `tools/l2hub_selftest_small.py`, 2026-10-24) — три пункта «Моды L2Hub»: олимпиада малого мира (`0025`: с какого числа записавшихся матч, период героев, сколько до его конца, идут ли матчи), эпики малого мира (`0028`: пересчитан ли респаун — интервал Антараса против `GrandBoss.ini`, ожидание Антараса, какие эпики живы), фейки в городах (`0031`: сколько их в мире по расписанию). Семи печатей у Classic и Essence нет — нет и пункта; Валакаса и Белета в `GrandBossConfig` нет — пункт эпиков без них. Слой языка `0099` выше `0032` и оборачивает в `tr()` каждую русскую строку, поэтому генератор снимает его с дерева, а затем его пересобирает `tools/l2hub_lang.py <id> --write`. Переводы — в `tools/l2hub_lang/en.json`, кроме пометки «L2Hub (малый мир)»: её проба ищет в `Olympiad.ini`. Проверено сборкой с нуля в тестовой папке данных: Classic (en) и Essence (ru) — 19 из 20 (красный — окно моба, `npcinfo` там выключен), `error0.log` пуст.
- **SetHateRace** (`0050-npc-hate-race`): у пяти орков 8–17 уровня (Orcish Shooter, Vuku Orc, Vuku Orc Shooter, Vuku Orc Warrior, Orc Warrior) в датапаке Mobius `SetHateRace = race_human` и т. п., а код ждёт `HUMAN` — удар игрока или охотника по ним падал в `Attackable.getHateRatio` с `IllegalArgumentException` (4 раза за 15 минут у Essence). Значения исправлены на `HUMAN`, `ELF`, `DARK_ELF`, `ORC`, `DWARF`.

## «Мир сейчас» и кнопки доски (2026-10-22)

- **«Мир сейчас»** (`0022-l2hub-world-info`, генератор c4-interlude `tools/l2hub_world_info.py`, `docs/INTERLUDE.md`): команда `.world` — окно с живыми суперчемпионами и их городами, идущими сезонными событиями, ближайшими PvP-ивентами и эльпи; `game/log/L2HubWorld.json` раз в минуту — для «Моего мира» в лаунчере. С 0.4.128 у c4-interlude там же рейд-боссы и эпики (жив ли, когда встанет, город); у Classic и Essence РБ — в `DatabaseSpawnManager` вместе с другими NPC из базы (РБ — по типу шаблона `RaidBoss`, живой — `getNpcs()`, статус — `getStatus`), поэтому эталон генератора поправлен под них: 4 замены, скрипт `wi_adapt.py` в папке заметок (эталон из HEAD → правки → генератор; переводы строк — LF). Проверено 2026-10-22: `L2HubWorld.json` раз в минуту — суперчемпионы (38 у Classic, 29 у Essence), сезонные события, расписание PvP и эльпи. Рейд-боссы: у Classic 160 (159 живых, 6 эпиков), у Essence 106 (104 живых, 6 эпиков; у трёх нет города — точки нет).
- **«Летопись мира»** (`0023-l2hub-chronicle`, генератор c4-interlude `tools/l2hub_chronicle.py`): скрипт `custom/L2HubChronicle` пишет `game/log/L2HubChronicle.json` — ленту для экрана «Летопись» лаунчера (убийства РБ, суперчемпионов и эпиков, эпик встал, итоги PvP-ивентов, сезонные события, эльпи, профессии и уровни 20/40/52/61/76). Правка под Classic и Essence одна: `OnAttackableKill` у них в `holders.actor.npc`, а не в `holders.actor.npc.attackable`.
- **Самопроверка живого мира** (`0024-l2hub-selftest-world`, генератор c4-interlude `tools/l2hub_selftest_world.py`, поверх `0015`): пункты «Суперчемпионы», «Мир сейчас: .world», «Нашествие эльпи», «Летопись мира». Собран байт в байт, компилируется и разложен на обе хроники; вживую ещё не прогонялся. «Летопись» поднимается («записей из прошлых запусков: 0»), но запись в ленту вживую не поймана: лента пишется только при событии, а первое состояние (уже идущие эльпи в первые 30 с) она считает исходным. Проверить — эльпи `ElpiesMinutes = 1`, `ElpiesSchedule = */2 * * * *`, `ElpiesOnlyWithPlayers = False`, 5 минут мира.
- Правки эталонов c4-interlude под Classic и Essence («Мир» — 4 замены, «Летопись» — 1) накладывает `ref_adapt.py world|chronicle <id> [--write]` (папка заметок): эталон из HEAD → замены → генератор; эталон поменялся так, что замена не легла, — скрипт останавливается и говорит, какая.
- **Кнопки доски Alt+B** (`0051-board-buttons`): «Поручения дня» (`.daily`), «Рейд охотников» (`.raid`) и «Мир сейчас» (`.world`) — как `0017` у CT, но в разметке доски Classic и Essence (строки таблицы, текстуры, которые доска уже использует). Номер после `0022`: генератор «Мира» вставил бы свою кнопку в стиле Interlude рядом с «Рейдом», а уже стоящая кнопка его останавливает; поздние патчи генератор снимает. Номера с `0050` — свои правки Classic и Essence.
- Самопроверка (`0015`) после правки мультиселлов — 13 из 13 у обеих хроник (мультиселлов в окнах GM-шопа: 120 у Classic, 150 у Essence, все загружены); после `0050` ошибок `getHateRatio` нет.

## Роли саппортов охотников (2026-10-22)

Роли (HEALER, BUFFER, TANK, DAMAGE) ядро берёт из шаблона фантома — их пишет генератор по общим таблицам персонажей, — а хилы, бафы и Recharge — по id скиллов, которые фантом знает. Сверка по деревьям классов (`stats/players/skillTrees`, скрипт `roles_check.py` в папке заметок): цепочки классов и роли у Classic и Essence одинаковые и совпадают с H5, кроме замены камаэлей (`NO_KAMAEL`). Bishop/Cardinal (Vesna, Ded) — 8 хилов из 9 (Major Group Heal 1402 в Classic и Essence нет — ядро берёт Greater Group Heal), Elven Elder и Shillien Elder (Alenka, Nyusha) — хилы и **Recharge у Classic есть, у Essence нет** (у Essence мана другому — только Eva's Blessing у Eva's Saint на 80-м; у Elder — лишь на себя: Rapid Recharge, MP Recovery): ядро проверяет `getKnownSkill(Recharge)` и без него ману не льёт — так и задумано хроникой. Чистый баффер — только напарник Mityai (Prophet → Hierophant): 16 бафов из 19 у Classic, 15 у Essence. Сопоставление «класс → роль» через `PhantomCompat` не понадобилось. Вживую: у Essence охотники-хилеры в пати — «хилит и бафает» (Alenka, Ded), у Classic — пати с хилером-лидером (Nyusha).

## «Хардкор», автоохота и дуэли с охотниками (2026-10-22)

Classic и Essence одинаково; что у Essence своё — в скобках.

- **«Хардкор» — это и есть официал.** Умолчания Mobius у обеих хроник сделаны «как на официале» (так написано в шапке `Rates.ini`, `Player.ini`, `General.ini` и других: «The defaults are set to be retail-like»; рейты — «Retail value = 1»). Поэтому общий x1 режима и есть официальные рейты. Sayha's Grace (Vitality): `RateVitalityExpMultiplier = 3` — опыт x3, пока есть очки; `RateLimitedSayhaGraceExpMultiplier = 2` — x2 под ограниченной Sayha's Grace. Режимы эти ключи не трогают. Штраф смерти — потеря опыта по датапаку, ключей у него нет. Свой формат режимов по хроникам не понадобился. Ключи, которых в сборке нет, режим и так пропускает, а у Classic и Essence режим ложится без пропусков (29 правок, в «Хардкоре» по факту меняется 8 ключей: баффер, GM-шоп, бафы на 3 часа, чемпионы, суперчемпионы, `.daily`, эльпи, автопоты).
- **Что было не как на официале и исправлено:**
  - Доска Alt+B отдавала баффер и GM-шоп при `TownBuffer = False` и `TownGmShop = False`: «Хардкор» убирал NPC у гейткиперов, а с доски всё оставалось. Исправлено в `0052-town-switches-board`, как `0018` у H5: выключено — доска отвечает «выключен в настройках сервера».
  - Доска давала бесплатный телепорт из любой точки и хил (`CommunityTeleportPrice = 0`). Теперь «Хардкор» ставит в `Custom/CommunityBoard.ini` `CommunityEnableTeleports`, `CommunityEnableHeal` и `CommunityEnableBuffs` в `False`, а «PvP-крафт» и «PvP» — обратно в `True`. Это общая таблица `server_modes.json`, ключи есть у всех хроник Mobius с доской. Страница телепортов на доске остаётся, но кнопки переноса молчат: в логе гейма — `Couldn't find parse handler for command _bbsteleport`.
  - Самопроверка в «Хардкоре» краснела на нарочно выключенном: NPC у гейткиперов, бафы на 3 часа, чемпионы. С `0052` это пункты «выключены (…)», как у суперчемпионов и эльпи.
- **Где Mobius отходит от официала и что не трогаю:** `ClanWarKillMessage` и `ShowEffectMessagesOnLogin` (на официале `True`) — косметика; `PacketEncryption = False` — для скорости. L-Coin с мобов (`LCoinDropEnable`, 15 %, мобы от 40-го уровня) — кастом Mobius, выключен. В дропе мобов L-Coin нет ни у одной хроники. У официального Essence есть дневной лимит L-Coin: 160 до 40-го уровня, 300 с 41-го (патчноут 4game от 2019-10-02). Откуда они берутся, источники не говорят, поэтому не включаю.
- **Автоохота клиента** (у Essence встроенная, у Classic тот же `AutoPlayTaskManager` Mobius; охотники ходят на нём же):
  - Цели. У охотников включена «вежливая охота» (`setRespectfulHunting(true)` в `PhantomHunterOutfit`): моба, который бьёт живого игрока, охотник не берёт и бросает своего, если тот переключился на игрока. Живой игрок — по своей настройке в окне автоохоты.
  - Лут. Автоохота Mobius не смотрела на защиту дропа (`DropProtection`, 15 с после убийства). Мобы роняют лут с `isProtected() = false` (это защита от автоудаления), и фильтр подбора считал чужой дроп своим. Живой игрок бежал к луту охотника, раз за разом получал «Не удалось подобрать» и стоял без охоты, пока защита не спадёт, а потом забирал чужое. Охотники так же стопорились на дропе игрока. Исправлено во врезке `AutoPlayTaskManager` (`phantoms/<id>/glue.patch`): чужой защищённый дроп автоохота не трогает. В пати защита общая, как у Mobius.
- **Дуэль с охотником** (ядро epilogue-h5, `docs/PHANTOMS.md`, 4.40): врезка в `RequestDuelStart`, как у H5. У фантома нет окна «принять дуэль?», отвечает сервер. С 2026-10-25 вызывающий, который сам не может начать дуэль, получает причину: в бою, HP или MP меньше половины, мирная зона и так далее (`getNoDuelReason`), а не общее «сейчас нельзя». В дуэли пати так же видно, кто из своей пати не готов и почему. Заодно пересобрана «Летопись» (`0023`): в ленту идут итоги дуэлей из `PhantomNews`.
- **Олимпиада малого мира** (`0025-olympiad-small-world`, генератор c4-interlude `tools/l2hub_olympiad_small.py`). Матч начинается, когда записались двое (было 20). Герои — каждую неделю: `OlympiadPeriod = WEEK`. Ключа `OlympiadUseCustomPeriodSettings` у Classic и Essence нет, `Olympiad.java` читает период прямо. Соревнования каждый день (`OlympiadCompetitionDays = 1,2,3,4,5,6,7`, у Mobius было `6,7`, только пятница и суббота) с 20:00 на 4 часа. Охотников на олимпиаду ведёт ядро (`HunterOlympiad`, с 76-го уровня). В развёрнутый сервер положен `Olympiad.ini` из сборки, копия старого — `data/backups/config/<id>/2026-10-22_18-55-07/`. Новые значения действуют со следующего периода: конец текущего записан в базе. «Мир сейчас» (`0022`) пересобран из эталона c4-interlude: в `.world` и `L2HubWorld.json` есть раздел олимпиады.
- **Эпики малого мира** (`0028-epics-small-world`, генератор c4-interlude `tools/l2hub_epics_small.py`, разбор — `docs/INTERLUDE.md`). У Classic и Essence эпиков шесть: Королева муравьёв, Ядро, Орфен, Баюм, Закен, Антарас. Интервал респауна и случайная добавка — 25 % официальных (12 ключей), Антарас после первого входа ждёт 5 минут. Белета у этих хроник нет. Выключатель — «Эпики малого мира» («Мир»), «Хардкор» его выключает. Новый интервал — со следующей смерти босса. Проверено стартом обеих: 6 эпиков загружены, 17 из 17, `error0.log` пуст.
- **Фринтеза** у Classic и Essence — инстанс 205 (`LastImperialTomb.xml`). Вход — только лидеру командного канала: у Classic канал от 27 человек, у Essence от 2. В малом мире не войти, а генератор c4-interlude правит только старую Фринтезу Interlude и Epilogue. `0055-frintezza-small-world` вешает послабление на тот же выключатель: при `SmallWorldEpics = True` скрипт на старте правит условия шаблона. Командный канал и его лидер не нужны, хватает пати от двух человек (с напарником `.buddy` — уже двое). Уровень (у Classic 70+, у Essence 80+), дистанция и откат по средам — как у Mobius. В логе гейма — `LastImperialTomb: SmallWorldEpics — вход пати от 2 человек, без командного канала.` (проверено стартом обеих).
- **Язык текстов L2Hub** (генератор c4-interlude `tools/l2hub_lang.py`, разбор — `docs/INTERLUDE.md`, «Язык наших текстов»). При `L2HubLanguage = en` в `Custom/L2HubWorld.ini` «Мир сейчас», «Летопись», эльпи, суперчемпион и самопроверка идут по-английски; лаунчер пишет ключ по языку своего окна. У Classic и Essence патч называется `0099-l2hub-language`, а не `0029`, как у Interlude. Причина — свои `0050`–`0055`: `0052` трогает самопроверку, и патч под номером `0029` ложился бы раньше `0052` с контекстом после неё — сборка падала. Последним номером он заворачивает в `tr()` и строки `005x`. Генератор сам берёт патч хроники под её номером (`tools/l2hub_lang.py <id> --write`), с 2026-10-24 — и объявления сезонных событий (`LongTimeEvent`): 6 файлов, 155 строк, без перевода 0. В развёрнутых `L2HubWorld.ini` — `L2HubLanguage = ru`.
- **Окна L2Hub по языку** (`0030-l2hub-lang-html`, генератор c4-interlude `tools/l2hub_lang_html.py`, 2026-10-24). Наши русские окна стояли на месте английских — при `en` игрок всё равно видел русские. Теперь русское окно моба по Shift+клику (`0014`, 4 окна `NpcView`) лежит в `data/lang/ru/…`, на месте — английский оригинал Mobius. Включена штатная многоязычность Mobius (`Custom/MultilingualSupport.ini`, `MultiLangEnable = True`, `ru` в списке), язык по умолчанию — `L2HubLanguage`; у персонажа свой — `.lang`. Диалогов событий по-русски у Classic и Essence нет. Порядок слоёв: `0030` ложится до `0052` и `0099`, генераторы снимают их с копии и кладут обратно. С многоязычностью сервер грузит и переводы `data/lang/el`, и у Mobius Classic и Essence на них падал `NpcStringId` (NPE в `error0.log`: конструктор не заводит пустой список переводов, а у `SystemMessageId` заводит) — одна строка в своём патче `0056-npcstring-multilang` (2026-10-24).
- **Битва с Балоком** (только Essence, `GrandBoss.ini`: `BalokTime = 20:30`, `BalokPointsPerMonster = 10`) — массовое событие на 30 минут каждый день. Этапы — от 250 000 и 800 000 общих очков, то есть 25 и 80 тысяч убитых мобов, в малом мире дальше первого этапа не уйти: в конце просто встаёт обычный Балок. Оставлено как у Mobius. Если автор захочет этапы, можно поднять `BalokPointsPerMonster`, например до 2500.
- **Город эльпи в «Летописи».** Запасной путь эталона ищет эльпи в `SpawnTable`, но `Quest.addSpawn` спавн туда не кладёт: у Classic и Essence город в записи оставался пустым. Заменено на `World.getNpc(900100)` (замена в `ref_adapt.py`, c4-interlude предупреждена).
- **Проверено вживую 2026-10-22 без клиента.** Essence в «Хардкоре» (5 минут): «Проверить всё» зелёное, самопроверка 17 из 17 (баффер и GM-шоп «выключен…, в мире 0», бафы на 3 часа и чемпионы «выключены»), в логе `L2HubTown: TownBuffer is turned off`, `error0.log` пуст, после прогона 7 конфигов вернулись из снимка. Classic с эльпи раз в 2 минуты: 3 нашествия, у каждого в «Летописи» город и точка (Dion Castle Town, Hunters Village); 17 из 17, `error0.log` пуст; тестовая лента — в Корзине. Олимпиада в `L2HubWorld.json` — окно 20:00–00:00, дворян 0, записавшихся 0.

## Родное Classic и Essence на малом мире (2026-10-22)

Здесь не моды L2Hub, а собственные системы хроник. Сверено по датапаку и коду Mobius: что есть, что выключено или не работает, когда сервер поднят не круглые сутки, и что поправлено. Выключатели — в окне, «Настройки сервера» → «Моды и ивенты», группа «Родное». Все три режима сервера их включают: на официале эти системы есть.

- **Сбросы дня, недели и месяца** (`DailyResetManager`, в 6:30) рассчитаны на сервер, который работает круглые сутки. На старте Mobius догонял только сегодняшний сброс и только после 6:30: поднял сервер ночью — вчерашний пропадал. Недельный сброс (среда) и месячный (1-е число) шли, только если сброс пришёлся на сам этот день. Недельный — это Sayha's Grace на максимум, недельные временные зоны, недельные лимиты магазина L-Coin; месячный — месячные лимиты магазина, у Essence ещё L2 Pass. Sayha's Grace за день (+25 %) начислялась один раз, сколько бы дней сервер ни стоял. `0053-daily-reset-catchup` (своя правка ядра): на старте догоняется последний пропущенный сброс, среда и 1-е число — если их день был между прошлым сбросом и сейчас, Sayha's Grace — за каждый день простоя (больше четырёх не нужно: 4 × 25 % — полная).
- **Награды за вход** (окно посещения, `AttendanceRewards.xml`). У Classic — набор рыбака, Soulshot Ticket, Plain Life Stone, Improved Scroll of Resurrection и т. п.; у Essence — XP Growth Scroll, Soulshot Ticket, Special HP Recovery Potion, Silver Strider Rider Scroll, наборы заточки. У Mobius выключены (`EnableAttendanceRewards = False`). С `0054` включены, выключатель «Награды за вход». Клиенту Classic нужен `UseVIPAttendanceClassic=true` в `L2Hub.ini` — он там уже есть. У Essence такого ключа нет, окно посещения — в меню клиента (проверить клиентом).
- **L-Coin и магазин L-Coin** (`LimitShop.xml`). В дропе мобов L-Coin нет ни у одной хроники, единственный источник в малом мире — суперчемпион L2Hub (30 монет). А в магазине важное: у Essence — купон смены класса, «My Teleport Book», камень времени Alligator Island; у Classic — расширение инвентаря, браслеты талисманов и агатионов, камни эволюции духов. С `0054` включён `LCoinDropEnable`: мобы от 40-го уровня роняют 1–5 монет с шансом 15 %. Выключатель — «L-Coin с мобов». На официале Essence есть дневной лимит (160 до 40-го уровня, 300 с 41-го — патчноут 4game 2019-10-02), у Mobius лимита нет.
- **L2 Pass и волшебная лампа** (только Essence; `HuntPass.ini`, `MagicLamp.ini`) — у Mobius выключены, с `0054` включены. L2 Pass даёт награды за охоту по сезону (сезон кончается 16-го числа, премиум — 3600 L-Coin). Лампа копит опыт охоты и дарит бонус.
- **Задания дня** (`DailyMission.xml`). У Classic 96: охота 60, уровни 33, плюс вход в выходные, вступление в клан и осада. У Essence 76: уровни 36, охота 22, духи 8, олимпиада 6, хна 2, плюс клан и выходные. В одиночку не выполнить только осаду у Classic. Олимпиада — с `0025`, клан — свой. Правок нет.
- **Временные зоны** (`TimedHuntingZoneData.xml`). У Classic одна — Ancient Pirates' Tomb (78+). У Essence 14. Дневные: Primeval Isle, Alligator Island, Tower of Elmoreden, Aztacan Temple, Ancient Ruins, Training Zone, Transcendent Instance Zone. Недельные: Forgotten Garden, Antharas Lair, Frost Lord's Castle, Tower of Insolence, Kelbim's Fortress, Pagan Temple, Celestial Tower, Ironheart Fortress. Время в них возвращают дневной и недельный сброс, поэтому без `0053` недельные зоны у одиночки обновлялись бы редко.
- **Питомцы Essence**: у менеджеров питомцев (Cooper, Graman, Pazz, в Адене — Caro) «Necklace and Guardian Contract Pack» за 5 млн адены; навыки питомцев — за книги и адену (`PetAcquireList.xml`). Доступно и в малом мире, правок нет.
- **Не трогаю**: тренировочный лагерь (`TrainingCamp.ini`, у Mobius выключен и только для премиума), Prime Shop и VIP (на донат-валюту, источника нет), мировая биржа (`WorldExchange`, торговать в малом мире не с кем — охотники ей не пользуются).

## Фантомы (2026-10-21)

Напарники (`.buddy`), охотники, фейки с мозгами и доска Alt+B — тот же модуль `phantoms/`, что у H5, Interlude и Epilogue (`phantoms/README.md`).

- **Ядро** (`phantoms/core`, с 0.4.64) зовёт API, которое у Classic и Essence другое, через 21 новый метод `PhantomCompat` (инстанс — объект, карма — репутация, нет зоны города, другая модель целей скиллов и т. д.; список — `phantoms/README.md`). У H5, Interlude и Epilogue в этих методах дословно прежний код ядра.
- **Хроника** — `phantoms/classic_tales/`: `base.json` (архив Mobius 2026-10-20), свой `PhantomCompat`, данные генератора, `glue.patch`.
- **Данные** — `tools/fake_players.py` на датапаке и геодате Classic: 3 фейка, 2 напарника, 28 охотников, 684 спота охоты; снаряжение по грейдам от NG до S; камаэли заменены классами своего времени (`NO_KAMAEL`). Генератор понимает датапак Classic (`toLevel`, один `Sets.xml` на все сеты, вложенные `<item>` в коробках, формат спавнов), а зарядки берёт без грейда (`Soulshot` 91927, `Blessed Spiritshot` 91930) — их продают торговцы, и подходят они к любому оружию. Вывод для H5, Interlude и Epilogue — тот же байт в байт.
- **Врезки** — `ct26_highfive/glue.patch` на дереве Classic: из 21 файла Mobius не легли 23 куска в 13 файлах (другие имена и порядок в `MasterHandler`, чатах, `ConfigLoader`, `Party`, `RequestJoinParty`, `AutoPlayTaskManager` и т. д.), доперенесены вручную; итог — `classic_tales/glue.patch`, 21 файл, сборка с нуля — байт в байт.
- **Доска Alt+B** (`0012`) вернулась из `pending/`.
- **Конфиги**, которые меняют врезки и доска (`AdminCommands.xml`, `Custom/FakePlayers.ini`, `Custom/CommunityBoard.ini`), в развёрнутом сервере были чистым Mobius и заменены собранными; копии — `data/backups/config/classic_tales/2026-10-21_21-43-52/` и `…_21-44-10/`.
- **Мозги** — раздел `brain` в `profiles/classic_tales.json` (порт 8771, ответы кириллицей: русский ввод в клиенте есть); `python -m l2hub.brain classic_tales --test …` отвечает.
- **Баффер**: в списке Mobius был баф 1352 (Elemental Protection), которого в данных Classic нет, — `SkillData` писал предупреждение со стеком при каждом бафе охотника. `0004` теперь убирает бафы без скилла.
- **Безвредно**: с включёнными фейками Mobius начинает писать `Requested spawn for non-existing NPC: 22159… in file: OrcFortress.xml` — в датапаке Classic эти монстры Крепости орков не описаны (без фейков Mobius такие предупреждения молчит из-за ошибки в условии). Как и LogisticOfficer, это недоделка Mobius.
- **Проверено** 2026-10-21 стартом и 3–4 минутами жизни мира: охотники заходят в стартовые деревни, идут на споты, берут уровни, зовут друг друга в пати; `error0.log` пуст.
- **Зарядки**: охотники берут зарядки без грейда, как продают торговцы Classic и Essence (2026-10-21, `PhantomCompat.gradelessShot`: 91927 Soulshot, 91930 Blessed Spiritshot). До того брали по таблице CT (1835, 1463…, 3947…): у Classic такие предметы есть и работают, грейд зарядок Mobius не проверяет.

## Стратег: BUY, SELL и PARTY с именем (2026-10-24)

Ядро — epilogue-h5 (`docs/PHANTOMS.md`, 4.3, «Протокол v1: `BUY`, `SELL` и `PARTY` с именем»), мозги — `docs/MIND.md`. Своё у Classic и Essence — только `PhantomCompat` (`phantoms/classic_tales/java/…`, `phantoms/ess_crow/java/…`). Врезки не понадобились: `phantoms_glue.py --check` — 22 файла Mobius, совпадают.
- `askJoinParty` — окно «вступить в пати?» живому игроку заявкой `PartyRequest`, как `RequestJoinParty` Classic: пати создаётся сразу и ждёт ответа 30 с, а `RequestAnswerJoinParty` видит только эту заявку. Схема H5 с `setActiveRequester` тут собралась бы, но «Да» игрока ничего бы не делало.
  - Не зовёт, если у кого-то из двоих уже идёт заявка. Не зовёт и тогда, когда игрок в настройках клиента не принимает приглашения от чужих, а охотник ему не друг и не из клана (как `checkInviteByIgnoredSettings`). Ядро тогда говорит охотнику «он сейчас занят другим запросом».
- `partyMax` — `AltPartyMaxMembers` (9).
- `merchantNpcIds`, `merchantGoods` — торговцы города и цены с налогом, как `RequestBuyItem` Classic (налог замка `TaxType.BUY` и базовый налог товара).

Что продают:
- Торговцы Classic продают шмот только NG и D, C и выше у NPC нет. Поэтому с 40-го уровня `BUY` идёт только через GM-шоп (`TownGmShop`). Без него у охотника в C варианта `BUY` нет и стоит `"buy_empty": true`.
- Роль живого кандидата `PARTY` ядро берёт по имени класса; камаэли Classic — «мили».
- Грейды `R` и `R99` в датапаке — только колчан и костюм Death Knight, `EVENT` нет. Там, где грейд — индекс массива, ядро режет его до S84.

Проверено 2026-10-24 в тестовой папке данных (одноразовая база, без клиента), ядро 4b083a2:
- **Товары:** 14 городов, 93–94 торговца (402 списка); GM-шопов 13 при `TownGmShop = True`, 0 — при `False`.
- **MIND** (`HuntersSelfTest = MIND`). Включён через 3 минуты после старта, когда охотники уже на спотах; адену перед шагом «BUY по карману» доложила проба. Проверялся DarkLord, 9–10-й уровень:
  - отказы: `SPOT_TO` — «нет такого спота», `PARTY Нет Такого` — «нет такого рядом», `BUY Нет Такой Вещи` — «нет в списке», `SELL Squire's Sword` — «надето — сначала смени», `SELL Нет Такой Вещи` — «нет у тебя такого»;
  - `[[PARTY Sanchez]]` — ядро привело Sanchez, «DarkLord собрал пати: DarkLord, Sanchez», ведёт DarkLord;
  - `[[BUY Leather Cap; Short Leather Gloves]]` — ушёл в Dwarven Town, купил обе вещи за 1047 и 698 и надел;
  - `SELL` из сумки прошёл в прогоне на ядре 06c54e9: Iron Ore, «sells 1 for 100». В этом прогоне сумка была пуста.
- **Проба `askJoinParty`** на четырёх свободных охотниках:
  - первое приглашение — заявка у обоих, пати ждёт ответа, лидер ещё не в пати;
  - повторное — `false`;
  - при запрете приглашений от чужих — `false`, а когда тот же охотник в друзьях — `true`;
  - через 35 с таймаут снял обе заявки, лидеры не в пати.
- **R99:** на охотника надет костюм Death Knight (R99). `buyOffers` и `sellOffers` ядра считаются без ошибок, после пробы снаряжение вернулось.
- **«Выше грейда торговцев»** вживую не видели: охотники в тестовом мире 8–14-го уровня, а C начинается с 40-го. `buy_empty` в прогоне на 06c54e9 был у охотника, который в MIND уже купил всю бижу NG.
- `error0.log` пуст во всех прогонах; SEVERE — только известный LogisticOfficer.
- **Не проверено:** окно приглашения живому игроку и его ответ — нужен клиент.

## Приёмка ночных фич: что проверить в клиенте (Classic, 2026-10-22)

Всё ниже уже на сервере Classic (собран 2026-10-22 на ядре фантомов 99f820e, патчи `0015`–`0024`, `0050`, `0051`). Выключатели — в лаунчере, «Настройки сервера» → «Моды и ивенты»; ключи — в `game/config/Custom/`. GM-команды (`//…`) нужны персонажу с правами GM. У автора на Classic персонажа пока нет (2026-10-22: в базе только тестовый `Probnik` агента), права выдаются в базе (`characters.accesslevel = 100` при выключенном сервере) — после первого персонажа попросить агента (скрипт `gm_grant.py` в папке заметок). Тестовые ключи после проверок возвращены как в сборке.

1. **Окно «Мир сейчас».** `.world` в чате или Alt+B → «Мир сейчас» (кнопка под «Рейдом охотников»). Должно быть: суперчемпионы с городами, рейд-боссы и эпики (жив ли, когда встанет), сезонные события (Squash по 31.10, сбор букв, Total Recall с 01.11, Merry Squashmas с 01.12), ближайшие PvP-ивенты и эльпи. В лаунчере — «Мой мир» → «Обзор» и «Карта» (суперчемпионы, РБ, эпики).
2. **Суперчемпион** (выключатель «Суперчемпионы»). Найди ближайшего через `.world`. Должно быть: титул «Legendary Champion», красный круг, крупнее, свечение Ultimate Defense, золотые звёзды и белый круг под ногами (Magic Circle); HP x20, атака x3, опыт x20, дроп x10, награда — 30 L-Coin. При появлении — объявление по-русски игрокам региона. Как выглядит «золото» — `//ave_abnormal` на цели (без имени — список); не нравится — `SuperChampionEffects`.
3. **Рейд охотников на суперчемпиона** — когда охотники дорастут примерно до 24-го (лидер на 4 уровня выше цели); видно в «Летописи».
4. **Нашествие эльпи** (выключатель «Нашествие эльпи»; в 00:30, 04:30, 08:30, 12:30, 16:30, 20:30, только когда ты в игре, 10 минут). Объявление «Нашествие эльпи: <город>!» — ближайший к тебе из Aden, Dion, Gludin, Hunters Village, Oren; 100 кроликов, бить физикой; с них зелья, благословлённые свитки, Giant's Codex, кристаллы. Не ждать — `//event_start Elpies`.
5. **Охотники на сезонных событиях.** Squash: Puss the Cat (Event Manager) в 13 городах и деревнях, охотники приходят к нему и меняют нектар на семена арбуза (видно в «Моём мире» — занятие «сезонное событие»).
6. **PvP-ивенты с охотниками — по умолчанию выключены.** Включить — «PvP-ивенты по расписанию» (или режим «PvP»), перезапуск. TvT в 00:00, 03:00…, Deathmatch в 01:00, 04:00…, CtF в 02:00, 05:00… — только когда ты в игре. Менеджер — в Гиране; порог уровня `PvpMinLevel = 1`. Не ждать — `//event_start TvT` (`Deathmatch`, `CtF`). Итог — в «Летописи».
7. **Летопись** — «Мой мир» → «Летопись»: убийства РБ, суперчемпионов и эпиков (кем), итоги PvP-ивентов, нашествия эльпи, начало и конец сезонных событий, уровни 20/40/52/61/76 и профессии.
8. **«Проверить всё»** («Сервис») — пункт «Моды L2Hub»: самопроверка `0015` + `0024`, через минуту после старта.
9. **Доска Alt+B** — кнопки «Поручения дня», «Рейд охотников», «Мир сейчас»; **«Помощники»** — автопоты `.apon`, игра офлайн `.offlineplay`, маунты, трансмог.

Чистый старт 2026-10-22 17:50 (без клиента; на время прогона — эльпи на 1 минуту раз в 2 минуты, потом ключи вернул): «Проверить всё» — всё зелёное (порты на 127.0.0.1, гейм на логине как «Sieghardt», геодата 233 региона, протокол 286, `error0.log` пустой, мозги фейков, бэкап базы перед запуском; прав GM ни у кого нет); самопроверка — 17 из 17 (баффер и GM-шоп 13 из 13, мультиселлы 120, бафы 74 на максимуме и 75 на 3 часа, чемпионы, доска, `.buddy`, `.hunters`, `.daily`, `.raid`, окно моба, 27 охотников, Squash и сбор букв, 28 суперчемпионов, `.world`, эльпи, летопись); `L2HubWorld.json` — 28 суперчемпионов, 160 РБ; «Летопись» — первые записи «Нашествие эльпи» (тестовые записи потом ушли в Корзину). Город нашествия в записи у Classic и Essence — по спавнам эльпи: у их Elpies нет набора `_elpies`, на который смотрит эталон (правка — в `ref_adapt.py`).

## Приёмка — чек-лист для автора

Правило автора (2026-10-21): хроника готова, когда проверены все способы её поднять и автор в ней поиграл.

**Способы поднять:**
- [ ] Окно лаунчера: «Играть» → сервер и клиент (`system_ru`) → окно логина (соглашения Innova нет) → сервер «Sieghardt» → персонаж → мир. Аккаунт создаётся сам.
- [ ] «Стоп» из окна → мир сохранился; повторный «Играть» — персонаж на месте.
- [ ] «Проверить всё» — всё зелёное (геодата теперь есть).
- [ ] Перезапуск гейма из окна (без логина и базы).
- [ ] Друзья: пакет клиента и «Собрать L2.ini» для клиентов 4game пока не умеют (нужен `L2Hub.ini` с адресом хоста) — **не готово**, отметить как известное.
- [ ] Мастер установки (сессия оркестратора): подключение клиента Classic EU 286 и выбор языка.
- Другие сборки и клиенты уже разобраны и отброшены: клиент Melior (447 — нет такой Classic у Mobius), «Prelude of War» (снимок 2019, протокол не тот), живой клиент 4game (протокол новее любой сборки Mobius). Поднять Classic можно только Tales Untold + EU 286 — этот способ и проверяем.

**В игре:**
- [ ] Русский чат (кроме Ё); имена NPC по-русски, меню английские.
- [ ] Схем-баффер и GM-шоп у гейткиперов (13 городов, в Глудио — у постамента); бафы держатся 3 часа.
- [ ] Выключатели баффера и GM-шопа в окне («Настройки сервера», `L2HubTown.ini`): выключить, перезапустить сервер — NPC нет; включить — вернулись.
- [ ] Чемпионы: мобы 20–85 уровня с синей и красной аурой — больше HP, опыта и дропа.
- [ ] Суперчемпион (0,1 % мобов): титул «Legendary Champion», крупнее, свечение, золотые звёзды и белый круг под ногами, объявление в регионе; HP x20 — мини-РБ. Как выглядит — посмотреть и при желании поменять `SuperChampionEffects` (`//ave_abnormal` на цели).
- [ ] PvP-ивенты по расписанию: включить в окне («PvP-ивенты по расписанию»), дождаться TvT, Deathmatch или CtF у менеджера в Гиране — охотники записываются тоже.
- [ ] Shift+клик по мобу — окно по-русски (дроп, спойл, агро).
- [ ] Мобы не ходят сквозь стены (геодата).
- [ ] «Помощники» («Моды и ивенты» в окне, со следующего старта гейма): автопоты — `.apon` (сам пьёт HP/MP/CP), игра офлайн — `.offlineplay` (закрыть клиент — персонаж продолжает автоохоту), бесплатные страйдер и виверна, трансмог. «Хардкор» их выключает.
- [ ] Сезонные события: сейчас — Squash (Puss the Cat, нектар с мобов) и сбор букв; календарь — `config/Custom/L2HubEvents.ini` («Все настройки»).
- [ ] Напарник: `.buddy` — окно найма, напарник в пати бафает, лечит, ходит следом, отвечает в чате пати (мозги).
- [ ] Охотники: в стартовых деревнях и на спотах — качаются, болтают в общем чате, зовут в пати; окно охотников на доске Alt+B.
- [ ] Доска Alt+B: GM-шоп, баффер, телепорты, поиск дропа; кнопки «Поручения дня» (`.daily`), «Рейд охотников» (`.raid`) и «Мир сейчас» (`.world` — суперчемпионы, события, PvP, эльпи).
- [ ] Родное (раздел «Родное Classic и Essence на малом мире»): окно посещения — награда за сегодня; мобы от 40-го уровня изредка роняют L-Coin, магазин L-Coin продаёт за них; Sayha's Grace после пары дней без сервера прибавилась за каждый день.
- [ ] Автоохота рядом с охотниками и `/duel` на охотника — как в `docs/ESSENCE.md`, «Приёмка» (тот же `AutoPlayTaskManager`).

## Дальше

1. Автор играет: чек-лист выше («Приёмка»).
