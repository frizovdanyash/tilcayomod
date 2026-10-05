package com.tilcayo.fat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Физика забега без единой Android-зависимости: одна и та же логика крутится и в игре,
 * и в «решателе», который при сборке уровней проверяет, что каждый кусок проходим.
 */
object Phys {
    const val W = 360f
    const val WALL = 34f
    const val SPIKE_LEN = 24f
    const val GRAV = 1100f
    const val JUMP_VY = 560f
    const val JUMP_VX = 320f
    const val AIR_VY = 470f
    const val MAX_AIR = 2
    /** Сколько тилкайо намертво держится на стене, прежде чем начать срываться. */
    const val GRIP_TIME = 1.0f
    const val FALL_ACC = 1400f
    const val FALL_MAX = 650f
    const val TAU = (2.0 * PI).toFloat()

    fun radius(level: Int) = 15f + level * 1.7f
    fun hitRadius(level: Int) = radius(level) * 0.55f
}

// ---------------------------------------------------------------------------
// Сущности мира
// ---------------------------------------------------------------------------

class Coin(val x: Float, val y: Float) { var got = false }
class Snack(val x: Float, val y: Float, val emoji: String, val fat: Int) { var got = false }
class Spike(val left: Boolean, val y0: Float, val y1: Float)

/** Пила на рельсе от (x0,y0) до (x1,y1); положение зависит только от времени. */
class Saw(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val speed: Float, val phase: Float) {
    val len = hypot(x1 - x0, y1 - y0)
    var dead = false

    fun posAt(t: Float, out: FloatArray) {
        if (len < 1f || speed == 0f) { out[0] = x0; out[1] = y0; return }
        val m = 2f * len
        var d = (phase + speed * t) % m
        if (d < 0f) d += m
        val p = if (d < len) d else m - d
        val f = p / len
        out[0] = x0 + (x1 - x0) * f
        out[1] = y0 + (y1 - y0) * f
    }
}

class Orbit(val cx: Float, val cy: Float, val rad: Float, val a0: Float, val spd: Float, val n: Int) {
    val alive = BooleanArray(n) { true }
    fun angle(t: Float, i: Int) = a0 + spd * t + i * Phys.TAU / n
}

class Laser(val y: Float, val period: Float, val onTime: Float, val phase: Float) {
    fun isOn(t: Float): Boolean {
        var m = (t + phase) % period
        if (m < 0f) m += period
        return m < onTime
    }
}

class Pillar(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val hot: Boolean)
class Cannon(val x: Float, val y: Float) { var used = false; var ang = 0f }
class Bumper(val x: Float, val y: Float) { var pop = 0f }

/** Арена боя с боссом: пустой участок уровня. */
class Arena(val top: Float, val bottom: Float, val hp: Int) {
    var active = false
    var done = false
}

/** Снаряд босса: клубок шерсти (kind 0) или морковка (kind 1). */
class Hair(var x: Float, var y: Float, var vx: Float, var vy: Float, val kind: Int, var life: Float) {
    fun copy() = Hair(x, y, vx, vy, kind, life)
}

/** Патрон, который можно подобрать в бою с боссом. */
class AmmoItem(val x: Float, val y: Float) {
    var life = 12f
    fun copy() = AmmoItem(x, y).also { it.life = life }
}

/** Выпущенная игроком пуля, самонаводящаяся на босса. */
class Bullet(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float) {
    fun copy() = Bullet(x, y, vx, vy, life)
}

/** Толстый Котозаяц. Все состояния — простые поля, поэтому его легко копировать для ботов. */
class Boss(val hpMax: Int) {
    var x = Phys.W / 2
    var y = 0f
    var hp = hpMax
    var state = ENTER
    var t = 0f
    var hover = 0f
    var stun = 0f
    var flash = 0f
    var attack = 0
    var dir = 1
    var fired = 0
    var seed = 1234567
    var tx = 0f
    val cols = FloatArray(3)
    var face = 1
    var off = 0f
    var dodgeT = 0f
    var dodgeCd = 1.2f
    var dodgeDir = 1
    /** Направление безопасного прохода в кольце пуха. */
    var gapAng = 0f

    fun rand(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65536f
    }

    fun copy(): Boss {
        val b = Boss(hpMax)
        b.x = x; b.y = y; b.hp = hp; b.state = state; b.t = t; b.hover = hover; b.stun = stun; b.flash = flash
        b.attack = attack; b.dir = dir; b.fired = fired; b.seed = seed; b.tx = tx; b.face = face
        b.gapAng = gapAng; b.off = off; b.dodgeT = dodgeT; b.dodgeCd = dodgeCd; b.dodgeDir = dodgeDir
        cols.copyInto(b.cols)
        return b
    }

