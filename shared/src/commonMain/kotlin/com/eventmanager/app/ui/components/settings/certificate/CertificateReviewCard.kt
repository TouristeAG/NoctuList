package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.FailedDirectSend
import com.eventmanager.app.portal.certificate.CertificateReview
import com.eventmanager.app.portal.certificate.CertificateReviewRequest
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.rememberGuestFormAttentionPalette
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The certificate option is effectively on: portal on, switch on and a template saved. */
@Composable
fun rememberCertificateReviewActive(viewModel: EventManagerViewModel?): Boolean {
    if (viewModel == null) return false
    val portal by viewModel.volunteerPortalEnabled.collectAsState()
    val on by viewModel.volunteerPortalCertificateEnabled.collectAsState()
    val saved by viewModel.certificateTemplateSaved.collectAsState()
    return portal && on && saved
}

/** The profile of a portal account. Matched by `portalUid` only. */
internal fun volunteerOfPortal(volunteers: List<Volunteer>, portalUid: String): Volunteer? =
    if (portalUid.isBlank()) null else volunteers.firstOrNull { it.portalUid == portalUid }

/** "Certificates to review" at the top of the volunteer list (admin only). Nothing when there is nothing to do. */
@Composable
fun CertificateReviewCard(
    viewModel: EventManagerViewModel,
    isPhone: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!rememberCertificateReviewActive(viewModel)) return
    val requests by viewModel.certificateRequests.collectAsState()
    val failed by viewModel.failedDirectSends.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    val scope = rememberCoroutineScope()
    var reviewing by remember { mutableStateOf<CertificateReviewTarget.Request?>(null) }
    var flash by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val sentText = stringResource(Res.string.certificate_review_sent_to_server)
    val retryFailed = stringResource(Res.string.certificate_review_already_handled)

    CertificateReviewCardContent(
        requests = requests,
        failed = failed,
        volunteerOf = { volunteerOfPortal(volunteers, it) },
        isPhone = isPhone,
        modifier = modifier,
        onOpen = { request -> reviewing = CertificateReviewTarget.Request(request, volunteerOfPortal(volunteers, request.portalUid)) },
        onRetry = { request -> scope.launch { if (!viewModel.retryCertificateRequest(request.requestId)) flash = retryFailed to true } },
        onDismissFailed = { send -> scope.launch { viewModel.dismissDirectCertificate(send.approvalId) } },
    )

    reviewing?.let { target ->
        CertificateReviewDialog(
            target = target,
            viewModel = viewModel,
            onDismiss = { reviewing = null },
            onSentToServer = { flash = sentText to false },
        )
    }
    flash?.let { shown ->
        LaunchedEffect(shown) {
            delay(3_000)
            flash = null
        }
        ShortToast(shown.first, isError = shown.second)
    }
}

