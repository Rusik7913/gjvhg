package com.neonrush.game

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/**
 * Safe, lightweight renderer for Neon Rush.
 * Designed to avoid GPU-driver crashes and invalid Canvas operations on older devices.
 */
class GameView(ctx: Context) : View(ctx) {

    private enum class Screen { MENU, RUN, PAUSE, GAME_OVER, SETTINGS }
    private enum class Quality { LOW, MEDIUM, HIGH }

    private data class Entity(
        var z: Float,
        var lane: Int,
        var kind: Int,
        var collected: Boolean = false
    )

    private val prefs = ctx.applicationContext.getSharedPreferences("neon_rush", Context.MODE_PRIVATE)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(System.currentTimeMillis())
    private val entities = ArrayList<Entity>(32)

    private var screen = Screen.MENU
    private var quality = Quality.HIGH
    private var distance = 0f
    private var coins = 0
    private var runCoins = 0
    private var speed = 0.36f
    private var playerLane = 1
    private var playerX = 0f
    private var playerY = 0f
    private var verticalVelocity = 0f
    private var slideTimer = 0f
    private var spawnTimer = 0.3f
    private var lastFrameNs = 0L
    private var best = prefs.getFloat("best", 0f)

    private var downX = 0f
    private var downY = 0f

    init {
        isFocusable = true
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        // Software Canvas is slower than GPU rendering, but is much more tolerant
        // of shader/driver problems on older Android devices.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        quality = when (prefs.getInt("quality", 2).coerceIn(0, 2)) {
            0 -> Quality.LOW
            1 -> Quality.MEDIUM
            else -> Quality.HIGH
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f
        else ((now - lastFrameNs).coerceAtMost(50_000_000L) / 1_000_000_000f)
        lastFrameNs = now

        // A bad frame must never kill the whole Activity.
        try {
            if (width > 0 && height > 0) {
                drawWorld(canvas)

                when (screen) {
                    Screen.MENU -> drawMenu(canvas)
                    Screen.RUN -> {
                        update(dt.coerceIn(0f, 0.033f))
                        drawHud(canvas)
                    }
                    Screen.PAUSE -> {
                        drawHud(canvas)
                        drawPause(canvas)
                    }
                    Screen.GAME_OVER -> {
                        drawHud(canvas)
                        drawGameOver(canvas)
                    }
                    Screen.SETTINGS -> drawSettings(canvas)
                }
            }
        } catch (_: RuntimeException) {
            // Fall back to a plain frame instead of crashing because of a renderer issue.
            try {
                fill.shader = null
                fill.color = Color.rgb(6, 8, 24)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
                fill.color = Color.WHITE
                fill.textAlign = Paint.Align.CENTER
                fill.textSize = max(18f, width * 0.055f)
                canvas.drawText("NEON RUSH", width / 2f, height / 2f, fill)
                fill.textAlign = Paint.Align.LEFT
            } catch (_: RuntimeException) {
                // Nothing else to do if the Canvas itself is unavailable.
            }
        }

        postInvalidateOnAnimation()
    }

