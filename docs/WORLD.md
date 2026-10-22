# Живой мир для одного игрока

Цель автора — серверы, где одному игроку не скучно. Фантомы (`docs/PHANTOMS.md`) дают живых соседей; здесь — то, что даёт игроку поводы и цели: задания, события, вызовы. Код — общий модуль L2Hub для всех хроник Mobius: `phantoms/core/java/…/managers/world/`, конфиг — `config/Custom/L2HubWorld.ini`. Ставится вместе с фантомами (`scripts/server_build.ps1`), врезок в файлы Mobius не требует: модуль сам вешает слушатели глобальных событий и регистрирует команды при старте (`PhantomManager.onServerStart` → `WorldDaily.start`). Каждое новшество — своим переключателем в `L2HubWorld.ini`.

Сделанное:

| Раздел | Что | Переключатель | Хроники |
|---|---|---|---|
| 1 | Поручения дня `.daily` | `WorldDaily` | все с фантомами |
| 2 | Сезонные события круглый год — календарь (`0016-l2hub-event-calendar`, как у Interlude) | `Enabled` в `L2HubEvents.ini` | High Five, Epilogue, Interlude (своё расписание) |
| 3 | Кнопки «Поручения дня» и «Рейд охотников» на доске Alt+B (`0017-board-daily-raid`) | — | High Five, Epilogue |
| 4 | Выключатели баффера и GM-шопа у гейткиперов и на доске (`0018-l2hub-town-switches`) | `TownBuffer`, `TownGmShop` в `L2HubTown.ini` | High Five, Epilogue |
| 5 | Суперчемпион — третий уровень чемпионов, мини-РБ (`0020-super-champion`, c4-interlude); охотники собирают на него рейд (`docs/PHANTOMS.md`, 4.35) | `SuperChampionEnable` в `ChampionMonsters.ini`; охотники — `HunterSuperChampions` | High Five, Epilogue (и Interlude, Classic, Essence — у владельцев) |
| 6 | Нашествие эльпи по расписанию и менеджеры L2 Day в больших городах (`0021-l2day-towns-elpies`, c4-interlude) | `Elpies`, `ElpiesSchedule`, `ElpiesMinutes`, `ElpiesOnlyWithPlayers` в `L2HubEvents.ini` | High Five, Epilogue (и Interlude; у Classic и Essence — своё) |
| 7 | Окно «Мир сейчас» `.world` и кнопка «Мир» на доске Alt+B, `game/log/L2HubWorld.json` для «Моего мира» (`0022-l2hub-world-info`, c4-interlude) | — | High Five, Epilogue (и Interlude) |
| 8 | «Летопись мира» — `game/log/L2HubChronicle.json` для экрана «Летопись» лаунчера (`0023-l2hub-chronicle`, c4-interlude) | — | High Five, Epilogue (и Interlude) |
| — | Рейды охотников на РБ `.raid` — охотники сами собираются на рейд-босса, игрок может пойти с ними (`docs/PHANTOMS.md`, 4.32) | `HunterRaids` в `PhantomHunters.ini` | все с фантомами |
| — | Скупка охотников и б/у шмот на рынке Гирана (`docs/PHANTOMS.md`, 4.29) | `HunterBuyStores`, `HunterSellOldGear` | все с фантомами |
| — | Самопроверка модов на сервере, пункт «.daily и .raid» (`0015-l2hub-selftest`) | `L2HubSelfTest.ini` | High Five, Epilogue (у Interlude — без пункта) |

## 1. Поручения дня (2026-10-21)

У каждого персонажа три задания на день по его уровню. Окно — команда `.daily`.

| Задание | Что сделать (по умолчанию) | Когда бывает |
|---|---|---|
| Охота | 100 мобов не слабее своего уровня − 5 (`WorldDailyHuntKills`) | всегда |
| Место дня | 50 мобов на споте охотников по уровню: мобы на уровень−4 … +1 от своего, с 20-го — не в стартовых зонах (`WorldDailySpotKills`); в окне — кнопка «Туда»: телепорт на спот, с 40-го — за 15 000 (`WorldDailyTeleportFee`), из мирной зоны, с запретами доски | два из трёх, |
| Чемпион | убить чемпиона, синего или красного (`WorldDailyChampions`) | на день наугад, |
| Вместе с охотником | 40 мобов в пати с охотником-фантомом (`WorldDailyHunterPartyKills`) | у каждого свои |

