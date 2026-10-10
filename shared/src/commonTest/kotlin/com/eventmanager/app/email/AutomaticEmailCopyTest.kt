package com.eventmanager.app.email

import kotlin.test.Test
import kotlin.test.assertEquals

class AutomaticEmailCopyTest {
    @Test
    fun blankTitleKeepsTheLocalizedDefault() {
        assertEquals("Guestlist artiste", AutomaticEmailCopy.resolveTitle("  ", "Guestlist artiste"))
    }

    @Test
    fun customTitleReplacesTheDefault() {
        assertEquals(
            "Guestlist institution",
            AutomaticEmailCopy.resolveTitle(" Guestlist institution ", "Guestlist artiste"),
        )
    }

    @Test
    fun blankSignatureUsesTheAssociationName() {
        assertEquals(
            "Maison des arts",
            AutomaticEmailCopy.resolveSignature("", "Maison des arts", "Collectif Nocturne"),
        )
    }

    @Test
    fun customSignatureWinsOverTheAssociationName() {
        assertEquals(
            "L'équipe",
            AutomaticEmailCopy.resolveSignature(" L'équipe ", "Maison des arts", "Collectif Nocturne"),
        )
    }

    @Test
    fun blankAssociationFallsBackToTheLocalizedSignature() {
        assertEquals(
            "Collectif Nocturne",
            AutomaticEmailCopy.resolveSignature("  ", "  ", "Collectif Nocturne"),
        )
    }

    @Test
    fun legacyCollectifNocturneSignatureYieldsToACustomAssociationName() {
        assertEquals(
            "Maison des arts",
            AutomaticEmailCopy.resolveSignature(
                "Collectif Nocturne",
                "Maison des arts",
                "Collectif Nocturne",
            ),
        )
    }
}
