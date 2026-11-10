# Plateforme bénévole — spec de conception

Date : 2026-11-10 · Branche : `feature/volunteer-portal` · Statut : validée par le propriétaire du projet.

## 1. Objectif

Donner à chaque bénévole un accès web personnel (connexion e-mail + mot de passe) à ses
informations : profil, rang, shifts, avantages, solde du porte-monnaie et 5 derniers transferts.
Inscription par un lien à usage unique créé par un admin. Le tout hébergé sur le site des
formulaires déjà déployé par l'institution (Firebase Hosting de son propre projet).

Exigence principale : **sécurité des données personnelles**. Aucune donnée d'un autre bénévole,
aucun identifiant porte-monnaie (`volunteer.id` = QR), aucun UID NFC, aucun flag admin ne sort
vers le navigateur.

## 2. Décisions validées

| Sujet | Décision |
|---|---|
| Architecture | « Fiche publiée » : l'app écrit une projection filtrée par bénévole ; le navigateur ne lit que la sienne. Aucun serveur, compatible plan Spark. |
| Authentification | Firebase Auth e-mail/mot de passe (hachage par Google). Aucun mot de passe stocké dans NoctuList. |
| Vérification | E-mail vérifié obligatoire pour lire sa fiche (`email_verified` dans les règles). Pas de TOTP pour l'instant. |
| Mot de passe admin | Bouton « Envoyer un lien de réinitialisation » (e-mail Firebase). L'admin ne voit jamais le mot de passe. |
| Création manuelle | Mot de passe aléatoire affiché une seule fois + envoi automatique d'un lien « Définir mon mot de passe ». |
| Inscription | Création automatique du bénévole, lien à usage unique valable 7 jours. |
| QR par e-mail | Relais par un appareil NoctuList en ligne (Gmail), uniquement vers l'adresse de la fiche, 1 envoi / 10 min, refusé pour les bénévoles admin. |
| Extras | Prochains shifts, export JSON de ses données, site installable (manifest), choix de langue (FR/EN/ES/DE/IT). |
| Backend | Firebase uniquement. En mode Sheets, l'option n'existe pas. |

## 3. Modèle de données

### 3.1 Room / `Volunteer` (migration 51 → 52)

- `portalEnabled: Boolean = false` — l'admin a ouvert l'accès (ou inscription par formulaire).
- `portalUid: String = ""` — UID Firebase Auth lié. Vide = accès ouvert mais pas encore lié.

Synchronisés via le document `volunteers/{id}` existant (champs `portalEnabled`, `portalUid`).
Jamais écrits dans Google Sheets.

### 3.2 Firestore (sous `orgs/{orgId}`)

| Collection | Écrit par | Lu par | Contenu |
|---|---|---|---|
| `institutionSettings/volunteer_portal_enabled` | admin org | membres + **règles** | `value: "true"/"false"` — interrupteur serveur. |
| `volunteerSignupForms/{formId}` | app (membre) ; le public : passage `OPEN → SUBMITTED` | public : `get` si ouvert | `status, expiresAtMillis, photosEnabled, associationName` puis `submittedUid, email, firstName, lastName, phone, dateOfBirth, gender, photoPath, consentAt, submittedAt`. |
| `volunteerPortal/{uid}` | app (membre) | le titulaire (`auth.uid == uid`, e-mail vérifié) | Projection filtrée (§4). |
| `portalRequests/{uid}_{type}` | titulaire (création / renouvellement throttlé) ; app (traitement) | titulaire (`get`) ; membres | `type ∈ {PROFILE, QR_EMAIL, CLAIM}`, champs du type, `status ∈ {PENDING, PROCESSING, DONE, REJECTED}`, `requestedAt`, `processedAt`, `reason`. |

Storage :

- `orgs/{orgId}/portalSignupPhotos/{formId}.jpg` — upload par l'inscrit (connecté) tant que le formulaire est ouvert ; lecture/suppression membres.
- `orgs/{orgId}/portalPhotos/{uid}.jpg` — copie de la photo publiée par l'app, lisible par le seul titulaire. Le chemin d'origine (`profilePhotos/volunteers/{volunteer.id}.jpg`) contient l'id porte-monnaie et n'est jamais exposé.

## 4. Projection `volunteerPortal/{uid}`

Champs (aucun autre) :

```
schema: 1
orgId, volunteerKeyHash (SHA-256 tronqué de volunteer.id — jamais l'id lui-même ; sert au debug)
firstName, lastName, email, phone, dateOfBirth (yyyy-MM-dd), gender
photoPath (chemin Storage ou "")
rank (enum ou ""), active (bool), totalShifts, lastShiftAt (millis|0)
shifts: [ {date, jobTypeName, venueName, shiftTime} ]   — 60 plus récents passés
upcomingShifts: [ {date, jobTypeName, venueName, shiftTime} ] — 10 prochains
benefits: [ {rank, description, freeEntry, inviteCount, friendInvitation, drinkTokens,
             barDiscount, guestListAccess, validUntil, futureEntriesRemaining} ]
uniqueEntriesRemaining (int|null)
wallet: { balance, currency, transfers: [ {at, type, amount, label, venue} ] (max 5) }
isAdmin: false — jamais publié ; les bénévoles admin n'ont simplement pas de QR par portail
qrEmailAllowed (bool = !isAdmin && email non vide)
mustSetPassword (bool, création manuelle)
updatedAt, contentHash
```

