# Модуль фантомов

Фантомы — настоящие игроки Mobius без клиента: напарники (`.buddy`, хил, бафы, приказы), охотники (сами качаются, торгуют, собирают пати, дружат и ссорятся), плюс мост мозгов фейков (`FakePlayerBrain` → `launcher/l2hub/brain.py`). Как они устроены и как вели себя в игре — `docs/PHANTOMS.md`.

С 2026-10-20 их код живёт здесь, один на все хроники Mobius, а не в серии патчей High Five. До выноса он рос патчами `0005`–`0046`, они лежат в `patches/ct26_highfive/history/`: по их номерам и названиям удобно искать историю в `docs/PHANTOMS.md` и `CHANGELOG.md`.

## Устройство

```
phantoms/
  core/                       общий код — повторяет дерево исходников Mobius
    java/…/managers/phantom/  менеджеры фантомов (27 файлов)
    java/…/managers/world/    живой мир для одного игрока: поручения дня .daily (docs/WORLD.md)
    java/…/managers/FakePlayerBrain.java
    java/…/config/custom/Phantom*Config.java, L2HubWorldConfig.java
    dist/game/config/Custom/Phantom*.ini, L2HubWorld.ini   конфиги по умолчанию
    dist/game/data/scripts/handlers/…          //phantom, //hunters, .buddy
    dist/game/data/xsd/L2Hub*.xsd
  <id>/                       хроника (id профиля, как в servers/<id>): ct26_highfive, interlude, epilogue
    base.json                 чистые исходники Mobius, от которых считаются врезки (архив в downloads/)
    glue.patch                врезки в файлы Mobius: запуск менеджеров, конфиги, чат, пати, AutoPlay, дроп
    java/…/phantom/PhantomCompat.java   переходник: отличия API хроники для общего кода
    dist/game/data/…          данные хроники — генерирует tools/fake_players.py из её датапака и геодаты
                              (с ними — база знаний охотников для мозгов, tools/hunter_knowledge.py)
```

`PhantomCompat` — один класс с одним именем в каждой хронике; сборка кладёт в исходники копию своей. Общий код зовёт его там, где API хроник расходится:
- адена и количество предметов: у H5 `long`, у Interlude `int` — суммы общего кода `long`, Interlude приводит с отсечкой по `Integer.MAX_VALUE`;
- арбалеты и раса камаэлей — только с Kamael (`isCrossbow`, `addHomeRegions`);
- запись на олимпиаду (`olympiadRegister`, `olympiadRegistered`, 2026-10-22, `docs/PHANTOMS.md` 4.39): у H5, Classic и Essence — `OlympiadManager` с `CompetitionType`, у Interlude и Epilogue — `Olympiad.registerNoble(…, classBased)`;
- призванный слуга (`servitor`, 2026-10-22, призыватели — `docs/PHANTOMS.md` 4.37): у CT один — `getSummon()`, если это слуга, а не пет; у Classic и Essence их может быть несколько — `getAnyServitor()`;
- сосы без грейда (`gradelessShot`, 2026-10-21): у Classic и Essence торговцы продают только Soulshot 91927 и Blessed Spiritshot 91930, у H5, Epilogue и Interlude — 0, сосы по грейду оружия из таблиц `PhantomHunterOutfit`;
- системные сообщения с разными именами или без пары в клиенте («член другой пати», «олимпиада: нельзя приглашать»);
- текстуры кнопок HTML-окон: H5 — `L2UI_CT1.Button_DF`, Interlude — `L2UI_ch3.Btn1_normal`, а для широких кнопок (вкладки) — `L2UI_ch3.BigButton3`: `Btn1_normal` Interlude шире родной ширины не растягивает;
- ширина окна охотников на доске Alt+B (`BOARD_WIDTH`): H5 и Epilogue — 520, Interlude — 480 (поле доски Interlude уже);
- строка лавки скупки (`addBuyItem`, 2026-10-21): у Interlude цена и количество `int`;
- живые рейд-боссы (`raidBosses`, 2026-10-21) — для рейдов охотников: у H5, Epilogue и Interlude их держит `RaidBossSpawnManager`, у Classic и Essence — `DatabaseSpawnManager` (`getNpcs()`, отбирать `isRaid()`);
- под Classic и Essence (2026-10-21, ещё 21 метод; у CT в них дословно прежний код ядра): инстанс — объект (`canMoveToTarget`, `setInstanceId`), пати (`acceptPartyJoin`, `newParty`), `useMagic` с целью, карма — отрицательная репутация (`karma`), зоны города нет — мирная зона (`isInTown`), цель AI (`attackTarget`), цели и сила скиллов (`isAttackSkillTarget`, `isSelfTarget`, `skillPower`, `skillTargets`), заряды (`rechargeShots`), грейд и штраф (`expertiseLevel`, `refreshExpertisePenalty`), перегруз (`refreshOverloaded`), подземелья 7 печатей (`leave7sDungeon`), баф по абнормалу (`buffByAbnormal`), слушатель убийства (`addAttackableKillListener`; `PhantomRelations.onAttackableKill` теперь берёт убийцу и цель), автоподбор (`ignoredAutoPickItems`), «не та цель приглашения» (`sendWrongInviteTarget`). В новом коде ядра эти вызовы — только через `PhantomCompat`, иначе Classic и Essence не соберутся.

