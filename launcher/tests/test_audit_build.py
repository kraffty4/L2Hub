"""Сборка и установка: находки аудита 2026-11-09 — недолитая база, распаковка прямо в итоговую папку, снятый патч и файл
модуля, упавший npm install у L2Solo, пароль root в командной строке. Без сети, без MariaDB и без общих портов."""
import hashlib
import json
import re
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from l2hub import builder, l2solo, paths

DONE = lambda code=0, out=b"", err=b"": SimpleNamespace(returncode=code, stdout=out, stderr=err)  # noqa: E731


class FakeMaria:
    """Сколько нужно MariaDB для fill_database / server_setup: базы и имена таблиц; SQL-файл с broken в имени падает."""

    def __init__(self):
        self.dbs: dict[str, set[str]] = {}
        self.sourced: list[str] = []

    def __call__(self, *args):
        sql = args[-1]
        if sql.startswith("source "):
            assert args[0] == "--abort-source-on-error", args  # иначе ошибка в файле даёт код 0
            args = args[1:]
        if m := re.search(r"SCHEMA_NAME = '([^']+)'", sql):
            return DONE(out=(m[1] + "\n").encode() if m[1] in self.dbs else b"")
        if m := re.search(r"TABLE_SCHEMA = '([^']+)'", sql):
            return DONE(out="".join(t + "\n" for t in sorted(self.dbs.get(m[1], ()))).encode())
        if m := re.match(r"CREATE DATABASE `([^`]+)`", sql):
            if m[1] in self.dbs:
                return DONE(1, err=b"database exists")
            self.dbs[m[1]] = set()
            for t in re.findall(r"CREATE TABLE `[^`]+`\.`([^`]+)`", sql):
                self.dbs[m[1]].add(t)
            return DONE()
        if m := re.match(r"DROP TABLE `([^`]+)`\.`([^`]+)`", sql):
            self.dbs[m[1]].discard(m[2])
            return DONE()
        if m := re.match(r"DROP DATABASE `([^`]+)`", sql):
            self.dbs.pop(m[1], None)
            return DONE()
        if sql.startswith("source "):
            f = Path(sql[len("source "):])
            self.sourced.append(f.name)
            if "broken" in f.name:
                return DONE(1, err=b"ERROR 1064")
            self.dbs[args[0]].add(f.stem)
            return DONE()
        raise AssertionError(f"неожиданный запрос: {args}")


def write(path: Path, text: str = "x") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


