package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Benefit
import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.VolunteerRank
import com.eventmanager.app.data.models.affectsAccountLedger
import com.eventmanager.app.data.models.isNovaMeetingOnlyStylePerk
import com.eventmanager.app.data.utils.VolunteerActivityManager
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
    val now: Long,
    val offsetHours: Int,
)

/** The Firestore document `orgs/{orgId}/volunteerPortal/{uid}`. */
data class PortalProjection(
    /** JSON payload read by the portal page. */
    val payload: String,
    /** SHA-256 of [payload]; unchanged content is never re-published. */
    val contentHash: String,
) {
    fun toFirestoreMap(updatedAt: Long): Map<String, Any?> = mapOf(
        "schema" to SCHEMA,
        "payload" to payload,
        "contentHash" to contentHash,
        "updatedAt" to updatedAt,
    )

    companion object {
        const val SCHEMA = 1
        const val COLLECTION = "volunteerPortal"
    }
}

object PortalProjectionBuilder {
    const val MAX_PAST_SHIFTS = 60
    const val MAX_UPCOMING_SHIFTS = 10
    const val MAX_TRANSFERS = 5
    private const val MAX_LABEL = 80

    fun build(input: PortalProjectionInput): PortalProjection {
        val volunteer = input.volunteer
        val jobs = input.jobs.filter { it.volunteerId == volunteer.id }
        val status = BenefitCalculator.calculateVolunteerBenefitStatusFromVolunteerJobs(
            volunteer,
            jobs,
            input.jobTypeConfigs,
            input.now,
            input.offsetHours,
        )
        val configsByName = input.jobTypeConfigs.associateBy { it.name }
        val past = jobs.filter { it.date <= input.now }.sortedByDescending { it.date }
        val upcoming = jobs.filter { it.date > input.now }.sortedBy { it.date }
        val benefits = status.activeBenefits
            .filter { it.isActive || (it.futureEventEntriesRemaining ?: 0) > 0 }
            .filterNot { status.rank == VolunteerRank.ORION && it.isNovaMeetingOnlyStylePerk() }

        val payload = buildJsonObject {
            put("firstName", volunteer.name.trim())
            put("lastName", volunteer.lastNameAbbreviation.trim())
            put("email", volunteer.email.trim())
            put("phone", volunteer.phoneNumber.trim())
            put("dateOfBirth", volunteer.dateOfBirth.trim())
            put("gender", volunteer.gender?.name ?: "")
            put("hasPhoto", input.hasPhoto)
            put("photoVersion", if (input.hasPhoto) input.photoVersion else "")
            put("rank", status.rank?.name ?: "")
            put("active", VolunteerActivityManager.isVolunteerActive(volunteer, jobs))
            put("totalShifts", jobs.size)
            put("lastShiftAt", VolunteerActivityManager.effectiveLastShiftDate(volunteer, jobs) ?: 0L)
            put("shiftsTotal", past.size)
            put("shifts", buildJsonArray { past.take(MAX_PAST_SHIFTS).forEach { add(shiftJson(it, configsByName)) } })
            put("upcomingShifts", buildJsonArray {
                upcoming.take(MAX_UPCOMING_SHIFTS).forEach { add(shiftJson(it, configsByName)) }
            })
            put("benefits", buildJsonArray { benefits.forEach { add(benefitJson(it)) } })
            // What applies right now, combined across ranks exactly as the door and bar see it.
            if (benefits.isNotEmpty()) put("summary", benefitJson(status.benefits))
            put("uniqueEntriesRemaining", status.benefits.futureEventEntriesRemaining?.let { JsonPrimitive(it) } ?: JsonNull)
            put("wallet", walletJson(input))
            // The wallet QR doubles as an admin key for admin volunteers: never mail it from the web.
            put("qrEmailAllowed", !volunteer.isAdmin && volunteer.email.isNotBlank())
            put("mustSetPassword", input.mustSetPassword)
            put("association", input.associationName.trim().take(MAX_LABEL))
        }.toString()
        return PortalProjection(payload = payload, contentHash = sha256(payload))
    }

    private fun shiftJson(job: Job, configsByName: Map<String, JobTypeConfig>): JsonObject {
        val config = configsByName[job.jobTypeName]
        val tracksShiftTime = config == null ||
            (config.isShiftJob && config.requiresShiftTime && config.novaJobType == NovaJobType.DEFAULT_SHIFT)
        return buildJsonObject {
            put("date", job.date)
            put("jobTypeName", job.jobTypeName.take(MAX_LABEL))
            put("venueName", job.venueName.take(MAX_LABEL))
            put("shiftTime", if (tracksShiftTime) job.shiftTime.name else "")
        }
    }

    private fun benefitJson(benefit: Benefit): JsonObject = buildJsonObject {
        put("rank", benefit.rank?.name ?: "")
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

    private fun walletJson(input: PortalProjectionInput): JsonElement {
        val mine = input.transfers.filter {
            it.holderType == AccountHolderType.VOLUNTEER &&
                it.holderId == input.volunteer.id &&
                it.affectsAccountLedger()
        }
        val balance = mine.sumOf { it.amount }
        val recent = mine.sortedByDescending { it.createdAt }.take(MAX_TRANSFERS)
        return buildJsonObject {
            put("balance", roundCents(balance))
            put("currency", input.currencyCode.ifBlank { "CHF" })
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

    /** Human label only — never ids, references or another person's name. */
    internal fun transferLabel(transfer: AccountTransfer): String = when (transfer.type) {
        AccountTransferType.SHIFT_CREDIT, AccountTransferType.SHIFT_REVERSAL -> transfer.jobTypeName
        AccountTransferType.POS_SALE, AccountTransferType.MANUAL_ADJUSTMENT -> transfer.description
    }.trim().take(MAX_LABEL)

    private fun roundCents(value: Double): Double = Math.round(value * 100.0) / 100.0

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
