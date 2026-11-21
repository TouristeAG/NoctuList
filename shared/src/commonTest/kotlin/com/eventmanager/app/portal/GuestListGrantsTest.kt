package com.eventmanager.app.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestListGrantsTest {
    private val event = 1_800_000_000_000L
    private val until = TemporaryGuestPortalProjectionBuilder.visibleUntil(event)

    private fun grant(id: String, date: Long = event, venue: String = "") =
        GuestListGrant(id = id, eventDateMillis = date, venueName = venue, grantedBy = "Admin", grantedAt = 1L)

    @Test
    fun activeUntilEightNextDay() {
        val g = grant("A")
        assertTrue(g.isActive(until - 1))
        assertFalse(g.isActive(until))
        val raw = GuestListGrants.add("", g)
        assertEquals(listOf("A"), GuestListGrants.active(raw, until - 1).map { it.id })
        assertEquals(listOf("A"), GuestListGrants.history(raw, until).map { it.id })
    }

    @Test
    fun revokedGoesToHistory() {
        var raw = GuestListGrants.add("", grant("A"))
        raw = GuestListGrants.add(raw, grant("B", date = event + 86_400_000L))
        raw = GuestListGrants.revoke(raw, "A", "Admin", 5L)
        assertEquals(listOf("B"), GuestListGrants.active(raw, event).map { it.id })
        val history = GuestListGrants.history(raw, event)
        assertEquals(listOf("A"), history.map { it.id })
        assertEquals(5L, history.single().revokedAt)
        assertEquals("Admin", history.single().revokedBy)
        // Revoking twice keeps the first revocation.
        assertEquals(5L, GuestListGrants.decode(GuestListGrants.revoke(raw, "A", "Other", 9L)).first { it.id == "A" }.revokedAt)
    }

    @Test
    fun historyNewestFirst() {
        var raw = ""
        listOf(1L, 3L, 2L).forEach { raw = GuestListGrants.add(raw, grant("G$it", date = event - it * 10 * 86_400_000L)) }
        assertEquals(listOf("G1", "G2", "G3"), GuestListGrants.history(raw, event).map { it.id })
    }

    @Test
    fun mergeAcrossRowsOfOneAccount() {
        val a = GuestListGrants.add("", grant("A"))
        val ab = GuestListGrants.add(a, grant("B"))
        val revokedA = GuestListGrants.revoke(a, "A", "Admin", 3L)
        val merged = GuestListGrants.decode(GuestListGrants.merge(listOf(ab, revokedA, "")))
        assertEquals(listOf("A", "B"), merged.map { it.id }.sorted())
        assertEquals(3L, merged.first { it.id == "A" }.revokedAt, "a revocation on any row wins")
    }

    @Test
    fun decodeIgnoresGarbage() {
        assertEquals(emptyList(), GuestListGrants.decode(""))
        assertEquals(emptyList(), GuestListGrants.decode("not json"))
        assertEquals(emptyList(), GuestListGrants.decode("""[{"id":"","eventDateMillis":5}]"""))
    }
}
