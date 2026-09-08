package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.firebase_advanced_project
import com.eventmanager.app.resources.firebase_api_key_label
import com.eventmanager.app.resources.firebase_application_id_label
import com.eventmanager.app.resources.firebase_checklist_auth
import com.eventmanager.app.resources.firebase_checklist_org
import com.eventmanager.app.resources.firebase_checklist_project
import com.eventmanager.app.resources.firebase_checklist_title
import com.eventmanager.app.resources.firebase_hide_project
import com.eventmanager.app.resources.firebase_join_scan_body
import com.eventmanager.app.resources.firebase_org_id_hint
import com.eventmanager.app.resources.firebase_org_id_label
import com.eventmanager.app.resources.firebase_project_configured_hidden
import com.eventmanager.app.resources.firebase_project_id_label
import com.eventmanager.app.resources.firebase_sign_in
import com.eventmanager.app.resources.firebase_sign_out
import com.eventmanager.app.resources.firebase_status_not_signed_in
import com.eventmanager.app.resources.firebase_status_signed_in_as
import com.eventmanager.app.resources.firebase_step_org_body
import com.eventmanager.app.resources.firebase_step_org_title
import com.eventmanager.app.resources.firebase_step_project_body
import com.eventmanager.app.resources.firebase_step_project_title
import com.eventmanager.app.resources.firebase_step_project_where
import com.eventmanager.app.resources.firebase_step_signin_body
import com.eventmanager.app.resources.firebase_step_signin_title
import com.eventmanager.app.resources.firebase_web_client_id_hint
import com.eventmanager.app.resources.firebase_web_client_id_label
import com.eventmanager.app.resources.firebase_web_client_secret_hint
import com.eventmanager.app.resources.firebase_web_client_secret_label
import org.jetbrains.compose.resources.stringResource

/**
 * Shared Firebase connection fields for Setup Wizard, follow-migration, and migrate wizard.
 *
 * @param hideProjectSecrets when true (join/follow after QR or announcement), never show
 *   apiKey / applicationId / projectId / web client credentials — only org + Sign-In.
 */
