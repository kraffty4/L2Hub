"""Патч «Лавки фейков» для хроники Mobius: patches/<id>/0037-l2hub-town-stores.patch.

Четыре врезки в файлы Mobius, чтобы окно личной лавки работало у фейка-NPC (сама лавка — PhantomStores в модуле фантомов):
  PrivateStoreListSell, PrivateStoreListBuy — второй конструктор: хозяин не игрок, список свой;
  RequestPrivateStoreBuy, RequestPrivateStoreSell — пакет клиента с id фейка уходит в PhantomStores.
Перевод строк каждого файла сохраняется. Правила — регулярками по единому у пяти хроник коду: если Mobius их поменял, инструмент останавливается.

    python -X utf8 tools/l2hub_town_stores.py <id>            — собрать и проверить (ничего не пишет)
    python -X utf8 tools/l2hub_town_stores.py <id> --write    — записать patches/<id>/0037-l2hub-town-stores.patch (дерево не трогает)
    --dump <папка> — файлы после патча в папку (для javac).
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import l2hub_town_fakes as fakes  # noqa: E402
import patch_layers  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
NAME = "0037-l2hub-town-stores.patch"
PKG = "java/org/l2jmobius/gameserver/network/"
SELL_LIST = PKG + "serverpackets/PrivateStoreListSell.java"
BUY_LIST = PKG + "serverpackets/PrivateStoreListBuy.java"
BUY = PKG + "clientpackets/RequestPrivateStoreBuy.java"
SELL = PKG + "clientpackets/RequestPrivateStoreSell.java"
FILES = [SELL_LIST, BUY_LIST, BUY, SELL]


def replace_once(text: str, old: str, new: str, rel: str) -> str:
    if text.count(old) != 1:
        sys.exit(f"{rel}: «{old[:60]}» — найдено {text.count(old)} раз, нужно одно")
    return text.replace(old, new)


def add_import(text: str, line: str, rel: str) -> str:
    """Импорт по алфавиту: перед первым импортом, который идёт после него."""
    imports = re.findall(r"(?m)^import org\.l2jmobius\.[^\n]*$", text)
    if not imports:
        sys.exit(f"{rel}: нет импортов")
    later = [i for i in imports if i > line]
    if later:
        return text.replace(later[0], line + "\n" + later[0], 1)
    return text.replace(imports[-1], imports[-1] + "\n" + line, 1)


def sell_list(t: str) -> str:
    rel = SELL_LIST
    t = add_import(t, "import org.l2jmobius.gameserver.network.holders.TradeList;", rel)
    t = replace_once(t, "\tprivate final Player _seller;\n", "\tprivate final Player _seller;\n\tprivate final int _sellerId;\n\tprivate final TradeList _list;\n", rel)
    t = replace_once(t, "\t\t_seller = seller;\n\t}\n",
                     "\t\t_seller = seller;\n\t\t_sellerId = seller.getObjectId();\n\t\t_list = seller.getSellList();\n\t}\n"
                     "\t\n"
                     "\t/**\n\t * L2Hub: лавка фейка-NPC (PhantomStores): хозяин не игрок, список свой.\n"
                     "\t * @param player покупатель\n\t * @param sellerId id фейка\n\t * @param list что продаёт\n\t */\n"
                     "\tpublic PrivateStoreListSell(Player player, int sellerId, TradeList list)\n\t{\n"
                     "\t\t_player = player;\n\t\t_seller = null;\n\t\t_sellerId = sellerId;\n\t\t_list = list;\n\t}\n", rel)
    t = replace_once(t, "if (_seller.isSellingBuffs())", "if ((_seller != null) && _seller.isSellingBuffs())", rel)
    t = replace_once(t, "buffer.writeInt(_seller.getObjectId());", "buffer.writeInt(_sellerId);", rel)
    if t.count("_seller.getSellList()") != 3:  # конструктор, isPackaged и размер, цикл
        sys.exit(f"{rel}: _seller.getSellList() — {t.count('_seller.getSellList()')} раз, ждал 3 (Mobius поменял пакет?)")
    head, tail = t.split("public void writeImpl", 1)
    return head + "public void writeImpl" + tail.replace("_seller.getSellList()", "_list")


def buy_list(t: str) -> str:
    rel = BUY_LIST
    t = add_import(t, "import org.l2jmobius.gameserver.network.holders.TradeList;", rel)
    m = re.search(r"(\t\t_items = storePlayer\.getBuyList\(\)\.getAvailableItems\(player\.getInventory\(\)\);\n\t}\n)", t)
    if not m:
        sys.exit(f"{rel}: конструктор не совпал")
    return t.replace(m.group(1), m.group(1) + "\t\n"
                     "\t/**\n\t * L2Hub: скупка фейка-NPC (PhantomStores): хозяин не игрок, список свой.\n"
                     "\t * @param player кто продаёт\n\t * @param storeId id фейка\n\t * @param list что скупает\n\t */\n"
                     "\tpublic PrivateStoreListBuy(Player player, int storeId, TradeList list)\n\t{\n"
                     "\t\t_objId = storeId;\n\t\t_playerAdena = player.getAdena();\n"
                     "\t\t_items = list.getAvailableItems(player.getInventory());\n\t}\n", 1)


def hook(t: str, rel: str, call: str) -> str:
    t = add_import(t, "import org.l2jmobius.gameserver.managers.phantom.PhantomStores;", rel)
    m = re.search(r"\t\tfinal (?:WorldObject|Player) \w+ = World\.getPlayer\(_storePlayerId\);\n", t)
    if not m:
        sys.exit(f"{rel}: поиск хозяина лавки не совпал")
    block = ("\t\t// L2Hub: лавка фейка-NPC.\n"
             "\t\tif (PhantomStores.isStore(_storePlayerId))\n\t\t{\n"
             f"\t\t\t{call}\n\t\t\treturn;\n\t\t}}\n\t\t\n")
    return t.replace(m.group(0), block + m.group(0), 1)


def apply_rules(rel: str, data: bytes) -> bytes:
    crlf = b"\r\n" in data
    if crlf and data.count(b"\r\n") != data.count(b"\n"):
        sys.exit(f"{rel}: перевод строк вперемешку")
    t = data.decode("utf-8").replace("\r\n", "\n")
    if rel == SELL_LIST:
        t = sell_list(t)
    elif rel == BUY_LIST:
        t = buy_list(t)
    elif rel == BUY:
        t = hook(t, rel, "PhantomStores.buy(player, _storePlayerId, _items);")
    else:
        t = hook(t, rel, "PhantomStores.sell(player, _storePlayerId, _items);")
    return (t.replace("\n", "\r\n") if crlf else t).encode("utf-8")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cid = sys.argv[1]
    src = fakes.tree(cid)
    patch_file = ROOT / "patches" / cid / NAME
    upper = []
    if patch_file.is_file() and any(b"PhantomStores" in (src / rel).read_bytes() for rel in FILES):
        peeled, upper = patch_layers.peel(cid, src, NAME, FILES)
        base = {rel: peeled[rel] for rel in FILES}
    else:
        base = {rel: (src / rel).read_bytes() for rel in FILES}
    if upper:
        sys.exit(f"поздние патчи трогают файлы лавок: {[p.name for p in upper]} — так не задумано")
    new = {rel: apply_rules(rel, base[rel]) for rel in FILES}
    patch, bad = fakes.build_patch({rel: (base[rel], new[rel]) for rel in FILES})
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    print(f"{cid}: файлов в патче {len(FILES)}, наложение на копию — байт в байт")
    if "--dump" in sys.argv:
        out = Path(sys.argv[sys.argv.index("--dump") + 1])
        for rel, data in new.items():
            (out / rel).parent.mkdir(parents=True, exist_ok=True)
            (out / rel).write_bytes(data)
    if "--write" in sys.argv:
        patch_file.write_bytes(patch)
        print(f"записано: patches/{cid}/{NAME}")


if __name__ == "__main__":
    main()
