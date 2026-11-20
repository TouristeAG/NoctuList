# Espace invités (permanents et temporaires) : spec de conception

Date : 2026-11-20 · Branche : `feature/guest-portal` (depuis `main`, 3.1.0 + traductions) · Statut : à relire par le propriétaire du projet.
Complète `2026-11-10-volunteer-portal-design.md`, `2026-11-10-portal-server-design.md`,
`2026-11-11-portal-google-login-design.md`, `2026-11-12-portal-qr-design.md` et
`2026-11-19-portal-google-autofill-design.md`.

## 1. Objectif

L'espace bénévole fonctionne. Le même espace doit exister pour les invités :

- **Invités permanents** : exactement la logique des bénévoles (compte e-mail + mot de passe,
  lien d'inscription, ouverture par l'admin, compte Google associatif, adresse Google remplie
  automatiquement, accès automatique des nouveaux, QR, modification du profil, export), avec
  leurs propres avantages : solde, rabais au bar en pourcentage, invitations, lieu. Pas de shifts
  ni de calendrier.
- **Invités temporaires** : un espace en lecture seule, complet dès la première connexion. Le
  compte est celui du manager du groupe (adresse `temporaryContactEmail`, la seule que les invités
  temporaires ont), avec un mot de passe par défaut envoyé dans l'e-mail des QR, que le manager
  peut garder ou remplacer. Rien d'autre n'est modifiable.

Réussite :

- un invité permanent vit exactement le parcours d'un bénévole ;
- un manager reçoit l'e-mail du groupe, ouvre le lien, se connecte avec le mot de passe reçu et
  voit tout de suite chaque invité du groupe avec sa date d'accès et son QR, sans attente ni
  vérification ;
- une personne à la fois bénévole, invitée et manager a un seul compte et passe d'un profil à
  l'autre dans le même espace.

## 2. Décisions validées en discussion