- В пати засчитывается то, что убила пати рядом с персонажем (как делится опыт пати, 1500), в том числе охотник или напарник.
- Награда — сразу, как задание выполнено: `WorldDailyAdena` × уровень² адены (30 × 40² = 48 000 на 40-м) и `WorldDailyExpPercent` % опыта до следующего уровня (2%). Все три за день — бонус (`WorldDailyBonus`): свиток заточки брони своего грейда (с 20-го; до 20-го — ещё столько же адены).
- Новый день — в `WorldDailyResetTime` (06:30 по часам сервера). При входе в игру — напоминание «выполнено N из 3 — команда .daily»; прогресс — в чате на 25, 50 и 75%; выполнено — надпись на экране.
- Задания и прогресс — переменная персонажа `L2HubDaily` (`character_variables`), строка «дата|бонус|задания»: `2026-10-21|B0|HUNT:100:37:0:-1;SPOT:50:0:0:812;CHAMPION:1:0:0:-1`. «Место дня» хранит номер спота в `L2HubHuntSpots.xml`: после перегенерации спотов задание этого дня может указать на другой спот.
- В логе сервера: `WorldDaily: <кто> (<ур>) gets daily tasks: …`, `done <задание>`, `did all daily tasks: bonus …`, `teleports to the daily spot …`.

Проверено: `javac` модуля против jar-ов H5, Epilogue и Interlude; старт H5 и Epilogue — `WorldDaily: daily tasks on (.daily)`, самопроверка видит команду `.daily`, ошибок в логе нет. Живым персонажем в клиенте не проверялось: окно, счёт убийств, награды и телепорт — автору (`docs/PLAN.md`, «Автору проверить в игре»).

Что дальше (не сделано): кнопка на доске Alt+B (H5, Epilogue); задание на рынок (купить или продать в лавке охотника) — нужна врезка в покупку в лавке, событий у Mobius нет; задание недели на РБ.

## 2. Сезонные события круглый год (2026-10-21, `0016-l2hub-event-calendar`)

У Mobius 9 сезонных событий (`data/scripts/events`, `LongTimeEvent`: NPC в городах, дроп с мобов, обмен на награды), но их даты в `config.xml` — прошлые годы («has passed... Ignored»), и в мире не идёт ни одно. Календарь — тот же, что c4-interlude сделала для Interlude (0.4.25): `LongTimeEvent` берёт окно события из `game/config/Custom/L2HubEvents.ini` (ключ — папка события: `дд ММ-дд ММ` каждый год, `дд ММ гггг-дд ММ гггг` один раз, `Off`, пусто — как в `config.xml`), рубильник `Enabled` (его показывает окно лаунчера). Кусок `LongTimeEvent` из патча Interlude лёг на H5 и Epilogue байт в байт. По умолчанию события идут по году одно за другим, без пересечений и без перехода через Новый год; дроп — всё время окна. Первая версия (`0016-events-all-year`, правка дат в `config.xml`) заменена календарём в тот же день.

| High Five | Epilogue | Событие |
|---|---|---|
| 01.01–09.02 | 01.01–14.02 | Saving Santa |
| 10.02–21.03 | 15.02–31.03 | Valentine Event |
| 22.03–30.04 | 01.04–15.05 | L2 Day |
| 01.05–09.06 | 16.05–30.06 | Gift of Vitality |
| 10.06–19.07 | 01.07–14.08 | Watermelon Ninja |
| 20.07–28.08 | 15.08–30.09 | Heavy Medal |
| 29.08–06.10 | 01.10–14.11 | Love Your Gatekeeper |
| 07.10–15.11 | 15.11–31.12 | Master of Enchanting |
| 16.11–31.12 | — | Freya Celebration (в Epilogue её нет) |

