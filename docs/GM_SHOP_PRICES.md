# Цены GM-шопа High Five: аудит 2026-10-31

Запрос автора: «Цены в гм шопе кривые: дракончик за 5к (дёшево), соски пакет стоят дороже чем поштучно. Провести полный аудит цен».

Патч `patches/ct26_highfive/0125-gm-shop-prices.patch`, с 2026-11-01 тот же номер у Epilogue: цены те же, кроме строк H5 с предметами, которых в датапаке Epilogue нет (`tools/multisell_prices.py epilogue` — 0 находок). Других хроник не касается. Правила приняты без автора, поправить можно утром.

## Страницы на русском

Патч `patches/ct26_highfive/0191-gm-shop-russian.patch`, только High Five. 23 страницы лежат в `dist/game/data/lang/ru/data/html/merchant`, разметка и команды те же, что в `html/merchant`, поэтому новая кнопка или список правятся на обоих языках. Страницу выбирает `HtmCache.getHtm` по языку персонажа: окно NPC 50010 и раздел Alt+B берут её одинаково. Новый персонаж получает `L2HubLanguage`, сменить язык можно кнопкой «Language / Язык» на главной шопа (`.lang`). Заголовки доски идут от `L2HubLanguage`, а не от языка персонажа.

Кнопка «60 Min. Limited» переименована в «3 Hours Worn» и «3 часа в руках»: в списке 50039974 у всех 72 вещей `duration` 180 и `time` 21600, то есть 3 часа в руках и не дольше 15 дней.

Проверено: `tools/board_links.py` с русскими страницами, сравнение тегов с английскими, `L2HUBBOARD` на копии базы (`shop: pages follow the character's language`). В клиенте вид кнопок не смотрели.

## Заглушки 56 млн

Патч `patches/ct26_highfive/0192-gm-shop-stub-prices.patch`, только High Five, решение автора 2026-11-02. Из 1973 заглушек цену получили 1366: 1342 подешевели, 24 подорожали. 607 остались по 56 млн.

Правило: заглушка получает обычную цену такой же вещи или того же слота и грейда из этого же шопа; вещь на срок — долю от постоянной; коробка — матожидание содержимого; то, что покупать незачем или нельзя, остаётся по 56 млн.

Доля вещи на срок берётся по `time` шаблона, минимум 1 000 адены, три значащие цифры: до 4 часов 0,4 %, до суток 2 %, до 3 дней 5 %, до 7 дней 10 %, до 15 дней 20 %, до 30 дней 30 %, дольше 40 %.

На глаз, без соседей в шопе: Mercenary 100 000, Sealbook 500 000, SA (10 000 × 1,6 в степени стадии), баф-свитки 5 000. 21 вид содержимого коробок без цены считается как 0.

Остались по 56 млн: квестовые страницы (529 строк), оружие монстров (42), Infinity (14), осада и инстансы (9), Territory Badge (9), «Not In Use» (4). Четыре последние лежат в 50039027 и 50039034, это коробки с агатионом на 30 дней: в редакторе у них предупреждение «пачка дороже штук», оно верное.

Кнопка Infinity снята со страницы оружия на обоих языках: это оружие героев, не-герой купил бы его за 56 млн и не смог бы ни надеть, ни уничтожить. Строки списка 50039980 остались, охотники эти вещи и раньше не брали.

Заметно дешевле стали вещи, которые заглушка раньше прятала от охотников, поэтому в `PhantomHunterOutfit.sold` добавлена строка `item.getTime() > 0`: вещь на срок охотник не покупает. Иначе он взял бы оружие на 3 часа как постоянное и остался бы без него.

## Редактор цен

Вкладка «Цены GM-шопа» в «Настройках сервера», ядро `launcher/l2hub/gmshop.py`, консоль `python -m l2hub.cli gmshop <id> [show|find|warn|set|mul|reset|apply]`. Можно смотреть и искать вещи, править цену, умножать цены раздела, группы или всего шопа с предпросмотром. Округление при множителе — до трёх значащих цифр. Правится только строка, где платят одной аденой за одну вещь; обмены видны, но не правятся.

Правки лежат в `data/gmshop/<id>.json`, вне git: это мир автора и пользователя, в репозиторий они не идут. Файл сервера = список из исходников + правки, отличается от списка только числом цены. `gmshop.apply` вызывается при каждой правке и перед каждым стартом гейма в `servers.start_part`, поэтому правки переживают «Пересобрать». Снятая правка возвращает файл к списку сборки байт в байт; файл списка без правок редактор не трогает.

