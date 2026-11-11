# Sync Settings Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the overloaded "Sync & data" settings with four calm, task-oriented pages (Connexion, Équipe, Fonctions en ligne, Sauvegarde; Sheets mode: Connexion, Synchronisation) on desktop and Android, with no lost function.

**Architecture:** A small shared settings UI kit (`ui/components/settings/`) and one `SyncSettingsBindings` holder (values + callbacks) built once per screen replace the 45-parameter `FirebaseSyncSettingsSection`. Each page is a `commonMain` composable taking the bindings. Desktop gets new `DesktopSettingsNav` entries; Android gets one `ExpandableSettingsCategory` per page. Existing logic (ViewModel, `SettingsManager`, join QR, project config card, migration wizard, Firebase setup assistant) is reused, not rewritten. Team role changes move from UID entry to picking a member, resolved by e-mail inside the data layer.

**Tech Stack:** Kotlin Multiplatform (commonMain, desktopMain, androidMain), Compose Multiplatform Material 3, compose resources (7 locales), GitLive Firestore, `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-11-12-sync-settings-redesign-design.md`

## Global Constraints

- No function is lost except "Synchroniser maintenant" / "Tester le statut" in Firebase mode (spec §2).
- Firebase guide: large "Guide de configuration Firebase" button at the top of Connexion, opening the existing assistant at its current size; the assistant itself is unchanged.
- Help: each block has a "?" next to its title opening a `HelpDialog`; today's explanatory texts become that content. No explanatory paragraph left in the page body.
- Visual rules (spec §3): content max 720 dp wide on desktop; 28 dp between blocks; block = title + "?" + one `surfaceContainerLow` surface, 16 dp corners, no elevation, rows split by `outlineVariant` at 40 % alpha; row padding 16 dp horizontal / 14 dp vertical; status pills are text only on a tinted 8 dp rounded background; icons only for actions (help, copy, close, chevron), never decorative; light and dark via `MaterialTheme`.
- Read-only: every Firebase page passes `readOnlyNotice = "Seuls les admins de l'organisation peuvent modifier ces réglages."` to `SettingsPage` when `!isOrgAdmin` and a user is signed in; no per-card admin reminder remains.
- Member UIDs never reach the UI or settings (existing rule in `FirestoreGateway.kt`). Role changes and removal: only for Firebase org admins, never on one's own row, with confirmation; the Firestore rules stay the real barrier.
- Mirror frequency menu maps to stored minutes: "Chaque heure" = 60, "Chaque jour" = 1440, "Seulement à la main" = 0. Any other stored value shows as its nearest option without rewriting it until the user picks.
- Sheets sync frequency menu: 1, 5, 15, 30, 60 minutes (same stored value as `getSyncInterval()`).
- Copy: French tone of the app settings ("vous"), no em dashes, no artificial phrasing; every new string in the 7 `shared/src/commonMain/composeResources/values*/strings.xml`; `python3 scripts/check-i18n.py` passes.
- Git: `/usr/bin/git`. Never stage `shared/src/desktopMain/resources/*.dylib`. The working tree holds unrelated uncommitted portal changes (`webform/*`, `VolunteerPortalUi.kt`, settings plumbing for the portal logo): commit only the files of each task; do not revert or commit those unless a task touches the same lines, in which case build on them.
- Known pre-existing test failure: `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode…`; introduce no other failure. Compile checks: `./gradlew :shared:compileDebugKotlinAndroid :app:compileDebugKotlin :desktopApp:compileKotlinJvm`.

## Review Focus

1. A non-admin team member opening Équipe: no role panel, no "Ajouter une personne" secrets, one read-only notice; rules would refuse anyway. (Task 4 test `nonAdminSeesNoMemberActions` at the composable-state level via `memberActionsAllowed`.)
2. Two member documents with the same e-mail (re-install, two devices): role change must refuse with a clear message instead of picking one. (Task 2 test `ambiguousEmailIsRefused`.)
3. Switching "Qui peut rejoindre" back to "Toute personne" with domains set: must confirm, then clear and publish the empty list exactly like removing domains today. (Task 4 step.)
4. Mirror interval stored as an odd value (e.g. 45): the menu must not silently rewrite it. (Task 1 test `unknownIntervalKeepsStoredValue`.)
5. Sheets backend: Équipe and Fonctions en ligne must not appear, and no Firebase-only callback runs. (Task 7 nav test or manual check.)

