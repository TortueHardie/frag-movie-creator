# Highlights

Transforme des captures de gameplay brutes (Outplayed, OBS, ShadowPlay…) en montages courts des meilleurs moments, sans intervention manuelle.

Deux interfaces sur le même moteur : une **application de bureau** (Compose) et une **ligne de commande**.

- **Détection des moments** : volume du jeu (EBU R128), prises de parole et rires (YAMNet), événements enregistrés par
  Outplayed (kills, morts, assistances), killfeed lu à l'image quand Outplayed n'est pas là (VALORANT, Counter-Strike 2),
  icônes du HUD et journal des gains lu par OCR (Wardogs), compteur d'éliminations du HUD lu
  chiffre par chiffre (Fortnite), notifications de kill
  lues par OCR (Apex Legends).
- **Highlights** : les meilleurs moments fusionnés et exportés en 16:9 et/ou 9:16, encodés par le GPU (AMD, NVIDIA,
  Intel) avec repli logiciel.
- **Étalonnage** (`edit.grade` d'un profil, pour les highlights comme pour le montage) : table de correspondance
  `.cube` (chemin relatif au dossier de configuration), saturation, contraste et vignettage. Sert à unifier des
  captures venues de sessions ou de jeux différents. Neutre tant qu'on n'y touche pas.
- **Montage kills** : tous les kills calés sur une musique (temps, sections, drop), avec effets, style TikTok.
- **Profils par jeu** (`config/profiles/`) : LoL, VALORANT, Counter-Strike 2, Wardogs, Fortnite, Apex Legends, et un profil par défaut.
  - **Fortnite** (`fortnite.yaml`, fichiers dont le chemin contient « Fortnite ») : les éliminations du joueur sont lues
    sur son compteur en haut à droite (détecteur `hud-counter` : chiffres comparés à `config/templates/fortnite/digit_*.png`,
    deux images par seconde, puis suite croissante la plus probable, remise à zéro à chaque partie : `elimination`).
    **Mettre à terre, c'est le kill** : le `kill` du montage est la notification « KNOCKED! » que l'élimination achève
    (rattachement sur 30 s, `params.knocks`), sans second plan quand l'adversaire est achevé ; une élimination sans
    mise à terre reste un kill à son instant, une équipe éliminée d'un coup reste un multi-kill (« QUADRUPLÉ »), une
    mise à terre jamais créditée au joueur reste un kill. Réglé et vérifié sur une seule vidéo YouTube 854x480 de trois
    parties en escouade : 31 éliminations sur 31 et 33 kills sur 33 (`verite/fortnite/`), aucun en trop. Non vérifié : captures 1080p, HUD déplacé dans les options du jeu, parties
    où le joueur meurt et regarde un coéquipier (son compteur s'affiche alors à la place).
- **Mémoire des analyses** : une capture déjà analysée n'est pas réanalysée. La rechoisir (application ou ligne de
  commande) reprend son analyse instantanément, moments décochés compris ; seule la sélection (seuil, cible) est
  recalculée. Elle est refaite d'office si le fichier change ou si les détecteurs, fenêtres ou pistes audio de son
  profil changent (retoucher la sélection ou le montage du profil ne la refait pas). Forcer : « Réanalyser » sur une
  analyse ouverte, ou `--reanalyze`. L'index est `sessions/library.json` dans le dossier de sortie de `app.yaml`.
- **Analyses enregistrées** : quand aucune analyse n'est ouverte, l'application liste celles déjà faites ; un clic en
  rouvre une, plusieurs cochées s'ouvrent ensemble pour un seul montage (toute une soirée).
- **File des traitements** : un export, un montage ou des aperçus se lancent pendant qu'un autre tourne ; ils attendent
  leur tour dans la carte « Traitements » (un seul traitement lourd à la fois : FFmpeg et la mémoire ne supportent pas
  mieux). On y annule un traitement en attente ou en cours, on change l'ordre de ceux qui attendent, on ouvre le
  résultat d'un traitement fini ou le détail d'un échec, qui ne bloque pas les suivants. Le dossier surveillé et le
  montage des soirées passent par la même file, derrière ce qu'on lance à la main : un export demandé passe devant les
  analyses du dossier surveillé (marquées « auto ») sans interrompre celle en cours. Fermer l'application avec des traitements en file demande
  confirmation ; ils ne reprennent pas au lancement suivant.
- **Dossier surveillé** : dans l'application, « Surveiller un dossier… » (celui d'Outplayed, d'OBS…, sous-dossiers
  compris). Chaque nouvelle capture y est analysée en fond une fois son enregistrement terminé (fichier inchangé
  depuis 15 s), puis apparaît dans la liste marquée « nouveau » ; l'export reste à lancer soi-même. Les captures déjà
  présentes quand le dossier est choisi ne sont pas analysées d'office, celles arrivées pendant que l'application était
  fermée le sont à la relance. Le dossier est retenu d'un lancement à l'autre (`~/.highlights/watch.json`).
  Une rafale de clips (douze sauvegardés à la chaîne) passe en entier dans la file, dans l'ordre d'arrivée, et le
  montage de la soirée attend qu'ils soient tous analysés ; les fichiers en cours d'écriture de FTHRClips
  (`….partial.<jeton>.mp4`, dossiers `.fthr-…`) ne sont jamais pris.
- **Montage de la soirée, sans clic** (case « Monter chaque soirée finie » de la carte du dossier surveillé, désactivée
  par défaut) : quand la soirée est finie (aucune nouvelle partie depuis 1 h, réglable, et rien en cours
  d'enregistrement ni d'analyse), un montage kills de toutes ses parties se fait seul : soirée découpée comme les
  statistiques (de 6 h à 6 h, un jeu à la fois), musique choisie dans le dossier de musiques du dialogue du montage,
  formats et plateforme des réglages, style choisi dans la carte (par défaut celui de `montage-styles.yaml`, « aucun »
  pour les réglages du profil seuls ; `app soiree --style`). Une notification Windows le dit (« Soirée du 16/11 : 47 kills, montage prêt »),
  un clic ouvre la vidéo. Rien n'est monté ni notifié sous 5 kills (réglable). Seules les soirées d'une capture arrivée
  dans le dossier surveillé pendant que l'option est active comptent (l'activer ne remonte pas l'historique), et une
  soirée n'est jamais remontée d'office (`output/sessions/evening-montages.json`). Seulement quand l'application est
  ouverte : ni service ni tâche planifiée. `app soiree [--date AAAA-MM-JJ] [--game wardogs] --music <musique ou
  dossier>` monte une soirée analysée à la demande, `app soiree --list` liste les soirées et celles déjà montées.

## Installer l'application (Windows)

Rien à compiler ni à installer d'autre. Dans [les releases du dépôt](../../releases/latest), prendre :

- **`Highlights-<version>.msi`** : double-clic, l'installation se fait dans le profil de l'utilisateur
  (`%LOCALAPPDATA%\Highlights`), sans droits administrateur, avec un raccourci sur le bureau et dans le menu Démarrer ;
- **`Highlights-<version>-portable.zip`** : la même application sans installation, à décompresser puis lancer
  `Highlights\Highlights.exe` (clé USB, poste verrouillé…).

Tout est inclus : Java, FFmpeg, le modèle YAMNet et les profils de jeu ; ni Outplayed ni carte graphique particulière
ne sont nécessaires.

**Configuration requise**

| | Minimum | Recommandé |
|---|---|---|
| Système | Windows 10 64 bits | Windows 11 64 bits |
| Mémoire vive | 8 Go | 16 Go (pour analyser ou monter pendant qu'un jeu tourne) |
| Carte graphique | facultative : sans elle, encodage par le processeur (libx264), plus lent | NVIDIA (NVENC), AMD (AMF) ou Intel (QSV) : export bien plus rapide |

Ce que coûte un traitement, mesuré sur une vraie soirée (captures 3440×1440 à 60 img/s) : l'application seule occupe
550 à 800 Mo (interface, Java, modèle YAMNet), dont 50 à 150 Mo de données Java pendant une analyse ; chaque FFmpeg
lancé à côté prend 60 à 380 Mo (décodage pour l'analyse, rendu pour l'export), deux au plus à la fois. Avant de lancer
une analyse, Highlights vérifie qu'au moins 1 Go de mémoire est disponible (1,5 Go pour un export ou un montage) ;
sinon il explique quoi fermer, au lieu de laisser Windows ou FFmpeg arrêter le traitement en route (« Lancer quand
même » reste possible). Si la mémoire Java s'épuise malgré tout, le traitement s'arrête avec un message et
l'application reste ouverte.

Réglages de Java dans le lanceur (`app-ui/build.gradle.kts`) : petit tas de départ (`-Xms64m`) pour démarrer même
quand la mémoire de Windows est presque pleine (les plantages « G1 failed to reserve 512 MB » venaient de la réserve
de départ par défaut, 1/64 de la RAM), plafond à 25 % de la RAM plutôt qu'une taille fixe (2 Go sur un PC de 8 Go,
largement au-dessus des 150 Mo mesurés, le reste pour Windows, le jeu et FFmpeg), et mémoire rendue à Windows après
30 s sans travail (`G1PeriodicGCInterval`), utile quand l'application surveille un dossier pendant qu'on joue.

