package dev.highlights.ui

import dev.highlights.core.i18n.I18n
import dev.highlights.core.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.EditStyle
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.aspectLabel
import dev.highlights.core.serialization.toTimecode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun AppHeader(config: ConfigStatus, language: String, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).border(BorderStroke(5.dp, Palette.accent), RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text("Highlights", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            HelpMenu(actions)
            LanguagePicker(language, actions)
        }
        when (config) {
            ConfigStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("ui.header.loading"), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
            is ConfigStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("ui.header.profiles", "count" to config.profiles.size, "dir" to config.configFile.parent),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions::reloadConfig) { Text(tr("ui.header.reload")) }
            }
            is ConfigStatus.Failed -> Column {
                Text(config.message, style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                TextButton(onClick = actions::reloadConfig) { Text(tr("ui.common.retry")) }
            }
        }
    }
}

/**
 * Menu d'aide « ? » de l'en-tête : signaler un problème, ouvrir le dossier des journaux. Un menu plutôt que des boutons
 * dans l'en-tête : en russe ou en allemand, deux boutons de plus y rognaient le chemin de la configuration.
 */
@Composable
private fun HelpMenu(actions: UiActions) {
    var expanded by remember { mutableStateOf(false) }
    val help = tr("ui.header.help")
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = help }) {
            Text("?", fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(tr("ui.header.report")) }, onClick = { expanded = false; actions.openReport() })
            DropdownMenuItem(text = { Text(tr("ui.header.logs")) }, onClick = { expanded = false; actions.openLogsFolder() })
        }
    }
}

@Composable
fun SourceSection(state: UiState, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(if (state.sources.size > 1) tr("ui.source.titleMany", "count" to state.sources.size) else tr("ui.source.title"))
        val source = state.source
        if (source == null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Palette.background,
                border = BorderStroke(1.dp, Palette.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("ui.source.dropHint"), color = Palette.textMuted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions::chooseSource, enabled = state.job == null && state.config is ConfigStatus.Ready) {
                        Text(tr("ui.source.choose"))
                    }
                }
            }
        } else if (state.sources.size > 1) {
            SourceList(state, actions)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::addSources, enabled = state.job == null) { Text(tr("ui.source.add")) }
                OutlinedButton(onClick = actions::chooseSource, enabled = state.job == null) { Text(tr("ui.source.change")) }
            }
        } else {
            val media = source.media
            Surface(shape = RoundedCornerShape(10.dp), color = Palette.background, border = BorderStroke(1.dp, Palette.outline)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.path.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        source.path.parent?.toString() ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val video = media.video
                    val line = listOfNotNull(
                        video?.let { "${it.width}x${it.height} (${aspectLabel(it.width, it.height)})" },
                        video?.let { tr("ui.source.fps", "fps" to it.fps.toInt()) },
                        media.duration.toTimecode().substringBefore('.'),
                        formatBytes(media.sizeBytes),
                        AudioTracks.of(media.audio).shortLabel(),
                    ).joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    if (source.alreadyAnalyzed && state.session == null) {
                        Text(tr("ui.source.alreadyAnalyzed"), style = MaterialTheme.typography.bodySmall, color = Palette.success)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::chooseSource, enabled = state.job == null) { Text(tr("ui.source.changeVideo")) }
                OutlinedButton(onClick = actions::addSources, enabled = state.job == null) { Text(tr("ui.source.addVideos")) }
            }
        }
        Row {
            if (state.source != null) {
                TextButton(onClick = actions::showLibrary, enabled = state.job == null) { Text(tr("ui.library.title")) }
            }
            TextButton(onClick = actions::chooseSession, enabled = state.job == null && state.config is ConfigStatus.Ready) {
                Text(tr("ui.source.openSession"))
            }
        }
    }
}

/**
 * Captures d'un montage à plusieurs vidéos, dans l'ordre où les parties ont été jouées : c'est l'ordre des moments
 * dans le montage chronologique.
 */
