package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.montage.KillRef
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontagePin
import dev.highlights.montage.SectionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.sin
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

    test("temps visé : le plus proche dans la frise, rendu en instant du morceau, épingles replacées sur leur temps") {
        val beats = (0 until 20).map { (it * 500).milliseconds }
        val view = PlanView("m.mp3", 120.0, 55.seconds, 10.seconds, beats, beats.map { 0.5 }, emptyList(), null, emptyList(), emptyList())
        view.beatNear(3.2.seconds) shouldBe 6
        view.beatNear(30.seconds) shouldBe 19
        view.musicAt(6) shouldBe 58.seconds
        val g = group(100.0)
        val h = group(200.0)
        val c = MontageConstraints.NONE.pinAt(g.refs, 58.1.seconds).pinAt(h.refs, 120.seconds).edit(group(300.0).refs, KillEdit.DROP)
        // Dans le passage : arrondie au temps (3,0 s du montage) ; dehors : gardée avec son instant du morceau ; la drop n'y est pas.
        view.pinMarks(c) shouldBe listOf(PinMark(g.refs.last(), 58.1.seconds, 3.seconds), PinMark(h.refs.last(), 120.seconds, null))
    }

    test("frise du plan : épingler sur un temps, avant/pendant/après (build/ui-snapshots/montage_plan_pin*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val duration = 24.seconds
        val beats = (0 until 48).map { (it * 500).milliseconds }
        val placed = listOf(group(312.0) to (0.0 to 6.0), group(905.0, 906.2) to (6.0 to 12.0), group(1500.0) to (12.0 to 18.0), group(2100.0) to (18.0 to 24.0))
        fun view(order: List<Int>) = PlanView(
            music = "Rome Is Burning.mp3", bpm = 120.0, musicStart = 55.seconds, duration = duration, beats = beats,
            energy = beats.map { t -> 0.5 + 0.4 * sin(t.inWholeMilliseconds / 1000.0 * 3.1) },
            sections = listOf(PlanSection(0.seconds, 8.seconds, SectionKind.BUILD_UP, 0.5), PlanSection(8.seconds, duration, SectionKind.DROP, 1.0)),
            dropAt = 8.seconds,
            clips = order.mapIndexed { slot, i ->
                val g = placed[i].first
                val (a, b) = placed[slot].second
                PlanClip(g, a.seconds, b.seconds, listOf(PlanKill((b - 1.0).seconds, g.refs.last())), a == 6.0)
            },
            left = listOf(group(400.0), group(1000.0)),
        )
        val pinnedGroup = placed[0].first
        // Après : le groupe 1 épinglé au temps de 17,0 s du montage (72,0 s du morceau), le plan l'y a mis.
        val pinned = MontageConstraints.NONE.pinAt(pinnedGroup.refs, 72.seconds)
        val shots = listOf(
            Triple("montage_plan_pin_avant", MontagePlanState(loading = false, view = view(listOf(0, 1, 2, 3))) to MontageConstraints.NONE, null),
            Triple("montage_plan_pin_glisser", MontagePlanState(loading = false, view = view(listOf(0, 1, 2, 3))) to MontageConstraints.NONE, pinnedGroup to 34),
            Triple("montage_plan_pin_apres", MontagePlanState(loading = false, view = view(listOf(1, 2, 0, 3)), planned = pinned) to pinned, null),
        )
        for ((name, stateAndConstraints, drag) in shots) {
            val (state, constraints) = stateAndConstraints
            for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/")) {
                I18n.withLanguage(tag) {
                    val image = SceneRendering.image(1320, 600) {
                        HighlightsTheme { MontagePlanContent(constraints, state, noop, previewDrag = drag) }
                    }
                    val file: Path = out.resolve("$folder$name.png")
                    file.parent.createDirectories()
                    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    test("frise du plan : rendu hors écran (build/ui-snapshots/montage_plan*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val duration = 42.seconds
        val beats = (0 until 84).map { (it * 500).milliseconds }
        val drop = 16.seconds
        val placed = listOf(
            group(312.0) to (0.0 to 4.0), group(640.0) to (4.0 to 8.0), group(905.0, 906.2) to (8.0 to 12.0),
            group(1210.0, 1211.0, 1212.4, rank = 3.5) to (12.0 to 18.0), group(1500.0) to (18.0 to 22.0), group(1620.0) to (22.0 to 26.0),
            group(1800.0) to (26.0 to 30.0), group(1950.0, 1951.0) to (30.0 to 36.0), group(2100.0) to (36.0 to 42.0),
        )
        val view = PlanView(
            music = "Rome Is Burning.mp3", bpm = 120.0, musicStart = 55.seconds, duration = duration, beats = beats,
            energy = beats.map { t -> val s = t.inWholeMilliseconds / 1000.0; (if (s < 16) 0.35 + 0.3 * s / 16 else 0.9) * (0.75 + 0.25 * sin(s * 6.3)) },
            sections = listOf(
                PlanSection(0.seconds, 8.seconds, SectionKind.INTRO, 0.2),
                PlanSection(8.seconds, drop, SectionKind.BUILD_UP, 0.55),
                PlanSection(drop, 34.seconds, SectionKind.DROP, 1.0),
                PlanSection(34.seconds, duration, SectionKind.BODY, 0.7),
            ),
            dropAt = drop,
            clips = placed.map { (g, span) ->
                val (a, b) = span
                PlanClip(g, a.seconds, b.seconds, g.kills.mapIndexed { i, k -> PlanKill((b - 1.5 - (g.kills.last() - k).inWholeMilliseconds / 1000.0).seconds, KillRef.of(media, k)) }, a.seconds <= drop && drop < b.seconds)
            },
            left = listOf(group(400.0), group(700.0, 702.0), group(1000.0), group(1300.0), group(2300.0)),
        )
        val constraints = MontageConstraints.NONE
            .edit(placed[1].first.refs, KillEdit.EXCLUDE)
            .edit(placed[5].first.refs, KillEdit.DROP)
            .edit(view.left[2].refs, KillEdit.FORCE)
        val states = listOf(
            "montage_plan" to MontagePlanState(loading = false, view = view, planned = MontageConstraints.NONE),
            "montage_plan_impossible" to MontagePlanState(
                loading = false, view = view, planned = constraints,
                error = "error.montage.edit.notPlaced",
            ),
        )
        for ((name, state) in states) {
            for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/", "ja" to "ja/")) {
                I18n.withLanguage(tag) {
                    val image = SceneRendering.image(1320, 600) {
                        HighlightsTheme {
                            val error = state.error?.let { dev.highlights.core.i18n.Message(it, "kill" to view.left[2].key).text() }
                            MontagePlanContent(constraints, state.copy(error = error), noop)
                        }
                    }
                    val file: Path = out.resolve("$folder$name.png")
                    file.parent.createDirectories()
                    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
})
