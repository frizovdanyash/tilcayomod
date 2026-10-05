package com.tilcayo.fat

import org.junit.Assert.*
import org.junit.Test

class ModRulesTest {
    @Test fun acceptsDecimalCommaAndLimits() {
        assertEquals(12.5f, ModRules.parseMeters(" 12,5 ")!!, 0f)
        assertNotNull(ModRules.parseMeters("0.1"))
        assertNotNull(ModRules.parseMeters("100000"))
    }
    @Test fun rejectsInvalidDistances() {
        for (text in listOf("", "abc", "NaN", "Infinity", "-1", "0", "0.09", "100001", "1e40"))
            assertNull(text, ModRules.parseMeters(text))
    }
    @Test fun movesUpByTenUnitsPerMeter() {
        assertEquals(-1125f, ModRules.teleportedY(-1000f, 12.5f), 0f)
    }
    @Test fun coinGrantDoesNotOverflow() {
        assertEquals(100050L, ModRules.addCoins(50, 100000))
        assertEquals(Long.MAX_VALUE, ModRules.addCoins(Long.MAX_VALUE - 1, 100000))
        assertEquals(100000L, ModRules.addCoins(-20, 100000))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTeleportFails() {
        ModRules.teleportedY(0f, Float.NaN)
    }
}
