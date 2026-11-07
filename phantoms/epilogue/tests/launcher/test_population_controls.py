"""Достоверный счётчик Epilogue: только временные XML/INI, без JVM/БД."""
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from l2hub import modes


class PopulationControlsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        (self.root/'game/data').mkdir(parents=True)
        (self.root/'game/config/Custom').mkdir(parents=True)
        self.xml = self.root/'game/data/L2HubPhantoms.xml'
        self.ini = self.root/'game/config/Custom/PhantomHunters.ini'
        self.ini.write_text('HuntersMax = 700\n', encoding='utf-8')
        self.profile = {'id': 'epilogue', 'type': 'mobius'}
        self.patches = [patch.object(modes.catalog, 'server_dir', return_value=self.root),
                        patch.object(modes.paths, 'BACKUPS', self.root/'backups'),
                        patch.object(modes.servers, 'game_heap_gb', return_value=8),
                        patch.object(modes.machine, 'assess', return_value={})]
        for p in self.patches: p.start()

    def tearDown(self):
        for p in reversed(self.patches): p.stop()
        self.tmp.cleanup()

    def roster(self, hunters, helpers=11):
        self.xml.write_text('<list>' + ''.join(f'<phantom name="Pawn{i}" kind="hunter"/>' for i in range(hunters))
                            + ''.join(f'<phantom name="Helper{i}"/>' for i in range(helpers)) + '</list>', encoding='utf-8')

    def test_actual_700_and_711_templates(self):
        self.roster(700)
        before = self.ini.read_bytes()
        a = modes.hunters_max(self.profile)
        self.assertEqual((a['configured'], a['count'], a['top']), (700, 700, 700))
        self.assertEqual(a['roster']['templates'], 711)
        self.assertFalse(a['tableMismatch'])
        self.assertEqual(self.ini.read_bytes(), before)

    def test_stale_table_does_not_hide_or_rewrite_700(self):
        self.roster(219)
        before = self.ini.read_bytes()
        a = modes.hunters_max(self.profile)
        self.assertEqual((a['configured'], a['count'], a['top']), (700, 219, 219))
        self.assertTrue(a['tableMismatch'])
        with self.assertRaises(ValueError): modes.set_hunters_max(self.profile, 700)
        self.assertEqual(self.ini.read_bytes(), before)

    def test_parser_ignores_comments_and_matches_last_name_wins(self):
        self.xml.write_text('''<list><!-- kind="hunter" -->
            <phantom name='One' kind='hunter'/><phantom name="ONE"/>
            <phantom name="Two" kind="hunter"/><phantom name="Reaper" kind="reaper"/>
            </list>''', encoding='utf-8')
        self.assertEqual(modes.hunter_roster(self.profile), {'hunters': 1, 'companions': 1, 'templates': 3, 'readable': True})

    def test_save_and_reopen_preserves_meaning_and_backup(self):
        self.roster(700)
        modes.set_hunters_max(self.profile, 650)
        self.assertEqual(modes.hunters_max(self.profile)['configured'], 650)
        self.assertTrue(list((self.root/'backups').rglob('PhantomHunters.ini')))
        modes.set_hunters_max(self.profile, 700)
        a = modes.hunters_max(self.profile)
        self.assertEqual((a['configured'], a['count']), (0, 700))


if __name__ == '__main__': unittest.main()
