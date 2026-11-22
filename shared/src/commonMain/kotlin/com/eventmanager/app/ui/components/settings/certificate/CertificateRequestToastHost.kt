package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.eventmanager.app.portal.certificate.CertificateReview
import com.eventmanager.app.portal.certificate.CertificateReviewRequest
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.AttentionToastPopup
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The first list seen within this long after mounting is taken as already known (the listener may still be loading). */
private const val SETTLE_MILLIS = 5_000L

/** Ids of the requests that call for a toast: new since [previous] and waiting for review. */
internal fun newCertificateRequests(previous: Set<String>, current: List<CertificateReviewRequest>): List<CertificateReviewRequest> =
    current.filter { it.requestId !in previous && it.status == CertificateReview.STATUS_PENDING_REVIEW }

/**
 * "{name} asks for a certificate", top right. Mounted only inside the admin area (desktop admin
 * shell, Android admin screen): never on welcome, ticketing or POS. Requests that arrive while it is
 * not mounted give no toast later; they wait in the card of the volunteer list.
 */
@Composable
fun CertificateRequestToastHost(viewModel: EventManagerViewModel) {
    val active = rememberCertificateReviewActive(viewModel)
    val requests by viewModel.certificateRequests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    var seen by remember { mutableStateOf<Set<String>?>(null) }
    val mountedAt = remember { System.currentTimeMillis() }
    var shown by remember { mutableStateOf<CertificateReviewRequest?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(requests, active) {
        if (!active) {
            seen = null
            visible = false
            shown = null
            return@LaunchedEffect
        }
        val ids = requests.map { it.requestId }.toSet()
        val previous = seen
        val settling = previous.isNullOrEmpty() && System.currentTimeMillis() - mountedAt < SETTLE_MILLIS
        seen = ids
        if (previous == null || settling) return@LaunchedEffect
        val newcomers = newCertificateRequests(previous, requests)
        try {
            for (request in newcomers) {
                shown = request
                visible = true
                delay(5_000L)
                visible = false
                // Exit tween is 300 ms; wait it out so the popup is not left mid-slide.
                delay(400L)
            }
        } finally {
            visible = false
            shown = null
        }
    }

    val request = shown ?: return
    val name = volunteerOfPortal(volunteers, request.portalUid)?.let(::displayName) ?: request.email
    AttentionToastPopup(
        visible = visible,
        title = stringResource(Res.string.certificate_toast_title),
        message = stringResource(Res.string.certificate_toast_one, name),
        icon = Icons.Default.WorkspacePremium,
        // Below the guest-form toast, which uses the same corner.
        topOffset = 96.dp,
    )
}
