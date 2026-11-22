package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.Highlight
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import dev.highlights.export.ExportResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.writeBytes
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Rendu hors écran des écrans principaux en PNG (build/ui-snapshots) : vérifie que la composition ne plante pas
 * et permet de relire visuellement la mise en page sans lancer l'application.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiSnapshotTest : FunSpec({
    val outDir = Path("build", "ui-snapshots").createDirectories()

    /**
     * Chaque écran est rendu en français (nom simple), en anglais (`en/`) et dans la pseudo-langue `en-XA` (`long/` :
     * textes allongés de 40 %, comme l'allemand) pour repérer les textes coupés ou qui débordent. Toute autre langue
     * livrée l'est aussi, dans le dossier de son fichier (`ru/`, `ja/`, `zh_CN/`…) : on y voit les caractères que la
     * police de l'interface n'aurait pas (carrés vides).
     */
    val languages = listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/") +
        I18n.available().map { it.tag }.filter { it != "fr" && it != "en" }.sorted().map { it to "$it/" }

    /** La fenêtre du parcours du montage (une fenêtre à part : rendue seule, comme dans l'application). */
    fun renderFlow(name: String, state: UiState): Path {
        var french: Path? = null
        for ((tag, folder) in languages) {
            I18n.withLanguage(tag) {
                val image = SceneRendering.image(1440, 900) { HighlightsTheme { MontageFlowContent(state.montage!!, state.copy(language = tag), NoopActions) } }
                val file = outDir.resolve("$folder$name.png")
                file.parent.createDirectories()
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                if (tag == "fr") french = file
            }
        }
        return french!!
    }

    fun render(name: String, state: UiState, width: Int = 1440, height: Int = 920): Path {
        var french: Path? = null
        for ((tag, folder) in languages) {
            I18n.withLanguage(tag) {
                // Rendue jusqu'au repos avant l'image et la fermeture (voir SceneRendering).
                val image = SceneRendering.image(width, height) { App(state.copy(language = tag), NoopActions) }
                val file = outDir.resolve("$folder$name.png")
                file.parent.createDirectories()
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                if (tag == "fr") french = file
            }
        }
        return french!!
    }

    val media = MediaInfo(
        path = Path("C:/Users/Colin/Videos/Overwolf/Outplayed/WARDOGS/WARDOGS_09-16-2026_18-4-11-925/WARDOGS_09-16-2026_19-1-23-983.mp4"),
        sizeBytes = 5_328_000_000,
        duration = 56.minutes + 59.seconds,
        video = VideoStream(0, "h264", 3440, 1440, 60.0),
        audio = listOf(AudioStream(1, 0, "aac", 2, 48000, "Track1"), AudioStream(2, 1, "aac", 2, 48000, "Track2"), AudioStream(3, 2, "aac", 2, 48000, "Track3")),
    )
    val grid = WindowGrid(1.seconds, 500.milliseconds, media.duration)
    val peaks = listOf(212, 530, 861, 1204, 1490, 1905, 2410, 2980, 3150)
    val random = Random(7)
    val scores = List(grid.count) { i ->
        val t = i * 0.5
        val bumps = peaks.sumOf { p -> 0.85 * exp(-((t - p) * (t - p)) / 30.0) }
        (0.18 + 0.08 * sin(t / 40) + random.nextDouble(0.0, 0.12) + bumps).coerceIn(0.0, 1.0)
    }
    val highlights = peaks.mapIndexed { i, p ->
        Highlight(
            id = "h%03d".format(i + 1),
            source = media.path,
            range = TimeRange((p - 5).seconds, (p + 4).seconds),
            peak = p.seconds,
            score = listOf(0.92, 0.71, 0.86, 0.64, 0.99, 0.77, 0.68, 0.83, 0.61)[i],
            contributions = mapOf("game-audio" to 0.62, "mic-audio" to 0.21),
            enabled = i != 3,
            events = if (i % 3 == 0) mapOf("kill" to 1 + i / 3) else emptyMap(),
        )
    }
    val session = Session(
        createdAt = Instant.EPOCH,
        media = media,
        profileId = "wardogs",
        timeline = ScoredTimeline(
            grid, scores, mapOf("game-audio" to scores),
            events = peaks.mapIndexed { i, p -> TimelineEvent(p.seconds, if (i % 3 == 0) "kill" else "assist", 1.0, "notifications") },
            excluded = listOf(TimeRange(0.seconds, 90.seconds), TimeRange(2300.seconds, 2400.seconds)),
        ),
        highlights = highlights,
    )
    val ready = ConfigStatus.Ready(
        Path("D:/dev/editor/config/app.yaml"),
        Path("D:/dev/editor/config/profiles"),
        listOf(ProfileInfo("default", "Générique"), ProfileInfo("lol", "League of Legends"), ProfileInfo("valorant", "VALORANT"), ProfileInfo("wardogs", "Wardogs")),
    )
    val settings = SettingsState(
        formats = setOf(OutputFormat.SOURCE, OutputFormat.VERTICAL),
        durationText = "60s",
        threshold = 0.6,
        outputDir = Path("D:/Highlights"),
    )
    val source = SourceInfo(media.path, media, "wardogs")

    test("écran d'accueil") {
        render("01_accueil", UiState(config = ready, settings = settings)).fileSize() shouldBeGreaterThan 0L
    }

    test("analyses enregistrées et dossier surveillé") {
        val sessions = Path("D:/Highlights/sessions")
        val outplayed = Path("C:/Users/Colin/Videos/Overwolf/Outplayed")
        val library = listOf(
            LibraryItem(outplayed.resolve("League of Legends/LoL_09-23-2026_21-40.mp4"), sessions.resolve("LoL_09-23-2026_21-40.session.json"), "lol", Instant.parse("2026-09-23T20:25:00Z"), Instant.parse("2026-09-23T19:40:00Z"), 31.minutes, mapOf("kill" to 7, "death" to 3), true),
            LibraryItem(outplayed.resolve("League of Legends/LoL_09-23-2026_20-55.mp4"), sessions.resolve("LoL_09-23-2026_20-55.session.json"), "lol", Instant.parse("2026-09-23T19:36:00Z"), Instant.parse("2026-09-23T18:55:00Z"), 28.minutes, mapOf("kill" to 4, "assist" to 9), true),
            LibraryItem(media.path, sessions.resolve("WARDOGS.session.json"), "wardogs", Instant.parse("2026-09-16T18:10:00Z"), Instant.parse("2026-09-16T17:01:00Z"), media.duration, mapOf("kill" to 12), true),
            LibraryItem(Path("E:/OBS/valorant_old.mkv"), sessions.resolve("valorant_old.session.json"), "valorant", Instant.parse("2026-09-02T21:00:00Z"), null, 42.minutes, emptyMap(), false),
        )
        val state = UiState(
            config = ready,
            settings = settings,
            library = library,
            librarySelection = setOf(library[0].sessionFile, library[1].sessionFile),
            watch = WatchState(
                folder = outplayed,
                current = outplayed.resolve("League of Legends/LoL_09-23-2026_22-20.mp4"),
                fraction = 0.42,
                pending = listOf(outplayed.resolve("VALORANT/Valorant_09-23-2026_22-50.mp4")),
                fresh = setOf(library[0].sessionFile),
            ),
        )
        render("08_bibliotheque", state).fileSize() shouldBeGreaterThan 0L
        render("09_bibliotheque_vide", UiState(config = ready, settings = settings)).fileSize() shouldBeGreaterThan 0L
    }

    test("analyse en cours") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            job = JobState("Analyse de ${media.path.fileName}", 0.43, "Analyse > game-audio", 37.seconds, 49.seconds),
        )
        render("02_analyse", state).fileSize() shouldBeGreaterThan 0L
    }

    test("relecture des segments et export terminé") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json"), selectedId = "h003"),
            lastExport = ExportResult(
                videos = mapOf(
                    OutputFormat.SOURCE to Path("D:/Highlights/wardogs_2026-09-16_highlights.mp4"),
                    OutputFormat.VERTICAL to Path("D:/Highlights/wardogs_2026-09-16_highlights_9x16.mp4"),
                ),
                report = Path("D:/Highlights/wardogs_2026-09-16_highlights.json"),
                duration = 58.seconds,
                encoder = "h264_amf",
            ),
            busyClips = setOf("h005"),
        )
        render("03_relecture", state).fileSize() shouldBeGreaterThan 0L
        render("04_relecture_petite_fenetre", state, 1100, 700).fileSize() shouldBeGreaterThan 0L
    }

    test("file des traitements : un en cours, deux en attente, un fini, un en échec ; puis la confirmation de fermeture") {
        val jobs = listOf(
            QueuedJob(1, "Export : WARDOGS_09-16-2026_19-1-23-983.mp4", status = JobStatus.DONE, fraction = 1.0, result = Path("D:/Highlights/wardogs_highlights.mp4")),
            QueuedJob(2, "Montage kills sur R2D2.mp3", status = JobStatus.FAILED, error = "Aucune musique ne convient à ce montage"),
            QueuedJob(3, "Montage kills sur Rome Is Burning.mp3", status = JobStatus.RUNNING, fraction = 0.42, stage = "Rendu > 9:16", elapsed = 83.seconds, eta = 115.seconds),
            QueuedJob(4, "Aperçus de variantes sur Rome Is Burning.mp3"),
            QueuedJob(5, "Export : WARDOGS_09-22-2026_22-55-31-983.mp4"),
            QueuedJob(6, "Analyse en arrière-plan : WARDOGS_09-23-2026_21-4-12-105.mp4", auto = true),
        )
        val state = UiState(config = ready, sources = listOf(source), settings = settings, session = SessionState(session, Path("D:/Highlights/sessions/x.session.json"), selectedId = "h003"), jobs = jobs)
        render("16_file_des_traitements", state).fileSize() shouldBeGreaterThan 0L
        render("17_fermeture_avec_traitements", state.copy(quitConfirm = 3)).fileSize() shouldBeGreaterThan 0L
    }

    test("file des traitements : un export lancé à la main passe devant deux analyses du dossier surveillé") {
        // Ordre produit par la vraie file : une analyse automatique en cours, deux en attente, puis l'export.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val queue = JobQueue(scope, onChange = {})
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = queue.submit("Analyse en fond : WARDOGS_09-25-2026_21-4-12-105.mp4", auto = true) { gate.await(); null }
        queue.submit("Analyse en fond : WARDOGS_09-25-2026_21-41-7-105.mp4", auto = true) { null }
        queue.submit("Analyse en fond : WARDOGS_09-25-2026_22-20-55-105.mp4", auto = true) { null }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { while (queue.snapshot.first().status != JobStatus.RUNNING) kotlinx.coroutines.delay(10) } }
        val before = queue.snapshot.map { if (it.id == first) it.copy(fraction = 0.37, stage = "Détecteurs", elapsed = 41.seconds, eta = 70.seconds) else it }
        queue.submit("Export : WARDOGS_09-22-2026_22-55-31-983.mp4") { null }
        val after = queue.snapshot.map { if (it.id == first) it.copy(fraction = 0.37, stage = "Détecteurs", elapsed = 41.seconds, eta = 70.seconds) else it }
        after.map { it.auto } shouldBe listOf(true, false, true, true)
        val state = UiState(config = ready, sources = listOf(source), settings = settings)
        render("16b_file_avant_export", state.copy(jobs = before)).fileSize() shouldBeGreaterThan 0L
        render("16c_file_export_devant", state.copy(jobs = after)).fileSize() shouldBeGreaterThan 0L
        gate.complete(Unit)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    test("réglages du montage kills") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json"), selectedId = "h003"),
            montage = MontageUiState(music = Path("D:/Musique/phonk_128.mp3")),
        )
        renderFlow("06_montage", state).fileSize() shouldBeGreaterThan 0L
        for (step in listOf(MontageStep.MUSIC, MontageStep.FINISHING, MontageStep.PREVIEW)) {
            renderFlow("06_montage_${step.ordinal + 1}_${step.name.lowercase()}", state.copy(montage = state.montage!!.copy(step = step, reached = step.ordinal))).fileSize() shouldBeGreaterThan 0L
        }
    }

    test("ouverture et fin : réglages du logo, du pseudo et du fond") {
        val cards = dev.highlights.core.model.IntroOutroSettings(enabled = true, logo = "D:/Images/logo.png", name = "ZeroX")
        val state = UiState(config = ready, settings = settings, introCards = cards, introCustom = true, introDialog = IntroUiState())
        render("16_ouverture_fin", state).fileSize() shouldBeGreaterThan 0L
    }

    test("publication sur YouTube : champs proposés, puis sans identifiant configuré, puis lien de la vidéo en ligne") {
        val video = Path("D:/Highlights/valorant_2026-09-27_killmontage_9x16.mp4")
        val suggested = dev.highlights.publish.YouTubeMetadata(
            "TRIPLÉ — 19 kills en 57 s | VALORANT", "19 kills, dont 13 headshots · 1 triplé, 4 doublés.\nPartie du 27/09/2026.\nMusique : R2D2\n\n#VALORANT #Shorts",
            listOf("VALORANT", "gaming", "highlights"), dev.highlights.core.config.YouTubePrivacy.PRIVATE,
        )
        val base = UiState(config = ready, sources = listOf(source), settings = settings, session = SessionState(session, Path("D:/Highlights/sessions/x.session.json")))
        render("09_publication_manuelle", base.copy(publish = PublishUiState(video).withSuggestion(suggested))).fileSize() shouldBeGreaterThan 0L
        render("09_publication_api", base.copy(publish = PublishUiState(video, api = true, connected = true).withSuggestion(suggested))).fileSize() shouldBeGreaterThan 0L
        render("10_publication_sans_identifiant", base.copy(publish = PublishUiState(video, api = true, configured = false))).fileSize() shouldBeGreaterThan 0L
        val exported = base.copy(
            lastExport = ExportResult(mapOf(OutputFormat.VERTICAL to video), Path("D:/Highlights/valorant_2026-09-27_killmontage.json"), 57.seconds, "h264_amf", Path("D:/Musique/R2D2.mp3")),
            lastUpload = dev.highlights.publish.UploadedVideo("abc123"),
        )
        render("11_publiee", exported).fileSize() shouldBeGreaterThan 0L
    }

    test("plateforme visée : format imposé, ce qu'elle change affiché") {
        val platforms = dev.highlights.core.model.PlatformProfile.DEFAULTS.map { (id, p) -> PlatformInfo.of(id, p) }
        val state = UiState(
            config = ready.copy(platforms = platforms),
            sources = listOf(source),
            settings = settings.copy(platform = "tiktok"),
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json")),
            montage = MontageUiState(music = Path("D:/Musique/phonk_128.mp3"), platform = "shorts"),
        )
        renderFlow("12_plateforme", state).fileSize() shouldBeGreaterThan 0L
        render("13_plateforme_reglages", state.copy(montage = null)).fileSize() shouldBeGreaterThan 0L
    }

    test("préréglages d'export : puces en haut de la barre d'export et de la fenêtre de montage") {
        val presets = listOf(
            dev.highlights.core.model.ExportPreset("tiktok", platform = "tiktok", length = 30.seconds, montageStyle = "tiktok", thumbnail = false),
            dev.highlights.core.model.ExportPreset("shorts", platform = "shorts", length = 60.seconds, montageStyle = "cinematique", thumbnail = false),
            dev.highlights.core.model.ExportPreset("complet", format = OutputFormat.LANDSCAPE, length = 3.minutes, montageStyle = "cinematique", thumbnail = true),
        )
        val platforms = dev.highlights.core.model.PlatformProfile.DEFAULTS.map { (id, p) -> PlatformInfo.of(id, p) }
        val state = UiState(
            config = ready.copy(platforms = platforms, presets = presets),
            sources = listOf(source),
            settings = settings.choosePreset("tiktok", presets).copy(durationText = "45s", presetTouched = setOf(PresetKnob.LENGTH)),
            session = SessionState(session, Path("D:/Highlights/sessions/x.session.json")),
        )
        render("18_prereglages_export", state).fileSize() shouldBeGreaterThan 0L
        val montage = MontageUiState(music = Path("D:/Musique/phonk_128.mp3")).choosePreset("shorts", presets)
        renderFlow("19_prereglages_montage", state.copy(montage = montage)).fileSize() shouldBeGreaterThan 0L
    }

    test("statistiques par soirée") {
        fun game(at: String, name: String, kills: Int, deaths: Int?, hs: Int?, best: Int?, multi: Map<Int, Int> = emptyMap(), clutches: Int = 0) =
            dev.highlights.pipeline.GameStats(
                Path("D:/Highlights/sessions/${at.take(10)}-$kills.session.json"), Path("partie.mp4"), name.lowercase(), name,
                Instant.parse(at), 40.minutes, kills, deaths, hs, multi, 0, clutches, best, best?.let { 24 },
            )
        val games = listOf(
            game("2026-09-21T19:45:00Z", "VALORANT", 10, 11, 1, 3, mapOf(2 to 1)),
            game("2026-09-22T16:45:00Z", "VALORANT", 7, 5, 2, 3, mapOf(2 to 1)),
            game("2026-09-22T16:59:00Z", "VALORANT", 6, 3, 2, 2),
            game("2026-09-25T20:24:00Z", "VALORANT", 14, 14, 4, 4, mapOf(2 to 1, 3 to 1), 1),
            game("2026-09-25T21:07:00Z", "VALORANT", 9, 17, 5, 2),
            game("2026-09-26T20:33:00Z", "VALORANT", 13, 17, 7, 3, mapOf(2 to 2)),
            game("2026-09-26T21:26:00Z", "VALORANT", 23, 15, 13, 2, mapOf(2 to 3), 2),
            game("2026-09-27T14:14:00Z", "VALORANT", 19, 16, 11, 2, mapOf(2 to 1)),
            game("2026-09-27T16:08:00Z", "VALORANT", 23, 13, null, 3, mapOf(2 to 5), 3),
            game("2026-09-27T20:26:00Z", "VALORANT", 19, 18, 13, 3, mapOf(2 to 4), 3),
            game("2026-09-23T19:55:00Z", "Wardogs", 5, null, null, null),
        )
        val state = UiState(config = ready, stats = StatsState(loading = false, games = games))
        render("14_statistiques", state).fileSize() shouldBeGreaterThan 0L
    }

    test("goûts appris : poids des critères et sortes de kills préférées") {
        val base = dev.highlights.montage.MontageScorer.WEIGHTS
        val weights = base + mapOf("action" to 0.19, "opening" to 0.16, "restraint" to 0.09, "sync" to 0.21)
        val learned = dev.highlights.montage.LearnedTaste(weights, base, choices = 14, agreementBefore = 0.43, agreementAfter = 0.79, active = true)
        val groups = dev.highlights.montage.LearnedGroupTaste(
            bonus = mapOf(dev.highlights.montage.GroupTrait.FLICK to 0.42, dev.highlights.montage.GroupTrait.ONE_TAP to -0.31, dev.highlights.montage.GroupTrait.HEADSHOT to 0.08),
            actions = mapOf(dev.highlights.montage.GroupTrait.FLICK to 9, dev.highlights.montage.GroupTrait.ONE_TAP to 5, dev.highlights.montage.GroupTrait.HEADSHOT to 6, dev.highlights.montage.GroupTrait.MULTI_KILL to 4, dev.highlights.montage.GroupTrait.ACE to 1),
            edits = 7, active = true,
        )
        val taste = dev.highlights.pipeline.TasteState(
            Path("D:/Highlights/sessions/taste-history.json"), 14, 7, learned, listOf("valorant", "wardogs"), null, null, null, groups = groups,
        )
        render("17_gouts", UiState(config = ready, taste = TasteUiState(taste, confirmReset = true))).fileSize() shouldBeGreaterThan 0L
        val fresh = dev.highlights.pipeline.TasteState(
            Path("D:/Highlights/sessions/taste-history.json"), 3, 1, learned.copy(weights = base, choices = 3, active = false), listOf("valorant"), null, null, null,
        )
        render("17_gouts_debut", UiState(config = ready, taste = TasteUiState(fresh))).fileSize() shouldBeGreaterThan 0L
    }

    test("recherche de moments") {
        fun moment(at: String, kills: Int, hs: Int, ace: Boolean = false, clutch: Boolean = false, game: String = "VALORANT") = dev.highlights.pipeline.FoundMoment(
            Path("D:/Highlights/sessions/${at.take(10)}.session.json"), Path("D:/Videos/Valorant_${at.take(10)}.mp4"), game.lowercase(), game,
            Instant.parse(at), List(kills) { (Instant.parse(at).epochSecond % 3000 + it).seconds }, hs, ace, clutch, kills.toDouble(),
        )
        val moments = listOf(
            moment("2026-09-25T20:24:00Z", 3, 1, clutch = true), moment("2026-09-26T21:26:00Z", 2, 2, clutch = true),
            moment("2026-09-27T20:26:00Z", 2, 2, clutch = true), moment("2026-09-27T19:54:00Z", 2, 1), moment("2026-09-27T14:14:00Z", 1, 1),
            moment("2026-09-23T19:55:00Z", 1, 0, game = "Wardogs"),
        )
        val search = SearchState(loading = false, moments = moments, minKills = 2)
        search.results.size shouldBe 4
        search.copy(unpicked = setOf(SearchState.key(moments[3]))).picked.size shouldBe 3
        search.copy(clutch = true, headshots = true).results.size shouldBe 2
        render("15_recherche", UiState(config = ready, search = search)).fileSize() shouldBeGreaterThan 0L
    }

    test("plusieurs captures pour un seul montage") {
        // Trois parties de la même soirée : la deuxième est sélectionnée, sa courbe est affichée.
        val parts = listOf("18-4-11-925" to 0, "19-1-23-983" to 3, "20-12-2-114" to 6).mapIndexed { n, (stamp, from) ->
            val m = media.copy(
                path = Path("C:/Users/Colin/Videos/Overwolf/Outplayed/WARDOGS/WARDOGS_09-16-2026_$stamp.mp4"),
                creationTime = Instant.parse("2026-09-16T1${6 + n}:04:11Z"),
            )
            session.copy(media = m, highlights = highlights.subList(from, from + 3).mapIndexed { i, h -> h.copy(id = "h%03d".format(i + 1), source = m.path) })
        }
        val state = UiState(
            config = ready,
            sources = parts.map { SourceInfo(it.media.path, it.media, "wardogs") },
            settings = settings,
            session = SessionState(parts.mapIndexed { i, s -> SessionEntry(s, Path("D:/Highlights/sessions/$i.session.json")) }, selectedId = "2-h002"),
        )
        render("07_plusieurs_videos", state).fileSize() shouldBeGreaterThan 0L
    }

    test("mise à jour disponible, puis en cours de téléchargement") {
        val release = Release(
            "1.4.0", "Highlights-1.4.0.msi", java.net.URI("https://github.com/x/y/releases/download/v1.4.0/Highlights-1.4.0.msi"),
            180_000_000, null, java.net.URI("https://github.com/x/y/releases/tag/v1.4.0"),
        )
        val state = UiState(config = ready, sources = listOf(source), settings = settings, update = UpdateState.Available(release))
        render("08_mise_a_jour", state).fileSize() shouldBeGreaterThan 0L
        render("09_mise_a_jour_telechargement", state.copy(update = UpdateState.Downloading(release, 0.42))).fileSize() shouldBeGreaterThan 0L
    }

    test("erreur FFmpeg") {
        val state = UiState(
            config = ready,
            sources = listOf(source),
            settings = settings,
            error = ErrorInfo(
                "Export du montage : échec",
                "FFmpeg a échoué (rendu 9:16), code retour -22 : Invalid argument",
                listOf("[Parsed_crop_3 @ 0000] Invalid too big or non positive size", "Error reinitializing filters!", "", "Journal : C:\\Users\\Colin\\.highlights\\logs\\highlights.log"),
            ),
        )
        render("05_erreur", state).fileSize() shouldBeGreaterThan 0L
    }

    test("premier lancement : enregistreurs trouvés, conseils, rien trouvé") {
        val at = Instant.parse("2026-11-20T21:40:00Z")
        fun videos(folder: String, count: Int) = List(count) { i -> FoundVideo(Path("$folder/VALORANT/VALORANT 11-20-2026_21-${10 + i}-00.mp4"), at.minusSeconds(1800L * i)) }
        val outplayed = SetupRecorder(
            RecorderKind.OUTPLAYED, Path("C:/Users/Colin/Videos/Overwolf/Outplayed"), videos("C:/Users/Colin/Videos/Overwolf/Outplayed", 42),
            listOf(SetupGame("valorant", "VALORANT", 31), SetupGame("wardogs", "WARDOGS", 9), SetupGame("default", "Générique", 2)), null, true,
        )
        val obs = SetupRecorder(RecorderKind.OBS, Path("D:/Captures"), videos("D:/Captures", 6), listOf(SetupGame("default", "Générique", 6)), false, true)
        val nvidia = SetupRecorder(RecorderKind.SHADOWPLAY, Path("C:/Users/Colin/Videos"), emptyList(), emptyList(), null, false)
        val setup = SetupUiState(
            loading = false, recorders = listOf(outplayed, obs, nvidia), folder = outplayed.folder,
            firstPick = outplayed.videos.take(4).reversed().map { it.path },
        )
        val state = UiState(config = ready, setup = setup)
        render("30_premier_lancement", state).fileSize() shouldBeGreaterThan 0L
        render("31_premier_lancement_conseils", state.copy(setup = setup.copy(page = SetupPage.TIPS))).fileSize() shouldBeGreaterThan 0L
        render("32_premier_lancement_vide", state.copy(setup = SetupUiState(loading = false))).fileSize() shouldBeGreaterThan 0L
        render("33_premier_lancement_sans_video", state.copy(setup = setup.copy(folder = nvidia.folder, firstPick = emptyList(), watch = false))).fileSize() shouldBeGreaterThan 0L
        render("34_premier_lancement_recherche", state.copy(setup = SetupUiState())).fileSize() shouldBeGreaterThan 0L
    }
})

