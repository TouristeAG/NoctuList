package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.PosVenueScope
import com.eventmanager.app.data.models.SalesCategory
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VolunteerRank

enum class SalesItemStatusFilter { ALL, ACTIVE, INACTIVE }

enum class SalesItemFlagFilter { ANY, YES, NO }

enum class SalesItemSort { NAME_ASC, NAME_DESC, PRICE_ASC, PRICE_DESC, RECENT, ACTIVE_FIRST }

/**
 * Admin-side filter for the sales item list. Groups combine with AND, the values
 * picked inside one group combine with OR.
 */
data class SalesItemFilter(
    val query: String = "",
    val status: SalesItemStatusFilter = SalesItemStatusFilter.ALL,
    val categories: Set<SalesCategory> = emptySet(),
    val includeUncategorized: Boolean = false,
    /** Venue names, [PosVenueScope.GLOBAL] included. Same rule as the POS venue picker. */
    val venues: Set<String> = emptySet(),
    /** Lower-cased sub-category names; an empty string stands for "no sub-category". */
    val subcategories: Set<String> = emptySet(),
    val deposit: SalesItemFlagFilter = SalesItemFlagFilter.ANY,
    val discount: SalesItemFlagFilter = SalesItemFlagFilter.ANY,
    /** Required ranks to keep; null stands for "no rank needed". */
    val ranks: Set<VolunteerRank?> = emptySet(),
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val sort: SalesItemSort = SalesItemSort.NAME_ASC,
) {
    /** Filters tucked behind "More", for the badge on that button. */
    val secondaryCount: Int
        get() = listOf(
            subcategories.isNotEmpty(),
            deposit != SalesItemFlagFilter.ANY,
            discount != SalesItemFlagFilter.ANY,
            ranks.isNotEmpty(),
            minPrice != null || maxPrice != null,
        ).count { it }

    /** True when any filter narrows the list (search text and sort order excluded). */
    val hasFilters: Boolean
        get() = status != SalesItemStatusFilter.ALL ||
            categories.isNotEmpty() || includeUncategorized ||
            venues.isNotEmpty() || secondaryCount > 0

    fun matches(item: SalesSheetItem): Boolean {
        val q = query.trim()
        if (q.isNotEmpty() &&
            !item.name.contains(q, ignoreCase = true) &&
            !item.subcategory.contains(q, ignoreCase = true) &&
            !(item.emoji.isNotBlank() && item.emoji.contains(q))
        ) return false

        when (status) {
            SalesItemStatusFilter.ACTIVE -> if (!item.isActive) return false
            SalesItemStatusFilter.INACTIVE -> if (item.isActive) return false
            SalesItemStatusFilter.ALL -> Unit
        }

        if (categories.isNotEmpty() || includeUncategorized) {
            val itemCategories = SalesCategory.parseList(item.categories)
            val ok = (includeUncategorized && itemCategories.isEmpty()) ||
                itemCategories.any { it in categories }
            if (!ok) return false
        }

        if (venues.isNotEmpty()) {
            val itemVenues = PosVenueScope.parseVenueList(item.availableVenues)
            if (venues.none { PosVenueScope.isItemAvailableAt(itemVenues, it) }) return false
        }

        if (subcategories.isNotEmpty() &&
            item.subcategory.trim().lowercase() !in subcategories
        ) return false

        if (!deposit.accepts(item.isDeposit)) return false
        if (!discount.accepts(item.hasDiscount)) return false

        if (ranks.isNotEmpty() && item.requiredRank !in ranks) return false

        minPrice?.let { if (item.price < it) return false }
        maxPrice?.let { if (item.price > it) return false }
        return true
    }

    fun apply(items: List<SalesSheetItem>): List<SalesSheetItem> {
        val kept = items.filter(::matches)
        val byName = compareBy<SalesSheetItem> { it.name.lowercase() }
        return when (sort) {
            SalesItemSort.NAME_ASC -> kept.sortedWith(byName)
            SalesItemSort.NAME_DESC -> kept.sortedWith(byName.reversed())
            SalesItemSort.PRICE_ASC -> kept.sortedWith(compareBy<SalesSheetItem> { it.price }.then(byName))
            SalesItemSort.PRICE_DESC -> kept.sortedWith(compareByDescending<SalesSheetItem> { it.price }.then(byName))
            SalesItemSort.RECENT -> kept.sortedWith(compareByDescending<SalesSheetItem> { it.lastModified }.then(byName))
            SalesItemSort.ACTIVE_FIRST -> kept.sortedWith(compareByDescending<SalesSheetItem> { it.isActive }.then(byName))
        }
    }

    /** Clears every filter but keeps the search text and sort order. */
    fun cleared(): SalesItemFilter = SalesItemFilter(query = query, sort = sort)

    private fun SalesItemFlagFilter.accepts(value: Boolean): Boolean = when (this) {
        SalesItemFlagFilter.ANY -> true
        SalesItemFlagFilter.YES -> value
        SalesItemFlagFilter.NO -> !value
    }
}
