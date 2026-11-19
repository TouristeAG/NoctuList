# Assistant d'installation Firebase : vérification manuelle

À faire sur un Mac et sur un Windows propres, avec l'application installée (pas lancée depuis Gradle). Les journaux sont dans `~/.noctulist/logs/setup/` sur macOS et `%USERPROFILE%\.noctulist\logs\setup\` sur Windows. Les rapports de plantage sont dans `~/Library/Logs/NoctuList/` (macOS) et `%APPDATA%\NoctuList\logs\` (Windows). Chaque erreur nouvelle trouvée pendant la vérification doit être ajoutée au catalogue d'erreurs avant de clore.

## Lancement

- [ ] Lancer l'application installée depuis le Finder (macOS) → la fenêtre s'ouvre, pas de blocage sur "synchro des données"
- [ ] Lancer l'application installée depuis le menu Démarrer (Windows) → la fenêtre s'ouvre, pas de blocage sur "synchro des données"
- [ ] Lancer l'application une deuxième fois pendant qu'elle tourne → la première fenêtre passe au premier plan et la deuxième instance se ferme sans rien afficher
- [ ] Ouvrir la page de l'assistant d'installation automatique → aucune erreur de dossier non modifiable (`firebase-debug.log`)

## Outils

- [ ] Ouvrir l'étape 0 sur une machine sans outils → l'étape indique "à réinstaller", jamais "installé"
- [ ] Installer les outils sans proxy → Node et Firebase CLI s'installent, l'étape passe à "installé" avec les bonnes versions
- [ ] Installer les outils derrière un proxy → le téléchargement et npm passent par le proxy et l'installation réussit
- [ ] Installer les outils sans connexion internet → une erreur explique que le téléchargement est bloqué et parle du proxy ou de la connexion
- [ ] Supprimer le dossier des outils dans `<appData>/setup`, puis rouvrir la page → l'étape 0 affiche "à réinstaller"
- [ ] Cliquer sur "Réinstaller" quand les outils sont déjà installés → les outils sont réinstallés et l'étape repasse à "installé"
- [ ] Cliquer sur "Réinstaller" pendant qu'un déploiement tourne → le déploiement est arrêté d'abord, puis la réinstallation démarre
- [ ] Pendant une réinstallation, laisser la page se rafraîchir → le rafraîchissement ne touche pas aux étapes en cours

## Connexion

- [ ] Se connecter avec le code collé (étape 1) → le compte s'affiche et l'étape est cochée
- [ ] Coller un code faux ou expiré → l'erreur propose de recommencer la connexion
- [ ] Cliquer sur "Se déconnecter" → le compte disparaît et les coches qui en dépendent sont retirées
- [ ] Cliquer sur "Changer de compte" → déconnexion puis nouvelle connexion avec un autre compte, les coches de l'ancien compte ne restent pas
- [ ] Se déconnecter puis changer de compte avec un autre compte Google → le nouveau compte s'affiche et les projets listés sont les siens
- [ ] Révoquer l'accès de l'application dans le compte Google, puis rouvrir la page → l'étape affiche "Reconnectez-vous" avec le bouton de connexion

## Projet et base de données

- [ ] Créer un projet sur un domaine Workspace (étape 2) → le projet est créé et l'étape est cochée
- [ ] Demander un identifiant de projet déjà pris → l'application propose un identifiant libre
- [ ] Utiliser un compte Workspace sans le rôle de création de projet → l'erreur explique ce que l'administrateur Workspace doit accorder
- [ ] Lancer l'étape 3 sur un projet neuf → les API nécessaires sont activées par l'application et la ligne "Réparé" les mentionne
- [ ] Créer la base de données (étape 3) → la base Firestore est créée et l'étape est cochée
- [ ] Choisir une région non autorisée ou une base déjà configurée → l'erreur explique la situation sans modifier le projet

## Règles, site et photos

