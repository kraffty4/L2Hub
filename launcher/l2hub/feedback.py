"""Голоса «Хочу эту хронику» и отзывы автору (docs/FEEDBACK.md). Приёмник — веб-приложение Google Apps Script автора
с таблицей (tools/feedback/Code.gs); адрес — feedback.json рядом, для проверок — переменная L2HUB_FEEDBACK_URL.

Уходит только то, что пользователь нажал сам: id хроники, случайный номер установки, версия L2Hub; в отзыве — ещё текст,
контакт, если он его написал, и отчёт (report.py, без паролей и ключей), если поставил галку. Свои голоса и номер
установки — data/feedback.json."""

import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from . import __version__, paths, report

CONFIG = paths.PACKAGE / "feedback.json"
STATE = paths.DATA / "feedback.json"
TIMEOUT = 25           # Apps Script отвечает за 1–3 с, отзыв с отчётом — дольше
MAX_REPORT = 8 * 1024 * 1024
MAX_TEXT = 4000
COUNTS_TTL = 300       # счётчики голосов спрашиваем не чаще раза в 5 минут
KINDS = {"bug", "idea"}

_counts = {"t": None, "votes": {}}


def url() -> str:
    if env := os.environ.get("L2HUB_FEEDBACK_URL"):
        return env
    try:
        return json.loads(CONFIG.read_text(encoding="utf-8")).get("url") or ""
    except (OSError, ValueError):
        return ""


def _state() -> dict:
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save_state(s: dict) -> None:
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_bytes((json.dumps(s, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def install_id() -> str:
    s = _state()
    if not s.get("install"):
        s["install"] = uuid.uuid4().hex
        _save_state(s)
    return s["install"]


def _call(data: dict | None = None, query: dict | None = None) -> dict:
    """GET (query) или POST (data) к приёмнику. Apps Script отвечает на POST редиректом 302 на страницу с ответом —
    urllib идёт по нему уже GET-ом, как и нужно."""
    u = url()
    if not u:
        raise RuntimeError("Приём голосов и отзывов в этой версии L2Hub не настроен")
    if query:
        u += ("&" if "?" in u else "?") + urllib.parse.urlencode(query)
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    req = urllib.request.Request(u, data=body, headers={"Content-Type": "text/plain; charset=utf-8", "User-Agent": f"L2Hub/{__version__}"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:  # noqa: S310 — адрес наш, https
            out = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Приёмник ответил {e.code}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise RuntimeError(f"Нет связи с приёмником: {getattr(e, 'reason', e)}") from e
    except ValueError as e:
        raise RuntimeError("Приёмник ответил не JSON") from e
    if not out.get("ok"):
        raise RuntimeError(f"Приёмник: {out.get('error') or 'ошибка'}")
    return out


def votes(refresh: bool = False) -> dict:
    """Счётчики голосов по хроникам и свои голоса. Без связи — свои голоса, прежние счётчики и error."""
    mine = sorted(_state().get("votes", []))
    if not url():
        return {"enabled": False, "mine": mine, "votes": {}}
    out = {"enabled": True, "mine": mine}
    if refresh or _counts["t"] is None or time.monotonic() - _counts["t"] > COUNTS_TTL:
        try:
            _counts.update(t=time.monotonic(), votes=_call(query={"action": "votes"}).get("votes") or {})
        except RuntimeError as e:
            out["error"] = str(e)
    return {**out, "votes": dict(_counts["votes"])}


def vote(chronicle: str, on: bool = True) -> dict:
    r = _call({"action": "vote", "install": install_id(), "chronicle": chronicle, "on": bool(on), "version": __version__})
    s = _state()
    mine = set(s.get("votes", []))
    (mine.add if on else mine.discard)(chronicle)
    s["votes"] = sorted(mine)
    _save_state(s)
    _counts["votes"][chronicle] = int(r.get("count") or 0)
    return {"on": bool(on), "count": _counts["votes"][chronicle]}


def send(kind: str, text: str, contact: str = "", chronicle: str = "", attach: bool = False, running: str | None = None) -> dict:
    """Отзыв автору. attach — собрать отчёт (как «Собрать отчёт») и приложить, если он не больше 8 МБ."""
    text = (text or "").strip()
    if kind not in KINDS:
        raise ValueError(f"Нет вида отзыва {kind!r}")
    if not text:
        raise ValueError("Пустой отзыв — напиши пару слов")
    if len(text) > MAX_TEXT:
        raise ValueError(f"Длиннее {MAX_TEXT} знаков — сократи")
    payload = {"action": "feedback", "install": install_id(), "kind": kind, "text": text, "contact": (contact or "").strip()[:200],
               "chronicle": chronicle or "", "version": __version__}
    name = None
    if attach:
        path = report.build(running)
        data = path.read_bytes()
        if len(data) > MAX_REPORT:
            raise ValueError(f"Отчёт {len(data) / 2**20:.1f} МБ — больше 8 МБ. Отправь без него, zip лежит в data\\reports ({path.name})")
        name = path.name
        payload["report"] = {"name": name, "data": base64.b64encode(data).decode("ascii")}
    _call(payload)
    return {"report": name}
