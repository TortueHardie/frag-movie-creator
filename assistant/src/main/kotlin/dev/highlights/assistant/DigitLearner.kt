package dev.highlights.assistant

import dev.highlights.core.ffmpeg.FfmpegCommand
import dev.highlights.core.ffmpeg.FfmpegService
import dev.highlights.core.ffmpeg.StdoutHandler
import dev.highlights.core.model.CropRegion
import dev.highlights.vision.CounterDecoder
import dev.highlights.vision.CounterReader
import dev.highlights.vision.GrayImage
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

/** Chiffres appris d'un compteur : zone du nombre (normalisée), modèles `digit_N` et ce qu'ils lisent sur la capture. */
data class LearnedCounter(val region: CropRegion, val digits: Map<Int, GrayImage>, val kills: Int, val detail: String)

/**
 * Apprend tout seul les chiffres d'un compteur du HUD (éliminations…) à partir du cadre que l'utilisateur a tracé autour
 * de lui, pour le lire avec `hud-counter` au lieu de comparer des images (`hud-change`, 23 kills dont 19 faux sur
 * Fortnite). Sans OCR : sur une vidéo 480p, les chiffres font 5x7 px.
 *
 * 1. La zone est lue sur toute la vidéo, 2 images par seconde (comme le détecteur). Le bloc de colonnes claires le plus à
 *    droite qui change au fil de la partie est celui des unités (une icône à côté reste allumée).
 * 2. **Graines** : la forme exacte des pixels clairs du chiffre des unités. Une forme fréquente ne montre qu'un chiffre
 *    (mesuré sur Fortnite : chaque forme vue 15 fois ou plus est pure), mais un chiffre a plusieurs formes selon le fond.
 * 3. **Regroupement** : formes de même largeur dont les images moyennes se ressemblent, puis formes qui **alternent**
 *    dans une même valeur du compteur (deux formes d'un chiffre alternent 21 à 177 fois ; deux chiffres différents se
 *    suivent au plus 6 fois, une fois par kill) : un groupe par chiffre vu.
 * 4. **Noms des chiffres** : la vidéo est relue avec un modèle par groupe ; la suite des valeurs tenues 5 s donne
 *    l'ordre du compteur (il monte de 1, parfois plus, revient à 0 à chaque partie ; un nombre à deux chiffres a un 1
 *    devant). L'ordre seul laisse un décalage possible ; la ressemblance avec des chiffres dessinés dans des polices
 *    grasses (Arial Black, Segoe UI Black…) l'ancre : affectation qui maximise ordre + 3 × ressemblance.
 * 5. **Redécoupe** : avec ces noms, le compteur est décodé ([CounterDecoder]), puis les chiffres sont redécoupés sur les
 *    images calmes (valeur tenue 2 s avant et après, lecture d'accord) : un chiffre jamais regroupé (un 6 vu 30 s) y
 *    gagne son modèle. Deux tours, sur les 10 meilleures affectations ; on garde celle que les lectures contredisent le moins.
 */
object DigitLearner {
    private const val FPS = 2.0
    private const val BRIGHT = 190
    private const val MIN_SEED = 15
    private const val GROUP_SIMILARITY = 0.9
    private const val ALTERNATIONS = 10
    private const val ALTERNATION_GAP = 6
    private const val MIN_RUN = 10
    private const val FONT_WEIGHT = 3.0
    private const val THRESHOLD = 0.75
    private const val SLACK = 4
    private const val CALM = 4
    private const val GAP_SAMPLES = 240
    private const val CANDIDATES = 10
    private val FONTS = listOf(Font("Arial Black", Font.PLAIN, 120), Font("Arial", Font.BOLD, 120), Font("Segoe UI Black", Font.PLAIN, 120), Font("Bahnschrift", Font.BOLD, 120))
    private val STEPS = mapOf(1 to 3.0, 2 to 1.5, 3 to 0.8, 4 to 0.5)

