package dev.highlights.core.model

import dev.highlights.core.i18n.Message
import dev.highlights.core.i18n.tr
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

    /**
     * Voix des coéquipiers (Discord, TeamSpeak, chat de SteelSeries Sonar…) sur leur propre piste. Absent le plus
     * souvent : la plupart des enregistreurs la mélangent au son du PC.
     */
    @SerialName("chat") CHAT,
}

/**
 * Indices imposés par un profil quand la disposition des pistes n'est pas celle qui est déduite (voir [AudioTracks]).
 * Un index absent du fichier est ignoré.
 */
@Serializable
data class AudioLayout(val mix: Int? = null, val game: Int? = null, val mic: Int? = null, val chat: Int? = null) {
    val isEmpty: Boolean get() = mix == null && game == null && mic == null && chat == null
}

/**
 * Rôle de chaque piste d'une capture, quel que soit l'enregistreur, sans réglage du joueur : Outplayed écrit trois
 * pistes (mix, jeu, micro), OBS une (tout mélangé) ou deux (jeu puis micro), FTHRClips une piste « Default Mix », le micro
 * et, au choix du joueur, une piste par application ou par appareil (« Gaming », « Chat » de SteelSeries Sonar).
 *
 * Ordre de décision : indices imposés par le profil, puis ce que l'enregistreur déclare ([AudioStream.source]), puis les
 * titres des pistes, puis l'écoute ([AudioStream.content] : copies, piste qui contient les autres, piste muette, piste
 * qui sonne en continu = le jeu), enfin leur nombre. Un joueur n'a donc rien à régler pour que ça marche.
 */
