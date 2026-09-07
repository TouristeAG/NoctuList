package com.eventmanager.app.data.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueAccessCatalogTest {

    @Test
    fun encodeDecode_roundTrips() {
        val catalog = listOf(
            VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage"),
            VenueAccess(id = "a2", venueName = "GROOVE", name = "Zone VIP"),
            VenueAccess(id = "a3", venueName = "CAVE", name = "Stage"),
        )
        assertEquals(catalog, VenueAccessCatalog.decode(VenueAccessCatalog.encode(catalog)))
    }

    @Test
    fun encode_ofEmptyCatalogIsBlank() {
        assertEquals("", VenueAccessCatalog.encode(emptyList()))
    }

    @Test
    fun decode_toleratesEmptyAndGarbage() {
        assertEquals(emptyList(), VenueAccessCatalog.decode(""))
        assertEquals(emptyList(), VenueAccessCatalog.decode("   "))
        assertEquals(emptyList(), VenueAccessCatalog.decode("not json"))
        // Entries missing an id, a venue or a name cannot be resolved and are dropped.
        assertEquals(
            emptyList(),
            VenueAccessCatalog.decode("""[{"id":"","venue":"GROOVE","name":"Backstage"}]"""),
        )
    }

    @Test
    fun add_normalizesWhitespaceAndRejectsDuplicates() {
        var catalog = VenueAccessCatalog.add(emptyList(), "GROOVE", "  Zone   VIP ")
        assertEquals(listOf("Zone VIP"), catalog.map { it.name })

        catalog = VenueAccessCatalog.add(catalog, "GROOVE", "zone vip")
        assertEquals(1, catalog.size)

        catalog = VenueAccessCatalog.add(catalog, "GROOVE", "   ")
        assertEquals(1, catalog.size)

        // The same access name at another venue is a distinct entry.
        catalog = VenueAccessCatalog.add(catalog, "CAVE", "Zone VIP")
        assertEquals(2, catalog.size)
    }

    @Test
    fun add_stopsAtTheVenueLimit() {
        var catalog = emptyList<VenueAccess>()
        repeat(VenueAccessCatalog.MAX_PER_VENUE + 3) { index ->
            catalog = VenueAccessCatalog.add(catalog, "GROOVE", "Access $index")
        }
        assertEquals(VenueAccessCatalog.MAX_PER_VENUE, catalog.size)
    }

    @Test
    fun normalizeName_capsLength() {
        val long = "A".repeat(VenueAccessCatalog.MAX_NAME_LENGTH + 10)
        assertEquals(VenueAccessCatalog.MAX_NAME_LENGTH, VenueAccessCatalog.normalizeName(long).length)
    }

    @Test
    fun forVenue_isCaseInsensitive() {
        val catalog = listOf(
            VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage"),
            VenueAccess(id = "a2", venueName = "CAVE", name = "Stage"),
        )
        assertEquals(listOf("Backstage"), VenueAccessCatalog.forVenue(catalog, "groove").map { it.name })
        assertEquals(emptyList(), VenueAccessCatalog.forVenue(catalog, "  "))
        assertTrue(VenueAccessCatalog.contains(catalog, "groove", "backstage"))
        assertFalse(VenueAccessCatalog.contains(catalog, "cave", "backstage"))
    }

    @Test
    fun removeById_dropsOnlyTheTargetedAccess() {
        val catalog = listOf(
            VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage"),
            VenueAccess(id = "a2", venueName = "GROOVE", name = "Zone VIP"),
        )
        assertEquals(listOf("a2"), VenueAccessCatalog.removeById(catalog, "a1").map { it.id })
        assertEquals(catalog, VenueAccessCatalog.removeById(catalog, "unknown"))
    }

    /** A deleted access must not resurface on guests that still hold its ID. */
    @Test
    fun resolve_ignoresUnknownIds() {
        val catalog = listOf(VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage"))
        assertEquals(listOf("Backstage"), VenueAccessCatalog.resolve(catalog, setOf("a1", "gone")).map { it.name })
        assertEquals(emptyList(), VenueAccessCatalog.resolve(catalog, emptySet()))
    }
}
