package com.eventmanager.app.ui.components.settings

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
