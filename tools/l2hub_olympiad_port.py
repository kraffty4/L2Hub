"""Патч «режим олимпиады до телепорта» для хроники Mobius со старой олимпиадой: patches/<id>/0027-olympiad-mode-before-port.patch.

Старая олимпиада Mobius (Interlude, Epilogue: OlympiadGame.portPlayersToArena) сначала телепортирует бойцов на стадион, а
режим олимпиады и сторону ставит следом. Живому игроку это не мешает: в зону стадиона он входит позже, когда клиент
пришлёт Appearing. Фантом без клиента заканчивает телепорт прямо в teleToLocation (врезка модуля фантомов в
Creature.teleToLocation), и OlympiadStadiumZone.onEnter видит его без режима олимпиады — «посторонний» — и отправляет в
ближайший город. Охотники Interlude 2026-10-22 так дрались «на стадионе» в Руне, в мирной зоне: HP 100 % против 100 % весь
матч. Здесь блок setInOlympiadMode / setOlympiadStart / setOlympiadSide (и что рядом ставится тем же игрокам) переносится
до первого телепорта. У новой олимпиады (High Five, AbstractOlympiadGame) режим и так ставится до телепорта — менять нечего.

    python -X utf8 tools/l2hub_olympiad_port.py <id>           — только собрать и проверить патч (в дерево не пишет)
    python -X utf8 tools/l2hub_olympiad_port.py <id> --write   — собрать патч в patches/<id>/ и наложить на дерево
"""
import re

import l2hub_olympiad_small as small

NAME = "0027-olympiad-mode-before-port.patch"
FILE = "java/org/l2jmobius/gameserver/mechanics/olympiad/OlympiadGame.java"
MARK = "L2Hub: режим олимпиады"
SETTER = re.compile(r"_player(?:One|Two)\.set\w+\(.*\);")


def move(text: bytes) -> tuple[bytes, list[str]]:
    nl = "\r\n" if b"\r\n" in text else "\n"
    lines = text.decode("utf-8").split(nl)
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == "protected boolean portPlayersToArena()")
        port = next(i for i in range(start, len(lines)) if lines[i].strip() == "_playerOne.setInstanceId(0);")
        first = next(i for i in range(port, len(lines)) if lines[i].strip() == "_playerOne.setInOlympiadMode(true);")
    except StopIteration:
        return text, []
    end = first
    while end < len(lines) and (SETTER.fullmatch(lines[end].strip()) or not lines[end].strip()):
        end += 1
    while not lines[end - 1].strip():
        end -= 1
    block = lines[first:end]
    if not any(l.strip() == "_playerTwo.setInOlympiadMode(true);" for l in block):
        return text, []
    indent = lines[port][:len(lines[port]) - len(lines[port].lstrip())]
    blank = lines[first - 1] if not lines[first - 1].strip() else indent
    note = [indent + f"// {MARK} и сторона — до телепорта: фантом без клиента входит в зону стадиона прямо в teleToLocation,",
            indent + "// и OlympiadStadiumZone без режима отправила бы его в город (tools/l2hub_olympiad_port.py)."]
    # Пустая строка перед блоком уходит вместе с ним, иначе на старом месте их стало бы две подряд.
    moved = lines[port:first - 1] if not lines[first - 1].strip() else lines[port:first]
    out = lines[:port] + note + block + [blank] + moved + lines[end:]
    return nl.join(out).encode("utf-8"), [f"{len([l for l in block if l.strip()])} строк режима — до телепорта"]


if __name__ == "__main__":
    small.run(__doc__, FILE, NAME, move, MARK)