    companion object {
        const val R = 50f

        /** Босс злой во время атак: тогда касание смертельно, а пули отскакивают. */
        fun angry(state: Int) = state in VOLLEY_TELE..RAIN_FALL

        /** Половина угла безопасного прохода (рад). С каждым ударом проход чуть уже. */
        fun gapHalf(phase: Int) = max(0.45f, 0.66f - 0.035f * phase)
        const val ENTER = 0
        const val HOVER = 1
        const val VOLLEY_TELE = 2
        const val VOLLEY_FIRE = 3
        const val CHARGE_MOVE = 4
        const val CHARGE_WARN = 5
        const val CHARGE_GO = 6
        const val CHARGE_BACK = 7
        const val RAIN_TELE = 8
        const val RAIN_FALL = 9
        const val STUN = 10
        const val DEAD = 11
    }
}

/** Событие для звука и эффектов. */
class Ev(val type: Int, val x: Float = 0f, val y: Float = 0f, val ref: Any? = null) {
    companion object {
        const val JUMP = 0
        const val AIR = 1
        const val LAND = 2
        const val COIN = 3
        const val SNACK = 4
        const val SMASH = 5
        const val CANNON_IN = 6
        const val CANNON_FIRE = 7
        const val BUMP = 8
        const val LASER_ON = 9
        const val DIE = 10
        const val BOSS_START = 11
        const val BOSS_TELE = 12
        const val BOSS_FIRE = 13
        const val BOSS_HIT = 14
        const val BOSS_DEAD = 15
        const val AMMO = 16
        const val SHOOT = 17
        const val BULLET_BLOCK = 18
        const val SLIP = 19
    }
}

class Snap(
    val px: Float, val py: Float, val pvx: Float, val pvy: Float,
    val side: Int, val face: Int, val onWall: Boolean, val clingIdx: Int,
    val jumpsLeft: Int, val jumpBuf: Float, val boostT: Float, val time: Float, val bumpCd: Float,
    val gripT: Float = 0f, val wallVy: Float = 0f,
)

/** Снимок, который включает босса и снаряды (для тестов-ботов). */
class FullState(
    val snap: Snap, val boss: Boss?, val hairs: List<Hair>, val camY: Float, val lavaY: Float,
    val minPy: Float, val runCoins: Float, val shield: Float, val arenaActive: Boolean, val dead: Boolean,
    val bullets: List<Bullet> = emptyList(), val ammoItems: List<AmmoItem> = emptyList(), val ammo: Int = 0, val ammoTimer: Float = 0f,
)

// ---------------------------------------------------------------------------
// Симуляция
// ---------------------------------------------------------------------------

class Sim {
    var viewH = 800f
    var lavaOn = true
    var camOn = true
    var collect = true
    /** Запас на размер хитбокса — решатель перестраховывается. */
    var hrExtra = 0f

    // игрок
    var px = 0f
    var py = 0f
    var pvx = 0f
    var pvy = 0f
    var side = -1
    var face = 1
    var onWall = true
    var cling: Pillar? = null
    var jumpsLeft = Phys.MAX_AIR
    var squash = 0f
    var jumpBuf = 0f
    var shield = 0f
    var boostT = 0f
    var inCannon: Cannon? = null
    var cannonT = 0f
    var bumpCd = 0f
    /** Сколько секунд тилкайо уже на стене и как быстро он срывается вниз. */
    var gripT = 0f
    var wallVy = 0f
    var level = 1
    var r = 16f
    var hr = 9f

    // забег
    var time = 0f
    var camY = 0f
    var lavaY = 0f
    var minPy = 0f
    var genY = 0f
    var runCoins = 0f
    var coinMul = 1f
    var dead = false
    var deathReason = ""
    val events = ArrayList<Ev>()

    // босс
    var arena: Arena? = null
    var boss: Boss? = null
    val hairs = ArrayList<Hair>()
    val bullets = ArrayList<Bullet>()
    val ammoItems = ArrayList<AmmoItem>()
    var ammo = 0
    var ammoTimer = 6f
    var bossesBeaten = 0
    /** Множитель скорости лавы (модификатор). */
    var lavaMul = 1f

