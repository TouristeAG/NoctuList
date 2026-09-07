package com.eventmanager.app.email

enum class QrEmailProfile {
    Volunteer,
    Guest,

    /** One mail carrying the whole artist guest list, so it can hold several QR codes. */
    TempGuest,
}

enum class QrEmailTheme {
    Volunteer,
    Guest,
    TempGuest,
}

/**
 * One QR block in the mail. [contentId] must match the inline attachment's Content-ID, and
 * [base64] is the fallback used when the mail is rendered without attachments.
 */
data class QrEmailCode(
    val holderName: String,
    val contentId: String = "qrcode",
    val base64: String? = null,
)

/**
 * One person's QR to put in a mail, before any image is rendered. A single-entry list reproduces
 * the classic one-code mail exactly.
 */
data class QrEmailRecipientCode(
    val holderName: String,
    val qrPayload: String,
)

data class QrEmailHtmlOptions(
    val holderName: String,
    val contentBefore: String,
    val contentAfter: String,
    val signature: String,
    val includeQr: Boolean,
    val headerText: String,
    val footerText: String,
    val qrAttachmentText: String,
    val qrAttachmentNote: String,
    val includeDigitalWalletPass: Boolean,
    val digitalWalletPassTitle: String,
    val digitalWalletPassDescription: String,
    val digitalWalletPassCompatibility: String,
    val includeLogo: Boolean,
    val qrCodeBase64: String? = null,
    val logoBase64: String? = null,
    val useContentId: Boolean = false,
    val theme: QrEmailTheme,
    /**
     * Several QR codes in one mail. Empty means the single code described by [holderName] and
     * [qrCodeBase64], which renders exactly as it always has.
     */
    val qrCodes: List<QrEmailCode> = emptyList(),
)

