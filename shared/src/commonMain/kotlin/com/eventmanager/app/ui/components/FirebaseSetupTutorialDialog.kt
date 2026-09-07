package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.data.remote.FirestoreRulesClipboardContent
import com.eventmanager.app.data.remote.InstitutionGoogleWebOAuth
import com.eventmanager.app.data.remote.NOCTULIST_FIRESTORE_RULES_VERSION
import com.eventmanager.app.data.remote.StorageRulesClipboardContent
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.firebase_tutorial_close
import com.eventmanager.app.resources.firebase_tutorial_copy_redirect_uris
import com.eventmanager.app.resources.firebase_tutorial_copy_redirect_uris_done
import com.eventmanager.app.resources.firebase_tutorial_copy_rules
import com.eventmanager.app.resources.firebase_tutorial_copy_rules_done
import com.eventmanager.app.resources.firebase_tutorial_copy_rules_error
import com.eventmanager.app.resources.firebase_tutorial_copy_storage_rules
import com.eventmanager.app.resources.firebase_tutorial_copy_storage_rules_done
import com.eventmanager.app.resources.firebase_tutorial_copy_storage_rules_error
import com.eventmanager.app.resources.firebase_tutorial_intro
import com.eventmanager.app.resources.firebase_tutorial_method_cloud
import com.eventmanager.app.resources.firebase_tutorial_method_cloud_hint
import com.eventmanager.app.resources.firebase_tutorial_method_firebase
import com.eventmanager.app.resources.firebase_tutorial_method_firebase_hint
import com.eventmanager.app.resources.firebase_tutorial_method_label
import com.eventmanager.app.resources.firebase_tutorial_oauth_note
import com.eventmanager.app.resources.firebase_tutorial_storage_optional_body
import com.eventmanager.app.resources.firebase_tutorial_storage_optional_title
import com.eventmanager.app.resources.firebase_tutorial_forms_optional_body
import com.eventmanager.app.resources.firebase_tutorial_forms_optional_title
import com.eventmanager.app.resources.firebase_tutorial_cloud_step1_body
import com.eventmanager.app.resources.firebase_tutorial_cloud_step1_title
import com.eventmanager.app.resources.firebase_tutorial_cloud_step2_body
import com.eventmanager.app.resources.firebase_tutorial_cloud_step2_title
import com.eventmanager.app.resources.firebase_tutorial_cloud_step3_body
import com.eventmanager.app.resources.firebase_tutorial_cloud_step3_title
import com.eventmanager.app.resources.firebase_tutorial_cloud_step4_body
import com.eventmanager.app.resources.firebase_tutorial_cloud_step4_title
import com.eventmanager.app.resources.firebase_tutorial_cloud_step5_body
import com.eventmanager.app.resources.firebase_tutorial_cloud_step5_title
import com.eventmanager.app.resources.firebase_tutorial_fb_step1_body
import com.eventmanager.app.resources.firebase_tutorial_fb_step1_title
import com.eventmanager.app.resources.firebase_tutorial_fb_step2_body
import com.eventmanager.app.resources.firebase_tutorial_fb_step2_title
import com.eventmanager.app.resources.firebase_tutorial_fb_step3_body
import com.eventmanager.app.resources.firebase_tutorial_fb_step3_title
import com.eventmanager.app.resources.firebase_tutorial_fb_step4_body
import com.eventmanager.app.resources.firebase_tutorial_fb_step4_title
import com.eventmanager.app.resources.firebase_tutorial_fb_step5_body
import com.eventmanager.app.resources.firebase_tutorial_fb_step5_title
import com.eventmanager.app.resources.firebase_tutorial_step7_body
import com.eventmanager.app.resources.firebase_tutorial_step7_title
import com.eventmanager.app.resources.firebase_tutorial_step8_body
import com.eventmanager.app.resources.firebase_tutorial_step8_title
import com.eventmanager.app.resources.firebase_tutorial_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private enum class TutorialConsoleMethod { Firebase, Cloud }