    // мир
    val coins = ArrayList<Coin>()
    val snacks = ArrayList<Snack>()
    val spikes = ArrayList<Spike>()
    val saws = ArrayList<Saw>()
    val orbits = ArrayList<Orbit>()
    val lasers = ArrayList<Laser>()
    val pillars = ArrayList<Pillar>()
    val cannons = ArrayList<Cannon>()
    val bumpers = ArrayList<Bumper>()

    private val tmp = FloatArray(2)

    fun clearWorld() {
        coins.clear(); snacks.clear(); spikes.clear(); saws.clear(); orbits.clear(); lasers.clear()
        pillars.clear(); cannons.clear(); bumpers.clear()
    }

    fun reset(level: Int, coinMul: Float, viewH: Float) {
        this.level = level
        this.coinMul = coinMul
        this.viewH = viewH
        r = Phys.radius(level)
        hr = Phys.hitRadius(level)
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
        jumpsLeft = Phys.MAX_AIR
        shield = 0f
        boostT = 0f
        bumpCd = 0f
        gripT = 0f
        wallVy = 0f
        time = 0f
        camY = py - viewH * 0.62f
        lavaY = py + 440f
        minPy = 0f
        genY = -150f
        runCoins = 0f
        dead = false
        deathReason = ""
        events.clear()
        clearWorld()
        arena = null
        boss = null
        hairs.clear()
        bullets.clear()
        ammoItems.clear()
        ammo = 0
        ammoTimer = 6f
        bossesBeaten = 0
    }

    fun stickX(s: Int) = if (s < 0) Phys.WALL + hr else Phys.W - Phys.WALL - hr

    fun forwardDir() = if (pvx > 0f) 1 else if (pvx < 0f) -1 else -side

    // ---------- снимок для решателя ----------

    fun save() = Snap(px, py, pvx, pvy, side, face, onWall, if (cling == null) -1 else pillars.indexOf(cling!!), jumpsLeft, jumpBuf, boostT, time, bumpCd, gripT, wallVy)

    fun saveFull() = FullState(
        save(), boss?.copy(), hairs.map { it.copy() }, camY, lavaY, minPy, runCoins, shield, arena?.active == true, dead,
        bullets.map { it.copy() }, ammoItems.map { it.copy() }, ammo, ammoTimer,
    )

    fun loadFull(f: FullState) {
        load(f.snap)
        boss = f.boss?.copy()
        hairs.clear()
        for (h in f.hairs) hairs.add(h.copy())
        camY = f.camY; lavaY = f.lavaY; minPy = f.minPy; runCoins = f.runCoins; shield = f.shield
        arena?.active = f.arenaActive
        dead = f.dead
        bullets.clear(); for (b in f.bullets) bullets.add(b.copy())
        ammoItems.clear(); for (a in f.ammoItems) ammoItems.add(a.copy())
        ammo = f.ammo
        ammoTimer = f.ammoTimer
    }

    fun load(s: Snap) {
        px = s.px; py = s.py; pvx = s.pvx; pvy = s.pvy
        side = s.side; face = s.face; onWall = s.onWall
        cling = if (s.clingIdx < 0) null else pillars[s.clingIdx]
        jumpsLeft = s.jumpsLeft; jumpBuf = s.jumpBuf; boostT = s.boostT; time = s.time; bumpCd = s.bumpCd
        gripT = s.gripT; wallVy = s.wallVy
        shield = 0f; inCannon = null; squash = 0f; dead = false
    }

    // ---------- управление ----------

    fun jump() {
        onWall = false
        cling = null
        face = -side
        pvx = face * Phys.JUMP_VX
        pvy = -Phys.JUMP_VY
        squash = -0.6f
        jumpBuf = 0f
        jumpsLeft = Phys.MAX_AIR
        events.add(Ev(Ev.JUMP, px, py))
    }

    fun airJump(d: Int) {
        jumpsLeft--
        face = d
        pvx = d * Phys.JUMP_VX
        pvy = -Phys.AIR_VY
        squash = -0.5f
        events.add(Ev(Ev.AIR, px, py))
    }

    private fun land(s: Int, pl: Pillar?) {
        side = s
        cling = pl
        px = if (pl == null) stickX(s) else if (s > 0) pl.x0 - hr else pl.x1 + hr
        onWall = true
        pvx = 0f
        pvy = 0f
        gripT = 0f
        wallVy = 0f
        squash = 1f
        jumpsLeft = Phys.MAX_AIR
        events.add(Ev(Ev.LAND, px, py))
        if (jumpBuf > 0f) jump()
    }

