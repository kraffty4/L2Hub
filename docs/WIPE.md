# Вайп мира хроники Mobius

Команда `python -m l2hub.cli wipe <id>` начинает мир хроники с нуля и умеет откатить это одной командой. Код — `launcher/l2hub/wipe.py`. Работает для профилей `type: mobius`: `ct26_highfive`, `epilogue`, `interlude`, `classic_tales`, `ess_crow`. У L2Solo свой вайп в окне. В окне лаунчера этой команды пока нет.

**Состояние (2026-10-31):** на настоящей MariaDB ещё не запускалось. Логика шагов, откаты при сбоях и файлы проверены на подделке базы в памяти, ротация бэкапов — настоящим `backups.rotate`. Схема всех пяти хроник сверена без базы: свежий дамп против SQL установщика, таблицы и имена столбцов совпадают. Прогон на тестовой папке — раздел «Проверка».

## Команды

```
python -m l2hub.cli wipe ct26_highfive                                 # предпросмотр, мир не меняется
python -m l2hub.cli wipe ct26_highfive --confirm ct26_highfive         # вайп
python -m l2hub.cli wipe ct26_highfive --undo                          # что вернёт откат, мир не меняется
python -m l2hub.cli wipe ct26_highfive --undo --confirm ct26_highfive  # откат последнего вайпа
python -m l2hub.cli wipe ct26_highfive --undo <время> --confirm ct26_highfive
```

Отказывает, если работают гейм, логин или мозги этой хроники, если заняты порты логина и гейма (порты общие, это может быть другая хроника), если база в профиле не совпадает с базой в конфиге логина или в базе есть процедуры, события, триггеры и представления. У Mobius их нет.

Предпросмотр — полный холостой прогон: считает строки, показывает людей и GM, заливает чистую базу рядом, сверяет столбцы и удаляет её. MariaDB, если выключена, поднимается на время команды.

## Как устроен вайп

База не чистится по списку таблиц. Рядом заливается чистая база из SQL установщика (`db_installer/sql/game`), и таблицы гейма разом подменяются одним `RENAME TABLE`. Таблицы логина (`db_installer/sql/login`) не трогаются вообще.

Почему так, а не `TRUNCATE` по списку и не залив по месту:

- Таблицу нельзя забыть. У хроник разные наборы (Interlude 101 таблица, Essence 131), а всё, чего нет в установщике, уходит без замены. Код, которому такая таблица нужна, создаст её сам.
- Статичные строки возвращаются сами: замки, форты, гварды, начальные значения эпиков и Семи Печатей. `TRUNCATE` их бы убил.
- Ошибка в SQL установщика или в бэкапе ловится до первого изменения мира. `RENAME TABLE` атомарен: мир либо старый, либо новый целиком.

`cli dbinit` для этого не годится: `builder.db_init` создаёт папку данных MariaDB целиком, со всеми хрониками, и отказывает, если она уже есть. Берётся та часть `builder.server_setup`, что заливает SQL.

Порядок шагов:

1. Чистая база рядом (`<база>__wipe_new`). Ошибка в SQL: мир не тронут, бэкап даже не снят.
2. `backups.make(p, "before-wipe")`.
3. Проверка бэкапа: файл кончается отметкой `mariadb-dump`, заливается рядом (`<база>__wipe_check`), число строк сходится по всем таблицам. Не сошлось: вайп отменён.
4. `manifest.json` в `data/backups/wipe/<id>/<время>/`: бэкап, строки по таблицам, файлы с суммами.
5. Файлы вне базы уезжают в ту же папку, подмена таблиц. Сбой подмены: файлы возвращаются, база старая.
6. Сверка строк новой базы со чистой и таблиц логина с прежними, служебные базы удаляются.

**Откат.** Сверяет, что бэкап и файлы вайпа на месте и суммы те же. Заливает дамп рядом и сверяет строки с записью вайпа. Снимает и проверяет `before-restore` с новым миром, файлы нового мира убирает в `<время вайпа>_undo_<время>`. Подменяет все таблицы, включая логин, и возвращает файлы копией, поэтому откат можно повторить. Обычный `backups.restore` здесь не подходит: таблицы, которых нет в дампе, он оставляет на месте.

