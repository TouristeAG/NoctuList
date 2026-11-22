package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.eventmanager.app.portal.certificate.CertBlock
import com.eventmanager.app.portal.certificate.CertMail
import com.eventmanager.app.portal.certificate.CertParagraph
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.CertificateDefaults
import com.eventmanager.app.portal.certificate.CertificateTemplate
import com.mohamedrejeb.richeditor.model.RichTextState

enum class CertVariant { ONE, MANY, MANY_ONE_ROLE, LINE }

/** A block of the editor: its template key, the variants it shows and the variables that make sense in each. */
class CertBlockSpec(val key: String, val variants: List<CertVariant>, val variables: (CertVariant) -> List<String>)

object CertificateBlocks {
    private val person = listOf("firstName", "lastName", "association")
    private val period = listOf("period", "start", "end")

    private fun fixed(key: String, vararg extra: String) =
        CertBlockSpec(key, listOf(CertVariant.ONE)) { person + period + extra.toList() }

    private fun oneOrMany(key: String) = CertBlockSpec(key, listOf(CertVariant.ONE, CertVariant.MANY)) { v ->
        if (v == CertVariant.ONE) person + "date" else person + "count" + period
    }

    private fun withRoles(key: String) = CertBlockSpec(
        key,
        listOf(CertVariant.ONE, CertVariant.MANY, CertVariant.MANY_ONE_ROLE, CertVariant.LINE),
    ) { v ->
        when (v) {
            CertVariant.ONE -> person + listOf("date", "role", "description")
            CertVariant.MANY -> person + "count" + period + "roles"
            CertVariant.MANY_ONE_ROLE -> person + "count" + period + listOf("role", "description")
            CertVariant.LINE -> listOf("role", "description", "count") + period + "date"
        }
    }

    /** In the order of the document. */
    val all: List<CertBlockSpec> = listOf(
        fixed("header", "place"),
        fixed("title"),
        CertBlockSpec("intro", listOf(CertVariant.ONE, CertVariant.MANY)) { v ->
            if (v == CertVariant.ONE) person + "date" else person + period
        },
        CertBlockSpec("MANDATE", listOf(CertVariant.ONE)) { person + listOf("role", "description") + period },
        withRoles("SHIFT"),
        oneOrMany("PHOTO"),
        oneOrMany("GRAPHIC_EVENT"),
        CertBlockSpec("GRAPHIC_ASSOCIATION", listOf(CertVariant.ONE)) { person + period },
        withRoles("OTHER"),
        oneOrMany("MEETING"),
        fixed("closing"),
        CertBlockSpec("dateLine", listOf(CertVariant.ONE)) { listOf("place", "today", "association") },
        CertBlockSpec("footer", listOf(CertVariant.ONE)) { listOf("association") },
    )

    fun paragraphs(block: CertBlock?, variant: CertVariant): List<CertParagraph> = when (variant) {
        CertVariant.ONE -> block?.one
        CertVariant.MANY -> block?.many
        CertVariant.MANY_ONE_ROLE -> block?.manyOneRole
        CertVariant.LINE -> block?.line
    }.orEmpty()

    fun with(block: CertBlock, variant: CertVariant, paragraphs: List<CertParagraph>): CertBlock = when (variant) {
        CertVariant.ONE -> block.copy(one = paragraphs)
        CertVariant.MANY -> block.copy(many = paragraphs)
        CertVariant.MANY_ONE_ROLE -> block.copy(manyOneRole = paragraphs)
        CertVariant.LINE -> block.copy(line = paragraphs)
    }
}

/**
 * Everything the certificate dialog edits. Rich texts live in one [RichTextState] per block variant;
 * [toTemplate] reads them back into the model. Variants the editor does not show are kept as loaded.
 */
