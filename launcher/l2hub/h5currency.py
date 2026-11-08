"""Две валюты H5: дополнение существующих DAT без изменений штатных записей.

Поля H5P4 сверены со схемами первичного проекта редактора:
https://github.com/majestic-world/L2Dat-Editor/tree/main/dist/data/structure/dats
Код редактора не исполняется. Иконки и модели берутся из клиентских записей4037/6392;
клиентские файлы/тексты не распространяются в репозитории.
"""
import json
import struct
from pathlib import Path
from . import l2ini

CURRENCIES = (
    (40001, 4037, 'Коины', 'Coins',
     'Награда за активное участие в ивентах и поручения. Редко выпадают с обычных мобов. Магазин: Alt+B - Ивенты - Магазин. Не передаются. За реальные деньги не продаются.',
     'Earned through active events and contracts; rare drops from ordinary monsters. Shop: Alt+B - Events - Shop. Personal, untradeable, never sold for real money.'),
    (40002, 6392, 'Жетоны стиля', 'Style Tokens',
     'За полноценное участие в ивентах. Покупка украшений без боевых характеристик: Alt+B - Ивенты - Магазин - Стиль. Не передаются.',
     'Earned through full event participation. Buy cosmetic accessories without combat stats: Alt+B - Events - Shop - Style. Personal and untradeable.'),
)

# Ярлык автобоя: запись заряда души (1835), чтобы клиент рисовал свечение авто-использования; своя иконка.
TOGGLES = (
    (40003, 1835, 'Автобой', 'Auto Combat',
     'Ярлык автобоя. Клик — пуск или стоп, правый клик — как авто-соски. Светится, пока автобой работает. Настройки: Alt+B - Автобой.',
     'Auto combat shortcut. Click to start or stop, or right-click like auto soulshots. Glows while auto combat is running. Settings: Alt+B - Auto combat.',
     'icon.skill0340'),
)

class Reader:
    def __init__(self, data): self.data=data; self.pos=0
    def take(self, length):
        if length < 0 or length > len(self.data)-self.pos: raise ValueError('Повреждённый DAT: выход за границу')
        value=self.data[self.pos:self.pos+length]; self.pos+=length; return value
    def uint(self): return struct.unpack('<I', self.take(4))[0]
    def compact(self):
        first=self.take(1)[0]; negative=first&128; value=first&63; shift=6
        if first&64:
            for _ in range(4):
                byte=self.take(1)[0]; value|=(byte&127)<<shift; shift+=7
                if not byte&128: break
            else: raise ValueError('Слишком длинная строка DAT')
        return -value if negative else value
    def node(self, node, scope):
        name=node['name']; start=self.pos; tag=node['tag']
        if tag=='node':
            kind=node['reader']
            if kind in ('UINT','INT','FLOAT'): value=self.uint()
            elif kind in ('UBYTE','BYTE'): value=self.take(1)[0]
            elif kind=='UNICODE': value=self.take(self.uint()).decode('utf-16-le').rstrip('\0')
            elif kind=='ASCF':
                length=self.compact(); value=self.take(-length*2 if length<0 else length).decode('utf-16-le' if length<0 else 'latin-1').rstrip('\0')
            else: raise ValueError('Неизвестное поле '+str(kind))
            scope[name]=value
        elif tag=='for':
            size=node['size']; count=scope[size[1:]] if size.startswith('#') else int(size)
            if not 0<=count<50000: raise ValueError('Повреждённое количество записей DAT')
            values=[]
            for _ in range(count):
                sub={'_start':self.pos}
                for child in node['children']: self.node(child, sub)
                sub['_end']=self.pos; values.append(sub)
            scope[name]=values
        elif tag=='wrapper':
            sub={}
            for child in node['children']: self.node(child, sub)
            scope[name]=sub
        scope.setdefault('_ranges',{})[name]=(start,self.pos)

def parse(raw, kind):
    schema=json.loads(Path(__file__).with_name('h5_currency_schema.json').read_text(encoding='utf-8'))[kind]
    reader=Reader(raw); data={}
    for node in schema['children']: reader.node(node,data)
    if raw[reader.pos:]!=b'\x0cSafePackage\0': raise ValueError('Формат DAT отличается от проверенного High Five P4')
    return data['item_name' if kind=='itemname' else 'item'], reader.pos

def compact(number):
    negative=number<0; value=abs(number); first=(value&63)|(128 if negative else 0); value>>=6
    out=bytearray([first|(64 if value else 0)])
    while value:
        byte=value&127; value>>=7; out.append(byte|(128 if value else 0))
    return bytes(out)

def unicode(value):
    raw=(value+'\0').encode('utf-16-le')
    return struct.pack('<I',len(raw))+raw

def ascf(value):
    return compact(-len(value)-1)+(value+'\0').encode('utf-16-le')

def patch(raw, kind, language='ru'):
    rows,end=parse(raw,kind); key='id' if kind=='itemname' else 'object_id'
    by_id={row[key]:row for row in rows}
    if len(by_id)!=len(rows): raise ValueError('Повторяющиеся ID клиента')
    additions={}
    for item_id,base_id,ru,en,ru_help,en_help,*extra in CURRENCIES+TOGGLES:
        if item_id in by_id and kind=='itemname' and by_id[item_id]['name'] not in (ru,en): raise ValueError('ID валюты занят чужим предметом: '+str(item_id))
        base=by_id[base_id]; replacements={base['_ranges'][key]:struct.pack('<I',item_id)}
        if kind=='itemname': replacements.update({base['_ranges']['name']:unicode(en if language=='en' else ru), base['_ranges']['description']:ascf(en_help if language=='en' else ru_help)})
        if kind=='etcitemgrp' and extra: replacements[base['icon']['_ranges']['icon1']]=unicode(extra[0])
        pieces=[]; cursor=base['_start']
        for (start,stop),value in sorted(replacements.items()):
            pieces.extend((raw[cursor:start],value));cursor=stop
        pieces.append(raw[cursor:base['_end']]);additions[item_id]=b''.join(pieces)
    records={row[key]:raw[row['_start']:row['_end']] for row in rows};records.update(additions)
    result=struct.pack('<I',len(records))+b''.join(records[i] for i in sorted(records))+raw[end:]
    verified,_=parse(result,kind); after={row[key]:result[row['_start']:row['_end']] for row in verified}
    if any(after[row[key]]!=raw[row['_start']:row['_end']] for row in rows if row[key] not in additions): raise ValueError('Затронута штатная запись')
    return result

def build(system: Path, output: Path, language='ru'):
    """Создаёт два новых DAT (валюты и ярлык автобоя) в отдельной папке, исходный клиент не меняет."""
    if language not in ('ru','en'): raise ValueError('Язык должен быть ru/en')
    if system.resolve()==output.resolve(): raise ValueError('Нужна отдельная выходная папка для проверки')
    output.mkdir(parents=True,exist_ok=True)
    report={}
    for name,kind in (('itemname-e.dat','itemname'),('etcitemgrp.dat','etcitemgrp')):
        key,raw=l2ini.decode((system/name).read_bytes())
        if key!='community': raise ValueError('Клиент должен поддерживать ключ комьюнити413')
        result=patch(raw,kind,language); encrypted=l2ini.encode(result)
        if l2ini.decode(encrypted)[1]!=result: raise ValueError('Контрольная расшифровка не совпала')
        (output/name).write_bytes(encrypted); report[name]=len(encrypted)
    return report
