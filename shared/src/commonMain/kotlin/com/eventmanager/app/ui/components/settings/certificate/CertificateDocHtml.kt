package com.eventmanager.app.ui.components.settings.certificate

import com.eventmanager.app.portal.certificate.CertAlign
import com.eventmanager.app.portal.certificate.CertParagraph
import com.eventmanager.app.portal.certificate.CertRun
import com.eventmanager.app.portal.certificate.CertSize

/**
 * Converts the certificate text model to and from the HTML used by the rich text editor.
 *
 * Variables and gender agreements are links: `var:firstName` and `gender:m|f|nb`. Anything the
 * model cannot express (colors, fonts, other tags) keeps its text and loses its style. Empty
 * paragraphs are not written, because the editor would drop them anyway.
 */
object CertificateDocHtml {
    private const val VAR_PREFIX = "var:"
    private const val GENDER_PREFIX = "gender:"

    private fun sizePx(size: CertSize) = when (size) {
        CertSize.SMALL -> 12
        CertSize.NORMAL -> 16
        CertSize.LARGE -> 20
        CertSize.TITLE -> 28
    }

    private fun sizeFromPx(px: Double) = when {
        px <= 14.0 -> CertSize.SMALL
        px <= 18.0 -> CertSize.NORMAL
        px <= 24.0 -> CertSize.LARGE
        else -> CertSize.TITLE
    }

    fun toHtml(doc: List<CertParagraph>): String {
        val out = StringBuilder()
        var inList = false
        for (p in doc) {
            // The editor cannot keep an empty paragraph (it drops it and shifts the style onto the next one).
            if (p.runs.all { it.text.isEmpty() && it.variable.isEmpty() && it.gender.isEmpty() }) continue
            if (p.bullet && !inList) out.append("<ul>")
            if (!p.bullet && inList) out.append("</ul>")
            inList = p.bullet
            val tag = if (p.bullet) "li" else "p"
            val align = p.align.name.lowercase()
            out.append("<$tag style=\"text-align:$align\">")
            var skip = -1
            for ((index, run) in p.runs.withIndex()) {
                if (index == skip) continue
                // The editor drops a text made only of spaces, so one that follows a link rides inside the link text (and takes the link's style).
                val next = p.runs.getOrNull(index + 1)
                val isLink = run.variable.isNotEmpty() || run.gender.isNotEmpty()
                val carried = if (isLink && next != null && next.variable.isEmpty() && next.gender.isEmpty() &&
                    next.text.isNotEmpty() && next.text.isBlank()
                ) next.text else ""
                if (carried.isNotEmpty()) skip = index + 1
                out.append(runHtml(run, p.size, carried))
            }
            out.append("</$tag>")
        }
        if (inList) out.append("</ul>")
        return out.toString()
    }

