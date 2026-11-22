import { safeUrl, serverAddress, eventTime, nextEvent, relativeStart, shortDate, seoulDate, shiftCalendarDate, seoulDateTime, calendarInputValues, calendarEventPayload, calendarEventRange, calendarLookupConfirmed, summaryState, summaryAvailability, summaryModelUsage, summarySource, shouldPollSummary } from './devdesk-core.mjs';

const $ = id => document.getElementById(id);
let session, filter = 'all', requestNumber = 0;
let summaryService;
let calendarDate = seoulDate(), calendarLoadSequence = 0, calendarData, calendarEditor, calendarDelete, calendarBusy = false;
const summaryPanels = new Map(), summaryCache = new Map(), openSummaries = new Set(), summaryDeadlines = new Map();
const SUMMARY_POLL_MS = 5000, SUMMARY_POLL_WINDOW_MS = 120000;
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
  if (!response.ok) {
    let problem;
    if (response.headers.get('content-type')?.includes('application/json')) { try { problem = await response.json(); } catch {} }
    const message = typeof problem?.message === 'string' && problem.message.trim() ? problem.message
      : response.status === 403 ? '로그인이 만료됐거나 접근 권한이 없어요. 새로고침 후 다시 시도해 주세요.' : `요청을 처리하지 못했어요. (${response.status})`;
    const error = new Error(message); error.status = response.status; error.code = problem?.code; throw error;
  }
  if (response.status === 204) return null;
  if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('DevDesk 서버에 연결해 주세요.');
  return response.json();
}

function renderNews(items) {
  for (const panel of summaryPanels.values()) stopSummaryPoll(panel);
  summaryPanels.clear();
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
    card.append(meta, title, text('p', item.description || '원문에서 자세한 내용을 확인하세요.', 'description'), actions, createSummaryPanel(item)); $('news-list').append(card);
  }
}

function stopSummaryPoll(panel) {
  clearTimeout(panel.timer);
  panel.timer = null;
  if (panel.controller) panel.controller.abort();
  panel.controller = null;
  panel.version++;
}

function summaryCanRun() {
  return summaryAvailability(summaryService) === 'AVAILABLE';
}

function summaryUnavailableReason() {
  const availability = summaryAvailability(summaryService);
  if (availability === 'DISABLED')
    return 'AI 정리를 사용하려면 서버의 Gemini 연결 설정과 사용 여부를 확인해 주세요. 저장된 정리는 계속 읽을 수 있어요.';
  if (availability === 'MODEL_WAIT')
    return '현재 모든 사용 가능한 모델이 잠시 대기 중이에요. 저장된 정리는 계속 읽을 수 있어요.';
  if (availability === 'GLOBAL_WAIT' || availability === 'CALLS_PAUSED')
    return '현재 추가 AI 호출을 진행할 수 없어요. 잠시 후 호출 상태를 다시 확인해 주세요. 저장된 정리는 계속 읽을 수 있어요.';
  if (availability === 'MODEL_LIMIT')
    return '모든 모델의 앱 일일 호출 예산을 사용했어요. 저장된 정리는 계속 읽을 수 있어요.';
  return '앱에 설정한 오늘의 AI 호출 예산을 사용했어요. 저장된 정리는 계속 읽을 수 있어요.';
}

async function loadSummaryStatus() {
  try {
    summaryService = await api('/api/summary/status');
    const remaining = Number.isFinite(summaryService.remainingToday) ? ` · 앱 전체 오늘 남은 호출 예산 ${summaryService.remainingToday}회` : '';
    const state = $('summary-service-state');
    state.textContent = summaryCanRun()
      ? `AI 내용 정리 사용 가능${remaining}. 저장된 정리를 읽을 때는 AI를 다시 호출하지 않아요.`
      : summaryUnavailableReason();
    for (const line of summaryModelUsage(summaryService)) state.append(document.createElement('br'), document.createTextNode(line));
    for (const panel of summaryPanels.values()) {
      const cached = summaryCache.get(panel.id);
      if (cached && panel.details.open && !panel.submitting && !panel.unconfirmed) renderSummary(panel, cached);
    }
  } catch (error) {
    $('summary-service-state').textContent = 'AI 연결 상태를 확인하지 못했어요. 뉴스의 내용 정리를 열어 저장된 결과를 확인할 수 있어요.';
  }
}

