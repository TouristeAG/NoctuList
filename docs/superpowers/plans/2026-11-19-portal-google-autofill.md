# Automatic Google accounts and portal access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A team setting builds each new portal user's Google address from a template and links it directly; another team setting opens portal access for every volunteer added in the app.

**Architecture:** A pure template helper turns a volunteer's names into an address. `VolunteerPortalService` applies it through the existing `assignGoogleAccount` right after an access opens (grant and signup). Three new institution settings sync like the other `VOLUNTEER_PORTAL_*` keys; the Online settings page and the Add volunteer dialog read them through the view model.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlin.test (`./gradlew :shared:desktopTest`).

**Spec:** `docs/superpowers/specs/2026-11-19-portal-google-autofill-design.md`

## Global Constraints

- New settings are Firebase-only institution settings (in `FIREBASE_ONLY_KEYS`), defaults: auto access `false`, Google auto `false`, template `""`.
- The rule never replaces a non-empty `portalGoogleEmail`.
- Rule errors (`INVALID`, `DOMAIN`, `TAKEN`, `SAME_AS_EMAIL`, `SAVE_FAILED`) are logged with `println("Portal: ...")` and never fail the access grant or the signup.
- UI copy in French and English, no em dashes, no subtitle that repeats the title (see memory rules).
- Use `/usr/bin/git` (the default git cannot read worktrees). Never commit `shared/src/desktopMain/resources/*.dylib`. No Claude co-author line in commits.

## Review Focus

- Volunteer with an empty last name (signup always has one, manual add may not): `{nom}` yields an empty part → `render` returns null, no link, no crash.
- Names that are only symbols or emoji (`"???"`): cleaned part empty → null.
- Template typed with uppercase or stray spaces (`" {Prénom}.{NOM}@Asso.ch "`): still works, domain lowercased.
- Google sign-in switched off after the rule was set: nothing is linked (service checks `portalGoogleEnabled()`).
- Signup that links an existing profile which already has a Google address: address kept.

---

### Task 1: Address template helper

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalGoogleAddressTemplate.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalGoogleAddressTemplateTest.kt`

**Interfaces:**
- Produces:
  - `object PortalGoogleAddressTemplate`
  - `fun render(template: String, firstName: String, lastName: String): String?`
  - `fun cleanPart(raw: String): String`
  - `fun check(template: String, verifiedDomains: List<String>, anyAccount: Boolean): TemplateCheck`
  - `const val SAMPLE_FIRST = "Jean Pierre"`, `const val SAMPLE_LAST = "D'Amico"`
  - `enum class TemplateCheck { OK, EMPTY, INVALID, DOMAIN }` (top level, same file)

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun ownersConvention() = assertEquals("jean-pierre.damico@asso.ch",
    PortalGoogleAddressTemplate.render("{prénom}.{nom}@asso.ch", "Jean Pierre", "D'AMICO"))
@Test fun accentsAndCase() = assertEquals("helene.muller@asso.ch",
    PortalGoogleAddressTemplate.render(" {Prénom}.{NOM}@Asso.ch ", "Hélène", "Müller"))
@Test fun unaccentedAndEnglishPlaceholders() {
    assertEquals("lea.roth@asso.ch", PortalGoogleAddressTemplate.render("{prenom}.{nom}@asso.ch", "Léa", "Roth"))
    assertEquals("lea.roth@asso.ch", PortalGoogleAddressTemplate.render("{first}.{last}@asso.ch", "Léa", "Roth"))
}
@Test fun initials() = assertEquals("j.dupont@asso.ch",
    PortalGoogleAddressTemplate.render("{p}.{nom}@asso.ch", "Jean", "Dupont"))
@Test fun cleaning() {
    assertEquals("jean-pierre", PortalGoogleAddressTemplate.cleanPart("  Jean   Pierre "))
    assertEquals("strasse", PortalGoogleAddressTemplate.cleanPart("Straße"))
    assertEquals("aeo", PortalGoogleAddressTemplate.cleanPart("Æø"))
    assertEquals("anne-marie", PortalGoogleAddressTemplate.cleanPart("Anne_Marie"))
    assertEquals("", PortalGoogleAddressTemplate.cleanPart("???"))
}
@Test fun emptyNamePartGivesNoAddress() {
    assertNull(PortalGoogleAddressTemplate.render("{prénom}.{nom}@asso.ch", "Jean", ""))
    assertNull(PortalGoogleAddressTemplate.render("{prénom}.{nom}@asso.ch", "???", "Dupont"))
}
@Test fun brokenTemplatesGiveNoAddress() {
    assertNull(PortalGoogleAddressTemplate.render("{prénom}.{nom}", "Jean", "Dupont"))
    assertNull(PortalGoogleAddressTemplate.render("{prénom}@a@asso.ch", "Jean", "Dupont"))
    assertNull(PortalGoogleAddressTemplate.render("{prénom}@{nom}.ch", "Jean", "Dupont"))
}
@Test fun checkTemplate() {
    val t = "{prénom}.{nom}@asso.ch"
    assertEquals(TemplateCheck.OK, PortalGoogleAddressTemplate.check(t, listOf("asso.ch"), anyAccount = false))
    assertEquals(TemplateCheck.DOMAIN, PortalGoogleAddressTemplate.check(t, listOf("other.ch"), anyAccount = false))
    assertEquals(TemplateCheck.OK, PortalGoogleAddressTemplate.check(t, emptyList(), anyAccount = true))
    assertEquals(TemplateCheck.EMPTY, PortalGoogleAddressTemplate.check("  ", listOf("asso.ch"), false))
    assertEquals(TemplateCheck.INVALID, PortalGoogleAddressTemplate.check("{prénom}", listOf("asso.ch"), false))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :shared:desktopTest --tests "com.eventmanager.app.portal.PortalGoogleAddressTemplateTest"`