class AudioTracks private constructor(
    val streams: List<AudioStream>,
    private val roles: Map<AudioRole, AudioStream>,
    /** Pistes (0:a:N) qui, additionnées, donnent tout le son une seule fois (voir [mixIndices]). */
    private val mixed: List<Int>,
    /** Comment la disposition a été décidée, pour le journal, le diagnostic et les messages d'erreur (traduit). */
    val explanation: Message,
) {
    operator fun get(role: AudioRole): AudioStream? = roles[role]

    fun indexOf(role: AudioRole): Int? = roles[role]?.audioIndex

    /** Vrai quand une piste contient déjà tout le son : inutile d'en mélanger d'autres à l'export. */
    val hasPremix: Boolean get() = streams.size > 1 && mixed.size == 1

    /**
     * Pistes à mélanger pour obtenir tout le son de la capture, chaque son une seule fois : la piste de mix si
     * l'enregistreur en a écrit une qui contient tout, sinon les pistes qu'aucune autre ne contient déjà (jamais une
     * copie, jamais une piste muette).
     */
    fun mixIndices(): List<Int> = mixed

    /** Résumé court pour l'interface : « 3 pistes audio : jeu + micro séparés ». */
    fun shortLabel(): String = when {
        streams.isEmpty() -> tr("audio.tracks.none")
        this[AudioRole.CHAT] != null -> tr("audio.tracks.withChat", "count" to streams.size)
        this[AudioRole.MIC] != null -> tr("audio.tracks.separate", "count" to streams.size)
        streams.size == 1 -> tr("audio.tracks.single")
        else -> tr("audio.tracks.mixed", "count" to streams.size)
    }

    /**
     * Résumé lisible et traduit : « 3 pistes : a:0 → mix, a:1 → jeu, a:2 → micro (…) ». Il finit dans le message
     * « aucun moment retenu » quand une piste manque : c'est un [Message], jamais un texte écrit en dur.
     */
    fun describe(): Message {
        if (streams.isEmpty()) return explanation
        val list = streams.map { s ->
            val named = roles.entries.filter { it.value.audioIndex == s.audioIndex }.map { roleName(it.key) }
            if (named.isEmpty()) {
                Message.raw(s.label)
            } else {
                // « mix/jeu » : le séparateur de liste de la langue (« ; ») couperait une piste en deux.
                val joined = named.reduce { a, b -> Message("audio.tracks.both", "a" to a, "b" to b) }
                Message("audio.tracks.assigned", "track" to s.label, "roles" to joined)
            }
        }
        return Message("audio.tracks.describe", "count" to streams.size, "tracks" to list, "reason" to explanation)
    }

    companion object {
        /*
         * Mots cherchés dans les titres, en minuscules, dans les langues de l'application. Ordre des tests : chat avant
         * micro (« Voice Chat », « Discord Mic » sont le chat), micro avant mix et jeu. « gaming » ne contient pas « game » :
         * cherché à part. Noms de SteelSeries Sonar : « Sonar - Gaming », « Sonar - Chat », « Sonar - Microphone ».
         */
        private val CHAT_WORDS = listOf(
            "chat", "discord", "teamspeak", "ts3", "mumble", "comms", "party", "vocal", "sprach", "czat", "голос",
            "チャット", "채팅", "语音", "聊天",
        )
        private val MIC_WORDS = listOf(
            "mic", "micro", "mikro", "микрофон", "マイク", "마이크", "麦克风", "voice", "voix", "headset", "casque",
        )
        private val MIX_WORDS = listOf("mix", "all", "tout", "master", "global", "combined", "gesamt", "микс")
        private val GAME_WORDS = listOf(
            "game", "gaming", "jeu", "spiel", "juego", "gioco", "jogo", "oyun", "gry", "игр", "ゲーム", "게임", "游戏",
            "desktop", "bureau", "system", "système", "application", "speaker", "haut-parleur", "lautsprecher",
        )

        val EMPTY = AudioTracks(emptyList(), emptyMap(), emptyList(), explanation = Message("audio.layout.none"))

        /** Nom traduit d'un rôle (« jeu », « micro »), pour les messages. */
        fun roleName(role: AudioRole): Message = Message("audio.role.${role.name.lowercase()}")

        /** Rôle lisible dans un titre de piste seul, null s'il n'en dit rien. */
        fun roleOfTitle(title: String?): AudioRole? {
            val t = title?.lowercase()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                CHAT_WORDS.any { t.hasWord(it) } -> AudioRole.CHAT
                MIC_WORDS.any { t.hasWord(it) } -> AudioRole.MIC
                MIX_WORDS.any { t.hasWord(it) } -> AudioRole.MIX
                GAME_WORDS.any { t.hasWord(it) } -> AudioRole.GAME
                else -> null
            }
        }

        /**
         * Mot qui commence un mot du titre (« mic » dans « Microphone », pas « all » dans « Call of Duty » ni « party » dans
         * « third-party »… si : le tiret coupe, assumé). Les écritures sans espaces (japonais, chinois, coréen) : n'importe où.
         */
        private fun String.hasWord(word: String): Boolean {
            if (word.any { Character.UnicodeScript.of(it.code).let { s -> s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL } }) {
                return contains(word)
            }
            var from = indexOf(word)
            while (from >= 0) {
                if (from == 0 || !this[from - 1].isLetterOrDigit()) return true
                from = indexOf(word, from + 1)
            }
            return false
        }

        /**
         * Rôle d'une piste d'après ce que l'enregistreur déclare puis son titre. La piste système de FTHRClips (« Default
         * Mix ») est tout le son du PC sans le micro : du jeu au sens de l'export (le micro s'y ajoute), pas un mix complet.
         */
        private fun guess(stream: AudioStream): AudioRole? {
            val byTitle = roleOfTitle(stream.title)
            return when {
                stream.source == AudioSourceKind.MICROPHONE -> if (byTitle == AudioRole.CHAT) AudioRole.CHAT else AudioRole.MIC
                isSystemMix(stream) -> if (byTitle == AudioRole.CHAT || byTitle == AudioRole.MIC) byTitle else AudioRole.GAME
                stream.source == AudioSourceKind.APPLICATION -> if (byTitle == AudioRole.CHAT) AudioRole.CHAT else AudioRole.GAME
                else -> byTitle
            }
        }

        /** Piste « tout le son du PC » de FTHRClips, déclarée ou reconnue à son titre (anciens clips sans description). */
        private fun isSystemMix(stream: AudioStream): Boolean =
            stream.source == AudioSourceKind.SYSTEM || stream.title?.trim()?.lowercase() == "default mix"

        /** Déduit les rôles des pistes de [streams], [layout] ayant le dernier mot. */
        fun of(streams: List<AudioStream>, layout: AudioLayout = AudioLayout()): AudioTracks {
            if (streams.isEmpty()) return EMPTY
            // Pistes nommées : l'écoute ne retire aucun rôle. Le jeu d'Outplayed ressemble à s'y méprendre au mix quand
            // le joueur se tait, et un micro peut se taire sur tous les passages écoutés ; elle sert alors seulement à ne
            // pas compter deux fois un son dans le mix ([mixOf]). Sans nom lisible, copies et pistes muettes n'ont pas de rôle.
            val heard = streams.filter { it.content?.duplicateOf == null && it.content?.silent != true }.ifEmpty { listOf(streams[0]) }
            val usable = if (streams.any { it.source != null || roleOfTitle(it.title) != null }) streams else heard
            val roles = LinkedHashMap<AudioRole, AudioStream>()
            var reason: Message
            // Piste qui contient tout le son (mix d'Outplayed, d'OBS) : seule à mélanger à l'export.
            var premix: AudioStream? = null

            val guesses = usable.associateWith { guess(it) }
            val declared = usable.any { it.source != null }
            val known = guesses.values.filterNotNull().toSet()
            val listened = usable.size > 1 && usable.all { it.content != null }

            if (declared || known.size >= 2 || (known.size == 1 && usable.size == 1)) {
                for ((stream, role) in guesses) {
                    role ?: continue
                    val current = roles[role]
                    // Deux pistes de jeu (FTHRClips : « Default Mix » et la piste de l'application du jeu) : la plus
                    // pure, celle qui ne porte pas aussi le chat vocal.
                    if (current == null || (role == AudioRole.GAME && isSystemMix(current) && !isSystemMix(stream))) roles[role] = stream
                }
                premix = roles[AudioRole.MIX]
                // Piste sans nom parmi des pistes nommées : celle qui sonne le plus souvent est le jeu s'il manque.
                if (AudioRole.GAME !in roles && AudioRole.MIX !in roles) {
                    usable.filter { guesses[it] == null }.maxByOrNull { it.content?.active ?: 0.0 }?.let { roles[AudioRole.GAME] = it }
                }
                reason = Message(if (declared) "audio.layout.declared" else "audio.layout.titles")
            } else if (listened) {
                // Rien de lisible : on décide à l'écoute. Une piste qui contient toutes les autres est le mix ; parmi
                // les autres, celle qui sonne le plus souvent est le jeu ; les voix (souvent silencieuses) suivent,
                // le micro d'abord, dans l'ordre des pistes (OBS, Outplayed : le micro avant les pistes ajoutées).
                premix = usable.firstOrNull { s ->
                    s.content!!.contains.containsAll(usable.filter { it !== s }.map { it.audioIndex })
                }
                premix?.let { roles[AudioRole.MIX] = it }
                val rest = usable.filter { it !== premix }
                val game = rest.maxByOrNull { it.content!!.active }
                game?.let { roles[AudioRole.GAME] = it }
                val voices = rest.filter { it !== game }
                voices.getOrNull(0)?.let { roles[AudioRole.MIC] = it }
                voices.getOrNull(1)?.let { roles[AudioRole.CHAT] = it }
                reason = Message("audio.layout.listened")
            } else {
                when (usable.size) {
                    1 -> {
                        roles[AudioRole.MIX] = usable[0]
                        premix = usable[0]
                        reason = Message("audio.layout.single")
                    }
                    2 -> {
                        // OBS et les enregistreurs à deux pistes écrivent le son du jeu (ou le mix) puis le micro.
                        roles[AudioRole.GAME] = usable[0]
                        roles[AudioRole.MIC] = usable[1]
                        reason = Message("audio.layout.two")
                    }
                    else -> {
                        // Disposition d'Outplayed, mesurée : mix, jeu, micro.
                        roles[AudioRole.MIX] = usable[0]
                        roles[AudioRole.GAME] = usable[1]
                        roles[AudioRole.MIC] = usable[2]
                        premix = usable[0]
                        reason = Message("audio.layout.outplayed")
                    }
                }
            }

            if (!layout.isEmpty) {
                layout.mix?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.MIX] = it; premix = it } }
                layout.game?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.GAME] = it } }
                layout.mic?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.MIC] = it } }
                layout.chat?.let { i -> streams.getOrNull(i)?.let { roles[AudioRole.CHAT] = it } }
                reason = Message("audio.layout.profile", "reason" to reason)
            }

            // Jeu et mix se remplacent l'un l'autre : ces deux rôles sont toujours servis, micro et chat peuvent manquer.
            roles[AudioRole.MIX]?.let { roles.putIfAbsent(AudioRole.GAME, it) }
            roles[AudioRole.GAME]?.let { roles.putIfAbsent(AudioRole.MIX, it) }

            // Une piste muette sur les passages écoutés reste mélangée (le joueur parle peut-être ailleurs) ; une copie, non.
            val mixed = premix?.let { listOf(it.audioIndex) } ?: mixOf(streams.filter { it.content?.duplicateOf == null }.ifEmpty { streams })
            return AudioTracks(streams, roles, mixed, reason)
        }

        /**
         * Pistes à additionner pour avoir tout le son une fois, sans mix complet : chaque piste utilisable sauf celles
         * qu'une autre contient déjà (mesuré à l'écoute, ou déclaré : la piste système de FTHRClips contient ses pistes
         * d'application, jamais le micro ni un autre appareil).
         */
        private fun mixOf(usable: List<AudioStream>): List<Int> {
            val inside = HashSet<Int>()
            for (s in usable) {
                s.content?.contains?.let { inside += it }
                if (isSystemMix(s)) inside += usable.filter { it.source == AudioSourceKind.APPLICATION }.map { it.audioIndex }
            }
            return usable.map { it.audioIndex }.filter { it !in inside }.ifEmpty { usable.map { it.audioIndex } }
        }
    }
}
