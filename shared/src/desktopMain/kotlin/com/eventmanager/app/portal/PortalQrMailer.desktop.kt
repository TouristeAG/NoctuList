package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.DesktopGmailAuth
import com.eventmanager.app.data.sync.DesktopQrEmailService
import com.eventmanager.app.data.sync.DesktopQrEmailTemplateStrings
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.email.QrEmailProfile
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.email_content_after_default
import com.eventmanager.app.resources.email_content_before_default
import com.eventmanager.app.resources.email_html_footer
import com.eventmanager.app.resources.email_html_header
import com.eventmanager.app.resources.email_qr_attachment_note
import com.eventmanager.app.resources.email_qr_attachment_text
import com.eventmanager.app.resources.email_signature_default
import com.eventmanager.app.resources.email_subject_default
import com.eventmanager.app.resources.email_wallet_section_compatibility
import com.eventmanager.app.resources.email_wallet_section_description
import com.eventmanager.app.resources.email_wallet_section_title
import com.eventmanager.app.resources.guest_email_content_after_default
import com.eventmanager.app.resources.guest_email_content_before_default
import com.eventmanager.app.resources.guest_email_html_footer
import com.eventmanager.app.resources.guest_email_html_header
import com.eventmanager.app.resources.guest_email_subject_default
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

actual object PortalQrMailer {
    actual fun canSend(context: PlatformContext): Boolean =
        runCatching { DesktopGmailAuth(context).isSignedIn() }.getOrDefault(false)

    actual suspend fun send(context: PlatformContext, settings: SettingsManager, volunteer: Volunteer): Boolean {
        val to = volunteer.email.trim()
        if (to.isBlank() || volunteer.isAdmin) return false
        val template = DesktopQrEmailTemplateStrings(
            volunteerHeader = getString(Res.string.email_html_header),
            guestHeader = getString(Res.string.guest_email_html_header),
            volunteerFooter = getString(Res.string.email_html_footer),
            guestFooter = getString(Res.string.guest_email_html_footer),
            volunteerSubjectDefault = getString(Res.string.email_subject_default),
            guestSubjectDefault = getString(Res.string.guest_email_subject_default),
            volunteerContentBeforeDefault = getString(Res.string.email_content_before_default),
            guestContentBeforeDefault = getString(Res.string.guest_email_content_before_default),
            volunteerContentAfterDefault = getString(Res.string.email_content_after_default),
            guestContentAfterDefault = getString(Res.string.guest_email_content_after_default),
            signatureDefault = getString(Res.string.email_signature_default),
            qrAttachmentText = getString(Res.string.email_qr_attachment_text),
            qrAttachmentNote = getString(Res.string.email_qr_attachment_note),
            walletPassTitle = getString(Res.string.email_wallet_section_title),
            walletPassDescription = getString(Res.string.email_wallet_section_description),
            walletPassCompatibility = getString(Res.string.email_wallet_section_compatibility),
        )
        return withContext(Dispatchers.IO) {
            runCatching {
                DesktopQrEmailService(context).sendViaGmailApi(
                    profile = QrEmailProfile.Volunteer,
                    settingsManager = settings,
                    recipientEmail = to,
                    recipientName = volunteer.name,
                    qrPayload = volunteer.id,
                    template = template,
                )
            }.getOrDefault(false)
        }
    }
}
