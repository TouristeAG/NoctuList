package com.eventmanager.app.data.drive

import com.eventmanager.app.data.models.Volunteer

/**
 * Links a person mentioned in a document to a NoctuList volunteer.
 *
 * Rather than a fixed priority ladder, evidence is scored and summed, so agreement across several
 * weak signals can outrank one mediocre signal. Two properties matter more than the raw score:
 *
 * - **Uniqueness.** An email only proves identity if exactly one volunteer has it. When an address
 *   is duplicated in the roster it stops being decisive and merely narrows the field.
 * - **Margin.** `@Bob` matching "Bob Dupont" is only useful when no other Bob is comparably close.
 *   A high score with a close runner-up is reported as ambiguous instead of being guessed at.
 *
 * Pure Kotlin with no Google dependency, so it is directly unit-testable.
 */
object VolunteerMatcher {

    // Identifying: proves identity on its own, but only while the value stays unique.
    private const val W_EMAIL_UNIQUE = 100.0
    private const val W_PHONE_UNIQUE = 90.0

    /** Same value shared by several volunteers: still narrows the field, no longer decisive. */
    private const val W_EMAIL_DUPLICATED = 25.0
    private const val W_PHONE_DUPLICATED = 22.0

    // Strong: does not identify alone, but combinations of these are convincing.
    private const val W_FULL_NAME = 40.0
    private const val W_BIRTHDAY = 35.0
    private const val W_EMAIL_LOCAL_PART = 30.0
    private const val W_FAMILY_NAME = 22.0

    // Weak: the long tail, where the margin check does the real work.
    private const val W_GIVEN_NAME = 14.0
    private const val W_ABBREVIATION = 24.0

    /** Below this, two tokens are different names rather than a misspelling of one. */
    private const val FUZZY_FLOOR = 0.88

    // Tie-breakers only; never enough to lift a candidate over a real signal.
    private const val W_ACTIVE = 3.0
    private const val W_RECENT_SHIFT = 2.0

    private const val SCORE_FLOOR = 12.0
    private const val CONFIRMED_SCORE = 70.0
    private const val CONFIRMED_MARGIN = 20.0
    private const val LIKELY_SCORE = 40.0
    private const val LIKELY_MARGIN = 15.0
    /** Unique weak hit (a single first name) is still offered, just labelled as low confidence. */
    private const val POSSIBLE_MARGIN = 8.0

    private const val RECENT_SHIFT_WINDOW_MS = 365L * 24 * 60 * 60 * 1000

