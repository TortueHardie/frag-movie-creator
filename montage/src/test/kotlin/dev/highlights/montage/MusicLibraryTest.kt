package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.ScoredTimeline
import dev.highlights.core.model.TimelineEvent
import dev.highlights.core.model.VideoStream
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.session.Session
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MusicLibraryTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))

    fun session(kills: List<Int>): Session {
        val grid = WindowGrid(1.seconds, 1.seconds, media.duration)
        return Session(
            createdAt = Instant.EPOCH,
            media = media,
            profileId = "wardogs",
            timeline = ScoredTimeline(grid, List(grid.count) { 0.5 }, emptyMap(), events = kills.map { TimelineEvent(it.seconds, "kill", 1.0, "n") }),
            highlights = emptyList(),
        )
    }

    /** Musique régulière : intro calme, montée, puis drop jusqu'à la fin. */
    fun music(name: String, seconds: Int, bpm: Double = 120.0, intro: Int = 32, build: Int = 32): MusicAnalysis {
        val n = (seconds * bpm / 60).toInt()
        val sections = listOf(
            MusicSection(0, intro, -20.0, 0.1),
            MusicSection(intro, intro + build, -14.0, 0.5),
            MusicSection(intro + build, n, -8.0, 1.0),
        )
        return MusicAnalysis(
            Path(name), seconds.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 }, sections = sections,
            dropBeat = intro + build,
        )
    }

    val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 60.seconds)
    val kills = listOf(60, 180, 183, 300, 420, 540, 660, 780, 900, 1020)
    val groups = MontagePlanner.groups(listOf(session(kills)), settings)

    test("une analyse gardée se relit à la microseconde près") {
        val a = music("musique.mp3", 90).copy(beats = List(180) { (it * 500_003L).microseconds }, halfAccent = DoubleArray(180) { 0.3 })
        val back = StoredMusic.of(a, 1234L to 5678L).toAnalysis(Path("ailleurs.mp3"))!!
        back.file shouldBe Path("ailleurs.mp3")
        back.beats shouldContainExactly a.beats
        back.duration shouldBe a.duration
        back.bpm shouldBe a.bpm
        back.sections shouldContainExactly a.sections
        back.dropBeat shouldBe a.dropBeat
        back.beatAccent.toList() shouldContainExactly a.beatAccent.toList()
        back.halfAccent.toList() shouldContainExactly a.halfAccent.toList()
        StoredMusic(MusicAnalyzer.VERSION, 1, 2, error = "trop courte").toAnalysis(Path("x.mp3")) shouldBe null
    }

    test("les musiques d'un dossier : sous-dossiers compris, autres fichiers ignorés") {
        val dir = tempdir().toPath()
        dir.resolve("b.mp3").writeText("")
        dir.resolve("notes.txt").writeText("")
        dir.resolve("sous").createDirectories().resolve("a.FLAC").writeText("")
        MusicLibrary.files(dir).map { dir.relativize(it).toString().replace('\\', '/') } shouldContainExactly listOf("b.mp3", "sous/a.FLAC")
        shouldThrow<dev.highlights.core.InputException> { MusicLibrary.files(dir.resolve("absent")) }
    }

    test("la musique qui montre tous les kills l'emporte sur une trop courte") {
        val long = music("longue.mp3", 120)
        val short = music("courte.mp3", 14, intro = 8, build = 8)
        val ranked = MusicChoice.rank(groups, listOf(short, long), settings) { false }
        ranked.first().music.file shouldBe Path("longue.mp3")
        (ranked.first().groups > ranked.last().groups) shouldBe true
        // La valeur tient compte des kills perdus : la note seule ne suffit pas à la courte.
        ranked.last().value shouldBe (ranked.last().score * ranked.last().groups / ranked.first().groups plusOrMinus 1e-9)
    }

    test("chaque musique garde son réglage « depuis le début »") {
        val a = music("a.mp3", 120)
        val b = music("b.mp3", 120)
        val ranked = MusicChoice.rank(groups, listOf(a, b), settings) { it == Path("b.mp3") }
        ranked.single { it.music.file == Path("a.mp3") }.plan!!.settings.cuts.fromStart shouldBe false
        ranked.single { it.music.file == Path("b.mp3") }.plan!!.settings.cuts.fromStart shouldBe true
    }

    test("retenue des musiques des trois derniers montages, pas au-delà") {
        val recent = listOf("a", "b", "c", "d").map { Path("$it.mp3") }
        MusicChoice.recency(Path("a.mp3"), recent) shouldBe MusicChoice.RECENT_PENALTY
        MusicChoice.recency(Path("c.mp3"), recent) shouldBe MusicChoice.RECENT_PENALTY
        MusicChoice.recency(Path("d.mp3"), recent) shouldBe 0.0
        MusicChoice.recency(Path("e.mp3"), recent) shouldBe 0.0
    }

    test("trois musiques qui collent presque autant tournent au lieu d'alterner à deux") {
        val musics = listOf("a", "b", "c").map { music("$it.mp3", 120) }
        val history = mutableListOf<java.nio.file.Path>()
        val chosen = List(4) {
            MusicChoice.rank(groups, musics, settings, recent = history.toList()) { false }.first().music.file.also { history.add(0, it) }
        }
        chosen.map { it.toString() } shouldContainExactly listOf("a.mp3", "b.mp3", "c.mp3", "a.mp3")
    }

    test("deux musiques qui collent autant : celle du dernier montage laisse sa place") {
        val a = music("a.mp3", 120)
        val b = music("b.mp3", 120)
        MusicChoice.rank(groups, listOf(a, b), settings) { false }.first().music.file shouldBe Path("a.mp3")
        val ranked = MusicChoice.rank(groups, listOf(a, b), settings, recent = listOf(Path("a.mp3"))) { false }
        ranked.first().music.file shouldBe Path("b.mp3")
        ranked.last().recency shouldBe MusicChoice.RECENT_PENALTY
    }

    test("la retenue ne fait jamais gagner une musique qui perd des kills") {
        val long = music("longue.mp3", 120)
        val short = music("courte.mp3", 14, intro = 8, build = 8)
        val ranked = MusicChoice.rank(groups, listOf(short, long), settings, recent = listOf(Path("longue.mp3"))) { false }
        ranked.first().music.file shouldBe Path("longue.mp3")
    }

    test("musiques récentes : sans doublon, la dernière d'abord, relues depuis le disque") {
        val file = tempdir().toPath().resolve("music-history.json")
        val a = Path("C:/Musique/a.mp3")
        val b = Path("C:/Musique/b.mp3")
        MusicHistory(file).apply { record(a); record(b); record(a) }
        MusicHistory(file).recent().map { it.toString() } shouldContainExactly listOf(a.toString(), b.toString())
    }

    test("sans kill à monter, aucune musique n'a de plan et chacune dit pourquoi") {
        val ranked = MusicChoice.rank(emptyList(), listOf(music("a.mp3", 120), music("b.mp3", 120)), settings) { false }
        ranked.forEach { c ->
            c.plan shouldBe null
            c.value shouldBe 0.0
            c.error shouldNotBe null
        }
    }
})
