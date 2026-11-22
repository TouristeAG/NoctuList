package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestListPortalProjectionBuilderTest {
    private val day = 86_400_000L
    private val event = 1_800_000_000_000L
    private val accesses = listOf(
        VenueAccess(id = "bs", venueName = "Groove", name = "Backstage"),
        VenueAccess(id = "vip", venueName = "Terreau", name = "VIP"),
    )

    private fun temp(nano: String, name: String, venue: String = "Groove", list: String = "Band", date: Long = event, used: Long = 0L, access: String = "") = Guest(
        nanoId = nano, name = name, lastNameAbbreviation = "X", invitations = 0, venueName = "Main",
        isTemporaryGuest = true, temporaryEventDate = date, temporaryVenueName = venue, temporaryArtistName = list,
        temporaryContactPhone = "+41 79 1", temporaryEntryValidatedAt = used, temporaryAccessIds = access,
    )

    private fun grant(id: String, venue: String = "", date: Long = event) = GuestListGrant(id = id, eventDateMillis = date, venueName = venue)

    private fun build(grants: List<GuestListGrant>, guests: List<Guest>) =
        GuestListPortalProjectionBuilder.build(grants, guests, accesses, "Asso", event, 8)

    private fun grantsOf(p: PortalProjection) =
        Json.parseToJsonElement(p.payload).jsonObject.getValue("grants").jsonArray.map { it.jsonObject }

    private fun names(g: JsonObject) = g.getValue("guests").jsonArray.map { it.jsonObject.getValue("firstName").jsonPrimitive.content }

    @Test
    fun onlyTemporaryGuestsOfTheDate() {
        val permanent = Guest(nanoId = "P1", name = "Perm", invitations = 1, venueName = "Groove")
        val other = temp("O1", "Other", date = event + 2 * day)
        val p = build(listOf(grant("G1")), listOf(temp("A1", "Ann"), permanent, other))!!
        assertEquals(listOf("Ann"), names(grantsOf(p).single()))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(event), p.extraFields["grantDates"] as List<Long>)
        assertEquals(TemporaryGuestPortalProjectionBuilder.visibleUntil(event, 8), p.extraFields["expiresAtMillis"])
    }

    @Test
    fun venueGrantShowsOnlyThatVenue() {
        val p = build(listOf(grant("G1", venue = "Terreau")), listOf(temp("A1", "Ann"), temp("B1", "Bob", venue = "Terreau")))!!
        val g = grantsOf(p).single()
        assertEquals(listOf("Bob"), names(g))
        assertEquals("Terreau", g.getValue("venueName").jsonPrimitive.content)
        assertEquals(listOf("vip"), g.getValue("accesses").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun noNanoIdNoQr() {
        val a = temp("SECRETNANO000001", "Ann", used = 5L, access = "bs")
        val p = build(listOf(grant("G1")), listOf(a))!!
        assertFalse(p.payload.contains("SECRETNANO000001"))
        assertFalse(p.payload.contains("\"qr\""))
        val guest = grantsOf(p).single().getValue("guests").jsonArray.single().jsonObject
        assertEquals(TemporaryGuestPortalQrDocument.guestKey(a), guest.getValue("guestKey").jsonPrimitive.content)
        assertTrue(guest.getValue("entryUsed").jsonPrimitive.boolean)
        assertEquals("Backstage", guest.getValue("accessNames").jsonArray.single().jsonPrimitive.content)
        assertEquals("bs", guest.getValue("accessIds").jsonArray.single().jsonPrimitive.content)
        assertEquals("Band", guest.getValue("listName").jsonPrimitive.content)
    }

    @Test
    fun sameDateTwiceMerges() {
        val p = build(listOf(grant("G1"), grant("G2", venue = "Groove"), grant("G3")), listOf(temp("A1", "Ann")))!!
        val g = grantsOf(p).single()
        assertEquals("", g.getValue("venueName").jsonPrimitive.content)
        assertEquals(1, g.getValue("guests").jsonArray.size)
    }

    @Test
    fun aListWithoutVenueShowsNoVenue() {
        val noVenue = temp("A1", "Ann", venue = "").copy(venueName = "BOTH")
        val g = grantsOf(build(listOf(grant("G1")), listOf(noVenue))!!).single()
        assertEquals("", g.getValue("lists").jsonArray.single().jsonObject.getValue("venueName").jsonPrimitive.content)
        assertEquals("", g.getValue("guests").jsonArray.single().jsonObject.getValue("venueName").jsonPrimitive.content)
    }

    @Test
    fun listsAndAccessesOffered() {
        val p = build(listOf(grant("G1")), listOf(temp("A1", "Ann"), temp("B1", "Bob", list = "Friends", venue = "Terreau"), temp("C1", "Cy")))!!
        val g = grantsOf(p).single()
        assertEquals(
            listOf("Band" to "Groove", "Friends" to "Terreau"),
            g.getValue("lists").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("venueName").jsonPrimitive.content },
        )
        assertEquals(listOf("bs", "vip"), g.getValue("accesses").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test
    fun nullWithoutActiveGrant() {
        assertNull(build(emptyList(), listOf(temp("A1", "Ann"))))
        assertNull(build(listOf(grant("G1").copy(revokedAt = 5L)), listOf(temp("A1", "Ann"))))
        assertNull(build(listOf(grant("G1", date = event - 3 * day)), listOf(temp("A1", "Ann"))))
    }

    @Test
    fun schemaIsTwo() {
        assertEquals(2, build(listOf(grant("G1")), listOf(temp("A1", "Ann")))!!.schema)
    }

    private fun pendingForm() = com.eventmanager.app.data.models.GuestForm(
        formId = "F1", eventName = "Band", eventDateMillis = event,
        status = com.eventmanager.app.data.models.GuestFormStatus.PENDING_REVIEW.name,
        submissionJson = com.eventmanager.app.data.models.GuestFormSubmissionCodec.encode(
            com.eventmanager.app.data.models.GuestFormSubmission(people = listOf(com.eventmanager.app.data.models.GuestFormPerson("Ann", emptySet()))),
        ),
    )

    @Test
    fun noReviewsWhenOff() {
        val p = GuestListPortalProjectionBuilder.build(listOf(grant("G1")), listOf(temp("A1", "Ann")), accesses, "Asso", event, 8, reviewForms = listOf(pendingForm()))!!
        assertFalse(grantsOf(p).single().containsKey("reviews"))
    }

    @Test
    fun reviewsWhenOn() {
        val on = GuestListPortalProjectionBuilder.build(listOf(grant("G1")), listOf(temp("A1", "Ann")), accesses, "Asso", event, 8, reviewForms = listOf(pendingForm()), reviewsEnabled = true)!!
        val reviews = grantsOf(on).single().getValue("reviews").jsonArray
        assertEquals(1, reviews.size)
        assertEquals(GuestListReviews.reviewKey(pendingForm()), reviews[0].jsonObject.getValue("reviewKey").jsonPrimitive.content)
        val none = GuestListPortalProjectionBuilder.build(listOf(grant("G1")), listOf(temp("A1", "Ann")), accesses, "Asso", event, 8, reviewsEnabled = true)!!
        assertEquals(0, grantsOf(none).single().getValue("reviews").jsonArray.size)
    }
}