    fun match(
        mentions: List<DocumentMention>,
        volunteers: List<Volunteer>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<MentionMatch> {
        val index = VolunteerIndex(volunteers)
        return mentions.map { match(it, volunteers, index, nowMillis) }
    }

    fun match(
        mention: DocumentMention,
        volunteers: List<Volunteer>,
        index: VolunteerIndex = VolunteerIndex(volunteers),
        nowMillis: Long = System.currentTimeMillis(),
    ): MentionMatch {
        val scored = volunteers
            .map { volunteer -> score(mention, volunteer, index, nowMillis) }
            .filter { it.score >= SCORE_FLOOR }
            .sortedWith(compareByDescending<VolunteerCandidate> { it.score }.thenBy { it.volunteer.name })

        if (scored.isEmpty()) {
            return MentionMatch(mention, emptyList(), null, MatchConfidence.UNMATCHED)
        }

        val top = scored.first()
        val margin = top.score - (scored.getOrNull(1)?.score ?: 0.0)
        val confidence = when {
            top.hasUniqueIdentifier -> MatchConfidence.CONFIRMED
            top.score >= CONFIRMED_SCORE && margin >= CONFIRMED_MARGIN -> MatchConfidence.CONFIRMED
            top.score >= LIKELY_SCORE && margin >= LIKELY_MARGIN -> MatchConfidence.LIKELY
            // One unrivalled candidate (even a first name alone) is worth preselecting.
            scored.size == 1 || margin >= POSSIBLE_MARGIN -> MatchConfidence.POSSIBLE
            else -> MatchConfidence.AMBIGUOUS
        }
        // Ambiguous rows stay blank: several people are equally close, so guesswork is worse.
        val selected = if (confidence == MatchConfidence.AMBIGUOUS) null else top.volunteer
        return MentionMatch(
            mention = mention,
            candidates = scored.take(MAX_CANDIDATES),
            selectedVolunteer = selected,
            confidence = confidence,
        )
    }

    private const val MAX_CANDIDATES = 5

    private fun score(
        mention: DocumentMention,
        volunteer: Volunteer,
        index: VolunteerIndex,
        nowMillis: Long,
    ): VolunteerCandidate {
        val reasons = mutableListOf<MatchReason>()
        var score = 0.0
        var unique = false

        fun add(weight: Double, reason: MatchReason) {
            score += weight
            reasons += reason
        }

        mention.email?.let { mentionEmail ->
            val normalized = TextNormalization.normalizeEmail(mentionEmail)
            if (normalized.isNotBlank() && TextNormalization.normalizeEmail(volunteer.email) == normalized) {
                val holders = index.volunteersByEmail[normalized]?.size ?: 0
                if (holders == 1) {
                    unique = true
                    add(W_EMAIL_UNIQUE, MatchReason.EMAIL_EXACT)
                } else {
                    add(W_EMAIL_DUPLICATED, MatchReason.EMAIL_SHARED)
                }
            }
        }

        mention.phone?.let { mentionPhone ->
            val normalized = TextNormalization.normalizePhone(mentionPhone)
            if (normalized != null && TextNormalization.normalizePhone(volunteer.phoneNumber) == normalized) {
                val holders = index.volunteersByPhone[normalized]?.size ?: 0
                if (holders == 1) {
                    unique = true
                    add(W_PHONE_UNIQUE, MatchReason.PHONE_EXACT)
                } else {
                    add(W_PHONE_DUPLICATED, MatchReason.PHONE_SHARED)
                }
            }
        }

        if (TextNormalization.birthdaysMatch(mention.birthday, volunteer.dateOfBirth)) {
            add(W_BIRTHDAY, MatchReason.BIRTHDAY)
        }

        val mentionTokens = mentionNameTokens(mention)
        val volunteerTokens = volunteerNameTokens(volunteer)
        val alignment = alignTokens(mentionTokens, volunteerTokens)
        val concatenated = concatenatedNameMatch(mentionTokens, volunteerTokens)
        if (concatenated) {
            add(W_FULL_NAME, MatchReason.FULL_NAME)
        } else if (alignment.matched > 0) {
            // Quality discounts near-misses: "Mathieu" for "Matthieu" is worth slightly less
            // than an exact hit, but far more than an unrelated token.
            val quality = alignment.similaritySum / alignment.matched
            val exact = alignment.matched == alignment.exact
            when {
                alignment.coversMention && alignment.coversVolunteer && alignment.matched >= 2 ->
                    add(W_FULL_NAME * quality, if (exact) MatchReason.FULL_NAME else MatchReason.FUZZY_NAME)

                // "@Bob" against "Bob Jean Dupont": everything the document gave us lines up,
                // but the volunteer record is richer, so this is suggestive, not conclusive.
                alignment.coversMention || alignment.coversVolunteer ->
                    if (alignment.matched >= 2) {
                        add(W_FULL_NAME * 0.75 * quality, MatchReason.NAME_SUBSET)
                    } else {
                        add(W_GIVEN_NAME * quality, MatchReason.GIVEN_NAME)
                    }

                else -> {
                    val breadth = alignment.matched.toDouble() /
                        maxOf(mentionTokens.size, volunteerTokens.size)
                    add(W_GIVEN_NAME * quality * breadth, MatchReason.FUZZY_NAME)
                }
            }
        }

        mention.familyName?.let { family ->
            val normalized = TextNormalization.normalizeName(family)
            if (normalized.isNotBlank() && normalized in volunteerTokens) {
                add(W_FAMILY_NAME, MatchReason.FAMILY_NAME)
            }
        }

        // A volunteer's abbreviation often stands in for the surname the document spells out
        // ("Bob" + "Dup" is the roster's way of writing "Bob Dupont"). Only counted when the
        // name field did not already account for that token, so evidence is not double-paid.
        val abbreviation = TextNormalization.normalizeName(volunteer.lastNameAbbreviation)
        if (abbreviation.isNotBlank() && abbreviation.length >= 2 && !concatenated) {
            val spelledOut = mention.familyName?.let { TextNormalization.normalizeName(it) }
                ?: mentionTokens.lastOrNull()?.takeIf { it !in alignment.alignedMentionTokens }
            if (spelledOut != null && spelledOut != abbreviation && spelledOut.startsWith(abbreviation)) {
                add(W_ABBREVIATION, MatchReason.ABBREVIATION)
            }
        }

        // An email nobody verified still hints: jean.dupont@ lines up with "Jean Dupont".
        if (mention.email == null && volunteer.email.isNotBlank() && mentionTokens.isNotEmpty()) {
            val localPart = TextNormalization.emailLocalPart(volunteer.email)
            val squashed = mentionTokens.joinToString("")
            val localDigitsStripped = localPart.filter { it.isLetter() }
            if (localDigitsStripped.isNotBlank() && localDigitsStripped == squashed) {
                add(W_EMAIL_LOCAL_PART, MatchReason.EMAIL_LOCAL_PART)
            }
        }

        if (score > 0) {
            if (volunteer.isActive) score += W_ACTIVE
            val lastShift = volunteer.lastShiftDate
            if (lastShift != null && nowMillis - lastShift <= RECENT_SHIFT_WINDOW_MS) {
                score += W_RECENT_SHIFT
            }
        }

        return VolunteerCandidate(volunteer, score, reasons, unique)
    }

    /**
     * Greedily pairs each mention token with its closest unused volunteer token. Comparing token
     * by token rather than whole strings means word order does not matter and a single misspelt
     * word does not drag down an otherwise perfect name.
     */
    private fun alignTokens(mentionTokens: List<String>, volunteerTokens: List<String>): NameAlignment {
        if (mentionTokens.isEmpty() || volunteerTokens.isEmpty()) return NameAlignment.EMPTY
        val used = BooleanArray(volunteerTokens.size)
        val aligned = mutableSetOf<String>()
        var matched = 0
        var exact = 0
        var similaritySum = 0.0
        for (mentionToken in mentionTokens) {
            var bestIndex = -1
            var bestScore = 0.0
            volunteerTokens.forEachIndexed { index, volunteerToken ->
                if (used[index]) return@forEachIndexed
                val similarity = if (mentionToken == volunteerToken) {
                    1.0
                } else {
                    TextNormalization.jaroWinkler(mentionToken, volunteerToken)
                }
                if (similarity > bestScore) {
                    bestScore = similarity
                    bestIndex = index
                }
            }
            if (bestIndex >= 0 && bestScore >= FUZZY_FLOOR) {
                used[bestIndex] = true
                aligned += mentionToken
                matched++
                similaritySum += bestScore
                if (bestScore == 1.0) exact++
            }
        }
        return NameAlignment(
            matched = matched,
            exact = exact,
            similaritySum = similaritySum,
            coversMention = matched == mentionTokens.size,
            coversVolunteer = matched == volunteerTokens.size,
            alignedMentionTokens = aligned,
        )
    }

    private data class NameAlignment(
        val matched: Int,
        val exact: Int,
        val similaritySum: Double,
        val coversMention: Boolean,
        val coversVolunteer: Boolean,
        val alignedMentionTokens: Set<String>,
    ) {
        companion object {
            val EMPTY = NameAlignment(0, 0, 0.0, false, false, emptySet())
        }
    }

    /**
     * `@Lucapasini` (no camelCase split) against "Luca" + "PASINI": the joined tokens are
     * the same person even though no individual token lines up.
     */
    private fun concatenatedNameMatch(mentionTokens: List<String>, volunteerTokens: List<String>): Boolean {
        if (mentionTokens.isEmpty() || volunteerTokens.size < 2) return false
        val mentionSquash = mentionTokens.joinToString("")
        val volunteerSquash = volunteerTokens.joinToString("")
        if (mentionSquash.length < 6 || volunteerSquash.length < 6) return false
        return mentionSquash == volunteerSquash
    }

    private fun mentionNameTokens(mention: DocumentMention): List<String> {
        val explicit = listOfNotNull(mention.givenName, mention.familyName)
            .flatMap { TextNormalization.nameTokens(it) }
        if (explicit.isNotEmpty()) return explicit.distinct()
        val label = mention.displayName.ifBlank { mention.rawLabel.removePrefix("@") }
        return TextNormalization.nameTokens(label).distinct()
    }

    private fun volunteerNameTokens(volunteer: Volunteer): List<String> =
        (TextNormalization.nameTokens(volunteer.name) +
            TextNormalization.nameTokens(volunteer.lastNameAbbreviation).filter { it.length >= 2 })
            .distinct()
}

/** Precomputed duplicate lookups so uniqueness is decided once, not per candidate. */
class VolunteerIndex(volunteers: List<Volunteer>) {
    val volunteersByEmail: Map<String, List<Volunteer>> = volunteers
        .filter { it.email.isNotBlank() }
        .groupBy { TextNormalization.normalizeEmail(it.email) }

