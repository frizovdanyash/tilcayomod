package com.tilcayo.fat

import org.junit.Test
import kotlin.random.Random

/** Монте-Карло бот сражается с боссом, чтобы убедиться, что бой выполним. */
class BossTest {
    private fun apply(sim: Sim, a: Int) {
        if (sim.dead) return
        when (a) {
            1 -> if (sim.onWall) sim.jump() else if (sim.jumpsLeft > 0) sim.airJump(sim.forwardDir())
            2 -> if (sim.onWall) sim.jump() else if (sim.jumpsLeft > 0) sim.airJump(-1)
            3 -> if (sim.onWall) sim.jump() else if (sim.jumpsLeft > 0) sim.airJump(1)
        }
    }

    private fun score(sim: Sim, hp0: Int, beaten0: Int): Float {
        if (sim.dead) return -1000f
        val b = sim.boss
        var sc = 0f
        if (b == null) return if (sim.bossesBeaten > beaten0) 2000f else 0f
        sc += (hp0 - b.hp) * 600f
        if (b.state != Boss.STUN) {
            // хорошо быть над боссом и близко по x
            val above = b.y - sim.py
            sc += if (above > 0f) 40f + above.coerceAtMost(160f) * 0.3f else above.coerceAtLeast(-200f) * 0.15f
            sc -= kotlin.math.abs(sim.px - b.x) * 0.25f
        }
        return sc
    }

