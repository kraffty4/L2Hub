"""Открытый ini для клиентов 4game (Essence, Classic): штатный L2.exe запускается с -INI=<файл>.

Сам код — launcher/l2hub/clientsys.py (лаунчер делает L2Hub.ini сам, когда указывают клиент); здесь — то же
из консоли. Что меняется в ini — там же, в описании модуля.

Использование: python -X utf8 -I tools/l2ini_plain.py <system\\L2.ini> <system\\L2Hub.ini> [адрес]
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
from l2hub.clientsys import EDITS, OPTIONAL, plain_ini  # noqa: E402, F401 — EDITS и OPTIONAL — для старых импортов

if __name__ == "__main__":
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    addr = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
    out.write_bytes(plain_ini(src.read_bytes(), addr))
    print(f"ok: {out} (ServerAddr={addr})")
