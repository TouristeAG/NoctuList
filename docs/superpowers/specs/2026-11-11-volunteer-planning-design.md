# Planification des bénévoles par la plateforme bénévole

Date : 2026-11-11 · Branche : `feature/volunteer-portal` · Statut : à relire par le propriétaire du projet.
S'appuie sur [2026-11-10-volunteer-portal-design.md](2026-11-10-volunteer-portal-design.md) et
[2026-11-10-portal-server-design.md](2026-11-10-portal-server-design.md).

## 1. Objectif

Aujourd'hui, le planning du mois se fait à côté de l'app (tableur partagé, messages), puis les shifts
sont saisis à la main ou importés depuis Drive. But : un seul circuit dans NoctuList.

1. L'admin crée en une fois les événements du mois, avec leurs postes.
2. Il publie : tous les bénévoles de l'institution qui ont un compte portail cochent leurs disponibilités.
3. Il ferme les réponses, décide qui fait quoi, puis applique : les shifts sont créés automatiquement.
4. Il peut exporter le planning en Excel, dans la mise en page du tableur utilisé aujourd'hui.

## 2. Décisions validées

| Sujet | Décision |
|---|---|
| Disponibilité de la fonction | Seulement si `isVolunteerPortalFeatureEnabled()` (backend Firebase et portail activé). Sinon, l'entrée est masquée. |
| Point d'entrée | Une carte « Planifier avec les bénévoles » sous « Importer depuis Google Drive » dans le dialogue d'ajout de shift, plus un sous-onglet « Plannings » dans l'onglet Shifts. |
| Unité de travail | Un planning regroupe plusieurs événements (typiquement un mois). |
| Lieu | Chaque événement a un lieu, choisi parmi les lieux de l'app (`VenueEntity`). |
| Heures | Les heures existent seulement dans le planning (portail et export). `Job` n'est pas modifié et ne porte pas d'heures. |
| Date de soirée | Un poste qui commence ou finit après minuit reste rattaché à la soirée de l'événement. Le shift créé prend la date de la soirée. |
| Modes de poste | Bénévoles (N places, visible sur le portail, crée des shifts) ou Équipe externe (simple libellé, invisible sur le portail, ne crée rien). |
| Postes salariés et permanences | Hors périmètre. |
| Confidentialité | Un bénévole ne voit que ses propres choix, jamais ceux des autres. |
| Attribution | Manuelle, avec une aide : chevauchements bloqués, nombre de shifts du mois affiché. Pas d'attribution automatique. |
| Bénévoles sans compte portail | Pas de réponse possible. L'admin peut les ajouter à la main dans l'attribution. |
| Modification après coup | Le planning reste modifiable. « Appliquer » crée, modifie ou supprime les shifts liés, après un récapitulatif. Relancer ne crée pas de doublon. |
| Notifications | Aucun e-mail automatique. |
| Portail « Prochains shifts » | Inchangé. |
| Export | Excel (.xlsx), feuille de synthèse dans le style du tableur actuel, plus une feuille de détail. |

## 3. Modèle

### 3.1 Objets

- **Planning** : `id`, `name` (« Novembre 2026 »), `status`, `responsesCloseAt` (date limite, optionnelle),
  `events`, `createdAt`, `updatedAt`, `publishedAt`, `appliedAt`.
- **Événement** : `id`, `nightDate` (date de la soirée, `YYYY-MM-DD`), `name`, `description`,
  `venueName`, `startMinutes`, `endMinutes`, `posts`.
- **Poste** : `id`, `jobTypeConfigId`, `jobTypeName` (copie pour l'affichage et l'export),
  `startMinutes`, `endMinutes`, `mode` (`VOLUNTEERS` | `EXTERNAL`), `places` (si `VOLUNTEERS`),
  `externalLabel` (si `EXTERNAL`), `shiftTime` (optionnel, voir §3.3).
- **Attribution** : `postId`, `volunteerId`, `jobNanoId` (le shift créé, vide avant application),
  `appliedAt`.
