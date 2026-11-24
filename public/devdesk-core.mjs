export function safeUrl(value) {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : null; } catch { return null; }
}

export function serverAddress(value) {
  const url = new URL(value);
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.username || url.password || (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback))) throw new Error('서버 주소는 HTTPS를 사용해 주세요. 로컬에서는 http://localhost:8080을 사용할 수 있어요.');
  return `${url.origin}/devdesk.html`;
}

export function eventTime(event) {
  if (event.allDay) return '종일';
  const date = new Date(event.start);
  if (Number.isNaN(date.getTime())) return '시간 확인 필요';
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hour12: false }).format(date);
}

export function nextEvent(events, now = new Date()) {
  return events.filter(e => !e.allDay && Date.parse(e.end || e.start) > now.getTime()).sort((a,b) => Date.parse(a.start) - Date.parse(b.start))[0] || null;
}

export function relativeStart(event, now = new Date()) {
  if (!event) return '';
  const minutes = Math.ceil((Date.parse(event.start) - now.getTime()) / 60000);
  if (minutes <= 0) return '지금 진행 중';
  return minutes < 60 ? `${minutes}분 후` : `${Math.floor(minutes / 60)}시간 ${minutes % 60}분 후`;
}

export function shortDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '날짜 미상' : new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date);
}

export function seoulDate(value = new Date()) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error('날짜를 확인해 주세요.');
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(date).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function shiftCalendarDate(value, days) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('날짜를 확인해 주세요.');
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value)
    throw new Error('날짜를 확인해 주세요.');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

export function seoulDateTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error('시간을 확인해 주세요.');
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
  }).formatToParts(date).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

export function calendarInputValues(event, date = seoulDate()) {
  if (!event) return { title: '', description: '', location: '', allDay: false,
    start: `${date}T09:00`, end: `${date}T10:00`, reminderMode: 'DEFAULT', reminderMinutes: 10,
    repeat: 'NONE', repeatCount: 12 };
  return { title: event.title || '', description: event.description || '', location: event.location || '',
    allDay: Boolean(event.allDay),
    start: event.allDay ? event.start : seoulDateTime(event.start),
    end: event.allDay ? shiftCalendarDate(event.end, -1) : seoulDateTime(event.end),
    reminderMode: 'KEEP', reminderMinutes: Number.isInteger(event.reminderMinutes) ? event.reminderMinutes : 10,
    repeat: 'NONE', repeatCount: 12 };
}

export function calendarEventPayload(fields, editing = false) {
  const title = String(fields.title || '').trim();
  if (!title) throw new Error('일정 제목을 입력해 주세요.');
  let start, end;
  if (fields.allDay) {
    start = shiftCalendarDate(fields.start, 0);
    const inclusiveEnd = shiftCalendarDate(fields.end, 0);
    if (inclusiveEnd < start) throw new Error('종료 날짜는 시작 날짜와 같거나 이후여야 해요.');
    end = shiftCalendarDate(inclusiveEnd, 1);
  } else {
    const toOffset = value => {
      const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})$/.exec(value || '');
      if (!match || Number(match[2]) > 23 || Number(match[3]) > 59) throw new Error('시작과 종료 시간을 확인해 주세요.');
      shiftCalendarDate(match[1], 0);
      return `${value}:00+09:00`;
    };
    start = toOffset(fields.start); end = toOffset(fields.end);
    if (Date.parse(end) <= Date.parse(start)) throw new Error('종료 시간은 시작 시간보다 이후여야 해요.');
  }
  const reminderMode = fields.reminderMode || 'DEFAULT';
  if (!(editing ? ['KEEP', 'DEFAULT', 'NONE', 'POPUP'] : ['DEFAULT', 'NONE', 'POPUP']).includes(reminderMode))
    throw new Error('알림 설정을 확인해 주세요.');
  const reminderMinutes = reminderMode === 'POPUP' ? Number(fields.reminderMinutes) : null;
  if (reminderMode === 'POPUP' && (!Number.isInteger(reminderMinutes) || reminderMinutes < 0 || reminderMinutes > 40320))
    throw new Error('알림은 시작 0~40320분 전으로 설정해 주세요.');
  const payload = { title, description: String(fields.description || ''), location: String(fields.location || ''),
    allDay: Boolean(fields.allDay), start, end, reminderMode, reminderMinutes };
  if (!editing) {
    payload.repeat = fields.repeat || 'NONE';
    if (!['NONE', 'DAILY', 'WEEKDAYS', 'WEEKLY'].includes(payload.repeat)) throw new Error('반복 설정을 확인해 주세요.');
    if (payload.repeat === 'WEEKDAYS' && [0, 6].includes(new Date(`${fields.start.slice(0, 10)}T00:00:00Z`).getUTCDay()))
      throw new Error('평일 반복 일정은 월요일부터 금요일 중에 시작하도록 날짜를 선택해 주세요.');
    payload.repeatCount = payload.repeat === 'NONE' ? null : Number(fields.repeatCount);
    if (payload.repeat !== 'NONE' && (!Number.isInteger(payload.repeatCount) || payload.repeatCount < 2 || payload.repeatCount > 366))
      throw new Error('반복 횟수는 2~366회로 설정해 주세요.');
  }
  return payload;
}

export function calendarEventRange(event) {
  if (event.allDay) {
    const last = shiftCalendarDate(event.end, -1);
    return event.start === last ? '종일' : `종일 · ${event.start} ~ ${last}`;
  }
  const start = seoulDateTime(event.start), end = seoulDateTime(event.end);
  return start.slice(0, 10) === end.slice(0, 10)
    ? `${start.slice(11)}–${end.slice(11)}`
    : `${start.slice(5, 10)} ${start.slice(11)}–${end.slice(5, 10)} ${end.slice(11)}`;
}

