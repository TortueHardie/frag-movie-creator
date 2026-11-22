package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import kotlinx.serialization.SerialName
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
     * Moment de l'enregistrement : date de création inscrite dans la capture, sinon celle du nom de fichier (Outplayed,
     * OBS, ShadowPlay : [nameTime]), sinon date de modification du fichier.
     */
    val recordedAt: Instant?
        get() = creationTime ?: nameTime(path.fileName?.toString() ?: "") ?: runCatching { path.getLastModifiedTime().toInstant() }.getOrNull()

    /**
     * L'heure de [recordedAt] vient-elle de l'enregistrement (métadonnées ou nom daté par l'enregistreur) ? Sinon c'est
     * la date du fichier, celle d'une copie ou d'un téléchargement : deux parties copiées à une minute d'écart
     * sembleraient jouées en même temps.
     */
    val reliableTime: Boolean get() = creationTime != null || nameTime(path.fileName?.toString() ?: "") != null

    companion object {
        /**
         * Ordre des parties jouées : par date d'enregistrement, puis par nom (captures numérotées). Un montage de
         * plusieurs captures les enchaîne dans cet ordre, quel que soit celui dans lequel elles ont été choisies.
         */
        val RECORDING_ORDER: Comparator<MediaInfo> =
            compareBy<MediaInfo, Instant?>(nullsLast()) { it.recordedAt }.thenBy { it.path.toString() }

        /** Outplayed : « WARDOGS_09-22-2026_22-55-31-983 », « REMATCH 09-27-2026 0-47-38-983_1 » (mois-jour-année). */
        private val OUTPLAYED = Regex("""(?<![\d-])(\d{1,2})-(\d{1,2})-(\d{4})[ _](\d{1,2})-(\d{1,2})-(\d{1,2})""")

        /** OBS : « 2026-11-17 21-08-40 » (année-mois-jour). */
        private val OBS = Regex("""(?<![\d-])(\d{4})-(\d{2})-(\d{2})[ _](\d{2})-(\d{2})-(\d{2})""")

        /** ShadowPlay : « VALORANT 2026.11.17 - 21.08.40.02 ». */
        private val SHADOWPLAY = Regex("""(\d{4})\.(\d{2})\.(\d{2}) - (\d{2})\.(\d{2})\.(\d{2})""")

        /**
         * Heure d'enregistrement écrite dans le nom par l'enregistreur (heure locale de [zone]) ; null si le nom n'en
         * porte pas, ou si la date est impossible.
         */
        fun nameTime(fileName: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Instant? {
            fun at(y: String, mo: String, d: String, h: String, mi: String, s: String) = runCatching {
                java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).atZone(zone).toInstant()
            }.getOrNull()
            OBS.find(fileName)?.destructured?.let { (y, mo, d, h, mi, s) -> at(y, mo, d, h, mi, s)?.let { return it } }
            SHADOWPLAY.find(fileName)?.destructured?.let { (y, mo, d, h, mi, s) -> at(y, mo, d, h, mi, s)?.let { return it } }
            OUTPLAYED.find(fileName)?.destructured?.let { (mo, d, y, h, mi, s) -> at(y, mo, d, h, mi, s)?.let { return it } }
            return null
        }
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
    /** Nature de la piste déclarée par l'enregistreur à côté du fichier (FTHRClips : `.fthr-audio.json`), null s'il ne dit rien. */
    val source: AudioSourceKind? = null,
    /** Ce que l'écoute de la piste a montré ([TrackContent]), null tant qu'elle n'a pas été écoutée. */
    val content: TrackContent? = null,
) {
    val label: String get() = "a:$audioIndex" + (title?.let { " \"$it\"" } ?: "")
}

/** Nature d'une piste telle que l'enregistreur la déclare (vocabulaire de FTHRClips, le seul qui la déclare aujourd'hui). */
@Serializable
enum class AudioSourceKind {
    /** Tout ce que le PC joue (« Default Mix » de FTHRClips) : jeu, chat vocal, musique ; jamais le micro. */
    @SerialName("system") SYSTEM,
    @SerialName("microphone") MICROPHONE,
    /** Une seule application (le jeu, Discord…), déjà comprise dans la piste [SYSTEM] quand elle existe. */
    @SerialName("application") APPLICATION,
}

/**
 * Écoute rapide d'une piste : quelques passages répartis dans la capture, énergie par tranche de 20 ms, comparée à celle
 * des autres pistes au même instant (voir `TrackListener` du pipeline). Sert à décider quand les titres ne suffisent pas.
 */
@Serializable
data class TrackContent(
    /** Part des tranches où la piste sonne (au-dessus du seuil de silence) : ~1 pour le jeu, bien moins pour une voix. */
    val active: Double,
    /** Pistes (0:a:N) que celle-ci contient déjà : son énergie s'explique par leur somme (une piste « mix »). */
    val contains: List<Int> = emptyList(),
    /** Piste (0:a:N) dont celle-ci est une copie (même son à l'énergie près), null sinon. */
    val duplicateOf: Int? = null,
) {
    /** Piste muette sur tous les passages écoutés : un micro débranché, un appareil où rien ne passe. */
    val silent: Boolean get() = active < SILENT_SHARE

    companion object {
        /** En dessous, la piste est tenue pour muette (moins d'une tranche sur 200 sonne). */
        const val SILENT_SHARE = 0.005
    }
}
