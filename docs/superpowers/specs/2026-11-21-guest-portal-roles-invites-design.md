# Espace invités, suite : rôles fusionnés, invitations et nouvelle présentation

Date : 2026-11-21 · Branche : `feature/guest-portal` · Statut : à relire par le propriétaire du projet.
Complète `2026-11-20-guest-portal-design.md` (appelée ci-dessous « spec invités ») et remplace les
points de cette spec cités au §12.

## 1. Objectif

Quatre changements sur l'espace des invités temporaires et sur la plateforme en général :

1. **Une adresse, une personne, un compte.** Tous les rôles qui partagent une adresse sont
   rattachés au même compte portail et proposés automatiquement dans la plateforme.
2. **Les listes gérées s'affichent chez les bénévoles et les invités permanents**, dans une
   section séparée en bas de leur espace, et seulement pour les événements à venir.
3. **Invitations depuis la plateforme**, ouvertes événement par événement par l'admin, avec des
   limites propres à chaque événement et la même validation que le formulaire d'invités.
4. **Nouvelle présentation des invités temporaires** : une carte par invité avec des modules, un
   bouton « Modifier » pour les noms, des textes généraux (plus de « groupe » ni de « manager »).

Réussite :

- une personne bénévole et invitée permanente avec la même adresse voit ses deux profils sans
  qu'aucun accès supplémentaire soit ouvert à la main ;
- un bénévole dont l'adresse est le contact d'une liste d'invités temporaires voit cette liste en
  bas de son espace, jusqu'au lendemain de l'événement à 8h00, puis plus du tout ;
- l'admin ouvre les invitations pour un événement précis avec ses limites ; la personne ajoute ou
  retire des invités depuis la plateforme ; chaque demande arrive dans la liste de validation des
  formulaires et ne prend effet qu'après acceptation ;
- un responsable corrige le prénom ou le nom d'un de ses invités sans passer par l'admin.

## 2. Décisions validées en discussion

| Sujet | Décision |
|---|---|
| Rôles | Une adresse = une personne = un compte. Bénévole, invité permanent et contact d'une liste d'invités temporaires sur la même adresse partagent le compte. |
| Comparaison | E-mail du profil et compte Google lié (`portalGoogleEmail`). |
| Affichage | Profils personnels (bénévole, invité permanent) avec le sélecteur en haut. Listes gérées dans une section « Invités que je gère », en bas, séparée. Une personne qui ne fait que gérer des invités voit la page « Mes invités ». |
| Fenêtre d'affichage | Une liste est visible jusqu'au lendemain de l'événement à 8h00 (heure de Genève), pour tout le monde. Les listes passées ne s'affichent plus. |
| Invitations | Interrupteur « Peut inviter » par événement. Limites par événement : nombre maximum de personnes, accès distribuables, nombre maximum par accès. Des événements peuvent être ouverts et d'autres non. |
| Événement sans liste | L'admin peut ouvrir les invitations pour un événement où la personne n'a pas encore de liste. |
| Validation | Ajouts et retraits passent par la liste de validation des formulaires d'invités. |
| Modification | Prénom et nom de chaque invité, plus le mot de passe du compte. Rien d'autre. |
| Textes | « Mes groupes » devient « Mes invités ». Plus de « groupe » ni de « manager » dans les textes visibles. |
| E-mails | Toujours aucun envoi automatique. |

*Mes choix, non discutés :* le nom et le prénom se modifient sans validation (§7) ; un retrait
n'est possible que tant que l'entrée n'a pas servi ; les invitations restent possibles jusqu'à la
fin de la fenêtre d'affichage.

## 3. Non-objectifs et anti-objectifs

- Pas de création d'invités sans validation de l'admin.
- Pas de suppression ni de modification des comptes Firebase Auth par le serveur (question
  ouverte, traitée à part).
- Pas de changement du formulaire public d'invités, de son adresse ni de ses réponses.
- Anti-objectif : une liste d'invités temporaires lisible sans e-mail vérifié sous un compte que
  l'appareil n'a pas créé lui-même (§4).