class CertificateEditorModel(
    initial: CertificateTemplate,
    private val association: String,
    /** Text a variable shows in the editor, for example "{prénom}". The template keeps the key. */
    val labelOf: (String) -> String = CertificateDocHtml::keyLabel,
) {
    var language by mutableStateOf(initial.language.takeIf { it in CertificateDefaults.LANGUAGES } ?: "fr")
    var place by mutableStateOf(initial.place)
    var signerName by mutableStateOf(initial.signer.name)
    var signerTitle by mutableStateOf(initial.signer.title)
    var signerImage by mutableStateOf(initial.signer.imageBase64)
    var fromName by mutableStateOf(initial.mail.fromName)
    var fromAddress by mutableStateOf(initial.mail.fromAddress)
    var replyTo by mutableStateOf(initial.mail.replyTo)
    var subject by mutableStateOf(initial.mail.subject)
    var body by mutableStateOf(initial.mail.body)

    /** The block variant the toolbar acts on: the last editor that had the focus. */
    var activeKey by mutableStateOf<Pair<String, CertVariant>?>(null)

    val active: RichTextState? get() = activeKey?.let { editors[it] }

    /** The variant shown in each block. Kept here so it survives the editor leaving the screen (tabs). */
    val shownVariant: SnapshotStateMap<String, CertVariant> = mutableStateMapOf()

    fun variantOf(key: String): CertVariant =
        shownVariant[key] ?: CertificateBlocks.all.first { it.key == key }.variants.first()

    fun showVariant(key: String, variant: CertVariant) {
        shownVariant[key] = variant
        activeKey = key to variant
    }

    /** The e-mail section is unfolded. */
    var mailOpen by mutableStateOf(false)

    private val version = initial.version
    private var baseBlocks: Map<String, CertBlock> = initial.blocks

    val editors: Map<Pair<String, CertVariant>, RichTextState> = buildMap {
        for (spec in CertificateBlocks.all) for (variant in spec.variants) {
            put(spec.key to variant, RichTextState())
        }
    }

    init {
        loadBlocks(initial.blocks)
    }

    fun editor(key: String, variant: CertVariant): RichTextState = editors.getValue(key to variant)

    fun styleLinks(color: Color) {
        for (state in editors.values) {
            state.config.linkColor = color
            state.config.linkTextDecoration = TextDecoration.None
        }
    }

    private fun loadBlocks(blocks: Map<String, CertBlock>) {
        baseBlocks = blocks
        for ((key, state) in editors) {
            state.setHtml(CertificateDocHtml.toHtml(CertificateBlocks.paragraphs(blocks[key.first], key.second), labelOf))
        }
    }

    /** Back to the default texts of the current language; place, signer and addresses stay. */
    fun loadDefaultTexts() {
        val defaults = CertificateDefaults.template(language, association)
        loadBlocks(defaults.blocks)
        subject = defaults.mail.subject
        body = defaults.mail.body
    }

    /** Read by the preview so it recomputes after every change. */
    fun changeKey(): List<Any?> = buildList {
        add(language); add(place); add(signerName); add(signerTitle); add(signerImage)
        for (state in editors.values) add(state.annotatedString)
    }

    fun toTemplate(): CertificateTemplate {
        val blocks = LinkedHashMap(baseBlocks)
        for ((key, state) in editors) {
            val (blockKey, variant) = key
            val paragraphs = CertificateDocHtml.fromHtml(state.toHtml(), labelOf)
            blocks[blockKey] = CertificateBlocks.with(blocks[blockKey] ?: CertBlock(), variant, paragraphs)
        }
        return CertificateTemplate(
            version = version,
            language = language,
            place = place.trim(),
            signer = CertSigner(signerName.trim(), signerTitle.trim(), signerImage),
            mail = CertMail(fromName.trim(), fromAddress.trim(), replyTo.trim(), subject.trim(), body.trim()),
            blocks = blocks,
        )
    }

    companion object {
        /** Same limit as the logo: the published document must stay well under the Firestore size. */
        const val MAX_TEMPLATE_CHARS = 400_000
        const val MAX_SIGNATURE_CHARS = 200_000
        const val SIGNATURE_MAX_WIDTH = 600
    }
}
