package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Étape 1 = repères d'intention sur la musique (kill, multi-kill, coupe, ralenti, transition, vitrine), sans dire quel
 * kill ; étape 2 = quel kill va sur quel repère, et les plans construits autour (demande du 20/11).
 */
class IntentLayoutTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    // Kills seuls toutes les 2 minutes, et un double kill à 7:00 / 7:01.
    val kills = listOf(60.0, 180.0, 300.0, 420.0, 421.0, 540.0, 660.0, 780.0, 900.0)
    val session = run {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        Session(
            createdAt = Instant.EPOCH, media = media, profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "game-events") }),
            highlights = emptyList(),
        )
    }
    val n = 240
    val music = MusicAnalysis(
        Path("musique.mp3"), 120.seconds, 120.0, List(n) { (it * 0.5).seconds }, downbeatPhase = 0,
        beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
        sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64,
    )
    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds).earlierDefaults()
    // Les kills seuls rangés par score : 900 le meilleur, puis 780, 660…
    val groups = MontagePlanner.groups(listOf(session), settings).map { g -> g.copy(score = g.kills.last().inWholeSeconds / 1000.0) }
    fun s(d: Duration) = d.inWholeMicroseconds / 1e6
    fun kill(id: Int, at: Double, kind: IntentKind = IntentKind.KILL) = IntentMark(id, kind, at.seconds, byHand = true)

    /** Chaque groupe placé tombe (son dernier kill) exactement sur son repère. */
    fun check(p: MontagePlan, a: IntentAssignment) {
        a.laid.forEach { (g, at) ->
            val c = p.clips.single { !it.showcase && it.group.kills.last() == g.kills.last() }
            withClue("kill ${g.kills.last()}") { s(p.music.beatTime(c.slot.startBeat) + c.toOutput(c.group.kills.last())) shouldBe (s(at) plusOrMinus 1e-3) }
        }
        p.clips.zipWithNext().all { (x, y) -> x.slot.endBeat == y.slot.startBeat } shouldBe true
    }

    test("le meilleur kill va sur la drop, les suivants sur les repères les plus forts") {
        val intents = MusicIntents(listOf(kill(1, 26.0), kill(2, 32.0), kill(3, 37.0)))
        val a = IntentLayout.assign(groups, music, intents)
        // Le double kill de 7:00 est le meilleur groupe : sur la drop.
        a.byMark.getValue(2).kills.last() shouldBe 421.seconds
        // 26 s et 37 s : temps faibles égaux ; les deux suivants y vont, aucun repère vide.
        a.byMark.values.map { it.kills.last() }.toSet() shouldBe setOf(421.seconds, 900.seconds, 780.seconds)
        a.extras shouldBe emptyList()
        check(IntentLayout.plan(groups, music, settings, intents), a)
    }

    test("repères rapprochés ou « multi-kill » : le multi-kill y va, ses kills dans un seul plan") {
        val close = MusicIntents(listOf(kill(1, 30.0), kill(2, 30.5), kill(3, 31.0), kill(4, 36.0)))
        val a = IntentLayout.assign(groups, music, close)
        val multi = a.byMark.getValue(1)
        multi.kills shouldBe listOf(420.seconds, 421.seconds)
        a.byMark.getValue(2) shouldBe multi
        a.byMark.getValue(3) shouldBe multi
        // Le dernier kill du multi-kill tombe sur le dernier repère du groupe.
        a.laid.single { it.first == multi }.second shouldBe 31.0.seconds
        check(IntentLayout.plan(groups, music, settings, close), a)
        val marked = IntentLayout.assign(groups, music, MusicIntents(listOf(kill(1, 30.0), kill(2, 40.0, IntentKind.MULTI))))
        marked.byMark.getValue(2).kills.size shouldBe 2
    }

    test("plus de repères que de kills : les repères en trop deviennent de la vitrine, ou restent vides") {
        val marks = (0 until 10).map { kill(it + 1, 20.0 + it * 3.0) }
        val a = IntentLayout.assign(groups, music, MusicIntents(marks))
        a.laid.size shouldBe 8
        a.extras.size shouldBe 2
        val showcase = IntentLayout.plan(groups, music, settings, MusicIntents(marks))
        showcase.clips.count { it.showcase } shouldBe 2
        check(showcase, a)
        val empty = IntentLayout.plan(groups, music, settings, MusicIntents(marks, extras = ExtraMarks.EMPTY))
        empty.clips.count { it.showcase } shouldBe 0
    }

    test("coupe ici : un plan commence exactement au repère ; vitrine sur sa plage") {
        val intents = MusicIntents(
            listOf(kill(1, 30.0), IntentMark(2, IntentKind.CUT, 33.0.seconds, true), kill(3, 36.0)),
            showcase = IntentRange(22.0.seconds, 25.0.seconds, true),
        )
        val p = IntentLayout.plan(groups, music, settings, intents)
        p.clips.map { s(p.music.beatTime(it.slot.startBeat)) }.contains(33.0) shouldBe true
        // Vitrine de 22 s à 25 s, puis le premier kill ; aucun kill dedans.
        s(p.musicStart) shouldBe 22.0
        val shots = p.clips.takeWhile { it.showcase }
        s(p.music.beatTime(shots.last().slot.endBeat)) shouldBe 25.0
        check(p, IntentLayout.assign(groups, music, intents))
    }

    test("vitrine sur tout le début, qui recouvre les premiers kills : gardée jusqu'à l'approche du premier (22/11 : 4,8 s au lieu de 31)") {
        // Comme le montage de « life kinda sucks » : vitrine tirée du début jusqu'après la drop (32 s), kills un temps
        // sur deux à partir de la drop ; la vitrine en recouvrait deux, elle était abandonnée en entier.
        val intents = MusicIntents(
            (0 until 6).map { kill(it + 1, 32.0 + it) },
            showcase = IntentRange(0.seconds, 33.5.seconds, true),
        )
        val p = IntentLayout.plan(groups, music, settings, intents)
        s(p.musicStart) shouldBe 0.0
        val shots = p.clips.takeWhile { it.showcase }
        (shots.size > 5) shouldBe true
        // Plus de plans de vitrine que de groupes : chaque plan reste un passage différent, jamais deux fois le même.
        (shots.size > groups.size) shouldBe true
        shots.map { it.group.media.path to it.start }.toSet().size shouldBe shots.size
        shots.zipWithNext().none { (a, b) -> a.group.media.path == b.group.media.path && a.start < b.end && b.start < a.end } shouldBe true
        // La vitrine s'arrête à l'approche du premier kill (deux temps avant lui).
        s(p.music.beatTime(shots.last().slot.endBeat)) shouldBe 31.0
        check(p, IntentLayout.assign(groups, music, intents))
    }

    test("morceaux de vitrine : coupés autour des kills, approche et fin laissées, morceaux trop courts écartés") {
        val period = 0.5.seconds
        KillLayout.fillerPieces(0.seconds, 20.seconds, listOf(10.seconds), period) shouldBe listOf(0.seconds to 9.seconds, 11.seconds to 20.seconds)
        KillLayout.fillerPieces(0.seconds, 20.seconds, emptyList(), period) shouldBe listOf(0.seconds to 20.seconds)
        // Un kill tout au début : le morceau d'avant (moins de deux temps) disparaît.
        KillLayout.fillerPieces(0.seconds, 5.seconds, listOf(0.5.seconds), period) shouldBe listOf(1.5.seconds to 5.seconds)
    }

    test("ralenti et transition seulement là où les repères les posent") {
        val intents = MusicIntents(
            listOf(kill(1, 26.0), kill(2, 32.0), IntentMark(3, IntentKind.SLOW, 32.0.seconds, true), kill(4, 38.0), IntentMark(5, IntentKind.TRANSITION, 35.2.seconds, true)),
        )
        val p = IntentLayout.plan(groups, music, settings, intents)
        p.byIntents shouldBe true
        val slowed = p.clips.filter { it.slow != null }
        slowed.size shouldBe 1
        slowed.single().slot.dropBeat shouldBe 64
        val whips = MontageRenderBuilder.whips(p)
        whips.count { it != null } shouldBe 1
        // Sur la coupe la plus proche de 35,2 s.
        val i = whips.indexOfFirst { it != null }
        val cutAt = s(p.music.beatTime(p.clips[i].slot.startBeat))
        p.clips.indices.drop(1).minBy { kotlin.math.abs(s(p.music.beatTime(p.clips[it].slot.startBeat)) - 35.2) } shouldBe i
        (cutAt > 32.0 && cutAt < 38.0) shouldBe true
        // Sans repère de ralenti ni de transition : aucun.
        val plain = IntentLayout.plan(groups, music, settings, MusicIntents(listOf(kill(1, 26.0), kill(2, 32.0), kill(4, 38.0))))
        plain.clips.count { it.slow != null } shouldBe 0
        MontageRenderBuilder.whips(plain).count { it != null } shouldBe 0
    }

    test("étape 2 : un kill mis à la main sur un repère y reste, les autres se répartissent") {
        val intents = MusicIntents(listOf(kill(1, 26.0), kill(2, 32.0), kill(3, 37.0)), fixed = mapOf(2 to KillRef("partie.mp4", 60.seconds)))
        val a = IntentLayout.assign(groups, music, intents)
        a.byMark.getValue(2).kills.last() shouldBe 60.seconds
        a.byMark.values.map { it.kills.last() }.toSet() shouldBe setOf(60.seconds, 421.seconds, 900.seconds)
        check(IntentLayout.plan(groups, music, settings, intents), a)
    }

    test("proposition de départ : un repère par kill du plan automatique, multi-kill compris") {
        val auto = MontagePlanner.best(groups, music, settings)
        val p = IntentLayout.propose(auto)
        val kills = auto.clips.filterNot { it.showcase }
        p.killMarks.size shouldBe kills.size
        p.killMarks.zip(kills).forEach { (m, c) ->
            s(m.at) shouldBe (s(auto.music.beatTime(c.slot.startBeat) + c.anchorOutput(auto.music)) plusOrMinus 1e-6)
            m.kind shouldBe (if (c.kills.size >= 2) IntentKind.MULTI else IntentKind.KILL)
        }
        p.marks.none { it.byHand } shouldBe true
    }
})