- Anti-objectif : une personne qui voit ou modifie les invités d'une liste dont elle n'est pas le
  contact.
- Anti-objectif : une régression de l'espace bénévole et de l'espace invité permanent.

## 4. Invariants de sécurité

Ceux de la spec invités (§4) restent valables. En plus :

- **Lecture sans vérification.** Les pages temporaires (`temporaryGuestPortal`,
  `temporaryGuestPortalQr`) ne sont lisibles sans e-mail vérifié que si l'appareil a créé
  lui-même le compte (`deviceCreated: true` dans la page). Sous un compte réutilisé, elles
  demandent une session vérifiée ou Google, comme les autres pages.
- **Invitations.** Une invitation n'est acceptée que pour un modèle d'invitation dont le
  `portalUid` est celui de la session. Aucun lien public n'existe pour ces modèles.
- **Retraits.** Un retrait ne vise que des invités de la liste liée au modèle d'invitation. Il
  passe par un `guestKey`, jamais par un `nanoId`.
- **Noms.** Une demande de nom ne vise qu'un invité présent sur la page de la session, entrée non
  utilisée.

## 5. Une adresse, un compte

### 5.1 Règle de rattachement

Clé de comparaison : adresse en minuscules, sans espaces autour. Pour chaque profil :

| Profil | Adresses comparées |
|---|---|
| Bénévole | `email`, `portalGoogleEmail` |
| Invité permanent | `email`, `portalGoogleEmail` |
| Liste d'invités temporaires | `temporaryContactEmail` |

Dès qu'un profil de l'adresse a un compte (`portalUid` non vide), les autres profils de la même
adresse sont rattachés à ce compte (`portalUid`, `portalEnabled = true`), si leur espace est actif
(`guest_portal_enabled` pour les invités permanents, `temp_guest_portal_enabled` pour les listes).
Le rattachement est fait par l'appareil, à chaque synchronisation d'un profil ou d'un réglage, et
par le CLAIM (spec invités §8), qui lie déjà tous les profils d'une adresse.

Le rattachement ne crée aucun compte et n'envoie aucun e-mail. Il ne se fait que vers un compte
existant ; sans compte, rien ne change tant que l'admin n'ouvre pas un accès.

### 5.2 Conflit

Deux profils de même adresse liés à deux comptes différents ne sont pas fusionnés. La carte
« Accès plateforme » du profil le plus récent affiche « Cette adresse est déjà liée à un autre
compte » ; l'admin coupe l'un des deux accès pour lever le conflit. Même refus que
`LINKED_ELSEWHERE` côté serveur.

### 5.3 Ouvrir l'espace d'une liste sur une adresse qui a déjà un compte

Remplace le point du §16 de la spec invités qui interdisait de reprendre l'uid d'un bénévole ou
d'un invité permanent :

1. Un profil de l'adresse a un compte : la liste est rattachée à ce compte. Pas de `signUp`, pas de
   mot de passe. La page est publiée avec `deviceCreated: false`. La fenêtre de résultat dit
   « Cette adresse a déjà un compte. La liste apparaît dans son espace. »
2. Une autre liste de l'adresse a un compte créé par l'appareil : comportement actuel, page avec
   `deviceCreated: true`.
3. Sinon `signUp` : succès, page avec `deviceCreated: true` ; `EMAIL_EXISTS`, comportement actuel
   (CLAIM vérifié).

`deviceCreated` est calculé par l'appareil : vrai si le `portalUid` vient d'un `signUp` fait par un
appareil pour une liste (nouveau champ Room `temporaryPortalCreated`, §10), faux sinon. Il passe à
faux dès qu'un bénévole ou un invité permanent est rattaché au même compte.

### 5.4 Rôle retiré, adresse modifiée

- Bénévole ou invité supprimé, accès coupé, liste fermée : seule la page de ce rôle disparaît. Le
  compte garde les autres rôles.
- Plus aucun rôle : toutes les pages du compte sont supprimées. Le compte Firebase Auth reste
  (§3).
