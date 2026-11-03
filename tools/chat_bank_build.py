"""Банк реплик фейков: собирает L2HubChatBank.xml и L2HubChatBankMat.xml из исходных таблиц вклада пользователя.

    python -X utf8 -I tools/chat_bank_build.py <папка с LocalReplyBank.xml и LocalCityDialogues.xml> [папка выхода]

Выход по умолчанию — phantoms/core/dist/game/data. Исходник читается только как данные. Что делает:
- строки с dialogue_id из общей таблицы не берёт: городские есть в файле сцен, остальные — обрывки чужих разговоров;
- строку и сцену с именем несуществующего человека выбрасывает: в мире их никто не знает;
- строку и сцену с матом уносит в отдельный файл (свой ключ HunterChatBankMat);
- ставит род: g="m" или g="f" — фраза от мужского или женского лица; у сцены род считается на роль;
- переводит persona в короткий код; повторы в той же ситуации и канале убирает.

Подробности — docs/PHANTOMS.md, раздел про банк реплик. Вывод детерминирован: без правки исходника и этого файла совпадает байт в байт.
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "phantoms" / "core" / "dist" / "game" / "data"

PERSONA = {
    "общительный": "social",
    "разговорчивый": "talkative",
    "ироничный": "ironic",
    "деловой": "business",
    "осторожный": "careful",
    "спокойный": "calm",
    "дерзкий": "bold",
}
ROLE_NAMES = "ABCDEFGH"
MAX_LINE = 180

# Люди, которых в мире нет. Регистр важен: «серый плащ» остаётся.
NAMES = re.compile(r"\b(Серый|Серого|Серому|Серым|Сер[ёе]г\w*|Лёх\w*|Лёш\w*|Дим[ауеы]|Димон\w*|Саш\w*|Сань\w*|Паш\w*|Кост[яюе]|Денис\w*)\b")

MAT = re.compile(
    r"\b(?:"
    r"(?:ни|на|по|за|а|о|до|вы|у|рас|от)?х[уy][йеёяюи]\w*"
    r"|(?:на|по|ни|до)?хер(?:а|ня|ню|ни|не|ов\w*)?"
    r"|\w*пизд\w*"
    r"|(?:за|до|вы|от|по|у|на|раз|рас|съ|при|про|под|подъ|пере|из|въ)?[её]б(?:а|у|ы|ё|л|н|и|е)\w*"
    r"|долбо[её]б\w*"
    r"|бля\w*"
    r"|сук[аиеу]|сучк\w*|сучар\w*"
    r"|мудак\w*|мудил\w*|мудо\w*"
    r"|пид[оа]р\w*|пидр\w*"
    r"|гандон\w*|залуп\w*|дроч\w*|шлюх\w*"
    r"|говн\w*|жоп\w*|срать|сран\w*|ссыкл\w*|манда"
    r")\b",
    re.I,
)

# Род фразы. Лишняя пометка безвредна (фразу не скажет другой род), пропущенная видна в игре.
MASC_NOUN = set(
    "хил скил кил мил сил дел мел цел бел смел предел прицел раздел отдел удел передел тел стрел расстрел обстрел пробел чел чёл козёл орёл осёл котёл "
    "канал сигнал журнал вокзал зал бал вал нал кал мал финал портал капитал материал кристал арсенал идеал квартал скандал персонал минимал кинжал "
    "ритуал сериал оригинал интервал подвал перевал завал провал обвал навал развал пал сал нормал реал дуал кардинал генерал адмирал терминал трибунал минерал "
    "гул стул аул караул мул пул могил жил тыл пыл крыл".split()
)
FEM_NOUN = set(
    "сначала начала вначала сила могила мила дела тела мела стрела цела бела смела пчела кала нала вала зала бала мала канала сигнала портала журнала материала "
    "кинжала прицела предела раздела отдела стула аула караула хила скила мобила крокодила правила чернила светила чела одеяла мыла рыла крыла тыла пыла финала "
    "сала запала завала провала перевала подвала кардинала генерала вышла_из".split()
)
MASC_VERB = re.compile(r"\b[а-яё]{1,}(?:ал|ял|ил|ыл|ел|ул|ёл)(?:ся)?\b")
FEM_VERB = re.compile(r"\b[а-яё]{1,}(?:ала|яла|ила|ыла|ела|ула|шла|огла|егла|ёгла)(?:сь)?\b")
MASC_WORD = re.compile(r"\b(?:мог|смог|помог|лёг|прилёг|застрял|привык|отвык|сдох|умер|погиб|готов|рад|занят|уверен|должен|согласен|свободен|один|сам|виноват|голоден|новенький|пьян|жив)\b")
FEM_WORD = re.compile(r"\b(?:готова|рада|занята|уверена|должна|согласна|свободна|одна|сама|виновата|голодна|новенькая|пьяна|жива)\b")
# Последний такой подлежащий перед глаголом решает, о ком речь.
THIRD = set(
    "вы он она оно они кто кого кому никто это тот та этот эта который которая все всё "
    "мана мп хп цель вещь очередь регистрация рега команда группа пати сторона разведка кошка запись цена игра сумка лавка "
    "план стан хил бафер баф танк лидер бой матч босс рб моб кд инвайт дроп лут сбор кач клан друг спот шмот пул ивент твт сервер ник чат час народ".split()
)
SUBJECT = re.compile(r"\b(я|мы|ты|" + "|".join(sorted(THIRD, key=len, reverse=True)) + r")\b")


def gender_marks(text):
    """@return (род говорящего, род собеседника): "m", "f", "" или "mf" — встретились оба."""
    speaker = set()
    listener = set()
    for clause in re.split(r"[,.!?;:…()—]+|\s-\s", text.lower()):
        marks = []
        for match in MASC_VERB.finditer(clause):
            if match.group(0) not in MASC_NOUN:
                marks.append((match.start(), "m"))
        for match in FEM_VERB.finditer(clause):
            if match.group(0) not in FEM_NOUN:
                marks.append((match.start(), "f"))
        for match in MASC_WORD.finditer(clause):
            marks.append((match.start(), "m"))
        for match in FEM_WORD.finditer(clause):
            marks.append((match.start(), "f"))
        for at, kind in marks:
            subjects = SUBJECT.findall(clause[:at])
            last = subjects[-1] if subjects else "я"
            if last == "ты":
                listener.add(kind)
            elif last not in THIRD:
                speaker.add(kind)
    return "".join(sorted(speaker, reverse=True)), "".join(sorted(listener, reverse=True))


def valid(text):
    return text and (len(text) <= MAX_LINE) and not any(ord(c) < 32 for c in text) and ("[[" not in text)


def attr(name, value):
    return f" {name}={quoteattr(value)}" if value else ""


def header(kind):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f"<!-- Банк реплик фейков, {kind}. Собирает tools/chat_bank_build.py из таблиц вклада пользователя, руками не править. -->\n"
        "<chatBank>\n"
    )


def build_singles(lines, stats):
    """@return строки XML: обычные и с матом."""
    plain = []
    mat = []
    seen = set()
    for line in lines:
        text = (line.text or "").strip()
        if line.get("dialogue_id"):
            stats["строк сцен и обрывков в общей таблице"] += 1
            continue
        if not valid(text):
            stats["битых строк"] += 1
            continue
        if NAMES.search(text):
            stats["строк с несуществующими именами"] += 1
            continue
        key = (line.get("context"), line.get("situation"), line.get("channel"), text)
        if key in seen:
            stats["повторов"] += 1
            continue
        seen.add(key)
        speaker, _ = gender_marks(text)
        if speaker == "mf":
            stats["строк с обоими родами"] += 1
            speaker = ""
        stats["одиночных с родом " + (speaker or "любым")] += 1
        row = f'\t<line c="{line.get("context")}" s="{line.get("situation")}" ch="{line.get("channel")}" p="{PERSONA[line.get("persona")]}"{attr("g", speaker)}>{escape(text)}</line>\n'
        (mat if MAT.search(text) else plain).append(row)
    return plain, mat


def build_scenes(scenes, bank, stats):
    """@return сцены XML: обычные и с матом."""
    plain = []
    mat = []
    for scene in scenes:
        lines = list(scene.iter("line"))
        texts = [(line.text or "").strip() for line in lines]
        if not all(valid(text) for text in texts) or any(("{" in text) or ("}" in text) for text in texts):
            stats["битых сцен"] += 1
            continue
        if any(NAMES.search(text) for text in texts):
            stats["сцен с несуществующими именами"] += 1
            continue

        order = []
        for line in lines:
            if line.get("role") not in order:
                order.append(line.get("role"))
        if not (2 <= len(lines) <= 8) or not (2 <= len(order) <= len(ROLE_NAMES)):
            stats["сцен не по размеру"] += 1
            continue

        votes = {role: Counter() for role in order}
        persona = {}
        for line, text in zip(lines, texts):
            role = line.get("role")
            persona[role] = PERSONA[bank[line.get("id")].get("persona")]
            speaker, listener = gender_marks(text)
            for kind in speaker:
                votes[role][kind] += 1
            if len(order) == 2:
                other = order[1] if role == order[0] else order[0]
                for kind in listener:
                    votes[other][kind] += 1

        gender = {}
        broken = False
        for role in order:
            m, f = votes[role]["m"], votes[role]["f"]
            if m and f and (m == f):
                broken = True
            gender[role] = "m" if m > f else "f" if f > m else ""
        if broken:
            stats["сцен, где роль и он, и она"] += 1
            continue
        for role in order:
            stats["ролей сцен с родом " + (gender[role] or "любым")] += 1

        name = {role: ROLE_NAMES[i] for i, role in enumerate(order)}
        rows = [f'\t<scene id={quoteattr(scene.get("id"))}>\n']
        for role in order:
            rows.append(f'\t\t<role id="{name[role]}" p="{persona[role]}"{attr("g", gender[role])}/>\n')
        for line, text in zip(lines, texts):
            rows.append(f'\t\t<line role="{name[line.get("role")]}">{escape(text)}</line>\n')
        rows.append("\t</scene>\n")
        (mat if any(MAT.search(text) for text in texts) else plain).append("".join(rows))
    return plain, mat


def write(path, kind, rows):
    data = (header(kind) + "".join(rows) + "</chatBank>\n").encode("utf-8")
    path.write_bytes(data)
    print(f"{path.name}: {len(data)} байт")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    source = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else OUT
    lines = list(ET.parse(source / "LocalReplyBank.xml").getroot().iter("line"))
    scenes = list(ET.parse(source / "LocalCityDialogues.xml").getroot().iter("scene"))
    bank = {line.get("id"): line for line in lines}

    stats = Counter()
    singles, singles_mat = build_singles(lines, stats)
    scene_rows, scene_mat = build_scenes(scenes, bank, stats)

    write(out / "L2HubChatBank.xml", "без мата", singles + scene_rows)
    write(out / "L2HubChatBankMat.xml", "только с матом", singles_mat + scene_mat)

    print(f"одиночных строк: {len(singles)} и {len(singles_mat)} с матом; сцен: {len(scene_rows)} и {len(scene_mat)} с матом")
    for key in sorted(stats):
        print(f"  {key}: {stats[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
