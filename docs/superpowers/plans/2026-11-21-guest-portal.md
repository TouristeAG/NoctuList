# Espace invités (permanents et temporaires) : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ouvrir l'espace web `/v/{orgId}` aux invités permanents (mêmes options que les bénévoles) et aux managers de groupes d'invités temporaires (lecture seule).

**Architecture:** Socle commun généralisé à deux types de titulaires (bénévole, invité) dans `VolunteerPortalService`, Functions et `portal.js`. Une page publiée par type et par compte (`guestPortal/{uid}`, `temporaryGuestPortal/{uid}`) plus un document QR séparé pour chacune. La publication invité vit dans une classe dédiée appelée par le service.

**Tech Stack:** Kotlin Multiplatform (Room, GitLive Firestore, Compose), TypeScript Cloud Functions (Node 22, firebase-functions v6), JS vanilla (portail), règles Firestore, `@firebase/rules-unit-testing` + `node:test`.

**Spec:** `docs/superpowers/specs/2026-11-20-guest-portal-design.md`

## Global Constraints

- Travail sur `feature/guest-portal` (worktree `.claude/worktrees/guest-portal`). Git : `/usr/bin/git` (le `git` de `/usr/local/bin` ne lit pas les worktrees).
- Commits sans ligne `Co-Authored-By` ni mention de Claude (préférence du propriétaire).
- Gradle : `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Ne jamais commiter `shared/src/desktopMain/resources/*.dylib`. `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode...` échoue déjà sur `main` : ignorer.
- Room 53 → 54. Règles `NOCTULIST_RULES_VERSION = 17`, trois copies identiques : `shared/src/commonMain/composeResources/files/firestore.rules`, `firebase/firestore.rules`, `firebase/tests/firestore.rules`.
- Nouveaux champs `Guest` Firebase uniquement, jamais écrits dans Google Sheets.
- Aucun e-mail envoyé automatiquement : ouvrir un accès (invité permanent ou groupe) n'envoie rien ; l'admin clique « Envoyer un lien de réinitialisation » ou « Envoyer l'e-mail du groupe ».
- `guest.nanoId` jamais dans `guestPortal`, `temporaryGuestPortal`, l'export JSON ni les journaux ; jamais de QR pour un invité admin.
- `guestKey` = `PortalProjectionBuilder.sha256(nanoId).take(16)`.
- Fin d'un groupe temporaire : `endsAt = DateTimeUtils.getStartOfDayWithOffset(eventDate, offsetHours) + 3 * 86_400_000L` (jour de l'événement + 2 jours).
- Textes : 7 langues dans l'app (parité i18n vérifiée par test), 5 dans le portail (fr, en, es, de, it). Pas de tiret cadratin, pas de texte d'aide superflu, pas de sous-titre redondant.
- Functions : `lib/index.js` recompilé (`npm run build` dans `webform/functions`) et copié dans `shared/src/commonMain/composeResources/files/webform/functions/lib/index.js`.
- Réglages : `guest_portal_enabled` et `temp_guest_portal_enabled`, `"true"`/`"false"`, défaut `false`, tous deux sous `volunteer_portal_enabled`.

## Review Focus

1. **Une adresse, plusieurs profils** : un compte à la fois bénévole et invité permanent garde sa page bénévole intacte, sa photo bénévole, et ses deux pages ; couper l'un ne supprime pas l'autre (Task 6, test `revokingGuestKeepsVolunteerPage`).
2. **Invité temporaire sans date d'événement** (`temporaryEventDate == null`) : jamais publié, jamais de plantage (Task 5, test `guestWithoutEventDateIsSkipped`).
3. **Manager dont l'adresse a déjà un compte** (`EMAIL_EXISTS`) : aucune page publiée tant que `portalUid` est vide ; le CLAIM vérifié la publie (Task 9, test `existingAccountPublishesNothingUntilClaim`).
4. **Cas de l'adresse du groupe modifiée après l'ouverture** : les invités gardent l'ancien `portalUid` ; la fenêtre de groupe montre l'accès « ouvert pour <ancienne adresse> » et propose de couper puis rouvrir (Task 9, test `contactEmailChangeKeepsUidAndFlagsMismatch`).
5. **Invités temporaires avec entrée utilisée ou admin** : listés, mais absents du document QR (Task 5, test `qrExcludesAdminAndUsedEntries`).

---

### Task 0: Reprendre « ouvrir l'accès sans e-mail » des bénévoles

Prérequis de la spec §14. Ce changement est non commité dans le dossier principal (`feature/setup-hardening`), mêlé à d'autres. **Demander l'accord du propriétaire avant de le reprendre.**

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt` (`PortalGrantResult`, `grantAccess`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/VolunteerPortalUi.kt` (fenêtre de résultat avec bouton « Envoyer un lien de réinitialisation »)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/viewmodel/EventManagerViewModel.kt` (`grantVolunteerPortalAccess(volunteer)` sans `languageCode`, `addVolunteerWithPortalAccess`)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/VolunteerPortalServiceTest.kt`

**Interfaces:**
- Produces: `sealed class PortalGrantResult { data class Created(val password: String); data object ExistingAccount; data class Failed(val code: String) }` ; `suspend fun grantAccess(volunteer: Volunteer): PortalGrantResult` (garde l'appel `applyGoogleAddressRule(volunteer.id)` de `main`).

- [ ] **Step 1:** `cd "/Users/leonardomondada/Documents/DEV/NoctuList PC Edition" && /usr/bin/git diff -- shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/VolunteerPortalUi.kt shared/src/commonTest/kotlin/com/eventmanager/app/portal/VolunteerPortalServiceTest.kt > <scratchpad>/no-mail.patch` ; lire le patch en entier et ne garder que les morceaux « sans e-mail ».
- [ ] **Step 2:** Appliquer à la main sur la branche (le patch part d'une base plus ancienne que `main` : conserver `applyGoogleAddressRule`).
- [ ] **Step 3:** Test `grantAccessSendsNoMail` : un `PortalAuthApi` factice compte les appels à `sendPasswordReset` ; après `grantAccess` (créé et `EMAIL_EXISTS`), `assertEquals(0, fake.resetCalls)`.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*VolunteerPortalServiceTest*'` → PASS.
- [ ] **Step 5:** Commit `Open volunteer portal access without sending an e-mail`.

### Task 1: Champs portail sur `Guest`, migration et synchro

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/models/Models.kt` (`Guest`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/database/EventManagerDatabase.kt` (`version = 54`, `MIGRATION_53_54`, enregistrement dans la liste)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/remote/GitLiveFirestoreGateway.kt` (`guestToMap`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/remote/FirestoreChangeApplier.kt` (décodage invité)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/data/remote/FirestoreCodecAndRulesShapeTest.kt`, test Sheets existant des invités

**Interfaces:**
- Produces: `Guest.portalEnabled: Boolean = false`, `portalUid: String = ""`, `portalGoogleEmail: String = ""`, `portalGoogleUid: String = ""`, `portalGoogleLinkedBy: String = ""`.

- [ ] **Step 1:** Test `guestPortalFieldsRoundTrip` : `guestToMap` puis décodage par l'applier conserve les cinq champs ; test `guestPortalFieldsNeverInSheetsRow` : la ligne Sheets d'un invité ne contient aucune de ces valeurs.
- [ ] **Step 2:** Lancer, vérifier l'échec (champs inconnus).
- [ ] **Step 3:** Ajouter les champs, `MIGRATION_53_54` (5 × `ALTER TABLE guests ADD COLUMN … TEXT/INTEGER NOT NULL DEFAULT ''/0`, même style de journalisation que 52→53), codec et applier (mêmes clés que les bénévoles).
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*FirestoreCodec*' --tests '*Sheets*Guest*'` → PASS ; `./gradlew :shared:compileKotlinDesktop :app:compileDebugKotlin` → OK.
- [ ] **Step 5:** Commit `Add portal fields to guests`.

### Task 2: Réglages des deux espaces

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/InstitutionSettings.kt` (deux clés, ajoutées aux deux listes où figurent les clés `VOLUNTEER_PORTAL_*`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/SettingsManager.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/viewmodel/EventManagerViewModel.kt` (flows + setters à côté de `setVolunteerPortalAutoAccess`, membres `volunteerPortalHost`)
- Modify: la carte « Plateforme bénévole » (fichier qui contient les coches `setVolunteerPortalQrEnabled`)
- Modify: `composeResources/values*/strings.xml` (7 langues)

**Interfaces:**
- Produces: `InstitutionSettingsKeys.GUEST_PORTAL_ENABLED = "guest_portal_enabled"`, `TEMP_GUEST_PORTAL_ENABLED = "temp_guest_portal_enabled"` ; `SettingsManager.isGuestPortalEnabled()/setGuestPortalEnabled(Boolean)`, `isTemporaryGuestPortalEnabled()/setTemporaryGuestPortalEnabled(Boolean)` ; ViewModel `guestPortalEnabled: StateFlow<Boolean>`, `temporaryGuestPortalEnabled: StateFlow<Boolean>`, `setGuestPortalEnabled`, `setTemporaryGuestPortalEnabled` (publient le réglage puis `republishVolunteerPortal()`) ; `VolunteerPortalHost.guestPortalEnabled(): Boolean = false`, `temporaryGuestPortalEnabled(): Boolean = false`, `temporaryGuestCreditsEnabled(): Boolean = false`.
- Chaînes : `portal_guest_space_enabled` « Espace des invités permanents », `portal_temp_guest_space_enabled` « Espace des invités temporaires ».

- [ ] **Step 1:** Test dans le test des réglages institution existant : les deux clés sont synchronisées, défaut `false`.
- [ ] **Step 2:** Implémenter ; la seconde coche n'apparaît que si `isTemporaryGuestFeaturesEnabled()`, les deux seulement si la plateforme est active.
- [ ] **Step 3:** `./gradlew :shared:desktopTest --tests '*InstitutionSettings*' --tests '*I18n*'` → PASS.
- [ ] **Step 4:** Commit `Add guest space settings`.

### Task 3: Règles Firestore v17

**Files:**
- Modify: les trois copies de `firestore.rules`
- Create: `firebase/tests/rules/guest-portal.test.mjs`
- Modify: `firebase/tests/rules/portal.test.mjs` (demandes `GUEST_*`, inscription `GUEST`)

Contenu : spec §9, à la lettre. Détails décidés :
- `function settingOn(orgId, key) { let p = settingPath(orgId, key); return exists(p) && get(p).data.get('value', '') in ['true', true]; }` ; `guestPortalOn = portalOn && settingOn('guest_portal_enabled')`, `tempGuestPortalOn = portalOn && settingOn('temp_guest_portal_enabled')`.
- `temporaryGuestPortalQr` : `get(/…/temporaryGuestPortal/$(uid)).data.expiresAtMillis > request.time.toMillis()`.
- `validPortalRequest` : `GUEST_PROFILE` mêmes clés que `PROFILE`, `GUEST_QR_EMAIL` mêmes clés que `QR_EMAIL`, les deux exigent `guestPortalOn(orgId)` ; `portalRequestDelayMinutes` renvoie 10 pour `QR_EMAIL` et `GUEST_QR_EMAIL`.
- `validSignupSubmission` : si `resource.data.get('holderType', 'VOLUNTEER') == 'GUEST'`, `guestPortalOn(orgId)` et clés modifiables sans `dateOfBirth`, `gender` ; sinon inchangé.

- [ ] **Step 1:** Écrire `guest-portal.test.mjs` : pour `guestPortal`, `guestPortalQr`, `temporaryGuestPortal`, `temporaryGuestPortalQr` : `get` titulaire OK ; autre uid, réglage coupé, plateforme coupée, non connecté, `list`, `set` web refusés ; session mot de passe **non vérifiée** acceptée sur les deux collections temporaires et refusée sur `guestPortal` ; `temporaryGuestPortal` avec `expiresAtMillis` passé refusé ; Google lié (alias) accepté sur `guestPortal`. Ajouter dans `portal.test.mjs` : `GUEST_PROFILE` accepté seulement si l'espace invités est actif, throttle 10 min de `GUEST_QR_EMAIL`, formulaire `holderType: 'GUEST'` soumis sans date de naissance accepté, avec espace invités coupé refusé, `holderType` modifié par le public refusé.
- [ ] **Step 2:** `cd firebase/tests && npm run test:rules` (ou `npm test`) → les nouveaux cas échouent.
- [ ] **Step 3:** Écrire les règles, passer la version à 17 dans les trois copies.
- [ ] **Step 4:** `npm test` → PASS ; `./gradlew :shared:desktopTest --tests '*FirestoreCodecAndRulesShapeTest*'` → PASS (copie et version).
- [ ] **Step 5:** Lire `storage.rules` : l'upload `portalPhotoUploads/{uid}.jpg` doit accepter un titulaire invité permanent (session mot de passe vérifiée ou Google lié) quand l'espace invités est actif ; sinon ajouter le cas et un test dans `firebase/tests/rules` (limite : 2 lectures Firestore par règle Storage).
- [ ] **Step 6:** Commit `Firestore rules v17: guest spaces`.

### Task 4: Page et QR des invités permanents (purs)

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalProjection.kt`
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/GuestPortalProjection.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalQrDocument.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/GuestPortalProjectionBuilderTest.kt`, `PortalQrDocumentTest.kt`

**Interfaces:**
- Produces:
  - `PortalProjection(payload, contentHash, schema: Int = SCHEMA, extraFields: Map<String, Any?> = emptyMap())`, `toFirestoreMap` écrit `schema` et fusionne `extraFields`.
  - `internal fun PortalProjectionBuilder.walletJson(transfers: List<AccountTransfer>, holderType: AccountHolderType, holderId: String, currencyCode: String): JsonElement` (extraction ; le bénévole l'appelle avec `VOLUNTEER, volunteer.id`).
  - `data class GuestPortalProjectionInput(guest: Guest, transfers: List<AccountTransfer>, currencyCode: String, associationName: String, hasPhoto: Boolean, photoVersion: String, googleLinkAllowed: Boolean)`.
  - `object GuestPortalProjectionBuilder { const val COLLECTION = "guestPortal"; const val SCHEMA = 1; fun build(input): PortalProjection }` ; clés du payload : `firstName, lastName, email, phone, hasPhoto, photoVersion, venueName, invitations, barDiscountPercent, wallet, qrEmailAllowed, mustSetPassword(false), association, googleEmail, googleLinkedBy, googleLinkAvailable`.
  - `object GuestPortalQrDocument { const val COLLECTION = "guestPortalQr"; fun build(guest: Guest, enabled: Boolean): Map<String, Any>? }` (même forme que `PortalQrDocument`).

- [ ] **Step 1:** Tests : `payloadHasNoNanoIdNotesNfcOrAdmin` (le payload ne contient ni `nanoId`, ni `notes`, ni `nfcCardUid`, ni `nfcCardUidHash`, ni `isAdmin`) ; `barDiscountClampedTo100` (150 → 100) ; `walletOnlyCountsThisGuest` (transferts GUEST du même id, pas ceux d'un bénévole de même id) ; `qrEmailRefusedForAdmin` ; `qrDocumentNullForAdminOrDisabledOrUnlinked` ; `volunteerProjectionUnchanged` (hash d'un bénévole de référence identique avant et après l'extraction de `walletJson`).
- [ ] **Step 2:** Lancer → échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*PortalProjectionBuilderTest*' --tests '*GuestPortalProjectionBuilderTest*' --tests '*PortalQrDocumentTest*'` → PASS.
- [ ] **Step 5:** Commit `Build the permanent guest portal page`.

