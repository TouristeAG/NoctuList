package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext

/**
 * Sends a volunteer their wallet QR code by e-mail without any UI — the relay behind the portal's
 * "Send me my QR code" button. Always to the address stored on the profile, never to one taken
 * from the request.
 */
expect object PortalQrMailer {
    /** True when this device has a Gmail sender ready to use without user interaction. */
    fun canSend(context: PlatformContext): Boolean

    suspend fun send(context: PlatformContext, settings: SettingsManager, volunteer: Volunteer): Boolean

    /** Same mail for a permanent guest, with the guest e-mail template. Refused for admins ([guestQrMailRecipient]). */
    suspend fun sendGuest(context: PlatformContext, settings: SettingsManager, guest: Guest): Boolean
}
