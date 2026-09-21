package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Manière de monter les meilleurs moments. */
@Serializable
enum class EditStyle {
    /** Les moments bout à bout, tels qu'ils ont été sélectionnés, séparés par un fondu. */
    @SerialName("simple") SIMPLE,

    /**
     * Montage façon vidéo YouTube : temps morts retirés en jump cuts, accroche en ouverture, punch-in sur les réactions,
     * secousses sur les impacts, coupes franches soulignées d'un flash et d'un whoosh entre les moments.
     */
    @SerialName("story") STORY,
}

/**
 * Réglages du montage « story ». Chaque moment est raconté en trois temps : la mise en place (ce qui mène au pic), le
 * pic, la réaction ; tout ce qui ne sert ni l'un ni l'autre (silence, attente, déplacement) est coupé.
 */
@Serializable
data class StorySettings(
    val jumpCuts: JumpCutSettings = JumpCutSettings(),
    val coldOpen: ColdOpenSettings = ColdOpenSettings(),
    val punchIn: PunchInSettings = PunchInSettings(),
    val shake: ShakeSettings = ShakeSettings(),
    val transition: StoryTransition = StoryTransition(),
    val sfx: SfxSettings = SfxSettings(),
    val music: MusicBed = MusicBed(),
    val captions: CaptionSettings = CaptionSettings(),
    /** Fondu au noir (image et son) sur la fin du montage. */
    val fadeOut: SerialDuration = 600.milliseconds,
)

/**
 * Jump cuts : les passages creux d'un moment (ni voix, ni événement, score bas) sont retirés. Il en reste un peu de
 * chaque côté de la coupe ([breath]) pour que la phrase ou l'action ne soit pas tranchée net.
 */
@Serializable
data class JumpCutSettings(
    val enabled: Boolean = true,
    /** Passage creux plus court que ça : gardé, couper ferait sauter l'image pour rien. */
    val minGap: SerialDuration = 1500.milliseconds,
    /** Marge gardée de part et d'autre d'un passage retiré. */
    val breath: SerialDuration = 300.milliseconds,
    /**
     * Score relatif (0..1, rapporté au pic du moment) sous lequel une fenêtre est creuse, si rien d'autre (voix,
     * événement) ne la retient.
     */
    val deadScore: Double = 0.35,
    /** Plan plus court que ça après découpe : fusionné avec son voisin plutôt que de clignoter à l'écran. */
    val minShot: SerialDuration = 600.milliseconds,
    /**
     * Zoom alterné d'un plan à l'autre au sein d'un moment (1 = aucun) : le saut d'image d'un jump cut devient un
     * changement de cadre, comme deux caméras. 1,08 = 8 % plus serré un plan sur deux.
     */
    val alternateZoom: Double = 1.08,
    /** Temps gardé autour d'un événement (kill…) même si la fenêtre est calme. */
    val eventGuard: SerialDuration = 1500.milliseconds,
) {
    init {
        require(deadScore in 0.0..1.0) { "story.jumpCuts.deadScore doit être entre 0 et 1" }
        require(alternateZoom in 1.0..1.5) { "story.jumpCuts.alternateZoom doit être entre 1 et 1,5" }
    }
}

/**
 * Accroche : quelques secondes du meilleur moment ouvrent la vidéo, avant de reprendre dans l'ordre. C'est dans les
 * premières secondes que le spectateur décide de rester.
 */
@Serializable
data class ColdOpenSettings(
    val enabled: Boolean = true,
    /** Durée de l'extrait, centrée un peu avant le pic (la mise en place compte). */
    val length: SerialDuration = 3.seconds,
    /** Part de l'extrait avant le pic. */
    val beforePeak: Double = 0.6,
    /** Moments nécessaires pour qu'une accroche ait un sens (sinon elle répète le seul moment de la vidéo). */
    val minMoments: Int = 2,
) {
    init {
        require(beforePeak in 0.0..1.0) { "story.coldOpen.beforePeak doit être entre 0 et 1" }
        require(minMoments >= 1) { "story.coldOpen.minMoments doit être ≥ 1" }
    }
}

/** Punch-in : l'image se resserre pendant une réaction (rire, cri) et revient à la coupe suivante. */
@Serializable
data class PunchInSettings(
    val enabled: Boolean = true,
    /** 0,15 = 15 % plus serré. */
    val amount: Double = 0.15,
    /** Durée de la montée du zoom : assez courte pour paraître voulue, assez longue pour ne pas sauter. */
    val ramp: SerialDuration = 120.milliseconds,
    /** Types de segments qui déclenchent le punch-in. */
    val on: List<String> = listOf("laughter", "shout"),
    /** Réaction plus courte que ça : pas de punch-in. */
    val minLength: SerialDuration = 500.milliseconds,
) {
    init {
        require(amount in 0.0..0.6) { "story.punchIn.amount doit être entre 0 et 0,6" }
    }
}

