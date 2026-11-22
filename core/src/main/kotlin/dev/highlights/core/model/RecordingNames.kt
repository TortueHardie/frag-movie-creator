package dev.highlights.core.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Logiciel qui a nommé la capture, reconnu à la forme de son nom (voir [RecordingNames.parse]). */
enum class Recorder(
    /** Ce logiciel ne garde que des clips (quelques dizaines de secondes enregistrées d'une touche), pas des parties. */
    val clips: Boolean,
    /**
     * L'heure du nom est celle de l'enregistrement du clip, donc de sa FIN : un tampon de rejeu écrit les dernières
     * secondes jouées au moment où l'on appuie sur la touche. Le début de la capture est alors cette heure moins sa durée.
     */
    val nameMarksEnd: Boolean,
) {
    OUTPLAYED(clips = false, nameMarksEnd = false),
    OBS(clips = false, nameMarksEnd = false),
    SHADOWPLAY(clips = false, nameMarksEnd = false),
    /** ShadowPlay / NVIDIA App, « Instant Replay » : « VALORANT 2026.11.17 - 21.08.40.02.DVR.mp4 ». */
    SHADOWPLAY_REPLAY(clips = true, nameMarksEnd = true),
    /**
     * FTHRClips : « VALORANT_clip_from_22Nov2026_21-05-33.mp4 » (`datetime.now().strftime('%d%b%Y_%H-%M-%S')` à l'appui
     * sur la touche, `FTHR_UI/main.py`), « _2 », « _3 » si le nom existe déjà.
     */
    FTHR(clips = true, nameMarksEnd = true),
    /** Medal : « MedalTVVALORANT20261122210533.mp4 » (forme relevée sur des clips publiés, pas dans une documentation). */
    MEDAL(clips = true, nameMarksEnd = true),
}

/** Heure écrite dans un nom de capture ([at], heure locale convertie) et le logiciel qui l'a écrite. */
data class NamedTime(val at: Instant, val recorder: Recorder) {
    /** Début de la capture de durée [duration] : l'heure du nom, ou cette heure moins la durée si elle en marque la fin. */
    fun start(duration: kotlin.time.Duration): Instant =
        if (recorder.nameMarksEnd) at.minusMillis(duration.inWholeMilliseconds) else at
}

/** Lecture des noms de fichiers datés par les enregistreurs de parties et de clips. */
object RecordingNames {
    /** Outplayed : « WARDOGS_09-22-2026_22-55-31-983 », « REMATCH 09-27-2026 0-47-38-983_1 » (mois-jour-année). */
    private val OUTPLAYED = Regex("""(?<![\d-])(\d{1,2})-(\d{1,2})-(\d{4})[ _](\d{1,2})-(\d{1,2})-(\d{1,2})""")

    /** OBS : « 2026-11-17 21-08-40 » (année-mois-jour). */
    private val OBS = Regex("""(?<![\d-])(\d{4})-(\d{2})-(\d{2})[ _](\d{2})-(\d{2})-(\d{2})""")

    /** ShadowPlay : « VALORANT 2026.11.17 - 21.08.40.02 », « .DVR » pour un Instant Replay. */
    private val SHADOWPLAY = Regex("""(\d{4})\.(\d{2})\.(\d{2}) - (\d{2})\.(\d{2})\.(\d{2})""")
    private val DVR = Regex("""\.DVR(\.|$)""", RegexOption.IGNORE_CASE)

    /** FTHRClips : « _clip_from_22Nov2026_21-05-33 ». */
    private val FTHR = Regex("""_clip_from_(\d{1,2})(\p{L}{3,5}\.?)(\d{4})_(\d{1,2})-(\d{2})-(\d{2})""", RegexOption.IGNORE_CASE)

    /** Medal : « MedalTV » + jeu + « yyyyMMddHHmmss ». */
    private val MEDAL = Regex("""^MedalTV.*?(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?!\d)""", RegexOption.IGNORE_CASE)

    /**
     * Mois abrégés de `%b` : en anglais (Python garde la locale « C » pour les dates tant que l'application ne la change
     * pas), et en français par prudence, sans accent ni point.
     */
    private val MONTHS: Map<String, Int> = buildMap {
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").forEachIndexed { i, m -> put(m, i + 1) }
        listOf("janv", "fevr", "mars", "avr", "mai", "juin", "juil", "aout", "sept", "oct", "nov", "dec").forEachIndexed { i, m -> putIfAbsent(m, i + 1) }
        put("sept", 9)
    }

    private fun month(text: String): Int? {
        val plain = java.text.Normalizer.normalize(text.lowercase().trimEnd('.'), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return MONTHS[plain]
    }

    /**
     * Heure d'enregistrement écrite dans le nom par l'enregistreur (heure locale de [zone]) et l'enregistreur ; null si
     * le nom n'en porte pas, ou si la date est impossible. Les formes de clips passent avant celles de parties : un nom
     * FTHRClips peut contenir un jeu daté.
     */
    fun parse(fileName: String, zone: ZoneId = ZoneId.systemDefault()): NamedTime? {
        fun at(y: Int, mo: Int?, d: Int, h: Int, mi: Int, s: Int, recorder: Recorder) = mo?.let {
            runCatching { NamedTime(LocalDateTime.of(y, it, d, h, mi, s).atZone(zone).toInstant(), recorder) }.getOrNull()
        }
        FTHR.find(fileName)?.destructured?.let { (d, mo, y, h, mi, s) ->
            at(y.toInt(), month(mo), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), Recorder.FTHR)?.let { return it }
        }
        MEDAL.find(fileName)?.destructured?.let { (y, mo, d, h, mi, s) ->
            at(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), Recorder.MEDAL)?.let { return it }
        }
        OBS.find(fileName)?.destructured?.let { (y, mo, d, h, mi, s) ->
            at(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), Recorder.OBS)?.let { return it }
        }
        SHADOWPLAY.find(fileName)?.destructured?.let { (y, mo, d, h, mi, s) ->
            val recorder = if (DVR.containsMatchIn(fileName)) Recorder.SHADOWPLAY_REPLAY else Recorder.SHADOWPLAY
            at(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), recorder)?.let { return it }
        }
        OUTPLAYED.find(fileName)?.destructured?.let { (mo, d, y, h, mi, s) ->
            at(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), Recorder.OUTPLAYED)?.let { return it }
        }
        return null
    }
}
