# Rôles fusionnés, invitations et cartes d'invités : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une adresse = un compte portail avec tous ses rôles, listes d'invités temporaires visibles jusqu'au lendemain 8h00 (y compris chez les bénévoles et invités permanents), invitations par événement validées comme le formulaire, cartes d'invités avec modification du nom, textes généraux.

**Architecture:** Le rattachement par adresse est une fonction pure (`PortalAccountMerger`) appliquée par `VolunteerPortalService` avant chaque publication. La page `temporaryGuestPortal/{uid}` passe en schema 2 (fenêtre `visibleUntil`, `deviceCreated`, noms d'accès, bloc `invite`, `pending`). Les invitations réutilisent `GuestForm` : un modèle multi-réponses lié à un compte (`portalUid`), dont les réponses (`ADD`/`REMOVE`) suivent la validation existante. La demande `TEMP_GUEST_NAME` suit le chemin des demandes (Functions, repli appareil).

**Tech Stack:** Kotlin Multiplatform (Room, GitLive Firestore, Compose), TypeScript Cloud Functions, JS vanilla (portail), règles Firestore, `@firebase/rules-unit-testing` + `node:test`.

**Spec:** `docs/superpowers/specs/2026-11-21-guest-portal-roles-invites-design.md` (et `2026-11-20-guest-portal-design.md` pour le socle).

## Global Constraints

- Branche `feature/guest-portal`, worktree `.claude/worktrees/guest-portal`. Git : `/usr/bin/git`.
- Commits sans ligne `Co-Authored-By` ni mention de Claude.
- Gradle : `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Ne jamais commiter `shared/src/desktopMain/resources/*.dylib`. `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode...` échoue déjà sur `main` : ignorer.
- Ne jamais lancer un build de la branche sur `~/.noctulist` : pour l'app, `./gradlew -I ~/.noctulist-test/test-run.init.gradle :desktopApp:run` ou les émulateurs.
- Room 55 → 56. Règles `NOCTULIST_RULES_VERSION = 18`, trois copies identiques : `shared/src/commonMain/composeResources/files/firestore.rules`, `firebase/firestore.rules`, `firebase/tests/firestore.rules` (lien).
- Fichiers web copiés à l'identique dans `shared/src/commonMain/composeResources/files/webform/`. Functions : `npm run build` dans `webform/functions` puis copie de `lib/index.js` dans `composeResources/files/webform/functions/lib/index.js`.
- `visibleUntil = DateTimeUtils.getStartOfDayWithOffset(eventDate, 0) + 32 * 3_600_000L` (heure de Genève, décalage fixe 8 h, indépendant de `date_change_offset_hours`).
- Clé d'adresse : `email.trim().lowercase()`. Comparées : bénévole et invité permanent `email`, `portalGoogleEmail` ; invité temporaire `temporaryContactEmail`.
- `guestKey = PortalProjectionBuilder.sha256(nanoId).take(16)` ; jamais de `nanoId` ni d'identifiant d'accès brut dans une page.
- Aucun e-mail automatique ; aucun compte Firebase Auth supprimé ou modifié.
- Textes : 7 langues dans l'app (test de parité), 5 dans le portail (fr, en, es, de, it). Pas de tiret cadratin, pas de sous-titre redondant, pas de texte qui sonne artificiel. Noms internes (`TemporaryGroupPortalDialog`, `groups`) inchangés.

## Review Focus

1. **Bénévole sans accès dont l'adresse a un compte d'invité permanent** : il est rattaché et voit son profil bénévole à la connexion suivante, sans e-mail (Task 3, test `volunteerJoinsGuestAccount`).
2. **Liste à 7h59 puis 8h00 le lendemain, et à 23h59 la veille** : visible, puis retirée à la minute près par la minuterie (Task 1, tests `visibleUntilBoundaries`, `republishesWhenAListEnds`).
3. **Compte réutilisé d'un bénévole issu d'une inscription non vérifiée** : la liste rattachée n'est pas lisible sans e-mail vérifié (Task 5, test `reusedAccountNeedsVerifiedSession`).
4. **Deux envois d'ajout en attente qui dépassent ensemble la limite** : la plateforme compte les places restantes avec les ajouts en attente ; la seconde acceptation est bloquée (Task 6, test `secondPendingAddBlockedOverLimit`).
5. **Fenêtre QR ouverte pendant la relecture qui suit un CLAIM** : elle reste ouverte (Task 9, test `qrDialogSurvivesDeniedReload`).

---

### Task 1: Fenêtre d'affichage `visibleUntil`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/TemporaryGuestPortalProjection.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/GuestPortalPublisher.kt`, `VolunteerPortalService.kt` (boucle de reprise)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/TemporaryGuestPortalProjectionBuilderTest.kt`, `VolunteerPortalServiceTest.kt`

**Interfaces:**
- Produces: `TemporaryGuestPortalProjectionBuilder.visibleUntil(eventDate: Long): Long` (remplace `endsAt`, plus de paramètre `offsetHours` ; `runningBatches(guests, now)` et `TemporaryGuestPortalQrDocument.build(guests, enabled, now)` perdent aussi `offsetHours`). `GuestPortalPublisher.nextTemporaryExpiry: Long?` (plus petit `visibleUntil` futur des listes publiées au dernier passage). Champ de page `endsAt` renommé `visibleUntil`.

- [ ] **Step 1:** Tests `visibleUntilBoundaries` : événement le 20/11 (Genève) ; publié à 19/11 23:59, 21/11 01:00 et 21/11 07:59 ; absent à 21/11 08:00 (page `null`, QR `null`). Test `republishesWhenAListEnds` : horloge factice passée au-delà de `nextTemporaryExpiry`, un tour de la boucle de reprise republie (la page est supprimée).
- [ ] **Step 2:** `./gradlew :shared:desktopTest --tests '*TemporaryGuestPortalProjectionBuilderTest*' --tests '*VolunteerPortalServiceTest*'` → FAIL.
- [ ] **Step 3:** Implémenter. Dans la boucle de reprise de `VolunteerPortalService.start`, republier si `clock() >= publisher.nextTemporaryExpiry` (en plus du changement de jour). Mettre à jour les appels existants.
- [ ] **Step 4:** Relancer → PASS.
- [ ] **Step 5:** Commit `Show temporary guest lists until 8:00 the day after the event`.

### Task 2: Room 56 et synchro des nouveaux champs

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/models/Models.kt` (`Guest.temporaryPortalCreated: Boolean = false`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/models/GuestForm.kt` (`portalUid: String = ""`, `requestKind: String = "ADD"`, `removeGuestKeys: String = ""` ; `enum class GuestFormRequestKind { ADD, REMOVE }` avec `parse` comme les autres enums ; `val GuestForm.isPortalInvite get() = portalUid.isNotBlank()` ; `fun GuestForm.removeGuestKeyList(): List<String>`)
- Modify: `EventManagerDatabase.kt` (version 56, `MIGRATION_55_56`), codec Firestore des invités et `GitLiveFirestoreGateway.guestFormToMap` / lecture, `FirestoreChangeApplier`
- Test: tests de migration et de codec existants (mêmes fichiers que pour `MIGRATION_54_55`)

**Interfaces:**
- Produces: les champs ci-dessus, synchronisés en Firestore sous les mêmes noms ; jamais écrits dans Google Sheets.

- [ ] **Step 1:** Tests : `migration55To56KeepsRowsAndDefaults` ; aller-retour codec invité (`temporaryPortalCreated`) ; aller-retour `guestFormToMap` (`portalUid`, `requestKind`, `removeGuestKeys`) ; une réponse sans ces champs se lit avec les valeurs par défaut.
- [ ] **Step 2:** Lancer les tests concernés → FAIL.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `./gradlew :shared:desktopTest` → PASS.
- [ ] **Step 5:** Commit `Room 56: portal invite fields on guest forms and guests`.

### Task 3: Une adresse, un compte

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalAccountMerger.kt`
- Modify: `VolunteerPortalService.kt` (appel avant `publishAll`), `TemporaryGuestPortalAccess.kt`, `VolunteerPortalUi.kt` (mention de conflit)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/PortalAccountMergerTest.kt`, `TemporaryGuestPortalAccessTest.kt`

**Interfaces:**
- Produces:
  - `data class PortalMergePlan(val volunteers: List<Volunteer>, val guests: List<Guest>, val conflicts: Set<String> /* adresses */)` : profils à sauvegarder (déjà modifiés, `lastModified` non touché : l'appelant le monte).
  - `object PortalAccountMerger { fun plan(volunteers: List<Volunteer>, guests: List<Guest>, guestSpaceOn: Boolean, tempSpaceOn: Boolean): PortalMergePlan; fun addressesOf(v: Volunteer): Set<String>; fun addressesOf(g: Guest): Set<String>; fun conflictFor(addresses: Set<String>, plan: PortalMergePlan): Boolean }`.
  - Règles de `plan` : un uid par adresse (celui des profils qui l'ont) ; un profil sans uid dont une adresse a un uid unique reçoit `portalUid` et `portalEnabled = true` (invités permanents si `guestSpaceOn`, temporaires si `tempSpaceOn`, bénévoles toujours) ; deux uids pour une adresse → adresse dans `conflicts`, rien ne bouge ; un profil dont l'uid est partagé avec d'autres profils mais dont aucune adresse n'est commune à un autre profil de cet uid → `portalUid = ""` (adresse modifiée) puis règle normale. Un invité temporaire rattaché à un compte qui porte aussi un bénévole ou un invité permanent garde `temporaryPortalCreated` tel quel ; la page calcule `deviceCreated` (Task 4).
  - `TemporaryGuestPortalAccess.open` : si un bénévole ou invité permanent de l'adresse a un uid → `TemporaryGroupAccessResult.JoinedAccount(uid)` (nouveau cas, pas de `signUp`) ; uid d'une autre liste ouverte → `Reused` ; `Created` pose `temporaryPortalCreated = true` sur toute la liste ; `Reused` recopie `temporaryPortalCreated` de la liste d'origine.
  - ViewModel : `portalAddressConflict(email: String): Boolean`.
- Consumes: `VolunteerPortalHost.saveVolunteer/saveGuest`, `republish()`.

- [ ] **Step 1:** Tests `PortalAccountMergerTest` : `volunteerJoinsGuestAccount` ; `guestJoinsVolunteerByGoogleEmail` ; `temporaryListJoinsVolunteerAccount` ; `nothingWithoutAnAccount` ; `guestSpaceOffLeavesGuestAlone` ; `twoAccountsIsAConflict` ; `changedAddressLeavesSharedAccount` ; `planIsIdempotent` (appliquer le plan puis replanifier → plan vide). Tests `TemporaryGuestPortalAccessTest` : `openJoinsExistingVolunteerAccount` (aucun `createAccount`, `temporaryPortalCreated = false`) ; `createdMarksListDeviceCreated` ; remplacer le test qui interdisait la reprise d'un uid de bénévole.
- [ ] **Step 2:** Lancer → FAIL.
- [ ] **Step 3:** Implémenter ; le service applique le plan à chaque instantané avant `publishAll` (sauvegardes avec `lastModified = maxOf(clock(), old + 1)`), et seulement si `volunteer_portal_enabled`. La carte « Accès plateforme » affiche `Cette adresse est déjà liée à un autre compte` quand `portalAddressConflict` est vrai. La fenêtre de résultat affiche `Cette adresse a déjà un compte. La liste apparaît dans son espace.` pour `JoinedAccount`.
- [ ] **Step 4:** `./gradlew :shared:desktopTest --tests '*PortalAccountMergerTest*' --tests '*TemporaryGuestPortalAccessTest*' --tests '*VolunteerPortalServiceTest*'` → PASS.
- [ ] **Step 5:** Commit `One portal account per address across volunteers and guests`.

### Task 4: Page temporaire schema 2

**Files:**
- Modify: `TemporaryGuestPortalProjection.kt`, `GuestPortalPublisher.kt`, `VolunteerPortalService.kt` (flux `guestForms` ajouté à l'instantané), `VolunteerPortalHost` (`venueAccessNames(): Map<String, String>`, `guestForms(): List<GuestForm>` par défaut vides)
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalInviteAllowance.kt`
- Test: `TemporaryGuestPortalProjectionBuilderTest.kt`, `PortalInviteAllowanceTest.kt`

