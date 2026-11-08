"""Перекрытия хроники не считаются чужой правкой при повторной сборке."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from l2hub import builder, modes


class ModuleOverlayCheck(unittest.TestCase):
    def test_x10_setting_is_high_five_only(self):
        for mode in modes.table()["modes"]:
            if mode["id"] not in ("hardcore", "x10", "pvpcraft", "pvp"):
                continue
            h5 = modes._settings({"id": "ct26_highfive"}, mode)
            epilogue = modes._settings({"id": "epilogue"}, mode)
            for flag in ("EventsEnabled", "ContractsEnabled", "CoinDropEnabled", "FarmSpotsEnabled"):
                self.assertEqual(h5["L2HubX10.ini"][flag], "True" if mode["id"] == "x10" else "False")
            self.assertNotIn("L2HubX10.ini", epilogue)

    def test_chronicle_override_and_real_local_edit(self):
        # Мелкая проверочная среда остаётся рядом с остальными локальными проверками.
        checks = Path(__file__).resolve().parents[2] / "local/checks"
        checks.mkdir(parents=True, exist_ok=True)
        fixture = Path(tempfile.mkdtemp(prefix="module-overlay-", dir=checks))
        modules, source, state = fixture / "modules", fixture / "source", fixture / "state"
        for directory in (modules / "core/java", modules / "ct26_highfive/java", source / "java", state):
            directory.mkdir(parents=True, exist_ok=True)
        (modules / "core/java/Bridge.java").write_bytes(b"common fallback\n")
        (modules / "ct26_highfive/java/Bridge.java").write_bytes(b"H5 implementation\n")
        (modules / "core/java/Shared.java").write_bytes(b"shared\n")
        (source / "java/Bridge.java").write_bytes(b"H5 implementation\n")
        digest = hashlib.sha256(b"H5 implementation\n").hexdigest().upper()
        (state / ".module_manifest.json").write_text(json.dumps({"java/Bridge.java": digest}), encoding="utf-8")
        messages = []
        with patch.object(builder.paths, "PHANTOMS", modules):
            keys = builder.copy_module("ct26_highfive", source, state, messages.append)
            self.assertEqual((source / "java/Bridge.java").read_bytes(), b"H5 implementation\n")
            self.assertEqual((source / "java/Shared.java").read_bytes(), b"shared\n")
            self.assertEqual(keys.count("java/Bridge.java"), 1)
            self.assertFalse((state / ".module_overwritten").exists())
            (source / "java/Bridge.java").write_bytes(b"real local edit\n")
            builder.copy_module("ct26_highfive", source, state, messages.append)
            backups = list((state / ".module_overwritten").rglob("Bridge.java"))
            self.assertEqual(len(backups), 1)
            self.assertEqual(backups[0].read_bytes(), b"real local edit\n")
            self.assertEqual((source / "java/Bridge.java").read_bytes(), b"H5 implementation\n")


if __name__ == "__main__":
    unittest.main()
