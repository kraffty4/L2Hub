# C4 на других эмуляторах

Решение автора 2026-10-21: C4 станет «готовой», когда она собрана не только на L2Solo, все способы проверены и автор поиграл (`docs/PLAN.md`, «Решения»). Здесь — что ещё поднимает C4 Scions of Destiny и что из этого живое и законное. Разведка c4-interlude, 2026-10-21. Основной способ — L2Solo (`docs/L2SOLO.md`).

## Кандидаты

| Что | Язык, лицензия | Живой | Протокол | Итог |
|---|---|---|---|---|
| **L2JLisvus** — GitLab [TheDnR/l2j-lisvus](https://gitlab.com/TheDnR/l2j-lisvus), зеркало GitHub `craqpter/L2c4` | Java, GPL-3.0 | с 2011 года; последний коммит `fdc7e33a` от 2026-06-18, релиз 755 — 2025-12-27 | **656–665** | второй способ: клиент L2Solo подходит, сервер поднят у нас (ниже) |
| L2J Mobius `L2J_Mobius_C4_ScionsOfDestiny` | Java 27, GPL-3.0 | да (коммит 2026-10-20) | 660 (`AllowedProtocolRevisions`) | клиент 656 не пустит; добавить 656 в список — лотерея по пакетам. Наш `builder.py` его соберёт; моды и фантомы не перенесены |
| [NodeL2](https://github.com/NodeL2/NodeL2) | JavaScript, Apache-2.0 | пуш 2025-01 | C1–C4 | прародитель L2Solo, та же семья — отдельной проверки не стоит |
| [l2cpp](https://github.com/Chnossos/l2cpp) | C++23, без лицензии | да, ранняя стадия | 656 | без лицензии и сырой — нет |
| [l2auth](https://github.com/Ruk33/l2auth) | C | учебный | — | нет |
| `w3nder/interlude-to-scions-of-destiny` | C, Python, Go | да | — | не сервер: порт клиента Interlude под чужой сервер L2Killer, с exe-апдейтером — не берём |
| Серверы NCSoft C4 (L2Off) | — | — | — | утечка, незаконно — не берём |

## L2JLisvus: проверено 2026-10-21

- **Сборка.** `git clone --depth 1` в `downloads/l2j-lisvus` (91 МБ с `.git`). В README — Java 17, в `core/build.xml` — `source`/`target` 25. Наша JDK 27 и Ant собрали без правок: `ant -f core/build.xml` — 15 с, `ant -f datapack/build.xml` — 64 с. На выходе `core/build/core.zip` (36,6 МБ) и `datapack/build/datapack.zip` (8,6 МБ). Распакованная сборка «как у пользователя» — `<рабочая папка>\L2Hub_lisvus_c4` (80 МБ: `gameserver/`, `login/`, `sql/`, `tools/`) — для проверки «Подключить свою сборку».
- **Раскладка.** `gameserver/` и `login/`: в каждой `L2JLisvus.jar` и `libs/` (`mariadb-java-client` 3.5.7, HikariCP, jython, ecj). Конфиги — `*.properties` в `config/`, не `.ini`. Старт — `startLoginServer.bat` / `startGameServer.bat`: `java … -cp ./libs/*;L2JLisvus.jar net.sf.l2j.loginserver.L2LoginServer` (логин, `-Xmx256m`) и `net.sf.l2j.gameserver.GameServer` (гейм, `-Xmx2048m`); код выхода 2 — рестарт.
- **База.** По умолчанию — драйвер MariaDB, `jdbc:mariadb://localhost/l2jdb?useSSL=false`, `root` без пароля (`URL`, `Login`, `Password` в `LoginServer.properties` и `GameServer.properties`). Их `tools/database_installer.bat` создаёт базу и заливает `sql/*.sql` (логин — `accounts`, `gameservers`, остальное — гейм). У нас — база `l2jlisvus` в общей MariaDB: 80 файлов, 80 таблиц, 7 с.
- **Сеть.** Порты 2106, 7777, 9014 — как у всех профилей. Адреса — `LoginserverHostname`, `LoginHostname`, `GameserverHostname`, `ExternalHostname`, `InternalHostname` (по умолчанию `*` — все сети; для этого ПК — `127.0.0.1`). `AcceptNewGameServer = True` — регистрировать гейм (`hexid`) руками не надо.
- **Старт** нашей JDK 27 на копии с этими настройками: логин — «Login Server ready on 127.0.0.1:2106», гейм — «Server loaded in 11 seconds», «Registered on login as Server 1 : Bartz», ошибок нет, оба слушают только `127.0.0.1`.
- **Геодата** — формат L2J `XX_YY.l2j` (`data/geodata/geo_index.txt`, 143 региона C4), своей в сборке нет («Loaded 0 regions»). C4-геодата L2Solo (l2solo.com, 203 файла) подходит: с `GeoDataPath` на `servers/c4/data/Geodata` — «Loaded 163 regions», старт 10 с. Раздавать её не будем: пользователь берёт её у L2Solo (их `npm run geodata`) или своим источником.
- После проверки: копия с настройками убрана в Корзину, MariaDB и порты отданы. Тестовая база `l2jlisvus` (80 таблиц, пустой мир) — дамп в `<свалка>\db_l2jlisvus_2026-10-21.sql`, из MariaDB удалена.

## Вариант `c4_lisvus` у автора (2026-10-21)

Лаунчер ставит Lisvus как вид своей сборки `lisvus` (`bundles.json`, e637e70) вариантом C4 рядом с L2Solo — строка «Scions of Destiny · L2JLisvus» в списке (ae26fae). У автора поставлен из сборки `<рабочая папка>\L2Hub_lisvus_c4` вызовом `Api().install("c4", r"<рабочая папка>\L2Hub_lisvus_c4")` — то же, что «Подключить свою сборку…» на установленной C4:

- профиль `profiles/c4_lisvus.json` (`"chronicle": "c4"`, `source.imported` — папка сборки), сервер — `servers/c4_lisvus/server` (части `game`, `login`, `sql`, `tools`), база `l2hub_c4_lisvus` (80 файлов из `sql/`), установка — 14 с;
- клиент — `<игры>\Lineage2-C4` (тот же клиент L2Solo, протокол 656);
- геодата — руками: в `servers/c4_lisvus/server/game/config/GeoData.properties` `GeoDataPath = <рабочая папка>/L2Hub/servers/c4/data/Geodata` (геодата L2Solo; исходный файл — `servers/c4_lisvus/GeoData.properties.orig`). С 3d3ee2f лаунчер прописывает этот путь сам: у варианта своей геодаты нет, а у основного сервера хроники есть (у C4 — `servers/c4/data/Geodata` L2Solo); у автора ручной путь с этим совпадает;
- старт без клиента (`l2hub.cli start c4_lisvus --no-client`) — 17 с: «GeoData: Loaded 163 regions», «Registered on login as Server 1 : Bartz», «Server loaded in 13 seconds». «Проверить всё»: база, логин 2106, гейм 7777, регистрация, протокол 656 против 656–665, `ServerAddr = 127.0.0.1`, сеть только локальная, `error0.log.0` пустой — всё зелёное. Ложное «Геодата: файлов .l2j: 0» (проверка ждала строку Mobius «GeoEngine: Loaded», а у Lisvus «GeoData: Loaded», и считала файлы в `game/data/geodata`, а не по `GeoDataPath`) оркестратор исправил в 3d3ee2f: теперь «в прошлом запуске загружено регионов: 163»;
- остановка через лаунчер — SIGTERM, Lisvus сохраняет рейды, гранд-боссов и олимпиаду.

## Проверка без клиента (0.4.150, 2026-10-22)

- Старт `cli start c4_lisvus --no-client`, «Проверить всё» — всё зелёное: база, логин 2106 и гейм 7777 на 127.0.0.1, «Server 1: Bartz» на логине, геодата 163 региона (проверка ловит строку Lisvus «GeoData: Loaded» — 3d3ee2f), протокол 656 против 656–665, `error0.log.0` пустой, бэкап перед запуском.
- Протокол без клиента (скрипт шлёт первый пакет клиента): логин при подключении сам прислал пакет Init (159 байт); гейм на `ProtocolVersion` 656 и 665 ответил `KeyPacket` «принят», на 600 — «отклонён». Клиент L2Solo (656) по протоколу войдёт.
- Аккаунты: `AutoCreateAccounts=True` в `LoginServer.properties` — первый вход с любым логином создаст аккаунт. GM-персонажей нет.
- Остановка — штатная, мир сохраняется (SIGTERM, «All Raid Boss info saved», «Olympiad System: Data saved»).

## Что дальше

1. ~~Подключить Lisvus в лаунчер~~ — сделано (e637e70, ae26fae), вариант у автора поставлен (выше).
2. Первый вход автора клиентом L2Solo (протокол 656) в вариант `c4_lisvus` — проверить, что логин и вход в мир проходят, чат мода работает.
3. По желанию автора — порт наших модов (баффер, чемпионы, доска) и фантомов на Lisvus: это не Mobius, переходник `PhantomCompat` пришлось бы писать заново, как у новой хроники.
