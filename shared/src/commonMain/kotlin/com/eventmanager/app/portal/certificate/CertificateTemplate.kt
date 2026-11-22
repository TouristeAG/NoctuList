package com.eventmanager.app.portal.certificate

import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class CertAlign { LEFT, CENTER, RIGHT, JUSTIFY }

@Serializable
enum class CertSize { SMALL, NORMAL, LARGE, TITLE }

/**
 * One piece of a paragraph. Exactly one of [text], [variable] or [gender] is used.
 * [gender] holds the three forms in the order masculine, feminine, non-binary.
 */
@Serializable
data class CertRun(
    val text: String = "",
    @SerialName("var") val variable: String = "",
    val gender: List<String> = emptyList(),
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
)

@Serializable
data class CertParagraph(
    val align: CertAlign = CertAlign.LEFT,
    val size: CertSize = CertSize.NORMAL,
    val bullet: Boolean = false,
    val runs: List<CertRun> = emptyList(),
)

/** Variants of one block. Fixed blocks (header, title, closing...) only use [one]. */
@Serializable
data class CertBlock(
    val one: List<CertParagraph> = emptyList(),
    val many: List<CertParagraph> = emptyList(),
    val manyOneRole: List<CertParagraph> = emptyList(),
    val line: List<CertParagraph> = emptyList(),
)

@Serializable
data class CertSigner(
    val name: String = "",
    val title: String = "",
    val imageBase64: String = "",
)

@Serializable
data class CertMail(
    val fromName: String = "",
    val fromAddress: String = "",
    val replyTo: String = "",
    val subject: String = "",
    val body: String = "",
)

@Serializable
data class CertificateTemplate(
    val version: Int = 1,
    val language: String = "fr",
    val place: String = "",
    val signer: CertSigner = CertSigner(),
    val mail: CertMail = CertMail(),
    val blocks: Map<String, CertBlock> = emptyMap(),
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Returns null when [json] is not a readable template. */
        fun parse(json: String): CertificateTemplate? =
            try {
                this.json.decodeFromString<CertificateTemplate>(json)
            } catch (_: Exception) {
                null
            }
    }
}

/** Declaration order is the order of the blocks in the document. */
enum class CertificateCategory { MANDATE, SHIFT, PHOTO, GRAPHIC_EVENT, GRAPHIC_ASSOCIATION, OTHER, MEETING }

fun certificateCategoryOf(config: JobTypeConfig?): CertificateCategory {
    if (config == null) return CertificateCategory.OTHER
    if (config.isOrionJob) return CertificateCategory.MANDATE
    return when (config.novaJobType) {
        NovaJobType.MEETING -> CertificateCategory.MEETING
        NovaJobType.PHOTOGRAPHER_VIDEOGRAPHER -> CertificateCategory.PHOTO
        NovaJobType.GRAPHIC_DESIGNER_EVENT -> CertificateCategory.GRAPHIC_EVENT
        NovaJobType.GRAPHIC_DESIGNER_ASSOCIATION -> CertificateCategory.GRAPHIC_ASSOCIATION
        NovaJobType.DEFAULT_SHIFT ->
            if (config.isShiftJob) CertificateCategory.SHIFT else CertificateCategory.OTHER
    }
}
