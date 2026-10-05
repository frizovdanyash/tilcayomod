package com.tilcayo.fat

import android.content.Context
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Еда, которой можно откармливать тилкайо на ферме. */
class FoodItem(val emoji: String, val name: String, val cost: Int, val fat: Int)

object Balance {
    const val MAX_LEVEL = 10
    const val MAX_HERD = 9
    const val OFFLINE_CAP_SEC = 3 * 3600L

    /** Сколько жира нужно, чтобы достичь уровня L: растёт быстрее линейного, поэтому поздние уровни дорогие. */
    private val thresholds = IntArray(MAX_LEVEL) { i -> if (i == 0) 0 else (100.0 * i.toDouble().pow(1.6)).toInt() }
    val MAX_FAT = thresholds[MAX_LEVEL - 1]

    fun fatFor(level: Int) = thresholds[(level - 1).coerceIn(0, MAX_LEVEL - 1)]

    fun level(fat: Int): Int {
        var l = 1
        while (l < MAX_LEVEL && fat >= thresholds[l]) l++
        return l
    }

    val foods = listOf(
        FoodItem("🥕", "Морковка", 20, 20),
        FoodItem("🍔", "Бургер", 110, 100),
        FoodItem("🍰", "Тортик", 440, 400),
    )

    val names = listOf(
        "Бублик", "Пончик", "Жорик", "Пельмень", "Батон",
        "Кабачок", "Колобок", "Пирожок", "Борщ",
    )

    /** Цена нового тилкайо при данном размере стада: 400, 800, 1600… */
    fun breedCost(herdSize: Int): Long = 400L shl (herdSize - 1)

    /** Какого уровня должен быть родитель. */
    fun breedLevel(herdSize: Int) = min(MAX_LEVEL, 3 + herdSize)

    /** Сколько «вынашивается» малыш: 3 минуты × размер стада. */
    fun breedMs(herdSize: Int) = 180_000L * herdSize
}

/** Всё, что сохраняется между запусками. */
class SaveData(context: Context) {
    private val sp = context.getSharedPreferences("tilcayo", Context.MODE_PRIVATE)

    var coins: Long = sp.getLong("coins", 50L)
    var best: Int = sp.getInt("best", 0)
    var selected: Int = sp.getInt("selected", 0)
    var sound: Boolean = sp.getBoolean("sound", true)
    /** Когда родится малыш (мс), 0 — никто не вынашивается. */
    var breedEnd: Long = sp.getLong("breedEnd", 0L)
    var lastCollect: Long = sp.getLong("lastCollect", System.currentTimeMillis())
    val herd: MutableList<Int> = (sp.getString("herd", "0") ?: "0")
        .split(",").mapNotNull { it.toIntOrNull() }.toMutableList()
        .ifEmpty { mutableListOf(0) }

    init {
        if (selected !in herd.indices) selected = 0
    }

    fun level(i: Int) = Balance.level(herd[i])
    fun totalLevels() = herd.indices.sumOf { level(it) }

    /** Множитель монет в забеге: чем больше и жирнее стадо, тем жирнее куш. */
    fun coinMultiplier() = 1f + (totalLevels() - 1) * 0.04f

    fun incomePerMin() = totalLevels() * 0.5f

    fun pending(): Long {
        val sec = min((System.currentTimeMillis() - lastCollect) / 1000, Balance.OFFLINE_CAP_SEC)
        return (sec.coerceAtLeast(0) * incomePerMin() / 60f).toLong()
    }

    fun collect(): Long {
        val got = pending()
        coins += got
        lastCollect = System.currentTimeMillis()
        save()
        return got
    }

    fun pregnant() = breedEnd > 0L

    fun breedLeftMs() = max(0L, breedEnd - System.currentTimeMillis())

    /** Рождается ли сейчас малыш. Возвращает true в момент рождения. */
    fun checkBirth(): Boolean {
        if (breedEnd <= 0L || System.currentTimeMillis() < breedEnd) return false
        breedEnd = 0L
        if (herd.size < Balance.MAX_HERD) herd.add(0)
        save()
        return true
    }

    fun save() {
        sp.edit()
            .putLong("coins", coins)
            .putInt("best", best)
            .putInt("selected", selected)
            .putBoolean("sound", sound)
            .putLong("breedEnd", breedEnd)
            .putLong("lastCollect", lastCollect)
            .putString("herd", herd.joinToString(","))
            .apply()
    }
}
