package com.eventmanager.app.portal

/** Outcome of checking the Google address rule typed in the settings. */
enum class TemplateCheck { OK, EMPTY, INVALID, DOMAIN }

/**
 * Builds a volunteer's association Google address from the rule set by the team, for example
 * `{prénom}.{nom}@asso.ch` gives `jean-pierre.damico@asso.ch` for "Jean Pierre D'Amico".
 * Placeholders go in the part before `@` only: `{prénom}` (or `{prenom}`, `{first}`), `{nom}`
 * (or `{last}`), `{p}` and `{n}` for the first letters.
 */
object PortalGoogleAddressTemplate {
    const val SAMPLE_FIRST = "Jean Pierre"
    const val SAMPLE_LAST = "D'Amico"

    private val PLACEHOLDER = Regex("\\{([^{}]*)\\}")

    private val LETTERS: Map<Char, String> = buildMap {
        fun put(chars: String, value: String) = chars.forEach { put(it, value) }
        put("àáâãäåāăą", "a")
        put("æ", "ae")
        put("çćĉċč", "c")
        put("ďđð", "d")
        put("èéêëēĕėęě", "e")
        put("ĝğġģ", "g")
        put("ĥħ", "h")
        put("ìíîïĩīĭįı", "i")
        put("ĵ", "j")
        put("ķ", "k")
        put("ĺļľŀł", "l")
        put("ñńņňŉ", "n")
        put("òóôõöøōŏő", "o")
        put("œ", "oe")
        put("ŕŗř", "r")
        put("śŝşš", "s")
        put("ß", "ss")
        put("ţťŧ", "t")
        put("þ", "th")
        put("ùúûüũūŭůűų", "u")
        put("ŵ", "w")
        put("ýÿŷ", "y")
        put("źżž", "z")
    }

    private const val APOSTROPHES = "'’‘`´"

    /** One name cleaned for an address: lowercase, no accents or apostrophes, spaces as `-`. */
    fun cleanPart(raw: String): String {
        val out = StringBuilder()
        for (c in raw.lowercase()) {
            when {
                c in '̀'..'ͯ' || c in APOSTROPHES -> Unit
                c.isWhitespace() || c == '_' || c == '-' -> out.append('-')
                c in 'a'..'z' || c in '0'..'9' || c == '.' -> out.append(c)
                else -> LETTERS[c]?.let(out::append)
            }
        }
        return out.toString().replace(Regex("-+"), "-").trim('-', '.')
    }

    /** The address for these names, or null when the rule or the names cannot give a valid one. */
    fun render(template: String, firstName: String, lastName: String): String? {
        val rule = template.trim().lowercase()
        if (rule.count { it == '@' } != 1) return null
        val local = rule.substringBefore('@')
        val domain = rule.substringAfter('@')
        if ('{' in domain || '}' in domain) return null
        val first = cleanPart(firstName)
        val last = cleanPart(lastName)
        var usable = true
        val rendered = PLACEHOLDER.replace(local) { match ->
            val value = when (cleanPart(match.groupValues[1])) {
                "prenom", "first" -> first
                "nom", "last" -> last
                "p" -> first.take(1)
                "n" -> last.take(1)
                else -> ""
            }
            if (value.isEmpty()) usable = false
            value
        }
        if (!usable || '{' in rendered || '}' in rendered) return null
        return PortalInputValidator.cleanEmail("$rendered@$domain")
    }

    /**
     * Checks a rule against the sample name and the verified Workspace domains. The domain must be
     * verified even when any Google account may sign in: a guessed address on a public domain such
     * as gmail.com could belong to a stranger, who would then open the volunteer's profile.
     */
    fun check(template: String, verifiedDomains: List<String>): TemplateCheck {
        if (template.isBlank()) return TemplateCheck.EMPTY
        val sample = render(template, SAMPLE_FIRST, SAMPLE_LAST) ?: return TemplateCheck.INVALID
        if (!usesVerifiedDomain(sample, verifiedDomains)) return TemplateCheck.DOMAIN
        return TemplateCheck.OK
    }

    /** True when [address] is on one of the verified Workspace domains. */
    fun usesVerifiedDomain(address: String, verifiedDomains: List<String>): Boolean {
        val domain = address.substringAfter('@').lowercase()
        return verifiedDomains.any { it.trim().lowercase() == domain }
    }
}
