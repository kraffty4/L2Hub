"""Безрамочное окно — как обычное: растяжение за края и углы, сворачивание по значку на панели задач, Win+стрелки,
развёрнутое окно — по рабочей области, а не поверх панели задач.

pywebview ставит форме WinForms FormBorderStyle.None, а WinForms тогда не даёт окну WS_MINIMIZEBOX и WS_SYSMENU:
без них Windows не сворачивает окно щелчком по значку на панели задач и не слушает Win+↓. Стили возвращаются
вручную (SetWindowLongPtr) — и снова после развёртывания: WinForms может пересобрать их сама. Рамки (WS_THICKFRAME)
нет, поэтому растяжение — свой цикл: края окна в интерфейсе (index.html, .rz) зовут Api.start_resize, а здесь
окно тянется за курсором, пока зажата кнопка мыши. Только для окна: devserver этот модуль не грузит.
"""

import ctypes
import threading
import time
from ctypes import wintypes

# Свой экземпляр user32, а не общий ctypes.windll.user32: argtypes на общем достаются и pywebview, а его move()
# зовёт SetWindowPos с None вместо размеров — с нашими argtypes это ArgumentError, и окно не таскалось за шапку.
user32 = ctypes.WinDLL("user32", use_last_error=True)
user32.GetWindowLongPtrW.restype = ctypes.c_ssize_t
user32.GetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int]
user32.SetWindowLongPtrW.restype = ctypes.c_ssize_t
user32.SetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_ssize_t]
user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT]
user32.GetDpiForWindow.argtypes = [wintypes.HWND]

GWL_STYLE = -16
WS_MINIMIZEBOX, WS_MAXIMIZEBOX, WS_SYSMENU = 0x00020000, 0x00010000, 0x00080000
SWP_NOSIZE, SWP_NOMOVE, SWP_NOZORDER, SWP_NOACTIVATE, SWP_FRAMECHANGED = 0x1, 0x2, 0x4, 0x10, 0x20
VK_LBUTTON, VK_RBUTTON, SM_SWAPBUTTON = 0x01, 0x02, 23
EDGES = {"n", "s", "e", "w", "ne", "nw", "se", "sw"}


def hwnd(window) -> int:
    return int(str(window.native.Handle))


def fix_styles(window) -> None:
    """Вернуть WS_MINIMIZEBOX, WS_MAXIMIZEBOX и WS_SYSMENU: сворачивание по значку на панели задач и Win+стрелки."""
    h = hwnd(window)
    style = user32.GetWindowLongPtrW(h, GWL_STYLE)
    want = style | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU
    if want != style:
        user32.SetWindowLongPtrW(h, GWL_STYLE, want)
        user32.SetWindowPos(h, None, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED)


def fit_maximized(window) -> None:
    """Развёрнутое безрамочное окно иначе закрывает панель задач. MaximizedBounds — относительно монитора:
    Windows считает ptMaxPosition для основного монитора и сама переносит на тот, где окно."""
    from System import Action  # noqa: PLC0415 — pythonnet, только в окне
    from System.Drawing import Rectangle  # noqa: PLC0415
    from System.Windows.Forms import Screen  # noqa: PLC0415

    form = window.native

    def _set():
        scr = Screen.FromHandle(form.Handle)
        wa, b = scr.WorkingArea, scr.Bounds
        form.MaximizedBounds = Rectangle(wa.X - b.X, wa.Y - b.Y, wa.Width, wa.Height)
    form.Invoke(Action(_set))


def start_resize(window, edge: str, min_size: tuple[int, int]) -> None:
    """Тянуть окно за край или угол, пока зажата кнопка мыши. Размер — не меньше min_size (логические пиксели)."""
    if edge not in EDGES:
        raise ValueError(f"нет края {edge!r}")
    h = hwnd(window)
    button = VK_RBUTTON if user32.GetSystemMetrics(SM_SWAPBUTTON) else VK_LBUTTON

    def loop():
        pt, r = wintypes.POINT(), wintypes.RECT()
        user32.GetCursorPos(ctypes.byref(pt))
        user32.GetWindowRect(h, ctypes.byref(r))
        sx, sy, left, top, right, bottom = pt.x, pt.y, r.left, r.top, r.right, r.bottom
        scale = (user32.GetDpiForWindow(h) or 96) / 96
        min_w, min_h = int(min_size[0] * scale), int(min_size[1] * scale)
        last = None
        while user32.GetAsyncKeyState(button) & 0x8000:
            user32.GetCursorPos(ctypes.byref(pt))
            dx, dy = pt.x - sx, pt.y - sy
            l, t, rr, b = left, top, right, bottom
            if "w" in edge:
                l = min(left + dx, right - min_w)
            if "e" in edge:
                rr = max(right + dx, left + min_w)
            if "n" in edge:
                t = min(top + dy, bottom - min_h)
            if "s" in edge:
                b = max(bottom + dy, top + min_h)
            if (l, t, rr, b) != last:
                user32.SetWindowPos(h, None, l, t, rr - l, b - t, SWP_NOZORDER | SWP_NOACTIVATE)
                last = (l, t, rr, b)
            time.sleep(0.01)
    threading.Thread(target=loop, daemon=True).start()
