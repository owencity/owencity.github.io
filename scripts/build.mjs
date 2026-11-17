import { readFile, writeFile, mkdir, cp, readdir, unlink } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import MarkdownIt from 'markdown-it';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

const require = createRequire(import.meta.url);
const root = new URL('../', import.meta.url);
const output = new URL('dist/', root);
const includeDrafts = process.argv.includes('--drafts');
const readJson = async name => JSON.parse(await readFile(new URL(name, root), 'utf8'));
const [config, github, allPosts] = await Promise.all([readJson('site.config.json'), readJson('data/github.json'), readJson('content/posts.json')]);
const posts = allPosts.filter(post => includeDrafts || !post.draft).sort((first, second) => second.date.localeCompare(first.date));
const escape = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
const safeUrl = value => {
  try { const address = new URL(value); return address.protocol === 'https:' ? address.href : '#'; } catch { return '#'; }
};
const external = (url, label, className = '') => `<a href="${escape(safeUrl(url))}" class="${className}" target="_blank" rel="noopener noreferrer">${escape(label)}</a>`;
const date = value => value ? new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: 'Asia/Seoul' }).format(new Date(value)) : '아직 없음';

function repositoryCard(repository) {
  return `<article class="repo-card" data-search="${escape(`${repository.name} ${repository.description} ${repository.language}`.toLocaleLowerCase('ko'))}">
    <div class="repo-top"><span class="repo-icon" aria-hidden="true">⌘</span><span class="repo-visibility">Public</span></div>
    <h3>${external(repository.url, repository.name)}</h3><p>${escape(repository.description || 'GitHub에서 프로젝트를 확인할 수 있습니다.')}</p>
    <div class="repo-meta"><span><i class="language-dot" aria-hidden="true"></i>${escape(repository.language || '언어 미분류')}</span><span>★ ${escape(repository.stars)}</span>${repository.archived ? '<span>보관됨</span>' : ''}</div></article>`;
}

function repositories() {
  const selected = Array.isArray(github.pinned) ? github.pinned : config.featuredRepositories.map(name => `${config.username}/${name}`);
  const indexed = new Map(github.repositories.map(repository => [repository.fullName, repository]));
  const featured = selected.map(name => indexed.get(name)).filter(Boolean);
  const other = github.repositories.filter(repository => !selected.includes(repository.fullName));
  return `<div class="repo-grid">${featured.map(repositoryCard).join('')}</div>
    <details class="all-repositories"><summary>전체 저장소 보기 (${github.repositories.length})</summary>
    <label class="search repo-search" hidden><span class="sr-only">공개 저장소 검색</span><input type="search" id="repository-search" placeholder="이름, 설명, 언어" aria-controls="repository-results"></label>
    <div id="repository-results" class="repo-grid">${[...featured, ...other].map(repositoryCard).join('')}</div>
    <p class="repo-empty empty" hidden>검색어와 일치하는 저장소가 없습니다.</p><p class="repo-status sr-only" role="status"></p></details><p class="data-note">저장소 정보 확인 ${date(github.fetchedAt.repositories)}</p>`;
}

