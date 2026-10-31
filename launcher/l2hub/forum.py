"""Форум сервера в «Обществе» (docs/WORLD_CONTROL.md, 6б): как форум старого сервера.

Темы растут из настоящей летописи (кто взял замок, кто что сломал, кто с кем воюет) или из вечных тем игроков. Пишут охотники
в своей манере, одним запросом к мозгам на ветку. Живой может ответить — ему отвечают. Новая ветка не чаще FORUM_GAP и только
когда страницу открыли: пока никто не читает, денег не тратит. Файл — data/society/<id>_forum.json, выключатель — voice.forum
в «Разуме». В игру отсюда ничего не уходит, отношения и память пар не меняются.
"""
from __future__ import annotations

import json
import random
import re
import threading
import time

from . import brain, paths, worldtalk

FORUM_GAP = 4 * 3600   # с между новыми ветками
FORUM_FIRST = 3        # веток при первом открытии
FORUM_MAX = 40         # веток хранится, старые уходят
FORUM_BUMP = 50        # % шанс при обновлении, что в старую ветку допишут
SEED_WEIGHT = 5        # вес вида в NEWS_KINDS: ниже — обыденность, тему не заводит
SEED_REPEAT = 3        # во столько раз тише вид, о котором уже есть одна из двух последних веток
HISTORY_POSTS = 8      # постов в запросе на продолжение
POST_MAX = 400         # символов в посте
TOPICS = ("на каком классе сейчас лучше и зачем все пересаживаются", "где выгодно качаться на средних уровнях", "почему такие цены на соски и кто их взвинтил",
          "идёт набор в клан, кого берут и что от них хотят", "кс и отжимы мобов на спотах, кто больше всех наглеет", "как кто начинал на этом сервере",
          "какой шмот брать на средних уровнях и на что копить", "когда вечером больше всего народу", "куда пропали знакомые ники", "кто пойдёт на ближайшего РБ",
          "оффтоп: кто что делает вне игры, работа, учёба, дети", "что на сервере бесит больше всего", "покажите самую глупую смерть за неделю")

_locks: dict[str, threading.Lock] = {}


def _path(chronicle_id: str):
    return paths.HOME / "data" / "society" / f"{chronicle_id}_forum.json"


