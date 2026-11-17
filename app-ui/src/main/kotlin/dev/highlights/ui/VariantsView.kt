package dev.highlights.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import dev.highlights.pipeline.MontagePreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.skia.Image as SkiaImage

/**
 * Aperçus des variantes d'un montage kills ([dev.highlights.pipeline.HighlightPipeline.montagePreviews]) : l'utilisateur
 * les regarde, puis rend celle qu'il préfère en pleine qualité. [requested] : nombre demandé ; moins de cartes quand les
 * variantes ne se distinguent pas assez.
 */
data class VariantsUiState(val cards: List<VariantCard>, val requested: Int, val variantsFile: Path) {
    companion object {
        fun of(previews: MontagePreviews, requested: Int) = VariantsUiState(
            previews.previews.map { p ->
                VariantCard(p.entry.index, p.entry.score.total, p.entry.clips, p.entry.kills, p.entry.durationSeconds, p.video, p.frames)
            },
            requested,
            previews.variantsFile,
        )
    }
}

/** Une variante : son rang ([index], 1 = la mieux notée), sa note, sa vidéo d'aperçu et les images de sa vignette animée. */
data class VariantCard(
    val index: Int,
    val score: Double,
    val clips: Int,
    val kills: Int,
    val durationSeconds: Double,
    val video: Path,
    val frames: List<Path>,
)

/** Fenêtre des aperçus : une carte par variante, côte à côte, chacune lisible et rendable. */
@Composable
fun VariantsWindow(variants: VariantsUiState, busy: Boolean, actions: UiActions) {
    DialogWindow(
        onCloseRequest = actions::closeVariants,
        state = rememberDialogState(size = DpSize((300 * variants.cards.size + 80).coerceAtLeast(560).dp, 860.dp)),
        title = tr("ui.variants.title"),
    ) {
        HighlightsTheme {
            Column(
                Modifier.fillMaxSize().background(Palette.background).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("ui.variants.title"), style = MaterialTheme.typography.titleLarge)
                Text(tr("ui.variants.intro"), style = MaterialTheme.typography.bodyMedium, color = Palette.textMuted)
                if (variants.cards.size < variants.requested) {
                    Text(tr("ui.variants.fewer", "count" to variants.cards.size), style = MaterialTheme.typography.bodySmall, color = Palette.accent)
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    variants.cards.forEach { card -> VariantCardView(card, busy, actions, Modifier.weight(1f)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions::closeVariants) { Text(tr("ui.common.close")) }
                }
            }
        }
    }
}

@Composable
private fun VariantCardView(card: VariantCard, busy: Boolean, actions: UiActions, modifier: Modifier) {
    Column(
        modifier.border(BorderStroke(1.dp, if (card.index == 1) Palette.accent else Palette.outline), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = tr("ui.variants.version", "index" to card.index) + if (card.index == 1) " · " + tr("ui.variants.best") else ""
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (card.index == 1) Palette.accent else Palette.text)
        Box(Modifier.weight(1f).fillMaxWidth().background(Palette.surface, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            AnimatedFrames(card.frames, tr("ui.variants.version", "index" to card.index))
        }
        Text(tr("ui.variants.score", "score" to I18n.decimal(card.score, 3)), style = MaterialTheme.typography.bodyMedium)
        Text(
            listOf(
                tr("ui.variants.clips", "count" to card.clips),
                I18n.textOrNull("event.kill", mapOf("count" to card.kills)) ?: card.kills.toString(),
                I18n.duration(kotlin.time.Duration.parse("${card.durationSeconds}s")),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.open(card.video) }) { Text(tr("ui.variants.play")) }
            Button(onClick = { actions.renderVariant(card.index) }, enabled = !busy) { Text(tr("ui.variants.render")) }
        }
    }
}

/** Bouton du dialogue de montage : aperçus de quelques variantes au lieu du rendu final. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PreviewVariantsButton(montage: MontageUiState, actions: UiActions) {
    TooltipArea(
        tooltip = {
            Text(
                tr("ui.montage.previewsHint"),
                modifier = Modifier.background(Palette.surfaceHigh, RoundedCornerShape(6.dp)).padding(10.dp).widthIn(max = 320.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        },
    ) {
        OutlinedButton(onClick = actions::previewMontage, enabled = montage.canCreate) { Text(tr("ui.montage.previews")) }
    }
}

/** Vignette animée : les images de l'aperçu (une par demi-seconde) défilent en boucle, deux fois plus vite que le montage. */
@Composable
private fun AnimatedFrames(frames: List<Path>, description: String) {
    var images by remember(frames) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var shown by remember(frames) { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        images = withContext(Dispatchers.IO) {
            frames.mapNotNull { runCatching { SkiaImage.makeFromEncoded(Files.readAllBytes(it)).toComposeImageBitmap() }.getOrNull() }
        }
        while (images.size > 1) {
            delay(FRAME_INTERVAL)
            shown = (shown + 1) % images.size
        }
    }
    images.getOrNull(shown)?.let { Image(it, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        ?: Box(Modifier.height(120.dp))
}

/** Une image de vignette sur deux secondes de montage par seconde : le rythme se devine, les scènes se reconnaissent. */
private val FRAME_INTERVAL = 250.milliseconds
