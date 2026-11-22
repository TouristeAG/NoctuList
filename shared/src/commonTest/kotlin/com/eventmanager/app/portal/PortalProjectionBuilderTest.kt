package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.VolunteerRank
import com.eventmanager.app.data.utils.DateTimeUtils
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
        at: Long = now,
        configs: List<JobTypeConfig> = emptyList(),
        googleLinkAllowed: Boolean = false,
        offset: Int = 0,
        certificateEnabled: Boolean = false,
    ) = PortalProjectionInput(
        volunteer = v,
        jobs = jobs,
        transfers = transfers,
        jobTypeConfigs = configs,
        currencyCode = "CHF",
        associationName = "Asso",
        hasPhoto = true,
        photoVersion = "abc",
        mustSetPassword = false,
        googleLinkAllowed = googleLinkAllowed,
        certificateEnabled = certificateEnabled,
        now = at,
        offsetHours = offset,
    )

    @Test
    fun pageListsEveryPastJobTypeWithItsDays() {
        val jobs = (1..50).map { job(-it.toLong(), "Bar") } + (51..70).map { job(-it.toLong(), "Entrée") } + job(4, "Bar")
        val root = Json.parseToJsonElement(
            PortalProjectionBuilder.build(input(jobs = jobs, certificateEnabled = true)).payload,
        ).jsonObject
        val types = root["certificate"]!!.jsonObject["types"]!!.jsonArray
        assertEquals(2, types.size)
        assertEquals(70, types.sumOf { it.jsonObject["days"]!!.jsonArray.size })
        // Types by first day: Entrée starts 70 days ago, Bar 50 days ago.
        assertEquals(listOf("Entrée", "Bar"), types.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("OTHER", types[0].jsonObject["category"]!!.jsonPrimitive.content)
        val days = types[1].jsonObject["days"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(days.sorted().distinct(), days)
        assertTrue(days.all { it in 20_000_000..29_999_999 })
    }

    @Test
    fun pageHasNoCertificateFieldWhenOff() {
        val root = Json.parseToJsonElement(PortalProjectionBuilder.build(input()).payload).jsonObject
        assertFalse("certificate" in root)
    }

    @Test
    fun pageCertificateNeverContainsTheVolunteerId() {
        val payload = PortalProjectionBuilder.build(input(certificateEnabled = true)).payload
        val certificate = Json.parseToJsonElement(payload).jsonObject["certificate"].toString()
        assertFalse(certificate.contains(secretId))
    }

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
    fun publishesProfileAndAllShiftsInOneList() {
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input()).payload).jsonObject
        assertEquals("Léa", json["firstName"]!!.jsonPrimitive.content)
        assertEquals("MARTIN", json["lastName"]!!.jsonPrimitive.content)
        assertEquals("FEMALE", json["gender"]!!.jsonPrimitive.content)
        assertEquals(3, json["shifts"]!!.jsonArray.size)
        assertEquals(0L, json["lastShiftFloor"]!!.jsonPrimitive.long)
        assertEquals(0L, json["olderShifts"]!!.jsonPrimitive.long)
        // Same order as minimalProjection (webform/functions/src/common.ts): it feeds the hash.
        assertEquals(
            listOf(
                "firstName", "lastName", "email", "phone", "dateOfBirth", "gender", "hasPhoto", "photoVersion",
                "shifts", "olderShifts", "lastShiftFloor", "timeline", "wallet", "qrEmailAllowed", "mustSetPassword",
                "association", "googleEmail", "googleLinkedBy", "googleLinkAvailable",
            ),
            json.keys.toList(),
        )
        val step = json["timeline"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("from", "active", "summary", "benefits", "uniqueEntriesRemaining"), step.keys)
    }

    @Test
    fun payloadHasNoRank() {
        val payload = PortalProjectionBuilder.build(input()).payload
        assertFalse(payload.contains("\"rank\""), "rank key published")
        VolunteerRank.values().forEach { assertFalse(payload.contains(it.name), "rank ${it.name} published") }
        assertEquals(2, PortalProjection.SCHEMA)
    }

    @Test
    fun shiftsHoldPastAndUpcoming() {
        val jobs = listOf(job(-30), job(-20), job(-10), job(3), job(6))
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input(jobs = jobs)).payload).jsonObject
        val shifts = json["shifts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, shifts.size)
        val past = shifts.filter { it["date"]!!.jsonPrimitive.long < now - day }
        assertEquals(3, past.size)
        assertTrue(past.none { "gains" in it }, "past shifts must not carry gains")
        shifts.forEach { assertTrue(it.keys.all { k -> k in setOf("date", "pastFrom", "jobTypeName", "venueName", "shiftTime", "gains") }) }
    }

    private val barConfig = JobTypeConfig(
        name = "Bar", isShiftJob = true, novaJobType = NovaJobType.DEFAULT_SHIFT, requiresShiftTime = true,
    )

    @Test
    fun gainsOnlyOnUpcomingShiftsThatBringSomethingNew() {
        // "Plain" has no config, so it brings no benefit; the Bar shift brings same-night free entry.
        val past = job(-5)
        val upcomingBar = job(2)
        val upcomingPlain = job(4, "Plain")
        val json = Json.parseToJsonElement(
            PortalProjectionBuilder.build(input(jobs = listOf(past, upcomingBar, upcomingPlain), configs = listOf(barConfig))).payload,
        ).jsonObject
        val byDate = json["shifts"]!!.jsonArray.map { it.jsonObject }.associateBy { it["date"]!!.jsonPrimitive.long }
        val bar = byDate.getValue(upcomingBar.date)
        val gains = bar["gains"]!!.jsonArray
        assertTrue(gains.isNotEmpty())
        assertTrue(gains.any { it.jsonObject["freeEntry"]!!.jsonPrimitive.boolean }, "free entry gain expected")
        assertFalse(gains.toString().contains("\"rank\""))
        assertFalse("gains" in byDate.getValue(upcomingPlain.date), "nothing new, no gains key")
        assertFalse("gains" in byDate.getValue(past.date), "past shifts never carry gains")
    }

    @Test
    fun lastShiftFloorFollowsVolunteerLastShiftDate() {
        val json = Json.parseToJsonElement(
            PortalProjectionBuilder.build(input(v = volunteer.copy(lastShiftDate = 12345L))).payload,
        ).jsonObject
        assertEquals(12345L, json["lastShiftFloor"]!!.jsonPrimitive.long)
    }

    @Test
    fun pastShiftsCappedAt60AndUpcomingAt10() {
        val jobs = (1..80).map { job(-it.toLong() - 1) } + (1..15).map { job(it.toLong() + 1) }
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input(jobs = jobs)).payload).jsonObject
        val dates = json["shifts"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonPrimitive.long }
        assertEquals(70, dates.size)
        assertEquals(60, dates.count { it < now - day })
        assertEquals(10, dates.count { it > now })
        // Newest 60 past kept: the oldest (80 days ago) is gone, 61 days ago is the oldest kept.
        assertTrue(dates.none { it == now - 81 * day })
        assertTrue(dates.any { it == now - 61 * day })
        // Earliest 10 upcoming kept.
        assertTrue(dates.any { it == now + 11 * day })
        assertTrue(dates.none { it == now + 12 * day })
    }

    @Test
    fun olderShiftsCountsPastShiftsLeftOutByTheCap() {
        val jobs = (1..65).map { job(-it.toLong()) } + (1..3).map { job(it.toLong()) }
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input(jobs = jobs)).payload).jsonObject
        val dates = json["shifts"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonPrimitive.long }
        assertEquals(60, dates.count { it < now })
        assertEquals(3, dates.count { it > now })
        assertEquals(5L, json["olderShifts"]!!.jsonPrimitive.long)
    }

    @Test
    fun earlierTodayIsNotAnOlderShift() {
        // Day-aligned like the split: a shift earlier today is upcoming, so 60 strictly past days fit.
        val jobs = (1..60).map { job(-it.toLong()) } + Job(
            volunteerId = secretId,
            jobType = JobType.BAR,
            jobTypeName = "Bar",
            venueName = "Le Terreau",
            date = now - 60_000L,
            shiftTime = ShiftTime.BEFORE_MIDNIGHT,
        )
        val json = Json.parseToJsonElement(PortalProjectionBuilder.build(input(jobs = jobs)).payload).jsonObject
        assertEquals(61, json["shifts"]!!.jsonArray.size)
        assertEquals(0L, json["olderShifts"]!!.jsonPrimitive.long)
    }

    @Test
    fun hashStableWithinOneDay() {
        val base = DateTimeUtils.getStartOfDayWithOffset(now, 0)
        val morning = base + 9 * 60 * 60 * 1000
        val evening = base + 21 * 60 * 60 * 1000
        val jobs = listOf(
            job(-3), job(-10, "Entrée"), job(5),
            Job(
                volunteerId = secretId, jobType = JobType.BAR, jobTypeName = "Bar", venueName = "Le Terreau",
                date = base + 20 * 60 * 60 * 1000, shiftTime = ShiftTime.BEFORE_MIDNIGHT,
            ),
        )
        val a = PortalProjectionBuilder.build(input(jobs = jobs, at = morning))
        val b = PortalProjectionBuilder.build(input(jobs = jobs, at = evening))
        assertEquals(a.contentHash, b.contentHash)
        // Same with benefits in play (a past shift with a validUntil, a later shift today).
        val withBar = PortalProjectionBuilder.build(input(jobs = jobs, at = morning, configs = listOf(barConfig)))
        val withBarLater = PortalProjectionBuilder.build(input(jobs = jobs, at = evening, configs = listOf(barConfig)))
        assertTrue(withBar.payload.contains("\"freeEntry\":true"), "benefits must be present in this case")
        assertEquals(withBar.contentHash, withBarLater.contentHash)
    }

    @Test
    fun hashDoesNotChangeNextDayWithoutJobs() {
        val a = PortalProjectionBuilder.build(input(jobs = emptyList(), at = now))
        val b = PortalProjectionBuilder.build(input(jobs = emptyList(), at = now + day))
        assertEquals(a.contentHash, b.contentHash)
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

    @Test
    fun publishesTheGoogleLinkState() {
        fun json(v: Volunteer, allowed: Boolean) =
            Json.parseToJsonElement(PortalProjectionBuilder.build(input(v = v, googleLinkAllowed = allowed)).payload).jsonObject

        val unlinked = json(volunteer, allowed = true)
        assertEquals("", unlinked["googleEmail"]!!.jsonPrimitive.content)
        assertEquals("", unlinked["googleLinkedBy"]!!.jsonPrimitive.content)
        assertTrue(unlinked["googleLinkAvailable"]!!.jsonPrimitive.boolean)
        assertFalse(json(volunteer, allowed = false)["googleLinkAvailable"]!!.jsonPrimitive.boolean)

        val linked = volunteer.copy(
            portalGoogleEmail = " jean@asso.ch ",
            portalGoogleUid = "GoogleUidNeverPublished",
            portalGoogleLinkedBy = Volunteer.GOOGLE_LINKED_BY_VOLUNTEER,
        )
        val payload = PortalProjectionBuilder.build(input(v = linked, googleLinkAllowed = true)).payload
        val linkedJson = json(linked, allowed = true)
        assertEquals("jean@asso.ch", linkedJson["googleEmail"]!!.jsonPrimitive.content)
        assertEquals("VOLUNTEER", linkedJson["googleLinkedBy"]!!.jsonPrimitive.content)
        assertFalse(linkedJson["googleLinkAvailable"]!!.jsonPrimitive.boolean)
        assertFalse(payload.contains("GoogleUidNeverPublished"), "Google uid leaked")
    }

    @Test
    fun volunteerProjectionUnchanged() {
        // Fixed reference input; the literal was recorded when shifts gained pastFrom (synced day change).
        val transfers = listOf(
            transfer(-2.0, 1), transfer(50.0, 20, type = AccountTransferType.SHIFT_CREDIT),
            transfer(-30.0, 2, holderId = "someone-else"),
        )
        val projection = PortalProjectionBuilder.build(input(transfers = transfers))
        assertEquals("1a49adcf70c1d949939043e3dadb2dd72ffefc1262968c363926c9a5cbf577e1", projection.contentHash)
        assertEquals(2, projection.toFirestoreMap(1L)["schema"])
    }

    @Test
    fun tonightsShiftStaysUpcomingUntilTheDayChange() {
        // Friday's shift, read at 3:00 on Saturday with the day changing at 6:00: still tonight's.
        val friday = DateTimeUtils.getStartOfDayWithOffset(now, 0)
        val shift = Job(
            volunteerId = secretId, jobType = JobType.BAR, jobTypeName = "Bar", venueName = "Le Terreau",
            date = friday, shiftTime = ShiftTime.AFTER_MIDNIGHT,
        )
        val hour = 3_600_000L
        val dayEnd = DateTimeUtils.getEndOfDayWithOffset(friday, 6).timeInMillis + 1
        assertEquals(dayEnd, PortalProjectionBuilder.pastFrom(friday, 6))
        val later = job(2)
        val older = job(-3)
        fun shifts(at: Long) = Json.parseToJsonElement(
            PortalProjectionBuilder.build(input(jobs = listOf(older, later, shift), at = at, offset = 6)).payload,
        ).jsonObject["shifts"]!!.jsonArray.map { it.jsonObject }
        // Upcoming first (ascending), then past: tonight's shift leads while the night lasts.
        val atThree = shifts(dayEnd - 3 * hour)
        assertEquals(listOf(friday, later.date, older.date), atThree.map { it["date"]!!.jsonPrimitive.long })
        assertEquals(dayEnd, atThree.first()["pastFrom"]!!.jsonPrimitive.long)
        assertEquals(listOf(later.date, friday, older.date), shifts(dayEnd).map { it["date"]!!.jsonPrimitive.long })
        assertEquals(friday + 30 * hour, dayEnd, "next midnight plus 6 h")
    }
}
