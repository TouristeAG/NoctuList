package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.ApproveResult
import com.eventmanager.app.portal.DirectSendStatus
import com.eventmanager.app.portal.certificate.CertAssembly
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.ResolvedParagraph
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.DialogWindowFollowsScreenSize
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The view model's certificate actions, as the review window sees them. Saving the template is not one of them. */
fun EventManagerViewModel.certificateReviewActions(): CertificateReviewActions {
    val vm = this
    return object : CertificateReviewActions {
        override suspend fun approve(requestId: String, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner) =
            vm.approveCertificateRequest(requestId, body, footer, title, signer)

        override suspend fun refuse(requestId: String, note: String) = vm.refuseCertificateRequest(requestId, note)

        override suspend fun sendDirect(volunteer: Volunteer, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner) =
            vm.sendDirectCertificate(volunteer, body, footer, title, signer)

        override fun directStatus(approvalId: String): Flow<DirectSendStatus> = vm.directCertificateStatus(approvalId)

        override suspend fun dismissDirect(approvalId: String) = vm.dismissDirectCertificate(approvalId)
    }
}

internal fun displayName(volunteer: Volunteer): String = "${volunteer.name} ${volunteer.lastNameAbbreviation}".trim()

/**
 * Review of one certificate request, or generation of a certificate from a volunteer's panel
 * ([CertificateReviewTarget.Direct]). Edits stay in this window: the template is never saved here.
 */
@Composable
fun CertificateReviewDialog(
    target: CertificateReviewTarget,
    viewModel: EventManagerViewModel,
    onDismiss: () -> Unit,
    /** Request approved: the server sends it now. */
    onSentToServer: () -> Unit = {},
) {
    val jobs by viewModel.jobs.collectAsState()
    val configs by viewModel.jobTypeConfigs.collectAsState()
    val logo by viewModel.institutionLogoPng.collectAsState()
    // Built once: later list updates must not throw away the corrections being made.
    val model = remember(target) {
        CertificateReviewModel(
            target,
            viewModel.volunteerPortalCertificateTemplate(),
            viewModel.certificateAssociationName(),
            jobs,
            configs,
        )
    }
    val actions = remember(viewModel) { viewModel.certificateReviewActions() }
    var closeRequests by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = { closeRequests++ },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        DialogWindowFollowsScreenSize()
        CertificateReviewFrame {
            CertificateReviewContent(model, logo, actions, onDismiss, onSentToServer, closeRequests)
        }
    }
}

/** Same size as the template editor. */
@Composable
internal fun CertificateReviewFrame(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val compact = maxWidth < 600.dp
        Surface(
            modifier = if (compact) {
                Modifier.fillMaxSize()
            } else {
                Modifier.widthIn(max = 1600.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f)
            },
            shape = if (compact) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
        ) { content() }
    }
}

@Composable
internal fun reasonText(reason: String): String = when (reason) {
    "INVALID" -> stringResource(Res.string.certificate_reason_invalid)
    "NO_ACCESS" -> stringResource(Res.string.certificate_reason_no_access)
    "NO_EMAIL" -> stringResource(Res.string.certificate_reason_no_email)
    else -> stringResource(Res.string.certificate_reason_send_failed)
}

