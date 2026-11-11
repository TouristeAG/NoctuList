# Refonte des réglages « Sync & données » : spec de conception

Date : 2026-11-12 · Branche : `feature/volunteer-portal` · Statut : direction validée en discussion, spec à relire.

## 1. Objectif

La catégorie « Sync & données » est une seule longue page surchargée : configuration technique en
haut, paragraphes d'explication dans chaque carte, étapes numérotées, liste à cocher qui répète le
bandeau d'état, et des réglages mal conçus (ajout d'un membre par UID dans deux grandes zones de
texte). La refonte organise ces réglages autour de ce qu'un admin vient y faire, dans l'ordre de
fréquence :

1. vérifier que tout marche (souvent) ;
2. ajouter ou retirer quelqu'un de l'équipe (parfois) ;
3. activer une fonction : formulaires, plateforme, photos, import (rarement) ;
4. réparer ou reconfigurer (très rarement, l'assistant Firebase le fait).

Aucune fonction n'est supprimée, sauf « Synchroniser maintenant » et « Tester le statut » en mode
Firebase, qui n'y servent à rien. Toutes les infos nécessaires restent accessibles ; ce qui se
répète disparaît ; les explications passent derrière un « ? ».

## 2. Décisions validées

| Sujet | Décision |
|---|---|
| Découpage | « Sync & données » devient 4 catégories du menu de gauche (ordinateur) et 4 cartes repliables (Android) : Connexion, Équipe, Fonctions en ligne, Sauvegarde. En mode Sheets : Connexion et Synchronisation. |
| Plateformes | Ordinateur et Android, mêmes composants partagés (`commonMain`). |
| Aide | Un « ? » à côté du titre de chaque bloc ouvre une fenêtre d'explication. Le « ? » de Connexion ouvre le guide Firebase (assistant). Plus de bouton « Guide », plus de paragraphe dans la page. |
| Configuration du projet | Quitte la page. « Reconfigurer… » ouvre une fenêtre « Projet Firebase » (coller la configuration, identifiants, client Web, secret, lien vers le guide). |
| Organisations | Restent dans Connexion : liste compacte + « Ajouter une organisation » (petite fenêtre avec validation). |
| Ajouter une personne | Un bouton ouvre une fenêtre : QR en grand, puis « Pas de caméra ? » avec le code de configuration à copier. Le code d'invitation est créé automatiquement ; « Désactiver les anciens QR » est dans le menu de la fenêtre. |
| Qui peut rejoindre | Un choix en deux cartes : « Toute personne qui scanne le QR » ou « Seulement certaines adresses ». Les domaines n'apparaissent qu'avec la deuxième, en pastilles. |
| Membres | Une ligne par membre (initiales, e-mail, pastille de rôle). Un clic ouvre un panneau : rôle Membre / Admin et « Retirer de l'équipe », avec confirmation. Visible seulement pour les admins de l'organisation, jamais sur sa propre ligne. Plus aucun champ UID ni e-mail. |
| Site public | Une seule ligne d'état en haut de Fonctions en ligne (adresse du site, traitement automatique). Les fonctions dessous ne répètent ni lien ni état. |
| Connexion Google des bénévoles | À l'activation, propose les domaines de l'équipe déjà connus au lieu d'un champ vide. |
| Synchroniser maintenant | Retiré en mode Firebase (synchro continue). Gardé en mode Sheets. |
| Miroir Sheets | Renommé « Copie de sauvegarde dans Google Sheets ». Fréquence en menu (Chaque heure, Chaque jour, Seulement à la main). Les 9 noms de feuilles restent repliés avec les valeurs par défaut. |
| Lecture seule | Une seule mention en haut de page pour les non-admins, au lieu d'un rappel par carte. |

## 3. Style visuel

Objectif : calme, aéré, lisible. Pas de décoration « carré + icône » sur chaque ligne.

- **Page** : titre (`titleLarge`) + une ligne de sous-titre en couleur atténuée. Contenu limité à
  720 dp de large sur ordinateur, centré à gauche. 28 dp entre les blocs.
- **Bloc** : en-tête = titre (`titleMedium`, semi-gras) + bouton « ? » à droite. Contenu dans une
  seule surface (`surfaceContainerLow`, coins 16 dp, sans ombre). Lignes séparées par un filet fin
  (`outlineVariant` à 40 %).
- **Ligne de réglage** (`SettingsRow`) : marge 16 dp horizontale, 14 dp verticale. Titre
  (`bodyLarge`, medium), une phrase d'effet en dessous (`bodyMedium`, atténuée, 2 lignes max),
  contrôle à droite (interrupteur, bouton texte, chevron). Toute la ligne est cliquable quand elle
  porte un interrupteur ou ouvre quelque chose.
- **Sous-options** : apparaissent avec une animation sous la ligne qui les active, dans la même
  surface, décalées de 16 dp.
- **Pastilles d'état** (`StatusPill`) : petit libellé arrondi (8 dp) sur fond teinté, texte seul :
  vert « Actif », orange « À configurer », neutre « Désactivé ».
- **Choix exclusif** (`ChoiceCards`) : deux cartes côte à côte (empilées sur téléphone), titre + une
  ligne ; la carte choisie a un contour `primary` de 2 dp et une coche.
- **Domaines** : pastilles `InputChip` avec ×, plus une pastille « + Ajouter » qui ouvre un champ en
  ligne.
- **Membres** : cercle d'initiales (pas d'icône), e-mail, pastille Admin/Membre.
- **Icônes** : seulement pour des actions (?, copier, fermer, chevron). Aucune icône décorative.
- Clair et sombre via `MaterialTheme`, comme le reste de l'app.