Гейм читает мультиселлы при старте, поэтому в игре цена меняется после перезапуска гейма. Охотники читают те же файлы (`PhantomHunterOutfit.gmShopGoods`) и идут за правками со следующего старта.

Предупреждения те же, что в `tools/multisell_prices.py`, и правило пачки: «дешевле скупки NPC», «обмен с прибылью», «пачка дороже штук». Они не запрещают цену. Считаются только по спискам, у которых есть NPC шопа: цена в недоступном списке 600014 ни на что не влияет.

Другая хроника подключается записью в `launcher/l2hub/gm_shops.json`: NPC шопа, папки списков, страницы по языкам, шаблоны вещей и умений. Вкладка появится сама.

## Поиск, рейды и карма на доске

Патчи `0193`–`0196`, только High Five. Команды обрабатывают отдельные классы рядом с `L2HubBoard`, по образцу `L2HubKits`.

- **Поиск вещи** (`_bbshubfind`, `L2HubFind`, патч `0193`): кнопка на первой странице шопа и в разделе «Мир». Ищет по английскому имени или номеру вещи: что почём в GM-шопе и кто продаёт в лавках. Цены показывает те, что загрузил сервер, значит и правки редактора. Лавки только продажи, скупка в поиск не входит. К лавке охотника можно телепортироваться, к остальным доска ставит метку на карте. В поиске дропа (`DropSearchBoard`) появились вещи, которые продаются только в шопе, и строка «где купить».
- **Рейд-боссы** (`_bbshubraids`, `L2HubRaids`, патч `0194`): кто жив, кто мёртв, метка на карте. Эпики берутся из `GrandBossManager`, мёртв тот, у кого время возрождения ещё не наступило. Само время возрождения не показываем.
- **Снятие кармы** (`_bbshubkarma`, `L2HubKarma`, патч `0195`): с кармой доска закрыта (`CommunityKarmaDisabled`), поэтому `HomeBoard` вместо отказа открывает эту страницу. Цена очка — `TownKarmaPrice` в `L2HubTown.ini`, по умолчанию 1000, значение взято на глаз, 0 выключает снятие. Счётчик PK не меняется: мера за PK остаётся за квестом Repent Your Sins. С проклятым оружием карма не снимается.
- **Самопроверка** (`L2HUBBOARD`, патч `0196`): поиск, лавки, рейды и карма на копии базы.

## Итог

- Изменено строк: 75, в шести списках: 50039918 питомцы, 50039930 свитки, 50039938 сосы, 50039979 снаряды, 50039988 и 50039989.
- Пачки сосок: 32 из 36 были дороже штук, теперь стоят ровно штуки × количество. Четыре пачки и раньше были дешевле штук, их не трогал.
- Питомцы подняты по аналогии между собой, три ступени: 100 тысяч детёныши, 2 миллиона взрослые, 10 миллионов верховые и большие.
- Заглушки 56 млн: заменены только там, где есть явная аналогия. Остальные оставлены, список ниже.
- `tools/multisell_prices.py ct26_highfive`: 0 находок. Банки маны 728 и 726 не менялись.
- Охотники не обнищают: в патче нет их снаряжения, а сосы и стрелы они считают по цене предмета из `stats/items`, которая не менялась.

**Дракончика за 5000 нет** ни в списке питомцев, ни в других. По 5000 продаются Life Stone 46, Primeval Potion, Scroll: Flames of Valakas и Blessed Scroll of Resurrection for Pets. Самые дешёвые дракончики были Dragonflute за 1000 и Dragon Bugle за 2000, скорее всего речь о них. Теперь оба стоят 100 000.

**Капкан Blessed Spiritshot C.** Штука 42, пачка из 300 по новому правилу вышла бы 12 600, а NPC скупает такую пачку за 14 850: покупаешь и продаёшь с прибылью. Поэтому штука поднята до 50, пачки до 15 000 и 50 000.

## Было и стало

### питомец: начальный, как Baby 100000 (4)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039918 | 2375 | Wolf Collar | 56 000 000 | 100 000 |
| 50039918 | 3500 | Dragonflute of Wind | 1 000 | 100 000 |
| 50039918 | 3501 | Dragonflute of Star | 1 000 | 100 000 |
| 50039918 | 3502 | Dragonflute of Twilight | 1 000 | 100 000 |

