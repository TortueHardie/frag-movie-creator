package dev.highlights.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Texte écrit en dur au lieu de passer par les bundles (`tr("ui.…")`) : toute chaîne de app-ui qui ressemble à une
 * phrase (deux mots ou plus) ou qui contient une lettre accentuée est signalée, avec son fichier et sa ligne. Les
 * commentaires, les journaux (`log.`) et la liste [ALLOWED] (textes qui ne s'affichent pas) sont ignorés. Dans les
 * autres modules, une exception métier construite d'un texte brut (`InputException("…")`) est signalée aussi : son
 * message atteint l'interface, il doit être un `Message` traduisible.
 */
class HardcodedTextTest : FunSpec({
    val root = Path.of("..").toAbsolutePath().normalize()

    test("aucun texte d'interface écrit en dur dans app-ui") {
        val dir = root.resolve("app-ui/src/main/kotlin")
        // Le pont de pilotage (`ui/bridge/`) ne parle qu'aux outils de développement, jamais à l'utilisateur.
        val files = Files.walk(dir).use { w -> w.filter { it.extension == "kt" && "bridge" !in dir.relativize(it).map { p -> p.name } }.toList() }
        val found = files.flatMap { file ->
            KotlinStrings.literals(file.readText())
                .filter { (_, text, lineText) -> looksLikeText(text) && "log." !in lineText && ALLOWED.none { it in lineText || it in text } }
                .map { (line, text, _) -> "${file.name}:$line  \"$text\"" }
        }
        found.shouldBeEmpty()
    }

    test("aucune exception métier construite d'un texte brut hors de app-ui") {
        val raw = Regex("""(HighlightsException|InputException|ConfigException)\(\s*"""")
        val found = Files.list(root).use { s -> s.toList() }
            .filter { it.name != "app-cli" }
            .map { it.resolve("src/main/kotlin") }.filter { it.isDirectory() }
            .flatMap { src -> Files.walk(src).use { w -> w.filter { it.extension == "kt" }.toList() } }
            .flatMap { file -> file.readText().lines().mapIndexedNotNull { i, l -> if (raw.containsMatchIn(l)) "${root.relativize(file)}:${i + 1}" else null } }
        found.shouldBeEmpty()
    }
}) {
    companion object {
        /**
         * Chaînes qui ne s'affichent pas (repérées par leur ligne ou leur contenu) : raisons d'annulation (journaux),
         * commentaire du manifeste d'installation, script PowerShell de mise à jour.
         */
        val ALLOWED = listOf(
            "CancellationException(",
            "next.store(it,",
            "INSTALLDIR=",
            "Start-Process",
        )

        private val SENTENCE = Regex("""\p{L}{2,}[,:;]? +\p{L}{2,}""")
        private val ACCENT = Regex("""[À-ÿŒœ]""")

        fun looksLikeText(text: String): Boolean {
            // Une clé de traduction ou un motif technique n'a pas d'espace ; les gabarits ${…} ne comptent pas.
            val plain = text.replace(Regex("""\$\{[^}]*}|\$\w+"""), " ")
            return SENTENCE.containsMatchIn(plain) || ACCENT.containsMatchIn(plain)
        }
    }
}

/** Extraction des chaînes d'un fichier Kotlin, hors commentaires : (ligne, contenu, texte de la ligne). */
object KotlinStrings {
    fun literals(source: String): List<Triple<Int, String, String>> {
        val lines = source.lines()
        val out = mutableListOf<Triple<Int, String, String>>()
        var i = 0
        var line = 1
        fun peek(k: Int) = source.getOrNull(i + k)
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\n' -> { line++; i++ }
                c == '/' && peek(1) == '/' -> { while (i < source.length && source[i] != '\n') i++ }
                c == '/' && peek(1) == '*' -> {
                    i += 2
                    while (i < source.length && !(source[i] == '*' && peek(1) == '/')) { if (source[i] == '\n') line++; i++ }
                    i += 2
                }
                c == '\'' -> {
                    i++
                    while (i < source.length && source[i] != '\'') { if (source[i] == '\\') i++; i++ }
                    i++
                }
                c == '"' -> {
                    val raw = peek(1) == '"' && peek(2) == '"'
                    val start = line
                    i += if (raw) 3 else 1
                    val text = StringBuilder()
                    var depth = 0
                    while (i < source.length) {
                        val ch = source[i]
                        if (depth == 0 && raw && ch == '"' && peek(1) == '"' && peek(2) == '"') { i += 3; break }
                        if (depth == 0 && !raw && ch == '"') { i++; break }
                        if (!raw && ch == '\\') { text.append(ch).append(peek(1) ?: ' '); i += 2; continue }
                        if (ch == '$' && peek(1) == '{') depth++
                        if (depth > 0 && ch == '}') depth--
                        if (ch == '\n') line++
                        // Une chaîne imbriquée dans un gabarit ${…} n'est pas du texte de celle-ci.
                        if (depth > 0 && ch == '"') { i++; while (i < source.length && source[i] != '"') i++; i++; continue }
                        text.append(ch)
                        i++
                    }
                    out += Triple(start, text.toString(), lines.getOrElse(start - 1) { "" })
                }
                else -> i++
            }
        }
        return out
    }
}