Событие начинается само в свой день (если сервер работает — по таймеру Mobius, иначе — при старте) и кончается в последний; объявление при начале и при входе в игру — родное. Сервер, работающий через Новый год, январское событие начнёт после перезапуска. Выключить одно событие — `<событие> = Off` в `L2HubEvents.ini`, все — `Enabled = False`; новое окно — со следующего запуска сервера. Самопроверка показывает, какое идёт: «Сезонные события — загружено 9, идёт: MasterOfEnchanting до 15.11.2026».

Проверено: старт H5 2026-10-21 — `Event Master of Enchanting active till Sun Nov 15`, `Event Freya Celebration will be started at Mon Nov 16`, остальные — «has passed» (их окна в этом году прошли). Что внутри событий (награды, баланс), не менялось — это события официальных серверов.

## 3. Кнопки на доске Alt+B (2026-10-21, `0017-board-daily-raid`)

В меню доски High Five и Epilogue после «Охотники» — «Поручения дня» (`bypass voice .daily`) и «Рейд охотников» (`bypass voice .raid`): окна команд, чтобы игрок нашёл их без знания команд. Текстуры кнопок — как у соседней «Охотники» (у Epilogue — `L2UI_CT1.Button_DF`). В клиенте не проверялось.

## 4. Выключатели баффера и GM-шопа (2026-10-21, `0018-l2hub-town-switches`)

Для режимов сервера в окне лаунчера («Хардкор» — без баффера и GM-шопа) по просьбе оркестратора. `game/config/Custom/L2HubTown.ini`: `TownBuffer` — схем-баффер (NPC 50008), `TownGmShop` — GM-шоп (50010) у гейткиперов 17 городов (мод `0003`). `False` — скрипт `custom/L2HubTown` после загрузки спавнов убирает этих NPC из мира (`stopRespawn`, `deleteMe`, `SpawnTable.removeSpawn`), а баффер и GM-шоп на доске Alt+B (`L2HubBoard`) отвечают «выключен»; самопроверка считает выключенное нормой. Применяется после перезапуска гейма. Патч хронико-независимый: Interlude взял его байт в байт (c4-interlude, 0.4.58); у Classic и Essence — свой вариант на группах спавна (у H5 спавны без групп).

Проверено вживую 2026-10-21: H5 с `TownBuffer = False` — «баффер у гейткиперов убран: 17 NPC», самопроверка «выключен… в мире 0», 13 из 13; календарь — H5 Master of Enchanting, Epilogue Love Your Gatekeeper по `L2HubEvents.ini`, `error0.log` пуст.

## 5. Суперчемпион (2026-10-22, `0020-super-champion`)

Мод c4-interlude (рецепт и ключи — `docs/INTERLUDE.md`, «Суперчемпион»), на High Five и Epilogue — тем же генератором: `python -X utf8 tools/l2hub_super_champion.py ct26_highfive|epilogue --write` (наложение на копию — байт в байт, врезки поверх `0002-champion-tiers`). При появлении моба (уровни `ChampionMinLevel`–`ChampionMaxLevel`, только при `ChampionEnable`) сначала бросается `SuperChampionFrequency` (0,1 %): HP x20, атака x3, опыт и SP x20, дроп x10, титул «Legendary Champion», объявление в регионе.

Эффекты над моделью: High Five и Epilogue, в отличие от Interlude, шлют в пакете NPC и особые эффекты. У High Five — `BIG_BODY,ULTIMATE_DEFENCE,NAVIT_ADVENT` (золотое сияние Благословения Невита; `INVINCIBILITY` — золотая сфера — не взят: игрок решит, что моб неуязвим), у Epilogue — `BIG_BODY,ULTIMATE_DEFENCE` (Невита в клиенте Epilogue ещё нет). Значение по хронике — `EFFECTS_BY_ID` в генераторе. Как выглядит — глазами в клиенте, примерить: `//ave_abnormal <ЭФФЕКТ>` на цели.

