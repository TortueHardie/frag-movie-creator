package dev.highlights.analysis.outplayed

import dev.highlights.core.analysis.AnalysisContext
import dev.highlights.core.analysis.DetectorParams
import dev.highlights.core.analysis.DetectorRegistry
import dev.highlights.core.config.ConfigYaml
import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegResult
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.WindowGrid
import dev.highlights.core.progress.ProgressReporter
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Bases LevelDB et valeurs V8 construites ici, au format de celles d'Outplayed (aucune donnée réelle). */
class OutplayedLibraryTest : FunSpec({

    test("Snappy : littéraux et copies qui se chevauchent") {
        // "abc" en littéral, puis 9 octets copiés 3 octets en arrière.
        val compressed = byteArrayOf(12, 8, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 34, 3, 0)
        String(Snappy.decompress(compressed)) shouldBe "abcabcabcabc"
    }

    test("V8 : objet, tableau, nombres, chaînes et référence à un objet déjà lu") {
        val timing = V8.obj("past" to 8000, "future" to 4000)
        val value = V8.value(
            V8.obj(
                "gameId" to 21640,
                "name" to "Kill ×2",
                "ok" to true,
                "events" to V8.array(
                    V8.obj("type" to "kill", "time" to 1500.5, "timing" to timing),
                    V8.obj("type" to "kill", "time" to 2500.0, "timing" to V8.Ref(3)),
                ),
            ),
        )
        val decoded = V8Deserializer.decodeIndexedDbValue(value) as Map<*, *>
        decoded["gameId"] shouldBe 21640L
        decoded["name"] shouldBe "Kill ×2"
        decoded["ok"] shouldBe true
        val events = decoded["events"] as List<*>
        (events[0] as Map<*, *>)["time"] shouldBe 1500.5
        (events[1] as Map<*, *>)["timing"] shouldBe mapOf("past" to 8000L, "future" to 4000L)
    }

    test("LevelDB : dernière version de chaque clé, entre table compressée et journal, suppressions comprises") {
        val dir = tempdir().toPath()
        val big = ByteArray(40_000) { (it % 251).toByte() } // réparti sur deux blocs du journal
        LevelDb.table(
            dir.resolve("000005.ldb"),
            listOf(
                Triple("a", 5L, "ancien".toByteArray()),
                Triple("b", 7L, null),
                Triple("c", 2L, "table".toByteArray()),
            ),
        )
        LevelDb.log(
            dir.resolve("000007.log"),
            listOf(
                listOf("b" to "plus ancien que la suppression".toByteArray()) to 3L,
                listOf("a" to "récent".toByteArray(), "d" to big) to 10L,
            ),
        )
        val values = LevelDbSnapshot.read(dir).mapKeys { String(it.key.bytes) }
        String(values.getValue("a")) shouldBe "récent"
        values["b"].shouldBeNull()
        String(values.getValue("c")) shouldBe "table"
        values.getValue("d").contentEquals(big) shouldBe true
    }

    test("journal interrompu en pleine écriture : les lots complets restent lisibles") {
        val dir = tempdir().toPath()
        val log = dir.resolve("000003.log")
        LevelDb.log(log, listOf(listOf("a" to "1".toByteArray()) to 1L, listOf("b" to "2".toByteArray()) to 2L))
        val bytes = java.nio.file.Files.readAllBytes(log)
        log.writeBytes(bytes.copyOf(bytes.size - 3))
        LevelDbSnapshot.read(dir).mapKeys { String(it.key.bytes) }.keys shouldBe setOf("a")
    }

    test("bibliothèque : vidéo retrouvée par chemin puis par nom, événements triés") {
        val dir = tempdir().toPath()
        LevelDb.log(
            dir.resolve("000001.log"),
            listOf(
                listOf(
                    "m1" to match("C:\\Videos\\Valorant\\partie.mp4", listOf("death" to 57528.0, "kill" to 209560.0, "headshot" to 209560.0)),
                    "m2" to match("C:\\Videos\\Valorant\\clip.mp4", listOf("kill" to 5730.0)),
                    "autre" to V8.value(V8.obj("gameId" to 1, "matchCount" to 4)),
                ) to 1L,
            ),
        )
        val library = OutplayedLibrary.load(dir)
        library.size shouldBe 2
        val media = library.find(Path("c:/videos/valorant/PARTIE.mp4"))
        media shouldNotBe null
        media!!.events.map { it.type } shouldBe listOf("death", "kill", "headshot")
        media.events[1].at shouldBe 209560.milliseconds
        media.info["killCount"] shouldBe 1L
        // Capture déplacée : retrouvée par son nom de fichier.
        library.find(Path("D:\\Archives\\clip.mp4"))!!.events.single().at shouldBe 5730.milliseconds
        library.find(Path("D:\\inconnue.mp4")).shouldBeNull()
    }

    test("détecteur : types retenus, décalage appliqué, capture inconnue = signal absent") {
        val dir = tempdir().toPath().resolve("db").createDirectories()
        val video = tempdir().toPath().resolve("partie.mp4")
        LevelDb.log(
            dir.resolve("000001.log"),
            listOf(listOf("m" to match(video.toString(), listOf("kill" to 209560.0, "spike_defused" to 226018.0, "death" to 274491.0))) to 1L),
        )
        val yaml = """
            database: '${dir.toString().replace("'", "''")}'
            kinds: { kill: kill, death: death }
            offsets: { kill: -390ms, death: -390ms }
        """.trimIndent()
        fun analyze(path: Path) = kotlinx.coroutines.runBlocking {
            val media = MediaInfo(path, 0, 5.minutes)
            DetectorRegistry.fromServiceLoader()
                .create("outplayed-events", "game-events", DetectorParams(ConfigYaml.yaml.parseToYamlNode(yaml)))
                .analyze(AnalysisContext(media, WindowGrid(1.seconds, 500.milliseconds, media.duration), NoFfmpeg, dir, ProgressReporter.NONE))
        }
        val track = analyze(video)
        track.events.map { it.kind to it.at } shouldBe listOf("kill" to 209170.milliseconds, "death" to 274101.milliseconds)
        analyze(Path("D:\\ailleurs.mp4")).isMissing shouldBe true
    }
})

