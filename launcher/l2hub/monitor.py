"""Живая нагрузка компьютера для полосок окна (docs/design/PULT.md, раздел 3): сколько ЦПУ, ОЗУ, диска и видеопамяти занято прямо сейчас и кем.
Ядро без окна, читает только локально. Нити живут, пока окно спрашивает: замолчало на IDLE_STOP секунд — останавливаются.
Наше отличается от чужого по PID, а не по имени: java.exe из чужой программы или соседнего прогона — другая программа.

Проверка без окна: python -X utf8 -m l2hub.monitor [секунд]
"""

from __future__ import annotations

import csv
import ctypes
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import psutil

from . import paths, servers

GB = 2**30
IDLE_STOP = 10  # с без вопросов от окна — нити останавливаются
TOP_KEEP = 3  # с: столько после просьбы окна обход ещё идёт; сам обход дорогой (полсекунды на 380 процессах), частоту задаёт окно
TOP_N = 5
GPU_FAST, GPU_SLOW = 2, 10  # с: NVML — микросекунды, счётчик Windows через typeperf — больше секунды
GPU_CLASS = r"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}"  # класс «Видеоадаптеры» в реестре

_lock = threading.Lock()
_thread: threading.Thread | None = None
_asked = 0.0
_ours: dict[str, int] = {}
_top_until = 0.0
_top_every = 0
_state: dict = {"ok": False}
_gpu: dict | None = None
_top: dict = {"ram": [], "cpu": []}


def ours_of(p: dict) -> dict[str, int]:
    """Наши процессы хроники: {роль: PID}. Логин и гейм L2Solo — один процесс, он идёт одной строкой game."""
    out: dict[str, int] = {}
    for part in ("game", "login", "client", "brain"):
        try:
            proc = servers.part_process(p, part)
        except (OSError, KeyError, ValueError, psutil.Error):
            proc = None
        if proc and proc.pid not in out.values():
            out[part] = proc.pid
    port = p.get("ports", {}).get("db")
    if port:
        pid = next((pid for _, pid in servers.listeners(port) if pid), None)
        if pid and pid not in out.values():
            out["db"] = pid
    return out


# ---------- Видеокарта ----------


class _Memory(ctypes.Structure):
    _fields_ = [("total", ctypes.c_ulonglong), ("free", ctypes.c_ulonglong), ("used", ctypes.c_ulonglong)]


class _Rates(ctypes.Structure):
    _fields_ = [("gpu", ctypes.c_uint), ("memory", ctypes.c_uint)]


def _nvml():
    """Чтение NVIDIA через NVML: библиотека ставится с драйвером, без отдельного процесса nvidia-smi. Нет её — None. Путь полный: чужой nvml.dll из рабочей папки не подхватим."""
    root = Path(os.environ.get("SystemRoot", r"C:\Windows"))
    for path in (root / "System32" / "nvml.dll", Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "NVIDIA Corporation" / "NVSMI" / "nvml.dll"):
        if not path.is_file():
            continue
        try:
            lib = ctypes.WinDLL(str(path))
            if lib.nvmlInit_v2() != 0:
                continue
            count = ctypes.c_uint()
            if lib.nvmlDeviceGetCount_v2(ctypes.byref(count)) != 0 or not count.value:
                continue
            best, best_total = None, -1
            for i in range(count.value):
                handle, mem = ctypes.c_void_p(), _Memory()
                if lib.nvmlDeviceGetHandleByIndex_v2(ctypes.c_uint(i), ctypes.byref(handle)) == 0 and lib.nvmlDeviceGetMemoryInfo(handle, ctypes.byref(mem)) == 0 and mem.total > best_total:
                    best, best_total = handle, mem.total
        except (OSError, AttributeError):
            continue
        if best is None:
            continue

        def read(lib=lib, handle=best) -> dict | None:
            mem, rates = _Memory(), _Rates()
            if lib.nvmlDeviceGetMemoryInfo(handle, ctypes.byref(mem)) != 0:
                return None
            util = rates.gpu if lib.nvmlDeviceGetUtilizationRates(handle, ctypes.byref(rates)) == 0 else None
            return {"totalGb": round(mem.total / GB, 1), "usedGb": round(mem.used / GB, 2), "util": util, "source": "nvml"}

        return read
    return None


def _registry_vram() -> float | None:
    """Объём видеопамяти самой большой карты из реестра: драйвер пишет его в HardwareInformation.qwMemorySize. У встроенных карт значения может не быть."""
    try:
        import winreg  # noqa: PLC0415 — есть только на Windows

        best = 0
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, GPU_CLASS) as root:
            for i in range(winreg.QueryInfoKey(root)[0]):
                try:
                    with winreg.OpenKey(root, winreg.EnumKey(root, i)) as key:
                        size = winreg.QueryValueEx(key, "HardwareInformation.qwMemorySize")[0]
                except OSError:
                    continue
                best = max(best, int.from_bytes(size, "little") if isinstance(size, bytes) else int(size))
    except (ImportError, OSError, ValueError):
        return None
    return round(best / GB, 1) if best else None


def _counter_vram() -> float | None:
    """Занятая видеопамять по счётчику Windows, для карт без NVML: самый занятый адаптер, ГБ. На NVIDIA сошлось с NVML, на AMD и Intel не проверялось."""
    try:
        out = subprocess.run(["typeperf", r"\GPU Adapter Memory(*)\Dedicated Usage", "-sc", "1"], capture_output=True, timeout=15, creationflags=subprocess.CREATE_NO_WINDOW).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    rows = [r for r in csv.reader(out.decode("ascii", "replace").splitlines()) if len(r) > 1]
    if len(rows) < 2:  # заголовок и строка значений; локаль может писать дробную часть через запятую
        return None
    try:
        return round(max(float(x.replace(",", ".")) for x in rows[1][1:] if x.strip()) / GB, 2)
    except ValueError:
        return None


