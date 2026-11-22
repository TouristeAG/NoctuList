# Attestation de bénévolat (PDF par e-mail)

Branche `feature/volunteer-certificate`, partie de `feature/guest-portal` (non fusionnée).
Lire aussi : 2026-11-10-volunteer-portal-design.md, 2026-11-10-portal-server-design.md,
2026-11-12-portal-qr-design.md (invariant `volunteer.id`).

## 1. Intention

Un bénévole a besoin d'un document sérieux qui prouve son engagement (CV, école, employeur,
dossier). Aujourd'hui l'association l'écrit à la main. Depuis son espace, le bénévole choisit une
période et les types d'activité à inclure, et reçoit par e-mail un PDF soigné au nom de
l'association, dont l'association a écrit les textes.

Le document doit être **utile à l'extérieur** : il dit ce que la personne a fait, avec les
descriptions des jobs, pas comment l'association compte en interne.

## 2. Objectifs, non-objectifs, anti-objectifs

Objectifs
- Demande depuis le portail, PDF envoyé par le Gmail de l'association, sans appareil allumé.
- Filtres : période (choisie par le bénévole) et types de job à inclure.
- Texte qui s'adapte à ce qui a été fait (mandat, plusieurs shifts, un seul shift, réunions,
  photo/vidéo, graphisme, autres missions) et au genre de la personne.
- Textes entièrement modifiables par l'admin dans un éditeur de texte riche, avec aperçu.
- Interrupteur d'activation dans les réglages du portail.
- Logo de l'association en tête.

Non-objectifs
- Pas de liste des shifts avec leurs dates, pas d'heures, pas de « profité / non profité », pas de
  rangs, pas d'avantages, pas de lieux (`venueName` reste interne).
- Pas de plusieurs langues : l'attestation sort dans une seule langue, celle choisie par
  l'association dans l'éditeur (défaut : français, textes par défaut fournis en français).
- Pas de signature électronique certifiée, pas de numéro de vérification.
- Pas de téléchargement direct dans le navigateur (uniquement par e-mail).

Anti-objectifs (échec même si « ça marche »)
- « Léa a effectué 1 shift de co-présidence » : un mandat Orion n'est jamais appelé shift.
- Une phrase grammaticalement fausse au singulier ou selon le genre.
- Une fuite de `volunteer.id` (dans le PDF, l'e-mail, le document de demande ou la page).
- Un PDF qui ne ressemble pas à ce que l'admin voit dans l'éditeur.
- Un e-mail envoyé sans demande du bénévole (aucun envoi automatique).

## 3. Approches considérées

Génération du PDF
- **A. Cloud Function (choisie).** Les appareils publient le modèle et les types de job ; le
  serveur lit les jobs du bénévole dans `orgs/{orgId}/jobs` (en clair), assemble le texte, rend le
  PDF (pdf-lib) et l'envoie comme `QR_EMAIL`. Fonctionne sans appareil. Coût : le rendu est en
  TypeScript.
- B. Appareil : attente d'un appareil en ligne, deux moteurs PDF (Android et desktop). Écartée.
- C. Navigateur : la page ne garde que 60 shifts et l'envoi passe de toute façon par le serveur.
  Écartée.

Éditeur
- **A. Texte riche (choisi)** avec la bibliothèque compose-rich-editor (Compose Multiplatform,
  Android et desktop), format volontairement réduit pour que le PDF reproduise exactement l'éditeur,
  et aperçu à côté.
- B. Champs texte avec marqueurs (`**gras**`) et aperçu. Plus simple, pas « Word ». Écartée.

Le propriétaire a suivi les deux recommandations (1A + 2A).

## 4. Ce que voit le bénévole (portail)

- Dans la feuille « Mon compte », un **petit bouton discret** (style lien, comme « Mot de passe
  oublié ? ») « Demander une attestation de bénévolat », en bas de la feuille. Pas de section, pas
  de tuile sur le tableau de bord : on s'en sert rarement, souvent une seule fois. Visible seulement
  si l'option est active et que le bénévole a au moins un job passé. Il ouvre la feuille de demande :
  - Période : « Du » et « Au » (champs date). Défaut : date du premier job passé → aujourd'hui.
    « Au » ne peut pas dépasser aujourd'hui ; « Du » ≤ « Au ».
  - Types d'activité : cases groupées par catégorie (Mandats, Shifts, Réunions, Photo et vidéo,
    Graphisme, Autres missions), seulement les types que ce bénévole a déjà faits. Tous cochés
    sauf les réunions. Boutons « Tout » / « Aucun ». Un type dont aucun job ne tombe dans la
    période est grisé.
  - Envoyer est désactivé si rien de coché ou rien dans la période.
  - Nom complet : si le nom de famille de la fiche fait moins de 3 lettres (fiche créée à la main
    avec une initiale), la feuille demande d'abord le nom complet et l'envoie par la modification
    de profil existante (`PROFILE`) ; la demande d'attestation part une fois le nom appliqué.
    Validé par le propriétaire le 2026-11-22.
  - Le nom est écrit avec la casse corrigée (« DUPONT » → « Dupont », « VAN DER BERG » → « Van
    Der Berg »).
  - Message : « Ton attestation est envoyée à {email}. »
