"""Native INI controls Epilogue: metadata, edit roundtrip and ordinary Java command."""
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from l2hub import configs, modes, servers


class NativeControlsTest(unittest.TestCase):
    def test_custom_groups_and_file_scopes(self):
        profile={'id':'epilogue','type':'mobius'}
        page=next(p for p in modes.pages(profile) if p['id']=='custom')
        rows=[s for s in page['subs'] if s.get('chronicles')==['epilogue']]
        scoped={(s.get('file'),k[0]) for s in rows for k in s.get('keys',[])}
        self.assertIn(('AppearanceServices.ini','Enabled'),scoped)
        self.assertIn(('PartyDiagnostics.ini','Enabled'),scoped)
        self.assertIn(('PersonalCompanionNames.ini','healer'),scoped)
        self.assertIn(('PersonalCompanionNamePool.ini','PreferredNames'),scoped)
        self.assertIn(('PhantomHunters.ini','HunterOlympiadPreparation'),scoped)
        self.assertIn(('PhantomHunters.ini','HunterClanWarTruceHours'),scoped)
        self.assertIn(('PlayingGm.ini','PlayingGmDefaultMode'),scoped)
        switch=next(s for s in modes.table()['switches'] if s['id']=='instantclanleader')
        self.assertEqual((switch['file'],switch['key'],switch['restart'],switch['bulk']),('Player.ini','AltClanLeaderInstantActivation','game',False))
        self.assertIn('instantclanleader',[i for s in page['subs'] for i in s.get('ids',[])])

    def test_roundtrip_does_not_enable_other_custom(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp);d=root/'game/config/Custom';d.mkdir(parents=True)
            (root/'game/config/Player.ini').write_text('AltClanLeaderInstantActivation = False\nOtherValue = 123\n',encoding='utf-8')
            (d/'PartyDiagnostics.ini').write_text('Enabled=False\nHunters=Probe\n',encoding='utf-8')
            with patch.object(modes.catalog,'server_dir',return_value=root),patch.object(modes.paths,'BACKUPS',root/'backups'):
                p={'id':'epilogue','type':'mobius'};before=(d/'PartyDiagnostics.ini').read_bytes()
                self.assertFalse(next(s for s in modes.switches(p) if s['id']=='instantclanleader')['value'])
                modes.set_switch(p,'instantclanleader',True)
                self.assertTrue(next(s for s in modes.switches(p) if s['id']=='instantclanleader')['value'])
                modes.set_switch(p,'instantclanleader',False)
                self.assertFalse(next(s for s in modes.switches(p) if s['id']=='instantclanleader')['value'])
                self.assertEqual((d/'PartyDiagnostics.ini').read_bytes(),before)
                self.assertIn('OtherValue = 123',(root/'game/config/Player.ini').read_text())

    def test_ordinary_epilogue_start_command_is_direct_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=pathlib.Path(tmp);(d/'java.cfg').write_text('-Xms1g -Xmx8g\n',encoding='utf-8')
            with patch.object(servers,'bundle',return_value=None),patch.object(servers,'with_game_heap',side_effect=lambda a:a):
                command=list(map(str,servers._part_command({'id':'epilogue','type':'mobius'},'game',d)))
            self.assertEqual(command[-2:],['-jar','../libs/GameServer.jar'])
            self.assertFalse(any('javaagent' in x.lower() or x.lower().endswith(('.cmd','.bat')) for x in command))

    def test_scoped_string_and_list_keys_keep_native_types(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)/'PersonalCompanionNames.ini';p.write_text('healer=TestHealer\nPreferredNames=One,Two\nPrice=100000000\n',encoding='utf-8')
            items={k['key']:k['type'] for s in configs.parse_ini(p)['sections'] for k in s['keys']}
            self.assertEqual(items,{'healer':'string','PreferredNames':'list','Price':'int'})


if __name__ == '__main__': unittest.main()
