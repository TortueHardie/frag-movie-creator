package dev.highlights.montage

import dev.highlights.core.HighlightsException
import dev.highlights.core.InputException
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.MusicPace
import dev.highlights.core.progress.ProgressReporter
import dev.highlights.core.serialization.SerialInstant
import dev.highlights.core.serialization.SerialPath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList
import kotlin.time.Duration.Companion.microseconds

private val log = KotlinLogging.logger {}

/**
 * Dossier de musiques analysées une fois pour toutes : l'analyse d'un morceau (décodage complet, tempo, sections) prend
 * quelques secondes, et une bibliothèque se réessaie à chaque montage. Chaque analyse est gardée dans [cacheDir], avec la
 * taille et la date du fichier : une musique remplacée est réanalysée, comme après un changement de [MusicAnalyzer.VERSION].
 * Les échecs (morceau trop court, sans rythme marqué) sont gardés aussi : on ne redécode pas pour rien à chaque montage.
 */
class MusicLibrary(private val cacheDir: Path) {
    /**
     * Analyse de chaque musique de [dir] (sous-dossiers compris), reprise du cache quand le fichier n'a pas changé. Les
     * morceaux inexploitables sont écartés avec un avertissement.
     */
    suspend fun load(ffmpeg: FfmpegService, dir: Path, progress: ProgressReporter = ProgressReporter.NONE): List<MusicAnalysis> {
        val files = files(dir)
        if (files.isEmpty()) throw InputException("Aucune musique dans $dir (attendu : ${AUDIO_EXTENSIONS.joinToString()})")
        val analyses = files.mapIndexedNotNull { i, file ->
            progress.update(i.toDouble() / files.size, file.fileName.toString())
            analysis(ffmpeg, file)
        }
        progress.complete()
        if (analyses.isEmpty()) throw HighlightsException("Aucune musique exploitable dans $dir : aucun tempo détecté")
        log.info { "Bibliothèque $dir : ${analyses.size} musique(s) exploitable(s) sur ${files.size}" }
        return analyses
    }

    /** Analyse de [file], du cache si possible ; null si la musique est inexploitable. */
    suspend fun analysis(ffmpeg: FfmpegService, file: Path): MusicAnalysis? {
        val stamp = stamp(file) ?: return null
        cached(file, stamp)?.let { stored ->
            stored.error?.let { log.debug { "${file.fileName} écartée (déjà vu) : $it" } }
            return stored.toAnalysis(file)
        }
        val stored = try {
            StoredMusic.of(MusicAnalyzer.analyze(ffmpeg, file), stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HighlightsException) {
            log.warn { "Musique écartée, ${file.fileName} : ${e.message?.lineSequence()?.firstOrNull()}" }
            StoredMusic(MusicAnalyzer.VERSION, stamp.first, stamp.second, error = e.message?.lineSequence()?.firstOrNull() ?: "analyse impossible")
        }
        store(file, stored)
        return stored.toAnalysis(file)
    }

    private fun cached(file: Path, stamp: Pair<Long, Long>): StoredMusic? {
        val entry = entryOf(file)
        if (!entry.exists()) return null
        return try {
            json.decodeFromString(StoredMusic.serializer(), entry.readText())
                .takeIf { it.version == MusicAnalyzer.VERSION && it.sizeBytes == stamp.first && it.modifiedAtMillis == stamp.second }
        } catch (e: Exception) {
            log.warn { "Analyse gardée illisible (${entry.fileName}), musique réanalysée : ${e.message}" }
            null
        }
    }

    private fun store(file: Path, stored: StoredMusic) {
        runCatching {
            cacheDir.createDirectories()
            val entry = entryOf(file)
            val tmp = entry.resolveSibling(entry.fileName.toString() + ".tmp")
            tmp.writeText(json.encodeToString(StoredMusic.serializer(), stored))
            tmp.moveTo(entry, overwrite = true)
        }.onFailure { log.warn { "Analyse de ${file.fileName} non gardée ($cacheDir) : ${it.message}" } }
    }

    /** Une entrée par chemin absolu : deux musiques du même nom dans deux dossiers ne se confondent pas. */
    private fun entryOf(file: Path): Path {
        val key = file.toAbsolutePath().normalize().toString().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
        return cacheDir.resolve("$digest.json")
    }

    companion object {
        /** Formats audio proposés dans l'application et lus dans une bibliothèque. */
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus")

        private val json = Json { ignoreUnknownKeys = true }

        /** Musiques de [dir] et de ses sous-dossiers, dans l'ordre alphabétique des chemins. */
        fun files(dir: Path): List<Path> {
            if (!dir.isDirectory()) throw InputException("Dossier de musiques introuvable : $dir")
            return Files.walk(dir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension.lowercase() in AUDIO_EXTENSIONS }.toList()
            }.sortedBy { it.toString().lowercase() }
        }

        private fun stamp(file: Path): Pair<Long, Long>? =
            runCatching { file.fileSize() to file.getLastModifiedTime().toMillis() }.getOrNull()
    }
}

