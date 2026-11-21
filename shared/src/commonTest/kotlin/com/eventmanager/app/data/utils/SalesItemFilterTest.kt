package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.PosVenueScope
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.SalesCategory
import com.eventmanager.app.data.models.VolunteerRank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SalesItemFilterTest {

    private fun item(
        id: Long,
        name: String,
        price: Double = 5.0,
        categories: String = "",
        venues: String = "",
        active: Boolean = true,
        deposit: Boolean = false,
        discount: Boolean = false,
        rank: VolunteerRank? = null,
        subcategory: String = "",
        emoji: String = "",
        modified: Long = 0,
    ) = SalesSheetItem(
        id = id, name = name, price = price, categories = categories, availableVenues = venues,
        isActive = active, isDeposit = deposit, hasDiscount = discount, requiredRank = rank,
        subcategory = subcategory, emoji = emoji, lastModified = modified,
    )

    private val beer = item(1, "Bière", 4.0, "BAR", "Salle A", subcategory = "Pression", emoji = "🍺", modified = 30)
    private val shirt = item(2, "T-shirt", 20.0, "MERCH", PosVenueScope.GLOBAL, discount = true, modified = 10)
    private val ticket = item(3, "Entrée", 10.0, "ENTRY", "", active = false, rank = VolunteerRank.ORION, modified = 20)
    private val cup = item(4, "Gobelet", 2.0, "", "Salle B", deposit = true, modified = 40)
    private val all = listOf(beer, shirt, ticket, cup)

    private fun ids(filter: SalesItemFilter) = filter.apply(all).map { it.id }

    @Test
    fun defaultKeepsEverythingSortedByName() {
        assertEquals(listOf(1L, 3L, 4L, 2L), ids(SalesItemFilter()))
    }

    @Test
    fun searchLooksAtNameSubcategoryAndEmoji() {
        assertEquals(listOf(1L), ids(SalesItemFilter(query = "pression")))
        assertEquals(listOf(1L), ids(SalesItemFilter(query = "🍺")))
        assertEquals(listOf(2L), ids(SalesItemFilter(query = "shirt")))
    }

    @Test
    fun statusFilter() {
        assertEquals(listOf(3L), ids(SalesItemFilter(status = SalesItemStatusFilter.INACTIVE)))
        assertFalse(3L in ids(SalesItemFilter(status = SalesItemStatusFilter.ACTIVE)))
    }

    @Test
    fun categoriesCombineWithOrAndUncategorizedIsItsOwnChoice() {
        assertEquals(listOf(1L, 2L), ids(SalesItemFilter(categories = setOf(SalesCategory.BAR, SalesCategory.MERCH))))
        assertEquals(listOf(4L), ids(SalesItemFilter(includeUncategorized = true)))
    }

    @Test
    fun venueFollowsPosRule() {
        // Salle A shows its own items and items with no venue, not Global ones.
        assertEquals(listOf(1L, 3L), ids(SalesItemFilter(venues = setOf("Salle A"))))
        assertEquals(listOf(3L, 2L), ids(SalesItemFilter(venues = setOf(PosVenueScope.GLOBAL))))
    }

    @Test
    fun flagsRanksAndPrice() {
        assertEquals(listOf(4L), ids(SalesItemFilter(deposit = SalesItemFlagFilter.YES)))
        assertEquals(listOf(1L, 3L, 4L), ids(SalesItemFilter(discount = SalesItemFlagFilter.NO)))
        assertEquals(listOf(3L), ids(SalesItemFilter(ranks = setOf(VolunteerRank.ORION))))
        assertEquals(listOf(1L, 4L, 2L), ids(SalesItemFilter(ranks = setOf(null))))
        assertEquals(listOf(1L, 3L), ids(SalesItemFilter(minPrice = 3.0, maxPrice = 10.0)))
        assertEquals(listOf(1L), ids(SalesItemFilter(subcategories = setOf("pression"))))
        assertEquals(listOf(3L, 4L, 2L), ids(SalesItemFilter(subcategories = setOf(""))))
    }

    @Test
    fun groupsCombineWithAnd() {
        val filter = SalesItemFilter(categories = setOf(SalesCategory.BAR, SalesCategory.ENTRY), status = SalesItemStatusFilter.ACTIVE)
        assertEquals(listOf(1L), ids(filter))
    }

    @Test
    fun sorting() {
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(SalesItemFilter(sort = SalesItemSort.PRICE_ASC)))
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(SalesItemFilter(sort = SalesItemSort.PRICE_DESC)))
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(SalesItemFilter(sort = SalesItemSort.RECENT)))
        assertEquals(listOf(2L, 4L, 3L, 1L), ids(SalesItemFilter(sort = SalesItemSort.NAME_DESC)))
        assertEquals(3L, ids(SalesItemFilter(sort = SalesItemSort.ACTIVE_FIRST)).last())
    }

    @Test
    fun countsAndClear() {
        val filter = SalesItemFilter(query = "x", sort = SalesItemSort.RECENT, deposit = SalesItemFlagFilter.YES, minPrice = 1.0)
        assertEquals(2, filter.secondaryCount)
        assertTrue(filter.hasFilters)
        val cleared = filter.cleared()
        assertFalse(cleared.hasFilters)
        assertEquals("x", cleared.query)
        assertEquals(SalesItemSort.RECENT, cleared.sort)
    }
}
