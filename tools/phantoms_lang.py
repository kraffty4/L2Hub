"""Что в окнах модуля фантомов осталось без английского.

Строки окон и сообщений модуля (phantoms/core: .buddy, .hunters, //hunters, .daily, .raid, .clans) обёрнуты в tr() из PhantomText.java,
таблица EN — там же, ключ — литерал целиком, как в коде. Новая строка окна: tr("…") в коде и EN.put("…", "…") в таблице. Речь ботов и подсказки
их мозгам не оборачиваются. Доска Alt+B и её окна (они в патчах хроник, а не в модуле) — tools/l2hub_lang.py.

    python -X utf8 tools/phantoms_lang.py   — строки без перевода и с другими пробелами по краям (код возврата 1), лишние ключи таблицы
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "phantoms" / "core"
TABLE = ROOT / "java/org/l2jmobius/gameserver/managers/phantom/PhantomText.java"
LITERAL = r'"((?:[^"\\\n]|\\.)*)"'
# Здесь край уходит вместе с русским хвостом: «41-го уровня включительно» → «41 inclusive», « ур.)» → «)».
EDGE_FREE = ("-го уровня включительно", " ур.). Окно")


def edges(text: str) -> tuple[int, int]:
    """Сколько пробелов слева и справа: строки склеиваются с числами и именами, край терять нельзя."""
    return len(text) - len(text.lstrip(" ")), len(text) - len(text.rstrip(" "))


def main() -> None:
    table = dict(re.findall(r"EN\.put\(" + LITERAL + ", " + LITERAL + r"\);", TABLE.read_text(encoding="utf-8")))
    used = {}
    for path in ROOT.rglob("*.java"):
        for key in re.findall(r"\btr\(" + LITERAL + r"\)", path.read_text(encoding="utf-8")):
            used.setdefault(key, path.name)
    missing = [(key, name) for key, name in used.items() if key not in table]
    extra = [key for key in table if key not in used]
    uneven = [key for key, value in table.items() if edges(key) != edges(value) and not key.startswith(EDGE_FREE)]
    print(f"строк в коде {len(used)}, в таблице {len(table)}, без перевода {len(missing)}, с другими краями {len(uneven)}, лишних в таблице {len(extra)}")
    for key, name in missing:
        print(f"  без перевода ({name}): {key}")
    for key in uneven:
        print(f"  другие края: «{key}» → «{table[key]}»")
    for key in extra:
        print(f"  лишний ключ: {key}")
    sys.exit(1 if missing or uneven else 0)


if __name__ == "__main__":
    main()
