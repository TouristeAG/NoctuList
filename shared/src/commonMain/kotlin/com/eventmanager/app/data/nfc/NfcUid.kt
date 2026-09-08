package com.eventmanager.app.data.nfc

/**
 * NFC UID comparison for mixed readers.
 *
 * ACS PC/SC often answers `FF CA 00 00 04` with the first 4 bytes of a 7-byte NTAG UID,
 * while phone NFC (`tag.id`) and `Le=00` return the full UID. Exact string match then
 * fails even though the card is assigned — until the card is re-written with the other
 * length.
 */
object NfcUid {
    fun normalize(raw: String): String =
        raw.trim().replace(" ", "").replace(":", "").replace("-", "").uppercase()

    fun matches(stored: String, scanned: String): Boolean {
        val a = normalize(stored)
        val b = normalize(scanned)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (cascadeEquivalent(a, b)) return true
        val reversedA = reverseHexBytes(a)
        val reversedB = reverseHexBytes(b)
        if (reversedA == b || a == reversedB) return true
        return cascadeEquivalent(reversedA, b) || cascadeEquivalent(a, reversedB)
    }

    private fun cascadeEquivalent(a: String, b: String): Boolean {
        if (!isPlausibleHexUid(a) || !isPlausibleHexUid(b)) return false
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (shorter.length == longer.length) return false
        val shortIsFourBytes = shorter.length == 8
        val longIsCascade = longer.length == 14 || longer.length == 20
        return shortIsFourBytes && longIsCascade && longer.startsWith(shorter)
    }

    private fun isPlausibleHexUid(value: String): Boolean =
        value.length in 8..20 &&
            value.length % 2 == 0 &&
            value.all { it in '0'..'9' || it in 'A'..'F' }

    private fun reverseHexBytes(hex: String): String {
        if (hex.length < 2 || hex.length % 2 != 0) return hex
        return hex.chunked(2).asReversed().joinToString("")
    }
}
