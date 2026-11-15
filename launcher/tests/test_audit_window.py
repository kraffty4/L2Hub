"""Окно, отчёт, очередь задач: находки аудита 2026-11-09 — db_query, сброс пешек мимо очереди, scrub, стоп не той хроники, база предпросмотра вайпа."""
import json
import os
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from l2hub import api, report, servers, wipe


def bare_api():
    a = api.Api.__new__(api.Api)
    a._lock = threading.Lock()
    a._job = None
    a._stop_requested_at = 0.0
    return a


def wait(a):
    for _ in range(100):
        if a._job and a._job.done:
            return a._job
        time.sleep(0.02)
    raise AssertionError("задача не закончилась")


class DbQueryCheck(unittest.TestCase):
    def query(self, out: bytes):
        done = SimpleNamespace(returncode=0, stdout=out, stderr=b"")
        with patch.object(servers, "_db_client", return_value=["mariadb.exe"]), patch.object(servers.subprocess, "run", return_value=done):
            return servers.db_query({"database": "l2"}, "SELECT 1")

    def test_odd_characters_do_not_split_rows(self):
        out = "acc\x1cb\tName\t40\t1\t0\t1.2.3.4\r\nplain\tOther\t20\t0\t0\t\r\n".encode("utf-8")
        rows = self.query(out)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0][0], "acc\x1cb")
        self.assertEqual(rows[1], ["plain", "Other", "20", "0", "0", ""])

    def test_lone_cr_and_nel_inside_value(self):
        rows = self.query("a\rb\x85c\tX\n".encode("utf-8"))
        self.assertEqual(rows, [["a\rb\x85c", "X"]])

    def test_broken_row_is_dropped(self):
        rows = self.query(b"one\ttwo\tthree\nbroken\ntail\tx\ty\n")
        self.assertEqual(rows, [["one", "two", "three"], ["tail", "x", "y"]])


class ScrubCheck(unittest.TestCase):
    def test_user_in_json_and_forward_slashes(self):
        with patch.dict(os.environ, {"USERNAME": "ivan", "COMPUTERNAME": "BOX"}):
            text = report.scrub(json.dumps({"app": r"C:\Users\ivan\AppData\Local", "home": "C:/Users/ivan/L2Hub", "dbPassword": "qwerty",
                                            "other": r"C:\Users\ivanov\x"}))
        self.assertNotIn("\\\\ivan\\\\", text)
        self.assertNotIn("/ivan/", text)
        self.assertNotIn("qwerty", text)
        self.assertIn("ivanov", text)


class JobQueueCheck(unittest.TestCase):
    def test_stop_and_restart_of_another_chronicle_refused(self):
        a = bare_api()
        with patch.object(api.Api, "running", return_value="ct26_highfive"):
            for action in ("stop", "restart"):
                r = a._start_job("interlude", action, [("шаг", lambda: None)])
                self.assertFalse(r["ok"])
                self.assertIn("ct26_highfive", r["error"])
            self.assertIsNone(a._job)
            self.assertTrue(a._start_job("ct26_highfive", "stop", [("шаг", lambda: None)])["ok"])
            wait(a)

    def test_reset_hunters_is_a_job_and_blocks_start(self):
        a = bare_api()
        gate = threading.Event()
        calls = []

        def slow_backup(p, label):
            calls.append(label)
            gate.wait(2)

        ini = Path(self.id().replace(".", "_") + ".ini")
        with patch.object(api.catalog, "profile", return_value={"id": "ct26_highfive"}), patch.object(api.catalog, "server_dir", return_value=Path(".")), \
                patch.object(api.Path, "is_file", return_value=True), patch.object(api, "_ini_value", return_value="4"), \
                patch.object(api.backups, "make", side_effect=slow_backup), patch.object(api.Api, "running", return_value=None), \
                patch.object(api.Api, "save_settings", return_value={"ok": True}) as save:
            self.assertTrue(a.reset_hunters("ct26_highfive", ["ALL"])["ok"])
            busy = a._start_job("ct26_highfive", "start", [("шаг", lambda: None)])
            self.assertFalse(busy["ok"])
            gate.set()
            job = wait(a)
            self.assertIsNone(job.error)
            self.assertEqual(calls, ["before-reset"])
            edits = save.call_args[0][1]
            self.assertEqual([e["value"] for e in edits], ["ALL", "5"])
        self.assertFalse(ini.exists())

    def test_failed_backup_cancels_reset(self):
        a = bare_api()
        with patch.object(api.catalog, "profile", return_value={"id": "ct26_highfive"}), patch.object(api.catalog, "server_dir", return_value=Path(".")), \
                patch.object(api.Path, "is_file", return_value=True), patch.object(api.backups, "make", side_effect=RuntimeError("нет места")), \
                patch.object(api.Api, "running", return_value=None), patch.object(api.Api, "save_settings") as save:
            self.assertTrue(a.reset_hunters("ct26_highfive", ["Vesna"])["ok"])
            job = wait(a)
        self.assertIn("сброс отменён", job.error)
        save.assert_not_called()

    def test_wipe_preview_waits_for_job(self):
        a = bare_api()
        a._job = SimpleNamespace(done=False)
        with patch.object(api.wipe, "preview") as preview:
            self.assertFalse(a.wipe_preview("ct26_highfive")["ok"])
        preview.assert_not_called()


class WipePreviewSideCheck(unittest.TestCase):
    def test_preview_builds_its_own_side_database(self):
        sql = []
        with patch.object(wipe, "_sql", side_effect=lambda q, db=None, timeout=600: sql.append(q) or [["utf8mb4", "utf8mb4_general_ci"]]), \
                patch.object(wipe, "_tables", return_value=[]):
            new = wipe._build_new({"database": "l2h5"}, [], wipe.PREVIEW)
        self.assertEqual(new, "l2h5__wipe_preview")
        self.assertTrue(any("DROP DATABASE IF EXISTS `l2h5__wipe_preview`" in q for q in sql))
        self.assertFalse(any("__wipe_new" in q for q in sql))


if __name__ == "__main__":
    unittest.main()
