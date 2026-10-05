package com.tilcayo.fat

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Перебирает тапы и свайпы по настоящей физике [Sim] и ищет путь, которым игрок
 * поднимется на стену выше куска уровня и не погибнет.
 */
class Solver(private val sim: Sim) {
    class Node(val snap: Snap, val parent: Node?, val action: Int, val pri: Float)

    var expanded = 0
    var verbose = false

    private fun key(s: Snap, dynamic: Boolean): Long {
        val kx = (s.px / 6f).roundToInt()
        val ky = (s.py / (if (s.onWall) 2.5f else 6f)).roundToInt()
        val kvy = (s.pvy / 50f).roundToInt()
        val kvx = (s.pvx / 80f).roundToInt()
        val kt = if (dynamic) (s.time / 0.1f + 0.5f).toInt() else 0
        var k = 17L
        k = k * 1009 + kx
        k = k * 1009 + ky
        k = k * 31 + kvy
        k = k * 31 + kvx
        k = k * 7 + s.jumpsLeft
        k = k * 3 + (if (s.onWall) 1 else 0)
        k = k * 5 + s.side + 1
        k = k * 7 + s.clingIdx + 1
        k = k * 997 + kt
        k = k * 3 + (if (s.bumpCd > 0f) 1 else 0)
        return k
    }

    /** Возвращает последний узел пути (или null, если пройти нельзя). */
    fun solve(start: Snap, topY: Float, floorY: Float, dynamic: Boolean, cap: Int): Node? {
        expanded = 0
        val pq = PriorityQueue<Node>(compareBy { it.pri })
        val seen = HashSet<Long>()
        pq.add(Node(start, null, 0, start.py))
        seen.add(key(start, dynamic))
        while (pq.isNotEmpty() && expanded < cap) {
            val n = pq.poll()
            expanded++
            val s = n.snap
            if (s.time - start.time > 16f) continue
            val actions = if (s.onWall) intArrayOf(0, 1) else if (s.jumpsLeft > 0) intArrayOf(0, 2, 3) else intArrayOf(0)
            for (a in actions) {
                sim.load(s)
                sim.events.clear()
                when (a) {
                    1 -> sim.jump()
                    2 -> sim.airJump(-1)
                    3 -> sim.airJump(1)
                }
                var alive = true
                var win = false
                for (i in 0 until STEPS) {
                    sim.step(DT)
                    if (sim.dead || sim.py > floorY) { alive = false; break }
                    if (sim.onWall && sim.py <= topY) { win = true; break }
                }
                sim.events.clear()
                if (verbose && expanded < 8) println("  SOLVER pop=$expanded a=$a alive=$alive py=${sim.py} px=${sim.px} wall=${sim.onWall} t=${sim.time}")
                if (!alive) continue
                val ns = sim.save()
                val child = Node(ns, n, a, ns.py + 30f * (ns.time - start.time))
                if (win) return child
                val fresh = seen.add(key(ns, dynamic))
                if (verbose && expanded < 8) println("  SOLVER   child a=$a fresh=$fresh key=${key(ns, dynamic)} pq=${pq.size}")
                if (fresh) pq.add(child)
            }
        }
        return null
    }

    /** Точки пути (x, y), снятые каждые 0.1 с. */
    fun pathPoints(last: Node): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var n: Node? = last
        while (n != null) { out.add(floatArrayOf(n.snap.px, n.snap.py)); n = n.parent }
        out.reverse()
        return out
    }

    companion object {
        const val DT = 1f / 60f
        const val STEPS = 6
    }
}