## 4. Contenu des pages (mode Firebase)

### 4.1 Connexion
- **État** : une surface avec pastille (« Synchronisé » / « À configurer » / « Non connecté »),
  « Connecté à {org} en tant que {e-mail} », boutons « Se déconnecter » et « Reconfigurer… ». S'il
  manque quelque chose, l'état le dit en une phrase et propose le bon bouton (« Se connecter »,
  « Reconfigurer… »).
- **Organisations** : une ligne par ID ; « Ajouter une organisation » ouvre une petite fenêtre (champ
  + validation existante : format, doublon) ; retirer avec confirmation.
- **Fenêtre « Projet Firebase »** (via « Reconfigurer… ») : reprend `FirebaseAdminProjectConfigCard`
  (coller la config, Project ID, Application ID, clé API, client Web, secret, masquage des secrets
  pour les non-admins) + lien « Ouvrir le guide ».
- **Supprimé** : texte d'intro, étapes numérotées, liste à cocher, bouton « Guide » séparé.

### 4.2 Équipe
- **Ajouter une personne** : bouton → fenêtre : QR (240 dp), « Pas de caméra ? Copie ce code dans
  l'app sur l'autre appareil » + code de configuration en police mono avec « Copier ». Menu de la
  fenêtre : « Désactiver les anciens QR » (régénère le code d'invitation, avec confirmation). Si le
  code d'invitation n'existe pas encore, il est créé à l'ouverture (admin uniquement).
- **Qui peut rejoindre l'équipe ?** : `ChoiceCards`. Choisir « Seulement certaines adresses »
  affiche les domaines (pastilles + ajout). Revenir à « Toute personne » vide la liste après
  confirmation. Même stockage qu'aujourd'hui (`allowedEmailDomains`).
- **Membres** : liste ; clic → panneau (dialogue sur ordinateur, bottom sheet sur Android) : rôle
  Membre / Admin (sélecteur segmenté), « Retirer de l'équipe ». « Rendre admin » demande :
  « {e-mail} pourra modifier les réglages et l'équipe. Continuer ? ». Retirer demande une
  confirmation. Rafraîchissement par glissement ou bouton discret.
- Technique : nouvelle action `FirebaseTeamActions.setRoleByEmail(email, role)` et
  `removeMemberByEmail(email)` qui résolvent l'UID côté code sans jamais l'exposer à l'UI (règle du
  code existant). Les règles Firestore restent la vraie barrière : seuls les admins de
  l'organisation, et jamais sur eux-mêmes.

