from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]

class ProjectIntegrationInventory(unittest.TestCase):
    def test_camera_recorder_and_duration_guard(self):
        source = (ROOT / 'app/src/main/java/ru/momento/app/ui/CameraScreen.kt').read_text()
        for token in ('VideoRecordEvent.Finalize', 'MediaMetadataRetriever', '2_000L..5_000L', 'onSaved(output, duration)'):
            self.assertIn(token, source)

    def test_account_bound_upload_and_idempotency_key(self):
        source = (ROOT / 'app/src/main/java/ru/momento/app/data/MomentUploadWorker.kt').read_text()
        for token in ('readVerifiedAccountId()', 'uploadRequestId', 'ExistingWorkPolicy.KEEP', 'completeUpload('):
            self.assertIn(token, source)

    def test_no_unapproved_runtime_dependencies(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        for token in ('media3-exoplayer', 'retrofit2', 'coil-compose', 'hilt-android'):
            self.assertNotIn(token, gradle)

if __name__ == '__main__':
    unittest.main()
