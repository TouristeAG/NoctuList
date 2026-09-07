
package com.eventmanager.app.ui.screens

import android.net.Uri
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.border
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import android.webkit.WebView
import com.eventmanager.app.data.sync.GmailAuthService
import kotlinx.coroutines.launch
import android.widget.Toast
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.eventmanager.app.data.sync.FileManager
import com.eventmanager.app.data.sync.GoogleSheetsConfig
import com.eventmanager.app.data.sync.JsonKeyInfo
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.formatDateTime
import com.eventmanager.app.data.utils.AppIconManager
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.ui.platform.AppAppearanceState
import com.eventmanager.app.ui.components.FactoryResetSettingsSection
import com.eventmanager.app.ui.components.TemporaryGuestAccessSettingsSection
import com.eventmanager.app.ui.components.BackgroundAnimationSettingsSection
import com.eventmanager.app.ui.components.BackgroundAnimationSettingsTarget
import com.eventmanager.app.ui.components.ColorThemePicker
import com.eventmanager.app.ui.components.ScrollBehaviorPicker
import com.eventmanager.app.ui.components.ThemeModePicker
import com.eventmanager.app.ui.components.ActiveVolunteersDialog
import com.eventmanager.app.ui.components.CleanupInactiveVolunteersDialog
import com.eventmanager.app.ui.components.SyncStatusDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import kotlin.math.roundToInt
import com.eventmanager.app.R
import com.eventmanager.app.platform.AppBuildInfo
import com.eventmanager.app.platform.installedVersionName
import com.eventmanager.app.ui.theme.ThemeMode
import com.eventmanager.app.ui.theme.ColorThemes
import com.eventmanager.app.ui.components.ResolutionScaleSlider
import com.eventmanager.app.ui.components.restartApp
import com.eventmanager.app.ui.components.RetroSynthwaveGameDialog
import com.eventmanager.app.ui.components.HextrisGameDialog
import com.eventmanager.app.ui.components.PizzaUndeliveryGameDialog
import com.eventmanager.app.ui.components.ScrollGameDialog
import com.eventmanager.app.ui.components.CatculusGameDialog
import com.eventmanager.app.ui.components.WendolVillageGameDialog
import com.eventmanager.app.utils.ImageUtils
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.eventmanager.app.ui.components.ScannerMatch
import com.eventmanager.app.ui.components.QRScannerDialog
import com.eventmanager.app.ui.components.BiometricAdminVerificationDialog
import com.eventmanager.app.ui.components.BiometricOrgEnrollmentWizard
import com.eventmanager.app.data.sync.BiometricAdminOrgEnrollment
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.FirebaseOrgAbbreviation
import com.eventmanager.app.ui.components.toBiometricAdminProfileLink

/** Full admin settings vs. Billeterie subset (appearance, localization, animations, app info). */
// SettingsScreenVariant moved to commonMain SettingsScreenVariant.kt

import com.eventmanager.app.ui.screens.SettingsScreenVariant
private data class IconOption(
    val style: String,
    val nameResId: Int,
    val toastResId: Int,
    val iconResId: Int,
    val backgroundColor: Color
)

private data class CustomThemeRole(
    val key: String,
    val label: String
)

private fun customThemeRoleDescription(roleKey: String): String {
    return when (roleKey) {
        "primary" -> "Couleur principale des actions importantes."
        "onPrimary" -> "Texte/icone affiches sur la couleur Primary."
        "primaryContainer" -> "Fond secondaire base sur Primary."
        "onPrimaryContainer" -> "Texte/icone sur Primary Container."
        "secondary" -> "Couleur d'accent secondaire."
        "onSecondary" -> "Texte/icone sur Secondary."
        "secondaryContainer" -> "Fond secondaire lie a Secondary."
        "onSecondaryContainer" -> "Texte/icone sur Secondary Container."
        "tertiary" -> "Troisieme accent pour elements distincts."
        "onTertiary" -> "Texte/icone sur Tertiary."
        "tertiaryContainer" -> "Fond lie a Tertiary."
        "onTertiaryContainer" -> "Texte/icone sur Tertiary Container."
        "error" -> "Couleur des erreurs et alertes."
        "onError" -> "Texte/icone sur Error."
        "errorContainer" -> "Fond de messages d'erreur."
        "onErrorContainer" -> "Texte/icone sur Error Container."
        "background" -> "Fond general de l'application."
        "onBackground" -> "Texte principal sur Background."
        "surface" -> "Fond des cartes, feuilles et panneaux."
        "onSurface" -> "Texte principal sur Surface."
        "surfaceVariant" -> "Fond alternatif de surface."
        "onSurfaceVariant" -> "Texte secondaire sur Surface Variant."
        "outline" -> "Contours principaux."
        "outlineVariant" -> "Contours secondaires discrets."
        "scrim" -> "Voile d'assombrissement derriere dialogs."
        "inverseSurface" -> "Surface utilisee en mode inverse."
        "inverseOnSurface" -> "Texte sur Inverse Surface."
        "inversePrimary" -> "Primary utilisee en contexte inverse."
        "surfaceDim" -> "Version plus sombre de Surface."
        "surfaceBright" -> "Version plus claire de Surface."
        "surfaceContainerLowest" -> "Container surface le plus leger."
        "surfaceContainerLow" -> "Container surface leger."
        "surfaceContainer" -> "Container surface standard."
        "surfaceContainerHigh" -> "Container surface marque."
        "surfaceContainerHighest" -> "Container surface le plus marque."
        else -> "Role de couleur Material."
    }
}

private val customThemeRoles = listOf(
    CustomThemeRole("primary", "Primary"),
    CustomThemeRole("onPrimary", "On Primary"),
    CustomThemeRole("primaryContainer", "Primary Container"),
    CustomThemeRole("onPrimaryContainer", "On Primary Container"),
    CustomThemeRole("secondary", "Secondary"),
    CustomThemeRole("onSecondary", "On Secondary"),
    CustomThemeRole("secondaryContainer", "Secondary Container"),
    CustomThemeRole("onSecondaryContainer", "On Secondary Container"),
    CustomThemeRole("tertiary", "Tertiary"),
    CustomThemeRole("onTertiary", "On Tertiary"),
    CustomThemeRole("tertiaryContainer", "Tertiary Container"),
    CustomThemeRole("onTertiaryContainer", "On Tertiary Container"),
    CustomThemeRole("error", "Error"),
    CustomThemeRole("onError", "On Error"),
    CustomThemeRole("errorContainer", "Error Container"),
    CustomThemeRole("onErrorContainer", "On Error Container"),
    CustomThemeRole("background", "Background"),
    CustomThemeRole("onBackground", "On Background"),
    CustomThemeRole("surface", "Surface"),
    CustomThemeRole("onSurface", "On Surface"),
    CustomThemeRole("surfaceVariant", "Surface Variant"),
    CustomThemeRole("onSurfaceVariant", "On Surface Variant"),
    CustomThemeRole("outline", "Outline"),
    CustomThemeRole("outlineVariant", "Outline Variant"),
    CustomThemeRole("scrim", "Scrim"),
    CustomThemeRole("inverseSurface", "Inverse Surface"),
    CustomThemeRole("inverseOnSurface", "Inverse On Surface"),
    CustomThemeRole("inversePrimary", "Inverse Primary"),
    CustomThemeRole("surfaceDim", "Surface Dim"),
    CustomThemeRole("surfaceBright", "Surface Bright"),
    CustomThemeRole("surfaceContainerLowest", "Surface Container Lowest"),
    CustomThemeRole("surfaceContainerLow", "Surface Container Low"),
    CustomThemeRole("surfaceContainer", "Surface Container"),
    CustomThemeRole("surfaceContainerHigh", "Surface Container High"),
    CustomThemeRole("surfaceContainerHighest", "Surface Container Highest")
)

private fun getBaseCustomThemeRoleColor(isDark: Boolean, role: String): Color {
    val scheme = if (isDark) ColorThemes.SUNSET_MIST.darkColors else ColorThemes.SUNSET_MIST.lightColors
    return when (role) {
        "primary" -> scheme.primary
        "onPrimary" -> scheme.onPrimary
        "primaryContainer" -> scheme.primaryContainer
        "onPrimaryContainer" -> scheme.onPrimaryContainer
        "secondary" -> scheme.secondary
        "onSecondary" -> scheme.onSecondary
        "secondaryContainer" -> scheme.secondaryContainer
        "onSecondaryContainer" -> scheme.onSecondaryContainer
        "tertiary" -> scheme.tertiary
        "onTertiary" -> scheme.onTertiary
        "tertiaryContainer" -> scheme.tertiaryContainer
        "onTertiaryContainer" -> scheme.onTertiaryContainer
        "error" -> scheme.error
        "onError" -> scheme.onError
        "errorContainer" -> scheme.errorContainer
        "onErrorContainer" -> scheme.onErrorContainer
        "background" -> scheme.background
        "onBackground" -> scheme.onBackground
        "surface" -> scheme.surface
        "onSurface" -> scheme.onSurface
        "surfaceVariant" -> scheme.surfaceVariant
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        "outline" -> scheme.outline
        "outlineVariant" -> scheme.outlineVariant
        "scrim" -> scheme.scrim
        "inverseSurface" -> scheme.inverseSurface
        "inverseOnSurface" -> scheme.inverseOnSurface
        "inversePrimary" -> scheme.inversePrimary
        "surfaceDim" -> scheme.surfaceDim
        "surfaceBright" -> scheme.surfaceBright
        "surfaceContainerLowest" -> scheme.surfaceContainerLowest
        "surfaceContainerLow" -> scheme.surfaceContainerLow
        "surfaceContainer" -> scheme.surfaceContainer
        "surfaceContainerHigh" -> scheme.surfaceContainerHigh
        "surfaceContainerHighest" -> scheme.surfaceContainerHighest
        else -> scheme.primary
    }
}

@Composable
private fun CustomThemeEditorDialog(
    settingsManager: SettingsManager,
    onColorChanged: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var editDark by remember { mutableStateOf(false) }
    var selectedRoleKey by remember { mutableStateOf("primary") }
    var editorVersion by remember { mutableIntStateOf(0) }

    val currentColor = remember(editDark, selectedRoleKey, editorVersion) {
        val fallback = getBaseCustomThemeRoleColor(editDark, selectedRoleKey).toArgb()
        Color(settingsManager.getCustomThemeColor(editDark, selectedRoleKey, fallback))
    }
    val startHsv = remember(currentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColor.toArgb(), hsv)
        hsv
    }
    var hue by remember(currentColor) { mutableFloatStateOf(startHsv[0]) }
    var saturation by remember(currentColor) { mutableFloatStateOf(startHsv[1]) }
    var value by remember(currentColor) { mutableFloatStateOf(startHsv[2]) }

    val previewColor = remember(hue, saturation, value) {
        Color(
            android.graphics.Color.HSVToColor(
                floatArrayOf(
                    hue.coerceIn(0f, 360f),
                    saturation.coerceIn(0f, 1f),
                    value.coerceIn(0f, 1f)
                )
            )
        )
    }

    fun persistColor() {
        settingsManager.saveCustomThemeColor(editDark, selectedRoleKey, previewColor.toArgb())
        editorVersion++
        onColorChanged()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = context.getString(R.string.custom_theme_editor_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { editDark = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!editDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) { Text(context.getString(R.string.theme_light)) }
                    Button(
                        onClick = { editDark = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (editDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) { Text(context.getString(R.string.theme_dark)) }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    customThemeRoles.forEach { role ->
                        OutlinedButton(
                            onClick = { selectedRoleKey = role.key },
                            border = BorderStroke(
                                1.dp,
                                if (selectedRoleKey == role.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Text(role.label, maxLines = 1)
                        }
                    }
                }
                Text(
                    text = customThemeRoles.firstOrNull { it.key == selectedRoleKey }?.let {
                        "${it.label}: ${customThemeRoleDescription(it.key)}"
                    } ?: customThemeRoleDescription(selectedRoleKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            previewColor
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp)
                        )
                )

                Text(context.getString(R.string.theme_hue_label, hue.roundToInt()))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Red,
                                        Color(0xFFFFFF00),
                                        Color.Green,
                                        Color.Cyan,
                                        Color.Blue,
                                        Color.Magenta,
                                        Color.Red
                                    )
                                )
                            )
                    )
                    Slider(
                        value = hue,
                        onValueChange = { hue = it; persistColor() },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                Text(context.getString(R.string.theme_saturation_label, (saturation * 100f).roundToInt()))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                ) {
                    val vividHue = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, value.coerceIn(0.15f, 1f))))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFB0B0B0), vividHue)))
                    )
                    Slider(
                        value = saturation,
                        onValueChange = { saturation = it; persistColor() },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                Text(context.getString(R.string.theme_luminosity_label, (value * 100f).roundToInt()))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                ) {
                    val fullColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), 1f)))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Brush.horizontalGradient(listOf(Color.Black, fullColor)))
                    )
                    Slider(
                        value = value,
                        onValueChange = { value = it; persistColor() },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                Text(
                    text = "Hex: #${String.format("%08X", previewColor.toArgb())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(context.getString(R.string.done))
                }
            }
        }
    }
}