/**
 * Analyse gardée sur disque. Les temps sont en microsecondes : un kill doit tomber sur son temps à l'image près, un
 * arrondi à la milliseconde ferait dériver la grille sur quelques minutes de musique. [error] : musique inexploitable.
 */
@Serializable
internal data class StoredMusic(
    val version: Int,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val error: String? = null,
    val durationMicros: Long = 0,
    val bpm: Double = 0.0,
    val beatsMicros: LongArray = LongArray(0),
    val downbeatPhase: Int = 0,
    val beatEnergy: DoubleArray = DoubleArray(0),
    val beatAccent: DoubleArray = DoubleArray(0),
    val halfAccent: DoubleArray = DoubleArray(0),
    val sections: List<MusicSection> = emptyList(),
    val dropBeat: Int = 0,
) {
    fun toAnalysis(file: Path): MusicAnalysis? = if (error != null) null else MusicAnalysis(
        file = file,
        duration = durationMicros.microseconds,
        bpm = bpm,
        beats = beatsMicros.map { it.microseconds },
        downbeatPhase = downbeatPhase,
        beatEnergy = beatEnergy,
        beatAccent = beatAccent,
        sections = sections,
        dropBeat = dropBeat,
        halfAccent = halfAccent,
    )

    companion object {
        fun of(a: MusicAnalysis, stamp: Pair<Long, Long>) = StoredMusic(
            version = MusicAnalyzer.VERSION,
            sizeBytes = stamp.first,
            modifiedAtMillis = stamp.second,
            durationMicros = a.duration.inWholeMicroseconds,
            bpm = a.bpm,
            beatsMicros = LongArray(a.beats.size) { a.beats[it].inWholeMicroseconds },
            downbeatPhase = a.downbeatPhase,
            beatEnergy = a.beatEnergy,
            beatAccent = a.beatAccent,
            halfAccent = a.halfAccent,
            sections = a.sections,
            dropBeat = a.dropBeat,
        )
    }
}

/**
 * Musique d'une bibliothèque essayée pour un montage. [score] : note du plan ([MontageScorer]) ; [groups] : groupes de
 * kills qu'il montre ; [recency] : retenue pour avoir servi à un montage récent ; [pace] : avance pour des plans courts
 * ([MontageSettings.musicPace]) ; [value] : la note rapportée aux kills gardés, plus l'avance, moins la retenue, ce qui
 * départage les musiques. [error] : aucun plan possible sur cette musique.
 */
data class MusicCandidate(
    val music: MusicAnalysis,
    val plan: MontagePlan?,
    val score: Double,
    val groups: Int,
    val value: Double,
    val error: String? = null,
    val recency: Double = 0.0,
    val pace: Double = 0.0,
)

/**
 * Musiques des derniers montages, la plus récente d'abord, partagées par l'application et la ligne de commande. Sans
 * elles, le choix dans une bibliothèque est figé : la même partie reprend toujours la même musique, et des montages de
 * quelques kills tombent presque tous sur celle qui note le mieux les montages courts.
 */
class MusicHistory(val file: Path) {
    private val lock = Any()

    /** Musiques utilisées, sans doublon, la plus récente d'abord. */
    fun recent(): List<Path> = synchronized(lock) { load().map { it.music }.distinct() }

    /** Retient [music] comme musique du dernier montage. */
    fun record(music: Path) = synchronized(lock) {
        val key = music.toAbsolutePath().normalize()
        val next = (listOf(MusicUse(key, Instant.now())) + load().filter { it.music != key }).take(KEEP)
        runCatching {
            file.parent?.createDirectories()
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            tmp.writeText(historyJson.encodeToString(StoredHistory.serializer(), StoredHistory(next)))
            tmp.moveTo(file, overwrite = true)
        }.onFailure { log.warn { "Musiques récentes non enregistrées ($file) : ${it.message}" } }
    }

    private fun load(): List<MusicUse> {
        if (!file.exists()) return emptyList()
        return try {
            historyJson.decodeFromString(StoredHistory.serializer(), file.readText()).uses
        } catch (e: Exception) {
            log.warn { "Musiques récentes illisibles ($file), ignorées : ${e.message}" }
            emptyList()
        }
    }