    private fun update(dt: Float) {
        distance += speed * dt * 110f
        speed = min(0.72f, speed + dt * 0.004f)

        val targetX = laneX(playerLane)
        playerX += (targetX - playerX) * min(1f, dt * 12f)

        if (playerY > 0f || verticalVelocity > 0f) {
            playerY += verticalVelocity * dt
            verticalVelocity -= 2.9f * dt
            if (playerY <= 0f) {
                playerY = 0f
                verticalVelocity = 0f
            }
        }

        slideTimer = max(0f, slideTimer - dt)
        spawnTimer -= dt

        if (spawnTimer <= 0f) {
            spawnPattern()
            spawnTimer = max(0.38f, 0.9f - speed * 0.45f) + random.nextFloat() * 0.25f
        }

        val iterator = entities.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            e.z -= speed * dt

            if (e.z < -0.08f) {
                iterator.remove()
                continue
            }

            if (!e.collected && e.z in 0.02f..0.13f && e.lane == playerLane) {
                when (e.kind) {
                    0 -> {
                        e.collected = true
                        runCoins++
                    }
                    1 -> if (playerY < 0.12f && slideTimer <= 0f) {
                        finishRun()
                        return
                    }
                    2 -> if (playerY < 0.18f) {
                        finishRun()
                        return
                    }
                }
            }
        }
    }

    private fun spawnPattern() {
        if (entities.size > 28) return

        val lane = random.nextInt(3)
        if (random.nextFloat() < 0.55f) {
            entities.add(Entity(1.02f, lane, 0))
            if (random.nextFloat() < 0.35f && entities.size < 28) {
                val other = (lane + 1 + random.nextInt(2)) % 3
                entities.add(Entity(1.16f, other, 1))
            }
        } else {
            entities.add(Entity(1.02f, lane, if (random.nextBoolean()) 1 else 2))
            if (entities.size < 28) {
                val coinLane = (lane + 1) % 3
                entities.add(Entity(1.18f, coinLane, 0))
                entities.add(Entity(1.34f, coinLane, 0))
            }
        }
    }

    private fun finishRun() {
        coins += runCoins
        best = max(best, distance)
        prefs.edit()
            .putInt("coins", coins)
            .putFloat("best", best)
            .apply()
        screen = Screen.GAME_OVER
    }

    private fun startRun() {
        screen = Screen.RUN
        distance = 0f
        runCoins = 0
        speed = 0.36f
        playerLane = 1
        playerX = laneX(1)
        playerY = 0f
        verticalVelocity = 0f
        slideTimer = 0f
        spawnTimer = 0.3f
        entities.clear()
        lastFrameNs = System.nanoTime()
    }

    private fun drawWorld(c: Canvas) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        // Keep gradients simple and bounded.
        val skyBottom = h * 0.66f
        fill.shader = LinearGradient(
            0f, 0f, 0f, skyBottom,
            intArrayOf(0xFF070A26.toInt(), 0xFF1B0A3B.toInt(), 0xFF6D155C.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, skyBottom, fill)
        fill.shader = null

        fill.color = 0xFF0A0D20.toInt()
        c.drawRect(0f, skyBottom, w, h, fill)

        drawSkyline(c, w, h)
        drawTrack(c, w, h)
        drawEntities(c, w, h)
        drawPlayer(c, w, h)
    }

    private fun drawSkyline(c: Canvas, w: Float, h: Float) {
        val base = h * 0.55f
        val count = 12
        for (i in 0 until count) {
            val bw = w / count + 3f
            val x = i * w / count - 2f
            val bh = h * (0.07f + ((i * 37) % 9) / 100f)
            fill.color = if (i % 2 == 0) 0xFF080B22.toInt() else 0xFF0C0E2B.toInt()
            c.drawRect(x, base - bh, x + bw, base + h * 0.03f, fill)

            if (quality != Quality.LOW) {
                fill.color = if (i % 3 == 0) 0xFF20E7FF.toInt() else 0xFFFF2CCB.toInt()
                for (r in 0 until 3) {
                    val wy = base - bh + 18f + r * 24f
                    if (wy + 5f < base) c.drawRect(x + 9f, wy, x + 13f, wy + 5f, fill)
                }
            }
        }
    }

    private fun drawTrack(c: Canvas, w: Float, h: Float) {
        val horizon = h * 0.52f
        val bottom = h * 1.03f
        val center = w / 2f

        val road = Path()
        road.moveTo(w * 0.42f, horizon)
        road.lineTo(w * 0.58f, horizon)
        road.lineTo(w * 0.96f, bottom)
        road.lineTo(w * 0.04f, bottom)
        road.close()
        fill.color = 0xFF060914.toInt()
        c.drawPath(road, fill)

        line.strokeWidth = max(2f, w * 0.006f)
        for (lane in 0..3) {
            val topX = w * (0.42f + lane * 0.053f)
            val bottomX = w * (0.04f + lane * 0.306f)
            if (quality == Quality.LOW) {
                line.shader = null
                line.color = 0xFF138BAA.toInt()
            } else {
                line.shader = LinearGradient(topX, horizon, bottomX, bottom, 0x0033EFFF, 0xFF20DFFF.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawLine(topX, horizon, bottomX, bottom, line)
        }
        line.shader = null

        val lines = if (quality == Quality.LOW) 8 else 14
        for (i in 0 until lines) {
            val t = i / lines.toFloat()
            val z = (t * t).coerceIn(0f, 1f)
            val y = horizon + (bottom - horizon) * z
            val left = center - (center - w * 0.04f) * z
            val right = center + (center - w * 0.04f) * z
            line.strokeWidth = 3f + 7f * z
            line.color = 0xFF171C37.toInt()
            c.drawLine(left, y, right, y, line)
        }

        line.strokeWidth = 4f
        line.color = 0xFF00D9FF.toInt()
        c.drawLine(w * 0.04f, bottom, w * 0.42f, horizon, line)
        line.color = 0xFFFF2CCB.toInt()
        c.drawLine(w * 0.96f, bottom, w * 0.58f, horizon, line)
    }

    private fun projectZ(zRaw: Float, h: Float): Pair<Float, Float> {
        val z = zRaw.coerceIn(0f, 1.4f)
        val horizon = h * 0.52f
        val y = horizon + (h * 0.94f - horizon) * (1f - z).coerceAtLeast(0f).pow(1.75f)
        val scale = 0.18f + (1f - z).coerceIn(0f, 1f) * 1.55f
        return Pair(y, scale)
    }

    private fun laneX(lane: Int): Float {
        val safeLane = lane.coerceIn(0, 2)
        return width / 2f + (safeLane - 1) * width * 0.18f
    }

    private fun drawEntities(c: Canvas, w: Float, h: Float) {
        val snapshot = entities.sortedByDescending { it.z }
        for (e in snapshot) {
            if (e.collected) continue
            val (y, scale) = projectZ(e.z, h)
            val lane = e.lane.coerceIn(0, 2)
            val x = w / 2f + (lane - 1) * w * 0.18f * (1.15f - e.z.coerceIn(0f, 1.4f))
            val s = (scale * w * 0.045f).coerceIn(2f, w * 0.12f)

            if (e.kind == 0) {
                if (quality != Quality.LOW) {
                    fill.shader = RadialGradient(x - s * .25f, y - s * .3f, s * 1.5f,
                        intArrayOf(0xFFFFF176.toInt(), 0xFFFFC400.toInt(), 0x00FFC400), null, Shader.TileMode.CLAMP)
                    c.drawCircle(x, y, s * 1.5f, fill)
                    fill.shader = null
                }
                fill.color = 0xFFFFD740.toInt()
                c.drawCircle(x, y, s, fill)
                fill.color = 0xFFFFF59D.toInt()
                c.drawCircle(x - s * .28f, y - s * .28f, s * .22f, fill)
            } else {
                val ow = s * 2.5f
                val oh = if (e.kind == 1) s * 1.9f else s * 2.8f
                fill.color = if (e.kind == 1) 0xFFFF176B.toInt() else 0xFF7C4DFF.toInt()
                c.drawRoundRect(x - ow / 2f, y - oh, x + ow / 2f, y, s * .25f, s * .25f, fill)
                if (quality != Quality.LOW) {
                    fill.color = 0xAAFFFFFF.toInt()
                    c.drawRoundRect(x - ow * .32f, y - oh * .78f, x + ow * .32f, y - oh * .68f, 8f, 8f, fill)
                }
            }
        }
    }

    private fun drawPlayer(c: Canvas, w: Float, h: Float) {
        val groundY = h * 0.88f
        val bob = if (playerY == 0f) sin(distance * 0.12f) * 4f else 0f
        val x = playerX.coerceIn(-w, w * 2f)
        val y = groundY - playerY * h * 0.32f + bob
        val sc = (w / 420f).coerceIn(0.55f, 2.0f)

        fill.color = 0x66000000
        c.drawOval(x - 42f * sc, groundY - 8f * sc, x + 42f * sc, groundY + 10f * sc, fill)

        fill.color = 0xFFFF2CCB.toInt()
        c.drawRoundRect(x - 43f * sc, y + 45f * sc, x + 43f * sc, y + 57f * sc, 8f * sc, 8f * sc, fill)

        val crouch = slideTimer > 0f
        val bodyTop = if (crouch) y + 2f * sc else y - 5f * sc
        val bodyBottom = y + 43f * sc

        line.strokeWidth = 11f * sc
        line.shader = null
        line.color = 0xFF202B4D.toInt()
        c.drawLine(x - 13f * sc, bodyBottom, x - 25f * sc, y + 48f * sc, line)
        c.drawLine(x + 13f * sc, bodyBottom, x + 25f * sc, y + 48f * sc, line)

        fill.color = 0xFF16D9FF.toInt()
        c.drawRoundRect(x - 27f * sc, bodyTop, x + 27f * sc, bodyBottom, 15f * sc, 15f * sc, fill)

        fill.color = 0xFFFFC7A8.toInt()
        c.drawCircle(x, bodyTop - 25f * sc, 21f * sc, fill)
        fill.color = 0xFF10152B.toInt()
        c.drawArc(x - 22f * sc, bodyTop - 47f * sc, x + 22f * sc, bodyTop - 10f * sc, 185f, 170f, true, fill)
        fill.color = Color.WHITE
        c.drawCircle(x - 7f * sc, bodyTop - 27f * sc, 3f * sc, fill)
        c.drawCircle(x + 7f * sc, bodyTop - 27f * sc, 3f * sc, fill)

        if (quality == Quality.HIGH) {
            fill.color = 0x3300E5FF
            c.drawCircle(x, y + 26f * sc, 48f * sc, fill)
        }
    }

    private fun drawHud(c: Canvas) {
        val w = width.toFloat()
        fill.shader = null
        fill.typeface = Typeface.DEFAULT_BOLD
        fill.textSize = max(16f, w * 0.045f)
        fill.textAlign = Paint.Align.LEFT
        fill.color = Color.WHITE
        c.drawText("DIST ${distance.toInt()} m", 24f, 48f, fill)
        c.drawText("● $runCoins", 24f, 82f, fill)

        fill.color = 0xAA11162D.toInt()
        c.drawRoundRect(w - 78f, 22f, w - 20f, 68f, 22f, 22f, fill)
        fill.color = Color.WHITE
        c.drawRect(w - 51f, 32f, w - 46f, 58f, fill)
        c.drawRect(w - 36f, 32f, w - 31f, 58f, fill)
    }

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.shader = null
        fill.textAlign = Paint.Align.CENTER
        fill.typeface = Typeface.DEFAULT_BOLD
        fill.textSize = max(28f, w * 0.14f)
        fill.color = Color.WHITE
        c.drawText("NEON", w / 2f, h * .20f, fill)
        fill.color = 0xFF00E5FF.toInt()
        c.drawText("RUSH", w / 2f, h * .31f, fill)
        fill.textSize = max(13f, w * .038f)
        fill.color = 0xFFDCE7FF.toInt()
        c.drawText("RUN • JUMP • SLIDE • SURVIVE", w / 2f, h * .36f, fill)

        button(c, w / 2f, h * .58f, w * .72f, h * .085f, "START RUN")
        button(c, w / 2f, h * .69f, w * .72f, h * .085f, "SETTINGS")

        fill.textSize = max(12f, w * .032f)
        fill.color = 0xFF8C9AC7.toInt()
        c.drawText("BEST ${best.toInt()} m   TOTAL COINS $coins", w / 2f, h * .84f, fill)
        fill.textAlign = Paint.Align.LEFT
    }

    private fun drawPause(c: Canvas) {
        overlay(c)
        panelTitle(c, "PAUSED")
        button(c, width / 2f, height * .55f, width * .68f, height * .08f, "CONTINUE")
        button(c, width / 2f, height * .66f, width * .68f, height * .08f, "QUIT")
    }

    private fun drawGameOver(c: Canvas) {
        overlay(c)
        panelTitle(c, "RUN OVER")
        fill.textAlign = Paint.Align.CENTER
        fill.color = Color.WHITE
        fill.textSize = max(18f, width * .055f)
        c.drawText("DISTANCE ${distance.toInt()} m", width / 2f, height * .43f, fill)
        c.drawText("COINS $runCoins", width / 2f, height * .49f, fill)
        button(c, width / 2f, height * .61f, width * .68f, height * .08f, "RUN AGAIN")
        button(c, width / 2f, height * .72f, width * .68f, height * .08f, "MENU")
        fill.textAlign = Paint.Align.LEFT
    }

    private fun drawSettings(c: Canvas) {
        overlay(c)
        panelTitle(c, "SETTINGS")
        fill.textAlign = Paint.Align.CENTER
        fill.color = Color.WHITE
        fill.textSize = max(16f, width * .045f)
        c.drawText("QUALITY", width / 2f, height * .43f, fill)
        val names = arrayOf("LOW", "MEDIUM", "HIGH")
        names.forEachIndexed { i, name ->
            val x = width * (0.25f + i * .25f)
            val active = quality.ordinal == i
            fill.color = if (active) 0xFF00E5FF.toInt() else 0xFF202742.toInt()
            c.drawRoundRect(x - width * .095f, height * .49f, x + width * .095f, height * .56f, 20f, 20f, fill)
            fill.color = Color.WHITE
            fill.textSize = max(12f, width * .032f)
            c.drawText(name, x, height * .535f, fill)
        }
        button(c, width / 2f, height * .69f, width * .68f, height * .08f, "BACK")
        fill.textAlign = Paint.Align.LEFT
    }

    private fun overlay(c: Canvas) {
        fill.shader = null
        fill.color = 0xAA050713.toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
    }

    private fun panelTitle(c: Canvas, title: String) {
        fill.shader = null
        fill.textAlign = Paint.Align.CENTER
        fill.typeface = Typeface.DEFAULT_BOLD
        fill.textSize = max(28f, width * .09f)
        fill.color = Color.WHITE
        c.drawText(title, width / 2f, height * .31f, fill)
    }

    private fun button(c: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, text: String) {
        val safeW = max(1f, bw)
        val safeH = max(1f, bh)
        fill.shader = null
        fill.color = 0xFF10162E.toInt()
        c.drawRoundRect(cx - safeW / 2f, cy - safeH / 2f, cx + safeW / 2f, cy + safeH / 2f, 24f, 24f, fill)
        line.shader = null
        line.strokeWidth = 3f
        line.color = 0xFF00E5FF.toInt()
        c.drawRoundRect(cx - safeW / 2f, cy - safeH / 2f, cx + safeW / 2f, cy + safeH / 2f, 24f, 24f, line)
        fill.textAlign = Paint.Align.CENTER
        fill.typeface = Typeface.DEFAULT_BOLD
        fill.textSize = max(14f, width * .043f)
        fill.color = Color.WHITE
        c.drawText(text, cx, cy + fill.textSize * .35f, fill)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            val dy = event.y - downY
            val swipe = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            if (screen == Screen.RUN) {
                if (swipe > width * .08f) {
                    if (abs(dx) > abs(dy)) {
                        playerLane = if (dx > 0f) min(2, playerLane + 1) else max(0, playerLane - 1)
                    } else if (dy < 0f && playerY <= 0f) {
                        verticalVelocity = 1.35f
                    } else if (dy > 0f && playerY <= 0f) {
                        slideTimer = .55f
                    }
                } else if (event.x > width - 110f && event.y < 100f) {
                    screen = Screen.PAUSE
                }
            } else {
                handleTap(event.x, event.y)
            }
            return true
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val h = height.toFloat()
        val w = width.toFloat()

        when (screen) {
            Screen.MENU -> {
                if (y in h * .52f..h * .64f) startRun()
                else if (y in h * .64f..h * .75f) screen = Screen.SETTINGS
            }
            Screen.PAUSE -> {
                if (y in h * .49f..h * .61f) screen = Screen.RUN
                else if (y in h * .61f..h * .73f) screen = Screen.MENU
            }
            Screen.GAME_OVER -> {
                if (y in h * .56f..h * .66f) startRun()
                else if (y in h * .67f..h * .78f) screen = Screen.MENU
            }
            Screen.SETTINGS -> {
                if (y in h * .46f..h * .59f) {
                    val i = ((x / max(1f, w)) * 4f).toInt().coerceIn(0, 2)
                    quality = Quality.entries[i]
                    prefs.edit().putInt("quality", quality.ordinal).apply()
                } else if (y in h * .64f..h * .75f) {
                    screen = Screen.MENU
                }
            }
            else -> Unit
        }
    }
}
