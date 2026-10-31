"""Врезки «Живого сервера» в файлы Mobius всех хроник с фантомами (docs/LIVE_SERVER.md, вторая волна).

  petition — петиция, когда GM нет: после системного сообщения Mobius отвечает PhantomPetition.answer. Выключено ключом PetitionAnswer.

  python -X utf8 tools/l2hub_live_patches.py <хроника> <патч>           проверка: патч собирается и ложится байт в байт
  python -X utf8 tools/l2hub_live_patches.py <хроника> <патч> --write   записать patches/<id>/<файл патча>; дерево не трогает, патч наложит сборка
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import l2hub_contrib as contrib  # noqa: E402
import patch_layers  # noqa: E402

ROOT = patch_layers.ROOT
CHRONICLES = ("ct26_highfive", "epilogue", "interlude", "classic_tales", "ess_crow")
PETITION = "java/org/l2jmobius/gameserver/network/clientpackets/RequestPetition.java"
NO_GM = "THERE_ARE_NO_GMS_CURRENTLY_VISIBLE_IN_THE_PUBLIC_LIST_AS_THEY_MAY_BE_PERFORMING_OTHER_FUNCTIONS_AT_THE_MOMENT"
PATCHES = {
    "petition": ("0110-petition-answer.patch", [
        (PETITION, "import org.l2jmobius.gameserver.managers.PetitionManager;\n",
         f"import org.l2jmobius.gameserver.managers.PetitionManager;\nimport {contrib.PHANTOM_PKG}.PhantomPetition;\n", 1),
        (PETITION, f"\t\t\tplayer.sendPacket(SystemMessageId.{NO_GM});\n",
         f"\t\t\tplayer.sendPacket(SystemMessageId.{NO_GM});\n\t\t\tPhantomPetition.answer(player, _content);\n", 1),
    ]),
}


def tree(cid: str) -> Path:
    """Дерево сборки: у L2HUB_HOME своё, иначе дерево репозитория."""
    home = Path(os.environ.get("L2HUB_HOME") or ROOT)
    found = [p for p in (home / "servers" / cid).glob("L2J_Mobius_*") if p.is_dir()]
    if len(found) != 1:
        found = [p for p in (ROOT / "servers" / cid).glob("L2J_Mobius_*") if p.is_dir()]
    if len(found) != 1:
        sys.exit(f"{cid}: ожидалась одна папка L2J_Mobius_*, найдено {len(found)}")
    return found[0]


def main() -> None:
    if len(sys.argv) < 3 or sys.argv[1] not in CHRONICLES or sys.argv[2] not in PATCHES:
        sys.exit(__doc__)
    cid, write = sys.argv[1], "--write" in sys.argv
    name, edits = PATCHES[sys.argv[2]]
    files = sorted({rel for rel, *_ in edits})
    src = tree(cid)
    try:
        peeled, upper = patch_layers.peel(cid, src, name, files)
    except SystemExit:  # дерево ещё без этого патча: снимать нечего
        peeled, upper = {rel: (src / rel).read_bytes() for rel in files}, []
    if upper:
        sys.exit(f"поздние патчи трогают те же файлы, снять их здесь не предусмотрено: {', '.join(p.name for p in upper)}")
    base = {rel: peeled[rel] for rel in files}
    new = contrib.changes(base, edits)
    patch, bad = contrib.build_patch(base, new)
    if bad:
        sys.exit(f"патч не воспроизводит файлы: {bad}")
    print(f"{cid}: файлов в патче {patch.count(b'diff --git a/')}, наложение на копию — байт в байт")
    if write:
        (ROOT / "patches" / cid / name).write_bytes(patch)
        print(f"записано: patches/{cid}/{name}")


if __name__ == "__main__":
    main()
