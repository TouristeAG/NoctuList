package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ORG = "org-a"
private const val CONTACT = "manager@example.org"

class TemporaryGuestPortalAccessTest {
    private val host = FakeHost(emptyList()).apply { tempSpace = true }
    private var republished = 0

    private fun temp(
        nanoId: String,
        artist: String = "Band",
        email: String = CONTACT,
        org: String = ORG,
        uid: String = "",
        enabled: Boolean = false,
    ) = Guest(
        nanoId = nanoId,
        name = nanoId,
        invitations = 1,
        venueName = "Club",
        isTemporaryGuest = true,
        temporaryArtistName = artist,
        temporaryEventDate = 1_800_000_000_000L,
        temporaryVenueName = "Club",
        temporaryContactEmail = email,
        firebaseOrgId = org,
        portalUid = uid,
        portalEnabled = enabled,
    )

    private fun access(auth: PortalAuthApi) = TemporaryGuestPortalAccess(
        orgId = ORG,
        host = host,
        authClientFactory = { auth },
        republish = { republished++ },
        clock = { 1L },
    )

    private fun batch(vararg ids: String) = ids.map { temp(it) }.also { host.guests += it }

    @Test
    fun openCreatesAccountAndLinksWholeBatch() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val b = batch("G1", "G2")
        val result = access(auth).open(b)
        val created = assertIs<TemporaryGroupAccessResult.Created>(result)
        assertEquals("uid-1", created.uid)
        assertTrue(created.password.length >= 12)
        assertEquals(listOf(CONTACT), auth.creates)
        host.guests.forEach {
            assertTrue(it.portalEnabled)
            assertEquals("uid-1", it.portalUid)
            assertEquals(CONTACT, it.temporaryPortalEmail)
        }
        assertEquals(1, republished)
    }

    @Test
    fun aVolunteerWithTheSameAddressIsNeverReusedAndGoesThroughCreateAccount() = runBlocking {
        // The volunteer's uid may come from an unverified registration: the group waits for a verified CLAIM.
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        host.volunteers += Volunteer(
            id = "V1", name = "Lea", lastNameAbbreviation = "M", email = "MANAGER@example.org",
            phoneNumber = "", portalEnabled = true, portalUid = "uid-vol", firebaseOrgId = ORG,
        )
        val result = access(auth).open(batch("G1"))
        assertEquals(TemporaryGroupAccessResult.ExistingAccount, result)
        assertEquals(listOf(CONTACT), auth.creates)
        assertEquals("", host.guests.single().portalUid)
        assertTrue(host.guests.single().portalEnabled)
    }

    @Test
    fun anotherOpenGroupForTheSameAddressIsReused() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("never"))
        host.guests += temp("OTHER", artist = "Other", uid = "uid-group", enabled = true)
            .copy(temporaryPortalEmail = "Manager@Example.org")
        val result = access(auth).open(batch("G1"))
        assertEquals(TemporaryGroupAccessResult.Reused("uid-group"), result)
        assertTrue(auth.creates.isEmpty())
        assertEquals("uid-group", host.guests.single { it.nanoId == "G1" }.portalUid)
    }

    @Test
    fun reuseIgnoresOtherOrganizations() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-new"))
        host.guests += temp("OTHER", artist = "Other", org = "org-b", uid = "uid-b", enabled = true)
        val result = access(auth).open(batch("G1"))
        assertIs<TemporaryGroupAccessResult.Created>(result)
        Unit
    }

    @Test
    fun existingAccountPublishesNothingUntilClaim() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        val result = access(auth).open(batch("G1", "G2"))
        assertEquals(TemporaryGroupAccessResult.ExistingAccount, result)
        host.guests.forEach {
            assertTrue(it.portalEnabled)
            assertEquals("", it.portalUid)
            assertEquals(CONTACT, it.temporaryPortalEmail)
        }
    }

    @Test
    fun openSendsNoMail() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        access(auth).open(batch("G1"))
        assertTrue(auth.resets.isEmpty())
        assertTrue(host.guestMailed.isEmpty())
    }

    @Test
    fun closeDisablesBatchOnlyAndKeepsItsUid() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val a = batch("G1", "G2")
        val other = temp("G3", artist = "Other", email = "other@example.org", uid = "uid-2", enabled = true)
            .copy(temporaryPortalEmail = "other@example.org")
        host.guests += other
        val acc = access(auth)
        acc.open(a)
        republished = 0
        val before = host.guests.filter { it.nanoId in setOf("G1", "G2") }
        assertTrue(acc.close(before))
        val after = host.guests.filter { it.nanoId in setOf("G1", "G2") }
        after.forEach {
            assertFalse(it.portalEnabled)
            assertEquals("uid-1", it.portalUid)
            assertEquals(CONTACT, it.temporaryPortalEmail)
        }
        after.forEach { g -> assertTrue(g.lastModified > before.first { it.nanoId == g.nanoId }.lastModified) }
        assertNull(acc.openedFor(after))
        val untouched = host.guests.first { it.nanoId == "G3" }
        assertTrue(untouched.portalEnabled)
        assertEquals("uid-2", untouched.portalUid)
        assertEquals(1, republished)
    }

    @Test
    fun closeThenReopenReusesTheSameUidWithoutCreatingAnAccount() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val acc = access(auth)
        acc.open(batch("G1", "G2"))
        acc.close(host.guests.toList())
        val result = acc.open(host.guests.toList())
        assertEquals(TemporaryGroupAccessResult.Reused("uid-1"), result)
        assertEquals(1, auth.creates.size)
        host.guests.forEach { assertTrue(it.portalEnabled); assertEquals("uid-1", it.portalUid) }
    }

    @Test
    fun openAndCloseBumpLastModified() = runBlocking {
        val acc = access(FakeAuth(PortalAuthResult.Created("uid-1")))
        val b = batch("G1").map { it.copy(lastModified = 500L) }
        host.guests.clear(); host.guests += b
        acc.open(b)
        val opened = host.guests.single()
        assertEquals(501L, opened.lastModified)
        acc.close(listOf(opened))
        assertEquals(502L, host.guests.single().lastModified)
    }

    @Test
    fun anotherGroupIsNeverReusedThroughAnEditedContactAddress() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-new"))
        val acc = access(auth)
        val a = listOf(temp("A1", artist = "A", email = "old@example.org")).also { host.guests += it }
        assertEquals("uid-new", (acc.open(a) as TemporaryGroupAccessResult.Created).uid)
        host.guests[0] = host.guests[0].copy(temporaryContactEmail = "new@example.org")
        val authB = FakeAuth(PortalAuthResult.Created("uid-b"))
        val b = listOf(temp("B1", artist = "B", email = "new@example.org")).also { host.guests += it }
        val result = access(authB).open(b)
        assertEquals(TemporaryGroupAccessResult.Created("uid-b", (result as TemporaryGroupAccessResult.Created).password), result)
        assertEquals(listOf("new@example.org"), authB.creates)
    }

    @Test
    fun saveFailureKeepsTheNewPasswordVisible() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val g = temp("GHOST")
        // Not in the host list: saveGuest returns false.
        val result = access(auth).open(listOf(g))
        val failed = assertIs<TemporaryGroupAccessResult.Failed>(result)
        assertEquals("SAVE_FAILED", failed.code)
        assertTrue(failed.password!!.length >= 12)
    }

    @Test
    fun existingAccountOpenedMeanwhileByAnotherDeviceIsReused() = runBlocking {
        // The other device linked the group between our read and the EMAIL_EXISTS answer.
        val b = batch("G1", "G2")
        val auth = object : PortalAuthApi {
            override suspend fun createAccount(email: String, password: String): PortalAuthResult {
                host.guests.replaceAll {
                    it.copy(portalEnabled = true, portalUid = "uid-other", temporaryPortalEmail = CONTACT)
                }
                return PortalAuthResult.EmailExists
            }
            override suspend fun sendPasswordReset(email: String, languageCode: String) = PortalAuthResult.Sent
        }
        val result = access(auth).open(b)
        assertEquals(TemporaryGroupAccessResult.Reused("uid-other"), result)
        host.guests.forEach { assertEquals("uid-other", it.portalUid) }
    }

    @Test
    fun aPermanentGuestWithTheSameAddressIsNeverReused() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.EmailExists)
        host.guests += Guest(
            nanoId = "P1", name = "P", invitations = 1, venueName = "Club", email = "Manager@Example.org",
            firebaseOrgId = ORG, portalEnabled = true, portalUid = "uid-guest",
        )
        val result = access(auth).open(batch("G1"))
        assertEquals(TemporaryGroupAccessResult.ExistingAccount, result)
        assertEquals(listOf(CONTACT), auth.creates)
        assertEquals("", host.guests.single { it.nanoId == "G1" }.portalUid)
    }

    @Test
    fun aVolunteerLinkedMeanwhileIsNotReusedAfterEmailExists() = runBlocking {
        val b = batch("G1")
        val auth = object : PortalAuthApi {
            override suspend fun createAccount(email: String, password: String): PortalAuthResult {
                host.volunteers += Volunteer(
                    id = "V1", name = "Lea", lastNameAbbreviation = "M", email = CONTACT,
                    phoneNumber = "", portalEnabled = true, portalUid = "uid-vol", firebaseOrgId = ORG,
                )
                return PortalAuthResult.EmailExists
            }
            override suspend fun sendPasswordReset(email: String, languageCode: String) = PortalAuthResult.Sent
        }
        assertEquals(TemporaryGroupAccessResult.ExistingAccount, access(auth).open(b))
        assertEquals("", host.guests.single().portalUid)
    }

    @Test
    fun contactEmailChangeKeepsUidAndFlagsMismatch() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val acc = access(auth)
        acc.open(batch("G1", "G2"))
        val changed = host.guests.map { it.copy(temporaryContactEmail = "new@example.org") }
        host.guests.clear(); host.guests += changed
        assertEquals(CONTACT, acc.openedFor(changed))
        assertEquals("uid-1", changed.first().portalUid)
    }

    @Test
    fun openedForIsNullWhenNotOpen() {
        val acc = access(FakeAuth(PortalAuthResult.Sent))
        assertNull(acc.openedFor(listOf(temp("G1"))))
    }

    @Test
    fun openRefusedWithoutContactEmailOrSpaceOff() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val acc = access(auth)
        val noMail = listOf(temp("G1", email = "")).also { host.guests += it }
        assertEquals(TemporaryGroupAccessResult.Failed("NO_CONTACT_EMAIL"), acc.open(noMail))
        assertEquals(TemporaryGroupAccessResult.Failed("EMPTY_BATCH"), acc.open(emptyList()))
        host.tempSpace = false
        assertEquals(TemporaryGroupAccessResult.Failed("TEMP_SPACE_OFF"), acc.open(batch("G2")))
        assertTrue(auth.creates.isEmpty())
        assertEquals(0, republished)
    }
}
