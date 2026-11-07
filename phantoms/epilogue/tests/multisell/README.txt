0506 — причина отказа крупной покупки multisell

Native по умолчанию разрешает10000обменов за один запрос. Превышение раньше
молча сбрасывало открытый список.0506 добавляет штатное локализованное сообщение
SystemMessage1036; лимит/отказ/сброссписка и вся торговая логика не меняются.
Отрицательное/нулевое количество отклоняется как прежде, без новой подсказки.

Это не повышение общего default и не обход веса/денег. Размер допустимого
обмена настраивается существующим MultisellAmountLimit в General.ini через
L2Hub/Все настройки. Владелец выбирает локальное значение; его цены и настройки
не включаются в публичные defaults. Клиентский документированный максимум999999.

Изолированная проверка JDK27, Native Epilogue libs и отдельные CLASSES/TESTS:
  javac -encoding UTF-8 -cp "SERVER/libs/*" -d CLASSES TREE/java/org/l2jmobius/gameserver/network/clientpackets/MultiSellChoose.java
  javac -encoding UTF-8 -cp "CLASSES;SERVER/libs/*" -d TESTS MultiSellLimitCheck.java
Использовать существующий tests/ready-source/NoWorldAgent.java (Premain-Class:
NoWorldAgent, Can-Retransform-Classes:true), затем в отдельном рабочем каталоге:
  java -Xmx128m --sun-misc-unsafe-memory-access=allow -javaagent:NoWorldAgent.jar -Dmultisell.fixed=true -cp "TESTS;CLASSES;SERVER/libs/*" MultiSellLimitCheck

Check извлекает и исполняет настоящий начальный amount-guard из скомпилированного
runImpl, останавливаясь ДО prepared-list/инвентаря/денег. Параметры amount/player
подставляются вместо соответствующих полей/локалов. ЗначенияGeneralConfig меняются
только в этой приватной тестовой JVM. Фактические покупки, World/SQL не выполняются.
Проверяются24комбинации: оба лимита10000/100000 и границы(-1/0/1/10000/55555/
100000/LongMIN/MAX). Для sourceбазы ожидается75checksиотсутствиеуведомления,
для0506—76checks сNative1036. Это проверка причины отказа/сообщения, не полный
тест выдачи или сохранения покупки. Всю остальную кодовую часть0506 не меняет.
