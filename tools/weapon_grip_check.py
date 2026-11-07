"""Оружие, на котором у клиента нет анимаций игрока (Т-поза): сверка хватки из weapongrp.dat клиента с типом оружия на сервере.

Клиент выбирает анимацию по хватке (handness) из своей таблицы оружия, а не по типу на сервере. У оружия NPC хватка своя:
у кинжалов игрока она 1, у Lizardspear и Dailaon Knife — 6; у древкового игрока 4, у Giant Bar — 1. Игрок или охотник
с таким оружием в руке стоит в Т-позе. Инструмент выписывает всё оружие, у которого хватка не совпадает с типом,
и где его можно взять: GM-шоп за адену (мультиселлы NPC 50010), списки торговцев, таблица охотников; отмечает,
ловит ли его признак «только для NPC» (PhantomHunterOutfit.npcOnly, NPC_WEAPON_NAMES в tools/fake_players.py).

Формат weapongrp — Gracia Epilogue и High Five (проверено на обоих клиентах). Запись: tag, id, drop_type, drop_anim,
radius, height, 0, затем строки; три dword перед моделью в руке — body_part, handness, число моделей. Полного разбора
структуры нет: записи ищутся двумя способами, оружие попадает в список, если хватку против типа показал хотя бы один,
а второй не возразил.

Использование:
  python -X utf8 -I tools/weapon_grip_check.py <system клиента> <dist/game/data сервера> [L2HubPhantoms.xml]
"""
import glob
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
from l2hub.l2ini import decode  # noqa: E402

# Хватка игрока по типу оружия на сервере (weapon_type).
PLAYER_GRIP = {
    "SWORD": {1, 2}, "BLUNT": {1, 2, 4}, "DAGGER": {1}, "POLE": {4}, "BOW": {5}, "CROSSBOW": {8},
    "RAPIER": {9}, "ANCIENTSWORD": {2}, "DUAL": {3}, "DUALDAGGER": {10}, "DUALFIST": {7},
    "FISHINGROD": {4}, "FLAG": {4}, "OWNTHING": {4}, "ETC": {1},
}
PET_PARTS = {"wolf", "hatchling", "strider", "greatwolf", "babypet"}
MON_ICON = re.compile(r"(weapon|shield)_mon_")
NPC_PREFIXES = ("NPC only", "Monster Only", "Exclusive to Monsters", "For Monster", "For NPC")


def server_weapons(data_root):
    weapons = {}
    for f in glob.glob(str(Path(data_root) / "stats" / "items" / "*.xml")):
        for item in ET.parse(f).getroot().iter("item"):
            if item.get("type") != "Weapon":
                continue
            sets = {s.get("name"): s.get("val") for s in item.iter("set")}
            weapons[int(item.get("id"))] = {
                "name": item.get("name") or "",
                "type": sets.get("weapon_type"),
                "grade": sets.get("crystal_type") or "NG",
                "body": sets.get("bodypart"),
                "for_npc": sets.get("for_npc") == "true",
                "icon": sets.get("icon") or "",
            }
    return weapons


def npc_names():
    """NPC_WEAPON_NAMES из генератора — тот же список, что в ядре."""
    text = (Path(__file__).resolve().parent / "fake_players.py").read_text(encoding="utf-8")
    m = re.search(r"NPC_WEAPON_NAMES\s*=\s*frozenset\(\{(.*?)\}\)", text, re.S)
    return set(re.findall(r'"([^"]+)"', m.group(1))) if m else set()


def npc_only(w, names):
    return (w["for_npc"] or "weapon_monster" in w["icon"] or bool(MON_ICON.search(w["icon"]))
            or w["name"].startswith(NPC_PREFIXES) or w["name"] in names)


