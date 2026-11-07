AutoPlay0509 — язык при выключенном Native MultiLang

Подтверждённый контрпример текущих конфигов: MultiLangEnable=False,
L2HubLanguage=ru -> MultilingualSupportConfig.MULTILANG_DEFAULT=ru, но
Player.getLang()==null и htmlPrefix=="". Одного перевода0501 недостаточно:
tr выбиралEN, NativeHtmCache открывал стандартный английский шаблон.

0509 использует выбранный язык персонажа, иначе Native default (который уже
читает язык L2Hub). ДляRU явно запрашивает4русских шаблона AutoPlay, дляEN
оставляет прежний путь. Мультиязычность не включается глобально, остальные
окна/язык/команды/настройки и названия Skill/Item не меняются.
Явный RU-путь также доступен внешнему overlay до выбора Native htmlPrefix.

Проверка AutoPlayLanguageCheck — только отдельная JVM и C-каталог.
Реально исполняются privatetr/html, Native Player.getLang/getHtmlPrefix,
MultilingualSupportConfig.load и HtmCache.getHtm/loadFile. Эти методы и IO
не подменяются; контролируются Player/пакетные границы.
Проверки: OFF/defaultRU/null; ON/RU; ON/EN/globalRU; OFF/defaultEN.
66+66+66+65=263. Baseline501 приOFF/null выдаётEN, fixed509 —RU.
4шаблона/5вызовов (Potion дважды),39пар текста, команды/actions/placeholders
сохранены; RU/EN кеши и посторонняя страница не переключаются вместе с AutoPlay.

Нужны JDK27, Native205 libs, скомпилированные AutoPlay501 до509 и после509,
4EN/4RU шаблона Main/Skills/Items/Potion, schema и config из собственного
временного datapack. Точные CLI-параметры перечислены в main fixture.
Обязателен NoWorldAgent из ../ready-source/NoWorldAgent.java с манифестом
Premain-Class:NoWorldAgent и Can-Retransform-Classes:true.
Java options: -Xverify:all -Xmx256m -Djava.awt.headless=true
 --sun-misc-unsafe-memory-access=allow
 --enable-final-field-mutation=ALL-UNNAMED -javaagent:<NoWorldAgent.jar>.
World/JDBC активно заблокированы. Мир, клиент и live-рендер не тестируются.
Это целевая fixture, не общий прогонщик серверных самопроверок.