Expected: compilation failure (object not defined).

- [ ] **Step 3: Implement**

- Placeholders are matched case-insensitively: `{prénom}|{prenom}|{first}` → cleaned first name, `{nom}|{last}` → cleaned last name, `{p}` / `{n}` → first char of the cleaned part. A template containing any other `{...}` is invalid.
- The domain part (after the single `@`) must be literal text: placeholders only in the local part. Lowercase and trim the whole template first.
- `cleanPart`: lowercase, map `ß→ss æ→ae œ→oe ø→o đ→d ł→l`, strip combining marks after NFD decomposition (Kotlin common has no `java.text.Normalizer`: use a small explicit table for Latin-1 Supplement + Latin Extended-A letters), drop apostrophes (`'` and `’`), turn runs of whitespace/`_`/`-` into one `-`, drop everything outside `[a-z0-9.-]`, trim leading/trailing `-` and `.`.
- Any placeholder that cleans to empty → null. Final string must pass `PortalInputValidator.cleanEmail`, else null.
- `check`: blank → EMPTY; `render(template, SAMPLE_FIRST, SAMPLE_LAST)` null → INVALID; not `anyAccount` and domain not in `verifiedDomains` (compare lowercase) → DOMAIN; else OK.

- [ ] **Step 4: Run tests, expect PASS** (same command).

- [ ] **Step 5: Commit** `Add the Google address template for portal accounts`

### Task 2: Team settings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/InstitutionSettings.kt` (keys, all-keys list, `FIREBASE_ONLY_KEYS`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/SettingsManager.kt` (storage keys near line 218, accessors near line 500, export near line 1227, import near line 1400)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/data/sync/SettingsManagerPortalAutoTest.kt` (copy `MemoryStorage` from `SettingsManagerPortalQrTest.kt`)

