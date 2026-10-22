"""Сводка по охотникам High Five: что они наделали за период и где кто сейчас.

Читает логи гейм-сервера (game/log/java*.log) и живое состояние охотников (game/log/L2HubHunters.json, сервер пишет его раз в 10 с)
и печатает: где кто качается по зонам, прогресс по уровням, город и деньги, лавки и рынок, пати, конфликты, подозрительное
и предупреждения сервера.

Использование:
  python -X utf8 tools/hunters_digest.py [--minutes 60 | --since "2026.10.19 22:10"] [--log <папка game/log>]
  Другая хроника — её лог: --log servers/interlude/server/game/log (по умолчанию — High Five).
"""
import argparse
import collections
import datetime as dt
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "servers/ct26_highfive/server/game"
LINE = re.compile(r"^(\d{4}\.\d\d\.\d\d \d\d:\d\d:\d\d),\d+\t(\w+)\t\d+\t(\S+)\t(.*)$")
# Предупреждения сервера, которые есть всегда и ни о чём не говорят.
NOISE = ("HTML encoding check", "Directory not found", "Master access level")
EVENTS = {
    "level": re.compile(r"^PhantomHunterManager: (\w+) reached level (\d+) \((\w+), (\d+) adena\)"),
    "town": re.compile(r"^PhantomHunterManager: (\w+) \((\d+)\) in ([^:]+): (.*)"),
    "store": re.compile(r"^PhantomHunterManager: (\w+) opens a store in (\w+): (\d+) items, price x([\d.]+)"),
    "store_close": re.compile(r"^PhantomHunterManager: (\w+) closes the store; (\d+) adena"),
    "goes_buy": re.compile(r"^PhantomHunterManager: (\w+) goes to buy (.+)"),
    "not_bought": re.compile(r"^PhantomHunterManager: (\w+) did not buy (.+)"),
    "bought": re.compile(r"^PhantomMarket: (\w+) bought (.+) from (\w+) for (\d+)"),
    # Скупка (раздел 4.29): лавка «Куплю …», продажи в скупку, купленное в своей скупке.
    "buy_store": re.compile(r"^PhantomHunterManager: (\w+) opens a buy store in \w+: (.+) for (\d+) \(shop (\d+)\)"),
    "goes_sell": re.compile(r"^PhantomHunterManager: (\w+) goes to sell (.+)"),
    "not_sold": re.compile(r"^PhantomHunterManager: (\w+) did not sell (.+)"),
    "sold": re.compile(r"^PhantomMarket: (\w+) sold (.+) to (\w+) for (\d+)"),
    "got_bought": re.compile(r"^PhantomHunterManager: (\w+) got (.+) in the buy store"),
    # Рейды охотников на РБ (раздел 4.32).
    "raid": re.compile(r"^PhantomRaid: (\w+) \(\d+\) leads a raid on (.+?) \((\d+)\) at (.+?): (.+?); fight"),
    "raid_win": re.compile(r"^PhantomRaid: (\w+)'s raid killed (.+?) in (\d+) s"),
    "raid_fail": re.compile(r"^PhantomRaid: (\w+)'s raid on (.+?) failed \((.+?)\)"),
    "raid_join": re.compile(r"^PhantomRaid: (\w+) \(\d+\) joins the raid on (.+)\."),
    "walk": re.compile(r"^PhantomHunterManager: (\w+) walks to (.+?) \(мобы"),
    "gk": re.compile(r"^PhantomHunterManager: (\w+) runs to gatekeeper \S+ \(\d+\) for (.+)\.$"),
    "stuck": re.compile(r"^PhantomHunterManager: (\w+) got stuck walking"),
    "gk_fail": re.compile(r"^PhantomHunterManager: (\w+) did not reach the gatekeeper"),
    "back": re.compile(r"^PhantomHunterManager: (\w+) teleports back to (.+?) \("),
    "killed": re.compile(r"^PhantomHunterManager: (\w+) was killed by (.+?) \("),
    "decides": re.compile(r"^PhantomHunterManager: Strategy: (\w+) \(\d+, .*\) decides (\w+)"),
    "quest": re.compile(r"^PhantomHunterManager: (\w+) turned in the quest"),
    "party": re.compile(r"^PhantomHunterParty: (\w+) собрал пати: (.+?) \("),
    "party_end": re.compile(r"^PhantomHunterParty: пати (\w+) .*распущена"),
    "joined": re.compile(r"^PhantomCompanionManager: (\w+) joined the party of (\w+)"),
    "contested": re.compile(r"^PhantomRelations: (\w+) contested (\w+)"),
    "pvp_kill": re.compile(r"^PhantomRelations: (\w+) killed (\w+)"),
    "brawl": re.compile(r"^PhantomBrawl: (.+)"),
}


