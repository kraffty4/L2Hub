"""Данные профы квестом для охотников High Five (docs/PHANTOMS.md, раздел 4.152): 1-я и 2-я профа.

Берёт базы знаний квестов (вне git, по умолчанию data/knowledge/h5_quests_prof_1.json и h5_quests_prof_2.json), сверяет каждый номер с Java квеста
и мастеров смены класса в исходниках Mobius и пишет только номера: квест, скрипт, ступень, уровень, стартовый NPC, знак, по шагам NPC и мобы;
пути классов — квесты и мастера. Номер, которого нет в Java, выпадает с предупреждением. Текст гайдов и имена предметов в вывод не идут: знак — id
из награды, который берёт мастер (так отсекаются выдуманные награды вроде «Crystal (D-Grade)» в Q216 и Q221).

    python -X utf8 tools/profession_quests.py [--prof1 база1.json] [--prof2 база2.json] [--src servers/ct26_highfive/L2J_Mobius_CT_2.6_HighFive] [--out phantoms/ct26_highfive/dist/game/data/L2HubProfessionQuests.xml] [--check]

--check — только сверить с готовым файлом (код 1, если вывод отличается).
"""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MASTERS = {
    1: ('ElfHumanFighterChange1', 'ElfHumanWizardChange1', 'DarkElfChange1', 'OrcChange1', 'DwarfBlacksmithChange1',
        'DwarfWarehouseChange1', 'KamaelChange1', 'FirstClassTransferTalk'),
    2: ('ElfHumanFighterChange2', 'ElfHumanWizardChange2', 'ElfHumanClericChange2', 'DarkElfChange2', 'OrcChange2',
        'DwarfBlacksmithChange2', 'DwarfWarehouseChange2', 'KamaelChange2'),
}
# Альтернатива всем трём (Q60 Good Works Reward) и класс без квестов (Inspector) — не наши пути.
SKIP_QUESTS = {60}


def has(text, number):
    return re.search(r'(?<![\d.])' + str(number) + r'(?![\d.])', text) is not None


def numbers(block):
    return [int(x) for x in re.findall(r'\b\d{5}\b', block)]


