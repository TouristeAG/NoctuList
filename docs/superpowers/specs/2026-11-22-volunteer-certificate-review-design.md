# Attestation de bénévolat : validation par un admin

Avenant à `2026-11-22-volunteer-certificate-design.md` (même branche
`feature/volunteer-certificate`). Il remplace le §8 « Serveur » de cette spec et une partie du §4.

## 1. Intention

Un document signé au nom de l'association ne doit jamais partir sans qu'un admin l'ait relu. La
demande du bénévole n'envoie donc plus rien d'elle-même : l'app prévient l'admin, qui relit
l'attestation, peut corriger son texte, puis la valide (le serveur l'envoie) ou la refuse.

## 2. Objectifs, non-objectifs, anti-objectifs

Objectifs
- Notification dans l'app à l'arrivée d'une demande, comme les guestlists.
- Carte « Attestations à valider » en haut de la liste Bénévoles (pas dans la guestlist).
- Fenêtre de relecture : la demande (bénévole, période, types cochés), le texte de cette
  attestation dans l'éditeur riche, l'aperçu A4, « Refuser » (motif facultatif) et « Valider et
  envoyer ».
- À la validation, le serveur met le texte validé en PDF et l'envoie au bénévole.
- Le bénévole voit l'état dans son espace : en attente de validation, envoyée, refusée (+ motif).

Non-objectifs
- Les corrections ne modifient pas le modèle : elles ne valent que pour cette attestation.
- Pas de modification de la période ni des types par l'admin (il corrige le texte).
- Aucun e-mail au bénévole en cas de refus (règle du projet : aucun e-mail automatique hors envoi
  demandé ; le refus se lit dans le portail).
- Pas de notification système (OS) : un toast dans l'app, comme les guestlists.

Anti-objectifs
- Une attestation envoyée sans validation d'un admin, par n'importe quel chemin.
- Un texte envoyé différent de celui que l'admin a validé.
- Deux envois pour une même demande (deux admins qui valident en même temps).
- `volunteer.id` qui sort des appareils et du serveur.

## 3. Approches (choix du propriétaire : A)

- **A. L'app prépare, le serveur envoie (choisie).** La demande passe en attente de validation ;
  l'app assemble le texte avec ses données (l'assembleur Kotlin qui sert déjà à l'aperçu),
  l'admin le relit et le corrige, l'app transmet le texte final, le serveur rend le PDF et
  l'envoie. L'assembleur TypeScript et ses cas partagés deviennent inutiles et sont retirés.
- B. Le serveur prépare un brouillon stocké dans Firestore, l'app l'affiche, le serveur envoie :
  deux assembleurs à garder identiques et des données personnelles stockées plus longtemps.
- C. L'app fait tout, PDF et envoi compris : deux moteurs PDF (Android, desktop), déjà écarté.

## 4. Déroulement

1. Le bénévole envoie sa demande depuis le portail (inchangé, §4 de la spec d'origine).
2. Le serveur la vérifie comme aujourd'hui (option active, modèle publié, propriétaire, adresse,
   au moins un job dans la sélection). Refus → `REJECTED` + raison, comme aujourd'hui. Sinon il
   met la demande à `status: "PENDING_REVIEW"` (même valeur que les invitations à valider) et
   **n'envoie rien**.
3. Les appareils écoutent déjà `portalRequests`. Toute demande `CERTIFICATE_EMAIL` en
   `PENDING_REVIEW` apparaît :
   - en toast global à son arrivée (« {prénom nom} demande une attestation »), sur le modèle de
     `GuestFormArrivalToastHost` (même emplacement, mêmes conditions d'affichage) ;
   - dans une carte « Attestations à valider » en haut de la liste Bénévoles (Android et
     desktop, toutes les mises en page où `GuestFormPendingCard` existe pour la guestlist), sur
     le modèle de `GuestFormPendingCard` : une ligne par demande, plus ancienne en premier.
4. Fenêtre de relecture (modèle `GuestFormReviewDialog`) :
   - en-tête : bénévole, adresse d'envoi, date de la demande, période et types demandés ;
   - l'attestation de ce bénévole, assemblée par `CertificateAssembler` avec le modèle enregistré
     et tous ses jobs, ouverte dans l'éditeur riche (même barre d'outils, sans variables ni
     accords : le texte est déjà résolu) et l'aperçu A4 (`CertificatePreview`) ;
   - « Refuser » : petite fenêtre avec un motif facultatif (≤ 300 caractères), puis la demande
     passe à `REJECTED`, raison `REFUSED`, champ `note` = motif ;
   - « Valider et envoyer » : l'app écrit l'attestation validée (§5) ; la carte affiche « Envoi en
     cours » jusqu'au `DONE` du serveur ; en cas d'échec d'envoi, la demande revient en
     `PENDING_REVIEW` avec un message et peut être validée à nouveau.
   - « Fermer » sans rien faire : la demande reste à valider ; les corrections non validées sont
     perdues (avec confirmation si le texte a changé).
