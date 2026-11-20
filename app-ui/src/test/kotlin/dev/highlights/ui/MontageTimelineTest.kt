package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontagePin
import dev.highlights.montage.SectionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalComposeUiApi::class)
class MontageTimelineTest : FunSpec({
    val media = MediaInfo(Path("D:/Vidéo/WARDOGS_1/WARDOGS_partie1.mp4"), 1, 40.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))
    val noop = java.lang.reflect.Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { _, _, _ -> null } as UiActions
    fun group(vararg kills: Double, rank: Double = 1.0) = PlanGroup(media, kills.map { it.seconds }, rank)

    test("corrections depuis la frise : écarter tout le groupe, forcer et épingler par son dernier kill, annuler") {
        val g = group(100.0, 101.5)
        val excluded = MontageConstraints.NONE.edit(g.refs, KillEdit.EXCLUDE)
        excluded.excluded shouldBe g.refs.toSet()
        g.mark(excluded) shouldBe GroupMark.EXCLUDED
        // Un seul kill écarté : le groupe reste, marqué.
        g.mark(MontageConstraints.NONE.edit(g.refs.take(1), KillEdit.EXCLUDE)) shouldBe GroupMark.PARTLY_EXCLUDED
        val forced = excluded.edit(g.refs, KillEdit.FORCE)
        forced.excluded shouldBe emptySet()
        forced.forced shouldBe setOf(g.refs.last())
        g.mark(forced) shouldBe GroupMark.FORCED
        val dropped = forced.edit(g.refs, KillEdit.DROP)
        dropped.pins shouldBe mapOf(g.refs.last() to MontagePin.Drop)
        g.mark(dropped) shouldBe GroupMark.DROP
        dropped.edit(g.refs, KillEdit.CLEAR).isEmpty shouldBe true
        // La désignation que le pont et la CLI relisent.
        KillRef.parse(g.key) shouldBe g.refs.last()
    }

    test("épingler sur un temps : par le dernier kill, instant du morceau, autres corrections retirées, une seule place") {
        val g = group(100.0, 101.5)
        val forced = MontageConstraints.NONE.edit(g.refs, KillEdit.FORCE)
        val pinned = forced.pinAt(g.refs, 72.5.seconds)
        pinned.forced shouldBe emptySet()
        pinned.pins shouldBe mapOf(g.refs.last() to MontagePin.At(72.5.seconds))
        g.mark(pinned) shouldBe GroupMark.PINNED
        // Épinglé ailleurs : la nouvelle place remplace l'ancienne ; la drop d'un autre groupe reste.
        val other = group(300.0)
        val both = pinned.edit(other.refs, KillEdit.DROP).pinAt(g.refs, 80.seconds)
        both.pins shouldBe mapOf(other.refs.last() to MontagePin.Drop, g.refs.last() to MontagePin.At(80.seconds))
        // Désépingler (clic sur le plan ou sur l'épingle) : plus rien.
        both.edit(g.refs, KillEdit.CLEAR).pins shouldBe mapOf(other.refs.last() to MontagePin.Drop)
        MontageConstraints.NONE.pinAt(emptyList(), 1.seconds) shouldBe MontageConstraints.NONE
    }

    test("temps visé : le plus proche dans la frise, rendu en instant du morceau, épingles à leur instant exact") {
        val beats = (0 until 20).map { (it * 500).milliseconds }
        val view = PlanView("m.mp3", 120.0, 55.seconds, 10.seconds, beats, beats.map { 0.5 }, emptyList(), null, emptyList(), emptyList())
        view.beatNear(3.2.seconds) shouldBe 6
        view.beatNear(30.seconds) shouldBe 19
        view.musicAt(6) shouldBe 58.seconds
        val g = group(100.0)
        val h = group(200.0)
        val c = MontageConstraints.NONE.pinAt(g.refs, 58.1.seconds).pinAt(h.refs, 120.seconds).edit(group(300.0).refs, KillEdit.DROP)
        // Dans le passage : à son instant exact (3,1 s du montage : le kill y tombe, même entre deux temps) ; dehors : gardée
        // avec son instant du morceau ; la drop n'y est pas.
        view.pinMarks(c) shouldBe listOf(PinMark(g.refs.last(), 58.1.seconds, 3.1.seconds), PinMark(h.refs.last(), 120.seconds, null))
    }

    test("calage d'un kill à l'image : bande d'images, image choisie, décalage du temps (build/ui-snapshots/montage_kill_timing*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        // Images de synthèse : un réticule au centre, la marque d'impact rouge à partir de la 9e image (kill vu 3 images
        // après la détection, posée sur la 6e).
        val dir = kotlin.io.path.createTempDirectory("kill-frames")
        val detected = 1210.seconds
        val frames = (0 until 31).map { i ->
            val img = java.awt.image.BufferedImage(480, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = java.awt.Color(40 + i * 2, 52, 60)
            g.fillRect(0, 0, 480, 200)
            g.color = java.awt.Color(120, 110, 90)
            g.fillRect(300 - i * 3, 70, 40, 80)
            g.color = java.awt.Color.WHITE
            g.drawLine(230, 100, 250, 100); g.drawLine(240, 90, 240, 110)
            if (i >= 8) { g.color = java.awt.Color.RED; g.drawLine(228, 88, 236, 96); g.drawLine(252, 88, 244, 96); g.drawLine(228, 112, 236, 104); g.drawLine(252, 112, 244, 104) }
            g.dispose()
            val file = dir.resolve("k_%03d.jpg".format(i))
            javax.imageio.ImageIO.write(img, "jpg", file.toFile())
            dev.highlights.montage.KillFrame(detected - 83.milliseconds + (i * 1000 / 60).milliseconds, file)
        }
        val ref = KillRef.of(media, detected)
        // Son du jeu ±2 s : bruit de fond, puis la rafale du tir à partir de l'image 8 (detected + 50 ms).
        val waveStart = detected - 2.seconds
        val wave = (0 until KillTimingUi.WAVE_BUCKETS).map { i ->
            val t = i * 4.0 / KillTimingUi.WAVE_BUCKETS - 2.0
            (if (t in 0.05..0.6) 0.9 - 0.4 * ((t - 0.05) % 0.1) / 0.1 else 0.12 + 0.05 * sin(i * 0.7)).toFloat()
        }
        val timing = KillTimingUi(
            ref, media, current = detected, frames = frames, loading = false, selected = 8, beatShift = 17.milliseconds, songShift = Duration.ZERO,
            cursor = frames[8].at, wave = wave, waveStart = waveStart, waveLength = 4.seconds, text = KillRef.precise(frames[8].at),
        )
        val view = PlanView(
            music = "Rome Is Burning.mp3", bpm = 120.0, musicStart = 55.seconds, duration = 12.seconds, beats = (0 until 24).map { (it * 500).milliseconds },
            energy = (0 until 24).map { 0.4 + 0.5 * (it % 4 == 0).compareTo(false) }, sections = listOf(PlanSection(0.seconds, 12.seconds, SectionKind.DROP, 1.0)), dropAt = 4.seconds,
            clips = listOf(
                PlanClip(group(900.0), 0.seconds, 4.seconds, listOf(PlanKill(2.5.seconds, KillRef.of(media, 900.seconds))), false),
                PlanClip(group(1210.0), 4.seconds, 8.seconds, listOf(PlanKill(5.seconds, ref, detected + 50.milliseconds, retimed = true, byHand = true)), true),
                PlanClip(group(1500.0), 8.seconds, 12.seconds, listOf(PlanKill(10.seconds, KillRef.of(media, 1500.seconds), 1500.05.seconds, retimed = true)), false),
            ),
            left = emptyList(),
        )
        // Repères posés : bornes du plan de ce kill, une section sans kill et une à garder entière.
        val markers = MontageConstraints.NONE.anchor(ref, frames[8].at).cut(ref, detected - 1.2.seconds, detected + 0.8.seconds)
            .addRange(dev.highlights.montage.CaptureRange(ref.capture, dev.highlights.core.model.TimeRange(detected - 1.9.seconds, detected - 1.4.seconds), dev.highlights.montage.RangeKind.SKIP))
            .addRange(dev.highlights.montage.CaptureRange(ref.capture, dev.highlights.core.model.TimeRange(detected + 1.seconds, detected + 1.6.seconds), dev.highlights.montage.RangeKind.KEEP))
        timing.nearest(detected) shouldBe 5
        timing.chosen shouldBe frames[8].at
        KillTimingUi.signedMs((-17).milliseconds) shouldBe "−17"
        for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/", "ja" to "ja/")) {
            I18n.withLanguage(tag) {
                val image = SceneRendering.image(1320, 1060) {
                    HighlightsTheme {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                            KillTimingPanel(timing.copy(sectionStart = detected + 900.milliseconds), noop, constraints = markers)
                            MarkerChips(markers, noop)
                        }
                    }
                }
                val file: Path = out.resolve("${folder}montage_kill_timing.png")
                file.parent.createDirectories()
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }
    test("outil de la drop : forme d'onde, curseur, temps détectés et drop posée (build/ui-snapshots/montage_music_drop*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        // Forme d'onde de synthèse : calme, silence d'un demi-temps, puis la drop forte sur chaque temps (126 BPM).
        val beats = (0 until 480).map { (it * 476).milliseconds }
        fun level(t: Double) = if (t < 30.5) 0.25 + 0.1 * sin(t * 13) else if (t < 30.52) 0.02 else 0.9 - 0.3 * ((t - 30.523) % 0.476) / 0.476
        val overview = (0 until MusicDropUi.OVERVIEW_BUCKETS).map { i -> level(i * 228.0 / MusicDropUi.OVERVIEW_BUCKETS).toFloat() }
        val cursor = 30.523.seconds
        val base = MusicDropUi(Path("D:/Musique/Rome Is Burning.mp3"), cursor, loading = false, duration = 228.seconds, overview = overview, beats = beats, detected = 31.474.seconds)
        val detail = (0 until MusicDropUi.DETAIL_BUCKETS).map { i -> level(base.detailStart.inWholeMilliseconds / 1000.0 + i * 6.0 / MusicDropUi.DETAIL_BUCKETS).toFloat() }
        val drop = base.copy(detail = detail, byHand = 30.523.seconds, playing = true, playhead = 29.9.seconds)
        drop.detailStart shouldBe 27.523.seconds
        drop.offsetFromDetected shouldBe "−951"
        val view = PlanView(
            music = "Rome Is Burning.mp3", bpm = 126.0, musicStart = 17.182.seconds, duration = 12.seconds, beats = (0 until 24).map { (it * 476).milliseconds },
            energy = (0 until 24).map { 0.4 + 0.5 * (it % 4 == 0).compareTo(false) }, sections = listOf(PlanSection(0.seconds, 12.seconds, SectionKind.DROP, 1.0)), dropAt = 6.seconds,
            clips = listOf(PlanClip(group(900.0), 0.seconds, 12.seconds, emptyList(), true)), left = emptyList(), musicPath = Path("D:/Musique/Rome Is Burning.mp3"), dropByHand = true,
        )
        for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/", "ja" to "ja/")) {
            I18n.withLanguage(tag) {
                val image = SceneRendering.image(1320, 760) {
                    HighlightsTheme { MusicDropPanel(drop, noop, androidx.compose.ui.Modifier.padding(16.dp)) }
                }
                val file: Path = out.resolve("${folder}montage_music_drop.png")
                file.parent.createDirectories()
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    test("outil de la drop : ±10 ms, ±1 temps, saisie m:ss.mmm, sans morceau chargé ni sortie son") {
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            UiState(montage = MontageUiState(plan = MontagePlanState(loading = false, drop = MusicDropUi(Path("rome.mp3"), 30.seconds, loading = false, duration = 200.seconds, beats = (0 until 400).map { (it * 476).milliseconds })))),
        )
        val c = MusicDropController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), state, { null }, {}, sinks = { null })
        fun d() = state.value.montage!!.plan!!.drop!!
        c.step(10)
        d().cursor shouldBe 30.01.seconds
        d().text shouldBe "0:30.010"
        c.stepBeat(1)
        d().cursor shouldBe 30.464.seconds
        c.stepBeat(-1)
        d().cursor shouldBe 29.988.seconds
        c.text("0:30.523")
        d().cursor shouldBe 30.523.seconds
        d().textError shouldBe false
        c.text("0:3x")
        d().textError shouldBe true
        d().cursor shouldBe 30.523.seconds
        // Sans son chargé, écouter ne fait rien (et ne plante pas).
        c.play(true)
        d().playing shouldBe false
        c.close()
        state.value.montage!!.plan!!.drop shouldBe null
    }
    test("Kill ici : pas d'une image ou de 10 ms, saisie de l'instant, borné à ±1 s de la détection, sans lecteur") {
        val detected = 1210.seconds
        val ref = KillRef.of(media, detected)
        val frames = (0 until 61).map { i -> dev.highlights.montage.KillFrame(detected - 500.milliseconds + (i * 1000 / 60).milliseconds, Path("k_$i.jpg")) }
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            UiState(montage = MontageUiState(plan = MontagePlanState(loading = false, timing = KillTimingUi(ref, media, current = detected, frames = frames, loading = false, selected = 30, cursor = detected)))),
        )
        val c = KillTimingController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), state, { null }, {}, {})
        fun t() = state.value.montage!!.plan!!.timing!!
        c.step(1)
        t().chosen shouldBe frames[31].at
        t().selected shouldBe 31
        c.stepMs(10)
        t().chosen shouldBe frames[31].at + 10.milliseconds
        t().text shouldBe KillRef.precise(frames[31].at + 10.milliseconds)
        // Hors d'une image : un pas d'image depuis le curseur, l'image montrée reste la plus proche.
        c.step(-1)
        t().chosen shouldBe frames[31].at + 10.milliseconds - t().frameStep
        c.pick(10)
        t().chosen shouldBe frames[10].at
        c.text("20:10.250")
        t().chosen shouldBe 1210.25.seconds
        t().textError shouldBe false
        c.text("20:1x")
        t().textError shouldBe true
        // Au-delà de ±1 s de la détection : le curseur y va (pour les autres repères), « Kill ici » n'y est pas possible.
        c.seek(1215.0)
        t().chosen shouldBe 1215.seconds
        t().killHereAllowed shouldBe false
        c.apply()
        state.value.montage!!.plan!!.timing shouldNotBe null
        c.play(true)
        t().playing shouldBe false
        c.close()
        state.value.montage!!.plan!!.timing shouldBe null
    }
    test("repères : couper ici et sections posés au curseur deviennent des contraintes, la frise les retire") {
        val detected = 1210.seconds
        val ref = KillRef.of(media, detected)
        val state = kotlinx.coroutines.flow.MutableStateFlow(
            UiState(montage = MontageUiState(plan = MontagePlanState(loading = false, timing = KillTimingUi(ref, media, current = detected, loading = false, cursor = detected)))),
        )
        var replans = 0
        val c = KillTimingController(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), state, { null },
            { change -> state.value = state.value.copy(montage = state.value.montage?.let(change)) }, { replans++ },
        )
        fun constraints() = state.value.montage!!.constraints
        c.seek(1208.5)
        c.markCut(start = true)
        c.seek(1211.4)
        c.markCut(start = false)
        constraints().cuts[ref] shouldBe dev.highlights.montage.ClipCut(1208.5.seconds, 1211.4.seconds)
        c.seek(1205.0)
        c.markSectionStart()
        state.value.montage!!.plan!!.timing!!.sectionStart shouldBe 1205.seconds
        c.seek(1203.0)
        c.markSectionEnd(keep = false)
        constraints().ranges.single() shouldBe dev.highlights.montage.CaptureRange(ref.capture, dev.highlights.core.model.TimeRange(1203.seconds, 1205.seconds), dev.highlights.montage.RangeKind.SKIP)
        state.value.montage!!.plan!!.timing!!.sectionStart shouldBe null
        replans shouldBe 3
        // Le panneau reste ouvert pour d'autres repères.
        state.value.montage!!.plan!!.timing shouldNotBe null
        constraints().clearCut(ref, start = true).cuts[ref] shouldBe dev.highlights.montage.ClipCut(null, 1211.4.seconds)
        constraints().removeRange(0).ranges shouldBe emptyList()
    }
    test("kill sur ce moment de la musique : l'outil du morceau ouvert pour un kill l'épingle à l'instant exact du curseur (build/ui-snapshots/montage_kill_on_music*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val g = group(900.0)
        val view = PlanView(
            music = "Rome Is Burning.mp3", bpm = 126.0, musicStart = 17.seconds, duration = 12.seconds, beats = (0 until 24).map { (it * 476).milliseconds },
            energy = (0 until 24).map { 0.5 }, sections = listOf(PlanSection(0.seconds, 12.seconds, SectionKind.DROP, 1.0)), dropAt = 4.seconds,
            clips = listOf(PlanClip(g, 0.seconds, 4.seconds, listOf(PlanKill(2.5.seconds, g.refs.last())), false)), left = emptyList(),
            musicPath = Path("D:/Musique/Rome Is Burning.mp3"),
        )
        val state = kotlinx.coroutines.flow.MutableStateFlow(UiState(montage = MontageUiState(plan = MontagePlanState(loading = false, view = view))))
        var replans = 0
        val c = MusicDropController(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), state, { null }, { replans++ }, sinks = { null },
            updateMontage = { change -> state.value = state.value.copy(montage = state.value.montage?.let(change)) },
        )
        c.openForKill(g.refs.last().toString())
        val d = state.value.montage!!.plan!!.drop!!
        d.target shouldBe g.refs.last()
        // Le curseur part de là où le kill tombe aujourd'hui dans le morceau : début du passage + place dans le montage.
        d.cursor shouldBe 19.5.seconds
        d.targetAt shouldBe 19.5.seconds
        c.text("0:19.623")
        // Capture : l'outil en mode « kill », forme d'onde de synthèse.
        val wave = (0 until MusicDropUi.OVERVIEW_BUCKETS).map { i -> (0.3 + 0.6 * ((i * 7) % 11) / 11.0).toFloat() }
        val shown = state.value.montage!!.plan!!.copy(drop = state.value.montage!!.plan!!.drop!!.copy(loading = false, duration = 228.seconds, overview = wave, detail = wave.take(MusicDropUi.DETAIL_BUCKETS), beats = (0 until 480).map { (it * 476).milliseconds }))
        for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/", "ja" to "ja/")) {
            I18n.withLanguage(tag) {
                val image = SceneRendering.image(1320, 700) { HighlightsTheme { MusicDropPanel(shown.drop!!, noop, androidx.compose.ui.Modifier.padding(16.dp)) } }
                val file: Path = out.resolve("${folder}montage_kill_on_music.png")
                file.parent.createDirectories()
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        c.confirm()
        state.value.montage!!.constraints.pins shouldBe mapOf(g.refs.last() to MontagePin.At(19.623.seconds))
        state.value.montage!!.plan!!.drop shouldBe null
        replans shouldBe 1
        // Revenir au placement auto : l'épingle part.
        c.openForKill(g.refs.last().toString())
        state.value.montage!!.plan!!.drop!!.pinned shouldBe true
        state.value.montage!!.plan!!.drop!!.cursor shouldBe 19.623.seconds
        c.clear()
        state.value.montage!!.constraints.pins shouldBe emptyMap()
    }
})