- [ ] Déployer les règles (étape 4) → l'étape est cochée
- [ ] Créer le site d'hébergement et le déployer (étape 7) → le site existe et a une version en ligne
- [ ] Lancer l'étape 7 sur un projet sans site d'hébergement → l'application crée le site
- [ ] Configurer les photos (étape 8) sur un projet sans Blaze → l'application explique que Blaze est nécessaire pour le stockage et propose l'offre de facturation
- [ ] Configurer les photos sur un projet avec Blaze → le stockage et ses règles sont créés et l'étape est cochée

## Serveur et facturation

- [ ] Avec un seul compte de facturation, arriver à une étape qui demande Blaze → l'offre "Activer Blaze avec <nom du compte>" apparaît avec une explication et un lien vers les budgets
- [ ] Ne pas cliquer sur l'offre de facturation, regarder le compte de facturation dans la console → rien n'est lié tant qu'on n'a pas cliqué
- [ ] Cliquer sur l'offre puis confirmer → le compte de facturation est lié et le texte de confirmation montrait ce qui allait se passer
- [ ] Avec zéro compte de facturation, arriver à la même étape → pas d'offre, des étapes numérotées et un bouton vers la console
- [ ] Avec plusieurs comptes de facturation, arriver à la même étape → pas d'offre automatique, des étapes numérotées et un bouton vers la console
- [ ] Déployer le serveur (étape 9) sur un projet neuf → le rôle de compilation (Cloud Build) est ajouté si besoin et la ligne "Réparé : rôle Cloud Build ajouté au compte …" s'affiche
- [ ] Déployer sur un projet dont le serveur a déjà été déployé dans un essai précédent et est resté en échec (FAILED) → l'application supprime les anciennes fonctions, affiche "Réparé : anciennes fonctions en échec supprimées" et le déploiement réussit sans intervention
- [ ] Après le déploiement, regarder les fonctions dans la console → toutes les fonctions NoctuList sont actives et aucune autre fonction du projet n'a été supprimée

## Mail : mot de passe d'application

- [ ] Saisir une adresse `@gmail.com` à l'étape 9 → le mode mot de passe d'application est proposé, sans offre Workspace
- [ ] Saisir lettre par lettre "x@gmail.com" → la suggestion de mode ne change pas pendant la frappe
- [ ] Saisir un mot de passe d'application valide et envoyer le mail de test → le mail arrive chez l'administrateur connecté et l'étape est cochée
- [ ] Saisir un mauvais mot de passe d'application → l'erreur dit que Gmail a refusé le mot de passe, sans l'afficher
- [ ] Après cette erreur, regarder le bouton "Réessayer" → il est désactivé tant que le mot de passe d'application n'a pas été saisi de nouveau
- [ ] Saisir de nouveau le mot de passe, puis "Réessayer" → l'étape se relance avec le mot de passe saisi
- [ ] Vérifier le secret du projet dans la console → il contient le mot de passe d'application et rien dans les journaux n'en garde la valeur

## Mail : Workspace

- [ ] Saisir une adresse sur un domaine Workspace à l'étape 9 → le mode Workspace est proposé comme recommandé, avec le mot de passe d'application en alternative
- [ ] Choisir le mode Workspace → les champs "Compte qui envoie" et "Adresse affichée" apparaissent, la seconde reprend la première par défaut
- [ ] Lancer l'étape → le compte de service `noctulist-mail` est créé sans clé et sans rôle, et le secret `GMAIL_APP_PASSWORD` contient `unused`
- [ ] Regarder les indications de délégation → l'identifiant client numérique et le champ d'accès `https://www.googleapis.com/auth/gmail.send` ont chacun un bouton "Copier" et un bouton vers la page de la console d'administration
- [ ] Envoyer le mail de test avant d'avoir autorisé la délégation → l'erreur parle de la "délégation" pas encore autorisée et l'étape n'est pas cochée
- [ ] Autoriser la délégation dans la console d'administration, puis envoyer le mail de test → le mail arrive, l'étape est cochée
- [ ] Utiliser une adresse affichée différente (groupe no-reply) qui n'est pas un alias d'envoi → l'erreur parle de l'alias refusé et explique la configuration dans Gmail
- [ ] Ajouter l'alias dans Gmail puis renvoyer un mail de test → le mail arrive avec la bonne adresse d'expéditeur
- [ ] Envoyer le mail de test sur un appareil où l'utilisateur n'a aucune organisation → l'erreur explique qu'aucune organisation n'est enregistrée sur cet appareil
- [ ] Vérifier que le mail de test est lié au projet en cours → après un changement de projet, le mail de test précédent ne coche pas le nouveau projet

