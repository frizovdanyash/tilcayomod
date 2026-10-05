package com.tilcayo.fat

import android.content.Context

/** Настройки MOD-меню: сохраняются между запусками. */
class ModSettings(context: Context) {
    private val sp = context.getSharedPreferences("tilcayo_mod", Context.MODE_PRIVATE)

    var godMode = sp.getBoolean("god", false)
    var noCollision = sp.getBoolean("noclip", false)
    var infiniteJumps = sp.getBoolean("jumps", false)
    var flyMode = sp.getBoolean("fly", false)
    var infiniteShield = sp.getBoolean("shield", false)
    var freezeHazards = sp.getBoolean("freeze", false)
    var magnet = sp.getBoolean("magnet", false)
    var instaBoss = sp.getBoolean("instaboss", false)
    var autoPlay = sp.getBoolean("auto", false)
    var noSlip = sp.getBoolean("noslip", false)
    var lavaPaused = sp.getBoolean("lavapause", false)
    var ammoInfinite = sp.getBoolean("ammo", false)
    var moonJump = sp.getBoolean("moon", false)
    var showStats = sp.getBoolean("stats", false)
    var speed = sp.getInt("speed", 1).coerceIn(1, 3)
    var coinMul = sp.getFloat("coinmul", 1f).let { if (ModRules.validCoinMul(it)) it else 1f }
    var teleportMeters = sp.getFloat("meters", 100f).let { if (ModRules.validMeters(it)) it else 100f }

    fun save() {
        sp.edit()
            .putBoolean("god", godMode).putBoolean("noclip", noCollision)
            .putBoolean("jumps", infiniteJumps).putBoolean("fly", flyMode)
            .putBoolean("shield", infiniteShield).putBoolean("freeze", freezeHazards)
            .putBoolean("magnet", magnet).putBoolean("instaboss", instaBoss)
            .putBoolean("auto", autoPlay).putBoolean("noslip", noSlip)
            .putBoolean("lavapause", lavaPaused).putBoolean("ammo", ammoInfinite)
            .putBoolean("moon", moonJump)
            .putBoolean("stats", showStats).putInt("speed", speed)
            .putFloat("coinmul", coinMul).putFloat("meters", teleportMeters)
            .apply()
    }
}