def master_npcs(java, cls):
    """NPC мастеров, у которых скрипт меняет класс на cls: «case "cls":» или строка таблицы CLASSES «{ cls, …»."""
    if not (re.search(r'case "' + str(cls) + r'":', java) or re.search(r'\{\s*' + str(cls) + r'\s*,', java)):
        return []
    out = []
    for m in re.finditer(r'int\[\]\s+NPCS\w*\s*=\s*\{([^}]*)\}', java):
        out += numbers(m.group(1))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--prof1', default=str(ROOT / 'data/knowledge/h5_quests_prof_1.json'))
    ap.add_argument('--prof2', default=str(ROOT / 'data/knowledge/h5_quests_prof_2.json'))
    ap.add_argument('--src', default=str(ROOT / 'servers/ct26_highfive/L2J_Mobius_CT_2.6_HighFive'))
    ap.add_argument('--out', default=str(ROOT / 'phantoms/ct26_highfive/dist/game/data/L2HubProfessionQuests.xml'))
    ap.add_argument('--check', action='store_true')
    a = ap.parse_args()

    scripts = pathlib.Path(a.src) / 'dist/game/data/scripts'
    masters = {}
    for tier, names in MASTERS.items():
        masters[tier] = {}
        for name in names:
            f = scripts / 'village_master' / name / (name + '.java')
            if not f.is_file():
                sys.exit(f'нет мастера {f}')
            masters[tier][name] = f.read_text(encoding='utf-8')

    warn = []
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<!-- L2Hub: профа охотника квестом (docs/PHANTOMS.md, раздел 4.152). Пишет tools/profession_quests.py: только номера, сверенные с Java квестов и мастеров. -->',
           '<!-- quest: tier — ступень профы, level — с какого уровня берётся, mark — знак, который берёт мастер. step: npc — с кем говорить, mobs — кого бить на этом шаге. -->',
           '<!-- path: квесты класса по порядку и мастера смены класса. weapon="fist" — квест на кулаки. -->',
           '<list>']
    paths_out = []
    for tier, base_path in ((1, a.prof1), (2, a.prof2)):
        bp = pathlib.Path(base_path)
        if not bp.is_file():
            warn.append(f'ступень {tier}: нет базы {bp}, пропущена')
            continue
        base = json.loads(bp.read_text(encoding='utf-8'))
        mtext = ''.join(masters[tier].values())
        good = {}
        for q in sorted(base['quests'], key=lambda x: (x['quest_id'] < 100, x['quest_id'])):
            qid, script = q['quest_id'], q['script']
            if qid in SKIP_QUESTS:
                continue
            jf = scripts / 'quests' / script / (script + '.java')
            if not jf.is_file():
                warn.append(f'Q{qid}: нет скрипта {script}, квест пропущен')
                continue
            java = jf.read_text(encoding='utf-8')
            marks = [r['id'] for r in q.get('reward', []) if r.get('id') and r['id'] != 57 and has(mtext, r['id']) and has(java, r['id'])]
            if not marks:
                warn.append(f'Q{qid}: знака из награды нет у мастеров, квест пропущен')
                continue
            start = q['start_npc']['id']
            if not has(java, start):
                warn.append(f'Q{qid}: стартового NPC {start} нет в Java, квест пропущен')
                continue
            level = int(q.get('min_level') or 1)
            fist = 'WeaponType.FIST' in java
            out.append(f'\t<quest id="{qid}" tier="{tier}" script="{script}" level="{level}" start="{start}" mark="{marks[0]}"' + (' weapon="fist"' if fist else '') + '>')
            for i, s in enumerate(q['steps']):
                npc = s.get('npc')
                if npc and not has(java, npc):
                    warn.append(f'Q{qid} шаг {i}: NPC {npc} нет в Java, выпал')
                    npc = None
                mobs = []
                for m in s.get('mobs') or []:
                    if has(java, m):
                        mobs.append(m)
                    else:
                        warn.append(f'Q{qid} шаг {i}: моба {m} нет в Java, выпал')
                if not npc and not mobs:
                    continue
                attrs = (f' npc="{npc}"' if npc else '') + (f' mobs="{",".join(map(str, mobs))}"' if mobs else '')
                out.append(f'\t\t<step{attrs}/>')
            out.append('\t</quest>')
            good[qid] = marks[0]

        for row in base['class_paths']:
            cls, quests = row['class_id'], [q for q in row['quests'] if q not in SKIP_QUESTS]
            if not quests:
                continue
            missing = [q for q in quests if q not in good]
            if missing:
                warn.append(f'ступень {tier}, класс {cls}: квестов {missing} нет в данных, путь пропущен')
                continue
            if row.get('marks') and [good[q] for q in quests] != row['marks']:
                warn.append(f'ступень {tier}, класс {cls}: знаки {[good[q] for q in quests]} против базы {row["marks"]}')
            npcs = []
            for java in masters[tier].values():
                if all(has(java, good[q]) for q in quests):
                    npcs += [n for n in master_npcs(java, cls) if n not in npcs]
            if tier == 1 and row.get('transfer_npc'):
                npcs = [row['transfer_npc']] + [n for n in npcs if n != row['transfer_npc']]
            if not npcs:
                warn.append(f'ступень {tier}, класс {cls}: нет мастера со знаками {[good[q] for q in quests]}, путь пропущен')
                continue
            paths_out.append(f'\t<path tier="{tier}" class="{cls}" quests="{",".join(map(str, quests))}" masters="{",".join(map(str, npcs))}"/>')
    out += paths_out
    out.append('</list>')
    text = '\n'.join(out) + '\n'

    for w in warn:
        print('!', w)
    target = pathlib.Path(a.out)
    if a.check:
        same = target.is_file() and target.read_bytes() == text.encode('utf-8')
        print('совпадает' if same else 'ОТЛИЧАЕТСЯ', target)
        sys.exit(0 if same else 1)
    target.write_bytes(text.encode('utf-8'))
    print(f'{text.count("<quest ")} квестов, {text.count("<path ")} путей -> {target}')


if __name__ == '__main__':
    main()
