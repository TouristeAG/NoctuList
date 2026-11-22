# Attestation de bénévolat : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le bénévole demande depuis le portail une attestation PDF (période + types de job), la
Cloud Function l'assemble à partir d'un modèle rédigé par l'admin dans un éditeur de texte riche,
et l'envoie par le Gmail de l'association.

**Architecture:** Un format de modèle unique (JSON, spec §7 et §11), un assembleur écrit deux fois
(Kotlin pour l'aperçu, TypeScript pour le serveur) tenu identique par des cas partagés, un rendu
PDF pdf-lib côté functions. Les appareils stockent le modèle sur disque comme le logo, le
synchronisent en réglage d'institution, le publient dans `portalMail/certificate`, et ajoutent
`certificate.types` à la page. Nouveau type de demande `CERTIFICATE_EMAIL`, traité par le serveur
seul. Règles v20.

**Tech Stack:** Kotlin Multiplatform (Compose 1.7.3, kotlinx.serialization), compose-rich-editor,
Firebase Functions (TypeScript, esbuild, nodemailer), pdf-lib + @pdf-lib/fontkit, règles
Firestore, JS vanilla, `node:test`.

**Spec:** `docs/superpowers/specs/2026-11-22-volunteer-certificate-design.md` (lire §11 en
priorité : il précise §5 à §7).

## Global Constraints

- Branche `feature/volunteer-certificate` (worktree `.claude/worktrees/volunteer-certificate`). Git : `/usr/bin/git` (le git de `/usr/local/bin` ne lit pas les worktrees).
- Gradle : `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Ne jamais lancer `./gradlew --stop`. Une erreur « IR lowering » se relance telle quelle. `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode…` échoue déjà sur main.
- Ne jamais committer `shared/src/*/resources/*.dylib`.
- Commits sans `Co-Authored-By` Claude ni mention de Claude.
- Textes visibles : pas de tiret cadratin, pas de tournures artificielles, pas de sous-titre qui répète le titre.
- Chaînes de l'app dans les 7 langues : `values`, `values-fr`, `values-es`, `values-hi`, `values-la`, `values-zh-rCN`, `values-zh-rTW` (`scripts/check-i18n.py` doit passer). Portail : fr, en, es, de, it dans `webform/portal-i18n.js`.
- Langues de l'attestation : `fr`, `en`, `de`, `it`, `es`. Défaut `fr`.
- `volunteer.id` n'apparaît jamais dans le PDF, l'e-mail, `portalRequests`, `portalMail/certificate` ni `volunteerPortal/{uid}`.
- Jour d'un job = date civile `Europe/Zurich` de `Job.date`, en entier `AAAAMMJJ`.
- Délai entre deux demandes `CERTIFICATE_EMAIL` : 10 minutes.
- Réglages : `volunteer_portal_certificate_enabled` (booléen, absent = `false`) et `volunteer_portal_certificate_template` (JSON), tous deux dans `FIREBASE_ONLY_KEYS`.
- Signature : 600 px de large max, base64 ≤ 200 000 caractères. Modèle complet ≤ 400 000 caractères (limite du logo).
- Rien n'est envoyé sans demande du bénévole. Aucun contenu d'attestation dans Firestore ni dans les logs.

## Review Focus

1. Un type de job renommé ou supprimé après coup (job dont `jobTypeName` n'a plus de config) : il doit tomber dans OTHER avec une description vide, sans planter ni disparaître. Test : cas partagé `deleted-type` (Tâche 2).
2. Un très long texte ou beaucoup de rôles, qui dépasse une page : le PDF doit passer à la page suivante sans couper une ligne, et le pied de page doit se répéter. Test : `renders a two-page certificate` (Tâche 4).
3. Des caractères hors de la police (emoji dans un nom de job, guillemets typographiques, « ŒŸ ») : le rendu ne doit pas lever d'exception, le glyphe manquant est remplacé. Test : `survives characters missing from the font` (Tâche 4).
4. Un bénévole sans aucun job dans la période choisie, ou qui ne coche que des types absents de la période : le portail bloque l'envoi, et le serveur refuse avec `NOTHING_TO_CERTIFY` sans envoyer d'e-mail. Tests : cas partagé `nothing` (Tâche 2), `rejects an empty selection` (Tâche 5).
5. L'option est coupée pendant qu'une demande attend : le serveur refuse avec `NOT_ALLOWED` et l'appareil ne la rejette pas comme `INVALID`. Tests : `refuses when the option is off` (Tâche 5), `leaves CERTIFICATE_EMAIL to the server` (Tâche 8).

---

### Task 1 : modèle Kotlin, catégories et textes par défaut

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/certificate/CertificateTemplate.kt`
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/certificate/CertificateDefaults.kt`
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/certificate/CertificateDates.kt`
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/portal/certificate/CertificateTemplateTest.kt`

**Interfaces:**
- Produces (package `com.eventmanager.app.portal.certificate`) :
  - `@Serializable enum class CertAlign { LEFT, CENTER, RIGHT, JUSTIFY }`, `enum class CertSize { SMALL, NORMAL, LARGE, TITLE }`
  - `@Serializable data class CertRun(val text: String = "", @SerialName("var") val variable: String = "", val gender: List<String> = emptyList(), val b: Boolean = false, val i: Boolean = false, val u: Boolean = false)` (exactement un de `text` / `variable` / `gender` est utilisé ; `gender` = [masculin, féminin, non-binaire])
  - `@Serializable data class CertParagraph(val align: CertAlign = CertAlign.LEFT, val size: CertSize = CertSize.NORMAL, val bullet: Boolean = false, val runs: List<CertRun> = emptyList())`
  - `@Serializable data class CertBlock(val one: List<CertParagraph> = emptyList(), val many: List<CertParagraph> = emptyList(), val manyOneRole: List<CertParagraph> = emptyList(), val line: List<CertParagraph> = emptyList())`
  - `@Serializable data class CertSigner(val name: String = "", val title: String = "", val imageBase64: String = "")`
  - `@Serializable data class CertMail(val fromName: String = "", val fromAddress: String = "", val replyTo: String = "", val subject: String = "", val body: String = "")`
  - `@Serializable data class CertificateTemplate(val version: Int = 1, val language: String = "fr", val place: String = "", val signer: CertSigner = CertSigner(), val mail: CertMail = CertMail(), val blocks: Map<String, CertBlock> = emptyMap())` avec `fun toJson(): String`, `companion fun parse(json: String): CertificateTemplate?` (null si illisible ; `ignoreUnknownKeys = true`)
  - Clés de `blocks` : `header`, `title`, `intro`, `MANDATE`, `SHIFT`, `PHOTO`, `GRAPHIC_EVENT`, `GRAPHIC_ASSOCIATION`, `OTHER`, `MEETING`, `closing`, `dateLine`, `footer`
  - `enum class CertificateCategory { MANDATE, SHIFT, PHOTO, GRAPHIC_EVENT, GRAPHIC_ASSOCIATION, OTHER, MEETING }` (l'ordre déclaré est l'ordre du document) et `fun certificateCategoryOf(config: JobTypeConfig?): CertificateCategory` (règles spec §5, `isOrionJob` d'abord, `null` → OTHER)
  - `object CertificateDefaults { val LANGUAGES = listOf("fr","en","de","it","es"); fun template(language: String, associationName: String): CertificateTemplate }` (langue inconnue → `en`)
  - `object CertificateDates { fun dayOf(millis: Long): Int; fun longDate(day: Int, lang: String): String; fun monthYear(day: Int, lang: String): String; fun period(firstDay: Int, lastDay: Int, lang: String): String }`

- [ ] **Step 1 : tests** dans `CertificateTemplateTest` :
  - `roundTripsJson` : `CertificateTemplate.parse(CertificateDefaults.template("fr","Les Noctambules").toJson()) == CertificateDefaults.template("fr","Les Noctambules")`, et le JSON contient `"var":"firstName"`.
  - `parseRejectsGarbage` : `parse("{")` et `parse("")` renvoient `null`.
  - `categoryRules` : `isOrionJob=true` + `isShiftJob=true` → MANDATE ; `isShiftJob` + `DEFAULT_SHIFT` → SHIFT ; `MEETING` → MEETING ; `PHOTOGRAPHER_VIDEOGRAPHER` → PHOTO ; `GRAPHIC_DESIGNER_EVENT` → GRAPHIC_EVENT ; `GRAPHIC_DESIGNER_ASSOCIATION` → GRAPHIC_ASSOCIATION ; `isShiftJob=false`, non Orion → OTHER ; `null` → OTHER.
  - `everyLanguageHasEveryBlock` : pour chaque langue de `LANGUAGES`, les 13 clés existent ; `SHIFT` et `OTHER` ont `one`, `many`, `manyOneRole` et `line` non vides ; `MANDATE`, `GRAPHIC_ASSOCIATION` et les blocs fixes ont `one` non vide.
  - `dates` : `longDate(20260312,"fr") == "12 mars 2026"`, `longDate(20260301,"fr") == "1er mars 2026"`, `longDate(20260312,"en") == "12 March 2026"`, `longDate(20260312,"de") == "12. März 2026"`, `longDate(20260312,"it") == "12 marzo 2026"`, `longDate(20260312,"es") == "12 de marzo de 2026"`.
  - `periods` : `period(20260305,20261020,"fr") == "entre mars et octobre 2026"`, `period(20250405,20260820,"fr") == "entre avril 2025 et août 2026"`, `period(20260302,20260328,"fr") == "en mars 2026"`, `period(20260305,20261020,"en") == "between March and October 2026"`, `period(20260302,20260328,"de") == "im März 2026"`, `period(20260305,20261020,"es") == "entre marzo y octubre de 2026"`, `period(20260302,20260328,"it") == "a marzo 2026"`.
  - `dayOfUsesGeneva` : `dayOf` de `2026-03-12T23:30:00Z` == `20260313` (UTC+1).
- [ ] **Step 2 :** `JAVA_HOME=… ./gradlew :shared:desktopTest --tests 'com.eventmanager.app.portal.certificate.CertificateTemplateTest'`. Attendu : échec de compilation (classes absentes).
- [ ] **Step 3 :** implémenter. Textes par défaut : ceux de la spec §5 et §6 pour le français (intro avec accord `engagé/engagée/engagéx`, conclusion sans pronom à accorder : « Nous remercions chaleureusement {firstName} pour son engagement. »), traduits sobrement pour les 4 autres langues. Défauts de `mail` : objet et corps de la spec §6, `fromName` = nom de l'association. `dateLine` par défaut : « Fait à {place}, le {today} ». Les blocs `SHIFT.many` et `OTHER.many` se terminent par un paragraphe qui ne contient que `{roles}`. `line` par défaut : rôle en gras, « : », description.
- [ ] **Step 4 :** relancer le test. Attendu : PASS.
- [ ] **Step 5 :** commit `Certificate template model and default texts`.

### Task 2 : assembleur Kotlin et cas partagés

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/certificate/CertificateAssembler.kt`
- Create: `webform/functions/test/certificate-cases.json`
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/portal/certificate/SharedCertificateCasesTest.kt`

**Interfaces:**
- Consumes : Tâche 1.
- Produces :
  - `data class CertJobType(val name: String, val category: CertificateCategory, val description: String)`
  - `data class CertPerson(val firstName: String, val lastName: String, val gender: String)` (`gender` = nom d'enum `Gender` ou `""`)
  - `data class CertJob(val jobTypeName: String, val day: Int)`
  - `data class CertSelection(val fromDay: Int, val toDay: Int, val todayDay: Int, val jobTypes: Set<String>)`
  - `data class ResolvedRun(val text: String, val b: Boolean, val i: Boolean, val u: Boolean)`, `data class ResolvedParagraph(val align: CertAlign, val size: CertSize, val bullet: Boolean, val runs: List<ResolvedRun>)`
  - `data class CertAssembly(val body: List<ResolvedParagraph>, val footer: List<ResolvedParagraph>)`
  - `object CertificateAssembler { fun assemble(template: CertificateTemplate, association: String, person: CertPerson, types: List<CertJobType>, jobs: List<CertJob>, selection: CertSelection): CertAssembly?; fun displayName(raw: String): String; fun genderIndex(gender: String): Int }` ; `null` = rien à certifier.

Règles (spec §5 et §11) que l'assembleur applique, dans cet ordre :
1. Jobs gardés : `day in fromDay..toDay`, `day <= todayDay`, `jobTypeName in jobTypes`. Aucun → `null`.
2. Type inconnu de `types` → `CertJobType(name, OTHER, "")`.
3. Variables globales : `firstName`/`lastName` via `displayName` (casse corrigée seulement sans minuscule, majuscule après début, espace, `-`, `'`), `association`, `place`, `today` = `longDate(todayDay)`, `period`/`start`/`end` = premier et dernier jour gardés (`period(...)`, `monthYear(...)`), `date` = `longDate` du seul jour quand il n'y a qu'un job.
4. Blocs dans l'ordre : `header`, `title`, `intro` (`one` si un seul job au total, sinon `many`), une section par catégorie présente dans l'ordre de l'enum, `closing`, `dateLine`. `footer` est résolu à part dans `CertAssembly.footer` : le rendu le place en bas de chaque page (Tâche 4).
5. Par catégorie :
   - MANDATE : un `one` par type de mandat, ordre de premier jour ; `period` local = jours de ce type ; `role`/`description` du type.
   - SHIFT, OTHER : 1 job → `one` (`date`, `role`, `description`) ; plusieurs jobs d'un seul type → `manyOneRole` (`count`, `period` local, `role`, `description`) ; plusieurs types → `many` (`count`, `period` local), où le paragraphe `{roles}` devient une ligne `line` par type, triés par nombre décroissant puis par nom, avec `role`, `description`, `count` du type ; une ligne dont `description` est vide perd le run de description et le séparateur qui le précède (le constructeur choisit comment détecter le séparateur ; le cas `empty-description` le fixe).
   - MEETING, PHOTO, GRAPHIC_EVENT : `one` si 1 job, sinon `many` (`count`, `period` local).
   - GRAPHIC_ASSOCIATION : toujours `one`.
6. Accord : `gender[genderIndex(person.gender)]`, `MALE`→0, `FEMALE`→1, tout le reste→2.
7. Variable inconnue → texte vide. Paragraphe dont tous les runs résolus sont vides ou blancs → supprimé. Runs contigus de même style fusionnés.

- [ ] **Step 1 : cas partagés.** `certificate-cases.json` = liste de `{ "name", "language", "association", "place", "person", "types", "jobs", "selection", "expect" }`, avec le modèle par défaut de la langue. `expect` vaut `null` ou `{ "body": [textes], "footer": [textes] }` (runs concaténés par paragraphe). Un cas `runs` vérifie aussi les styles. Cas obligatoires : `single-shift` (fr, FEMALE : intro `one`, phrase avec « 12 mars 2026 », aucun nombre), `many-shifts-one-role` (« 8 shifts », « en tant que Bar », pas de liste), `many-shifts-many-roles` (lignes triées Bar(8) puis Entrée(6)), `mandate-only` (« mandat de Co-présidence », jamais le mot « shift »), `mandate-one-month` (« en janvier 2025 »), `mandate-and-shifts` (mandat avant shifts), `meetings-only`, `meeting-excluded` (le type réunion non coché ne laisse aucune trace), `deleted-type` (job sans type connu → bloc OTHER), `empty-description`, `gender-male`, `gender-nonbinary` (« engagéx »), `gender-unknown` (`""` → forme 2), `period-trimmed` (sélection 2019→2026, jobs 2024–2025 : période 2024→2025), `future-excluded` (job après `todayDay` ignoré), `nothing` (`expect: null`), `caps-name` (« DUPONT » → « Dupont », « van der Berg » inchangé), `english` (un cas en `en`), `runs` (le rôle en gras dans une ligne).
- [ ] **Step 2 :** `SharedCertificateCasesTest` remonte les dossiers jusqu'à trouver `webform/functions/test/certificate-cases.json` (comme `SharedValidationCasesTest.casesFile`), et pour chaque cas compare `assemble(...)` aux attendus (`assertEquals(expected, actual, case.name)`).
- [ ] **Step 3 :** lancer `:shared:desktopTest --tests '*SharedCertificateCasesTest*'`. Attendu : FAIL.
- [ ] **Step 4 :** implémenter `CertificateAssembler`.
- [ ] **Step 5 :** relancer. Attendu : PASS.
- [ ] **Step 6 :** commit `Assemble certificate texts in Kotlin, with shared cases`.

### Task 3 : assembleur TypeScript

**Files:**
- Create: `webform/functions/src/certificate.ts`
- Test: `webform/functions/test/certificate.test.mjs`

**Interfaces:**
- Consumes : `certificate-cases.json` (Tâche 2), format JSON du modèle (Tâche 1).
- Produces (exportés par `certificate.ts`, les mêmes noms et règles que la Tâche 2) :
  `type Category`, `categoryOf(config: { isOrionJob?: boolean; isShiftJob?: boolean; novaJobType?: string } | undefined): Category`,
  `parseTemplate(raw: unknown): CertificateTemplate | null`, `defaultTemplate(language: string, association: string): CertificateTemplate`,
  `dayOf(millis: number): number` (Intl, `Europe/Zurich`), `longDate`, `monthYear`, `period`,
  `displayName(raw: string): string`,
  `assemble(template, association, person, types, jobs, selection): { body: ResolvedParagraph[]; footer: ResolvedParagraph[] } | null`.
  Les textes par défaut sont ceux de la Tâche 1 : le test charge les défauts Kotlin indirectement par les cas ; un test supplémentaire compare `defaultTemplate("fr","X")` à un instantané JSON `webform/functions/test/certificate-default-fr.json`, que le test Kotlin `defaultsMatchSnapshot` (ajouté à `CertificateTemplateTest`) compare lui aussi, pour que les deux défauts restent identiques.
  Les fonctions exportées sont ajoutées à `index.ts` seulement si un trigger les utilise (Tâche 5).

- [ ] **Step 1 :** `certificate.test.mjs` importe `../lib/certificate.js` (build esbuild d'un second point d'entrée : ajouter `src/certificate.ts` → `lib/certificate.js` dans `build.mjs`, comme les autres modules testés, vérifier comment `validate.test.mjs` importe et faire pareil), et rejoue chaque cas de `certificate-cases.json` (`assert.deepEqual(actual, expect, name)`), plus les dates de la Tâche 1 (mêmes valeurs) et `defaultTemplate("fr","X")` égal à `certificate-default-fr.json`.
- [ ] **Step 2 :** écrire `certificate-default-fr.json` depuis Kotlin (test `defaultsMatchSnapshot` dans `CertificateTemplateTest` qui écrit le fichier s'il manque et le compare sinon) ; le committer.
- [ ] **Step 3 :** `npm --prefix webform/functions run build && npm --prefix webform/functions test`. Attendu : FAIL sur `certificate.test.mjs`.
- [ ] **Step 4 :** implémenter `certificate.ts`.
- [ ] **Step 5 :** relancer. Attendu : PASS (les autres tests restent verts).
- [ ] **Step 6 :** commit `Assemble certificate texts on the server`.

### Task 4 : rendu PDF

**Files:**
- Create: `webform/functions/src/certificatePdf.ts`
- Create: `webform/functions/assets/` (Nunito 400, 700, 400 italique, 700 italique + `OFL.txt`)
- Modify: `webform/functions/package.json` (dépendances `pdf-lib`, `@pdf-lib/fontkit`), `webform/functions/package-lock.json`
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/guestform/GuestFormSiteBundle.kt` (ajouter `functions/assets/*` à `files`)
- Test: `webform/functions/test/certificatePdf.test.mjs`

**Interfaces:**
- Consumes : `ResolvedParagraph` et le résultat de `assemble` (Tâche 3).
- Produces :
  - `layoutParagraphs(paragraphs: ResolvedParagraph[], width: number, measure: (text: string, bold: boolean, italic: boolean, size: number) => number): LaidLine[]`, avec `LaidLine = { x: number; size: number; gapBefore: number; bullet: boolean; justify: boolean; last: boolean; segments: { text: string; b: boolean; i: boolean; u: boolean; width: number }[] }` (coupe aux espaces, mot plus long que la ligne coupé au caractère)
  - `renderCertificatePdf(input: { paragraphs: ResolvedParagraph[]; footer: ResolvedParagraph[]; logoPng: Uint8Array | null; signatureImage: Uint8Array | null; signer: { name: string; title: string }; title: string }): Promise<Uint8Array>`
  - Mise en page : A4 (595,28 × 841,89 pt), marges 25 mm (70,87 pt). Tailles SMALL 9, NORMAL 11, LARGE 14, TITLE 22 pt, interligne 1,4. Logo 48 pt de haut max, aligné comme le premier paragraphe d'en-tête. Signature après `dateLine`, alignée à droite : image (120 pt de large max) puis nom en gras et fonction. Pied de page en SMALL, gris, sur chaque page. Filet fin sous l'en-tête. Métadonnées PDF : titre = `title`, sans auteur ni producteur parlant de NoctuList.
  - Polices : lues depuis `new URL("../assets/…", import.meta.url)`. Caractère absent de la police → remplacé par `?` avant le rendu (vérifié avec `font.hasGlyph` de fontkit ou un essai d'encodage).

- [ ] **Step 1 :** récupérer les fichiers italiques Nunito sous licence OFL. Si aucune source fiable n'est déjà sur la machine, demander au propriétaire avant tout téléchargement (nom du fichier, source, taille). Copier les quatre TTF et `OFL.txt` dans `webform/functions/assets/`.
- [ ] **Step 2 : tests** dans `certificatePdf.test.mjs` :
  - `wraps on spaces` : avec `measure = (t) => t.length * 5` et `width = 50`, « aaaa bbbb cccc » → 2 lignes (`aaaa bbbb`, `cccc`).
  - `splits a word longer than the line` : `"x".repeat(30)`, largeur 50 → 3 lignes.
  - `keeps styles across a wrap` : un run gras coupé en deux garde `b: true` des deux côtés.
  - `renders a one-page certificate` : sortie qui commence par `%PDF-`, chargée avec `PDFDocument.load`, 1 page, taille A4.
  - `renders a two-page certificate` : 80 paragraphes NORMAL → 2 pages ou plus, pas d'exception.
  - `survives characters missing from the font` : un run « 🎉 Œ “ok” » → pas d'exception.
  - `embeds the logo and the signature` : avec un PNG 1×1, le PDF contient 2 images (compter les XObjects `Image` de la page 1).
- [ ] **Step 3 :** `npm --prefix webform/functions install pdf-lib @pdf-lib/fontkit && npm --prefix webform/functions run build && npm --prefix webform/functions test`. Attendu : FAIL sur ces tests.
- [ ] **Step 4 :** implémenter `certificatePdf.ts` (ajouter le point d'entrée dans `build.mjs`). Le soulignement est un trait de 0,6 pt sous le segment ; la justification répartit l'espace restant entre les espaces, sauf sur la dernière ligne.
- [ ] **Step 5 :** relancer. Attendu : PASS.
- [ ] **Step 6 :** vérifier à l'œil : un petit script dans le scratchpad rend le cas `mandate-and-shifts` avec le logo de test vers un fichier `.pdf`, qu'on ouvre et dont on joint une capture au rapport de tâche.
- [ ] **Step 7 :** commit `Render the certificate PDF`.

### Task 5 : demande CERTIFICATE_EMAIL côté serveur

**Files:**
- Modify: `webform/functions/src/validate.ts` (type, champs), `webform/functions/test/validation-cases.json` (cas du nouveau type)
- Modify: `webform/functions/src/requests.ts` (aiguillage, `sendCertificate`), `webform/functions/src/mail.ts` (`buildCertificateMail`, expéditeur, réponse)
- Test: `firebase/tests/functions/certificate.test.mjs`, `webform/functions/test/mail.test.mjs`

**Interfaces:**
- Consumes : Tâches 3 et 4.
- Produces :
  - Demande `portalRequests/{uid}_CERTIFICATE_EMAIL` : `{ uid, type: "CERTIFICATE_EMAIL", status, requestedAt, email, from: "AAAA-MM-JJ", to: "AAAA-MM-JJ", jobTypes: string[] }` (1 à 50 noms, chacun 1 à 80 caractères après `kotlinTrim`, `from <= to`). Toute autre forme → `INVALID`.
  - `RequestReason` gagne `NO_TEMPLATE` (s'il n'y est pas déjà comme valeur de retour) et `NOTHING_TO_CERTIFY`.
  - `buildCertificateMail(args: { to: string; fromName: string; fromAddress: string; replyTo: string; subject: string; body: string; pdf: Uint8Array; fileName: string; logoPng: Uint8Array | null }): OutgoingMail`, avec la pièce jointe `application/pdf`. L'en-tête From = `fromAddress` si `safeRecipient` l'accepte, sinon le compte Gmail ; l'enveloppe SMTP reste sur le compte (comportement actuel de `sendMail`) ; `Reply-To` seulement si `safeRecipient(replyTo)` l'accepte.
  - Nom de fichier : `attestation-benevolat-{prenom}-{nom}.pdf`, en minuscules, accents retirés (NFD), tout ce qui n'est pas `[a-z0-9]` remplacé par `-`, tirets en double fusionnés. Préfixe traduit dans les 5 langues (`attestation-benevolat`, `volunteer-certificate`, `freiwilligen-bestaetigung`, `attestato-volontariato`, `certificado-voluntariado`).

`sendCertificate` dans l'ordre : réglage `volunteer_portal_certificate_enabled` vrai sinon `NOT_ALLOWED` ; document `portalMail/certificate` présent et `parseTemplate` valide sinon `NO_TEMPLATE` ; propriétaire (`isOwner`, non révoqué) sinon `NO_ACCESS` ; **admins acceptés** ; `safeRecipient(volunteer.email)` sinon `NO_EMAIL` ; jobs `orgs/{orgId}/jobs` `where("volunteerId","==",volunteerDocId)` → `CertJob` (`dayOf(date)`) ; types depuis le document publié ; `assemble` null → `NOTHING_TO_CERTIFY` ; rendu ; envoi, échec → `SEND_FAILED` ; `DONE`. Ne loguer que le type de demande, l'uid et la raison.

- [ ] **Step 1 : cas de validation** ajoutés à `validation-cases.json` (valide, `from > to`, `jobTypes` vide, 51 noms, nom de 81 caractères, champ en trop, date mal formée). Le test Kotlin `SharedValidationCasesTest` les lira aussi : ajouter le type côté Kotlin à la Tâche 8 avant de lancer ce test Kotlin.
- [ ] **Step 2 : tests mail** dans `mail.test.mjs` : `certificate mail carries the PDF` (une pièce jointe `application/pdf` avec le nom de fichier donné) ; `uses the alias as From and keeps the envelope` (From = alias, enveloppe = compte) ; `drops an unsafe reply-to` (`"a@b.c, d@e.f"` → pas de Reply-To) ; `file name is ascii` (`fileName("Zoé","D'Été-Ñ","fr") == "attestation-benevolat-zoe-d-ete-n.pdf"`).
- [ ] **Step 3 : tests émulateur** dans `firebase/tests/functions/certificate.test.mjs`, en suivant la mise en place de `requests.test.mjs` (organisation, bénévole avec portail, transport `capture`) : `sends a certificate` (status DONE, un mail capturé avec un PDF, le texte du mail ne contient pas `volunteer.id`, le document de demande non plus) ; `refuses when the option is off` (REJECTED `NOT_ALLOWED`, aucun mail) ; `refuses without a published template` (`NO_TEMPLATE`) ; `rejects an empty selection` (types cochés sans job dans la période → `NOTHING_TO_CERTIFY`, aucun mail) ; `accepts an admin volunteer` (DONE) ; `rejects a malformed request` (`INVALID`).
- [ ] **Step 4 :** `cd firebase/tests && npm run test:functions` et `npm --prefix webform/functions test`. Attendu : FAIL sur les nouveaux tests.
- [ ] **Step 5 :** implémenter.
- [ ] **Step 6 :** relancer les deux. Attendu : PASS.
- [ ] **Step 7 :** commit `Mail the certificate from the server`.

### Task 6 : règles v20

**Files:**
- Modify: `firebase/firestore.rules`, `shared/src/commonMain/composeResources/files/firestore.rules` (copies identiques), constante `NOCTULIST_FIRESTORE_RULES_VERSION` (chercher sa définition)
- Test: `firebase/tests/rules/portal-certificate.test.mjs`, `shared/src/commonTest/kotlin/com/eventmanager/app/data/remote/FirestoreCodecAndRulesShapeTest.kt` si la forme des règles y est vérifiée

**Interfaces:**
- Produces : `validPortalRequest` accepte `CERTIFICATE_EMAIL` si `certificateOn(orgId)` (réglage `volunteer_portal_certificate_enabled` égal à `true`, sur le modèle de la lecture de `volunteer_portal_qr_enabled`), avec exactement les clés `uid, type, status, requestedAt, email, from, to, jobTypes`, `from`/`to` au format `^20[0-9]{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$`, `from <= to`, `jobTypes is list`, `size()` entre 1 et 50. `portalRequestDelayMinutes` renvoie 10 pour `CERTIFICATE_EMAIL`. `portalMail/{docId}` inchangé (membres seulement).

- [ ] **Step 1 : tests** : `volunteer creates a valid certificate request` ; `refused when the option is off` ; `refused with an extra key` ; `refused with 51 job types` ; `refused with from after to` ; `refused again before 10 minutes` ; `portal user cannot read portalMail/certificate`.
- [ ] **Step 2 :** `cd firebase/tests && npm run test:rules`. Attendu : FAIL sur ces tests.
- [ ] **Step 3 :** modifier les deux copies, passer à la version 20.
- [ ] **Step 4 :** relancer `npm run test:rules` et `:shared:desktopTest --tests '*FirestoreCodecAndRulesShapeTest*'`. Attendu : PASS.
- [ ] **Step 5 :** commit `Rules v20: certificate requests`.

### Task 7 : réglages et stockage dans l'app

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/InstitutionSettings.kt` (clés `VOLUNTEER_PORTAL_CERTIFICATE_ENABLED`, `VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE`, dans la liste synchronisée et dans `FIREBASE_ONLY_KEYS`)
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/CertificateTemplateStore.kt` (même modèle que `InstitutionLogoStore` : lecteur et écrivain installés par la racine de l'app, fichier `certificate_template.json`)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/data/sync/SettingsManager.kt` (lecture/écriture et les deux `when` de synchronisation, comme `VOLUNTEER_PORTAL_QR_ENABLED` et `INSTITUTION_LOGO_PNG`)
- Modify: l'endroit où `InstitutionLogoStore.installDiskWriter/Reader` est appelé (installer aussi le nouveau store)
- Test: `shared/src/commonTest/kotlin/com/eventmanager/app/data/sync/CertificateSettingsTest.kt`

**Interfaces:**
- Produces sur `SettingsManager` : `fun isVolunteerPortalCertificateEnabled(): Boolean` (défaut `false`), `fun setVolunteerPortalCertificateEnabled(enabled: Boolean)`, `fun volunteerPortalCertificateTemplate(): CertificateTemplate?` (null si jamais enregistré), `fun saveVolunteerPortalCertificateTemplate(template: CertificateTemplate)`. Les préférences ne gardent qu'un repère, le JSON est sur disque ; la valeur synchronisée est le JSON complet ; un JSON reçu illisible est ignoré.

- [ ] **Step 1 : tests** avec le stockage factice déjà utilisé par les tests de `SettingsManager` : `certificateOffByDefault` ; `templateRoundTrip` (enregistrer puis relire = égal) ; `prefsKeepOnlyAMarker` (la valeur dans le stockage clé/valeur fait moins de 64 caractères) ; `syncValueIsTheJson` (la valeur exportée pour la clé template se relit avec `CertificateTemplate.parse`) ; `garbageFromSyncIsIgnored` (appliquer `"{"` laisse le modèle précédent) ; `certificateKeysAreFirebaseOnly`.
- [ ] **Step 2 :** lancer les tests. Attendu : FAIL.
- [ ] **Step 3 :** implémenter.
- [ ] **Step 4 :** relancer. Attendu : PASS.
- [ ] **Step 5 :** commit `Store and sync the certificate settings`.

### Task 8 : publication, page et appareils

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalDocuments.kt` (`PortalRequestType.CERTIFICATE_EMAIL`)
- Modify: le validateur Kotlin des demandes (`PortalInputValidator.parseRequest`) : accepter et lire `from`, `to`, `jobTypes` (mêmes règles que la Tâche 5)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/VolunteerPortalService.kt` (boucle des demandes, `claimable`, publication de `portalMail/certificate`, suppression quand l'option est coupée)
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/portal/PortalProjection.kt` (`PortalProjectionInput.certificateEnabled: Boolean = false`, champ `certificate`)
- Modify: l'implémentation de l'hôte du service dans `EventManagerViewModel` (`certificateEnabled()`, `certificateTemplate()`)
- Test: `VolunteerPortalServiceTest`, `PortalProjectionBuilderTest`, `SharedValidationCasesTest` (déjà existants)

**Interfaces:**
- Consumes : Tâches 1, 2 (`certificateCategoryOf`, `CertificateDates.dayOf`), 7.
- Produces :
  - Page : `"certificate": { "types": [ { "name": String, "category": String, "days": [Int] } ] }` quand `certificateEnabled`, absent sinon ; tous les jobs passés du bénévole (pas de plafond à 60), jours triés et sans doublon, types triés par premier jour.
  - `portalMail/certificate` : `{ template: <JSON du modèle, objet>, association: String, logoBase64: String, jobTypes: [ { name, category, description } ], updatedAt }`, publié avec le même hachage que `publishMailTemplate`, supprimé (une fois, avec le hachage remis à zéro) quand l'option est coupée ou le portail désactivé. Modèle jamais enregistré → `CertificateDefaults.template("fr", association)`.
  - Les appareils ne réclament jamais une demande `CERTIFICATE_EMAIL` : elle est sautée dans la boucle et exclue de `claimable`.

- [ ] **Step 1 : tests** : `leaves CERTIFICATE_EMAIL to the server` (demande PENDING de plus de 2 minutes : le service ne la réclame pas, son statut reste PENDING) ; `publishes the certificate template when on` ; `deletes the certificate template when turned off` ; `republishes only on change` (deux passes sans changement = une écriture) ; `page lists every past job type with its days` (70 jobs passés de 2 types → 2 entrées, 70 jours au total, aucun job futur) ; `page has no certificate field when off` ; `page certificate never contains the volunteer id` ; `SharedValidationCasesTest` avec les cas de la Tâche 5.
- [ ] **Step 2 :** lancer ces classes de test. Attendu : FAIL.
- [ ] **Step 3 :** implémenter.
- [ ] **Step 4 :** relancer, puis toute la suite `:shared:desktopTest`. Attendu : PASS (sauf l'échec déjà connu sur main).
- [ ] **Step 5 :** commit `Publish the certificate template and job types`.

### Task 9 : conversion éditeur ⇄ modèle

**Files:**
- Modify: `gradle/libs.versions.toml`, `shared/build.gradle.kts` (dépendance `com.mohamedrejeb.richeditor:richeditor-compose`, version compatible avec Compose Multiplatform 1.7.3 et Kotlin 2.0.21 : vérifier sur la page des versions de la bibliothèque, ne pas monter Compose)
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/certificate/CertificateDocHtml.kt`
- Test: `shared/src/desktopTest/kotlin/com/eventmanager/app/ui/certificate/CertificateDocHtmlTest.kt`

**Interfaces:**
- Produces : `object CertificateDocHtml { fun toHtml(doc: List<CertParagraph>): String; fun fromHtml(html: String): List<CertParagraph> }`. Variables et accords sont des liens : `<a href="var:firstName">Prénom</a>`, `<a href="gender:engagé|engagée|engagéx">engagé·e·x</a>` (`|` interdit dans les formes, échappé en `%7C` si besoin). Gras `<b>`/`<strong>`, italique `<i>`/`<em>`, souligné `<u>`, alignement `style="text-align:…"`, taille par une classe ou un `font-size` en px (SMALL 12, NORMAL 16, LARGE 20, TITLE 28), puces `<ul><li>`. Tout autre balise : son texte est gardé, le style perdu.

- [ ] **Step 1 : tests** : `roundTripsEveryStyle` (un document avec chaque style, alignement, taille, puce, variable et accord → `fromHtml(toHtml(doc)) == doc`) ; `roundTripsThroughTheEditor` (charger `toHtml(doc)` dans un `RichTextState` avec `setHtml`, relire `toHtml()` de la bibliothèque, puis `fromHtml` = `doc`) ; `unknownTagsKeepText` (`<span style="color:red">x</span>` → run `x` sans style) ; `defaultsSurviveTheEditor` (chaque bloc par défaut des 5 langues fait l'aller-retour par `RichTextState`).
- [ ] **Step 2 :** lancer. Attendu : FAIL.
- [ ] **Step 3 :** ajouter la dépendance, implémenter. Si `roundTripsThroughTheEditor` montre que la bibliothèque perd les liens personnalisés ou les tailles, adapter la conversion au HTML qu'elle produit réellement et le noter dans le rapport de tâche.
- [ ] **Step 4 :** relancer. Attendu : PASS. `./gradlew :app:assembleDebug` (ou la tâche Android du dépôt) compile aussi.
- [ ] **Step 5 :** commit `Convert certificate texts to and from the rich text editor`.

### Task 10 : interrupteur et fenêtre d'édition dans l'app

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/SyncOnlinePage.kt`, `SyncSettingsBindings.kt`, `EventManagerViewModel.kt` (comme `volunteerPortalQrEnabled`)
- Create: `shared/src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/certificate/CertificateEditorDialog.kt`, `CertificatePreview.kt`, `CertificateSampleData.kt`
- Modify: les 7 fichiers `strings.xml`

**Interfaces:**
- Consumes : Tâches 1, 2, 7, 9.
- Produces : `@Composable fun CertificateEditorDialog(initial: CertificateTemplate, association: String, logoBase64: String, volunteers: List<Volunteer>, jobTypes: List<JobTypeConfig>, jobsOf: (Volunteer) -> List<Job>, gmailAddress: String, onSave: (CertificateTemplate) -> Unit, onDismiss: () -> Unit)` ; `@Composable fun CertificatePreview(assembly: CertAssembly, logoBase64: String, signer: CertSigner, modifier: Modifier)` (page de rapport A4, mêmes tailles relatives que la Tâche 4) ; `object CertificateSampleData { fun sample(language: String): Triple<CertPerson, List<CertJobType>, List<CertJob>> }` (« Léa Exemple », FEMALE, un mandat, 8 shifts sur 2 rôles, une réunion).

Contenu (spec §6) :
- Carte « Plateforme bénévole » : `SettingsSwitchRow` « Attestation de bénévolat » juste après celui du QR, puis un `TextButton` « Modifier l'attestation » (actif même si l'option est coupée). Rien d'autre.
- Fenêtre : largeur ≥ 900 dp → deux colonnes (éditeur, aperçu) ; sinon onglets « Modifier » / « Aperçu ». En haut : langue (5 langues), « Aperçu avec » (exemple puis bénévoles actifs triés par nom). Liste des blocs dans l'ordre du document, chaque bloc avec un `RichTextEditor` ; pour SHIFT et OTHER, des onglets « Un seul », « Plusieurs », « Plusieurs, un seul rôle », « Ligne de rôle » ; pour MEETING, PHOTO et GRAPHIC_EVENT, « Un seul » / « Plusieurs ». Barre d'outils commune au bloc actif : gras, italique, souligné, 4 alignements, taille (4 valeurs), puces, « Insérer une variable » (seulement les variables utiles au bloc), « Accorder selon le genre » (actif avec une sélection d'un mot ; dialogue à 3 champs pré-remplis : mot, mot + « e », mot + « x »). Champ « Lieu ». Signataire : nom, fonction, image (sélecteur de fichier existant de l'app, redimensionnement à 600 px, refus au-delà de 200 000 caractères en base64 avec un message). Section E-mail repliée : nom affiché, adresse d'envoi (indice = `gmailAddress` ; note courte sous le champ sur l'alias Gmail), « Répondre à », objet, corps. Logo : aperçu du logo publié et lien « Changer le logo » vers le réglage existant. Boutons « Rétablir les textes par défaut » (confirmation), « Annuler », « Enregistrer ».
- Changer de langue ne change que la langue des dates ; un bouton dans la confirmation propose de reprendre les textes par défaut de la nouvelle langue.
- L'aperçu se recalcule à chaque frappe (avec un léger délai), par `CertificateAssembler.assemble` sur l'historique complet de la personne choisie et tous ses types.

- [ ] **Step 1 :** ajouter les chaînes (7 langues) et vérifier `python3 scripts/check-i18n.py`. Attendu : OK.
- [ ] **Step 2 :** brancher l'interrupteur et le bouton, puis la fenêtre.
- [ ] **Step 3 :** compiler desktop et Android. Attendu : succès.
- [ ] **Step 4 :** vérifier à la main dans la session de test isolée (`./gradlew -I ~/.noctulist-test/test-run.init.gradle :desktopApp:run`) : basculer l'interrupteur, ouvrir la fenêtre, mettre un mot en gras, insérer une variable, créer un accord, voir l'aperçu changer, enregistrer, rouvrir (contenu identique), rétablir les défauts. Joindre des captures au rapport de tâche.
- [ ] **Step 5 :** commit `Edit the certificate texts in the portal settings`.

### Task 11 : portail web

**Files:**
- Create: `webform/portal-certificate.js` (logique pure, testable)
- Modify: `webform/portal.js` (petit bouton dans `openAccountDialog`, feuille de demande, `DELAYS_MIN.CERTIFICATE_EMAIL = 10`, raisons `NOTHING_TO_CERTIFY` et `NO_TEMPLATE`), `webform/portal-i18n.js` (5 langues), `webform/portal.css` si besoin, `webform/portal-sw.js` (précache du nouveau fichier, version du cache), numéros `?v=` des scripts dans `portal.html`
- Modify: `GuestFormSiteBundle.kt` (`portal-certificate.js`)
- Test: `firebase/tests/site-unit/portal-certificate.test.mjs`

**Interfaces:**
- Consumes : champ de page `certificate` (Tâche 8), demande (Tâches 5 et 6).
- Produces (`portal-certificate.js`) : `groupTypes(types): { category: string, types: { name: string, firstDay: number, lastDay: number, days: number[] }[] }[]` (ordre des catégories de l'enum, types par nom) ; `defaultSelection(types): Set<string>` (tous sauf MEETING) ; `defaultPeriod(types, todayIso): { from: string, to: string }` (premier jour de tous les types → aujourd'hui) ; `typesInPeriod(types, from, to): Set<string>` ; `canSend(selection, inPeriod): boolean` ; `needsFullName(lastName: string): boolean` (`lastName.replace(/[^\p{L}]/gu,"").length < 3`) ; `requestFields(from, to, selection): { from, to, jobTypes: string[] }` (noms triés).

Contenu (spec §4) :
- Dans « Mon compte », section `quiet` du bas, au-dessus de « Télécharger mes données » : un `link-btn` « Demander une attestation de bénévolat », seulement si `page.certificate?.types?.length`, profil bénévole actif. Pas de tuile ni de section.
- Le bouton ouvre une feuille (`openSheet`) : si `needsFullName(page.lastName)`, d'abord un champ « Nom de famille complet » qui envoie une demande `PROFILE` (prénom et téléphone actuels, `photo` absent), puis attend que la page publie un nom assez long (même attente que la modification du profil) ; ensuite champs « Du » / « Au » (`type="date"`, `max` = aujourd'hui), cases par catégorie avec intitulés traduits, boutons « Tout » / « Aucun », types hors période grisés et décochés, bouton « Envoyer » désactivé si `!canSend`. Après envoi : « Ton attestation est envoyée à {email}. » ; délai et renvoi d'une demande bloquée comme le QR.

- [ ] **Step 1 : tests** : `groups types in document order` ; `meetings are unchecked by default` ; `default period runs from the first job to today` ; `types outside the period are excluded` ; `cannot send an empty selection` ; `needsFullName` (`"D."` → vrai, `"Du"` → vrai, `"Dupont"` → faux, `"O'"` → vrai) ; `request fields are sorted and dated`.
- [ ] **Step 2 :** `cd firebase/tests && npm run test:site`. Attendu : FAIL.
- [ ] **Step 3 :** implémenter, chaînes dans les 5 langues.
- [ ] **Step 4 :** relancer `npm run test:site`. Attendu : PASS.
- [ ] **Step 5 :** vérifier dans le navigateur : `npm run preview` dans `firebase/tests` (configuration `launch.json` du portail), `node seed.mjs` complété avec des jobs, un bénévole au nom abrégé et le réglage activé. Se connecter, ouvrir « Mon compte », vérifier le petit bouton, la feuille, le grisage, l'étape du nom, l'envoi (demande visible dans l'émulateur). Capture d'écran.
- [ ] **Step 6 :** commit `Request a volunteer certificate from the portal`.

### Task 12 : documentation et vérification complète

**Files:**
- Modify: `CHANGELOG.md`, `FIREBASE_SETUP.md` (règles v20, functions avec hosting, nouvelles dépendances), `firebase/tests/seed.mjs` (données de démo de l'attestation, si pas déjà fait à la Tâche 11)

- [ ] **Step 1 :** entrée CHANGELOG (version obligatoire, attestation de bénévolat) et section de FIREBASE_SETUP.
- [ ] **Step 2 :** tout relancer : `:shared:desktopTest`, `npm --prefix webform/functions test`, `cd firebase/tests && npm test`, `python3 scripts/check-i18n.py`, compilation desktop et Android. Attendu : tout vert sauf l'échec déjà connu sur main.
- [ ] **Step 3 :** de bout en bout dans les émulateurs : activer l'option dans l'app de test, demander depuis le portail, ouvrir le PDF capturé, vérifier qu'il correspond à l'aperçu de l'app pour la même personne (même texte, même ordre). Capture des deux.
- [ ] **Step 4 :** commit `Docs for the volunteer certificate`.