/** Secousse de l'image sur les impacts : événements (kills…) et pic de chaque moment. */
@Serializable
data class ShakeSettings(
    val enabled: Boolean = true,
    /** Amplitude, en part de la hauteur de l'image. */
    val amplitude: Double = 0.012,
    val duration: SerialDuration = 350.milliseconds,
    /** Oscillations par seconde. */
    val frequency: Double = 18.0,
    /** Événements qui secouent l'image ; le pic de chaque moment la secoue aussi si [onPeak]. */
    val events: List<String> = listOf("kill"),
    val onPeak: Boolean = true,
) {
    init {
        require(amplitude in 0.0..0.05) { "story.shake.amplitude doit être entre 0 et 0,05" }
    }
}

/** Passage d'un moment au suivant : coupe franche, soulignée d'un flash blanc de quelques images. */
@Serializable
data class StoryTransition(
    val flash: Boolean = true,
    val flashDuration: SerialDuration = 80.milliseconds,
    /** Fondu du son à chaque coupe, jump cuts compris : sans lui, une coupe au milieu d'un son claque. */
    val audioFade: SerialDuration = 15.milliseconds,
)

/**
 * Bruitages générés par FFmpeg (aucun fichier à fournir) : whoosh aux changements de moment, impact sourd sous les
 * secousses. [whooshFile] / [impactFile] remplacent le son généré par un fichier (chemin relatif au dossier de configuration).
 */
@Serializable
data class SfxSettings(
    val enabled: Boolean = true,
    val whooshVolume: Double = 0.35,
    val impactVolume: Double = 0.5,
    val whooshFile: String? = null,
    val impactFile: String? = null,
) {
    init {
        require(whooshVolume in 0.0..2.0 && impactVolume in 0.0..2.0) { "story.sfx : volumes entre 0 et 2" }
    }
}

/**
 * Musique de fond, facultative : baissée sous la voix, et coupée net sur le pic de chaque moment pour laisser la place
 * à l'action (le « drop » du silence), puis relancée en fondu.
 */
@Serializable
data class MusicBed(
    /** Fichier audio ; chemin relatif au dossier de configuration. null = pas de musique. */
    val file: String? = null,
    val volume: Double = 0.25,
    /** Volume sous la voix et les réactions. */
    val underVoice: Double = 0.1,
    /** Silence de la musique sur le pic de chaque moment. */
    val dropOut: Boolean = true,
    val dropLength: SerialDuration = 1500.milliseconds,
) {
    init {
        require(volume in 0.0..2.0 && underVoice in 0.0..2.0) { "story.music : volumes entre 0 et 2" }
    }
}

/**
 * Sous-titres de la voix, transcrite en local par whisper.cpp (filtre `whisper` de FFmpeg) : quelques mots à la fois,
 * qui apparaissent d'un coup de zoom, en gros, comme dans les vidéos YouTube. Sans modèle, pas de sous-titres.
 */
@Serializable
data class CaptionSettings(
    val enabled: Boolean = true,
    /** Modèle whisper.cpp (ggml) ; chemin relatif au dossier de configuration. */
    val model: String? = null,
    /** Langue parlée ("auto" pour la détecter, au prix d'erreurs sur des extraits courts). */
    val language: String = "fr",
    /** Longueur maximale d'un sous-titre, en caractères : deux à quatre mots, lisibles d'un coup d'œil. */
    val maxChars: Int = 18,
    /** Piste transcrite : la voix du joueur (mic), à défaut le son complet. */
    val role: AudioRole = AudioRole.MIC,
    val font: String = "C:/Windows/Fonts/impact.ttf",
    /** Taille du texte, en part de la hauteur de l'image. */
    val size: Double = 0.075,
    /** Position verticale du centre du texte (0 = haut, 1 = bas), en paysage et en vertical. */
    val y: Double = 0.78,
    val verticalY: Double = 0.66,
    /** Apparition « pop » : le texte grossit jusqu'à sa taille en ce temps-là. */
    val pop: SerialDuration = 120.milliseconds,
    val uppercase: Boolean = true,
    /** Transcription sur le GPU quand whisper.cpp le permet. */
    val useGpu: Boolean = true,
) {
    init {
        require(maxChars in 4..80) { "story.captions.maxChars doit être entre 4 et 80" }
        require(size in 0.02..0.2) { "story.captions.size doit être entre 0,02 et 0,2" }
        require(y in 0.0..1.0 && verticalY in 0.0..1.0) { "story.captions.y doit être entre 0 et 1" }
    }
}
