"""Rebuilds must not replace local balance, population or world-owned data."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from l2hub import builder


class NativeDataPreservation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.src, self.srv = self.root / "source", self.root / "server"
        self.servers, self.backups = self.root / "servers", self.root / "backups"
        self.rel = "game/data/multisell/custom/50039938.xml"

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def deploy(self, data, cid="epilogue", prev=None):
        self.write(self.src / "dist" / self.rel, data)
        with patch.object(builder.paths, "SERVERS", self.servers), patch.object(builder.paths, "BACKUPS", self.backups):
            builder.deploy_data_files(cid, self.src, self.srv, {self.rel}, lambda _: None, prev)

    def test_fresh_and_unchanged_data_receive_new_version_with_backup(self):
        self.deploy(b"old")
        self.deploy(b"new")
        self.assertEqual((self.srv / self.rel).read_bytes(), b"new")
        copies = list(self.backups.rglob("50039938.xml"))
        self.assertEqual([x.read_bytes() for x in copies], [b"old"])

    def test_local_price_survives_changed_build_and_repeated_deployment(self):
        self.deploy(b"stock")
        self.write(self.srv / self.rel, b"local-price")
        self.deploy(b"upstream")
        self.deploy(b"upstream")
        self.assertEqual((self.srv / self.rel).read_bytes(), b"local-price")
        self.assertEqual((self.servers / "epilogue/.data_new" / self.rel).read_bytes(), b"upstream")

    def test_first_deployment_does_not_guess_existing_data_are_unmodified(self):
        self.write(self.srv / self.rel, b"roster-700")
        self.deploy(b"roster-109")
        self.assertEqual((self.srv / self.rel).read_bytes(), b"roster-700")

    def test_explicitly_accepted_new_version_clears_pending(self):
        self.write(self.srv / self.rel, b"custom")
        self.deploy(b"upstream")
        self.write(self.srv / self.rel, b"upstream")
        self.deploy(b"upstream")
        self.assertFalse((self.servers / "epilogue/.data_new" / self.rel).exists())

    def test_first_rebuild_takes_previous_build_archive_as_baseline(self):
        # Индекса ещё нет (хроника пересобирается впервые с этой выкладкой): база — прошлый архив сборки.
        import hashlib
        import zipfile
        archive = self.root / "prev.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr(self.rel, b"stock")
        prev = builder.prev_data_shas(archive, {self.rel, "game/config/General.ini"})
        self.assertEqual(prev, {self.rel: hashlib.sha256(b"stock").hexdigest()})
        self.write(self.srv / self.rel, b"stock")
        self.deploy(b"upstream", "ct26_highfive", prev)
        self.assertEqual((self.srv / self.rel).read_bytes(), b"upstream")
        self.write(self.srv / self.rel, b"local-price")
        self.deploy(b"upstream-2", "ct26_highfive", prev)
        self.assertEqual((self.srv / self.rel).read_bytes(), b"local-price")
        self.assertEqual((self.servers / "ct26_highfive/.data_new" / self.rel).read_bytes(), b"upstream-2")

    def test_live_state_outside_source_touched_set_is_untouched(self):
        state = self.srv / "game/data/L2HubSavedPersonalParties.properties"
        self.write(state, b"saved-party")
        self.deploy(b"shop")
        self.assertEqual(state.read_bytes(), b"saved-party")


if __name__ == "__main__":
    unittest.main()
