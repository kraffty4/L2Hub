"""Автоподготовка DAT без клиентских файлов: синтетические записи по схеме H5."""

import hashlib
import json
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import patch, Mock

from l2hub import clientsys, h5client, h5currency, l2ini, servers


def dat(kind):
    schema = json.loads(Path(h5currency.__file__).with_name('h5_currency_schema.json').read_text())

    def nodes(children, item_id, scope):
        result = b''
        for node in children:
            tag, name = node['tag'], node['name']
            if tag == 'node':
                reader = node['reader']
                value = item_id if name in ('id', 'object_id') else 0
                scope[name] = value
                if reader == 'UNICODE':
                    result += h5currency.unicode('Base' if name == 'name' else 'icon.base')
                elif reader == 'ASCF':
                    result += h5currency.ascf('Base description')
                elif reader in ('BYTE', 'UBYTE'):
                    result += bytes([value])
                else:
                    result += struct.pack('<I', value)
            elif tag == 'wrapper':
                result += nodes(node['children'], item_id, {})
            elif tag == 'for':
                size = node['size']
                count = scope[size[1:]] if size.startswith('#') else int(size)
                for _ in range(count):
                    result += nodes(node['children'], item_id, {})
        return result

    row = schema[kind]['children'][1]
    # Несортированный исходник также должен перестать переписываться после дополнения.
    return struct.pack('<I', 3) + b''.join(nodes(row['children'], i, {}) for i in (6392, 4037, 1835)) + b'\x0cSafePackage\0'


def snapshot(root):
    return {str(p.relative_to(root)): (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns)
            for p in root.rglob('*') if p.is_file()}


class CurrencyFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.system = self.root / 'client/system'
        self.system.mkdir(parents=True)
        (self.system / 'L2.exe').write_bytes(b'fixture')
        (self.system / 'L2.ini').write_bytes(b'ServerAddr=127.0.0.1\r\n')
        plain = b'[LanguageSet]\r\nLanguage=1\r\n[English]\r\nEnglish_Code=e\r\n[Russia]\r\nRussia_Code=ru\r\n'
        (self.system / 'localization.ini').write_bytes(clientsys.ver111_encode(plain, b''))
        for name, kind in (('itemname-e.dat', 'itemname'), ('etcitemgrp.dat', 'etcitemgrp')):
            (self.system / name).write_bytes(l2ini.encode(dat(kind)))
        self.profile = {'id': 'ct26_highfive', 'client': {'path': str(self.system.parent), 'exe': 'system/L2.exe'}}
        self.original = snapshot(self.system)
        p = patch.object(h5client.catalog, 'server_dir', side_effect=AssertionError('Локальный сервер не нужен'))
        p.start()
        self.addCleanup(p.stop)
        p = patch.object(h5client.paths, 'DATA', self.root / 'data')
        p.start()
        self.addCleanup(p.stop)

    def assert_records(self, system, language, itemname='itemname-e.dat'):
        rows, _ = h5currency.parse(l2ini.decode((system / itemname).read_bytes())[1], 'itemname')
        names = {row['id']: row['name'] for row in rows}
        for item_id, _, ru, en, *_ in h5currency.CURRENCIES + h5currency.TOGGLES:
            self.assertEqual(names[item_id], ru if language == 'ru' else en)
        rows, _ = h5currency.parse(l2ini.decode((system / 'etcitemgrp.dat').read_bytes())[1], 'etcitemgrp')
        icons = {row['object_id']: row['icon']['icon1'] for row in rows}
        self.assertEqual(icons[40003], 'icon.skill0340')
        self.assertEqual(icons[40001], icons[4037])
        self.assertEqual(icons[40002], icons[6392])


