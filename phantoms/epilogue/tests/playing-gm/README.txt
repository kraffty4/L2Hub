Играющий GM — целевые изолированные проверки

База: epilogue df108bb9, Mobius 2b9d8cf5, Native 0.5.205.
0503 меняет игровые исключения, но не Player.isGM(), AccessLevel,
AdminData.hasAccess, админские команды или аудит администратора.
В glue синхронизированы только две строки контекста GM-чатов: он по-прежнему
накладывается после профиля patches/epilogue штатным builder.py.

PlayingGm.ini:
  PlayingGmEnabled=True — разрешить режим; False возвращает штатную политику.
  PlayingGmDefaultMode=False — выбор по умолчанию для GM без личной настройки.
Личный выбор хранится в штатных PlayerVariables: L2HubPlayingGm.
Меню: //admin -> Playing GM; команда: //playinggm [on|off].
Глобальные параметры требуют перезапуска, личный выбор действует сразу.
Их графическое отображение требует отдельного изменения server_modes.json.

При включении снимаются ручные invul/diet/GM-баффы и GM-невидимость.
Обычные навыки Hide/INVUL и остальные классовые баффы сохраняются.
Переключение недоступно во время наблюдения: сначала надо выйти из него.
Выключение не накладывает автоматически бессмертие или GM-баффы.
Явные команды тестирования доступны в обоих режимах. Повторное включение
очищает их игровые эффекты. Античит, способный удалять вещи/навыки, не меняется.

PlayingGmStateCheck.java исполняет настоящие Policy/State с Native Player,
AccessLevel, PlayerVariables и флагами эффектов. Запись в БД, отправка пакетов
и terminal удаления эффекта заменены записывающими границами. Проверяются
персональный выбор, значения по умолчанию, выключение, сохранение/повторное
чтение, ошибка сохранения, ACL обоих знаков, Hide/INVUL и observer guard.
Серия с Lethal/ACL: 56 default-off +56 default-on +29 global-off +56 missing-config.

CavernConditionsCheck.java исполняет собранный checkConditions Закена.
До/после подмены зависимостей проверяет fingerprint самого метода: его
инструкции не меняются. Проверяет оба template, GM без группы против обычных
условий, лидерство, численность, уровень, дистанцию и откат участника.
Исходная серия: 25 проверок. Групповой список, уровень, время/хранилище отката
и отправка сообщения контролируются. Пороги9/72 остаются штатными; численность
SmallWorldEpics — отдельный балансный вопрос, этот режим её не меняет.

Для повторения нужны JDK27, собранная дельта0503, Native GameServer.jar,
его библиотеки, эффекты Buff/Hide/Invincible из scripts и временный game-каталог.
В него копируются config, data/xsd, data/stats/players, корневые XML data,
statBonus.xml и skills/00900-00999.xml,01000-01099.xml,01400-01499.xml,07000-07099.xml.
Никаких пользовательских аккаунтов или базы для этих fixture не требуется.

Использовать защитный NoWorldAgent из ../ready-source/NoWorldAgent.java:
манифест Premain-Class: NoWorldAgent и Can-Retransform-Classes: true.
Компилировать обе fixture через javac -encoding UTF-8 в отдельный каталог.
Classpath: fixture;собранные Policy/State и0503;эффекты;Native libs/*.
Рабочий каталог каждой JVM — временный game, не рабочая папка сервера.
Параметры JVM: -Xverify:all -Djava.awt.headless=true -Xmx256m
  --sun-misc-unsafe-memory-access=allow
  --enable-final-field-mutation=ALL-UNNAMED -javaagent:<NoWorldAgent.jar>
Классы запуска:
  org.l2jmobius.gameserver.managers.PlayingGmStateCheck <enabled> <default>
  org.l2jmobius.gameserver.managers.CavernConditionsCheck <каталог0503class>
Перед каждым State-запуском создать соответствующий PlayingGm.ini; для
missing-config его не создавать. Для Cavern включить Enabled=True/Default=False.

Границы: полноценный игровой мир, SQL-транзакция, реальная партия/командный
канал, повторный вход в существующий InstanceWorld и клиент не проверяются
этими fixture. Отдельный фильтр обычного зрения0504 проверяется отдельно.
Это целевые fixture, не новый общий прогонщик серверных самопроверок.
