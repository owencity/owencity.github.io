/* Scenery is decorative. Reading, links, and navigation work independently. */
(() => {
  const engine = window.PixelScenes
  const panel = document.querySelector('.scenery')
  if (!engine?.scenes?.length || !panel) return

  const screens = [...document.querySelectorAll('[data-scene-wallpaper], [data-scene-window]')]
  const contexts = screens.map(canvas => canvas.getContext('2d', { alpha: false }))
  if (contexts.some(context => !context)) return
  const frame = document.createElement('canvas')
  const previousFrame = document.createElement('canvas')
  frame.width = previousFrame.width = engine.width
  frame.height = previousFrame.height = engine.height
  const drawing = frame.getContext('2d', { alpha: false })
  const previousDrawing = previousFrame.getContext('2d', { alpha: false })
  if (!drawing || !previousDrawing) return
  screens.forEach(canvas => { canvas.width = engine.width; canvas.height = engine.height })
  contexts.forEach(context => { context.imageSmoothingEnabled = false })

  const play = document.querySelector('[data-scene-play]')
  if (!play) return
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)')
  const duration = 10_000
  const frameInterval = 1000 / 24
  const transitionDuration = 800
  let sceneIndex = 0
  let paused = reducedMotion.matches
  try {
    const storedIndex = Number(sessionStorage.getItem('owencity-pixel-scene'))
    if (Number.isInteger(storedIndex) && storedIndex >= 0 && storedIndex < engine.scenes.length) sceneIndex = storedIndex
    paused ||= localStorage.getItem('owencity-pixel-paused') === 'true'
  } catch {}
  let elapsed = 0
  let lastTimestamp = null
  let lastPaint = -Infinity
  let transitionElapsed = transitionDuration
  let animationId = null

  function updateLabels() {
    const scene = engine.scenes[sceneIndex]
    panel.querySelector('.scene-label').textContent = scene.name
    panel.querySelector('.scene-counter').textContent = `${String(sceneIndex + 1).padStart(2, '0')} / ${String(engine.scenes.length).padStart(2, '0')}`
    panel.dataset.scene = scene.id
    panel.dataset.paused = String(paused)
    play.querySelector('span').textContent = paused ? '▶' : 'Ⅱ'
    play.setAttribute('aria-label', paused ? '배경 재생' : '배경 정지')
    play.title = paused ? '배경 재생' : '배경 정지'
    try { sessionStorage.setItem('owencity-pixel-scene', String(sceneIndex)) } catch {}
  }

  function paint() {
    drawing.save()
    engine.scenes[sceneIndex].draw(drawing, elapsed / 1000)
    drawing.restore()
    for (const context of contexts) {
      context.globalAlpha = 1
      context.drawImage(frame, 0, 0)
      if (transitionElapsed < transitionDuration) {
        context.globalAlpha = 1 - transitionElapsed / transitionDuration
        context.drawImage(previousFrame, 0, 0)
        context.globalAlpha = 1
      }
    }
  }

  function chooseScene(index, animate = false) {
    previousDrawing.drawImage(screens[screens.length - 1], 0, 0)
    sceneIndex = (index + engine.scenes.length) % engine.scenes.length
    elapsed = 0
    transitionElapsed = animate && !paused && !reducedMotion.matches ? 0 : transitionDuration
    updateLabels()
    paint()
  }

  function tick(timestamp) {
    animationId = null
    if (paused || document.hidden) return
    const delta = lastTimestamp === null ? 0 : timestamp - lastTimestamp
    lastTimestamp = timestamp
    elapsed += delta
    transitionElapsed += delta
    if (elapsed >= duration) {
      const remaining = elapsed % duration
      chooseScene(sceneIndex + Math.floor(elapsed / duration), true)
      elapsed = remaining
    }
    if (timestamp - lastPaint >= frameInterval) {
      paint()
      lastPaint = timestamp
    }
    animationId = requestAnimationFrame(tick)
  }

  function schedule() {
    if (animationId !== null) cancelAnimationFrame(animationId)
    animationId = null
    lastTimestamp = null
    lastPaint = -Infinity
    if (!paused && !document.hidden) animationId = requestAnimationFrame(tick)
  }

  play.addEventListener('click', () => {
    paused = !paused
    updateLabels()
    try { localStorage.setItem('owencity-pixel-paused', String(paused)) } catch {}
    schedule()
  })
  document.addEventListener('visibilitychange', schedule)
  reducedMotion.addEventListener('change', () => {
    if (reducedMotion.matches) {
      paused = true
      transitionElapsed = transitionDuration
      updateLabels()
      paint()
      schedule()
    }
  })
  play.hidden = false
  updateLabels()
  paint()
  schedule()
})()
