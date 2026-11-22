package dev.highlights.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import dev.highlights.core.i18n.I18n
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MusicIntents
import dev.highlights.montage.ExtraMarks
import dev.highlights.montage.IntentRange
import dev.highlights.montage.IntentMark
import dev.highlights.montage.IntentKind
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
    // Proposition de l'étape 1 : un repère par kill, un ralenti sur la drop, une transition, pas de vitrine.
    val proposal = MusicIntents(
        (placed.indices.map { i -> IntentMark(i + 1, IntentKind.KILL, musicStart + ((i * 8 + 2) * period).seconds) } +
            listOf(IntentMark(10, IntentKind.SLOW, musicStart + (10 * period).seconds), IntentMark(11, IntentKind.TRANSITION, musicStart + (16 * period).seconds))).sortedBy { it.at },
    )
    val view = PlanView(
        music = "Rome Is Burning.mp3", bpm = 126.07, musicStart = musicStart, duration = (6 * 8 * period).seconds,
        beats = (0 until 48).map { (it * period).seconds }, energy = List(48) { 0.6 },
        sections = listOf(PlanSection(0.seconds, 5.7.seconds, SectionKind.BUILD_UP, 0.3), PlanSection(5.7.seconds, 22.8.seconds, SectionKind.DROP, 1.0)),
        dropAt = 5.713.seconds, clips = clips, left = left, musicPath = Path("D:/Musique/Rome Is Burning.mp3"), proposal = proposal,
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

    test("étape 1 : les repères proposés (aucun kill nommé), sur tout le morceau ; les groupes comptés pour « kills disponibles »") {
        val (c, state) = controller()
        val e = ed(state)
        e.intents shouldBe proposal
        e.proposal shouldBe proposal
        e.intents!!.killMarks.map { it.at } shouldBe placed.indices.map { i -> musicStart + ((i * 8 + 2) * period).seconds }
        e.groups.size shouldBe placed.size + left.size
        e.extraMarks() shouldBe emptyList()
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

    test("étape 1 : déplacer un repère change seulement son instant, il devient « à toi » ; proches permis, aucune contrainte") {
        val (c, state) = controller()
        val m = ed(state).intents!!.killMarks[2]
        c.drag(EditorDragKind.MARK, m.id.toString(), (beats[20] + 25.milliseconds).inWholeMicroseconds / 1e6, free = false)
        c.drop()
        val moved = ed(state).intents!!.marks.single { it.id == m.id }
        moved.at shouldBe beats[20] + 11.milliseconds
        moved.byHand shouldBe true
        state.value.montage!!.constraints.isEmpty shouldBe true
        // Sur l'instant d'un autre repère : rien n'est bloqué ni poussé.
        val other = ed(state).intents!!.killMarks.first { it.id != m.id }
        c.drag(EditorDragKind.MARK, m.id.toString(), (other.at + 100.milliseconds).inWholeMicroseconds / 1e6, free = true)
        c.drop()
        ed(state).intents!!.marks.single { it.id == m.id }.at shouldBe other.at + 100.milliseconds
        ed(state).intents!!.marks.single { it.id == other.id }.at shouldBe other.at
        // Kills de la partie : ils ne se glissent pas à l'étape 1.
        c.drag(EditorDragKind.KILL, placed[0].key, 70.0, free = true)
        ed(state).drag shouldBe null
    }

    test("étape 1 : outils (double-clic), vitrine, retirer, repères en trop, tout remettre en auto") {
        val (c, state) = controller()
        c.setTool(EditorTool.CUT)
        c.addMark(100.0, free = true)
        ed(state).intents!!.of(IntentKind.CUT).single().let { it.at shouldBe 100.seconds; it.byHand shouldBe true }
        c.setTool(EditorTool.SHOWCASE)
        c.addMark(20.0, free = true)
        ed(state).intents!!.showcase!!.start shouldBe 20.seconds
        c.drag(EditorDragKind.SHOWCASE_END, null, 23.5, free = true)
        c.drop()
        ed(state).intents!!.showcase!!.end shouldBe 23.5.seconds
        c.remove(MusicEditorController.SHOWCASE_KEY)
        ed(state).intents!!.showcase shouldBe null
        // Trois repères « kill » de plus : 9 repères pour 8 kills, un en trop, le plus faible.
        c.setTool(EditorTool.KILL)
        listOf(120.0, 130.0, 140.0).forEach { c.addMark(it, free = true) }
        ed(state).intents!!.killMarks.size shouldBe 9
        ed(state).extraMarks().size shouldBe 1
        c.setExtras(ExtraMarks.EMPTY)
        ed(state).intents!!.extras shouldBe ExtraMarks.EMPTY
        val cut = ed(state).intents!!.of(IntentKind.CUT).single()
        c.remove(MusicEditorController.markKey(cut.id))
        ed(state).intents!!.of(IntentKind.CUT) shouldBe emptyList()
        c.resetAuto()
        ed(state).intents shouldBe proposal
    }

    test("« Placer les plans » : les repères deviennent la contrainte du plan ; étape 2 : un kill glissé sur un repère, échange ; retirer") {
        val (c, state) = controller()
        val m = ed(state).intents!!.killMarks[1]
        c.drag(EditorDragKind.MARK, m.id.toString(), 60.0, free = true)
        c.drop()
        c.placeClips()
        val intents = state.value.montage!!.constraints.intents!!
        intents shouldBe ed(state).intents
        state.value.montage!!.constraints.layout shouldBe null
        ed(state).step shouldBe EditorStep.CLIPS
        // Les repères ne bougent plus à l'étape 2.
        c.drag(EditorDragKind.MARK, m.id.toString(), 70.0, free = true)
        ed(state).drag shouldBe null
        // Le kill du repère 1 glissé sur le repère 3 : il y va, celui du repère 3 prend sa place.
        val marks = proposal.killMarks
        c.drag(EditorDragKind.KILL, placed[0].key, marks[2].at.inWholeMicroseconds / 1e6, free = true)
        c.drop()
        val fixed = state.value.montage!!.constraints.intents!!.fixed
        fixed[marks[2].id] shouldBe placed[0].refs.last()
        fixed[marks[0].id] shouldBe placed[2].refs.last()
        state.value.montage!!.constraints.forced.contains(placed[0].refs.last()) shouldBe true
        // Retirer : le kill est exclu, plus fixé nulle part ; le repère prendra le meilleur suivant.
        c.remove(placed[0].key)
        state.value.montage!!.constraints.excluded.contains(placed[0].refs.last()) shouldBe true
        state.value.montage!!.constraints.intents!!.fixed.values.none { it == placed[0].refs.last() } shouldBe true
        c.backToKills()
        ed(state).step shouldBe EditorStep.KILLS
        ed(state).intents!!.marks.single { it.id == m.id }.at shouldBe 60.seconds
    }

    test("captures : étape 1 (repères, déplacement, outils, trop de repères) et étape 2 (build/ui-snapshots/music_editor*.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val (_, st) = controller()
        val laid = ed(st)
        val base = MontageUiState(music = Path("D:/Musique/Rome Is Burning.mp3"), plan = MontagePlanState(loading = false, view = view), editor = laid.copy(playhead = 30.523.seconds, dropAt = 30.523.seconds))
        val marks = proposal.killMarks
        val edited = proposal.copy(
            marks = (proposal.marks.map { if (it.id == marks[2].id) it.copy(at = 44.335.seconds, byHand = true) else it } + listOf(
                IntentMark(20, IntentKind.KILL, 55.60.seconds, true), IntentMark(21, IntentKind.KILL, 55.84.seconds, true), IntentMark(22, IntentKind.KILL, 56.08.seconds, true),
                IntentMark(23, IntentKind.CUT, 41.95.seconds, true), IntentMark(24, IntentKind.SLOW, 30.523.seconds, true),
            )).sortedBy { it.at },
            showcase = IntentRange(22.9.seconds, 24.81.seconds, true),
        )
        val zoom = base.editor!!.copy(intents = edited, from = 21.seconds, to = 60.seconds, tool = EditorTool.KILL)
        val states = listOf(
            "music_editor_kills" to base,
            "music_editor_kills_move" to base.copy(editor = zoom.copy(selected = MusicEditorController.markKey(marks[2].id), drag = EditorDrag(EditorDragKind.MARK, marks[2].id.toString(), 44.335.seconds, "note"))),
            "music_editor_kills_add" to base.copy(editor = zoom.copy(selected = MusicEditorController.markKey(23), tool = EditorTool.CUT)),
            "music_editor_kills_over" to base.copy(editor = zoom.copy(intents = edited.copy(marks = edited.marks + IntentMark(25, IntentKind.KILL, 50.0.seconds, true)))),
            "music_editor_clips" to base.copy(
                constraints = MontageConstraints(intents = proposal),
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

    test("capture : ce qui est appris des placements, dans « Goûts appris » (build/ui-snapshots/taste_layout.png)") {
        val out = Path("build", "ui-snapshots").createDirectories()
        val learned = dev.highlights.montage.LearnedLayout(
            montages = 4, chosen = 41, offset = 25.milliseconds, offsetSpread = 4.milliseconds, offsetActive = true,
            accentGain = 0.26, accentActive = true, density = 1.21, densityActive = true,
            offbeatShare = 0.12, autoOffbeatShare = 0.05, afterDropShare = 0.6, autoAfterDropShare = 0.55,
        )
        val states = listOf("taste_layout" to learned, "taste_layout_pending" to learned.copy(montages = 2, offsetActive = false, accentActive = false, densityActive = false), "taste_layout_none" to dev.highlights.montage.LearnedLayout.NONE)
        for ((name, l) in states) {
            for ((tag, folder) in listOf("fr" to "", "en" to "en/", I18n.PSEUDO_TAG to "long/", "ja" to "ja/")) {
                I18n.withLanguage(tag) {
                    val image = SceneRendering.image(760, 300) { HighlightsTheme { LayoutTasteCard(dev.highlights.pipeline.TasteState(Path("taste-history.json"), 0, 0, dev.highlights.montage.TasteLearner.learn(emptyList()), emptyList(), null, null, null, layout = l), noop) } }
                    val file: Path = out.resolve("$folder$name.png")
                    file.parent.createDirectories()
                    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }
})
