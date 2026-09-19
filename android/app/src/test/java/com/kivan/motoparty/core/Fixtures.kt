package com.kivan.motoparty.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** Loads the shared vectors in <repo>/fixtures (path injected by app/build.gradle.kts). */
object Fixtures {
    private val dir: File by lazy {
        val path = System.getProperty("motoparty.fixtures")
            ?: error("system property motoparty.fixtures not set; run through ./gradlew test")
        File(path).also { check(it.isDirectory) { "fixtures dir $it missing" } }
    }

    fun load(name: String): JsonElement = Json.parseToJsonElement(File(dir, name).readText())

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
