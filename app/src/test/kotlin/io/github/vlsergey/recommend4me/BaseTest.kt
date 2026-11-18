package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.CountingSuggester
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * EVERY TEST OF THE WHOLE APPLICATION INHERITS THIS, and sets nothing of the application's context of its own (user,
 * 2026-11-18): every plugin of the tests at once ([TestPlugins]), the data folder, the cheapest suggester of the
 * facets. So Spring starts the application once in a process of the tests and keeps it for every class — seconds, once;
 * the classes share its data, every test reading what it wrote by keys of its own. What a test needs otherwise — a
 * facet learnt by another suggester — it sets as the user would, a setting, and takes it back.
 *
 * The data folder is the process's own (`org.gradle.test.worker`): the processes side by side never open one database;
 * emptied once in a process, before the application opens a file in it ([TestDataFolder]). The suggester of the facets
 * the naive Bayes, fitted in milliseconds: the bilinear one, fitted by up to 1800 steps a facet, is a setting of the
 * scenarios of what it works out.
 */
@SpringBootTest
@Import(TestPlugins::class)
@TestPropertySource(
    properties =
        [
            "recommend4me.data-dir=build/test-data/application-\${org.gradle.test.worker:0}",
            "recommend4me.suggester=naive-bayes-1",
        ]
)
abstract class BaseTest {
    @Autowired lateinit var stores: Stores

    @Autowired lateinit var workload: Workload

    /**
     * Waits till the queue of the computations has done all it has to do soon: nothing running, nothing waiting its
     * turn, nothing planned within [SOON_SECONDS]. What a test checks of the background — of its own data, the other
     * classes' works the application of the tests holds too — is checked after it.
     */
    fun settled() {
        val quiet =
            waitFor(SETTLING_SECONDS) {
                val soon = Instant.now().plusSeconds(SOON_SECONDS)
                workload.running() == null &&
                    workload.waiting().isEmpty() &&
                    workload.planned().none { it.due.isBefore(soon) }
            }
        assertTrue(quiet, "the queue busy: ${workload.running()?.work}, ${workload.waiting()}, ${workload.planned()}")
    }

    companion object {
        /** How long the queue may take to settle, and how soon a work planned is one to wait for. */
        const val SETTLING_SECONDS = 120
        const val SOON_SECONDS = 15L

        @JvmStatic @BeforeAll fun clean(info: TestInfo) = TestDataFolder.clean(info.testClass.get())
    }
}

/**
 * Every plugin of the tests of the whole application, in one application: the sites, the encoder, the catalogue, the
 * suggester of counts. Never nested in a class of tests: Spring takes a configuration nested there for that class's own
 * context.
 */
@TestConfiguration
@Import(SitePlugins::class, TwoSitesPlugins::class, CountingSuggester.Plugin::class)
class TestPlugins {
    /** The source of [ScenarioTest]. */
    @Bean fun testSource(): Source = ScenarioTest.TestSource()
}
