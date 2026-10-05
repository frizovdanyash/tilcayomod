package com.tilcayo.fat

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Вся игра в одном View: меню, забег, экран смерти и ферма.
 *
 * Забег рисуется в «мировых» единицах (ширина поля = 360), UI — в сетке 360x800,
 * которая центрируется на экране.
 */
class GameView(context: Context) : View(context) {

    private enum class Scene { MENU, PLAY, DEAD, FARM }

    private class Coin(val x: Float, val y: Float) { var got = false }
    private class Snack(val x: Float, val y: Float, val emoji: String, val fat: Int) { var got = false }
    private class Spike(val left: Boolean, val y0: Float, val y1: Float)
    private class Saw(var x: Float, val y: Float, val minX: Float, val maxX: Float, var vx: Float) { var ang = 0f }
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float,
    )
    private class Pop(val x: Float, val y: Float, val text: String, val color: Int, val size: Float) { var life = 1f }
    private class Btn(val x: Float, val y: Float, val w: Float, val h: Float, val onTap: () -> Unit)

    // ---------- константы ----------
    private val W = 360f
    private val WALL = 34f
    private val SPIKE_LEN = 24f
    private val GRAV = 1400f
    private val JUMP_VY = 420f
    private val JUMP_VX = 650f
    private val SLIDE = 50f

    // ---------- рисование ----------
    private val save = SaveData(context)
    private val sprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.tilcayo)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val argb = ArgbEvaluator()
    private val path = Path()
    private val rect = RectF()

    private var scale = 1f
    private var viewH = 800f
    private var ui = 1f
    private var uiOx = 0f
    private var uiOy = 0f

    private val rnd = Random(System.nanoTime())
    private val stars = List(70) { Triple(rnd.nextFloat() * 360f, rnd.nextFloat() * 900f, 0.6f + rnd.nextFloat() * 1.8f) }

    // ---------- состояние ----------
    private var scene = Scene.MENU
    private var sceneTime = 0f
    private var anim = 0f
    private var last = System.nanoTime()
    private var paused = false
    private val buttons = ArrayList<Btn>()
    private var toastText = ""
    private var toastT = 0f

    // забег
    private var px = 0f
    private var py = 0f
    private var pvx = 0f
    private var pvy = 0f
    private var side = -1
    private var dir = 1
    private var onWall = true
    private var squash = 0f
    private var jumpBuf = 0f
    private var r = 16f
    private var hr = 10f
    private var runLevel = 1
    private var camY = 0f
    private var lavaY = 0f
    private var minPy = 0f
    private var genY = 0f
    private var runTime = 0f
    private var runCoins = 0f
    private var coinMul = 1f
    private var gotSnacks = 0
    private var deathReason = ""
    private var newRecord = false
    private var earned = 0L

    private val coins = ArrayList<Coin>()
    private val snacks = ArrayList<Snack>()
    private val spikes = ArrayList<Spike>()
    private val saws = ArrayList<Saw>()
    private val particles = ArrayList<Particle>()
    private val pops = ArrayList<Pop>()

    // ферма
    private val uiPops = ArrayList<Pop>()

    private fun k(v: Long) = v.toInt()

    // =====================================================================
    // Жизненный цикл
    // =====================================================================

    fun onHostPause() {
        if (scene == Scene.PLAY) paused = true
        save.save()
    }

    /** true — нажатие «назад» обработано внутри игры. */
    fun onBack(): Boolean = when (scene) {
        Scene.PLAY, Scene.DEAD, Scene.FARM -> { scene = Scene.MENU; sceneTime = 0f; paused = false; true }
        Scene.MENU -> false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = w / W
        viewH = h / scale
        ui = min(w / 360f, h / 800f)
        uiOx = (w - 360f * ui) / 2f
        uiOy = (h - 800f * ui) / 2f
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        anim += dt
        sceneTime += dt
        toastT = max(0f, toastT - dt)
        buttons.clear()

        when (scene) {
            Scene.MENU -> drawMenu(c)
            Scene.FARM -> drawFarm(c, dt)
            Scene.PLAY, Scene.DEAD -> {
                if (scene == Scene.PLAY && !paused) {
                    var rem = dt
                    while (rem > 0f) {
                        val s = min(rem, 1f / 120f)
                        stepPlay(s)
                        rem -= s
                        if (scene != Scene.PLAY) break
                    }
                }
                stepFx(dt)
                drawWorld(c)
                if (scene == Scene.PLAY) drawHud(c) else drawDeadOverlay(c)
                if (paused) drawPause(c)
            }
        }
        postInvalidateOnAnimation()
    }

    // =====================================================================
    // Ввод
    // =====================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN && e.actionMasked != MotionEvent.ACTION_POINTER_DOWN) return true
        when {
            paused -> paused = false
            scene == Scene.PLAY -> {
                if (onWall) jump() else jumpBuf = 0.18f
            }
            else -> {
                val ux = (e.x - uiOx) / ui
                val uy = (e.y - uiOy) / ui
                if (scene == Scene.DEAD && sceneTime < 0.5f) return true
                buttons.asReversed().firstOrNull { ux >= it.x && ux <= it.x + it.w && uy >= it.y && uy <= it.y + it.h }
                    ?.let {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        it.onTap()
                    }
            }
        }
        return true
    }

    // =====================================================================
    // Забег: логика
    // =====================================================================

    private fun stickX(s: Int) = if (s < 0) WALL + r * 0.55f else W - WALL - r * 0.55f

    private fun startRun() {
        scene = Scene.PLAY
        sceneTime = 0f
        paused = false
        runLevel = save.level(save.selected)
        r = 15f + runLevel * 1.7f
        hr = r * 0.55f
        side = -1
        dir = 1
        onWall = true
        px = stickX(-1)
        py = 0f
        pvx = 0f
        pvy = 0f
        squash = 0f
        jumpBuf = 0f
        camY = py - viewH * 0.62f
        lavaY = py + 430f
        minPy = 0f
        genY = -150f
        runTime = 0f
        runCoins = 0f
        coinMul = save.coinMultiplier()
        gotSnacks = 0
        newRecord = false
        coins.clear(); snacks.clear(); spikes.clear(); saws.clear(); particles.clear(); pops.clear()
    }

    private fun jump() {
        onWall = false
        dir = -side
        pvx = dir * JUMP_VX
        pvy = -JUMP_VY
        squash = -0.6f
        jumpBuf = 0f
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        dust(px, py, 6, 0xFFFFFFFF.toInt())
    }

    private fun land(s: Int) {
        px = stickX(s)
        side = s
        onWall = true
        pvx = 0f
        pvy = 0f
        squash = 1f
        dust(px - s * r * 0.3f, py, 8, 0xFFE0D6FF.toInt())
        if (jumpBuf > 0f) jump()
    }

    private fun stepPlay(dt: Float) {
        runTime += dt
        squash += (0f - squash) * min(1f, dt * 9f)
        jumpBuf = max(0f, jumpBuf - dt)

        if (onWall) {
            py += SLIDE * dt
        } else {
            pvy += GRAV * dt
            px += pvx * dt
            py += pvy * dt
            val stick = stickX(dir)
            if ((dir > 0 && px >= stick) || (dir < 0 && px <= stick)) land(dir)
        }
        if (py < minPy) minPy = py

        // камера идёт только вверх
        val target = py - viewH * 0.62f
        if (target < camY) camY += (target - camY) * min(1f, dt * 7f)

        // лава
        val height = -minPy
        val lavaSpeed = 26f + min(62f, height / 22f)
        lavaY -= lavaSpeed * dt
        lavaY = min(lavaY, py + 470f)
        if (py + hr > lavaY) return die("Тилкайо сгорел в лаве 🔥")
        if (py - hr > camY + viewH + 20f) return die("Упал вниз 😵")

        // генерация
        while (genY > camY - 700f) genRow()

        // шипы
        for (s in spikes) {
            val x0 = if (s.left) WALL else W - WALL - SPIKE_LEN
            if (circleRect(px, py, hr, x0, s.y0, x0 + SPIKE_LEN, s.y1)) return die("Наколол попу на шипы 📌")
        }
        // пилы
        for (s in saws) {
            s.x += s.vx * dt
            if (s.x < s.minX) { s.x = s.minX; s.vx = abs(s.vx) }
            if (s.x > s.maxX) { s.x = s.maxX; s.vx = -abs(s.vx) }
            s.ang += dt * 9f
            val dx = s.x - px
            val dy = s.y - py
            val rr = hr + 13f
            if (dx * dx + dy * dy < rr * rr) return die("Распилило пополам 🪚")
        }
        // монетки
        for (co in coins) {
            if (co.got) continue
            val dx = co.x - px
            val dy = co.y - py
            val rr = hr + 22f
            if (dx * dx + dy * dy < rr * rr) {
                co.got = true
                runCoins += coinMul
                sparkle(co.x, co.y, 0xFFFFD54F.toInt())
                pops.add(Pop(co.x, co.y - 8f, "+" + fmt(coinMul), 0xFFFFE082.toInt(), 13f))
            }
        }
        // еда
        for (sn in snacks) {
            if (sn.got) continue
            val dx = sn.x - px
            val dy = sn.y - py
            val rr = hr + 22f
            if (dx * dx + dy * dy < rr * rr) {
                sn.got = true
                gotSnacks++
                save.herd[save.selected] = min(Balance.MAX_FAT, save.herd[save.selected] + sn.fat)
                save.save()
                sparkle(sn.x, sn.y, 0xFFFF8A65.toInt())
                pops.add(Pop(sn.x, sn.y - 10f, "ням! +${sn.fat} жира", 0xFFFFAB91.toInt(), 14f))
            }
        }

        // новый рекорд
        if (!newRecord && save.best > 0 && (height / 10f).toInt() > save.best) {
            newRecord = true
            pops.add(Pop(W / 2, camY + viewH * 0.3f, "НОВЫЙ РЕКОРД!", 0xFFFFD54F.toInt(), 26f))
        }

        // чистка
        val bottom = camY + viewH + 150f
        coins.removeAll { it.got || it.y > bottom }
        snacks.removeAll { it.got || it.y > bottom }
        spikes.removeAll { it.y0 > bottom }
        saws.removeAll { it.y > bottom }
    }

    private fun die(reason: String) {
        deathReason = reason
        scene = Scene.DEAD
        sceneTime = 0f
        earned = runCoins.toLong()
        save.coins += earned
        val score = (-minPy / 10f).toInt()
        if (score > save.best) save.best = score
        save.save()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        for (i in 0 until 28) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = 80f + rnd.nextFloat() * 220f
            particles.add(Particle(px, py, cos(a) * sp, sin(a) * sp - 90f, 0.9f, 0.9f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 4f))
        }
    }

    private fun circleRect(cx: Float, cy: Float, rad: Float, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val nx = cx.coerceIn(x0, x1)
        val ny = cy.coerceIn(y0, y1)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < rad * rad
    }

    // ---------- генерация препятствий ----------

    private fun genRow() {
        val h = -genY
        val d = min(1f, h / 2500f)
        val roll = rnd.nextFloat()
        val ps = if (h > 250f) 0.22f + 0.2f * d else 0f
        val pw = if (h > 700f) 0.12f + 0.2f * d else 0f
        val left = WALL + 26f
        val right = W - WALL - 26f
        when {
            roll < ps -> {
                val onLeft = rnd.nextBoolean()
                val len = 52f + rnd.nextFloat() * (20f + 20f * d)
                spikes.add(Spike(onLeft, genY - len / 2, genY + len / 2))
                coins.add(Coin(if (onLeft) right else left, genY))
            }
            roll < ps + pw -> {
                val span = 130f
                val minX = left + rnd.nextFloat() * (right - left - span)
                val spd = (70f + 60f * d) * (if (rnd.nextBoolean()) 1f else -1f)
                saws.add(Saw(minX + rnd.nextFloat() * span, genY, minX, minX + span, spd))
                coins.add(Coin(W / 2, genY - 50f))
            }
            roll > 0.94f -> {
                val sn = when (rnd.nextInt(3)) {
                    0 -> Snack(W / 2, genY, "🥕", 20)
                    1 -> Snack(W / 2, genY, "🍔", 50)
                    else -> Snack(W / 2, genY, "🍰", 80)
                }
                snacks.add(sn)
            }
            else -> {
                val n = 4 + rnd.nextInt(3)
                val up = rnd.nextBoolean()
                val flip = rnd.nextBoolean()
                for (i in 0 until n) {
                    val t = i / (n - 1f)
                    val x = if (flip) right + (left - right) * t else left + (right - left) * t
                    val y = genY + (if (up) -1 else 1) * sin(t * PI.toFloat()) * 26f
                    coins.add(Coin(x, y))
                }
            }
        }
        genY -= 115f + rnd.nextFloat() * 45f
    }

    // ---------- эффекты ----------

    private fun dust(x: Float, y: Float, n: Int, color: Int) {
        for (i in 0 until n) {
            particles.add(Particle(x, y + r * 0.4f, (rnd.nextFloat() - 0.5f) * 120f, -rnd.nextFloat() * 60f, 0.45f, 0.45f, color, 2f + rnd.nextFloat() * 3f))
        }
    }

    private fun sparkle(x: Float, y: Float, color: Int) {
        for (i in 0 until 8) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = 40f + rnd.nextFloat() * 90f
            particles.add(Particle(x, y, cos(a) * sp, sin(a) * sp, 0.5f, 0.5f, color, 2f + rnd.nextFloat() * 2f))
        }
    }

    private fun stepFx(dt: Float) {
        for (q in particles) {
            q.life -= dt
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.vy += 300f * dt
        }
        particles.removeAll { it.life <= 0f }
        for (q in pops) q.life -= dt * 1.1f
        pops.removeAll { it.life <= 0f }
        for (q in uiPops) q.life -= dt * 0.9f
        uiPops.removeAll { it.life <= 0f }
    }

    // =====================================================================
    // Рисование: общее
    // =====================================================================

    private fun lerpColor(a: Int, b: Int, t: Float) = argb.evaluate(t.coerceIn(0f, 1f), a, b) as Int

    // палитры неба: верх / низ
    private val skyTop = intArrayOf(k(0xFF1B1035), k(0xFF0B2A4A), k(0xFF053B2F), k(0xFF3A0F2E), k(0xFF050510))
    private val skyBot = intArrayOf(k(0xFF4A2370), k(0xFF1D6A96), k(0xFF1F8A6A), k(0xFFB5446E), k(0xFF1A1A4A))

    private fun skyAt(height: Float, bottom: Boolean): Int {
        val pal = if (bottom) skyBot else skyTop
        val f = (height / 1800f)
        val i = floor(f).toInt()
        val t = f - i
        return lerpColor(pal[i % pal.size], pal[(i + 1) % pal.size], t)
    }

    private fun drawBackground(c: Canvas, top: Int, bottom: Int, scroll: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        c.save()
        c.scale(scale, scale)
        for ((i, s) in stars.withIndex()) {
            val y = (((s.second - scroll * 0.12f * s.third) % viewH) + viewH) % viewH
            val tw = 0.5f + 0.5f * sin(anim * 2f + i)
            p.color = ((60 + 140 * tw).toInt() shl 24) or 0xFFFFFF
            c.drawCircle(s.first, y, s.third * 0.8f, p)
        }
        c.restore()
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = 0xFFFFFFFF.toInt(),
        align: Paint.Align = Paint.Align.CENTER, shadow: Boolean = true, maxW: Float = 0f,
    ) {
        tp.textSize = size
        tp.textAlign = align
        if (maxW > 0f) {
            val wdt = tp.measureText(s)
            if (wdt > maxW) tp.textSize = size * maxW / wdt
        }
        if (shadow) {
            tp.color = 0x66000000
            c.drawText(s, x, y + size * 0.07f, tp)
        }
        tp.color = color
        c.drawText(s, x, y, tp)
    }

    private fun darken(color: Int) = lerpColor(color, 0xFF000000.toInt(), 0.35f)

    private fun panel(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int = 0x66000000, rad: Float = 16f) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(x, y, x + w, y + h, rad, rad, p)
    }

    private fun btn(
        c: Canvas, label: String, x: Float, y: Float, w: Float, h: Float, color: Int,
        sub: String? = null, enabled: Boolean = true, onTap: () -> Unit,
    ) {
        val col = if (enabled) color else k(0xFF55556A)
        p.style = Paint.Style.FILL
        p.color = darken(col)
        c.drawRoundRect(x, y + 4f, x + w, y + h + 4f, 14f, 14f, p)
        p.color = col
        c.drawRoundRect(x, y, x + w, y + h, 14f, 14f, p)
        p.color = 0x33FFFFFF
        c.drawRoundRect(x + 4f, y + 3f, x + w - 4f, y + h * 0.48f, 11f, 11f, p)
        if (sub == null) {
            text(c, label, x + w / 2, y + h / 2 + h * 0.16f, h * 0.44f, maxW = w - 16f)
        } else {
            text(c, label, x + w / 2, y + h * 0.46f, h * 0.36f, maxW = w - 12f)
            text(c, sub, x + w / 2, y + h * 0.82f, h * 0.24f, 0xFFFFF3C4.toInt(), maxW = w - 12f)
        }
        buttons.add(Btn(x, y, w, h, onTap))
    }

    private fun toast(msg: String) {
        toastText = msg
        toastT = 1.8f
    }

    private fun fmt(v: Float) = if (v == floor(v)) v.toInt().toString() else "%.1f".format(v)

    /** Рисует тилкайо с центром в (cx, cy); r — «радиус пузика». */
    private fun drawTilcayo(
        c: Canvas, cx: Float, cy: Float, rad: Float, faceDir: Int, rot: Float,
        sx: Float, sy: Float, level: Int,
    ) {
        val w = rad * 2.5f
        val h = w * sprite.height / sprite.width
        // тень
        p.style = Paint.Style.FILL
        p.color = 0x22000000
        c.save()
        c.translate(cx, cy)
        c.rotate(rot)
        c.scale(sx * faceDir, sy)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        c.drawBitmap(sprite, null, rect, bmpPaint)
        c.restore()
        if (level >= Balance.MAX_LEVEL) text(c, "👑", cx + faceDir * w * 0.02f, cy - h * 0.46f * sy, rad * 0.95f, shadow = false)
    }

    // =====================================================================
    // Рисование: забег
    // =====================================================================

    private fun drawWorld(c: Canvas) {
        val height = -minPy
        drawBackground(c, skyAt(height, false), skyAt(height, true), camY)

        c.save()
        c.scale(scale, scale)
        c.translate(0f, -camY)

        val top = camY - 20f
        val bot = camY + viewH + 20f

        // стены
        for (leftWall in booleanArrayOf(true, false)) {
            val x0 = if (leftWall) 0f else W - WALL
            p.style = Paint.Style.FILL
            p.color = k(0xFF2A1F42)
            c.drawRect(x0, top, x0 + WALL, bot, p)
            p.color = k(0xFF3A2B5C)
            val ex = if (leftWall) x0 + WALL - 6f else x0
            c.drawRect(ex, top, ex + 6f, bot, p)
            p.color = 0x33000000
            var yy = floor(top / 46f) * 46f
            while (yy < bot) {
                c.drawRect(x0, yy, x0 + WALL, yy + 2.5f, p)
                val off = if (((yy / 46f).toInt() and 1) == 0) 10f else 22f
                c.drawRect(x0 + off, yy, x0 + off + 2.5f, yy + 46f, p)
                yy += 46f
            }
        }

        // линия рекорда
        if (save.best > 0) {
            val by = -save.best * 10f
            if (by in top..bot) {
                p.color = 0x99FFD54F.toInt()
                var xx = WALL + 4f
                while (xx < W - WALL) { c.drawRect(xx, by - 1f, xx + 8f, by + 1f, p); xx += 16f }
                text(c, "РЕКОРД ${save.best} м", W / 2, by - 6f, 11f, 0xCCFFD54F.toInt(), maxW = 160f)
            }
        }

        // шипы
        for (s in spikes) {
            if (s.y1 < top || s.y0 > bot) continue
            val baseX = if (s.left) WALL else W - WALL
            val sgn = if (s.left) 1f else -1f
            val n = max(1, ((s.y1 - s.y0) / 14f).toInt())
            val step = (s.y1 - s.y0) / n
            p.color = k(0xFFE0E4F5)
            path.reset()
            for (i in 0 until n) {
                val ya = s.y0 + i * step
                path.moveTo(baseX, ya)
                path.lineTo(baseX + sgn * SPIKE_LEN, ya + step / 2)
                path.lineTo(baseX, ya + step)
                path.close()
            }
            c.drawPath(path, p)
            p.color = k(0xFFFF4D6D)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
        }

        // монетки
        for (co in coins) {
            if (co.y < top - 20f || co.y > bot) continue
            val squeeze = abs(cos(anim * 4f + co.x * 0.05f + co.y * 0.03f))
            p.color = k(0xFFFFB300)
            rect.set(co.x - 9f * squeeze - 1f, co.y - 10f, co.x + 9f * squeeze + 1f, co.y + 10f)
            c.drawOval(rect, p)
            p.color = k(0xFFFFE066)
            rect.set(co.x - 6f * squeeze, co.y - 7f, co.x + 6f * squeeze, co.y + 7f)
            c.drawOval(rect, p)
        }

        // еда
        for (sn in snacks) {
            if (sn.y < top - 20f || sn.y > bot) continue
            val bob = sin(anim * 3f + sn.y) * 3f
            p.color = 0x33FFFFFF
            c.drawCircle(sn.x, sn.y + bob, 17f, p)
            text(c, sn.emoji, sn.x, sn.y + bob + 8f, 24f, shadow = false)
        }

        // пилы
        for (s in saws) {
            if (s.y < top - 30f || s.y > bot) continue
            // направляющая
            p.color = 0x22FFFFFF
            c.drawRect(s.minX, s.y - 1.5f, s.maxX, s.y + 1.5f, p)
            c.save()
            c.translate(s.x, s.y)
            c.rotate(s.ang * 57.3f)
            path.reset()
            val teeth = 10
            for (i in 0 until teeth * 2) {
                val a = i * PI.toFloat() / teeth
                val rr = if (i % 2 == 0) 17f else 11f
                if (i == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr)
            }
            path.close()
            p.color = k(0xFFCFD8DC)
            c.drawPath(path, p)
            p.color = k(0xFFFF4D6D)
            c.drawCircle(0f, 0f, 5f, p)
            c.restore()
        }

        // лава
        path.reset()
        path.moveTo(0f, bot + 400f)
        var xx = 0f
        while (xx <= W + 12f) {
            path.lineTo(xx, lavaY + sin(xx * 0.05f + anim * 3f) * 4f)
            xx += 12f
        }
        path.lineTo(W, bot + 400f)
        path.close()
        p.color = k(0xFFFF5A1F)
        c.drawPath(path, p)
        p.color = k(0xFFFFC94D)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        path.reset()
        xx = 0f
        while (xx <= W + 12f) {
            val yy = lavaY + sin(xx * 0.05f + anim * 3f) * 4f
            if (xx == 0f) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            xx += 12f
        }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        if (lavaY < bot && rnd.nextFloat() < 0.15f) {
            particles.add(Particle(rnd.nextFloat() * W, lavaY, (rnd.nextFloat() - 0.5f) * 40f, -80f - rnd.nextFloat() * 80f, 0.8f, 0.8f, k(0xFFFFB74D), 2.5f))
        }

        // тилкайо
        if (scene == Scene.PLAY) {
            val rot = if (onWall) side * 10f else (dir * pvy / 20f).coerceIn(-25f, 25f)
            val stretch = if (onWall) 0f else min(0.18f, abs(pvy) / 3000f)
            val sx = 1f + squash * 0.22f - stretch
            val sy = 1f - squash * 0.22f + stretch
            val face = if (onWall) -side else dir
            drawTilcayo(c, px, py, r, face, rot, sx, sy, runLevel)
        }

        // частицы
        for (q in particles) {
            val a = (q.life / q.maxLife).coerceIn(0f, 1f)
            p.color = (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            c.drawCircle(q.x, q.y, q.size * (0.4f + 0.6f * a), p)
        }
        for (q in pops) {
            val a = q.life.coerceIn(0f, 1f)
            val col = (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            text(c, q.text, q.x, q.y - (1f - q.life) * 40f, q.size, col, maxW = 200f)
        }
        c.restore()
    }

    private fun drawHud(c: Canvas) {
        c.save()
        c.scale(scale, scale)
        val m = (-minPy / 10f).toInt()
        text(c, "$m м", W / 2, 66f, 40f)
        panel(c, 12f, 36f, 96f, 30f, 0x55000000)
        text(c, "🪙 ${runCoins.toInt()}", 20f, 58f, 19f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 84f)
        if (coinMul > 1f) text(c, "x${fmt(coinMul)}", 12f, 82f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT)
        if (runTime < 3f) {
            val a = (1f - runTime / 3f).coerceIn(0f, 1f)
            text(c, "ТАП — прыжок на другую стену", W / 2, viewH * 0.82f, 17f, ((a * 255).toInt() shl 24) or 0xFFFFFF, maxW = 300f)
        }
        c.restore()
    }

    private fun drawPause(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        panel(c, 0f, 330f, 360f, 120f, 0xAA000000.toInt(), 0f)
        text(c, "ПАУЗА", 180f, 385f, 44f)
        text(c, "тапни, чтобы продолжить", 180f, 425f, 18f, 0xCCFFFFFF.toInt())
        c.restore()
    }

    private fun drawDeadOverlay(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        p.color = 0x88000000.toInt()
        c.drawRect(-uiOx / ui, -uiOy / ui, 360f + uiOx / ui, 800f + uiOy / ui, p)

        val a = min(1f, sceneTime * 3f)
        c.save()
        c.translate(0f, (1f - a) * 40f)
        panel(c, 24f, 150f, 312f, 430f, 0xEE231A3D.toInt(), 24f)
        text(c, "УПС!", 180f, 205f, 44f, 0xFFFF8A80.toInt())
        text(c, deathReason, 180f, 238f, 16f, 0xCCFFFFFF.toInt(), maxW = 280f)

        val score = (-minPy / 10f).toInt()
        text(c, "$score м", 180f, 310f, 64f, 0xFFFFFFFF.toInt())
        if (newRecord) text(c, "🏆 НОВЫЙ РЕКОРД!", 180f, 345f, 22f, 0xFFFFD54F.toInt())
        else text(c, "рекорд: ${save.best} м", 180f, 345f, 18f, 0xAAFFFFFF.toInt())

        text(c, "🪙 +$earned", 180f, 395f, 30f, 0xFFFFE082.toInt())
        if (gotSnacks > 0) text(c, "съедено вкусняшек: $gotSnacks 🍔", 180f, 424f, 16f, 0xFFFFAB91.toInt())

        btn(c, "ЕЩЁ РАЗ", 48f, 450f, 264f, 56f, k(0xFF00C853)) { startRun() }
        btn(c, "🐾 ФЕРМА", 48f, 516f, 264f, 48f, k(0xFF7C4DFF)) { scene = Scene.FARM; sceneTime = 0f }
        c.restore()
        c.restore()
    }

    // =====================================================================
    // Меню
    // =====================================================================

    private fun drawMenu(c: Canvas) {
        drawBackground(c, k(0xFF1B1035), k(0xFF5A2A8A), anim * 40f)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)

        text(c, "ТОЛСТЫЙ", 180f, 120f, 56f, k(0xFFFFD54F))
        text(c, "ТИЛКАЙО", 180f, 180f, 62f, k(0xFFFFFFFF))
        text(c, "прыгай · собирай · откармливай · размножай", 180f, 212f, 13f, 0xBBFFFFFF.toInt(), maxW = 330f)

        val lvl = save.level(save.selected)
        val bob = sin(anim * 2.5f)
        drawTilcayo(c, 180f, 380f + bob * 8f, 40f + lvl * 3f, 1, bob * 4f, 1f + bob * 0.03f, 1f - bob * 0.03f, lvl)
        text(c, Balance.names[save.selected % Balance.names.size] + " · ур. $lvl", 180f, 470f, 20f, 0xFFFFF3C4.toInt())

        btn(c, "▶  ИГРАТЬ", 50f, 520f, 260f, 80f, k(0xFF00C853)) { startRun() }
        btn(c, "🐾  ФЕРМА", 50f, 618f, 260f, 64f, k(0xFF7C4DFF), sub = if (save.pending() > 0) "ждёт +${save.pending()} 🪙" else null) {
            scene = Scene.FARM; sceneTime = 0f
        }

        panel(c, 20f, 20f, 120f, 34f, 0x66000000)
        text(c, "🪙 ${save.coins}", 30f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 104f)
        panel(c, 220f, 20f, 120f, 34f, 0x66000000)
        text(c, "🏆 ${save.best} м", 230f, 44f, 20f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 104f)
        c.restore()
    }

    // =====================================================================
    // Ферма
    // =====================================================================

    private fun drawFarm(c: Canvas, dt: Float) {
        stepFx(dt)
        drawBackground(c, k(0xFF14301F), k(0xFF2E7D4F), anim * 25f)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)

        // шапка
        btn(c, "◀", 10f, 14f, 52f, 44f, k(0xFF5C6BC0)) { scene = Scene.MENU; sceneTime = 0f }
        text(c, "ФЕРМА", 180f, 48f, 32f)
        panel(c, 250f, 18f, 100f, 36f, 0x66000000)
        text(c, "🪙 ${save.coins}", 258f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 86f)

        // доход
        panel(c, 10f, 70f, 340f, 52f, 0x55000000)
        text(c, "Доход: ${save.incomePerMin()} 🪙/мин", 20f, 93f, 17f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
        text(c, "монет в забеге: x${fmt(save.coinMultiplier())}", 20f, 112f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
        val pend = save.pending()
        btn(c, "СОБРАТЬ", 196f, 76f, 148f, 40f, k(0xFFFFA000), sub = null, enabled = pend > 0) {
            val got = save.collect()
            toast("Собрано +$got 🪙")
        }
        if (pend > 0) text(c, "+$pend", 270f, 70f, 13f, 0xFFFFE082.toInt())

        // сетка стада
        val cw = 110f
        val ch = 112f
        for (i in 0 until Balance.MAX_HERD) {
            val col = i % 3
            val row = i / 3
            val x = 8f + col * (cw + 8f)
            val y = 134f + row * (ch + 8f)
            if (i < save.herd.size) {
                val lvl = save.level(i)
                val sel = i == save.selected
                panel(c, x, y, cw, ch, if (sel) 0xCC7C4DFF.toInt() else 0x66000000, 14f)
                if (sel) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 3f
                    p.color = k(0xFFFFD54F)
                    c.drawRoundRect(x, y, x + cw, y + ch, 14f, 14f, p)
                    p.style = Paint.Style.FILL
                }
                val bob = sin(anim * 3f + i * 1.3f)
                val rad = 11f + lvl * 2.2f
                drawTilcayo(c, x + cw / 2, y + 46f + bob, rad, if (i % 2 == 0) 1 else -1, 0f, 1f + bob * 0.03f, 1f - bob * 0.03f, lvl)
                text(c, "ур.$lvl", x + 8f, y + 18f, 13f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT)
                if (sel) text(c, "⭐", x + cw - 16f, y + 18f, 14f, shadow = false)
                text(c, Balance.names[i % Balance.names.size], x + cw / 2, y + 90f, 13f, 0xFFFFF3C4.toInt(), maxW = cw - 10f)
                // шкала жира
                p.color = 0x55000000
                c.drawRoundRect(x + 8f, y + 97f, x + cw - 8f, y + 106f, 5f, 5f, p)
                val frac = if (lvl >= Balance.MAX_LEVEL) 1f else (save.herd[i] % Balance.FAT_PER_LEVEL) / Balance.FAT_PER_LEVEL.toFloat()
                p.color = if (lvl >= Balance.MAX_LEVEL) k(0xFFFFD54F) else k(0xFFFF8A65)
                c.drawRoundRect(x + 8f, y + 97f, x + 8f + (cw - 16f) * frac, y + 106f, 5f, 5f, p)
                buttons.add(Btn(x, y, cw, ch) { save.selected = i; save.save() })
            } else {
                panel(c, x, y, cw, ch, 0x33000000, 14f)
                text(c, "?", x + cw / 2, y + ch / 2 + 14f, 40f, 0x44FFFFFF, shadow = false)
            }
        }

        // выбранный
        val sel = save.selected
        val lvl = save.level(sel)
        val name = Balance.names[sel % Balance.names.size]
        text(c, "$name — бегун в забеге, ур. $lvl", 180f, 520f, 17f, 0xFFFFFFFF.toInt(), maxW = 340f)
        text(c, "жирнее = больше монет, но и пролезать сложнее!", 180f, 538f, 12f, 0xAAFFFFFF.toInt(), maxW = 340f)

        // еда
        for ((i, f) in Balance.foods.withIndex()) {
            val canBuy = lvl < Balance.MAX_LEVEL && save.coins >= f.cost
            btn(c, "${f.emoji} +${f.fat}", 8f + i * 118f, 548f, 110f, 62f, k(0xFFEF6C00), sub = "${f.cost} 🪙", enabled = canBuy) { feed(f) }
        }

        // размножение
        val canBreed = save.herd.size < Balance.MAX_HERD
        val cost = Balance.breedCost(save.herd.size)
        val breedLabel = if (canBreed) "💕 РАЗМНОЖИТЬ" else "Ферма переполнена!"
        btn(c, breedLabel, 8f, 624f, 344f, 66f, k(0xFFE91E63), sub = if (canBreed) "нужен ур.2+ · $cost 🪙" else null,
            enabled = canBreed && lvl >= 2 && save.coins >= cost) { breed() }

        btn(c, "▶  В ЗАБЕГ", 8f, 706f, 344f, 70f, k(0xFF00C853)) { startRun() }

        // всплывашки
        for (q in uiPops) {
            val a = q.life.coerceIn(0f, 1f)
            text(c, q.text, q.x, q.y - (1f - q.life) * 50f, q.size, (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
        }
        if (toastT > 0f) {
            val a = min(1f, toastT * 3f)
            panel(c, 40f, 480f, 280f, 34f, ((a * 200).toInt() shl 24), 17f)
            text(c, toastText, 180f, 503f, 17f, ((a * 255).toInt() shl 24) or 0xFFFFFF, maxW = 260f)
        }
        c.restore()
    }

    private fun feed(f: FoodItem) {
        val i = save.selected
        if (save.level(i) >= Balance.MAX_LEVEL) { toast("Уже максимально толстый 👑"); return }
        if (save.coins < f.cost) { toast("Не хватает монет"); return }
        val before = save.level(i)
        save.coins -= f.cost
        save.herd[i] = min(Balance.MAX_FAT, save.herd[i] + f.fat)
        save.save()
        val col = i % 3
        val row = i / 3
        uiPops.add(Pop(8f + col * 118f + 55f, 134f + row * 120f + 30f, "ням! ${f.emoji}", 0xFFFFAB91.toInt(), 22f))
        val after = save.level(i)
        if (after > before) {
            toast("Уровень $after! Тилкайо стал жирнее 😋")
            uiPops.add(Pop(180f, 300f, "УРОВЕНЬ $after!", 0xFFFFD54F.toInt(), 34f))
        }
    }

    private fun breed() {
        val n = save.herd.size
        val cost = Balance.breedCost(n)
        if (n >= Balance.MAX_HERD) return toast("Ферма переполнена")
        if (save.level(save.selected) < 2) return toast("Сначала откорми до ур.2")
        if (save.coins < cost) return toast("Не хватает монет")
        save.coins -= cost
        save.herd.add(0)
        save.save()
        toast("Родился ${Balance.names[n % Balance.names.size]}! 🍼")
        uiPops.add(Pop(180f, 330f, "💕 +1 тилкайо!", 0xFFF48FB1.toInt(), 32f))
    }
}
