"""Совмещение кнопочного меню Alt+B при переносе локальных ресурсов Epilogue.

0146 заменил ссылку gatekeeper/main.html единым _bbshubspots: города и споты.
Сохранение обоих bypass как разных услуг возвращает уже исправленный дубль.
Локальные кнопки остаются первыми; недостающие штатные добавляются в конце.
Подавать штатное меню нужного языка. Входы не меняются, CLI создаёт новый файл.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path

LEGACY_TELEPORT = "bypass _bbstop;gatekeeper/main.html"
UNIFIED_TELEPORT = "bypass _bbshubspots"


@dataclass(frozen=True)
class Button:
    raw: str
    action: str


class Navigation(HTMLParser):
    """Плоское кнопочное меню; старые лишние tr/td нормализуются как в rebase185."""

    def __init__(self, content: str):
        super().__init__(convert_charrefs=False)
        self.buttons: list[Button] = []
        self.opening = ""
        self.stack: list[str] = []
        self.tables = 0
        self.feed(content)
        self.close()
        if self.tables != 1 or self.stack:
            raise ValueError("Ожидается одно законченное кнопочное меню table")

    def handle_starttag(self, tag, attrs):
        if tag == "button":
            if self.stack != ["table"]:
                raise ValueError("Кнопка вне таблицы меню")
            actions = [value for key, value in attrs if key == "action"]
            if len(actions) != 1 or not actions[0]:
                raise ValueError("У кнопки должен быть один action")
            self.buttons.append(Button(self.get_starttag_text(), actions[0].strip()))
            return
        if tag not in ("table", "tr", "td", "br"):
            raise ValueError("Неизвестная разметка меню: " + tag)
        if tag == "table":
            if self.stack:
                raise ValueError("Вложенная таблица требует ручного совмещения")
            self.tables += 1
            self.opening = self.get_starttag_text()
            self.stack.append(tag)
        elif self.stack != ["table"]:
            raise ValueError("Разметка строки вне таблицы меню")

    def handle_endtag(self, tag):
        if tag == "button":
            raise ValueError("Ожидается штатный void-тег button")
        if self.stack != ["table"] or tag not in ("table", "tr", "td", "br"):
            raise ValueError("Несогласованные теги меню")
        if tag == "table":
            self.stack.pop()

    def handle_data(self, data):
        if data.strip():
            raise ValueError("Текст вне кнопок требует ручного совмещения")

    def handle_comment(self, data):
        raise ValueError("Комментарий требует ручного совмещения")

    def handle_entityref(self, name):
        raise ValueError("Текст вне кнопок требует ручного совмещения")

    def handle_charref(self, name):
        raise ValueError("Текст вне кнопок требует ручного совмещения")

    def handle_decl(self, decl):
        raise ValueError("Неожиданное объявление в меню")

    def handle_pi(self, data):
        raise ValueError("Неожиданная инструкция в меню")

    def unknown_decl(self, data):
        raise ValueError("Неизвестное объявление в меню")


def merge_navigation(local: bytes, native: bytes) -> bytes:
    """Сохраняет локальные кнопки, учитывая замену старого маршрута в0146.

    Нормализация строк таблицы соответствует прежнему rebase185.navigation.
    Назначения/цены телепортов не редактируются. Без единого маршрута в native
    старую ссылку не заменяем: наличие готового0146 — обязательная предпосылка.
    """
    left = Navigation(local.decode("utf-8-sig"))
    right = Navigation(native.decode("utf-8-sig"))
    unified = [button for button in right.buttons if button.action == UNIFIED_TELEPORT]
    if len(unified) > 1:
        raise ValueError("В штатном меню неоднозначный единый телепорт")
    aliases = {LEGACY_TELEPORT, UNIFIED_TELEPORT} if unified else set()
    selected: list[Button] = []
    known: set[str] = set()
    for button in left.buttons:
        if button.action in aliases:
            # На прежнем месте телепорта остаётся одна штатная кнопка нужного языка.
            if UNIFIED_TELEPORT not in known:
                selected.append(unified[0])
                known.add(UNIFIED_TELEPORT)
        else:
            selected.append(button)
            known.add(button.action)
    for button in right.buttons:
        action = UNIFIED_TELEPORT if button.action in aliases else button.action
        if action not in known:
            selected.append(unified[0] if button.action in aliases else button)
            known.add(action)
    newline = "\r\n" if b"\r\n" in local else "\n"
    rows = [left.opening, "<tr><td height=20></td></tr>"]
    rows.extend("<tr><td>" + button.raw + "</td></tr>" for button in selected)
    rows.append("</table>")
    result = newline.join(rows).encode("utf-8")
    return (b"\xef\xbb\xbf" if local.startswith(b"\xef\xbb\xbf") else b"") + result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("local", type=Path)
    parser.add_argument("native", type=Path)
    parser.add_argument("output", type=Path, help="Новый файл, отдельно от входов")
    args = parser.parse_args()
    if args.output.resolve() in (args.local.resolve(), args.native.resolve()):
        parser.error("Входные файлы не перезаписываются")
    merged = merge_navigation(args.local.read_bytes(), args.native.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as destination:
        destination.write(merged)


if __name__ == "__main__":
    main()