@Composable
fun FirebaseConnectionFields(
    configuredOrgs: List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>,
    onConfiguredOrgsChange: (List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>) -> Unit,
    onOrgIdCommitted: (String) -> Unit = {},
    projectId: String,
    onProjectIdChange: (String) -> Unit,
    applicationId: String,
    onApplicationIdChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    webClientId: String,
    onWebClientIdChange: (String) -> Unit,
    webClientSecret: String = "",
    onWebClientSecretChange: (String) -> Unit = {},
    authEmail: String?,
    onSignIn: () -> Unit,
    onSignOut: (() -> Unit)? = null,
    showSignIn: Boolean = true,
    compact: Boolean = false,
    showChecklist: Boolean = true,
    signInFeedback: String? = null,
    hideProjectSecrets: Boolean = false,
    orgIdReadOnly: Boolean = false,
    /** When false (Sheets → Firebase migration), only one org ID can be entered. */
    allowMultipleOrgs: Boolean = true,
) {
    val projectReady = projectId.isNotBlank() && applicationId.isNotBlank() && apiKey.isNotBlank()
    val orgReady = firebaseConfiguredOrgsReady(configuredOrgs)
    val authReady = !authEmail.isNullOrBlank()
    var showProjectDetails by remember {
        mutableStateOf(!compact || !projectReady)
    }
    LaunchedEffect(projectReady, hideProjectSecrets) {
        if (!projectReady && !hideProjectSecrets) showProjectDetails = true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
    ) {
        FirebaseConfiguredOrgsSection(
            configuredOrgs = configuredOrgs,
            onConfiguredOrgsChange = { if (!orgIdReadOnly) onConfiguredOrgsChange(it) },
            readOnly = orgIdReadOnly,
            allowMultipleOrgs = allowMultipleOrgs,
            onOrgIdCommitted = onOrgIdCommitted,
        )

        if (hideProjectSecrets) {
            GuidedStepCard(title = stringResource(Res.string.firebase_step_project_title)) {
                Text(
                    if (projectReady) {
                        stringResource(Res.string.firebase_project_configured_hidden)
                    } else {
                        stringResource(Res.string.firebase_join_scan_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (projectReady) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        } else {
            GuidedStepCard(
                title = stringResource(Res.string.firebase_step_project_title),
                body = stringResource(Res.string.firebase_step_project_body),
            ) {
                TextButton(onClick = { showProjectDetails = !showProjectDetails }) {
                    Text(
                        if (showProjectDetails) {
                            stringResource(Res.string.firebase_hide_project)
                        } else {
                            stringResource(Res.string.firebase_advanced_project)
                        },
                    )
                }
                if (showProjectDetails) {
                    Text(
                        stringResource(Res.string.firebase_step_project_where),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = projectId,
                        onValueChange = onProjectIdChange,
                        label = { Text(stringResource(Res.string.firebase_project_id_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = applicationId,
                        onValueChange = onApplicationIdChange,
                        label = { Text(stringResource(Res.string.firebase_application_id_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text(stringResource(Res.string.firebase_api_key_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = webClientId,
                        onValueChange = onWebClientIdChange,
                        label = { Text(stringResource(Res.string.firebase_web_client_id_label)) },
                        supportingText = { Text(stringResource(Res.string.firebase_web_client_id_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = webClientSecret,
                        onValueChange = onWebClientSecretChange,
                        label = { Text(stringResource(Res.string.firebase_web_client_secret_label)) },
                        supportingText = { Text(stringResource(Res.string.firebase_web_client_secret_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }

        if (showSignIn) {
            FirebaseSignInStep(
                authEmail = authEmail,
                onSignIn = onSignIn,
                onSignOut = onSignOut,
                signInFeedback = signInFeedback,
            )
        }

        if (showChecklist) {
            GuidedStepCard(title = stringResource(Res.string.firebase_checklist_title)) {
                GuidedChecklistItem(
                    label = stringResource(Res.string.firebase_checklist_org),
                    done = orgReady,
                )
                GuidedChecklistItem(
                    label = stringResource(Res.string.firebase_checklist_project),
                    done = projectReady,
                )
                if (showSignIn) {
                    GuidedChecklistItem(
                        label = stringResource(Res.string.firebase_checklist_auth),
                        done = authReady,
                    )
                }
            }
        }
    }
}

/** Step 3 — Google Sign-In (shared across wizard, migration, and admin settings). */
@Composable
fun FirebaseSignInStep(
    authEmail: String?,
    onSignIn: () -> Unit,
    onSignOut: (() -> Unit)? = null,
    signInFeedback: String? = null,
) {
    val authReady = !authEmail.isNullOrBlank()
    GuidedStepCard(
        title = stringResource(Res.string.firebase_step_signin_title),
        body = stringResource(Res.string.firebase_step_signin_body),
    ) {
        Text(
            authEmail?.let { stringResource(Res.string.firebase_status_signed_in_as, it) }
                ?: stringResource(Res.string.firebase_status_not_signed_in),
            style = MaterialTheme.typography.bodyMedium,
            color = if (authReady) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (!authReady) {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.firebase_sign_in))
            }
        } else if (onSignOut != null) {
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.firebase_sign_out))
            }
        }
        if (!signInFeedback.isNullOrBlank()) {
            Text(
                signInFeedback,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

fun SettingsManager.hasFirebaseProjectOptions(): Boolean =
    getFirebaseProjectId().isNotBlank() &&
        getFirebaseApiKey().isNotBlank() &&
        getFirebaseApplicationId().isNotBlank()

fun SettingsManager.persistFirebaseConnectionFields(
    configuredOrgs: List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>,
    projectId: String,
    applicationId: String,
    apiKey: String,
    webClientId: String,
    webClientSecret: String = "",
) {
    val previousProject = getFirebaseProjectId().trim()
    val nextProject = projectId.trim()
    val switchingProject = previousProject.isNotBlank() && previousProject != nextProject
    setFirebaseConfiguredOrgs(configuredOrgs)
    setFirebaseProjectId(nextProject)
    setFirebaseApplicationId(applicationId.trim())
    setFirebaseApiKey(apiKey.trim())
    setFirebaseWebClientId(webClientId.trim())
    when {
        webClientSecret.isNotBlank() -> setFirebaseWebClientSecret(webClientSecret.trim())
        switchingProject -> setFirebaseWebClientSecret("")
    }
}

fun firebaseConnectionReady(
    configuredOrgs: List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>,
    projectId: String,
    applicationId: String,
    apiKey: String,
    authEmail: String?,
    requireAuth: Boolean = true,
): Boolean {
    if (!firebaseConfiguredOrgsReady(configuredOrgs)) return false
    if (projectId.isBlank() || applicationId.isBlank() || apiKey.isBlank()) return false
    if (requireAuth && authEmail.isNullOrBlank()) return false
    return true
}

fun firebaseConnectionReady(
    orgId: String,
    projectId: String,
    applicationId: String,
    apiKey: String,
    authEmail: String?,
    requireAuth: Boolean = true,
): Boolean {
    if (orgId.isBlank()) return false
    if (projectId.isBlank() || applicationId.isBlank() || apiKey.isBlank()) return false
    if (requireAuth && authEmail.isNullOrBlank()) return false
    return true
}
