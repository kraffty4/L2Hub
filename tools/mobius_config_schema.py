"""Схема настроек сборки Mobius в JSON. Логика — launcher/l2hub/configs.py.

Использование: python -X utf8 -I tools/mobius_config_schema.py <папка server> <out.json>
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
from l2hub.configs import build  # noqa: E402

if __name__ == "__main__":
    schema = build(Path(sys.argv[1]))
    Path(sys.argv[2]).write_text(json.dumps(schema, ensure_ascii=False, indent=1), encoding="utf-8")
    keys = sum(len(s["keys"]) for part in schema.values() for f in part for s in f["sections"])
    files = sum(len(part) for part in schema.values())
    print(f"ok: {files} файлов, {keys} ключей -> {sys.argv[2]}")
