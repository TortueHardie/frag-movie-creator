package dev.highlights.ui.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.toBufferedImage
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.accessibility.AccessibleContext
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Accès aux fenêtres de l'application pour le pont : liste, capture, arbre d'accessibilité, clics et frappes simulés.
 * Tout se fait sur le thread de l'interface (appelant : [ControlBridge]) et ne touche que les fenêtres de ce processus :
 *
 * - capture : Skia garde la dernière image dessinée de chaque fenêtre ([SkiaLayer.screenshot]), rendue même si la
 *   fenêtre est cachée derrière d'autres (jamais de capture de l'écran) ;
 * - arbre : l'accessibilité Java que Compose construit à partir de sa sémantique (texte, rôle, état, position) ;
 * - clics et frappes : événements AWT remis directement au composant de la fenêtre (`dispatchEvent`), jamais par
 *   `java.awt.Robot` : le curseur et le clavier de l'utilisateur ne bougent pas, la fenêtre ne passe pas devant.
 *
 * Coordonnées : pixels logiques (ceux de Compose en dp à l'échelle 1) relatifs au coin haut gauche du contenu de la
 * fenêtre, sans la barre de titre. Les captures sont en pixels physiques : diviser par `scale` (donné par /windows).
 */
object BridgeWindows {
    /** Fenêtres affichées de l'application, la principale d'abord. */
    fun windows(): List<Window> = Window.getWindows().filter { it.isShowing }
        .sortedBy { if (it.owner == null) 0 else 1 }

    fun describe(): JsonArray = buildJsonArray {
        windows().forEachIndexed { i, w ->
            add(
                buildJsonObject {
                    put("index", i)
                    put("title", (w as? Frame)?.title ?: (w as? Dialog)?.title ?: "")
                    put("kind", w.javaClass.simpleName)
                    put("focused", w.isFocused)
                    val c = content(w)
                    put("x", c.x); put("y", c.y); put("width", c.width); put("height", c.height)
                    put("scale", scaleOf(w).toDouble())
                    put("compose", layers(w).isNotEmpty())
                },
            )
        }
    }

    /** Zone du contenu à l'écran (hors barre de titre et bords). */
    private fun content(w: Window): Rectangle {
        val root = (w as? javax.swing.RootPaneContainer)?.contentPane ?: w
        val p = root.locationOnScreen
        return Rectangle(p.x, p.y, root.width, root.height)
    }

    private fun layers(c: Component): List<SkiaLayer> = when (c) {
        is SkiaLayer -> listOf(c)
        is Container -> c.components.flatMap(::layers)
        else -> emptyList()
    }

    private fun scaleOf(w: Window): Float =
        layers(w).firstOrNull()?.contentScale ?: w.graphicsConfiguration.defaultTransform.scaleX.toFloat()

    // ------------------------------------------------------------------ capture

    /**
     * PNG d'une fenêtre ([index]) ou, sans index, de toutes les fenêtres affichées superposées comme à l'écran
     * (boîte de dialogue au-dessus de la fenêtre principale).
     */
    fun screenshot(index: Int?): ByteArray {
        val all = windows()
        require(all.isNotEmpty()) { "Aucune fenêtre affichée" }
        val shown = if (index == null) all else listOf(all.getOrNull(index) ?: error("Fenêtre $index inexistante (0..${all.lastIndex})"))
        val scale = scaleOf(shown.first())
        val areas = shown.map(::content)
        val union = areas.reduce { a, b -> a.union(b) }
        val image = BufferedImage((union.width * scale).roundToInt(), (union.height * scale).roundToInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            for (w in shown) paint(w, g, union, scale)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun paint(w: Window, g: java.awt.Graphics2D, origin: Rectangle, scale: Float) {
        val skia = layers(w)
        if (skia.isEmpty()) {
            // Fenêtre Swing (sélecteur de dossier…) : dessinée par Swing lui-même, hors écran.
            val area = content(w)
            val root = (w as? javax.swing.RootPaneContainer)?.contentPane ?: w
            val sub = g.create(((area.x - origin.x) * scale).roundToInt(), ((area.y - origin.y) * scale).roundToInt(), (area.width * scale).roundToInt(), (area.height * scale).roundToInt()) as java.awt.Graphics2D
            sub.scale(scale.toDouble(), scale.toDouble())
            root.printAll(sub)
            sub.dispose()
            return
        }
        for (layer in skia) {
            val bitmap = layer.screenshot() ?: continue
            try {
                // toBufferedImage lit directement la mémoire du bitmap : le libérer seulement après l'avoir dessiné
                // (le libérer avant faisait planter la JVM).
                val img = bitmap.toBufferedImage()
                val p = layer.locationOnScreen
                val x = ((p.x - origin.x) * scale).roundToInt()
                val y = ((p.y - origin.y) * scale).roundToInt()
                g.drawImage(img, x, y, (layer.width * scale).roundToInt(), (layer.height * scale).roundToInt(), null)
            } finally {
                bitmap.close()
            }
        }
    }

    // ------------------------------------------------------------------ arbre

    /** Un élément de l'arbre : [bounds] en pixels logiques relatifs au contenu de la fenêtre [window]. */
    data class Node(
        val id: String,
        val window: Int,
        val role: String,
        val name: String?,
        val description: String?,
        val value: String?,
        val bounds: Rectangle?,
        val states: Set<String>,
        val depth: Int,
        /** Au moins en partie dans la zone visible de la fenêtre (sinon : faire défiler avant de cliquer). */
        val visible: Boolean,
    ) {
        val enabled: Boolean get() = "enabled" in states
        val label: String get() = listOfNotNull(name, value, description).joinToString(" ")
    }

    fun nodes(): List<Node> {
        val out = mutableListOf<Node>()
        windows().forEachIndexed { wi, w ->
            val origin = content(w)
            val roots = layers(w).map { it as Component }.ifEmpty { listOf(w) }
            roots.forEachIndexed { ri, root ->
                root.accessibleContext?.let { walk(it, "$wi.$ri", wi, origin, 0, out, mutableSetOf()) }
            }
        }
        return out
    }

    private fun walk(ctx: AccessibleContext, id: String, window: Int, origin: Rectangle, depth: Int, out: MutableList<Node>, seen: MutableSet<AccessibleContext>) {
        if (depth > 80 || !seen.add(ctx)) return
        val states = runCatching { ctx.accessibleStateSet.toArray().map { it.toDisplayString(Locale.ROOT) }.toSet() }.getOrDefault(emptySet())
        val bounds = runCatching {
            val comp = ctx.accessibleComponent ?: return@runCatching null
            val p = comp.locationOnScreen ?: return@runCatching null
            val size = comp.size ?: return@runCatching null
            Rectangle(p.x - origin.x, p.y - origin.y, size.width, size.height)
        }.getOrNull()
        val name = runCatching { ctx.accessibleName }.getOrNull()?.takeIf { it.isNotBlank() }
        val value = runCatching {
            ctx.accessibleEditableText?.let { t -> t.getTextRange(0, t.charCount) }
                ?: ctx.accessibleValue?.currentAccessibleValue?.toString()
                ?: if (name == null) ctx.accessibleText?.let { t -> if (t.charCount !in 1..2000) null else (t as? javax.accessibility.AccessibleExtendedText)?.getTextRange(0, t.charCount) ?: t.getAtIndex(javax.accessibility.AccessibleText.SENTENCE, 0) } else null
        }.getOrNull()
        out += Node(
            id = id,
            window = window,
            role = runCatching { ctx.accessibleRole.toDisplayString(Locale.ROOT) }.getOrDefault("?"),
            name = name,
            description = runCatching { ctx.accessibleDescription }.getOrNull()?.takeIf { it.isNotBlank() },
            value = value?.takeIf { it.isNotBlank() && it != ctx.accessibleName },
            bounds = bounds,
            states = states,
            depth = depth,
            visible = bounds != null && bounds.intersects(Rectangle(0, 0, origin.width, origin.height)),
        )
        val count = runCatching { ctx.accessibleChildrenCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { ctx.getAccessibleChild(i)?.accessibleContext }.getOrNull() ?: continue
            walk(child, "$id.$i", window, origin, depth + 1, out, seen)
        }
    }

    fun tree(nodes: List<Node>, all: Boolean): JsonArray = buildJsonArray {
        for (n in nodes) {
            // Sans `all` : seulement ce qui porte un texte, une valeur ou une action (le reste n'est que structure).
            if (!all && n.name == null && n.value == null && n.description == null && n.role !in INTERACTIVE) continue
            add(
                buildJsonObject {
                    put("id", n.id)
                    put("window", n.window)
                    put("role", n.role)
                    n.name?.let { put("name", it) }
                    n.value?.let { put("value", it) }
                    n.description?.let { put("description", it) }
                    put("enabled", n.enabled)
                    put("visible", n.visible)
                    n.bounds?.let { b -> putJsonArray("bounds") { add(kotlinx.serialization.json.JsonPrimitive(b.x)); add(kotlinx.serialization.json.JsonPrimitive(b.y)); add(kotlinx.serialization.json.JsonPrimitive(b.width)); add(kotlinx.serialization.json.JsonPrimitive(b.height)) } }
                    val extra = n.states - BORING
                    if (extra.isNotEmpty()) putJsonArray("states") { extra.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    put("depth", n.depth)
                },
            )
        }
    }

    private val INTERACTIVE = setOf("push button", "check box", "radio button", "text", "combo box", "toggle button", "slider", "menu item")
    private val BORING = setOf("enabled", "visible", "showing", "focusable", "opaque")

    /** Élément désigné par son identifiant, ou le premier dont le texte est exactement (puis contient) [text]. */
    fun find(nodes: List<Node>, id: String?, text: String?, role: String?): Node {
        if (id != null) return nodes.firstOrNull { it.id == id } ?: error("Aucun élément d'identifiant $id")
        require(text != null) { "Désigner un élément par « id » ou « text », ou donner « x » et « y »" }
        val all = nodes.filter { it.bounds != null && (role == null || it.role.equals(role, ignoreCase = true)) }
        fun pick(candidates: List<Node>) = candidates.firstOrNull { it.name.equals(text, ignoreCase = true) }
            ?: candidates.firstOrNull { it.label.equals(text, ignoreCase = true) }
            ?: candidates.firstOrNull { it.label.contains(text, ignoreCase = true) }
        pick(all.filter { it.visible })?.let { return it }
        pick(all)?.let { error("« $text » (${it.id}) est hors de la zone visible, en ${it.bounds} : faire défiler (/scroll) d'abord") }
        error("Aucun élément ne contient « $text »${role?.let { " (rôle $it)" } ?: ""}")
    }

    // ------------------------------------------------------------------ entrées simulées

    /** Composant qui reçoit les événements à ce point du contenu de la fenêtre, et le point dans ses coordonnées. */
    private fun target(window: Int, x: Int, y: Int): Pair<Component, java.awt.Point> {
        val w = windows().getOrNull(window) ?: error("Fenêtre $window inexistante")
        val root = (w as? javax.swing.RootPaneContainer)?.contentPane ?: w
        val deepest = SwingUtilities.getDeepestComponentAt(root, x, y) ?: root
        // Compose écoute le canevas de son SkiaLayer, qui est bien le composant le plus profond à ce point.
        return deepest to SwingUtilities.convertPoint(root, x, y, deepest)
    }

    /** Clic gauche (ou droit, double) au point ([x], [y]) du contenu de la fenêtre [window]. */
    fun click(window: Int, x: Int, y: Int, button: Int = MouseEvent.BUTTON1, count: Int = 1) {
        val (c, p) = target(window, x, y)
        val mask = when (button) {
            MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK
            MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }
        fun send(id: Int, modifiers: Int, clicks: Int) {
            c.dispatchEvent(MouseEvent(c, id, System.currentTimeMillis(), modifiers, p.x, p.y, clicks, button == MouseEvent.BUTTON3 && id == MouseEvent.MOUSE_PRESSED, if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_ENTERED) MouseEvent.NOBUTTON else button))
        }
        send(MouseEvent.MOUSE_ENTERED, 0, 0)
        send(MouseEvent.MOUSE_MOVED, 0, 0)
        for (i in 1..count) {
            send(MouseEvent.MOUSE_PRESSED, mask, i)
            send(MouseEvent.MOUSE_RELEASED, 0, i)
            send(MouseEvent.MOUSE_CLICKED, 0, i)
        }
    }

    /** Défilement de la molette au point donné ([amount] crans, positif = vers le bas). */
    fun scroll(window: Int, x: Int, y: Int, amount: Int) {
        val (c, p) = target(window, x, y)
        c.dispatchEvent(java.awt.event.MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, p.x, p.y, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, amount))
    }

    /** Composant qui reçoit le clavier de Compose dans la fenêtre [window] (le canevas de son SkiaLayer). */
    private fun keyTarget(window: Int): Component {
        val w = windows().getOrNull(window) ?: error("Fenêtre $window inexistante")
        return layers(w).firstOrNull()?.canvas ?: w
    }

    /** Texte tapé caractère par caractère, dans l'élément qui a le focus de Compose. */
    fun type(window: Int, text: String) {
        val c = keyTarget(window)
        for (ch in text) {
            val code = KeyEvent.getExtendedKeyCodeForChar(ch.code)
            val now = System.currentTimeMillis()
            c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_PRESSED, now, 0, code, ch))
            c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch))
            c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_RELEASED, now, 0, code, ch))
        }
    }

    /** Touche (« ENTER », « TAB », « BACK_SPACE », « ctrl+A »…) envoyée à la fenêtre [window]. */
    fun key(window: Int, spec: String) {
        val c = keyTarget(window)
        val parts = spec.split('+').map { it.trim() }
        var modifiers = 0
        for (m in parts.dropLast(1)) {
            modifiers = modifiers or when (m.lowercase()) {
                "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK
                "shift" -> InputEvent.SHIFT_DOWN_MASK
                "alt" -> InputEvent.ALT_DOWN_MASK
                else -> error("Modificateur inconnu : $m")
            }
        }
        val name = parts.last().uppercase().replace(' ', '_')
        val code = runCatching { KeyEvent::class.java.getField("VK_$name").getInt(null) }.getOrElse { error("Touche inconnue : ${parts.last()}") }
        val ch = when (code) {
            KeyEvent.VK_ENTER -> '\n'
            KeyEvent.VK_TAB -> '\t'
            KeyEvent.VK_BACK_SPACE -> '\b'
            else -> if (name.length == 1 && modifiers == 0) name[0].lowercaseChar() else KeyEvent.CHAR_UNDEFINED
        }
        val now = System.currentTimeMillis()
        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_PRESSED, now, modifiers, code, ch))
        if (ch != KeyEvent.CHAR_UNDEFINED && modifiers == 0 && code != KeyEvent.VK_BACK_SPACE) c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch))
        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_RELEASED, now, modifiers, code, ch))
    }

    fun nodeJson(n: Node): JsonObject = buildJsonObject {
        put("id", n.id)
        put("role", n.role)
        n.name?.let { put("name", it) }
        n.bounds?.let { putJsonObject("center") { put("x", it.centerX.roundToInt()); put("y", it.centerY.roundToInt()) } }
    }
}