### питомец: средний, как Great Wolf 2 млн (7)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039918 | 4422 | Dragon Bugle of Wind | 2 000 | 2 000 000 |
| 50039918 | 4423 | Dragon Bugle of Star | 2 000 | 2 000 000 |
| 50039918 | 4424 | Dragon Bugle of Twilight | 2 000 | 2 000 000 |
| 50039918 | 10311 | Improved Buffalo Panpipe | 2 000 | 2 000 000 |
| 50039918 | 10312 | Improved Cougar Chime | 2 000 | 2 000 000 |
| 50039918 | 10313 | Improved Kookaburra Ocarina | 2 000 | 2 000 000 |
| 50039918 | 14819 | Guardian's Dragon Bugle | 2 000 | 2 000 000 |

### питомец: верхний, выше Great Wolf (5)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039918 | 10308 | Red Dragon Bugle of Wind | 2 000 | 10 000 000 |
| 50039918 | 10309 | Red Dragon Bugle of Star | 2 000 | 10 000 000 |
| 50039918 | 10310 | Red Dragon Bugle of Twilight | 2 000 | 10 000 000 |
| 50039918 | 10426 | Fenrir Necklace | 56 000 000 | 10 000 000 |
| 50039918 | 10611 | Snow Fenrir Necklace | 2 000 000 | 10 000 000 |

### заглушка: как Scroll of Escape (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039930 | 10650 | Adventurer's Scroll of Escape | 56 000 000 | 400 |

### заглушка: как Blessed Scroll of Escape (12)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039930 | 10149 | Battleground Blessed Scroll of Escape | 56 000 000 | 150 000 |
| 50039930 | 13258 | Gran Kain's Blessed Scroll of Escape | 56 000 000 | 150 000 |
| 50039988 | 13731 | Blessed Scroll of Escape: Gludio | 56 000 000 | 150 000 |
| 50039988 | 13732 | Blessed Scroll of Escape: Dion | 56 000 000 | 150 000 |
| 50039988 | 13733 | Blessed Scroll of Escape: Giran | 56 000 000 | 150 000 |
| 50039988 | 13734 | Blessed Scroll of Escape: Oren | 56 000 000 | 150 000 |
| 50039988 | 13735 | Blessed Scroll of Escape: Aden | 56 000 000 | 150 000 |
| 50039988 | 13736 | Blessed Scroll of Escape: Innadril | 56 000 000 | 150 000 |
| 50039988 | 13737 | Blessed Scroll of Escape: Goddard | 56 000 000 | 150 000 |
| 50039988 | 13738 | Blessed Scroll of Escape: Rune | 56 000 000 | 150 000 |
| 50039988 | 13739 | Blessed Scroll of Escape: Schuttgart | 56 000 000 | 150 000 |
| 50039989 | 10149 | Battleground Blessed Scroll of Escape | 56 000 000 | 150 000 |

### заглушка: как Scroll of Escape в деревню (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039930 | 12753 | Kamael Village Teleportation Scroll | 56 000 000 | 35 000 |

### заглушка: как Blessed Scroll of Resurrection (2)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039930 | 13259 | Gran Kain's Blessed Scroll of Resurrection | 56 000 000 | 400 000 |
| 50039989 | 10150 | Battleground Blessed Scroll of Resurrection | 56 000 000 | 400 000 |

### штука Blessed C: иначе пачка дешевле своей скупки NPC (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039938 | 3949 | Blessed Spiritshot (C-Grade) | 42 | 50 |

