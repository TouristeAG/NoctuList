# Connexion Google à la plateforme bénévole : spec de conception

Date : 2026-11-11 · Branche : `feature/volunteer-portal` · Statut : design validé en discussion, spec à relire.
Complète `2026-11-10-volunteer-portal-design.md` et `2026-11-10-portal-server-design.md`.

## 1. Objectif

Les associations qui ont Google Workspace donnent à leurs bénévoles un compte Google à leur nom
(`jean@asso.ch`). Le bénévole doit pouvoir ouvrir son espace avec ce compte au lieu de son e-mail
personnel et de son mot de passe. L'e-mail personnel reste la donnée de référence de la fiche
(il doit continuer à être collecté) et reste l'identifiant e-mail/mot de passe. Le bénévole choisit
à chaque connexion.

L'option est une coche dans les réglages. Elle n'est activable que si l'association possède au
moins un domaine vérifié comme géré par Google Workspace.

## 2. Décisions validées

| Sujet | Décision |
|---|---|
| Modèle d'identité | Le compte Google reste un compte Firebase Auth distinct, relié à la fiche par un document de lien. Pas de `linkWithCredential` : un membre de l'équipe qui est aussi bénévole a déjà un compte Firebase pour son Google, Firebase refuserait de le rattacher une seconde fois. |
| Qui associe | L'admin (adresse saisie dans la fiche) **et** le bénévole (depuis « Mon compte ») si aucune adresse n'est associée et que l'option est active. |
| Priorité de l'admin | Si l'admin a saisi une adresse, le bénévole ne voit aucune option d'association et ne peut ni la changer ni la retirer. |
| Retrait | L'admin peut toujours modifier ou retirer. Le bénévole ne retire que le lien qu'il a créé. |
| Domaines | Liste dédiée au portail, plusieurs domaines possibles, séparée de `allowedEmailDomains` (équipe). Pré-remplie une fois avec les domaines de l'équipe, puis indépendante. |
| Détection Workspace | Chaque domaine est vérifié par une requête DNS MX (DNS-over-HTTPS) depuis l'appareil admin. MX chez Google = vérifié. Seuls les domaines vérifiés comptent. |
| Filtre | Seuls les comptes Google dont le domaine est vérifié peuvent se connecter au portail. Les comptes gmail.com sont refusés. |
| Inscription | Reste en e-mail/mot de passe (l'e-mail personnel est nécessaire). La connexion Google suppose un accès portail déjà ouvert (`portalUid` rempli). |
| Backend | Firebase uniquement, sous-option de la plateforme bénévole. |

## 3. Réglages (app)

Carte « Plateforme bénévole » (Paramètres → Firebase), admin, visible quand le portail est actif :

- Liste « Domaines Google Workspace des bénévoles ». Ajout d'un domaine, normalisé avec
  `FirebaseEmailDomainPolicy.normalizeDomain`. Chaque ligne affiche son état : vérifié,
  pas Google, erreur réseau (réessayer). Suppression possible.
- Vérification : `GET https://dns.google/resolve?name=<domaine>&type=MX`. Le domaine est vérifié
  si au moins un MX se termine par `.google.com.` ou `.googlemail.com.` (couvre `smtp.google.com`,
  `aspmx.l.google.com`, `alt*.aspmx.l.google.com`). Seul le nom de domaine sort de l'appareil.
- Coche « Connexion avec un compte Google » : désactivée tant qu'aucun domaine n'est vérifié,
  avec le texte « Ajoutez un domaine géré par Google Workspace pour activer cette option ».
- Retirer le dernier domaine vérifié éteint la coche.
- Pré-remplissage : à la première ouverture, les domaines de `allowedEmailDomains` sont proposés
  (non vérifiés). Ensuite les deux listes vivent séparément.

Réglages locaux : `VOLUNTEER_PORTAL_GOOGLE_ENABLED` et `VOLUNTEER_PORTAL_GOOGLE_DOMAINS`
(liste sérialisée avec l'état de vérification et `verifiedAt`).

Côté Firestore, ces réglages sont deux documents `institutionSettings` au format habituel
`{value: string}` : `volunteer_portal_google_enabled` (`"true"` ou `"false"`) et
`volunteer_portal_google_domains` (domaines vérifiés séparés par des virgules, que les règles lisent
avec `value.split(',')`). Un domaine non vérifié n'est affiché que dans l'écran admin tant qu'il n'a
pas passé la vérification : il n'est jamais enregistré.

## 4. Modèle de données

### 4.1 Room / `Volunteer` (migration 52 → 53)

- `portalGoogleEmail: String = ""` : adresse Google associée (saisie par l'admin ou liée par le bénévole).
- `portalGoogleUid: String = ""` : uid Firebase du compte Google, rempli une fois le lien créé.
- `portalGoogleLinkedBy: String = ""` : `ADMIN` ou `VOLUNTEER`.

Synchronisés via `volunteers/{id}`. Jamais écrits dans Google Sheets.

### 4.2 Firestore (sous `orgs/{orgId}`)

| Document | Écrit par | Lu par | Contenu |
|---|---|---|---|
| `institutionSettings/volunteer_portal_google_enabled` et `volunteer_portal_google_domains` | admin org | membres + règles | `{value: string}` : `"true"`/`"false"`, et la liste des domaines vérifiés séparés par des virgules (voir section 3). |
| `portalBranding/default` | admin org (publié par `publishBranding`) | public (`get`) | Document déjà public ; il gagne `googleLogin: bool`. Rien d'autre (ni domaines). Pas de `portalPublic/config`. |
| `portalGoogleAccounts/{googleUid}` | membres, Functions | titulaire (`get`), membres | `volunteerUid`, `email`, `linkedBy`, `linkedAt`. |
| `portalGoogleClaims/{googleUid}` | titulaire Google (création/renouvellement throttlé) ; traitement | titulaire (`get`), membres | `type ∈ {ADMIN_MATCH, LINK_CODE}`, `email`, `code` (LINK_CODE), `status`, `requestedAt`, `processedAt`, `reason`. |
| `portalRequests/{uid}_GOOGLE_LINK` | titulaire (session mot de passe) | titulaire, membres | `codeHash` (SHA-256 hex), champs habituels. Pas d'`expiresAt` : l'expiration se calcule depuis `requestedAt` (+ 10 min). |
| `portalRequests/{uid}_GOOGLE_UNLINK` | titulaire (mot de passe ou Google lié) | titulaire, membres | champs habituels. |

La projection `volunteerPortal/{uid}` gagne deux champs : `googleEmail` (ou `""`) et
`googleLinkedBy` (ou `""`), pour que « Mon compte » affiche le bon état. `googleLinkAvailable`
(option active et aucune adresse associée) indique si le bouton « Associer » doit apparaître.

## 5. Règles de sécurité (version suivante)

- `portalGoogleOn(orgId)` : `portalOn(orgId)` et `volunteer_portal_google.enabled == true`.
- `isGoogleAccount()` : `sign_in_provider == 'google.com'`, `email_verified == true`.
- `portalGoogleDomainOk(orgId)` : domaine de `auth.token.email` présent et `true` dans
  `volunteer_portal_google.domains`.
- `isPortalGoogleUser(orgId)` : `portalGoogleOn && isGoogleAccount() && portalGoogleDomainOk
  && exists(portalGoogleAccounts/{auth.uid})`.
- `portalUidOf(orgId)` : `auth.uid` pour `isPortalUser()`, sinon
  `get(portalGoogleAccounts/{auth.uid}).data.volunteerUid` pour `isPortalGoogleUser(orgId)`.
- `volunteerPortal/{uid}` : `get` si `portalOn` et (`isPortalUser() && auth.uid == uid` ou
  `isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid`).
- Storage `portalPhotos/{uid}.jpg` : même condition (lecture du lien via `firestore.get`).
- `portalRequests/{rid}` : une session Google liée agit pour `portalUidOf(orgId)` sur les types
  `PROFILE`, `QR_EMAIL`, `GOOGLE_UNLINK`. `CLAIM` et `GOOGLE_LINK` restent réservés aux sessions
  mot de passe. Délais et champs autorisés inchangés pour les types existants. `GOOGLE_LINK` :
  1 min ; `GOOGLE_UNLINK` : 1 min.
- `portalGoogleClaims/{googleUid}` : `create/update` si `portalGoogleOn`, `isGoogleAccount()`,
  `portalGoogleDomainOk`, `auth.uid == googleUid`, `email == auth.token.email`,
  `status == 'PENDING'`, `requestedAt == request.time`, champs whitelistés, code
  `^[A-Za-z0-9_-]{32,64}$` pour LINK_CODE, renouvellement après 1 min et jamais pendant
  `PROCESSING`. `get` titulaire. Jamais de `list`.
- `portalGoogleAccounts/{googleUid}` : `get` si `auth.uid == googleUid` ; écriture membres seulement.
- `portalBranding/default` : `get` public (déjà le cas) ; écriture admin.
- Équipe : aucun changement. Un compte Google sans `members/{uid}` reste sans droit sur l'org
  (création de membre impossible sans code d'amorçage, pas d'admin si `metadata/config` existe).
  Les tests le vérifient explicitement pour un compte Google lié à un bénévole.
- Copie identique dans `shared/src/commonMain/composeResources/files/firestore.rules`.

## 6. Traitement

Même modèle que les requêtes existantes : Cloud Functions en premier, un appareil NoctuList en
ligne prend les documents de plus de 2 minutes, verrou `PROCESSING` par transaction.
Logique partagée côté Kotlin (`portal/PortalGoogleLinkProcessor`, pur et testé) et côté
Functions (`webform/functions/src/google.ts`).

**ADMIN_MATCH** (`portalGoogleClaims/{googleUid}`) :
1. Domaine de `email` vérifié, sinon REJECTED `domain`.
2. `portalGoogleAccounts/{googleUid}` existe déjà : DONE (rien à faire).
3. Bénévoles avec `portalEnabled`, `portalUid` non vide et `portalGoogleEmail` égal à `email`
   (comparaison minuscules, sans normalisation Gmail). Exactement un : écrire le lien
   (`linkedBy = ADMIN`), `portalGoogleUid` sur la fiche, republier la projection, DONE.
   Aucun : REJECTED `no_match`. Plusieurs : REJECTED `ambiguous`.

**LINK_CODE** (`portalGoogleClaims/{googleUid}` + `portalRequests/{uid}_GOOGLE_LINK`) :
1. Trouver la requête `GOOGLE_LINK` PENDING non expirée dont `codeHash == sha256(code)`.
   Aucune : REJECTED `code`.
2. Fiche du `uid` : `portalGoogleEmail` vide, domaine vérifié, `googleUid` non lié à une autre
   fiche, `email` non utilisé par une autre fiche. Sinon REJECTED (`already_linked`, `domain`,
   `taken`).
3. Écrire le lien (`linkedBy = VOLUNTEER`), les trois champs de la fiche, republier, DONE.
   Supprimer le `code` du claim et marquer `GOOGLE_LINK` DONE.

**GOOGLE_UNLINK** : accepté seulement si `portalGoogleLinkedBy == VOLUNTEER`. Supprime le lien,
vide les champs, republie. Sinon REJECTED `admin_link`.

**Admin dans l'app** (carte « Accès plateforme ») : champ « Compte Google associatif » validé
contre les domaines vérifiés et unique parmi les fiches. Enregistrer met
`portalGoogleLinkedBy = ADMIN` et efface un éventuel lien précédent si l'adresse change.
« Retirer » supprime le lien et vide les champs, quel que soit `linkedBy`.

Couper l'accès portail d'un bénévole ou le supprimer supprime aussi son lien Google.
Éteindre la coche ne supprime pas les liens : les règles refusent, et tout revient si on la rallume.

## 7. Portail web

- `portalBranding/default` lu au chargement. Si `googleLogin`, la page de connexion affiche
  « Se connecter avec Google » sous le formulaire e-mail.
- Connexion Google : `signInWithPopup(GoogleAuthProvider)` avec `prompt: select_account`
  (pas de paramètre `hd` : les domaines ne sont pas publiés, la barrière reste les règles). Si la fiche
  est illisible faute de lien : écrire ADMIN_MATCH et attendre la réponse (écoute du claim).
  DONE : afficher le tableau de bord. REJECTED : message selon `reason`, puis déconnexion.
- « Mon compte » :
  - aucune adresse et `googleLinkAvailable` : « Associer mon compte Google » ;
  - `linkedBy = VOLUNTEER` : « Compte Google : jean@asso.ch » + « Retirer » ;
  - `linkedBy = ADMIN` : rien.
  - Session Google : la partie mot de passe est masquée.
- Associer (session mot de passe) : générer 32 octets aléatoires (`crypto.getRandomValues`,
  base64url), écrire `GOOGLE_LINK` avec `codeHash`, ouvrir une seconde instance
  `initializeApp(config, "google-link")` + `signInWithPopup`, écrire le claim LINK_CODE avec le
  code, attendre la réponse, se déconnecter de la seconde instance. Le code n'est jamais stocké en clair hors du claim.
- `authDomain` du portail = l'hôte du site (Firebase Hosting sert `/__/auth/` nativement), pour
  éviter un cadre vers `firebaseapp.com`.
- CSP : `frame-src 'self'` ; ajout de `https://apis.google.com` dans `script-src` et `connect-src`.
  Rien d'autre.
- Textes en 5 langues web, sans tirets longs ni tournures artificielles.

## 8. Composants

- Kotlin : `portal/WorkspaceDomainVerifier` (DoH, parsing pur testé), `portal/PortalGoogleLinkProcessor`
  (pur, testé), extension de `VolunteerPortalService` (écoute des claims et des nouveaux types),
  `PortalProjectionBuilder` (nouveaux champs), `PortalGoogleSettingsSection` dans `PortalSettingsCard`,
  champ Google dans `VolunteerPortalAccessCard`.
- Functions : `google.ts` (ADMIN_MATCH, LINK_CODE, UNLINK), déclencheur sur `portalGoogleClaims`,
  extension du déclencheur `portalRequests`. `lib/index.js` reconstruit.
- Web : `portal.js` (bouton, flux, Mon compte), `portal-i18n.js`, `firebase.json` (CSP),
  `firebase-env.js` (authDomain).
- Docs : `FIREBASE_SETUP.md` (le fournisseur Google est déjà activé pour l'équipe ; vérifier que le
  domaine du site est dans les domaines autorisés d'Auth), CHANGELOG, chaînes app en 7 langues.

## 9. Tests

- Règles (`firebase/tests`, émulateur) : session Google liée lit sa fiche et sa photo et crée
  PROFILE/QR_EMAIL/GOOGLE_UNLINK ; refus pour Google non lié, domaine hors liste, domaine retiré,
  option éteinte, portail éteint, fiche d'un autre bénévole, écriture de `portalGoogleAccounts`,
  CLAIM et GOOGLE_LINK en session Google, `list` des claims, claim avec un e-mail différent du
  jeton. Un compte Google lié n'obtient aucun droit d'équipe (pas de lecture org, pas de création
  de membre ni de config).
- Functions : ADMIN_MATCH (une, aucune, plusieurs correspondances ; déjà lié), LINK_CODE (bon code,
  mauvais, expiré, fiche déjà liée, compte Google pris, adresse prise), UNLINK (VOLUNTEER accepté,
  ADMIN refusé).
- Kotlin : parsing DoH (MX Google, autre fournisseur, NXDOMAIN, réponse invalide), processeur de
  lien, validation de l'adresse admin, migration 52 → 53, projection.
- Navigateur : émulateur Auth (fournisseur Google simulé) + `seed.mjs` étendu, les deux parcours,
  clair/sombre, mobile/ordinateur.

## 10. Limites connues

- Les codes de raison des réponses de traitement sont en MAJUSCULES (`AMBIGUOUS`, etc.), pas en minuscules comme dans les exemples plus haut.
- ADMIN_MATCH traite un alias dont la cible n'est plus un bénévole portant ce `portalGoogleUid` comme périmé : il le supprime et poursuit la recherche.
- Les règles Storage ne peuvent lire que 2 documents. Tant qu'un lien existe, le compte Google lié peut donc encore voir et modifier la photo de son propre bénévole, même après la désactivation de l'option ou le retrait de son domaine.
- Désactiver l'option ne déconnecte une session Google déjà ouverte que lorsque sa page se rafraîchit depuis le serveur.
- La fenêtre de connexion Google n'a été vérifiée que sur les émulateurs : à contrôler une fois sur le vrai site après le déploiement (« Se connecter avec Google » et « Associer mon compte Google »), y compris sur iPhone.

- La vérification MX prouve que les e-mails du domaine passent par Google, pas l'abonnement
  Workspace lui-même. C'est suffisant ici : la barrière réelle est le domaine du compte Google.
- La vérification est faite une fois par l'admin. Si l'association quitte Google, l'admin doit
  retirer le domaine (une revérification manuelle reste possible).
- La liaison dépend des Functions ou d'un appareil en ligne, comme les autres demandes.
- Un compte Google lié ne peut pas changer le mot de passe du compte e-mail personnel ni lancer
  « Mot de passe oublié » pour lui : ces actions restent sur la connexion e-mail.
