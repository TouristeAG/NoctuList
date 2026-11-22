# Validation des demandes depuis l'accès guestlist : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une personne avec un accès guestlist en cours accepte (après correction) ou refuse depuis la plateforme les demandes d'invités en attente de sa soirée, si l'interrupteur « La plateforme valide » est actif.

**Architecture:** Les appareils ajoutent `reviews` à chaque accès de la page `guestListPortal/{uid}` (schema 2). La plateforme envoie une décision `ACCEPT`/`REJECT` dans `guestListEdits`. `GuestListEditProcessor` la revérifie et l'applique par l'hôte du portail, qui appelle `acceptGuestForm`, `acceptGuestFormRemoval` ou `rejectGuestForm` du ViewModel.

**Tech Stack:** Kotlin Multiplatform (Compose, Room), Firestore rules, JS vanilla du portail, tests `kotlin.test`, `node --test`, émulateurs Firebase.

**Spec:** `docs/superpowers/specs/2026-11-22-guest-list-review-design.md` (s'appuie sur `2026-11-21-guest-list-access-design.md`).

## Global Constraints

- Worktree `.claude/worktrees/guest-list-review`, branche `feature/guest-list-review`, toujours `/usr/bin/git` (le `git` du PATH est trop vieux pour les worktrees). Commits sans `Co-Authored-By` ni mention de Claude.
- Gradle avec `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Jamais `./gradlew --stop` (démons partagés) ; une erreur « IR lowering » se relance une fois. Ne jamais commiter `*.dylib`. `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode...` peut déjà être rouge sur `main`.
- Règles : `NOCTULIST_RULES_VERSION = 20` dans `firebase/firestore.rules`, `shared/src/commonMain/composeResources/files/firestore.rules` (identiques) et `NOCTULIST_FIRESTORE_RULES_VERSION` de `FirestorePermissionErrors.kt`.
- Portail : `webform/*` et `shared/src/commonMain/composeResources/files/webform/*` identiques avant chaque commit ; incrémenter la version du cache de `portal-sw.js` comme dans les commits portail précédents.
- Jamais de `formId` ni de `nanoId` dans une page : `reviewKey = PortalProjectionBuilder.sha256(form.formId).take(16)`, `guestKey = TemporaryGuestPortalQrDocument.guestKey(guest)`.
- Textes : 7 langues app (`values`, `-fr`, `-es`, `-la`, `-hi`, `-zh-rCN`, `-zh-rTW`), 5 portail (en, fr, es, de, it) ; pas de tiret cadratin, pas de sous-titre inutile, chaînes Compose sans apostrophe échappée.
- Interrupteur désactivé par défaut ; désactivé = pas de champ `reviews` et décision refusée `DISABLED`.

## Review Focus

1. **Demande traitée entre-temps dans l'app** (admin ou billetterie accepte pendant que la personne corrige) : décision refusée `NOT_PENDING`, rien n'est créé deux fois (Task 3 `alreadyReviewedNotPending`).
2. **Accès limité au lieu A, demande du lieu B à la même date** : invisible dans la page et décision refusée `NOT_PENDING` même avec la bonne `reviewKey` (Task 2 `venueGrantHidesOtherVenue`, Task 3 `otherVenueReviewNotPending`).
3. **Correction qui coche un accès d'un autre lieu ou vide toutes les lignes** : accès retiré ; aucune personne non vide = `INVALID`, la demande reste en attente (Task 3 `acceptFiltersAccessAndNeedsPeople`).
4. **Ajout depuis la plateforme au-delà de la limite de l'invitation** : `OVER_LIMIT`, demande toujours en attente pour l'admin (Task 3 `overLimitKeepsPending`).
5. **Interrupteur coupé alors qu'une décision est déjà envoyée** : `DISABLED`, et la page republiée sans `reviews` (Task 2 `noReviewsWhenOff`, Task 3 `disabledRejects`).

---

### Task 1: Interrupteur « La plateforme valide »

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/InstitutionSettings.kt` (clé + `ALL`, pas Sheets, comme `GUEST_FORMS_BILLETERIE_VALIDATE_ENABLED`)
- Modify: `data/sync/SettingsManager.kt` (clé de stockage, getter/setter, export l. ~1403, import l. ~1603)
- Modify: `ui/viewmodel/EventManagerViewModel.kt` (state flow, setter, rechargement l. ~1144, `volunteerPortalHost.portalValidateEnabled()`)
- Modify: `portal/VolunteerPortalService.kt` (interface `VolunteerPortalHost`)
- Modify: `ui/components/settings/SyncSettingsBindings.kt`, `ui/components/settings/SyncOnlinePage.kt` (ligne sous « La billetterie valide », dans le même `expanded`)
- Modify: `composeResources/values*/strings.xml` ×7
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/data/sync/InstitutionSettingsKeysTest.kt`

**Interfaces:**
- Produces: `InstitutionSettingsKeys.GUEST_FORMS_PORTAL_VALIDATE_ENABLED = "guest_forms_portal_validate_enabled"` ; `SettingsManager.isGuestFormsPortalValidateEnabled(): Boolean` (défaut `false`), `setGuestFormsPortalValidateEnabled(Boolean)` ; `EventManagerViewModel.guestFormsPortalValidateEnabled: StateFlow<Boolean>`, `setGuestFormsPortalValidateEnabled(Boolean)` ; `VolunteerPortalHost.fun portalValidateEnabled(): Boolean = false` ; chaîne `sync_online_portal_validates`.

- [ ] **Step 1: Test** `guestFormPortalValidateSyncsOverFirebaseButNotSheets` : la clé est dans `ALL` et `isSyncedToSheets` est faux.
- [ ] **Step 2:** `./gradlew :shared:desktopTest --tests '*InstitutionSettingsKeysTest*'` → rouge (clé absente).
- [ ] **Step 3:** Implémenter. Le setter du ViewModel fait comme `setTemporaryGuestPortalEnabled` : enregistre, `publishVolunteerPortalSettings()`, `republishVolunteerPortal()`, `backupInstitutionSettingsToSheets()`. La ligne n'est active (`enabled`) que si l'espace des invités temporaires est actif.
  Textes : en « The portal approves the forms », fr « La plateforme valide les formulaires », es « La plataforma valida los formularios », la « Porta formulas probat », hi « पोर्टल फ़ॉर्म स्वीकृत करता है », zh-rCN « 平台审核表单 », zh-rTW « 平台審核表單 ».
- [ ] **Step 4:** Le test passe ; `./gradlew :shared:desktopTest --tests '*StringsEscapeTest*'` vert ; `./gradlew :desktopApp:compileKotlin` vert.
- [ ] **Step 5:** Commit `Setting: the portal approves guest forms`.

### Task 2: Demandes dans la page guestlist (schema 2)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/GuestListReviews.kt`
- Modify: `portal/GuestListPortalProjection.kt` (`SCHEMA = 2`, paramètres en plus, champ `reviews`)
- Modify: `portal/GuestPortalPublisher.kt` (passe les formulaires, les noms des titulaires et l'interrupteur)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/GuestListReviewsTest.kt`, `GuestListPortalProjectionBuilderTest.kt`

**Interfaces:**
- Consumes: `VolunteerPortalHost.portalValidateEnabled()` (Task 1).
- Produces:
  - `object GuestListReviews { fun reviewKey(form: GuestForm): String; fun covered(grant: GuestListGrant, forms: List<GuestForm>, orgId: String): List<GuestForm>; fun toJson(form: GuestForm, forms: List<GuestForm>, guests: List<Guest>, holderNames: Map<String, String>): JsonObject }`
  - `GuestListPortalProjectionBuilder.build(grants, guests, accesses, associationName, now, offsetHours, reviewForms: List<GuestForm> = emptyList(), holderNames: Map<String, String> = emptyMap(), reviewsEnabled: Boolean = false, orgId: String = "")`

`covered` garde les formulaires `awaitsReview`, de l'organisation (`firebaseOrgId` vide ou égal), même jour que l'accès (`DateTimeUtils.getStartOfDayWithOffset(_, 0)`), et, si l'accès a un lieu, `listVenueLabel(form.venueName)` vide ou égal (sans casse). Tri par `submittedAt` croissant.
`toJson` suit la spec §5.2 : `source = PORTAL` si `isPortalInvite`, sinon `FORM` ; `kind` = `requestKindValue` ; ADD : `people` depuis `GuestFormSubmissionCodec.decode(form.submissionJson)` (`name`, `accessIds` = `requestedAccessIds`), `contactEmail`, `emergencyPhone`, `notes` de la soumission (une réponse portail prend `prefillEmail`/`prefillPhone` de son modèle, retrouvé par `parentFormId` dans `forms`) ; REMOVE : `removeGuests` = invités dont le `guestKey` est dans `removeGuestKeyList()`, `{guestKey, name}` ; `requestedBy` = `holderNames[form.portalUid]` ou `""`. Le publisher construit `holderNames` depuis les bénévoles et invités de l'organisation (`portalUid` → « prénom nom »).

- [ ] **Step 1: Tests** `GuestListReviewsTest` : `coversSameDayOnly` (demande d'un autre jour exclue), `venueGrantHidesOtherVenue` (lieu B exclu, sans lieu gardé), `onlyPendingReview` (OPEN, ACCEPTED, REJECTED exclus), `addCarriesWholeRequest` (personnes, accès, e-mail, téléphone, notes), `portalAddUsesTemplateContact`, `removeListsTargetedGuests`, `noFormIdInJson` (le JSON sérialisé ne contient pas `formId`), `reviewKeyIsHashOfFormId` (16 hex, stable). Dans `GuestListPortalProjectionBuilderTest` : `noReviewsWhenOff` (pas de clé `reviews`), `reviewsWhenOn`, `schemaIsTwo`.
- [ ] **Step 2:** `./gradlew :shared:desktopTest --tests '*GuestListReviewsTest*' --tests '*GuestListPortalProjectionBuilderTest*'` → rouge.
- [ ] **Step 3:** Implémenter ; dans le publisher, passer `inviteForms` (déjà tous les formulaires du snapshot), `host.portalValidateEnabled()` et `orgId`.
- [ ] **Step 4:** Les tests passent, avec `*TemporaryGuestPortalProjectionBuilderTest*` et `*GuestPortalPublisher*` toujours verts.
- [ ] **Step 5:** Commit `Publish pending guest requests on the guest list page`.

### Task 3: Décisions `ACCEPT` et `REJECT` sur l'appareil

**Files:**
- Modify: `portal/GuestListEditProcessor.kt` (`GuestListEdit` : `reviewKey`, `people` ; parse ; branches)
- Modify: `portal/VolunteerPortalService.kt` (interface hôte ; `processListEdit` passe les formulaires)
- Modify: `ui/viewmodel/EventManagerViewModel.kt` (implémentations hôte)
- Modify: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalServiceFakes.kt`
- Test: `GuestListEditProcessorTest.kt`

**Interfaces:**
- Consumes: `GuestListReviews.reviewKey`, `GuestListReviews.covered` (Task 2), `portalValidateEnabled()` (Task 1).
- Produces:
  - `GuestListEdit.ACCEPT = "ACCEPT"`, `REJECT = "REJECT"` ; champs `reviewKey: String = ""`, `people: List<ManualTemporaryGuestEntry> = emptyList()`. Parse : `reviewKey` 16 hex obligatoire pour ACCEPT/REJECT ; `people` ≤ 100, chaque `name` passé par `PortalInputValidator.cleanName` (null → document invalide), `accessIds` ≤ 20.
  - `VolunteerPortalHost` : `suspend fun guestForms(): List<GuestForm> = emptyList()`, `suspend fun acceptGuestFormFromPortal(form: GuestForm, people: List<ManualTemporaryGuestEntry>, contactEmail: String, phone: String, notes: String, reviewedBy: String): Boolean = false`, `suspend fun acceptGuestFormRemovalFromPortal(form: GuestForm, reviewedBy: String): Boolean = false`, `suspend fun rejectGuestFormFromPortal(form: GuestForm, reviewedBy: String): Boolean = false`.
  - `GuestListEditProcessor.apply(edit, volunteers, guests, forms: List<GuestForm> = emptyList())`.

Ordre dans `apply` pour ACCEPT/REJECT : `DISABLED` si `!host.portalValidateEnabled()`, puis `NO_GRANT` (code actuel), puis la demande = `GuestListReviews.covered(grant, forms, "")` dont la `reviewKey` correspond, sinon `NOT_PENDING`. ACCEPT d'un ajout : personnes au nom non vide, accès filtrés par `accessesFor(form.venueName, …)` ; aucune → `INVALID` ; `false` de l'hôte → `OVER_LIMIT`. ACCEPT d'un retrait → `acceptGuestFormRemovalFromPortal`. REJECT → `rejectGuestFormFromPortal`. `reviewedBy = "Plateforme · " + nom du titulaire` (prénom nom du profil qui porte l'accès). Échec d'enregistrement : `SAVE_FAILED`.
Côté ViewModel : `guestForms()` = `_guestForms.value` ; `acceptGuestFormFromPortal` relit la demande (`repository.getGuestFormByFormIdAndOrg`) et rend `false` si elle n'est plus `PENDING_REVIEW`, sinon `acceptGuestForm(...)` ; les deux autres pareil avec `acceptGuestFormRemoval` et `rejectGuestForm`. Chacun commence par `rejectIfCrudSoftLocked`.
`VolunteerPortalService.processListEdit` passe `host.guestForms()`.

- [ ] **Step 1: Tests** : `acceptAddWithCorrections` (noms et accès envoyés, e-mail, téléphone, notes transmis, `reviewedBy` commence par « Plateforme · »), `acceptRemoval`, `rejectRequest`, `disabledRejects`, `revokedGrantRejectsReview` (`NO_GRANT`), `alreadyReviewedNotPending`, `otherVenueReviewNotPending`, `acceptFiltersAccessAndNeedsPeople`, `overLimitKeepsPending` (l'hôte rend `false` → `OVER_LIMIT`, aucun rejet appelé) ; parse : ACCEPT sans `reviewKey` → null, 101 personnes → null, nom `<b>` → null.
- [ ] **Step 2:** `./gradlew :shared:desktopTest --tests '*GuestListEditProcessorTest*'` → rouge.
- [ ] **Step 3:** Implémenter (processeur, interface, fake avec listes `acceptedReviews`, `rejectedReviews`, `removedReviews` et `var acceptResult = true`, ViewModel).
- [ ] **Step 4:** Tests verts, plus `*VolunteerPortalService*` ; `./gradlew :desktopApp:compileKotlin` vert.
- [ ] **Step 5:** Commit `Apply guest request decisions from the portal on devices`.

### Task 4: Règles v20

**Files:**
- Modify: `firebase/firestore.rules`, `shared/src/commonMain/composeResources/files/firestore.rules`, `data/remote/FirestorePermissionErrors.kt`
- Test: `firebase/tests/rules/guest-list.test.mjs`

`validGuestListEdit` : clés permises `+ reviewKey, people` ; `action in ['ADD','UPDATE','DELETE','ACCEPT','REJECT']` ; la condition `guestKey` ne vaut que pour UPDATE/DELETE ; pour ACCEPT/REJECT `d.reviewKey is string && d.reviewKey.matches('^[0-9a-f]{16}$')` ; `d.get('people', []) is list && d.get('people', []).size() <= 100`. Le contenu de chaque personne est revérifié par l'appareil (les règles ne bouclent pas).

- [ ] **Step 1: Tests** : `ACCEPT avec bonne date et reviewKey : oui`, `REJECT : oui`, `ACCEPT sans reviewKey : non`, `ACCEPT autre date : non`, `people de 101 : non`, `ACCEPT par un autre compte : non`.
- [ ] **Step 2:** `cd firebase/tests && npm run test:rules` → les nouveaux tests rouges.
- [ ] **Step 3:** Modifier les deux fichiers de règles (en-tête v20) et la constante Kotlin à 20.
- [ ] **Step 4:** `npm run test:rules` vert ; `./gradlew :shared:desktopTest --tests '*FirestoreCodecAndRulesShapeTest*'` vert.
- [ ] **Step 5:** Commit `Rules v20: guest request decisions from the guest list`.

### Task 5: Portail

**Files:**
- Modify: `webform/portal-common.js` (+ copie), `webform/portal.js` (+ copie), `webform/portal.css` (+ copie), `webform/portal-i18n.js` (+ copie), `webform/portal-sw.js` (+ copie)
- Modify: `firebase/tests/seed.mjs` (une demande formulaire et un retrait plateforme en attente pour la date de l'accès de démo, interrupteur actif)
- Test: `firebase/tests/site-unit/portal-guest-list.test.mjs`

**Interfaces:**
- Consumes: page schema 2 (Task 2), décisions (Tasks 3, 4).
- Produces (`portal-common.js`) :
  - `reviewSummary(review): { title, count, sourceKey }` : `title` = `listName` sinon `requestedBy` sinon `contactEmail` ; `count` = nombre de `people` (ADD) ou de `removeGuests` (REMOVE) ; `sourceKey` = `"reviewFromForm"` ou `"reviewFromPortal"`.
  - `guestListReviewEdit({ review, accept, people, contactEmail, phone, notes })` : renvoie les champs de la décision (`action`, `reviewKey`, et pour un ACCEPT d'ajout `people` nettoyés : noms passés par `cleanName`, lignes vides retirées, accès dédoublonnés, `contactEmail`, `phone`, `notes` coupés) ; renvoie `null` si un nom est invalide ou si un ACCEPT d'ajout n'a plus personne.

Dans `portal.js` : `guestListSection` affiche, si `grant.reviews` n'est pas vide, une carte « {n} demandes à valider » avant les outils, une ligne par demande (`reviewSummary`) ; clic → `openGuestListReview(review, grant)` dans `openSheet`. Ajout : une ligne par personne (nom + `accessBoxes(grant, review.venueName, accessIds)`), bouton « Ajouter une personne », champs e-mail, téléphone, notes, puis « Refuser » (confirmation « Refuser cette demande ? ») et « Accepter ». Retrait : la liste des noms, « Refuser » et « Accepter ». Envoi par `sendGuestListEdit` ; `guestListPending` garde la `reviewKey` pour afficher « En cours… » sur la ligne. `GUEST_LIST_REASONS` + `NOT_PENDING`, `OVER_LIMIT`, `DISABLED`, `INVALID`.

Textes (en / fr ; es, de, it traduits pareil) : `reviewsHeading` « {n} requests to review » / « {n} demandes à valider » (singulier `reviewsHeadingOne` « 1 request to review » / « 1 demande à valider ») ; `reviewFromForm` « form » / « formulaire » ; `reviewFromPortal` « from the portal » / « depuis la plateforme » ; `reviewRemoval` « Removal of {n} guests » / « Retrait de {n} invités » ; `accept` « Accept » / « Accepter » ; `refuse` « Refuse » / « Refuser » ; `refuseConfirm` « Refuse this request? » / « Refuser cette demande ? » ; `addPerson` « Add a person » / « Ajouter une personne » ; `guestListNotPending` « This request was already handled. » / « Cette demande a déjà été traitée. » ; `guestListOverLimit` « Goes past the invite limit. » / « Dépasse la limite de l'invitation. » ; `guestListDisabled` « Approving from the portal is turned off. » / « La validation depuis la plateforme est désactivée. ».

- [ ] **Step 1: Tests** : `reviewSummary: titre, nombre, source` (ADD formulaire, REMOVE plateforme, demande sans nom de liste) ; `guestListReviewEdit: ACCEPT nettoie les personnes` (lignes vides retirées, accès dédoublonnés) ; `guestListReviewEdit: REJECT ne porte que action et reviewKey` ; `guestListReviewEdit: nom invalide ou plus personne → null`.
- [ ] **Step 2:** `cd firebase/tests && npm run test:site` → rouge.
- [ ] **Step 3:** Implémenter, copier vers `composeResources/files/webform/`, incrémenter le cache du service worker.
- [ ] **Step 4:** `npm run test:site` vert ; `diff -r webform shared/src/commonMain/composeResources/files/webform` sans différence sur les fichiers touchés ; navigateur sur les émulateurs (`npm run preview`, `node seed.mjs`) : la carte des demandes apparaît, la fenêtre montre toute la demande, une correction puis « Accepter » envoie le bon document (`read_network_requests` ou lecture Firestore de l'émulateur), « Refuser » demande confirmation ; interrupteur coupé dans le seed : pas de carte.
- [ ] **Step 5:** Commit `Portal: review guest requests from the guest list`.

### Task 6: Vérification d'ensemble

- [ ] `./gradlew :shared:desktopTest` (seul l'échec connu est toléré), `./gradlew :desktopApp:compileKotlin :app:compileDebugKotlin`, `cd firebase/tests && npm test`.
- [ ] Mettre à jour `CHANGELOG.md` (section non publiée) : validation des demandes depuis l'accès guestlist, règles v20 à publier.
- [ ] Commit `Changelog: guest requests reviewed from the portal`.
