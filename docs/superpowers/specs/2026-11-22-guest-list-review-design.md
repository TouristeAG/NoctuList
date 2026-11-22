# Valider les demandes d'invités depuis l'accès guestlist de la plateforme

Date : 2026-11-22 · Branche : `feature/guest-list-review` · Statut : à valider par le propriétaire.
Complète `2026-11-21-guest-list-access-design.md` (appelée ci-dessous « spec accès »).

## 1. Objectif

Une personne qui a l'accès complet à la guestlist temporaire d'une soirée (spec accès) traite aussi
les demandes d'invités de cette soirée, comme l'admin dans la guestlist admin ou la billetterie :
réponses aux formulaires publics et demandes envoyées depuis la plateforme (ajouts et retraits).

Exemple : le responsable de la soirée du 10 décembre voit en haut de sa section les demandes en
attente pour le 10 décembre, ouvre une demande, la corrige si besoin, puis l'accepte ou la refuse.

## 2. Décisions

| Sujet | Décision |
|---|---|
| Activation | Nouvel interrupteur « La plateforme valide » dans les réglages en ligne, sous « La billetterie valide », dans le bloc des formulaires d'invités. Réglage de l'institution, synchronisé, désactivé par défaut. Désactivé : rien ne s'affiche et toute décision est refusée. |
| Demandes visibles | Les demandes `PENDING_REVIEW` dont la date est celle d'un accès en cours. Si l'accès est limité à un lieu, seulement les demandes de ce lieu (une demande sans lieu reste visible). |
| Types de demandes | Réponses aux formulaires publics, ajouts et retraits envoyés depuis la plateforme. |
| Place | En haut de la section « Guestlist du … », avant la recherche. |
| Ouverture | Toute la demande : nom de la liste, lieu, date, e-mail de contact, téléphone, notes, et chaque personne avec ses accès. Pour un retrait : les invités visés. |
| Correction | Comme l'admin : modifier les noms, retirer ou ajouter une ligne, changer les accès d'une personne, l'e-mail, le téléphone et les notes, puis accepter. |
| Refus | Bouton « Refuser » avec confirmation. |
| Limites | Les mêmes que chez l'admin : une demande depuis la plateforme qui dépasse une limite de son invitation est refusée à l'acceptation, avec le message « Dépasse la limite de l'invitation ». |

## 3. Non-objectifs

- Pas de validation sans accès en cours pour la date.
- Pas de traitement par le serveur : seul un appareil a la clé de chiffrement des contacts.
- Pas de notification ni d'e-mail.
- Pas de changement de la validation dans l'app (guestlist admin, billetterie).

## 4. Invariants de sécurité

- Le `formId` d'une demande n'est jamais publié. La page porte une `reviewKey` (16 hex, hachage du
  `formId`, même principe que `guestKey`).
- L'appareil revérifie tout avant d'appliquer : interrupteur actif, accès en cours du compte, date
  et lieu de la demande couverts par l'accès, demande toujours `PENDING_REVIEW`.
- Les accès choisis sont filtrés sur ceux du lieu de la demande, comme pour un ajout (spec accès
  §6.3).
- La personne voit l'e-mail et le téléphone écrits dans la demande : c'est voulu, l'admin a choisi
  de lui confier la soirée.

## 5. Données

### 5.1 Réglage

`InstitutionSettingsKeys.GUEST_FORMS_PORTAL_VALIDATE_ENABLED`, même circuit que
`GUEST_FORMS_BILLETERIE_VALIDATE_ENABLED` (SettingsManager, ViewModel, SyncSettingsBindings,
SyncOnlinePage). Les appareils republient les pages guestlist quand il change.

### 5.2 Page `guestListPortal/{uid}`, schema 2

Chaque accès en cours reçoit, si l'interrupteur est actif :

```
reviews: [ {
  reviewKey, source: FORM | PORTAL, kind: ADD | REMOVE,
  listName, venueName, eventDate, submittedAt,
  contactEmail, emergencyPhone, notes,
  people: [ { name, accessIds: [..] } ],          // ADD
  removeGuests: [ { guestKey, name } ],            // REMOVE
  requestedBy                                       // PORTAL : nom de la personne
} ]
```