| Sujet | Décision |
|---|---|
| Architecture | Approche 1 : socle commun généralisé (connexion, demandes, inscription, Google, adresse et accès automatiques), une page publiée par type de profil, une seule adresse `/v/{orgId}`. |
| Permanents | Mêmes options que les bénévoles, toutes. |
| Temporaires, identité | Compte e-mail + mot de passe sur l'adresse du groupe. Le manager voit tous les invités de ses groupes. |
| Temporaires, mot de passe | Mot de passe par défaut (aléatoire, dans l'e-mail du groupe) ou mot de passe choisi par le manager. |
| Temporaires, publication | L'appareil qui ouvre l'accès crée le compte et publie la page au même moment. Le serveur n'est pas nécessaire. |
| Temporaires, contenu | Date d'accès, lieu, nom, contact d'urgence, e-mail du groupe, QR, et le solde si les crédits temporaires sont activés et que le solde n'est pas nul. |
| Temporaires, modification | Aucune, sauf le mot de passe du compte. |
| E-mails | Rien n'est envoyé automatiquement, comme pour les bénévoles depuis le dernier changement : ni l'e-mail du groupe temporaire, ni le lien de réinitialisation d'un invité permanent. L'admin les envoie depuis la fenêtre s'il le veut. |

Propositions faites en discussion et non contestées :

- un manager de plusieurs groupes les voit tous, regroupés par date ;
- un groupe disparaît de l'espace 2 jours après la date de l'événement ;
- une entrée déjà validée reste affichée avec « Entrée utilisée », sans QR ;
- un invité admin n'a jamais de QR dans l'espace (son `nanoId` est aussi une clé admin) ;
- l'adresse du groupe déjà connue de Firebase Auth : compte réutilisé, mot de passe inchangé.

## 3. Non-objectifs et anti-objectifs

- Pas de shifts, de rang ni de calendrier pour les invités.
- Pas de second portail ni de copie du code bénévole (approche 2 écartée).
- Le format de `volunteerPortal/{uid}` ne change pas (approche 3 écartée).
- Anti-objectif : un `nanoId` d'invité (QR, clé admin pour un invité admin, chemin de photo) qui
  sort ailleurs que vers son titulaire, ou vers le manager de son groupe pour un invité temporaire
  non admin, par un document dédié aux QR.
- Anti-objectif : un manager qui voit les invités d'un autre groupe que les siens.
- Anti-objectif : une régression de l'espace bénévole (même parcours, mêmes documents, mêmes tests).
- Mode Sheets : aucune de ces options n'existe (comme pour les bénévoles).

## 4. Invariants de sécurité

- `guest.nanoId` ne quitte les appareils que vers :
  - son titulaire non admin, par e-mail ou par `guestPortalQr/{uid}` (permanent) ;
  - le manager de son groupe, par l'e-mail du groupe (déjà le cas) ou par
    `temporaryGuestPortalQr/{uid}` (temporaire non admin).
- Il n'apparaît jamais dans `guestPortal/{uid}`, `temporaryGuestPortal/{uid}`, l'export JSON ni
  les journaux.
- Le chemin de photo d'origine (`profilePhotos/guests/{nanoId}.jpg` ou équivalent) n'est jamais
  exposé : copie dans `portalPhotos/{uid}.jpg` comme pour les bénévoles. Si une personne a une
  photo bénévole et une photo invité, voir §6.3.
- Les UID NFC, le hash NFC, `isAdmin`, `notes`, `temporaryAccessIds` bruts,
  `temporaryEntryValidatedBy` ne sont jamais publiés.

## 5. Modèle de données

### 5.1 Room / `Guest` (migration 53 → 54)

Mêmes champs que le bénévole, Firebase uniquement, jamais écrits dans Google Sheets :

- `portalEnabled: Boolean = false`
- `portalUid: String = ""`
- `portalGoogleEmail: String = ""`, `portalGoogleUid: String = ""`, `portalGoogleLinkedBy: String = ""`
  (permanents seulement ; restent vides pour un temporaire)

Pour un invité temporaire, `portalEnabled`/`portalUid` décrivent le compte du manager : tous les
invités d'un groupe portent le même `portalUid`. Synchronisés par le codec Firestore des invités
(`guests/{nanoId}`) et appliqués par `FirestoreChangeApplier`, comme pour les bénévoles.

### 5.2 Réglages (`institutionSettings`)

| Clé | Défaut | Rôle |
|---|---|---|
| `volunteer_portal_enabled` | inchangé | Interrupteur général de la plateforme. Coupé, tout est refusé, invités compris. |
| `guest_portal_enabled` | `false` | Espace des invités permanents. |
| `temp_guest_portal_enabled` | `false` | Espace des invités temporaires. |

Les réglages Google (`volunteer_portal_google_*`), d'accès automatique
(`volunteer_portal_auto_access`), d'adresse automatique (`volunteer_portal_google_auto`,
`_template`), de logo et de QR (`volunteer_portal_qr_enabled`) s'appliquent tels quels aux invités
permanents quand `guest_portal_enabled` est actif. Le réglage QR s'applique aussi aux temporaires.
*Mon choix : pas de doublon de réglages par type ; les clés gardent leur nom `volunteer_*` pour ne
rien migrer, l'interface les présente comme réglages de la plateforme.*

L'interface : dans la carte « Plateforme bénévole » (Paramètres → Firebase), deux coches
« Espace des invités permanents » et « Espace des invités temporaires », visibles quand la
plateforme est active. La seconde n'apparaît que si les fonctions temporaires sont disponibles
(`temporaryGuestFeaturesEnabled`).

### 5.3 Collections Firestore (sous `orgs/{orgId}`)

| Collection | Écrit par | Lu par | Contenu |
|---|---|---|---|
| `guestPortal/{uid}` | membres, Functions | titulaire (mot de passe vérifié ou Google lié) | Page de l'invité permanent (§6.1). |
| `guestPortalQr/{uid}` | membres, Functions | titulaire | `{schema:1, payload: nanoId, name}`. Absent pour un invité admin ou réglage QR coupé. |
| `temporaryGuestPortal/{uid}` | membres | titulaire, session mot de passe (§7.4) | Page du manager (§6.2). |
| `temporaryGuestPortalQr/{uid}` | membres | titulaire, session mot de passe | `{schema:1, items: {guestKey: {payload, name}}}`, invités temporaires non admin, entrée non utilisée. |
| `volunteerSignupForms/{formId}` | inchangé | inchangé | Nouveau champ `holderType` (`VOLUNTEER` par défaut, `GUEST`), posé par l'app, non modifiable par le public. |
| `portalRequests/{uid}_{type}` | inchangé | inchangé | Nouveaux types `GUEST_PROFILE`, `GUEST_QR_EMAIL` (§8). |
| `portalGoogleAccounts/{googleUid}` | inchangé | inchangé | `volunteerUid` garde son nom mais désigne le compte portail (uid), quel que soit le profil. |

