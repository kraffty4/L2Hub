"""Окно лаунчера: python -m l2hub (или run.cmd). С --debug открываются инструменты разработчика.

L2Hub живёт одной копией: повторный запуск не открывает второе окно, а показывает первое — в том числе из трея.
Место и размер окна запоминаются в data/launcher.json (l2hub.appsettings).
"""

import ctypes
import sys
import threading
import time

import webview

from . import __version__, appsettings, i18n, paths, winframe
from .api import Api

MUTEX = "L2Hub.Launcher.Instance"
SHOW_EVENT = "L2Hub.Launcher.Show"
MIN_SIZE = (1100, 720)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateMutexW.restype = k32.CreateEventW.restype = k32.OpenEventW.restype = ctypes.c_void_p
k32.SetEvent.argtypes = [ctypes.c_void_p]
k32.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]


def ui_version() -> int:
    """Время последней правки файлов интерфейса. WebView2 с постоянным профилем (private_mode=False) кэширует
    styles.css и app.js — pywebview теряет свои заголовки no-cache, — поэтому версия идёт в адрес окна,
    а index.html подключает файлы с ней же: поменялся интерфейс — новый адрес, мимо кэша."""
    return int(max(f.stat().st_mtime for f in paths.UI.glob("*.*")))


def single_instance():
    """Событие «покажись» для первой копии; None — L2Hub уже запущен, и его попросили показать окно."""
    k32.CreateMutexW(None, False, MUTEX)  # хэндл живёт до конца процесса — этого и надо
    if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
        ev = k32.OpenEventW(0x0002, False, SHOW_EVENT)  # EVENT_MODIFY_STATE
        if ev:
            k32.SetEvent(ev)
            return None
    return k32.CreateEventW(None, False, False, SHOW_EVENT)


def wait_show(show_event, api) -> None:
    while True:
        k32.WaitForSingleObject(show_event, 0xFFFFFFFF)
        if api._tray:
            api._tray.show_window()


def initial_geometry() -> dict:
    """Где открыть окно: как в прошлый раз, если то место ещё на экране (монитор могли отключить)."""
    s = appsettings.load()
    g = s.get("window") if s["rememberWindow"] else None
    size = {"width": 1320, "height": 860}
    if not g:
        return size
    size = {"width": max(MIN_SIZE[0], int(g["width"])), "height": max(MIN_SIZE[1], int(g["height"]))}
    metric = ctypes.windll.user32.GetSystemMetrics
    vx, vy, vw, vh = metric(76), metric(77), metric(78), metric(79)  # виртуальный экран: все мониторы
    if vx - size["width"] // 2 < g["x"] < vx + vw - 120 and vy - 8 < g["y"] < vy + vh - 80:
        return {**size, "x": int(g["x"]), "y": int(g["y"]), "maximized": bool(g.get("maximized"))}
    return size


def main() -> None:
    show_event = single_instance()
    if show_event is None:
        return
    # Свой AppUserModelID до создания окна: иначе панель задач склеивает окно с python.exe и берёт его иконку.
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("L2Hub.Launcher")
    api = Api()
    geo = initial_geometry()
    window = webview.create_window(
        f"L2Hub {__version__}",
        url=f"{paths.UI / 'index.html'}?v={ui_version()}&lang={i18n.lang()}",
        js_api=api,
        min_size=MIN_SIZE,
        frameless=True,
        easy_drag=False,
        background_color=appsettings.THEMES.get(appsettings.load()["theme"], appsettings.THEMES["gold"]),
        **geo,
    )
    api._window = window

    def remember(*_):
        # Только «обычное» состояние: развёрнутое и свёрнутое окно своё место не перезаписывает.
        if api._window_state() == "Normal" and not (api._tray and api._tray.hidden):
            api._geometry = {"x": window.x, "y": window.y, "width": window.width, "height": window.height}

    def on_closing():
        api._save_window()
        if api._tray:
            api._tray.dispose()

    def frame_state(*_):
        # После развёртывания и восстановления WinForms может пересобрать стили — вернуть свои; интерфейс прячет края.
        winframe.fix_styles(window)
        maxed = api._window_state() == "Maximized"
        window.evaluate_js(f"document.body.classList.toggle('maxed', {'true' if maxed else 'false'})")

    last_fit = [0.0]

    def on_moved(*args):
        remember(*args)
        if time.monotonic() - last_fit[0] > 1:  # окно могли перетащить на другой монитор — рабочая область своя
            last_fit[0] = time.monotonic()
            winframe.fit_maximized(window)

    def after_start():
        window.events.shown.wait(30)
        # Безрамочная форма pywebview вычитает из заданного размера системную рамку, которой у неё нет
        # (1320×860 → 1304×821 при 100 %), и окно худело бы с каждым запуском. Ставим размер ещё раз — уже точный.
        if not geo.get("maximized") and (window.width, window.height) != (geo["width"], geo["height"]):
            window.resize(geo["width"], geo["height"])
        # Сворачивание по значку на панели задач, Win+стрелки, развёрнутое — по рабочей области (winframe).
        winframe.fit_maximized(window)
        if geo.get("maximized"):
            window.restore()
            window.maximize()
        frame_state()
        remember()
        window.events.moved += on_moved
        window.events.resized += remember
        window.events.maximized += frame_state
        window.events.restored += frame_state
        window.events.closing += on_closing
        from .tray import Tray  # pythonnet и WinForms — только в окне, не в devserver
        api._tray = Tray(window, api)
        api._tray.start()
        # Повторный запуск L2Hub дёргает событие — показываем окно. Поток фоновый: иначе после закрытия окна
        # процесс не выходит, мьютекс живёт, и следующий run.cmd «показывает» уже уничтоженное окно.
        threading.Thread(target=wait_show, args=(show_event, api), daemon=True).start()

    # icon в докстринге pywebview помечен «только GTK/QT», но WinForms 6.2.1 тоже ставит её форме (_state['icon']).
    webview.start(after_start, debug="--debug" in sys.argv, private_mode=False, icon=str(paths.UI / "assets" / "icon.ico"))


if __name__ == "__main__":
    main()
