package dev.highlights.montage

import dev.highlights.core.model.MusicFeel
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.writeBytes

/**
 * Ressenti des musiques (demande de l'utilisateur du 18/11 : juger une musique « à son contenu et pas seulement à son
 * BPM ») : réponses retenues par musique, puis un modèle appris de leurs mesures, utilisé seulement s'il prédit bien.
 */
class MusicFeelTest : FunSpec({
    fun features(bpm: Double, low: Double, perc: Double = 0.4) = MusicFeatures(bpm, 6.0, low, 6.0, 6.0, -8.0, perc)
    fun entry(name: String, feel: MusicFeel, f: MusicFeatures) = FeelEntry("$name|1", name, 1, feel, "2026-11-18T00:00:00Z", features = f)

    test("réponse : « ça va » confirme ce que le montage a fait, « trop rapide » donne calme, « pas assez » rapide") {
        FeelVote.GOOD.feel(hadFast = true) shouldBe MusicFeel.FAST
        FeelVote.GOOD.feel(hadFast = false) shouldBe MusicFeel.CALM
        FeelVote.TOO_FAST.feel(hadFast = true) shouldBe MusicFeel.CALM
        FeelVote.NOT_FAST_ENOUGH.feel(hadFast = false) shouldBe MusicFeel.FAST
    }

    test("ressenti gardé par nom et taille : une copie ailleurs le retrouve, auto l'efface") {
        val dir = tempdir().toPath()
        val store = MusicFeelStore(dir.resolve("sessions").resolve("music-feel.json"))
        val song = dir.resolve("a").also { Files.createDirectories(it) }.resolve("Chanson.mp3").also { it.writeBytes(ByteArray(1000)) }
        val copy = dir.resolve("b").also { Files.createDirectories(it) }.resolve("chanson.MP3").also { it.writeBytes(ByteArray(1000)) }
        val other = dir.resolve("c").also { Files.createDirectories(it) }.resolve("Chanson.mp3").also { it.writeBytes(ByteArray(2000)) }
        store.feelOf(song) shouldBe MusicFeel.AUTO
        store.record(song, MusicFeel.CALM, FeelVote.TOO_FAST)
        store.feelOf(song) shouldBe MusicFeel.CALM
        store.feelOf(copy) shouldBe MusicFeel.CALM
        // Même nom, autre taille : une autre version, sans ressenti.
        store.feelOf(other) shouldBe MusicFeel.AUTO
        store.record(song, MusicFeel.FAST)
        store.load().songs.size shouldBe 1
        store.feelOf(song) shouldBe MusicFeel.FAST
        store.record(song, MusicFeel.AUTO) shouldBe null
        store.load().songs.size shouldBe 0
    }

    test("pas de modèle tant qu'il manque des musiques : le jeu de départ (6 musiques, une seule calme) reste à la règle par défaut") {
        val seed = Json { ignoreUnknownKeys = true }.decodeFromString(
            StoredFeels.serializer(),
            javaClass.getResource("/ressenti/music-feel-seed.json")!!.readText(),
        )
        seed.songs.size shouldBe 6
        seed.songs.count { it.feel == MusicFeel.CALM } shouldBe 1
        seed.songs.all { it.features != null } shouldBe true
        val learned = MusicFeelLearner.learn(seed.songs)
        learned.songs shouldBe 6
        learned.fast shouldBe 5
        learned.active shouldBe false
        // Mesuré le 18/11 : 4 musiques sur 6 bien classées en validation croisée, loin des 80 % demandés (la seule calme ne
        // peut pas s'apprendre des cinq autres).
        learned.accuracy!! shouldBe (4.0 / 6 plusOrMinus 1e-9)
    }

    test("apprend une séparation nette, la valide en croisé, et ne prédit que les cas nets") {
        // Rapides : basses denses ; calmes : basses rares. Le tempo, lui, ne sépare rien (il varie dans les deux).
        val songs = (0 until 6).map { entry("rapide$it", MusicFeel.FAST, features(90.0 + 15 * it, 7.0 + 0.2 * it)) } +
            (0 until 5).map { entry("calme$it", MusicFeel.CALM, features(95.0 + 15 * it, 4.0 + 0.2 * it)) }
        val learned = MusicFeelLearner.learn(songs)
        learned.accuracy!! shouldBeGreaterThan 0.8
        learned.active shouldBe true
        learned.probability(features(120.0, 7.5)) shouldBeGreaterThan 0.7
        learned.probability(features(120.0, 4.2)) shouldBeLessThan 0.3
        // Déterministe : même entrée, même modèle.
        MusicFeelLearner.learn(songs) shouldBe learned
        MusicFeelLearner.loss(learned, songs) shouldBeLessThan 0.3
    }

    test("pas de modèle s'il prédit mal ce qu'il n'a pas vu, même avec assez de musiques") {
        // Étiquettes sans rapport avec les mesures : la validation croisée tombe, la règle par défaut reste.
        val songs = (0 until 10).map { i ->
            entry("m$i", if (i % 2 == 0) MusicFeel.FAST else MusicFeel.CALM, features(100.0 + i, 5.0 + (i * 7 % 10) / 10.0))
        }
        val learned = MusicFeelLearner.learn(songs)
        learned.accuracy shouldNotBe null
        learned.active shouldBe false
    }

    test("la musique dite par l'utilisateur l'emporte sur la prédiction ; sans modèle actif, auto") {
        val dir = tempdir().toPath()
        val store = MusicFeelStore(dir.resolve("music-feel.json"))
        val song = dir.resolve("dite.mp3").also { it.writeBytes(ByteArray(10)) }
        val unknown = dir.resolve("inconnue.mp3").also { it.writeBytes(ByteArray(20)) }
        store.record(song, MusicFeel.CALM)
        fun analysis(file: java.nio.file.Path, f: MusicFeatures?) = MusicAnalysis(
            file, kotlin.time.Duration.ZERO, 120.0, emptyList(), 0, DoubleArray(0), DoubleArray(0), emptyList(), 0, features = f,
        )
        val feels = MusicFeels(store)
        feels.resolve(analysis(song, features(150.0, 8.0))) shouldBe ResolvedFeel(MusicFeel.CALM, FeelSource.MARKED)
        feels.resolve(analysis(unknown, features(150.0, 8.0))).feel shouldBe MusicFeel.AUTO
    }
})