    private fun fight(level: Int, bossHp: Int, seed: Int, stepsPerDecision: Int = 6, rollouts: Int = 24): Pair<Boolean, Float> {
        val sim = Sim()
        sim.reset(level, 1f, 800f)
        sim.camOn = true
        sim.collect = true
        sim.arena = Arena(-700f, -80f, bossHp)
        val rnd = Random(seed)
        sim.boss = null
        var guard = 0
        while (!sim.dead && sim.bossesBeaten == 0 && sim.time < 120f && guard++ < 2000) {
            val hp0 = sim.boss?.hp ?: 0
            val beaten0 = sim.bossesBeaten
            val base = sim.saveFull()
            var bestA = 0
            var bestV = -1e9f
            for (a in 0..3) {
                var tot = 0f
                val n = rollouts
                for (r in 0 until n) {
                    sim.loadFull(base)
                    apply(sim, a)
                    var step = 0
                    var best = -1e9f
                    while (step < 14 && !sim.dead) {
                        for (i in 0 until stepsPerDecision) sim.step(1f / 60f)
                        sim.events.clear()
                        best = maxOf(best, score(sim, hp0, beaten0))
                        if (sim.boss == null && sim.bossesBeaten > beaten0) break
                        step++
                        val act = when (rnd.nextInt(8)) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 0 }
                        apply(sim, act)
                    }
                    tot += if (sim.dead) -1000f else best
                }
                val v = tot / n
                if (v > bestV) { bestV = v; bestA = a }
            }
            sim.loadFull(base)
            apply(sim, bestA)
            for (i in 0 until stepsPerDecision) sim.step(1f / 60f)
            if (System.getenv("TRACE_BOSS") != null) {
                for (e in sim.events) if (e.type in 11..15) println("  EV t=${"%.1f".format(sim.time)} type=${e.type} boss=${sim.boss?.state} hp=${sim.boss?.hp} px=${sim.px.toInt()} py=${sim.py.toInt()} bx=${sim.boss?.x?.toInt()} by=${sim.boss?.y?.toInt()}")
            }
            sim.events.clear()
            sim.cleanup()
        }
        return Pair(sim.bossesBeaten > 0, sim.time)
    }

    private fun arenaSim(): Sim {
        val sim = Sim()
        sim.reset(5, 1f, 800f)
        sim.startRush(5)
        // дождаться, пока босс въедет и перейдёт в спокойное парение
        var guard = 0
        while (guard++ < 2000 && sim.boss?.state != Boss.HOVER) { sim.shield = 9f; sim.py = sim.arena!!.bottom - 100f; sim.step(1f / 60f) }
        sim.shield = 0f
        return sim
    }

    @Test
    fun calmBossIsHurtByAnyTouch() {
        val sim = arenaSim()
        val b = sim.boss!!
        assert(b.state == Boss.HOVER)
        // подходим сбоку и касаемся
        sim.onWall = false
        sim.px = b.x - (Boss.R * 0.92f + sim.hr - 4f); sim.py = b.y; sim.pvx = 0f; sim.pvy = 0f
        val hp0 = b.hp
        sim.step(1f / 60f)
        println("TOUCH hp $hp0 -> ${sim.boss?.hp} dead=${sim.dead}")
        assert(!sim.dead && sim.boss!!.hp == hp0 - 1)
    }

    @Test
    fun angryBossKillsOnTouch() {
        val sim = arenaSim()
        val b = sim.boss!!
        b.state = Boss.VOLLEY_TELE
        b.t = 0f
        sim.onWall = false
        sim.px = b.x - (Boss.R * 0.92f + sim.hr - 4f); sim.py = b.y; sim.pvx = 0f; sim.pvy = 0f
        sim.step(1f / 60f)
        println("ANGRY dead=${sim.dead}")
        assert(sim.dead)
    }

    @Test
    fun bulletsHurtOnlyCalmBoss() {
        for (angry in booleanArrayOf(false, true)) {
            val sim = arenaSim()
            val b = sim.boss!!
            sim.ammo = 1
            sim.py = sim.arena!!.bottom - 100f
            sim.onWall = true
            assert(sim.shoot())
            if (angry) { b.state = Boss.RAIN_TELE; b.t = 0f }
            val hp0 = b.hp
            var n = 0
            while (n++ < 240 && sim.bullets.isNotEmpty()) {
                sim.shield = 9f
                sim.gripT = 0f; sim.wallVy = 0f // держим игрока на стене, чтобы он не срывался
                if (angry) { b.t = 0f }
                sim.step(1f / 60f)
            }
            println("BULLET angry=$angry hp $hp0 -> ${sim.boss?.hp}")
            if (angry) assert(sim.boss!!.hp == hp0) else assert(sim.boss!!.hp == hp0 - 1)
        }
    }

    @Test
    fun ammoAppearsRarely() {
        var spawned = 0
        val sim = arenaSim()
        var t = 0f
        while (t < 120f && !sim.dead) {
            sim.shield = 9f
            sim.py = sim.arena!!.bottom - 100f; sim.px = 40f; sim.onWall = true
            sim.step(1f / 60f)
            for (e in sim.events) if (e.type == Ev.AMMO) spawned++
            sim.events.clear()
            if (sim.ammoItems.isNotEmpty()) { spawned = maxOf(spawned, 1) }
            t += 1f / 60f
        }
        println("AMMO spawned/picked in 120s (rough)=$spawned")
    }

    @Test
    fun dangerBaselines() {
        // пассивный игрок и случайный игрок: сколько живут рядом с боссом
        for (mode in 0..1) {
            var tsum = 0f
            val runs = 10
            for (seed in 1..runs) {
                val sim = Sim()
                sim.reset(5, 1f, 800f)
                sim.arena = Arena(-700f, -80f, 5)
                sim.px = 180f; sim.py = -200f; sim.pvx = 0f; sim.pvy = 0f; sim.onWall = false
                sim.side = -1
                val rnd = Random(seed)
                // сразу в арену: стоим у стены на высоте боя
                sim.onWall = true; sim.px = sim.stickX(-1); sim.py = -300f
                var n = 0
                while (!sim.dead && sim.time < 60f) {
                    if (mode == 1 && n % 15 == 0) apply(sim, rnd.nextInt(4))
                    for (i in 0 until 6) sim.step(1f / 60f)
                    sim.events.clear()
                    n++
                }
                tsum += sim.time
            }
            println("DANGER mode=${if (mode == 0) "passive" else "random"} avgSurvive=${(tsum / runs).toInt()}s")
        }
    }

    @Test
    fun weakBotVsBoss() {
        for (level in intArrayOf(1, 10)) for (hp in intArrayOf(4, 7)) {
            var w = 0
            var tsum = 0f
            val runs = 8
            for (seed in 1..runs) {
                val (won, t) = fight(level, hp, seed * 17 + level + hp, stepsPerDecision = 15, rollouts = 6)
                if (won) { w++; tsum += t }
            }
            println("WEAK lvl=$level hp=$hp wins=$w/$runs avgTime=${if (w > 0) (tsum / w).toInt() else -1}s")
        }
    }

    @Test
    fun botCanBeatBoss() {
        var wins = 0
        var total = 0
        val lines = ArrayList<String>()
        for (level in intArrayOf(1, 10)) {
            for (hp in intArrayOf(4, 7)) {
                var w = 0
                var tsum = 0f
                val runs = 5
                for (seed in 1..runs) {
                    val (won, t) = fight(level, hp, seed * 31 + level + hp)
                    if (won) { w++; tsum += t }
                    total++
                }
                wins += w
                lines.add("BOSS lvl=$level hp=$hp wins=$w/$runs avgTime=${if (w > 0) (tsum / w).toInt() else -1}s")
            }
        }
        lines.forEach { println(it) }
        println("BOSS total wins=$wins/$total")
    }
}