### Task 5: Page et QR du manager (purs)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/TemporaryGuestPortalProjection.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/TemporaryGuestPortalProjectionBuilderTest.kt`

**Interfaces:**
- Produces:
  - `data class TemporaryGuestPortalInput(guests: List<Guest> /* temporaires liés à ce uid */, transfers: List<AccountTransfer>, creditsEnabled: Boolean, currencyCode: String, associationName: String, now: Long, offsetHours: Int)`.
  - `object TemporaryGuestPortalProjectionBuilder { const val COLLECTION = "temporaryGuestPortal"; const val SCHEMA = 1; fun build(input): PortalProjection? }` : `null` quand aucun groupe n'est en cours ; `extraFields = mapOf("expiresAtMillis" to max(endsAt))`. Payload : `association, contactEmail, groups[{eventDate, venueName (temporaryVenueName ou venueName), artistName, endsAt, guests[{guestKey, firstName, lastName, emergencyPhone, entryUsed, qr, wallet: {balance, currency} | null}]}]`. Groupes = `temporaryBatchKey()`, triés par `eventDate` ; invités par nom ; `contactEmail` = `temporaryContactEmail` du groupe le plus récent.
  - `object TemporaryGuestPortalQrDocument { const val COLLECTION = "temporaryGuestPortalQr"; fun build(guests: List<Guest>, enabled: Boolean): Map<String, Any>? }` → `{schema: 1, items: {guestKey: {payload: nanoId, name}}}`, `null` si vide ou désactivé.

