import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {safeUrl, serverAddress, nextEvent, relativeStart, eventTime, seoulDate, seoulDateTime, shiftCalendarDate, calendarInputValues, calendarEventPayload, calendarEventRange, calendarLookupConfirmed, summaryState, summaryAvailability, summaryModelUsage, summarySource, shouldPollSummary, createTaskDeadlineScheduler} from '../public/devdesk-core.mjs';

test('untrusted news links cannot execute scripts',()=>{
  assert.equal(safeUrl('javascript:alert(1)'),null);
  assert.equal(safeUrl('data:text/html,hello'),null);
  assert.equal(safeUrl('https://news.hada.io/topic?id=1'),'https://news.hada.io/topic?id=1');
});
test('connection screen requires HTTPS except loopback and rejects embedded secrets',()=>{
  assert.equal(serverAddress('http://localhost:8080'),'http://localhost:8080/devdesk.html');
  assert.equal(serverAddress('https://desk.example.com/foo'),'https://desk.example.com/devdesk.html');
  assert.throws(()=>serverAddress('http://203.0.113.10:8080'));
  assert.throws(()=>serverAddress('https://user:secret@example.com'));
  assert.throws(()=>serverAddress('javascript:alert(1)'));
});
test('next event keeps ongoing events but excludes all-day and ended ones',()=>{
  const now = new Date('2026-11-22T12:00:00Z');
  const events = [{id:'all',allDay:true,start:'2026-11-22',end:'2026-11-23'},{id:'done',start:'2026-11-22T10:00:00Z',end:'2026-11-22T11:00:00Z'},{id:'future',start:'2026-11-22T12:30:00Z',end:'2026-11-22T13:00:00Z'},{id:'ongoing',start:'2026-11-22T11:30:00Z',end:'2026-11-22T12:15:00Z'}];
  assert.equal(nextEvent(events,now).id,'ongoing');
  assert.equal(relativeStart(events[2],now),'30분 후');
  assert.equal(relativeStart(events[3],now),'지금 진행 중');
  assert.equal(eventTime(events[2]),'21:30');
  assert.equal(eventTime(events[0]),'종일');
});

test('summary states do not confuse queued, failed or unknown work with a completed summary',()=>{
  assert.equal(summaryState('SUCCEEDED').success,true);
  assert.equal(summaryState('QUEUED').pending,true);
  assert.equal(summaryState('PROCESSING').pending,true);
  assert.equal(summaryState('NOT_REQUESTED').canRequest,true);
  assert.equal(summaryState('FAILED').canRequest,false);
  assert.equal(summaryState('DISABLED').canRequest,undefined);
  for (const state of ['NOT_REQUESTED','QUEUED','PROCESSING','FAILED','DISABLED','unexpected'])
    assert.notEqual(summaryState(state).success,true);
});

test('failed summaries offer retry only when the server explicitly permits another attempt',()=>{
  assert.equal(summaryState('FAILED',true).canRequest,true);
  for (const permission of [false,undefined,null,'true',1])
    assert.equal(summaryState('FAILED',permission).canRequest,false);
  assert.equal(summaryState('NOT_REQUESTED',false).canRequest,true);
  for (const status of ['QUEUED','PROCESSING','SUCCEEDED','DISABLED','unexpected'])
    assert.notEqual(summaryState(status,true).canRequest,true);
});

