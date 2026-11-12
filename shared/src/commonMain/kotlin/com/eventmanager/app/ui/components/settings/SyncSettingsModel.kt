package com.eventmanager.app.ui.components.settings

import com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy
import kotlin.math.abs

/**
 * Options of the Sheets mirror frequency menu, with the minutes stored in settings.
 * [MANUAL] is stored as 0.
 */
enum class MirrorFrequency(val minutes: Int) {
    HOURLY(60),
    DAILY(1440),
    MANUAL(0),
    ;

    companion object
}

/**
 * Maps a stored interval to the option the menu shows. 0 or negative means [MirrorFrequency.MANUAL];
 * any positive value shows as the nearest timed option. Pure: callers write a new value only when
 * the user picks an option, so an odd stored value (for example 45) is never rewritten silently.
 */
fun MirrorFrequency.Companion.fromMinutes(m: Int): MirrorFrequency {
    if (m <= 0) return MirrorFrequency.MANUAL
    return listOf(MirrorFrequency.HOURLY, MirrorFrequency.DAILY).minBy { abs(it.minutes - m) }
}

/** Choices of the Sheets sync frequency menu, in minutes (same stored value as `getSyncInterval()`). */
val SHEETS_SYNC_MINUTES: List<Int> = listOf(1, 5, 15, 30, 60)

/**
 * The menu option shown for a stored Sheets sync interval: the nearest of [SHEETS_SYNC_MINUTES]
 * (the shorter one on a tie). Pure: the page writes only when the user picks an option, so an odd
 * stored value (for example 10) is never rewritten silently.
 */
fun nearestSheetsSyncMinutes(stored: Int): Int = SHEETS_SYNC_MINUTES.minBy { abs(it - stored) }

enum class SyncConnectionState { READY, NEEDS_SIGN_IN, NEEDS_CONFIG }

fun syncConnectionState(orgReady: Boolean, projectReady: Boolean, signedIn: Boolean): SyncConnectionState =
    when {
        !orgReady || !projectReady -> SyncConnectionState.NEEDS_CONFIG
        !signedIn -> SyncConnectionState.NEEDS_SIGN_IN
        else -> SyncConnectionState.READY
    }

/**
 * Whether the Équipe page offers role and removal actions on a member row: only to an org admin,
 * never on one's own row (e-mails compared trimmed and case-insensitively), and never on a member
 * without a known e-mail. The Firestore rules remain the real barrier.
 */
fun memberActionsAllowed(isOrgAdmin: Boolean, memberEmail: String?, selfEmail: String?): Boolean {
    if (!isOrgAdmin) return false
    val member = memberEmail?.trim()?.lowercase().orEmpty()
    if (member.isEmpty()) return false
    val self = selfEmail?.trim()?.lowercase().orEmpty()
    return member != self
}

/**
 * Whether the org's `metadata/config` already holds an invitation code: the hashed field
 * `bootstrapCodeHash` or the legacy `bootstrapCode`, non-empty (the same test as the Firestore
 * rules). When it does, a device without the code must not create one silently, because a new code
 * disables every QR already shared.
 */
fun orgConfigHasInvitationCode(config: Map<String, Any?>): Boolean =
    listOf("bootstrapCodeHash", "bootstrapCode").any { key ->
        (config[key] as? String)?.isNotBlank() == true
    }

/**
 * Team domains (the allowed e-mail domains of the team) offered as one-click suggestions for the
 * volunteer portal's Google sign-in: normalized, valid, without duplicates, and not already among
 * the verified [verifiedDomains]. A suggestion is only a shortcut: picking it runs the same Google
 * Workspace check as typing the domain.
 */
fun portalGoogleDomainSuggestions(teamDomains: List<String>, verifiedDomains: List<String>): List<String> {
    val verified = verifiedDomains.map { FirebaseEmailDomainPolicy.normalizeDomain(it) }.toSet()
    return teamDomains
        .map { FirebaseEmailDomainPolicy.normalizeDomain(it) }
        .filter { it.isNotBlank() && it.contains('.') && it !in verified }
        .distinct()
}

/** How long ago the last mirror export ran, in the unit the page shows. */
enum class ExportAgeUnit { NOW, MINUTES, HOURS, DAYS }

data class ExportAge(val unit: ExportAgeUnit, val count: Int)

/** Coarse "x ago" for the last mirror export: under a minute is [ExportAgeUnit.NOW], then minutes, hours, days. */
fun exportAge(lastExportAt: Long, nowMillis: Long): ExportAge {
    val minutes = ((nowMillis - lastExportAt).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes < 1 -> ExportAge(ExportAgeUnit.NOW, 0)
        minutes < 60 -> ExportAge(ExportAgeUnit.MINUTES, minutes)
        minutes < 1440 -> ExportAge(ExportAgeUnit.HOURS, minutes / 60)
        else -> ExportAge(ExportAgeUnit.DAYS, minutes / 1440)
    }
}
