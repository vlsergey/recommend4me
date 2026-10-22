package io.github.vlsergey.recommend4me.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageSecretsTest {

    @Test
    fun `scripts and session tokens are taken out, the rest written as it was`() {
        val html = """<html><head><meta name="csrf-token" content="e63F5Lf0"><meta property="og:image" content="/content/c.jpg">""" +
            """<script>var token = "x";</script></head><body><form><input type="hidden" name="authenticity_token" value="RN_82R">""" +
            """<input type="hidden" name="__RequestVerificationToken" value="ho-Jk4"><input name="q"></form>""" +
            "<div id=\"content\">The first line\n\n   the second  one</div></body></html>"
        val stripped = PageSecrets.stripped(html)
        listOf("e63F5Lf0", "RN_82R", "ho-Jk4", "var token").forEach { assertFalse(it in stripped, it) }
        // What the parsers read stays: a meta of the page, a field, a chapter's line breaks and spaces
        assertTrue("og:image" in stripped && "name=\"q\"" in stripped)
        assertTrue("The first line\n\n   the second  one" in stripped, stripped)
    }

    @Test
    fun `a page with none of them is kept unchanged`() {
        val html = "<html><head><title>A</title></head><body><p>a  b</p></body></html>"
        assertSame(html, PageSecrets.stripped(html))
        assertEquals(html, PageSecrets.stripped(html))
    }
}
