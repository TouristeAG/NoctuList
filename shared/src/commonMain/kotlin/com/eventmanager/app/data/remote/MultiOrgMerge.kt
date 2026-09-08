package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.OrgScoped
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec

data class MergedVenueFilter(
    val displayName: String,
    val orgIds: List<String>,
)

data class MergedPosProduct(
    val displayItem: SalesSheetItem,
    val sources: List<OrgScoped<SalesSheetItem>>,
)

object MultiOrgMerge {
    fun mergeVenueFilters(venues: List<VenueEntity>): List<MergedVenueFilter> =
        venues
            .filter { it.isActive }
            .groupBy { it.name.trim() }
            .map { (name, group) ->
                MergedVenueFilter(
                    displayName = name,
                    orgIds = group.map { it.firebaseOrgId }.distinct(),
                )
            }
            .sortedBy { it.displayName.lowercase() }

    fun guestMatchesVenueFilter(guest: Guest, filter: MergedVenueFilter): Boolean =
        filter.orgIds.contains(guest.firebaseOrgId) &&
            (guest.venueName == filter.displayName || guest.venueName == "BOTH")

    fun matchesGuestVenueSelection(
        guest: Guest,
        selectedVenueName: String?,
        venues: List<VenueEntity>,
        allOrgsMode: Boolean,
    ): Boolean {
        if (selectedVenueName == null) return true
        if (selectedVenueName == "BOTH") return guest.venueName == "BOTH"
        if (!allOrgsMode) {
            return guest.venueName == selectedVenueName || guest.venueName == "BOTH"
        }
        val filter = mergeVenueFilters(venues).firstOrNull { it.displayName == selectedVenueName }
            ?: return guest.venueName == selectedVenueName || guest.venueName == "BOTH"
        return guestMatchesVenueFilter(guest, filter)
    }

    fun mergedVenueFilterNames(venues: List<VenueEntity>): List<String> =
        mergeVenueFilters(venues).map { it.displayName }

    fun salesItemMergeKey(item: SalesSheetItem): String = listOf(
        item.name.trim().lowercase(),
        item.price.toString(),
        item.hasDiscount.toString(),
        item.isDeposit.toString(),
        item.requiredRank?.name.orEmpty(),
        item.categories.trim().lowercase(),
        item.subcategory.trim().lowercase(),
        item.emoji,
        item.availableVenues.trim().lowercase(),
        item.isActive.toString(),
    ).joinToString("|")

    fun mergePosProducts(items: List<SalesSheetItem>): List<MergedPosProduct> =
        items
            .filter { it.isActive }
            .groupBy { salesItemMergeKey(it) }
            .map { (_, group) ->
                val sources = group.map { OrgScoped(it.firebaseOrgId, it) }
                MergedPosProduct(
                    displayItem = group.first(),
                    sources = sources,
                )
            }
            .sortedBy { it.displayItem.name.lowercase() }

    fun resolvePosProductForOrg(merged: MergedPosProduct, customerOrgId: String): SalesSheetItem? =
        merged.sources.firstOrNull { it.orgId == customerOrgId }?.value
            ?: merged.sources.firstOrNull()?.value

    fun findGuestsByNfcUid(guests: List<Guest>, uid: String): List<OrgScoped<Guest>> =
        guests
            .filter { it.nfcCardUid.isNotBlank() && SensitiveFieldCodec.matchesNfcUid(it, uid) }
            .map { OrgScoped(it.firebaseOrgId, it) }

    fun findVolunteersByNfcUid(volunteers: List<Volunteer>, uid: String): List<OrgScoped<Volunteer>> =
        volunteers
            .filter { it.nfcCardUid.isNotBlank() && SensitiveFieldCodec.matchesNfcUid(it, uid) }
            .map { OrgScoped(it.firebaseOrgId, it) }

    fun findGuestByNanoId(guests: List<Guest>, nanoId: String): List<OrgScoped<Guest>> =
        guests
            .filter { it.nanoId == nanoId }
            .map { OrgScoped(it.firebaseOrgId, it) }

    fun configuredOrgIdsOnly(
        configuredOrgIds: List<String>,
        dataOrgIds: List<String>,
    ): List<String> = dataOrgIds.filter { it in configuredOrgIds }

    /**
     * Whether a Room row should be visible for the current Firebase org view.
     * Blank [entityOrgId] is treated as legacy untagged data and stays visible.
     */
    fun belongsToVisibleOrg(
        entityOrgId: String,
        allOrgsMode: Boolean,
        activeOrgId: String,
        configuredOrgIds: Set<String>,
    ): Boolean {
        val org = entityOrgId.trim()
        if (allOrgsMode) {
            if (configuredOrgIds.isEmpty()) return true
            return org.isBlank() || org in configuredOrgIds
        }
        val active = activeOrgId.trim()
        if (active.isBlank() || isFirebaseOrgAllSentinel(active)) return true
        return org.isBlank() || org == active
    }

    fun <T> filterForVisibleOrg(
        items: List<T>,
        orgIdOf: (T) -> String,
        allOrgsMode: Boolean,
        activeOrgId: String,
        configuredOrgIds: Set<String>,
    ): List<T> {
        if (allOrgsMode) {
            return items.filter {
                belongsToVisibleOrg(orgIdOf(it), true, activeOrgId, configuredOrgIds)
            }
        }
        val active = activeOrgId.trim()
        if (active.isBlank() || isFirebaseOrgAllSentinel(active)) {
            return items
        }
        return items.filter { item ->
            belongsToVisibleOrg(orgIdOf(item), false, active, configuredOrgIds)
        }
    }
}