test('summary model fallback remains available when primary is exhausted or blocked, within the global cap',()=>{
  const now=Date.parse('2026-11-22T12:00:00Z');
  const primary={model:'gemini-3.5-flash-lite',dailyLimit:500,attemptsToday:500,remainingToday:0,blockedUntil:null};
  const fallback={model:'gemini-3.1-flash-lite',dailyLimit:500,attemptsToday:2,remainingToday:498,blockedUntil:null};
  const status={enabled:true,configured:true,dailyLimit:600,attemptsToday:502,remainingToday:98,models:[primary,fallback]};
  assert.equal(summaryAvailability(status,now),'AVAILABLE');
  assert.equal(summaryAvailability({...status,models:[{...primary,attemptsToday:490,remainingToday:0,blockedUntil:'2026-11-22T13:00:00Z'},fallback]},now),'AVAILABLE');
  assert.equal(summaryAvailability({...status,attemptsToday:600,remainingToday:0},now),'DAILY_LIMIT');
  assert.equal(summaryAvailability({...status,models:[primary,{...fallback,attemptsToday:500,remainingToday:0}]},now),'MODEL_LIMIT');
  const blocked={...status,models:[primary,{...fallback,remainingToday:0,blockedUntil:'2026-11-22T13:00:00Z'}]};
  assert.equal(summaryAvailability(blocked,now),'MODEL_WAIT');
  assert.equal(summaryAvailability(blocked,Date.parse('2026-11-22T13:00:00Z')),'AVAILABLE');
  assert.equal(summaryAvailability({...status,configured:false},now),'DISABLED');
  assert.equal(summaryAvailability({enabled:true,configured:true,remainingToday:20},now),'AVAILABLE');
});

test('provider pauses returning zero remaining are not mislabeled as exhausted app budgets',()=>{
  const now=Date.parse('2026-11-22T12:00:00Z');
  const status={enabled:true,configured:true,dailyLimit:1000,attemptsToday:5,remainingToday:995,models:[
    {model:'gemini-3.5-flash-lite',dailyLimit:500,attemptsToday:3,remainingToday:0,blockedUntil:'2026-11-22T13:00:00Z'},
    {model:'gemini-3.1-flash-lite',dailyLimit:500,attemptsToday:2,remainingToday:0,blockedUntil:'2026-11-22T13:00:00Z'}
  ]};
  assert.equal(summaryAvailability(status,now),'MODEL_WAIT');
  assert.equal(summaryAvailability({...status,remainingToday:0},now),'GLOBAL_WAIT');
  assert.equal(summaryAvailability({enabled:true,configured:true,remainingToday:0},now),'CALLS_PAUSED');
  const lines=summaryModelUsage(status,now);
  assert.match(lines[1],/오늘 앱 호출 3회.*앱 예산 497회 남음.*앱 대기/);
  assert.match(lines[2],/오늘 앱 호출 2회.*앱 예산 498회 남음.*앱 대기/);
});

test('summary usage preserves configured model order and labels counters as app records, not account quota',()=>{
  const now=Date.parse('2026-11-22T12:00:00Z');
  const lines=summaryModelUsage({models:[
    {model:'gemini-3.5-flash-lite',dailyLimit:500,attemptsToday:500,remainingToday:0,blockedUntil:'2026-11-22T13:00:00Z'},
    {model:'gemini-3.1-flash-lite',dailyLimit:500,attemptsToday:7,remainingToday:493,blockedUntil:null}
  ]},now);
  assert.equal(lines[0],'모델 사용 순서: gemini-3.5-flash-lite → gemini-3.1-flash-lite');
  assert.match(lines[1],/오늘 앱 호출 500회.*앱 일일 한도 500회.*앱 예산 0회 남음.*앱 대기/);
  assert.match(lines[2],/오늘 앱 호출 7회.*앱 예산 493회 남음/);
  assert.match(lines[3],/Google 계정의 실제 남은 한도와는 다를 수/);
  assert.deepEqual(summaryModelUsage({model:'legacy',remainingToday:20},now),[]);
});

test('summary source makes RSS fallback distinct from article body',()=>{
  assert.equal(summarySource('ARTICLE_BODY'),'기사 본문 기반');
  assert.match(summarySource('FEED_DESCRIPTION'),/원문 전체를 확인한 정리가 아니에요/);
  assert.match(summarySource(null),/확인 필요/);
});

