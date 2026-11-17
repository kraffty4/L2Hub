"""Русские названия точек телепорта: таблица, сверка и файл для сервера (docs/RU_PAGES.md, «Список телепортов»).

Список телепортов у хранителя врат (и у всех NPC со списком из data/teleporters) сервер строит в Java
(TeleportHolder.buildTeleportList), поэтому русские страницы его не переводят. Названия берутся из
data/lang/<язык>/TeleportNameLocalisation.xml по языку игрока (патч NNNN-teleport-names-ru хроники): ключ — английское
название из data/teleporters, без перевода остаётся английское.

Таблица — tools/ru_pages/teleport_names.tsv («английское<TAB>русское»), одна на все хроники с русскими диалогами
(High Five, Gracia Epilogue): одно место — одно название.

    python -X utf8 tools/teleport_names.py check --data <dist/game/data> [--tm tools/ru_pages/tm_ct26_highfive.tsv]
        названия датапака без перевода, глоссарий (docs/RU_GLOSSARY.md), символы, расхождение с памятью переводов;
        код 1 при ошибке. Где название входит в переведённый кусок памяти, а его русская основа — нет, печатается
        отдельно (швы, не ошибки).
    python -X utf8 tools/teleport_names.py xml --data <dist/game/data> --out <TeleportNameLocalisation.xml>
        файл для data/lang/ru: только названия этого датапака, по алфавиту, CRLF, как NpcNameLocalisation.xml.
"""
import argparse
import re
import sys
from pathlib import Path
from xml.sax.saxutils import quoteattr

import ru_pages_check as rc

REPO = Path(__file__).resolve().parent.parent
TABLE = REPO / "tools" / "ru_pages" / "teleport_names.tsv"
# Плата за телепорт: имя предмета после суммы («- 1000 аден»). Чего нет — остаётся имя предмета из датапака.
# Olympiad Token в глоссарии латиницей (как в сумке), поэтому здесь его нет.
FEE_ITEMS = {57: "аден"}
ROMAN = {"I", "II", "III", "IV", "V"}
FORBIDDEN = set("ё«»—…")  # docs/RU_GLOSSARY.md, «Символы»
LOCATION = re.compile(r'<location\b[^>]*?\bname="([^"]*)"')


def load_table(path: Path = TABLE) -> dict[str, str]:
    out = {}
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        en, ru = line.split("\t")
        if en in out:
            raise SystemExit(f"{path.name}:{n}: {en!r} уже есть")
        out[en] = ru
    return out


def datapack_names(data: Path) -> dict[str, int]:
    names: dict[str, int] = {}
    files = sorted((data / "teleporters").rglob("*.xml"))
    if not files:
        raise SystemExit(f"нет {data / 'teleporters'}")
    for f in files:
        for m in LOCATION.finditer(f.read_text(encoding="utf-8")):
            names[m.group(1)] = names.get(m.group(1), 0) + 1
    return names


def check(data: Path, tm_path: Path | None) -> int:
    table = load_table()
    names = datapack_names(data)
    glossary = rc.Glossary(rc.GLOSSARY)
    tm = rc.load_tm(tm_path) if tm_path else {}
    errors = []
    for en in sorted(names):
        ru = table.get(en)
        if ru is None:
            errors.append(f"нет перевода: {en!r} ({names[en]} записей)")
            continue
        if bad := set(ru) - rc.ALLOWED or set(ru) & FORBIDDEN:
            errors.append(f"символы {''.join(sorted(bad))!r}: {en!r} -> {ru!r}")
        latin = [w for w in rc.LATIN.findall(ru) if w not in ROMAN and w not in glossary.latin]
        if latin:
            errors.append(f"латиница {latin}: {en!r} -> {ru!r}")
        if missing := glossary.missing(en, ru):
            errors.append(f"глоссарий {missing}: {en!r} -> {ru!r}")
        if en in tm and tm[en] != ru:
            errors.append(f"память переводов: {en!r} -> {tm[en]!r}, в таблице {ru!r}")
    seams = []
    if tm:
        # Длинное закрывает вложенное, как в глоссарии: Elven Village внутри Dark Elven Village — не оно.
        terms = sorted({t for t, _, _ in glossary.terms} | {n for n in names if n in table}, key=len, reverse=True)
        pats = [(t, re.compile(rf"(?<![A-Za-z']){re.escape(t)}(?![A-Za-z])")) for t in terms]
        stems = {en: max(rc.LATIN.sub(" ", table[en]).split(), key=len).lower()[:-2] for en in names if en in table}
        for k, v in tm.items():
            if k in names or not any(n in k for n in stems):
                continue
            text = k
            for t, pat in pats:
                if t in text and pat.search(text):
                    text = pat.sub(" ", text)
                    if t in stems and len(t) >= 6 and stems[t] not in v.lower():
                        seams.append(f"{t!r} ({table[t]}) в куске {k[:70]!r} -> {v[:70]!r}")
    unused = sorted(set(table) - set(names))
    print(f"датапак: {sum(names.values())} записей, {len(names)} названий; в таблице {len(table)}, "
          f"из них в этом датапаке нет {len(unused)}")
    for line in unused:
        print(f"  не в датапаке: {line!r}")
    if tm:
        print(f"швы с памятью переводов: {len(seams)}")
        for line in seams:
            print(f"  {line}")
    print(f"ошибок: {len(errors)}")
    for line in errors:
        print(f"  {line}")
    return 1 if errors else 0


def xml(data: Path, out: Path) -> int:
    table = load_table()
    names = datapack_names(data)
    lost = sorted(n for n in names if n not in table)
    if lost:
        raise SystemExit(f"нет перевода у {len(lost)}: {lost[:10]}")
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<list xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="../../xsd/TeleportNameLocalisation.xsd">',
             "\t<!-- L2Hub: tools/teleport_names.py из tools/ru_pages/teleport_names.tsv; ключ - название из data/teleporters -->"]
    lines += [f"\t<location name={quoteattr(en)} translation={quoteattr(table[en])} />" for en in sorted(names)]
    lines += [f'\t<item id="{i}" name={quoteattr(n)} />' for i, n in sorted(FEE_ITEMS.items())]
    lines.append("</list>")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(("\r\n".join(lines) + "\r\n").encode("utf-8"))
    print(f"{out}: {len(names)} названий, {len(FEE_ITEMS)} предмет(ов) платы")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("cmd", choices=["check", "xml"])
    ap.add_argument("--data", type=Path, required=True, help="dist/game/data датапака")
    ap.add_argument("--tm", type=Path, help="память переводов хроники (check)")
    ap.add_argument("--out", type=Path, help="файл XML (xml)")
    a = ap.parse_args()
    if a.cmd == "check":
        return check(a.data, a.tm)
    if not a.out:
        ap.error("xml: нужен --out")
    return xml(a.data, a.out)


if __name__ == "__main__":
    sys.exit(main())
