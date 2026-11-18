package dev.highlights.pipeline

import dev.highlights.core.i18n.tr
import dev.highlights.core.userMessage
import dev.highlights.core.i18n.Message
import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.analysis.SignalEvent
import dev.highlights.core.analysis.SignalTrack
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.ClipOrder
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.EffectDensity
import dev.highlights.core.model.GameAudio
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageOrder
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.MontageStyle
import dev.highlights.core.model.MontageStyles
import dev.highlights.core.model.PlatformProfile
import dev.highlights.core.model.SelectionTarget
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.profile.DetectorConfig
import dev.highlights.core.profile.GameProfile
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.session.Session
import dev.highlights.core.session.SessionStore
import dev.highlights.core.serialization.toTimecode
import dev.highlights.core.video.FrameSampler
import dev.highlights.export.OutputNamer
import dev.highlights.export.ExportRequest
import dev.highlights.export.ExportResult
import dev.highlights.export.Exporter
import dev.highlights.montage.MontagePin
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageScorer
import dev.highlights.montage.LearnedTaste
import dev.highlights.montage.TasteLearner
import dev.highlights.montage.TasteEdit
import dev.highlights.montage.TasteChoice
import dev.highlights.montage.TasteHistory
import dev.highlights.montage.MontageVariantsFile
import dev.highlights.montage.MontageVariants
import dev.highlights.montage.MontageVariantEntry
import dev.highlights.montage.MontagePreview
import dev.highlights.montage.MontageChoiceRecord
import dev.highlights.montage.InspectionCache
import dev.highlights.montage.KillInspector
import dev.highlights.montage.KillMontageExporter
import dev.highlights.montage.KillGroup
import dev.highlights.montage.MatchCutter
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontageEdits
import dev.highlights.montage.MontageExportRequest
import dev.highlights.montage.MontagePlan
import dev.highlights.montage.MontagePlanner
import dev.highlights.montage.ThumbnailStyle
import dev.highlights.montage.MontageReportMusic
import dev.highlights.montage.MusicAnalysis
import dev.highlights.montage.MusicAnalyzer
import dev.highlights.montage.MusicChoice
import dev.highlights.montage.MusicHistory
import dev.highlights.montage.MusicLibrary
import dev.highlights.montage.OneTaps
import dev.highlights.montage.ScopeCuts
import dev.highlights.scoring.MomentEdits
import dev.highlights.scoring.ScoringEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

data class AnalyzeOptions(
    val profileId: String? = null,
    val threshold: Double? = null,
    val target: SelectionTarget? = null,
    /** Construire les moments autour d'un type d'événement (ex. "kill") plutôt que du seuil. */
    val requiredEvent: String? = null,
    /** Dossier où écrire la session (sous-dossier sessions). null = outputDir de la config. */
    val outputDir: Path? = null,
    /** Reprendre l'analyse déjà faite d'une capture inchangée (voir [AnalysisLibrary]) ; false = tout recalculer. */
    val reuse: Boolean = true,
)

data class ExportOptions(
    val formats: List<OutputFormat>? = null,
    val outputDir: Path? = null,
    /** simple ou story (montage façon YouTube) ; null = réglage du profil. */
    val style: EditStyle? = null,
    /** Musique de fond du montage story ; null = réglage du profil. */
    val music: Path? = null,
    /** Plateforme visée (tiktok, shorts, reels, youtube…) : format, volume, débit, zone sûre et durée maximale. */
    val platform: String? = null,
    /** Ordre des moments ; null = ordre manuel si l'interface en a fixé un ([ClipOrder.MANUAL]), sinon celui du profil. */
    val order: ClipOrder? = null,
    /** Miniature de chaque vidéo, dans cet habillage ([Thumbnails]) ; null : pas de miniature. */
    val thumbnail: ThumbnailStyle? = null,
)

/** Surcharges ponctuelles des réglages de montage du profil (null = valeur du profil). */
data class MontageOptions(
    val formats: List<OutputFormat>? = null,
    val outputDir: Path? = null,
    val maxDuration: Duration? = null,
    /** Durée tirée du nombre de kills, la durée maximale n'étant qu'un plafond ; false : remplir la durée maximale. */
    val fitKills: Boolean? = null,
    val order: MontageOrder? = null,
    val hook: Boolean? = null,
    val effectDensity: EffectDensity? = null,
    val zoom: Boolean? = null,
    val flash: Boolean? = null,
    val flashEveryCut: Boolean? = null,
    /** Ouverture vitrine : passages calmes avant le premier kill (voir [dev.highlights.core.model.ShowcaseOpening]). */
    val showcase: Boolean? = null,
    /** Whip pan dans le sens du flick aux coupes qui en suivent ou en précèdent un. */
    val whip: Boolean? = null,
    /** Souffle (whoosh) sur les whip pans ; faux : whip pan muet. */
    val whoosh: Boolean? = null,
    /** Dossier de souffles qui remplace les souffles générés (voir `WhipPanEffect.sounds`). */
    val whooshDir: Path? = null,
    /** Couleurs boostées (vibrance, saturation, contraste) comme les kill montages de TikTok. */
    val boostColors: Boolean? = null,
    /** Flou de mouvement : traînées sur les mouvements rapides et flou radial sur les coupes franches. */
    val motionBlur: Boolean? = null,
    /** Raccords sur les animations du jeu (rechargement, sprint, sort…) qui reviennent d'un clip à l'autre. */
    val matchCut: Boolean? = null,
    /** Classement des kills selon leur round (mort juste après, ace, clutch) ; false : les morts sont ignorées. */
    val rounds: Boolean? = null,
    val slowMotion: Boolean? = null,
    val speedRamp: Boolean? = null,
    val text: Boolean? = null,
    /** Compteur de kills à l'écran ([KillCounterMode]) ; null : réglage du profil (désactivé par défaut). */
    val killCounter: dev.highlights.core.model.KillCounterMode? = null,
    /** Équilibre jeu / musique, de -1 (musique devant) à 1 (jeu devant). */
    val audioBalance: Double? = null,
    val gameAudio: GameAudio? = null,
    /** Mettre en avant voix et rires (plans prolongés, micro monté, musique baissée dessous). */
    val reactions: Boolean? = null,
    /** Musique prise depuis son début, au lieu du passage le plus intense autour de la drop. null : réglage de chaque musique. */
    val musicFromStart: Boolean? = null,
    /**
     * Musiques d'une bibliothèque à prendre depuis leur début (choix retenu pour chacune dans l'application), quand
     * [musicFromStart] ne tranche pas pour toutes.
     */
    val fromStartMusics: Set<Path> = emptySet(),
    /** Plateforme visée : format, volume, débit, zone sûre, et la durée maximale devient un plafond de plus. */
    val platform: String? = null,
    /** Seulement ces moments (résultat d'une recherche, voir [MomentPick]) ; null : tous les kills des sessions. */
    val onlyKills: MomentPick? = null,
    /**
     * Montage « onetaps » : que les kills d'une balle à la tête, un plan très court chacun ([MontageSettings.forOneTaps]) ;
     * null : celui du style, sinon non.
     */
    val oneTaps: Boolean? = null,
    /** Rythme rapide : un kill par temps de musique ([MontageSettings.forFast]) ; null : réglage du profil. */
    val fast: Boolean? = null,
    /** Style de montage ([dev.highlights.core.model.MontageStyle]) : remplit les réglages laissés à null ci-dessus. */
    val style: String? = null,
    /** Corrections de l'utilisateur sur le plan : kills exclus, groupes forcés ou épinglés (voir [MontageConstraints]). */
    val constraints: MontageConstraints = MontageConstraints.NONE,
    /** Rendre une des variantes proposées en aperçu ([HighlightPipeline.montagePreviews]) ; null : la mieux notée, sans aperçu. */
    val pick: VariantPick? = null,
    /** Miniature de chaque vidéo du rendu final, dans cet habillage ([Thumbnails]) ; jamais pour les aperçus ; null : aucune. */
    val thumbnail: ThumbnailStyle? = null,
)

