package com.tilcayo.fat

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Собирает случайные раскладки препятствий и проверяет их решателем. */
class Composer(private val rnd: Random) {
    class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        fun hits(o: Box, m: Float) = x0 - m < o.x1 && x1 + m > o.x0 && y0 - m < o.y1 && y1 + m > o.y0
    }

    class Part(val items: List<ChunkItem>, val box: Box)

    private fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    private fun item(kind: Char, vararg v: Float) = ChunkItem(kind, v.toList().toFloatArray())

    private val lo = Phys.WALL + 26f
    private val hi = Phys.W - Phys.WALL - 26f

    // y — «центр» элемента, H — высота куска
    private fun spike(yc: Float): Part {
        val left = rnd.nextBoolean()
        val len = f(60f, 120f)
        val x0 = if (left) Phys.WALL else Phys.W - Phys.WALL - Phys.SPIKE_LEN
        return Part(listOf(item('S', if (left) 1f else 0f, yc - len / 2, yc + len / 2)), Box(x0, yc - len / 2, x0 + Phys.SPIKE_LEN, yc + len / 2))
    }

    private fun pillar(yc: Float, hot: Boolean): Part {
        val w = if (hot) f(18f, 26f) else f(20f, 34f)
        val hgt = if (hot) f(50f, 110f) else f(90f, 220f)
        val cx = f(70f, 290f)
        val m = if (hot) 9f else 0f
        return Part(listOf(item('P', cx - w / 2, yc - hgt / 2, cx + w / 2, yc + hgt / 2, if (hot) 1f else 0f)), Box(cx - w / 2 - m, yc - hgt / 2, cx + w / 2 + m, yc + hgt / 2))
    }

    private fun staticSaw(yc: Float): Part {
        val x = f(lo, hi)
        return Part(listOf(item('W', x, yc, x, yc, 0f, 0f)), Box(x - 14f, yc - 14f, x + 14f, yc + 14f))
    }

    private fun railH(yc: Float): Part {
        val len = f(70f, 200f)
        val x0 = f(lo - 10f, hi - len + 10f)
        val spd = f(70f, 170f) * (if (rnd.nextBoolean()) 1f else -1f)
        return Part(listOf(item('W', x0, yc, x0 + len, yc, spd, f(0f, 2 * len))), Box(x0 - 14f, yc - 14f, x0 + len + 14f, yc + 14f))
    }

    private fun railV(yc: Float): Part {
        val len = f(80f, 220f)
        val x = f(lo, hi)
        val spd = f(70f, 160f) * (if (rnd.nextBoolean()) 1f else -1f)
        return Part(listOf(item('W', x, yc - len / 2, x, yc + len / 2, spd, f(0f, 2 * len))), Box(x - 14f, yc - len / 2 - 14f, x + 14f, yc + len / 2 + 14f))
    }

    private fun orbit(yc: Float): Part {
        val rad = f(45f, 75f)
        val cx = f(110f, 250f)
        val spd = f(1.2f, 2.4f) * (if (rnd.nextBoolean()) 1f else -1f)
        val n = 2 + rnd.nextInt(2)
        val e = rad + 14f
        return Part(listOf(item('O', cx, yc, rad, f(0f, 6.28f), spd, n.toFloat())), Box(cx - e, yc - e, cx + e, yc + e))
    }

    private fun laser(yc: Float): Part {
        val period = f(2.6f, 3.8f)
        return Part(listOf(item('L', yc, period, f(0.8f, 1.3f), f(0f, period))), Box(Phys.WALL, yc - 12f, Phys.W - Phys.WALL, yc + 12f))
    }

    private fun bumper(yc: Float): Part {
        val x = f(lo, hi)
        return Part(listOf(item('B', x, yc)), Box(x - 20f, yc - 20f, x + 20f, yc + 20f))
    }

    private val tierPrims = listOf(
        listOf("spike", "pillar", "saw", "bumper"),
        listOf("spike", "pillar", "saw", "railH", "bumper", "hot"),
        listOf("spike", "pillar", "saw", "railH", "railV", "orbit", "bumper", "hot"),
        listOf("spike", "pillar", "saw", "railH", "railV", "orbit", "bumper", "hot", "laser"),
        listOf("spike", "pillar", "saw", "railH", "railV", "orbit", "bumper", "hot", "laser", "spike", "saw"),
    )
    private val tierK = listOf(1..2, 2..3, 3..5, 4..6, 5..8)
    private val tierMargin = floatArrayOf(34f, 34f, 30f, 26f, 22f)
    val tierMinH = floatArrayOf(150f, 500f, 1100f, 1800f, 2800f)

    /** Случайный кусок данного уровня; null, если элементы не удалось расставить. */
    fun compose(tier: Int): ChunkTemplate? {
        val k = tierK[tier].let { rnd.nextInt(it.first, it.last + 1) }
        val h = 180f + 55f * k + f(0f, 40f)
        val parts = ArrayList<Part>()
        val prims = tierPrims[tier]
        for (i in 0 until k) {
            var placed: Part? = null
            for (attempt in 0 until 40) {
                val yc = -f(70f, h - 60f)
                val p = when (prims[rnd.nextInt(prims.size)]) {
                    "spike" -> spike(yc)
                    "pillar" -> pillar(yc, false)
                    "hot" -> pillar(yc, true)
                    "saw" -> staticSaw(yc)
                    "railH" -> railH(yc)
                    "railV" -> railV(yc)
                    "orbit" -> orbit(yc)
                    "laser" -> laser(yc)
                    else -> bumper(yc)
                }
                if (p.box.y0 < -(h - 40f) || p.box.y1 > -40f) continue
                if (parts.none { it.box.hits(p.box, tierMargin[tier]) }) { placed = p; break }
            }
            if (placed == null) return null
            parts.add(placed)
        }
        return ChunkTemplate(tierMinH[tier], h, 1f, parts.flatMap { it.items })
    }
}

