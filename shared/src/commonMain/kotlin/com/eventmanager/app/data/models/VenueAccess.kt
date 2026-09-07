package com.eventmanager.app.data.models

import com.eventmanager.app.data.utils.NanoIdGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Admin-defined special access a temporary guest can be granted at a venue — for example
 * "Backstage", "Stage" or "Zone VIP".
 *
 * Firebase-only: the catalogue rides on an institution setting and the per-guest link is a
 * Firestore field, so the Google Sheets temporary guest contract stays untouched.
 */
data class VenueAccess(
    /** Stable ID stored on guests, so renaming an access keeps existing grants valid. */
    val id: String,
    val venueName: String,
    val name: String,
)

/**
 * Serializes the admin-defined venue access catalogue into the single string an institution
 * setting can hold, and keeps the add/remove rules in one place.
 */
object VenueAccessCatalog {

    const val MAX_NAME_LENGTH = 28
    const val MAX_PER_VENUE = 12

    @Serializable
    private data class Entry(val id: String = "", val venue: String = "", val name: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private val entryListSerializer = ListSerializer(Entry.serializer())

    /** Collapses whitespace and trims, so " Zone   VIP " and "Zone VIP" are one entry. */
    fun normalizeName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)

    fun encode(accesses: List<VenueAccess>): String {
        if (accesses.isEmpty()) return ""
        val entries = accesses.map { Entry(it.id, it.venueName, it.name) }
        return json.encodeToString(entryListSerializer, entries)
    }

    fun decode(raw: String): List<VenueAccess> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val entries = runCatching {
            json.decodeFromString(entryListSerializer, trimmed)
        }.getOrElse { return emptyList() }
        return entries.mapNotNull { entry ->
            val venue = entry.venue.trim()
            val name = normalizeName(entry.name)
            val id = entry.id.trim()
            if (venue.isEmpty() || name.isEmpty() || id.isEmpty()) null
            else VenueAccess(id = id, venueName = venue, name = name)
        }.distinctBy { it.id }
    }

    fun forVenue(catalog: List<VenueAccess>, venueName: String): List<VenueAccess> {
        val venue = venueName.trim()
        if (venue.isEmpty()) return emptyList()
        return catalog.filter { it.venueName.equals(venue, ignoreCase = true) }
    }

    /** Case-insensitive: "Backstage" and "backstage" are the same access at a venue. */
    fun contains(catalog: List<VenueAccess>, venueName: String, name: String): Boolean {
        val normalized = normalizeName(name)
        return forVenue(catalog, venueName).any { it.name.equals(normalized, ignoreCase = true) }
    }

    /** No-op when the name is blank, already present, or the venue is already full. */
    fun add(catalog: List<VenueAccess>, venueName: String, name: String): List<VenueAccess> {
        val venue = venueName.trim()
        val normalized = normalizeName(name)
        if (venue.isEmpty() || normalized.isEmpty()) return catalog
        if (contains(catalog, venue, normalized)) return catalog
        if (forVenue(catalog, venue).size >= MAX_PER_VENUE) return catalog
        return catalog + VenueAccess(
            id = NanoIdGenerator.generateGuestId(),
            venueName = venue,
            name = normalized,
        )
    }

    fun removeById(catalog: List<VenueAccess>, accessId: String): List<VenueAccess> =
        catalog.filterNot { it.id == accessId }

    /** Resolves the IDs stored on a guest back to catalogue entries, dropping deleted ones. */
    fun resolve(catalog: List<VenueAccess>, accessIds: Set<String>): List<VenueAccess> {
        if (accessIds.isEmpty()) return emptyList()
        return catalog.filter { accessIds.contains(it.id) }
    }
}