    /** Zone de la capture lue pour apprendre : cadre agrandi de 2 px, sur la grille paire de FFmpeg (voir HudCounterDetector). */
    internal fun area(box: PixelBox, width: Int, height: Int): PixelBox {
        val g = box.grow(2, 2, width, height)
        val x = g.x and 1.inv()
        val y = g.y and 1.inv()
        val w = (g.right - x + 1 and 1.inv()).coerceAtMost((width - x) and 1.inv())
        val h = (g.bottom - y + 1 and 1.inv()).coerceAtMost((height - y) and 1.inv())
        return PixelBox(x, y, w, h)
    }

    suspend fun learn(ffmpeg: FfmpegService, video: Path, width: Int, height: Int, box: PixelBox): LearnedCounter? {
        val area = area(box, width, height)
        val frames = decode(ffmpeg, video, area)
        val result = learn(frames)
        if (result == null) {
            log.info { "chiffres : rien appris dans la zone ${area.width}x${area.height} (${frames.size} images)" }
            return null
        }
        val region = PixelBox(area.x, area.y, result.zoneWidth, area.height).toRegion(width, height)
        return LearnedCounter(region, result.digits, result.kills, result.detail)
    }

    private suspend fun decode(ffmpeg: FfmpegService, video: Path, area: PixelBox): List<GrayImage> {
        var bytes = ByteArray(0)
        ffmpeg.run(
            FfmpegCommand(
                listOf(
                    "-i", video.toString(), "-map", "0:v:0", "-an", "-sn",
                    "-vf", "fps=$FPS,crop=${area.width}:${area.height}:${area.x}:${area.y},format=gray",
                    "-f", "rawvideo", "-pix_fmt", "gray", "-",
                ),
                "chiffres du compteur",
            ),
            StdoutHandler.Binary { input -> bytes = input.readAllBytes() },
        )
        val size = area.width * area.height
        return List(bytes.size / size) { i -> GrayImage(area.width, area.height, bytes.copyOfRange(i * size, (i + 1) * size)) }
    }

    /** Ce que l'apprentissage donne sur des images déjà lues : largeur utile de la zone (calée sur les unités) et chiffres. */
    internal data class Learned(val zoneWidth: Int, val digits: Map<Int, GrayImage>, val kills: Int, val detail: String)

