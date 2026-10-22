"""Каталог хроник (chronicles.json) и профили установленных (profiles/*.json)."""

import json
from pathlib import Path

from . import paths


def load_profiles() -> dict[str, dict]:
    profiles = {}
    for f in sorted(paths.PROFILES.glob("*.json")):
        p = json.loads(f.read_text(encoding="utf-8"))
        profiles[p["id"]] = p
    return profiles


def load_catalog() -> list[dict]:
    """Хроники из chronicles.json и варианты серверов: профиль с "chronicle": "<id хроники>" (у C4 — L2Solo и
    L2JLisvus рядом) — отдельная строка сразу после своей хроники, с её обложкой и эпохой."""
    data = json.loads((paths.PACKAGE / "chronicles.json").read_text(encoding="utf-8"))
    profiles = load_profiles()
    variants: dict[str, list[dict]] = {}
    for p in profiles.values():
        if p.get("chronicle") and p["chronicle"] != p["id"]:
            variants.setdefault(p["chronicle"], []).append(p)
    out = []
    for era in data["eras"]:
        for item in era["items"]:
            out.append(dict(item, era=era["name"], installed=item["id"] in profiles))
            for v in variants.get(item["id"], []):
                # Сервер варианта — по его профилю, а не по хронике: у Lisvus при C4 на L2Solo пульт L2Solo не нужен.
                kind = {"l2solo": "l2solo", "mobius": "mobius"}.get(v.get("type"), "own")
                out.append(dict(item, id=v["id"], era=era["name"], installed=True, server=kind, variant=v.get("build") or v["id"], base=item["id"]))
    return out


def profile(chronicle_id: str) -> dict:
    profiles = load_profiles()
    if chronicle_id not in profiles:
        raise KeyError(f"Хроника {chronicle_id} не установлена")
    return profiles[chronicle_id]


def server_dir(p: dict) -> Path:
    return paths.HOME / p["server"]


def client_exe(p: dict) -> Path:
    return Path(p["client"]["path"]) / p["client"]["exe"]


def client_ini(p: dict) -> Path:
    """Главный ini клиента: client.ini профиля (у клиентов 4game — открытый ini для -INI=), иначе L2.ini рядом с exe."""
    if p["client"].get("ini"):
        return Path(p["client"]["path"]) / p["client"]["ini"]
    return client_exe(p).parent / "L2.ini"  # system или system_ru — откуда запускается клиент