- Adresse modifiée sur un profil : le profil quitte son compte (`portalUid = ""`) puis suit la
  règle §5.1 pour sa nouvelle adresse. Ses pages sous l'ancien compte sont supprimées à la
  publication suivante.

### 5.5 Google

Une liste dont l'adresse de contact est le `portalGoogleEmail` d'un profil rattaché suit ce
compte. Les règles des deux collections temporaires acceptent la session Google du compte
(`isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid`), comme `guestPortal`.

## 6. Affichage dans la plateforme

### 6.1 Structure

Après connexion, la page lit `volunteerPortal`, `guestPortal` et `temporaryGuestPortal` du compte
(spec invités §7.5, inchangé pour la lecture).

| Profils trouvés | Affichage |
|---|---|
| Bénévole et/ou invité permanent, sans liste | Comme aujourd'hui (sélecteur si deux profils). |
| Bénévole et/ou invité permanent, avec listes | Comme aujourd'hui, puis la section « Invités que je gère » en bas, sous les tuiles du profil choisi, séparée par un titre. |
| Listes seulement | Page « Mes invités ». |

Le sélecteur ne propose plus « Mes groupes » quand un profil personnel existe : les listes sont
toujours en bas. « Mon compte » (mot de passe, déconnexion) reste accessible depuis « Modifier »
pour les profils personnels, et par son bouton sur la page « Mes invités ».

### 6.2 Fenêtre d'affichage

```
visibleUntil = début du jour de l'événement (Genève, 0h00) + 24 h + 8 h
```

Le décalage de 8 h est fixe : il ne dépend pas du réglage `date_change_offset_hours`, qui reste à 0
par défaut. Une liste n'est publiée que si `now < visibleUntil`. Remplace `endsAt = date + 3 j` de
la spec invités pour l'affichage, la page et le document QR. La page porte
`expiresAtMillis = max(visibleUntil)` de ses listes. Les appareils republient au passage de chaque
`visibleUntil` (minuterie au lieu d'une fois par jour) pour retirer la liste à l'heure.

### 6.3 Une liste

Carte par liste :

- **Titre** : le nom inscrit sur la liste (`temporaryArtistName` : artiste, institution, amis…),
  sinon le lieu, sinon la date.
- **Sous-titre** : date longue et lieu.
- **Invitations ouvertes** (§8) : bouton « Ajouter des invités », compteur
  « 3 places sur 10 » et, par accès, « Backstage : 1 sur 2 ».
- Puis une carte par invité.

### 6.4 Un invité

```
┌ Bruno MEIER ─────────────────────── [ Modifier ] ┐
│ ┌ Accès ─────────────┐  ┌ Solde ─────────────┐   │
│ │ ven. 20 novembre   │  │ 15.00 CHF          │   │
│ │ Groove · Backstage │  └────────────────────┘   │
│ └────────────────────┘                           │
│ ┌ Contact d'urgence ─┐  ┌────────────────────┐   │
│ │ +41 79 222 33 44   │  │     [ Mon QR ]     │   │
│ └────────────────────┘  └────────────────────┘   │
└──────────────────────────────────────────────────┘
```

- **Accès** : date, lieu et noms des accès de l'invité (nouveau champ `accessNames` dans la page,
  noms seulement, jamais les identifiants bruts).
- **Solde** : seulement si `wallet` est présent (crédits temporaires actifs et solde non nul).
- **Contact d'urgence** : seulement si renseigné.
- **QR** : grand bouton « Mon QR », ou badge « Entrée utilisée ».
- **États de validation** (§8) : badge « En attente de validation » sur un invité ajouté pas encore
  accepté (carte sans QR ni solde), badge « Retrait demandé » sur un invité dont le retrait attend,
  badge « Refusé » sur un ajout refusé, affiché jusqu'à la fin de la fenêtre.
- **Modifier** : absent sur une entrée utilisée et sur un invité en attente.
- Deux modules par ligne sur ordinateur, un par ligne sur mobile.

## 7. Modifier le nom d'un invité