    internal fun learn(frames: List<GrayImage>): Learned? {
        if (frames.size < 20) return null
        val w = frames[0].width
        val h = frames[0].height
        val unitsRight = unitsRight(frames, w, h) ?: return null
        // Zone lue par hud-counter : jusqu'à deux colonnes après le bord droit des unités (marge du modèle comprise).
        val zoneWidth = min(w, unitsRight + 3)
        val zone = frames.map { crop(it, zoneWidth) }

        // 2. Graines : formes exactes du chiffre des unités.
        val seeds = HashMap<String, MutableList<Glyph>>()
        zone.forEachIndexed { i, f -> glyphOf(i, f)?.let { g -> seeds.getOrPut(g.key) { mutableListOf() } += g } }
        val frequent = seeds.values.filter { it.size >= MIN_SEED }
        if (frequent.isEmpty()) return null
        val glyphHeight = frequent.groupingBy { it[0].bottom - it[0].top + 1 }.eachCount().maxBy { it.value }.key
        val kept = frequent.filter { it[0].bottom - it[0].top + 1 == glyphHeight }
        val top = kept.minOf { it[0].top } - 1
        val bottom = kept.maxOf { it[0].bottom } + 1
        if (top < 0 || bottom >= h) return null

        // 3. Groupes : images moyennes proches, puis formes qui alternent.
        val groups = mutableListOf<MutableList<Glyph>>()
        val groupImages = mutableListOf<DoubleArray>()
        val groupWidths = mutableListOf<Int>()
        for (seed in kept.sortedByDescending { it.size }) {
            val gw = seed[0].right - seed[0].left + 1
            val img = meanWindow(zone, seed, gw, top, bottom)
            val k = groupImages.indices.firstOrNull { groupWidths[it] == gw && ncc(groupImages[it], img) >= GROUP_SIMILARITY }
            if (k == null) {
                groups += seed.toMutableList(); groupImages += img; groupWidths += gw
            } else {
                groups[k] += seed
                groupImages[k] = meanWindow(zone, groups[k], gw, top, bottom)
            }
        }
        val groupOf = HashMap<Int, Int>()
        groups.forEachIndexed { k, g -> g.forEach { groupOf[it.frame] = k } }
        val parent = IntArray(groups.size) { it }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        val alternations = HashMap<Pair<Int, Int>, Int>()
        groupOf.keys.sorted().zipWithNext().forEach { (a, b) ->
            val ga = groupOf.getValue(a)
            val gb = groupOf.getValue(b)
            if (b - a <= ALTERNATION_GAP && ga != gb) alternations.merge(min(ga, gb) to max(ga, gb), 1, Int::plus)
        }
        alternations.filterValues { it >= ALTERNATIONS }.keys.forEach { (a, b) -> parent[root(a)] = root(b) }
        val clusters = groups.indices.groupBy { root(it) }.values
            .map { ks -> ks.flatMap { groups[it] }.map { it.frame } }
            .sortedByDescending { it.size }.take(10)
        val clusterDigits = clusters.mapNotNull { cut(zone, it, top, bottom, null) }
        if (clusterDigits.size < 3) return null

        // 4. Relecture avec un modèle par groupe (clés 100+k : aucune n'est « 0 »), suite des valeurs tenues, noms.
        val keyed = clusterDigits.mapIndexed { k, img -> 100 + k to img }.toMap()
        val rawReader = CounterReader(keyed, THRESHOLD, SLACK)
        val raw = zone.map { rawReader.readPair(it, noTens = null) }
        val sequence = steadyValues(raw)
        val fonts = Array(keyed.size) { k -> DoubleArray(10) { d -> fontLikeness(keyed.getValue(100 + k), d) } }
        val candidates = bestLabelings(keyed.size, sequence, fonts, CANDIDATES)
        if (candidates.isEmpty()) return null

        // 5. Redécoupe sur les images calmes, deux tours, pour chaque affectation retenue.
        var best: Learned? = null
        var bestFit = Double.NEGATIVE_INFINITY
        for (labels in candidates) {
            var reads: List<Int?> = raw.map { p -> p?.let { labels[it.units - 100] + 10 * (it.tens?.let { t -> labels[t - 100] } ?: 0) } }
            var path = decoder().decode(reads)
            var digits: Map<Int, GrayImage> = emptyMap()
            repeat(2) {
                digits = recut(zone, path, reads, top, bottom)
                if (digits.isEmpty()) return@repeat
                digits = digits + fillGaps(zone, reads, digits, top, bottom)
                val reader = CounterReader(digits, THRESHOLD, SLACK)
                reads = zone.map { reader.read(it)?.value }
                path = decoder().decode(reads)
            }
            if (digits.isEmpty()) continue
            val fit = objective(reads, path)
            val kills = (1 until path.size).sumOf { maxOf(0, path[it] - path[it - 1]) }
            log.info { "chiffres : noms ${labels.toList()} → note ${"%.1f".format(fit)}, $kills kills, chiffres ${digits.keys.sorted()}" }
            if (fit > bestFit) {
                bestFit = fit
                best = Learned(zoneWidth, digits, kills, "(chiffres appris : ${digits.keys.sorted().joinToString("")}, $kills kills)")
            }
        }
        return best
    }

    /**
     * Note d'une affectation : ce que le décodage lui-même optimise, lectures d'accord moins lectures contraires, moins
     * le coût de chaque pas et de chaque retour à zéro. Compter seulement les accords favorisait une affectation fausse
     * à qui il manque un chiffre (ses images deviennent illisibles au lieu de contraires) : sur Fortnite, 38 kills notés
     * au-dessus des 31 justes. Une fausse affectation paie un retour à zéro inventé (25) et des pas en trop.
     */
    private fun objective(reads: List<Int?>, path: IntArray): Double {
        var s = 0.0
        for (i in path.indices) {
            val r = reads[i] ?: continue
            s += if (r == path[i]) 1.0 else -1.0
        }
        for (i in 1 until path.size) {
            val step = path[i] - path[i - 1]
            if (step > 0) s -= 3.0 + 0.5 * (step - 1) else if (step < 0) s -= 25.0
        }
        return s
    }

    private fun decoder() = CounterDecoder(maxValue = 99, maxStep = 5, stepCost = 3.0, resetCost = 25.0, missCost = 1.0)