/**
 * First-time Firebase setup tutorial for admins.
 * Offers two complete walkthroughs: Firebase Console (simpler) or Google Cloud Console.
 */
@Composable
fun FirebaseSetupTutorialDialog(
    onDismiss: () -> Unit,
    projectId: String = "",
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var rulesText by remember { mutableStateOf<String?>(null) }
    var storageRulesText by remember { mutableStateOf<String?>(null) }
    var firestoreCopyStatus by remember { mutableStateOf<CopyStatus>(CopyStatus.Idle) }
    var storageCopyStatus by remember { mutableStateOf<CopyStatus>(CopyStatus.Idle) }
    var redirectUrisCopyStatus by remember { mutableStateOf<CopyStatus>(CopyStatus.Idle) }
    var method by remember { mutableStateOf(TutorialConsoleMethod.Firebase) }

    LaunchedEffect(Unit) {
        rulesText = runCatching { FirestoreRulesClipboardContent.load() }.getOrNull()
        storageRulesText = runCatching { StorageRulesClipboardContent.load() }.getOrNull()
    }

    val steps = when (method) {
        TutorialConsoleMethod.Firebase -> listOf(
            stringResource(Res.string.firebase_tutorial_fb_step1_title) to
                stringResource(Res.string.firebase_tutorial_fb_step1_body),
            stringResource(Res.string.firebase_tutorial_fb_step2_title) to
                stringResource(Res.string.firebase_tutorial_fb_step2_body),
            stringResource(Res.string.firebase_tutorial_fb_step3_title) to
                stringResource(Res.string.firebase_tutorial_fb_step3_body),
            stringResource(Res.string.firebase_tutorial_fb_step4_title) to
                stringResource(Res.string.firebase_tutorial_fb_step4_body),
            stringResource(Res.string.firebase_tutorial_fb_step5_title) to
                stringResource(Res.string.firebase_tutorial_fb_step5_body),
            stringResource(Res.string.firebase_tutorial_step7_title) to
                stringResource(Res.string.firebase_tutorial_step7_body),
            stringResource(Res.string.firebase_tutorial_step8_title) to
                stringResource(Res.string.firebase_tutorial_step8_body),
        )
        TutorialConsoleMethod.Cloud -> listOf(
            stringResource(Res.string.firebase_tutorial_cloud_step1_title) to
                stringResource(Res.string.firebase_tutorial_cloud_step1_body),
            stringResource(Res.string.firebase_tutorial_cloud_step2_title) to
                stringResource(Res.string.firebase_tutorial_cloud_step2_body),
            stringResource(Res.string.firebase_tutorial_cloud_step3_title) to
                stringResource(Res.string.firebase_tutorial_cloud_step3_body),
            stringResource(Res.string.firebase_tutorial_cloud_step4_title) to
                stringResource(Res.string.firebase_tutorial_cloud_step4_body),
            stringResource(Res.string.firebase_tutorial_cloud_step5_title) to
                stringResource(Res.string.firebase_tutorial_cloud_step5_body),
            stringResource(Res.string.firebase_tutorial_step7_title) to
                stringResource(Res.string.firebase_tutorial_step7_body),
            stringResource(Res.string.firebase_tutorial_step8_title) to
                stringResource(Res.string.firebase_tutorial_step8_body),
        )
    }

    // Rules copy button sits after step 3 (index 2) in both guides.
    val rulesStepIndex = 2
    // Localhost redirect URIs: Firebase guide step 5 (index 4), Cloud guide step 4 (index 3).
    val redirectUrisStepIndex = when (method) {
        TutorialConsoleMethod.Firebase -> 4
        TutorialConsoleMethod.Cloud -> 3
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(Res.string.firebase_tutorial_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(Res.string.firebase_tutorial_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(Res.string.firebase_tutorial_oauth_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Text(
                    stringResource(Res.string.firebase_tutorial_method_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = method == TutorialConsoleMethod.Firebase,
                        onClick = { method = TutorialConsoleMethod.Firebase },
                        label = { Text(stringResource(Res.string.firebase_tutorial_method_firebase)) },
                    )
                    FilterChip(
                        selected = method == TutorialConsoleMethod.Cloud,
                        onClick = { method = TutorialConsoleMethod.Cloud },
                        label = { Text(stringResource(Res.string.firebase_tutorial_method_cloud)) },
                    )
                }
                Text(
                    stringResource(
                        if (method == TutorialConsoleMethod.Firebase) {
                            Res.string.firebase_tutorial_method_firebase_hint
                        } else {
                            Res.string.firebase_tutorial_method_cloud_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                steps.forEachIndexed { index, (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (index == rulesStepIndex) {
                            // Stale published rules are the usual cause of PERMISSION_DENIED, so
                            // name the revision this build expects.
                            Text(
                                "firestore.rules v$NOCTULIST_FIRESTORE_RULES_VERSION",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val text = rulesText
                                            ?: runCatching { FirestoreRulesClipboardContent.load() }.getOrNull()
                                        if (text.isNullOrBlank() || !text.startsWith("rules_version")) {
                                            firestoreCopyStatus = CopyStatus.Error
                                            return@launch
                                        }
                                        clipboard.setText(AnnotatedString(text))
                                        rulesText = text
                                        firestoreCopyStatus = CopyStatus.Done
                                    }
                                },
                                enabled = firestoreCopyStatus != CopyStatus.Error || rulesText != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when (firestoreCopyStatus) {
                                        CopyStatus.Done ->
                                            stringResource(Res.string.firebase_tutorial_copy_rules_done)
                                        CopyStatus.Error ->
                                            stringResource(Res.string.firebase_tutorial_copy_rules_error)
                                        CopyStatus.Idle ->
                                            stringResource(Res.string.firebase_tutorial_copy_rules)
                                    },
                                )
                            }
                        }
                        if (index == redirectUrisStepIndex) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(
                                            InstitutionGoogleWebOAuth.loopbackRedirectUrisClipboardText(),
                                        ),
                                    )
                                    redirectUrisCopyStatus = CopyStatus.Done
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when (redirectUrisCopyStatus) {
                                        CopyStatus.Done ->
                                            stringResource(Res.string.firebase_tutorial_copy_redirect_uris_done)
                                        else ->
                                            stringResource(Res.string.firebase_tutorial_copy_redirect_uris)
                                    },
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.firebase_tutorial_storage_optional_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.firebase_tutorial_storage_optional_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val text = storageRulesText
                                    ?: runCatching { StorageRulesClipboardContent.load() }.getOrNull()
                                if (text.isNullOrBlank() || !text.startsWith("rules_version")) {
                                    storageCopyStatus = CopyStatus.Error
                                    return@launch
                                }
                                clipboard.setText(AnnotatedString(text))
                                storageRulesText = text
                                storageCopyStatus = CopyStatus.Done
                            }
                        },
                        enabled = storageCopyStatus != CopyStatus.Error || storageRulesText != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when (storageCopyStatus) {
                                CopyStatus.Done ->
                                    stringResource(Res.string.firebase_tutorial_copy_storage_rules_done)
                                CopyStatus.Error ->
                                    stringResource(Res.string.firebase_tutorial_copy_storage_rules_error)
                                CopyStatus.Idle ->
                                    stringResource(Res.string.firebase_tutorial_copy_storage_rules)
                            },
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.firebase_tutorial_forms_optional_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(Res.string.firebase_tutorial_forms_optional_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GuestFormHostingGuide(
                        projectId = projectId,
                        isDesktop = LocalPlatformContext.current.isDesktop,
                        canExport = true,
                        showExportButton = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.firebase_tutorial_close))
            }
        },
    )
}

private enum class CopyStatus { Idle, Done, Error }
