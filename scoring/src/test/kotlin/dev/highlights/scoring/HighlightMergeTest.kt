package dev.highlights.scoring

import dev.highlights.core.model.Highlight
import dev.highlights.core.model.TimeRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

class HighlightMergeTest : FunSpec({
    fun h(id: String, start: Int, end: Int, enabled: Boolean = true) =
        Highlight(id, Path("x.mp4"), TimeRange(start.seconds, end.seconds), start.seconds, 0.8, enabled = enabled)

    test("un segment décoché reste décoché s'il recouvre un nouveau segment") {
        val previous = listOf(h("h001", 10, 20, enabled = false), h("h002", 50, 60))
        val fresh = listOf(h("h001", 5, 12), h("h002", 20, 30), h("h003", 52, 58))
        HighlightMerge.preserveDisabled(previous, fresh).map { it.enabled } shouldBe listOf(false, true, true)
    }
})