    /** Bord droit des unités : fin du bloc de colonnes claires le plus à droite dont l'allumage varie (voir UnitsFinder). */
    internal fun unitsRight(frames: List<GrayImage>, w: Int, h: Int): Int? {
        val n = frames.size.toDouble()
        val sum = DoubleArray(w * h)
        for (f in frames) for (i in 0 until w * h) if ((f.pixels[i].toInt() and 0xFF) >= BRIGHT) sum[i] += 1.0
        val mean = DoubleArray(w) { x -> (0 until h).sumOf { y -> sum[y * w + x] } / (n * h) }
        val std = DoubleArray(w) { x -> (0 until h).sumOf { y -> val m = sum[y * w + x] / n; sqrt((m - m * m).coerceAtLeast(0.0)) } / h }
        var right: Int? = null
        var x = 0
        while (x < w) {
            if (mean[x] > 0.05) {
                val start = x
                while (x + 1 < w && mean[x + 1] > 0.05) x++
                val block = start..x
                if (block.sumOf { std[it] } / block.sumOf { mean[it] } > 0.6) right = x
            }
            x++
        }
        return right
    }

    private fun crop(f: GrayImage, width: Int): GrayImage {
        val out = ByteArray(width * f.height)
        for (y in 0 until f.height) System.arraycopy(f.pixels, y * f.width, out, y * width, width)
        return GrayImage(width, f.height, out)
    }

    /** Chiffre des unités d'une image : colonnes claires contiguës qui finissent à la dernière colonne claire. */
    private class Glyph(val frame: Int, val left: Int, val right: Int, val top: Int, val bottom: Int, val key: String)

    private fun glyphOf(i: Int, f: GrayImage): Glyph? {
        fun lit(x: Int, y: Int) = f[x, y] >= BRIGHT
        val cols = (0 until f.width).filter { x -> (0 until f.height).any { y -> lit(x, y) } }
        val right = cols.lastOrNull() ?: return null
        if (right < f.width - 6) return null
        var left = right
        while (left - 1 >= 0 && (0 until f.height).any { y -> lit(left - 1, y) }) left--
        if (right - left + 1 > 7 || left < 1) return null
        val rows = (0 until f.height).filter { y -> (left..right).any { x -> lit(x, y) } }
        val key = buildString {
            append(rows.first()).append(':').append(rows.last() - rows.first() + 1).append('x').append(right - left + 1).append(':')
            for (y in rows.first()..rows.last()) for (x in left..right) append(if (lit(x, y)) '1' else '0')
        }
        // La hauteur fait partie de la clé, la position verticale non (même forme un pixel plus haut).
        return Glyph(i, left, right, rows.first(), rows.last(), key.substringAfter(':'))
    }

    private fun meanWindow(zone: List<GrayImage>, items: List<Glyph>, width: Int, top: Int, bottom: Int): DoubleArray {
        val ww = width + 2
        val hh = bottom - top + 1
        val acc = DoubleArray(ww * hh)
        var n = 0
        for (g in items) {
            val x0 = g.right - width
            if (x0 < 0 || g.right + 1 >= zone[g.frame].width) continue
            for (y in 0 until hh) for (x in 0 until ww) acc[y * ww + x] += zone[g.frame][x0 + x, top + y].toDouble()
            n++
        }
        if (n > 0) for (i in acc.indices) acc[i] /= n
        return acc
    }

    /**
     * Modèle d'un chiffre découpé comme à la main sur les images [frames] : bord droit du chiffre trouvé sur les niveaux
     * de gris (colonne plus claire que le fond de 60), largeur la plus fréquente, moyenne des fenêtres chiffre + 1 px.
     */
    private fun cut(zone: List<GrayImage>, frames: List<Int>, top: Int, bottom: Int, minCount: Int?): GrayImage? {
        val found = frames.mapNotNull { i -> segment(zone[i], top, bottom)?.let { (l, r) -> Triple(i, l, r) } }
        if (found.isEmpty()) return null
        val width = found.groupingBy { it.third - it.second + 1 }.eachCount().maxBy { it.value }.key
        val good = found.filter { it.third - it.second + 1 == width && it.third - width >= 0 && it.third + 2 <= zone[it.first].width }
        if (good.size < (minCount ?: 1)) return null
        val ww = width + 2
        val hh = bottom - top + 1
        val acc = DoubleArray(ww * hh)
        for ((i, _, r) in good) for (y in 0 until hh) for (x in 0 until ww) acc[y * ww + x] += zone[i][r - width + x, top + y].toDouble()
        return GrayImage(ww, hh, ByteArray(ww * hh) { (acc[it] / good.size).roundToInt().coerceIn(0, 255).toByte() })
    }