// Helper composable for expandable settings category
@Composable
private fun ExpandableSettingsCategory(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

/**
 * Optimized Email Settings composable - extracted to reduce recomposition scope
 * and improve performance by only loading settings when needed.
 * Now includes tabbed interface for Volunteer and Guest email settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmailSettingsContent(
    settingsManager: SettingsManager,
    context: android.content.Context,
    onSyncedSettingChanged: () -> Unit = {},
    editable: Boolean = true,
    /** The artist guest list tab only exists on Firebase, like the rest of the feature. */
    temporaryFeaturesEnabled: Boolean = false,
    /** Keep the e-mail logo and the institution / form logo in sync. */
    onInstitutionLogoBytes: (ByteArray?) -> Unit = {},
) {
    // Cache string resources to avoid repeated lookups
    val strings = remember {
        EmailSettingsStrings(
            subjectDefault = context.getString(R.string.email_subject_default),
            contentBeforeDefault = context.getString(R.string.email_content_before_default),
            contentAfterDefault = context.getString(R.string.email_content_after_default),
            signatureDefault = context.getString(R.string.email_signature_default),
            title = context.getString(R.string.email_qr_settings_title),
            description = context.getString(R.string.email_qr_settings_description),
            subjectLabel = context.getString(R.string.email_subject_label),
            subjectHint = context.getString(R.string.email_subject_hint),
            contentBeforeLabel = context.getString(R.string.email_content_before_label),
            contentBeforeHint = context.getString(R.string.email_content_before_hint),
            includeQrLabel = context.getString(R.string.email_include_qr_label),
            includeQrDescription = context.getString(R.string.email_include_qr_description),
            contentAfterLabel = context.getString(R.string.email_content_after_label),
            contentAfterHint = context.getString(R.string.email_content_after_hint),
            signatureLabel = context.getString(R.string.email_signature_label),
            signatureHint = context.getString(R.string.email_signature_hint),
            associationNameLabel = context.getString(R.string.email_association_name_label),
            associationNameHint = context.getString(R.string.email_association_name_hint),
            associationNameDescription = context.getString(R.string.email_association_name_description),
            includeDigitalWalletPassLabel = context.getString(R.string.email_include_digital_wallet_pass_label),
            includeDigitalWalletPassDescription = context.getString(R.string.email_include_digital_wallet_pass_description),
            includeLogoLabel = context.getString(R.string.email_include_logo_label),
            includeLogoDescription = context.getString(R.string.email_include_logo_description),
            logoPreview = context.getString(R.string.email_logo_preview),
            logoChange = context.getString(R.string.email_logo_change),
            logoRemove = context.getString(R.string.email_logo_remove),
            logoUpload = context.getString(R.string.email_logo_upload),
            resetDefaults = context.getString(R.string.email_reset_to_defaults)
        )
    }
    
    // Guest email defaults
    val guestStrings = remember {
        GuestEmailSettingsStrings(
            subjectDefault = context.getString(R.string.guest_email_subject_default),
            contentBeforeDefault = context.getString(R.string.guest_email_content_before_default),
            contentAfterDefault = context.getString(R.string.guest_email_content_after_default)
        )
    }
    
    // Temporary guest (artist guest list) defaults
    val tempStrings = remember {
        GuestEmailSettingsStrings(
            subjectDefault = context.getString(R.string.temp_guest_email_subject_default),
            contentBeforeDefault = context.getString(R.string.temp_guest_email_content_before_default),
            contentAfterDefault = context.getString(R.string.temp_guest_email_content_after_default)
        )
    }

    // Tab state: 0 = Volunteer, 1 = Guest, 2 = artist guest list (Firebase only)
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabLabels = remember(temporaryFeaturesEnabled) {
        buildList {
            add(context.getString(R.string.email_tab_volunteer))
            add(context.getString(R.string.email_tab_guest))
            if (temporaryFeaturesEnabled) add(context.getString(R.string.temp_guest_email_tab))
        }
    }
    
    // Volunteer email settings
    var volunteerSubject by remember { mutableStateOf(strings.subjectDefault) }
    var volunteerContentBefore by remember { mutableStateOf(strings.contentBeforeDefault) }
    var volunteerIncludeQr by remember { mutableStateOf(true) }
    var volunteerContentAfter by remember { mutableStateOf(strings.contentAfterDefault) }
    
    // Guest email settings
    var guestSubject by remember { mutableStateOf(guestStrings.subjectDefault) }
    var guestContentBefore by remember { mutableStateOf(guestStrings.contentBeforeDefault) }
    var guestIncludeQr by remember { mutableStateOf(true) }
    var guestContentAfter by remember { mutableStateOf(guestStrings.contentAfterDefault) }

    // Artist guest list email settings
    var tempSubject by remember { mutableStateOf(tempStrings.subjectDefault) }
    var tempContentBefore by remember { mutableStateOf(tempStrings.contentBeforeDefault) }
    var tempIncludeQr by remember { mutableStateOf(true) }
    var tempContentAfter by remember { mutableStateOf(tempStrings.contentAfterDefault) }

    // Shared settings
    var emailSignature by remember { mutableStateOf(strings.signatureDefault) }
    var emailAssociationName by remember { mutableStateOf("Collectif Nocturne") }
    var emailIncludeDigitalWalletPass by remember { mutableStateOf(true) }
    var emailIncludeLogo by remember { mutableStateOf(false) }
    var emailLogoUri by remember { mutableStateOf("") }
    
    // Load settings asynchronously only once
    LaunchedEffect(Unit) {
        // Volunteer settings
        volunteerSubject = settingsManager.getEmailSubject().ifEmpty { strings.subjectDefault }
        volunteerContentBefore = settingsManager.getEmailContentBefore().ifEmpty { strings.contentBeforeDefault }
        volunteerIncludeQr = settingsManager.isEmailIncludeQrEnabled()
        volunteerContentAfter = settingsManager.getEmailContentAfter().ifEmpty { strings.contentAfterDefault }
        
        // Guest settings
        guestSubject = settingsManager.getGuestEmailSubject().ifEmpty { guestStrings.subjectDefault }
        guestContentBefore = settingsManager.getGuestEmailContentBefore().ifEmpty { guestStrings.contentBeforeDefault }
        guestIncludeQr = settingsManager.isGuestEmailIncludeQrEnabled()
        guestContentAfter = settingsManager.getGuestEmailContentAfter().ifEmpty { guestStrings.contentAfterDefault }

        // Artist guest list settings
        tempSubject = settingsManager.getTemporaryGuestEmailSubject().ifEmpty { tempStrings.subjectDefault }
        tempContentBefore =
            settingsManager.getTemporaryGuestEmailContentBefore().ifEmpty { tempStrings.contentBeforeDefault }
        tempIncludeQr = settingsManager.isTemporaryGuestEmailIncludeQrEnabled()
        tempContentAfter =
            settingsManager.getTemporaryGuestEmailContentAfter().ifEmpty { tempStrings.contentAfterDefault }

        // Shared settings
        emailSignature = settingsManager.getEmailSignature().ifEmpty { strings.signatureDefault }
        emailAssociationName = settingsManager.getEmailAssociationName()
        emailIncludeDigitalWalletPass = settingsManager.isEmailIncludeDigitalWalletPassEnabled()
        emailIncludeLogo = settingsManager.isEmailIncludeLogoEnabled()
        emailLogoUri = settingsManager.getEmailLogoUri()
    }
    
    // Debounce saves to avoid excessive SharedPreferences writes
    val coroutineScope = rememberCoroutineScope()
    var saveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    fun debouncedSave(block: suspend () -> Unit) {
        if (!editable) return
        saveJob?.cancel()
        saveJob = coroutineScope.launch {
            kotlinx.coroutines.delay(500) // 500ms debounce
            block()
            onSyncedSettingChanged()
        }
    }
    
    // Logo picker launcher
    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (!editable) return@rememberLauncherForActivityResult
        uri?.let { selectedUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if permission already taken or not available
            }
            emailLogoUri = selectedUri.toString()
            settingsManager.saveEmailLogoUri(selectedUri.toString())
            val bytes = runCatching {
                context.contentResolver.openInputStream(selectedUri)?.use { it.readBytes() }
            }.getOrNull()
            onInstitutionLogoBytes(bytes)
        }
    }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        Text(
            text = strings.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = strings.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!editable) {
            Text(
                text = context.getString(R.string.institution_settings_firebase_admin_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        
        HorizontalDivider()
        
        // Tab Row for Volunteer / Guest selection
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            tabLabels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabLabels.size),
                    icon = {
                        if (selectedTabIndex == index) {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Default.VolunteerActivism
                                    1 -> Icons.Default.Person
                                    else -> Icons.Default.Groups
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                ) {
                    Text(label)
                }
            }
        }
        
        // Tab-specific content
        if (selectedTabIndex == 0) {
            // Volunteer Email Settings
            VolunteerEmailFields(
                subject = volunteerSubject,
                onSubjectChange = { 
                    volunteerSubject = it
                    debouncedSave { settingsManager.saveEmailSubject(it) }
                },
                contentBefore = volunteerContentBefore,
                onContentBeforeChange = { 
                    volunteerContentBefore = it
                    debouncedSave { settingsManager.saveEmailContentBefore(it) }
                },
                includeQr = volunteerIncludeQr,
                onIncludeQrChange = {
                    if (!editable) return@VolunteerEmailFields
                    volunteerIncludeQr = it
                    settingsManager.setEmailIncludeQrEnabled(it)
                    onSyncedSettingChanged()
                },
                contentAfter = volunteerContentAfter,
                onContentAfterChange = { 
                    volunteerContentAfter = it
                    debouncedSave { settingsManager.saveEmailContentAfter(it) }
                },
                strings = strings,
                enabled = editable,
            )
        } else if (selectedTabIndex == 2) {
            // Artist guest list: one mail carrying every temporary guest's QR code
            GuestEmailFields(
                subject = tempSubject,
                onSubjectChange = {
                    tempSubject = it
                    debouncedSave { settingsManager.saveTemporaryGuestEmailSubject(it) }
                },
                contentBefore = tempContentBefore,
                onContentBeforeChange = {
                    tempContentBefore = it
                    debouncedSave { settingsManager.saveTemporaryGuestEmailContentBefore(it) }
                },
                includeQr = tempIncludeQr,
                onIncludeQrChange = {
                    if (!editable) return@GuestEmailFields
                    tempIncludeQr = it
                    settingsManager.setTemporaryGuestEmailIncludeQrEnabled(it)
                    onSyncedSettingChanged()
                },
                contentAfter = tempContentAfter,
                onContentAfterChange = {
                    tempContentAfter = it
                    debouncedSave { settingsManager.saveTemporaryGuestEmailContentAfter(it) }
                },
                strings = strings,
                enabled = editable,
            )
        } else {
            // Guest Email Settings
            GuestEmailFields(
                subject = guestSubject,
                onSubjectChange = { 
                    guestSubject = it
                    debouncedSave { settingsManager.saveGuestEmailSubject(it) }
                },
                contentBefore = guestContentBefore,
                onContentBeforeChange = { 
                    guestContentBefore = it
                    debouncedSave { settingsManager.saveGuestEmailContentBefore(it) }
                },
                includeQr = guestIncludeQr,
                onIncludeQrChange = {
                    if (!editable) return@GuestEmailFields
                    guestIncludeQr = it
                    settingsManager.setGuestEmailIncludeQrEnabled(it)
                    onSyncedSettingChanged()
                },
                contentAfter = guestContentAfter,
                onContentAfterChange = { 
                    guestContentAfter = it
                    debouncedSave { settingsManager.saveGuestEmailContentAfter(it) }
                },
                strings = strings,
                enabled = editable,
            )
        }
        
        HorizontalDivider()
        
        // Shared Settings Header
        Text(
            text = context.getString(R.string.email_shared_settings_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = context.getString(R.string.email_shared_settings_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Signature (shared)
        OutlinedTextField(
            value = emailSignature,
            onValueChange = { 
                emailSignature = it
                debouncedSave { settingsManager.saveEmailSignature(it) }
            },
            label = { Text(strings.signatureLabel) },
            placeholder = { Text(strings.signatureHint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = editable,
            leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) }
        )

        OutlinedTextField(
            value = emailAssociationName,
            onValueChange = {
                emailAssociationName = it
                debouncedSave { settingsManager.saveEmailAssociationName(it) }
            },
            label = { Text(strings.associationNameLabel) },
            placeholder = { Text(strings.associationNameHint) },
            supportingText = { Text(strings.associationNameDescription) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = editable,
            leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) }
        )
        
        HorizontalDivider()

        // Digital Wallet Pass Toggle (shared)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.includeDigitalWalletPassLabel,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = strings.includeDigitalWalletPassDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = emailIncludeDigitalWalletPass,
                onCheckedChange = {
                    if (!editable) return@Switch
                    emailIncludeDigitalWalletPass = it
                    settingsManager.setEmailIncludeDigitalWalletPassEnabled(it)
                },
                enabled = editable,
            )
        }

        HorizontalDivider()

        // Include Logo Toggle (shared)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.includeLogoLabel,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = strings.includeLogoDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = emailIncludeLogo,
                onCheckedChange = {
                    if (!editable) return@Switch
                    emailIncludeLogo = it
                    settingsManager.setEmailIncludeLogoEnabled(it)
                },
                enabled = editable,
            )
        }
        
        // Logo Upload (only visible when toggle is on)
        if (emailIncludeLogo) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (emailLogoUri.isNotEmpty()) {
                        Text(
                            text = strings.logoPreview,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val logoUri = remember(emailLogoUri) { Uri.parse(emailLogoUri) }
                        Image(
                            painter = rememberAsyncImagePainter(logoUri),
                            contentDescription = strings.logoPreview,
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { logoPickerLauncher.launch(arrayOf("image/*")) }
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.logoChange)
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    emailLogoUri = ""
                                    settingsManager.saveEmailLogoUri("")
                                    onInstitutionLogoBytes(null)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(strings.logoRemove)
                            }
                        }
                    } else {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { logoPickerLauncher.launch(arrayOf("image/*")) }
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.logoUpload)
                        }
                    }
                }
            }
        }
        
        HorizontalDivider()
        
        // Gmail OAuth Authentication Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = context.getString(R.string.email_gmail_auth_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = context.getString(R.string.email_gmail_auth_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Gmail Auth Status and Actions
                GmailAuthSection(settingsManager, context)
            }
        }
        
        HorizontalDivider()
        
        // Reset to Defaults Button - resets current tab only
        OutlinedButton(
            onClick = {
                if (!editable) return@OutlinedButton
                if (selectedTabIndex == 0) {
                    // Reset volunteer settings
                    volunteerSubject = strings.subjectDefault
                    volunteerContentBefore = strings.contentBeforeDefault
                    volunteerIncludeQr = true
                    volunteerContentAfter = strings.contentAfterDefault
                    
                    coroutineScope.launch {
                        settingsManager.saveEmailSubject(volunteerSubject)
                        settingsManager.saveEmailContentBefore(volunteerContentBefore)
                        settingsManager.setEmailIncludeQrEnabled(volunteerIncludeQr)
                        settingsManager.saveEmailContentAfter(volunteerContentAfter)
                        onSyncedSettingChanged()
                    }
                } else {
                    // Reset guest settings
                    guestSubject = guestStrings.subjectDefault
                    guestContentBefore = guestStrings.contentBeforeDefault
                    guestIncludeQr = true
                    guestContentAfter = guestStrings.contentAfterDefault
                    
                    coroutineScope.launch {
                        settingsManager.saveGuestEmailSubject(guestSubject)
                        settingsManager.saveGuestEmailContentBefore(guestContentBefore)
                        settingsManager.setGuestEmailIncludeQrEnabled(guestIncludeQr)
                        settingsManager.saveGuestEmailContentAfter(guestContentAfter)
                        onSyncedSettingChanged()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(strings.resetDefaults + " (${tabLabels[selectedTabIndex]})")
        }
    }
}

/**
 * Volunteer email fields composable
 */
@Composable
private fun VolunteerEmailFields(
    subject: String,
    onSubjectChange: (String) -> Unit,
    contentBefore: String,
    onContentBeforeChange: (String) -> Unit,
    includeQr: Boolean,
    onIncludeQrChange: (Boolean) -> Unit,
    contentAfter: String,
    onContentAfterChange: (String) -> Unit,
    strings: EmailSettingsStrings,
    enabled: Boolean = true,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Email Subject
        OutlinedTextField(
            value = subject,
            onValueChange = onSubjectChange,
            label = { Text(strings.subjectLabel) },
            placeholder = { Text(strings.subjectHint) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = null) }
        )
        
        // Content Before QR Code
        OutlinedTextField(
            value = contentBefore,
            onValueChange = onContentBeforeChange,
            label = { Text(strings.contentBeforeLabel) },
            placeholder = { Text(strings.contentBeforeHint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
        )
        
        // Include QR Code Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.includeQrLabel,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = strings.includeQrDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = includeQr,
                onCheckedChange = onIncludeQrChange,
                enabled = enabled,
            )
        }
        
        // Content After QR Code
        OutlinedTextField(
            value = contentAfter,
            onValueChange = onContentAfterChange,
            label = { Text(strings.contentAfterLabel) },
            placeholder = { Text(strings.contentAfterHint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
        )
    }
}

/**
 * Guest email fields composable
 */
@Composable
private fun GuestEmailFields(
    subject: String,
    onSubjectChange: (String) -> Unit,
    contentBefore: String,
    onContentBeforeChange: (String) -> Unit,
    includeQr: Boolean,
    onIncludeQrChange: (Boolean) -> Unit,
    contentAfter: String,
    onContentAfterChange: (String) -> Unit,
    strings: EmailSettingsStrings,
    enabled: Boolean = true,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Email Subject
        OutlinedTextField(
            value = subject,
            onValueChange = onSubjectChange,
            label = { Text(strings.subjectLabel) },
            placeholder = { Text(strings.subjectHint) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = null) }
        )
        
        // Content Before QR Code
        OutlinedTextField(
            value = contentBefore,
            onValueChange = onContentBeforeChange,
            label = { Text(strings.contentBeforeLabel) },
            placeholder = { Text(strings.contentBeforeHint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
        )
        
        // Include QR Code Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.includeQrLabel,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = strings.includeQrDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = includeQr,
                onCheckedChange = onIncludeQrChange,
                enabled = enabled,
            )
        }
        
        // Content After QR Code
        OutlinedTextField(
            value = contentAfter,
            onValueChange = onContentAfterChange,
            label = { Text(strings.contentAfterLabel) },
            placeholder = { Text(strings.contentAfterHint) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
        )
    }
}

