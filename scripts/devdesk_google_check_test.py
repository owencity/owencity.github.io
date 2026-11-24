"""Connection checks use fabricated credentials and never contact Google."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('calendar_check', Path(__file__).with_name('devdesk-google-check.py'))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class ConnectionCheckTest(unittest.TestCase):
    def settings(self):
        return {'GOOGLE_CLIENT_ID': 'fake-client', 'GOOGLE_CLIENT_SECRET': 'fake-secret',
                'GOOGLE_REFRESH_TOKEN': 'fake-refresh', 'GOOGLE_CALENDAR_WRITE_ENABLED': 'true'}

    def run_check(self, calendar_status=200, calendar_body=None, scope=None):
        requests = []
        def send(request):
            requests.append(request)
            if len(requests) == 1:
                token = {'access_token': 'fake-access'}
                if scope is not None:
                    token['scope'] = scope
                return 200, token
            return calendar_status, calendar_body or {'accessRole': 'owner'}
        return check.check(self.settings(), send), requests

    def test_ready_check_requests_no_event_content_and_never_mutates_calendar(self):
        result, requests = self.run_check(scope=next(iter(check.WRITE_SCOPES)))
        self.assertEqual(result['result'], 'READY')
        self.assertEqual([request.get_method() for request in requests], ['POST', 'GET'])
        self.assertIn('fields=accessRole', requests[-1].full_url)
        self.assertNotIn('fake-access', requests[-1].full_url)
        for secret in ['fake-access', 'fake-client', 'fake-secret', 'fake-refresh']:
            self.assertNotIn(secret, json.dumps(result))

    def test_disabled_api_is_not_misreported_as_missing_consent(self):
        result, _ = self.run_check(403, {'error': {'errors': [{'reason': 'accessNotConfigured'}], 'message': 'fake-secret'}})
        self.assertEqual(result['result'], 'CALENDAR_API_DISABLED')
        self.assertNotIn('fake-secret', json.dumps(result))

    def test_scope_and_quota_failures_are_separate(self):
        self.assertEqual(check.error_kind({'error': {'details': [{'@type': 'type.googleapis.com/google.rpc.ErrorInfo',
            'domain': 'googleapis.com', 'reason': 'ACCESS_TOKEN_SCOPE_INSUFFICIENT'}]}}), 'SCOPE_INSUFFICIENT')
        self.assertEqual(check.error_kind({'error': {'errors': [{'reason': 'userRateLimitExceeded'}]}}), 'RATE_LIMIT')

    def test_unknown_scope_or_nonowner_never_reports_ready(self):
        result, _ = self.run_check()
        self.assertEqual(result['result'], 'WRITE_SCOPE_UNCONFIRMED')
        result, _ = self.run_check(calendar_body={'accessRole': 'writer'}, scope=next(iter(check.WRITE_SCOPES)))
        self.assertEqual(result['result'], 'NOT_OWNER')

    def test_env_parser_reads_only_google_settings_with_quotes_without_modifying_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '.env'
            original = "GOOGLE_CLIENT_SECRET='fake=a$b'\nOWNER_PASSWORD='not-read'\n"
            path.write_text(original, encoding='utf-8')
            self.assertEqual(check.read_settings(path), {'GOOGLE_CLIENT_SECRET': 'fake=a$b'})
            self.assertEqual(path.read_text(encoding='utf-8'), original)


if __name__ == '__main__':
    unittest.main()
