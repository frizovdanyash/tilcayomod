package com.tilcayo.fat

import android.content.Context
import kotlin.math.min

/** Еда, которой можно откармливать тилкайо на ферме. */
class FoodItem(val emoji: String, val name: String, val cost: Int, val fat: Int)

object Balance {
    const val MAX_LEVEL = 10
    const val FAT_PER_LEVEL = 100
    const val MAX_FAT = (MAX_LEVEL - 1) * FAT_PER_LEVEL
    const val MAX_HERD = 9
    const val OFFLINE_CAP_SEC = 3 * 3600L

    val foods = listOf(
        FoodItem("🥕", "Морковка", 15, 20),
        FoodItem("🍔", "Бургер", 70, 100),
        FoodItem("🍰", "Тортик", 250, 400),
    )

    val names = listOf(
        "Бублик", "Пончик", "Жорик", "Пельмень", "Батон",
        "Кабачок", "Колобок", "Пирожок", "Борщ",
    )

    fun level(fat: Int) = min(MAX_LEVEL, 1 + fat / FAT_PER_LEVEL)

    /** Сколько стоит родить нового тилкайо при данном размере стада. */
    fun breedCost(herdSize: Int): Long = 100L shl (herdSize - 1)
}

/** Всё, что сохраняется между запусками. */
class SaveData(context: Context) {
    private val sp = context.getSharedPreferences("tilcayo", Context.MODE_PRIVATE)

    var coins: Long = sp.getLong("coins", 50L)
    var best: Int = sp.getInt("best", 0)
    var selected: Int = sp.getInt("selected", 0)
    var sound: Boolean = sp.getBoolean("sound", true)
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
    fun coinMultiplier() = 1f + (totalLevels() - 1) * 0.1f

    fun incomePerMin() = totalLevels() * 2L

    fun pending(): Long {
        val sec = min((System.currentTimeMillis() - lastCollect) / 1000, Balance.OFFLINE_CAP_SEC)
        return sec.coerceAtLeast(0) * incomePerMin() / 60
    }

    fun collect(): Long {
        val got = pending()
        coins += got
        lastCollect = System.currentTimeMillis()
        save()
        return got
    }

    fun save() {
        sp.edit()
            .putLong("coins", coins)
            .putInt("best", best)
            .putInt("selected", selected)
            .putBoolean("sound", sound)
            .putLong("lastCollect", lastCollect)
            .putString("herd", herd.joinToString(","))
            .apply()
    }
}
