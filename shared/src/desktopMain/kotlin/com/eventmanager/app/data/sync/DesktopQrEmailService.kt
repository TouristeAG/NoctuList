package com.eventmanager.app.data.sync

import com.eventmanager.app.email.MimeEmailAttachment
import com.eventmanager.app.email.MimeEmailBuilder
import com.eventmanager.app.email.QrEmailCode
import com.eventmanager.app.email.QrEmailHtmlBuilder
import com.eventmanager.app.email.QrEmailHtmlOptions
import com.eventmanager.app.email.QrEmailProfile
import com.eventmanager.app.email.QrEmailRecipientCode
import com.eventmanager.app.email.QrEmailTheme
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.platform.appDataDir
import com.eventmanager.app.wallet.WalletPassRequest
import com.eventmanager.app.wallet.WalletPassService
import com.google.api.client.auth.oauth2.Credential
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.Color
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

data class DesktopQrEmailTemplateStrings(
    val volunteerHeader: String,
    val guestHeader: String,
    val volunteerFooter: String,
    val guestFooter: String,
    val volunteerSubjectDefault: String,
    val guestSubjectDefault: String,
    val volunteerContentBeforeDefault: String,
    val guestContentBeforeDefault: String,
    val volunteerContentAfterDefault: String,
    val guestContentAfterDefault: String,
    val signatureDefault: String,
    val qrAttachmentText: String,
    val qrAttachmentNote: String,
    val walletPassTitle: String,
    val walletPassDescription: String,
    val walletPassCompatibility: String,
    val tempGuestHeader: String = guestHeader,
    val tempGuestFooter: String = guestFooter,
    val tempGuestSubjectDefault: String = guestSubjectDefault,
    val tempGuestContentBeforeDefault: String = guestContentBeforeDefault,
    val tempGuestContentAfterDefault: String = guestContentAfterDefault,
)
class DesktopQrEmailService(private val context: PlatformContext) {
    private val fileManager = PlatformFileManager(context)
    private val cacheDir = File(context.appDataDir, "cache").also { it.mkdirs() }

    suspend fun sendViaGmailApi(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        recipientEmail: String,
        recipientName: String,
        qrPayload: String,
        template: DesktopQrEmailTemplateStrings,
        codes: List<QrEmailRecipientCode> = emptyList(),
    ): Boolean {
        val gmailAuth = DesktopGmailAuth(context)
        if (!gmailAuth.isSignedIn()) return false
        val credential = gmailAuth.loadRequestInitializer() ?: return false

        val prepared = prepareEmail(
            profile = profile,
            settingsManager = settingsManager,
            recipientEmail = recipientEmail,
            recipientName = recipientName,
            qrPayload = qrPayload,
            template = template,
            forManualSend = false,
            codes = codes,
        )

        return DesktopGmailSendService(context).sendMimeEmail(
            requestInitializer = credential,
            userId = gmailAuth.getSendUserId(),
            to = recipientEmail,
            mimeMessage = prepared.mimeMessage,
        )
    }

