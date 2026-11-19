package dev.highlights.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import dev.highlights.montage.GroupTasteLearner
import dev.highlights.montage.GroupTrait
import dev.highlights.montage.KillTimingLearner
import dev.highlights.montage.MontageScorer
import dev.highlights.montage.TasteHistory
import dev.highlights.montage.TasteLearner
import dev.highlights.pipeline.Pipelines
import java.nio.file.Path

/**
 * `app gouts` : ce que le moteur a appris des choix de l'utilisateur (variantes rendues après les aperçus) : nombre de
 * choix, poids appris contre poids d'origine, et ce que ça changerait au dernier choix. `--reset` met l'historique de
 * côté (réversible : renommer le fichier mis de côté le rétablit).
 */
class TasteCommand : PipelineCommand("gouts") {
    private val reset by option("--reset", help = "Oublier ce qui a été appris : l'historique est mis de côté (taste-history.reset-<date>.json), pas effacé").flag()
    private val resetTiming by option("--reset-timing", help = "Oublier les calages appris (kill-timing.json mis de côté, pas effacé)").flag()
    private val history by option("--history", help = "Lire cet historique au lieu de <sortie>\\sessions\\taste-history.json").path(mustExist = true, canBeDir = false)

    override fun help(context: Context) =
        "Goûts appris des choix de variantes (montage --variants puis --pick) : poids des critères de la note, appris contre d'origine. " +
            "Actifs par défaut (montage.learnTaste), ils ne servent au montage qu'à partir de ${TasteLearner.MIN_CHOICES} choix ; --reset revient aux poids d'origine."

    override fun run() {
        val pipeline = Pipelines.create(env.config)
        val file: Path = history ?: pipeline.tasteHistory.file
        if (resetTiming) {
            val aside = pipeline.timingHistory.reset()
            echo(if (aside == null) "Rien à oublier : ${pipeline.timingHistory.file} n'existe pas." else "Calages oubliés. Historique mis de côté : $aside.")
            if (!reset) return
        }
        if (reset) {
            val aside = TasteHistory(file).reset()
            echo(if (aside == null) "Rien à oublier : $file n'existe pas." else "Goûts oubliés. Historique mis de côté : $aside (le renommer en ${file.fileName} le rétablit).")
            return
        }
        val state = pipeline.taste(TasteHistory(file))
        val learned = state.learned
        echo("Historique : ${state.file}")
        echo("  ${state.choices} choix de variante, ${state.edits} montage(s) corrigé(s) dans l'éditeur")
        val off = state.allProfiles - state.enabledProfiles.toSet()
        echo(
            "  apprentissage : " + when {
                state.enabledProfiles.isEmpty() -> "désactivé (montage.learnTaste: false dans tous les profils)"
                learned.active -> "actif, ${learned.choices} choix appris : les poids appris décident"
                else -> "actif, ${learned.choices} choix sur ${TasteLearner.MIN_CHOICES} avant d'apprendre (d'ici là, les poids d'origine décident)"
            },
        )
        if (state.enabledProfiles.isNotEmpty() && off.isNotEmpty()) echo("  désactivé pour ${off.joinToString()} (montage.learnTaste: false)")
        if (state.enabledProfiles.isNotEmpty()) echo("  pour revenir aux poids d'origine : app gouts --reset")
        echo("")
        echo("Poids des critères (somme constante ; bornés entre ×${TasteLearner.MIN_FACTOR} et ×${TasteLearner.MAX_FACTOR} de l'origine) :")
        echo("  %-10s %8s %8s %8s".format("critère", "origine", "appris", "écart"))
        learned.base.keys.sortedByDescending { learned.weights.getValue(it) / learned.base.getValue(it) }.forEach { k ->
            val b = learned.base.getValue(k)
            val w = learned.weights.getValue(k)
            val change = (w / b - 1) * 100
            echo("  %-10s %8.3f %8.3f %+7.0f %%".format(k, b, w, change))
        }
        echo("")
        echo("Accord avec les choix (variante rendue = la mieux notée) : %.0f %% avec les poids d'origine, %.0f %% avec les poids appris"
            .format(learned.agreementBefore * 100, learned.agreementAfter * 100))
        val groups = state.groups
        echo("")
        echo(
            "Préférences de groupes (corrections de l'éditeur, bonus en kills ajouté au rang ; actives à partir de ${GroupTasteLearner.MIN_EDITS} corrections) : " +
                if (groups.active) "actives, ${groups.edits} corrections" else "${groups.edits} correction(s) sur ${GroupTasteLearner.MIN_EDITS}, pas encore actives",
        )
        GroupTrait.entries.forEach { t ->
            val bonus = groups.bonus[t]
            echo("  %-10s %3d écarté(s) ou gardé(s)  %s".format(t.key, groups.actions[t] ?: 0, if (bonus == null) "-" else "%+.2f kill".format(bonus)))
        }
        val timing = state.timing
        echo("")
        echo(
            "Calages à l'image (${timing.corrections} kill(s) calé(s) ; une source à partir de ${KillTimingLearner.MIN_SOURCE} calages concordants, " +
                "un morceau à partir de ${KillTimingLearner.MIN_SONG}) :",
        )
        if (timing.isEmpty) echo("  rien encore (frise du plan, ou montage --anchor / --beat-shift)")
        timing.sources.forEach { o ->
            echo("  détection %-40s %+5d ms  %2d calage(s), écart %3d ms  %s".format(o.key, o.offset.inWholeMilliseconds, o.count, o.spread.inWholeMilliseconds, if (o.active) "appliqué" else "pas encore"))
        }
        timing.songs.forEach { o ->
            echo("  morceau   %-40s %+5d ms  %2d calage(s), écart %3d ms  %s".format(o.key.substringBefore('|'), o.offset.inWholeMilliseconds, o.count, o.spread.inWholeMilliseconds, if (o.active) "appliqué" else "pas encore"))
        }
        if (!timing.isEmpty) echo("  pour les oublier : app gouts --reset-timing")
        val last = state.last ?: return
        echo("")
        echo("Dernier choix (${last.at}, ${Path.of(last.music).fileName}) : variante ${last.chosen + 1} rendue")
        val before = state.lastBefore.orEmpty()
        val after = state.lastAfter.orEmpty()
        fun rank(notes: List<Double>, i: Int) = notes.count { it > notes[i] } + 1
        last.variants.indices.forEach { i ->
            val mark = if (i == last.chosen) "  <- choisie" else ""
            echo("  variante %d : note %.3f (rang %d) avec l'origine, %.3f (rang %d) appris%s".format(i + 1, before[i], rank(before, i), after[i], rank(after, i), mark))
        }
        val firstBefore = before.indices.maxBy { before[it] }
        val firstAfter = after.indices.maxBy { after[it] }
        echo(
            if (firstBefore == firstAfter) "  Même variante en tête avec les deux : rien ne changerait sur ce montage."
            else "  Avec les poids appris, la variante ${firstAfter + 1} passerait devant la ${firstBefore + 1}" +
                (if (firstAfter == last.chosen) " (celle qui a été choisie)." else "."),
        )
        if (learned.base != MontageScorer.WEIGHTS) echo("  (poids d'origine différents de ceux du moteur)")
    }
}
