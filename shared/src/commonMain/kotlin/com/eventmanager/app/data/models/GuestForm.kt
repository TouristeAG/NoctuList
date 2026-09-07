package com.eventmanager.app.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.utils.AppTimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

/** Where a guest form stands. The public page only ever serves [OPEN] forms. */
enum class GuestFormStatus {
    OPEN,
    PENDING_REVIEW,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    ;

    companion object {
        fun parse(raw: String?): GuestFormStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: OPEN
    }
}

/**
 * When the public link stops working.
 *
 * For single-response forms, leaving [GuestFormStatus.OPEN] on the first answer also closes the
 * link. Multi-response templates stay OPEN until [expiresAtMillis] (or admin delete).
 */
enum class GuestFormExpiry {
    AFTER_RESPONSE,
    DAY_BEFORE,
    EVENT_DAY,
    DAY_AFTER,
    MANUAL,
    ;

    companion object {
        fun parse(raw: String?): GuestFormExpiry =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: AFTER_RESPONSE
    }
}

/** How a logo is clipped on the public form and in the creator preview. */
enum class GuestFormLogoShape {
    SQUARE,
    ROUNDED,
    CIRCLE,
    ;

    companion object {
        fun parse(raw: String?): GuestFormLogoShape =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ROUNDED
    }
}

/**
 * A public guest list form handed to an artist or a guest institution so they fill in their own
 * temporary guest list.
 *
 * Firebase-only. [formId] is both the Firestore document ID and the unguessable token in the
 * public URL, which is what keeps a form private without any sign-in.
 */