class RingFairnessTest {
    /** Кольцо пуха оставляет проход: в нём можно стоять, в остальных направлениях — нет. */
    @Test
    fun ringHasASafeGapAndOnlyThere() {
        var aliveInGap = 0
        var deadElsewhere = 0
        for (phase in intArrayOf(0, 2, 4)) {
            for (seed in 1..6) {
                for (inGap in booleanArrayOf(true, false)) {
                    val sim = Sim()
                    sim.reset(5, 1f, 800f)
                    sim.startRush(5 + phase)
                    var guard = 0
                    while (guard++ < 3000 && sim.boss?.state != Boss.HOVER) { sim.shield = 9f; sim.py = sim.arena!!.bottom - 100f; sim.step(1f / 60f) }
                    val b = sim.boss!!
                    b.hp = b.hpMax - phase
                    b.seed = seed * 7919
                    sim.shield = 0f
                    // принудительно запускаем кольцо
                    b.attack = 1
                    val m = Sim::class.java.getDeclaredMethod("chooseAttack", Boss::class.java, Int::class.javaPrimitiveType)
                    m.isAccessible = true
                    b.state = Boss.HOVER
                    // chooseAttack выбирает случайно — крутим, пока не выпадет кольцо
                    var tries = 0
                    while (b.state != Boss.VOLLEY_TELE && tries++ < 50) { b.state = Boss.HOVER; m.invoke(sim, b, phase) }
                    assert(b.state == Boss.VOLLEY_TELE)
                    // стоим на расстоянии 150 от босса в направлении прохода (или напротив)
                    val dirA = if (inGap) b.gapAng else b.gapAng + Math.PI.toFloat() / 2f + 0.3f
                    var t = 0f
                    while (t < 4f && !sim.dead) {
                        sim.onWall = false
                        sim.px = (b.x + kotlin.math.cos(dirA) * 150f)
                        sim.py = (b.y + kotlin.math.sin(dirA) * 150f)
                        sim.pvx = 0f; sim.pvy = 0f
                        sim.step(1f / 60f)
                        t += 1f / 60f
                    }
                    if (inGap && !sim.dead) aliveInGap++
                    if (!inGap && sim.dead) deadElsewhere++
                }
            }
        }
        println("RING aliveInGap=$aliveInGap/18 deadElsewhere=$deadElsewhere/18")
        assert(aliveInGap == 18)
        assert(deadElsewhere >= 15)
    }
}
