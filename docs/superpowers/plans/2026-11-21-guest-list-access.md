# Accès complet à la guestlist : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** L'admin donne à un profil un accès daté (lieu optionnel) à toute la guestlist temporaire ; la personne la gère depuis sa plateforme, appliqué directement par un appareil.

**Architecture:** Accès stockés en JSON sur le profil (`guestListGrantsJson`). Les appareils publient `guestListPortal/{uid}` (pur : `GuestListPortalProjectionBuilder`) et traitent `guestListEdits/{id}` (`GuestListEditProcessor`, branché dans `VolunteerPortalService`). Règles v19. UI app : fenêtre sur la carte d'accès. Portail : section par accès.

**Tech Stack:** Kotlin Multiplatform (Room, GitLive Firestore, Compose), règles Firestore, JS vanilla, `node:test`.

**Spec:** `docs/superpowers/specs/2026-11-21-guest-list-access-design.md`

## Global Constraints

- Branche `feature/guest-portal`, `/usr/bin/git`, commits sans `Co-Authored-By` ni mention de Claude.
- Gradle avec `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` ; ne jamais commiter `*.dylib` ; `FirebaseOrgBootstrapProbeTest.deniedWithoutInvitationCode...` rouge sur `main`.
- Room 56 → 57. Règles `NOCTULIST_RULES_VERSION = 19`, trois copies identiques (le lien `firebase/tests/firestore.rules` suit).
- Fin d'un accès : `TemporaryGuestPortalProjectionBuilder.visibleUntil(eventDateMillis)`.
- `guestKey = TemporaryGuestPortalQrDocument.guestKey(guest)` ; jamais de `nanoId` dans une page.
- Chaînes Compose sans apostrophe échappée ; 7 langues app, 5 portail ; pas de tiret cadratin.
- Copies `composeResources/files/webform/*` identiques à `webform/*` avant commit.

## Review Focus

1. **Accès retiré pendant qu'une demande attend** : la demande est refusée `NO_GRANT` (Task 3, `revokedGrantRejects`).
2. **Accès limité à un lieu** : ni lecture ni modification d'un invité d'un autre lieu de la même date (Task 2 `venueGrantShowsOnlyThatVenue`, Task 3 `otherVenueNotFound`).
3. **Deux profils du même compte avec des accès à la même date** : une seule section par (date, lieu), pas de doublon (Task 2 `sameDateTwiceMerges`).
4. **Session non vérifiée d'un contact de liste** : ne lit pas la guestlist (Task 4).
5. **Ajout dans une nouvelle liste quand l'accès couvre tous les lieux** : le lieu demandé doit être actif, sinon `INVALID` (Task 3 `addNeedsAnActiveVenue`).

---

### Task 1: Accès accordés sur le profil (Room 57, synchro)

**Files:** `data/models/Models.kt`, nouveau `portal/GuestListGrants.kt`, `EventManagerDatabase.kt`, `GitLiveFirestoreGateway.kt`, `FirestoreChangeApplier.kt`. Tests : `portal/GuestListGrantsTest.kt`, `FirestoreSyncIntegrityTest.kt`.

**Interfaces (Produces):**
- `@Serializable data class GuestListGrant(val id: String, val eventDateMillis: Long, val venueName: String = "", val grantedBy: String = "", val grantedAt: Long = 0, val revokedAt: Long = 0, val revokedBy: String = "")` avec `fun isActive(now: Long): Boolean`.
- `object GuestListGrants { fun decode(raw: String): List<GuestListGrant>; fun encode(list): String; fun add(raw, grant): String; fun revoke(raw, id, by, now): String; fun active(raw, now): List<GuestListGrant>; fun history(raw, now): List<GuestListGrant> }` (historique trié du plus récent au plus ancien).
- `Volunteer.guestListGrantsJson`, `Guest.guestListGrantsJson` (défaut `""`), synchronisés ; l'applier garde la valeur locale si le champ manque.

- [ ] Tests : `activeUntilEightNextDay`, `revokedGoesToHistory`, `decodeIgnoresGarbage`, aller-retour codec Firestore.
- [ ] Rouge, implémentation, vert. Commit `Guest list grants on profiles (Room 57)`.

### Task 2: Page `guestListPortal/{uid}`

