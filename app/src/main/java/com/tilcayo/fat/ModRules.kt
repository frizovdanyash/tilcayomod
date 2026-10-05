package com.tilcayo.fat

/** Чистые функции, проверяемые без Android. */
object ModRules {
    fun validMeters(value: Float) = value.isFinite() && value in 0.1f..100_000f
    fun parseMeters(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { validMeters(it) }
    fun teleportedY(y: Float, meters: Float): Float {
        require(validMeters(meters))
        return y - meters * 10f
    }
    fun addCoins(current: Long, amount: Long): Long {
        require(amount >= 0)
        return current.coerceAtLeast(0).let { if (it > Long.MAX_VALUE - amount) Long.MAX_VALUE else it + amount }
    }
}
