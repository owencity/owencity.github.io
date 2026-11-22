import test from 'node:test';
import assert from 'node:assert/strict';
import {safeUrl, serverAddress, nextEvent, relativeStart, eventTime} from '../public/devdesk-core.mjs';

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
