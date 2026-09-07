package com.eventmanager.app.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.eventmanager.app.data.utils.NanoIdGenerator
import com.eventmanager.app.data.utils.effectiveBenefitFutureEntriesRemaining

@Entity(
    tableName = "guests",
    indices = [
        Index(value = ["sheetsId"]),
        Index(value = ["volunteerId"]),
        Index(value = ["venueName"]),
        Index(value = ["lastModified"]),
        Index(value = ["isVolunteerBenefit"])
    ]
)
data class Guest(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sheetsId: String? = null, // Google Sheets row ID for syncing
    val nanoId: String = NanoIdGenerator.generateGuestId(), // Globally-unique NanoID for cross-device sync
    val name: String,
    val lastNameAbbreviation: String = "", // Last name abbreviation for volunteer guests
    val email: String = "", // Guest email address
    val phoneNumber: String = "", // Guest phone number
    val invitations: Int,
    val venueName: String, // Store actual venue name for unlimited venue support
    val notes: String = "",
    val isVolunteerBenefit: Boolean = false,
    val volunteerId: String? = null, // NanoID of the volunteer this guest entry represents (for volunteer benefits)
    val lastModified: Long = System.currentTimeMillis(),
    val isTemporaryGuest: Boolean = false,
    val temporaryArtistName: String = "",
    val temporaryEventDate: Long? = null,
    val temporaryContactPhone: String = "",
    /** Venue a temporary guest is allowed into. Firebase-only; never synced to Google Sheets. */
    val temporaryVenueName: String = "",
    /** Single artist/manager mail receiving the whole batch. Firebase-only; never synced to Google Sheets. */
    val temporaryContactEmail: String = "",
    /** Comma-separated [VenueAccess] IDs. Firebase-only; never synced to Google Sheets. */
    val temporaryAccessIds: String = "",
    /** Epoch millis of the one-shot entry validation; 0 when unused. Firebase-only. */
    val temporaryEntryValidatedAt: Long = 0L,
    /** Account or device that validated the entry. Firebase-only. */
    val temporaryEntryValidatedBy: String = "",
    val nfcCardUid: String = "",
    val nfcCardUidHash: String = "",
    val isAdmin: Boolean = false,
    val firebaseOrgId: String = "",
    /** Firebase Storage object path; empty when unused. Never synced to Google Sheets. */
    val profilePhotoPath: String = "",
    /** Firebase Storage download URL; empty when unused. Never synced to Google Sheets. */
    val profilePhotoUrl: String = "",
    /** Bar discount granted to a permanent guest, in percent. Firebase-only; never synced to Google Sheets. */
    val barDiscountPercent: Int = 0,
)

/**
 * Bar discount a guest is entitled to. The field only exists on the Firebase backend, so
 * [firebaseBackend] must be false on Sheets to keep guests at full price there. Volunteer benefit
 * rows are computed from shifts and carry their discount through [BenefitCalculator] instead.
 */
fun Guest.activeBarDiscountPercent(firebaseBackend: Boolean): Int =
    if (firebaseBackend && !isVolunteerBenefit) {
        barDiscountPercent.coerceIn(0, 100)
    } else {
        0
    }

/** A temporary guest's single entry has already been consumed at the door. */
val Guest.temporaryEntryValidated: Boolean
    get() = temporaryEntryValidatedAt > 0L

fun Guest.temporaryAccessIdSet(): Set<String> =
    temporaryAccessIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

fun encodeTemporaryAccessIds(ids: Set<String>): String =
    ids.filter { it.isNotBlank() }.sorted().joinToString(",")

/**
 * Groups a temporary guest with everyone invited by the same artist for the same night and venue.
 * This is the unit the artist QR mail is sent for.
 */
fun Guest.temporaryBatchKey(): String =
    listOf(
        temporaryArtistName.trim().lowercase(),
        (temporaryEventDate ?: 0L).toString(),
        temporaryVenueName.trim().lowercase(),
    ).joinToString("|")

/**
 * One person on a manually added temporary guest batch. [accessIds] are [VenueAccess] IDs and stay
 * empty on the Sheets backend, which has no access catalogue.
 */
data class ManualTemporaryGuestEntry(
    val name: String,
    val accessIds: Set<String> = emptySet(),
)

/**
 * Manual add of temporary guests from the guest list: one Google Sheet row per [guests] entry,
 * sharing event date, artist, emergency contact phone, and comments. [venueName], [contactEmail]
 * and per-guest accesses are Firebase-only and ignored by the Sheets backend.
 */
data class ManualTemporaryGuestBatch(
    val eventDateMillis: Long,
    val artistName: String,
    val emergencyContactPhone: String,
    val comments: String,
    val guests: List<ManualTemporaryGuestEntry>,
    val venueName: String = "",
    val contactEmail: String = "",
    val barDiscountPercent: Int = 0,
) {
    val guestNames: List<String> get() = guests.map { it.name }
}

@Entity(
    tableName = "volunteers",
    indices = [
        Index(value = ["sheetsId"]),
        Index(value = ["isActive"]),
        Index(value = ["currentRank"]),
        Index(value = ["lastModified"])
    ]
)
data class Volunteer(
    @PrimaryKey
    val id: String = NanoIdGenerator.generateVolunteerId(), // NanoID generated at object creation
    val sheetsId: String? = null, // Google Sheets row ID for syncing
    val name: String,
    val lastNameAbbreviation: String,
    val email: String,
    val phoneNumber: String,
    val dateOfBirth: String = "", // Store as string for simplicity
    val gender: Gender? = null, // Gender field with nullable default
    val currentRank: VolunteerRank? = null, // No default rank - must be earned
    val isActive: Boolean = true,
    val lastShiftDate: Long? = null, // Timestamp of last shift
    val lastModified: Long = System.currentTimeMillis(),
    val nfcCardUid: String = "",
    val nfcCardUidHash: String = "",
    val isAdmin: Boolean = false,
    val firebaseOrgId: String = "",
    /** Firebase Storage object path; empty when unused. Never synced to Google Sheets. */
    val profilePhotoPath: String = "",
    /** Firebase Storage download URL; empty when unused. Never synced to Google Sheets. */
    val profilePhotoUrl: String = "",
)

