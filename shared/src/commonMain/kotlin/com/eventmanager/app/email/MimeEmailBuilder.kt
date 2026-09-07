package com.eventmanager.app.email

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class MimeEmailAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val contentId: String? = null,
    val disposition: String = "attachment",
)

object MimeEmailBuilder {
    fun buildMultipartRelatedEmail(
        to: String,
        subject: String,
        plainText: String,
        htmlContent: String,
        attachments: List<MimeEmailAttachment>,
        boundary: String = "----=_Part_${generateBoundarySuffix()}",
        fromAddress: String = "noreply@eventmanager.app",
    ): String {
        val sb = StringBuilder()
        sb.append("From: $fromAddress\r\n")
        sb.append("To: $to\r\n")
        sb.append("Subject: ${encodeSubject(subject)}\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")

        sb.append("--$boundary\r\n")
        sb.append("Content-Type: multipart/alternative; boundary=\"${boundary}_alt\"\r\n")
        sb.append("\r\n")

        sb.append("--${boundary}_alt\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(plainText)
        sb.append("\r\n")

        sb.append("--${boundary}_alt\r\n")
        sb.append("Content-Type: text/html; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(htmlContent)
        sb.append("\r\n")

        sb.append("--${boundary}_alt--\r\n")
        sb.append("\r\n")

        attachments.forEach { attachment ->
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: ${attachment.mimeType}; name=\"${attachment.fileName}\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: ${attachment.disposition}; filename=\"${attachment.fileName}\"\r\n")
            attachment.contentId?.let { sb.append("Content-ID: <$it>\r\n") }
            sb.append("\r\n")
            encodeBase64(attachment.bytes).chunked(76).forEach { line ->
                sb.append(line)
                sb.append("\r\n")
            }
            sb.append("\r\n")
        }

        sb.append("--$boundary--\r\n")
        return sb.toString()
    }

    fun qrInlineAndAttachment(qrBytes: ByteArray): List<MimeEmailAttachment> = listOf(
        MimeEmailAttachment(
            fileName = "qr_code.png",
            mimeType = "image/png",
            bytes = qrBytes,
            contentId = "qrcode",
            disposition = "inline",
        ),
        MimeEmailAttachment(
            fileName = "qr_code.png",
            mimeType = "image/png",
            bytes = qrBytes,
            disposition = "attachment",
        ),
    )

    /**
     * Inline + downloadable attachment for one named QR code of a multi-code mail. The file name
     * carries the person's name so a saved attachment stays identifiable.
     */
    fun namedQrInlineAndAttachment(
        qrBytes: ByteArray,
        contentId: String,
        fileName: String,
    ): List<MimeEmailAttachment> = listOf(
        MimeEmailAttachment(
            fileName = fileName,
            mimeType = "image/png",
            bytes = qrBytes,
            contentId = contentId,
            disposition = "inline",
        ),
        MimeEmailAttachment(
            fileName = fileName,
            mimeType = "image/png",
            bytes = qrBytes,
            disposition = "attachment",
        ),
    )

    /** Sanitizes a person's name into a safe PNG file name, e.g. `qr_Ana_Lopez.png`. */
    fun qrFileNameFor(holderName: String, index: Int): String {
        val slug = holderName.trim()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
            .take(40)
        return if (slug.isEmpty()) "qr_code_${index + 1}.png" else "qr_${slug}.png"
    }

    fun logoInlineAttachment(logoBytes: ByteArray): MimeEmailAttachment =
        MimeEmailAttachment(
            fileName = "logo.png",
            mimeType = "image/png",
            bytes = logoBytes,
            contentId = "logo",
            disposition = "inline",
        )

    fun walletPassAttachment(passBytes: ByteArray): MimeEmailAttachment =
        MimeEmailAttachment(
            fileName = "digital_wallet_pass.pkpass",
            mimeType = "application/vnd.apple.pkpass",
            bytes = passBytes,
            disposition = "attachment",
        )

    private fun encodeSubject(subject: String): String {
        if (subject.all { it.code <= 127 }) return subject
        val encoded = encodeBase64(subject.encodeToByteArray())
        return "=?UTF-8?B?$encoded?="
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeBase64(bytes: ByteArray): String =
        Base64.Default.encode(bytes)

    private fun generateBoundarySuffix(): String =
        (1..32).map { (('a'..'z') + ('0'..'9')).random() }.joinToString("")
}
