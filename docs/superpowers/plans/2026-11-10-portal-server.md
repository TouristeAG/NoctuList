# Portal Server Processing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Volunteers get signups, profile edits, account links and QR e-mails handled within seconds by Cloud Functions, and see time-correct benefits (including what each upcoming shift brings) without any NoctuList device online.

**Architecture:** Firestore-triggered Cloud Functions (TypeScript, shipped prebuilt inside the exported form site) process `volunteerSignupForms` and `portalRequests`. Devices keep publishing `volunteerPortal/{uid}`, now in schema 2 with a 30-day benefit timeline computed by the existing Kotlin `BenefitCalculator`; the web page picks today's step. Devices stay as fallback, acting only on documents older than 2 minutes.

**Tech Stack:** Kotlin Multiplatform (commonMain), Node 22, `firebase-functions` v6 (2nd gen), `firebase-admin` v13, `nodemailer` v6, `qrcode` v1, `esbuild` (bundling), `node:test`, Firebase emulators (auth, firestore, storage, functions).

**Spec:** `docs/superpowers/specs/2026-11-10-portal-server-design.md`

## Global Constraints

- No em dashes (—) and no AI-sounding phrasing in any user-facing text: app strings, portal i18n, e-mails, guide. Check with `grep —`.
- App strings exist in all 7 locales: `values`, `values-fr`, `values-es`, `values-hi`, `values-la`, `values-zh-rCN`, `values-zh-rTW`. `python3 scripts/check-i18n.py` must pass.
- Portal page strings exist in all 5 languages of `webform/portal-i18n.js` (fr, en, es, de, it).
- `volunteer.id` (wallet QR) never appears in `volunteerPortal`, `portalMail`, `portalServer`, logs, or anything the browser reads. It only goes into the QR image of the e-mail.
- Function logs contain only document ids and reason codes, never names, e-mails or phone numbers.
- Functions have no HTTP endpoints; Firestore triggers only, `retry: false`.
- Every function first checks `orgs/{orgId}/institutionSettings/volunteer_portal_enabled` has `value == "true"`; otherwise it returns without writing.
- Claim protocol: status `PROCESSING`, `processingAt` (millis), `processingDevice` (`"server"` for functions). Stale after 5 minutes (`PortalRequest.PROCESSING_STALE_MILLIS`).
- Device grace period: 2 minutes (`120_000` ms) measured from `requestedAt` (requests) or `submittedAt` (signups).
- Timeline: horizon 30 days, at most 10 upcoming shifts, 60 past shifts, 5 wallet transfers.
- Firestore rules version becomes 13 (`NOCTULIST_FIRESTORE_RULES_VERSION`), rules copied to `shared/src/commonMain/composeResources/files/firestore.rules`.
- Nothing is committed unless a task step says so; each task ends with its own commit.
- Prerequisite: the first volunteer portal work on `feature/volunteer-portal` is still uncommitted. It must be committed (owner's go-ahead) before Task 1, otherwise the task commits would sweep it in.

## Review Focus

1. Same data published twice on the same day must not rewrite any `volunteerPortal` doc (hash stable). Timeline points are day-aligned, never the raw "now". Test in Task 2.
2. Day boundaries with a non-zero institution offset (`offsetHours = 2`): a shift at 23:00 local belongs to that local day and its gains start the next local day. Test in Task 1.
3. Server and device racing on one request: exactly one processes it. A device ignores a request younger than 2 minutes; a function finding the doc already `PROCESSING` and not stale does nothing. Tests in Tasks 3 and 6.
4. A first name containing HTML-like text reaches the e-mail escaped, and a missing `portalMail/default` falls back to the neutral template. Test in Task 7.
5. A portal page holding a schema 1 document (not yet republished) still renders, without rank. Check in Task 8.

---

### Task 1: Benefit timeline builder (Kotlin)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalTimeline.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalTimelineBuilderTest.kt`

**Interfaces:**
- Consumes: `BenefitCalculator.calculateVolunteerBenefitStatusFromVolunteerJobs(volunteer, jobs, configs, now, offsetHours)`, `VolunteerActivityManager.isVolunteerActive(volunteer, jobs)`, `Benefit`, `Benefit.isNovaMeetingOnlyStylePerk()`.
- Produces:
  - `data class PortalTimelineStep(val from: Long, val active: Boolean, val summary: Benefit?, val benefits: List<Benefit>, val uniqueEntriesRemaining: Int?)`
  - `data class PortalShiftGains(val jobNanoId: String, val gains: List<Benefit>)`
  - `data class PortalTimeline(val steps: List<PortalTimelineStep>, val gains: List<PortalShiftGains>)`
  - `object PortalTimelineBuilder { const val HORIZON_DAYS = 30; const val MAX_UPCOMING = 10; fun build(volunteer: Volunteer, jobs: List<Job>, configs: List<JobTypeConfig>, now: Long, offsetHours: Int): PortalTimeline }`

- [ ] **Step 1: Write the failing tests** in `PortalTimelineBuilderTest`:
  - `firstStepStartsAtLocalMidnight`: `now` = 2026-11-10 15:00 UTC, `offsetHours = 2`; `steps.first().from` equals 2026-11-09 22:00 UTC.
  - `identicalConsecutiveStepsAreMerged`: volunteer with no jobs gives exactly 1 step.
  - `upcomingShiftCreatesStepAndGains`: one shift tomorrow that grants free entry; a step starts the local day after the shift, and `gains.single().gains` contains a benefit with `freeEntry == true`.
  - `shiftAt2300LocalCountsForThatDay` with `offsetHours = 2`: gains step `from` is the following local midnight.
  - `expiryCreatesStep`: a past shift whose benefit `validUntil` falls in 5 days; there is a step with `from == validUntil + 1` and that step's `benefits` no longer contains it.
  - `shiftBeyondHorizonStillGetsGains`: shift in 45 days gets non-empty gains.
  - `atMostTenUpcomingShiftsGetGains`: 12 upcoming shifts produce 10 `PortalShiftGains`.
  - `alreadyHeldBenefitIsNotAGain`: same benefit with the same `validUntil` before and after gives empty gains; a later `validUntil` counts as a gain.

- [ ] **Step 2: Run tests, expect FAIL (unresolved reference)**
  Run: `./gradlew :shared:desktopTest --tests '*PortalTimelineBuilderTest*'`

- [ ] **Step 3: Implement `PortalTimelineBuilder.build`**
  Points = local midnight of today, each local midnight up to `HORIZON_DAYS`, local midnight after each of the first `MAX_UPCOMING` upcoming shifts, `validUntil + 1` for every benefit seen within the horizon. Sort, dedupe, compute status at each point with jobs filtered to `date <= point`, apply the same filters as `PortalProjectionBuilder` today (`isActive || futureEventEntriesRemaining > 0`, drop Nova meeting perks for ORION). Merge consecutive steps whose `(active, summary, benefits, uniqueEntriesRemaining)` are equal. Gains compare the step in force at the shift's local midnight with the step just before it, keyed by benefit content without `validUntil`.

- [ ] **Step 4: Run tests, expect PASS** (same command).

- [ ] **Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalTimeline.kt shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalTimelineBuilderTest.kt
git commit -m "Add 30-day benefit timeline for the volunteer portal"
```

### Task 2: Projection schema 2

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalProjection.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalProjectionBuilderTest.kt`

**Interfaces:**
- Consumes: `PortalTimelineBuilder.build` (Task 1).
- Produces: `PortalProjection.SCHEMA = 2`. Payload keys: `firstName, lastName, email, phone, dateOfBirth, gender, hasPhoto, photoVersion, shifts, lastShiftFloor, timeline, wallet, qrEmailAllowed, mustSetPassword, association`. Each shift: `{ date, jobTypeName, venueName, shiftTime, gains? }` (`gains` only on upcoming shifts, omitted when empty). Each benefit JSON: same as today minus `rank`. Each timeline step: `{ from, active, summary, benefits, uniqueEntriesRemaining }`. Task 6 and Task 8 read exactly these keys.

- [ ] **Step 1: Update and add tests**
  - `payloadHasNoRank`: payload string contains neither `"rank"` nor any `VolunteerRank` name.
  - `shiftsHoldPastAndUpcoming`: 3 past + 2 upcoming jobs give 5 entries in `shifts`; only upcoming ones may carry `gains`.
  - `pastShiftsCappedAt60AndUpcomingAt10`.
  - `hashStableWithinOneDay`: build at 09:00 and 21:00 the same local day with the same data; equal `contentHash`.
  - `hashChangesNextDay` only when the timeline differs: no jobs at all gives the same hash on the next day too.
  - Keep existing tests on wallet, id/NFC/admin absence; drop rank assertions.

- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*PortalProjectionBuilderTest*'`

- [ ] **Step 3: Implement** the schema above in `PortalProjectionBuilder.build`. `lastShiftFloor = volunteer.lastShiftDate ?: 0`. Remove `rank`, `active`, `totalShifts`, `lastShiftAt`, `shiftsTotal`, `upcomingShifts`, `benefits`, `summary`, `uniqueEntriesRemaining` from the top level (they move into `shifts` and `timeline`).

- [ ] **Step 4: Run, expect PASS**, then the whole portal suite: `./gradlew :shared:desktopTest --tests 'com.eventmanager.app.portal.*'`

- [ ] **Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalProjection.kt shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalProjectionBuilderTest.kt
git commit -m "Publish portal schema 2 with timeline and without rank"
```

### Task 3: Device fallback delay and mail template publishing

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalMailTemplate.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt` (`processSignups`, `processRequests`, `publishAll`, `VolunteerPortalHost`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/viewmodel/EventManagerViewModel.kt` (host implementation)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/VolunteerPortalServiceTest.kt`

**Interfaces:**
- Consumes: `QrEmailHtmlBuilder.build(QrEmailHtmlOptions)`, `QrEmailHtmlBuilder.buildPlainText(...)`, the volunteer template getters of `SettingsManager` used by `DesktopQrEmailService` (`getEmailSubject`, `getEmailHeader`, `getEmailContentBefore`, `getEmailContentAfter`, `resolveAutomaticEmailSignature`, `isEmailIncludeLogoEnabled`, `getEmailLogoUri`) with the same `Res.string` defaults as `PortalQrMailer.desktop.kt`.
- Produces:
  - `const val PORTAL_FIRST_NAME_TOKEN = "%%NOCTULIST_FIRST_NAME%%"`
  - `data class PortalMailTemplate(val subject: String, val html: String, val text: String, val logoBase64: String)` with `fun toFirestoreMap(): Map<String, Any?>` (keys `subject, html, text, logoBase64, schema = 1, lastModified`).
  - `VolunteerPortalHost.portalMailTemplate(): PortalMailTemplate?` (html rendered with `holderName = PORTAL_FIRST_NAME_TOKEN`, `useContentId = true`, `includeQr = true`, `includeDigitalWalletPass = false`; images referenced as `cid:qrcode` and `cid:logo`).
  - `VolunteerPortalService.DEVICE_GRACE_MS = 120_000L`; constants `MAIL_COLLECTION = "portalMail"`, `MAIL_DOC = "default"`.

- [ ] **Step 1: Write failing tests**
  - `freshRequestIsLeftToServer`: PENDING request with `requestedAt = now - 30_000` stays PENDING after `start`.
  - `oldRequestIsProcessedByDevice`: `requestedAt = now - 150_000` ends DONE.
  - `freshSignupIsLeftToServer` / `oldSignupIsProcessed` with `submittedAt`.
  - `mailTemplatePublishedOnceAndOnChange`: `portalMail/default` written on first publish, not rewritten when unchanged, rewritten when the subject changes.
  - `mailTemplateNeverContainsVolunteerId`.

- [ ] **Step 2: Run, expect FAIL**: `./gradlew :shared:desktopTest --tests '*VolunteerPortalServiceTest*'`

- [ ] **Step 3: Implement**: skip docs younger than `DEVICE_GRACE_MS` in `processSignups` / `processRequests` (missing timestamp counts as old); publish the mail template next to `publishBranding`, hash-keyed `"$orgId#mail"`. Implement `portalMailTemplate()` in the ViewModel host (desktop and Android share it through commonMain resources).

- [ ] **Step 4: Run, expect PASS** (same command).

- [ ] **Step 5: Commit**
```bash
git add shared/src/commonMain/kotlin/com/eventmanager/app/portal shared/src/commonMain/kotlin/com/eventmanager/app/ui/viewmodel/EventManagerViewModel.kt shared/src/commonTest/kotlin/com/eventmanager/app/portal/VolunteerPortalServiceTest.kt
git commit -m "Leave fresh portal work to the server and publish the mail template"
```

### Task 4: Rules v13 and server status in settings

**Files:**
- Modify: `firebase/firestore.rules`, copy to `shared/src/commonMain/composeResources/files/firestore.rules`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/remote/FirestorePermissionErrors.kt` (version 13)
- Modify: `firebase/tests/rules/portal.test.mjs`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/VolunteerPortalUi.kt` (settings card), `VolunteerPortalService.kt` (status listener), 7 `strings.xml`

**Interfaces:**
- Produces: `VolunteerPortalService.serverStatus: StateFlow<PortalServerStatus?>`, `data class PortalServerStatus(val version: String, val lastRunAt: Long)`, read from `orgs/{orgId}/portalServer/status` (fields `version`, `lastRunAt`). Strings `portal_server_active` ("Automatic processing is on. Last run: %1$s") and `portal_server_inactive` ("Automatic processing is not set up. A NoctuList device handles requests when it is online.").

- [ ] **Step 1: Write failing rules tests**: member reads and writes `portalMail/default`; portal user and anonymous cannot read it; member reads `portalServer/status`; nobody (member included) can write `portalServer/status`.
- [ ] **Step 2: Run, expect FAIL**: `cd firebase/tests && npm test`
- [ ] **Step 3: Implement** the two match blocks, bump version, copy rules file, add the status line under the portal toggle in the settings card.
- [ ] **Step 4: Run** `cd firebase/tests && npm test` (all pass), `python3 scripts/check-i18n.py` (PASSED), `./gradlew :shared:compileKotlinDesktop` (success).
- [ ] **Step 5: Commit**
```bash
git add firebase/firestore.rules firebase/tests/rules/portal.test.mjs shared/src/commonMain
git commit -m "Rules v13 for portal mail and server status"
```

### Task 5: Functions package and shared validation

**Files:**
- Create: `webform/functions/package.json`, `webform/functions/tsconfig.json`, `webform/functions/build.mjs`, `webform/functions/.gitignore` (`node_modules`, `.secret.local`)
- Create: `webform/functions/src/validate.ts`, `webform/functions/test/validate.test.mjs`, `webform/functions/test/validation-cases.json`
- Create: `shared/src/desktopTest/kotlin/com/eventmanager/app/portal/SharedValidationCasesTest.kt`
- Modify: `shared/build.gradle.kts` (`syncWebFormResources` excludes `functions/node_modules/**`, `functions/src/**`, `functions/test/**`)

**Interfaces:**
- Produces (TypeScript, `src/validate.ts`): `cleanName(raw: unknown): string | null`, `cleanPhone(raw: unknown): string | null`, `cleanEmail(raw: unknown): string | null`, `parseSignup(formId: string, data: Record<string, unknown>): SignupSubmission | null`, `parseRequest(requestId: string, data: Record<string, unknown>): PortalRequest | null`, with the same regexes as `PortalInputValidator`. Types mirror the Kotlin `SignupSubmission` and `PortalRequest` field names.
- `validation-cases.json`: `[{ "fn": "cleanName"|"cleanPhone"|"cleanEmail"|"parseSignup"|"parseRequest", "input": ..., "expect": ... }]`, at least 25 cases including `<b>x</b>`, 61-char name, `+41 79 123 45 67`, `1899-01-01`, uid with `-`, unknown lang.
- `package.json`: `"engines": { "node": "22" }`, `"main": "lib/index.js"`, scripts `build` (`node build.mjs`, esbuild bundle of `src/index.ts` to `lib/index.js`, deps external) and `test` (`node --test test/*.test.mjs`).

- [ ] **Step 1: Write the cases file and failing TS test** iterating every case against `lib/` build output.
- [ ] **Step 2: Write `SharedValidationCasesTest`** reading `../webform/functions/test/validation-cases.json` and asserting `PortalInputValidator` gives the same verdict for each case.
- [ ] **Step 3: Run, expect FAIL**: `cd webform/functions && npm install && npm run build && npm test`
- [ ] **Step 4: Implement `validate.ts`**; fix the Kotlin side only if a case reveals a real bug (note it in the commit).
- [ ] **Step 5: Run both**: `cd webform/functions && npm run build && npm test` (pass) and `./gradlew :shared:desktopTest --tests '*SharedValidationCasesTest*'` (pass).
- [ ] **Step 6: Commit** (include `webform/functions/lib/index.js`; it ships prebuilt)
```bash
git add webform/functions shared/build.gradle.kts shared/src/desktopTest
git commit -m "Add portal functions package with shared validation cases"
```

### Task 6: Signup function

**Files:**
- Create: `webform/functions/src/index.ts`, `src/common.ts`, `src/signup.ts`, `src/nanoid.ts`
- Modify: `firebase/tests/sync-config.mjs` (link `functions` to `../../webform/functions`, add `functions` emulator on port 5001), `firebase/tests/package.json` (`test` adds `functions`, new script `test:functions`)
- Create: `firebase/tests/functions/signup.test.mjs`

**Interfaces:**
- Consumes: `parseSignup` (Task 5); schema 2 payload keys (Task 2).
- Produces:
  - `common.ts`: `portalEnabled(db, orgId): Promise<boolean>`, `claim(ref, isClaimable: (d) => boolean): Promise<Record<string, unknown> | null>` (transaction, sets `status: "PROCESSING"`, `processingAt: Date.now()`, `processingDevice: "server"`), `isStale(d): boolean` (5 min), `touchStatus(db, orgId): Promise<void>` (writes `portalServer/status` `{ version, lastRunAt }`), `minimalProjection(v): { schema: 2, payload: string, contentHash: string, updatedAt: number }` (sha256 hex of payload).
  - `nanoid.ts`: `volunteerId(): string`, 21 chars from `A-Za-z0-9_-` using `crypto.randomInt`.
  - `index.ts`: `export const onSignupWritten = onDocumentWritten({ document: "orgs/{orgId}/volunteerSignupForms/{formId}", region: REGION, retry: false }, handler)` with `REGION = defineString("REGION", { default: "europe-west1" })`.
  - Volunteer doc written with exactly the keys of `GitLiveFirestoreGateway.volunteerToMap` (`id, name, lastNameAbbreviation, email, phone, dateOfBirth, gender, currentRank: null, isActive: true, lastShiftDate: null, lastModified, nfcCardUid: "", nfcCardUidHash: "", isAdmin: false, profilePhotoPath, profilePhotoUrl: "", portalEnabled: true, portalUid`).
  - Photo: copy `portalSignupPhotos/{formId}.jpg` to `orgs/{orgId}/profilePhotos/volunteers/{id}.jpg` and `orgs/{orgId}/portalPhotos/{uid}.jpg`, then delete the original.

- [ ] **Step 1: Write failing integration tests** (emulator, admin SDK seeding):
  - `newSignupCreatesVolunteerAndProjection`: volunteer doc exists with 21-char id, `portalUid` set; `volunteerPortal/{uid}` has `schema == 2` and a payload without the id; signup doc deleted.
  - `sameEmailLinksExistingVolunteer`: no new volunteer, existing one gets `portalUid`, no projection written by the server.
  - `alreadyLinkedUidCreatesNothing`.
  - `photoCopiedAndOriginalDeleted`.
  - `portalDisabledDoesNothing`: signup stays SUBMITTED.
  - `invalidSubmissionIsDropped`: doc deleted, no volunteer.
  - `processingNotStaleIsLeftAlone`: doc already PROCESSING 1 minute ago by a device stays untouched.
- [ ] **Step 2: Run, expect FAIL**: `cd firebase/tests && npm run test:functions`
- [ ] **Step 3: Implement** `signup.ts` following `VolunteerPortalService.processSignup`.
- [ ] **Step 4: Run, expect PASS**: `cd webform/functions && npm run build && cd ../../firebase/tests && npm run test:functions`
- [ ] **Step 5: Commit**
```bash
git add webform/functions firebase/tests
git commit -m "Process volunteer signups in Cloud Functions"
```

### Task 7: Request function and QR e-mail

**Files:**
- Create: `webform/functions/src/requests.ts`, `webform/functions/src/mail.ts`
- Modify: `webform/functions/src/index.ts`
- Create: `firebase/tests/functions/requests.test.mjs`

**Interfaces:**
- Consumes: `claim`, `portalEnabled`, `touchStatus` (Task 6); `parseRequest` (Task 5); `PortalMailTemplate` doc keys and `PORTAL_FIRST_NAME_TOKEN` (Task 3).
- Produces:
  - `index.ts`: `onPortalRequestWritten` on `orgs/{orgId}/portalRequests/{requestId}`, secret `GMAIL_APP_PASSWORD = defineSecret("GMAIL_APP_PASSWORD")`, param `GMAIL_USER = defineString("GMAIL_USER")`.
  - `mail.ts`: `buildQrMail(template: MailTemplate | null, firstName: string, qrPayload: string): Promise<{ subject, html, text, attachments }>` (first name HTML-escaped in `html`, raw in `text`; `qrcode` PNG 600 px as `cid:qrcode`; logo as `cid:logo` when present; fallback English template "Your volunteer QR code" when `template` is null). `sendMail(msg): Promise<boolean>` using nodemailer `service: "gmail"`; in the emulator with `PORTAL_MAIL_TRANSPORT=capture` it writes the message to `_testMail/{autoId}` instead.
  - Results `{ status: "DONE" | "REJECTED", reason, processedAt }`, reasons as the device: `INVALID, NO_ACCESS, NOT_ALLOWED, NO_EMAIL, SEND_FAILED, SAVE_FAILED, LINKED_ELSEWHERE`.
  - PROFILE also patches `volunteerPortal/{uid}` payload `firstName`, `lastName`, `phone` and recomputes `contentHash` when the doc exists.

- [ ] **Step 1: Write failing tests**:
  - `profileUpdatesVolunteerAndProjection` (name uppercased last name, `lastModified` increased).
  - `claimLinksSingleFreeMatch`, `claimTwoMatchesRejectedLinkedElsewhere`.
  - `qrMailSentToProfileAddress`: captured mail `to` equals the volunteer e-mail, has a `qrcode` attachment, subject from `portalMail/default`.
  - `qrRefusedForAdmin` (NOT_ALLOWED), `qrRefusedWithoutEmail` (NO_EMAIL).
  - `firstNameEscapedInHtml`: first name `Léa & <Co>` appears as `Léa &amp; &lt;Co&gt;`.
  - `missingTemplateUsesFallback`.
  - `serverStatusTouched`: `portalServer/status.lastRunAt` set after a request.
- [ ] **Step 2: Run, expect FAIL**: `cd firebase/tests && npm run test:functions`
- [ ] **Step 3: Implement** `requests.ts` and `mail.ts` following `VolunteerPortalService.processRequest` / `handleClaim`.
- [ ] **Step 4: Run, expect PASS** (build then `npm run test:functions`).
- [ ] **Step 5: Commit**
```bash
git add webform/functions firebase/tests
git commit -m "Process portal requests and QR e-mails in Cloud Functions"
```

### Task 8: Portal page for schema 2

**Files:**
- Modify: `webform/portal.js` (`infoTile`, `benefitSummary`, `benefitsTile`, `upcomingTile`, `historyTile`, `rankLabel` removed), `webform/portal-i18n.js`, `webform/portal.css`
- Modify: `firebase/tests/seed.mjs` (demo payload in schema 2 with one upcoming shift carrying gains and one benefit expiring tomorrow)
- Bump cache keys `portal.js?v=portal-3` and `portal.css?v=portal-3` in `webform/portal.html`.

**Interfaces:**
- Consumes: schema 2 payload keys (Task 2).
- Produces: `currentStep(payload, now): Step | null` (last step with `from <= now`, else first), `splitShifts(shifts, now): { past, upcoming }`, `lastShiftAt(payload, now)` (max of past shift dates and `lastShiftFloor`). Schema 1 docs: keep today's code path but skip rank.
- New i18n key in 5 languages: `shiftGains` ("This shift gives you:"), and the "valid until {date}" label already used for benefits.

- [ ] **Step 1: Seed** `node seed.mjs` with the new payload; start preview `npm run preview`.
- [ ] **Step 2: Implement** the functions above and rewire the tiles. Info tile rows: status (active or inactive from current step), total shifts (past count), last shift. Benefits tile: current step summary and list with description and end date, no rank badge; benefits with `validUntil < now` hidden. Upcoming tile: each shift lists its `gains` under "This shift gives you:".
- [ ] **Step 3: Verify in the browser** at `http://127.0.0.1:5002/v/demo-org`: no "rank" text anywhere (`document.body.innerText`), the upcoming shift shows its gains, the expiring benefit disappears when the page clock is moved past `validUntil` (override `Date.now` in the console and re-render), light and dark, 1440 px and 375 px, no console or CSP errors. Then load a schema 1 doc (old seed variant) and check it renders.
- [ ] **Step 4: Commit**
```bash
git add webform firebase/tests/seed.mjs
git commit -m "Show today's benefits and upcoming shift gains on the portal"
```

### Task 9: Export, deploy config and guide

**Files:**
- Modify: `webform/firebase.json` (`"functions": [{ "source": "functions", "codebase": "noctulist", "runtime": "nodejs22", "ignore": ["node_modules", "src", "test", ".secret.local"] }]`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/guestform/GuestFormSiteBundle.kt` (add `functions/package.json`, `functions/lib/index.js`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/FirebaseSetupTutorialDialog.kt` (new section after the portal section), 7 `strings.xml`
- Modify: `FIREBASE_SETUP.md`, `CHANGELOG.md`

**Interfaces:**
- Produces strings `firebase_tutorial_server_optional_title` ("Optional: automatic processing (Cloud Functions)") and `firebase_tutorial_server_optional_body` with the 5 steps of spec §10 (Blaze plus a budget alert, Gmail app password, `cd functions`, `npm install`, `cd ..`, `firebase deploy --only hosting,functions`, `firebase functions:artifacts:setpolicy`), and the region note (`eur3` to `europe-west1`, Zurich to `europe-west6`).

- [ ] **Step 1: Export check**: build the desktop app, export the form site, unzip into the scratchpad, confirm `functions/package.json` and `functions/lib/index.js` are present and `functions/node_modules` is not.
- [ ] **Step 2: Deploy dry run** from the unzipped folder against the emulator: `npx firebase-tools emulators:start --only functions,firestore --project demo-org` loads `onSignupWritten` and `onPortalRequestWritten` without errors.
- [ ] **Step 3: Strings and docs**; run `python3 scripts/check-i18n.py` (PASSED), `grep -c '—'` on the new strings (0), `./gradlew :shared:compileKotlinDesktop :shared:compileDebugKotlinAndroid` (success).
- [ ] **Step 4: Full regression**: `./gradlew :shared:desktopTest --tests 'com.eventmanager.app.portal.*'`, `cd firebase/tests && npm test && npm run test:functions`.
- [ ] **Step 5: Commit**
```bash
git add webform/firebase.json shared/src/commonMain FIREBASE_SETUP.md CHANGELOG.md
git commit -m "Ship portal functions with the form site and document the setup"
```