    val volunteersByPhone: Map<String, List<Volunteer>> = volunteers
        .mapNotNull { v -> TextNormalization.normalizePhone(v.phoneNumber)?.let { it to v } }
        .groupBy({ it.first }, { it.second })
}

data class VolunteerCandidate(
    val volunteer: Volunteer,
    val score: Double,
    val reasons: List<MatchReason>,
    /** True when a single piece of evidence identifies this volunteer beyond doubt. */
    val hasUniqueIdentifier: Boolean,
)

data class MentionMatch(
    val mention: DocumentMention,
    val candidates: List<VolunteerCandidate>,
    val selectedVolunteer: Volunteer?,
    val confidence: MatchConfidence,
)

enum class MatchConfidence {
    /** A unique identifier, or an overwhelming and unrivalled score. */
    CONFIRMED,

    /** Clearly ahead of the alternatives, but worth a glance. */
    LIKELY,

    /** A unique weak signal (typically a first name) — preselected, but easy to override. */
    POSSIBLE,

    /** Plausible candidates exist but none stands out — the admin must choose. */
    AMBIGUOUS,

    /** Nothing resembled this person. */
    UNMATCHED,
}

enum class MatchReason {
    EMAIL_EXACT,
    EMAIL_SHARED,
    PHONE_EXACT,
    PHONE_SHARED,
    BIRTHDAY,
    FULL_NAME,
    NAME_SUBSET,
    FAMILY_NAME,
    GIVEN_NAME,
    ABBREVIATION,
    FUZZY_NAME,
    EMAIL_LOCAL_PART,
}