5. Le serveur reçoit l'attestation validée, vérifie que la demande est toujours en
   `PENDING_REVIEW` (réclamation transactionnelle : un seul envoi), rend le PDF, l'envoie avec
   l'expéditeur et la réponse du modèle publié, puis met la demande à `DONE` et supprime
   l'attestation validée.

## 5. Données

- Demande : `portalRequests/{uid}_CERTIFICATE_EMAIL`, nouveaux états `PENDING_REVIEW` (écrit par
  le serveur) ; refus écrit par un appareil : `status: "REJECTED"`, `reason: "REFUSED"`,
  `note`, `processedAt`. Le bénévole ne peut pas réécrire sa demande tant qu'elle est en
  `PENDING_REVIEW` (ni en `APPROVED`).
- Attestation validée : `orgs/{orgId}/certificateApprovals/{requestId}` écrite par un membre :
  `{ requestId, body: [ResolvedParagraph], footer: [ResolvedParagraph], title: string,
  approvedBy: string (adresse de l'admin connecté ou nom de l'appareil), approvedAt }`. Lisible
  et modifiable par les membres seulement, jamais par le portail. La demande passe à
  `status: "APPROVED"` dans la même écriture (batch) ; c'est ce qui empêche une deuxième
  validation depuis un autre appareil.
- Le serveur déclenche sur la création de `certificateApprovals/{id}` ; signature, logo,
  expéditeur et réponse viennent de `portalMail/certificate` au moment de l'envoi.
- Taille : body + footer ≤ 200 000 caractères en JSON, sinon l'app refuse d'enregistrer.

## 6. Portail

