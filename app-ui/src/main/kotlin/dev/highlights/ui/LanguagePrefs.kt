package dev.highlights.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.core.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/** Langue choisie dans l'application (tag d'un fichier de traduction : `fr`, `pt_BR`) ; absente = celle de Windows. */
@Serializable
data class LanguagePrefs(val language: String)

/** `~/.highlights/language.json`, lu au démarrage (avant la première fenêtre) et écrit à chaque changement. */
class LanguagePrefsStore(private val file: Path) {
    fun load(): String? = runCatching {
        if (!file.exists()) null else SessionStore.json.decodeFromString(LanguagePrefs.serializer(), file.readText()).language
    }.onFailure { log.warn { "Langue choisie illisible ($file) : ${it.message}" } }.getOrNull()

    fun save(tag: String?) {
        runCatching {
            if (tag == null) {
                file.deleteIfExists()
            } else {
                file.parent?.createDirectories()
                file.writeText(SessionStore.json.encodeToString(LanguagePrefs.serializer(), LanguagePrefs(tag)))
            }
        }.onFailure { log.warn { "Langue non enregistrée ($file) : ${it.message}" } }
    }

    companion object {
        val DEFAULT_FILE: Path = Path.of(System.getProperty("user.home"), ".highlights", "language.json")
    }
}

/**
 * Sélecteur de langue de l'en-tête : « Système » (langue de Windows) puis chaque langue dont un fichier de traduction
 * existe, sous son propre nom. Le choix s'applique tout de suite et reste d'un lancement à l'autre.
 */
@Composable
internal fun LanguagePicker(current: String, actions: UiActions) {
    var expanded by remember { mutableStateOf(false) }
    val languages = I18n.available()
    val currentTag = I18n.fileTag(Locale.forLanguageTag(current))
    Box {
        TextButton(onClick = { expanded = true }) {
            Text((languages.firstOrNull { it.tag == currentTag }?.name ?: current) + " ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(tr("ui.language.system")) }, onClick = { expanded = false; actions.setLanguage(null) })
            languages.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.name) }, onClick = { expanded = false; actions.setLanguage(lang.tag) })
            }
        }
    }
}
