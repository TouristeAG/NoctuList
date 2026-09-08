package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiOrgMergeTest {

    @Test
    fun mergeVenueFilters_combinesSameNameAcrossOrgs() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Main", firebaseOrgId = "org-a", isActive = true),
            VenueEntity(id = 2, name = "Main", firebaseOrgId = "org-b", isActive = true),
            VenueEntity(id = 3, name = "Bar", firebaseOrgId = "org-a", isActive = true),
        )
        val merged = MultiOrgMerge.mergeVenueFilters(venues)
        assertEquals(2, merged.size)
        val main = merged.first { it.displayName == "Main" }
        assertEquals(listOf("org-a", "org-b"), main.orgIds.sorted())
    }

    @Test
    fun mergePosProducts_keepsDistinctSettingsSeparate() {
        val items = listOf(
            SalesSheetItem(id = 1, name = "Beer", price = 5.0, firebaseOrgId = "org-a", isActive = true),
            SalesSheetItem(id = 2, name = "Beer", price = 6.0, firebaseOrgId = "org-b", isActive = true),
        )
        val merged = MultiOrgMerge.mergePosProducts(items)
        assertEquals(2, merged.size)
    }

    @Test
    fun mergePosProducts_mergesIdenticalItems() {
        val items = listOf(
            SalesSheetItem(id = 1, name = "Beer", price = 5.0, firebaseOrgId = "org-a", isActive = true),
            SalesSheetItem(id = 2, name = "Beer", price = 5.0, firebaseOrgId = "org-b", isActive = true),
        )
        val merged = MultiOrgMerge.mergePosProducts(items)
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().sources.size)
    }

    @Test
    fun configuredOrgIdsOnly_excludesUnconfiguredOrgs() {
        val configured = listOf("org-a", "org-b")
        val data = listOf("org-a", "org-b", "org-c")
        assertEquals(listOf("org-a", "org-b"), MultiOrgMerge.configuredOrgIdsOnly(configured, data))
    }

    @Test
    fun findGuestsByNfcUid_returnsAllOrgMatches() {
        val guests = listOf(
            Guest(nanoId = "g1", name = "A", invitations = 1, venueName = "Main", nfcCardUid = "ABC", firebaseOrgId = "org-a"),
            Guest(nanoId = "g2", name = "B", invitations = 1, venueName = "Main", nfcCardUid = "ABC", firebaseOrgId = "org-b"),
        )
        val matches = MultiOrgMerge.findGuestsByNfcUid(guests, "abc")
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.orgId.isNotBlank() })
    }

    @Test
    fun findGuestsByNfcUid_matchesTruncatedFourByteUid() {
        val guests = listOf(
            Guest(
                nanoId = "g1",
                name = "A",
                invitations = 1,
                venueName = "Main",
                nfcCardUid = "04AABBCC",
                firebaseOrgId = "org-a",
            ),
        )
        val matches = MultiOrgMerge.findGuestsByNfcUid(guests, "04AABBCCDDEE80")
        assertEquals(1, matches.size)
        assertEquals("g1", matches.first().value.nanoId)
    }

    @Test
    fun belongsToVisibleOrg_singleModeHidesOtherOrgs() {
        val configured = setOf("org-a", "org-b")
        assertTrue(MultiOrgMerge.belongsToVisibleOrg("org-a", false, "org-a", configured))
        assertTrue(!MultiOrgMerge.belongsToVisibleOrg("org-b", false, "org-a", configured))
        assertTrue(MultiOrgMerge.belongsToVisibleOrg("", false, "org-a", configured))
    }

    @Test
    fun belongsToVisibleOrg_allModeKeepsConfiguredOnly() {
        val configured = setOf("org-a", "org-b")
        assertTrue(MultiOrgMerge.belongsToVisibleOrg("org-a", true, FIREBASE_ORG_ALL_SENTINEL, configured))
        assertTrue(MultiOrgMerge.belongsToVisibleOrg("org-b", true, FIREBASE_ORG_ALL_SENTINEL, configured))
        assertTrue(!MultiOrgMerge.belongsToVisibleOrg("org-c", true, FIREBASE_ORG_ALL_SENTINEL, configured))
    }

    @Test
    fun filterForVisibleOrg_singleModeKeepsOnlyActiveCatalog() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Main", firebaseOrgId = "org-a", isActive = true),
            VenueEntity(id = 2, name = "Bar", firebaseOrgId = "org-b", isActive = true),
        )
        val visible = MultiOrgMerge.filterForVisibleOrg(
            venues,
            { it.firebaseOrgId },
            allOrgsMode = false,
            activeOrgId = "org-a",
            configuredOrgIds = setOf("org-a", "org-b"),
        )
        assertEquals(listOf("Main"), visible.map { it.name })
    }

    @Test
    fun filterForVisibleOrg_singleModeKeepsUntaggedAlongsideActiveOrg() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Legacy", firebaseOrgId = "", isActive = true),
            VenueEntity(id = 2, name = "Main", firebaseOrgId = "org-a", isActive = true),
            VenueEntity(id = 3, name = "Bar", firebaseOrgId = "org-b", isActive = true),
        )
        val visible = MultiOrgMerge.filterForVisibleOrg(
            venues,
            { it.firebaseOrgId },
            allOrgsMode = false,
            activeOrgId = "org-a",
            configuredOrgIds = setOf("org-a", "org-b"),
        )
        assertEquals(listOf(1L, 2L), visible.map { it.id })
    }
}
