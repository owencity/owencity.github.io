"""Check the configured Google Calendar connection without reading event contents or changing events."""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

LIMIT = 65536
KEYS = {'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'GOOGLE_REFRESH_TOKEN',
        'GOOGLE_CALENDAR_ID', 'GOOGLE_CALENDAR_WRITE_ENABLED'}
WRITE_SCOPES = {'https://www.googleapis.com/auth/calendar.events.owned',
                'https://www.googleapis.com/auth/calendar.events',
                'https://www.googleapis.com/auth/calendar'}


def read_settings(path):
    values = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        name, separator, value = line.partition('=')
        name = name.strip()
        if not separator or name not in KEYS:
            continue
        if name in values:
            raise ValueError('Duplicate Google setting; check the environment file.')
        value = value.strip()
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError('Invalid quoted Google setting.')
            value = value[1:-1]
        values[name] = value
    return values


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, target):
        return None


def exchange(request):
    opener = urllib.request.build_opener(NoRedirect())
    try:
        response = opener.open(request, timeout=15)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise ValueError('Google response exceeded the check limit.')
        try:
            payload = json.loads(raw)
        except (ValueError, UnicodeError):
            payload = {}
        return response.code, payload if isinstance(payload, dict) else {}


def error_kind(payload):
    error = payload.get('error', {})
    if isinstance(error, str):
        return 'REAUTHORIZE' if error in {'invalid_grant', 'invalid_client', 'unauthorized_client'} else 'GOOGLE_REJECTED'
    if not isinstance(error, dict):
        return 'GOOGLE_REJECTED'
    errors = error.get('errors') if isinstance(error.get('errors'), list) else []
    details = error.get('details') if isinstance(error.get('details'), list) else []
    reasons = {item.get('reason') for item in errors if isinstance(item, dict) and isinstance(item.get('reason'), str)}
    reasons.update(item.get('reason') for item in details if isinstance(item, dict)
                   and item.get('@type') == 'type.googleapis.com/google.rpc.ErrorInfo'
                   and item.get('domain') == 'googleapis.com' and isinstance(item.get('reason'), str))
    if reasons & {'accessNotConfigured', 'SERVICE_DISABLED'}:
        return 'CALENDAR_API_DISABLED'
    if reasons & {'insufficientPermissions', 'ACCESS_TOKEN_SCOPE_INSUFFICIENT'}:
        return 'SCOPE_INSUFFICIENT'
    if reasons & {'rateLimitExceeded', 'userRateLimitExceeded', 'quotaExceeded', 'dailyLimitExceeded', 'RATE_LIMIT_EXCEEDED', 'QUOTA_EXCEEDED'}:
        return 'RATE_LIMIT'
    if 'domainPolicy' in reasons:
        return 'ORGANIZATION_POLICY'
    return 'GOOGLE_REJECTED'


def check(settings, send=exchange):
    if any(not settings.get(key) for key in ('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'GOOGLE_REFRESH_TOKEN')):
        return {'result': 'GOOGLE_SETTINGS_MISSING'}
    form = urllib.parse.urlencode({'grant_type': 'refresh_token', 'client_id': settings['GOOGLE_CLIENT_ID'],
        'client_secret': settings['GOOGLE_CLIENT_SECRET'], 'refresh_token': settings['GOOGLE_REFRESH_TOKEN']}).encode()
    status, token = send(urllib.request.Request('https://oauth2.googleapis.com/token', data=form,
        headers={'Content-Type': 'application/x-www-form-urlencoded'}, method='POST'))
    if status != 200 or not isinstance(token.get('access_token'), str) or not token['access_token']:
        return {'result': error_kind(token), 'token_http_status': status}
    scope = token.get('scope')
    scope_status = 'unknown' if not isinstance(scope, str) or not scope.strip() else (
        'write' if set(scope.split()) & WRITE_SCOPES else 'no_write_scope')
    calendar = urllib.parse.quote(settings.get('GOOGLE_CALENDAR_ID', 'primary'), safe='')
    # Only the access role is requested. Event titles, dates and descriptions are not requested.
    status, data = send(urllib.request.Request(
        'https://www.googleapis.com/calendar/v3/calendars/' + calendar + '/events?maxResults=1&fields=accessRole',
        headers={'Authorization': 'Bearer ' + token['access_token'], 'Accept': 'application/json'}))
    enabled = settings.get('GOOGLE_CALENDAR_WRITE_ENABLED', 'false').lower() == 'true'
    raw_role = data.get('accessRole')
    role = raw_role if isinstance(raw_role, str) and raw_role in {'owner', 'writer', 'reader', 'freeBusyReader'} else 'unknown'
    result = error_kind(data) if status != 200 else ('WRITE_SETTING_OFF' if not enabled else
        'WRITE_SCOPE_UNCONFIRMED' if scope_status != 'write' else 'NOT_OWNER' if role != 'owner' else 'READY')
    return {'result': result, 'calendar_http_status': status, 'write_setting': enabled,
            'scope_status': scope_status, 'calendar_role': role}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=Path(__file__).resolve().parents[1] / '.env')
    args = parser.parse_args()
    try:
        result = check(read_settings(args.env_file))
    except FileNotFoundError:
        result = {'result': 'ENV_FILE_NOT_FOUND'}
    except (OSError, ValueError):
        # Exceptions may contain request information. Return a fixed message, never exception text.
        result = {'result': 'CHECK_FAILED', 'next_step': 'Check network access and the Google settings file.'}
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result['result'] == 'READY' else 1


if __name__ == '__main__':
    sys.exit(main())
