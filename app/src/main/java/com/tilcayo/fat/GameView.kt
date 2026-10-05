package com.tilcayo.fat

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import kotlin.math.hypot
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

    // ---------- эффекты и UI ----------
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float,
    )
    private class Pop(val x: Float, val y: Float, val text: String, val color: Int, val size: Float) { var life = 1f }
    private class Btn(val x: Float, val y: Float, val w: Float, val h: Float, val onTap: () -> Unit)

    // ---------- ферма ----------
    private class FarmFood(val emoji: String, var x: Float, var y: Float, val owner: Pet) { var z = 170f; var vz = 0f }
    private class Pet(var x: Float, var y: Float) {
        var z = 0f
        var vz = 0f
        var tx = x
        var ty = y
        var st = IDLE
        var t = 0.5f
        var face = 1
        var ph = 0f
        var hops = 0
        var spin = 0f
        var hvx = 0f

        companion object {
            const val IDLE = 0
            const val WALK = 1
            const val HOP = 2
            const val FLIP = 3
            const val EAT = 4
        }
    }

    // ---------- константы ----------
    private val W = 360f
    private val WALL = 34f
    private val SPIKE_LEN = 24f
    private val MAX_AIR = Phys.MAX_AIR
    private val SWIPE_U = 16f

    // фермерский луг (в UI-единицах)
    private val GX0 = 30f
    private val GX1 = 330f
    private val GY0 = 305f
    private val GY1 = 495f

    // ---------- рисование ----------
    private val save = SaveData(context)
    private val sfx = Sfx(context)
    private val music = Music()
    private val sprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.tilcayo)
    private val bossSprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.boss)
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
    private val decor = run {
        val r = Random(42)
        List(26) { Triple(GX0 - 10f + r.nextFloat() * (GX1 - GX0 + 20f), GY0 - 10f + r.nextFloat() * (GY1 - GY0 + 14f), listOf("🌼", "🌷", "🍄", "🌱", "🌸")[r.nextInt(5)]) }
            .sortedBy { it.second }
    }

    // ---------- состояние ----------
    private var scene = Scene.MENU
    private var sceneTime = 0f
    private var anim = 0f
    private var last = System.nanoTime()
    private var paused = false
    private val buttons = ArrayList<Btn>()
    private var toastText = ""
    private var toastT = 0f

    // ядро забега: вся физика и мир живут в Sim, здесь только отображение
    private val sim = Sim()
    private val lib: List<ChunkTemplate> = try {
        ChunkLib.parse(resources.openRawResource(R.raw.chunks).bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        emptyList()
    }
    private val gen = Gen(sim, lib, rnd)

    private val px: Float get() = sim.px
    private val py: Float get() = sim.py
    private val pvx: Float get() = sim.pvx
    private val pvy: Float get() = sim.pvy
    private val side: Int get() = sim.side
    private val face: Int get() = sim.face
    private val onWall: Boolean get() = sim.onWall
    private val squash: Float get() = sim.squash
    private val shield: Float get() = sim.shield
    private val boostT: Float get() = sim.boostT
    private val inCannon: Cannon? get() = sim.inCannon
    private val jumpsLeft: Int get() = sim.jumpsLeft
    private val r: Float get() = sim.r
    private val hr: Float get() = sim.hr
    private val camY: Float get() = sim.camY
    private val lavaY: Float get() = sim.lavaY
    private val minPy: Float get() = sim.minPy
    private val runTime: Float get() = sim.time
    private val runCoins: Float get() = sim.runCoins
    private val coinMul: Float get() = sim.coinMul
    private val coins get() = sim.coins
    private val snacks get() = sim.snacks
    private val spikes get() = sim.spikes
    private val saws get() = sim.saws
    private val orbits get() = sim.orbits
    private val lasers get() = sim.lasers
    private val pillars get() = sim.pillars
    private val cannons get() = sim.cannons
    private val bumpers get() = sim.bumpers

    private val sawPos = FloatArray(2)
    private var bossBanner = 0f
    private var bossHint = 0f
    private val boss: Boss? get() = sim.boss
    private var runLevel = 1
    private var shake = 0f
    private var combo = 0
    private var comboT = 0f
    private var gotSnacks = 0
    private var deathReason = ""
    private var newRecord = false
    private var earned = 0L

    // жест: тап — прыжок вперёд, свайп влево/вправо — рывок в сторону
    private var gesturePtr = -1
    private var gestureX0 = 0f
    private var gestureDone = false

    private val particles = ArrayList<Particle>()
    private val pops = ArrayList<Pop>()

    // ферма
    private val pets = ArrayList<Pet>()
    private val farmFoods = ArrayList<FarmFood>()
    private val uiPops = ArrayList<Pop>()

    private fun k(v: Long) = v.toInt()

    init {
        sfx.enabled = save.sound
        music.start()
        music.setPlaying(save.sound)
    }

    // =====================================================================
    // Жизненный цикл
    // =====================================================================

    fun onHostPause() {
        if (scene == Scene.PLAY) paused = true
        music.setPlaying(false)
        save.save()
    }

    fun onHostResume() {
        music.setPlaying(save.sound)
    }

    fun release() {
        music.release()
        sfx.release()
    }

    /** true — нажатие «назад» обработано внутри игры. */
    fun onBack(): Boolean = when (scene) {
        Scene.PLAY -> { paused = !paused; true }
        Scene.DEAD, Scene.FARM -> { goMenu(); true }
        Scene.MENU -> false
    }

    private fun goMenu() {
        scene = Scene.MENU
        sceneTime = 0f
        paused = false
    }

    private fun goFarm() {
        scene = Scene.FARM
        sceneTime = 0f
        paused = false
    }

    private fun toggleSound() {
        save.sound = !save.sound
        sfx.enabled = save.sound
        music.setPlaying(save.sound)
        save.save()
        sfx.play(Sfx.S.CLICK)
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
        if (scene != Scene.PLAY && save.checkBirth()) {
            sfx.play(Sfx.S.BREED)
            toast("Родился малыш! 🍼")
        }

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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onDown(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex))
            MotionEvent.ACTION_MOVE -> if (gesturePtr >= 0 && !gestureDone) {
                val i = e.findPointerIndex(gesturePtr)
                if (i >= 0) {
                    val dx = (e.getX(i) - gestureX0) / scale
                    if (abs(dx) > SWIPE_U) {
                        gestureDone = true
                        swipeDash(if (dx > 0f) 1 else -1)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (e.getPointerId(e.actionIndex) == gesturePtr) {
                    if (!gestureDone) tapRelease()
                    gesturePtr = -1
                }
            MotionEvent.ACTION_CANCEL -> gesturePtr = -1
        }
        return true
    }

    private fun onDown(id: Int, rx: Float, ry: Float) {
        if (scene == Scene.PLAY && !paused) {
            val wx = rx / scale
            val wy = ry / scale
            when {
                wx > W - 54f && wy < 70f -> { paused = true; sfx.play(Sfx.S.CLICK) }
                sim.inCannon != null -> sim.fireCannon()
                sim.onWall -> sim.jump()
                sim.jumpsLeft > 0 -> { gesturePtr = id; gestureX0 = rx; gestureDone = false }
                else -> sim.jumpBuf = 0.2f
            }
            return
        }
        val ux = (rx - uiOx) / ui
        val uy = (ry - uiOy) / ui
        if (scene == Scene.DEAD && sceneTime < 0.5f) return
        val hit = buttons.asReversed().firstOrNull { ux >= it.x && ux <= it.x + it.w && uy >= it.y && uy <= it.y + it.h }
        if (hit != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sfx.play(Sfx.S.CLICK)
            hit.onTap()
        } else if (scene == Scene.FARM) {
            onFarmTap(ux, uy)
        }
    }

    /** Палец отпущен без свайпа — это тап: прыжок «вперёд», по ходу движения. */
    private fun tapRelease() {
        if (scene != Scene.PLAY || paused || sim.dead) return
        when {
            sim.inCannon != null -> sim.fireCannon()
            sim.onWall -> sim.jump()
            sim.jumpsLeft > 0 -> sim.airJump(sim.forwardDir())
            else -> sim.jumpBuf = 0.2f
        }
    }

    private fun swipeDash(dir: Int) {
        if (scene != Scene.PLAY || paused || sim.dead) return
        when {
            sim.inCannon != null -> sim.fireCannon()
            sim.onWall -> sim.jump()
            sim.jumpsLeft > 0 -> sim.airJump(dir)
        }
    }

    // =====================================================================
    // Забег: связка Sim с эффектами и звуком
    // =====================================================================

    private fun startRun() {
        scene = Scene.PLAY
        sceneTime = 0f
        paused = false
        runLevel = save.level(save.selected)
        sim.reset(runLevel, save.coinMultiplier(), viewH)
        gen.reset()
        gen.fill(sim.camY - 800f)
        shake = 0f
        combo = 0
        comboT = 0f
        gotSnacks = 0
        newRecord = false
        gesturePtr = -1
        bossBanner = 0f
        bossHint = 0f
        particles.clear()
        pops.clear()
    }

    private fun stepPlay(dt: Float) {
        comboT = max(0f, comboT - dt)
        if (comboT <= 0f) combo = 0
        shake = max(0f, shake - dt)
        bossBanner = max(0f, bossBanner - dt)
        bossHint = max(0f, bossHint - dt)
        for (b in sim.bumpers) b.pop = max(0f, b.pop - dt)

        sim.step(dt)
        gen.fill(sim.camY - 800f)
        handleEvents()
        sim.cleanup()

        if (sim.boostT > 0f && rnd.nextFloat() < 0.6f) {
            particles.add(Particle(sim.px, sim.py, (rnd.nextFloat() - 0.5f) * 40f, 40f, 0.4f, 0.4f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 3f))
        }
        if (sim.dead) { finishRun(); return }

        val height = -sim.minPy
        if (!newRecord && save.best > 0 && (height / 10f).toInt() > save.best) {
            newRecord = true
            sfx.play(Sfx.S.RECORD)
            pops.add(Pop(W / 2, sim.camY + viewH * 0.3f, "НОВЫЙ РЕКОРД!", 0xFFFFD54F.toInt(), 26f))
        }
    }

    private fun handleEvents() {
        for (ev in sim.events) {
            when (ev.type) {
                Ev.JUMP -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    sfx.play(Sfx.S.JUMP)
                    dust(ev.x, ev.y, 6, 0xFFFFFFFF.toInt())
                }
                Ev.AIR -> {
                    sfx.play(Sfx.S.AIR, 1f + (MAX_AIR - sim.jumpsLeft) * 0.12f)
                    ring(ev.x, ev.y, 0xFF9BE7FF.toInt())
                }
                Ev.LAND -> {
                    sfx.play(Sfx.S.LAND, 0.9f + rnd.nextFloat() * 0.2f, 0.7f)
                    dust(ev.x - sim.side * sim.r * 0.3f, ev.y, 8, 0xFFE0D6FF.toInt())
                }
                Ev.COIN -> {
                    combo++
                    comboT = 0.7f
                    sfx.play(Sfx.S.COIN, min(1.9f, 1f + combo * 0.06f))
                    sparkle(ev.x, ev.y, 0xFFFFD54F.toInt())
                    pops.add(Pop(ev.x, ev.y - 8f, "+" + fmt(sim.coinMul), 0xFFFFE082.toInt(), 13f))
                }
                Ev.SNACK -> {
                    val sn = ev.ref as Snack
                    gotSnacks++
                    save.herd[save.selected] = min(Balance.MAX_FAT, save.herd[save.selected] + sn.fat)
                    save.save()
                    sfx.play(Sfx.S.EAT)
                    sparkle(sn.x, sn.y, 0xFFFF8A65.toInt())
                    pops.add(Pop(sn.x, sn.y - 10f, "ням! +${sn.fat} жира", 0xFFFFAB91.toInt(), 14f))
                }
                Ev.SMASH -> {
                    sfx.play(Sfx.S.SMASH)
                    shake = max(shake, 0.15f)
                    sparkle(ev.x, ev.y, 0xFFCFD8DC.toInt())
                    pops.add(Pop(ev.x, ev.y - 10f, "+" + fmt(sim.coinMul * 3), 0xFFFFE082.toInt(), 16f))
                }
                Ev.CANNON_IN -> sfx.play(Sfx.S.CAN_IN)
                Ev.CANNON_FIRE -> {
                    val a = ev.ref as Float
                    shake = 0.35f
                    sfx.play(Sfx.S.CAN_FIRE)
                    sfx.play(Sfx.S.SHIELD)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    for (i in 0 until 20) {
                        val ang = a + (rnd.nextFloat() - 0.5f) * 1.2f
                        val sp = 120f + rnd.nextFloat() * 260f
                        particles.add(Particle(ev.x, ev.y, sin(ang) * sp, -cos(ang) * sp, 0.6f, 0.6f, 0xFFFFC107.toInt(), 3f + rnd.nextFloat() * 4f))
                    }
                }
                Ev.BUMP -> {
                    (ev.ref as Bumper).pop = 0.3f
                    sfx.play(Sfx.S.BUMP, 0.9f + rnd.nextFloat() * 0.3f)
                    ring(ev.x, ev.y, 0xFFFF8AD8.toInt())
                }
                Ev.BOSS_START -> {
                    bossBanner = 3f
                    bossHint = 6f
                    shake = 0.5f
                    sfx.play(Sfx.S.BOSS_ROAR)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                Ev.BOSS_TELE -> sfx.play(Sfx.S.WARN)
                Ev.BOSS_FIRE -> sfx.play(Sfx.S.LASER, 0.8f, 0.5f)
                Ev.BOSS_HIT -> {
                    shake = 0.45f
                    bossHint = 0f
                    sfx.play(Sfx.S.BOSS_HIT)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    sparkle(ev.x, ev.y, 0xFFFFFFFF.toInt())
                    for (i in 0 until 14) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        val sp = 100f + rnd.nextFloat() * 200f
                        particles.add(Particle(ev.x, ev.y - 20f, cos(a) * sp, sin(a) * sp - 80f, 0.7f, 0.7f, 0xFFB0BEC5.toInt(), 3f + rnd.nextFloat() * 3f))
                    }
                    pops.add(Pop(ev.x, ev.y - 70f, "БАМ!", 0xFFFFD54F.toInt(), 26f))
                }
                Ev.BOSS_DEAD -> {
                    shake = 0.7f
                    sfx.play(Sfx.S.BOSS_DEAD)
                    pops.add(Pop(W / 2, sim.camY + viewH * 0.3f, "ПОБЕДА!", 0xFFFFD54F.toInt(), 34f))
                    pops.add(Pop(W / 2, sim.camY + viewH * 0.3f + 40f, "+" + fmt(sim.coinMul * 150f) + " монет", 0xFFFFE082.toInt(), 18f))
                    for (i in 0 until 40) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        val sp = 80f + rnd.nextFloat() * 280f
                        particles.add(Particle(ev.x, ev.y, cos(a) * sp, sin(a) * sp - 120f, 1.2f, 1.2f, blockColors[i % 5], 3f + rnd.nextFloat() * 4f))
                    }
                }
                Ev.LASER_ON -> if (ev.y > sim.camY && ev.y < sim.camY + viewH) sfx.play(Sfx.S.LASER, 1.4f, 0.4f)
            }
        }
        sim.events.clear()
    }

    private fun finishRun() {
        deathReason = sim.deathReason
        scene = Scene.DEAD
        sceneTime = 0f
        gesturePtr = -1
        earned = sim.runCoins.toLong()
        save.coins += earned
        val score = (-sim.minPy / 10f).toInt()
        if (score > save.best) save.best = score
        save.save()
        sfx.play(Sfx.S.DEATH)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        for (i in 0 until 28) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = 80f + rnd.nextFloat() * 220f
            particles.add(Particle(sim.px, sim.py, cos(a) * sp, sin(a) * sp - 90f, 0.9f, 0.9f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 4f))
        }
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

    private fun ring(x: Float, y: Float, color: Int) {
        for (i in 0 until 10) {
            val a = i * 2f * PI.toFloat() / 10f
            particles.add(Particle(x, y, cos(a) * 130f, sin(a) * 130f * 0.5f, 0.35f, 0.35f, color, 2.5f))
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

    private val skyTop = intArrayOf(k(0xFF1B1035), k(0xFF0B2A4A), k(0xFF053B2F), k(0xFF3A0F2E), k(0xFF050510))
    private val skyBot = intArrayOf(k(0xFF4A2370), k(0xFF1D6A96), k(0xFF1F8A6A), k(0xFFB5446E), k(0xFF1A1A4A))

    private fun skyAt(height: Float, bottom: Boolean): Int {
        val pal = if (bottom) skyBot else skyTop
        val f = (height / 1800f)
        val i = floor(f).toInt()
        val t = f - i
        return lerpColor(pal[i % pal.size], pal[(i + 1) % pal.size], t)
    }

    private fun drawBackground(c: Canvas, top: Int, bottom: Int, scroll: Float, withStars: Boolean = true) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        if (!withStars) return
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

    /** Рисует тилкайо с центром в (cx, cy); rad — «радиус пузика». */
    private fun drawTilcayo(
        c: Canvas, cx: Float, cy: Float, rad: Float, faceDir: Int, rot: Float,
        sx: Float, sy: Float, level: Int,
    ) {
        val w = rad * 2.5f
        val h = w * sprite.height / sprite.width
        c.save()
        c.translate(cx, cy)
        c.rotate(rot)
        c.scale(sx * faceDir, sy)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        c.drawBitmap(sprite, null, rect, bmpPaint)
        c.restore()
        if (level >= Balance.MAX_LEVEL) text(c, "👑", cx + faceDir * w * 0.02f, cy - h * 0.46f * sy, rad * 0.95f, shadow = false)
    }

    private fun drawParticles(c: Canvas) {
        for (q in particles) {
            val a = (q.life / q.maxLife).coerceIn(0f, 1f)
            p.style = Paint.Style.FILL
            p.color = (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            c.drawCircle(q.x, q.y, q.size * (0.4f + 0.6f * a), p)
        }
    }

    private val blockColors = intArrayOf(k(0xFF8BD450), k(0xFFB06AE8), k(0xFFF2C230), k(0xFF35A9B8), k(0xFFFF5470))

    private fun drawBlock(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = k(0xFF241A38)
        c.drawRoundRect(x, y, x + w, y + h, 5f, 5f, p)
        p.color = color
        c.drawRoundRect(x + 2f, y + 2f, x + w - 2f, y + h - 2f, 4f, 4f, p)
        p.color = 0x55FFFFFF
        c.drawRect(x + 3f, y + 3f, x + w - 3f, y + 7f, p)
        p.color = 0x33000000
        c.drawRect(x + 3f, y + h - 8f, x + w - 3f, y + h - 3f, p)
        p.color = 0x66000000
        c.drawCircle(x + 7f, y + h * 0.5f, 2f, p)
        c.drawCircle(x + w - 7f, y + h * 0.5f, 2f, p)
    }

    // =====================================================================
    // Рисование: забег
    // =====================================================================

    private fun drawWorld(c: Canvas) {
        val height = -minPy
        drawBackground(c, skyAt(height, false), skyAt(height, true), camY)

        c.save()
        c.scale(scale, scale)
        if (shake > 0f) c.translate((rnd.nextFloat() - 0.5f) * shake * 24f, (rnd.nextFloat() - 0.5f) * shake * 24f)
        c.translate(0f, -camY)

        val top = camY - 20f
        val bot = camY + viewH + 20f

        // стены из цветных блоков
        val bh = 52f
        for (leftWall in booleanArrayOf(true, false)) {
            val x0 = if (leftWall) 0f else W - WALL
            var bi = floor(top / bh).toInt()
            while (bi * bh < bot) {
                val ci = (((bi * 3 + (if (leftWall) 0 else 2)) % 5) + 5) % 5
                drawBlock(c, x0, bi * bh, WALL, bh, blockColors[ci])
                bi++
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

        // столбы
        for (pl in pillars) {
            if (pl.y1 < top || pl.y0 > bot) continue
            val seg = 48f
            var yy = pl.y1
            var i = 0
            while (yy > pl.y0) {
                val sh = min(seg, yy - pl.y0)
                val col = if (pl.hot) k(0xFFFF5470) else intArrayOf(k(0xFFFF5470), k(0xFF35A9B8), k(0xFFF2C230))[i % 3]
                drawBlock(c, pl.x0, yy - sh, pl.x1 - pl.x0, sh, col)
                yy -= sh
                i++
            }
            if (pl.hot) {
                p.color = k(0xFFE0E4F5)
                path.reset()
                var ty = pl.y0 + 4f
                while (ty < pl.y1 - 12f) {
                    path.moveTo(pl.x0, ty); path.lineTo(pl.x0 - 9f, ty + 6f); path.lineTo(pl.x0, ty + 12f); path.close()
                    path.moveTo(pl.x1, ty); path.lineTo(pl.x1 + 9f, ty + 6f); path.lineTo(pl.x1, ty + 12f); path.close()
                    ty += 16f
                }
                c.drawPath(path, p)
            }
        }

        // шипы на стенах
        for (s in spikes) {
            if (s.y1 < top || s.y0 > bot) continue
            val baseX = if (s.left) WALL else W - WALL
            val sgn = if (s.left) 1f else -1f
            val n = max(1, ((s.y1 - s.y0) / 14f).toInt())
            val step = (s.y1 - s.y0) / n
            p.style = Paint.Style.FILL
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

        // лазеры
        for (l in lasers) {
            if (l.y < top - 20f || l.y > bot) continue
            val t = (runTime + l.phase) % l.period
            val on = t < l.onTime
            val warn = !on && t > l.period - 0.8f
            p.style = Paint.Style.FILL
            if (on) {
                p.color = 0x55FF1744
                c.drawRect(WALL, l.y - 9f, W - WALL, l.y + 9f, p)
                p.color = k(0xFFFF1744)
                c.drawRect(WALL, l.y - 4f, W - WALL, l.y + 4f, p)
                p.color = k(0xFFFFFFFF)
                c.drawRect(WALL, l.y - 1.2f, W - WALL, l.y + 1.2f, p)
            } else if (warn && ((t * 8f).toInt() % 2 == 0)) {
                p.color = 0x99FF1744.toInt()
                var xx = WALL
                while (xx < W - WALL) { c.drawRect(xx, l.y - 1f, xx + 8f, l.y + 1f, p); xx += 16f }
            }
            p.color = k(0xFF37474F)
            c.drawRoundRect(WALL - 2f, l.y - 11f, WALL + 8f, l.y + 11f, 3f, 3f, p)
            c.drawRoundRect(W - WALL - 8f, l.y - 11f, W - WALL + 2f, l.y + 11f, 3f, 3f, p)
            p.color = if (on) k(0xFFFF1744) else k(0xFF90A4AE)
            c.drawCircle(WALL + 3f, l.y, 3f, p)
            c.drawCircle(W - WALL - 3f, l.y, 3f, p)
        }

        // монетки
        for (co in coins) {
            if (co.y < top - 20f || co.y > bot) continue
            val squeeze = abs(cos(anim * 4f + co.x * 0.05f + co.y * 0.03f))
            p.style = Paint.Style.FILL
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

        // бамперы
        for (b in bumpers) {
            if (b.y < top - 30f || b.y > bot) continue
            val s = 1f + b.pop * 0.8f
            p.style = Paint.Style.FILL
            p.color = k(0xFFD81B60)
            c.drawCircle(b.x, b.y, 17f * s, p)
            p.color = k(0xFFFF80AB)
            c.drawCircle(b.x, b.y, 12f * s, p)
            p.color = k(0xFFFFFFFF)
            c.drawCircle(b.x - 4f, b.y - 4f, 3f, p)
        }

        // пушки
        for (cn in cannons) {
            if (cn.y < top - 50f || cn.y > bot + 50f) continue
            val active = inCannon === cn
            p.style = Paint.Style.FILL
            c.save()
            c.translate(cn.x, cn.y)
            if (active) {
                p.color = 0x66FFFFFF
                var dd = 30f
                while (dd < 200f) {
                    c.drawCircle(sin(cn.ang) * dd, -cos(cn.ang) * dd, 2.2f, p)
                    dd += 18f
                }
            }
            c.save()
            c.rotate(Math.toDegrees(cn.ang.toDouble()).toFloat())
            p.color = if (cn.used) k(0xFF455A64) else k(0xFF546E7A)
            c.drawRoundRect(-11f, -38f, 11f, 0f, 4f, 4f, p)
            p.color = if (cn.used) k(0xFF37474F) else k(0xFFFFB300)
            c.drawRoundRect(-13f, -40f, 13f, -32f, 3f, 3f, p)
            c.restore()
            p.color = if (cn.used) k(0xFF37474F) else k(0xFF263238)
            c.drawCircle(0f, 0f, 22f, p)
            p.color = if (cn.used) k(0xFF455A64) else k(0xFFFFB300)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            c.drawCircle(0f, 0f, 22f, p)
            p.style = Paint.Style.FILL
            if (!cn.used) {
                val pulse = 1f + 0.08f * sin(anim * 6f)
                text(c, "🚀", 0f, 7f * pulse, 20f * pulse, shadow = false)
            }
            c.restore()
        }

        // пилы
        for (s in saws) {
            if (s.dead) continue
            if (max(s.y0, s.y1) < top - 30f || min(s.y0, s.y1) > bot) continue
            s.posAt(runTime, sawPos)
            // рельса
            if (s.len > 1f && s.speed != 0f) {
                p.color = 0x33FFFFFF
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                c.drawLine(s.x0, s.y0, s.x1, s.y1, p)
                p.style = Paint.Style.FILL
            }
            drawSaw(c, sawPos[0], sawPos[1], runTime * 9f + s.phase)
        }
        for (o in orbits) {
            if (o.cy < top - 90f || o.cy > bot + 90f) continue
            p.color = 0x33FFFFFF
            c.drawCircle(o.cx, o.cy, 7f, p)
            for (i in 0 until o.n) {
                if (!o.alive[i]) continue
                val a = o.angle(runTime, i)
                val sx = o.cx + cos(a) * o.rad
                val sy = o.cy + sin(a) * o.rad
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                p.color = 0x55CFD8DC
                c.drawLine(o.cx, o.cy, sx, sy, p)
                p.style = Paint.Style.FILL
                drawSaw(c, sx, sy, runTime * 9f + i)
            }
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
        p.style = Paint.Style.FILL
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

        drawBoss(c, top, bot)

        // тилкайо
        if (scene == Scene.PLAY && inCannon == null) {
            val rot = if (onWall) side * 10f else (face * pvy / 20f).coerceIn(-25f, 25f)
            val stretch = if (onWall) 0f else min(0.18f, abs(pvy) / 3000f)
            val sx = 1f + squash * 0.22f - stretch
            val sy = 1f - squash * 0.22f + stretch
            val faceDir = if (onWall) -side else face
            drawTilcayo(c, px, py, r, faceDir, rot, sx, sy, runLevel)
            if (shield > 0f && (shield > 0.8f || (anim * 10f).toInt() % 2 == 0)) {
                p.style = Paint.Style.FILL
                p.color = 0x3355CCFF
                c.drawCircle(px, py, r * 1.45f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f
                p.color = 0xCC8FE3FF.toInt()
                c.drawCircle(px, py, r * 1.45f, p)
                p.style = Paint.Style.FILL
            }
            if (!onWall && jumpsLeft > 0) {
                // стрелки: куда уйдёт тап (яркая) и свайп назад (тусклая)
                val fwd = sim.forwardDir()
                for (dirSign in intArrayOf(fwd, -fwd)) {
                    val bright = dirSign == fwd
                    val ax = px + dirSign * (r * 1.7f + (if (bright) 4f else 0f))
                    val sz = if (bright) 8f else 6f
                    p.style = Paint.Style.FILL
                    p.color = if (bright) 0xE6FFEB3B.toInt() else 0x66FFFFFF
                    path.reset()
                    path.moveTo(ax + dirSign * sz, py)
                    path.lineTo(ax - dirSign * sz * 0.6f, py - sz)
                    path.lineTo(ax - dirSign * sz * 0.6f, py + sz)
                    path.close()
                    c.drawPath(path, p)
                }
            }
            if (!onWall) {
                // точки доступных прыжков в воздухе
                for (i in 0 until MAX_AIR) {
                    p.color = if (i < jumpsLeft) k(0xFFFFEB3B) else 0x44FFFFFF
                    c.drawCircle(px + (i - (MAX_AIR - 1) / 2f) * 11f, py - r * 1.35f, 3.6f, p)
                }
            }
        }

        drawParticles(c)
        for (q in pops) {
            val a = q.life.coerceIn(0f, 1f)
            val col = (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            text(c, q.text, q.x, q.y - (1f - q.life) * 40f, q.size, col, maxW = 200f)
        }
        c.restore()
    }

    private fun drawBoss(c: Canvas, top: Float, bot: Float) {
        val a = sim.arena
        val b = sim.boss
        if (a != null && a.active) {
            // потолок арены
            p.style = Paint.Style.FILL
            p.color = 0x99140A28.toInt()
            c.drawRect(WALL, a.top - 400f, W - WALL, a.top, p)
            p.color = k(0xFF7C4DFF)
            c.drawRect(WALL, a.top - 3f, W - WALL, a.top, p)
        }
        if (b == null) return
        val phase = b.hpMax - b.hp
        // телеграфы атак
        if (b.state == Boss.RAIN_TELE && a != null) {
            val blink = if ((anim * 10f).toInt() % 2 == 0) 0x55FF1744 else 0x33FF1744
            p.style = Paint.Style.FILL
            p.color = blink
            for (i in 0 until 3) {
                c.drawRect(b.cols[i] - 15f, a.top, b.cols[i] + 15f, a.bottom, p)
                text(c, "!", b.cols[i], a.top + 40f, 30f, 0xFFFF1744.toInt(), shadow = false)
            }
        }
        if (b.state == Boss.CHARGE_WARN || b.state == Boss.CHARGE_MOVE) {
            val blink = if ((anim * 12f).toInt() % 2 == 0) 0x66FF1744 else 0x22FF1744
            p.style = Paint.Style.FILL
            p.color = blink
            c.drawRect(WALL, b.y - Boss.R, W - WALL, b.y + Boss.R, p)
            text(c, if (b.dir > 0) "▶▶▶" else "◀◀◀", W / 2, b.y + 10f, 32f, 0xFFFF1744.toInt(), shadow = false)
        }
        // снаряды
        for (h in sim.hairs) {
            if (h.kind == 0) {
                p.style = Paint.Style.FILL
                p.color = k(0xFF9E9E9E)
                c.drawCircle(h.x, h.y, 9f, p)
                p.color = k(0xFFCFD8DC)
                c.drawCircle(h.x - 2f, h.y - 2f, 6f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = k(0xFF757575)
                for (j in 0 until 5) {
                    val ang = anim * 6f + j * 1.2566f
                    c.drawLine(h.x, h.y, h.x + cos(ang) * 12f, h.y + sin(ang) * 12f, p)
                }
                p.style = Paint.Style.FILL
            } else {
                text(c, "🥕", h.x, h.y + 8f, 22f, shadow = false)
            }
        }
        // сам Толстый Котозаяц
        var sx = 1f + 0.03f * sin(anim * 2.4f)
        var sy = 1f - 0.03f * sin(anim * 2.4f)
        var rot = 0f
        var alpha = 255
        when (b.state) {
            Boss.VOLLEY_TELE, Boss.RAIN_TELE -> { val k2 = 0.1f * sin(b.t * 30f).coerceIn(-1f, 1f); sx = 1.12f + k2; sy = 1.12f - k2 }
            Boss.CHARGE_WARN -> rot = sin(b.t * 50f) * 5f
            Boss.CHARGE_GO -> rot = b.dir * 12f
            Boss.STUN -> rot = sin(anim * 18f) * 9f
            Boss.DEAD -> { rot = b.t * 400f; alpha = (255 * (1f - b.t / 2.2f)).toInt().coerceIn(0, 255) }
        }
        val w = Boss.R * 2.75f
        val h = w * bossSprite.height / bossSprite.width
        c.save()
        c.translate(b.x, b.y - 4f)
        c.rotate(rot)
        c.scale(sx, sy)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        bmpPaint.alpha = alpha
        if (b.flash > 0f) {
            val f = b.flash / 0.5f * 160f
            bmpPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, f, 0f, 1f, 0f, 0f, f, 0f, 0f, 1f, 0f, f, 0f, 0f, 0f, 1f, 0f)))
        } else if (b.state == Boss.VOLLEY_TELE || b.state == Boss.RAIN_TELE || b.state == Boss.CHARGE_WARN) {
            bmpPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1.15f, 0f, 0f, 0f, 20f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        c.drawBitmap(bossSprite, null, rect, bmpPaint)
        bmpPaint.colorFilter = null
        bmpPaint.alpha = 255
        // злые брови во время атаки, обиженные глазки в стане
        val eyeY = -h * 0.21f
        val angry = b.state != Boss.HOVER && b.state != Boss.ENTER && b.state != Boss.STUN && b.state != Boss.DEAD
        if (angry) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.color = k(0xFF3E1A1A)
            c.drawLine(-w * 0.26f, eyeY - 16f, -w * 0.07f, eyeY - 6f, p)
            c.drawLine(w * 0.26f, eyeY - 16f, w * 0.07f, eyeY - 6f, p)
            p.style = Paint.Style.FILL
        }
        c.restore()
        if (b.state == Boss.STUN) text(c, "💫", b.x, b.y - h * 0.55f, 26f, shadow = false)
        if (phase >= 2 && b.state != Boss.DEAD) text(c, "💢", b.x + w * 0.38f, b.y - h * 0.38f, 20f, shadow = false)
    }

    private fun drawSaw(c: Canvas, x: Float, y: Float, ang: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(ang * 57.3f)
        path.reset()
        val teeth = 10
        for (i in 0 until teeth * 2) {
            val a = i * PI.toFloat() / teeth
            val rr = if (i % 2 == 0) 17f else 11f
            if (i == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr)
        }
        path.close()
        p.style = Paint.Style.FILL
        p.color = k(0xFFCFD8DC)
        c.drawPath(path, p)
        p.color = k(0xFF90A4AE)
        c.drawCircle(0f, 0f, 8f, p)
        p.color = k(0xFFFF4D6D)
        c.drawCircle(0f, 0f, 4.5f, p)
        c.restore()
    }

    private fun drawHud(c: Canvas) {
        c.save()
        c.scale(scale, scale)
        val m = (-minPy / 10f).toInt()
        text(c, "$m м", W / 2, 66f, 40f)
        if (save.best > 0) text(c, "рекорд ${save.best} м", W / 2, 86f, 13f, 0xBBFFD54F.toInt())
        panel(c, 12f, 36f, 96f, 30f, 0x55000000)
        text(c, "💰 ${runCoins.toInt()}", 20f, 58f, 19f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 84f)
        if (coinMul > 1f) text(c, "x${fmt(coinMul)}", 12f, 82f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT)
        if (combo >= 3) text(c, "комбо x$combo", 12f, 100f, 13f, 0xFFFFD54F.toInt(), Paint.Align.LEFT)
        // босс: полоска здоровья, баннер, подсказка
        val bs = sim.boss
        if (bs != null && bs.state != Boss.DEAD) {
            panel(c, 40f, 94f, 280f, 34f, 0x99000000.toInt(), 17f)
            text(c, "ТОЛСТЫЙ КОТОЗАЯЦ", W / 2, 111f, 12f, 0xFFFFF3C4.toInt(), maxW = 250f)
            val segW = 250f / bs.hpMax
            for (i in 0 until bs.hpMax) {
                p.style = Paint.Style.FILL
                p.color = if (i < bs.hp) k(0xFFFF5470) else 0x44FFFFFF
                c.drawRoundRect(55f + i * segW + 2f, 117f, 55f + (i + 1) * segW - 2f, 123f, 3f, 3f, p)
            }
        }
        if (bossBanner > 0f) {
            val ba = min(1f, bossBanner).coerceIn(0f, 1f)
            val col = ((ba * 255).toInt() shl 24) or 0xFFFF1744.toInt().and(0xFFFFFF)
            text(c, "⚠ БОСС ⚠", W / 2, viewH * 0.36f, 40f, col, maxW = 330f)
            text(c, "ТОЛСТЫЙ КОТОЗАЯЦ", W / 2, viewH * 0.36f + 34f, 24f, ((ba * 255).toInt() shl 24) or 0xFFFFFF, maxW = 330f)
        } else if (bossHint > 0f && bs != null) {
            val ha = min(1f, bossHint).coerceIn(0f, 1f)
            text(c, "ПРЫГАЙ ЕМУ НА ГОЛОВУ СВЕРХУ!", W / 2, viewH * 0.8f, 16f, ((ha * 255).toInt() shl 24) or 0xFFFFFF, maxW = 330f)
        }
        // кнопка паузы
        panel(c, W - 48f, 14f, 36f, 36f, 0x77000000, 18f)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(W - 38f, 24f, W - 33f, 40f, 2f, 2f, p)
        c.drawRoundRect(W - 27f, 24f, W - 22f, 40f, 2f, 2f, p)
        if (inCannon != null && (anim * 4f).toInt() % 2 == 0) {
            text(c, "ТАП — ВЫСТРЕЛ!", W / 2, viewH * 0.3f, 24f, 0xFFFFC107.toInt(), maxW = 300f)
        } else if (runTime < 7f && inCannon == null && sim.boss == null) {
            val a = (1f - runTime / 7f).coerceIn(0f, 1f)
            val col = ((a * 255).toInt() shl 24) or 0xFFFFFF
            text(c, "ТАП — прыжок на другую стену", W / 2, viewH * 0.78f, 17f, col, maxW = 320f)
            text(c, "в воздухе ещё 2 рывка:", W / 2, viewH * 0.78f + 24f, 14f, col, maxW = 320f)
            text(c, "ТАП — вперёд  ·  СВАЙП ← → — в сторону", W / 2, viewH * 0.78f + 44f, 14f, col, maxW = 320f)
        }
        c.restore()
    }

    private fun drawPause(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        p.color = 0xAA000000.toInt()
        c.drawRect(-uiOx / ui, -uiOy / ui, 360f + uiOx / ui, 800f + uiOy / ui, p)
        text(c, "ПАУЗА", 180f, 230f, 48f)
        text(c, "ТАП — прыжок со стены, в воздухе — рывок вперёд", 180f, 262f, 12f, 0xCCFFFFFF.toInt(), maxW = 330f)
        text(c, "СВАЙП ← → в воздухе — рывок в сторону (хоть назад)", 180f, 280f, 12f, 0xCCFFFFFF.toInt(), maxW = 330f)
        btn(c, "▶  ПРОДОЛЖИТЬ", 50f, 300f, 260f, 64f, k(0xFF00C853)) { paused = false }
        btn(c, if (save.sound) "🔊  ЗВУК: ВКЛ" else "🔇  ЗВУК: ВЫКЛ", 50f, 382f, 260f, 52f, k(0xFF5C6BC0)) { toggleSound() }
        btn(c, "🏠  В МЕНЮ", 50f, 452f, 260f, 52f, k(0xFF7C4DFF)) { goMenu() }
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

        text(c, "💰 +$earned", 180f, 395f, 30f, 0xFFFFE082.toInt())
        if (gotSnacks > 0) text(c, "съедено вкусняшек: $gotSnacks 🍔", 180f, 424f, 16f, 0xFFFFAB91.toInt())

        btn(c, "ЕЩЁ РАЗ", 48f, 450f, 264f, 56f, k(0xFF00C853)) { startRun() }
        btn(c, "🐾 ФЕРМА", 48f, 516f, 264f, 48f, k(0xFF7C4DFF)) { goFarm() }
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
        btn(c, "🐾  ФЕРМА", 50f, 618f, 260f, 64f, k(0xFF7C4DFF), sub = if (save.pending() > 0) "ждёт +${save.pending()} 💰" else null) { goFarm() }
        btn(c, if (save.sound) "🔊" else "🔇", 150f, 706f, 60f, 48f, k(0xFF5C6BC0)) { toggleSound() }

        panel(c, 20f, 20f, 120f, 34f, 0x66000000)
        text(c, "💰 ${save.coins}", 30f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 104f)
        panel(c, 220f, 20f, 120f, 34f, 0x66000000)
        text(c, "🏆 ${save.best} м", 230f, 44f, 20f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 104f)
        c.restore()
    }

    // =====================================================================
    // Ферма: луг, по которому бродят тилкайо
    // =====================================================================

    private fun depthScale(y: Float) = 0.78f + 0.32f * ((y - GY0) / (GY1 - GY0)).coerceIn(0f, 1f)

    private fun petRadius(i: Int, pet: Pet) = (14f + save.level(i) * 2.4f) * depthScale(pet.y)

    private fun syncPets() {
        while (pets.size < save.herd.size) {
            val parent = pets.getOrNull(save.selected)
            val pet = Pet(
                (parent?.x ?: (GX0 + rnd.nextFloat() * (GX1 - GX0))) + (rnd.nextFloat() - 0.5f) * 30f,
                (parent?.y ?: (GY0 + rnd.nextFloat() * (GY1 - GY0))) + (rnd.nextFloat() - 0.5f) * 20f,
            )
            pet.x = pet.x.coerceIn(GX0, GX1)
            pet.y = pet.y.coerceIn(GY0, GY1)
            pets.add(pet)
            hop(pet, 2)
        }
        while (pets.size > save.herd.size) pets.removeAt(pets.size - 1)
    }

    private fun hop(pet: Pet, n: Int) {
        pet.st = Pet.HOP
        pet.hops = n - 1
        pet.vz = 150f
        pet.hvx = pet.face * 20f
    }

    private fun flip(pet: Pet) {
        pet.st = Pet.FLIP
        pet.hops = 0
        pet.vz = 270f
        pet.spin = 0f
        pet.hvx = pet.face * 45f
    }

    private fun pickNext(pet: Pet) {
        val food = farmFoods.filter { it.owner === pet }.minByOrNull { hypot(it.x - pet.x, it.y - pet.y) }
        if (food != null) {
            pet.st = Pet.WALK; pet.tx = food.x; pet.ty = food.y
            return
        }
        val x = rnd.nextFloat()
        when {
            x < 0.45f -> {
                pet.st = Pet.WALK
                pet.tx = GX0 + rnd.nextFloat() * (GX1 - GX0)
                pet.ty = GY0 + rnd.nextFloat() * (GY1 - GY0)
            }
            x < 0.72f -> { hop(pet, 1 + rnd.nextInt(3)); sfx.play(Sfx.S.HOP, 0.9f + rnd.nextFloat() * 0.4f, 0.35f) }
            x < 0.82f -> { flip(pet); sfx.play(Sfx.S.HOP, 1.3f, 0.4f) }
            else -> { pet.st = Pet.IDLE; pet.t = 0.8f + rnd.nextFloat() * 1.8f }
        }
    }

    private fun updatePets(dt: Float) {
        // еда падает
        val fi = farmFoods.iterator()
        while (fi.hasNext()) {
            val f = fi.next()
            if (f.z > 0f || f.vz > 0f) {
                f.vz -= 800f * dt
                f.z += f.vz * dt
                if (f.z <= 0f) { f.z = 0f; f.vz = if (f.vz < -200f) -f.vz * 0.3f else 0f }
            }
        }
        for ((i, pet) in pets.withIndex()) {
            val sp = max(18f, 56f - save.level(i) * 3.5f)
            when (pet.st) {
                Pet.IDLE -> {
                    pet.t -= dt
                    if (pet.t <= 0f) pickNext(pet)
                    if (farmFoods.any { it.owner === pet }) pickNext(pet)
                }
                Pet.WALK -> {
                    val food = farmFoods.filter { it.owner === pet }.minByOrNull { hypot(it.x - pet.x, it.y - pet.y) }
                    if (food != null) { pet.tx = food.x; pet.ty = food.y }
                    val dx = pet.tx - pet.x
                    val dy = pet.ty - pet.y
                    val dist = hypot(dx, dy)
                    if (dist < (if (food != null) 12f else 3f)) {
                        if (food != null && food.z < 6f) {
                            farmFoods.remove(food)
                            pet.st = Pet.EAT
                            pet.t = 0.9f
                            sfx.play(Sfx.S.EAT)
                            uiPops.add(Pop(pet.x, pet.y - petRadius(i, pet) * 2.4f, "ням!", 0xFFFFAB91.toInt(), 18f))
                        } else if (food == null) {
                            pet.st = Pet.IDLE
                            pet.t = 0.4f + rnd.nextFloat() * 1.5f
                        }
                    } else {
                        pet.x += dx / dist * sp * dt
                        pet.y += dy / dist * sp * dt
                        if (abs(dx) > 2f) pet.face = if (dx > 0f) 1 else -1
                        pet.ph += dt * sp * 0.03f
                    }
                }
                Pet.HOP, Pet.FLIP -> {
                    pet.vz -= 700f * dt
                    pet.z += pet.vz * dt
                    pet.x += pet.hvx * dt
                    if (pet.st == Pet.FLIP) pet.spin += dt * 480f
                    if (pet.z <= 0f) {
                        pet.z = 0f
                        pet.spin = 0f
                        if (pet.st == Pet.HOP && pet.hops > 0) {
                            pet.hops--
                            pet.vz = 150f
                        } else {
                            pet.st = Pet.IDLE
                            pet.t = 0.4f + rnd.nextFloat() * 1.2f
                        }
                    }
                }
                Pet.EAT -> {
                    pet.t -= dt
                    if (pet.t <= 0f) { pet.st = Pet.IDLE; pet.t = 0.3f }
                }
            }
            pet.x = pet.x.coerceIn(GX0, GX1)
            pet.y = pet.y.coerceIn(GY0, GY1)
        }
    }

    private fun onFarmTap(ux: Float, uy: Float) {
        if (uy < 250f || uy > 515f) return
        // верхний (ближайший к зрителю) тилкайо под пальцем
        var best = -1
        var bestY = -1f
        for ((i, pet) in pets.withIndex()) {
            val rad = petRadius(i, pet)
            val cy = pet.y - rad * 1.1f - pet.z
            if (abs(ux - pet.x) < rad * 1.2f && uy > cy - rad * 1.2f && uy < pet.y + 6f && pet.y > bestY) {
                best = i
                bestY = pet.y
            }
        }
        if (best >= 0) {
            save.selected = best
            save.save()
            val pet = pets[best]
            if (pet.st != Pet.HOP && pet.st != Pet.FLIP) { if (rnd.nextInt(3) == 0) flip(pet) else hop(pet, 2) }
            sfx.play(Sfx.S.HOP, 1.1f, 0.6f)
            uiPops.add(Pop(pet.x, pet.y - petRadius(best, pet) * 2.4f, "💛", 0xFFFFFFFF.toInt(), 22f))
        }
    }

    private fun drawFarm(c: Canvas, dt: Float) {
        syncPets()
        updatePets(dt)
        stepFx(dt)
        drawBackground(c, k(0xFF4FB3F6), k(0xFFD9F3FF), 0f, withStars = false)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        val ox = -uiOx / ui - 4f
        val ow = 368f + 2 * uiOx / ui

        // солнце и облака
        p.style = Paint.Style.FILL
        p.color = 0x55FFF59D
        c.drawCircle(300f, 165f, 44f, p)
        p.color = k(0xFFFFEB3B)
        c.drawCircle(300f, 165f, 30f, p)
        for (i in 0 until 3) {
            val cx = ((anim * (6f + i * 3f) + i * 140f) % 480f) - 60f
            val cy = 150f + i * 26f
            p.color = 0xCCFFFFFF.toInt()
            c.drawCircle(cx, cy, 15f, p); c.drawCircle(cx + 16f, cy - 8f, 19f, p)
            c.drawCircle(cx + 36f, cy, 15f, p); c.drawRect(cx, cy, cx + 36f, cy + 15f, p)
        }
        // холмы
        for (layer in 0..1) {
            path.reset()
            val base = if (layer == 0) 238f else 250f
            path.moveTo(ox, 320f)
            var xx = ox
            while (xx <= ox + ow + 10f) {
                path.lineTo(xx, base - sin(xx * 0.018f + layer * 2f) * (if (layer == 0) 16f else 10f))
                xx += 10f
            }
            path.lineTo(ox + ow, 320f)
            path.close()
            p.color = if (layer == 0) k(0xFF8BD17F) else k(0xFF6CC067)
            c.drawPath(path, p)
        }
        text(c, "🌳", 28f, 258f, 56f, shadow = false)
        text(c, "🌳", 334f, 254f, 48f, shadow = false)

        // луг с полосами
        p.color = k(0xFF5DBB57)
        c.drawRect(ox, 252f, ox + ow, 515f, p)
        var by = 252f
        var bhh = 12f
        var band = 0
        while (by < 515f) {
            if (band % 2 == 0) { p.color = 0x14FFFFFF; c.drawRect(ox, by, ox + ow, min(515f, by + bhh), p) }
            by += bhh; bhh *= 1.13f; band++
        }
        // забор
        var fx = ox
        while (fx < ox + ow) {
            p.color = k(0xFFD9A760)
            c.drawRoundRect(fx, 246f, fx + 8f, 276f, 3f, 3f, p)
            p.color = k(0xFFB98544)
            c.drawRect(fx + 5f, 248f, fx + 8f, 276f, p)
            fx += 38f
        }
        p.color = k(0xFFE8BC78)
        c.drawRect(ox, 254f, ox + ow, 260f, p)
        c.drawRect(ox, 266f, ox + ow, 272f, p)
        // цветочки
        for (d in decor) text(c, d.third, d.first, d.second, 13f, shadow = false)

        // еда на земле
        for (f in farmFoods) {
            p.color = 0x33000000
            rect.set(f.x - 9f, f.y - 3f, f.x + 9f, f.y + 3f)
            c.drawOval(rect, p)
            text(c, f.emoji, f.x, f.y - 4f - f.z, 20f, shadow = false)
        }

        // тилкайо, отсортированные по глубине
        val order = pets.indices.sortedBy { pets[it].y }
        for (i in order) {
            val pet = pets[i]
            val lvl = save.level(i)
            val rad = petRadius(i, pet)
            val sel = i == save.selected
            // тень
            val sh = 1f / (1f + pet.z / 90f)
            p.style = Paint.Style.FILL
            p.color = ((0x40 * sh).toInt() shl 24)
            rect.set(pet.x - rad * 1.15f * sh, pet.y - rad * 0.2f, pet.x + rad * 1.15f * sh, pet.y + rad * 0.2f)
            c.drawOval(rect, p)
            if (sel) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f
                p.color = k(0xFFFFD54F)
                val pulse = 1f + 0.06f * sin(anim * 5f)
                rect.set(pet.x - rad * 1.35f * pulse, pet.y - rad * 0.3f * pulse, pet.x + rad * 1.35f * pulse, pet.y + rad * 0.3f * pulse)
                c.drawOval(rect, p)
                p.style = Paint.Style.FILL
            }
            var rot = 0f
            var sx = 1f + 0.02f * sin(anim * 2f + i)
            var sy = 1f - 0.02f * sin(anim * 2f + i)
            var bounce = 0f
            when (pet.st) {
                Pet.WALK -> { rot = sin(pet.ph * 2f * PI.toFloat()) * 6f; bounce = abs(sin(pet.ph * 2f * PI.toFloat())) * 2.5f }
                Pet.FLIP -> rot = pet.spin * pet.face
                Pet.EAT -> { sy = 1f - 0.08f * abs(sin(pet.t * 20f)); sx = 1f + 0.05f * abs(sin(pet.t * 20f)) }
                Pet.HOP -> { val st = (pet.vz / 200f).coerceIn(-0.2f, 0.2f); sx = 1f - st * 0.4f; sy = 1f + st * 0.5f }
            }
            val cy = pet.y - rad * 1.1f - pet.z - bounce
            drawTilcayo(c, pet.x, cy, rad, pet.face, rot, sx, sy, lvl)
            if (sel) {
                text(c, "⭐ ${Balance.names[i % Balance.names.size]} · ур.$lvl", pet.x, cy - rad * 1.55f, 12f, 0xFFFFF3C4.toInt(), maxW = 110f)
            } else {
                text(c, "ур.$lvl", pet.x, cy - rad * 1.25f, 10f, 0xCCFFFFFF.toInt())
            }
        }
        drawParticles(c)

        // нижняя панель
        p.color = k(0xFF3B2A1C)
        c.drawRect(ox, 512f, ox + ow, 800f + uiOy / ui + 4f, p)
        p.color = k(0xFF5C4129)
        c.drawRect(ox, 512f, ox + ow, 518f, p)

        // шапка
        btn(c, "◀", 10f, 14f, 52f, 44f, k(0xFF5C6BC0)) { goMenu() }
        text(c, "ФЕРМА", 180f, 48f, 32f)
        panel(c, 250f, 18f, 100f, 36f, 0x66000000)
        text(c, "💰 ${save.coins}", 258f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 86f)

        // доход
        panel(c, 10f, 70f, 340f, 52f, 0x66000000)
        text(c, "Доход: ${fmt(save.incomePerMin())} 💰/мин", 20f, 93f, 17f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
        text(c, "монет в забеге: x${fmt(save.coinMultiplier())}", 20f, 112f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
        val pend = save.pending()
        btn(c, "СОБРАТЬ", 196f, 76f, 148f, 40f, k(0xFFFFA000), enabled = pend > 0) {
            val got = save.collect()
            toast("Собрано +$got 💰")
            sfx.play(Sfx.S.BUY)
        }
        if (pend > 0) text(c, "+$pend", 270f, 70f, 13f, 0xFFFFE082.toInt())

        // выбранный
        val sel = save.selected
        val lvl = save.level(sel)
        val name = Balance.names[sel % Balance.names.size]
        text(c, "$name — бегун в забеге, ур. $lvl", 180f, 536f, 16f, 0xFFFFFFFF.toInt(), maxW = 340f)
        val cur = save.herd[sel]
        val info = if (lvl < Balance.MAX_LEVEL) "жир $cur / ${Balance.fatFor(lvl + 1)} до ур.${lvl + 1} · тапни тилкайо, чтобы выбрать"
        else "максимальный уровень 👑 · тапни тилкайо, чтобы выбрать"
        text(c, info, 180f, 552f, 11f, 0xAAFFFFFF.toInt(), maxW = 340f)

        // еда
        for ((i, f) in Balance.foods.withIndex()) {
            val canBuy = lvl < Balance.MAX_LEVEL && save.coins >= f.cost
            btn(c, "${f.emoji} +${f.fat}", 8f + i * 118f, 562f, 110f, 56f, k(0xFFEF6C00), sub = "${f.cost} 💰", enabled = canBuy) { feed(f) }
        }

        // размножение
        val n = save.herd.size
        val canBreed = n < Balance.MAX_HERD
        val req = Balance.breedLevel(n)
        val cost = Balance.breedCost(n)
        val preg = save.pregnant()
        val left = save.breedLeftMs() / 1000
        val breedLabel = when {
            preg -> "🍼 Роды через %d:%02d".format(left / 60, left % 60)
            !canBreed -> "Ферма переполнена!"
            else -> "💕 РАЗМНОЖИТЬ"
        }
        val breedSub = when {
            preg -> "малыш вынашивается…"
            !canBreed -> null
            else -> "нужен ур.$req+ · $cost 💰 · ${Balance.breedMs(n) / 60000} мин"
        }
        btn(c, breedLabel, 8f, 632f, 344f, 62f, k(0xFFE91E63), sub = breedSub,
            enabled = !preg && canBreed && lvl >= req && save.coins >= cost) { breed() }

        btn(c, "▶  В ЗАБЕГ", 8f, 708f, 344f, 70f, k(0xFF00C853)) { startRun() }

        // всплывашки
        for (q in uiPops) {
            val a = q.life.coerceIn(0f, 1f)
            text(c, q.text, q.x, q.y - (1f - q.life) * 50f, q.size, (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
        }
        if (toastT > 0f) {
            val a = min(1f, toastT * 3f)
            panel(c, 40f, 190f, 280f, 34f, ((a * 200).toInt() shl 24), 17f)
            text(c, toastText, 180f, 213f, 17f, ((a * 255).toInt() shl 24) or 0xFFFFFF, maxW = 260f)
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
        sfx.play(Sfx.S.BUY)
        val pet = pets.getOrNull(i) ?: return
        farmFoods.add(
            FarmFood(
                f.emoji,
                (pet.x + (rnd.nextFloat() - 0.5f) * 120f).coerceIn(GX0, GX1),
                (pet.y + (rnd.nextFloat() - 0.5f) * 50f).coerceIn(GY0, GY1),
                pet,
            ),
        )
        val after = save.level(i)
        if (after > before) {
            sfx.play(Sfx.S.LEVEL)
            toast("Уровень $after! Тилкайо стал жирнее 😋")
            uiPops.add(Pop(180f, 280f, "УРОВЕНЬ $after!", 0xFFFFD54F.toInt(), 34f))
            for (n in 0 until 24) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val sp = 60f + rnd.nextFloat() * 120f
                particles.add(Particle(pet.x, pet.y - 30f, cos(a) * sp, sin(a) * sp - 100f, 0.9f, 0.9f, blockColors[n % 5], 3f + rnd.nextFloat() * 2f))
            }
            flip(pet)
        }
    }

    private fun breed() {
        val n = save.herd.size
        val cost = Balance.breedCost(n)
        val req = Balance.breedLevel(n)
        if (save.pregnant()) return toast("Малыш ещё вынашивается…")
        if (n >= Balance.MAX_HERD) return toast("Ферма переполнена")
        if (save.level(save.selected) < req) return toast("Нужен выбранный тилкайо ур.$req+")
        if (save.coins < cost) return toast("Не хватает монет: нужно $cost")
        save.coins -= cost
        save.breedEnd = System.currentTimeMillis() + Balance.breedMs(n)
        save.save()
        sfx.play(Sfx.S.BUY)
        toast("Малыш родится через ${Balance.breedMs(n) / 60000} мин 💕")
        val par = pets.getOrNull(save.selected)
        if (par != null) uiPops.add(Pop(par.x, par.y - 70f, "💕", 0xFFF48FB1.toInt(), 30f))
    }
}