private fun match(path: String, events: List<Pair<String, Double>>): ByteArray = V8.value(
    V8.obj(
        "gameId" to 21640,
        "info" to V8.obj("agentKey" to "Cashew_PC_C", "killCount" to 1),
        "medias" to V8.array(
            V8.obj(
                "type" to "fullMatch",
                "path" to path,
                "events" to V8.array(*events.map { (type, time) -> V8.obj("type" to type, "time" to time, "data" to "1") }.toTypedArray()),
            ),
        ),
    ),
)

/** Sérialisation V8 minimale, avec l'en-tête IndexedDB et l'enveloppe Blink. */
private object V8 {
    class Obj(val props: List<Pair<String, Any>>)
    class Arr(val items: List<Any>)
    class Ref(val id: Int)

    fun obj(vararg props: Pair<String, Any>) = Obj(props.toList())
    fun array(vararg items: Any) = Arr(items.toList())

    fun value(root: Any): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint(1))
        out.write(byteArrayOf(0xFF.toByte(), 0x15, 0xFE.toByte()))
        out.write(ByteArray(12))
        out.write(byteArrayOf(0xFF.toByte(), 0x0F))
        write(out, root)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, v: Any) {
        when (v) {
            is Obj -> {
                out.write('o'.code)
                v.props.forEach { (k, x) ->
                    write(out, k)
                    write(out, x)
                }
                out.write('{'.code)
                out.write(varint(v.props.size.toLong()))
            }
            is Arr -> {
                out.write('A'.code)
                out.write(varint(v.items.size.toLong()))
                v.items.forEach { write(out, it) }
                out.write('$'.code)
                out.write(varint(0))
                out.write(varint(v.items.size.toLong()))
            }
            is Ref -> {
                out.write('^'.code)
                out.write(varint(v.id.toLong()))
            }
            is Int -> {
                out.write('I'.code)
                out.write(varint(((v shl 1) xor (v shr 31)).toLong()))
            }
            is Double -> {
                out.write('N'.code)
                out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array())
            }
            is Boolean -> out.write(if (v) 'T'.code else 'F'.code)
            is String -> {
                if (v.all { it.code < 256 }) {
                    out.write('"'.code)
                    out.write(varint(v.length.toLong()))
                    out.write(v.toByteArray(Charsets.ISO_8859_1))
                } else {
                    val bytes = v.toByteArray(Charsets.UTF_16LE)
                    out.write('c'.code)
                    out.write(varint(bytes.size.toLong()))
                    out.write(bytes)
                }
            }
            else -> error("type non géré : $v")
        }
    }
}

