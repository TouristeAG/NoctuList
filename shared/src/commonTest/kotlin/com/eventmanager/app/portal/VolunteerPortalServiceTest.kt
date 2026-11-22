package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Guest
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ORG = "org-a"
private const val UID = "PortalUid000000000000001"
private const val GOOGLE_UID = "GoogleUid000000000000001"
private const val LINK_CODE = "abcdefghijklmnopqrstuvwxyz0123456789_-AB"

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
            it.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
    fun leavesCertificateEmailToTheServer() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CERTIFICATE_EMAIL"
        gateway.docs[key] = mapOf(
            "uid" to UID, "type" to "CERTIFICATE_EMAIL", "status" to "PENDING", "email" to "lea@example.org",
            "from" to 20260101, "to" to 20261231, "jobTypes" to listOf("Bar"),
            "requestedAt" to System.currentTimeMillis() - 150_000,
        )
        gateway.push("portalRequests")
        assertEquals("PENDING", gateway.docs[key]!!["status"])
        assertTrue(host.mailed.isEmpty())
    }

    private fun startCertificate(gateway: FakeGateway, host: FakeHost, volunteers: MutableStateFlow<List<Volunteer>>) =
        service(gateway, host).also {
            it.start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()),
                MutableStateFlow(listOf(JobTypeConfig(name = "Bar", description = "Behind the bar"), JobTypeConfig(name = "  ", description = "x"))))
        }

    private fun savedTemplate() = com.eventmanager.app.portal.certificate.CertificateDefaults.template("fr", "Asso")

    @Test
    fun doesNotPublishTheCertificateWithoutASavedTemplate() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID))).apply { certificateOn = true }
        val volunteers = MutableStateFlow(host.volunteers.toList())
        startCertificate(gateway, host, volunteers)
        withTimeout(5_000) { while (gateway.docs["volunteerPortal/$UID"] == null) delay(20) }
        val payload = Json.parseToJsonElement(gateway.docs["volunteerPortal/$UID"]!!["payload"] as String).jsonObject
        assertFalse("certificate" in payload, "no certificate link without a saved template")
        assertNull(gateway.docs["portalMail/certificate"])
        assertTrue(gateway.writes.none { it == "portalMail/certificate" })
        host.certTemplate = savedTemplate()
        volunteers.value = listOf(volunteer("V2", "lea@example.org", enabled = true, uid = UID))
        withTimeout(5_000) { while (gateway.docs["portalMail/certificate"] == null) delay(20) }
        val saved = Json.parseToJsonElement(gateway.docs["volunteerPortal/$UID"]!!["payload"] as String).jsonObject
        assertTrue("certificate" in saved)
    }

    @Test
    fun publishesTheCertificateTemplateWhenOn() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("SECRETID123", "lea@example.org", enabled = true, uid = UID))).apply { certificateOn = true; certTemplate = savedTemplate() }
        startCertificate(gateway, host, MutableStateFlow(host.volunteers.toList()))
        withTimeout(5_000) { while (gateway.docs["portalMail/certificate"] == null) delay(20) }
        val doc = gateway.docs["portalMail/certificate"]!!
        assertIs<Map<*, *>>(doc["template"])
        assertEquals("fr", (doc["template"] as Map<*, *>)["language"])
        assertEquals("Asso", doc["association"])
        assertEquals("", doc["logoBase64"])
        assertEquals(
            listOf(mapOf("name" to "Bar", "category" to "SHIFT", "description" to "Behind the bar")),
            doc["jobTypes"],
        )
        assertTrue(doc["updatedAt"] is Long)
        assertFalse(doc.toString().contains("SECRETID123"))
    }

    @Test
    fun deletesTheCertificateTemplateWhenTurnedOff() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { certificateOn = true; certTemplate = savedTemplate() }
        val volunteers = MutableStateFlow(host.volunteers.toList())
        startCertificate(gateway, host, volunteers)
        withTimeout(5_000) { while (gateway.docs["portalMail/certificate"] == null) delay(20) }
        host.certificateOn = false
        volunteers.value = listOf(volunteer("V9", "z@example.org"))
        withTimeout(5_000) { while ("portalMail/certificate" !in gateway.deleted) delay(20) }
        assertNull(gateway.docs["portalMail/certificate"])
        // The hashes are saved once the whole republish pass is done, after the delete.
        withTimeout(5_000) { while (host.loadPublishedHashes().keys.any { it.endsWith("#certificate") }) delay(20) }
        volunteers.value = listOf(volunteer("V8", "y@example.org"))
        delay(300)
        assertEquals(1, gateway.deleted.count { it == "portalMail/certificate" }, "deleted once")
    }

    @Test
    fun republishesTheCertificateTemplateOnlyOnChange() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { certificateOn = true; certTemplate = savedTemplate() }
        val volunteers = MutableStateFlow(host.volunteers.toList())
        startCertificate(gateway, host, volunteers)
        withTimeout(5_000) { while (gateway.docs["portalMail/certificate"] == null) delay(20) }
        volunteers.value = listOf(volunteer("V9", "z@example.org"))
        delay(300)
        volunteers.value = listOf(volunteer("V8", "y@example.org"))
        delay(300)
        assertEquals(1, gateway.writes.count { it == "portalMail/certificate" })
        host.certTemplate = com.eventmanager.app.portal.certificate.CertificateDefaults.template("en", "Asso")
        volunteers.value = listOf(volunteer("V7", "x@example.org"))
        withTimeout(5_000) { while (gateway.writes.count { it == "portalMail/certificate" } < 2) delay(20) }
        assertEquals("en", (gateway.docs["portalMail/certificate"]!!["template"] as Map<*, *>)["language"])
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
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
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
    fun guestMailTemplatePublishedApartFromTheVolunteerOne() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        val volunteers = MutableStateFlow(host.volunteers.toList())
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalMail/guest"] == null || gateway.docs["portalMail/default"] == null) delay(20) }
        assertEquals("Your guest QR code", gateway.docs["portalMail/guest"]!!["subject"])
        assertEquals(1, gateway.docs["portalMail/guest"]!!["schema"])
        assertEquals("Your QR code", gateway.docs["portalMail/default"]!!["subject"])

        gateway.docs.remove("portalMail/guest")
        volunteers.value = listOf(volunteer("V9", "z@example.org"))
        delay(300)
        assertNull(gateway.docs["portalMail/guest"], "unchanged guest template is not rewritten")

        host.guestMailSubject = "Another guest subject"
        volunteers.value = listOf(volunteer("V8", "y@example.org"))
        withTimeout(5_000) { while (gateway.docs["portalMail/guest"] == null) delay(20) }
        assertEquals("Another guest subject", gateway.docs["portalMail/guest"]!!["subject"])
        assertEquals("Your QR code", gateway.docs["portalMail/default"]!!["subject"])
    }

    @Test
    fun unpublishAllRemovesBothMailTemplates() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        val service = service(gateway, host)
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalMail/guest"] == null || gateway.docs["portalMail/default"] == null) delay(20) }
        service.unpublishAll()
        assertTrue("portalMail/guest" in gateway.deleted)
        assertTrue("portalMail/default" in gateway.deleted)
        assertFalse(host.loadPublishedHashes().keys.any { it.endsWith("#mailGuest") })
    }

    @Test
    fun mailTemplateNeverContainsVolunteerId() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("SECRETID123", "lea@example.org", enabled = true, uid = UID)))
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()),
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
        service.start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))

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
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        awaitDoc(gateway, "volunteerPortalQr/$UID")
        assertEquals("SECRETID123", gateway.docs["volunteerPortalQr/$UID"]!!["payload"])
        assertFalse(gateway.docs["volunteerPortal/$UID"].toString().contains("SECRETID123"))
    }

    @Test
    fun qrDocNotPublishedForAdmin() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID, admin = true)))
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service(gateway, host).start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service.start(volunteers, MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service.start(MutableStateFlow(emptyList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
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
        service(gateway, host).start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()),
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
        service(gateway, host).start(MutableStateFlow(emptyList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalBranding/default"] == null) delay(20) }
        assertEquals(false, gateway.docs["portalBranding/default"]!!["googleLogin"])
    }

    @Test
    fun republishPicksUpAChangedGoogleOption() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        val svc = service(gateway, host)
        svc.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()),
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
        svc.start(MutableStateFlow(emptyList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList()),
            MutableStateFlow(emptyList()), MutableStateFlow(emptyList()))
        withTimeout(5_000) { while (gateway.docs["portalBranding/default"] == null) delay(20) }
        assertEquals(true, gateway.docs["portalBranding/default"]!!["googleLogin"])
        host.googleDomains = emptyList()
        svc.republish()
        assertEquals(false, gateway.docs["portalBranding/default"]!!["googleLogin"])
    }

    // endregion

    // region Guests

    private fun guest(
        nanoId: String,
        email: String,
        enabled: Boolean = true,
        uid: String = "",
        admin: Boolean = false,
    ) = Guest(
        nanoId = nanoId,
        name = "Max",
        lastNameAbbreviation = "DUPONT",
        email = email,
        phoneNumber = "+41 78 000 00 00",
        invitations = 0,
        venueName = "",
        isAdmin = admin,
        firebaseOrgId = ORG,
        portalEnabled = enabled,
        portalUid = uid,
    )

    private fun tempGroup(nanoId: String, contact: String, uid: String = "") = Guest(
        nanoId = nanoId,
        name = "Band",
        invitations = 0,
        venueName = "",
        isTemporaryGuest = true,
        temporaryContactEmail = contact,
        firebaseOrgId = ORG,
        portalEnabled = true,
        portalUid = uid,
    )

    private fun guestProfileRequest(photo: Boolean = false) = mapOf(
        "uid" to UID, "type" to "GUEST_PROFILE", "status" to "PENDING", "email" to "max@example.org",
        "firstName" to "Maxime", "lastName" to "Dupont-Roy", "phone" to "+41 79 111 22 33", "photo" to photo,
    )

    @Test
    fun guestProfileUpdatesGuest() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        host.guestSpace = true
        host.guests += guest("G1", "max@example.org", uid = UID, admin = true)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_GUEST_PROFILE"
        gateway.docs[key] = guestProfileRequest()
        gateway.push("portalRequests")

        assertEquals("DONE", gateway.docs[key]!!["status"])
        val g = host.guests.single()
        assertEquals("Maxime", g.name)
        assertEquals("DUPONT-ROY", g.lastNameAbbreviation)
        assertEquals("+41 79 111 22 33", g.phoneNumber)
        assertEquals("max@example.org", g.email)
        assertTrue(g.isAdmin)
        assertEquals("Léa", host.volunteers.single().name, "the volunteer of the same account is untouched")
    }

    @Test
    fun guestProfilePhotoAttachesToTheGuest() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.guestSpace = true
        host.guests += guest("G1", "max@example.org", uid = UID)
        val upload = "orgs/$ORG/portalPhotoUploads/$UID.jpg"
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        host.storage[upload] = jpeg
        startedService(gateway, host)
        val key = "portalRequests/${UID}_GUEST_PROFILE"
        gateway.docs[key] = guestProfileRequest(photo = true)
        gateway.push("portalRequests")

        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("G1", host.guestAttached.single().first)
        assertTrue(host.guestAttached.single().second.contentEquals(jpeg))
        assertTrue(host.attached.isEmpty())
        assertFalse(upload in host.storage)
    }

    @Test
    fun guestQrEmailRefusedForAdmin() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.guestSpace = true
        host.guests += guest("G1", "max@example.org", uid = UID, admin = true)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_GUEST_QR_EMAIL"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "GUEST_QR_EMAIL", "status" to "PENDING", "email" to "max@example.org")
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("NOT_ALLOWED", gateway.docs[key]!!["reason"])
        assertTrue(host.guestMailed.isEmpty())

        host.guests[0] = host.guests[0].copy(isAdmin = false, email = "profile@example.org")
        gateway.docs[key] = mapOf("uid" to UID, "type" to "GUEST_QR_EMAIL", "status" to "PENDING", "email" to "max@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("profile@example.org", host.guestMailed.single().email)
        assertTrue(host.mailed.isEmpty())
    }

    @Test
    fun guestRequestsIgnoredWhenGuestSpaceOff() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.guests += guest("G1", "max@example.org", uid = UID)
        startedService(gateway, host)
        val profileKey = "portalRequests/${UID}_GUEST_PROFILE"
        val qrKey = "portalRequests/${UID}_GUEST_QR_EMAIL"
        gateway.docs[profileKey] = guestProfileRequest()
        gateway.docs[qrKey] = mapOf("uid" to UID, "type" to "GUEST_QR_EMAIL", "status" to "PENDING", "email" to "max@example.org")
        gateway.push("portalRequests")
        assertEquals("PENDING", gateway.docs[profileKey]!!["status"])
        assertEquals("PENDING", gateway.docs[qrKey]!!["status"])
        assertEquals("Max", host.guests.single().name)
        assertTrue(host.guestMailed.isEmpty())

        // Space back on: the waiting requests are answered.
        host.guestSpace = true
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[profileKey]!!["status"])
        assertEquals("Maxime", host.guests.single().name)
    }

    @Test
    fun claimLinksVolunteerGuestAndTempGroup() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true)))
        host.guestSpace = true
        host.tempSpace = true
        host.guests += guest("G1", "Lea@Example.org")
        host.guests += tempGroup("T1", "lea@example.org ")
        host.guests += tempGroup("T2", "lea@example.org")
        // Not an enabled kind for the claim: volunteer benefit, closed access, another address.
        host.guests += guest("B1", "lea@example.org").copy(isVolunteerBenefit = true)
        host.guests += guest("G2", "lea@example.org", enabled = false)
        host.guests += guest("G3", "other@example.org")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CLAIM"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")

        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals(UID, host.volunteers.single().portalUid)
        val byId = host.guests.associateBy { it.nanoId }
        assertEquals(UID, byId["G1"]!!.portalUid)
        assertEquals(UID, byId["T1"]!!.portalUid)
        assertEquals(UID, byId["T2"]!!.portalUid)
        assertEquals("", byId["B1"]!!.portalUid)
        assertEquals("", byId["G2"]!!.portalUid)
        assertEquals("", byId["G3"]!!.portalUid)
    }

    @Test
    fun claimMatchesTemporaryGroupsOnTheAddressTheAccountWasOpenedFor() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.tempSpace = true
        // Opened for lea@, contact later edited to another address: the claim follows the opened address.
        host.guests += tempGroup("T1", "edited@example.org").copy(temporaryPortalEmail = "lea@example.org")
        host.guests += tempGroup("T2", "lea@example.org").copy(temporaryPortalEmail = "other@example.org")
        host.guests += tempGroup("T3", "lea@example.org")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CLAIM"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        val byId = host.guests.associateBy { it.nanoId }
        assertEquals(UID, byId["T1"]!!.portalUid)
        assertEquals("", byId["T2"]!!.portalUid)
        assertEquals(UID, byId["T3"]!!.portalUid, "no recorded address: the contact address is used")
    }

    @Test
    fun claimSkipsGuestKindsWhoseSpaceIsOff() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.guests += guest("G1", "lea@example.org")
        host.guests += tempGroup("T1", "lea@example.org")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CLAIM"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("NO_ACCESS", gateway.docs[key]!!["reason"])
        assertTrue(host.guests.all { it.portalUid.isBlank() })

        host.tempSpace = true
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals(listOf("", UID), host.guests.map { it.portalUid })
    }

    @Test
    fun claimRejectedWhenAnotherUidOwnsAddress() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true)))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org", uid = "OtherUid0000000000000002")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CLAIM"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("LINKED_ELSEWHERE", gateway.docs[key]!!["reason"])
        assertEquals("", host.volunteers.single().portalUid, "nothing linked")
    }

    @Test
    fun googleLinkWrittenOnEveryHolderOfTheAccount() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleLinkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN),
        ))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org", uid = UID)
        startedService(gateway, host)
        val key = "portalGoogleClaims/$GOOGLE_UID"
        gateway.docs[key] = adminMatchClaim()
        gateway.push("portalGoogleClaims")

        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals(UID, gateway.docs["portalGoogleAccounts/$GOOGLE_UID"]!!["volunteerUid"])
        val g = host.guests.single()
        assertEquals("jean@asso.ch", g.portalGoogleEmail)
        assertEquals(GOOGLE_UID, g.portalGoogleUid)
        assertEquals("ADMIN", g.portalGoogleLinkedBy)
        assertEquals(GOOGLE_UID, host.volunteers.single().portalGoogleUid)

        // Unlinked from the portal: both lose it.
        val both = listOf(host.volunteers[0].copy(portalGoogleLinkedBy = "VOLUNTEER"))
        host.volunteers.clear(); host.volunteers += both
        host.guests[0] = host.guests[0].copy(portalGoogleLinkedBy = "VOLUNTEER")
        val unlink = "portalRequests/${UID}_GOOGLE_UNLINK"
        gateway.docs[unlink] = mapOf("uid" to UID, "type" to "GOOGLE_UNLINK", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[unlink]!!["status"])
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertEquals("", host.guests.single().portalGoogleUid)
        assertEquals("", host.volunteers.single().portalGoogleUid)
    }

    @Test
    fun adminMatchLinksAGuestAlone() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        host.guestSpace = true
        host.guests += guest("G1", "max@example.org", uid = UID)
            .copy(portalGoogleEmail = "jean@asso.ch", portalGoogleLinkedBy = "ADMIN")
        startedService(gateway, host)
        gateway.docs["portalGoogleClaims/$GOOGLE_UID"] = adminMatchClaim()
        gateway.push("portalGoogleClaims")
        assertEquals("DONE", gateway.docs["portalGoogleClaims/$GOOGLE_UID"]!!["status"])
        assertEquals(GOOGLE_UID, host.guests.single().portalGoogleUid)

        // Guest space off: the guest is not a holder any more.
        host.guestSpace = false
        val other = "GoogleUid000000000000002"
        gateway.docs["portalGoogleClaims/$other"] = adminMatchClaim()
        gateway.push("portalGoogleClaims")
        assertEquals("NO_MATCH", gateway.docs["portalGoogleClaims/$other"]!!["reason"])
    }

    @Test
    fun assigningANewAddressMovesTheGuestsOfTheAccountToo() = runBlocking {
        val oldGoogle = "OldGoogleUid000000000001"
        val gateway = FakeGateway()
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "x@asso.ch", portalGoogleUid = oldGoogle, portalGoogleLinkedBy = "ADMIN"),
        ))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org", uid = UID)
            .copy(portalGoogleEmail = "x@asso.ch", portalGoogleUid = oldGoogle, portalGoogleLinkedBy = "ADMIN")
        gateway.docs["portalGoogleAccounts/$oldGoogle"] = mapOf("volunteerUid" to UID)
        val svc = startedService(gateway, host)
        assertNull(svc.assignGoogleAccount(host.volunteers.single(), "y@asso.ch"))

        assertNull(gateway.docs["portalGoogleAccounts/$oldGoogle"], "the former Google account loses access")
        val g = host.guests.single()
        assertEquals("y@asso.ch", g.portalGoogleEmail)
        assertEquals("", g.portalGoogleUid)
        assertEquals("ADMIN", g.portalGoogleLinkedBy)

        // The former account signs in again: nobody carries its address any more.
        val key = "portalGoogleClaims/$oldGoogle"
        gateway.docs[key] = adminMatchClaim(email = "x@asso.ch")
        gateway.push("portalGoogleClaims")
        assertEquals("NO_MATCH", gateway.docs[key]!!["reason"])
        assertEquals("", host.volunteers.single().portalGoogleUid)
    }

    @Test
    fun removingAndUnlinkingClearTheGuestEvenWithTheSpaceOff() = runBlocking {
        val gateway = FakeGateway()
        fun linkedHost(linkedBy: String) = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "x@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = linkedBy),
        )).apply {
            guests += guest("G1", "lea@example.org", uid = UID)
                .copy(portalGoogleEmail = "x@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = linkedBy)
        }

        val removed = linkedHost("ADMIN")
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        assertTrue(service(gateway, removed).removeGoogleAccount(removed.volunteers.single()))
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertEquals("", removed.guests.single().portalGoogleEmail)
        assertEquals("", removed.guests.single().portalGoogleUid)
        assertEquals("", removed.guests.single().portalGoogleLinkedBy)

        val unlinked = linkedHost("VOLUNTEER")
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        startedService(gateway, unlinked)
        val key = "portalRequests/${UID}_GOOGLE_UNLINK"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "GOOGLE_UNLINK", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertEquals("", unlinked.guests.single().portalGoogleUid)
        assertEquals("", unlinked.guests.single().portalGoogleEmail)
        assertEquals("", unlinked.volunteers.single().portalGoogleUid)
    }

    @Test
    fun claimByAnExistingOwnerSkipsAGuestLinkedElsewhere() = runBlocking {
        val gateway = FakeGateway()
        val other = "OtherUid0000000000000002"
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org", uid = other)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CLAIM"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("", gateway.docs[key]!!["reason"])
        assertEquals(other, host.guests.single().portalUid)
    }

    @Test
    fun claimByAnExistingOwnerLinksAFreeGuest() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_CLAIM"
        gateway.docs[key] = mapOf("uid" to UID, "type" to "CLAIM", "status" to "PENDING", "email" to "lea@example.org")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals(UID, host.guests.single().portalUid)
    }

    // endregion

    // region Permanent guest access

    @Test
    fun grantGuestAccessSendsNoMail() = runBlocking {
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", enabled = false)
        val auth = FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))
        val result = service(FakeGateway(), host, auth).grantGuestAccess(host.guests.single())
        val created = assertIs<PortalGrantResult.Created>(result)
        assertTrue(created.password.length >= 12)
        val g = host.guests.single()
        assertTrue(g.portalEnabled)
        assertEquals("NewUid00000000000000003", g.portalUid)
        assertEquals(0, auth.resets.size)
        assertTrue(host.guestMailed.isEmpty())
        assertTrue(host.mailed.isEmpty())
    }

    @Test
    fun grantGuestAccessToAnExistingAccountWaitsForTheClaim() = runBlocking {
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", enabled = false)
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        assertIs<PortalGrantResult.ExistingAccount>(service(FakeGateway(), host, auth).grantGuestAccess(host.guests.single()))
        assertTrue(host.guests.single().portalEnabled)
        assertEquals("", host.guests.single().portalUid)
        assertEquals(0, auth.resets.size)
    }

    @Test
    fun grantGuestAccessOnlyForPermanentGuestsWithTheSpaceOn() = runBlocking {
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        host.guests += tempGroup("T1", "band@example.org").copy(portalEnabled = false, email = "band@example.org")
        host.guests += guest("B1", "ben@example.org", enabled = false).copy(isVolunteerBenefit = true)
        host.guests += guest("G1", "", enabled = false)
        host.guests += guest("G2", "max@example.org", enabled = false)
        val svc = service(FakeGateway(), host, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003")))
        val byId = { id: String -> host.guests.first { it.nanoId == id } }
        assertEquals(PortalGrantResult.Failed("NOT_PERMANENT"), svc.grantGuestAccess(byId("T1")))
        assertEquals(PortalGrantResult.Failed("NOT_PERMANENT"), svc.grantGuestAccess(byId("B1")))
        assertEquals(PortalGrantResult.Failed("INVALID_EMAIL"), svc.grantGuestAccess(byId("G1")))
        host.guestSpace = false
        assertEquals(PortalGrantResult.Failed("GUEST_SPACE_OFF"), svc.grantGuestAccess(byId("G2")))
        assertTrue(host.guests.none { it.portalEnabled })
    }

    @Test
    fun grantGuestAccessLinksTheGeneratedGoogleAddress() = runBlocking {
        val host = ruleHost().apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", enabled = false)
        assertIs<PortalGrantResult.Created>(
            service(FakeGateway(), host, FakeAuth(PortalAuthResult.Created("NewUid00000000000000003"))).grantGuestAccess(host.guests.single()),
        )
        assertEquals("max.dupont@asso.ch", host.guests.single().portalGoogleEmail)
        assertEquals(Volunteer.GOOGLE_LINKED_BY_ADMIN, host.guests.single().portalGoogleLinkedBy)
    }

    @Test
    fun grantGuestAccessReusesTheVolunteerAccount() = runBlocking {
        val host = ruleHost(volunteer("V1", "Max@Example.org", enabled = true, uid = UID)).apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", enabled = false)
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        assertIs<PortalGrantResult.ExistingAccount>(service(FakeGateway(), host, auth).grantGuestAccess(host.guests.single()))
        assertTrue(auth.creates.isEmpty(), "no account is created")
        val g = host.guests.single()
        assertTrue(g.portalEnabled)
        assertEquals(UID, g.portalUid)
        assertEquals("max.dupont@asso.ch", g.portalGoogleEmail, "the Google rule still applies")
        assertEquals(0, auth.resets.size)
    }

    @Test
    fun grantGuestAccessOnAnOpenGuestKeepsItsLink() = runBlocking {
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", uid = UID)
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        assertIs<PortalGrantResult.ExistingAccount>(service(FakeGateway(), host, auth).grantGuestAccess(host.guests.single()))
        assertTrue(auth.creates.isEmpty())
        assertEquals(UID, host.guests.single().portalUid)
        assertTrue(host.guests.single().portalEnabled)
    }

    @Test
    fun revokingAGuestKeepsAnyAddressItDidNotLinkItself() = runBlocking {
        for ((linkedBy, kept) in listOf("VOLUNTEER" to false, "ADMIN" to true, "" to true)) {
            val host = FakeHost(emptyList()).apply { guestSpace = true }
            host.guests += guest("G1", "max@example.org", uid = UID)
                .copy(portalGoogleEmail = "max@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = linkedBy)
            assertTrue(service(FakeGateway(), host).revokeGuestAccess(host.guests.single()))
            val g = host.guests.single()
            assertEquals(if (kept) "max@asso.ch" else "", g.portalGoogleEmail, linkedBy)
            assertEquals(if (kept) linkedBy else "", g.portalGoogleLinkedBy, linkedBy)
            assertEquals("", g.portalGoogleUid)
            assertTrue(g.portalRevoked, "a cut access is not joined back to its address's account")
        }
    }

    @Test
    fun revokeGuestAccessRefusesTemporaryAndBenefitRows() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true; tempSpace = true }
        host.guests += tempGroup("T1", "band@example.org", uid = UID)
        host.guests += guest("B1", "ben@example.org", uid = UID).copy(isVolunteerBenefit = true)
        gateway.docs["temporaryGuestPortal/$UID"] = mapOf("payload" to "{}")
        val svc = service(gateway, host)
        assertFalse(svc.revokeGuestAccess(host.guests[0]))
        assertFalse(svc.revokeGuestAccess(host.guests[1]))
        assertTrue(host.guests.all { it.portalEnabled && it.portalUid == UID })
        assertTrue(gateway.docs.containsKey("temporaryGuestPortal/$UID"))
    }

    @Test
    fun forgettingAGuestKeepsGuestRequestsForAnotherGuestOfTheUid() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", uid = UID)
        host.guests += guest("G2", "max@example.org", uid = UID)
        gateway.docs["guestPortal/$UID"] = mapOf("payload" to "{}")
        PortalRequestType.entries.forEach { gateway.docs["portalRequests/${UID}_${it.name}"] = mapOf("status" to "DONE") }
        service(gateway, host).forgetGuest(host.guests[0])
        PortalRequestType.entries.forEach { assertTrue(gateway.docs.containsKey("portalRequests/${UID}_${it.name}"), it.name) }
        assertTrue(gateway.docs.containsKey("guestPortal/$UID"), "the other guest's page stays")
    }

    @Test
    fun guestPasswordResetGoesToTheGuestAddress() = runBlocking {
        val host = FakeHost(emptyList())
        val auth = FakeAuth(PortalAuthResult.Sent)
        assertTrue(service(FakeGateway(), host, auth).sendGuestPasswordReset(guest("G1", "max@example.org", uid = UID), "fr"))
        assertEquals(listOf("max@example.org"), auth.resets)
    }

    @Test
    fun assigningAndRemovingAGuestGoogleAccount() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V2", "zoe@example.org").copy(portalGoogleEmail = "zoe@asso.ch")))
        host.guestSpace = true
        host.guests += guest("G1", "max@example.org", uid = UID)
        val svc = service(gateway, host)
        val g = host.guests.single()
        assertEquals("INVALID", svc.assignGuestGoogleAccount(g, "nope"))
        assertEquals("DOMAIN", svc.assignGuestGoogleAccount(g, "max@gmail.com"))
        assertEquals("SAME_AS_EMAIL", svc.assignGuestGoogleAccount(guest("G9", "ann@asso.ch", uid = "OtherUid0000000000000009"), " Ann@Asso.ch "))
        assertEquals("TAKEN", svc.assignGuestGoogleAccount(g, "zoe@asso.ch"))
        assertNull(svc.assignGuestGoogleAccount(g, "Max@Asso.ch"))
        assertEquals("max@asso.ch", host.guests.single().portalGoogleEmail)
        assertEquals("ADMIN", host.guests.single().portalGoogleLinkedBy)

        host.guests[0] = host.guests[0].copy(portalGoogleUid = GOOGLE_UID)
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        assertTrue(svc.removeGuestGoogleAccount(host.guests.single()))
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertEquals("", host.guests.single().portalGoogleEmail)
        assertEquals("zoe@asso.ch", host.volunteers.single().portalGoogleEmail, "another account is untouched")
    }

    @Test
    fun assigningAGuestAddressMovesTheVolunteerOfTheAccountToo() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org", uid = UID)
        assertNull(service(gateway, host).assignGuestGoogleAccount(host.guests.single(), "lea@asso.ch"))
        assertEquals("lea@asso.ch", host.guests.single().portalGoogleEmail)
        assertEquals("lea@asso.ch", host.volunteers.single().portalGoogleEmail)
    }

    private fun guestSignup(email: String = "max@example.org", venue: String = "Main hall") =
        submittedForm(email = email) - "dateOfBirth" - "gender" +
            mapOf("holderType" to "GUEST", "venueName" to venue, "submittedAt" to old)

    @Test
    fun guestSignupCreatesPermanentGuestWithFormVenue() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        startedService(gateway, host)
        val photo = "orgs/$ORG/portalSignupPhotos/form00000011.jpg"
        host.storage[photo] = byteArrayOf(1, 2, 3)
        gateway.docs["volunteerSignupForms/form00000011"] = guestSignup() + ("photoPath" to photo)
        gateway.push("volunteerSignupForms")

        assertTrue(host.created.isEmpty(), "no volunteer")
        val (created, createdPhoto) = host.createdGuests.single()
        assertEquals("Max", created.name)
        assertEquals("DUPONT", created.lastNameAbbreviation)
        assertEquals("max@example.org", created.email)
        assertEquals("+41 78 555 12 34", created.phoneNumber)
        assertEquals("Main hall", created.venueName)
        assertEquals(0, created.invitations)
        assertEquals(0, created.barDiscountPercent)
        assertFalse(created.isTemporaryGuest)
        assertFalse(created.isVolunteerBenefit)
        assertFalse(created.isAdmin)
        assertTrue(created.portalEnabled)
        assertEquals(UID, created.portalUid)
        assertEquals(ORG, created.firebaseOrgId)
        assertTrue(created.nanoId.isNotBlank())
        assertTrue(createdPhoto!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertNull(gateway.docs["volunteerSignupForms/form00000011"])
        assertFalse(host.storage.containsKey(photo))
    }

    @Test
    fun guestSignupLinksExistingGuestSameEmail() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(listOf(volunteer("V1", "max@example.org"))).apply { guestSpace = true }
        host.guests += guest("G1", "Max@Example.org", enabled = false).copy(venueName = "Bar")
        // Same address but not a permanent guest, or already on an account: never linked.
        host.guests += tempGroup("T1", "max@example.org").copy(email = "max@example.org", portalEnabled = false)
        startedService(gateway, host)
        val photo = "orgs/$ORG/portalSignupPhotos/form00000012.jpg"
        host.storage[photo] = byteArrayOf(4)
        gateway.docs["volunteerSignupForms/form00000012"] = guestSignup() + ("photoPath" to photo)
        gateway.push("volunteerSignupForms")

        assertTrue(host.createdGuests.isEmpty())
        val linked = host.guests.first { it.nanoId == "G1" }
        assertTrue(linked.portalEnabled)
        assertEquals(UID, linked.portalUid)
        assertEquals("Bar", linked.venueName, "an existing guest keeps its venue")
        assertEquals("G1", host.guestAttached.single().first)
        assertEquals("", host.guests.first { it.nanoId == "T1" }.portalUid)
        assertEquals("", host.volunteers.single().portalUid, "the volunteer of the same address is untouched")
        assertNull(gateway.docs["volunteerSignupForms/form00000012"])
    }

    @Test
    fun guestSignupIgnoredWhenGuestSpaceOff() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000013"] = guestSignup()
        gateway.push("volunteerSignupForms")
        assertTrue(host.createdGuests.isEmpty())
        assertTrue(host.created.isEmpty())
        assertEquals("SUBMITTED", gateway.docs["volunteerSignupForms/form00000013"]!!["status"])

        host.guestSpace = true
        gateway.push("volunteerSignupForms")
        assertEquals(1, host.createdGuests.size)
    }

    @Test
    fun guestSignupLinksTheGeneratedGoogleAddress() = runBlocking {
        val gateway = FakeGateway()
        val host = ruleHost().apply { guestSpace = true }
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000014"] = guestSignup()
        gateway.push("volunteerSignupForms")
        assertEquals("max.dupont@asso.ch", host.guests.single().portalGoogleEmail)
    }

    @Test
    fun volunteerSignupUnchanged() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { guestSpace = true }
        host.guests += guest("G1", "max@example.org", enabled = false)
        startedService(gateway, host)
        gateway.docs["volunteerSignupForms/form00000015"] =
            submittedForm() + mapOf("holderType" to "VOLUNTEER", "venueName" to "Main hall", "submittedAt" to old)
        gateway.push("volunteerSignupForms")
        val created = host.created.single()
        assertEquals("1999-07-21", created.dateOfBirth)
        assertEquals(Gender.MALE, created.gender)
        assertEquals(UID, created.portalUid)
        assertTrue(host.createdGuests.isEmpty())
        assertEquals("", host.guests.single().portalUid, "a guest of the same address is not linked by a volunteer link")
    }

    @Test
    fun createSignupFormWritesHolderTypeAndVenue() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        val svc = service(gateway, host)
        val guestForm = svc.createSignupForm(PortalHolderKind.GUEST, "Main hall")!!
        val doc = gateway.docs["volunteerSignupForms/${guestForm.formId}"]!!
        assertEquals("GUEST", doc["holderType"])
        assertEquals("Main hall", doc["venueName"])
        assertEquals(PortalHolderKind.GUEST, host.loadPendingSignupForms().single().holderType)

        val volunteerForm = svc.createSignupForm()!!
        assertEquals("VOLUNTEER", gateway.docs["volunteerSignupForms/${volunteerForm.formId}"]!!["holderType"])
        assertEquals("", gateway.docs["volunteerSignupForms/${volunteerForm.formId}"]!!["venueName"])
        // Old saved lists without the new fields still read.
        val decoded = VolunteerSignupForm.decodeList("""[{"formId":"f","orgId":"o","createdAt":1,"expiresAtMillis":2,"photosEnabled":true}]""")
        assertEquals(PortalHolderKind.VOLUNTEER, decoded.single().holderType)
    }

    /** A volunteer and a permanent guest on one portal account and one Google account, with their documents. */
    private fun sharedAccount(gateway: FakeGateway): FakeHost {
        val host = FakeHost(listOf(
            volunteer("V1", "lea@example.org", enabled = true, uid = UID)
                .copy(portalGoogleEmail = "lea@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN"),
        ))
        host.guestSpace = true
        host.guests += guest("G1", "lea@example.org", uid = UID)
            .copy(portalGoogleEmail = "lea@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "ADMIN")
        gateway.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        gateway.docs["portalGoogleClaims/$GOOGLE_UID"] = mapOf("status" to "DONE")
        gateway.docs["volunteerPortal/$UID"] = mapOf("payload" to "{}")
        gateway.docs["volunteerPortalQr/$UID"] = mapOf("payload" to "V1")
        gateway.docs["guestPortal/$UID"] = mapOf("payload" to "{}")
        gateway.docs["guestPortalQr/$UID"] = mapOf("payload" to "G1")
        PortalRequestType.entries.forEach { gateway.docs["portalRequests/${UID}_${it.name}"] = mapOf("status" to "DONE") }
        host.storage["orgs/$ORG/portalPhotos/$UID.jpg"] = byteArrayOf(1)
        host.storage["orgs/$ORG/portalPhotoUploads/$UID.jpg"] = byteArrayOf(2)
        return host
    }

    private val sharedRequests = listOf("CLAIM", "GOOGLE_LINK", "GOOGLE_UNLINK")

    @Test
    fun revokingTheVolunteerKeepsTheGuestOfTheSameAccount() = runBlocking {
        val gateway = FakeGateway()
        val host = sharedAccount(gateway)
        assertTrue(service(gateway, host).revokeAccess(host.volunteers.single()))

        assertFalse(host.volunteers.single().portalEnabled)
        assertNull(gateway.docs["volunteerPortal/$UID"])
        assertNull(gateway.docs["volunteerPortalQr/$UID"])
        assertTrue(gateway.docs.containsKey("portalGoogleAccounts/$GOOGLE_UID"), "the guest still signs in with Google")
        assertTrue(gateway.docs.containsKey("guestPortal/$UID"))
        assertTrue(gateway.docs.containsKey("guestPortalQr/$UID"))
        assertTrue(host.storage.containsKey("orgs/$ORG/portalPhotos/$UID.jpg"), "the account photo stays for the guest")
        val g = host.guests.single()
        assertTrue(g.portalEnabled)
        assertEquals(UID, g.portalUid)
        assertEquals(GOOGLE_UID, g.portalGoogleUid)
    }

    @Test
    fun revokingTheGuestKeepsTheVolunteerOfTheSameAccount() = runBlocking {
        val gateway = FakeGateway()
        val host = sharedAccount(gateway)
        assertTrue(service(gateway, host).revokeGuestAccess(host.guests.single()))

        val g = host.guests.single()
        assertFalse(g.portalEnabled)
        assertEquals("", g.portalUid)
        assertEquals("", g.portalGoogleUid)
        assertEquals("lea@asso.ch", g.portalGoogleEmail, "an admin address stays for a later reopening")
        assertNull(gateway.docs["guestPortal/$UID"])
        assertNull(gateway.docs["guestPortalQr/$UID"])
        assertTrue(gateway.docs.containsKey("portalGoogleAccounts/$GOOGLE_UID"))
        assertTrue(gateway.docs.containsKey("volunteerPortal/$UID"))
        assertTrue(gateway.docs.containsKey("volunteerPortalQr/$UID"))
        assertTrue(host.storage.containsKey("orgs/$ORG/portalPhotos/$UID.jpg"))
        val v = host.volunteers.single()
        assertTrue(v.portalEnabled)
        assertEquals(GOOGLE_UID, v.portalGoogleUid)
    }

    @Test
    fun revokingTheLastHolderOfTheAccountDeletesTheAliasAndPhoto() = runBlocking {
        // Guest first, then the volunteer.
        val gateway = FakeGateway()
        val host = sharedAccount(gateway)
        val svc = service(gateway, host)
        svc.revokeGuestAccess(host.guests.single())
        svc.revokeAccess(host.volunteers.single())
        assertNull(gateway.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertFalse(host.storage.containsKey("orgs/$ORG/portalPhotos/$UID.jpg"))

        // Volunteer first, then the guest.
        val gateway2 = FakeGateway()
        val host2 = sharedAccount(gateway2)
        val svc2 = service(gateway2, host2)
        svc2.revokeAccess(host2.volunteers.single())
        svc2.revokeGuestAccess(host2.guests.single())
        assertNull(gateway2.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertFalse(host2.storage.containsKey("orgs/$ORG/portalPhotos/$UID.jpg"))
        assertNull(gateway2.docs["guestPortal/$UID"])

        // A guest alone, linked by itself: alias and Google fields go.
        val gateway3 = FakeGateway()
        val host3 = FakeHost(emptyList()).apply { guestSpace = true }
        host3.guests += guest("G1", "max@example.org", uid = UID)
            .copy(portalGoogleEmail = "max@asso.ch", portalGoogleUid = GOOGLE_UID, portalGoogleLinkedBy = "VOLUNTEER")
        gateway3.docs["portalGoogleAccounts/$GOOGLE_UID"] = mapOf("volunteerUid" to UID)
        assertTrue(service(gateway3, host3).revokeGuestAccess(host3.guests.single()))
        assertNull(gateway3.docs["portalGoogleAccounts/$GOOGLE_UID"])
        assertEquals("", host3.guests.single().portalGoogleEmail)
        assertEquals("", host3.guests.single().portalGoogleLinkedBy)
    }

    @Test
    fun forgettingTheVolunteerKeepsWhatTheGuestStillUses() = runBlocking {
        val gateway = FakeGateway()
        val host = sharedAccount(gateway)
        // As in the app: the volunteer is still stored while the portal forgets it.
        service(gateway, host).forgetVolunteer(host.volunteers.single())

        assertNull(gateway.docs["volunteerPortal/$UID"])
        assertNull(gateway.docs["volunteerPortalQr/$UID"])
        assertNull(gateway.docs["portalRequests/${UID}_PROFILE"])
        assertNull(gateway.docs["portalRequests/${UID}_QR_EMAIL"])
        assertTrue(gateway.docs.containsKey("portalGoogleAccounts/$GOOGLE_UID"))
        assertTrue(gateway.docs.containsKey("portalGoogleClaims/$GOOGLE_UID"))
        assertTrue(gateway.docs.containsKey("guestPortal/$UID"))
        assertTrue(gateway.docs.containsKey("guestPortalQr/$UID"))
        (sharedRequests + listOf("GUEST_PROFILE", "GUEST_QR_EMAIL")).forEach {
            assertTrue(gateway.docs.containsKey("portalRequests/${UID}_$it"), it)
        }
        assertTrue(host.storage.containsKey("orgs/$ORG/portalPhotos/$UID.jpg"))
        assertTrue(host.storage.containsKey("orgs/$ORG/portalPhotoUploads/$UID.jpg"))
        assertEquals(UID, host.guests.single().portalUid)
    }

    @Test
    fun forgettingTheGuestKeepsTheVolunteerSide() = runBlocking {
        val gateway = FakeGateway()
        val host = sharedAccount(gateway)
        service(gateway, host).forgetGuest(host.guests.single())

        assertNull(gateway.docs["guestPortal/$UID"])
        assertNull(gateway.docs["guestPortalQr/$UID"])
        assertNull(gateway.docs["portalRequests/${UID}_GUEST_PROFILE"])
        assertNull(gateway.docs["portalRequests/${UID}_GUEST_QR_EMAIL"])
        assertTrue(gateway.docs.containsKey("portalGoogleAccounts/$GOOGLE_UID"))
        assertTrue(gateway.docs.containsKey("portalGoogleClaims/$GOOGLE_UID"))
        assertTrue(gateway.docs.containsKey("volunteerPortal/$UID"))
        assertTrue(gateway.docs.containsKey("volunteerPortalQr/$UID"))
        (sharedRequests + listOf("PROFILE", "QR_EMAIL")).forEach {
            assertTrue(gateway.docs.containsKey("portalRequests/${UID}_$it"), it)
        }
        assertTrue(host.storage.containsKey("orgs/$ORG/portalPhotos/$UID.jpg"))
        assertTrue(host.storage.containsKey("orgs/$ORG/portalPhotoUploads/$UID.jpg"))
        assertEquals(GOOGLE_UID, host.volunteers.single().portalGoogleUid)
    }

    @Test
    fun forgettingALoneGuestRemovesEverythingThePortalKept() = runBlocking {
        val gateway = FakeGateway()
        val host = sharedAccount(gateway)
        val g = host.guests.single()
        host.volunteers.clear()
        gateway.docs.remove("volunteerPortal/$UID")
        gateway.docs.remove("volunteerPortalQr/$UID")
        // Another account's documents stay.
        gateway.docs["guestPortal/OtherUid0000000000000001"] = mapOf("payload" to "{}")
        service(gateway, host).forgetGuest(g)

        assertEquals(setOf("guestPortal/OtherUid0000000000000001"), gateway.docs.keys)
        assertTrue(host.storage.isEmpty())
        assertTrue(host.loadPublishedHashes().keys.none { it.contains(UID) })
    }

    @Test
    fun forgettingSkipsAGuestOfAnotherOrganization() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList())
        gateway.docs["guestPortal/$UID"] = mapOf("payload" to "{}")
        service(gateway, host).forgetGuest(guest("G1", "max@example.org", uid = UID).copy(firebaseOrgId = "org-b"))
        assertTrue(gateway.docs.containsKey("guestPortal/$UID"))
    }

    // endregion

    private fun nameRequest(guestKey: String, first: String = "Anna", last: String = "Doe") = mapOf(
        "uid" to UID, "type" to "TEMP_GUEST_NAME", "status" to "PENDING", "email" to "c@example.org",
        "guestKey" to guestKey, "firstName" to first, "lastName" to last,
    )

    @Test
    fun renamesOwnTemporaryGuest() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { tempSpace = true }
        host.guests += tempGroup("T1", "c@example.org", uid = UID).copy(portalEnabled = true, name = "Ann", lastNameAbbreviation = "X")
        host.guests += tempGroup("T2", "c@example.org", uid = UID).copy(portalEnabled = true, name = "Bob")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_TEMP_GUEST_NAME"
        gateway.docs[key] = nameRequest(TemporaryGuestPortalQrDocument.guestKey(host.guests[0]), first = " Anna ", last = "")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("Anna", host.guests[0].name)
        assertEquals("X", host.guests[0].lastNameAbbreviation, "an empty field keeps the current value")
        assertEquals("Bob", host.guests[1].name)
    }

    @Test
    fun renameKeepsFirstNameAndUpperCasesLastName() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { tempSpace = true }
        host.guests += tempGroup("T1", "c@example.org", uid = UID).copy(portalEnabled = true, name = "Ann", lastNameAbbreviation = "X")
        startedService(gateway, host)
        val key = "portalRequests/${UID}_TEMP_GUEST_NAME"
        gateway.docs[key] = nameRequest(TemporaryGuestPortalQrDocument.guestKey(host.guests[0]), first = "", last = "doe")
        gateway.push("portalRequests")
        assertEquals("DONE", gateway.docs[key]!!["status"])
        assertEquals("Ann", host.guests[0].name)
        assertEquals("DOE", host.guests[0].lastNameAbbreviation)
    }

    @Test
    fun entryUsedRejected() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { tempSpace = true }
        host.guests += tempGroup("T1", "c@example.org", uid = UID).copy(portalEnabled = true, temporaryEntryValidatedAt = 5L)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_TEMP_GUEST_NAME"
        gateway.docs[key] = nameRequest(TemporaryGuestPortalQrDocument.guestKey(host.guests[0]))
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("ENTRY_USED", gateway.docs[key]!!["reason"])
        assertEquals("Band", host.guests[0].name)
    }

    @Test
    fun otherAccountNotFound() = runBlocking {
        val gateway = FakeGateway()
        val host = FakeHost(emptyList()).apply { tempSpace = true }
        host.guests += tempGroup("T1", "c@example.org", uid = "someoneElse").copy(portalEnabled = true)
        startedService(gateway, host)
        val key = "portalRequests/${UID}_TEMP_GUEST_NAME"
        gateway.docs[key] = nameRequest(TemporaryGuestPortalQrDocument.guestKey(host.guests[0]))
        gateway.push("portalRequests")
        assertEquals("REJECTED", gateway.docs[key]!!["status"])
        assertEquals("NOT_FOUND", gateway.docs[key]!!["reason"])
    }

    // region Certificate review

    private val reviewId = "${UID}_CERTIFICATE_EMAIL"
    private val reviewKey = "portalRequests/$reviewId"
    private val signer = com.eventmanager.app.portal.certificate.CertSigner("Chef", "Président", "")
    private val paragraph = com.eventmanager.app.portal.certificate.ResolvedParagraph(
        com.eventmanager.app.portal.certificate.CertAlign.LEFT,
        com.eventmanager.app.portal.certificate.CertSize.NORMAL,
        false,
        listOf(com.eventmanager.app.portal.certificate.ResolvedRun("Hello", true, false, false)),
    )

    private fun reviewDoc(status: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "uid" to UID, "type" to "CERTIFICATE_EMAIL", "status" to status, "email" to "lea@example.org",
        "from" to "2026-01-01", "to" to "2026-12-31", "jobTypes" to listOf("Bar"),
        "requestedAt" to System.currentTimeMillis() - 600_000,
    ) + extra

    private fun reviewService(gateway: FakeGateway) =
        startedService(gateway, FakeHost(listOf(volunteer("SECRETID123", "lea@example.org", enabled = true, uid = UID))))

    private fun approvals(gateway: FakeGateway) = gateway.docs.filterKeys { it.startsWith("certificateApprovals/") }

    @Test
    fun staleApprovalIsRetryableAndDeletesTheOldApproval() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("APPROVED", mapOf("approvalId" to "old", "approvedAt" to System.currentTimeMillis() - 6 * 60_000L))
        gateway.docs["certificateApprovals/old"] = mapOf("kind" to "REQUEST")
        gateway.push("portalRequests")
        assertTrue(service.certificateRequests.value.single().stuck)
        assertTrue(service.retryCertificate(reviewId))
        assertEquals("PENDING_REVIEW", gateway.docs[reviewKey]!!["status"])
        assertTrue("certificateApprovals/old" in gateway.deleted)
    }

    @Test
    fun freshApprovalIsNotRetryable() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("APPROVED", mapOf("approvalId" to "a", "approvedAt" to System.currentTimeMillis() - 60_000L))
        gateway.push("portalRequests")
        assertFalse(service.certificateRequests.value.single().stuck)
        assertFalse(service.retryCertificate(reviewId))
        assertEquals("APPROVED", gateway.docs[reviewKey]!!["status"])
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun approveWritesClaimThenApproval() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("PENDING_REVIEW")
        val result = service.approveCertificate(reviewId, listOf(paragraph), emptyList(), "Title", signer, "admin@asso.ch")
        assertEquals(ApproveResult.SENT_TO_SERVER, result)
        val request = gateway.docs[reviewKey]!!
        assertEquals("APPROVED", request["status"])
        assertNotNull(request["approvedAt"])
        val approvalId = request["approvalId"] as String
        val approval = gateway.docs["certificateApprovals/$approvalId"]!!
        assertEquals(
            setOf("kind", "requestId", "body", "footer", "title", "signer", "approvedBy", "approvedAt"),
            approval.keys,
        )
        assertEquals("REQUEST", approval["kind"])
        assertEquals(reviewId, approval["requestId"])
        assertEquals("admin@asso.ch", approval["approvedBy"])
        assertEquals(setOf("name", "title", "imageBase64"), (approval["signer"] as Map<*, *>).keys)
        assertTrue("SECRETID123" !in approval.toString() && "SECRETID123" !in request.toString())
        val body = (approval["body"] as List<*>).single() as Map<*, *>
        assertEquals("LEFT", body["align"])
        assertEquals("Hello", ((body["runs"] as List<*>).single() as Map<*, *>)["text"])
    }

    @Test
    fun secondApprovalLoses() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("APPROVED", mapOf("approvalId" to "first", "approvedAt" to System.currentTimeMillis()))
        val result = service.approveCertificate(reviewId, listOf(paragraph), emptyList(), "T", signer, "b@asso.ch")
        assertEquals(ApproveResult.ALREADY_HANDLED, result)
        assertTrue(approvals(gateway).isEmpty())
        assertEquals("first", gateway.docs[reviewKey]!!["approvalId"])
    }

    @Test
    fun approvalWriteFailureUndoesTheClaimWhenItCan() = runBlocking {
        val gateway = FakeGateway().apply { failApprovalWrites = true }
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("PENDING_REVIEW")
        assertEquals(ApproveResult.FAILED, service.approveCertificate(reviewId, listOf(paragraph), emptyList(), "T", signer, "a"))
        val request = gateway.docs[reviewKey]!!
        assertEquals("PENDING_REVIEW", request["status"])
        assertTrue((request["approvalId"] as String?).isNullOrBlank())
        assertEquals(null, request["approvedAt"])
        assertTrue(approvals(gateway).isEmpty())
        // The admin can approve again at once.
        gateway.failApprovalWrites = false
        assertEquals(ApproveResult.SENT_TO_SERVER, service.approveCertificate(reviewId, listOf(paragraph), emptyList(), "T", signer, "a"))
    }

    @Test
    fun tooLargeIsRefusedLocally() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("PENDING_REVIEW")
        val big = paragraph.copy(runs = listOf(com.eventmanager.app.portal.certificate.ResolvedRun("x".repeat(200_001), false, false, false)))
        assertEquals(ApproveResult.TOO_LARGE, service.approveCertificate(reviewId, listOf(big), emptyList(), "T", signer, "a"))
        val bigImage = signer.copy(imageBase64 = "x".repeat(200_001))
        assertEquals(ApproveResult.TOO_LARGE, service.approveCertificate(reviewId, listOf(paragraph), emptyList(), "T", bigImage, "a"))
        assertEquals("PENDING_REVIEW", gateway.docs[reviewKey]!!["status"])
        assertTrue(approvals(gateway).isEmpty())
    }

    @Test
    fun refuseWritesReasonAndNote() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        gateway.docs[reviewKey] = reviewDoc("PENDING_REVIEW")
        assertTrue(service.refuseCertificate(reviewId, "  " + "n".repeat(400)))
        val request = gateway.docs[reviewKey]!!
        assertEquals("REJECTED", request["status"])
        assertEquals("REFUSED", request["reason"])
        assertEquals("n".repeat(300), request["note"])
        assertNotNull(request["processedAt"])
        assertFalse(service.refuseCertificate(reviewId, "again"))
    }

    @Test
    fun directSendWritesNoVolunteerId() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        val id = assertNotNull(service.sendDirectCertificate(
            volunteer("SECRETID123", "lea@example.org"), listOf(paragraph), emptyList(), "Title", signer, "admin@asso.ch",
        ))
        val approval = gateway.docs["certificateApprovals/$id"]!!
        assertEquals(
            setOf("kind", "email", "firstName", "lastName", "body", "footer", "title", "signer", "approvedBy", "approvedAt"),
            approval.keys,
        )
        assertEquals("DIRECT", approval["kind"])
        assertEquals("lea@example.org", approval["email"])
        assertEquals("Léa", approval["firstName"])
        assertEquals("MARTIN", approval["lastName"])
        assertTrue("SECRETID123" !in approval.toString())
    }

    @Test
    fun directSendStatusFollowsTheApproval() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        val id = assertNotNull(service.sendDirectCertificate(
            volunteer("V9", "lea@example.org"), listOf(paragraph), emptyList(), "T", signer, "a",
        ))
        gateway.push("certificateApprovals")
        assertEquals(DirectSendStatus.Pending, service.directSendStatus(id).first())
        gateway.docs["certificateApprovals/$id"] = gateway.docs["certificateApprovals/$id"]!! + mapOf("status" to "FAILED", "reason" to "SEND_FAILED")
        gateway.push("certificateApprovals")
        assertEquals(DirectSendStatus.Failed("SEND_FAILED"), service.directSendStatus(id).first())
        assertEquals(listOf("SEND_FAILED"), service.failedDirectSends.value.map { it.reason })
        gateway.docs.remove("certificateApprovals/$id")
        gateway.push("certificateApprovals")
        assertEquals(DirectSendStatus.Sent, service.directSendStatus(id).first())
        assertTrue(service.failedDirectSends.value.isEmpty())
    }

    @Test
    fun directSendDeletedWithoutAnySnapshotOfItIsSent() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        val id = assertNotNull(service.sendDirectCertificate(
            volunteer("V9", "lea@example.org"), listOf(paragraph), emptyList(), "T", signer, "a",
        ))
        // The server was quick: the first snapshot after the write no longer lists the approval.
        gateway.listeners["certificateApprovals"]!!.invoke(emptyList())
        assertEquals(DirectSendStatus.Sent, service.directSendStatus(id).first())
    }

    @Test
    fun directSendNeverPickedUpFailsAfterFiveMinutes() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        val base = mapOf<String, Any?>("kind" to "DIRECT", "firstName" to "Léa", "lastName" to "M")
        gateway.docs["certificateApprovals/old"] = base + ("approvedAt" to System.currentTimeMillis() - 6 * 60_000L)
        gateway.docs["certificateApprovals/fresh"] = base + ("approvedAt" to System.currentTimeMillis() - 60_000L)
        gateway.push("certificateApprovals")
        assertEquals(listOf("old"), service.failedDirectSends.value.map { it.approvalId })
        assertEquals(DirectSendStatus.Failed("SEND_FAILED"), service.directSendStatus("old").first())
        assertEquals(DirectSendStatus.Pending, service.directSendStatus("fresh").first())
    }

    @Test
    fun directSendStatusTurnsFailedWhenProcessingGetsOld() = runBlocking {
        val gateway = FakeGateway()
        var now = 10_000_000L
        val host = FakeHost(listOf(volunteer("V1", "lea@example.org", enabled = true, uid = UID)))
        val service = VolunteerPortalService(
            ORG, gateway, host, scope, authClientFactory = { FakeAuth(PortalAuthResult.Sent) },
            clock = { now }, publishDebounceMs = 0, retryIntervalMs = 50,
        )
        service.start(MutableStateFlow(host.volunteers.toList()), MutableStateFlow(emptyList<Guest>()), MutableStateFlow(emptyList<Job>()),
            MutableStateFlow(emptyList<AccountTransfer>()), MutableStateFlow(emptyList<JobTypeConfig>()))
        gateway.docs["certificateApprovals/p"] = mapOf(
            "kind" to "DIRECT", "firstName" to "Léa", "lastName" to "M", "status" to "PROCESSING", "processingAt" to now,
        )
        gateway.push("certificateApprovals")
        assertEquals(DirectSendStatus.Pending, service.directSendStatus("p").first())
        now += 6 * 60_000L
        // The periodic loop bumps the tick; no new snapshot arrives.
        val failed = withTimeout(5_000) { service.directSendStatus("p").first { it is DirectSendStatus.Failed } }
        assertEquals(DirectSendStatus.Failed("SEND_FAILED"), failed)
    }

    @Test
    fun failedDirectSendsIncludeStaleProcessing() = runBlocking {
        val gateway = FakeGateway()
        val service = reviewService(gateway)
        val base = mapOf<String, Any?>("kind" to "DIRECT", "firstName" to "Léa", "lastName" to "M", "status" to "PROCESSING")
        gateway.docs["certificateApprovals/old"] = base + ("processingAt" to System.currentTimeMillis() - 6 * 60_000L)
        gateway.docs["certificateApprovals/fresh"] = base + ("processingAt" to System.currentTimeMillis() - 60_000L)
        gateway.push("certificateApprovals")
        val failed = service.failedDirectSends.value.single()
        assertEquals("old", failed.approvalId)
        assertEquals("SEND_FAILED", failed.reason)
    }

    // endregion
}
