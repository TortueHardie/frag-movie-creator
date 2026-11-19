package dev.highlights.montage

import dev.highlights.core.model.AudioLayout
import dev.highlights.core.model.AudioRole
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditSettings
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.ReactionMontage
import dev.highlights.core.model.TimeRange
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.editing.story.Caption
import dev.highlights.editing.story.Captions
import dev.highlights.export.CaptionTranscriber
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * Rendu du montage des réactions ([ReactionMontage]) : micro mixé par-dessus le jeu et sous-titres de ce qui est dit.
 * Branché dans [MontageRenderBuilder] et [KillMontageExporter] par un appel chacun ; sans montage des réactions, rien.
 */
object ReactionRender {
    /**
     * Son d'un plan, micro devant : piste du jeu et piste micro mixées, le micro à [ReactionMontage.micGain]. La piste
     * « mix » d'Outplayed mêle déjà les deux au même niveau, le micro n'y passe pas devant une explosion. Null (son
     * habituel) hors montage des réactions, ou sans pistes jeu et micro séparées.
     */
    fun source(input: Int, media: MediaInfo, layout: AudioLayout, settings: ReactionMontage, graph: MutableList<String>): String? {
        if (!settings.enabled) return null
        val tracks = AudioTracks.of(media.audio, layout)
        val mic = tracks.indexOf(AudioRole.MIC) ?: return null
        val game = tracks.indexOf(AudioRole.GAME) ?: return null
        val label = "[rv$input]"
        graph += "[$input:a:$game][$input:a:$mic]amix=inputs=2:weights='1 ${String.format(Locale.ROOT, "%.3f", settings.micGain)}':normalize=0:duration=longest$label"
        return label
    }

    /**
     * Sous-titres d'un plan, dans le temps du plan : chaque phrase transcrite (temps de la source) ramenée à la sortie
     * du plan ([MontageClip.toOutput] + l'avance de la coupe [lead]) et coupée à sa longueur.
     */
    fun captionFilters(
        captions: List<Caption>?, clip: MontageClip, lead: Duration, length: Duration, edit: EditSettings, format: OutputFormat, w: Int, h: Int,
    ): List<String> {
        if (captions.isNullOrEmpty()) return emptyList()
        val settings = edit.story.captions
        val y = if (format == OutputFormat.VERTICAL) settings.verticalY else settings.y
        return captions.flatMap { c ->
            val from = (clip.toOutput(maxOf(c.range.start, clip.start)) + lead).coerceAtLeast(Duration.ZERO)
            val to = (clip.toOutput(minOf(c.range.end, clip.end)) + lead).coerceAtMost(length)
            if (to <= from) emptyList() else Captions.drawText(c.copy(range = TimeRange(from, to)), settings, w, h, y, edit.safeArea)
        }
    }

    /**
     * Transcription de chaque plan (temps de la source), sur la piste micro, avec le modèle du profil
     * (`edit.story.captions.model`). Les éclats de voix de [reactions] s'affichent comme des cris. Vide hors montage des réactions, sans modèle, ou réglage `captions` coupé : le
     * montage sort alors sans sous-titres, jamais en échec.
     */
    suspend fun transcribe(
        transcriber: CaptionTranscriber, plan: MontagePlan, reactions: List<SessionReactions>, edit: EditSettings, layout: AudioLayout, workDir: Path, cacheDir: Path?,
        progress: ProgressReporter,
    ): List<List<Caption>> {
        val r = plan.settings.reactionMontage
        val captions = edit.story.captions
        val model = captions.model?.let { Path.of(it) }
        if (!r.enabled || !r.captions || !captions.enabled) return emptyList()
        if (model == null || !model.exists()) {
            log.warn { "Modèle de transcription introuvable (${captions.model}) : montage des réactions sans sous-titres" }
            return emptyList()
        }
        return plan.clips.mapIndexed { i, clip ->
            progress.update(i.toDouble() / plan.clips.size)
            val g = clip.group
            // Phrase criée (elle recouvre un éclat ou un cri, pas un rire) : en couleur, plus grosse.
            val shouts = reactions.filter { it.session.media.path == g.media.path }.flatMap { it.reactions }
                .filter { it.kind != ReactionKind.LAUGH }.map { it.range }
            transcriber.transcribe(g.media, TimeRange(clip.start, clip.end), g.voiceSegments, shouts, captions, model, layout, workDir.resolve("captions"), cacheDir)
                .let { list -> list.filter { it.range.end > clip.start && it.range.start < clip.end } }
        }.also { progress.complete() }
    }
}
