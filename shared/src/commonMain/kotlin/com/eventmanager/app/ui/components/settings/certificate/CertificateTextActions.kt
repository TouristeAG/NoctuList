package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.eventmanager.app.portal.certificate.CertAlign
import com.eventmanager.app.portal.certificate.CertSize
import com.mohamedrejeb.richeditor.model.RichTextState

/** What the toolbar does to the active editor. Sizes match [CertificateDocHtml] (12/16/20/28 px). */
object CertificateTextActions {
    fun sizeSp(size: CertSize): TextUnit = when (size) {
        CertSize.SMALL -> 12.sp
        CertSize.NORMAL -> 16.sp
        CertSize.LARGE -> 20.sp
        CertSize.TITLE -> 28.sp
    }

    private val bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val italic = SpanStyle(fontStyle = FontStyle.Italic)
    private val underline = SpanStyle(textDecoration = TextDecoration.Underline)

    fun isBold(state: RichTextState) = state.currentSpanStyle.fontWeight == FontWeight.Bold
    fun isItalic(state: RichTextState) = state.currentSpanStyle.fontStyle == FontStyle.Italic
    fun isUnderline(state: RichTextState) =
        state.currentSpanStyle.textDecoration?.contains(TextDecoration.Underline) == true

    fun toggleBold(state: RichTextState) = state.toggleSpanStyle(bold)
    fun toggleItalic(state: RichTextState) = state.toggleSpanStyle(italic)
    fun toggleUnderline(state: RichTextState) = state.toggleSpanStyle(underline)

    fun align(state: RichTextState): CertAlign = when (state.currentParagraphStyle.textAlign) {
        TextAlign.Center -> CertAlign.CENTER
        TextAlign.Right, TextAlign.End -> CertAlign.RIGHT
        TextAlign.Justify -> CertAlign.JUSTIFY
        else -> CertAlign.LEFT
    }

    fun setAlign(state: RichTextState, align: CertAlign) {
        val textAlign = when (align) {
            CertAlign.LEFT -> TextAlign.Left
            CertAlign.CENTER -> TextAlign.Center
            CertAlign.RIGHT -> TextAlign.Right
            CertAlign.JUSTIFY -> TextAlign.Justify
        }
        state.addParagraphStyle(ParagraphStyle(textAlign = textAlign))
    }

    fun size(state: RichTextState): CertSize {
        val px = state.currentSpanStyle.fontSize.takeIf { it.isSpecified }?.value ?: return CertSize.NORMAL
        return when {
            px <= 14f -> CertSize.SMALL
            px <= 18f -> CertSize.NORMAL
            px <= 24f -> CertSize.LARGE
            else -> CertSize.TITLE
        }
    }

    /** The certificate has one size per paragraph, so the size goes to every paragraph the selection touches. */
    fun setSize(state: RichTextState, size: CertSize) {
        val selection = state.selection
        val length = state.annotatedString.text.length
        // Each range covers one paragraph and the separator that follows it (none after the last one).
        val touched = state.annotatedString.paragraphStyles.filter {
            it.start <= selection.max && (selection.min < it.end || it.end == length) &&
                !(selection.max == it.start && !selection.collapsed && it.start > selection.min)
        }
        val start = touched.minOfOrNull { it.start } ?: selection.min
        val last = touched.maxByOrNull { it.end }
        val end = when {
            last == null -> selection.max
            last.end < length -> last.end - 1
            else -> last.end
        }
        val style = SpanStyle(fontSize = sizeSp(size))
        if (end > start) {
            state.selection = TextRange(start, end)
            state.addSpanStyle(style)
            state.selection = selection
        } else {
            state.addSpanStyle(style)
        }
    }

    fun toggleBullets(state: RichTextState) = state.toggleUnorderedList()

    fun insertVariable(state: RichTextState, key: String) {
        if (!state.selection.collapsed) state.removeSelectedText()
        state.addLink("{$key}", "var:$key")
    }

    /** The selected word when the selection is exactly one word outside any variable, else null. */
    fun selectedWord(state: RichTextState): String? {
        val sel = state.selection
        if (sel.collapsed) return null
        val text = state.annotatedString.text
        if (sel.max > text.length) return null
        val word = text.substring(sel.min, sel.max)
        if (word.isBlank() || word.any { it.isWhitespace() }) return null
        if (word.any { it in "{}/" } || state.isLink) return null
        return word
    }

    /** Replaces the selection by an agreement with the three forms (masculine, feminine, non-binary). */
    fun makeAgreement(state: RichTextState, selection: TextRange, forms: List<String>) {
        state.selection = selection
        state.removeSelectedText()
        val href = forms.joinToString("|") { it.replace("%", "%25").replace("|", "%7C") }
        state.addLink(forms.joinToString("/"), "gender:$href")
    }
}
