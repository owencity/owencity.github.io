import { safeUrl, serverAddress, eventTime, nextEvent, relativeStart, shortDate } from './devdesk-core.mjs';

const $ = id => document.getElementById(id);
let session, filter = 'all', requestNumber = 0;
const feedbackLabels = { INTERESTED: '관심 있어요', NOT_INTERESTED: '관심 없는 주제', KNOWN: '이미 알아요', LATER: '나중에 읽기', READ: '읽었어요' };
const notice = (text, error = false) => { $('message').textContent = text; $('message').classList.toggle('error', error); $('message').hidden = !text; };
const text = (tag, value, className) => { const el = document.createElement(tag); el.textContent = value; if (className) el.className = className; return el; };
function link(label, url) {
  const el = text('a', label); const safe = safeUrl(url);
  if (safe) { el.href = safe; el.target = '_blank'; el.rel = 'noopener noreferrer'; }
  return el;
}
async function api(path, options = {}) {
  const response = await fetch(path, { credentials: 'same-origin', ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(session && options.method && options.method !== 'GET' ? { [session.csrfHeaderName]: session.csrfToken } : {}), ...options.headers } });
  if (response.status === 401 || (response.redirected && new URL(response.url).pathname === '/login')) { location.assign('/login'); throw new Error('로그인이 필요해요.'); }
  if (!response.ok) throw new Error(response.status === 403 ? '로그인이 만료됐거나 접근 권한이 없어요. 새로고침 후 다시 시도해 주세요.' : `요청을 처리하지 못했어요. (${response.status})`);
  if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('DevDesk 서버에 연결해 주세요.');
  return response.json();
}

