package com.eventmanager.app.data.remote

/**
 * Flat Firestore payloads so [firebase/firestore.rules] can read `role`, `balance`, `value`, etc.
 * Also embeds a legacy `json` envelope for older readers.
 */
internal const val FIRESTORE_ENVELOPE_MAX_STRING_CHARS = 8_192

/**
 * Firestore rules `is number` rejects a timestamp that GitLive encoded as a digit string.
 * Double always lands as a number on both the native SDK and the REST transport.
 */
internal fun firestoreMillis(value: Long): Double = value.toDouble()

internal fun sanitizeFirestoreValue(value: Any?): Any? = when (value) {
    null -> null
    is Boolean -> value
    is Int -> value
    is Long -> value
    is Float -> value.toDouble()
    is Double -> value
    is Number -> value.toDouble()
    else -> value.toString()
}

internal fun sanitizeFirestoreMap(data: Map<String, Any?>): Map<String, Any?> =
    data.mapValues { (_, v) -> sanitizeFirestoreValue(v) }

internal fun ruleCompatibleFirestoreMap(data: Map<String, Any?>): Map<String, Any?> {
    val flat = sanitizeFirestoreMap(data).toMutableMap()
    // Oversized strings (guest-form logo data URIs) stay flat-only. Duplicating them in
    // the legacy json envelope can push the document over Firestore's 1 MiB cap, so the
    // public page never sees the form and shows "closed".
    val forEnvelope = data.filterValues { value ->
        value !is String || value.length <= FIRESTORE_ENVELOPE_MAX_STRING_CHARS
    }
    flat["json"] = FirestoreJsonCodec.toEnvelope(forEnvelope).json
    // Nested maps (e.g. allowedEmailDomains) must stay maps for rules — restore from source.
    data.forEach { (key, value) ->
        if (value is Map<*, *>) {
            flat[key] = toFirestoreFieldValue(value)
        }
    }
    return flat
}

/**
 * GitLive Firestore [set] uses [dev.gitlive.firebase.internal.FirebaseEncoder], not [JsonEncoder].
 * Never pass [kotlinx.serialization.json.JsonObject] — it crashes with
 * "Expected Encoder to be JsonEncoder, got FirebaseEncoder".
 */
internal fun toFirestoreFieldMap(data: Map<String, Any?>): Map<String, Any?> =
    data.mapValues { (_, value) -> toFirestoreFieldValue(value) }

internal fun toFirestoreFieldValue(value: Any?): Any? = when (value) {
    null -> null
    is Boolean -> value
    is Int -> value
    is Long -> value
    is Float -> value.toDouble()
    is Double -> value
    is Number -> value.toDouble()
    is String -> value
    is Map<*, *> -> value.entries
        .mapNotNull { (k, v) -> k?.toString()?.takeIf { it.isNotBlank() }?.let { it to toFirestoreFieldValue(v) } }
        .toMap()
    is Iterable<*> -> value.map { toFirestoreFieldValue(it) }
    else -> value.toString()
}
