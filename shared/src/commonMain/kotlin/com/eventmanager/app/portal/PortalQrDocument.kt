package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
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

/**
 * A permanent guest's own QR code, published apart from the portal page so the page never holds
 * the guest id. Only the owner can read it; admins, temporary guests and volunteer rows never get one.
 */
object GuestPortalQrDocument {
    const val COLLECTION = "guestPortalQr"
    const val SCHEMA = 1

    fun build(guest: Guest, enabled: Boolean): Map<String, Any>? {
        if (!enabled || guest.isAdmin || guest.isTemporaryGuest || guest.isVolunteerBenefit) return null
        if (guest.nanoId.isBlank() || guest.portalUid.isBlank()) return null
        return mapOf(
            "schema" to SCHEMA,
            "payload" to guest.nanoId,
            "name" to "${guest.name} ${guest.lastNameAbbreviation}".trim(),
        )
    }
}