    /** Dernier bloc de colonnes plus claires que le fond (médiane de la zone) de 60, s'il touche le bord droit utile. */
    private fun segment(f: GrayImage, top: Int, bottom: Int): Pair<Int, Int>? {
        val values = IntArray(f.width * (bottom - top + 1)) { f[it % f.width, top + it / f.width] }.sorted()
        val bg = values[values.size / 2]
        val on = BooleanArray(f.width) { x -> (top..bottom).any { y -> f[x, y] - bg > 60 } }
        var right = f.width - 1
        while (right >= 0 && !on[right]) right--
        if (right < f.width - 6) return null
        var left = right
        while (left - 1 >= 0 && on[left - 1]) left--
        return left to right
    }

    /** Suite des valeurs tenues au moins [MIN_RUN] images (lectures illisibles sautées), sans répétition. */
    internal fun steadyValues(raw: List<dev.highlights.vision.DigitPair?>): List<Pair<Int?, Int>> {
        val out = mutableListOf<Pair<Int?, Int>>()
        var current: Pair<Int?, Int>? = null
        var count = 0
        fun flush() {
            val c = current ?: return
            if (count >= MIN_RUN && out.lastOrNull() != c) out += c
        }
        for (p in raw) {
            if (p == null) continue
            val v = p.tens to p.units
            if (v == current) { count++; continue }
            flush()
            current = v
            count = 1
        }
        flush()
        return out
    }

    /**
     * Affectations groupe → chiffre (injectives) les mieux notées : ordre du compteur sur [sequence] (+1 récompensé,
     * retour à 0 permis, recul puni) + [FONT_WEIGHT] × ressemblance aux polices. Essai exhaustif : 10!/(10−k)! au plus.
     */
    internal fun bestLabelings(k: Int, sequence: List<Pair<Int?, Int>>, fonts: Array<DoubleArray>, count: Int): List<IntArray> {
        val keys = sequence.map { (t, u) -> (t?.minus(100) ?: -1) to u - 100 }
        val best = ArrayList<Pair<Double, IntArray>>()
        val labels = IntArray(k)
        val used = BooleanArray(10)
        fun score(): Double {
            var s = 0.0
            var prev = -1
            for ((t, u) in keys) {
                val v = labels[u] + if (t >= 0) 10 * labels[t] else 0
                if (prev >= 0) {
                    val step = v - prev
                    s += STEPS[step] ?: if (v == 0 && prev > 0) 1.5 else -2.0
                }
                prev = v
            }
            for (c in 0 until k) s += FONT_WEIGHT * fonts[c][labels[c]]
            return s
        }
        fun place(c: Int) {
            if (c == k) {
                val s = score()
                if (best.size < count || s > best.last().first) {
                    best += s to labels.copyOf()
                    best.sortByDescending { it.first }
                    if (best.size > count) best.removeAt(best.size - 1)
                }
                return
            }
            for (d in 0..9) {
                if (used[d]) continue
                used[d] = true; labels[c] = d
                place(c + 1)
                used[d] = false
            }
        }
        place(0)
        return best.map { it.second }
    }

    /** Ressemblance (corrélation moyenne) d'un modèle de chiffre, marges ôtées, avec le chiffre [d] de polices grasses. */
    internal fun fontLikeness(t: GrayImage, d: Int): Double {
        val w = t.width - 2
        val h = t.height - 2
        if (w < 1 || h < 1) return 0.0
        val core = DoubleArray(w * h) { t[1 + it % w, 1 + it / w].toDouble() }
        return FONTS.map { font -> ncc(core, render(font, d, w, h)) }.average()
    }

