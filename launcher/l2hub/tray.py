"""Значок L2Hub в трее: окно прячется туда и возвращается, меню и уведомление «гейм-сервер остановился сам».

WinForms NotifyIcon через pythonnet: на WinForms и так работает окно pywebview, лишних зависимостей нет.
Всё, что трогает WinForms, идёт в поток окна через form.Invoke. Только для окна: devserver этот модуль не грузит.
"""

import threading
import time

import clr  # pythonnet, его загружает pywebview

clr.AddReference("System.Windows.Forms")
clr.AddReference("System.Drawing")
from System import Action  # noqa: E402
from System.Drawing import Color, Font, FontStyle, Icon, SystemIcons  # noqa: E402
from System.Windows.Forms import (  # noqa: E402
    ContextMenuStrip, FormWindowState, MouseButtons, NotifyIcon, SystemInformation, ToolStripMenuItem, ToolStripSeparator, ToolTipIcon,
)

from . import appsettings, catalog, health, paths, servers  # noqa: E402
from .i18n import t  # noqa: E402

POLL = 3  # с, как часто обновлять подпись и смотреть, не упал ли гейм
BG, FG, MUTED = Color.FromArgb(28, 23, 18), Color.FromArgb(236, 226, 204), Color.FromArgb(160, 146, 120)


