package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Benefit
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.affectsAccountLedger
import com.eventmanager.app.data.utils.DateTimeUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Everything the app knows about one volunteer that may be shown on their own portal page.
 * The builder decides what leaves the device; nothing else is ever published.
 */
data class PortalProjectionInput(
    val volunteer: Volunteer,
    /** Jobs of this volunteer only. */
    val jobs: List<Job>,
    /** Ledger rows; filtered to this volunteer by the builder. */
    val transfers: List<AccountTransfer>,
    val jobTypeConfigs: List<JobTypeConfig>,
    val currencyCode: String,
    val associationName: String,
    val hasPhoto: Boolean,
    /** Changes whenever the published photo changes, so the page reloads it. */
    val photoVersion: String,
    val mustSetPassword: Boolean,
    /** Google sign-in is on with at least one verified domain: the page may offer "link my Google account". */
    val googleLinkAllowed: Boolean = false,
    val now: Long,
    val offsetHours: Int,
)

/** The Firestore document `orgs/{orgId}/volunteerPortal/{uid}`. */
data class PortalProjection(
    /** JSON payload read by the portal page. */
    val payload: String,
    /** SHA-256 of [payload]; unchanged content is never re-published. */
    val contentHash: String,
    /** Document schema; volunteers keep the default, other collections pass their own. */
    val schema: Int = SCHEMA,
    /** Extra top-level fields merged into the document. */
    val extraFields: Map<String, Any?> = emptyMap(),
) {
    fun toFirestoreMap(updatedAt: Long): Map<String, Any?> = mapOf(
        "schema" to schema,
        "payload" to payload,
        "contentHash" to contentHash,
        "updatedAt" to updatedAt,
    ) + extraFields

    companion object {
        const val SCHEMA = 2
        const val COLLECTION = "volunteerPortal"
    }
}

object PortalProjectionBuilder {
    const val MAX_PAST_SHIFTS = 60
    const val MAX_UPCOMING_SHIFTS = 10
    const val MAX_TRANSFERS = 5
    internal const val MAX_LABEL = 80

    fun build(input: PortalProjectionInput): PortalProjection {
        val volunteer = input.volunteer
        val jobs = input.jobs.filter { it.volunteerId == volunteer.id }
        val configsByName = input.jobTypeConfigs.associateBy { it.name }
        val timeline = PortalTimelineBuilder.build(volunteer, jobs, input.jobTypeConfigs, input.now, input.offsetHours)
        val gainsByJob = timeline.gains.associateBy { it.jobNanoId }
        // A shift stays upcoming until its event day ends (synced day-change offset), as the page
        // splits them: the split only moves at those ends, so the hash stays stable in between.
        val (upcomingJobs, pastJobs) = jobs.partition { input.now < pastFrom(it.date, input.offsetHours) }
        // Order: upcoming ascending, then past descending.
        val shifts = upcomingJobs.sortedBy { it.date }.take(MAX_UPCOMING_SHIFTS) +
            pastJobs.sortedByDescending { it.date }.take(MAX_PAST_SHIFTS)
        val upcomingIds = upcomingJobs.mapTo(HashSet()) { it.jobNanoId }

        val payload = buildJsonObject {
            put("firstName", volunteer.name.trim())
            put("lastName", volunteer.lastNameAbbreviation.trim())
            put("email", volunteer.email.trim())
            put("phone", volunteer.phoneNumber.trim())
            put("dateOfBirth", volunteer.dateOfBirth.trim())
            put("gender", volunteer.gender?.name ?: "")
            put("hasPhoto", input.hasPhoto)
            put("photoVersion", if (input.hasPhoto) input.photoVersion else "")
            put("shifts", buildJsonArray {
                shifts.forEach { add(shiftJson(it, input.offsetHours, configsByName, (if (it.jobNanoId in upcomingIds) gainsByJob[it.jobNanoId]?.gains else null).orEmpty())) }
            })
            // Past shifts left out by the cap, so the page can still show the real total.
            put("olderShifts", (pastJobs.size - MAX_PAST_SHIFTS).coerceAtLeast(0))
            put("lastShiftFloor", volunteer.lastShiftDate ?: 0L)
            // The first step holds from the beginning of time: its computed start is just today's
            // day start, which would change the hash every day even when nothing else did.
            put("timeline", buildJsonArray {
                timeline.steps.forEachIndexed { i, step -> add(stepJson(step, if (i == 0) 0L else step.from)) }
            })
            put("wallet", walletJson(input.transfers, AccountHolderType.VOLUNTEER, volunteer.id, input.currencyCode))
            // The wallet QR doubles as an admin key for admin volunteers: never mail it from the web.
            put("qrEmailAllowed", !volunteer.isAdmin && volunteer.email.isNotBlank())
            put("mustSetPassword", input.mustSetPassword)
            put("association", input.associationName.trim().take(MAX_LABEL))
            // The Google uid stays on the device: the page only needs the address and who linked it.
            val googleEmail = volunteer.portalGoogleEmail.trim()
            put("googleEmail", googleEmail)
            put("googleLinkedBy", volunteer.portalGoogleLinkedBy.trim())
            put("googleLinkAvailable", input.googleLinkAllowed && googleEmail.isEmpty())
        }.toString()
        return PortalProjection(payload = payload, contentHash = sha256(payload))
    }

