package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.time.Duration

@Serializable
data class MediaInfo(
    val path: SerialPath,
    val sizeBytes: Long,
    val duration: SerialDuration,
    val creationTime: SerialInstant? = null,
    val container: String? = null,
    val video: VideoStream? = null,
    val audio: List<AudioStream> = emptyList(),
) {
    val bounds: TimeRange get() = TimeRange(Duration.ZERO, duration)

    /**
     * Moment de l'enregistrement (début de la capture). Un clip dont le nom porte l'heure de sa sauvegarde (FTHRClips,
     * Medal, Instant Replay : [Recorder.nameMarksEnd]) commence à cette heure moins sa durée ; ce nom passe alors avant
     * les métadonnées, qui disent l'heure d'écriture du fichier. Sinon : date de création inscrite dans la capture, puis
     * heure du nom (Outplayed, OBS, ShadowPlay : [nameTime]), puis date de modification du fichier.
     */
    val recordedAt: Instant?
        get() {
            val named = named
            if (named != null && named.recorder.nameMarksEnd) return named.start(duration)
            return creationTime ?: named?.at ?: runCatching { path.getLastModifiedTime().toInstant() }.getOrNull()
        }

    /**
     * Un clip (quelques dizaines de secondes gardées d'une touche) plutôt qu'une partie entière : nom d'un logiciel de
     * clips ([Recorder.clips]), ou capture plus courte que [CLIP_MAX]. Les clips d'une soirée se regroupent en séances
     * de jeu au lieu de compter chacun comme une partie.
     */
    val isClip: Boolean get() = isClip(path.fileName?.toString() ?: "", duration)

    private val named: NamedTime? get() = RecordingNames.parse(path.fileName?.toString() ?: "")

    /**
     * L'heure de [recordedAt] vient-elle de l'enregistrement (métadonnées ou nom daté par l'enregistreur) ? Sinon c'est
     * la date du fichier, celle d'une copie ou d'un téléchargement : deux parties copiées à une minute d'écart
     * sembleraient jouées en même temps.
     */
    val reliableTime: Boolean get() = creationTime != null || named != null

    companion object {
        /**
         * Ordre des parties jouées : par date d'enregistrement, puis par nom (captures numérotées). Un montage de
         * plusieurs captures les enchaîne dans cet ordre, quel que soit celui dans lequel elles ont été choisies.
         */
        val RECORDING_ORDER: Comparator<MediaInfo> =
            compareBy<MediaInfo, Instant?>(nullsLast()) { it.recordedAt }.thenBy { it.path.toString() }

        /**
         * Capture plus courte : un clip, pas une partie (30 à 45 min en VALORANT). Outplayed garde ses clips de 1 min 30,
         * les tampons de rejeu (FTHRClips, ShadowPlay, Medal) de 30 s à 2 min.
         */
        val CLIP_MAX: Duration = kotlin.time.Duration.parse("5m")

        /** Voir [isClip] : d'après le nom de fichier [fileName] et la durée. */
        fun isClip(fileName: String, duration: Duration): Boolean =
            RecordingNames.parse(fileName)?.recorder?.clips == true || duration < CLIP_MAX

        /**
         * Heure écrite dans le nom par l'enregistreur (heure locale de [zone]) ; null si le nom n'en porte pas, ou si la
         * date est impossible. Pour un clip, c'est souvent l'heure de sa fin (voir [RecordingNames.parse]).
         */
        fun nameTime(fileName: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Instant? =
            RecordingNames.parse(fileName, zone)?.at
    }
}

@Serializable
data class VideoStream(
    val index: Int,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val pixelFormat: String? = null,
)

@Serializable
data class AudioStream(
    /** Index absolu dans le conteneur (0:N). */
    val index: Int,
    /** Position parmi les pistes audio uniquement (0:a:N). */
    val audioIndex: Int,
    val codec: String,
    val channels: Int,
    val sampleRate: Int,
    val title: String? = null,
) {
    val label: String get() = "a:$audioIndex" + (title?.let { " \"$it\"" } ?: "")
}
