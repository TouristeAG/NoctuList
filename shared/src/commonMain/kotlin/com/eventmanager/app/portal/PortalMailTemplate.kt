package com.eventmanager.app.portal

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.email.QrEmailHtmlBuilder
import com.eventmanager.app.email.QrEmailHtmlOptions
import com.eventmanager.app.email.QrEmailTheme
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.email_content_after_default
import com.eventmanager.app.resources.email_content_before_default
import com.eventmanager.app.resources.email_html_footer
import com.eventmanager.app.resources.email_html_header
import com.eventmanager.app.resources.email_qr_attachment_note
import com.eventmanager.app.resources.email_qr_attachment_text
import com.eventmanager.app.resources.email_signature_default
import com.eventmanager.app.resources.email_subject_default
import org.jetbrains.compose.resources.getString

/** Placeholder the server swaps for the volunteer's first name when it renders the mail. */
const val PORTAL_FIRST_NAME_TOKEN = "%%NOCTULIST_FIRST_NAME%%"

/**
 * The "my QR code" mail, rendered once per institution with the institution's own e-mail
 * settings. It holds no volunteer data; the server fills in the name and attaches the QR image
 * (`cid:qrcode`) and the logo (`cid:logo`) when it sends.
 */
data class PortalMailTemplate(
    val subject: String,
    val html: String,
    val text: String,
    val logoBase64: String,
) {
    /** [lastModified] stays out of the content hash, so it is added by the caller when writing. */
    fun toFirestoreMap(lastModified: Long? = null): Map<String, Any?> = mapOf(
        "subject" to subject,
        "html" to html,
        "text" to text,
        "logoBase64" to logoBase64,
        "schema" to 1,
    ) + (if (lastModified != null) mapOf("lastModified" to lastModified) else emptyMap())

    companion object {
        /** Renders the Volunteer-profile mail from [settings], with the same defaults as the device mailer. */
        suspend fun fromSettings(settings: SettingsManager, logoPngBase64: String): PortalMailTemplate {
            val subject = settings.getEmailSubject().ifEmpty { getString(Res.string.email_subject_default) }
            val header = settings.getEmailHeader().ifEmpty { getString(Res.string.email_html_header) }
            val contentBefore = settings.getEmailContentBefore().ifEmpty { getString(Res.string.email_content_before_default) }
            val contentAfter = settings.getEmailContentAfter().ifEmpty { getString(Res.string.email_content_after_default) }
            val signature = settings.resolveAutomaticEmailSignature(getString(Res.string.email_signature_default))
            val logo = logoPngBase64.trim()
            val includeLogo = settings.isEmailIncludeLogoEnabled() && logo.isNotBlank()
            val html = QrEmailHtmlBuilder.build(
                QrEmailHtmlOptions(
                    holderName = PORTAL_FIRST_NAME_TOKEN,
                    contentBefore = contentBefore,
                    contentAfter = contentAfter,
                    signature = signature,
                    includeQr = true,
                    headerText = header,
                    footerText = getString(Res.string.email_html_footer),
                    qrAttachmentText = getString(Res.string.email_qr_attachment_text),
                    qrAttachmentNote = getString(Res.string.email_qr_attachment_note),
                    includeDigitalWalletPass = false,
                    digitalWalletPassTitle = "",
                    digitalWalletPassDescription = "",
                    digitalWalletPassCompatibility = "",
                    includeLogo = includeLogo,
                    useContentId = true,
                    theme = QrEmailTheme.Volunteer,
                ),
            )
            val text = QrEmailHtmlBuilder.buildPlainText(
                contentBefore = contentBefore,
                contentAfter = contentAfter,
                signature = signature,
                includeQr = true,
                includeDigitalWalletPass = false,
            )
            return PortalMailTemplate(subject, html, text, if (includeLogo) logo else "")
        }
    }
}
