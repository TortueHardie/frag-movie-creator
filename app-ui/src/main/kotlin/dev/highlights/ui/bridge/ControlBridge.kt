package dev.highlights.ui.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.highlights.ui.Platform
import dev.highlights.ui.UiActions
import dev.highlights.ui.UiState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.awt.event.MouseEvent
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.valueParameters

private val log = KotlinLogging.logger {}

/**
 * Pont de pilotage pour le développement : un serveur HTTP local qui donne à un outil (une session Claude) l'état de
 * l'interface, ses actions, des captures de ses fenêtres, son arbre d'accessibilité, des clics et frappes simulés et la
 * fin du journal, sans prendre la souris ni le clavier de l'utilisateur.
 *
 * Sûreté :
 * - éteint par défaut ; allumé seulement par `-Dhighlights.bridge=true` (`gradlew :app-ui:run -Pbridge`) ;
 * - jamais dans l'application installée (MSI ou portable) : son lanceur jpackage pose `jpackage.app-path`, et le pont
 *   refuse alors de démarrer même si la propriété est là ; la construction vérifie aussi que le lanceur ne la pose pas ;
 * - écoute sur 127.0.0.1 seulement, port au hasard, jeton au hasard exigé à chaque requête (en-tête
 *   `Authorization: Bearer <jeton>` ou `X-Bridge-Token`) ; port et jeton dans [file], effacé à la fermeture.
 */