Calcul : `BenefitCalculator` + `VolunteerActivityManager` existants (mêmes chiffres que l'app).
Publication : debounce 5 s après changement local de volunteers/jobs/transfers/jobTypes/réglages ;
écriture seulement si `contentHash` diffère du dernier publié par cet appareil. Suppression de la
projection quand l'accès est coupé, le bénévole supprimé ou la plateforme désactivée.

## 5. Règles de sécurité (Firestore v12)

- `portalOn(orgId)` : le doc `institutionSettings/volunteer_portal_enabled` existe et `value == 'true'`.
- `portalUser()` : `request.auth != null && request.auth.token.email_verified == true &&
  request.auth.token.firebase.sign_in_provider == 'password'`.
- `volunteerSignupForms` : `get` public si ouvert + non expiré + `portalOn` ; `update` par un
  utilisateur connecté si ouvert, `status → SUBMITTED`, `submittedUid == auth.uid`,
  `email == auth.token.email`, `affectedKeys().hasOnly([...])`, tailles et formats (date
  `^\d{4}-\d{2}-\d{2}$`, genre dans l'enum, téléphone `^[0-9 +().\-]{4,32}$`, noms 1–60).
  `photoPath` vide ou égal au chemin exact du formulaire. Jamais de `list`.
- `volunteerPortal/{uid}` : `get` si `portalOn && portalUser() && auth.uid == uid` ; écriture membres.
- `portalRequests/{rid}` : `get` titulaire ; `create/update` titulaire si `rid == auth.uid + '_' + type`,
  `portalOn`, `portalUser()`, `status == 'PENDING'`, `requestedAt == request.time`, champs whitelistés
  par type, et (update) `request.time > resource.data.requestedAt + délai` (PROFILE 1 min,
  QR_EMAIL 10 min, CLAIM 1 min) et ancienne requête non `PROCESSING`. Membres : tout.
- Le catch-all `orgs/{orgId}/{document=**}` reste réservé aux membres.

## 6. Flux

1. **Activation** : Paramètres → Firebase → « Plateforme bénévole » (admin). Affiche l'URL du portail
   `https://<site>/v/{orgId}` et le rappel : activer le fournisseur E-mail/Mot de passe, republier
   les règles, redéployer le site.
2. **Inscription** : Bénévoles → Ajouter → « Envoyer un formulaire d'inscription » → doc
   `volunteerSignupForms` → dialogue lien + QR (`/s/{orgId}/{formId}`) qui attend la réponse.
   Page web : 1) e-mail, mot de passe (≥ 10 car., liste noire, indicateur), confirmation, case RGPD ;
   2) prénom, nom, téléphone, date de naissance, genre ; 3) photo (si photos activées).
   À l'envoi : `createUserWithEmailAndPassword` → upload photo → update du formulaire →
   `sendEmailVerification`. L'app (n'importe quel appareil membre, verrou par transaction) crée le
   bénévole (`portalEnabled=true, portalUid`), importe la photo, supprime le formulaire.
