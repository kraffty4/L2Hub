"""Заглушка приёмника L2Hub (tools/feedback/Code.gs) для проверки лаунчера: как Apps Script — POST отвечает 302 на /echo,
ответ забирается GET-ом. Голоса — в памяти, запросы и присланные отчёты — data/feedback_mock. Запуск:
`python -X utf8 tools/feedback/mock.py [порт]`, окно с ней — l2hub-devserver-fb в .claude/launch.json (docs/FEEDBACK.md)."""
import base64
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DATA = Path(__file__).resolve().parents[2] / "data" / "feedback_mock"   # data/ — не в git
DATA.mkdir(parents=True, exist_ok=True)
votes = {}          # (install, chronicle)
answers = {}
log = []


def counts():
    out = {}
    for _, ch in votes:
        out[ch] = out.get(ch, 0) + 1
    return out


class H(BaseHTTPRequestHandler):
    def _json(self, o, code=200):
        b = json.dumps(o, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/echo":
            return self._json(answers.pop(q["id"][0], {"ok": False, "error": "нет ответа"}))
        if q.get("action", ["votes"])[0] == "votes":
            return self._json({"ok": True, "votes": counts()})
        self._json({"ok": False, "error": "нет такого действия"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        req = json.loads(self.rfile.read(n).decode("utf-8"))
        log.append({k: (v if k != "report" else {"name": v["name"], "bytes": len(v["data"])}) for k, v in req.items()})
        (DATA / "log.json").write_text(json.dumps(log, ensure_ascii=False, indent=1), encoding="utf-8")
        if req.get("action") == "vote":
            key = (req["install"], req["chronicle"])
            (votes.__setitem__(key, 1) if req.get("on", True) else votes.pop(key, None))
            out = {"ok": True, "on": req.get("on", True), "count": counts().get(req["chronicle"], 0)}
        elif req.get("action") == "feedback":
            if req.get("report"):
                (DATA / req["report"]["name"]).write_bytes(base64.b64decode(req["report"]["data"]))
            out = {"ok": True}
        else:
            out = {"ok": False, "error": "нет такого действия"}
        rid = str(len(log))
        answers[rid] = out
        self.send_response(302)
        self.send_header("Location", f"/echo?id={rid}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *a):
        pass


votes[("seed0000000000000000000000000001", "freya")] = 1
votes[("seed0000000000000000000000000002", "freya")] = 1
votes[("seed0000000000000000000000000001", "c1")] = 1
print(f"заглушка приёмника: http://127.0.0.1:{int(sys.argv[1]) if len(sys.argv) > 1 else 8799}/exec, данные — {DATA}", flush=True)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8799), H).serve_forever()
