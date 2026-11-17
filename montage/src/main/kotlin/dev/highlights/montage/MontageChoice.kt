package dev.highlights.montage

import dev.highlights.core.serialization.roundTo
import dev.highlights.core.serialization.toTimecode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Variantes d'un montage proposées en aperçu : `<jeu>_<date>_killmontage_variantes.json`, à côté des aperçus
 * (dossier `apercus` de la sortie). Format stable, versionné ([version]) : relu par `montage --pick` pour rendre la
 * variante choisie, et destiné à un futur apprentissage des choix de l'utilisateur.
 *
 * - [sessions] : captures (chemins des vidéos) dont viennent les kills ; [music] : musique utilisée (fichier, même
 *   quand elle a été choisie dans un dossier) ; [profile] : jeu.
 * - [variants] : de la mieux notée (index 1, celle que le montage aurait rendue sans aperçu) à la moins bien notée.
 */
@Serializable
data class MontageVariantsFile(
    val version: Int = VERSION,
    val createdAt: String,
    val profile: String,
    val music: String,
    val sessions: List<String>,
    val variants: List<MontageVariantEntry>,
) {
    companion object {
        const val VERSION = 1
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun load(file: Path): MontageVariantsFile = json.decodeFromString(serializer(), file.readText())

        /** Écriture atomique (.tmp puis déplacement) : un fichier relu par `--pick` n'est jamais à moitié écrit. */
        fun save(file: Path, value: MontageVariantsFile) = write(file, json.encodeToString(serializer(), value))

        internal fun write(file: Path, text: String) {
            val temp = file.resolveSibling(file.name + ".tmp")
            temp.writeText(text)
            temp.moveTo(file, overwrite = true)
        }
    }
}

/**
 * Une variante proposée.
 * - [index] : rang de 1 (mieux notée) à n ; [variant] : réglage du planificateur qui l'a produite (échelle de la grille,
 *   null = choisie par la grille elle-même ; décalage de la drop), [label] sa forme lisible.
 * - [signature] : empreinte du montage ([MontageVariants.signature]) ; la même au rendu final, sinon il est refusé.
 * - [score] : note complète ([MontageScorer]), critère par critère, pour apprendre ce qui fait choisir une variante.
 * - [distance] : écart ([MontageVariants.distance]) à la plus proche des variantes mieux classées ; null pour la première.
 * - [preview] / [report] : aperçu vidéo et son rapport ([MontageReport]) ; null si l'aperçu n'a pas été rendu.
 */
@Serializable
data class MontageVariantEntry(
    val index: Int,
    val scale: Double? = null,
    val dropShift: Double = 0.0,
    val label: String,
    val signature: String,
    val score: MontageScore,
    val clips: Int,
    val kills: Int,
    val durationSeconds: Double,
    val musicStart: String,
    val distance: Double? = null,
    val preview: String? = null,
    val report: String? = null,
) {
    companion object {
        fun of(index: Int, plan: MontagePlan, earlier: List<MontagePlan>, preview: Path? = null, report: Path? = null) = MontageVariantEntry(
            index = index,
            scale = plan.variant.scale,
            dropShift = plan.variant.dropShift,
            label = plan.variant.toString(),
            signature = MontageVariants.signature(plan),
            score = MontageScorer.score(plan),
            clips = plan.clips.size,
            kills = plan.clips.sumOf { it.kills.size },
            durationSeconds = (plan.duration.inWholeMilliseconds / 1000.0).roundTo(3),
            musicStart = plan.musicStart.toTimecode(),
            distance = earlier.minOfOrNull { MontageVariants.distance(it, plan) }?.roundTo(3),
            preview = preview?.toString(),
            report = report?.toString(),
        )
    }
}

/**
 * Choix de l'utilisateur après les aperçus : `<rapport>.choix.json`, à côté du rapport du rendu final. Ce qu'un
 * apprentissage des choix lira : quelle variante a été préférée ([chosen], son [signature]) face à quelles autres
 * ([variants], avec toutes leurs notes), et ce qui a été rendu ([outputs], [report]).
 */
@Serializable
data class MontageChoiceRecord(
    val version: Int = MontageVariantsFile.VERSION,
    val chosenAt: String,
    val chosen: Int,
    val signature: String,
    val variantsFile: String? = null,
    val profile: String,
    val music: String,
    val variants: List<MontageVariantEntry>,
    val outputs: List<String>,
    val report: String,
) {
    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        /** Fichier du choix pour le rapport [report] du rendu final : `x.json` → `x.choix.json`. */
        fun fileFor(report: Path): Path = report.resolveSibling(report.name.removeSuffix(".json") + ".choix.json")

        fun load(file: Path): MontageChoiceRecord = json.decodeFromString(serializer(), file.readText())

        fun save(file: Path, value: MontageChoiceRecord) = MontageVariantsFile.write(file, json.encodeToString(serializer(), value))

        fun now(): String = Instant.now().toString()
    }
}
