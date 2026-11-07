Автобой: проверка недоступной цели без игрового мира.

Основа: Mobius2b9d8cf5 + main f072c301 + Epilogue df108bb9.
0500 накладывается после прежних patches/epilogue, затем прежний glue.patch;
чистая цепочка воспроизводит изменённые Native-файлы. Общийcore/launcher не меняются.

Настройка Custom/AutoPlay.ini: AutoPlayStuckTargetSeconds=20, диапазон0..3600.
0 отключает новый контроль уже выбранного моба, старый обход остаётся.
Временное исключение использует прежние60сек; новые таймеры не создаются.

AutoPlayNativeCheck — целевая NoWorld-проверка run/walkAround. Нужны JDK27,
Native205 GameServer.jar/зависимости, data/stats/skills/01200-01299.xml
и скомпилированный Native handlers.skill.effects.MagicalDamage.
Guard — существующий NoWorldAgent из tests/ready-source.
Компиляция fixture: javac -cp <Native-libs/*> -d <scratch> AutoPlayNativeCheck.java
После компиляции классов TaskManager/AutoPlayConfig и helper из0500:
java -javaagent:<NoWorldAgent.jar> --sun-misc-unsafe-memory-access=allow
 --enable-final-field-mutation=ALL-UNNAMED -cp <scratch;effects;Native-libs/*>
 org.l2jmobius.gameserver.taskmanagers.AutoPlayNativeCheck false
java с теми же параметрами и аргументами true <compiled-fixed-class-directory>
Рабочий каталог — копия datapack, не установленный игровой мир.

Проверялась реальная ветка контроллера и штатные search predicates.
World/геометрия/AI/clock/actor values записаны; roundtrip bytecode-fingerprint
восстанавливается. Это не тест боевого урона, геодаты или клиента.
Результат: baseline22/fixed23. Без SQL, мира, клиента и продуктового Java-agent.
Штатные полные серверные прогоны — только tools/selftest_run.py по разрешению.
