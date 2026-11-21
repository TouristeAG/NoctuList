package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest

/**
 * Where a permanent guest's QR e-mail goes: the address on the profile, never one from a request.
 * Null for an admin (no QR code), a temporary guest, a volunteer benefit or an unusable address.
 */
fun guestQrMailRecipient(guest: Guest): String? {
    if (guest.isAdmin || !guest.isPermanentPortalGuest) return null
    val to = guest.email.trim()
    return to.takeIf { PortalInputValidator.cleanEmail(it) != null }
}

/**
 * `volunteer_portal_auto_access` for a guest just added in the app: a permanent guest with an
 * e-mail address gets its access right away (no e-mail) when the portal and the guest space are on.
 */
fun guestAutoAccessApplies(guest: Guest, portalOn: Boolean, autoAccess: Boolean, guestSpaceOn: Boolean): Boolean =
    portalOn && autoAccess && guestSpaceOn && !guest.portalEnabled && guest.isPermanentPortalGuest &&
        PortalInputValidator.cleanEmail(guest.email) != null