function renderNews(items) {
  $('news-list').replaceChildren(); $('empty').hidden = items.length > 0; $('feed-count').textContent = `${items.length}개의 소식`;
  for (const item of items) {
    const card = text('article', '', 'news-card');
    const meta = text('p', '', 'news-meta'); meta.append(text('span', item.topic || '개발'), text('span', 'GeekNews'), text('time', shortDate(item.publishedAt || item.collectedAt)));
    const title = text('h3', ''); title.append(link(item.title, item.url));
    const actions = text('div', '', 'feedback-actions'); actions.setAttribute('role', 'group'); actions.setAttribute('aria-label', `${item.title}에 대한 반응`);
    for (const [value, label] of Object.entries(feedbackLabels)) {
      const button = text('button', label); button.type = 'button'; button.setAttribute('aria-pressed', String(item.feedback === value));
      button.addEventListener('click', async () => {
        actions.querySelectorAll('button').forEach(el => el.disabled = true);
        try { await api(`/api/news/${encodeURIComponent(item.id)}/feedback`, { method: 'POST', body: JSON.stringify({ feedback: item.feedback === value ? 'NONE' : value }) }); await loadNews(); notice('반응을 저장했어요. 같은 버튼을 다시 누르면 취소할 수 있어요.'); }
        catch (error) { notice(error.message, true); actions.querySelectorAll('button').forEach(el => el.disabled = false); }
      });
      actions.append(button);
    }
    card.append(meta, title, text('p', item.description || '원문에서 자세한 내용을 확인하세요.', 'description'), actions); $('news-list').append(card);
  }
}
async function loadNews() {
  const current = ++requestNumber;
  const data = await api(`/api/news?filter=${encodeURIComponent(filter)}&q=${encodeURIComponent($('search').value.trim())}`);
  if (current !== requestNumber) return;
  renderNews(data.items || []);
  $('collected-at').textContent = data.lastCollectedAt ? `마지막 수집 ${shortDate(data.lastCollectedAt)}` : '아직 수집하지 않았어요';
  if (data.error) notice('최근 뉴스 수집에 실패했어요. 마지막으로 저장한 소식을 표시합니다.', true);
}
function renderCalendar(data) {
  $('events').replaceChildren();
  if (!data.connected || data.error) {
    $('next-title').textContent = 'Google Calendar에서 오늘을 확인해요';
    $('next-detail').textContent = data.error ? '일정을 불러오지 못했어요.' : '캘린더 연결을 준비하고 있어요.';
    $('calendar-state').textContent = data.error || '연결 전에는 Google Calendar에서 직접 일정을 확인할 수 있어요.';
    return;
  }
  const events = data.events || [];
  $('calendar-state').textContent = events.length ? `${events.length}개의 일정` : '오늘 등록된 일정이 없어요.';
  for (const event of events) { const li = text('li', ''); li.append(text('time', eventTime(event)), link(event.title || '제목 없는 일정', event.url || data.calendarUrl)); $('events').append(li); }
  const next = nextEvent(events);
  $('next-title').textContent = next?.title || '남은 시간은 나의 페이스대로';
  $('next-detail').textContent = next ? `${eventTime(next)} · ${relativeStart(next)}` : '새로운 소식을 읽거나 다음 개발을 계획해 보세요.';
}
async function loadCalendar() { renderCalendar(await api('/api/calendar/today')); }
function connectionScreen(message) {
  $('connect').hidden = false; $('dashboard').hidden = true; notice(message);
  try { $('server-address').value = localStorage.getItem('devdesk-server') || ''; } catch { /* Storage may be unavailable. */ }
}
$('today-label').textContent = new Intl.DateTimeFormat('ko-KR', {timeZone:'Asia/Seoul',month:'long',day:'numeric',weekday:'long'}).format(new Date());
$('connect-form').addEventListener('submit', event => {
  event.preventDefault();
  try { const url = serverAddress($('server-address').value); try { localStorage.setItem('devdesk-server', new URL(url).origin); } catch {} location.assign(url); } catch(error) { notice(error.message, true); }
});
document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', async () => {
  filter = button.dataset.filter; document.querySelectorAll('[data-filter]').forEach(el => el.setAttribute('aria-pressed', String(el === button)));
  try { await loadNews(); } catch(error) { notice(error.message, true); }
}));
let searchTimer; $('search').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => loadNews().catch(error => notice(error.message, true)), 250); });
$('refresh').addEventListener('click', async () => {
  $('refresh').disabled = true; notice('새 소식을 확인하고 있어요.');
  try { const result = await api('/api/news/refresh', {method:'POST'}); await loadNews(); notice(`수집 완료 · 새 글 ${result.imported}개`); } catch(error) { notice(error.message, true); } finally { $('refresh').disabled = false; }
});
async function previewDigest() {
  const data = await api('/api/digest/preview'); $('digest-text').textContent = `${data.subject}\n\n${data.text}`; $('digest-text').hidden = false; $('digest-actions').hidden = false;
  $('digest-state').textContent = data.enabled ? '보내기는 하루에 채널별 한 번만 처리해요. 기기 알림 표시는 앱 설정에 따라 달라요.' : '발송이 꺼져 있어요. 서버에서 알림 설정을 완료하면 사용할 수 있어요.';
  document.querySelectorAll('[data-channel]').forEach(button => { const channel = data.channels.find(c => c.name === button.dataset.channel); button.disabled = !data.enabled || !channel?.configured || ['SENT','UNKNOWN','PROCESSING'].includes(channel.status); button.title = channel?.status || '설정 필요'; });
}
$('preview-digest').addEventListener('click', async () => { try { await previewDigest(); } catch(error) { notice(error.message,true); } });
document.querySelectorAll('[data-channel]').forEach(button => button.addEventListener('click', async () => {
  button.disabled = true;
  try { const data = await api('/api/digest/send', {method:'POST',body:JSON.stringify({channel:button.dataset.channel})}); notice(data.message, data.status !== 'SENT'); await previewDigest(); } catch(error) { notice(error.message,true); }
}));
$('logout').addEventListener('click', () => {
  const form = document.createElement('form'); form.method='POST'; form.action='/logout'; const input = document.createElement('input'); input.type='hidden'; input.name=session.csrfParameterName; input.value=session.csrfToken; form.append(input); document.body.append(form); form.submit();
});

async function init() {
  if (location.hostname.endsWith('.github.io') || location.protocol === 'file:') { connectionScreen('블로그에서 내 DevDesk 서버로 연결할 수 있어요.'); return; }
  try { session = await api('/api/session'); }
  catch(error) { connectionScreen('서버 연결이 필요해요. 실행 중인 DevDesk 주소를 입력해 주세요.'); return; }
  $('dashboard').hidden=false; $('logout').hidden=false; notice('');
  const results = await Promise.allSettled([loadNews(),loadCalendar()]);
  results.filter(r=>r.status==='rejected').forEach(r=>notice(r.reason.message,true));
  setInterval(() => { if (!document.hidden) loadCalendar().catch(error=>notice(error.message,true)); },60000);
  setInterval(() => { if (!document.hidden) loadNews().catch(error=>notice(error.message,true)); },300000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) Promise.allSettled([loadNews(), loadCalendar()]).then(results => results.filter(r=>r.status==='rejected').forEach(r=>notice(r.reason.message,true)));
  });
}
init();
