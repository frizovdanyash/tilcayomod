package com.tilcayo.fat

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Один элемент куска уровня: буква-тип и числа. Координаты по y — от основания куска (вверх = минус). */
class ChunkItem(val kind: Char, val v: FloatArray)

/** Кусок уровня, который решатель уже проверил на проходимость. */
class ChunkTemplate(val minH: Float, val height: Float, val weight: Float, val items: List<ChunkItem>)

object ChunkLib {
    /**
     * Формат текста:
     *   K minH height weight      — начало куска
     *   S left y0 y1              — шипы на стене
     *   W x0 y0 x1 y1 speed phase — пила на рельсе
     *   O cx cy rad a0 spd n      — вращающиеся пилы
     *   L y period on phase       — лазер
     *   P x0 y0 x1 y1 hot         — столб
     *   B x y                     — бампер
     *   C x y                     — монетка
     *   E                         — конец куска
     */
    fun parse(text: String): List<ChunkTemplate> {
        val out = ArrayList<ChunkTemplate>()
        var head: FloatArray? = null
        var items = ArrayList<ChunkItem>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val t = line.split(' ')
            when (t[0]) {
                "K" -> { head = floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat()); items = ArrayList() }
                "E" -> { head?.let { out.add(ChunkTemplate(it[0], it[1], it[2], items)) }; head = null }
                else -> items.add(ChunkItem(t[0][0], FloatArray(t.size - 1) { t[it + 1].toFloat() }))
            }
        }
        return out
    }

    fun instantiate(sim: Sim, tpl: ChunkTemplate, baseY: Float, mirror: Boolean) {
        val w = Phys.W
        fun mx(x: Float) = if (mirror) w - x else x
        for (it in tpl.items) {
            val v = it.v
            when (it.kind) {
                'S' -> sim.spikes.add(Spike((v[0] > 0.5f) != mirror, baseY + v[1], baseY + v[2]))
                'W' -> sim.saws.add(Saw(mx(v[0]), baseY + v[1], mx(v[2]), baseY + v[3], v[4], v[5]))
                'O' -> {
                    val a0 = if (mirror) PI.toFloat() - v[3] else v[3]
                    val spd = if (mirror) -v[4] else v[4]
                    sim.orbits.add(Orbit(mx(v[0]), baseY + v[1], v[2], a0, spd, v[5].toInt()))
                }
                'L' -> sim.lasers.add(Laser(baseY + v[0], v[1], v[2], v[3]))
                'P' -> {
                    val xa = mx(v[0]); val xb = mx(v[2])
                    sim.pillars.add(Pillar(min(xa, xb), baseY + v[1], max(xa, xb), baseY + v[3], v[4] > 0.5f))
                }
                'B' -> sim.bumpers.add(Bumper(mx(v[0]), baseY + v[1]))
                'C' -> sim.coins.add(Coin(mx(v[0]), baseY + v[1]))
            }
        }
    }
}

/** Выбирает, что положить дальше: проверенные куски, передышки с монетками, вкусняшки и пушки. */
class Gen(private val sim: Sim, private val lib: List<ChunkTemplate>, private val rnd: Random) {
    private var lastCannonH = 0f
    private var lastBossH = 0f

    /** Босс появляется редко: не раньше bossMinH, не чаще раза в bossCooldown и с шансом bossChance на каждом куске. */
    var bossMinH = 3000f
    var bossCooldown = 5000f
    var bossChance = 0.08f
    private var calmUntil = Float.POSITIVE_INFINITY
    private var lastWasBreather = false
    private val recent = ArrayDeque<Int>()

    fun reset() {
        lastCannonH = 0f
        lastBossH = 0f
        calmUntil = Float.POSITIVE_INFINITY
        lastWasBreather = false
        recent.clear()
    }

    fun fill(upTo: Float) {
        while (sim.genY > upTo) next()
    }

    private fun next() {
        val y = sim.genY
        val h = -y
        val calm = y > calmUntil
        val ch: Float = when {
            h < 100f -> coinChunk(y)
            !calm && h > 350f && h - lastCannonH > 1700f -> cannonChunk(y, h)
            !calm && sim.arena == null && h > bossMinH && h - lastBossH > bossCooldown && rnd.nextFloat() < bossChance -> bossChunk(y, h)
            calm -> if (rnd.nextFloat() < 0.2f) snackChunk(y) else coinChunk(y)
            h > 150f && rnd.nextFloat() < 0.07f -> snackChunk(y)
            !lastWasBreather && rnd.nextFloat() < 0.2f -> { lastWasBreather = true; coinChunk(y) }
            else -> libChunk(y, h)
        }
        sim.genY -= ch
    }