@Entity(
    tableName = "guest_forms",
    indices = [
        Index(value = ["firebaseOrgId", "formId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["lastModified"]),
        Index(value = ["parentFormId"]),
    ],
)
data class GuestForm(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** NanoID; doubles as the Firestore document ID and the public URL token. */
    val formId: String,
    val firebaseOrgId: String = "",
    val venueName: String = "",
    val eventName: String = "",
    val eventDateMillis: Long = 0L,
    /** Artist or institution answering the form. */
    val artistName: String = "",
    /** [VenueAccess] IDs the answerer may request, comma separated. */
    val offeredAccessIds: String = "",
    val maxGuests: Int = DEFAULT_MAX_GUESTS,
    val expiryMode: String = GuestFormExpiry.AFTER_RESPONSE.name,
    val expiresAtMillis: Long = 0L,
    /**
     * When true, the public link stays OPEN until expiry and each answer creates a child
     * [PENDING_REVIEW] document with [parentFormId] set to this form's [formId].
     */
    val allowMultipleResponses: Boolean = false,
    /** Non-empty on response children spawned from a multi-response template. */
    val parentFormId: String = "",
    /** Reuses the synced institution logo instead of carrying a copy per form. */
    val showInstitutionLogo: Boolean = true,
    /**
     * Snapshot of the institution logo as a data URI. The public page cannot read institution
     * settings, so the bytes travel with the form document and are cleared when it closes.
     */
    val institutionLogoDataUri: String = "",
    val institutionLogoShape: String = GuestFormLogoShape.ROUNDED.name,
    val institutionLogoInvert: Boolean = false,
    /** Optional second logo for the invited artist; cleared once the form closes. */
    val guestLogoDataUri: String = "",
    val guestLogoShape: String = GuestFormLogoShape.ROUNDED.name,
    val guestLogoInvert: Boolean = false,
    /** When false, the public form hides the contact email field. */
    val askEmail: Boolean = true,
    /** When false, the public form hides the emergency phone field. */
    val askPhone: Boolean = true,
    val prefillEmail: String = "",
    val prefillPhone: String = "",
    val status: String = GuestFormStatus.OPEN.name,
    /** [GuestFormSubmissionCodec] payload; empty until the artist answers. */
    val submissionJson: String = "",
    val submittedAt: Long = 0L,
    val reviewedAt: Long = 0L,
    val reviewedBy: String = "",
    val createdAt: Long = 0L,
    val lastModified: Long = 0L,
) {
    companion object {
        const val DEFAULT_MAX_GUESTS = 10
        const val MAX_GUESTS_LIMIT = 100

        /** Hard retention: forms are deleted at end of day this many days after the event. */
        const val MAX_DAYS_AFTER_EVENT = 3

        /** Mirrors the ceiling the Firestore rules enforce on `submissionJson`. */
        const val MAX_SUBMISSION_CHARS = 20_000
    }
}

val GuestForm.statusValue: GuestFormStatus get() = GuestFormStatus.parse(status)

val GuestForm.expiryModeValue: GuestFormExpiry get() = GuestFormExpiry.parse(expiryMode)

val GuestForm.institutionLogoShapeValue: GuestFormLogoShape
    get() = GuestFormLogoShape.parse(institutionLogoShape)

val GuestForm.guestLogoShapeValue: GuestFormLogoShape
    get() = GuestFormLogoShape.parse(guestLogoShape)

fun GuestForm.offeredAccessIdSet(): Set<String> =
    GuestFormOfferedAccessCodec.decode(offeredAccessIds).map { it.id }.toSet()

fun GuestForm.offeredAccesses(): List<GuestFormOfferedAccess> =
    GuestFormOfferedAccessCodec.decode(offeredAccessIds)

/** Awaiting an admin decision, which is what the dashboard card counts. */
val GuestForm.awaitsReview: Boolean get() = statusValue == GuestFormStatus.PENDING_REVIEW

/** True when this row is an answer spawned from a multi-response template. */
val GuestForm.isResponseChild: Boolean get() = parentFormId.isNotBlank()

/** A form nobody can answer any more, whether it was decided, expired or already filled. */
val GuestForm.isClosed: Boolean get() = statusValue != GuestFormStatus.OPEN

fun GuestForm.isExpiredAt(nowMillis: Long): Boolean =
    statusValue == GuestFormStatus.OPEN && expiresAtMillis in 1 until nowMillis

/**
 * Public guest list forms are a Firebase-only surface behind an opt-in institution setting: the
 * Sheets backend has no place to store a form and no rules engine to expose one safely.
 */
fun guestFormFeaturesEnabled(backend: BackendType, settingEnabled: Boolean): Boolean =
    backend == BackendType.FIREBASE && settingEnabled

/**
 * Resolves the chosen expiry into an absolute deadline the Firestore rules can compare against.
 *
 * Everything lands on the end of the day in venue time, so a form set to close on the event day
 * stays usable for the whole day rather than dying at midnight of the previous night.
 *
 * Hard cap: never later than [MAX_DAYS_AFTER_EVENT] days after the event, so an unanswered
 * form cannot linger indefinitely (including MANUAL dates far in the future).
 */
fun resolveGuestFormExpiry(
    mode: GuestFormExpiry,
    eventDateMillis: Long,
    manualDateMillis: Long = 0L,
): Long {
    fun endOfDay(millis: Long): Long = AppTimeZone.calendar().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis

    fun shiftDays(millis: Long, days: Int): Long = AppTimeZone.calendar().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis

    val chosen = when (mode) {
        // Outer bound while OPEN. Single-response forms also leave OPEN on first answer;
        // multi-response templates stay OPEN until this deadline.
        GuestFormExpiry.AFTER_RESPONSE -> endOfDay(shiftDays(eventDateMillis, 1))
        GuestFormExpiry.DAY_BEFORE -> endOfDay(shiftDays(eventDateMillis, -1))
        GuestFormExpiry.EVENT_DAY -> endOfDay(eventDateMillis)
        GuestFormExpiry.DAY_AFTER -> endOfDay(shiftDays(eventDateMillis, 1))
        GuestFormExpiry.MANUAL ->
            if (manualDateMillis > 0L) endOfDay(manualDateMillis) else endOfDay(eventDateMillis)
    }
    if (eventDateMillis <= 0L) return chosen
    val hardCap = guestFormHardRetentionDeadlineMillis(eventDateMillis)
    return minOf(chosen, hardCap)
}

/**
 * Absolute latest moment a guest form may still exist: end of day
 * [GuestForm.MAX_DAYS_AFTER_EVENT] days after the event.
 */
fun guestFormHardRetentionDeadlineMillis(eventDateMillis: Long): Long {
    if (eventDateMillis <= 0L) return 0L
    return AppTimeZone.calendar().apply {
        timeInMillis = eventDateMillis
        add(Calendar.DAY_OF_YEAR, GuestForm.MAX_DAYS_AFTER_EVENT)
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}

/** True when this form must be deleted (event + 3 days, regardless of fill status). */
fun GuestForm.isPastHardRetention(nowMillis: Long): Boolean {
    val deadline = when {
        eventDateMillis > 0L -> guestFormHardRetentionDeadlineMillis(eventDateMillis)
        expiresAtMillis > 0L -> expiresAtMillis
        createdAt > 0L -> guestFormHardRetentionDeadlineMillis(createdAt)
        else -> 0L
    }
    return deadline > 0L && nowMillis > deadline
}

/** An access the artist may request, with the name the public page needs to show. */
@Serializable
data class GuestFormOfferedAccess(
    val id: String = "",
    val name: String = "",
)

/**
 * Stores offered accesses as JSON so the public page can show names without reading the
 * institution catalogue (which is member-only).
 */
object GuestFormOfferedAccessCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(
        GuestFormOfferedAccess.serializer(),
    )

    fun encode(accesses: List<VenueAccess>): String {
        val entries = accesses
            .map { GuestFormOfferedAccess(it.id.trim(), it.name.trim()) }
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
        if (entries.isEmpty()) return ""
        return json.encodeToString(listSerializer, entries)
    }

    fun decode(raw: String): List<GuestFormOfferedAccess> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return runCatching { json.decodeFromString(listSerializer, trimmed) }
                .getOrElse { emptyList() }
                .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
        }
        // Legacy comma-separated IDs, kept so a half-written form still resolves.
        return trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { GuestFormOfferedAccess(id = it, name = it) }
    }
}

