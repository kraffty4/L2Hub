"""Записи x10 в рабочей системе H5; штатную систему клиента не изменяет."""

from datetime import datetime
import logging
from pathlib import Path
import re
import shutil
import struct

from . import catalog, clientsys, h5currency, l2ini, paths


def is_h5(p: dict) -> bool:
    return p.get("chronicle", p["id"]) == "ct26_highfive"


def copy_exe(exe: Path) -> Path:
    return exe.parent.with_name(exe.parent.name + "_l2hub") / exe.name


def is_working(exe: Path) -> bool:
    name = exe.parent.name.lower()
    return ((name == "system_ru" or name.endswith("_l2hub"))
            and exe.parent.resolve() != (exe.parent.parent / "system").resolve())


def dat_language(localization: bytes) -> tuple[str, str]:
    """Язык новых предметов и файл имён по localization системы или пакета."""
    plain, _ = clientsys.ver111_decode(localization)
    text = plain.decode("latin-1")
    start, end = clientsys.section(text, "LanguageSet")
    match = re.search(r"^Language=(1|8)\s*$", text[start:end], re.M)
    if not match:
        raise ValueError("Поддерживаются языки клиента English и Russia (Language=1/8)")
    ru = match[1] == "8"
    code = clientsys.base_code(text, "Russia" if ru else "English")
    if not re.fullmatch(r"[A-Za-z0-9]+", code):
        raise ValueError("Неизвестный код языка клиента")
    return ("ru" if ru else "en"), f"itemname-{code}.dat"


def prepare(p: dict) -> tuple[dict, bool]:
    """Профиль запуска и признак H5. Ошибка DAT не препятствует запуску клиента.

    Профиль на диске не меняется; путь рабочей копии учитывает servers.part_process.
    """
    if not is_h5(p):
        return p, False
    launch = p
    try:
        exe = catalog.client_exe(p)
        if not exe.is_file() and exe.parent.name.lower() == "system_ru":
            fallback = exe.parent.parent / "system" / exe.name
            if fallback.is_file():
                launch = dict(p, client=dict(p["client"], exe=str(fallback.relative_to(Path(p["client"]["path"])))))
            clientsys.make_system_ru(exe.parent.parent, out=lambda message: None)
            if not exe.is_file():
                raise ValueError("В русской копии нет файла запуска клиента")
            launch = p
        # system_ru — уже рабочая копия. Остальные варианты копируем с тем же языком.
        if not is_working(exe):
            target = copy_exe(exe)
            if not target.parent.exists():
                # Оборванное копирование (нет места, файл занят) не должно оставить полу-систему, которую
                # следующий запуск примет за готовую: копия собирается рядом и переименовывается целиком.
                partial = target.parent.with_name(target.parent.name + ".partial")
                if partial.exists():
                    shutil.rmtree(partial)
                shutil.copytree(exe.parent, partial)
                partial.rename(target.parent)
            if target.parent.resolve() == exe.parent.resolve() or not is_working(target):
                raise ValueError("Рабочая система ссылается на оригинал")
            if not target.is_file():
                raise ValueError("В рабочей копии нет файла запуска клиента")
            client = dict(p["client"], exe=str(target.relative_to(Path(p["client"]["path"]))))
            if client.get("ini"):
                client["ini"] = str(target.parent.relative_to(Path(client["path"])) / Path(client["ini"]).name)
            launch = dict(p, client=client)
            exe = target
        language, itemname = dat_language((exe.parent / "localization.ini").read_bytes())
        files = ((itemname, "itemname"), ("etcitemgrp.dat", "etcitemgrp"))
        changed = []
        for name, kind in files:
            key, raw = l2ini.decode((exe.parent / name).read_bytes())
            if key != "community":
                raise ValueError("DAT требует систему с поддержкой ключа community413")
            if h5currency.patch(raw, kind, language) != raw:
                changed.append(name)
        if changed:
            stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
            output = exe.parent / ".l2hub-x10" / stamp
            # Сначала построить и проверить оба DAT, затем сохранить оригиналы и заменить.
            h5currency.build(exe.parent, output, language, itemname=itemname)
            backups = {}
            for name in changed:
                backup = exe.parent / f"{name}.x10-{stamp}.bak"
                shutil.copy2(exe.parent / name, backup)
                backups[name] = backup
            try:
                for name in changed:
                    (output / name).replace(exe.parent / name)
            except OSError:
                for name, backup in backups.items():
                    shutil.copy2(backup, exe.parent / name)
                raise
        return launch, True
    except (OSError, ValueError, KeyError, struct.error) as error:
        message = ("High Five: не удалось подготовить коины, жетоны стиля и ярлык автобоя: "
                   f"{error}. Клиент запускается; эти предметы могут отображаться без имени и иконки.")
        logging.getLogger(__name__).warning(message)
        try:
            log = paths.DATA / "logs" / "client-x10.log"
            log.parent.mkdir(parents=True, exist_ok=True)
            with log.open("a", encoding="utf-8") as stream:
                stream.write(f"{datetime.now().isoformat(timespec='seconds')} {message}\n")
        except OSError:
            pass
        return launch, True
