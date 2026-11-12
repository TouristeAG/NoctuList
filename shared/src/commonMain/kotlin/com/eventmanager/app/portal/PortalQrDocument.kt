package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Volunteer

/**
 * The volunteer's own QR code, published apart from the portal page so the page never holds the
 * volunteer id. Only the owner can read it; admins never get one.
 */
object PortalQrDocument {
    const val COLLECTION = "volunteerPortalQr"
    const val SCHEMA = 1

    fun build(volunteer: Volunteer, enabled: Boolean): Map<String, Any>? {
        if (!enabled || volunteer.isAdmin || volunteer.id.isBlank() || volunteer.portalUid.isBlank()) return null
        return mapOf(
            "schema" to SCHEMA,
            "payload" to volunteer.id,
            "name" to "${volunteer.name} ${volunteer.lastNameAbbreviation}".trim(),
        )
    }
}
