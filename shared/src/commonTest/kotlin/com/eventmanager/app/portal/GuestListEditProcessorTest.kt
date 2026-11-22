package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormRequestKind
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.ManualTemporaryGuestEntry
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

    private fun run(e: GuestListEdit, grants: List<GuestListGrant> = listOf(grant), forms: List<GuestForm> = emptyList()) = runBlocking {
        GuestListEditProcessor(host) { event }.apply(e, listOf(holder(grants)), host.guests.toList(), forms)
    }

    private fun request(
        id: String,
        venue: String = "Groove",
        kind: GuestFormRequestKind = GuestFormRequestKind.ADD,
        status: GuestFormStatus = GuestFormStatus.PENDING_REVIEW,
    ) = GuestForm(
        formId = id, venueName = venue, eventName = "Band", eventDateMillis = event,
        status = status.name, submittedAt = 10L, requestKind = kind.name,
    )

    private fun review(action: String, form: GuestForm, people: List<ManualTemporaryGuestEntry> = emptyList()) =
        edit(action).copy(reviewKey = GuestListReviews.reviewKey(form), people = people)

    private fun reviewing() = host.apply { portalValidate = true }

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
    fun addToNewListCarriesContactAndNotes() {
        val e = edit("ADD", first = "Cy", list = "Friends", venue = "Terreau")
            .copy(phone = "+41 79 555 44 33", contactEmail = "me@example.org", notes = "Arrives after midnight")
        assertEquals("DONE" to "", run(e))
        val added = host.addedBatches.single()
        assertEquals("+41 79 555 44 33", added.emergencyContactPhone)
        assertEquals("me@example.org", added.contactEmail)
        assertEquals("Arrives after midnight", added.comments)
    }

    @Test
    fun addToExistingListKeepsItsContactAndTakesTheNotes() {
        host.guests += temp("A1", "Ann")
        val e = edit("ADD", first = "Cy", list = "Band", venue = "Groove")
            .copy(phone = "+41 79 000 00 00", contactEmail = "me@example.org", notes = "Plus one")
        assertEquals("DONE" to "", run(e))
        val added = host.addedBatches.single()
        assertEquals("band@example.org", added.contactEmail, "the list's contact stays")
        assertEquals("+41 79 1", added.emergencyContactPhone)
        assertEquals("Plus one", added.comments)
    }

    @Test
    fun addToAListWithoutVenue() {
        // Admin list with no venue: its guests carry "BOTH", and the portal sends that as the venue.
        host.guests += temp("A1", "Ann", venue = "").copy(venueName = "BOTH")
        assertEquals("DONE" to "", run(edit("ADD", first = "Cy", list = "Band", venue = "BOTH", access = listOf("bs", "vip", "gone"))))
        val added = host.addedBatches.single()
        assertEquals("", added.venueName, "the list keeps no venue")
        assertEquals("band@example.org", added.contactEmail, "joined the existing list")
        assertEquals(setOf("bs", "vip"), added.guests.single().accessIds, "a list without venue takes the accesses of every venue")
    }

    @Test
    fun newListWithoutVenue() {
        assertEquals("DONE" to "", run(edit("ADD", first = "Cy", list = "Friends", venue = "")))
        assertEquals("", host.addedBatches.single().venueName)
    }

    @Test
    fun updateOnAListWithoutVenueKeepsItsAccesses() {
        val a = temp("A1", "Ann", venue = "").copy(venueName = "BOTH")
        host.guests += a
        val key = TemporaryGuestPortalQrDocument.guestKey(a)
        assertEquals("DONE" to "", run(edit("UPDATE", guestKey = key, first = "Anna", access = listOf("bs", "vip"))))
        assertEquals("bs,vip", host.guests.single { it.nanoId == "A1" }.temporaryAccessIds)
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
    fun acceptAddWithCorrections() {
        reviewing()
        val form = request("F1", venue = "")
        val e = review("ACCEPT", form, listOf(ManualTemporaryGuestEntry("Ann Lee", setOf("bs", "vip")), ManualTemporaryGuestEntry("Bob", emptySet())))
            .copy(contactEmail = "fix@example.org", phone = "+41 79 2", notes = "Corrected")
        assertEquals("DONE" to "", run(e, forms = listOf(form)))
        val a = host.acceptedReviews.single()
        assertEquals("F1", a.form.formId)
        assertEquals(listOf("Ann Lee", "Bob"), a.people.map { it.name })
        assertEquals(setOf("bs", "vip"), a.people.first().accessIds)
        assertEquals("fix@example.org", a.contactEmail)
        assertEquals("+41 79 2", a.phone)
        assertEquals("Corrected", a.notes)
        assertTrue(a.reviewedBy.startsWith("Plateforme · "), a.reviewedBy)
        assertEquals("Plateforme · Lea M", a.reviewedBy)
        assertTrue(host.rejectedReviews.isEmpty())
    }

    @Test
    fun acceptRemoval() {
        reviewing()
        val form = request("F2", kind = GuestFormRequestKind.REMOVE)
        assertEquals("DONE" to "", run(review("ACCEPT", form, listOf(ManualTemporaryGuestEntry("Ignored"))), forms = listOf(form)))
        assertEquals("F2", host.removedReviews.single().first.formId)
        assertEquals("Plateforme · Lea M", host.removedReviews.single().second)
        assertTrue(host.acceptedReviews.isEmpty())
    }

    @Test
    fun rejectRequest() {
        reviewing()
        val form = request("F3")
        assertEquals("DONE" to "", run(review("REJECT", form), forms = listOf(form)))
        assertEquals("F3", host.rejectedReviews.single().first.formId)
        assertEquals("Plateforme · Lea M", host.rejectedReviews.single().second)
    }

    @Test
    fun disabledRejects() {
        val form = request("F4")
        assertEquals("REJECTED" to "DISABLED", run(review("REJECT", form), forms = listOf(form)))
        assertEquals("REJECTED" to "DISABLED", run(review("ACCEPT", form, listOf(ManualTemporaryGuestEntry("Ann"))), forms = listOf(form)))
        assertTrue(host.rejectedReviews.isEmpty() && host.acceptedReviews.isEmpty())
    }

    @Test
    fun revokedGrantRejectsReview() {
        reviewing()
        val form = request("F5")
        assertEquals("REJECTED" to "NO_GRANT", run(review("REJECT", form), listOf(grant.copy(revokedAt = 1L)), listOf(form)))
        assertTrue(host.rejectedReviews.isEmpty())
    }

    @Test
    fun alreadyReviewedNotPending() {
        reviewing()
        val done = request("F6", status = GuestFormStatus.ACCEPTED)
        assertEquals("REJECTED" to "NOT_PENDING", run(review("REJECT", done), forms = listOf(done)))
        val gone = request("F7")
        assertEquals("REJECTED" to "NOT_PENDING", run(review("REJECT", gone), forms = emptyList()))
        assertTrue(host.rejectedReviews.isEmpty())
    }

    @Test
    fun otherVenueReviewNotPending() {
        reviewing()
        val form = request("F8", venue = "Terreau")
        assertEquals("REJECTED" to "NOT_PENDING", run(review("REJECT", form), listOf(grant.copy(venueName = "Groove")), listOf(form)))
        assertTrue(host.rejectedReviews.isEmpty())
    }

    @Test
    fun acceptFiltersAccessAndNeedsPeople() {
        reviewing()
        val form = request("F9", venue = "Groove")
        assertEquals("REJECTED" to "INVALID", run(review("ACCEPT", form, listOf(ManualTemporaryGuestEntry(" "))), forms = listOf(form)))
        assertEquals("REJECTED" to "INVALID", run(review("ACCEPT", form), forms = listOf(form)))
        assertTrue(host.acceptedReviews.isEmpty())
        assertTrue(host.rejectedReviews.isEmpty(), "the request stays pending")
        assertEquals("DONE" to "", run(review("ACCEPT", form, listOf(ManualTemporaryGuestEntry("Ann", setOf("bs", "vip", "gone")), ManualTemporaryGuestEntry(""))), forms = listOf(form)))
        val a = host.acceptedReviews.single()
        assertEquals(listOf("Ann"), a.people.map { it.name })
        assertEquals(setOf("bs"), a.people.single().accessIds, "accesses of another venue are dropped")
    }

    @Test
    fun overLimitKeepsPending() {
        reviewing()
        host.acceptResult = false
        val form = request("F10")
        assertEquals("REJECTED" to "OVER_LIMIT", run(review("ACCEPT", form, listOf(ManualTemporaryGuestEntry("Ann"))), forms = listOf(form)))
        assertTrue(host.rejectedReviews.isEmpty())
    }

    @Test
    fun parseReviews() {
        val ok = mapOf(
            "uid" to uid, "grantId" to "G1", "eventDateMillis" to event, "action" to "ACCEPT",
            "reviewKey" to "0123456789abcdef", "status" to "PENDING",
            "people" to listOf(mapOf("name" to " Ann  Lee ", "accessIds" to listOf("bs", " "))),
        )
        val parsed = GuestListEdit.parse("E9", ok)!!
        assertEquals("0123456789abcdef", parsed.reviewKey)
        assertEquals(listOf(ManualTemporaryGuestEntry("Ann Lee", setOf("bs"))), parsed.people)
        assertEquals("REJECT", GuestListEdit.parse("E9", ok + ("action" to "REJECT"))!!.action)
        assertNull(GuestListEdit.parse("E9", ok - "reviewKey"))
        assertNull(GuestListEdit.parse("E9", ok + ("reviewKey" to "raw-form-id")))
        assertNull(GuestListEdit.parse("E9", ok + ("action" to "REJECT") - "reviewKey"))
        assertNull(GuestListEdit.parse("E9", ok + ("people" to List(101) { mapOf("name" to "Ann") })))
        assertNull(GuestListEdit.parse("E9", ok + ("people" to listOf(mapOf("name" to "<b>")))))
        assertNull(GuestListEdit.parse("E9", ok + ("people" to listOf(mapOf("name" to "Ann", "accessIds" to List(21) { "a$it" })))))
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
        val contact = GuestListEdit.parse("E9", ok + mapOf("phone" to " +41 79 1 ", "contactEmail" to " Me@Example.org ", "notes" to " Late "))!!
        assertEquals("+41 79 1", contact.phone)
        assertEquals("me@example.org", contact.contactEmail)
        assertEquals("Late", contact.notes)
        assertNull(GuestListEdit.parse("E9", ok + ("phone" to "call me")))
        assertNull(GuestListEdit.parse("E9", ok + ("contactEmail" to "not-an-address")))
        assertNull(GuestListEdit.parse("E9", ok + ("notes" to "x".repeat(501))))
    }
}