**Signaler un problème** : menu **?** en haut à gauche → « Signaler un problème » (aussi proposé dans chaque boîte
d'erreur, et au lancement qui suit un plantage). Highlights prépare un fichier ZIP et montre ce qu'il contiendra avant
de l'enregistrer (sur le Bureau par défaut) : journaux récents, rapports de plantage de Java (`hs_err_pid*.log`),
diagnostic (version, Windows, mémoire, carte graphique, FFmpeg, encodeurs, détecteurs, profils), et l'analyse ou le
rapport de montage ouverts. Jamais de vidéo, de musique, d'identifiant OAuth (`client_secret*.json`) ni de jeton YouTube ;
le dossier personnel et le nom d'utilisateur Windows sont remplacés par `~` et `<user>` dans chaque fichier, jetons et
adresses e-mail masqués. Rien n'est envoyé : la page « nouveau ticket » de GitHub s'ouvre préremplie (version,
système, questions à compléter), il reste à y glisser le ZIP. Le menu ouvre aussi le dossier des journaux
(`~/.highlights/logs`).

L'installeur n'étant pas signé, Windows affiche « Windows a protégé votre ordinateur » : « Informations
complémentaires », puis « Exécuter quand même ». (Signer demanderait un certificat payant.)

**Mettre à jour** : au démarrage, Highlights regarde s'il existe une release plus récente et propose « Mettre à jour » :
le MSI est téléchargé (taille et empreinte vérifiées), l'application se ferme, s'installe par-dessus dans le même
dossier puis se relance. À la main, il suffit aussi de lancer le MSI de la nouvelle version. Il remplace l'installation existante au lieu de
s'ajouter à côté : une seule entrée dans « Applications installées », une seule application dans le menu Démarrer.
Relancer l'installeur d'une version déjà installée ne crée pas non plus de doublon. Les profils modifiés dans
`%APPDATA%\Highlights\config` sont conservés ; les fichiers livrés qui n'ont pas été retouchés sont mis à jour.

**Désinstaller** : Paramètres Windows → Applications → Applications installées → Highlights → Désinstaller (ou un
clic droit sur l'application dans le menu Démarrer). L'application, FFmpeg livré avec elle et les raccourcis du bureau
et du menu Démarrer sont retirés. Les montages déjà produits (`Vidéos\Highlights`) et la configuration
(`%APPDATA%\Highlights`) restent en place : à supprimer à la main si vous n'en voulez plus.

Ces parcours sont rejoués sur une machine Windows neuve à chaque publication (`.github/workflows/installeur-verif.yml`) :
installation, mise à jour vers une version plus récente, réinstallation de la même version, désinstallation, avec
contrôle du registre, des fichiers et des raccourcis après chaque étape. Chaque construction vérifie en plus, dans le
MSI lui-même, qu'il s'installe sans droits administrateur et qu'il porte un code de mise à niveau.

## Prérequis (développement)

- JDK 21
- FFmpeg : `winget install Gyan.FFmpeg`. Il est détecté automatiquement (PATH puis installation winget) ; sinon
  renseigner `ffmpeg.ffmpegPath` dans `config/app.yaml`. FFmpeg 7 ou plus est conseillé ; une version plus ancienne
  (6 et avant) marche aussi, le graphe de filtres lui est alors passé avec l'option qu'elle comprend.

## Construire et tester

```powershell
.\gradlew.bat test                    # unitaires + intégration (vidéos synthétiques générées par FFmpeg)
.\gradlew.bat :app-cli:installDist    # → app-cli\build\install\app\bin\app.bat
```

### Vérité terrain : prouver qu'un changement de détecteur n'a rien cassé

`verite/<jeu>/*.yaml` liste, pour quelques vraies captures, les vrais kills et morts du joueur avec leurs temps, relevés
image par image et validés par le joueur. Les vidéos ne sont pas dans le dépôt : une capture y est reconnue par son nom,
sa taille et sa durée. `app verite` réanalyse chaque capture avec le code du moment et mesure chaque détecteur :

```powershell
.\gradlew.bat :app-cli:verite "-Pverite=--videos D:\Vidéo"          # réanalyse (longue : une analyse par capture)
& $app verite verite --sessions D:\dev\editor\output\sessions        # mesure les analyses déjà enregistrées (immédiat)
```

Pour chaque capture et chaque détecteur : vrais (retrouvés dans la tolérance), faux (détections sans vrai événement),
manqués, précision, rappel, avec l'instant de chaque faux et de chaque manqué. Une détection ne valide qu'un seul vrai
événement (deux kills à 1 s d'écart demandent deux détections). Les seuils (`thresholds` du fichier) sont l'état mesuré
au moment du relevé : la commande échoue (code 1) si un détecteur passe en dessous, et écrit `verite-rapport.json`.
Les analyses refaites vont dans `-o` (défaut : `%TEMP%\highlights\verite`), avec leur propre mémoire d'analyses : la
bibliothèque de l'utilisateur n'est pas touchée.

Format d'un fichier :

```yaml
capture: { name: WARDOGS_09-23-2026_20-4-19-983.mp4, sizeBytes: 6911741815, duration: 2708.992s }
profile: wardogs
tolerance: 3s               # écart toléré entre l'instant relevé et l'instant détecté
validated: joueur, 2026-11-18
kinds: [kill, death]        # sortes relevées sans oubli ; les autres (réanimations…) ne comptent pas
events:
  - { at: "07:56", kind: kill, note: "ÉLIMINATION dans le journal" }
thresholds:
  game-events: { kill: { precision: 1.0, recall: 1.0 } }
```

## Construire l'installeur

```powershell
.\gradlew.bat :app-ui:packageMsi     # → app-ui\build\compose\binaries\main\msi\Highlights-<version>.msi
.\gradlew.bat :app-ui:portableZip    # → app-ui\build\distributions\Highlights-<version>-portable.zip
```

Sur une machine neuve, il ne manque que [WiX 3](https://github.com/wixtoolset/wix3/releases) (utilisé par `jpackage`
pour écrire un MSI) : FFmpeg est téléchargé par le build s'il n'est pas déjà là. Le MSI (≈ 340 Mo) contient tout :
Java, FFmpeg, le modèle YAMNet, les profils et les modèles d'images.

**Publier une version** : augmenter `highlights.version` dans `gradle.properties`, puis pousser un tag `v<version>`
(`git tag v1.2.0 && git push origin v1.2.0`). Le workflow `.github/workflows/installeur.yml` construit le MSI et le ZIP
portable sur une machine Windows neuve, lance les tests, installe puis désinstalle le MSI produit avant de le publier
dans une release GitHub. En parallèle, `installeur-verif.yml` rejoue le cycle complet : installation, mise à jour vers
une version plus récente, réinstallation de la même version, désinstallation.

Le numéro de version doit augmenter d'une release à l'autre : c'est lui qui déclenche le remplacement de l'ancienne
installation (le code de mise à niveau, `upgradeUuid` dans `app-ui/build.gradle.kts`, ne doit lui jamais changer).

- **Configuration** : au premier lancement, la config livrée est copiée dans `%APPDATA%\Highlights\config`, où les
  profils se modifient (puis « Recharger » dans l'application). Une mise à jour de l'application apporte les nouveaux
  fichiers et remplace ceux qui n'ont pas été retouchés ; les profils modifiés sont conservés.
- **Montages** : écrits dans `Vidéos\Highlights` (`outputDir` dans `app.yaml` ; `~/` désigne le dossier de l'utilisateur).
- **FFmpeg** : celui de l'installeur est utilisé en priorité (sauf `ffmpeg.ffmpegPath` dans `app.yaml`). À la
  construction, il est pris dans `-PffmpegDir=<dossier contenant bin\ffmpeg.exe>`, sinon dans l'installation winget
  `Gyan.FFmpeg`, sinon téléchargé (build Windows 64 bits GPL ; `-PffmpegUrl` pour changer d'archive et
  `-PffmpegSha256` pour figer son empreinte). Sa licence (GPL) est livrée à côté, dans `resources\ffmpeg`.
- **Version** : `highlights.version` dans `gradle.properties`. À augmenter à chaque nouvel installeur, sinon Windows
  refuse la mise à jour ; l'ancienne version est remplacée automatiquement.
- Pour qu'une application installée lise la config du projet : variable d'environnement `HIGHLIGHTS_CONFIG=D:\...\config\app.yaml`.

## Marche avec n'importe quelle configuration

Rien à régler avant d'analyser une première capture : l'application s'adapte à ce qu'elle trouve.

- **Outplayed ou pas** : les événements du jeu (kills, morts) viennent d'Outplayed quand il est là. Sinon, pour
  VALORANT, ils sont lus dans le killfeed de la capture (voir plus bas) ; pour les autres jeux ce signal est simplement
  absent, les autres (volume, voix, rires, icônes du HUD) continuent et leur poids est redistribué.
- **Pistes audio** : elles sont désignées par leur rôle (`game`, `mic`, `mix`, `chat` = voix des coéquipiers), pas par
  leur numéro, et le joueur n'a rien à régler. Outplayed en écrit trois (mix, jeu, micro), OBS une ou deux (jeu puis
  micro), FTHRClips une piste « Default Mix », le micro et, au choix, une piste par application ou par appareil
  (« Gaming », « Chat » de SteelSeries Sonar). Ordre de décision : la description que l'enregistreur écrit à côté de la
  vidéo (FTHRClips : `<clip>.mp4.fthr-audio.json`), puis les titres des pistes (mots de toutes les langues de
  l'application, en début de mot : « Call of Duty » n'est pas un mix), puis l'écoute (quatre passages de 15 s, toutes
  les pistes ensemble, énergie par tranche de 20 ms : une copie d'une autre piste est écartée, une piste muette aussi,
  une piste dont l'énergie est la somme des autres est le mix, celle qui sonne presque tout le temps est le jeu), enfin
  leur nombre. Le montage garde tout le son sans le doubler : la piste de mix si elle existe, sinon les pistes
  qu'aucune autre ne contient déjà (les pistes d'application de FTHRClips sont dans sa « Default Mix »). Pour imposer
  des index : `audio: { game: 1, mic: 2, chat: 3 }` dans le profil.
- **Format d'écran** : les zones du HUD mesurées sur un écran ultrawide sont converties pour une capture 16:9 (et
  l'inverse). Une zone garde sa taille et sa distance au bord auquel elle est accrochée, comme le fait l'interface du
  jeu ; il suffit de déclarer la capture de référence (`referenceWidth`/`referenceHeight`, ou `vertical.reference`).
- **Carte graphique** : AMD, NVIDIA, Intel et Apple sont essayés dans l'ordre, chacun par un vrai encodage de test, avec
  repli sur l'encodeur logiciel. Le décodage matériel est en `auto` et retombe en logiciel si le rendu échoue.
- **Résolution et cadence** : le montage ne dépasse jamais celles de la capture (un enregistrement 720p à 30 img/s n'est
  ni agrandi ni dupliqué), et un montage mélangeant plusieurs captures les ramène toutes au format de la première.
- **Plusieurs captures, un seul montage** : toute une soirée d'un coup, une capture par partie. Chaque capture est
  analysée et garde sa session ; la cible (`--top`, `--duration`) vaut pour l'ensemble, si bien que « les 8 meilleurs
  moments » sont les 8 meilleurs de la soirée et non 8 par partie. Les meilleurs moments se suivent dans l'ordre où les
  parties ont été jouées (date d'enregistrement de la capture, sinon date du fichier), quel que soit l'ordre dans lequel
  les fichiers sont donnés. Le montage kills, lui, place les kills sur la musique sans tenir compte de la partie
  d'origine (sauf avec `--chronological`).
- **Micro absent, capture muette, OCR de Windows indisponible** : chaque signal manquant est signalé et ignoré, jamais
  bloquant.

Pour voir ce que ça donne sur une machine donnée :

```powershell
& $app doctor                       # FFmpeg, encodeurs utilisables, prérequis des détecteurs, profils
& $app doctor D:\Videos\partie.mp4  # + format d'écran, rôle des pistes, profil retenu, détecteurs actifs
```

## Interface graphique (développement)

```powershell
.\gradlew.bat :app-ui:run   # lit config/ du projet : une modification de profil s'applique sans reconstruire
```

Via « Recharger », ou « Régénérer » dans l'aperçu 9:16.

**Premier lancement.** Un nouveau joueur (ni dossier surveillé, ni analyse) voit d'abord un assistant en quatre étapes
(enregistreur → jeux → langue → premier montage, « Passer » à chaque étape), rouvrable depuis le menu « ? » →
« Premiers pas ». Il cherche les enregistreurs installés et lit leurs réglages, sans jamais rien y
changer : Outplayed (dossier des captures d'Overwolf, `Settings/SettingsPageGeneral.xml`, + `Outplayed`), OBS Studio
(profil de `user.ini`/`global.ini`, dossier d'enregistrement du mode Simple ou Avancé, pistes de `RecTracks`), NVIDIA
ShadowPlay (registre `NVSPCAPS\DefaultPathW`), FTHR Clips (`~/.fthr/settings.json` : `clips_directory`,
`recording_directory` (`<clips>\Recordings`, où FTHR écrit `FTHR_recording_<date>.mp4` : le dossier proposé),
`audio_capture_mode`), Medal (fichiers de `%APPDATA%\Medal\store`, sinon `C:\Medal`). Il montre
pour chacun le dossier, le nombre de vidéos, les jeux reconnus et si le micro est sur sa propre piste ; propose le
dossier dont la dernière vidéo est la plus récente, avec les réglages conseillés pour cet enregistreur. L'étape des jeux
liste les jeux du dossier et choisit celui du premier montage : le plus joué (un jeu reconnu d'abord), ou un autre d'un
clic. « Faire mon premier montage » surveille ce dossier (case cochée), analyse la dernière soirée de ce jeu (5 parties
au plus, à moins de 3 h l'une de l'autre) et ouvre le parcours du montage. Rien trouvé : comment enregistrer et quel
enregistreur installer. Vu une fois : `~/.highlights/first-run.json`. Pour le piloter sur une fausse machine :
`-PrecordersRoot=<dossier>` (`home`, `AppData/Roaming`, `AppData/Local`, `Program Files` dedans).

Dans l'application :
1. choisir ou glisser une ou plusieurs captures (« Ajouter des vidéos » pour compléter la liste) ;
2. régler le profil, les formats, la durée et le seuil ;
3. cliquer sur « Analyser » ;
4. relire les segments : timeline (glisser le long de la courbe parcourt la capture dans le lecteur intégré), vignettes, « ▶ Revoir » ouvre un extrait dans le lecteur, « 9:16 » affiche l'aperçu vertical avec le HUD ;
5. décocher les segments à retirer (le seuil et la durée se recalculent sans réanalyser) ;
6. retoucher à la main si besoin (voir ci-dessous) ;
7. cliquer sur « Exporter le montage ».

**Retoucher les moments.** Un moment sélectionné montre une barre de retouche : « Début » et « Fin » se déplacent de
−1 s, −0,5 s, +0,5 s ou +1 s, dans les limites de la vidéo et des longueurs du profil (`selection.minClip`/`maxClip` ;
un moment déjà étendu au-delà peut raccourcir, pas grandir). « ↑ Plus tôt » / « ↓ Plus tard » changent sa place dans
le montage (n° affiché sur chaque ligne) : l'export suit alors cet ordre manuel (`order: manual`), toutes captures de la
soirée confondues. Un double-clic sur la courbe ajoute un moment de 8 s centré à cet endroit, marqué « ajouté à la
main ». Ces retouches sont enregistrées dans la session (`origin`, `order` de chaque moment) et survivent au changement
de seuil ou de durée : un moment détecté qui recouvre un moment retouché laisse la place à la retouche.
**Lecteur intégré.** Les vidéos se lisent dans l'application : « Lire » d'une variante, « ▶ Lire » d'un export fini
(sous le résultat et dans la file des traitements), « ▶ Revoir » d'un moment, et la frise d'une session (glisser le
long de la courbe montre l'image de la capture à cet endroit, relâcher lance la lecture à partir de là, une ligne
blanche marque la tête de lecture). Lecture/pause,
barre de position (image par image pendant le glissement), temps écoulé et total ; clavier : Espace (ou K) lecture/pause,
← / → (ou J / L) 5 s en arrière ou en avant, Échap ferme. « Lecteur du système » reste à côté, en secours.
Aucune dépendance nouvelle : le FFmpeg livré décode les images réduites à 720p (RGBA brut) et le son (PCM 48 kHz vers
Java Sound) ; le son est l'horloge maîtresse, une image en retard de plus d'une trame et demie est sautée. Décodage
matériel (`-hwaccel auto`) avec repli logiciel au premier échec ; sans sortie son, lecture muette sur l'horloge murale.
Cadence plafonnée par un budget de pixels (un montage 9:16 reste à 60 img/s, une capture 21:9 passe à 30) ; si l'image
prend plus d'une seconde de retard, la lecture repart à la position du son à cadence moitié. Mesuré sur un montage
1080×1920 à 60 img/s (Ryzen 7 5800X) : écart image/son moyen 4 ms, 95 % sous 7,5 ms, 2 images sautées sur 2838,
environ 5 % du processeur.

**Oublier une analyse.** Dans la liste des analyses, « Oublier » (après confirmation) retire l'analyse de la mémoire
et efface sa session ; la vidéo n'est jamais touchée et peut être réanalysée.

### Pont de pilotage (outils de développement)

```powershell
.\gradlew.bat :app-ui:run -Pbridge
.\gradlew.bat :app-ui:run -Pbridge -PuserHome=D:/tmp/maison   # préférences isolées : pas de dossier surveillé repris
```

Démarre aussi un petit serveur HTTP local (127.0.0.1, port au hasard, jeton au hasard, tous deux dans
`~/.highlights/control-bridge.json`) qui permet à un outil, par exemple une session Claude, de piloter l'application
sans prendre la souris ni le clavier : état de l'interface en JSON (`/state`), actions du contrôleur (`/action`, les
boîtes de dialogue de fichiers recevant des chemins), captures des fenêtres rendues par Skia même quand elles sont
cachées (`/screenshot`), arbre d'accessibilité (`/tree`), clics et frappes remis directement à la fenêtre (`/click`,
`/type`, `/key`, `/scroll`), fin du journal (`/logs`). Mode d'emploi : `.claude/skills/drive-app/SKILL.md`.

Jamais actif sans `-Pbridge`, ni dans l'application installée : le pont refuse de démarrer sous le lanceur de l'installeur
(MSI ou portable), et `createDistributable` échoue si ce lanceur active la propriété `highlights.bridge`.

## Langues de l'interface

L'application parle anglais, français, espagnol, portugais du Brésil, italien, allemand, polonais, turc, russe,
japonais, coréen et chinois simplifié ; d'autres langues s'ajoutent par simple fichier. Par défaut, la langue
d'affichage de Windows (sinon l'anglais) ; le sélecteur en haut à gauche (à côté de « Highlights ») en choisit une
autre, appliquée tout de suite et retenue dans `~/.highlights/language.json` (« Langue du système » revient au
réglage de Windows). Sont traduits : toute l'interface, les messages d'erreur, les textes YouTube proposés et les
textes incrustés par défaut dans les vidéos (DOUBLE KILL / DOUBLÉ…). La ligne de commande et les journaux restent en
français.

- **Ajouter une langue** : copier `core/src/main/resources/i18n/messages.properties` (l'anglais, langue de repli) en
  `messages_<tag>.properties` (`de`, `es`, `pt_BR`, `zh_CN`…), UTF-8, et traduire les valeurs. Rien d'autre : la langue
  apparaît dans le sélecteur sous son `language.name`. `plural.rule` choisit la famille de pluriels (`one`, `zero-one`,
  `east-slavic`, `polish`, `czech`, `none`) ; une clé `….one/.few/.many/.other` est une forme plurielle choisie
  d'après `{count}`. Les `{placeholders}` se gardent tels quels.
- **Vérifier** : `.\gradlew.bat :core:test` (`BundlesTest` : clés manquantes ou en trop, placeholders différents de
  l'anglais, formes plurielles absentes, clé citée dans le code mais absente) ; `.\gradlew.bat :app-ui:test` rend
  chaque écran en français, en anglais, en pseudo-langue allongée de 40 % et dans chaque langue livrée
  (`app-ui/build/ui-snapshots/`, `en/`, `long/`, `ru/`, `ja/`…) et signale tout texte écrit en dur (`HardcodedTextTest`).
  Garder les `\u0020` de `format.listSeparator` / `format.listComma` : un éditeur (ou un outil) qui les remplace par
  une espace la fait disparaître au chargement.
- **Essayer une langue sans la choisir** : `-Dhighlights.lang=de` (ou `en-XA`, la pseudo-langue : textes accentués et
  allongés, pour voir ceux qui débordent ou ne passent pas par les traductions).
- **Textes incrustés** : les libellés DOUBLE KILL… viennent de la langue de l'interface au moment du rendu, sauf si le
  profil en donne (`story.labels.multiKill`, `montage.text.multiKillLabels`). Polices : la police du profil
  (`montage.text.font`, `story.captions.font`, Impact par défaut) sert tant qu'elle a tous les caractères du texte
  (latin, cyrillique : DOUBLE KILL, ДАБЛ КИЛЛ) ; sinon le texte passe dans une police livrée avec Windows 10/11 qui
  les a : Yu Gothic Bold (japonais), Malgun Gothic Bold (coréen), Microsoft YaHei Bold (chinois), Arial Black ou
  Segoe UI Black (autres écritures). Les sous-titres whisper suivent la même règle, mot à mot selon la phrase.
  `drawtext` n'a aucun repli : sans ce choix, un caractère absent de la police s'affiche en carré vide.

## Montage « story » (façon vidéo YouTube)

Style par défaut des profils livrés (`edit.style: story`, ou `--style story|simple` sur `process` et `export`). Les
meilleurs moments ne sont plus posés bout à bout : chacun est remonté comme le ferait un monteur YouTube.

- **Jump cuts** : dans chaque moment, les passages creux (ni voix, ni rire, ni kill, score bas) de plus de 1,5 s sont
  retirés, avec 300 ms de marge de chaque côté ; l'attente avant l'action est rognée. Une phrase n'est jamais coupée.
- **Cadrage alterné** : un plan sur deux après un jump cut est 8 % plus serré, le saut se lit comme un changement de caméra.
- **Accroche** : 3 s du meilleur moment ouvrent la vidéo, avant de reprendre dans l'ordre.
- **Punch-in** sur les rires et les cris, **secousse** de l'image sur les kills et le pic de chaque moment.
- **Transitions** : coupe franche soulignée d'un flash blanc et d'un whoosh ; impact sourd sous chaque secousse.
  Plusieurs variantes générées par FFmpeg servent à tour de rôle ; un dossier de sons (`story.sfx.whooshDir` /
  `impactDir`) les remplace, chaque son à son tour, dans un ordre mélangé mais stable d'un export à l'autre.
- **Ralenti** sur le pic des moments forts (le meilleur, les séries de kills) : l'image ralentit, le son du jeu garde
  sa vitesse puis s'efface.
- **Libellés** « DOUBLÉ », « TRIPLÉ », « QUADRUPLÉ », « ACE » (dans la langue de l'interface : DOUBLE KILL… en anglais) au kill
  qui prolonge une série (kills à moins de 5 s).
- **Musique de fond** facultative (`story.music.file` ou `--music`) : baissée sous la voix, coupée net sur le pic de
  chaque moment puis relancée en fondu.
- **Sous-titres de la voix** : le micro est transcrit en local par whisper.cpp (filtre `whisper` de FFmpeg), deux à
  quatre mots à la fois, en gros, qui apparaissent d'un coup de zoom. Les mots forts (« GG », « ACE », jurons…,
  `story.captions.emphasis`) passent en jaune, une phrase criée en rouge et plus grosse. Seul ce qui recouvre une prise de parole détectée
  est gardé (whisper invente du texte sur le silence). La transcription est mise en cache : un nouvel export des mêmes
  moments ne la refait pas. Il faut le modèle, trop lourd pour le dépôt et l'installeur :
  [`ggml-large-v3-turbo-q5_0.bin`](https://huggingface.co/ggerganov/whisper.cpp) (~574 Mo) dans `config/models/`
  (`story.captions.model`). Sans lui, le montage sort sans sous-titres.
  Les **coéquipiers** sont sous-titrés aussi quand la capture a leur voix sur une piste à elle (rôle `chat` : Discord,
  chat de SteelSeries Sonar, enregistrés à part par FTHRClips ou OBS), en bleu clair (`story.captions.friendColor`) ;
  une phrase dite pendant une phrase du joueur s'affiche au-dessus d'elle. Cette piste ne porte que des voix : la
  parole y est lue à son niveau (au-dessus de −45 dBFS, trous de moins de 400 ms comblés), ce qui écarte ce que whisper
  inventerait sur le silence. `story.captions.friends: false` les coupe.
  Une capture **sans piste micro séparée** (une seule piste « tout le son », comme les vidéos VALORANT téléchargées)
  n'est sous-titrée que là où l'analyse a trouvé la voix du joueur : whisper y transcrit sinon l'annonceur et les
  personnages du jeu (« Enemies spotted », « Spike down », « One enemy remains »). Sans détecteur qui isole cette voix
  sur la piste unique, pas de sous-titres. `story.captions.withoutMic: true` force la transcription du son complet,
  `false` l'interdit. Sur le son complet, les répliques du jeu (`story.captions.ignore`, liste du profil VALORANT) sont
  écartées avec toute leur phrase, et un texte redit d'un moment à l'autre aussi.
  Partout : les descriptions de sons (« \*Bruit de feu\* »), les textes sans lettre (« ... », « 501 ») et un même texte
  redit en moins de 10 s (boucle de whisper) sautent. Un sous-titre s'efface avec la voix détectée (fin de la parole
  + 250 ms), 3 s au plus ; un morceau devenu trop court pour être lu est fusionné avec le précédent quand ils se
  suivent (« du » + « bâtiment. ») ; une phrase trop large pour l'image passe sur deux lignes à la taille normale,
  coupées à l'espace le plus proche du milieu ; un segment de whisper étiré sur plus de 8 s est ramené à la
  parole qu'il recouvre (« Essaye de ping » : 14 s → 2 s) au lieu d'être jeté.
- Son adouci à chaque coupe, fondu au noir final, normalisation EBU R128.

```powershell
& $app export output\sessions\partie.session.json --style story --music D:\Musique\fond.mp3 -f source,9:16
```

## Montage « tous les kills » (style TikTok)

Dans l'application : bouton **Montage kills** au-dessus de la liste des moments (après une analyse avec un profil qui
détecte les kills). Une fenêtre s'ouvre sur le **parcours du montage**, en cinq étapes, avec un fil d'Ariane en haut
(on revient librement en arrière ou sur une étape déjà vue, rien n'est perdu) :
1. **Quoi** : « C'est pour où ? » (TikTok, YouTube Shorts, Vidéo complète ou « À ma façon »), « Avec quoi ? » (vos
   kills, vos réactions au micro, que des one taps), les formats (une plateforme impose le sien), le style, et vos
   parties avec leurs kills en vignettes.
2. **Musique** : un morceau ou un dossier où l'application choisit ; le morceau avec sa forme d'onde, sa drop (« Écouter
   la drop » ouvre l'outil de la drop ici même) et la partie prise dans le montage ; « Commencer au début du morceau » ;
   la durée (« Selon mes kills », ou 30 s, 1 min, 3 min, ou une durée tapée).
3. **Sur la musique** : l'éditeur, dans la même fenêtre (voir « Montage sur la musique » plus bas) : **3a** les repères,
   **3b** les plans. En 3b, glisser un kill sur un autre les échange ; le lâcher loin de tout repère y déplace son plan
   (ou y ajoute un plan, pour un kill de la liste) ; « Un autre kill ici » écarte le kill et le repère prend le suivant ;
   « Retirer ce plan » retire le plan et son repère ; « + » entre deux plans ajoute un passage sans kill (vitrine) ;
   « Passage de la partie, longueur… » ouvre le calage (couper ici, sections). L'ancienne frise « Voir le plan… »
   n'existe plus : tout se fait ici.
4. **Finitions** : le son (tout le jeu ou autour des kills, équilibre, réactions au micro, « whoosh » sur les
   transitions), les textes (compteur de kills, « Doublé / Triplé », score des rounds), les cartes de début et de fin,
   les effets (densité, zoom, flash, couleurs, flou) et « Plus de réglages ». Ce qui change le montage est marqué ⟳ :
   le plan est refait, repères gardés.
5. **Aperçu** : « Regarder l'aperçu » (360p, en quelques secondes) ou « 3 idées de l'appli » (variantes, qui remplacent
   vos repères), ce que vous allez avoir (durée, plans, kills dans l'ordre, celui de la drop en rouge), la **miniature**
   et **Créer le montage**. Rien n'est rendu avant.

« Tout en auto ⏭ » (étapes 1 et 2) saute les étapes 3 et 4 : placement automatique, droit à l'aperçu.
Montée en puissance, rythme rapide (et par partie), whip pan, ouverture vitrine et ralenti ne sont plus des cases de
l'application : les repères les remplacent (transition ici, vitrine ici, ralenti ici) ; les styles et la ligne de
commande les gardent pour le placement automatique.

En ligne de commande :

```powershell
& $app montage output\sessions\partie.session.json --music D:\Musique\son.mp3 --max 60s --format 9:16,source
& $app montage output\sessions\partie.session.json --music D:\Musique\TikTok --max 60s   # musique choisie dans le dossier
& $app montage output\sessions\partie.session.json --music D:\Musique\son.mp3 --style tiktok
```

- **Défauts** (choisis par l'utilisateur le 18/11) : style Cinématique, couleurs boostées (`--colors natural` pour les
  couper), ouverture vitrine (`--no-showcase`), whip pans muets (`--whoosh` ou un `--whoosh-dir` pour le souffle),
  rythme rapide sur les parties rapides de la musique (voir « Rythme rapide »).
- **Styles** (`config/montage-styles.yaml`) : un jeu de réglages choisi d'un coup, en haut de la fenêtre de montage
  (puces « Aucun », « Edit TikTok », « Propre », « One taps », « Cinématique ») ou par `--style <id>` ; les cases
  détaillées sont repliées sous « Réglages avancés ». Livrés : `tiktok` (rythme rapide, couleurs boostées, flou, zoom
  d'approche), `propre` (effets sobres, raccords sur la pose et whip pans, ni flash, ni zoom, ni texte), `onetaps`,
  `cinematique` (ouverture vitrine, ralentis et rampes, montée jusqu'à la drop). Un style ne dit que ce qu'il change,
  le reste vient du profil ; une case modifiée à la main (ou une option de la ligne de commande) passe devant lui et
  reste en place quand on change de style, jusqu'à « Revenir au style ». `default:` dans le fichier choisit le style
  pris sans rien demander (`--style none` : profil seul). On peut modifier, retirer ou ajouter des styles ; un style
  sans traduction s'affiche avec son `displayName` et sa `description`.

- **Bibliothèque musicale** : `--music` accepte un dossier (sous-dossiers compris), et le dialogue du montage a un mode
  « Choisir dans un dossier » (dossier retenu d'un montage à l'autre). Le montage est alors planifié sur chaque musique,
  et celle qui obtient la meilleure note (la note du montage : kills sur des temps
  accentués, durée visée tenue, drop à portée, pas de trou) l'emporte ; la note est multipliée par la part des groupes
  de kills que la musique montre, pour qu'une musique trop courte ne gagne pas en en perdant. Planifier ne coûte presque
  rien face au rendu : seules les analyses sont longues, et chaque musique n'est analysée qu'une fois (gardée dans
  `<workDir>/cache/music`, refaite si le fichier change). `app music <dossier>` les fait d'avance. Chaque musique garde
  son réglage « depuis le début » ; `--from-start` l'impose à toutes. Les musiques essayées et leur note sont dans le
  rapport JSON (`musicCandidates`) et affichées par la commande. Pour ne pas ressortir toujours la même, une musique
  des 3 derniers montages (choisie ou imposée, `output/sessions/music-history.json`) perd 0,03 de note : les musiques
  qui collent presque aussi bien tournent, jamais une qui ferait perdre des kills (un groupe perdu coûte plus).
- **Inspections gardées** : le recalage de chaque kill sur le tir, son flick et la pose de l'arme autour de chaque
  groupe (quelques secondes de source décodées par kill, des minutes pour une soirée) sont gardés dans
  `<workDir>/cache/kills`, un fichier par capture, refaits si la capture ou un réglage change. Un nouveau montage des
  mêmes parties (autre musique, autre durée) ne décode que les kills jamais vus. La pose de l'arme n'est lue que pour
  les groupes qui peuvent entrer dans le montage (pas plus de groupes de chaque sorte que de temps dans la durée
  maximale), la visée et le repère d'arme d'un même décodage, avant et après le kill d'une seule lecture. Mesuré sur
  15 parties (245 kills, montage de 30 s en 9:16 et source) : 9 min 20 avant, 5 min 10 au premier montage, 3 min aux
  suivants, le rendu restant ; plan identique au bit près.

- **Durée adaptée au nombre de kills** (`length`) : le montage dure ce qu'il faut pour montrer ses kills — 2,5 s par
  clip (`perClip`), 1 s de plus par kill supplémentaire d'un multi-kill (`perExtraKill`), la réaction gardée après le
  dernier kill et le temps du ralenti des plans forts, au moins 12 s (`min`). La durée maximale (`--max`, case « Durée
  maximale ») n'est plus qu'un plafond : quatre kills ne sont plus étirés sur une minute de musique. Raccourcir ne
  coûte rien à l'écran : si la durée visée ferait perdre un clip, le début d'un multi-kill, la fin d'une réaction ou le
  ralenti d'un flick, elle s'allonge jusqu'à les retrouver. `--fill` (ou la case « Durée adaptée au nombre de kills »
  décochée) rend l'ancien comportement : occuper toute la durée maximale.
- **Musique** : analysée entièrement (`app music son.mp3` pour voir le résultat) : tempo et temps (suivi à ~12 ms, sans
  dérive), mesures, **sections** (délimitées par timbre et volume, alignées sur les mesures) avec leur intensité, et la
  **drop** (plus gros saut d'intensité). Chaque section reçoit aussi un **rôle** — intro, montée, drop, breakdown, corps
  de morceau, outro — qui dit ce qu'elle *fait* et pas seulement à quel point elle joue fort. La section qui mène à la
  drop en est la montée par construction : un riser perd souvent ses basses en gagnant ses aigus, si bien que son volume
  peut même baisser. Ailleurs, il faut l'entendre monter (+2,5 dB entre son premier et son dernier tiers).
- **Coupes qui accélèrent dans une montée** (`cuts.accelerateBuildUp`) : les plans partent du double de leur longueur
  nominale et sont divisés par deux à mi-parcours, puis aux trois quarts — toujours en puissances de deux, donc toujours
  sur les mesures. Le montage préfère aussi s'ouvrir sur une intro ou une montée, pour avoir la rampe qui mène à la drop.
- **Coupes dictées par la musique** : la longueur des plans dépend de la section (courts dans les parties intenses, longs
  dans les calmes, toujours des mesures entières), les frontières de sections sont des coupes, et le montage est placé
  sur la fenêtre de la musique la plus intéressante (montée puis drop vers 40 %), quitte à commencer au milieu du
  morceau. La drop garde une vraie montée avant elle (`cuts.dropLead`, 6 s, au plus 40 % du montage) : un passage qui
  s'ouvre presque sur la drop n'est pris qu'à défaut, même si l'intro calme qui y mène fait baisser l'intensité moyenne.
  Chaque coupe tombe une image avant son temps (`cuts.preBeatFrames`) — l'œil met quelques images à enregistrer un
  nouveau plan — sans déplacer le kill.
- **Choix et ordre des clips** : deux plans voisins ne viennent pas du même moment de la même partie (`varietyGap`), et
  `minScore` permet d'écarter les groupes de kills trop faibles quitte à raccourcir le montage (0 = tout garder ; les
  trois meilleurs sont gardés quoi qu'il arrive). Les meilleurs groupes restants terminent naturellement le montage.
- **Peu de kills trouvés** (`minKills`, 3 par défaut, 0 : jamais) : le montage se fait quand même, mais un
  avertissement le dit (« Seulement 2 kills trouvés dans ces parties : vérifiez l'analyse »), sur la console, dans le
  rapport JSON (`notices`) et sous le résultat dans l'application. Le plus souvent, c'est la détection des kills qui a
  manqué (une partie VALORANT de 44 kills en donnait un). Pas d'avertissement pour des moments choisis dans la recherche.
- **Clips taillés pour la musique** : chaque clip est étendu ou coupé pour remplir exactement son plan ; le dernier kill
  tombe sur le temps le plus accentué du plan, le meilleur groupe (multi-kill, ace) exactement sur la drop — en ordre
  chronologique aussi : le passage de la musique est alors choisi pour que la drop tombe sur lui (ou sur un groupe qui
  le vaut) sans changer l'ordre ni perdre de groupe ; les multi-kills
  et, avec `--reactions`, les réactions (voix, rires) fusionnent des plans voisins ; l'image est gelée si la vidéo manque. Quand il y a peu de
  kills, les plans s'allongent plutôt que de laisser de la musique inutilisée.
- **Chaque kill sur un temps** : dans un multi-kill, la lecture entre deux kills est accélérée ou ralentie de ±15 % au
  plus (speed ramp, `--no-ramp` pour désactiver) pour que chaque kill tombe sur un temps ; dans une suite de plans de
  même longueur, le kill tombe toujours au même endroit du plan (élan). Les kills intermédiaires visent la **frappe** la
  plus marquée à portée, pas seulement le temps le plus proche : un contretemps aussi fort que les temps qui l'entourent
  (caisse claire, clap) compte comme une frappe, un charleston non (`speedRamp.onHits`).
- **Kill recalé sur le tir** (`shotAlign`) : l'instant d'un kill vient d'une notification dont le retard sur le tir
  varie d'un kill à l'autre (`killOffset` n'en corrige que la moyenne). Avant le montage, le son du jeu est lu autour de
  chaque kill (300 ms avant, 50 ms après : le tir qui tue précède toujours sa notification) et le kill est recalé sur l'attaque la plus nette : c'est le tir qu'on entend
  tomber sur le temps. Sans attaque nette (`minRiseDb`), l'instant annoncé est gardé.
- **Kills spectaculaires** (`killStyle`) : tir à la tête (événement `headshot` d'Outplayed), **flick** (la vue balaie
  l'écran puis s'arrête sur la cible, mesuré sur une demi-seconde d'image autour du kill) et kills enchaînés à moins
  d'une seconde ajoutent un bonus au rang d'un groupe : un one-tap en flick passe devant un double kill ordinaire et
  décroche la drop. Un flick est aussi un plan fort : il reçoit le ralenti, sinon il passe trop vite pour être vu.
- **Whip pan dans le sens du flick** (`whip`) : la mesure du flick donne aussi son sens (gauche, droite, haut, bas).
  Une coupe qui suit un plan fini sur un flick, ou qui précède un plan qui s'ouvre sur un flick, devient un whip pan :
  le plan sortant file dans le sens où la vue tournait, flouté par la vitesse, et le suivant arrive en continuant le
  même mouvement (200 ms en tout, `duration` ; flou `blur`). Le whip remplace le flash sur cette coupe ; sans flick de
  part et d'autre, la coupe reste franche. Désactivable par une case de la fenêtre de montage ou `--no-whip`.
  Un souffle (whoosh, celui des highlights story) culmine sur la coupe (`whoosh`, volume 0,5 ; 0, `--no-whoosh` ou la
  case « Souffle sur les whip pans » : whip pan muet). Il suit la musique dans `audio.balance` (±6 dB par cran), pour
  rester sous elle. Un dossier de sons (`whip.sounds`, ou `--whoosh-dir DOSSIER`) remplace les souffles générés,
  chacun à son tour dans un ordre mélangé mais toujours le même, comme `story.sfx.whooshDir`. Le rapport JSON indique
  pour chaque plan si sa coupe d'entrée est un whip pan (`whip`) et le souffle entendu (`whoosh` : fichier ou
  « généré N »).
- **Flou de mouvement** (`motionBlur`), mesuré sur les montages de référence (flou sur 48 à 93 % des coupes, une ou
  deux images à 30 img/s) :
  - Mélange d'images (`blend`, désactivé par défaut) : chaque image mélange celles des 17 ms précédentes. Dans un FPS
    la caméra bouge presque toujours un peu : il dédoublait légèrement presque chaque image (-17 % de netteté sur un
    montage, jugé moins net). Les vecteurs le remplacent, et ne floutent qu'au-delà de 10 pixels de glissement par
    image (-10 % de netteté à 4 pixels).
  - Flou par vecteurs de mouvement (`vectors`) : avant le rendu, chaque plan est décodé en tout petit et la rotation
    de la caméra mesurée image par image (dans un FPS, tout le décor glisse d'un bloc). Chaque image est alors floutée
    dans le sens du glissement, sur la longueur parcourue entre deux images (plafond `maxLength`, 8 % de la largeur) :
    une traînée continue pendant les flicks, comme une vraie caméra, ralentis et rampes compris. Calculer les images
    intermédiaires (`minterpolate`) donnait le même effet en 180 fois le temps réel ; la mesure coûte ici environ 40 s
    sur un montage d'une minute. Montage de référence : 13 % d'images floues, contre 13 à 17 % pour les références
    (6 % sans les vecteurs).
  - Coupes : 50 ms de flou radial de part et d'autre de chaque coupe franche (`cuts`, `cutDuration`), le plus fort sur
    la coupe, l'image et sept copies agrandies jusqu'à 8 % (`cutZoom`). Pas sur les coupes en whip pan, déjà floutées.
    Sur un montage de 36 coupes : 31 floutées au lieu de 11.
  - Lisibilité du kill : de 0,1 s avant à 0,25 s après chaque kill, la traînée est réduite au quart (`clearBefore`,
    `clearAfter`, `clearStrength`) et aucune coupe n'y est floutée ; le flou d'une coupe ne prend pas plus de 8 % du
    plan de chaque côté (`cutShare`). La rotation de la caméra n'est mesurée que sur le décor (au-dessus de l'arme) :
    mains d'une capacité ou écran de mort passaient pour un mouvement. Sur un montage au rythme rapide de 74 kills
    (R2D2), les images floues juste après le kill passent de 40 % à 18 %, et de 19 % à 10 % sur tout le montage.
  - La longueur de chaque plan ne bouge pas d'une image : les kills restent sur leurs temps. Activé par défaut ; case
    « Flou de mouvement » de la fenêtre de montage, ou `--no-motion-blur`.
- **Couleurs boostées** (`colors`) : le jeu « ressort » comme dans les kill montages de TikTok, couleurs vives et noirs
  francs. Vibrance d'abord (elle pousse les couleurs ternes sans brûler le rouge d'un skin), puis saturation, contraste
  et luminosité, sur chaque plan avant flashs et textes. Mesuré sur quatre montages VALORANT de référence : 12 à 38 %
  de pixels très saturés et 3 à 8 % de noirs, contre 7,5 % et 1,7 % sans étalonnage ; les valeurs par défaut
  (`vibrance` 0,5, `saturation` 1,2, `contrast` 1,12, `brightness` -0,02) donnent 38 % et 5,6 %. `vignette` et `lut`
  (fichier .cube) en plus si on veut. Désactivé par défaut (`enabled: true` dans le profil pour l'avoir toujours) ;
  case « Couleurs boostées » de la fenêtre de montage, ou `--colors boost` / `--colors natural`.
- **Raccords sur la pose de l'arme** (`matchCut`) : la même pose de l'arme de part et d'autre d'une coupe, l'arme reste
  en place et seul le décor change. Deux poses selon le jeu (`pose`) :
  - `aim` (défaut, WARDOGS) : la visée. Le plan sortant s'arrête avant que le joueur ne baisse son arme, l'entrant
    commence quand il a déjà épaulé, le viseur reste au centre. La visée dure tant que le centre de l'écran (`region`)
    ressemble à ce qu'il était juste avant le kill (`minSimilarity`), sur ses pixels immobiles (`stillShare`), et que
    l'arme descend au centre, symétrique (`minSymmetry`) : un kill à la hanche, une arme inclinée ne comptent pas.
  - `rest` (VALORANT) : l'arme au repos, à la hanche. Au kill, elle tremble sous le recul : le plan commence et finit
    dans le repos le plus proche, là où la zone de l'arme ressemble à son image médiane. Le repos doit ressembler au
    moment du tir (sinon l'arme n'était pas en main : capacité, changement d'arme).

  Arme en main (`weapon`, facultatif) : un repère du HUD qui n'apparaît qu'avec une arme à feu (VALORANT : l'icône du
  chargeur, `config/templates/valorant/ammo_icon.png`). Une image sans lui ne compte pas dans la pose : une capacité
  ou un couteau tenus au même endroit qu'une arme ne se raccordent plus.

  Les deux poses doivent se ressembler (`minPoseMatch` : même arme tenue de la même façon ; -1 en visée, où le viseur au
  centre suffit). Les portions déplacées sont ralenties (jusqu'à ×0,3, `minSpeed`) ou un peu accélérées pour garder
  la durée de leur slot : les kills restent sur leur temps. Sans ralenti, elles ne changent pas plus de vitesse qu'une
  rampe (±15 %) ; sans ralenti ni rampes, seuls restent les raccords qui ne changent aucune vitesse. La pose est mesurée avant la planification : les
  groupes d'importance voisine échangent leurs places pour mettre côte à côte ceux qui se raccordent (hors drop et
  accroche ; un échange à la fois, ou deux quand aucun n'aide seul), et un kill peut changer de temps dans son plan pour qu'il reste moins à ralentir, si la note du montage
  n'y perd pas plus que les raccords gagnés ne valent. Une coupe raccordée n'a ni flash ni whip. Le journal donne, coupe
  par coupe, les fenêtres de pose, la ressemblance des poses et la vitesse appliquée. Désactivable par une case de la
  fenêtre de montage ou `--no-match-cut`.
- **Kills dans leur round** (`killStyle`, avec les morts d'Outplayed) : un kill suivi de sa propre mort dans les 3 s
  recule (`deathPenalty`) ; le groupe qui finit un round de 5 kills monte nettement (**ace**, `aceBonus`) ; celui qui
  finit un round survécu sur au moins deux kills monte aussi (**clutch**, `clutchBonus`). Le jeu ne donne ni les rounds
  ni le nombre d'alliés en vie : une mort clôt le round du joueur, un silence de plus de 40 s aussi (`roundGap`, plus
  court que la phase d'achat), et le clutch reste une approximation. En VALORANT, le clutch est celui que le jeu annonce
  (Outplayed) ; sans Outplayed, il est **réel** : les morts des deux équipes sont lues dans le killfeed (détecteur
  `killfeed-teams` : la moitié droite de chaque ligne a la couleur de l'équipe de la victime, sarcelle pour celle du
  joueur, rouge pour l'adversaire). Le joueur, resté seul de son équipe face à au moins un adversaire, gagne le round
  en les éliminant (`TeamRounds`) : un 1 contre 1 gagné compte (`clutchMinEnemies: 2` pour suivre le bandeau « CLUTCH »
  du jeu, qui n'apparaît qu'à partir d'un 1 contre 2). Un round
  gagné au spike (désamorcé, explosé) ne se voit pas dans le killfeed : le bandeau « CLUTCH » de fin de round, lu au
  centre de l'écran (gabarit du mot, `templates/valorant/banner_clutch.png`), le rattrape. Gris pour les deux équipes,
  il est rattaché comme un clutch annoncé : au groupe du joueur dont le dernier kill le précède sans mort entre les
  deux (le clutch d'un coéquipier, joueur mort, n'est pas le sien). Un 1 contre 1 gagné au spike reste manqué (pas de
  bandeau). Les rounds sont séparés
  par l'élimination d'une équipe, un silence de 40 s, et surtout le retour des cinq barres de vie alliées pleines en haut
  de l'écran (`round-start`) : dans une vidéo montée, phases d'achat coupées, deux rounds se suivent à 10 s. Mesuré sur
  deux parties en 854x480 (`output/revue/clutch-reel/`, vérité `verite/valorant/`) : morts alliées précision 0,92,
  rappel 0,97 (67 sur 69, le joueur compris) ; clutchs : 2 trouvés pour 2 réels (deux 1 contre 1, aucun 1 contre 2
  dans ces parties), aucun inventé, ni à partir des clutchs de coéquipiers. Pas vérifié en 1080p ni 1440p, faute de capture. Une mort sépare toujours deux kills rapprochés en
  deux clips. Sans aucune mort annoncée (`deathEvent` vide, ou jeu sans Outplayed ni killfeed lu), rien de tout ça ne
  s'applique.
  Désactivable par une case de la fenêtre de montage ou `--no-rounds`.
- **Kills à la capacité écartés** (`skipAbilityKills`, activé dans le profil VALORANT) : un kill fait avec une molly,
  une flèche, un drone… ne montre ni le tir ni la cible sous le réticule. Il est reconnu à ce que le compteur de
  munitions (détecteur `ammo-counter`) ne voit **aucune balle** de 1 s avant le kill à 0,3 s après ; un couteau aussi.
  Sans compteur lu, rien n'est écarté (le son seul se trompait sur 1 kill au fusil sur 61). Mesuré sur 61 kills au fusil
  de deux parties 854x480 : aucun écarté ; aucune de ces parties n'a de kill à la capacité du joueur, ce qui est
  vraiment reconnu reste donc à mesurer. Les kills restent dans la session, les statistiques et la recherche ; un moment
  choisi dans la recherche, ou un kill forcé ou épinglé dans la frise, est monté quand même. Un multi-kill garde son
  importance et ses autres kills. Case « Écarter les kills à la capacité » de la fenêtre de montage (profils qui lisent
  le compteur), `--ability-kills` pour les garder, `--skip-ability-kills` pour les écarter.
- **Variantes** (`variants`) : une douzaine de plans sont calculés (échelle de la grille, place de la drop) et seul le
  mieux noté est rendu, à condition de garder presque tous les clips du plan de base et de le battre nettement.
- **Accroche** : le meilleur groupe après celui de la drop ouvre le montage (`--no-hook`) : c'est dans les premières
  secondes que le spectateur décide de rester.
- **Musique depuis le début** (`cuts.fromStart`, `--from-start`, case de la fenêtre de montage retenue pour chaque
  musique dans `~/.highlights/music.json`) : le montage part du premier temps de la musique au lieu du passage le plus
  intense autour de la drop. Pour les musiques qu'on reconnaît à leur intro. Une musique peut avoir plusieurs drops
  (Spitfire : 0:23 et 4:15) : la drop retenue est alors la plus forte de celles qui tombent dans le montage.
- **Une emphase par plan** (`effectDensity`, `--effects sober|balanced|heavy`) : au rythme normal, un plan reçoit un
  ralenti *ou* un zoom, jamais les deux. Le ralenti va aux plans forts — drop, multi-kill visible à l'écran, accroche —
  et les autres prennent le zoom. `sober` ne garde que le ralenti de la drop, `heavy` remet tout partout.
- **Compteur de kills** (désactivé par défaut ; « Compteur de kills à l'écran » du dialogue, `--kill-counter clip|montage`,
  `text.killCounter` d'un profil) : « 1… 2… 3… » à chaque kill, tenu jusqu'au suivant, puis ACE (doré, sous le nombre)
  sur le dernier kill d'un groupe qui finit un ace. `clip` : compté dans chaque plan (un multi-kill fait 1, 2, 3) ;
  `montage` : cumul sur tout le montage, le total restant affiché d'un plan à l'autre. En 9:16, en haut à droite à
  côté du kill feed recollé (à gauche si le HUD recollé du profil occupe ce coin), dans la zone sûre de la plateforme.
- **Bandeau du score des rounds** (VALORANT, désactivé par défaut ; case « Bandeau du score des rounds » du dialogue,
  `--round-score`, `text.roundScore` d'un profil) : un petit cartouche « ROUND 13 » au-dessus de « 7 – 5 » (équipe du
  joueur en sarcelle, adversaire en rouge), tenu tout le plan. C'est le score au moment du kill, donc du round que le
  kill joue : le HUD ne passe au score suivant que quelques secondes après le kill qui gagne le round. Le score est lu
  dans la barre du haut du jeu par le détecteur `round-score` (avec ou sans Outplayed, qui ne le transmet pas) : chaque
  nombre de 0 à 30 est composé à partir des chiffres (`templates/valorant/score/`) et comparé à toute la zone de chaque
  côté du chrono, puis les deux côtés sont décodés ensemble (Viterbi : un round à la fois, d'un seul côté), ce qui
  corrige les lectures ratées sur un ciel clair, un flash ou l'écran de fin. Il émet un `round-won` ou `round-lost` par
  round. Mesuré sur deux parties 854x480 (`output/revue/bandeau-score/`) : 98 à 99 % des images lues d'un côté disent le
  score décodé, 48 changements de score sur 48 justes (vérifiés à l'œil avant et après chacun), aucun faux, fins 13-10
  et 12-13 comme les tableaux de fin ; mêmes résultats avec une image toutes les 2 s. 2 img/s en décodage logiciel (images clés si elles tombent chaque seconde).
  Non vérifié en 1080p ni 1440p. En 9:16, centré en haut sous la barre du HUD recollée, à 10 % du haut au moins et dans
  la zone sûre, entre le compteur de kills (coin) et les libellés DOUBLÉ/TRIPLÉ (25 %) ; dans les autres formats, posé
  sur le chrono du jeu. Rien pour une capture sans score lu ni pour l'ouverture vitrine.
- **Effets** (désactivables : `--no-zoom`, `--no-flash`, `--no-slowmo`, `--no-text`) : zoom punch, ralenti ×0,5 sur le
  kill (seulement s'il tient dans le plan), textes « DOUBLÉ / TRIPLÉ », fondu au noir final. Le ralenti s'installe par
  paliers avant le kill et le plein régime revient exactement sur un temps, au lieu d'un changement de vitesse net ;
  `slowMotion.interpolate` calcule de vraies images intermédiaires au lieu de répéter celles de la source (mouvement
  fluide, rendu bien plus lent). Le flash blanc ne tombe qu'aux coupes fortes — nouvelle section, drop, multi-kill
  (`--flash-every-cut` pour toutes) ; `zoom.onEveryKill: false` réserve le zoom au kill calé sur le temps.
- **Son** : musique au premier plan ; le jeu remonte sur les kills. C'est l'écran qui compte : le micro reste au niveau
  du jeu, sans rien décider du montage. Avec `--reactions` (`montage.reactions`, case « Mettre en avant les
  réactions » du dialogue), voix et rires sont montés, la musique baisse pendant qu'on les entend et le plan dure
  jusqu'à la fin de la phrase. Pendant un ralenti, le son du jeu n'est **pas** étiré avec l'image (le timbre d'un tir s'y
  déliterait) : il joue à sa vitesse puis s'efface, la musique porte la fin du plan (`audio.slowMotion` : `natural` par
  défaut, sinon `stretch` ou `mute`). Tous ces changements de volume montent et descendent en fondu (`audio.duckAttack`,
  `audio.duckRelease`), sinon la marche s'entend plus que ce qu'elle met en avant ; le son d'un kill (ou d'une phrase,
  avec `--reactions`) déborde un peu sur le plan suivant (`audio.bleed`). L'équilibre jeu / musique se règle dans le dialogue du montage
  (`audio.balance`, `--balance`, de -1 à 1 : ±6 dB par cran, jeu et musique en sens opposés). En mode « kills
  seulement » (`audio.game: kills`, `--game-audio kills`), on n'entend du jeu que le son du kill — tir, notification —
  et la musique baisse dessous pour le laisser passer (`audio.musicUnderKill`).
- **Note du montage** : chaque rapport porte une note qui mesure ce que le moteur prétend faire — kills sur un temps,
  temps accentués, sobriété des effets, variété des clips, durée occupée, plans plus courts dans les sections intenses,
  absence d'image gelée, et trois mesures des temps morts : délai avant le premier kill (sans reproche jusqu'à 2 s,
  nul à 6 s), plus long passage sans kill (4 s, puis nul à 10 s) et part de chaque plan passée hors de l'action (loin
  de tout kill et de toute réaction gardée). Ces mesures pèsent aussi dans le choix entre les variantes du plan. Elle
  ne dit pas si un montage est beau ; elle sert à comparer deux versions du moteur sans les
  regarder l'une après l'autre. Un critère qu'on ne peut pas mesurer sur un montage donné (`-` à l'affichage) sort de la
  moyenne au lieu d'y entrer à 1.

  ```powershell
  & $app score output\a\partie_killmontage.json output\b\partie_killmontage.json
  ```

- Réglages détaillés : section `montage:` d'un profil (voir `core/.../model/Montage.kt`), notamment `cuts:` (durées
  visées par intensité `low`/`mid`/`high`, `maxBeats`, `minLead`/`minTail`, `dropPosition`, `dropLead`).

### Corriger le plan : écarter, forcer, épingler un kill

Quand le planificateur se trompe sur un kill (un faux kill, le mauvais sur la drop, un beau kill écarté), on corrige le
plan au lieu de tout relancer à l'aveugle.

- **Dans l'application** : depuis le 20/11, l'étape 3b du parcours (voir plus haut) ; la frise décrite ici n'existe plus.
  Elle montrait la musique (volume de
  chaque temps, sections, drop) et les plans placés dessus, une vignette par groupe de kills, chaque kill en trait ; les
  groupes que le plan écarte sont listés dessous. Clic sur un plan : l'écarter ; clic sur un trait : ouvrir le
  **calage du kill à l'image** (voir plus bas, et de là l'écarter seul) ; clic sur un groupe écarté : le forcer dans le montage ; glisser un groupe sur la drop : l'y épingler ; un
  nouveau clic annule. Glisser un groupe (placé ou écarté) sur **n'importe quel temps** de la frise l'y épingle : un
  trait blanc et l'instant montrent le temps visé pendant le glisser, le plan est recalculé au lâcher et le kill tombe
  sur ce temps (la tête ronde marque l'épingle). Chaque épingle est rappelée sous la frise ; un clic dessus la retire et
  replanifie. Une épingle impossible (deux groupes sur le même temps, moment de la musique hors du montage) est dite en
  rouge, le dernier plan reste affiché. Chaque correction (écarter, forcer, drop, épingle, calage, tout annuler)
  recalcule le plan aussitôt, sans réanalyser (inspection des kills en cache : quelques secondes) ; **Replanifier**
  reste pour relancer à la main. « Créer le montage » rend le plan corrigé.
- **En ligne de commande** : `--plan` affiche le plan sans rendre, avec la désignation de chaque kill
  (`capture@instant`, à une seconde près) ; `--exclude`, `--force` (répétables), `--drop` et `--pin capture@instant=0:42`
  (le kill tombe sur le temps du morceau le plus proche) le corrigent.

  ```powershell
  & $app montage partie.session.json --music son.mp3 --plan
  & $app montage partie.session.json --music son.mp3 --exclude partie.mp4@40:00.6 --drop partie.mp4@38:36.3
  ```

**Caler un kill à l'image.** La détection (Outplayed, killfeed, compteur de munitions, son du tir) se trompe parfois de
quelques images, et le temps détecté d'une musique peut lui-même être un peu décalé. Clic sur le trait d'un kill dans
la frise : toutes les images de la capture une demi-seconde avant et après le kill (instants exacts lus par FFmpeg,
`showinfo`), l'image choisie entière et en grand, ← → pour avancer d'une image, Entrée pour appliquer.
L'image choisie devient l'instant qui tombe sur le temps (`MontageConstraints.anchors`, refusé à plus d'une seconde de la
détection). « Kill par rapport au temps » pose le kill une ou plusieurs images après (+) ou avant (−) son temps quand
c'est le temps qui est décalé (`beatShifts`, ±250 ms au plus) : les coupes restent sur les temps, chaque plan garde sa
longueur. Le plan est recalculé aussitôt ; un kill calé à la main a un trait orange, un kill déplacé par un décalage
appris un trait blanc. En ligne de commande : `--anchor capture@instant=3:12.483` et `--beat-shift capture@instant=+17ms`
(répétables ; la désignation reste l'instant détecté, celui que `--plan` affiche).

**Kill ici, à l'écoute.** Le même panneau a aussi l'ergonomie de l'outil de la drop : le son du jeu ±2 s autour de la
détection (forme d'onde, images de la bande en repères, détection en gris, hors de ±1 s assombri), un curseur qu'on place
d'un clic ou d'un glisser, par image (← →) ou par 10 ms (Maj+← →), ou en tapant l'instant (`m:ss.mmm`).
**Écouter** (Espace) et **Depuis 1 s avant** (R, arrêt 1 s après le curseur) jouent l'image et le son de la capture dans
le panneau (lecteur intégré) ; **Marquer ici** (M) pose le curseur sur l'image montrée ; **Kill ici** (Entrée) pose le
calage (même contrainte `anchors` que le choix d'une image, donc même apprentissage par source) et recalcule le plan.

**Repères.** Le même curseur pose d'autres repères sur la capture (« Repères : » sous la forme d'onde), chacun
contrainte du plan, recalculé aussitôt, visible sous la frise (« Repères (clic : retirer) ») :
- **Le plan commence ici** / **Le plan finit ici** (« Couper ici ») : bornes du plan du kill dans la capture. Le plan garde
  sa longueur : la partie d'avant ou d'après le kill est ralentie ou accélérée pour remplir sa place, ×2 au plus ; une zone
  trop courte est refusée avec un message qui dit combien il faut (jamais de coupe silencieuse). Pas de coupe dans
  l'attente ni de passage vitrine autour d'un plan qui a des repères.
- **Début de section ici**, puis **Fin ici : section sans kill** : passage jamais montré (un temps mort, un menu) ; un kill
  détecté dedans est écarté, et un plan qui le recouvrirait commence après ou finit avant.
- **Fin ici : section à garder entière** : un plan qui en montrerait une partie la montre toute (sa borne va au bord de
  la section, partie accélérée ×2 au plus).

En ligne de commande : `--cut capture@instant=31:40.2..31:42.9` (« =31:40.2.. » ou « =..31:42.9 » pour un seul bord),
`--skip capture@1:02.5..1:10`, `--keep capture@1:02.5..1:04` (répétables). Les coupes posées s'enregistrent
(`<sortie>\sessions\kill-cuts.json`) : à partir de 8 coupes concordantes d'un profil (écart médian ≤ 0,3 s), la longueur
préférée avant et après le kill oriente la place du kill dans ses plans (`app gouts` la montre, `app gouts --reset-cuts` et
le bouton « Oublier les longueurs apprises » l'oublient).

  ```powershell
  & $app montage partie.session.json --music son.mp3 --anchor partie.mp4@31:41.392=31:41.309 --beat-shift partie.mp4@31:41.392=+17ms
  ```

**Calages appris.** Chaque montage rendu avec des kills calés note, par kill, sa source de détection (« détecteur/recalage » :
`game-events/ammo-counter`, `killfeed/kill-confirm`, `…/shot-sound`, `…/event`), l'écart choisi, la musique et le temps
(`<sortie>\sessions\kill-timing.json` ; un kill recalé remplace sa correction précédente). Deux choses s'apprennent
(montage.learnTaste) : le retard d'une source par profil (8 calages au moins, écart à la médiane ≤ 40 ms, médiane ≥ 10 ms,
bornée à ±200 ms) appliqué aux kills de cette source ; et le décalage des temps d'un morceau (3 calages concordants à
30 ms près, borné à ±120 ms) appliqué à tous ses plans. Un kill calé à la main n'est jamais déplacé par ce qui est appris.
Vue « Goûts appris » : carte « Calage des kills » et « Oublier les calages appris » ; `app gouts` les liste,
`app gouts --reset-timing` les oublie (fichier mis de côté, pas effacé).

**Montage sur la musique.** L'étape 3 du parcours du montage (la même fenêtre). Deux temps :
1. **Vos intentions sur la musique** : le morceau entier (forme d'onde, sections, drop glissable) et des **repères**,
   sans dire quel kill : ◆ **Kill ici**, ◆◆ **Multi-kill ici** (un repère qui vaut plusieurs kills), ┃ **Coupe ici**
   (un plan commence là), ◐ **Ralenti ici**, ↯ **Transition ici** (whip pan sur la coupe la plus proche), ▭ **Vitrine
   ici** (une plage de passages de partie sans kill). L'algorithme en propose au départ, tirés du placement automatique
   (losanges creux) ; on choisit un outil et on double-clique pour en poser (aimant sur les notes et les temps, Alt :
   à l'instant exact), on les glisse, on les retire ; ceux qu'on touche deviennent « à vous » (losanges pleins).
   Le compteur « Kills disponibles : N · repères « kill » : M » reste visible. Avec plus de repères que de kills, un
   avertissement doux entoure les repères en trop (ceux où la musique frappe le moins) : ils deviennent des **plans de
   vitrine**, ou restent vides (**Les laisser vides**). Des repères proches sont permis. **Tout remettre en auto**
   revient à la proposition. En ligne de commande : `--mark kill@0:31.200` (et `multi@`, `cut@`, `slow@`,
   `transition@`), `--showcase-at 0:22.900..0:24.810`, `--extra-marks showcase|empty`.
2. **Placer les plans →** : l'algorithme choisit **quel kill va sur quel repère** (`IntentLayout`) : les multi-kills
   sur les repères « multi-kill » et les repères rapprochés (à un temps ou moins), les meilleurs kills sur la drop et
   les repères qui frappent le plus. Puis il construit les plans autour (`KillLayout`) : chaque kill exactement sur son
   repère, un début de plan à chaque coupe, la vitrine sur sa plage et sur les repères en trop, des ralentis et des
   transitions **seulement** là où sont leurs repères. Deux kills à moins d'un temps : un plan court pour le second
   (la coupe 50 ms avant lui), ou un seul plan s'ils viennent de la même capture et que leur écart réel correspond à
   150 ms près. Jamais d'erreur.
   L'étape montre les plans et les repères numérotés. **Glisser un kill** sur un autre les échange, sur un repère l'y
   met ; un kill de la liste glissé sur un repère y va ; **Retirer** écarte le kill et le repère prend le meilleur
   suivant (`--put 3=capture@7:00.000` en ligne de commande). **← Revenir aux repères** rouvre l'étape 1 ; **Aperçu du
   montage (360p)**, **Rendre le montage**. L'ancienne pose de kills nommés reste en ligne de commande :
   `--kill-at capture@kill=0:31.200`.
Le réarrangement des plans à la main (bornes, ordre, longueur) est la prochaine étape.

**Apprendre d'où vous posez vos kills.** Chaque **Placer les plans** enregistre la proposition automatique et vos
repères finaux (`<sortie>/sessions/kill-layout.json`) : repères « kill » déplacés (d'où vers où), ajoutés, retirés,
coupes, ralentis, transitions et vitrine, et le contexte musical de chaque instant (temps le plus proche et écart en ms, contretemps, attaque de note, force du temps,
section, avant ou après la drop, kills dans la mesure). Après au moins 3 montages qui concordent (chaque goût vérifié
« une de côté » : appris sans un montage, prédit-il ce montage ?), la proposition automatique de l'étape 1 suit :
l'écart préféré au temps (entre 10 et 120 ms, dispersion ≤ 30 ms), la préférence pour les temps qui frappent, et la
densité (plus serré ou plus espacé, ×0,7 à ×1,5 sur la durée visée par kill). Jamais par-dessus un kill posé à la
main. Les kills retirés et ajoutés nourrissent les goûts par sorte de kill (multi-kill, tête, one tap…). Contretemps
et kills après la drop sont montrés, pas encore appliqués. **Goûts appris** le dit en clair (« Vous posez vos kills
~25 ms après le temps : appliqué ») ; **Oublier…** (ou `app gouts --reset-layout`) met le fichier de côté. Démo à goût
caché (`LayoutTasteTest`) : un goût net ou flou (le temps fort 6 fois sur 10, ±15 ms) est appliqué dès le 3e montage ;
sans goût ou au hasard, rien ne s'applique.

**Kill sur ce moment de la musique.** Pour réarranger les kills sur la musique : clic sur le trait d'un kill dans la
frise, puis **Sur la musique…** : l'outil d'écoute du morceau (le même que pour la drop : forme d'onde, son, curseur
qu'on glisse en écoutant, `m:ss.mmm`, ±10 ms, ±1 temps, « Depuis 2 s avant »), le curseur là où le kill tombe
aujourd'hui. **Poser le kill ici** (Entrée) l'épingle à cet instant du morceau : il y tombe **exactement**, même entre
deux temps (le temps le plus proche porte son plan, le kill est décalé de l'écart, les coupes restent sur les temps) ;
les autres kills sont replacés autour. **Revenir au placement auto** retire l'épingle (comme un clic sur sa puce). En
ligne de commande : `--pin partie.mp4@52:38.600=0:44.335`. Deux kills au même instant (au même temps) : refusé, comme
avant. Chaque montage rendu note où vous posez vos kills (`<sortie>\sessions\kill-pins.json` : écart au temps, force du
temps et de ceux de son plan) ; à partir de 8 kills, deux choses s'apprennent, chacune seulement si la validation « une
de côté » la confirme : un **écart régulier au temps** (« juste avant le temps », dispersion ≤ 30 ms, 10 à 120 ms),
appliqué aux morceaux sans écart à eux, et une **préférence pour les temps qui frappent**, qui oriente le placement
automatique. « Goûts appris » (carte « Calage des kills ») et `app gouts` les montrent ; `app gouts --reset-pins` les
oublie.

**Poser la drop à l'oreille.** La drop détectée peut tomber à côté de celle qu'on entend (« Rome Is Burning » : détectée
à 0:31.474, alors que la basse entre après un silence à 0:30.523, deux temps plus tôt). Clic sur la marque **DROP** de la
frise : la forme d'onde du morceau entier et celle des 6 s autour du curseur (temps détectés en repères, drop détectée en
gris), le son du morceau, un curseur qu'on place d'un clic ou d'un glisser, au clavier (← → 10 ms, Maj+← → un temps) ou
en tapant l'instant (`m:ss.mmm`). **Écouter** (Espace) joue depuis le curseur, **Depuis 2 s avant** (R) rejoue la montée
et la drop, **Marquer ici** (M) pose le curseur sur ce qu'on entend pendant l'écoute, **La drop est ici** (Entrée) la
retient pour ce morceau (`<sortie>\sessions\music-drops.json`, clé nom + taille comme le ressenti) et recalcule le plan.
Elle remplace la drop détectée à tous les montages suivants sur ce morceau, « depuis le début » compris ; la marque
devient « DROP (posée à la main) ». L'instant devient un temps : le temps détecté le plus proche y est amené et ses
voisins suivent en s'atténuant sur 8 temps (la grille est recalée là, pas ailleurs), puis une section commence sur lui :
le kill de la drop et les coupes autour restent ensemble sur ce qu'on entend. **Revenir à la détection** l'oublie.
Vue « Goûts appris » : carte « Drops posées à la main », une ligne par morceau avec **Oublier**. En ligne de commande :
`--drop-at 0:30.523` (retenue comme dans l'application ; `--drop-at auto` l'oublie), `app gouts` les liste,
`app gouts --reset-drops` les oublie toutes (fichier mis de côté).

  ```powershell
  & $app montage partie.session.json --music "Rome Is Burning.mp3" --drop-at 0:30.523
  ```

**Drops apprises.** Chaque drop posée garde où la détection la mettait, où vous l'avez posée et les mesures de chaque
temps du morceau (`<sortie>\sessions\drop-learning.json`, une par morceau). Deux choses s'apprennent (montage.learnTaste),
pour les morceaux dont vous n'avez jamais posé la drop :
- un **écart régulier** de la détection (par exemple toujours deux temps trop tard) : appliqué à partir de 5 morceaux
  dont 80 % ont exactement le même écart en temps, le reste en ms à 30 ms près (borné à 4 mesures et ±120 ms) ;
- un **choix de la drop réappris** : une note par temps (montée du volume, creux juste avant, attaque, saillance, début
  de section et son intensité, premier temps de mesure, drop de la règle actuelle), dont les poids sont réappris pour
  rendre vos drops les plus probables. Il ne sert qu'à partir de 6 morceaux, et seulement s'il retrouve, en validation
  « une de côté » (appris sans le morceau, testé dessus), plus de vos drops que la règle actuelle.

Une drop posée à la main n'est jamais remplacée par ce qui est appris ; une drop apprise est marquée « DROP (apprise) »
dans la frise. Vue « Goûts appris » : nombre de drops posées, écart moyen, état des deux apprentissages et
**Oublier ce qui est appris des drops** (fichier mis de côté, les drops posées restent) ; `app gouts` les montre,
`app gouts --reset-drop-learning` les oublie.

- Un kill exclu sort de son groupe (un triplé devient un doublé) ; un groupe forcé prend la place du moins bon groupe
  que rien n'impose ; épinglé sur la drop, il y tombe (en ordre chronologique, les moins bons groupes joués avant lui
  cèdent leur place). Une correction impossible (kill introuvable, plus de place, pas de drop dans le passage retenu,
  temps hors du montage) arrête le plan avec un message qui nomme le kill : jamais un montage qui l'ignore en silence.

## Récap de soirée

Une vidéo highlights de toute une soirée, partie par partie : avant chaque partie, un intercalaire d'une seconde avec un
impact sonore (ceux du style story, au volume du reste)
(« PARTIE 3 – 14 KILLS », le jeu et l'heure, sur une image floutée de la partie), puis ses meilleurs moments ; à la fin,
le bilan, 2 s sur un souffle (« SOIRÉE DU 16/11 », « 47 KILLS · MEILLEURE PARTIE : N°3 (14) »). La soirée est celle des statistiques (de 6 h
à 6 h, un jeu à la fois) ; les moments de chaque partie sont recalculés avec une cible de durée partagée sur la soirée
(moments retouchés à la main gardés), et rendus par l'export habituel (style simple ou story, formats, plateforme). Une
partie sans moment n'a pas d'intercalaire mais garde son numéro. Dans l'application : « Récap de la soirée » sur chaque
soirée des statistiques (passe par la file des traitements). En ligne de commande :

```powershell
& $app recap --date 2026-09-22 --game wardogs --style story --platform tiktok
& $app recap output\sessions\a.session.json output\sessions\b.session.json   # ces parties-là, telles quelles
```

Sortie : `<jeu>_<date>_recap[_9x16].mp4` et un rapport JSON (parties montrées, kills, début de chaque carton). La carte
ou le mode d'une partie ne sont pas connus des analyses : l'intercalaire dit le jeu et l'heure.

## Clip « meilleur moment » (9:16)

Un seul moment en vidéo verticale de 15 à 30 s, prête pour TikTok ou Shorts, sans musique imposée : l'action d'origine,
le son du jeu et la voix. Bouton **Clip 9:16** sur le moment sélectionné, ou :

```powershell
& $app clip output\sessions\partie.session.json                       # le meilleur moment (le plus de kills, puis le score)
& $app clip output\sessions\partie.session.json --moment h003 --platform tiktok
```

- Monté en style story sur ce seul moment : accroche (le kill en ouverture), attente coupée en jump cuts, punch-in et
  secousse sur le kill, sous-titres de la voix si le modèle whisper est là, bruitages.
- Un moment détecté ne dure que 5 à 7 s : le clip prend la partie autour (au plus 6 s après le kill, le reste avant) et
  l'élargit jusqu'à ce que le montage, attente coupée, dure entre 15 et 30 s (22 s visées). Une partie où presque
  tout est de l'attente peut aller chercher 3 minutes avant le kill. Réglages : `edit.clip` du profil (`minDuration`,
  `maxDuration`, `target`, `lead`, `after`).
- `--platform` (ou la plateforme choisie dans l'application) : zone sûre, volume, débit, et sa durée maximale plafonne
  le clip. Fichier `<jeu>_<date>_clip_<moment>[_plateforme]_9x16.mp4`.

## Clips courts (FTHRClips, Medal, Instant Replay)

Beaucoup de joueurs ne gardent que des clips de 30 s à 2 min, sauvegardés d'une touche, au lieu de parties entières.
Highlights les traite comme une soirée de jeu :

- **Heure du clip lue dans son nom** : FTHRClips (`VALORANT_clip_from_22Nov2026_21-05-33.mp4`, format lu dans son code
  source, `FTHR_UI/main.py`), Medal (`MedalTVVALORANT20261122210533.mp4`, forme relevée sur des clips publiés, pas
  documentée) et Instant Replay de ShadowPlay / NVIDIA App (`… 2026.11.22 - 21.05.33.02.DVR.mp4`). Ces logiciels datent
  le clip de sa **sauvegarde**, c'est-à-dire de sa fin : le clip commence cette heure moins sa durée. Les clips se
  rangent et se regroupent donc dans l'ordre où ils ont été joués. FTHR nomme aussi `FTHR_recording_2026-11-22_22-28-00.mp4`,
  sans le jeu : l'heure est alors celle du **début** (vérifié sur de vrais fichiers, d'un clip de 24 s à une partie de 5 h).
- **Clip ou partie** : un nom de Medal ou d'Instant Replay, ou une capture de moins de 5 minutes, est un clip. FTHR fait
  les deux : la durée seule décide. Son nom ne dit pas le jeu : choisir le profil (ou ranger les captures dans un dossier
  au nom du jeu), sinon c'est le profil par défaut.
- **Séance** : des clips d'un même jeu sauvegardés à moins de 45 min d'intervalle forment une séance. La liste des
  analyses les range en une ligne (« 12 clips · VALORANT », de 21:04 à 22:10, leurs kills), à cocher ou ouvrir d'un coup
  pour un seul montage, et à déplier pour voir chaque clip ; les statistiques montrent une ligne par séance et la
  comptent comme une partie dans les kills par partie.
- **Clips sauvegardés deux fois** : appuyer deux fois sur la touche à 20 s d'écart donne deux clips qui partagent 40 s
  de jeu. Les kills vus par les deux (même sorte, à 1,5 s près en heure réelle) ne comptent et ne se montent qu'une
  fois ; le second clip garde ce qu'il a de nouveau. Un clip pris pendant une partie entière enregistrée à côté (OBS,
  Outplayed) en est un morceau : la partie seule compte.
- **Montage et récap de soirée** : ils prennent les clips comme les parties. Le récap montre une séance de clips sous un
  seul carton (« CLIPS – 18 KILLS », le jeu et les heures de début et de fin), sans repasser un moment déjà montré par
  le clip précédent. Si la meilleure de la soirée est une séance de clips, l'écran de fin dit « meilleure séance ».

## Statistiques par soirée

Bouton **Statistiques** au-dessus des analyses enregistrées (ou `app stats`) : les parties analysées, un jeu à la
fois, soirée par soirée — kills par partie, K/D, part de tirs à la tête, meilleur round, doublés / triplés / aces /
clutchs, et un graphique des kills par partie d'une soirée à l'autre. Chaque partie se rouvre d'un clic.

Une même partie enregistrée deux fois (Outplayed et OBS en même temps, ou la même capture analysée deux fois) ne
compte qu'une fois. Deux captures qui se chevauchent ne sont fusionnées que si leurs horaires viennent de
l'enregistrement (date dans les métadonnées, ou nom daté par Outplayed, OBS, ShadowPlay, FTHRClips ou Medal) : des captures copiées ou
téléchargées n'ont que la date du fichier, et deux parties copiées à une minute d'écart restent deux parties.

```powershell
& $app stats                     # dix dernières soirées, jeu par jeu, et la tendance
& $app stats --game valorant --games --last 30
```

- Rien à relancer : tout vient des sessions déjà enregistrées. Rounds, multi-kills, aces et clutchs suivent les règles
  du montage kills (les chiffres concordent avec ce qu'il monte).
- **Clutchs** : en VALORANT, ceux que le jeu annonce (via Outplayed), et non plus déduits (la déduction en comptait 3
  pour 1 réel). Une partie analysée avant ce réglage a ses clutchs « inconnus » : **Mettre à jour les analyses** (ou
  `app stats --refresh`) la réanalyse, en quelques secondes. Une capture OBS, lue dans le killfeed, a ses clutchs réels,
  comptés sur les morts des deux équipes (voir « Kills dans leur round ») ; analysée avant, elle les a inconnus.
- Une **soirée** va jusqu'à 6 h du matin : la partie de 1 h compte pour la veille. Deux jeux ne se mélangent pas.
- Une capture de moins de 5 minutes est un **clip** (voir « Clips courts ») : ses kills comptent, dans la séance de
  ses voisins. La même partie enregistrée deux fois (deux captures entières qui se chevauchent de plus de moitié) ne
  compte qu'une, la plus riche en kills ; un clip pris pendant une partie enregistrée compte avec elle.
- Morts et tirs à la tête ne comptent que là où ils sont vus : un profil qui ne détecte pas les morts (Wardogs) n'a ni
  K/D ni rounds, et une partie dont les kills viennent du killfeed de secours (qui ne voit pas les tirs à la tête) est
  laissée hors du taux de headshots au lieu d'y compter pour 0 %.

## Rechercher des moments

Bouton **Rechercher** au-dessus des analyses enregistrées (ou `app search`) : tous les moments (groupes de kills,
comme le montage kills les forme) des parties analysées, filtrés par jeu, période (7 jours, 30 jours, tout), taille
(doublés et plus, triplés et plus), aces, clutchs, « tout en headshot » ou « one taps ». On coche ceux qu'on veut, puis **Montage
kills de N moment(s)** ouvre leurs parties et le dialogue du montage, limité à ces moments.

```powershell
& $app search --clutch                                  # tous les clutchs, du plus fort au moins fort
& $app search --min-kills 2 --headshots --last 7        # doublés et plus, tout en headshot, 7 dernières soirées
& $app search --min-kills 3 --montage D:\Musique --platform tiktok   # montage des triplés et plus
& $app search --onetaps --montage D:\Musique --platform tiktok       # montage onetaps
```

- L'arme n'est pas un critère : ni VALORANT ni Outplayed ne la transmettent (les événements n'ont qu'un numéro d'ordre).
- Mêmes parties que les statistiques : une partie enregistrée deux fois ne donne ses moments qu'une fois.

## Plateformes (TikTok, Shorts, Reels, YouTube)

Le choix **Plateforme** (sous « Formats de sortie », et dans le dialogue du montage kills ; `--platform` en ligne de
commande pour `export`, `process` et `montage`) adapte l'export à là où la vidéo sera publiée :

| | Format | Durée max | Zone sûre (haut / bas / gauche / droite) |
|---|---|---|---|
| `tiktok` | 9:16 | 10 min | 10 % / 22 % / 5 % / 14 % |
| `shorts` (YouTube Shorts) | 9:16 | 3 min | 8 % / 20 % / 4 % / 12 % |
| `reels` (Instagram) | 9:16 | 3 min | 10 % / 22 % / 5 % / 12 % |
| `youtube` | source | — | — |

- **Format** imposé (les cases cochées ne comptent plus), **volume** ramené à -14 LUFS (les plateformes normalisent :
  plus fort, la vidéo est baissée ; plus faible, elle paraît terne), **débit** plafonné à 16 Mb/s et 60 images par
  seconde au plus pour les formats verticaux.
- **Zone sûre** : les parts de l'image que l'interface de l'appli recouvre (onglets en haut, légende et musique en bas,
  boutons à droite). Les textes du montage (DOUBLÉ, TRIPLÉ…, sous-titres et libellés du montage story) sont centrés dans
  la largeur utile et gardés entre les marges du haut et du bas. Les éléments du HUD replacés en 9:16 (`vertical.hud`
  du profil) gardent la place que le profil leur donne.
- **HUD fixe pendant les zooms** : en 9:16, le HUD recollé (`vertical.hud` : bandeau VALORANT, killfeed WARDOGS) reste
  fixe par-dessus le punch-in, le zoom d'approche, la secousse et le flou de mouvement ; seule l'image du jeu zoome.
  Les couleurs du montage, le flash, les textes et le compteur de kills restent par-dessus. Montage kills, montage
  story et clips. VALORANT : le bandeau du haut et le bloc du bas (vie, armure, capacités, munitions) sont replacés
  entiers, le bloc dans la zone sûre des applications verticales ; le recadrage 9:16 s'arrête au-dessus de lui.
- **Durée** : en montage kills, la durée maximale de la plateforme devient un plafond de plus ; un montage highlights
  trop long est refusé avant le rendu, avec la durée à atteindre.
- Le nom du fichier porte la plateforme : `valorant_2026-09-27_killmontage_tiktok_9x16.mp4`.
- Valeurs relevées sur les applications, qui changent avec elles : toutes se surchargent dans `app.yaml` (`platforms:`),
  où l'on peut aussi en ajouter (ex. clips Twitch en 16:9).

```powershell
& $app montage output\sessions\partie.session.json --music D:\Musique --platform tiktok
& $app export output\sessions\partie.session.json --platform shorts
```

## Ouverture et fin (logo, pseudo)

Une courte carte ouvre et ferme chaque vidéo — highlights simples, story et montages kills — avec le logo et le pseudo
du joueur sur un fond au choix. Désactivée par défaut.

- **Où la régler** : section `intro:` d'`app.yaml` (tous les jeux) ou d'un profil (ce jeu-là, prioritaire) ; dans
  l'application, **Ouverture et fin** dans les réglages, bouton **Personnaliser…** (logo, pseudo, fond, aperçu en 16:9
  et 9:16). Les réglages faits dans l'application sont gardés dans `~/.highlights/intro.json` et remplacent alors ceux
  des fichiers pour les exports lancés depuis l'application.
- **Fond** : `blur` (une image de la vidéo floutée et assombrie : le meilleur moment en ouverture, le dernier plan en
  fin), `color` (`color: "#RRGGBB"`) ou `image` (`image: chemin`, recadrée pour remplir l'écran).
- **Durées** : ouverture 1 s, fin 1,5 s par défaut (`introLength`, `outroLength`, de 0,5 à 5 s).
- **Animation** (`animation`, fenêtre « Personnaliser… », `--intro-animation`) :
  - `rise` (par défaut) : le logo apparaît en montant légèrement (0,35 s), le pseudo « pop » juste après ; l'ouverture
    sort du noir et coupe franc sur la vidéo, la fin entre et sort par le noir ;
  - `flash` : logo et pseudo posés d'emblée, flash blanc à chaque coupe entre une carte et la vidéo ;
  - `zoom` : logo et pseudo posés, le fond avance lentement (8 % sur la carte) ;
  - `none` : rien d'animé, cartes fixes et coupes franches.
- **Son de la fin** : la musique (montage kills, highlights story avec musique) continue sous la carte de fin et s'y
  éteint en fondu (tenue un tiers de la carte) au lieu de s'arrêter avec la dernière image. Sans musique (highlights
  simples), la fin est muette.
- **Montage kills** : l'ouverture dure un nombre entier de temps de la musique (1 s demandée → 2 temps à 120 BPM) et
  joue l'amorce de la musique, ce qui précède le passage choisi, en montée de volume ; le premier plan arrive sur un
  temps et chaque kill reste sur le sien (vidéo et son reculent ensemble d'autant). Si la musique démarre trop tôt
  pour avoir une amorce, du silence la précède.
- **Plateformes** : logo et pseudo restent dans la zone sûre ; le pseudo prend une police qui a tous ses caractères
  (comme les autres textes incrustés). Les cartes comptent dans la durée maximale de la plateforme.
- **Ligne de commande** : `--intro` / `--no-intro` sur `export`, `process` et `montage` (sans l'option : `intro.enabled`),
  `--intro-animation rise|flash|zoom|none` (sans l'option : `intro.animation`).

```yaml
intro:
  enabled: true
  logo: images/logo.png        # relatif au fichier de configuration
  name: ZeroX
  background: blur             # blur, color ou image
  color: "#101820"             # fond uni, et fond de secours
  introLength: 1s              # 0,5 à 5 s
  outroLength: 1500ms
  animation: rise              # rise, flash, zoom ou none
  intro: true                  # carte d'ouverture
  outro: true                  # carte de fin
```

## Préréglages d'export (TikTok, YouTube court, vidéo complète)

Un préréglage choisit d'un clic **ce qu'on fabrique**, pour les highlights comme pour le montage kills : puces en haut
de la barre d'export et de la fenêtre de montage, `--preset <id>` en ligne de commande (`export`, `process`, `montage`,
`search --montage`). Ils sont dans `config/export-presets.yaml` (YAML strict, comme les styles) : on peut les modifier,
en ajouter ou en retirer.

| | Plateforme / format | Durée | Highlights | Style du montage kills | Miniature |
|---|---|---|---|---|---|
| `tiktok` (TikTok) | `tiktok` (9:16) | 30 s au plus | story | `tiktok` | oui |
| `shorts` (YouTube court) | `shorts` (9:16) | 60 s au plus | story | `cinematique` | oui |
| `complet` (vidéo complète) | format de la capture (21:9 reste 21:9) | 3 min au plus | story | `cinematique` | oui |

- **Durée** : un maximum. Durée cible des highlights (les moments sont resélectionnés aussitôt dans l'application, comme
  quand on tape la durée ; les jump cuts du style story la raccourcissent, sans compensation : 30 s visées ont donné
  23 s sur une partie WARDOGS) et durée maximale du montage kills (la durée adaptée au nombre de kills reste un plafond).
- **Format de la vidéo complète** : celui de la capture, sans bandes noires (une capture 21:9 donne une vidéo 21:9 ;
  YouTube l'affiche telle quelle). Pour un 16:9 imposé : `-f 16:9` ou la case du format, qui passent devant.
  `export` repart des moments déjà choisis dans la session : la durée du préréglage n'y change rien.
- **Personnalisé** : aucun préréglage, les réglages restent tels qu'ils sont.
- **Ce qu'on choisit à la main passe devant** : un réglage changé sous un préréglage (plateforme, formats, durée, style,
  miniature) est noté (« 1 réglage modifié à la main ») et ne bouge plus quand on change de préréglage ; « Revenir au
  préréglage » l'oublie. En ligne de commande, `--platform`, `-f`, `--max`, `--duration`, `--style`, `--thumbnail`
  passent devant `--preset` ; un format choisi (`-f`) écarte la plateforme du préréglage, qui imposerait le sien.
- Montage kills : le style du préréglage passe devant le style par défaut de `montage-styles.yaml` ; les cases d'un
  style modifiées à la main restent.
- La case **Miniature YouTube** (sous les puces) est posée par le préréglage et se change à la main.
- Un préréglage qui vise une plateforme ou un style inconnus est refusé au choix, avec la liste de ceux qui existent.

```powershell
& $app montage output\sessions\partie.session.json --music D:\Musique --preset tiktok
& $app process D:\Vidéo\partie.mp4 --preset complet
& $app search --min-kills 2 --montage D:\Musique --preset shorts
```

## Webcam en incrustation

La vidéo du joueur, enregistrée à côté de la partie, posée sur le montage : highlights simples, montage story et montage
kills.

- **9:16** : bandeau de webcam en haut, sur toute la largeur (30 % de la hauteur), un liseré blanc de 4 px dessous ; le
  jeu, recadré plus large, occupe le reste dessous. Le HUD recollé, le point visé par les zooms et la zone sûre des textes (DOUBLÉ, sous-titres,
  compteur de kills) descendent avec lui : rien ne passe sous la webcam.
- **16:9 et format source** : incrustation dans un coin, 24 % de la largeur, coins arrondis et liseré blanc ; le jeu
  reste entier. Le coin est celui que le HUD du jeu laisse libre, choisi dans chaque profil : haut gauche pour WARDOGS
  (la mini-carte est en bas à gauche), Apex, Fortnite et League of Legends ; bas gauche pour VALORANT, CS2 et les
  autres jeux.
- **Les effets ne touchent que le jeu** : zoom, punch-in, secousse, flous, whip pan, flash, couleurs boostées et
  étalonnage. La webcam suit la chronologie du plan (au ralenti avec lui, figée avec lui) mais reste fixe et nette.
- **Trouver la webcam** : un fichier choisi, ou « auto » : second flux vidéo de la capture (OBS peut enregistrer jeu et
  webcam dans le même fichier), sinon une vidéo du même dossier dont le nom parle de webcam (`webcam`, `facecam`,
  `cam`…) et dont l'enregistrement recouvre le plus la partie. Plusieurs captures : chacune la sienne ; un même long
  fichier de webcam peut servir à toute la soirée.
- **Synchronisation** : l'heure de début des deux enregistrements (métadonnées ou nom daté), puis le son : les attaques
  du son de la webcam sont comparées à celles du micro de la capture sur une minute, ± 5 s autour du calage par l'heure
  (un clap au début de l'enregistrement aide). Le décalage n'est retenu que si la ressemblance est nette ; sinon l'heure
  seule, ou début contre début. Le **décalage** réglé à la main (`--webcam-offset`, champ de l'application) s'ajoute,
  positif si la webcam est en retard sur le jeu.
- Une capture sans webcam trouvée : ses plans montrent le jeu en plein cadre (pas de bandeau vide en 9:16), et le
  résultat le signale.
- Application : section **Webcam** du panneau des réglages (pas de webcam / la trouver automatiquement / choisir une
  vidéo, décalage) et bouton **Aperçu de la disposition** (9:16 et 16:9 côte à côte, avec le calage trouvé). Le choix
  vaut pour l'export et le montage kills.
- Profil (`edit.webcam`) : `band` (hauteur du bandeau), `corner` (`top-left`, `top-right`, `bottom-left`,
  `bottom-right`), `size`, `margin`, `aspect` (rapport de l'incrustation du coin), `crop` (partie de l'image de la
  webcam à garder, ex. le visage), `border` / `borderColor` (liseré en pixels de sortie, 4 par défaut, 0 = aucun), `radius` (arrondi des coins du 16:9,
  en part de la hauteur de l'incrustation, 0,12 par défaut, 0 = coins droits), `offset`, `audioSync`.
- Vérifié sur des vidéos synthétiques seulement (aucune vraie webcam enregistrée au 19/11/2026).

```powershell
& $app montage output\sessions\partie.session.json --music D:\Musique --webcam auto --platform tiktok
& $app export output\sessions\partie.session.json -f 16:9 --webcam D:\Vidéo\facecam.mp4 --webcam-offset -0.4s
& $app preview D:\Vidéo\partie.mp4 --at 21:00 -f 9:16,16:9 --webcam auto   # régler la disposition
```

## Miniature YouTube

Chaque export lancé depuis l'application écrit aussi une miniature PNG prête pour YouTube, à côté de la vidéo
(`<nom>_miniature.png`, `_miniature_2.png`… : jamais d'écrasement) : 1280x720 pour une vidéo en largeur, 1080x1920
pour une vidéo verticale. La fenêtre de publication la montre, avec « Copier le chemin » (YouTube Studio la demande à
part), ou « Créer la miniature » pour une vidéo exportée avant.

- **Le moment** : le plus fort de la vidéo d'après son rapport JSON (kills du groupe, +3 pour un ace, +2 pour un clutch,
  +0,3 par tir à la tête, +0,5 pour le kill posé sur la drop), comme l'accroche du titre.
- **L'instant** : 10 images par seconde de 0,4 s avant à 1,2 s après le kill, au centre de l'écran, notées par leur
  netteté (variance du laplacien : une image filée par un flick tombe), un peu plus après le kill (la notification de
  kill est à l'écran) et moins dans le noir (luminosité moyenne ramenée à 80 ; écran noir écarté). Entre moments de
  même force (sur une soirée de Wardogs, tous les kills sont seuls), c'est l'image la plus lisible qui l'emporte.
- **L'image** vient de la capture d'origine en pleine résolution : recadrée au format, resserrée sur le réticule.
- **L'habillage** : `vivid` (défaut : couleurs boostées comme les kill montages de TikTok, texte jaune), `sober`
  (couleurs à peine relevées, petit texte blanc), `big` (texte aussi large que l'image, lisible en petite vignette). Texte :
  ACE, CLUTCH, la série (TRIPLÉ…), sinon le nombre de kills de la vidéo, dans la langue de l'interface (`video.*`).

```powershell
& $app montage partie.session.json -m D:\Musique\son.mp3 --thumbnail               # miniature (vive) à côté du montage
& $app export partie.session.json --thumbnail-style vivid                            # habillage au choix
& $app thumbnail output\wardogs_2026-09-22_killmontage_9x16.mp4 --thumbnail-style big   # vidéo déjà exportée
```

Une vidéo dont les captures ont été déplacées depuis l'export n'a plus d'image : la miniature est refusée avec un
message qui le dit.

## Publier sur YouTube

Après un export, le bouton **YouTube** à côté de chaque vidéo prépare la publication : titre, description et tags
pré-remplis d'après la vidéo, tout modifiable (« Texte proposé » y revient).

**Envoi manuel assisté (par défaut, rien à configurer)** : chaque texte a son bouton **Copier**, et « Ouvrir YouTube »
ouvre la page d'envoi dans le navigateur (où tu es déjà connecté) en montrant la vidéo dans l'Explorateur. On la glisse
dans la page, on colle les textes, on choisit la visibilité : environ 30 secondes, sans quota ni verrouillage en privé.

```powershell
& $app publish output\valorant_2026-09-27_killmontage_9x16.mp4 --open     # textes à coller, page d'envoi ouverte
& $app publish output\valorant_2026-09-27_killmontage_9x16.mp4 --title "Mon ace" --tags "fps,valorant"
```

**Envoi direct par l'API (optionnel, désactivé)** : `publish.youtube.api: true` dans `app.yaml`. La fenêtre propose
alors aussi visibilité (privée par défaut), mise en ligne programmée, catégorie, langue, « conçue pour les enfants » et
notification des abonnés, et envoie la vidéo elle-même :

```powershell
& $app publish output\valorant_2026-09-27_killmontage_9x16.mp4 --dry-run          # ce qui partirait, sans rien envoyer
& $app publish output\valorant_2026-09-27_killmontage_9x16.mp4 --privacy unlisted
& $app publish output\partie_highlights.mp4 --publish-at "2026-10-01 18:00"
& $app publish --login        # (re)connecter le compte ;  --logout pour l'oublier
```

- **Texte proposé** : tiré du rapport JSON écrit avec la vidéo. Montage kills : « TRIPLÉ — 19 kills en 57 s |
  VALORANT » (accroche : ace, clutch, sinon le plus gros multi-kill à partir du triplé), description avec kills,
  headshots, multi-kills, date de la partie, musique, puis `#VALORANT #Shorts` (#Shorts pour une vidéo verticale de 3
  minutes au plus). Highlights : « Meilleurs moments VALORANT du 27/09/2026 ». Les tags : le jeu, plus
  `publish.youtube.tags` (anglais par défaut), plus deux tags dans la langue de l'interface. Textes écrits dans la langue de l'interface (« TRIPLE KILL — 19 kills in 57s | VALORANT » en
  anglais) ; `publish.youtube.language` (vide par défaut) impose la langue déclarée à YouTube. Modèles réglables dans
  `app.yaml` (`titleTemplate`, `descriptionTemplate`, champs `{game}` `{date}` `{hook}` `{kills}` `{headshots}`
  `{detail}` `{moments}` `{duration}` `{music}` `{hashtags}` `{title}` ; les anciens noms `{jeu}` `{accroche}`
  `{duree}` `{musique}` `{titre}` restent acceptés).
- **Mise en place de l'API, une fois** : YouTube n'accepte les envois directs que d'une application déclarée chez Google.
  1. [console.cloud.google.com](https://console.cloud.google.com) : créer un projet, puis activer « YouTube Data API
     v3 » (API et services › Bibliothèque).
  2. Écran de consentement OAuth : type « Externe », s'ajouter comme utilisateur test.
  3. Identifiants › Créer › ID client OAuth › « Application de bureau », puis télécharger le JSON.
  4. Déposer ce `client_secret_….json` dans le dossier de configuration (à côté d'`app.yaml`), ou indiquer son chemin
     dans `publish.youtube.clientSecretFile`.

  Au premier envoi, le navigateur s'ouvre sur la page de Google ; le jeton est ensuite gardé dans
  `~/.highlights/youtube-token.json`. Il ne permet que d'envoyer des vidéos (portée `youtube.upload`), jusqu'à
  « Se déconnecter » ou au retrait de l'accès dans le compte Google.
- **Limites de Google** : le quota gratuit d'un projet permet environ 6 envois par jour. Surtout, tant que le projet
  n'a pas passé l'audit de YouTube, **les vidéos envoyées par l'API sont verrouillées en privé**, quelle que soit la
  visibilité demandée : c'est une règle de YouTube pour les projets non audités, pas un réglage de Highlights. Pour un
  usage personnel, on envoie en privé puis on change la visibilité dans YouTube Studio (lien « Studio » après
  l'envoi) — ou on demande l'audit dans la console Google Cloud.
- Le `client_secret_….json` déposé dans `config/` est ignoré par git et exclu de l'installeur.
- **Envoi reprenable** : par morceaux de 8 Mo ; une coupure réseau ou une erreur passagère reprend là où l'envoi en
  était, au lieu de tout renvoyer.

## Événements de jeu (Outplayed)

`outplayed-events` reprend les événements qu'Outplayed a enregistrés avec la capture : kills, morts, assistances,
headshots… transmis par le jeu lui-même via Overwolf, sans aucune analyse d'image. Ils sont lus en lecture seule dans
la base de l'application (`%LOCALAPPDATA%\Overwolf\CefBrowserCache\…\IndexedDB`), même pendant qu'Outplayed tourne.
La capture est retrouvée par son chemin, ou par son nom de fichier si elle a été déplacée. Pour une capture qui ne vient
pas d'Outplayed (OBS…), le signal est simplement absent.

- `kinds` choisit les types retenus (ex. `kill: kill`) ; `offsets` corrige le retard de l'événement (VALORANT : −390 ms,
  mesuré sur une partie complète : l'instant tombe alors sur l'image où le kill apparaît dans le killfeed).
- Sur une partie VALORANT de 50 min : 22 kills, 22 morts, 8 assistances, exactement le tableau de fin.

## Ajouter un jeu : l'assistant « nouveau jeu »

Pour un jeu sans profil, l'assistant écrit `config/profiles/<jeu>.yaml` à partir d'une vidéo et de 1 à 3 kills
marqués. Dans l'application : « Nouveau jeu… » dans la liste des analyses. En ligne de commande :

```powershell
& $app nouveau-jeu "D:\Vidéo\partie.mp4" --game "Counter-Strike 2" --mark "3:48.5@0.903,0.063,0.095,0.042" --mark "4:57.5@0.875,0.096,0.125,0.042"
```

1. Pour chaque kill marqué : l'instant où le repère apparaît, et un cadre autour de ce repère (notification, ligne du
   killfeed, compteur). Dans l'application, on affiche l'image et on trace le cadre à la souris ; en ligne de commande,
   `@x,y,largeur,hauteur` en fractions de l'image (ou `--box` pour tous).
2. L'assistant construit un détecteur par méthode et les fait tourner ensemble sur toute la vidéo (un seul décodage) :
   - **image du repère** (`hud-template`) : le cadre découpé sur le kill marqué où il change le plus ; seuil choisi
     d'après les scores des kills marqués ;
   - **ligne de couleur** (`killfeed`, mode `outline`) : la teinte qui apparaît dans le cadre au kill et la hauteur de
     la ligne, mesurée sur ses traits, fixent couleur et tailles ;
   - **compteur** (`hud-counter`) : les chiffres du compteur sont **appris sur la vidéo**, sans OCR. Les formes exactes
     du chiffre des unités, regroupées (deux formes d'un même chiffre alternent dans une même valeur), sont nommées par
     l'ordre du compteur (il monte de 1, revient à 0 à chaque partie, « 10 » a un 1 devant) ancré par leur ressemblance
     à des chiffres de polices grasses, puis redécoupées sur les images où la valeur est sûre. Si rien n'est appris (pas
     de compteur dans le cadre), chaque nouvelle valeur stable de la zone est un kill (`hud-change`, sans lire) ;
   - **texte** (`ocr-log`) : le mot lu par l'OCR de Windows sur les kills marqués et jamais ailleurs.
3. Une planche par méthode (12 détections), toutes décochées : on coche celles qui sont de vrais kills (en ligne de
   commande, on donne leurs numéros). La méthode retenue garde le
   plus de vrais kills moins de faux, et doit retrouver au moins un kill marqué. Le profil est écrit (son, le
   détecteur choisi, puis sélection et montage du profil par défaut), jamais par-dessus un profil existant.

Mesuré (18-19/11, `app verite`, CS2 et Fortnite refaits depuis 3 kills chacun) :

| Jeu | Méthode retenue | Profil de l'assistant | Profil fait main |
|---|---|---|---|
| CS2 (33 kills, 14 morts) | ligne de couleur | 32 kills (8 faux), 14 morts (1 fausse) | 32 kills (10 faux), 13 morts ; depuis le tri des assistances : 30 kills (4 faux) |
| Fortnite (31 kills) | compteur, chiffres appris | 31 kills (0 faux) ; 23 kills (19 faux) avant l'apprentissage | 31 kills (0 faux) |

Fortnite : l'assistant apprend maintenant les 10 chiffres du compteur tout seul (3 kills marqués, environ 1 s de
calcul après la lecture de la zone) et fait aussi bien que les modèles découpés à la main (31/31, 0 faux,
`output/revue/assistant-chiffres/`). Avant, il comparait des images de 6x6 px sur un fond qui bouge (23 kills, 19 faux).

## Killfeed (sans Outplayed)

Une capture VALORANT enregistrée par OBS, ShadowPlay ou autre n'a pas d'événements Outplayed : le détecteur `killfeed`
les lit alors à l'image. Dans le killfeed, le portrait du joueur est pris dans un cadre jaune : à gauche quand il
tue, au bord droit quand il meurt, quels que soient l'agent, l'arme, le skin ou le pseudo (« Moi » ou son nom). FFmpeg
ne transmet de la zone que ce jaune (expression `color`, un octet par pixel). Chaque tache jaune qui a la forme du cadre
(hauteur d'une ligne, trait en haut ; largeur et remplissage varient selon ce que le visage de l'agent en cache) est une
ligne du joueur, suivie d'une image à l'autre (elle garde sa place en largeur et ne fait que monter), et chaque nouvelle
ligne est un événement.

- Mesuré sur quatre parties : trois en 3440x1440 comparées à Outplayed, une en 1920x1080 d'un autre joueur et d'un
  autre agent relevée à l'image : 79 kills sur 79, 58 morts sur 58, aucun faux positif (ni le décor jaune, ni les
  flashs, ni les icônes du jeu, ni le graphe « Erreur de tir »). Une ligne « joueur → joueur » (ultime de Clove qui
  expire) compte comme une mort, pas un kill. Pas d'assistances ni de headshots, que le killfeed ne distingue pas.
- Toutes les captures sont lues à la même finesse (`scale`, en pixels lus par pixel de référence) : réduite davantage,
  une capture 1080p perdait le contour d'un pixel du cadre.
- Il ne tourne que si Outplayed n'a rien donné (`fallbackFor: game-events` dans le profil) : les kills ne sont jamais
  comptés deux fois, et une capture Outplayed ne coûte pas un décodage vidéo de plus. Il tourne alors en même temps
  que l'analyse audio, pas après.
- Lecture en deux temps (`keyframeScan`, 2 s par défaut) : seules les images clés sont d'abord lues, sans décoder les
  autres. Une ligne du joueur qu'aucune image clé des secondes précédentes n'explique (même bord gauche, même hauteur ou
  plus haut) est nouvelle : seul l'intervalle où elle est apparue est décodé à 5 img/s, de l'image clé d'avant à une
  seconde après celle qui la montre, trois intervalles à la fois. Une ligne qui reste ou qui monte ne coûte rien. Sur
  une capture 3440x1440 de 2 min à 60 img/s (images clés toutes les 2 s), 5 s d'analyse au lieu de 35, mêmes kills aux
  mêmes instants. Si les images clés de la capture sont plus espacées (réglage « intervalle d'images clés » d'OBS), ou
  avec `keyframeScan: null`, toute la vidéo est décodée à 5 img/s comme avant.
- `fallbackFor` vaut pour n'importe quel détecteur : il désigne l'id du détecteur que celui-ci remplace quand son signal
  est absent.
- La zone (`region`) est mesurée en 3440x1440 et suit le format de la capture (`referenceWidth`, `anchor: right`) ; les
  tailles du cadre (`minHeight`…`maxFill`) et la couleur (`color`, `threshold`) se règlent pour un autre jeu.
- **Basse définition** (capture de moins de 720 px de haut, `fillBelow`) : le contour du cadre (3 px en 1440p) y fait
  moins d'un pixel et la couleur sous-échantillonnée le délave jusqu'à la teinte des murs de sable ; mesuré en 854x480
  (deux parties téléchargées de YouTube) : 25 à 50 de jaune pour un seuil de 60, 1 kill lu sur 44. Le détecteur y lit
  alors l'aplat jaune pur derrière le portrait du joueur (`fillColor` : rouge et vert égaux à 30 près, ce qui écarte le
  sable), une tache pleine de la hauteur d'une demi-ligne à une ligne (`fillMinHeight`…`fillMin`), pas collée au bord
  droit de l'écran (bordure dorée de certaines capacités). Une ligne doit y rester visible 2 s (`fillMinSeen`, une
  ligne en reste 5) et toute la vidéo est décodée (480p coûte peu). Sur la partie dont le tableau de fin dit 44 kills /
  14 morts : 46 kills et 18 morts lus (1 et 0 avant) ; 4 des morts portent l'icône de l'ultime de Phoenix, qui ramène le
  joueur sans compter de mort (vérifié à l'image : il revient aussitôt). Ces morts sont écartées partout (montage des
  morts, rounds, clutchs, K/D) : le détecteur `killfeed-teams` reconnaît le grand cercle blanc de « Run it Back » sur
  la ligne (il déborde en haut et en bas, l'icône de tir à la tête non) et émet `ally-run-it-back` ; la mort du joueur
  lue à moins de 1,5 s est retirée (`killStyle.revivedEvent`). Sans Outplayed seulement, et mesuré en 480p seulement.
  La géométrie 16:9 n'était pas en cause : la zone mesurée en
  21:9 tombe juste sur le killfeed en 16:9.

### Counter-Strike 2 : lignes encadrées (`outline`)

Dans CS2, le joueur n'a pas de portrait dans le killfeed : c'est sa **ligne entière** qui est marquée, entourée d'un
contour rouge quand il tue (ou aide), sur fond rouge plein quand il meurt. Avec `outline` (profil `cs2`), le détecteur
`killfeed` cherche ces lignes au lieu du cadre d'un portrait ; `fillBelow` et les réglages du cadre ne servent plus.

- Rouge du joueur (`color`) : rouge nettement au-dessus du vert et du bleu, eux égaux (contour 163/92/100 sur un mur
  clair, fond 96/23/21) ; le sable et les murs au soleil (180/135/97) restent à 0.
- En 854x480, le contour fait 2 px en haut et en bas mais un seul sur les côtés, et la couleur sous-échantillonnée en
  efface des morceaux sur un ciel ou un mur clair (côtés, moitié gauche d'un trait, trous de 8 à 14 px sous les
  lettres, d'où `bridge: 16`). Une ligne est donc une **paire de traits horizontaux** qui finissent au même bord droit,
  l'un 13 à 16 px sous l'autre, l'intérieur presque vide (`maxInnerFill`) ; deux lignes du joueur empilées (doublé)
  sont deux kills. Une bande de la hauteur d'une ligne, colorée à 50 % au moins (`deathFill`), est une mort ; collée
  au bord de l'écran ou loin de lui (`minEdgeGap`, `maxEdgeGap`), c'est du décor (voile rouge des dégâts, panneaux).
- Suivi : le bord gauche d'une ligne n'étant pas sûr, une ligne est rapprochée du suivi le plus proche en hauteur ; une
  ligne qui dépasse à gauche de 45 px (`newLineGrowth`) le suivi qu'elle rejoint en est une nouvelle (une ligne plus
  longue prend la place de la plus ancienne). Une ligne doit être vue 1 s (`minSightings: 10` à 10 img/s).
- **Assistances** (`outline.player`) : le contour marque aussi les lignes « coéquipier + joueur ». Ce qui les sépare
  d'un kill, c'est le premier nom de la ligne. Le pseudo du joueur n'est pas donné : il est appris sur la vidéo, comme
  le premier mot qui revient le plus souvent en tête des lignes encadrées (21x10 px en 480p), lu dans le canal le plus
  clair de la même zone. Une ligne est un kill si son premier mot lui ressemble (corrélation ≥ 0,86, posée sur le début
  du mot : posée n'importe où, « Adamant » ressemblait à « Kryptek ») ; sinon une assistance (événement `assist`), sauf
  si elle lui ressemble un peu (≥ 0,3) et que le chat affiche au même moment une récompense « +$… for neutralizing an
  enemy » (le « +$ » vert en début de ligne, `reward`), donnée pour les kills seuls. L'OCR de Windows ne lit pas ce
  message en 480p, d'où sa couleur.
- Mesuré sur « CS2- 33 Kills On Dust 2 » (854x480, 28 min, `verite/cs2/`) : **30 kills sur 33**, 4 en trop ; 13 morts
  sur 14, aucune en trop. Des 6 assistances vues par le contour, 4 sont écartées ; celles de 9:26 et 16:43 restent
  (une récompense du chat tombe au même moment). Avant ce tri : 32 kills sur 33 et 10 en trop, dont les 6 assistances.
  Manqués : 10:44 et 16:29 (premier mot mal lu sur un fond clair, sans récompense lue) ; 18:50 (ligne plus courte qui
  remplace celle du kill d'avant). Une mort manquée sur un écran déjà rouge. Analyse : 1 min 40 (trois zones lues).
- 9:16 : bandeau du haut, killfeed, mini-carte (en haut à gauche, face au killfeed) et ligne du bas (vie, munitions)
  sont replacés (`vertical.hud`).

## Journal des gains (Wardogs)

`ocr-log` lit un journal textuel affiché à l'écran : dans Wardogs, la liste des actions récompensées en haut à droite
(« ÉLIMINATION +$1500 », « AIDE : ÉLIMINATION », « COÉQUIPIER RÉANIMÉ 250XP »…). La lecture passe par l'OCR intégré à
Windows (rien à installer), par lots et en parallèle pendant le décodage de la vidéo.

- `rules` associe des libellés à un type d'événement, dans l'ordre, avec quelques erreurs de lecture tolérées ; une
  règle sans `kind` fait ignorer la ligne (pénalités, bonus d'XP…).
- Chaque ligne est suivie d'une image à l'autre et n'est comptée qu'une fois, après deux lectures.
- `icons` (paramètres d'un `hud-template`) fusionne le journal avec les icônes de notification : le journal donne le
  type (une « Aide : Élimination » affiche la même tête de mort qu'un kill) et sépare les kills enchaînés, l'icône date
  l'événement et couvre le texte illisible sur ciel clair.
- `notice` lit aussi le libellé écrit sous l'icône (« ÉLIMINATION CONFIRMÉE », « AIDE : ÉLIMINATION », « UN PASSAGER
  A SURVÉCU »…), avec ses propres `rules` : il corrige le type de l'icône quand le journal n'a rien lu, ou l'écarte.
  Sans lui, une aide au kill sortait en kill (même tête de mort) et un passager déposé en aide (même poignée de main).
  Illisible (texte gris sur fond clair), l'icône garde son type.
- Joueur à terre : « EN ATTENTE DE RÉANIMATION » sort en événement `downed`, pas en mort (il est souvent relevé).
  `montage.killStyle.downedAsDeath: true` le ferait compter comme une mort (rounds, kill aussitôt payé, K/D) ; désactivé.
- Sur une partie de 1 h 25 : 14 kills sur 14, 3 aides sur 3, 8 réanimations sur 10, aucun faux positif (les icônes
  seules : 12 kills et un faux, aucune aide, 4 réanimations). Environ 1 min d'analyse pour 1 h 53 de vidéo.

## Notifications de kill (Apex Legends)

Le profil `apex` lit avec `ocr-log` la notification affichée sous le réticule, au lieu d'un journal. Les notifications
s'empilent, la nouvelle au-dessus des autres : `maxDrop: 1` laisse une ligne déjà vue descendre d'un cran sans la
recompter (sans lui, une élimination poussée par un « KNOCKED DOWN » comptait deux fois). Jeu en anglais
(`language: en-US`).

- **Le kill, c'est la mise à terre** (choix du joueur) : « KNOCKED DOWN <pseudo> » donne le `kill`, et le plan du
  montage est calé dessus. « ELIMINATED <pseudo> » et « SQUAD WIPE » (`finishes` du détecteur, `LogFinish`) qui
  achèvent un ennemi déjà mis à terre (30 s au plus avant) ne font ni un deuxième plan ni un deuxième kill ;
  « SQUAD WIPE » referme toutes les mises à terre ouvertes. Une élimination sans mise à terre lue avant (achèvement
  hors champ, tiers qui passe) compte, à son instant ; une même notification relue est écartée. Les libellés
  DOUBLÉ / TRIPLÉ et les statistiques suivent les mises à terre.
- Ni ace ni clutch déduits (escouades de trois, pas de rounds).
- 9:16 : compteur (escouades restantes, kills, dégâts) et killfeed, en haut à droite, replacés en haut à gauche ; vie
  et arme replacées en bas (à partir de y 0,85), sous la bande de la notification (y 0,66 à 0,83), qui reste visible.
- Mesuré contre le compteur de kills du HUD, relevé à l'œil (`verite/apex/`), sur deux compilations YouTube en
  854x480, image teintée. Le compteur crédite le kill à l'élimination, 2 à 30 s après la mise à terre : la vérité
  le dit (`lead: 30s` : une détection peut précéder le relevé de 30 s, `GroundTruth.lead`). « Apex 53 kills octane »
  (trois parties) : 54 kills pour 56 crédits, 47 retrouvés ; « Apex 37 kills revenant » : 37 pour 36, 35 retrouvés.
  Les kills sans crédit sont des mises à terre d'ennemis relevés par leurs alliés (le jeu ne les compte pas) ou
  achevés par un autre. Avant (kill = élimination, SQUAD WIPE compté par mise à terre avec `credits`) : 43 et 29
  vrais à 3 s.
- Statistiques : les « kills » sont les mises à terre, comme le montage. Leur total est plus proche du compteur du jeu
  (54 pour 56, 37 pour 36) que celui des éliminations lues (44 et 31) : une élimination de SQUAD WIPE en cache
  plusieurs.
- `credits` (`LogCredit`, datation et compte des notifications en rafale) reste disponible pour un jeu dont le kill
  est l'élimination.
- Le compteur lui-même (chiffres de ~6 px en 480p) n'est pas lisible par l'OCR de Windows. Morts du joueur non lues :
  aucune dans ces captures.

## Voix et rires

- `voice-activity` : prises de parole sur la piste micro. Avec `selection.keepWhole: [speech, laughter]`, un moment
  n'est jamais coupé au milieu d'une phrase ou d'un rire (extension jusqu'à `maxExtension`).
- `audio-events` : rires et exclamations détectés par YAMNet (Google, hors ligne, `config/models/`), qui deviennent
  des moments à garder (`eventBoosts`). Le journal indique les meilleurs scores pour régler les seuils.

## Tirs et tirs à la tête dans le son du jeu

Le détecteur `game-sounds` lit la piste du jeu une fois (48 kHz mono, en flux) et ne dépend ni d'Outplayed ni de
l'image :

- `shots` : chaque tir du joueur devient un événement `shot`. Ce sont les attaques les plus fortes de la partie (son
  arme), les tirs lointains, les pas et les capacités étant plus faibles (`percentile`, `belowDb`). Le montage en tire
  le nombre de balles de chaque kill (`KillTraits.shots`) : un kill d'une balle à la tête est un one tap.
- `sounds` : un son précis reconnu à son empreinte (spectre en bandes, corrélation normalisée), par exemple le son de
  l'impact à la tête pour les captures sans Outplayed. Le gabarit s'extrait une fois d'une capture, au ras du début
  du son : `ffmpeg -ss 548.054 -t 0.09 -i partie.mp4 -map 0:a:1 -ac 1 config/templates/valorant/headshot.wav`. Le
  journal donne les meilleures ressemblances pour régler `threshold`.

Mesuré sur deux parties VALORANT (23 kills, dont 9 headshots selon Outplayed) :

- Les tirs isolés sont bien entendus, pas les rafales : entre deux balles du Vandal (0,1 s), le son ne redescend que
  de 6 à 10 dB, et une balle sur deux à quatre sur cinq passe inaperçue. Deux sprays passaient ainsi pour des one taps,
  et les vrais one taps sortaient avec deux à quatre « tirs » (le son du kill, de l'impact à la tête). En VALORANT,
  les balles viennent donc du compteur de munitions (ci-dessous) ; le son reste le recours des autres jeux.

## Balles lues sur le compteur de munitions

Le détecteur `ammo-counter` (VALORANT) relit, pendant l'analyse, 2 s d'image autour de chaque kill (1,8 s avant, 0,45 s
après) dans la zone des chiffres du chargeur, à 60 images/s : chaque changement des chiffres est une balle (événement
`hud-shot`). Il tourne après les détecteurs qui donnent les kills (`dependsOn: [game-events, killfeed]` : Outplayed,
ou le killfeed d'une capture OBS). La balle qui tue est la dernière vue entre 1 s avant l'instant du kill et 0,3 s après
(Outplayed l'annonce jusqu'à 0,6 s après la balle) ; comptent avec elle celles des 0,8 s d'avant et des 0,25 s d'après
(une rafale qui continue n'est pas un one tap).

- Mesuré : 111 balles sur 111 autour de 14 kills vérifiés à l'œil. Sur 16 parties, 5 vrais one taps sur 138 kills à
  la tête, dont aucun n'était désigné par les tirs entendus.
- Combat à mort : le chargeur se remplit à chaque kill, annoncé par une animation (chiffres agrandis, puis turquoise
  qui repasse au blanc en 0,3 s), qui comptait pour trois balles de plus : aucun one tap n'y était jamais trouvé. La
  zone est aussi relue en turquoise, et les changements pendant une recharge ne comptent pas (2 balles sur 2 au lieu
  de 5, vérifié à l'image). Aucun turquoise autour des kills des parties classées.
- Lecture en paliers : un changement ne compte que s'il fait passer le compteur d'un affichage stable (0,05 s au moins)
  à un autre, et un compteur absent ou effacé (presque sans blanc : caché, flash) n'est pas lu. Avant, chaque image
  était comparée à la précédente : le décor qui change derrière les chiffres (flash rouge des dégâts, mur clair,
  compteur caché) comptait des balles. Sur une partie en 854x480 (16:9), autour de 5 kills vérifiés à l'image :
  33 vraies balles sur 33 dans les deux cas, 29 fausses avant, 3 après (`minPlateau`, `minDigits`).
- Zone mesurée en 3440x1440 et vérifiée en 854x480 (16:9). Une partie analysée avant ce détecteur (ou avant la correction du Combat à mort)
  est à mettre à jour (**Mettre à jour les analyses**, ou `--reanalyze`).
- Le gabarit du son headshot livré reconnaissait la moitié des headshots de deux parties du 25/09, sans faux. Revu le
  19/11 sur deux autres parties : 2 kills à la tête sur 27, et 4 kills au corps dits à la tête. Il est éteint dans le
  profil VALORANT (`headshot-sound`, `enabled: false`) : l'icône du killfeed le remplace (ci-dessous).

## Tirs à la tête lus dans le killfeed

Sans Outplayed, le détecteur `killfeed-headshot` (VALORANT, `headshot-icon` du profil) relit 2 s du killfeed autour de
chaque kill (0,4 s avant, 1,6 s après, 10 images/s) : il y retrouve la ligne du joueur comme le détecteur `killfeed`
(même zone, même lecteur), cherche le début du fond rouge de la victime à droite de son portrait, et juste avant lui
l'icône de la cible que VALORANT met entre l'arme et la victime d'un kill à la tête (corrélation avec
`templates/valorant/killfeed_headshot.png`, seuil 0,6). Événement `headshot` à l'instant du kill. Avec Outplayed, qui
donne déjà les tirs à la tête, il ne relit rien.

- Le profil disait l'icône absente des lignes du joueur : c'était faux. Planche des 46 kills de « Valorant- 44 kills »
  (854x480) : l'icône est là sur 18 d'entre eux.
- Mesuré à l'image sur les deux captures 854x480 du disque (61 kills, 27 à la tête) : 26 retrouvés, aucun faux
  (seuil 0,6 ; les kills sans icône restent sous 0,45). Voir le journal de l'atelier,
  `output\revue\onetaps-finition\LOG.md`. Gabarit moyen de 9 icônes nettes de la première partie.
- La ligne se pose en glissant (0,4 s) : la note retenue est la meilleure image de la ligne apparue le plus près du
  kill ; deux kills à 0,4 s d'écart ont chacun la leur. Une ligne déjà là avant le kill ne compte pas.
- Non vérifié en 1080p ni en 1440p (gabarit pris en 480p, agrandi à la taille de la ligne).

## Monter sur la musique : les notes qui ressortent

La grille des temps vient de la pulsation du morceau (la grosse caisse). Mais ce qu'on entend, ce sont les notes qui
ressortent : dans l'intro de « Rome Is Burning », le piano joue une phrase de 8 temps dont deux notes jaillissent (un mi
au temps 0, un fa au temps 6, jusqu'à +33 dB), au milieu de petites notes régulières. Le moteur les repère sur n'importe
quel morceau, sans réglage par musique :

- Saillance de chaque temps (`MusicAnalysis.salience`) : la montée du volume à l'attaque comparée à celle des temps
  voisins (8 de part et d'autre), et le niveau du temps face au plus fort alentour. Sur une drop à grosse caisse
  régulière, aucun temps ne ressort (0 % sur deux morceaux) ; sur « Rome Is Burning », toute la phrase du piano.
  Le journal l'affiche (« Moments qui ressortent (notes + changements de son) : intro 5+2/18 … »).
- Changements de son : un temps dont le spectre s'écarte de celui d'avant au moins trois fois plus que d'habitude
  alentour, durablement (pas un bruit d'un seul temps, ni le retour à la normale après lui). Un instrument qui entre, une
  voix, une coupure avant la drop. 9 à 50 par morceau sur six morceaux, presque tous à l'intérieur des sections. Ils
  comptent comme les notes : coupe calée juste avant, kill dessus.
- Le rapport du montage dit pour chaque plan si son kill tombe sur un de ces moments (`onMusicMoment`). Au rythme rapide,
  sur une minute : 9 moments sur 10 reçoivent un kill (« Fortunate Son »), 16 sur 19 (« Rome Is Burning »), 25 sur 38
  (« R2D2 »).
- Ouverture vitrine (`showcase`, désactivée par défaut ; case « Ouverture vitrine » ou `--showcase`) : comme les kill
  montages de référence, le montage s'ouvre sur des plans sans kill (8 s au plus, `length`, deux temps chacun,
  `shotBeats`), sur les temps de musique qui précèdent : le joueur qui avance, l'arme en main. Chaque passage est pris
  juste avant le plan d'un kill du montage (1 s avant ce qu'il montre), au moins 5 s après le kill ou la mort d'avant
  (`afterEvent`). Sa place est réservée dans la durée maximale. Rien si la musique est prise depuis son début. Le critère
  « ouverture » de la note baisse d'autant : le premier kill arrive plus tard, c'est voulu.
- Plans longs coupés dans l'attente (`cuts.jumpCuts`, activé par défaut) : un plan d'au moins 8 temps (`jumpMinBeats`)
  est coupé sur le temps le plus marqué de son attente, à deux temps au moins du kill et des bords. Attente avant le
  kill : la première partie est la même partie 4 s plus tôt (`jumpBack`) ; après le kill : la fin est 4 s plus tard (le
  joueur qui repart). Kills, coupes et longueurs ne bougent pas. Jamais sur la drop, un raccord, un plan ralenti ou
  d'un temps, ni si le saut ferait voir un autre kill ou la mort du joueur (passage calme autour du groupe, morts
  connues seulement). Le rapport le dit pour chaque plan (`jumpCut`).
- Contretemps syncopés (`cuts.offbeatKills`, activé par défaut) : un contretemps qui frappe au moins 1,2 fois plus fort
  que les deux temps qui l'entourent peut recevoir le kill du plan, s'il est mieux noté que le temps retenu (sans le
  bonus du premier temps de mesure) ; les coupes restent sur les temps, et la drop sur le sien. Sur 17 musiques, de tels
  contretemps font 0 à 5 % des temps, 11 % sur « Big Dawgs » (hip-hop à 90 BPM) : 9 kills sur 19 y passent au
  contretemps (accent 0,45 → 0,58), 2 sur 30 sur « Spitfire », aucun sur « Rome Is Burning ». Le rapport le dit pour
  chaque plan (`offbeat`).
- Cadence : la longueur des plans vient de l'intensité de chaque section, en continu (de `cuts.low` pour la plus calme à
  `cuts.high` pour la plus intense ; au rythme rapide, de `fast.calm` 1,6 s à `fast.cut` 0,4 s). Les notes ne font que
  placer les coupes : chaque coupe se cale juste avant la note la plus marquante à une demi-longueur de plan, et le kill
  tombe dessus ; sans note à portée, la coupe régulière.
- Un plan ne s'allonge que pour montrer ses kills : le ralenti n'a de place réservée que sur le plan de la drop, et n'est
  gardé ailleurs que s'il tient. Les multi-kills étalés (deux kills à plus de 1,5 s, `montage.splitGap`) deviennent
  plusieurs plans, chacun sur la musique ; le groupe reste entier pour le classement, les aces et les clutchs.
- Le temps du kill d'ancrage préfère les notes qui ressortent, et les rampes qui recalent les autres kills aussi.
- Mesuré sur « Rome Is Burning » (17 parties, une minute depuis le début) : au rythme rapide, un kill tous les 2 temps
  dans l'intro (sur le piano), un par temps dans la drop, contre un toutes les 3,8 s dans la drop avant ; au rythme
  normal, la drop passe d'un kill toutes les 2,9 s à un toutes les 1,6 s.

## Kills confirmés par le killfeed (VALORANT)

L'annonce d'un kill par Outplayed s'écarte du vrai kill de -0,55 à +0,47 s. Dans un plan d'un temps, qui commence 0,2 s
avant le kill, c'était trop : sur un montage de 76 kills, l'icône de kill (sous le réticule, l'instant où le jeu
l'enregistre) apparaissait avant la coupe pour un kill sur trois, et on ne voyait que la suite. Le détecteur
`kill-confirm` relit donc toujours le killfeed, à 30 img/s, et émet `feed-kill` à l'apparition de chaque ligne du
joueur : 0,02 à 0,10 s après l'icône sur 13 kills mesurés. Le montage cale chaque kill sur la dernière balle vue au
compteur de munitions jusqu'à 0,12 s après cette ligne (à ±0,07 s de l'icône), ou sur la ligne elle-même sans balle
(capacité). Réglage `montage.killStyle.confirmEvent`. Coût : environ 1 min 30 d'analyse pour une partie de 30 min ;
les parties analysées avant sont à mettre à jour.

## Rythme rapide : un kill par temps

**Par défaut, seulement sur les parties rapides de la musique** (`montage.fastParts`, case « Rythme rapide sur les
parties rapides » de « Réglages avancés », `--no-fast-parts` pour l'éviter) : la drop et ce qui suit, si cette partie est
intense (`minIntensity` 0,7 ; « Rome Is Burning » : 0,95 après la drop), quel que soit le tempo. Avant, l'intro et la
montée gardent le rythme du style (Cinématique : plans longs, ralentis) ; après, un kill par temps (deux à 160 BPM et
plus), sans ralenti ni rampe, zoom d'approche vers le réticule. Le rythme rapide part de la drop elle-même (choix de
l'utilisateur du 18/11) : le meilleur groupe y tombe, en plan rapide, sans ralenti (un multi-kill s'étend juste avant
la drop pour y finir). Si une musique passe au rythme rapide sans que vous le vouliez, `--no-fast-parts` ou la case
l'évitent.

**Le rythme suit la musique après la drop** : chaque partie calme qui suit (breakdown, couplet plus doux, outro) revient
au rythme du style, et le rythme rapide reprend quand la musique redevient intense. Les sections de l'analyse font au
moins quatre mesures ; une coupure plus courte au milieu d'une partie intense (la basse de « Big Dawgs » qui s'arrête
deux mesures à 0:20) est repérée à part : une suite d'au moins `calmBars` (2) mesures dont le volume est à `calmDb`
(7 dB) ou plus sous le niveau fort du morceau devient une section calme, au rythme du style. Mesuré sur les 19 musiques
de test : seule cette coupure de « Big Dawgs » descend aussi bas (9,2 dB) ; ailleurs, deux mesures d'une partie intense
ne descendent jamais à plus de 5,8 dB. Avec beaucoup de kills, le montage peut finir avant le retour au calme : 45 kills
d'un plan d'un temps tiennent en 25 s après la drop, et la première partie calme de « Rome Is Burning » arrive 61 s
après la sienne.

**Garde-fou : peu de kills.** Un plan d'un temps par kill raccourcit le montage d'autant : 3 kills faisaient 1,5 s. Si le
montage au rythme rapide dure moins que `montage.length.min` (12 s), il est refait entièrement au rythme du style.
Au-dessus, le rythme rapide reste, même s'il raccourcit le montage : 10 kills WARDOGS en font 17 à 26 s, et c'est la
version préférée (18/11).

### Ressenti de chaque musique

Aucune mesure du contenu ne sépare seule les musiques qu'on ressent rapides des calmes (mesuré sur 19 musiques). Le
montage apprend donc de vos réponses. Sous le résultat d'un montage kills, une question : « Rythme rapide sur
<musique> ? » **👍 Bien**, **👎 Trop rapide** ou **👎 Pas assez rapide**. En ligne de commande : `app musique-ressenti
MUSIQUE fast|calm|auto` (`auto` efface), et `app musique-ressenti` seul liste les ressentis et ce que le modèle en a
appris. La fin d'un `app montage` rappelle la commande.

- **calm** : jamais de rythme rapide sur cette musique.
- **fast** : rapide dès la drop, aussi dans les parties moyennes qui suivent (intensité à partir de
  `montage.fastParts.fastIntensity`, 0,45 au lieu de 0,7).
- **auto** : la règle par défaut.
- « Bien » confirme ce que le montage a fait : rapide s'il avait des plans rapides, calme sinon.

Les ressentis sont gardés dans `<sortie>\sessions\music-feel.json`, par nom de fichier et taille : une musique copiée
ailleurs garde le sien, une autre version (autre taille) non. Chaque réponse garde aussi les mesures de la musique
(attaques par seconde, toutes bandes puis basses, médiums, aigus ; volume ; part percussive ; tempo), d'où un modèle
appris (régression logistique) qui devine le ressenti des musiques sans réponse. Il ne sert qu'à partir de 8 musiques,
dont 3 de chaque sorte, et seulement s'il classe bien au moins 80 % des musiques qu'il n'a pas vues (validation croisée
« une de côté »). Sinon, la règle par défaut. Le jeu de départ connu le 18/11 (rapides : « Rome Is Burning » après la
drop, « R2D2 », « Spitfire », « Doom Eternal », « Big Dawgs » ; calme : « Lose Yourself ») n'est pas versé dans vos
réponses. Il sert de données de test : avec 6 musiques dont une seule calme, le modèle en classe 4 sur 6 en validation
croisée et reste inactif.

Dans une bibliothèque de musiques, un style au rythme rapide partout (`fast: true`, plans d'un temps) préfère les
musiques ressenties rapides (+0,03 sur la note du choix) et évite les calmes (−0,03). Les autres styles n'avancent
rien : le ressenti y règle seulement les parties rapides.

Plus de seuil de tempo (choix de l'utilisateur du 18/11, après des montages côte à côte avec et sans lui) : le seuil des
120 BPM gardait au rythme du style des musiques lentes mais intenses (« Doom Eternal » 103 BPM, « Big Dawgs » 90).
Aucune mesure du contenu (attaques par seconde, part percussive, volume) ne séparait mieux les musiques ressenties
rapides des calmes. Une ancienne configuration qui pose `montage.fastParts.minBpm` se charge encore ; le réglage est
ignoré.
Le rythme rapide forcé (ci-dessous) rend tout le montage rapide.

Case « Rythme rapide » du dialogue du montage kills, `montage --fast` ou `search --montage … --fast` : un kill sur
chaque temps de la musique, sur chaque coup de grosse caisse d'une techno, comme les edits TikTok de référence (plans de
0,37 à 0,67 s en médiane). Tous les kills, chacun son plan (même dans un multi-kill), sans ralenti ni rampe (ils n'ont pas
la place), ni whip pan (200 ms d'image qui file dans un plan de 0,37 s : jugé illisible) ; zoom, flash, flou et couleurs restent. Le kill tombe sur le temps qui ouvre son plan, la
coupe le précède de 0,2 s, et il reste au moins 0,15 s pour voir l'ennemi tomber (`montage.fast` : `minLead`,
`minTail`) : un temps par plan jusqu'à 171 BPM, deux au-delà. Durée visée : 0,4 s par kill (`perClip`).
Zoom d'approche (`fast.zoomApproach` 0,25, sur `fast.zoomApproachLead` 0,2 s ; `zoom.approach` hors rythme rapide, 0 par
défaut) : l'image se resserre vers le réticule pendant les 0,2 s qui précèdent chaque kill, puis retombe avec le punch.
En 21:9, l'ennemi n'est souvent que quelques pixels au fond d'un couloir : il grossit d'un quart au moment du tir.

- Mesuré sur R2D2 (161 BPM) avec deux parties : 45 kills en 19,6 s, 42 plans d'un temps (0,37 s), contre des plans de
  quatre temps (1,5 s, un kill toutes les 1,5 s) au rythme normal. Le marqueur de kill reste lisible à l'image.
- Un kill aussitôt suivi de la mort du joueur (moins de 0,6 s, `fast.skipDeathWithin`) est écarté : son plan finit
  0,17 s après le kill et ne montrait que l'écran de mort.
- Dans un dossier de musiques, celles qui gardent des plans d'un temps passent devant (`fast.musicPace`).
- `montage.fast.enabled: true` dans le profil pour l'avoir par défaut.

## Tempo : musiques lues au double

Le tempo est lu par autocorrélation des attaques, avec une préférence pour 120 BPM. Une musique lente aux croches
très régulières peut alors se lire sur ses croches : « Lose Yourself » sortait à 171 BPM pour 86, et passait au rythme
rapide après sa drop. Au-dessus de 140 BPM lus, l'analyse ramène le tempo à la noire quand deux choses se voient à la
fois (`MusicAnalyzer.halvesTempo`) :
- **rien ne bat entre deux temps lus**, dans aucune bande (basses < 220 Hz, médiums < 2,2 kHz, aigus) :
  l'autocorrélation de chaque bande au demi-temps lu reste ≤ −0,1. Une vraie musique rapide a quelque chose entre ses
  temps (charleston, caisse claire, basse) ;
- **la noire est presque aussi nette que le temps lu** (autocorrélation à deux temps lus ≥ 0,85 × celle du temps lu).

Mesuré sur les 19 musiques différentes de `D:\Musique` et de `music sample`. Le « réel » n'est noté que s'il est
établi : tempo publié par plusieurs sources, ou dit par l'utilisateur. Une mesure maison de la battue (grosse caisse et
caisse claire séparées, puis leur alternance) a été essayée : elle se trompait sur des musiques connues (« Fortunate
Son », « R2D2 »), donc elle ne sert pas de référence.

| Musique | Lu avant | Lu après | Réel | Source du réel | Entre deux temps / noire (lu > 140) |
|---|---|---|---|---|---|
| Eminem - Lose Yourself | 171,4 | **85,7** | 86 | piste de batterie de Karaoke-Version (85,7) ; plusieurs bases de données donnent 171, le même doublement | −0,26 / 0,96 → ramené |
| Creedence - Fortunate Son | 132,6 | 132,6 | 132 | analyses publiées (Université de Waterloo 132,7 ; AcousticBrainz 132-133) | — |
| Two Door Cinema Club - Undercover Martyn | 159,9 | 159,9 | 161 | audiokeychain (une seule source) | 0,32 / 0,78 |
| R2D2 | 161,4 | 161,4 | rapide | dit par l'utilisateur ; aucun BPM publié trouvé | 0,02 / 0,73 |
| GO2 - Spitfire | 157,0 | 157,0 | rapide | dit par l'utilisateur ; eurobeat (genre à 150-160) ; aucun BPM publié trouvé | 0,45 / 0,87 |
| Apashe - Rome Is Burning | 126,1 | 126,1 | rapide après la drop | dit par l'utilisateur ; aucun BPM publié trouvé | — |
| Apashe - Menace | 155,0 | 155,0 | non établi | aucun BPM publié trouvé ; rap sur trap, peut-être 77 (à écouter) | 0,12 / 1,12 |
| life kinda sucks | 187,6 | 187,6 | non établi | aucun BPM publié trouvé ; peut-être 94 (à écouter) | 0,24 / 0,72 |
| Meg & Dia - Monster (DotEXE Dubstep Remix) | 140,0 | 140,0 | non établi | dubstep, d'habitude publié à 140 et ressenti à 70 | — (139,7 à la grille de l'autocorrélation, sous 140) |
| BACK2BACK | 124,0 | 124,0 | non établi | — | — |
| HEAVENLY JUMPSTYLE (Slowed) | 111,2 | 111,2 | non établi | jumpstyle (~150) ralenti, cohérent | — |
| Hanumankind - Big Dawgs | 90,4 | 90,4 | non établi | aucun BPM publié trouvé | — |
| KEYGEN CHURCH - Tenebre Rosso Sangue | 88,1 | 88,1 | non établi | aucun BPM publié trouvé | — |
| MokuMoku - You Are Not Invited | 110,2 | 110,2 | non établi | aucun BPM publié trouvé | — |
| Nightcore - Snake Eyes | 131,4 | 131,4 | non établi | — | — |
| RUBIKDICE - MONTAGEM PEGADORA | 110,4 | 110,4 | non établi | aucun BPM publié trouvé | — |
| STORM I | 123,0 | 123,0 | non établi | — | — |
| Time Is Ticking | 101,7 | 101,7 | non établi | — | — |
| Doom Eternal - The Only Thing They Fear Is You | 103,0 | 103,0 | non établi | aucun BPM publié trouvé | — |

Deux boucles synthétiques des tests (grosse caisse à chaque temps, charleston entre) restent à 150 et 170 BPM
(entre deux temps : 0,61 et 0,80). Une seule musique est ramenée : le critère tient sur un seul exemple, d'où des
seuils loin des musiques gardées. Une musique rapide sans rien entre ses temps et à la noire très nette, une boucle de
grosse caisse seule par exemple, serait ramenée à tort.

Note du montage par défaut (WARDOGS, deux parties, ~10 kills), avant et après :

| Musique | BPM | Durée | Plans (rapides) | Note | sync | accent | durée remplie | creux | action |
|---|---|---|---|---|---|---|---|---|---|
| Lose Yourself avant | 171,4 | 22 s | 25 (10) | 0,837 | 1,00 | 0,51 | 0,40 | 1,00 | 0,97 |
| Lose Yourself après | 85,7 | 43 s | 20 (0) | 0,862 | 1,00 | 0,54 | 0,94 | 0,85 | 0,88 |
| Rome Is Burning avant / après | 126,1 | 16 s | 22 (13) | 0,833 / 0,833 | identiques | | | | |
| R2D2 avant / après | 161,4 | 16 s | 25 (13) | 0,813 / 0,813 | identiques | | | | |

Les plans restent calés sur les temps (sync 1,00). « Lose Yourself » n'a plus de partie rapide, et le montage remplit
sa durée cible (43 s contre 22). Il perd un peu en creux et en action : des plans plus longs à 86 BPM.
`MusicAnalyzer.VERSION` passe à 4 : les analyses gardées des bibliothèques de musiques sont refaites.

## Montage des morts

`montage --fails` (en ligne de commande seulement : retiré de l'application, jugé sans usage) monte les morts du
joueur au lieu de ses kills, sur la musique, avec le même moteur : un plan par mort, la mort sur un temps.

- Chaque plan montre les 3 secondes qui précèdent la mort, une image figée (0,7 s, son du jeu coupé, la musique
  continue) et un petit texte. Le ralenti qui finit sur la mort est réservé aux plus fortes, une sur trois (au moins
  une) : d'abord les échanges (le joueur venait de tuer), puis la mort sur la drop, puis les morts en plein combat
  (intensité du moment), puis le plan d'ouverture (`montage.fails.slowShare`, 1 = toutes) : OUPS, RIP, AÏE, GAME OVER, BIEN ESSAYÉ, RATÉ (`video.fail.*`,
  dans la langue de l'interface, ou `montage.fails.labels` du profil). Le texte d'une mort est tiré d'une empreinte de
  la mort (capture et instant) : le même montage garde les mêmes textes, et deux morts voisines n'ont jamais le même.
- Les morts sont les `death` du killfeed ou d'Outplayed. Dans Wardogs, « à terre » ne compte que si
  `montage.killStyle.downedAsDeath` est activé ; sinon une partie de Wardogs n'a pas de mort, et le montage le dit.
- Une mort juste après un kill (un échange) reste : elle est drôle aussi. Rounds, tirs à la tête, one taps et raccords
  sur la pose de l'arme ne servent pas ici.
- Fichiers : `<jeu>_<date>_fails.mp4`, à côté de son rapport (le texte de chaque mort y est : `failText`).

```powershell
& $app montage "Valorant- 44 kills.session.json" Valorant_game.session.json -m D:\Musique\R2D2.mp3 --fails -f 9:16
```

## Montage des réactions

Case « Réactions » du dialogue du montage (rubrique du rythme, réglages avancés) ou `montage --reaction-montage` : les
moments où le joueur réagit au micro (rire, cri, coup de gueule), quelle qu'en soit la cause (un kill, une mort, rien
du tout), montés sur la musique avec le même moteur : un plan par réaction, son début sur un temps.

- Il faut une piste micro séparée (Outplayed en écrit trois : mix, jeu, micro ; OBS sait le faire). Sans elle, le
  montage refuse plutôt que d'inventer des réactions (`error.montage.noMicTrack`). Dans `D:\Vidéo`, seules les captures
  WARDOGS en ont une ; les vidéos VALORANT, CS2, Apex et Fortnite n'ont qu'une piste.
- Les réactions viennent de la piste micro, décodée en entier (4 s pour 45 min) : les éclats de voix 10 dB au-dessus
  de la voix habituelle du joueur dans la partie (médiane des passages où il parle), plus les rires et cris reconnus par
  YAMNet. YAMNet seul n'en trouvait presque pas (un rire en 2 h 30 de WARDOGS : le joueur rit peu fort) ; les éclats en
  donnent 53, que whisper confirme : au-dessus de +20 dB ce sont des réactions (« oh la pute, ah fait chier », « waouh,
  putain ! », « c'est pas le moment ! »), entre +12 et +17 dB souvent de la conversation ou des annonces.
- Classement par la force de la réaction : montée au-dessus de la voix habituelle, durée, rire reconnu, et un kill, une
  mort ou une aide moins de 5 s avant (`montage.reactionMontage.causeBonus`). Deux réactions à moins de 8 s : la plus
  forte seule ; case « Espacer les réactions » décochée, `--no-reaction-spacing` ou `spacing: 0s` dans le profil :
  aucun écart minimal, deux réactions proches peuvent faire deux plans.
- Micro mixé par-dessus la piste du jeu (×2, +6 dB), musique en fond sonore (35 % de son volume, 12 % quand le joueur parle : mesurée 9 LU sous la voix sur tout le montage, 14 LU pendant les réactions ; `musicVolume`, `musicUnderVoice`), plan prolongé jusqu'à la fin
  de la phrase, et ce qui est dit en sous-titres (whisper, modèle de `edit.story.captions`, une phrase criée en rouge).
  Ni ralenti ni image figée (même si le profil active le montage des morts) : l'image continue pendant la réaction. Pas de plans d'un temps sur les parties rapides de la musique.
- Réglages : `montage.reactionMontage` du profil (`rise`, `preRoll`, `postRoll`, `perClip`, `spacing`, `micGain`,
  `musicUnderVoice`, `captions`…). Fichiers : `<jeu>_<date>_reactions.mp4` ; le rapport liste toutes les réactions
  trouvées (`reactions`, avec `used` pour celles montées).

```powershell
& $app montage WARDOGS_09-22-2026_22-55-31-983.session.json WARDOGS_09-23-2026_20-4-19-983.session.json -m D:\Musique\R2D2.mp3 --reaction-montage -f 9:16
```

## Montage onetaps

Case « Onetaps » du dialogue du montage kills, `montage --onetaps` ou `search --onetaps --montage` : que les kills
d'une balle à la tête, un plan chacun, enchaînés au rythme de la musique comme les edits TikTok. Les réglages du
montage kills restent ceux du profil, sauf ce que `montage.oneTaps` impose : un kill par plan, ni ralenti ni accroche,
et un kill par temps de musique. Dans un plan d'un temps (0,57 s à 105 BPM), le kill tombe sur le temps qui l'ouvre et
la coupe le précède de 0,25 s, prises sur la fin du plan précédent : on voit la visée se poser, l'impact tombe sur le
temps, l'ennemi a 0,3 s pour tomber. Au-delà de 109 BPM, un temps ne laisse plus assez de contexte : deux temps par plan.
Le kill est calé sur la balle vue au compteur de munitions quand la partie en a (Outplayed l'annonce jusqu'à 0,6 s
en retard : la balle tombait parfois sur la coupe).
La drop garde son plan d'élan, le premier plan en fait deux. Fichiers `…_onetaps.mp4`.

- Musique : dans un dossier, celles qui gardent des plans courts passent devant (`oneTaps.musicPace` : longueur
  médiane des plans de 0,55 s ou moins, +0,1 à la valeur de la musique ; rien à partir d'une seconde). Le journal
  l'affiche (« rythme +0,100 »).

- Les balles viennent du compteur de munitions (`ammo-counter`), à défaut des tirs entendus (`game-sounds`) : une partie
  analysée avant eux est à réanalyser. Une capacité ou le couteau (compteur immobile) n'est jamais un one tap. Un
  profil qui n'a ni l'un ni l'autre (WARDOGS) ne peut pas faire de montage onetaps, et le message le dit au lieu de
  proposer une réanalyse ; « aucun one tap » donne le nombre de kills trouvés.
- Sans tirs à la tête connus (ni Outplayed, ni icône du killfeed), tout kill d'une balle compte, et le journal le dit ;
  `montage.oneTaps.allowWithoutHeadshots: false` refuse plutôt.
- Peu de one taps : le montage dure ce que durent ses one taps (plus l'élan de la drop), sans durée minimale qui étire
  les plans (`oneTaps.min`, 1 s ; à 6 s, deux one taps à 130 BPM faisaient un second plan de 4 temps au lieu de 2).
  Sous `oneTaps.few` (3) one taps, le montage se fait et prévient qu'il sera très court.

## Aperçus : choisir parmi plusieurs versions du montage

Un rendu pleine qualité du montage kills prend plusieurs minutes : le juger à l'aveugle coûte cher. Bouton « Aperçu de
3 versions » du dialogue du montage, ou `montage … --variants 3` : trois versions du même montage sont rendues en petit
(360 pixels sur le petit côté, vertical d'abord), vite (x264 `ultrafast`, sans flou par vecteurs ni flou radial des
coupes, que le rendu final garde), dans
`<sortie>\apercus\`. Chaque plan y garde exactement son nombre d'images : coupes, kills sur les temps et son sont ceux
du rendu final. L'aperçu décode en logiciel, sans filtre de boucle H.264 (plus rapide ici que le décodage matériel,
dont chaque image pleine taille redescend de la carte graphique).

**Aperçus rapides** : deux choses les accélèrent.
- **Copies réduites (proxies)** : chaque passage de capture que lisent les aperçus est réduit une fois (à la taille
  que l'aperçu montre vraiment : 640 lignes pour WARDOGS 3440x1440 en 9:16), avec une image clé toutes les 0,5 s, et
  gardé dans `%TEMP%\highlights\cache\proxies` pour les aperçus suivants (autre musique, autre style). Mêmes
  horodatages d'images que la capture et son copié tel quel : coupes, kills et son ne bougent pas ; le cadrage (9:16,
  HUD recollé, zoom vers le réticule) se calcule en coordonnées relatives, donc juste sur la copie. Les copies d'une
  capture modifiée ou effacée sont jetées ; au-delà de 3 Go, les moins récemment servies partent. Une capture déjà
  petite (VALORANT 480p) est lue telle quelle. Le rendu final lit toujours les captures d'origine.
- **Rendus simultanés** : le graphe de filtres d'un montage n'occupe qu'un cœur ; les trois aperçus se rendent donc en
  même temps quand les images lues sont petites (copies réduites, captures ≤ 1080p). Dans l'application, la fenêtre des
  versions s'ouvre dès que la première est prête.

Mesuré (3 versions, « GO2 - Spitfire », sous le verrou de rendu ; détail dans `output\revue\apercu-rapide\`) :

| parties | avant | après, 1re fois | après, copies déjà faites |
|---|---|---|---|
| WARDOGS 22-55-31 (2 versions, 19 plans) | 34,7 s | 28,8 s | 15,0 s |
| WARDOGS 20-4-19 (3 versions, 4 plans) | 9,9 s | 8,5 s | 4,7 s |
| VALORANT « 44 kills » 480p (3 versions, ~50 plans) | 143,8 s | 71,0 s | 64,3 s |
| VALORANT « game » 480p (2 versions) | 32,7 s | 21,7 s | 21,6 s |

Depuis, le flou radial des coupes n'est plus rendu dans les aperçus (un `scale` recalculé à chaque image, un tiers du
temps d'un aperçu) : il ne couvre que 3 images de part et d'autre d'une coupe, et le rendu final le garde. Mesuré
ensuite (copies déjà faites, détail dans `output\revue\apercu-sans-flou\`) : WARDOGS 22-55-31 14,8 s → 9,5 s,
VALORANT « 44 kills » 52,1 s → 28,4 s. La version choisie se rend toujours : l'empreinte ne voit pas les effets.

- **Les versions** : celles que le planificateur note déjà (échelle de la grille de coupes × place de la drop) et le
  plan de base, classées comme il les classe : la version 1 est celle qu'un montage sans aperçu aurait rendue. Les
  suivantes doivent s'écarter d'au moins 0,25 de toutes les précédentes : un quart des plans changent de clip ou de
  place, ou un quart des coupes tombent ailleurs (à un temps près), ou la musique part d'un quart de montage plus loin.
  Moins de trois versions quand les autres se ressemblent trop.
- **Choisir** : dans l'application, chaque version a sa vignette animée, « Lire » (lecteur intégré, lecteur du système en secours) et « Rendre
  celle-ci ». En ligne de commande, la même commande avec `--pick <n>` à la place de `--variants` (fichier des
  versions : le plus récent pour ces parties et cette musique, ou `--variants-file`). Rien n'est réanalysé ; le montage
  recalculé doit avoir la même empreinte que l'aperçu regardé, sinon il est refusé (réglages ou analyses changés).
- **Fichiers** : `…_killmontage_variantes.json` (chaque version : réglage, empreinte, note critère par critère, écart
  aux précédentes, aperçu) et, à côté du rapport du rendu final, `….choix.json` (version choisie, notes de toutes) :
  de quoi apprendre plus tard ce qui fait préférer une version.

```powershell
& $app montage output\sessions\WARDOGS_*.session.json --music "D:\Musique\R2D2.mp3" --variants 3
& $app montage output\sessions\WARDOGS_*.session.json --music "D:\Musique\R2D2.mp3" --pick 2
```

## Goûts appris : la note du montage s'ajuste aux choix

Chaque fois qu'une version est rendue après les aperçus (`--pick`, « Rendre celle-ci »), le choix est gardé dans
`<sortie>\sessions\taste-history.json` : les critères de la note de chaque version proposée (sur le temps, accent,
sobriété, variété, durée, rythme, images gelées, ouverture, trous, action) et celle qui a été rendue. Les corrections de
l'éditeur de plan (kills écartés, forcés, épinglés) y sont gardées aussi, avec les sortes de chaque groupe proposé.

Dès 10 choix (`montage.learnTaste`, actif par défaut), les poids de ces critères s'ajustent pour que la
version rendue batte les autres (préférence par paires) : un critère que vous préférez plus que le moteur ne le pensait
pèse plus, et décide de la version 1, des versions proposées et du montage rendu sans aperçu. Prudent : chaque poids
reste entre le quart et le quadruple de l'origine, leur somme ne change pas, et un rappel vers l'origine empêche
quelques choix de tout renverser. Tant qu'il y a moins de 10 choix, rien ne change : même plan, même note qu'avec
les poids d'origine. Pour revenir aux poids d'origine : `app gouts --reset` ; pour ne jamais apprendre :
`montage.learnTaste: false` dans le profil.

```powershell
& $app gouts            # « actif, N choix sur 10 avant d'apprendre », choix gardés, poids appris contre poids d'origine, et ce que ça changerait au dernier choix
& $app gouts --reset    # oublier : l'historique est mis de côté (taste-history.reset-<date>.json), pas effacé
```

Démonstration sur données synthétiques (aucun vrai choix encore) : un goût caché qui tient 4 fois plus à l'action et
3 fois plus à l'ouverture, 4 fois moins à la sobriété, 10 % de choix au hasard. Après 25 choix : action +37 %,
ouverture +13 %, sobriété −30 % ; sur 2000 choix tenus à l'écart, la version préférée est la mieux notée 83,6 % des fois
contre 79,5 % avec les poids d'origine. Après 100 choix : action +59 %, ouverture +23 %, sobriété −29 %.

**Sortes de kills préférées (corrections de l'éditeur).** À chaque montage rendu avec des corrections, chaque groupe
proposé est gardé avec ses sortes (multi-kill, tout à la tête, one tap, flick, ace, clutch) et ce que vous
en avez fait : écarté (−1), gardé de force ou épinglé (+1), rien (0). Dès 5 montages corrigés, une sorte qui compte au
moins 3 groupes écartés ou gardés reçoit un bonus de rang : sa moyenne moins celle de tous les groupes (un utilisateur
qui écarte beaucoup ne déteste pas toutes les sortes pour autant), ×1,5, borné à ±0,75 kill par sorte et ±1 kill par
groupe, nul sous 0,2 kill (bruit : une sorte neutre sort entre −0,18 et +0,12 sur la démonstration). Un flick préféré passe donc devant un kill seul quand la place manque, jamais devant un groupe qui a un kill de
plus. Comptage simple, déterministe ; la distance du kill n'en fait pas partie (pas mesurée). `app gouts` les liste ;
les corrections d'avant cette version (sans les groupes) restent gardées mais ne sont pas apprises.

**Dans l'application** : bouton « Goûts appris » dans la liste des analyses. Une barre par critère (écart à son poids
d'origine) et par sorte de kill (préférée, évitée, pas de préférence, trop peu de corrections), grisées tant que
l'apprentissage n'a pas assez de choix, et « Oublier ce qui a été appris » (après confirmation, comme `--reset`).

## Ligne de commande

```powershell
$app = ".\app-cli\build\install\app\bin\app.bat"

& $app doctor                                     # état de l'installation (voir plus haut)
& $app encoders                                   # encodeurs utilisables sur cette machine
& $app probe "D:\Videos\Outplayed\League of Legends\partie.mp4"   # pistes audio, résolution, durée
& $app music D:\Musique\son.mp3 --max 60s --clips 12         # tempo, sections, drop et grille de coupes d'un montage
& $app music D:\Musique\TikTok                         # analyse d'avance chaque musique d'un dossier (bibliothèque)
& $app publish output\partie_killmontage_9x16.mp4 --dry-run   # titre, description et tags proposés pour YouTube
& $app thumbnail output\partie_killmontage_9x16.mp4           # miniature YouTube d'une vidéo exportée
& $app stats --games                                  # statistiques par soirée, partie par partie
& $app gouts                                         # goûts appris des choix de variantes (voir plus haut)
& $app search --clutch --montage D:\Musique            # moments cherchés dans toutes les parties, et leur montage
& $app process "D:\Videos\...\partie.mp4"         # analyse + montage
& $app process partie.mp4 --profile valorant --duration 5m --format 16:9,9:16
& $app process partie1.mp4 partie2.mp4 partie3.mp4 --top 10   # toute une soirée : un seul montage, parties dans l'ordre joué
& $app analyze partie.mp4                         # analyse seule → output\sessions\partie.session.json
& $app export output\sessions\partie.session.json # ré-export après avoir passé "enabled": false sur des segments
& $app export output\sessions\partie1.session.json output\sessions\partie2.session.json   # ré-export de plusieurs parties
```

Options globales : `--config <app.yaml>`, `-v` (logs détaillés : commandes FFmpeg et stderr).
Journal : `%USERPROFILE%\.highlights\logs\highlights.log`.

Sans installation : `.\gradlew.bat :app-cli:run --args="process D:\chemin\partie.mp4"`.

## Sorties

Dans `outputDir` (par défaut `output\` à la racine du projet) :

- `lol_2026-09-17_highlights.mp4` et `lol_2026-09-17_highlights_9x16.mp4` ;
- `lol_2026-09-17_highlights.json` : timestamps, score et contribution de chaque signal pour les segments retenus et ignorés ;
- `sessions\<source>.session.json` : timeline complète des scores, relue par `export` (et plus tard par l'interface).

Un fichier existant n'est jamais écrasé (suffixe `_2`, `_3`…), et les sources ne sont jamais modifiées.

## Configuration

- `config/app.yaml` : FFmpeg, dossiers, préférence d'encodeur, parallélisme.
- `config/montage-styles.yaml` : styles du montage kills (`--style`, puces de la fenêtre de montage) et style par défaut.
- `config/export-presets.yaml` : préréglages d'export (`--preset`, puces de la barre d'export et de la fenêtre de montage).
- `config/profiles/*.yaml` : un fichier par jeu. Il définit les fenêtres d'analyse, les détecteurs (type, poids, normalisation, paramètres), la sélection (seuil, marges, durée cible) et le montage. Le profil est choisi d'après le chemin du fichier (`match.pathContains`), sinon c'est `default`. `--profile` force un profil.

Pistes audio : un détecteur demande un rôle (`role: game`, `role: mic`…) et non un numéro de piste ; l'index reste
possible (`stream: 2`) quand la capture sort de l'ordinaire. Quand une piste manque (pas de micro séparé), son poids est
redistribué. `app probe` montre les pistes d'un fichier et le rôle déduit pour chacune.

Zones d'image (`hud-template`, `ocr-log`, `edit.vertical`) : les coordonnées sont normalisées (0..1). En déclarant la
capture sur laquelle elles ont été mesurées (`referenceWidth` et `referenceHeight`, ou `vertical.reference`), elles sont
converties pour un autre format d'écran ; `anchor` (`left`, `center`, `right`, `auto`) dit à quel bord l'élément est
accroché, ce qui est deviné par défaut d'après sa position.

## Architecture

| Module | Rôle |
|---|---|
| `core` | Modèle, interfaces (`SignalDetector`, `FfmpegService`, `EncoderSelector`…), config YAML, progression + ETA, session, décodage vidéo partagé (`FrameSampler`), FFT |
| `ffmpeg` | Exécution FFmpeg/ffprobe via ProcessBuilder, détection des encodeurs |
| `analysis` | Détecteurs audio et Outplayed (`audio-loudness`, `voice-activity`, `game-sounds`, `outplayed-events`), enregistrés par `ServiceLoader` |
| `analysis-vision` | Détecteurs d'image (`hud-template`, `killfeed`, `ocr-log` via l'OCR de Windows) |
| `analysis-ml` | Rires et exclamations (`audio-events`, YAMNet via ONNX Runtime) |
| `scoring` | Normalisation par percentiles, fusion pondérée, sélection des moments |
| `editing` | Plan de montage et graphe de filtres FFmpeg (une entrée par clip, xfade, loudnorm, recadrage) |
| `montage` | Montage kills : analyse musicale (tempo, temps, sections, drop), grille de coupes, planification et rendu |
| `export` | Nommage, rendu, rapport JSON |
| `pipeline` | Orchestration par coroutines, indépendante de toute interface |
| `app-cli` | Commandes Clikt |
| `app-ui` | Application de bureau Compose, installeur MSI |

Ajouter un détecteur : implémenter `SignalDetectorFactory`, déclarer la classe dans `META-INF/services/dev.highlights.core.analysis.SignalDetectorFactory`, puis la référencer par son `type` dans un profil.

Un détecteur qui lit des images déclare ses zones dans `prepare()` auprès de `ctx.frames` (`FrameSampler`) plutôt que de lancer son propre FFmpeg : tous ceux qui demandent la même cadence se partagent alors un seul décodage de la capture, et deux zones identiques ne sont découpées qu'une fois.

## Licence

Highlights est un logiciel libre sous **GNU GPL version 3 ou ultérieure** ([`LICENSE`](LICENSE)). Les composants livrés
avec l'installeur (FFmpeg, le runtime Java, YAMNet, les bibliothèques) et leurs licences sont listés dans
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), textes complets dans [`licenses/`](licenses/). L'installeur et
la version portable embarquent ces fichiers dans `app/resources`, ainsi que `ffmpeg/VERSION.txt` (la version exacte
du FFmpeg livré, pour en fournir les sources). Une bibliothèque ajoutée sans sa ligne dans les notices fait échouer
la construction de l'installeur (`checkThirdPartyNotices`).
