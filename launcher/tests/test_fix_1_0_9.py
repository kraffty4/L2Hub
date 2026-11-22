"""Отзывы пользователей на 1.0.8 (2026-11-22).

1. «Не устанавливается C4», а в отчёте ни строчки о причине: у неустановленной хроники профиля нет, и запись упавшей
   установки (data/logs/install_<id>.json и .log) в zip не попадала.
2. Ответы автора на отзывы — в окне, по номеру установки (feedback.replies).
Всё — на временных папках, без сети."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from l2hub import feedback, paths, report

INSTALL = "0123456789abcdef0123456789abcdef"


class InstallFailureInReport(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = Path(self.tmp.name) / "data"
        logs = self.data / "logs"
        logs.mkdir(parents=True)
        build = Path(self.tmp.name) / "build.log"
        build.write_text("BUILD FAILED\n", encoding="utf-8")
        (logs / "install_c4.json").write_text(json.dumps(
            {"at": "2026-11-22 14:20", "step": "Ставлю зависимости npm…", "error": "npm ERR! network", "log": str(logs / "install_c4.log")},
            ensure_ascii=False), encoding="utf-8")
        (logs / "install_c4.log").write_text("Traceback …\nRuntimeError: npm ERR! network\npassword=secret\n", encoding="utf-8")
        (logs / "install_interlude.json").write_text(json.dumps({"at": "x", "step": "y", "error": "z", "log": str(build)}), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_failed_installs_are_in_zip(self):
        with patch.object(paths, "DATA", self.data), patch.object(paths, "DB_DIR", self.data / "mariadb"), \
                patch("l2hub.catalog.load_profiles", return_value={}):
            out = report.build()
        with zipfile.ZipFile(out) as z:
            names = set(z.namelist())
            self.assertIn("install_failed/c4.json", names)
            self.assertIn("install_failed/c4.install_c4.log", names)
            self.assertIn("install_failed/interlude.build.log", names)
            log = z.read("install_failed/c4.install_c4.log").decode("utf-8")
        self.assertIn("npm ERR! network", log)
        self.assertNotIn("secret", log)


class Replies(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name) / "feedback.json"
        self.state.write_text(json.dumps({"install": INSTALL}), encoding="utf-8")
        feedback._replies.update(t=None, list=[], error=None)
        self.patches = [patch.object(feedback, "STATE", self.state), patch.object(feedback, "url", return_value="https://x/exec")]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in self.patches:
            p.stop()
        feedback._replies.update(t=None, list=[], error=None)
        self.tmp.cleanup()

    def test_new_until_seen(self):
        rows = [{"at": "2026-11-21T10:00:00.000Z", "kind": "Баг", "chronicle": "c4", "text": "не ставится", "reply": "Пришли текст ошибки"},
                {"at": "2026-11-22T10:00:00.000Z", "kind": "Идея", "chronicle": "", "text": "масштаб", "reply": "Сделано в 1.0.6"},
                {"at": "2026-11-22T11:00:00.000Z", "kind": "Идея", "chronicle": "", "text": "без ответа", "reply": "  "}]
        with patch.object(feedback, "_call", return_value={"ok": True, "replies": rows}) as call:
            r = feedback.replies()
            self.assertEqual(call.call_args.kwargs["query"], {"action": "replies", "install": INSTALL})
            self.assertEqual([x["reply"] for x in r["replies"]], ["Сделано в 1.0.6", "Пришли текст ошибки"])  # новые сверху, пустой — мимо
            self.assertEqual(r["unseen"], 2)
            feedback.replies_seen([r["replies"][0]["key"]])
            self.assertEqual(feedback.replies()["unseen"], 1)
            self.assertEqual(call.call_count, 1)  # кэш: второй раз сеть не дёргается
        self.assertEqual(json.loads(self.state.read_text(encoding="utf-8"))["install"], INSTALL)

    def test_old_receiver_is_silent(self):
        with patch.object(feedback, "_call", side_effect=RuntimeError("Приёмник: нет такого действия")):
            r = feedback.replies()
        self.assertEqual((r["replies"], r["unseen"]), ([], 0))
        self.assertNotIn("error", r)

    def test_no_install_no_request(self):
        self.state.write_text("{}", encoding="utf-8")
        with patch.object(feedback, "_call") as call:
            self.assertEqual(feedback.replies()["replies"], [])
        call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
