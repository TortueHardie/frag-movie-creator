package dev.highlights.cli

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import dev.highlights.core.model.WebcamChoice
import dev.highlights.core.serialization.Durations
import kotlin.io.path.isRegularFile
import kotlin.time.Duration

/** `--webcam FICHIER|auto` : webcam en incrustation (bandeau du 9:16, coin du 16:9). */
internal fun ParameterHolder.webcamOption() = option(
    "--webcam",
    help = "Webcam en incrustation : une vidéo, ou « auto » (second flux vidéo de la capture, sinon une vidéo « webcam », " +
        "« facecam » ou « cam » rangée à côté). Bandeau au-dessus du jeu en 9:16, coin en 16:9",
).convert("FICHIER|auto") { text ->
    WebcamChoice.parse(text).also { choice ->
        if (choice is WebcamChoice.File && !choice.path.isRegularFile()) throw BadParameterValue("fichier introuvable : $text")
    }
}

/** `--webcam-offset` : décalage de la webcam, signé (« 1.2s », « -400ms »). */
internal fun ParameterHolder.webcamOffsetOption() = option(
    "--webcam-offset",
    help = "Décalage de la webcam ajouté au calage automatique, ex. 1.2s ou -400ms : positif si la webcam est en retard sur le jeu",
).convert("DURÉE") { text -> parseSignedDuration(text) ?: throw BadParameterValue("durée invalide '$text' (ex. 1.2s, -400ms)") }

/** Durée signée : « -400ms », « +1.5s », « 2s ». */
internal fun parseSignedDuration(text: String): Duration? {
    val t = text.trim()
    val negative = t.startsWith("-")
    val body = t.removePrefix("-").removePrefix("+")
    val d = Durations.parseOrNull(body) ?: body.toDoubleOrNull()?.let { Duration.parse("${it}s") } ?: return null
    return if (negative) -d else d
}