object QrEmailHtmlBuilder {
    fun build(options: QrEmailHtmlOptions): String {
        val headerGradient = when (options.theme) {
            QrEmailTheme.Volunteer -> "linear-gradient(135deg, #4f46e5 0%, #7c3aed 50%, #a855f7 100%)"
            QrEmailTheme.Guest -> "linear-gradient(135deg, #10b981 0%, #059669 50%, #047857 100%)"
            QrEmailTheme.TempGuest -> "linear-gradient(135deg, #f43f5e 0%, #e11d48 50%, #9f1239 100%)"
        }
        val qrBoxGradient = when (options.theme) {
            QrEmailTheme.Volunteer -> "linear-gradient(135deg, #667eea 0%, #764ba2 100%)"
            QrEmailTheme.Guest -> "linear-gradient(135deg, #10b981 0%, #059669 100%)"
            QrEmailTheme.TempGuest -> "linear-gradient(135deg, #fb7185 0%, #e11d48 100%)"
        }

        fun String.toHtmlParagraphs(): String =
            split("\n\n")
                .filter { it.isNotBlank() }
                .joinToString("") { paragraph ->
                    "<p style=\"margin: 0 0 16px 0; line-height: 1.6;\">${paragraph.replace("\n", "<br>")}</p>"
                }

        fun String.toHtmlSignature(): String =
            split("\n")
                .filter { it.isNotBlank() }
                .joinToString("<br>") { line ->
                    "<strong style=\"color: #1f2937; font-weight: 600;\">$line</strong>"
                }

        val codes = options.qrCodes.ifEmpty {
            listOf(
                QrEmailCode(
                    holderName = options.holderName,
                    contentId = "qrcode",
                    base64 = options.qrCodeBase64,
                )
            )
        }

        fun qrCard(code: QrEmailCode): String {
            val image = if (options.useContentId) {
                """<img src="cid:${code.contentId}" 
                                                     alt="QR Code" 
                                                     width="180" 
                                                     height="180" 
                                                     style="display: block; margin: 0 auto; border: none; max-width: 180px; max-height: 180px;">"""
            } else if (code.base64 != null) {
                """<img src="data:image/png;base64,${code.base64}" 
                                                     alt="QR Code" 
                                                     width="180" 
                                                     height="180" 
                                                     style="display: block; margin: 0 auto; border: none; max-width: 180px; max-height: 180px;">"""
            } else {
                """<div style="color: #94a3b8; font-size: 14px; text-align: center; padding: 20px;">
                                                    <table role="presentation" cellpadding="0" cellspacing="0" style="width: 120px; height: 120px; margin: 0 auto 12px; border: 3px dashed #cbd5e1; border-radius: 8px; background-color: #f8fafc;">
                                                        <tr>
                                                            <td style="text-align: center; vertical-align: middle;">
                                                                <div style="font-size: 32px; color: #94a3b8; font-weight: 600;">QR</div>
                                                            </td>
                                                        </tr>
                                                    </table>
                                                    <div style="color: #64748b; font-weight: 500;">${options.qrAttachmentText.replace("\n", "<br>")}</div>
                                                 </div>"""
            }
            val note = if (code.base64 == null && !options.useContentId) {
                """<p style="margin: 12px 0 0 0; font-size: 12px; color: #64748b; font-style: italic;">
                                    ${options.qrAttachmentNote}
                                </p>"""
            } else ""
            return """
                <table role="presentation" cellpadding="0" cellspacing="0" style="margin: 0 auto 16px auto; width: 100%; max-width: 300px;">
                    <tr>
                        <td style="background-color: #ffffff; padding: 32px; border-radius: 16px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); border: 2px solid #e2e8f0;">
                            <div style="background: $qrBoxGradient; padding: 24px; border-radius: 12px; margin-bottom: 20px;">
                                <table role="presentation" cellpadding="0" cellspacing="0" style="width: 200px; height: 200px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                                    <tr>
                                        <td style="text-align: center; vertical-align: middle; padding: 10px;">
                                            $image
                                        </td>
                                    </tr>
                                </table>
                            </div>
                            <p style="margin: 0; font-size: 14px; color: #475569; font-weight: 500; letter-spacing: 0.3px;">
                                ${code.holderName}
                            </p>
                            $note
                        </td>
                    </tr>
                </table>
            """.trimIndent()
        }

        val qrSection = if (options.includeQr) {
            """
        <tr>
            <td style="padding: 40px 40px; text-align: center; background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);">
                ${codes.joinToString("\n") { qrCard(it) }}
            </td>
        </tr>
            """.trimIndent()
        } else ""

        val walletPassSection = if (options.includeDigitalWalletPass && options.useContentId) {
            """
        <tr>
            <td style="padding: 10px 40px 26px 40px;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%" style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px;">
                    <tr>
                        <td style="padding: 12px 14px;">
                            <div style="font-size: 14px; font-weight: 700; color: #1f2937; margin-bottom: 4px;">${options.digitalWalletPassTitle}</div>
                            <div style="font-size: 12px; color: #475569; line-height: 1.45;">${options.digitalWalletPassDescription}</div>
                            <div style="margin-top: 4px; font-size: 11px; color: #64748b;">${options.digitalWalletPassCompatibility}</div>
                        </td>
                    </tr>
                </table>
            </td>
        </tr>
            """.trimIndent()
        } else ""

        return """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Your QR Code</title>
    </head>
    <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f4f4f5;">
        <table role="presentation" cellpadding="0" cellspacing="0" width="100%" style="min-width: 100%; background-color: #f4f4f5;">
            <tr>
                <td align="center" style="padding: 40px 20px;">
                    <table role="presentation" cellpadding="0" cellspacing="0" width="600" style="max-width: 600px; width: 100%; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.07);">
                        <tr>
                            <td style="background: $headerGradient; padding: 48px 40px; text-align: center; position: relative; overflow: hidden;">
                                <div style="position: absolute; top: -50%; left: -50%; width: 200%; height: 200%; background: radial-gradient(circle, rgba(255,255,255,0.1) 0%, transparent 70%); pointer-events: none;"></div>
                                <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700; letter-spacing: -0.8px; text-shadow: 0 2px 8px rgba(0,0,0,0.15); position: relative; z-index: 1;">
                                    ${options.headerText}
                                </h1>
                                <div style="margin-top: 12px; height: 3px; width: 60px; background-color: rgba(255,255,255,0.6); margin-left: auto; margin-right: auto; border-radius: 2px; position: relative; z-index: 1;"></div>
                            </td>
                        </tr>
                        <tr>
                            <td style="padding: 40px 40px 24px 40px; color: #1f2937; font-size: 16px; line-height: 1.7;">
                                ${options.contentBefore.toHtmlParagraphs()}
                            </td>
                        </tr>
                        $qrSection
                        <tr>
                            <td style="padding: 24px 40px 40px 40px; color: #1f2937; font-size: 16px; line-height: 1.7;">
                                ${options.contentAfter.toHtmlParagraphs()}
                            </td>
                        </tr>
                        <tr>
                            <td style="padding: 32px 40px; border-top: 2px solid #f1f5f9; background-color: #fafbfc;">
                                <table role="presentation" cellpadding="0" cellspacing="0" width="100%">
                                    <tr>
                                        <td style="vertical-align: ${if (options.includeLogo && options.logoBase64 != null) "top" else "middle"};">
                                            <div style="color: #1f2937; font-size: 15px; line-height: 1.8;">
                                                ${options.signature.toHtmlSignature()}
                                            </div>
                                        </td>
                                        ${if (options.includeLogo) {
            if (options.useContentId) {
                """<td style="text-align: right; padding-left: 24px; vertical-align: middle;">
                                                    <img src="cid:logo" 
                                                         alt="Logo" 
                                                         style="max-width: 120px; max-height: 80px; display: block; border: none; height: auto;">
                                                </td>"""
            } else if (options.logoBase64 != null) {
                """<td style="text-align: right; padding-left: 24px; vertical-align: middle;">
                                                    <img src="data:image/png;base64,${options.logoBase64}" 
                                                         alt="Logo" 
                                                         style="max-width: 120px; max-height: 80px; display: block; border: none; height: auto;">
                                                </td>"""
            } else ""
        } else ""}
                                    </tr>
                                </table>
                            </td>
                        </tr>
                        $walletPassSection
                    </table>
                    <table role="presentation" cellpadding="0" cellspacing="0" width="600" style="max-width: 600px; width: 100%;">
                        <tr>
                            <td style="padding: 32px 40px; text-align: center;">
                                <div style="width: 40px; height: 1px; background: linear-gradient(90deg, transparent, #e5e7eb, transparent); margin: 0 auto 20px;"></div>
                                <p style="margin: 0; font-size: 12px; color: #9ca3af; line-height: 1.6;">
                                    ${options.footerText}
                                </p>
                            </td>
                        </tr>
                    </table>
                </td>
            </tr>
        </table>
    </body>
    </html>
        """.trimIndent()
    }

    fun buildPlainText(
        contentBefore: String,
        contentAfter: String,
        signature: String,
        includeQr: Boolean,
        includeDigitalWalletPass: Boolean,
    ): String = buildString {
        append(contentBefore)
        append("\n\n")
        if (includeQr) append("[ QR Code - See attachment ]\n\n")
        if (includeDigitalWalletPass) append("[ Digital Wallet Pass (.pkpass) - See attachment ]\n\n")
        append(contentAfter)
        append("\n\n")
        append(signature)
    }
}
