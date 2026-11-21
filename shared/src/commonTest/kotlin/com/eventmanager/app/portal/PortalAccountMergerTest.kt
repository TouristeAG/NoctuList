package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortalAccountMergerTest {
    private fun vol(id: String, email: String, uid: String = "", enabled: Boolean = uid.isNotEmpty(), google: String = "", revoked: Boolean = false) =
        Volunteer(
            id = id, name = "V$id", lastNameAbbreviation = "X", email = email, phoneNumber = "",
            portalEnabled = enabled, portalUid = uid, portalGoogleEmail = google, portalRevoked = revoked,
        )

    private fun guest(nano: String, email: String, uid: String = "", enabled: Boolean = uid.isNotEmpty(), google: String = "", revoked: Boolean = false) =
        Guest(
            nanoId = nano, name = "G$nano", invitations = 0, venueName = "Main", email = email,
            portalEnabled = enabled, portalUid = uid, portalGoogleEmail = google, portalRevoked = revoked,
        )

    private fun temp(nano: String, contact: String, uid: String = "", enabled: Boolean = uid.isNotEmpty()) =
        Guest(
            nanoId = nano, name = "T$nano", invitations = 0, venueName = "Main", isTemporaryGuest = true,
            temporaryEventDate = 1L, temporaryContactEmail = contact, portalEnabled = enabled, portalUid = uid,
        )

    private fun plan(vs: List<Volunteer>, gs: List<Guest>, guestSpace: Boolean = true, tempSpace: Boolean = true) =
        PortalAccountMerger.plan(vs, gs, guestSpace, tempSpace)

    @Test
    fun volunteerJoinsGuestAccount() {
        val p = plan(listOf(vol("v1", "Ada@Example.org ")), listOf(guest("g1", "ada@example.org", uid = "U1")))
        val v = p.volunteers.single()
        assertEquals("U1", v.portalUid)
        assertTrue(v.portalEnabled)
        assertTrue(p.guests.isEmpty())
    }

    @Test
    fun guestJoinsVolunteerByGoogleEmail() {
        val p = plan(listOf(vol("v1", "ada@home.org", uid = "U1", google = "ada@assoc.org")), listOf(guest("g1", "ada@assoc.org")))
        assertEquals("U1", p.guests.single().portalUid)
        assertTrue(p.guests.single().portalEnabled)
    }

    @Test
    fun temporaryListJoinsVolunteerAccount() {
        val p = plan(listOf(vol("v1", "ada@example.org", uid = "U1")), listOf(temp("t1", "ada@example.org"), temp("t2", "ada@example.org")))
        assertEquals(setOf("t1", "t2"), p.guests.map { it.nanoId }.toSet())
        assertTrue(p.guests.all { it.portalUid == "U1" && it.portalEnabled })
    }

    @Test
    fun nothingWithoutAnAccount() {
        val p = plan(listOf(vol("v1", "ada@example.org")), listOf(guest("g1", "ada@example.org"), temp("t1", "ada@example.org")))
        assertTrue(p.volunteers.isEmpty() && p.guests.isEmpty() && p.conflicts.isEmpty())
    }

    @Test
    fun guestSpaceOffLeavesGuestAlone() {
        val p = plan(listOf(vol("v1", "ada@example.org", uid = "U1")), listOf(guest("g1", "ada@example.org"), temp("t1", "ada@example.org")), guestSpace = false, tempSpace = false)
        assertTrue(p.guests.isEmpty())
    }

    @Test
    fun revokedProfileStaysOff() {
        val p = plan(listOf(vol("v1", "ada@example.org", revoked = true)), listOf(guest("g1", "ada@example.org", uid = "U1")))
        assertTrue(p.volunteers.isEmpty())
    }

    @Test
    fun closedListKeepsItsState() {
        // A list the admin closed keeps its uid with the space off: it is not reopened.
        val p = plan(listOf(vol("v1", "ada@example.org", uid = "U1")), listOf(temp("t1", "ada@example.org", uid = "U1", enabled = false)))
        assertTrue(p.guests.isEmpty())
    }

    @Test
    fun twoAccountsIsAConflict() {
        val p = plan(
            listOf(vol("v1", "ada@example.org", uid = "U1")),
            listOf(guest("g1", "ada@example.org", uid = "U2"), temp("t1", "ada@example.org")),
        )
        assertEquals(setOf("ada@example.org"), p.conflicts)
        assertTrue(p.guests.isEmpty() && p.volunteers.isEmpty())
        assertTrue(PortalAccountMerger.conflictFor(setOf("ada@example.org"), p))
    }

    @Test
    fun changedAddressLeavesSharedAccount() {
        val p = plan(
            listOf(vol("v1", "new@example.org", uid = "U1")),
            listOf(guest("g1", "ada@example.org", uid = "U1")),
        )
        val v = p.volunteers.single()
        assertEquals("", v.portalUid)
        assertTrue(v.portalEnabled)
    }

    @Test
    fun changedAddressJoinsTheNewAccount() {
        val p = plan(
            listOf(vol("v1", "bob@example.org", uid = "U1")),
            listOf(guest("g1", "ada@example.org", uid = "U1"), guest("g2", "bob@example.org", uid = "U2")),
        )
        assertEquals("U2", p.volunteers.single().portalUid)
    }

    @Test
    fun planIsIdempotent() {
        val vs = listOf(vol("v1", "ada@example.org"), vol("v2", "new@example.org", uid = "U9"))
        val gs = listOf(guest("g1", "ada@example.org", uid = "U1"), temp("t1", "ada@example.org"), guest("g9", "old@example.org", uid = "U9"))
        val first = plan(vs, gs)
        val vs2 = vs.map { v -> first.volunteers.firstOrNull { it.id == v.id } ?: v }
        val gs2 = gs.map { g -> first.guests.firstOrNull { it.nanoId == g.nanoId } ?: g }
        val second = plan(vs2, gs2)
        assertTrue(second.volunteers.isEmpty() && second.guests.isEmpty(), "second plan: $second")
    }
}
