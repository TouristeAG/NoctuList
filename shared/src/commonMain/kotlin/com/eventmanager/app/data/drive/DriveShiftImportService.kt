package com.eventmanager.app.data.drive

import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.AppLogger

private const val TAG = "DriveShiftImport"

/**
 * Ties the import together: read a Drive document, pull out the people mentioned in it, enrich
 * them from Google Contacts, then line each one up with a NoctuList volunteer.
 *
 * Every step degrades rather than fails — a missing contacts scope only costs matching accuracy,
 * and a missing Drive scope only costs the recent-files list.
 */
class DriveShiftImportService(private val auth: GoogleUserApiAuth) {

    fun capabilities(): DriveImportCapabilities =
        if (!auth.isSupported || auth.requestInitializer() == null) {
            DriveImportCapabilities.NONE
        } else {
            DriveImportCapabilities.from(auth.grantedScopes())
        }

    suspend fun listRecentDocuments(): Result<List<DriveDocumentRef>> {
        val initializer = auth.requestInitializer()
            ?: return Result.failure(IllegalStateException("Not signed in with Google"))
        if (!capabilities().canListRecentFiles) {
            return Result.failure(IllegalStateException("Drive listing scope not granted"))
        }
        return DriveDocumentBrowser(initializer).listRecentDocuments()
    }

    suspend fun analyze(
        document: DriveDocumentRef,
        volunteers: List<Volunteer>,
    ): Result<DriveImportAnalysis> {
        val initializer = auth.requestInitializer()
            ?: return Result.failure(IllegalStateException("Not signed in with Google"))
        val capabilities = capabilities()

        val extraction = when (document.type) {
            DriveDocumentType.DOC -> {
                if (!capabilities.canReadDocs) {
                    return Result.failure(IllegalStateException("Google Docs scope not granted"))
                }
                GoogleDocMentionExtractor(initializer).extract(document.id)
            }
            DriveDocumentType.SHEET -> {
                if (!capabilities.canReadSheets) {
                    return Result.failure(IllegalStateException("Google Sheets scope not granted"))
                }
                GoogleSheetMentionExtractor(initializer).extract(document.id)
            }
        }.getOrElse { return Result.failure(it) }

        val enriched = if (capabilities.canEnrichContacts && extraction.mentions.isNotEmpty()) {
            runCatching { PeopleEnricher(initializer).enrich(extraction.mentions) }
                .onFailure { AppLogger.w(TAG, "Contact enrichment skipped: ${it.message}") }
                .getOrDefault(extraction.mentions)
                .mergeDuplicates()
        } else {
            extraction.mentions
        }

        return Result.success(
            DriveImportAnalysis(
                documentTitle = extraction.title.ifBlank { document.name },
                matches = VolunteerMatcher.match(enriched, volunteers),
            ),
        )
    }
}

data class DriveImportAnalysis(
    val documentTitle: String,
    val matches: List<MentionMatch>,
)
