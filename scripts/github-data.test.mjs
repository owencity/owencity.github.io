import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeEvents, normalizeGraphql, normalizeRepository } from './github-data.mjs';

const sampleRepository = {
  name: 'inventory', full_name: 'sample/inventory', html_url: 'https://github.com/sample/inventory',
  description: null, language: null, stargazers_count: 0, pushed_at: null,
};

function push(id, date, overrides = {}) {
  return { id, type: 'PushEvent', public: true, repo: { name: 'sample/inventory' }, created_at: date, ...overrides };
}

test('저장소의 선택 필드가 없으면 화면에서 사용할 기본값을 반환한다', () => {
  assert.deepEqual(normalizeRepository(sampleRepository), {
    name: 'inventory', fullName: 'sample/inventory', url: 'https://github.com/sample/inventory',
    description: '', language: '', stars: 0, updatedAt: null, fork: false, archived: false,
  });
  const fork = normalizeRepository({ ...sampleRepository, description: '재고', language: 'Kotlin', fork: true, archived: true });
  assert.deepEqual([fork.description, fork.language, fork.fork, fork.archived], ['재고', 'Kotlin', true, true]);
});

test('공개 활동은 지원하는 종류만 최신순으로 정렬하고 최대 12건으로 제한한다', () => {
  const datedPushes = Array.from({ length: 13 }, (_, index) => push(String(index + 1), `2026-01-${String(index + 1).padStart(2, '0')}T00:00:00Z`));
  const newest = '2026-02-03T00:00:00Z';
  const activity = normalizeEvents([
    ...datedPushes,
    push('private', newest, { public: false }),
    push('unknown visibility', newest, { public: undefined }),
    push('unsupported', newest, { type: 'WatchEvent' }),
    push('pr', newest, { type: 'PullRequestEvent', payload: { number: 8, pull_request: { title: '재고 조회', html_url: 'https://github.com/sample/inventory/pull/8' } } }),
    push('create', '2026-02-02T00:00:00Z', { type: 'CreateEvent', payload: { ref_type: 'repository' } }),
    push('push', '2026-02-01T00:00:00Z', { payload: { head: 'abc123' } }),
  ]);
  assert.deepEqual(activity.map(event => event.id), ['pr', 'create', 'push', '13', '12', '11', '10', '9', '8', '7', '6', '5']);
  assert.deepEqual(activity.slice(0, 3).map(event => [event.type, event.title, event.url]), [
    ['pr', '재고 조회', 'https://github.com/sample/inventory/pull/8'],
    ['repository', '저장소를 만들었습니다', 'https://github.com/sample/inventory'],
    ['push', '코드를 업데이트했습니다', 'https://github.com/sample/inventory/commit/abc123'],
  ]);
});

test('공개 Push 활동에 커밋 ID가 없으면 저장소 링크를 사용한다', () => {
  const [event] = normalizeEvents([push('push', '2026-01-01T00:00:00Z')]);
  assert.equal(event.url, sampleRepository.html_url);
});

test('필수 정보가 없는 활동은 제외하고 정상 응답을 보존한다', () => {
  const date = '2026-01-01T00:00:00Z';
  const activity = normalizeEvents([
    push('valid', date),
    push('missing repository', date, { repo: {} }),
    push('missing date', undefined),
    push('missing PR number', date, { type: 'PullRequestEvent' }),
    null,
  ]);
  assert.deepEqual(activity.map(event => event.id), ['valid']);
});

const pinnedRepository = { __typename: 'Repository', isPrivate: false, nameWithOwner: 'sample/inventory' };

test('공개 핀과 합성 기여 기록을 변환하고 비공개 핀을 제외한다', () => {
  const user = {
    pinnedItems: { nodes: [pinnedRepository, { ...pinnedRepository, isPrivate: true }, { __typename: 'Gist' }, null] },
    contributionsCollection: {
      contributionCalendar: {
        totalContributions: 4,
        weeks: [{ contributionDays: [{ date: '2026-01-01', contributionCount: 4, contributionLevel: 'SECOND_QUARTILE' }] }],
      },
    },
  };
  assert.deepEqual(normalizeGraphql(user), {
    pinned: ['sample/inventory'],
    contributions: { total: 4, weeks: [{ days: [{ date: '2026-01-01', count: 4, level: 'SECOND_QUARTILE' }] }] },
  });
});

test('GraphQL 응답에 없는 항목을 빈 핀이나 기여 0건으로 바꾸지 않는다', () => {
  assert.deepEqual(normalizeGraphql({}), {});
  assert.deepEqual(normalizeGraphql({ pinnedItems: { nodes: [] }, contributionsCollection: null }), { pinned: [] });
  assert.deepEqual(normalizeGraphql({ pinnedItems: null, contributionsCollection: { contributionCalendar: null } }), {});
  assert.throws(() => normalizeGraphql(null), /GitHub 사용자/);
});

test('불완전한 기여 응답에서 정상 핀을 유지한다', () => {
  const user = {
    pinnedItems: { nodes: [{ ...pinnedRepository, nameWithOwner: undefined }, pinnedRepository] },
    contributionsCollection: { contributionCalendar: { totalContributions: 0, weeks: [{ contributionDays: null }] } },
  };
  assert.deepEqual(normalizeGraphql(user), { pinned: ['sample/inventory'] });
});
