package com.eventmanager.app.data.models

import kotlin.test.Test
import kotlin.test.assertEquals

class ManualTemporaryGuestBatchTest {

    private fun batch(
        venueName: String = "",
        contactEmail: String = "",
        barDiscountPercent: Int = 0,
    ) = ManualTemporaryGuestBatch(
        eventDateMillis = 1_700_000_000_000L,
        artistName = "Nova Collective",
        emergencyContactPhone = "+41 79 000 00 00",
        comments = "Late arrival",
        guests = listOf(
            ManualTemporaryGuestEntry(name = "Ada", accessIds = setOf("a1", "a2")),
            ManualTemporaryGuestEntry(name = "Bo"),
        ),
        venueName = venueName,
        contactEmail = contactEmail,
        barDiscountPercent = barDiscountPercent,
    )

    /**
     * The Sheets writer builds one row per name from exactly these seven values. Adding a
     * Firebase-only field must never widen that row, so the `Temp Guest List` tab stays A–G.
     */
    @Test
    fun sheetsRow_staysSevenColumnsWhateverTheFirebaseFieldsHold() {
        val enriched = batch(venueName = "GROOVE", contactEmail = "manager@nova.ch", barDiscountPercent = 40)
        enriched.guestNames.forEach { name ->
            val row = listOf(
                "2023-11-14",
                "2023-11-14",
                enriched.artistName,
                enriched.emergencyContactPhone,
                name,
                enriched.comments,
                "nano-id",
            )
            assertEquals(7, row.size)
        }
    }

    @Test
    fun guestNames_mirrorsTheEntriesInOrder() {
        assertEquals(listOf("Ada", "Bo"), batch().guestNames)
    }

    @Test
    fun firebaseOnlyFields_defaultToEmpty() {
        val plain = batch()
        assertEquals("", plain.venueName)
        assertEquals("", plain.contactEmail)
        assertEquals(0, plain.barDiscountPercent)
    }

    @Test
    fun accessIds_areCarriedPerPersonNotPerBatch() {
        val entries = batch().guests
        assertEquals(setOf("a1", "a2"), entries.first().accessIds)
        assertEquals(emptySet(), entries.last().accessIds)
    }
}
