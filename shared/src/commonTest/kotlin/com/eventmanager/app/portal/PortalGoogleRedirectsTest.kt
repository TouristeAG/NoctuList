package com.eventmanager.app.portal

import kotlin.test.Test
import kotlin.test.assertEquals

class PortalGoogleRedirectsTest {
    @Test
    fun defaultSitesAndTheCustomPortalDomain() {
        assertEquals(
            listOf(
                "https://asso-prod.web.app/__/auth/handler",
                "https://asso-prod.firebaseapp.com/__/auth/handler",
                "https://benevoles.asso.ch/__/auth/handler",
            ),
            PortalGoogleRedirects.redirectUris("asso-prod", "https://Benevoles.asso.ch/v/org1"),
        )
    }

    @Test
    fun portalOnTheDefaultSiteIsNotListedTwice() {
        assertEquals(
            listOf("https://asso-prod.web.app", "https://asso-prod.firebaseapp.com"),
            PortalGoogleRedirects.origins("asso-prod", "https://asso-prod.web.app/v/org1"),
        )
    }

    @Test
    fun unknownProjectOrPlainHttpGiveOnlyWhatIsKnown() {
        assertEquals(listOf("https://site.example/__/auth/handler"), PortalGoogleRedirects.redirectUris("", "https://site.example/v/x"))
        assertEquals(emptyList(), PortalGoogleRedirects.redirectUris("", "http://127.0.0.1:5002/v/x"))
        assertEquals(emptyList(), PortalGoogleRedirects.redirectUris("Bad Id!", ""))
    }

    @Test
    fun credentialsPageOfTheProject() {
        assertEquals(
            "https://console.cloud.google.com/apis/credentials?project=asso-prod",
            PortalGoogleRedirects.credentialsUrl(" Asso-Prod "),
        )
        assertEquals("", PortalGoogleRedirects.credentialsUrl(""))
    }
}