/**
 * Variante choisie après les aperçus : [index] de 1 (la mieux notée) à n. [variantsFile] : fichier des variantes écrit
 * par les aperçus ; le montage recalculé doit avoir la même empreinte que l'aperçu regardé, et le choix y est rattaché.
 */
data class VariantPick(val index: Int, val variantsFile: Path? = null) {
    init {
        require(index >= 1) { "variante numérotée à partir de 1" }
    }
}

/** Aperçu d'une variante : la vidéo basse résolution, son rapport, les images de sa vignette animée. */
data class VariantPreview(val entry: MontageVariantEntry, val video: Path, val report: Path, val frames: List<Path>)

/**
 * État des goûts appris ([HighlightPipeline.taste]) : [file] l'historique, [choices] et [edits] ce qu'il contient,
 * [learned] les poids, [enabledProfiles] les profils qui s'en servent (`montage.learnTaste`). [last] : le dernier choix,
 * avec la note de chacune de ses variantes selon les poids d'origine ([lastBefore]) et appris ([lastAfter]).
 */
data class TasteState(
    val file: Path,
    val choices: Int,
    val edits: Int,
    val learned: LearnedTaste,
    val enabledProfiles: List<String>,
    val last: TasteChoice?,
    val lastBefore: List<Double>?,
    val lastAfter: List<Double>?,
)

/** Aperçus des variantes d'un montage et le fichier qui les décrit (à passer à [VariantPick]). */
data class MontagePreviews(
    val variantsFile: Path,
    val previews: List<VariantPreview>,
    /** Avertissements du lot (peu de kills trouvés…), une fois pour toutes les variantes, pas une par aperçu. */
    val notices: List<Message> = emptyList(),
)

/**
 * Montage kills planifié, pas encore rendu ([HighlightPipeline.planKillMontage]) : [plan] ce qui sera rendu ; [candidates]
 * tous les groupes de kills avant les corrections de l'utilisateur (ceux que le plan écarte compris), pour les montrer.
 */
class PlannedMontage internal constructor(
    val plan: MontagePlan,
    val candidates: List<KillGroup>,
    internal val choice: List<MontageReportMusic>,
    internal val profile: GameProfile,
    internal val platform: PlatformProfile?,
    /** Variantes proposées en aperçu ([MontageVariants.distinct]), finies comme [plan] ; [plan] seul sans aperçu. */
    internal val variants: List<MontagePlan> = listOf(plan),
    /** Fichier des variantes relu pour [MontageOptions.pick] ; null sans choix ou sans fichier. */
    internal val variantsFile: MontageVariantsFile? = null,
    /** Kills trouvés dans les parties (avant le tri des one taps et les corrections), pour l'avertissement [fewKills]. */
    val killsFound: Int = candidates.sumOf { it.kills.size },
) {
    /**
     * Avertissement quand les parties n'ont presque pas de kills ([MontageSettings.minKills]) : le montage se fait,
     * mais c'est le plus souvent une détection en défaut, à vérifier. Pas pour une sélection faite à la main (recherche).
     */
    fun fewKills(options: MontageOptions): Message? = fewKillsNotice(killsFound, plan.settings.minKills, options.onlyKills != null)

    companion object {
        /** Avertissement pour [found] kills trouvés quand il en faut [minKills] ; aucun sur une sélection à la main ([picked]). */
        fun fewKillsNotice(found: Int, minKills: Int, picked: Boolean): Message? =
            Message("notice.montage.fewKills", "count" to found).takeIf { !picked && found < minKills }
    }
}

/** [reused] : analyse reprise de la mémoire, sans recalcul. */
data class AnalysisOutcome(val session: Session, val sessionFile: Path, val profile: GameProfile, val reused: Boolean = false)

data class ProcessOutcome(val analysis: AnalysisOutcome, val export: ExportResult?)

/** Analyse de plusieurs captures pour un seul montage : une session par capture, dans l'ordre d'enregistrement. */
data class BatchOutcome(val analyses: List<AnalysisOutcome>, val export: ExportResult?) {
    val sessions: List<Session> get() = analyses.map { it.session }
}