object Verifier {
    class Report(val ok: Boolean, val path: List<FloatArray>?, val states: Int)

    private fun dynamic(sim: Sim) = sim.saws.any { it.speed != 0f && it.len > 1f } || sim.orbits.isNotEmpty() || sim.lasers.isNotEmpty()

    /** Сколько можно сползти по стене, пока ждёшь момента (~2 с): дальше догонит лава. */
    const val MAX_DROP = 90f

    /**
     * Проверяет кусок из обеих стен, с любой «стоячей» точки внутри куска, для самого мелкого
     * и самого толстого тилкайо: оттуда всегда должен быть путь наверх без долгого ожидания.
     */
    fun verify(tpl: ChunkTemplate, cap: Int = 20000, wantPath: Boolean = false, extra: Float = 5f): Report {
        val sim = Sim()
        var canonical: List<FloatArray>? = null
        var totalStates = 0
        val entries = ArrayList<Float>()
        entries.add(30f)
        var y = 0f
        while (y > -tpl.height + 60f) { entries.add(y); y -= 40f }
        for (level in intArrayOf(10, 1)) {
            sim.reset(level, 1f, 800f)
            ChunkLib.instantiate(sim, tpl, 0f, false)
            val dyn = dynamic(sim)
            val t0s = if (dyn) floatArrayOf(0f, 1.3f, 2.7f) else floatArrayOf(0f)
            for (side in intArrayOf(-1, 1)) {
                for (yE in entries) {
                    for (t0 in t0s) {
                        sim.reset(level, 1f, 800f)
                        sim.camOn = false; sim.lavaOn = false; sim.collect = false; sim.hrExtra = extra
                        ChunkLib.instantiate(sim, tpl, 0f, false)
                        sim.side = side
                        sim.px = sim.stickX(side)
                        sim.py = yE
                        sim.time = t0
                        // стоять здесь вообще можно? (не внутри шипов/пилы)
                        val start = sim.save()
                        sim.step(1e-3f)
                        val valid = !sim.dead
                        sim.load(start)
                        // с самого низа куска игрок обязан иметь возможность встать на любую стену
                        if (!valid && yE == entries[0]) return Report(false, null, totalStates)
                        if (!valid) continue
                        val solver = Solver(sim)
                        val end = solver.solve(start, -tpl.height + 20f, yE + MAX_DROP, dyn, cap)
                        totalStates += solver.expanded
                        if (end == null) return Report(false, null, totalStates)
                        if (wantPath && canonical == null) canonical = solver.pathPoints(end)
                    }
                }
            }
        }
        return Report(true, canonical, totalStates)
    }