test('summary polling requires a visible expanded connected panel and stops at its deadline',()=>{
  const active={status:'PROCESSING',expanded:true,visible:true,connected:true,now:1000,deadline:2000};
  assert.equal(shouldPollSummary(active),true);
  assert.equal(shouldPollSummary({...active,status:'QUEUED'}),true);
  for (const status of ['SUCCEEDED','FAILED','DISABLED','NOT_REQUESTED','unexpected'])
    assert.equal(shouldPollSummary({...active,status}),false);
  for (const key of ['expanded','visible','connected'])
    assert.equal(shouldPollSummary({...active,[key]:false}),false);
  assert.equal(shouldPollSummary({...active,now:2000}),false);
  assert.equal(shouldPollSummary({...active,now:3000}),false);
});

test('calendar date and edit inputs consistently use Seoul even across UTC midnight',()=>{
  assert.equal(seoulDate('2026-11-22T23:00:00Z'),'2026-11-23');
  assert.equal(seoulDateTime('2026-11-22T15:00:00Z'),'2026-11-23T00:00');
  const form=calendarInputValues({title:'회의',allDay:false,start:'2026-11-22T23:00:00Z',end:'2026-11-23T00:00:00Z',reminderMode:'OTHER'});
  assert.equal(form.start,'2026-11-23T08:00');
  assert.equal(form.end,'2026-11-23T09:00');
  assert.equal(form.reminderMode,'KEEP');
  assert.equal(shiftCalendarDate('2028-02-28',1),'2028-02-29');
  assert.equal(shiftCalendarDate('2026-12-31',1),'2027-01-01');
  assert.throws(()=>shiftCalendarDate('2026-02-30',1));
});

test('all-day inclusive UI dates round-trip to exclusive Google end dates',()=>{
  const event={title:'휴가',allDay:true,start:'2026-11-23',end:'2026-11-26'};
  const fields=calendarInputValues(event);
  assert.equal(fields.end,'2026-11-25');
  const payload=calendarEventPayload(fields,true);
  assert.equal(payload.start,event.start);
  assert.equal(payload.end,event.end);
  assert.equal(payload.reminderMode,'KEEP');
  assert.equal('repeat' in payload,false);
  const single=calendarEventPayload({...fields,start:'2026-11-23',end:'2026-11-23'},true);
  assert.equal(single.end,'2026-11-24');
  assert.equal(calendarEventRange(single),'종일');
  assert.throws(()=>calendarEventPayload({...fields,end:'2026-11-22'},true),/종료 날짜/);
});

test('timed calendar payloads carry explicit Seoul offsets and reject invalid durations',()=>{
  const fields=calendarInputValues(null,'2026-11-23');
  const payload=calendarEventPayload({...fields,start:'2026-11-23T23:30',end:'2026-11-24T00:30',title:'배포',reminderMode:'POPUP',reminderMinutes:'15'});
  assert.equal(payload.start,'2026-11-23T23:30:00+09:00');
  assert.equal(payload.end,'2026-11-24T00:30:00+09:00');
  assert.equal(payload.reminderMinutes,15);
  assert.equal(calendarEventRange(payload),'11-23 23:30–11-24 00:30');
  assert.throws(()=>calendarEventPayload({...fields,title:'회의',end:fields.start}),/종료 시간/);
  assert.throws(()=>calendarEventPayload({...fields,title:'회의',start:'2026-11-23T25:00'}),/시간/);
  assert.throws(()=>calendarEventPayload({...fields,title:'회의',start:'2026-02-30T09:00'}),/날짜/);
  assert.throws(()=>calendarEventPayload({...fields,title:'회의',reminderMode:'POPUP',reminderMinutes:40321}),/알림/);
});