**Files:** nouveau `portal/GuestListPortalProjection.kt`, `GuestPortalPublisher.kt` (publication et suppression, clé de hash `#guestlist`), test `GuestListPortalProjectionBuilderTest.kt`.

**Interfaces (Produces):** `GuestListPortalProjectionBuilder.build(grants: List<GuestListGrant>, guests: List<Guest>, accesses: List<VenueAccess>, associationName: String, now: Long): PortalProjection?` (spec §5.2 ; `extraFields = expiresAtMillis, grantDates`). Doublons (même date et lieu) fusionnés ; accès « tous les lieux » couvre les accès limités de la même date. Le publisher regroupe les accès en cours par `portalUid` des profils (bénévoles et invités, organisation courante, espace temporaire actif).

- [ ] Tests : `onlyTemporaryGuestsOfTheDate`, `venueGrantShowsOnlyThatVenue`, `noNanoIdNoQr`, `sameDateTwiceMerges`, `listsAndAccessesOffered`, `nullWithoutActiveGrant`, publisher `guestListPageFollowsGrants` (publiée puis supprimée au retrait).
- [ ] Rouge, implémentation, vert. Commit `Publish the guest list page for granted accounts`.

### Task 3: Traitement des modifications

**Files:** nouveau `portal/GuestListEditProcessor.kt`, `VolunteerPortalService.kt` (écoute `guestListEdits`, réclamation, nettoyage à 7 jours), `VolunteerPortalHost` (`suspend fun addTemporaryGuests(batch: ManualTemporaryGuestBatch): Boolean = false`, `suspend fun deleteTemporaryGuest(guest: Guest): Boolean = false`, `fun activeVenueNames(): Set<String> = emptySet()`), ViewModel (implémentations), `PortalServiceFakes.kt`. Test `GuestListEditProcessorTest.kt`.

**Interfaces (Produces):** `data class GuestListEdit(...)` (spec §5.3) avec `parse(id, map)` ; `class GuestListEditProcessor(host, clock) { suspend fun apply(edit: GuestListEdit, volunteers, guests): Pair<String, String> /* status, reason */ }`.

- [ ] Tests : `addToExistingListCopiesContact`, `addToNewList`, `addNeedsAnActiveVenue`, `updateNameAndAccesses`, `deleteUnused`, `entryUsedRefused`, `revokedGrantRejects`, `otherDateRejects`, `otherVenueNotFound`, `accessIdsFilteredToVenue`.
- [ ] Rouge, implémentation, vert. Commit `Apply guest list edits from the portal on devices`.

### Task 4: Règles v19

**Files:** `firebase/firestore.rules` (+ copie), `FirestorePermissionErrors.kt` (19), nouveau `firebase/tests/rules/guest-list.test.mjs`.

- [ ] Tests spec §8 (règles), puis règles, `npm run test:rules` vert, `FirestoreCodecAndRulesShapeTest` vert. Commit `Rules v19: guest list access`.

### Task 5: Fenêtre des accès dans l'app

**Files:** nouveau `ui/components/GuestListGrantsUi.kt`, `VolunteerPortalUi.kt` (bouton), ViewModel (`grantGuestListAccess(holder: PortalHolder, eventDateMillis: Long, venueName: String)`, `revokeGuestListAccess(holder, grantId)`, `guestListGrantsOf(holder): String`), chaînes ×7.

- [ ] Compilation desktop + Android, `StringsEscapeTest`. Commit `Manage guest list access from the access card`.

### Task 6: Portail

**Files:** `portal.js`, `portal-common.js` (`guestListGroups(guests, query)`, `guestListCounts(guests)`), `portal.css`, `portal-i18n.js`, `portal-sw.js` + versions, `firebase/tests/seed.mjs`, test `site-unit/portal-guest-list.test.mjs`.

- [ ] Tests purs, implémentation (lecture `guestListPortal` pour sessions vérifiées/Google, section par accès, ajouter/modifier/supprimer via `guestListEdits`, suivi du statut), navigateur sur émulateurs. Commit `Portal: full guest list for granted accounts`.

### Task 7: Docs et vérification

- [ ] `CHANGELOG.md`, `FIREBASE_SETUP.md` (v19) ; suites complètes ; relecture finale.
