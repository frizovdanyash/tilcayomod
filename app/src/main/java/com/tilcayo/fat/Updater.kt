package com.tilcayo.fat

import java.net.HttpURLConnection
import java.net.URL

/** Проверка обновлений: читает version.json из репозитория на GitHub. */
object Updater {
    const val VERSION_URL = "https://raw.githubusercontent.com/narezy/tilcayo/main/version.json"
    const val APK_FALLBACK = "https://github.com/narezy/tilcayo/raw/main/apk/tilcayo.apk"

    class Info(val code: Int, val name: String, val apk: String, val notes: String)

    /** Разбор без org.json, чтобы работало и в обычных юнит-тестах. */
    fun parse(json: String): Info? {
        val code = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        fun str(key: String) = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
        val apk = str("apk").ifBlank { APK_FALLBACK }
        return Info(code, str("versionName"), apk, str("notes"))
    }

    /** Блокирующий запрос — вызывать только из фонового потока. */
    fun fetch(): Info? = try {
        val c = URL(VERSION_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode == 200) parse(c.inputStream.bufferedReader().use { it.readText() }) else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
