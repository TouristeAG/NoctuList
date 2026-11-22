package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.eventmanager.app.portal.certificate.CertAlign
import com.eventmanager.app.portal.certificate.CertAssembly
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.CertSize
import com.eventmanager.app.portal.certificate.ResolvedParagraph
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.nunito_bold
import com.eventmanager.app.resources.nunito_regular
import com.eventmanager.app.resources.nunito_regular_italic
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Layout of the certificate PDF (webform/functions/src/certificatePdf.ts), in points. The preview
 * scales them to its width so it reads like the PDF. Pages break between paragraphs here, where the
 * PDF can also break inside one.
 */
internal object CertificatePageMetrics {
    const val PAGE_W = 595.28f
    const val PAGE_H = 841.89f
    const val MARGIN = 70.87f
    const val LEADING = 1.5f
    const val PARAGRAPH_GAP = 1.1f
    const val TITLE_GAP_AFTER = 28f
    const val DATE_GAP_BEFORE = 24f
    const val LOGO_GAP = 14f
    const val RULE_HEIGHT = 50f
    const val SIGNATURE_GAP = 44f
    const val BULLET_INDENT = 14f
    const val FOOTER_BASE = 38f
    const val FOOTER_SPACE = 18f
    const val LOGO_MAX_W = 200f
    const val LOGO_MAX_H = 48f
    const val SIGNATURE_MAX_W = 120f
    const val SIGNATURE_MAX_H = 60f

    fun sizePt(size: CertSize) = when (size) {
        CertSize.SMALL -> 9f
        CertSize.NORMAL -> 11f
        CertSize.LARGE -> 14f
        CertSize.TITLE -> 22f
    }
}

/** One piece of the page, with the space above it in points (dropped at the top of a page). */
internal sealed class PreviewItem(val gapBefore: Float) {
    class Logo(val align: CertAlign, gapBefore: Float) : PreviewItem(gapBefore)
    class Paragraph(val paragraph: ResolvedParagraph, gapBefore: Float) : PreviewItem(gapBefore)
    class Rule : PreviewItem(0f)
    class Signature(val name: String, val title: String, gapBefore: Float) : PreviewItem(gapBefore)
}

internal object CertificatePreviewItems {
    /** The same order and spacing as the PDF renderer. */
    fun build(assembly: CertAssembly, hasLogo: Boolean, hasSignatureImage: Boolean, signer: CertSigner): List<PreviewItem> {
        val m = CertificatePageMetrics
        val body = assembly.body
        val titleAt = body.indexOfFirst { it.size == CertSize.TITLE }
        val headerCount = if (titleAt > 0) titleAt else 0
        val out = mutableListOf<PreviewItem>()
        if (hasLogo) out += PreviewItem.Logo(body.firstOrNull()?.align ?: CertAlign.LEFT, 0f)

        fun gaps(paragraphs: List<ResolvedParagraph>): List<Float> = paragraphs.mapIndexed { index, p ->
            if (index == 0) 0f else {
                val prevTitle = paragraphs[index - 1].size == CertSize.TITLE
                maxOf(m.sizePt(p.size) * m.PARAGRAPH_GAP, if (prevTitle) m.TITLE_GAP_AFTER else 0f)
            }
        }

        val header = body.take(headerCount)
        gaps(header).forEachIndexed { k, gap ->
            out += PreviewItem.Paragraph(header[k], if (k == 0 && hasLogo) m.LOGO_GAP else gap)
        }
        if (headerCount > 0) out += PreviewItem.Rule()
        val rest = body.drop(headerCount)
        val restGaps = gaps(rest)
        rest.forEachIndexed { k, p ->
            // The last paragraph (the date line) is set apart from the text above it.
            val gap = if (k == rest.lastIndex && k > 0) maxOf(restGaps[k], m.DATE_GAP_BEFORE) else restGaps[k]
            out += PreviewItem.Paragraph(p, gap)
        }
        val name = signer.name.trim()
        val title = signer.title.trim()
        if (hasSignatureImage || name.isNotEmpty() || title.isNotEmpty()) {
            out += PreviewItem.Signature(name, title, m.SIGNATURE_GAP)
        }
        return out
    }
}