@Entity(
    tableName = "jobs",
    indices = [
        Index(value = ["volunteerId"]),
        Index(value = ["date"]),
        Index(value = ["venueName"]),
        Index(value = ["jobTypeName"]),
        Index(value = ["sheetsId"]),
        Index(value = ["lastModified"]),
        Index(value = ["volunteerId", "date"]),
        Index(value = ["date", "shiftTime"])
    ]
)
data class Job(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sheetsId: String? = null, // Google Sheets row ID for syncing
    /** Stable cross-device ID for Firestore document keys (Room v37+). */
    val jobNanoId: String = NanoIdGenerator.generateGuestId(),
    val volunteerId: String, // NanoID of the volunteer
    val jobType: JobType,
    val jobTypeName: String, // Store the actual job type name for personalized types
    val venueName: String, // Store actual venue name for unlimited venue support
    val date: Long, // Store as timestamp
    val shiftTime: ShiftTime,
    /** null = job does not track consumable future event entries; 0 = all used; n = n entries remaining (Sheets: "n left (+X inv.)"). */
    val benefitFutureEntriesRemaining: Int? = null,
    /** How many friends/invites accompany each future entry use. null = not tracking; 0 = solo entry; n = n guests with the holder. */
    val benefitFutureEntryInvites: Int? = null,
    val notes: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    val firebaseOrgId: String = "",
)

data class Benefit(
    val rank: VolunteerRank?,
    val description: String,
    val freeEntry: Boolean = false,
    val friendInvitation: Boolean = false,
    val inviteCount: Int = 0, // Number of invites (for manual rewards)
    val drinkTokens: Int = 0,
    val barDiscount: Int = 0,
    val guestListAccess: Boolean = false,
    val extraordinaryBenefits: Boolean = false,
    val validUntil: Long? = null, // Timestamp when benefits expire
    val isActive: Boolean = true,
    /** Manual (or aggregated) consumable future self-entries remaining; null = not shown / N/A. */
    val futureEventEntriesRemaining: Int? = null,
    /** Invites (friends) per future entry use; null = not applicable. */
    val futureEventEntryInvites: Int? = null
)

/**
 * True for active NOVA perks that match a Nova **MEETING** same-day package (one off-event drink only).
 * Orion volunteers must not receive these; used to hide matching rows if they appear in [VolunteerBenefitStatus.activeBenefits].
 */
fun Benefit.isNovaMeetingOnlyStylePerk(): Boolean {
    if (rank != VolunteerRank.NOVA || !isActive) return false
    if (freeEntry || friendInvitation || barDiscount > 0) return false
    return drinkTokens == 1 && inviteCount == 0
}

data class VolunteerBenefitStatus(
    val volunteerId: String, // NanoID of the volunteer
    val rank: VolunteerRank?,
    val benefits: Benefit,
    val activeBenefits: List<Benefit> = emptyList(), // All active benefits from all applicable ranks
    val lastJobDate: Long? = null,
    val monthlyShifts: Int = 0,
    val isEligibleForGalaxie: Boolean = false,
    val isEligibleForEtoile: Boolean = false,
    val isEligibleForNova: Boolean = false
) 

enum class Venue {
    GROOVE,
    LE_TERREAU,
    BOTH
}

enum class VolunteerRank {
    NOVA,       // All shift-type jobs (default profité/non-profité, meeting, photographer, graphic designer)
    ETOILE,     // Legacy: kept for DB backward compatibility, no longer actively assigned
    GALAXIE,    // 3+ shifts/meetings in a month
    ORION,      // Committee roles
    VETERAN,    // Ex-Orion (1 year after mandate)
    SPECIAL     // Manual rewards
}

/**
 * Sub-type of a NOVA (shift) job, determining which benefit package applies.
 * Configured on [JobTypeConfig] when [JobTypeConfig.isShiftJob] is true.
 */
enum class NovaJobType {
    DEFAULT_SHIFT,                  // Regular shift — uses profité / non-profité distinction
    MEETING,                        // Participation in a meeting
    GRAPHIC_DESIGNER_EVENT,         // Graphic design for a specific event
    PHOTOGRAPHER_VIDEOGRAPHER,      // Photo / video coverage of an event
    GRAPHIC_DESIGNER_ASSOCIATION    // Graphic design for the association in general
}

enum class JobType {
    BAR,
    SECURITY,
    CLEANING,
    SETUP,
    SOUND_TECH,
    LIGHTING,
    ENTRANCE,
    CLOAKROOM,
    COORDINATION,
    COMMITTEE,
    COMMISSION_PRESIDENCY,
    MEETING,
    OTHER
}

enum class Gender {
    FEMALE,
    MALE,
    NON_BINARY,
    OTHER,
    PREFER_NOT_TO_DISCLOSE
}

@Entity(
    tableName = "job_type_configs",
    indices = [
        Index(value = ["sheetsId"]),
        Index(value = ["firebaseOrgId", "name"], unique = true),
        Index(value = ["isActive"]),
        Index(value = ["lastModified"])
    ]
)
data class JobTypeConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sheetsId: String? = null, // Google Sheets row ID for syncing
    val name: String,
    val isActive: Boolean = true,
    val isShiftJob: Boolean = true, // If true, counts for Nova / Galaxie
    val isOrionJob: Boolean = false, // If true, counts for Orion rank
    val requiresShiftTime: Boolean = true, // If true, needs profited vs non-profited (only for DEFAULT_SHIFT)
    val novaJobType: NovaJobType = NovaJobType.DEFAULT_SHIFT, // Sub-type of NOVA shift determining the benefits package
    val benefitSystemType: BenefitSystemType = BenefitSystemType.STELLAR, // Type of benefit system
    val manualRewards: ManualRewards? = null, // Manual rewards configuration (only used if benefitSystemType is MANUAL)
    /** null = use default drink-equivalent CHF; 0 = no credit; >0 = override per shift */
    val accountCreditChf: Double? = null,
    val description: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    val firebaseOrgId: String = "",
)

@Entity(
    tableName = "venues",
    indices = [
        Index(value = ["sheetsId"]),
        Index(value = ["firebaseOrgId", "name"], unique = true),
        Index(value = ["isActive"]),
        Index(value = ["lastModified"])
    ]
)
data class VenueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sheetsId: String? = null, // Google Sheets row ID for syncing
    val name: String,
    val description: String = "",
    val isActive: Boolean = true,
    val lastModified: Long = System.currentTimeMillis(),
    /** Google Sheets column E — header "Number of people"; people count for this venue (synced). */
    val peopleCounterCount: Int = 0,
    /** Google Sheets column F — header "Priority Device ID"; device ID that may write counter updates. */
    val peopleCounterWriterDeviceId: String = "",
    /** Firebase only: Google account email of the device that holds people-counter priority. */
    val peopleCounterWriterAccountEmail: String = "",
    /** Google Sheets column G — header "Last Modified (counter)"; millis when counter cells were last written. */
    val peopleCounterLastModified: Long = 0L,
    /** Google Sheets column H — header "Announcement Title". */
    val announcementTitle: String = "",
    /** Google Sheets column I — header "Announcement Message". */
    val announcementMessage: String = "",
    /** Google Sheets column J — header "Announcement Sent At"; millis when the announcement was sent. */
    val announcementSentAt: Long = 0L,
    /** Google Sheets column K — header "Announcement Sender Device ID"; device that sent the announcement. */
    val announcementSenderDeviceId: String = "",
    val firebaseOrgId: String = "",
)