## Erreurs

- [ ] Provoquer une erreur connue (par exemple un identifiant de projet pris) → la boîte d'erreur montre le titre, la cause, ce que l'application a déjà essayé et les étapes numérotées
- [ ] Sur une erreur avec une page à ouvrir → le bouton "Ouvrir la page" ouvre la bonne page dans le navigateur
- [ ] Cliquer sur "Réessayer" après avoir corrigé la cause → l'étape se relance avec les valeurs actuelles du formulaire et non celles du premier essai
- [ ] Cliquer sur "Copier le rapport" puis coller dans un éditeur → le rapport contient l'étape, l'identifiant d'erreur, les dernières lignes, l'OS et les versions des outils, et aucun mot de passe ni jeton
- [ ] Provoquer une erreur inconnue → le titre est la vraie ligne d'erreur de Google, pas seulement une phrase générique
- [ ] Déplier les détails techniques → ils sont repliés par défaut et ne contiennent aucun mot de passe
- [ ] Couper le réseau pendant une commande longue → l'erreur indique un délai dépassé ou une absence de réponse, avec "Réessayer"
- [ ] Ouvrir le dossier des journaux → un fichier par commande existe, il contient la réponse de Google et aucun secret

## Vérification des coches

- [ ] Rouvrir la page sur un projet entièrement installé → toutes les étapes sont cochées sans relancer quoi que ce soit
- [ ] Supprimer la version en ligne du site d'hébergement dans la console, puis cliquer sur "Tout vérifier" → l'étape du site est décochée
- [ ] Supprimer une fonction dans la console, puis cliquer sur "Tout vérifier" → l'étape du serveur est décochée
- [ ] Laisser une fonction en échec (FAILED) dans le projet, puis "Tout vérifier" → l'étape du serveur n'est pas cochée
- [ ] Rendre la liste des bases illisible (couper le réseau), puis "Tout vérifier" → les coches existantes ne sont pas retirées à tort
- [ ] Lancer "Tout vérifier" pendant qu'une étape tourne → l'étape en cours n'est pas modifiée

## Windows

- [ ] Utiliser un compte Windows dont le dossier personnel contient un espace et un accent (par exemple `C:\Users\Élodie Martin`) → l'installation des outils, la connexion et le déploiement fonctionnent
- [ ] Installer les outils avec Windows Defender activé → l'installation réussit, ou une erreur nomme le dossier et demande de mettre l'antivirus en pause
- [ ] Après une pause de l'antivirus, cliquer sur "Réessayer" → l'installation se termine
- [ ] Vérifier que l'extraction de l'archive des outils ne dépend pas de `tar` → l'extraction réussit sur un Windows sans `tar` récent
- [ ] Déployer le serveur sur Windows avec un long chemin de dossier → `npm install` réussit, ou l'erreur parle des chemins trop longs
- [ ] Lire les messages d'erreur contenant des accents → les accents et les apostrophes s'affichent correctement, sans `\'` ni caractères cassés
- [ ] Derrière un proxy d'entreprise sous Windows, refaire l'installation des outils et un déploiement → tout passe par le proxy du système
- [ ] Vérifier le lancement du menu Démarrer après un redémarrage de Windows → l'application démarre et la deuxième instance reste bloquée
