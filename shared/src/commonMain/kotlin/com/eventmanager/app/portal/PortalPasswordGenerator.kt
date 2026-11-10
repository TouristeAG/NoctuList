package com.eventmanager.app.portal

import java.security.SecureRandom

/**
 * Random first password for a volunteer account created by an admin. Shown once, then the
 * volunteer sets their own through the "set your password" e-mail or the portal.
 *
 * No look-alike characters (0/O, 1/l/I), so it can be read aloud or copied from a screen.
 */
object PortalPasswordGenerator {
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "-_.!?#%+="
    private const val ALL = UPPER + LOWER + DIGITS + SYMBOLS

    const val DEFAULT_LENGTH = 16

    fun generate(length: Int = DEFAULT_LENGTH, random: SecureRandom = SecureRandom()): String {
        require(length >= 12) { "Portal passwords are at least 12 characters" }
        val chars = mutableListOf(
            UPPER[random.nextInt(UPPER.length)],
            LOWER[random.nextInt(LOWER.length)],
            DIGITS[random.nextInt(DIGITS.length)],
            SYMBOLS[random.nextInt(SYMBOLS.length)],
        )
        while (chars.size < length) chars += ALL[random.nextInt(ALL.length)]
        // Fisher–Yates so the guaranteed classes are not always at the start.
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return chars.joinToString("")
    }
}
