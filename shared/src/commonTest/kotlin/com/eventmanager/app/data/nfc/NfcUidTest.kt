package com.eventmanager.app.data.nfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NfcUidTest {
    @Test
    fun normalizeStripsSeparators() {
        assertEquals("04AABBCC", NfcUid.normalize(" 04:AA BB-cc "))
    }

    @Test
    fun exactMatchIgnoresFormatting() {
        assertTrue(NfcUid.matches("04:AA:BB:CC", "04aabbcc"))
    }

    @Test
    fun fourBytePrefixMatchesSevenByteNtagUid() {
        assertTrue(NfcUid.matches("04AABBCC", "04AABBCCDDEE80"))
        assertTrue(NfcUid.matches("04AABBCCDDEE80", "04AABBCC"))
    }

    @Test
    fun reversedByteOrderStillMatches() {
        assertTrue(NfcUid.matches("AABBCCDD", "DDCCBBAA"))
    }

    @Test
    fun differentCardsDoNotMatch() {
        assertFalse(NfcUid.matches("04AABBCC", "04AABBCD"))
        assertFalse(NfcUid.matches("04AABBCCDDEE80", "04AABBCD112233"))
        assertFalse(NfcUid.matches("", "04AABBCC"))
    }
}