class Tray:
    def __init__(self, window, api):
        self._window = window
        self._api = api
        self._icon = None
        self._items = {}
        self._watch = {}  # что было в прошлый опрос: {"id": хроника, "game": "on"/…}
        self.hidden = False

    # ---------- Поток окна ----------

    def _ui(self, fn) -> None:
        form = self._window.native
        if form is not None and not form.IsDisposed:
            form.Invoke(Action(fn))

    def start(self) -> None:
        self._ui(self._create)
        threading.Thread(target=self._poll, daemon=True).start()

    def _create(self) -> None:
        icon = NotifyIcon()
        try:
            icon.Icon = Icon(str(paths.UI / "assets" / "icon.ico"), SystemInformation.SmallIconSize)
        except Exception:  # noqa: BLE001 — без своей иконки трей всё равно нужен
            icon.Icon = SystemIcons.Application
        icon.Text = "L2Hub"
        menu = ContextMenuStrip()
        menu.BackColor, menu.ForeColor, menu.ShowImageMargin = BG, FG, False
        menu.Font = Font("Segoe UI", 9.5)

        def item(key, text, handler=None, bold=False, enabled=True):
            it = ToolStripMenuItem(t(text))
            it.BackColor, it.ForeColor, it.Enabled = BG, FG, enabled
            if bold:
                it.Font = Font(menu.Font, FontStyle.Bold)
            if handler:
                it.Click += lambda s, e: self._safe(handler)
            menu.Items.Add(it)
            self._items[key] = it
            return it

        item("status", "Ничего не запущено", enabled=False)
        menu.Items.Add(ToolStripSeparator())
        item("open", "Открыть L2Hub", self.show_window, bold=True)
        item("client", "Запустить клиент", self._client, enabled=False)
        item("world", "Мой мир", self._world)
        menu.Items.Add(ToolStripSeparator())
        item("stop", "Остановить сервер", self._stop, enabled=False)
        item("quit", "Выйти", self._quit)
        icon.ContextMenuStrip = menu
        icon.MouseClick += lambda s, e: self._safe(self.show_window) if e.Button == MouseButtons.Left else None
        icon.Visible = True
        self._icon = icon

    def dispose(self) -> None:
        def _dispose():
            if self._icon is not None:
                self._icon.Visible = False
                self._icon.Dispose()
                self._icon = None
        try:
            self._ui(_dispose)
        except Exception:  # noqa: BLE001 — окно уже закрыто
            pass

    def notify(self, title: str, text: str, warn: bool = False) -> None:
        self._ui(lambda: self._icon and self._icon.ShowBalloonTip(6000, t(title), t(text), ToolTipIcon.Warning if warn else ToolTipIcon.Info))

    # ---------- Окно ----------

    def hide_window(self) -> None:
        self.hidden = True
        self._window.hide()
        if not self._watch.get("hintShown"):
            self._watch["hintShown"] = True
            self.notify("L2Hub в трее", "Сервер работает дальше. Окно — щелчком по значку.")

    def show_window(self) -> None:
        self.hidden = False
        self._window.show()

        def _front():
            form = self._window.native
            if form.WindowState == FormWindowState.Minimized:
                form.WindowState = FormWindowState.Normal
            form.Activate()
        self._ui(_front)

    def _js(self, code: str) -> None:
        self.show_window()
        self._window.evaluate_js(code)

    # ---------- Меню ----------

    def _safe(self, fn) -> None:
        threading.Thread(target=self._guard, args=(fn,), daemon=True).start()  # не держим поток окна

    def _guard(self, fn) -> None:
        try:
            fn()
        except Exception as e:  # noqa: BLE001 — ошибку показываем всплывашкой, а не роняем трей
            self.notify("L2Hub", str(e) or e.__class__.__name__, warn=True)

    def _client(self) -> None:
        running = self._api.running()
        if running:
            r = self._api.launch_client(running)
            if not r.get("ok"):
                self.notify("Клиент не запустился", r.get("error", ""), warn=True)

    def _world(self) -> None:
        self._js("window.openWorld && window.openWorld()")

    def _stop(self) -> None:
        running = self._api.running()
        if running:
            r = self._api.stop(running)
            if not r.get("ok"):
                self.notify("Не остановить", r.get("error", ""), warn=True)

    def _quit(self) -> None:
        if self._api.running():
            self._js("window.askClose && window.askClose()")  # окно спросит, что делать с сервером
        else:
            self._api.close()

    # ---------- Опрос ----------

    def _poll(self) -> None:
        while self._icon is not None:
            try:
                self._tick()
            except Exception:  # noqa: BLE001 — опрос живёт, пока живо окно
                pass
            time.sleep(POLL)

    def _tick(self) -> None:
        running = self._api.running()
        p = catalog.profile(running) if running else None
        game = health.state(p)["game"] if p else {"state": "off"}
        name = next((c["name"] for c in catalog.load_catalog() if c["id"] == running), running) if running else ""
        if running:
            up = game.get("uptime_s")
            status = f"{name} · " + ({"on": "работает", "starting": "запускается"}.get(game["state"], "без гейма"))
            if up:
                status += f" · {up // 3600} ч {up // 60 % 60} мин" if up >= 3600 else f" · {up // 60} мин"
        else:
            status = "Ничего не запущено"
        status = t(status)
        client_on = bool(p and servers.part_process(p, "client"))

        def _update():
            if self._icon is None:
                return
            self._items["status"].Text = status
            self._items["client"].Enabled = bool(running and game["state"] == "on" and not client_on)
            self._items["stop"].Enabled = bool(running)
            self._icon.Text = ("L2Hub · " + status)[:63]  # у NotifyIcon предел 63 знака
        self._ui(_update)
        self._check_crash(running, game, p)

    def _check_crash(self, running, game, p) -> None:
        """Гейм был «on», а теперь нет, и остановку не просили из L2Hub (кнопки, трей, аварийная) — сказать всплывашкой.
        Штатную остановку из консоли отличаем по логу гейма: Mobius пишет «successfully shut down»."""
        prev = self._watch.get("game")  # (хроника, состояние гейма) прошлого опроса
        self._watch["game"] = (running, game["state"]) if running else None
        if not prev or prev[1] != "on" or (running == prev[0] and game["state"] == "on"):
            return
        if time.monotonic() - self._api._stop_requested_at < 180 or not appsettings.load()["notifyCrash"]:
            return
        prof = p if running == prev[0] else catalog.profile(prev[0])
        graceful = "shut down" in " ".join(health.log_tail(prof, "game", 15))
        chron = next((c["name"] for c in catalog.load_catalog() if c["id"] == prev[0]), prev[0])
        if graceful:
            self.notify(f"{chron}: гейм-сервер остановлен", "Остановили не из L2Hub — сохранение прошло штатно.")
        else:
            self.notify(f"{chron}: гейм-сервер упал", "Процесса больше нет, а штатной остановки в логе нет. Подробности — «Живой лог».", warn=True)
