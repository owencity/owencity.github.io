export function normalizeRepository(repository) {
  return {
    name: repository.name,
    fullName: repository.full_name,
    description: repository.description || '',
    url: repository.html_url,
    language: repository.language || '',
    stars: repository.stargazers_count,
    updatedAt: repository.pushed_at,
    fork: repository.fork === true,
    archived: repository.archived === true,
  };
}

export function normalizeEvents(events) {
  return events.filter(event => event?.public === true).flatMap(event => {
    const payload = event.payload || {};
    const repository = event.repo?.name;
    if (!repository || !event.created_at || !Number.isFinite(Date.parse(event.created_at))) return [];
    let title;
    let url = `https://github.com/${repository}`;
    let type;
    if (event.type === 'PushEvent') {
      type = 'push';
      title = '코드를 업데이트했습니다';
      if (payload.head) url += `/commit/${encodeURIComponent(payload.head)}`;
    } else if (event.type === 'PullRequestEvent') {
      if (!Number.isInteger(payload.number)) return [];
      type = 'pr';
      title = payload.pull_request?.title || `PR #${payload.number}`;
      url = payload.pull_request?.html_url || `${url}/pull/${payload.number}`;
    } else if (event.type === 'CreateEvent' && payload.ref_type === 'repository') {
      type = 'repository';
      title = '저장소를 만들었습니다';
    } else return [];
    return [{ id: event.id, type, title, repository, url, date: event.created_at }];
  }).sort((first, second) => second.date.localeCompare(first.date)).slice(0, 12);
}

export function normalizeGraphql(user) {
  if (!user) throw new Error('GitHub 사용자를 찾지 못했습니다.');
  const result = {};
  if (Array.isArray(user.pinnedItems?.nodes)) {
    result.pinned = user.pinnedItems.nodes.filter(item => item?.__typename === 'Repository' && item.isPrivate === false)
      .map(item => item.nameWithOwner).filter(name => typeof name === 'string' && /^[^/]+\/[^/]+$/.test(name));
  }
  const calendar = user.contributionsCollection?.contributionCalendar;
  const validCalendar = Array.isArray(calendar?.weeks) && calendar.weeks.length > 0
    && Number.isInteger(calendar.totalContributions) && calendar.totalContributions >= 0
    && calendar.weeks.every(week => Array.isArray(week?.contributionDays) && week.contributionDays.every(day =>
      day && /^\d{4}-\d{2}-\d{2}$/.test(day.date) && Number.isInteger(day.contributionCount) && day.contributionCount >= 0
      && ['NONE', 'FIRST_QUARTILE', 'SECOND_QUARTILE', 'THIRD_QUARTILE', 'FOURTH_QUARTILE'].includes(day.contributionLevel)));
  if (validCalendar) {
    result.contributions = {
      total: calendar.totalContributions,
      weeks: calendar.weeks.map(week => ({
        days: week.contributionDays.map(day => ({ date: day.date, count: day.contributionCount, level: day.contributionLevel })),
      })),
    };
  }
  return result;
}
