package com.eventmanager.app.data.remote

import java.net.URLEncoder

/**
 * Institution Firebase Sign-In via the **Web** OAuth client (browser + loopback redirect).
 * Desktop and Android share the same localhost callback URIs — no Android OAuth client / SHA-1
 * per institution.
 */
object InstitutionGoogleWebOAuth {
    const val LOOPBACK_CALLBACK_PATH = "/Callback"

    val LOOPBACK_PORTS: List<Int> = listOf(8889, 8888, 8765, 9090)

    /** Registered in each institution Web OAuth client (Desktop + Android). */
    val LOOPBACK_REDIRECT_URIS: List<String> = LOOPBACK_PORTS.map { port ->
        loopbackRedirectUri(port)
    }

    /** @deprecated Use [LOOPBACK_REDIRECT_URIS] — kept as alias for Desktop docs. */
    val DESKTOP_REDIRECT_URIS: List<String> = LOOPBACK_REDIRECT_URIS

    fun loopbackRedirectUri(port: Int): String =
        "http://localhost:$port$LOOPBACK_CALLBACK_PATH"

    /** One URI per line — paste into Google Cloud “Authorized redirect URIs”. */
    fun loopbackRedirectUrisClipboardText(): String =
        LOOPBACK_REDIRECT_URIS.joinToString("\n")

    val OAUTH_SCOPES: List<String> = listOf(
        "openid",
        "email",
        "profile",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
    )

    const val SCOPE_DOCS_READONLY = "https://www.googleapis.com/auth/documents.readonly"
    const val SCOPE_SHEETS_READONLY = "https://www.googleapis.com/auth/spreadsheets.readonly"
    const val SCOPE_CONTACTS_READONLY = "https://www.googleapis.com/auth/contacts.readonly"
    const val SCOPE_DRIVE_METADATA_READONLY = "https://www.googleapis.com/auth/drive.metadata.readonly"

    /**
     * Requested on top of [OAUTH_SCOPES] when the institution enabled Drive shift import.
     * Docs/Sheets/Contacts are "sensitive" scopes; [SCOPE_DRIVE_METADATA_READONLY] is "restricted"
     * and only powers the recent-files list — the import degrades to URL paste without it.
     */
    val DRIVE_IMPORT_SCOPES: List<String> = listOf(
        SCOPE_DOCS_READONLY,
        SCOPE_SHEETS_READONLY,
        SCOPE_CONTACTS_READONLY,
        SCOPE_DRIVE_METADATA_READONLY,
    )

    fun scopesFor(driveImport: Boolean): List<String> =
        if (driveImport) OAUTH_SCOPES + DRIVE_IMPORT_SCOPES else OAUTH_SCOPES

    /** Google returns the granted scopes space-separated; blanks and duplicates are common. */
    fun parseGrantedScopes(raw: String?): Set<String> =
        raw.orEmpty().split(' ', '\n', '\t').filter { it.isNotBlank() }.toSet()

    fun buildAuthorizationUrl(
        webClientId: String,
        redirectUri: String,
        promptConsent: Boolean = true,
        forceAccountPicker: Boolean = false,
        driveImport: Boolean = false,
    ): String {
        val scope = scopesFor(driveImport).joinToString(" ")
        val params = linkedMapOf(
            "client_id" to webClientId.trim(),
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to scope,
            "access_type" to "offline",
        )
        when {
            forceAccountPicker -> params["prompt"] = "select_account consent"
            promptConsent -> params["prompt"] = "consent"
        }
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
