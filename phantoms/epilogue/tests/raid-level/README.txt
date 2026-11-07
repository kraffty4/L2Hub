Кастомные уровневые штрафы рейд-боссов —0505

L2Hub: Настройки сервера -> Кастом сервера.
EpicNoLevelPenalty — эпики и связанная охрана.
RaidNoLevelPenalty — все РБ, включая эпиков и рейдовых миньонов.
Оба ключа по умолчанию False и независимы от EpicLevelAccessEnabled.
Обычный файл: config/Custom/EpicLevelAccess.ini, применяется после рестарта.
В том же разделе доступны прежний EpicLevelAccessEnabled и13порогов эпиков.
Полное снятие штрафов имеет приоритет; при выключении действует прежний допуск.

Снимаются только наказания за разницу уровня с выбранным рейдовым NPC:
Creature attack/reflection/direct skill/support/nearby skill curse;
QueenAnt.onAggro; отдельная входная EffectZone АК; grouped/ungrouped drop/spoil;
Attackable.calculateExpAndSp; отдельные5456 в Q511/Q727.
Q727 также обслуживает обычных мобов: для них прежняя проверка сохраняется.
Q511 giveRaidCurse=false не отключает его5456, поэтому принадлежность к рейду
не зависит от флага giveRaidCurse. QA larva29002 определяется и без raid/leader.

Настоящие getLevel, HP/урон/защита, навыки NPC, базовые награды, dynamic rates,
доля урона, party-cutoff, summon-penalty, квестовые входы и откаты не меняются.
Прежний proxy уровней эпиков применяется только к curse/drop и не меняет XP.
Новые флаги нейтрализуют XP levelDiff и его cutoff; getExpReward(charLevel) и
getSpReward(charLevel) получают настоящий уровень персонажа.
Неуровневые ивентовые проклятия (например Rabbits) и обычные формулы боя прежние.

Проверка без запуска мира
-----------------------
RaidLevelAccessCheck.java исполняет настоящий helper и private
Attackable.calculateExpAndSp из собранного0505. Сам метод XP/SP не подменяется.
Actor/reward/zone входы контролируются; Native getId/getLevel/isRaid/leader,
формула доли урона и аргументcharLevel остаются реальными.
4режима off/epic/all/both x legacy on/off x83проверки =664.
Проверены legacy85, независимость новых флагов, обычные мобы, GrandBoss вне
карты ID, рейдовые миньоны и циклы, larva29002 без parent, QA zone, границы
curse/drop/XP, maxgap/нулевой cutoff и85против88, damage share/целочисленное деление.

Нужны JDK27, Native205 libs и собранные0505 Attackable/RaidLevelAccess.
Скомпилировать fixture в отдельный каталог с classpath0505;Native libs/*.
Перед Native libs в runtime classpath должны стоять собранный Attackable иhelper.
Рабочий каталог — временный game, не папка установленного сервера.
В config/Custom/EpicLevelAccess.ini задать соответствующий режим и legacyflag;
значения старых порогов для проверки —85. Использовать NoWorldAgent из
../ready-source/NoWorldAgent.java (с Can-Retransform-Classes: true).
Параметры JVM: -Xverify:all -Xmx256m -Djava.awt.headless=true
  --sun-misc-unsafe-memory-access=allow
  --enable-final-field-mutation=ALL-UNNAMED -javaagent:<NoWorldAgent.jar>
Класс: org.l2jmobius.gameserver.managers.RaidLevelAccessCheck.
Точные параметры main перечислены в конце исходника fixture.
JDBC guard активно проверяется до сценариев.

Границы: фактическое наложение эффекта/loot pipeline/запись SQL/квестовый мир/
боевой клиент не запускаются. Проверка новых ссылок в семи Native-файлах —
чистое применение профиля+компиляция; полная сборка модуля проверяется отдельно.
Это целевая fixture, не новый общий прогонщик server selftests.
