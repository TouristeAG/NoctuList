package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Guest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestPortalProjectionBuilderTest {
    private val secretId = "G1secretGuestWalletId"

    private val guest = Guest(
        nanoId = secretId,
        name = "Jean",
        lastNameAbbreviation = "DUPONT",
        email = "jean@example.org",
        phoneNumber = "+41 79 111 22 33",
        invitations = 2,
        venueName = "Le Terreau",
        notes = "private note about Jean",
        nfcCardUid = "04A1B2C3D4",
        nfcCardUidHash = "deadbeefhash",
        barDiscountPercent = 20,
        portalEnabled = true,
        portalUid = "guestPortalUid1",
    )

    private fun transfer(amount: Double, holderType: AccountHolderType, holderId: String) = AccountTransfer(
        holderType = holderType,
        holderId = holderId,
        holderName = "x",
        amount = amount,
        type = AccountTransferType.POS_SALE,
        sourceReference = "ref-$holderType-$holderId-$amount",
        description = "1x Beer",
        createdAt = 1_000L,
    )

    private fun input(g: Guest = guest, transfers: List<AccountTransfer> = emptyList()) =
        GuestPortalProjectionInput(
            guest = g,
            transfers = transfers,
            currencyCode = "CHF",
            associationName = "Asso",
            hasPhoto = false,
            photoVersion = "abc",
        )

    private fun json(g: Guest = guest, transfers: List<AccountTransfer> = emptyList()) =
        Json.parseToJsonElement(GuestPortalProjectionBuilder.build(input(g, transfers)).payload).jsonObject

    @Test
    fun payloadHasNoNanoIdNotesNfcOrAdmin() {
        val payload = GuestPortalProjectionBuilder.build(input()).payload
        assertFalse(payload.contains(secretId), "guest id leaked")
        assertFalse(payload.contains("nanoId"))
        assertFalse(payload.contains("notes"))
        assertFalse(payload.contains("private note"))
        assertFalse(payload.contains("nfcCardUid"))
        assertFalse(payload.contains("04A1B2C3D4"))
        assertFalse(payload.contains("deadbeefhash"))
        assertFalse(payload.contains("isAdmin"))
        assertFalse(payload.contains("guestPortalUid1"))
        assertEquals(
            listOf(
                "firstName", "lastName", "email", "phone", "hasPhoto", "photoVersion", "venueName", "invitations",
                "barDiscountPercent", "wallet", "qrEmailAllowed", "mustSetPassword", "association",
                "googleEmail", "googleLinkedBy", "googleLinkAvailable",
            ),
            json().keys.toList(),
        )
        assertEquals(2, json()["invitations"]!!.jsonPrimitive.int)
    }

    @Test
    fun barDiscountClampedTo100() {
        assertEquals(100, json(guest.copy(barDiscountPercent = 150))["barDiscountPercent"]!!.jsonPrimitive.int)
        assertEquals(20, json()["barDiscountPercent"]!!.jsonPrimitive.int)
    }

    @Test
    fun walletOnlyCountsThisGuest() {
        val transfers = listOf(
            transfer(-5.0, AccountHolderType.GUEST, secretId),
            transfer(-7.0, AccountHolderType.VOLUNTEER, secretId),
            transfer(-11.0, AccountHolderType.GUEST, "other-guest"),
        )
        val wallet = json(transfers = transfers)["wallet"]!!.jsonObject
        assertEquals(-5.0, wallet["balance"]!!.jsonPrimitive.double)
    }

    @Test
    fun qrEmailRefusedForAdmin() {
        assertTrue(json()["qrEmailAllowed"]!!.jsonPrimitive.boolean)
        assertFalse(json(guest.copy(isAdmin = true))["qrEmailAllowed"]!!.jsonPrimitive.boolean)
        assertFalse(json(guest.copy(email = ""))["qrEmailAllowed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun documentCarriesGuestSchema() {
        val projection = GuestPortalProjectionBuilder.build(input())
        assertEquals(1, projection.toFirestoreMap(5L)["schema"])
        assertEquals(64, projection.contentHash.length)
    }
}
