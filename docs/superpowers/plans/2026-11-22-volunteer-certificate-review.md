# Validation des attestations par un admin : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une demande d'attestation attend la validation d'un admin ; l'admin la relit dans
l'app (toast dans la partie admin, carte en haut de la liste Bénévoles), corrige le texte, puis
valide (le serveur rend le PDF et l'envoie) ou refuse avec un motif.

**Architecture:** Le serveur vérifie la demande et la passe en `PENDING_REVIEW` sans rien
envoyer. L'app assemble le texte (assembleur Kotlin existant), l'admin l'édite, l'app réclame la
demande (`APPROVED` + `approvalId`) puis écrit `certificateApprovals/{approvalId}` ; un trigger
serveur réclame `APPROVED → PROCESSING`, rend le PDF du texte validé, l'envoie, met `DONE` et
supprime l'attestation validée. L'assembleur TypeScript est retiré. Règles v21.

**Tech Stack:** Kotlin Multiplatform (Compose, compose-rich-editor rc10), Firebase Functions
(TypeScript, pdf-lib, nodemailer), règles Firestore, JS vanilla, `node:test`.

**Spec:** `docs/superpowers/specs/2026-11-22-volunteer-certificate-review-design.md` (avenant ;
§11 précise le reste) et la spec d'origine `2026-11-22-volunteer-certificate-design.md`.

## Global Constraints

- Branche `feature/volunteer-certificate` (worktree `.claude/worktrees/volunteer-certificate`). Git : `/usr/bin/git`.
- Gradle : `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Jamais `./gradlew --stop`. « IR lowering » : relancer. `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode…` échoue déjà sur main.
- Ne jamais committer `*.dylib`, `*.so`, `*.dll` modifiés par le build, ni `firebase/tests/firebase.json`. Pas de `Co-Authored-By` ni de mention de Claude.
- L'émulateur de prévisualisation du propriétaire peut occuper les ports par défaut : ne jamais l'arrêter ; décaler les ports par le `firebase/tests/firebase.json` ignoré, puis le restaurer.
- Après tout changement dans `webform/`, recopier à l'identique dans `shared/src/commonMain/composeResources/files/webform/` ; après tout changement TS, reconstruire et committer les deux `functions/lib/index.js` ; incrémenter les `?v=` et la version du cache du service worker si le portail change.
- Textes : pas de tiret cadratin, pas de tournures artificielles ; app dans les 7 langues (`scripts/check-i18n.py`), portail en fr, en, es, de, it avec « tu ».
- Statuts de la demande `CERTIFICATE_EMAIL` : `PENDING` → (serveur) `PENDING_REVIEW` ou `REJECTED` ; (appareil) `PENDING_REVIEW` → `APPROVED` (+ `approvalId`) ou `REJECTED` (`reason: "REFUSED"`, `note` ≤ 300) ; (serveur) `APPROVED` → `PROCESSING` → `DONE`, ou retour `PENDING_REVIEW` avec `reason` (`SEND_FAILED`, `INVALID`, `NO_ACCESS`, `NO_EMAIL`).
- « Envoi bloqué » : `APPROVED` ou `PROCESSING` depuis plus de 5 minutes (`approvedAt` / `processingAt`).
- Limites : `body` + `footer` en JSON ≤ 200 000 caractères ; `signer.imageBase64` ≤ 200 000 caractères ; `note` ≤ 300 caractères.
- Toast et carte : **seulement dans la partie admin**, jamais sur l'accueil, la billetterie, la caisse.
- `volunteer.id` n'apparaît jamais dans `certificateApprovals`, `portalRequests` ni le portail.
- Aucun e-mail au bénévole en cas de refus.

## Review Focus

1. Deux admins valident la même demande presque en même temps : un seul `APPROVED`, un seul mail ; le second voit « Déjà validée ». Test : `secondApprovalLoses` (Tâche 4) et `sends once for two approvals` (Tâche 2).
2. Le bénévole a changé d'adresse ou perdu l'accès au portail entre la demande et la validation : l'envoi part vers l'adresse actuelle de la fiche, ou la demande revient en `PENDING_REVIEW` avec `NO_ACCESS`/`NO_EMAIL`, jamais vers l'ancienne. Test : `uses the profile address at send time` (Tâche 2).
3. L'admin vide tout le texte avant de valider : le bouton Valider est désactivé si le corps résolu est vide. Test : `cannotApproveAnEmptyText` (Tâche 5).
4. Une demande arrive pendant qu'on est sur la caisse : aucun toast hors admin, la carte l'affiche en entrant dans l'admin. Test : `toastHostIsOnlyMountedInAdmin` (Tâche 5, test de structure ou capture).
5. Le serveur meurt pendant l'envoi : la carte montre « Envoi bloqué » après 5 minutes et « Réessayer » remet la demande à valider. Test : `staleApprovalIsRetryable` (Tâche 4).

---

### Task 1 : le serveur met la demande en attente et l'assembleur TS disparaît

**Files:**
- Modify: `webform/functions/src/requests.ts` (`sendCertificate` → `reviewCertificate`), `webform/functions/src/index.ts` (exports)
- Delete: `webform/functions/src/certificate.ts` parties d'assemblage (`assemble`, défauts, dates, catégories), `webform/functions/test/certificate.test.mjs`, `webform/functions/test/certificate-default-fr.json`
- Move: `webform/functions/test/certificate-cases.json` → `shared/src/desktopTest/resources/certificate-cases.json` (et adapter `SharedCertificateCasesTest`, `CertificateSnapshotTest` : le snapshot TS n'existe plus, supprimer ce test ou le transformer en test Kotlin des défauts déjà couverts par `CertificateTemplateTest`)
- Test: `firebase/tests/functions/certificate.test.mjs`

**Interfaces:**
- Produces : `reviewCertificate(db, orgId, request): Promise<RequestResult | "REVIEW">` ; une demande valide finit en `status: "PENDING_REVIEW"` avec `reviewAt` (horodatage serveur), sans mail. Les refus existants restent (`NOT_ALLOWED`, `NO_TEMPLATE`, `NO_ACCESS`, `NO_EMAIL`, `NOTHING_TO_CERTIFY`, `INVALID`). La vérification « au moins un job dans la sélection » se fait sans l'assembleur : au moins un job passé (jour ≤ aujourd'hui, Europe/Zurich), dans `from..to`, d'un type coché, date valide et `jobTypeName` non vide.
- `certificate.ts` ne garde que ce que les Tâches 2 et suivantes utilisent : `parseTemplate` (pour lire le modèle publié) et les types ; tout le reste est supprimé, avec ses exports dans `index.ts`.

- [ ] **Step 1 :** adapter les tests émulateur : `puts a valid request in review without mail` (statut `PENDING_REVIEW`, `reviewAt` présent, aucun mail capturé, ni `volunteer.id` dans la demande) ; les tests de refus existants inchangés ; supprimer `sends a certificate` (remplacé à la Tâche 2).
- [ ] **Step 2 :** `cd firebase/tests && npm run test:functions` (ports décalés si besoin). Attendu : FAIL sur le nouveau test.
- [ ] **Step 3 :** implémenter, supprimer le code mort, déplacer les cas partagés et adapter les tests Kotlin.
- [ ] **Step 4 :** `npm --prefix webform/functions run typecheck && npm --prefix webform/functions run build && npm --prefix webform/functions test`, `npm run test:functions`, `:shared:desktopTest --tests '*portal.certificate*'`. Attendu : PASS.
- [ ] **Step 5 :** commit `Certificate requests wait for an admin`.

### Task 2 : le serveur envoie une attestation validée

**Files:**
- Modify: `webform/functions/src/requests.ts` ou nouveau `webform/functions/src/approvals.ts`, `webform/functions/src/index.ts` (trigger `onCertificateApprovalCreated` sur `orgs/{orgId}/certificateApprovals/{approvalId}`, région et options comme `onPortalRequestWritten`), `webform/functions/src/mail.ts` si besoin
- Test: `firebase/tests/functions/certificate-approval.test.mjs`, `webform/functions/test/approvals.test.mjs` (validation pure)

**Interfaces:**
- Consumes : `renderCertificatePdf`, `buildCertificateMail`, `certificateFileName`, `safeRecipient`, `isOwner`.
- Produces : document `certificateApprovals/{approvalId}` = `{ requestId: string, body: ResolvedParagraph[], footer: ResolvedParagraph[], title: string, signer: { name: string, title: string, imageBase64: string }, approvedBy: string, approvedAt: timestamp }` ; `parseApproval(raw: unknown): Approval | null` (null si un champ manque, a le mauvais type, ou dépasse les limites des Global Constraints).
- Déroulement du trigger : lire l'approbation ; transaction sur `portalRequests/{requestId}` : continuer seulement si `status == "APPROVED"` et `approvalId == approvalId`, puis `status: "PROCESSING"`, `processingAt` ; sinon supprimer l'approbation et s'arrêter. Relire la fiche (`portalUid == request.uid`, `isOwner`), adresse via `safeRecipient` ; logo et réglages d'e-mail depuis `portalMail/certificate` (modèle absent → réglages d'e-mail par défaut de la langue, logo vide). Rendre et envoyer. Fin : `DONE`, `processedAt`. Échec (approbation invalide → `INVALID`, fiche → `NO_ACCESS`/`NO_EMAIL`, envoi → `SEND_FAILED`) : `status: "PENDING_REVIEW"`, `reason`. Dans tous les cas, supprimer l'approbation. Logs : uid, type, raison seulement.

- [ ] **Step 1 : tests** : `sends the approved text` (un mail, PDF présent, le texte extrait ou le JSON transmis au rendu contient une phrase écrite par l'admin et absente du modèle — exposer un point de test ou vérifier via la capture) ; `sends once for two approvals` (deux approbations pour la même demande, une seule avec le bon `approvalId` : un seul mail) ; `ignores an approval when the request is not APPROVED` ; `uses the profile address at send time` (adresse de la fiche changée après la demande : le mail part vers la nouvelle) ; `send failure goes back to review` (transport en échec : `PENDING_REVIEW`, `SEND_FAILED`, approbation supprimée) ; `rejects an oversized approval` (`INVALID`) ; `parseApproval` unitaire (champs manquants, mauvais types, limites).
- [ ] **Step 2 :** lancer. Attendu : FAIL.
- [ ] **Step 3 :** implémenter.
- [ ] **Step 4 :** relancer les tests functions (unitaires et émulateur). Attendu : PASS.
- [ ] **Step 5 :** commit `Send a certificate once an admin approves it`.

### Task 3 : règles v21

**Files:**
- Modify: `firebase/firestore.rules`, `shared/src/commonMain/composeResources/files/firestore.rules` (identiques), `NOCTULIST_FIRESTORE_RULES_VERSION`
- Test: `firebase/tests/rules/portal-certificate.test.mjs`

**Interfaces:**
- Produces : (a) un compte du portail ne peut pas réécrire `{uid}_CERTIFICATE_EMAIL` quand la demande existante est `PENDING_REVIEW`, `APPROVED` ou `PROCESSING` ; il peut toujours lire sa demande (états et `note`). (b) un membre peut faire passer une demande `CERTIFICATE_EMAIL` de `PENDING_REVIEW` à `APPROVED` (champs ajoutés : `approvalId`, `approvedAt`, `processingDevice` éventuel) ou à `REJECTED` (`reason == "REFUSED"`, `note` chaîne ≤ 300, `processedAt`), et de `APPROVED`/`PROCESSING` à `PENDING_REVIEW` (Réessayer) ; regarder comment les membres écrivent aujourd'hui `portalRequests` (réclamations des appareils) et rester cohérent. (c) `certificateApprovals/{id}` : `create` par un membre seulement si `get(portalRequests/requestId).data.status == "APPROVED"` et `approvalId == id` ; `read`, `delete` par les membres ; aucun accès portail.

- [ ] **Step 1 : tests** : `portal cannot rewrite a request in review` ; `portal reads its refused request and note` ; `member approves a request in review` ; `member refuses with a note` ; `note over 300 is refused` ; `member resets a stale approval to review` ; `approval needs an APPROVED request with the same id` ; `portal cannot read certificateApprovals`.
- [ ] **Step 2 :** `npm run test:rules`. Attendu : FAIL.
- [ ] **Step 3 :** modifier les deux copies, version 21.
- [ ] **Step 4 :** relancer `npm run test:rules` et `:shared:desktopTest --tests '*FirestoreCodecAndRulesShapeTest*'`. Attendu : PASS.
- [ ] **Step 5 :** commit `Rules v21: admin review of certificates`.

### Task 4 : demandes à valider côté appareil

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalDocuments.kt` (statuts `STATUS_PENDING_REVIEW`, `STATUS_APPROVED`, champs `note`, `reason`, `processedAt`, `approvalId`, `approvedAt`, `reviewAt` acceptés pour `CERTIFICATE_EMAIL`)
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/certificate/CertificateReview.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt` (exposer les demandes, actions), `EventManagerViewModel.kt` (flux et actions pour l'UI)
- Test: `shared/src/commonTest/.../portal/certificate/CertificateReviewTest.kt`, `VolunteerPortalServiceTest`

**Interfaces:**
- Produces :
  - `data class CertificateReviewRequest(val requestId: String, val portalUid: String, val email: String, val fromDay: Int, val toDay: Int, val jobTypes: List<String>, val requestedAt: Long, val status: String, val stuck: Boolean, val lastReason: String)` (`stuck` = `APPROVED`/`PROCESSING` depuis plus de 5 minutes)
  - `fun certificateReviewRequests(docs: List<Pair<String, Map<String, Any?>>>, now: Long): List<CertificateReviewRequest>` (pur ; garde `PENDING_REVIEW`, `APPROVED`, `PROCESSING` ; tri par `requestedAt` croissant)
  - `fun certificateDraft(template: CertificateTemplate, association: String, volunteer: Volunteer, jobs: List<Job>, configs: List<JobTypeConfig>, request: CertificateReviewRequest, now: Long): CertAssembly?` (même conversion volontaire → `CertPerson`/`CertJob` que l'aperçu ; sélection de la demande)
  - Sur `VolunteerPortalService` : `val certificateRequests: StateFlow<List<CertificateReviewRequest>>` (alimenté par l'écoute existante de `portalRequests`) ; `suspend fun approveCertificate(requestId: String, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner, approvedBy: String): ApproveResult` avec `enum class ApproveResult { SENT_TO_SERVER, ALREADY_HANDLED, TOO_LARGE, FAILED }` (réclame `PENDING_REVIEW → APPROVED` avec `approvalId` aléatoire via `claimDocument`, puis écrit l'approbation) ; `suspend fun refuseCertificate(requestId: String, note: String): Boolean` ; `suspend fun retryCertificate(requestId: String): Boolean` (seulement si `stuck`).
  - Les filtres « les appareils ne réclament jamais CERTIFICATE_EMAIL » dans la boucle des demandes restent ; les nouvelles actions passent par leurs propres fonctions.

- [ ] **Step 1 : tests** : `listsRequestsInReviewOldestFirst` ; `staleApprovalIsRetryable` (`APPROVED` depuis 6 min → `stuck`, `retryCertificate` remet `PENDING_REVIEW`) ; `approveWritesClaimThenApproval` (demande `APPROVED` avec `approvalId`, approbation écrite avec ce même id, sans `volunteer.id`) ; `secondApprovalLoses` (la demande n'est plus `PENDING_REVIEW` → `ALREADY_HANDLED`, aucune approbation écrite) ; `tooLargeIsRefusedLocally` ; `refuseWritesReasonAndNote` (note tronquée à 300) ; `draftUsesTheRequestSelection` (période et types de la demande, jobs futurs exclus) ; `parserAcceptsReviewFields` (cas ajoutés à la validation partagée si `SharedValidationCasesTest` lit encore `validation-cases.json`).
- [ ] **Step 2 :** lancer. Attendu : FAIL.
- [ ] **Step 3 :** implémenter.
- [ ] **Step 4 :** relancer, puis `:shared:desktopTest` complet et `:shared:compileDebugKotlinAndroid`. Attendu : PASS.
- [ ] **Step 5 :** commit `Review certificate requests on devices`.

### Task 5 : carte, fenêtre de relecture et toast admin

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/certificate/CertificateReviewCard.kt`, `CertificateReviewDialog.kt`, `CertificateRequestToastHost.kt`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/screens/VolunteerScreen.kt` (carte dans `headerContent()` ou juste après, pour les trois mises en page `HEADER_PINNED`, `STICKY_FILTERS` et la mise en page par défaut), les hôtes de la partie admin pour le toast (desktop : là où `AppRootContent.desktop.kt` affiche les onglets `AdminTab`, vers la ligne 648 ; Android : là où `AppRootContent.kt` affiche l'écran admin qui contient `VolunteerScreen`, vers la ligne 2576), les 7 `strings.xml`
- Test: `shared/src/desktopTest/.../ui/certificate/CertificateReviewDialogTest.kt` (logique sans UI)

**Interfaces:**
- Consumes : Tâche 4 (`certificateRequests`, `certificateDraft`, actions) ; `CertificateDocHtml`, `RichTextState`, barre d'outils et `CertificatePreview` de l'éditeur de modèle (réutiliser, sans les boutons Variable et Accord).
- Contenu (spec avenant §4) :
  - Carte (modèle `GuestFormPendingCard`) : titre « Attestations à valider » avec le nombre ; une ligne par demande : nom du bénévole (via `portalUid`), date de la demande, période ; « Envoi en cours » pour `APPROVED`/`PROCESSING` récents ; « Envoi bloqué » + « Réessayer » si `stuck` ; « Fiche introuvable » si aucun bénévole ne correspond (seul « Refuser » dans la fenêtre). Rien si la liste est vide. Visible seulement si l'option attestation est active.
  - Fenêtre (modèle `GuestFormReviewDialog`, même largeur que l'éditeur de modèle) : en-tête (bénévole, adresse, date de la demande, période, types) ; texte de l'attestation dans un éditeur riche unique (corps), le pied de page dans un second champ ; aperçu A4 à droite (desktop ≥ 900 dp) ou onglet (étroit) ; « Refuser » (petite fenêtre, motif ≤ 300, compteur) ; « Valider et envoyer » (désactivé si le corps résolu est vide ; pendant l'action, indicateur ; résultat : fermer et toast court « Envoyée au serveur », ou message « Déjà traitée par un autre appareil », « Texte trop long », « Échec, réessaie ») ; « Fermer » demande confirmation si le texte a changé. `approvedBy` = adresse du compte Google de l'appareil si connue, sinon le nom de l'appareil.
  - Toast (`CertificateRequestToastHost`) : même apparence que `GuestFormArrivalToastHost`, monté seulement dans les hôtes admin ci-dessus ; premier affichage = mémoriser les demandes existantes sans toast ; ensuite un toast par nouvelle demande `PENDING_REVIEW` (« {nom} demande une attestation »). Démonté hors admin : pas de toast différé.

- [ ] **Step 1 :** chaînes (7 langues) et `python3 scripts/check-i18n.py`. Attendu : OK.
- [ ] **Step 2 : tests** (logique) : `cannotApproveAnEmptyText` ; `editedTextIsWhatIsSent` (le corps modifié dans l'éditeur, relu par `CertificateDocHtml.fromHtml`, est passé à `approveCertificate`) ; `unknownVolunteerOnlyAllowsRefusal` ; `toastHostIsOnlyMountedInAdmin` (vérification par lecture : aucun appel hors des deux hôtes admin ; ou test de structure si possible).
- [ ] **Step 3 :** implémenter, compiler desktop et Android.
- [ ] **Step 4 :** captures sans écran (ImageComposeScene, comme pour l'éditeur) de la carte et de la fenêtre, 1280×900 et 400×860, dans le scratchpad ; les regarder et corriger.
- [ ] **Step 5 :** `:shared:desktopTest` complet. Commit `Review certificates from the volunteer list`.

### Task 6 : portail

**Files:**
- Modify: `webform/portal.js`, `webform/portal-certificate.js`, `webform/portal-i18n.js`, `webform/portal-sw.js`, `webform/portal.html`, copies dans `composeResources`
- Test: `firebase/tests/site-unit/portal-certificate.test.mjs`

**Interfaces:**
- Produces : `certificateState(request, now): "none" | "waiting" | "review" | "sent" | "refused" | "failed"` dans `portal-certificate.js` (`PENDING`/`PROCESSING` récents avant revue → `waiting` ; `PENDING_REVIEW`/`APPROVED`/`PROCESSING` après revue → `review` ; `DONE` → `sent` ; `REJECTED` + `REFUSED` → `refused` ; autres `REJECTED` → `failed`).
- Textes (5 langues, « tu ») : review « Ta demande attend la validation de l'équipe. » ; sent « Ton attestation a été envoyée à {email}. » ; refused « Ta demande a été refusée. » puis le motif sur sa propre ligne s'il existe (texte, jamais HTML). Pas de bouton d'envoi en `review` ; après `refused`, nouvelle demande possible après le délai de 10 minutes.

- [ ] **Step 1 : tests** des six états, et `refused` avec et sans note.
- [ ] **Step 2 :** `npm run test:site`. Attendu : FAIL.
- [ ] **Step 3 :** implémenter.
- [ ] **Step 4 :** relancer. Attendu : PASS. Vérification navigateur sur les émulateurs de ce worktree (ports décalés) : demande → « attend la validation » ; refus simulé par l'émulateur → message et motif. Captures dans le scratchpad.
- [ ] **Step 5 :** commit `Show certificate review states in the portal`.

### Task 7 : documentation et vérification complète

**Files:**
- Modify: `CHANGELOG.md` (l'entrée attestation dit que l'équipe valide avant l'envoi ; règles v21), `FIREBASE_SETUP.md` (règles v21, nouvelle fonction `onCertificateApprovalCreated`), spec d'origine §8 (renvoi vers l'avenant)

- [ ] **Step 1 :** docs.
- [ ] **Step 2 :** tout relancer : `:shared:desktopTest`, compilations desktop et Android, functions (typecheck, build, tests), `cd firebase/tests && npm test`, `check-i18n`. Attendu : vert sauf l'échec connu.
- [ ] **Step 3 :** de bout en bout dans les émulateurs : demande depuis le portail → `PENDING_REVIEW` sans mail ; approbation écrite par le vrai code Kotlin (`approveCertificate` sur un gateway pointé vers l'émulateur, ou script qui reproduit exactement ses deux écritures, à préciser dans le rapport) avec une phrase ajoutée par l'admin → mail capturé, PDF rendu en PNG, phrase présente ; refus → portail affiche le motif.
- [ ] **Step 4 :** commit `Docs for certificate review`.
