package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.HierarchyOffer
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.correction.CorrectionsController
import io.github.vlsergey.recommend4me.draft.LayerDrafts
import io.github.vlsergey.recommend4me.item.ExplanationController
import io.github.vlsergey.recommend4me.item.FiltersController
import io.github.vlsergey.recommend4me.item.GradesController
import io.github.vlsergey.recommend4me.item.ItemsController
import io.github.vlsergey.recommend4me.item.MarksController
import io.github.vlsergey.recommend4me.item.SearchController
import io.github.vlsergey.recommend4me.marking.MarkingController
import io.github.vlsergey.recommend4me.model.CatalogueReader
import io.github.vlsergey.recommend4me.model.InputMeasures
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.model.ScorerComparisons
import io.github.vlsergey.recommend4me.page.PagesController
import io.github.vlsergey.recommend4me.pairing.PairingsController
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.SuggestionsController
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagCataloguesController
import io.github.vlsergey.recommend4me.tag.TagRenaming
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.tag.TagVectors
import io.github.vlsergey.recommend4me.tag.Tags
import io.github.vlsergey.recommend4me.tag.TagsController
import io.github.vlsergey.recommend4me.tagtext.TagsByTextController
import io.github.vlsergey.recommend4me.tagtext.TextTags
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniversesController
import io.github.vlsergey.recommend4me.workload.inBackground
import java.io.File
import org.junit.jupiter.api.BeforeAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * THE SITE OF THE TESTS as the application's interface sees it, its plugins [SitePlugins]: one application for every
 * test of it over one data folder, and what they share — the controllers they ask, the pages they capture, the
 * background made to run at once.
 *
 * ONE RUN IN ONE ORDER, as when they were one class: a test finds what those before it left — a work's model learns of
 * every work the tests before captured — so the classes run in their order ([SiteTagsTest], [SiteUniversesTest],
 * [SiteTest]: `junit-platform.properties`) and so do the tests of each.
 */
@SpringBootTest(properties = ["recommend4me.data-dir=build/test-data/site"])
@Import(SitePlugins::class)
abstract class SiteTestBase {

    companion object {
        private var cleaned = false

        /**
         * Every run from an empty data folder, emptied once: the application of the tests is one, kept between their
         * classes, its files open in it.
         */
        @JvmStatic
        @BeforeAll
        @Synchronized
        fun clean() {
            if (cleaned) return
            File("build/test-data/site").deleteRecursively()
            cleaned = true
        }
    }

    @Autowired lateinit var captures: CaptureController
    @Autowired lateinit var items: ItemsController
    @Autowired lateinit var searchController: SearchController
    @Autowired lateinit var filtersController: FiltersController
    @Autowired lateinit var explanationController: ExplanationController
    @Autowired lateinit var gradesController: GradesController
    @Autowired lateinit var marksController: MarksController
    @Autowired lateinit var inputMeasures: InputMeasures
    @Autowired lateinit var corrections: CorrectionsController
    @Autowired lateinit var pages: PagesController
    @Autowired lateinit var suggestionsApi: SuggestionsController
    @Autowired lateinit var stores: Stores
    @Autowired lateinit var textVectors: TextVectors
    @Autowired lateinit var suggestions: FacetSuggestions
    @Autowired lateinit var universes: UniversesController
    @Autowired lateinit var recommendations: Recommendations
    @Autowired lateinit var comparisons: ScorerComparisons
    @Autowired lateinit var tagsApi: TagsController
    @Autowired lateinit var catalogueReader: CatalogueReader
    @Autowired lateinit var tagService: Tags
    @Autowired lateinit var tagRenaming: TagRenaming
    @Autowired lateinit var tagCataloguesApi: TagCataloguesController
    @Autowired lateinit var tagVectors: TagVectors
    @Autowired lateinit var tagsByText: TagsByTextController
    @Autowired lateinit var textTags: TextTags
    @Autowired lateinit var pairingsApi: PairingsController
    @Autowired lateinit var marking: MarkingController
    @Autowired lateinit var layerDrafts: LayerDrafts

