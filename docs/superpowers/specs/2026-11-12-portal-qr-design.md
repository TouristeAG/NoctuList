# QR du bénévole dans l'espace bénévole : spec de conception

Date : 2026-11-12 · Branche : `feature/volunteer-portal` · Statut : design validé en discussion, spec à relire.
Complète `2026-11-10-volunteer-portal-design.md` et `2026-11-10-portal-server-design.md`.

## 1. Objectif

Sans compte Apple Developer, le `.pkpass` n'est pas signé et iOS le refuse. Au lieu d'Apple Wallet,
le bénévole affiche son QR depuis l'espace bénévole installé sur son téléphone (PWA), y compris
sans réseau.

Réussite : un bénévole non admin ouvre l'app depuis l'écran d'accueil en mode avion, touche
« Mon QR », et le QR est accepté par le scanner de NoctuList.

## 2. Décisions validées

| Sujet | Décision |
|---|---|
| Qui | Bénévoles non admin seulement. Pour un admin, `volunteer.id` est aussi la clé admin : jamais affiché, comme pour le QR par e-mail. |
| Activation | Réglage par association, **activé par défaut** quand le portail est actif. |
| Transport | Document séparé `volunteerPortalQr/{uid}`, jamais un champ de la page `volunteerPortal/{uid}`. |
| Contenu du QR | Exactement celui de l'e-mail : `volunteer.id`. Rien à changer côté scanners. |
| Affichage | Bouton « Mon QR » dans le bloc « Mon QR code » existant, sous son titre, qui ouvre une vue plein écran. « M'envoyer mon QR code » devient un petit lien en dessous. Pas de QR sur le tableau de bord. |
| Hors ligne | QR gardé sur l'appareil, effacé à la déconnexion ou quand le serveur refuse l'accès. Pas d'expiration par durée. |
| QR par e-mail | Reste disponible, inchangé. |
| Textes | Aucun texte d'aide ou d'explication ajouté (ni conseil iOS, ni message hors ligne). Seuls les libellés nécessaires. |

Invariant mis à jour : `volunteer.id` ne quitte les appareils que vers son propriétaire non admin,
par e-mail ou par `volunteerPortalQr/{uid}`. Il n'apparaît jamais dans `volunteerPortal/{uid}`,
dans l'export JSON, ni dans un document lisible par quelqu'un d'autre.

## 3. Données et règles

### Réglage

- Clé `institutionSettings/volunteer_portal_qr_enabled`, `value` booléen. Absente = activé.
- App : coche « Afficher le QR dans l'espace bénévole » dans la carte « Plateforme bénévole »
  (Paramètres → Firebase), visible quand le portail est actif.

### Document `orgs/{orgId}/volunteerPortalQr/{uid}`

```
{ schema: 1, payload: <volunteer.id>, name: <prénom nom> }
```

- Existe seulement si : portail actif, réglage actif, bénévole non admin, page `volunteerPortal/{uid}`
  publiée. Sinon il est supprimé.
- Écrit et supprimé par les appareils dans `VolunteerPortalService`, au même moment que la page,
  avec le même mécanisme de hash publié (`loadPublishedHashes`) pour ne pas réécrire un document
  inchangé. Couper le réglage déclenche un `republish()` qui supprime les documents existants.
- Écrit par le serveur (Cloud Functions) à l'inscription, seul endroit où il crée une page (un CLAIM ne fait que lier la fiche, la page vient ensuite des appareils).
- Une fonction pure partagée côté Kotlin (`PortalQrDocument.build(volunteer, settings): Map?`)
  décide de l'existence et du contenu. Le serveur a son équivalent TypeScript.

### Règles Firestore (v16)

```
function portalQrOn(orgId) {
  return !exists(.../institutionSettings/volunteer_portal_qr_enabled)
    || get(.../institutionSettings/volunteer_portal_qr_enabled).data.get('value', true) in [true, 'true'];
}

match /orgs/{orgId}/volunteerPortalQr/{uid} {
  allow get: if portalOn(orgId) && portalQrOn(orgId)
    && ((isPortalUser() && request.auth.uid == uid)
        || (isPortalGoogleUser(orgId) && portalUidOf(orgId) == uid));
}
```

- Pas de `list`, aucune écriture depuis le web. Les membres de l'organisation passent par la règle
  générale `orgs/{orgId}/{document=**}` comme pour le reste.
