"""Настройки самого L2Hub, а не хроник: крестик и трей, список хроник, окно. Файл — data/launcher.json (не в git).

Не зависит от окна: окно, трей и интерфейс только читают и пишут эти значения.
"""

import json

from . import paths

FILE = paths.DATA / "launcher.json"

DEFAULTS = {
    "closeRunning": "ask",    # крестик при работающем сервере: ask | tray | stop | leave
    "closeIdle": "exit",      # крестик, когда ничего не запущено: exit | tray
    "notifyCrash": True,      # уведомление от значка в трее, если гейм-сервер остановился сам
    "rememberWindow": True,   # открывать окно там же и того же размера
    "showUnplanned": True,    # показывать хроники вне плана 1.0: у них кнопка «Хочу эту хронику» (feedback.py)
    "checkUpdates": True,     # при запуске спросить GitHub Releases, не вышла ли новая версия (updates.py)
    "language": "auto",       # язык интерфейса: auto (как у Windows) | ru | en — i18n.py, ui/i18n.js
    "theme": "gold",          # тема окна, ключ THEMES — ui/themes.css
    "window": None,           # {"x", "y", "width", "height", "maximized"} — пишет окно при закрытии
}
# Темы окна и цвет окна до загрузки страницы (--win темы); сами палитры — ui/themes.css.
THEMES = {"gold": "#16120e", "parchment": "#f1eadb", "elven": "#101611", "darkelf": "#15101b", "orc": "#18100e", "freya": "#0f141a", "graphite": "#141416"}
CHOICES = {"closeRunning": {"ask", "tray", "stop", "leave"}, "closeIdle": {"exit", "tray"}, "language": {"auto", "ru", "en"}, "theme": set(THEMES)}
FLAGS = {"notifyCrash", "rememberWindow", "showUnplanned", "checkUpdates"}


def load() -> dict:
    try:
        saved = json.loads(FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        saved = {}
    return {**DEFAULTS, **{k: v for k, v in saved.items() if k in DEFAULTS}}


def save(patch: dict) -> dict:
    """Сохранить часть настроек; неизвестные ключи и значения вне списка — ошибка, а не молча мимо."""
    current = load()
    for key, value in patch.items():
        if key in CHOICES and value not in CHOICES[key]:
            raise ValueError(f"{key}: нет варианта {value!r}")
        if key in FLAGS and not isinstance(value, bool):
            raise ValueError(f"{key}: нужно да или нет")
        if key == "window" and value is not None and not (isinstance(value, dict) and {"x", "y", "width", "height"} <= value.keys()):
            raise ValueError("window: нужны x, y, width, height")
        if key not in DEFAULTS:
            raise ValueError(f"нет настройки {key!r}")
        current[key] = value
    FILE.parent.mkdir(parents=True, exist_ok=True)
    FILE.write_bytes((json.dumps(current, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return current
