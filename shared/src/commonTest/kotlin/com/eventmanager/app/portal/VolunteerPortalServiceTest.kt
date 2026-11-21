package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.NoOpFirestoreGateway
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ORG = "org-a"
private const val UID = "PortalUid000000000000001"
private const val GOOGLE_UID = "GoogleUid000000000000001"
private const val LINK_CODE = "abcdefghijklmnopqrstuvwxyz0123456789_-AB"

/** In-memory Firestore: documents per "collection/id", listeners fed by the test. */
private class FakeGateway : NoOpFirestoreGateway() {
    val docs = mutableMapOf<String, Map<String, Any?>>()
    val deleted = mutableListOf<String>()
    val listeners = mutableMapOf<String, suspend (List<Pair<String, Map<String, Any?>>>) -> Unit>()
    var claimsRefused = false
    var qrWrites = 0
    var qrDeleteFailures = 0

    override fun isAvailable() = true

    override suspend fun setPlainDocument(orgId: String, collection: String, docId: String, data: Map<String, Any?>, merge: Boolean) {
        val key = "$collection/$docId"
        if (collection == "volunteerPortalQr") qrWrites++
        docs[key] = if (merge) (docs[key].orEmpty() + data) else data
    }

    override suspend fun deleteDocument(orgId: String, collection: String, docId: String) {
        if (collection == "volunteerPortalQr" && qrDeleteFailures > 0) {
            qrDeleteFailures--
            throw IllegalStateException("offline")
        }
        docs.remove("$collection/$docId")
        deleted += "$collection/$docId"
    }

    override suspend fun getDocumentFromServer(orgId: String, collection: String, docId: String) = docs["$collection/$docId"]

    override fun listenCollection(
        orgId: String,
        collection: String,
        onSnapshot: suspend (List<Pair<String, Map<String, Any?>>>) -> Unit,
    ): AutoCloseable {
        listeners[collection] = onSnapshot
        return AutoCloseable { listeners.remove(collection) }
    }

    override suspend fun claimDocument(
        orgId: String,
        collection: String,
        docId: String,
        claimable: (Map<String, Any?>) -> Boolean,
        claimFields: Map<String, Any?>,
    ): Map<String, Any?>? {
        if (claimsRefused) return null
        val key = "$collection/$docId"
        val current = docs[key] ?: return null
        if (!claimable(current)) return null
        docs[key] = current + claimFields
        return current
    }

    suspend fun push(collection: String) {
        val prefix = "$collection/"
        val snapshot = docs.filterKeys { it.startsWith(prefix) }.map { (k, v) -> k.removePrefix(prefix) to v }
        listeners[collection]!!.invoke(snapshot)
    }
}

private class FakeHost(initial: List<Volunteer>) : VolunteerPortalHost {
    val volunteers = initial.toMutableList()
    val created = mutableListOf<Volunteer>()
    val mailed = mutableListOf<Volunteer>()
    val storage = mutableMapOf<String, ByteArray>()
    var canMail = true
    var mailSubject = "Your QR code"
    var mailTemplateEnabled = true
    var googleEnabled = true
    var qrEnabled = true
    var googleDomains = listOf("asso.ch")
    private var pending = emptyList<VolunteerSignupForm>()
    private var hashes = emptyMap<String, String>()

    override val deviceId = "device-1"
    override fun apiKey() = "key"
    override fun currencyCode() = "CHF"
    override fun associationName() = "Asso"
    override fun dateOffsetHours() = 0
    override fun institutionLogoPng() = ""
    override fun profilePhotosEnabled() = true
    override fun portalGoogleEnabled() = googleEnabled
    override fun portalGoogleDomains() = googleDomains
    var googleTemplate = ""
    var googleAny = false
    override fun portalGoogleAnyAccount() = googleAny
    override fun portalGoogleAddressTemplate() = googleTemplate
    override fun portalQrEnabled() = qrEnabled
    override suspend fun volunteers() = volunteers.toList()
    override suspend fun createVolunteer(volunteer: Volunteer, photo: ByteArray?): Volunteer {
        created += volunteer
        volunteers += volunteer
        return volunteer
    }
    override suspend fun saveVolunteer(volunteer: Volunteer): Boolean {
        val i = volunteers.indexOfFirst { it.id == volunteer.id }
        if (i < 0) return false
        volunteers[i] = volunteer
        return true
    }
    val attached = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun attachVolunteerPhoto(volunteer: Volunteer, bytes: ByteArray): Boolean {
        attached += volunteer.id to bytes
        return true
    }
    override suspend fun downloadStorage(path: String) = storage[path]
    override suspend fun uploadStorageJpeg(path: String, bytes: ByteArray): Boolean {
        storage[path] = bytes
        return true
    }
    override suspend fun deleteStorage(path: String) {
        storage.remove(path)
    }
    override suspend fun volunteerPhotoBytes(volunteer: Volunteer): ByteArray? = null
    override suspend fun portalMailTemplate(): PortalMailTemplate? =
        if (mailTemplateEnabled) PortalMailTemplate(mailSubject, "<p>Hello $PORTAL_FIRST_NAME_TOKEN</p>", "Hello $PORTAL_FIRST_NAME_TOKEN", "") else null
    override fun canSendQrEmail() = canMail
    override suspend fun sendQrEmail(volunteer: Volunteer): Boolean {
        mailed += volunteer
        return true
    }
    override fun loadPendingSignupForms() = pending
    override fun savePendingSignupForms(forms: List<VolunteerSignupForm>) {
        pending = forms
    }
    override fun loadPublishedHashes() = hashes
    override fun savePublishedHashes(hashes: Map<String, String>) {
        this.hashes = hashes
    }
}

