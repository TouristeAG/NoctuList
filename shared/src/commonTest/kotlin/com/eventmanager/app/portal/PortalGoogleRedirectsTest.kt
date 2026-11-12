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

    @Test
    fun typedCustomDomainBecomesAnOriginUnlessItIsADefaultSite() {
        assertEquals("https://benevoles.asso.ch", PortalGoogleRedirects.customOrigin("asso-prod", " Benevoles.asso.ch "))
        assertEquals("https://benevoles.asso.ch", PortalGoogleRedirects.customOrigin("asso-prod", "https://benevoles.asso.ch/v/org1"))
        assertEquals(null, PortalGoogleRedirects.customOrigin("asso-prod", "asso-prod.web.app"))
        assertEquals(null, PortalGoogleRedirects.customOrigin("asso-prod", "http://benevoles.asso.ch"))
        assertEquals(null, PortalGoogleRedirects.customOrigin("asso-prod", "pas un domaine"))
        assertEquals(null, PortalGoogleRedirects.customOrigin("asso-prod", ""))
    }

    @Test
    fun consoleLinksNeedValidIds() {
        assertEquals(
            "https://console.cloud.google.com/apis/credentials/oauthclient/123-abc.apps.googleusercontent.com?project=asso-prod",
            PortalGoogleRedirects.oauthClientUrl("asso-prod", "123-abc.apps.googleusercontent.com"),
        )
        assertEquals("", PortalGoogleRedirects.oauthClientUrl("asso-prod", "not-a-client"))
        assertEquals(
            "https://console.firebase.google.com/project/asso-prod/authentication/settings",
            PortalGoogleRedirects.authorizedDomainsUrl("asso-prod"),
        )
        assertEquals("", PortalGoogleRedirects.authorizedDomainsUrl(""))
    }
}