- **Réponse** (disponibilités d'un bénévole pour un planning) : `planningId`, `uid`, `postIds`, `updatedAt`.

Les identifiants (`planning`, `événement`, `poste`) sont des NanoID générés par l'app
(`NanoIdGenerator`).

### 3.2 Heures et soirée

Les heures sont stockées en minutes depuis 00:00 du jour `nightDate`. Elles peuvent dépasser 1440 :
02:00 le lendemain vaut `1560`.

Saisie dans l'éditeur : l'admin tape `HH:MM`. Une heure entre 00:00 et 07:59 est comptée au
lendemain, sauf si l'événement commence lui-même avant 12:00 (événement de journée). Un petit
indicateur « +1 j » s'affiche à côté de l'heure et peut être basculé à la main. Une fin inférieure
ou égale au début passe au lendemain.

Affichage : une heure au lendemain est préfixée du jour (« sam. 02:00 »).

### 3.3 `ShiftTime` du shift créé

Si le type de shift a `requiresShiftTime`, le shift créé reçoit `BEFORE_MIDNIGHT` quand le poste
commence avant 24:00 (minutes < 1440) et `AFTER_MIDNIGHT` sinon. L'éditeur affiche ce choix sur
chaque poste concerné et permet de le changer (champ `shiftTime` du poste). Sinon,
`BEFORE_MIDNIGHT`, comme l'import Drive.

### 3.4 Statuts

| Statut | Portail | Éditer les événements | Réponses | Attribuer et appliquer |
|---|---|---|---|---|
| `DRAFT` | rien | oui | non | oui |
| `OPEN` | visible | oui | oui, jusqu'à `responsesCloseAt` | oui |
| `CLOSED` | rien | oui | non | oui |

Transitions : Publier (`DRAFT` → `OPEN`), Fermer les réponses (`OPEN` → `CLOSED`),
Rouvrir (`CLOSED` → `OPEN`). L'attribution est possible dans tous les statuts, mais l'écran
invite à fermer d'abord. Supprimer un planning ne supprime pas les shifts déjà créés : l'app
demande si elle doit aussi les supprimer.

## 4. Stockage (Firestore uniquement)

La fonction exige le backend Firebase. Les plannings ne passent donc ni par Room ni par
Google Sheets. L'app les lit avec `FirestoreGateway.listenCollection` et les écrit avec
`setPlainDocument`, comme `VolunteerPortalService`. L'édition demande une connexion : sans
réseau, l'écran le dit et passe en lecture seule.

| Chemin | Contenu | Écrit par | Lu par |
|---|---|---|---|
| `orgs/{orgId}/plannings/{planningId}` | Planning complet : événements, postes (y compris externes), attributions | membres de l'app | membres de l'app |
| `orgs/{orgId}/planningRounds/{planningId}` | Vue publique : nom, statut, date limite, événements et postes `VOLUNTEERS` seulement (sans attributions, sans libellés externes), `openPostIds` | l'app, à chaque publication ou modification d'un planning `OPEN`/`CLOSED` | membres ; bénévoles du portail si `status == OPEN` |
| `orgs/{orgId}/planningAvailability/{planningId}_{uid}` | Réponse d'un bénévole | le bénévole lui-même | lui-même, membres de l'app |

Taille : un mois d'environ 30 événements et 6 postes par événement tient largement dans un
document (limite 1 Mo). Pas de sous-collection.

La correspondance `uid` → bénévole passe par `Volunteer.portalUid`, comme pour les demandes du
portail. `volunteer.id` (secret du QR) n'est jamais écrit dans `planningRounds` ni dans les réponses.

## 5. Règles Firestore (v14)

`NOCTULIST_RULES_VERSION = 14`.

- `plannings/{id}` : lecture et écriture réservées aux membres de l'org (mêmes droits que `jobs`).
- `planningRounds/{id}` :
  - membres : lecture et écriture ;
  - bénévoles : `get` et `list` si `portalOn(orgId) && isPortalUser()`, que le bénévole a une
    fiche publiée (`exists(volunteerPortal/{request.auth.uid})`) et que `resource.data.status == 'OPEN'`.
    La requête du portail filtre donc sur `status == 'OPEN'`.
- `planningAvailability/{planningId}_{uid}` :
  - bénévole : `get` de son propre document ; `create`/`update` si l'identifiant vaut
    `planningId + '_' + request.auth.uid`, `uid == request.auth.uid`, le round est `OPEN`,
    `request.time < responsesCloseAt` (si défini), `postIds` est une liste d'au plus 200 éléments
    tous présents dans `openPostIds` du round, et seules les clés `planningId`, `uid`, `postIds`,
    `updatedAt` sont présentes. Pas de `delete` (une liste vide suffit).
  - membres : lecture ; suppression (nettoyage quand un planning est supprimé).

Tests dans `firebase/tests/rules` : lecture refusée d'un round fermé, écriture refusée après la
date limite, poste inconnu refusé, réponse d'un autre uid refusée, lecture des réponses des
autres refusée.

## 6. App : écrans

Interface Compose commune (desktop et Android). Textes dans les 7 langues de l'app, sans tiret
cadratin.

### 6.1 Entrée dans le dialogue d'ajout de shift

`AddJobDialog` (`JobTrackingScreen.kt`) : sous `DriveShiftImportEntry`, une carte de même style,
« Planifier avec les bénévoles », visible seulement si la fonction est disponible (§2). Elle ferme
le dialogue et ouvre l'éditeur d'un nouveau planning.

### 6.2 Sous-onglet « Plannings »

L'onglet Shifts reçoit deux sous-onglets : « Shifts » (écran actuel) et « Plannings » (masqué si
la fonction n'est pas disponible). La liste des plannings montre nom, statut, date limite, nombre
de réponses et places pourvues.

### 6.3 Éditeur de planning

- En-tête : nom, badge de statut, date limite, boutons Excel et Publier (ou Fermer, Rouvrir).
- Liste des événements triés par soirée, une ligne par événement : soirée, nom, lieu, horaires,
  pastilles des postes (les externes en noir avec leur libellé). Un seul événement déplié à la fois.
- Événement déplié : champs de l'événement, puis tableau des postes (type, début, fin, places,
  mode Bénévoles ou Équipe externe, `ShiftTime` si le type l'exige), « + Poste »,
  « Copier les postes d'un autre événement », suppression.
- Sous la liste : « + Événement » et « Dupliquer sur d'autres dates » (copie l'événement déplié,
  postes compris, sur les dates choisies dans un calendrier).
