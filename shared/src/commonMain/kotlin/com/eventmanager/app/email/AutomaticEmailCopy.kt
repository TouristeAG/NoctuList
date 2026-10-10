package com.eventmanager.app.email

/**
 * Resolves the visible title and the sign-off of an automatic QR email.
 * A blank stored value keeps the localized default; a custom value wins.
 */
object AutomaticEmailCopy {
    /** Historical default written into the signature field before it followed the association name. */
    const val LEGACY_SIGNATURE_DEFAULT = "Collectif Nocturne"

    fun resolveTitle(stored: String, fallback: String): String =
        stored.trim().ifEmpty { fallback }

    /**
     * True when the signature field should display and follow the association name:
     * nothing custom was saved, it already matches the association, or it is still the
     * old hardcoded "Collectif Nocturne" while the association name was changed.
     */
    fun signatureTracksAssociation(customSignature: String, associationName: String): Boolean {
        val custom = customSignature.trim()
        val association = associationName.trim()
        if (custom.isEmpty() || custom == association) return true
        return custom == LEGACY_SIGNATURE_DEFAULT &&
            association.isNotEmpty() &&
            association != LEGACY_SIGNATURE_DEFAULT
    }

    /**
     * Custom signature first. Otherwise the association name from settings.
     * The localized fallback is only used when both are blank.
     */
    fun resolveSignature(
        customSignature: String,
        associationName: String,
        localizedFallback: String,
    ): String {
        val custom = customSignature.trim()
        val association = associationName.trim()
        if (custom.isNotEmpty() && !signatureTracksAssociation(custom, association)) return custom
        if (association.isNotEmpty()) return association
        if (custom.isNotEmpty()) return custom
        return localizedFallback
    }
}