- Délai entre deux demandes : 10 minutes, comme le QR (`portalRequestDelayMinutes`), même logique
  de renvoi d'une demande bloquée.
- Textes du portail dans les 5 langues du portail (`portal-i18n.js`), tournures sans formules
  creuses (voir mémoire « no AI-sounding copy », pas de tiret cadratin).

Données nécessaires côté page : `volunteerPortal/{uid}` gagne un champ `certificate`
(publié par les appareils, qui ont tout l'historique) :

```json
"certificate": {
  "types": [ { "name": "Bar", "category": "SHIFT", "days": [20250314, 20250402] } ]
}
```

`days` = jours (AAAAMMJJ, fuseau de l'association) des jobs passés de ce type, tous les jobs, pas
seulement les 60 derniers. Sert au grisage et aux dates par défaut. Absent si l'option est coupée.
Ne contient aucun identifiant.

## 5. Catégories et formulation

Catégorie d'un job, d'après son `JobTypeConfig` (par `jobTypeName`) :

| Catégorie | Règle | Formulation |
|---|---|---|
| MANDATE | `isOrionJob` | mandat, par type : « a exercé le mandat de {rôle} de {début} à {fin} » |
| SHIFT | `isShiftJob` et `novaJobType = DEFAULT_SHIFT` | nombre total + liste des rôles |
| MEETING | `novaJobType = MEETING` | « a participé à {nombre} réunions » |
| PHOTO | `PHOTOGRAPHER_VIDEOGRAPHER` | « a assuré la couverture photo et vidéo de {nombre} événements » |
| GRAPHIC_EVENT | `GRAPHIC_DESIGNER_EVENT` | « a réalisé les visuels de {nombre} événements » |
| GRAPHIC_ASSOCIATION | `GRAPHIC_DESIGNER_ASSOCIATION` | « a contribué à l'identité graphique de l'association » |
| OTHER | tout le reste, ou type introuvable | « a mené {nombre} missions : {rôles} » |

`isOrionJob` gagne sur tout le reste. Les formulations ci-dessus sont les textes par défaut ;
tous sont modifiables (§6).

Règles d'assemblage
- Seuls les jobs passés (`date` < début du jour suivant, fuseau de l'association), dans la période,
  d'un type coché.
- Chaque catégorie présente donne un bloc, dans l'ordre : mandats, shifts, photo, graphisme
  événement, graphisme association, autres, réunions. Une catégorie absente ne donne rien.
- **Singulier / pluriel** : chaque bloc a deux variantes, « un seul » et « plusieurs ». La variante
  « un seul » d'un shift dit la date : « Le {date}, {prénom} a participé bénévolement à un
  événement de {association} en tant que {rôle}. » La variante « plusieurs » dit la période et le
  nombre, jamais les dates individuelles.
- **Mandats** : un bloc par type de mandat. {début} et {fin} = premier et dernier job de ce type
  dans la période, au format mois + année ; si les deux tombent le même mois : « en {mois} ».
  Pas de nombre affiché.
- **Liste des rôles** (shifts et autres) : une ligne par type, ordre décroissant de nombre, format
  de ligne modifiable, défaut « **{rôle}** : {description} ». Si la description est vide, la ligne
  devient « **{rôle}** ». Un seul type : pas de liste, le rôle entre dans la phrase (« en tant que
  {rôle} ») et la description suit en paragraphe.
- {début}/{fin} du document = dates choisies par le bénévole, ramenées au premier et dernier job
  réellement inclus (une période « 2019 → aujourd'hui » avec des jobs de 2024 à 2025 affiche
  2024 → 2025).
- Nombres : en chiffres. Dates : format long de la langue de l'attestation (« 12 mars 2026 »,
  « mars 2026 »).
- Si plus aucun job ne reste après filtrage côté serveur : demande refusée `NOTHING_TO_CERTIFY`
  (le portail l'empêche déjà, c'est une sécurité).

**Genre** : un passage peut porter un accord à trois formes (masculin, féminin, non-binaire).
Choix de la forme selon `Volunteer.gender` : MALE → masculin, FEMALE → féminin, NON_BINARY, OTHER,
PREFER_NOT_TO_DISCLOSE ou vide → non-binaire. Dans l'éditeur, l'admin sélectionne un mot et clique
« Accorder selon le genre » ; les trois formes sont pré-remplies (féminin = mot + « e »,
non-binaire = mot + « x »), modifiables. Dans l'éditeur, le passage apparaît surligné sous la forme
« engagé·e·x » ; un clic ouvre les trois formes.

## 6. Ce que voit l'admin (app)

Paramètres → Firebase → carte « Plateforme bénévole » (là où se trouve déjà l'interrupteur du QR) :
- Interrupteur « Attestation de bénévolat » (`institutionSettings/volunteer_portal_certificate_enabled`,
  booléen, absent = coupé).
- Juste en dessous, bouton « Modifier l'attestation » (actif même si l'option est coupée) qui ouvre
  une grande fenêtre. Rien d'autre dans la carte.

Fenêtre « Attestation de bénévolat »
- Deux colonnes sur desktop (éditeur à gauche, aperçu A4 à droite) ; sur Android, onglets
  « Modifier » / « Aperçu ».
- En haut : langue de l'attestation (liste des langues de l'app, défaut français) ; « Aperçu
  avec » : choix d'un bénévole réel, plus un exemple fictif par défaut (« Léa Exemple », femme, avec
  un mandat, plusieurs shifts et une réunion). L'aperçu applique tous les types, sur tout
  l'historique de la personne choisie.
