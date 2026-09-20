package dev.highlights.pipeline

import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.config.LoadedConfig
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.Hwaccel
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.aspectLabel
import dev.highlights.core.profile.ProfileRepository
import dev.highlights.core.serialization.toTimecode
import dev.highlights.ffmpeg.FfmpegEncoderSelector
import dev.highlights.ffmpeg.ProcessFfmpegService
import java.nio.file.Path

/** Une rubrique du diagnostic : un titre et des lignes déjà rédigées. */
data class DiagnosticSection(val title: String, val lines: List<String>)

/**
 * État de la machine et de la configuration : FFmpeg, encodeurs réellement utilisables, décodage matériel, prérequis
 * des détecteurs (OCR de Windows, base Outplayed), et pour une capture donnée ses pistes, son format d'écran et le
 * profil retenu. Sert à répondre en une commande à « pourquoi ça ne marche pas chez moi ».
 */
class Diagnostics(
    private val config: LoadedConfig,
    private val ffmpeg: ProcessFfmpegService,
    private val profiles: ProfileRepository,
    private val detectors: DetectorRegistry = DetectorRegistry.fromServiceLoader(),
) {
    suspend fun run(file: Path? = null): List<DiagnosticSection> = buildList {
        add(environment())
        add(ffmpegSection())
        add(encoders())
        add(detectorsSection())
        add(profilesSection())
        file?.let { add(capture(it)) }
    }

    private fun environment() = DiagnosticSection(
        "Machine",
        listOf(
            "système : ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            "Java : ${System.getProperty("java.version")}",
            "configuration : ${config.source ?: "valeurs par défaut"}",
            "sorties : ${config.outputDir}",
            "dossier de travail : ${config.workDir}",
        ),
    )

    private suspend fun ffmpegSection(): DiagnosticSection {
        val hwaccel = Hwaccel.resolve(config.app.ffmpeg.hwaccelDecode)
        return DiagnosticSection(
            "FFmpeg",
            listOf(
                "ffmpeg : ${ffmpeg.ffmpegPath} (${version()})",
                "ffprobe : ${ffmpeg.ffprobePath}",
                "décodage matériel : ${hwaccel ?: "logiciel"}" +
                    if (hwaccel == Hwaccel.AUTO) " (FFmpeg choisit, repli logiciel automatique)" else "",
            ),
        )
    }

    private suspend fun version(): String {
        var line = "version inconnue"
        runCatching {
            ffmpeg.run(FfmpegCommand(listOf("-version"), "version"), StdoutHandler.Lines { if (it.startsWith("ffmpeg version")) line = it.trim() })
        }
        return line.removePrefix("ffmpeg version ").substringBefore(" Copyright")
    }

    private suspend fun encoders(): DiagnosticSection {
        val checks = FfmpegEncoderSelector(ffmpeg, config.app.encoder).checkAll()
        val lines = checks.map { "${if (it.usable) "OK" else "KO"}  ${it.name}${it.reason?.let { r -> " : $r" } ?: ""}" }
        val retained = checks.firstOrNull { it.usable }?.name
        return DiagnosticSection(
            "Encodeurs",
            lines + if (retained != null) "→ le montage sera encodé par $retained" else "→ aucun encodeur utilisable : l'export échouera",
        )
    }

    private fun detectorsSection(): DiagnosticSection {
        val lines = detectors.types.sorted().map { type ->
            val availability = detectors.availability(type)
            when {
                availability == null -> "OK  $type"
                else -> "${if (availability.usable) "OK" else "--"}  $type : ${availability.detail}"
            }
        }
        return DiagnosticSection("Détecteurs", lines.ifEmpty { listOf("aucun détecteur chargé") })
    }

    private fun profilesSection() = DiagnosticSection(
        "Profils",
        profiles.all().map { p ->
            val match = p.match.pathContains.joinToString().ifEmpty { "aucun motif (profil de repli)" }
            "${p.id} : ${p.displayName} — chemin contenant $match"
        },
    )

    private suspend fun capture(file: Path): DiagnosticSection {
        val media = ffmpeg.probe(file)
        val profile = profiles.resolve(file)
        val tracks = AudioTracks.of(media.audio, profile.audio)
        val lines = mutableListOf<String>()
        lines += "fichier : $file"
        lines += "durée : ${media.duration.toTimecode()}"
        media.video?.let { lines += "image : ${it.width}x${it.height} (${aspectLabel(it.width, it.height)}), ${"%.2f".format(it.fps)} img/s, ${it.codec}" }
            ?: run { lines += "image : aucune piste vidéo (fichier inutilisable)" }
        lines += "son : ${tracks.describe()}"
        if (tracks[AudioRole.MIC] == null) lines += "  micro absent : voix et rires ne seront pas détectés, leur poids est redistribué"
        lines += "profil retenu : ${profile.id} (${profile.displayName})"
        lines += "montage : ${profile.edit.formats.joinToString { it.label }}, son ${profile.edit.audio.name.lowercase()} " +
            "→ pistes ${profile.edit.audioIndices(tracks).joinToString { "a:$it" }.ifEmpty { "aucune" }}"
        profile.detectors.filter { it.enabled }.forEach { cfg ->
            val availability = detectors.availability(cfg.type)
            val state = when {
                cfg.type !in detectors.types -> "type inconnu"
                availability == null || availability.usable -> "prêt"
                else -> "inactif : ${availability.detail}"
            }
            lines += "  ${cfg.id} (${cfg.type}, poids ${cfg.weight}) : $state"
        }
        return DiagnosticSection("Capture", lines)
    }
}

/** Rendu texte, une rubrique par bloc. */
fun List<DiagnosticSection>.render(): String = joinToString("\n\n") { section ->
    (listOf("${section.title} :") + section.lines.map { "  $it" }).joinToString("\n")
}