`guestKey` = hachage SHA-256 tronqué (16 hex) du `nanoId`, jamais le `nanoId` lui-même.

## 6. Pages publiées

Même enveloppe que les bénévoles : `{ schema, payload (JSON), contentHash, updatedAt }`, écriture
seulement si `contentHash` change, debounce 5 s, mécanisme `loadPublishedHashes` étendu avec une
clé par collection.

### 6.1 `guestPortal/{uid}`, schema 1

```
orgId, guestKeyHash
firstName (name), lastName (lastNameAbbreviation), email, phone (phoneNumber), photoPath
venueName, invitations, barDiscountPercent (activeBarDiscountPercent(firebase = true))
wallet: { balance, currency, transfers: [ {at, type, amount, label, venue} ] (max 5) } | null
qrEmailAllowed (= !isAdmin && email non vide)
mustSetPassword
```

`wallet` : même source que l'app (`AccountHolderKey(GUEST, nanoId)`), même règle d'affichage que
la fiche invité de l'app. Le constructeur reprend `PortalProjectionBuilder` pour la partie
porte-monnaie, sans dupliquer le calcul.

Une personne avec plusieurs invités permanents sur la même adresse (cas rare, doublons) : la page
est celle de l'invité permanent le plus récemment modifié parmi ceux liés au `uid`, les autres ne
sont pas publiés. *Mon choix, à revoir si le cas existe vraiment chez vous.*

### 6.2 `temporaryGuestPortal/{uid}`, schema 1

```
orgId, contactEmail, expiresAtMillis (max des fins d'accès de ses groupes)
groups: [ {
  eventDate (millis), venueName, artistName (temporaryArtistName), endsAt (eventDate + 2 j),
  guests: [ { guestKey, firstName, lastName, emergencyPhone (temporaryContactPhone),
              entryUsed (bool), qr (bool : a un QR dans temporaryGuestPortalQr),
              wallet: { balance, currency } | null } ]
} ]
```

- Un groupe = les invités temporaires de même `temporaryBatchKey()` dont `portalUid == uid`.
- Groupes triés par date, invités par nom. Groupes dont `endsAt` est passé : retirés à la
  publication suivante ; la page est supprimée quand il n'en reste aucun.
- `wallet` seulement si `temp_guest_credits_enabled` et solde différent de zéro, sinon `null`.
- Les appareils republient à chaque changement d'invité temporaire, de transfert d'un invité
  temporaire ou de réglage, et une fois par jour pour retirer les groupes échus.

### 6.3 Photos

`portalPhotos/{uid}.jpg` est commune au compte. Bénévoles d'abord : si le compte a un bénévole avec
photo, c'est elle ; sinon la photo de l'invité permanent. Les temporaires n'ont pas de photo dans
l'espace. *Mon choix : évite un second chemin Storage et une règle Storage de plus (limite de
2 lectures Firestore par règle Storage).*

## 7. Parcours

### 7.1 Invités permanents

Exactement ceux des bénévoles (`VolunteerPortalService`), sur la fiche invité. Ouvrir l'accès
(à la main ou par l'accès automatique) n'envoie aucun e-mail : le mot de passe est affiché une
fois, et la fenêtre propose « Envoyer un lien de réinitialisation », que l'admin clique s'il le
veut. Même comportement que `grantAccess` des bénévoles après le changement en cours (§14).

- carte « Accès plateforme » sur la fiche d'un invité permanent (`!isTemporaryGuest`,
  `!isVolunteerBenefit`) : créer l'accès, lien de réinitialisation, Google associatif, couper
  l'accès ;
- « Ajouter un invité » → « Envoyer un formulaire d'inscription » (`holderType = GUEST`) ;
- accès automatique des nouveaux invités permanents si `volunteer_portal_auto_access` ;
- adresse Google automatique selon la règle de l'association ;
- portail : profil (prénom, nom, téléphone, photo → `GUEST_PROFILE`), QR, QR par e-mail
  (`GUEST_QR_EMAIL`), export JSON, mot de passe, Google.

