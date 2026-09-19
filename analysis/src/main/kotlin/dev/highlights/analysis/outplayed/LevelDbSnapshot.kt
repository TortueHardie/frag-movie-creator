package dev.highlights.analysis.outplayed

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Lecture seule d'une base LevelDB (stockage IndexedDB de Chromium/CEF), sans la bibliothèque LevelDB : l'application
 * propriétaire (Overwolf) garde le verrou et continue d'écrire pendant qu'on lit. On lit donc les fichiers tels quels :
 * le journal (.log, écritures récentes) et les tables (.ldb, écritures compactées), et on garde pour chaque clé la
 * version au numéro de séquence le plus haut. Une écriture en cours en fin de journal est ignorée.
 */
object LevelDbSnapshot {

    /** Clé brute, comparable (les ByteArray ne le sont pas). */
    class Key(val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    /** Valeur courante de chaque clé (les clés supprimées sont absentes). */
    fun read(dir: Path, attempts: Int = 3): Map<Key, ByteArray> {
        require(dir.isDirectory()) { "base LevelDB introuvable : $dir" }
        var last: IOException? = null
        repeat(attempts) {
            try {
                return readOnce(dir)
            } catch (e: NoSuchFileException) {
                // Compaction pendant la lecture : un fichier listé a disparu, on recommence avec la nouvelle liste.
                last = e
            }
        }
        throw last ?: IOException("lecture impossible : $dir")
    }

    private fun readOnce(dir: Path): Map<Key, ByteArray> {
        val latest = HashMap<Key, Pair<Long, ByteArray?>>()
        fun put(key: ByteArray, seq: Long, value: ByteArray?) {
            val k = Key(key)
            val current = latest[k]
            if (current == null || current.first < seq) latest[k] = seq to value
        }
        val files = dir.listDirectoryEntries().filter { it.name.endsWith(".ldb") || it.name.endsWith(".sst") || it.name.endsWith(".log") }
        for (file in files) {
            val data = file.readBytes()
            if (file.name.endsWith(".log")) readLog(data, ::put) else readTable(data, ::put)
        }
        return latest.mapNotNull { (k, v) -> v.second?.let { k to it } }.toMap()
    }

    // ------------------------------------------------------------------ journal

    /** Journal : blocs de 32 Ko, fragments (complet, début, milieu, fin) qui forment des lots d'écritures. */
    internal fun readLog(data: ByteArray, put: (ByteArray, Long, ByteArray?) -> Unit) {
        var pos = 0
        var fragment: java.io.ByteArrayOutputStream? = null
        while (pos + HEADER <= data.size) {
            val left = BLOCK - pos % BLOCK
            if (left < HEADER) {
                pos += left
                continue
            }
            val length = (data[pos + 4].toInt() and 0xFF) or ((data[pos + 5].toInt() and 0xFF) shl 8)
            val type = data[pos + 6].toInt()
            if (type == 0 && length == 0) {
                pos += left
                continue
            }
            if (pos + HEADER + length > data.size) break // écriture en cours
            val start = pos + HEADER
            pos = start + length
            when (type) {
                FULL -> applyBatch(data.copyOfRange(start, start + length), put)
                FIRST -> fragment = java.io.ByteArrayOutputStream().apply { write(data, start, length) }
                MIDDLE -> fragment?.write(data, start, length)
                LAST -> fragment?.let {
                    it.write(data, start, length)
                    applyBatch(it.toByteArray(), put)
                    fragment = null
                }
            }
        }
    }

    private fun applyBatch(batch: ByteArray, put: (ByteArray, Long, ByteArray?) -> Unit) {
        if (batch.size < 12) return
        val buf = ByteBuffer.wrap(batch).order(ByteOrder.LITTLE_ENDIAN)
        val seq = buf.getLong(0)
        val count = buf.getInt(8)
        val r = Reader(batch, 12)
        try {
            for (k in 0 until count) {
                val type = r.byte()
                val key = r.bytes(r.varint().toInt())
                val value = if (type == 1) r.bytes(r.varint().toInt()) else null
                put(key, seq + k, value)
            }
        } catch (_: IndexOutOfBoundsException) {
            // Lot tronqué : ignoré.
        }
    }

    // ------------------------------------------------------------------ tables

    /** Table triée : pied de page → bloc d'index → blocs de données (compressés en Snappy ou non). */
    internal fun readTable(data: ByteArray, put: (ByteArray, Long, ByteArray?) -> Unit) {
        if (data.size < FOOTER) return
        val footer = Reader(data, data.size - FOOTER)
        footer.varint()
        footer.varint() // bloc de métadonnées (filtres), inutile ici
        val indexOffset = footer.varint().toInt()
        val indexSize = footer.varint().toInt()
        for ((_, handle) in blockEntries(block(data, indexOffset, indexSize))) {
            val h = Reader(handle, 0)
            val offset = h.varint().toInt()
            val size = h.varint().toInt()
            for ((internalKey, value) in blockEntries(block(data, offset, size))) {
                if (internalKey.size < 8) continue
                val trailer = ByteBuffer.wrap(internalKey, internalKey.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
                val key = internalKey.copyOf(internalKey.size - 8)
                put(key, trailer ushr 8, if ((trailer and 0xFF) == 1L) value else null)
            }
        }
    }

    private fun block(data: ByteArray, offset: Int, size: Int): ByteArray {
        val raw = data.copyOfRange(offset, offset + size)
        return when (data[offset + size].toInt()) {
            0 -> raw
            1 -> Snappy.decompress(raw)
            else -> throw IOException("compression de bloc inconnue : ${data[offset + size]}")
        }
    }

    /** Entrées d'un bloc : clés à préfixe partagé, suivies des points de redémarrage (ignorés, on lit tout). */
    private fun blockEntries(block: ByteArray): List<Pair<ByteArray, ByteArray>> {
        val restarts = ByteBuffer.wrap(block, block.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
        val end = block.size - 4 - 4 * restarts
        val r = Reader(block, 0)
        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        var last = ByteArray(0)
        while (r.pos < end) {
            val shared = r.varint().toInt()
            val unshared = r.varint().toInt()
            val valueLength = r.varint().toInt()
            val key = last.copyOf(shared) + r.bytes(unshared)
            out += key to r.bytes(valueLength)
            last = key
        }
        return out
    }

    internal class Reader(val data: ByteArray, var pos: Int) {
        fun byte(): Int = data[pos++].toInt() and 0xFF

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw IndexOutOfBoundsException("$n octets à $pos sur ${data.size}")
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
    }

    private const val BLOCK = 32768
    private const val HEADER = 7
    private const val FOOTER = 48
    private const val FULL = 1
    private const val FIRST = 2
    private const val MIDDLE = 3
    private const val LAST = 4
}

/** Décompression Snappy (format brut, sans cadre), utilisée par les blocs des tables LevelDB. */
object Snappy {
    fun decompress(input: ByteArray): ByteArray {
        val r = LevelDbSnapshot.Reader(input, 0)
        val size = r.varint().toInt()
        val out = ByteArray(size)
        var o = 0
        while (r.pos < input.size) {
            val tag = r.byte()
            when (tag and 3) {
                0 -> {
                    var length = tag ushr 2
                    if (length >= 60) {
                        val n = length - 59
                        length = 0
                        for (k in 0 until n) length = length or (r.byte() shl (8 * k))
                    }
                    length += 1
                    System.arraycopy(input, r.pos, out, o, length)
                    r.pos += length
                    o += length
                }
                else -> {
                    val length: Int
                    val offset: Int
                    when (tag and 3) {
                        1 -> {
                            length = ((tag ushr 2) and 7) + 4
                            offset = ((tag ushr 5) shl 8) or r.byte()
                        }
                        2 -> {
                            length = (tag ushr 2) + 1
                            offset = r.byte() or (r.byte() shl 8)
                        }
                        else -> {
                            length = (tag ushr 2) + 1
                            offset = r.byte() or (r.byte() shl 8) or (r.byte() shl 16) or (r.byte() shl 24)
                        }
                    }
                    if (offset <= 0 || offset > o) throw IOException("Snappy : référence invalide ($offset à $o)")
                    // Copie octet par octet : la source peut chevaucher la destination (répétitions).
                    for (k in 0 until length) out[o + k] = out[o - offset + k]
                    o += length
                }
            }
        }
        if (o != size) throw IOException("Snappy : $o octets décompressés, $size attendus")
        return out
    }
}