Новое расхождение — новый метод во всех копиях `PhantomCompat`, а не ветвление в `core/`.

`scripts/server_build.ps1 -Id <id>`:
1. копирует `core/{java,dist}` и `<id>/{java,dist}` в исходники `servers/<id>/<сборка>/`;
2. накладывает моды `patches/<id>/*.patch`, затем `<id>/glue.patch` (уже наложенные пропускает);
3. собирает Ant и раскладывает в развёрнутый сервер jar-ы, данные (`game/data`) и конфиги: новые файлы и ключи доезжают, правки пользователя остаются (`builder.deploy_configs`, устройство — `docs/RELEASE.md`, «Что мешает сейчас»).

Хэши скопированного запоминаются в `servers/<id>/.module_manifest.json`. Если файл модуля правили в исходниках, а не здесь, сборка его затирает, но копию кладёт в `servers/<id>/.module_overwritten/<время>/` и пишет `ВНИМАНИЕ`.

## Команды игрока

- `.buddy` — окно напарников: позвать, отпустить, приказы кнопками, внизу — шпаргалка приказов словами в чате пати (`PhantomUi.commands`).
- `.hunters` — где охотники (на твоём уровне, все, в лавках) и телепорт на их спот или к лавке, с теми же запретами, что у доски; показывается в окне доски — нужна ширина таблицы. В H5 то же окно есть на Alt+B, в Interlude доски нет. GM — `//hunters`.
- `.raid` — рейд охотников на РБ (`docs/PHANTOMS.md`, 4.32): кто кого собирается бить, вступить в пати рейда, телепорт к сбору.
- `.daily` — поручения дня (`docs/WORLD.md`): три задания по уровню, награда сразу, телепорт на «Место дня».
- `.raid` и `.daily` модуль регистрирует сам при старте (`VoicedCommandHandler.registerHandler`), без врезки в `MasterHandler`.
- Новые персонажи получают макросы на первую страницу панели: «Напарник» (`.buddy`, макрос 10100) и рядом «Застрял» (`/unstuck`, 10101; мгновенный — с выключателем «Мгновенный /unstuck»). Слоты 5 и 6, у Classic и Essence — 7 и 8. Врезка в `stats/players/initialShortcuts.xml`. Уже созданным — не выдаётся (решение автора 2026-10-20).

## Как править

- **Код фантомов, конфиги, xsd** — здесь, в `core/`. Потом `server_build.ps1`.
- **Данные хроники** — не руками: таблицы `PERSONAS` и `COMPANIONS` в `tools/fake_players.py`, затем перегенерация прямо сюда (команда — в AGENTS.md). Генератор детерминирован.
- **Врезки в файлы Mobius** — правка в дереве `servers/<id>/<сборка>/`, затем `python -X utf8 tools/phantoms_glue.py <id>`. Инструмент пересоберёт `glue.patch` от «чистый Mobius + моды» до дерева и проверит сборку с нуля байт в байт. `--check` — только сверить. Если файл модуля в дереве разошёлся с `phantoms/` или в дереве появился новый файл вне модуля и патчей, инструмент остановится и назовёт его.
- Патчи держат переводы строк файлов: CRLF-файлы — CRLF-строки (LF-патч переписал бы CRLF-файл в LF целиком).

## Вынос 2026-10-20 (High Five)

- Из 44 наших файлов серии патчей 36 ушли в `core/`, 6 — в `ct26_highfive/dist/`. Доска Alt+B (`L2HubBoard.java`) и спавн городских NPC (`L2HubTownNpcs.xml`) — моды H5, остались патчами `0012` и `0003`.
- Из 45 изменённых файлов Mobius 20 — врезки фантомов (`glue.patch`, +233/−14 строк), остальное — моды H5: `0001`, `0002`, `0003`, `0004`, `0014` — прежние, `0012-community-board` пересобран из итогового состояния доски (с окном охотников из `0024`).
- `0001-gameserver-bind-hostname` переписан в CRLF: в LF он переводил `GameServer.java` в LF целиком (до `0007`, который возвращал CRLF).
- Проверено: сборка с нуля (чистый Mobius 2026-10-16 → моды → модуль → `glue.patch`) совпадает с прежним деревом H5 байт в байт — и Python-проверкой, и самим `server_build.ps1` на временной копии (`BUILD SUCCESSFUL`; повторный прогон — всё «уже наложено»); генератор с выводом в модуль даёт те же 6 файлов байт в байт; `phantoms_glue.py --check` на H5 — совпадает.

## Interlude (2026-10-20)

