package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.certificate.CertAssembly
import com.eventmanager.app.portal.certificate.CertJob
import com.eventmanager.app.portal.certificate.CertJobType
import com.eventmanager.app.portal.certificate.CertPerson
import com.eventmanager.app.portal.certificate.CertSelection
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.CertificateAssembler
import com.eventmanager.app.portal.certificate.CertificateDates
import com.eventmanager.app.portal.certificate.CertificateDefaults
import com.eventmanager.app.portal.certificate.CertificateTemplate
import com.eventmanager.app.portal.certificate.certificateCategoryOf
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.DialogWindowFollowsScreenSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringResource

/** Native names: the certificate languages are those of the portal, not of the app. */
internal val CERTIFICATE_LANGUAGE_NAMES = mapOf(
    "fr" to "Français",
    "en" to "English",
    "de" to "Deutsch",
    "it" to "Italiano",
    "es" to "Español",
)

private const val PREVIEW_DELAY_MS = 250L
private val TWO_COLUMNS_MIN_WIDTH = 900.dp

/** Builds the preview for the example volunteer (null) or a real one, on their whole history and all types. */
internal fun certificatePreviewAssembly(
    template: CertificateTemplate,
    association: String,
    volunteer: Volunteer?,
    jobTypes: List<JobTypeConfig>,
    jobsOf: (Volunteer) -> List<Job>,
    todayMillis: Long = System.currentTimeMillis(),
): CertAssembly? {
    val (person, types, jobs) = if (volunteer == null) {
        CertificateSampleData.sample(template.language)
    } else {
        Triple(
            CertPerson(volunteer.name, volunteer.lastNameAbbreviation, volunteer.gender?.name.orEmpty()),
            jobTypes.map { CertJobType(it.name.trim(), certificateCategoryOf(it), it.description.trim()) },
            jobsOf(volunteer).map { CertJob(it.jobTypeName.trim(), CertificateDates.dayOf(it.date)) },
        )
    }
    val selection = CertSelection(
        fromDay = 0,
        toDay = Int.MAX_VALUE,
        todayDay = CertificateDates.dayOf(todayMillis),
        jobTypes = (types.map { it.name } + jobs.map { it.jobTypeName }).toSet(),
    )
    return CertificateAssembler.assemble(template, association, person, types, jobs, selection)
}

/** Leaves `var:` and `gender:` links alone if anything tries to open them. */
private val inertUriHandler = object : UriHandler {
    override fun openUri(uri: String) = Unit
}

