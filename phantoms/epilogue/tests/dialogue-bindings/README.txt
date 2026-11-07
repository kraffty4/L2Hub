Совместимость готового realism-bank с Native source-port (0513).

Данные: семь полных файлов Local* сохранены байт в байт из зафиксированного
редакторского пакета06.11.548переписанных версий сцен/331отдельная строка;
остальной банк сохранён, полной литературной переписи нет.
LocalDialogueBindings.xml — отдельные runtime-метаданные из role-bindings,
edits, single-edits и opener-edits; содержимое семи банков не меняет.

Речевая личность v1 — детерминированная виртуальная male/female роль по
постоянному character objectID (для непостоянного fixture — имени).
Пол/внешность аватара и текущая пати её не определяют. Это не утверждение
о поле реального человека. Смена владельца/класса/имени при прежнем ID
не меняет речевую личность. Пользовательские записи/БД не переписываются.
По явным metadata выбираются совместимые роли сцены, самостоятельные
строки и12геройских одиночных строк. Личность не хранится в per-party кешах;
Snapshot кэширует только две voice-выборки, исходный банк остаётся целым.

Nonparty-версия не попадает в обе старые групповые копии. Отдельно написанная
party-версия того же sceneID (напримерglobal-work-077) остаётся в пати.
Ключи включают файл: роли и пол могут различаться между этими версиями.
Новые rr20261106 строки без обязательных metadata не произносятся; несовпадение
ролей/линий не превращается в случайный выбор. Проверка повторяется перед ходом.

0513 — короткие врезки к CURRENT LocalCityDialogues/LocalReplyBank/
LocalHeroDialogues и7точечных литералов PhantomChat. Требуется Root0512
и текущий source-port Local helpers. Не заменять PhantomChat полным старым
reference-файлом: 0513 рассчитан после Native gameplay-port и сохраняет
его callbacks/EOL. Неизменные LocalPartyDialogues иLocalClanDialogues не
подменяются. Четыре ID+text whitelists обновлены вместе; стараяcity-0112
ветка вопроса про музыку удалена;26уникальных старых turnID уже отсутствуют
в новом city-bank и не переназначались механически.

QA на JDK27, обычные Native server/libs без legacy-module JAR:
- компиляция current Local5+newhelper+Root PhantomChat/PhantomChatFlow/
  HumanChatFallback/CombatEventPolicy. Эти актуальные Root зависимости нужны
  для source API, в сыром205JAR ещё нет noteTrade/inPublicConversation.
- DialogueDataCheck:6709условий, реальные runtime readers читают17190reply,
  все2546city/1324party/51long/29clan/18hero сцен, metadata1384/7245/12;
- DialogueRuntimeCheck:79условий, реальный Controller/voice cache/hero safe
  иNative CreatureSay/BlockList, group channels/membership, отложенная очередь,
  прерывание человеком, потеря hero/group/eligibility, fail-closed metadata.

Для standalone-проверок в ПУСТОЙ собственной cwd положить metadata вdata/.
Скомпилировать три test-файла; DialogueContextsAgent.jar имеет manifest
Premain-Class: DialogueContextsAgent. Включать только в test JVM вместе
с ../ready-source/NoWorldAgent (JDBC/GameServer/LoginServer block).
Contexts agent подменяет только PhantomManager.getInstance иWorld.getPlayers
объектами в памяти. Controller/selection/guard/broadcast logic не переписаны;
actor properties, часы, scheduler и доставка пакетов записываются fixtures.

Data: java -javaagent:<NoWorldAgent.jar> -cp <tests;candidate;Native libs/*>
 org.l2jmobius.gameserver.managers.phantom.DialogueDataCheck <candidate game/data>
Runtime: дополнительно --enable-final-field-mutation=ALL-UNNAMED
 -javaagent:<DialogueContextsAgent.jar>, класс DialogueRuntimeCheck.

Эти test agents не входят в production и не заменяют штатный серверный
tools/selftest_run.py. Мир/БД/клиент не запускаются. Native chat route audit
проверяет подключение city/party/clan/hero ticks и humanSpoke; геройский handler
зовёт humanSpoke только после проверки прав, ban/jail и flood guard.
Полный чистый build, реальный серверный старт/пакеты/видимые игроки и итоговое
применение проверяет интегратор; эти standalone-проверки их не доказывают.