- L'éditeur liste les blocs dans l'ordre du document ; chaque bloc est une zone de texte riche :
  1. En-tête (sous le logo, par défaut le nom de l'association ; une ligne d'adresse facultative)
  2. Titre (défaut « Attestation de bénévolat »)
  3. Introduction (défaut « {association} atteste que {prénom} {nom} s'est engagé{·e·x}
     bénévolement au sein de l'association entre {début} et {fin}. » ; variante un seul job :
     « … s'est engagé{·e·x} bénévolement au sein de l'association. »)
  4. Un bloc par catégorie, chacun avec ses variantes « un seul » / « plusieurs » (onglets dans le
     bloc), plus le format de ligne de la liste des rôles
  5. Conclusion (défaut : « Nous remercions chaleureusement {prénom} pour son engagement. »)
  6. Lieu et date (défaut « Fait à {lieu}, le {date_du_jour} » ; champ « Lieu »)
  7. Signature : nom, fonction, image de signature facultative (PNG/JPEG, fond transparent
     conseillé, redimensionnée à 600 px de large max)
  8. Pied de page facultatif (coordonnées)
- Barre d'outils : gras, italique, souligné, alignement (gauche, centre, droite, justifié), taille
  (petit, normal, grand, titre), liste à puces, « Insérer une variable » (menu), « Accorder selon
  le genre ». Rien d'autre (pas de couleurs, pas de polices, pas d'images dans le texte).
- Variables disponibles : {prénom}, {nom}, {association}, {début}, {fin}, {date}, {nombre},
  {rôle}, {description}, {rôles}, {lieu}, {date_du_jour}. Le menu ne propose que celles qui ont un
  sens dans le bloc (par ex. {rôles} seulement dans les blocs à plusieurs rôles). Dans l'éditeur,
  une variable s'affiche comme une étiquette insécable ; une variable inconnue saisie à la main reste
  du texte.
