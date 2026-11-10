# Plateforme bénévole : traitement serveur et calendrier des avantages

Date : 2026-11-10 · Branche : `feature/volunteer-portal` · Statut : à relire par le propriétaire du projet.
Complète [2026-11-10-volunteer-portal-design.md](2026-11-10-volunteer-portal-design.md).

## 1. Objectif

Aujourd'hui, la plateforme bénévole dépend d'un appareil NoctuList allumé :

- les inscriptions, modifications de profil, liaisons de compte et e-mails de QR code attendent
  qu'un appareil les traite ;
- ce qui dépend de l'heure (shifts à venir ou passés, avantages expirés, activité) n'est
  recalculé que lorsqu'un appareil republie la fiche.

But : une expérience immédiate pour le bénévole, sans appareil allumé, sans écrire deux fois le
calcul des avantages, et en ajoutant l'aperçu des avantages que chaque shift à venir apportera.

## 2. Constat préalable : pas de chiffrement actif

Le chiffrement par champ (`OrgCryptoService`, `SensitiveFieldCodec`) ne s'active qu'avec une phrase
secrète par organisation. `SettingsManager.setOrgCryptoPassphrase` n'est appelé nulle part depuis son
ajout (commit `21b8088`) : toutes les données sont déjà en clair dans Firestore, protégées par les
règles et les comptes Google. Un traitement serveur ne change donc rien au niveau de protection.
Le code de chiffrement inutilisé reste en place (hors périmètre, voir §11).

## 3. Décisions validées

| Sujet | Décision |
|---|---|
| Architecture | Hybride. Le serveur (Cloud Functions) traite ce que font les bénévoles. Les appareils publient les fiches quand les données changent, comme aujourd'hui. |
| Calcul des avantages | Reste uniquement en Kotlin (`BenefitCalculator`). Aucune copie en TypeScript. |
| Temps qui passe | L'appareil publie un calendrier sur 30 jours ; la page affiche l'étape du jour. |
| Rang | Retiré du portail (carte Informations et liste des avantages). |
| Shifts à venir | Chaque shift prévu affiche les avantages qu'il apporte. |
| E-mails | Gmail de l'association, via SMTP et un mot de passe d'application (validation en deux étapes requise sur ce compte). |
| Forfait | Blaze requis pour les Cloud Functions. Alerte de budget recommandée dans le guide. |
| Repli | Sans fonctions déployées, les appareils continuent de tout traiter, avec 2 minutes de délai. |

## 4. Répartition des rôles

