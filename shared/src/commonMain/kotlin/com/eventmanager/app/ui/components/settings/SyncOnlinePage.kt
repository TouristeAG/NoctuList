package com.eventmanager.app.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eventmanager.app.portal.DomainCheck
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.domainCheckMessages
import com.eventmanager.app.ui.components.portalServerLastRun
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.stringResource

/**
 * Fonctions en ligne page (Firebase): the public site, then the org-wide online features (guest
 * list forms, volunteer portal, profile photos, Google Drive import). Every control here changes a
 * setting of the whole organization, so only org admins can use them; others see a read-only notice.
 */
@Composable
fun SyncOnlinePage(b: SyncSettingsBindings, showTitle: Boolean = true) {
    SettingsPage(
        showTitle = showTitle,
        title = stringResource(Res.string.sync_online_page_title),
        subtitle = stringResource(Res.string.sync_online_page_subtitle),
        readOnlyNotice = syncPageNoticeText(b),
    ) {
        PublicSiteBlock(b)
        FeaturesBlock(b, canEdit = b.isOrgAdmin)
    }
}

@Composable
private fun PublicSiteBlock(b: SyncSettingsBindings) {
    val title = stringResource(Res.string.sync_online_site_title)
    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = stringResource(Res.string.sync_online_site_help)),
    ) {
        if (b.guestFormSiteOrigin.isBlank()) {
            SettingsRow(title = stringResource(Res.string.sync_online_site_not_published))
            SettingsDivider()
            SettingsRow(
                title = stringResource(Res.string.sync_firebase_guide_title),
                onClick = b.onOpenFirebaseGuide,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = SettingsKitDefaults.RowHorizontalPadding,
                    vertical = SettingsKitDefaults.RowVerticalPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CopyField(b.guestFormSiteOrigin)
                val lastRun = portalServerLastRun(b.portalServerStatus)
                if (lastRun != null) {
                    // Two lines: with the date the text does not fit on one line on a phone.
                    StatusPill(stringResource(Res.string.sync_online_processing_active, lastRun), PillTone.OK, maxLines = 2)
                } else {
                    StatusPill(stringResource(Res.string.sync_online_processing_inactive), PillTone.NEUTRAL)
                }
            }
        }
    }
}

