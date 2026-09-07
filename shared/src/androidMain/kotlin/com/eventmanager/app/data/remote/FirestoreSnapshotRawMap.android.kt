package com.eventmanager.app.data.remote

import com.google.firebase.Timestamp
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.android

internal actual fun firestoreSnapshotRawMap(doc: DocumentSnapshot): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val raw = doc.android.data as? Map<String, Any?> ?: return emptyMap()
    val coerced = coerceAndroidFirestoreValues(raw)
    return FirestoreJsonCodec.snapshotToMap(normalizeFirestoreRawMap(coerced))
}

private fun coerceAndroidFirestoreValues(raw: Map<String, Any?>): Map<String, Any?> =
    raw.mapValues { (_, value) -> coerceAndroidFirestoreValue(value) }

private fun coerceAndroidFirestoreValue(value: Any?): Any? = when (value) {
    null -> null
    is Timestamp -> value.seconds * 1000L + value.nanoseconds / 1_000_000L
    is Map<*, *> -> value.entries.mapNotNull { (k, v) ->
        val key = k?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        key to coerceAndroidFirestoreValue(v)
    }.toMap()
    is List<*> -> value.map { coerceAndroidFirestoreValue(it) }
    else -> value
}