---

### Task 1: Settings UI kit and pure helpers

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SettingsKit.kt`
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncSettingsModel.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/ui/components/settings/SyncSettingsModelTest.kt`

**Interfaces (Produces):**
- Composables: `SettingsPage(title: String, subtitle: String, readOnlyNotice: String? = null, content: @Composable ColumnScope.() -> Unit)`, `SettingsBlock(title: String, help: HelpContent? = null, content: @Composable ColumnScope.() -> Unit)`, `SettingsRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null)`, `SettingsSwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, expanded: @Composable (ColumnScope.() -> Unit)? = null)` (sub-options animate in under the row while checked), `SettingsDivider()`, `StatusPill(text: String, tone: PillTone)`, `enum class PillTone { OK, ATTENTION, NEUTRAL }`, `ChoiceCards(options: List<ChoiceOption>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit)` with `data class ChoiceOption(val title: String, val subtitle: String)`, `DomainChips(domains: List<String>, enabled: Boolean, onRemove: (String) -> Unit, onAdd: suspend (String) -> String?)` (returns an error message or null), `data class HelpContent(val title: String, val body: String)`, `HelpDialog(content: HelpContent, onDismiss: () -> Unit)`, `CopyField(value: String)` (mono text + copy icon; replaces `CopyableUrlBox` usage in these pages).
- Pure: `enum class MirrorFrequency(val minutes: Int) { HOURLY(60), DAILY(1440), MANUAL(0) }` with `fun MirrorFrequency.Companion.fromMinutes(m: Int): MirrorFrequency` (nearest; 0 or negative = MANUAL); `val SHEETS_SYNC_MINUTES = listOf(1, 5, 15, 30, 60)`; `enum class SyncConnectionState { READY, NEEDS_SIGN_IN, NEEDS_CONFIG }` with `fun syncConnectionState(orgReady: Boolean, projectReady: Boolean, signedIn: Boolean): SyncConnectionState`.

- [ ] **Step 1: Tests** `SyncSettingsModelTest`: `fromMinutes(60)==HOURLY`, `(1440)==DAILY`, `(0)==MANUAL`, `(-5)==MANUAL`, `unknownIntervalKeepsStoredValue` (`fromMinutes(45)==HOURLY` and the page only writes on user pick: assert the helper is pure, no setter involved), `(2000)==DAILY`; `syncConnectionState`: all true → READY; org+project, not signed in → NEEDS_SIGN_IN; missing org or project → NEEDS_CONFIG.
- [ ] **Step 2: Run** `./gradlew :shared:desktopTest --tests '*SyncSettingsModelTest*'` → FAIL.
- [ ] **Step 3: Implement** helpers, then the composables per Global Constraints visuals (`ColumnScope` content, `widthIn(max = 720.dp)` in `SettingsPage`, `AnimatedVisibility` for `expanded`).
- [ ] **Step 4: Run** the test → PASS; compile desktop + Android.
- [ ] **Step 5: Commit** — `Add the settings UI kit for the sync pages`

---