class ClientCurrencyCheck(CurrencyFixture):
    def test_english_copy_and_repeat_does_not_write(self):
        launch, enabled = h5client.prepare(self.profile)
        self.assertTrue(enabled)
        system = h5client.catalog.client_exe(launch).parent
        self.assertEqual(system.name, 'system_l2hub')
        self.assert_records(system, 'en')
        self.assertEqual(len(list(system.glob('*.bak'))), 2)
        before = snapshot(system)
        h5client.prepare(self.profile)
        self.assertEqual(before, snapshot(system))
        self.assertEqual(self.original, snapshot(self.system))

    def test_missing_russian_copy_is_created_and_reused(self):
        self.profile['client']['exe'] = 'system_ru/L2.exe'
        launch, _ = h5client.prepare(self.profile)
        system = h5client.catalog.client_exe(launch).parent
        self.assert_records(system, 'ru')
        before = snapshot(system)
        h5client.prepare(self.profile)
        self.assertEqual(before, snapshot(system))
        self.assertEqual(self.original, snapshot(self.system))

    def test_existing_system_ru_is_patched_in_place_without_l2hub_copy(self):
        # Профиль H5 автора запускает system_ru: рабочая копия уже есть, вторая (system_l2hub) не нужна.
        clientsys.make_system_ru(self.system.parent, out=lambda message: None)
        self.profile['client']['exe'] = 'system_ru/L2.exe'
        launch, enabled = h5client.prepare(self.profile)
        self.assertTrue(enabled)
        self.assertEqual(h5client.catalog.client_exe(launch).parent.name, 'system_ru')
        self.assertFalse(self.system.with_name('system_l2hub').exists())
        self.assert_records(self.system.with_name('system_ru'), 'ru')
        self.assertEqual(self.original, snapshot(self.system))

    def test_real_russian_text_filename_is_used(self):
        system = self.system.with_name('system_ru')
        clientsys.make_system_ru(self.system.parent, out=lambda message: None)
        loc = system / 'localization.ini'
        plain, footer = clientsys.ver111_decode(loc.read_bytes())
        loc.write_bytes(clientsys.ver111_encode(plain.replace(b'Russia_Code=e', b'Russia_Code=ru'), footer))
        (system / 'itemname-ru.dat').write_bytes((system / 'itemname-e.dat').read_bytes())
        original_e = (system / 'itemname-e.dat').read_bytes()
        self.profile['client']['exe'] = 'system_ru/L2.exe'
        h5client.prepare(self.profile)
        self.assert_records(system, 'ru', 'itemname-ru.dat')
        self.assertEqual(original_e, (system / 'itemname-e.dat').read_bytes())

    def test_h5_variant_without_server_is_prepared(self):
        self.profile.update(id='remote_h5', chronicle='ct26_highfive')
        launch, enabled = h5client.prepare(self.profile)
        self.assertTrue(enabled)
        self.assert_records(h5client.catalog.client_exe(launch).parent, 'en')
        self.assertEqual(self.original, snapshot(self.system))

    def test_other_chronicle_is_untouched(self):
        self.profile['id'] = 'epilogue'
        self.assertEqual(h5client.prepare(self.profile), (self.profile, False))
        self.assertFalse(self.system.with_name('system_l2hub').exists())
        self.assertEqual(self.original, snapshot(self.system))

    def test_missing_russian_copy_failure_launches_original_and_warns(self):
        self.profile['client']['exe'] = 'system_ru/L2.exe'
        # Ошибка копирования, ошибка формата localization и неполная копия без exe.
        for error in (PermissionError('read-only client'), ValueError('unsupported localization'), None):
            with self.subTest(error=error), \
                 patch.object(clientsys, 'make_system_ru', side_effect=error), \
                 patch.object(servers, 'part_process', return_value=None), \
                 patch.object(servers, 'read_pids', return_value={}), \
                 patch.object(servers, '_write_pids'), \
                 patch.object(servers, 'ensure_local_addr') as address, \
                 patch.object(clientsys, 'system_lcid', return_value=0x419), \
                 patch.object(clientsys, 'start_chat_paste'), \
                 patch.object(servers.subprocess, 'Popen', return_value=Mock(pid=45)) as popen:
                self.assertEqual(servers.start_client(self.profile), 45)
                self.assertEqual(Path(popen.call_args.args[0][0]), self.system / 'L2.exe')
                address.assert_not_called()
        self.assertTrue((self.root / 'data/logs/client-x10.log').is_file())
        self.assertEqual(self.original, snapshot(self.system))
        with patch.object(servers, 'read_pids', return_value={'client': 45}), \
             patch.object(servers, 'process', side_effect=[None, None, 'fallback-client']) as process:
            self.assertEqual(servers.part_process(self.profile, 'client'), 'fallback-client')
            self.assertEqual(process.call_args.args, (45, self.system / 'L2.exe'))

    def test_corrupt_dat_warns_and_launches_without_partial_update(self):
        (self.system / 'etcitemgrp.dat').write_bytes(b'unsupported')
        before = (self.system / 'itemname-e.dat').read_bytes()
        with patch.object(servers, 'part_process', return_value=None), \
             patch.object(servers, 'read_pids', return_value={}), \
             patch.object(servers, '_write_pids'), \
             patch.object(servers.clientsys, 'system_lcid', return_value=0x419), \
             patch.object(servers.clientsys, 'start_chat_paste'), \
             patch.object(servers.subprocess, 'Popen', return_value=Mock(pid=42)) as popen:
            self.assertEqual(servers.start_client(self.profile), 42)
        launched = Path(popen.call_args.args[0][0]).parent
        self.assertEqual(before, (launched / 'itemname-e.dat').read_bytes())
        self.assertTrue((self.root / 'data/logs/client-x10.log').is_file())
        self.assertFalse(list(launched.glob('*.bak')))

    def test_start_client_uses_copy_and_process_lookup_can_find_it(self):
        with patch.object(servers, 'part_process', return_value=None), \
             patch.object(servers, 'read_pids', return_value={}), \
             patch.object(servers, '_write_pids'), \
             patch.object(servers.clientsys, 'system_lcid', return_value=0x419), \
             patch.object(servers.clientsys, 'start_chat_paste'), \
             patch.object(servers.subprocess, 'Popen', return_value=Mock(pid=43)) as popen:
            self.assertEqual(servers.start_client(self.profile), 43)
        exe = Path(popen.call_args.args[0][0])
        self.assertEqual(exe.parent.name, 'system_l2hub')
        self.assert_records(exe.parent, 'en')
        with patch.object(servers, 'read_pids', return_value={'client': 43}), \
             patch.object(servers, 'process', side_effect=[None, 'our-process']) as process:
            self.assertEqual(servers.part_process(self.profile, 'client'), 'our-process')
            self.assertEqual(process.call_args.args, (43, exe))
        self.assertEqual(self.original, snapshot(self.system))

    def test_copy_failure_still_launches_original_without_writing_ini(self):
        with patch.object(h5client.shutil, 'copytree', side_effect=PermissionError('read-only client')), \
             patch.object(servers, 'part_process', return_value=None), \
             patch.object(servers, 'read_pids', return_value={}), \
             patch.object(servers, '_write_pids'), \
             patch.object(servers, 'ensure_local_addr') as address, \
             patch.object(servers.clientsys, 'system_lcid', return_value=0x419), \
             patch.object(servers.clientsys, 'start_chat_paste'), \
             patch.object(servers.subprocess, 'Popen', return_value=Mock(pid=44)) as popen:
            self.assertEqual(servers.start_client(self.profile), 44)
        self.assertEqual(Path(popen.call_args.args[0][0]), self.system / 'L2.exe')
        address.assert_not_called()
        self.assertEqual(self.original, snapshot(self.system))

    def test_interrupted_copy_is_not_taken_for_ready_system(self):
        # Первая копия обрывается на середине: полу-копия не становится system_l2hub, следующий запуск копирует заново.
        real = h5client.shutil.copytree

        def broken(src, dst, **kwargs):
            Path(dst).mkdir(parents=True)
            shutil.copy2(Path(src) / 'L2.exe', Path(dst) / 'L2.exe')
            raise OSError('disk full')

        with patch.object(h5client.shutil, 'copytree', side_effect=broken):
            launch, enabled = h5client.prepare(self.profile)
        self.assertTrue(enabled)
        self.assertEqual(h5client.catalog.client_exe(launch), self.system / 'L2.exe')
        self.assertFalse(self.system.with_name('system_l2hub').exists())
        self.assertTrue(self.system.with_name('system_l2hub.partial').exists())
        with patch.object(h5client.shutil, 'copytree', side_effect=real):
            launch, _ = h5client.prepare(self.profile)
        system = h5client.catalog.client_exe(launch).parent
        self.assertEqual(system.name, 'system_l2hub')
        self.assertFalse(self.system.with_name('system_l2hub.partial').exists())
        self.assert_records(system, 'en')
        self.assertEqual(self.original, snapshot(self.system))

    def test_failed_second_replace_restores_both_dat(self):
        replace = Path.replace

        def fail_second(path, target):
            if path.name == 'etcitemgrp.dat':
                raise PermissionError('locked DAT')
            return replace(path, target)

        with patch.object(Path, 'replace', new=fail_second):
            launch, _ = h5client.prepare(self.profile)
        work = h5client.catalog.client_exe(launch).parent
        for name in ('itemname-e.dat', 'etcitemgrp.dat'):
            self.assertEqual((work / name).read_bytes(), (self.system / name).read_bytes())
        self.assertEqual(self.original, snapshot(self.system))
if __name__ == '__main__':
    unittest.main()