@Entity(
    tableName = "sales_sheet_items",
    indices = [
        Index(value = ["sheetsId"]),
        Index(value = ["firebaseOrgId", "name"], unique = true),
        Index(value = ["requiredRank"]),
        Index(value = ["isActive"]),
        Index(value = ["lastModified"])
    ]
)
data class SalesSheetItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sheetsId: String? = null, // Google Sheets row ID for syncing
    val name: String,
    val price: Double,
    val hasDiscount: Boolean = false,
    /** Deposit ("consigne"): the POS pairs this product with a return tile that refunds [price]. */
    val isDeposit: Boolean = false,
    val requiredRank: VolunteerRank? = null,
    val categories: String = "", // Comma-separated SalesCategory names
    /** Admin-defined refinement of [categories]; Firebase-only, never synced to Google Sheets. */
    val subcategory: String = "",
    val emoji: String = "",
    val availableVenues: String = "",
    val isActive: Boolean = true,
    val lastModified: Long = System.currentTimeMillis(),
    val firebaseOrgId: String = "",
)

enum class SalesCategory {
    MERCH,
    ENTRY,
    BAR,
    OTHER;

    companion object {
        fun parseList(raw: String): Set<SalesCategory> =
            raw.split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { name -> entries.find { it.name == name } }
                .toSet()

        fun formatList(categories: Set<SalesCategory>): String =
            categories.joinToString(",") { it.name }
    }
} 

enum class ShiftTime {
    BEFORE_MIDNIGHT,
    AFTER_MIDNIGHT
}

/**
 * English values for the Google Sheets "Shift Time" column (column E on the jobs/shifts sheet).
 * Legacy cells may still contain [ShiftTime] enum names; see [parseShiftTimeFromGoogleSheets].
 */
object ShiftTimeGoogleSheets {
    const val EVENING_PROFITED = "Evening shift (profited)"
    const val EVENING_NON_PROFITED = "Evening shift (non-profited)"
}

private fun normalizedShiftTimeToken(raw: String): String =
    raw.trim()
        .replace("-", "_")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString("_")
        .uppercase()

fun ShiftTime.toGoogleSheetsShiftTimeValue(): String = when (this) {
    ShiftTime.BEFORE_MIDNIGHT -> ShiftTimeGoogleSheets.EVENING_PROFITED
    ShiftTime.AFTER_MIDNIGHT -> ShiftTimeGoogleSheets.EVENING_NON_PROFITED
}

/**
 * Parsed result from the Google Sheets "Entries left" column.
 * @param remaining How many self-entries the holder has left.
 * @param invites   How many friends can accompany each entry use.
 */
data class FutureEntrySheetData(val remaining: Int, val invites: Int)

/** Google Sheets jobs column "Entries left" (e.g. "2 left (+1 inv.)"); empty cell = not tracking. */
fun formatJobBenefitFutureEntriesForSheets(remaining: Int?, invites: Int? = null): String {
    if (remaining == null) return ""
    val inv = invites ?: 1
    return "$remaining left (+$inv inv.)"
}

private val jobSheetEntriesLeftWithInvRegex =
    Regex("""^(\d+)\s*left\s*\(\+(\d+)\s*inv\.\)$""", RegexOption.IGNORE_CASE)
private val jobSheetEntriesLeftRegex =
    Regex("""^(\d+)\s*left$""", RegexOption.IGNORE_CASE)

/**
 * Parses "Entries left" column. Supports:
 * - "n left (+X inv.)" → FutureEntrySheetData(n, X)
 * - "n left"           → FutureEntrySheetData(n, 1) (legacy default)
 * - "Yes"              → FutureEntrySheetData(0, 1)
 * - "No"               → FutureEntrySheetData(1, 1)
 * - ""                 → null
 */
fun parseJobBenefitFutureEntriesFromSheets(cellValue: String): FutureEntrySheetData? {
    val s = cellValue.trim()
    if (s.isEmpty()) return null
    when (s.lowercase()) {
        "yes", "oui", "sí", "si" -> return FutureEntrySheetData(0, 1)
        "no" -> return FutureEntrySheetData(1, 1)
    }
    jobSheetEntriesLeftWithInvRegex.matchEntire(s)?.let { m ->
        val rem = m.groupValues[1].toIntOrNull() ?: return null
        val inv = m.groupValues[2].toIntOrNull() ?: 1
        return FutureEntrySheetData(rem, inv)
    }
    jobSheetEntriesLeftRegex.matchEntire(s)?.groupValues?.get(1)?.toIntOrNull()?.let {
        return FutureEntrySheetData(it, 1)
    }
    s.toIntOrNull()?.let { return FutureEntrySheetData(it, 1) }
    return null
}

/**
 * Legacy jobs/shifts sheets sometimes put [Venue] enum names or venue nicknames in the "Shift Time"
 * column instead of [ShiftTime] or the English evening labels. Map those to a sensible default so
 * rows still load and the UI can show shifts.
 */
private fun shiftTimeFromLegacyVenueLikeCell(normalizedToken: String): ShiftTime? {
    val n = normalizedToken.uppercase()
    if (n == "BOTH" || n == "GROOVE" || n == "LE_TERREAU") return ShiftTime.BEFORE_MIDNIGHT
    if (n.contains("GROOVE") || n.contains("TERREAU")) return ShiftTime.BEFORE_MIDNIGHT
    return null
}

private val sheetShiftTimeProfitedAliases: Set<String> = setOf(
    ShiftTimeGoogleSheets.EVENING_PROFITED,
    "Profited",
    "Profité",
    "Con beneficio",
    "可享",
).map { it.lowercase() }.toSet()

private val sheetShiftTimeNonProfitedAliases: Set<String> = setOf(
    ShiftTimeGoogleSheets.EVENING_NON_PROFITED,
    "Not profited",
    "Pas profité",
    "Sin beneficio",
    "不可享",
).map { it.lowercase() }.toSet()

fun parseShiftTimeFromGoogleSheets(cellValue: String): ShiftTime {
    val s = cellValue.trim()
    if (s.isEmpty()) return ShiftTime.BEFORE_MIDNIGHT
    if (s.equals(ShiftTimeGoogleSheets.EVENING_PROFITED, ignoreCase = true)) return ShiftTime.BEFORE_MIDNIGHT
    if (s.equals(ShiftTimeGoogleSheets.EVENING_NON_PROFITED, ignoreCase = true)) return ShiftTime.AFTER_MIDNIGHT
    val lower = s.lowercase()
    if (lower in sheetShiftTimeProfitedAliases) return ShiftTime.BEFORE_MIDNIGHT
    if (lower in sheetShiftTimeNonProfitedAliases) return ShiftTime.AFTER_MIDNIGHT
    val normalized = normalizedShiftTimeToken(s)
    when (normalized) {
        "BEFORE_MIDNIGHT" -> return ShiftTime.BEFORE_MIDNIGHT
        "AFTER_MIDNIGHT" -> return ShiftTime.AFTER_MIDNIGHT
    }
    shiftTimeFromLegacyVenueLikeCell(normalized)?.let { return it }
    return try {
        ShiftTime.valueOf(normalized)
    } catch (_: IllegalArgumentException) {
        ShiftTime.BEFORE_MIDNIGHT
    }
}

