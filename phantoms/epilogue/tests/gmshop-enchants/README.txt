GM-шоп: виды свитков заточки (0508)

Production: GmShopEnchantPolicy + короткий Native patch0508.
Настройки: config/Custom/GmShopEnchantScrolls.ini, раздел Кастом в L2Hub.
По умолчанию только обычные/благословенные. Пять других групп opt-in.
Настройки читаются при запуске JVM. Уже имеющиеся свитки, дроп, обмен между
персонажами, шансы и условия самой заточки этим правилом не меняются.
Support items не считаются свитками заточки.

Проверки выполняются только в собственном пустом каталоге, с NoWorldAgent
из ../ready-source. Он должен блокировать JDBC/GameServer/LoginServer.
Это отдельные unit/Native-parser проверки; они не заменяют штатный серверный
tools/selftest_run.py, не поднимают мир и не открывают БД.

1. Применить0508 штатным byte-wise builder к совместимым Native исходникам,
   добавить GmShopEnchantPolicy, скомпилировать семь затронутых классов и policy.
2. Скомпилировать GmShopEnchantPolicyCheck.java против этих классов и server/libs.
3. В отдельном пустом cwd создать data/stats/items и config/Custom.
   В последний положить тестовый INI. Запуск JDK27 с
   -javaagent:<NoWorldAgent.jar> --enable-final-field-mutation=ALL-UNNAMED
   -cp <tests;candidate;server/libs/*>
   org.l2jmobius.gameserver.managers.GmShopEnchantPolicyCheck <Native game/data> <mode>
   mode: default/crystal/enhanced/ancient/divine/special/all/off.
   Для каждого mode INI выставляется отдельно ДО запуска JVM:
   default — все дополнительныеFalse; одно имя — только этот флагTrue;
   all — всеTrue; off — masterFalse.
   Не запускать fixture из рабочего server/game: ItemData получает пустой cwd.
4. python -B phantoms/epilogue/tests/gmshop-enchants/test_settings.py

Fixture читает действительные60 EnchantItemData definitions и top-level item
attributes; EtcItem/EnchantScroll и Native MultisellData.parseDocument настоящие.
ItemData lookup заполняется минимальными EtcItem в памяти: проверки не оценивают
боевые свойства оружия/брони, загрузку всего мира или полную оплату/выдачу.
Охвачены обычныйNPC, Alt+B, магазин за медали, повторный запрос покупки,
bundle/ингредиенты, BuyList scope, сохранение цен/количества/entryID.

Native pre-parse сохраняет старый shop-контракт: в GM DOM удаляет только8
non-scroll/Fortuna, внеGM — прежние20. Пять scroll flags здесь НЕ читаются:
ID строк должны назначаться раньше фильтрации свитков и оставаться одинаковыми
при смене настроек. Native parser затем применяет единую scroll-policy к списку;
проверки отображения и оплаты используют её же. Не удалять DOM-строки свитков
по flags: старый адаптер так менял71идентификатор реально видимых товаров.

Весь механизм находится в обычных Native исходниках: никакой production-agent
или external SalePolicy не требуется. Только при сохранении прежних source hooks
local.l2.SalePolicy должен стать тонким делегатом filterLegacyDocument и брать
BANNED из legacyBannedIds, без второго blacklist и независимого DOM-фильтра.
Опциональный третий аргумент legacy проверяет этот адаптер в test classpath.
Native-прогон исключает legacy alias/module JAR из classpath полностью.
DOM contract проверяется при каждом запуске; STABLE_ROW выводит Native ordinal,
полные products/ingredients/count для сверки всех режимов.

Не заявлять живую покупку/рендер проверенными по этим standalone fixtures.
