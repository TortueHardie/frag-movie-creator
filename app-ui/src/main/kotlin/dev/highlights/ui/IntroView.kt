package dev.highlights.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.i18n.tr
import dev.highlights.core.model.CardAnimation
import dev.highlights.core.model.CardBackground
import dev.highlights.core.model.IntroOutroSettings
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.session.SessionStore
import dev.highlights.editing.CardStill
import dev.highlights.pipeline.HighlightPipeline
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Fenêtre « Ouverture et fin » : quelle carte et quel format l'aperçu montre, l'image d'aperçu ([preview], relue à
 * chaque [previewVersion]), et une erreur de rendu de l'aperçu (logo illisible…).
 */
data class IntroUiState(
    val previewFormat: OutputFormat = OutputFormat.LANDSCAPE,
    val previewOutro: Boolean = false,
    val preview: Path? = null,
    val previewVersion: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Cartes choisies dans l'application (`~/.highlights/intro.json`) : une fois enregistrées, elles remplacent celles
 * du profil et d'app.yaml pour les exports lancés depuis l'application. Rien n'est écrit tant qu'on n'y touche pas.
 */
class IntroPrefsStore(private val file: Path) {
    fun load(): IntroOutroSettings? = runCatching {
        if (!file.exists()) null else SessionStore.json.decodeFromString(IntroOutroSettings.serializer(), file.readText())
    }.onFailure { log.warn { "Cartes d'ouverture et de fin illisibles ($file) : ${it.message}" } }.getOrNull()

    fun save(settings: IntroOutroSettings) {
        runCatching {
            file.parent?.createDirectories()
            file.writeText(SessionStore.json.encodeToString(IntroOutroSettings.serializer(), settings))
        }.onFailure { log.warn { "Cartes d'ouverture et de fin non enregistrées ($file) : ${it.message}" } }
    }

    companion object {
        val DEFAULT_FILE: Path = Path.of(System.getProperty("user.home"), ".highlights", "intro.json")
    }
}

/**
 * Logique de la section « Ouverture et fin », appelée par [AppController] : réglages retenus, choix des images, aperçu
 * régénéré peu après chaque changement (le pseudo se tape lettre par lettre : on attend qu'il se pose).
 */
internal class IntroController(
    private val scope: CoroutineScope,
    private val platform: Platform,
    private val prefs: IntroPrefsStore,
    private val state: MutableStateFlow<UiState>,
    private val pipeline: () -> HighlightPipeline?,
) {
    private var previewJob: Job? = null

    /** Réglages de départ : ceux de l'application s'il y en a, sinon ceux d'app.yaml. */
    fun load() {
        val saved = prefs.load()
        val base = saved ?: pipeline()?.introSettings() ?: IntroOutroSettings()
        state.update { it.copy(introCards = base, introCustom = saved != null) }
    }

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
        // Rien à montrer : la fenêtre s'ouvre pour choisir un logo ou un pseudo.
        if (enabled && !state.value.introCards.hasContent) open()
    }

    fun open() {
        state.update { it.copy(introDialog = it.introDialog ?: IntroUiState()) }
        schedulePreview(immediate = true)
    }

    fun close() {
        previewJob?.cancel()
        state.update { it.copy(introDialog = null) }
    }

    fun update(change: (IntroOutroSettings) -> IntroOutroSettings) {
        val before = state.value.introCards
        val next = runCatching { change(before) }.getOrElse { return }
        if (next == before) return
        state.update { it.copy(introCards = next, introCustom = true) }
        prefs.save(next)
        if (state.value.introDialog != null) schedulePreview(immediate = next.name == before.name)
    }

    fun chooseLogo() {
        platform.chooseImage(state.value.introCards.logo?.let { Path.of(it).parent })?.let { file ->
            update { it.copy(logo = file.toAbsolutePath().toString()) }
        }
    }

    fun chooseImage() {
        platform.chooseImage(state.value.introCards.image?.let { Path.of(it).parent })?.let { file ->
            update { it.copy(image = file.toAbsolutePath().toString(), background = CardBackground.IMAGE) }
        }
    }

    fun setPreview(format: OutputFormat, outro: Boolean) {
        state.update { it.copy(introDialog = it.introDialog?.copy(previewFormat = format, previewOutro = outro)) }
        schedulePreview(immediate = true)
    }

    /** Options d'export : les cartes de l'application une fois réglées ici, sinon la configuration décide. */
    fun exportChoice(): Pair<Boolean?, IntroOutroSettings?> {
        val s = state.value
        return if (s.introCustom) s.introCards.enabled to s.introCards else null to null
    }

    private fun schedulePreview(immediate: Boolean) {
        previewJob?.cancel()
        val dialog = state.value.introDialog ?: return
        val settings = state.value.introCards
        if (!settings.hasContent) {
            state.update { it.copy(introDialog = it.introDialog?.copy(preview = null, busy = false, error = null)) }
            return
        }
        val p = pipeline() ?: return
        // Fond flouté : une image du meilleur moment de l'analyse ouverte, s'il y en a une.
        val still = state.value.session?.segments?.maxByOrNull { it.highlight.score }?.let { segment ->
            val media = state.value.session!!.entries[segment.entry].session.media
            CardStill(media.path, segment.highlight.peak)
        }
        previewJob = scope.launch {
            if (!immediate) delay(PREVIEW_DELAY)
            state.update { it.copy(introDialog = it.introDialog?.copy(busy = true, error = null)) }
            try {
                val name = "intro_${if (dialog.previewOutro) "outro" else "intro"}_${dialog.previewFormat.name.lowercase()}.png"
                val image = withContext(Dispatchers.IO) {
                    p.introPreview(settings.copy(enabled = true), dialog.previewFormat, !dialog.previewOutro, p.previewDir.resolve(name), still)
                }
                state.update { it.copy(introDialog = it.introDialog?.copy(preview = image, previewVersion = System.nanoTime(), busy = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "Aperçu de la carte impossible : ${e.message}" }
                state.update { it.copy(introDialog = it.introDialog?.copy(busy = false, error = e.localizedMessage ?: e.toString())) }
            }
        }
    }

    private companion object {
        val PREVIEW_DELAY = 600.milliseconds
    }
}

/** Ligne de la section des réglages : cartes d'ouverture et de fin, et le bouton qui ouvre leurs réglages. */
@Composable
fun IntroSection(state: UiState, actions: UiActions) {
    val cards = state.introCards
    Text(tr("ui.intro.section"), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable { actions.setIntroEnabled(!cards.enabled) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = cards.enabled, onCheckedChange = actions::setIntroEnabled)
            Text(tr("ui.intro.enabled"), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = actions::openIntro, enabled = state.config is ConfigStatus.Ready) { Text(tr("ui.intro.customize")) }
    }
    if (cards.enabled) {
        Text(
            cards.name?.takeIf { it.isNotBlank() }?.let { tr("ui.intro.summaryName", "name" to it) } ?: tr("ui.intro.summaryLogo"),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )
    }
}

/** Réglages des cartes d'ouverture et de fin, avec leur aperçu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntroDialog(dialog: IntroUiState, cards: IntroOutroSettings, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::closeIntro,
        title = { Text(tr("ui.intro.title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("ui.intro.hint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)

                // Aperçu : la carte choisie, dans le format choisi.
                val vertical = dialog.previewFormat == OutputFormat.VERTICAL
                Box(
                    Modifier.fillMaxWidth().height(if (vertical) 300.dp else 220.dp).background(Palette.surfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = rememberImage(dialog.preview, dialog.previewVersion)
                    when {
                        !cards.hasContent -> Text(tr("ui.intro.empty"), color = Palette.textMuted, style = MaterialTheme.typography.bodySmall)
                        image != null -> Image(image, contentDescription = tr("ui.intro.preview"), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        dialog.error != null -> Text(dialog.error, color = Palette.danger, style = MaterialTheme.typography.bodySmall)
                        else -> CircularProgressIndicator()
                    }
                    if (dialog.busy && image != null) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.TopEnd))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf(false to tr("ui.intro.showIntro"), true to tr("ui.intro.showOutro")).forEachIndexed { i, (outro, label) ->
                            SegmentedButton(
                                selected = dialog.previewOutro == outro,
                                onClick = { actions.setIntroPreview(dialog.previewFormat, outro) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf(OutputFormat.LANDSCAPE, OutputFormat.VERTICAL).forEachIndexed { i, format ->
                            SegmentedButton(
                                selected = dialog.previewFormat == format,
                                onClick = { actions.setIntroPreview(format, dialog.previewOutro) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(format.label) }
                        }
                    }
                }

                DialogGroup(tr("ui.intro.identity")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("ui.intro.logo"), Modifier.width(110.dp))
                        Text(
                            cards.logo?.let { Path.of(it).name } ?: tr("ui.intro.noLogo"),
                            modifier = Modifier.weight(1f),
                            color = if (cards.logo == null) Palette.textMuted else Palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (cards.logo != null) TextButton(onClick = { actions.updateIntro { it.copy(logo = null) } }) { Text(tr("ui.intro.remove")) }
                        OutlinedButton(onClick = actions::chooseIntroLogo) { Text(tr("ui.common.chooseEllipsis")) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("ui.intro.name"), Modifier.width(110.dp))
                        OutlinedTextField(
                            value = cards.name.orEmpty(),
                            onValueChange = { v -> actions.updateIntro { it.copy(name = v.take(MAX_NAME).ifEmpty { null }) } },
                            singleLine = true,
                            placeholder = { Text(tr("ui.intro.namePlaceholder")) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                DialogGroup(tr("ui.intro.background")) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val choices = listOf(
                            CardBackground.BLUR to tr("ui.intro.backgroundBlur"),
                            CardBackground.COLOR to tr("ui.intro.backgroundColor"),
                            CardBackground.IMAGE to tr("ui.intro.backgroundImage"),
                        )
                        choices.forEachIndexed { i, (background, label) ->
                            SegmentedButton(
                                selected = cards.background == background,
                                onClick = { if (background == CardBackground.IMAGE && cards.image == null) actions.chooseIntroImage() else actions.updateIntro { it.copy(background = background) } },
                                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                            ) { Text(label) }
                        }
                    }
                    when (cards.background) {
                        CardBackground.BLUR -> Text(tr("ui.intro.blurHint"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                        CardBackground.COLOR -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SWATCHES.forEach { hex ->
                                Box(
                                    Modifier.size(28.dp).background(androidx.compose.ui.graphics.Color(0xFF000000 or hex.drop(1).toLong(16)))
                                        .clickable { actions.updateIntro { it.copy(color = hex) } },
                                    contentAlignment = Alignment.Center,
                                ) { if (cards.color.equals(hex, ignoreCase = true)) Text("✓", color = Palette.text) }
                            }
                        }
                        CardBackground.IMAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                cards.image?.let { Path.of(it).name } ?: tr("ui.intro.noImage"),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            OutlinedButton(onClick = actions::chooseIntroImage) { Text(tr("ui.common.chooseEllipsis")) }
                        }
                    }
                }

                DialogGroup(tr("ui.intro.animation")) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val choices = listOf(
                            CardAnimation.RISE to tr("ui.intro.animationRise"),
                            CardAnimation.FLASH to tr("ui.intro.animationFlash"),
                            CardAnimation.ZOOM to tr("ui.intro.animationZoom"),
                            CardAnimation.NONE to tr("ui.intro.animationNone"),
                        )
                        choices.forEachIndexed { i, (animation, label) ->
                            SegmentedButton(
                                selected = cards.animation == animation,
                                onClick = { actions.updateIntro { it.copy(animation = animation) } },
                                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                            ) { Text(label) }
                        }
                    }
                    val hint = when (cards.animation) {
                        CardAnimation.RISE -> tr("ui.intro.animationRiseHint")
                        CardAnimation.FLASH -> tr("ui.intro.animationFlashHint")
                        CardAnimation.ZOOM -> tr("ui.intro.animationZoomHint")
                        CardAnimation.NONE -> tr("ui.intro.animationNoneHint")
                    }
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                }

                DialogGroup(tr("ui.intro.cards")) {
                    MontageToggle(tr("ui.intro.useIntro"), cards.intro) { v -> actions.updateIntro { it.copy(intro = v) } }
                    MontageToggle(tr("ui.intro.useOutro"), cards.outro) { v -> actions.updateIntro { it.copy(outro = v) } }
                    Row(Modifier.fillMaxWidth().clickable { actions.setIntroEnabled(!cards.enabled) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = cards.enabled, onCheckedChange = actions::setIntroEnabled)
                        Text(tr("ui.intro.enabled"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions::closeIntro) { Text(tr("ui.common.close")) } },
    )
}

/** Couleurs de fond proposées : sombres, pour que le pseudo blanc se lise. */
private val SWATCHES = listOf("#101820", "#1B1035", "#2A0F14", "#0F2A1E", "#0B1F3A", "#202020")

private const val MAX_NAME = 32
