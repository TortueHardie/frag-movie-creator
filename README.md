# Highlights

Transforme des captures de gameplay brutes (Outplayed, OBS) en montages courts des meilleurs moments, sans intervention manuelle.

Deux interfaces sur le même moteur : une **application de bureau** (Compose) et une **ligne de commande**.

- **Détection des moments** : volume du jeu (EBU R128), prises de parole et rires (YAMNet), événements enregistrés par
  Outplayed (kills, morts, assistances), icônes du HUD et journal des gains lu par OCR (Wardogs).
- **Highlights** : les meilleurs moments fusionnés et exportés en 16:9 et/ou 9:16, encodés par AMF (repli libx264).
- **Montage kills** : tous les kills calés sur une musique (temps, sections, drop), avec effets, style TikTok.
- **Profils par jeu** (`config/profiles/`) : LoL, VALORANT, Wardogs, et un profil par défaut.

## Prérequis (développement)

Pour utiliser l'application sans rien installer d'autre, voir [Installer l'application](#installer-lapplication-autre-ordinateur).

- JDK 21
- FFmpeg avec AMF : `winget install Gyan.FFmpeg`. Il est détecté automatiquement (PATH puis installation winget) ; sinon renseigner `ffmpeg.ffmpegPath` dans `config/app.yaml`.

## Construire et tester

```powershell
.\gradlew.bat test                    # unitaires + intégration (vidéos synthétiques générées par FFmpeg)
.\gradlew.bat :app-cli:installDist    # → app-cli\build\install\app\bin\app.bat
```

## Installer l'application (autre ordinateur)

```powershell
.\gradlew.bat :app-ui:packageMsi   # → app-ui\build\compose\binaries\main\msi\Highlights-<version>.msi
```

Le MSI (≈ 340 Mo) contient tout : Java, FFmpeg (avec AMF, repli libx264), le modèle YAMNet, les profils et les
modèles d'images. Sur l'autre PC, un double-clic suffit : l'installation se fait dans le profil de l'utilisateur, sans
droits administrateur, et crée un raccourci sur le bureau et dans le menu Démarrer. Windows 10 ou 11 64 bits.

- **Configuration** : au premier lancement, la config livrée est copiée dans `%APPDATA%\Highlights\config`, où les
  profils se modifient (puis « Recharger » dans l'application). Une mise à jour de l'application apporte les nouveaux
  fichiers et remplace ceux qui n'ont pas été retouchés ; les profils modifiés sont conservés.
- **Montages** : écrits dans `Vidéos\Highlights` (`outputDir` dans `app.yaml` ; `~/` désigne le dossier de l'utilisateur).
- **FFmpeg** : celui de l'installeur est utilisé en priorité (sauf `ffmpeg.ffmpegPath` dans `app.yaml`). À la
  construction, il est repris de l'installation winget `Gyan.FFmpeg`, ou de `-PffmpegDir=<dossier contenant bin\ffmpeg.exe>` ;
  sans lui, `packageMsi` échoue. Sa licence (GPL) est livrée à côté, dans `resources\ffmpeg`.
- **Version** : `highlights.version` dans `gradle.properties`. À augmenter à chaque nouvel installeur, sinon Windows
  refuse la mise à jour ; l'ancienne version est remplacée automatiquement.
- Pour qu'une application installée lise la config du projet : variable d'environnement `HIGHLIGHTS_CONFIG=D:\...\config\app.yaml`.

## Interface graphique (développement)

```powershell
.\gradlew.bat :app-ui:run   # lit config/ du projet : une modification de profil s'applique sans reconstruire
```

Via « Recharger », ou « Régénérer » dans l'aperçu 9:16.

Dans l'application :
1. choisir ou glisser une capture ;
2. régler le profil, les formats, la durée et le seuil ;
3. cliquer sur « Analyser » ;
4. relire les segments : timeline, vignettes, « ▶ Revoir » ouvre un extrait dans le lecteur, « 9:16 » affiche l'aperçu vertical avec le HUD ;
5. décocher les segments à retirer (le seuil et la durée se recalculent sans réanalyser) ;
6. cliquer sur « Exporter le montage ».

## Montage « tous les kills » (style TikTok)

Dans l'application : bouton **Montage kills** au-dessus de la liste des moments (après une analyse avec un profil qui
détecte les kills). En ligne de commande :

```powershell
& $app montage output\sessions\partie.session.json --music D:\Musique\son.mp3 --max 60s --format 9:16,source
```

- **Musique** : analysée entièrement (`app music son.mp3` pour voir le résultat) : tempo et temps (suivi à ~12 ms, sans
  dérive), mesures, **sections** (intro, montée, drop, breakdown… par timbre et volume, alignées sur les mesures) avec
  leur intensité, et la **drop** (plus gros saut d'intensité).
- **Coupes dictées par la musique** : la longueur des plans dépend de la section (courts dans les parties intenses, longs
  dans les calmes, toujours des mesures entières), les frontières de sections sont des coupes, et le montage est placé
  sur la fenêtre de la musique la plus intéressante (montée puis drop vers 40 %). Chaque coupe tombe une image avant son
  temps (`cuts.preBeatFrames`) — l'œil met quelques images à enregistrer un nouveau plan — sans déplacer le kill.
- **Choix et ordre des clips** : deux plans voisins ne viennent pas du même moment de la même partie (`varietyGap`), et
  `minScore` permet d'écarter les groupes de kills trop faibles quitte à raccourcir le montage (0 = tout garder ; les
  trois meilleurs sont gardés quoi qu'il arrive). Les meilleurs groupes restants terminent naturellement le montage.
- **Clips taillés pour la musique** : chaque clip est étendu ou coupé pour remplir exactement son plan ; le dernier kill
  tombe sur le temps le plus accentué du plan, le meilleur groupe (multi-kill) exactement sur la drop ; les multi-kills
  et les réactions (voix, rires) fusionnent des plans voisins ; l'image est gelée si la vidéo manque. Quand il y a peu de
  kills, les plans s'allongent plutôt que de laisser de la musique inutilisée.
- **Chaque kill sur un temps** : dans un multi-kill, la lecture entre deux kills est accélérée ou ralentie de ±15 % au
  plus (speed ramp, `--no-ramp` pour désactiver) pour que chaque kill tombe sur un temps ; dans une suite de plans de
  même longueur, le kill tombe toujours au même endroit du plan (élan).
- **Accroche** : le meilleur groupe après celui de la drop ouvre le montage (`--no-hook`) : c'est dans les premières
  secondes que le spectateur décide de rester.
- **Effets** (désactivables : `--no-zoom`, `--no-flash`, `--no-slowmo`, `--no-text`) : zoom punch, ralenti ×0,5 sur le
  kill (seulement s'il tient dans le plan), textes « DOUBLÉ / TRIPLÉ », fondu au noir final. Le ralenti s'installe par
  paliers avant le kill et le plein régime revient exactement sur un temps, au lieu d'un changement de vitesse net.
  Le flash blanc ne tombe qu'aux coupes fortes — nouvelle section, drop, multi-kill (`--flash-every-cut` pour toutes).
- **Son** : musique au premier plan ; le jeu remonte sur les kills ; voix et rires restent audibles, et la musique baisse
  pendant qu'on les entend ; tous ces changements de volume montent et descendent en fondu (`audio.duckAttack`,
  `audio.duckRelease`), sinon la marche s'entend plus que ce qu'elle met en avant ; le son d'un kill ou d'une phrase
  déborde un peu sur le plan suivant (`audio.bleed`).
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

& $app encoders                                   # vérifie qu'AMF fonctionne
& $app probe "D:\Videos\Outplayed\League of Legends\partie.mp4"   # pistes audio, résolution, durée
& $app music D:\Musique\son.mp3 --max 60s --clips 12         # tempo, sections, drop et grille de coupes d'un montage
& $app process "D:\Videos\...\partie.mp4"         # analyse + montage
& $app process partie.mp4 --profile valorant --duration 5m --format 16:9,9:16
& $app analyze partie.mp4                         # analyse seule → output\sessions\partie.session.json
& $app export output\sessions\partie.session.json # ré-export après avoir passé "enabled": false sur des segments
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

Pistes audio : `game-audio` lit la piste 0, `mic-audio` la piste 1 si elle existe (`optional: true`). Quand une piste manque, son poids est redistribué. `app probe` indique les pistes présentes dans un fichier.

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
