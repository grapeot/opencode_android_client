package com.yage.opencode_client.ambient.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.floor

internal val ambientJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = true
}

internal fun jsonObject(text: String): JsonObject? =
    try {
        ambientJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

internal fun jsonString(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

internal fun jsonBoolean(element: JsonElement?): Boolean? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.booleanOrNull
}

/** Finite, non-negative JSON number. Booleans and strings are rejected. */
internal fun jsonFinite(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString || primitive.booleanOrNull != null) return null
    val value = primitive.doubleOrNull ?: return null
    if (!value.isFinite() || value < 0.0) return null
    return value
}

/** Whole JSON number in the Int range. Fractional and boolean values are rejected. */
internal fun jsonWhole(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString || primitive.booleanOrNull != null) return null
    val text = primitive.content
    if (text.any { it == '.' || it == 'e' || it == 'E' }) {
        val value = primitive.doubleOrNull ?: return null
        if (!value.isFinite() || value < 0.0 || value > Int.MAX_VALUE.toDouble()) return null
        if (value != floor(value)) return null
        return value.toInt()
    }
    val whole = primitive.longOrNull ?: return null
    if (whole < 0L || whole > Int.MAX_VALUE.toLong()) return null
    return whole.toInt()
}

internal fun jsonMillis(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString || primitive.booleanOrNull != null) return null
    val value = primitive.doubleOrNull ?: return null
    if (!value.isFinite() || value < 0.0 || value > Int.MAX_VALUE.toDouble()) return null
    return floor(value + 0.5).toInt()
}

internal fun asObject(element: JsonElement?): JsonObject? = element as? JsonObject

internal fun asArray(element: JsonElement?): JsonArray? = element as? JsonArray
