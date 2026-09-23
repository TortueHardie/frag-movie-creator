# Highlights

Transforme des captures de gameplay brutes (Outplayed, OBS, ShadowPlay…) en montages courts des meilleurs moments, sans intervention manuelle.

Deux interfaces sur le même moteur : une **application de bureau** (Compose) et une **ligne de commande**.

- **Détection des moments** : volume du jeu (EBU R128), prises de parole et rires (YAMNet), événements enregistrés par
  Outplayed (kills, morts, assistances), icônes du HUD et journal des gains lu par OCR (Wardogs).
- **Highlights** : les meilleurs moments fusionnés et exportés en 16:9 et/ou 9:16, encodés par le GPU (AMD, NVIDIA,
  Intel) avec repli logiciel.
- **Étalonnage** (`edit.grade` d'un profil, pour les highlights comme pour le montage) : table de correspondance
  `.cube` (chemin relatif au dossier de configuration), saturation, contraste et vignettage. Sert à unifier des
  captures venues de sessions ou de jeux différents. Neutre tant qu'on n'y touche pas.
- **Montage kills** : tous les kills calés sur une musique (temps, sections, drop), avec effets, style TikTok.
- **Profils par jeu** (`config/profiles/`) : LoL, VALORANT, Wardogs, et un profil par défaut.

## Installer l'application (Windows)

Rien à compiler ni à installer d'autre. Dans [les releases du dépôt](../../releases/latest), prendre :

- **`Highlights-<version>.msi`** : double-clic, l'installation se fait dans le profil de l'utilisateur
  (`%LOCALAPPDATA%\Highlights`), sans droits administrateur, avec un raccourci sur le bureau et dans le menu Démarrer ;
- **`Highlights-<version>-portable.zip`** : la même application sans installation, à décompresser puis lancer
  `Highlights\Highlights.exe` (clé USB, poste verrouillé…).

Windows 10 ou 11 64 bits. Tout est inclus : Java, FFmpeg, le modèle YAMNet et les profils de jeu ; ni Outplayed ni
carte graphique particulière ne sont nécessaires.

L'installeur n'étant pas signé, Windows affiche « Windows a protégé votre ordinateur » : « Informations
complémentaires », puis « Exécuter quand même ». (Signer demanderait un certificat payant.)

**Mettre à jour** : lancer le MSI de la nouvelle version, rien d'autre. Il remplace l'installation existante au lieu de
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

- **Outplayed ou pas** : les événements du jeu (kills, morts) viennent d'Outplayed quand il est là ; sinon ce signal est
  simplement absent, les autres (volume, voix, rires, icônes du HUD) continuent et leur poids est redistribué.
- **Pistes audio** : elles sont désignées par leur rôle (`game`, `mic`, `mix`), pas par leur numéro. Outplayed en écrit
  trois (mix, jeu, micro), OBS une ou deux (jeu puis micro) : la bonne piste est trouvée dans les deux cas, d'après les
  titres des pistes puis leur nombre. Le montage garde tout le son sans le doubler : la piste de mix si elle existe,
  sinon toutes mélangées. Pour imposer des index : `audio: { game: 1, mic: 2 }` dans le profil.
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

Dans l'application :
1. choisir ou glisser une ou plusieurs captures (« Ajouter des vidéos » pour compléter la liste) ;
2. régler le profil, les formats, la durée et le seuil ;
3. cliquer sur « Analyser » ;
4. relire les segments : timeline, vignettes, « ▶ Revoir » ouvre un extrait dans le lecteur, « 9:16 » affiche l'aperçu vertical avec le HUD ;
5. décocher les segments à retirer (le seuil et la durée se recalculent sans réanalyser) ;
6. cliquer sur « Exporter le montage ».

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
- **Libellés** « DOUBLÉ », « TRIPLÉ », « QUADRUPLÉ », « ACE » au kill qui prolonge une série (kills à moins de 5 s).
- **Musique de fond** facultative (`story.music.file` ou `--music`) : baissée sous la voix, coupée net sur le pic de
  chaque moment puis relancée en fondu.
- **Sous-titres de la voix** : le micro est transcrit en local par whisper.cpp (filtre `whisper` de FFmpeg), deux à
  quatre mots à la fois, en gros, qui apparaissent d'un coup de zoom. Les mots forts (« GG », « ACE », jurons…,
  `story.captions.emphasis`) passent en jaune, une phrase criée en rouge et plus grosse. Seul ce qui recouvre une prise de parole détectée
  est gardé (whisper invente du texte sur le silence). La transcription est mise en cache : un nouvel export des mêmes
  moments ne la refait pas. Il faut le modèle, trop lourd pour le dépôt et l'installeur :
  [`ggml-large-v3-turbo-q5_0.bin`](https://huggingface.co/ggerganov/whisper.cpp) (~574 Mo) dans `config/models/`
  (`story.captions.model`). Sans lui, le montage sort sans sous-titres.
- Son adouci à chaque coupe, fondu au noir final, normalisation EBU R128.

```powershell
& $app export output\sessions\partie.session.json --style story --music D:\Musique\fond.mp3 -f source,9:16
```

## Montage « tous les kills » (style TikTok)

Dans l'application : bouton **Montage kills** au-dessus de la liste des moments (après une analyse avec un profil qui
détecte les kills). En ligne de commande :