- Le bouton « Modifier » ouvre une fenêtre avec le prénom et le nom (60 caractères chacun, au
  moins un des deux non vide).
- Demande `portalRequests/{uid}_TEMP_GUEST_NAME` :
  `{type, guestKey, firstName, lastName, status: PENDING, createdAt}`.
- Une seule demande en cours par compte ; la fenêtre attend le résultat comme `PROFILE`.
- Traitement par le serveur (`requests.ts`), repli appareil après 2 minutes, comme les autres
  demandes. Le traitement retrouve l'invité temporaire par `guestKey` parmi les invités dont
  `portalUid` est celui de la demande, vérifie que l'entrée n'est pas utilisée, puis écrit `name`
  et `lastName` et republie la page.
- Refus : `NOT_FOUND` (invité absent ou hors du compte), `ENTRY_USED`.
- Pas de validation par l'admin. *Mon choix : seul le nom change et l'entrée n'a pas servi.*
- Session sans e-mail vérifié acceptée seulement si la page du compte a `deviceCreated: true` (§4).

## 8. Invitations depuis la plateforme

### 8.1 Modèle d'invitation

Une invitation ouverte est un **formulaire d'invités** (`GuestForm`) d'un type nouveau, lié à un
compte au lieu d'un lien public. On réutilise ainsi les limites, la réception des réponses, la
liste de validation et le compteur du tableau de bord.

Nouveaux champs sur `GuestForm` (Room 55 → 56, codec Firestore `guestForms`) :

| Champ | Défaut | Rôle |
|---|---|---|
| `portalUid` | `""` | Non vide : modèle d'invitation lié à ce compte. Aucun lien public. |
| `requestKind` | `"ADD"` | Sur une réponse : `ADD` ou `REMOVE`. |
| `removeGuestKeys` | `""` | Sur une réponse `REMOVE` : `guestKey` séparés par des virgules. |

Un modèle d'invitation est un formulaire `allowMultipleResponses = true` avec :

- `eventDateMillis`, `venueName`, `artistName` : ceux de la liste (même `temporaryBatchKey`) ;
- `offeredAccessIds` avec `maxRequests` par accès, et `maxGuests` : les limites de l'événement ;
- `prefillEmail` : l'adresse de contact ; `prefillPhone` : le contact d'urgence de la liste ;
- `expiryMode = MANUAL`, `expiresAtMillis = visibleUntil` (§6.2) ;
- `askEmail = false`, `askPhone = false`.

`maxGuests` et `maxRequests` comptent ici pour **tout l'événement** : invités acceptés de la liste
plus ajouts en attente, moins retraits en attente. Pour un formulaire public, ils restent par
réponse comme aujourd'hui.

### 8.2 Côté admin

- Dans la fenêtre de l'espace (`TemporaryGroupPortalDialog`, renommée « Espace invités »), chaque
  liste à venir de l'adresse a son interrupteur « Peut inviter ». Activé, il déplie les réglages
  de l'événement : nombre maximum de personnes, accès proposés avec leur maximum. Ce sont les
  mêmes contrôles que la création d'un formulaire.
