package com.eventmanager.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirebaseConfiguredOrgCodecTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val orgs = listOf(
            FirebaseConfiguredOrg("club-a", 0xFFE53935L),
            FirebaseConfiguredOrg("club-b", 0xFF1E88E5L),
        )
        val encoded = FirebaseConfiguredOrgCodec.encode(orgs)
        val decoded = FirebaseConfiguredOrgCodec.decode(encoded)
        assertEquals(orgs, decoded)
    }

    @Test
    fun normalizeRequiresAtLeastOneOrg() {
        assertFailsWith<IllegalArgumentException> {
            FirebaseConfiguredOrgCodec.normalize(emptyList())
        }
    }

    @Test
    fun normalizeDeduplicatesOrgIds() {
        val normalized = FirebaseConfiguredOrgCodec.normalize(
            listOf(
                FirebaseConfiguredOrg("club-a", 0xFFE53935L),
                FirebaseConfiguredOrg("club-a", 0xFF1E88E5L),
                FirebaseConfiguredOrg("club-b", 0xFF43A047L),
            ),
        )
        assertEquals(2, normalized.size)
        assertEquals(listOf("club-a", "club-b"), normalized.map { it.orgId })
    }

    @Test
    fun migrateFromSingleOrgId() {
        val migrated = FirebaseConfiguredOrgCodec.migrateFromSingleOrgId("legacy-org")
        assertEquals(1, migrated.size)
        assertEquals("legacy-org", migrated.first().orgId)
        assertTrue(migrated.first().colorArgb != 0L)
    }

    @Test
    fun mergeRemoteKeepsLocalOnlyOrgsAndDropsSharedOnesThePushLeftOut() {
        val local = listOf(
            FirebaseConfiguredOrg("club-a", 0xFFE53935L),
            FirebaseConfiguredOrg("club-old", 0xFF8E24AAL),
            FirebaseConfiguredOrg("perso", 0xFF43A047L, localOnly = true),
        )
        val remote = listOf(
            FirebaseConfiguredOrg("club-a", 0xFF1E88E5L, displayName = "Club A"),
            FirebaseConfiguredOrg("club-b", 0xFFFB8C00L),
        )

        val merged = FirebaseConfiguredOrgCodec.mergeRemote(local, remote)

        assertEquals(listOf("club-a", "club-b", "perso"), merged.map { it.orgId })
        assertEquals("Club A", merged.first().label)
        assertEquals(0xFF1E88E5L, merged.first().colorArgb)
        assertEquals(listOf(false, false, true), merged.map { it.localOnly })
    }

    @Test
    fun mergeRemoteMakesALocalOrgSharedOnceTheOrgListHasIt() {
        val local = listOf(
            FirebaseConfiguredOrg("club-a", 0xFFE53935L),
            FirebaseConfiguredOrg("club-b", 0xFF1E88E5L, localOnly = true),
        )
        val remote = listOf(FirebaseConfiguredOrg("club-a", 0xFFE53935L), FirebaseConfiguredOrg("club-b", 0xFF1E88E5L))

        val merged = FirebaseConfiguredOrgCodec.mergeRemote(local, remote)

        assertEquals(listOf(false, false), merged.map { it.localOnly })
    }

    @Test
    fun mergeRemoteIgnoresAnEmptyPush() {
        val local = listOf(FirebaseConfiguredOrg("club-a", 0xFFE53935L))
        assertEquals(local, FirebaseConfiguredOrgCodec.mergeRemote(local, emptyList()))
    }

    @Test
    fun encodeSharedLeavesLocalOnlyOrgsOut() {
        val orgs = listOf(
            FirebaseConfiguredOrg("club-a", 0xFFE53935L),
            FirebaseConfiguredOrg("perso", 0xFF43A047L, localOnly = true),
        )
        val shared = FirebaseConfiguredOrgCodec.decode(FirebaseConfiguredOrgCodec.encodeShared(orgs)!!)
        assertEquals(listOf("club-a"), shared.map { it.orgId })
        assertEquals(null, FirebaseConfiguredOrgCodec.encodeShared(orgs.drop(1)))
    }

    @Test
    fun renameSetsTheLabelButNeverTheOrgId() {
        val orgs = listOf(FirebaseConfiguredOrg("club-a", 0xFFE53935L))
        val renamed = FirebaseConfiguredOrgCodec.rename(orgs, "club-a", "Club\nA  Lausanne")
        assertEquals("club-a", renamed.single().orgId)
        assertEquals("Club A Lausanne", renamed.single().label)
        // A name equal to the ID, or blank, clears the display name.
        assertEquals("", FirebaseConfiguredOrgCodec.rename(renamed, "club-a", "club-a").single().displayName)
        assertEquals("", FirebaseConfiguredOrgCodec.rename(renamed, "club-a", " ").single().displayName)
        val long = "x".repeat(200)
        assertEquals(
            FirebaseConfiguredOrgCodec.DISPLAY_NAME_MAX_LENGTH,
            FirebaseConfiguredOrgCodec.rename(orgs, "club-a", long).single().displayName.length,
        )
    }

    @Test
    fun olderListsWithoutTheNewFieldsStillDecode() {
        val decoded = FirebaseConfiguredOrgCodec.decode("""[{"orgId":"club-a","colorArgb":4293212469}]""")
        assertEquals("club-a", decoded.single().label)
        assertEquals(false, decoded.single().localOnly)
    }

    private val a = FirebaseConfiguredOrg("club-a", 0xFFE53935L, displayName = "Club A")
    private val b = FirebaseConfiguredOrg("club-b", 0xFF1E88E5L, displayName = "Club B")

    @Test
    fun theSourceOrgGetsTheWholeSharedList() {
        val written = FirebaseConfiguredOrgCodec.listForTarget(
            targetOrgId = "club-a", sourceOrgId = "club-a", deviceShared = listOf(a, b), targetRemote = listOf(a),
        )
        assertEquals(listOf(a, b), written)
    }

    @Test
    fun anotherOrgNeverGetsOrgsItDoesNotHold() {
        // Adding B for every device of A must not put A on the devices of B: they are not members.
        val bOwnList = listOf(b.copy(displayName = ""))
        assertEquals(
            null,
            FirebaseConfiguredOrgCodec.listForTarget("club-b", "club-a", listOf(a, b.copy(displayName = "")), bOwnList),
        )
        // A rename still reaches the orgs that already hold the renamed org.
        assertEquals(
            listOf(b),
            FirebaseConfiguredOrgCodec.listForTarget("club-b", "club-a", listOf(a, b), bOwnList),
        )
        // An org whose list cannot be read is left alone.
        assertEquals(null, FirebaseConfiguredOrgCodec.listForTarget("club-b", "club-a", listOf(a, b), emptyList()))
    }
}