function contributions() {
  const calendar = github.contributions;
  if (!calendar) return `<div class="calendar-unavailable"><span class="calendar-icon" aria-hidden="true">▦</span><div><h3>GitHub 기여 기록</h3><p>기여 기록을 아직 불러오지 못했습니다.</p>${external(`https://github.com/${config.username}?tab=overview`, 'GitHub에서 잔디 보기 ↗')}</div></div>`;
  const levels = ['NONE', 'FIRST_QUARTILE', 'SECOND_QUARTILE', 'THIRD_QUARTILE', 'FOURTH_QUARTILE'];
  const labels = ['일', '월', '화', '수', '목', '금', '토'];
  const days = calendar.weeks.flatMap(week => week.days);
  const rows = labels.map((label, weekday) => `<tr><th scope="row">${label}</th>${calendar.weeks.map(week => {
    const day = week.days.find(entry => new Date(`${entry.date}T00:00:00Z`).getUTCDay() === weekday);
    if (!day) return '<td class="calendar-padding"></td>';
    return `<td class="contribution-day" data-level="${Math.max(0, levels.indexOf(day.level))}" title="${escape(day.date)} · ${day.count}건" aria-label="${escape(day.date)} 기여 ${day.count}건"></td>`;
  }).join('')}</tr>`).join('');
  return `<div class="calendar-summary"><strong>기여 ${calendar.total.toLocaleString('ko-KR')}건</strong><span>${escape(days[0]?.date)} ~ ${escape(days.at(-1)?.date)}</span></div>
    <div class="calendar-scroll" tabindex="0" role="region" aria-label="GitHub 기여 달력"><table class="contribution-calendar"><caption class="sr-only">GitHub에서 제공한 날짜별 기여 횟수</caption><tbody>${rows}</tbody></table></div>
    <div class="calendar-legend"><span>적음</span>${levels.map((level, index) => `<i class="contribution-day" data-level="${index}" aria-hidden="true"></i>`).join('')}<span>많음</span></div>
    <p class="data-note">확인 ${date(github.fetchedAt.contributions)} · ${external(`https://github.com/${config.username}`, 'GitHub에서 보기 ↗')}</p>`;
}

function activity() {
  if (!Array.isArray(github.activity)) return `<p class="empty">활동을 불러오지 못했습니다. ${external(`https://github.com/${config.username}`, 'GitHub에서 확인 ↗')}</p>`;
  if (!github.activity.length) return '<p class="empty">최근 공개 활동이 없습니다.</p>';
  return `<ol class="activity-list">${github.activity.map(event => `<li class="activity-item">
    <span class="activity-mark" aria-hidden="true">${event.type === 'pr' ? 'PR' : event.type === 'push' ? '↑' : '+'}</span>
    <div>${external(event.url, event.title, 'activity-title')}<p class="activity-meta">${escape(event.repository)} <span>·</span> <time datetime="${escape(event.date)}">${date(event.date)}</time></p></div>
    </li>`).join('')}</ol>`;
}

function postCards() {
  return posts.length ? `<ul class="posts">${posts.map(post => `<li class="card post-card" data-category="${escape(post.category)}">
    <a class="post-link" href="./${escape(post.slug)}.html">${post.image ? `<img src="./${escape(post.image)}" alt="" width="520" height="260" loading="lazy">` : ''}
    <div class="post-body"><p class="meta"><span class="chip">${escape(post.category)}</span>${post.draft ? '<span class="chip">검수 전 초안</span>' : ''}<time datetime="${escape(post.date)}">${date(post.date)}</time></p>
    <h2>${escape(post.title)}</h2><p class="post-summary">${escape(post.summary)}</p><span class="post-read">글 읽기 <span aria-hidden="true">→</span></span></div></a></li>`).join('')}</ul>` : '<ul class="posts"></ul><div class="publication-empty"><strong>첫 글을 준비하고 있습니다.</strong><p>공개한 글은 이곳에서 읽을 수 있습니다.</p></div>';
}