- « Ouvrir les invitations pour un autre événement » : date, lieu, nom de la liste (par défaut le
  nom de la personne, sinon l'adresse) et limites. Crée le modèle sans aucun invité ; la liste
  apparaît dans la plateforme avec zéro invité.
- La même fenêtre s'ouvre depuis la carte « Accès plateforme » d'un bénévole ou d'un invité
  permanent (« Gérer des invités »), pour une personne qui n'a encore aucune liste.
- Couper l'interrupteur ferme le modèle (`EXPIRED`). Les réponses en attente restent à valider.
- Baisser une limite sous le nombre d'invités acceptés ne retire personne ; la plateforme affiche
  alors zéro place.

### 8.3 Côté plateforme

- « Ajouter des invités » : un nom par ligne, accès à cocher par personne parmi ceux proposés.
  Places restantes affichées et vérifiées avant l'envoi. Pas d'adresse ni de téléphone : ceux de
  la liste sont repris.
- « Retirer » : sur la carte d'un invité accepté, entrée non utilisée, avec confirmation.
- Chaque envoi crée une réponse `guestForms/{nouvel id}` : `parentFormId` = modèle,
  `status = PENDING_REVIEW`, `requestKind`, `submissionJson` (ajout) ou `removeGuestKeys`
  (retrait), `portalUid` = uid de la session.
- La page de la liste porte, pour l'affichage : `invite: { formId, maxGuests, remaining,
  accesses: [{id, name, max, remaining}] } | null` et `pending: [{ responseId, kind, names,
  guestKeys, status }]`. L'appareil la calcule et republie à chaque réponse ou décision.

### 8.4 Validation

- Les réponses apparaissent dans la liste de validation des formulaires et dans le compteur du
  tableau de bord, comme celles d'un formulaire public, avec la mention « Depuis la plateforme »
  et le nom de la personne.
- Ajout accepté : `acceptGuestForm` existant. L'adresse et le téléphone du modèle sont repris,
  donc les invités rejoignent la même liste (`temporaryBatchKey` identique) et le même compte.
  L'admin peut corriger la liste avant d'accepter, comme aujourd'hui. Acceptation bloquée si elle
  dépasse une limite de l'événement ; l'admin retire des noms ou relève la limite.
- Retrait accepté : l'appareil supprime les invités visés de la liste si leur entrée n'a pas servi
  (sinon ils restent, et la fenêtre le dit).
- Refus : `rejectGuestForm` existant.

### 8.5 Session non vérifiée

Créer une réponse n'est permis sans e-mail vérifié que si la page temporaire du compte a
`deviceCreated: true` (§4), comme la demande de nom.

## 9. Règles Firestore (v18)

```
function tempPageOf(orgId, uid)      = get(.../temporaryGuestPortal/$(uid))
function tempSessionOk(orgId, uid)   = (isPortalUser() && uid == auth.uid)
                                       || (isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid)
                                       || (isPasswordAccount() && uid == auth.uid
                                           && tempPageOf(orgId, uid).data.deviceCreated == true)

