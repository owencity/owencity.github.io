"""Offline OAuth setup tests. All credentials below are fabricated test values."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('devdesk_auth', Path(__file__).with_name('devdesk-google-auth.py'))
auth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auth)


class AuthSettingsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / '.env.google'
        self.client = {'client_id': 'test-client', 'client_secret': 'test-secret'}

    def test_write_requests_owned_event_permission(self):
        self.assertEqual(auth.requested_scope('write'), auth.WRITE_SCOPE)
        self.assertEqual(auth.requested_scope('read'), auth.READ_SCOPE)
        with self.assertRaises(ValueError):
            auth.requested_scope('admin')

    def test_readonly_grant_cannot_enable_writes(self):
        with self.assertRaisesRegex(RuntimeError, 'permission was not granted'):
            auth.save_settings(self.output, self.client,
                {'refresh_token': 'test-refresh', 'scope': auth.READ_SCOPE}, 'write')
        self.assertFalse(self.output.exists())

    def test_write_grant_saves_edit_setting(self):
        auth.save_settings(self.output, self.client,
            {'refresh_token': 'test-refresh', 'scope': auth.WRITE_SCOPE}, 'write')
        self.assertIn("GOOGLE_CALENDAR_WRITE_ENABLED='true'", self.output.read_text())

    def test_default_read_grant_does_not_enable_writes(self):
        auth.save_settings(self.output, self.client, {'refresh_token': 'test-refresh'}, 'read')
        self.assertIn("GOOGLE_CALENDAR_WRITE_ENABLED='false'", self.output.read_text())

    def test_tasks_grant_saves_only_the_tasks_token(self):
        auth.save_settings(self.output, self.client, {'refresh_token': 'test-refresh', 'scope': auth.TASKS_SCOPE}, 'tasks')
        self.assertEqual(self.output.read_text(), "GOOGLE_TASKS_REFRESH_TOKEN='test-refresh'\n")

    def test_install_tasks_updates_only_the_tasks_token_and_requires_matching_oauth_client(self):
        before = ("GOOGLE_CLIENT_ID='test-client'\r\nGOOGLE_CLIENT_SECRET='test-secret'\r\n"
                  "GOOGLE_REFRESH_TOKEN='calendar-refresh'\r\nGEMINI_API_KEY='test-key'\r\n"
                  "GOOGLE_TASKS_REFRESH_TOKEN='old-tasks'\r\n")
        self.output.write_bytes(before.encode())
        auth.install_tasks(self.output, self.client, {'refresh_token': 'new-tasks', 'scope': auth.TASKS_SCOPE})
        self.assertEqual(self.output.read_bytes(), before.replace('old-tasks', 'new-tasks').encode())
        with self.assertRaisesRegex(RuntimeError, 'does not match'):
            auth.install_tasks(self.output, {'client_id': 'other-client', 'client_secret': 'test-secret'},
                {'refresh_token': 'other-tasks', 'scope': auth.TASKS_SCOPE})
        self.assertNotIn(b'other-tasks', self.output.read_bytes())

    def test_existing_settings_are_never_overwritten(self):
        self.output.write_text('existing-settings')
        with self.assertRaises(FileExistsError):
            auth.save_settings(self.output, self.client, {'refresh_token': 'test-refresh'}, 'write')
        self.assertEqual(self.output.read_text(), 'existing-settings')

    def test_missing_token_or_line_injection_saves_nothing(self):
        for tokens in ({}, {'refresh_token': 'fake\nANOTHER_SETTING=true'}):
            with self.subTest(tokens=tokens):
                with self.assertRaises(RuntimeError):
                    auth.save_settings(self.output, self.client, tokens, 'write')
                self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
