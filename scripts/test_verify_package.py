"""Release gate regressions using the actual build output, without credentials."""

import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("verify_package", Path(__file__).with_name("verify-package.py"))
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("pom.xml", "oie.json", "docs/store.md", "LICENSE", "package/resources/LICENSE",
                     "package/webadmin/plugin.json", "package/webadmin/package.json",
                     "package/webadmin/package-lock.json"):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(verify.ROOT / name, target)
        self.version = verify.verify_versions(self.root, {})
        self.bundle = self.root / f"oie-debugger-{self.version}.zip"
        shutil.copyfile(verify.ROOT / "package/target" / self.bundle.name, self.bundle)

    def rewrite_zip(self, change):
        with zipfile.ZipFile(self.bundle) as archive:
            entries = {entry.filename: archive.read(entry) for entry in archive.infolist()}
        change(entries)
        with zipfile.ZipFile(self.bundle, "w") as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def test_valid_build_and_matching_tag(self):
        self.assertEqual(verify.verify_bundle(self.bundle, self.root,
                         {"GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": "v" + self.version}), self.version)

    def test_wrong_tag_rejected(self):
        with self.assertRaisesRegex(ValueError, "Release tag"):
            verify.verify_bundle(self.bundle, self.root, {"GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": "v9.9.9"})

    def test_manifest_drift_rejected(self):
        path = self.root / "package/webadmin/plugin.json"
        data = json.loads(path.read_text())
        data["version"] = "9.9.9"
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "version must match"):
            verify.verify_bundle(self.bundle, self.root, {})

    def test_stale_jar_rejected(self):
        self.rewrite_zip(lambda entries: entries.update({"oie-debugger/libs/old.jar": b"stale"}))
        with self.assertRaisesRegex(ValueError, "exactly"):
            verify.verify_bundle(self.bundle, self.root, {})

    def test_catalog_drift_rejected(self):
        path = self.root / "oie.json"
        for field, value, message in (("version", "9.9.9", "oie.json version"),
                                      ("filename", "wrong.zip", "Catalog filename"),
                                      ("storeDocs", "missing.md", "documentation must exist")):
            with self.subTest(field=field):
                data = json.loads((verify.ROOT / "oie.json").read_text())
                data[field] = value
                path.write_text(json.dumps(data))
                with self.assertRaisesRegex(ValueError, message):
                    verify.verify_versions(self.root, {})

    def test_missing_browser_bundle_rejected(self):
        self.rewrite_zip(lambda entries: entries.pop("oie-debugger/webadmin/web/plugin.js"))
        with self.assertRaisesRegex(ValueError, "exactly"):
            verify.verify_bundle(self.bundle, self.root, {})

    def test_wrong_packaged_descriptor_rejected(self):
        def change(entries):
            name = "oie-debugger/plugin.xml"
            entries[name] = entries[name].replace(f"<pluginVersion>{self.version}</pluginVersion>".encode(),
                                                  b"<pluginVersion>9.9.9</pluginVersion>")
        self.rewrite_zip(change)
        with self.assertRaisesRegex(ValueError, "descriptor version"):
            verify.verify_bundle(self.bundle, self.root, {})

    def test_duplicate_entry_rejected(self):
        with zipfile.ZipFile(self.bundle, "a") as archive:
            archive.writestr("oie-debugger/plugin.xml", b"duplicate")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            verify.verify_bundle(self.bundle, self.root, {})

    def test_changed_packaged_license_rejected(self):
        self.rewrite_zip(lambda entries: entries.update({"oie-debugger/LICENSE": b"wrong license"}))
        with self.assertRaisesRegex(ValueError, "LICENSE changed"):
            verify.verify_bundle(self.bundle, self.root, {})


if __name__ == "__main__":
    unittest.main()