@Composable
private fun FeaturesBlock(b: SyncSettingsBindings, canEdit: Boolean) {
    val title = stringResource(Res.string.sync_online_features_title)
    val guestForms = stringResource(Res.string.sync_online_guest_forms)
    val portal = stringResource(Res.string.portal_settings_title)
    val photos = stringResource(Res.string.sync_online_profile_photos)
    val drive = stringResource(Res.string.sync_online_drive_import)
    // One help for the block: each feature's former explanation, under its name.
    val helpBody = listOf(
        guestForms to stringResource(Res.string.guest_form_settings_body),
        portal to stringResource(Res.string.portal_settings_body),
        photos to stringResource(Res.string.profile_photos_settings_body),
        drive to stringResource(Res.string.drive_import_settings_description),
    ).joinToString("\n\n") { (name, body) -> "$name\n$body" }

    SettingsBlock(title = title, help = HelpContent(title = title, body = helpBody)) {
        SettingsSwitchRow(
            title = guestForms,
            subtitle = null,
            checked = b.guestFormsEnabled,
            enabled = canEdit,
            onCheckedChange = b.onGuestFormsEnabledChange,
            expanded = {
                SettingsSwitchRow(
                    title = stringResource(Res.string.sync_online_billeterie_validates),
                    subtitle = null,
                    checked = b.guestFormsBilleterieValidateEnabled,
                    enabled = canEdit,
                    onCheckedChange = b.onGuestFormsBilleterieValidateEnabledChange,
                )
            },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = portal,
            subtitle = null,
            checked = b.volunteerPortalEnabled,
            enabled = canEdit,
            onCheckedChange = b.onVolunteerPortalEnabledChange,
            expanded = { PortalOptions(b, canEdit) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = photos,
            subtitle = stringResource(Res.string.sync_online_photos_hint),
            checked = b.profilePhotosEnabled,
            enabled = canEdit,
            onCheckedChange = b.onProfilePhotosEnabledChange,
        )
        SettingsDivider()
        // Surfaced here rather than blocking sign-in: an organization whose Cloud project lacks the
        // APIs still signs in, it just cannot import.
        val scopeError = b.settingsManager.getDriveImportScopeError()
        SettingsSwitchRow(
            title = drive,
            subtitle = null,
            checked = b.driveImportEnabled,
            enabled = canEdit,
            onCheckedChange = b.onDriveImportEnabledChange,
            expanded = if (scopeError.isBlank()) null else {
                {
                    Text(
                        stringResource(Res.string.drive_import_settings_scope_error, scopeError),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            horizontal = SettingsKitDefaults.RowHorizontalPadding,
                            vertical = 8.dp,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun PortalOptions(b: SyncSettingsBindings, canEdit: Boolean) {
    if (b.volunteerPortalUrl.isNotBlank()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = SettingsKitDefaults.RowHorizontalPadding,
                vertical = SettingsKitDefaults.RowVerticalPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.sync_online_portal_link),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CopyField(b.volunteerPortalUrl)
        }
    }
    SettingsSwitchRow(
        title = stringResource(Res.string.portal_show_logo),
        subtitle = stringResource(Res.string.sync_online_logo_hint),
        checked = b.volunteerPortalShowLogo,
        enabled = canEdit,
        onCheckedChange = b.onVolunteerPortalShowLogoChange,
    )
    PortalGoogleOptions(b, canEdit)
}

/**
 * Google sign-in for volunteers. The switch cannot be on without a verified Workspace domain; the
 * team's own domains are offered as one-click suggestions that run the same check as typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortalGoogleOptions(b: SyncSettingsBindings, canEdit: Boolean) {
    val domains = b.volunteerPortalGoogleDomains
    val hasDomain = domains.isNotEmpty()
    val messageFor = domainCheckMessages()
    // Suggestions that turned out not to be Google Workspace domains are not offered again here.
    var rejected by remember { mutableStateOf(emptySet<String>()) }
    var checking by remember { mutableStateOf<String?>(null) }
    var suggestionError by remember { mutableStateOf<String?>(null) }

    SettingsSwitchRow(
        title = stringResource(Res.string.portal_google_title),
        subtitle = if (hasDomain) null else stringResource(Res.string.portal_google_needs_domain),
        checked = b.volunteerPortalGoogleEnabled && hasDomain,
        enabled = canEdit && hasDomain,
        onCheckedChange = b.onVolunteerPortalGoogleEnabledChange,
    )
    if (!canEdit && !hasDomain) return

    val suggestions = portalGoogleDomainSuggestions(b.allowedEmailDomains, domains).filterNot { it in rejected }
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = SettingsKitDefaults.RowHorizontalPadding,
            end = SettingsKitDefaults.RowHorizontalPadding,
            bottom = SettingsKitDefaults.RowVerticalPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.portal_google_domains_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DomainChips(
            domains = domains,
            enabled = canEdit,
            onRemove = b.onRemoveVolunteerPortalGoogleDomain,
            onAdd = { typed -> messageFor(b.onAddVolunteerPortalGoogleDomain(typed)) },
        )
        if (canEdit && suggestions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                suggestions.forEach { domain ->
                    AssistChip(
                        onClick = {
                            checking = domain
                            suggestionError = null
                            b.scope.launch {
                                val outcome = try {
                                    b.onAddVolunteerPortalGoogleDomain(domain)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    DomainCheck.ERROR
                                }
                                checking = null
                                suggestionError = messageFor(outcome)
                                if (outcome == DomainCheck.OTHER || outcome == DomainCheck.NOT_FOUND) {
                                    rejected = rejected + domain
                                }
                            }
                        },
                        enabled = checking == null,
                        label = { Text(stringResource(Res.string.sync_online_use_domain, domain)) },
                        leadingIcon = if (checking == domain) {
                            { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        suggestionError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