- La demande en `PENDING_REVIEW` ou `APPROVED` : « Ta demande attend la validation de l'équipe. »
  (pas de bouton d'envoi).
- `DONE` : « Ton attestation a été envoyée à {email}. »
- `REJECTED` + `REFUSED` : « Ta demande a été refusée. » suivi du motif s'il y en a un ; le
  bénévole peut refaire une demande après le délai habituel (10 minutes).
- Autres raisons : inchangées.

## 7. Ce qui disparaît

- L'assemblage côté serveur (`certificate.ts` : `assemble`, défauts, dates, catégories) n'est plus
  utilisé ; il est retiré avec `certificate-cases.json` et `certificate-default-fr.json`. Le
  serveur garde la validation, `parseTemplate` s'il sert encore à lire l'expéditeur et la
  signature, le rendu PDF et l'envoi. Les cas d'assemblage restent en Kotlin
  (`SharedCertificateCasesTest` devient un test Kotlin ordinaire, fichier déplacé dans les
  ressources de test Kotlin).

## 8. Règles (v21)

- `portalRequests` : un compte du portail ne peut pas réécrire une demande `CERTIFICATE_EMAIL`
  en `PENDING_REVIEW` ou `APPROVED`. Les membres peuvent mettre une demande `PENDING_REVIEW` à
  `APPROVED` ou à `REJECTED` (+ `reason: "REFUSED"`, `note` ≤ 300 caractères).
- `certificateApprovals/{id}` : création par un membre seulement si la demande liée est en
  `PENDING_REVIEW` dans la même écriture ou déjà `APPROVED` par cette écriture (batch) ; lecture
  et suppression par les membres ; jamais par le portail.

## 9. Laissé au constructeur

- Mise en page exacte de la carte et de la fenêtre de relecture, tant qu'elles suivent les
  composants guestlist existants.
- Source exacte de `approvedBy` (compte Google de l'appareil, nom de l'appareil).
- Comment rafraîchir la liste (écoute existante de `portalRequests` dans le service, exposée au
  ViewModel).

## 10. Tests

- Functions : une demande valide passe en `PENDING_REVIEW` sans aucun mail ; une attestation
  validée produit un seul mail avec le PDF du texte validé (pas du modèle) et met la demande à
  `DONE` ; deux validations concurrentes = un seul mail ; échec d'envoi → retour en
  `PENDING_REVIEW` ; attestation validée pour une demande pas en `APPROVED` → ignorée.
- Règles : le portail ne peut ni lire `certificateApprovals` ni réécrire une demande en
  attente ; un membre peut approuver ou refuser ; refus avec note trop longue rejeté.
- App : liste des demandes à valider depuis `portalRequests` ; approbation = batch demande +
  attestation ; refus ; le texte édité est celui écrit.
- Portail : les trois nouveaux états et le motif.
- Manuel : toast, carte, relecture, correction, envoi, refus, sur desktop et Android.

## 11. Précisions après relecture (choix de Claude)

- **Identifiant de l'attestation validée** : identifiant aléatoire, avec `requestId` dans le
  document (pas l'identifiant de la demande, qui est toujours le même pour un bénévole). Le
  serveur supprime l'attestation validée après l'envoi **et** après un échec ; une nouvelle
  validation crée un nouveau document.
- **Réclamation par le serveur** : à la création d'une attestation validée, une transaction passe
  la demande de `APPROVED` à `PROCESSING` (avec `processingAt`) ; si elle n'est pas en `APPROVED`,
  l'attestation est supprimée sans envoi. Fin : `DONE` ; échec d'envoi : retour à
  `PENDING_REVIEW` avec `reason: "SEND_FAILED"`.
- **Envoi bloqué** : une demande restée en `APPROVED` ou `PROCESSING` plus de 5 minutes apparaît
  dans la carte comme « Envoi bloqué » avec « Réessayer », qui la remet en `PENDING_REVIEW`
  (l'admin valide à nouveau). Les appareils ne réclament toujours jamais la demande.
- **Modèle utilisé** : l'app assemble avec le modèle enregistré sur l'appareil. L'attestation
  validée porte tout ce qui vient du texte : `body`, `footer`, `title`, `signer` (nom, fonction,
  image). Le serveur prend dans `portalMail/certificate` seulement le logo et les réglages
  d'e-mail (expéditeur, réponse, objet, corps).
- **Destinataire** : le serveur relit la fiche du bénévole au moment de l'envoi (`isOwner`,
  adresse via `safeRecipient`) et construit le nom du fichier à partir de cette fiche ; il refuse
  (`NO_ACCESS`, `NO_EMAIL`) comme aujourd'hui. `volunteer.id` n'est jamais écrit dans
  l'attestation validée.
- **Demande en attente** : le bénévole ne peut ni la réécrire ni l'annuler tant qu'elle attend ;
  c'est à l'équipe d'agir (la carte reste visible).
- **Demande sans fiche** : si aucun bénévole n'a ce `portalUid`, la ligne affiche « Fiche
  introuvable » et seul « Refuser » est proposé.
- **Écrans** : la carte va partout où la liste Bénévoles s'affiche (vérifier chaque mise en page
  de `VolunteerScreen` sur Android et desktop). Le toast est un second hôte monté à côté de
  `GuestFormArrivalToastHost`, ou une généralisation de celui-ci (au choix du constructeur).
- **Lecteur Kotlin des demandes** (`PortalDocuments.kt`) : il accepte les champs `note`,
  `reason`, `processedAt` et les nouveaux états ; les filtres « les appareils ne traitent pas
  CERTIFICATE_EMAIL » restent.
- **Limites revérifiées par le serveur** : body + footer ≤ 200 000 caractères, image de
  signature ≤ 200 000 caractères, sinon `INVALID` et retour à `PENDING_REVIEW` ; note ≤ 300
  caractères vérifiée par les règles.
