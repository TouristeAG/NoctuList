package com.eventmanager.app.data.drive

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequestInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Pulls people out of a Google Sheet.
 *
 * Deliberately talks to the Sheets REST endpoint rather than the bundled
 * `google-api-services-sheets` client: that client is pinned to a 2022 revision for
 * [com.eventmanager.app.data.sync.GoogleSheetsService] and predates the `chipRuns` field that
 * carries smart-chip data. Only a handful of fields are needed, so a field mask plus a small
 * model is cheaper than bumping a dependency the whole sync layer rests on.
 */
class GoogleSheetMentionExtractor(private val requestInitializer: HttpRequestInitializer) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extract(spreadsheetId: String): Result<DocumentExtraction> = withContext(Dispatchers.IO) {
        runCatching {
            val fields = "properties.title,sheets(data(rowData(values(formattedValue,userEnteredValue.stringValue,chipRuns))))"
            val url = "https://sheets.googleapis.com/v4/spreadsheets/" +
                URLEncoder.encode(spreadsheetId, Charsets.UTF_8.name()) +
                "?fields=" + URLEncoder.encode(fields, Charsets.UTF_8.name())
            val body = GoogleNetHttpTransport.newTrustedTransport()
                .createRequestFactory(requestInitializer)
                .buildGetRequest(GenericUrl(url))
                .execute()
                .parseAsString()
            val payload = json.decodeFromString<SpreadsheetPayload>(body)
            DocumentExtraction(
                title = payload.properties?.title.orEmpty(),
                mentions = collect(payload).mergeDuplicates(),
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(GoogleWorkspaceApiErrors.map(it, WorkspaceApi.SHEETS)) },
        )
    }

    private fun collect(payload: SpreadsheetPayload): List<DocumentMention> {
        val out = mutableListOf<DocumentMention>()
        payload.sheets.orEmpty().forEach { sheet ->
            sheet.data.orEmpty().forEach { grid ->
                grid.rowData.orEmpty().forEach { row ->
                    row.values.orEmpty().forEach { cell -> collectCell(cell, out) }
                }
            }
        }
        return out
    }

    private fun collectCell(cell: CellPayload, out: MutableList<DocumentMention>) {
        val chipEmails = cell.chipRuns.orEmpty()
            .mapNotNull { it.chip?.personProperties?.email?.trim() }
            .filter { it.isNotEmpty() }

        if (chipEmails.isNotEmpty()) {
            chipEmails.forEach { email ->
                // Sheets person chips carry only the email, unlike Docs which also sends a name.
                // The display name is filled in later by People API enrichment when available.
                out += DocumentMention(
                    rawLabel = "@$email",
                    displayName = TextNormalization.emailLocalPart(email),
                    email = email,
                    source = MentionSource.CONTACT_CHIP,
                )
            }
            // Chip cells render their placeholder as text; scanning it would double-count.
            return
        }

        val text = cell.formattedValue ?: cell.userEnteredValue?.stringValue ?: return
        out += PlainTextMentionParser.parse(text)
    }

    @Serializable
    private data class SpreadsheetPayload(
        val properties: SpreadsheetProperties? = null,
        val sheets: List<SheetPayload>? = null,
    )

    @Serializable
    private data class SpreadsheetProperties(val title: String? = null)

    @Serializable
    private data class SheetPayload(val data: List<GridPayload>? = null)

    @Serializable
    private data class GridPayload(val rowData: List<RowPayload>? = null)

    @Serializable
    private data class RowPayload(val values: List<CellPayload>? = null)

    @Serializable
    private data class CellPayload(
        val formattedValue: String? = null,
        val userEnteredValue: ExtendedValuePayload? = null,
        val chipRuns: List<ChipRunPayload>? = null,
    )

    @Serializable
    private data class ExtendedValuePayload(val stringValue: String? = null)

    @Serializable
    private data class ChipRunPayload(
        val startIndex: Int? = null,
        val chip: ChipPayload? = null,
    )

    @Serializable
    private data class ChipPayload(
        @SerialName("personProperties") val personProperties: PersonPropertiesPayload? = null,
    )

    @Serializable
    private data class PersonPropertiesPayload(val email: String? = null)
}
