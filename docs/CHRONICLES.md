# Карта хроник

Чем закрывать каждую хронику. Список сборок Mobius взят из GitLab API `MobiusDevelopment/L2J_Mobius`, ветка `master`, 2026-10-16: 37 сборок.

Статусы:

- **есть** — сервер рабочий, клиент установлен;
- **сборка** — у Mobius есть сборка, клиента пока нет;
- **нет сборки** — у Mobius нет, другие эмуляторы не искали.

Клиент сейчас установлен только у Gracia Final (`<игры>\Lineage II`).

## Chaotic Chronicle

| Хроника | Сервер | Статус |
|---|---|---|
| Prelude | — | нет сборки |
| C1 Harbingers of War | `L2J_Mobius_C1_HarbingersOfWar` | сборка |
| C2 Age of Splendor | — | нет сборки |
| C3 Rise of Darkness | — | нет сборки |
| C4 Scions of Destiny | [L2Solo](https://github.com/pmbstyle/L2Solo) (Node.js, Apache-2.0; у Mobius есть и `L2J_Mobius_C4_ScionsOfDestiny`) | профиль `c4` (2026-10-20): L2Solo как есть в `servers/c4`, сервер из лаунчера поднимается и гасится с сохранением мира; клиент C4 протокола 656 и client mod осмотрены и стоят в `<игры>\Lineage2-C4`, боты на DeepSeek; ждёт входа автора в мир — `docs/L2SOLO.md` |
| C5 Oath of Blood | — | нет сборки |
| Interlude | `L2J_Mobius_CT_0_Interlude` | профиль `interlude` (2026-10-20): свежий `master`, протокол 746, геодата — L2J Geodata (ветка `Interlude`), клиент `<игры>\Lineage2-Interlude` с русским чатом, вход проверен; фантомы (напарники, охотники, мозги фейков) — через общий модуль `phantoms/`, моды H5 — `patches/interlude/` (2026-10-20) — `docs/INTERLUDE.md`. Источник идей — [L2 Living Worlds](https://github.com/sateriok1/L2-Living-Worlds) (форк Mobius Interlude, GPLv3): фантомы без клиента, пати по крику LFM/LFP, лавки фейков, LLM-мозг |

## Chaotic Throne

| Хроника | Сервер | Статус |
|---|---|---|
| The Kamael | — | нет сборки |
| Hellbound | — | нет сборки |
| Gracia Part 1 | — | нет сборки |
| Gracia Part 2 | — | нет сборки |
| Gracia Final | La2offline 4.0 (L2J 2.0.6, 2009) | **есть** |
| Gracia Epilogue | `L2J_Mobius_CT_2.4_Epilogue` | профиль `epilogue` (2026-10-20): свежий `master`, протокол 152, геодата H5, клиент `<игры>\Lineage2-Epilogue` (`system_ru` для русского ввода), пробный старт без клиента — чисто; фантомы — через `phantoms/`, моды H5 — `patches/epilogue/`, вход автора проверен — `docs/EPILOGUE.md` |
| Freya | — | нет сборки |
| High Five | `L2J_Mobius_CT_2.6_HighFive` | **есть** (2026-10-16): сервер с геодатой, клиент `<игры>\LA2 H5` с системой R.C-TEAM Rev 273; протоколы 267, 268, 271, 273 |

## Goddess of Destruction

Последнее, во что играл автор. У Mobius нет ни одной главы.

| Хроника | Сервер | Статус |
|---|---|---|
| Awakening | — | нет сборки |
| Harmony | — | нет сборки |
| Tauti | — | нет сборки |
| Glory Days | — | нет сборки |
| Lindvior | — | нет сборки |
| Valiance (Epeisodion) | — | нет сборки |

## Основная линия после GoD

Названия и номера — как у папок Mobius. Обновления, которых у Mobius нет, здесь не перечислены.

| Хроника | Сервер | Статус |
|---|---|---|
| Ertheia | `L2J_Mobius_01.0_Ertheia` | сборка |
| Underground | `L2J_Mobius_02.5_Underground` | сборка |
| Helios | `L2J_Mobius_03.0_Helios` | сборка |
| Grand Crusade | `L2J_Mobius_04.0_GrandCrusade` | сборка |
| Salvation | `L2J_Mobius_05.0_Salvation` | сборка |
| Fafurion | `L2J_Mobius_06.0_Fafurion` | сборка |
| Prelude of War | `L2J_Mobius_07.0_PreludeOfWar` | сборка |
| Homunculus | `L2J_Mobius_08.2_Homunculus` | сборка |
| Return of the Queen Ant | `L2J_Mobius_09.2_ReturnOfTheQueenAnt` | сборка |
| Master Class | `L2J_Mobius_10.3_MasterClass` | сборка |
| Shinemaker | `L2J_Mobius_11.3_Shinemaker` | сборка |
| Superion | `L2J_Mobius_12.3_Superion` | сборка |
| Orc Village | `L2J_Mobius_13.1_OrcVillage` | сборка |
| Wolf Waker | `L2J_Mobius_13.2_WolfWaker` | сборка |
| Samurai Crow | `L2J_Mobius_14.1_SamuraiCrow` | сборка |

## Classic

| Хроника | Сервер | Статус |
|---|---|---|
| Classic 1.0 | `L2J_Mobius_Classic_1.0` | сборка |
| Age of Splendor | `L2J_Mobius_Classic_1.5_AgeOfSplendor` | сборка |
| Saviors | `L2J_Mobius_Classic_2.0_Saviors` | сборка |
| Zaken | `L2J_Mobius_Classic_2.5_Zaken` | сборка |
| Antharas | `L2J_Mobius_Classic_2.7_Antharas` | сборка |
| Secret of Empire | `L2J_Mobius_Classic_2.9_SecretOfEmpire` | сборка |
| Saviors (2.9.5) | `L2J_Mobius_Classic_2.9.5_Saviors` | сборка |
| The Kamael | `L2J_Mobius_Classic_3.0_TheKamael` | сборка |
| Tales Untold | `L2J_Mobius_Classic_3.5_TalesUntold` | профиль `classic_tales` (2026-10-20): сервер собран, база `l2jmobiusclassic`, протокол 286; единый клиент 4game EU 286 — `<игры>\Lineage2-Classic`, штатная система через открытый ini (`L2.exe -INI=`) — `docs/CLASSIC.md` |

## Essence

| Хроника | Сервер | Статус |
|---|---|---|
| Dwelling of Spirits | `L2J_Mobius_Essence_04.2_DwellingOfSpirits` | сборка |
| Frost Lord | `L2J_Mobius_Essence_05.2_FrostLord` | сборка |
| Crusader | `L2J_Mobius_Essence_06.3_Crusader` | сборка |
| Seven Signs | `L2J_Mobius_Essence_07.3_SevenSigns` | сборка |
| Guardians | `L2J_Mobius_Essence_08.3_Guardians` | сборка |
| Warg | `L2J_Mobius_Essence_09.1_Warg` | сборка |
| Rose Vain | `L2J_Mobius_Essence_09.2_RoseVain` | сборка |
| Samurai Crow | `L2J_Mobius_Essence_10.1_SamuraiCrow` | профиль `ess_crow` (2026-10-20): сервер собран, база `l2jmobiusessence`, протокол 542; клиент Essence EU 542 (4game) — `<игры>\Lineage2-Essence`, штатная система через открытый ini (`L2.exe -INI=`), автор зашёл в мир 2026-10-20 — `docs/ESSENCE.md` |

Протоколы восьми сборок Essence (`AllowedProtocolRevisions`, 2026-10-20): 286, 311, 388, 447, 502, 507, 520, 542 — по порядку таблицы. Номера общие с основной веткой той же сборки движка (Homunculus 286, Return of the Queen Ant 311, Master Class 388, Shinemaker 447, Superion 502).

## Популярность и список хроник для 1.0

Решение автора 2026-10-18: L2Hub 1.0 — это семь хроник, на которые реально приходят игроки, а не все 53. Проходные хроники вроде Gracia Part 1 и 2 (есть Final и Epilogue) в 1.0 не входят.

| | Хроника | Сборка | Статус |
|---|---|---|---|
| 1 | Interlude | `L2J_Mobius_CT_0_Interlude` | **готова** (0.4, 2026-10-20): фантомы и все моды H5 — `docs/INTERLUDE.md` |
| 2 | High Five | `L2J_Mobius_CT_2.6_HighFive` | **готова** (0.1) |
| 3 | Gracia Epilogue | `L2J_Mobius_CT_2.4_Epilogue` | **готова** (0.3, 2026-10-20): фантомы и все моды H5 — `docs/EPILOGUE.md` |
| 4 | Gracia Final | La2offline 4.0 | **готова** (0.2), без наших модов: у La2offline нет исходников |
| 5 | C4 | [L2Solo](https://github.com/pmbstyle/L2Solo) (Node.js, `docs/L2SOLO.md`) | подключена через L2Solo (2026-10-20), ждёт входа автора в мир |
| 6 | Classic | `L2J_Mobius_Classic_3.5_TalesUntold` | играбельна (пробный вход агента 2026-10-21, русский ввод — `system_ru`), без наших модов и фантомов (`docs/CLASSIC.md`) |
| 7 | Essence | `L2J_Mobius_Essence_10.1_SamuraiCrow` | играбельна (вход автора 2026-10-20), интерфейс по-русски (`system_ru`), без наших модов и фантомов (`docs/ESSENCE.md`) |

Готова — значит, играбельна из L2Hub и в ней есть наши моды, которые технически можно перенести. Версии — `CHANGELOG.md`.

### Статистика (2026-10-18)

[L2TOP](https://l2top.ru/) — 5382 сервера за всю историю рейтинга. 4975 из них помечены одной хроникой, остальные — «комплексы» на несколько хроник:

| Хроника | Серверов | Доля помеченных |
|---|---|---|
| Interlude | 2561 | 51 % |
| High Five | 1071 | 22 % |
| Gracia Epilogue | 255 | 5,1 % |
| Goddess of Destruction | 190 | 3,8 % |
| C4 | 162 | 3,3 % |
| Classic | 145 | 2,9 % |
| Gracia Final | 111 | 2,2 % |
| Прочие | 98 | 2,0 % |
| Freya | 77 | 1,5 % |
| Gracia (Part 1/2) | 43 | 0,9 % |
| C1 | 43 | 0,9 % |
| C3 | 38 | 0,8 % |
| C5 | 37 | 0,7 % |
| Hellbound | 37 | 0,7 % |
| C2 | 34 | 0,7 % |
| Kamael | 33 | 0,7 % |
| Essence | 27 | 0,5 % |
| The Final Frontier | 13 | 0,3 % |

[L2ELO](https://l2elo.com/) — новые серверы за последние 90 дней, всего 257:

| Хроника | Открытий |
|---|---|
| Interlude | 165 |
| High Five | 45 |
| Essence | 20 |
| Epilogue | 9 |
| C4 | 6 |

Отдельные открытия бывают и у GoD, C3, Classic и Throne. Essence в архиве L2TOP почти нет (ветка молодая), но среди свежих открытий она третья. Goddess of Destruction заметна, но сборки Mobius под неё нет, это задача фазы 6.


## Итог

| Эпоха | Хроник | Mobius | Другое | Дыры |
|---|---|---|---|---|
| Старая: Prelude → High Five | 15 | 5 (C1, C4, Interlude, Epilogue, High Five) | Gracia Final — La2offline | 9 |
| Goddess of Destruction | 6 | 0 | — | 6 |
| Новая: Ertheia и дальше, Classic, Essence | 32 | 32 | — | — |

- Из 37 сборок Mobius 32 относятся к новой эпохе. Старую эпоху Mobius закрывает на 5 хроник из 15.
- Строки новой эпохи взяты из списка самого Mobius, поэтому полное покрытие там получилось по построению. Официальные обновления, которых у Mobius нет, в карту не внесены.
- Дыры: Prelude, C2, C3, C5, The Kamael, Hellbound, Gracia Part 1 и 2, Freya и все шесть глав Goddess of Destruction. Для них нужно искать другие эмуляторы (фаза 6).