/** True when the sheet cell should be rewritten to [toGoogleSheetsShiftTimeValue] (legacy enum-style labels). */
fun shouldMigrateShiftTimeSheetCell(raw: String): Boolean {
    val s = raw.trim()
    if (s.isEmpty()) return false
    if (s.equals(ShiftTimeGoogleSheets.EVENING_PROFITED, ignoreCase = true)) return false
    if (s.equals(ShiftTimeGoogleSheets.EVENING_NON_PROFITED, ignoreCase = true)) return false
    val normalized = normalizedShiftTimeToken(s)
    if (normalized == "BEFORE_MIDNIGHT" || normalized == "AFTER_MIDNIGHT") return true
    // Rewrite venue tokens mistakenly stored in the shift column to canonical sheet labels.
    return shiftTimeFromLegacyVenueLikeCell(normalized) != null
}

enum class BenefitSystemType {
    STELLAR,    // Uses the existing stellar benefits system
    MANUAL      // Uses manual rewards configuration
}

data class ManualRewards(
    val durationDays: Int = 1,           // Duration of benefits in days
    val freeDrinks: Int = 0,             // How many drinks for free
    val barDiscountPercentage: Int = 0,  // Percentage of reduction at the bar
    val freeEntry: Boolean = false,      // Free entry or not
    val invites: Int = 0,                // How many invites
    val otherNotes: String = "",         // Other notes
    /** Future event self-entries (each swipe at the door consumes one); only for MANUAL shift types. */
    val futureSingleUseEntries: Int = 0,
    /** How many friends/invites the holder gets with each future entry; only meaningful when futureSingleUseEntries > 0. */
    val futureSingleUseEntryInvites: Int = 1,
    val accountCreditChf: Double = 0.0
) 

// Type converters for Room database
class Converters {
    @TypeConverter
    fun fromVolunteerRank(rank: VolunteerRank?): String? = rank?.name

    @TypeConverter
    fun toVolunteerRank(rank: String?): VolunteerRank? = rank?.let { VolunteerRank.valueOf(it) }

    @TypeConverter
    fun fromJobType(jobType: JobType): String = jobType.name

    @TypeConverter
    fun toJobType(jobType: String): JobType = JobType.valueOf(jobType)

    @TypeConverter
    fun fromShiftTime(shiftTime: ShiftTime): String = shiftTime.name

    @TypeConverter
    fun toShiftTime(shiftTime: String): ShiftTime = ShiftTime.valueOf(shiftTime)

    @TypeConverter
    fun fromBenefitSystemType(benefitSystemType: BenefitSystemType): String = benefitSystemType.name

    @TypeConverter
    fun toBenefitSystemType(benefitSystemType: String): BenefitSystemType = BenefitSystemType.valueOf(benefitSystemType)

    @TypeConverter
    fun fromNovaJobType(novaJobType: NovaJobType): String = novaJobType.name

    @TypeConverter
    fun toNovaJobType(novaJobType: String): NovaJobType = try {
        NovaJobType.valueOf(novaJobType)
    } catch (_: Exception) {
        NovaJobType.DEFAULT_SHIFT
    }

    @TypeConverter
    fun fromGender(gender: Gender?): String? = gender?.name

    @TypeConverter
    fun toGender(gender: String?): Gender? = gender?.let { Gender.valueOf(it) }

    @TypeConverter
    fun fromManualRewards(manualRewards: ManualRewards?): String? {
        return manualRewards?.let {
            "${it.durationDays}|${it.freeDrinks}|${it.barDiscountPercentage}|${it.freeEntry}|${it.invites}|${it.otherNotes}|${it.futureSingleUseEntries}|${it.futureSingleUseEntryInvites}|${it.accountCreditChf}"
        }
    }

    @TypeConverter
    fun toManualRewards(manualRewards: String?): ManualRewards? {
        return manualRewards?.let { data ->
            val parts = data.split("|")
            when {
                parts.size >= 9 -> ManualRewards(
                    durationDays = parts[0].toIntOrNull() ?: 1,
                    freeDrinks = parts[1].toIntOrNull() ?: 0,
                    barDiscountPercentage = parts[2].toIntOrNull() ?: 0,
                    freeEntry = parts[3].toBooleanStrictOrNull() ?: false,
                    invites = parts[4].toIntOrNull() ?: 0,
                    otherNotes = parts[5],
                    futureSingleUseEntries = parts[6].toIntOrNull() ?: 0,
                    futureSingleUseEntryInvites = parts[7].toIntOrNull() ?: 1,
                    accountCreditChf = parts[8].toDoubleOrNull() ?: 0.0
                )
                parts.size >= 8 -> ManualRewards(
                    durationDays = parts[0].toIntOrNull() ?: 1,
                    freeDrinks = parts[1].toIntOrNull() ?: 0,
                    barDiscountPercentage = parts[2].toIntOrNull() ?: 0,
                    freeEntry = parts[3].toBooleanStrictOrNull() ?: false,
                    invites = parts[4].toIntOrNull() ?: 0,
                    otherNotes = parts[5],
                    futureSingleUseEntries = parts[6].toIntOrNull() ?: 0,
                    futureSingleUseEntryInvites = parts[7].toIntOrNull() ?: 1
                )
                parts.size >= 7 -> ManualRewards(
                    durationDays = parts[0].toIntOrNull() ?: 1,
                    freeDrinks = parts[1].toIntOrNull() ?: 0,
                    barDiscountPercentage = parts[2].toIntOrNull() ?: 0,
                    freeEntry = parts[3].toBooleanStrictOrNull() ?: false,
                    invites = parts[4].toIntOrNull() ?: 0,
                    otherNotes = parts[5],
                    futureSingleUseEntries = parts[6].toIntOrNull() ?: 0,
                    futureSingleUseEntryInvites = 1
                )
                parts.size == 6 -> ManualRewards(
                    durationDays = parts[0].toIntOrNull() ?: 1,
                    freeDrinks = parts[1].toIntOrNull() ?: 0,
                    barDiscountPercentage = parts[2].toIntOrNull() ?: 0,
                    freeEntry = parts[3].toBooleanStrictOrNull() ?: false,
                    invites = parts[4].toIntOrNull() ?: 0,
                    otherNotes = parts[5],
                    futureSingleUseEntries = 0,
                    futureSingleUseEntryInvites = 1
                )
                else -> null
            }
        }
    }