def _load(chronicle_id: str) -> dict:
    try:
        data = json.loads(_path(chronicle_id).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    return {"threads": data.get("threads") or [], "used": data.get("used") or [], "last": data.get("last") or 0}


def _save(chronicle_id: str, data: dict) -> None:
    path = _path(chronicle_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    data["threads"] = sorted(data["threads"], key=lambda t: -t["updated"])[:FORUM_MAX]
    data["used"] = data["used"][-200:]
    path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")


def _who(b: brain.Brain, fpc: str) -> str:
    """Строка о нике для запроса: кто он и как пишет."""
    p, row = b.personas.get(fpc, {}), b.talk._row(fpc)
    head = ", ".join(str(x) for x in (p.get("race"), p.get("class"), f"{row['level']} уровень" if row.get("level") else None, f"клан {row['clan']}" if row.get("clan") else None) if x)
    parts = [f"- {fpc}: {head}."]
    if p.get("traits"):
        parts.append(f"Характер: {p['traits']}.")
    if p.get("style"):
        parts.append(f"Манера: {p['style']}.")
    voice = b.settings["voice"]
    if voice["live"]:
        mask = b.voice_mask(fpc)
        typing = brain.VOICE_TRANSLIT if b.translit_nick(fpc) else brain.VOICE_TYPING[mask[0] % len(brain.VOICE_TYPING)]
        swear = brain.VOICE_SWEAR[mask[1] % len(brain.VOICE_SWEAR)] if voice["swear"] else brain.VOICE_SWEAR[0]
        parts.append(f"Пишет: {typing}; {swear}.")
    return " ".join(parts)


def _seed(b: brain.Brain, data: dict) -> dict:
    """О чём ветка: самое громкое свежее из летописи, что ещё не обсуждали; нет такого — вечная тема."""
    now, hidden, rows = time.time(), b.talk._hidden(), []
    recent = {t["kind"] for t in sorted(data["threads"], key=lambda t: -t["updated"])[:2]}
    for e in b.talk._load("L2HubChronicle.json").get("entries") or []:
        weight, key = worldtalk.NEWS_KINDS.get(e.get("kind"), 0), f"{e.get('time')}:{e.get('kind')}"
        age = now - float(e.get("time") or 0) / 1000
        text = worldtalk.clean(str(e.get("text") or ""))
        if weight >= SEED_WEIGHT and key not in data["used"] and age < worldtalk.NEWS_DAYS * 86400 and text and not e.get("secret") and not any(n in text for n in hidden):
            rows.append((weight / (1 + age / 86400) / (SEED_REPEAT if e.get("kind") in recent else 1), key, text, e.get("kind")))
    if rows:
        _, key, text, kind = max(rows, key=lambda r: r[0])
        return {"key": key, "kind": kind, "fact": text}
    fresh = [t for t in TOPICS if t not in data["used"]]
    if not fresh:
        data["used"] = [u for u in data["used"] if u not in TOPICS]
        fresh = list(TOPICS)
    topic = random.choice(fresh)
    return {"key": topic, "kind": "talk", "topic": topic}


def _prompt(b: brain.Brain, people: list[str], lang: str) -> str:
    top = b.talk.top()
    return "\n".join(x for x in [
        f"Ты пишешь ветку форума старого сервера Lineage II ({b.chronicle_name}). На форуме сидят обычные игроки этого сервера и пишут как люди в интернете: "
        "с подколами, спорами, ошибками, не по теме. Никто не пишет как помощник или журналист. Пост — одно-три коротких предложения.",
        "Кто пишет, только эти ники, каждый в своей манере:",
        *[_who(b, n) for n in people],
        top,
        "Про цифры, вещи и имена пиши только то, что есть в этом запросе. Без угроз вне игры и без оскорблений нации, веры и пола. Никто не говорит, что он ИИ, бот или персонаж. Без эмодзи.",
        "Все пишут по-английски." if lang == "en" else "",
    ] if x)


def _post_text(text: str) -> str:
    return re.sub(r"(?:^|(?<=\s))\*[^*\s][^*]{0,78}\*(?=\s|$)", "", worldtalk.clean(" ".join(text.split()))).strip()


def _parse(reply: str, people: set[str], hidden: set[str]) -> dict:
    """Строки модели «Тема: …» и «ник: …» → заголовок и посты. Строки: JSON модель ломает кавычками внутри поста. Чужие ники и скрытые имена отбрасываются,
    строка без ника — продолжение прошлого поста."""
    title, posts, names = "", [], {n.lower(): n for n in people}
    for raw in reply.splitlines():
        line = raw.strip().lstrip("-•").strip()
        head, sep, text = line.partition(":")
        head = head.strip(" *«»\"").lower()
        if sep and head in ("тема", "заголовок", "title", "topic"):
            title = title or worldtalk.clean(" ".join(text.split()))[:90].rstrip(".").strip(" «»\"*")
        elif sep and head in names:
            posts.append({"by": names[head], "text": _post_text(text)})
        elif sep and len(head) <= 24 and " " not in head:
            posts.append({"by": "", "text": ""})  # чужой ник: и его продолжение тоже мимо
        elif line and posts:
            posts[-1]["text"] = f"{posts[-1]['text']} {_post_text(line)}".strip()
    posts = [{"by": p["by"], "text": p["text"][:POST_MAX]} for p in posts if p["by"] and p["text"] and not any(n in p["text"] for n in hidden)]
    if not posts:
        raise ValueError("в ответе модели нет постов")
    return {"title": title, "posts": posts}


def _stamp(posts: list[dict], start: float, now: float) -> list[dict]:
    """Посты растянуты по времени: в форуме пишут не разом."""
    moment = start
    for p in posts:
        p["time"] = int(min(moment, now) * 1000)
        moment += random.randint(120, 2400)
    return posts


def _new_thread(b: brain.Brain, data: dict, lang: str) -> dict | None:
    hunters = [x["name"] for x in b.talk._hunters()]
    if len(hunters) < 3:
        return None
    seed = _seed(b, data)
    named = [n for n in hunters if n in seed.get("fact", "")][:2]
    rest = random.sample([n for n in hunters if n not in named], min(random.randint(3, 4), len(hunters) - len(named)))
    people = named + rest
    random.shuffle(people)
    count = random.randint(4, 7)
    task = (f"Что случилось на сервере: {seed['fact']}. Это правда было, из этого растёт тема." if "fact" in seed else f"Тема: {seed['topic']}.")
    reply = b.call_llm([{"role": "system", "content": _prompt(b, people, lang)},
                        {"role": "user", "content": f"{task}\nНапиши заголовок, как на форумах: коротко, бытово или с криком, без точки. И {count} постов: первый — автор темы, дальше кто спорит, кто подкалывает, кто не по теме; "
                                                    "один ник может написать дважды. Ответ — только строки, без разметки: первая «Тема: заголовок», дальше по строке на пост «ник: текст»."}],
                       max_tokens=700)
    parsed = _parse(reply, set(people), b.talk._hidden())
    now = time.time()
    posts = _stamp(parsed["posts"], now - random.randint(1800, 4 * 3600), now)
    data["used"].append(seed["key"])
    return {"id": int(now * 1000) + random.randint(0, 999), "title": parsed["title"] or parsed["posts"][0]["text"][:60], "kind": seed["kind"], "by": posts[0]["by"], "posts": posts, "updated": posts[-1]["time"]}


def _continue(b: brain.Brain, thread: dict, lang: str, player: str = "") -> None:
    """Ещё один-три поста в ветку; написал живой — отвечают ему."""
    hunters = {x["name"] for x in b.talk._hunters()}
    people = list(dict.fromkeys(p["by"] for p in thread["posts"] if p["by"] in hunters))[-4:]
    extra = [n for n in hunters if n not in people]
    if extra:
        people.append(random.choice(extra))
    if not people:
        return
    history = "\n".join(f"{p['by']}: {p['text']}" for p in thread["posts"][-HISTORY_POSTS:])
    about = b.talk.about(people[0], player) if player else ""
    task = f"Ветка «{thread['title']}», последние посты:\n{history}\n" + (f"Последний пост написал {player}: ответь ему. " if player else "Кто-то зашёл и дописал. ") + (f"{about}\n" if about else "")
    reply = b.call_llm([{"role": "system", "content": _prompt(b, people, lang)},
                        {"role": "user", "content": f"{task}Напиши {random.randint(1, 3)} новых поста, по ветке и в её тоне. Ответ — только строки, без разметки, по строке на пост: «ник: текст»."}],
                       max_tokens=400)
    posts = _parse(reply, set(people), b.talk._hidden())["posts"]
    now = time.time()
    last = thread["posts"][-1]["time"] / 1000
    thread["posts"] += _stamp(posts, max(last + 60, now - 600) if not player else now, now)
    thread["updated"] = thread["posts"][-1]["time"]


def _view(chronicle_id: str, data: dict | None = None, **extra) -> dict:
    data = data or _load(chronicle_id)
    return {"ok": True, "threads": sorted(data["threads"], key=lambda t: -t["updated"]), **extra}


def state(chronicle_id: str) -> dict:
    """Что уже написано; ничего не генерирует."""
    return _view(chronicle_id, enabled=brain.load_settings({})["voice"]["forum"])


def refresh(chronicle_id: str, lang: str = "ru") -> dict:
    """Страницу открыли: новая ветка, если подошло время. Параллельный запрос просто получает то, что есть."""
    lock = _locks.setdefault(chronicle_id, threading.Lock())
    if not lock.acquire(blocking=False):
        return _view(chronicle_id, busy=True)
    try:
        data = _load(chronicle_id)
        first = len(data["threads"]) < FORUM_FIRST
        if not first and time.time() - data["last"] < FORUM_GAP:
            return _view(chronicle_id, data)
        b = brain.Brain(chronicle_id)
        if not b.settings["voice"]["forum"]:
            return _view(chronicle_id, data, enabled=False)
        try:
            for _ in range(FORUM_FIRST - len(data["threads"]) if first else 1):
                thread = _new_thread(b, data, lang)
                if thread is None:
                    return _view(chronicle_id, data, nobody=True)
                data["threads"].append(thread)
            if not first and data["threads"] and random.randint(0, 99) < FORUM_BUMP:
                _continue(b, random.choice(sorted(data["threads"], key=lambda t: -t["updated"])[:5]), lang)
        except (brain.ModelDown, ValueError) as e:
            _save(chronicle_id, data)
            return _view(chronicle_id, data, error=str(e))
        data["last"] = time.time()
        _save(chronicle_id, data)
        return _view(chronicle_id, data)
    finally:
        lock.release()


def reply(chronicle_id: str, thread_id: int, player: str, text: str, lang: str = "ru") -> dict:
    """Живой написал в ветку: его пост сохраняется сразу, ответы — как получится."""
    text = " ".join(str(text).split())[:POST_MAX]
    if not text or not player:
        return {"ok": False, "error": "Пустой пост."}
    with _locks.setdefault(chronicle_id, threading.Lock()):
        data = _load(chronicle_id)
        thread = next((t for t in data["threads"] if t["id"] == thread_id), None)
        if thread is None:
            return {"ok": False, "error": "Ветки больше нет."}
        now = int(time.time() * 1000)
        thread["posts"].append({"by": player, "text": text, "time": now, "you": True})
        thread["updated"] = now
        _save(chronicle_id, data)
        try:
            _continue(brain.Brain(chronicle_id), thread, lang, player)
        except (brain.ModelDown, ValueError) as e:
            return _view(chronicle_id, data, error=str(e))
        _save(chronicle_id, data)
        return _view(chronicle_id, data)