**Interfaces:**
- Produces, in `InstitutionSettingsKeys`: `VOLUNTEER_PORTAL_AUTO_ACCESS = "volunteer_portal_auto_access"`, `VOLUNTEER_PORTAL_GOOGLE_AUTO = "volunteer_portal_google_auto"`, `VOLUNTEER_PORTAL_GOOGLE_TEMPLATE = "volunteer_portal_google_template"`.
- Produces, in `SettingsManager`: `isVolunteerPortalAutoAccess(): Boolean`, `setVolunteerPortalAutoAccess(Boolean)`, `isVolunteerPortalGoogleAuto(): Boolean`, `setVolunteerPortalGoogleAuto(Boolean)`, `volunteerPortalGoogleTemplate(): String`, `setVolunteerPortalGoogleTemplate(String)` (stores trimmed). Each setter calls `touchInstitutionSettingLastModified`.

- [ ] **Step 1: Failing tests**: defaults (`false`, `false`, `""`); each setter round-trips through `getInstitutionSettingRows()` (`"true"`, `"true"`, the template text); applying a row through the same import path used by the existing settings test helpers restores the values; all three keys are not `isSyncedToSheets`.
- [ ] **Step 2: Run** `./gradlew :shared:desktopTest --tests "*SettingsManagerPortalAutoTest"`, expect failure.
- [ ] **Step 3: Implement** following the `VOLUNTEER_PORTAL_GOOGLE_ANY_ACCOUNT` pattern at every spot it appears. Also run `InstitutionSettingsKeysTest` since it may enumerate keys.
- [ ] **Step 4: Run** `./gradlew :shared:desktopTest --tests "*SettingsManagerPortal*" --tests "*InstitutionSettingsKeysTest"`, expect PASS.
- [ ] **Step 5: Commit** `Add team settings for automatic portal access and Google accounts`

### Task 3: Apply the rule when an access opens

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt` (`VolunteerPortalHost` near line 50, `grantAccess` line ~674, `processSignup` line ~236)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/VolunteerPortalServiceTest.kt` (FakeHost gets `var googleTemplate = ""`)

**Interfaces:**
- Consumes: `PortalGoogleAddressTemplate.render` (Task 1), existing `assignGoogleAccount(volunteer, email): String?`.
- Produces: `fun portalGoogleAddressTemplate(): String = ""` on `VolunteerPortalHost` (non-empty only when the automatic rule is on); private `suspend fun applyGoogleAddressRule(volunteerId: String)` in the service.

- [ ] **Step 1: Failing tests** (host `googleDomains = listOf("asso.ch")`, `googleTemplate = "{prénom}.{nom}@asso.ch"`):
  - `grantLinksTheGeneratedGoogleAddress`: volunteer `Léa`/`ROTH`, `FakeAuth(Created(...))` → `portalGoogleEmail == "lea.roth@asso.ch"`, `portalGoogleLinkedBy == "ADMIN"`, `portalUid` set.
  - `grantToExistingAccountAlsoLinks`: `EmailExists` → same address set.
  - `grantKeepsAnExistingGoogleAddress`: volunteer already has `portalGoogleEmail = "lea@asso.ch"` → unchanged.
  - `noRuleWhenTemplateEmptyOrGoogleOff`: `googleTemplate = ""` → empty; and separately `googleEnabled = false` → empty.
  - `takenAddressLeavesTheFieldEmpty`: another volunteer holds `lea.roth@asso.ch` → result still `Created`, field empty.
  - `signupLinksTheGeneratedGoogleAddress`: push `submittedForm()` (Max / Dupont) as in the existing signup tests → created volunteer has `max.dupont@asso.ch`.
  - `signupLinkingExistingProfileKeepsItsGoogleAddress`: roster volunteer with the form's e-mail and `portalGoogleEmail = "max@asso.ch"` → still `max@asso.ch`.
