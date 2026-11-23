from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]

class ProjectIntegrationInventory(unittest.TestCase):
    def test_camera_recorder_and_duration_guard(self):
        source = (ROOT / 'app/src/main/java/ru/momento/app/ui/CameraScreen.kt').read_text()
        for token in ('VideoRecordEvent.Finalize', 'LocalMomentDatabase.readCaptureDuration(output)', 'setDurationLimitMillis', 'onSaved(output, duration)'):
            self.assertIn(token, source)

    def test_account_bound_upload_and_idempotency_key(self):
        source = (ROOT / 'app/src/main/java/ru/momento/app/data/MomentUploadWorker.kt').read_text()
        for token in ('readVerifiedAccountId()', 'uploadRequestId', 'ExistingWorkPolicy.APPEND_OR_REPLACE', 'completeUpload('):
            self.assertIn(token, source)

if __name__ == '__main__':
    unittest.main()
