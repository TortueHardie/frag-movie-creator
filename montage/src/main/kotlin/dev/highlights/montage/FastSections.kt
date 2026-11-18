package dev.highlights.montage

import dev.highlights.core.model.CutSettings
import dev.highlights.core.model.FastParts
import dev.highlights.core.model.MontageSettings
import kotlin.math.ceil
import kotlin.math.log10

/**
 * Parties rapides de la musique ([dev.highlights.core.model.FastParts]) : celles où le montage passe au rythme rapide
 * dans un montage qui ne l'est pas tout entier. La grille de coupes ([CutGrid]) les découpe à la cadence du rythme
 * rapide, le planificateur ([MontagePlanner]) y place un kill par plan sans ralenti ni rampe, et le rendu y ajoute le
 * zoom d'approche vers le réticule ([MontageClip.fastPart]).
 *
 * Les sections de l'analyse font au moins quatre mesures : une coupure de deux mesures au milieu d'une partie intense
 * (la basse de « Big Dawgs » qui s'arrête à 0:20) n'en est pas une, et restait au rythme rapide. [of] découpe donc les
 * sections intenses à leurs creux ([calmBreaks]) : le planificateur travaille sur [Rhythm.music], où chaque creux est une
 * section à lui, plus calme, au rythme du style.
 */
object FastSections {

    /**
     * Sections rapides ([sections] : indices dans [music].sections), et ce qui change dans leurs plans : réglages de
     * coupe, longueur minimale d'un plan, réglages du plan. [music] : la musique dont les sections intenses sont
     * découpées à leurs creux, à utiliser pour tout le plan.
     */
    data class Rhythm(val sections: Set<Int>, val cuts: CutSettings, val minBeats: Int, val settings: MontageSettings, val music: MusicAnalysis)

    /**
     * Rythme des parties rapides de [music], null s'il n'y en a pas : réglage coupé, montage déjà entièrement rapide
     * (rythme rapide forcé ou onetaps : plans d'un temps partout), aucune partie assez intense à partir de la drop.
     */
    fun of(music: MusicAnalysis, settings: MontageSettings): Rhythm? {
        val parts = settings.fastParts
        if (!parts.enabled || settings.cuts.singleBeat || music.sections.isEmpty()) return null
        val split = withCalmBreaks(music, parts)
        val dropSection = split.sectionIndexAt(split.dropBeat)
        val sections = split.sections.indices.filter { it >= dropSection && split.sections[it].intensity >= parts.minIntensity }.toSet()
        if (sections.isEmpty()) return null
        val fast = settings.forFast()
        val period = split.beatPeriod
        val single = fast.cuts.minLead + fast.cuts.minTail <= period
        val minBeats = if (single) 1 else maxOf(2, beats(fast.cuts.minLead, period) + beats(fast.cuts.minTail, period))
        // Seul le rythme des plans change : groupes de kills, durée, ordre et effets restent ceux du montage.
        val planSettings = settings.copy(
            cuts = fast.cuts,
            slowMotion = settings.slowMotion.copy(enabled = false),
            speedRamp = settings.speedRamp.copy(enabled = false),
        )
        return Rhythm(sections, fast.cuts, minBeats, planSettings, split)
    }

    /**
     * Creux des sections intenses ([FastParts.minIntensity]) à partir de la drop : suites d'au moins [FastParts.calmBars]
     * mesures dont le volume moyen (moyenne des dB de ses temps) est à [FastParts.calmDb] ou plus sous le niveau fort du
     * morceau (90e centile des mesures). Temps de début inclus, de fin exclu, sur les premiers temps de mesure.
     */
    internal fun calmBreaks(music: MusicAnalysis, parts: FastParts): List<IntRange> {
        val n = minOf(music.beatEnergy.size, music.beats.size)
        val loud = DoubleArray(n) { 20 * log10(music.beatEnergy[it] + 1e-5) }
        val phase = ((music.downbeatPhase % BAR) + BAR) % BAR
        val bars = (phase..n - BAR step BAR).map { it to (it until it + BAR).sumOf { b -> loud[b] } / BAR }
        if (bars.size < 2) return emptyList()
        val sorted = bars.map { it.second }.sorted()
        val reference = sorted[((sorted.size - 1) * 0.9).toInt()]
        val dropSection = music.sectionIndexAt(music.dropBeat)
        val breaks = mutableListOf<IntRange>()
        music.sections.forEachIndexed { si, s ->
            if (si < dropSection || s.intensity < parts.minIntensity) return@forEachIndexed
            val calm = bars.filter { (b, level) -> b >= s.startBeat && b + BAR <= s.endBeat && reference - level >= parts.calmDb }.map { it.first }
            var k = 0
            while (k < calm.size) {
                var j = k
                while (j + 1 < calm.size && calm[j + 1] == calm[j] + BAR) j++
                if (j - k + 1 >= parts.calmBars) breaks += calm[k] until calm[j] + BAR
                k = j + 1
            }
        }
        return breaks
    }

    /**
     * [music] dont chaque section intense est coupée à ses creux ([calmBreaks]) : le creux devient une section
     * [SectionKind.BREAKDOWN] dont l'intensité baisse de l'écart de volume, au poids que lui donne l'analyse (0,8 de
     * l'intensité vient du volume, rapporté à l'écart entre les temps forts et faibles du morceau, au moins 6 dB),
     * et jamais au-dessus de [FastParts.minIntensity] : le rythme du style à l'intensité qu'on entend. Le reste de la
     * section garde son intensité et son rôle. Sans creux, [music] tel quel.
     */
    fun withCalmBreaks(music: MusicAnalysis, parts: FastParts): MusicAnalysis {
        val breaks = calmBreaks(music, parts)
        if (breaks.isEmpty()) return music
        val n = minOf(music.beatEnergy.size, music.beats.size)
        val loud = DoubleArray(n) { 20 * log10(music.beatEnergy[it] + 1e-5) }
        val sorted = loud.sorted()
        val p95 = sorted[((n - 1) * 0.95).toInt()]
        val p10 = maxOf(sorted[((n - 1) * 0.10).toInt()], p95 - 20)
        val range = maxOf(p95 - p10, 6.0)
        fun level(from: Int, to: Int) = (from until minOf(to, n)).map { loud[it] }.average()
        val sections = music.sections.flatMap { s ->
            val inside = breaks.filter { it.first >= s.startBeat && it.last < s.endBeat }
            if (inside.isEmpty()) return@flatMap listOf(s)
            val pieces = mutableListOf<MusicSection>()
            var at = s.startBeat
            for (b in inside) {
                if (b.first > at) pieces += s.copy(startBeat = at, endBeat = b.first, rise = 0.0)
                val db = level(b.first, b.last + 1)
                val intensity = (s.intensity - 0.8 * (s.loudnessDb - db) / range).coerceIn(0.0, parts.minIntensity - 0.01)
                pieces += MusicSection(b.first, b.last + 1, db, intensity, kind = SectionKind.BREAKDOWN)
                at = b.last + 1
            }
            if (at < s.endBeat) pieces += s.copy(startBeat = at, rise = 0.0)
            pieces
        }
        return music.copy(sections = sections)
    }

    private const val BAR = 4

    private fun beats(d: kotlin.time.Duration, period: kotlin.time.Duration) = ceil(d / period).toInt().coerceAtLeast(1)
}
