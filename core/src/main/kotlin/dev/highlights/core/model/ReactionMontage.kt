package dev.highlights.core.model

import dev.highlights.core.serialization.SerialDuration
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Montage « réactions » : les moments choisis par la réaction du joueur au micro (rire, cri, coup de gueule), quelle
 * qu'en soit la cause (kill, mort, ou rien du tout). Une compilation de morts seule n'intéressait pas le joueur (18/11) :
 * une mort ne fait rire que si on entend la réaction. Chaque réaction ancre un plan calé sur la musique comme un kill ;
 * le micro passe devant le jeu, la musique s'efface dessous, et ce qui est dit s'affiche en sous-titres (whisper).
 *
 * Réactions trouvées par `ReactionFinder` (module montage) sur la piste micro : les éclats de voix nettement plus forts
 * que la voix habituelle du joueur dans la partie ([rise]), plus les rires et cris reconnus par YAMNet. Mesuré sur deux
 * parties WARDOGS (2 h 30, piste micro d'Outplayed) : YAMNet n'y trouve qu'un rire (score 0,39, les autres sous 0,06 :
 * le joueur rit peu fort, ses rires sortent en « Speech »), alors que les éclats à +10 dB en donnent 29, dont « il est
 * dead », un fou rire, « what the fuck ? ». [enabled] est posé par [forReactions].
 */
@Serializable
data class ReactionMontage(
    val enabled: Boolean = false,
    /** Événement ajouté à la chronologie au début de chaque réaction : il ancre le plan comme un kill. */
    val event: String = "reaction",
    /** Écart (dB) au niveau habituel de la voix du joueur (médiane des passages où il parle) pour compter comme éclat. */
    val rise: Double = 10.0,
    /** Plus court éclat retenu : un mot fort isolé (« ah ») ne suffit pas. */
    val minLength: SerialDuration = 300.milliseconds,
    /** Deux éclats séparés de moins que ça forment une seule réaction (un fou rire, une phrase criée). */
    val mergeGap: SerialDuration = 1500.milliseconds,
    /** Niveau (dBFS) sous lequel le micro est considéré comme muet (micro coupé, push-to-talk). */
    val silenceDb: Double = -50.0,
    /** Avant la réaction : de quoi voir ce qui la provoque. */
    val preRoll: SerialDuration = 3500.milliseconds,
    /** Après le début de la réaction : de quoi l'entendre en entier (la phrase prolonge encore le plan, voir keepWhole). */
    val postRoll: SerialDuration = 2500.milliseconds,
    /** Longueur visée d'un plan. */
    val perClip: SerialDuration = 6.seconds,
    val minLead: SerialDuration = 2500.milliseconds,
    val minTail: SerialDuration = 1500.milliseconds,
    val min: SerialDuration = 15.seconds,
    /**
     * Un kill, une mort ou une aide qui précède la réaction de moins que ça en est la cause probable : la réaction compte
     * davantage ([causeBonus]), un appel radio (« un à gauche, attention ! ») n'en a pas.
     */
    val causeWindow: SerialDuration = 5.seconds,
    val causeBonus: Double = 0.5,
    /**
     * Réactions distantes de moins que ça : la plus forte seulement (un même fou rire ne fait pas deux plans). 0s : aucun
     * écart minimal, chaque réaction garde sa chance (choix du joueur, 20/11 : « j'ai coupé mon parachute » et « c'est
     * un Toulousain », 11 s d'écart, peuvent faire deux plans) ; `--no-reaction-spacing`, case de l'application.
     */
    val spacing: SerialDuration = 8.seconds,
    /** Gain du micro mixé par-dessus le jeu (2 : +6 dB). */
    val micGain: Double = 2.0,
    /** Volume de la musique sous la voix : plus bas que dans le montage kills, ici la voix est le sujet. */
    val musicUnderVoice: Double = 0.25,
    /** Sous-titres whisper de ce qui est dit (modèle du profil, `edit.story.captions`). */
    val captions: Boolean = true,
) {
    init {
        require(rise > 0.0) { "montage.reactionMontage.rise doit être positif" }
        require(!spacing.isNegative()) { "montage.reactionMontage.spacing ne peut pas être négatif" }
        require(micGain > 0.0) { "montage.reactionMontage.micGain doit être positif" }
        require(musicUnderVoice in 0.0..1.0) { "montage.reactionMontage.musicUnderVoice doit être entre 0 et 1" }
    }
}

/**
 * Les mêmes réglages, pour le montage des réactions : chaque réaction ancre son plan (début de l'éclat sur un temps), les
 * plans s'étendent jusqu'à la fin de la phrase ([MontageSettings.reactions]), sans ralenti ni image figée : ils
 * déformeraient la voix, qui est le sujet ; sans plans d'un temps (rythme rapide) : une phrase n'y tient pas. Ce qui sert à classer les kills (rounds, tirs à la tête, flick, pose de
 * l'arme, confirmation au killfeed, capacités) n'a pas de sens ici : coupé. Le classement vient de la force de la
 * réaction (`Reactions.rescore`).
 */
fun MontageSettings.forReactions(): MontageSettings {
    val r = reactionMontage
    return copy(
        killEvent = r.event,
        anchorEvents = emptySet(),
        killOffset = Duration.ZERO,
        reactionMontage = r.copy(enabled = true),
        mergeGap = Duration.ZERO,
        splitGap = Duration.ZERO,
        preRoll = r.preRoll,
        postRoll = r.postRoll,
        hook = false,
        reactions = true,
        keepWhole = listOf("laughter", "shout", "speech"),
        skipDeathWithin = Duration.ZERO,
        skipAbilityKills = false,
        minKills = 0,
        length = length.copy(perClip = r.perClip, perExtraKill = Duration.ZERO, min = r.min),
        cuts = cuts.copy(
            low = r.perClip, mid = r.perClip, high = r.perClip, minLead = r.minLead, minTail = r.minTail,
            singleBeat = false, offbeatKills = false, jumpCuts = false,
        ),
        slowMotion = slowMotion.copy(enabled = false),
        // Ni image figée : l'image continue pendant la réaction (choix du joueur, 20/11), même si le profil active les morts.
        fails = fails.copy(enabled = false),
        speedRamp = speedRamp.copy(enabled = false),
        matchCut = matchCut.copy(enabled = false),
        whip = whip.copy(enabled = false),
        showcase = showcase.copy(enabled = false),
        // Plans d'un temps sur les parties rapides de la musique : une phrase n'y tient pas.
        fastParts = fastParts.copy(enabled = false),
        zoom = zoom.copy(approach = 0.0),
        text = text.copy(multiKillLabels = emptyList(), killCounter = KillCounterMode.OFF),
        audio = audio.copy(musicUnderVoice = r.musicUnderVoice),
        killStyle = killStyle.copy(deathEvent = "", headshotEvent = "", shotEvent = "", hudShotEvent = "", confirmEvent = "", clutchEvent = "", allyDeathEvent = "", clutchBannerEvent = "", flick = false),
    )
}
