package com.eventmanager.app.data.sync

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message as GmailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Exception that contains an authorization Intent for the user to grant permissions
 */
class GmailAuthorizationRequiredException(val authIntent: Intent, message: String) : Exception(message)

/**
 * Exception thrown when Gmail API is not configured in Google Cloud Console
 */
class GmailNotConfiguredException(message: String) : Exception(message)
class GmailPlayServicesUnavailableException(message: String) : Exception(message)

/**
 * One QR image of a mail that carries several codes, e.g. a whole artist guest list.
 * [contentId] must match the `cid:` reference used in the HTML body.
 */
data class GmailQrAttachment(
    val file: File,
    val contentId: String,
    val fileName: String,
)

/**
 * Service for sending emails via Gmail API
 * Uses manual MIME construction to avoid AWT dependencies
 */
class GmailSendService(private val context: Context) {
    private val TAG = "GmailSendService"
    
    /**
     * Sends an email via Gmail API with multipart/related content
     */
    suspend fun sendEmail(
        gmailService: Gmail,
        to: String,
        subject: String,
        htmlContent: String,
        plainText: String,
        qrFile: File?,
        logoFile: File?,
        digitalWalletPassFile: File?,
        qrAttachments: List<GmailQrAttachment> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Generate unique boundary for multipart message
            val boundary = "----=_Part_${UUID.randomUUID().toString().replace("-", "")}"
            
            // Build MIME message manually to avoid DataHandler/AWT dependencies
            val mimeMessage = buildMultipartEmail(
                to = to,
                subject = subject,
                htmlContent = htmlContent,
                plainText = plainText,
                qrFile = qrFile,
                logoFile = logoFile,
                digitalWalletPassFile = digitalWalletPassFile,
                qrAttachments = qrAttachments,
                boundary = boundary
            )
            
            // Convert to bytes and encode to base64url
            val bytes = mimeMessage.toByteArray(Charsets.UTF_8)
            val encodedEmail = Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
            
            // Create Gmail message
            val gmailMessage = GmailMessage().apply {
                raw = encodedEmail
            }
            
            // Send via Gmail API
            val sentMessage = gmailService.users().messages().send("me", gmailMessage).execute()
            
            Log.d(TAG, "Email sent successfully. Message ID: ${sentMessage.id}")
            Result.success(Unit)
        } catch (e: UserRecoverableAuthIOException) {
            // User needs to authorize the app - return the authorization intent
            Log.d(TAG, "User authorization required for Gmail API", e)
            Log.d(TAG, "Auth intent available: ${e.intent != null}")
            if (e.intent != null) {
                Result.failure(GmailAuthorizationRequiredException(
                    authIntent = e.intent,
                    message = "Please authorize the app to send emails via Gmail"
                ))
            } else {
                Log.e(TAG, "Auth intent is null - OAuth settings may not be fully propagated yet")
                Result.failure(Exception(
                    "OAuth authorization is required but not yet available. " +
                    "If you just configured OAuth in Google Cloud Console, please wait 5 minutes to 24 hours " +
                    "for the settings to propagate, then try again."
                ))
            }
        } catch (e: GoogleAuthIOException) {
            // Check if the cause is a UserRecoverableAuthException (wrapped) - handle this first
            val userRecoverableCause = e.cause as? com.google.android.gms.auth.UserRecoverableAuthException
            if (userRecoverableCause != null && userRecoverableCause.intent != null) {
                Log.d(TAG, "User authorization required (wrapped in GoogleAuthIOException)", e)
                Result.failure(GmailAuthorizationRequiredException(
                    authIntent = userRecoverableCause.intent!!,
                    message = "Please authorize the app to send emails via Gmail"
                ))
            } else {
                // Check if it's an UnregisteredOnApiConsole error
                val cause = e.cause
                if (isGooglePlayServicesUnavailableError(cause, e)) {
                    Log.e(TAG, "Google Play Services / Play Store unavailable for Gmail auth", e)
                    Result.failure(
                        GmailPlayServicesUnavailableException(
                            "Gmail API authentication requires official Google Play Services and the Google Play Store. " +
                                "This device does not provide them (common on microG / LineageOS). " +
                                "Please use manual email sending instead."
                        )
                    )
                } else if (cause is com.google.android.gms.auth.GoogleAuthException &&
                    cause.message?.contains("UnregisteredOnApiConsole", ignoreCase = true) == true) {
                    Log.e(TAG, "Gmail API not registered in Google Cloud Console. " +
                        "The app needs to be registered with OAuth credentials.", e)
                    // User-friendly error message
                    Result.failure(GmailNotConfiguredException(
                        "Gmail sending is not yet configured. Please use the manual email option instead, " +
                        "or contact the app developer to enable Gmail API integration."
                    ))
                } else {
                    Log.e(TAG, "Gmail authentication error", e)
                    Result.failure(e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending email via Gmail API", e)
            Result.failure(e)
        }
    }

    private fun isGooglePlayServicesUnavailableError(cause: Throwable?, googleAuthIOException: GoogleAuthIOException): Boolean {
        val combinedMessages = buildString {
            append(googleAuthIOException.message.orEmpty())
            append(" ")
            append(cause?.message.orEmpty())
        }

        if (combinedMessages.contains("GooglePlayServices not available due to error 9", ignoreCase = true) ||
            combinedMessages.contains("requires the Google Play Store, but it is missing", ignoreCase = true)
        ) {
            return true
        }

        var current: Throwable? = cause ?: googleAuthIOException
        var depth = 0
        while (current != null && depth < 10) {
            if (current is com.google.android.gms.common.GooglePlayServicesNotAvailableException) {
                return true
            }
            val currentMessage = current.message.orEmpty()
            if (currentMessage.contains("GooglePlayServices not available due to error 9", ignoreCase = true) ||
                currentMessage.contains("requires the Google Play Store, but it is missing", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
    
    /**
     * Encodes email subject according to RFC 2047 for non-ASCII characters
     */
    private fun encodeSubject(subject: String): String {
        // Check if subject contains non-ASCII characters
        val hasNonAscii = subject.any { it.code > 127 }
        
        if (!hasNonAscii) {
            return subject
        }
        
        // Encode using RFC 2047 format: =?charset?encoding?encoded-text?=
        // Using base64 encoding (B) for UTF-8
        val bytes = subject.toByteArray(Charsets.UTF_8)
        val base64Encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "=?UTF-8?B?$base64Encoded?="
    }
    
    /**
     * Builds a multipart/related email with Content-ID references for images
     * This manual construction avoids JavaMail's DataHandler which depends on AWT
     */
    private fun buildMultipartEmail(
        to: String,
        subject: String,
        plainText: String,
        htmlContent: String,
        qrFile: File?,
        logoFile: File?,
        digitalWalletPassFile: File?,
        qrAttachments: List<GmailQrAttachment>,
        boundary: String
    ): String {
        val sb = StringBuilder()
        
        // Email headers
        sb.append("From: noreply@eventmanager.app\r\n")
        sb.append("To: $to\r\n")
        sb.append("Subject: ${encodeSubject(subject)}\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")
        
        // Start multipart/alternative section
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: multipart/alternative; boundary=\"${boundary}_alt\"\r\n")
        sb.append("\r\n")
        
        // Plain text part
        sb.append("--${boundary}_alt\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(plainText)
        sb.append("\r\n")
        
        // HTML part
        sb.append("--${boundary}_alt\r\n")
        sb.append("Content-Type: text/html; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(htmlContent)
        sb.append("\r\n")
        
        // Close alternative section
        sb.append("--${boundary}_alt--\r\n")
        sb.append("\r\n")
        
        // Several named QR codes (one artist guest list in a single mail)
        qrAttachments.filter { it.file.exists() }.forEach { attachment ->
            val base64 = Base64.encodeToString(attachment.file.readBytes(), Base64.NO_WRAP)
            listOf("inline", "attachment").forEach { disposition ->
                sb.append("--$boundary\r\n")
                sb.append("Content-Type: image/png; name=\"${attachment.fileName}\"\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n")
                sb.append("Content-Disposition: $disposition; filename=\"${attachment.fileName}\"\r\n")
                if (disposition == "inline") {
                    sb.append("Content-ID: <${attachment.contentId}>\r\n")
                }
                sb.append("\r\n")
                base64.chunked(76).forEach { line ->
                    sb.append(line)
                    sb.append("\r\n")
                }
                sb.append("\r\n")
            }
        }

        // QR Code - First as inline with Content-ID for HTML display
        if (qrFile != null && qrFile.exists()) {
            val qrBytes = qrFile.readBytes()
            val qrBase64 = Base64.encodeToString(qrBytes, Base64.NO_WRAP)
            
            // Inline version for HTML display (with Content-ID)
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: image/png; name=\"qr_code.png\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: inline; filename=\"qr_code.png\"\r\n")
            sb.append("Content-ID: <qrcode>\r\n")
            sb.append("\r\n")
            // Split into 76-character lines (RFC 2045)
            qrBase64.chunked(76).forEach { line ->
                sb.append(line)
                sb.append("\r\n")
            }
            sb.append("\r\n")
            
            // Attachment version for download (as actual attachment)
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: image/png; name=\"qr_code.png\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"qr_code.png\"\r\n")
            sb.append("\r\n")
            // Split into 76-character lines (RFC 2045)
            qrBase64.chunked(76).forEach { line ->
                sb.append(line)
                sb.append("\r\n")
            }
            sb.append("\r\n")
        }
        
        // Logo attachment with Content-ID
        if (logoFile != null && logoFile.exists()) {
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: image/png; name=\"logo.png\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: inline; filename=\"logo.png\"\r\n")
            sb.append("Content-ID: <logo>\r\n")
            sb.append("\r\n")
            
            val logoBytes = logoFile.readBytes()
            val logoBase64 = Base64.encodeToString(logoBytes, Base64.NO_WRAP)
            // Split into 76-character lines (RFC 2045)
            logoBase64.chunked(76).forEach { line ->
                sb.append(line)
                sb.append("\r\n")
            }
            sb.append("\r\n")
        }

        if (digitalWalletPassFile != null && digitalWalletPassFile.exists()) {
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: application/vnd.apple.pkpass; name=\"digital_wallet_pass.pkpass\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"digital_wallet_pass.pkpass\"\r\n")
            sb.append("\r\n")

            val passBytes = digitalWalletPassFile.readBytes()
            val passBase64 = Base64.encodeToString(passBytes, Base64.NO_WRAP)
            passBase64.chunked(76).forEach { line ->
                sb.append(line)
                sb.append("\r\n")
            }
            sb.append("\r\n")
        }
        
        // Close boundary
        sb.append("--$boundary--\r\n")
        
        return sb.toString()
    }
}

