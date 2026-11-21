package com.eventmanager.app.portal

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.GmailAuthService
import com.eventmanager.app.data.sync.GmailAuthorizationRequiredException
import com.eventmanager.app.data.sync.GmailSendService
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.email.QrEmailHtmlBuilder
import com.eventmanager.app.email.QrEmailHtmlOptions
import com.eventmanager.app.email.QrEmailTheme
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
import com.eventmanager.app.resources.guest_email_content_after_default
import com.eventmanager.app.resources.guest_email_content_before_default
import com.eventmanager.app.resources.guest_email_subject_default
import com.eventmanager.app.utils.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream

actual object PortalQrMailer {
    actual fun canSend(context: PlatformContext): Boolean =
        runCatching { GmailAuthService(context.androidContext).isAccountSelected() }.getOrDefault(false)

    actual suspend fun send(context: PlatformContext, settings: SettingsManager, volunteer: Volunteer): Boolean {
        val to = volunteer.email.trim()
        if (to.isBlank() || volunteer.isAdmin) return false
        return sendQr(
            context = context,
            settings = settings,
            to = to,
            holderName = volunteer.name,
            qrPayload = volunteer.id,
            subject = settings.getEmailSubject().ifEmpty { getString(Res.string.email_subject_default) },
            contentBefore = settings.getEmailContentBefore().ifEmpty { getString(Res.string.email_content_before_default) },
            contentAfter = settings.getEmailContentAfter().ifEmpty { getString(Res.string.email_content_after_default) },
            theme = QrEmailTheme.Volunteer,
        )
    }

    actual suspend fun sendGuest(context: PlatformContext, settings: SettingsManager, guest: Guest): Boolean {
        val to = guestQrMailRecipient(guest) ?: return false
        return sendQr(
            context = context,
            settings = settings,
            to = to,
            holderName = guest.name,
            qrPayload = guest.nanoId,
            subject = settings.getGuestEmailSubject().ifEmpty { getString(Res.string.guest_email_subject_default) },
            contentBefore = settings.getGuestEmailContentBefore().ifEmpty { getString(Res.string.guest_email_content_before_default) },
            contentAfter = settings.getGuestEmailContentAfter().ifEmpty { getString(Res.string.guest_email_content_after_default) },
            theme = QrEmailTheme.Guest,
        )
    }

    private suspend fun sendQr(
        context: PlatformContext,
        settings: SettingsManager,
        to: String,
        holderName: String,
        qrPayload: String,
        subject: String,
        contentBefore: String,
        contentAfter: String,
        theme: QrEmailTheme,
    ): Boolean {
        val android = context.androidContext
        val gmail = try {
            GmailAuthService(android).createGmailService()
        } catch (_: GmailAuthorizationRequiredException) {
            // Needs the user's consent on screen: leave the request for another device.
            null
        } ?: return false

        val signature = settings.resolveAutomaticEmailSignature(getString(Res.string.email_signature_default))
        val html = QrEmailHtmlBuilder.build(
            QrEmailHtmlOptions(
                holderName = holderName,
                contentBefore = contentBefore,
                contentAfter = contentAfter,
                signature = signature,
                includeQr = true,
                headerText = settings.getEmailHeader().ifEmpty { getString(Res.string.email_html_header) },
                footerText = getString(Res.string.email_html_footer),
                qrAttachmentText = getString(Res.string.email_qr_attachment_text),
                qrAttachmentNote = getString(Res.string.email_qr_attachment_note),
                includeDigitalWalletPass = false,
                digitalWalletPassTitle = "",
                digitalWalletPassDescription = "",
                digitalWalletPassCompatibility = "",
                includeLogo = false,
                useContentId = true,
                theme = theme,
            ),
        )
        val plain = QrEmailHtmlBuilder.buildPlainText(
            contentBefore = contentBefore,
            contentAfter = contentAfter,
            signature = signature,
            includeQr = true,
            includeDigitalWalletPass = false,
        )
        return withContext(Dispatchers.IO) {
            val qrFile = File(android.cacheDir, "portal_qr_${System.nanoTime()}.png")
            try {
                val bitmap = QRCodeUtils.generateQrImageBitmap(qrPayload, 512)?.asAndroidBitmap()
                    ?: return@withContext false
                FileOutputStream(qrFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                GmailSendService(android).sendEmail(
                    gmailService = gmail,
                    to = to,
                    subject = subject,
                    htmlContent = html,
                    plainText = plain,
                    qrFile = qrFile,
                    logoFile = null,
                    digitalWalletPassFile = null,
                ).isSuccess
            } catch (e: Exception) {
                println("Portal QR mail failed: ${e.message}")
                false
            } finally {
                qrFile.delete()
            }
        }
    }
}