def log_lines(log: Path, since: dt.datetime):
    """События из всех java*.log (ротация при каждом старте), по времени, без повторов."""
    seen, out = set(), []
    for f in sorted(log.glob("java*.log"), key=lambda p: p.stat().st_mtime):
        if dt.datetime.fromtimestamp(f.stat().st_mtime) < since:
            continue
        for raw in f.read_text(encoding="utf-8", errors="replace").splitlines():
            m = LINE.match(raw)
            if not m:
                continue
            t = dt.datetime.strptime(m[1], "%Y.%m.%d %H:%M:%S")
            if t >= since and raw not in seen:
                seen.add(raw)
                out.append((t, m[2], m[3], m[4]))
    out.sort(key=lambda e: e[0])
    return out


def spot_regions(data: Path) -> dict[str, set[str]]:
    regions = collections.defaultdict(set)
    f = data / "L2HubHuntSpots.xml"
    if f.exists():
        for s in ET.parse(f).getroot().iter("spot"):
            regions[s.get("name")].add(s.get("region"))
    return regions


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--minutes", type=int, default=60, help="период до текущего момента, минут (по умолчанию 60)")
    ap.add_argument("--since", help='начало периода: "ГГГГ.ММ.ДД ЧЧ:ММ"')
    ap.add_argument("--log", type=Path, default=GAME / "log", help="папка game/log сервера")
    args = ap.parse_args()
    now = dt.datetime.now()
    since = dt.datetime.strptime(args.since, "%Y.%m.%d %H:%M") if args.since else now - dt.timedelta(minutes=args.minutes)
    events = log_lines(args.log, since)
    print(f"# Охотники: {since:%d.%m %H:%M} — {now:%H:%M} ({(now - since).total_seconds() / 60:.0f} мин, событий в логе: {len(events)})")

    hits = collections.defaultdict(list)
    warnings = []
    for t, level, logger, msg in events:
        if level in ("WARNING", "SEVERE") and not msg.startswith(NOISE):
            warnings.append(f"{t:%H:%M:%S} {level} {logger.rsplit('.', 1)[-1]}: {msg[:200]}")
        for kind, rx in EVENTS.items():
            m = rx.match(msg)
            if m:
                hits[kind].append((t, m.groups()))
                break

    # Срез: где кто сейчас.
    status = args.log / "L2HubHunters.json"
    hunters = []
    if status.exists():
        st = json.loads(status.read_text(encoding="utf-8"))
        age = now.timestamp() - st["time"] / 1000
        hunters = st["hunters"]
        print(f"\n## Сейчас ({len(hunters)} охотников, срез {age:.0f} с назад{' — СЕРВЕР, ПОХОЖЕ, НЕ ПИШЕТ' if age > 120 else ''})")
        regions = spot_regions(args.log.parent / "data")  # данные того же сервера, что и лог
        by_zone = collections.defaultdict(list)
        for h in hunters:
            zone = "/".join(sorted(regions.get(h.get("spot") or "", set()))) or "-"
            by_zone[zone].append(h)
        for zone, hs in sorted(by_zone.items(), key=lambda kv: -len(kv[1])):
            print(f"- {zone}: " + ", ".join(f"{h['name']} {h['level']} {h['phase']}{' пати ' + h['party'] if h.get('party') else ''} @ {h.get('spot') or h.get('town')}"
                                           for h in sorted(hs, key=lambda h: h["level"])))
        spots = collections.Counter(h["spot"] for h in hunters if h.get("spot") and h["phase"] == "HUNT")
        crowded = [f"{s} ×{c}" for s, c in spots.items() if c > 2]
        if crowded:
            print("- больше двух на одном имени спота (может быть разные места): " + ", ".join(crowded))

    # Прогресс.
    print("\n## Прогресс")
    ups = collections.defaultdict(list)
    for _, (name, lv, _cls, _adena) in hits["level"]:
        ups[name].append(int(lv))
    if ups:
        print("- уровни: " + ", ".join(f"{n} {min(v) - 1}→{max(v)}" for n, v in sorted(ups.items(), key=lambda kv: -max(kv[1]))))
    stalled = [h["name"] for h in hunters if h["name"] not in ups and h["level"] < 85]
    if stalled and (now - since).total_seconds() >= 3600:
        print(f"- без нового уровня за период: {', '.join(stalled)}")
    print(f"- квестов сдано: {len(hits['quest'])}; решений стратега: {dict(collections.Counter(g[1] for _, g in hits['decides']))}")

    # Город, лавки, рынок.
    print("\n## Город, лавки, рынок")
    print(f"- в городе: {len(hits['town'])} раз; лавок открыто: {len(hits['store'])}, закрыто: {len(hits['store_close'])}")
    if hits["store"]:
        print("- лавки: " + ", ".join(f"{g[0]} {g[2]} шт. x{g[3]}" for _, g in hits["store"][-10:]))
    print(f"- рынок: идут покупать {len(hits['goes_buy'])}, купили {len(hits['bought'])}, не купили {len(hits['not_bought'])}")
    for t, g in hits["bought"]:
        print(f"  - {t:%H:%M} {g[0]} купил {g[1]} у {g[2]} за {g[3]}")
    print(f"- скупка: лавок открыто {len(hits['buy_store'])}, идут продавать {len(hits['goes_sell'])}, продали {len(hits['sold'])}, "
          f"не продали {len(hits['not_sold'])}, получили в своей скупке {len(hits['got_bought'])}")
    for t, g in hits["buy_store"][-10:]:
        print(f"  - {t:%H:%M} {g[0]}: куплю {g[1]} за {g[2]} (магазин {g[3]})")
    for t, g in hits["sold"]:
        print(f"  - {t:%H:%M} {g[0]} продал {g[1]} в скупку {g[2]} за {g[3]}")
    broke = [f"{h['name']} {h['adena']}" for h in hunters if h.get("adena", 0) < 1000]
    if broke:
        print(f"- почти без адены: {', '.join(broke)}")

    # Рейды.
    if hits["raid"] or hits["raid_win"] or hits["raid_fail"]:
        print("\n## Рейды на РБ")
        for t, g in hits["raid"]:
            print(f"- {t:%H:%M} {g[0]} собрал рейд на {g[1]} ({g[2]}) у {g[3]}: {g[4]}")
        for t, g in hits["raid_join"]:
            print(f"  - {t:%H:%M} вступил живой игрок {g[0]} ({g[1]})")
        for t, g in hits["raid_win"]:
            print(f"  - {t:%H:%M} {g[1]} убит за {int(g[2]) // 60} мин {int(g[2]) % 60} с")
        for t, g in hits["raid_fail"]:
            print(f"  - {t:%H:%M} рейд на {g[1]} провален: {g[2]}")

    # Перемещения.
    print("\n## Перемещения")
    print(f"- ногами на спот: {len(hits['walk'])}; к гейткиперу: {len(hits['gk'])}; застряли в пути: {len(hits['stuck'])}; не дошли до ГК: {len(hits['gk_fail'])}; "
          f"из города на свой спот через ГК: {len(hits['back'])}")
    if hits["gk"]:
        print("- куда через ГК: " + ", ".join(f"{g[0]}→{g[1]}" for _, g in hits["gk"][-15:]))

    # Пати и конфликты.
    print("\n## Пати и конфликты")
    print(f"- пати охотников собрано: {len(hits['party'])}, распущено: {len(hits['party_end'])}; вступлений в пати: {len(hits['joined'])}")
    for _, g in hits["party"][-8:]:
        print(f"  - {g[0]}: {g[1]}")
    print(f"- отжатых мобов: {len(hits['contested'])}; убийств охотниками: {len(hits['pvp_kill'])}; охотников убили: {len(hits['killed'])}; строк махачей: {len(hits['brawl'])}")
    for t, g in hits["brawl"][-8:]:
        print(f"  - {t:%H:%M} {g[0][:160]}")
    dead = [h["name"] for h in hunters if h["phase"] == "DEAD"]
    if dead:
        print(f"- сейчас мёртвые: {', '.join(dead)}")

    # Подозрительное.
    flags = []
    for kind, limit, text in (("stuck", 3, "часто застревает"), ("gk_fail", 2, "не доходит до ГК")):
        for name, c in collections.Counter(g[0] for _, g in hits[kind]).items():
            if c >= limit:
                flags.append(f"{name}: {text} ({c})")
    print("\n## Подозрительное")
    print("\n".join(f"- {f}" for f in flags) if flags else "- ничего")

    print("\n## Предупреждения сервера")
    print("\n".join(f"- {w}" for w in warnings[-15:]) if warnings else "- нет")
    err = args.log / "error0.log"
    if err.exists() and err.stat().st_size:
        print(f"- error0.log: {err.stat().st_size} байт, конец:\n" + err.read_text(encoding="utf-8", errors="replace")[-1500:])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
