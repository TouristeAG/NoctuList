package com.eventmanager.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.openExternalUrl
import com.eventmanager.app.resources.*
import com.eventmanager.app.setup.CliErrorKind
import com.eventmanager.app.setup.LoginChallenge
import com.eventmanager.app.setup.SetupAutomation
import com.eventmanager.app.setup.SetupStepId
import com.eventmanager.app.setup.StepInput
import com.eventmanager.app.setup.StepState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val requiredSteps = SetupStepId.entries.filterNot { it.optional }

/** Steps worth running again later: rules and site change with NoctuList updates. */
private val RERUNNABLE = setOf(SetupStepId.PROJECT, SetupStepId.RULES, SetupStepId.SITE, SetupStepId.PHOTOS, SetupStepId.SERVER)
internal val optionalSteps = SetupStepId.entries.filter { it.optional }

internal fun stepTitle(step: SetupStepId): StringResource = when (step) {
    SetupStepId.TOOLS -> Res.string.setup_step_tools_title
    SetupStepId.LOGIN -> Res.string.setup_step_login_title
    SetupStepId.PROJECT -> Res.string.setup_step_project_title
    SetupStepId.DATABASE -> Res.string.setup_step_database_title
    SetupStepId.RULES -> Res.string.setup_step_rules_title
    SetupStepId.PROVIDERS -> Res.string.setup_step_providers_title
    SetupStepId.CONNECT -> Res.string.setup_step_connect_title
    SetupStepId.SITE -> Res.string.setup_step_site_title
    SetupStepId.PHOTOS -> Res.string.setup_step_photos_title
    SetupStepId.SERVER -> Res.string.setup_step_server_title
}

private fun stepPurpose(step: SetupStepId): StringResource = when (step) {
    SetupStepId.TOOLS -> Res.string.setup_step_tools_purpose
    SetupStepId.LOGIN -> Res.string.setup_step_login_purpose
    SetupStepId.PROJECT -> Res.string.setup_step_project_purpose
    SetupStepId.DATABASE -> Res.string.setup_step_database_purpose
    SetupStepId.RULES -> Res.string.setup_step_rules_purpose
    SetupStepId.PROVIDERS -> Res.string.setup_step_providers_purpose
    SetupStepId.CONNECT -> Res.string.setup_step_connect_purpose
    SetupStepId.SITE -> Res.string.setup_step_site_purpose
    SetupStepId.PHOTOS -> Res.string.setup_step_photos_purpose
    SetupStepId.SERVER -> Res.string.setup_step_server_purpose
}

@Composable
internal fun errorMessage(kind: CliErrorKind): String = stringResource(
    when (kind) {
        CliErrorKind.NOT_LOGGED_IN -> Res.string.setup_err_not_logged_in
        CliErrorKind.BILLING_REQUIRED -> Res.string.setup_err_billing
        CliErrorKind.API_NOT_ENABLED -> Res.string.setup_err_api
        CliErrorKind.EVENTARC_PROPAGATION -> Res.string.setup_err_eventarc
        CliErrorKind.PERMISSION_DENIED -> Res.string.setup_err_permission
        CliErrorKind.NETWORK -> Res.string.setup_err_network
        CliErrorKind.QUOTA -> Res.string.setup_err_quota
        CliErrorKind.UNKNOWN -> Res.string.setup_err_unknown
    },
)

/** Console links for the steps only a person can do. */
internal object SetupLinks {
    fun providers(id: String) = "https://console.firebase.google.com/project/$id/authentication/providers"
    fun credentials(id: String) = "https://console.cloud.google.com/apis/credentials?project=$id"
    fun blaze(id: String) = "https://console.firebase.google.com/project/$id/usage/details"
    fun budget(id: String) = "https://console.cloud.google.com/billing/budgets?project=$id"
    fun storage(id: String) = "https://console.firebase.google.com/project/$id/storage"
    const val APP_PASSWORDS = "https://myaccount.google.com/apppasswords"
}

