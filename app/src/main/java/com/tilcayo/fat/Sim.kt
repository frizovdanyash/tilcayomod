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
    const val SLIDE = 45f
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
    }
}

class Snap(
    val px: Float, val py: Float, val pvx: Float, val pvy: Float,
    val side: Int, val face: Int, val onWall: Boolean, val clingIdx: Int,
    val jumpsLeft: Int, val jumpBuf: Float, val boostT: Float, val time: Float, val bumpCd: Float,
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
    }

    fun stickX(s: Int) = if (s < 0) Phys.WALL + hr else Phys.W - Phys.WALL - hr

    fun forwardDir() = if (pvx > 0f) 1 else if (pvx < 0f) -1 else -side

    // ---------- снимок для решателя ----------

    fun save() = Snap(px, py, pvx, pvy, side, face, onWall, if (cling == null) -1 else pillars.indexOf(cling!!), jumpsLeft, jumpBuf, boostT, time, bumpCd)

    fun load(s: Snap) {
        px = s.px; py = s.py; pvx = s.pvx; pvy = s.pvy
        side = s.side; face = s.face; onWall = s.onWall
        cling = if (s.clingIdx < 0) null else pillars[s.clingIdx]
        jumpsLeft = s.jumpsLeft; jumpBuf = s.jumpBuf; boostT = s.boostT; time = s.time; bumpCd = s.bumpCd
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
            py += Phys.SLIDE * dt
            val pl = cling
            if (pl != null && py - hr * 0.3f > pl.y1) {
                onWall = false
                cling = null
                pvx = 0f
                pvy = 60f
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

        if (camOn) {
            val target = py - viewH * 0.62f
            if (target < camY) camY += (target - camY) * min(1f, dt * 7f)
        }
        if (lavaOn) {
            val height = -minPy
            lavaY -= (30f + min(70f, height / 20f)) * dt
            lavaY = min(lavaY, py + 500f)
            if (shield <= 0f && py + hr > lavaY) return die("Тилкайо сгорел в лаве 🔥")
        }
        if (camOn && py - hr > camY + viewH + 20f) return die("Упал вниз 😵")

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