test('repeat creation is bounded and weekday repeats start on a weekday',()=>{
  const fields={...calendarInputValues(null,'2026-11-23'),title:'개발 공부',repeat:'WEEKDAYS',repeatCount:'12'};
  assert.equal(calendarEventPayload(fields).repeatCount,12);
  assert.throws(()=>calendarEventPayload({...fields,start:'2026-11-22T09:00',end:'2026-11-22T10:00'}),/평일 반복/);
  assert.throws(()=>calendarEventPayload({...fields,start:'2026-11-21T09:00',end:'2026-11-21T10:00'}),/평일 반복/);
  assert.throws(()=>calendarEventPayload({...fields,repeatCount:367}),/반복 횟수/);
  assert.throws(()=>calendarEventPayload({...fields,reminderMode:'KEEP'}),/알림 설정/);
});

test('uncertain creation can reset only after the submitted date lookup was applied and remains current',()=>{
  const date='2026-11-23';
  const result={sequence:4,date,data:{date,connected:true,error:null}};
  assert.equal(calendarLookupConfirmed(result,date,date,4),true);
  assert.equal(calendarLookupConfirmed(null,date,date,4),false);
  assert.equal(calendarLookupConfirmed(result,date,date,5),false); // A background reload superseded the check.
  assert.equal(calendarLookupConfirmed(result,date,'2026-11-24',4),false); // User navigated while waiting.
  assert.equal(calendarLookupConfirmed({...result,data:{...result.data,date:'2026-11-24'}},date,date,4),false);
  assert.equal(calendarLookupConfirmed({...result,data:{...result.data,connected:false}},date,date,4),false);
  assert.equal(calendarLookupConfirmed({...result,data:{...result.data,error:'연결 오류'}},date,date,4),false);
});

function taskClock() {
  let time=0, busy=false, calls=0, next=0;
  const timers=new Map();
  const scheduler=createTaskDeadlineScheduler({isBusy:()=>busy,check:()=>{calls++;},now:()=>time,
    schedule:(fn,delay)=>{const id=++next;timers.set(id,{fn,at:time+delay});return id;},cancel:id=>timers.delete(id)});
  return {scheduler,timers,get calls(){return calls;},time:value=>{time=value;},busy:value=>{busy=value;},
    fire:()=>{const [id,timer]=[...timers][0];timers.delete(id);time=timer.at;timer.fn();}};
}

test('completion check survives a mutation longer than the old single retry window',()=>{
  const clock=taskClock();
  clock.scheduler.update([1000]);clock.busy(true);clock.fire();
  clock.time(8000);clock.scheduler.update([]);
  assert.equal(clock.calls,0);
  clock.busy(false);clock.scheduler.update([]);
  assert.equal(clock.calls,1);
  clock.scheduler.resume();assert.equal(clock.calls,1);
});

test('cancelled plan timers do not prevent future plans after the cancelled deadline',()=>{
  const clock=taskClock();clock.scheduler.update([1000]);
  clock.time(500);clock.scheduler.update([]);assert.equal(clock.timers.size,0);
  clock.time(2000);clock.scheduler.update([4000]);assert.equal(clock.timers.size,1);
  clock.fire();assert.equal(clock.calls,1);
});

test('a delayed browser timer remains due across rendering and still schedules the next plan',()=>{
  const clock=taskClock();clock.scheduler.update([1000]);clock.time(1500);clock.scheduler.update([4000]);
  assert.equal(clock.calls,1);assert.equal(clock.timers.size,1);
  clock.scheduler.stop();assert.equal(clock.timers.size,0);
});

