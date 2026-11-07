"""Контракт миграции меню0146: без дублирования и потери локальных услуг."""
import importlib.util
import sys
import unittest
from pathlib import Path

source = Path(__file__).resolve().parents[1] / "tools/merge_board_navigation.py"
spec = importlib.util.spec_from_file_location("epilogue_navigation", source)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def menu(*actions, newline="\n", bom=False):
    html = '<table valign="top">' + newline
    html += newline.join('<tr><td><button value="Услуга' + str(i) + '" action="' + action
                         + '" width=200 height=30 back="local.skin"></td></tr>'
                         for i, action in enumerate(actions))
    html += newline + "</table>"
    return (b"\xef\xbb\xbf" if bom else b"") + html.encode("utf-8")


def buttons(data):
    return module.Navigation(data.decode("utf-8-sig")).buttons


class BoardNavigationMerge(unittest.TestCase):
    def test_old_union_bug_and_known_alias(self):
        local = menu("bypass _bbstop;home.html", module.LEGACY_TELEPORT,
                     "bypass _bbshubweight", module.UNIFIED_TELEPORT)
        native = menu("bypass _bbstop;home.html", module.UNIFIED_TELEPORT, "bypass _bbshubplay")
        # Старый union-by-action сохранял обе ссылки: этот вход воспроизводит дефект.
        self.assertEqual(sum(b.action in {module.LEGACY_TELEPORT, module.UNIFIED_TELEPORT}
                             for b in buttons(local)), 2)
        merged = buttons(module.merge_navigation(local, native))
        self.assertEqual([b.action for b in merged], ["bypass _bbstop;home.html", module.UNIFIED_TELEPORT,
                                                     "bypass _bbshubweight", "bypass _bbshubplay"])
        self.assertEqual(merged[2].raw, buttons(local)[2].raw)
        self.assertEqual(merged[1].raw, buttons(native)[1].raw)

    def test_only_legacy_link_migrates(self):
        actual = buttons(module.merge_navigation(menu(module.LEGACY_TELEPORT), menu(module.UNIFIED_TELEPORT)))
        self.assertEqual([b.action for b in actual], [module.UNIFIED_TELEPORT])

    def test_no_native_route_keeps_legacy(self):
        actual = buttons(module.merge_navigation(menu(module.LEGACY_TELEPORT), menu("bypass _bbstop;home.html")))
        self.assertIn(module.LEGACY_TELEPORT, [b.action for b in actual])

    def test_special_gatekeepers_and_subcommands_are_not_aliases(self):
        custom = ["bypass _bbstop;gatekeeper/special.html", "bypass _bbshubspots 80", "bypass _bbshubspots go 12 80 0"]
        actual = buttons(module.merge_navigation(menu(*custom), menu(module.UNIFIED_TELEPORT)))
        self.assertEqual(actual[:3], buttons(menu(*custom)))

    def test_duplicate_aliases_only_one_but_other_duplicates_preserved(self):
        actual = buttons(module.merge_navigation(menu(module.UNIFIED_TELEPORT, "bypass local", "bypass local",
                                                     module.LEGACY_TELEPORT, module.UNIFIED_TELEPORT),
                                                 menu(module.UNIFIED_TELEPORT)))
        self.assertEqual([b.action for b in actual], [module.UNIFIED_TELEPORT, "bypass local", "bypass local"])

    def test_repeated_rebase_is_byte_idempotent(self):
        for local in (menu(module.LEGACY_TELEPORT), menu(module.UNIFIED_TELEPORT), menu("bypass custom")):
            native = menu(module.UNIFIED_TELEPORT, "bypass _bbshubplay")
            merged = module.merge_navigation(local, native)
            self.assertEqual(module.merge_navigation(merged, native), merged)

    def test_en_ru_native_label_and_local_service_skin(self):
        local = menu("bypass local", module.LEGACY_TELEPORT)
        for label in ("Teleport", "Телепорт"):
            native = menu(module.UNIFIED_TELEPORT).replace("Услуга0".encode(), label.encode())
            merged = buttons(module.merge_navigation(local, native))
            self.assertEqual(merged[0].raw, buttons(local)[0].raw)
            self.assertIn('value="' + label + '"', merged[1].raw)

    def test_bom_crlf_are_preserved(self):
        merged = module.merge_navigation(menu(module.LEGACY_TELEPORT, newline="\r\n", bom=True),
                                         menu(module.UNIFIED_TELEPORT))
        self.assertTrue(merged.startswith(b"\xef\xbb\xbf"))
        self.assertEqual(merged.count(b"\n"), merged.count(b"\r\n"))

    def test_legacy_extra_row_closers_and_loose_button_are_normalized(self):
        legacy = ("<table><tr><td><button action='bypass local'></td></tr></tr>"
                  "<button action='" + module.LEGACY_TELEPORT + "'><br></tr></table>").encode()
        merged = module.merge_navigation(legacy, menu(module.UNIFIED_TELEPORT))
        self.assertEqual([b.action for b in buttons(merged)], ["bypass local", module.UNIFIED_TELEPORT])
        self.assertEqual(merged.count(b"<tr>"), merged.count(b"</tr>"))
        self.assertEqual(merged.count(b"<td>"), merged.count(b"</td>") - 1)

    def test_ambiguous_or_nonbutton_content_refuses_instead_of_dropping_it(self):
        bad = [b"<table><tr><td>keep text</td></tr></table>", b"<table><!--keep--></table>",
               b"<table><tr><td><button action='a' action='b'></td></tr></table>",
               b"<table><tr><td><button action='a'></tr>", b"<table><img src='keep'></table>",
               b"<table><table></table></table>", b"<table><?keep important?></table>"]
        for content in bad:
            with self.subTest(content=content), self.assertRaises(ValueError):
                module.merge_navigation(content, menu(module.UNIFIED_TELEPORT))
        with self.assertRaises(ValueError):
            module.merge_navigation(menu(module.LEGACY_TELEPORT), menu(module.UNIFIED_TELEPORT, module.UNIFIED_TELEPORT))


if __name__ == "__main__":
    unittest.main()