    protected fun capture(body: String) =
        captures.capturePage(CaptureRequest("https://site.example/work/${body.substringBefore('|')}", body))

    /**
     * The texts' vectors made, and what the background makes of the tags made now: their works counted and kept
     * ([TagRenaming.rename]), their vectors ([TagVectors]) — the screen of the tags reads them.
     */
    protected fun refresh(store: SourceStore) = inBackground {
        textVectors.refresh(store, "asked")
        tagService.scope()?.let { tagRenaming.rename(it, it.facet) }
        tagVectors.fill()
    }

    /** The offers of the tags' graph as worked out with the names of the tags, after the quiet — here at once. */
    protected fun offers(): List<HierarchyOffer> {
        inBackground { tagService.scope()?.let { tagRenaming.rename(it, it.facet) } }
        return tagCataloguesApi.listHierarchyOffers().body!!.offers
    }

    /**
     * Forty works of two kinds, one of the first kind the site gave a dragons' tag by mistake, and one of the first
     * kind it gave nothing.
     */
    protected fun catalogue() {
        repeat(20) { k ->
            // As on a site: some works with one tag of their two, some untagged, some without pairings
            fun tags(one: String, two: String) =
                when {
                    k % 5 == 0 -> ""
                    k % 3 == 0 -> one
                    else -> "$one,$two"
                }
            // And some crossovers: the other kind's pairing beside the own one
            fun pairing(own: String, other: String) =
                when {
                    k % 4 == 0 -> ""
                    k % 7 == 3 -> "$own, $other"
                    else -> own
                }
            // Titles of all sorts, that tell nothing of the kind: the description does
            val names =
                listOf(
                    "Anna",
                    "Boris",
                    "Clara",
                    "Denis",
                    "Elena",
                    "Fedor",
                    "Galina",
                    "Igor",
                    "Kira",
                    "Leonid",
                    "Maria",
                    "Nikita",
                    "Olga",
                    "Pavel",
                )
            val endings = listOf("Story", "Summer", "Letters", "Road", "Winter")
            fun title(i: Int) = "${names[i % names.size]} and ${names[(i * 5 + 3) % names.size]} ${endings[i % 5]}"
            capture(
                "s$k|${title(2 * k)}|${tags("space", "ships")}" +
                    "|Starships cross the galaxy, pilots fight in orbit near distant planets $k" +
                    "|${pairing("Pilot/Captain", "Knight/Princess")}"
            )
            capture(
                "d$k|${title(2 * k + 1)}|${tags("dragons", "magic")}" +
                    "|A dragon guards the castle, wizards cast spells and magic $k" +
                    "|${pairing("Knight/Princess", "Pilot/Captain")}"
            )
        }
        capture(
            "w1|Wrong tag|space,ships,magic" +
                "|Starships cross the galaxy, pilots fight in orbit near distant planets|Pilot/Captain"
        )
        capture("x1|Lost fleet||Pilots of starships lost beyond the galaxy fight near planets|")
        // The user's tags of some works the site left untagged: where the work's tags part from the
        // site's, the model learns what the texts say of them
        listOf(5, 10, 15).forEach { k ->
            listOf("space", "ships").forEach {
                corrections.correctFacet("site", "s$k", FacetCorrection(facet = "tags", added = true, key = tag(it)))
            }
            listOf("dragons", "magic").forEach {
                corrections.correctFacet("site", "d$k", FacetCorrection(facet = "tags", added = true, key = tag(it)))
            }
        }
        val store = stores.source("site")!!
        refresh(store)
        inBackground { suggestions.refresh(store) }
    }

    /** The key of the normalized tag the site's tag [site] is bound to. */
    protected fun tag(site: String) =
        TagRepository.key(stores.type("books")!!.tags.bindings().getValue(SiteTagRef("site", site)))
}
