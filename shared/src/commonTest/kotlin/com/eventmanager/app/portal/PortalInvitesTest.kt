package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormPerson
import com.eventmanager.app.data.models.GuestFormSubmission
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.offeredAccesses
import com.eventmanager.app.data.models.temporaryBatchKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalInvitesTest {
    private val event = 1_800_000_000_000L
    private val accesses = listOf(VenueAccess(id = "bs", venueName = "Club", name = "Backstage"))

    private fun member(nano: String, used: Long = 0L) = Guest(
        nanoId = nano, name = nano, invitations = 0, venueName = "Club", isTemporaryGuest = true,
        temporaryEventDate = event, temporaryVenueName = "Club", temporaryArtistName = "Friends",
        temporaryContactEmail = "lea@example.org", temporaryContactPhone = "+41 79 1",
        portalUid = "U1", portalEnabled = true, temporaryEntryValidatedAt = used,
    )

    private fun template(max: Int = 3) = PortalInvites.template(
        existing = null, formId = "TPL", contactEmail = "lea@example.org", portalUid = "U1",
        eventDateMillis = event, venueName = "Club", artistName = "Friends", maxGuests = max,
        accessLimits = mapOf("bs" to 1), accesses = accesses, emergencyPhone = "+41 79 1", now = 5L,
    )

    private fun add(id: String, vararg names: String) = GuestForm(
        formId = id, parentFormId = "TPL", portalUid = "U1", status = "PENDING_REVIEW", requestKind = "ADD",
        submissionJson = GuestFormSubmissionCodec.encode(GuestFormSubmission(people = names.map { GuestFormPerson(it) })),
    )

    @Test
    fun openPortalInvitesCreatesBoundTemplate() {
        val t = template()
        assertEquals("U1", t.portalUid)
        assertTrue(t.allowMultipleResponses)
        assertEquals("MANUAL", t.expiryMode)
        assertEquals(TemporaryGuestPortalProjectionBuilder.visibleUntil(event), t.expiresAtMillis)
        assertFalse(t.askEmail)
        assertFalse(t.askPhone)
        assertEquals("lea@example.org", t.prefillEmail)
        assertEquals("+41 79 1", t.prefillPhone)
        assertEquals("OPEN", t.status)
        assertEquals(listOf("bs" to 1), t.offeredAccesses().map { it.id to it.maxRequests })
        assertEquals(member("A").temporaryBatchKey(), PortalInvites.listKey(t))
        // Editing keeps the id and the creation time.
        val edited = PortalInvites.template(
            existing = t, formId = "ignored", contactEmail = "lea@example.org", portalUid = "U1",
            eventDateMillis = event, venueName = "Club", artistName = "Friends", maxGuests = 8,
            accessLimits = emptyMap(), accesses = accesses, emergencyPhone = "", now = 9L,
        )
        assertEquals("TPL", edited.formId)
        assertEquals(5L, edited.createdAt)
        assertEquals(8, edited.maxGuests)
        assertEquals("", edited.offeredAccessIds)
    }

    @Test
    fun secondPendingAddBlockedOverLimit() {
        val r1 = add("R1", "Cy", "Di")
        val r2 = add("R2", "Ed", "Flo")
        val batch = listOf(member("A"))
        // R1 fits (1 + 2 = 3), R2 on top of R1 does not.
        assertTrue(PortalInvites.canAccept(template(), r1, batch, listOf(r1, r2), r1.people()))
        val accepted = batch + listOf(member("Cy"), member("Di"))
        assertFalse(PortalInvites.canAccept(template(), r2, accepted, listOf(r2), r2.people()))
        assertTrue(PortalInvites.canAccept(template(max = 5), r2, accepted, listOf(r2), r2.people()))
    }

    @Test
    fun removalDeletesUnusedOnly() {
        val a = member("A")
        val used = member("B", used = 7L)
        val form = GuestForm(
            formId = "R3", parentFormId = "TPL", portalUid = "U1", requestKind = "REMOVE",
            removeGuestKeys = listOf(a, used).joinToString(",") { TemporaryGuestPortalQrDocument.guestKey(it) },
        )
        val (remove, kept) = PortalInvites.removalTargets(form, listOf(a, used, member("C")))
        assertEquals(listOf("A"), remove.map { it.nanoId })
        assertEquals(listOf("B"), kept.map { it.nanoId })
    }

    @Test
    fun emptyEventTemplateHasNoGuests() {
        val lists = PortalInvites.upcomingLists(
            guests = emptyList(), templates = listOf(template()), address = "LEA@example.org ", now = event,
        )
        val only = lists.single()
        assertEquals("Friends", only.artistName)
        assertTrue(only.members.isEmpty())
        assertEquals("TPL", only.template?.formId)
        assertNull(PortalInvites.upcomingLists(emptyList(), listOf(template()), "lea@example.org", TemporaryGuestPortalProjectionBuilder.visibleUntil(event)).firstOrNull())
    }

    @Test
    fun upcomingListsMatchTheAddressAndJoinTheirTemplate() {
        val lists = PortalInvites.upcomingLists(
            guests = listOf(member("A"), member("Z").copy(temporaryContactEmail = "other@example.org", temporaryArtistName = "Other")),
            templates = listOf(template()), address = "lea@example.org", now = event,
        )
        assertEquals(1, lists.size)
        assertEquals(listOf("A"), lists.single().members.map { it.nanoId })
        assertEquals("TPL", lists.single().template?.formId)
    }

    @Test
    fun aListWithoutVenueKeepsOneRowOnceInvitesAreOpen() {
        // Admin list with no venue: the guests carry the technical "BOTH" as their own venue.
        val guest = member("A").copy(venueName = "BOTH", temporaryVenueName = "")
        val before = PortalInvites.upcomingLists(listOf(guest), emptyList(), "lea@example.org", event).single()
        assertEquals("", before.venueName, "BOTH is no venue to show or to key a template on")
        val opened = PortalInvites.template(
            existing = null, formId = "TPL", contactEmail = "lea@example.org", portalUid = "U1",
            eventDateMillis = before.eventDateMillis, venueName = before.venueName, artistName = before.artistName,
            maxGuests = 3, accessLimits = emptyMap(), accesses = accesses, emergencyPhone = "", now = 5L,
        )
        val after = PortalInvites.upcomingLists(listOf(guest), listOf(opened), "lea@example.org", event)
        assertEquals(1, after.size)
        assertEquals("TPL", after.single().template?.formId)
    }

    @Test
    fun aTemplateKeyedOnBothJoinsTheListWithoutVenue() {
        // Data written before the fix: the template and the guest it added carry "BOTH".
        val admin = member("A").copy(venueName = "BOTH", temporaryVenueName = "")
        val added = member("B").copy(venueName = "BOTH", temporaryVenueName = "BOTH")
        val old = template().copy(venueName = "BOTH")
        val lists = PortalInvites.upcomingLists(listOf(admin, added), listOf(old), "lea@example.org", event)
        assertEquals(1, lists.size)
        assertEquals(listOf("A", "B"), lists.single().members.map { it.nanoId }.sorted())
        assertEquals("TPL", lists.single().template?.formId)
    }

    private fun GuestForm.people() =
        GuestFormSubmissionCodec.decode(submissionJson)!!.people.map { it.name to it.requestedAccessIds }
}
