"""Дев-режим: тот же интерфейс и тот же Api в обычном браузере — http://127.0.0.1:8770/?dev
Другой порт — аргументом (`python -m l2hub.devserver 8772`): так две сессии смотрят интерфейс параллельно.

Слушает только 127.0.0.1 и принимает только JSON-запросы со своего адреса, чтобы посторонняя
страница в браузере не могла дёрнуть действия лаунчера.
"""

import json
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

from . import paths
from .api import Api

PORT = 8770
WINDOW_ONLY = {"minimize", "toggle_maximize", "close", "hide_to_tray", "start_resize"}
api = Api()
ALLOWED = {n for n in dir(Api) if not n.startswith("_") and n not in WINDOW_ONLY and callable(getattr(Api, n))}


class Handler(SimpleHTTPRequestHandler):
    def do_POST(self):
        origin = self.headers.get("Origin")
        if self.headers.get("Content-Type") != "application/json" or origin not in (None, f"http://127.0.0.1:{PORT}"):
            self.send_error(403)
            return
        name = self.path.removeprefix("/api/")
        if not self.path.startswith("/api/") or name not in ALLOWED:
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        args = json.loads(self.rfile.read(length) or b"[]")
        try:
            body = {"result": getattr(api, name)(*args)}
        except Exception as e:  # noqa: BLE001 — ошибку отдаём интерфейсу как есть
            body = {"error": str(e) or e.__class__.__name__}
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", PORT), partial(Handler, directory=str(paths.UI)))
    print(f"L2Hub дев-режим: http://127.0.0.1:{PORT}/?dev", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) > 1:
        PORT = int(sys.argv[1])
    main()