    private fun render(font: Font, d: Int, w: Int, h: Int): DoubleArray {
        val big = BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY)
        val g = big.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font
        g.drawString(d.toString(), 20, 150)
        g.dispose()
        var x0 = 200; var y0 = 200; var x1 = -1; var y1 = -1
        for (y in 0 until 200) for (x in 0 until 200) if (big.raster.getSample(x, y, 0) > 128) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        if (x1 < 0) return DoubleArray(w * h)
        val small = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g2 = small.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.drawImage(big.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1), 0, 0, w, h, null)
        g2.dispose()
        return DoubleArray(w * h) { small.raster.getSample(it % w, it / w, 0).toDouble() }
    }

    /** Chiffres redécoupés sur les images calmes : valeur décodée tenue [CALM] images avant et après, lecture d'accord. */
    private fun recut(zone: List<GrayImage>, path: IntArray, reads: List<Int?>, top: Int, bottom: Int): Map<Int, GrayImage> {
        val byDigit = HashMap<Int, MutableList<Int>>()
        for (i in CALM until path.size - CALM) {
            val v = path[i]
            if ((i - CALM..i + CALM).any { path[it] != v }) continue
            val r = reads[i]
            if (r != null && r != v) continue
            byDigit.getOrPut(v % 10) { mutableListOf() } += i
        }
        val cuts = byDigit.mapNotNull { (d, frames) -> cut(zone, frames, top, bottom, 4)?.let { d to it } }.toMap()
        // Tous les modèles à la même hauteur (CounterReader) : celle du découpage.
        return cuts
    }

    /**
     * Chiffre jamais regroupé (vu trop peu : le 6 de Fortnite, 30 s en tout) : lu comme un autre, il fait inventer au
     * décodage un retour à zéro (5 → 8 → 0 → 5 → 7 : 8 kills de trop). Quand les lectures tiennent v puis v + 2 à moins
     * de 2 min d'écart, les images entre les deux montrent v + 1 : son modèle y est découpé.
     */
    private fun fillGaps(zone: List<GrayImage>, reads: List<Int?>, digits: Map<Int, GrayImage>, top: Int, bottom: Int): Map<Int, GrayImage> {
        val runs = steadyRuns(reads)
        val frames = HashMap<Int, MutableList<Int>>()
        for ((k, a) in runs.withIndex()) {
            val missing = (a.value + 1) % 10
            if (missing in digits) continue
            // Valeur v + 2 suivante, à moins de 2 min, sans valeur plus basse entre (nouvelle partie) : ce qu'il y a
            // entre les deux est v + 1, même lu de travers (le 6 de Fortnite est lu 8 en attendant d'avoir son modèle).
            val later = runs.drop(k + 1).takeWhile { it.start - a.end <= GAP_SAMPLES && it.value >= a.value }
            val b = later.firstOrNull { it.value == a.value + 2 } ?: continue
            if (later.takeWhile { it !== b }.any { it.value == a.value + 1 }) continue
            frames.getOrPut(missing) { mutableListOf() } += (a.end + 1 until b.start)
        }
        return frames.mapNotNull { (d, f) -> cut(zone, f, top, bottom, 4)?.let { d to it } }.toMap()
    }

    private data class Run(val value: Int, val start: Int, val end: Int)

    /** Valeurs lues tenues au moins [MIN_RUN] images (les illisibles ne coupent pas une valeur). */
    private fun steadyRuns(reads: List<Int?>): List<Run> {
        val out = mutableListOf<Run>()
        var value: Int? = null
        var start = 0
        var end = 0
        var count = 0
        fun flush() { val v = value; if (v != null && count >= MIN_RUN) out += Run(v, start, end) }
        reads.forEachIndexed { i, r ->
            if (r == null) return@forEachIndexed
            if (r == value) { end = i; count++; return@forEachIndexed }
            flush()
            value = r; start = i; end = i; count = 1
        }
        flush()
        return out
    }

    private fun ncc(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val ma = a.average()
        val mb = b.average()
        var c = 0.0; var ea = 0.0; var eb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; c += x * y; ea += x * x; eb += y * y }
        val d = sqrt(ea * eb)
        return if (d < 1e-9) 0.0 else c / d
    }
}
