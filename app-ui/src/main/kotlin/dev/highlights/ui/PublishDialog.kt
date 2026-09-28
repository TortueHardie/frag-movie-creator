package dev.highlights.ui

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
        title = { Text("Publier sur YouTube") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(publish.video.name, style = MaterialTheme.typography.bodySmall, color = Palette.textMuted)
                when {
                    !publish.configured -> NotConfigured(actions)
                    publish.loading -> Text("Préparation du titre et de la description…", color = Palette.textMuted)
                    !publish.api -> ManualFields(publish, actions)
                    else -> PublishFields(publish, actions)
                }
            }
        },
        confirmButton = {
            when {
                !publish.api -> Button(onClick = actions::openYouTubeUpload, enabled = !publish.loading) { Text("Ouvrir YouTube") }
                publish.configured -> Button(onClick = actions::publishToYouTube, enabled = publish.canPublish) { Text("Envoyer sur YouTube") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (publish.configured && !publish.loading) TextButton(onClick = actions::resetPublishText) { Text("Texte proposé") }
                // Envoi manuel : la fenêtre reste ouverte pendant qu'on colle les textes dans YouTube.
                TextButton(onClick = actions::closePublish) { Text(if (publish.api && publish.configured) "Annuler" else "Fermer") }
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
        "« Ouvrir YouTube » ouvre la page d'envoi dans ton navigateur et montre la vidéo dans l'Explorateur : glisse-la " +
            "dans la page, puis colle le titre, la description et les tags. Cette fenêtre reste ouverte en attendant.",
        style = MaterialTheme.typography.bodyMedium,
    )
    CopyField("Titre (${publish.title.length}/${YouTubeText.TITLE_MAX})", publish.title, actions, singleLine = true,
        isError = publish.title.isBlank() || publish.title.length > YouTubeText.TITLE_MAX) { v -> actions.updatePublish { it.copy(title = v) } }
    CopyField("Description", publish.description, actions) { v -> actions.updatePublish { it.copy(description = v) } }
    CopyField("Tags (champ « Tags » de YouTube, dans « Afficher plus »)", publish.tagsText, actions, singleLine = true) { v ->
        actions.updatePublish { it.copy(tagsText = v) }
    }
    publish.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text(
        "Dans YouTube : « Non, elle n'est pas conçue pour les enfants », catégorie « Jeux vidéo », puis la visibilité de ton choix. " +
            "L'envoi direct par l'API se réactive avec publish.youtube.api dans app.yaml.",
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
        OutlinedButton(onClick = { actions.copyToClipboard(value) }, enabled = value.isNotBlank()) { Text("Copier") }
    }
}

@Composable
private fun NotConfigured(actions: UiActions) {
    Text(
        "YouTube n'accepte les envois que d'une application déclarée chez Google. Une fois pour toutes : dans la console " +
            "Google Cloud, crée un projet, active « YouTube Data API v3 », puis crée un identifiant OAuth de type " +
            "« Application de bureau » et télécharge son fichier JSON.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Dépose ce fichier client_secret….json dans le dossier de la configuration (à côté d'app.yaml), ou indique son " +
            "chemin dans publish.youtube.clientSecretFile. Rouvre ensuite cette fenêtre : la connexion au compte se fera " +
            "dans le navigateur au premier envoi.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = actions::editConfig) { Text("Ouvrir app.yaml") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublishFields(publish: PublishUiState, actions: UiActions) {
    OutlinedTextField(
        value = publish.title,
        onValueChange = { v -> actions.updatePublish { it.copy(title = v) } },
        label = { Text("Titre (${publish.title.length}/${YouTubeText.TITLE_MAX})") },
        singleLine = true,
        isError = publish.title.isBlank() || publish.title.length > YouTubeText.TITLE_MAX,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = publish.description,
        onValueChange = { v -> actions.updatePublish { it.copy(description = v) } },
        label = { Text("Description") },
        minLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = publish.tagsText,
        onValueChange = { v -> actions.updatePublish { it.copy(tagsText = v) } },
        label = { Text("Tags, séparés par des virgules") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    DialogGroup("Visibilité") {
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
            label = { Text("Mise en ligne programmée (ex. 2026-10-01 18:00) — vide : tout de suite") },
            singleLine = true,
            isError = publish.publishAtText.isNotBlank() && publish.publishAt == null,
            modifier = Modifier.fillMaxWidth(),
        )
        MontageToggle("Prévenir les abonnés (vidéo publique)", publish.notify) { v -> actions.updatePublish { it.copy(notify = v) } }
        MontageToggle("Conçue pour les enfants (déclaration obligatoire)", publish.madeForKids) { v -> actions.updatePublish { it.copy(madeForKids = v) } }
    }

    DialogGroup("Classement") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = publish.categoryText,
                onValueChange = { v -> actions.updatePublish { it.copy(categoryText = v) } },
                label = { Text("Catégorie (20 = Jeux vidéo)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = publish.language,
                onValueChange = { v -> actions.updatePublish { it.copy(language = v) } },
                label = { Text("Langue") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }
    }

    publish.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (publish.connected) "Compte YouTube connecté." else "La connexion au compte s'ouvrira dans le navigateur au premier envoi.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.textMuted,
            modifier = Modifier.weight(1f),
        )
        if (publish.connected) TextButton(onClick = actions::youtubeLogout) { Text("Se déconnecter") }
    }
}