Interrupteur coupé : pas de champ `reviews`. Les autres champs ne changent pas. La page est
republiée à chaque nouvelle demande et à chaque décision, comme aujourd'hui pour les invités.

### 5.3 Décisions dans `guestListEdits/{editId}`

Deux actions de plus : `ACCEPT` et `REJECT`.

```
{ uid, email, grantId, eventDateMillis, action: ACCEPT | REJECT, reviewKey,
  people: [ { name, accessIds } ] (ACCEPT d'un ajout, ≤ 100),
  contactEmail, phone, notes (ACCEPT d'un ajout), status: PENDING, createdAt }
```

Nouveaux refus : `NOT_PENDING` (déjà traitée ou disparue), `OVER_LIMIT`, `DISABLED`.

## 6. Parcours

### 6.1 Plateforme

```
Guestlist du mercredi 10 décembre
┌ 2 demandes à valider ───────────────────────────┐
│ ▌ Groove Collective · 4 personnes · formulaire  │
│ ▌ Marie Dupont · retrait de 1 invité            │
└─────────────────────────────────────────────────┘
[ Rechercher… ]            12 entrés sur 40   [ Ajouter des invités ]
```

- Un clic ouvre la fenêtre de la demande. Ajout : champs modifiables (liste des personnes avec
  leurs accès à cocher, e-mail, téléphone, notes), puis « Refuser » et « Accepter ». Retrait : la
  liste des invités visés, puis « Refuser » et « Accepter ».
- Après l'envoi, la carte porte « En cours… » jusqu'à la republication. Un refus de l'appareil
  s'affiche sous la section, comme pour les autres modifications.

### 6.2 Appareil

1. Vérifie l'interrupteur (`DISABLED`), puis l'accès comme aujourd'hui (`NO_GRANT`).
2. Retrouve la demande par `reviewKey` parmi les `PENDING_REVIEW` couvertes par l'accès
   (`NOT_PENDING`).
3. **ACCEPT d'un ajout** : `acceptGuestForm` avec les personnes, l'e-mail, le téléphone et les
   notes envoyés, accès filtrés sur le lieu. `false` à cause d'une limite : `OVER_LIMIT`.
4. **ACCEPT d'un retrait** : `acceptGuestFormRemoval`.
5. **REJECT** : `rejectGuestForm`.
6. `reviewedBy` : « Plateforme · nom du titulaire ».

Il faut sortir la logique d'acceptation du ViewModel vers l'hôte du portail
(`VolunteerPortalHost`) pour que le processeur l'appelle sans l'interface.

## 7. Règles Firestore (v20)

- `guestListEdits` `create` : actions `ACCEPT` et `REJECT` en plus, `reviewKey` 16 hex
  obligatoire pour elles, `people` liste ≤ 100 (noms ≤ 60, ≤ 20 accès chacun), mêmes contrôles de
  session, de date et de clés qu'en v19.
- `NOCTULIST_RULES_VERSION = 20`, copies synchronisées.

## 8. Tests

- **Kotlin** : page avec et sans interrupteur, filtre date et lieu, pas de `formId` ; processeur :
  ACCEPT avec correction, ACCEPT d'un retrait, REJECT, `DISABLED`, `NO_GRANT`, `NOT_PENDING`,
  `OVER_LIMIT`, accès hors lieu retirés.
- **Règles** : ACCEPT et REJECT acceptés avec la bonne date, refusés sans `reviewKey`, avec une
  autre date ou un `people` trop long.
- **Portail** : fonctions pures (résumé d'une demande, construction de la décision), puis
  navigateur sur les émulateurs.

## 9. Textes

7 langues de l'app, 5 du portail, sans tiret cadratin.

- **App** : « La plateforme valide ».
- **Portail** : « {n} demandes à valider » (1 : « 1 demande à valider »), « formulaire »,
  « depuis la plateforme », « Retrait de {n} invités », « Accepter », « Refuser », « Refuser cette
  demande ? », messages `NOT_PENDING`, `OVER_LIMIT`, `DISABLED`.