### Task 2: Team actions by e-mail

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/remote/FirestoreGateway.kt` (add `internal suspend fun listMemberRecords(orgId: String): List<FirestoreMemberRecord> = emptyList()`), `GitLiveFirestoreGateway.kt` (implement with the same query as `listMembers`, using `memberRecordFromSnapshot`), `FirebaseTeamActions.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/data/remote/FirebaseTeamActionsByEmailTest.kt`

**Interfaces (Produces):**
- Pure resolver: `internal fun resolveMemberUid(records: List<FirestoreMemberRecord>, email: String, selfUid: String?): Result<String>` failing with messages `"NOT_FOUND"`, `"AMBIGUOUS"`, `"SELF"` (case-insensitive trimmed e-mail match).
- `suspend fun FirebaseTeamActions.setRoleByEmail(platformContext: PlatformContext?, settings: SettingsManager, email: String, role: MemberRole): Result<Unit>` (resolves, then `MemberRoleAdmin.assignTeamMember` with the resolved uid and the e-mail).
- `suspend fun FirebaseTeamActions.removeMemberByEmail(platformContext: PlatformContext?, settings: SettingsManager, email: String): Result<Unit>` (resolves, then `gateway.deleteDocument(org, "members", uid)`; permission errors map to `"PERMISSION"` like `assignTeamMember`).
- Self uid comes from `createFirebaseAuthService(platformContext).currentUserId()`.

- [ ] **Step 1: Tests** on `resolveMemberUid`: single match returns its uid (`"Jean@Asso.ch "` matches `jean@asso.ch`); `ambiguousEmailIsRefused` (two records same e-mail) → `AMBIGUOUS`; unknown → `NOT_FOUND`; match equals `selfUid` → `SELF`; blank e-mail → `NOT_FOUND`.
- [ ] **Step 2: Run → FAIL. Step 3: Implement. Step 4: Run → PASS; compile.**
- [ ] **Step 5: Commit** — `Change team roles by picking a member instead of typing a UID`

---

### Task 3: Bindings and the Connexion page

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncSettingsBindings.kt`
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncConnectionPage.kt` (page + `FirebaseProjectDialog` + `AddOrganisationDialog`)
- Modify: 7 `strings.xml`

**Interfaces (Produces):**
- `class SyncSettingsBindings(...)`: every value and callback `FirebaseSyncSettingsSection` takes today (same names, see its parameter list), plus `isOrgAdmin: Boolean`, `onOpenFirebaseGuide: () -> Unit`, `onOpenMigration: (MigrationDirection) -> Unit`, `lastSyncAt: Long`, `onSyncNow: () -> Unit` (Sheets only).
- `@Composable fun rememberSyncSettingsBindings(viewModel: EventManagerViewModel, settingsManager: SettingsManager, platformContext: PlatformContext, onOpenFirebaseGuide: () -> Unit, onOpenMigration: (MigrationDirection) -> Unit): SyncSettingsBindings` — absorbs the sign-in, sign-out, allowed-domains publish, project field and org callbacks now written twice (desktop `SettingsScreen.desktop.kt` ~420-555 and Android `SettingsScreen.kt` ~2020+). Moves the code, does not change its behaviour. `isOrgAdmin` uses `FirebaseOrgAdminAccess.currentUserIsOrgAdmin` as the section does.
- `@Composable fun SyncConnectionPage(b: SyncSettingsBindings)`.

Page content (spec §4.1): state surface with `StatusPill` from `syncConnectionState`, "Connecté à {org} en tant que {e-mail}" or what is missing, buttons "Se déconnecter" / "Se connecter" / "Reconfigurer…"; under it the large "Guide de configuration Firebase" button (full width, same look as today's `SyncSetupInstructionsButton`) with the line "Pour la première installation et après chaque mise à jour de NoctuList"; block "Organisations" (rows with remove + confirm, "Ajouter une organisation" → `AddOrganisationDialog` using the existing validation of `FirebaseConfiguredOrgsSection`: format, duplicate, then `onOrgIdCommitted`); "Reconfigurer…" → `FirebaseProjectDialog` wrapping the existing `FirebaseAdminProjectConfigCard` unchanged. Sign-in feedback errors show under the state. "?" help: Firebase connection (reuse `firebase_settings_intro`, `firebase_step_signin_body`, `firebase_configured_orgs_body`).

- [ ] **Step 1: Implement bindings** and switch both screens to build them (screens still render the old section through the bindings for now; no visual change). Compile both.
- [ ] **Step 2: Implement the page and dialogs + strings; check-i18n.**
- [ ] **Step 3: Compile; commit** — `Add the Connexion settings page`

---

### Task 4: Équipe page

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncTeamPage.kt` (page, `AddPersonDialog`, `MemberDialog`)
- Modify: `FirebaseJoinUi.kt` only to expose what `AddPersonDialog` reuses (payload building, `FirebaseJoinCodec.encode`, QR bitmap, invitation code rotate) as an internal function instead of duplicating it; 7 `strings.xml`
- Test: extend `SyncSettingsModelTest` with `memberActionsAllowed(isOrgAdmin: Boolean, memberEmail: String?, selfEmail: String?): Boolean` (pure; false for non-admin, own row, or blank e-mail) — `nonAdminSeesNoMemberActions`, `ownRowHasNoActions`.