def _gpu_loop() -> None:
    global _gpu
    read = _nvml()
    total = None if read else _registry_vram()
    while time.monotonic() - _asked < IDLE_STOP:
        if read:
            _gpu = read()
        else:
            used = _counter_vram()
            _gpu = {"totalGb": total, "usedGb": used, "util": None, "source": "counter"} if total and used is not None else None
        time.sleep(GPU_FAST if read else GPU_SLOW)


# ---------- ЦПУ, ОЗУ, диск, процессы ----------


def _scan_top(skip: set[int]) -> dict:
    """Самые большие чужие процессы: по памяти и по ЦПУ, по имени (у браузера их десятки)."""
    ram: dict[str, int] = {}
    cpu: dict[str, float] = {}
    for proc in psutil.process_iter(["name", "memory_info"]):
        if not proc.pid or proc.pid in skip:  # PID 0 — бездействие системы
            continue
        try:
            name, info, used = proc.info["name"] or "?", proc.info["memory_info"], proc.cpu_percent(None)
        except psutil.Error:
            continue
        if info:
            ram[name] = ram.get(name, 0) + info.rss
        cpu[name] = cpu.get(name, 0.0) + used
    return {"ram": [{"name": n, "gb": round(v / GB, 2)} for n, v in sorted(ram.items(), key=lambda x: -x[1])[:TOP_N]],
            "cpu": [{"name": n, "threads": round(v / 100, 2)} for n, v in sorted(cpu.items(), key=lambda x: -x[1])[:TOP_N] if v >= 1]}


def _loop() -> None:
    global _state, _top
    threading.Thread(target=_gpu_loop, daemon=True).start()
    psutil.cpu_percent(None)
    procs: dict[int, psutil.Process] = {}
    io_prev, t_prev = psutil.disk_io_counters(), time.monotonic()
    top_at = 0.0
    while time.monotonic() - _asked < IDLE_STOP:
        time.sleep(1)
        now = time.monotonic()
        ours_map = dict(_ours)
        rows = []
        for part, pid in ours_map.items():
            try:
                proc = procs.get(pid) or procs.setdefault(pid, psutil.Process(pid))
                rows.append({"part": part, "name": proc.name(), "pid": pid, "gb": round(proc.memory_info().rss / GB, 2), "threads": round(proc.cpu_percent(None) / 100, 2)})
            except psutil.Error:
                procs.pop(pid, None)
        for pid in set(procs) - set(ours_map.values()):
            procs.pop(pid, None)
        if now < _top_until and now - top_at >= _top_every:
            _top, top_at = _scan_top(set(ours_map.values())), now
        vm = psutil.virtual_memory()
        threads = psutil.cpu_count() or 1
        pct = psutil.cpu_percent(None)
        ours_threads = sum(r["threads"] for r in rows)
        ours_gb = sum(r["gb"] for r in rows)
        used_gb = (vm.total - vm.available) / GB
        io = psutil.disk_io_counters()
        mbs = None
        if io and io_prev:
            mbs = round(((io.read_bytes + io.write_bytes) - (io_prev.read_bytes + io_prev.write_bytes)) / 2**20 / max(now - t_prev, 0.001), 1)
        io_prev, t_prev = io, now
        disk = psutil.disk_usage(str(paths.HOME))
        _state = {"ok": True, "time": time.time(),
                  "cpu": {"pct": round(pct, 1), "cores": psutil.cpu_count(logical=False) or threads, "threads": threads,
                          "oursThreads": round(ours_threads, 2), "othersThreads": round(max(0.0, pct / 100 * threads - ours_threads), 2)},
                  "ram": {"totalGb": round(vm.total / GB, 1), "usedGb": round(used_gb, 2), "oursGb": round(ours_gb, 2), "othersGb": round(max(0.0, used_gb - ours_gb), 2)},
                  "disk": {"totalGb": round(disk.total / GB, 1), "usedGb": round(disk.used / GB, 1), "freeGb": round(disk.free / GB, 1), "mbs": mbs},
                  "ours": rows}


def snapshot(ours: dict[str, int] | None = None, top_every: int = 0) -> dict:
    """Последние значения; нити запускает первый вопрос, поэтому первый ответ — {"ok": false}. ours — наши процессы {роль: PID},
    top_every — ещё и самые большие чужие: раз во сколько секунд обходить все процессы, 0 — не обходить."""
    global _thread, _asked, _ours, _top_until, _top_every
    now = time.monotonic()
    with _lock:
        _asked = now
        _ours = ours or {}
        if top_every:
            _top_until, _top_every = now + TOP_KEEP, top_every
        if not (_thread and _thread.is_alive()):
            _thread = threading.Thread(target=_loop, daemon=True)
            _thread.start()
    return {**_state, "gpu": _gpu, "top": _top if top_every else None}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 7
    t0 = time.perf_counter()
    for _ in range(seconds):
        result = snapshot({}, 5)
        time.sleep(1)
    print(json.dumps(result, ensure_ascii=False, indent=1))
    print(f"NVML: {'есть' if _nvml() else 'нет'}; реестр: {_registry_vram()} ГБ; счётчик Windows: {_counter_vram()} ГБ")
