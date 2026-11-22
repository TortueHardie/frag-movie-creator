package dev.highlights.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.VideoStream
import dev.highlights.montage.IntentKind
import dev.highlights.montage.IntentMark
import dev.highlights.montage.IntentRange
import dev.highlights.montage.MontageConstraints
import dev.highlights.montage.MusicIntents
import dev.highlights.montage.SectionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Vrais gestes de souris sur l'étape 1 de l'éditeur (22/11 : bords de la vitrine et repères qui lâchaient dès le premier
 * mouvement). Le geste est donné en dix mouvements séparés, l'écran se redessinant entre chacun, comme avec une souris.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class EditorDragTest : FunSpec({
    val media = MediaInfo(Path("D:/Vidéo/partie.mp4"), 1, 40.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val period = 0.32
    val beats = (0 until 700).map { (it * period).seconds }
    val marks = listOf(IntentMark(1, IntentKind.KILL, 40.seconds), IntentMark(2, IntentKind.KILL, 50.seconds))
    val intents = MusicIntents(marks, showcase = IntentRange(10.seconds, 14.seconds, true))
    val view = PlanView(
        music = "x.mp3", bpm = 187.6, musicStart = 30.seconds, duration = 30.seconds,
        beats = (0 until 90).map { (it * period).seconds }, energy = List(90) { 0.6 },
        sections = listOf(PlanSection(0.seconds, 30.seconds, SectionKind.DROP, 1.0)),
        dropAt = 0.seconds, clips = emptyList(), left = listOf(PlanGroup(media, listOf(100.seconds), 1.0), PlanGroup(media, listOf(200.seconds), 1.0)),
        musicPath = Path("D:/Musique/x.mp3"), proposal = intents,
    )
    val editor = MusicEditorUi(
        loading = false, duration = 200.seconds, peaks = List(MusicEditorUi.PEAKS) { 0.5f }, beats = beats, notes = emptyList(),
        sections = listOf(SongSection(0.seconds, 27.seconds, SectionKind.INTRO, 0.2), SongSection(27.seconds, 200.seconds, SectionKind.DROP, 1.0)),
        magnet = false,
    )

    fun scene(): Triple<MusicEditorController, MutableStateFlow<UiState>, UiActions> {
        val state = MutableStateFlow(UiState(montage = MontageUiState(constraints = MontageConstraints.NONE, plan = MontagePlanState(loading = false, view = view), editor = editor)))
        val c = MusicEditorController(
            CoroutineScope(Dispatchers.Unconfined), state, { null },
            { change -> state.value = state.value.copy(montage = state.value.montage?.let(change)) }, {}, sinks = { null },
        )
        c.planned(view)
        val actions = java.lang.reflect.Proxy.newProxyInstance(UiActions::class.java.classLoader, arrayOf(UiActions::class.java)) { _, m, args ->
            when (m.name) {
                "dragEditor" -> c.drag(EditorDragKind.valueOf(args[0] as String), args[1] as String?, args[2] as Double, args[3] as Boolean, args[4] as Boolean)
                "dropEditor" -> c.drop()
                else -> null
            }
            if (m.returnType == java.lang.Boolean.TYPE) false else null
        } as UiActions
        return Triple(c, state, actions)
    }

    /** Glisse le nœud [tag] de [steps] × [dx] px, en mouvements séparés, et renvoie les secondes par pixel de la piste. */
    fun drag(tag: String, dx: Float, steps: Int = 10): MusicEditorUi {
        val (_, state, actions) = scene()
        runDesktopComposeUiTest(1600, 940) { gesture(state, actions, tag, dx, steps) }
        return state.value.montage!!.editor!!
    }

    test("bord de fin de la vitrine : suivi jusqu'au bout du geste, pas seulement le premier pas") {
        val before = 14.seconds
        val one = drag("showcase-end", 30f, steps = 1).intents!!.showcase!!.end - before
        val ten = drag("showcase-end", 30f).intents!!.showcase!!.end - before
        // Dix pas de 30 px vont bien plus loin qu'un seul (le geste repartait de zéro à chaque mouvement).
        (ten.inWholeMilliseconds / 1000.0) shouldBeGreaterThan 3 * (one.inWholeMilliseconds / 1000.0)
        drag("showcase-end", 30f).drag shouldBe null
    }

    test("toute la vitrine se déplace par son libellé, même longueur") {
        val e = drag("showcase-move", 30f)
        val r = e.intents!!.showcase!!
        (r.start > 11.seconds) shouldBe true
        (r.end - r.start) shouldBe 4.seconds
    }

    test("un repère glissé au-delà de son voisin reste celui qu'on tient") {
        val e = drag("mark-1", 40f, steps = 12)
        val m1 = e.intents!!.marks.single { it.id == 1 }.at
        val m2 = e.intents!!.marks.single { it.id == 2 }.at
        (m1 > 40.seconds + 1.seconds) shouldBe true
        m2 shouldBe 50.seconds
        e.drag shouldBe null
    }
})

@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.gesture(state: MutableStateFlow<UiState>, actions: UiActions, tag: String, dx: Float, steps: Int) {
    setContent {
        val s by state.collectAsState()
        HighlightsTheme { MusicMontageContent(s.montage!!, s.montage!!.editor!!, actions) }
    }
    val node = onNodeWithTag(tag)
    node.performTouchInput { down(center) }
    repeat(steps) {
        node.performTouchInput { moveBy(Offset(dx, 0f)) }
        waitForIdle()
    }
    node.performTouchInput { up() }
    waitForIdle()
}