match /orgs/{orgId}/temporaryGuestPortal/{uid}    get: tempGuestPortalOn && tempSessionOk && non échue
match /orgs/{orgId}/temporaryGuestPortalQr/{uid}  get: idem && portalQrOn
```

- `portalRequests` : type `TEMP_GUEST_NAME` (clés `type, guestKey, firstName, lastName, status,
  createdAt`, longueurs bornées), seulement si `tempGuestPortalOn` et `tempSessionOk` pour
  `auth.uid` (ou son uid Google).
- `guestForms` `create` d'une réponse dont le parent a un `portalUid` non vide : exige
  `tempSessionOk(orgId, parent.portalUid)`, parent `OPEN` et non expiré, `portalUid` de la réponse
  égal à celui du parent, `requestKind` dans `ADD`/`REMOVE`, `removeGuestKeys` borné. Une réponse
  publique (sans session) à un parent lié à un compte est refusée.
- `guestForms` `get` d'un modèle lié à un compte : seulement par ce compte (pour lire les limites
  au besoin) ; aucune `list`.
- Limite de 10 accès : le plan compte les `get` de chaque chemin (page, réglages, alias Google,
  parent) et regroupe si besoin.
- `NOCTULIST_RULES_VERSION = 18`, copies synchronisées comme en v17.

## 10. Modèle de données

- Room 55 → 56 :
  - `Guest.temporaryPortalCreated: Boolean = false` (vrai si l'uid vient d'un `signUp` fait pour
    une liste) ;
  - `GuestForm.portalUid`, `GuestForm.requestKind`, `GuestForm.removeGuestKeys`.
- Page `temporaryGuestPortal/{uid}` passe en schema 2 : `deviceCreated`, `visibleUntil` par
  liste, `accessNames` par invité, `invite` et `pending` par liste. Le portail lit encore le
  schema 1 (sans ces blocs) pendant la mise à jour des appareils.
- Projection bénévole et invité permanent : inchangées.

## 11. Textes

Portail (5 langues) et app (7 langues), sans tiret cadratin :

| Avant | Après |
|---|---|
| « Mes groupes » | « Mes invités » |
| « Ouvrir l'espace du groupe » | « Ouvrir l'espace invités » |
| « Couper l'accès à l'espace » (groupe) | « Fermer l'espace invités » |
| « E-mail du groupe » | « Adresse de contact » |
| « Envoyer l'e-mail du groupe » | « Envoyer l'e-mail des invités » |
| autres libellés « groupe »/« manager » visibles | équivalents neutres, liste fixée au plan |

Nouveaux : « Invités que je gère », « Accès », « Contact d'urgence », « Mon QR »,
« Entrée utilisée », « Modifier le nom », « Ajouter des invités », « Retirer »,
« Places : {n} sur {max} », « En attente de validation », « Retrait demandé », « Refusé »,
« Peut inviter », « Ouvrir les invitations pour un autre événement », « Gérer des invités »,
« Depuis la plateforme », « Cette adresse a déjà un compte. La liste apparaît dans son espace. »,
« Cette adresse est déjà liée à un autre compte ».

Les noms internes (`TemporaryGroupPortalDialog`, `groups` dans la page) ne changent pas, seuls
les textes visibles.

## 12. Ce qui change dans la spec invités

- §2 et §6.2 : `endsAt = date + 2 j` (code : + 3 j) remplacé par `visibleUntil` (§6.2).
- §7.3 : « Aucun bouton Modifier, aucune demande » remplacé par §7 et §8.
- §7.5 : le sélecteur ne montre plus les listes à côté des profils personnels (§6.1).
- §16, point sur la réutilisation d'uid : remplacé par §5.3.
- Anti-objectif « un manager qui voit les invités d'un autre groupe » : reformulé au §3.

## 13. Correctif inclus

Fenêtre QR qui se ferme seule pendant environ 3 minutes après le chargement : la relecture qui
suit un CLAIM ne doit fermer la fenêtre QR que si elle montre le profil dont le QR disparaît, et ne
doit pas relancer la lecture QR d'un profil dont la page est encore refusée.

## 14. Tests

- Kotlin :
  - rattachement par adresse (e-mail, Google, conflit, espace coupé, adresse modifiée, rôle
    retiré) ;
  - `deviceCreated` (création, rattachement d'un bénévole qui le fait passer à faux) ;
  - `visibleUntil` (1h00 et 7h59 le lendemain visibles, 8h00 non) ;
  - page schema 2 (`accessNames`, `invite`, `pending`, aucun identifiant d'accès ni `nanoId`) ;
  - `TEMP_GUEST_NAME` (succès, `NOT_FOUND`, `ENTRY_USED`, autre compte) ;
  - comptage des places (acceptés, en attente, retraits) ;
  - acceptation bloquée au-delà des limites ; retrait accepté, entrée utilisée conservée ;
  - migration 55 → 56.
- Règles : lecture temporaire non vérifiée (`deviceCreated` vrai oui, faux non), session Google ;
  `TEMP_GUEST_NAME` ; réponse liée à un compte (bon compte oui, autre compte, sans session, parent
  fermé ou expiré, `portalUid` différent : non) ; formulaire public inchangé.
- Functions : `TEMP_GUEST_NAME`.
- Navigateur (seed étendu) : bénévole avec une liste en bas ; personne avec listes seulement ;
  liste avec invitations ouvertes, ajout, retrait, badges ; modification du nom ; liste passée
  absente ; fenêtre QR stable après connexion ; clair/sombre, mobile/ordinateur.

## 15. Laissé au plan

- Découpage et ordre (proposé : rattachement et fenêtre d'affichage, présentation et textes, nom,
  invitations, règles et Functions, correctif QR).
- Liste exacte des chaînes.
- Comptage des accès des règles et regroupement éventuel.
- Place exacte du compteur et des badges dans l'écran de validation des formulaires.
