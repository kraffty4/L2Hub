"""Единый каталог и безопасное сохранение прежних календарных/кроличьих настроек."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from l2hub import modes


class EventHubCheck(unittest.TestCase):
    def test_x10_enables_optional_features_other_modes_leave_them_configurable(self):
        for mode in modes.table()['modes']:
            if mode['id'] not in {'hardcore','x10','pvpcraft','pvp'}:continue
            settings=modes._settings({'id':'ct26_highfive'},mode)['L2HubX10.ini']
            for key in ('EventsEnabled','ContractsEnabled','CoinDropEnabled','FarmSpotsEnabled'):
                self.assertEqual(settings[key],'True' if mode['id']=='x10' else 'False')
            self.assertNotIn('Enabled',settings)

    def test_one_navigation_entry_and_complete_catalog(self):
        pages=modes.table()['pages']
        self.assertEqual(len(pages),len({p['id'] for p in pages}))
        self.assertEqual([p['id'] for p in pages if p.get('kind')=='eventHub'],['events'])
        self.assertFalse(any('Ивенты x10'==p.get('head') for p in pages))
        children=[p for p in pages if p.get('parent')=='events']
        self.assertEqual({p['section'] for p in children},{'catalog','rules','contracts','rewards'})
        self.assertEqual({p['id'] for p in children if p['section']=='catalog'},
                         {'event-farm','event-defence','event-hunt','event-pvp','event-elpies','event-seasonal'})
        self.assertIsNone(next(p for p in pages if p['id']=='farming').get('parent'))

    def test_calendar_separates_rewards_from_annual_dates_and_saves_only_requested(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'local/checks') as directory:
            root=Path(directory);cfg=root/'server/game/config/Custom';cfg.mkdir(parents=True)
            file=cfg/'L2HubEvents.ini'
            original='Enabled = True\nL2Day = 22 03-30 04\nFreyaCelebration = 16 11-01 01\nPvpLevelGap = 3\nPvpRewardMobs = 60\nPvpRewardScrolls = True\nElpies = True\nElpiesMinutes = 10\nElpiesSchedule = 30 */4 * * *\n'
            file.write_text(original)
            with patch.object(modes.catalog,'server_dir',return_value=root/'server'),patch.object(modes.servers,'part_process',return_value=None),patch.object(modes.paths,'BACKUPS',root/'backups'):
                p={'id':'ct26_highfive'}
                calendar=modes.calendar(p)
                self.assertEqual([x['key'] for x in calendar['events']],['L2Day','FreyaCelebration'])
                self.assertEqual(calendar['pvp']['PvpLevelGap']['type'],'int')
                modes.set_calendar(p,{'ElpiesMinutes':'12'})
                self.assertEqual(file.read_text(),original.replace('ElpiesMinutes = 10','ElpiesMinutes = 12'))
                before=file.read_bytes()
                with self.assertRaises(ValueError):modes.set_calendar(p,{'L2Day':'invalid','PvpRewardMobs':'90'})
                self.assertEqual(file.read_bytes(),before)


if __name__=='__main__':unittest.main()
