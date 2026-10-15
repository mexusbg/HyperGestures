import contextlib
import io
import shutil
import tempfile
import unittest
from pathlib import Path
from tools.hgtools import aconfig_gen as g

FIX = Path(__file__).parent / "fixtures" / "aconfig"
ROOT = Path(__file__).resolve().parents[3]


def _write_release_configs(d: Path, configs: dict) -> None:
    for name, inherits in configs.items():
        text = f'name: "{name}"\n' + "".join(f'inherits: "{i}"\n' for i in inherits)
        (d / f"{name}.textproto").write_text(text + f'aconfig_value_sets: "set-{name}"\n')


class AconfigGenTest(unittest.TestCase):
    def test_parse_declarations(self):
        pkg, flags = g.parse_declarations((FIX / "decl.aconfig").read_text())
        self.assertEqual(pkg, "com.example.flags")
        self.assertEqual([f.name for f in flags], ["enable_foo_bar", "exported_thing", "never_set"])
        self.assertTrue(flags[1].is_exported and flags[1].is_fixed_read_only)
        self.assertFalse(flags[0].is_exported)

    def test_parse_values(self):
        vals = g.parse_values((FIX / "values_low.textproto").read_text())
        self.assertEqual(vals, [("com.example.flags", "enable_foo_bar", False),
                                ("com.example.flags", "exported_thing", True)])

    def test_camel(self):
        self.assertEqual(g.camel("enable_foo_bar"), "enableFooBar")
        self.assertEqual(g.camel("____some_snake___name____"), "someSnakeName")
        self.assertEqual(g.camel("a11y_thing"), "a11yThing")

    def test_precedence_project_over_device_over_aosp(self):
        _, flags = g.parse_declarations((FIX / "decl.aconfig").read_text())
        decls = [("com.example.flags", f) for f in flags]
        low = {(p, n): v for p, n, v in g.parse_values((FIX / "values_low.textproto").read_text())}
        high = {(p, n): v for p, n, v in g.parse_values((FIX / "values_high.textproto").read_text())}
        device = {("com.example.flags", "exported_thing"): False}
        project = {("com.example.flags", "enable_foo_bar"): False}
        r = g.resolve(decls, [("aosp:low", low), ("aosp:high", high), ("device", device), ("project", project)])
        self.assertEqual(r[("com.example.flags", "enable_foo_bar")], (False, "project"))
        self.assertEqual(r[("com.example.flags", "exported_thing")], (False, "device"))
        self.assertEqual(r[("com.example.flags", "never_set")], (False, "default"))
        r2 = g.resolve(decls, [("aosp:low", low), ("aosp:high", high)])
        self.assertEqual(r2[("com.example.flags", "enable_foo_bar")], (True, "aosp:high"))

    def test_render_flags_java(self):
        src = g.render_flags_java("com.example.flags", [("enable_foo_bar", True), ("never_set", False)])
        self.assertIn("package com.example.flags;", src)
        self.assertIn('public static final String FLAG_ENABLE_FOO_BAR = "com.example.flags.enable_foo_bar";', src)
        self.assertIn("public static boolean enableFooBar() { return true; }", src)
        self.assertIn("public static boolean neverSet() { return false; }", src)
        self.assertIn("public final class Flags", src)

    def test_overrides_file_parsing_ignores_comments(self):
        text = "# why\ncom.a.b.c_d=true  # trailing\n\ncom.a.b.e=false\n"
        self.assertEqual(g.parse_kv(text), {("com.a.b", "c_d"): True, ("com.a.b", "e"): False})

    def test_multiple_flags_each_with_several_string_fields_all_parse(self):
        # Regression test: an earlier parser implementation used a generator
        # "peek by consuming, then re-wrap the peeked token with the
        # remainder" trick for adjacent-string-literal lookahead. Whenever a
        # peeked token turned out to be a block-closing "}" rather than
        # another string, the wrapper generator created for the lookahead
        # was discarded immediately. Because CPython refcounts objects and
        # `yield from` propagates close() to its delegate (PEP 380), that
        # discard cascaded down and silently closed the underlying token
        # generator, truncating the parse after the first block that had
        # more than one string-valued field. This is deterministic (not a
        # timing-dependent GC flake) and reproduces on decl.aconfig itself,
        # so it is also covered directly here with a minimal, self-contained
        # case: two flags, each with two string fields.
        text = (
            'package: "p"\n'
            'flag { name: "a" namespace: "n" }\n'
            'flag { name: "b" namespace: "n2" }\n'
        )
        pkg, flags = g.parse_declarations(text)
        self.assertEqual(pkg, "p")
        self.assertEqual([f.name for f in flags], ["a", "b"])
        self.assertEqual([f.namespace for f in flags], ["n", "n2"])

    # --- project override vs device.txt conflicts (I3) ---------------------------------------

    def test_conflicts_lists_only_contradicting_overrides(self):
        device = {("p", "a"): True, ("p", "b"): False, ("p", "c"): True}
        project = {("p", "a"): False, ("p", "b"): False, ("p", "d"): True}
        self.assertEqual(g.conflicts(device, project), [(("p", "a"), True, False)])

    def _run_main(self, device_text: str):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / "values" / "low" / "com.example.flags").mkdir(parents=True)
        shutil.copy(FIX / "values_low.textproto", tmp / "values" / "low" / "com.example.flags" / "v.textproto")
        (tmp / "chain.txt").write_text("low\n")
        (tmp / "device.txt").write_text(device_text)
        (tmp / "overrides.txt").write_text("# why\ncom.example.flags.enable_foo_bar=true\n")
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            g.main(["--decl", str(FIX / "decl.aconfig"), "--values-root", str(tmp / "values"),
                    "--chain", str(tmp / "chain.txt"), "--device", str(tmp / "device.txt"),
                    "--overrides", str(tmp / "overrides.txt"), "--out", str(tmp / "out"),
                    "--report", str(tmp / "VALUES.md")])
        return err.getvalue(), (tmp / "VALUES.md").read_text(), (tmp / "out")

    def test_main_warns_and_reports_override_contradicting_device(self):
        stderr, report, out = self._run_main("com.example.flags.enable_foo_bar=false\n")
        self.assertIn("WARNING", stderr)
        self.assertIn("com.example.flags.enable_foo_bar", stderr)
        self.assertIn("## Conflicts (project override vs device)", report)
        self.assertIn("| `com.example.flags.enable_foo_bar` | false | true |", report)
        # the project override still wins
        java = (out / "com" / "example" / "flags" / "Flags.java").read_text()
        self.assertIn("public static boolean enableFooBar() { return true; }", java)

    def test_main_without_conflicts_is_quiet_and_has_no_section(self):
        stderr, report, _ = self._run_main("com.example.flags.enable_foo_bar=true\n")
        self.assertNotIn("WARNING", stderr)
        self.assertNotIn("Conflicts", report)

    # --- the AOSP release-config chain (M4) --------------------------------------------------

    def test_release_chain_follows_soong_inherits_order(self):
        # The real cp2a shape: cp2a inherits mainline_04 then cp1a; mainline_03 inherits
        # mainline_02 then cp1a; both branches share bp4a. Soong applies each inherited config's
        # contributions in inherits order, a config already applied is not applied again, and a
        # config's own values come last (highest).
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        _write_release_configs(tmp, {
            "ap3a": [], "bp4a": ["ap3a"], "cp1a": ["bp4a"],
            "m01": ["bp4a"], "m02": ["m01"], "m03": ["m02", "cp1a"], "m04": ["m03"],
            "cp2a": ["m04", "cp1a"],
        })
        self.assertEqual(g.release_chain(tmp, "cp2a"),
                         ["ap3a", "bp4a", "m01", "m02", "cp1a", "m03", "m04", "cp2a"])

    def test_release_chain_rejects_inheritance_loops(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        _write_release_configs(tmp, {"a": ["b"], "b": ["a"]})
        with self.assertRaises(ValueError):
            g.release_chain(tmp, "a")

    def test_committed_chain_is_derived_from_vendored_release_configs(self):
        committed = [line.strip() for line in (ROOT / "flags/values/aosp-chain.txt").read_text().splitlines()
                     if line.strip() and not line.startswith("#")]
        self.assertEqual(g.release_chain(ROOT / "aosp/build-release/release_configs", "cp2a"), committed)


if __name__ == "__main__":
    unittest.main()