Formulaire d'inscription invité : e-mail, mot de passe, prénom, nom, téléphone, photo (si
activée). Pas de date de naissance ni de genre (le modèle `Guest` ne les a pas). Création d'un
invité permanent avec `invitations = 0`, `barDiscountPercent = 0` et le lieu choisi par l'admin
au moment de créer le lien (le document du formulaire porte `venueName`, non modifiable par le
public) ; l'admin complète ensuite. *Mon choix.* Liaison à un invité permanent existant sans
compte et de même e-mail au lieu de créer un doublon, comme pour les bénévoles.

### 7.2 Invités temporaires : ouverture de l'accès

Déclencheur unique : l'action « Ouvrir l'espace du groupe » sur un groupe, quand
`temp_guest_portal_enabled` est actif et que le groupe a une adresse. Elle n'envoie aucun e-mail.

1. Si un invité ou bénévole déjà synchronisé a un `portalUid` non vide pour cette adresse : on le
   réutilise.
2. Sinon `PortalAuthClient.signUp(adresse, PortalPasswordGenerator)` :
   - succès : `portalUid` = nouvel uid, le mot de passe est inséré dans l'e-mail ;
   - `EMAIL_EXISTS` : `portalUid = ""`, l'e-mail dit « utilise ton mot de passe habituel » et
     contient le lien de réinitialisation ; la liaison se fait par CLAIM (§8).
3. Tous les invités du groupe reçoivent `portalEnabled = true`, `portalUid`.
4. Publication immédiate de `temporaryGuestPortal/{uid}` et du document QR.
5. Fenêtre de résultat, comme pour un bénévole : mot de passe par défaut affiché une seule fois
   (ou « compte existant »), bouton Copier, et deux actions facultatives :
   - « Envoyer l'e-mail du groupe » : ouvre l'envoi existant (`DesktopEmailConfirmDialog`,
     panneau Android) avec, en plus des QR, un bloc compte : adresse de l'espace `/v/{orgId}`,
     adresse de connexion, mot de passe par défaut tant que la fenêtre le connaît, sinon mention
     « ton mot de passe habituel » ;
   - « Envoyer un lien de réinitialisation » (`sendOobCode PASSWORD_RESET`).

L'envoi de l'e-mail du groupe reste une action manuelle, comme aujourd'hui. Envoyé plus tard
depuis la liste, il contient le bloc compte sans mot de passe dès que l'espace du groupe est ouvert.

