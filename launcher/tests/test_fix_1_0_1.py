"""Отчёты пользователя на 1.0.0 (2026-11-16, 2026-11-17).

1. Программа и данные в одной папке: шаблоны профилей из папки программы лаунчер принимал за установленные хроники.
2. «Проверить всё» у работающего сервера с сотнями пешек: «Гейм зарегистрирован на логине — в логе нет регистрации»,
   потому что строка старта уехала из хвоста лога.
Всё — на временных папках, без сервера и сети."""
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from l2hub import health, paths

START = (
    "2026.11.17 17:03:50,100\tINFO\t1\torg.l2jmobius.gameserver.geoengine.GeoEngine\tGeoEngine: Loaded 203 regions.\n"
    "2026.11.17 17:04:22,900\tINFO\t1\torg.l2jmobius.gameserver.GameServer\tGameServer: Started, using 4256 of 8192 MB total memory.\n"
    "2026.11.17 17:04:22,950\tINFO\t1\torg.l2jmobius.gameserver.GameServer\tServer loaded in 44 seconds.\n"
    "2026.11.17 17:04:23,410\tINFO\t57\torg.l2jmobius.gameserver.LoginServerThread\tRegistered on login as Server 2: Sieghardt\n"
)
NOISE = "2026.11.17 17:05:18,662\tINFO\t93\torg.l2jmobius.gameserver.managers.phantom.PhantomSpoil\tPhantomSpoil: Menyala spoils Salamander Rowin.\n"


class FakeProc:
    def __init__(self, born: float):
        self.born = born

    def create_time(self) -> float:
        return self.born


class FrozenHome(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = Path(self.tmp.name) / "L2Hub"
        (self.app / "profiles").mkdir(parents=True)  # шаблоны профилей лежат в папке программы

    def tearDown(self):
        self.tmp.cleanup()

    def test_same_folder_goes_to_subfolder(self):
        self.assertEqual(paths.frozen_home(self.app, self.app), self.app / "home")
        # Тот же путь другим регистром и с хвостовым слэшем — та же папка.
        twin = Path(str(self.app).swapcase() + os.sep)
        self.assertEqual(paths.frozen_home(twin, self.app), self.app / "home")

    def test_no_settings_goes_to_subfolder(self):
        self.assertEqual(paths.frozen_home(None, self.app), self.app / "home")

    def test_separate_folder_is_kept(self):
        home = Path(self.tmp.name) / "data_home"
        self.assertEqual(paths.frozen_home(home, self.app), home)
        # Подпапка программы — не сама программа.
        self.assertEqual(paths.frozen_home(self.app / "home", self.app), self.app / "home")

    def test_real_data_in_program_folder_is_not_hidden(self):
        (self.app / "data" / "mariadb").mkdir(parents=True)
        self.assertEqual(paths.frozen_home(self.app, self.app), self.app)

    def test_logs_and_install_id_are_not_real_data(self):
        # У пользователя из отчёта в папке программы были только data/logs и data/feedback.json.
        (self.app / "data" / "logs").mkdir(parents=True)
        (self.app / "data" / "feedback.json").write_text("{}", encoding="utf-8")
        self.assertEqual(paths.frozen_home(self.app, self.app), self.app / "home")


class StartFacts(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.log = self.dir / "java0.log"
        health._start_cache.clear()
        self.p = {"id": "test_h5"}

    def tearDown(self):
        self.tmp.cleanup()

    def facts(self, proc):
        with patch.object(health, "log_path", lambda p, part, kind="java": self.log), \
                patch.object(health.servers, "part_process", lambda p, part: proc):
            return health.game_facts(self.p)

    def test_registration_scrolled_out_of_tail(self):
        # Старт — в начале файла, дальше мегабайт болтовни пешек: в хвосте в 200 КБ строки регистрации нет.
        self.log.write_bytes((START + NOISE * 8000).encode("utf-8"))
        self.assertGreater(self.log.stat().st_size, 1_000_000)
        self.assertNotIn("Registered on login", health._tail(self.log))
        facts = self.facts(FakeProc(time.time() - 600))
        self.assertEqual(facts["registered"], "Server 2: Sieghardt")
        self.assertEqual(facts["geo_regions"], 203)
        self.assertEqual(facts["loaded_s"], 44)
        self.assertEqual(facts["heap"], "4256 / 8192 МБ")

    def test_start_in_rotated_file_of_this_run(self):
        # Лог перевернулся за этот запуск: старт остался в java1.log.
        born = time.time() - 3600
        (self.dir / "java1.log").write_bytes((START + NOISE * 100).encode("utf-8"))
        self.log.write_bytes((NOISE * 4000).encode("utf-8"))
        self.assertEqual(self.facts(FakeProc(born))["registered"], "Server 2: Sieghardt")

    def test_rotated_file_of_previous_run_is_ignored(self):
        # java1.log — от прошлого запуска (старше процесса): его регистрация не считается нынешней.
        old = self.dir / "java1.log"
        old.write_bytes(START.encode("utf-8"))
        past = time.time() - 7200
        os.utime(old, (past, past))
        self.log.write_bytes((NOISE * 4000).encode("utf-8"))
        self.assertNotIn("registered", self.facts(FakeProc(time.time() - 600)))

    def test_not_registered_stays_not_registered(self):
        self.log.write_bytes((NOISE * 4000).encode("utf-8"))
        self.assertNotIn("registered", self.facts(FakeProc(time.time() - 600)))

    def test_old_l2j_rotation_names(self):
        log = self.dir / "java0.log.0"
        log.write_bytes(b"x")
        (self.dir / "java0.log.1").write_bytes(b"x")
        (self.dir / "java0.log.2").write_bytes(b"x")
        self.assertEqual([f.name for f in health._rotations(log)], ["java0.log.0", "java0.log.1", "java0.log.2"])
        self.assertEqual([f.name for f in health._rotations(self.dir / "java0.log")], ["java0.log"])


if __name__ == "__main__":
    unittest.main()
