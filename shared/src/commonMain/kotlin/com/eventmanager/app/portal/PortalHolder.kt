package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer

enum class PortalHolderKind { VOLUNTEER, GUEST }

/**
 * A profile that can hold a portal account and a Google link: a volunteer or a permanent guest.
 * Holders sharing a non-blank [portalUid] are one person. [id] is the volunteer id or the guest nanoId.
 */
data class PortalHolder(
    val kind: PortalHolderKind,
    val id: String,
    val email: String,
    val portalEnabled: Boolean,
    val portalUid: String,
    val portalGoogleEmail: String,
    val portalGoogleUid: String,
    val portalGoogleLinkedBy: String,
    val isAdmin: Boolean,
)

fun Volunteer.asPortalHolder() = PortalHolder(
    kind = PortalHolderKind.VOLUNTEER,
    id = id,
    email = email,
    portalEnabled = portalEnabled,
    portalUid = portalUid,
    portalGoogleEmail = portalGoogleEmail,
    portalGoogleUid = portalGoogleUid,
    portalGoogleLinkedBy = portalGoogleLinkedBy,
    isAdmin = isAdmin,
)

/** Permanent guests only; the caller filters out temporary guests and volunteer benefits. */
fun Guest.asPortalHolder() = PortalHolder(
    kind = PortalHolderKind.GUEST,
    id = nanoId,
    email = email,
    portalEnabled = portalEnabled,
    portalUid = portalUid,
    portalGoogleEmail = portalGoogleEmail,
    portalGoogleUid = portalGoogleUid,
    portalGoogleLinkedBy = portalGoogleLinkedBy,
    isAdmin = isAdmin,
)

/** A permanent guest that can hold its own portal space. */
internal val Guest.isPermanentPortalGuest: Boolean get() = !isTemporaryGuest && !isVolunteerBenefit
