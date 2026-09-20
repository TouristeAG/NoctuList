package com.eventmanager.app.data.drive

import java.text.Normalizer
import java.util.Locale

/**
 * Comparison helpers for matching document mentions against volunteer records.
 * Everything here is deterministic and locale-independent so the matcher stays testable.
 */
object TextNormalization {

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_NAME_CHARS = Regex("[^\\p{L}\\p{N} ]+")
    private val WHITESPACE = Regex("\\s+")
    private val NON_DIGITS = Regex("[^0-9]")
    /** `LucaPasini` / `LucaPASINI` → two tokens, without breaking an all-caps surname. */
    private val CAMEL_BOUNDARY = Regex("(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})")

    /** Lowercase, accent-folded, punctuation-stripped: `"Jean-Luc Béro"` -> `"jean luc bero"`. */
    fun normalizeName(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace('\'', ' ')
            .replace('’', ' ')
            .replace('-', ' ')
            .replace(NON_NAME_CHARS, "")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Word-level pieces of a person name. CamelCase (`LucaPasini`) and separators used in
     * email local parts (`luca.pasini`, `luca_pasini`) are split so a single mention token
     * can still line up with a first name plus a last-name abbreviation.
     */
    fun nameTokens(value: String): List<String> {
        val prepared = CAMEL_BOUNDARY.replace(value, " ")
            .replace('.', ' ')
            .replace('_', ' ')
        return normalizeName(prepared).split(' ').filter { it.isNotBlank() }
    }

    /**
     * Canonical email for equality checks. Gmail ignores dots in the local part and everything
     * after a `+`, so `J.Doe+asso@gmail.com` and `jdoe@googlemail.com` are the same mailbox.
     */
    fun normalizeEmail(value: String): String {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        val at = trimmed.lastIndexOf('@')
        if (at <= 0) return trimmed
        var local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1).let { if (it == "googlemail.com") "gmail.com" else it }
        local = local.substringBefore('+')
        if (domain == "gmail.com") local = local.replace(".", "")
        return "$local@$domain"
    }

    fun emailLocalPart(value: String): String = normalizeEmail(value).substringBefore('@')

    /**
     * Digits only, with Swiss national numbers promoted to their international form so
     * `079 123 45 67` and `+41791234567` compare equal. Other countries keep their own prefix.
     */
    fun normalizePhone(value: String, defaultCountryCode: String = "41"): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        val hadPlus = raw.startsWith("+") || raw.startsWith("00")
        var digits = raw.replace(NON_DIGITS, "")
        if (raw.startsWith("00")) digits = digits.removePrefix("00")
        if (digits.length < 6) return null
        if (!hadPlus && digits.startsWith("0")) {
            digits = defaultCountryCode + digits.removePrefix("0")
        }
        return digits
    }

    /**
     * Dates arrive as free text from the volunteer sheet and as structured values from Google.
     * Returns `YYYY-MM-DD`, or `--MM-DD` when the year is unknown, or null when unparseable.
     */
    fun normalizeBirthday(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(raw)?.let { m ->
            val (y, mo, d) = m.destructured
            return "%04d-%02d-%02d".format(y.toInt(), mo.toInt(), d.toInt())
        }
        Regex("^--(\\d{1,2})-(\\d{1,2})$").find(raw)?.let { m ->
            val (mo, d) = m.destructured
            return "--%02d-%02d".format(mo.toInt(), d.toInt())
        }
        // Day-first is the convention in the French-speaking volunteer sheets.
        Regex("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})$").find(raw)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = y.toInt().let { if (it < 100) if (it > 30) 1900 + it else 2000 + it else it }
            return "%04d-%02d-%02d".format(year, mo.toInt(), d.toInt())
        }
        return null
    }

    /** True when both dates are known and fall on the same day, ignoring a missing year. */
    fun birthdaysMatch(a: String?, b: String?): Boolean {
        val left = normalizeBirthday(a) ?: return false
        val right = normalizeBirthday(b) ?: return false
        if (left == right) return true
        // `--MM-DD` from Google Contacts still confirms a `YYYY-MM-DD` volunteer record.
        return left.takeLast(5) == right.takeLast(5) &&
            (left.startsWith("--") || right.startsWith("--"))
    }

    /**
     * Jaro-Winkler, which handles the typos and diminutives common in hand-typed planning
     * documents far better than edit distance (`"Mathieu"` vs `"Matthieu"` scores ~0.97).
     */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matchWindow = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val start = maxOf(0, i - matchWindow)
            val end = minOf(i + matchWindow + 1, b.length)
            for (j in start until end) {
                if (bMatched[j] || a[i] != b[j]) continue
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        var prefix = 0
        for (i in 0 until minOf(4, minOf(a.length, b.length))) {
            if (a[i] != b[i]) break
            prefix++
        }
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}