| Tâche | Serveur | Appareil |
|---|---|---|
| Inscription par lien (création ou liaison de la fiche) | oui, en quelques secondes | repli après 2 min |
| Modification du profil (prénom, nom, téléphone) | oui | repli après 2 min |
| Liaison d'un compte existant (CLAIM) | oui | repli après 2 min |
| E-mail « M'envoyer mon QR code » | oui, Gmail de l'association | repli après 2 min, Gmail de l'appareil |
| Fiche publiée (shifts, avantages, porte-monnaie) | première fiche minimale d'un nouvel inscrit, puis correctifs de profil | oui, à chaque changement, avec le calendrier |
| Accès admin (créer un accès, lien de mot de passe, retirer l'accès) | non | oui, inchangé (l'admin agit depuis l'app) |
| E-mails de vérification et de mot de passe | non, envoyés par Firebase Auth | inchangé |

## 5. Partie serveur (`firebase/functions/`)

TypeScript, Node 22, `firebase-functions` v6 (2e génération), `firebase-admin`, `nodemailer`,
`qrcode`. Uniquement des déclencheurs Firestore : aucun point d'accès HTTP public.

### 5.1 Déclencheurs

1. `onSignupWritten`, sur `orgs/{orgId}/volunteerSignupForms/{formId}`, quand `status == SUBMITTED`.
2. `onPortalRequestWritten`, sur `orgs/{orgId}/portalRequests/{requestId}`, quand `status == PENDING`.

Chaque déclencheur vérifie d'abord le réglage `institutionSettings/volunteer_portal_enabled == "true"`.
S'il est désactivé, il ne fait rien.

### 5.2 Réservation et reprise

Même protocole que les appareils (`claimDocument`) : transaction qui passe le document en
`PROCESSING` avec `processingDevice = "server"` et `processingAt`. Une réservation de plus de 5 minutes
est considérée comme abandonnée et peut être reprise par un appareil ou par le serveur. Les
déclencheurs sont configurés avec `retry: false` pour éviter les boucles. Une exception laisse le
document en `PROCESSING`, et la reprise après 5 minutes le récupère.

### 5.3 Validation

Le serveur utilise le SDK Admin, qui contourne les règles. Il refait donc toute la validation de
`PortalInputValidator` (longueurs, caractères autorisés, format du téléphone, date de naissance,
genre, e-mail). Un fichier de cas partagé, `firebase/functions/test/validation-cases.json`, est
exécuté par les tests Kotlin et TypeScript pour garantir le même verdict des deux côtés.

### 5.4 Inscription

Reprend la logique de `VolunteerPortalService.processSignup` :

- fiche déjà liée à ce `uid` : rien à créer ;
- fiche non liée avec le même e-mail : liaison (`portalEnabled`, `portalUid`, `lastModified`) ;
- sinon : création de `orgs/{orgId}/volunteers/{id}` au format de `volunteerToMap`, avec un
  `id` NanoID de 21 caractères (alphabet `A-Za-z0-9_-`, comme `NanoIdGenerator`).

Photo : copiée depuis `portalSignupPhotos/{formId}.jpg` vers le chemin de photo de profil utilisé
par l'app et vers `portalPhotos/{uid}.jpg`, puis l'original est supprimé.

Pour un nouvel inscrit, le serveur publie aussitôt une fiche minimale (profil, aucun shift,
porte-monnaie à 0, pas d'avantages), au même format que l'appareil (§7). Pour une fiche liée, qui a
déjà un historique, le serveur ne publie rien : le portail affiche « Ton espace est en préparation »
jusqu'à la publication par un appareil.

Fin : suppression du document d'inscription.

### 5.5 Demandes

- `PROFILE` : met à jour `name`, `lastNameAbbreviation` (en majuscules), `phone` et `lastModified`
  dans la fiche bénévole, puis corrige les mêmes champs dans la fiche publiée et recalcule son
  `contentHash`.
- `CLAIM` : même règle que `handleClaim` (une seule fiche libre avec cet e-mail et `portalEnabled`).
- `QR_EMAIL` : refus si pas d'accès, bénévole admin ou e-mail vide, sinon envoi (§6).

Résultat écrit comme aujourd'hui : `status` (`DONE` ou `REJECTED`), `reason`, `processedAt`.

### 5.6 Témoin de présence

Après chaque traitement, le serveur écrit `orgs/{orgId}/portalServer/status` :
`{ version, lastRunAt }`. Les réglages de l'app affichent « Traitement serveur actif » si ce
document existe, avec la date du dernier passage.

## 6. E-mail du QR code côté serveur

- Expéditeur : le Gmail de l'association, configuré au déploiement par deux paramètres :
  `GMAIL_USER` (paramètre simple) et `GMAIL_APP_PASSWORD` (secret dans Secret Manager).
- Contenu : le modèle e-mail bénévole de l'institution. Les appareils publient déjà la marque du
  portail (`portalBranding/default`) ; ils publient en plus `portalMail/default` avec les textes du
  modèle déjà résolus (sujet, en-tête, texte avant et après, signature, pied de page), dans la langue
  de l'institution. Le serveur y insère le prénom et l'image du QR code (PNG généré à partir de
  `volunteer.id`, en pièce jointe inline).
- Limite : le serveur n'inclut pas les cartes Apple Wallet ou Google Wallet, signées avec des
  certificats présents sur les appareils. L'e-mail du serveur contient seulement le QR code. Quand
  un appareil traite la demande en repli, l'e-mail reste complet.
- Si `portalMail/default` n'existe pas encore, un modèle neutre minimal en anglais est utilisé.

## 7. Fiche publiée, version 2

`volunteerPortal/{uid}` garde `{ schema, payload, contentHash, updatedAt }` avec `schema = 2`.

### 7.1 Contenu du payload

- Profil : inchangé (`firstName`, `lastName`, `email`, `phone`, `dateOfBirth`, `gender`, photo).
- Retirés : `rank`, ainsi que `rank` dans chaque avantage.
- `shifts` : les 60 derniers shifts passés et les 10 prochains, chacun avec sa date. La page les
  sépare elle-même selon l'heure actuelle.
- `lastShiftFloor` : date du dernier shift saisi à la main sur la fiche (`volunteer.lastShiftDate`),
  pour que la page calcule « dernier shift » comme `effectiveLastShiftDate`.
- `wallet` : inchangé (solde et 5 derniers transferts).
- `timeline` : liste ordonnée d'étapes `{ from, active, summary, benefits, uniqueEntriesRemaining }`.
- Chaque shift à venir porte `gains` : la liste des avantages nouveaux ou prolongés par ce shift.

### 7.2 Calcul du calendrier (appareil)

Nouveau `PortalTimelineBuilder` dans `portal/`, testé seul. Points de calcul :

- maintenant ;
- chaque début de journée des 30 prochains jours (avec le décalage horaire de l'institution) ;
- le lendemain de chaque shift à venir (même au-delà de 30 jours, 10 au maximum) ;
- l'instant qui suit chaque `validUntil` dans l'horizon.

Pour chaque point, `BenefitCalculator.calculateVolunteerBenefitStatusFromVolunteerJobs` est appelé
avec ce `now`, et `VolunteerActivityManager.isVolunteerActive` avec les shifts passés à cette date.
Deux étapes consécutives identiques sont fusionnées. Taille attendue : quelques dizaines de Ko au
plus, loin de la limite de 1 Mo de Firestore.

`gains` d'un shift à venir = avantages de l'étape qui suit ce shift, absents de l'étape qui le
précède ou dont la date de fin est plus tardive.

### 7.3 Affichage (portal.js)

- Étape affichée : la dernière dont `from <= maintenant` ; avantages dont `validUntil` est passé
  masqués.
- Au-delà de la dernière étape : on garde la dernière, sans message particulier.
- Carte Informations : statut actif ou inactif, total de shifts, dernier shift. Plus de rang.
- Liste des avantages : description et date de fin, sans badge de rang.
- Shifts à venir : sous chaque shift, « Ce shift te donne : … » si `gains` n'est pas vide.
- Compatibilité : une fiche `schema = 1` reste lisible (ancien affichage, sans rang) jusqu'à sa
  republication.

## 8. Changements côté appareil

- `VolunteerPortalService` : ne traite une inscription ou une demande que si elle attend depuis
  plus de 2 minutes (`requestedAt` ou `submittedAt`), pour laisser la main au serveur. Sans
  serveur, l'effet est seulement un délai de 2 minutes.
- `PortalProjectionBuilder` : schéma 2 (§7), appelle `PortalTimelineBuilder`.
- Publication de `portalMail/default` à côté de `portalBranding/default`.
- Réglages Firebase : témoin « Traitement serveur actif » (§5.6).
- Une fiche bénévole créée par le serveur arrive sur les appareils par la synchro Firestore
  existante (`FirestoreChangeApplier`). Rien à ajouter.

## 9. Règles de sécurité

- `portalMail/{doc}` : lecture et écriture réservées aux membres de l'équipe.
- `portalServer/{doc}` : lecture réservée aux membres, aucune écriture client (seul le SDK Admin
  écrit).
- `volunteerPortal` : inchangé (le bénévole lit uniquement sa fiche, e-mail vérifié).
- Version des règles : 13 (`NOCTULIST_FIRESTORE_RULES_VERSION`).

Le serveur n'expose aucun point d'accès : il ne réagit qu'à des documents que les règles ont déjà
filtrés et limités en fréquence. Les journaux ne contiennent ni nom, ni e-mail, ni téléphone :
uniquement des identifiants de document et des codes d'erreur.

## 10. Déploiement et guide

- L'export « site des formulaires » inclut `functions/` avec le code déjà compilé (`lib/`) et un
  `package.json` : l'association n'a pas besoin de TypeScript.
- Nouvelle section facultative dans le guide Firebase, « Traitement automatique (Cloud Functions) » :
  1. passer le projet au forfait Blaze et créer une alerte de budget (par exemple 5 €) ;
  2. créer un mot de passe d'application sur le Gmail de l'association ;
  3. dans le dossier exporté : `cd functions`, `npm install`, `cd ..` ;
  4. `firebase deploy --only hosting,functions` (le terminal demande l'adresse Gmail et le mot de
     passe d'application au premier déploiement) ;
  5. `firebase functions:artifacts:setpolicy` pour nettoyer automatiquement les anciennes images.
- Région des fonctions : un paramètre `REGION`, à choisir selon l'emplacement de la base Firestore
  (le guide donne la correspondance pour les cas courants : `eur3` vers `europe-west1`, Zurich vers
  `europe-west6`).
- `FIREBASE_SETUP.md` et `CHANGELOG.md` mis à jour, dans les 7 langues pour les textes de l'app.

## 11. Hors périmètre

- Retrait du code de chiffrement inutilisé : à décider séparément. Sans phrase secrète, il ne fait
  rien, et les hachages NFC continuent de fonctionner sans clé.
- Cartes Wallet dans l'e-mail envoyé par le serveur.
- Recalcul des avantages au-delà de 30 jours sans aucun appareil.
- Envoi par Brevo ou Resend.

## 12. Tests

- Kotlin : `PortalTimelineBuilderTest` (fusion des étapes, horizon, `gains`, expiration),
  mise à jour de `PortalProjectionBuilderTest` (schéma 2, plus de rang), délai de 2 minutes dans
  `VolunteerPortalServiceTest`, cas de validation partagés.
- TypeScript : tests unitaires de la validation (cas partagés), de la réservation et du format de la
  fiche minimale ; tests d'intégration sur l'émulateur Functions + Firestore + Storage dans
  `firebase/tests` (inscription nouvelle et liée, profil, CLAIM, QR avec un transport nodemailer
  factice, portail désactivé, document mal formé).
- Règles : nouveaux cas pour `portalMail` et `portalServer` dans `rules/portal.test.mjs`.
- Navigateur : seed de démonstration en schéma 2, vérification de l'étape du jour, des shifts à
  venir avec leurs gains et de l'absence du rang, en clair et en sombre, ordinateur et mobile.
