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

    private fun access(auth: FakeAuth) = TemporaryGuestPortalAccess(
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
    fun openReusesKnownUid() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("never"))
        host.volunteers += Volunteer(
            id = "V1", name = "Lea", lastNameAbbreviation = "M", email = "MANAGER@example.org",
            phoneNumber = "", portalEnabled = true, portalUid = "uid-vol", firebaseOrgId = ORG,
        )
        val result = access(auth).open(batch("G1"))
        assertEquals(TemporaryGroupAccessResult.Reused("uid-vol"), result)
        assertTrue(auth.creates.isEmpty())
        assertEquals("uid-vol", host.guests.single().portalUid)
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
    fun closeClearsBatchOnly() = runBlocking {
        val auth = FakeAuth(PortalAuthResult.Created("uid-1"))
        val a = batch("G1", "G2")
        val other = temp("G3", artist = "Other", email = "other@example.org", uid = "uid-2", enabled = true)
            .copy(temporaryPortalEmail = "other@example.org")
        host.guests += other
        val acc = access(auth)
        acc.open(a)
        republished = 0
        assertTrue(acc.close(host.guests.filter { it.nanoId in setOf("G1", "G2") }))
        host.guests.filter { it.nanoId in setOf("G1", "G2") }.forEach {
            assertFalse(it.portalEnabled)
            assertEquals("", it.portalUid)
            assertEquals("", it.temporaryPortalEmail)
        }
        val untouched = host.guests.first { it.nanoId == "G3" }
        assertTrue(untouched.portalEnabled)
        assertEquals("uid-2", untouched.portalUid)
        assertEquals(1, republished)
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