    private fun runHtml(run: CertRun, size: CertSize, carried: String): String {
        val inner = when {
            run.variable.isNotEmpty() ->
                "<a href=\"$VAR_PREFIX${escape(run.variable)}\">${escape("{${run.variable}}")}$carried</a>"
            run.gender.isNotEmpty() -> {
                val href = run.gender.joinToString("|") { it.replace("%", "%25").replace("|", "%7C") }
                "<a href=\"$GENDER_PREFIX${escape(href)}\">${escape(run.gender.joinToString("/"))}$carried</a>"
            }
            else -> escape(run.text)
        }
        if (inner.isEmpty()) return ""
        var s = "<span style=\"font-size:${sizePx(size)}px\">$inner</span>"
        if (run.u) s = "<u>$s</u>"
        if (run.i) s = "<i>$s</i>"
        if (run.b) s = "<b>$s</b>"
        return s
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- parsing -------------------------------------------------------------------------

    private class Style(
        val b: Boolean = false,
        val i: Boolean = false,
        val u: Boolean = false,
        val px: Double? = null,
        val href: String? = null,
    )

    private class Frame(val tag: String, val style: Style)

    private class Builder {
        val paragraphs = mutableListOf<CertParagraph>()
        var open = false
        var bullet = false
        var align = CertAlign.LEFT
        var blockPx: Double? = null
        var firstPx: Double? = null
        val runs = mutableListOf<CertRun>()
        var linkRun: CertRun? = null
        var linkText = StringBuilder()
        var linkHref: String? = null

        fun begin(bullet: Boolean, align: CertAlign, px: Double?) {
            finish()
            open = true
            this.bullet = bullet
            this.align = align
            blockPx = px
        }

        fun addText(text: String, style: Style) {
            if (text.isEmpty()) return
            if (!open) begin(false, CertAlign.LEFT, null)
            if (firstPx == null) firstPx = style.px
            if (style.href != null) {
                if (linkHref != style.href) {
                    flushLink()
                    linkHref = style.href
                    linkRun = CertRun(b = style.b, i = style.i, u = style.u)
                }
                linkText.append(text)
                return
            }
            flushLink()
            val last = runs.lastOrNull()
            if (last != null && last.variable.isEmpty() && last.gender.isEmpty() &&
                last.b == style.b && last.i == style.i && last.u == style.u
            ) {
                runs[runs.lastIndex] = last.copy(text = last.text + text)
            } else {
                runs += CertRun(text = text, b = style.b, i = style.i, u = style.u)
            }
        }

        fun flushLink() {
            val href = linkHref ?: return
            val base = linkRun ?: CertRun()
            val shown = linkText.toString()
            linkHref = null
            linkRun = null
            linkText = StringBuilder()
            val forms = if (href.startsWith(GENDER_PREFIX)) {
                href.removePrefix(GENDER_PREFIX).split("|").map { it.replace("%7C", "|").replace("%7c", "|").replace("%25", "%") }
            } else {
                emptyList()
            }
            val link: CertRun?
            val label: String
            when {
                href.startsWith(VAR_PREFIX) && href.length > VAR_PREFIX.length -> {
                    val key = href.removePrefix(VAR_PREFIX)
                    link = base.copy(variable = key)
                    label = "{$key}"
                }
                href.startsWith(GENDER_PREFIX) && forms.size == 3 -> {
                    link = base.copy(gender = forms)
                    label = forms.joinToString("/")
                }
                else -> {
                    appendText(shown, base)
                    return
                }
            }
            // Text typed next to the label ends up inside the link: it is kept as plain text around it.
            val at = shown.indexOf(label)
            if (at < 0) {
                runs += link
                appendText(shown, base)
            } else {
                appendText(shown.substring(0, at), base)
                runs += link
                appendText(shown.substring(at + label.length), base)
            }
        }

        private fun appendText(text: String, base: CertRun) {
            if (text.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && last.variable.isEmpty() && last.gender.isEmpty() &&
                last.b == base.b && last.i == base.i && last.u == base.u
            ) {
                runs[runs.lastIndex] = last.copy(text = last.text + text)
            } else {
                runs += CertRun(text = text, b = base.b, i = base.i, u = base.u)
            }
        }

        fun finish() {
            flushLink()
            if (!open) return
            open = false
            val px = blockPx ?: firstPx
            // An empty paragraph is not kept: the editor loses it and the PDF skips it.
            if (runs.all { it.text.isEmpty() && it.variable.isEmpty() && it.gender.isEmpty() }) {
                runs.clear()
                firstPx = null
                blockPx = null
                return
            }
            paragraphs += CertParagraph(
                align = align,
                size = if (px == null) CertSize.NORMAL else sizeFromPx(px),
                bullet = bullet,
                runs = runs.toList(),
            )
            runs.clear()
            firstPx = null
            blockPx = null
        }
    }

    private val blockTags = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6")
    private val tagRegex = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(/?)>""")
    private val attrRegex = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")

    private fun attrs(raw: String): Map<String, String> =
        attrRegex.findAll(raw).associate { m ->
            m.groupValues[1].lowercase() to unescape(m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: "")
        }

    private fun declarations(style: String?): Map<String, String> =
        style.orEmpty().split(";").mapNotNull {
            val i = it.indexOf(':')
            if (i < 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim().lowercase()
        }.toMap()

    private fun fontPx(value: String?): Double? {
        val v = value?.trim()?.lowercase() ?: return null
        val number = Regex("""^([0-9]*\.?[0-9]+)\s*(px|pt|sp|em|rem)?$""").find(v) ?: return null
        val n = number.groupValues[1].toDoubleOrNull() ?: return null
        return when (number.groupValues[2]) {
            "pt" -> n * 4.0 / 3.0
            "em", "rem" -> n * 16.0
            else -> n
        }
    }

    private fun alignOf(value: String?) = when (value?.trim()?.lowercase()) {
        "center" -> CertAlign.CENTER
        "right", "end" -> CertAlign.RIGHT
        "justify" -> CertAlign.JUSTIFY
        else -> CertAlign.LEFT
    }

    fun fromHtml(html: String): List<CertParagraph> {
        val builder = Builder()
        val stack = ArrayList<Frame>()
        var listDepth = 0
        var skipDepth = 0
        fun current() = stack.lastOrNull()?.style ?: Style()

        fun text(raw: String) {
            if (skipDepth > 0) return
            val t = unescape(raw)
            if (!builder.open && t.isBlank()) return

            builder.addText(t.replace("\r", "").replace("\n", " "), current())
        }

        var pos = 0
        for (m in tagRegex.findAll(html)) {
            if (m.range.first > pos) text(html.substring(pos, m.range.first))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val selfClosing = m.groupValues[4] == "/"
            if (tag == "script" || tag == "style" || tag == "head") {
                if (closing) skipDepth = maxOf(0, skipDepth - 1) else if (!selfClosing) skipDepth++
                continue
            }
            if (skipDepth > 0) continue
            if (closing) {
                if (tag == "ul" || tag == "ol") listDepth = maxOf(0, listDepth - 1)
                if (tag in blockTags) builder.finish()
                val idx = stack.indexOfLast { it.tag == tag }
                if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.lastIndex)
                continue
            }
            val a = attrs(m.groupValues[3])
            val css = declarations(a["style"])
            val parent = current()
            when (tag) {
                "br" -> {
                    if (builder.open) {
                        val bullet = builder.bullet
                        val align = builder.align
                        val px = builder.blockPx
                        builder.finish()
                        builder.begin(bullet, align, px)
                    }
                    continue
                }
                "ul", "ol" -> {
                    builder.finish()
                    listDepth++
                }
                in blockTags -> builder.begin(tag == "li", alignOf(css["text-align"] ?: a["align"]), fontPx(css["font-size"]))
            }
            if (selfClosing) continue
            val weight = css["font-weight"]
            val decoration = css["text-decoration"] ?: css["text-decoration-line"]
            val style = Style(
                b = parent.b || tag == "b" || tag == "strong" ||
                    (weight != null && (weight == "bold" || (weight.toIntOrNull() ?: 0) >= 600)),
                i = parent.i || tag == "i" || tag == "em" || css["font-style"] == "italic",
                u = parent.u || tag == "u" || (decoration?.contains("underline") == true),
                px = if (tag in blockTags) parent.px else fontPx(css["font-size"]) ?: parent.px,
                href = if (tag == "a") (a["href"] ?: parent.href) else parent.href,
            )
            stack += Frame(tag, style)
        }
        if (pos < html.length) text(html.substring(pos))
        builder.finish()
        return builder.paragraphs
    }

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                else -> NAMED_ENTITIES[e] ?: m.value
            }
        }
    }

    // The editor writes accents and punctuation as named entities, so the Latin-1 set is covered.
    private val NAMED_ENTITIES: Map<String, String> = buildMap {
        put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'")
        put("sol", "/"); put("bsol", "\\"); put("lcub", "{"); put("rcub", "}"); put("lbrack", "["); put("rbrack", "]")
        put("lpar", "("); put("rpar", ")"); put("verbar", "|"); put("vert", "|"); put("num", "#"); put("percnt", "%")
        put("colon", ":"); put("semi", ";"); put("comma", ","); put("period", "."); put("excl", "!"); put("quest", "?")
        put("equals", "="); put("plus", "+"); put("ast", "*"); put("commat", "@"); put("dollar", "$"); put("lowbar", "_")
        put("grave", "`"); put("Hat", "^"); put("lsquo", "\u2018"); put("rsquo", "\u2019"); put("ldquo", "\u201C")
        put("rdquo", "\u201D"); put("ndash", "\u2013"); put("mdash", "\u2014"); put("hellip", "\u2026")
        put("euro", "\u20AC"); put("OElig", "\u0152"); put("oelig", "\u0153"); put("middot", "\u00B7")
        val latin1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn " +
            "sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc " +
            "Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute " +
            "Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml " +
            "aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde " +
            "ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(" ")
        latin1.forEachIndexed { index, name -> put(name, (160 + index).toChar().toString()) }
    }

    private fun codePoint(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}