// Execute the production planner with a minimal DOM and an injected API. No HTTP or Google calls.
function plannerHarness(apiHandler) {
  class Element {
    constructor(tag='div'){this.tagName=tag;this.children=[];this.events={};this.dataset={};this.value='';this.textContent='';this.disabled=false;}
    append(...nodes){this.children.push(...nodes);}
    replaceChildren(...nodes){this.children=[...nodes];}
    setAttribute(name,value){this[name]=value;}
    addEventListener(name,handler){this.events[name]=handler;}
    scrollIntoView(){} focus(){}
    get childElementCount(){return this.children.length;}
    querySelectorAll(selector){
      const all=this.children.flatMap(child=>[child,...child.querySelectorAll('*')]);
      if(selector==='*')return all;
      if(selector==='details[open]')return all.filter(child=>child.tagName==='details'&&child.open);
      if(selector==='.task-add input')return all.filter(child=>child.className==='task-add').flatMap(form=>form.children.filter(child=>child.tagName==='input'));
      return all.filter(child=>child.tagName===selector);
    }
  }
  const nodes=new Map();const $=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);};
  const calls=[];let fixtureBoard,confirmation=true,calendarResult,boardLoader;
  const context={$,
    document:{createElement:tag=>new Element(tag)},window:{confirm:()=>confirmation},
    text:(tag,value,className)=>{const el=new Element(tag);el.textContent=value;el.className=className;return el;},
    seoulDate:()=> '2026-11-24',seoulDateTime,crypto:{randomUUID:()=> 'test-request'},
    calendarWritable:()=>true,
    isUncertainCalendarError:error=>!Number.isInteger(error.status)||error.status>=500,
    api:async(path,options={})=>{calls.push({path,...options});if(path==='/api/tasks/board')return boardLoader ? boardLoader() : structuredClone(fixtureBoard);return apiHandler(path,options);},
    loadCalendar:async()=>calendarResult ?? {sequence:1,todayData:{connected:true,events:[]}},
  };
  const app=readFileSync(new URL('../public/devdesk.js',import.meta.url),'utf8');
  const planner=app.slice(app.indexOf('function taskPath('),app.indexOf('function calendarFields('));
  runInNewContext(`let tasksBoard,tasksLoadSequence=0,taskBusy=false,taskEditor,todayPlans=[],plansDate='2026-11-24';
    let taskRefresh,taskRefreshPending=false,taskMutationVersion=0,calendarLoadSequence=1;
    const notifiedPlans=new Set(),uncertainTaskAdds=new Map();const taskDeadlines={update(){},stop(){}};
    ${planner}
    globalThis.ui={renderTasks,loadTasks,openTaskPlan,refreshTaskNotifications,
      seed(board,plans=[]){tasksBoard=board;todayPlans=plans;},
      get editor(){return taskEditor;},get pending(){return uncertainTaskAdds;},get board(){return tasksBoard;}};`,context);
  return {$,calls,ui:context.ui,setBoard:board=>{fixtureBoard=board;context.ui.seed(structuredClone(board));},
    confirm:value=>{confirmation=value;},calendar:value=>{calendarResult=value;},loadBoardWith:loader=>{boardLoader=loader;}};
}

const taskFixture=()=>({connected:true,writeEnabled:true,lists:[{id:'today',title:'TODAY',horizon:'TODAY',tasks:[{id:'task1',title:'개발',completed:false,planKey:'key1'}]}]});
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const submit={preventDefault(){}};

test('readonly Tasks keeps Calendar planning available while blocking add and completion',()=>{
  const app=plannerHarness(()=>{throw Error('Unexpected write');});
  app.setBoard({...taskFixture(),writeEnabled:false});app.ui.renderTasks();
  const board=app.$('tasks-board');
  const inputs=board.querySelectorAll('input');
  assert.equal(inputs.find(input=>input.type==='checkbox').disabled,true);
  assert.equal(inputs.find(input=>input.name==='today').disabled,true);
  assert.equal(board.querySelectorAll('button').find(button=>button.textContent==='시간 정하기').disabled,false);
  assert.match(app.$('tasks-status').textContent,/조회만 가능/);
  board.querySelectorAll('form')[0].events.submit(submit);
  assert.equal(app.calls.length,0);
});

