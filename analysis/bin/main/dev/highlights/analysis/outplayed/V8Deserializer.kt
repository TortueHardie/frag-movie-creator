package dev.highlights.analysis.outplayed

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Valeurs IndexedDB de Chromium : un numéro de version, l'enveloppe de Blink, puis l'objet JavaScript sérialisé par V8
 * (format de `structuredClone`). Seul le sous-ensemble utile est décodé : objets, tableaux, Set/Map, chaînes, nombres,
 * booléens, dates et références à un objet déjà lu. Résultat en types Kotlin : Map<String, Any?>, List<Any?>, String,
 * Double, Long, Boolean ou null.
 */
object V8Deserializer {

    /** Décode une valeur IndexedDB ; null si elle n'a pas la forme attendue (valeur externe, format inconnu). */
    fun decodeIndexedDbValue(value: ByteArray): Any? {
        val r = LevelDbSnapshot.Reader(value, 0)
        return try {
            r.varint() // version de la valeur IndexedDB
            if (r.byte() != 0xFF) return null
            r.varint() // version de Blink
            // À partir de la version 21 de Blink : décalage et taille d'une remorque (objets hôtes), 12 octets.
            if (value[r.pos].toInt() and 0xFF == TRAILER_OFFSET) r.pos += 1 + 12
            Parser(r).value()
        } catch (e: IOException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    private class Parser(val r: LevelDbSnapshot.Reader) {
        /** Objets déjà lus, dans l'ordre : cible des références '^'. */
        private val objects = mutableListOf<Any>()

        private fun peek(): Int = r.data[r.pos].toInt() and 0xFF

        fun value(): Any? {
            var tag = r.byte()
            while (tag == 0x00 || tag == 0xFF) {
                if (tag == 0xFF) r.varint() // version de V8
                tag = r.byte()
            }
            return when (tag.toChar()) {
                '_', '0', '-' -> null
                'T' -> true
                'F' -> false
                'I' -> r.varint().let { (it ushr 1) xor -(it and 1) }
                'U' -> r.varint()
                'N', 'D' -> double()
                '"' -> String(r.bytes(r.varint().toInt()), Charsets.ISO_8859_1)
                'S' -> String(r.bytes(r.varint().toInt()), Charsets.UTF_8)
                'c' -> String(r.bytes(r.varint().toInt()), Charsets.UTF_16LE)
                '^' -> objects.getOrNull(r.varint().toInt()) ?: throw IOException("référence V8 invalide")
                '?' -> {
                    r.varint()
                    value()
                }
                'o' -> {
                    val map = LinkedHashMap<String, Any?>().also { objects += it }
                    properties('{') { k, v -> map[k.toString()] = v }
                    map
                }
                'A' -> {
                    val list = ArrayList<Any?>().also { objects += it }
                    repeat(r.varint().toInt()) { list += value() }
                    properties('$') { _, _ -> }
                    r.varint() // longueur
                    list
                }
                'a' -> {
                    val length = r.varint().toInt()
                    val list = ArrayList<Any?>(List(length) { null }).also { objects += it }
                    properties('@') { k, v -> (k as? Long)?.toInt()?.takeIf { it in 0 until length }?.let { list[it] = v } }
                    r.varint()
                    list
                }
                '\'' -> {
                    val set = ArrayList<Any?>().also { objects += it }
                    while (peek() != ','.code) set += value()
                    r.pos++
                    r.varint()
                    set
                }
                ';' -> {
                    val map = LinkedHashMap<String, Any?>().also { objects += it }
                    while (peek() != ':'.code) {
                        val k = value()
                        map[k.toString()] = value()
                    }
                    r.pos++
                    r.varint()
                    map
                }
                else -> throw IOException("type V8 non pris en charge : 0x%02x à %d".format(tag, r.pos - 1))
            }
        }

        /** Paires clé/valeur jusqu'à [end], suivi du nombre de propriétés. */
        private fun properties(end: Char, onEach: (Any?, Any?) -> Unit) {
            while (peek() != end.code) {
                val key = value()
                onEach(key, value())
            }
            r.pos++
            r.varint()
        }

        private fun double(): Double = ByteBuffer.wrap(r.bytes(8)).order(ByteOrder.LITTLE_ENDIAN).getDouble()
    }

    private const val TRAILER_OFFSET = 0xFE
}
