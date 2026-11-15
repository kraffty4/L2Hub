"""UPnP: находка аудита 2026-11-09 (servers.md 3) — свой проброс на прошлый адрес ПК нельзя было ни обновить, ни снять,
а неудачу шага «Порты на роутере» запуск проглатывал. Только подставной роутер: soap подменён, в сеть ничего не уходит."""
import threading
import unittest
from unittest.mock import patch

from l2hub import api, network, upnp

GW = upnp.Gateway("192.168.2.1", "192.168.2.230", "http://192.168.2.1/ctl", "urn:schemas-upnp-org:service:WANIPConnection:1")


class FakeRouter:
    """Таблица пробросов TCP: внешний порт → {client, port, desc}. Конфликт — как у настоящих IGD (718)."""

    def __init__(self, table: dict):
        self.table = {int(k): dict(v) for k, v in table.items()}
        self.calls = []

    def __call__(self, gw, action, args=None):
        self.calls.append(action)
        port = int((args or {}).get("NewExternalPort", 0))
        if action == "GetSpecificPortMappingEntry":
            m = self.table.get(port)
            if not m:
                raise upnp.UpnpError(upnp.NO_ENTRY, "NoSuchEntryInArray")
            return {"NewInternalClient": m["client"], "NewInternalPort": str(m.get("port", port)), "NewEnabled": "1",
                    "NewPortMappingDescription": m["desc"]}
        if action == "DeletePortMapping":
            self.table.pop(port)
            return {}
        if action == "AddPortMapping":
            m = self.table.get(port)
            if m and m["client"] != args["NewInternalClient"]:
                raise upnp.UpnpError(upnp.CONFLICT, "ConflictInMappingEntry")
            self.table[port] = {"client": args["NewInternalClient"], "port": int(args["NewInternalPort"]), "desc": args["NewPortMappingDescription"]}
            return {}
        raise AssertionError(action)


class OpenCloseCheck(unittest.TestCase):
    def test_own_mapping_on_old_address_is_moved(self):
        router = FakeRouter({2106: {"client": "192.168.2.50", "desc": "L2Hub логин"}})
        with patch.object(upnp, "soap", router):
            upnp.open_port(GW, 2106, "логин")
        self.assertEqual(router.table[2106]["client"], "192.168.2.230")
        self.assertIn("DeletePortMapping", router.calls)

    def test_foreign_mapping_is_not_touched(self):
        router = FakeRouter({7777: {"client": "192.168.2.77", "desc": "Plex"}})
        with patch.object(upnp, "soap", router), self.assertRaises(RuntimeError) as e:
            upnp.open_port(GW, 7777, "гейм")
        self.assertIn("192.168.2.77", str(e.exception))
        self.assertEqual(router.table[7777]["client"], "192.168.2.77")
        self.assertNotIn("DeletePortMapping", router.calls)

    def test_manual_mapping_to_this_pc_is_kept(self):
        router = FakeRouter({2106: {"client": "192.168.2.230", "desc": ""}})
        with patch.object(upnp, "soap", router):
            upnp.open_port(GW, 2106, "логин")
            self.assertFalse(upnp.close_port(GW, 2106))
        self.assertEqual(router.table[2106], {"client": "192.168.2.230", "desc": ""})
        self.assertEqual(router.calls, ["GetSpecificPortMappingEntry"] * 2)

    def test_close_removes_own_on_old_address_only(self):
        router = FakeRouter({2106: {"client": "192.168.2.50", "desc": "L2Hub логин"}, 7777: {"client": "192.168.2.77", "desc": "Plex"}})
        with patch.object(upnp, "soap", router):
            self.assertTrue(upnp.close_port(GW, 2106))
            self.assertFalse(upnp.close_port(GW, 7777))
        self.assertEqual(list(router.table), [7777])


class PlayStepCheck(unittest.TestCase):
    def router_step(self):
        a = api.Api.__new__(api.Api)
        a._lock, a._job = threading.Lock(), None
        captured = {}
        p = {"id": "ct26_highfive", "type": "mobius"}
        with patch.object(api.catalog, "profile", return_value=p), patch.object(api.l2solo, "is_l2solo", return_value=False), \
                patch.object(api.servers, "own_db", return_value=True), patch.object(api.servers, "brain_port", return_value=None), \
                patch.object(api.network, "state", return_value={"kind": "internet"}), \
                patch.object(api.Api, "_start_job", lambda self, cid, action, steps, *a: captured.update(steps=steps) or {"ok": True}):
            a.play("ct26_highfive", with_client=False)
        return next(fn for label, fn in captured["steps"] if label == "Порты на роутере…")

    def run_step(self, result):
        step = self.router_step()
        job = api.Job("ct26_highfive", "start", [("Порты на роутере…", step)])
        with patch.object(api.network, "internet_ports", return_value=result):
            job.run()
        return job.as_dict()

    def test_failed_mapping_is_reported(self):
        d = self.run_step({"ok": False, "error": "порт 7777 на роутере уже проброшен на 192.168.2.77 (Plex)", "upnp": True})
        self.assertIsNone(d["error"])
        self.assertIn("192.168.2.77", d["warning"])

    def test_no_upnp_stays_quiet(self):
        d = self.run_step({"ok": False, "error": "роутер не ответил по UPnP", "upnp": False})
        self.assertEqual(d["warning"], "")

    def test_internet_ports_tells_upnp_apart(self):
        with patch.object(network, "lan_sources", return_value=["192.168.2.230"]), patch.object(upnp, "probe"):
            with patch.object(upnp, "gateway", side_effect=RuntimeError("роутер не ответил по UPnP")):
                self.assertFalse(network.internet_ports(True)["upnp"])
            router = FakeRouter({7777: {"client": "192.168.2.77", "desc": "Plex"}})
            with patch.object(upnp, "gateway", return_value=GW), patch.object(upnp, "soap", router):
                r = network.internet_ports(True)
        self.assertEqual((r["ok"], r["upnp"]), (False, True))


if __name__ == "__main__":
    unittest.main()
