package com.eventmanager.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Firestore document envelope: stores an arbitrary field map as JSON so we do not need
 * a @Serializable class per entity. Flat maps are restored on read for [FirestoreChangeApplier].
 */
@Serializable
data class FirestoreJsonEnvelope(
    val json: String,
)

object FirestoreJsonCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun toEnvelope(data: Map<String, Any?>): FirestoreJsonEnvelope {
        val obj = buildJsonObject {
            data.forEach { (key, value) ->
                put(
                    key,
                    when (value) {
                        null -> JsonNull
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    },
                )
            }
        }
        return FirestoreJsonEnvelope(json.encodeToString(JsonObject.serializer(), obj))
    }

    fun fromEnvelope(envelope: FirestoreJsonEnvelope): Map<String, Any?> {
        val obj = runCatching {
            json.decodeFromString(JsonObject.serializer(), envelope.json)
        }.getOrElse { return emptyMap() }
        return obj.mapValues { (_, el) ->
            when {
                el is JsonNull -> null
                el.jsonPrimitive.isString -> el.jsonPrimitive.contentOrNull
                else -> {
                    val raw = el.jsonPrimitive.content
                    raw.toLongOrNull()
                        ?: raw.toDoubleOrNull()
                        ?: when (raw) {
                            "true" -> true
                            "false" -> false
                            else -> raw
                        }
                }
            }
        }
    }

    fun snapshotToMap(raw: Map<String, Any?>): Map<String, Any?> {
        val nested = raw["json"] as? String
        if (!nested.isNullOrBlank()) {
            val fromJson = fromEnvelope(FirestoreJsonEnvelope(nested))
            if (fromJson.isNotEmpty()) {
                // Flat fields win: the public form writes status/submissionJson at the top
                // level and cannot rewrite the legacy json envelope.
                return fromJson + raw.filterKeys { it != "json" && it != "_seed" }
            }
        }
        return raw.filterKeys { it != "json" && it != "_seed" }
    }

    fun fromJsonObject(obj: JsonObject): Map<String, Any?> {
        val nested = obj["json"]?.jsonPrimitive?.contentOrNull
        val flat = obj.mapValues { (_, el) ->
            when {
                el is JsonNull -> null
                el.jsonPrimitive.isString -> el.jsonPrimitive.contentOrNull
                else -> {
                    val raw = el.jsonPrimitive.content
                    raw.toLongOrNull()
                        ?: raw.toDoubleOrNull()
                        ?: when (raw) {
                            "true" -> true
                            "false" -> false
                            else -> raw
                        }
                }
            }
        }.filterKeys { it != "json" && it != "_seed" }
        if (!nested.isNullOrBlank()) {
            val fromJson = fromEnvelope(FirestoreJsonEnvelope(nested))
            if (fromJson.isNotEmpty()) {
                // Envelope fills gaps; flat fields win so an artist answer (status,
                // submissionJson, lastModified) is not overwritten by the stale envelope.
                return fromJson + flat
            }
        }
        return flat
    }
}