/** Écriture de fichiers LevelDB minimaux : journal (fragmenté sur des blocs de 32 Ko) et table à un bloc compressé. */
private object LevelDb {
    fun log(file: Path, batches: List<Pair<List<Pair<String, ByteArray>>, Long>>) {
        val out = ByteArrayOutputStream()
        for ((entries, seq) in batches) {
            val batch = ByteArrayOutputStream()
            batch.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).putInt(entries.size).array())
            for ((k, v) in entries) {
                batch.write(1)
                batch.write(varint(k.length.toLong()))
                batch.write(k.toByteArray())
                batch.write(varint(v.size.toLong()))
                batch.write(v)
            }
            var payload = batch.toByteArray()
            var first = true
            while (true) {
                val left = 32768 - out.size() % 32768
                if (left < 7) {
                    out.write(ByteArray(left))
                    continue
                }
                val n = minOf(payload.size, left - 7)
                val last = n == payload.size
                val type = when {
                    first && last -> 1
                    first -> 2
                    last -> 4
                    else -> 3
                }
                out.write(ByteArray(4)) // somme de contrôle, non vérifiée
                out.write(byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte(), type.toByte()))
                out.write(payload, 0, n)
                payload = payload.copyOfRange(n, payload.size)
                first = false
                if (last) break
            }
        }
        file.writeBytes(out.toByteArray())
    }

    /** Entrées (clé, séquence, valeur ou null pour une suppression), triées par clé. */
    fun table(file: Path, entries: List<Triple<String, Long, ByteArray?>>) {
        val out = ByteArrayOutputStream()
        val data = block(
            entries.sortedBy { it.first }.map { (k, seq, v) ->
                val trailer = (seq shl 8) or (if (v == null) 0L else 1L)
                k.toByteArray() + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(trailer).array() to (v ?: ByteArray(0))
            },
        )
        val compressed = snappyLiteral(data)
        out.write(compressed)
        out.write(byteArrayOf(1, 0, 0, 0, 0)) // type Snappy + somme de contrôle
        val handle = varint(0) + varint(compressed.size.toLong())
        val index = block(listOf("\u00ff".toByteArray() to handle))
        val indexOffset = out.size()
        out.write(index)
        out.write(byteArrayOf(0, 0, 0, 0, 0))
        val footer = ByteArray(48)
        val handles = varint(0) + varint(0) + varint(indexOffset.toLong()) + varint(index.size.toLong())
        handles.copyInto(footer)
        out.write(footer)
        file.writeBytes(out.toByteArray())
    }

    private fun block(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((k, v) in entries) {
            out.write(varint(0))
            out.write(varint(k.size.toLong()))
            out.write(varint(v.size.toLong()))
            out.write(k)
            out.write(v)
        }
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(1).array())
        return out.toByteArray()
    }

    private fun snappyLiteral(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint(data.size.toLong()))
        // Littéral long : étiquette 61 (longueur sur 2 octets).
        out.write(61 shl 2)
        out.write((data.size - 1) and 0xFF)
        out.write((data.size - 1) shr 8)
        out.write(data)
        return out.toByteArray()
    }
}

private fun varint(value: Long): ByteArray {
    val out = ByteArrayOutputStream()
    var v = value
    while (v >= 0x80) {
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
    out.write(v.toInt())
    return out.toByteArray()
}

private object NoFfmpeg : FfmpegService {
    override suspend fun probe(file: Path): MediaInfo = error("inutile")
    override suspend fun run(command: FfmpegCommand, stdout: StdoutHandler, onStderrLine: ((String) -> Unit)?): FfmpegResult = error("inutile")
    override suspend fun runProbe(command: FfmpegCommand, stdout: StdoutHandler): FfmpegResult = error("inutile")
}
