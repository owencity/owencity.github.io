"""Check the local CI container's login, dashboard and disconnected integrations."""
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser


class LoginForm(HTMLParser):
    csrf = None

    def handle_starttag(self, tag, attrs):
        fields = dict(attrs)
        if tag == 'input' and fields.get('name') == '_csrf':
            self.csrf = fields.get('value')


def main():
    server = 'http://127.0.0.1:18080'
    browser = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
    )

    def read(path):
        with browser.open(server + path, timeout=5) as response:
            return response.read()

    try:
        read('/api/tasks/board')
    except urllib.error.HTTPError as failure:
        assert failure.code == 401, failure.code
    else:
        raise AssertionError('Private tasks endpoint allowed an anonymous read')

    login = LoginForm()
    login.feed(read('/login').decode('utf-8'))
    assert login.csrf, 'Login CSRF field missing'
    body = urllib.parse.urlencode({
        'username': 'owner', 'password': 'devdesk-ci-owner-only', '_csrf': login.csrf,
    }).encode()
    with browser.open(server + '/login', body, timeout=5) as response:
        assert response.url == server + '/devdesk.html', 'Login did not reach the dashboard'
        assert b'id="tasks-board"' in response.read(), 'Planner is missing from the dashboard'

    assert b'function renderTasks' in read('/devdesk.js'), 'Planner script is missing'
    assert json.loads(read('/api/session'))['csrfToken'], 'Session CSRF token missing'
    board = json.loads(read('/api/tasks/board'))
    assert board == {'connected': False, 'writeEnabled': False, 'lists': []}, board
    assert json.loads(read('/api/calendar/events'))['connected'] is False
    assert json.loads(read('/api/summary/status'))['enabled'] is False
    print('DevDesk runtime smoke passed')


if __name__ == '__main__':
    main()