- Section « E-mail » (repliée) :
  - **Expéditeur** : nom affiché (défaut : nom de l'association) et adresse d'envoi. Par défaut
    l'adresse Gmail de l'association déjà configurée pour le portail, affichée en clair. On peut
    saisir une autre adresse seulement si c'est un alias « Envoyer des e-mails en tant que » de ce
    compte Gmail (Gmail remplace sinon l'adresse par celle du compte) ; une note courte le dit sous
    le champ. Le serveur garde l'enveloppe SMTP sur le compte et ne met que l'en-tête From.
  - **Répondre à** (facultatif) : adresse où arrivent les réponses, par exemple celle du comité.
  - Objet et corps simples (texte brut, variables {prénom} et
  {association}). Défauts : « Ton attestation de bénévolat » / « Bonjour {prénom}, voici ton
  attestation de bénévolat pour {association}. Elle est jointe à cet e-mail. »
- Boutons « Rétablir les textes par défaut » (avec confirmation), « Annuler », « Enregistrer ».
- Le logo vient de celui que publie déjà le portail (`portalBranding`). S'il n'y en a pas,
  l'en-tête n'a que le texte. Un lien « Changer le logo » mène au réglage existant.

Stockage côté app : les préférences desktop refusent les valeurs de plus de ~8 Ko. Le modèle (JSON,
§7) est donc gardé sur disque comme le logo (`getInstitutionLogoPng`), avec un simple repère dans
les préférences ; l'image de signature est un fichier à part (limite 200 Ko après
redimensionnement). Hors synchro Sheets (`FIREBASE_ONLY_KEYS`). Partage entre appareils : par le
document publié `portalMail/certificate` (un appareil qui n'a pas de modèle local reprend celui
publié). Choix de Claude.

Chaînes de l'app dans les 7 langues de l'app (règle du dépôt). Les textes **par défaut de
l'attestation** sont fournis en français, anglais, allemand, italien et espagnol ; choisir une
autre langue part des textes anglais.

## 7. Format du modèle

Un seul format, lu par l'aperçu Kotlin et par le serveur :

```json
{
  "version": 1,
  "language": "fr",
  "place": "Genève",
  "signer": { "name": "…", "title": "…", "imageBase64": "" },
  "mail": { "fromName": "…", "fromAddress": "", "replyTo": "", "subject": "…", "body": "…" },
  "blocks": {
    "header": Doc, "title": Doc, "intro": { "one": Doc, "many": Doc },
    "MANDATE": { "one": Doc }, "SHIFT": { "one": Doc, "many": Doc, "line": Doc },
    "MEETING": { "one": Doc, "many": Doc }, "...": "…",
    "closing": Doc, "dateLine": Doc, "footer": Doc
  }
}
```

`Doc` = liste de paragraphes `{ "align": "LEFT|CENTER|RIGHT|JUSTIFY", "size": "SMALL|NORMAL|LARGE|TITLE", "bullet": false, "runs": [Run] }`,
`Run` = `{ "text": "…", "b": true, "i": true, "u": true }`, ou `{ "var": "prénom", ... }`, ou
`{ "gender": ["engagé", "engagée", "engagéx"], ... }`. Pas de HTML stocké : l'éditeur convertit
vers et depuis ce format. Le bloc MANDATE n'a pas de variante « many » (un bloc par mandat).

Publication : à chaque passe de publication du portail, si l'option est active, les appareils
écrivent `orgs/{orgId}/portalMail/certificate` (même mécanisme et même hachage que
`publishMailTemplate`) avec le modèle, `associationName`, le logo, le fuseau (`offsetHours`) et la
table des types de job `{ name, category, description }`. Option coupée : le document est supprimé.
Lisible par le serveur seulement (pas par les comptes du portail).

## 8. Serveur

Nouveau type de demande `CERTIFICATE_EMAIL` dans `portalRequests` (même flux que
`QR_EMAIL` : PENDING → claim → DONE/REJECTED) :
`{ type, uid, from: "AAAA-MM-JJ", to: "AAAA-MM-JJ", jobTypes: [noms, ≤ 50, ≤ 80 caractères] }`.

Traitement :
1. Option active (`volunteer_portal_certificate_enabled`) et modèle publié, sinon
   `NOT_ALLOWED` / `NO_TEMPLATE`.
2. Volontaire propriétaire (`portalEnabled && portalUid == uid`, non révoqué), adresse utilisable
   (`safeRecipient`), sinon raisons existantes. **Les admins sont acceptés** (contrairement au QR :
   l'attestation ne contient aucun identifiant, et les mandats concernent surtout le comité).
3. Lecture des jobs : `orgs/{orgId}/jobs` où `volunteerId == volunteer.id` (l'id ne sort jamais du
   serveur), filtrés comme au §5.
4. Assemblage (§5) puis rendu PDF : A4, marges 25 mm, police Nunito (déjà dans `webform/fonts`,
   embarquée dans le build des functions), logo en haut à gauche ou centré selon l'alignement de
   l'en-tête, retour à la ligne avec des runs mixtes (gras/italique/souligné), justification,
   puces, saut de page si besoin (le pied de page se répète). Bibliothèques : `pdf-lib` +
   `@pdf-lib/fontkit`.
5. E-mail via `sendMail` existant, avec `fromName`, `fromAddress` (vide = compte Gmail) et
   `replyTo` du modèle (adresses vérifiées avec `safeRecipient`, sinon ignorées), pièce jointe `attestation-benevolat-{prénom}-{nom}.pdf`
   (nom ASCII nettoyé), logo inline comme le QR.
6. Aucun contenu de l'attestation n'est écrit dans Firestore ni dans les logs.

Mise en service : version obligatoire de l'app (comme la 3.1.0), parce que les anciennes versions
rejettent un type de demande inconnu après le délai de 2 minutes. Choix de Claude (le propriétaire
n'a pas répondu à cette question).

Appareils : ne traitent jamais `CERTIFICATE_EMAIL` (ils n'ont pas de moteur PDF). Ils ne doivent
pas non plus la rejeter comme type inconnu : la laisser au serveur, y compris après le délai de
2 minutes.

Règles Firestore v20 : création de `CERTIFICATE_EMAIL` par le propriétaire avec validation des
champs ; `portalMail/certificate` écrit par les membres, jamais lu par le portail. Vérifier les
deux copies des règles (`firebase/` et `composeResources/files/`).

## 9. Ce que décide le constructeur

- Intégration exacte de compose-rich-editor (version compatible avec la version de Compose du
  dépôt) et conversion vers le format §7. Si la bibliothèque ne permet pas les étiquettes de
  variable ou d'accord, un rendu surligné non modifiable caractère par caractère suffit.
- Mise en page fine du PDF (filet sous l'en-tête, espacements), tant qu'elle reste sobre et que
  l'aperçu correspond.
- Aperçu Kotlin : l'assemblage du texte (§5) existe en Kotlin (aperçu) et en TypeScript (serveur).
  Les deux passent les **mêmes cas de test partagés** (fichier JSON d'entrées et de textes
  attendus), comme pour les validations partagées existantes.
- Version Room si le réglage demande une migration (normalement non : réglage d'institution).

## 10. Tests

- Cas partagés Kotlin/TS : un shift, plusieurs shifts d'un seul type, plusieurs types, mandat seul,
  mandat sur un seul mois, mandat + shifts, réunions seules, type supprimé (OTHER), description
  vide, chaque genre, période ramenée aux jobs réels, jobs futurs exclus.
- Functions : flux complet dans l'émulateur (option coupée, modèle absent, aucun job, succès avec
  PDF lisible, nom de fichier), aucune trace de `volunteer.id` dans le mail ni le document.
- Règles : création valide/invalide de la demande, `portalMail/certificate` illisible par un compte
  du portail.
- App : sérialisation du modèle aller-retour, défauts par langue.
- Manuel : éditeur sur desktop et Android, PDF reçu ouvert sur iPhone et sur ordinateur.

Docs : CHANGELOG, FIREBASE_SETUP.md (règles v20, déploiement des functions avec hosting).

## 11. Précisions ajoutées à la planification (choix de Claude)

- **Période** : une variable {période} remplace {début}/{fin} dans les textes par défaut, pour
  éviter les dates trop précises et les élisions (« de avril ») : « entre mars et octobre 2026 »,
  « entre avril 2025 et août 2026 », « en mars 2026 » (et équivalents dans les 4 autres langues).
  {début} et {fin} (mois + année) restent disponibles.
- **Troisième variante** pour SHIFT et OTHER : « plusieurs jobs, un seul rôle » (le rôle entre
  dans la phrase, la description suit). Les variantes sont donc `one`, `many`, `manyOneRole`, plus
  le format de ligne `line`.
- **Format** : tous les blocs sont des `{ one, many, manyOneRole, line }` ; les blocs fixes
  (en-tête, titre, conclusion, lieu et date, pied de page) n'utilisent que `one`.
- **Clés des variables** stockées en anglais (`firstName`, `lastName`, `association`, `period`,
  `start`, `end`, `date`, `count`, `role`, `description`, `roles`, `place`, `today`) ; l'éditeur
  affiche des libellés traduits.
- {rôles} occupe seul son paragraphe ; ce paragraphe est remplacé par une ligne par rôle.
  Un paragraphe vide après remplacement (par ex. {description} vide) disparaît.
- **Langues de l'attestation** : les 5 langues du portail (fr, en, de, it, es), pas les 7 de l'app.
- **Jour d'un job** : date civile à Genève de `Job.date` (pas de décalage `offsetHours`).
- **Noms en capitales** : corrigés seulement s'ils n'ont aucune minuscule.
