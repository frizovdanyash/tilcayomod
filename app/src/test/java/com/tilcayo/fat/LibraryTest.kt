package com.tilcayo.fat

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class LibraryTest {
    /** Пересобирает res/raw/chunks.txt. Запуск: LIB_OUT=путь gradlew testDebugUnitTest --tests '*generateLibrary' */
    @Test
    fun generateLibrary() {
        val out = System.getenv("LIB_OUT")
        assumeTrue(out != null)
        val perTier = (System.getenv("LIB_PER_TIER") ?: "40").toInt()
        val seed = (System.getenv("LIB_SEED") ?: "1").toLong()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val result = ArrayList<ChunkTemplate>()
        for (tier in 0 until 5) {
            val got = ArrayList<ChunkTemplate>()
            val tried = AtomicInteger()
            val passed = AtomicInteger()
            val t0 = System.currentTimeMillis()
            var batch = 0
            while (got.size < perTier && tried.get() < perTier * 600) {
                val futures = ArrayList<Future<ChunkTemplate?>>()
                for (w in 0 until 16) {
                    val s = seed * 1_000_003 + tier * 100_003L + batch * 31L + w
                    futures.add(pool.submit<ChunkTemplate?> {
                        val rnd = Random(s)
                        val c = Composer(rnd)
                        var res: ChunkTemplate? = null
                        for (i in 0 until 6) {
                            val tpl = c.compose(tier) ?: continue
                            tried.incrementAndGet()
                            val sl = Verifier.slack(tpl)
                            val want = when (tier) { 0 -> sl >= 17f; 1 -> sl >= 13f; 2 -> sl >= 9f; 3 -> sl >= 5f; else -> sl in 5f..13f }
                            if (!want) continue
                            val rep = Verifier.verify(tpl, wantPath = true, extra = sl)
                            if (rep.ok && rep.path != null) { passed.incrementAndGet(); res = Verifier.withCoins(tpl, rep.path); break }
                        }
                        res
                    })
                }
                for (f in futures) f.get()?.let { if (got.size < perTier) got.add(it) }
                batch++
            }
            println("TIER ${tier + 1}: kept ${got.size}, tried ${tried.get()}, passed ${passed.get()}, ${(System.currentTimeMillis() - t0) / 1000}s")
            result.addAll(got)
        }
        pool.shutdown()
        File(out!!).writeText(Verifier.format(result))
        println("WROTE ${result.size} chunks to $out")
    }

    /** Библиотека из репозитория должна по-прежнему проходиться (на случай правок физики). */
    @Test
    fun shippedLibraryIsPassable() {
        val f = File("src/main/res/raw/chunks.txt")
        assumeTrue(f.exists())
        val lib = ChunkLib.parse(f.readText())
        val bad = lib.withIndex().filter { !Verifier.verify(it.value).ok }
        assert(bad.isEmpty()) { "Непроходимые куски: ${bad.map { it.index }}" }
    }
}

class SolverSanityTest {
    private fun tpl(h: Float, vararg items: ChunkItem) = ChunkTemplate(0f, h, 1f, items.toList())
    private fun it(k: Char, vararg v: Float) = ChunkItem(k, v)

    @Test
    fun emptyChunkIsPassable() {
        val r = Verifier.verify(tpl(300f))
        println("EMPTY ok=${r.ok} states=${r.states}")
        assert(r.ok)
    }

    @Test
    fun wallOfSpikesBothSidesIsImpassable() {
        val r = Verifier.verify(tpl(300f, it('S', 1f, -300f, 40f), it('S', 0f, -300f, 40f)))
        println("SPIKES-BOTH ok=${r.ok}")
        assert(!r.ok)
    }

    @Test
    fun fullWidthLaserWallIsHandled() {
        // сплошной ряд пил на всю ширину — пройти нельзя
        val saws = (0 until 12).map { i -> it('W', 40f + i * 25f, -150f, 40f + i * 25f, -150f, 0f, 0f) }
        val r = Verifier.verify(tpl(300f, *saws.toTypedArray()))
        println("SAWWALL ok=${r.ok} states=${r.states}")
        assert(!r.ok)
    }

    @Test
    fun printOneSolvedPath() {
        val c = Composer(Random(5))
        for (i in 0 until 50) {
            val t = c.compose(1) ?: continue
            val r = Verifier.verify(t, wantPath = true)
            println("COMPOSED ${t.items.map { it.kind }} ok=${r.ok} states=${r.states} pathLen=${r.path?.size}")
        }
    }
}