    @TypeConverter
    fun fromAccountHolderType(type: AccountHolderType): String = type.name

    @TypeConverter
    fun toAccountHolderType(type: String): AccountHolderType = AccountHolderType.valueOf(type)

    @TypeConverter
    fun fromAccountTransferType(type: AccountTransferType): String = type.name

    @TypeConverter
    fun toAccountTransferType(type: String): AccountTransferType = AccountTransferType.valueOf(type)

    @TypeConverter
    fun fromAccountTransferSyncState(state: AccountTransferSyncState): String = state.name

    @TypeConverter
    fun toAccountTransferSyncState(state: String): AccountTransferSyncState =
        runCatching { AccountTransferSyncState.valueOf(state) }.getOrDefault(AccountTransferSyncState.CONFIRMED)
}

object BenefitCalculator {
    private fun hasActiveNonVoucherPerks(benefit: Benefit): Boolean {
        return benefit.freeEntry ||
            benefit.friendInvitation ||
            benefit.inviteCount > 0 ||
            benefit.drinkTokens > 0 ||
            benefit.barDiscount > 0 ||
            benefit.extraordinaryBenefits
    }

    class CalculationContext(
        jobTypeConfigs: List<JobTypeConfig>,
        val currentTime: Long = System.currentTimeMillis(),
        val offsetHours: Int = 0
    ) {
        val shiftJobTypeNames: Set<String> = jobTypeConfigs
            .filter { it.isShiftJob && it.isActive }
            .map { it.name }
            .toSet()

        val orionJobTypeNames: Set<String> = jobTypeConfigs
            .filter { it.isOrionJob && it.isActive }
            .map { it.name }
            .toSet()

        val manualRewardJobTypes: Map<String, JobTypeConfig> = jobTypeConfigs
            .filter { it.benefitSystemType == BenefitSystemType.MANUAL && it.manualRewards != null }
            .associateBy { it.name }

        val jobTypeConfigsByName: Map<String, JobTypeConfig> = jobTypeConfigs.associateBy { it.name }

        /** Shift job names whose NovaJobType is MEETING — used for Orion exclusion logic. */
        val meetingJobTypeNames: Set<String> = jobTypeConfigs
            .filter { it.isShiftJob && it.isActive && it.novaJobType == NovaJobType.MEETING }
            .map { it.name }
            .toSet()

        val monthStart: Long = com.eventmanager.app.data.utils.DateTimeUtils.getStartOfMonthWithOffset(currentTime, offsetHours)
        val monthEnd: Long = com.eventmanager.app.data.utils.DateTimeUtils.getEndOfMonthWithOffset(currentTime, offsetHours) + 1

        private val calendar = java.util.Calendar.getInstance().apply { timeInMillis = currentTime }
        val currentMonth: Int = calendar.get(java.util.Calendar.MONTH)
        val currentYear: Int = calendar.get(java.util.Calendar.YEAR)
    }

    fun calculateVolunteerBenefitStatus(
        volunteer: Volunteer,
        jobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        currentTime: Long = System.currentTimeMillis(),
        offsetHours: Int = 0
    ): VolunteerBenefitStatus {
        val volunteerJobs = jobs.filter { it.volunteerId == volunteer.id }
        return calculateVolunteerBenefitStatusFromVolunteerJobs(
            volunteer, volunteerJobs, jobTypeConfigs, currentTime, offsetHours
        )
    }

    fun calculateVolunteerBenefitStatusFromVolunteerJobs(
        volunteer: Volunteer,
        volunteerJobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        currentTime: Long = System.currentTimeMillis(),
        offsetHours: Int = 0
    ): VolunteerBenefitStatus {
        val ctx = CalculationContext(jobTypeConfigs, currentTime, offsetHours)
        return calculateWithContext(volunteer, volunteerJobs, ctx)
    }

    /**
     * Primary display rank per volunteer for the Google Sheets "Rank" column.
     * Matches in-app [calculateVolunteerBenefitStatus] / benefits UI (job-driven), not [Volunteer.currentRank] alone.
     */
    fun volunteerPrimaryRanksForSheetUpload(
        volunteers: List<Volunteer>,
        jobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        currentTime: Long = System.currentTimeMillis(),
        offsetHours: Int = 0
    ): Map<String, VolunteerRank?> {
        if (volunteers.isEmpty()) return emptyMap()
        val ctx = CalculationContext(jobTypeConfigs, currentTime, offsetHours)
        val jobsByVolunteerId = jobs.groupBy { it.volunteerId }
        return volunteers.associate { volunteer ->
            val volunteerJobs = jobsByVolunteerId[volunteer.id] ?: emptyList()
            volunteer.id to calculateWithContext(volunteer, volunteerJobs, ctx).rank
        }
    }

    /**
     * True while the volunteer is in the Orion mandate window (from first Orion job date, 1 year),
     * used to suppress Nova **meeting** shift perks and their contribution to the monthly Galaxie count.
     */
    fun isVolunteerOrionActive(
        volunteerJobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        currentTime: Long = System.currentTimeMillis(),
        offsetHours: Int = 0
    ): Boolean {
        val ctx = CalculationContext(jobTypeConfigs, currentTime, offsetHours)
        val orionJobs = volunteerJobs
            .filter { ctx.orionJobTypeNames.contains(it.jobTypeName) }
            .sortedByDescending { it.date }
        return isVolunteerOrionOptimized(orionJobs, ctx)
    }

