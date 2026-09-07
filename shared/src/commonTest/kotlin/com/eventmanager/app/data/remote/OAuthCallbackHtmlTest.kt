package com.eventmanager.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthCallbackHtmlTest {
    @Test
    fun successPage_isClearlySuccessfulAndResponsive() {
        val html = OAuthCallbackHtml.page(success = true, logoDataUri = "", languageCode = "en")
        assertTrue(html.contains("Signed in"))
        assertTrue(html.contains("lang=\"en\""))
        assertFalse(html.contains("Connexion réussie"))
        assertTrue(html.contains("viewport-fit=cover"))
        assertTrue(html.contains("min-width: 720px"))
        assertTrue(html.contains("Nunito"))
        assertTrue(html.contains("#b39ddb"))
        assertTrue(html.contains("id=\"topo\""))
        assertTrue(html.contains("getContext(\"webgl\")"))
        assertFalse(html.contains("Switch back to NoctuList"))
        assertFalse(html.contains("Retournez à NoctuList"))
        assertFalse(html.contains("Connexion interrompue"))
    }

    @Test
    fun failurePage_doesNotClaimSuccess() {
        val html = OAuthCallbackHtml.page(success = false, logoDataUri = "", languageCode = "fr")
        assertTrue(html.contains("Connexion interrompue"))
        assertFalse(html.contains("Connexion réussie"))
    }

    @Test
    fun successPage_embedsSanitizedAssociationLogo() {
        val uri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val html = OAuthCallbackHtml.page(success = true, logoDataUri = uri, languageCode = "en")
        assertTrue(html.contains("src=\"$uri\""))
        assertTrue(html.contains("class=\"logo\""))
        assertFalse(html.contains("<p class=\"brand\">NoctuList</p>"))
    }

    @Test
    fun sanitizeLogoDataUri_rejectsNonImagePayloads() {
        assertTrue(OAuthCallbackHtml.sanitizeLogoDataUri("javascript:alert(1)") == null)
        assertTrue(OAuthCallbackHtml.sanitizeLogoDataUri("data:text/html;base64,PHNjcmlwdD4=") == null)
        assertTrue(
            OAuthCallbackHtml.sanitizeLogoDataUri("data:image/png;base64,abc+") != null,
        )
    }

    @Test
    fun page_usesAppLanguageNotBrowser() {
        val fr = OAuthCallbackHtml.page(success = true, logoDataUri = "", languageCode = "fr")
        assertTrue(fr.contains("Connexion réussie"))
        assertTrue(fr.contains("lang=\"fr\""))
        assertFalse(fr.contains("Signed in"))
        assertEquals("fr", OAuthCallbackHtml.normalizeLanguage("fr-CH"))
        assertEquals("zh-TW", OAuthCallbackHtml.normalizeLanguage("zh-Hant-TW"))
    }
}
