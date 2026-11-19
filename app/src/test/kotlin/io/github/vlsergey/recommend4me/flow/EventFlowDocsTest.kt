package io.github.vlsergey.recommend4me.flow

import io.github.vlsergey.recommend4me.Application
import io.github.vlsergey.recommend4me.source.Source
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.fail

/**
 * THE FLOW OF THE EVENTS AND THE WORKS IS DOCUMENTED AS THE CODE IS: docs/events.md is made of the compiled application
 * ([EventFlow]) — what every request, the start and every loop of the background lead to, what every event and every
 * work leads to. A change of the code that changes the flow and a document not changed with it fail here, the document
 * written anew: commit it. So does a place planning a work whose kind the code names nowhere it can be read
 * ([EventFlow.unnamed]).
 *
 * The procedure of making it: `gradlew :app:test --tests "*EventFlowDocsTest*"`, then commit docs/events.md.
 */
class EventFlowDocsTest {
    private val doc = Path.of("../docs/events.md").toAbsolutePath().normalize()

    @Test
    fun `the flow of the events and the works is documented as the code makes it`() {
        fun of(c: Class<*>) = Path.of(c.protectionDomain.codeSource.location.toURI())
        val flow = EventFlow(CodeRead.of(of(Application::class.java), of(Source::class.java)))
        if (flow.unnamed.isNotEmpty())
            fail("Works planned whose kind the code names nowhere it is read (EventFlow): ${flow.unnamed}")
        val expected = EventFlowDoc.of(flow.edges)
        val written = if (Files.exists(doc)) Files.readString(doc).replace("\r\n", "\n") else null
        if (written != expected) {
            Files.writeString(doc, expected)
            fail("docs/events.md differed from the flow the code makes: written anew, commit it")
        }
    }
}
