package dev.highlights.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Rôle d'une piste audio dans une capture, indépendamment de l'enregistreur qui l'a produite. */
@Serializable
enum class AudioRole {
    /** Tout le son de la capture (jeu + voix). Toujours servi, au pire en mélangeant les autres pistes. */
    @SerialName("mix") MIX,

    /** Son du jeu seul si l'enregistreur l'a isolé, sinon le mix. */
    @SerialName("game") GAME,

    /** Micro seul. Absent d'une capture à piste unique. */
    @SerialName("mic") MIC,
}

/**
 * Indices imposés par un profil quand la disposition des pistes n'est pas celle qui est déduite (voir [AudioTracks]).
 * Un index absent du fichier est ignoré.
 */
@Serializable
data class AudioLayout(val mix: Int? = null, val game: Int? = null, val mic: Int? = null) {
    val isEmpty: Boolean get() = mix == null && game == null && mic == null
}

/**
 * Rôle de chaque piste d'une capture, quel que soit l'enregistreur : Outplayed écrit trois pistes (mix, jeu, micro),
 * OBS une (tout mélangé) ou deux (jeu puis micro), un enregistrement console une seule.
 *
 * Ordre de décision : indices imposés par le profil, puis titres des pistes (OBS les nomme souvent), puis leur nombre.
 * Un profil n'a donc pas à connaître la disposition d'un enregistreur pour marcher chez tout le monde.
 */
class AudioTracks private constructor(
    val streams: List<AudioStream>,
    private val roles: Map<AudioRole, AudioStream>,
    /** Vrai quand une piste contient déjà tout le son : inutile d'en mélanger d'autres à l'export. */
    val hasPremix: Boolean,
    /** Comment la disposition a été décidée, pour le journal et le diagnostic. */
    val explanation: String,
) {
    operator fun get(role: AudioRole): AudioStream? = roles[role]

    fun indexOf(role: AudioRole): Int? = roles[role]?.audioIndex

    /**
     * Pistes à mélanger pour obtenir tout le son de la capture : celle de mix si l'enregistreur en a écrit une
     * (les autres la composent déjà), sinon toutes.
     */
    fun mixIndices(): List<Int> {
        val mix = roles[AudioRole.MIX]
        return if (hasPremix && mix != null) listOf(mix.audioIndex) else streams.map { it.audioIndex }
    }

    /** Résumé court pour l'interface : « 3 pistes audio : jeu + micro séparés ». */
    fun shortLabel(): String = when {
        streams.isEmpty() -> "aucune piste audio"
        this[AudioRole.MIC] != null -> "${streams.size} pistes audio : jeu + micro séparés"
        streams.size == 1 -> "1 piste audio : tout le son"
        else -> "${streams.size} pistes audio : micro non séparé"
    }

    /** Résumé lisible : « 3 pistes : a:0 → mix, a:1 → game, a:2 → mic (…) ». */
    fun describe(): String {
        val head = if (streams.size > 1) "${streams.size} pistes : " else "${streams.size} piste : "
        val body = streams.joinToString { s ->
            val named = roles.entries.filter { it.value.audioIndex == s.audioIndex }.map { it.key.name.lowercase() }
            s.label + if (named.isEmpty()) "" else " → " + named.joinToString("/")
        }
        return "$head$body ($explanation)"
    }

    companion object {
        private val MIC_WORDS = listOf("mic", "micro", "voice", "voix", "chat", "headset", "casque")
        private val GAME_WORDS = listOf("game", "jeu", "desktop", "bureau", "system", "système", "application", "speaker", "haut-parleur")
        private val MIX_WORDS = listOf("mix", "all", "tout", "master", "global")

        val EMPTY = AudioTracks(emptyList(), emptyMap(), hasPremix = false, explanation = "aucune piste audio")

        /** Déduit les rôles des pistes de [streams], [layout] ayant le dernier mot. */
        fun of(streams: List<AudioStream>, layout: AudioLayout = AudioLayout()): AudioTracks {
            if (streams.isEmpty()) return EMPTY
            val roles = LinkedHashMap<AudioRole, AudioStream>()
            val byTitle = fromTitles(streams)
            var premix: Boolean
            var reason: String

            if (byTitle.isNotEmpty()) {
                roles += byTitle
                premix = AudioRole.MIX in byTitle
                reason = "d'après les titres des pistes"
            } else {
                when (streams.size) {
                    1 -> {
                        roles[AudioRole.MIX] = streams[0]
                        premix = true
                        reason = "piste unique : tout le son"
                    }
                    2 -> {
                        // OBS et les enregistreurs à deux pistes écrivent le son du jeu (ou le mix) puis le micro.
                        roles[AudioRole.GAME] = streams[0]
                        roles[AudioRole.MIC] = streams[1]
                        premix = false
                        reason = "deux pistes : jeu puis micro, mélangés à l'export"
                    }
                    else -> {
                        // Disposition d'Outplayed, mesurée : mix, jeu, micro.
                        roles[AudioRole.MIX] = streams[0]
                        roles[AudioRole.GAME] = streams[1]
                        roles[AudioRole.MIC] = streams[2]
                        premix = true
                        reason = "trois pistes ou plus : mix, jeu, micro (disposition Outplayed)"
                    }
                }
            }

            if (!layout.isEmpty) {
                layout.mix?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.MIX] = it; premix = true } }
                layout.game?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.GAME] = it } }
                layout.mic?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.MIC] = it } }
                reason += ", indices du profil appliqués"
            }

            // Jeu et mix se remplacent l'un l'autre : ces deux rôles sont toujours servis, le micro peut manquer.
            roles[AudioRole.MIX]?.let { roles.putIfAbsent(AudioRole.GAME, it) }
            roles[AudioRole.GAME]?.let { roles.putIfAbsent(AudioRole.MIX, it) }

            return AudioTracks(streams, roles, premix, reason)
        }

        /** Rôles lisibles dans les titres des pistes (OBS : « Mic/Aux », « Desktop Audio »). */
        private fun fromTitles(streams: List<AudioStream>): Map<AudioRole, AudioStream> {
            val found = LinkedHashMap<AudioRole, AudioStream>()
            for (stream in streams) {
                val title = stream.title?.lowercase()?.trim() ?: continue
                val role = when {
                    MIC_WORDS.any { title.contains(it) } -> AudioRole.MIC
                    MIX_WORDS.any { title.contains(it) } -> AudioRole.MIX
                    GAME_WORDS.any { title.contains(it) } -> AudioRole.GAME
                    else -> null
                } ?: continue
                found.putIfAbsent(role, stream)
            }
            // Un seul rôle reconnu sur une capture multi-pistes n'apprend rien de la disposition des autres.
            return if (found.size >= 2 || (found.size == 1 && streams.size == 1)) found else emptyMap()
        }
    }
}
