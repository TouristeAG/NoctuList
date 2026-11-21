package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext

/**
 * Computers no longer sign in to Gmail: volunteer and guest QR requests from the portal are sent by the
 * association's site (Cloud Functions) or by a phone signed in to Gmail.
 */
actual object PortalQrMailer {
    actual fun canSend(context: PlatformContext): Boolean = false

    actual suspend fun send(context: PlatformContext, settings: SettingsManager, volunteer: Volunteer): Boolean = false

    actual suspend fun sendGuest(context: PlatformContext, settings: SettingsManager, guest: Guest): Boolean = false
}
