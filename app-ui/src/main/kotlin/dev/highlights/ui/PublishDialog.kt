package dev.highlights.ui

import dev.highlights.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import dev.highlights.core.config.YouTubePrivacy
import dev.highlights.publish.YouTubeText
import kotlin.io.path.name

/** Envoi d'une vidéo exportée sur YouTube : tout est pré-rempli d'après la vidéo, et tout se modifie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishDialog(publish: PublishUiState, actions: UiActions) {
    AlertDialog(
        onDismissRequest = actions::closePublish,
        title = { Text(tr("ui.publish.title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(publish.video.name, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                when {
                    !publish.configured -> NotConfigured(actions)
                    publish.loading -> Text(tr("ui.publish.loading"), color = Palette.textMuted)
                    !publish.api -> ManualFields(publish, actions)
                    else -> PublishFields(publish, actions)
                }
            }
        },
        confirmButton = {
            when {
                !publish.api -> Button(onClick = actions::openYouTubeUpload, enabled = !publish.loading) { Text(tr("ui.publish.openYouTube")) }
                publish.configured -> Button(onClick = actions::publishToYouTube, enabled = publish.canPublish) { Text(tr("ui.publish.upload")) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (publish.configured && !publish.loading) TextButton(onClick = actions::resetPublishText) { Text(tr("ui.publish.reset")) }
                // Envoi manuel : la fenêtre reste ouverte pendant qu'on colle les textes dans YouTube.
                TextButton(onClick = actions::closePublish) { Text(if (publish.api && publish.configured) tr("ui.common.cancel") else tr("ui.common.close")) }
            }
        },
    )
}

/**
 * Envoi manuel assisté : la page d'envoi de YouTube s'ouvre dans le navigateur (où l'on est connecté), la vidéo est
 * montrée dans l'Explorateur pour l'y glisser, et chaque texte se copie d'un clic.
 */
@Composable
private fun ManualFields(publish: PublishUiState, actions: UiActions) {
    Text(
        tr("ui.publish.manualIntro"),
        style = MaterialTheme.typography.bodyMedium,
    )
    CopyField(tr("ui.publish.titleField", "length" to publish.title.length, "max" to YouTubeText.TITLE_MAX), publish.title, actions, singleLine = true,
        isError = publish.title.isBlank() || publish.title.length > YouTubeText.TITLE_MAX) { v -> actions.updatePublish { it.copy(title = v) } }
    CopyField(tr("ui.publish.description"), publish.description, actions) { v -> actions.updatePublish { it.copy(description = v) } }
    CopyField(tr("ui.publish.tagsManual"), publish.tagsText, actions, singleLine = true) { v ->
        actions.updatePublish { it.copy(tagsText = v) }
    }
    publish.problems.forEach { Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text(
        tr("ui.publish.manualHint"),
        style = MaterialTheme.typography.bodySmall,
        color = Palette.textMuted,
    )
}

@Composable
private fun CopyField(
    label: String,
    value: String,
    actions: UiActions,
    singleLine: Boolean = false,
    isError: Boolean = false,
    onChange: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 5,
            isError = isError,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { actions.copyToClipboard(value) }, enabled = value.isNotBlank()) { Text(tr("ui.common.copy")) }
    }
}

@Composable
private fun NotConfigured(actions: UiActions) {
    Text(
        tr("ui.publish.setup1"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        tr("ui.publish.setup2"),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = actions::editConfig) { Text(tr("ui.publish.openConfig")) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublishFields(publish: PublishUiState, actions: UiActions) {
    OutlinedTextField(
        value = publish.title,
        onValueChange = { v -> actions.updatePublish { it.copy(title = v) } },
        label = { Text(tr("ui.publish.titleField", "length" to publish.title.length, "max" to YouTubeText.TITLE_MAX)) },
        singleLine = true,
        isError = publish.title.isBlank() || publish.title.length > YouTubeText.TITLE_MAX,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = publish.description,
        onValueChange = { v -> actions.updatePublish { it.copy(description = v) } },
        label = { Text(tr("ui.publish.description")) },
        minLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = publish.tagsText,
        onValueChange = { v -> actions.updatePublish { it.copy(tagsText = v) } },
        label = { Text(tr("ui.publish.tags")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    DialogGroup(tr("ui.publish.visibility")) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            YouTubePrivacy.entries.forEachIndexed { i, privacy ->
                SegmentedButton(
                    selected = publish.privacy == privacy,
                    onClick = { actions.updatePublish { it.copy(privacy = privacy) } },
                    shape = SegmentedButtonDefaults.itemShape(i, YouTubePrivacy.entries.size),
                ) { Text(privacy.label) }
            }
        }
        OutlinedTextField(
            value = publish.publishAtText,
            onValueChange = { v ->
                // Une mise en ligne programmée part d'une vidéo privée : on la met privée dès qu'on en tape une.
                actions.updatePublish { it.copy(publishAtText = v, privacy = if (v.isNotBlank()) YouTubePrivacy.PRIVATE else it.privacy) }
            },
            label = { Text(tr("ui.publish.schedule")) },
            singleLine = true,
            isError = publish.publishAtText.isNotBlank() && publish.publishAt == null,
            modifier = Modifier.fillMaxWidth(),
        )
        MontageToggle(tr("ui.publish.notify"), publish.notify) { v -> actions.updatePublish { it.copy(notify = v) } }
        MontageToggle(tr("ui.publish.madeForKids"), publish.madeForKids) { v -> actions.updatePublish { it.copy(madeForKids = v) } }
    }

    DialogGroup(tr("ui.publish.classification")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = publish.categoryText,
                onValueChange = { v -> actions.updatePublish { it.copy(categoryText = v) } },
                label = { Text(tr("ui.publish.category")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = publish.language,
                onValueChange = { v -> actions.updatePublish { it.copy(language = v) } },
                label = { Text(tr("ui.publish.language")) },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }
    }

    publish.problems.forEach { Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (publish.connected) tr("ui.publish.connected") else tr("ui.publish.notConnected"),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
            modifier = Modifier.weight(1f),
        )
        if (publish.connected) TextButton(onClick = actions::youtubeLogout) { Text(tr("ui.publish.logout")) }
    }
}
