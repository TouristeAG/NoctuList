package com.eventmanager.app.data.models

import com.eventmanager.app.data.remote.ProfilePhotoKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A public "send us your profile photo" link for one guest or volunteer.
 *
 * Lives in Firestore at `orgs/{orgId}/photoRequests/{requestId}`. The public page at
 * `/p/{orgId}/{requestId}` uploads one JPEG to Storage at [uploadStoragePath] and flips the
 * status to [STATUS_UPLOADED]; the device that created the request then stores that photo on the
 * profile and deletes the request. The device keeps the open requests it created in settings so
 * it can still finish them after the link dialog was closed or the app restarted.
 */
@Serializable
data class ProfilePhotoRequest(
    val requestId: String,
    val orgId: String,
    /** [ProfilePhotoKind.storageFolder]: "guests" or "volunteers". */
    val kind: String,
    val entityId: String,
    val displayName: String,
    val createdAt: Long,
    val expiresAtMillis: Long,
) {
    val photoKind: ProfilePhotoKind
        get() = if (kind == ProfilePhotoKind.VOLUNTEER.storageFolder) ProfilePhotoKind.VOLUNTEER else ProfilePhotoKind.GUEST

    val uploadStoragePath: String
        get() = uploadStoragePath(orgId, requestId)

    fun isExpiredAt(now: Long): Boolean = now >= expiresAtMillis

    fun toFirestoreMap(): Map<String, Any?> = mapOf(
        "requestId" to requestId,
        "kind" to kind,
        "entityId" to entityId,
        "displayName" to displayName,
        "status" to STATUS_OPEN,
        "photoPath" to "",
        "uploadedAt" to 0L,
        "createdAt" to createdAt,
        "expiresAtMillis" to expiresAtMillis,
        "lastModified" to createdAt,
    )

    companion object {
        const val COLLECTION = "photoRequests"
        const val STATUS_OPEN = "OPEN"
        const val STATUS_UPLOADED = "UPLOADED"

        /** Long enough to forward a link to someone, short enough not to linger. */
        const val VALIDITY_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        fun uploadStoragePath(orgId: String, requestId: String): String =
            "orgs/${orgId.trim()}/photoRequests/${requestId.trim()}.jpg"

        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(serializer())

        fun encodeList(requests: List<ProfilePhotoRequest>): String =
            json.encodeToString(listSerializer, requests)

        fun decodeList(raw: String): List<ProfilePhotoRequest> {
            if (raw.isBlank()) return emptyList()
            return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
        }
    }
}

/** What the request dialog shows while it waits for the person to upload. */
sealed interface ProfilePhotoRequestState {
    data object Waiting : ProfilePhotoRequestState
    data object Applied : ProfilePhotoRequestState
    data object Expired : ProfilePhotoRequestState
    data class Failed(val message: String) : ProfilePhotoRequestState
}
