package com.eventmanager.app.data.drive

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.docs.v1.Docs
import com.google.api.services.docs.v1.model.Document
import com.google.api.services.docs.v1.model.StructuralElement
import com.google.api.services.docs.v1.model.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls people out of a Google Doc.
 *
 * Contact smart chips arrive as `person` elements carrying both a display name and an email,
 * which is the strongest signal the matcher can get. Plain `@Name` text is also collected, but
 * flagged [MentionSource.PLAIN_TEXT] since nothing verifies who it refers to.
 */
class GoogleDocMentionExtractor(private val requestInitializer: HttpRequestInitializer) {

    private val docs: Docs by lazy {
        Docs.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer,
        ).setApplicationName(DriveDocumentBrowser.APPLICATION_NAME).build()
    }

    suspend fun extract(documentId: String): Result<DocumentExtraction> = withContext(Dispatchers.IO) {
        runCatching {
            val document = docs.documents().get(documentId)
                .setIncludeTabsContent(true)
                .execute()
            DocumentExtraction(
                title = document.title.orEmpty(),
                mentions = collect(document).mergeDuplicates(),
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(GoogleWorkspaceApiErrors.map(it, WorkspaceApi.DOCS)) },
        )
    }

    private fun collect(document: Document): List<DocumentMention> {
        val out = mutableListOf<DocumentMention>()
        // A document either uses the newer tab model or the legacy top-level body, never both.
        document.tabs.orEmpty().forEach { collectTab(it, out) }
        document.body?.content?.let { collectStructural(it, out) }
        document.headers?.values?.forEach { it.content?.let { c -> collectStructural(c, out) } }
        document.footers?.values?.forEach { it.content?.let { c -> collectStructural(c, out) } }
        document.footnotes?.values?.forEach { it.content?.let { c -> collectStructural(c, out) } }
        return out
    }

    private fun collectTab(tab: Tab, out: MutableList<DocumentMention>) {
        tab.documentTab?.let { docTab ->
            docTab.body?.content?.let { collectStructural(it, out) }
            docTab.headers?.values?.forEach { it.content?.let { c -> collectStructural(c, out) } }
            docTab.footers?.values?.forEach { it.content?.let { c -> collectStructural(c, out) } }
            docTab.footnotes?.values?.forEach { it.content?.let { c -> collectStructural(c, out) } }
        }
        tab.childTabs.orEmpty().forEach { collectTab(it, out) }
    }

    private fun collectStructural(content: List<StructuralElement>, out: MutableList<DocumentMention>) {
        content.forEach { element ->
            element.paragraph?.elements?.forEach { paragraphElement ->
                paragraphElement.person?.personProperties?.let { props ->
                    val email = props.email?.trim().orEmpty()
                    if (email.isNotEmpty()) {
                        val name = props.name?.trim().orEmpty()
                        out += DocumentMention(
                            rawLabel = "@" + name.ifBlank { email },
                            displayName = name.ifBlank { TextNormalization.emailLocalPart(email) },
                            email = email,
                            source = MentionSource.CONTACT_CHIP,
                        )
                    }
                }
                paragraphElement.textRun?.content?.let { text ->
                    out += PlainTextMentionParser.parse(text)
                }
            }
            element.table?.tableRows?.forEach { row ->
                row.tableCells?.forEach { cell ->
                    cell.content?.let { collectStructural(it, out) }
                }
            }
            element.tableOfContents?.content?.let { collectStructural(it, out) }
        }
    }
}

data class DocumentExtraction(
    val title: String,
    val mentions: List<DocumentMention>,
)

/**
 * Finds hand-typed `@Name` mentions. Capitalisation is required so that email addresses,
 * social handles and stray `@` symbols in prose do not turn into shift rows.
 */
object PlainTextMentionParser {

    private val MENTION = Regex(
        "(?<![\\p{L}\\p{N}@._-])@(\\p{Lu}[\\p{L}'\\u2019-]{1,}(?:[ \\u00A0]\\p{Lu}[\\p{L}'\\u2019-]{1,}){0,2})",
    )

    fun parse(text: String): List<DocumentMention> =
        MENTION.findAll(text).mapNotNull { match ->
            val name = match.groupValues[1].trim().trimEnd(',', '.', ';', ':')
            if (name.length < 2) return@mapNotNull null
            DocumentMention(
                rawLabel = "@$name",
                displayName = name,
                source = MentionSource.PLAIN_TEXT,
            )
        }.toList()
}
