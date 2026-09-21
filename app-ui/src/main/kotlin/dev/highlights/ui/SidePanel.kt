package dev.highlights.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.highlights.core.model.AudioTracks
import dev.highlights.core.model.OutputFormat
import dev.highlights.core.model.aspectLabel
import dev.highlights.core.serialization.toTimecode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.name
import kotlin.time.Duration

@Composable
fun AppHeader(config: ConfigStatus, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).border(BorderStroke(5.dp, Palette.accent), RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Text("Highlights", style = MaterialTheme.typography.titleLarge)
        }
        when (config) {
            ConfigStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Chargement de la configuration…", style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
            }
            is ConfigStatus.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${config.profiles.size} profils · ${config.configFile.parent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions::reloadConfig) { Text("Recharger") }
            }
            is ConfigStatus.Failed -> Column {
                Text(config.message, style = MaterialTheme.typography.bodySmall, color = Palette.danger)
                TextButton(onClick = actions::reloadConfig) { Text("Réessayer") }
            }
        }
    }
}

@Composable
fun SourceSection(state: UiState, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(if (state.sources.size > 1) "Captures (${state.sources.size})" else "Capture")
        val source = state.source
        if (source == null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Palette.background,
                border = BorderStroke(1.dp, Palette.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Glisse une ou plusieurs vidéos (ou sessions) ici", color = Palette.textMuted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = actions::chooseSource, enabled = state.job == null && state.config is ConfigStatus.Ready) {
                        Text("Choisir des vidéos…")
                    }
                }
            }
        } else if (state.sources.size > 1) {
            SourceList(state, actions)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::addSources, enabled = state.job == null) { Text("Ajouter") }
                OutlinedButton(onClick = actions::chooseSource, enabled = state.job == null) { Text("Changer") }
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
                        video?.let { "${it.fps.toInt()} fps" },
                        media.duration.toTimecode().substringBefore('.'),
                        formatBytes(media.sizeBytes),
                        AudioTracks.of(media.audio).shortLabel(),
                    ).joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::chooseSource, enabled = state.job == null) { Text("Changer de vidéo") }
                OutlinedButton(onClick = actions::addSources, enabled = state.job == null) { Text("Ajouter des vidéos") }
            }
        }
        TextButton(onClick = actions::chooseSession, enabled = state.job == null && state.config is ConfigStatus.Ready) {
            Text("Ouvrir une analyse enregistrée…")
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
                        val recorded = source.media.recordedAt?.let { RECORDED.format(it.atZone(ZoneId.systemDefault())) }
                        Text(
                            listOfNotNull(recorded, source.media.duration.toTimecode().substringBefore('.')).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textMuted,
                        )
                    }
                    TextButton(onClick = { actions.removeSource(source.path) }, enabled = state.job == null) { Text("Retirer") }
                }
            }
            val total = state.sources.fold(Duration.ZERO) { acc, s -> acc + s.media.duration }
            Text(
                "${total.toTimecode().substringBefore('.')} au total · rangées par date d'enregistrement",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val RECORDED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.FRENCH)