- [ ] **Step 2: Run** `./gradlew :shared:desktopTest --tests "*VolunteerPortalServiceTest"`, expect the new tests to fail.
- [ ] **Step 3: Implement** `applyGoogleAddressRule`: return unless `host.portalGoogleEnabled()` and template non-blank; read the current volunteer from `host.volunteers()` by id; return if its `portalGoogleEmail` is non-blank; `render(template, name, lastNameAbbreviation)` null → log and return; call `assignGoogleAccount`; non-null code → log. Wrap in `runCatching` (rethrow `CancellationException`). Call it after a successful save in both `grantAccess` branches and at the end of both `processSignup` branches (linked and created), before `finishSignup`.
- [ ] **Step 4: Run** the full `VolunteerPortalServiceTest`, expect PASS.
- [ ] **Step 5: Commit** `Link the generated Google address when a portal access opens`

### Task 4: View model, settings page, add dialog, copy

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/viewmodel/EventManagerViewModel.kt` (state flows near 2623-2671, setters near 2675, host near 2906)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncSettingsBindings.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncOnlinePage.kt` (`PortalOptions`, `PortalGoogleOptions`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/screens/VolunteerScreen.kt` (`AddVolunteerDialog` ~534, call site ~317)
- Modify: `shared/src/commonMain/composeResources/values/strings.xml`, `values-fr/strings.xml`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/ui/components/settings/SyncSettingsModelTest.kt` only if bindings construction there needs the new fields.

**Interfaces:**
- Consumes: Task 1 `check`, `render`, `SAMPLE_FIRST/LAST`, `TemplateCheck`; Task 2 accessors.
- Produces: view model `volunteerPortalAutoAccess`, `volunteerPortalGoogleAuto`, `volunteerPortalGoogleTemplate` (`StateFlow`), `setVolunteerPortalAutoAccess(Boolean)`, `setVolunteerPortalGoogleAuto(Boolean)`, `setVolunteerPortalGoogleTemplate(String)`. Setters follow `setVolunteerPortalQrEnabled` (Firebase only, `backupInstitutionSettingsToSheets()`; no republish needed). The flows refresh in the same place as the other portal flows (~line 2656). Host: `portalGoogleAddressTemplate() = if (isVolunteerPortalGoogleAuto()) volunteerPortalGoogleTemplate() else ""`.

- [ ] **Step 1: Settings page.** In `PortalOptions`, a `SettingsSwitchRow` "Open portal access for every new volunteer" / "Ouvrir l'accès pour chaque nouveau bénévole", no subtitle. In `PortalGoogleOptions`, after the scope cards and before the `if (anyAccount) return`, move the early return so the new block shows in both modes: a `SettingsSwitchRow` "Fill in Google accounts automatically" / "Remplir les comptes Google automatiquement"; when on, an `OutlinedTextField` for the template (save on focus loss or on a Save button, not on every keystroke, since each save syncs), placeholder `{prénom}.{nom}@asso.ch` (EN `{first}.{last}@asso.ch`), supporting text with the variables `{prénom} {nom} {p} {n}`, then the preview line "Jean Pierre D'Amico → jean-pierre.damico@asso.ch" from `render(input, SAMPLE_FIRST, SAMPLE_LAST)`, or the error for `INVALID` ("This rule does not give a valid address" / "Cette règle ne donne pas d'adresse valable") / `DOMAIN` ("This domain is not in the verified domains" / "Ce domaine ne fait pas partie des domaines vérifiés"). A template with an error is not saved.
- [ ] **Step 2: Add dialog.** New parameter `portalAccessAlways: Boolean = false`. When true, hide `PortalAccessToggleRow` and pass `createPortalAccess = email.isNotBlank()` to `onConfirm`. Call site passes `portalAccessAlways = portalEnabled && viewModel.volunteerPortalAutoAccess.value` (collect as state).
- [ ] **Step 3: Build** `./gradlew :shared:desktopTest` (whole suite), expect PASS, and `./gradlew :desktopApp:compileKotlin` (or the desktop compile task the project uses), expect success.
- [ ] **Step 4: Run the desktop app** with the Firebase test org and check: settings rows appear, preview updates, DOMAIN error shows for `@other.ch`, adding a volunteer with auto access on opens the password dialog and the profile shows the generated Google account as pending.
- [ ] **Step 5: Commit** `Show the automatic access and Google rule settings`