test('unknown task creation freezes repeat POST until fresh list reconciliation and confirmation',async()=>{
  const app=plannerHarness(async()=>{throw Object.assign(Error('응답 불명확'),{status:502});});
  app.setBoard(taskFixture());app.ui.renderTasks();
  let form=app.$('tasks-board').querySelectorAll('form')[0];form.children[0].value='새 할 일';
  form.events.submit(submit);await tick();
  form=app.$('tasks-board').querySelectorAll('form')[0];
  assert.equal(form.children[0].value,'새 할 일');assert.equal(form.children[0].disabled,true);
  form.events.submit(submit);await tick();
  assert.equal(app.calls.filter(call=>call.method==='POST').length,1);
  await app.ui.loadTasks();
  let reset=app.$('tasks-board').querySelectorAll('button').find(button=>button.textContent==='확인 후 입력 다시 열기');
  assert.equal(reset.disabled,false);
  app.confirm(false);reset.events.click();assert.equal(app.ui.pending.size,1);
  app.confirm(true);reset.events.click();assert.equal(app.ui.pending.size,0);
  form=app.$('tasks-board').querySelectorAll('form')[0];assert.equal(form.children[0].disabled,false);assert.equal(form.children[0].value,'');
  assert.equal(app.calls.filter(call=>call.method==='POST').length,1);
});

test('ambiguous plan save preserves exact request and CAS; conflict requires latest-calendar reconciliation',async()=>{
  let attempt=0;
  const app=plannerHarness(async()=>{throw Object.assign(Error('결과 확인 필요'),{status:++attempt===1?502:412});});
  const board=taskFixture();app.setBoard(board);
  const plan={id:'event1',etag:'version1',taskPlanKey:'key1',start:'2026-11-24T10:00:00+09:00',end:'2026-11-24T11:00:00+09:00'};
  app.ui.seed(board,[plan]);await app.ui.openTaskPlan(board.lists[0],board.lists[0].tasks[0],plan);
  await app.$('task-plan-form').events.submit(submit);await tick();
  assert.equal(app.ui.editor.uncertain,true);assert.equal(app.$('task-plan-start').disabled,true);
  app.$('task-plan-start').value='12:00'; // Programmatic changes must not change an ambiguous retry.
  await app.$('task-plan-form').events.submit(submit);await tick();
  const writes=app.calls.filter(call=>call.method==='PUT');
  assert.equal(writes.length,2);assert.equal(writes[0].body,writes[1].body);
  assert.deepEqual(JSON.parse(writes[0].body),{date:'2026-11-24',start:'10:00',end:'11:00',requestId:'test-request',eventId:'event1',etag:'version1'});
  assert.equal(app.ui.editor.stale,true);assert.equal(app.$('task-plan-save').disabled,true);
  await app.$('task-plan-form').events.submit(submit);assert.equal(app.calls.filter(call=>call.method==='PUT').length,2);
  app.$('task-plan-close').events.click();assert.equal(app.ui.editor.stale,true);
  app.calendar(null);app.confirm(false);
  await app.ui.openTaskPlan(board.lists[0],board.lists[0].tasks[0],plan);assert.equal(app.ui.editor.stale,true);
  app.confirm(true);await app.ui.openTaskPlan(board.lists[0],board.lists[0].tasks[0],plan);
  assert.equal(app.ui.editor.stale,false);assert.equal(app.ui.editor.etag,'version1');
});

test('a mutation during completion refresh invalidates the old read and performs a fresh check',async()=>{
  const board=taskFixture();
  const app=plannerHarness(async()=>({id:'task2',title:'추가',completed:false,planKey:'key2'}));
  app.setBoard(board);app.ui.renderTasks();
  let reads=0,release;
  app.loadBoardWith(()=>++reads===1 ? new Promise(resolve=>{release=resolve;}) : structuredClone(board));
  const refreshing=app.ui.refreshTaskNotifications();
  const form=app.$('tasks-board').querySelectorAll('form')[0];form.children[0].value='추가';
  form.events.submit(submit);await tick();
  release(structuredClone(board));await refreshing;await tick();
  assert.equal(reads,2);
  assert.equal(app.calls.filter(call=>call.method==='POST').length,1);
});