    /** When a shift dated [date] becomes past: the end of its event day, the offset included. */
    fun pastFrom(date: Long, offsetHours: Int): Long =
        DateTimeUtils.getEndOfDayWithOffset(date, offsetHours).timeInMillis + 1

    private fun shiftJson(job: Job, offsetHours: Int, configsByName: Map<String, JobTypeConfig>, gains: List<Benefit>): JsonObject {
        val config = configsByName[job.jobTypeName]
        val tracksShiftTime = config == null ||
            (config.isShiftJob && config.requiresShiftTime && config.novaJobType == NovaJobType.DEFAULT_SHIFT)
        return buildJsonObject {
            put("date", job.date)
            put("pastFrom", pastFrom(job.date, offsetHours))
            put("jobTypeName", job.jobTypeName.take(MAX_LABEL))
            put("venueName", job.venueName.take(MAX_LABEL))
            put("shiftTime", if (tracksShiftTime) job.shiftTime.name else "")
            if (gains.isNotEmpty()) put("gains", buildJsonArray { gains.forEach { add(benefitJson(it)) } })
        }
    }

    private fun stepJson(step: PortalTimelineStep, from: Long): JsonObject = buildJsonObject {
        put("from", from)
        put("active", step.active)
        put("summary", step.summary?.let { benefitJson(it) } ?: JsonNull)
        put("benefits", buildJsonArray { step.benefits.forEach { add(benefitJson(it)) } })
        put("uniqueEntriesRemaining", step.uniqueEntriesRemaining?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun benefitJson(benefit: Benefit): JsonObject = buildJsonObject {
        put("description", benefit.description.take(160))
        put("freeEntry", benefit.freeEntry)
        put("friendInvitation", benefit.friendInvitation)
        put("inviteCount", benefit.inviteCount)
        put("drinkTokens", benefit.drinkTokens)
        put("barDiscount", benefit.barDiscount)
        put("guestListAccess", benefit.guestListAccess)
        put("validUntil", benefit.validUntil?.let { JsonPrimitive(it) } ?: JsonNull)
        put("futureEntriesRemaining", benefit.futureEventEntriesRemaining?.let { JsonPrimitive(it) } ?: JsonNull)
        put("futureEntryInvites", benefit.futureEventEntryInvites?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /** Human label only — never ids, references or another person's name. */
    internal fun transferLabel(transfer: AccountTransfer): String = when (transfer.type) {
        AccountTransferType.SHIFT_CREDIT, AccountTransferType.SHIFT_REVERSAL -> transfer.jobTypeName
        AccountTransferType.POS_SALE, AccountTransferType.MANUAL_ADJUSTMENT -> transfer.description
    }.trim().take(MAX_LABEL)

    internal fun roundCents(value: Double): Double = Math.round(value * 100.0) / 100.0

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Wallet block of a portal page: balance and the latest ledger rows of exactly one holder. */
internal fun PortalProjectionBuilder.walletJson(
    transfers: List<AccountTransfer>,
    holderType: AccountHolderType,
    holderId: String,
    currencyCode: String,
): JsonElement {
    val mine = transfers.filter {
        it.holderType == holderType && it.holderId == holderId && it.affectsAccountLedger()
    }
    val balance = mine.sumOf { it.amount }
    val recent = mine.sortedByDescending { it.createdAt }.take(MAX_TRANSFERS)
    return buildJsonObject {
        put("balance", roundCents(balance))
        put("currency", currencyCode.ifBlank { "CHF" })
        put("transfers", JsonArray(recent.map { transfer ->
            buildJsonObject {
                put("at", transfer.createdAt)
                put("type", transfer.type.name)
                put("amount", roundCents(transfer.amount))
                put("label", transferLabel(transfer))
                put("venue", transfer.posVenueName.take(MAX_LABEL))
            }
        }))
    }
}
