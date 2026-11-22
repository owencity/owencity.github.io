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