@Composable
fun AutomaticTab(
    automation: SetupAutomation,
    projectId: String,
    listState: LazyListState,
    onShowManual: (SetupStepId) -> Unit,
) {
    val states by automation.states.collectAsState()
    val progress by automation.progress.collectAsState()
    val info by automation.info.collectAsState()
    LaunchedEffect(automation) { automation.refresh() }
    val pid = progress.projectId.ifBlank { projectId }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(requiredSteps, key = { it.name }) { step ->
            Card(step, states[step] ?: StepState.Todo, onShowManual) {
                StepControls(step, automation, pid, info.projects.map { it.id }, info.locations, info.account, info.region, info.siteUrl, info.toolsProgress, progress.confirmed, info.loginChallenge)
            }
        }
        item(key = "extras") {
            Text(
                stringResource(Res.string.setup_optional_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        items(optionalSteps, key = { it.name }) { step ->
            val enabled = step in progress.extras || states[step] == StepState.Done
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(stepTitle(step)), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { automation.setExtra(step, it) })
                }
                if (enabled) {
                    Card(step, states[step] ?: StepState.Todo, onShowManual) {
                        StepControls(step, automation, pid, info.projects.map { it.id }, info.locations, info.account, info.region, info.siteUrl, info.toolsProgress, progress.confirmed, info.loginChallenge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(step: SetupStepId, state: StepState, onShowManual: (SetupStepId) -> Unit, content: @Composable () -> Unit) {
    StepCard(
        step = step,
        state = state,
        title = stringResource(stepTitle(step)),
        purpose = stringResource(stepPurpose(step)),
        onShowManual = onShowManual,
        rerunnable = step in RERUNNABLE,
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StepControls(
    step: SetupStepId,
    automation: SetupAutomation,
    projectId: String,
    projects: List<String>,
    locations: List<String>,
    account: String,
    region: String,
    siteUrl: String,
    toolsProgress: Float?,
    confirmed: Set<SetupStepId>,
    loginChallenge: LoginChallenge?,
) {
    val platform = LocalPlatformContext.current
    val open: (String) -> Unit = { openExternalUrl(platform, it) }
    val busy = automation.states.collectAsState().value[step].let { it is StepState.Running || it is StepState.WaitingRetry }
    val runLabel = stringResource(Res.string.setup_btn_run)

    @Composable
    fun RunButton(enabled: Boolean = true, input: () -> StepInput = { StepInput() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { automation.run(step, input()) }, enabled = enabled && !busy) { Text("▶  $runLabel") }
            if (busy) TextButton(onClick = { automation.cancel() }) { Text(stringResource(Res.string.setup_btn_cancel)) }
        }
    }

    @Composable
    fun OpenButton(label: StringResource, url: String) {
        OutlinedButton(onClick = { open(url) }) { Text("↗  ${stringResource(label)}") }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (step) {
            SetupStepId.TOOLS -> {
                toolsProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                RunButton()
            }
            SetupStepId.LOGIN -> {
                if (account.isNotBlank()) Text(stringResource(Res.string.setup_signed_in_as, account))
                val challenge = loginChallenge
                if (challenge == null) {
                    RunButton()
                } else {
                    var code by remember(challenge) { mutableStateOf("") }
                    LaunchedEffect(challenge) { open(challenge.url) }
                    Text(stringResource(Res.string.setup_login_session, challenge.sessionId), fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { open(challenge.url) }) { Text("↗  ${stringResource(Res.string.setup_login_open)}") }
                    OutlinedTextField(code, { code = it.trim() }, label = { Text(stringResource(Res.string.setup_login_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { automation.run(step, StepInput(authCode = code)) }, enabled = code.isNotBlank() && !busy) {
                            Text(stringResource(Res.string.setup_login_finish))
                        }
                    }
                }
            }
            SetupStepId.PROJECT -> {
                var picked by remember(projects) { mutableStateOf(projectId.takeIf { it in projects } ?: projects.firstOrNull().orEmpty()) }
                var newId by remember { mutableStateOf("") }
                var newName by remember { mutableStateOf("") }
                if (projects.isNotEmpty()) Picker(stringResource(Res.string.setup_project_pick), projects, picked) { picked = it }
                OutlinedTextField(newId, { newId = it.lowercase().trim() }, label = { Text(stringResource(Res.string.setup_project_new_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (newId.isNotBlank()) {
                    OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(Res.string.setup_project_new_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                RunButton(enabled = newId.isNotBlank() || picked.isNotBlank()) {
                    if (newId.isNotBlank()) StepInput(projectId = newId, newProjectName = newName.ifBlank { newId })
                    else StepInput(projectId = picked)
                }
            }
            SetupStepId.DATABASE -> {
                var location by remember(locations) { mutableStateOf(locations.firstOrNull().orEmpty()) }
                if (locations.isNotEmpty()) Picker(stringResource(Res.string.setup_location), locations, location) { location = it }
                RunButton { StepInput(location = location.ifBlank { null }) }
            }
            SetupStepId.RULES -> RunButton()
            SetupStepId.PROVIDERS -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenButton(Res.string.setup_btn_open, SetupLinks.providers(projectId))
                    Button(onClick = { automation.confirm(step) }) { Text(stringResource(Res.string.setup_btn_done)) }
                }
            }
            SetupStepId.CONNECT -> {
                var clientId by remember { mutableStateOf("") }
                var clientSecret by remember { mutableStateOf("") }
                OpenButton(Res.string.setup_btn_open, SetupLinks.credentials(projectId))
                RedirectUris()
                OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text(stringResource(Res.string.setup_client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    clientSecret, { clientSecret = it.trim() },
                    label = { Text(stringResource(Res.string.setup_client_secret)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                RunButton { StepInput(webClientId = clientId, webClientSecret = clientSecret) }
                Text(stringResource(Res.string.setup_connect_signin), style = MaterialTheme.typography.bodySmall)
            }
            SetupStepId.SITE -> {
                if (siteUrl.isNotBlank()) Text(stringResource(Res.string.setup_site_url, siteUrl))
                RunButton()
            }
            SetupStepId.PHOTOS -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenButton(Res.string.setup_open_blaze, SetupLinks.blaze(projectId))
                    OpenButton(Res.string.setup_btn_open, SetupLinks.storage(projectId))
                    if (step !in confirmed) Button(onClick = { automation.confirm(step) }) { Text(stringResource(Res.string.setup_btn_done)) }
                }
                RunButton(enabled = step in confirmed)
            }
            SetupStepId.SERVER -> {
                var gmail by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenButton(Res.string.setup_open_blaze, SetupLinks.blaze(projectId))
                    OpenButton(Res.string.setup_open_budget, SetupLinks.budget(projectId))
                    OpenButton(Res.string.setup_open_apppassword, SetupLinks.APP_PASSWORDS)
                }
                OutlinedTextField(
                    gmail, { gmail = it.trim() },
                    label = { Text(stringResource(Res.string.setup_gmail)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    password, { password = it },
                    label = { Text(stringResource(Res.string.setup_app_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (region.isNotBlank()) Text(stringResource(Res.string.setup_region, region), style = MaterialTheme.typography.bodySmall)
                RunButton(enabled = gmail.isNotBlank() && password.isNotBlank()) {
                    val input = StepInput(gmailUser = gmail, appPassword = password.filterNot { it == ' ' }.toCharArray())
                    password = ""
                    input
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}
