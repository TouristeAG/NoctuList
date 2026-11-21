package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Gender
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Single-use registration link `orgs/{orgId}/volunteerSignupForms/{formId}`, opened at
 * `/s/{orgId}/{formId}`. Firestore rules let the public side flip it once to SUBMITTED.
 */
@Serializable
data class VolunteerSignupForm(
    val formId: String,
    val orgId: String,
    val createdAt: Long,
    val expiresAtMillis: Long,
    val photosEnabled: Boolean,
    /** Who the link registers: a volunteer, or a permanent guest (no birth date nor gender). */
    val holderType: PortalHolderKind = PortalHolderKind.VOLUNTEER,
    /** GUEST links: venue of the guest created from the answer, chosen by the admin. */
    val venueName: String = "",
) {
    fun isExpiredAt(now: Long): Boolean = now >= expiresAtMillis

    fun toFirestoreMap(associationName: String): Map<String, Any?> = mapOf(
        "formId" to formId,
        "status" to STATUS_OPEN,
        "photosEnabled" to photosEnabled,
        "associationName" to associationName.trim().take(80),
        "holderType" to holderType.name,
        "venueName" to venueName,
        "createdAt" to createdAt,
        "expiresAtMillis" to expiresAtMillis,
        "lastModified" to createdAt,
    )

    val photoStoragePath: String get() = photoStoragePath(orgId, formId)

    companion object {
        const val COLLECTION = "volunteerSignupForms"
        const val STATUS_OPEN = "OPEN"
        const val STATUS_SUBMITTED = "SUBMITTED"
        const val VALIDITY_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        fun photoStoragePath(orgId: String, formId: String): String =
            "orgs/${orgId.trim()}/portalSignupPhotos/${formId.trim()}.jpg"

        private val json = Json { ignoreUnknownKeys = true }

        fun encodeList(forms: List<VolunteerSignupForm>): String =
            json.encodeToString(ListSerializer(serializer()), forms)

        fun decodeList(raw: String): List<VolunteerSignupForm> =
            if (raw.isBlank()) emptyList()
            else runCatching { json.decodeFromString(ListSerializer(serializer()), raw) }.getOrDefault(emptyList())
    }
}

/** A registration answered on the public page, validated again on the device. */
data class SignupSubmission(
    val formId: String,
    val uid: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val phone: String,
    /** Empty for a guest. */
    val dateOfBirth: String,
    /** Not asked of a guest: [Gender.PREFER_NOT_TO_DISCLOSE] there, never used. */
    val gender: Gender,
    val photoPath: String,
    val lang: String,
    val holderType: PortalHolderKind = PortalHolderKind.VOLUNTEER,
    /** GUEST only: the venue stored on the link by the app (the public side cannot change it). */
    val venueName: String = "",
)

enum class PortalRequestType { PROFILE, QR_EMAIL, CLAIM, GOOGLE_LINK, GOOGLE_UNLINK, GUEST_PROFILE, GUEST_QR_EMAIL }

enum class PortalGoogleClaimType { ADMIN_MATCH, LINK_CODE }

/** `orgs/{orgId}/portalGoogleClaims/{googleUid}` written by a signed-in association Google account. */
data class PortalGoogleClaim(
    val googleUid: String,
    val type: PortalGoogleClaimType,
    val status: String,
    val email: String,
    /** LINK_CODE only: the plaintext one-time link code. */
    val code: String = "",
) {
    companion object {
        const val COLLECTION = "portalGoogleClaims"
    }
}

/** `orgs/{orgId}/portalGoogleAccounts/{googleUid}` maps a Google account to a volunteer. */
object PortalGoogleAccount {
    const val COLLECTION = "portalGoogleAccounts"
}

/** `orgs/{orgId}/portalServer/status`, written by the Cloud Functions after each run. */
data class PortalServerStatus(val version: String, val lastRunAt: Long) {
    companion object {
        const val COLLECTION = "portalServer"
        const val DOC = "status"

        fun fromMap(data: Map<String, Any?>): PortalServerStatus? {
            val at = firestoreMillisOf(data["lastRunAt"]) ?: return null
            return PortalServerStatus(data["version"]?.toString().orEmpty(), at)
        }
    }
}

/** `orgs/{orgId}/portalRequests/{uid}_{type}` written by a signed-in portal account. */
data class PortalRequest(
    val requestId: String,
    val uid: String,
    val type: PortalRequestType,
    val status: String,
    val email: String,
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    /** PROFILE and GUEST_PROFILE only: a new photo waits in `portalPhotoUploads/{uid}.jpg`. */
    val photo: Boolean = false,
    /** GOOGLE_LINK only: SHA-256 hex of the one-time link code. */
    val codeHash: String = "",
) {
    companion object {
        const val COLLECTION = "portalRequests"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_DONE = "DONE"
        const val STATUS_REJECTED = "REJECTED"

        /** A device that claimed a request and vanished frees it after this long. */
        const val PROCESSING_STALE_MILLIS: Long = 5L * 60 * 1000
    }
}

/**
 * Parses and re-validates what the public pages wrote. Firestore rules already enforce the same
 * shapes; the device checks again so a rules mistake can never put garbage on a profile.
 */
object PortalInputValidator {
    private val NAME = Regex("^[^<>{}\\\\]{1,60}$")
    private val PHONE = Regex("^[0-9+() .-]{4,32}$")
    private val DATE = Regex("^(19|20)[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
    private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}$")
    private val UID = Regex("^[A-Za-z0-9]{6,128}$")
    private val CODE_HASH = Regex("^[0-9a-f]{64}$")
    private val LINK_CODE = Regex("^[A-Za-z0-9_-]{32,64}$")
    private val LANGS = setOf("fr", "en", "es", "de", "it")

