package com.tilcayo.fat

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Button
import android.widget.TextView
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

    // ---------- сущности забега ----------
    private class Coin(val x: Float, val y: Float) { var got = false }
    private class Snack(val x: Float, val y: Float, val emoji: String, val fat: Int) { var got = false }
    private class Spike(val left: Boolean, val y0: Float, val y1: Float)
    private class Saw(
        var x: Float, var y: Float,
        val x0: Float, val x1: Float, val y0: Float, val y1: Float,
        var vx: Float, var vy: Float,
    ) { var ang = 0f }
    private class Orbit(val cx: Float, val cy: Float, val rad: Float, var a: Float, val spd: Float, val n: Int) {
        val alive = BooleanArray(n) { true }
    }
    private class Laser(val y: Float, val period: Float, val onTime: Float, val phase: Float)
    private class Pillar(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val hot: Boolean)
    private class Cannon(val x: Float, val y: Float) { var used = false; var ang = 0f }
    private class Bumper(val x: Float, val y: Float) { var pop = 0f }

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
    private val GRAV = 1100f
    private val JUMP_VY = 560f
    private val JUMP_VX = 320f
    private val AIR_VY = 470f
    private val MAX_AIR = 2
    private val SLIDE = 45f

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
    private var modMenuOpen = false
    private var modDialog: AlertDialog? = null
    private val mod = ModSettings(context)
    private val buttons = ArrayList<Btn>()
    private var toastText = ""
    private var toastT = 0f

    // игрок
    private var px = 0f
    private var py = 0f
    private var pvx = 0f
    private var pvy = 0f
    private var side = -1
    private var face = 1
    private var onWall = true
    private var cling: Pillar? = null
    private var jumpsLeft = 0
    private var squash = 0f
    private var jumpBuf = 0f
    private var shield = 0f
    private var boostT = 0f
    private var shake = 0f
    private var inCannon: Cannon? = null
    private var cannonT = 0f
    private var r = 16f
    private var hr = 9f
    private var runLevel = 1

    // забег
    private var camY = 0f
    private var lavaY = 0f
    private var minPy = 0f
    private var genY = 0f
    private var lastCannonH = 0f
    private var calmUntil = Float.POSITIVE_INFINITY
    private var runTime = 0f
    private var runCoins = 0f
    private var coinMul = 1f
    private var combo = 0
    private var comboT = 0f
    private var gotSnacks = 0
    private var deathReason = ""
    private var newRecord = false
    private var earned = 0L

    private val coins = ArrayList<Coin>()
    private val snacks = ArrayList<Snack>()
    private val spikes = ArrayList<Spike>()
    private val saws = ArrayList<Saw>()
    private val orbits = ArrayList<Orbit>()
    private val lasers = ArrayList<Laser>()
    private val pillars = ArrayList<Pillar>()
    private val cannons = ArrayList<Cannon>()
    private val bumpers = ArrayList<Bumper>()
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
        modDialog?.dismiss()
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

        when (scene) {
            Scene.MENU -> drawMenu(c)
            Scene.FARM -> drawFarm(c, dt)
            Scene.PLAY, Scene.DEAD -> {
                if (scene == Scene.PLAY && !paused && !modMenuOpen) {
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
        drawModButton(c)
        postInvalidateOnAnimation()
    }

    // =====================================================================
    // Ввод
    // =====================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN && e.actionMasked != MotionEvent.ACTION_POINTER_DOWN) return true
        val rx = e.getX(e.actionIndex)
        val ry = e.getY(e.actionIndex)
        val ux = (rx - uiOx) / ui
        val uy = (ry - uiOy) / ui
        if (ux in 276f..350f && uy in 132f..174f) {
            showModMenu()
            return true
        }
        if (modMenuOpen) return true
        if (scene == Scene.PLAY && !paused) {
            val wx = rx / scale
            val wy = ry / scale
            if (wx > W - 54f && wy < 70f) {
                paused = true
                sfx.play(Sfx.S.CLICK)
            } else {
                onTapPlay(wx)
            }
            return true
        }
        if (scene == Scene.DEAD && sceneTime < 0.5f) return true
        val hit = buttons.asReversed().firstOrNull { ux >= it.x && ux <= it.x + it.w && uy >= it.y && uy <= it.y + it.h }
        if (hit != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sfx.play(Sfx.S.CLICK)
            hit.onTap()
        } else if (scene == Scene.FARM) {
            onFarmTap(ux, uy)
        }
        return true
    }

    // Мод-меню не меняет paused: закрытие сохраняет предыдущую паузу.
    private fun drawModButton(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        panel(c, 276f, 132f, 74f, 42f, 0xEE00897B.toInt(), 12f)
        text(c, "MOD", 313f, 160f, 20f)
        c.restore()
    }

    private fun showModMenu() {
        if (modMenuOpen) return
        modMenuOpen = true
        val pad = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun label(value: String) {
            content.addView(TextView(context).apply { text = value; setPadding(0, pad / 2, 0, pad / 2) })
        }
        fun toggle(title: String, checked: Boolean, action: (Boolean) -> Unit) {
            content.addView(Switch(context).apply {
                text = title
                isChecked = checked
                setOnCheckedChangeListener { _, enabled -> action(enabled); mod.save() }
            })
        }
        fun action(title: String, task: () -> Unit) {
            content.addView(Button(context).apply { text = title; setOnClickListener { task() } })
        }
        label("Офлайн-мод · настройки сохраняются. Во время открытия забег заморожен.")
        toggle("Бессмертие", mod.godMode) { mod.godMode = it }
        toggle("Сквозь препятствия (стены остаются)", mod.noCollision) {
            mod.noCollision = it
            if (it && scene == Scene.PLAY) {
                cling = null; inCannon = null
                onWall = false; jumpsLeft = MAX_AIR
            }
        }
        toggle("Бесконечные прыжки в воздухе", mod.infiniteJumps) { mod.infiniteJumps = it }
        label("Телепорт вверх на N метров (1 м = 10 единиц). Только в забеге; от 0,1 до 100 000 м.")
        val distance = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(mod.teleportMeters.toString())
            hint = "Расстояние в метрах"
        }
        content.addView(distance)
        action("ТЕЛЕПОРТ ВПЕРЁД / ВВЕРХ") {
            val meters = ModRules.parseMeters(distance.text.toString())
            when {
                scene != Scene.PLAY -> distance.error = "Сначала начните забег"
                meters == null -> distance.error = "Введите число от 0,1 до 100 000"
                else -> {
                    mod.teleportMeters = meters; mod.save()
                    teleportForward(meters)
                    modDialog?.dismiss()
                }
            }
        }
        label("Ферма: перед первым изменением создаётся копия прогресса. Бонусы сохраняются, откат — кнопкой ниже.")
        val status = TextView(context)
        content.addView(status)
        fun farmChange(message: String, change: () -> Unit) {
            save.backupForMod()
            change()
            save.save()
            if (scene == Scene.PLAY) coinMul = save.coinMultiplier()
            status.text = message
        }
        action("+100 000 МОНЕТ") {
            farmChange("Монеты: ${ModRules.addCoins(save.coins, 100_000L)}") {
                save.coins = ModRules.addCoins(save.coins, 100_000L)
            }
        }
        action("ВЫБРАННЫЙ ТИЛКАЙО: УРОВЕНЬ 10") {
            farmChange("Выбранный тилкайо: уровень 10") { save.herd[save.selected] = Balance.MAX_FAT }
        }
        action("СТАДО: 9 ТИЛКАЙО, ВСЕ УРОВНЯ 10") {
            farmChange("Стадо заполнено и откормлено") {
                while (save.herd.size < Balance.MAX_HERD) save.herd.add(0)
                for (i in save.herd.indices) save.herd[i] = Balance.MAX_FAT
            }
        }
        action("ВОССТАНОВИТЬ ПРОГРЕСС ДО БОНУСОВ") {
            if (scene == Scene.PLAY) {
                status.text = "Для восстановления сначала выйдите из забега в меню"
            } else {
                status.text = if (save.restoreModBackup()) "Прогресс восстановлен" else "Резервной копии ещё нет"
                farmFoods.clear()
                syncPets()
            }
        }
        label("Бонус уровня меняет размер бегуна со следующего забега. Телепорт не собирает пропущенные монеты; даёт щит на 2 секунды.")
        modDialog = AlertDialog.Builder(context)
            .setTitle("Тилкайо · MOD")
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton("ЗАКРЫТЬ", null)
            .create().also { dialog ->
                dialog.setOnDismissListener { modMenuOpen = false; modDialog = null; last = System.nanoTime() }
                dialog.show()
            }
    }

    private fun teleportForward(meters: Float) {
        if (scene != Scene.PLAY || !ModRules.validMeters(meters)) return
        py = ModRules.teleportedY(py, meters)
        px = stickX(-1)
        pvx = 0f; pvy = 0f
        side = -1; face = 1
        onWall = true; cling = null; inCannon = null
        cannonT = 0f; boostT = 0f; jumpBuf = 0f; jumpsLeft = MAX_AIR
        shield = max(shield, 2f)
        minPy = min(minPy, py)
        camY = py - viewH * 0.62f
        lavaY = py + 440f
        // Генерируем только новое видимое окно, а не весь пропущенный путь.
        coins.clear(); snacks.clear(); spikes.clear(); saws.clear(); orbits.clear(); lasers.clear()
        pillars.clear(); cannons.clear(); bumpers.clear(); particles.clear(); pops.clear()
        genY = camY + viewH + 200f
        lastCannonH = -py
        calmUntil = py - 200f
        while (genY > camY - 800f) genChunk()
        ring(px, py, 0xFF64FFDA.toInt())
        sfx.play(Sfx.S.SHIELD)
    }

    private fun onTapPlay(wx: Float) {
        when {
            inCannon != null -> fireCannon()
            onWall -> jump()
            jumpsLeft > 0 || mod.infiniteJumps -> airJump(if (wx < W / 2) -1 else 1)
            else -> jumpBuf = 0.2f
        }
    }

    // =====================================================================
    // Забег: логика
    // =====================================================================

    private fun stickX(s: Int) = if (s < 0) WALL + hr else W - WALL - hr

    private fun startRun() {
        scene = Scene.PLAY
        sceneTime = 0f
        paused = false
        runLevel = save.level(save.selected)
        r = 15f + runLevel * 1.7f
        hr = r * 0.55f
        side = -1
        face = 1
        onWall = true
        cling = null
        inCannon = null
        px = stickX(-1)
        py = 0f
        pvx = 0f
        pvy = 0f
        squash = 0f
        jumpBuf = 0f
        jumpsLeft = MAX_AIR
        shield = 0f
        boostT = 0f
        shake = 0f
        camY = py - viewH * 0.62f
        lavaY = py + 440f
        minPy = 0f
        genY = -150f
        lastCannonH = 0f
        calmUntil = Float.POSITIVE_INFINITY
        runTime = 0f
        runCoins = 0f
        coinMul = save.coinMultiplier()
        combo = 0
        comboT = 0f
        gotSnacks = 0
        newRecord = false
        coins.clear(); snacks.clear(); spikes.clear(); saws.clear(); orbits.clear(); lasers.clear()
        pillars.clear(); cannons.clear(); bumpers.clear(); particles.clear(); pops.clear()
    }

    private fun jump() {
        onWall = false
        cling = null
        face = -side
        pvx = face * JUMP_VX
        pvy = -JUMP_VY
        squash = -0.6f
        jumpBuf = 0f
        jumpsLeft = MAX_AIR
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        sfx.play(Sfx.S.JUMP)
        dust(px, py, 6, 0xFFFFFFFF.toInt())
    }

    private fun airJump(d: Int) {
        if (!mod.infiniteJumps) jumpsLeft--
        face = d
        pvx = d * JUMP_VX
        pvy = -AIR_VY
        squash = -0.5f
        sfx.play(Sfx.S.AIR, 1f + (MAX_AIR - jumpsLeft) * 0.12f)
        ring(px, py, 0xFF9BE7FF.toInt())
    }

    private fun land(s: Int, pl: Pillar?) {
        side = s
        cling = pl
        px = if (pl == null) stickX(s) else if (s > 0) pl.x0 - hr else pl.x1 + hr
        onWall = true
        pvx = 0f
        pvy = 0f
        squash = 1f
        jumpsLeft = MAX_AIR
        sfx.play(Sfx.S.LAND, 0.9f + rnd.nextFloat() * 0.2f, 0.7f)
        dust(px - s * r * 0.3f, py, 8, 0xFFE0D6FF.toInt())
        if (jumpBuf > 0f) jump()
    }

    private fun fireCannon() {
        val cn = inCannon ?: return
        cn.used = true
        inCannon = null
        val a = cn.ang
        pvx = sin(a) * 860f
        pvy = -cos(a) * 860f
        px = cn.x + sin(a) * 34f
        py = cn.y - cos(a) * 34f
        boostT = 0.9f
        shield = 2.8f
        jumpsLeft = MAX_AIR
        onWall = false
        cling = null
        shake = 0.35f
        sfx.play(Sfx.S.CAN_FIRE)
        sfx.play(Sfx.S.SHIELD)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        for (i in 0 until 20) {
            val ang = a + (rnd.nextFloat() - 0.5f) * 1.2f
            val sp = 120f + rnd.nextFloat() * 260f
            particles.add(Particle(cn.x, cn.y, sin(ang) * sp, -cos(ang) * sp, 0.6f, 0.6f, 0xFFFFC107.toInt(), 3f + rnd.nextFloat() * 4f))
        }
    }

    private fun stepPlay(dt: Float) {
        runTime += dt
        squash += (0f - squash) * min(1f, dt * 9f)
        jumpBuf = max(0f, jumpBuf - dt)
        shield = max(0f, shield - dt)
        shake = max(0f, shake - dt)
        comboT = max(0f, comboT - dt)
        if (comboT <= 0f) combo = 0
        for (b in bumpers) b.pop = max(0f, b.pop - dt)

        val cn = inCannon
        if (cn != null) {
            cannonT += dt
            cn.ang = sin(cannonT * 2.4f) * 0.95f
            px = cn.x
            py = cn.y
            if (cannonT > 2.4f) fireCannon()
        } else if (onWall) {
            py += SLIDE * dt
            val pl = cling
            if (pl != null && py - hr * 0.3f > pl.y1) {
                onWall = false
                cling = null
                pvx = 0f
                pvy = 60f
            }
        } else {
            val g = if (boostT > 0f) GRAV * 0.12f else GRAV
            boostT = max(0f, boostT - dt)
            pvy = min(pvy + g * dt, 950f)
            px += pvx * dt
            py += pvy * dt
            if (pvx != 0f) face = if (pvx > 0f) 1 else -1
            if (boostT > 0f && rnd.nextFloat() < 0.6f) {
                particles.add(Particle(px, py, (rnd.nextFloat() - 0.5f) * 40f, 40f, 0.4f, 0.4f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 3f))
            }
            if (!mod.noCollision) collidePillars()
            if (!onWall) collideScreenWalls()
        }
        if (py < minPy) minPy = py

        // камера идёт только вверх
        val target = py - viewH * 0.62f
        if (target < camY) camY += (target - camY) * min(1f, dt * 7f)

        // лава
        val height = -minPy
        lavaY -= (30f + min(70f, height / 20f)) * dt
        lavaY = min(lavaY, py + 500f)
        if (!mod.godMode && !mod.noCollision && shield <= 0f && py + hr > lavaY) return die("Тилкайо сгорел в лаве 🔥")
        if (py - hr > camY + viewH + 20f) return die("Упал вниз 😵")

        while (genY > camY - 800f) genChunk()

        val invuln = mod.godMode || mod.noCollision || shield > 0f || inCannon != null
        checkHazards(dt, invuln)
        if (scene != Scene.PLAY) return
        checkPickups()

        if (!newRecord && save.best > 0 && (height / 10f).toInt() > save.best) {
            newRecord = true
            sfx.play(Sfx.S.RECORD)
            pops.add(Pop(W / 2, camY + viewH * 0.3f, "НОВЫЙ РЕКОРД!", 0xFFFFD54F.toInt(), 26f))
        }

        val bottom = camY + viewH + 200f
        coins.removeAll { it.got || it.y > bottom }
        snacks.removeAll { it.got || it.y > bottom }
        spikes.removeAll { it.y0 > bottom }
        saws.removeAll { it.y > bottom + 100f && it.y0 > bottom }
        orbits.removeAll { it.cy > bottom + 100f }
        lasers.removeAll { it.y > bottom }
        pillars.removeAll { it.y0 > bottom }
        cannons.removeAll { it.y > bottom }
        bumpers.removeAll { it.y > bottom }
    }

    private fun collideScreenWalls() {
        val lx = stickX(-1)
        val rx = stickX(1)
        if (px <= lx) {
            if (boostT > 0f) { px = lx; pvx = abs(pvx) } else if (pvx <= 0f) land(-1, null)
        } else if (px >= rx) {
            if (boostT > 0f) { px = rx; pvx = -abs(pvx) } else if (pvx >= 0f) land(1, null)
        }
    }

    private fun collidePillars() {
        for (pl in pillars) {
            if (pl.hot) continue
            val nx = px.coerceIn(pl.x0, pl.x1)
            val ny = py.coerceIn(pl.y0, pl.y1)
            val dx = px - nx
            val dy = py - ny
            if (dx * dx + dy * dy >= hr * hr) continue
            val cx = (pl.x0 + pl.x1) / 2
            val cy = (pl.y0 + pl.y1) / 2
            val inX = px >= pl.x0 && px <= pl.x1
            val inY = py >= pl.y0 && py <= pl.y1
            val sideHit = if (inX) false else if (inY) true else abs(dx) > abs(dy)
            if (sideHit) {
                if (boostT > 0f) { pvx = -pvx; px = if (px < cx) pl.x0 - hr else pl.x1 + hr }
                else land(if (px < cx) 1 else -1, pl)
                return
            } else if (py < cy) {
                // приземлился на верхушку — соскальзывает
                py = pl.y0 - hr
                pvx = if (px < cx) -150f else 150f
                pvy = 40f
            } else {
                py = pl.y1 + hr
                pvy = max(pvy, 120f)
            }
        }
    }

    private fun checkHazards(dt: Float, invuln: Boolean) {
        // шипы на стенах
        if (!invuln) {
            for (s in spikes) {
                val x0 = if (s.left) WALL else W - WALL - SPIKE_LEN
                if (circleRect(px, py, hr, x0, s.y0, x0 + SPIKE_LEN, s.y1)) return die("Наколол попу на шипы 📌")
            }
            for (pl in pillars) {
                if (pl.hot && circleRect(px, py, hr, pl.x0 - 9f, pl.y0, pl.x1 + 9f, pl.y1)) return die("Обжёгся о раскалённый столб 🌋")
            }
        }
        // пилы
        val it = saws.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.vx * dt
            s.y += s.vy * dt
            if (s.x < s.x0) { s.x = s.x0; s.vx = abs(s.vx) }
            if (s.x > s.x1) { s.x = s.x1; s.vx = -abs(s.vx) }
            if (s.y < s.y0) { s.y = s.y0; s.vy = abs(s.vy) }
            if (s.y > s.y1) { s.y = s.y1; s.vy = -abs(s.vy) }
            s.ang += dt * 9f
            if (inCannon != null || mod.noCollision || (mod.godMode && shield <= 0f)) continue
            val dx = s.x - px
            val dy = s.y - py
            val rr = hr + 13f
            if (dx * dx + dy * dy < rr * rr) {
                if (shield > 0f) {
                    smash(s.x, s.y)
                    it.remove()
                } else return die("Распилило пополам ⚙️")
            }
        }
        for (o in orbits) {
            o.a += o.spd * dt
            if (inCannon != null || mod.noCollision || (mod.godMode && shield <= 0f)) continue
            for (i in 0 until o.n) {
                if (!o.alive[i]) continue
                val a = o.a + i * 2f * PI.toFloat() / o.n
                val sx = o.cx + cos(a) * o.rad
                val sy = o.cy + sin(a) * o.rad
                val dx = sx - px
                val dy = sy - py
                val rr = hr + 12f
                if (dx * dx + dy * dy < rr * rr) {
                    if (shield > 0f) { smash(sx, sy); o.alive[i] = false } else return die("Закрутило пилами 🌀")
                }
            }
        }
        // лазеры
        for (l in lasers) {
            val t = (runTime + l.phase) % l.period
            val on = t < l.onTime
            if (!on && t > l.period - 0.06f) sfx.play(Sfx.S.LASER, 1.4f, 0.4f)
            if (on && !invuln && abs(py - l.y) < hr + 4f) return die("Поджарило лазером ⚡")
        }
        // бамперы
        for (b in bumpers) {
            val dx = px - b.x
            val dy = py - b.y
            val rr = hr + 17f
            val d2 = dx * dx + dy * dy
            if (d2 < rr * rr && b.pop <= 0f && inCannon == null && !mod.noCollision) {
                val d = max(1f, sqrt(d2))
                pvx = dx / d * 500f
                pvy = dy / d * 500f - 140f
                onWall = false
                cling = null
                face = if (pvx > 0f) 1 else -1
                jumpsLeft = max(jumpsLeft, 1)
                b.pop = 0.3f
                sfx.play(Sfx.S.BUMP, 0.9f + rnd.nextFloat() * 0.3f)
                ring(b.x, b.y, 0xFFFF8AD8.toInt())
            }
        }
        // пушки
        if (inCannon == null && boostT <= 0f && !mod.noCollision) {
            for (cn in cannons) {
                if (cn.used) continue
                if (hypot(cn.x - px, cn.y - py) < 30f) {
                    inCannon = cn
                    cannonT = 0f
                    px = cn.x; py = cn.y
                    pvx = 0f; pvy = 0f
                    onWall = false
                    cling = null
                    sfx.play(Sfx.S.CAN_IN)
                    break
                }
            }
        }
    }

    private fun sqrt(v: Float) = kotlin.math.sqrt(v)

    private fun smash(x: Float, y: Float) {
        sfx.play(Sfx.S.SMASH)
        shake = max(shake, 0.15f)
        sparkle(x, y, 0xFFCFD8DC.toInt())
        runCoins += coinMul * 3
        pops.add(Pop(x, y - 10f, "+" + fmt(coinMul * 3), 0xFFFFE082.toInt(), 16f))
    }

    private fun checkPickups() {
        for (co in coins) {
            if (co.got) continue
            val dx = co.x - px
            val dy = co.y - py
            val rr = hr + 22f
            if (dx * dx + dy * dy < rr * rr) {
                co.got = true
                runCoins += coinMul
                combo++
                comboT = 0.7f
                sfx.play(Sfx.S.COIN, min(1.9f, 1f + combo * 0.06f))
                sparkle(co.x, co.y, 0xFFFFD54F.toInt())
                pops.add(Pop(co.x, co.y - 8f, "+" + fmt(coinMul), 0xFFFFE082.toInt(), 13f))
            }
        }
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
                sfx.play(Sfx.S.EAT)
                sparkle(sn.x, sn.y, 0xFFFF8A65.toInt())
                pops.add(Pop(sn.x, sn.y - 10f, "ням! +${sn.fat} жира", 0xFFFFAB91.toInt(), 14f))
            }
        }
    }

    private fun die(reason: String) {
        if (mod.godMode || mod.noCollision) {
            // Не оставляем бессмертного игрока за нижним краем камеры.
            if (py - hr > camY + viewH + 20f) {
                py = camY + viewH * 0.62f
                px = stickX(-1)
                onWall = true
                cling = null
                inCannon = null
                pvx = 0f; pvy = 0f
                jumpsLeft = MAX_AIR
                lavaY = py + 440f
            }
            return
        }
        deathReason = reason
        scene = Scene.DEAD
        sceneTime = 0f
        inCannon = null
        earned = runCoins.toLong()
        save.coins += earned
        val score = (-minPy / 10f).toInt()
        if (score > save.best) save.best = score
        save.save()
        sfx.play(Sfx.S.DEATH)
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

    // ---------- генерация чанков ----------

    private fun coin(x: Float, y: Float) { coins.add(Coin(x, y)) }

    private fun coinLine(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i / (n - 1f)
            coin(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }

    private fun pickWeighted(ids: List<Int>, ws: List<Float>): Int {
        var tot = 0f
        for (w in ws) tot += w
        var x = rnd.nextFloat() * tot
        for (i in ids.indices) {
            x -= ws[i]
            if (x <= 0f) return ids[i]
        }
        return ids.last()
    }

    private fun genChunk() {
        val y = genY
        val h = -y
        val d = min(1f, h / 3500f)
        val calm = y > calmUntil
        val fl = WALL + 26f
        val fr = W - WALL - 26f
        val mid = W / 2

        var id: Int
        if (!calm && h > 350f && h - lastCannonH > 1700f) {
            id = 11
        } else {
            val ids = ArrayList<Int>()
            val ws = ArrayList<Float>()
            fun add(i: Int, w: Float, minH: Float = 0f) {
                if (h >= minH && (!calm || i == 0 || i == 12)) { ids.add(i); ws.add(w) }
            }
            add(0, 2.5f); add(1, 3f, 200f); add(2, 2f, 500f); add(3, 3f, 120f); add(4, 2f, 600f)
            add(5, 2.5f, 400f); add(6, 2f, 600f); add(7, 1.5f, 900f); add(8, 1.5f, 1100f)
            add(9, 1.5f, 1300f); add(10, 1.5f, 500f); add(12, 0.8f, 150f); add(13, 1.5f, 1000f)
            id = pickWeighted(ids, ws)
        }
        if (h < 100f) id = 0

        val ch: Float
        when (id) {
            0 -> { // фигуры из монеток
                when (rnd.nextInt(6)) {
                    0 -> for (i in 0 until 8) { // волна
                        val t = i / 7f
                        coin(fl + (fr - fl) * t, y - 70f + sin(t * 2f * PI.toFloat()) * 40f)
                    }
                    1 -> { // колонна
                        val x = fl + 30f + rnd.nextFloat() * (fr - fl - 60f)
                        coinLine(x, y - 20f, x, y - 150f, 6)
                    }
                    2 -> { // кольцо
                        val cx = fl + 60f + rnd.nextFloat() * (fr - fl - 120f)
                        for (i in 0 until 10) {
                            val a = i * 2f * PI.toFloat() / 10f
                            coin(cx + cos(a) * 40f, y - 85f + sin(a) * 40f)
                        }
                    }
                    3 -> if (rnd.nextBoolean()) coinLine(fl, y - 10f, fr, y - 140f, 7) else coinLine(fr, y - 10f, fl, y - 140f, 7)
                    4 -> { // две колонны у стен
                        coinLine(fl - 8f, y - 20f, fl - 8f, y - 140f, 5)
                        coinLine(fr + 8f, y - 20f, fr + 8f, y - 140f, 5)
                    }
                    else -> { // сердечко
                        for (i in 0 until 16) {
                            val t = i * 2f * PI.toFloat() / 16f
                            val hx = 16f * sin(t).let { it * it * it }
                            val hy = 13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)
                            coin(mid + hx * 2.6f, y - 90f - hy * 2.6f)
                        }
                    }
                }
                ch = 170f
            }
            1 -> { // шипы на стене
                val onLeft = rnd.nextBoolean()
                val len = 70f + rnd.nextFloat() * 30f * (0.5f + d)
                val y0 = y - 30f - len
                spikes.add(Spike(onLeft, y0, y0 + len))
                val ox = if (onLeft) W - WALL - 40f else WALL + 40f
                coinLine(ox, y0 + len, ox, y0 - 20f, 5)
                ch = len + 120f
            }
            2 -> { // лесенка из шипов
                val s0 = rnd.nextBoolean()
                for (i in 0 until 3) {
                    val left = (i % 2 == 0) == s0
                    val yk = y - 40f - i * 135f
                    spikes.add(Spike(left, yk - 70f, yk))
                    coin(if (left) W - WALL - 38f else WALL + 38f, yk - 35f)
                }
                ch = 430f
            }
            3 -> { // столб посередине
                val hgt = 130f + rnd.nextFloat() * 80f
                val cx = mid + (rnd.nextFloat() - 0.5f) * 140f
                val y1 = y - 20f
                pillars.add(Pillar(cx - 13f, y1 - hgt, cx + 13f, y1, false))
                coinLine(cx - 40f, y1 - 10f, cx - 40f, y1 - hgt, 4)
                coinLine(cx + 40f, y1 - 10f, cx + 40f, y1 - hgt, 4)
                coin(cx, y1 - hgt - 30f)
                ch = hgt + 100f
            }
            4 -> { // два столба
                val hA = 150f + rnd.nextFloat() * 50f
                val hB = 150f + rnd.nextFloat() * 50f
                val y1 = y - 20f
                val ax = 100f + rnd.nextFloat() * 10f
                val bx = 260f - rnd.nextFloat() * 10f
                pillars.add(Pillar(ax - 13f, y1 - hA, ax + 13f, y1, false))
                pillars.add(Pillar(bx - 13f, y1 - 100f - hB, bx + 13f, y1 - 100f, false))
                coinLine(mid, y1 - 10f, mid, y1 - 190f, 7)
                ch = 100f + max(hA, hB) + 110f
            }
            5 -> { // статичные пилы с проходом
                val rows = if (d > 0.25f) 2 else 1
                val gapSlots = if (d > 0.5f) 1 else 2
                for (rr in 0 until rows) {
                    val ry = y - 50f - rr * 150f
                    val g = rnd.nextInt(6 - gapSlots)
                    for (i in 0 until 5) {
                        if (i >= g && i < g + gapSlots) continue
                        val sx = 62f + i * 59f
                        saws.add(Saw(sx, ry, sx, sx, ry, ry, 0f, 0f))
                    }
                    val gx = 62f + (g + (gapSlots - 1) / 2f) * 59f
                    coin(gx, ry); coin(gx, ry - 32f)
                }
                ch = rows * 150f + 70f
            }
            6 -> { // пила на рельсе
                val span = 170f
                val x0 = fl + rnd.nextFloat() * (fr - fl - span)
                val sy = y - 70f
                val spd = (90f + 70f * d) * (if (rnd.nextBoolean()) 1f else -1f)
                saws.add(Saw(x0 + rnd.nextFloat() * span, sy, x0, x0 + span, sy, sy, spd, 0f))
                coinLine(fl + 10f, sy - 50f, fr - 10f, sy - 50f, 6)
                var extra = 0f
                if (d > 0.5f) {
                    val x2 = fl + rnd.nextFloat() * (fr - fl - span)
                    saws.add(Saw(x2, sy - 120f, x2, x2 + span, sy - 120f, sy - 120f, -spd, 0f))
                    extra = 120f
                }
                ch = 190f + extra
            }
            7 -> { // вертикальная пила
                val cx = fl + 30f + rnd.nextFloat() * (fr - fl - 60f)
                val spd = (90f + 60f * d) * (if (rnd.nextBoolean()) 1f else -1f)
                saws.add(Saw(cx, y - 130f, cx, cx, y - 230f, y - 40f, 0f, spd))
                coinLine(cx - 50f, y - 40f, cx - 50f, y - 230f, 6)
                coinLine(cx + 50f, y - 40f, cx + 50f, y - 230f, 6)
                ch = 280f
            }
            8 -> { // вращающиеся пилы
                val cx = mid + (rnd.nextFloat() - 0.5f) * 60f
                val spd = (1.5f + d) * (if (rnd.nextBoolean()) 1f else -1f)
                orbits.add(Orbit(cx, y - 140f, 58f, rnd.nextFloat() * 6.28f, spd, if (d > 0.5f) 3 else 2))
                coin(cx, y - 140f)
                coinLine(fl, y - 60f, fl, y - 220f, 4)
                coinLine(fr, y - 60f, fr, y - 220f, 4)
                ch = 290f
            }
            9 -> { // лазер
                val ly = y - 90f
                lasers.add(Laser(ly, 3.2f, 1.1f, rnd.nextFloat() * 3.2f))
                coinLine(fl + 20f, ly - 45f, fr - 20f, ly - 45f, 6)
                ch = 190f
            }
            10 -> { // бамперы
                bumpers.add(Bumper(mid - 75f, y - 50f))
                bumpers.add(Bumper(mid + 75f, y - 120f))
                bumpers.add(Bumper(mid - 20f, y - 195f))
                coinLine(mid - 75f, y - 80f, mid + 75f, y - 150f, 4)
                ch = 260f
            }
            11 -> { // пушка
                val cx = mid + (rnd.nextFloat() - 0.5f) * 100f
                cannons.add(Cannon(cx, y - 90f))
                for (i in 0 until 6) {
                    val a = PI.toFloat() * (0.15f + i * 0.14f)
                    coin(cx + cos(a) * 55f, y - 90f + sin(a) * 40f)
                }
                for (i in 0 until 18) coin(mid + sin(i * 0.5f) * 60f, y - 270f - i * 34f)
                lastCannonH = h
                calmUntil = y - 1000f
                ch = 260f
            }
            12 -> { // вкусняшка
                snacks.add(
                    when (rnd.nextInt(3)) {
                        0 -> Snack(mid, y - 60f, "🥕", 20)
                        1 -> Snack(mid, y - 60f, "🍔", 50)
                        else -> Snack(mid, y - 60f, "🍰", 80)
                    },
                )
                ch = 140f
            }
            else -> { // раскалённый столб
                val hgt = 150f + rnd.nextFloat() * 40f
                val cx = mid + (rnd.nextFloat() - 0.5f) * 120f
                val y1 = y - 20f
                pillars.add(Pillar(cx - 13f, y1 - hgt, cx + 13f, y1, true))
                coinLine(cx - 50f, y1 - 10f, cx - 50f, y1 - hgt, 4)
                coinLine(cx + 50f, y1 - 10f, cx + 50f, y1 - hgt, 4)
                ch = hgt + 110f
            }
        }
        genY -= ch
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
            if (s.y < top - 30f || s.y > bot) continue
            // рельса
            if (s.x1 > s.x0 || s.y1 > s.y0) {
                p.color = 0x33FFFFFF
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                c.drawLine(s.x0, s.y0, s.x1, s.y1, p)
                p.style = Paint.Style.FILL
            }
            drawSaw(c, s.x, s.y, s.ang)
        }
        for (o in orbits) {
            if (o.cy < top - 90f || o.cy > bot + 90f) continue
            p.color = 0x33FFFFFF
            c.drawCircle(o.cx, o.cy, 7f, p)
            for (i in 0 until o.n) {
                if (!o.alive[i]) continue
                val a = o.a + i * 2f * PI.toFloat() / o.n
                val sx = o.cx + cos(a) * o.rad
                val sy = o.cy + sin(a) * o.rad
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                p.color = 0x55CFD8DC
                c.drawLine(o.cx, o.cy, sx, sy, p)
                p.style = Paint.Style.FILL
                drawSaw(c, sx, sy, anim * 9f + i)
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
        // кнопка паузы
        panel(c, W - 48f, 14f, 36f, 36f, 0x77000000, 18f)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(W - 38f, 24f, W - 33f, 40f, 2f, 2f, p)
        c.drawRoundRect(W - 27f, 24f, W - 22f, 40f, 2f, 2f, p)
        if (inCannon != null && (anim * 4f).toInt() % 2 == 0) {
            text(c, "ТАП — ВЫСТРЕЛ!", W / 2, viewH * 0.3f, 24f, 0xFFFFC107.toInt(), maxW = 300f)
        } else if (runTime < 5f && inCannon == null) {
            val a = (1f - runTime / 5f).coerceIn(0f, 1f)
            val col = ((a * 255).toInt() shl 24) or 0xFFFFFF
            text(c, "ТАП — прыжок на другую стену", W / 2, viewH * 0.80f, 17f, col, maxW = 320f)
            text(c, "в воздухе ещё 2 прыжка: тап слева / справа", W / 2, viewH * 0.80f + 22f, 14f, col, maxW = 320f)
        }
        c.restore()
    }

    private fun drawPause(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        p.color = 0xAA000000.toInt()
        c.drawRect(-uiOx / ui, -uiOy / ui, 360f + uiOx / ui, 800f + uiOy / ui, p)
        text(c, "ПАУЗА", 180f, 250f, 48f)
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
        text(c, "Доход: ${save.incomePerMin()} 💰/мин", 20f, 93f, 17f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
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
        text(c, "тапни тилкайо, чтобы выбрать · жирнее = больше монет", 180f, 552f, 11f, 0xAAFFFFFF.toInt(), maxW = 340f)

        // еда
        for ((i, f) in Balance.foods.withIndex()) {
            val canBuy = lvl < Balance.MAX_LEVEL && save.coins >= f.cost
            btn(c, "${f.emoji} +${f.fat}", 8f + i * 118f, 562f, 110f, 56f, k(0xFFEF6C00), sub = "${f.cost} 💰", enabled = canBuy) { feed(f) }
        }

        // размножение
        val canBreed = save.herd.size < Balance.MAX_HERD
        val cost = Balance.breedCost(save.herd.size)
        val breedLabel = if (canBreed) "💕 РАЗМНОЖИТЬ" else "Ферма переполнена!"
        btn(c, breedLabel, 8f, 632f, 344f, 62f, k(0xFFE91E63), sub = if (canBreed) "нужен ур.2+ · $cost 💰" else null,
            enabled = canBreed && lvl >= 2 && save.coins >= cost) { breed() }

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
        if (n >= Balance.MAX_HERD) return toast("Ферма переполнена")
        if (save.level(save.selected) < 2) return toast("Сначала откорми до ур.2")
        if (save.coins < cost) return toast("Не хватает монет")
        save.coins -= cost
        save.herd.add(0)
        save.save()
        sfx.play(Sfx.S.BREED)
        toast("Родился ${Balance.names[n % Balance.names.size]}! 🍼")
        val par = pets.getOrNull(save.selected)
        if (par != null) uiPops.add(Pop(par.x, par.y - 70f, "💕 +1 тилкайо!", 0xFFF48FB1.toInt(), 26f))
    }
}