Учётки при откате возвращаются такими, какими были до вайпа. Созданные после него и новые пароли остаются только в `before-restore`.

**Бэкап `before-wipe`** ротация не трогает: `backups.rotate` уносит только метку `start`.

## Подводные камни, которые учтены

- **`gameservers`.** Вернуть после вайпа только `accounts` мало: в `gameservers` лежит `hexid` гейма, парный к `game/config/hexid.txt`. Поэтому не трогается весь `sql/login`: `accounts`, `account_data`, `accounts_ipauth`, `gameservers`.
- **Одноразовые команды из `PhantomHunters.ini`.** Сервер сравнивает `HuntersResetId` и `HuntersSelfTestId` с отметками в `global_variables`. В чистой базе отметок нет, и новый мир счёл бы старые номера невыполненными: сброс `ALL` и оставшаяся в ini самопроверка сработали бы на первом тике. Отметки `L2HubHuntersResetId` и `L2HubHuntersSelfTestId` переезжают в новую базу. Это единственное, что переносится из старой базы.
- **Аккаунт фантомов.** `l2hub_phantom` остаётся в `accounts` вместе с баном на вход, охотники создаются на нём заново.
- **Запись в живую базу.** Служебные базы — три имени с суффиксами `__wipe_new`, `__wipe_check`, `__wipe_old`. `DROP DATABASE` в коде возможен только для них.

## Опись мира (High Five, 116 таблиц; у других хроник наборы похожи)

Список ниже для понимания, команда им не пользуется. Состояние мира — это то, что читается при следующем старте и меняет игру. Снимок — то, что сервер перезапишет сам.

### База

| Что | Таблицы | Что это |
|---|---|---|
| Логин, **остаётся** | `accounts`, `account_data`, `accounts_ipauth`, `gameservers` | не мир |
| Персонажи | `characters`, все `character_*`, `items`, `item_attributes`, `item_elementals`, `item_variables`, `pets`, `buffer_schemes`, `rebirth_system`, `mods_wedding`, `account_gsdata`, `account_premium`, `bbs_favorites`, `bot_reported_char_data`, `custom_mail`, `messages`, `petition_feedback`, `prime_shop_transactions`, `punishments` | состояние, в том числе охотники и напарники (их связи — в `character_variables`) |
| Кланы | `clan_data`, `clan_notices`, `clan_privs`, `clan_skills`, `clan_subpledges`, `clan_wars`, `crests`, `forums`, `posts`, `topic` | состояние |
| Осады и владения | `castle`, `castle_doorupgrade`, `castle_functions`, `castle_manor_procure`, `castle_manor_production`, `castle_trapupgrade`, `fort`, `fort_doorupgrade`, `fort_functions`, `fortsiege_clans`, `siege_clans`, `territories`, `territory_registrations`, `merchant_lease` | состояние |
| Клан-холлы | `clanhall`, `clanhall_functions`, `clanhall_siege_attackers`, `siegable_clanhall`, `siegable_hall_flagwar_attackers`, `siegable_hall_flagwar_attackers_members`, `rainbowsprings_attacker_list` | состояние |
| РБ и эпики | `raidboss_spawnlist`, `grandboss_data`, `grandboss_list`, `cursed_weapons` | состояние: кто жив, таймеры |
| Семь Печатей | `seven_signs`, `seven_signs_status`, `seven_signs_festival` | состояние |
| Олимпиада | `olympiad_data`, `olympiad_fights`, `olympiad_nobles`, `olympiad_nobles_eom`, `heroes`, `heroes_diary` | состояние |
| Рынок | `auction`, `auction_bid`, `auction_watch`, `item_auction`, `item_auction_bid`, `buylists`, `itemsonground` | состояние |
| Лавки | `character_offline_trade`, `character_offline_trade_items` | состояние |
| Прочие циклы | `lottery`, `mdt_bets`, `mdt_history`, `fishing_championship`, `underground_coliseum`, `airships` | состояние |
| Переменные | `global_variables` | состояние: возраст мира, войны и наборы кланов, активные напарники, простой мира, таймеры скриптов Mobius |
| Статика | `castle_siege_guards`, `fort_siege_guards`, `clanhall_siege_guards`, `fort_spawnlist`, `territory_spawnlist`, `random_spawn`, `random_spawn_loc`, `dimensional_rift`, `herb_droplist_groups`, `announcements` | заливается установщиком заново. Объявления, добавленные командой GM, пропадут |

