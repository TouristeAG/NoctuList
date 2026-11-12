package com.eventmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirebaseJoinCodec
import com.eventmanager.app.data.remote.FirebaseJoinImport
import com.eventmanager.app.data.remote.FirebaseJoinImportProblem
import com.eventmanager.app.data.remote.FirebaseJoinImportResult
import com.eventmanager.app.data.remote.FirebaseJoinPayload
import com.eventmanager.app.data.remote.MemberRoleAdmin
import com.eventmanager.app.data.remote.createFirestoreGateway
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.security.firebaseOAuthCredentialsReady
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.firebase_api_key_label
import com.eventmanager.app.resources.firebase_application_id_label
import com.eventmanager.app.resources.firebase_config_paste_apply
import com.eventmanager.app.resources.firebase_config_paste_hint
import com.eventmanager.app.resources.firebase_config_paste_label
import com.eventmanager.app.resources.firebase_hide_secrets
import com.eventmanager.app.resources.firebase_join_code_copied
import com.eventmanager.app.resources.firebase_join_config_code_hint
import com.eventmanager.app.resources.firebase_join_config_code_label
import com.eventmanager.app.resources.firebase_join_copy_code
import com.eventmanager.app.resources.firebase_join_error_oauth_missing
import com.eventmanager.app.resources.firebase_join_error_invite_missing
import com.eventmanager.app.resources.firebase_join_invite_admin_body
import com.eventmanager.app.resources.firebase_join_invite_code_hint
import com.eventmanager.app.resources.firebase_join_invite_code_label
import com.eventmanager.app.resources.firebase_join_invite_copied
import com.eventmanager.app.resources.firebase_join_invite_copy
import com.eventmanager.app.resources.firebase_join_invite_create
import com.eventmanager.app.resources.firebase_join_invite_missing_body
import com.eventmanager.app.resources.firebase_join_invite_rotate
import com.eventmanager.app.resources.firebase_join_or_enter_codes
import com.eventmanager.app.resources.firebase_join_paste_code
import com.eventmanager.app.resources.firebase_join_qr_body
import com.eventmanager.app.resources.firebase_join_qr_need_config
import com.eventmanager.app.resources.firebase_join_qr_restricted
import com.eventmanager.app.resources.firebase_join_qr_title
import com.eventmanager.app.resources.firebase_join_received
import com.eventmanager.app.resources.firebase_join_scan
import com.eventmanager.app.resources.firebase_join_scan_body
import com.eventmanager.app.resources.firebase_join_scan_title
import com.eventmanager.app.resources.firebase_project_configured_hidden
import com.eventmanager.app.resources.firebase_project_id_label
import com.eventmanager.app.resources.firebase_project_secrets_restricted
import com.eventmanager.app.resources.firebase_reveal_secrets
import com.eventmanager.app.resources.firebase_step_project_body
import com.eventmanager.app.resources.firebase_step_project_title
import com.eventmanager.app.resources.firebase_step_project_where
import com.eventmanager.app.resources.firebase_advanced_project
import com.eventmanager.app.resources.firebase_hide_project
import com.eventmanager.app.resources.firebase_web_client_id_label
import com.eventmanager.app.resources.firebase_web_client_secret_label
import com.eventmanager.app.utils.QRCodeUtils
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Admin: paste Firebase web config + manual project fields (masked).
 */