/**
 * Data class for guest email settings strings
 */
private data class GuestEmailSettingsStrings(
    val subjectDefault: String,
    val contentBeforeDefault: String,
    val contentAfterDefault: String
)

/**
 * Gmail Authentication Section using AccountManager
 * This approach uses the device's Google accounts directly - like email apps do.
 */
@Composable
private fun GmailAuthSection(
    settingsManager: SettingsManager,
    context: android.content.Context
) {
    val gmailAuthService = remember { GmailAuthService(context) }
    val coroutineScope = rememberCoroutineScope()
    val activity = context as? android.app.Activity
    
    var isAccountSelected by remember { mutableStateOf(gmailAuthService.isAccountSelected()) }
    var selectedEmail by remember { mutableStateOf(gmailAuthService.getSelectedAccountEmail()) }
    var isCredentialReady by remember { mutableStateOf(gmailAuthService.isCredentialReady()) }
    var isLoading by remember { mutableStateOf(false) }
    var needsPermission by remember { mutableStateOf(false) }
    var needsAndroidTVAuth by remember { mutableStateOf(false) }
    
    val androidTVAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLoading = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            isCredentialReady = gmailAuthService.isCredentialReady()
            needsAndroidTVAuth = !isCredentialReady
            if (isCredentialReady) {
                Toast.makeText(context, "Gmail API connected!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Gmail authorization cancelled", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun tryAndroidTVAuth() {
        if (activity == null) return
        
        isLoading = true
        coroutineScope.launch {
            try {
                val authIntent = gmailAuthService.tryGetTokenForAndroidTV(activity)
                if (authIntent != null) {
                    androidTVAuthLauncher.launch(authIntent)
                } else {
                    isCredentialReady = gmailAuthService.isCredentialReady()
                    needsAndroidTVAuth = !isCredentialReady
                    isLoading = false
                    if (isCredentialReady) {
                        Toast.makeText(context, "Gmail API connected!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                isLoading = false
                Toast.makeText(context, "Gmail connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    LaunchedEffect(Unit) {
        val settingsAccount = settingsManager.getGmailAccount()
        if (settingsAccount.isNotEmpty() && selectedEmail == null) {
            selectedEmail = settingsAccount
            isAccountSelected = true
        }
        
        if (isAccountSelected && !isCredentialReady) {
            val delay = if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1) 500L else 0L
            if (delay > 0) {
                kotlinx.coroutines.delay(delay)
            }
            
            isCredentialReady = gmailAuthService.refreshCredential()
            if (!isCredentialReady) {
                needsAndroidTVAuth = gmailAuthService.needsAndroidTVAuth()
            }
        }
    }
    
    // Account picker launcher
    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLoading = false
        
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (accountName != null) {
                gmailAuthService.setSelectedAccount(accountName)
                settingsManager.saveGmailAccount(accountName)
                isAccountSelected = true
                selectedEmail = accountName
                isCredentialReady = gmailAuthService.isCredentialReady()
                
                if (!isCredentialReady) {
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(500)
                        isCredentialReady = gmailAuthService.refreshCredential()
                        if (!isCredentialReady) {
                            needsAndroidTVAuth = true
                            tryAndroidTVAuth()
                        }
                    }
                }
                
                needsPermission = true
                Toast.makeText(context, context.getString(R.string.email_gmail_auth_success), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, context.getString(R.string.email_gmail_auth_error, "Account selection cancelled"), Toast.LENGTH_SHORT).show()
        }
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        needsPermission = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Gmail permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Gmail permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Check and request permission after account selection
    LaunchedEffect(needsPermission, isAccountSelected) {
        if (needsPermission && isAccountSelected && isCredentialReady) {
            val authIntent = gmailAuthService.testPermissionAndGetAuthIntent()
            if (authIntent != null) {
                permissionLauncher.launch(authIntent)
            }
            needsPermission = false
        }
    }
    
    if (isAccountSelected && selectedEmail != null) {
        // Account selected state
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.email_gmail_signed_in_as, selectedEmail!!),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedButton(
                    onClick = {
                        gmailAuthService.clearSelectedAccount()
                        settingsManager.clearGmailAuth()
                        isAccountSelected = false
                        selectedEmail = null
                        isCredentialReady = false
                        needsAndroidTVAuth = false
                        Toast.makeText(context, "Account disconnected", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !isLoading
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(context.getString(R.string.email_gmail_sign_out))
                }
            }
            
            // Show warning if credential is not ready (Android TV issue)
            if (!isCredentialReady) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Gmail API Connection Required",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = context.getString(R.string.email_gmail_connect_api_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { tryAndroidTVAuth() },
                        enabled = !isLoading && activity != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.email_gmail_connect_api))
                    }
                    if (activity == null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = context.getString(R.string.email_gmail_connect_activity_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    } else {
        // No account selected
        Button(
            onClick = {
                isLoading = true
                val pickerIntent = gmailAuthService.getAccountPickerIntent()
                accountPickerLauncher.launch(pickerIntent)
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(Icons.Default.AccountCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(context.getString(R.string.email_gmail_sign_in))
        }
        
        Text(
            text = context.getString(R.string.email_gmail_not_signed_in),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Data class to cache string resources
private data class EmailSettingsStrings(
    val subjectDefault: String,
    val contentBeforeDefault: String,
    val contentAfterDefault: String,
    val signatureDefault: String,
    val title: String,
    val description: String,
    val subjectLabel: String,
    val subjectHint: String,
    val contentBeforeLabel: String,
    val contentBeforeHint: String,
    val includeQrLabel: String,
    val includeQrDescription: String,
    val contentAfterLabel: String,
    val contentAfterHint: String,
    val signatureLabel: String,
    val signatureHint: String,
    val associationNameLabel: String,
    val associationNameHint: String,
    val associationNameDescription: String,
    val includeDigitalWalletPassLabel: String,
    val includeDigitalWalletPassDescription: String,
    val includeLogoLabel: String,
    val includeLogoDescription: String,
    val logoPreview: String,
    val logoChange: String,
    val logoRemove: String,
    val logoUpload: String,
    val resetDefaults: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun SettingsScreen(
    viewModel: EventManagerViewModel,
    onNavigateToJobTypeManagement: () -> Unit,
    onNavigateToVenueManagement: () -> Unit,
    onNavigateToSalesSheetItemManagement: () -> Unit,
    variant: SettingsScreenVariant,
    modifier: Modifier,
    onDesktopAdminNavLayoutChanged: () -> Unit,
    onFactoryResetComplete: () -> Unit,
) {
    val context = LocalContext.current
    val platformContext = remember(context) { com.eventmanager.app.platform.createPlatformContext(context) }
    val settingsManager = remember { settingsManagerFor(context) }
    val fileManager = remember { FileManager(context) }
    val appIconManager = remember { AppIconManager(context) }
    
    var spreadsheetId by remember { mutableStateOf(settingsManager.getSpreadsheetId()) }
    var guestListSheet by remember { mutableStateOf(settingsManager.getGuestListSheet()) }
    var volunteerSheet by remember { mutableStateOf(settingsManager.getVolunteerSheet()) }
    var jobsSheet by remember { mutableStateOf(settingsManager.getJobsSheet()) }
    var volunteerGuestListSheet by remember { mutableStateOf(settingsManager.getVolunteerGuestListSheet()) }
    var jobTypesSheet by remember { mutableStateOf(settingsManager.getJobTypesSheet()) }
    var venuesSheet by remember { mutableStateOf(settingsManager.getVenuesSheet()) }
    var salesItemsSheet by remember { mutableStateOf(settingsManager.getSalesItemsSheet()) }
    var transfersSheet by remember { mutableStateOf(settingsManager.getTransfersSheet()) }
    var tempGuestListSheet by remember { mutableStateOf(settingsManager.getTempGuestListSheet()) }
    var settingsSheet by remember { mutableStateOf(settingsManager.getSettingsSheet()) }
    var showInstructions by remember { mutableStateOf(false) }
    var jsonKeyInfo by remember { mutableStateOf<JsonKeyInfo?>(null) }
    var showActiveVolunteersDialog by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var syncInterval by remember { mutableStateOf(settingsManager.getSyncInterval()) }
    var migrationWizard by remember { mutableStateOf<com.eventmanager.app.data.remote.MigrationDirection?>(null) }
    var firebaseConfiguredOrgs by remember { mutableStateOf(settingsManager.getFirebaseConfiguredOrgs()) }
    var firebaseAuthEmail by remember { mutableStateOf(settingsManager.getFirebaseAuthEmail()) }
    var firebaseSignInFeedback by remember { mutableStateOf<String?>(null) }
    var allowedEmailDomains by remember { mutableStateOf(settingsManager.getAllowedEmailDomains()) }
    var isFirebaseOrgAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(firebaseAuthEmail, settingsManager.getFirebaseOrgId()) {
        isFirebaseOrgAdmin = com.eventmanager.app.data.remote.FirebaseOrgAdminAccess
            .currentUserIsOrgAdmin(platformContext, settingsManager)
    }
    val canEditInstitutionSettings = com.eventmanager.app.data.security.canEditSyncedInstitutionSettings(
        backendType = settingsManager.getBackendType(),
        isFirebaseOrgAdmin = isFirebaseOrgAdmin,
    )
    val canEditBilleterieSendSetting = com.eventmanager.app.data.security.canEditAnnouncementsNonAdminSendSetting(
        backendType = settingsManager.getBackendType(),
        isFirebaseOrgAdmin = isFirebaseOrgAdmin,
    )
    val catalogVenues by viewModel.venues.collectAsState()
    val activeVenues = remember(catalogVenues) { catalogVenues.filter { it.isActive } }
    val temporaryGuestAccesses by viewModel.temporaryGuestVenueAccesses.collectAsState()
    val temporaryGuestCreditsEnabled by viewModel.temporaryGuestCreditsEnabled.collectAsState()
    val temporaryGuestFeaturesEnabled = viewModel.isTemporaryGuestFeaturesEnabled()
    val firebaseAuthService = remember {
        com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext)
            as? com.eventmanager.app.data.remote.AndroidFirebaseAuthService
    }
    var pendingMigrationSignInResult by remember {
        mutableStateOf<((com.eventmanager.app.data.remote.FirebaseAuthResult) -> Unit)?>(null)
    }
    val firebaseAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        coroutineScope.launch {
            val auth = firebaseAuthService
            if (auth == null) {
                val err = com.eventmanager.app.data.remote.FirebaseAuthResult.Error(
                    "Firebase Auth unavailable on this build",
                )
                firebaseSignInFeedback = err.message
                uploadStatus = err.message
                pendingMigrationSignInResult?.invoke(err)
                pendingMigrationSignInResult = null
                return@launch
            }
            when (val result = auth.completeSignInFromIntent(activityResult.data)) {
                is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                    firebaseAuthEmail = result.email.orEmpty()
                    settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                    firebaseSignInFeedback = null
                    val org = settingsManager.getFirebaseOrgId()
                    if (org.isNotBlank() && result.uid.isNotBlank()) {
                        val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                            platformContext,
                            settingsManager,
                        )
                        runCatching {
                            com.eventmanager.app.data.remote.FirebaseMemberSignIn.afterGoogleSignIn(
                                gateway = gateway,
                                settings = settingsManager,
                                uid = result.uid,
                                email = result.email,
                                joinWithBootstrapCode = settingsManager.getFirebaseBootstrapCode().isNotBlank(),
                            )
                        }.onFailure { e ->
                            firebaseSignInFeedback = e.message
                            uploadStatus = e.message
                        }
                    }
                    uploadStatus = "Firebase signed in as ${result.email ?: result.uid}"
                    pendingMigrationSignInResult?.invoke(result)
                    pendingMigrationSignInResult = null
                }
                is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                    firebaseSignInFeedback = result.message
                    uploadStatus = result.message
                    pendingMigrationSignInResult?.invoke(result)
                    pendingMigrationSignInResult = null
                }
            }
        }
    }
    var showSyncSettings by remember { mutableStateOf(settingsManager.isCategorySyncExpanded()) }
    var showEmailSettings by remember { mutableStateOf(settingsManager.isCategoryEmailExpanded()) }
    var showAppearanceSettings by remember { mutableStateOf(settingsManager.isCategoryAppearanceExpanded()) }
    var showLocalizationSettings by remember { mutableStateOf(settingsManager.isCategoryLocalizationExpanded()) }
    var showAnnouncementsSettings by remember { mutableStateOf(settingsManager.isCategoryAnnouncementsExpanded()) }
    var showAnimationSettings by remember { mutableStateOf(settingsManager.isCategoryAnimationExpanded()) }
    var showDeveloperSettings by remember { mutableStateOf(settingsManager.isCategoryDeveloperExpanded()) }
    var showMaintenanceSettings by remember { mutableStateOf(settingsManager.isCategoryMaintenanceExpanded()) }
    var showExternalReaderSettings by remember { mutableStateOf(settingsManager.isCategoryExternalReaderExpanded()) }
    var showBleReaderPicker by remember { mutableStateOf(false) }
    var externalBleReaderMac by remember { mutableStateOf(settingsManager.getExternalBleReaderMac()) }
    var externalBleReaderName by remember { mutableStateOf(settingsManager.getExternalBleReaderName()) }
    var externalReaderTestRunning by remember { mutableStateOf(false) }
    var externalReaderTestDialogMessage by remember { mutableStateOf<String?>(null) }
    var externalReaderTestDialogSuccess by remember { mutableStateOf(false) }
    var currentResolutionScale by remember { mutableStateOf(settingsManager.getResolutionScale()) }
    var pendingResolutionScale by remember { mutableStateOf(settingsManager.getResolutionScale()) }
    var hasUnsavedResolutionChanges by remember { mutableStateOf(false) }
    var showUpdateResultDialog by remember { mutableStateOf(false) }
    var showUpdateSourcesDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var factoryResetInProgress by remember { mutableStateOf(false) }
    
    // Easter Egg state
    var easterEggTapCount by remember { mutableStateOf(0) }
    var lastEasterEggTapTime by remember { mutableStateOf(0L) }
    var showEasterEggDialog by remember { mutableStateOf(false) }
    var showHextrisGame by remember { mutableStateOf(false) }
    var showPizzaUndeliveryGame by remember { mutableStateOf(false) }
    var showScrollGame by remember { mutableStateOf(false) }
    var showWendolVillageGame by remember { mutableStateOf(false) }
    var showCatculusGame by remember { mutableStateOf(false) }
    
    // Sync status dialog state
    val syncStatusMessage by viewModel.syncStatusMessage.collectAsState()
    val showSyncStatusDialog by viewModel.showSyncStatusDialog.collectAsState()
    val profilePhotosEnabled by viewModel.profilePhotosUploadEnabled.collectAsState()
    val guestFormsEnabled by viewModel.guestFormsEnabled.collectAsState()
    val billeterieSendEnabled by viewModel.announcementsBilleterieSendEnabled.collectAsState()
    
    // Check if JSON key file exists on first load
    LaunchedEffect(Unit) {
        if (fileManager.hasServiceAccountKey()) {
            // Try to extract info from existing file
            jsonKeyInfo = JsonKeyInfo(context.getString(R.string.key_file_found), context.getString(R.string.unknown))
        }
    }
    
    // File picker launcher (use OpenDocument with multiple MIME types for wider compatibility on older Android)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            selectedFileUri = selectedUri
        }
    }
    
    LaunchedEffect(selectedFileUri) {
        selectedFileUri?.let { uri ->
            uploadStatus = context.getString(R.string.validating_file)
            fileManager.validateJsonKeyFile(uri)
                .onSuccess { keyInfo ->
                    uploadStatus = context.getString(R.string.file_validated_uploading)
                    fileManager.copyFileToAssets(uri, "service_account_key.json")
                        .onSuccess { _ ->
                            uploadStatus = context.getString(R.string.file_uploaded_successfully)
                            jsonKeyInfo = keyInfo
                        }
                        .onFailure { error ->
                            uploadStatus = context.getString(R.string.upload_failed, error.message ?: "")
                        }
                }
                .onFailure { error ->
                    uploadStatus = context.getString(R.string.validation_failed, error.message ?: "")
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (variant == SettingsScreenVariant.BilleterieBasic) {
            BackgroundAnimationSettingsSection(
                settingsManager = settingsManager,
                isDesktop = false,
                target = BackgroundAnimationSettingsTarget.Billeterie,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var pageAnimationsEnabled by remember { mutableStateOf(settingsManager.isPageAnimationsEnabled()) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.page_animations_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = context.getString(R.string.page_animations_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pageAnimationsEnabled,
                    onCheckedChange = {
                        pageAnimationsEnabled = it
                        settingsManager.setPageAnimationsEnabled(it)
                    }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (variant == SettingsScreenVariant.PosBasic) {
            BackgroundAnimationSettingsSection(
                settingsManager = settingsManager,
                isDesktop = false,
                target = BackgroundAnimationSettingsTarget.Pos,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var pageAnimationsEnabled by remember { mutableStateOf(settingsManager.isPageAnimationsEnabled()) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.page_animations_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = context.getString(R.string.page_animations_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pageAnimationsEnabled,
                    onCheckedChange = {
                        pageAnimationsEnabled = it
                        settingsManager.setPageAnimationsEnabled(it)
                    }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (variant == SettingsScreenVariant.BilleterieBasic || variant == SettingsScreenVariant.PosBasic) {
            ExpandableSettingsCategory(
                title = context.getString(R.string.settings_category_announcements),
                icon = Icons.Default.Campaign,
                isExpanded = showAnnouncementsSettings,
                onToggleExpanded = {
                    showAnnouncementsSettings = !showAnnouncementsSettings
                    settingsManager.setCategoryAnnouncementsExpanded(showAnnouncementsSettings)
                },
            ) {
                val announcementsVenues by viewModel.venues.collectAsState()
                val activeAnnouncementsVenues = remember(announcementsVenues) {
                    announcementsVenues.filter { it.isActive }
                }
                com.eventmanager.app.ui.components.AnnouncementsSettingsContent(
                    settingsManager = settingsManager,
                    activeVenues = activeAnnouncementsVenues,
                    mode = com.eventmanager.app.ui.components.AnnouncementsSettingsMode.Standard,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (variant == SettingsScreenVariant.Full) {
        // Header
        Text(
            text = context.getString(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = context.getString(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Sync & Data Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_sync),
            icon = Icons.Default.CloudSync,
            isExpanded = showSyncSettings,
            onToggleExpanded = { 
                showSyncSettings = !showSyncSettings
                settingsManager.setCategorySyncExpanded(showSyncSettings)
            }
        ) {
            if (variant == SettingsScreenVariant.Full &&
                settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE
            ) {
                com.eventmanager.app.ui.components.SyncSetupInstructionsButton(
                    backendType = com.eventmanager.app.data.remote.BackendType.FIREBASE,
                    onClick = { showInstructions = true },
                )
                Spacer(modifier = Modifier.height(8.dp))
                com.eventmanager.app.ui.components.FirebaseSyncSettingsSection(
                    configuredOrgs = firebaseConfiguredOrgs,
                    authEmail = firebaseAuthEmail.ifBlank { null },
                    onConfiguredOrgsChange = {
                        firebaseConfiguredOrgs = it
                        viewModel.updateFirebaseConfiguredOrgsLocal(it)
                    },
                    onOrgIdCommitted = viewModel::provisionFirebaseOrg,
                    onSignIn = {
                        firebaseSignInFeedback = null
                        val auth = firebaseAuthService
                        if (auth != null) {
                            firebaseAuthLauncher.launch(auth.getSignInIntent())
                        } else {
                            coroutineScope.launch {
                                when (val result = com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext).signInWithGoogle()) {
                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                        firebaseAuthEmail = result.email.orEmpty()
                                        settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                        firebaseSignInFeedback = null
                                        uploadStatus = "Firebase signed in as ${result.email ?: result.uid}"
                                    }
                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                        firebaseSignInFeedback = result.message
                                        uploadStatus = result.message
                                    }
                                }
                            }
                        }
                    },
                    onSignOut = {
                        coroutineScope.launch {
                            com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext).signOut()
                            firebaseAuthEmail = ""
                            settingsManager.setFirebaseAuthEmail("")
                            firebaseSignInFeedback = null
                        }
                    },
                    onMigrateToSheets = {
                        migrationWizard = com.eventmanager.app.data.remote.MigrationDirection.FIREBASE_TO_SHEETS
                    },
                    onMirrorExport = {
                        viewModel.exportSheetsMirrorNow()
                    },
                    platformContext = platformContext,
                    onMirrorSettingsChanged = { viewModel.onSheetsMirrorSettingsChanged() },
                    settingsManager = settingsManager,
                    profilePhotosEnabled = profilePhotosEnabled,
                    onProfilePhotosEnabledChange = { viewModel.setProfilePhotosEnabled(it) },
                    guestFormsEnabled = guestFormsEnabled,
                    onGuestFormsEnabledChange = { viewModel.setGuestFormsEnabled(it) },
                    guestFormSiteOrigin = viewModel.guestFormSiteOrigin(),
                    projectId = settingsManager.getFirebaseProjectId(),
                    apiKey = settingsManager.getFirebaseApiKey(),
                    applicationId = settingsManager.getFirebaseApplicationId(),
                    onProjectIdChange = { settingsManager.setFirebaseProjectId(it) },
                    onApiKeyChange = { settingsManager.setFirebaseApiKey(it) },
                    onApplicationIdChange = { settingsManager.setFirebaseApplicationId(it) },
                    webClientId = settingsManager.getFirebaseWebClientId(),
                    onWebClientIdChange = { settingsManager.setFirebaseWebClientId(it) },
                    webClientSecret = settingsManager.getFirebaseWebClientSecret(),
                    onWebClientSecretChange = { settingsManager.setFirebaseWebClientSecret(it) },
                    allowedEmailDomains = allowedEmailDomains,
                    onAllowedEmailDomainsChange = { domains ->
                        allowedEmailDomains = domains
                        settingsManager.setAllowedEmailDomains(domains)
                        coroutineScope.launch {
                            val org = settingsManager.resolveWritableFirebaseOrgId()
                            if (org.isBlank()) return@launch
                            val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                                platformContext,
                                settingsManager,
                            )
                            runCatching {
                                com.eventmanager.app.data.remote.MemberRoleAdmin.publishAllowedEmailDomains(
                                    gateway = gateway,
                                    orgId = org,
                                    domains = domains,
                                )
                            }.onFailure { uploadStatus = it.message }
                        }
                    },
                    signInFeedback = firebaseSignInFeedback,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.SHEETS) {
            // Google Sheets Configuration Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = context.getString(R.string.google_sheets_config_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Instructions Button
                    Button(
                        onClick = { showInstructions = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.view_setup_instructions))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Spreadsheet ID
                    OutlinedTextField(
                        value = spreadsheetId,
                        onValueChange = { spreadsheetId = it },
                        label = { Text(context.getString(R.string.spreadsheet_id_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { 
                            Text(context.getString(R.string.spreadsheet_id_hint))
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null)
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Sheet Names
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = guestListSheet,
                            onValueChange = { guestListSheet = it },
                            label = { Text(context.getString(R.string.guest_list_sheet_label)) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Default.People, contentDescription = null)
                            }
                        )
                        
                        OutlinedTextField(
                            value = volunteerSheet,
                            onValueChange = { volunteerSheet = it },
                            label = { Text(context.getString(R.string.volunteer_sheet_label)) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Default.Group, contentDescription = null)
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = jobsSheet,
                        onValueChange = { jobsSheet = it },
                        label = { Text(context.getString(R.string.shifts_sheet_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.Work, contentDescription = null)
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Additional Sheet Names
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = volunteerGuestListSheet,
                            onValueChange = { volunteerGuestListSheet = it },
                            label = { Text(context.getString(R.string.volunteer_guest_list_sheet_label)) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Default.People, contentDescription = null)
                            }
                        )
                        
                        OutlinedTextField(
                            value = tempGuestListSheet,
                            onValueChange = { tempGuestListSheet = it },
                            label = { Text(context.getString(R.string.temp_guest_list_sheet_label)) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Default.People, contentDescription = null)
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = jobTypesSheet,
                            onValueChange = { jobTypesSheet = it },
                            label = { Text(context.getString(R.string.shift_types_sheet_label)) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            }
                        )
                        
                        OutlinedTextField(
                            value = venuesSheet,
                            onValueChange = { venuesSheet = it },
                            label = { Text(context.getString(R.string.venues_sheet_label)) },
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Default.LocationOn, contentDescription = null)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = salesItemsSheet,
                        onValueChange = { salesItemsSheet = it },
                        label = { Text(context.getString(R.string.sales_items_sheet_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.PointOfSale, contentDescription = null)
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = transfersSheet,
                        onValueChange = { transfersSheet = it },
                        label = { Text(context.getString(R.string.transfers_sheet_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = settingsSheet,
                        onValueChange = { settingsSheet = it },
                        label = { Text(context.getString(R.string.settings_sheet_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Action Buttons
                    Button(
                        onClick = { 
                            settingsManager.saveSpreadsheetId(spreadsheetId)
                            settingsManager.saveGuestListSheet(guestListSheet)
                            settingsManager.saveVolunteerSheet(volunteerSheet)
                            settingsManager.saveJobsSheet(jobsSheet)
                            settingsManager.saveVolunteerGuestListSheet(volunteerGuestListSheet)
                            settingsManager.saveTempGuestListSheet(tempGuestListSheet)
                            settingsManager.saveJobTypesSheet(jobTypesSheet)
                            settingsManager.saveVenuesSheet(venuesSheet)
                            settingsManager.saveSalesItemsSheet(salesItemsSheet)
                            settingsManager.saveTransfersSheet(transfersSheet)
                            settingsManager.saveSettingsSheet(settingsSheet)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.save_settings))
                    }
                }
            }
            
            } // end SHEETS-only sync fields

            if (variant == SettingsScreenVariant.Full &&
                settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.SHEETS
            ) {
                com.eventmanager.app.ui.components.SheetsMigrateToFirebaseButton(
                    onClick = {
                        migrationWizard = com.eventmanager.app.data.remote.MigrationDirection.SHEETS_TO_FIREBASE
                    },
                    projectId = settingsManager.getFirebaseProjectId(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            if (settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.SHEETS) {
            // Service Account Configuration Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = context.getString(R.string.service_account_config_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // JSON Key File Status
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (jsonKeyInfo != null) 
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else 
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (jsonKeyInfo != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (jsonKeyInfo != null) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (jsonKeyInfo != null) context.getString(R.string.service_account_key_found) else context.getString(R.string.service_account_key_required),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (jsonKeyInfo != null) {
                                        Text(
                                            text = context.getString(R.string.email_colon, jsonKeyInfo!!.clientEmail),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = context.getString(R.string.project_colon, jsonKeyInfo!!.projectId),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        Text(
                                            text = context.getString(R.string.upload_key_description),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Upload Button
                            Button(
                                onClick = {
                                    // Accept common fallbacks because some Android 9 pickers gray out application/json
                                    filePickerLauncher.launch(arrayOf(
                                        "application/json",
                                        "application/octet-stream",
                                        "text/plain",
                                        "text/*",
                                        "application/x-json"
                                    ))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (jsonKeyInfo != null) context.getString(R.string.replace_key_file) else context.getString(R.string.upload_key_file))
                            }
                            
                            if (uploadStatus != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = uploadStatus!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uploadStatus!!.contains(context.getString(R.string.file_uploaded_successfully))) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Sync Configuration Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = context.getString(R.string.sync_config_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Sync Interval
                    OutlinedTextField(
                        value = syncInterval.toString(),
                        onValueChange = { 
                            val newInterval = it.toIntOrNull() ?: 5
                            if (newInterval >= 1 && newInterval <= 60) {
                                syncInterval = newInterval
                                settingsManager.saveSyncInterval(newInterval)
                                // Update the background sync interval with a small delay to prevent rapid changes
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(500) // 500ms debounce
                                    viewModel.updateSyncInterval()
                                }
                            }
                        },
                        label = { Text(context.getString(R.string.sync_interval_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { 
                            Text(context.getString(R.string.sync_interval_hint))
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Timer, contentDescription = null)
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Test Buttons
                    Button(
                        onClick = { viewModel.testSyncStatus() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.test_sync_status))
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = { viewModel.syncWithGoogleSheets() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.manual_sync_now))
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            } // end SHEETS service account + sync interval
        }
        }

        com.eventmanager.app.ui.components.BackendMigrationUiHost(
            viewModel = viewModel,
            settingsManager = settingsManager,
            platformContext = platformContext,
            showMigrationWizard = migrationWizard,
            onDismissMigrationWizard = { migrationWizard = null },
            onRequestFirebaseSignIn = { onResult ->
                val auth = firebaseAuthService
                if (auth != null) {
                    pendingMigrationSignInResult = onResult
                    firebaseAuthLauncher.launch(auth.getSignInIntent())
                } else {
                    coroutineScope.launch {
                        val result = com.eventmanager.app.data.remote
                            .createFirebaseAuthService(platformContext)
                            .signInWithGoogle()
                        when (result) {
                            is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                firebaseAuthEmail = result.email.orEmpty()
                            }
                            is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                uploadStatus = result.message
                            }
                        }
                        onResult(result)
                    }
                }
            },
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Email Settings Category (admin + Billeterie — Gmail API & templates for volunteer QR emails)
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_email),
            icon = Icons.Default.Email,
            isExpanded = showEmailSettings,
            onToggleExpanded = { 
                showEmailSettings = !showEmailSettings
                settingsManager.setCategoryEmailExpanded(showEmailSettings)
            }
        ) {
            EmailSettingsContent(
                settingsManager = settingsManager,
                context = context,
                onSyncedSettingChanged = { viewModel.backupInstitutionSettingsToSheets() },
                editable = canEditInstitutionSettings,
                temporaryFeaturesEnabled = viewModel.isTemporaryGuestFeaturesEnabled(),
                onInstitutionLogoBytes = { viewModel.setInstitutionLogo(it) },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // External NFC reader (ACR1255U-J1 BLE) — visible in both admin and Billeterie settings.
        // The reader is a BLE GATT accessory that does NOT show up reliably in the system
        // Bluetooth pairing list, so we provide an in-app scan + pick + remember flow.
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_external_reader),
            icon = Icons.Default.Nfc,
            isExpanded = showExternalReaderSettings,
            onToggleExpanded = {
                showExternalReaderSettings = !showExternalReaderSettings
                settingsManager.setCategoryExternalReaderExpanded(showExternalReaderSettings)
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = context.getString(R.string.external_reader_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (externalBleReaderMac.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = context.getString(R.string.external_reader_connected_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = externalBleReaderName.ifBlank {
                                    context.getString(R.string.ble_reader_unnamed)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = externalBleReaderMac,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showBleReaderPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(context.getString(R.string.external_reader_change))
                        }
                        OutlinedButton(
                            onClick = {
                                com.eventmanager.app.hardware.Acr1255uj1BleNfcReader.shutdown()
                                settingsManager.clearExternalBleReader()
                                externalBleReaderMac = ""
                                externalBleReaderName = ""
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.external_reader_cleared_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(context.getString(R.string.external_reader_forget))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val testScope = rememberCoroutineScope()
                    OutlinedButton(
                        onClick = {
                            if (externalReaderTestRunning) return@OutlinedButton
                            externalReaderTestRunning = true
                            testScope.launch {
                                val result = com.eventmanager.app.hardware.Acr1255uj1BleNfcReader
                                    .runDiagnostic(context)
                                externalReaderTestDialogSuccess = result.success
                                externalReaderTestDialogMessage = result.details
                                externalReaderTestRunning = false
                            }
                        },
                        enabled = !externalReaderTestRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (externalReaderTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(context.getString(R.string.external_reader_testing))
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(context.getString(R.string.external_reader_test_button))
                        }
                    }
                } else {
                    Button(
                        onClick = { showBleReaderPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.external_reader_pick_button))
                    }
                }
            }
        }

        if (showBleReaderPicker) {
            com.eventmanager.app.ui.components.BleReaderPickerDialog(
                platformContext = remember(context) { com.eventmanager.app.platform.createPlatformContext(context) },
                onDismiss = { showBleReaderPicker = false },
                onPicked = { mac, name ->
                    settingsManager.saveExternalBleReaderMac(mac)
                    settingsManager.saveExternalBleReaderName(name)
                    externalBleReaderMac = mac
                    externalBleReaderName = name
                    showBleReaderPicker = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.external_reader_saved_toast, name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }

        externalReaderTestDialogMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { externalReaderTestDialogMessage = null },
                icon = {
                    Icon(
                        imageVector = if (externalReaderTestDialogSuccess) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Warning
                        },
                        contentDescription = null,
                        tint = if (externalReaderTestDialogSuccess) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                },
                title = {
                    Text(
                        context.getString(
                            if (externalReaderTestDialogSuccess) {
                                R.string.external_reader_test_success_title
                            } else {
                                R.string.external_reader_test_failure_title
                            }
                        ),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                },
                confirmButton = {
                    Button(onClick = { externalReaderTestDialogMessage = null }) {
                        Text(context.getString(R.string.external_reader_test_close))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        // Appearance & Display Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_appearance),
            icon = Icons.Default.Palette,
            isExpanded = showAppearanceSettings,
            onToggleExpanded = { 
                showAppearanceSettings = !showAppearanceSettings
                settingsManager.setCategoryAppearanceExpanded(showAppearanceSettings)
            }
        ) {
            // Theme Selection
            var selectedTheme by remember { mutableStateOf(ThemeMode.fromString(settingsManager.getThemeMode())) }
            val applyVisualThemeRefresh = {
                settingsManager.markSkipNextStartupSync()
                AppAppearanceState.notifyThemeAppearanceChanged(settingsManager.getThemeMode())
                (context as? android.app.Activity)?.recreate()
            }
            
            ThemeModePicker(
                selectedMode = selectedTheme,
                onSelect = { mode ->
                                    if (selectedTheme != mode) {
                                        selectedTheme = mode
                                        settingsManager.saveThemeMode(mode.value)
                                        applyVisualThemeRefresh()
                                    }
                                },
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Color Theme Selection
            var selectedColorTheme by remember { mutableStateOf(settingsManager.getColorTheme()) }
            var showCustomThemeEditor by remember { mutableStateOf(false) }
            var customThemePreviewVersion by remember { mutableIntStateOf(0) }
            val previewDarkMode = when (selectedTheme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.DEFAULT -> isSystemInDarkTheme()
            }
            ColorThemePicker(
                selectedThemeKey = selectedColorTheme,
                previewDark = previewDarkMode,
                settingsManager = settingsManager,
                customThemeRefreshNonce = customThemePreviewVersion,
                includeCustomTheme = true,
                onSelect = { key ->
                    selectedColorTheme = key
                    settingsManager.saveColorTheme(key)
                    if (key == "custom") {
                                                showCustomThemeEditor = true
                                            } else {
                                                applyVisualThemeRefresh()
                    }
                },
                onCustomThemeReselect = { showCustomThemeEditor = true },
            )

                if (selectedColorTheme == "custom") {
                    OutlinedButton(
                        onClick = { showCustomThemeEditor = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.color_theme_customize))
                    }
                }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Resolution Scale Selection
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = context.getString(R.string.resolution_scale_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = context.getString(R.string.resolution_scale_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Custom slider component
                        ResolutionScaleSlider(
                            value = pendingResolutionScale,
                            onValueChange = { newValue ->
                                pendingResolutionScale = newValue
                                hasUnsavedResolutionChanges = newValue != currentResolutionScale
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Save button for resolution changes
                        if (hasUnsavedResolutionChanges) {
                            Button(
                                onClick = {
                                    currentResolutionScale = pendingResolutionScale
                                    settingsManager.saveResolutionScale(pendingResolutionScale)
                                    hasUnsavedResolutionChanges = false
                                    (context as? android.app.Activity)?.recreate()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(context.getString(R.string.save_resolution_scale))
                            }
                        }
                    }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // App Icon Style Selection - Carousel
            var selectedIconStyle by remember { mutableStateOf(settingsManager.getAppIconStyle()) }
            var scrollBehavior by remember { mutableStateOf(settingsManager.getScrollBehavior()) }
            
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                        Text(
                            text = context.getString(R.string.app_icon_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = context.getString(R.string.app_icon_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Icon Carousel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Define all icon options with their properties
                            val iconOptions = listOf(
                                IconOption(AppIconManager.ICON_WHITE, R.string.app_icon_white, R.string.app_icon_white_applied, R.mipmap.ic_launcher_foreground, Color(0xFFFFFFFF)),
                                IconOption(AppIconManager.ICON_BLACK, R.string.app_icon_black, R.string.app_icon_black_applied, R.mipmap.ic_launcher_foreground_dark, Color(0xFF000000)),
                                IconOption(AppIconManager.ICON_BROWN, R.string.app_icon_brown, R.string.app_icon_brown_applied, R.mipmap.ic_launcher_foreground_braun, Color(0xFF5A3214)),
                                IconOption(AppIconManager.ICON_CREME_BLACK, R.string.app_icon_creme_black, R.string.app_icon_creme_black_applied, R.mipmap.ic_launcher_foreground_purple, Color(0xFFF0E6DC)),
                                IconOption(AppIconManager.ICON_DARK_BLUE, R.string.app_icon_dark_blue, R.string.app_icon_dark_blue_applied, R.mipmap.ic_launcher_foreground_deep_blue, Color(0xFF000A3C)),
                                IconOption(AppIconManager.ICON_DARK_LEGACY, R.string.app_icon_dark_legacy, R.string.app_icon_dark_legacy_applied, R.mipmap.ic_launcher_foreground_dark_legacy, Color(0xFF321E3C)),
                                IconOption(AppIconManager.ICON_DARK_TURQUOISE, R.string.app_icon_dark_turquoise, R.string.app_icon_dark_turquoise_applied, R.mipmap.ic_launcher_foreground_blue_ocean, Color(0xFF00283C)),
                                IconOption(AppIconManager.ICON_DARK_VIOLET, R.string.app_icon_dark_violet, R.string.app_icon_dark_violet_applied, R.mipmap.ic_launcher_foreground_violet, Color(0xFF321E32)),
                                IconOption(AppIconManager.ICON_LIGHT_BLUE, R.string.app_icon_light_blue, R.string.app_icon_light_blue_applied, R.mipmap.ic_launcher_foreground_light_blue, Color(0xFF4696BE)),
                                IconOption(AppIconManager.ICON_LIGHT_LEGACY, R.string.app_icon_light_legacy, R.string.app_icon_light_legacy_applied, R.mipmap.ic_launcher_foreground_light_legacy, Color(0xFFE6E6DC)),
                                IconOption(AppIconManager.ICON_LIGHT_VIOLET, R.string.app_icon_light_violet, R.string.app_icon_light_violet_applied, R.mipmap.ic_launcher_foreground_light_violet, Color(0xFF8232C8)),
                                IconOption(AppIconManager.ICON_ORANGE, R.string.app_icon_orange, R.string.app_icon_orange_applied, R.mipmap.ic_launcher_foreground_orange, Color(0xFF963C0A)),
                                IconOption(AppIconManager.ICON_PINK, R.string.app_icon_pink, R.string.app_icon_pink_applied, R.mipmap.ic_launcher_foreground_pink, Color(0xFF823C82))
                            )
                            
                            // Launcher-like preview: large colored plate + full foreground (adaptive) scaled to fit.
                            val iconPreviewPlate = 120.dp
                            val iconDecodeMaxDp = 280.dp

                            iconOptions.forEach { iconOption ->
                                val isSelected = selectedIconStyle == iconOption.style
                                
                            Card(
                                modifier = Modifier
                                    .width(168.dp)
                                    .clickable {
                                            selectedIconStyle = iconOption.style
                                            println("🔄 Saving ${iconOption.style} icon style")
                                            settingsManager.saveAppIconStyle(iconOption.style)
                                            println("✅ ${iconOption.style} icon style saved")
                                        println("🔍 Verification - saved style is now: ${settingsManager.getAppIconStyle()}")
                                            println("🔄 Applying ${iconOption.style} icon")
                                            appIconManager.setAppIcon(iconOption.style)
                                            println("✅ ${iconOption.style} icon applied")
                                            coroutineScope.launch {
                                                delay(200)
                                                restartApp(context)
                                            }
                                        // Show toast to user
                                        android.widget.Toast.makeText(
                                            context,
                                                context.getString(iconOption.toastResId),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(
                                        defaultElevation = if (isSelected) 12.dp else 4.dp
                                ),
                                colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                        // Display icon preview: adaptive background + foreground, sized like a launcher tile.
                                    Box(
                                        modifier = Modifier
                                            .size(iconPreviewPlate)
                                            .clip(RoundedCornerShape(26.dp))
                                            .background(color = iconOption.backgroundColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val platformContextForIcons = remember(context) { com.eventmanager.app.platform.createPlatformContext(context) }
                                        val iconBitmap = remember(iconOption.iconResId, platformContextForIcons) {
                                            val resourceName = context.resources.getResourceEntryName(iconOption.iconResId)
                                            ImageUtils.loadScaledImageBitmap(
                                                platformContext = platformContextForIcons,
                                                resourceName = resourceName,
                                                maxWidthDp = iconDecodeMaxDp,
                                                maxHeightDp = iconDecodeMaxDp
                                            )
                                        }

                                        iconBitmap?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = context.getString(iconOption.nameResId),
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(6.dp),
                                                contentScale = ContentScale.Fit
                                            )
                                        } ?: run {
                                            val fallbackBitmap = remember(iconOption.iconResId) {
                                                ContextCompat.getDrawable(context, iconOption.iconResId)?.let { d ->
                                                    android.graphics.Bitmap.createBitmap(
                                                        d.intrinsicWidth.coerceAtLeast(1),
                                                        d.intrinsicHeight.coerceAtLeast(1),
                                                        android.graphics.Bitmap.Config.ARGB_8888
                                                    ).apply {
                                                        val canvas = android.graphics.Canvas(this)
                                                        d.setBounds(0, 0, width, height)
                                                        d.draw(canvas)
                                                    }.asImageBitmap()
                                                }
                                            }
                                            fallbackBitmap?.let { bitmap ->
                                                Image(
                                                    bitmap = bitmap,
                                                    contentDescription = context.getString(iconOption.nameResId),
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(6.dp),
                                                    contentScale = ContentScale.Fit
                                                )
                                            }
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                        Text(
                                            text = context.getString(iconOption.nameResId),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                                        if (isSelected) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = context.getString(R.string.app_icon_selected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                                        }
                        }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Auto-Adapt Icon Based on System Theme
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var autoAdaptEnabled by remember { mutableStateOf(settingsManager.isAppIconAutoAdapt()) }
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = context.getString(R.string.app_icon_auto_adapt_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = context.getString(R.string.app_icon_auto_adapt_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (autoAdaptEnabled) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (appIconManager.isSystemDarkMode()) 
                                        context.getString(R.string.system_is_in_dark_mode)
                                    else 
                                        context.getString(R.string.system_is_in_light_mode),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Switch(
                            checked = autoAdaptEnabled,
                            onCheckedChange = {
                                autoAdaptEnabled = it
                                settingsManager.setAppIconAutoAdapt(it)
                                if (it) {
                                    // Apply the system-adapted icon immediately
                                    val adaptedIcon = appIconManager.getSystemAdaptedIcon()
                                    appIconManager.setAppIcon(adaptedIcon)
                                    selectedIconStyle = adaptedIcon
                                    settingsManager.saveAppIconStyle(adaptedIcon)
                                    coroutineScope.launch {
                                        delay(200)
                                        restartApp(context)
                                    }
                                    // Show toast to user
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(R.string.app_icon_auto_adapt_enabled),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // People Counter Visibility Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var peopleCounterVisible by remember { mutableStateOf(settingsManager.isPeopleCounterVisible()) }
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = context.getString(R.string.people_counter_visibility_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = context.getString(R.string.people_counter_visibility_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = peopleCounterVisible,
                            onCheckedChange = {
                                peopleCounterVisible = it
                                settingsManager.setPeopleCounterVisible(it)
                                if (it) {
                                    coroutineScope.launch {
                                        viewModel.refreshVenuesForPeopleCounterQuietly()
                                    }
                                }
                            }
                        )
                    }

                    if (variant == SettingsScreenVariant.BilleterieBasic) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var billeterieClockVisible by remember {
                                mutableStateOf(settingsManager.isBilleterieClockVisible())
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = context.getString(R.string.billeterie_clock_visibility_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = context.getString(R.string.billeterie_clock_visibility_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = billeterieClockVisible,
                                onCheckedChange = {
                                    billeterieClockVisible = it
                                    settingsManager.setBilleterieClockVisible(it)
                                }
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (variant == SettingsScreenVariant.Full) {
                        // Statistics Visibility Toggle (Show Graphs) — admin only
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var statisticsVisible by remember { mutableStateOf(settingsManager.isStatisticsVisible()) }
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = context.getString(R.string.statistics_visibility_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = context.getString(R.string.statistics_visibility_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = statisticsVisible,
                                onCheckedChange = {
                                    statisticsVisible = it
                                    settingsManager.setStatisticsVisible(it)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    
                    ScrollBehaviorPicker(
                        scrollBehavior = scrollBehavior,
                        onScrollBehaviorChange = { behavior ->
                            scrollBehavior = behavior
                            settingsManager.setScrollBehavior(behavior)
                        },
                    )
                    
                    if (showCustomThemeEditor) {
                        CustomThemeEditorDialog(
                            settingsManager = settingsManager,
                            onColorChanged = { customThemePreviewVersion++ },
                            onDismiss = {
                                showCustomThemeEditor = false
                                customThemePreviewVersion++
                                applyVisualThemeRefresh()
                            }
                        )
                }
            }
        
        Spacer(modifier = Modifier.height(24.dp))

        // Announcements Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_announcements),
            icon = Icons.Default.Campaign,
            isExpanded = showAnnouncementsSettings,
            onToggleExpanded = {
                showAnnouncementsSettings = !showAnnouncementsSettings
                settingsManager.setCategoryAnnouncementsExpanded(showAnnouncementsSettings)
            }
        ) {
            val announcementsVenues by viewModel.venues.collectAsState()
            val activeAnnouncementsVenues = remember(announcementsVenues) { announcementsVenues.filter { it.isActive } }
            com.eventmanager.app.ui.components.AnnouncementsSettingsContent(
                settingsManager = settingsManager,
                activeVenues = activeAnnouncementsVenues,
                mode = if (variant == SettingsScreenVariant.Full) {
                    com.eventmanager.app.ui.components.AnnouncementsSettingsMode.Admin
                } else {
                    com.eventmanager.app.ui.components.AnnouncementsSettingsMode.Standard
                },
                billeterieSendEnabled = billeterieSendEnabled,
                canEditBilleterieSend = canEditBilleterieSendSetting,
                onBilleterieSendEnabledChange = if (variant == SettingsScreenVariant.Full) {
                    { enabled -> viewModel.setAnnouncementsBilleterieSendEnabled(enabled) }
                } else {
                    null
                },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        // Localization Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_localization),
            icon = Icons.Default.Language,
            isExpanded = showLocalizationSettings,
            onToggleExpanded = { 
                showLocalizationSettings = !showLocalizationSettings
                settingsManager.setCategoryLocalizationExpanded(showLocalizationSettings)
            }
        ) {
            // Language Selection (shared by admin, POS and billeterie)
            var selectedLanguage by remember { mutableStateOf(settingsManager.getLanguage()) }
            var showLanguageMenu by remember { mutableStateOf(false) }
            val persistLanguageSelection: (String) -> Unit = { code ->
                selectedLanguage = code
                showLanguageMenu = false
                settingsManager.saveLanguage(code)
                (context as? android.app.Activity)?.recreate()
            }
            
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = context.getString(R.string.language_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = context.getString(R.string.language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { showLanguageMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = when (selectedLanguage) {
                                "fr" -> context.getString(R.string.language_french)
                                "es" -> context.getString(R.string.language_spanish)
                                "zh-TW" -> context.getString(R.string.language_chinese)
                                "zh-CN" -> context.getString(R.string.language_chinese_simplified)
                                "la" -> context.getString(R.string.language_latin)
                                "hi" -> context.getString(R.string.language_hindi)
                                else -> context.getString(R.string.language_english)
                            },
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_english)) },
                            onClick = { persistLanguageSelection("en") }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_french)) },
                            onClick = { persistLanguageSelection("fr") }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_spanish)) },
                            onClick = { persistLanguageSelection("es") }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_chinese)) },
                            onClick = { persistLanguageSelection("zh-TW") }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_chinese_simplified)) },
                            onClick = { persistLanguageSelection("zh-CN") }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_latin)) },
                            onClick = { persistLanguageSelection("la") }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.language_hindi)) },
                            onClick = { persistLanguageSelection("hi") }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (!canEditInstitutionSettings) {
                Text(
                    text = context.getString(R.string.institution_settings_firebase_admin_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            var selectedCurrency by remember { mutableStateOf(settingsManager.getCurrencyCode()) }
            var showCurrencyMenu by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = context.getString(R.string.currency_setting_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = context.getString(R.string.currency_setting_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { if (canEditInstitutionSettings) showCurrencyMenu = true },
                        enabled = canEditInstitutionSettings,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = selectedCurrency,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = showCurrencyMenu,
                        onDismissRequest = { showCurrencyMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("CHF", "EUR", "USD", "GBP").forEach { code ->
                            DropdownMenuItem(
                                text = { Text(code) },
                                onClick = {
                                    selectedCurrency = code
                                    settingsManager.saveCurrencyCode(code)
                                    viewModel.backupInstitutionSettingsToSheets()
                                    showCurrencyMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (variant == SettingsScreenVariant.Full) {
                var purchaseBufferText by remember {
                    mutableStateOf(settingsManager.getPurchaseCreditBuffer().toString())
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = context.getString(R.string.purchase_credit_buffer_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = context.getString(R.string.purchase_credit_buffer_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = purchaseBufferText,
                        onValueChange = { newValue ->
                            if (!canEditInstitutionSettings) return@OutlinedTextField
                            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                purchaseBufferText = newValue
                                val amount = newValue.toDoubleOrNull() ?: 0.0
                                settingsManager.savePurchaseCreditBuffer(amount)
                                viewModel.backupInstitutionSettingsToSheets()
                            }
                        },
                        label = { Text(context.getString(R.string.amount_label)) },
                        suffix = { Text(selectedCurrency) },
                        singleLine = true,
                        enabled = canEditInstitutionSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Date and Time Format Selection
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = context.getString(R.string.date_time_format_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = context.getString(R.string.date_time_format_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                var selectedDateFormat by remember { mutableStateOf(settingsManager.getDateFormat()) }
                var showDateFormatMenu by remember { mutableStateOf(false) }
                
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { showDateFormatMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = when (selectedDateFormat) {
                                "dd/MM/yyyy" -> context.getString(R.string.date_format_dd_mm_yyyy)
                                "MM/dd/yyyy" -> context.getString(R.string.date_format_mm_dd_yyyy)
                                "yyyy-MM-dd" -> context.getString(R.string.date_format_yyyy_mm_dd)
                                else -> context.getString(R.string.date_format_dd_mm_yyyy)
                            },
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showDateFormatMenu,
                        onDismissRequest = { showDateFormatMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.date_format_dd_mm_yyyy)) },
                            onClick = {
                                selectedDateFormat = "dd/MM/yyyy"
                                settingsManager.saveDateFormat(selectedDateFormat)
                                showDateFormatMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.date_format_mm_dd_yyyy)) },
                            onClick = {
                                selectedDateFormat = "MM/dd/yyyy"
                                settingsManager.saveDateFormat(selectedDateFormat)
                                showDateFormatMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.date_format_yyyy_mm_dd)) },
                            onClick = {
                                selectedDateFormat = "yyyy-MM-dd"
                                settingsManager.saveDateFormat(selectedDateFormat)
                                showDateFormatMenu = false
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                var selectedTimeFormat by remember { mutableStateOf(settingsManager.getTimeFormat()) }
                var showTimeFormatMenu by remember { mutableStateOf(false) }
                
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { showTimeFormatMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = when (selectedTimeFormat) {
                                "HH:mm" -> context.getString(R.string.time_format_hh_mm)
                                "HH:mm:ss" -> context.getString(R.string.time_format_hh_mm_ss)
                                "HH:mm:ss.SSS" -> context.getString(R.string.time_format_hh_mm_ss_sss)
                                else -> context.getString(R.string.time_format_hh_mm)
                            },
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showTimeFormatMenu,
                        onDismissRequest = { showTimeFormatMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.time_format_hh_mm)) },
                            onClick = {
                                selectedTimeFormat = "HH:mm"
                                settingsManager.saveTimeFormat(selectedTimeFormat)
                                showTimeFormatMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.time_format_hh_mm_ss)) },
                            onClick = {
                                selectedTimeFormat = "HH:mm:ss"
                                settingsManager.saveTimeFormat(selectedTimeFormat)
                                showTimeFormatMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.time_format_hh_mm_ss_sss)) },
                            onClick = {
                                selectedTimeFormat = "HH:mm:ss.SSS"
                                settingsManager.saveTimeFormat(selectedTimeFormat)
                                showTimeFormatMenu = false
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Date Change Offset Setting
            var dateChangeOffsetHours by remember { mutableStateOf(settingsManager.getDateChangeOffsetHours()) }
            
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = context.getString(R.string.date_change_offset_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = context.getString(R.string.date_change_offset_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (!canEditInstitutionSettings) return@IconButton
                            if (dateChangeOffsetHours > -12) {
                                dateChangeOffsetHours--
                                settingsManager.saveDateChangeOffsetHours(dateChangeOffsetHours)
                                viewModel.backupInstitutionSettingsToSheets()
                            }
                        },
                        enabled = canEditInstitutionSettings && dateChangeOffsetHours > -12
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = context.getString(R.string.date_change_offset_decrease)
                        )
                    }
                    
                    Text(
                        text = if (dateChangeOffsetHours == 0) {
                            context.getString(R.string.date_change_offset_zero)
                        } else if (dateChangeOffsetHours > 0) {
                            context.getString(R.string.date_change_offset_hours, dateChangeOffsetHours) + " (${context.getString(R.string.date_change_offset_time, dateChangeOffsetHours)})"
                        } else {
                            context.getString(R.string.date_change_offset_hours, dateChangeOffsetHours) + " (${context.getString(R.string.date_change_offset_previous_day, 24 + dateChangeOffsetHours)})"
                        },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    
                    IconButton(
                        onClick = {
                            if (!canEditInstitutionSettings) return@IconButton
                            if (dateChangeOffsetHours < 12) {
                                dateChangeOffsetHours++
                                settingsManager.saveDateChangeOffsetHours(dateChangeOffsetHours)
                                viewModel.backupInstitutionSettingsToSheets()
                            }
                        },
                        enabled = canEditInstitutionSettings && dateChangeOffsetHours < 12
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = context.getString(R.string.date_change_offset_increase)
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Animations & Effects Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_animations),
            icon = Icons.Default.PlayArrow,
            isExpanded = showAnimationSettings,
            onToggleExpanded = { 
                showAnimationSettings = !showAnimationSettings
                settingsManager.setCategoryAnimationExpanded(showAnimationSettings)
            }
        ) {
            if (variant == SettingsScreenVariant.Full) {
            // Background animation style (admin only — billeterie has its own section)
            BackgroundAnimationSettingsSection(
                settingsManager = settingsManager,
                isDesktop = false,
                target = BackgroundAnimationSettingsTarget.Admin,
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Page Animations Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var pageAnimationsEnabled by remember { mutableStateOf(settingsManager.isPageAnimationsEnabled()) }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = context.getString(R.string.page_animations_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = context.getString(R.string.page_animations_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pageAnimationsEnabled,
                    onCheckedChange = {
                        pageAnimationsEnabled = it
                        settingsManager.setPageAnimationsEnabled(it)
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Seasonal Fun Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var seasonalFunEnabled by remember { mutableStateOf(settingsManager.isSeasonalFunEnabled()) }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = context.getString(R.string.seasonal_fun_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = context.getString(R.string.seasonal_fun_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = seasonalFunEnabled,
                    onCheckedChange = {
                        seasonalFunEnabled = it
                        settingsManager.setSeasonalFunEnabled(it)
                    }
                )
            }
            
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (variant == SettingsScreenVariant.Full) {
        // Developer & Debug Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_developer),
            icon = Icons.Default.BugReport,
            isExpanded = showDeveloperSettings,
            onToggleExpanded = { 
                showDeveloperSettings = !showDeveloperSettings
                settingsManager.setCategoryDeveloperExpanded(showDeveloperSettings)
            }
        ) {
            // Debug Mode Toggle
            var debugModeEnabled by remember { mutableStateOf(settingsManager.getDebugMode()) }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = context.getString(R.string.debug_mode_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = context.getString(R.string.debug_mode_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = debugModeEnabled,
                    onCheckedChange = {
                        debugModeEnabled = it
                        settingsManager.saveDebugMode(it)
                        com.eventmanager.app.data.sync.FileAppLogger.setIntercepting(it)
                        com.eventmanager.app.data.sync.FileAppLogger.i("SettingsScreen", "Debug mode ${if (it) "enabled" else "disabled"}")
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Log Files Management (shown when debug mode is enabled)
            if (debugModeEnabled) {
                var logFilesState by remember { mutableStateOf(com.eventmanager.app.data.sync.FileAppLogger.getAllLogFiles()) }
                var totalLogSizeState by remember { mutableStateOf(com.eventmanager.app.data.sync.FileAppLogger.getTotalLogSize()) }
                var showLogViewer by remember { mutableStateOf(false) }
                var selectedLogContent by remember { mutableStateOf<String?>(null) }
                val logsDirectoryPath = remember { com.eventmanager.app.data.sync.FileAppLogger.getLogsDirectoryPath() }
                val logCoroutineScope = rememberCoroutineScope()
                
                LaunchedEffect(debugModeEnabled) {
                    if (debugModeEnabled) {
                        logFilesState = com.eventmanager.app.data.sync.FileAppLogger.getAllLogFiles()
                        totalLogSizeState = com.eventmanager.app.data.sync.FileAppLogger.getTotalLogSize()
                    }
                }
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = context.getString(R.string.debug_logs_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = context.getString(R.string.debug_logs_location, logsDirectoryPath),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = context.getString(
                            R.string.debug_logs_files_size,
                            logFilesState.size,
                            String.format("%.2f", totalLogSizeState / (1024.0 * 1024.0))
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (logFilesState.isNotEmpty()) {
                        Text(
                            text = context.getString(R.string.debug_logs_recent_files),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        logFilesState.takeLast(3).reversed().forEach { logFile ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = logFile.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format("%.2f KB", logFile.length() / 1024.0)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = {
                                            logCoroutineScope.launch {
                                                selectedLogContent = try {
                                                    logFile.readText()
                                                } catch (e: Exception) {
                                                    context.getString(R.string.debug_logs_error_reading, e.message ?: "")
                                                }
                                                showLogViewer = true
                                            }
                                        }
                                    ) {
                                        Text(context.getString(R.string.debug_logs_view), style = MaterialTheme.typography.labelSmall)
                                    }
                                    
                                    TextButton(
                                        onClick = {
                                            try {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "com.eventmanager.app.fileprovider",
                                                    logFile
                                                )
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.debug_logs_share_file)))
                                            } catch (e: Exception) {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, try {
                                                        logFile.readText()
                                                    } catch (ex: Exception) {
                                                        context.getString(R.string.debug_logs_error_reading_generic)
                                                    })
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.debug_logs_share_file)))
                                            }
                                        }
                                    ) {
                                        Text(context.getString(R.string.debug_logs_share), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (logFilesState.isNotEmpty()) {
                            Button(
                                onClick = {
                                    logCoroutineScope.launch {
                                        selectedLogContent = com.eventmanager.app.data.sync.FileAppLogger.getLatestLogContent()
                                        showLogViewer = true
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Text(
                                    text = context.getString(R.string.debug_logs_view_latest),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        
                        Button(
                            onClick = {
                                com.eventmanager.app.data.sync.FileAppLogger.clearAllLogs()
                                logFilesState = com.eventmanager.app.data.sync.FileAppLogger.getAllLogFiles()
                                totalLogSizeState = com.eventmanager.app.data.sync.FileAppLogger.getTotalLogSize()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = context.getString(R.string.debug_logs_clear_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                
                if (showLogViewer && selectedLogContent != null) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { showLogViewer = false }) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .fillMaxHeight(0.8f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = context.getString(R.string.debug_logs_content),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(onClick = { showLogViewer = false }) {
                                        Icon(Icons.Default.Close, contentDescription = context.getString(R.string.close))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                val scrollState = rememberScrollState()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(scrollState)
                                ) {
                                    Text(
                                        text = selectedLogContent ?: context.getString(R.string.debug_logs_no_content),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Biometric Admin Login Toggle
            val biometricManager = remember { BiometricManager.from(context) }
            val canAuthenticate = remember {
                biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }
            var biometricEnabled by remember { mutableStateOf(settingsManager.isBiometricAdminLoginEnabled()) }
            var showBiometricWarningDialog by remember { mutableStateOf(false) }
            var showBiometricAdminVerifyDialog by remember { mutableStateOf(false) }
            var showBiometricOrgWizard by remember { mutableStateOf(false) }
            var pendingBiometricEnrollmentMatch by remember { mutableStateOf<ScannerMatch?>(null) }
            var pendingBiometricEnrollments by remember { mutableStateOf<List<BiometricAdminOrgEnrollment>?>(null) }

            val configuredOrgs = viewModel.getFirebaseConfiguredOrgs()
            val useMultiOrgBiometricWizard =
                settingsManager.getBackendType() == BackendType.FIREBASE && configuredOrgs.size > 1
            val biometricEnrollments = remember(biometricEnabled) {
                settingsManager.getBiometricEnrollments()
            }

            val biometricAvailable = canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
            val noneEnrolled = canAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.biometric_admin_login_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            noneEnrolled -> context.getString(R.string.biometric_admin_login_none_enrolled)
                            !biometricAvailable -> context.getString(R.string.biometric_admin_login_not_available)
                            else -> context.getString(R.string.biometric_admin_login_description)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!biometricAvailable) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = biometricEnabled,
                    onCheckedChange = { wantsEnabled ->
                        if (wantsEnabled) {
                            showBiometricWarningDialog = true
                        } else {
                            biometricEnabled = false
                            settingsManager.setBiometricAdminLoginEnabled(false)
                            Toast.makeText(context, context.getString(R.string.biometric_admin_login_disabled), Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = biometricAvailable
                )
            }

            if (biometricEnabled && useMultiOrgBiometricWizard && biometricEnrollments.isNotEmpty()) {
                biometricEnrollments.forEach { enrollment ->
                    Text(
                        text = context.getString(
                            R.string.biometric_enrollment_org_enrolled,
                            FirebaseOrgAbbreviation.abbreviate(enrollment.orgId),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }

            // Warning dialog
            if (showBiometricWarningDialog) {
                AlertDialog(
                    onDismissRequest = { showBiometricWarningDialog = false },
                    icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text(context.getString(R.string.biometric_warning_title), fontWeight = FontWeight.Bold) },
                    text = { Text(context.getString(R.string.biometric_warning_message)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                showBiometricWarningDialog = false
                                if (useMultiOrgBiometricWizard) {
                                    showBiometricOrgWizard = true
                                } else {
                                    showBiometricAdminVerifyDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(context.getString(R.string.biometric_warning_accept))
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showBiometricWarningDialog = false }) {
                            Text(context.getString(R.string.biometric_warning_cancel))
                        }
                    }
                )
            }

            if (showBiometricOrgWizard) {
                BiometricOrgEnrollmentWizard(
                    platformContext = remember(context) { com.eventmanager.app.platform.createPlatformContext(context) },
                    viewModel = viewModel,
                    configuredOrgs = configuredOrgs,
                    onCompleted = { enrollments ->
                        showBiometricOrgWizard = false
                        pendingBiometricEnrollments = enrollments
                    },
                    onDismiss = { showBiometricOrgWizard = false },
                )
            }

            // Admin verification dialog (QR / NFC) before enrolling biometrics
            if (showBiometricAdminVerifyDialog) {
                BiometricAdminVerificationDialog(
                    platformContext = remember(context) { com.eventmanager.app.platform.createPlatformContext(context) },
                    viewModel = viewModel,
                    onVerified = { match ->
                        showBiometricAdminVerifyDialog = false
                        pendingBiometricEnrollmentMatch = match
                    },
                    onDismiss = { showBiometricAdminVerifyDialog = false }
                )
            }

            // Once admin is verified via QR/NFC, prompt for biometric enrollment
            LaunchedEffect(pendingBiometricEnrollmentMatch) {
                val match = pendingBiometricEnrollmentMatch ?: return@LaunchedEffect
                pendingBiometricEnrollmentMatch = null
                    val fragmentActivity = context as? FragmentActivity ?: return@LaunchedEffect
                val profileLink = match.toBiometricAdminProfileLink()
                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle(context.getString(R.string.biometric_enrollment_prompt_title))
                        .setSubtitle(context.getString(R.string.biometric_enrollment_prompt_subtitle))
                        .setNegativeButtonText(context.getString(R.string.biometric_warning_cancel))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .build()
                    val biometricPrompt = BiometricPrompt(
                        fragmentActivity,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            settingsManager.setBiometricAdminProfileLink(profileLink)
                                biometricEnabled = true
                                Toast.makeText(context, context.getString(R.string.biometric_enrollment_success), Toast.LENGTH_LONG).show()
                            }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                    Toast.makeText(context, context.getString(R.string.admin_auth_biometric_error, errString), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    biometricPrompt.authenticate(promptInfo)
            }

            LaunchedEffect(pendingBiometricEnrollments) {
                val enrollments = pendingBiometricEnrollments ?: return@LaunchedEffect
                pendingBiometricEnrollments = null
                val fragmentActivity = context as? FragmentActivity ?: return@LaunchedEffect
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(context.getString(R.string.biometric_enrollment_prompt_title))
                    .setSubtitle(context.getString(R.string.biometric_enrollment_prompt_subtitle))
                    .setNegativeButtonText(context.getString(R.string.biometric_warning_cancel))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()
                val biometricPrompt = BiometricPrompt(
                    fragmentActivity,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            settingsManager.setBiometricEnrollments(enrollments)
                            biometricEnabled = true
                            Toast.makeText(context, context.getString(R.string.biometric_enrollment_success), Toast.LENGTH_LONG).show()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                Toast.makeText(context, context.getString(R.string.admin_auth_biometric_error, errString), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                biometricPrompt.authenticate(promptInfo)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Maintenance Settings
        ExpandableSettingsCategory(
            title = context.getString(R.string.settings_category_maintenance),
            icon = Icons.Default.Build,
            isExpanded = showMaintenanceSettings,
            onToggleExpanded = { 
                showMaintenanceSettings = !showMaintenanceSettings
                settingsManager.setCategoryMaintenanceExpanded(showMaintenanceSettings)
            }
        ) {
            // View Active Volunteers Button
            Button(
                onClick = { showActiveVolunteersDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.People, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(context.getString(R.string.view_active_volunteers))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Cleanup Inactive Volunteers Button
            Button(
                onClick = { showCleanupDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(context.getString(R.string.cleanup_inactive_volunteers))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Check for updates
            Button(
                onClick = {
                    viewModel.checkForAppUpdates()
                    showUpdateResultDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(context.getString(R.string.check_for_updates))
            }
            
            // Change update sources link
            TextButton(
                onClick = { showUpdateSourcesDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = context.getString(R.string.change_update_sources),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Clear Cache Button
            OutlinedButton(
                onClick = { 
                    // Clear app data cache (database contents) but keep settings and key file
                    viewModel.clearAppData()
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.app_cache_cleared),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Clear, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(context.getString(R.string.clear_app_cache))
            }

            Spacer(modifier = Modifier.height(8.dp))

            FactoryResetSettingsSection(
                onResetClick = { showFactoryResetDialog = true },
            )
        }
        
        if (showUpdateSourcesDialog) {
            UpdateSourcesDialog(
                settingsManager = settingsManager,
                onDismiss = { showUpdateSourcesDialog = false }
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Job Type Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Work,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = context.getString(R.string.shift_type_management_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = context.getString(R.string.shift_type_management_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onNavigateToJobTypeManagement,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.manage_shift_types))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Sales Items Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PointOfSale,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = context.getString(R.string.sales_items_management_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = context.getString(R.string.sales_items_management_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onNavigateToSalesSheetItemManagement,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.manage_sales_items))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Venue Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = context.getString(R.string.venue_management_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = context.getString(R.string.venue_management_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onNavigateToVenueManagement,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.manage_venues))
                }
            }
        }

        if (temporaryGuestFeaturesEnabled) {
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = context.getString(R.string.temp_guest_access_settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TemporaryGuestAccessSettingsSection(
                        activeVenues = activeVenues,
                        accesses = temporaryGuestAccesses,
                        creditsEnabled = temporaryGuestCreditsEnabled,
                        canEdit = canEditInstitutionSettings,
                        onAddAccess = { venueName, name ->
                            viewModel.addTemporaryGuestVenueAccess(venueName, name)
                        },
                        onRemoveAccess = { viewModel.removeTemporaryGuestVenueAccess(it) },
                        onCreditsEnabledChange = { viewModel.setTemporaryGuestCreditsEnabled(it) },
                    )

                    if (!canEditInstitutionSettings) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = context.getString(R.string.institution_settings_firebase_admin_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // App Information Section with Easter Egg
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val currentTime = System.currentTimeMillis()
                    // Reset if more than 500ms between taps
                    if (currentTime - lastEasterEggTapTime > 500) {
                        easterEggTapCount = 1
                    } else {
                        easterEggTapCount++
                    }
                    lastEasterEggTapTime = currentTime
                    
                    // Trigger Easter Egg after 10 rapid taps
                    if (easterEggTapCount >= 10) {
                        showEasterEggDialog = true
                        easterEggTapCount = 0
                    }
                },
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = context.getString(R.string.app_info_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${context.getString(R.string.app_name)} ${platformContext.installedVersionName()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = context.getString(R.string.app_designed_for),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = context.getString(R.string.app_developed_full),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = context.getString(
                            R.string.last_sync,
                            if (settingsManager.getLastSyncTime() > 0)
                                formatDateTime(
                                    settingsManager.getLastSyncTime(),
                                    context
                                )
                            else context.getString(R.string.never)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (variant == SettingsScreenVariant.BilleterieBasic || variant == SettingsScreenVariant.PosBasic) {
            Spacer(modifier = Modifier.height(24.dp))
            FactoryResetSettingsSection(
                onResetClick = { showFactoryResetDialog = true },
            )
        }
    }

    // Update result dialog
    if (showUpdateResultDialog) {
        val updateResult = viewModel.updateCheckState.collectAsState().value
        val downloadState = viewModel.updateDownloadState.collectAsState().value
        
        when (updateResult) {
            is com.eventmanager.app.data.update.UpdateCheckResult.UpdateAvailable -> {
                val manifest = updateResult.manifest
                val isRequired = updateResult.isRequired
                
                // Show download progress if downloading
                when (downloadState) {
                    is com.eventmanager.app.data.update.DownloadState.Downloading -> {
                        AlertDialog(
                            onDismissRequest = { },
                            title = {
                                Text(text = context.getString(R.string.downloading_update))
                            },
                            text = {
                                Column {
                                    LinearProgressIndicator(
                                        progress = { downloadState.progress / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = context.getString(R.string.download_progress, downloadState.progress),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            },
                            confirmButton = {}
                        )
                    }
                    is com.eventmanager.app.data.update.DownloadState.Downloaded -> {
                        AlertDialog(
                            onDismissRequest = if (isRequired) { {} } else { { showUpdateResultDialog = false } },
                            properties = if (isRequired) {
                                DialogProperties(
                                    dismissOnBackPress = false,
                                    dismissOnClickOutside = false
                                )
                            } else {
                                DialogProperties()
                            },
                            title = {
                                Text(text = context.getString(R.string.download_complete))
                            },
                            text = {
                                Text(text = context.getString(R.string.update_available_message))
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.installUpdate(downloadState.filePath)
                                    showUpdateResultDialog = false
                                }) {
                                    Text(context.getString(R.string.install_update))
                                }
                            },
                            dismissButton = if (!isRequired) {
                                {
                                    TextButton(onClick = { showUpdateResultDialog = false }) {
                                        Text(context.getString(R.string.later))
                                    }
                                }
                            } else null
                        )
                    }
                    is com.eventmanager.app.data.update.DownloadState.Error -> {
                        AlertDialog(
                            onDismissRequest = if (isRequired) { {} } else { { showUpdateResultDialog = false } },
                            title = {
                                Text(text = context.getString(R.string.download_error_title))
                            },
                            text = {
                                Text(text = context.getString(R.string.download_error_message, downloadState.message))
                            },
                            confirmButton = {
                                TextButton(onClick = { showUpdateResultDialog = false }) {
                                    Text(context.getString(R.string.ok))
                                }
                            }
                        )
                    }
                    else -> {
                        // Show update available dialog
                        AlertDialog(
                            onDismissRequest = if (isRequired) { {} } else { { showUpdateResultDialog = false } },
                            title = {
                                Text(text = context.getString(R.string.update_available_title, manifest.latestVersionName))
                            },
                            text = {
                                Text(
                                    text = manifest.changelogShort
                                        ?: if (isRequired) {
                                            context.getString(R.string.update_required_message)
                                        } else {
                                            context.getString(R.string.update_available_message)
                                        }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    // Start download instead of opening browser
                                    val downloadUrl = manifest.downloadUrl
                                    if (downloadUrl != null) {
                                        viewModel.downloadUpdate(downloadUrl)
                                    } else {
                                        // Fallback to browser if no download URL
                                        val targetUrl = manifest.storeUrl
                                            ?: settingsManager.getUpdateStoreUrl()
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                        context.startActivity(intent)
                                        showUpdateResultDialog = false
                                    }
                                }) {
                                    Text(context.getString(R.string.update_now))
                                }
                            },
                            dismissButton = {
                                if (!isRequired) {
                                    TextButton(onClick = { showUpdateResultDialog = false }) {
                                        Text(context.getString(R.string.later))
                                    }
                                }
                            }
                        )
                    }
                }
            }
            is com.eventmanager.app.data.update.UpdateCheckResult.NoUpdate -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResultDialog = false },
                    title = {
                        Text(text = context.getString(R.string.up_to_date_title))
                    },
                    text = {
                        Text(text = context.getString(R.string.up_to_date_message))
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResultDialog = false }) {
                            Text(context.getString(R.string.ok))
                        }
                    }
                )
            }
            is com.eventmanager.app.data.update.UpdateCheckResult.Error -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResultDialog = false },
                    title = {
                        Text(text = context.getString(R.string.update_error_title))
                    },
                    text = {
                        Text(text = context.getString(R.string.update_error_message, updateResult.message))
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResultDialog = false }) {
                            Text(context.getString(R.string.ok))
                        }
                    }
                )
            }
            else -> {
                // Still loading or no result yet: simple loading dialog
                AlertDialog(
                    onDismissRequest = { showUpdateResultDialog = false },
                    title = {
                        Text(text = context.getString(R.string.checking_for_updates_title))
                    },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = context.getString(R.string.checking_for_updates_message))
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResultDialog = false }) {
                            Text(context.getString(R.string.cancel))
                        }
                    }
                )
            }
        }
    }
    
    // Instructions Dialog
    if (showInstructions) {
        when (settingsManager.getBackendType()) {
            com.eventmanager.app.data.remote.BackendType.FIREBASE ->
                com.eventmanager.app.ui.components.FirebaseSetupTutorialDialog(
                    onDismiss = { showInstructions = false },
                    projectId = settingsManager.getFirebaseProjectId(),
                )
            com.eventmanager.app.data.remote.BackendType.SHEETS ->
                GoogleSheetsInstructionsDialog(
                    onDismiss = { showInstructions = false },
                )
        }
    }
    
    // Sync Status Dialog (used by test connection button)
    SyncStatusDialog(
        isVisible = showSyncStatusDialog,
        onDismiss = { viewModel.dismissSyncStatusDialog() },
        statusMessage = syncStatusMessage
    )
    
    // Active Volunteers Dialog
    if (showActiveVolunteersDialog) {
        ActiveVolunteersDialog(
            volunteers = viewModel.volunteers.collectAsState().value,
            jobs = viewModel.jobs.collectAsState().value,
            onDismiss = { showActiveVolunteersDialog = false }
        )
    }
    
    // Cleanup Inactive Volunteers Dialog
    if (showCleanupDialog) {
        CleanupInactiveVolunteersDialog(
            volunteers = viewModel.volunteers.collectAsState().value,
            jobs = viewModel.jobs.collectAsState().value,
            onConfirm = { yearsInactive ->
                viewModel.cleanupInactiveVolunteers(yearsInactive)
                showCleanupDialog = false
            },
            onDismiss = { showCleanupDialog = false }
        )
    }

    if (showFactoryResetDialog) {
        val platformFileManager = remember(platformContext) {
            com.eventmanager.app.platform.PlatformFileManager(platformContext)
        }
        AlertDialog(
            onDismissRequest = { if (!factoryResetInProgress) showFactoryResetDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(context.getString(R.string.factory_reset_confirm_title)) },
            text = { Text(context.getString(R.string.factory_reset_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (factoryResetInProgress) return@Button
                        factoryResetInProgress = true
                        viewModel.factoryReset(
                            settingsManager = settingsManager,
                            fileManager = platformFileManager,
                        ) {
                            showFactoryResetDialog = false
                            factoryResetInProgress = false
                            onFactoryResetComplete()
                        }
                    },
                    enabled = !factoryResetInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (factoryResetInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.factory_reset_in_progress))
                    } else {
                        Text(context.getString(R.string.factory_reset_confirm_button))
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showFactoryResetDialog = false },
                    enabled = !factoryResetInProgress,
                ) {
                    Text(context.getString(R.string.cancel))
                }
            },
        )
    }
    
    // Easter Egg Dialog
    if (showEasterEggDialog) {
        RetroSynthwaveGameDialog(
            onDismiss = { showEasterEggDialog = false },
            onHextrisSelected = {
                showEasterEggDialog = false
                showHextrisGame = true
            },
            onPizzaUndeliverySelected = {
                showEasterEggDialog = false
                showPizzaUndeliveryGame = true
            },
            onScrollSelected = {
                showEasterEggDialog = false
                showScrollGame = true
            },
            onWendolVillageSelected = {
                showEasterEggDialog = false
                showWendolVillageGame = true
            },
            onCatculusSelected = {
                showEasterEggDialog = false
                showCatculusGame = true
            }
        )
    }
    
    // Hextris Game Dialog
    if (showHextrisGame) {
        HextrisGameDialog(
            onDismiss = { showHextrisGame = false }
        )
    }
    
    // Pizza Undelivery Game Dialog
    if (showPizzaUndeliveryGame) {
        PizzaUndeliveryGameDialog(
            onDismiss = { showPizzaUndeliveryGame = false }
        )
    }

    // Scroll Game Dialog
    if (showScrollGame) {
        ScrollGameDialog(
            onDismiss = { showScrollGame = false }
        )
    }

    // Wendol Village Game Dialog
    if (showWendolVillageGame) {
        WendolVillageGameDialog(
            onDismiss = { showWendolVillageGame = false }
        )
    }

    if (showCatculusGame) {
        CatculusGameDialog(
            onDismiss = { showCatculusGame = false }
        )
    }
    
}

@Composable
fun GoogleSheetsInstructionsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                context.getString(R.string.google_sheets_setup_instructions),
                fontWeight = FontWeight.Bold
            ) 
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = context.getString(R.string.setup_instructions_intro),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                item {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.open_google_cloud_console))
                    }
                }
                
                item {
                    InstructionStep(
                        number = "1",
                        title = context.getString(R.string.step_1_title),
                        description = context.getString(R.string.step_1_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "2",
                        title = context.getString(R.string.step_2_title),
                        description = context.getString(R.string.step_2_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "3",
                        title = context.getString(R.string.step_3_title),
                        description = context.getString(R.string.step_3_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "4",
                        title = context.getString(R.string.step_4_title),
                        description = context.getString(R.string.step_4_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "5",
                        title = context.getString(R.string.step_5_title),
                        description = context.getString(R.string.step_5_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "6",
                        title = context.getString(R.string.step_6_title),
                        description = context.getString(R.string.step_6_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "7",
                        title = context.getString(R.string.step_7_title),
                        description = context.getString(R.string.step_7_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "8",
                        title = context.getString(R.string.step_8_title),
                        description = context.getString(R.string.step_8_description)
                    )
                }
                
                item {
                    InstructionStep(
                        number = "9",
                        title = context.getString(R.string.step_9_title),
                        description = context.getString(R.string.step_9_description)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.got_it))
            }
        }
    )
}

@Composable
fun InstructionStep(
    number: String,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Card(
            modifier = Modifier.size(24.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun UpdateSourcesDialog(
    settingsManager: SettingsManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    var githubUrl by remember { mutableStateOf(settingsManager.getUpdateManifestUrl()) }
    var storeUrl by remember { mutableStateOf(settingsManager.getUpdateStoreUrl()) }
    
    val defaultGithubUrl = AppBuildInfo.UPDATE_MANIFEST_URL
    val defaultStoreUrl = AppBuildInfo.UPDATE_FALLBACK_STORE_URL
    
    val isGithubUrlChanged = githubUrl != defaultGithubUrl
    val isStoreUrlChanged = storeUrl != defaultStoreUrl
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = context.getString(R.string.update_sources_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Warning card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = context.getString(R.string.update_sources_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                
                // GitHub URL field
                Column {
                    Text(
                        text = context.getString(R.string.update_sources_github_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = githubUrl,
                        onValueChange = { githubUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { 
                            Text(
                                text = defaultGithubUrl,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Code,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (isGithubUrlChanged) {
                                IconButton(
                                    onClick = { githubUrl = defaultGithubUrl }
                                ) {
                                    Icon(
                                        Icons.Default.Restore,
                                        contentDescription = context.getString(R.string.update_sources_reset),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = false,
                        maxLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    if (isGithubUrlChanged) {
                        Text(
                            text = context.getString(R.string.update_sources_custom_url),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
                
                // Store URL field
                Column {
                    Text(
                        text = context.getString(R.string.update_sources_store_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = storeUrl,
                        onValueChange = { storeUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { 
                            Text(
                                text = defaultStoreUrl,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Store,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (isStoreUrlChanged) {
                                IconButton(
                                    onClick = { storeUrl = defaultStoreUrl }
                                ) {
                                    Icon(
                                        Icons.Default.Restore,
                                        contentDescription = context.getString(R.string.update_sources_reset),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = false,
                        maxLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    if (isStoreUrlChanged) {
                        Text(
                            text = context.getString(R.string.update_sources_custom_url),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    settingsManager.saveUpdateManifestUrl(githubUrl)
                    settingsManager.saveUpdateStoreUrl(storeUrl)
                    onDismiss()
                }
            ) {
                Text(context.getString(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}