    fun calculateWithContext(
        volunteer: Volunteer,
        volunteerJobs: List<Job>,
        ctx: CalculationContext
    ): VolunteerBenefitStatus {
        val lastJobDate = volunteerJobs.maxOfOrNull { it.date }
        val manualRewardsBenefit = calculateManualRewardsBenefitOptimized(volunteerJobs, ctx)

        val allApplicableBenefits = mutableListOf<Benefit>()
        var primaryRank: VolunteerRank? = null

        val orionJobs = volunteerJobs
            .filter { ctx.orionJobTypeNames.contains(it.jobTypeName) }
            .sortedByDescending { it.date }

        val isOrion = isVolunteerOrionOptimized(orionJobs, ctx)
        val isVeteran = isVolunteerVeteranOptimized(orionJobs, ctx)

        // VETERAN
        if (isVeteran) {
            val benefit = calculateBenefitsForRank(VolunteerRank.VETERAN, volunteerJobs, orionJobs, ctx)
            if (benefit.isActive) {
                allApplicableBenefits.add(benefit)
                if (hasActiveNonVoucherPerks(benefit)) primaryRank = VolunteerRank.VETERAN
            }
        }

        // ORION
        if (isOrion) {
            val benefit = calculateBenefitsForRank(VolunteerRank.ORION, volunteerJobs, orionJobs, ctx)
            if (benefit.isActive) {
                allApplicableBenefits.add(benefit)
                if (primaryRank != VolunteerRank.VETERAN && hasActiveNonVoucherPerks(benefit)) primaryRank = VolunteerRank.ORION
            }
        }

        // GALAXIE: 3+ contributions this month (shifts + meetings, but meetings excluded for Orion)
        val monthlyContributions = getMonthlyContributionCount(volunteerJobs, ctx, excludeMeetingsForOrion = isOrion)
        if (monthlyContributions >= 3) {
            val benefit = calculateBenefitsForRank(VolunteerRank.GALAXIE, volunteerJobs, orionJobs, ctx)
            if (benefit.isActive) {
                allApplicableBenefits.add(benefit)
                if (primaryRank == null && hasActiveNonVoucherPerks(benefit)) primaryRank = VolunteerRank.GALAXIE
            }
        }

        // NOVA: all shift jobs (all NovaJobType variants) contribute to NOVA
        val hasAnyShiftJobEver = volunteerJobs.any { ctx.shiftJobTypeNames.contains(it.jobTypeName) }
        if (hasAnyShiftJobEver) {
            val benefit = calculateNovaBenefit(volunteerJobs, ctx, isOrion)
            if (benefit.isActive || (benefit.futureEventEntriesRemaining ?: 0) > 0) {
                allApplicableBenefits.add(benefit)
                if (primaryRank == null && hasActiveNonVoucherPerks(benefit)) primaryRank = VolunteerRank.NOVA
            }
        }

        // Manual rewards
        if (manualRewardsBenefit != null &&
            (manualRewardsBenefit.isActive || (manualRewardsBenefit.futureEventEntriesRemaining ?: 0) > 0)
        ) {
            allApplicableBenefits.add(0, manualRewardsBenefit)
        }
        if (primaryRank == null && manualRewardsBenefit != null && manualRewardsBenefit.isActive) {
            primaryRank = VolunteerRank.SPECIAL
        }

        val aggregatedBenefit = if (allApplicableBenefits.isNotEmpty()) {
            aggregateBenefits(allApplicableBenefits)
        } else {
            Benefit(
                rank = null, description = "No benefits - no rank earned",
                freeEntry = false, friendInvitation = false, inviteCount = 0,
                drinkTokens = 0, barDiscount = 0, guestListAccess = false,
                extraordinaryBenefits = false, validUntil = null, isActive = false,
                futureEventEntriesRemaining = null
            )
        }

        return VolunteerBenefitStatus(
            volunteerId = volunteer.id,
            rank = primaryRank,
            benefits = aggregatedBenefit,
            activeBenefits = allApplicableBenefits,
            lastJobDate = lastJobDate,
            monthlyShifts = monthlyContributions,
            isEligibleForGalaxie = monthlyContributions >= 3,
            isEligibleForEtoile = false,
            isEligibleForNova = hasAnyShiftJobEver
        )
    }

    // ========== NOVA BENEFIT (unified, replaces old NOVA + ETOILE split) ==========

    /**
     * Builds the aggregated NOVA benefit from all shift-type jobs.
     * Same-night perks apply only on each job's event day (start→end of venue day with offset), not before.
     * Future free entries are pooled only after that job's event day has started.
     * Orion members do not receive meeting benefits.
     */
    private fun calculateNovaBenefit(
        jobs: List<Job>,
        ctx: CalculationContext,
        isOrion: Boolean
    ): Benefit {
        val shiftJobs = jobs.filter { ctx.shiftJobTypeNames.contains(it.jobTypeName) }
        if (shiftJobs.isEmpty()) return emptyBenefit()

        // Compute same-night perks from the most recent qualifying shift still active today
        var sameNightActive = false
        var sameNightDrinks = 0
        var sameNightBarDiscount = 0
        var sameNightFreeEntry = false
        var sameNightFriend = false
        var sameNightValidUntil: Long? = null

        // Track drinks from non-event jobs (meetings, graphic-designer-association) awarded today
        var offEventDrinks = 0

        val sortedRecent = shiftJobs.sortedByDescending { it.date }
        for (job in sortedRecent) {
            val config = ctx.jobTypeConfigsByName[job.jobTypeName] ?: continue
            val njt = config.novaJobType

            if (isOrion && njt == NovaJobType.MEETING) continue

            val startOfEventDay = com.eventmanager.app.data.utils.DateTimeUtils
                .getStartOfDayWithOffset(job.date, ctx.offsetHours)
            val endOfEventDay = com.eventmanager.app.data.utils.DateTimeUtils
                .getEndOfDayWithOffset(job.date, ctx.offsetHours).timeInMillis
            // Same-night / same-day perks only on the job's event day, not before or after
            if (ctx.currentTime < startOfEventDay || ctx.currentTime > endOfEventDay) continue

            when (njt) {
                NovaJobType.DEFAULT_SHIFT -> {
                    if (!sameNightActive) {
                        sameNightActive = true
                        sameNightValidUntil = endOfEventDay
                        sameNightFreeEntry = true
                        sameNightFriend = true
                        sameNightBarDiscount = 50
                        sameNightDrinks = 2
                    }
                }
                NovaJobType.PHOTOGRAPHER_VIDEOGRAPHER,
                NovaJobType.GRAPHIC_DESIGNER_EVENT -> {
                    if (!sameNightActive) {
                        sameNightActive = true
                        sameNightValidUntil = endOfEventDay
                        sameNightFreeEntry = true
                        sameNightFriend = true
                        sameNightBarDiscount = 50
                        sameNightDrinks = 2
                    }
                }
                NovaJobType.MEETING -> {
                    offEventDrinks += 1
                    if (sameNightValidUntil == null) sameNightValidUntil = endOfEventDay
                }
                NovaJobType.GRAPHIC_DESIGNER_ASSOCIATION -> {
                    offEventDrinks += 4
                    if (sameNightValidUntil == null) sameNightValidUntil = endOfEventDay
                }
            }
        }

        val totalDrinks = (if (sameNightActive) sameNightDrinks else 0) + offEventDrinks
        val hasAnyActiveToday = sameNightActive || offEventDrinks > 0

        val futurePool = shiftJobs.sumOf { job ->
            val config = ctx.jobTypeConfigsByName[job.jobTypeName]
            val r = effectiveBenefitFutureEntriesRemaining(
                job, config, ctx.currentTime, ctx.offsetHours,
                meetingNovaBenefitsExcludedForOrion = isOrion
            )
            if (r <= 0) return@sumOf 0
            r
        }

        val descParts = mutableListOf<String>()
        if (sameNightActive) {
            if (sameNightFreeEntry) descParts.add("Free entry + 1 friend (same night)")
            if (sameNightBarDiscount > 0) descParts.add("$sameNightBarDiscount% bar (same night)")
            if (sameNightDrinks > 0) descParts.add("$sameNightDrinks drinks (same night)")
        }
        if (offEventDrinks > 0) descParts.add("$offEventDrinks drinks (off-event)")
        if (futurePool > 0) descParts.add("$futurePool future free entries (+1 friend each)")

        return Benefit(
            rank = VolunteerRank.NOVA,
            description = if (descParts.isNotEmpty()) descParts.joinToString("; ") else "Nova shift benefits",
            freeEntry = sameNightActive && sameNightFreeEntry,
            friendInvitation = sameNightActive && sameNightFriend,
            inviteCount = if (sameNightActive && sameNightFriend) 1 else 0,
            drinkTokens = totalDrinks,
            barDiscount = if (sameNightActive) sameNightBarDiscount else 0,
            // Guest list = venue entry / invites / future entry pool — not off-event drink-only perks (meetings, etc.)
            guestListAccess = sameNightActive || futurePool > 0,
            validUntil = sameNightValidUntil,
            isActive = hasAnyActiveToday,
            futureEventEntriesRemaining = futurePool.takeIf { it > 0 },
            futureEventEntryInvites = if (futurePool > 0) 1 else null
        )
    }

