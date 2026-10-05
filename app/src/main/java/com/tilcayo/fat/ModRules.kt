package com.tilcayo.fat

/** Чистые функции, проверяемые без Android. */
object ModRules {
    const val MAX_COIN_MUL = 1000f
    const val MAX_COINS_GRANT = 1_000_000_000L

    fun validMeters(value: Float) = value.isFinite() && value in 0.1f..100_000f
    fun validCoinMul(value: Float) = value.isFinite() && value in 1f..MAX_COIN_MUL

    /** Множитель монет за забег: от 1 до 1000. */
    fun parseCoinMul(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { validCoinMul(it) }

    /** Количество монет для бонуса: от 1 до миллиарда, пробелы и подчёркивания игнорируются. */
    fun parseCoins(text: String): Long? = text.trim().replace(" ", "").replace("_", "").replace(',', '.')
        .toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 && it <= MAX_COINS_GRANT.toDouble() }?.toLong()

    /** Рекорд высоты в метрах: 0,1–100 000. */
    fun parseRecord(text: String): Int? = parseMeters(text)?.toInt()

    /** Множитель гравитации: 0,05–3 (меньше единицы — прыжки выше). */
    fun validGravity(value: Float) = value.isFinite() && value in 0.05f..3f

    fun gravityFor(moonJump: Boolean) = if (moonJump) 0.35f else 1f

    /** Метры вниз: положительное число означает «ниже». */
    fun teleportedDownY(y: Float, meters: Float): Float {
        require(validMeters(meters))
        return y + meters * 10f
    }

    /** Монеты в текущий забег. */
    fun runCoins(current: Float, amount: Float) = current + amount.coerceIn(0f, 1_000_000f)

    /** Награда с учётом множителя; некорректный множитель не портит начисление. */
    fun runReward(base: Float, mul: Float) = base * (if (mul.isFinite() && mul >= 0f) mul else 1f)
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
