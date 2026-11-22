package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormExpiry
import com.eventmanager.app.data.models.GuestFormOfferedAccessCodec
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.isPortalInvite
import com.eventmanager.app.data.models.listVenueLabel
import com.eventmanager.app.data.models.removeGuestKeyList
import com.eventmanager.app.data.models.statusValue
import com.eventmanager.app.data.models.temporaryBatchKey
import com.eventmanager.app.data.models.temporaryEntryValidated

/** One upcoming list of an address, with the invite template bound to it, if any. */
data class PortalInviteList(
    val eventDateMillis: Long,
    val venueName: String,
    val artistName: String,
    val members: List<Guest>,
    val template: GuestForm?,
)

/**
 * Per-event invites from the portal (spec 2026-11-21 §8): an invite is a multi-response guest form
 * bound to a portal account, so its answers follow the guest form review.
 */
object PortalInvites {

    /** Same key as [temporaryBatchKey], so accepted people join the list the template belongs to. */
    fun listKey(form: GuestForm): String =
        listOf(form.artistName.trim().lowercase(), form.eventDateMillis.toString(), com.eventmanager.app.data.models.listVenueKey(form.venueName))
            .joinToString("|")

    /** A new template, or [existing] with new limits (its id and creation time stay). */
    fun template(
        existing: GuestForm?,
        formId: String,
        contactEmail: String,
        portalUid: String,
        eventDateMillis: Long,
        venueName: String,
        artistName: String,
        maxGuests: Int,
        accessLimits: Map<String, Int>,
        accesses: List<VenueAccess>,
        emergencyPhone: String,
        now: Long,
        offsetHours: Int,
    ): GuestForm {
        val cap = maxGuests.coerceIn(1, GuestForm.MAX_GUESTS_LIMIT)
        val offered = accesses.filter { it.id in accessLimits }
        return (existing ?: GuestForm(formId = formId, createdAt = now)).copy(
            portalUid = portalUid.trim(),
            venueName = venueName.trim(),
            eventDateMillis = eventDateMillis,
            artistName = artistName.trim(),
            maxGuests = cap,
            offeredAccessIds = GuestFormOfferedAccessCodec.encode(offered, accessLimits, cap),
            allowMultipleResponses = true,
            expiryMode = GuestFormExpiry.MANUAL.name,
            expiresAtMillis = TemporaryGuestPortalProjectionBuilder.visibleUntil(eventDateMillis, offsetHours),
            askEmail = false,
            askPhone = false,
            prefillEmail = contactEmail.trim(),
            prefillPhone = emergencyPhone.trim(),
            status = GuestFormStatus.OPEN.name,
            lastModified = maxOf(now, (existing?.lastModified ?: 0L) + 1),
        )
    }

    /**
     * Whether accepting [people] from [response] keeps the event within its limits. Only accepted
     * guests count against it: other pending answers may still be refused, and counting them would
     * let two pending answers block each other. The portal already counts them before sending.
     */
    fun canAccept(
        template: GuestForm,
        response: GuestForm,
        batch: List<Guest>,
        responses: List<GuestForm>,
        people: List<Pair<String, Set<String>>>,
    ): Boolean {
        return !PortalInviteAllowance.of(template, batch, emptyList()).exceededBy(people)
    }

    /** Guests a REMOVE answer takes off the list, and those kept because their entry was used. */
    fun removalTargets(form: GuestForm, batch: List<Guest>): Pair<List<Guest>, List<Guest>> {
        val keys = form.removeGuestKeyList().toSet()
        val targets = batch.filter { TemporaryGuestPortalQrDocument.guestKey(it) in keys }
        return targets.partition { !it.temporaryEntryValidated }
    }

    /** Lists of [address] still visible at [now], with their templates; a template alone is a list too. */
    fun upcomingLists(guests: List<Guest>, templates: List<GuestForm>, address: String, now: Long, offsetHours: Int): List<PortalInviteList> {
        val key = address.trim().lowercase()
        if (key.isEmpty()) return emptyList()
        val members = guests
            .filter { it.isTemporaryGuest && it.temporaryEventDate != null && it.temporaryContactEmail.trim().lowercase() == key }
            .groupBy { it.temporaryBatchKey() }
        val own = templates.filter {
            it.isPortalInvite && it.parentFormId.isBlank() && it.prefillEmail.trim().lowercase() == key
        }
        val byKey = own.groupBy { listKey(it) }
        return (members.keys + byKey.keys).distinct().mapNotNull { k ->
            val group = members[k].orEmpty()
            val template = byKey[k].orEmpty().maxByOrNull { if (it.statusValue == GuestFormStatus.OPEN) 1 else 0 }
            val first = group.firstOrNull()
            val list = if (first != null) {
                // The list's own venue, as in temporaryBatchKey: a guest's venueName ("BOTH" for
                // admin lists) is not the list's, and keying a template on it splits the list in two.
                PortalInviteList(first.temporaryEventDate!!, listVenueLabel(first.temporaryVenueName), first.temporaryArtistName, group, template)
            } else {
                // A closed template alone is no list any more.
                template?.takeIf { it.statusValue == GuestFormStatus.OPEN }
                    ?.let { PortalInviteList(it.eventDateMillis, listVenueLabel(it.venueName), it.artistName, group, it) }
            }
            list?.takeIf { TemporaryGuestPortalProjectionBuilder.visibleUntil(it.eventDateMillis, offsetHours) > now }
        }.sortedWith(compareBy({ it.eventDateMillis }, { it.artistName.lowercase() }))
    }
}