В развёрнутые `ChampionMonsters.ini` H5 и Epilogue блок `SuperChampion*` дописан целиком (копии прежних — `data/backups/config/<id>/2026-10-21_23-50-56_superchampion/`), в `PhantomHunters.ini` — `HunterSuperChampions`.

## 6. Нашествие эльпи и L2 Day в больших городах (2026-10-22, `0021-l2day-towns-elpies`)

Мод c4-interlude для Interlude (рецепт — `docs/INTERLUDE.md`, «L2 Day в больших городах и Elpies по расписанию»), на High Five и Epilogue — тот же патч: Elpies.java, блок `Elpies*` в `L2HubEvents.ini` и L2Day.java легли как есть (со сдвигом строк), в `L2Day/config.xml` — те же 11 точек менеджеров (мир тот же; в Гиране — рядом с баффером и GM-шопом `0003`) и английская строка `onEnter` с большими городами (сообщения событий у H5 и Epilogue английские). Скрипты H5 и Epilogue одинаковые — патч у обеих один и тот же (собран наложением частей Interlude-патча на дерево H5, проверен наложением на исходники — байт в байт).

- **Эльпи:** 100 кроликов в городе на `ElpiesMinutes` (10) минут, город — ближайший к живому игроку из пяти, по крону `ElpiesSchedule` (`30 */4 * * *`), `ElpiesOnlyWithPlayers` — только при живом игроке. Выключатель — `Elpies`.
- **L2 Day:** менеджер события (31854) ещё и в 11 больших городах, свиток телепорта — в свой город (`TOWN_SCROLLS` по `MapRegionData`, имена городов H5 совпадают с Interlude); при спавне — проверка высоты по геодате и строка «manager in <город> gives scroll <id>». Охотникам (`docs/PHANTOMS.md`, 4.34) теперь есть к кому нести слова и после стартовых деревень.

В развёрнутые `L2HubEvents.ini` H5 и Epilogue блок `Elpies*` дописан (копии прежних — `data/backups/config/<id>/2026-10-22_01-50-16_elpies/`), после этого они байт в байт как в сборке.

## 7. Окно «Мир сейчас» (2026-10-22, `0022-l2hub-world-info`)

Мод c4-interlude (что в окне и формат JSON — `docs/INTERLUDE.md`, «Окно «Мир сейчас»»), на High Five и Epilogue — его генератором: `python -X utf8 tools/l2hub_world_info.py ct26_highfive|epilogue --write` (2 файла, наложение на копию — байт в байт; нужны `0016` и `0020`). Команда `.world` и кнопка «Мир» на доске после «Рейд»: живые суперчемпионы ближе к уровню игрока с городом, сезонные события с датами, ближайшие TvT, Deathmatch, CtF и эльпи по крону `L2HubEvents.ini`; раз в минуту — `game/log/L2HubWorld.json` для лаунчера.

## 8. Летопись мира (2026-10-22, `0023-l2hub-chronicle`)

Мод c4-interlude (рецепт — `docs/INTERLUDE.md`), на High Five и Epilogue — его генератором: `python -X utf8 tools/l2hub_chronicle.py ct26_highfive|epilogue --write` (один новый файл — скрипт `custom/L2HubChronicle`, наложение на копию — байт в байт; нужен `0020`). Пишет `game/log/L2HubChronicle.json` для экрана «Летопись» в лаунчере: убиты РБ, суперчемпион или эпик (кем — игрок, охотник, пати, охотники), эпик встал, итоги TvT, Deathmatch и CtF, начало и конец сезонного события, эльпи, профессии и уровни 20/40/52/61/76. Охотника отличает через `PhantomManager.isPhantom`, итоги PvP-ивентов читает из их скриптов без врезок. Проверено 2026-10-22: скрипт стартует на H5 и Epilogue (`error0.log` без его ошибок); на H5 за 10 минут — «Нашествие эльпи у Hunters Village», «Суперчемпион Ol Mahum Patrol (21) убит: охотники LunaTik» и итог TvT.