    // ========== HELPER METHODS ==========

    /**
     * Counts monthly contributions (shifts + meetings).
     * When [excludeMeetingsForOrion] is true, meetings are excluded from the count
     * (Orion members don't count meetings toward the 3-shift Galaxie bonus).
     */
    private fun getMonthlyContributionCount(jobs: List<Job>, ctx: CalculationContext, excludeMeetingsForOrion: Boolean): Int {
        return jobs.count { job ->
            val jobDayStart = com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(job.date, ctx.offsetHours)
            job.date >= ctx.monthStart && job.date < ctx.monthEnd &&
                ctx.currentTime >= jobDayStart &&
                ctx.shiftJobTypeNames.contains(job.jobTypeName) &&
                !(excludeMeetingsForOrion && ctx.meetingJobTypeNames.contains(job.jobTypeName))
        }
    }

    private fun isVolunteerVeteranOptimized(orionJobs: List<Job>, ctx: CalculationContext): Boolean {
        if (orionJobs.isNotEmpty()) {
            val mandateStart = com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(
                orionJobs.first().date, ctx.offsetHours
            )
            val oneYear = mandateStart + (365L * 24 * 60 * 60 * 1000)
            val twoYears = mandateStart + (2L * 365L * 24 * 60 * 60 * 1000)
            return ctx.currentTime >= oneYear && ctx.currentTime < twoYears
        }
        return false
    }

    private fun isVolunteerOrionOptimized(orionJobs: List<Job>, ctx: CalculationContext): Boolean {
        if (orionJobs.isNotEmpty()) {
            val mandateStart = com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(
                orionJobs.first().date, ctx.offsetHours
            )
            val oneYear = mandateStart + (365L * 24 * 60 * 60 * 1000)
            return ctx.currentTime >= mandateStart && ctx.currentTime < oneYear
        }
        return false
    }

    private fun calculateManualRewardsBenefitOptimized(jobs: List<Job>, ctx: CalculationContext): Benefit? {
        val manualRewardJobs = jobs.filter { ctx.manualRewardJobTypes.containsKey(it.jobTypeName) }
        if (manualRewardJobs.isEmpty()) return null

        // Duration-based perks: use the most recent manual reward job whose duration is still active.
        var durationActive = false
        var validUntil: Long? = null
        var activeRewards: ManualRewards? = null
        for (job in manualRewardJobs.sortedByDescending { it.date }) {
            val config = ctx.manualRewardJobTypes[job.jobTypeName] ?: continue
            val rewards = config.manualRewards ?: continue
            val periodStart = com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(
                job.date, ctx.offsetHours
            )
            val periodEnd = periodStart + (rewards.durationDays * 24L * 60 * 60 * 1000)
            if (ctx.currentTime >= periodStart && ctx.currentTime <= periodEnd) {
                durationActive = true
                validUntil = periodEnd
                activeRewards = rewards
                break
            }
        }

        // Pool future single-use entries from ALL manual reward jobs (not just the most recent).
        var totalFutureRemaining = 0
        var totalFutureCap = 0
        for (job in manualRewardJobs) {
            val config = ctx.manualRewardJobTypes[job.jobTypeName] ?: continue
            val rewards = config.manualRewards ?: continue
            val cap = rewards.futureSingleUseEntries
            if (cap <= 0) continue
            val rem = effectiveBenefitFutureEntriesRemaining(job, config, ctx.currentTime, ctx.offsetHours)
                .coerceIn(0, cap)
            totalFutureRemaining += rem
            totalFutureCap += cap
        }
        val hasFutureEntries = totalFutureRemaining > 0

        if (!durationActive && !hasFutureEntries) return null

        // Build description
        val descriptionParts = mutableListOf<String>()
        if (durationActive && activeRewards != null) {
            if (activeRewards.freeEntry) descriptionParts.add("Free entry")
            if (activeRewards.invites > 0) descriptionParts.add("${activeRewards.invites} invites")
            if (activeRewards.freeDrinks > 0) descriptionParts.add("${activeRewards.freeDrinks} free drinks")
            if (activeRewards.barDiscountPercentage > 0) descriptionParts.add("${activeRewards.barDiscountPercentage}% bar discount")
            if (activeRewards.otherNotes.isNotEmpty()) descriptionParts.add(activeRewards.otherNotes)
        }
        if (totalFutureCap > 0) {
            descriptionParts.add("$totalFutureRemaining / $totalFutureCap future event entries (single use)")
        }

        val durationDays = activeRewards?.durationDays
            ?: manualRewardJobs.mapNotNull { ctx.manualRewardJobTypes[it.jobTypeName]?.manualRewards?.durationDays }.maxOrNull()
            ?: 1
        val description = when {
            !durationActive && hasFutureEntries ->
                "Manual rewards: $totalFutureRemaining future event entries remain (reward duration ended; entries still redeemable)"
            descriptionParts.isNotEmpty() ->
                "Manual rewards: ${descriptionParts.joinToString(", ")} ($durationDays days)"
            else -> "Manual rewards ($durationDays days)"
        }

        val guestListWhileDuration = (activeRewards?.freeEntry == true) || (activeRewards?.invites ?: 0) > 0 || hasFutureEntries
        val effectiveGuestListAccess = if (durationActive) guestListWhileDuration else hasFutureEntries

        return Benefit(
            rank = if (durationActive) VolunteerRank.SPECIAL else null,
            description = description,
            freeEntry = durationActive && (activeRewards?.freeEntry == true),
            friendInvitation = durationActive && (activeRewards?.invites ?: 0) > 0,
            inviteCount = if (durationActive) (activeRewards?.invites ?: 0) else 0,
            drinkTokens = if (durationActive) (activeRewards?.freeDrinks ?: 0) else 0,
            barDiscount = if (durationActive) (activeRewards?.barDiscountPercentage ?: 0) else 0,
            guestListAccess = effectiveGuestListAccess,
            extraordinaryBenefits = false,
            validUntil = if (durationActive) validUntil else null,
            isActive = durationActive,
            futureEventEntriesRemaining = if (totalFutureCap > 0) totalFutureRemaining else null,
            futureEventEntryInvites = null
        )
    }

