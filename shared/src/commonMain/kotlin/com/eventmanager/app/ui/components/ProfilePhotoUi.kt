package com.eventmanager.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.add_profile_photo
import com.eventmanager.app.resources.change_profile_photo
import com.eventmanager.app.resources.download_profile_photo
import com.eventmanager.app.resources.profile_photo_cd
import com.eventmanager.app.resources.profile_photo_preview_hint
import com.eventmanager.app.resources.profile_photo_preview_label
import com.eventmanager.app.resources.remove_profile_photo
import com.eventmanager.app.resources.remove_profile_photo_confirm
import com.eventmanager.app.resources.share_profile_photo
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.isStoredProfilePhotoRef
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.ui.utils.isTablet
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.utils.ProfilePhotoDisplayQuality
import com.eventmanager.app.utils.ProfilePhotoImageCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Follows the org-wide profile-photo flag so upload UI appears on every device after an admin enables it. */
@Composable
fun rememberProfilePhotosUploadEnabled(viewModel: EventManagerViewModel?): Boolean {
    val fallback = remember { MutableStateFlow(false) }
    val enabled by (viewModel?.profilePhotosUploadEnabled ?: fallback).collectAsState()
    return enabled
}

/** Open profiles keep a snapshot; photo uploads write Room and must show on the same panel. */
@Composable
fun rememberLiveGuest(snapshot: Guest, viewModel: EventManagerViewModel?): Guest {
    val fallback = remember { MutableStateFlow(emptyList<Guest>()) }
    val guests by (viewModel?.guests ?: fallback).collectAsState()
    return guests.find { it.nanoId == snapshot.nanoId } ?: snapshot
}

@Composable
fun rememberLiveVolunteer(snapshot: Volunteer, viewModel: EventManagerViewModel?): Volunteer {
    val fallback = remember { MutableStateFlow(emptyList<Volunteer>()) }
    val volunteers by (viewModel?.volunteers ?: fallback).collectAsState()
    return volunteers.find { it.id == snapshot.id } ?: snapshot
}

/** Brand circles match guest/volunteer lists; OnDark is for green/red scanner screens. */
enum class ProfileAvatarTone {
    Brand,
    OnDark,
}

@Composable
fun ProfilePhotoHeaderAvatar(
    name: String,
    photoUrl: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tone: ProfileAvatarTone = ProfileAvatarTone.Brand,
    canExport: Boolean = false,
    canManage: Boolean = false,
    fullQuality: Boolean = false,
    photoPath: String = "",
    onUpload: (ByteArray) -> Unit = {},
    onRemove: () -> Unit = {},
) {
    var showFull by remember { mutableStateOf(false) }
    val pick = rememberProfilePhotoPicker(onUpload)
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val displayUrl = photoUrl.takeIf { it.isStoredProfilePhotoRef() }.orEmpty()
    val displayPath = photoPath.takeIf { it.isStoredProfilePhotoRef() }.orEmpty()
    val hasPhoto = displayUrl.isNotBlank() || displayPath.isNotBlank()
    val exportName = "${name.trim().ifBlank { "profile" }}.jpg"
    ProfilePhotoAvatar(
        name = name,
        photoUrl = displayUrl,
        photoPath = displayPath,
        modifier = modifier,
        size = size,
        tone = tone,
        fullQuality = fullQuality,
        onClick = { showFull = true },
    )
    if (showFull) {
        ProfilePhotoFullscreen(
            name = name,
            photoUrl = displayUrl,
            photoPath = displayPath,
            onDismiss = { showFull = false },
            canExport = canExport && hasPhoto,
            canManage = canManage && hasPhoto,
            onShare = {
                scope.launch {
                    ProfilePhotoExport.share(platformContext, displayUrl, exportName, displayPath)
                }
            },
            onDownload = {
                scope.launch {
                    ProfilePhotoExport.download(platformContext, displayUrl, exportName, displayPath)
                }
            },
            onChange = {
                showFull = false
                pick()
            },
            onDelete = onRemove,
        )
    }
}

@Composable
fun ProfileRemoteImage(
    url: String,
    modifier: Modifier,
    contentDescription: String?,
    quality: ProfilePhotoDisplayQuality = ProfilePhotoDisplayQuality.Thumbnail,
    storagePath: String = "",
) {
    if (url.isBlank() && storagePath.isBlank()) return
    val platformContext = LocalPlatformContext.current
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    val cacheId = remember(url, storagePath) {
        url.trim().ifBlank { storagePath.trim() }
    }
    val revisionFlow = remember(cacheId) { ProfilePhotoImageCache.revisionState(cacheId) }
    val cacheRevision by revisionFlow.collectAsState()
    LaunchedEffect(url, storagePath, quality, cacheRevision) {
        bytes = ProfilePhotoImageCache.load(platformContext, url, quality, storagePath)
    }
    val data = bytes ?: return
    ProfileDecodedImage(
        bytes = data,
        modifier = modifier,
        contentDescription = contentDescription,
    )
}

