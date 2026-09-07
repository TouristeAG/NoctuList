package com.eventmanager.app.data.remote

import dev.gitlive.firebase.firestore.DocumentSnapshot

/**
 * Reads the native Firestore document field map. GitLive's [DocumentSnapshot.data] with
 * [kotlinx.serialization.json.JsonObject] / untyped [Map] fails under FirebaseDecoder
 * ("Expected Decoder to be JsonDecoder"), so we fall back to the platform SDK map and then
 * [FirestoreJsonCodec.snapshotToMap] so flat public updates beat a stale `json` envelope.
 */
internal expect fun firestoreSnapshotRawMap(doc: DocumentSnapshot): Map<String, Any?>

/**
 * Coerce platform SDK values (numbers, nested maps) into the plain types our applier expects.
 * Platform actuals should convert Timestamps to millis before calling this.
 */
internal fun normalizeFirestoreRawMap(raw: Map<*, *>?): Map<String, Any?> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.entries.mapNotNull { (key, value) ->
        val k = key?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        k to normalizeFirestoreRawValue(value)
    }.toMap()
}

internal fun normalizeFirestoreRawValue(value: Any?): Any? = when (value) {
    null -> null
    is Boolean -> value
    is String -> value
    is Int -> value
    is Long -> value
    is Float -> value.toDouble()
    is Double -> value
    is Number -> value.toDouble()
    is Map<*, *> -> normalizeFirestoreRawMap(value)
    is Iterable<*> -> value.map { normalizeFirestoreRawValue(it) }
    else -> value.toString()
}
