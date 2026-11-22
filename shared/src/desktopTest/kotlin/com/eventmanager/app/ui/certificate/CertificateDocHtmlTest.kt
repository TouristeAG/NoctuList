package com.eventmanager.app.ui.certificate

import com.eventmanager.app.portal.certificate.CertAlign
import com.eventmanager.app.portal.certificate.CertParagraph
import com.eventmanager.app.portal.certificate.CertRun
import com.eventmanager.app.portal.certificate.CertSize
import com.eventmanager.app.portal.certificate.CertificateDefaults
import com.eventmanager.app.ui.components.settings.certificate.CertificateDocHtml
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals

class CertificateDocHtmlTest {
    private val everyStyle = listOf(
        CertParagraph(
            align = CertAlign.CENTER,
            size = CertSize.TITLE,
            runs = listOf(CertRun(text = "Titre ", b = true), CertRun(text = "souligné", u = true)),
        ),
        CertParagraph(
            align = CertAlign.RIGHT,
            size = CertSize.SMALL,
            runs = listOf(CertRun(text = "a < b & c", i = true), CertRun(variable = "firstName", b = true, i = true)),
        ),
        CertParagraph(
            align = CertAlign.JUSTIFY,
            size = CertSize.LARGE,
            runs = listOf(
                CertRun(text = "s'est "),
                CertRun(gender = listOf("engagé", "engagée", "engagéx"), u = true),
                CertRun(text = " bénévolement", b = true, i = true, u = true),
            ),
        ),
        CertParagraph(bullet = true, runs = listOf(CertRun(text = "puce un"))),
        CertParagraph(bullet = true, runs = listOf(CertRun(text = "puce deux "), CertRun(variable = "role"))),
        CertParagraph(
            runs = listOf(
                CertRun(variable = "firstName"),
                CertRun(text = " "),
                CertRun(variable = "lastName", b = true),
                CertRun(text = " ", b = true),
                CertRun(gender = listOf("a", "b", "c")),
                CertRun(text = " "),
                CertRun(variable = "role", u = true),
            ),
        ),
        CertParagraph(runs = listOf(CertRun(text = "fin"))),
    )

    private fun throughEditor(doc: List<CertParagraph>): List<CertParagraph> {
        val state = RichTextState()
        state.setHtml(CertificateDocHtml.toHtml(doc))
        return CertificateDocHtml.fromHtml(state.toHtml())
    }

    @Test
    fun roundTripsEveryStyle() {
        assertEquals(everyStyle, CertificateDocHtml.fromHtml(CertificateDocHtml.toHtml(everyStyle)))
    }

    @Test
    fun roundTripsThroughTheEditor() {
        assertEquals(everyStyle, throughEditor(everyStyle))
    }

    @Test
    fun emptyParagraphsAreDropped() {
        val doc = listOf(
            CertParagraph(runs = listOf(CertRun(text = "a"))),
            CertParagraph(align = CertAlign.CENTER, runs = listOf(CertRun())),
            CertParagraph(runs = listOf(CertRun(text = "b"))),
        )
        assertEquals(listOf(doc[0], doc[2]), throughEditor(doc))
    }

    @Test
    fun unknownTagsKeepText() {
        val doc = CertificateDocHtml.fromHtml("""<p><span style="color:red">x</span></p>""")
        assertEquals(listOf(CertParagraph(runs = listOf(CertRun(text = "x")))), doc)
    }

    @Test
    fun defaultsSurviveTheEditor() {
        for (lang in CertificateDefaults.LANGUAGES) {
            val template = CertificateDefaults.template(lang, "Asso")
            for ((key, block) in template.blocks) {
                for ((name, doc) in listOf("one" to block.one, "many" to block.many, "manyOneRole" to block.manyOneRole, "line" to block.line)) {
                    if (doc.isEmpty()) continue
                    // The editor cannot hold an empty paragraph (the optional footer), so it comes back absent.
                    val expected = doc.filter { p -> p.runs.any { it.text.isNotEmpty() || it.variable.isNotEmpty() || it.gender.isNotEmpty() } }
                    assertEquals(expected, throughEditor(doc), "$lang/$key/$name")
                }
            }
        }
    }
}
