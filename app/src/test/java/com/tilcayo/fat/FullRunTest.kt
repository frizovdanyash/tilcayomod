package com.tilcayo.fat

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Бот-планировщик проходит настоящий забег (лава, камера, генератор, библиотека кусков). */
class FullRunTest {
    private fun lib(): List<ChunkTemplate> {
        val f = File("src/main/res/raw/chunks.txt")
        assumeTrue(f.exists())
        return ChunkLib.parse(f.readText())
    }

    class Outcome(val height: Float, val seconds: Float, val reason: String, val replans: Int, val fails: Int)

    private fun play(lib: List<ChunkTemplate>, seed: Int, level: Int, targetH: Float): Outcome {
        val real = Sim()
        real.reset(level, 1f, 800f)
        val gen = Gen(real, lib, Random(seed))
        gen.bossChance = 0f
        gen.fill(real.camY - 800f)
        val planner = Sim().apply { lavaOn = false; camOn = false; collect = false; hrExtra = 4f }
        var replans = 0
        var fails = 0
        var guard = 0
        var dumped = false
        val dumpAt = System.getenv("DUMP_AT")?.toFloat() ?: -1f
        while (!real.dead && -real.minPy < targetH && real.time < 900f && guard++ < 20000) {
            if (!dumped && dumpAt > 0f && real.time >= dumpAt) { dumped = true; dump(real) }
            // подготовка планировщика: тот же мир, свой игрок
            planner.reset(level, 1f, 800f)
            planner.camOn = false; planner.lavaOn = false; planner.collect = false; planner.hrExtra = 4f
            planner.clearWorld()
            planner.coins.addAll(real.coins); planner.spikes.addAll(real.spikes); planner.saws.addAll(real.saws)
            planner.orbits.addAll(real.orbits); planner.lasers.addAll(real.lasers); planner.pillars.addAll(real.pillars)
            planner.bumpers.addAll(real.bumpers)
            // пушки бот игнорирует (в планировщике их нет)
            planner.load(real.save().let { it })
            val dynamic = true
            val solver = Solver(planner)
            val end = solver.solve(real.save(), real.py - 110f, real.py + 240f, dynamic, 30000)
            replans++
            if (end == null) {
                fails++
                if (fails == 1 && System.getenv("DEBUG_FAIL") != null) {
                    dump(real)
                    println("  solver expanded=${solver.expanded} target=${real.py - 110f} floor=${real.py + 240f}")
                    val snap0 = real.save()
                    for (a in 0..1) {
                        planner.load(snap0); planner.events.clear()
                        if (a == 1) planner.jump()
                        var k = 0
                        while (k < 6 && !planner.dead) { planner.step(Solver.DT); k++ }
                        println("  action=$a dead=${planner.dead} reason=${planner.deathReason} py=${planner.py} px=${planner.px} onWall=${planner.onWall}")
                    }
                    println("  planner lava=${planner.lavaOn} cam=${planner.camOn} hr=${planner.hr} hrExtra=${planner.hrExtra} spikes=${planner.spikes.size}")
                    val vs = Solver(planner); vs.verbose = true; vs.solve(real.save(), real.py - 110f, real.py + 240f, true, 30000)
                    for (dy in floatArrayOf(60f, 90f, 110f)) {
                        val e2 = Solver(planner).solve(real.save(), real.py - dy, real.py + 240f, true, 30000)
                        println("  retry dy=$dy -> ${e2 != null}")
                    }
                    println("  coins=${real.coins.size} spikes=${real.spikes.size} saws=${real.saws.size} pillars=${real.pillars.size} lava=${real.lavaY} cam=${real.camY} minPy=${real.minPy}")
                }
                // нет пути — просто ждём (скольжение по стене) и надеемся
                repeat(6) { real.step(Solver.DT); gen.fill(real.camY - 800f); real.events.clear(); real.cleanup() }
                if (fails > 400) break
                continue
            }
            val actions = ArrayList<Int>()
            var n: Solver.Node? = end
            while (n?.parent != null) { actions.add(n.action); n = n.parent }
            actions.reverse()
            for (a in actions) {
                when (a) { 1 -> real.jump(); 2 -> real.airJump(-1); 3 -> real.airJump(1) }
                for (i in 0 until Solver.STEPS) {
                    real.step(Solver.DT)
                    if (System.getenv("TRACE") != null && (real.time * 10).toInt() % 3 == 0 && i == 0) println("  TR t=${real.time} py=${real.py} px=${real.px} lava=${real.lavaY} cam=${real.camY} wall=${real.onWall} cannon=${real.inCannon != null} shield=${real.shield} boost=${real.boostT} a=$a")
                    gen.fill(real.camY - 800f)
                    real.events.clear()
                    real.cleanup()
                    if (real.dead) break
                }
                if (real.dead) break
            }
        }
        return Outcome(-real.minPy, real.time, if (real.dead) real.deathReason else "жив", replans, fails)
    }

    private fun dump(r: Sim) {
        println("FAIL state px=${r.px} py=${r.py} side=${r.side} onWall=${r.onWall} t=${r.time} jumps=${r.jumpsLeft} level=${r.level}")
        val y0 = r.py - 330f; val y1 = r.py + 120f
        for (s in r.spikes) if (s.y1 > y0 && s.y0 < y1) println("  spike left=${s.left} y=${s.y0}..${s.y1}")
        for (p in r.pillars) if (p.y1 > y0 && p.y0 < y1) println("  pillar x=${p.x0}..${p.x1} y=${p.y0}..${p.y1} hot=${p.hot}")
        for (w in r.saws) if (maxOf(w.y0, w.y1) > y0 && minOf(w.y0, w.y1) < y1) println("  saw (${w.x0},${w.y0})-(${w.x1},${w.y1}) speed=${w.speed} phase=${w.phase}")
        for (o in r.orbits) if (o.cy > y0 && o.cy < y1) println("  orbit c=(${o.cx},${o.cy}) R=${o.rad} spd=${o.spd} n=${o.n}")
        for (l in r.lasers) if (l.y > y0 && l.y < y1) println("  laser y=${l.y}")
        for (b in r.bumpers) if (b.y > y0 && b.y < y1) println("  bumper (${b.x},${b.y})")
        for (c in r.cannons) if (c.y > y0 && c.y < y1) println("  cannon (${c.x},${c.y})")
    }

    @Test
    fun botSurvivesLongRuns() {
        val l = lib()
        var worst = Float.MAX_VALUE
        val summary = ArrayList<String>()
        val only = System.getenv("ONLY")
        for (level in intArrayOf(1, 10)) {
            for (seed in 1..6) {
                if (only != null && only != "$level:$seed") continue
                val o = play(l, seed * 17 + level, level, 12000f)
                summary.add("lvl=$level seed=$seed h=${o.height.toInt()} t=${o.seconds.toInt()}s ${o.reason} replans=${o.replans} noPlan=${o.fails}")
                worst = minOf(worst, o.height)
            }
        }
        summary.forEach { println("RUN $it") }
        println("RUN worst=${worst.toInt()}")
    }
}