Le mot de passe par défaut n'est stocké nulle part : il n'existe que dans la fenêtre et, si
l'admin l'envoie, dans l'e-mail. Envoyé par le
relais (`mailOutbox`, sans Gmail sur l'appareil), il transite dans le MIME lisible par les seuls
membres jusqu'à l'envoi, comme le QR aujourd'hui. 

Couper l'accès : action « Couper l'accès à l'espace » sur le groupe ; `portalEnabled = false`,
`portalUid = ""` sur ses invités, republication (la page ne garde que les autres groupes ou est
supprimée).

### 7.3 Invités temporaires : dans l'espace

- Connexion par e-mail et mot de passe. Pas d'écran de vérification d'e-mail (§7.4).
- Liste des groupes (date, lieu, artiste), puis les invités : nom, contact d'urgence, solde
  éventuel, bouton « QR » (plein écran, comme les bénévoles) ou mention « Entrée utilisée ».
- E-mail du groupe affiché en tête.
- « Mon compte » réduit au changement de mot de passe et à la déconnexion. Aucun bouton
  « Modifier », aucune demande, pas d'export.
- QR hors ligne : même mécanisme `localStorage` que les bénévoles, une clé par profil.

### 7.4 Pourquoi pas de vérification d'e-mail pour les temporaires

La page `temporaryGuestPortal/{uid}` n'est publiée que sous un `uid` que l'appareil vient de créer
lui-même (mot de passe connu seulement de l'appareil et de la boîte du groupe), ou un `uid` lié par
CLAIM, qui exige déjà un e-mail vérifié. Un tiers qui créerait un compte avec l'adresse du groupe
avant l'association tombe dans le cas `EMAIL_EXISTS` : aucune page n'est publiée pour lui tant
qu'un CLAIM vérifié n'a pas eu lieu. La règle accepte donc une session mot de passe dont l'uid est
celui de la page, sans `email_verified`. Ce relâchement ne vaut que pour les deux collections
temporaires.

### 7.5 Une adresse, plusieurs profils

Après connexion, la page lit en parallèle `volunteerPortal`, `guestPortal` et
`temporaryGuestPortal` pour le même uid (refus ou absence = profil absent). Aucun : parcours
actuel (CLAIM, « en préparation »). Un seul : affichage direct. Plusieurs : sélecteur en haut
(« Bénévole », « Invité », « Mes groupes »), dernier choix mémorisé. Pour une session non vérifiée,
seule la lecture temporaire est tentée puis, si elle échoue, l'écran de vérification actuel.

## 8. Demandes et liaison (appareils et Functions)

- `CLAIM` : lie à l'uid tous les profils avec `portalEnabled`, un `portalUid` vide et cette adresse
  (`email` des bénévoles et invités permanents, `temporaryContactEmail` des temporaires). Refus
  si un profil de même adresse est déjà lié à un autre uid. Le refus « aucun accès ouvert » ne
  tombe que si aucun profil ne correspond.
- `GUEST_PROFILE` : comme `PROFILE`, sur l'invité permanent lié (`name`, `lastNameAbbreviation`,
  `phoneNumber`, photo).
- `GUEST_QR_EMAIL` : comme `QR_EMAIL`, refus pour un invité admin ou sans e-mail ; modèle d'e-mail
  invité de l'association.
- Google (`ADMIN_MATCH`, `LINK_CODE`, `GOOGLE_LINK`, `GOOGLE_UNLINK`) : la recherche porte sur les
  bénévoles et les invités permanents. L'unité de correspondance est le compte portail : plusieurs
  profils qui partagent le même `portalUid` comptent pour une seule personne. Le correctif
  `portalUid` vide (`getUserByEmail`, branche `feature/setup-hardening`) s'applique aux invités s'il
  est fusionné ; sinon comportement actuel.
- Inscription `holderType = GUEST` : `signup.ts` crée ou lie un invité permanent, publie une page
  minimale `guestPortal/{uid}` et `guestPortalQr/{uid}`.
- Functions : mêmes déclencheurs (`onSignupWritten`, `onPortalRequestWritten`,
  `onPortalGoogleClaimWritten`), lecture de `guests` en plus de `volunteers`. Repli appareil après
  2 minutes inchangé.

## 9. Règles Firestore (v17) et Storage

```
function guestPortalOn(orgId)     = portalOn(orgId) && setting 'guest_portal_enabled' vrai
function tempGuestPortalOn(orgId) = portalOn(orgId) && setting 'temp_guest_portal_enabled' vrai

match /orgs/{orgId}/guestPortal/{uid}     get: guestPortalOn && (isPortalUser() && uid == auth.uid
                                                 || isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid)
match /orgs/{orgId}/guestPortalQr/{uid}   get: idem && portalQrOn(orgId)
match /orgs/{orgId}/temporaryGuestPortal/{uid}
                                          get: tempGuestPortalOn && isPasswordAccount() && uid == auth.uid
                                               && request.time.toMillis() < resource.data.expiresAtMillis
match /orgs/{orgId}/temporaryGuestPortalQr/{uid}
                                          get: tempGuestPortalOn && portalQrOn && isPasswordAccount() && uid == auth.uid
                                               && la page temporaire existe et n'est pas échue (get de la page)
```

- `validPortalRequest` : types `GUEST_PROFILE` (mêmes champs que `PROFILE`) et `GUEST_QR_EMAIL`
  (délai 10 min), autorisés seulement si `guestPortalOn` ; `portalRequests` `get` accepte les deux
  nouveaux identifiants.
- `validSignupSubmission` : si `resource.data.get('holderType', 'VOLUNTEER') == 'GUEST'`, exige
  `guestPortalOn`, sans `dateOfBirth` ni `gender` dans les clés modifiées ; `holderType` hors de
  `affectedKeys`.
- Aucune `list`, aucune écriture web sur les quatre nouvelles collections. Membres par le
  catch-all.
- Storage : inchangé (`portalPhotos/{uid}.jpg`, `portalPhotoUploads/{uid}.jpg`). Une demande
  `GUEST_PROFILE` avec photo utilise le même chemin d'upload ; les règles Storage acceptent l'upload
  si le portail est actif, ce qui couvre déjà ce cas (à vérifier pendant le plan).
- `NOCTULIST_RULES_VERSION = 17`, copies `composeResources/files` et `firebase/tests` synchronisées,
  `firebase/firestore.rules` aussi.

## 10. Composants

Kotlin (`portal/`) :

- `PortalHolder` (nouveau) : abstraction mince VOLUNTEER / GUEST pour ce qui est commun (e-mail,
  uid, champs Google, sauvegarde). Le code bénévole existant passe par elle sans changer de
  comportement.
- `GuestPortalProjectionBuilder`, `TemporaryGuestPortalProjectionBuilder` (purs, testés).
- `GuestPortalQrDocument`, `TemporaryGuestPortalQrDocument` (purs, testés).
- `VolunteerPortalService` : publication des quatre nouvelles collections, demandes `GUEST_*`,
  CLAIM généralisé, inscription `GUEST`, accès automatique et adresse Google pour les invités.
  *Mon choix : garder un seul service (une seule file, un seul verrou par transaction), renommé
  seulement si le plan le juge utile.*
- `TemporaryGuestPortalAccess` : étapes §7.2 (réutilise `PortalAuthClient`,
  `PortalPasswordGenerator`).
- UI : carte « Accès plateforme » sur la fiche invité permanent, choix « formulaire d'inscription »
  dans l'ajout d'invité, bloc compte dans l'e-mail du groupe, action « Couper l'accès à l'espace »
  sur un groupe, deux coches de réglage.

Web : `portal.js` (sélecteur de profil, rendu invité, rendu temporaire en lecture seule),
`signup.js` (variante invité), `portal-i18n.js` (5 langues), `portal-sw.js` (version de cache).

Functions : `signup.ts`, `requests.ts`, `google.ts`, `validate.ts` (validation invité),
`lib/index.js` recompilé et copié dans `composeResources`.

## 11. Textes

Nouveaux libellés seulement, 7 langues dans l'app, 5 dans le portail, sans tiret cadratin ni texte
d'explication superflu. Liste fixée pendant le plan.

## 12. Tests

- Kotlin : constructeurs de pages et de QR (aucun `nanoId`, `notes`, NFC, `isAdmin` ; invité admin
  sans QR ; groupes échus retirés ; solde seulement si crédits actifs et non nul), étapes §7.2
  (création, `EMAIL_EXISTS`, réutilisation d'un uid connu), CLAIM généralisé, migration 53 → 54,
  codec invité, non-régression bénévole.
- Règles (`firebase/tests/rules`) : chaque nouvelle collection (titulaire oui ; autre uid, réglage
  coupé, plateforme coupée, groupe échu, `list`, écriture web : non) ; session non vérifiée
  acceptée seulement sur les deux collections temporaires ; demandes `GUEST_*` ; inscription
  `GUEST`.
- Functions : inscription invité (création, liaison), `GUEST_PROFILE`, `GUEST_QR_EMAIL`, CLAIM
  multi-profils, Google `ADMIN_MATCH` sur un invité, refus quand l'espace invités est coupé.
- Navigateur (`npm run preview` + seed étendu) : invité permanent, manager à deux groupes, compte
  à trois profils, QR hors ligne, clair/sombre, mobile/ordinateur.

## 13. Hors périmètre

- Calcul serveur des pages invité au fil du temps (pas nécessaire : pas de calendrier).
- Comptes individuels pour chaque invité temporaire.
- Apple Wallet / Google Wallet.

## 14. Prérequis

Le changement « ouvrir l'accès sans e-mail » des bénévoles (`grantAccess` sans `languageCode`,
`PortalGrantResult.Created(password)`, `ExistingAccount` sans envoi, bouton dans la fenêtre)
n'est pas encore commité : il est dans le dossier principal (`feature/setup-hardening`), avec
d'autres changements en cours. Il doit être commité et fusionné dans `main` (ou repris sur
`feature/guest-portal`) avant le travail sur les invités, pour que les deux suivent le même code.

## 15. Laissé au plan

- Ordre de livraison (proposé : socle et règles, permanents, temporaires, Functions, web, i18n).
- Noms exacts des fichiers et des chaînes.
- Vérification de la règle Storage d'upload pour les invités.
