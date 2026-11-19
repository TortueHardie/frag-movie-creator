package dev.highlights.ui.player

import io.github.oshai.kotlinlogging.KotlinLogging
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

private val log = KotlinLogging.logger {}

/**
 * Sortie du son du lecteur : du PCM 16 bits stéréo entrelacé, petit-boutiste. [playedFrames] (échantillons par canal
 * réellement joués) sert d'horloge maîtresse : les images attendent le son, jamais l'inverse, parce qu'une image en
 * retard d'une trame se voit à peine alors qu'un son qui saute s'entend tout de suite.
 */
interface AudioSink {
    val rate: Int

    /** Échantillons (par canal) joués depuis l'ouverture ; figé pendant une pause. */
    val playedFrames: Long

    /** Bloque tant que le tampon est plein (c'est ce qui cadence le décodage du son). */
    fun write(bytes: ByteArray, length: Int)

    fun pause()

    fun resume()

    /** Attend que tout ce qui a été écrit soit joué. */
    fun drain()

    /** Libère la sortie ; débloque un [write] en attente. */
    fun close()

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 2 * CHANNELS
    }
}

/**
 * Sortie Java Sound (DirectSound sous Windows, rien à installer). Tampon de 200 ms : assez pour ne pas craquer quand la
 * machine rend un montage en même temps, et sans effet sur la synchro puisque l'horloge lit la position de lecture
 * ([SourceDataLine.getLongFramePosition]), pas ce qui a été écrit.
 */
class JavaSoundSink private constructor(private val line: SourceDataLine) : AudioSink {
    override val rate = AudioSink.RATE
    override val playedFrames: Long get() = line.longFramePosition

    override fun write(bytes: ByteArray, length: Int) {
        var offset = 0
        while (offset < length && line.isOpen) {
            val written = line.write(bytes, offset, length - offset)
            if (written <= 0) return
            offset += written
        }
    }

    override fun pause() = line.stop()

    override fun resume() = line.start()

    override fun drain() {
        if (line.isOpen) line.drain()
    }

    override fun close() {
        runCatching {
            line.stop()
            line.flush()
            line.close()
        }
    }

    companion object {
        private const val BUFFER_SECONDS = 0.2

        /** Null s'il n'y a pas de sortie son (machine sans carte son, sortie prise) : la vidéo se lit alors muette. */
        fun create(): AudioSink? = try {
            val format = AudioFormat(AudioSink.RATE.toFloat(), 16, AudioSink.CHANNELS, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            val buffer = (AudioSink.RATE * BUFFER_SECONDS).toInt() * AudioSink.BYTES_PER_FRAME
            line.open(format, buffer)
            line.start()
            JavaSoundSink(line)
        } catch (e: LineUnavailableException) {
            log.warn { "Pas de sortie son pour le lecteur (${e.message}) : lecture muette" }
            null
        } catch (e: IllegalArgumentException) {
            log.warn { "Pas de sortie son pour le lecteur (${e.message}) : lecture muette" }
            null
        }
    }
}
