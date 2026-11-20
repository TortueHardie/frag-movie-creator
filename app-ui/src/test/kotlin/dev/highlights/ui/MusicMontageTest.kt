package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MontagePin
import dev.highlights.montage.SectionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
class MusicMontageTest : FunSpec({
    val media = MediaInfo(Path("D:/Vidéo/WARDOGS_1/WARDOGS_partie1.mp4"), 1, 40.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))
    val noop = java.lang.reflect.Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { _, _, _ -> null } as UiActions
    fun group(vararg kills: Double) = PlanGroup(media, kills.map { it.seconds }, 1.0)
    val period = 0.476
    // Morceau : 126 BPM, 3 min 54 ; passage du montage de 0:24.8 à 0:52.9, un plan par kill.
    val beats = (0 until 490).map { (0.046 + it * period).seconds }
    val musicStart = 24.81.seconds
    val placed = listOf(1000.0, 1100.0, 1200.0, 1300.0, 1400.0, 1500.0).map { group(it) }
    val clips = placed.mapIndexed { i, g ->
        val start = (i * 8 * period).seconds
        PlanClip(g, start, start + (8 * period).seconds, listOf(PlanKill(start + (2 * period).seconds, g.refs.last())), i == 1)
    }
    val left = listOf(group(1700.0), group(1800.0))
    val view = PlanView(
        music = "Rome Is Burning.mp3", bpm = 126.07, musicStart = musicStart, duration = (6 * 8 * period).seconds,
        beats = (0 until 48).map { (it * period).seconds }, energy = List(48) { 0.6 },
        sections = listOf(PlanSection(0.seconds, 5.7.seconds, SectionKind.BUILD_UP, 0.3), PlanSection(5.7.seconds, 22.8.seconds, SectionKind.DROP, 1.0)),
        dropAt = 5.713.seconds, clips = clips, left = left, musicPath = Path("D:/Musique/Rome Is Burning.mp3"),
    )
    val peaks = (0 until MusicEditorUi.PEAKS).map { i -> val t = i * 234.0 / MusicEditorUi.PEAKS; ((if (t < 30.5) 0.3 else 0.85) * (0.6 + 0.4 * sin(t * 13.2))).toFloat().coerceAtLeast(0.05f) }
    val editor = MusicEditorUi(
        loading = false, duration = 234.seconds, peaks = peaks, beats = beats, notes = beats.map { it + 11.milliseconds },
        sections = listOf(SongSection(0.seconds, 8.6.seconds, SectionKind.INTRO, 0.1), SongSection(8.6.seconds, 30.522.seconds, SectionKind.BUILD_UP, 0.3), SongSection(30.522.seconds, 92.4.seconds, SectionKind.DROP, 1.0), SongSection(92.4.seconds, 234.seconds, SectionKind.BODY, 0.5)),
    )

    fun controller(initial: MontageConstraints = MontageConstraints.NONE): Pair<MusicEditorController, MutableStateFlow<UiState>> {
        val state = MutableStateFlow(UiState(montage = MontageUiState(constraints = initial, plan = MontagePlanState(loading = false, view = view), editor = editor)))
        val c = MusicEditorController(
            CoroutineScope(Dispatchers.Unconfined), state, { null },
            { change -> state.value = state.value.copy(montage = state.value.montage?.let(change)) }, {}, sinks = { null },
        )
        return c to state
    }

    test("vue de départ : le passage du montage avec une marge ; zoom autour du pointeur ; défilement borné au morceau") {
        val (c, state) = controller()
        val (from, to) = c.window()
        from shouldBe musicStart - MusicEditorUi.MARGIN
        to shouldBe musicStart + view.duration + MusicEditorUi.MARGIN
        c.zoom(0.5, 40.0)
        val e = state.value.montage!!.editor!!
        (e.to!! - e.from!!) shouldBe (to - from) / 2
        // Le point sous le pointeur reste à la même place relative.
        ((40.seconds - e.from!!) / (e.to!! - e.from!!)) shouldBe (((40.seconds - from) / (to - from)) plusOrMinus 1e-6)
        c.scroll(-1000.0)
        state.value.montage!!.editor!!.from shouldBe Duration.ZERO
    }

    test("aimant : la note la plus proche (ou le temps) à 70 ms, sinon l'instant tel quel ; Alt ou aimant coupé : tel quel") {
        val (c, _) = controller()
        val beat = beats[100]
        c.snap(editor, beat + 30.milliseconds, free = false) shouldBe ((beat + 11.milliseconds) to "note")
        c.snap(editor, beat - 20.milliseconds, free = false) shouldBe (beat to "beat")
        c.snap(editor, beat + 200.milliseconds, free = false) shouldBe ((beat + 200.milliseconds) to null)
        c.snap(editor, beat + 30.milliseconds, free = true) shouldBe ((beat + 30.milliseconds) to null)
        c.snap(editor.copy(magnet = false), beat + 30.milliseconds, free = false) shouldBe ((beat + 30.milliseconds) to null)
    }

    test("déplacer un kill : épinglé (son groupe) à l'instant aimanté où on le lâche ; plan recalculé") {
        val (c, state) = controller()
        val g = placed[2]
        val target = beats[120] + 25.milliseconds
        c.drag(EditorDragKind.KILL, g.key, target.inWholeMicroseconds / 1e6, free = false)
        state.value.montage!!.editor!!.drag shouldBe EditorDrag(EditorDragKind.KILL, g.key, beats[120] + 11.milliseconds, "note")
        c.drop()
        state.value.montage!!.constraints.pins shouldBe mapOf(g.refs.last() to MontagePin.At(beats[120] + 11.milliseconds))
        state.value.montage!!.editor!!.drag shouldBe null
        state.value.montage!!.editor!!.selected shouldBe g.refs.last().toString()
    }

    test("lâcher sur la place d'un kill déjà posé : l'autre est poussé au temps libre le plus proche (pas d'échange)") {
        val a = placed[0]
        val b = placed[3]
        val (c, state) = controller(MontageConstraints.NONE.pin(a.refs.last(), MontagePin.At(beats[110])))
        c.drag(EditorDragKind.KILL, b.key, beats[110].inWholeMicroseconds / 1e6, free = false)
        c.drop()
        val pins = state.value.montage!!.constraints.pins
        pins[b.refs.last()] shouldBe MontagePin.At(beats[110])
        val pushed = (pins[a.refs.last()] as MontagePin.At).music
        (pushed == beats[112] || pushed == beats[108]) shouldBe true
    }

    test("ajouter un kill de la liste : épinglé là où on le lâche au-dessus de la musique ; lâché ailleurs : rien") {
        val (c, state) = controller()
        val g = left[0]
        c.drag(EditorDragKind.NEW, g.key, 40.0, free = true, over = false)
        c.drop()
        state.value.montage!!.constraints.isEmpty shouldBe true
        c.drag(EditorDragKind.NEW, g.key, 40.0, free = true, over = true)
        c.drop()
        state.value.montage!!.constraints.pins shouldBe mapOf(g.refs.last() to MontagePin.At(40.seconds))
    }

    test("retirer un kill (tout son groupe), puis tout remettre en auto") {
        val (c, state) = controller()
        c.remove(placed[4].key)
        state.value.montage!!.constraints.excluded shouldBe placed[4].refs.toSet()
        c.resetAuto()
        state.value.montage!!.constraints.isEmpty shouldBe true
    }

    test("captures : vue d'ensemble, kill en déplacement, ajout depuis la liste (build/ui-snapshots/music_editor*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val base = MontageUiState(music = Path("D:/Musique/Rome Is Burning.mp3"), plan = MontagePlanState(loading = false, view = view), editor = editor.copy(playhead = musicStart + 5.713.seconds))
        val states = listOf(
            "music_editor" to base,
            "music_editor_move" to base.copy(
                constraints = MontageConstraints.NONE.pin(placed[2].refs.last(), MontagePin.At(beats[115])),
                editor = base.editor!!.copy(selected = placed[2].key, drag = EditorDrag(EditorDragKind.KILL, placed[2].key, musicStart + 15.2.seconds + 11.milliseconds, "note")),
            ),
            "music_editor_add" to base.copy(editor = base.editor!!.copy(drag = EditorDrag(EditorDragKind.NEW, left[0].key, musicStart + 14.seconds, "beat"))),
        )
        for ((name, s) in states) {
            for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/", "ja" to "ja/")) {
                I18n.withLanguage(tag) {
                    val image = SceneRendering.image(1600, 940) { HighlightsTheme { MusicMontageContent(s, s.editor!!, noop) } }
                    val file: Path = out.resolve("$folder$name.png")
                    file.parent.createDirectories()
                    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
})

