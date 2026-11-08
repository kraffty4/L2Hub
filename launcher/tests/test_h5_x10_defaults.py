"""Штатный x10 H5: актуальные параметры применяются без локального личного набора."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from l2hub import modes


class H5X10DefaultsCheck(unittest.TestCase):
    def test_defaults_are_h5_scoped(self):
        mode=next(x for x in modes.table()['modes'] if x['id']=='x10')
        h5=modes._settings({'id':'ct26_highfive'},mode)
        ep=modes._settings({'id':'epilogue'},mode)
        self.assertEqual(mode['title'],'x10')
        self.assertEqual(h5['Rates.ini']['RatePartyXp'],'1.5')
        self.assertEqual(h5['Rates.ini']['RateKarmaLost'],'0.5')
        self.assertEqual(h5['L2HubWorld.ini']['PlayerPAtkSpeedMultiplier'],'1.15')
        self.assertEqual(h5['GrandBoss.ini']['SmallWorldEpics'],'True')
        self.assertEqual(ep['Rates.ini']['RatePartyXp'],'1')
        self.assertEqual(ep['L2HubWorld.ini']['PlayerPAtkSpeedMultiplier'],'1.1')
        self.assertEqual(ep['GrandBoss.ini']['SmallWorldEpics'],'False')

    def test_standard_apply_sets_all_updated_values(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'local/checks') as directory:
            root=Path(directory);server=root/'server';cfg=server/'game/config';cfg.mkdir(parents=True)
            inputs={'Rates.ini':{'RatePartyXp':'1','RatePartySp':'1','RateKarmaLost':'1','DeathDropChanceMultiplier':'1','SpoilDropChanceMultiplier':'1'},
                    'L2HubWorld.ini':{'PlayerPAtkSpeedMultiplier':'1','PlayerMAtkSpeedMultiplier':'1'},
                    'GrandBoss.ini':{'SmallWorldEpics':'False'},'General.ini':{'EverybodyHasAdminRights':'True','ServerGMOnly':'True'}}
            for file,keys in inputs.items():(cfg/file).write_text(''.join(f'{key} = {value}\n' for key,value in keys.items()))
            with patch.object(modes.catalog,'server_dir',return_value=server),patch.object(modes.gmshop,'set_mode_grades'),patch.object(modes.paths,'BACKUPS',root/'backups'):
                modes.apply_mode({'id':'ct26_highfive'},'x10')
            self.assertIn('RatePartyXp = 1.5',(cfg/'Rates.ini').read_text())
            self.assertIn('RatePartySp = 1.5',(cfg/'Rates.ini').read_text())
            self.assertIn('RateKarmaLost = 0.5',(cfg/'Rates.ini').read_text())
            self.assertIn('DeathDropChanceMultiplier = 2',(cfg/'Rates.ini').read_text())
            self.assertIn('SpoilDropChanceMultiplier = 2',(cfg/'Rates.ini').read_text())
            self.assertIn('PlayerPAtkSpeedMultiplier = 1.15',(cfg/'L2HubWorld.ini').read_text())
            self.assertIn('PlayerMAtkSpeedMultiplier = 1.15',(cfg/'L2HubWorld.ini').read_text())
            self.assertIn('SmallWorldEpics = True',(cfg/'GrandBoss.ini').read_text())
            self.assertIn('EverybodyHasAdminRights = False',(cfg/'General.ini').read_text())
            self.assertIn('ServerGMOnly = False',(cfg/'General.ini').read_text())


if __name__=='__main__':unittest.main()
