package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.models.ManualTemporaryGuestEntry
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.data.utils.DateTimeUtils

/** `orgs/{orgId}/guestListEdits/{id}`: one change asked from the portal (spec guest list access §5.3). */
data class GuestListEdit(
    val id: String,
    val uid: String,
    val grantId: String,
    val eventDateMillis: Long,
    val action: String,
    val guestKey: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val listName: String = "",
    val venueName: String = "",
    val accessIds: List<String> = emptyList(),
) {
    companion object {
        const val COLLECTION = "guestListEdits"
        const val ADD = "ADD"
        const val UPDATE = "UPDATE"
        const val DELETE = "DELETE"
        private val UID = Regex("^[A-Za-z0-9]{6,128}$")
        private val GUEST_KEY = Regex("^[0-9a-f]{16}$")

        private fun optionalName(v: Any?): String? =
            if ((v as? String).orEmpty().trim().isEmpty()) "" else PortalInputValidator.cleanName(v)

        /** Null when the document does not have the shape the rules allow. */
        fun parse(id: String, data: Map<String, Any?>): GuestListEdit? {
            val uid = (data["uid"] as? String)?.takeIf { UID.matches(it) } ?: return null
            val action = (data["action"] as? String)?.takeIf { it in setOf(ADD, UPDATE, DELETE) } ?: return null
            val date = (data["eventDateMillis"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            val key = (data["guestKey"] as? String).orEmpty()
            if (action != ADD && !GUEST_KEY.matches(key)) return null
            val first = optionalName(data["firstName"]) ?: return null
            val last = optionalName(data["lastName"]) ?: return null
            val access = (data["accessIds"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            if (access.size > 20) return null
            return GuestListEdit(
                id = id, uid = uid, grantId = (data["grantId"] as? String).orEmpty().trim(), eventDateMillis = date,
                action = action, guestKey = key, firstName = first, lastName = last,
                listName = (data["listName"] as? String).orEmpty().trim().take(80),
                venueName = (data["venueName"] as? String).orEmpty().trim().take(80),
                accessIds = access,
            )
        }
    }
}

/**
 * Applies a portal edit to the temporary guest list, after checking again that the account holds
 * an active grant for that date (and venue). Returns (status, reason) for the edit document.
 */
class GuestListEditProcessor(
    private val host: VolunteerPortalHost,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private fun day(millis: Long) = DateTimeUtils.getStartOfDayWithOffset(millis, 0)

    suspend fun apply(edit: GuestListEdit, volunteers: List<Volunteer>, guests: List<Guest>): Pair<String, String> {
        val now = clock()
        val grants = volunteers.filter { it.portalEnabled && it.portalUid == edit.uid }.map { it.guestListGrantsJson } +
            guests.filter { it.portalEnabled && it.portalUid == edit.uid }.map { it.guestListGrantsJson }
        val grant = grants.flatMap { GuestListGrants.decode(it) }
            .firstOrNull { it.id == edit.grantId && it.isActive(now) && day(it.eventDateMillis) == day(edit.eventDateMillis) }
            ?: return REJECTED to "NO_GRANT"
        return when (edit.action) {
            GuestListEdit.ADD -> add(edit, grant, guests)
            else -> change(edit, grant, guests, now)
        }
    }

    private fun accessesFor(venue: String, ids: List<String>): Set<String> {
        val allowed = host.venueAccessCatalog().filter { it.venueName.trim().equals(venue, ignoreCase = true) }.map { it.id }.toSet()
        return ids.filter { it in allowed }.toSet()
    }

    private suspend fun add(edit: GuestListEdit, grant: GuestListGrant, guests: List<Guest>): Pair<String, String> {
        val venue = grant.venueName.trim().ifEmpty { edit.venueName.trim() }
        val activeVenue = host.activeVenueNames().firstOrNull { it.trim().equals(venue, ignoreCase = true) }
        val name = "${edit.firstName} ${edit.lastName.uppercase()}".trim()
        if (activeVenue == null || edit.listName.isBlank() || name.isEmpty()) return REJECTED to "INVALID"
        val sameList = guests.firstOrNull {
            GuestListPortalProjectionBuilder.covers(grant, it) &&
                it.temporaryArtistName.trim().equals(edit.listName, ignoreCase = true) &&
                GuestListPortalProjectionBuilder.venueOf(it).equals(activeVenue, ignoreCase = true)
        }
        val ok = host.addTemporaryGuests(
            ManualTemporaryGuestBatch(
                eventDateMillis = sameList?.temporaryEventDate ?: grant.eventDateMillis,
                artistName = sameList?.temporaryArtistName ?: edit.listName,
                emergencyContactPhone = sameList?.temporaryContactPhone.orEmpty(),
                comments = "",
                guests = listOf(ManualTemporaryGuestEntry(name, accessesFor(activeVenue, edit.accessIds))),
                venueName = activeVenue,
                contactEmail = sameList?.temporaryContactEmail.orEmpty(),
            ),
        )
        return if (ok) DONE to "" else REJECTED to "SAVE_FAILED"
    }

    private suspend fun change(edit: GuestListEdit, grant: GuestListGrant, guests: List<Guest>, now: Long): Pair<String, String> {
        val target = guests.firstOrNull {
            GuestListPortalProjectionBuilder.covers(grant, it) && TemporaryGuestPortalQrDocument.guestKey(it) == edit.guestKey
        } ?: return REJECTED to "NOT_FOUND"
        if (target.temporaryEntryValidated) return REJECTED to "ENTRY_USED"
        val ok = if (edit.action == GuestListEdit.DELETE) {
            host.deleteTemporaryGuest(target)
        } else {
            host.saveGuest(
                target.copy(
                    name = edit.firstName.ifEmpty { target.name },
                    lastNameAbbreviation = edit.lastName.uppercase().ifEmpty { target.lastNameAbbreviation },
                    temporaryAccessIds = accessesFor(GuestListPortalProjectionBuilder.venueOf(target), edit.accessIds).joinToString(","),
                    lastModified = maxOf(now, target.lastModified + 1),
                ),
            )
        }
        return if (ok) DONE to "" else REJECTED to "SAVE_FAILED"
    }

    companion object {
        const val DONE = "DONE"
        const val REJECTED = "REJECTED"
    }
}