- Supprimer ou modifier les heures d'un poste qui a déjà des réponses : confirmation qui indique
  combien de bénévoles l'avaient coché. Les réponses vers un poste supprimé sont ignorées.

### 6.4 Attribution

- Bandeau des soirées, chacune avec ses places pourvues (`5/8`) ou une coche quand tout est pourvu.
  Les soirées sans poste bénévole sont marquées « externe ».
- Pour la soirée choisie, une matrice : une colonne par poste `VOLUNTEERS` (type, heures,
  `pourvus/places`) et une ligne par bénévole ayant répondu, avec « N shifts ce mois »
  (shifts existants du mois, plus les attributions du planning).
- Cellules : non disponible (vide), disponible (pointillé), attribué (plein), bloqué (la personne
  a déjà un poste qui chevauche celui-ci dans la soirée). Un clic attribue ou retire. Une colonne
  pleine affiche « complet » mais accepte un renfort.
- « Ajouter quelqu'un sans réponse » : recherche parmi les bénévoles actifs ; la personne
  apparaît dans la matrice avec toutes ses cellules attribuables.
- Les attributions sont enregistrées dans `plannings/{id}` à chaque clic (pas de bouton Enregistrer).

### 6.5 Appliquer

Bouton « Appliquer aux shifts » dans l'éditeur et l'attribution. Il calcule un récapitulatif, puis
l'applique après confirmation (§7).

## 7. Appliquer : création des shifts

Pour chaque attribution d'un poste `VOLUNTEERS`, le shift voulu est :

`volunteerId`, `jobTypeConfig` du poste, `venueName` de l'événement, `date` = `nightDate` avec la
même heure de référence que `AddJobDialog`, `shiftTime` selon §3.3. Les champs d'avantages sont
remplis par le même chemin que l'import Drive (`onImportJobs` du ViewModel), pour que STELLAR
et MANUAL se comportent comme une saisie normale.

Comparaison avec l'existant, via `jobNanoId` de chaque attribution :

| Cas | Action | Ligne du récapitulatif |
|---|---|---|
| Pas de `jobNanoId` | créer, enregistrer le `jobNanoId` | « N shifts créés » |
| Shift trouvé, champs différents | mettre à jour | « N modifiés », avec le détail |
| Shift trouvé, `lastModified > appliedAt` | mettre à jour | « modifié à la main depuis, sera écrasé » |
| `jobNanoId` présent, shift introuvable | recréer | « supprimé à la main, sera recréé » |
| Shift lié à une attribution retirée, ou à un poste ou événement supprimé | supprimer | « N supprimés » |
| Places vides | rien | « N places encore vides » |

Les shifts liés à un planning ne portent aucune marque dans `Job` : le lien vit uniquement dans
les attributions. Ensuite, `appliedAt` est mis à jour sur le planning et les attributions. Une
coupure en cours de route laisse un état cohérent, car chaque création enregistre son `jobNanoId`
avant de passer à la suivante. Relancer termine le travail.