    fun fireCannon() {
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
        jumpsLeft = Phys.MAX_AIR
        onWall = false
        cling = null
        events.add(Ev(Ev.CANNON_FIRE, cn.x, cn.y, a))
    }

    // ---------- шаг ----------

    fun step(dt: Float) {
        if (dead) return
        val prevTime = time
        time += dt
        squash += (0f - squash) * min(1f, dt * 9f)
        jumpBuf = max(0f, jumpBuf - dt)
        shield = max(0f, shield - dt)
        bumpCd = max(0f, bumpCd - dt)

        val cn = inCannon
        if (cn != null) {
            cannonT += dt
            cn.ang = sin(cannonT * 2.4f) * 0.95f
            px = cn.x
            py = cn.y
            if (cannonT > 2.4f) fireCannon()
        } else if (onWall) {
            // секунду висит намертво, потом срывается и быстро падает, пока не прыгнешь
            val before = gripT
            gripT += dt
            if (gripT > Phys.GRIP_TIME) {
                if (before <= Phys.GRIP_TIME) events.add(Ev(Ev.SLIP, px, py))
                wallVy = min(wallVy + Phys.FALL_ACC * dt, Phys.FALL_MAX)
                py += wallVy * dt
            }
            val pl = cling
            if (pl != null && py - hr * 0.3f > pl.y1) {
                onWall = false
                cling = null
                pvx = 0f
                pvy = wallVy
            }
        } else {
            val g = if (boostT > 0f) Phys.GRAV * 0.12f else Phys.GRAV
            boostT = max(0f, boostT - dt)
            pvy = min(pvy + g * dt, 950f)
            px += pvx * dt
            py += pvy * dt
            if (pvx != 0f) face = if (pvx > 0f) 1 else -1
            collidePillars()
            if (!onWall) collideScreenWalls()
        }
        if (py < minPy) minPy = py

        val a = arena
        val locked = a != null && a.active
        if (a != null && !a.active && !a.done && py < a.bottom - 40f) startBoss(a)
        if (locked) {
            // потолок арены
            if (py < a!!.top + hr) { py = a.top + hr; pvy = max(pvy, 0f) }
        }

        if (camOn) {
            if (locked) {
                val target = a!!.bottom + 55f - viewH
                camY += (target - camY) * min(1f, dt * 4f)
            } else {
                val target = py - viewH * 0.62f
                if (target < camY) camY += (target - camY) * min(1f, dt * 7f)
            }
        }
        if (lavaOn) {
            if (locked) {
                lavaY = a!!.bottom + 50f
            } else {
                val height = -minPy
                lavaY -= (30f + min(70f, height / 20f)) * lavaMul * dt
                lavaY = min(lavaY, py + 500f)
            }
            if (shield <= 0f && py + hr > lavaY) return die("Тилкайо сгорел в лаве 🔥")
        }
        if (camOn && py - hr > camY + viewH + 20f) return die("Упал вниз 😵")

        updateBoss(dt)
        if (dead) return
        checkHazards(prevTime, time)
        if (dead) return
        if (collect) checkPickups()
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

    private fun circleRect(cx: Float, cy: Float, rad: Float, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val nx = cx.coerceIn(x0, x1)
        val ny = cy.coerceIn(y0, y1)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < rad * rad
    }

    private fun die(reason: String) {
        dead = true
        deathReason = reason
        events.add(Ev(Ev.DIE, px, py))
    }

    private fun checkHazards(prevTime: Float, t: Float) {
        val hh = hr + hrExtra
        val invuln = shield > 0f || inCannon != null

        if (!invuln) {
            for (s in spikes) {
                val x0 = if (s.left) Phys.WALL else Phys.W - Phys.WALL - Phys.SPIKE_LEN
                if (circleRect(px, py, hh, x0, s.y0, x0 + Phys.SPIKE_LEN, s.y1)) return die("Наколол попу на шипы 📌")
            }
            for (pl in pillars) {
                if (pl.hot && circleRect(px, py, hh, pl.x0 - 9f, pl.y0, pl.x1 + 9f, pl.y1)) return die("Обжёгся о раскалённый столб 🌋")
            }
        }
        if (inCannon == null) {
            for (s in saws) {
                if (s.dead) continue
                s.posAt(t, tmp)
                val dx = tmp[0] - px
                val dy = tmp[1] - py
                val rr = hh + 13f
                if (dx * dx + dy * dy < rr * rr) {
                    if (shield > 0f) {
                        s.dead = true
                        runCoins += coinMul * 3
                        events.add(Ev(Ev.SMASH, tmp[0], tmp[1]))
                    } else return die("Распилило пополам ⚙️")
                }
            }
            for (o in orbits) {
                for (i in 0 until o.n) {
                    if (!o.alive[i]) continue
                    val a = o.angle(t, i)
                    val sx = o.cx + cos(a) * o.rad
                    val sy = o.cy + sin(a) * o.rad
                    val dx = sx - px
                    val dy = sy - py
                    val rr = hh + 12f
                    if (dx * dx + dy * dy < rr * rr) {
                        if (shield > 0f) {
                            o.alive[i] = false
                            runCoins += coinMul * 3
                            events.add(Ev(Ev.SMASH, sx, sy))
                        } else return die("Закрутило пилами 🌀")
                    }
                }
            }
        }
        for (l in lasers) {
            val on = l.isOn(t)
            if (on && !l.isOn(prevTime)) events.add(Ev(Ev.LASER_ON, 0f, l.y))
            if (on && !invuln && abs(py - l.y) < hh + 4f) return die("Поджарило лазером ⚡")
        }
        if (inCannon == null && bumpCd <= 0f) {
            for (b in bumpers) {
                val dx = px - b.x
                val dy = py - b.y
                val rr = hr + 17f
                val d2 = dx * dx + dy * dy
                if (d2 < rr * rr) {
                    val d = max(1f, sqrt(d2))
                    pvx = dx / d * 500f
                    pvy = dy / d * 500f - 140f
                    onWall = false
                    cling = null
                    face = if (pvx > 0f) 1 else -1
                    jumpsLeft = max(jumpsLeft, 1)
                    bumpCd = 0.3f
                    events.add(Ev(Ev.BUMP, b.x, b.y, b))
                    break
                }
            }
        }
        if (collect && inCannon == null && boostT <= 0f) {
            for (cn in cannons) {
                if (cn.used) continue
                if (hypot(cn.x - px, cn.y - py) < 30f) {
                    inCannon = cn
                    cannonT = 0f
                    px = cn.x; py = cn.y
                    pvx = 0f; pvy = 0f
                    onWall = false
                    cling = null
                    events.add(Ev(Ev.CANNON_IN, cn.x, cn.y))
                    break
                }
            }
        }
    }

    // ---------- босс ----------

    private fun startBoss(a: Arena) {
        a.active = true
        val b = Boss(a.hp)
        b.seed = 987654 + bossesBeaten * 7919
        b.x = Phys.W / 2
        b.y = a.top - 140f
        boss = b
        hairs.clear()
        events.add(Ev(Ev.BOSS_START, b.x, b.y))
    }

    private fun ease(k: Float) = k * k * (3f - 2f * k)

    private fun chooseAttack(b: Boss, phase: Int) {
        var next = b.rand().times(3f).toInt().coerceIn(0, 2)
        if (next == b.attack) next = (next + 1) % 3
        b.attack = next
        b.t = 0f
        b.fired = 0
        b.state = when (next) {
            0 -> {
                // безопасный проход — рядом с игроком, но не точно на нём: надо чуть сместиться
                val toP = kotlin.math.atan2(py - b.y, px - b.x)
                val off = (0.4f + 0.5f * b.rand()) * (if (b.rand() < 0.5f) -1f else 1f)
                b.gapAng = toP + off
                Boss.VOLLEY_TELE
            }
            1 -> { b.dir = if (b.rand() < 0.5f) 1 else -1; Boss.CHARGE_MOVE }
            else -> {
                // три колонны дождя на расстоянии друг от друга
                val lo = Phys.WALL + 30f
                val hi = Phys.W - Phys.WALL - 30f
                for (i in 0 until 3) b.cols[i] = lo + (hi - lo) * ((i + 0.15f + 0.7f * b.rand()) / 3f)
                Boss.RAIN_TELE
            }
        }
        events.add(Ev(Ev.BOSS_TELE, b.x, b.y, next))
    }

    private fun updateBoss(dt: Float) {
        val a = arena ?: return
        val b = boss ?: return
        b.flash = max(0f, b.flash - dt)
        val phase = b.hpMax - b.hp
        val mid = Phys.W / 2
        val range = mid - Phys.WALL - Boss.R
        val hoverY = a.top + 200f - 16f * phase
        b.t += dt
        when (b.state) {
            Boss.ENTER -> {
                val k = min(1f, b.t / 1.6f)
                b.y = (a.top - 140f) + (hoverY - (a.top - 140f)) * ease(k)
                if (b.t >= 1.6f) { b.state = Boss.HOVER; b.t = 0f }
            }
            Boss.HOVER -> {
                b.hover += dt
                b.dodgeCd = max(0f, b.dodgeCd - dt)
                if (b.dodgeT > 0f) {
                    b.dodgeT -= dt
                    b.off += b.dodgeDir * 430f * dt
                } else {
                    b.off -= b.off * min(1f, dt * 1.2f)
                }
                // плавно подплывает к траектории парения (после оглушения не дёргается)
                val hx = (mid + sin(b.hover * 0.9f) * range * 0.8f + b.off).coerceIn(mid - range, mid + range)
                val hy = hoverY + sin(b.hover * 1.7f) * 26f
                b.x += (hx - b.x) * min(1f, dt * 14f)
                b.y += (hy - b.y) * min(1f, dt * 10f)
                b.face = if (cos(b.hover * 0.9f) >= 0f) 1 else -1
                // видит, что сверху пикируют, — отпрыгивает в сторону
                if (b.dodgeCd <= 0f && b.dodgeT <= 0f && py < b.y - 30f && py > b.y - 240f &&
                    abs(px - b.x) < Boss.R + 26f && pvy > -80f
                ) {
                    b.dodgeDir = if (px >= b.x) -1 else 1
                    if (b.x + b.dodgeDir * 140f !in (mid - range)..(mid + range)) b.dodgeDir = -b.dodgeDir
                    b.dodgeT = 0.3f
                    b.dodgeCd = max(0.9f, 1.7f - 0.15f * phase)
                }
                if (b.t >= max(0.6f, 1.4f - 0.12f * phase)) chooseAttack(b, phase)
            }
            Boss.VOLLEY_TELE -> {
                b.y = hoverY + sin(b.t * 30f) * 2f
                if (b.t >= max(0.75f, 1.1f - 0.06f * phase)) { b.state = Boss.VOLLEY_FIRE; b.t = 0f; b.fired = 0 }
            }
            Boss.VOLLEY_FIRE -> {
                // кольцо пуха с проходом; позже — второе кольцо с тем же проходом
                val waves = if (phase >= 2) 2 else 1
                if (b.fired < waves && b.t >= 0.6f * b.fired) {
                    b.fired++
                    val n = 18
                    val step = Phys.TAU / n
                    val half = Boss.gapHalf(phase)
                    val sp = 150f + 8f * phase
                    for (i in 0 until n) {
                        val ang = b.gapAng + step / 2f + i * step
                        var diff = (ang - b.gapAng) % Phys.TAU
                        if (diff > Math.PI) diff -= Phys.TAU
                        if (diff < -Math.PI) diff += Phys.TAU
                        if (abs(diff) < half) continue
                        hairs.add(Hair(b.x + cos(ang) * 28f, b.y + sin(ang) * 28f, cos(ang) * sp, sin(ang) * sp, 0, 6f))
                    }
                    events.add(Ev(Ev.BOSS_FIRE, b.x, b.y))
                }
                if (b.fired >= waves && b.t >= 0.6f * (waves - 1) + 0.7f) { b.state = Boss.HOVER; b.t = 0f }
            }
            Boss.CHARGE_MOVE -> {
                val tx = mid - b.dir * range
                b.x += (tx - b.x) * min(1f, dt * 5f)
                b.y += (hoverY - b.y) * min(1f, dt * 5f)
                if (b.t >= 0.7f) { b.state = Boss.CHARGE_WARN; b.t = 0f }
            }
            Boss.CHARGE_WARN -> {
                b.face = b.dir
                if (b.t >= max(0.4f, 0.8f - 0.07f * phase)) { b.state = Boss.CHARGE_GO; b.t = 0f }
            }
            Boss.CHARGE_GO -> {
                b.x += b.dir * (470f + 40f * phase) * dt
                if (b.dir > 0 && b.x >= mid + range) { b.x = mid + range; b.state = Boss.CHARGE_BACK; b.t = 0f }
                if (b.dir < 0 && b.x <= mid - range) { b.x = mid - range; b.state = Boss.CHARGE_BACK; b.t = 0f }
            }
            Boss.CHARGE_BACK -> {
                b.x += (mid - b.x) * min(1f, dt * 3f)
                if (b.t >= 0.8f) { b.state = Boss.HOVER; b.t = 0f }
            }
            Boss.RAIN_TELE -> {
                b.y = hoverY + sin(b.t * 30f) * 2f
                if (b.t >= max(0.5f, 0.95f - 0.08f * phase)) { b.state = Boss.RAIN_FALL; b.t = 0f; b.fired = 0 }
            }
            Boss.RAIN_FALL -> {
                // три залпа: в каждой колонне по морковке
                if (b.fired < 3 && b.t >= 0.24f * b.fired) {
                    b.fired++
                    for (i in 0 until 3) hairs.add(Hair(b.cols[i], a.top - 10f, 0f, 380f + 20f * phase, 1, 6f))
                    events.add(Ev(Ev.BOSS_FIRE, b.cols[1], a.top))
                }
                if (b.fired >= 3 && b.t >= 1.3f) { b.state = Boss.HOVER; b.t = 0f }
            }
            Boss.STUN -> {
                b.x += (mid - b.x) * min(1f, dt * 1.5f)
                b.y += (hoverY + 40f - b.y) * min(1f, dt * 2f)
                if (b.t >= 1.25f) { b.state = Boss.HOVER; b.t = 0f }
            }
            Boss.DEAD -> {
                b.y += 160f * dt
                if (b.t >= 2.2f) {
                    boss = null
                    hairs.clear()
                    bullets.clear()
                    ammoItems.clear()
                    a.active = false
                    a.done = true
                    arena = null
                    bossesBeaten++
                    lavaY = a.bottom + 320f
                }
            }
        }
        // снаряды
        val it = hairs.iterator()
        while (it.hasNext()) {
            val h = it.next()
            h.x += h.vx * dt
            h.y += h.vy * dt
            h.life -= dt
            if (h.life <= 0f || h.y > a.bottom + 60f || h.y < a.top - 90f || h.x < Phys.WALL - 6f || h.x > Phys.W - Phys.WALL + 6f) it.remove()
        }
        if (b.state == Boss.DEAD) return

        // столкновения с игроком
        val hh = hr + hrExtra
        val invuln = shield > 0f || inCannon != null
        if (!invuln) {
            for (h in hairs) {
                val dx = h.x - px
                val dy = h.y - py
                val rr = hh + (if (h.kind == 0) 9f else 10f)
                if (dx * dx + dy * dy < rr * rr) return die(if (h.kind == 0) "Получил клубком шерсти 🧶" else "Прилетело морковкой 🥕")
            }
        }
        if (b.state != Boss.ENTER && inCannon == null) {
            val dx = px - b.x
            val dy = py - b.y
            val rr = hr + Boss.R * 0.92f
            if (dx * dx + dy * dy < rr * rr) {
                if (b.state == Boss.STUN) return
                val fromTop = pvy > 40f && py < b.y - Boss.R * 0.2f
                if (fromTop) {
                    hitBoss(b, a, 0)
                } else if (!Boss.angry(b.state)) {
                    hitBoss(b, a, 1)
                } else if (shield <= 0f) {
                    die("Толстый Котозаяц тебя расплющил 🐰")
                }
            }
        }

        // патроны: появляются редко, пока босс дерётся
        if (b.state != Boss.ENTER) {
            ammoTimer -= dt
            if (ammoTimer <= 0f) {
                ammoTimer = 8f + b.rand() * 5f
                if (ammoItems.isEmpty() && ammo < 3) {
                    val x = Phys.WALL + 36f + b.rand() * (Phys.W - 2 * Phys.WALL - 72f)
                    val y = a.top + 260f + b.rand() * (a.bottom - a.top - 330f)
                    ammoItems.add(AmmoItem(x, y))
                }
            }
        }
        val ai = ammoItems.iterator()
        while (ai.hasNext()) {
            val it2 = ai.next()
            it2.life -= dt
            val ddx = it2.x - px
            val ddy = it2.y - py
            val rr2 = hr + 20f
            if (it2.life <= 0f) ai.remove()
            else if (ddx * ddx + ddy * ddy < rr2 * rr2) {
                ai.remove()
                ammo = min(3, ammo + 1)
                events.add(Ev(Ev.AMMO, it2.x, it2.y))
            }
        }

        // пули самонаводятся на босса и ранят его, только если он не злой
        val bi = bullets.iterator()
        while (bi.hasNext()) {
            val bu = bi.next()
            bu.life -= dt
            val tx = b.x - bu.x
            val ty = b.y - bu.y
            val d = max(1f, kotlin.math.sqrt(tx * tx + ty * ty))
            val sp = 560f
            val cur = kotlin.math.atan2(bu.vy, bu.vx)
            val want = kotlin.math.atan2(ty, tx)
            var diff = want - cur
            while (diff > Math.PI) diff -= Phys.TAU
            while (diff < -Math.PI) diff += Phys.TAU
            val turn = (7f * dt).coerceAtMost(abs(diff)) * (if (diff >= 0f) 1f else -1f)
            val na = cur + turn
            bu.vx = cos(na) * sp
            bu.vy = sin(na) * sp
            bu.x += bu.vx * dt
            bu.y += bu.vy * dt
            if (bu.life <= 0f) { bi.remove(); continue }
            if (d < Boss.R * 0.9f && b.state != Boss.ENTER && b.state != Boss.DEAD) {
                bi.remove()
                if (!Boss.angry(b.state) && b.state != Boss.STUN) hitBoss(b, a, 2)
                else events.add(Ev(Ev.BULLET_BLOCK, bu.x, bu.y))
            }
        }
    }

    /** Выстрел по боссу. Возвращает true, если патрон потрачен. */
    fun shoot(): Boolean {
        val b = boss ?: return false
        if (ammo <= 0 || dead || b.state == Boss.DEAD) return false
        ammo--
        val ang = kotlin.math.atan2(b.y - py, b.x - px)
        bullets.add(Bullet(px, py, cos(ang) * 560f, sin(ang) * 560f, 2.6f))
        events.add(Ev(Ev.SHOOT, px, py))
        return true
    }

    /** Режим «Босс-раш»: игрок сразу в арене, остального уровня нет. */
    fun startRush(hp: Int) {
        val ah = min(620f, viewH - 150f)
        val a = Arena(-80f - ah, -80f, hp)
        arena = a
        side = -1
        face = 1
        onWall = true
        px = stickX(-1)
        py = a.bottom - 120f
        minPy = py
        camY = a.bottom + 55f - viewH
        lavaY = a.bottom + 50f
        genY = a.top - 100f
    }

    /** Урон боссу. kind: 0 — прыжок сверху, 1 — касание, пока он не злой, 2 — пуля. */
    private fun hitBoss(b: Boss, a: Arena, kind: Int) {
        b.hp--
        b.flash = 0.5f
        when (kind) {
            0 -> {
                pvy = -560f
                pvx = if (px < b.x) -120f else 120f
            }
            1 -> {
                // отбрасывает от босса, чтобы не прилипнуть
                val dx = px - b.x
                val dy = py - b.y
                val d = max(1f, kotlin.math.sqrt(dx * dx + dy * dy))
                pvx = dx / d * 380f
                pvy = dy / d * 380f - 220f
            }
        }
        if (kind != 2) {
            onWall = false
            cling = null
            jumpsLeft = Phys.MAX_AIR
            shield = 0.8f
        }
        hairs.clear()
        events.add(Ev(Ev.BOSS_HIT, b.x, b.y, kind))
        if (b.hp <= 0) {
            b.state = Boss.DEAD
            b.t = 0f
            runCoins += coinMul * 150f
            for (i in 0 until 24) {
                val ang = i * Phys.TAU / 24f
                coins.add(Coin(b.x + cos(ang) * 70f, b.y + sin(ang) * 55f))
            }
            events.add(Ev(Ev.BOSS_DEAD, b.x, b.y))
        } else {
            b.state = Boss.STUN
            b.t = 0f
        }
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
                events.add(Ev(Ev.COIN, co.x, co.y))
            }
        }
        for (sn in snacks) {
            if (sn.got) continue
            val dx = sn.x - px
            val dy = sn.y - py
            val rr = hr + 22f
            if (dx * dx + dy * dy < rr * rr) {
                sn.got = true
                events.add(Ev(Ev.SNACK, sn.x, sn.y, sn))
            }
        }
    }

    /** Выкидывает всё, что давно осталось внизу. */
    fun cleanup() {
        val bottom = camY + viewH + 200f
        coins.removeAll { it.got || it.y > bottom }
        snacks.removeAll { it.got || it.y > bottom }
        spikes.removeAll { it.y0 > bottom }
        saws.removeAll { it.dead || max(it.y0, it.y1) > bottom + 100f }
        orbits.removeAll { it.cy > bottom + 100f }
        lasers.removeAll { it.y > bottom }
        pillars.removeAll { it.y0 > bottom }
        cannons.removeAll { it.y > bottom }
        bumpers.removeAll { it.y > bottom }
    }
}
