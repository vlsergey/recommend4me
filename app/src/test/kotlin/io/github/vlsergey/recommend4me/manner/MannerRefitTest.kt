package io.github.vlsergey.recommend4me.manner

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** The words of the manners fitted again as the items with a text change by a hundredth, not of every new one. */
class MannerRefitTest {
    @Test
    fun `fitted again of none kept, or of a hundredth more or fewer texts`() {
        assertEquals(true, Manners.refits(null, 5), "none kept")
        assertEquals(false, Manners.refits(null, 0), "no text")
        assertEquals(false, Manners.refits(1000, 1009))
        assertEquals(true, Manners.refits(1000, 1010))
        assertEquals(true, Manners.refits(1000, 990))
        assertEquals(true, Manners.refits(30, 31), "of a few texts, one more")
    }
}
