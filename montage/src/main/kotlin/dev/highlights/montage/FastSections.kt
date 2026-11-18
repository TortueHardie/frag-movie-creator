package dev.highlights.montage

import dev.highlights.core.model.CutSettings
import dev.highlights.core.model.MontageSettings
import kotlin.math.ceil

/**
 * Parties rapides de la musique ([dev.highlights.core.model.FastParts]) : celles où le montage passe au rythme rapide
 * dans un montage qui ne l'est pas tout entier. La grille de coupes ([CutGrid]) les découpe à la cadence du rythme
 * rapide, le planificateur ([MontagePlanner]) y place un kill par plan sans ralenti ni rampe, et le rendu y ajoute le
 * zoom d'approche vers le réticule ([MontageClip.fastPart]).
 */
object FastSections {

    /** Sections rapides, et ce qui change dans leurs plans : réglages de coupe, longueur minimale d'un plan, réglages du plan. */
    data class Rhythm(val sections: Set<Int>, val cuts: CutSettings, val minBeats: Int, val settings: MontageSettings)

    /**
     * Rythme des parties rapides de [music], null s'il n'y en a pas : réglage coupé, montage déjà entièrement rapide
     * (rythme rapide forcé ou onetaps : plans d'un temps partout), tempo trop lent, aucune partie assez intense à partir
     * de la drop.
     */
    fun of(music: MusicAnalysis, settings: MontageSettings): Rhythm? {
        val parts = settings.fastParts
        if (!parts.enabled || settings.cuts.singleBeat || music.bpm < parts.minBpm || music.sections.isEmpty()) return null
        val dropSection = music.sectionIndexAt(music.dropBeat)
        val sections = music.sections.indices.filter { it >= dropSection && music.sections[it].intensity >= parts.minIntensity }.toSet()
        if (sections.isEmpty()) return null
        val fast = settings.forFast()
        val period = music.beatPeriod
        val single = fast.cuts.minLead + fast.cuts.minTail <= period
        val minBeats = if (single) 1 else maxOf(2, beats(fast.cuts.minLead, period) + beats(fast.cuts.minTail, period))
        // Seul le rythme des plans change : groupes de kills, durée, ordre et effets restent ceux du montage.
        val planSettings = settings.copy(
            cuts = fast.cuts,
            slowMotion = settings.slowMotion.copy(enabled = false),
            speedRamp = settings.speedRamp.copy(enabled = false),
        )
        return Rhythm(sections, fast.cuts, minBeats, planSettings)
    }

    private fun beats(d: kotlin.time.Duration, period: kotlin.time.Duration) = ceil(d / period).toInt().coerceAtLeast(1)
}
