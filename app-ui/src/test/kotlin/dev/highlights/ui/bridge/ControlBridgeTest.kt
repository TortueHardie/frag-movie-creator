package dev.highlights.ui.bridge

import dev.highlights.core.model.EffectDensity
import dev.highlights.ui.AppController
import dev.highlights.ui.MontageUiState
import dev.highlights.ui.RecordingPlatform
import dev.highlights.ui.UiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.seconds

class ControlBridgeTest : FunSpec({
    fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    test("le pont est éteint par défaut et jamais allumé dans l'application installée") {
        ControlBridge.enabled(props()) shouldBe false
        ControlBridge.enabled(props(ControlBridge.PROPERTY to "false")) shouldBe false
        ControlBridge.enabled(props(ControlBridge.PROPERTY to "true")) shouldBe true
        // Lanceur jpackage (MSI, portable) : refusé même si quelqu'un ajoute la propriété.
        ControlBridge.enabled(props(ControlBridge.PROPERTY to "true", ControlBridge.PACKAGED_PROPERTY to "C:\\Highlights\\Highlights.exe")) shouldBe false

        val real = RecordingPlatform()
        ControlBridge.platform(real, props()) shouldBeSameInstanceAs real
        val packaged = props(ControlBridge.PROPERTY to "true", ControlBridge.PACKAGED_PROPERTY to "x")
        ControlBridge.platform(real, packaged) shouldBeSameInstanceAs real
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AppController(scope, real, backendFactory = { error("pas de config") })
            ControlBridge.startIfEnabled(controller.state, controller, real, tempdir().toPath().resolve("b.json"), props = packaged).shouldBeNull()
            ControlBridge.startIfEnabled(controller.state, controller, real, tempdir().toPath().resolve("b.json"), props = props()).shouldBeNull()
        } finally {
            scope.cancel()
        }
    }

    test("état en JSON : chemins, durées, propriétés calculées, grands tableaux résumés") {
        val state = UiState()
        val json = BridgeJson.toJson(state).jsonObject
        json["config"]!!.jsonPrimitive.content shouldBe "Loading"
        json["canAnalyze"]!!.jsonPrimitive.content shouldBe "false"
        json["settings"]!!.jsonObject["durationText"]!!.jsonPrimitive.content shouldBe dev.highlights.core.i18n.I18n.duration(60.seconds)
        json["settings"]!!.jsonObject["targetDuration"]!!.jsonPrimitive.content shouldBe "1m"

        val big = BridgeJson.toJson(DoubleArray(1000) { it.toDouble() }).jsonObject
        big["size"]!!.jsonPrimitive.content shouldBe "1000"
        big["max"]!!.jsonPrimitive.content shouldBe "999.0"
    }

    test("les actions update* reçoivent les champs à changer") {
        val patch = BridgeJson.fromJson(
            buildJsonObject { put("fast", true); put("maxDurationText", "30s"); put("density", "heavy") },
            typeOf<(MontageUiState) -> MontageUiState>(),
        )
        @Suppress("UNCHECKED_CAST")
        val changed = (patch as (MontageUiState) -> MontageUiState)(MontageUiState())
        changed.fast shouldBe true
        changed.maxDurationText shouldBe "30s"
        changed.density shouldBe EffectDensity.HEAVY
        changed.zoom shouldBe true

        BridgeJson.fromJson(Json.parseToJsonElement("""["a.mp4", "b.mp4"]"""), typeOf<List<Path>>()) shouldBe listOf(Path.of("a.mp4"), Path.of("b.mp4"))
    }

    test("fin du journal") {
        val file = tempdir().toPath().resolve("log.txt")
        file.writeText((1..50).joinToString("") { "ligne $it\n" })
        ControlBridge.tail(file, 3) shouldBe "ligne 48\nligne 49\nligne 50\n"
        ControlBridge.tail(file, 500) shouldBe file.readText()
    }

    test("serveur : jeton exigé, état, action avec dossier passé sans boîte de dialogue, journal") {
        val dir = tempdir().toPath()
        val log = dir.resolve("highlights.log").also { it.writeText("première\ndernière\n") }
        val real = RecordingPlatform()
        val on = props(ControlBridge.PROPERTY to "true")
        val platform = ControlBridge.platform(real, on)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AppController(scope, platform, backendFactory = { error("pas de config") })
        val file = dir.resolve("control-bridge.json")
        val bridge = ControlBridge.startIfEnabled(controller.state, controller, platform, file, log, on).shouldNotBeNull()
        try {
            val info = Json.parseToJsonElement(file.readText()).jsonObject
            val url = info["url"]!!.jsonPrimitive.content
            val token = info["token"]!!.jsonPrimitive.content
            url shouldContain "127.0.0.1"
            val http = HttpClient.newHttpClient()
            fun call(path: String, body: JsonObject? = null, auth: Boolean = true): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI("$url$path"))
                if (auth) b.header("Authorization", "Bearer $token")
                if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body.toString())) else b.GET()
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }

            call("/state", auth = false).statusCode() shouldBe 401
            call("/state").body() shouldContain "\"settings\""

            val out = dir.resolve("sorties")
            val res = call("/action", buildJsonObject { put("name", "chooseOutputDir"); put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(out.toString())))) })
            res.statusCode() shouldBe 200
            res.body() shouldContain "chooseDirectory"
            controller.state.value.settings.outputDir shouldBe out
            real.directory.shouldBeNull()

            call("/action", buildJsonObject { put("name", "setThreshold"); put("args", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0.42)))) }).statusCode() shouldBe 200
            controller.state.value.settings.threshold shouldBe 0.42
            call("/state?path=settings.threshold").body().trim() shouldBe "0.42"

            call("/action", buildJsonObject { put("name", "nExistePas") }).statusCode() shouldBe 400
            call("/logs?lines=1").body() shouldBe "dernière\n"
        } finally {
            bridge.close()
            scope.cancel()
        }
        file.exists() shouldBe false
    }
})