    fun sendManually(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        recipientEmail: String,
        recipientName: String,
        qrPayload: String,
        template: DesktopQrEmailTemplateStrings,
        codes: List<QrEmailRecipientCode> = emptyList(),
    ): Boolean = runCatching {
        val prepared = prepareEmail(
            profile = profile,
            settingsManager = settingsManager,
            recipientEmail = recipientEmail,
            recipientName = recipientName,
            qrPayload = qrPayload,
            template = template,
            forManualSend = true,
            codes = codes,
        )
        val emlFile = File(cacheDir, "qr_email_${System.currentTimeMillis()}.eml")
        emlFile.writeText(prepared.mimeMessage)
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(emlFile)
        }
        true
    }.getOrDefault(false)

    private fun prepareEmail(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        recipientEmail: String,
        recipientName: String,
        qrPayload: String,
        template: DesktopQrEmailTemplateStrings,
        forManualSend: Boolean,
        codes: List<QrEmailRecipientCode> = emptyList(),
    ): PreparedEmail {
        val theme = when (profile) {
            QrEmailProfile.Volunteer -> QrEmailTheme.Volunteer
            QrEmailProfile.Guest -> QrEmailTheme.Guest
            QrEmailProfile.TempGuest -> QrEmailTheme.TempGuest
        }
        val subject = resolveSubject(profile, settingsManager, template)
        val contentBefore = resolveContentBefore(profile, settingsManager, template)
        val contentAfter = resolveContentAfter(profile, settingsManager, template)
        val signature = resolveSignature(profile, settingsManager, template)
        val includeQr = when (profile) {
            QrEmailProfile.Volunteer -> settingsManager.isEmailIncludeQrEnabled()
            QrEmailProfile.Guest -> settingsManager.isGuestEmailIncludeQrEnabled()
            QrEmailProfile.TempGuest -> settingsManager.isTemporaryGuestEmailIncludeQrEnabled()
        }
        val includeLogo = settingsManager.isEmailIncludeLogoEnabled()
        // A grouped artist mail carries several people, so a single-holder wallet pass is meaningless.
        val includeDigitalWalletPass = settingsManager.isEmailIncludeDigitalWalletPassEnabled() &&
            profile != QrEmailProfile.TempGuest
        val headerText = when (profile) {
            QrEmailProfile.Volunteer -> template.volunteerHeader
            QrEmailProfile.Guest -> template.guestHeader
            QrEmailProfile.TempGuest -> template.tempGuestHeader
        }
        val footerText = when (profile) {
            QrEmailProfile.Volunteer -> template.volunteerFooter
            QrEmailProfile.Guest -> template.guestFooter
            QrEmailProfile.TempGuest -> template.tempGuestFooter
        }

        val effectiveCodes = codes.ifEmpty {
            listOf(QrEmailRecipientCode(holderName = recipientName, qrPayload = qrPayload))
        }
        val renderedCodes = if (includeQr) {
            effectiveCodes.mapIndexedNotNull { index, code ->
                val bytes = generateQrPngBytes(code.qrPayload, 512) ?: return@mapIndexedNotNull null
                RenderedQrCode(
                    holderName = code.holderName,
                    contentId = if (effectiveCodes.size == 1) "qrcode" else "qrcode-$index",
                    fileName = MimeEmailBuilder.qrFileNameFor(code.holderName, index),
                    bytes = bytes,
                )
            }
        } else emptyList()
        val logoBytes = if (includeLogo && !forManualSend) loadLogoBytes(settingsManager.getEmailLogoUri()) else null
        val walletPassBytes = if (includeDigitalWalletPass) {
            generateWalletPassBytes(
                settingsManager = settingsManager,
                serialNumber = "$qrPayload-${System.currentTimeMillis()}",
                holderName = recipientName,
                qrPayload = qrPayload,
            )
        } else null

        val html = QrEmailHtmlBuilder.build(
            QrEmailHtmlOptions(
                holderName = recipientName,
                contentBefore = contentBefore,
                contentAfter = contentAfter,
                signature = signature,
                includeQr = if (forManualSend) false else includeQr,
                headerText = headerText,
                footerText = footerText,
                qrAttachmentText = template.qrAttachmentText,
                qrAttachmentNote = template.qrAttachmentNote,
                includeDigitalWalletPass = includeDigitalWalletPass && !forManualSend,
                digitalWalletPassTitle = template.walletPassTitle,
                digitalWalletPassDescription = template.walletPassDescription,
                digitalWalletPassCompatibility = template.walletPassCompatibility,
                includeLogo = if (forManualSend) false else includeLogo,
                qrCodeBase64 = null,
                logoBase64 = null,
                useContentId = !forManualSend,
                theme = theme,
                qrCodes = if (renderedCodes.size > 1) {
                    renderedCodes.map {
                        QrEmailCode(holderName = it.holderName, contentId = it.contentId)
                    }
                } else emptyList(),
            )
        )

        val plainText = QrEmailHtmlBuilder.buildPlainText(
            contentBefore = contentBefore,
            contentAfter = contentAfter,
            signature = signature,
            includeQr = includeQr,
            includeDigitalWalletPass = includeDigitalWalletPass,
        )

        val attachments = buildList {
            if (includeQr) {
                if (forManualSend) {
                    renderedCodes.forEach { code ->
                        add(
                            MimeEmailAttachment(
                                fileName = if (renderedCodes.size == 1) "qr_code.png" else code.fileName,
                                mimeType = "image/png",
                                bytes = code.bytes,
                                disposition = "attachment",
                            )
                        )
                    }
                } else if (renderedCodes.size == 1) {
                    addAll(MimeEmailBuilder.qrInlineAndAttachment(renderedCodes.first().bytes))
                } else {
                    renderedCodes.forEach { code ->
                        addAll(
                            MimeEmailBuilder.namedQrInlineAndAttachment(
                                qrBytes = code.bytes,
                                contentId = code.contentId,
                                fileName = code.fileName,
                            )
                        )
                    }
                }
            }
            if (!forManualSend && includeLogo && logoBytes != null) {
                add(MimeEmailBuilder.logoInlineAttachment(logoBytes))
            }
            if (includeDigitalWalletPass && walletPassBytes != null) {
                add(MimeEmailBuilder.walletPassAttachment(walletPassBytes))
            }
        }

        val mimeMessage = MimeEmailBuilder.buildMultipartRelatedEmail(
            to = recipientEmail,
            subject = subject,
            plainText = plainText,
            htmlContent = html,
            attachments = attachments,
        )

        return PreparedEmail(subject = subject, mimeMessage = mimeMessage)
    }

    private fun resolveSubject(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        template: DesktopQrEmailTemplateStrings,
    ): String = when (profile) {
        QrEmailProfile.Volunteer -> settingsManager.getEmailSubject().ifEmpty { template.volunteerSubjectDefault }
        QrEmailProfile.Guest -> settingsManager.getGuestEmailSubject().ifEmpty { template.guestSubjectDefault }
        QrEmailProfile.TempGuest ->
            settingsManager.getTemporaryGuestEmailSubject().ifEmpty { template.tempGuestSubjectDefault }
    }

    private fun resolveContentBefore(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        template: DesktopQrEmailTemplateStrings,
    ): String = when (profile) {
        QrEmailProfile.Volunteer -> settingsManager.getEmailContentBefore().ifEmpty { template.volunteerContentBeforeDefault }
        QrEmailProfile.Guest -> settingsManager.getGuestEmailContentBefore().ifEmpty { template.guestContentBeforeDefault }
        QrEmailProfile.TempGuest ->
            settingsManager.getTemporaryGuestEmailContentBefore().ifEmpty { template.tempGuestContentBeforeDefault }
    }

    private fun resolveContentAfter(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        template: DesktopQrEmailTemplateStrings,
    ): String = when (profile) {
        QrEmailProfile.Volunteer -> settingsManager.getEmailContentAfter().ifEmpty { template.volunteerContentAfterDefault }
        QrEmailProfile.Guest -> settingsManager.getGuestEmailContentAfter().ifEmpty { template.guestContentAfterDefault }
        QrEmailProfile.TempGuest ->
            settingsManager.getTemporaryGuestEmailContentAfter().ifEmpty { template.tempGuestContentAfterDefault }
    }

    private fun resolveSignature(
        profile: QrEmailProfile,
        settingsManager: SettingsManager,
        template: DesktopQrEmailTemplateStrings,
    ): String = when (profile) {
        QrEmailProfile.Volunteer -> settingsManager.getEmailSignature().ifEmpty { template.signatureDefault }
        QrEmailProfile.Guest -> settingsManager.getGuestEmailSignature().ifEmpty { template.signatureDefault }
        QrEmailProfile.TempGuest -> settingsManager.getGuestEmailSignature().ifEmpty { template.signatureDefault }
    }

    private fun loadLogoBytes(logoPath: String): ByteArray? {
        if (logoPath.isBlank()) return null
        val file = fileManager.getEmailLogoFile() ?: File(logoPath).takeIf { it.exists() } ?: return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun generateQrPngBytes(content: String, sizePx: Int): ByteArray? {
        if (content.isEmpty()) return null
        return runCatching {
            val hints = mapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    image.setRGB(x, y, if (matrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
                }
            }
            ByteArrayOutputStream().use { baos ->
                ImageIO.write(image, "png", baos)
                baos.toByteArray()
            }
        }.getOrNull()
    }

    private fun generateWalletPassBytes(
        settingsManager: SettingsManager,
        serialNumber: String,
        holderName: String,
        qrPayload: String,
    ): ByteArray? {
        val certBytes = fileManager.getWalletPassCertificateFile()?.readBytes()
        val logoBytes = loadLogoBytes(settingsManager.getEmailLogoUri())
        val request = WalletPassRequest(
            serialNumber = serialNumber,
            holderName = holderName,
            qrPayload = qrPayload,
            associationName = settingsManager.getEmailAssociationName(),
        )
        return WalletPassService.createPassBytes(settingsManager, certBytes, request, logoBytes)
    }

    private data class RenderedQrCode(
        val holderName: String,
        val contentId: String,
        val fileName: String,
        val bytes: ByteArray,
    )

    private data class PreparedEmail(
        val subject: String,
        val mimeMessage: String,
    )
}
