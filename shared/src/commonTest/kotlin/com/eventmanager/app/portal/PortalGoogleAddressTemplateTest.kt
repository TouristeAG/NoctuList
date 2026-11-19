package com.eventmanager.app.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortalGoogleAddressTemplateTest {
    @Test
    fun ownersConvention() = assertEquals(
        "jean-pierre.damico@asso.ch",
        PortalGoogleAddressTemplate.render("{prénom}.{nom}@asso.ch", "Jean Pierre", "D'AMICO"),
    )

    @Test
    fun accentsAndCase() = assertEquals(
        "helene.muller@asso.ch",
        PortalGoogleAddressTemplate.render(" {Prénom}.{NOM}@Asso.ch ", "Hélène", "Müller"),
    )

    @Test
    fun unaccentedAndEnglishPlaceholders() {
        assertEquals("lea.roth@asso.ch", PortalGoogleAddressTemplate.render("{prenom}.{nom}@asso.ch", "Léa", "Roth"))
        assertEquals("lea.roth@asso.ch", PortalGoogleAddressTemplate.render("{first}.{last}@asso.ch", "Léa", "Roth"))
    }

    @Test
    fun initials() {
        assertEquals("j.dupont@asso.ch", PortalGoogleAddressTemplate.render("{p}.{nom}@asso.ch", "Jean", "Dupont"))
        assertEquals("jean.d@asso.ch", PortalGoogleAddressTemplate.render("{prénom}.{n}@asso.ch", "Jean", "Dupont"))
        assertEquals("e.dupont@asso.ch", PortalGoogleAddressTemplate.render("{p}.{nom}@asso.ch", "Émile", "Dupont"))
    }

    @Test
    fun cleaning() {
        assertEquals("jean-pierre", PortalGoogleAddressTemplate.cleanPart("  Jean   Pierre "))
        assertEquals("strasse", PortalGoogleAddressTemplate.cleanPart("Straße"))
        assertEquals("aeo", PortalGoogleAddressTemplate.cleanPart("Æø"))
        assertEquals("anne-marie", PortalGoogleAddressTemplate.cleanPart("Anne_Marie"))
        assertEquals("anne-marie", PortalGoogleAddressTemplate.cleanPart("Anne - Marie"))
        assertEquals("oconnor", PortalGoogleAddressTemplate.cleanPart("O’Connor"))
        assertEquals("francois", PortalGoogleAddressTemplate.cleanPart("FRANÇOIS"))
        assertEquals("", PortalGoogleAddressTemplate.cleanPart("???"))
    }

    @Test
    fun emptyNamePartGivesNoAddress() {
        assertNull(PortalGoogleAddressTemplate.render("{prénom}.{nom}@asso.ch", "Jean", ""))
        assertNull(PortalGoogleAddressTemplate.render("{prénom}.{nom}@asso.ch", "???", "Dupont"))
    }

    @Test
    fun brokenTemplatesGiveNoAddress() {
        assertNull(PortalGoogleAddressTemplate.render("{prénom}.{nom}", "Jean", "Dupont"))
        assertNull(PortalGoogleAddressTemplate.render("{prénom}@a@asso.ch", "Jean", "Dupont"))
        assertNull(PortalGoogleAddressTemplate.render("{prénom}@{nom}.ch", "Jean", "Dupont"))
        assertNull(PortalGoogleAddressTemplate.render("{age}.{nom}@asso.ch", "Jean", "Dupont"))
    }

    @Test
    fun checkTemplate() {
        val t = "{prénom}.{nom}@asso.ch"
        assertEquals(TemplateCheck.OK, PortalGoogleAddressTemplate.check(t, listOf("asso.ch")))
        assertEquals(TemplateCheck.OK, PortalGoogleAddressTemplate.check(t, listOf(" Asso.CH ")))
        assertEquals(TemplateCheck.DOMAIN, PortalGoogleAddressTemplate.check(t, listOf("other.ch")))
        // A guessed address on a public domain could belong to a stranger: always a verified domain.
        assertEquals(TemplateCheck.DOMAIN, PortalGoogleAddressTemplate.check("{prénom}.{nom}@gmail.com", emptyList()))
        assertEquals(TemplateCheck.EMPTY, PortalGoogleAddressTemplate.check("  ", listOf("asso.ch")))
        assertEquals(TemplateCheck.INVALID, PortalGoogleAddressTemplate.check("{prénom}", listOf("asso.ch")))
    }
}