    fun cleanName(raw: Any?): String? {
        val value = (raw as? String)?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return value.takeIf { it.isNotEmpty() && NAME.matches(it) }
    }

    fun cleanPhone(raw: Any?): String? {
        val value = (raw as? String)?.trim() ?: return null
        return value.takeIf { PHONE.matches(it) }
    }

    fun cleanEmail(raw: Any?): String? {
        val value = (raw as? String)?.trim()?.lowercase() ?: return null
        return value.takeIf { it.length <= 254 && EMAIL.matches(it) }
    }

    fun parseSignup(formId: String, orgId: String, data: Map<String, Any?>): SignupSubmission? {
        if (data["status"]?.toString() != VolunteerSignupForm.STATUS_SUBMITTED) return null
        val uid = (data["submittedUid"] as? String)?.takeIf { UID.matches(it) } ?: return null
        val email = cleanEmail(data["email"]) ?: return null
        val firstName = cleanName(data["firstName"]) ?: return null
        val lastName = cleanName(data["lastName"]) ?: return null
        val phone = cleanPhone(data["phone"]) ?: return null
        // Same test as the rules: only "GUEST" makes a guest link, anything else is a volunteer one.
        val holderType = if (data["holderType"] == PortalHolderKind.GUEST.name) PortalHolderKind.GUEST else PortalHolderKind.VOLUNTEER
        val guest = holderType == PortalHolderKind.GUEST
        val dateOfBirth = if (guest) "" else (data["dateOfBirth"] as? String)?.takeIf { DATE.matches(it) } ?: return null
        val gender = if (guest) {
            Gender.PREFER_NOT_TO_DISCLOSE
        } else {
            runCatching { Gender.valueOf(data["gender"].toString()) }.getOrNull() ?: return null
        }
        if (data["consent"] != true) return null
        val photoPath = (data["photoPath"] as? String).orEmpty()
        // Only ever trust the path this form owns, whatever the document says.
        val ownPhoto = VolunteerSignupForm.photoStoragePath(orgId, formId)
        val lang = (data["lang"] as? String)?.takeIf { it in LANGS } ?: "fr"
        return SignupSubmission(
            formId = formId,
            uid = uid,
            email = email,
            firstName = firstName,
            lastName = lastName,
            phone = phone,
            dateOfBirth = dateOfBirth,
            gender = gender,
            photoPath = if (photoPath == ownPhoto) ownPhoto else "",
            lang = lang,
            holderType = holderType,
            venueName = if (guest) (data["venueName"] as? String).orEmpty().trim() else "",
        )
    }

    fun parseRequest(requestId: String, data: Map<String, Any?>): PortalRequest? {
        val uid = (data["uid"] as? String)?.takeIf { UID.matches(it) } ?: return null
        val type = runCatching { PortalRequestType.valueOf(data["type"].toString()) }.getOrNull() ?: return null
        if (requestId != "${uid}_${type.name}") return null
        val email = cleanEmail(data["email"]) ?: return null
        val status = data["status"]?.toString().orEmpty()
        return when (type) {
            PortalRequestType.PROFILE, PortalRequestType.GUEST_PROFILE -> PortalRequest(
                requestId = requestId,
                uid = uid,
                type = type,
                status = status,
                email = email,
                firstName = cleanName(data["firstName"]) ?: return null,
                lastName = cleanName(data["lastName"]) ?: return null,
                phone = cleanPhone(data["phone"]) ?: return null,
                photo = data["photo"] == true,
            )
            PortalRequestType.GOOGLE_LINK -> PortalRequest(
                requestId = requestId,
                uid = uid,
                type = type,
                status = status,
                email = email,
                codeHash = (data["codeHash"] as? String)?.takeIf { CODE_HASH.matches(it) } ?: return null,
            )
            else -> PortalRequest(requestId = requestId, uid = uid, type = type, status = status, email = email)
        }
    }

    fun parseGoogleClaim(googleUid: String, data: Map<String, Any?>): PortalGoogleClaim? {
        if (!UID.matches(googleUid)) return null
        val type = runCatching { PortalGoogleClaimType.valueOf(data["type"].toString()) }.getOrNull() ?: return null
        val email = cleanEmail(data["email"]) ?: return null
        val status = data["status"]?.toString().orEmpty()
        val code = when (type) {
            PortalGoogleClaimType.LINK_CODE -> (data["code"] as? String)?.takeIf { LINK_CODE.matches(it) } ?: return null
            PortalGoogleClaimType.ADMIN_MATCH -> ""
        }
        return PortalGoogleClaim(googleUid, type, status, email, code)
    }
}

/** Firestore timestamps arrive as platform objects; numbers as millis. Null when unknown. */
internal fun firestoreMillisOf(value: Any?): Long? {
    when (value) {
        null -> return null
        is Number -> return value.toLong()
        is String -> return value.toDoubleOrNull()?.toLong()
        is java.util.Date -> return value.time
    }
    return runCatching {
        val toDate = value.javaClass.methods.firstOrNull { it.name == "toDate" && it.parameterCount == 0 }
        if (toDate != null) return (toDate.invoke(value) as? java.util.Date)?.time
        val seconds = value.javaClass.methods.firstOrNull {
            (it.name == "getSeconds" || it.name == "seconds") && it.parameterCount == 0
        }?.invoke(value) as? Number
        seconds?.toLong()?.times(1000)
    }.getOrNull()
}