class Grp:
    """Строки и записи расшифрованного weapongrp."""

    def __init__(self, data, ids):
        self.data = data
        self.n = len(data)
        self.ids = ids

    def string_at(self, q):
        data = self.data
        if q + 4 > self.n:
            return None
        size = int.from_bytes(data[q:q + 4], "little")
        if size == 0:
            return ""
        if 4 <= size <= 400 and not size & 1 and q + 4 + size <= self.n:
            b = data[q + 4:q + 4 + size]
            if all(b[i + 1] == 0 and 32 <= b[i] < 127 for i in range(0, size, 2)):
                return b[::2].decode("ascii")
        return None

    def header_ok(self, p):
        tag, iid, dt, da, rad, hei, unk = struct.unpack_from("<7I", self.data, p)
        return iid in self.ids and tag < 256 and dt < 256 and da < 256 and rad < 5000 and hei < 5000, iid, unk

    def chain(self, cands):
        heads, last = [], 0
        for p, iid in sorted(cands):
            if iid > last:
                heads.append((p, iid))
                last = iid
        return heads

    def heads_by_mesh(self):
        """Способ 1: заголовок перед первой строкой записи, строка — модель на земле (Lineage…)."""
        cands = []
        for q in range(32, self.n - 8, 2):
            s = self.string_at(q)
            if s and s.startswith("Lineage"):
                ok, iid, _ = self.header_ok(q - 28)
                if ok:
                    cands.append((q - 28, iid))
        return self.chain(cands)

    def heads_by_header(self):
        """Способ 2: заголовок с нулём перед первой строкой, строка может быть пустой."""
        cands = []
        for p in range(4, self.n - 40, 2):
            if int.from_bytes(self.data[p + 4:p + 8], "little") not in self.ids:
                continue
            ok, iid, unk = self.header_ok(p)
            if ok and unk == 0 and self.string_at(p + 28) is not None:
                cands.append((p, iid))
        return self.chain(cands)

    def grips(self, heads):
        """id → (хватка, модель в руке)."""
        res = {}
        for i, (p, iid) in enumerate(heads):
            end = heads[i + 1][0] if i + 1 < len(heads) else self.n
            q = p + 28
            while q < end - 4:
                s = self.string_at(q)
                if not s:
                    q += 2
                    continue
                pkg = s.split(".")[0]
                if (s.startswith(("Lineage", "Branch")) and not any(k in pkg for k in ("Tex", "Sys", "Effect", "Sound"))
                        and q - 12 > p):
                    body, grip, count = struct.unpack_from("<3i", self.data, q - 12)
                    if 1 <= count <= 4 and 0 <= grip < 32 and 0 <= body < 64:
                        res[iid] = (grip, s)
                        break
                q += 4 + len(s) * 2
        return res


def gm_goods(data_root):
    goods = {}
    for d in ("multisell/custom", "multisell"):
        for f in sorted(glob.glob(str(Path(data_root) / d / "*.xml"))):
            try:
                root = ET.parse(f).getroot()
            except ET.ParseError:
                continue
            if "50010" not in [n.text.strip() for n in root.iter("npc") if n.text]:
                continue
            for item in root.iter("item"):
                ing, prod = item.findall("ingredient"), item.findall("production")
                if len(ing) == 1 and len(prod) == 1 and ing[0].get("id") == "57":
                    goods.setdefault(int(prod[0].get("id")), (Path(f).name, int(ing[0].get("count"))))
    return goods


def buylists(data_root):
    lists = {}
    for d in ("buylists/custom", "buylists"):
        for f in sorted(glob.glob(str(Path(data_root) / d / "*.xml"))):
            for item in ET.parse(f).getroot().iter("item"):
                lists.setdefault(int(item.get("id")), Path(f).name)
    return lists


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    system, data_root = Path(sys.argv[1]), Path(sys.argv[2])
    hunters = Path(sys.argv[3]).read_text(encoding="utf-8") if len(sys.argv) > 3 else ""
    weapons = server_weapons(data_root)
    _, raw = decode((system / "weapongrp.dat").read_bytes())
    grp = Grp(raw, set(weapons))
    first = grp.grips(grp.heads_by_mesh())
    second = grp.grips(grp.heads_by_header())
    names = npc_names()
    gm, buy = gm_goods(data_root), buylists(data_root)
    print(f"Оружие сервера: {len(weapons)}; записей клиента прочитано: {len(first)} и {len(second)} из {struct.unpack_from('<I', raw, 0)[0]}.")
    print("id\tоружие\tтип\tгрейд\tхватка\tnpcOnly\tGM-шоп за адену\tторговцы\tохотники\tмодель")
    found = 0
    for iid, w in sorted(weapons.items()):
        if w["body"] in PET_PARTS:
            continue
        ok = PLAYER_GRIP.get(w["type"], set())
        a, b = first.get(iid), second.get(iid)
        bad_a = a is not None and a[0] not in ok
        bad_b = b is not None and b[0] not in ok
        if not ((bad_a and (bad_b or b is None)) or (bad_b and a is None)):
            continue
        grip, mesh = a if bad_a else b
        in_hunters = bool(hunters) and re.search(r"\b%d\b" % iid, hunters) is not None
        shop = "%s, %d" % gm[iid] if iid in gm else ""
        print("\t".join(str(x) for x in (
            iid, w["name"], w["type"], w["grade"], grip, "да" if npc_only(w, names) else "НЕТ",
            shop, buy.get(iid, ""), "ЕСТЬ" if in_hunters else "", mesh)))
        found += 1
    print(f"Всего: {found}. Опасно то, что можно взять (GM-шоп, торговцы, охотники) и что не ловит npcOnly.")


if __name__ == "__main__":
    main()
