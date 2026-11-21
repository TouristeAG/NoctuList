package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.activeBarDiscountPercent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything the app knows about one permanent guest that may be shown on their own portal page. */
data class GuestPortalProjectionInput(
    val guest: Guest,
    /** Ledger rows; filtered to this guest by the builder. */
    val transfers: List<AccountTransfer>,
    val currencyCode: String,
    val associationName: String,
    val hasPhoto: Boolean,
    /** Changes whenever the published photo changes, so the page reloads it. */
    val photoVersion: String,
    /** Google sign-in is on with at least one verified domain. */
    val googleLinkAllowed: Boolean = false,
)

object GuestPortalProjectionBuilder {
    const val COLLECTION = "guestPortal"
    const val SCHEMA = 1

    fun build(input: GuestPortalProjectionInput): PortalProjection {
        val guest = input.guest
        val payload = buildJsonObject {
            put("firstName", guest.name.trim())
            put("lastName", guest.lastNameAbbreviation.trim())
            put("email", guest.email.trim())
            put("phone", guest.phoneNumber.trim())
            put("hasPhoto", input.hasPhoto)
            put("photoVersion", if (input.hasPhoto) input.photoVersion else "")
            put("venueName", guest.venueName.trim().take(PortalProjectionBuilder.MAX_LABEL))
            put("invitations", guest.invitations)
            put("barDiscountPercent", guest.activeBarDiscountPercent(firebaseBackend = true))
            put(
                "wallet",
                PortalProjectionBuilder.walletJson(input.transfers, AccountHolderType.GUEST, guest.nanoId, input.currencyCode),
            )
            // The wallet QR doubles as an admin key for admin guests: never mail it from the web.
            put("qrEmailAllowed", !guest.isAdmin && guest.email.isNotBlank())
            put("mustSetPassword", false)
            put("association", input.associationName.trim().take(PortalProjectionBuilder.MAX_LABEL))
            // The Google uid stays on the device.
            val googleEmail = guest.portalGoogleEmail.trim()
            put("googleEmail", googleEmail)
            put("googleLinkedBy", guest.portalGoogleLinkedBy.trim())
            put("googleLinkAvailable", input.googleLinkAllowed && googleEmail.isEmpty())
        }.toString()
        return PortalProjection(
            payload = payload,
            contentHash = PortalProjectionBuilder.sha256(payload),
            schema = SCHEMA,
        )
    }
}