**Consumes:** Task 1 kit, Task 2 actions, Task 3 bindings.

Page content (spec §4.2):
- Block "Ajouter une personne": row with button → `AddPersonDialog`: QR 240 dp; "Pas de caméra ? Copiez ce code dans NoctuList sur l'autre appareil." + `CopyField(encoded)`; overflow menu "Désactiver les anciens QR" (confirm, then the existing rotate). If no invitation code exists and the user is org admin, create it on open (existing create path). Non-admin: the existing restricted text.
- Block "Qui peut rejoindre l'équipe ?": `ChoiceCards` ["Toute personne qui scanne le QR" / "Seulement certaines adresses"]; selected = domains not empty (or the user just picked the second card); second shows `DomainChips` bound to `onAllowedEmailDomainsChange` (add normalizes with `FirebaseEmailDomainPolicy`). Picking the first while domains exist: confirm dialog, then `onAllowedEmailDomainsChange(emptyList())`.
- Block "Membres": rows (initials circle, e-mail, `StatusPill` Admin=OK / Membre=NEUTRAL); click (only if `memberActionsAllowed`) → `MemberDialog`: segmented Membre / Admin, "Retirer de l'équipe"; "Rendre admin" confirm text "{e-mail} pourra modifier les réglages et l'équipe. Continuer ?"; remove confirm; errors mapped like today's `mapError` plus `NOT_FOUND`, `AMBIGUOUS`, `SELF`. Small "Actualiser" text button in the block header area.
- "?" help: roles (reuse `firebase_team_role_admin_desc`, `firebase_team_role_member_desc`), joining (`firebase_join_qr_body`), domains (`firebase_email_domains_body`).

- [ ] **Step 1: Tests for `memberActionsAllowed` → FAIL → implement → PASS.**
- [ ] **Step 2: Implement page + dialogs + strings; check-i18n; compile.**
- [ ] **Step 3: Commit** — `Add the Équipe settings page`

---

### Task 5: Fonctions en ligne page

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncOnlinePage.kt`
- Modify: `VolunteerPortalUi.kt` (extract the Google section body so the page can host it; remove the now-duplicated link/status texts from `VolunteerPortalSettingsCard` or stop using that card), `GuestFormSettingsSection.kt` (same), 7 `strings.xml`

Page content (spec §4.3):
- Block "Site public": row "Site public" with `CopyField(guestFormSiteOrigin)`, and a `StatusPill` "Traitement automatique actif depuis {date}" (OK) / "Traitement automatique inactif" (NEUTRAL) from `portalServerStatus`. Hidden values when the origin is blank: one line "Site pas encore publié" + "Guide de configuration Firebase" text button.
- `SettingsSwitchRow` "Formulaires de guestlist" → expanded: switch "La billetterie valide les formulaires".
- `SettingsSwitchRow` "Plateforme bénévole" → expanded: row "Lien du portail" with `CopyField(volunteerPortalUrl)`; switch "Afficher le logo de l'association" (subtitle: "Le logo défini dans Réglages, E-mail"); switch "Connexion avec un compte Google" (enabled only when at least one verified domain) with `DomainChips` for the verified domains (add = `onAddVolunteerPortalGoogleDomain`, mapping `DomainCheck` to the existing messages) and, when no domain is verified yet, one-click suggestion chips for each team domain not yet verified ("Utiliser asso.ch") that call the same add.
- `SettingsSwitchRow` "Photos de profil".
- `SettingsSwitchRow` "Import Google Drive", error text only when `getDriveImportScopeError()` is not blank.
- "?" help per block from the current `*_settings_body` / `*_description` strings.

- [ ] **Step 1: Implement + strings; check-i18n; compile.**
- [ ] **Step 2: Commit** — `Add the Fonctions en ligne settings page`

---

### Task 6: Sauvegarde page

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncBackupPage.kt`
- Modify: `FirebaseSheetsMirrorSettingsSection.kt` (reuse its field and service-account parts; the frequency text field becomes a `MirrorFrequency` menu), 7 `strings.xml`