## 8. Portail : « Disponibilités »

`portal.html` / `portal.js`, i18n dans `portal-i18n.js` (fr, en, es, de, it).

- Au chargement, requête `planningRounds where status == 'OPEN'`. Les rounds dont la date limite
  est passée sont ignorés. Sans round ouvert, rien n'apparaît.
- Une carte par round : nom, phrase d'aide, date limite. Puis une section par soirée qui a au
  moins un poste ouvert : nom de l'événement, date, lieu, horaires, lien « Toute la soirée », et
  une ligne cochable par poste (type, heures, « sam. 02:00 » pour le lendemain).
- Compteur « N postes sur M soirées » et bouton Envoyer. Le document de réponse est relu à
  l'ouverture pour pré-cocher. Un nouvel envoi remplace le précédent.
- Mobile d'abord : listes, pas de frise.
- Les autres réponses ne sont jamais chargées.

Les appareils n'ont rien à traiter : la réponse est écrite directement par le bénévole, sous
contrôle des règles. Aucune Cloud Function n'est ajoutée.

## 9. Export Excel

Apache POI, déjà utilisé pour l'export des statistiques (`GraphExportBridge.desktop.kt` avec POI
5.3 sur desktop, POI 3.15 sur Android). Disponible dans tous les statuts. Desktop : dialogue
d'enregistrement. Android : partage du fichier, comme l'export des statistiques. Nom :
`planning_<nom>_<date>.xlsx`.

**Feuille « Planning »** : une ligne par événement, triées par soirée.

Colonnes : Date (`JJ/MM/AAAA`), Événement, Lieu, une colonne par type de poste utilisé dans le
planning (ordre de première apparition), Début, Fin (heures de l'événement, `HH:MM`).

Contenu d'une cellule poste (événement × type), en réunissant tous les postes de ce type :

| Situation | Texte | Couleur |
|---|---|---|
| Aucun poste de ce type | vide | aucune |
| Que des postes externes | libellé(s), séparés par « + » | fond noir, texte blanc |
| Postes bénévoles tous pourvus | noms « Prénom N. » séparés par « + » | fond vert clair |
| Places manquantes | noms déjà attribués, puis « Nx bénévoles » par poste incomplet (« 2x 2 bénévoles » si deux postes ont chacun 2 places vides) | fond rouge |

En-tête en gras, bordures fines, largeur de colonne ajustée, première ligne figée.

**Feuille « Détail »** : une ligne par poste. Date, Événement, Lieu, Poste, Début, Fin
(« sam. 02:00 » au lendemain), Assuré par (Bénévoles ou le libellé externe), Places, Attribués
(noms), Manquants.

## 10. Cas limites

- Portail désactivé pendant qu'un planning est ouvert : les règles (`portalOn`) bloquent la lecture
  et les réponses. L'app garde les plannings et l'attribution reste possible.
- Bénévole désactivé ou sans `portalUid` : ses anciennes réponses restent visibles dans la matrice,
  marquées « inactif ».
- Réponse vers un poste supprimé : ignorée à l'affichage. Le portail retire la coche au prochain envoi.
- Type de shift désactivé après coup : le poste garde `jobTypeName`. « Appliquer » le signale et
  ne crée pas ses shifts tant qu'un type actif n'est pas choisi.
- Lieu supprimé : l'événement garde `venueName` en texte.
- Deux appareils admin en même temps : dernier écrit gagne, au niveau du document planning. Le
  listener rafraîchit l'autre écran. Accepté pour une première version.

## 11. Tests

- Kotlin (commonTest) : conversion des heures et règle du lendemain (§3.2), `ShiftTime` (§3.3),
  détection des chevauchements, calcul du récapitulatif d'application (§7, tous les cas du
  tableau), construction de la vue publique (aucun poste externe, aucune attribution, aucun
  `volunteer.id`), contenu et couleurs des cellules d'export (§9).
- Règles : cas listés en §5, dans `firebase/tests/rules`.
- Portail : vérification manuelle avec l'émulateur (`npm run preview`, `seed.mjs` complété par un
  round ouvert).

## 12. Hors périmètre

Postes salariés et permanences, heures dans `Job` et dans « Prochains shifts », e-mails
automatiques, attribution automatique, commentaires des bénévoles sur leurs réponses, modèles de
postes enregistrés, réponses des bénévoles sans compte portail.
