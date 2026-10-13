package com.quantumobile.launcher.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val LauncherJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

private fun JsonElement?.primitive(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonObject.str(key: String): String? = this[key].primitive()?.content
fun JsonObject.int(key: String): Int? = this[key].primitive()?.intOrNull
fun JsonObject.long(key: String): Long? = this[key].primitive()?.longOrNull
fun JsonObject.bool(key: String): Boolean? = this[key].primitive()?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun parseJsonObject(text: String): JsonObject = LauncherJson.parseToJsonElement(text) as JsonObject
