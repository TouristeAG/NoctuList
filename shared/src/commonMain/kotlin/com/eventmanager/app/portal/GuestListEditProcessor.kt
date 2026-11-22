package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormRequestKind
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.models.ManualTemporaryGuestEntry
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.listVenueKey
import com.eventmanager.app.data.models.listVenueLabel
import com.eventmanager.app.data.models.requestKindValue
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
    /** ADD only: contact of a new list (an existing list keeps its own), and notes on the added guests. */
    val phone: String = "",
    val contactEmail: String = "",
    val notes: String = "",
    /** ACCEPT / REJECT: the pending request, as published on the guest list page. */
    val reviewKey: String = "",
    /** ACCEPT of an added request: the people as corrected on the portal. */
    val people: List<ManualTemporaryGuestEntry> = emptyList(),
) {
    companion object {
        const val COLLECTION = "guestListEdits"
        const val ADD = "ADD"
        const val UPDATE = "UPDATE"
        const val DELETE = "DELETE"
        const val ACCEPT = "ACCEPT"
        const val REJECT = "REJECT"
        private val REVIEWS = setOf(ACCEPT, REJECT)
        private val UID = Regex("^[A-Za-z0-9]{6,128}$")
        private val GUEST_KEY = Regex("^[0-9a-f]{16}$")

        const val MAX_NOTES = 500
        const val MAX_PEOPLE = 100
        const val MAX_ACCESS = 20

        private fun accessIds(v: Any?): List<String>? =
            (v as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .takeIf { it.size <= MAX_ACCESS }

        /** Null when one person has a name the rules refuse; a blank name is kept and dropped on accept. */
        private fun people(v: Any?): List<ManualTemporaryGuestEntry>? {
            val raw = (v as? List<*>).orEmpty()
            if (raw.size > MAX_PEOPLE) return null
            return raw.map { item ->
                val person = item as? Map<*, *> ?: return null
                val name = optionalName(person["name"]) ?: return null
                ManualTemporaryGuestEntry(name, (accessIds(person["accessIds"]) ?: return null).toSet())
            }
        }

        private fun blank(v: Any?): Boolean = (v as? String).orEmpty().trim().isEmpty()

        private fun optionalName(v: Any?): String? =
            if ((v as? String).orEmpty().trim().isEmpty()) "" else PortalInputValidator.cleanName(v)

        /** Null when the document does not have the shape the rules allow. */
        fun parse(id: String, data: Map<String, Any?>): GuestListEdit? {
            val uid = (data["uid"] as? String)?.takeIf { UID.matches(it) } ?: return null
            val action = (data["action"] as? String)?.takeIf { it in setOf(ADD, UPDATE, DELETE) + REVIEWS } ?: return null
            val date = (data["eventDateMillis"] as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
            val key = (data["guestKey"] as? String).orEmpty()
            if (action != ADD && action !in REVIEWS && !GUEST_KEY.matches(key)) return null
            val reviewKey = (data["reviewKey"] as? String).orEmpty()
            if (action in REVIEWS && !GUEST_KEY.matches(reviewKey)) return null
            val people = if (action in REVIEWS) people(data["people"]) ?: return null else emptyList()
            val first = optionalName(data["firstName"]) ?: return null
            val last = optionalName(data["lastName"]) ?: return null
            val access = accessIds(data["accessIds"]) ?: return null
            val phone = if (blank(data["phone"])) "" else PortalInputValidator.cleanPhone(data["phone"]) ?: return null
            val contactEmail = if (blank(data["contactEmail"])) "" else PortalInputValidator.cleanEmail(data["contactEmail"]) ?: return null
            val notes = (data["notes"] as? String).orEmpty().trim()
            if (notes.length > MAX_NOTES) return null
            return GuestListEdit(
                id = id, uid = uid, grantId = (data["grantId"] as? String).orEmpty().trim(), eventDateMillis = date,
                action = action, guestKey = key, firstName = first, lastName = last,
                listName = (data["listName"] as? String).orEmpty().trim().take(80),
                venueName = (data["venueName"] as? String).orEmpty().trim().take(80),
                accessIds = access, phone = phone, contactEmail = contactEmail, notes = notes,
                reviewKey = if (action in REVIEWS) reviewKey else "", people = people,
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

    suspend fun apply(
        edit: GuestListEdit,
        volunteers: List<Volunteer>,
        guests: List<Guest>,
        forms: List<GuestForm> = emptyList(),
    ): Pair<String, String> {
        val now = clock()
        val review = edit.action == GuestListEdit.ACCEPT || edit.action == GuestListEdit.REJECT
        if (review && !host.portalValidateEnabled()) return REJECTED to "DISABLED"
        // Each profile of the account with the grants it carries, and the name it goes by.
        val holders = volunteers.filter { it.portalEnabled && it.portalUid == edit.uid }
            .map { "${it.name} ${it.lastNameAbbreviation}".trim() to it.guestListGrantsJson } +
            guests.filter { it.portalEnabled && it.portalUid == edit.uid }
                .map { "${it.name} ${it.lastNameAbbreviation}".trim() to it.guestListGrantsJson }
        val (holderName, grant) = holders.firstNotNullOfOrNull { (name, json) ->
            GuestListGrants.decode(json)
                .firstOrNull { it.id == edit.grantId && it.isActive(now, host.dateOffsetHours()) && day(it.eventDateMillis) == day(edit.eventDateMillis) }
                ?.let { name to it }
        } ?: return REJECTED to "NO_GRANT"
        return when (edit.action) {
            GuestListEdit.ADD -> add(edit, grant, guests)
            GuestListEdit.ACCEPT, GuestListEdit.REJECT -> decide(edit, grant, forms, holderName)
            else -> change(edit, grant, guests, now)
        }
    }

    /** ACCEPT or REJECT of a pending guest request the grant covers (spec guest list review §6.2). */
    private suspend fun decide(edit: GuestListEdit, grant: GuestListGrant, forms: List<GuestForm>, holderName: String): Pair<String, String> {
        val form = GuestListReviews.covered(grant, forms, "").firstOrNull { GuestListReviews.reviewKey(it) == edit.reviewKey }
            ?: return REJECTED to "NOT_PENDING"
        val reviewedBy = "$REVIEWED_BY_PREFIX$holderName"
        if (edit.action == GuestListEdit.REJECT) {
            return if (host.rejectGuestFormFromPortal(form, reviewedBy)) DONE to "" else REJECTED to "SAVE_FAILED"
        }
        if (form.requestKindValue == GuestFormRequestKind.REMOVE) {
            return if (host.acceptGuestFormRemovalFromPortal(form, reviewedBy)) DONE to "" else REJECTED to "SAVE_FAILED"
        }
        val people = edit.people
            .map { ManualTemporaryGuestEntry(it.name.trim(), accessesFor(form.venueName, it.accessIds.toList())) }
            .filter { it.name.isNotEmpty() }
        if (people.isEmpty()) return REJECTED to "INVALID"
        val ok = host.acceptGuestFormFromPortal(form, people, edit.contactEmail, edit.phone, edit.notes, reviewedBy)
        return if (ok) DONE to "" else REJECTED to "OVER_LIMIT"
    }

    /** Accesses of [venue] among [ids]; a list without venue ("" or "BOTH") takes those of every venue. */
    private fun accessesFor(venue: String, ids: List<String>): Set<String> {
        val listVenue = listVenueLabel(venue)
        val allowed = host.venueAccessCatalog()
            .filter { listVenue.isEmpty() || it.venueName.trim().equals(listVenue, ignoreCase = true) }
            .map { it.id }.toSet()
        return ids.filter { it in allowed }.toSet()
    }

    private suspend fun add(edit: GuestListEdit, grant: GuestListGrant, guests: List<Guest>): Pair<String, String> {
        // No venue ("" or the "BOTH" the portal shows for admin lists) is a list for every venue.
        val venue = listVenueLabel(grant.venueName.trim().ifEmpty { edit.venueName.trim() })
        val activeVenue = if (venue.isEmpty()) "" else host.activeVenueNames().firstOrNull { it.trim().equals(venue, ignoreCase = true) }
        val name = "${edit.firstName} ${edit.lastName.uppercase()}".trim()
        if (activeVenue == null || edit.listName.isBlank() || name.isEmpty()) return REJECTED to "INVALID"
        val sameList = guests.firstOrNull {
            GuestListPortalProjectionBuilder.covers(grant, it) &&
                it.temporaryArtistName.trim().equals(edit.listName, ignoreCase = true) &&
                listVenueKey(it.temporaryVenueName) == listVenueKey(activeVenue)
        }
        val ok = host.addTemporaryGuests(
            ManualTemporaryGuestBatch(
                eventDateMillis = sameList?.temporaryEventDate ?: grant.eventDateMillis,
                artistName = sameList?.temporaryArtistName ?: edit.listName,
                // A new list takes the contact typed on the portal; an existing one keeps its own.
                emergencyContactPhone = if (sameList != null) sameList.temporaryContactPhone else edit.phone,
                comments = edit.notes,
                guests = listOf(ManualTemporaryGuestEntry(name, accessesFor(activeVenue, edit.accessIds))),
                venueName = sameList?.temporaryVenueName ?: activeVenue,
                contactEmail = if (sameList != null) sameList.temporaryContactEmail else edit.contactEmail,
                barDiscountPercent = sameList?.barDiscountPercent ?: 0,
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
        const val REVIEWED_BY_PREFIX = "Plateforme · "
    }
}
