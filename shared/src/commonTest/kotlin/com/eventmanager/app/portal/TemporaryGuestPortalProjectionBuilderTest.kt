package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.utils.DateTimeUtils
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TemporaryGuestPortalProjectionBuilderTest {
    private val day = 86_400_000L
    private val offset = 0
    private val hour = 3_600_000L
    private val event1 = 1_800_000_000_000L - 1_800_000_000_000L % day + 10 * 3_600_000L
    private val event2 = event1 + 5 * day

    private fun guest(
        id: String,
        name: String = "Ann",
        event: Long? = event1,
        artist: String = "Band",
        venue: String = "Terreau",
        admin: Boolean = false,
        used: Long = 0L,
        email: String = "mgr@example.org",
    ) = Guest(
        nanoId = id,
        name = name,
        lastNameAbbreviation = "X",
        invitations = 0,
        venueName = "Main",
        isTemporaryGuest = true,
        temporaryArtistName = artist,
        temporaryEventDate = event,
        temporaryVenueName = venue,
        temporaryContactPhone = "+41 79 000 00 00",
        temporaryContactEmail = email,
        isAdmin = admin,
        temporaryEntryValidatedAt = used,
    )

    private fun endsAt(event: Long) = DateTimeUtils.getStartOfDayWithOffset(event, 0) + 32 * 3_600_000L

    /** The tests run with the day changing at 8:00, so a list ends 32 h after its day starts. */
    private val listOffset = 8

    private fun input(
        guests: List<Guest>,
        now: Long,
        transfers: List<AccountTransfer> = emptyList(),
        credits: Boolean = true,
    ) = TemporaryGuestPortalInput(guests, transfers, credits, "CHF", "Asso", now, listOffset)

    private fun transfer(amount: Double, id: String) = AccountTransfer(
        holderType = AccountHolderType.GUEST,
        holderId = id,
        holderName = "x",
        amount = amount,
        type = AccountTransferType.MANUAL_ADJUSTMENT,
        sourceReference = "ref-$id-$amount",
        description = "d",
        createdAt = 1L,
    )

    private fun groups(p: PortalProjection) =
        Json.parseToJsonElement(p.payload).jsonObject.getValue("groups").jsonArray.map { it.jsonObject }

    @Test
    fun visibleUntilBoundaries() {
        val start = DateTimeUtils.getStartOfDayWithOffset(event1, 0)
        assertEquals(start + 32 * hour, TemporaryGuestPortalProjectionBuilder.visibleUntil(event1, 8))
        // The list follows the synced day-change offset, as the door does.
        assertEquals(start + 24 * hour, TemporaryGuestPortalProjectionBuilder.visibleUntil(event1, 0))
        assertEquals(start + 30 * hour, TemporaryGuestPortalProjectionBuilder.visibleUntil(event1, 6))
        assertEquals(start + 22 * hour, TemporaryGuestPortalProjectionBuilder.visibleUntil(event1, -2))
        val gs = listOf(guest("A1"))
        for (now in listOf(start - 1, start + 25 * hour, start + 32 * hour - 60_000)) {
            assertNotNull(TemporaryGuestPortalProjectionBuilder.build(input(gs, now)), "visible at ${now - start}")
            assertNotNull(TemporaryGuestPortalQrDocument.build(gs, true, now, listOffset))
        }
        assertNull(TemporaryGuestPortalProjectionBuilder.build(input(gs, start + 32 * hour)))
        assertNull(TemporaryGuestPortalQrDocument.build(gs, true, start + 32 * hour, listOffset))
    }

    @Test
    fun groupsSortedAndEndedOnesDropped() {
        val old = guest("A1", event = event1, artist = "Old")
        val late = guest("B1", event = event2, artist = "Late")
        val mid = guest("C1", event = event2 - day, artist = "Mid")
        val now = endsAt(event1) + 1
        val p = TemporaryGuestPortalProjectionBuilder.build(input(listOf(late, old, mid), now))!!
        assertEquals(listOf("Mid", "Late"), groups(p).map { it.getValue("artistName").jsonPrimitive.content })
        assertEquals(endsAt(event2), p.extraFields["expiresAtMillis"])
        assertEquals(endsAt(event2), groups(p).last().getValue("visibleUntil").jsonPrimitive.content.toLong())
    }

    @Test
    fun returnsNullWhenAllGroupsEnded() {
        val g = guest("A1")
        assertNull(TemporaryGuestPortalProjectionBuilder.build(input(listOf(g), endsAt(event1))))
        assertNotNull(TemporaryGuestPortalProjectionBuilder.build(input(listOf(g), endsAt(event1) - 1)))
    }

    @Test
    fun guestWithoutEventDateIsSkipped() {
        val p = TemporaryGuestPortalProjectionBuilder.build(
            input(listOf(guest("A1", event = null), guest("B1", name = "Bob")), event1)
        )!!
        val guests = groups(p).single().getValue("guests").jsonArray
        assertEquals(1, guests.size)
        assertEquals("Bob", guests.single().jsonObject.getValue("firstName").jsonPrimitive.content)
    }

    @Test
    fun walletOnlyWhenCreditsOnAndNonZero() {
        val a = guest("A1", name = "A")
        val b = guest("B1", name = "B")
        val tr = listOf(transfer(12.5, "A1"))
        fun wallets(credits: Boolean) = groups(
            TemporaryGuestPortalProjectionBuilder.build(input(listOf(a, b), event1, tr, credits))!!
        ).single().getValue("guests").jsonArray.map { it.jsonObject.getValue("wallet") }

        val on = wallets(true)
        assertEquals(12.5, on[0].jsonObject.getValue("balance").jsonPrimitive.double)
        assertEquals("CHF", on[0].jsonObject.getValue("currency").jsonPrimitive.content)
        assertFalse(on[0].jsonObject.containsKey("transfers"))
        assertEquals(JsonNull, on[1])
        assertEquals(listOf(JsonNull, JsonNull), wallets(false))
    }

    @Test
    fun payloadHasNoNanoId() {
        val p = TemporaryGuestPortalProjectionBuilder.build(input(listOf(guest("SecretNano123")), event1))!!
        assertFalse(p.payload.contains("SecretNano123"))
        val key = groups(p).single().getValue("guests").jsonArray.single().jsonObject.getValue("guestKey").jsonPrimitive.content
        assertEquals(PortalProjectionBuilder.sha256("SecretNano123").take(16), key)
    }

    @Test
    fun qrExcludesAdminAndUsedEntries() {
        val ok = guest("OK1", name = "Ok")
        val admin = guest("AD1", name = "Admin", admin = true)
        val used = guest("US1", name = "Used", used = 5L)
        val blank = guest("", name = "Blank")
        val doc = TemporaryGuestPortalQrDocument.build(listOf(ok, admin, used, blank), true, event1, listOffset)!!
        @Suppress("UNCHECKED_CAST")
        val items = doc["items"] as Map<String, Map<String, String>>
        assertEquals(setOf(TemporaryGuestPortalQrDocument.guestKey(ok)), items.keys)
        assertEquals("OK1", items.values.single()["payload"])
        assertNull(TemporaryGuestPortalQrDocument.build(listOf(ok), false, event1, listOffset))
        assertNull(TemporaryGuestPortalQrDocument.build(listOf(admin, used), true, event1, listOffset))

        val p = TemporaryGuestPortalProjectionBuilder.build(input(listOf(ok, admin, used), event1))!!
        val qr = groups(p).single().getValue("guests").jsonArray.associate {
            it.jsonObject.getValue("firstName").jsonPrimitive.content to it.jsonObject.getValue("qr").jsonPrimitive.boolean
        }
        assertEquals(mapOf("Ok" to true, "Admin" to false, "Used" to false), qr)
    }

    @Test
    fun hashStableWithinADay() {
        val gs = listOf(guest("A1"))
        val start = DateTimeUtils.getStartOfDayWithOffset(event1, offset)
        val a = TemporaryGuestPortalProjectionBuilder.build(input(gs, start + 1_000))!!
        val b = TemporaryGuestPortalProjectionBuilder.build(input(gs, start + 20 * 3_600_000L))!!
        assertEquals(a.contentHash, b.contentHash)
        val c = TemporaryGuestPortalProjectionBuilder.build(input(listOf(guest("A1", name = "Zed")), start + 1_000))!!
        assertNotEquals(a.contentHash, c.contentHash)
    }

    @Test
    fun qrSkipsEndedGroupsAndGuestsWithoutDate() {
        val running = guest("RUN1", event = event2, artist = "Run")
        val ended = guest("END1", event = event1, artist = "End")
        val noDate = guest("ND1", event = null)
        val now = endsAt(event1) + 1
        val doc = TemporaryGuestPortalQrDocument.build(listOf(running, ended, noDate), true, now, listOffset)!!
        @Suppress("UNCHECKED_CAST")
        val items = doc["items"] as Map<String, Map<String, String>>
        assertEquals(setOf(TemporaryGuestPortalQrDocument.guestKey(running)), items.keys)
        assertNull(TemporaryGuestPortalQrDocument.build(listOf(ended, noDate), true, now, listOffset))
    }

    @Test
    fun tinyBalanceRoundsToNullWallet() {
        val p = TemporaryGuestPortalProjectionBuilder.build(
            input(listOf(guest("A1")), event1, listOf(transfer(0.004, "A1")))
        )!!
        assertEquals(JsonNull, groups(p).single().getValue("guests").jsonArray.single().jsonObject.getValue("wallet"))
    }

    @Test
    fun guestsSortedByNameAndVenueFallsBack() {
        val p = TemporaryGuestPortalProjectionBuilder.build(
            input(listOf(guest("Z1", name = "zoe", venue = ""), guest("B1", name = "Bob", venue = "")), event1)
        )!!
        val g = groups(p).single()
        assertEquals(listOf("Bob", "zoe"), g.getValue("guests").jsonArray.map { it.jsonObject.getValue("firstName").jsonPrimitive.content })
        assertEquals("Main", g.getValue("venueName").jsonPrimitive.content)
    }

    @Test
    fun aListWithoutVenueAndItsBothTemplateAreOneGroup() {
        // Admin list with no venue; template and added guest written with "BOTH" before the fix.
        val admin = guest("A1", venue = "").copy(venueName = "BOTH")
        val added = guest("B1", name = "Bisous", venue = "BOTH").copy(venueName = "BOTH")
        val p = TemporaryGuestPortalProjectionBuilder.build(input2(listOf(admin, added), event1, listOf(tpl().copy(venueName = "BOTH"))))!!
        val g = groups(p).single()
        assertEquals(2, g.getValue("guests").jsonArray.size)
        assertEquals("", g.getValue("venueName").jsonPrimitive.content, "BOTH is never shown as a venue")
    }

    private fun tpl(open: Boolean = true, artist: String = "Band", event: Long = event1, max: Int = 4) = com.eventmanager.app.data.models.GuestForm(
        formId = "TPL-$artist",
        portalUid = "U1",
        allowMultipleResponses = true,
        eventDateMillis = event,
        venueName = "Terreau",
        artistName = artist,
        maxGuests = max,
        status = if (open) "OPEN" else "EXPIRED",
        expiresAtMillis = TemporaryGuestPortalProjectionBuilder.visibleUntil(event, 8),
        offeredAccessIds = """[{"id":"acc-bs","name":"Backstage","maxRequests":1}]""",
    )

    private fun input2(guests: List<Guest>, now: Long, forms: List<com.eventmanager.app.data.models.GuestForm> = emptyList(), created: Boolean = true) =
        TemporaryGuestPortalInput(
            guests, emptyList(), true, "CHF", "Asso", now, listOffset,
            deviceCreated = created,
            accessNames = mapOf("acc-bs" to "Backstage", "acc-vip" to "VIP"),
            inviteForms = forms,
        )

    @Test
    fun schema2CarriesAccessNamesNotIds() {
        val g = guest("A1").copy(temporaryAccessIds = "acc-bs,acc-vip,acc-gone")
        val p = TemporaryGuestPortalProjectionBuilder.build(input2(listOf(g), event1))!!
        assertEquals(2, p.schema)
        val names = groups(p).single().getValue("guests").jsonArray.single().jsonObject.getValue("accessNames").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("Backstage", "VIP"), names)
        assertFalse(p.payload.contains("acc-bs") || p.payload.contains("acc-vip") || p.payload.contains("A1"))
    }

    @Test
    fun deviceCreatedIsCarried() {
        val p = TemporaryGuestPortalProjectionBuilder.build(input2(listOf(guest("A1")), event1, created = false))!!
        assertEquals(false, p.extraFields["deviceCreated"])
        assertEquals(false, Json.parseToJsonElement(p.payload).jsonObject.getValue("deviceCreated").jsonPrimitive.boolean)
        assertEquals(true, TemporaryGuestPortalProjectionBuilder.build(input2(listOf(guest("A1")), event1))!!.extraFields["deviceCreated"])
    }

    @Test
    fun emptyListWithOpenTemplateIsPublished() {
        val p = TemporaryGuestPortalProjectionBuilder.build(input2(emptyList(), event1, listOf(tpl(artist = "Friends"))))!!
        val group = groups(p).single()
        assertEquals("Friends", group.getValue("artistName").jsonPrimitive.content)
        assertEquals(0, group.getValue("guests").jsonArray.size)
        val invite = group.getValue("invite").jsonObject
        assertEquals("TPL-Friends", invite.getValue("formId").jsonPrimitive.content)
        assertEquals(4, invite.getValue("remaining").jsonPrimitive.content.toInt())
        assertEquals("Backstage", invite.getValue("accesses").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertNull(TemporaryGuestPortalProjectionBuilder.build(input2(emptyList(), event1, listOf(tpl(open = false, artist = "Friends")))))
    }

    @Test
    fun inviteBlockNullWithoutTemplate() {
        val group = groups(TemporaryGuestPortalProjectionBuilder.build(input2(listOf(guest("A1")), event1))!!).single()
        assertEquals(JsonNull, group.getValue("invite"))
        assertEquals(0, group.getValue("pending").jsonArray.size)
    }

    @Test
    fun pendingAddAndRemoveListed() {
        val a = guest("A1", name = "Ann")
        val add = com.eventmanager.app.data.models.GuestForm(
            formId = "R1", parentFormId = "TPL-Band", portalUid = "U1", status = "PENDING_REVIEW", requestKind = "ADD",
            submissionJson = com.eventmanager.app.data.models.GuestFormSubmissionCodec.encode(
                com.eventmanager.app.data.models.GuestFormSubmission(people = listOf(com.eventmanager.app.data.models.GuestFormPerson("Cy Doe"))),
            ),
        )
        val rm = com.eventmanager.app.data.models.GuestForm(
            formId = "R2", parentFormId = "TPL-Band", portalUid = "U1", status = "REJECTED", requestKind = "REMOVE",
            removeGuestKeys = TemporaryGuestPortalQrDocument.guestKey(a),
        )
        val group = groups(TemporaryGuestPortalProjectionBuilder.build(input2(listOf(a), event1, listOf(tpl(), add, rm)))!!).single()
        val pending = group.getValue("pending").jsonArray.map { it.jsonObject }
        assertEquals(listOf("R1", "R2"), pending.map { it.getValue("responseId").jsonPrimitive.content })
        assertEquals(listOf("Cy Doe"), pending[0].getValue("names").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("ADD", pending[0].getValue("kind").jsonPrimitive.content)
        assertEquals(listOf("Ann X"), pending[1].getValue("names").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(TemporaryGuestPortalQrDocument.guestKey(a), pending[1].getValue("guestKeys").jsonArray.single().jsonPrimitive.content)
        assertEquals("REJECTED", pending[1].getValue("status").jsonPrimitive.content)
        // 4 places, Ann accepted, Cy pending; the refused removal frees nothing.
        assertEquals(2, group.getValue("invite").jsonObject.getValue("remaining").jsonPrimitive.content.toInt())
    }
}
