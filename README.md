# Highlights

Transforme des captures de gameplay brutes (Outplayed, OBS) en montages courts des meilleurs moments, sans intervention manuelle.

État actuel : **MVP en CLI**, avec un détecteur audio (loudness EBU R128) et un export 16:9 / 9:16 encodé par AMF.

## Prérequis

- JDK 21
- FFmpeg avec AMF : `winget install Gyan.FFmpeg`. Il est détecté automatiquement (PATH puis installation winget) ; sinon renseigner `ffmpeg.ffmpegPath` dans `config/app.yaml`.

## Construire et tester

```powershell
.\gradlew.bat test                    # unitaires + intégration (vidéos synthétiques générées par FFmpeg)
.\gradlew.bat :app-cli:installDist    # → app-cli\build\install\app\bin\app.bat
```

## Interface graphique

```powershell
.\gradlew.bat :app-ui:createDistributable   # → app-ui\build\compose\binaries\main\app\Highlights\Highlights.exe
.\gradlew.bat :app-ui:run                   # lancement direct pendant le développement
```

L'exécutable lit `config/` dans le projet : une modification de profil s'applique sans reconstruire, via « Recharger » ou « Régénérer » dans l'aperçu 9:16.
`.\gradlew.bat :app-ui:packageMsi` produit un installeur MSI.

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
  sur la fenêtre de la musique la plus intéressante (montée puis drop vers 40 %).
- **Clips taillés pour la musique** : chaque clip est étendu ou coupé pour remplir exactement son plan ; le dernier kill
  tombe sur le temps le plus accentué du plan, le meilleur groupe (multi-kill) exactement sur la drop ; les multi-kills
  et les réactions (voix, rires) fusionnent des plans voisins ; l'image est gelée si la vidéo manque. Quand il y a peu de
  kills, les plans s'allongent plutôt que de laisser de la musique inutilisée.
- **Chaque kill sur un temps** : dans un multi-kill, la lecture entre deux kills est accélérée ou ralentie de ±15 % au
  plus (speed ramp, `--no-ramp` pour désactiver) pour que chaque kill tombe sur un temps ; dans une suite de plans de
  même longueur, le kill tombe toujours au même endroit du plan (élan).
- **Effets** (désactivables : `--no-zoom`, `--no-flash`, `--no-slowmo`, `--no-text`) : zoom punch, flash blanc aux
  coupes, ralenti ×0,5 sur le kill (seulement s'il tient dans le plan), textes « DOUBLÉ / TRIPLÉ » et compteur de kills,
  fondu au noir final.
- **Son** : musique au premier plan ; le jeu remonte sur les kills ; voix et rires restent audibles, et la musique baisse
  pendant qu'on les entend ; le son d'un kill ou d'une phrase déborde un peu sur le plan suivant (`audio.bleed`).
- Réglages détaillés : section `montage:` d'un profil (voir `core/.../model/Montage.kt`), notamment `cuts:` (durées
  visées par intensité `low`/`mid`/`high`, `maxBeats`, `minLead`/`minTail`, `dropPosition`).

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
| `core` | Modèle, interfaces (`SignalDetector`, `FfmpegService`, `EncoderSelector`…), config YAML, progression + ETA, session |
| `ffmpeg` | Exécution FFmpeg/ffprobe via ProcessBuilder, détection des encodeurs |
| `analysis` | Détecteurs (`audio-loudness`), enregistrés par `ServiceLoader` |
| `scoring` | Normalisation par percentiles, fusion pondérée, sélection des moments |
| `editing` | Plan de montage et graphe de filtres FFmpeg (une entrée par clip, xfade, loudnorm, recadrage) |
| `montage` | Montage kills : analyse musicale (tempo, temps, sections, drop), grille de coupes, planification et rendu |
| `export` | Nommage, rendu, rapport JSON |
| `pipeline` | Orchestration par coroutines, indépendante de toute interface |
| `app-cli` | Commandes Clikt |

Ajouter un détecteur : implémenter `SignalDetectorFactory`, déclarer la classe dans `META-INF/services/dev.highlights.core.analysis.SignalDetectorFactory`, puis la référencer par son `type` dans un profil.
