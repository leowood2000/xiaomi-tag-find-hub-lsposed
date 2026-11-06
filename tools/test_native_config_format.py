import struct
import unittest
from native_config_format import Snapshot, add_bool, remove_added_bool, snapshot_leaf


class NativeFormatTests(unittest.TestCase):
    def snapshot(self, metadata=b"\x08\x07", payload=b"untouched compressed flags"):
        return struct.pack("<II", 1, 0) + bytes([len(metadata)]) + metadata + payload

    def test_leaf_matches_independently_observed_runtime_path(self):
        self.assertEqual(snapshot_leaf("com.google.android.gms.phenotype"),
                         "jH6wMIA11so7er5idBKzug==.pb")

    def test_account_changes_identity_and_invalid_paths_rejected(self):
        self.assertNotEqual(snapshot_leaf("namespace"), snapshot_leaf("namespace", "account"))
        with self.assertRaises(ValueError):
            snapshot_leaf("../escape")

    def test_patch_preserves_payload_unknown_metadata_and_rolls_back(self):
        source = self.snapshot(b"\x08\x07\x9a\x06\x03xyz")
        patched, token = add_bool(source, "enableDebugService", True)
        self.assertEqual(Snapshot.parse(source).payload, Snapshot.parse(patched).payload)
        self.assertTrue(Snapshot.parse(patched).metadata.startswith(Snapshot.parse(source).metadata))
        self.assertEqual(remove_added_bool(patched, "enableDebugService", token), source)

    def test_rollback_preserves_concurrent_payload_and_unrelated_override(self):
        patched, token = add_bool(self.snapshot(), "enableDebugService", True)
        other, _ = add_bool(patched, "unrelated", False)
        state = Snapshot.parse(other)
        changed = Snapshot(state.prefix, state.metadata + b"\x30\x01", b"new server flags").encode()
        restored = Snapshot.parse(remove_added_bool(changed, "enableDebugService", token))
        self.assertEqual(restored.payload, b"new server flags")
        self.assertIn("unrelated", restored.overrides())
        self.assertNotIn("enableDebugService", restored.overrides())
        self.assertTrue(restored.metadata.endswith(b"\x30\x01"))

    def test_refuses_existing_override_and_changed_ownership(self):
        patched, token = add_bool(self.snapshot(), "flag", True)
        with self.assertRaises(ValueError):
            add_bool(patched, "flag", False)
        changed, _ = add_bool(self.snapshot(), "flag", False)
        with self.assertRaises(ValueError):
            remove_added_bool(changed, "flag", token)

    def test_rollback_is_idempotent_after_external_removal(self):
        source = self.snapshot()
        _, token = add_bool(source, "flag", True)
        self.assertEqual(remove_added_bool(source, "flag", token), source)

    def test_rejects_unverified_version_truncation_and_duplicate_keys(self):
        valid = self.snapshot()
        for invalid in (b"", struct.pack("<II", 2, 0) + valid[8:],
                        struct.pack("<II", 1, 0) + b"\xff" * 11,
                        struct.pack("<II", 1, 0) + b"\x7f\x08"):
            with self.assertRaises(ValueError):
                Snapshot.parse(invalid)
        patched, token = add_bool(valid, "flag", True)
        snap = Snapshot.parse(patched)
        with self.assertRaises(ValueError):
            Snapshot(snap.prefix, snap.metadata + token, snap.payload).overrides()


if __name__ == "__main__":
    unittest.main()
