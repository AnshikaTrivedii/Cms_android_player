package com.orion.player.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Gson configured for backend JSON that may send numbers as doubles, numeric strings,
 * or mixed types — a common source of ClassCastException during /player/sync parsing.
 */
object GsonConfig {

    fun create(): Gson = GsonBuilder()
        .registerTypeAdapter(String::class.java, FlexibleStringAdapter)
        .registerTypeAdapter(Int::class.javaPrimitiveType, FlexibleIntAdapter)
        .registerTypeAdapter(Int::class.javaObjectType, FlexibleIntAdapter)
        .registerTypeAdapter(Boolean::class.javaPrimitiveType, FlexibleBooleanAdapter)
        .registerTypeAdapter(Boolean::class.javaObjectType, FlexibleBooleanAdapter)
        .registerTypeAdapter(Double::class.javaPrimitiveType, FlexibleDoubleAdapter)
        .registerTypeAdapter(Double::class.javaObjectType, FlexibleDoubleAdapter)
        .create()
}

/** Accepts JSON strings, numbers, and booleans as Kotlin/Java strings. */
private object FlexibleStringAdapter : TypeAdapter<String>() {
    override fun write(out: JsonWriter, value: String?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> formatNumber(reader.nextDouble())
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                null
            }
        }
    }
}

/** Accepts JSON numbers (including 15.0 doubles), numeric strings, and booleans. */
private object FlexibleIntAdapter : TypeAdapter<Int>() {
    override fun write(out: JsonWriter, value: Int?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): Int? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.NUMBER -> reader.nextDouble().toInt()
            JsonToken.STRING -> parseIntString(reader.nextString())
            JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1 else 0
            else -> {
                reader.skipValue()
                null
            }
        }
    }
}

/** Accepts JSON booleans, 0/1 numbers, and "true"/"false" strings. */
private object FlexibleBooleanAdapter : TypeAdapter<Boolean>() {
    override fun write(out: JsonWriter, value: Boolean?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): Boolean? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> reader.nextDouble() != 0.0
            JsonToken.STRING -> parseBooleanString(reader.nextString())
            else -> {
                reader.skipValue()
                null
            }
        }
    }
}

private object FlexibleDoubleAdapter : TypeAdapter<Double>() {
    override fun write(out: JsonWriter, value: Double?) {
        if (value == null) out.nullValue() else out.value(value)
    }

    override fun read(reader: JsonReader): Double? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.STRING -> reader.nextString().toDoubleOrNull()
            else -> {
                reader.skipValue()
                null
            }
        }
    }
}

private fun formatNumber(value: Double): String {
    val longValue = value.toLong()
    return if (value == longValue.toDouble()) longValue.toString() else value.toString()
}

private fun parseIntString(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toIntOrNull()?.let { return it }
    trimmed.toDoubleOrNull()?.let { return it.toInt() }
    throw JsonSyntaxException("Cannot parse integer from '$raw'")
}

private fun parseBooleanString(raw: String): Boolean? {
    return when (raw.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
