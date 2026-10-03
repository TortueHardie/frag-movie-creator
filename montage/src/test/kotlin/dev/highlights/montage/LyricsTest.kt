package dev.highlights.montage

import dev.highlights.core.ffmpeg.EncoderProfile
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.LyricsEffect
import dev.highlights.core.model.LyricsSpan
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.TimeRange
import dev.highlights.core.model.VideoStream
import dev.highlights.editing.story.Caption
import dev.highlights.editing.story.Captions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LyricsTest : FunSpec({
    val settings = LyricsEffect(enabled = true)
    fun word(from: Int, to: Int, text: String) = Caption(TimeRange(from.milliseconds, to.milliseconds), text)

    test("transcription brute : mots nettoyés, mot étiré sur l'instrumental écarté") {
        // Début réel de « Rome Is Burning » : whisper colle « I » sur les 30 s d'instrumental.
        val raw = listOf(
            """{"start":40,"end":29980,"text":"I"}""",
            """{"start":30039,"end":30519,"text":"Pulled"}""",
            """{"start":30519,"end":30739,"text":"up,"}""",
            """{"start":30739,"end":30800,"text":" ..."}""",
            """{"start":30800,"end":31000,"text":"[Music]"}""",
        )
        val words = Lyrics.clean(Captions.parse(raw, Duration.ZERO), settings)
        words.map { it.text } shouldBe listOf("Pulled", "up")
    }

    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 1920, 1080, 60.0))
    val music = MusicAnalysis(
        Path("musique.wav"), 120.seconds, 120.0, List(240) { (it * 0.5).seconds }, 0, DoubleArray(240) { 1.0 }, DoubleArray(240) { 0.8 },
        listOf(MusicSection(0, 96, -14.0, 0.4), MusicSection(96, 240, -8.0, 1.0)), 96,
    )
    val montage = MontageSettings(killOffset = Duration.ZERO, lyrics = settings)
    val plan = MontagePlanner.plan(
        listOf(
            KillGroup(media, listOf(100.seconds, 102.seconds), 1.0, emptyList(), emptyList()),
            KillGroup(media, listOf(500.seconds), 0.5, emptyList(), emptyList()),
            KillGroup(media, listOf(900.seconds), 0.4, emptyList(), emptyList()),
        ),
        music, montage,
    )

    test("mots affichés : en temps du montage, de l'ouverture à la drop, chacun lisible sans chevaucher le suivant") {
        val drop = plan.dropAt!!
        val start = plan.musicStart
        val words = listOf(
            // Avant le passage retenu : pas à l'écran.
            Caption(TimeRange(start - 1.seconds, start - 0.5.seconds), "avant"),
            Caption(TimeRange(start + 1.seconds, start + 1.05.seconds), "vite"),
            Caption(TimeRange(start + 1.1.seconds, start + 1.6.seconds), "suivant"),
            Caption(TimeRange(start + drop - 0.1.seconds, start + drop + 0.3.seconds), "drop"),
            Caption(TimeRange(start + drop + 1.seconds, start + drop + 1.3.seconds), "après"),
        )
        val shown = Lyrics.shown(words, plan, settings)
        shown.map { it.text } shouldBe listOf("vite", "suivant", "drop")
        // Un mot de 50 ms reste jusqu'au suivant (0,1 s), pas 0,15 s : il ne le chevauche pas.
        shown[0].range shouldBe TimeRange(1.seconds, 1.1.seconds)
        shown[1].range shouldBe TimeRange(1.1.seconds, 1.6.seconds)
        // Le dernier mot avant la drop s'efface sur elle.
        shown[2].range.end shouldBe drop
        // Sur tout le montage : le mot d'après la drop aussi.
        Lyrics.shown(words, plan, settings.copy(span = LyricsSpan.ALL)).map { it.text } shouldBe listOf("vite", "suivant", "drop", "après")
    }

    test("rendu : un texte par mot sur le montage entier, avant le fondu de fin ; rien sans paroles") {
        val request = MontageRenderRequest(
            plan, OutputFormat.SOURCE, EditSettings(), EncoderProfile("libx264", listOf("-c:v", "libx264"), false), Path("out.mp4"), Path("f.txt"),
            lyrics = listOf(word(1000, 1300, "Pulled"), word(1300, 1500, "up")),
        )
        val vout = MontageRenderBuilder.build(request).filterGraph.lines().single { it.trimEnd(';').endsWith("[vout]") }
        vout shouldContain "text='PULLED'"
        vout shouldContain "text='UP'"
        (vout.indexOf("text='UP'") < vout.indexOf("fade=t=out")) shouldBe true
        MontageRenderBuilder.build(request.copy(lyrics = emptyList())).filterGraph.lines().single { it.trimEnd(';').endsWith("[vout]") } shouldNotContain "drawtext"
    }

    test("sans transcription exploitable : aucun mot") {
        Lyrics.clean(Captions.parse(listOf("", "pas du json"), Duration.ZERO), settings).shouldBeEmpty()
    }
})