3. **Création manuelle** : switch « Créer un accès à la plateforme » (si e-mail). REST
   `accounts:signUp` (clé API, n'affecte pas la session admin) avec mot de passe aléatoire →
   `portalUid` ; puis `accounts:sendOobCode PASSWORD_RESET`. Le mot de passe est affiché une fois.
   Si l'e-mail existe déjà côté Auth : `portalEnabled=true`, `portalUid=""`, envoi du lien de
   réinitialisation ; la liaison se fait par CLAIM.
4. **Liaison CLAIM** : connecté + e-mail vérifié mais pas de fiche → le portail écrit
   `{uid}_CLAIM` ; l'app lie si un bénévole `portalEnabled` a cet e-mail et un `portalUid` vide ou
   identique. Sinon REJECTED (« aucun accès ouvert pour cette adresse »).
5. **Portail** : `/v/{orgId}` — connexion, mot de passe oublié, vérification d'e-mail (renvoi),
   tableau de bord, édition profil (prénom, nom, téléphone → requête PROFILE), changement de mot de
   passe (ré-authentification), QR par e-mail, export JSON, déconnexion. Session navigateur par
   défaut, « Rester connecté » optionnel, déconnexion après 30 min d'inactivité.
6. **Admin** (fiche bénévole) : carte « Accès plateforme » — statut ; Créer l'accès ; Envoyer un
   lien de réinitialisation ; Couper l'accès.
7. **Désactivation globale** : règles refusent tout ; l'app arrête de publier et supprime les
   projections qu'elle connaît.

## 7. Interface web

Thème partagé `theme.css` (palette Sunset Mist claire/sombre, Nunito auto-hébergée, rayons 16 px,
cartes translucides sur fond topographique teinté). Bouton clair/sombre/auto mémorisé. Appliqué au
formulaire invités, à la page photo, à l'inscription et au portail.

Portail responsive : < 760 px une colonne ; 760–1499 px deux colonnes (gauche : infos perso +
Volunteer Information ; droite : avantages + porte-monnaie ; Shift History pleine largeur) ;
≥ 1500 px trois colonnes (historique à droite, hauteur de l'écran).

Sécurité front : rendu par `textContent` uniquement ; aucune donnée personnelle dans l'URL ;
en-têtes Hosting : CSP stricte (gstatic + googleapis uniquement), `frame-ancestors 'none'`,
HSTS, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `X-Content-Type-Options`.
Émulateurs utilisés automatiquement uniquement sur `localhost`.

## 8. Composants Kotlin

- `portal/PortalProjectionBuilder` (pur, testé) — Volunteer + jobs + transfers + configs → map.
- `portal/PortalAuthClient` — REST Identity Toolkit (`signUp`, `sendOobCode`), parsing d'erreurs testé.
- `portal/PortalPasswordGenerator` (testé).
- `portal/PortalRequestParser` / `SignupSubmissionParser` (purs, testés : validation côté app en plus des règles).
- `portal/VolunteerPortalService` — listeners `volunteerSignupForms` + `portalRequests`, verrou par
  transaction, traitement, publication des projections.
- `portal/PortalQrMailer` (expect/actual) — envoi du QR volontaire via Gmail en arrière-plan.
- UI : `PortalSettingsCard`, `VolunteerSignupLinkDialog`, `VolunteerPortalAccessCard`,
  `PortalPasswordRevealDialog`, choix dans « Ajouter un bénévole ».

## 9. Tests

- Règles : `firebase/tests/` (`@firebase/rules-unit-testing`, `node:test`) contre l'émulateur —
  chaque refus attendu (autre uid, e-mail non vérifié, plateforme off, champs interdits, throttle,
  list interdit, formulaire fermé…) et chaque accès légitime.
- Kotlin : tests unitaires des composants purs ; compilation desktop + Android ; `desktopTest`.
- Web : émulateurs Auth/Firestore/Storage/Hosting + données de démo, vérification dans le
  navigateur (clair/sombre, mobile/tablette/ordinateur).

## 10. Plan d'implémentation

1. **Thème** : `theme.css`, `theme.js`, polices, restyle `styles.css`/pages existantes, `topo.js`
   teinté, en-têtes Hosting, bundle (`GuestFormSiteBundle`).
2. **Socle** : réglage `VOLUNTEER_PORTAL_ENABLED` (Firebase-only), migration Room 52, codec
   Firestore, règles v12 + Storage + tests émulateur, copies `composeResources`.
3. **Inscription** : page `/s/`, `PortalAuthClient`, `VolunteerPortalService` (formulaires),
   dialogue lien/QR, création manuelle + mot de passe une fois.
4. **Portail** : page `/v/`, projection + publication, requêtes PROFILE/CLAIM.
5. **QR & admin** : relais QR_EMAIL, carte « Accès plateforme », lien de réinitialisation, couper l'accès.
6. **Docs & i18n** : chaînes 7 langues (app) et 5 langues (web), `FIREBASE_SETUP.md`, guide
   d'hébergement, CHANGELOG.
7. **Vérification** complète (§9).

## 11. Limites connues

- Traitements (inscription, modification, QR) et fraîcheur des données dépendent d'un appareil
  NoctuList en ligne ; le portail affiche « Mis à jour il y a … » et l'état des demandes.
- Un compte Auth ne peut pas être supprimé ni son e-mail changé sans API admin : couper l'accès
  supprime la fiche et la liaison (le compte ne voit plus rien).
- Changer l'e-mail d'un bénévole dans l'app ne change pas son identifiant de connexion ;
  la carte « Accès plateforme » le signale.

## 12. Ajustements pendant la réalisation

- La fiche publiée est un document `{schema, payload (JSON), contentHash, updatedAt}` : plus robuste
  avec l'encodeur GitLive, relu tel quel par la page.
- Photo : copie dédiée `portalPhotos/{uid}.jpg` (voir §3.2), jamais le chemin d'origine.
- Avantages : la fiche contient aussi `summary`, le cumul calculé par `BenefitCalculator`
  (ce qui s'applique réellement), affiché en tuiles « En ce moment », puis le détail par rang.
- Interface : bouton « Modifier » discret dans l'en-tête de la carte profil ; il ouvre la fenêtre
  « Mon compte » (informations, mot de passe replié, export des données). Plus de carte
  « Compte et sécurité » dans le tableau de bord.
- Textes : pas de tirets longs ni de tournures artificielles.
- Signup lié à un bénévole existant ayant le même e-mail (sans compte lié) au lieu de créer un doublon.
