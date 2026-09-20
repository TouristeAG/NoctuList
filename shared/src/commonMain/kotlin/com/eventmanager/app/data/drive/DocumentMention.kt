package com.eventmanager.app.data.drive

/** Where a mention came from, which drives how much we trust its fields. */
enum class MentionSource {
    /** Google "smart chip" — the email is authoritative. */
    CONTACT_CHIP,

    /** A plain `@Name` typed into the document — name only, no verified identity. */
    PLAIN_TEXT,
}

/** Google Workspace document type we can import shifts from. */
enum class DriveDocumentType { DOC, SHEET }

/** A document plus enough metadata to show it in the picker. */
data class DriveDocumentRef(
    val id: String,
    val name: String,
    val type: DriveDocumentType,
    val modifiedTimeMillis: Long = 0L,
)

/**
 * One person referenced in a document. [email] is only ever set from a smart chip or from
 * People API enrichment, never guessed from the label.
 */
data class DocumentMention(
    /** Exactly as it appeared, e.g. `@Bob` — shown to the admin so they can recognise the row. */
    val rawLabel: String,
    val displayName: String,
    val email: String? = null,
    val phone: String? = null,
    /** `YYYY-MM-DD`, or `--MM-DD` when Google has no year on the contact. */
    val birthday: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val source: MentionSource,
    /** How many times this person appears; used only to order the review list. */
    val occurrences: Int = 1,
) {
    /**
     * Mentions collapse on email when known, else on the normalized label: the same person
     * mentioned five times in a planning document should produce one shift, not five.
     */
    val dedupeKey: String
        get() = email?.let { "email:" + TextNormalization.normalizeEmail(it) }
            ?: "label:" + TextNormalization.normalizeName(displayName.ifBlank { rawLabel })
}

/** Merges duplicates, keeping the richest copy of each field, and orders by frequency. */
fun List<DocumentMention>.mergeDuplicates(): List<DocumentMention> =
    groupBy { it.dedupeKey }
        .map { (_, group) ->
            val base = group.firstOrNull { it.source == MentionSource.CONTACT_CHIP } ?: group.first()
            base.copy(
                displayName = group.firstNotNullOfOrNull { it.displayName.takeIf(String::isNotBlank) }
                    ?: base.displayName,
                email = group.firstNotNullOfOrNull { it.email?.takeIf(String::isNotBlank) },
                phone = group.firstNotNullOfOrNull { it.phone?.takeIf(String::isNotBlank) },
                birthday = group.firstNotNullOfOrNull { it.birthday?.takeIf(String::isNotBlank) },
                givenName = group.firstNotNullOfOrNull { it.givenName?.takeIf(String::isNotBlank) },
                familyName = group.firstNotNullOfOrNull { it.familyName?.takeIf(String::isNotBlank) },
                occurrences = group.sumOf { it.occurrences },
            )
        }
        .sortedByDescending { it.occurrences }
