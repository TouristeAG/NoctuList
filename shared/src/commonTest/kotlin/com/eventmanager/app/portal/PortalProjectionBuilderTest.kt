package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PortalProjectionBuilderTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val secretId = "V1secretWalletQrId77"

    private val volunteer = Volunteer(
        id = secretId,
        name = "Léa",
        lastNameAbbreviation = "MARTIN",
        email = "lea@example.org",
        phoneNumber = "+41 79 123 45 67",
        dateOfBirth = "1998-04-12",
        gender = Gender.FEMALE,
        nfcCardUid = "04A1B2C3D4",
        nfcCardUidHash = "deadbeefhash",
        profilePhotoPath = "orgs/o/profilePhotos/volunteers/$secretId.jpg",
        profilePhotoUrl = "https://firebasestorage.googleapis.com/v0/b/x/o/orgs%2Fo%2FprofilePhotos%2Fvolunteers%2F$secretId.jpg?alt=media&token=t",
        portalEnabled = true,
        portalUid = "portalUid123",
    )

    private fun job(daysFromNow: Long, name: String = "Bar") = Job(
        volunteerId = secretId,
        jobType = JobType.BAR,
        jobTypeName = name,
        venueName = "Le Terreau",
        date = now + daysFromNow * day,
        shiftTime = ShiftTime.BEFORE_MIDNIGHT,
    )

    private fun transfer(
        amount: Double,
        daysAgo: Long,
        holderId: String = secretId,
        type: AccountTransferType = AccountTransferType.POS_SALE,
        state: AccountTransferSyncState = AccountTransferSyncState.CONFIRMED,
    ) = AccountTransfer(
        holderType = AccountHolderType.VOLUNTEER,
        holderId = holderId,
        holderName = "Léa M.",
        amount = amount,
        type = type,
        sourceReference = "ref-$holderId-$daysAgo-$amount",
        description = "1x Beer",
        jobTypeName = "Bar",
        posVenueName = "Le Terreau",
        createdAt = now - daysAgo * day,
        syncState = state,
    )

    private fun input(
        v: Volunteer = volunteer,
        jobs: List<Job> = listOf(job(-3), job(-10, "Entrée"), job(5)),
        transfers: List<AccountTransfer> = emptyList(),
    ) = PortalProjectionInput(
        volunteer = v,
        jobs = jobs,
        transfers = transfers,
        jobTypeConfigs = emptyList(),
        currencyCode = "CHF",
        associationName = "Asso",
        hasPhoto = true,
        photoVersion = "abc",
        mustSetPassword = false,
        now = now,
        offsetHours = 0,
    )

    @Test
    fun neverPublishesWalletIdNfcOrPhotoPaths() {
        val projection = PortalProjectionBuilder.build(input())
        val payload = projection.payload
        assertFalse(payload.contains(secretId), "volunteer id (wallet QR) leaked")
        assertFalse(payload.contains("04A1B2C3D4"), "NFC UID leaked")
        assertFalse(payload.contains("deadbeefhash"), "NFC hash leaked")
        assertFalse(payload.contains("profilePhotos"), "photo path leaked")
        assertFalse(payload.contains("portalUid123"))
        assertFalse(payload.contains("isAdmin"))
        assertFalse(payload.contains("ref-"), "ledger references leaked")
    }

    @Test
    fun publishesProfileAndSplitsPastFromUpcomingShifts() {
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input()).payload).jsonObject
        assertEquals("Léa", json["firstName"]!!.jsonPrimitive.content)
        assertEquals("MARTIN", json["lastName"]!!.jsonPrimitive.content)
        assertEquals("FEMALE", json["gender"]!!.jsonPrimitive.content)
        assertEquals(3, json["totalShifts"]!!.jsonPrimitive.int)
        assertEquals(2, json["shifts"]!!.jsonArray.size)
        assertEquals(1, json["upcomingShifts"]!!.jsonArray.size)
        val firstPast = json["shifts"]!!.jsonArray[0].jsonObject
        assertEquals(now - 3 * day, firstPast["date"]!!.jsonPrimitive.long)
        assertTrue(json["active"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun walletShowsBalanceAndOnlyTheFiveLatestOwnConfirmedTransfers() {
        val transfers = (1L..8L).map { transfer(-2.0, it) } +
            transfer(50.0, 20, type = AccountTransferType.SHIFT_CREDIT) +
            transfer(-1000.0, 1, state = AccountTransferSyncState.REJECTED) +
            transfer(-30.0, 1, holderId = "someone-else")
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input(transfers = transfers)).payload).jsonObject
        val wallet = json["wallet"]!!.jsonObject
        assertEquals(34.0, wallet["balance"]!!.jsonPrimitive.double)
        assertEquals("CHF", wallet["currency"]!!.jsonPrimitive.content)
        val list = wallet["transfers"]!!.jsonArray
        assertEquals(5, list.size)
        val dates = list.map { it.jsonObject["at"]!!.jsonPrimitive.long }
        assertEquals(dates.sortedDescending(), dates)
        assertTrue(list.none { it.jsonObject["amount"]!!.jsonPrimitive.double == -1000.0 })
        assertTrue(list.none { it.jsonObject["amount"]!!.jsonPrimitive.double == -30.0 })
    }

    @Test
    fun adminVolunteersCannotRequestTheirQrByMail() {
        val admin = PortalProjectionBuilder.build(input(v = volunteer.copy(isAdmin = true))).payload
        val regular = PortalProjectionBuilder.build(input()).payload
        assertFalse(Json.parseToJsonElement(admin).jsonObject["qrEmailAllowed"]!!.jsonPrimitive.boolean)
        assertTrue(Json.parseToJsonElement(regular).jsonObject["qrEmailAllowed"]!!.jsonPrimitive.boolean)
        val noEmail = PortalProjectionBuilder.build(input(v = volunteer.copy(email = ""))).payload
        assertFalse(Json.parseToJsonElement(noEmail).jsonObject["qrEmailAllowed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun hashIsStableAndFollowsContent() {
        val a = PortalProjectionBuilder.build(input())
        val b = PortalProjectionBuilder.build(input())
        val c = PortalProjectionBuilder.build(input(v = volunteer.copy(phoneNumber = "+41 79 000 00 00")))
        assertEquals(a.contentHash, b.contentHash)
        assertNotEquals(a.contentHash, c.contentHash)
        assertEquals(64, a.contentHash.length)
        val map = a.toFirestoreMap(updatedAt = 42L)
        assertEquals(setOf("schema", "payload", "contentHash", "updatedAt"), map.keys)
    }
}
