"""Файлы клиента формата Lineage2Ver413 (l2.ini, .dat): RSA-блоки + zlib.

Известны два публичных ключа: оригинальный NCSoft и ключ комьюнити (l2encdec), который вшивают
в патченные l2.exe систем для приватных серверов. Зашифровать можно только ключом комьюнити:
его приватная экспонента — та, что зашита в l2encdec 2.92 (La2offline, data\\encdec\\l2encdec.exe,
смещение 58784, little-endian); найдена проверкой шифр → расшифровка, сам l2encdec не запускался.

Формат: заголовок «Lineage2Ver413» в UTF-16LE; поток «размер распакованного (4 байта LE) + zlib»
режется на куски по 124 байта, каждый — RSA-блок 128 байт: [длина куска 4 байта BE][нули][кусок][нули
до кратности 4]; в конце хвост 20 байт: 12 нулей, CRC32 заголовка с блоками (LE), 4 нуля.
"""

import binascii
import re
import zlib
from pathlib import Path

HEADER = "Lineage2Ver413".encode("utf-16-le")

KEYS = {
    "ncsoft": (
        int(
            "97df398472ddf737ef0a0cd17e8d172f0fef1661a38a8ae1d6e829bc1c6e4c3c"
            "fc19292dda9ef90175e46e7394a18850b6417d03be6eea274d3ed1dde5b5d7bd"
            "e72cc0a0b71d03608655633881793a02c9a67d9ef2b45eb7c08d4be329083ce4"
            "50e68f7867b6749314d40511d09bc5744551baa86a89dc38123dc1668fd72d83",
            16,
        ),
        0x35,
    ),
    "community": (
        int(
            "75b4d6de5c016544068a1acf125869f43d2e09fc55b8b1e289556daf9b875763"
            "5593446288b3653da1ce91c87bb1a5c18f16323495c55d7d72c0890a83f69bfd"
            "1fd9434eb1c02f3e4679edfa43309319070129c267c85604d87bb65bae205de3"
            "707af1d2108881abb567c3b3d069ae67c3a4c6a3aa93d26413d4c66094ae2039",
            16,
        ),
        0x1D,
    ),
}

# Приватная экспонента пары комьюнити (из l2encdec, см. описание модуля) — только для зашифровки.
COMMUNITY_D = int(
    "30b4c2d798d47086145c75063c8e841e719776e400291d7838d3e6c4405b504c6a07f8fca27f32b86643d2649d1d5f124"
    "cdd0bf272f0909dd7352fe10a77b34d831043d9ae541f8263c6fe3d1c14c2f04e43a7253a6dda9a8c1562cbd493c1b631"
    "a1957618ad5dfe5ca28553f746e2fc6f2db816c7db223ec91e955081c1de65",
    16,
)
CHUNK = 124


def _decode_with(body: bytes, n: int, e: int) -> bytes:
    out = bytearray()
    # Хвост короче 128 байт — контрольная сумма, не RSA-блок.
    for i in range(0, len(body) - len(body) % 128, 128):
        block = pow(int.from_bytes(body[i:i + 128], "big"), e, n).to_bytes(128, "big")
        size = int.from_bytes(block[0:4], "big")
        if size > 124:
            raise ValueError("ключ не подходит")
        pad = (-size) & 3
        out += block[128 - size - pad:128 - pad]
    data = zlib.decompress(bytes(out[4:]))
    if len(data) != int.from_bytes(out[0:4], "little"):
        raise ValueError("размер после распаковки не совпал")
    return data


def decode(raw: bytes) -> tuple[str, bytes]:
    """Возвращает (имя подошедшего ключа, расшифрованные байты)."""
    if not raw.startswith(HEADER):
        raise ValueError("нет заголовка Lineage2Ver413")
    body = raw[len(HEADER):]
    for name, (n, e) in KEYS.items():
        try:
            return name, _decode_with(body, n, e)
        except (ValueError, zlib.error):
            continue
    raise ValueError("ни один известный ключ не подошёл")


def encode(data: bytes) -> bytes:
    """Зашифровать ключом комьюнити — так, как это делает l2encdec -e 413."""
    n = KEYS["community"][0]
    stream = len(data).to_bytes(4, "little") + zlib.compress(data, 6)
    out = bytearray(HEADER)
    for i in range(0, len(stream), CHUNK):
        chunk = stream[i:i + CHUNK]
        pad = (-len(chunk)) & 3
        block = len(chunk).to_bytes(4, "big") + bytes(128 - 4 - len(chunk) - pad) + chunk + bytes(pad)
        out += pow(int.from_bytes(block, "big"), COMMUNITY_D, n).to_bytes(128, "big")
    out += bytes(12) + (binascii.crc32(out) & 0xFFFFFFFF).to_bytes(4, "little") + bytes(4)
    return bytes(out)


def with_server_addr(raw: bytes, addr: str) -> bytes:
    """Тот же L2.ini (зашифрованный ключом комьюнити), но с другим ServerAddr; кодировка текста сохраняется."""
    key, data = decode(raw)
    if key != "community":
        raise ValueError("L2.ini зашифрован не ключом комьюнити — перешифровать его нечем")
    # UTF-16 с BOM или однобайтный текст (бывают байты cp1251): latin-1 сохраняет каждый байт как есть.
    bom = data[:2] == b"\xff\xfe"
    text = data[2:].decode("utf-16-le") if bom else data.decode("latin-1")
    new, count = re.subn(r"(?m)^(\s*ServerAddr\s*=\s*)\S+", lambda m: m.group(1) + addr, text)
    if count != 1:
        raise ValueError(f"в L2.ini строк ServerAddr: {count}, ожидалась одна")
    return encode(b"\xff\xfe" + new.encode("utf-16-le") if bom else new.encode("latin-1"))


def read_text(path: Path) -> tuple[str, str]:
    return text_of(path.read_bytes())


def text_of(raw: bytes) -> tuple[str, str]:
    """Текст зашифрованного или открытого ini клиента и способ: ncsoft, community или plain."""
    if raw.startswith(HEADER):
        key, data = decode(raw)
    else:
        key, data = "plain", raw
    if data[:2] == b"\xff\xfe":
        return data[2:].decode("utf-16-le", "replace"), key
    return data.decode("utf-8", "replace"), key


def packages_dir(l2ini: Path) -> str | None:
    """Папка классов клиента из Paths=../<папка>/*.u. Копия системы (system_ru) может брать классы из соседней ../System."""
    text, _ = read_text(l2ini)
    m = re.search(r"(?mi)^\s*Paths\s*=\s*\.\.[/\\]([^/\\\r\n]+)[/\\]\*\.u\s*$", text)
    return m.group(1) if m else None


def server_addr(l2ini: Path) -> str | None:
    return server_addr_of(l2ini.read_bytes())


def server_addr_of(raw: bytes) -> str | None:
    text, _ = text_of(raw)
    m = re.search(r"^\s*ServerAddr\s*=\s*(\S+)", text, re.MULTILINE)
    return m.group(1) if m else None
