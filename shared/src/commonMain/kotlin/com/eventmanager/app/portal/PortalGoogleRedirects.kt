package com.eventmanager.app.portal

/**
 * Addresses the association must register on its Web OAuth client in Google Cloud so the portal's
 * Google sign-in works. The portal uses its own site as Firebase `authDomain`, so Google redirects
 * to `https://<site>/__/auth/handler`; a missing entry shows `redirect_uri_mismatch`.
 */
object PortalGoogleRedirects {
    private const val HANDLER_PATH = "/__/auth/handler"
    private val ORIGIN = Regex("^(https://[^/?#]+)", RegexOption.IGNORE_CASE)
    private val PROJECT_ID = Regex("^[a-z0-9][a-z0-9-]{4,62}$")

    /** `https://host` of the portal address, or null when it is not an https URL. */
    fun originOf(portalUrl: String): String? =
        ORIGIN.find(portalUrl.trim())?.groupValues?.get(1)?.lowercase()

    /** The default Firebase Hosting sites first, then the site the portal is actually served from. */
    fun origins(projectId: String, portalUrl: String): List<String> {
        val id = projectId.trim().lowercase()
        val defaults = if (PROJECT_ID.matches(id)) {
            listOf("https://$id.web.app", "https://$id.firebaseapp.com")
        } else {
            emptyList()
        }
        return (defaults + listOfNotNull(originOf(portalUrl))).distinct()
    }

    fun redirectUris(projectId: String, portalUrl: String): List<String> =
        origins(projectId, portalUrl).map { it + HANDLER_PATH }

    /** Credentials page of the project in Google Cloud, or "" when the project id is unknown. */
    fun credentialsUrl(projectId: String): String {
        val id = projectId.trim().lowercase()
        return if (PROJECT_ID.matches(id)) "https://console.cloud.google.com/apis/credentials?project=$id" else ""
    }
}