### пачка = штуки x количество (32)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039938 | 5134 | Compressed Package of Soulshots: No Grade | 2 300 | 2 100 |
| 50039938 | 5137 | Compressed Package of Soulshots (B-Grade) | 16 500 | 15 000 |
| 50039938 | 5138 | Compressed Package of Soulshots (A-Grade) | 26 400 | 24 000 |
| 50039938 | 5139 | Compressed Package of Soulshots (S-Grade) | 33 000 | 30 000 |
| 50039938 | 5140 | Compressed Package of Spiritshots: No Grade | 4 950 | 4 500 |
| 50039938 | 5141 | Compressed Package of Spiritshots (D-Grade) | 5 940 | 5 400 |
| 50039938 | 5142 | Compressed Package of Spiritshots (C-Grade) | 11 550 | 10 500 |
| 50039938 | 5143 | Compressed Package of Spiritshots (B-Grade) | 33 000 | 30 000 |
| 50039938 | 5144 | Compressed Package of Spiritshots (A-Grade) | 39 600 | 36 000 |
| 50039938 | 5145 | Compressed Package of Spiritshots (S-Grade) | 49 500 | 45 000 |
| 50039938 | 5146 | Compressed Package of Blessed Spiritshots: No Grade | 11 550 | 10 500 |
| 50039938 | 5147 | Compressed Package of Blessed Spiritshots (D-Grade) | 14 850 | 8 400 |
| 50039938 | 5148 | Compressed Package of Blessed Spiritshots (C-Grade) | 29 700 | 15 000 |
| 50039938 | 5149 | Compressed Package of Blessed Spiritshots (B-Grade) | 80 850 | 73 500 |
| 50039938 | 5150 | Compressed Package of Blessed Spiritshots (A-Grade) | 95 700 | 87 000 |
| 50039938 | 5151 | Compressed Package of Blessed Spiritshots (S-Grade) | 115 500 | 105 000 |
| 50039938 | 5250 | Greater Compressed Package of Soulshots (No-Grade) | 7 700 | 7 000 |
| 50039938 | 5253 | Greater Compressed Package of Soulshots (B-Grade) | 55 000 | 50 000 |
| 50039938 | 5254 | Greater Compressed Package of Soulshots (A-Grade) | 88 000 | 80 000 |
| 50039938 | 5255 | Greater Compressed Package of Soulshots (S-Grade) | 120 000 | 100 000 |
| 50039938 | 5256 | Greater Compressed Package of Spiritshots (No-Grade) | 16 500 | 15 000 |
| 50039938 | 5257 | Greater Compressed Package of Spiritshots (D-Grade) | 19 800 | 18 000 |
| 50039938 | 5258 | Greater Compressed Package of Spiritshots (C-Grade) | 38 500 | 35 000 |
| 50039938 | 5259 | Greater Compressed Package of Spiritshots (B-Grade) | 110 000 | 100 000 |
| 50039938 | 5260 | Greater Compressed Package of Spiritshots (A-Grade) | 132 000 | 120 000 |
| 50039938 | 5261 | Greater Compressed Package of Spiritshots (S-Grade) | 180 000 | 150 000 |
| 50039938 | 5262 | Greater Compressed Package of Blessed Spiritshots (No-Grade) | 38 500 | 35 000 |
| 50039938 | 5263 | Greater Compressed Package of Blessed Spiritshots (D-Grade) | 49 500 | 28 000 |
| 50039938 | 5264 | Greater Compressed Package of Blessed Spiritshots (C-Grade) | 99 000 | 50 000 |
| 50039938 | 5265 | Greater Compressed Package of Blessed Spiritshots (B-Grade) | 269 500 | 245 000 |
| 50039938 | 5266 | Greater Compressed Package of Blessed Spiritshots (A-Grade) | 319 000 | 290 000 |
| 50039938 | 5267 | Greater Compressed Package of Blessed Spiritshots (S-Grade) | 419 000 | 350 000 |

### заглушка: как Bone Arrow (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22067 | Bone Arrow | 56 000 000 | 3 |

### заглушка: как Steel Arrow (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22068 | Steel Arrow | 56 000 000 | 5 |

### заглушка: как Silver Arrow (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22069 | Silver Arrow | 56 000 000 | 7 |

### заглушка: как Mithril Arrow (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22070 | Mithril Arrow | 56 000 000 | 8 |

### заглушка: как Shining Arrow (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22071 | Arrow of Light | 56 000 000 | 10 |

### заглушка: как Bone Bolt (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22144 | Bone Bolt | 56 000 000 | 3 |

### заглушка: как Steel Bolt (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22145 | Steel Bolt | 56 000 000 | 5 |

### заглушка: как Silver Bolt (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22146 | Silver Bolt | 56 000 000 | 7 |

### заглушка: как Mithril Bolt (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22147 | Mithril Bolt | 56 000 000 | 8 |

### заглушка: как Shining Bolt (1)

| Список | id | Вещь | Было | Стало |
|---|---|---|---:|---:|
| 50039979 | 22148 | Bolt of Light | 56 000 000 | 10 |

## Что не тронуто и ждёт автора

