"""Мозги: находки аудита 2026-11-09 — тег стратега с одной скобкой, порт мозгов, пробные запросы, ключ на чужой адрес."""
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from l2hub import api, brain, health, servers

OPTIONS = ["HUNT", "BUY", "SPOT_TO", "PARTY", "TOWN", "GOAL"]
NAMED = {"BUY": ["Mithril Gloves", "Brigandine Helmet"], "SPOT_TO": ["Silent Valley · северо-восток (Aden, 75-78)"], "PARTY": ["Masha"]}


class StrategyTagCheck(unittest.TestCase):
    def parse(self, text):
        return brain.parse_strategy(text, "Fake", OPTIONS, [5, 30], NAMED, goal=True)

    def test_half_closed_tags_from_the_journal(self):
        self.assertEqual(self.parse("[[BUY Mithril Gloves] добиваю сет d"), ("BUY", "Mithril Gloves", "", "добиваю сет d"))
        self.assertEqual(self.parse("[[SPOT_TO Silent Valley · северо-восток (Aden, 75-78)] иду"),
                         ("SPOT_TO", "Silent Valley · северо-восток (Aden, 75-78)", "", "иду"))
        self.assertEqual(self.parse("[[PARTY Masha] го"), ("PARTY", "Masha", "", "го"))
        self.assertEqual(self.parse("[[TOWN]] [[GOAL до 40-го] мысль"), ("TOWN", "", "до 40-го", "мысль"))

    def test_regular_tags_unchanged(self):
        self.assertEqual(self.parse("[[BUY Mithril Gloves]] норм"), ("BUY", "Mithril Gloves", "", "норм"))
        self.assertEqual(self.parse("HUNT мысль"), ("HUNT", "", "", "мысль"))
        with self.assertRaises(ValueError):
            self.parse("просто болтовня без тега")


class BrainPortCheck(unittest.TestCase):
    def test_second_service_cannot_share_the_port(self):
        first = brain.ExclusiveHTTPServer(("127.0.0.1", 0), BaseHTTPRequestHandler)
        self.addCleanup(first.server_close)
        port = first.server_address[1]
        with self.assertRaises(OSError):
            brain.ExclusiveHTTPServer(("127.0.0.1", port), BaseHTTPRequestHandler).server_close()
        with self.assertRaises(OSError):
            ThreadingHTTPServer(("127.0.0.1", port), BaseHTTPRequestHandler).server_close()

    def test_brain_command_line(self):
        self.assertEqual(servers.brain_cmd_id(["python.exe", "-m", "l2hub.brain", "ct26_highfive"]), "ct26_highfive")
        self.assertEqual(servers.brain_cmd_id(["C:/x/" + servers.paths.CLI_EXE.name, "brain", "interlude"]), "interlude")
        self.assertIsNone(servers.brain_cmd_id(["java.exe", "-jar", "GameServer.jar"]))

    def test_foreign_brain_is_not_ours(self):
        class Answer:
            def __init__(self, body):
                self.body = body

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return self.body

        p = {"id": "ct26_highfive"}
        with patch.object(servers, "brain_port", return_value=8771), patch.object(servers, "part_process", return_value=None), \
                patch.object(health, "_proc_info", return_value={}), \
                patch.object(health.urllib.request, "urlopen", return_value=Answer(b'{"ok": true, "id": "interlude", "mode": "api"}')):
            self.assertEqual(health.brain_state(p)["state"], "off")


class ProbeCheck(unittest.TestCase):
    def test_probe_requests_do_not_count_as_live(self):
        b = brain.Brain.__new__(brain.Brain)
        b.echo = False
        self.assertTrue(b._probe({"test": "cli"}))
        self.assertFalse(b._probe({"kind": "phantom"}))
        b.echo = True
        self.assertTrue(b._probe({"kind": "phantom"}))


class ModelsKeyCheck(unittest.TestCase):
    def call(self, url):
        seen = {}

        def models(base_url, key=""):
            seen["key"] = key
            return ["m"]

        settings = {"api": {"baseUrl": "https://api.deepseek.com", "keyFile": "deepseek.key"}}
        with patch.object(api.catalog, "profile", return_value={"brain": {}}), patch.object(brain, "load_settings", return_value=settings), \
                patch.object(brain, "read_key", return_value="SECRET"), patch.object(brain, "list_models", side_effect=models):
            api.Api.brain_models(api.Api.__new__(api.Api), "ct26_highfive", "api", url)
        return seen["key"]

    def test_key_only_to_saved_address(self):
        self.assertEqual(self.call("https://api.deepseek.com/"), "SECRET")
        self.assertEqual(self.call("https://api.minimax.io/v1"), "")


if __name__ == "__main__":
    unittest.main()
