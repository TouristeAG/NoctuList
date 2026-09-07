package com.eventmanager.app.ui.components

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer

internal fun String.normalizeScannerUid(): String =
    trim().replace(" ", "").replace(":", "").uppercase()

private fun ScannerMatch.hasAdminPrivileges(): Boolean = when (this) {
    is ScannerMatch.VolunteerMatch -> volunteer.isAdmin
    is ScannerMatch.GuestMatch -> guest.isAdmin
}

internal fun resolveNanoidScannerMatch(
    volunteers: List<Volunteer>,
    permanentGuests: List<Guest>,
    rawId: String
): ScannerMatch? {
    val trimmed = rawId.trim()
    if (trimmed.isEmpty()) return null
    val volMatches = volunteers.filter { it.id == trimmed }
    val guestMatches = permanentGuests.filter { it.nanoId == trimmed || it.id.toString() == trimmed }
    val all = volMatches.map { ScannerMatch.VolunteerMatch(it) } +
        guestMatches.map { ScannerMatch.GuestMatch(it) }
    if (all.isEmpty()) return null
    return all.firstOrNull { it.hasAdminPrivileges() } ?: all.first()
}

internal fun resolveNfcUidMatches(
    rawUid: String,
    volunteers: List<Volunteer>,
    permanentGuests: List<Guest>
): List<NfcUidMatchOption> {
    val uid = rawUid.normalizeScannerUid()
    if (uid.isBlank()) return emptyList()
    val volunteersByNfc = volunteers
        .filter { it.nfcCardUid.isNotBlank() }
        .groupBy { it.nfcCardUid.normalizeScannerUid() }
    val guestsByNfc = permanentGuests
        .filter { it.nfcCardUid.isNotBlank() }
        .groupBy { it.nfcCardUid.normalizeScannerUid() }
    return buildList {
        volunteersByNfc[uid].orEmpty().forEach { volunteer ->
            add(
                NfcUidMatchOption(
                    match = ScannerMatch.VolunteerMatch(volunteer),
                    title = volunteer.name,
                    subtitle = "${volunteer.lastNameAbbreviation} • ${volunteer.id}",
                    typeLabel = "volunteer"
                )
            )
        }
        guestsByNfc[uid].orEmpty().forEach { guest ->
            add(
                NfcUidMatchOption(
                    match = ScannerMatch.GuestMatch(guest),
                    title = guest.name,
                    subtitle = guest.email.ifBlank { guest.phoneNumber },
                    typeLabel = "guest"
                )
            )
        }
    }
}

internal fun resolveDesktopScannerPayload(
    raw: String,
    volunteers: List<Volunteer>,
    guests: List<Guest>,
    /** Firebase only: temporary guests join the search pool for their single-use entry. */
    includeTemporaryGuests: Boolean = false,
): Pair<ScannerMatch?, List<NfcUidMatchOption>> {
    val permanentGuests = guests.filter {
        !it.isVolunteerBenefit && (!it.isTemporaryGuest || includeTemporaryGuests)
    }
    val nfcMatches = resolveNfcUidMatches(raw, volunteers, permanentGuests)
    when {
        nfcMatches.size == 1 -> return nfcMatches.first().match to emptyList()
        nfcMatches.size > 1 -> return null to nfcMatches
    }
    resolveNanoidScannerMatch(volunteers, permanentGuests, raw)?.let { return it to emptyList() }
    return null to emptyList()
}