private object NoopActions : UiActions {
    override fun chooseSource() = Unit
    override fun showLibrary() = Unit
    override fun openLibraryItem(sessionFile: Path) = Unit
    override fun toggleLibraryItem(sessionFile: Path) = Unit
    override fun openLibrarySelection() = Unit
    override fun askForget(sessionFile: Path) = Unit
    override fun confirmForget() = Unit
    override fun cancelForget() = Unit
    override fun nudgeSegment(id: String, end: Boolean, delta: kotlin.time.Duration) = Unit
    override fun addSegmentAt(entry: Int, at: kotlin.time.Duration) = Unit
    override fun moveSegment(id: String, delta: Int) = Unit
    override fun chooseWatchFolder() = Unit
    override fun stopWatching() = Unit
    override fun addSources() = Unit
    override fun removeSource(path: Path) = Unit
    override fun chooseSession() = Unit
    override fun dropFiles(paths: List<Path>) = Unit
    override fun reloadConfig() = Unit
    override fun openProfilesFolder() = Unit
    override fun editProfile() = Unit
    override fun setProfile(id: String?) = Unit
    override fun toggleFormat(format: OutputFormat) = Unit
    override fun setPlatform(id: String?) = Unit
    override fun setStyle(style: EditStyle) = Unit
    override fun setMomentMode(mode: MomentMode) = Unit
    override fun setTargetMode(mode: TargetMode) = Unit
    override fun setDurationText(text: String) = Unit
    override fun setTopNText(text: String) = Unit
    override fun setThreshold(value: Double) = Unit
    override fun chooseOutputDir() = Unit
    override fun openOutputDir() = Unit
    override fun analyze() = Unit
    override fun export() = Unit
    override fun clipMoment(key: String) = Unit
    override fun cancelJob() = Unit
    override fun toggleSegment(id: String) = Unit
    override fun setAllSegments(enabled: Boolean) = Unit
    override fun selectSegment(id: String?) = Unit
    override fun previewClip(id: String) = Unit
    override fun previewVertical(id: String) = Unit
    override fun regenerateVerticalPreview() = Unit
    override fun openMontage() = Unit
    override fun closeMontage() = Unit
    override fun chooseMusic() = Unit
    override fun chooseMusicLibrary() = Unit
    override fun showStats() = Unit
    override fun showSearch() = Unit
    override fun closeSearch() = Unit
    override fun updateSearch(change: (SearchState) -> SearchState) = Unit
    override fun toggleMoment(key: String) = Unit
    override fun montageFromSearch() = Unit
    override fun closeStats() = Unit
    override fun setStatsGame(game: String) = Unit
    override fun refreshOutdated() = Unit
    override fun openPublish(video: java.nio.file.Path) = Unit
    override fun closePublish() = Unit
    override fun updatePublish(change: (PublishUiState) -> PublishUiState) = Unit
    override fun resetPublishText() = Unit
    override fun publishToYouTube() = Unit
    override fun youtubeLogout() = Unit
    override fun editConfig() = Unit
    override fun browse(uri: java.net.URI) = Unit
    override fun openYouTubeUpload() = Unit
    override fun copyToClipboard(text: String) = Unit
    override fun makeThumbnail() = Unit
    override fun updateMontage(change: (MontageUiState) -> MontageUiState) = Unit
    override fun setIntroEnabled(enabled: Boolean) = Unit
    override fun openIntro() = Unit
    override fun closeIntro() = Unit
    override fun updateIntro(change: (dev.highlights.core.model.IntroOutroSettings) -> dev.highlights.core.model.IntroOutroSettings) = Unit
    override fun chooseIntroLogo() = Unit
    override fun chooseIntroImage() = Unit
    override fun setIntroPreview(format: OutputFormat, outro: Boolean) = Unit
    override fun createMontage() = Unit
    override fun open(path: Path) = Unit
    override fun reveal(path: Path) = Unit
    override fun dismissError() = Unit
    override fun dismissImagePreview() = Unit
    override fun installUpdate() = Unit
    override fun dismissUpdate() = Unit
    override fun setLanguage(tag: String?) = Unit
    override fun openReport() = Unit
    override fun reportError() = Unit
    override fun reportCrash() = Unit
    override fun dismissCrash() = Unit
    override fun closeReport() = Unit
    override fun saveReport() = Unit
    override fun openIssuePage() = Unit
    override fun revealReport() = Unit
    override fun openLogsFolder() = Unit
    override fun retryMemoryCheck() = Unit
    override fun startDespiteMemory() = Unit
    override fun dismissMemoryWarning() = Unit
}
