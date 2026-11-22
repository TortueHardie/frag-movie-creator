package dev.highlights.export

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegResult
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioStream
import dev.highlights.core.model.CaptionEngine
import dev.highlights.core.model.CaptionSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.TimeRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CaptionTranscriberTest : FunSpec({
    /** FFmpeg factice : compte les transcriptions lancées et écrit un sous-titre dans le fichier demandé. */
    class FakeFfmpeg : FfmpegService {
        var runs = 0
        override suspend fun probe(file: Path): MediaInfo = error("non utilisé")
        override suspend fun run(command: FfmpegCommand, stdout: StdoutHandler, onStderrLine: ((String) -> Unit)?): FfmpegResult {
            // Extraction de l'audio pour whisper-cli : rien à transcrire ici.
            if ("-af" !in command.args) return FfmpegResult(0, kotlin.time.Duration.ZERO, emptyList())
            runs++
            val af = command.args[command.args.indexOf("-af") + 1]
            Path(af.substringAfter("destination='").substringBefore("'").replace("\\:", ":")).writeText("""{"start":500,"end":1500,"text":"un ennemi"}""" + "\n")
            return FfmpegResult(0, kotlin.time.Duration.ZERO, emptyList())
        }
        override suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult = error("non utilisé")
    }

    fun media(vararg titles: String) = MediaInfo(
        path = Path("partie.mp4"),
        sizeBytes = 1,
        duration = 10.minutes,
        audio = titles.mapIndexed { i, t -> AudioStream(i + 1, i, "aac", 2, 48000, t) },
    )
    val range = TimeRange(60.seconds, 70.seconds)
    val model = Path("modele.bin")

    test("capture à piste unique (son du jeu et voix mêlés) : pas de sous-titres, whisper n'est pas lancé") {
        val ffmpeg = FakeFfmpeg()
        val dir = tempdir().toPath()
        CaptionTranscriber(ffmpeg).transcribe(media("ISO Media file"), range, null, CaptionSettings(), model, AudioLayout(), dir, null).shouldBeEmpty()
        ffmpeg.runs shouldBe 0
    }

    test("piste unique transcrite si le profil le force (withoutMic: true)") {
        val ffmpeg = FakeFfmpeg()
        val dir = tempdir().toPath()
        val captions = CaptionTranscriber(ffmpeg).transcribe(media("ISO Media file"), range, null, CaptionSettings(withoutMic = true), model, AudioLayout(), dir, null)
        ffmpeg.runs shouldBe 1
        captions.map { it.text } shouldBe listOf("un ennemi")
    }

    test("piste micro séparée : transcrite") {
        val ffmpeg = FakeFfmpeg()
        val dir = tempdir().toPath()
        CaptionTranscriber(ffmpeg).transcribe(media("Game", "Mic"), range, null, CaptionSettings(), model, AudioLayout(), dir, null).size shouldBe 1
        ffmpeg.runs shouldBe 1
    }

    test("piste unique, réglage automatique : transcrite seulement là où l'analyse a trouvé la voix du joueur") {
        val ffmpeg = FakeFfmpeg()
        val dir = tempdir().toPath()
        val voice = listOf(TimeRange(58.seconds, 61.seconds))
        val transcriber = CaptionTranscriber(ffmpeg)
        // Contexte de 2 s : le sous-titre factice (0,5 s après le début de l'audio lu) tombe dans la voix, à 58,5 s.
        transcriber.transcribe(media("ISO Media file"), range, voice, emptyList(), CaptionSettings(context = 2.seconds), model, AudioLayout(), dir, null)
            .map { it.text } shouldBe listOf("un ennemi")
        // Voix ailleurs dans la capture : pas ici.
        transcriber.transcribe(media("ISO Media file"), range, listOf(TimeRange(5.minutes, 6.minutes)), emptyList(), CaptionSettings(), model, AudioLayout(), dir, null)
            .shouldBeEmpty()
        // Désactivé : jamais, même avec de la voix.
        transcriber.transcribe(media("ISO Media file"), range, voice, emptyList(), CaptionSettings(withoutMic = false), model, AudioLayout(), dir, null)
            .shouldBeEmpty()
        ffmpeg.runs shouldBe 1
    }

    test("répliques du jeu écartées sur le son complet seulement, pas sur le micro") {
        val settings = CaptionSettings(withoutMic = true, ignore = listOf("ennemi"))
        val dir = tempdir().toPath()
        CaptionTranscriber(FakeFfmpeg()).transcribe(media("ISO Media file"), range, null, settings, model, AudioLayout(), dir, null).shouldBeEmpty()
        CaptionTranscriber(FakeFfmpeg()).transcribe(media("Game", "Mic"), range, null, settings, model, AudioLayout(), dir, null).size shouldBe 1
    }

    test("whisper-cli en échec (programme illisible) : le filtre de FFmpeg prend le relais, mots corrigés") {
        val ffmpeg = FakeFfmpeg()
        val dir = tempdir().toPath()
        val cli = dir.resolve("whisper-cli.exe").also { it.writeText("pas un programme") }
        val settings = CaptionSettings(cli = cli.toString(), spelling = mapOf("Cypher" to listOf("un ennemi")))
        val captions = CaptionTranscriber(ffmpeg).transcribe(media("Game", "Mic"), range, null, settings, model, AudioLayout(), dir, null)
        ffmpeg.runs shouldBe 1
        captions.map { it.text } shouldBe listOf("Cypher")
    }

    test("moteur ffmpeg imposé : whisper-cli n'est pas lancé même s'il est là") {
        val ffmpeg = FakeFfmpeg()
        val dir = tempdir().toPath()
        val cli = dir.resolve("whisper-cli.exe").also { it.writeText("pas un programme") }
        val settings = CaptionSettings(cli = cli.toString(), engine = CaptionEngine.FFMPEG)
        CaptionTranscriber(ffmpeg).transcribe(media("Game", "Mic"), range, null, settings, model, AudioLayout(), dir, null).size shouldBe 1
        ffmpeg.runs shouldBe 1
    }
})