@Composable
internal expect fun ProfileDecodedImage(
    bytes: ByteArray,
    modifier: Modifier,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Crop,
    colorFilter: ColorFilter? = null,
)

@Composable
expect fun rememberProfilePhotoPicker(onPicked: (ByteArray) -> Unit): () -> Unit

private fun profilePhotoInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.first().uppercase()
}

@Composable
fun ProfilePhotoAvatar(
    name: String,
    photoUrl: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tone: ProfileAvatarTone = ProfileAvatarTone.Brand,
    onClick: (() -> Unit)? = null,
    fullQuality: Boolean = false,
    photoPath: String = "",
    pendingBytes: ByteArray? = null,
) {
    val initial = remember(name) { profilePhotoInitial(name) }
    val background = when (tone) {
        ProfileAvatarTone.Brand -> MaterialTheme.colorScheme.primary
        ProfileAvatarTone.OnDark -> Color.White.copy(alpha = 0.22f)
    }
    val initialColor = when (tone) {
        ProfileAvatarTone.Brand -> MaterialTheme.colorScheme.onPrimary
        ProfileAvatarTone.OnDark -> Color.White
    }
    val ring = when (tone) {
        ProfileAvatarTone.Brand -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f)
        ProfileAvatarTone.OnDark -> Color.White.copy(alpha = 0.45f)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(BorderStroke(1.5.dp, ring), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = when {
                size >= 72.dp -> MaterialTheme.typography.headlineMedium
                size >= 48.dp -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
            color = initialColor,
        )
        when {
            pendingBytes != null -> {
                ProfileDecodedImage(
                    bytes = pendingBytes,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentDescription = stringResource(Res.string.profile_photo_cd),
                )
            }
            photoUrl.isStoredProfilePhotoRef() || photoPath.isStoredProfilePhotoRef() -> {
                ProfileRemoteImage(
                    url = photoUrl,
                    storagePath = photoPath,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentDescription = stringResource(Res.string.profile_photo_cd),
                    quality = ProfilePhotoDisplayQuality.Thumbnail,
                )
                if (fullQuality) {
                    ProfileRemoteImage(
                        url = photoUrl,
                        storagePath = photoPath,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentDescription = stringResource(Res.string.profile_photo_cd),
                        quality = ProfilePhotoDisplayQuality.Full,
                    )
                }
            }
        }
    }
}