function createSummaryPanel(item) {
  const id = String(item.id);
  const details = document.createElement('details'); details.className = 'news-summary';
  const toggle = text('summary', '내용 정리 읽기'); toggle.setAttribute('aria-label', `${item.title} 내용 정리 읽기`);
  const body = text('div', '', 'summary-body');
  details.append(toggle, body);
  const panel = { id, details, body, timer: null, controller: null, version: 0, submitting: false, unconfirmed: false };
  summaryPanels.set(id, panel);
  if (summaryCache.has(id)) renderSummary(panel, summaryCache.get(id));
  details.addEventListener('toggle', () => {
    if (!details.open) { openSummaries.delete(id); stopSummaryPoll(panel); return; }
    openSummaries.add(id);
    if (!summaryDeadlines.has(id)) summaryDeadlines.set(id, Date.now() + SUMMARY_POLL_WINDOW_MS);
    if (!document.hidden) loadSummary(panel);
  });
  details.open = openSummaries.has(id);
  return details;
}

function appendSummaryList(container, heading, values, className) {
  if (!Array.isArray(values)) return;
  const entries = values.filter(value => typeof value === 'string' && value.trim());
  if (!entries.length) return;
  const section = text('section', '', className || 'summary-section');
  section.append(text('h4', heading));
  const list = document.createElement('ul');
  entries.forEach(value => list.append(text('li', value)));
  section.append(list); container.append(section);
}

function renderSummary(panel, data) {
  const state = summaryState(data.status, data.canRetry);
  panel.body.replaceChildren();
  const status = text('p', state.message, 'summary-state'); status.setAttribute('role', 'status');
  panel.body.append(status);
  if (state.success) {
    const meta = text('div', '', 'summary-meta'); meta.append(text('span', summarySource(data.sourceScope), 'summary-source'));
    if (data.generatedAt) meta.append(text('time', `${shortDate(data.generatedAt)} 정리`));
    panel.body.append(meta);
    if (data.sourceWarning) panel.body.append(text('p', data.sourceWarning, 'summary-warning'));
    let rendered = 0;
    for (const section of Array.isArray(data.sections) ? data.sections : []) {
      const paragraphs = Array.isArray(section?.paragraphs) ? section.paragraphs.filter(value => typeof value === 'string' && value.trim()) : [];
      if (!paragraphs.length) continue;
      const block = text('section', '', 'summary-section');
      if (typeof section.heading === 'string' && section.heading.trim()) block.append(text('h4', section.heading));
      paragraphs.forEach(paragraph => block.append(text('p', paragraph)));
      panel.body.append(block); rendered++;
    }
    if (!rendered) panel.body.append(text('p', '저장된 정리 본문을 확인할 수 없어요. 원문을 확인해 주세요.', 'summary-warning'));
    appendSummaryList(panel.body, '개발자 관점 · AI 해석', data.developerNotes, 'summary-perspective');
    appendSummaryList(panel.body, '확인할 조건과 한계', data.caveats);
    const source = text('div', '', 'summary-footer');
    if (safeUrl(data.sourceUrl)) source.append(link('정리에 사용한 자료 열기', data.sourceUrl));
    if (typeof data.model === 'string' && data.model) source.append(text('span', `AI 모델 · ${data.model}`));
    panel.body.append(source);
    return;
  }
  if (typeof data.error === 'string' && data.error) panel.body.append(text('p', data.error, 'summary-warning'));
  const actions = text('div', '', 'summary-actions');
  if (state.canRequest) {
    const request = text('button', state.requestLabel); request.type = 'button'; request.className = 'primary';
    request.disabled = panel.submitting || !summaryCanRun();
    request.addEventListener('click', () => requestSummary(panel)); actions.append(request);
    if (!summaryCanRun()) panel.body.append(text('p', summaryUnavailableReason(), 'muted'));
  }
  const check = text('button', '상태 다시 확인'); check.type = 'button'; check.disabled = panel.submitting;
  check.addEventListener('click', () => {
    summaryDeadlines.set(panel.id, Date.now() + SUMMARY_POLL_WINDOW_MS);
    loadSummary(panel); loadSummaryStatus();
  }); actions.append(check); panel.body.append(actions);
  if (state.pending && Date.now() >= (summaryDeadlines.get(panel.id) || 0))
    panel.body.append(text('p', '자동 확인을 잠시 멈췄어요. 서버의 처리 결과는 나중에 다시 확인할 수 있어요.', 'muted'));
}