    private fun libChunk(y: Float, h: Float): Float {
        lastWasBreather = false
        val cands = ArrayList<Int>()
        val ws = ArrayList<Float>()
        for ((i, t) in lib.withIndex()) {
            if (t.minH > h || i in recent) continue
            // чем «старее» уровень сложности, тем реже он выпадает — игра постепенно усложняется
            val age = (h - t.minH) / 3500f
            cands.add(i)
            ws.add(t.weight * kotlin.math.exp(-age))
        }
        if (cands.isEmpty()) return coinChunk(y)
        var tot = 0f
        for (w in ws) tot += w
        var x = rnd.nextFloat() * tot
        var pick = cands.last()
        for (i in cands.indices) {
            x -= ws[i]
            if (x <= 0f) { pick = cands[i]; break }
        }
        recent.addLast(pick)
        while (recent.size > min(8, lib.size / 3)) recent.removeFirst()
        val t = lib[pick]
        ChunkLib.instantiate(sim, t, y, rnd.nextBoolean())
        return t.height
    }

    // ---------- «ручные» куски ----------

    private fun coin(x: Float, y: Float) { sim.coins.add(Coin(x, y)) }

    private fun coinLine(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i / (n - 1f)
            coin(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }

    private fun coinChunk(y: Float): Float {
        val fl = Phys.WALL + 26f
        val fr = Phys.W - Phys.WALL - 26f
        val mid = Phys.W / 2
        when (rnd.nextInt(7)) {
            0 -> for (i in 0 until 8) { // волна
                val t = i / 7f
                coin(fl + (fr - fl) * t, y - 70f + sin(t * Phys.TAU) * 40f)
            }
            1 -> { // колонна
                val x = fl + 30f + rnd.nextFloat() * (fr - fl - 60f)
                coinLine(x, y - 20f, x, y - 150f, 6)
            }
            2 -> { // кольцо
                val cx = fl + 60f + rnd.nextFloat() * (fr - fl - 120f)
                for (i in 0 until 10) {
                    val a = i * Phys.TAU / 10f
                    coin(cx + cos(a) * 40f, y - 85f + sin(a) * 40f)
                }
            }
            3 -> if (rnd.nextBoolean()) coinLine(fl, y - 10f, fr, y - 140f, 7) else coinLine(fr, y - 10f, fl, y - 140f, 7)
            4 -> { // две колонны у стен
                coinLine(fl - 8f, y - 20f, fl - 8f, y - 140f, 5)
                coinLine(fr + 8f, y - 20f, fr + 8f, y - 140f, 5)
            }
            5 -> { // зигзаг
                for (i in 0 until 9) coin(if (i % 2 == 0) fl + 20f else fr - 20f, y - 10f - i * 16f)
            }
            else -> { // сердечко
                for (i in 0 until 16) {
                    val t = i * Phys.TAU / 16f
                    val s = sin(t)
                    val hx = 16f * s * s * s
                    val hy = 13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)
                    coin(mid + hx * 2.6f, y - 90f - hy * 2.6f)
                }
            }
        }
        return 170f
    }

    private fun snackChunk(y: Float): Float {
        val mid = Phys.W / 2
        sim.snacks.add(
            when (rnd.nextInt(3)) {
                0 -> Snack(mid, y - 60f, "🥕", 20)
                1 -> Snack(mid, y - 60f, "🍔", 50)
                else -> Snack(mid, y - 60f, "🍰", 80)
            },
        )
        return 140f
    }

    /** Пустая арена: босс появится, когда игрок в неё войдёт. */
    private fun bossChunk(y: Float, h: Float): Float {
        val ah = min(620f, sim.viewH - 150f)
        sim.arena = Arena(y - 40f - ah, y - 40f, min(7, 4 + sim.bossesBeaten))
        lastBossH = h
        lastWasBreather = false
        return ah + 80f
    }

    private fun cannonChunk(y: Float, h: Float): Float {
        val mid = Phys.W / 2
        val cx = mid + (rnd.nextFloat() - 0.5f) * 100f
        sim.cannons.add(Cannon(cx, y - 90f))
        for (i in 0 until 6) {
            val a = PI.toFloat() * (0.15f + i * 0.14f)
            coin(cx + cos(a) * 55f, y - 90f + sin(a) * 40f)
        }
        for (i in 0 until 18) coin(mid + sin(i * 0.5f) * 60f, y - 270f - i * 34f)
        lastCannonH = h
        calmUntil = y - 1000f
        return 260f
    }
}
