package com.eventmanager.app.data.drive

import com.eventmanager.app.data.sync.AppLogger
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DriveDocumentBrowser"
private const val MIME_DOC = "application/vnd.google-apps.document"
private const val MIME_SHEET = "application/vnd.google-apps.spreadsheet"

/**
 * Lists the user's most recently touched Docs and Sheets. Needs the restricted
 * `drive.metadata.readonly` scope; the import falls back to URL paste when it is absent.
 */
class DriveDocumentBrowser(private val requestInitializer: HttpRequestInitializer) {

    private val drive: Drive by lazy {
        Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            requestInitializer,
        ).setApplicationName(APPLICATION_NAME).build()
    }

    suspend fun listRecentDocuments(limit: Int = 25): Result<List<DriveDocumentRef>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = drive.files().list()
                    .setQ("trashed = false and (mimeType = '$MIME_DOC' or mimeType = '$MIME_SHEET')")
                    // modifiedByMeTime surfaces what the admin actually worked on, not what a
                    // colleague edited in a shared folder.
                    .setOrderBy("modifiedByMeTime desc,modifiedTime desc")
                    .setPageSize(limit)
                    .setFields("files(id,name,mimeType,modifiedTime)")
                    .setCorpora("user")
                    .execute()
                response.files.orEmpty().mapNotNull { file ->
                    val type = when (file.mimeType) {
                        MIME_DOC -> DriveDocumentType.DOC
                        MIME_SHEET -> DriveDocumentType.SHEET
                        else -> return@mapNotNull null
                    }
                    DriveDocumentRef(
                        id = file.id ?: return@mapNotNull null,
                        name = file.name.orEmpty(),
                        type = type,
                        modifiedTimeMillis = file.modifiedTime?.value ?: 0L,
                    )
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    val mapped = GoogleWorkspaceApiErrors.map(error, WorkspaceApi.DRIVE)
                    AppLogger.w(TAG, "Recent Drive files unavailable: ${mapped.message}")
                    Result.failure(mapped)
                },
            )
        }

    companion object {
        const val APPLICATION_NAME = "NoctuList"

        private val DOC_URL = Regex("/document/d/([A-Za-z0-9_-]+)")
        private val SHEET_URL = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)")
        private val BARE_ID = Regex("^[A-Za-z0-9_-]{20,}$")

        /**
         * Accepts a full Docs/Sheets URL or a bare document ID. A bare ID has no type marker,
         * so callers must supply [assumeType] (the picker defaults it to Docs).
         */
        fun parseDocumentRef(input: String, assumeType: DriveDocumentType = DriveDocumentType.DOC): DriveDocumentRef? {
            val raw = input.trim()
            if (raw.isBlank()) return null
            DOC_URL.find(raw)?.let {
                return DriveDocumentRef(it.groupValues[1], "", DriveDocumentType.DOC)
            }
            SHEET_URL.find(raw)?.let {
                return DriveDocumentRef(it.groupValues[1], "", DriveDocumentType.SHEET)
            }
            if (BARE_ID.matches(raw)) return DriveDocumentRef(raw, "", assumeType)
            return null
        }
    }
}
