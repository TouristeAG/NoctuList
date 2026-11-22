package com.eventmanager.app.portal.certificate

import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificateTemplateTest {

    private val blockKeys = listOf(
        "header", "title", "intro", "MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT",
        "GRAPHIC_ASSOCIATION", "OTHER", "MEETING", "closing", "dateLine", "footer",
    )

    @Test
    fun roundTripsJson() {
        val template = CertificateDefaults.template("fr", "Les Noctambules")
        val json = template.toJson()
        assertTrue(json.contains("\"var\":\"firstName\""))
        assertEquals(template, CertificateTemplate.parse(json))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(CertificateTemplate.parse("{"))
        assertNull(CertificateTemplate.parse(""))
    }

    @Test
    fun parseIgnoresUnknownKeys() {
        val parsed = CertificateTemplate.parse("""{"language":"de","extra":1}""")
        assertEquals("de", parsed?.language)
    }

    private fun config(
        shift: Boolean = true,
        orion: Boolean = false,
        nova: NovaJobType = NovaJobType.DEFAULT_SHIFT,
    ) = JobTypeConfig(name = "x", isShiftJob = shift, isOrionJob = orion, novaJobType = nova)

    @Test
    fun categoryRules() {
        assertEquals(CertificateCategory.MANDATE, certificateCategoryOf(config(shift = true, orion = true)))
        assertEquals(CertificateCategory.SHIFT, certificateCategoryOf(config()))
        assertEquals(CertificateCategory.MEETING, certificateCategoryOf(config(nova = NovaJobType.MEETING)))
        assertEquals(
            CertificateCategory.PHOTO,
            certificateCategoryOf(config(nova = NovaJobType.PHOTOGRAPHER_VIDEOGRAPHER)),
        )
        assertEquals(
            CertificateCategory.GRAPHIC_EVENT,
            certificateCategoryOf(config(nova = NovaJobType.GRAPHIC_DESIGNER_EVENT)),
        )
        assertEquals(
            CertificateCategory.GRAPHIC_ASSOCIATION,
            certificateCategoryOf(config(nova = NovaJobType.GRAPHIC_DESIGNER_ASSOCIATION)),
        )
        assertEquals(CertificateCategory.OTHER, certificateCategoryOf(config(shift = false)))
        assertEquals(CertificateCategory.OTHER, certificateCategoryOf(null))
    }

    @Test
    fun categoriesAreInDocumentOrder() {
        assertEquals(
            listOf("MANDATE", "SHIFT", "PHOTO", "GRAPHIC_EVENT", "GRAPHIC_ASSOCIATION", "OTHER", "MEETING"),
            CertificateCategory.entries.map { it.name },
        )
    }

    @Test
    fun everyLanguageHasEveryBlock() {
        for (lang in CertificateDefaults.LANGUAGES) {
            val t = CertificateDefaults.template(lang, "Les Noctambules")
            assertEquals(lang, t.language)
            for (key in blockKeys) assertTrue(t.blocks.containsKey(key), "$lang missing $key")
            for (key in listOf("SHIFT", "OTHER")) {
                val b = t.blocks.getValue(key)
                assertTrue(b.one.isNotEmpty() && b.many.isNotEmpty(), "$lang $key one/many")
                assertTrue(b.manyOneRole.isNotEmpty() && b.line.isNotEmpty(), "$lang $key manyOneRole/line")
                val last = b.many.last().runs
                assertEquals(1, last.size, "$lang $key many ends with roles only")
                assertEquals("roles", last.single().variable)
            }
            for (key in listOf("MANDATE", "GRAPHIC_ASSOCIATION", "header", "title", "closing", "dateLine", "footer")) {
                assertTrue(t.blocks.getValue(key).one.isNotEmpty(), "$lang $key one")
            }
            assertTrue(t.mail.subject.isNotBlank() && t.mail.body.isNotBlank())
            assertEquals("Les Noctambules", t.mail.fromName)
        }
    }

    @Test
    fun defaultsUseNoEmDashAndUnknownLanguageFallsBackToEnglish() {
        for (lang in CertificateDefaults.LANGUAGES) {
            assertTrue(!CertificateDefaults.template(lang, "A").toJson().contains('—'))
        }
        assertEquals("en", CertificateDefaults.template("xx", "A").language)
    }

    @Test
    fun frenchIntroAgreesWithGender() {
        val intro = CertificateDefaults.template("fr", "A").blocks.getValue("intro").many
        val gender = intro.flatMap { it.runs }.first { it.gender.isNotEmpty() }.gender
        assertEquals(listOf("engagé", "engagée", "engagéx"), gender)
    }

    @Test
    fun dates() {
        assertEquals("12 mars 2026", CertificateDates.longDate(20260312, "fr"))
        assertEquals("1er mars 2026", CertificateDates.longDate(20260301, "fr"))
        assertEquals("12 March 2026", CertificateDates.longDate(20260312, "en"))
        assertEquals("12. März 2026", CertificateDates.longDate(20260312, "de"))
        assertEquals("12 marzo 2026", CertificateDates.longDate(20260312, "it"))
        assertEquals("12 de marzo de 2026", CertificateDates.longDate(20260312, "es"))
        assertEquals("mars 2026", CertificateDates.monthYear(20260312, "fr"))
        assertEquals("marzo de 2026", CertificateDates.monthYear(20260312, "es"))
    }

    @Test
    fun periods() {
        assertEquals("entre mars et octobre 2026", CertificateDates.period(20260305, 20261020, "fr"))
        assertEquals("entre avril 2025 et août 2026", CertificateDates.period(20250405, 20260820, "fr"))
        assertEquals("en mars 2026", CertificateDates.period(20260302, 20260328, "fr"))
        assertEquals("between March and October 2026", CertificateDates.period(20260305, 20261020, "en"))
        assertEquals("im März 2026", CertificateDates.period(20260302, 20260328, "de"))
        assertEquals("entre marzo y octubre de 2026", CertificateDates.period(20260305, 20261020, "es"))
        assertEquals("a marzo 2026", CertificateDates.period(20260302, 20260328, "it"))
        assertEquals("entre abril de 2025 y agosto de 2026", CertificateDates.period(20250405, 20260820, "es"))
    }

    @Test
    fun dayOfUsesGeneva() {
        // 2026-03-12T23:30:00Z is 00:30 on the 13th in Europe/Zurich (UTC+1).
        assertEquals(20260313, CertificateDates.dayOf(1773358200000L))
    }
}