- Переходник `interlude/java/…/PhantomCompat.java`; в `core/` через него пошли 17 мест (из разведки `docs/INTERLUDE.md`: 15 ошибок `javac` на копии Interlude).
- Врезки: `ct26_highfive/glue.patch` на дереве Interlude лёг на 16 файлах из 20; вручную — регистрация конфигов в `ConfigLoader` (у Interlude список кончается на `RebirthConfig`), импорт в `OfflineTraderTable`, множитель дропа охотникам в `NpcTemplate` (у Interlude там нет наших чемпионов из `0002`). Итог — `interlude/glue.patch`, те же 20 файлов Mobius, +233/−14, сборка с нуля — байт в байт.
- Данные — тот же генератор на датапаке и геодате Interlude: грейды до S (наборов S80/S84 нет), камаэли заменены классами своего времени (`NO_KAMAEL`: Krosh — Warlord → Dreadnought, Freya — Phantom Ranger → Ghost Sentinel, Zloy — Dark Avenger → Hell Knight), 1304 спота охоты. На данных H5 вывод генератора после правок — прежний байт в байт.
- Доски Alt+B (`L2HubBoard`, мод H5) в Interlude нет: окно напарника — `.buddy`, окно охотников — `//hunters` (у GM).
- Конфиги, которые меняют врезки (`AdminCommands.xml`, `Custom/FakePlayers.ini`), в развёрнутом сервере совпадали с архивом Mobius и заменены собранными; копии — `data/backups/config/interlude/2026-10-20_18-26-16/`.

## Gracia Epilogue (2026-10-20)

- Переходник — копия H5: для нашего кода API Epilogue совпадает с High Five (адена `long`, камаэли, арбалеты, те же системные сообщения, текстуры `L2UI_CT1` в клиенте есть) — сборка без единой ошибки.
- Врезки: на дереве Epilogue из 21 файла врезок H5 не легли те же три места, что у Interlude (`ConfigLoader` — список кончается на `RebirthConfig`, импорт в `OfflineTraderTable`, `NpcTemplate` без наших чемпионов); итог — `epilogue/glue.patch`, +240/−14, сборка с нуля — байт в байт.
- Данные — генератор на датапаке Epilogue и геодате H5: грейды до S84, камаэли свои (без `NO_KAMAEL`), 1466 спотов.

## Classic и Essence (2026-10-21)

- Ядро: 21 новый метод `PhantomCompat` (список — выше), 58 вызовов в `core/` переписаны на него; копии CT получили дословно прежний код. Новый код ядра с этими вызовами — только через `PhantomCompat`.
- Переходники `classic_tales/` и `ess_crow/` — одинаковые, кроме сообщения 152 (у Essence — `THE_TARGET_CANNOT_BE_INVITED`).
- Врезки: `ct26_highfive/glue.patch` на деревьях Classic и Essence — не легли 23 куска в 13 файлах (`MasterHandler`, чаты, `initialShortcuts.xml`, `GameServer`, `ConfigLoader`, `Creature`, `NpcTemplate`, `Party`, `RequestJoinParty`, `AutoPlayTaskManager`), доперенесены; итог — по 21 файлу Mobius, сборка с нуля — байт в байт. С 2026-10-22 — 22: дуэль с охотником (`RequestDuelStart`, как у H5); заодно в `AutoPlayTaskManager` подбор не трогает чужой защищённый дроп (`DropProtection`), иначе автоохота стояла у чужого лута (у Classic и Essence `AutoLoot` выключен, дроп охотников лежит на земле).
- Данные — генератор на датапаке и геодате хроники: грейды NG–S, `NO_KAMAEL`, зарядки без грейда (91927, 91930); 684 спота у Classic, 934 у Essence. Генератор: `toLevel`, `Sets.xml`, вложенные `<item>` в коробках (только для формата `Sets.xml`, у CT — прежнее поведение), формат спавнов; вывод H5, Interlude и Epilogue — байт в байт.
- Доска Alt+B — мод `0012` каждой хроники.

## Новая хроника

1. Хроника собрана и запускается без фантомов (`servers/<id>`, профиль, `server_build.ps1`, `server_setup.ps1`).
2. `phantoms/<id>/base.json` — архив чистых исходников в `downloads/` и имя сборки.
3. Данные: `tools/fake_players.py` с датапаком и геодатой хроники → `phantoms/<id>/dist/game/data` (таблицы — по расам и классам хроники).
4. Отличия API хроники от H5 (типы адены, расы, оружие, имена системных сообщений, текстуры) — своя копия `PhantomCompat` в `phantoms/<id>/java/…/phantom/`, а не копия `core/`. Что не компилируется — покажет `server_build.ps1`.
5. Врезки: наложить `ct26_highfive/glue.patch` как образец (`git apply --reject`), доправить отказы в дереве, `tools/phantoms_glue.py <id>`.
6. `server_build.ps1 -Id <id>`, старт, сценарии проверки из `docs/PHANTOMS.md`.
