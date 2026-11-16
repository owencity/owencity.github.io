import { readFile, writeFile } from 'node:fs/promises';
import { normalizeRepository, normalizeEvents, normalizeGraphql } from './github-data.mjs';

const root = new URL('../', import.meta.url);
const config = JSON.parse(await readFile(new URL('site.config.json', root), 'utf8'));
const dataPath = new URL('data/github.json', root);
let previous;
try { previous = JSON.parse(await readFile(dataPath, 'utf8')); } catch {}
const token = process.env.GITHUB_TOKEN;
const now = new Date().toISOString();
const headers = {
  Accept: 'application/vnd.github+json',
  'User-Agent': 'owencity-personal-site',
  'X-GitHub-Api-Version': '2026-03-10',
};

async function request(url, options = {}) {
  const response = await fetch(url, { ...options, headers: { ...headers, ...options.headers }, signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new Error(`GitHub 조회 실패: HTTP ${response.status}`);
  return response.json();
}

async function repositories() {
  const collection = [];
  for (let page = 1; ; page++) {
    const entries = await request(`https://api.github.com/users/${config.username}/repos?type=owner&sort=pushed&per_page=100&page=${page}`);
    collection.push(...entries.filter(repository => repository.private === false).map(normalizeRepository));
    if (entries.length < 100) return collection;
  }
}

// The public REST endpoints intentionally do not receive credentials.
const responses = await Promise.allSettled([
  request(`https://api.github.com/users/${config.username}`),
  repositories(),
  request(`https://api.github.com/users/${config.username}/events/public?per_page=100`),
]);
const snapshot = {
  ...(previous || {}),
  attemptedAt: now,
  fetchedAt: { ...(previous?.fetchedAt || {}) },
  errors: [],
};
const names = ['profile', 'repositories', 'activity'];
responses.forEach((response, index) => {
  const name = names[index];
  if (response.status === 'rejected') {
    snapshot.errors.push(name);
    return;
  }
  if (name === 'profile') {
    const profile = response.value;
    snapshot.profile = { login: profile.login, name: config.name, avatar: profile.avatar_url, bio: profile.bio || '', url: profile.html_url, publicRepos: profile.public_repos };
  } else if (name === 'repositories') snapshot.repositories = response.value;
  else snapshot.activity = normalizeEvents(response.value);
  snapshot.fetchedAt[name] = now;
});

// Public events may omit PR titles; enrich only events read during this sync.
if (responses[2].status === 'fulfilled') {
  snapshot.activity = await Promise.all(snapshot.activity.map(async event => {
    if (event.type !== 'pr' || !/^PR #\d+$/.test(event.title)) return event;
    const number = event.url.match(/\/pull\/(\d+)$/)?.[1];
    if (!number) return event;
    try {
      const pullRequest = await request(`https://api.github.com/repos/${event.repository}/pulls/${number}`);
      if (pullRequest.base?.repo?.private === false && pullRequest.title) return { ...event, title: pullRequest.title };
    } catch { /* Keep the event link if supplementary metadata is unavailable. */ }
    return event;
  }));
}

if (token) {
  try {
    const response = await request('https://api.github.com/graphql', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        query: `query Profile($login: String!) { user(login: $login) {
          pinnedItems(first: 6, types: REPOSITORY) { nodes { __typename ... on Repository { nameWithOwner isPrivate } } }
          contributionsCollection { contributionCalendar { totalContributions weeks { contributionDays { date contributionCount contributionLevel } } } }
        } }`,
        variables: { login: config.username },
      }),
    });
    const normalized = normalizeGraphql(response.data?.user);
    for (const name of ['pinned', 'contributions']) {
      if (Object.hasOwn(normalized, name)) {
        snapshot[name] = normalized[name];
        snapshot.fetchedAt[name] = now;
      } else snapshot.errors.push(name);
    }
  } catch { snapshot.errors.push('pinned', 'contributions'); }
} else {
  snapshot.errors.push('pinned', 'contributions');
}

if (!snapshot.profile || !snapshot.repositories) {
  throw new Error('GitHub 정보를 가져오지 못했습니다. 공개 API 연결을 확인한 뒤 npm run sync를 다시 실행하세요.');
}
await writeFile(dataPath, `${JSON.stringify(snapshot, null, 2)}\n`);
console.log(JSON.stringify({ repositories: snapshot.repositories.length, activity: snapshot.activity?.length || 0, contributions: Boolean(snapshot.contributions), unavailable: snapshot.errors }));
