package io.github.vlsergey.recommend4me

import com.sun.net.httpserver.HttpServer
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.author.AuthorsController
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.capture.Captures
import io.github.vlsergey.recommend4me.correction.CorrectionsController
import io.github.vlsergey.recommend4me.duplicate.DuplicatesController
import io.github.vlsergey.recommend4me.item.ExplanationController
import io.github.vlsergey.recommend4me.item.FiltersController
import io.github.vlsergey.recommend4me.item.GradesController
import io.github.vlsergey.recommend4me.item.ItemsController
import io.github.vlsergey.recommend4me.item.MarksController
import io.github.vlsergey.recommend4me.item.SearchController
import io.github.vlsergey.recommend4me.job.Jobs
import io.github.vlsergey.recommend4me.job.JobsController
import io.github.vlsergey.recommend4me.model.ModelController
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.settings.SettingsController
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.tag.Tags
import io.github.vlsergey.recommend4me.tag.TagsController
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import org.junit.jupiter.api.BeforeAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * THE TWO SITES OF THE TESTS of one content type ([TwoSitesPlugins]): one application for every test of them —
 * [TwoSitesTest], [TwoSitesTagsTest], [TwoSitesSourcesTest] — over one data folder, as one class of tests had it, and
 * what they share: the host of the pictures, the controllers they ask, the pages they capture, a wait for the
 * background.
 */
@SpringBootTest(properties = ["recommend4me.data-dir=build/test-data/two-sites"])
@Import(TwoSitesPlugins::class)
abstract class TwoSitesTestBase {

    companion object {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        /** How many times the picture host was asked for a picture. */
        val pictureHits: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

        /** A picture of a few bytes: the application keeps what the host gives, it does not decode a preview. */
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

        val pictureBase: String
            get() = "http://127.0.0.1:${server.address.port}"

        private var started = false

        /**
         * The data folder emptied and the host of the pictures started, once: the application of the tests is one, kept
         * between their classes, its files open in it and the host its pictures are read from in it. The host stops
         * with the tests.
         */
        @JvmStatic
        @BeforeAll
        @Synchronized
        fun start() {
            if (started) return
            File("build/test-data/two-sites").deleteRecursively()
            server.createContext("/") { exchange ->
                pictureHits += exchange.requestURI.path
                if (exchange.requestURI.path == "/cover.png") {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, PNG.size.toLong())
                    exchange.responseBody.use { it.write(PNG) }
                } else {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
            server.start()
            Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
            started = true
        }
    }

    @Autowired lateinit var captures: CaptureController
    @Autowired lateinit var captured: Captures
    @Autowired lateinit var items: ItemsController
    @Autowired lateinit var searchController: SearchController
    @Autowired lateinit var filtersController: FiltersController
    @Autowired lateinit var explanationController: ExplanationController
    @Autowired lateinit var gradesController: GradesController
    @Autowired lateinit var marksController: MarksController
    @Autowired lateinit var corrections: CorrectionsController
    @Autowired lateinit var duplicates: DuplicatesController
    @Autowired lateinit var authors: AuthorsController
    @Autowired lateinit var tags: TagsController
    @Autowired lateinit var stores: Stores
    @Autowired lateinit var jobs: Jobs
    @Autowired lateinit var jobsApi: JobsController
    @Autowired lateinit var settingsApi: SettingsController
    @Autowired lateinit var settings: Settings
    @Autowired lateinit var model: ModelController
    @Autowired lateinit var tagService: Tags
    @Autowired lateinit var contexts: SourceContexts
    @Autowired lateinit var searchService: io.github.vlsergey.recommend4me.search.Search
    @Autowired lateinit var workSizes: WorkSizes

    protected fun alpha(body: String) =
        captures.capturePage(CaptureRequest("https://alpha.example/work/${body.substringBefore('|')}", body))

    protected fun beta(body: String) =
        captures.capturePage(CaptureRequest("https://beta.example/work/${body.substringBefore('|')}", body))

    /** The key of the normalized tag the site's tag [tag] of [source] is bound to. */
    protected fun tagOf(source: String, tag: String) =
        TagRepository.key(stores.type("books")!!.tags.bindings().getValue(SiteTagRef(source, tag)))

    /** Waits up to [seconds] for [condition]; true when it came. */
    protected fun waitFor(seconds: Int = 30, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /** Waits for a value that is not null yet. */
    protected fun <T : Any> waitForValue(read: () -> T?): T {
        waitFor { read() != null }
        return read()!!
    }
}