    private fun calculateBenefitsForRank(
        rank: VolunteerRank?,
        @Suppress("UNUSED_PARAMETER") jobs: List<Job>,
        orionJobs: List<Job>,
        ctx: CalculationContext
    ): Benefit {
        return when (rank) {
            VolunteerRank.GALAXIE -> {
                val nextMonthCalendar = java.util.Calendar.getInstance()
                nextMonthCalendar.set(ctx.currentYear, ctx.currentMonth + 1, 1, 0, 0, 0)
                nextMonthCalendar.set(java.util.Calendar.MILLISECOND, 0)
                val validUntil = nextMonthCalendar.timeInMillis
                Benefit(
                    rank = rank,
                    description = "Free entry + 50% bar at all events this month + 1 bonus drink",
                    freeEntry = true,
                    friendInvitation = false,
                    inviteCount = 0,
                    drinkTokens = 1,
                    barDiscount = 50,
                    guestListAccess = true,
                    validUntil = validUntil,
                    isActive = ctx.currentTime < validUntil
                )
            }

            VolunteerRank.ORION -> {
                val validUntil = if (orionJobs.isNotEmpty()) {
                    com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(
                        orionJobs.first().date, ctx.offsetHours
                    ) + (365L * 24 * 60 * 60 * 1000)
                } else {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(ctx.currentYear + 1, ctx.currentMonth, 1, 0, 0, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }
                Benefit(
                    rank = rank,
                    description = "Free entry to all events; 50% bar; 270 CHF internal wallet (1 year)",
                    freeEntry = true,
                    friendInvitation = true,
                    inviteCount = 1,
                    barDiscount = 50,
                    guestListAccess = true,
                    extraordinaryBenefits = true,
                    validUntil = validUntil,
                    isActive = ctx.currentTime < validUntil
                )
            }

            VolunteerRank.VETERAN -> {
                val validUntil = if (orionJobs.isNotEmpty()) {
                    com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(
                        orionJobs.first().date, ctx.offsetHours
                    ) + (2L * 365L * 24 * 60 * 60 * 1000)
                } else {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(ctx.currentYear + 1, ctx.currentMonth, 1, 0, 0, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }
                Benefit(
                    rank = rank,
                    description = "Free entry to all events; 50% bar discount (1 year after Orion)",
                    freeEntry = true,
                    friendInvitation = false,
                    inviteCount = 0,
                    barDiscount = 50,
                    guestListAccess = true,
                    extraordinaryBenefits = false,
                    validUntil = validUntil,
                    isActive = ctx.currentTime < validUntil
                )
            }

            else -> emptyBenefit()
        }
    }

    private fun emptyBenefit() = Benefit(
        rank = null, description = "No benefits",
        freeEntry = false, friendInvitation = false, inviteCount = 0,
        drinkTokens = 0, barDiscount = 0, guestListAccess = false,
        extraordinaryBenefits = false, validUntil = null, isActive = false
    )

    private fun aggregateBenefits(benefits: List<Benefit>): Benefit {
        val activeBenefits = benefits.filter { it.isActive }
        // Do not treat guestListAccess as structural: only count access that is live now or backed
        // by a redeemable future-entry pool (avoids drinks/bar-only rows inheriting a stale flag).
        val aggregatedGuestListAccess = benefits.any { b ->
            b.guestListAccess && (b.isActive || ((b.futureEventEntriesRemaining ?: 0) > 0))
        }
        val descriptionParts = mutableListOf<String>()
        if (activeBenefits.any { it.freeEntry }) descriptionParts.add("Free entry")
        if (activeBenefits.any { it.friendInvitation }) descriptionParts.add("Friend invitation")
        val totalInvites = activeBenefits.sumOf { it.inviteCount }
        if (totalInvites > 0) descriptionParts.add("$totalInvites invites")
        val totalDrinkTokens = activeBenefits.sumOf { it.drinkTokens }
        if (totalDrinkTokens > 0) descriptionParts.add("$totalDrinkTokens drink tokens")
        val maxDiscount = activeBenefits.maxOfOrNull { it.barDiscount } ?: 0
        if (maxDiscount > 0) descriptionParts.add("$maxDiscount% bar discount")
        if (aggregatedGuestListAccess) descriptionParts.add("Guest list access")
        if (activeBenefits.any { it.extraordinaryBenefits }) descriptionParts.add("Extraordinary benefits")

        val futureEntryTotals = benefits.mapNotNull { it.futureEventEntriesRemaining }
        val aggregatedFutureRem = if (futureEntryTotals.isNotEmpty()) futureEntryTotals.sum() else null
        aggregatedFutureRem?.takeIf { it > 0 }?.let { descriptionParts.add("$it future event entries") }

        return Benefit(
            rank = null,
            description = if (descriptionParts.isNotEmpty()) "Aggregated benefits: ${descriptionParts.joinToString(", ")}" else "Aggregated benefits",
            freeEntry = activeBenefits.any { it.freeEntry },
            friendInvitation = activeBenefits.any { it.friendInvitation },
            inviteCount = activeBenefits.sumOf { it.inviteCount },
            drinkTokens = activeBenefits.sumOf { it.drinkTokens },
            barDiscount = activeBenefits.maxOfOrNull { it.barDiscount } ?: 0,
            guestListAccess = aggregatedGuestListAccess,
            extraordinaryBenefits = activeBenefits.any { it.extraordinaryBenefits },
            validUntil = activeBenefits.mapNotNull { it.validUntil }.maxOrNull(),
            isActive = benefits.any { it.isActive } || benefits.any { (it.futureEventEntriesRemaining ?: 0) > 0 },
            futureEventEntriesRemaining = aggregatedFutureRem
        )
    }

    fun calculateTotalFreeDrinks(
        volunteers: List<Volunteer>,
        jobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        currentTime: Long = System.currentTimeMillis(),
        offsetHours: Int = 0
    ): Int {
        val ctx = CalculationContext(jobTypeConfigs, currentTime, offsetHours)
        val jobsByVolunteerId: Map<String, List<Job>> = jobs.groupBy { it.volunteerId }
        return volunteers.sumOf { volunteer ->
            val volunteerJobs = jobsByVolunteerId[volunteer.id] ?: emptyList()
            val benefitStatus = calculateWithContext(volunteer, volunteerJobs, ctx)
            if (benefitStatus.benefits.isActive) benefitStatus.benefits.drinkTokens else 0
        }
    }
}





