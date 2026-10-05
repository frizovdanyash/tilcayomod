package com.tilcayo.fat

import android.content.Context

class ModSettings(context: Context) {
    private val sp = context.getSharedPreferences("tilcayo_mod", Context.MODE_PRIVATE)
    var godMode = sp.getBoolean("god", false)
    var noCollision = sp.getBoolean("noclip", false)
    var infiniteJumps = sp.getBoolean("jumps", false)
    var teleportMeters = sp.getFloat("meters", 100f).let { if (ModRules.validMeters(it)) it else 100f }

    fun save() {
        sp.edit().putBoolean("god", godMode).putBoolean("noclip", noCollision)
            .putBoolean("jumps", infiniteJumps).putFloat("meters", teleportMeters).apply()
    }
}
