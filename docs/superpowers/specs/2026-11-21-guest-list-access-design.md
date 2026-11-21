# Accès complet à la guestlist temporaire depuis la plateforme

Date : 2026-11-21 · Branche : `feature/guest-portal` · Statut : validé en discussion.
S'appuie sur `2026-11-21-guest-portal-roles-invites-design.md` (une adresse = un compte, fenêtre
d'affichage, règles v18).

## 1. Objectif

L'admin donne à une personne (bénévole, invité permanent ou contact d'une liste temporaire)
l'accès complet à la guestlist temporaire d'une soirée. Par exemple, le responsable de la soirée du
10 décembre. La personne voit alors, dans sa plateforme, tous les invités temporaires de cette date
et les gère directement : elle peut en ajouter, modifier le nom et les accès, en supprimer, et voir
qui est entré.

## 2. Décisions validées

| Sujet | Décision |
|---|---|
| Qui peut recevoir l'accès | Bénévole, invité permanent, contact d'une liste temporaire : tout profil avec un compte plateforme. |
| Où l'admin le donne | Carte « Accès plateforme » du profil, bouton « Gérer les accès à la guestlist ». |
| Fenêtre de gestion | Accès en cours (à venir), puis, séparé en dessous, l'historique (passés ou retirés). |
| Portée d'un accès | Une date. Lieu optionnel : par défaut tous les lieux de la date. |
| Durée | Jusqu'au lendemain de la date à 8h00 (`visibleUntil`), ou jusqu'au retrait. |
| Contenu côté plateforme | Uniquement les invités temporaires de la date (et du lieu). Pas de QR. |
| Modifications | Appliquées directement, sans validation de l'admin. |

## 3. Non-objectifs

- Pas de QR, de solde, de bénévoles ni d'invités permanents dans cette vue.
- Pas de validation d'entrée depuis la plateforme : l'état « entré » est affiché, pas modifié.
- Pas d'accès sans e-mail vérifié : session vérifiée ou compte Google lié seulement.

## 4. Invariants de sécurité

- Les identifiants des invités (`nanoId`) ne quittent jamais les appareils. La plateforme ne
  connaît que leur `guestKey`.
- Une modification ne vise qu'un invité temporaire de la date et du lieu d'un accès actif du compte.
  L'appareil le vérifie à nouveau avant d'appliquer.
- Les contacts chiffrés (e-mail, téléphone) ne sont jamais écrits par la plateforme. Un invité
  ajouté reprend le contact de la liste choisie, côté appareil.
- Supprimer un invité dont l'entrée a servi est refusé.

## 5. Modèle de données

### 5.1 Accès accordés

Champ `guestListGrantsJson: String = ""` sur `Volunteer` et `Guest` (Room 56 → 57, synchronisé,
jamais écrit dans Google Sheets). Il contient une liste JSON de :

```
{ id, eventDateMillis, venueName ("" = tous les lieux), grantedBy, grantedAt, revokedAt (0 = actif), revokedBy }
```

Un accès est **en cours** si `revokedAt == 0` et `now < visibleUntil(eventDateMillis)`, sinon il
est dans l'historique. Le titulaire est le profil ; son compte est son `portalUid` (fusion par
adresse incluse).

### 5.2 Page `guestListPortal/{uid}`, schema 1

Publiée par les appareils pour chaque compte qui a au moins un accès en cours ; supprimée sinon.

```
payload: {
  association,
  grants: [ {
    grantId, eventDate, venueName ("" = tous), visibleUntil,
    lists: [ { name, venueName } ],            // listes de la soirée, pour l'ajout
    accesses: [ { id, name, venueName } ],     // accès que la soirée propose
    guests: [ { guestKey, firstName, lastName, listName, venueName,
                accessIds: [..], accessNames: [..], entryUsed, emergencyPhone } ]
  } ]
}
champs du document : schema, payload, contentHash, updatedAt, expiresAtMillis (max visibleUntil),
                     grantDates: [eventDateMillis…] (pour les règles)
```

Les identifiants d'accès sont exposés, car la plateforme doit pouvoir les attribuer. Ils ne
désignent que des zones du lieu, pas des personnes.

### 5.3 Modifications `guestListEdits/{editId}`

Créées par la plateforme et traitées par un appareil NoctuList : il faut la clé de chiffrement
pour recopier les contacts. Il n'y a pas de traitement serveur.

```
{ uid, email, grantId, eventDateMillis, action: ADD | UPDATE | DELETE,
  guestKey ("" pour ADD), firstName, lastName, listName (ADD), venueName (ADD), accessIds: [..],
  status: PENDING, createdAt }
```

L'appareil répond `status: DONE` ou `REJECTED` avec `reason` : `NO_GRANT`, `NOT_FOUND`,
`ENTRY_USED`, `INVALID`. Les documents traités sont supprimés au bout de 7 jours.

## 6. Parcours

### 6.1 App : donner l'accès

- Carte « Accès plateforme », quand le profil a un compte : bouton « Gérer les accès à la
  guestlist ».
