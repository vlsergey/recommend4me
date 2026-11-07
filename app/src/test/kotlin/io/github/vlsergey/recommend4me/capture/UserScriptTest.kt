package io.github.vlsergey.recommend4me.capture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class UserScriptTest {

    /** The extension of this repository, beside the module. */
    private val folder = Path.of("../browser-extension/extension").toAbsolutePath().normalize()

    @Test
    fun `the userscript is the extension's scripts with the address of the application`() {
        val script =
            UserScriptController.assemble(
                folder,
                "https://pc.example.ts.net",
                listOf("author.today", "ficbook.net"),
                JsonMapper.builder().build(),
            )
        assertTrue(script.startsWith("// ==UserScript=="))
        assertTrue(
            "// @match        *://author.today/*" in script && "// @match        *://*.ficbook.net/*" in script,
            script.lines().take(20).joinToString("\n"),
        )
        assertTrue("// @downloadURL  https://pc.example.ts.net/userscript/recommend4me.user.js" in script)
        assertTrue("const R4M_SERVER = \"https://pc.example.ts.net\";" in script)
        // page.js and its parts run on demand, watch.js and the stand-in of the background script beside it
        assertTrue(
            "function runPage() {" in script && "function watchPage()" in script && "GM.xmlHttpRequest" in script
        )
        // Every part of page.js inside runPage(), in their order, page.js last
        val run = script.indexOf("function runPage() {")
        val at =
            UserScriptController.PAGE_PARTS.map { part ->
                script.indexOf(Files.readString(folder.resolve(part)).trim()).also {
                    assertTrue(it > run, "$part is not in runPage()")
                }
            }
        assertEquals(at.sorted(), at, "the parts of page.js are not in their order")
        assertEquals("page.js", UserScriptController.PAGE_PARTS.last())
        // The extension's words before the parts, which call them
        val words = Files.readString(folder.resolve("i18n.js")).trim()
        assertTrue(words in script && script.indexOf(words) < run)
    }

    @Test
    fun `the userscript has every part of page js the extension gives a page, in the same order`() {
        val parts =
            Files.list(folder).use { files ->
                files.map { it.fileName.toString() }.filter { PART.matches(it) }.toList().toSet()
            }
        assertEquals(parts, UserScriptController.PAGE_PARTS.toSet(), "a part of page.js is missing from the userscript")
        // The background script's PAGE_SCRIPTS, the words first
        val given =
            PART_NAMED.findAll(Files.readString(folder.resolve("background.js"))).map { it.groupValues[1] }.toList()
        assertEquals(UserScriptController.PAGE_PARTS, given, "the background script gives the parts in another order")
    }

    private companion object {
        val PART = Regex("""page(-[\w-]+)?\.js""")
        val PART_NAMED = Regex(""""(page(?:-[\w-]+)?\.js)"""")
    }
}
