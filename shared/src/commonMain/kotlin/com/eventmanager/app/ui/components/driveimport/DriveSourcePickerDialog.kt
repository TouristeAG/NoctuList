package com.eventmanager.app.ui.components.driveimport

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.drive.DriveDocumentBrowser
import com.eventmanager.app.data.drive.DriveDocumentRef
import com.eventmanager.app.data.drive.DriveDocumentType
import com.eventmanager.app.data.drive.DriveImportCapabilities
import com.eventmanager.app.data.drive.WorkspaceApi
import com.eventmanager.app.data.drive.WorkspaceApiErrorKind
import com.eventmanager.app.data.drive.WorkspaceApiException
import com.eventmanager.app.data.utils.DateTimeUtils
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Chooses which Google document to import from.
 *
 * The recent-files tab needs the restricted `drive.metadata.readonly` scope, so it disappears
 * when that grant is missing and the admin pastes a link instead.
 */
@Composable
fun DriveSourcePickerDialog(
    capabilities: DriveImportCapabilities,
    loadRecentDocuments: suspend () -> Result<List<DriveDocumentRef>>,
    onRequestAccess: suspend () -> Result<Unit>,
    onDocumentChosen: (DriveDocumentRef) -> Unit,
    onDismiss: () -> Unit,
    analyzingDocument: DriveDocumentRef? = null,
    analysisError: Throwable? = null,
) {
    val scope = rememberCoroutineScope()
    var listingUnavailable by remember { mutableStateOf(false) }
    var listingError by remember { mutableStateOf<Throwable?>(null) }
    val showRecentTab = capabilities.canListRecentFiles && !listingUnavailable
    var selectedTab by remember(capabilities) { mutableStateOf(if (capabilities.canListRecentFiles) 0 else 1) }
    var recentDocuments by remember { mutableStateOf<List<DriveDocumentRef>>(emptyList()) }
    var loadingRecent by remember { mutableStateOf(capabilities.canListRecentFiles) }
    var urlInput by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var requestingAccess by remember { mutableStateOf(false) }
    var accessError by remember { mutableStateOf<String?>(null) }

    suspend fun refreshRecent() {
        loadingRecent = true
        listingError = null
        loadRecentDocuments()
            .onSuccess {
                recentDocuments = it
                listingUnavailable = false
            }
            .onFailure { error ->
                listingError = error
                if ((error as? WorkspaceApiException)?.hidesRecentFilesList == true) {
                    listingUnavailable = true
                    selectedTab = 1
                }
            }
        loadingRecent = false
    }

    LaunchedEffect(capabilities.canListRecentFiles) {
        if (capabilities.canListRecentFiles) refreshRecent()
    }

    Dialog(
        onDismissRequest = { if (analyzingDocument == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.84f).padding(16.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PickerHeader(
                    analyzing = analyzingDocument != null,
                    onDismiss = onDismiss,
                )

                if (analyzingDocument != null) {
                    DriveImportAnalyzingPanel(
                        document = analyzingDocument,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    return@Column
                }

                if (!capabilities.canImport) {
                    MissingAccessPanel(
                        requesting = requestingAccess,
                        error = accessError,
                        onRequest = {
                            scope.launch {
                                requestingAccess = true
                                accessError = null
                                onRequestAccess().onFailure { accessError = it.message }
                                requestingAccess = false
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    return@Column
                }

                if (showRecentTab) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(stringResource(Res.string.drive_import_tab_recent)) },
                            icon = { Icon(Icons.Default.Article, contentDescription = null) },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(stringResource(Res.string.drive_import_tab_url)) },
                            icon = { Icon(Icons.Default.Link, contentDescription = null) },
                        )
                    }
                }

                if (analysisError != null) {
                    Text(
                        text = workspaceApiErrorMessage(analysisError),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }

                if (selectedTab == 0 && showRecentTab) {
                    RecentDocumentsPane(
                        documents = recentDocuments,
                        loading = loadingRecent,
                        error = listingError,
                        onRefresh = { scope.launch { refreshRecent() } },
                        onDocumentChosen = onDocumentChosen,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    UrlImportPane(
                        urlInput = urlInput,
                        urlError = urlError,
                        listingNotice = listingError.takeIf { listingUnavailable },
                        onUrlChange = {
                            urlInput = it
                            urlError = null
                        },
                        onSubmit = {
                            val parsed = DriveDocumentBrowser.parseDocumentRef(urlInput)
                            if (parsed == null) {
                                urlError = "invalid"
                            } else {
                                onDocumentChosen(parsed)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerHeader(analyzing: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (analyzing) {
                    stringResource(Res.string.drive_import_analyzing_title)
                } else {
                    stringResource(Res.string.drive_import_title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (analyzing) {
                    stringResource(Res.string.drive_import_analyzing_subtitle)
                } else {
                    stringResource(Res.string.drive_import_picker_subtitle)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!analyzing) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
            }
        }
    }
}

@Composable
private fun RecentDocumentsPane(
    documents: List<DriveDocumentRef>,
    loading: Boolean,
    error: Throwable?,
    onRefresh: () -> Unit,
    onDocumentChosen: (DriveDocumentRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.drive_import_recent_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, enabled = !loading) {
                val spin = rememberInfiniteTransition(label = "recentRefresh")
                val rotation by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                    ),
                    label = "refreshRotation",
                )
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(Res.string.refresh),
                    modifier = if (loading) {
                        Modifier.graphicsLayer { rotationZ = rotation }
                    } else {
                        Modifier
                    },
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }

        when {
            loading && documents.isEmpty() -> RecentDocumentsLoading(Modifier.weight(1f).fillMaxWidth())
            error != null && documents.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = workspaceApiErrorMessage(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            documents.isEmpty() -> {
                EmptyRecentState(Modifier.weight(1f).fillMaxWidth())
            }
            else -> {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    documents.forEach { document ->
                        RecentDocumentRow(document, onClick = { onDocumentChosen(document) })
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun RecentDocumentsLoading(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "recentLoad")
    val scale by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadScale",
    )
    val halo by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadHalo",
    )
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = halo
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            )
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.drive_import_recent_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyRecentState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(72.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Article, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.drive_import_recent_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.drive_import_no_recent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UrlImportPane(
    urlInput: String,
    urlError: String?,
    listingNotice: Throwable?,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val invalidUrlMessage = stringResource(Res.string.drive_import_url_invalid)
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (listingNotice != null) {
            Text(
                text = workspaceApiErrorMessage(listingNotice),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(96.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = stringResource(Res.string.drive_import_url_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.drive_import_url_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            label = { Text(stringResource(Res.string.drive_import_url_label)) },
            placeholder = { Text("https://docs.google.com/…") },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            singleLine = true,
            isError = urlError != null,
            supportingText = if (urlError != null) {
                { Text(invalidUrlMessage) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        )
        Button(
            onClick = onSubmit,
            enabled = urlInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(Res.string.drive_import_open))
        }
    }
}

@Composable
private fun DriveImportAnalyzingPanel(
    document: DriveDocumentRef,
    modifier: Modifier = Modifier,
) {
    val steps = listOf(
        stringResource(Res.string.drive_import_analyzing_step_read),
        stringResource(Res.string.drive_import_analyzing_step_people),
        stringResource(Res.string.drive_import_analyzing_step_match),
    )
    var step by remember(document.id) { mutableStateOf(0) }
    LaunchedEffect(document.id) {
        while (true) {
            delay(1600)
            step = (step + 1) % steps.size
        }
    }
    val infinite = rememberInfiniteTransition(label = "driveImportLoad")
    val scale by infinite.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadScale",
    )
    val halo by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadHalo",
    )

    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = halo
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(88.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = documentTypeIcon(document.type),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(Res.string.drive_import_analyzing_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        val displayName = document.name.ifBlank { stringResource(documentTypeLabel(document.type)) }
        Spacer(Modifier.height(8.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(20.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(280)) },
            label = "analyzeStep",
        ) { index ->
            Text(
                text = steps[index],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .width(240.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(Res.string.drive_import_analyzing_wait),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

@Composable
private fun RecentDocumentRow(document: DriveDocumentRef, onClick: () -> Unit) {
    val typeColor = when (document.type) {
        DriveDocumentType.DOC -> MaterialTheme.colorScheme.primary
        DriveDocumentType.SHEET -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = documentTypeIcon(document.type),
                contentDescription = null,
                tint = typeColor,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = document.name.ifBlank { stringResource(documentTypeLabel(document.type)) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(stringResource(documentTypeLabel(document.type)))
                        if (document.modifiedTimeMillis > 0) {
                            append("  ·  ")
                            append(DateTimeUtils.formatGenevaDate(document.modifiedTimeMillis))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun documentTypeIcon(type: DriveDocumentType): ImageVector = when (type) {
    DriveDocumentType.DOC -> Icons.Default.Article
    DriveDocumentType.SHEET -> Icons.Default.GridOn
}

private fun documentTypeLabel(type: DriveDocumentType) = when (type) {
    DriveDocumentType.DOC -> Res.string.drive_import_type_doc
    DriveDocumentType.SHEET -> Res.string.drive_import_type_sheet
}

@Composable
internal fun workspaceApiErrorMessage(error: Throwable?): String {
    val mapped = error as? WorkspaceApiException
    return when (mapped?.kind) {
        WorkspaceApiErrorKind.SERVICE_DISABLED -> when (mapped.api) {
            WorkspaceApi.DRIVE -> stringResource(Res.string.drive_import_api_disabled_drive)
            WorkspaceApi.DOCS -> stringResource(Res.string.drive_import_api_disabled_docs)
            WorkspaceApi.SHEETS -> stringResource(Res.string.drive_import_api_disabled_sheets)
            WorkspaceApi.PEOPLE -> stringResource(Res.string.drive_import_api_disabled_people)
        }
        WorkspaceApiErrorKind.PERMISSION_DENIED -> stringResource(Res.string.drive_import_permission_denied)
        WorkspaceApiErrorKind.NOT_FOUND -> stringResource(Res.string.drive_import_document_not_found)
        WorkspaceApiErrorKind.OTHER -> mapped.message
        null -> error?.message?.takeIf { it.length < 180 } ?: stringResource(Res.string.drive_import_request_failed)
    }
}

@Composable
private fun MissingAccessPanel(
    requesting: Boolean,
    error: String?,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(80.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.drive_import_access_needed),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest, enabled = !requesting, modifier = Modifier.height(48.dp)) {
            if (requesting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(Res.string.drive_import_grant_access))
        }
    }
}