### 4.3 Fonctions en ligne
- **Site public** : une ligne « Site public : {adresse} » avec Copier, et une pastille « Traitement
  automatique actif depuis {date} » ou « inactif ». « ? » : ce qu'est le site, comment le publier.
- **Formulaires de guestlist** : interrupteur ; si actif : « La billetterie valide les formulaires ».
- **Plateforme bénévole** : interrupteur ; si active : lien du portail (Copier), « Afficher le logo »,
  « Connexion avec Google » (interrupteur ; domaines vérifiés en pastilles ; à la première
  activation, les domaines de l'équipe sont proposés et vérifiés en un clic).
- **Photos de profil** : interrupteur.
- **Import Google Drive** : interrupteur ; message d'erreur seulement s'il y en a un.

### 4.4 Sauvegarde
- **Copie de sauvegarde dans Google Sheets** : interrupteur ; si active : ID du tableur, fréquence
  (menu : Chaque heure = 60, Chaque jour = 1440, Seulement à la main = 0 minutes, mêmes réglages
  stockés qu'aujourd'hui), « Exporter maintenant », clé du compte de service (état + importer), bloc
  replié « Noms des feuilles ».
- **Changer de stockage** : ligne « Passer à Google Sheets… » qui ouvre l'assistant de migration
  existant.

## 5. Mode Google Sheets

- **Connexion** : ID du tableur, clé du compte de service (état + importer), bloc replié « Noms des
  feuilles », « Enregistrer ».
- **Synchronisation** : « Synchroniser maintenant » + « Dernière synchro il y a … », fréquence
  (menu : 1, 5, 15, 30, 60 minutes), « Passer à Firebase… ».

## 6. Composants

Nouveaux composants partagés (`ui/components/settings/`) : `SettingsPage`, `SettingsBlock`
(titre + « ? » + surface), `SettingsRow`, `SettingsSwitchRow`, `StatusPill`, `ChoiceCards`,
`DomainChips`, `HelpDialog`. Pages : `SyncConnectionPage`, `SyncTeamPage`, `SyncOnlinePage`,
`SyncBackupPage`, `SheetsConnectionPage`, `SheetsSyncPage`. Dialogues : `FirebaseProjectDialog`,
`AddOrganisationDialog`, `AddPersonDialog`, `MemberDialog`.

`FirebaseSyncSettingsSection` est découpé dans ces pages ; les écrans ordinateur
(`SettingsScreen.desktop.kt`, `DesktopSettingsNav`) et Android (`SettingsScreen.kt`,
`ExpandableSettingsCategory`) appellent les pages au lieu de la grosse section. La logique existante
(callbacks du ViewModel, `SettingsManager`) ne change pas.

Les textes d'explication actuels ne sont pas jetés : ils deviennent le contenu des « ? ». Textes en
7 langues, sans tirets longs ni tournures artificielles.

## 7. Tests

- Unitaires : `setRoleByEmail` / `removeMemberByEmail` (e-mail inconnu, plusieurs comptes, refus
  sur soi-même), conversion fréquence ↔ minutes, état de connexion (prêt / à configurer / non
  connecté).
- Compilation ordinateur + Android, `check-i18n`.
- Vérification visuelle : lancer l'app ordinateur, parcourir les 4 pages en clair et en sombre,
  en admin et en non-admin, et en mode Sheets.

## 8. Hors périmètre

Catégorie E-mail, autres catégories de réglages, assistant Firebase lui-même, logique de synchro.