fun GuestFormSubmission.toManualEntries(): List<ManualTemporaryGuestEntry> =
    people.map { ManualTemporaryGuestEntry(name = it.name, accessIds = it.requestedAccessIds) }

/** One person on an artist's answer. */
data class GuestFormPerson(
    val name: String,
    val requestedAccessIds: Set<String> = emptySet(),
)

/**
 * What the artist sent back. The contact email and emergency phone are per batch, matching
 * [ManualTemporaryGuestBatch], not per person.
 */
data class GuestFormSubmission(
    val contactEmail: String = "",
    val emergencyPhone: String = "",
    val notes: String = "",
    val people: List<GuestFormPerson> = emptyList(),
)

/**
 * Serializes an answer into the single string field the form document carries, so the sync
 * pipeline never has to map nested arrays. Same approach as [VenueAccessCatalog].
 */
object GuestFormSubmissionCodec {

    const val MAX_NAME_LENGTH = 60
    const val MAX_NOTES_LENGTH = 500

    @Serializable
    private data class PersonEntry(val name: String = "", val access: List<String> = emptyList())

    @Serializable
    private data class SubmissionEntry(
        val email: String = "",
        val phone: String = "",
        val notes: String = "",
        val people: List<PersonEntry> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(submission: GuestFormSubmission): String {
        if (submission.people.isEmpty()) return ""
        val entry = SubmissionEntry(
            email = submission.contactEmail.trim(),
            phone = submission.emergencyPhone.trim(),
            notes = submission.notes.trim().take(MAX_NOTES_LENGTH),
            people = submission.people.map { person ->
                PersonEntry(
                    name = normalizeName(person.name),
                    access = person.requestedAccessIds.filter { it.isNotBlank() }.sorted(),
                )
            },
        )
        return json.encodeToString(SubmissionEntry.serializer(), entry)
    }

    /** Returns null for anything unusable, so a corrupt payload never fabricates guests. */
    fun decode(raw: String): GuestFormSubmission? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val entry = runCatching {
            json.decodeFromString(SubmissionEntry.serializer(), trimmed)
        }.getOrElse { return null }
        val people = entry.people
            .map { GuestFormPerson(normalizeName(it.name), it.access.map(String::trim).filter { id -> id.isNotEmpty() }.toSet()) }
            .filter { it.name.isNotEmpty() }
        if (people.isEmpty()) return null
        return GuestFormSubmission(
            contactEmail = entry.email.trim(),
            emergencyPhone = entry.phone.trim(),
            notes = entry.notes.trim().take(MAX_NOTES_LENGTH),
            people = people,
        )
    }

    fun normalizeName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)
}