async function loadSummary(panel) {
  if (panel.submitting || !panel.details.open || !panel.details.isConnected || document.hidden) return;
  stopSummaryPoll(panel);
  const version = panel.version;
  const controller = new AbortController(); panel.controller = controller;
  if (!summaryCache.has(panel.id)) panel.body.replaceChildren(text('p', '저장된 내용 정리를 확인하고 있어요.', 'summary-state'));
  try {
    const data = await api(`/api/news/${encodeURIComponent(panel.id)}/summary`, { signal: controller.signal });
    if (version !== panel.version) return;
    const wasPending = summaryState(summaryCache.get(panel.id)?.status).pending;
    panel.unconfirmed = false; summaryCache.set(panel.id, data); renderSummary(panel, data);
    if (wasPending && !summaryState(data.status).pending && !document.hidden) loadSummaryStatus();
    if (shouldPollSummary({ status: data.status, expanded: panel.details.open, visible: !document.hidden,
      connected: panel.details.isConnected, now: Date.now(), deadline: summaryDeadlines.get(panel.id) || 0 }))
      panel.timer = setTimeout(() => loadSummary(panel), SUMMARY_POLL_MS);
  } catch (error) {
    if (error.name === 'AbortError' || version !== panel.version) return;
    panel.body.replaceChildren(text('p', '정리 내용을 불러오지 못했어요. 아래 버튼으로 다시 확인해 주세요.', 'summary-warning'));
    const retry = text('button', '다시 확인'); retry.type = 'button';
    retry.addEventListener('click', () => loadSummary(panel)); panel.body.append(retry);
  } finally {
    if (panel.controller === controller) panel.controller = null;
  }
}