@Composable
fun SettingsSection(state: UiState, actions: UiActions) {
    val settings = state.settings
    val profiles = (state.config as? ConfigStatus.Ready)?.profiles.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Réglages")

        // Profil
        Text("Profil de jeu", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            val detected = state.source?.detectedProfileId?.let { id -> profiles.firstOrNull { it.id == id }?.displayName ?: id }
            val label = settings.profileId?.let { id -> profiles.firstOrNull { it.id == id }?.displayName ?: id }
                ?: ("Automatique" + (detected?.let { " ($it)" } ?: ""))
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = profiles.isNotEmpty()) {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("▾")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Automatique (d'après le dossier)") }, onClick = { expanded = false; actions.setProfile(null) })
                    profiles.forEach { p ->
                        DropdownMenuItem(text = { Text(p.displayName) }, onClick = { expanded = false; actions.setProfile(p.id) })
                    }
                }
            }
            TextButton(onClick = actions::editProfile, enabled = state.config is ConfigStatus.Ready && state.source != null) { Text("Éditer") }
        }
        if (state.session?.profileChanged == true) {
            Text("Profil modifié : relance l'analyse pour l'appliquer.", style = MaterialTheme.typography.bodySmall, color = Palette.accent)
        }

        // Formats
        Spacer(Modifier.height(2.dp))
        Text("Formats de sortie", style = MaterialTheme.typography.labelLarge)
        OutputFormat.entries.forEach { format ->
            Row(
                Modifier.fillMaxWidth().clickable { actions.toggleFormat(format) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = format in settings.formats, onCheckedChange = { actions.toggleFormat(format) })
                Text(formatLabel(format, state.source?.media), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Cible
        Spacer(Modifier.height(2.dp))
        Text("Moments à garder", style = MaterialTheme.typography.labelLarge)
        listOf(MomentMode.BEST to "Les meilleurs moments", MomentMode.KILLS to "Tous les kills").forEach { (mode, label) ->
            Row(Modifier.fillMaxWidth().clickable { actions.setMomentMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.momentMode == mode, onClick = { actions.setMomentMode(mode) })
                Text(label)
            }
        }
        state.session?.let { session ->
            val counts = session.eventCounts
            Text(
                when {
                    counts.isEmpty() && session.multiple -> "Aucun événement détecté dans ces parties."
                    counts.isEmpty() -> "Aucun événement détecté dans cette partie."
                    session.multiple -> "Dans les ${session.entries.size} parties : ${eventsLabel(counts)}"
                    else -> "Dans la partie : ${eventsLabel(counts)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.textMuted,
            )
        }

        Spacer(Modifier.height(2.dp))
        Text("Longueur du montage", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.targetMode == TargetMode.DURATION, onClick = { actions.setTargetMode(TargetMode.DURATION) })
            Text("Durée cible", Modifier.width(130.dp).clickable { actions.setTargetMode(TargetMode.DURATION) })
            OutlinedTextField(
                value = settings.durationText,
                onValueChange = actions::setDurationText,
                singleLine = true,
                enabled = settings.targetMode == TargetMode.DURATION,
                isError = settings.targetMode == TargetMode.DURATION && settings.targetDuration == null,
                placeholder = { Text("60s, 5m") },
                modifier = Modifier.width(120.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.targetMode == TargetMode.TOP_N, onClick = { actions.setTargetMode(TargetMode.TOP_N) })
            Text("Meilleurs moments", Modifier.width(130.dp).clickable { actions.setTargetMode(TargetMode.TOP_N) })
            OutlinedTextField(
                value = settings.topNText,
                onValueChange = actions::setTopNText,
                singleLine = true,
                enabled = settings.targetMode == TargetMode.TOP_N,
                isError = settings.targetMode == TargetMode.TOP_N && settings.topN == null,
                modifier = Modifier.width(120.dp),
            )
        }
        Row(Modifier.fillMaxWidth().clickable { actions.setTargetMode(TargetMode.ALL) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.targetMode == TargetMode.ALL, onClick = { actions.setTargetMode(TargetMode.ALL) })
            Text("Tout garder (sans limite)")
        }

        // Seuil
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Seuil de score", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("%.2f".format(settings.threshold), fontWeight = FontWeight.SemiBold, color = Palette.accent)
        }
        Slider(
            value = settings.threshold.toFloat(),
            onValueChange = { actions.setThreshold(it.toDouble()) },
            valueRange = 0.2f..0.95f,
        )
        Text(
            when {
                settings.momentMode == MomentMode.KILLS -> "Ignoré en mode « Tous les kills »."
                state.session != null -> "Moments, seuil et longueur s'appliquent tout de suite, sans réanalyser."
                else -> "Plus bas = plus de moments retenus."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
        )

        // Sortie
        Spacer(Modifier.height(2.dp))
        Text("Dossier de sortie", style = MaterialTheme.typography.labelLarge)
        Text(
            settings.outputDir?.toString() ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::chooseOutputDir) { Text("Changer…") }
            TextButton(onClick = actions::openOutputDir) { Text("Ouvrir") }
        }

        Spacer(Modifier.height(6.dp))
        Button(onClick = actions::analyze, enabled = state.canAnalyze, modifier = Modifier.fillMaxWidth().height(44.dp)) {
            Text(if (state.session == null) "Analyser" else "Réanalyser", style = MaterialTheme.typography.titleMedium)
        }
    }
}
