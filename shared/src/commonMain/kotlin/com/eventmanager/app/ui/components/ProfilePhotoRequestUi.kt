package com.eventmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.ProfilePhotoRequest
import com.eventmanager.app.data.models.ProfilePhotoRequestState
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.profile_photo_request_body
import com.eventmanager.app.resources.profile_photo_request_creating
import com.eventmanager.app.resources.profile_photo_request_expired
import com.eventmanager.app.resources.profile_photo_request_failed
import com.eventmanager.app.resources.profile_photo_request_hint
import com.eventmanager.app.resources.profile_photo_request_received
import com.eventmanager.app.resources.profile_photo_request_title
import com.eventmanager.app.resources.profile_photo_request_waiting
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.utils.QRCodeUtils
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private const val DIALOG_POLL_MS = 3_000L
private const val AUTO_CLOSE_AFTER_APPLIED_MS = 1_600L

/**
 * Returns the click handler for "Ask the person for a photo" on a guest profile, or null when the
 * feature cannot be used (photos off, no Firebase, no public site). Shows its dialog in place.
 */
@Composable
fun rememberGuestPhotoRequestLauncher(viewModel: EventManagerViewModel?, guest: Guest): (() -> Unit)? {
    if (viewModel == null || guest.isTemporaryGuest || guest.nanoId.isBlank()) return null
    return rememberProfilePhotoRequestLauncher(
        viewModel = viewModel,
        key = "guest:${guest.nanoId}",
        displayName = guest.name,
        create = { viewModel.createProfilePhotoRequestForGuest(guest) },
    )
}

/** Volunteer variant of [rememberGuestPhotoRequestLauncher]. */
@Composable
fun rememberVolunteerPhotoRequestLauncher(viewModel: EventManagerViewModel?, volunteer: Volunteer): (() -> Unit)? {
    if (viewModel == null || volunteer.id.isBlank()) return null
    return rememberProfilePhotoRequestLauncher(
        viewModel = viewModel,
        key = "volunteer:${volunteer.id}",
        displayName = volunteer.name,
        create = { viewModel.createProfilePhotoRequestForVolunteer(volunteer) },
    )
}

@Composable
private fun rememberProfilePhotoRequestLauncher(
    viewModel: EventManagerViewModel,
    key: String,
    displayName: String,
    create: suspend () -> ProfilePhotoRequest?,
): (() -> Unit)? {
    val photosEnabled = rememberProfilePhotosUploadEnabled(viewModel)
    if (!photosEnabled || !viewModel.isProfilePhotoRequestAvailable()) return null
    var open by remember(key) { mutableStateOf(false) }
    if (open) {
        ProfilePhotoRequestDialog(
            viewModel = viewModel,
            displayName = displayName,
            create = create,
            onDismiss = { open = false },
        )
    }
    return remember(key) { { open = true } }
}

@Composable
private fun ProfilePhotoRequestDialog(
    viewModel: EventManagerViewModel,
    displayName: String,
    create: suspend () -> ProfilePhotoRequest?,
    onDismiss: () -> Unit,
) {
    var request by remember { mutableStateOf<ProfilePhotoRequest?>(null) }
    var createFailed by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ProfilePhotoRequestState>(ProfilePhotoRequestState.Waiting) }

    LaunchedEffect(Unit) {
        val created = create()
        if (created == null) createFailed = true else request = created
    }
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        while (true) {
            state = viewModel.checkProfilePhotoRequest(current)
            when (state) {
                ProfilePhotoRequestState.Applied -> {
                    delay(AUTO_CLOSE_AFTER_APPLIED_MS)
                    onDismiss()
                    return@LaunchedEffect
                }
                ProfilePhotoRequestState.Expired -> return@LaunchedEffect
                else -> delay(DIALOG_POLL_MS)
            }
        }
    }

    val url = request?.let { viewModel.profilePhotoRequestUrl(it) }.orEmpty()
    val qr: ImageBitmap? = remember(url) {
        if (url.isBlank()) null else QRCodeUtils.generateQrImageBitmap(url, 420)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.profile_photo_request_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    createFailed -> Text(
                        stringResource(Res.string.profile_photo_request_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    request == null -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text(
                            stringResource(Res.string.profile_photo_request_creating),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state == ProfilePhotoRequestState.Applied -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                        Text(
                            stringResource(Res.string.profile_photo_request_received),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    state == ProfilePhotoRequestState.Expired -> Text(
                        stringResource(Res.string.profile_photo_request_expired),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    else -> {
                        Text(
                            stringResource(Res.string.profile_photo_request_body, displayName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        qr?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.size(200.dp),
                            )
                        }
                        if (url.isNotBlank()) {
                            CopyableUrlBox(url = url)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(Res.string.profile_photo_request_waiting),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        (state as? ProfilePhotoRequestState.Failed)?.let {
                            Text(
                                it.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            stringResource(Res.string.profile_photo_request_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
