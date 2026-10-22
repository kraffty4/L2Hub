# Чужие компоненты L2Hub

Что входит в программу, что она скачивает по кнопке и чего не раздаёт вовсе. Тексты лицензий компонентов из первой таблицы лежат в сборке рядом с программой — папка `licenses/` (кладёт `launcher/packaging/build.py`), шрифтов — в `ui/assets/fonts`.

## Внутри программы (exe, установщик, портативный zip)

| Компонент | Версия | Лицензия | Зачем |
|---|---|---|---|
| Python | 3.12 | PSF-2.0 | среда программы (PyInstaller) |
| pywebview | 6.2.1 | BSD-3-Clause | окно на WebView2 |
| Microsoft.Web.WebView2 (Core, WinForms) | из pywebview | лицензия Microsoft WebView2 SDK (BSD-подобная) | связка с WebView2 Runtime |
| pythonnet, clr-loader | 3.2.1, 0.3.1 | MIT | .NET для окна и трея |
| bottle | 0.13.4 | MIT | локальный http-сервер окна (pywebview) |
| proxy-tools | 0.1.0 | MIT | зависимость pywebview |
| cffi, pycparser | 2.1.1, 3.11 | MIT-0, BSD-3-Clause | зависимости pythonnet |
| psutil | 7.2.2 | BSD-3-Clause | процессы серверов, порты, память |
| Pillow | 13.0.0 | MIT-CMU | подложка карты «Мой мир»: webp из скачанных картинок |
| typing-extensions | 4.16.0 | PSF-2.0 | зависимость |
| загрузчик PyInstaller | 6.22.3 | GPL-2.0-or-later с исключением для загрузчика (можно распространять с программой под любой лицензией) | запуск exe |
| шрифты Cinzel, Philosopher, Inter | — | SIL Open Font License 1.1 | интерфейс |
| установщик Inno Setup | 6 | лицензия Inno Setup (бесплатная, в том числе для коммерческих программ) | только сборка установщика |

## Скачивает лаунчер по кнопке пользователя (в программу не входит)

Каждый архив — с официального адреса; где поставщик публикует сумму, она сверяется (`runtimes.json`, `geodata.json`, `mobius_pins.json`).

| Компонент | Лицензия | Откуда |
|---|---|---|
| Eclipse Temurin JDK 27 | GPL-2.0 с Classpath Exception | github.com/adoptium |
| MariaDB 13.0.2 | GPL-2.0 | archive.mariadb.org |
| Apache Ant 1.10.18 | Apache-2.0 | archive.apache.org |
| Node.js 24 (только для C4 — L2Solo) | MIT | nodejs.org |
| L2J Mobius — исходники серверов на закреплённом коммите | GPL-3.0 | gitlab.com/MobiusDevelopment/L2J_Mobius |
| L2Solo (C4) | Apache-2.0 | github.com (релиз их репозитория) |
| L2JLisvus (C4, вариант) — если пользователь подключит свою сборку | GPL-3.0 | у пользователя |
| l2mapconv — геодата из карт клиента (Classic, Essence) | MIT | github.com/m49n/l2mapconv-public |
| L2J Geodata (High Five, Epilogue) | GPL-3.0 (LICENSE в архиве) | bitbucket.org/l2jgeo/l2j_geodata |
| L2J Geodata Project (Interlude) | лицензия не указана | github.com/ChaosPaladin/l2j-geodata-project |

Наши патчи к исходникам L2J Mobius (`patches/`) и модуль фантомов (`phantoms/`) — производные от GPL-3.0 кода и распространяются под GPL-3.0.

## Не раздаём

Клиенты Lineage II и их данные, системы клиентов приватных серверов, сборки-комбайны вроде La2offline, тайлы карты мира (`npetrovski/l2-world-map`, лицензии нет) — их приносит пользователь, программа их не скачивает и не выкладывает. Lineage II и названия хроник — товарные знаки NCSoft; L2Hub с NCSoft не связан.
