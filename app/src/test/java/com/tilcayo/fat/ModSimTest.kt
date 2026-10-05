package com.tilcayo.fat

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ModSimTest {
    private fun sim() = Sim().apply { reset(1, 1f, 800f); lavaOn = false; camOn = false }

    @Test fun originalHazardStillKills() {
        val s = sim(); s.saws.add(Saw(s.px, s.py, s.px, s.py, 0f, 0f))
        s.step(0.001f)
        assertTrue(s.dead)
    }
    @Test fun godModeProtectsFromSawAndLaserWithoutFreeCoins() {
        val s = sim(); s.godMode = true
        s.saws.add(Saw(s.px, s.py, s.px, s.py, 0f, 0f))
        s.lasers.add(Laser(s.py, 2f, 1f, 0f))
        s.step(0.001f)
        assertFalse(s.dead); assertFalse(s.saws[0].dead); assertEquals(0f, s.runCoins, 0f)
    }
    @Test fun godModeProtectsFromLavaAndRecoversBelowCamera() {
        val s = sim(); s.godMode = true; s.lavaOn = true; s.camOn = true
        s.lavaY = -100f; s.step(0.001f); assertFalse(s.dead)
        s.py = s.camY + s.viewH + 200f; s.step(0.001f)
        assertFalse(s.dead); assertTrue(s.py < s.camY + s.viewH); assertTrue(s.onWall)
    }
    @Test fun noCollisionBypassesPillarsBumpersAndCannons() {
        val s = sim(); s.noCollision = true; s.onWall = false; s.px = 180f
        s.pillars.add(Pillar(170f, -100f, 190f, 100f, false))
        s.bumpers.add(Bumper(s.px, s.py)); s.cannons.add(Cannon(s.px, s.py))
        s.step(0.001f)
        assertFalse(s.onWall); assertNull(s.inCannon); assertEquals(180f, s.px, 0f); assertEquals(0f, s.pvx, 0f)
    }
    @Test fun noCollisionStillUsesSideWallsAndCollectsCoins() {
        val s = sim(); s.noCollision = true; s.onWall = false; s.px = 0f; s.pvx = -20f
        s.coins.add(Coin(s.stickX(-1), s.py))
        s.step(0.001f)
        assertTrue(s.onWall); assertEquals(s.stickX(-1), s.px, 0f); assertEquals(1f, s.runCoins, 0f)
    }
    @Test fun infiniteJumpsDoNotConsumeCount() {
        val s = sim(); s.infiniteJumps = true
        repeat(20) { s.airJump(if (it % 2 == 0) -1 else 1) }
        assertEquals(Phys.MAX_AIR, s.jumpsLeft)
        s.infiniteJumps = false; s.airJump(1); assertEquals(Phys.MAX_AIR - 1, s.jumpsLeft)
    }
    @Test fun godModeProtectsFromBossBodyAndProjectiles() {
        val s = sim(); s.godMode = true
        s.arena = Arena(-600f, 0f, 4).apply { active = true }
        s.boss = Boss(4).apply { state = Boss.STUN; x = s.px; y = s.py; t = 0f }
        s.hairs.add(Hair(s.px, s.py, 0f, 0f, 0, 2f))
        s.step(0.001f); assertFalse(s.dead)
        s.boss!!.state = Boss.CHARGE_WARN
        s.boss!!.x = s.px; s.boss!!.y = s.py
        s.step(0.001f); assertFalse(s.dead)
    }
    @Test fun teleportClearsBossButPreservesEarnedCoinsAndKillsCount() {
        val s = sim(); s.py = -1000f; s.runCoins = 17f; s.bossesBeaten = 2
        s.arena = Arena(-1600f, -1000f, 4).apply { active = true }; s.boss = Boss(4)
        s.hairs.add(Hair(180f, -1000f, 0f, 0f, 0, 2f)); s.coins.add(Coin(180f, -1200f))
        s.inCannon = Cannon(180f, -1000f); s.jumpBuf = 0.2f
        s.teleportForward(12.5f)
        assertEquals(-1125f, s.py, 0f); assertNull(s.boss); assertNull(s.arena); assertNull(s.inCannon)
        assertTrue(s.hairs.isEmpty()); assertTrue(s.coins.isEmpty()); assertTrue(s.events.isEmpty())
        assertEquals(17f, s.runCoins, 0f); assertEquals(2, s.bossesBeaten)
        assertTrue(s.onWall); assertEquals(2f, s.shield, 0f); assertEquals(-1125f, s.minPy, 0f)
    }
    @Test fun longTeleportRegeneratesOnlyBoundedWindow() {
        val s = sim(); s.teleportForward(100_000f)
        val g = Gen(s, emptyList(), Random(42)); g.resetAfterTeleport(); g.fill(s.camY - 800f)
        assertTrue(s.coins.size in 1..500); assertTrue(s.genY < s.camY - 800f)
        assertNull(s.boss); assertNull(s.arena)
    }
    @Test fun noCollisionDoesNotStompBoss() {
        val s = sim(); s.noCollision = true; s.onWall = false; s.px = 180f; s.py = -230f; s.pvy = 60f
        s.arena = Arena(-500f, 0f, 4).apply { active = true }
        s.boss = Boss(4).apply { state = Boss.CHARGE_WARN; x = s.px; y = -200f }
        s.step(0.001f); assertFalse(s.dead); assertEquals(4, s.boss!!.hp)
    }
}
