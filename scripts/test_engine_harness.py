import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("engine_harness", Path(__file__).with_name("test-engine.py"))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.run = harness.EngineRun(SimpleNamespace(output=self.base / "evidence", artifacts=self.base / "shared"))
        self.run.root = self.base / "installation"
        self.run.root.mkdir()
        (self.run.root / ".owner").write_text(self.run.owner)
        self.run.install = self.run.root / "oie"
        self.run.install.mkdir()

    def test_completed_run_preserves_evidence_and_deduplicated_inputs_only(self):
        (self.run.install / "input.jar").write_bytes(b"owned immutable input")
        self.run.cleanup()
        self.assertFalse(self.run.root.exists())
        manifest = json.loads((self.run.output / "runtime-inputs.json").read_text())
        self.assertEqual((self.base / "shared" / manifest[0]["sha256"]).read_bytes(), b"owned immutable input")
        self.assertTrue(json.loads((self.run.output / "cleanup.json").read_text())["removed"])

    def test_wrong_owner_never_signals_or_removes(self):
        (self.run.root / ".owner").write_text("different-owner")
        self.run.process = Mock()
        with patch.object(harness.os, "killpg") as signal:
            self.run.cleanup()
        signal.assert_not_called()
        self.assertTrue(self.run.root.exists())
        self.assertIn("unowned", json.loads((self.run.output / "cleanup.json").read_text())["refused"])

    def test_exited_parent_with_live_descendants_prevents_removal(self):
        self.run.process = Mock(pid=123, returncode=0)
        self.run.process.poll.return_value = 0
        with patch.object(harness, "group_members", return_value=["456 123 owned-child"]), patch.object(harness.os, "killpg") as signal:
            self.run.cleanup()
        signal.assert_not_called()
        self.assertTrue(self.run.root.exists())
        self.assertIn("descendants", json.loads((self.run.output / "cleanup.json").read_text())["refused"])


if __name__ == "__main__":
    unittest.main()