@Composable
internal fun CertificateReviewContent(
    model: CertificateReviewModel,
    logoBase64: String,
    actions: CertificateReviewActions,
    onDismiss: () -> Unit,
    onSentToServer: () -> Unit,
    /** Bumped by the window (Esc, back): asks to close like the Close button does. */
    closeRequests: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val linkColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(model, linkColor) {
        for (state in listOf(model.body, model.footer)) state.config.linkColor = linkColor
    }

    var preview by remember { mutableStateOf<CertAssembly?>(null) }
    var ready by remember { mutableStateOf(false) }
    // Read straight from the editors, not from the debounced preview: a quick click right after typing counts.
    val canSubmit = if (model.isDirect) model.canSend() else model.canApprove()
    LaunchedEffect(model) {
        var first = true
        snapshotFlow { listOf(model.body.annotatedString, model.footer.annotatedString, model.fromDay, model.toDay, model.selectedTypes) }
            .collectLatest {
                if (!first) delay(PREVIEW_DELAY_MS)
                first = false
                preview = model.preview()
                ready = true
            }
    }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refusing by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var approvalId by remember { mutableStateOf<String?>(null) }
    val status by remember(approvalId) {
        approvalId?.let(actions::directStatus) ?: flowOf(null)
    }.collectAsState(null)
    val sent = status == DirectSendStatus.Sent
    val failed = status as? DirectSendStatus.Failed

    val alreadyHandled = stringResource(Res.string.certificate_review_already_handled)
    val tooLong = stringResource(Res.string.certificate_review_too_long)
    val failedText = stringResource(Res.string.certificate_review_failed)

    fun close() {
        if (busy && approvalId == null) return
        val failedId = approvalId.takeIf { failed != null }
        if (approvalId == null && model.isEdited()) {
            confirmClose = true
        } else {
            // The failure was seen here: it need not wait in the card too.
            if (failedId != null) scope.launch { actions.dismissDirect(failedId) }
            onDismiss()
        }
    }
    LaunchedEffect(closeRequests) { if (closeRequests > 0) close() }

    CompositionLocalProvider(LocalUriHandler provides inertUriHandler) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= TWO_COLUMNS_MIN_WIDTH
            val pad = if (wide) 24.dp else 12.dp
            val family = certificateFontFamily()
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(Res.string.certificate_review_title, model.volunteer?.let(::displayName) ?: model.recipient),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = pad, end = pad, top = pad, bottom = 8.dp),
                )

                val editor: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!model.onlyRefusal) CertificateTextToolbar(model.active, wrap = wide)
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(end = if (wide) 8.dp else 0.dp, top = 4.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CertificateReviewHeader(model, enabled = (approvalId == null || failed != null) && !busy)
                            if (!model.onlyRefusal) {
                                if (!model.hasDraft) {
                                    Text(
                                        stringResource(Res.string.certificate_review_nothing),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                SectionTitle(stringResource(Res.string.certificate_review_body))
                                CertificateTextArea(model.body, family, model.active === model.body, null) { model.active = model.body }
                                SectionTitle(stringResource(Res.string.certificate_block_footer))
                                CertificateTextArea(
                                    model.footer, family, model.active === model.footer,
                                    stringResource(Res.string.certificate_footer_placeholder),
                                ) { model.active = model.footer }
                            }
                        }
                    }
                }
                val previewPane: @Composable (Modifier) -> Unit = { modifier ->
                    CertificatePreviewPane(if (ready) preview else null, ready, logoBase64, model.signer, modifier)
                }

                if (wide && !model.onlyRefusal) {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        editor(Modifier.weight(1f).fillMaxHeight())
                        previewPane(Modifier.weight(1f).fillMaxHeight())
                    }
                } else if (model.onlyRefusal) {
                    editor(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad))
                } else {
                    var tab by remember { mutableIntStateOf(0) }
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.certificate_tab_edit)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.certificate_tab_preview)) })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad, vertical = 8.dp)) {
                        if (tab == 0) editor(Modifier.fillMaxSize()) else previewPane(Modifier.fillMaxSize())
                    }
                }

                HorizontalDivider()
                val statusLine: Pair<String, Boolean>? = when {
                    sent -> stringResource(Res.string.certificate_generate_sent, model.recipient) to false
                    failed != null -> stringResource(Res.string.certificate_generate_failed, reasonText(failed.reason)) to true
                    approvalId != null -> stringResource(Res.string.certificate_review_sending) to false
                    message != null -> message!! to true
                    else -> null
                }
                statusLine?.let { (text, isError) ->
                    Row(
                        Modifier.padding(horizontal = pad, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (approvalId != null && status == DirectSendStatus.Pending) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            text,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val refuseButton: @Composable () -> Unit = {
                    if (!model.isDirect) {
                        OutlinedButton(
                            onClick = { refusing = true },
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.certificate_review_refuse))
                        }
                    }
                }
                val closeButton: @Composable () -> Unit = {
                    val label = when {
                        !model.isDirect || approvalId != null -> Res.string.close
                        else -> Res.string.cancel
                    }
                    TextButton(onClick = { close() }, enabled = !busy || approvalId != null) { Text(stringResource(label)) }
                }
                val showPrimary = if (model.isDirect) approvalId == null || failed != null else !model.onlyRefusal
                val primaryButton: @Composable (Modifier) -> Unit = { modifier ->
                    if (model.isDirect) {
                        Button(
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    // A new attempt replaces the failed one, which need not wait in the card.
                                    approvalId?.let { actions.dismissDirect(it) }
                                    approvalId = null
                                    val id = model.sendDirect(actions)
                                    if (id == null) message = failedText else approvalId = id
                                    busy = false
                                }
                            },
                            enabled = canSubmit && !busy,
                            modifier = modifier,
                        ) { SubmitLabel(busy, stringResource(Res.string.certificate_generate_send)) }
                    } else {
                        Button(
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    when (model.approve(actions)) {
                                        ApproveResult.SENT_TO_SERVER -> {
                                            busy = false
                                            onDismiss()
                                            onSentToServer()
                                            return@launch
                                        }
                                        ApproveResult.ALREADY_HANDLED -> message = alreadyHandled
                                        ApproveResult.TOO_LARGE -> message = tooLong
                                        ApproveResult.FAILED -> message = failedText
                                        // Nothing to approve (the text was emptied): nothing was called, nothing to report.
                                        null -> Unit
                                    }
                                    busy = false
                                }
                            },
                            enabled = canSubmit && !busy,
                            modifier = modifier,
                        ) { SubmitLabel(busy, stringResource(Res.string.certificate_review_approve)) }
                    }
                }
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { refuseButton() }
                        closeButton()
                        if (showPrimary) primaryButton(Modifier)
                    }
                } else {
                    // On a phone the main action gets its own full-width line.
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { refuseButton() }
                            closeButton()
                        }
                        if (showPrimary) primaryButton(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (refusing) {
        CertificateRefuseDialog(
            onDismiss = { refusing = false },
            onRefuse = { note ->
                refusing = false
                busy = true
                message = null
                scope.launch {
                    val done = model.refuse(actions, note)
                    busy = false
                    if (done) onDismiss() else message = alreadyHandled
                }
            },
        )
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            text = { Text(stringResource(Res.string.certificate_review_discard)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onDismiss()
                }) { Text(stringResource(Res.string.close)) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun SubmitLabel(busy: Boolean, text: String) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
    } else {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(6.dp))
    Text(text)
}