@Composable
private fun SourceList(state: UiState, actions: UiActions) {
    Surface(shape = RoundedCornerShape(10.dp), color = Palette.background, border = BorderStroke(1.dp, Palette.outline)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            state.sources.forEachIndexed { i, source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.labelLarge, color = Palette.accent)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.path.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val recorded = source.media.recordedAt?.let { I18n.date(it.atZone(ZoneId.systemDefault()), "format.dateTime.short") }
                        Text(
                            listOfNotNull(recorded, source.media.duration.toTimecode().substringBefore('.')).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textMuted,
                        )
                    }
                    TextButton(onClick = { actions.removeSource(source.path) }, enabled = state.job == null) { Text(tr("ui.source.remove")) }
                }
            }
            val total = state.sources.fold(Duration.ZERO) { acc, s -> acc + s.media.duration }
            Text(
                tr("ui.source.total", "duration" to total.toTimecode().substringBefore('.')),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}


@Composable
fun SettingsSection(state: UiState, actions: UiActions) {
    val settings = state.settings
    val profiles = (state.config as? ConfigStatus.Ready)?.profiles.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(tr("ui.settings.title"))

        // Profil
        Text(tr("ui.settings.profile"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            val detected = state.source?.detectedProfileId?.let { id -> profiles.firstOrNull { it.id == id }?.name ?: id }
            val label = settings.profileId?.let { id -> profiles.firstOrNull { it.id == id }?.name ?: id }
                ?: (detected?.let { tr("ui.settings.profileAutoDetected", "profile" to it) } ?: tr("ui.settings.profileAuto"))
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = profiles.isNotEmpty()) {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("▾")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(tr("ui.settings.profileAutoMenu")) }, onClick = { expanded = false; actions.setProfile(null) })
                    profiles.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { expanded = false; actions.setProfile(p.id) })
                    }
                }
            }
            TextButton(onClick = actions::editProfile, enabled = state.config is ConfigStatus.Ready && state.source != null) { Text(tr("ui.settings.edit")) }
        }
        if (state.session?.profileChanged == true) {
            Text(tr("ui.settings.profileChanged"), style = MaterialTheme.typography.bodySmall, color = Palette.accent)
        }

        // Formats
        Spacer(Modifier.height(2.dp))
        Text(tr("ui.settings.formats"), style = MaterialTheme.typography.labelLarge)
        PlatformPicker(state.platforms, settings.platform, actions::setPlatform)
        // Une plateforme impose son format : les cases ne comptent plus tant qu'elle est choisie.
        val chosen = state.platforms.firstOrNull { it.id == settings.platform }
        OutputFormat.entries.forEach { format ->
            val enabled = chosen == null
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) { actions.toggleFormat(format) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = if (chosen != null) format == chosen.format else format in settings.formats,
                    onCheckedChange = { actions.toggleFormat(format) },
                    enabled = enabled,
                )
                Text(
                    formatLabel(format, state.source?.media),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) Palette.text else Palette.textMuted,
                )
            }
        }

        // Style
        Spacer(Modifier.height(2.dp))
        Text(tr("ui.settings.style"), style = MaterialTheme.typography.labelLarge)
        listOf(
            EditStyle.STORY to tr("ui.settings.styleStory"),
            EditStyle.SIMPLE to tr("ui.settings.styleSimple"),
        ).forEach { (style, label) ->
            Row(Modifier.fillMaxWidth().clickable { actions.setStyle(style) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.style == style, onClick = { actions.setStyle(style) })
                Text(label)
            }
        }

        // Ouverture et fin
        Spacer(Modifier.height(2.dp))
        IntroSection(state, actions)

        // Cible
        Spacer(Modifier.height(2.dp))
        Text(tr("ui.settings.moments"), style = MaterialTheme.typography.labelLarge)
        listOf(MomentMode.BEST to tr("ui.settings.momentsBest"), MomentMode.KILLS to tr("ui.settings.momentsKills")).forEach { (mode, label) ->
            Row(Modifier.fillMaxWidth().clickable { actions.setMomentMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.momentMode == mode, onClick = { actions.setMomentMode(mode) })
                Text(label)
            }
        }
        state.session?.let { session ->
            val counts = session.eventCounts
            Text(
                when {
                    counts.isEmpty() && session.multiple -> tr("ui.settings.noEventsMany")
                    counts.isEmpty() -> tr("ui.settings.noEvents")
                    session.multiple -> tr("ui.settings.eventsMany", "count" to session.entries.size, "events" to eventsLabel(counts))
                    else -> tr("ui.settings.events", "events" to eventsLabel(counts))
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textMuted,
            )
        }

        Spacer(Modifier.height(2.dp))
        Text(tr("ui.settings.length"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.targetMode == TargetMode.DURATION, onClick = { actions.setTargetMode(TargetMode.DURATION) })
            Text(tr("ui.settings.targetDuration"), Modifier.width(150.dp).clickable { actions.setTargetMode(TargetMode.DURATION) })
            OutlinedTextField(
                value = settings.durationText,
                onValueChange = actions::setDurationText,
                singleLine = true,
                enabled = settings.targetMode == TargetMode.DURATION,
                isError = settings.targetMode == TargetMode.DURATION && settings.targetDuration == null,
                placeholder = { Text(tr("ui.settings.durationExample", "a" to 60.seconds, "b" to 5.minutes)) },
                modifier = Modifier.width(110.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.targetMode == TargetMode.TOP_N, onClick = { actions.setTargetMode(TargetMode.TOP_N) })
            Text(tr("ui.settings.topN"), Modifier.width(150.dp).clickable { actions.setTargetMode(TargetMode.TOP_N) })
            OutlinedTextField(
                value = settings.topNText,
                onValueChange = actions::setTopNText,
                singleLine = true,
                enabled = settings.targetMode == TargetMode.TOP_N,
                isError = settings.targetMode == TargetMode.TOP_N && settings.topN == null,
                modifier = Modifier.width(110.dp),
            )
        }
        Row(Modifier.fillMaxWidth().clickable { actions.setTargetMode(TargetMode.ALL) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.targetMode == TargetMode.ALL, onClick = { actions.setTargetMode(TargetMode.ALL) })
            Text(tr("ui.settings.keepAll"))
        }

        // Seuil
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("ui.settings.threshold"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(I18n.fixed(settings.threshold, 2), fontWeight = FontWeight.SemiBold, color = Palette.accent)
        }
        Slider(
            value = settings.threshold.toFloat(),
            onValueChange = { actions.setThreshold(it.toDouble()) },
            valueRange = 0.2f..0.95f,
        )
        Text(
            when {
                settings.momentMode == MomentMode.KILLS -> tr("ui.settings.thresholdIgnored")
                state.session != null -> tr("ui.settings.thresholdLive")
                else -> tr("ui.settings.thresholdHint")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )

        WebcamSection(state, actions)

        // Sortie
        Spacer(Modifier.height(2.dp))
        Text(tr("ui.settings.outputDir"), style = MaterialTheme.typography.labelLarge)
        Text(
            settings.outputDir?.toString() ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::chooseOutputDir) { Text(tr("ui.common.changeEllipsis")) }
            TextButton(onClick = actions::openOutputDir) { Text(tr("ui.common.open")) }
        }

        Spacer(Modifier.height(6.dp))
        Button(onClick = actions::analyze, enabled = state.canAnalyze, modifier = Modifier.fillMaxWidth().height(44.dp)) {
            Text(if (state.session == null) tr("ui.settings.analyze") else tr("ui.settings.reanalyze"), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Plateforme visée : « Aucune » garde les formats cochés ; une plateforme impose le sien et adapte volume, débit,
 * durée et place des textes. Ce qu'elle impose s'affiche dessous.
 */
@Composable
internal fun PlatformPicker(platforms: List<PlatformInfo>, selected: String?, onSelect: (String?) -> Unit) {
    if (platforms.isEmpty()) return
    val chosen = platforms.firstOrNull { it.id == selected }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth()) {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(chosen?.let { tr("ui.platform.selected", "platform" to it.name) } ?: tr("ui.platform.none"), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(tr("ui.platform.noneMenu")) }, onClick = { expanded = false; onSelect(null) })
                platforms.forEach { p ->
                    DropdownMenuItem(text = { Text(p.name) }, onClick = { expanded = false; onSelect(p.id) })
                }
            }
        }
        chosen?.let { Text(it.summary(), style = MaterialTheme.typography.bodySmall, color = Palette.textMuted) }
    }
}
