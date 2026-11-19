package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.background.HeldBackground
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.CountingSuggester
import io.github.vlsergey.recommend4me.task.Tasks
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
 * facets, the background held. So Spring starts the application once in a process of the tests and keeps it for every
 * class — seconds, once; the classes share its data, every test reading what it wrote by keys of its own. What a test
 * needs otherwise — a facet learnt by another suggester — it sets as the user would, a setting, and takes it back.
 *
 * The data folder is the process's own (`org.gradle.test.worker`): the processes side by side never open one database;
 * emptied once in a process, before the application opens a file in it ([TestDataFolder]). The suggester of the facets
 * the naive Bayes, fitted in milliseconds: the bilinear one, fitted by up to 1800 steps a facet, is a setting of the
 * scenarios of what it works out. NOTHING IN THE BACKGROUND RUNS BY ITSELF ([HeldBackground]): a test runs it.
 */
@SpringBootTest
@Import(TestPlugins::class)
@TestPropertySource(
    properties =
        [
            "recommend4me.data-dir=build/test-data/application-\${org.gradle.test.worker:0}",
            "recommend4me.suggester=naive-bayes-1",
            "recommend4me.background=held",
        ]
)
abstract class BaseTest {
    @Autowired lateinit var stores: Stores

    @Autowired lateinit var tasks: Tasks

    /**
     * The background of the tests: nothing of it runs by itself. A test says [HeldBackground.run] where the application
     * would have done it by now — the works planned, the jobs started — and it is done at once, whole.
     */
    @Autowired lateinit var background: HeldBackground

    companion object {
        @JvmStatic @BeforeAll fun clean(info: TestInfo) = TestDataFolder.clean(info.testClass.get())
    }
}

/**
 * Every plugin of the tests of the whole application, in one application: the background held, the sites, the encoder,
 * the catalogue, the suggester of counts. Never nested in a class of tests: Spring takes a configuration nested there
 * for that class's own context.
 */
@TestConfiguration
@Import(SitePlugins::class, TwoSitesPlugins::class, CountingSuggester.Plugin::class)
class TestPlugins {
    /** The background of the tests, held till a test runs it. */
    @Bean fun background(): HeldBackground = HeldBackground()

    /** The source of [ScenarioTest]. */
    @Bean fun testSource(): Source = ScenarioTest.TestSource()
}