class ControlBridge private constructor(
    private val state: StateFlow<UiState>,
    private val actions: UiActions,
    private val platform: BridgePlatform,
    private val file: Path,
    private val logFile: Path,
) : AutoCloseable {
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "pont-pilotage").apply { isDaemon = true } }
    private val json = Json { prettyPrint = true }

    val port: Int get() = server.address.port

    private fun start() {
        route("/", ::help)
        route("/state", ::state)
        route("/actions", ::listActions)
        route("/action", ::action)
        route("/wait", ::waitIdle)
        route("/windows") { _, _ -> onUi { BridgeWindows.describe() } }
        route("/screenshot", ::screenshot)
        route("/tree", ::tree)
        route("/click", ::click)
        route("/scroll", ::scroll)
        route("/type", ::type)
        route("/key", ::key)
        route("/logs", ::logs)
        server.executor = executor
        server.start()
        Files.createDirectories(file.parent)
        val content = buildJsonObject {
            put("url", "http://127.0.0.1:$port")
            put("port", port)
            put("token", token)
            put("pid", ProcessHandle.current().pid())
            put("log", logFile.toString())
        }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        tmp.writeText(json.encodeToString(JsonElement.serializer(), content))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.warn { "Pont de pilotage ACTIF (développement) sur http://127.0.0.1:$port ; jeton dans $file" }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        runCatching { file.deleteIfExists() }
        log.info { "Pont de pilotage arrêté" }
    }

    // ------------------------------------------------------------------ routes

    /** Réponse d'une route : JSON, ou octets bruts avec leur type. */
    private class Raw(val bytes: ByteArray, val type: String)

    private fun route(path: String, handler: (HttpExchange, Map<String, String>) -> Any) {
        server.createContext(path) { ex ->
            try {
                if (path == "/" && ex.requestURI.path != "/") return@createContext reply(ex, 404, failure("Route inconnue : ${ex.requestURI.path} (GET / pour l'aide)"))
                if (!authorized(ex)) return@createContext reply(ex, 401, failure("Jeton absent ou faux (voir le fichier ${file})"))
                val result = handler(ex, query(ex))
                if (result is Raw) send(ex, 200, result.bytes, result.type) else reply(ex, 200, result as JsonElement)
            } catch (e: Throwable) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                log.warn(cause) { "Pont de pilotage : ${ex.requestMethod} ${ex.requestURI} a échoué" }
                reply(ex, if (cause is IllegalArgumentException || cause is IllegalStateException) 400 else 500, failure(cause.message ?: cause.toString()))
            } finally {
                ex.close()
            }
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val given = ex.requestHeaders.getFirst("X-Bridge-Token")
            ?: ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun failure(message: String): JsonElement = buildJsonObject { put("ok", false); put("error", message) }

    private fun reply(ex: HttpExchange, status: Int, body: JsonElement) =
        send(ex, status, json.encodeToString(JsonElement.serializer(), body).toByteArray(), "application/json; charset=utf-8")

    private fun send(ex: HttpExchange, status: Int, bytes: ByteArray, type: String) {
        ex.responseHeaders.set("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun query(ex: HttpExchange): Map<String, String> = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        .associate { part -> part.substringBefore('=').let(::decode) to part.substringAfter('=', "true").let(::decode) }

    private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)

    private fun body(ex: HttpExchange): JsonObject {
        require(ex.requestMethod == "POST") { "${ex.requestURI.path} attend un POST avec un corps JSON" }
        val text = ex.requestBody.readBytes().toString(Charsets.UTF_8).ifBlank { "{}" }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Exécute [block] sur le thread de l'interface (Swing), où vivent Compose et le contrôleur. */
    private fun <T> onUi(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(Callable(block))
        SwingUtilities.invokeLater(task)
        return try {
            task.get(60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun help(ex: HttpExchange, q: Map<String, String>): JsonElement = buildJsonObject {
        put("ok", true)
        putJsonArray("routes") {
            listOf(
                "GET /state[?path=a.b.0.c][&depth=N][&list=N] : état de l'interface (UiState) en JSON",
                "GET /actions : actions disponibles et leurs paramètres",
                "POST /action {name, args: [..] | {..}, paths: [..]} : appelle une action ; paths répond aux boîtes de dialogue",
                "GET /wait?timeout=600 : attend la fin du traitement en cours (job null, config chargée)",
                "GET /windows : fenêtres affichées (index, titre, position, échelle)",
                "GET /screenshot[?window=N] : PNG du contenu des fenêtres (toutes superposées par défaut)",
                "GET /tree[?all][&window=N][&text=…] : arbre d'accessibilité (rôle, nom, valeur, bornes, enabled)",
                "POST /click {x, y, window} | {text, role} | {id} [, button: right, count: 2]",
                "POST /scroll {x, y, window, amount} | {text, amount}",
                "POST /type {text [, target: {text|id}]} : clique la cible puis tape le texte",
                "POST /key {key: ENTER | TAB | ctrl+A …, window}",
                "GET /logs?lines=N : fin du journal de l'application",
            ).forEach { add(JsonPrimitive(it)) }
        }
    }

    private fun state(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val defaults = BridgeJson.Limits()
        val limits = BridgeJson.Limits(
            depth = q["depth"]?.toInt() ?: defaults.depth,
            list = q["list"]?.toInt() ?: defaults.list,
        )
        var element = BridgeJson.toJson(state.value, limits)
        q["path"]?.split('.')?.filter { it.isNotEmpty() }?.forEach { key ->
            element = when (element) {
                is JsonObject -> (element as JsonObject)[key] ?: error("« $key » absent ; champs : ${(element as JsonObject).keys.joinToString()}")
                is JsonArray -> (element as JsonArray).getOrNull(key.toInt()) ?: error("index $key hors de la liste")
                else -> error("« $key » : la valeur n'a pas de champs")
            }
        }
        return element
    }

    private val functions: Map<String, List<KFunction<*>>> by lazy {
        UiActions::class.declaredMemberFunctions.groupBy { it.name }
    }

    private fun listActions(ex: HttpExchange, q: Map<String, String>): JsonElement = buildJsonArray {
        for ((name, overloads) in functions.toSortedMap()) for (f in overloads) {
            add(
                buildJsonObject {
                    put("name", name)
                    putJsonArray("params") { f.valueParameters.forEach { p -> add(JsonPrimitive("${p.name}: ${p.type}")) } }
                },
            )
        }
    }

    private fun action(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val req = body(ex)
        val name = req["name"]?.jsonPrimitive?.contentOrNull ?: error("« name » manquant (GET /actions pour la liste)")
        val overloads = functions[name] ?: error("Action inconnue : $name (GET /actions pour la liste)")
        val paths = req["paths"]?.jsonArray?.map { Path.of(it.jsonPrimitive.content).toAbsolutePath().normalize() }.orEmpty()
        val args = req["args"]
        val (function, values) = overloads.firstNotNullOfOrNull { f -> runCatching { f to bind(f, args) }.getOrNull() }
            ?: overloads.first().let { f -> f to bind(f, args) } // relance pour le message d'erreur
        val (_, calls) = onUi { platform.scripted(paths) { function.callBy(values + (function.parameters.first() to actions)) } }
        return buildJsonObject {
            put("ok", true)
            put("action", name)
            if (calls.isNotEmpty()) putJsonArray("platform") { calls.forEach { add(JsonPrimitive(it)) } }
            state.value.error?.let { put("error", "${it.title} : ${it.message}") }
            state.value.job?.let { put("job", "${it.title} ${(it.fraction * 100).toInt()} %") }
        }
    }

    /** Arguments JSON (liste dans l'ordre, ou objet par nom) convertis aux paramètres de [f]. */
    private fun bind(f: KFunction<*>, args: JsonElement?): Map<KParameter, Any?> {
        val params = f.valueParameters
        return when (args) {
            null -> {
                require(params.all { it.isOptional }) { "${f.name} attend ${params.joinToString { "${it.name}: ${it.type}" }}" }
                emptyMap()
            }
            is JsonArray -> {
                require(args.size <= params.size && params.drop(args.size).all { it.isOptional }) {
                    "${f.name} attend ${params.size} argument(s) : ${params.joinToString { "${it.name}: ${it.type}" }}"
                }
                args.mapIndexed { i, a -> params[i] to BridgeJson.fromJson(a, params[i].type) }.toMap()
            }
            is JsonObject -> args.entries.associate { (k, v) ->
                val p = params.firstOrNull { it.name == k } ?: error("${f.name} n'a pas de paramètre « $k »")
                p to BridgeJson.fromJson(v, p.type)
            }
            else -> {
                require(params.size == 1) { "${f.name} attend ${params.size} argument(s)" }
                mapOf(params[0] to BridgeJson.fromJson(args, params[0].type))
            }
        }
    }

    private fun waitIdle(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val timeout = (q["timeout"]?.toDouble() ?: 600.0)
        val deadline = System.nanoTime() + (timeout * 1e9).toLong()
        // Laisse le temps à une action qui vient de partir de créer son traitement.
        Thread.sleep(q["settle"]?.toLong() ?: 300)
        while (true) {
            val s = state.value
            val idle = s.job == null && s.jobs.none { it.active } && s.config !is dev.highlights.ui.ConfigStatus.Loading &&
                (s.search?.loading != true) && (s.publish?.loading != true)
            if (idle) return buildJsonObject {
                put("ok", true)
                s.error?.let { put("error", "${it.title} : ${it.message}") }
            }
            if (System.nanoTime() > deadline) return buildJsonObject {
                put("ok", false)
                put("error", "Toujours occupé après $timeout s")
                s.job?.let { put("job", "${it.title} ${(it.fraction * 100).toInt()} % ${it.stage}") }
            }
            Thread.sleep(200)
        }
    }

    private fun screenshot(ex: HttpExchange, q: Map<String, String>): Any =
        Raw(onUi { BridgeWindows.screenshot(q["window"]?.toInt()) }, "image/png")

    private fun tree(ex: HttpExchange, q: Map<String, String>): JsonElement = onUi {
        val window = q["window"]?.toInt()
        val text = q["text"]
        val nodes = BridgeWindows.nodes().filter { window == null || it.window == window }
            .filter { text == null || it.label.contains(text, ignoreCase = true) }
        BridgeWindows.tree(nodes, all = q.containsKey("all") || text != null)
    }

    /** Point visé par une requête : coordonnées, ou centre d'un élément de l'arbre. */
    private fun point(req: JsonObject): Triple<Int, Int, BridgeWindows.Node?> {
        val x = req["x"]?.jsonPrimitive?.intOrNull
        val y = req["y"]?.jsonPrimitive?.intOrNull
        if (x != null && y != null) return Triple(x, y, null)
        val node = BridgeWindows.find(
            BridgeWindows.nodes().filter { n -> req["window"]?.jsonPrimitive?.intOrNull?.let { n.window == it } ?: true },
            req["id"]?.jsonPrimitive?.contentOrNull,
            req["text"]?.jsonPrimitive?.contentOrNull,
            req["role"]?.jsonPrimitive?.contentOrNull,
        )
        val b = node.bounds ?: error("L'élément ${node.id} n'a pas de position")
        return Triple(b.x + b.width / 2, b.y + b.height / 2, node)
    }

    private fun windowOf(req: JsonObject, node: BridgeWindows.Node?) = node?.window ?: req["window"]?.jsonPrimitive?.intOrNull ?: 0

    private fun click(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val req = body(ex)
        return onUi {
            val (x, y, node) = point(req)
            val button = if (req["button"]?.jsonPrimitive?.contentOrNull == "right") MouseEvent.BUTTON3 else MouseEvent.BUTTON1
            BridgeWindows.click(windowOf(req, node), x, y, button, req["count"]?.jsonPrimitive?.intOrNull ?: 1)
            clicked(x, y, node)
        }
    }

    private fun clicked(x: Int, y: Int, node: BridgeWindows.Node?) = buildJsonObject {
        put("ok", true)
        put("x", x); put("y", y)
        node?.let { put("element", BridgeWindows.nodeJson(it)) }
    }

    private fun scroll(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val req = body(ex)
        return onUi {
            val (x, y, node) = point(req)
            BridgeWindows.scroll(windowOf(req, node), x, y, req["amount"]?.jsonPrimitive?.intOrNull ?: 3)
            clicked(x, y, node)
        }
    }

    private fun type(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val req = body(ex)
        val text = req["text"]?.jsonPrimitive?.contentOrNull ?: error("« text » manquant")
        val target = req["target"] as? JsonObject
        val window = onUi {
            val node = target?.let { t ->
                val (x, y, n) = point(t)
                BridgeWindows.click(windowOf(t, n), x, y)
                n
            }
            windowOf(target ?: req, node)
        }
        // Le clic donne le focus au champ à la frame suivante : on la laisse passer avant de taper.
        if (target != null) Thread.sleep(150)
        onUi { BridgeWindows.type(window, text) }
        return buildJsonObject { put("ok", true); put("typed", text.length) }
    }

    private fun key(ex: HttpExchange, q: Map<String, String>): JsonElement {
        val req = body(ex)
        val spec = req["key"]?.jsonPrimitive?.contentOrNull ?: error("« key » manquant")
        onUi { BridgeWindows.key(req["window"]?.jsonPrimitive?.intOrNull ?: 0, spec) }
        return buildJsonObject { put("ok", true) }
    }

    private fun logs(ex: HttpExchange, q: Map<String, String>): Any {
        val lines = (q["lines"]?.toInt() ?: 100).coerceIn(1, 5000)
        if (!Files.isRegularFile(logFile)) error("Journal introuvable : $logFile")
        return Raw(tail(logFile, lines).toByteArray(), "text/plain; charset=utf-8")
    }

    companion object {
        /** Propriété système qui allume le pont. */
        const val PROPERTY = "highlights.bridge"

        /** Posée par le lanceur jpackage de l'application installée (MSI, portable). */
        const val PACKAGED_PROPERTY = "jpackage.app-path"

        /**
         * Le pont démarre-t-il ? Seulement si [PROPERTY] vaut true, et jamais dans l'application installée, même si
         * quelqu'un ajoute la propriété à son lanceur.
         */
        fun enabled(props: Properties = System.getProperties()): Boolean =
            props.getProperty(PROPERTY).toBoolean() && props.getProperty(PACKAGED_PROPERTY) == null

        /** Fichier où le pont écrit son adresse et son jeton. */
        fun defaultFile(): Path = System.getProperty("highlights.bridge.file")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".highlights", "control-bridge.json")

        /** Journal de l'application (même règle que logback.xml). */
        fun defaultLogFile(): Path = System.getProperty("highlights.logFile")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".highlights", "logs", "highlights.log")

        /** Plateforme à donner au contrôleur : celle du pont si le pont est allumé, sinon [real] telle quelle. */
        fun platform(real: Platform, props: Properties = System.getProperties()): Platform =
            if (enabled(props)) BridgePlatform(real) else real

        /**
         * Démarre le pont si [enabled] ; null sinon. [platform] doit venir de [ControlBridge.platform] (c'est elle qui
         * court-circuite les boîtes de dialogue).
         */
        fun startIfEnabled(
            state: StateFlow<UiState>,
            actions: UiActions,
            platform: Platform,
            file: Path = defaultFile(),
            logFile: Path = defaultLogFile(),
            props: Properties = System.getProperties(),
        ): ControlBridge? {
            if (props.getProperty(PROPERTY).toBoolean() && props.getProperty(PACKAGED_PROPERTY) != null) {
                log.warn { "Pont de pilotage demandé dans l'application installée : refusé" }
                return null
            }
            if (!enabled(props)) return null
            require(platform is BridgePlatform) { "Le contrôleur doit recevoir la plateforme de ControlBridge.platform" }
            return ControlBridge(state, actions, platform, file, logFile).also { it.start() }
        }

        /** Les [lines] dernières lignes d'un fichier texte, sans le lire en entier. */
        fun tail(file: Path, lines: Int): String = RandomAccessFile(file.toFile(), "r").use { raf ->
            var pos = raf.length()
            var found = 0
            val chunk = 8192
            while (pos > 0 && found <= lines) {
                val start = maxOf(0L, pos - chunk)
                val buf = ByteArray((pos - start).toInt())
                raf.seek(start)
                raf.readFully(buf)
                for (i in buf.indices.reversed()) if (buf[i] == '\n'.code.toByte()) {
                    found++
                    if (found > lines) { pos = start + i + 1; break }
                }
                if (found <= lines) pos = start
            }
            raf.seek(pos)
            val bytes = ByteArray((raf.length() - pos).toInt())
            raf.readFully(bytes)
            bytes.toString(Charsets.UTF_8)
        }
    }
}