- La fenêtre affiche :
  - **Accès en cours** : date, lieu (« Tous les lieux » par défaut), bouton « Retirer » ;
  - **Historique** : date, lieu, « donné par … le … », et « retiré » le cas échéant ;
  - **Ajouter un accès** : date (sélecteur de date), lieu optionnel (« Tous les lieux » ou un lieu
    actif), puis « Ajouter ».
- `grantedBy` et `revokedBy` : le compte qui agit sur l'appareil (nom affiché de l'appareil ou du
  membre).

### 6.2 Plateforme

- Pour chaque accès en cours, une section séparée sous tout le reste : « Guestlist du mercredi
  10 décembre » (avec le lieu s'il est limité).
- En tête : une recherche par nom, le compteur « 12 entrés sur 40 », et un bouton « Ajouter des
  invités ».
- Les invités sont regroupés par liste. Chacun est sur une ligne : nom, accès, contact d'urgence,
  état « Entré » ou « Pas encore entré ». Les boutons « Modifier » et « Supprimer » n'apparaissent
  que si l'entrée n'a pas servi.
- **Ajouter des invités** : un nom par ligne, une liste à choisir (une liste existante de la
  soirée, ou « Nouvelle liste » avec son nom), les accès à cocher, et le lieu quand l'accès couvre
  tous les lieux et que la liste est nouvelle.
- **Modifier** : prénom, nom, accès.
- **Supprimer** : confirmation, puis suppression.
- Après un envoi, l'invité porte le badge « En cours… » jusqu'à la republication de la page. En cas
  de refus, un message apparaît sous la section.
- Visible pour une session vérifiée ou une session Google liée.

### 6.3 Appareil : traitement

À la réception d'une demande `PENDING`, l'appareil la réclame (même mécanisme que les demandes),
puis :

1. Il retrouve le titulaire, c'est-à-dire le profil dont le `portalUid` est l'`uid` de la demande,
   ainsi que l'accès `grantId`. L'accès doit être en cours et sa date doit correspondre à celle de
   la demande. Sinon : `NO_GRANT`.
2. **ADD** : il crée l'invité temporaire avec la date, la liste (`temporaryArtistName`) et le lieu
   (celui de l'accès s'il est limité, sinon celui de la demande, qui doit être un lieu actif).
   Les accès sont filtrés sur ceux du lieu. Le contact (e-mail, téléphone) est recopié d'un invité
   de la même liste s'il en existe un ; sinon il reste vide.
3. **UPDATE** : il retrouve l'invité par `guestKey` parmi les invités de la date et du lieu, puis
   modifie le nom (un champ vide garde la valeur, nom en majuscules) et les accès. Une entrée
   utilisée donne `ENTRY_USED`.
4. **DELETE** : même recherche, refus si l'entrée a servi, sinon suppression.
5. Il republie les pages concernées : celle de la guestlist et celle des listes temporaires.

## 7. Règles Firestore (v19)

```
match /orgs/{orgId}/guestListPortal/{uid}
  get : tempGuestPortalOn && (isPortalUser() && uid == auth.uid
                              || isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid)
        && now < expiresAtMillis
match /orgs/{orgId}/guestListEdits/{editId}
  create : tempGuestPortalOn && (isPortalUser() || isPortalGoogleUser(orgId))
           && d.uid == portalUidOf(orgId) && d.email == token.email
           && d.eventDateMillis in get(guestListPortal/d.uid).data.grantDates
           && clés limitées, action ∈ {ADD, UPDATE, DELETE}, status == 'PENDING',
              noms valides ou vides, guestKey 16 hex (sauf ADD), accessIds liste ≤ 20,
              listName ≤ 80
  get    : même session, resource.data.uid == portalUidOf(orgId)
```

Aucune `list`, aucune modification ni suppression côté web. Les membres passent par la règle
générale (catch-all). `NOCTULIST_RULES_VERSION = 19`.

## 8. Tests

- **Kotlin** :
  - codec des accès accordés : en cours, historique, retrait ;
  - construction de la page : date, lieu limité ou tous, pas de `nanoId`, pas de QR, listes,
    accès ;
  - traitement des demandes : ADD avec liste existante (contact recopié) ou nouvelle liste,
    UPDATE, DELETE, `ENTRY_USED`, `NO_GRANT` (accès retiré, échu ou autre date), `NOT_FOUND`
    (autre lieu) ;
  - migration 56 → 57.
- **Règles** :
  - lecture de la page : session vérifiée oui, session non vérifiée non, Google lié oui, autre
    compte non, page échue non ;
  - création d'une demande : bonne date oui ; autre date, autre compte, clé invalide, action
    inconnue : non ;
  - aucune `list`, aucune modification.
- **Portail** : fonctions pures (regroupement par liste, compteur, recherche), puis navigateur sur
  les émulateurs.

## 9. Textes

Dans les 7 langues de l'app et les 5 du portail, sans tiret cadratin :

- **App** : « Gérer les accès à la guestlist », « Accès en cours », « Historique », « Ajouter un
  accès », « Tous les lieux », « Retirer », « Donné par %1$s le %2$s », « Retiré le %1$s ».
- **Portail** : « Guestlist du {date} », « {n} entrés sur {total} », « Entré », « Pas encore
  entré », « Nouvelle liste », « Nom de la liste », « Supprimer », « En cours… », plus les messages
  de refus.
