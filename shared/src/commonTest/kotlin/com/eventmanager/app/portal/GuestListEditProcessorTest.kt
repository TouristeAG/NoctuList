package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.Volunteer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestListEditProcessorTest {
    private val event = 1_800_000_000_000L
    private val uid = "PortalUid000000000000009"
    private val host = FakeHost(emptyList()).apply {
        tempSpace = true
        venues = setOf("Groove", "Terreau")
        catalog = listOf(
            VenueAccess(id = "bs", venueName = "Groove", name = "Backstage"),
            VenueAccess(id = "vip", venueName = "Terreau", name = "VIP"),
        )
    }

    private fun holder(grants: List<GuestListGrant>) = Volunteer(
        id = "V1", name = "Lea", lastNameAbbreviation = "M", email = "lea@example.org", phoneNumber = "",
        portalEnabled = true, portalUid = uid, guestListGrantsJson = GuestListGrants.encode(grants),
    )

    private fun temp(nano: String, name: String, venue: String = "Groove", list: String = "Band", used: Long = 0L) = Guest(
        nanoId = nano, name = name, lastNameAbbreviation = "X", invitations = 0, venueName = "Main",
        isTemporaryGuest = true, temporaryEventDate = event, temporaryVenueName = venue, temporaryArtistName = list,
        temporaryContactEmail = "band@example.org", temporaryContactPhone = "+41 79 1", temporaryEntryValidatedAt = used,
    )

    private val grant = GuestListGrant(id = "G1", eventDateMillis = event)

    private fun edit(action: String, guestKey: String = "", first: String = "", last: String = "", list: String = "", venue: String = "", access: List<String> = emptyList(), grantId: String = "G1", date: Long = event) =
        GuestListEdit(
            id = "E1", uid = uid, grantId = grantId, eventDateMillis = date, action = action, guestKey = guestKey,
            firstName = first, lastName = last, listName = list, venueName = venue, accessIds = access,
        )

    private fun run(e: GuestListEdit, grants: List<GuestListGrant> = listOf(grant)) = runBlocking {
        GuestListEditProcessor(host) { event }.apply(e, listOf(holder(grants)), host.guests.toList())
    }

    @Test
    fun addToExistingListCopiesContact() {
        host.guests += temp("A1", "Ann").copy(barDiscountPercent = 15)
        assertEquals("DONE" to "", run(edit("ADD", first = "Cy", last = "Doe", list = "Band", venue = "Groove", access = listOf("bs", "vip"))))
        val added = host.addedBatches.single()
        assertEquals("Band", added.artistName)
        assertEquals("Groove", added.venueName)
        assertEquals("band@example.org", added.contactEmail)
        assertEquals("+41 79 1", added.emergencyContactPhone)
        assertEquals("Cy DOE", added.guests.single().name)
        assertEquals(setOf("bs"), added.guests.single().accessIds, "accessIdsFilteredToVenue")
        assertEquals(15, added.barDiscountPercent, "the list's bar discount follows")
    }

    @Test
    fun addToNewList() {
        assertEquals("DONE" to "", run(edit("ADD", first = "Cy", list = "Friends", venue = "Terreau")))
        val added = host.addedBatches.single()
        assertEquals("Friends", added.artistName)
        assertEquals("", added.contactEmail)
        assertEquals(event, added.eventDateMillis)
    }

    @Test
    fun addNeedsAnActiveVenue() {
        assertEquals("REJECTED" to "INVALID", run(edit("ADD", first = "Cy", list = "Friends", venue = "Nowhere")))
        assertEquals("REJECTED" to "INVALID", run(edit("ADD", first = "Cy", list = "", venue = "Groove")))
        assertTrue(host.addedBatches.isEmpty())
    }

    @Test
    fun updateNameAndAccesses() {
        val a = temp("A1", "Ann")
        host.guests += a
        assertEquals("DONE" to "", run(edit("UPDATE", TemporaryGuestPortalQrDocument.guestKey(a), first = "", last = "doe", access = listOf("bs"))))
        val g = host.guests.single()
        assertEquals("Ann", g.name)
        assertEquals("DOE", g.lastNameAbbreviation)
        assertEquals("bs", g.temporaryAccessIds)
    }

    @Test
    fun deleteUnused() {
        val a = temp("A1", "Ann")
        host.guests += a
        assertEquals("DONE" to "", run(edit("DELETE", TemporaryGuestPortalQrDocument.guestKey(a))))
        assertTrue(host.guests.isEmpty())
    }

    @Test
    fun entryUsedRefused() {
        val a = temp("A1", "Ann", used = 5L)
        host.guests += a
        assertEquals("REJECTED" to "ENTRY_USED", run(edit("DELETE", TemporaryGuestPortalQrDocument.guestKey(a))))
        assertEquals("REJECTED" to "ENTRY_USED", run(edit("UPDATE", TemporaryGuestPortalQrDocument.guestKey(a), first = "Bo")))
        assertEquals(1, host.guests.size)
    }

    @Test
    fun revokedGrantRejects() {
        val a = temp("A1", "Ann")
        host.guests += a
        assertEquals("REJECTED" to "NO_GRANT", run(edit("DELETE", TemporaryGuestPortalQrDocument.guestKey(a)), listOf(grant.copy(revokedAt = 1L))))
        assertEquals(1, host.guests.size)
    }

    @Test
    fun otherDateRejects() {
        assertEquals("REJECTED" to "NO_GRANT", run(edit("ADD", first = "Cy", list = "Band", venue = "Groove", date = event + 86_400_000L)))
        assertEquals("REJECTED" to "NO_GRANT", run(edit("ADD", first = "Cy", list = "Band", venue = "Groove", grantId = "nope")))
    }

    @Test
    fun otherVenueNotFound() {
        val b = temp("B1", "Bob", venue = "Terreau")
        host.guests += b
        val limited = listOf(grant.copy(venueName = "Groove"))
        assertEquals("REJECTED" to "NOT_FOUND", run(edit("DELETE", TemporaryGuestPortalQrDocument.guestKey(b)), limited))
        // A venue-limited grant adds to its own venue whatever the request says.
        assertEquals("DONE" to "", run(edit("ADD", first = "Cy", list = "New", venue = "Terreau"), limited))
        assertEquals("Groove", host.addedBatches.single().venueName)
    }

    @Test
    fun parseChecksTheShape() {
        val ok = mapOf(
            "uid" to uid, "grantId" to "G1", "eventDateMillis" to event, "action" to "UPDATE",
            "guestKey" to "0123456789abcdef", "firstName" to " Ann ", "lastName" to "", "accessIds" to listOf("bs"),
            "status" to "PENDING",
        )
        val parsed = GuestListEdit.parse("E9", ok)!!
        assertEquals("Ann", parsed.firstName)
        assertEquals(listOf("bs"), parsed.accessIds)
        assertNull(GuestListEdit.parse("E9", ok + ("action" to "WIPE")))
        assertNull(GuestListEdit.parse("E9", ok + ("guestKey" to "raw-nano-id")))
        assertNull(GuestListEdit.parse("E9", ok + ("firstName" to "<b>")))
    }
}
