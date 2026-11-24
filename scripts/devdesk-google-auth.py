"""Owner-only local OAuth setup. No tokens are printed; run on your own computer."""
import argparse
import base64
import hashlib
import http.server
import json
import os
import secrets
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

READ_SCOPE = 'https://www.googleapis.com/auth/calendar.events.readonly'
WRITE_SCOPE = 'https://www.googleapis.com/auth/calendar.events.owned'
TASKS_SCOPE = 'https://www.googleapis.com/auth/tasks'


def requested_scope(access):
    scopes = {'read': READ_SCOPE, 'write': WRITE_SCOPE, 'tasks': TASKS_SCOPE}
    if access not in scopes:
        raise ValueError('Google access must be read, write or tasks.')
    return scopes[access]


def verify_granted_scope(tokens, access):
    # OAuth can omit scope when it is identical to the requested scope.
    if 'scope' not in tokens:
        return
    granted = set(tokens['scope'].split())
    accepted = {requested_scope(access)}
    if access != 'tasks':
        accepted.update({'https://www.googleapis.com/auth/calendar', 'https://www.googleapis.com/auth/calendar.events'})
    if not granted.intersection(accepted):
        raise RuntimeError('The required Google permission was not granted. No settings were saved.')


def save_settings(output, client, tokens, access):
    verify_granted_scope(tokens, access)
    token = tokens.get('refresh_token')
    if not token:
        raise RuntimeError('Google did not return a refresh token. Reauthorize offline access.')
    values = {'GOOGLE_CLIENT_ID': client['client_id'],
              'GOOGLE_CLIENT_SECRET': client['client_secret'],
              'GOOGLE_REFRESH_TOKEN': token,
              'GOOGLE_CALENDAR_WRITE_ENABLED': 'true' if access == 'write' else 'false'}
    if access == 'tasks':
        values = {'GOOGLE_TASKS_REFRESH_TOKEN': token}
    # Single quotes prevent Compose from expanding dollar signs in values.
    if any(not isinstance(value, str) or any(c in value for c in "\r\n'") for value in values.values()):
        raise RuntimeError('Unexpected credential format. Nothing was saved.')
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as file:
        file.write('\n'.join(f"{name}='{value}'" for name, value in values.items()) + '\n')


def check_install_target(path, client):
    with path.open(encoding='utf-8', newline='') as file:
        contents = file.read()
    expected = {'GOOGLE_CLIENT_ID': client['client_id'], 'GOOGLE_CLIENT_SECRET': client['client_secret']}
    settings = {}
    for line in contents.splitlines():
        key, separator, value = line.partition('=')
        if separator and key in expected:
            if key in settings:
                raise RuntimeError('Duplicate Google settings in .env.')
            settings[key] = value.strip().strip("'").strip('"')
    if settings != expected:
        raise RuntimeError('That OAuth file does not match the Google client in .env.')
    return contents


def install_tasks(path, client, tokens):
    verify_granted_scope(tokens, 'tasks')
    token = tokens.get('refresh_token')
    if not isinstance(token, str) or not token or any(char in token for char in "\r\n'"):
        raise RuntimeError('Google did not return a usable Tasks refresh token.')
    contents = check_install_target(path, client)
    prefix = 'GOOGLE_TASKS_REFRESH_TOKEN='
    rows = contents.splitlines(keepends=True)
    matches = [i for i, row in enumerate(rows) if row.startswith(prefix)]
    if len(matches) > 1:
        raise RuntimeError('Duplicate Tasks refresh token settings in .env.')
    newline = '\r\n' if '\r\n' in contents else '\n'
    assignment = f"{prefix}'{token}'{newline}"
    if matches:
        rows[matches[0]] = assignment
        updated = ''.join(rows)
    else:
        updated = contents + ('' if contents.endswith('\n') else newline) + assignment
    temp = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='', prefix='.env.', dir=path.parent,
                                         delete=False) as file:
            temp = Path(file.name)
            file.write(updated)
        if check_install_target(path, client) != contents:
            raise RuntimeError('.env changed during setup. Retry after saving your edits.')
        os.replace(temp, path)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('.env.google'))
    parser.add_argument('--access', choices=('read', 'write', 'tasks'), default='read',
                        help='read: list calendar events; write: manage calendar events; tasks: manage Google Tasks')
    parser.add_argument('--install-tasks', type=Path, metavar='ENV_FILE',
                        help='save the Tasks token into the existing matching DevDesk .env')
    args = parser.parse_args()
    if args.install_tasks and args.access != 'tasks':
        parser.error('--install-tasks requires --access tasks.')
    if not args.install_tasks and args.output.exists():
        parser.error('Output already exists; select another private path to avoid overwriting it.')
    client = json.loads(args.credentials.read_text(encoding='utf-8')).get('installed')
    if not client or not client.get('client_id') or not client.get('client_secret'):
        parser.error('A Desktop app OAuth client JSON is required.')
    if args.install_tasks:
        check_install_target(args.install_tasks, client)
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass  # Callback URLs contain authorization codes.

        def do_GET(self):
            request = urllib.parse.urlsplit(self.path)
            if request.path != '/callback':
                self.send_error(404)
                return
            params = urllib.parse.parse_qs(request.query)
            if not secrets.compare_digest(params.get('state', [''])[0], state):
                self.send_error(400, 'Invalid state')
                return
            result['code'] = params.get('code', [''])[0]
            result['error'] = params.get('error', [''])[0]
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write('승인을 확인했어요. 터미널에서 완료 여부를 확인하고 이 창을 닫아 주세요.'.encode())

    with http.server.HTTPServer(('127.0.0.1', 0), Handler) as server:
        server.timeout = 1
        redirect = f'http://127.0.0.1:{server.server_port}/callback'
        params = {
            'client_id': client['client_id'], 'redirect_uri': redirect,
            'response_type': 'code', 'scope': requested_scope(args.access),
            'state': state, 'access_type': 'offline', 'prompt': 'consent',
            'code_challenge': challenge, 'code_challenge_method': 'S256',
        }
        url = 'https://accounts.google.com/o/oauth2/v2/auth?' + urllib.parse.urlencode(params)
        access_label = {'read': 'read Calendar events', 'write': 'edit events on calendars you own',
                        'tasks': 'manage Google Tasks'}[args.access]
        print(f'Opening Google authorization in your browser ({access_label}).')
        if not webbrowser.open(url):
            raise RuntimeError('Could not open the default browser. Run this on a desktop with a browser.')
        deadline = time.monotonic() + 300
        while not result and time.monotonic() < deadline:
            server.handle_request()
    if not result.get('code'):
        raise RuntimeError('Authorization was declined or timed out. No credentials were saved.')
    request = urllib.request.Request('https://oauth2.googleapis.com/token', data=urllib.parse.urlencode({
        'code': result['code'], 'client_id': client['client_id'], 'client_secret': client['client_secret'],
        'redirect_uri': redirect, 'grant_type': 'authorization_code', 'code_verifier': verifier,
    }).encode(), headers={'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            tokens = json.load(response)
    except urllib.error.URLError:
        raise RuntimeError('Google token exchange failed. Check the client configuration and retry.') from None
    if args.install_tasks:
        install_tasks(args.install_tasks, client, tokens)
        print('Google Tasks connection saved in .env.')
    else:
        save_settings(args.output, client, tokens, args.access)
        print(f'Saved private settings to {args.output}. Copy them into the server .env without sharing them.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        raise SystemExit(str(error)) from None