    val extras = floatArrayOf(5f, 9f, 13f, 17f, 21f)

    /** Максимальный запас по хитбоксу, при котором кусок ещё проходим (-1 — не проходим даже с минимальным). */
    fun slack(tpl: ChunkTemplate): Float {
        var best = -1f
        for (e in extras) {
            if (verify(tpl, extra = e).ok) best = e else break
        }
        return best
    }

    /** Кладёт монетки вдоль найденного пути, подальше от неподвижных опасностей. */
    fun withCoins(tpl: ChunkTemplate, path: List<FloatArray>): ChunkTemplate {
        val items = ArrayList(tpl.items)
        var acc = 0f
        var px = path[0][0]
        var py = path[0][1]
        for (p in path) {
            acc += hypot(p[0] - px, p[1] - py)
            px = p[0]; py = p[1]
            if (acc < 34f) continue
            acc = 0f
            if (py > -25f || py < -tpl.height + 30f) continue
            if (px < 50f || px > Phys.W - 50f) continue
            if (nearStatic(tpl, px, py)) continue
            items.add(ChunkItem('C', floatArrayOf(Math.round(px).toFloat(), Math.round(py).toFloat())))
        }
        return ChunkTemplate(tpl.minH, tpl.height, tpl.weight, items)
    }

    private fun nearStatic(tpl: ChunkTemplate, x: Float, y: Float): Boolean {
        for (it in tpl.items) {
            val v = it.v
            when (it.kind) {
                'S' -> { val x0 = if (v[0] > 0.5f) Phys.WALL else Phys.W - Phys.WALL - Phys.SPIKE_LEN
                    if (x > x0 - 24f && x < x0 + Phys.SPIKE_LEN + 24f && y > v[1] - 24f && y < v[2] + 24f) return true }
                'P' -> if (x > v[0] - 28f && x < v[2] + 28f && y > v[1] - 28f && y < v[3] + 28f) return true
                'B' -> if (hypot(x - v[0], y - v[1]) < 36f) return true
                'W' -> { // рельса: расстояние до отрезка
                    val ax = v[0]; val ay = v[1]; val bx = v[2]; val by = v[3]
                    val dx = bx - ax; val dy = by - ay
                    val l2 = max(1f, dx * dx + dy * dy)
                    val t = min(1f, max(0f, ((x - ax) * dx + (y - ay) * dy) / l2))
                    if (hypot(x - (ax + dx * t), y - (ay + dy * t)) < 34f) return true }
                'O' -> if (hypot(x - v[0], y - v[1]) < v[2] + 34f) return true
                'L' -> if (kotlin.math.abs(y - v[0]) < 30f) return true
            }
        }
        return false
    }

    fun format(tpls: List<ChunkTemplate>): String {
        val sb = StringBuilder("# Проверенные решателем куски уровня. Сгенерировано автоматически, руками не править.\n")
        fun n(x: Float): String = if (x == Math.rint(x.toDouble()).toFloat()) x.toInt().toString() else "%.2f".format(java.util.Locale.US, x)
        for (t in tpls) {
            sb.append("K ${n(t.minH)} ${n(t.height)} ${n(t.weight)}\n")
            for (it in t.items) sb.append(it.kind).append(' ').append(it.v.joinToString(" ") { n(it) }).append('\n')
            sb.append("E\n")
        }
        return sb.toString()
    }
}