@Composable
fun CertificateEditorDialog(
    initial: CertificateTemplate,
    association: String,
    logoBase64: String,
    volunteers: List<Volunteer>,
    jobTypes: List<JobTypeConfig>,
    jobsOf: (Volunteer) -> List<Job>,
    gmailAddress: String,
    onSave: (CertificateTemplate) -> Unit,
    onDismiss: () -> Unit,
    onOpenLogoSettings: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        DialogWindowFollowsScreenSize()
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
            ) {
                CertificateEditorContent(
                    initial, association, logoBase64, volunteers, jobTypes, jobsOf, gmailAddress,
                    onSave, onDismiss, onOpenLogoSettings,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CertificateEditorContent(
    initial: CertificateTemplate,
    association: String,
    logoBase64: String,
    volunteers: List<Volunteer>,
    jobTypes: List<JobTypeConfig>,
    jobsOf: (Volunteer) -> List<Job>,
    gmailAddress: String,
    onSave: (CertificateTemplate) -> Unit,
    onDismiss: () -> Unit,
    onOpenLogoSettings: (() -> Unit)?,
) {
    val model = remember(initial) { CertificateEditorModel(initial, association) }
    val linkColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(model, linkColor) { model.styleLinks(linkColor) }
    val baseline = remember(model) { model.toTemplate() }

    val people = remember(volunteers) {
        volunteers.filter { it.isActive }.sortedBy { "${it.name} ${it.lastNameAbbreviation}".trim().lowercase() }
    }
    var previewId by remember { mutableStateOf<String?>(null) }
    val previewPerson = people.firstOrNull { it.id == previewId }
    var assembly by remember { mutableStateOf<CertAssembly?>(null) }
    var signer by remember { mutableStateOf(CertSigner()) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(model, previewPerson, jobTypes) {
        var first = true
        snapshotFlow { model.changeKey() }.collectLatest {
            if (!first) delay(PREVIEW_DELAY_MS)
            first = false
            val template = model.toTemplate()
            assembly = certificatePreviewAssembly(template, association, previewPerson, jobTypes, jobsOf)
            signer = template.signer
            ready = true
        }
    }

    var languageChanged by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val tooLarge = stringResource(Res.string.certificate_too_large)

    val family = FontFamily(
        Font(Res.font.nunito_regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.nunito_regular_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.nunito_bold, FontWeight.Bold, FontStyle.Normal),
    )

    CompositionLocalProvider(LocalUriHandler provides inertUriHandler) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= TWO_COLUMNS_MIN_WIDTH
            val pad = if (wide) 24.dp else 12.dp
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(start = pad, end = pad, top = pad, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(Res.string.certificate_enabled),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val menuModifier = if (wide) Modifier.width(300.dp) else Modifier.weight(1f)
                        ChoiceMenu(
                            modifier = menuModifier,
                            label = stringResource(Res.string.certificate_language),
                            current = CERTIFICATE_LANGUAGE_NAMES[model.language].orEmpty(),
                            options = CertificateDefaults.LANGUAGES.map { it to CERTIFICATE_LANGUAGE_NAMES[it].orEmpty() },
                            onSelect = { lang ->
                                if (lang != model.language) {
                                    model.language = lang
                                    languageChanged = lang
                                }
                            },
                        )
                        val sampleLabel = stringResource(Res.string.certificate_sample_person, CertificateSampleData.NAME)
                        ChoiceMenu(
                            modifier = menuModifier,
                            label = stringResource(Res.string.certificate_preview_with),
                            current = previewPerson?.let { "${it.name} ${it.lastNameAbbreviation}".trim() } ?: sampleLabel,
                            options = listOf<Pair<String?, String>>(null to sampleLabel) +
                                people.map { it.id to "${it.name} ${it.lastNameAbbreviation}".trim() },
                            onSelect = { previewId = it },
                        )
                    }
                }

                val editor: @Composable (Modifier) -> Unit = { modifier ->
                    CertificateEditorColumn(model, family, logoBase64, gmailAddress, wide, modifier) {
                        if (onOpenLogoSettings != null) {
                            if (model.toTemplate() == baseline) {
                                onDismiss()
                                onOpenLogoSettings()
                            } else {
                                confirmLeave = true
                            }
                        }
                    }
                }
                val preview: @Composable (Modifier) -> Unit = { modifier ->
                    CertificatePreviewPane(if (ready) assembly else null, ready, logoBase64, signer, modifier)
                }

                if (wide) {
                    Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        editor(Modifier.weight(1f).fillMaxHeight())
                        preview(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    var tab by remember { mutableIntStateOf(0) }
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.certificate_tab_edit)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.certificate_tab_preview)) })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad, vertical = 8.dp)) {
                        if (tab == 0) editor(Modifier.fillMaxSize()) else preview(Modifier.fillMaxSize())
                    }
                }

                HorizontalDivider()
                saveError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = pad, vertical = 6.dp),
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { confirmReset = true }) { Text(stringResource(Res.string.certificate_reset)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                    Button(
                        onClick = {
                            val template = model.toTemplate()
                            if (template.toJson().length > CertificateEditorModel.MAX_TEMPLATE_CHARS) {
                                saveError = tooLarge
                            } else {
                                saveError = null
                                onSave(template)
                            }
                        },
                        enabled = model.place.isNotBlank(),
                    ) { Text(stringResource(Res.string.save)) }
                }
            }
        }
    }

    languageChanged?.let { lang ->
        AlertDialog(
            onDismissRequest = { languageChanged = null },
            text = { Text(stringResource(Res.string.certificate_language_changed, CERTIFICATE_LANGUAGE_NAMES[lang].orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    model.loadDefaultTexts()
                    languageChanged = null
                }) { Text(stringResource(Res.string.certificate_language_load_defaults)) }
            },
            dismissButton = {
                TextButton(onClick = { languageChanged = null }) { Text(stringResource(Res.string.certificate_language_keep)) }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(Res.string.certificate_reset)) },
            text = { Text(stringResource(Res.string.certificate_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    model.loadDefaultTexts()
                    confirmReset = false
                }) { Text(stringResource(Res.string.certificate_reset_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            text = { Text(stringResource(Res.string.certificate_leave_unsaved)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onDismiss()
                    onOpenLogoSettings?.invoke()
                }) { Text(stringResource(Res.string.certificate_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun CertificateEditorColumn(
    model: CertificateEditorModel,
    family: FontFamily,
    logoBase64: String,
    gmailAddress: String,
    wide: Boolean,
    modifier: Modifier,
    onChangeLogo: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CertificateToolbar(model, wrap = wide)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(end = if (wide) 8.dp else 0.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CertificateLogoSection(logoBase64, onChangeLogo)
            val footerHint = stringResource(Res.string.certificate_footer_placeholder)
            for (spec in CertificateBlocks.all) {
                when (spec.key) {
                    "dateLine" -> CertificateBlockCard(model, spec, family) { CertificatePlaceField(model) }
                    "footer" -> {
                        CertificateSignatureSection(model)
                        CertificateBlockCard(model, spec, family, placeholder = footerHint)
                    }
                    else -> CertificateBlockCard(model, spec, family)
                }
            }
            HorizontalDivider()
            CertificateMailSection(model, gmailAddress)
        }
    }
}

@Composable
private fun CertificatePreviewPane(
    assembly: CertAssembly?,
    ready: Boolean,
    logoBase64: String,
    signer: CertSigner,
    modifier: Modifier,
) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            assembly != null -> CertificatePreview(assembly, logoBase64, signer, Modifier.widthIn(max = 720.dp))
            ready -> Text(
                stringResource(Res.string.certificate_preview_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun <T> ChoiceMenu(
    label: String,
    current: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, text) in options) {
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        open = false
                        onSelect(value)
                    })
                }
            }
        }
    }
}
