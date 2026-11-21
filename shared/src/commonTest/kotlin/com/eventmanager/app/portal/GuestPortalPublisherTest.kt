package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ORG = "org-a"
private const val UID = "PortalUid000000000000001"
private const val OTHER_UID = "PortalUid000000000000002"
private const val TEMP_UID = "PortalUid000000000000003"
private const val DAY = 86_400_000L

class GuestPortalPublisherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = 1_800_000_000_000L
    @Volatile private var now = start

    @AfterTest
    fun tearDown() = scope.cancel()

    private val volunteerPhoto = byteArrayOf(1, 1, 1)
    private val guestPhoto = byteArrayOf(2, 2, 2)

    private fun guest(uid: String = UID, nanoId: String = "GuestNano0000000000001", photo: Boolean = false) = Guest(
        nanoId = nanoId,
        name = "Max",
        lastNameAbbreviation = "DUPONT",
        // Another address than the volunteer on the same account: the merge must never split it.
        email = "max@example.org",
        invitations = 2,
        venueName = "Club",
        firebaseOrgId = ORG,
        portalEnabled = true,
        portalUid = uid,
        profilePhotoUrl = if (photo) "https://photos/guest.jpg" else "",
        lastModified = start,
    )

    private fun temp(name: String, nanoId: String, eventDate: Long = start) = Guest(
        nanoId = nanoId,
        name = name,
        invitations = 1,
        venueName = "Club",
        isTemporaryGuest = true,
        temporaryArtistName = "Band",
        temporaryEventDate = eventDate,
        temporaryContactEmail = "manager@example.org",
        firebaseOrgId = ORG,
        portalEnabled = true,
        portalUid = TEMP_UID,
        lastModified = start,
    )

    private fun volunteer(uid: String = UID, photo: Boolean = false) = Volunteer(
        id = "V1",
        name = "Léa",
        lastNameAbbreviation = "MARTIN",
        email = "lea@example.org",
        phoneNumber = "",
        portalEnabled = true,
        portalUid = uid,
        firebaseOrgId = ORG,
        profilePhotoUrl = if (photo) "https://photos/volunteer.jpg" else "",
    )

    private class Running(
        val service: VolunteerPortalService,
        val volunteers: MutableStateFlow<List<Volunteer>>,
        val guests: MutableStateFlow<List<Guest>>,
    )

    private fun run(gateway: FakeGateway, host: FakeHost, retryMs: Long = 60_000L): Running {
        val service = VolunteerPortalService(
            ORG, gateway, host, scope, authClientFactory = { FakeAuth(PortalAuthResult.Sent) },
            clock = { now }, publishDebounceMs = 0, retryIntervalMs = retryMs,
        )
        val volunteers = MutableStateFlow(host.volunteers.toList())
        val guests = MutableStateFlow(host.guests.toList())
        service.start(volunteers, guests, MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        return Running(service, volunteers, guests)
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    @Test
    fun publishesGuestPageAndQr() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true; guests += guest() }
        run(gateway, host)
        until("guest page") { gateway.docs["guestPortal/$UID"] != null && gateway.docs["guestPortalQr/$UID"] != null }
        val page = gateway.docs["guestPortal/$UID"]!!
        assertTrue(page["payload"].toString().contains("\"firstName\":\"Max\""))
        assertFalse(page["payload"].toString().contains("GuestNano0000000000001"), "nanoId never on the page")
        assertEquals("GuestNano0000000000001", gateway.docs["guestPortalQr/$UID"]!!["payload"])
        assertNull(gateway.docs["volunteerPortal/$UID"])
    }

    @Test
    fun guestSpaceOffDeletesGuestPages() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true; guests += guest() }
        val running = run(gateway, host)
        until("guest page") { gateway.docs["guestPortalQr/$UID"] != null }
        host.guestSpace = false
        running.service.republish()
        assertNull(gateway.docs["guestPortal/$UID"])
        assertNull(gateway.docs["guestPortalQr/$UID"])
        assertTrue(host.loadPublishedHashes().keys.none { it.endsWith("#guest") || it.endsWith("#guestQr") })
    }

    @Test
    fun tempSpaceOffDeletesTempPages() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply {
            tempSpace = true
            guests += temp("Ana", "TempNano00000000000001")
            guests += temp("Bob", "TempNano00000000000002")
        }
        val running = run(gateway, host)
        until("temp page") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] != null && gateway.docs["temporaryGuestPortalQr/$TEMP_UID"] != null }
        val page = gateway.docs["temporaryGuestPortal/$TEMP_UID"]!!
        assertEquals(TemporaryGuestPortalProjectionBuilder.visibleUntil(start), page["expiresAtMillis"])
        @Suppress("UNCHECKED_CAST")
        val items = gateway.docs["temporaryGuestPortalQr/$TEMP_UID"]!!["items"] as Map<String, Any?>
        assertEquals(2, items.size)
        host.tempSpace = false
        running.service.republish()
        assertNull(gateway.docs["temporaryGuestPortal/$TEMP_UID"])
        assertNull(gateway.docs["temporaryGuestPortalQr/$TEMP_UID"])
    }

    @Test
    fun revokingGuestKeepsVolunteerPage() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer(photo = true))).apply {
            guestSpace = true
            guests += guest(photo = true)
            volunteerPhotos["V1"] = volunteerPhoto
            guestPhotos["GuestNano0000000000001"] = guestPhoto
        }
        val running = run(gateway, host)
        val photoPath = "orgs/$ORG/portalPhotos/$UID.jpg"
        until("both pages") { gateway.docs["volunteerPortal/$UID"] != null && gateway.docs["guestPortal/$UID"] != null }
        assertContentEquals(volunteerPhoto, host.storage[photoPath], "the volunteer photo wins")
        // As revokeGuestAccess leaves it: off, unlinked and marked revoked, so the merge leaves it alone.
        running.guests.value = listOf(guest(photo = true).copy(portalEnabled = false, portalUid = "", portalRevoked = true))
        until("guest page gone") { gateway.docs["guestPortal/$UID"] == null }
        running.service.republish()
        assertNotNull(gateway.docs["volunteerPortal/$UID"])
        assertContentEquals(volunteerPhoto, host.storage[photoPath])
    }

    @Test
    fun guestOnlyAccountPublishesGuestPhoto() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply {
            guestSpace = true
            guests += guest(photo = true)
            guestPhotos["GuestNano0000000000001"] = guestPhoto
        }
        run(gateway, host)
        until("guest page") { gateway.docs["guestPortal/$UID"] != null }
        assertContentEquals(guestPhoto, host.storage["orgs/$ORG/portalPhotos/$UID.jpg"])
        assertTrue(gateway.docs["guestPortal/$UID"]!!["payload"].toString().contains("\"hasPhoto\":true"))
    }

    @Test
    fun closedTempGroupKeepingItsUidPublishesNoPage() = runBlocking {
        val gateway = FakeGateway()
        val ana = temp("Ana", "TempNano00000000000001")
        val host = FakeHost(emptyList()).apply { tempSpace = true; guests += ana }
        val running = run(gateway, host)
        until("temp page") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] != null }
        running.guests.value = listOf(ana.copy(portalEnabled = false))
        until("temp page gone") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] == null }
        running.service.republish()
        assertNull(gateway.docs["temporaryGuestPortal/$TEMP_UID"])
        assertNull(gateway.docs["temporaryGuestPortalQr/$TEMP_UID"])
    }

    @Test
    fun republishesWhenAListEnds() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply {
            tempSpace = true
            // The app's day changes at 10:00, so reaching 8:00 the next day is not a new day.
            dateOffset = 10
            guests += temp("Ana", "TempNano00000000000001")
        }
        run(gateway, host, retryMs = 50L)
        until("temp page") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] != null }
        now = TemporaryGuestPortalProjectionBuilder.visibleUntil(start)
        until("page removed at 8:00") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] == null }
        assertNull(gateway.docs["temporaryGuestPortalQr/$TEMP_UID"])
    }

    @Test
    fun tempGroupEndedDeletesPage() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { tempSpace = true; guests += temp("Ana", "TempNano00000000000001") }
        val running = run(gateway, host)
        until("temp page") { gateway.docs["temporaryGuestPortalQr/$TEMP_UID"] != null }
        now = TemporaryGuestPortalProjectionBuilder.visibleUntil(start) + 1
        running.service.republish()
        assertNull(gateway.docs["temporaryGuestPortal/$TEMP_UID"])
        assertNull(gateway.docs["temporaryGuestPortalQr/$TEMP_UID"])
    }

    @Test
    fun unchangedGuestNotRewritten() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true; guests += guest() }
        val running = run(gateway, host)
        until("guest page") { gateway.docs["guestPortalQr/$UID"] != null }
        running.service.republish()
        running.service.republish()
        assertEquals(1, gateway.writes.count { it == "guestPortal/$UID" })
        assertEquals(1, gateway.writes.count { it == "guestPortalQr/$UID" })
    }

    @Test
    fun volunteerStaleCleanupIgnoresGuestKeys() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer(uid = OTHER_UID))).apply {
            guestSpace = true
            guests += guest(photo = true)
            guestPhotos["GuestNano0000000000001"] = guestPhoto
        }
        val running = run(gateway, host)
        until("pages") { gateway.docs["volunteerPortal/$OTHER_UID"] != null && gateway.docs["guestPortal/$UID"] != null }
        running.volunteers.value = listOf(volunteer(uid = OTHER_UID).copy(portalEnabled = false, portalUid = ""))
        until("volunteer page gone") { gateway.docs["volunteerPortal/$OTHER_UID"] == null }
        assertNotNull(gateway.docs["guestPortal/$UID"])
        assertNotNull(gateway.docs["guestPortalQr/$UID"])
        assertContentEquals(guestPhoto, host.storage["orgs/$ORG/portalPhotos/$UID.jpg"])
        assertFalse("guestPortal/$UID" in gateway.deleted)
    }

    @Test
    fun volunteerWithoutPhotoLeavesTheAccountPhotoToTheGuest() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer(photo = false))).apply {
            guestSpace = true
            guests += guest(photo = true)
            guestPhotos["GuestNano0000000000001"] = guestPhoto
        }
        val running = run(gateway, host)
        val photoPath = "orgs/$ORG/portalPhotos/$UID.jpg"
        until("both pages") { gateway.docs["guestPortal/$UID"] != null && gateway.docs["volunteerPortal/$UID"] != null }
        assertContentEquals(guestPhoto, host.storage[photoPath])
        assertTrue(gateway.docs["volunteerPortal/$UID"]!!["payload"].toString().contains("\"hasPhoto\":true"))
        assertTrue(gateway.docs["guestPortal/$UID"]!!["payload"].toString().contains("\"hasPhoto\":true"))
        val uploads = host.storageUploads.size
        val deletes = host.storageDeletes.size
        running.service.republish()
        running.service.republish()
        assertEquals(uploads, host.storageUploads.size, "no re-upload")
        assertEquals(deletes, host.storageDeletes.size, "no delete")
        assertContentEquals(guestPhoto, host.storage[photoPath])
    }

    @Test
    fun volunteerPhotoReplacesTheGuestPhotoOnce() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer(photo = true))).apply {
            guestSpace = true
            guests += guest(photo = true)
            volunteerPhotos["V1"] = volunteerPhoto
            guestPhotos["GuestNano0000000000001"] = guestPhoto
        }
        val running = run(gateway, host)
        val photoPath = "orgs/$ORG/portalPhotos/$UID.jpg"
        until("both pages") { gateway.docs["guestPortal/$UID"] != null && gateway.docs["volunteerPortal/$UID"] != null }
        assertContentEquals(volunteerPhoto, host.storage[photoPath])
        val uploads = host.storageUploads.size
        running.service.republish()
        assertEquals(uploads, host.storageUploads.size)
        assertTrue(host.storageDeletes.isEmpty())
    }

    @Test
    fun unpublishAllRemovesGuestPages() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply {
            guestSpace = true
            tempSpace = true
            guests += guest(photo = true)
            guests += temp("Ana", "TempNano00000000000001")
            guestPhotos["GuestNano0000000000001"] = guestPhoto
        }
        val running = run(gateway, host)
        until("pages") { gateway.docs["guestPortalQr/$UID"] != null && gateway.docs["temporaryGuestPortalQr/$TEMP_UID"] != null }
        running.service.unpublishAll()
        assertNull(gateway.docs["guestPortal/$UID"])
        assertNull(gateway.docs["guestPortalQr/$UID"])
        assertNull(gateway.docs["temporaryGuestPortal/$TEMP_UID"])
        assertNull(gateway.docs["temporaryGuestPortalQr/$TEMP_UID"])
        assertNull(host.storage["orgs/$ORG/portalPhotos/$UID.jpg"])
        assertTrue(host.loadPublishedHashes().keys.none { it.startsWith("$ORG/") })
    }

    @Test
    fun newLocalDayRepublishesWithoutAnyChange() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { tempSpace = true; guests += temp("Ana", "TempNano00000000000001") }
        run(gateway, host, retryMs = 50)
        until("temp page") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] != null }
        now = start + 4 * DAY
        until("ended group removed by the daily republish") { gateway.docs["temporaryGuestPortal/$TEMP_UID"] == null }
    }
}
