"""Система клиентов 4game (Classic, Essence) без чужих бинарников: открытый L2Hub.ini и копия system_ru с языком Russia.

Открытый ini. У клиентов 4game L2.ini зашифрован ключом NCSoft: прочитать его можно, а перешифровать со своим
адресом — нечем. Зато L2.exe (как всякий Unreal) берёт главный ini из командной строки: `L2.exe -INI=.\\L2Hub.ini`,
и такой ini может быть открытым текстом. Бинарники остаются официальными, с подписью NCSoft. plain_ini расшифровывает
штатный L2.ini и пишет открытый, в котором ServerAddr — адрес сервера, ExternalLogin и CmdLineLogin выключены (окно
логина в клиенте вместо входа от лаунчера 4game), FrostModule выключен, автологин выключен, L2ID и L2Passwd пустые.
Остальные строки, кодировка и переводы строк — как в штатном файле.

Русский язык. EU-клиент знает язык 8=Russia (localization.ini), но русских файлов (*-ru.dat) в нём нет. make_system_ru
копирует system в system_ru (оригинал не меняется), в копии ставит Language=8, а раздел [Russia] направляет на тексты,
шрифты и текстуры базового языка: меню остаются английскими, а ввод — кириллицей. С ru_files — папкой настоящих
*-ru.dat той же сборки (например, тексты Иннова) — тексты становятся русскими. Клиенты 4game EU работают на языке
Europe (коды -eu), а не English: для них base="Europe". У новых клиентов (Essence 542) данные языка лежат
в подпапке system\\<код>: тогда русские тексты кладутся в system_ru\\ru.

Lineage2Ver111: 28 байт заголовка, тело XOR 0xAC, хвост 20 байт, в нём по смещению 12 — CRC32 заголовка и тела.
У клиентов 4game хвоста нет: заголовок и тело. Из консоли — tools/l2ini_plain.py и tools/client_system_ru.py."""

import re
import shutil
import zlib
from pathlib import Path

from .l2ini import decode

INI = "L2Hub.ini"
EDITS = {
    "ServerAddr": None,  # подставляется адрес
    "ExternalLogin": "false",
    "CmdLineLogin": "false",
    "FrostModule": "false",
    "IsL2AutoLogOn": "False",
    "L2ID": "",
    "L2Passwd": "",
}
OPTIONAL = {"FrostModule"}  # у штатной системы High Five (NCSoft) Frost нет — и строки нет
HEADER = "Lineage2Ver111".encode("utf-16-le")
FOOTER = 20


def plain_ini(raw: bytes, addr: str) -> bytes:
    _, data = decode(raw)
    bom = data[:2] == b"\xff\xfe"
    # Однобайтный текст бывает с байтами cp1251: latin-1 сохраняет каждый байт как есть.
    text = data[2:].decode("utf-16-le") if bom else data.decode("latin-1")
    for key, value in EDITS.items():
        value = addr if value is None else value
        text, count = re.subn(rf"(?m)^(\s*{key}\s*=)[^\r\n]*", lambda m: m.group(1) + value, text)
        if count != 1 and not (count == 0 and key in OPTIONAL):
            raise ValueError(f"в L2.ini строк {key}: {count}, ожидалась одна")
    return b"\xff\xfe" + text.encode("utf-16-le") if bom else text.encode("latin-1")


def is_4game(p: dict) -> bool:
    """Клиент запускается с открытым ini (-INI=.\\L2Hub.ini) — Classic, Essence."""
    return any(str(a).upper().startswith("-INI=") for a in (p.get("client") or {}).get("args", []))


def ensure_ini(system: Path, addr: str = "127.0.0.1", force: bool = False) -> Path:
    """L2Hub.ini в папке системы из её L2.ini. Уже есть — не трогаем (клиент дописывает в него свои настройки),
    force — пересобрать (другой адрес сервера)."""
    out = system / INI
    if out.is_file() and not force:
        return out
    src = system / "L2.ini"
    if not src.is_file():
        raise FileNotFoundError(f"В {system} нет L2.ini — это не система клиента 4game")
    out.write_bytes(plain_ini(src.read_bytes(), addr))
    return out


def ver111_decode(raw: bytes) -> tuple[bytes, bytes]:
    """Тело и хвост; у клиентов 4game хвоста нет (тогда хвост — b""): узнаём по CRC32 на его месте."""
    if not raw.startswith(HEADER):
        raise ValueError("нет заголовка Lineage2Ver111")
    tail = raw[-FOOTER:]
    if len(raw) >= len(HEADER) + FOOTER and tail[12:16] == zlib.crc32(raw[:-FOOTER]).to_bytes(4, "little"):
        return bytes(b ^ 0xAC for b in raw[len(HEADER):-FOOTER]), tail
    return bytes(b ^ 0xAC for b in raw[len(HEADER):]), b""


