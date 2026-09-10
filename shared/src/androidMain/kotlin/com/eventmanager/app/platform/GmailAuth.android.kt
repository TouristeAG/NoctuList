package com.eventmanager.app.platform

import com.eventmanager.app.data.sync.GmailAuthService
import com.eventmanager.app.data.sync.GmailSendService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createGmailAuth(context: PlatformContext): GmailAuth =
    AndroidGmailAuth(context)

private class AndroidGmailAuth(private val context: PlatformContext) : GmailAuth {
    private val authService = GmailAuthService(context.androidContext)

    override val isSignedIn: Boolean get() = authService.isAccountSelected()
    override val accountEmail: String? get() = authService.getSelectedAccountEmail()
    override val lastSignInError: String? get() = null

    override suspend fun signIn(): Boolean {
        return try {
            authService.createGmailService()
            authService.isCredentialReady()
        } catch (_: com.eventmanager.app.data.sync.GmailAuthorizationRequiredException) {
            false
        }
    }

    override suspend fun signOut() {
        authService.clearSelectedAccount()
        authService.clearCachedToken()
    }

    override suspend fun sendEmail(
        to: String,
        subject: String,
        htmlBody: String,
        attachments: List<EmailAttachment>
    ): Boolean = withContext(Dispatchers.IO) {
        val service = try {
            authService.createGmailService()
        } catch (_: com.eventmanager.app.data.sync.GmailAuthorizationRequiredException) {
            return@withContext false
        } ?: return@withContext false
        GmailSendService(context.androidContext).sendEmail(
            gmailService = service,
            to = to,
            subject = subject,
            htmlContent = htmlBody,
            plainText = htmlBody,
            qrFile = null,
            logoFile = null,
            digitalWalletPassFile = null
        ).isSuccess
    }
}