async function requestSummary(panel) {
  if (panel.submitting || !summaryCanRun()) return;
  stopSummaryPoll(panel); panel.submitting = true;
  panel.body.querySelectorAll('button').forEach(button => button.disabled = true);
  let returned;
  try {
    const data = await api(`/api/news/${encodeURIComponent(panel.id)}/summary`, { method: 'POST' });
    returned = data;
    panel.unconfirmed = false;
    summaryCache.set(panel.id, data); summaryDeadlines.set(panel.id, Date.now() + SUMMARY_POLL_WINDOW_MS);
    if (panel.details.isConnected) renderSummary(panel, data);
  } catch (error) {
    // A timeout may follow an accepted request. Read the server state before requesting again.
    panel.unconfirmed = true;
    if (panel.details.isConnected) {
      panel.body.replaceChildren(text('p', '요청 결과를 확인하지 못했어요. 중복 요청하기 전에 상태를 다시 확인해 주세요.', 'summary-warning'));
      const check = text('button', '상태 확인'); check.type = 'button'; check.addEventListener('click', () => loadSummary(panel)); panel.body.append(check);
    }
  } finally {
    panel.submitting = false;
    if (returned && panel.details.isConnected) renderSummary(panel, returned);
    if (summaryState(summaryCache.get(panel.id)?.status).pending) loadSummary(panel);
    if (!document.hidden) loadSummaryStatus();
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
function calendarWritable() { return Boolean(calendarData?.connected && calendarData?.writeEnabled && !calendarData?.error); }

function calendarMessage(id, message, error = false) {
  const element = $(id); element.textContent = message; element.hidden = !message; element.classList.toggle('error', error);
}

function renderNextCalendar(data) {
  if (!data?.connected || data.error) {
    $('next-title').textContent = 'Google Calendar에서 오늘을 확인해요';
    $('next-detail').textContent = data?.error || '캘린더 연결을 준비하고 있어요.';
    return;
  }
  const next = nextEvent(data.events || []);
  $('next-title').textContent = next?.title || '남은 시간은 나의 페이스대로';
  $('next-detail').textContent = next ? `${eventTime(next)} · ${relativeStart(next)}` : '새로운 소식을 읽거나 다음 개발을 계획해 보세요.';
}

function renderCalendar(data) {
  calendarData = data;
  $('calendar-date').value = calendarDate;
  $('agenda-title').textContent = calendarDate === seoulDate() ? '오늘 일정' : `${calendarDate} 일정`;
  $('calendar-add').disabled = !calendarWritable() || calendarBusy;
  $('calendar-write-state').textContent = calendarWritable() ? '일정과 알림을 여기서 관리할 수 있어요.'
    : data?.connected ? '현재 조회만 가능해요. 일정을 편집하려면 Google Calendar 수정 권한으로 다시 연결하고 서버에서 편집을 켜 주세요.' : '';
  updateCalendarEditorState();
  $('events').replaceChildren();
  if (!data?.connected || data.error) {
    $('calendar-state').textContent = data?.error || '연결 전에는 Google Calendar에서 직접 일정을 확인할 수 있어요.';
    return;
  }
  const events = data.events || [];
  $('calendar-state').textContent = events.length ? `${events.length}개의 일정` : '선택한 날짜에 등록된 일정이 없어요.';
  for (const event of events) {
    const li = text('li', '', 'calendar-event');
    const detail = text('div', '', 'calendar-event-detail');
    let range; try { range = calendarEventRange(event); } catch { range = '시간 확인 필요'; }
    detail.append(text('time', range), link(event.title || '제목 없는 일정', event.url || data.calendarUrl));
    if (event.location) detail.append(text('span', event.location, 'calendar-event-location'));
    if (event.recurringEventId) detail.append(text('span', '반복 일정 · 이번 일정', 'calendar-event-repeat'));
    li.append(detail);
    if (calendarWritable() && event.editable && event.etag) {
      const actions = text('div', '', 'calendar-event-actions');
      const edit = text('button', '수정'); edit.type = 'button'; edit.disabled = calendarBusy;
      edit.setAttribute('aria-label', `${event.title || '일정'} 수정`); edit.addEventListener('click', () => openCalendarEditor(event));
      const remove = text('button', '삭제'); remove.type = 'button'; remove.disabled = calendarBusy;
      remove.setAttribute('aria-label', `${event.title || '일정'} 삭제`); remove.addEventListener('click', () => confirmCalendarDelete(event));
      actions.append(edit, remove); li.append(actions);
    } else if (calendarWritable()) detail.append(text('span', 'Google Calendar에서 관리', 'calendar-event-repeat'));
    $('events').append(li);
  }
}

async function loadCalendar() {
  const selected = calendarDate, today = seoulDate(), sequence = ++calendarLoadSequence;
  if (calendarEditor) { calendarEditor.checkedAfterUnknown = false; calendarEditor.checkedLookup = null; updateCalendarEditorState(); }
  $('calendar-state').textContent = '일정을 확인하고 있어요.';
  const [agenda, focus] = await Promise.allSettled([
    api(`/api/calendar/events?date=${encodeURIComponent(selected)}`),
    selected === today ? Promise.resolve(null) : api('/api/calendar/today')
  ]);
  if (sequence !== calendarLoadSequence || selected !== calendarDate) return null;
  if (agenda.status === 'fulfilled') {
    renderCalendar(agenda.value);
    if (selected === today) renderNextCalendar(agenda.value);
  } else {
    renderCalendar({ connected: false, events: [], writeEnabled: false, error: agenda.reason.message });
    if (selected === today) renderNextCalendar({ connected: false, error: agenda.reason.message });
  }
  if (selected !== today) renderNextCalendar(focus.status === 'fulfilled' ? focus.value : { connected: false, error: focus.reason.message });
  return agenda.status === 'fulfilled' ? { sequence, date: selected, data: agenda.value } : null;
}

function calendarFields() {
  return { title: $('event-title').value, description: $('event-description').value, location: $('event-location').value,
    allDay: $('event-all-day').checked, start: $('event-start').value, end: $('event-end').value,
    reminderMode: $('event-reminder').value, reminderMinutes: $('event-reminder-minutes').value,
    repeat: $('event-repeat').value, repeatCount: $('event-repeat-count').value };
}

function updateCalendarOptions() {
  const allDay = $('event-all-day').checked;
  $('event-start-label').textContent = allDay ? '시작 날짜' : '시작';
  $('event-end-label').textContent = allDay ? '마지막 날짜 (이 날짜 포함)' : '종료';
  $('event-reminder-minutes-group').hidden = $('event-reminder').value !== 'POPUP';
  $('event-reminder-minutes').required = $('event-reminder').value === 'POPUP';
  $('event-repeat-count-group').hidden = $('event-repeat').value === 'NONE';
  $('event-repeat-count').required = calendarEditor?.mode === 'create' && $('event-repeat').value !== 'NONE';
}

function updateCalendarEditorState() {
  if (!calendarEditor) return;
  const locked = calendarBusy || calendarEditor.uncertain || calendarEditor.stale;
  $('calendar-fields').disabled = locked;
  $('calendar-save').disabled = calendarBusy || calendarEditor.stale || !calendarWritable();
  $('calendar-save').textContent = calendarBusy ? '저장하고 있어요…'
    : calendarEditor.uncertain ? '같은 요청 다시 확인' : calendarEditor.mode === 'edit' ? '변경 저장' : '일정 저장';
  $('calendar-form-refresh').hidden = !calendarEditor.uncertain && !calendarEditor.stale;
  $('calendar-form-reset').hidden = !(calendarEditor.uncertain || calendarEditor.stale) || calendarEditor.mode !== 'create';
  $('calendar-form-reset').disabled = calendarBusy || !calendarEditor.checkedAfterUnknown || !calendarWritable();
  $('calendar-editor-close').disabled = calendarBusy;
}

function openCalendarEditor(event = null, refresh = false) {
  if (calendarBusy || !calendarWritable()) return;
  if (event && (!event.editable || !event.etag)) return;
  if (!refresh && (calendarEditor?.uncertain || (calendarEditor?.stale && calendarEditor.mode === 'create'))) {
    $('calendar-editor').hidden = false; $('calendar-editor').scrollIntoView({ behavior: 'smooth', block: 'start' });
    calendarMessage('calendar-form-message', '먼저 이전 저장 요청의 결과를 확인해 주세요. 같은 요청을 다시 확인해도 새 일정으로 중복 등록하지 않아요.', true); return;
  }
  if (!refresh && calendarEditor && $('calendar-form').dataset.dirty === 'true'
    && !window.confirm('작성 중인 내용을 버리고 다른 일정을 열까요?')) return;
  const values = calendarInputValues(event, calendarDate);
  calendarEditor = { mode: event ? 'edit' : 'create', event, initialValues: values,
    requestId: event ? null : crypto.randomUUID(), payload: null, uncertain: false, stale: false, checkedAfterUnknown: false, checkedLookup: null };
  $('calendar-form').dataset.dirty = 'false';
  $('calendar-editor-title').textContent = event ? '일정 수정' : '일정 추가';
  $('calendar-occurrence-note').hidden = !event?.recurringEventId;
  $('event-title').value = values.title; $('event-location').value = values.location; $('event-description').value = values.description;
  $('event-all-day').checked = values.allDay;
  $('event-start').type = values.allDay ? 'date' : 'datetime-local'; $('event-end').type = values.allDay ? 'date' : 'datetime-local';
  $('event-start').value = values.start; $('event-end').value = values.end;
  $('event-reminder-keep').hidden = !event; $('event-reminder-keep').disabled = !event;
  $('event-reminder-keep').textContent = event?.reminderMode === 'OTHER' ? '기존 알림 유지 (별도 설정 있음)' : '기존 알림 유지';
  $('event-reminder').value = values.reminderMode; $('event-reminder-minutes').value = values.reminderMinutes;
  $('event-repeat').value = values.repeat; $('event-repeat-count').value = values.repeatCount;
  $('event-repeat-group').hidden = Boolean(event);
  $('calendar-delete-confirm').hidden = true; calendarDelete = null;
  calendarMessage('calendar-form-message', ''); updateCalendarOptions(); updateCalendarEditorState();
  $('calendar-editor').hidden = false; $('calendar-editor').scrollIntoView({ behavior: 'smooth', block: 'start' }); $('event-title').focus({ preventScroll: true });
}

function setCalendarBusy(value) {
  calendarBusy = value;
  for (const id of ['calendar-date', 'calendar-prev', 'calendar-next', 'calendar-today', 'calendar-form-refresh', 'calendar-form-reset', 'calendar-delete-cancel', 'calendar-delete-refresh']) $(id).disabled = value;
  if (calendarData) renderCalendar(calendarData);
  updateCalendarEditorState();
}

function isUncertainCalendarError(error) { return !Number.isInteger(error.status) || error.status >= 500; }

async function saveCalendarEvent(event) {
  event.preventDefault();
  if (!calendarEditor || calendarBusy || calendarEditor.stale || !calendarWritable()) return;
  const editor = calendarEditor;
  editor.checkedAfterUnknown = false; editor.checkedLookup = null;
  let payload;
  try {
    const fields = calendarFields();
    payload = editor.uncertain ? editor.payload : calendarEventPayload(fields, editor.mode === 'edit');
    if (!editor.uncertain && editor.mode === 'edit' && fields.allDay === editor.initialValues.allDay) {
      // Keep seconds/offsets from existing events when the user did not change the displayed time.
      if (fields.start === editor.initialValues.start) payload.start = editor.event.start;
      if (fields.end === editor.initialValues.end) payload.end = editor.event.end;
    }
    if (editor.mode === 'create') payload = { ...payload, requestId: editor.requestId };
  } catch (error) { calendarMessage('calendar-form-message', error.message, true); return; }
  editor.payload = payload; calendarMessage('calendar-form-message', 'Google Calendar에 저장하고 있어요.'); setCalendarBusy(true);
  try {
    const path = editor.mode === 'create' ? '/api/calendar/events' : `/api/calendar/events/${encodeURIComponent(editor.event.id)}?scope=occurrence`;
    await api(path, { method: editor.mode === 'create' ? 'POST' : 'PUT', body: JSON.stringify(payload),
      headers: editor.mode === 'edit' ? { 'If-Match': editor.event.etag } : {} });
    calendarDate = payload.allDay ? payload.start : seoulDate(payload.start); calendarEditor = null;
    $('calendar-editor').hidden = true; $('calendar-form').dataset.dirty = 'false'; notice('일정을 저장했어요.');
    await loadCalendar();
  } catch (error) {
    if (error.status === 409 || error.status === 412) {
      editor.stale = true;
      calendarMessage('calendar-form-message', `${error.message} 최신 일정을 다시 불러온 뒤 수정해 주세요.`, true);
    } else if (isUncertainCalendarError(error)) {
      editor.uncertain = true;
      calendarMessage('calendar-form-message', `${error.message} 저장됐을 수 있어요. 일정을 확인하거나, 입력을 바꾸지 않고 같은 요청의 결과를 다시 확인해 주세요.`, true);
    } else { editor.uncertain = false; editor.payload = null; calendarMessage('calendar-form-message', error.message, true); }
  } finally { setCalendarBusy(false); }
}

function confirmCalendarDelete(event) {
  if (calendarBusy || !calendarWritable() || !event.editable || !event.etag) return;
  calendarDelete = { event, stale: false };
  const date = event.allDay ? event.start : seoulDate(event.start);
  $('calendar-delete-description').textContent = `“${event.title || '제목 없는 일정'}” · ${date} · ${calendarEventRange(event)}${event.recurringEventId ? ' — 반복 일정 중 이번 일정만 삭제돼요.' : ' — Google Calendar에서도 삭제돼요.'}`;
  $('calendar-delete-submit').textContent = event.recurringEventId ? '이번 일정만 삭제' : '이 일정 삭제';
  $('calendar-delete-submit').disabled = false; $('calendar-delete-submit').hidden = false; $('calendar-delete-refresh').hidden = true;
  calendarMessage('calendar-delete-message', ''); $('calendar-delete-confirm').hidden = false;
  $('calendar-delete-confirm').scrollIntoView({ behavior: 'smooth', block: 'start' }); $('calendar-delete-cancel').focus({ preventScroll: true });
}

async function deleteCalendarEvent() {
  if (!calendarDelete || calendarDelete.stale || calendarBusy || !calendarWritable()) return;
  const deletion = calendarDelete; setCalendarBusy(true); $('calendar-delete-submit').disabled = true;
  calendarMessage('calendar-delete-message', 'Google Calendar에서 삭제하고 있어요.');
  try {
    await api(`/api/calendar/events/${encodeURIComponent(deletion.event.id)}?scope=occurrence`, { method: 'DELETE', headers: { 'If-Match': deletion.event.etag } });
    if (calendarEditor?.event?.id === deletion.event.id) { calendarEditor = null; $('calendar-editor').hidden = true; }
    calendarDelete = null; $('calendar-delete-confirm').hidden = true; notice('일정을 삭제했어요.'); await loadCalendar();
  } catch (error) {
    deletion.stale = true; $('calendar-delete-submit').hidden = true; $('calendar-delete-refresh').hidden = false;
    const detail = isUncertainCalendarError(error) ? '삭제됐을 수 있으니 현재 일정을 다시 확인해 주세요.' : '최신 일정을 다시 확인한 뒤 삭제해 주세요.';
    calendarMessage('calendar-delete-message', `${error.message} ${detail}`, true);
  } finally { setCalendarBusy(false); }
}
function connectionScreen(message) {
  $('connect').hidden = false; $('dashboard').hidden = true; notice(message);
  try { $('server-address').value = localStorage.getItem('devdesk-server') || ''; } catch { /* Storage may be unavailable. */ }
}
$('today-label').textContent = new Intl.DateTimeFormat('ko-KR', {timeZone:'Asia/Seoul',month:'long',day:'numeric',weekday:'long'}).format(new Date());
$('connect-form').addEventListener('submit', event => {
  event.preventDefault();
  try { const url = serverAddress($('server-address').value); try { localStorage.setItem('devdesk-server', new URL(url).origin); } catch {} location.assign(url); } catch(error) { notice(error.message, true); }
});
async function selectCalendarDate(date) {
  if (calendarBusy) return;
  try { calendarDate = shiftCalendarDate(date, 0); }
  catch (error) { $('calendar-date').value = calendarDate; notice(error.message, true); return; }
  calendarData = null; $('calendar-add').disabled = true;
  $('events').replaceChildren(); $('calendar-write-state').textContent = '';
  $('calendar-date').value = calendarDate; await loadCalendar();
}
$('calendar-date').value = calendarDate;
$('calendar-date').addEventListener('change', () => selectCalendarDate($('calendar-date').value));
$('calendar-prev').addEventListener('click', () => selectCalendarDate(shiftCalendarDate(calendarDate, -1)));
$('calendar-next').addEventListener('click', () => selectCalendarDate(shiftCalendarDate(calendarDate, 1)));
$('calendar-today').addEventListener('click', () => selectCalendarDate(seoulDate()));
$('calendar-add').addEventListener('click', () => openCalendarEditor());
$('calendar-form').addEventListener('submit', saveCalendarEvent);
$('calendar-form').addEventListener('input', () => { $('calendar-form').dataset.dirty = 'true'; });
$('calendar-editor-close').addEventListener('click', () => { if (!calendarBusy) $('calendar-editor').hidden = true; });
$('event-reminder').addEventListener('change', updateCalendarOptions);
$('event-repeat').addEventListener('change', updateCalendarOptions);
$('event-all-day').addEventListener('change', () => {
  const allDay = $('event-all-day').checked;
  const start = $('event-start').value, end = $('event-end').value;
  $('event-start').type = allDay ? 'date' : 'datetime-local'; $('event-end').type = allDay ? 'date' : 'datetime-local';
  $('event-start').value = allDay ? start.slice(0, 10) : `${start || calendarDate}T09:00`;
  $('event-end').value = allDay ? end.slice(0, 10) : `${end || calendarDate}T10:00`;
  updateCalendarOptions();
});
$('calendar-form-refresh').addEventListener('click', async () => {
  if (!calendarEditor || calendarBusy) return;
  const editor = calendarEditor;
  const submittedDate = editor.payload ? editor.payload.allDay ? editor.payload.start : seoulDate(editor.payload.start) : calendarDate;
  calendarDate = submittedDate;
  const lookup = await loadCalendar();
  if (editor !== calendarEditor) return;
  const confirmed = calendarLookupConfirmed(lookup, submittedDate, calendarDate, calendarLoadSequence);
  if (editor.mode === 'create') {
    editor.checkedAfterUnknown = confirmed;
    editor.checkedLookup = confirmed ? lookup : null;
    calendarMessage('calendar-form-message', editor.checkedAfterUnknown
      ? editor.stale ? '요청한 날짜의 목록에서 기존 일정을 확인해 주세요. 충돌한 요청은 다시 저장할 수 없어요. 확인을 마친 후 새로 작성할 수 있어요.'
        : '요청한 날짜의 목록에서 등록 여부를 확인해 주세요. 같은 요청 다시 확인은 기존 요청 번호를 사용해요. 이미 등록됐다면 닫아도 돼요.'
      : '요청한 날짜의 최신 목록 확인을 완료하지 못했어요. 날짜를 이동하거나 다시 조회했다면 다시 확인해 주세요.', !editor.checkedAfterUnknown);
    updateCalendarEditorState(); return;
  }
  if (!confirmed) { calendarMessage('calendar-form-message', '최신 일정 확인이 중단됐어요. 다시 불러온 후 수정해 주세요.', true); return; }
  const latest = lookup.data.events?.find(event => event.id === editor.event.id);
  if (latest?.editable && latest.etag && calendarWritable()) {
    if (!window.confirm('Google Calendar의 최신 내용으로 입력창을 다시 열까요? 현재 작성한 내용은 바뀝니다.')) return;
    openCalendarEditor(latest, true);
  } else calendarMessage('calendar-form-message', '선택한 날짜에서 이 일정을 찾을 수 없거나 수정 권한이 없어요. Google Calendar에서 변경·삭제 여부를 확인해 주세요.', true);
});
$('calendar-form-reset').addEventListener('click', () => {
  if (!(calendarEditor?.uncertain || calendarEditor?.stale) || !calendarEditor.checkedAfterUnknown || calendarBusy) return;
  const payload = calendarEditor.payload;
  const submittedDate = payload?.allDay ? payload.start : payload ? seoulDate(payload.start) : null;
  if (!calendarLookupConfirmed(calendarEditor.checkedLookup, submittedDate, calendarDate, calendarLoadSequence)) {
    calendarEditor.checkedAfterUnknown = false; updateCalendarEditorState();
    calendarMessage('calendar-form-message', '요청한 날짜의 최신 일정을 다시 확인해 주세요.', true); return;
  }
  if (window.confirm('이전 일정이 등록되지 않은 것을 확인했나요? 새로 작성하면 별도의 일정이 등록되며, 이전 요청이 성공했다면 중복될 수 있어요.')) openCalendarEditor(null, true);
});
$('calendar-delete-submit').addEventListener('click', deleteCalendarEvent);
$('calendar-delete-cancel').addEventListener('click', () => { if (!calendarBusy) { calendarDelete = null; $('calendar-delete-confirm').hidden = true; } });
$('calendar-delete-refresh').addEventListener('click', async () => {
  if (!calendarDelete || calendarBusy) return;
  const deletion = calendarDelete;
  const selectedDate = calendarDate;
  const lookup = await loadCalendar();
  if (deletion !== calendarDelete) return;
  if (!calendarLookupConfirmed(lookup, selectedDate, calendarDate, calendarLoadSequence)) {
    calendarMessage('calendar-delete-message', '최신 일정 확인이 중단됐어요. 다시 확인한 뒤 삭제해 주세요.', true); return;
  }
  const latest = lookup.data.events?.find(event => event.id === deletion.event.id);
  if (latest?.editable && latest.etag && calendarWritable()) confirmCalendarDelete(latest);
  else calendarMessage('calendar-delete-message', calendarData?.connected && !calendarData.error
    ? '선택한 날짜에서 이 일정을 찾을 수 없어요. 삭제되었거나 다른 날짜로 이동했을 수 있으니 Google Calendar에서도 확인해 주세요.'
    : '현재 일정을 확인하지 못했어요. 연결을 확인한 후 다시 시도해 주세요.', true);
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
  const results = await Promise.allSettled([loadNews(),loadCalendar(),loadSummaryStatus()]);
  results.filter(r=>r.status==='rejected').forEach(r=>notice(r.reason.message,true));
  setInterval(() => { if (!document.hidden) loadCalendar().catch(error=>notice(error.message,true)); },60000);
  setInterval(() => { if (!document.hidden) loadNews().catch(error=>notice(error.message,true)); },300000);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { for (const panel of summaryPanels.values()) stopSummaryPoll(panel); return; }
    Promise.allSettled([loadNews(), loadCalendar(), loadSummaryStatus()]).then(results => results.filter(r=>r.status==='rejected').forEach(r=>notice(r.reason.message,true)));
  });
}
window.addEventListener('pagehide', () => { for (const panel of summaryPanels.values()) stopSummaryPoll(panel); });
init();
