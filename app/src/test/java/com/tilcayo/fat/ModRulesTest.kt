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
    @Test fun coinMultiplierLimits() {
        assertEquals(25f, ModRules.parseCoinMul("25")!!, 0f)
        assertEquals(1.5f, ModRules.parseCoinMul(" 1,5 ")!!, 0f)
        assertNotNull(ModRules.parseCoinMul("1000"))
        for (bad in listOf("0.5", "0", "1001", "abc", "", "-3")) assertNull(bad, ModRules.parseCoinMul(bad))
    }
    @Test fun coinAmountParsesSpacesAndCaps() {
        assertEquals(1_000_000L, ModRules.parseCoins("1 000 000")!!)
        assertEquals(1_000_000_000L, ModRules.parseCoins("1_000_000_000")!!)
        for (bad in listOf("0", "", "abc", "2000000000", "-5")) assertNull(bad, ModRules.parseCoins(bad))
    }
    @Test fun recordLimits() {
        assertEquals(1234, ModRules.parseRecord("1234")!!)
        assertNull(ModRules.parseRecord("0"))
        assertNull(ModRules.parseRecord("100001"))
    }
    @Test fun rewardMultiplierIsSafe() {
        assertEquals(30f, ModRules.runReward(10f, 3f), 0f)
        assertEquals(10f, ModRules.runReward(10f, Float.NaN), 0f)
        assertEquals(10f, ModRules.runReward(10f, -2f), 0f)
    }
    @Test fun gravityRules() {
        assertEquals(0.35f, ModRules.gravityFor(true), 0f)
        assertEquals(1f, ModRules.gravityFor(false), 0f)
        assertTrue(ModRules.validGravity(0.05f))
        assertTrue(ModRules.validGravity(3f))
        assertFalse(ModRules.validGravity(0f))
        assertFalse(ModRules.validGravity(3.5f))
        assertFalse(ModRules.validGravity(Float.NaN))
    }
    @Test fun downwardTeleportAddsDistance() {
        assertEquals(500f, ModRules.teleportedDownY(0f, 50f), 0f)
        assertEquals(-450f, ModRules.teleportedDownY(-1000f, 55f), 0f)
    }
    @Test fun runCoinsAreClamped() {
        assertEquals(255f, ModRules.runCoins(5f, 250f), 0f)
        assertEquals(5f, ModRules.runCoins(5f, -50f), 0f)
        assertEquals(1_000_005f, ModRules.runCoins(5f, 9_000_000f), 0f)
    }
    @Test fun modifierMaskTogglesWithExclusions() {
        var mask = 0
        for (md in Mods.all) mask = Mods.toggle(mask, md.id)
        assertEquals("несовместимая пара выключается", Mods.all.size - 1, Mods.count(mask))
        assertFalse(Mods.has(mask, Mods.SPARSE) && Mods.has(mask, Mods.DENSE))
        assertTrue(Mods.multiplier(mask) > 1f)
        val before = Mods.count(mask)
        val enabled = Mods.all.first { Mods.has(mask, it.id) }
        mask = Mods.toggle(mask, enabled.id)
        assertEquals(before - 1, Mods.count(mask))
        mask = Mods.toggle(mask, Mods.SPARSE)
        assertTrue(Mods.has(mask, Mods.SPARSE))
        assertFalse(Mods.has(mask, Mods.DENSE))
    }
}
