package com.tilcayo.fat

import org.junit.Test
import java.io.File

class UpdaterTest {
    @Test
    fun parsesVersionFile() {
        val info = Updater.parse(File("../version.json").readText())!!
        println("UPD ${info.code} ${info.name} ${info.apk} ${info.notes}")
        assert(info.code > 0 && info.name.isNotBlank() && info.apk.startsWith("https://"))
    }

    @Test
    fun versionFileMatchesBuildGradle() {
        val gradle = File("build.gradle.kts").readText()
        val code = Regex("versionCode = (\\d+)").find(gradle)!!.groupValues[1].toInt()
        val name = Regex("versionName = \"([^\"]+)\"").find(gradle)!!.groupValues[1]
        val info = Updater.parse(File("../version.json").readText())!!
        assert(info.code == code) { "version.json: ${info.code}, build.gradle: $code" }
        assert(info.name == name) { "version.json: ${info.name}, build.gradle: $name" }
    }

    @Test
    fun rejectsGarbage() {
        assert(Updater.parse("<html>404</html>") == null)
        val i = Updater.parse("{\"versionCode\": 9, \"versionName\": \"2.0\", \"notes\": \"a \\\"b\\\"\"}")!!
        assert(i.code == 9 && i.name == "2.0" && i.apk == Updater.APK_FALLBACK && i.notes == "a \"b\"")
    }
}