private val INK = Color(0xFF1A1A1F)
private val GREY = Color(0xFF73737A)
private val RULE = Color(0xFFBFBFC7)

@OptIn(ExperimentalResourceApi::class)
internal fun decodeBase64Image(base64: String): ImageBitmap? {
    val raw = base64.trim().substringAfter("base64,")
    if (raw.isEmpty()) return null
    return try {
        java.util.Base64.getMimeDecoder().decode(raw).decodeToImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/** An A4 page (or several) drawn like the PDF the volunteer receives. */
@Composable
fun CertificatePreview(assembly: CertAssembly, logoBase64: String, signer: CertSigner, modifier: Modifier = Modifier) {
    val logo = remember(logoBase64) { decodeBase64Image(logoBase64) }
    val signature = remember(signer.imageBase64) { decodeBase64Image(signer.imageBase64) }
    val items = remember(assembly, logo != null, signature != null, signer.name, signer.title) {
        CertificatePreviewItems.build(assembly, logo != null, signature != null, signer)
    }
    val family = FontFamily(
        Font(Res.font.nunito_regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.nunito_regular_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.nunito_bold, FontWeight.Bold, FontStyle.Normal),
    )
    val m = CertificatePageMetrics

    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val pageW = constraints.maxWidth
        val pxPerPt = pageW / m.PAGE_W
        val pageH = (m.PAGE_H * pxPerPt).toInt()
        val margin = (m.MARGIN * pxPerPt).toInt()
        val textW = pageW - 2 * margin
        val pageGap = 16.dp.roundToPx()
        val scale = PreviewScale(pxPerPt, this)
        val textConstraints = Constraints(maxWidth = textW)

        val footer = subcompose("footer-size") {
            PreviewFooter(assembly.footer, family, scale)
        }.map { it.measure(textConstraints) }
        val footerH = footer.sumOf { it.height }
        val bottomLimit = (m.FOOTER_BASE * pxPerPt).toInt() + footerH +
            if (assembly.footer.isNotEmpty()) (m.FOOTER_SPACE * pxPerPt).toInt() else 0

        val placeables = subcompose("items") {
            for (item in items) PreviewItemContent(item, family, scale, logo, signature)
        }.map { it.measure(textConstraints) }

        // Page index and top of each item inside its page.
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        var page = 0
        var y = margin
        placeables.forEachIndexed { i, placeable ->
            var gap = if (y == margin) 0 else (items[i].gapBefore * pxPerPt).toInt()
            if (y + gap + placeable.height > pageH - bottomLimit && y > margin) {
                page++
                y = margin
                gap = 0
            }
            positions += page to (y + gap)
            y += gap + placeable.height
        }
        val pages = page + 1

        val backgrounds = subcompose("pages") {
            repeat(pages) {
                Box(
                    Modifier
                        .size(pageW.toDp(), pageH.toDp())
                        .background(Color.White)
                        .border(1.dp, Color(0x1F000000)),
                )
            }
        }.map { it.measure(Constraints.fixed(pageW, pageH)) }
        val footers = (0 until pages).map { index ->
            subcompose("footer-$index") { PreviewFooter(assembly.footer, family, scale) }
                .map { it.measure(textConstraints) }
        }

        val height = pages * pageH + (pages - 1) * pageGap
        layout(pageW, height) {
            backgrounds.forEachIndexed { index, bg -> bg.place(0, index * (pageH + pageGap)) }
            footers.forEachIndexed { index, list ->
                var fy = index * (pageH + pageGap) + pageH - (m.FOOTER_BASE * pxPerPt).toInt() - footerH
                for (p in list) {
                    p.place(margin, fy)
                    fy += p.height
                }
            }
            placeables.forEachIndexed { i, placeable ->
                val (pageIndex, top) = positions[i]
                placeable.place(margin, pageIndex * (pageH + pageGap) + top)
            }
        }
    }
}

/** Converts PDF points to the preview's pixels and text units. */
private class PreviewScale(val pxPerPt: Float, density: Density) : Density by density {
    fun dp(pt: Float) = (pt * pxPerPt).toDp()
    fun sp(pt: Float) = (pt * pxPerPt).toSp()
}

@Composable
private fun PreviewItemContent(
    item: PreviewItem,
    family: FontFamily,
    scale: PreviewScale,
    logo: ImageBitmap?,
    signature: ImageBitmap?,
) {
    val m = CertificatePageMetrics
    when (item) {
        is PreviewItem.Logo -> if (logo != null) {
            val k = minOf(m.LOGO_MAX_H / logo.height, m.LOGO_MAX_W / logo.width)
            Box(Modifier.fillMaxWidth(), contentAlignment = boxAlignment(item.align)) {
                Image(
                    logo,
                    contentDescription = null,
                    modifier = Modifier.size(scale.dp(logo.width * k), scale.dp(logo.height * k)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        is PreviewItem.Paragraph -> PreviewParagraph(item.paragraph, family, scale, INK)
        is PreviewItem.Rule -> Box(Modifier.fillMaxWidth().height(scale.dp(m.RULE_HEIGHT)), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(scale.dp(0.5f).coerceAtLeast(1.dp)).background(RULE))
        }
        is PreviewItem.Signature -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (signature != null) {
                val k = minOf(m.SIGNATURE_MAX_W / signature.width, m.SIGNATURE_MAX_H / signature.height)
                Image(
                    signature,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(bottom = scale.dp(4f))
                        .size(scale.dp(signature.width * k), scale.dp(signature.height * k)),
                    contentScale = ContentScale.Fit,
                )
            }
            val style = textStyle(family, scale, CertSize.NORMAL, TextAlign.End, INK)
            if (item.name.isNotEmpty()) Text(item.name, style = style.copy(fontWeight = FontWeight.Bold))
            if (item.title.isNotEmpty()) Text(item.title, style = style)
        }
    }
}

@Composable
private fun PreviewFooter(footer: List<ResolvedParagraph>, family: FontFamily, scale: PreviewScale) {
    for (p in footer) PreviewParagraph(p.copy(size = CertSize.SMALL), family, scale, GREY)
}

@Composable
private fun PreviewParagraph(p: ResolvedParagraph, family: FontFamily, scale: PreviewScale, color: Color) {
    val align = when (p.align) {
        CertAlign.LEFT -> TextAlign.Start
        CertAlign.CENTER -> TextAlign.Center
        CertAlign.RIGHT -> TextAlign.End
        CertAlign.JUSTIFY -> TextAlign.Justify
    }
    val style = textStyle(family, scale, p.size, align, color)
    val text = annotated(p)
    if (p.bullet) {
        Row(Modifier.fillMaxWidth()) {
            Text("•", style = style.copy(textAlign = TextAlign.Start), modifier = Modifier.width(scale.dp(CertificatePageMetrics.BULLET_INDENT)))
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
    } else {
        Text(text, style = style, modifier = Modifier.fillMaxWidth())
    }
}

private fun textStyle(family: FontFamily, scale: PreviewScale, size: CertSize, align: TextAlign, color: Color): TextStyle {
    val pt = CertificatePageMetrics.sizePt(size)
    return TextStyle(
        fontFamily = family,
        fontSize = scale.sp(pt),
        lineHeight = scale.sp(pt * CertificatePageMetrics.LEADING),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textAlign = align,
        color = color,
    )
}

private fun annotated(p: ResolvedParagraph): AnnotatedString = buildAnnotatedString {
    for (run in p.runs) {
        withStyle(
            SpanStyle(
                fontWeight = if (run.b) FontWeight.Bold else null,
                fontStyle = if (run.i) FontStyle.Italic else null,
                textDecoration = if (run.u) TextDecoration.Underline else null,
            ),
        ) { append(run.text) }
    }
}

private fun boxAlignment(align: CertAlign) = when (align) {
    CertAlign.CENTER -> Alignment.Center
    CertAlign.RIGHT -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}