**Interfaces:**
- Produces:
  - `TemporaryGuestPortalInput` gagne `deviceCreated: Boolean`, `accessNames: Map<String, String>`, `inviteForms: List<GuestForm>` (modèles et réponses liés à ce uid). `SCHEMA = 2`.
  - Payload par liste : `visibleUntil`, `invite: {formId, maxGuests, remaining, accesses: [{id, name, max, remaining}]} | null`, `pending: [{responseId, kind, names: [string], guestKeys: [string], status}]`. Par invité : `accessNames: [string]`. Racine : `deviceCreated` (aussi en champ de document via `extraFields`, à côté de `expiresAtMillis`).
  - Une liste sans invité mais avec un modèle ouvert est publiée (date, lieu, nom du modèle).
  - `object PortalInviteAllowance { fun of(template: GuestForm, batch: List<Guest>, responses: List<GuestForm>): InviteAllowance }` avec `data class InviteAllowance(val maxGuests: Int, val remaining: Int, val perAccess: Map<String, Pair<Int, Int>> /* id → (max, restant) */)`. Comptage : invités acceptés de la liste + noms des réponses `ADD` en `PENDING_REVIEW` − invités visés par des `REMOVE` en attente ; `maxRequests = 0` = pas de limite par accès ; `remaining` jamais négatif. Utilisé aussi par Task 6.
  - `pending` : réponses `PENDING_REVIEW` et `REJECTED` (ces dernières jusqu'à `visibleUntil`) ; `names` tirés de `submissionJson` pour `ADD`, des invités visés pour `REMOVE`.
  - `deviceCreated` = tous les invités temporaires du uid ont `temporaryPortalCreated` ET aucun bénévole ni invité permanent n'a ce uid (le publisher reçoit les deux listes).
- Consumes: `visibleUntil` (Task 1), champs Task 2.

- [ ] **Step 1:** Tests : `schema2CarriesAccessNamesNotIds` (aucun identifiant d'accès ni `nanoId` dans le payload) ; `emptyListWithOpenTemplateIsPublished` ; `inviteBlockNullWithoutTemplate` ; `pendingAddAndRemoveListed` ; `deviceCreatedFalseWhenVolunteerShares` ; `PortalInviteAllowanceTest` : `countsAcceptedAndPending`, `pendingRemoveFreesAPlace`, `perAccessLimit`, `zeroMeansNoAccessLimit`, `neverNegative`.
- [ ] **Step 2:** Lancer → FAIL.
- [ ] **Step 3:** Implémenter ; `combine` à plus de 5 flux : passer par la surcharge tableau (`combine(listOf(...)) { arr -> }`).
- [ ] **Step 4:** Relancer → PASS.
- [ ] **Step 5:** Commit `Temporary guest page schema 2 with invites and access names`.

### Task 5: Règles Firestore v18

**Files:**
- Modify: `firebase/firestore.rules` (+ copie `composeResources`), `NOCTULIST_RULES_VERSION` (constante Kotlin)
- Test: `firebase/tests/rules/guest-portal.test.mjs`, nouveau `firebase/tests/rules/guest-invites.test.mjs`

**Interfaces:**
- Produces (§9 de la spec) :
  - `tempSessionOk(orgId, uid)` : `isPortalUser() && auth.uid == uid` ou `isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid` ou `isPasswordAccount() && auth.uid == uid && <page>.deviceCreated == true`. Sur `temporaryGuestPortal/{uid}`, la dernière branche lit `resource.data.deviceCreated` (pas de `get`).
  - `temporaryGuestPortalQr/{uid}` : idem, `deviceCreated` lu dans le `get` de page déjà fait par `temporaryGuestPageLive`.
  - `portalRequests` : type `TEMP_GUEST_NAME`, clés `type, status, createdAt, email, guestKey, firstName, lastName` ; `guestKey` `^[0-9a-f]{16}$` ; noms ≤ 60 ; au moins un non vide ; `tempGuestPortalOn` et `tempSessionOk(orgId, <uid de la demande>)` ; délai 1 min ; `get` accepte `<uid>_TEMP_GUEST_NAME`.
  - `guestForms` : `get` public refusé si `resource.data.get('portalUid','') != ''` (mais permis à `tempSessionOk(orgId, resource.data.portalUid)`) ; `create` public refusé si le parent a un `portalUid` ; nouveau `create` lié : parent `OPEN`, non expiré, `allowMultipleResponses`, `portalUid` du parent == celui de la réponse, `tempSessionOk(orgId, parent.portalUid)`, `status == 'PENDING_REVIEW'`, mêmes champs copiés du parent que `isPublicMultiGuestFormCreate`, `requestKind in ['ADD','REMOVE']`, `removeGuestKeys` chaîne ≤ 1700, `submissionJson` < 20000.
  - Compter les accès de chaque chemin (≤ 10) ; regrouper les `get` du parent dans une `let`.
- Consumes: champs Tasks 2 et 4.

- [ ] **Step 1:** Tests : `reusedAccountNeedsVerifiedSession` (page `deviceCreated:false`, session non vérifiée → refus ; vérifiée → ok) ; `deviceCreatedAllowsUnverified` ; `googleSessionReadsTemporaryPage` ; `TEMP_GUEST_NAME` (ok, autre uid, clé invalide, noms vides, espace coupé) ; réponses liées : bon compte ok, autre compte, sans session, parent fermé, parent expiré, `portalUid` différent, `requestKind` inconnu → refus ; public : `get` et `create` sur un modèle lié refusés ; formulaire public existant inchangé (suite existante verte).
- [ ] **Step 2:** `cd firebase/tests && npm run test:rules` → FAIL.
- [ ] **Step 3:** Implémenter, synchroniser les copies, passer la version à 18.
- [ ] **Step 4:** `npm run test:rules` → PASS.
- [ ] **Step 5:** Commit `Rules v18: merged accounts, name requests and portal invites`.

### Task 6: Invitations dans l'app (modèles, validation, retrait)

**Files:**
- Modify: `EventManagerViewModel.kt`, `GuestFormManagementScreen.kt`, `GuestFormUi.kt` (contrôles de limites réutilisés), `TemporaryGroupPortalDialog.kt`, `VolunteerPortalUi.kt` (bouton « Gérer des invités ») et les deux panneaux d'invité permanent / bénévole qui l'affichent
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/ui/viewmodel/PortalInviteViewModelTest.kt` (ou le fichier de tests du ViewModel existant)

**Interfaces:**
- Produces (ViewModel) :
  - `suspend fun openPortalInvites(contactEmail: String, portalUid: String, eventDateMillis: Long, venueName: String, artistName: String, maxGuests: Int, accessLimits: Map<String, Int>, emergencyPhone: String): GuestForm?` : crée ou met à jour le modèle (`allowMultipleResponses = true`, `expiryMode = MANUAL`, `expiresAtMillis = visibleUntil`, `askEmail = false`, `askPhone = false`, `prefillEmail`, `prefillPhone`, `offeredAccessIds` via `GuestFormOfferedAccessCodec.encode`).
  - `fun closePortalInvites(template: GuestForm)` → `EXPIRED`, réponses en attente intactes.
  - `fun portalInviteTemplates(portalUid: String): Flow<List<GuestForm>>`.
  - `acceptGuestForm` : si le parent est un modèle lié, refuse (retourne `false`, nouveau type de retour `Boolean`) quand `PortalInviteAllowance.of` montre un dépassement ; contact et téléphone pris du modèle.
  - `fun acceptGuestFormRemoval(form: GuestForm, reviewedBy: String = ""): List<String>` : supprime les invités de la liste du modèle dont la `guestKey` est dans `removeGuestKeyList()` et l'entrée non utilisée ; renvoie les noms conservés (entrée utilisée) ; ferme la réponse `ACCEPTED`.
- UI :
  - `TemporaryGroupPortalDialog` (titre « Espace invités ») : par liste à venir de l'adresse, interrupteur « Peut inviter » qui déplie nombre maximum et accès avec maximum ; bouton « Ouvrir les invitations pour un autre événement » (date, lieu, nom de la liste par défaut nom de la personne sinon adresse, limites).
  - Carte « Accès plateforme » d'un bénévole ou invité permanent avec accès : bouton « Gérer des invités » qui ouvre la même fenêtre pour son adresse.
  - Écran de validation : mention « Depuis la plateforme » et nom de la personne (profil du `portalUid`, sinon `prefillEmail`) ; réponse `REMOVE` affichée comme liste de noms à retirer avec Accepter/Refuser ; message de blocage au-delà des limites.

- [ ] **Step 1:** Tests : `openPortalInvitesCreatesBoundTemplate` (champs ci-dessus) ; `acceptedAddJoinsTheSameList` (même `temporaryBatchKey`, même contact) ; `secondPendingAddBlockedOverLimit` ; `removalDeletesUnusedOnly` ; `closeKeepsPendingResponses` ; `emptyEventTemplateHasNoGuests`.
- [ ] **Step 2:** Lancer → FAIL.
- [ ] **Step 3:** Implémenter (ViewModel puis UI ; chaînes en 7 langues, liste du §11 de la spec).
- [ ] **Step 4:** `./gradlew :shared:desktopTest :shared:compileKotlinDesktop :app:compileDebugKotlin` → PASS.
- [ ] **Step 5:** Commit `Per-event portal invites reviewed like guest forms`.

### Task 7: Demande `TEMP_GUEST_NAME`

**Files:**
- Modify: `PortalDocuments.kt` (`PortalRequestType.TEMP_GUEST_NAME`, `PortalRequest.guestKey: String = ""`, `parseRequest`), `VolunteerPortalService.processRequest`
- Modify: `webform/functions/src/validate.ts`, `requests.ts` ; rebuild + copie `lib/index.js`
- Test: `PortalInputValidatorTest.kt`, `VolunteerPortalServiceTest.kt`, `firebase/tests/functions/requests.test.mjs`

**Interfaces:**
- Produces: traitement identique côté appareil et serveur : invité temporaire `portalEnabled` avec `portalUid == request.uid` et `guestKey` égal → si `temporaryEntryValidated` : `REJECTED ENTRY_USED` ; sinon `name = firstName.trim()`, `lastNameAbbreviation = lastName.trim()`, `lastModified` monté, `DONE` ; aucun → `REJECTED NOT_FOUND`. Le serveur corrige aussi les noms dans le payload de `temporaryGuestPortal/{uid}` et recalcule `contentHash` (comme `PROFILE`), et le `name` de l'élément QR. La demande est réclamée seulement si `temp_guest_portal_enabled`.

- [ ] **Step 1:** Tests : validateur (clé 16 hex, longueurs, au moins un nom) ; appareil `renamesOwnTemporaryGuest`, `entryUsedRejected`, `otherAccountNotFound` ; Functions `TEMP_GUEST_NAME renames and fixes the page`, `TEMP_GUEST_NAME refused for another account`.
- [ ] **Step 2:** Lancer (`./gradlew ... --tests '*PortalInputValidatorTest*' --tests '*VolunteerPortalServiceTest*'`, `cd firebase/tests && npm run test:functions`) → FAIL.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** Relancer → PASS.
- [ ] **Step 5:** Commit `Let a contact correct the name of a temporary guest`.

### Task 8: Textes généraux dans l'app

**Files:**
- Modify: `shared/src/commonMain/composeResources/values*/strings.xml` (7 langues), générateur de l'e-mail des invités temporaires si ses libellés parlent de « groupe »

**Interfaces:**
- Produces: les remplacements du §11 de la spec (« Ouvrir l'espace invités », « Fermer l'espace invités », « Adresse de contact », « Envoyer l'e-mail des invités ») et tout libellé visible contenant groupe/manager lié aux invités temporaires, reformulé neutre. Les clés de chaînes ne changent pas.

- [ ] **Step 1:** `grep -n -i "groupe\|manager" shared/src/commonMain/composeResources/values-fr/strings.xml` ; dresser la liste des clés liées aux invités temporaires et à l'espace (pas les autres usages du mot).
- [ ] **Step 2:** Reformuler dans les 7 langues.
- [ ] **Step 3:** `./gradlew :shared:desktopTest --tests '*I18n*'` → PASS ; relire le diff des 7 fichiers.
- [ ] **Step 4:** Commit `Neutral wording for temporary guest spaces`.

### Task 9: Portail web

**Files:**
- Modify: `webform/portal.js`, `webform/portal.css`, `webform/portal-i18n.js`, `webform/portal-sw.js` (version de cache), `webform/portal-common.js` si une fonction pure y va
- Modify: `firebase/tests/seed.mjs` (bénévole avec une liste ; personne avec listes seulement ; liste avec invitations ouvertes, un ajout en attente, un refusé ; liste passée ; invité avec accès nommés)
- Test: `firebase/tests/site-unit/portal-profiles.test.mjs`, nouveau `firebase/tests/site-unit/portal-guests.test.mjs`
- Copies `composeResources/files/webform/*`

**Interfaces:**
- Produces (fonctions pures exportées par `portal-common.js` pour les tests) :
  - `profilesLayout(available: string[]): { personal: string[], managed: boolean }` : « temporary » sort du sélecteur dès qu'un profil personnel existe.
  - `visibleLists(groups, now)` : filtre `visibleUntil > now` (repli `endsAt` pour le schema 1).
  - `inviteRemaining(invite, draft)` : places et accès restants après un brouillon.
- Rendu :
  - Section « Invités que je gère » sous les tuiles du profil personnel (bénévole et invité) ; page « Mes invités » sinon ; « Mon compte » sur cette page seulement.
  - Carte par liste (titre = nom inscrit, sinon lieu, sinon date ; sous-titre date longue et lieu ; bloc invitations avec « Places : {n} sur {max} » et accès).
  - Carte par invité (§6.4 : modules Accès, Solde, Contact d'urgence, grand bouton « Mon QR » ou « Entrée utilisée » ; badges ; « Modifier » absent si entrée utilisée ou en attente) ; 2 modules par ligne ≥ 640 px, 1 sinon.
  - « Modifier » → fenêtre prénom/nom → `sendRequest("TEMP_GUEST_NAME", {guestKey, firstName, lastName})`, attente du résultat comme `PROFILE`, messages pour `NOT_FOUND` et `ENTRY_USED`.
  - « Ajouter des invités » → un nom par ligne, accès à cocher, `inviteRemaining` bloque l'envoi ; écriture `addDoc`-équivalent `setDoc(doc(db,"orgs",org,"guestForms",nanoid()), {...})` avec les champs copiés du modèle (lu par `getDoc` sur `invite.formId`), `parentFormId`, `portalUid`, `requestKind:"ADD"`, `submissionJson` au format `GuestFormSubmissionCodec`, `status:"PENDING_REVIEW"`, `submittedAt`, `lastModified`.
  - « Retirer » → confirmation → réponse `requestKind:"REMOVE"`, `removeGuestKeys`.
  - Textes 5 langues : « Mes invités », « Invités que je gère », « Accès », « Contact d'urgence », « Mon QR », « Entrée utilisée », « Modifier le nom », « Ajouter des invités », « Retirer », « Places : {n} sur {max} », « En attente de validation », « Retrait demandé », « Refusé » ; « Mes groupes » et les libellés groupe/manager remplacés.
  - Correctif QR (§13) : `watchQr` ne ferme la fenêtre que si elle affiche ce profil (`openQrDialog` mémorise le profil affiché) ; `reloadAfterClaim` ne relance `watchQr(kind)` qu'une fois `pages[kind].state === "ready"`.

- [ ] **Step 1:** Tests site-unit : `profilesLayout` (aucun, bénévole seul, bénévole + listes, listes seules, bénévole + invité + listes) ; `visibleLists` (schema 1 et 2) ; `inviteRemaining` (total, par accès, zéro = sans limite) ; `qrDialogSurvivesDeniedReload` (faux `onSnapshot` refusé pour `temporary` pendant que la fenêtre montre le QR bénévole → fenêtre ouverte).
- [ ] **Step 2:** `cd firebase/tests && npm run test:site` → FAIL.
- [ ] **Step 3:** Implémenter, copier dans `composeResources`, monter la version du service worker.
- [ ] **Step 4:** `npm run test:site` → PASS.
- [ ] **Step 5:** `npm run preview` + `node seed.mjs` ; navigateur intégré : bénévole avec sa liste en bas ; listes seules ; ajout puis badge en attente ; retrait ; modification du nom ; liste passée absente ; fenêtre QR stable pendant 3 minutes après connexion ; clair/sombre ; mobile/ordinateur. Captures dans `.superpowers/sdd/2026-11-21-roles-invites/`.
- [ ] **Step 6:** Commit `Portal: guest cards, managed lists, invites and name edits`.

### Task 10: Docs et vérification finale

**Files:**
- Modify: `FIREBASE_SETUP.md` (règles v18, invitations), `CHANGELOG.md` (anglais), spec §« Ajustements » si un choix a bougé

- [ ] **Step 1:** Mettre à jour les docs ; noter dans le changelog que règles v18, hosting et Functions se déploient ensemble.
- [ ] **Step 2:** `./gradlew :shared:desktopTest :shared:compileKotlinDesktop :app:compileDebugKotlin` et `cd firebase/tests && npm test` → tout vert (hors test déjà rouge sur `main`).
- [ ] **Step 3:** `/usr/bin/git status` : aucun `.dylib` ni fichier hors périmètre indexé.
- [ ] **Step 4:** Commit `Docs for merged roles and portal invites`.