```powershell
& $app montage output\sessions\partie.session.json --music D:\Musique\son.mp3 --max 60s --format 9:16,source
```

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
  sur la fenêtre de la musique la plus intéressante (montée puis drop vers 40 %). Chaque coupe tombe une image avant son
  temps (`cuts.preBeatFrames`) — l'œil met quelques images à enregistrer un nouveau plan — sans déplacer le kill.
- **Choix et ordre des clips** : deux plans voisins ne viennent pas du même moment de la même partie (`varietyGap`), et
  `minScore` permet d'écarter les groupes de kills trop faibles quitte à raccourcir le montage (0 = tout garder ; les
  trois meilleurs sont gardés quoi qu'il arrive). Les meilleurs groupes restants terminent naturellement le montage.
- **Clips taillés pour la musique** : chaque clip est étendu ou coupé pour remplir exactement son plan ; le dernier kill
  tombe sur le temps le plus accentué du plan, le meilleur groupe (multi-kill) exactement sur la drop ; les multi-kills
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
- **Variantes** (`variants`) : une douzaine de plans sont calculés (échelle de la grille, place de la drop) et seul le
  mieux noté est rendu, à condition de garder presque tous les clips du plan de base et de le battre nettement.
- **Accroche** : le meilleur groupe après celui de la drop ouvre le montage (`--no-hook`) : c'est dans les premières
  secondes que le spectateur décide de rester.
- **Une emphase par plan** (`effectDensity`, `--effects sober|balanced|heavy`) : au rythme normal, un plan reçoit un
  ralenti *ou* un zoom, jamais les deux. Le ralenti va aux plans forts — drop, multi-kill visible à l'écran, accroche —
  et les autres prennent le zoom. `sober` ne garde que le ralenti de la drop, `heavy` remet tout partout.
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
  et la musique baisse dessous pour le laisser passer (`audio.musicUnderKill`). Juste avant la drop, la musique se tait
  un temps (`audio.dropBreak` : `beats`, `musicLevel`) : le jeu reste seul un instant, puis la drop repart d'un coup sur
  le meilleur kill.
- **Note du montage** : chaque rapport porte une note qui mesure ce que le moteur prétend faire — kills sur un temps,
  temps accentués, sobriété des effets, variété des clips, durée occupée, plans plus courts dans les sections intenses,
  absence d'image gelée. Elle ne dit pas si un montage est beau ; elle sert à comparer deux versions du moteur sans les
  regarder l'une après l'autre. Un critère qu'on ne peut pas mesurer sur un montage donné (`-` à l'affichage) sort de la
  moyenne au lieu d'y entrer à 1.

  ```powershell
  & $app score output\a\partie_killmontage.json output\b\partie_killmontage.json
  ```

- Réglages détaillés : section `montage:` d'un profil (voir `core/.../model/Montage.kt`), notamment `cuts:` (durées
  visées par intensité `low`/`mid`/`high`, `maxBeats`, `minLead`/`minTail`, `dropPosition`).

## Événements de jeu (Outplayed)

`outplayed-events` reprend les événements qu'Outplayed a enregistrés avec la capture : kills, morts, assistances,
headshots… transmis par le jeu lui-même via Overwolf, sans aucune analyse d'image. Ils sont lus en lecture seule dans
la base de l'application (`%LOCALAPPDATA%\Overwolf\CefBrowserCache\…\IndexedDB`), même pendant qu'Outplayed tourne.
La capture est retrouvée par son chemin, ou par son nom de fichier si elle a été déplacée. Pour une capture qui ne vient
pas d'Outplayed (OBS…), le signal est simplement absent.

- `kinds` choisit les types retenus (ex. `kill: kill`) ; `offsets` corrige le retard de l'événement (VALORANT : −390 ms,
  mesuré sur une partie complète : l'instant tombe alors sur l'image où le kill apparaît dans le killfeed).
- Sur une partie VALORANT de 50 min : 22 kills, 22 morts, 8 assistances, exactement le tableau de fin.

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
- Sur une partie de 1 h 25 : 14 kills sur 14, 3 aides sur 3, 8 réanimations sur 10, aucun faux positif (les icônes
  seules : 12 kills et un faux, aucune aide, 4 réanimations). Environ 1 min d'analyse pour 1 h 53 de vidéo.

## Voix et rires

- `voice-activity` : prises de parole sur la piste micro. Avec `selection.keepWhole: [speech, laughter]`, un moment
  n'est jamais coupé au milieu d'une phrase ou d'un rire (extension jusqu'à `maxExtension`).
- `audio-events` : rires et exclamations détectés par YAMNet (Google, hors ligne, `config/models/`), qui deviennent
  des moments à garder (`eventBoosts`). Le journal indique les meilleurs scores pour régler les seuils.

## Ligne de commande

```powershell
$app = ".\app-cli\build\install\app\bin\app.bat"

& $app doctor                                     # état de l'installation (voir plus haut)
& $app encoders                                   # encodeurs utilisables sur cette machine
& $app probe "D:\Videos\Outplayed\League of Legends\partie.mp4"   # pistes audio, résolution, durée
& $app music D:\Musique\son.mp3 --max 60s --clips 12         # tempo, sections, drop et grille de coupes d'un montage
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
| `analysis` | Détecteurs audio et Outplayed (`audio-loudness`, `voice-activity`, `outplayed-events`), enregistrés par `ServiceLoader` |
| `analysis-vision` | Détecteurs d'image (`hud-template`, `ocr-log` via l'OCR de Windows) |
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