/** Point d'entrée du cœur, indépendant de toute interface (CLI, Compose, watch folder). */
class HighlightPipeline(
    private val config: LoadedConfig,
    private val ffmpeg: FfmpegService,
    profiles: ProfileRepository,
    private val detectors: DetectorRegistry,
    private val scoring: ScoringEngine,
    private val exporter: Exporter,
    private val montageExporter: KillMontageExporter,
) {
    @Volatile
    var profiles: ProfileRepository = profiles
        private set

    /** Dossier de sortie par défaut (app.yaml). */
    val defaultOutputDir: Path get() = config.outputDir

    /** Dossier des aperçus temporaires (hors dossier de sortie). */
    val previewDir: Path get() = config.workDir.resolve("cache").resolve("previews")

    val profilesDir: Path get() = config.profilesDir

    /**
     * Diagnostic de la machine et de la configuration (rapport de problème, commande `doctor`) ; null si FFmpeg n'est
     * pas un vrai processus (tests).
     */
    suspend fun diagnostics(): List<DiagnosticSection>? =
        (ffmpeg as? dev.highlights.ffmpeg.ProcessFfmpegService)?.let { Diagnostics(config, it, profiles, detectors).run() }

    /** Musiques déjà analysées : une bibliothèque se réessaie à chaque montage sans tout redécoder. */
    private val musicLibrary = MusicLibrary(config.workDir.resolve("cache").resolve("music"))

    /** Kills et poses de l'arme déjà inspectés : un nouveau montage des mêmes parties ne redécode que les nouveaux. */
    private val inspections = InspectionCache(config.workDir.resolve("cache").resolve("kills"))

    /** Musiques des derniers montages : une bibliothèque ne ressort pas toujours la même. */
    val musicHistory = MusicHistory(config.outputDir.resolve("sessions").resolve("music-history.json"))
    private val thumbnails = Thumbnails(ffmpeg)

    /** Choix de variantes et corrections de l'éditeur : de quoi apprendre les goûts de l'utilisateur ([TasteLearner]). */
    val tasteHistory = TasteHistory(config.outputDir.resolve("sessions").resolve("taste-history.json"))

    /** Soirées déjà montées par le montage de soirée (voir [EveningHistory]). */
    val eveningHistory = EveningHistory(config.outputDir.resolve("sessions").resolve("evening-montages.json"))

    /** Analyses déjà faites : une capture inchangée n'est pas réanalysée. */
    val library = AnalysisLibrary(config.outputDir.resolve("sessions").resolve("library.json"))

    /** Publication sur YouTube ; [browse] ouvre la page de connexion de Google. */
    fun youtube(browse: (java.net.URI) -> Unit): YouTubePublisher = YouTubePublisher(config, ffmpeg, { profiles }, browse)

    /**
     * Bilan de chaque partie analysée, pour les statistiques par soirée ([Statistics.evenings]). Une partie par capture
     * (la plus récente analyse ; la même capture analysée sous deux chemins aussi : même instant, même durée), les clips
     * plus courts que [Statistics.MIN_GAME] écartés, et les jeux dont le profil ne voit aucun kill aussi. Les sessions
     * illisibles sont ignorées.
     */
    fun statistics(): List<GameStats> = analyzedGames().flatMap { g -> g.sessions.map { (file, session) -> g.stats(file, session) } }

    /** Soirées des parties analysées (voir [EveningPlanner.of]), la plus récente d'abord. */
    fun evenings(): List<Evening> = EveningPlanner.of(statistics())

    /**
     * Montage kills d'une soirée entière ([killMontage] de toutes ses parties), retenu dans [eveningHistory] : le
     * montage automatique ne la refera pas. Un échec y est retenu aussi, puis relancé.
     */
    suspend fun eveningMontage(evening: Evening, music: Path, options: MontageOptions, progress: ProgressReporter): ExportResult {
        val sessions = evening.sessionFiles.map(SessionStore::load)
        return try {
            killMontage(sessions, music, options, progress).also { eveningHistory.record(evening, it.videos.values.firstOrNull()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eveningHistory.record(evening, null, e.message ?: e.toString())
            throw e
        }
    }

    /**
     * Moments (groupes de kills, comme le montage les forme) des parties analysées qui répondent à [query], du plus
     * important au moins important. Mêmes parties que les statistiques : une même partie enregistrée deux fois ne
     * donne pas deux fois ses moments. Les parties dont la capture n'existe plus (effacée depuis l'analyse) restent
     * dans les statistiques, pas dans la recherche : leurs moments ne se monteraient pas.
     */
    fun search(query: MomentQuery): List<FoundMoment> {
        val games = analyzedGames()
        val kept = Statistics.distinctGames(games.flatMap { g -> g.sessions.map { (file, session) -> g.stats(file, session) } })
            .map { it.sessionFile }.toSet()
        return games.flatMap { g ->
            g.sessions.filter { it.first in kept && it.second.media.path.isRegularFile() }.flatMap { (file, session) ->
                dev.highlights.montage.MontagePlanner.groups(listOf(session), g.settings)
                    .map { FoundMoment.of(file, g.name, g.profileId, session.media.recordedAt, it) }
            }
        }.filter { query.matches(it) }.sortedWith(compareByDescending<FoundMoment> { it.rank }.thenByDescending { it.playedAt })
    }

    /**
     * Parties analysées d'un même profil, et ce qu'il sait détecter. [outdated] : sessions analysées avec d'autres
     * réglages de détection que ceux du profil aujourd'hui (empreinte de la bibliothèque).
     */
    private class AnalyzedGames(
        val profileId: String,
        val name: String,
        val settings: dev.highlights.core.model.MontageSettings,
        val sessions: List<Pair<Path, Session>>,
        val outdated: Set<Path>,
    ) {
        private val deaths = Statistics.sources(sessions.map { it.second }, settings.killStyle.deathKinds)
        private val headshots = Statistics.sources(sessions.map { it.second }, settings.killStyle.headshotEvent)
        private val clutches = Statistics.sources(sessions.map { it.second }, settings.killStyle.clutchEvent)

        fun stats(file: Path, session: Session) = Statistics.game(session, settings, file, name, deaths, headshots, file in outdated, clutches)
    }

    /**
     * Réanalyse les captures dont l'analyse date d'anciens réglages de détection (voir [GameStats.outdated]), pour que
     * leurs statistiques soient à jour. Leur session est remplacée (les moments décochés redeviennent cochés). Renvoie
     * le nombre de captures réanalysées ; celles qui n'existent plus sont passées.
     */
    suspend fun refreshOutdated(progress: ProgressReporter): Int {
        val outdated = statistics().filter { it.outdated && it.source.isRegularFile() }
        outdated.forEachIndexed { i, g ->
            analyze(g.source, AnalyzeOptions(profileId = g.profileId, reuse = false), progress.child("${g.source.fileName} (${i + 1}/${outdated.size})", 1.0 / outdated.size))
        }
        return outdated.size
    }

    private fun analyzedGames(): List<AnalyzedGames> {
        val entries = library.entries()
        val fingerprints = entries.associate { it.sessionFile to it.fingerprint }
        val loaded = entries
            .filter { it.duration >= Statistics.MIN_GAME }
            .distinctBy { it.source }
            .distinctBy { (it.recordedAt ?: it.analyzedAt) to it.duration }
            .mapNotNull { e ->
                runCatching { e.sessionFile to SessionStore.load(e.sessionFile) }
                    .onFailure { log.warn { "Session illisible pour les statistiques (${e.sessionFile}) : ${it.message}" } }
                    .getOrNull()
            }
        return loaded.groupBy { it.second.profileId }.mapNotNull { (profileId, list) ->
            val profile = runCatching { profiles.byId(profileId) }.getOrNull()
            val settings = profile?.montage ?: dev.highlights.core.model.MontageSettings()
            if (Statistics.sources(list.map { it.second }, settings.killEvent).isEmpty()) return@mapNotNull null
            val current = profile?.let(AnalysisLibrary::fingerprint)
            val outdated = list.map { it.first }.filter { file -> current != null && fingerprints[file] != null && fingerprints[file] != current }.toSet()
            AnalyzedGames(profileId, profile?.displayName ?: profileId, settings, list, outdated)
        }
    }

    /** Plateformes connues (app.yaml compris), par identifiant. */
    val platforms: Map<String, PlatformProfile> get() = config.platforms

    /** Styles de montage kills (`config/montage-styles.yaml`), relus à chaque appel : une modification du fichier compte aussitôt. */
    val montageStyles: MontageStyles get() = config.loadMontageStyles()

    /** Détecteurs qui comptent les balles d'un kill (montage onetaps). */
    private val SHOT_SOURCES = setOf("ammo-counter", "game-sounds")

    /** Options complétées par leur style ([MontageOptions.style]), s'il y en a un. */
    private fun styled(options: MontageOptions): MontageOptions = options.style?.let { options.withStyle(montageStyle(it)) } ?: options

    /** Style [id], ou une erreur qui liste ceux qui existent. */
    fun montageStyle(id: String): MontageStyle = montageStyles.let { all ->
        all.byId(id) ?: throw InputException(Message("error.montageStyle.unknown", "id" to id, "known" to all.styles.joinToString { it.id }))
    }

    private fun platform(id: String?): PlatformProfile? = id?.let {
        config.platforms[it] ?: throw InputException(Message("error.platform.unknown", "id" to it, "known" to config.platforms.keys.joinToString()))
    }

    /** Relit les profils sur disque (après modification d'un YAML). */
    fun reloadProfiles() {
        profiles = ProfileRepository.loadDirectory(config.profilesDir)
    }

    /** Profil qui serait utilisé pour ce fichier. */
    fun resolveProfile(file: Path, forcedId: String? = null): GameProfile = profiles.resolve(file, forcedId)

    /**
     * Réglages d'édition du profil, table d'étalonnage résolue depuis le dossier de configuration (comme les modèles
     * d'images du HUD) : le rendu ne connaît que des chemins absolus.
     */
    private fun GameProfile.gradedEdit(): EditSettings {
        val story = edit.story
        return edit.copy(
            grade = edit.grade.copy(lut = edit.grade.lut?.let { config.resolve(it).toString() }),
            story = story.copy(
                music = story.music.copy(file = story.music.file?.let { config.resolve(it).toString() }),
                captions = story.captions.copy(model = story.captions.model?.let { config.resolve(it).toString() }),
                sfx = story.sfx.copy(
                    whooshFile = story.sfx.whooshFile?.let { config.resolve(it).toString() },
                    impactFile = story.sfx.impactFile?.let { config.resolve(it).toString() },
                    whooshDir = story.sfx.whooshDir?.let { config.resolve(it).toString() },
                    impactDir = story.sfx.impactDir?.let { config.resolve(it).toString() },
                ),
            ),
        )
    }

    suspend fun probe(file: Path): MediaInfo {
        validateInput(file)
        return ffmpeg.probe(file)
    }

    suspend fun analyze(file: Path, options: AnalyzeOptions, progress: ProgressReporter): AnalysisOutcome =
        analyze(file, options, progress, file.nameWithoutExtension)

    /** [sessionName] : nom du fichier de session, sans extension. */
    private suspend fun analyze(file: Path, options: AnalyzeOptions, progress: ProgressReporter, sessionName: String): AnalysisOutcome {
        validateInput(file)
        val profileForFile = profiles.resolve(file, options.profileId)
        val fingerprint = AnalysisLibrary.fingerprint(profileForFile)
        val stamp = AnalysisLibrary.stamp(file)
        if (options.reuse) {
            library.find(file, profileForFile.id, fingerprint)?.let { entry -> reuse(entry, profileForFile, options, progress)?.let { return it } }
        }
        val probeStep = progress.child(tr("progress.probe"), 0.02)
        val media = ffmpeg.probe(file)
        probeStep.complete()
        if (media.video == null) throw InputException(Message("error.input.noVideo", "file" to file))
        if (!media.duration.isPositive()) throw InputException(Message("error.input.zeroDuration", "file" to file))

        val profile = profileForFile
        val selection = profile.selection.let { s ->
            s.copy(threshold = options.threshold ?: s.threshold, target = options.target ?: s.target, requiredEvent = options.requiredEvent)
        }
        log.info { "Analyse de ${media.path} (${media.duration}, ${media.audio.size} piste(s) audio) avec le profil ${profile.id}" }

        val grid = WindowGrid(profile.window.size, profile.window.hop, media.duration)
        val warnings = Collections.synchronizedList(mutableListOf<Message>())

        val tracks = withJobDir { workDir ->
            runDetectors(profile, media, grid, workDir, progress.child(tr("progress.analysis"), 0.93), warnings)
        }
        if (tracks.all { it.second.isMissing }) {
            throw HighlightsException(Message("error.analysis.noSignal", "file" to file, "reasons" to warnings.toList().ifEmpty { listOf(Message("notice.detectors.empty")) }))
        }

        val scoreStep = progress.child(tr("progress.scoring"), 0.05)
        val timeline = scoring.score(tracks, grid)
        val highlights = scoring.select(timeline, selection, media.path)
        scoreStep.complete()

        if (highlights.isEmpty()) {
            warnings += Message("notice.belowThreshold", "threshold" to selection.threshold, "max" to (timeline.total.maxOrNull() ?: 0.0))
        }
        val session = Session(
            createdAt = Instant.now(),
            media = media,
            profileId = profile.id,
            timeline = timeline,
            highlights = highlights,
            warnings = warnings.map { it.english() },
            notices = warnings.toList(),
        )
        val sessionFile = (options.outputDir ?: config.outputDir).resolve("sessions").resolve("$sessionName.session.json")
        SessionStore.save(session, sessionFile)
        log.info { "${highlights.size} moment(s) retenu(s), session : $sessionFile" }
        val outcome = AnalysisOutcome(session, sessionFile, profile)
        // Empreinte prise avant l'analyse : une capture encore en cours d'écriture ne passe pas pour analysée en entier.
        if (stamp != null && AnalysisLibrary.stamp(file) == stamp) library.record(libraryEntry(outcome, fingerprint, stamp))
        return outcome
    }

    /**
     * Reprend une analyse de la mémoire : la timeline est gardée, seule la sélection est recalculée avec les réglages
     * demandés (les moments décochés le restent). null si la session n'est plus lisible : on réanalyse alors.
     */
    private fun reuse(entry: LibraryEntry, profile: GameProfile, options: AnalyzeOptions, progress: ProgressReporter): AnalysisOutcome? {
        val stored = try {
            SessionStore.load(entry.sessionFile)
        } catch (e: HighlightsException) {
            log.warn { "Analyse enregistrée illisible, nouvelle analyse : ${e.message}" }
            return null
        }
        // Deux captures du même nom dans des dossiers différents écrivent la même session : celle-ci a pu être remplacée.
        if (stored.media.path.toAbsolutePath().normalize() != entry.source || stored.profileId != entry.profileId) return null
        val session = reselect(stored, options.threshold, options.target, options.requiredEvent)
        SessionStore.save(session, entry.sessionFile)
        progress.child(tr("progress.reused"), 1.0).complete()
        log.info { "${entry.source} déjà analysé le ${entry.analyzedAt} : analyse reprise (${entry.sessionFile})" }
        return AnalysisOutcome(session, entry.sessionFile, profile, reused = true)
    }

    /**
     * Analyse plusieurs captures d'une même soirée pour en faire un seul montage. Chaque capture garde sa session ;
     * la cible (top N, durée) vaut pour l'ensemble : les meilleurs moments de toutes les parties, pas de chacune.
     * Les sessions sont rendues dans l'ordre d'enregistrement des captures.
     */
    suspend fun analyzeAll(files: List<Path>, options: AnalyzeOptions, progress: ProgressReporter): List<AnalysisOutcome> {
        if (files.isEmpty()) throw InputException(Message("error.analysis.noVideos"))
        files.forEach(::validateInput)
        val distinct = files.map { it.toAbsolutePath().normalize() }.distinct()
        // Deux captures du même nom (dossiers différents) ne doivent pas écrire la même session.
        val names = distinct.map { it.nameWithoutExtension }
        val outcomes = distinct.mapIndexed { i, file ->
            val name = if (names.count { it == names[i] } > 1) "${file.parent?.fileName ?: "capture"}_${names[i]}_${i + 1}" else names[i]
            analyze(file, options, progress.child("${file.fileName} (${i + 1}/${distinct.size})", 1.0 / distinct.size), name)
        }.sortedWith(compareBy(MediaInfo.RECORDING_ORDER) { it.session.media })
        if (outcomes.size == 1) return outcomes
        val reselected = reselectAll(outcomes.map { it.session }, options.threshold, options.target, options.requiredEvent)
        return outcomes.zip(reselected) { outcome, session ->
            SessionStore.save(session, outcome.sessionFile)
            outcome.copy(session = session)
        }.also { all -> log.info { "${all.sumOf { it.session.highlights.size }} moment(s) retenu(s) sur ${all.size} captures" } }
    }

    suspend fun export(session: Session, options: ExportOptions, progress: ProgressReporter): ExportResult =
        export(listOf(session), options, progress)

    /**
     * Clip « meilleur moment » : un seul moment de [session] (le meilleur, ou [ClipOptions.moment]) en vidéo 9:16 de
     * 15 à 30 s, montée en style story, sans musique imposée. Voir [MomentClips] et [dev.highlights.core.model.ClipSettings].
     */
    suspend fun clip(session: Session, options: ClipOptions, progress: ProgressReporter): ExportResult {
        val profile = profiles.byId(session.profileId)
        val platform = platform(options.platform)
        val settings = MomentClips.settings(profile.gradedEdit()).let { platform?.applyTo(it) ?: it }
            // Pas de musique de fond dans un clip : l'action d'origine, le son du jeu et la voix.
            .let { it.copy(story = it.story.copy(music = it.story.music.copy(file = null))) }
        val moment = MomentClips.pick(session, options.moment)
        val max = platform?.maxDuration?.let { minOf(it, settings.clip.maxDuration) } ?: settings.clip.maxDuration
        val clipped = MomentClips.window(session, moment, settings, max)
        log.info { "Clip du moment ${moment.id} : ${clipped.highlights.single().range}" }
        return withJobDir { workDir ->
            exporter.export(
                listOf(clipped),
                ExportRequest(
                    settings = settings,
                    outputDir = options.outputDir ?: config.outputDir,
                    workDir = workDir,
                    gameName = profile.id,
                    audioBitrate = config.app.encoder.audioBitrate,
                    hwaccel = Hwaccel.resolve(config.app.ffmpeg.hwaccelDecode),
                    audioLayout = profile.audio,
                    captionCache = config.workDir.resolve("cache").resolve("captions"),
                    platform = options.platform,
                    maxDuration = max,
                    kind = "clip_${moment.id}",
                ),
                progress,
            )
        }.let { result -> options.thumbnail?.let { result.copy(thumbnails = thumbnails.makeAll(result.videos.values, it)) } ?: result }
    }

    /** Un seul montage des segments cochés de toutes les [sessions]. Réglages du profil de la première. */
    suspend fun export(sessions: List<Session>, options: ExportOptions, progress: ProgressReporter): ExportResult {
        if (sessions.isEmpty()) throw InputException(Message("error.export.noSession"))
        val profile = profiles.byId(sessions.first().profileId)
        val edit = profile.gradedEdit()
        val platform = platform(options.platform)
        val settings: EditSettings = edit.copy(
            formats = options.formats ?: edit.formats,
            style = options.style ?: edit.style,
            order = options.order ?: if (sessions.any { s -> s.highlights.any { it.enabled && it.order != null } }) ClipOrder.MANUAL else edit.order,
            story = options.music?.let { edit.story.copy(music = edit.story.music.copy(file = it.toAbsolutePath().toString())) } ?: edit.story,
        ).let { platform?.applyTo(it) ?: it }
        return withJobDir { workDir ->
            exporter.export(
                sessions,
                ExportRequest(
                    settings = settings,
                    outputDir = options.outputDir ?: config.outputDir,
                    workDir = workDir,
                    gameName = profile.id,
                    audioBitrate = config.app.encoder.audioBitrate,
                    hwaccel = Hwaccel.resolve(config.app.ffmpeg.hwaccelDecode),
                    audioLayout = profile.audio,
                    captionCache = config.workDir.resolve("cache").resolve("captions"),
                    platform = options.platform,
                    maxDuration = platform?.maxDuration,
                ),
                progress,
            )
        }.let { result -> options.thumbnail?.let { result.copy(thumbnails = thumbnails.makeAll(result.videos.values, it)) } ?: result }
    }

    /** Miniature YouTube de [video] (highlights ou montage kills), d'après le rapport écrit avec elle ([Thumbnails]). */
    suspend fun miniature(video: Path, style: ThumbnailStyle = ThumbnailStyle.SOBER): Path = thumbnails.make(video, style)

    /** Aperçu PNG des formats de sortie à un instant donné, sans analyse. */
    suspend fun preview(file: Path, at: Duration, profileId: String?, formats: List<OutputFormat>?, outputDir: Path?): List<Path> {
        validateInput(file)
        val media = ffmpeg.probe(file)
        if (media.video == null) throw InputException(Message("error.input.noVideo", "file" to file))
        if (at < Duration.ZERO || at >= media.duration) {
            throw InputException(Message("error.preview.outOfRange", "at" to at.toTimecode(), "duration" to media.duration.toTimecode()))
        }
        val profile = profiles.resolve(file, profileId)
        val edit = profile.gradedEdit()
        val settings = formats?.let { edit.copy(formats = it) } ?: edit
        return withJobDir { workDir ->
            exporter.preview(media, at, settings, (outputDir ?: config.outputDir).resolve("previews"), workDir)
        }
    }

    /**
     * Recalcule la sélection depuis la timeline de la session, sans réanalyser (instantané).
     * Les segments recouvrant un segment décoché restent décochés ; les moments retouchés ou ajoutés à la main restent
     * tels quels ([MomentEdits.preserveManual]).
     */
    fun reselect(session: Session, threshold: Double?, target: SelectionTarget?, requiredEvent: String? = null): Session {
        val policy = profiles.byId(session.profileId).selection.let { s ->
            s.copy(threshold = threshold ?: s.threshold, target = target ?: s.target, requiredEvent = requiredEvent)
        }
        val fresh = scoring.select(session.timeline, policy, session.media.path)
        return session.copy(highlights = MomentEdits.preserveManual(session.highlights, fresh))
    }

    /**
     * [reselect] sur plusieurs captures à la fois : la cible est partagée entre elles. Seuil, marges et cible viennent
     * du profil de la première session, comme pour l'export.
     */
    fun reselectAll(sessions: List<Session>, threshold: Double?, target: SelectionTarget?, requiredEvent: String? = null): List<Session> {
        if (sessions.size <= 1) return sessions.map { reselect(it, threshold, target, requiredEvent) }
        val policy = profiles.byId(sessions.first().profileId).selection.let { s ->
            s.copy(threshold = threshold ?: s.threshold, target = target ?: s.target, requiredEvent = requiredEvent)
        }
        val fresh = scoring.selectAcross(sessions.map { it.timeline to it.media.path }, policy)
        return sessions.zip(fresh) { session, highlights ->
            session.copy(highlights = MomentEdits.preserveManual(session.highlights, highlights))
        }
    }

    /** Vignette JPEG d'un instant, mise en cache dans le dossier de travail. */
    suspend fun thumbnail(media: MediaInfo, at: Duration, width: Int = 320): Path =
        exporter.thumbnail(media, at, width, cacheDir(media).resolve("thumb_${at.inWholeMilliseconds}_$width.jpg"))

    /** Extrait basse résolution d'un segment (son du montage), mis en cache. */
    suspend fun clipPreview(session: Session, highlight: Highlight): Path {
        val profile = profiles.byId(session.profileId)
        val audio = profile.edit.audioIndices(AudioTracks.of(session.media.audio, profile.audio)).firstOrNull()
        val name = "clip_${highlight.range.start.inWholeMilliseconds}_${highlight.range.end.inWholeMilliseconds}.mp4"
        return exporter.clipPreview(session.media, highlight.range, audio, cacheDir(session.media).resolve(name))
    }

    private fun cacheDir(media: MediaInfo): Path =
        config.workDir.resolve("cache").resolve("${media.path.nameWithoutExtension}_${media.sizeBytes}")

    /** Analyse chaque musique de [dir] (ou la reprend du cache) : le prochain montage sur ce dossier n'aura plus qu'à choisir. */
    suspend fun loadMusicLibrary(dir: Path, progress: ProgressReporter): List<MusicAnalysis> = musicLibrary.load(ffmpeg, dir, progress)

    /**
     * Montage « tous les kills » calé sur [music] à partir d'une ou plusieurs sessions analysées.
     * Les réglages viennent du profil de la première session, surchargés par [options].
     * [music] peut être un dossier : la musique y est choisie ([MusicChoice]), celle sur laquelle le montage est le mieux noté.
     */
    suspend fun killMontage(sessions: List<Session>, music: Path, requested: MontageOptions, progress: ProgressReporter): ExportResult {
        val options = styled(requested)
        val planned = planMontage(sessions, music, options, progress, count = options.pick?.index)
        val plan = planned.plan
        val pick = options.pick
        val signature = MontageVariants.signature(plan)
        val entry = planned.variantsFile?.variants?.firstOrNull { it.index == pick?.index }
        if (planned.variantsFile != null && entry?.signature != signature) {
            // Réglages, captures ou analyses changés depuis l'aperçu : ce ne serait pas le montage regardé.
            log.warn { "Variante ${pick?.index} : empreinte $signature, aperçu ${entry?.signature}" }
            throw InputException(Message("error.montage.variantChanged", "index" to (pick?.index ?: 0)))
        }
        val result = withJobDir { workDir ->
            montageExporter.export(
                plan,
                montageRequest(planned, sessions, options, workDir, options.outputDir ?: config.outputDir, notices = listOfNotNull(planned.fewKills(options))),
                progress.child(tr("progress.render"), 0.86),
            )
        }
        musicHistory.record(plan.music.file) // Choisie ou imposée : elle a servi, elle se repose un peu.
        if (pick != null) recordChoice(pick, plan, planned.variants, planned.variantsFile, planned.profile.id, result)
        recordTaste(pick, planned, options.constraints, result)
        // Rendu final seulement (variante choisie comprise) : les aperçus de variantes n'en ont pas.
        return options.thumbnail?.let { result.copy(thumbnails = thumbnails.makeAll(result.videos.values, it)) } ?: result
    }

    /**
     * Ce que l'utilisateur a montré de ses goûts sur ce rendu : la variante choisie face aux autres (critères de la note
     * de chacune), et ses corrections dans l'éditeur de plan. Enregistré même quand l'apprentissage est désactivé : le
     * jour où il l'active, ses choix passés comptent déjà.
     */
    private fun recordTaste(pick: VariantPick?, planned: PlannedMontage, constraints: MontageConstraints, result: ExportResult) {
        val now = Instant.now().toString()
        val music = planned.plan.music.file.toString()
        if (pick != null) {
            val variants = planned.variantsFile?.variants?.map { it.score.criteria() }
                ?: planned.variants.map { MontageScorer.score(it).criteria() }
            if (variants.size >= 2 && pick.index - 1 in variants.indices) {
                tasteHistory.record(TasteChoice(now, planned.profile.id, music, pick.index - 1, variants, result.report.toString()))
            }
        }
        if (!constraints.isEmpty) {
            tasteHistory.record(
                TasteEdit(
                    now, planned.profile.id, music,
                    excluded = constraints.excluded.map { it.toString() },
                    forced = constraints.forced.map { it.toString() },
                    pins = constraints.pins.entries.associate { (ref, pin) -> ref.toString() to (if (pin is MontagePin.At) KillRef.clock(pin.music) else "drop") },
                    report = result.report.toString(),
                ),
            )
        }
    }

    /**
     * Goûts appris de l'historique ([TasteLearner]), et ce qu'ils changeraient au dernier choix : le classement des
     * variantes proposées avec les poids d'origine puis appris. Pour `app gouts`.
     */
    fun taste(history: TasteHistory = tasteHistory): TasteState {
        val stored = history.load()
        val learned = TasteLearner.learn(stored.choices)
        val last = stored.choices.lastOrNull()
        return TasteState(
            history.file, stored.choices.size, stored.edits.size, learned,
            enabledProfiles = profiles.all().filter { it.montage.learnTaste }.map { it.id },
            last = last,
            lastBefore = last?.variants?.map { MontageScorer.total(it, learned.base) },
            lastAfter = last?.variants?.map { MontageScorer.total(it, learned.weights) },
        )
    }

    /** Demande de rendu du montage [planned] ; [preview] : aperçu basse résolution ([MontagePreview]). */
    private fun montageRequest(
        planned: PlannedMontage, sessions: List<Session>, options: MontageOptions, workDir: Path, outputDir: Path,
        kind: String? = null, preview: Boolean = false, notices: List<Message> = emptyList(),
    ): MontageExportRequest {
        val profile = planned.profile
        return MontageExportRequest(
            formats = planned.plan.settings.formats,
            edit = profile.gradedEdit().let { planned.platform?.applyTo(it) ?: it },
            outputDir = outputDir,
            workDir = workDir,
            gameName = profile.id,
            date = KillMontageExporter.recordingDate(sessions.first().media.creationTime),
            audioBitrate = config.app.encoder.audioBitrate,
            hwaccel = Hwaccel.resolve(config.app.ffmpeg.hwaccelDecode),
            audioLayout = profile.audio,
            musicChoice = planned.choice,
            platform = options.platform,
            kind = kind ?: montageKind(options),
            preview = preview,
            notices = notices,
        )
    }

    private fun montageKind(options: MontageOptions) = if (options.oneTaps == true) "onetaps" else "killmontage"

    /**
     * Aperçus de [count] variantes du montage ([MontageVariants.distinct]) : basse résolution, rendu rapide, même calage
     * que le rendu final ([MontagePreview]), corrections de l'utilisateur ([MontageOptions.constraints]) comprises.
     * Écrits dans `<sortie>/apercus`, avec le fichier des variantes ([MontageVariantsFile]). La variante choisie se rend
     * ensuite par [killMontage] avec [MontageOptions.pick].
     */
    suspend fun montagePreviews(sessions: List<Session>, music: Path, requested: MontageOptions, count: Int, progress: ProgressReporter): MontagePreviews {
        val options = styled(requested)
        val planned = planMontage(sessions, music, options.copy(pick = null), progress, count = count)
        val plans = planned.variants
        val dir = (options.outputDir ?: config.outputDir).resolve(PREVIEW_DIR)
        val kind = montageKind(options)
        val results = withJobDir { workDir ->
            plans.mapIndexed { i, plan ->
                val step = progress.child(tr("progress.preview", "index" to i + 1, "total" to plans.size), 0.86 / plans.size)
                montageExporter.export(plan, montageRequest(planned, sessions, options, workDir.resolve("variante${i + 1}"), dir, "${kind}_variante${i + 1}", preview = true), step)
            }
        }
        val frames = results.mapIndexed { i, r ->
            val video = r.videos.values.first()
            runCatching { MontagePreview.frames(ffmpeg, video, plans[i].duration, previewDir.resolve("variantes").resolve("${video.nameWithoutExtension}_${MontageVariants.signature(plans[i])}")) }
                .onFailure { log.warn { "Vignette animée impossible pour $video : ${it.message}" } }
                .getOrDefault(emptyList())
        }
        val file = OutputNamer.reserve(dir, planned.profile.id, KillMontageExporter.recordingDate(sessions.first().media.creationTime), emptyList(), kind = "${kind}_variantes").report
        val entries = plans.mapIndexed { i, plan -> MontageVariantEntry.of(i + 1, plan, plans.take(i), results[i].videos.values.first(), results[i].report) }
        MontageVariantsFile.save(
            file,
            MontageVariantsFile(
                createdAt = Instant.now().toString(),
                profile = planned.profile.id,
                music = plans.first().music.file.toString(),
                sessions = sessions.map { it.media.path.toString() },
                variants = entries,
            ),
        )
        log.info { "Aperçus : ${plans.size} variante(s) dans $dir, décrites dans ${file.fileName}" }
        return MontagePreviews(
            file,
            results.mapIndexed { i, r -> VariantPreview(entries[i], r.videos.values.first(), r.report, frames[i]) },
            notices = listOfNotNull(planned.fewKills(options)),
        )
    }

    /**
     * Fichier des variantes le plus récent de [outputDir] (dossier `apercus`) pour ces captures et cette musique (un
     * dossier de musiques : n'importe laquelle de ses musiques) ; null si aucun.
     */
    fun latestVariantsFile(sessions: List<Session>, music: Path, outputDir: Path?): Path? {
        val dir = (outputDir ?: config.outputDir).resolve(PREVIEW_DIR)
        if (!dir.isDirectory()) return null
        val media = sessions.map { normalized(it.media.path) }.toSet()
        return dir.listDirectoryEntries("*_variantes*.json").sortedByDescending { it.getLastModifiedTime() }.firstOrNull { f ->
            val v = runCatching { MontageVariantsFile.load(f) }.getOrNull() ?: return@firstOrNull false
            val m = Path.of(v.music)
            v.sessions.map { normalized(Path.of(it)) }.toSet() == media &&
                (normalized(m) == normalized(music) || (music.isDirectory() && normalized(m.parent) == normalized(music)))
        }
    }

    /** Poids appris donnés au scoreur quand le profil apprend les goûts et que l'historique a assez de choix. */
    private fun withTaste(settings: MontageSettings): MontageSettings {
        if (!settings.learnTaste) return settings
        val learned = TasteLearner.learn(tasteHistory.load().choices)
        if (!learned.active) {
            log.info { "Goûts : ${learned.choices} choix sur ${TasteLearner.MIN_CHOICES} nécessaires, poids d'origine" }
            return settings
        }
        log.info { "Goûts appris sur ${learned.choices} choix : ${learned.weights}" }
        return settings.copy(scoreWeights = learned.weights)
    }

    private fun normalized(p: Path) = p.toAbsolutePath().normalize().toString().lowercase()

    /** Choix enregistré à côté du rapport du rendu final ([MontageChoiceRecord]). */
    private fun recordChoice(pick: VariantPick, plan: MontagePlan, plans: List<MontagePlan>, variants: MontageVariantsFile?, profileId: String, result: ExportResult) {
        val entries = variants?.variants ?: plans.mapIndexed { i, p -> MontageVariantEntry.of(i + 1, p, plans.take(i)) }
        val record = MontageChoiceRecord(
            chosenAt = MontageChoiceRecord.now(),
            chosen = pick.index,
            signature = MontageVariants.signature(plan),
            variantsFile = pick.variantsFile?.toString(),
            profile = profileId,
            music = plan.music.file.toString(),
            variants = entries,
            outputs = result.videos.values.map { it.toString() },
            report = result.report.toString(),
        )
        MontageChoiceRecord.save(MontageChoiceRecord.fileFor(result.report), record)
        log.info { "Choix enregistré : variante ${pick.index} sur ${entries.size} → ${MontageChoiceRecord.fileFor(result.report).fileName}" }
    }

    /**
     * Plan du montage kills de [killMontage], sans le rendre : la musique, les kills inspectés (repris du cache
     * d'inspection d'un plan à l'autre), le plan. Sert à la frise de l'application, qui le recalcule à chaque correction.
     */
    suspend fun planKillMontage(sessions: List<Session>, music: Path, requested: MontageOptions, progress: ProgressReporter): PlannedMontage =
        planMontage(sessions, music, requested.copy(pick = null), progress, count = null)

    /**
     * [planKillMontage] ; [count] : nombre de variantes à proposer ([MontageVariants.distinct]) au lieu du seul plan de
     * [MontagePlanner.best]. Avec [MontageOptions.pick], [PlannedMontage.plan] est la variante choisie.
     */
    private suspend fun planMontage(sessions: List<Session>, music: Path, requested: MontageOptions, progress: ProgressReporter, count: Int?): PlannedMontage {
        // Style déjà appliqué par [killMontage] : le rejouer ne change rien (il ne remplit que les réglages restés à null).
        val options = styled(requested)
        if (sessions.isEmpty()) throw InputException(Message("error.montage.noSession"))
        val profile = profiles.byId(sessions.first().profileId)
        val base = profile.montage
        val platform = platform(options.platform)
        val maxDuration = (options.maxDuration ?: base.maxDuration).let { d -> platform?.maxDuration?.let { minOf(it, d) } ?: d }
        val settings = base.copy(
            formats = platform?.let { listOf(it.format) } ?: options.formats ?: base.formats,
            maxDuration = maxDuration,
            length = base.length.copy(fitKills = options.fitKills ?: base.length.fitKills),
            order = options.order ?: base.order,
            hook = options.hook ?: base.hook,
            cuts = base.cuts.copy(fromStart = options.musicFromStart ?: base.cuts.fromStart),
            effectDensity = options.effectDensity ?: base.effectDensity,
            zoom = base.zoom.copy(enabled = options.zoom ?: base.zoom.enabled),
            flash = base.flash.copy(
                enabled = options.flash ?: base.flash.enabled,
                onEveryCut = options.flashEveryCut ?: base.flash.onEveryCut,
            ),
            whip = base.whip.copy(
                enabled = options.whip ?: base.whip.enabled,
                whoosh = if (options.whoosh == false) 0.0 else base.whip.whoosh,
                sounds = (options.whooshDir?.toString() ?: base.whip.sounds)?.let { config.resolve(it).toString() },
            ),
            motionBlur = base.motionBlur.copy(enabled = options.motionBlur ?: base.motionBlur.enabled),
            colors = base.colors.copy(
                enabled = options.boostColors ?: base.colors.enabled,
                lut = base.colors.lut?.let { config.resolve(it).toString() },
            ),
            matchCut = base.matchCut.copy(
                enabled = options.matchCut ?: base.matchCut.enabled,
                weapon = base.matchCut.weapon?.let { it.copy(template = config.resolve(it.template).toString()) },
            ),
            // Sans événement de mort, ni round, ni ace, ni clutch, ni pénalité : le classement redevient celui d'avant.
            killStyle = if (options.rounds == false) base.killStyle.copy(deathEvent = "") else base.killStyle,
            slowMotion = base.slowMotion.copy(enabled = options.slowMotion ?: base.slowMotion.enabled),
            speedRamp = base.speedRamp.copy(enabled = options.speedRamp ?: base.speedRamp.enabled),
            text = base.text.copy(enabled = options.text ?: base.text.enabled, killCounter = options.killCounter ?: base.text.killCounter),
            showcase = base.showcase.copy(enabled = options.showcase ?: base.showcase.enabled),
            reactions = options.reactions ?: base.reactions,
            audio = base.audio.copy(
                balance = options.audioBalance ?: base.audio.balance,
                game = options.gameAudio ?: base.audio.game,
                loudnessLufs = platform?.loudnessLufs ?: base.audio.loudnessLufs,
            ),
        ).let {
            when {
                options.oneTaps == true -> it.forOneTaps()
                options.fast ?: base.fast.enabled -> it.forFast()
                else -> it
            }
        }.let(::withTaste)
        val musicStep = progress.child(tr(if (music.isDirectory()) "progress.musics" else "progress.music"), 0.06)
        val musics = if (music.isDirectory()) musicLibrary.load(ffmpeg, music, musicStep) else listOf(MusicAnalyzer.analyze(ffmpeg, music))
        musicStep.complete()
        val picked = MontagePlanner.groups(sessions, settings).let { all -> options.onlyKills?.let { pick -> all.filter(pick::keeps) } ?: all }
        if (picked.isEmpty()) throw InputException(Message("error.montage.pickedMissing"))
        // Une capture effacée ou déplacée depuis l'analyse : ses kills ne se rendraient pas, et FFmpeg ferait échouer
        // tout le montage. On monte les autres.
        val missing = picked.map { it.media.path }.distinct().filterNot { it.isRegularFile() }.toSet()
        missing.forEach { log.warn { "Capture introuvable, ses kills sont écartés du montage : $it" } }
        val available = picked.filterNot { it.media.path in missing }
        if (available.isEmpty()) throw InputException(Message("error.montage.capturesMissing", "files" to missing.joinToString()))
        val found = if (options.oneTaps == true) OneTaps.select(available, sessions, settings, countsShots = profile.detectors.any { it.enabled && it.type in SHOT_SOURCES }).groups else available
        // Les multi-kills étalés deviennent plusieurs plans (classement, aces et clutchs déjà tirés du groupe entier).
        val inspected = MontagePlanner.split(KillInspector(ffmpeg, inspections).inspect(found, settings, profile.audio, progress.child(tr("progress.kills"), 0.06)), settings)
        // Corrections de l'utilisateur : kills exclus retirés, groupes forcés ou épinglés marqués pour le planificateur.
        val edited = MontageEdits.apply(inspected, options.constraints, settings.killStyle)
        if (edited.isEmpty()) throw InputException(Message("error.montage.edit.allExcluded"))
        // La pose de l'arme ne sert qu'aux groupes qui peuvent entrer dans le montage : la lire coûte un décodage par groupe.
        // Ouverture vitrine : sa place est réservée dans la durée maximale, les kills se planifient dans le reste.
        val planning = if (!settings.showcase.enabled) settings
        else settings.copy(maxDuration = (settings.maxDuration - settings.showcase.length).coerceAtLeast(settings.maxDuration / 2))
        val groups = MatchCutter(ffmpeg, inspections).inspect(edited, settings, progress.child(tr("progress.aim"), 0.02), MontagePlanner.placeable(edited, musics, planning))
        val fromStart = options.fromStartMusics.map { it.toAbsolutePath().normalize() }.toSet()
        // Le réglage « depuis le début » de chaque musique, sauf si l'appel l'impose à toutes.
        val fromStartOf = { f: Path -> options.musicFromStart ?: (f.toAbsolutePath().normalize() in fromStart || base.cuts.fromStart) }
        val variants = options.pick?.variantsFile?.let(MontageVariantsFile::load)
        // Variantes : le choix est glouton dans l'ordre des notes, la liste pour n commence par celle pour n − 1 : la
        // variante choisie se retrouve parmi les [index] premières sans savoir combien d'aperçus ont été faits.
        fun plans(prepared: MusicAnalysis, own: MontageSettings) =
            if (count == null) listOf(MontagePlanner.best(groups, prepared, own)) else MontageVariants.distinct(groups, prepared, own, count).map { it.plan }
        val (chosen, choice) = if (music.isDirectory()) {
            // Variante choisie après les aperçus : la musique des aperçus, même si le classement des musiques a bougé
            // depuis (une autre a servi entre-temps et se repose).
            val tried = variants?.let { v -> musics.filter { normalized(it.file) == normalized(Path.of(v.music)) }.ifEmpty { null } } ?: musics
            val ranked = MusicChoice.rank(groups, tried, planning, musicHistory.recent(), fromStartOf)
            val best = ranked.first()
            val plan = best.plan ?: throw HighlightsException(Message("error.montage.noMusicFits", "dir" to music, "reason" to best.error))
            log.info { "Musique choisie : ${best.music.file.fileName} (note ${"%.3f".format(best.score)}, ${best.groups} groupe(s), parmi ${ranked.size})" }
            (if (count == null) listOf(plan) else plans(best.music, MusicChoice.settingsFor(planning, fromStartOf(best.music.file)))) to ranked.map(MontageReportMusic::of)
        } else {
            plans(MusicChoice.prepare(musics.single(), planning), planning) to emptyList()
        }
        // Raccords d'abord : un plan raccordé garde son début dans la pose, il n'est pas coupé dans l'attente.
        val finished = chosen.map { c ->
            MontagePlanner.showcase(MontagePlanner.jumpCuts(ScopeCuts.apply(c)).let { it.copy(settings = it.settings.copy(maxDuration = settings.maxDuration)) })
        }
        val pick = options.pick
        val plan = if (pick == null) {
            finished.first()
        } else {
            finished.getOrNull(pick.index - 1)
                ?: throw InputException(Message("error.montage.variantMissing", "index" to pick.index, "total" to finished.size))
        }
        log.info { "Montage : ${plan.clips.size} clips, ${plan.totalBeats} temps à ${"%.1f".format(plan.music.bpm)} BPM (${plan.duration}), départ musique ${plan.musicStart}" }
        return PlannedMontage(plan, inspected, choice, profile, platform, finished, variants, killsFound = available.sumOf { it.kills.size })
    }

    /** [analyzeAll] puis un seul montage de toutes les captures. */
    suspend fun processAll(files: List<Path>, analyze: AnalyzeOptions, export: ExportOptions, progress: ProgressReporter): BatchOutcome {
        val analyses = analyzeAll(files, analyze.copy(outputDir = analyze.outputDir ?: export.outputDir), progress.child("", 0.6))
        val sessions = analyses.map { it.session }
        if (sessions.none { s -> s.highlights.any { it.enabled } }) return BatchOutcome(analyses, null)
        return BatchOutcome(analyses, export(sessions, export, progress.child(tr("progress.export"), 0.4)))
    }

    suspend fun process(file: Path, analyze: AnalyzeOptions, export: ExportOptions, progress: ProgressReporter): ProcessOutcome {
        val analysis = analyze(file, analyze.copy(outputDir = analyze.outputDir ?: export.outputDir), progress.child("", 0.6))
        if (analysis.session.highlights.isEmpty()) return ProcessOutcome(analysis, null)
        val result = export(analysis.session, export, progress.child(tr("progress.export"), 0.4))
        return ProcessOutcome(analysis, result)
    }

    private suspend fun runDetectors(
        profile: GameProfile,
        media: MediaInfo,
        grid: WindowGrid,
        workDir: Path,
        progress: ProgressReporter,
        warnings: MutableList<Message>,
    ): List<Pair<DetectorConfig, SignalTrack>> {
        val enabled = profile.detectors.filter { it.enabled }
        if (enabled.isEmpty()) throw HighlightsException(Message("error.profile.noDetector", "id" to profile.id))
        val tracks = AudioTracks.of(media.audio, profile.audio)
        log.info { "Pistes audio : ${tracks.describe()}" }
        val steps = enabled.associate { it.id to progress.child(it.id, 1.0 / enabled.size) }

        // Les détecteurs de secours (fallbackFor) attendent le signal qu'ils remplacent : ils ne tournent que s'il est
        // absent, pour ne pas compter deux fois les mêmes événements ni décoder la vidéo pour rien. Seuls les signaux
        // remplaçables sont lus d'abord (base d'Outplayed : un instant) ; les secours nécessaires tournent ensuite en
        // même temps que tous les autres, décodage vidéo et analyse audio en parallèle, au lieu d'attendre leur fin.
        val (dependents, independent) = enabled.partition { it.dependsOn.isNotEmpty() }
        val (fallbacks, others) = independent.partition { cfg -> cfg.fallbackFor != null && enabled.any { it.id == cfg.fallbackFor } }
        val replaceable = fallbacks.mapNotNull { it.fallbackFor }.toSet()
        val (targets, rest) = others.partition { it.id in replaceable }
        val first = if (targets.isEmpty()) emptyList() else runPhase(targets, media, grid, workDir, steps, tracks, warnings)
        val missing = first.filter { it.second.isMissing }.map { it.first.id }.toSet()
        val (needed, skipped) = fallbacks.partition { it.fallbackFor in missing }
        skipped.forEach { steps.getValue(it.id).complete() }
        needed.forEach { log.info { "${it.fallbackFor} sans signal : ${it.id} prend le relais" } }
        val second = runPhase(rest + needed, media, grid, workDir, steps, tracks, warnings)
        // Les détecteurs qui lisent les événements d'autres détecteurs passent en dernier, avec ces événements.
        val done = (first + second).associateBy { it.first.id }
        val third = if (dependents.isEmpty()) emptyList() else runPhase(dependents, media, grid, workDir, steps, tracks, warnings) { cfg ->
            cfg.dependsOn.mapNotNull { done[it]?.second }.flatMap { it.events }.sortedBy { it.at }
        }
        val results = (first + second + third).associateBy { it.first.id }
        return enabled.mapNotNull { results[it.id] }
    }

    /** Lance [configs] ensemble : une seule passe de décodage vidéo partagée, au plus `parallelism` à la fois. */
    private suspend fun runPhase(
        configs: List<DetectorConfig>,
        media: MediaInfo,
        grid: WindowGrid,
        workDir: Path,
        steps: Map<String, ProgressReporter>,
        tracks: AudioTracks,
        warnings: MutableList<Message>,
        events: (DetectorConfig) -> List<SignalEvent> = { emptyList() },
    ): List<Pair<DetectorConfig, SignalTrack>> {
        val instances = configs.map { it to detectors.create(it.type, it.id, it.detectorParams()) }
        val semaphore = Semaphore(config.app.analysis.parallelism)

        // Chaque détecteur déclare d'abord ses besoins (zones vidéo, cadence) : la capture n'est ensuite décodée
        // qu'une fois pour tous ceux qui lisent des images. Une préparation en échec est relancée dans son détecteur,
        // pour être traitée comme n'importe quelle autre panne (continueOnDetectorError).
        val frames = FrameSampler(ffmpeg, media)
        val contexts = instances.map { (cfg, _) ->
            AnalysisContext(media, grid, ffmpeg, workDir, steps.getValue(cfg.id), config.baseDir, frames, tracks, events(cfg))
        }
        val preparations = instances.mapIndexed { i, (_, detector) -> runCatching { detector.prepare(contexts[i]) } }

        return coroutineScope {
            // Les passes vidéo partagées démarrent tout de suite, hors du sémaphore : le décodage recouvre
            // l'analyse audio au lieu d'attendre son tour.
            launch { frames.runAll() }
            instances.mapIndexed { i, (cfg, detector) ->
                val step = contexts[i].progress
                async {
                    semaphore.withPermit {
                        val track = try {
                            preparations[i].getOrThrow()
                            detector.analyze(contexts[i])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!config.app.analysis.continueOnDetectorError) throw e
                            log.error(e) { "Détecteur ${cfg.id} en échec, poursuite sans ce signal" }
                            SignalTrack.missing(cfg.id, grid.count, Message("notice.detector.failed", "detail" to e.userMessage()))
                        }
                        step.complete()
                        track.note?.let { warnings += Message("notice.detector", "id" to cfg.id, "note" to it) }
                        cfg to track
                    }
                }
            }.awaitAll()
        }
    }

    private fun validateInput(file: Path) {
        if (!file.isRegularFile()) throw InputException(Message("error.input.notFound", "file" to file))
        if (!file.isReadable()) throw InputException(Message("error.input.unreadable", "file" to file))
        if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
            throw InputException(Message("error.input.unsupported", "name" to file.fileName.toString(), "expected" to SUPPORTED_EXTENSIONS.joinToString()))
        }
    }

    /** Dossier temporaire propre à un job : supprimé en cas de succès, conservé en cas d'échec pour le diagnostic. */
    private suspend fun <T> withJobDir(block: suspend (Path) -> T): T {
        val dir = config.workDir.resolve("job-${UUID.randomUUID()}").createDirectories()
        val result = try {
            block(dir)
        } catch (e: Throwable) {
            if (e !is CancellationException) log.info { "Fichiers temporaires conservés pour diagnostic : $dir" }
            throw e
        }
        @OptIn(ExperimentalPathApi::class)
        runCatching { dir.deleteRecursively() }.onFailure { log.warn { "Suppression impossible de $dir : ${it.message}" } }
        return result
    }

    companion object {
        /** Conteneurs acceptés : ceux des enregistreurs courants (Outplayed, OBS, ShadowPlay, Medal, Xbox Game Bar). */
        val SUPPORTED_EXTENSIONS = setOf("mp4", "mkv", "mov", "m4v", "avi", "flv", "ts", "mts", "m2ts", "webm", "wmv", "mpg", "mpeg")

        /** Dossier des aperçus de variantes, dans le dossier de sortie. */
        const val PREVIEW_DIR = "apercus"
    }
}
