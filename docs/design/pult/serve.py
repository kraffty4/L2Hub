"""Сервер макета «Пульт»: статика из корня репозитория и /live — живая нагрузка этого компьютера. Только чтение, только 127.0.0.1.
Запуск из корня репозитория, psutil есть в окружении лаунчера: launcher\.venv\Scripts\python -X utf8 docs\design\pult\serve.py 8792
Настоящее окно будет читать то же самое из ядра (docs/design/PULT.md, раздел 3).
"""
import json
import subprocess
import sys
import threading
import time
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[3]
OURS = {"java.exe", "javaw.exe", "mariadbd.exe", "mysqld.exe", "l2.exe", "l2.bin"}
NO_WINDOW = 0x08000000
live: dict = {}


def gpu() -> dict | None:
    """Только NVIDIA. Для других видеокарт настоящему окну нужны счётчики Windows (GPU Adapter Memory)."""
    try:
        p = subprocess.run(["nvidia-smi", "--query-gpu=memory.total,memory.used,utilization.gpu", "--format=csv,noheader,nounits"],
                           capture_output=True, text=True, timeout=5, creationflags=NO_WINDOW)
        total, used, util = (float(x) for x in p.stdout.splitlines()[0].split(","))
        return {"vramTotal": round(total / 1024, 1), "vramUsed": round(used / 1024, 2), "util": util}
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        return None


def scan() -> tuple[list, list, float]:
    """Наши процессы отдельно, остальные — суммой и пятёркой самых больших по имени. Весь обход — около 250 мс, поэтому раз в 3 с."""
    ours, others = [], {}
    for p in psutil.process_iter(["name", "memory_info"]):
        try:
            name, rss, cpu = p.info["name"] or "?", p.info["memory_info"].rss, p.cpu_percent(None)
        except (psutil.Error, AttributeError):
            continue
        if name.lower() in OURS:
            ours.append({"name": name, "gb": round(rss / 2**30, 2), "cpu": round(cpu, 1)})
        else:
            others[name] = others.get(name, 0) + rss
    top = sorted(others.items(), key=lambda x: -x[1])[:5]
    return ours, [{"name": n, "gb": round(v / 2**30, 2)} for n, v in top], 0.0


def loop() -> None:
    psutil.cpu_percent(None)
    n = 0
    io_prev, t_prev = psutil.disk_io_counters(), time.monotonic()
    ours, top, g = [], [], None
    while True:
        time.sleep(1)
        if n % 3 == 0:
            ours, top, _ = scan()
        if n % 2 == 0:
            g = gpu()
        n += 1
        vm = psutil.virtual_memory()
        io, now = psutil.disk_io_counters(), time.monotonic()
        mbs = ((io.read_bytes + io.write_bytes) - (io_prev.read_bytes + io_prev.write_bytes)) / 2**20 / max(now - t_prev, 0.001)
        io_prev, t_prev = io, now
        live.update(ok=True, time=time.time(), cpuPct=psutil.cpu_percent(None), cores=psutil.cpu_count(False) or 1, threads=psutil.cpu_count() or 1,
                    ramTotal=round(vm.total / 2**30, 1), ramUsed=round((vm.total - vm.available) / 2**30, 1),
                    diskFree=round(psutil.disk_usage(str(ROOT)).free / 2**30, 1), diskMbs=round(mbs, 1), gpu=g, ours=ours, top=top)


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] != "/live":
            return super().do_GET()
        data = json.dumps(live, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        return None

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8792
    threading.Thread(target=loop, daemon=True).start()
    print(f"Макет «Пульт»: http://127.0.0.1:{port}/docs/design/pult/", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), partial(Handler, directory=str(ROOT))).serve_forever()
