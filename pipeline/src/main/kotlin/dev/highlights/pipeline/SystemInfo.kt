package dev.highlights.pipeline

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * Machine sur laquelle tourne l'application, pour le rapport de problème et le contrôle de mémoire : version de
 * Windows (numéro de build compris : « Windows 11 » seul ne dit pas quelle mise à jour), mémoire physique, carte
 * graphique, JVM. Les commandes système ont un délai court et un repli : un rapport doit pouvoir se faire même quand
 * PowerShell est bloqué.
 */
object SystemInfo {
    private val os get() = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    /** Mémoire physique totale, en octets (0 si inconnue). */
    fun totalMemory(): Long = os?.totalMemorySize ?: 0

    /**
     * Mémoire physique disponible, en octets ; sous Windows c'est la mémoire « disponible » du Gestionnaire des tâches
     * (libre + cache vidable). Null si la JVM ne la donne pas : aucun contrôle n'est alors fait.
     */
    fun availableMemory(): Long? = os?.freeMemorySize?.takeIf { it > 0 }

    /** « Windows 11 (10.0.26300.1234) » : nom donné par Java, build lu par `ver`. */
    fun windowsVersion(): String {
        val name = System.getProperty("os.name")
        val build = run("cmd", "/c", "ver")?.let { Regex("""\d+\.\d+\.\d+(\.\d+)?""").find(it)?.value }
        return if (build != null) "$name ($build)" else "$name ${System.getProperty("os.version")}"
    }

    /** Cartes graphiques et version de leur pilote, une par ligne ; vide si inconnues. */
    fun graphics(): List<String> =
        run(
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "Get-CimInstance Win32_VideoController | ForEach-Object { \$_.Name + ' | pilote ' + \$_.DriverVersion }",
        )?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    fun processor(): String? = System.getenv("PROCESSOR_IDENTIFIER")

    /** Rubrique « système » du rapport, en lignes « clé : valeur » (journal du développeur : en français). */
    fun describe(version: String, language: String): List<String> {
        val rt = Runtime.getRuntime()
        val mb = 1L shl 20
        return buildList {
            add("application : Highlights $version")
            add("langue de l'interface : $language")
            add("système : ${windowsVersion()} (${System.getProperty("os.arch")})")
            processor()?.let { add("processeur : $it, ${rt.availableProcessors()} cœurs logiques") }
            add("mémoire physique : ${totalMemory() / mb} Mo, disponible ${availableMemory()?.let { "${it / mb} Mo" } ?: "inconnue"}")
            add("tas Java : max ${rt.maxMemory() / mb} Mo, réservé ${rt.totalMemory() / mb} Mo, utilisé ${(rt.totalMemory() - rt.freeMemory()) / mb} Mo")
            add("Java : ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
            add("options de la JVM : ${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")}")
            val gpus = graphics()
            if (gpus.isEmpty()) add("carte graphique : inconnue") else gpus.forEach { add("carte graphique : $it") }
        }
    }

    /**
     * État de l'application au moment du rapport : configuration (fichier, ou pourquoi elle n'a pas chargé), profils
     * (« id (nom) »), profil en cours, ce qui est ouvert, traitement en cours.
     */
    fun appState(
        configFile: java.nio.file.Path?,
        configProblem: String?,
        profiles: List<String>,
        currentProfile: String?,
        sources: Int,
        sessions: Int,
        job: String?,
    ): List<String> = buildList {
        when {
            configFile != null -> add("configuration : $configFile")
            configProblem != null -> add("configuration en échec : $configProblem")
            else -> add("configuration : en cours de chargement")
        }
        if (profiles.isNotEmpty()) add("profils : ${profiles.joinToString()}")
        currentProfile?.let { add("profil en cours : $it") }
        add("captures ouvertes : $sources, analyses ouvertes : $sessions")
        job?.let { add("traitement en cours : $it") }
    }

    /** Diagnostic de [HighlightPipeline.diagnostics] en texte, ou pourquoi il manque. */
    suspend fun diagnostics(pipeline: HighlightPipeline?): String = when (pipeline) {
        null -> "diagnostic absent : configuration non chargée"
        else -> runCatching { pipeline.diagnostics()?.render() ?: "diagnostic indisponible" }.getOrElse { "diagnostic en échec : $it" }
    }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        // Attendre avant de lire : une lecture bloquerait sans fin sur une commande figée. Les sorties tiennent en
        // quelques lignes, bien en dessous du tampon du tube.
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