- [ ] **Step 1:** Tests : `groupsSortedAndEndedOnesDropped` (groupe échu à `now` absent, `expiresAtMillis` = fin du dernier) ; `returnsNullWhenAllGroupsEnded` ; `guestWithoutEventDateIsSkipped` ; `walletOnlyWhenCreditsOnAndNonZero` (crédits off → `null` ; solde 0 → `null` ; 12.5 → `{balance: 12.5}`) ; `payloadHasNoNanoId` ; `qrExcludesAdminAndUsedEntries` (et `qr: false` dans le payload pour eux) ; `hashStableWithinADay` (deux `now` le même jour local → même `contentHash`).
- [ ] **Step 2:** Lancer → échec.
- [ ] **Step 3:** Implémenter (même `walletJson` que Task 4 pour le solde, sans la liste des transferts).
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*TemporaryGuestPortalProjectionBuilderTest*'` → PASS.
- [ ] **Step 5:** Commit `Build the temporary guest group page`.

### Task 6: Publication des pages invités

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/GuestPortalPublisher.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt` (`VolunteerPortalHost`, `start`, `Snapshot`, `publishAll`, `unpublishAll`, boucle de reprise)
- Modify: `EventManagerViewModel.kt` (`volunteerPortalHost`, appel `start`)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/GuestPortalPublisherTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 4, 5.
- Produces:
  - Host : `suspend fun guests(): List<Guest>`, `suspend fun saveGuest(guest: Guest): Boolean`, `suspend fun createGuest(guest: Guest, photo: ByteArray?): Guest?`, `suspend fun guestPhotoBytes(guest: Guest): ByteArray?`, `suspend fun sendGuestQrEmail(guest: Guest): Boolean` (implémentations par défaut `emptyList()/false/null` pour les tests existants).
  - `fun start(volunteers, guests: Flow<List<Guest>>, jobs, transfers, jobTypeConfigs)`.
  - `internal class GuestPortalPublisher(orgId, gateway, host, clock) { suspend fun publish(guests: List<Guest>, volunteers: List<Volunteer>, transfers: List<AccountTransfer>, hashes: MutableMap<String, String>, googleLinkAllowed: Boolean, now: Long); suspend fun unpublishAll(hashes) }`.
  - Clés de hash : `"$orgId/$uid#guest"`, `"#guestQr"`, `"#temp"`, `"#tempQr"` ; photo invité sous la clé partagée `"$orgId/$uid#photo"`, seulement si aucun bénévole lié n'a ce `uid`.
  - Republication quotidienne : la boucle de reprise appelle `republish()` quand le jour local a changé depuis la dernière publication.

- [ ] **Step 1:** Tests (gateway factice existant de `VolunteerPortalServiceTest`) : `publishesGuestPageAndQr` ; `guestSpaceOffDeletesGuestPages` ; `tempSpaceOffDeletesTempPages` ; `revokingGuestKeepsVolunteerPage` (même `uid` bénévole + invité ; invité coupé → `volunteerPortal/{uid}` intact, `guestPortal/{uid}` supprimé, photo bénévole conservée) ; `guestOnlyAccountPublishesGuestPhoto` ; `tempGroupEndedDeletesPage` (horloge avancée au-delà de `endsAt`) ; `unchangedGuestNotRewritten` ; `volunteerStaleCleanupIgnoresGuestKeys`.
- [ ] **Step 2:** Lancer → échec.
- [ ] **Step 3:** Implémenter ; seuls les invités `firebaseOrgId` vide ou égal à l'org ; permanents = `!isTemporaryGuest && !isVolunteerBenefit && portalEnabled && portalUid != ""` (doublons sur un même uid : le `lastModified` le plus récent) ; temporaires groupés par `portalUid`.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*Portal*'` → PASS.
- [ ] **Step 5:** Commit `Publish guest portal pages`.

