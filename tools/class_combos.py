"""Связки классов для охотников (docs/PHANTOMS.md, раздел 4.153): из базы знаний data/knowledge/h5_class_playbooks_*.json (вне git)
в данные модуля phantoms/ct26_highfive/dist/game/data/L2HubClassCombos.xml. В файл идут только id классов и скиллов и их порядок,
текста гайдов там нет. Берутся скиллы вида «атака» в порядке связки; пункты с "source": "вывод" — догадка модели, не знание, они
пропускаются. Пачка пилота (pilot) уступает своей второй версии (v2), остальные пачки (b1, b2, …) берутся как есть; класс из двух
пачек — из более поздней по имени. Класс без связки в файл не попадает: у него автобой как раньше.

Запуск: python -X utf8 tools/class_combos.py [--check]
"""
import glob
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "data", "knowledge")
OUT = os.path.join(ROOT, "phantoms", "ct26_highfive", "dist", "game", "data", "L2HubClassCombos.xml")
ATTACK = "атака"
GUESS = "вывод"


def packs():
    files = sorted(glob.glob(os.path.join(SRC, "h5_class_playbooks_*.json")))
    names = {os.path.basename(f) for f in files}
    if "h5_class_playbooks_v2.json" in names:
        files = [f for f in files if not f.endswith("_pilot.json")]
    # Пилот и v2 раньше пачек b*: при совпадении класса берётся поздняя.
    return sorted(files, key=lambda f: (0 if f.endswith(("_pilot.json", "_v2.json")) else 1, os.path.basename(f)))


def build():
    classes = {}
    for f in packs():
        data = json.load(open(f, encoding="utf-8"))
        for c in data.get("classes", []):
            ids = []
            for s in c.get("skills", []):
                if (s.get("kind") == ATTACK) and (s.get("source") != GUESS) and isinstance(s.get("id"), int) and (s["id"] not in ids):
                    ids.append(s["id"])
            if ids:
                classes[int(c["id"])] = (c["name"].split(" — ")[0].strip(), ids, os.path.basename(f))
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        "<!-- L2Hub: связки классов для автобоя охотников (docs/PHANTOMS.md, раздел 4.153). Сгенерировано tools/class_combos.py из базы знаний: только id и порядок атак. -->",
        "<list>",
    ]
    for cid in sorted(classes):
        name, ids, pack = classes[cid]
        lines.append(f'\t<class id="{cid}" attacks="{",".join(str(i) for i in ids)}" /> <!-- {name}, {pack.replace("h5_class_playbooks_", "").replace(".json", "")} -->')
    lines.append("</list>")
    return "\n".join(lines) + "\n", len(classes)


def main():
    text, count = build()
    if "--check" in sys.argv:
        same = os.path.exists(OUT) and open(OUT, "rb").read() == text.encode("utf-8")
        print("совпадает" if same else "отличается", count, "классов")
        sys.exit(0 if same else 1)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "wb").write(text.encode("utf-8"))
    print(OUT, count, "классов")


if __name__ == "__main__":
    main()
