package com.eventmanager.app.ui.components.driveimport

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.drive.DriveConsentResult
import com.eventmanager.app.data.drive.DriveDocumentRef
import com.eventmanager.app.data.drive.DriveImportCapabilities
import com.eventmanager.app.data.drive.DriveShiftImportService
import com.eventmanager.app.data.drive.createGoogleUserApiAuth
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Entry point shown at the top of the add-shift dialog: opens the picker, runs the analysis,
 * then hands off to the review list. Owns the whole flow so the host screen only has to say
 * whether the feature is available.
 */
@Composable
fun DriveShiftImportEntry(
    volunteers: List<Volunteer>,
    jobTypeConfigs: List<JobTypeConfig>,
    venues: List<VenueEntity>,
    defaultDateTime: Long,
    onImport: (List<Job>, (Result<Int>) -> Unit) -> Unit,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val auth = remember(platformContext) { createGoogleUserApiAuth(platformContext) }
    val service = remember(auth) { DriveShiftImportService(auth) }

    var capabilities by remember { mutableStateOf(DriveImportCapabilities.NONE) }
    var showPicker by remember { mutableStateOf(false) }
    var analyzingDocument by remember { mutableStateOf<DriveDocumentRef?>(null) }
    var analysisError by remember { mutableStateOf<Throwable?>(null) }
    var reviewRows by remember { mutableStateOf<List<DriveImportRow>?>(null) }
    var reviewTitle by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // Reading the stored credential touches disk and may refresh the token over the network.
    LaunchedEffect(showPicker) {
        capabilities = withContext(Dispatchers.IO) { service.capabilities() }
    }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.drive_import_entry_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.drive_import_entry_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (analysisError != null && !showPicker) {
                    Text(
                        text = workspaceApiErrorMessage(analysisError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Button(
                onClick = {
                    analysisError = null
                    showPicker = true
                },
                enabled = analyzingDocument == null,
            ) {
                Text(stringResource(Res.string.drive_import_entry_action))
            }
        }
    }

    if (showPicker) {
        DriveSourcePickerDialog(
            capabilities = capabilities,
            loadRecentDocuments = { service.listRecentDocuments() },
            onRequestAccess = {
                when (val result = auth.requestDriveConsent()) {
                    is DriveConsentResult.Granted -> {
                        capabilities = withContext(Dispatchers.IO) { service.capabilities() }
                        Result.success(Unit)
                    }
                    is DriveConsentResult.Denied -> Result.failure(IllegalStateException(result.message))
                    DriveConsentResult.Unsupported ->
                        Result.failure(IllegalStateException("Not supported on this platform"))
                }
            },
            analyzingDocument = analyzingDocument,
            analysisError = analysisError,
            onDocumentChosen = { document ->
                analyzeDocument(
                    scope = scope,
                    service = service,
                    document = document,
                    volunteers = volunteers,
                    jobTypeConfigs = jobTypeConfigs,
                    defaultDateTime = defaultDateTime,
                    onStart = {
                        analyzingDocument = document
                        analysisError = null
                    },
                    onSuccess = { title, rows ->
                        analyzingDocument = null
                        showPicker = false
                        reviewTitle = title
                        reviewRows = rows
                    },
                    onError = { error ->
                        analyzingDocument = null
                        analysisError = error
                    },
                )
            },
            onDismiss = {
                if (analyzingDocument == null) {
                    showPicker = false
                    analysisError = null
                }
            },
        )
    }

    reviewRows?.let { rows ->
        DriveShiftImportDialog(
            documentTitle = reviewTitle,
            initialRows = rows,
            volunteers = volunteers,
            jobTypeConfigs = jobTypeConfigs,
            venues = venues,
            importing = importing,
            errorMessage = importError,
            onImport = { jobs ->
                importing = true
                importError = null
                onImport(jobs) { result ->
                    importing = false
                    result
                        .onSuccess {
                            reviewRows = null
                            onImported()
                        }
                        .onFailure { importError = it.message }
                }
            },
            onDismiss = {
                reviewRows = null
                importError = null
            },
        )
    }
}

private fun analyzeDocument(
    scope: kotlinx.coroutines.CoroutineScope,
    service: DriveShiftImportService,
    document: DriveDocumentRef,
    volunteers: List<Volunteer>,
    jobTypeConfigs: List<JobTypeConfig>,
    defaultDateTime: Long,
    onStart: () -> Unit,
    onSuccess: (String, List<DriveImportRow>) -> Unit,
    onError: (Throwable) -> Unit,
) {
    onStart()
    scope.launch {
        service.analyze(document, volunteers)
            .onSuccess { analysis ->
                onSuccess(
                    analysis.documentTitle,
                    DriveImportRowFactory.build(analysis.matches, jobTypeConfigs, defaultDateTime),
                )
            }
            .onFailure { onError(it) }
    }
}
