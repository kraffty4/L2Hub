"""Макет оболочки v3 в браузере: python docs/design/shell_v3/serve.py → http://127.0.0.1:8791/docs/design/shell_v3/

Отдаёт только папку макета и ассеты лаунчера (обложки, знак) по тем же относительным путям, что и в репо,
чтобы не выставлять наружу data/ с ключом мозгов. Слушает только 127.0.0.1.
"""

import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ALLOWED = ("/docs/design/shell_v3/", "/docs/design/covers/", "/launcher/l2hub/ui/assets/")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8791


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?", 1)[0].split("#", 1)[0]
        if path == "/":
            self.send_response(302)
            self.send_header("Location", ALLOWED[0])
            self.end_headers()
            return
        if not path.startswith(ALLOWED) or ".." in path:
            self.send_error(403)
            return
        super().do_GET()

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Макет оболочки v3: http://127.0.0.1:{PORT}{ALLOWED[0]}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), partial(Handler, directory=str(ROOT))).serve_forever()
