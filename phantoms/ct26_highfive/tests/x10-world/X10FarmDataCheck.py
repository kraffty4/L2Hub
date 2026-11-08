"""Проверка экономики, реальных уровней и геодаты подготовленных фарм-спотов."""
import copy
import os
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / "tools"))
import x10_farm_spots as farm
import town_npcs as tn


class EconomyCheck(unittest.TestCase):
    def test_elite_changes_quantity_without_inflating_other_rewards(self):
        source = ET.fromstring('<npc id="20001" name="Test" type="Monster" level="40"><acquire exp="500" sp="30"/><stats><vitals hp="1000"/><attack physical="100" magical="40"/></stats><dropLists><drop><group chance="70"><item id="57" min="11" max="31" chance="60"/><item id="1865" min="1" max="2" chance="40"/></group></drop></dropLists></npc>')
        before = ET.tostring(source)
        result = farm.elite(source)
        self.assertEqual(before, ET.tostring(source), "Native data must remain untouched")
        self.assertEqual(result.find("acquire").attrib, source.find("acquire").attrib)
        self.assertEqual(result.find(".//item[@id='57']").attrib, dict(id="57", min="22", max="62", chance="60"))
        self.assertEqual(ET.tostring(result.find(".//item[@id='1865']")), ET.tostring(source.find(".//item[@id='1865']")))
        self.assertEqual(result.find(".//group").get("chance"), "70")
        self.assertEqual(float(result.find("stats/vitals").get("hp")), 2000)
        self.assertEqual(float(result.find("stats/attack").get("physical")), 150)


@unittest.skipUnless(os.environ.get("X10_FARM_DATA"), "Pass the native data directory via X10_FARM_DATA")
class NativeDataCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = Path(os.environ["X10_FARM_DATA"])
        cls.module = REPO / "phantoms/ct26_highfive/dist/game/data"
        cls.native = {int(n.get("id")): n for f in (cls.data / "stats/npcs").glob("*.xml") for n in ET.parse(f).getroot().findall("npc")}
        cls.catalogue = list(ET.parse(cls.module / "L2HubX10FarmSpots.xml").getroot())
        cls.elites = {int(n.get("id")): n for n in ET.parse(cls.module / "stats/npcs/custom/L2HubX10Adena.xml").getroot()}

    def test_all_recommendations_and_four_distinct_adena_areas(self):
        expected = {area[0] for area in farm.AREAS} | {"adena_" + key for key in farm.ADENA_AREAS}
        self.assertEqual({s.get("id") for s in self.catalogue}, expected)
        self.assertEqual(len(self.catalogue), 31)
        for spot in self.catalogue:
            self.assertTrue(spot.get("ru") and spot.get("en"))
            self.assertEqual(len(spot.findall("pack")), 12)
            levels = []
            for pack in spot:
                iid = int(pack.get("npc"))
                levels.append(int((self.elites[iid] if iid in self.elites else self.native[iid]).get("level")))
                self.assertEqual(int(pack.get("count")), 6 if spot.get("kind") == "adena" else 3)
            self.assertEqual(int(spot.get("min")), min(levels))
            self.assertEqual(int(spot.get("max")), max(levels))
        by_id = {s.get("id"): s for s in self.catalogue}
        self.assertEqual(by_id["cruma"].get("maxPlayer"), "55")
        self.assertEqual({s.get("id") for s in self.catalogue if s.get("conditional") == "true"}, {"hellbound", "annihilation"})

    def test_actual_elites_preserve_xp_and_other_drops(self):
        for iid, clone in self.elites.items():
            original = self.native[iid - 920000]
            self.assertEqual(clone.find("acquire").attrib, original.find("acquire").attrib)
            self.assertAlmostEqual(float(clone.find("stats/vitals").get("hp")), float(original.find("stats/vitals").get("hp")) * 2, places=5)
            for key in ("physical", "magical"):
                self.assertAlmostEqual(float(clone.find("stats/attack").get(key)), float(original.find("stats/attack").get(key)) * 1.5, places=5)
            expected = copy.deepcopy(original.find("dropLists"))
            for item in expected.findall("drop//item[@id='57']"):
                item.set("min", str(int(item.get("min")) * 2))
                item.set("max", str(int(item.get("max")) * 2))
            self.assertEqual(ET.canonicalize(ET.tostring(clone.find("dropLists"), encoding="unicode"), strip_text=True), ET.canonicalize(ET.tostring(expected, encoding="unicode"), strip_text=True))
            self.assertEqual(clone.get("displayId"), original.get("displayId", original.get("id")))

    def test_all_destinations_and_packs_have_open_geodata_cells(self):
        geo = tn.Geo(self.data / "geodata")
        for spot in self.catalogue:
            for point in (spot, *spot.findall("pack")):
                x, y, z = (int(point.get(k)) for k in ("x", "y", "z"))
                cell = geo.cell(x, y, z)
                self.assertIsNotNone(cell, spot.get("id"))
                self.assertLessEqual(abs(cell[0] - z), 80, spot.get("id"))
                self.assertEqual(cell[1], 15, spot.get("id"))

    def test_custom_npc_ids_do_not_collide(self):
        collisions = []
        for file in (self.data / "stats/npcs").rglob("*.xml"):
            if file.name == "L2HubX10Adena.xml":
                continue
            collisions.extend(int(n.get("id")) for n in ET.parse(file).getroot().findall("npc") if int(n.get("id")) in self.elites)
        self.assertEqual(collisions, [])

    def test_teleport_has_clearance_and_monsters_cover_the_area(self):
        import math
        for spot in self.catalogue:
            entry=(int(spot.get('x')),int(spot.get('y')))
            points=[(int(pack.get('x')),int(pack.get('y'))) for pack in spot]
            self.assertGreaterEqual(min(math.dist(entry,p) for p in points),900,spot.get('id'))
            self.assertGreaterEqual(max(math.dist(a,b) for a in points for b in points),900,spot.get('id'))


if __name__ == "__main__":
    unittest.main()