Таблиц, которые создаёт модуль фантомов, нет: всё его состояние — в `character_variables` и `global_variables`. В модуле и патчах нет ни `CREATE TABLE`, ни `ALTER TABLE`.

### Файлы вне базы

Уезжают в `data/backups/wipe/<id>/<время>/files/` с тем же относительным путём.

| Файл | Что это |
|---|---|
| `data/brain/<id>.sqlite` (и `-wal`, `-shm`, `-journal`) | **состояние**: память охотников, разговоры, уроки |
| `data/brain/<id>.log` | журнал реплик, никто не читает |
| `game/log/L2HubChronicle.json` | **состояние**: летопись, единственная копия; читают окно, охотники и приветствие |
| `game/log/L2HubMarket.json` | **состояние**: память цен рынка, читается при старте |
| `data/society/<id>_forum.json` | **состояние**: ветки форума охотников |
| `data/society/<id>.json` | снимок для окна у выключенного мира |
| `game/log/L2HubHunters.json`, `L2HubWorld.json`, `L2HubOnline.json` | снимки для окна и мозгов, перезаписываются на ходу |
| `game/log/L2HubSelfTest.json`, `L2Hub*.html`, `L2HubPetitions.log`, `olympiad.csv` | отчёты и журналы старого мира |

В `game/log` берётся всё `L2Hub*`, поэтому новый файл с таким именем не потеряется.

### Что не трогается

Настройки сервера (`game/config`, `login/config`, `hexid.txt`), журналы сервера (`java*`, `error*`, `accounting*`, `GMAudit`), `data/brain/usage.json`, настройки и ключи мозгов, память других хроник, `data/friends`, клиенты и все бэкапы. По датам файлов после сборки H5 в дереве сервера вне журналов менялись только конфиги и файлы данных из наших патчей, состояния мира там нет.

## После вайпа

GM-права лежат в `characters.accesslevel` и пропадают вместе с персонажами. Новые выдаёт только автор. Настройки вроде `HuntersReset` и ключей мира остаются как были, охотники начинают с 1 уровня, как после сброса.

## Проверка

Сделано без портов (подделка базы, настоящие файлы в пустой папке данных): предпросмотр не меняет мир; отказы при работающем гейме, логине, мозгах и занятых портах, при чужом типе профиля и расхождении базы с конфигом; сбой SQL, оборванный бэкап, бэкап с другими строками и сбой подмены оставляют мир целым; вайп и откат по байтам файлов и строкам таблиц; повторный откат, второй вайп, откат по времени; подменённый файл вайпа останавливает откат; остаток `__wipe_old` останавливает новый вайп; `backups.rotate` не трогает `before-wipe`.

Настоящий SQL это не проверяет. Ждёт прогона на `L2HUB_HOME=<рабочая папка>\L2Hub_rttest` под замком портов, на копии базы:

1. `wipe ct26_highfive`: предпросмотр без ошибок, служебных баз после него нет.
2. `wipe ct26_highfive --confirm ct26_highfive`.
3. `start --no-client`: сервер встал, охотники с 1 уровня, память мозгов пустая, сброс и самопроверка не сработали сами, логин принимает гейм (`gameservers`).
4. Персонаж создаётся из клиента на старой учётке.
5. `stop`, потом `wipe ct26_highfive --undo --confirm ct26_highfive`: команда сама сверяет строки по всем таблицам с записью вайпа. Сверить ещё раз с исходной базой и файлами.
6. Несколько запусков подряд: `before-wipe` на месте.
7. То же на Interlude: другой набор таблиц.