private class FakeAuth(private val create: PortalAuthResult) : PortalAuthApi {
    val resets = mutableListOf<String>()
    override suspend fun createAccount(email: String, password: String) = create
    override suspend fun sendPasswordReset(email: String, languageCode: String): PortalAuthResult {
        resets += email
        return PortalAuthResult.Sent
    }
}

class VolunteerPortalServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun volunteer(id: String, email: String, enabled: Boolean = false, uid: String = "", admin: Boolean = false) =
        Volunteer(
            id = id,
            name = "Léa",
            lastNameAbbreviation = "MARTIN",
            email = email,
            phoneNumber = "+41 79 000 00 00",
            portalEnabled = enabled,
            portalUid = uid,
            isAdmin = admin,
            firebaseOrgId = ORG,
        )

    private fun service(gateway: FakeGateway, host: FakeHost, auth: PortalAuthApi = FakeAuth(PortalAuthResult.Sent)) =
        VolunteerPortalService(ORG, gateway, host, scope, authClientFactory = { auth }, publishDebounceMs = 0)

    private fun startedService(gateway: FakeGateway, host: FakeHost, auth: PortalAuthApi = FakeAuth(PortalAuthResult.Sent)) =
        service(gateway, host, auth).also {
            it.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Job>()),
                MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        }

    private fun submittedForm(email: String = "max@example.org", photoPath: String = "") = mapOf(
        "status" to "SUBMITTED",
        "submittedUid" to UID,
        "email" to email,
        "firstName" to "Max",
        "lastName" to "Dupont",
        "phone" to "+41 78 555 12 34",
        "dateOfBirth" to "1999-07-21",
        "gender" to "MALE",
        "photoPath" to photoPath,
        "consent" to true,
        "lang" to "fr",
    )

    @Test
    fun serverStatusDocShowsUpInServerStatus() = runBlocking {
        val gateway = FakeGateway()
        val service = startedService(gateway, FakeHost(emptyList()))
        assertEquals(null, service.serverStatus.value)
        gateway.docs["portalServer/status"] = mapOf("version" to "3", "lastRunAt" to 1_700_000_000_000L)
        gateway.push("portalServer")
        assertEquals(PortalServerStatus("3", 1_700_000_000_000L), service.serverStatus.value)
        gateway.docs.remove("portalServer/status")
        gateway.push("portalServer")
        assertEquals(null, service.serverStatus.value)
    }

    @Test
    fun freshRequestIsLeftToServer() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        val key = "portalRequests/${UID}_PROFILE"
        gateway.docs[key] = mapOf(
            "uid" to UID, "type" to "PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to "Neo", "lastName" to "X", "phone" to "+41 79 999 99 99",
            "requestedAt" to System.currentTimeMillis() - 30_000,
        )
        gateway.push("portalRequests")
        assertEquals("PENDING", gateway.docs[key]!!["status"])
        assertEquals("Léa", host.volunteers.single().name)
    }

    @Test
    fun oldRequestIsProcessedByDevice() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        val key = "portalRequests/${UID}_PROFILE"
        gateway.docs[key] = mapOf(
            "uid" to UID, "type" to "PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to "Neo", "lastName" to "X", "phone" to "+41 79 999 99 99",
            "requestedAt" to System.currentTimeMillis() - 150_000,
        )
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
    }

    @Test
    fun profileRequestWithPhotoAttachesUploadAndRemovesIt() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        val upload = "orgs/$ORG/portalPhotoUploads/$UID.jpg"
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        host.storage[upload] = jpeg
        startedService(gateway, host)
        val key = "portalRequests/${UID}_PROFILE"
        gateway.docs[key] = mapOf(
            "uid" to UID, "type" to "PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to "Neo", "lastName" to "X", "phone" to "+41 79 999 99 99", "photo" to true,
            "requestedAt" to System.currentTimeMillis() - 150_000,
        )
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("Neo", host.volunteers.single().name)
        assertEquals("V1", host.attached.single().first)
        assertTrue(host.attached.single().second.contentEquals(jpeg))
        assertFalse(upload in host.storage)
    }

    @Test
    fun profileRequestWithMissingPhotoIsRejectedWithoutChanges() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        val key = "portalRequests/${UID}_PROFILE"
        gateway.docs[key] = mapOf(
            "uid" to UID, "type" to "PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to "Neo", "lastName" to "X", "phone" to "+41 79 999 99 99", "photo" to true,
            "requestedAt" to System.currentTimeMillis() - 150_000,
        )
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("INVALID", gateway.docs[key]!!["reason"])
        assertEquals("Léa", host.volunteers.single().name)
        assertTrue(host.attached.isEmpty())
    }

    @Test
    fun freshSignupIsLeftToServer() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000004"] =
            submittedForm() + ("submittedAt" to System.currentTimeMillis() - 30_000)
        gateway.push("volunteerSignupForms")
        assertTrue(host.created.isEmpty())
        assertEquals("SUBMITTED", gateway.docs["volunteerSignupForms/form00000004"]!!["status"])
    }

    @Test
    fun oldSignupIsProcessed() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000005"] =
            submittedForm() + ("submittedAt" to System.currentTimeMillis() - 150_000)
        gateway.push("volunteerSignupForms")
        assertEquals(1, host.created.size)
    }

    @Test
    fun mailTemplatePublishedOnceAndOnChange() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        val volunteers = MutableStateFlow(host.volunteers.toList())
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalMail/default"] == null) delay(20) }
        val first = gateway.docs["portalMail/default"]!!
        assertEquals("Your QR code", first["subject"])
        assertEquals(1, first["schema"])

        gateway.docs.remove("portalMail/default")
        volunteers.value = listOf(volunteer("V9", "z@example.org"))
        delay(300)
        assertNull(gateway.docs["portalMail/default"], "unchanged template is not rewritten")

        host.mailSubject = "Another subject"
        volunteers.value = listOf(volunteer("V8", "y@example.org"))
        withTimeout(5_000) { while (gateway.docs["portalMail/default"] == null) delay(20) }
        assertEquals("Another subject", gateway.docs["portalMail/default"]!!["subject"])
    }

    @Test
    fun mailTemplateNeverContainsVolunteerId() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("SECRETID123", "lea@example.org", enabled = true, uid = UID)))
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalMail/default"] == null) delay(20) }
        assertFalse(gateway.docs["portalMail/default"]!!.values.joinToString().contains("SECRETID123"))
    }

    @Test
    fun registrationCreatesALinkedVolunteerAndCleansUp() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        startedService(gateway, host)
        val photo = "orgs/$ORG/portalSignupPhotos/form00000001.jpg"
        host.storage[photo] = byteArrayOf(1, 2, 3)
        gateway.docs["volunteerSignupForms/form00000001"] = submittedForm(photoPath = photo)
        gateway.push("volunteerSignupForms")

        val created = host.created.single()
        assertEquals("Max", created.name)
        assertEquals("DUPONT", created.lastNameAbbreviation)
        assertEquals(Gender.MALE, created.gender)
        assertEquals("1999-07-21", created.dateOfBirth)
        assertTrue(created.portalEnabled)
        assertEquals(UID, created.portalUid)
        assertFalse(created.isAdmin)
        assertNull(gateway.docs["volunteerSignupForms/form00000001"])
        assertFalse(host.storage.containsKey(photo))
    }

    @Test
    fun registrationLinksAnExistingVolunteerWithTheSameEmail() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "Max@Example.org")))
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000002"] = submittedForm()
        gateway.push("volunteerSignupForms")

        assertTrue(host.created.isEmpty())
        val linked = host.volunteers.single()
        assertEquals("V1", linked.id)
        assertEquals(UID, linked.portalUid)
        assertTrue(linked.portalEnabled)
    }

    @Test
    fun aRegistrationClaimedByAnotherDeviceIsLeftAlone() = runBlocking {
        val gateway = FakeGateway().apply { claimsRefused = true }
        val host = FakeHost(emptyList())
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000003"] = submittedForm()
        gateway.push("volunteerSignupForms")
        assertTrue(host.created.isEmpty())
    }

    @Test
    fun profileRequestUpdatesOnlyNameAndPhone() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID, admin = true)))
        startedService(gateway, host)
        gateway.docs["portalRequests/${UID}_PROFILE"] = mapOf(
            "uid" to UID, "type" to "PROFILE", "status" to "PENDING", "email" to "lea@example.org",
            "firstName" to "Léonie", "lastName" to "Martin-Durand", "phone" to "+41 79 999 99 99",
        )
        gateway.push("portalRequests")

        val v = host.volunteers.single()
        assertEquals("Léonie", v.name)
        assertEquals("MARTIN-DURAND", v.lastNameAbbreviation)
        assertEquals("+41 79 999 99 99", v.phoneNumber)
        assertEquals("lea@example.org", v.email)
        assertTrue(v.isAdmin, "admin flag untouched")
        assertEquals("DONE", gateway.docs["portalRequests/${UID}_PROFILE"]!!["status"])
    }

    @Test
    fun qrRequestsAreRefusedForAdminsAndWaitForADeviceThatCanMail() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID, admin = true)))
        host.canMail = false
        startedService(gateway, host)
        val key = "portalRequests/${UID}_QR_EMAIL"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "QR_EMAIL", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("PENDING", gateway.docs[key]!!["status"], "no mailer: left for another device")

        host.canMail = true
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("NOT_ALLOWED", gateway.docs[key]!!["reason"])
        assertTrue(host.mailed.isEmpty())
    }

    @Test
    fun qrRequestMailsTheAddressOnTheProfile() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "profile@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        gateway.docs["portalRequests/${UID}_QR_EMAIL"] =
            mapOf("uid" to UID, "type" to "QR_EMAIL", "status" to "PENDING", "email" to "login@example.org")
        gateway.push("portalRequests")
        assertEquals("profile@example.org", host.mailed.single().email)
        assertEquals("DONE", gateway.docs["portalRequests/${UID}_QR_EMAIL"]!!["status"])
    }

    @Test
    fun claimLinksOnlyAnOpenAccessWithTheVerifiedEmail() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true),
            volunteer("V2", "closed@example.org", enabled = false),
        ))
        startedService(gateway, host)
        gateway.docs["portalRequests/${UID}_CLAIM"] =
            mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals(UID, host.volunteers.first { it.id == "V1" }.portalUid)

        val other = "OtherUid0000000000000002"
        gateway.docs["portalRequests/${other}_CLAIM"] =
            mapOf("uid" to other, "type" to "CLAIM", "status" to "PENDING", "email" to "closed@example.org")
        gateway.push("portalRequests")
        assertEquals("", host.volunteers.first { it.id == "V2" }.portalUid)
        assertEquals("NO_ACCESS", gateway.docs["portalRequests/${other}_CLAIM"]!!["reason"])
    }

    @Test
    fun publishesLinkedVolunteersOnceAndTakesRevokedPagesDown() = runBlocking {
        val gateway = FakeGateway()
        val linked = volunteer("V1", "lea@example.org", enabled = true, uid = UID)
        val host = FakeHost(listOf(linked, volunteer("V2", "x@example.org")))
        val volunteers = MutableStateFlow(host.volunteers.toList())
        val service = service(gateway, host)
        service.start(volunteers, MutableStateFlow(emptyList()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))

        withTimeout(5_000) { while (gateway.docs["volunteerPortal/$UID"] == null) delay(20) }
        val doc = gateway.docs["volunteerPortal/$UID"]!!
        assertEquals(setOf("schema", "payload", "contentHash", "updatedAt"), doc.keys)
        assertFalse((doc["payload"] as String).contains("V1"), "wallet id must never be published")
        assertEquals(1, gateway.docs.keys.count { it.startsWith("volunteerPortal/") })
        assertTrue(gateway.docs.containsKey("portalBranding/default"))

        volunteers.value = listOf(linked.copy(portalEnabled = false, portalUid = ""))
        withTimeout(5_000) { while (gateway.docs["volunteerPortal/$UID"] != null) delay(20) }
        assertTrue("volunteerPortal/$UID" in gateway.deleted)
    }

    private suspend fun awaitDoc(gateway: FakeGateway, key: String) =
        withTimeout(5_000) { while (gateway.docs[key] == null) delay(20) }

    @Test
    fun qrDocPublishedWithPage() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("SECRETID123", "lea@example.org", enabled = true, uid = UID)))
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        assertEquals("SECRETID123", gateway.docs["volunteerPortalQr/$UID"]!!["payload"])
        assertFalse(gateway.docs["volunteerPortal/$UID"].toString().contains("SECRETID123"))
    }

    @Test
    fun qrDocNotPublishedForAdmin() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID, admin = true)))
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortal/$UID")
        assertNull(gateway.docs["volunteerPortalQr/$UID"])
    }

    @Test
    fun qrDocRemovedWhenVolunteerBecomesAdmin() = runBlocking {
        val gateway = FakeGateway()
        val linked = volunteer("V1", "lea@example.org", enabled = true, uid = UID)
        val host = FakeHost(listOf(linked))
        val volunteers = MutableStateFlow(host.volunteers.toList())
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        volunteers.value = listOf(linked.copy(isAdmin = true))
        withTimeout(5_000) { while ("volunteerPortalQr/$UID" !in gateway.deleted) delay(20) }
        assertNull(gateway.docs["volunteerPortalQr/$UID"])
    }

    @Test
    fun qrDocRemovedWhenSettingOff() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        val service = service(gateway, host)
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        host.qrEnabled = false
        service.republish()
        assertTrue("volunteerPortalQr/$UID" in gateway.deleted)
    }

    @Test
    fun qrDocRemovedWithStalePage() = runBlocking {
        val gateway = FakeGateway()
        val linked = volunteer("V1", "lea@example.org", enabled = true, uid = UID)
        val host = FakeHost(listOf(linked))
        val volunteers = MutableStateFlow(host.volunteers.toList())
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        volunteers.value = listOf(linked.copy(portalEnabled = false))
        withTimeout(5_000) { while ("volunteerPortalQr/$UID" !in gateway.deleted) delay(20) }
        assertTrue("volunteerPortal/$UID" in gateway.deleted)
    }

    @Test
    fun qrDocWrittenElsewhereIsRemovedForAdminOnce() = runBlocking {
        val gateway = FakeGateway()
        // Written by the signup function or another device: no local hash here.
        gateway.docs["volunteerPortalQr/$UID"] = mapOf("payload" to "V1")
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID, admin = true)))
        val service = service(gateway, host)
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortal/$UID")
        service.republish()
        assertNull(gateway.docs["volunteerPortalQr/$UID"])
        assertEquals(1, gateway.deleted.count { it == "volunteerPortalQr/$UID" })
        service.republish()
        assertEquals(1, gateway.deleted.count { it == "volunteerPortalQr/$UID" })
    }

    @Test
    fun failedQrDeleteOfStalePageIsRetried() = runBlocking {
        val gateway = FakeGateway()
        val linked = volunteer("V1", "lea@example.org", enabled = true, uid = UID)
        val host = FakeHost(listOf(linked))
        val volunteers = MutableStateFlow(host.volunteers.toList())
        val service = service(gateway, host)
        service.start(volunteers, MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        gateway.qrDeleteFailures = 1
        volunteers.value = listOf(linked.copy(portalEnabled = false))
        withTimeout(5_000) { while ("volunteerPortal/$UID" !in gateway.deleted) delay(20) }
        withTimeout(5_000) { while (gateway.qrDeleteFailures > 0) delay(20) }
        service.republish()
        assertNull(gateway.docs["volunteerPortalQr/$UID"])
        assertTrue("volunteerPortalQr/$UID" in gateway.deleted)
        assertFalse(host.loadPublishedHashes().keys.any { it.endsWith("#qr") })
    }

    @Test
    fun qrDocNotRewrittenWhenUnchanged() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        val service = service(gateway, host)
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        service.republish()
        service.republish()
        assertEquals(1, gateway.qrWrites)
    }

    @Test
    fun grantAccessSendsNoMail() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org")))
        val auth = FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))
        val result = service(gateway, host, auth).grantAccess(host.volunteers.single())
        val created = assertIs<PortalGrantResult.Created>(result)
        assertTrue(created.password.length >= 12)
        assertEquals("NewUid00000000000000003", host.volunteers.single().portalUid)
        assertEquals(0, auth.resets.size)
    }

    @Test
    fun grantingAccessToAnExistingAccountWaitsForTheClaim() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org")))
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        val result = service(gateway, host, auth).grantAccess(host.volunteers.single())
        assertIs<PortalGrantResult.ExistingAccount>(result)
        assertEquals(0, auth.resets.size)
        assertTrue(host.volunteers.single().portalEnabled)
        assertEquals("", host.volunteers.single().portalUid)
    }

    // region Automatic Google address

    private fun ruleHost(vararg roster: Volunteer) = FakeHost(roster.toList()).apply {
        googleDomains = listOf("asso.ch")
        googleTemplate = "{prénom}.{nom}@asso.ch"
    }

    @Test
    fun grantLinksTheGeneratedGoogleAddress() = runBlocking {
        val host = ruleHost(volunteer("V1", "lea@example.org"))
        val result = service(FakeGateway(), host, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003")))
            .grantAccess(host.volunteers.single())
        assertIs<PortalGrantResult.Created>(result)
        val v = host.volunteers.single()
        assertEquals("NewUid00000000000000003", v.portalUid)
        assertEquals("lea.martin@asso.ch", v.portalGoogleEmail)
        assertEquals(Volunteer.GOOGLE_LINKED_BY_ADMIN, v.portalGoogleLinkedBy)
    }

    @Test
    fun grantToExistingAccountAlsoLinks() = runBlocking {
        val host = ruleHost(volunteer("V1", "lea@example.org"))
        val result = service(FakeGateway(), host, FakeAuth(PortalAuthResult.EmailExists)).grantAccess(host.volunteers.single())
        assertIs<PortalGrantResult.ExistingAccount>(result)
        assertEquals("lea.martin@asso.ch", host.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun grantKeepsAnExistingGoogleAddress() = runBlocking {
        val host = ruleHost(volunteer("V1", "lea@example.org").copy(portalGoogleEmail = "lea@asso.ch"))
        service(FakeGateway(), host, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantAccess(host.volunteers.single())
        assertEquals("lea@asso.ch", host.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun noRuleWhenTemplateEmptyOrGoogleOff() = runBlocking {
        val noTemplate = ruleHost(volunteer("V1", "lea@example.org")).apply { googleTemplate = "" }
        service(FakeGateway(), noTemplate, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantAccess(noTemplate.volunteers.single())
        assertEquals("", noTemplate.volunteers.single().portalGoogleEmail)
        val googleOff = ruleHost(volunteer("V1", "lea@example.org")).apply { googleEnabled = false }
        service(FakeGateway(), googleOff, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantAccess(googleOff.volunteers.single())
        assertEquals("", googleOff.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun takenOrUnusableAddressLeavesTheFieldEmpty() = runBlocking {
        val other = volunteer("V2", "other@example.org").copy(portalGoogleEmail = "lea.martin@asso.ch")
        val host = ruleHost(volunteer("V1", "lea@example.org"), other)
        val result = service(FakeGateway(), host, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantAccess(host.volunteers.first())
        assertIs<PortalGrantResult.Created>(result)
        assertEquals("", host.volunteers.first().portalGoogleEmail)

        val noLastName = ruleHost(volunteer("V1", "lea@example.org").copy(lastNameAbbreviation = ""))
        val second = service(FakeGateway(), noLastName, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantAccess(noLastName.volunteers.single())
        assertIs<PortalGrantResult.Created>(second)
        assertEquals("", noLastName.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun ruleOnAnUnverifiedDomainNeverLinksEvenWhenAnyAccountMaySignIn() = runBlocking {
        val host = ruleHost(volunteer("V1", "lea@example.org")).apply {
            googleAny = true
            googleDomains = emptyList()
            googleTemplate = "{prénom}.{nom}@gmail.com"
        }
        service(FakeGateway(), host, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantAccess(host.volunteers.single())
        assertEquals("", host.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun signupLinksTheGeneratedGoogleAddress() = runBlocking {
        val gateway = FakeGateway()
        val host = ruleHost()
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000006"] = submittedForm() + ("submittedAt" to System.currentTimeMillis() - 150_000)
        gateway.push("volunteerSignupForms")
        assertEquals(1, host.created.size)
        assertEquals("max.dupont@asso.ch", host.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun signupOntoExistingProfileLinksOnlyWhenEmpty() = runBlocking {
        val gateway = FakeGateway()
        val host = ruleHost(
            volunteer("V1", "max@example.org").copy(name = "Max", lastNameAbbreviation = "DUPONT", portalGoogleEmail = "max@asso.ch"),
            volunteer("V2", "zoe@example.org").copy(name = "Zoé", lastNameAbbreviation = "BLANC"),
        )
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000007"] = submittedForm() + ("submittedAt" to System.currentTimeMillis() - 150_000)
        gateway.push("volunteerSignupForms")
        assertEquals("max@asso.ch", host.volunteers.first { it.id == "V1" }.portalGoogleEmail)
        assertEquals(UID, host.volunteers.first { it.id == "V1" }.portalUid)

        gateway.docs["volunteerSignupForms/form00000008"] =
            submittedForm(email = "zoe@example.org") + ("submittedUid" to "OtherUid0000000000000004") + ("submittedAt" to System.currentTimeMillis() - 150_000)
        gateway.push("volunteerSignupForms")
        assertEquals("zoe.blanc@asso.ch", host.volunteers.first { it.id == "V2" }.portalGoogleEmail)
    }

    // endregion

    // region Google accounts

    private val old get() = System.currentTimeMillis() - 150_000

    private fun adminMatchClaim(email: String = "jean@asso.ch", at: Long = old) = mapOf(
        "type" to "ADMIN_MATCH", "status" to "PENDING", "email" to email, "requestedAt" to at,
    )

    @Test
    fun adminMatchClaimLinksTheVolunteerAndAnswersDone() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleLinkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN),
        ))
        startedService(gateway, host)
        val key = "portalGoogleClaims/$GOOGLE_UID"
        gateway.docs[key] = adminMatchClaim()
        gateway.push("portalGoogleClaims")

        val alias = gateway.docs["portalGoogleAccounts/$GOOGLE_UID"]!!
        assertEquals(setOf("volunteerUid", "email", "linkedBy", "linkedAt"), alias.keys)
        assertEquals(UID, alias["volunteerUid"])
        assertEquals("jean@asso.ch", alias["email"])
        assertEquals("ADMIN", alias["linkedBy"])
        val v = host.volunteers.single()
        assertEquals(GOOGLE_UID, v.portalGoogleUid)
        assertEquals("ADMIN", v.portalGoogleLinkedBy)
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("", gateway.docs[key]!!["reason"])
    }

    @Test
    fun adminMatchClaimDropsThePreviousAlias() = runBlocking {
        val oldUid = "OldGoogleUid000000000001"
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = oldUid, portalGoogleLinkedBy = "VOLUNTEER"),
        ))
        gateway.docs["portalGoogleAccounts/$oldUid"] = mapOf("volunteerUid" to UID)
        startedService(gateway, host)
        gateway.docs["portalGoogleClaims/$GOOGLE_UID"] = adminMatchClaim()
        gateway.push("portalGoogleClaims")

        assertEquals("DONE", gateway.docs["portalGoogleClaims/$GOOGLE_UID"]!!["status"])
        assertNull(gateway.docs["portalGoogleAccounts/$oldUid"], "the previous Google account loses access")
        assertEquals("VOLUNTEER", gateway.docs["portalGoogleAccounts/$GOOGLE_UID"]!!["linkedBy"])
        val v = host.volunteers.single()
        assertEquals(GOOGLE_UID, v.portalGoogleUid)
        assertEquals("VOLUNTEER", v.portalGoogleLinkedBy)
    }

    @Test
    fun freshClaimIsLeftToServer() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID).copy(portalGoogleEmail = "jean@asso.ch", portalGoogleLinkedBy = "ADMIN"),
        ))
        startedService(gateway, host)
        val key = "portalGoogleClaims/$GOOGLE_UID"
        gateway.docs[key] = adminMatchClaim(at = System.currentTimeMillis() - 30_000)
        gateway.push("portalGoogleClaims")
        assertEquals("PENDING", gateway.docs[key]!!["status"])
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
    }

    @Test
    fun claimsWaitWhileTheGoogleOptionIsOff() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID).copy(portalGoogleEmail = "jean@asso.ch", portalGoogleLinkedBy = "ADMIN"),
        ))
        host.googleEnabled = false
        startedService(gateway, host)
        val key = "portalGoogleClaims/$GOOGLE_UID"
        gateway.docs[key] = adminMatchClaim()
        gateway.push("portalGoogleClaims")
        assertEquals("PENDING", gateway.docs[key]!!["status"])
    }

    @Test
    fun staleAliasIsDeletedWhenNoVolunteerMatches() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to "GoneUid0000000000000009")
        gateway.docs["portalGoogleClaims/$GOOGLE_UID"] = adminMatchClaim()
        gateway.push("portalGoogleClaims")
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertEquals("REJECTED", gateway.docs["portalGoogleClaims/$GOOGLE_UID"]!!["status"])
        assertEquals("NO_MATCH", gateway.docs["portalGoogleClaims/$GOOGLE_UID"]!!["reason"])
    }

    @Test
    fun linkCodeClaimLinksTheRequesterAndClearsTheCode() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        val requestKey = "portalRequests/${UID}_GOOGLE_LINK"
        gateway.docs[requestKey] = mapOf(
            "uid" to UID, "type" to "GOOGLE_LINK", "status" to "PENDING", "email" to "lea@example.org",
            "codeHash" to PortalProjectionBuilder.sha256(LINK_CODE), "requestedAt" to old,
        )
        gateway.push("portalRequests")
        assertEquals("PENDING", gateway.docs[requestKey]!!["status"], "GOOGLE_LINK waits for its claim")

        val claimKey = "portalGoogleClaims/$GOOGLE_UID"
        gateway.docs[claimKey] = mapOf(
            "type" to "LINK_CODE", "status" to "PENDING", "email" to "Jean@Asso.ch", "code" to LINK_CODE, "requestedAt" to old,
        )
        gateway.push("portalGoogleClaims")

        val alias = gateway.docs["portalGoogleAccounts/$GOOGLE_UID"]!!
        assertEquals(UID, alias["volunteerUid"])
        assertEquals("VOLUNTEER", alias["linkedBy"])
        val v = host.volunteers.single()
        assertEquals("jean@asso.ch", v.portalGoogleEmail)
        assertEquals(GOOGLE_UID, v.portalGoogleUid)
        assertEquals("VOLUNTEER", v.portalGoogleLinkedBy)
        assertEquals("DONE", gateway.docs[claimKey]!!["status"])
        assertEquals("", gateway.docs[claimKey]!!["code"])
        assertEquals("DONE", gateway.docs[requestKey]!!["status"])
    }

    @Test
    fun wrongLinkCodeIsRejectedAndTheCodeCleared() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        gateway.docs["portalRequests/${UID}_GOOGLE_LINK"] = mapOf(
            "uid" to UID, "type" to "GOOGLE_LINK", "status" to "PENDING", "email" to "lea@example.org",
            "codeHash" to PortalProjectionBuilder.sha256("another-code-another-code-another-code"), "requestedAt" to old,
        )
        gateway.push("portalRequests")
        val claimKey = "portalGoogleClaims/$GOOGLE_UID"
        gateway.docs[claimKey] = mapOf(
            "type" to "LINK_CODE", "status" to "PENDING", "email" to "jean@asso.ch", "code" to LINK_CODE, "requestedAt" to old,
        )
        gateway.push("portalGoogleClaims")
        assertEquals("REJECTED", gateway.docs[claimKey]!!["status"])
        assertEquals("CODE", gateway.docs[claimKey]!!["reason"])
        assertEquals("", gateway.docs[claimKey]!!["code"])
        assertEquals("", host.volunteers.single().portalGoogleUid)
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
    }

    @Test
    fun unlinkOfAVolunteerLinkRemovesAliasAndFields() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "VOLUNTEER"),
        ))
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_GOOGLE_UNLINK"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "GOOGLE_UNLINK", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        val v = host.volunteers.single()
        assertEquals("", v.portalGoogleEmail)
        assertEquals("", v.portalGoogleUid)
        assertEquals("", v.portalGoogleLinkedBy)
    }

    @Test
    fun unlinkOfAnAdminLinkIsRefused() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN"),
        ))
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_GOOGLE_UNLINK"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "GOOGLE_UNLINK", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("ADMIN_LINK", gateway.docs[key]!!["reason"])
        assertTrue(gateway.docs.containsKey("portalGoogleAccounts/$GOOGLE_UID"))
        assertEquals(GOOGLE_UID, host.volunteers.single().portalGoogleUid)
    }

    @Test
    fun revokingAccessDeletesTheGoogleAlias() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN"),
        ))
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        assertTrue(service(gateway, host).revokeAccess(host.volunteers.single()))
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        val v = host.volunteers.single()
        assertFalse(v.portalEnabled)
        assertEquals("", v.portalGoogleUid)
        assertEquals("jean@asso.ch", v.portalGoogleEmail, "an admin address stays for a later reopening")
    }

    @Test
    fun assigningAGoogleAccountChecksDomainAndUniqueness() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID),
            volunteer("V2", "max@example.org").copy(portalGoogleEmail = "max@asso.ch", portalGoogleLinkedBy = "ADMIN"),
        ))
        val service = service(gateway, host)
        val lea = host.volunteers.first { it.id == "V1" }
        assertEquals("INVALID", service.assignGoogleAccount(lea, "not an address"))
        assertEquals("DOMAIN", service.assignGoogleAccount(lea, "lea@gmail.com"))
        assertEquals("TAKEN", service.assignGoogleAccount(lea, " MAX@asso.ch "))
        assertNull(service.assignGoogleAccount(lea, "Lea@Asso.ch"))
        val saved = host.volunteers.first { it.id == "V1" }
        assertEquals("lea@asso.ch", saved.portalGoogleEmail)
        assertEquals("ADMIN", saved.portalGoogleLinkedBy)
    }

    @Test
    fun assigningTheRosterAddressIsRefused() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@asso.ch", enabled = true, uid = UID)))
        val lea = host.volunteers.single()
        assertEquals("SAME_AS_EMAIL", service(gateway, host).assignGoogleAccount(lea, " Lea@Asso.ch "))
        assertEquals("", host.volunteers.single().portalGoogleEmail)
    }

    @Test
    fun forgettingADeletedVolunteerDeletesItsGoogleAlias() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN"),
        ))
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        service(gateway, host).forgetVolunteer(host.volunteers.single())
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
    }

    @Test
    fun forgettingADeletedVolunteerRemovesEverythingThePortalKept() = runBlocking {
        val gateway = FakeGateway()
        val deleted = volunteer("V1", "lea@example.org", enabled = true, uid = UID)
            .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN")
        val host = FakeHost(emptyList())
        // Written by the signup function: this device never published them, so it has no hash.
        gateway.docs["volunteerPortal/$UID"] = mapOf("payload" to "{}")
        gateway.docs["volunteerPortalQr/$UID"] = mapOf("payload" to "V1")
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        gateway.docs["portalGoogleClaims/$GOOGLE_UID"] = mapOf("status" to "DONE", "email" to "jean@asso.ch")
        PortalRequestType.entries.forEach {
            gateway.docs["portalRequests/${UID}_${it.name}"] = mapOf("status" to "DONE", "phone" to "+41 79 000 00 00")
        }
        val service = service(gateway, host)
        host.storage[service.portalPhotoPath(UID)] = byteArrayOf(1)
        host.storage[service.portalUploadPath(UID)] = byteArrayOf(2)
        // A second volunteer's documents must stay.
        gateway.docs["volunteerPortal/OtherUid0000000000000001"] = mapOf("payload" to "{}")
        gateway.docs["portalRequests/OtherUid0000000000000001_PROFILE"] = mapOf("status" to "DONE")

        service.forgetVolunteer(deleted)

        assertEquals(
            setOf("volunteerPortal/OtherUid0000000000000001", "portalRequests/OtherUid0000000000000001_PROFILE"),
            gateway.docs.keys,
        )
        assertTrue(host.storage.isEmpty())
        assertTrue(host.loadPublishedHashes().keys.none { it.contains(UID) })
    }

    @Test
    fun forgettingRetriesAFailedQrDeleteLater() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        gateway.docs["volunteerPortalQr/$UID"] = mapOf("payload" to "V1")
        gateway.qrDeleteFailures = 1
        val service = service(gateway, host)
        service.forgetVolunteer(volunteer("V1", "lea@example.org", enabled = true, uid = UID))
        assertEquals("retry", host.loadPublishedHashes()["$ORG/$UID#qr"])
        service.start(MutableStateFlow(emptyList()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        withTimeout(5_000) { while (gateway.docs["volunteerPortalQr/$UID"] != null) delay(20) }
    }

    @Test
    fun forgettingSkipsAVolunteerOfAnotherOrganization() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        gateway.docs["volunteerPortal/$UID"] = mapOf("payload" to "{}")
        service(gateway, host).forgetVolunteer(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID).copy(firebaseOrgId = "org-b"),
        )
        assertTrue(gateway.docs.containsKey("volunteerPortal/$UID"))
    }

    @Test
    fun changingTheAssignedAddressDropsTheOldLink() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "VOLUNTEER"),
        ))
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        val service = service(gateway, host)
        assertNull(service.assignGoogleAccount(host.volunteers.single(), "lea@asso.ch"))
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        val v = host.volunteers.single()
        assertEquals("lea@asso.ch", v.portalGoogleEmail)
        assertEquals("", v.portalGoogleUid)
        assertEquals("ADMIN", v.portalGoogleLinkedBy)
    }

    @Test
    fun removingTheGoogleAccountClearsEverything() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN"),
        ))
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        assertTrue(service(gateway, host).removeGoogleAccount(host.volunteers.single()))
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        val v = host.volunteers.single()
        assertEquals("", v.portalGoogleEmail)
        assertEquals("", v.portalGoogleUid)
        assertEquals("", v.portalGoogleLinkedBy)
    }

    @Test
    fun projectionAndBrandingCarryTheGoogleState() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["volunteerPortal/$UID"] == null) delay(20) }
        val payload = gateway.docs["volunteerPortal/$UID"]!!["payload"] as String
        assertTrue(payload.contains("\"googleEmail\":\"\""))
        assertTrue(payload.contains("\"googleLinkedBy\":\"\""))
        assertTrue(payload.contains("\"googleLinkAvailable\":true"))
        assertEquals(true, gateway.docs["portalBranding/default"]!!["googleLogin"])
    }

    @Test
    fun brandingHidesGoogleLoginWithoutDomains() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.googleDomains = emptyList()
        service(gateway, host).start(MutableStateFlow(emptyList()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalBranding/default"] == null) delay(20) }
        assertEquals(false, gateway.docs["portalBranding/default"]!!["googleLogin"])
    }

    @Test
    fun republishPicksUpAChangedGoogleOption() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        val svc = service(gateway, host)
        svc.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["volunteerPortal/$UID"] == null) delay(20) }
        assertEquals(true, gateway.docs["portalBranding/default"]!!["googleLogin"])
        host.googleEnabled = false
        svc.republish()
        assertEquals(false, gateway.docs["portalBranding/default"]!!["googleLogin"])
        assertTrue((gateway.docs["volunteerPortal/$UID"]!!["payload"] as String).contains("\"googleLinkAvailable\":false"))
    }

    @Test
    fun republishPicksUpAnEmptiedDomainList() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        val svc = service(gateway, host)
        svc.start(MutableStateFlow(emptyList()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalBranding/default"] == null) delay(20) }
        assertEquals(true, gateway.docs["portalBranding/default"]!!["googleLogin"])
        host.googleDomains = emptyList()
        svc.republish()
        assertEquals(false, gateway.docs["portalBranding/default"]!!["googleLogin"])
    }

    // endregion
}