@Composable
fun FirebaseAdminProjectConfigCard(
    settingsManager: SettingsManager,
    projectId: String,
    applicationId: String,
    apiKey: String,
    webClientId: String,
    webClientSecret: String,
    onProjectIdChange: (String) -> Unit,
    onApplicationIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onWebClientIdChange: (String) -> Unit,
    onWebClientSecretChange: (String) -> Unit,
    onAppliedFromPaste: () -> Unit = {},
    allowProjectSecrets: Boolean = true,
) {
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var revealSecrets by remember { mutableStateOf(false) }
    val projectReady = projectId.isNotBlank() && applicationId.isNotBlank() && apiKey.isNotBlank()
    val oauthReady = firebaseOAuthCredentialsReady(webClientId, webClientSecret)
    var showManualFields by remember {
        mutableStateOf(allowProjectSecrets && (!projectReady || !oauthReady))
    }

    LaunchedEffect(allowProjectSecrets, oauthReady) {
        if (!allowProjectSecrets) {
            showManualFields = false
            revealSecrets = false
        } else if (!oauthReady) {
            showManualFields = true
        }
    }

    val secretTransform = if (revealSecrets) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }

    GuidedStepCard(
        title = stringResource(Res.string.firebase_step_project_title),
        body = if (allowProjectSecrets) {
            stringResource(Res.string.firebase_step_project_body)
        } else {
            stringResource(Res.string.firebase_project_secrets_restricted)
        },
    ) {
        if (!allowProjectSecrets) {
            Text(
                stringResource(Res.string.firebase_project_configured_hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            return@GuidedStepCard
        }

        GuidedStepCard(
            title = stringResource(Res.string.firebase_config_paste_label),
            body = stringResource(Res.string.firebase_config_paste_hint),
        ) {
            OutlinedTextField(
                value = pasteText,
                onValueChange = {
                    pasteText = it
                    pasteError = null
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            Button(
                onClick = {
                    FirebaseJoinCodec.parseFirebaseWebConfig(pasteText).fold(
                        onSuccess = { cfg ->
                            onProjectIdChange(cfg.projectId)
                            onApplicationIdChange(cfg.applicationId)
                            onApiKeyChange(cfg.apiKey)
                            if (cfg.webClientId.isNotBlank()) onWebClientIdChange(cfg.webClientId)
                            if (cfg.webClientSecret.isNotBlank()) onWebClientSecretChange(cfg.webClientSecret)
                            if (cfg.gcmSenderId.isNotBlank()) {
                                settingsManager.setFirebaseGcmSenderId(cfg.gcmSenderId)
                            }
                            if (cfg.storageBucket.isNotBlank()) {
                                settingsManager.setFirebaseStorageBucket(cfg.storageBucket)
                            }
                            settingsManager.setFirebaseProjectId(cfg.projectId)
                            settingsManager.setFirebaseApplicationId(cfg.applicationId)
                            settingsManager.setFirebaseApiKey(cfg.apiKey)
                            if (cfg.webClientId.isNotBlank()) {
                                settingsManager.setFirebaseWebClientId(cfg.webClientId)
                            }
                            if (cfg.webClientSecret.isNotBlank()) {
                                settingsManager.setFirebaseWebClientSecret(cfg.webClientSecret)
                            }
                            pasteError = null
                            pasteText = ""
                            showManualFields = true
                            onAppliedFromPaste()
                        },
                        onFailure = { e ->
                            pasteError = e.message ?: "Invalid config"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = pasteText.isNotBlank(),
            ) {
                Text(stringResource(Res.string.firebase_config_paste_apply))
            }
            pasteError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (projectReady && !showManualFields) {
            Text(
                stringResource(Res.string.firebase_project_configured_hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = { showManualFields = true }) {
                Text(stringResource(Res.string.firebase_advanced_project))
            }
        } else {
            TextButton(onClick = { showManualFields = !showManualFields }) {
                Text(
                    if (showManualFields) stringResource(Res.string.firebase_hide_project)
                    else stringResource(Res.string.firebase_advanced_project),
                )
            }
        }

        if (showManualFields) {
            Text(
                stringResource(Res.string.firebase_step_project_where),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { revealSecrets = !revealSecrets }) {
                Text(
                    stringResource(
                        if (revealSecrets) Res.string.firebase_hide_secrets else Res.string.firebase_reveal_secrets,
                    ),
                )
            }
            OutlinedTextField(
                value = projectId,
                onValueChange = {
                    onProjectIdChange(it)
                    settingsManager.setFirebaseProjectId(it)
                },
                label = { Text(stringResource(Res.string.firebase_project_id_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = secretTransform,
            )
            OutlinedTextField(
                value = applicationId,
                onValueChange = {
                    onApplicationIdChange(it)
                    settingsManager.setFirebaseApplicationId(it)
                },
                label = { Text(stringResource(Res.string.firebase_application_id_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = secretTransform,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    onApiKeyChange(it)
                    settingsManager.setFirebaseApiKey(it)
                },
                label = { Text(stringResource(Res.string.firebase_api_key_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = secretTransform,
            )
            OutlinedTextField(
                value = webClientId,
                onValueChange = {
                    onWebClientIdChange(it)
                    settingsManager.setFirebaseWebClientId(it)
                },
                label = { Text(stringResource(Res.string.firebase_web_client_id_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = secretTransform,
            )
            OutlinedTextField(
                value = webClientSecret,
                onValueChange = {
                    onWebClientSecretChange(it)
                    settingsManager.setFirebaseWebClientSecret(it)
                },
                label = { Text(stringResource(Res.string.firebase_web_client_secret_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = secretTransform,
            )
        }
    }
}

@Composable
fun FirebaseAdminJoinQrCard(
    orgId: String,
    projectId: String,
    applicationId: String,
    apiKey: String,
    webClientId: String,
    webClientSecret: String,
    bootstrapCode: String = "",
    allowProjectSecrets: Boolean = true,
    onBootstrapCodeChange: (String) -> Unit = {},
    onRotateBootstrapCode: (suspend () -> String)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copiedHint by remember { mutableStateOf(false) }
    var copiedInvite by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    var rotateError by remember { mutableStateOf<String?>(null) }
    var revealInvite by remember { mutableStateOf(false) }

    val encoded = remember(
        allowProjectSecrets,
        orgId,
        projectId,
        applicationId,
        apiKey,
        webClientId,
        webClientSecret,
        bootstrapCode,
    ) {
        if (!allowProjectSecrets) return@remember null
        firebaseJoinConfigCode(
            orgId = orgId,
            projectId = projectId,
            applicationId = applicationId,
            apiKey = apiKey,
            webClientId = webClientId,
            webClientSecret = webClientSecret,
            bootstrapCode = bootstrapCode,
        )
    }
    val qrBitmap = remember(encoded) {
        encoded?.let { QRCodeUtils.generateQrImageBitmap(it, 512) }
    }

    GuidedStepCard(
        title = stringResource(Res.string.firebase_join_qr_title),
        body = if (allowProjectSecrets) {
            stringResource(Res.string.firebase_join_qr_body)
        } else {
            stringResource(Res.string.firebase_join_qr_restricted)
        },
    ) {
        if (!allowProjectSecrets) {
            return@GuidedStepCard
        }
        if (bootstrapCode.isBlank()) {
            Text(
                stringResource(Res.string.firebase_join_invite_missing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRotateBootstrapCode != null) {
                Button(
                    onClick = {
                        rotating = true
                        rotateError = null
                        scope.launch {
                            runCatching { onRotateBootstrapCode() }
                                .onSuccess { code ->
                                    onBootstrapCodeChange(code)
                                    rotating = false
                                }
                                .onFailure { e ->
                                    rotateError = e.message ?: "Failed to create invitation code"
                                    rotating = false
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !rotating,
                ) {
                    Text(stringResource(Res.string.firebase_join_invite_create))
                }
            }
            rotateError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            return@GuidedStepCard
        }
        if (encoded == null || qrBitmap == null) {
            Text(
                stringResource(Res.string.firebase_join_qr_need_config),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = qrBitmap,
                contentDescription = stringResource(Res.string.firebase_join_qr_title),
                modifier = Modifier.size(220.dp).fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(encoded))
                    copiedHint = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.firebase_join_copy_code))
            }
            if (copiedHint) {
                Text(
                    stringResource(Res.string.firebase_join_code_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(Res.string.firebase_join_invite_admin_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Hidden by default: anyone reading this code over the admin's shoulder can join.
            TextButton(onClick = { revealInvite = !revealInvite }) {
                Text(
                    stringResource(
                        if (revealInvite) Res.string.firebase_hide_secrets
                        else Res.string.firebase_reveal_secrets,
                    ),
                )
            }
            if (revealInvite) {
                Text(
                    bootstrapCode,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(bootstrapCode))
                    copiedInvite = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.firebase_join_invite_copy))
            }
            if (copiedInvite) {
                Text(
                    stringResource(Res.string.firebase_join_invite_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (onRotateBootstrapCode != null) {
                TextButton(
                    onClick = {
                        rotating = true
                        rotateError = null
                        scope.launch {
                            runCatching { onRotateBootstrapCode() }
                                .onSuccess { code ->
                                    onBootstrapCodeChange(code)
                                    rotating = false
                                }
                                .onFailure { e ->
                                    rotateError = e.message ?: "Failed to rotate invitation code"
                                    rotating = false
                                }
                        }
                    },
                    enabled = !rotating,
                ) {
                    Text(stringResource(Res.string.firebase_join_invite_rotate))
                }
            }
            rotateError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The join configuration code (what the QR holds) for these project values, or null when the
 * project, the Google Sign-In credentials or the invitation code are incomplete.
 */
internal fun firebaseJoinConfigCode(
    orgId: String,
    projectId: String,
    applicationId: String,
    apiKey: String,
    webClientId: String,
    webClientSecret: String,
    bootstrapCode: String,
): String? {
    val payload = FirebaseJoinPayload(
        orgId = orgId.trim(),
        projectId = projectId.trim(),
        applicationId = applicationId.trim(),
        apiKey = apiKey.trim(),
        webClientId = webClientId.trim(),
        webClientSecret = webClientSecret.trim(),
        bootstrapCode = bootstrapCode.trim(),
    ).takeIf {
        it.hasJoinSecrets() && firebaseOAuthCredentialsReady(webClientId.trim(), webClientSecret.trim())
    } ?: return null
    return runCatching { FirebaseJoinCodec.encode(payload) }.getOrNull()
}

/**
 * Creates or replaces the org invitation code (older QR codes stop working), publishes its hash
 * and stores the new code on this device. Org admins only; Firestore refuses anyone else.
 */
internal suspend fun rotateFirebaseInvitationCode(
    platformContext: PlatformContext,
    settingsManager: SettingsManager,
): String {
    val gateway = createFirestoreGateway(platformContext, settingsManager)
    val code = MemberRoleAdmin.rotateBootstrapCode(
        gateway = gateway,
        orgId = settingsManager.resolveWritableFirebaseOrgId(),
        allowedEmailDomains = settingsManager.getAllowedEmailDomains(),
    )
    settingsManager.setFirebaseBootstrapCode(code)
    return code
}

/** Wizard helper: project config + join QR in one block. */
@Composable
fun FirebaseAdminConfigAndJoinQrSection(
    settingsManager: SettingsManager,
    orgId: String,
    projectId: String,
    applicationId: String,
    apiKey: String,
    webClientId: String,
    webClientSecret: String,
    onOrgIdChange: (String) -> Unit = {},
    onProjectIdChange: (String) -> Unit,
    onApplicationIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onWebClientIdChange: (String) -> Unit,
    onWebClientSecretChange: (String) -> Unit,
    onAppliedFromPaste: () -> Unit = {},
) {
    var bootstrapCode by remember { mutableStateOf(settingsManager.getFirebaseBootstrapCode()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FirebaseAdminProjectConfigCard(
            settingsManager = settingsManager,
            projectId = projectId,
            applicationId = applicationId,
            apiKey = apiKey,
            webClientId = webClientId,
            webClientSecret = webClientSecret,
            onProjectIdChange = onProjectIdChange,
            onApplicationIdChange = onApplicationIdChange,
            onApiKeyChange = onApiKeyChange,
            onWebClientIdChange = onWebClientIdChange,
            onWebClientSecretChange = onWebClientSecretChange,
            onAppliedFromPaste = onAppliedFromPaste,
        )
        FirebaseAdminJoinQrCard(
            orgId = orgId,
            projectId = projectId,
            applicationId = applicationId,
            apiKey = apiKey,
            webClientId = webClientId,
            webClientSecret = webClientSecret,
            bootstrapCode = bootstrapCode,
            onBootstrapCodeChange = { code ->
                bootstrapCode = code
                settingsManager.setFirebaseBootstrapCode(code)
            },
        )
    }
}

/**
 * Join / follow: scan or paste join code; never shows apiKey fields.
 * Full v1 codes already include OAuth secret + invitation — one paste/scan is enough.
 *
 * Once the configuration has been imported (QR scan or pasted join code) nothing is echoed back:
 * a member has no use for the org invitation code, and it is the one secret that lets any device
 * join. Values the user typed themselves stay visible — they already know them.
 */
@Composable
fun FirebaseJoinImportSection(
    settingsManager: SettingsManager,
    onJoined: (orgId: String) -> Unit,
    onRequestScan: (() -> Unit)? = null,
    joinError: String? = null,
    onJoinInputChanged: () -> Unit = {},
    configImported: Boolean = settingsManager.isFirebaseJoinImported(),
) {
    var pasteCode by remember { mutableStateOf("") }
    // Deliberately not seeded from storage: an imported invitation code never reaches the UI.
    var manualInviteCode by remember { mutableStateOf("") }
    // Keyed so a scan handled by the host screen also collapses the fields here.
    var imported by remember(configImported) { mutableStateOf(configImported) }
    var showManualEntry by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val error = joinError ?: localError
    val incompleteOAuthMsg = stringResource(Res.string.firebase_join_error_oauth_missing)
    val incompleteInviteMsg = stringResource(Res.string.firebase_join_error_invite_missing)

    // An imported-but-incomplete config must not collapse into the "received" banner: the fields
    // that fix it are in the manual block, and the invitation code is typed there.
    LaunchedEffect(error) {
        if (error != null) showManualEntry = true
    }

    fun applyRaw(raw: String) {
        when (val result = FirebaseJoinImport.apply(settingsManager, raw, manualInviteCode)) {
            is FirebaseJoinImportResult.Undecodable ->
                localError = result.message ?: "Invalid join code"

            is FirebaseJoinImportResult.Incomplete ->
                localError = when (result.problem) {
                    FirebaseJoinImportProblem.OAUTH_SECRET_MISSING -> incompleteOAuthMsg
                    FirebaseJoinImportProblem.INVITATION_MISSING -> incompleteInviteMsg
                }

            is FirebaseJoinImportResult.Complete -> {
                localError = null
                onJoinInputChanged()
                pasteCode = ""
                manualInviteCode = ""
                imported = true
                showManualEntry = false
                onJoined(result.orgId)
            }
        }
    }

    GuidedStepCard(
        title = stringResource(Res.string.firebase_join_scan_title),
        body = stringResource(Res.string.firebase_join_scan_body),
    ) {
        if (onRequestScan != null) {
            Button(onClick = onRequestScan, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.firebase_join_scan))
            }
        }
        if (imported && !showManualEntry) {
            FirebaseConfigReceivedBanner(orgId = settingsManager.getFirebaseOrgId())
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // Escape hatch when the imported config turned out to be incomplete.
            TextButton(onClick = { showManualEntry = true }) {
                Text(stringResource(Res.string.firebase_join_or_enter_codes))
            }
            return@GuidedStepCard
        }
        Text(
            stringResource(Res.string.firebase_join_or_enter_codes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = pasteCode,
            onValueChange = {
                pasteCode = it
                localError = null
                onJoinInputChanged()
            },
            label = { Text(stringResource(Res.string.firebase_join_config_code_label)) },
            supportingText = {
                Text(stringResource(Res.string.firebase_join_config_code_hint))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(
            value = manualInviteCode,
            onValueChange = {
                manualInviteCode = it
                settingsManager.setFirebaseBootstrapCode(it)
                localError = null
                // The host gates its own "continue" on the stored invitation code, which is not
                // observable state — it has to be told the code just changed.
                onJoinInputChanged()
            },
            label = { Text(stringResource(Res.string.firebase_join_invite_code_label)) },
            supportingText = {
                Text(stringResource(Res.string.firebase_join_invite_code_hint))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(
            onClick = { applyRaw(pasteCode) },
            modifier = Modifier.fillMaxWidth(),
            enabled = pasteCode.isNotBlank(),
        ) {
            Text(stringResource(Res.string.firebase_join_paste_code))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun FirebaseConfigReceivedBanner(orgId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.firebase_join_received),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (orgId.isNotBlank()) {
            Text(
                orgId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