### Task 7: Titulaires communs, demandes invité et CLAIM généralisé (appareil)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalHolder.kt`
- Modify: `PortalDocuments.kt` (`PortalRequestType`), `VolunteerPortalService.kt` (`processRequest`, `updateProfile`, `handleClaim`), `PortalGoogleLinkProcessor.kt`
- Test: `VolunteerPortalServiceTest.kt`, `PortalGoogleLinkProcessorTest.kt`, `PortalInputValidatorTest.kt`

**Interfaces:**
- Produces:
  - `enum class PortalHolderKind { VOLUNTEER, GUEST }` ; `data class PortalHolder(kind, id: String, email: String, portalEnabled: Boolean, portalUid: String, portalGoogleEmail: String, portalGoogleUid: String, portalGoogleLinkedBy: String, isAdmin: Boolean)` ; `fun Volunteer.asPortalHolder()`, `fun Guest.asPortalHolder()` (permanents ; e-mail = `email`).
  - `PortalRequestType` + `GUEST_PROFILE`, `GUEST_QR_EMAIL`.
  - `PortalGoogleLinkProcessor` travaille sur `List<PortalHolder>` ; `GoogleLinkDecision.Link(val holder: PortalHolder, val linkedBy: String)`. Plusieurs titulaires partageant un `portalUid` non vide comptent pour une personne (même verdict que s'il n'y en avait qu'un) ; le lien Google est écrit sur chacun d'eux.

- [ ] **Step 1:** Tests : tous les tests Google existants passent inchangés une fois convertis en `PortalHolder` ; `adminMatchFindsGuest` ; `volunteerAndGuestSameUidAreOnePerson` ; `guestProfileUpdatesGuest` (`name`, `lastNameAbbreviation` en majuscules, `phoneNumber`) ; `guestQrEmailRefusedForAdmin` ; `guestRequestsIgnoredWhenGuestSpaceOff` ; `claimLinksVolunteerGuestAndTempGroup` (même adresse : bénévole `email`, invité `email`, groupe `temporaryContactEmail`, tous `portalEnabled` et `portalUid` vide → tous liés) ; `claimRejectedWhenAnotherUidOwnsAddress`.
- [ ] **Step 2:** Lancer → échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*Portal*'` → PASS.
- [ ] **Step 5:** Commit `Share portal requests and linking between volunteers and guests`.

### Task 8: Accès des invités permanents (admin, inscription, automatique)

**Files:**
- Modify: `VolunteerPortalService.kt` (accès, inscription), `PortalDocuments.kt` (`VolunteerSignupForm`, `PortalInputValidator.parseSignup`), `EventManagerViewModel.kt`
- Test: `VolunteerPortalServiceTest.kt`, `PortalInputValidatorTest.kt`

**Interfaces:**
- Produces:
  - `VolunteerSignupForm(… , holderType: PortalHolderKind = VOLUNTEER, venueName: String = "")` ; `toFirestoreMap` écrit `holderType` et `venueName`.
  - `suspend fun createSignupForm(holderType: PortalHolderKind = VOLUNTEER, venueName: String = ""): VolunteerSignupForm?`.
  - `parseSignup` : pour `GUEST`, date de naissance et genre ni requis ni lus.
  - `suspend fun grantGuestAccess(guest: Guest): PortalGrantResult`, `sendGuestPasswordReset(guest, languageCode): Boolean`, `revokeGuestAccess(guest): Boolean`, `assignGuestGoogleAccount(guest, email): String?`, `removeGuestGoogleAccount(guest): Boolean`, `forgetGuest(guest)` (même corps logique que les versions bénévoles, factorisées sur `PortalHolder` là où c'est possible).
  - ViewModel : `grantGuestPortalAccess`, `addGuestWithPortalAccess`, `sendGuestPortalPasswordReset`, `revokeGuestPortalAccess`, `assignGuestPortalGoogleAccount`, `removeGuestPortalGoogleAccount`, `createGuestSignupForm(venueName)`. Accès automatique : à l'ajout d'un invité permanent avec e-mail, si `volunteer_portal_auto_access` et l'espace invités actifs, `grantGuestAccess` sans e-mail. Règle d'adresse Google appliquée à l'ouverture comme pour les bénévoles.
- Inscription `GUEST` : liaison à un invité permanent sans compte de même e-mail, sinon création (`invitations = 0`, `barDiscountPercent = 0`, `venueName` du formulaire, `isTemporaryGuest = false`) ; photo importée comme pour un bénévole.

- [ ] **Step 1:** Tests : `grantGuestAccessSendsNoMail` ; `guestSignupCreatesPermanentGuestWithFormVenue` ; `guestSignupLinksExistingGuestSameEmail` ; `guestSignupIgnoredWhenGuestSpaceOff` ; `volunteerSignupUnchanged` ; `parseGuestSignupWithoutBirthDate`.
- [ ] **Step 2:** Lancer → échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*Portal*'` → PASS.
- [ ] **Step 5:** Commit `Open the portal to permanent guests`.

### Task 9: Ouvrir et couper l'espace d'un groupe temporaire

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/TemporaryGuestPortalAccess.kt`
- Modify: `EventManagerViewModel.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/TemporaryGuestPortalAccessTest.kt`

**Interfaces:**
- Consumes: `PortalAuthApi`, `PortalPasswordGenerator`, host `guests()/volunteers()/saveGuest`, `VolunteerPortalService.republish()`.
- Produces:
  - `sealed class TemporaryGroupAccessResult { data class Created(val uid: String, val password: String); data class Reused(val uid: String); data object ExistingAccount; data class Failed(val code: String) }`.
  - `class TemporaryGuestPortalAccess(host: VolunteerPortalHost, authClientFactory: (String) -> PortalAuthApi, clock: () -> Long) { suspend fun open(batch: List<Guest>): TemporaryGroupAccessResult; suspend fun close(batch: List<Guest>): Boolean; fun openedFor(batch: List<Guest>): String? /* adresse du compte si ouvert */ }`.
  - Ordre de `open` : uid connu (invité ou bénévole déjà synchronisé, `portalUid` non vide, même adresse) → `Reused` ; sinon `createAccount` → `Created` ; `EMAIL_EXISTS` → `ExistingAccount` avec `portalUid = ""`. Puis tous les invités du groupe `portalEnabled = true`, `portalUid`, puis `republish()`. Aucun e-mail.
  - ViewModel : `openTemporaryGroupPortal(guest): TemporaryGroupAccessResult`, `closeTemporaryGroupPortal(guest): Boolean`, `sendTemporaryGroupPasswordReset(guest, languageCode): Boolean`.

- [ ] **Step 1:** Tests : `openCreatesAccountAndLinksWholeBatch` ; `openReusesKnownUid` (aucun appel `createAccount`) ; `existingAccountPublishesNothingUntilClaim` ; `openSendsNoMail` ; `closeClearsBatchOnly` (autre groupe du même manager intact) ; `contactEmailChangeKeepsUidAndFlagsMismatch` (`openedFor` renvoie l'ancienne adresse) ; `openRefusedWithoutContactEmailOrSpaceOff`.
- [ ] **Step 2:** Lancer → échec.
- [ ] **Step 3:** Implémenter ; l'adresse d'origine de l'ouverture est déduite du compte (premier titulaire lié à ce uid) pour `openedFor`.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*TemporaryGuestPortalAccessTest*'` → PASS.
- [ ] **Step 5:** Commit `Open and close a temporary group space`.

### Task 10: Interface de l'app

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/VolunteerPortalUi.kt` (rendre `VolunteerPortalAccessCard` et la fenêtre de mot de passe génériques sur `PortalHolder` + callbacks, ou ajouter `GuestPortalAccessCard` qui les réutilise)
- Modify: `shared/src/desktopMain/kotlin/com/eventmanager/app/ui/components/GuestDetailPanel.desktop.kt`, `shared/src/androidMain/kotlin/com/eventmanager/app/ui/components/GuestDetailPanel.kt`
- Modify: dialogue d'ajout d'invité (choix « Envoyer un formulaire d'inscription » avec sélection du lieu)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/TemporaryGuestSendChoiceDialog.kt` et les deux panneaux d'envoi (bloc compte dans l'e-mail du groupe)
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/TemporaryGroupPortalDialog.kt`
- Modify: générateur de l'e-mail temporaire (`QrEmailHtmlBuilder` ou le constructeur de l'e-mail de groupe) : paramètre `portalAccount: PortalAccountBlock?`
- Modify: `strings.xml` × 7

**Interfaces:**
- Produces: `data class PortalAccountBlock(val portalUrl: String, val loginEmail: String, val password: String?)` (mot de passe `null` → phrase « ton mot de passe habituel »).
- Fiche invité permanent : carte « Accès plateforme » visible si l'espace invités est actif, mêmes actions que le bénévole. Fiche invité temporaire : bouton « Ouvrir l'espace du groupe » / « Couper l'accès à l'espace » ; `TemporaryGroupPortalDialog` affiche le résultat (mot de passe une fois, Copier, « Envoyer l'e-mail du groupe », « Envoyer un lien de réinitialisation »). L'envoi depuis cette fenêtre passe le mot de passe dans `PortalAccountBlock` ; depuis la liste, `password = null` dès que `openedFor` n'est pas nul.

- [ ] **Step 1:** Test pur `groupMailContainsAccountBlock` sur le générateur : avec mot de passe, sans mot de passe, sans bloc (espace coupé) ; aucune donnée d'un autre groupe.
- [ ] **Step 2:** Implémenter l'UI et les chaînes (7 langues).
- [ ] **Step 3:** `./gradlew :shared:desktopTest :shared:compileKotlinDesktop :app:compileDebugKotlin` → OK (parité i18n comprise).
- [ ] **Step 4:** Lancer l'app desktop contre les émulateurs (`firebase/tests/emulators.sh`, seed) : ouvrir l'espace d'un groupe, vérifier qu'aucun e-mail ne part, copier le mot de passe ; ouvrir l'accès d'un invité permanent.
- [ ] **Step 5:** Commit `Guest portal controls in the app`.

### Task 11: Cloud Functions

**Files:**
- Modify: `webform/functions/src/validate.ts`, `signup.ts`, `requests.ts`, `google.ts`, `common.ts`
- Test: `firebase/tests/functions/signup.test.mjs`, `requests.test.mjs`, `google.test.mjs`

**Interfaces:**
- `GoogleVolunteer` devient `PortalHolderDoc { kind: "VOLUNTEER" | "GUEST"; id; portalEnabled; portalUid; portalGoogleEmail; portalGoogleUid; portalGoogleLinkedBy }` ; chargé depuis `volunteers` et depuis `guests` (permanents seulement : `isTemporaryGuest != true`, `isVolunteerBenefit != true`) quand `guest_portal_enabled`. Même règle « un uid = une personne » que Task 7.
- `parseRequest` accepte `GUEST_PROFILE`, `GUEST_QR_EMAIL` ; `requests.ts` les traite sur `guests/{id}` (champs `name`, `lastNameAbbreviation`, `phoneNumber`, `lastModified`, `sourceDeviceId: "server"` si ce champ existe sur la branche) puis corrige `guestPortal/{uid}` et recalcule `contentHash` comme pour `PROFILE`.
- CLAIM : mêmes cibles que Task 7 (bénévoles, invités permanents par `email`, temporaires par `temporaryContactEmail`).
- `signup.ts` : `holderType == "GUEST"` → crée ou lie `guests/{nanoId}` (champs du codec `guestToMap`, `nanoId` NanoID 21 car.), publie `guestPortal/{uid}` minimal (schema 1, même payload que Task 4 avec `wallet` à 0) et `guestPortalQr/{uid}` si le réglage QR est actif.

- [ ] **Step 1:** Tests : `guest signup creates a permanent guest`, `guest signup links an existing guest`, `guest signup ignored when guest space off`, `GUEST_PROFILE updates the guest and its page`, `GUEST_QR_EMAIL refused for admin guest`, `CLAIM links volunteer, guest and temporary group`, `ADMIN_MATCH links a guest`, `volunteer flows unchanged` (suite existante verte).
- [ ] **Step 2:** `cd firebase/tests && npm run test:functions` → échec.
- [ ] **Step 3:** Implémenter ; `cd webform/functions && npm run build` ; copier `lib/index.js` dans `composeResources`.
- [ ] **Step 4:** `npm run test:functions` → PASS.
- [ ] **Step 5:** Commit `Functions: guest signups, requests and linking`.

### Task 12: Portail web

**Files:**
- Modify: `webform/portal.js`, `webform/portal.css`, `webform/portal-i18n.js`, `webform/portal-sw.js` (version de cache), `webform/signup.js`, `webform/signup.html` si besoin
- Modify: `firebase/tests/seed.mjs` (invité permanent lié, manager à deux groupes dont un avec entrée utilisée, compte à trois profils)
- Copies `composeResources/files/webform/*` (même mécanisme que pour les fichiers existants)

**Interfaces:**
- `portal.js` : après connexion, `loadProfiles(uid)` lit en parallèle `volunteerPortal`, `guestPortal`, `temporaryGuestPortal` (+ QR correspondants) ; `permission-denied` ou absence = profil absent. Session non vérifiée : seule la lecture temporaire est tentée, sinon écran de vérification actuel. Sélecteur (« Bénévole », « Invité », « Mes groupes ») quand plusieurs profils, dernier choix dans `localStorage` `noctulist.portalProfile.<orgId>`.
- Rendu invité : carte profil (bouton Modifier → `GUEST_PROFILE`), carte avantages (rabais, invitations, lieu), porte-monnaie, QR, export JSON.
- Rendu temporaire : en-tête avec l'e-mail du groupe, groupes par date, invités (nom, contact d'urgence, solde éventuel, « QR » ou « Entrée utilisée »), « Mon compte » réduit au mot de passe et à la déconnexion.
- QR hors ligne : clé `noctulist.portalQr.<orgId>` devient un objet par profil ; ancienne valeur bénévole relue telle quelle.
- `signup.js` : si `holderType == "GUEST"`, pas d'étape date de naissance / genre.
- Textes : 5 langues.

- [ ] **Step 1:** Test `firebase/tests/site-unit` : sélection de profil (aucun, un, trois) et lecture de l'ancienne clé QR.
- [ ] **Step 2:** Implémenter.
- [ ] **Step 3:** `npm run preview` + `node seed.mjs` ; navigateur intégré : invité permanent (modifier, QR), manager (deux groupes, entrée utilisée, pas de bouton Modifier), compte à trois profils, mode avion puis QR, clair/sombre, mobile/ordinateur. Captures.
- [ ] **Step 4:** Commit `Guest and group views in the portal`.

### Task 13: Docs et vérification finale

**Files:**
- Modify: `FIREBASE_SETUP.md` (règles v17, deux réglages), `CHANGELOG.md` (anglais)
- Modify: la spec (§ « Ajustements pendant la réalisation » si un choix a bougé)

- [ ] **Step 1:** `./gradlew :shared:desktopTest :app:testDebugUnitTest` → PASS (hors échec connu) ; `cd firebase/tests && npm test` → PASS.
- [ ] **Step 2:** `/usr/bin/git status` : aucun `.dylib`, aucun fichier de test local hors périmètre.
- [ ] **Step 3:** Commit `Document the guest portal`.
