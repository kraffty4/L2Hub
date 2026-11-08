"""Сохранение публичных настроек: валидация всей пачки до записи и резервная копия."""
import tempfile
import unittest
from pathlib import Path
from l2hub import configs, modes


class X10SettingsCheck(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.file = self.root / 'game/config/Custom/L2HubX10.ini'
        self.file.parent.mkdir(parents=True)
        self.original = b'# retained\r\nFarmCoins = 100\r\nCoinDropChance = 200\r\nFarmEnabled = True\r\n'
        self.file.write_bytes(self.original)

    def edit(self, key, value):
        return dict(part='game',file='L2HubX10.ini',key=key,value=value)

    def save(self, *edits):
        return configs.save_edits(self.root, self.root/'backups', list(edits))

    def test_numeric_roundtrip_and_backup(self):
        result=self.save(self.edit('FarmCoins','1e3'), self.edit('CoinDropChance','125'),self.edit('FarmEnabled','false'))
        self.assertEqual(result['saved'],3)
        self.assertIn(b'FarmCoins = 1000\r\n',self.file.read_bytes())
        self.assertIn(b'FarmEnabled = False\r\n',self.file.read_bytes())
        self.assertEqual(next((self.root/'backups').rglob('L2HubX10.ini')).read_bytes(),self.original)

    def test_invalid_batch_changes_nothing(self):
        for value in ('','NaN','Infinity','-1','100001','3.5','100\nFarmEnabled = False'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.save(self.edit('CoinDropChance','50'), self.edit('FarmCoins',value))
            self.assertEqual(self.file.read_bytes(),self.original)
        self.assertFalse((self.root/'backups').exists())

    def test_strict_bool_and_file_scope(self):
        with self.assertRaises(ValueError): self.save(self.edit('FarmEnabled','1'))
        other=self.file.with_name('Other.ini')
        other.write_text('FarmCoins = custom\n')
        self.save(dict(part='game',file='Other.ini',key='FarmCoins',value='legacy'))
        self.assertEqual(other.read_text(),'FarmCoins = legacy\n')

    def test_unlock_levels_reject_invalid_batch_before_writing(self):
        self.file.write_bytes(self.original+b'DailyMinLevel = 40\r\nWeeklyMinLevel = 50\r\nAdenaAlwaysDrop = False\r\n')
        before=self.file.read_bytes()
        with self.assertRaises(ValueError):self.save(self.edit('DailyMinLevel','60'),self.edit('AdenaAlwaysDrop','True'))
        self.assertEqual(self.file.read_bytes(),before)
        self.save(self.edit('DailyMinLevel','60'),self.edit('WeeklyMinLevel','65'),self.edit('AdenaAlwaysDrop','True'))
        self.assertIn(b'WeeklyMinLevel = 65',self.file.read_bytes())

    def test_every_gui_control_has_matching_rule_and_default(self):
        table=modes.table()
        parsed=configs.parse_ini(Path(__file__).resolve().parents[2]/'phantoms/ct26_highfive/dist/game/config/Custom/L2HubX10.ini')
        keys={k['key']:k for section in parsed['sections'] for k in section['keys']}
        pages=[p for p in table['pages'] if p.get('file')=='L2HubX10.ini']
        self.assertEqual(len(pages),7)
        for page in pages:
            self.assertEqual(page['file'],'L2HubX10.ini')
            for sub in page['subs']:
                for key,title,_,_,meta in sub.get('keys',[]):
                    rule=table['validation']['L2HubX10.ini'][key]
                    self.assertEqual(meta['min']*meta['scale'],rule['min'])
                    self.assertEqual(meta['max']*meta['scale'],rule['max'])
                    self.assertFalse(keys[key]['secret'])
                    self.assertTrue(meta['hint'])


if __name__=='__main__': unittest.main()