class TempHome(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="l2hub_audit_build_"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        for name, rel in (("SERVERS", "servers"), ("BACKUPS", "data/backups"), ("PHANTOMS", "phantoms"), ("DATA", "data"),
                          ("DB_DIR", "data/mariadb"), ("CLIENT_CNF", "data/client.cnf"), ("MARIA_BIN", "runtime/mariadb/bin")):
            p = patch.object(paths, name, self.tmp / rel)
            p.start()
            self.addCleanup(p.stop)


class DatabaseFillCheck(TempHome):
    def make_server(self, sql: list[str]) -> Path:
        srv = self.tmp / "servers" / "h5" / "server"
        for part in ("login", "game"):
            write(srv / part / "config" / "Database.ini", "URL = jdbc:mysql://localhost/l2h5?useSSL=false\nPassword = \n")
            write(srv / part / "config" / "Server.ini", "LoginserverHostname = *\nGameserverHostname = *\n")
            write(srv / part / "config" / "Interface.ini", "EnableGUI = True\n")
        for name in sql:
            write(srv / "db_installer" / "sql" / "game" / name, "-- sql")
        return srv

    def setup_db(self, maria):
        with patch.object(builder, "_mysql", maria), patch.object(builder.servers, "port_open", return_value=True), \
                patch.object(builder, "db_password", return_value="pw"), patch.object(builder.machine, "fresh_default"):
            builder.server_setup({"id": "h5", "type": "mobius"}, out=lambda _l: None)

    def test_broken_fill_is_refilled_on_retry(self):
        maria = FakeMaria()
        srv = self.make_server(["01_accounts.sql", "02_broken.sql", "03_items.sql"])
        with self.assertRaises(RuntimeError):
            self.setup_db(maria)
        self.assertIn(builder.FILLING, maria.dbs["l2h5"])
        with patch.object(builder, "_mysql", maria):
            self.assertEqual(builder._db_state("l2h5"), "partial")
        (srv / "db_installer" / "sql" / "game" / "02_broken.sql").rename(srv / "db_installer" / "sql" / "game" / "02_fixed.sql")
        maria.sourced.clear()
        self.setup_db(maria)
        self.assertEqual(maria.dbs["l2h5"], {"01_accounts", "02_fixed", "03_items"})
        self.assertEqual(maria.sourced, ["01_accounts.sql", "02_fixed.sql", "03_items.sql"])

    def test_existing_world_is_not_touched(self):
        maria = FakeMaria()
        maria.dbs["l2h5"] = {"characters", "items"}
        self.make_server(["01_accounts.sql"])
        self.setup_db(maria)
        self.assertEqual(maria.dbs["l2h5"], {"characters", "items"})
        self.assertEqual(maria.sourced, [])

    def test_empty_database_counts_as_partial(self):
        maria = FakeMaria()
        maria.dbs["l2h5"] = set()
        with patch.object(builder, "_mysql", maria):
            self.assertEqual(builder._db_state("l2h5"), "partial")
            self.assertEqual(builder._db_state("nope"), "none")


class WipeSourceAbortCheck(unittest.TestCase):
    def test_wipe_client_aborts_source_on_error(self):
        from l2hub import wipe
        with patch.object(wipe.subprocess, "run", return_value=DONE()) as run:
            wipe._sql("source game/01.sql;\n", "l2h5__wipe_new")
        self.assertIn("--abort-source-on-error", run.call_args[0][0])


class RootPasswordCheck(TempHome):
    def fake_run(self, alter_code=0):
        calls = []

        def run(argv, **kw):
            calls.append((argv, kw.get("input")))
            exe = Path(argv[0]).name
            if exe == "mariadb-install-db.exe":
                write(paths.DB_DIR / "my.ini", "[mysqld]\ndatadir=x\nport=3306\n")
                return DONE()
            if "-e" in argv:
                return DONE(out=b"localhost\n127.0.0.1\n::1\nbox\n")
            return DONE(alter_code)
        return run, calls

    def test_password_never_on_command_line(self):
        run, calls = self.fake_run()
        popen = MagicMock()
        popen.poll.return_value = 0
        with patch.object(builder.subprocess, "run", side_effect=run), patch.object(builder.subprocess, "Popen", return_value=popen) as po:
            builder.db_init(out=lambda _l: None)
        password = builder.db_password()
        self.assertEqual(len(password), 24)
        argvs = [a for a, _ in calls] + [po.call_args[0][0]]
        self.assertFalse(any(password in str(x) for argv in argvs for x in argv))
        self.assertTrue(any("--skip-networking" in x for x in po.call_args[0][0]))
        sql = calls[-1][1].decode()
        for host in ("localhost", "127.0.0.1", "::1", "box"):
            self.assertIn(f"ALTER USER 'root'@'{host}' IDENTIFIED BY '{password}';", sql)
        self.assertTrue(sql.rstrip().endswith("SHUTDOWN;"))
        self.assertIn("bind-address=127.0.0.1", (paths.DB_DIR / "my.ini").read_text(encoding="utf-8"))

    def test_failed_password_leaves_nothing(self):
        run, _calls = self.fake_run(alter_code=1)
        popen = MagicMock()
        popen.poll.return_value = 0
        with patch.object(builder.subprocess, "run", side_effect=run), patch.object(builder.subprocess, "Popen", return_value=popen):
            with self.assertRaises(RuntimeError) as e:
                builder.db_init(out=lambda _l: None)
        self.assertNotIn("IDENTIFIED", str(e.exception))
        self.assertFalse(paths.DB_DIR.exists())
        self.assertFalse(paths.CLIENT_CNF.exists())


class StagedUnpackCheck(TempHome):
    BUILD = "L2J_Mobius_X"

    def archive(self) -> Path:
        z = self.tmp / "src.zip"
        with zipfile.ZipFile(z, "w") as f:
            for rel in ("build.xml", "java/A.java", "java/B.java", "dist/game/data/x.xml"):
                f.writestr(f"L2J_Mobius-abc/{self.BUILD}/{rel}", rel)
            f.comment = b"abc"
        return z

    def test_interrupted_fetch_is_not_taken_for_sources(self):
        pin = {"build": self.BUILD, "commit": "abc", "date": "2026-01-01"}
        real_read, n = zipfile.ZipFile.read, {"calls": 0}

        def flaky(self_, name, *a):
            n["calls"] += 1
            if n["calls"] == 3:
                raise OSError("диск кончился")
            return real_read(self_, name, *a)

        with patch.object(builder, "_source_zip", return_value=self.archive()):
            with patch.object(zipfile.ZipFile, "read", flaky), self.assertRaises(OSError):
                builder.fetch_sources("x", out=lambda _l: None, pin=pin)
            dst = paths.SERVERS / "x" / self.BUILD
            self.assertFalse(dst.exists())
            with self.assertRaises(RuntimeError):
                builder.source_dir("x")  # остаток в .unpack/<сборка> — не исходники
            builder.fetch_sources("x", out=lambda _l: None, pin=pin)
        self.assertEqual(sorted(f.relative_to(dst).as_posix() for f in dst.rglob("*") if f.is_file()),
                         ["build.xml", "dist/game/data/x.xml", "java/A.java", "java/B.java"])
        self.assertFalse((paths.SERVERS / "x" / ".unpack").exists())
        self.assertEqual(builder.source_dir("x"), dst)

    def test_interrupted_bundle_copy_retries(self):
        src = self.tmp / "bundle" / "server"
        write(src / "gameserver" / "a.txt")
        write(src / "login" / "b.txt")
        kind = {"title": "X — y", "rename": {"gameserver": "game"}, "local": [], "read": {}, "db": {"port": 3306},
                "ports": {"login": 2106, "game": 7777}}
        real, top = shutil.copytree, {"done": False}

        def broken(s, d, *a, **kw):
            if top["done"]:
                return real(s, d, *a, **kw)  # рекурсия copytree по подпапкам
            top["done"] = True
            real(s, d, ignore=lambda _d, names: [n for n in names if n == "login"], dirs_exist_ok=True)
            raise OSError("файл занят")

        with patch.object(builder, "detect_bundle", return_value=("k", src)), patch.object(builder.servers, "bundle_kinds", return_value={"k": kind}):
            with patch.object(builder.shutil, "copytree", broken), self.assertRaises(OSError):
                builder.import_bundle("own", src, out=lambda _l: None)
            dst = paths.SERVERS / "own" / "server"
            self.assertFalse(dst.exists())
            builder.import_bundle("own", src, out=lambda _l: None)
        self.assertTrue((dst / "game" / "a.txt").is_file())
        self.assertTrue((dst / "login" / "b.txt").is_file())


class DroppedPatchCheck(TempHome):
    def test_removed_and_changed_patches_are_stale(self):
        base = paths.SERVERS / "h5"
        a, b = write(self.tmp / "p" / "0001-a.patch", "a"), write(self.tmp / "p" / "0002-b.patch", "b2")
        write(base / builder.PATCH_MANIFEST, json.dumps({"0001-a.patch": builder._sha(a), "0002-b.patch": "00", "0003-gone.patch": "11"}))
        self.assertEqual(builder.stale_patches(base, [a, b]), ["0002-b.patch", "0003-gone.patch"])
        self.assertEqual(builder.stale_patches(self.tmp / "none", [a]), [])

    def test_gone_module_files(self):
        base = paths.SERVERS / "h5"
        write(paths.PHANTOMS / "core" / "java" / "Keep.java")
        write(paths.PHANTOMS / "h5" / "dist" / "game" / "data" / "keep.xml")
        write(base / ".module_manifest.json", json.dumps({"java/Keep.java": "1", "dist/game/data/keep.xml": "2", "java/Gone.java": "3"}))
        self.assertEqual(builder.gone_module_files("h5", base), ["java/Gone.java"])

    def test_stale_build_resets_before_copying_module(self):
        calls = []
        with patch.object(builder, "source_dir", return_value=self.tmp / "src"), patch.object(builder, "chronicle_patches", return_value=[]), \
                patch.object(builder, "stale_patches", return_value=["0407-gm-shop-no-wards.patch"]), \
                patch.object(builder, "gone_module_files", return_value=[]), \
                patch.object(builder, "reset_sources", side_effect=lambda *a, **k: calls.append("reset")), \
                patch.object(builder, "copy_module", side_effect=lambda *a, fresh=False, **k: calls.append(f"module fresh={fresh}") or []), \
                patch.object(builder, "apply_stack"), patch.object(builder, "ant", side_effect=RuntimeError("стоп")):
            with self.assertRaises(RuntimeError):
                builder.server_build({"id": "h5"}, out=lambda _l: None)
        self.assertEqual(calls, ["reset", "module fresh=True"])

    def test_dropped_data_files(self):
        cid, srv = "h5", paths.SERVERS / "h5" / "server"
        sha = lambda b: hashlib.sha256(b).hexdigest()  # noqa: E731
        files = {"game/data/html/merchant/50010-6.htm": b"patched", "game/data/multisell/custom/50039991.xml": b"ours",
                 "game/data/edited.xml": b"user", "game/data/kept.xml": b"kept"}
        for rel, data in files.items():
            (srv / rel).parent.mkdir(parents=True, exist_ok=True)
            (srv / rel).write_bytes(data)
        index = {"game/data/html/merchant/50010-6.htm": sha(b"patched"), "game/data/multisell/custom/50039991.xml": sha(b"ours"),
                 "game/data/edited.xml": sha(b"before"), "game/data/kept.xml": sha(b"kept")}
        write(paths.SERVERS / cid / ".data_base" / "index.json", json.dumps(index))
        archive = self.tmp / "build.zip"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("game/data/html/merchant/50010-6.htm", b"vanilla")
        lines = []
        builder.restore_dropped_data(cid, archive, srv, {"game/data/kept.xml"}, lines.append)
        self.assertEqual((srv / "game/data/html/merchant/50010-6.htm").read_bytes(), b"vanilla")
        self.assertFalse((srv / "game/data/multisell/custom/50039991.xml").exists())
        self.assertEqual((srv / "game/data/edited.xml").read_bytes(), b"user")
        self.assertEqual((srv / "game/data/kept.xml").read_bytes(), b"kept")
        saved = sorted(f.relative_to(f.parents[4]).as_posix() for f in paths.BACKUPS.rglob("*") if f.is_file())
        self.assertEqual(saved, ["game/data/html/merchant/50010-6.htm", "game/data/multisell/custom/50039991.xml"])
        self.assertEqual(list(json.loads((paths.SERVERS / cid / ".data_base" / "index.json").read_text(encoding="utf-8"))), ["game/data/kept.xml"])
        self.assertTrue(any("правили" in x for x in lines))


class L2SoloNpmCheck(TempHome):
    def test_failed_npm_is_retried(self):
        server = self.tmp / "l2solo"
        (server / "node_modules").mkdir(parents=True)
        p = {"id": "c4", "server": str(server)}
        npm = {"fail": True, "runs": 0}

        def run(_p, *args, timeout=None):
            if args[0] == "git":
                return {"rev-parse": "aaaa1111\n", "diff": "package-lock.json\nsrc/x.js\n"}.get(args[1], "")
            npm["runs"] += 1
            if npm["fail"]:
                raise RuntimeError("npm: ETIMEDOUT")
            return ""

        def go(behind):
            info = {"detached": False, "dirty": [], "ahead": 0, "behind": behind}
            with patch.object(l2solo, "LAYER_DIR", self.tmp / "data" / "l2solo"), patch.object(l2solo, "_run", run), \
                    patch.object(l2solo, "_npm", return_value="npm.cmd"), patch.object(l2solo, "server_dir", return_value=server), \
                    patch.object(l2solo, "server_process", return_value=None), patch.object(l2solo, "update_check", return_value=info), \
                    patch.object(l2solo, "db_files", return_value=[self.tmp / "none.db"]):
                for _title, step in l2solo.update_steps(p, save=lambda _p: {"name": "s"}):
                    step()

        with self.assertRaises(RuntimeError):
            go(behind=3)
        with patch.object(l2solo, "LAYER_DIR", self.tmp / "data" / "l2solo"):
            pending = l2solo.npm_pending_path(p)
        self.assertTrue(pending.is_file())
        npm["fail"] = False
        go(behind=0)
        self.assertEqual(npm["runs"], 2)
        self.assertFalse(pending.exists())
        go(behind=0)
        self.assertEqual(npm["runs"], 2)  # дальше без лишнего npm install


if __name__ == "__main__":
    unittest.main()
