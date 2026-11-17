const root = document.documentElement
const theme = document.querySelector('.theme')
const themeKey = 'owencity-site-theme-v2'

function setTheme(mode) {
  const dark = mode === 'dark'
  root.dataset.theme = mode
  theme.setAttribute('aria-pressed', String(dark))
  theme.setAttribute('aria-label', dark ? '라이트 모드로 전환' : '다크 모드로 전환')
  theme.querySelector('span').textContent = dark ? '☀' : '☾'
}

let savedTheme
try { savedTheme = localStorage.getItem(themeKey) } catch {}
setTheme(savedTheme === 'dark' ? 'dark' : 'light')
theme.addEventListener('click', () => {
  const mode = root.dataset.theme === 'dark' ? 'light' : 'dark'
  savedTheme = mode
  setTheme(mode)
  try { localStorage.setItem(themeKey, mode) } catch {}
})

for (const avatar of document.querySelectorAll('[data-avatar]')) {
  const fallback = new URL('./assets/brand.png', document.baseURI).href
  const useFallback = () => { if (avatar.src !== fallback) avatar.src = fallback }
  avatar.addEventListener('error', useFallback)
  if (avatar.complete && !avatar.naturalWidth) useFallback()
}

const sidebar = document.querySelector('.sidebar')
const openButton = document.querySelector('.drawer-open')
const closeButton = document.querySelector('.drawer-close')
const scrim = document.querySelector('.scrim')
const shell = document.querySelector('.shell')
const skipLink = document.querySelector('.skip')
const mobile = window.matchMedia('(max-width: 960px)')

function setDrawer(open, focus = true) {
  sidebar.classList.toggle('open', open)
  scrim.hidden = !open
  shell.inert = open
  skipLink.inert = open
  openButton.setAttribute('aria-expanded', String(open))
  if (open) {
    sidebar.setAttribute('role', 'dialog')
    sidebar.setAttribute('aria-modal', 'true')
    closeButton.focus()
  } else {
    sidebar.removeAttribute('role')
    sidebar.removeAttribute('aria-modal')
    if (focus) (mobile.matches ? openButton : document.querySelector('.brand')).focus()
  }
}

openButton.addEventListener('click', () => setDrawer(true))
closeButton.addEventListener('click', () => setDrawer(false))
scrim.addEventListener('click', () => setDrawer(false))
mobile.addEventListener('change', () => {
  if (!mobile.matches && sidebar.classList.contains('open')) setDrawer(false)
})
document.addEventListener('keydown', (event) => {
  if (!sidebar.classList.contains('open')) return
  if (event.key === 'Escape') {
    event.preventDefault()
    setDrawer(false)
  }
  if (event.key === 'Tab') {
    const focusable = sidebar.querySelectorAll('button:not([disabled]), a[href]')
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    const boundary = event.shiftKey ? first : last
    if (document.activeElement === boundary) {
      event.preventDefault()
      const next = event.shiftKey ? last : first
      next.focus()
    }
  }
})
sidebar.addEventListener('click', (event) => {
  const link = event.target.closest('a')
  if (!link || !sidebar.classList.contains('open')) return
  const href = link.getAttribute('href')
  const target = href.startsWith('#') ? document.getElementById(href.slice(1)) : null
  setDrawer(false, !target)
  if (target) target.focus({ preventScroll: true })
})

const normalizeSearch = (value) => value.normalize('NFKC').toLocaleLowerCase('ko').trim()

function connectSearch(input, items, empty, status, getSearchText) {
  if (!input || !items.length) return
  input.closest('label').hidden = false
  const indexed = [...items].map((item) => ({
    item,
    text: normalizeSearch(getSearchText(item)),
  }))
  function filter() {
    const words = normalizeSearch(input.value).split(/\s+/)
    for (const { item, text } of indexed) item.hidden = !words.every((word) => text.includes(word))
    empty.hidden = indexed.some(({ item }) => !item.hidden)
    status.textContent = empty.hidden ? '' : empty.textContent
  }
  input.addEventListener('input', filter)
  filter()
}

connectSearch(
  document.querySelector('#writing .search input'),
  document.querySelectorAll('#posts .post-card'),
  document.querySelector('.search-empty'),
  document.querySelector('.search-status'),
  (post) => `${post.querySelector('h2, h3').textContent} ${post.querySelector('.post-summary').textContent} ${post.dataset.category || ''}`,
)
connectSearch(
  document.querySelector('#repository-search'),
  document.querySelectorAll('#repository-results .repo-card'),
  document.querySelector('.repo-empty'),
  document.querySelector('.repo-status'),
  (repo) => repo.dataset.search || repo.textContent,
)

const sectionLinks = document.querySelectorAll('.side-nav a[href^="#"], .topnav a[href^="#"]')
function markSection() {
  const selected = window.location.hash || '#about'
  if (selected === '#writing' && document.querySelector('#about')) {
    window.location.replace(new URL('./blog.html', window.location.href).href)
    return
  }
  for (const link of sectionLinks) {
    if (link.hash === selected) link.setAttribute('aria-current', 'location')
    else link.removeAttribute('aria-current')
  }
}
markSection()
window.addEventListener('hashchange', markSection)

const toc = document.querySelector('.toc')
if (toc) {
  const wide = window.matchMedia('(min-width: 1270px)')
  const setToc = () => { toc.open = wide.matches }
  setToc()
  wide.addEventListener('change', setToc)
}
