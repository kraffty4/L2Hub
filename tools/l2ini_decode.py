"""Расшифровка файлов клиента Lineage2Ver413 (l2.ini, .dat). Логика — launcher/l2hub/l2ini.py.

Использование: python -X utf8 -I tools/l2ini_decode.py <зашифрованный файл> <выход>
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
from l2hub.l2ini import decode  # noqa: E402

if __name__ == "__main__":
    key, result = decode(Path(sys.argv[1]).read_bytes())
    Path(sys.argv[2]).write_bytes(result)
    print(f"ok: ключ {key}, {len(result)} байт -> {sys.argv[2]}")