Page content (spec §4.4): `SettingsSwitchRow` "Copie de sauvegarde dans Google Sheets" → expanded: spreadsheet ID field, "Fréquence" menu (`MirrorFrequency`, writes `setSheetsMirrorIntervalMinutes` only on pick, then `onMirrorSettingsChanged`), "Exporter maintenant" (`onMirrorExport`) with "Dernier export il y a …" from `getSheetsMirrorLastExportAt`, service account key state + import button, collapsed "Noms des feuilles" with the 9 sheet name fields. Block "Changer de stockage": row "Passer à Google Sheets…" → `onOpenMigration(FIREBASE_TO_SHEETS)`.

- [ ] **Step 1: Implement + strings; check-i18n; compile.**
- [ ] **Step 2: Commit** — `Add the Sauvegarde settings page`

---

### Task 7: Sheets mode pages and wiring on desktop and Android

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SheetsSettingsPages.kt` (`SheetsConnectionPage`, `SheetsSyncPage`)
- Modify: `shared/src/desktopMain/kotlin/com/eventmanager/app/ui/screens/SettingsScreen.desktop.kt`, `shared/src/androidMain/kotlin/com/eventmanager/app/ui/screens/SettingsScreen.kt`, 7 `strings.xml`
- Delete: `FirebaseSyncSettingsSection` composable once unused (keep helpers still referenced: `shouldShowSyncInterval`, `shouldShowSheetsManualSyncControls`, `SheetsMigrateToFirebaseButton` if reused)

Sheets pages (spec §5): Connexion = spreadsheet ID, service account key state + upload (`ServiceAccountKeyUploadButton`, `DesktopServiceAccountKeyInfoPanel` content on desktop / Android equivalent passed as slot), collapsed "Noms des feuilles" (the 9 `SheetNameField`s), "Enregistrer" (`saveSheetsConfig`). Synchronisation = "Synchroniser maintenant" + "Dernière synchro il y a …" (`getLastSyncTime`), frequency menu over `SHEETS_SYNC_MINUTES`, row "Passer à Firebase…" (existing migration wizard). Platform-specific bits enter as composable slots so the pages stay in `commonMain`.

Desktop: replace `DesktopSettingsNav.Sync` by `SyncConnection`, `SyncTeam`, `SyncOnline`, `SyncBackup` (Firebase) or `SyncConnection`, `SyncSheets` (Sheets) in `navItems`, each rendering its page; manual sync controls and the old sync category body are removed. Titles: "Connexion", "Équipe", "Fonctions en ligne", "Sauvegarde", "Synchronisation"; icons from the existing set (CloudSync, Group, Public, Backup, Sync). Remember the selected section key migration: an old saved `Sync` selection falls back to `SyncConnection`.
Android: replace the one `ExpandableSettingsCategory` "Sync" by one per page, each with its own remembered expanded flag (reuse `setCategorySyncExpanded` for Connexion; add flags in `SettingsManager` for the others following its pattern).

- [ ] **Step 1: Implement Sheets pages; wire desktop; compile.**
- [ ] **Step 2: Wire Android; compile.**
- [ ] **Step 3: Remove dead code and unused strings that only the old section used (grep each before deleting); check-i18n; full `:shared:desktopTest`.**
- [ ] **Step 4: Commit** — `Split the sync settings into Connexion, Équipe, Fonctions en ligne and Sauvegarde`

---

### Task 8: Visual check and polish

**Files:** whatever the check reveals; `CHANGELOG.md`.

- [ ] **Step 1:** Run the desktop app (`./gradlew :desktopApp:run`), open each page in light and dark, as org admin and as a non-admin member if available, and in Sheets mode (switch backend on a test profile only if it is safe; otherwise check the Sheets pages with a preview composable). Screenshot each page; fix spacing, overflow, truncation, wrong states.
- [ ] **Step 2:** Android: compile and, if an emulator or device is available, open the settings and check the 4 cards. Otherwise state that it was not checked.
- [ ] **Step 3:** CHANGELOG entry (plain sentences). Commit — `Polish the new sync settings pages`