export function calendarLookupConfirmed(result, submittedDate, selectedDate, latestSequence) {
  return Boolean(result && result.sequence === latestSequence && result.date === submittedDate
    && selectedDate === submittedDate && result.data?.date === submittedDate
    && result.data.connected && !result.data.error);
}

export function summaryState(status, canRetry = false) {
  const states = {
    NOT_REQUESTED: { message: '아직 정리된 내용이 없어요. 요청하면 내용을 확인해 정리하고 저장해요.', requestLabel: 'AI 정리 요청', canRequest: true },
    QUEUED: { message: '정리 순서를 기다리고 있어요. 완료되면 여기에 표시돼요.', pending: true },
    PROCESSING: { message: '기사 내용을 읽고 중요한 정보와 조건을 정리하고 있어요.', pending: true },
    SUCCEEDED: { message: '저장된 내용 정리', success: true },
    FAILED: { message: '내용 정리를 완료하지 못했어요.', requestLabel: '다시 정리 요청', canRequest: canRetry === true },
    DISABLED: { message: 'AI 정리가 꺼져 있거나 연결 설정이 필요해요. 서버의 Gemini 연결 설정을 확인해 주세요.' }
  };
  return states[status] || { message: '정리 상태를 확인할 수 없어요. 잠시 후 다시 확인해 주세요.' };
}

function summaryRecordedBudget(value) {
  return Number.isFinite(value?.dailyLimit) && Number.isFinite(value?.attemptsToday)
    ? Math.max(0, value.dailyLimit - value.attemptsToday) : null;
}

export function summaryAvailability(status, now = Date.now()) {
  if (status?.enabled === false || status?.configured === false) return 'DISABLED';
  const globalBudget = summaryRecordedBudget(status);
  if (globalBudget === 0) return 'DAILY_LIMIT';
  // The server also returns zero while provider calls are paused, before the app budget is spent.
  if (Number.isFinite(status?.remainingToday) && status.remainingToday <= 0)
    return globalBudget === null ? 'CALLS_PAUSED' : 'GLOBAL_WAIT';
  const models = Array.isArray(status?.models) ? status.models.filter(model => typeof model?.model === 'string' && model.model) : [];
  if (!models.length) return 'AVAILABLE'; // Older servers only report the app-wide budget.
  const withinBudget = models.filter(model => {
    const recordedBudget = summaryRecordedBudget(model);
    return recordedBudget !== null ? recordedBudget > 0
      : Date.parse(model.blockedUntil) > now || !Number.isFinite(model.remainingToday) || model.remainingToday > 0;
  });
  if (!withinBudget.length) return models.every(model => summaryRecordedBudget(model) === 0) ? 'MODEL_LIMIT' : 'CALLS_PAUSED';
  return withinBudget.some(model => !(Date.parse(model.blockedUntil) > now)) ? 'AVAILABLE' : 'MODEL_WAIT';
}

export function summaryModelUsage(status, now = Date.now()) {
  const models = Array.isArray(status?.models) ? status.models.filter(model => typeof model?.model === 'string' && model.model) : [];
  if (!models.length) return [];
  const lines = [`모델 사용 순서: ${models.map(model => model.model).join(' → ')}`];
  for (const model of models) {
    const count = Number.isFinite(model.attemptsToday) ? `${model.attemptsToday}회` : '확인 필요';
    const limit = Number.isFinite(model.dailyLimit) ? ` / 앱 일일 한도 ${model.dailyLimit}회` : '';
    const recordedBudget = summaryRecordedBudget(model);
    const budget = recordedBudget ?? (Date.parse(model.blockedUntil) > now ? null : model.remainingToday);
    const remaining = Number.isFinite(budget) ? ` · 앱 예산 ${Math.max(0, budget)}회 남음` : '';
    const pause = Date.parse(model.blockedUntil) > now ? ` · ${shortDate(model.blockedUntil)}까지 앱 대기` : '';
    lines.push(`${model.model}: 오늘 앱 호출 ${count}${limit}${remaining}${pause}`);
  }
  lines.push('앱이 기록한 사용량이며 Google 계정의 실제 남은 한도와는 다를 수 있어요.');
  return lines;
}

export function summarySource(scope) {
  if (scope === 'ARTICLE_BODY') return '기사 본문 기반';
  if (scope === 'FEED_DESCRIPTION') return 'RSS 설명 기반 · 원문 전체를 확인한 정리가 아니에요';
  return '사용한 자료 범위 확인 필요';
}

export function shouldPollSummary({ status, expanded, visible, connected, now, deadline }) {
  return Boolean(summaryState(status).pending && expanded && visible && connected && now < deadline);
}

export function createTaskDeadlineScheduler({ isBusy, check, now = Date.now, schedule = setTimeout, cancel = clearTimeout }) {
  let timer = null, deadline = null, pending = false;
  function resume() {
    if (!pending || isBusy()) return;
    pending = false;
    return check();
  }
  function stop() {
    if (timer !== null) cancel(timer);
    timer = null; deadline = null; pending = false;
  }
  function update(ends) {
    const current = now();
    // Preserve an elapsed deadline even if a busy mutation or delayed browser timer overlaps it.
    if (deadline !== null && deadline <= current) pending = true;
    if (timer !== null) cancel(timer);
    timer = null; deadline = null;
    const future = ends.filter(end => Number.isFinite(end) && end > current);
    if (future.length) {
      deadline = Math.min(...future);
      timer = schedule(() => {
        timer = null; deadline = null; pending = true;
        resume();
      }, deadline - current);
    }
    resume();
  }
  return { update, resume, stop };
}
