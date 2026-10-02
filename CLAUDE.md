# CODEMAP — Highlights (carte pour l'IA, pas pour un humain)

État cartographié : v1.5.1 + branche `montage-references` (2026-10-02). Les symboles sont plus stables que les numéros de ligne :
chercher par nom (Grep). Si un symbole cité ici n'existe plus, la carte est périmée sur ce point → relire le code et
mettre cette carte à jour.

## 0. Quoi
App Windows (Kotlin/JVM 21, Gradle multi-modules) : captures de gameplay (Outplayed/OBS/ShadowPlay) → détection des
meilleurs moments → montages vidéo via FFmpeg (process externe). 3 sorties :
1. **highlights simple** (moments bout à bout, fondu) ; 2. **highlights story** (façon YouTube : jump cuts, accroche,
punch-in, secousse, sous-titres whisper, SFX, musique de fond) ; 3. **montage kills** (TikTok, calé sur une musique :
tempo/sections/drop, ralentis, rampes, whip pan, raccords sur la pose de l'arme).
2 frontends sur le même moteur `HighlightPipeline` : CLI Clikt (`app-cli`) et bureau Compose (`app-ui`, MSI).
Tout (code, KDoc, logs, messages d'erreur, commits, README) est **en français**. Package racine `dev.highlights.*`.

## 1. Commandes
- Tests : `.\gradlew.bat test` (kotest FunSpec + JUnit5). Les `*IT.kt` et tests vidéo sont `enabledIf = { TestMedia.available }`
  (FFmpeg requis, vidéos synthétiques générées par `ffmpeg/src/testFixtures/.../TestMedia.kt`). Un module : `:montage:test`.
- CLI : `.\gradlew.bat :app-cli:installDist` → `app-cli\build\install\app\bin\app.bat` ; ou `:app-cli:run --args="..."`.
- UI dev : `.\gradlew.bat :app-ui:run` (lit `config/` du projet via `-Dhighlights.config`).
- Installeur : `:app-ui:packageMsi`, `:app-ui:portableZip` (WiX 3 ; FFmpeg téléchargé si absent).
- Version : `highlights.version` dans `gradle.properties` ; release = tag `v<version>` → `.github/workflows/installeur.yml`
  (+ `installeur-verif.yml` : cycle install/maj/réinstall/désinstall). `upgradeUuid` (app-ui/build.gradle.kts) ne change JAMAIS.
- Shell : Windows. Bash tool = Git Bash. Heredocs avec accents français cassent → écrire via Write / fichier python.

## 2. Modules et dépendances (settings.gradle.kts, conventions dans build-logic/)
```
core  ← ffmpeg, analysis, analysis-vision, analysis-ml, scoring, editing
editing ← export (api core+editing), montage (api core+editing+export)
publish (api core) : YouTube (OAuth bureau + envoi reprenable + textes), sans dépendance au montage
pipeline (api core, ffmpeg, scoring, editing, export, montage, publish) ← app-cli, app-ui
analysis / analysis-vision / analysis-ml : runtimeOnly dans app-cli, app-ui, tests pipeline (ServiceLoader, pas de dépendance de compilation)
```
Plugins de convention : `highlights.kotlin-library` (kotlin jvm + serialization, toolchain 21, kotest), `highlights.kotlin-app`,
`highlights.compose-app`. Versions : `gradle/libs.versions.toml` (Kotlin 2.4.20, coroutines, kotlinx-serialization, kaml,
kotlin-logging+logback, Clikt 5, Compose 1.12 + material3, onnxruntime, kotest 6.2).
IGNORER les dossiers `*/bin/` (sorties IDE, copies périmées des sources) et `build/`, `output/`, `music sample/`.

## 3. Flux principaux

### 3a. Analyse (`HighlightPipeline.analyze` / `analyzeAll`)
1. `validateInput` (extensions `SUPPORTED_EXTENSIONS`) → `profiles.resolve(file, forcedId)` (match `pathContains`+`priority`, sinon `default`).
2. Mémoire : `AnalysisLibrary.fingerprint(profile)` (window+audio+detectors+params canonisés, + `ANALYSIS_VERSION`) et
   `stamp(file)` (taille, mtime). Si trouvé → `reuse` : recharge la session, `reselect` seulement.
3. `ffmpeg.probe` → `MediaInfo`. `WindowGrid(size, hop, duration)`.
4. `runDetectors` : `DetectorRegistry.create(type,id,params)` pour chaque `DetectorConfig.enabled` ; un `AnalysisContext`
   par détecteur (media, grid, ffmpeg, workDir, progress, configDir, **FrameSampler partagé**, `AudioTracks`) ;
   `prepare()` de tous (abonnements vidéo) → `frames.runAll()` lancé → `analyze()` sous `Semaphore(parallelism)`.
   Échec d'un détecteur → `SignalTrack.missing` si `continueOnDetectorError`. Phases (`runPhase`) : les détecteurs
   `fallbackFor: <id>` ne tournent (nouveau FrameSampler) que si le signal de `<id>` est `isMissing` ; ceux avec
   `dependsOn: [ids]` tournent en dernier et reçoivent les événements de ces détecteurs dans `AnalysisContext.events`.
5. `ScoringEngine.score` (PercentileNormalizer + WeightedSumFusion) → `ScoredTimeline` ; `select` → `List<Highlight>`.
6. `Session` → `SessionStore.save(outputDir/sessions/<nom>.session.json)` (écriture atomique .tmp+move) ; `library.record`.
Multi-captures : triées par `MediaInfo.RECORDING_ORDER`, puis `reselectAll` (cible partagée sur la soirée).

### 3b. Export highlights (`HighlightPipeline.export` → `Exporter.export`)
`profile.gradedEdit()` (résout lut/music/captions/sfx relatifs à la config) + surcharges `ExportOptions` →
`DefaultEditPlanner.plan` (clips enabled, ordre, fps/hauteur plafonnés à la source, fondu ≤ 1/3 du plus court) →
si `EditStyle.STORY` : `StoryPlanner.plan` (+ `CaptionTranscriber` whisper si modèle présent → `withCaptions`) →
`OutputNamer.reserve` (jamais d'écrasement : suffixes _2,_3 ; refus d'écrire sur une source) →
`SourceCutter.prepare` (pré-découpe `-c copy` des extraits, économise la RAM FFmpeg) →
par format : `StoryRenderBuilder.build` ou `RenderCommandBuilder.build` → filter graph écrit dans un script
(`ffmpeg.filterScriptOption()` : `-/filter_complex` FFmpeg 7+ ou `-filter_complex_script`) → `runWithSoftwareFallback`
(rejoue sans `-hwaccel` si échec) → `.part.mp4` puis move → `ExportReport` JSON.

### 3c. Montage kills (`HighlightPipeline.killMontage`)
`MontageOptions` surchargent `profile.montage` (bloc `settings = base.copy(...)`) →
`MusicAnalyzer.analyze` (+ `fromStart`), ou si `music` est un dossier `MusicLibrary.load` puis `MusicChoice.rank` après l'inspection des kills → `MontagePlanner.groups(sessions)` (kills groupés par `mergeGap`, rounds/ace/clutch,
style) → `KillInspector.inspect` (`ShotLocator` recale sur l'attaque du tir ; `FlickMeter` flick + direction) →
`MatchCutter.inspect` (pose de l'arme `aim`/`rest`, `WeaponTemplate` arme en main ; groupes `MontagePlanner.placeable` seulement) ; ces deux-là passent par `InspectionCache` → `MontagePlanner.best`
(variantes échelle×dropShift notées par `MontageScorer`) → `ScopeCuts.apply` (raccords sur la pose, retiming) →
`KillMontageExporter.export` (`MontageRenderBuilder.build`, `MontageReport` JSON avec `MontageScore`).

### 3d. UI
`app-ui/Main.kt main()` → `Installation.setup()` (FFmpeg livré → prop `highlights.ffmpeg.dir` ; recopie config livrée vers
`%APPDATA%\Highlights\config` via manifeste SHA-256 sans écraser les fichiers modifiés) → `AppController` (implémente
`UiActions`, expose `StateFlow<UiState>`) → composables purs (`App`, `SidePanel`, `SessionViews`, `LibraryView`, `Dialogs`).
Un seul job long à la fois (`runTask`). Sauvegarde session différée (`scheduleSave`). Dossier surveillé :
`FolderWatcher.poll` toutes les 5 s, fichier stable 15 s → `analyzeInBackground`.

## 4. Index par fichier (main)

### core (`core/src/main/kotlin/dev/highlights/core/`)
- `Errors.kt` : `HighlightsException` > `ConfigException`, `InputException`.
- `analysis/SignalDetector.kt` : `SignalDetector{id; prepare(ctx); analyze(ctx): SignalTrack}`, `SignalDetectorFactory{type; create; availability()}`,
  `AnalysisContext`, `SignalTrack(detectorId, raw: DoubleArray par fenêtre, NaN = pas de donnée; events; note; segments)`,
  `SignalEvent(at, kind, confidence)`, `SignalSegment(range, kind)`, `DetectorParams.decode(serializer){default}` (YAML libre → data class du détecteur).
- `analysis/DetectorRegistry.kt` : `fromServiceLoader()`, `create`, `availability`.
- `config/PublishSettings.kt` : `PublishSettings(youtube)`, `YouTubeSettings` (clientSecretFile/clientId/clientSecret, privacy `YouTubePrivacy`, categoryId, madeForKids, tags, language, titleTemplate, descriptionTemplate, shortsHashtag, notifySubscribers).
- `config/AppConfig.kt` : `AppConfig(ffmpeg, outputDir="../output", profilesDir, workDir, analysis{parallelism, continueOnDetectorError}, encoder{preference, videoArgs, audioBitrate}, publish)`,
  `LoadedConfig.resolve(path)` (relatif au dossier d'app.yaml, `~/` = home), `outputDir/profilesDir/workDir(%TEMP%/highlights)`,
  `ConfigYaml` (kaml strictMode, BOM toléré) `.load/.decodeFile`.
- `dsp/Fft.kt` : `Fft.transform`, `RealFft(n).power/magnitude`.
- `ffmpeg/Ffmpeg.kt` : `FfmpegCommand`, `StdoutHandler{Discard, Lines, Binary}`, `FfmpegResult`, `FfmpegException`,
  interface `FfmpegService{probe, run, runProbe, filterScriptOption}`, `EncoderProfile`, `EncoderSelector{select()}`, `FfmpegProgressParser`.
- `ffmpeg/Hwaccel.kt` : `Hwaccel.resolve("auto"|"none"|nom)`.
- `model/AudioTracks.kt` : `AudioRole{MIX,GAME,MIC}`, `AudioLayout(mix,game,mic)` (imposé par profil), `AudioTracks.of(streams, layout)`
  (ordre : layout > titres > nombre de pistes), `indexOf(role)`, `mixIndices()`, `describe()`.
- `model/Highlight.kt` : `Highlight(id, source, range, peak, score, contributions, enabled, events)`, `ScoredTimeline(grid, total, contributions, events, excluded, segments)`, `TimelineEvent`, `TimelineSegment`.
- `model/MediaInfo.kt` : `MediaInfo(path, sizeBytes, duration, creationTime, container, video, audio ; recordedAt calculé)`, `RECORDING_ORDER`, `VideoStream`, `AudioStream(audioIndex, title…)`.
- `model/Settings.kt` : `SelectionPolicy`, `SelectionTarget(topN|totalDuration|all, exactement un)`, `EditSettings` (style, story, order, transition,
  loudnessLufs, audio, audioStreams, formats, sourceHeight, landscape, vertical, grade, fps; `audioIndices(tracks)`), `AudioSelection`,
  `GradeSettings(lut, saturation, contrast, vignette, vibrance, brightness)`, `ClipOrder`, `TransitionSettings`, `OutputFormat{SOURCE,LANDSCAPE "16:9",VERTICAL "9:16"}`,
  `FrameSize`, `VerticalSettings(size, cropRegion, hud, reference)`, `HudOverlay`, `OverlayTarget`, `CropRegion` (coords normalisées 0..1).
- `model/Platform.kt` : `SafeArea(top, bottom, left, right ; width, centerY)`, `PlatformProfile(name, format, maxDuration, loudnessLufs, maxBitrate, fps, safeArea ; applyTo(EditSettings))`,
  `DEFAULTS` (tiktok, shorts, reels, youtube), `parseBitrate` ; `LoadedConfig.platforms` = DEFAULTS + `AppConfig.platforms` ; `EncoderProfile.withMaxBitrate`.
  `EditSettings.safeArea/maxBitrate` : textes (Captions.drawText/drawLabel, MontageRenderBuilder.drawText) centrés dans la largeur utile et bornés en y ; sortie inchangée sans plateforme.
- `model/Story.kt` : `EditStyle{SIMPLE,STORY}`, `StorySettings{jumpCuts, coldOpen, punchIn, shake, transition, sfx, music, captions, labels, slowMo, scenes, fadeOut}` et sous-classes.
- `model/Montage.kt` : `MontageSettings` (voir §6) + `MontageLength, CutSettings, ShotAlign, KillStyle, SlowAudio, EffectDensity{SOBER,BALANCED,HEAVY},
  MontageOrder{BUILD_UP,CHRONOLOGICAL}, ZoomEffect, FlashEffect, WhipPanEffect, ColorBoost (couleurs boostées → `GradeSettings`), MotionBlur (`shutter` → tmix, `cuts` → flou radial), WeaponHud, PoseKind{AIM,REST}, MatchCut, SlowMotionEffect, SpeedRampEffect, TextEffect, GameAudio{FULL,KILLS}, MontageAudio`.
- `model/ScreenGeometry.kt` : `RegionAnchor{LEFT,CENTER,RIGHT,AUTO}`, `ScreenGeometry.rescale/forVideo` (zones HUD mesurées en 21:9 → 16:9 etc.), `aspectLabel`.
- `model/TimeRange.kt`, `model/WindowGrid.kt` (`count`, `rangeOf`, `centerOf`, `indicesCovering`).
- `profile/GameProfile.kt` : `GameProfile(id, displayName, match, window, audio, detectors, selection, edit, montage)`, `DetectorConfig(id, type=id, enabled,
  role SCORE|GATE, weight, eventBoost, eventBoosts, normalization, fallbackFor, params: YamlNode)` (fallbackFor validé dans `GameProfile.init`), `NormalizationConfig(low/highPercentile, minSpread, maxValue)`.
- `profile/ProfileRepository.kt` : `loadDirectory`, `resolve`, `byId`, `fileOf`, `DEFAULT_ID="default"`.
- `progress/Progress.kt` : `ProgressReporter{update, child(label, weight), complete}`, `ProgressTracker`, `EtaEstimator`, `ProgressSnapshot`.
- `serialization/` : `SerialDuration` (texte "1500ms", "2s", "1m30s"), `SerialPath`, `SerialInstant`, `Durations.parseOrNull/format/ffmpegSeconds`, `toTimecode`, `roundTo`.
- `session/Session.kt` : `Session(version=1, createdAt, media, profileId, timeline, highlights, warnings)`, `SessionStore.save/load/json`.
- `video/FrameSampler.kt` : décodage vidéo partagé. `subscribe(FrameSpec.keyframes(i)|fps(f,hw), zones: List<FrameZone>, label, progress, onReset, onFrame)` →
  `Subscription.await()`. Pixels gris par zone sur stdout FFmpeg (ou `FrameZone.color` : expression `geq` sur RVB, un octet/pixel) ;
  `keyframeInterval()` mémoïsé ; repli logiciel (onReset).

### ffmpeg
- `ProcessFfmpegService` : implémente `FfmpegService` (ProcessBuilder, stderr ring buffer, annulation = kill process, détecte l'option de script de filtres).
- `FfmpegLocator.locate(tool, configured)` : config > prop `highlights.ffmpeg.dir` (livré) > PATH > winget Gyan.FFmpeg.
- `FfmpegEncoderSelector` : essaie `encoder.preference` par un vrai encodage test ; `checkAll()` ; `EncoderPresets.defaultVideoArgs/isHardware`.
- `FfprobeParser.parse` : JSON ffprobe → `MediaInfo`.
- testFixtures `dev.highlights.testing.TestMedia` : `available`, `requireFfmpeg()`, `generate(...)` vidéos synthétiques avec salves audio.

### analysis (détecteurs audio + Outplayed ; déclarés dans `META-INF/services/dev.highlights.core.analysis.SignalDetectorFactory`)
- `audio/AudioLoudnessDetector.kt` — type **`audio-loudness`** : ebur128 momentané, raw = contraste local/global en LU. Params `role, stream, fallbackStream, titleContains, optional, localBaseline, localContrastWeight, floorLufs`.
- `audio/GameSoundsDetector.kt` + `GameSounds.kt` — type **`game-sounds`** (branche `montage-onetaps`) : piste du jeu 48 kHz en flux ; `shots` (attaques les plus fortes → events `shot`, manque les rafales) et `sounds` (gabarits `TemplateMatcher`, ex. `templates/valorant/headshot.wav`, seuil 0,7).
- `audio/VoiceActivityDetector.kt` — type **`voice-activity`** : silencedetect → segments `speech`. Params `role, optional, silenceDb…`.
- `outplayed/OutplayedEventsDetector.kt` — type **`outplayed-events`** : events (kill, death, assist, headshot…) lus dans la base IndexedDB d'Overwolf.
  Params `database, kinds (map type→nom), offsets`. `OutplayedLibrary.load/find` (par chemin puis nom), `LevelDbSnapshot` (lecteur LevelDB maison + `Snappy`), `V8Deserializer`.

### analysis-vision
- `HudTemplateDetector.kt` — type **`hud-template`** : correspondance de modèles d'images du HUD. `HudMode{EVENTS,PRESENCE}`, `Sampling{AUTO,KEYFRAMES,FPS}`,
  `MatchMethod{ncc, bright}`, `TemplateSpec(name, kind, file, region, anchor, threshold, scales, minConsecutive, brightness, minContrast…)`.
  Mode presence + `role: gate` = détection « en jeu ».
- `OcrLogDetector.kt` — type **`ocr-log`** : OCR Windows d'un journal à l'écran (Wardogs), `rules` → kinds, fusion avec icônes (`icons: HudTemplateParams`) via `EventFusion`.
- `KillfeedDetector.kt` — type **`killfeed`** : kills/morts du joueur lus dans le killfeed par la couleur de ses lignes (jaune VALORANT, zone `FrameZone.color`).
  `KillfeedParams` (region, `scale` en px lus par px de référence, forme du cadre en px de référence, `maxGap`, `deathSpacing`, `kinds`, `offset`), `KillfeedRole{KILL,DEATH}`,
  `FeedReader.rows` (composantes 8-connexes après comblement `bridge` ; forme `FrameShape` : hauteur d'une ligne, trait du haut, cadres étroits pleins ;
  colonne `victimWidth` au bord droit = mort si collée au bord ; kill avec du jaune côté victime = « soi → soi », écarté),
  `FeedTracker` (suivi : colonnes communes + bord gauche le plus proche, ne fait que monter ; nouvelle ligne = événement).
  Réglé sur 4 parties (scripts d'évaluation hors dépôt) : changer un seuil demande de revalider sur de vraies captures.
  Utilisé par valorant.yaml en `fallbackFor: game-events`.
- `RewardsLog.kt` : `RewardsLog.classify/fuzzyDistance/rows`, `LogTracker` (suivi de lignes, minSightings), `EventFusion.fuse`.
- `TemplateMatcher.kt` : `GrayImage`, `PreparedTemplate`, `TemplateMatcher.match` (NCC), `BrightTemplate/BrightMatcher`.
- `WindowsOcr.kt` : lance `resources/.../windows-ocr.ps1` (Windows.Media.Ocr) par lots ; `WindowsOcr.supported`.
- `AmmoCounterDetector.kt` — type **`ammo-counter`** : compteur de munitions du HUD relu à 60 img/s autour de chaque kill (`ctx.events`, `dependsOn`) via `frames.extractRange` → events `hud-shot` ; `changes` (chiffres blancs qui changent), `refills` (recharge animée du Combat à mort, zone `TEAL`, ignorée), `windows`.
- `FrameSampling.kt` : `chooseSampling` (images clés vs fps selon intervalle mesuré).

### analysis-ml
- `AudioEventDetector.kt` — type **`audio-events`** : YAMNet ONNX (`config/models/yamnet.onnx` + class map) sur le micro 16 kHz → segments/events `laughter`, `shout`.
- `LogMelStream.kt` (log-mel en flux, patchs 96×64), `YamnetClassifier.kt` (ONNX Runtime). Test de référence vs TensorFlow (`src/test/resources/yamnet/*.f32`).

### scoring
- `Normalization.kt` : `PercentileNormalizer` (low pct→0, high pct→1, minSpread, maxValue).
- `Fusion.kt` : `NormalizedSignal`, `WeightedSumFusion` (moyenne pondérée des signaux non-NaN + boosts d'events × confiance + multiplication par gates ; total non plafonné ; events hors jeu retirés).
- `MomentSelector.kt` : `ThresholdMomentSelector.select/selectAcross` (seuil ou `requiredEvent`, merge, pre/postRoll, min/maxClip, réactions → bonus, cible top/durée/all).
- `SegmentExtension.kt` : étend un moment pour ne pas couper `keepWhole` (≤ maxExtension).
- `HighlightMerge.preserveDisabled` : garde décochés les moments recouvrant un moment décoché.
- `ScoringEngine` : façade `score / select / selectAcross`.

### editing
- `EditPlan.kt` : `PlannedClip`, `EditPlan(clips, settings, fade)`, `DefaultEditPlanner`.
- `RenderCommandBuilder.kt` : rendu simple (une entrée par clip, xfade, loudnorm, recadrage 9:16 + HUD crop-and-replace, grade). `geometry`, `outputSize`, `pixelScale` (pixels de sortie par pixel de capture), `cropBox`, `grade`, `buildPreview`.
- `SourceCuts.kt` : `SourceCut`, `CutInput`, `SourceCuts.input`, `SourceCutter.prepare` (MIN_CUTS=12 ; découpe si ≥ ; vérif espace disque).
- `story/StoryPlanner.kt` : `StoryShot`, `ShotRole{COLD_OPEN,OPENING,JUMP}`, `StoryPlan`, `plan()` (accroche → scènes → `liveRanges` jump cuts → `decorate` punch/shake/slow/labels), `streakLabels`, `withCaptions`.
- `story/StoryRenderBuilder.kt` : graphe story (framing zoom/shake, slowVideo, SFX générés `WHOOSH_SOURCE_*`/`IMPACT_SOURCE_*` ou `SfxBank` dossiers, musique `musicVolume`, `envelope`).
- `story/Captions.kt` : parse sortie whisper, `clean` (garder ce qui recouvre de la voix), `readable`, `drawText` (drawtext, emphasis jaune, cri rouge), `transcriptionFilter`.
- `story/TextMeasure.kt` : largeur de texte AWT pour ajuster la taille.

### export
- `Exporter.kt` : `ExportRequest`, `ExportResult(videos, report, duration, encoder)`, `export`, `preview` (PNG par format), `thumbnail`, `clipPreview`.
- `CaptionTranscriber.kt` : filtre FFmpeg `whisper` (whisper.cpp), cache par clé (media, range, piste, réglages, modèle).
- `OutputNamer.kt` : `reserve(dir, game, date, formats, kind="highlights"|"killmontage")` → `<game>_<date>_<kind><suffix>.mp4` + `.json`, `tempFor`, `slug`.
- `ExportReport.kt` : rapport JSON des highlights.

### montage (montage kills)
- `MusicLibrary.kt` : `MusicLibrary(cacheDir).load(ffmpeg, dir)` (analyses gardées en JSON par musique, `StoredMusic` en µs, échecs gardés aussi,
  invalidées par taille/date/`MusicAnalyzer.VERSION`), `files(dir)`, `AUDIO_EXTENSIONS` ; `MusicChoice.rank(groups, musics, settings, fromStart)`
  (plan par musique, en parallèle, `value` = note × groupes gardés / max − `recency`), `recency` (`RECENT_PENALTY` 0,03 sur les `RECENT_DEPTH` 3 derniers montages),
  `settingsFor`, `prepare` ; `MusicCandidate` ; `MusicHistory` (`outputDir/sessions/music-history.json`, `recent/record`, alimenté par `killMontage`).
- `MusicAnalyzer.kt` : `VERSION` (à incrémenter si l'analyse change), `analyze(ffmpeg, file)` (décode 22050 Hz mono) / `analyzeSamples` ; mel → onset → `estimateTempo` → `trackBeats` (DP) → accents, `detectSections` (novelty timbre+volume, alignées mesures),
  `findDrop`, `classify` (`SectionKind` INTRO/BUILD_UP/DROP/BREAKDOWN/BODY/OUTRO), `fromStart`. Modèle `MusicAnalysis(beats, bpm, downbeatPhase, beatAccent, halfAccent, sections, dropBeat)`, `hits()`, `beatTime()`, `Intensity{LOW,MID,HIGH}`.
- `CutGrid.kt` : `CutSlot(startBeat, endBeat, section, dropBeat)`, `build` (tuiles par section, puissances de 2, `tileAccelerating` en montée), `select` (échelle + fenêtre selon cible),
  `window` (fenêtres notées puis la mieux notée qu'`accept` retient ; pénalité `DROP_LEAD_WEIGHT` si moins de `cuts.dropLead` avant la drop).
- `MontagePlanner.kt` : `KillGroup(media, kills, score, protectedSegments, voiceSegments, traits, outcome, style, aim)` (`rank = kills + score/10 + style`), `KillTraits(headshot, flick, shift, direction)`,
  `FlickDirection`, `Aim`, `RoundOutcome`, `SpeedSegment/SpeedKind`, `MontageClip` (`toOutput`, `outputKills`, `slow`, `ramps`), `PlanVariant`, `MontagePlan` (slots contigus obligatoires).
  Clutch : `killStyle.clutchEvent` (valorant : « clutch » d'Outplayed) → `announcedClutch` (groupe dont le dernier kill précède l'annonce, sans kill ni mort entre, ≤ roundGap) remplace la déduction.
  `MontagePlanner.groups / rounds / outcome / style / targetDuration / placeable / best / plan / layout / arbitrate / aimFit / assign / pairUp / clipFor / slowCurve / isStrong / allowsSlow`.
  `pairUp` recompte seulement les coupes touchées par un échange ; `placeable` = groupes que le plan peut placer (au plus autant de multi-kills et de kills seuls que de temps dans `maxDuration`, égalités de rank comprises).
  Chronologique : `layout` essaie les fenêtres (`accept`) jusqu'à celle où `assign` met sur la drop le meilleur groupe (± `ORDER_TOLERANCE`) sans en perdre.
  Constantes de réglage fin : `MIN_KEPT, MONOTONY_PENALTY, RAMP_COST, OFF_BEAT_DISCOUNT, MIN_CLIP_SHARE, TARGET_STEP, MATCH_BONUS, MATCH_VALUE, ORDER_TOLERANCE, MIN_GAIN, SCALES, DROP_SHIFTS`.
- Rythme rapide : `MontageSettings.forFast` / `FastMontage` (un kill par plan, plans d'un temps via `cuts.singleBeat`, contexte 0,2 s + 0,15 s, ni ralenti ni rampe, échanges écartés par `skipDeathWithin` dans `MontagePlanner.groups`) ; `MontageOptions.fast`, `--fast` (montage, search), case « Rythme rapide ».
- `OneTaps.kt` (branche `montage-onetaps`) : `OneTaps.select` (kills d'une balle à la tête, `strict` si headshots connus) ; réglages `MontageSettings.forOneTaps` / `OneTapMontage`.
- Kill confirmé à l'image : détecteur `kill-confirm` (type `killfeed`, toujours actif, 30 img/s, événements `feed-kill`) → `KillStyle.confirmEvent` ; `MontagePlanner.groups` associe chaque kill à la confirmation la plus proche (`CONFIRM_WINDOW` 0,8 s), `hudShots(…, confirmed)` cherche la balle qui tue jusqu'à `CONFIRM_AFTER` (0,12 s) après elle, sinon `fatal` = confirmation − `CONFIRM_LAG`.
- Balles d'un kill : `MontagePlanner.hudShotCounts` (événements `hud-shot` du compteur de munitions, préférés) ou `shotCounts` (tirs entendus) → `KillTraits.shots`.
- Plans d'un temps : `CutSettings.singleBeat` → `MontagePlanner.singleBeat` (temps ≥ minLead+minTail), `MontageClip.leadIn` (kill sur `slot.startBeat`, coupe avancée dans `MontageRenderBuilder.leads`, pas de whip ni de raccord `ScopeCuts` sur ces plans). `MusicPace` + `MusicChoice.pace` : bonus aux musiques à plans courts.
- `CameraMotion.kt` : `CameraMotion.measure` (rotation de la caméra sur chaque plan, décodage 256 px, `FlickMeter.motions`) → `MotionTrack` ; `VectorBlur.sigmas/sourceAt/filters` (flou orienté `gblur@vbN` piloté par `sendcmd`) ; mesuré dans `KillMontageExporter` en même temps que la pré-découpe, passé par `MontageRenderRequest.motion`.
- `SourceFrames.kt` : `SourceFrames.read` (plusieurs `FrameZoneSpec` d'une portion de source en un décodage : `split` + `pad` + `vstack`, mêmes octets que zone par zone), `PARALLELISM` (4 décodages courts à la fois, mesuré sur de vraies soirées).
- `InspectionCache.kt` : inspections gardées d'un montage à l'autre (`<workDir>/cache/kills`, un `.json.gz` par capture, oublié si taille/date changent) : `kill/putKill` (KillInspector), `aim/putAim` (MatchCutter, poses en doubles bruts), clés = tout ce dont la mesure dépend ; `VERSION` à incrémenter si KillInspector ou MatchCutter changent.
- `KillInspector.kt` : `inspect` (cache `InspectionCache`), `ShotLocator.locate/rises`, `FlickMeter.motions/speeds/direction/score/shift`.
- `MatchCutter.kt` : `MatchCutter.inspect(only = MontagePlanner.placeable)/read/footage/pose` (une lecture `SourceFrames` visée + arme par groupe quand les fenêtres d'avant/après sont alignées à l'image près, sinon deux ; `Footage.slice` reproduit le compte d'images de FFmpeg), `Pose` (ressemblances mémorisées), `HeldPose`, `WeaponTemplate.score/matches` (sommes cumulées), `ScopeCut`, `ScopeCuts.apply/count/aimCurve/restCurve/runs/similarity/compatible/aimStart/aimEnd/speeds/cut/retimeTail/retimeHead/disarm/symmetry`.
- `MontageRenderBuilder.kt` : graphe FFmpeg du montage : `build`, `sourceCuts`, `flashes`, `whips`/`whipStages`, `zooms`, `cutBlurStages` (flou radial des coupes), `leads` (preBeatFrames), `bleeds`, `speedParts`, `slowAudio`, `envelope`, `volumeExpression`.
- `MontageScorer.kt` : `MontageScore(total, sync, accent, restraint, variety, fill, pacing, coverage, opening, lull, action, details)` ; critère null = exclu de la moyenne.
- `KillMontageExporter.kt` : `MontageExportRequest(musicChoice)`, `MontageReport(+Section, +Clip, +Music ; musicCandidates)`, `export`, `recordingDate`.

### publish (`dev.highlights.publish`)
- `YouTubeText.kt` : `VideoKind`, `VideoFacts` (jeu, date, durée, taille, kills, headshots, multiKills, aces, clutches, moments, musique), `YouTubeMetadata` (+ `problems()` = refus prévisibles de YouTube),
  `YouTubeText.suggest/values/fill/hook/detail/hashtags/tags/tagsLength/parseSchedule` ; modèles `{jeu} {date} {accroche} {kills} {headshots} {detail} {moments} {duree} {musique} {hashtags} {titre}`.
- `GoogleAuth.kt` : `OAuthClient(.fromFile client_secret JSON)`, `GoogleEndpoints` (remplaçables en test), `GoogleAuth` (loopback 127.0.0.1 + PKCE, `accessToken/login/logout`, jeton `StoredToken` ; portée `youtube.upload` seule).
- `YouTubeClient.kt` : `upload` reprenable (session + morceaux multiples de 256 Kio, 308/Range, 5xx → reprise, 401 → rafraîchit une fois), `UploadedVideo(id, url, studio)`.
- Tests : `YouTubeUploadTest` (faux Google `HttpServer` : navigateur, jetons, 503 au milieu), `YouTubeTextTest`.

### pipeline
- `HighlightPipeline.kt` (captures effacées depuis l'analyse : écartées de `search` et des groupes de `killMontage`) : `AnalyzeOptions, ExportOptions, MontageOptions, AnalysisOutcome(reused), ProcessOutcome, BatchOutcome` ;
  méthodes `probe, analyze, analyzeAll, export, preview, reselect, reselectAll, thumbnail, clipPreview, killMontage (music = fichier ou dossier), loadMusicLibrary, process, processAll, reloadProfiles, resolveProfile` ;
  `ExportOptions.platform` / `MontageOptions.platform` (format, loudness, débit, zone sûre ; montage : maxDuration plafonnée ; highlights trop long → InputException avant rendu ; fichier `…_<kind>_<plateforme>`),
  `platforms` ; `MontageOptions.fromStartMusics` (réglage « depuis le début » par musique en mode bibliothèque) ; `ExportResult.music` = musique utilisée ;
  `library`, `previewDir`, `withJobDir` (workDir/job-UUID, gardé si échec).
- `Pipelines.kt` : câblage manuel (pas de DI) `Pipelines.ffmpeg(config)`, `Pipelines.create(config)`.
- `ConfigLocator.locate` : explicite > env `HIGHLIGHTS_CONFIG` > prop `highlights.config` > `./config/app.yaml` > `<install>/config/app.yaml`.
- `AnalysisLibrary.kt` : index `outputDir/sessions/library.json` (`LibraryEntry`), `find/contains/record/entries`, `fingerprint`, `stamp`, `ANALYSIS_VERSION`.
- `FolderWatcher.kt` : `poll(isKnown)` → fichiers stables depuis `settle`.
- `YouTubePublisher.kt` : `HighlightPipeline.youtube(browse)` → `apiEnabled` (`publish.youtube.api`, false par défaut : envoi manuel, `UPLOAD_PAGE`), `configured` (app.yaml `publish.youtube` ou `client_secret*.json` déposé dans le dossier de config), `connected`,
  `facts(video)` (probe + rapport JSON du même dossier qui liste la vidéo dans `outputs`), `suggest`, `login/logout`, `upload` ; jeton `~/.highlights/youtube-token.json`.
- `Statistics.kt` : `GameStats` (kills, deaths?, headshots?, multiKills, aces, clutches, bestRound?, rounds? ; kd, headshotRate), `EveningStats` (date, game, games ; taux sur les parties où c'est connu),
  `Statistics.game(session, settings, file, game, deathSources, headshotSources, outdated, clutchSources)` (clutches null = inconnus) (règles du montage : `MontagePlanner.groups/rounds`, rendus publics), `sources`, `evenings` (6 h, par jeu), `distinctGames` (chevauchement > 50 %), `MIN_GAME` 5 min.
  `HighlightPipeline.statistics()` : entrées de `library` ≥ 5 min, dédoublonnées, profils sans kill écartés ; `outdated` = empreinte de la bibliothèque ≠ `AnalysisLibrary.fingerprint(profil)` ;
  `refreshOutdated(progress)` les réanalyse (`reuse = false`).
- `LibrarySearch.kt` : `MomentQuery(game, from, to, minKills, ace, clutch, allHeadshots ; matches)`, `FoundMoment` (groupe de kills d'une partie), `MomentPick(kills par capture ; keeps(group) à 1 s près)`.
  `HighlightPipeline.search(query)` (mêmes parties que `statistics`, via `analyzedGames`) ; `MontageOptions.onlyKills` filtre les groupes de `killMontage`.
- `Diagnostics.kt` : `run(file?)` → sections (environnement, FFmpeg, encodeurs, détecteurs, profils, capture) ; `render()`.

### app-cli (`dev.highlights.cli`)
- `Main.kt` → `HighlightsCli` (+ sous-commandes). `CliSupport.kt` : `PipelineCommand` (options globales `--config`, `-v`, crée le pipeline), `ConsoleProgress`, `LogFiles`.
- `Commands.kt` : `process, analyze, export, montage, music, preview, probe, doctor, encoders, score` ; `PublishCommand.kt` : `publish VIDEO [--title --description(-file) --tags --privacy --category --language --kids --no-notify --publish-at --dry-run] | --login | --logout`. Options montage :
  `--music --max --fill --chronological --effects --no-hook --from-start --no-zoom --no-flash --flash-every-cut --no-whip --colors --no-motion-blur --fast --onetaps --no-match-cut --no-rounds --no-slowmo --no-ramp --no-text --balance --reactions --game-audio --out`.
  `--platform` : `export`, `process`, `montage`. `StatsCommand.kt` : `stats [--game --games --last]`. `SearchCommand.kt` : `search [--game --from --to --last --min-kills --ace --clutch --headshots --limit --onetaps --montage MUSIQUE --max --platform --colors -o]`.
  Communes : `-c/--config`, `-v/--verbose`, `-f/--format`, `-p/--profile`, `-o/--out`, `--style`, `--music`, `--top|--duration|--all`, `--kills`, `--threshold`, `--reanalyze` ;
  `music` : `--max --clips` ; `preview` : `--at`.
- Logs : `~/.highlights/logs/highlights.log` (logback.xml).

### app-ui (`dev.highlights.ui`)
- `Main.kt` (fenêtre, `Installation.setup`), `AppController.kt` (`Backend`, `UiActions`, toute la logique ; `withProfileDefaults`), `UiState.kt`
  (`UiState, ConfigStatus, SourceInfo, LibraryItem, WatchState, TargetMode, MomentMode{BEST,KILLS}, SettingsState, JobState, SessionEntry, Segment, SessionState, ErrorInfo, ImagePreview, MontageUiState`).
- Publication : `PublishUiState` (UiState.kt, `withSuggestion/metadata/problems/canPublish`), `PublishDialog.kt`, actions `openPublish/updatePublish/resetPublishText/publishToYouTube/youtubeLogout/editConfig/browse`,
  `openYouTubeUpload/copyToClipboard` (mode manuel, par défaut : `PublishUiState.api` false), `UiState.lastUpload` ; `Platform.browse/copy` (défaut vide pour les faux).
- Recherche : `SearchView.kt` (`SearchState` : moments chargés une fois, filtres, `unpicked`, `results/picked` ; `SearchPeriod`), actions `showSearch/closeSearch/updateSearch/toggleMoment/montageFromSearch`
  (ouvre les parties via `openSessions(files, then)` puis `openMontage` avec `MontageUiState.pick`).
- Statistiques : `StatsView.kt` (`StatsState` : games, jeu choisi, `evenings/gameNames/shown`), actions `showStats/closeStats/setStatsGame`, `UiState.stats` (remplace la liste des analyses).
- Vues : `App.kt` (layout, drop de fichiers), `SidePanel.kt` (`AppHeader, SourceSection, SettingsSection`), `SessionViews.kt` (`JobCard, SessionHeader, TimelineCard, SegmentList, ExportBar`),
  `LibraryView.kt` (analyses enregistrées + `WatchCard`), `Dialogs.kt` (`ErrorDialog, VerticalPreviewWindow, MontageDialog`), `Theme.kt` (`Palette`, `HighlightsTheme`).
- `Platform.kt` (`DesktopPlatform` : FileDialog/JFileChooser, open/reveal/edit), `Installation.kt`, prefs : `WatchPrefs.kt` (`~/.highlights/watch.json`), `MusicPrefs.kt` (`~/.highlights/music.json` : fromStart par musique, dossier `library` + `useLibrary` ; ajouter un Path à un Set avec `plusElement`, un Path est itérable).
- Tests : `UiStateTest`, `InstallationTest`, `UiSnapshotTest` (rendu composables), `AppControllerIT` (parcours complet, FFmpeg requis).

## 5. Configuration (`config/`)
- `app.yaml` → `AppConfig`. Installée : `%APPDATA%\Highlights\config`, sortie `~/Videos/Highlights` (filtre au packaging).
- `profiles/{default,lol,valorant,wardogs}.yaml` → `GameProfile`. Sections : `id, displayName, match{pathContains, priority}, window{size,hop}, audio{mix,game,mic}, detectors[], selection, edit{style, story, grade, vertical{reference, hud[]}, formats…}, montage{…}`.
  YAML strict : une clé inconnue = `ConfigException`. Profils : wardogs (ocr-log + icônes + gate hud-template, pose `aim`), valorant (outplayed-events avec offsets −390 ms, killfeed en secours et `kill-confirm` toujours, `matchCut.pose: rest`, weapon ammo_icon), lol (audio+voix+rires), default.
- `templates/<jeu>/*.png` (modèles HUD), `models/yamnet.onnx` + `yamnet_class_map.csv` ; `models/ggml-*.bin` (whisper) hors dépôt et hors installeur.
- Chemins relatifs dans les profils → résolus par `LoadedConfig.resolve` (dans `gradedEdit`, `killMontage` pour `matchCut.weapon.template`, et `ctx.configDir` pour les détecteurs).

## 6. Recettes « où toucher »
- **Nouveau détecteur** : classe `XxxParams` @Serializable + `SignalDetector` + `SignalDetectorFactory(type="...")` dans un module analysis* ; ligne dans son fichier `META-INF/services/...SignalDetectorFactory` ;
  référencer `type` dans un profil. Lecture d'images : s'abonner à `ctx.frames` dans `prepare()`, jamais lancer son propre FFmpeg vidéo. Ne jamais réencoder.
  Si le calcul d'un détecteur existant change : incrémenter `AnalysisLibrary.ANALYSIS_VERSION`.
- **Nouveau réglage de montage kills (exposé UI+CLI)** : champ dans la data class de `core/model/Montage.kt` (défaut + KDoc + `require`) → usage dans `montage/` →
  `MontageOptions` + bloc `base.copy(...)` de `HighlightPipeline.killMontage` → `MontageUiState` + `AppController.createMontage` + `Dialogs.MontageDialog` (+ `MusicPrefs` si mémorisé par musique) →
  option dans `MontageCommand` (Commands.kt) → README (section montage) → tests (`MontagePlannerTest`, `MontageRenderBuilderTest`, `KillMontageIT`).
- **Réglage highlights/story** : `Settings.kt`/`Story.kt` → `StoryPlanner`/`StoryRenderBuilder` ou `RenderCommandBuilder` ; chemins de fichiers à résoudre dans `gradedEdit`.
- **Nouveau critère de note** : `MontageScore` (nullable pour compat des anciens rapports) + `MontageScorer.score` + `criteria()` de `ScoreCommand`.
- **Changement de format de session** : `Session.CURRENT_VERSION`, `ignoreUnknownKeys` déjà actif ; défauts obligatoires pour les nouveaux champs.
- **Sélection des moments** : `ThresholdMomentSelector` (+ `SegmentExtension`) ; appelé aussi à chaud par `reselect` (UI seuil/durée sans réanalyse).

## 7. Invariants / pièges
- Les sources ne sont jamais modifiées ni écrasées (`ensureNotSource`, contrôle dans `KillMontageExporter`). Sorties jamais écrasées (`OutputNamer`).
- Timeline : `ScoredTimeline.total.size == grid.count` (require). NaN = absent ; un signal absent redistribue son poids.
- `MontagePlan` : slots contigus (require). Kills tombent sur des temps : toute modif de vitesse/retiming doit conserver `outputLength` du slot. Chaque plan est coupé en nombre d'images (`trim=end_frame`, puis `setpts=N/(fps*TB)`), jamais par durée : `trim=duration` gardait des images en trop.
- Pistes audio désignées par rôle, jamais par index en dur (sauf `AudioLayout`/`stream` explicites).
- Zones HUD en coordonnées normalisées + `reference`/`referenceWidth/Height` + `anchor` → `ScreenGeometry`.
- Graphe de filtres toujours via fichier script (commande trop longue sinon) ; nombres formatés `Locale.ROOT`.
- Hwaccel : toujours un repli logiciel en cas d'échec.
- Avertissement « 0 % » du gate HUD vu dans la sortie de tests : il vient des tests (vidéos synthétiques), pas d'un bug en vraie capture.
- `TODO.md` : feuille de route du montage kills (points 1–5 faits ; pistes ouvertes : whoosh sur whip, clutch réel, icône chargeur 16:9, perClip/min à valider).

## 8. Conventions de travail
- Commits en français, infinitif, une ligne (« Raccorder les coupes visée sur visée »). Une branche par fonctionnalité (noms FR en kebab, ex. `montage-arme-en-main`),
  mergée dans master par commit de merge (« Merge branch '…' »). Merge sur master seulement si branche complète + tests verts. Commit/merge seulement sur demande.
- Commentaires/KDoc en français, qui expliquent le pourquoi (mesures réelles citées). Garder ce style et cette densité.
- Tests kotest `FunSpec({ test("…") { … } })`, noms de tests en français ; `shouldBe` ; tests d'intégration conditionnés à FFmpeg.
- Après une nouvelle fonctionnalité : mettre à jour README (et TODO.md si concerné) et **cette carte** si un fichier/symbole/flux change.
