"""Отзывы пользователей на 1.0.9 (2026-11-27).

1. Перенос папки данных на другой диск: data\\mariadb\\my.ini хранил абсолютные datadir и plugin-dir, и MariaDB после
   переноса читала бы базу по старому пути. servers.fix_my_ini правит их перед стартом базы.
2. «GM входит невидимым» не действовал: штатный GMStartupBuilderHide = True обрывает остальные GMStartup-настройки,
   а в окне его не было. Теперь он в «Права GM».
Всё — на временных папках, без сервера."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from l2hub import paths, servers

OLD = ("[mysqld]\r\nbind-address=127.0.0.1\r\ndatadir=D:/L2Hub/data/mariadb\r\nport=3306\r\n"
       "[client]\r\nport=3306\r\nplugin-dir=D:\\L2Hub\\runtime\\mariadb/lib/plugin\r\n")


class MyIni(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "E_L2Hub"
        self.db = self.home / "data" / "mariadb"
        self.db.mkdir(parents=True)
        self.ini = self.db / "my.ini"
        self.ini.write_bytes(OLD.encode())
        self.patches = [patch.object(paths, "DB_DIR", self.db), patch.object(paths, "RUNTIME", self.home / "runtime")]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in self.patches:
            p.stop()
        self.tmp.cleanup()

    def test_moved_folder_is_fixed_once(self):
        fixed = servers.fix_my_ini()
        self.assertEqual(len(fixed), 2)
        text = self.ini.read_bytes().decode()
        self.assertIn(f"datadir={self.db.as_posix()}\r\n", text)
        self.assertIn(f"plugin-dir={self.home / 'runtime' / 'mariadb'}/lib/plugin\r\n", text)
        self.assertIn("bind-address=127.0.0.1\r\n", text)  # остальное и CRLF — как было
        self.assertEqual(text.count("\r\n"), OLD.count("\r\n"))
        self.assertEqual((self.db / "my.ini.before_move").read_bytes(), OLD.encode())
        before = self.ini.read_bytes()
        self.assertEqual(servers.fix_my_ini(), [])  # на месте — файл не трогаем
        self.assertEqual(self.ini.read_bytes(), before)

    def test_same_path_other_slashes_untouched(self):
        self.ini.write_bytes(f"[mysqld]\ndatadir={self.db}\n".encode())
        self.assertEqual(servers.fix_my_ini(), [])
        self.assertFalse((self.db / "my.ini.before_move").exists())


class BuilderHideToggle(unittest.TestCase):
    def test_in_gm_rights(self):
        modes = json.loads((paths.PACKAGE / "server_modes.json").read_text(encoding="utf-8"))
        entry = next(p for p in modes["pages"] if p["id"] == "entry")
        gm = next(s for s in entry["subs"] if s.get("title") == "Права GM")
        keys = [k[0] for k in gm["keys"]]
        self.assertLess(keys.index("GMStartupBuilderHide"), keys.index("GMStartupInvisible"))
        self.assertIn("//hide", gm["note"])


if __name__ == "__main__":
    unittest.main()