@Composable
private fun ShortToast(text: String, isError: Boolean) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, with(density) { (-96).dp.roundToPx() }),
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun CertificateReviewCardContent(
    requests: List<CertificateReviewRequest>,
    failed: List<FailedDirectSend>,
    volunteerOf: (String) -> Volunteer?,
    isPhone: Boolean,
    onOpen: (CertificateReviewRequest) -> Unit,
    onRetry: (CertificateReviewRequest) -> Unit,
    onDismissFailed: (FailedDirectSend) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (requests.isEmpty() && failed.isEmpty()) return
    val palette = rememberGuestFormAttentionPalette()
    Card(
        modifier = modifier.fillMaxWidth().padding(top = if (isPhone) 10.dp else 12.dp),
        shape = RoundedCornerShape(if (isPhone) 14.dp else 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = palette.container, contentColor = palette.onContainer),
        border = BorderStroke(1.dp, palette.border),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(palette.accent))
            Column(Modifier.weight(1f).padding(vertical = if (isPhone) 10.dp else 12.dp)) {
                Row(
                    Modifier.padding(horizontal = if (isPhone) 12.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(if (isPhone) 34.dp else 38.dp).clip(CircleShape).background(palette.iconBg)
                            .border(1.dp, palette.border.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        stringResource(Res.string.certificate_review_card_title, requests.size + failed.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent,
                    )
                }
                Spacer(Modifier.height(6.dp))
                // A long list scrolls inside the card instead of pushing the volunteers away.
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    var first = true
                    for (request in requests) {
                        if (!first) HorizontalDivider(color = palette.border.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 14.dp))
                        first = false
                        RequestRow(request, volunteerOf(request.portalUid), isPhone, palette.accent, palette.muted, onOpen, onRetry)
                    }
                    for (send in failed) {
                        if (!first) HorizontalDivider(color = palette.border.copy(alpha = 0.4f), modifier = Modifier.padding(horizontal = 14.dp))
                        first = false
                        FailedRow(send, isPhone, palette.muted, onDismissFailed)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    request: CertificateReviewRequest,
    volunteer: Volunteer?,
    isPhone: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    onOpen: (CertificateReviewRequest) -> Unit,
    onRetry: (CertificateReviewRequest) -> Unit,
) {
    val reviewable = request.status == CertificateReview.STATUS_PENDING_REVIEW
    val status: @Composable () -> Unit = {
        if (request.stuck) {
            Text(stringResource(Res.string.certificate_review_stuck), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onRetry(request) }) { Text(stringResource(Res.string.certificate_review_retry)) }
        } else {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
            Text(stringResource(Res.string.certificate_review_sending), style = MaterialTheme.typography.labelMedium, color = muted)
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (reviewable) Modifier.clickable { onOpen(request) } else Modifier)
            .padding(horizontal = if (isPhone) 12.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                volunteer?.let(::displayName) ?: request.email,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val requested = stringResource(Res.string.certificate_review_requested_on, formatCertificateMillis(request.requestedAt))
            val period = stringResource(Res.string.certificate_review_period_value, formatCertificateDay(request.fromDay), formatCertificateDay(request.toDay))
            if (isPhone) {
                Text(requested, style = MaterialTheme.typography.labelSmall, color = muted)
                Text(period.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = muted)
            } else {
                Text("$requested · $period", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (volunteer == null) {
                Text(stringResource(Res.string.certificate_review_no_profile), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (reviewable && request.lastReason.isNotEmpty()) {
                Text(
                    stringResource(Res.string.certificate_review_sent_back, reasonText(request.lastReason)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (isPhone && !reviewable) {
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { status() }
            }
        }
        when {
            reviewable -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = accent.copy(alpha = 0.85f))
            !isPhone -> status()
        }
    }
}

@Composable
private fun FailedRow(send: FailedDirectSend, isPhone: Boolean, muted: androidx.compose.ui.graphics.Color, onDismiss: (FailedDirectSend) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (isPhone) 12.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.certificate_review_send_failed_to, send.name),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(reasonText(send.reason), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        TextButton(onClick = { onDismiss(send) }) { Text(stringResource(Res.string.certificate_review_dismiss)) }
    }
}

/** "Generate a certificate" on a volunteer's panel: while the option is on and the volunteer has a past job. */
@Composable
fun VolunteerCertificateButton(viewModel: EventManagerViewModel?, volunteer: Volunteer, modifier: Modifier = Modifier) {
    if (viewModel == null || !rememberCertificateReviewActive(viewModel)) return
    val jobs by viewModel.jobs.collectAsState()
    if (!remember(volunteer.id, jobs) { CertificateReviewModel.hasPastJob(volunteer, jobs) }) return
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Default.WorkspacePremium, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.certificate_generate))
    }
    if (open) {
        CertificateReviewDialog(
            target = CertificateReviewTarget.Direct(volunteer),
            viewModel = viewModel,
            onDismiss = { open = false },
        )
    }
}
