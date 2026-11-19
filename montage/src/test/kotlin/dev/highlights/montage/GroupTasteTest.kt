package dev.highlights.montage

import dev.highlights.core.model.MediaInfo
import dev.highlights.core.model.MontageSettings
import dev.highlights.core.model.VideoStream
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GroupTasteTest : FunSpec({
    val media = MediaInfo(Path("partie.mp4"), 1, 30.minutes, video = VideoStream(0, "h264", 3440, 1440, 60.0))
    val flick = KillTraits(flick = 0.8)
    val plain = KillTraits()

    fun group(at: Int, traits: List<KillTraits> = listOf(plain), outcome: RoundOutcome = RoundOutcome.NONE) =
        KillGroup(media, traits.indices.map { (at + it).seconds }, 0.5, emptyList(), emptyList(), traits = traits, outcome = outcome)

    /**
     * Corrections d'un utilisateur au goût caché : il garde de force les flicks que le plan avait laissés et écarte les
     * one taps ; le reste, il n'y touche pas, sauf [noise] fois sur 1 où il écarte ou garde au hasard.
     */
    fun edits(n: Int, seed: Int, noise: Double = 0.1): List<TasteEdit> {
        val random = Random(seed)
        return List(n) {
            val groups = List(12) {
                val traits = buildList {
                    if (random.nextDouble() < 0.3) add(GroupTrait.FLICK.key)
                    if (random.nextDouble() < 0.2) add(GroupTrait.ONE_TAP.key)
                    if (random.nextDouble() < 0.3) add(GroupTrait.MULTI_KILL.key)
                    if (random.nextDouble() < 0.4) add(GroupTrait.HEADSHOT.key)
                }
                val action = when {
                    random.nextDouble() < noise -> GroupAction.entries[random.nextInt(3)]
                    GroupTrait.FLICK.key in traits && random.nextDouble() < 0.5 -> GroupAction.FORCED
                    GroupTrait.ONE_TAP.key in traits && random.nextDouble() < 0.6 -> GroupAction.EXCLUDED
                    else -> GroupAction.NONE
                }
                TasteGroup(traits.sorted(), action)
            }
            TasteEdit("2026-11-19T00:00:00Z", "wardogs", "musique.mp3", groups = groups)
        }
    }

    test("sortes d'un groupe : multi-kill, tête, one tap, flick, ace, clutch") {
        GroupTrait.of(group(10)) shouldBe emptySet()
        GroupTrait.of(group(10, listOf(KillTraits(headshot = true, shots = 1), KillTraits(headshot = true, flick = 0.6)), RoundOutcome(ace = true, clutch = true, traded = true))) shouldBe
            GroupTrait.entries.toSet()
        // Un seul des deux kills à la tête : le groupe n'est pas « à la tête ».
        GroupTrait.of(group(10, listOf(KillTraits(headshot = true), plain))) shouldBe setOf(GroupTrait.MULTI_KILL)
    }

    test("goût caché retrouvé : flick préféré, one tap écarté, le reste près de zéro") {
        val learned = GroupTasteLearner.learn(edits(20, seed = 1))
        learned.active shouldBe true
        (learned.bonus.getValue(GroupTrait.FLICK) > 0.25) shouldBe true
        (learned.bonus.getValue(GroupTrait.ONE_TAP) < -0.1) shouldBe true
        // Neutres : sous le seuil du bruit, pas de bonus du tout.
        learned.bonus[GroupTrait.HEADSHOT] shouldBe null
        learned.bonus[GroupTrait.MULTI_KILL] shouldBe null
    }

    test("bornes : une sorte ±MAX_BONUS, un groupe ±MAX_TOTAL") {
        val extreme = List(10) {
            TasteEdit("t", "wardogs", "m", groups = listOf(
                TasteGroup(listOf("flick", "headshot", "oneTap"), GroupAction.FORCED),
                TasteGroup(emptyList(), GroupAction.EXCLUDED),
                TasteGroup(emptyList(), GroupAction.EXCLUDED),
            ))
        }
        val learned = GroupTasteLearner.learn(extreme)
        learned.bonus.values.forEach { (kotlin.math.abs(it) <= GroupTasteLearner.MAX_BONUS) shouldBe true }
        learned.bonus.getValue(GroupTrait.FLICK) shouldBe GroupTasteLearner.MAX_BONUS
        learned.of(group(10, listOf(KillTraits(headshot = true, shots = 1, flick = 0.9)))) shouldBe GroupTasteLearner.MAX_TOTAL
    }

    test("trop peu de corrections : inactif, groupes inchangés") {
        val learned = GroupTasteLearner.learn(edits(GroupTasteLearner.MIN_EDITS - 1, seed = 2))
        learned.active shouldBe false
        val groups = listOf(group(10, listOf(flick)), group(60))
        learned.applyTo(groups) shouldBe groups
    }

    test("corrections d'avant gouts-editeur (sans groupes) : gardées, pas apprises") {
        val old = List(10) { TasteEdit("t", "wardogs", "m", excluded = listOf("partie.mp4@3:12.400")) }
        GroupTasteLearner.learn(old).edits shouldBe 0
        GroupTasteLearner.learn(old).active shouldBe false
    }

    test("déterministe : mêmes corrections dans un autre ordre, même résultat") {
        val all = edits(12, seed = 3)
        GroupTasteLearner.learn(all) shouldBe GroupTasteLearner.learn(all)
        GroupTasteLearner.learn(all).bonus shouldBe GroupTasteLearner.learn(all.shuffled(Random(9))).bonus
    }

    test("enregistrement : écarté, gardé, épinglé, et un groupe à moitié écarté mais gardé compte comme gardé") {
        val a = group(100, listOf(flick))
        val b = group(200, listOf(plain, plain))
        val c = group(300)
        val d = group(400)
        val constraints = MontageConstraints(
            excluded = setOf(KillRef.of(media, 200.seconds), KillRef.of(media, 300.seconds)),
            forced = setOf(KillRef.of(media, 201.seconds)),
            pins = mapOf(KillRef.of(media, 100.seconds) to MontagePin.Drop),
        )
        GroupTasteLearner.record(listOf(a, b, c, d), constraints) shouldBe listOf(
            TasteGroup(listOf("flick"), GroupAction.FORCED, 1),
            TasteGroup(listOf("multiKill"), GroupAction.FORCED, 2),
            TasteGroup(emptyList(), GroupAction.EXCLUDED, 1),
            TasteGroup(emptyList(), GroupAction.NONE, 1),
        )
    }

    test("historique : groupes relus ; un vieux fichier sans groupes se relit encore") {
        val dir = Files.createTempDirectory("gouts-groupes")
        val history = TasteHistory(dir.resolve("taste-history.json"))
        val edit = edits(1, seed = 4).single()
        history.record(edit)
        history.load().edits.single() shouldBe edit
        history.file.writeText("""{"version": 1, "edits": [{"at": "t", "profile": "wardogs", "music": "m", "excluded": ["partie.mp4@3:12.400"]}]}""")
        history.load().edits.single().groups shouldBe emptyList()
    }

    test("au planificateur : les flicks préférés passent devant des kills seuls de même note quand la place manque") {
        // 40 kills seuls de même note, un sur deux avec un flick ; 12 s de montage n'en montrent pas autant.
        val groups = List(40) { if (it % 2 == 0) group(60 + it * 60, listOf(flick)) else group(60 + it * 60) }
        val settings = MontageSettings(killOffset = Duration.ZERO, maxDuration = 12.seconds)
        val bpm = 120.0
        val n = 240
        val music = MusicAnalysis(
            Path("musique.mp3"), 120.seconds, bpm, List(n) { (it * 60.0 / bpm).seconds }, downbeatPhase = 0,
            beatEnergy = DoubleArray(n) { 1.0 }, beatAccent = DoubleArray(n) { if (it % 4 == 0) 1.0 else 0.5 },
            sections = listOf(MusicSection(0, 32, -20.0, 0.1), MusicSection(32, 64, -14.0, 0.5), MusicSection(64, n, -8.0, 1.0)), dropBeat = 64,
        )
        val before = MontagePlanner.best(groups, music, settings)
        val taste = LearnedGroupTaste(mapOf(GroupTrait.FLICK to 0.5), mapOf(GroupTrait.FLICK to 6), 6, active = true)
        val after = MontagePlanner.best(taste.applyTo(groups), music, settings)
        fun flicks(p: MontagePlan) = p.clips.count { c -> GroupTrait.FLICK in GroupTrait.of(c.group) }
        (after.clips.size < groups.size) shouldBe true
        (flicks(after) > flicks(before)) shouldBe true
        // Tous les flicks d'abord (20), puis des kills sans flick pour finir de remplir.
        flicks(after) shouldBe minOf(after.clips.size, 20)
    }
})
