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

    private fun input(
        guests: List<Guest>,
        now: Long,
        transfers: List<AccountTransfer> = emptyList(),
        credits: Boolean = true,
    ) = TemporaryGuestPortalInput(guests, transfers, credits, "CHF", "Asso", now)

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
        assertEquals(start + 32 * hour, TemporaryGuestPortalProjectionBuilder.visibleUntil(event1))
        val gs = listOf(guest("A1"))
        for (now in listOf(start - 1, start + 25 * hour, start + 32 * hour - 60_000)) {
            assertNotNull(TemporaryGuestPortalProjectionBuilder.build(input(gs, now)), "visible at ${now - start}")
            assertNotNull(TemporaryGuestPortalQrDocument.build(gs, true, now))
        }
        assertNull(TemporaryGuestPortalProjectionBuilder.build(input(gs, start + 32 * hour)))
        assertNull(TemporaryGuestPortalQrDocument.build(gs, true, start + 32 * hour))
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
        val doc = TemporaryGuestPortalQrDocument.build(listOf(ok, admin, used, blank), true, event1)!!
        @Suppress("UNCHECKED_CAST")
        val items = doc["items"] as Map<String, Map<String, String>>
        assertEquals(setOf(TemporaryGuestPortalQrDocument.guestKey(ok)), items.keys)
        assertEquals("OK1", items.values.single()["payload"])
        assertNull(TemporaryGuestPortalQrDocument.build(listOf(ok), false, event1))
        assertNull(TemporaryGuestPortalQrDocument.build(listOf(admin, used), true, event1))

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
        val doc = TemporaryGuestPortalQrDocument.build(listOf(running, ended, noDate), true, now)!!
        @Suppress("UNCHECKED_CAST")
        val items = doc["items"] as Map<String, Map<String, String>>
        assertEquals(setOf(TemporaryGuestPortalQrDocument.guestKey(running)), items.keys)
        assertNull(TemporaryGuestPortalQrDocument.build(listOf(ended, noDate), true, now))
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
}
