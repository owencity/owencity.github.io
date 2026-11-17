(() => {
  const width = 640
  const height = 360

  function random(seed) {
    return () => {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
      return seed / 4294967296
    }
  }

  function points(seed, count) {
    const rand = random(seed)
    return Array.from({ length: count }, () => [rand(), rand()])
  }

  const cityRipples = points(637, 23)
  const lakeRipples = points(601, 30)
  const riverRipples = points(19, 16)
  const harborRipples = points(98, 24)
  const raindrops = points(63, 150)
  const snowflakes = points(317, 143)
  const petals = points(37, 42)
  const cityStars = points(921, 24)
  const roofStars = points(922, 39)
  const observatoryStars = points(923, 61)
  const sunGlints = points(924, 82)
  const neonGlints = points(925, 76)
  const groundRipples = points(926, 27)
  const cityTrainCars = [51, 51, 51]
  const forestTrainCars = [51, 46, 46]
  const foregroundPosts = [24, 152, 282, 412, 542]
  const locomotiveWheels = [156, 169, 180]

  function mask(vertices) {
    const path = new Path2D()
    path.moveTo(vertices[0][0], vertices[0][1])
    for (let index = 1; index < vertices.length; index += 1) path.lineTo(vertices[index][0], vertices[index][1])
    path.closePath()
    return path
  }

  const cityWater = mask([[0, 244], [640, 244], [640, 326], [0, 326]])
  for (const bridgePier of [71, 232, 399, 566]) cityWater.rect(bridgePier - 5, 244, 24, 36)
  const lakeWater = mask([[143, 215], [640, 215], [640, 308], [548, 313], [541, 360], [145, 360]])
  const seaWater = mask([[0, 219], [96, 219], [96, 201], [287, 201], [256, 258], [228, 297],
    [193, 322], [112, 326], [112, 360], [0, 360]])
  const riverWater = mask([[210, 202], [370, 202], [370, 222], [574, 222], [574, 281], [472, 295],
    [327, 297], [217, 279], [210, 267]])
  const harborWater = mask([[346, 199], [640, 199], [640, 360], [0, 360], [0, 340], [189, 272],
    [240, 261], [333, 245]])
  const wetGround = mask([[219, 254], [403, 249], [640, 337], [640, 360], [0, 360], [0, 335]])
  const roofSky = new Path2D()
  roofSky.rect(0, 0, width, 100)
  roofSky.moveTo(449, 103)
  roofSky.arc(392, 103, 57, 0, Math.PI * 2)

  function twinkle(ctx, time, field, top, bottom, color) {
    ctx.save()
    for (let index = 0; index < field.length; index += 1) {
      const point = field[index]
      const phase = time * (.42 + point[0] * .32) + point[1] * 38
      const intensity = .5 + Math.sin(phase) * .5
      const x = 8 + point[0] * 623
      const y = top + point[1] * (bottom - top)
      ctx.globalAlpha = .16 + intensity * .72
      box(ctx, color, x, y, 1, 1)
      if (index % 17 === 0) {
        ctx.globalAlpha = Math.max(0, intensity - .72) * 1.9
        box(ctx, color, x - 2, y, 5, 1)
        box(ctx, color, x, y - 2, 1, 5)
      }
    }
    ctx.restore()
  }

  function pixelGlow(ctx, x, y, rx, ry, color, opacity) {
    ctx.save()
    for (let ring = 5; ring > 0; ring -= 1) {
      ctx.globalAlpha = opacity * (6 - ring) / 15
      disc(ctx, color, x, y, rx * ring / 5, ry * ring / 5, 2)
    }
    ctx.restore()
  }

  function reflectedHorizon(ctx, source, time, area, shoreline, depth, opacity) {
    ctx.save()
    ctx.clip(area, 'evenodd')
    ctx.globalAlpha = opacity
    ctx.imageSmoothingEnabled = false
    for (let row = 3; row < depth; row += 3) {
      const sourceY = shoreline - Math.floor(row * .68)
      const offset = Math.round(Math.sin(row * .31 + time * .62) * (1 + row * .022))
      ctx.drawImage(source, 0, sourceY, width, 2, offset, shoreline + row, width, 2)
    }
    ctx.restore()
  }

  function waterLight(ctx, time, area, x, top, depth, spread, color, field, opacity = .7) {
    ctx.save()
    ctx.clip(area, 'evenodd')
    for (let index = 0; index < field.length; index += 1) {
      const point = field[index]
      const distance = point[1]
      const phase = time * .8 + point[0] * 29 + distance * 17
      const reach = 1 + distance * 19
      const y = top + distance * depth
      ctx.globalAlpha = opacity * (.3 + .7 * Math.sin(phase) ** 2)
      box(ctx, color, x + (point[0] - .5) * spread * (.2 + distance) + Math.sin(phase) * 3,
        y, reach * (.25 + point[0]), 1)
    }
    ctx.restore()
  }

  function objectReflection(ctx, time, area, x, top, w, objectHeight, opacity) {
    ctx.save()
    ctx.clip(area, 'evenodd')
    ctx.globalAlpha = opacity
    for (let row = 0; row < objectHeight; row += 3) {
      const drift = Math.round(Math.sin(row * .34 + time * 1.3) * (1 + row * .05))
      ctx.drawImage(ctx.canvas, Math.round(x), Math.round(top - row - 2), w, 2,
        Math.round(x + drift), Math.round(top + row * .65 + 4), w, 1)
    }
    ctx.restore()
  }

  function lakeWake(ctx, time, x, y) {
    for (let index = 1; index <= 12; index += 1) {
      const age = index * .34
      const earlier = 303 + Math.sin((time - age) / 11) * 94
      const direction = Math.cos((time - age) / 11) >= 0 ? 1 : -1
      const start = earlier - direction * 20
      ctx.globalAlpha = (1 - index / 13) * .58
      box(ctx, '#d0ead6', start - index * .32, y + 10 + index * .9, 6 + index * .9, 1)
      box(ctx, '#b6ddd0', start - index * .32, y + 4 - index * .8, 4 + index * .7, 1)
    }
    ctx.globalAlpha = 1
  }

  function rainRings(ctx, time) {
    ctx.save()
    ctx.clip(wetGround)
    for (let index = 0; index < groundRipples.length; index += 1) {
      const point = groundRipples[index]
      const phase = (time * .49 + point[0] * 12) % 1
      const x = 12 + point[0] * 616
      const y = 270 + point[1] * 88
      const radius = (2 + phase * 11) * (.4 + point[1] * .6)
      ctx.globalAlpha = (1 - phase) * .56
      for (let angle = 0; angle < 24; angle += 1) {
        const radians = angle * Math.PI / 12
        box(ctx, index % 3 === 0 ? '#c6a7d6' : '#8bd4d2', x + Math.cos(radians) * radius,
          y + Math.sin(radians) * radius * .28, 1, 1)
      }
    }
    ctx.restore()
  }

  function box(ctx, color, x, y, w, h) {
    ctx.fillStyle = color
    ctx.fillRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h))
  }

  function shape(ctx, color, points, step = 2) {
    const top = Math.floor(Math.min(...points.map((point) => point[1])) / step) * step
    const bottom = Math.max(...points.map((point) => point[1]))
    ctx.fillStyle = color
    for (let y = top; y < bottom; y += step) {
      const scan = y + step / 2
      const crossings = []
      for (let index = 0; index < points.length; index += 1) {
        const [x1, y1] = points[index]
        const [x2, y2] = points[(index + 1) % points.length]
        if ((y1 <= scan && y2 > scan) || (y2 <= scan && y1 > scan)) {
          crossings.push(x1 + (x2 - x1) * (scan - y1) / (y2 - y1))
        }
      }
      crossings.sort((first, second) => first - second)
      for (let index = 0; index + 1 < crossings.length; index += 2) {
        const left = Math.round(crossings[index] / step) * step
        const right = Math.round(crossings[index + 1] / step) * step
        ctx.fillRect(left, y, right - left, step)
      }
    }
  }

  function pixelLine(ctx, color, x0, y0, x1, y1, size = 1) {
    let x = Math.round(x0)
    let y = Math.round(y0)
    x1 = Math.round(x1)
    y1 = Math.round(y1)
    const dx = Math.abs(x1 - x)
    const dy = -Math.abs(y1 - y)
    const sx = x < x1 ? 1 : -1
    const sy = y < y1 ? 1 : -1
    let error = dx + dy
    ctx.fillStyle = color
    while (true) {
      ctx.fillRect(x, y, size, size)
      if (x === x1 && y === y1) break
      const next = error * 2
      if (next >= dy) { error += dy; x += sx }
      if (next <= dx) { error += dx; y += sy }
    }
  }

  function rock(ctx, x, y, w, h, shade, light) {
    shape(ctx, shade, [[x - w * .54, y], [x - w * .45, y - h * .66], [x - w * .14, y - h],
      [x + w * .29, y - h * .84], [x + w * .54, y - h * .38], [x + w * .38, y]], 1)
    shape(ctx, light, [[x - w * .45, y - h * .55], [x - w * .13, y - h], [x + w * .28, y - h * .83],
      [x + w * .43, y - h * .49], [x - w * .02, y - h * .47], [x - w * .26, y - h * .35]], 1)
  }

  function disc(ctx, color, x, y, rx, ry = rx, step = 2) {
    ctx.fillStyle = color
    for (let dy = -ry; dy < ry; dy += step) {
      const span = Math.floor(rx * Math.sqrt(1 - (dy / ry) ** 2) / step) * step
      ctx.fillRect(Math.round(x - span), Math.round(y + dy), span * 2, step)
    }
  }

  function stripedSun(ctx, x, y, radius, color, stripe) {
    disc(ctx, color, x, y, radius)
    for (let dy = 6; dy < radius; dy += 6) {
      const half = Math.floor(Math.sqrt(radius ** 2 - dy ** 2) / 2) * 2
      box(ctx, stripe, x - half, y + dy, half * 2, 2)
    }
  }

  function sky(ctx, bands, bottom, seed) {
    const rand = random(seed)
    for (let index = 0; index < bands.length; index += 1) {
      const top = Math.round(index * bottom / bands.length / 2) * 2
      const end = Math.round((index + 1) * bottom / bands.length / 2) * 2
      box(ctx, bands[index], 0, top, width, end - top)
    }
    for (let index = 1; index < bands.length; index += 1) {
      const top = Math.round(index * bottom / bands.length / 2) * 2
      for (let x = 0; x < width; x += 4) {
        const shift = Math.floor(rand() * 4) * 2
        box(ctx, bands[index], x + (x % 8 ? 2 : 0), top - 2 - shift, 2, 2)
        box(ctx, bands[index - 1], x + (x % 8 ? 0 : 2), top + shift, 2, 2)
        if (x % 12 === 0) box(ctx, bands[index], x, top - 10 - shift, 1, 1)
      }
    }
  }

  function cloud(ctx, x, y, size, light, shadow) {
    disc(ctx, shadow, x - size * 0.35, y, size, size * 0.23)
    disc(ctx, light, x, y - size * 0.14, size * 0.93, size * 0.19)
    disc(ctx, light, x + size * 0.73, y, size * 0.56, size * 0.12)
    box(ctx, shadow, x - size, y + size * 0.12, size * 2.03, 2)
  }

  function stars(ctx, seed, count, bottom, colors) {
    const rand = random(seed)
    for (let index = 0; index < count; index += 1) {
      const x = rand() * width
      const y = 5 + rand() * (bottom - 5)
      const size = rand() < 0.17 ? 2 : 1
      const color = colors[Math.floor(rand() * colors.length)]
      box(ctx, color, x, y, size, size)
      if (index % 19 === 0) {
        box(ctx, color, x - 2, y, 6, 1)
        box(ctx, color, x, y - 2, 1, 5)
      }
    }
  }

  function water(ctx, top, bands, glints, seed) {
    const rand = random(seed)
    bands.forEach((color, index) => {
      const y = top + Math.floor(index * (height - top) / bands.length)
      const end = top + Math.floor((index + 1) * (height - top) / bands.length)
      box(ctx, color, 0, y, width, end - y)
    })
    for (let index = 0; index < 460; index += 1) {
      const y = top + rand() * (height - top)
      const reach = 4 + 40 * (y - top) / (height - top)
      box(ctx, glints[index % glints.length], rand() * width, y, 2 + rand() * reach, index % 5 === 0 ? 2 : 1)
    }
  }

  function reflect(ctx, x, top, span, spread, colors, seed, count = 68) {
    const rand = random(seed)
    for (let index = 0; index < count; index += 1) {
      const depth = rand()
      const y = top + depth * span
      const ox = Math.sin(y * .27) * (2 + depth * 5)
      const drift = (rand() - .5) * spread * (.35 + depth)
      const reach = (3 + depth * 12) * rand()
      box(ctx, colors[Math.floor(rand() * colors.length)], x + drift + ox, y, reach + 1, index % 6 ? 1 : 2)
    }
  }

  function sparkleWater(ctx, time, top, range, color, ripples) {
    for (let index = 0; index < ripples.length; index += 1) {
      const [x, row] = ripples[index]
      const start = x * width
      const y = top + row * range
      const phase = time * 1.4 + index * 1.71
      const length = 3 + Math.floor((0.5 + 0.5 * Math.sin(phase)) * (8 + 14 * (y - top) / range))
      if (Math.sin(phase * 0.61) > -0.15) box(ctx, color, start + time * 0.3, y, length, 1)
    }
  }

  function pine(ctx, x, y, size, shade, light = shade, bark = shade) {
    box(ctx, bark, x - size * 0.06, y - size * 0.23, size * 0.13, size * 0.23)
    shape(ctx, shade, [[x, y - size], [x + size * 0.21, y - size * 0.58], [x + size * 0.08, y - size * 0.60],
      [x + size * 0.30, y - size * 0.37], [x + size * 0.16, y - size * 0.42], [x + size * 0.40, y - size * 0.16],
      [x - size * 0.38, y - size * 0.15], [x - size * 0.15, y - size * 0.43], [x - size * 0.29, y - size * 0.37],
      [x - size * 0.08, y - size * 0.62], [x - size * 0.23, y - size * 0.56]])
    shape(ctx, light, [[x, y - size * 0.94], [x - size * 0.10, y - size * 0.62],
      [x - size * 0.03, y - size * 0.66], [x - size * 0.22, y - size * 0.39], [x - size * 0.07, y - size * 0.46],
      [x - size * 0.30, y - size * 0.19], [x - size * 0.01, y - size * 0.23]])
    if (size > 35) {
      for (let branch = 2; branch < 9; branch += 1) {
        const distance = branch / 10
        const reach = size * distance * .27
        pixelLine(ctx, light, x - reach, y - size + size * distance, x - 2, y - size + size * distance - 3)
        box(ctx, shade, x + 2, y - size + size * distance + 2, reach * .65, 1)
      }
      box(ctx, light, x - size * .04, y - size * .20, 1, size * .15)
    }
  }

  function buildings(ctx, base, seed, min, max, colors, windowColors, gap) {
    const rand = random(seed)
    for (let x = -10; x < width; ) {
      const w = 14 + Math.floor(rand() * 33)
      const h = min + Math.floor(rand() * (max - min))
      const y = base - h
      const color = colors[Math.floor(rand() * colors.length)]
      box(ctx, color, x, y, w, h)
      box(ctx, colors[0], x + w - Math.floor(w / 5), y + 3, Math.floor(w / 5), h - 3)
      box(ctx, colors[colors.length - 1], x, y, 1, h)
      box(ctx, colors[1], x, y, w, 1)
      for (let floor = y + 6; floor < base; floor += 14) {
        box(ctx, colors[0], x + 1, floor, w - 2, 1)
      }
      if (w > 27 && h > 42) {
        box(ctx, colors[0], x + 6, y - 4, 12, 5)
        box(ctx, colors[1], x + 7, y - 5, 10, 1)
        box(ctx, colors[0], x + w - 11, y - 9, 5, 9)
      }
      if (h > max * 0.68) {
        box(ctx, color, x + 4, y - 6, w - 10, 6)
        if (rand() < 0.42) {
          box(ctx, colors[0], x + w * 0.52, y - 15, 2, 10)
          box(ctx, windowColors[0], x + w * 0.52, y - 16, 2, 2)
        }
      }
      for (let wy = y + 7; wy < base - 5; wy += 7) {
        for (let wx = x + 4; wx < x + w - 7; wx += 6) {
          if (rand() > 0.54) {
            const glow = windowColors[Math.floor(rand() * windowColors.length)]
            ctx.globalAlpha = .16
            box(ctx, glow, wx - 1, wy - 1, 5, 4)
            ctx.globalAlpha = 1
            box(ctx, colors[0], wx - 1, wy, 5, 3)
            box(ctx, glow, wx, wy, 3, 2)
            box(ctx, colors[1], wx - 1, wy + 3, 5, 1)
          }
        }
      }
      x += w + gap + Math.floor(rand() * 6)
    }
  }

  function pixelText(ctx, text, x, y, color) {
    ctx.font = 'bold 10px monospace'
    ctx.textBaseline = 'top'
    ctx.fillStyle = color
    ctx.fillText(text, x, y)
  }

  function railcar(ctx, x, y, lengths, body, stripe, glass) {
    for (const length of lengths) {
      box(ctx, '#212132', x + 3, y + 13, length - 5, 4)
      box(ctx, body, x + 2, y, length - 4, 15)
      box(ctx, body, x, y + 3, length, 10)
      box(ctx, stripe, x, y + 10, length, 3)
      box(ctx, '#202332', x + 6, y + 15, 7, 3)
      box(ctx, '#202332', x + length - 14, y + 15, 8, 3)
      for (let slot = 4; slot < length - 7; slot += 10) box(ctx, glass, x + slot, y + 3, 7, 5)
      x += length + 3
    }
  }

  function cachedSprite(w, h, paint) {
    let canvas
    return {
      draw(ctx, x, y, drawWidth = w, drawHeight = h) {
        if (!canvas) {
          canvas = document.createElement('canvas')
          canvas.width = w
          canvas.height = h
          paint(canvas.getContext('2d'))
        }
        ctx.imageSmoothingEnabled = false
        ctx.drawImage(canvas, Math.round(x), Math.round(y), Math.round(drawWidth), Math.round(drawHeight))
      },
    }
  }

  const sailingBoat = cachedSprite(68, 58, (ctx) => {
    shape(ctx, '#724b43', [[1, 45], [65, 45], [54, 55], [11, 55]])
    box(ctx, '#d79c6c', 5, 47, 56, 3)
    box(ctx, '#f6c592', 8, 46, 51, 1)
    box(ctx, '#a37157', 16, 51, 36, 1)
    box(ctx, '#755d4f', 31, 1, 2, 48)
    shape(ctx, '#f4e6bf', [[29, 2], [29, 41], [5, 41]])
    shape(ctx, '#eee1ac', [[34, 8], [57, 38], [34, 38]])
    pixelLine(ctx, '#ffedd0', 28, 10, 8, 40)
  })
  const umbrella = cachedSprite(60, 22, (ctx) => {
    shape(ctx, '#8e586d', [[0, 17], [8, 6], [22, 1], [36, 1], [51, 7], [57, 18], [41, 15], [28, 18], [14, 15]], 1)
    shape(ctx, '#d18a93', [[6, 10], [20, 2], [34, 2], [43, 6], [28, 5], [19, 9]], 1)
    pixelLine(ctx, '#e6acb0', 22, 2, 8, 14)
    pixelLine(ctx, '#c18a9d', 28, 3, 28, 16)
  })
  const coastalCar = cachedSprite(40, 25, (ctx) => {
    shape(ctx, '#bd5263', [[1, 18], [8, 6], [25, 4], [35, 11], [39, 21], [0, 21]], 1)
    box(ctx, '#754b65', 10, 9, 17, 5)
    box(ctx, '#ecad9e', 11, 7, 14, 1)
    box(ctx, '#ffba91', 2, 17, 6, 2)
    box(ctx, '#ffba91', 30, 17, 6, 2)
    box(ctx, '#e18579', 9, 20, 21, 1)
    box(ctx, '#352940', 5, 22, 7, 3)
    box(ctx, '#352940', 29, 22, 7, 3)
  })
  const rooftopCat = cachedSprite(40, 33, (ctx) => {
    shape(ctx, '#171a2c', [[3, 27], [4, 18], [10, 11], [17, 13], [22, 19], [22, 27], [30, 28], [30, 31], [0, 30]], 1)
    box(ctx, '#171a2c', 3, 7, 12, 10)
    shape(ctx, '#171a2c', [[3, 10], [3, 1], [8, 6], [10, 7], [15, 2], [16, 10]], 1)
    box(ctx, '#b7a8c5', 5, 10, 2, 1)
    pixelLine(ctx, '#79708a', 4, 3, 7, 6)
    pixelLine(ctx, '#595670', 11, 12, 18, 16)
  })
  const fishingBoat = cachedSprite(110, 57, (ctx) => {
    shape(ctx, '#595f71', [[1, 41], [98, 41], [106, 34], [99, 52], [15, 52]])
    box(ctx, '#bd756e', 6, 44, 96, 5)
    box(ctx, '#dfd1b8', 38, 18, 40, 23)
    box(ctx, '#9d7971', 34, 14, 48, 4)
    box(ctx, '#67848d', 45, 23, 10, 7)
    box(ctx, '#67848d', 59, 23, 10, 7)
    box(ctx, '#606e76', 35, 2, 2, 39)
    pixelLine(ctx, '#8f8984', 37, 9, 80, 34)
    box(ctx, '#f0dfbf', 39, 19, 37, 1)
    box(ctx, '#e6c0a1', 14, 44, 72, 1)
  })

  function palm(ctx, x, y, size) {
    const crownX = x + size * .10
    const crownY = y - size
    for (let step = 0; step < size; step += 3) {
      const bend = Math.sin(step / size * 1.5) * size * .1
      box(ctx, '#39263d', x + bend, y - step, Math.max(2, size * .075 - step * .03), 3)
      if (step % 9 === 0) box(ctx, '#86536b', x + bend, y - step, 2, 1)
    }
    const fronds = [[-.70, .20, -.40, -.36], [-.61, -.10, -.31, -.42],
      [-.40, -.36, -.18, -.50], [-.15, -.49, -.03, -.55], [.16, -.42, .06, -.53],
      [.43, -.29, .26, -.45], [.69, -.03, .41, -.37], [.72, .22, .45, -.24],
      [.53, .42, .39, -.09], [-.48, .43, -.40, -.03]]
    for (let leaf = 0; leaf < fronds.length; leaf += 1) {
      const frond = fronds[leaf]
      const endX = crownX + frond[0] * size
      const endY = crownY + frond[1] * size
      const archX = crownX + frond[2] * size
      const archY = crownY + frond[3] * size
      let previousX = crownX
      let previousY = crownY
      for (let segment = 1; segment <= 18; segment += 1) {
        const part = segment / 18
        const remaining = 1 - part
        const leafX = remaining ** 2 * crownX + 2 * remaining * part * archX + part ** 2 * endX
        const leafY = remaining ** 2 * crownY + 2 * remaining * part * archY + part ** 2 * endY
        pixelLine(ctx, '#2c2037', previousX, previousY, leafX, leafY, part < .35 ? 2 : 1)
        if (leaf < 5 && segment < 12) pixelLine(ctx, '#89506b', previousX, previousY - 1, leafX, leafY - 1)
        const tangentX = remaining * (archX - crownX) + part * (endX - archX)
        const tangentY = remaining * (archY - crownY) + part * (endY - archY)
        const magnitude = Math.hypot(tangentX, tangentY) || 1
        const directionX = tangentX / magnitude
        const directionY = tangentY / magnitude
        const featherLength = Math.sin(part * Math.PI) ** .7 * size * (.10 + leaf % 3 * .01)
        for (let side = -1; side <= 1; side += 2) {
          const tipX = leafX + (-directionY * side + directionX * .42) * featherLength
          const tipY = leafY + (directionX * side + directionY * .42) * featherLength + size * .018
          const bendX = leafX * .4 + tipX * .6 - directionX * featherLength * .12
          const bendY = leafY * .4 + tipY * .6 - size * .008
          const shade = side < 0 ? '#37213f' : '#472640'
          pixelLine(ctx, shade, leafX, leafY, bendX, bendY, part < .6 ? 2 : 1)
          pixelLine(ctx, shade, bendX, bendY, tipX, tipY)
          if (leaf < 4 && side < 0 && segment % 3 === 0) {
            pixelLine(ctx, '#9e6078', leafX, leafY - 1, bendX, bendY - 1)
          }
        }
        previousX = leafX
        previousY = leafY
      }
    }
    box(ctx, '#37283e', crownX - 2, crownY + 1, 5, 5)
  }

  function scene(id, name, accent, background, foreground) {
    let layer
    return {
      id, name, accent,
      draw(ctx, timeSeconds = 0) {
        if (!layer) {
          layer = document.createElement('canvas')
          layer.width = width
          layer.height = height
          background(layer.getContext('2d'))
        }
        ctx.drawImage(layer, 0, 0)
        foreground(ctx, timeSeconds, layer)
      },
    }
  }

  const city = scene('night-city', '도시의 밤', '#eab2cc', (ctx) => {
    sky(ctx, ['#171c3c', '#23224d', '#322957', '#513362', '#753d6b', '#974b75', '#ba627c', '#d57b87'], 228, 101)
    stars(ctx, 401, 90, 122, ['#817696', '#b294ac', '#e4b7bf'])
    stripedSun(ctx, 475, 127, 33, '#efb6a3', '#d18a8a')
    cloud(ctx, 104, 133, 88, '#9a5b74', '#774d70')
    cloud(ctx, 519, 165, 63, '#ac6b78', '#8d5a78')
    cloud(ctx, 316, 100, 38, '#60456a', '#573c62')
    buildings(ctx, 212, 207, 30, 80, ['#66516c', '#7f5b73', '#8b6075'], ['#b47886', '#be8393', '#916581'], 1)
    buildings(ctx, 216, 12, 26, 87, ['#393553', '#51415a', '#604760'], ['#b47580', '#d5928d', '#916581'], 0)
    buildings(ctx, 227, 89, 43, 132, ['#24263f', '#2d2b46', '#39314a'], ['#e5a27e', '#ad7484', '#675674', '#f7c294'], 4)
    for (const [x, y, label, light, edge] of [[101, 171, '24H', '#74e0d1', '#2b6d83'],
      [346, 150, 'HOTEL', '#f7a7bc', '#8c4c74'], [528, 179, 'CAFE', '#ffb87f', '#88565e']]) {
      const w = label.length * 6 + 10
      box(ctx, edge, x - 4, y - 3, w + 8, 22)
      pixelGlow(ctx, x + w / 2, y + 9, w + 15, 26, light, .22)
      box(ctx, light, x, y, w, 15)
      box(ctx, '#292642', x + 1, y + 1, w - 2, 13)
      pixelText(ctx, label, x + 4, y + 3, light)
    }
    box(ctx, '#44425e', 252, 110, 2, 109)
    box(ctx, '#db8492', 253, 105, 3, 2)
    for (let y = 124; y < 216; y += 13) box(ctx, '#645067', 253, y, 11, 1)
    water(ctx, 223, ['#474161', '#393957', '#2e3253', '#222a49', '#1b213c'], ['#61506f', '#504768', '#3c405f', '#79546d'], 29)
    reflect(ctx, 477, 224, 120, 115, ['#9a627c', '#b87383', '#e39a8e'], 800, 144)
    reflect(ctx, 115, 233, 96, 45, ['#326576', '#45868f', '#65aeba'], 802)
    reflect(ctx, 360, 231, 86, 52, ['#68496d', '#a35483', '#b7769a'], 805)
    reflect(ctx, 543, 235, 95, 60, ['#785165', '#ba786e', '#cf9681'], 806)
    box(ctx, '#171d35', 0, 227, 640, 11)
    box(ctx, '#98697d', 0, 227, 640, 2)
    box(ctx, '#23253e', 0, 239, 640, 4)
    for (let x = 0; x < width; x += 28) {
      pixelLine(ctx, '#49425e', x, 230, x + 13, 241, 2)
      pixelLine(ctx, '#56465e', x + 13, 240, x + 27, 230, 1)
    }
    for (const x of [71, 232, 399, 566]) {
      box(ctx, '#22253d', x, 240, 12, 37)
      box(ctx, '#42405a', x + 10, 243, 3, 27)
      box(ctx, '#252841', x - 4, 274, 22, 5)
      for (let y = 248; y < 274; y += 6) box(ctx, '#302e48', x + 1, y, 7, 1)
    }
    for (let x = 7; x < width; x += 21) {
      box(ctx, '#28263f', x, 223, 2, 6)
      box(ctx, '#da9688', x, 222, 2, 2)
    }
  }, (ctx, time, layer) => {
    twinkle(ctx, time, cityStars, 8, 62, '#d7beda')
    ctx.globalAlpha = .18 + Math.sin(time * .48 + 1.7) * .055
    box(ctx, '#a3f3e1', 101, 171, 28, 1)
    ctx.globalAlpha = .20 + Math.sin(time * .39 + 3.1) * .07
    box(ctx, '#ffc0d2', 346, 150, 40, 1)
    ctx.globalAlpha = .14 + Math.sin(time * .44 + .7) * .06
    box(ctx, '#ffd1a6', 528, 179, 34, 1)
    ctx.globalAlpha = 1
    reflectedHorizon(ctx, layer, time, cityWater, 226, 110, .19)
    waterLight(ctx, time, cityWater, 114, 246, 82, 44, '#67c9d0', neonGlints, .56)
    waterLight(ctx, time + 2, cityWater, 366, 246, 82, 57, '#dd85c1', neonGlints, .58)
    waterLight(ctx, time + 4, cityWater, 546, 246, 82, 57, '#f5b48d', neonGlints, .61)
    ctx.save()
    ctx.clip(cityWater, 'evenodd')
    sparkleWater(ctx, time, 255, 104, '#b87c86', cityRipples)
    ctx.restore()
    const x = (412 + time * 34) % 835 - 176
    railcar(ctx, x, 208, cityTrainCars, '#a67581', '#6f4d6e', '#ffc88f')
    box(ctx, '#ffe7ac', x + 157, 215, 2, 2)
    box(ctx, '#f47280', x + 1, 216, 2, 2)
    box(ctx, '#161b30', 0, 339, 640, 6)
    box(ctx, '#665064', 0, 338, 640, 2)
    for (const post of foregroundPosts) {
      box(ctx, '#161b30', post, 327, 6, 33)
      box(ctx, '#8b5c70', post, 326, 7, 2)
    }
  })

  const lake = scene('forest-lake', '호수 위 보트', '#89d3ba', (ctx) => {
    sky(ctx, ['#8ec8d8', '#a4d3df', '#b6dce0', '#d1e8e0', '#e7eddc'], 208, 112)
    cloud(ctx, 145, 51, 70, '#edf3e7', '#cde5e1')
    cloud(ctx, 480, 69, 60, '#edf3e7', '#cde5e1')
    const ridge = [[0, 151], [56, 113], [94, 128], [163, 83], [213, 137], [303, 94], [352, 63],
      [417, 126], [459, 93], [530, 135], [597, 111], [640, 136], [640, 209], [0, 209]]
    shape(ctx, '#80aab3', ridge)
    shape(ctx, '#6b9bac', [[56, 113], [93, 127], [145, 178], [105, 160], [83, 143]])
    shape(ctx, '#789eb0', [[163, 83], [213, 137], [225, 176], [192, 155], [179, 122]])
    shape(ctx, '#648e9f', [[352, 63], [417, 126], [424, 148], [383, 129], [367, 88]])
    shape(ctx, '#759eac', [[459, 93], [530, 135], [550, 169], [512, 148], [476, 124]])
    shape(ctx, '#d9e9df', [[127, 108], [163, 83], [190, 113], [170, 106], [163, 115], [154, 103]])
    shape(ctx, '#e4eee2', [[317, 89], [352, 63], [390, 103], [359, 93], [351, 108], [339, 87]])
    shape(ctx, '#628f91', [[0, 132], [86, 160], [148, 191], [222, 155], [284, 176], [361, 154], [418, 127],
      [521, 174], [597, 134], [640, 143], [640, 210], [0, 210]])
    shape(ctx, '#497d78', [[0, 160], [66, 167], [125, 195], [211, 202], [332, 194], [452, 173], [528, 188],
      [582, 159], [640, 156], [640, 213], [0, 213]])
    const rand = random(199)
    for (let x = 0; x < width; x += 4 + rand() * 7) {
      const shore = 203 - (x < 120 ? (120 - x) / 8 : Math.max(0, x - 486) / 10)
      pine(ctx, x, shore, 10 + rand() * 35, '#3b7067', '#568276')
    }
    water(ctx, 205, ['#82b9b3', '#74b1ab', '#5fa6a3', '#559e9d', '#489294'], ['#a0cebf', '#6bc1b0', '#548e8e', '#b0dbce'], 43)
    for (const [x, span, spread] of [[86, 46, 124], [231, 21, 65], [436, 37, 134], [592, 55, 94]]) {
      reflect(ctx, x, 207, span, spread, ['#639d91', '#72aa9e', '#588e90'], x + 919, 86)
    }
    for (const [x, y, w] of [[110, 207, 94], [209, 213, 110], [380, 206, 103], [537, 206, 68]]) {
      box(ctx, '#c5e1d3', x, y, w, 1)
      box(ctx, '#9bcbbb', x + 15, y + 3, w * .62, 1)
    }
    shape(ctx, '#326e65', [[0, 265], [56, 277], [111, 307], [138, 360], [0, 360]])
    shape(ctx, '#497d6b', [[0, 277], [50, 287], [118, 330], [132, 360], [0, 360]])
    for (let x = 2; x < 91; x += 14) pine(ctx, x, 288 + x * 0.62, 34 + rand() * 52, '#24574f', '#3d7160', '#524b44')
    for (let index = 0; index < 46; index += 1) {
      const x = rand() * 106
      box(ctx, index % 3 === 0 ? '#d3c98e' : '#65927a', x, 313 + rand() * 42, 2 + rand() * 6, 2)
    }
    for (const [x, y, w, h] of [[126, 329, 41, 16], [99, 313, 32, 13], [64, 304, 26, 12], [16, 309, 39, 19], [595, 359, 65, 21], [635, 345, 62, 31]]) {
      rock(ctx, x, y, w, h, '#3d6d70', '#79a28d')
      box(ctx, '#8cc5b4', x + w * .3, y + 2, w * .48, 1)
    }
    for (let reed = 0; reed < 27; reed += 1) {
      const x = reed < 13 ? 80 + rand() * 56 : 556 + rand() * 77
      const y = reed < 13 ? 319 + rand() * 40 : 345 + rand() * 15
      const top = 9 + rand() * 26
      pixelLine(ctx, '#376b61', x, y, x - 6 + reed % 10, y - top)
      if (reed % 3 === 0) box(ctx, '#9d9464', x - 7 + reed % 10, y - top - 3, 3, 5)
    }
  }, (ctx, time, layer) => {
    reflectedHorizon(ctx, layer, time, lakeWater, 205, 114, .25)
    ctx.save()
    ctx.clip(lakeWater)
    sparkleWater(ctx, time, 218, 138, '#b7dfd1', lakeRipples)
    const x = 303 + Math.sin(time / 11) * 94
    const y = 260 + Math.sin(time * 2.1) * 1.3
    lakeWake(ctx, time, x, y)
    sailingBoat.draw(ctx, x - 27, y - 45)
    box(ctx, '#b77363', x + 6, y - 44, 12 + Math.sin(time * 4) * 2, 4)
    objectReflection(ctx, time, lakeWater, x - 27, y + 10, 66, 53, .28)
    ctx.restore()
  })

  const coast = scene('sunset-drive', '해안 드라이브', '#f4a17e', (ctx) => {
    sky(ctx, ['#51426f', '#73507d', '#a25c83', '#c97088', '#e88e89', '#f5a37f', '#ffd091', '#ffe3a5'], 198, 13)
    pixelGlow(ctx, 168, 143, 82, 61, '#ffc594', .18)
    stripedSun(ctx, 168, 143, 49, '#ffe9b1', '#eeaa86')
    cloud(ctx, 65, 76, 55, '#cf8791', '#9e6c89')
    cloud(ctx, 498, 126, 100, '#eda68d', '#d7888c')
    water(ctx, 197, ['#d8918d', '#bd8392', '#9c778c', '#876c87', '#6c6283'], ['#eaa991', '#ba8a9c', '#a2778c', '#e6a288'], 77)
    reflect(ctx, 166, 197, 151, 116, ['#edaa8e', '#ffc493', '#f4b69a', '#cb8f8e'], 178, 152)
    shape(ctx, '#746382', [[0, 200], [30, 182], [52, 188], [70, 179], [90, 203], [108, 215], [0, 215]])
    shape(ctx, '#8d6e84', [[0, 200], [30, 182], [52, 188], [66, 181], [48, 197], [0, 205]])
    shape(ctx, '#a4808f', [[11, 214], [43, 208], [92, 210], [80, 214], [33, 218]])
    shape(ctx, '#815e78', [[298, 200], [356, 180], [386, 183], [427, 160], [473, 157], [511, 111], [548, 100],
      [582, 69], [640, 67], [640, 360], [185, 360], [223, 298], [253, 264]])
    shape(ctx, '#a46f79', [[640, 107], [609, 110], [590, 133], [556, 146], [522, 172], [486, 190], [516, 233],
      [553, 255], [594, 360], [640, 360]])
    shape(ctx, '#c18180', [[640, 112], [607, 116], [589, 136], [554, 149], [524, 177], [491, 193],
      [517, 190], [563, 170], [595, 147], [640, 139]])
    shape(ctx, '#81576f', [[640, 151], [580, 177], [550, 185], [511, 205], [523, 214], [567, 208], [595, 187], [640, 183]])
    shape(ctx, '#ba7d81', [[640, 194], [597, 203], [578, 223], [542, 235], [553, 246], [584, 238], [616, 218], [640, 222]])
    shape(ctx, '#714c67', [[641, 237], [608, 247], [587, 276], [578, 297], [598, 311], [620, 291], [640, 292]])
    shape(ctx, '#543150', [[640, 156], [617, 172], [593, 181], [573, 201], [552, 208],
      [567, 217], [600, 200], [620, 183], [640, 178]])
    shape(ctx, '#623653', [[640, 230], [613, 241], [599, 263], [584, 283], [583, 294],
      [600, 300], [614, 276], [640, 263]])
    pixelLine(ctx, '#da9b8e', 641, 113, 609, 120)
    pixelLine(ctx, '#c58b85', 609, 120, 580, 143)
    pixelLine(ctx, '#d39388', 578, 145, 549, 158)
    pixelLine(ctx, '#bc7f80', 633, 190, 609, 199)
    pixelLine(ctx, '#be8581', 609, 199, 591, 214)
    shape(ctx, '#a97c80', [[183, 360], [234, 286], [301, 257], [382, 231], [432, 212], [468, 188], [496, 187],
      [498, 200], [460, 229], [417, 251], [357, 284], [335, 311], [315, 360]])
    shape(ctx, '#494254', [[207, 360], [241, 308], [275, 285], [357, 253], [413, 234], [460, 208], [477, 191],
      [487, 191], [473, 214], [431, 242], [381, 269], [314, 302], [281, 360]])
    shape(ctx, '#edd09c', [[234, 360], [261, 316], [270, 309], [263, 322], [243, 360]])
    shape(ctx, '#c5a68a', [[282, 299], [318, 281], [329, 276], [333, 277], [298, 294], [287, 302]])
    shape(ctx, '#c5a68a', [[349, 268], [378, 257], [381, 258], [355, 270]])
    shape(ctx, '#edbd94', [[199, 360], [231, 310], [267, 281], [354, 249], [410, 229], [439, 213], [439, 216],
      [412, 232], [355, 253], [269, 285], [233, 314], [205, 360]])
    for (const [x, y, size] of [[209, 342, 15], [237, 306, 11], [274, 280, 8], [318, 262, 6], [365, 245, 5], [403, 233, 4]]) {
      box(ctx, '#684b62', x, y, 3, size)
      box(ctx, '#ffe2ae', x - 1, y + 1, 4, 2)
    }
    for (const [x, y, size] of [[331, 313, 51], [411, 257, 34], [469, 221, 22]]) {
      const pale = size > 30 ? '#ffce9a' : '#fed6ad'
      box(ctx, '#63465b', x, y - size, 3, size)
      box(ctx, '#9c6d74', x, y - size, 1, size)
      box(ctx, '#76506b', x - size * .20, y - size, size * .20 + 2, 2)
      box(ctx, pale, x - size * .20, y - size + 1, 6, 2)
    }
    const rand = random(614)
    for (let index = 0; index < 100; index += 1) {
      const x = 503 + rand() * 137
      const y = 156 + rand() * 199
      if (y > 209 && x < 527 + (y - 209) / 3) continue
      box(ctx, index % 2 ? '#714e6b' : '#be8586', x, y, 3 + rand() * 12, 2)
    }
    palm(ctx, 558, 270, 57)
    palm(ctx, 605, 317, 84)
    palm(ctx, 509, 231, 33)
    shape(ctx, '#663c63', [[555, 268], [562, 268], [610, 296], [627, 306], [614, 306], [579, 285]])
    shape(ctx, '#704664', [[603, 316], [610, 316], [640, 332], [640, 340], [626, 331]])
    for (const [x, y, w, h] of [[583, 357, 62, 20], [548, 331, 54, 24], [640, 339, 78, 32], [527, 321, 34, 17], [163, 360, 83, 34]]) {
      rock(ctx, x, y, w, h, '#6f4e65', '#b87f7e')
    }
    for (const [x, y, w] of [[493, 278, 29], [523, 296, 35], [548, 311, 23], [568, 335, 31], [614, 354, 34]]) {
      pixelLine(ctx, '#603b59', x, y, x + w * .75, y - 3)
      pixelLine(ctx, '#71425d', x + w * .75, y - 3, x + w, y - 8)
      box(ctx, '#c38a87', x - 2, y - 2, w * .23, 1)
      box(ctx, '#9b6574', x + 6, y + 3, w * .4, 1)
    }
  }, (ctx, time) => {
    waterLight(ctx, time, seaWater, 164, 198, 160, 116, '#ffe3aa', sunGlints, .92)
    waterLight(ctx, time + 1.8, seaWater, 167, 198, 160, 139, '#f3ac96', neonGlints, .54)
    const progress = Math.min(1, 0.33 + time / 19)
    const x = 481 - progress * 258
    const y = 193 + progress ** 1.37 * 148
    const size = 8 + progress * 26
    disc(ctx, '#383746', x, y + 5, size * 0.69, size * 0.18)
    coastalCar.draw(ctx, x - size * .58, y - size * .47, size * 1.18, size * .74)
    gull(ctx, 215 + time * 7, 110 + Math.sin(time * 1.3) * 4, time, '#695577')
    gull(ctx, 247 + time * 5, 126 + Math.sin(time + 2) * 5, time + .5, '#786080')
  })

  const rain = scene('rainy-alley', '비 오는 골목', '#9b9bdc', (ctx) => {
    sky(ctx, ['#333552', '#3d4265', '#505374', '#656181', '#786c8b'], 220, 114)
    buildings(ctx, 219, 603, 44, 109, ['#464460', '#51506d', '#595673'], ['#85758d', '#9d819b', '#c09aa1'], 0)
    shape(ctx, '#423b54', [[0, 167], [640, 170], [640, 360], [0, 360]])
    shape(ctx, '#57506a', [[242, 216], [388, 214], [576, 360], [72, 360]])
    shape(ctx, '#65556b', [[263, 220], [348, 218], [466, 360], [14, 360]])
    shape(ctx, '#1e223f', [[0, 29], [169, 102], [218, 128], [218, 248], [0, 329]])
    shape(ctx, '#29243e', [[0, 52], [162, 116], [162, 262], [0, 324]])
    shape(ctx, '#1f233f', [[640, 19], [450, 91], [404, 129], [404, 238], [640, 329]])
    shape(ctx, '#2d2545', [[640, 63], [479, 106], [445, 135], [445, 256], [640, 325]])
    for (const [x, y, w, h] of [[24, 89, 28, 31], [75, 104, 25, 26], [129, 115, 20, 24],
      [477, 124, 21, 22], [556, 96, 30, 28], [610, 78, 30, 42]]) {
      box(ctx, '#242237', x - 2, y - 2, w + 4, h + 4)
      box(ctx, '#58475f', x, y, w, h)
      box(ctx, '#79627a', x + 2, y + h * .42, w - 4, 1)
      box(ctx, '#343049', x + w * .51, y, 2, h)
      box(ctx, '#75617d', x - 3, y + h + 1, w + 6, 2)
    }
    pixelLine(ctx, '#474059', 88, 92, 321, 118)
    pixelLine(ctx, '#474059', 321, 118, 540, 84)
    pixelLine(ctx, '#514462', 164, 119, 337, 149)
    pixelLine(ctx, '#514462', 337, 149, 491, 122)
    shape(ctx, '#665067', [[5, 159], [180, 173], [180, 197], [5, 191]])
    shape(ctx, '#ca888c', [[9, 171], [176, 178], [176, 184], [9, 182]])
    box(ctx, '#25233b', 26, 194, 127, 72)
    box(ctx, '#b36f74', 38, 199, 103, 64)
    box(ctx, '#e5a281', 42, 201, 95, 52)
    for (const x of [65, 89, 113]) box(ctx, '#734d66', x, 197, 4, 63)
    box(ctx, '#734d66', 42, 223, 95, 3)
    for (const x of [46, 72, 94, 120]) {
      box(ctx, '#ffca94', x, 203, 12, 3)
      box(ctx, '#bd786e', x, 245, 13, 4)
      box(ctx, '#f1ad81', x + 1, 237, 4, 8)
    }
    box(ctx, '#805369', 53, 133, 84, 31)
    box(ctx, '#f2b194', 57, 137, 76, 23)
    pixelGlow(ctx, 96, 149, 72, 38, '#ff9a8e', .19)
    box(ctx, '#ffc69b', 57, 137, 76, 1)
    pixelText(ctx, 'RAMEN', 78, 145, '#61445b')
    box(ctx, '#30283f', 499, 183, 113, 81)
    box(ctx, '#456074', 506, 189, 101, 70)
    box(ctx, '#8bc2b4', 512, 196, 86, 60)
    for (const x of [534, 556, 578]) box(ctx, '#5a7884', x, 193, 4, 66)
    box(ctx, '#5a7884', 512, 220, 86, 4)
    for (let x = 521; x < 597; x += 11) {
      disc(ctx, '#5a6a81', x, 241 + x % 4, 3, 3, 1)
      box(ctx, '#aed8bd', x - 4, 204, 8, 2)
    }
    box(ctx, '#79b0a9', 527, 139, 88, 24)
    box(ctx, '#41677a', 532, 142, 78, 18)
    pixelGlow(ctx, 569, 153, 68, 37, '#67ddd1', .19)
    box(ctx, '#b0f4dc', 532, 142, 78, 1)
    pixelText(ctx, 'RECORDS', 550, 147, '#afdfcb')
    for (const [x, y] of [[202, 190], [235, 184], [424, 186], [452, 178]]) {
      box(ctx, '#53405d', x, y, 16, 37)
      box(ctx, '#a47d9b', x + 3, y + 4, 9, 19)
    }
    const rand = random(7)
    reflect(ctx, 99, 267, 93, 130, ['#976381', '#bb7b8a', '#d99590'], 710, 96)
    reflect(ctx, 545, 266, 94, 137, ['#527f88', '#6a9f9f', '#99b4a9'], 720, 99)
    for (let index = 0; index < 150; index += 1) {
      const y = 250 + rand() * 110
      const x = rand() * width
      const color = x < 185 ? '#a16f85' : x > 471 ? '#698f9d' : '#786481'
      box(ctx, color, x, y, 1 + rand() * 23, rand() < 0.13 ? 2 : 1)
    }
    for (const [x, y] of [[252, 233], [392, 235], [236, 253], [414, 261], [214, 285], [448, 293], [174, 324], [484, 333]]) {
      box(ctx, '#c4a0a7', x, y, 15, 2)
    }
    for (const [x, y, w] of [[255, 263, 36], [325, 309, 73], [438, 328, 57]]) {
      disc(ctx, '#55435f', x, y, w, 5, 1)
      box(ctx, '#a68195', x - w * .5, y - 1, w * .63, 1)
      box(ctx, '#81667e', x + 3, y + 1, w * .67, 1)
    }
  }, (ctx, time, layer) => {
    reflectedHorizon(ctx, layer, time, wetGround, 265, 95, .17)
    waterLight(ctx, time, wetGround, 92, 267, 92, 133, '#f6a59c', neonGlints, .49)
    waterLight(ctx, time + 2.3, wetGround, 544, 267, 93, 141, '#75d6cd', neonGlints, .48)
    rainRings(ctx, time)
    const x = 323 + time * 2.9
    const y = 292 - time * 0.5
    disc(ctx, '#53475f', x + 1, y + 20, 19, 5)
    box(ctx, '#332b44', x - 6, y + 2, 5, 16 + Math.sin(time * 6) * 3)
    box(ctx, '#352f4a', x + 1, y + 3, 5, 16 - Math.sin(time * 6) * 3)
    box(ctx, '#5a4f74', x - 7, y - 10, 13, 15)
    box(ctx, '#a9848b', x - 4, y - 17, 8, 7)
    box(ctx, '#c2a5aa', x + 3, y - 24, 1, 24)
    umbrella.draw(ctx, x - 27, y - 39)
    for (let drop = 0; drop < raindrops.length; drop += 1) {
      const [startX, startY] = raindrops[drop]
      const px = ((startX * width - time * (24 + drop % 8)) % width + width) % width
      const py = (startY * height + time * (97 + drop % 40)) % height
      const color = drop % 5 === 0 ? '#c3bcd0' : '#8587aa'
      box(ctx, color, px, py, 1, 3 + drop % 3)
      box(ctx, color, px - 1, py + 4, 1, 3)
      if (drop % 9 === 0 && py > 274) box(ctx, '#b2a6bd', px - 4, py + 9, 7, 1)
    }
  })

  const forest = scene('forest-train', '숲속 기차', '#b4c888', (ctx) => {
    sky(ctx, ['#d2d6af', '#dce0b6', '#e4dfb6', '#e9dfb6'], 240, 37)
    disc(ctx, '#f5e7b4', 135, 86, 44)
    cloud(ctx, 481, 68, 81, '#f5eac9', '#e7e2be')
    shape(ctx, '#a3ab81', [[0, 155], [93, 116], [139, 75], [208, 131], [255, 139], [307, 104], [363, 69],
      [471, 121], [528, 90], [640, 144], [640, 244], [0, 244]])
    shape(ctx, '#718e74', [[0, 153], [64, 129], [135, 157], [191, 198], [251, 182], [316, 150], [389, 169],
      [457, 194], [520, 145], [583, 129], [640, 164], [640, 255], [0, 255]])
    shape(ctx, '#8da37b', [[65, 131], [137, 159], [211, 197], [179, 192], [119, 167], [92, 160]])
    shape(ctx, '#899e76', [[316, 152], [390, 171], [460, 192], [427, 195], [365, 175], [337, 174]])
    const rand = random(152)
    for (let x = -5; x < 660; x += 8 + rand() * 13) {
      const y = 223 + rand() * 12
      const size = 25 + rand() * 63
      pine(ctx, x, y, size, '#4c735c', '#668967', '#5a6253')
    }
    for (let x = -3; x < 643; x += 15 + rand() * 11) {
      const y = 230 + rand() * 8
      pine(ctx, x, y, 16 + rand() * 40, '#3c624f', '#537454', '#635e4e')
    }
    shape(ctx, '#8a9869', [[0, 216], [105, 227], [214, 215], [349, 224], [465, 210], [640, 216], [640, 360], [0, 360]])
    shape(ctx, '#728b63', [[0, 278], [119, 265], [306, 277], [442, 248], [640, 266], [640, 360], [0, 360]])
    for (let index = 0; index < 209; index += 1) {
      box(ctx, ['#a0a16c', '#607d5f', '#9fad76', '#b9b583'][index % 4], rand() * width, 217 + rand() * 143, 2 + rand() * 13, 1)
    }
    box(ctx, '#665c57', 0, 248, 640, 16)
    for (let x = 0; x < width; x += 13) {
      box(ctx, '#4c4946', x, 246, 6, 16)
      box(ctx, '#8b7361', x + 1, 247, 4, 13)
    }
    box(ctx, '#d0b683', 0, 249, width, 2)
    box(ctx, '#b29775', 0, 257, width, 2)
    for (let stone = 0; stone < 154; stone += 1) {
      const y = 246 + Math.floor(rand() * 22)
      if (y === 249 || y === 250 || y === 257 || y === 258) continue
      box(ctx, ['#836b56', '#a0896a', '#504c48'][stone % 3], rand() * width, y, 1 + rand() * 5, 1)
    }
    ctx.globalAlpha = .16
    shape(ctx, '#e8d896', [[136, 98], [151, 101], [335, 239], [301, 239]])
    shape(ctx, '#f4e7b1', [[145, 99], [157, 103], [398, 241], [372, 242]])
    ctx.globalAlpha = 1
    for (const [x, y, reach] of [[26, 279, 124], [112, 278, 63], [522, 278, 78], [604, 285, 137]]) {
      ctx.globalAlpha = .29
      shape(ctx, '#345646', [[x - 6, y], [x + 4, y], [x + reach, y + 45], [x + reach * .8, y + 49]])
      ctx.globalAlpha = 1
    }
    box(ctx, '#766859', 435, 217, 3, 31)
    box(ctx, '#313a3e', 432, 215, 9, 13)
    box(ctx, '#de9c65', 434, 218, 3, 3)
    box(ctx, '#875a57', 434, 223, 3, 2)
    for (const [x, y, size] of [[24, 337, 157], [79, 366, 124], [152, 359, 86], [531, 374, 96], [592, 366, 142], [646, 340, 164]]) {
      pine(ctx, x, y, size, '#2c524c', '#3f6a51', '#4d4c46')
    }
    for (let index = 0; index < 37; index += 1) {
      const x = rand() * width
      const y = 300 + rand() * 60
      box(ctx, '#b3a36c', x, y, 2, 3)
      box(ctx, '#e9d993', x, y, 2, 1)
    }
    for (const [x, y, w, h] of [[198, 357, 72, 22], [252, 338, 37, 16], [486, 359, 70, 30]]) {
      rock(ctx, x, y, w, h, '#4e6453', '#82906a')
    }
  }, (ctx, time) => {
    const x = 185 + time * 24
    ctx.globalAlpha = .22
    box(ctx, '#293f35', x + 6, 263, 174, 3)
    ctx.globalAlpha = 1
    railcar(ctx, x, 224, forestTrainCars, '#bc634b', '#8d4d45', '#f4d99a')
    box(ctx, '#354850', x + 150, 230, 33, 15)
    box(ctx, '#3e5656', x + 160, 221, 24, 20)
    box(ctx, '#293e49', x + 174, 215, 6, 12)
    box(ctx, '#e9c675', x + 183, 231, 3, 5)
    box(ctx, '#a65d46', x + 150, 241, 36, 3)
    for (const wheel of locomotiveWheels) disc(ctx, '#283b41', x + wheel, 246, 4, 4, 1)
    ctx.globalAlpha = .28
    box(ctx, '#e9c98c', x + 184, 242, 19, 1)
    box(ctx, '#e9c98c', x + 184, 245, 26, 1)
    ctx.globalAlpha = 1
    for (let puff = 0; puff < 5; puff += 1) {
      const drift = (puff * 12 + time * 11) % 61
      disc(ctx, puff % 2 ? '#d4d4b3' : '#e6dec1', x + 175 - drift * 1.60, 212 - drift * 0.60,
        3 + drift / 8, 2 + drift / 13)
    }
  })

  function cabin(ctx, x, y, size, wall, roof, window) {
    const w = size
    const h = Math.round(size * 0.53)
    box(ctx, wall, x, y - h, w, h)
    for (let row = 7; row < h; row += 6) {
      box(ctx, roof, x + 2, y - row, w - 4, 1)
    }
    box(ctx, '#796779', x + w - 4, y - h + 2, 3, h - 2)
    box(ctx, '#5b536a', x + w * 0.73, y - h - size * 0.33, size * 0.11, size * 0.41)
    box(ctx, '#d4d9ed', x + w * 0.72, y - h - size * 0.33, size * 0.13, 3)
    const peak = y - h - size * 0.36
    shape(ctx, roof, [[x - 8, y - h + 4], [x + w * 0.36, peak], [x + w + 7, y - h + 4]])
    shape(ctx, '#e9edf1', [[x - 9, y - h], [x + w * 0.36, peak - 4], [x + w + 8, y - h],
      [x + w, y - h + 1], [x + w * 0.36, peak + 6], [x, y - h + 4]])
    pixelLine(ctx, '#f8f1f5', x - 8, y - h, x + w * .36, peak - 4)
    pixelLine(ctx, '#c6c8de', x + w * .39, peak + 4, x + w + 4, y - h + 1)
    box(ctx, roof, x + w * 0.51, y - h * 0.57, w * 0.17, h * 0.57)
    for (const left of [x + w * 0.13, x + w * 0.74]) {
      box(ctx, roof, left - 2, y - h * .69 - 2, w * .14 + 4, h * .31 + 5)
      box(ctx, window, left, y - h * 0.69, w * 0.14, h * 0.31)
      box(ctx, wall, left + w * 0.06, y - h * 0.69, 2, h * 0.31)
      box(ctx, '#ffe3ae', left + 1, y - h * .66, w * .1, 2)
      box(ctx, '#d2cadf', left - 2, y - h * .36, w * .14 + 5, 2)
    }
    box(ctx, '#c5c9e2', x - 4, y - 2, w + 11, 3)
  }

  const snow = scene('snow-village', '눈 내리는 마을', '#b9c9ee', (ctx) => {
    sky(ctx, ['#546181', '#667393', '#7884a7', '#929bbb', '#adb2cc', '#bec2d7'], 223, 65)
    disc(ctx, '#ebe6e8', 119, 114, 24)
    shape(ctx, '#8794b1', [[0, 172], [63, 142], [121, 169], [207, 106], [248, 95], [283, 117], [331, 157],
      [433, 115], [465, 111], [527, 78], [596, 141], [640, 152], [640, 252], [0, 252]])
    shape(ctx, '#c9ccdf', [[172, 134], [207, 106], [248, 95], [271, 111], [254, 116], [235, 108], [220, 120],
      [201, 118]])
    shape(ctx, '#d4d8e7', [[471, 107], [527, 78], [568, 116], [545, 113], [528, 98], [512, 102], [495, 121]])
    shape(ctx, '#aab3ce', [[0, 186], [84, 183], [160, 170], [239, 188], [321, 168], [405, 191], [492, 167], [640, 185], [640, 360], [0, 360]])
    const rand = random(830)
    for (let x = 0; x < width; x += 10 + rand() * 14) pine(ctx, x, 212 + rand() * 12, 20 + rand() * 43, '#7785a3', '#adbad0')
    shape(ctx, '#d4daeb', [[0, 225], [134, 230], [242, 212], [352, 217], [494, 205], [640, 220], [640, 360], [0, 360]])
    shape(ctx, '#bdc6df', [[0, 285], [140, 263], [241, 271], [322, 251], [420, 254], [532, 235], [640, 242], [640, 360], [0, 360]])
    shape(ctx, '#dce0ed', [[0, 318], [150, 296], [266, 306], [408, 288], [531, 296], [640, 286], [640, 360], [0, 360]])
    shape(ctx, '#aaafcf', [[336, 258], [343, 258], [380, 272], [388, 296], [365, 324], [369, 360], [321, 360],
      [328, 334], [352, 305], [370, 290], [365, 277]])
    ctx.globalAlpha = .27
    shape(ctx, '#8594b8', [[89, 260], [148, 260], [195, 287], [162, 287]])
    shape(ctx, '#8899be', [[270, 255], [379, 255], [450, 290], [390, 295]])
    shape(ctx, '#8294b8', [[475, 246], [539, 246], [589, 272], [552, 276]])
    shape(ctx, '#7b8dad', [[18, 301], [27, 301], [105, 341], [87, 344]])
    shape(ctx, '#7b8dad', [[554, 305], [564, 305], [631, 338], [612, 340]])
    ctx.globalAlpha = 1
    shape(ctx, '#e7d2cb', [[348, 250], [359, 248], [378, 268], [400, 275], [382, 281], [354, 276]])
    shape(ctx, '#e1d3d9', [[278, 256], [294, 253], [323, 266], [333, 273], [293, 269]])
    pixelGlow(ctx, 304, 263, 37, 12, '#ffbd82', .41)
    pixelGlow(ctx, 352, 266, 33, 13, '#ffc68b', .45)
    pixelGlow(ctx, 108, 265, 21, 8, '#ffd4a0', .34)
    pixelGlow(ctx, 493, 251, 23, 9, '#ffcd97', .35)
    cabin(ctx, 89, 261, 59, '#a57e88', '#716679', '#fbd6a7')
    cabin(ctx, 268, 255, 111, '#9c707b', '#60566c', '#ffce96')
    cabin(ctx, 475, 246, 63, '#8c7387', '#615972', '#f5cca2')
    cabin(ctx, 405, 221, 32, '#8b8299', '#6e697f', '#efcea4')
    for (let post = 0; post < 8; post += 1) {
      const x = 162 + post * 10
      const y = 272 - post * 1.6
      box(ctx, '#7c738a', x, y - 9, 3, 11)
      box(ctx, '#ebe9f2', x - 1, y - 10, 4, 2)
      pixelLine(ctx, '#a293a2', x, y - 5, x + 10, y - 7)
    }
    for (const [x, y] of [[354, 273], [361, 282], [371, 290], [361, 299], [356, 309], [346, 321], [341, 335]]) {
      disc(ctx, '#8f9fc1', x, y, 4, 2, 1)
      box(ctx, '#e9e9f1', x - 2, y + 1, 4, 1)
    }
    for (const [x, y, size] of [[20, 339, 118], [80, 351, 82], [217, 298, 59], [556, 335, 101], [614, 345, 134]]) {
      pine(ctx, x, y, size, '#536982', '#a1b4cb', '#776b83')
    }
    for (let index = 0; index < 133; index += 1) {
      box(ctx, ['#f6f4f7', '#bbc4de', '#cbd5e7'][index % 3], rand() * width, 270 + rand() * 90, 1 + rand() * 6, 1)
    }
  }, (ctx, time) => {
    for (let flake = 0; flake < snowflakes.length; flake += 1) {
      const [startX, startY] = snowflakes[flake]
      const y = (startY * height + time * (7 + flake % 14)) % height
      const x = (startX * width + time * 5 + Math.sin(flake + time) * 5 + width) % width
      const size = flake % 9 === 0 ? 3 : 1
      box(ctx, flake % 3 === 0 ? '#f8f2f4' : '#e1e5ef', x, y, size, size)
    }
    for (let puff = 0; puff < 5; puff += 1) {
      const drift = (puff * 9 + time * 5.5) % 48
      disc(ctx, puff % 2 ? '#afb2c8' : '#c1c3d3', 354 + drift * 0.72, 158 - drift * 0.94,
        3 + drift / 8, 2 + drift / 14)
    }
  })

  const roof = scene('moonlit-roof', '달빛 옥상', '#c0bbef', (ctx) => {
    box(ctx, '#644963', 0, 0, width, height)
    sky(ctx, ['#1c213c', '#222743', '#292b4a', '#333251', '#3f395a', '#504061', '#644963'], 255, 826)
    pixelGlow(ctx, 392, 103, 79, 77, '#b9addb', .17)
    stars(ctx, 54, 127, 184, ['#727393', '#9a95b4', '#cfc1d3'])
    disc(ctx, '#d6cbe4', 392, 103, 54)
    disc(ctx, '#e8d9e9', 387, 97, 47)
    disc(ctx, '#c8bed8', 367, 88, 11, 8)
    disc(ctx, '#d1c5de', 409, 120, 17, 11)
    disc(ctx, '#d1c5de', 376, 128, 10, 9)
    disc(ctx, '#dbcee2', 416, 76, 10, 8)
    const crater = random(205)
    for (let grain = 0; grain < 186; grain += 1) {
      const ox = crater() * 78 - 39
      const oy = crater() * 82 - 41
      if (ox ** 2 + oy ** 2 > 1880) continue
      box(ctx, ['#d5c8df', '#f0e1eb', '#ded0e3'][grain % 3], 387 + ox, 97 + oy, grain % 4 ? 1 : 2, 1)
    }
    cloud(ctx, 381, 148, 105, '#64546e', '#4d4465')
    cloud(ctx, 128, 126, 85, '#453d60', '#393652')
    buildings(ctx, 252, 91, 15, 95, ['#292740', '#36334f', '#413751'], ['#6e586f', '#ac767a', '#b68a91'], 2)
    shape(ctx, '#22243c', [[0, 231], [99, 221], [149, 246], [246, 234], [246, 360], [0, 360]])
    shape(ctx, '#292840', [[0, 256], [106, 239], [203, 269], [315, 252], [640, 272], [640, 360], [0, 360]])
    shape(ctx, '#35344b', [[0, 275], [111, 263], [224, 289], [353, 268], [539, 291], [640, 281], [640, 360], [0, 360]])
    shape(ctx, '#171c34', [[204, 293], [325, 264], [534, 283], [640, 287], [640, 360], [244, 360]])
    shape(ctx, '#3d3a53', [[198, 288], [325, 260], [534, 279], [640, 283], [640, 289], [524, 287], [327, 268], [198, 296]])
    shape(ctx, '#6f5e76', [[197, 287], [324, 259], [534, 278], [640, 282], [640, 284], [533, 280], [324, 261], [197, 289]])
    box(ctx, '#353148', 39, 246, 58, 43)
    box(ctx, '#51425f', 34, 241, 65, 7)
    for (let y = 252; y < 275; y += 5) box(ctx, '#22253e', 45, y, 44, 2)
    box(ctx, '#807087', 35, 241, 58, 1)
    pixelLine(ctx, '#4d465f', 82, 285, 194, 273, 2)
    pixelLine(ctx, '#322f4a', 192, 273, 192, 231, 2)
    box(ctx, '#746b80', 189, 229, 10, 3)
    box(ctx, '#302e48', 535, 239, 10, 43)
    box(ctx, '#66536f', 531, 235, 18, 5)
    box(ctx, '#292941', 118, 205, 3, 62)
    box(ctx, '#55516c', 102, 207, 37, 2)
    box(ctx, '#55516c', 110, 217, 26, 2)
    for (let x = 106; x < 138; x += 7) box(ctx, '#4c4b67', x, 203, 1, 14)
    for (let line = 0; line < 8; line += 1) {
      box(ctx, '#25263d', 271 + line * 8, 305 + line * 5, 270 - line * 12, 1)
    }
    for (const [x, y, w] of [[479, 296, 18], [305, 297, 16], [575, 308, 31], [399, 325, 23], [536, 342, 40]]) {
      box(ctx, '#34374e', x, y, w, 1)
      box(ctx, '#30334a', x + 4, y + 2, w * .72, 1)
    }
  }, (ctx, time) => {
    ctx.save()
    ctx.clip(roofSky, 'evenodd')
    twinkle(ctx, time, roofStars, 7, 75, '#e3d5ef')
    ctx.restore()
    const x = 331
    const y = 260
    const tail = Math.sin(time * 2.1) * 3
    rooftopCat.draw(ctx, x - 16, y - 27)
    pixelLine(ctx, '#171a2c', x + 8, y + 1, x + 18, y + 1, 3)
    pixelLine(ctx, '#171a2c', x + 18, y + 1, x + 24, y - 5, 3)
    pixelLine(ctx, '#171a2c', x + 24, y - 5, x + 25, y - 12 + tail, 3)
    if (Math.floor(time * 2) % 4 < 2) box(ctx, '#ce7e9b', 119, 202, 2, 2)
  })

  function blossom(ctx, x, y, size, seed) {
    const rand = random(seed)
    shape(ctx, '#694858', [[x - 7, y], [x - 4, y - size * 0.58], [x - size * 0.24, y - size * 0.78],
      [x - size * 0.20, y - size * 0.79], [x - 1, y - size * 0.66], [x + size * 0.12, y - size * 0.88],
      [x + size * 0.15, y - size * 0.87], [x + 5, y - size * 0.49], [x + size * 0.35, y - size * 0.68],
      [x + size * 0.38, y - size * 0.67], [x + 5, y - size * 0.41], [x + 8, y]])
    pixelLine(ctx, '#ac7784', x - 4, y - 3, x - size * .021, y - size * .55)
    pixelLine(ctx, '#93637b', x - 3, y - size * .47, x + size * .29, y - size * .67)
    for (let cluster = 0; cluster < 39; cluster += 1) {
      const px = x + (rand() - 0.5) * size * 1.14
      const py = y - size * (0.59 + rand() * 0.34) - 0.23 * (size * 0.45 - Math.abs(x - px))
      const radius = size * (0.065 + rand() * 0.14)
      disc(ctx, ['#ba6e8f', '#cd809e', '#e292ad', '#efa6b7', '#ec9cb3'][cluster % 5], px, py, radius, radius * 0.56)
      disc(ctx, ['#f5b5c7', '#f1a5bb', '#f7c3cf'][cluster % 3], px - 3, py - radius * 0.3, radius * 0.71, radius * 0.24)
      if (cluster % 4 === 0) {
        box(ctx, '#ffe0e2', px - radius * .43, py - radius * .23, 4, 1)
        box(ctx, '#f8cddb', px - radius * .41 + 2, py - radius * .24 - 1, 2, 4)
      }
    }
    for (let detail = 0; detail < 54; detail += 1) {
      const px = x + (rand() - 0.5) * size * 1.18
      const py = y - size * (0.62 + rand() * 0.31)
      box(ctx, detail % 2 ? '#f3bbcb' : '#b87697', px, py, 2, 2)
    }
  }

  const blossoms = scene('cherry-river', '벚꽃 강변', '#e5a6c4', (ctx) => {
    sky(ctx, ['#a6c7d9', '#b9cede', '#ccd8e4', '#e0dde8', '#efe2e6'], 201, 123)
    cloud(ctx, 343, 54, 77, '#f2e5e9', '#d9dbe7')
    shape(ctx, '#a0adb8', [[0, 168], [96, 137], [166, 144], [252, 117], [336, 152], [401, 125], [468, 144],
      [520, 127], [640, 148], [640, 216], [0, 216]])
    shape(ctx, '#94a2ac', [[0, 191], [132, 163], [206, 187], [310, 169], [410, 176], [496, 158], [640, 177], [640, 216], [0, 216]])
    const rand = random(38)
    for (let x = 0; x < width; x += 18) {
      disc(ctx, ['#a88fae', '#c192b0', '#d4a4ba'][Math.floor(rand() * 3)], x, 188 - rand() * 13, 14, 6)
    }
    water(ctx, 196, ['#9aadc1', '#8da9bf', '#85a2bb', '#7995b3', '#7189aa'], ['#b4bfd0', '#b0a9c3', '#7897b3', '#deb2c5'], 29)
    reflect(ctx, 91, 205, 72, 101, ['#c69eb9', '#bba7c1', '#d1afc0'], 39)
    reflect(ctx, 571, 208, 88, 134, ['#b9a2bc', '#c09ab5', '#ddbac8'], 40)
    box(ctx, '#b4bacb', 218, 201, 120, 1)
    for (const x of [401, 479, 551, 621]) {
      box(ctx, '#927f96', x, 198, 7, 20)
      box(ctx, '#a599aa', x + 5, 198, 3, 15)
      box(ctx, '#c2aabd', x - 3, 213, 14, 2)
    }
    box(ctx, '#8e8199', 374, 193, 267, 6)
    box(ctx, '#e2bec7', 374, 191, 267, 2)
    for (let x = 382; x < width; x += 10) box(ctx, '#9d879f', x, 184, 2, 9)
    box(ctx, '#a8859a', 374, 184, 266, 1)
    shape(ctx, '#776884', [[0, 274], [113, 269], [206, 288], [322, 306], [471, 304], [640, 281], [640, 360], [0, 360]])
    shape(ctx, '#bd9faa', [[0, 291], [117, 280], [218, 300], [344, 324], [484, 319], [640, 298], [640, 360], [0, 360]])
    shape(ctx, '#d3b8bd', [[0, 310], [112, 295], [217, 317], [345, 341], [490, 337], [640, 316], [640, 342], [485, 360],
      [345, 360], [216, 340], [108, 318], [0, 331]])
    ctx.globalAlpha = .28
    shape(ctx, '#966f91', [[86, 299], [96, 300], [241, 333], [289, 352], [242, 349], [157, 325]])
    shape(ctx, '#8d7197', [[580, 307], [592, 309], [640, 341], [640, 357], [599, 333]])
    shape(ctx, '#a0829e', [[171, 292], [178, 293], [263, 315], [287, 325], [261, 322]])
    ctx.globalAlpha = 1
    for (const [x, y, w] of [[16, 315, 44], [124, 310, 31], [197, 322, 54], [298, 343, 42], [386, 352, 30], [490, 344, 55], [577, 336, 32]]) {
      pixelLine(ctx, '#b392a5', x, y, x + w, y + 2)
      box(ctx, '#e5c5c9', x + 3, y + 4, w * .72, 1)
    }
    for (const [x, y] of [[134, 277], [179, 286], [228, 298], [270, 306]]) {
      box(ctx, '#8b788b', x, y - 12, 3, 14)
      box(ctx, '#c6a8ba', x - 1, y - 12, 5, 2)
    }
    pixelLine(ctx, '#857184', 134, 266, 271, 295, 2)
    blossom(ctx, 89, 306, 238, 609)
    blossom(ctx, 585, 309, 208, 930)
    blossom(ctx, 173, 293, 113, 59)
    for (let index = 0; index < 112; index += 1) {
      box(ctx, ['#eac6cc', '#ca93ad', '#b68ba2'][index % 3], rand() * width, 303 + rand() * 57, 2 + rand() * 4, 1)
    }
  }, (ctx, time, layer) => {
    reflectedHorizon(ctx, layer, time, riverWater, 197, 98, .22)
    ctx.save()
    ctx.clip(riverWater, 'evenodd')
    sparkleWater(ctx, time, 211, 55, '#d2c1d3', riverRipples)
    ctx.restore()
    for (let index = 0; index < petals.length; index += 1) {
      const [startX, startY] = petals[index]
      const x = (startX * width + time * (7 + index % 6) + Math.sin(index + time) * 7 + width) % width
      const y = (startY * 304 + time * (4 + index % 6)) % 314
      box(ctx, index % 2 === 0 ? '#f9c9d2' : '#df99b1', x, y, 2 + (Math.sin(time * 3 + index) > 0 ? 2 : 0), 2)
    }
  })

  const observatory = scene('star-observatory', '별빛 관측소', '#adb9f2', (ctx) => {
    sky(ctx, ['#131831', '#191e3a', '#222646', '#2d2c51', '#3c355e', '#503e66', '#6d4d70'], 266, 63)
    pixelGlow(ctx, 255, 143, 119, 33, '#7e609e', .1)
    pixelGlow(ctx, 458, 91, 97, 31, '#546999', .11)
    const rand = random(328)
    for (let index = 0; index < 1039; index += 1) {
      const x = rand() * width
      const y = 233 - x * 0.31 + (rand() - 0.5) * (36 + rand() * 74)
      box(ctx, ['#3e3b64', '#535079', '#63587e', '#77709a', '#343a63'][index % 5], x, y, index % 9 === 0 ? 2 : 1, 1)
    }
    for (let index = 0; index < 410; index += 1) {
      const x = rand() * width
      const y = 228 - x * .30 + Math.sin(x / 37) * 6 + (rand() - .5) * 26
      box(ctx, ['#44466f', '#6f6289', '#84729c', '#5f5780'][index % 4], x, y, 1, 1)
    }
    stars(ctx, 16, 220, 224, ['#8b8fb4', '#aaaacc', '#d7c8e4', '#e5d6e8'])
    shape(ctx, '#343550', [[0, 235], [69, 201], [121, 215], [165, 179], [236, 217], [305, 194], [360, 208],
      [412, 155], [473, 186], [505, 177], [567, 213], [640, 207], [640, 360], [0, 360]])
    shape(ctx, '#252e48', [[0, 248], [100, 224], [192, 256], [256, 240], [340, 259], [423, 213], [480, 241], [640, 211],
      [640, 360], [0, 360]])
    shape(ctx, '#303653', [[423, 214], [479, 241], [552, 235], [518, 248], [462, 250], [436, 232]])
    shape(ctx, '#41405d', [[165, 180], [237, 217], [213, 219], [183, 203], [165, 194]])
    shape(ctx, '#23273e', [[0, 299], [86, 289], [189, 275], [236, 261], [354, 258], [421, 267], [524, 289], [640, 281],
      [640, 360], [0, 360]])
    shape(ctx, '#3b3b52', [[0, 337], [93, 319], [153, 321], [230, 302], [276, 311], [361, 283], [448, 298], [569, 315],
      [640, 307], [640, 360], [0, 360]])
    for (let index = 0; index < 110; index += 1) {
      box(ctx, ['#53516b', '#2e3049', '#676077'][index % 3], rand() * width, 314 + rand() * 46, 3 + rand() * 15, 1)
    }
    box(ctx, '#66637e', 269, 213, 119, 63)
    box(ctx, '#4a4a68', 362, 213, 26, 63)
    box(ctx, '#79748d', 264, 212, 129, 6)
    disc(ctx, '#aba3b7', 329, 212, 64, 62)
    shape(ctx, '#706e92', [[329, 151], [345, 152], [364, 163], [377, 177], [390, 200], [393, 212],
      [365, 212], [361, 191], [349, 169]])
    shape(ctx, '#4d4e70', [[307, 153], [319, 150], [331, 169], [339, 193], [341, 213], [326, 213], [321, 184]])
    shape(ctx, '#d4bcc9', [[307, 156], [318, 153], [331, 177], [329, 181], [319, 161], [309, 163]])
    pixelLine(ctx, '#8b87a6', 288, 175, 280, 190, 1)
    pixelLine(ctx, '#8b87a6', 280, 190, 275, 209, 1)
    pixelLine(ctx, '#d3bfd0', 294, 173, 308, 164)
    pixelLine(ctx, '#c1afc3', 285, 184, 302, 174)
    pixelLine(ctx, '#9a94af', 276, 203, 325, 192)
    pixelLine(ctx, '#8984a2', 341, 192, 370, 195)
    pixelLine(ctx, '#585876', 277, 226, 364, 226)
    pixelLine(ctx, '#89839a', 270, 218, 270, 274, 2)
    box(ctx, '#6c6886', 264, 211, 129, 5)
    box(ctx, '#31364f', 314, 236, 23, 42)
    box(ctx, '#bea481', 321, 244, 6, 8)
    box(ctx, '#d6ac90', 288, 232, 12, 7)
    box(ctx, '#373952', 398, 239, 37, 37)
    box(ctx, '#5b5976', 395, 235, 43, 6)
    box(ctx, '#777d95', 417, 208, 2, 30)
    box(ctx, '#777d95', 411, 212, 16, 2)
    shape(ctx, '#646079', [[308, 276], [337, 276], [366, 360], [313, 360]], 2)
    for (const [x, y, w, h] of [[57, 350, 48, 18], [143, 339, 39, 18], [266, 322, 29, 15], [454, 331, 46, 21], [588, 356, 66, 24]]) {
      rock(ctx, x, y, w, h, '#262b43', '#53536d')
    }
    for (const [x, y] of [[310, 283], [337, 287], [313, 302], [344, 309], [317, 324], [353, 337]]) {
      disc(ctx, '#8d7387', x + 2, y + 7, 7, 2, 1)
      box(ctx, '#76728a', x, y, 2, 7)
      box(ctx, '#ffc592', x - 1, y, 4, 2)
    }
  }, (ctx, time) => {
    twinkle(ctx, time, observatoryStars, 8, 135, '#e5daf2')
    const phase = (time + 0.7) % 6.7
    if (phase < 1.8) {
      const x = 521 - phase * 97
      const y = 39 + phase * 37
      for (let pixel = 0; pixel < 23; pixel += 1) {
        box(ctx, pixel < 5 ? '#f2ddea' : pixel < 13 ? '#b0a4ce' : '#66638b', x + pixel * 2, y - pixel * 0.76, 3, 1)
      }
      box(ctx, '#fcf2ef', x, y, 3, 2)
    }
    const glimmer = Math.sin(time * 2.1)
    if (glimmer > 0) {
      box(ctx, '#d6c5df', 212, 71, 3, 1)
      box(ctx, '#d6c5df', 213, 70, 1, 3)
    }
    box(ctx, glimmer > 0 ? '#e8a6ac' : '#ab758c', 417, 207, 2, 2)
  })

  function gull(ctx, x, y, time, color) {
    const lift = Math.round(Math.sin(time * 4.7) * 3)
    pixelLine(ctx, color, x - 12, y - lift - 2, x - 6, y - lift - 2)
    pixelLine(ctx, color, x - 6, y - lift - 2, x, y + 1, 2)
    pixelLine(ctx, color, x, y + 1, x + 6, y - lift - 2, 2)
    pixelLine(ctx, color, x + 6, y - lift - 2, x + 13, y - lift - 1)
  }

  const harbor = scene('morning-harbor', '아침 항구', '#f3c49a', (ctx) => {
    sky(ctx, ['#9ec5d2', '#aed0d9', '#bdd9db', '#d3e0d8', '#eae5d5', '#f4e5c8'], 196, 89)
    disc(ctx, '#fff0c3', 448, 122, 37)
    cloud(ctx, 154, 74, 85, '#f0e8d6', '#d3ddd6')
    cloud(ctx, 526, 95, 53, '#efe8d9', '#cfdcd7')
    shape(ctx, '#a0b1b4', [[0, 180], [64, 170], [133, 147], [173, 166], [234, 166], [301, 183], [408, 185],
      [470, 164], [519, 169], [571, 146], [640, 160], [640, 209], [0, 209]])
    water(ctx, 195, ['#91b9bd', '#83b4b8', '#73aab1', '#669ca7', '#64939f'], ['#afc9c3', '#8fc3bd', '#84a4ab', '#e2d7b6'], 617)
    for (const [x, y, w] of [[396, 192, 26], [515, 189, 31], [572, 184, 23]]) {
      box(ctx, '#809b9f', x, y, w, 3)
      box(ctx, '#bac2b3', x + w * .33, y - 4, w * .43, 4)
      box(ctx, '#849ca1', x + w * .43, y - 10, 1, 9)
      box(ctx, '#b7cbc3', x - 5, y + 4, w + 9, 1)
    }
    const rand = random(390)
    for (let index = 0; index < 80; index += 1) {
      const y = 202 + rand() * 158
      box(ctx, index % 3 ? '#b4c9bb' : '#e9d8af', 444 + (rand() - 0.5) * (28 + (y - 194) * 0.72), y, 3 + rand() * 19, 1)
    }
    shape(ctx, '#7a8c8f', [[0, 235], [63, 229], [122, 217], [160, 228], [211, 214], [260, 223], [316, 211], [341, 219],
      [327, 239], [242, 253], [114, 268], [0, 283]])
    shape(ctx, '#a1ad9f', [[0, 235], [67, 227], [123, 214], [160, 226], [212, 212], [260, 221], [316, 208],
      [341, 216], [314, 223], [210, 232], [133, 236], [0, 252]])
    for (const [x, y, w, h] of [[37, 275, 28, 10], [81, 269, 31, 10], [118, 265, 29, 12], [157, 261, 28, 10],
      [221, 255, 34, 12], [275, 248, 27, 13], [315, 239, 31, 12]]) {
      rock(ctx, x, y, w, h, '#647f88', '#9da89c')
      box(ctx, '#adc8bd', x - w * .23, y + 2, w * .54, 1)
    }
    shape(ctx, '#ded5b6', [[272, 216], [278, 128], [300, 128], [308, 214]])
    shape(ctx, '#f4ecd2', [[279, 214], [283, 128], [296, 128], [300, 216]])
    shape(ctx, '#b5645f', [[276, 151], [302, 151], [304, 166], [275, 166]])
    shape(ctx, '#bf7269', [[274, 180], [305, 180], [306, 195], [273, 195]])
    box(ctx, '#64777b', 284, 200, 8, 16)
    box(ctx, '#9d786f', 284, 173, 4, 5)
    box(ctx, '#9c9e8f', 287, 138, 3, 5)
    box(ctx, '#92685e', 272, 124, 35, 6)
    for (let x = 274; x < 306; x += 4) box(ctx, '#886761', x, 118, 1, 6)
    box(ctx, '#b48d7a', 272, 118, 35, 1)
    box(ctx, '#a89a88', 279, 111, 22, 13)
    box(ctx, '#fff0b7', 282, 113, 16, 9)
    box(ctx, '#827a75', 289, 111, 2, 12)
    shape(ctx, '#ad675f', [[275, 112], [289, 100], [305, 112]], 1)
    box(ctx, '#846f68', 288, 96, 2, 5)
    for (const [x, y, w] of [[103, 231, 60], [174, 224, 46], [222, 225, 32]]) {
      box(ctx, '#d9c3a6', x, y - 24, w, 24)
      shape(ctx, '#a37068', [[x - 4, y - 24], [x + w * 0.38, y - 39], [x + w + 4, y - 24]])
      box(ctx, '#858b85', x + 6, y - 15, w * 0.26, 9)
      box(ctx, '#f0d7ac', x + 7, y - 14, w * .23, 2)
      box(ctx, '#728084', x + w * 0.66, y - 12, w * 0.17, 12)
    }
    shape(ctx, '#9b806b', [[0, 298], [148, 265], [184, 268], [0, 338]])
    shape(ctx, '#b9a183', [[0, 296], [147, 263], [183, 266], [0, 326]])
    for (let post = 0; post < 5; post += 1) {
      const x = 18 + post * 31
      const y = 310 - post * 10
      box(ctx, '#796b60', x, y - 21, 5, 22)
      box(ctx, '#d1b79a', x, y - 21, 2, 20)
    }
    for (let board = 0; board < 9; board += 1) {
      pixelLine(ctx, '#9a806a', board * 16, 296 - board * 3.4, board * 16 + 26, 316 - board * 5)
    }
    pixelLine(ctx, '#847260', 22, 291, 147, 252, 1)
  }, (ctx, time, layer) => {
    reflectedHorizon(ctx, layer, time, harborWater, 196, 149, .22)
    waterLight(ctx, time, harborWater, 446, 200, 153, 126, '#fff0c1', sunGlints, .76)
    ctx.save()
    ctx.clip(harborWater)
    sparkleWater(ctx, time, 242, 117, '#dce0c1', harborRipples)
    const x = 398 + Math.sin(time / 10) * 59
    const y = 261 + Math.sin(time * 2.1) * 2
    fishingBoat.draw(ctx, x - 54, y - 41)
    box(ctx, '#b76865', x - 17, y - 38, 12, 5)
    for (let wave = 0; wave < 5; wave += 1) box(ctx, '#b5d2c7', x - 54 - wave * 12, y + 10 + wave * 2, 21 - wave * 2, 1)
    objectReflection(ctx, time, harborWater, x - 54, y + 11, 108, 50, .28)
    ctx.restore()
    gull(ctx, 195 + time * 8, 124 + Math.sin(time) * 9, time, '#657f88')
    gull(ctx, 225 + time * 9.4, 104 + Math.sin(time + 0.8) * 8, time + 0.3, '#687f89')
    gull(ctx, 533 - time * 6, 141 + Math.sin(time + 1) * 7, time + 0.7, '#73858b')
    gull(ctx, 500 - time * 5.3, 154 + Math.sin(time + 2) * 7, time + 0.1, '#73858b')
  })

  window.PixelScenes = { width, height, scenes: [city, lake, coast, rain, forest, snow, roof, blossoms, observatory, harbor] }
})()