def ver111_encode(plain: bytes, footer: bytes) -> bytes:
    data = bytes(b ^ 0xAC for b in plain)
    if not footer:
        return HEADER + data
    crc = zlib.crc32(HEADER + data).to_bytes(4, "little")
    return HEADER + data + footer[:12] + crc + footer[16:]


def section(text: str, name: str) -> tuple[int, int]:
    m = re.search(rf"^\[{name}\]\r?\n(.*?)(?=^\[|\Z)", text, flags=re.M | re.S)
    if not m:
        raise ValueError(f"в localization.ini нет раздела [{name}]")
    return m.start(1), m.end(1)


def base_code(text: str, base: str) -> str:
    """Код текстов базового языка: e у English, eu у Europe (клиенты 4game EU)."""
    s, e = section(text, base)
    m = re.search(rf"^{base}_Code=(\w+)", text[s:e], flags=re.M)
    if not m:
        raise ValueError(f"в [{base}] нет {base}_Code")
    return m.group(1)


def russian_localization(text: str, real_texts: bool, base: str = "English") -> str:
    """Language=8; раздел [Russia] — значения из [base], код текстов — ru (настоящие тексты) или код base."""
    s, e = section(text, "LanguageSet")
    body, n = re.subn(r"^Language=\d+", "Language=8", text[s:e], flags=re.M)
    if n != 1:
        raise ValueError("в [LanguageSet] нет строки Language=")
    text = text[:s] + body + text[e:]

    s, e = section(text, base)
    values = dict(re.findall(rf"^{base}_(\w+)=(.*?)\r?$", text[s:e], flags=re.M))
    s, e = section(text, "Russia")
    lines = []
    for line in text[s:e].splitlines(keepends=True):
        m = re.match(r"^Russia_(\w+)=(.*?)(\r?\n?)$", line)
        if m and m.group(1) == "Code":
            line = f"Russia_Code={'ru' if real_texts else values['Code']}{m.group(3)}"
        elif m and m.group(1) in values:
            line = f"Russia_{m.group(1)}={values[m.group(1)]}{m.group(3)}"
        lines.append(line)
    return text[:s] + "".join(lines) + text[e:]


def make_system_ru(client: Path, src: str = "system", dst: str = "system_ru", ru_files: Path | None = None,
                   base: str = "English", out=print) -> Path:
    """Копия системы с языком Russia. Повторный запуск обновляет localization.ini (собирается из src каждый раз)."""
    src_dir, dst_dir = client / src, client / dst
    loc_src = src_dir / "localization.ini"
    raw = loc_src.read_bytes()
    plain, footer = ver111_decode(raw)
    if ver111_encode(plain, footer) != raw:
        raise ValueError("пересборка localization.ini не совпала с оригиналом — формат не тот, ничего не меняю")

    text = plain.decode("latin-1")  # байты как есть: в файле корейские комментарии в ANSI
    code = base_code(text, base)

    if not dst_dir.exists():
        shutil.copytree(src_dir, dst_dir)
        out(f"скопировано: {src_dir} -> {dst_dir}")

    real = []
    if ru_files:
        lang_src = src_dir / code if (src_dir / code).is_dir() else src_dir  # Essence 542: system\eu
        target = dst_dir / "ru" if lang_src != src_dir else dst_dir
        if lang_src != src_dir:
            target.mkdir(exist_ok=True)
            for f in lang_src.iterdir():
                if f.is_file() and not f.name.lower().endswith(f"-{code}.dat"):
                    shutil.copy2(f, target / f.name)
        for f in sorted(ru_files.glob("*-ru.dat")):
            shutil.copy2(f, target / f.name)
            real.append(f.name.lower())
        for f in sorted(lang_src.glob(f"*-{code}.dat")):
            name = f.name[:-len(f"-{code}.dat")] + "-ru.dat"
            if name.lower() not in real:
                shutil.copy2(f, target / name)
                out(f"нет русского {name} — положен {f.name}")
        out(f"русских текстов: {len(real)} -> {target}")

    patched = russian_localization(text, bool(real), base).encode("latin-1")
    (dst_dir / loc_src.name).write_bytes(ver111_encode(patched, footer))
    out(f"{dst_dir / loc_src.name}: Language=8 (Russia), тексты {'ru' if real else code}, шрифты и текстуры — {base}")
    return dst_dir


def describe(system: str) -> str:
    """Подпись «Система» в профиле после выбора языка в окне."""
    lang = "язык Russia — копия штатной системы" if language(system) == "ru" else "штатная, EU (английский)"
    return f"клиент 4game, система {system}: {lang}; открытый {INI} на 127.0.0.1"


def language(system: str) -> str:
    """Язык по папке системы профиля: system_ru — русский, иначе — английский (EU)."""
    return "ru" if system.lower().endswith("_ru") else "en"
