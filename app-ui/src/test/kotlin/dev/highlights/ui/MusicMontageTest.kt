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

    fun controller(initial: MontageConstraints = MontageConstraints.NONE, step: EditorStep = EditorStep.KILLS): Pair<MusicEditorController, MutableStateFlow<UiState>> {
        val state = MutableStateFlow(UiState(montage = MontageUiState(constraints = initial, plan = MontagePlanState(loading = false, view = view), editor = editor.copy(step = step))))
        val c = MusicEditorController(
            CoroutineScope(Dispatchers.Unconfined), state, { null },
            { change -> state.value = state.value.copy(montage = state.value.montage?.let(change)) }, {}, sinks = { null },
        )
        c.planned(view)
        return c to state
    }
    fun ed(state: MutableStateFlow<UiState>) = state.value.montage!!.editor!!

    test("étape 1 : les kills du placement automatique, sur tout le morceau ; les autres groupes dans la liste") {
        val (c, state) = controller()
        val e = ed(state)
        e.layout!!.map { it.key } shouldBe placed.map { it.key }
        e.layout!!.first().at shouldBe musicStart + (2 * period).seconds
        e.auto shouldBe e.layout
        e.groups.map { it.key } shouldBe (placed + left).map { it.key }
        c.window() shouldBe (Duration.ZERO to 234.seconds)
        // Étape 2 : le passage du montage, avec une marge.
        val (c2, _) = controller(step = EditorStep.CLIPS)
        c2.window() shouldBe ((musicStart - MusicEditorUi.MARGIN) to (musicStart + view.duration + MusicEditorUi.MARGIN))
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

    test("étape 1 : déplacer un kill change seulement son instant (aucun plan, aucune contrainte, aucune erreur)") {
        val (c, state) = controller()
        val g = placed[2]
        c.drag(EditorDragKind.KILL, g.key, (beats[20] + 25.milliseconds).inWholeMicroseconds / 1e6, free = false)
        c.drop()
        ed(state).layout!!.single { it.key == g.key }.at shouldBe beats[20] + 11.milliseconds
        ed(state).layout!!.map { it.at } shouldBe ed(state).layout!!.map { it.at }.sorted()
        state.value.montage!!.constraints.isEmpty shouldBe true
        ed(state).notice shouldBe null
    }

    test("étape 1 : lâché sur le temps d'un autre kill, l'autre est poussé au temps libre le plus proche, avec un avertissement") {
        val (c, state) = controller()
        val a = ed(state).layout!![0]
        val b = placed[3]
        c.drag(EditorDragKind.KILL, b.key, a.at.inWholeMicroseconds / 1e6, free = true)
        c.drop()
        val layout = ed(state).layout!!
        layout.single { it.key == b.key }.at shouldBe a.at
        val pushed = layout.single { it.key == a.key }.at
        (pushed != a.at) shouldBe true
        ed(state).notice!!.key shouldBe "ui.musicEditor.pushed"
    }

    test("étape 1 : ajouter un kill de la liste (lâché sur la musique), le retirer, tout remettre en auto") {
        val (c, state) = controller()
        val g = left[0]
        c.drag(EditorDragKind.NEW, g.key, 100.0, free = true, over = false)
        c.drop()
        ed(state).layout!!.size shouldBe placed.size
        c.drag(EditorDragKind.NEW, g.key, 100.0, free = true, over = true)
        c.drop()
        ed(state).layout!!.single { it.key == g.key }.at shouldBe 100.seconds
        c.remove(placed[0].key)
        ed(state).layout!!.none { it.key == placed[0].key } shouldBe true
        c.resetAuto()
        ed(state).layout shouldBe ed(state).auto
    }

    test("« Placer les plans » : les kills posés deviennent la contrainte du plan ; étape 2 ; les kills n'y bougent plus") {
        val (c, state) = controller()
        c.drag(EditorDragKind.KILL, placed[1].key, 60.0, free = true)
        c.drop()
        c.placeClips()
        val layout = state.value.montage!!.constraints.layout!!
        layout.size shouldBe placed.size
        layout[placed[1].refs.last()] shouldBe 60.seconds
        ed(state).step shouldBe EditorStep.CLIPS
        c.drag(EditorDragKind.KILL, placed[0].key, 70.0, free = true)
        ed(state).drag shouldBe null
        c.backToKills()
        ed(state).step shouldBe EditorStep.KILLS
        ed(state).layout!!.single { it.key == placed[1].key }.at shouldBe 60.seconds
    }

    test("captures : étape 1 (vue, déplacement, ajout) et étape 2 (build/ui-snapshots/music_editor*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val (_, st) = controller()
        val laid = ed(st)
        val base = MontageUiState(music = Path("D:/Musique/Rome Is Burning.mp3"), plan = MontagePlanState(loading = false, view = view), editor = laid.copy(playhead = 30.523.seconds, dropAt = 30.523.seconds))
        val states = listOf(
            "music_editor_kills" to base,
            "music_editor_kills_move" to base.copy(editor = base.editor!!.copy(selected = placed[2].key, drag = EditorDrag(EditorDragKind.KILL, placed[2].key, 62.011.seconds, "note"))),
            "music_editor_kills_add" to base.copy(editor = base.editor!!.copy(drag = EditorDrag(EditorDragKind.NEW, left[0].key, 95.seconds, "beat"))),
            "music_editor_clips" to base.copy(
                constraints = MontageConstraints(layout = placed.associate { it.refs.last() to (musicStart + (2 * period).seconds) }),
                editor = base.editor!!.copy(step = EditorStep.CLIPS, selected = placed[2].key),
            ),
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