function renderArticle(post, source) {
  const markdown = new MarkdownIt({ html: false, linkify: true });
  const headings = [];
  const standardFence = markdown.renderer.rules.fence;
  markdown.renderer.rules.fence = (tokens, index, options, environment, renderer) => tokens[index].info.trim() === 'mermaid'
    ? `<pre class="mermaid">${escape(tokens[index].content)}</pre>` : standardFence(tokens, index, options, environment, renderer);
  markdown.renderer.rules.table_open = () => '<div class="table-scroll" tabindex="0" role="region" aria-label="표"><table>';
  markdown.renderer.rules.table_close = () => '</table></div>';
  const standardLink = markdown.renderer.rules.link_open;
  markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
    const original = tokens[index].attrGet('href');
    if (original && !/^(?:https?:|#|mailto:)/.test(original) && /\.md(?:#|$)/.test(original)) tokens[index].attrSet('href', new URL(original, post.sourceBaseUrl).href);
    if (/^https?:/.test(tokens[index].attrGet('href') || '')) {
      tokens[index].attrSet('target', '_blank'); tokens[index].attrSet('rel', 'noopener noreferrer');
    }
    return standardLink ? standardLink(tokens, index, options, environment, renderer) : renderer.renderToken(tokens, index, options);
  };
  markdown.renderer.rules.heading_open = (tokens, index, options, environment, renderer) => {
    const heading = tokens[index];
    if (['h2', 'h3'].includes(heading.tag)) {
      const id = `section-${headings.length + 1}`;
      heading.attrSet('id', id);
      headings.push({ id, tag: heading.tag, label: tokens[index + 1].content });
    }
    return renderer.renderToken(tokens, index, options);
  };
  const content = markdown.render(source);
  return { content, toc: headings.map(heading => `<a class="toc-${heading.tag}" href="#${heading.id}">${escape(heading.label)}</a>`).join('') };
}

const pixelAssets = await Promise.all(['site.css', 'site.js', 'blog.css', 'pixel-theme.css', 'pixel-scenes.js', 'scene-player.js', 'diagrams.js'].map(name => readFile(new URL(`public/${name}`, root))));
const pixelAssetVersion = createHash('sha256').update(Buffer.concat(pixelAssets)).digest('hex').slice(0, 12);
const common = {
  PIXEL_ASSET_VERSION: pixelAssetVersion,
  PROFILE_AVATAR: escape(safeUrl(github.profile.avatar)),
  PROFILE_BIO: escape(github.profile.bio || '백엔드 개발자 김동규의 프로젝트와 개발 기록.'),
  REPO_COUNT: String(github.repositories.length),
  UPDATED_AT: date(github.fetchedAt.activity),
  ASSET_PREFIX: './',
};
const fill = (template, values) => template.replace(/\{\{([A-Z_]+)\}\}/g, (placeholder, name) => {
  if (!Object.hasOwn(values, name)) throw new Error(`템플릿 값 누락: ${name}`);
  return values[name];
});

await mkdir(output, { recursive: true });
// Remove only generated HTML files in this project's output before rebuilding.
for (const filename of await readdir(output)) if (filename.endsWith('.html')) await unlink(new URL(filename, output));
await cp(new URL('public/', root), output, { recursive: true });
const [homeTemplate, articleTemplate, blogTemplate] = await Promise.all(['home', 'article', 'blog'].map(name => readFile(new URL(`src/${name}.html`, root), 'utf8')));
let home = fill(homeTemplate, { ...common, REPOSITORIES: repositories(), CONTRIBUTIONS: contributions(), ACTIVITY: activity() });
if (includeDrafts) home = home.replace('</head>', '<meta name="robots" content="noindex"></head>');
await writeFile(new URL('index.html', output), home);
let blog = fill(blogTemplate, {
  ...common,
  POSTS: postCards(),
  POST_COUNT: String(posts.length),
  BLOG_PREVIEW_NOTICE: includeDrafts ? '<p class="blog-preview-notice">미리보기 · 검수 전 초안을 포함한 목록입니다. 초안은 공개 사이트에 표시되지 않습니다.</p>' : '',
});
if (includeDrafts) blog = blog.replace('</head>', '<meta name="robots" content="noindex"></head>');
await writeFile(new URL('blog.html', output), blog);
for (const post of posts) {
  if (!/^[a-z0-9-]+$/.test(post.slug)) throw new Error('글 주소에는 영문 소문자, 숫자, 하이픈만 사용할 수 있습니다.');
  const source = await readFile(new URL(`content/${post.source}`, root), 'utf8');
  const rendered = renderArticle(post, source);
  let page = fill(articleTemplate, { ...common, ARTICLE_CONTENT: rendered.content, ARTICLE_TOC: rendered.toc, ARTICLE_TITLE: escape(post.title), ARTICLE_SUMMARY: escape(post.summary) });
  if (post.draft) page = page.replace('</head>', '<meta name="robots" content="noindex"></head>');
  await writeFile(new URL(`${post.slug}.html`, output), page);
}
await cp(require.resolve('mermaid/dist/mermaid.min.js'), fileURLToPath(new URL('mermaid.min.js', output)));
await writeFile(new URL('.nojekyll', output), '');
console.log(JSON.stringify({ output: fileURLToPath(output), posts: posts.length, includeDrafts, repositories: github.repositories.length, contributions: Boolean(github.contributions) }));