@Composable
fun ProfilePhotoFullscreen(
    name: String,
    photoUrl: String,
    onDismiss: () -> Unit,
    canExport: Boolean = false,
    canManage: Boolean = false,
    photoPath: String = "",
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onChange: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val hasPhoto = photoUrl.isStoredProfilePhotoRef() || photoPath.isStoredProfilePhotoRef()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onDismiss),
        ) {
            val isDesktop = LocalPlatformContext.current.isDesktop
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                val chromeHeight = if (hasPhoto && (canExport || canManage)) 200.dp else 96.dp
                val available = minOf(maxWidth, (maxHeight - chromeHeight).coerceAtLeast(160.dp))
                val photoSize = if (isDesktop) {
                    (available * 0.92f).coerceAtMost(760.dp)
                } else {
                    (available * 0.72f).coerceIn(240.dp, 360.dp)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .clickable(enabled = false, onClick = {}),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                ProfilePhotoAvatar(
                    name = name,
                    photoUrl = photoUrl,
                    photoPath = photoPath,
                    size = photoSize,
                    tone = ProfileAvatarTone.OnDark,
                    fullQuality = true,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (hasPhoto && (canExport || canManage)) {
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canExport) {
                            onShare?.let { share ->
                                OutlinedButton(
                                    onClick = share,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.share_profile_photo))
                                }
                            }
                            onDownload?.let { download ->
                                OutlinedButton(
                                    onClick = download,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.download_profile_photo))
                                }
                            }
                        }
                    }
                    if (canManage) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            onChange?.let { change ->
                                OutlinedButton(
                                    onClick = change,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.change_profile_photo))
                                }
                            }
                            onDelete?.let { delete ->
                                OutlinedButton(
                                    onClick = { confirmDelete = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                                    border = BorderStroke(1.dp, Color(0xFFFF8A80).copy(alpha = 0.7f)),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.remove_profile_photo))
                                }
                            }
                        }
                    }
                }
            }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cancel), tint = Color.White)
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.remove_profile_photo)) },
            text = { Text(stringResource(Res.string.remove_profile_photo_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                    onDismiss()
                }) {
                    Text(stringResource(Res.string.remove_profile_photo))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
fun ProfilePhotoActionButtons(
    hasPhoto: Boolean,
    enabled: Boolean,
    onAddOrChange: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    sideBySide: Boolean = false,
) {
    if (!enabled) return
    var confirmDelete by remember { mutableStateOf(false) }
    val addLabel = if (hasPhoto) {
        stringResource(Res.string.change_profile_photo)
    } else {
        stringResource(Res.string.add_profile_photo)
    }
    val addContent: @Composable () -> Unit = {
        Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(addLabel)
    }
    val removeContent: @Composable () -> Unit = {
        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(Res.string.remove_profile_photo))
    }
    if (sideBySide) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onAddOrChange, modifier = Modifier.weight(1f), content = { addContent() })
            if (hasPhoto) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    content = { removeContent() },
                )
            }
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAddOrChange,
                modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
                content = { addContent() },
            )
            if (hasPhoto) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    content = { removeContent() },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.remove_profile_photo)) },
            text = { Text(stringResource(Res.string.remove_profile_photo_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onRemove()
                }) {
                    Text(stringResource(Res.string.remove_profile_photo))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
fun ProfilePhotoFormPicker(
    enabled: Boolean,
    currentUrl: String,
    name: String,
    pendingBytes: ByteArray?,
    onPicked: (ByteArray) -> Unit,
    onClearPending: () -> Unit,
    onRemoveExisting: (() -> Unit)? = null,
    currentPath: String = "",
) {
    if (!enabled) return
    val pick = rememberProfilePhotoPicker(onPicked)
    val platformContext = LocalPlatformContext.current
    val useStackedLayout = platformContext.isDesktop || isTablet()
    val hasPhoto = pendingBytes != null ||
        currentUrl.isStoredProfilePhotoRef() ||
        currentPath.isStoredProfilePhotoRef()
    val avatarSize = if (useStackedLayout) 96.dp else 64.dp
    val previewLabel = stringResource(Res.string.profile_photo_preview_label)
    val previewHint = stringResource(Res.string.profile_photo_preview_hint)
    val avatar = @Composable {
        ProfilePhotoAvatar(
            name = name,
            photoUrl = currentUrl,
            photoPath = currentPath,
            pendingBytes = pendingBytes,
            size = avatarSize,
            onClick = pick,
        )
    }
    val actions = @Composable {
        ProfilePhotoActionButtons(
            hasPhoto = hasPhoto,
            enabled = true,
            onAddOrChange = pick,
            onRemove = {
                if (pendingBytes != null) onClearPending() else onRemoveExisting?.invoke()
            },
            fillWidth = !useStackedLayout,
            sideBySide = useStackedLayout && hasPhoto,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(16.dp),
        horizontalAlignment = if (useStackedLayout) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (useStackedLayout) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = previewLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = previewHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (useStackedLayout) TextAlign.Center else TextAlign.Start,
            )
        }
        if (useStackedLayout) {
            avatar()
            actions()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                avatar()
                Box(modifier = Modifier.weight(1f)) {
                    actions()
                }
            }
        }
    }
}

/**
 * Scanner identity: large photo on the left, name and rank to the right —
 * same header recipe as guest/volunteer profiles, sized to recognize the person.
 */
@Composable
fun ScannerIdentityCard(
    name: String,
    photoUrl: String,
    orgLabel: @Composable () -> Unit,
    extraLines: List<String>,
    lightOnDark: Boolean,
    modifier: Modifier = Modifier,
    largeName: Boolean = false,
    photoPath: String = "",
) {
    val textColor = if (lightOnDark) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (lightOnDark) Color.White.copy(alpha = 0.82f) else MaterialTheme.colorScheme.onSurfaceVariant
    val extras = extraLines.filter { it.isNotBlank() }
    val photoSize = when {
        !lightOnDark -> 64.dp
        largeName -> 176.dp
        else -> 128.dp
    }
    Row(
        modifier = if (lightOnDark) {
            modifier.widthIn(max = if (largeName) 720.dp else 560.dp)
        } else {
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (largeName) 24.dp else 16.dp),
    ) {
        ProfilePhotoHeaderAvatar(
            name = name,
            photoUrl = photoUrl,
            photoPath = photoPath,
            size = photoSize,
            tone = if (lightOnDark) ProfileAvatarTone.OnDark else ProfileAvatarTone.Brand,
            canExport = false,
            canManage = false,
            fullQuality = lightOnDark,
        )
        Column(
            modifier = Modifier.widthIn(max = if (largeName) 400.dp else 280.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = name,
                style = when {
                    largeName -> MaterialTheme.typography.headlineLarge
                    lightOnDark -> MaterialTheme.typography.headlineSmall
                    else -> MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            orgLabel()
            extras.forEach { line ->
                Text(
                    text = line,
                    style = if (lightOnDark) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = muted,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
