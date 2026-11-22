package com.eventmanager.app.ui.certificate

import androidx.compose.ui.text.TextRange
import com.eventmanager.app.portal.certificate.CertAlign
import com.eventmanager.app.portal.certificate.CertAssembly
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.CertSize
import com.eventmanager.app.portal.certificate.CertificateDefaults
import com.eventmanager.app.portal.certificate.ResolvedParagraph
import com.eventmanager.app.portal.certificate.ResolvedRun
import com.eventmanager.app.ui.components.settings.certificate.CertVariant
import com.eventmanager.app.ui.components.settings.certificate.CertificateEditorModel
import com.eventmanager.app.ui.components.settings.certificate.CertificatePreviewItems
import com.eventmanager.app.ui.components.settings.certificate.CertificateSampleData
import com.eventmanager.app.ui.components.settings.certificate.CertificateTextActions
import com.eventmanager.app.ui.components.settings.certificate.PreviewItem
import com.eventmanager.app.ui.components.settings.certificate.certificatePreviewAssembly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificateEditorModelTest {
    private val defaults = CertificateDefaults.template("fr", "Noctua").copy(place = "Genève")

    @Test
    fun defaultsComeBackUnchangedThroughTheEditor() {
        val back = CertificateEditorModel(defaults, "Noctua").toTemplate()
        val expected = defaults.copy(blocks = defaults.blocks + ("footer" to defaults.blocks.getValue("footer").copy(one = emptyList())))
        assertEquals(expected, back)
        assertTrue(back.blocks.getValue("footer").one.isEmpty())
    }

    @Test
    fun insertedVariableAndAgreementReachTheTemplate() {
        val model = CertificateEditorModel(defaults, "Noctua")
        val state = model.editor("closing", CertVariant.ONE)
        val text = state.annotatedString.text
        state.selection = TextRange(text.length)
        CertificateTextActions.insertVariable(state, "period")
        val word = "chaleureusement"
        val at = state.annotatedString.text.indexOf(word)
        state.selection = TextRange(at, at + word.length)
        assertEquals(word, CertificateTextActions.selectedWord(state))
        CertificateTextActions.makeAgreement(state, state.selection, listOf("a", "b", "c"))
        val runs = model.toTemplate().blocks.getValue("closing").one.single().runs
        assertTrue(runs.any { it.variable == "period" })
        assertTrue(runs.any { it.gender == listOf("a", "b", "c") })
        assertTrue(runs.none { it.text.contains(word) })
    }

    @Test
    fun sizeAndAlignmentApplyToTheWholeParagraphOnly() {
        val model = CertificateEditorModel(defaults, "Noctua")
        val state = model.editor("SHIFT", CertVariant.ONE)
        state.selection = TextRange(3)
        CertificateTextActions.setSize(state, CertSize.LARGE)
        CertificateTextActions.setAlign(state, CertAlign.JUSTIFY)
        CertificateTextActions.toggleBold(state)
        val paragraphs = model.toTemplate().blocks.getValue("SHIFT").one
        assertEquals(CertSize.LARGE, paragraphs[0].size)
        assertEquals(CertAlign.JUSTIFY, paragraphs[0].align)
        assertEquals(CertSize.NORMAL, paragraphs[1].size)
        assertEquals(CertAlign.LEFT, paragraphs[1].align)
    }

    @Test
    fun aWordSelectionIsNeededForAnAgreement() {
        val state = CertificateEditorModel(defaults, "Noctua").editor("closing", CertVariant.ONE)
        state.selection = TextRange(2)
        assertNull(CertificateTextActions.selectedWord(state))
        state.selection = TextRange(0, 14)
        assertNull(CertificateTextActions.selectedWord(state))
    }

    @Test
    fun defaultTextsFollowTheNewLanguageAndKeepThePlace() {
        val model = CertificateEditorModel(defaults, "Noctua")
        model.language = "de"
        model.loadDefaultTexts()
        val t = model.toTemplate()
        assertEquals("de", t.language)
        assertEquals("Genève", t.place)
        assertEquals(CertificateDefaults.template("de", "Noctua").mail.subject, t.mail.subject)
    }

    @Test
    fun sampleHasAMandateShiftsInTwoRolesAndAMeeting() {
        val (person, types, jobs) = CertificateSampleData.sample("fr")
        assertEquals("FEMALE", person.gender)
        assertEquals(8, jobs.count { j -> types.first { it.name == j.jobTypeName }.category.name == "SHIFT" })
        val assembly = assertNotNull(certificatePreviewAssembly(defaults, "Noctua", null, emptyList(), { emptyList() }))
        val text = assembly.body.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        assertTrue("Léa Exemple s'est engagée" in text, text)
        assertTrue("8 événements" in text, text)
        assertTrue("réunion" in text, text)
    }

    @Test
    fun previewFollowsThePdfOrderAndGaps() {
        fun p(text: String, size: CertSize = CertSize.NORMAL) =
            ResolvedParagraph(CertAlign.LEFT, size, false, listOf(ResolvedRun(text, false, false, false)))
        val assembly = CertAssembly(
            body = listOf(p("Asso", CertSize.LARGE), p("Titre", CertSize.TITLE), p("Intro"), p("Merci"), p("Fait à")),
            footer = emptyList(),
        )
        val items = CertificatePreviewItems.build(assembly, hasLogo = true, hasSignatureImage = false, signer = CertSigner(name = "Marie"))
        assertTrue(items[0] is PreviewItem.Logo)
        assertEquals(14f, items[1].gapBefore)
        assertTrue(items[2] is PreviewItem.Rule)
        assertEquals(0f, items[3].gapBefore)
        assertEquals(28f, items[4].gapBefore)
        assertEquals(24f, items[6].gapBefore)
        assertTrue(items[7] is PreviewItem.Signature)
        assertEquals(44f, items[7].gapBefore)
    }
}