- Couper le réglage bloque la lecture tout de suite, avant même la suppression des documents.
- `NOCTULIST_RULES_VERSION` passe à 16. Les deux copies des règles (`composeResources/files` et
  `firebase/tests`) sont synchronisées.

## 4. Page web

### Bouton et vue plein écran

- `portal.js` lit `volunteerPortalQr/{uid}` après la page. S'il existe, un bouton « Mon QR »
  apparaît dans le bloc « Mon QR code » existant, sous son titre. « M'envoyer mon QR code »
  devient un petit lien en dessous (demande de l'utilisateur).
- Vue plein écran : fond blanc, QR le plus grand possible avec une marge blanche, prénom et nom,
  nom de l'association, bouton Fermer. Fermeture aussi par Échap et par le bouton retour.
- Rendu par `qrcode-generator` (MIT), copié dans `webform/vendor/qrcode.js`, en SVG, niveau de
  correction M (comme l'e-mail). Aucun script externe ajouté.
- Pendant l'affichage : `navigator.wakeLock.request('screen')` si disponible, relâché à la fermeture.
  Échec silencieux.

### Hors ligne

- Service worker `webform/portal-sw.js`, enregistré par `portal.js` avec `scope: '/v/'`.
  - Précache : `portal.html`, `portal.css`, `portal.js`, `portal-common.js`, `portal-i18n.js`,
    `theme.*`, `topo.js`, `firebase-env.js`, `vendor/qrcode.js`, polices, icônes, manifest et les
    modules Firebase `https://www.gstatic.com/firebasejs/10.14.1/*` utilisés.
  - Navigation sous `/v/` : réseau d'abord, `portal.html` en cache si le réseau échoue.
  - Ressources statiques : cache d'abord, nom de cache versionné, anciens caches supprimés à l'activation.
  - Aucune requête Firestore, Auth ou Storage n'est mise en cache.
- `firebase.json` : CSP `worker-src 'none'` devient `worker-src 'self'`.
- `localStorage` clé `noctulist.portalQr.<orgId>` = `{ uid, payload, name, association }`, écrite
  après chaque lecture réussie du document QR.
- Démarrage sans réseau (`navigator.onLine === false`, ou chargement de la page en échec réseau)
  avec un QR enregistré : écran réduit avec le seul bouton « Mon QR ». Sans QR enregistré :
  comportement actuel.

### Effacement du QR enregistré

- Les deux appels `signOut` de `portal.js`.
- Lecture du document QR en ligne qui renvoie « inexistant » ou `permission-denied`.
- Session ouverte avec un autre `uid` que celui enregistré.

## 5. Textes

Nouveaux libellés uniquement, dans toutes les langues de l'app (7) et du portail (5), sans tiret cadratin :

- App : « Afficher le QR dans l'espace bénévole ».
- Portail : « Mon QR », « Fermer ».

## 6. Tests et vérification

Kotlin (`commonTest`) :
- `PortalQrDocumentTest` : document produit pour un non admin avec réglage actif ou absent ; aucun
  document pour un admin, réglage coupé, portail éteint.
- `PortalProjectionBuilderTest` : la page ne contient toujours pas `volunteer.id`.
- `VolunteerPortalServiceTest` : document écrit avec la page, supprimé au passage admin et à la
  coupure du réglage, pas réécrit si le hash est identique.

Règles (`firebase/tests/rules/portal-qr.test.mjs`) :
- Lecture autorisée au propriétaire (mot de passe et Google lié).
- Refusée à un autre bénévole, réglage coupé, portail éteint, utilisateur non connecté.
- `list` et toute écriture refusés depuis le web.

Cloud Functions (`npm run test:functions`) :
- L'inscription écrit le document QR quand le réglage est actif ou absent, pas quand il est coupé.

Navigateur (`npm run preview` + `node seed.mjs`) :
- « Mon QR » s'ouvre, le QR est lu par le scanner de l'app desktop.
- DevTools hors ligne puis rechargement : l'écran réduit affiche le QR.
- Déconnexion : la clé `localStorage` a disparu.
- Réglage coupé dans l'app, rechargement en ligne : bouton absent et clé effacée.

Doc : entrée CHANGELOG, FIREBASE_SETUP.md (règles v16, CSP `worker-src`).

## 7. Hors périmètre

- Apple Wallet et Google Wallet : inchangés.
- Luminosité de l'écran : impossible depuis une page web.
- Rotation ou expiration du contenu du QR.