    private companion object {
        /** Assez pour couvrir la retenue ([MusicChoice.RECENT_DEPTH]) avec de la marge. */
        const val KEEP = 20
        val historyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

@Serializable
private data class MusicUse(val music: SerialPath, val usedAt: SerialInstant)

@Serializable
private data class StoredHistory(val uses: List<MusicUse> = emptyList())

/**
 * Choix d'une musique dans une bibliothèque : le montage est planifié sur chacune, et la meilleure note l'emporte.
 * Planifier ne coûte presque rien face au rendu (les kills sont inspectés une fois pour toutes les musiques), et la note
 * mesure déjà ce qui fait qu'une musique colle : kills sur des temps accentués, durée visée tenue, drop à portée, pas de
 * trou. Il lui manque un seul critère : une musique trop courte, ou dont la grille ne laisse pas de place à tous les
 * groupes, en montre moins — la note est donc multipliée par la part des groupes gardés, rapportée à la musique qui en
 * garde le plus.
 *
 * Une musique des [RECENT_DEPTH] derniers montages est retenue de [RECENT_PENALTY] : sans quoi le choix est figé. La
 * retenue est à la mesure des écarts entre les premières musiques (0,002 à 0,03 sur 11 musiques et 6 montages de 9 à
 * 33 kills), bien en dessous de ce que coûte un groupe de kills perdu (0,07 sur 15) : elle fait tourner les musiques qui
 * collent presque aussi bien, jamais au profit d'une qui colle mal. Elle ne décroît pas : divisée par deux à chaque
 * montage, deux musiques en tête (0,962 et 0,957, la 3e à 0,934) alternaient sans jamais laisser passer la troisième.
 */
object MusicChoice {
    /** Retenue d'une musique des derniers montages. */
    const val RECENT_PENALTY = 0.03

    /** Nombre de montages récents dont la musique est retenue. */
    const val RECENT_DEPTH = 3

    /** Retenue d'une musique d'après les musiques des derniers montages ([recent], la plus récente d'abord). */
    fun recency(music: Path, recent: List<Path>): Double {
        val key = music.toAbsolutePath().normalize()
        return if (recent.take(RECENT_DEPTH).any { it.toAbsolutePath().normalize() == key }) RECENT_PENALTY else 0.0
    }

    /**
     * Candidates de la meilleure à la moins bonne ; celles sans plan possible à la fin. [fromStart] : la musique se
     * prend-elle depuis son début (réglage retenu pour chaque musique) ? [recent] : musiques des derniers montages, la
     * plus récente d'abord.
     */
    fun rank(
        groups: List<KillGroup>,
        musics: List<MusicAnalysis>,
        settings: MontageSettings,
        recent: List<Path> = emptyList(),
        fromStart: (Path) -> Boolean,
    ): List<MusicCandidate> {
        if (musics.isEmpty()) throw HighlightsException("Aucune musique à essayer")
        val tried = musics.map { music ->
            val own = settingsFor(settings, fromStart(music.file))
            val prepared = prepare(music, own)
            try {
                val plan = MontagePlanner.best(groups, prepared, own)
                MusicCandidate(prepared, plan, MontageScorer.score(plan).total, plan.clips.map { it.group }.distinct().size, value = 0.0)
            } catch (e: HighlightsException) {
                MusicCandidate(prepared, null, 0.0, 0, value = 0.0, error = e.message)
            } catch (e: IllegalArgumentException) {
                MusicCandidate(prepared, null, 0.0, 0, value = 0.0, error = e.message)
            }
        }
        val most = tried.maxOf { it.groups }.coerceAtLeast(1)
        val ranked = tried.map { c ->
            val recency = recency(c.music.file, recent)
            val pace = c.plan?.let { pace(it, settings.musicPace) } ?: 0.0
            c.copy(value = if (c.plan == null) 0.0 else c.score * c.groups / most + pace - recency, recency = recency, pace = pace)
        }
            .sortedWith(compareBy<MusicCandidate> { it.plan == null }.thenByDescending { it.value }.thenBy { it.music.file.toString().lowercase() })
        log.info {
            "Choix de la musique : " + ranked.joinToString(", ") { c ->
                "${c.music.file.fileName} " + (c.error?.let { "(aucun plan : $it)" } ?: "%.3f (note %.3f, %d groupe(s)%s)".format(c.value, c.score, c.groups, (if (c.pace > 0) ", rythme +%.3f".format(c.pace) else "") + if (c.recency > 0) ", récente -%.3f".format(c.recency) else ""))
            }
        }
        return ranked
    }

    /**
     * Avance d'un plan pour la longueur médiane de ses plans hors drop (celui de la drop garde son élan quel que soit le
     * tempo) : [MusicPace.weight] jusqu'à [MusicPace.fast], rien à partir de [MusicPace.slow].
     */
    fun pace(plan: MontagePlan, preference: MusicPace?): Double {
        val p = preference ?: return 0.0
        val lengths = plan.clips.filter { it.slot.dropBeat == null }.map { it.outputLength }.sorted()
        if (lengths.isEmpty()) return 0.0
        val median = lengths[lengths.size / 2]
        return p.weight * (1.0 - ((median - p.fast) / (p.slow - p.fast)).coerceIn(0.0, 1.0))
    }

    /** Réglages du montage pour une musique : prise depuis son début ou non. */
    fun settingsFor(settings: MontageSettings, fromStart: Boolean): MontageSettings =
        if (settings.cuts.fromStart == fromStart) settings else settings.copy(cuts = settings.cuts.copy(fromStart = fromStart))

    /** Analyse telle que le montage l'utilise : depuis le début, la drop doit tomber dans le montage. */
    fun prepare(music: MusicAnalysis, settings: MontageSettings): MusicAnalysis =
        if (settings.cuts.fromStart) MusicAnalyzer.fromStart(music, settings.maxDuration) else music
}