Раздел описывает аудит 2026-10-31. С 2026-11-02 большую часть этих строк закрыл `0192`, см. «Заглушки 56 млн».

Строк ровно с 56 000 000 адены было 2003, патч заменил 28. У всех заглушек `price` в `stats/items` равен 0: своей цены у вещи нет, и явной аналогии часто тоже. Самые заметные группы, что остались: квестовые вещи (529 строк в 50330530, 50330539–50330542), шляпы и ивентовые вещи (142), Transformation Scroll (108), талисманы (79), Soul Crystal (54), Crystal Scroll и кристаллы заточки (32), свитки бафов (23).

**Питомцы, оставлены на 56 млн:** 4425 Penitent's Manacles, 13017–13020, 14061–14064, 14828, 15488, 15489, 20760, 20761, 20908–20913, 20915–20920, 21916–21918. Это призывы, агатионы и ивентовые ошейники: аналога среди обычных питомцев нет.

**Колчаны и контейнеры без пачки внутри** (22089–22093, 22149–22153): заглушка осталась, вычислить цену не из чего.

Разрез по спискам (до патча, для списка и количества):

```text
50039001 (4): Bouquet, Chrono Cithara, Falchion, Apprentice Adventurer's Long S
50039003 (4): Neti's Dagger, Cybellin's Dagger, Pipette Knife, Apprentice Adventurer's Knife
50039004 (2): Neti's Bow, Apprentice Adventurer's Bow
50039005 (2): Chrono Unitus, Apprentice Adventurer's Cestus
50039006 (1): Chrono Campana
50039007 (1): Apprentice Adventurer's Bone C
50039008 (3): Mage Staff, Chrono Darbuka, Apprentice Adventurer's Staff
50039009 (1): Traveler's Bastard Sword
50039012 (1): Traveler's Poniard Dagger
50039013 (2): Traveler's Dark Elven Bow, Traveler's Long Bow
50039014 (1): Traveler's Jamadhr
50039015 (1): Traveler's Pike
50039016 (2): Traveler's Mace, Traveler's Tomahawk
50039017 (1): Traveler's Staff
50039027 (6): ×3, Sudden Agathion 7, Shiny Agathion 7, Sobbing Agathion 7
50039034 (1): 
50039903 (12): Fire Jewel, Water Jewel, Earth Jewel, Wind Jewel, Dark Jewel, Holy Jewel, Fire Energy, Water Energy, Earth Energy, Wind Energy, Dark Energy, Holy Energy
50039904 (3): Giant's Codex×3
50039905 (1): Ancient Book
50039906 (19): Transformation Sealbook×19
50039909 (13): Ancient Cloak×4, 7th Anniversary Cloak×3, Festival's Cloak×2, Sealed Ancient Cloak, Soul Cloak of Zaken, Soul Cloak of Freya, Soul Cloak of Frintezza
50039910 (18): Gludio Supply Box×2, Dion Supply Box×2, Giran Supply Box×2, Oren Supply Box×2, Aden Supply Box×2, Innadril Supply Box×2, Goddard Supply Box×2, Rune Supply 
50039911 (38): Sealed Magic Pin×4, Low×4, Mid×4, High×4, Top×4, Gludio Supply Box×2, Dion Supply Box×2, Giran Supply Box×2, Oren Supply Box×2, Aden Supply Box×2, Innadril
50039912 (38): Sealed Magic Pouch×4, Low×4, Mid×4, High×4, Top×4, Gludio Supply Box×2, Dion Supply Box×2, Giran Supply Box×2, Oren Supply Box×2, Aden Supply Box×2, Innadr
50039913 (5): Pailaka Shirt, Kratei Barbed Shirt, Kratei Mithril Shirt, Kratei Striped Barbed Shirt, Kratei Striped Mithril Shirt
50039914 (5): Summon of Love Bracelet×2, Pailaka Bracelet, Dawn's Bracelet, Bracelet of Friendship
50039915 (79): Yellow Talisman×10, Blue Talisman×8, Black Talisman×7, White Talisman×6, Red Talisman×4, Blue Talisman of Defense×2, Orange Talisman×2, Blue Talisman of Ev
50039917 (1): Improved Blessed Earring of Za
50039918 (31): Rudolph Necklace×2, Wolf Collar, Penitent's Manacles, Fenrir Necklace, Fox Shaman Hunting Helper Neck, Wild Beast Fighter Hunting Hel, White Weasel Hunting
50039919 (2): The Lord's Crown, Improved Lord's Crown
50039920 (142): L2day×5, Kat the Cat Hat×5, Skull Hat×5, Feline Queen Hat×4, Monster Eye Hat×4, Brown Bear Hat×4, Fungus Hat×4, Ornithomimus Hat×4, Feline King Hat×4, Kai
50039921 (3): Spellbook×3
50039925 (44): Monster Only×21, For Monsters Only×7, Exclusive to Monsters×4, For NPC×3, For Monster only, Gracian Soldier Two, Chrono Campana, Staff of Master Yogi, Cour
50039927 (54): Red Soul Crystal×17, Green Soul Crystal×17, Blue Soul Crystal×17, Cursed Red Soul Crystal, Cursed Blue Soul Crystal, Cursed Green Soul Crystal
50039928 (13): Pailaka Vitality Replenishing ×2, Beginner's Potion, Battle Health Recovery Potion, Battle Mana Recovery Potion, Battle CP Recovery Potion, Long, Mental St
50039929 (24): Greater Elixir of CP×6, Greater Elixir of Life×6, Greater Elixir of Mental Stren×6, Greater Elixir Gift Box×6
50039930 (6): Gran Kain's Blessed Scroll of ×2, Adventurer's Scroll of Escape, Battleground Blessed Scroll of, Kamael Village Teleportation S, Scroll of Escape
50039931 (32): Crystal Scroll×10, Scroll×8, Ancient Weapon Enchant Crystal×3, Divine Weapon Enchant Crystal×2, Divine Armor Enchant Crystal×2, Small fortuna box, Middle f
50039944 (24): Weapon Master Circlet×2, Bow Master Circlet×2, Human Circlet, Elven Circlet, Dark Elven Circlet, Orcish Circlet, Dwarven Circlet, Kamaelic Circlet, Shield 
50039945 (23): Scroll of Guidance×2, Scroll of Death Whisper×2, Scroll of Focus, Scroll of Acumen, Scroll of Haste, Scroll of Agility, Scroll of Empower, Scroll of Might,
50039950 (5): Proof of Catching a Fish, Wind Fishing Lure, Icy Air Fishing Lure, Earth Fishing Lure, Flaming Fishing Lure
50039969 (2): Freed Soul, Dragonkin Charm
50039970 (108): Transformation Scroll×108
50039971 (9): Gludio Disguise Scroll, Dion Disguise Scroll, Giran Disguise Scroll, Oren Disguise Scroll, Aden Disguise Scroll, Innadril Disguise Scroll, Goddard Disguise 
50039972 (15): Santa Claus' Sirra Blade, Santa Claus' Sword of Ipos, Santa Claus' Barakiel Axe, Santa Claus' Behemoth Tuning F, Santa Claus' Naga Storm, Santa Claus' Tiph
50039973 (15): Baguette Sword, Baguette Dagger, Baguette Dual Sword, Baguette Mace, Baguette Heavy Hammer, Baguette Spear, Baguette Bow, Baguette Fist, Baguette Magic Swo
50039974 (72): Player Commendation×72
50039975 (70): Sword of Revolution, Titan Sword, Maingauche, Tarbar, Titan Hammer, Priest Mace, Goat Head Staff, Bich'Hwa, Strengthened Long Bow, Winged Spear, Artisan's 
50039976 (22): Snow Kung Transformation Stick×4, Scarecrow Jack Transformation ×4, Tin Golem Transformation Stick×4, Pumpkin Transformation Stick×4, Aqua Elf Transforming
50039977 (50): Event×21, 1st Place Treasure Sack, 2nd Place Treasure Sack, 3rd Place Treasure Sack, 4th Place Treasure Sack, 5th Place Treasure Sack, 6th Place Treasure S
50039978 (4): Dynastic Essence, Dynastic Essence II, Freed Soul, Dragonkin Charm
50039979 (22): Special Bolt, Bone Arrow, Steel Arrow, Silver Arrow, Mithril Arrow, Arrow of Light, Bone Bolt, Steel Bolt, Silver Bolt, Mithril Bolt, Bolt of Light, Soul B
50039980 (14): Infinity Blade, Infinity Cleaver, Infinity Axe, Infinity Rod, Infinity Crusher, Infinity Scepter, Infinity Stinger, Infinity Fang, Infinity Bow, Infinity W
50039986 (41): Valkyrie Hat×2, Tiger Hat×2, Baby Panda Hat×2, Bamboo Panda Hat×2, Sexy Panda Hat×2, Gatekeeper Hat×2, Shiny Super Strong Giant's Mas×2, Golden Jack O'Lant
50039987 (54): Gludio Earth Resistance Ring, Gludio Protection Earring, Gludio Water Resistance Neckla, Gludio Earth Royal Guard Ring, Gludio Guard Earring, Gludio Water 
50039988 (18): Blessed Scroll of Escape×9, Gludio Territory Badge, Dion Territory Badge, Giran Territory Badge, Oren Territory Badge, Aden Territory Badge, Innadril Terri
50039989 (19): Battleground Spell×6, Battleground Blessed Scroll of×2, Battleground Transform Spell, Battle Health Recovery Potion, Battle Mana Recovery Potion, Battle CP
50039990 (22): Dagger of Val Turner Family, Slasher of Val Turner Family, Sword of Ashton Family, Claw of Ashton Family, Slasher of Esthus Family, Great Hammer of Esthus 
50039995 (17): Low×2, Mid×2, High×2, Top×2, Gludio Supply Box, Dion Supply Box, Giran Supply Box, Oren Supply Box, Aden Supply Box, Innadril Supply Box, Goddard Supply Bo
50039996 (17): Low×2, Mid×2, High×2, Top×2, Gludio Supply Box, Dion Supply Box, Giran Supply Box, Oren Supply Box, Aden Supply Box, Innadril Supply Box, Goddard Supply Bo
50040004 (173): Agathion Seal Bracelet×129, Soul Avatar Seal Bracelet×12, Agathion of Love×9, Sudden Agathion×4, Shiny Agathion×4, Sobbing Agathion×4, Teddy Boy Agathion 
50040005 (15): Steam Beatle Mount Bracelet×5, Light Purple×4, Gold×2, Jet Bike Mount Bracelet×2, Gold Maned Lion Mount Bracelet, Darkmane Pacer Mount Bracelet
50040006 (23): Shiny Super Strong Giant's Mas×3, Super Strong Giant's Mask, Mask of Silent Scream, Spirit of Wrath Mask, Undecaying Corpse Mask, Planet X235 Alien Mask, H
50060046 (4): Recipe×4
50330530 (101): Resonance Amulet×12, Mark of Ketra's Alliance×5, Mark of Varka's Alliance×5, Varka's Badge×3, Ketra's Badge×3, Munitions Box×2, Golden Ram Badge×2, Red Bo
50330539 (98): Guild Report×7, Guardian Angel of the Tablet×6, Tattered Book×4, Research Report×3, Voucher×3, Suspicious Totem Doll×3, Event×2, Map×2, Guild's Secret Repo
50330540 (149): Resonance Amulet×149
50330541 (150): Resonance Amulet×56, Halisha's Mark×31, Investigative Report×5, Offering×4, Galladucci's Order×3, Ancient Language Dictionary×2, Map Piece×2, Ice Wine, Do
50330542 (31): Looted Goods×7, Worn Tarot Card×4, Booty×2, Caradine's Letter×2, Rahorakti's Miraculous Medicin, Summoner Keats' Letter, Miracle Potion of Transferred , Ka
заглушек с price > 0: 0
```

## Другие странности (не менял)

- Wards 13560–13568 и Flags 13530–13538 продаются по 1 адене, и `price` у них 1. Они нужны для осад территорий, дешевизна на экономику не влияет, но выглядит странно.
- Списки 600010, 600012, 600014, 600016 и 600024 не привязаны ни к одной кнопке. В 600014 оружие вдвое дешевле шопа, но купить его нельзя.

## Откат

Удалить файл `patches/ct26_highfive/0125-gm-shop-prices.patch` (большое — в архив, правило проекта) и нажать «Пересобрать» в окне: сборка увидит изменившуюся стопку и вернёт списки. Или `git revert` коммита с этим патчем и та же пересборка. База при этом не меняется, купленные вещи остаются у персонажей.

Также исправлен сам `tools/multisell_prices.py`: разбор `iter("item")` цеплял вложенные `<item>` из `capsuled_items` и затирал 938 настоящих вещей, цена и имя у них пропадали. Находок на High Five это не добавило: и до, и после 0. Проверка на других хрониках после правки не запускалась.
