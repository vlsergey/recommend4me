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
import io.github.vlsergey.recommend4me.model.WorkPredictions
import io.github.vlsergey.recommend4me.page.PagesController
import io.github.vlsergey.recommend4me.pairing.PairingsController
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.SourceStore
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
import io.github.vlsergey.recommend4me.task.inBackground
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseEntriesController
import io.github.vlsergey.recommend4me.universe.UniversesController
import io.github.vlsergey.recommend4me.worklist.ListIndexing
import org.springframework.beans.factory.annotation.Autowired

/**
 * THE SITE OF THE TESTS as the application's interface sees it, its plugins [SitePlugins], in the one application of
 * the tests ([BaseTest]), and what its classes share — the controllers they ask, the pages they capture, the background
 * made to run at once. Every test stands alone, in any order: it captures the works it reads, of ids of its own, the
 * [catalogue] among them when it needs a model.
 */
abstract class SiteTestBase : BaseTest() {

    companion object {
        /** The works of a kind of the [catalogue], and of them those told in other words. */
        const val WORKS_OF_A_KIND = 20
        const val TOLD_OTHERWISE = 12

        /** The bilinear suggester of the facets, by its id: the application's own ([Plugins.DEFAULT_SUGGESTER]). */
        const val BILINEAR = "bilinear-2"
    }

    @Autowired lateinit var settings: Settings

    /** The site of the class: of every class the one of the tests, of a scenario whose models are its own its own. */
    open val site: String = SitePlugins.SiteSource.ID

    /**
     * Every facet of the site learnt by the suggester [id] — none: by the tests' own ([BaseTest]) —, a setting of the
     * site's as the user's, not the application's context: a test sets it, and takes it back after.
     */
    fun learntBy(id: String?) {
        val store = stores.source(site)!!
        store.schema.facets.forEach { settings.set(Plugins.suggesterSetting(store.id, it.key), id) }
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
    @Autowired lateinit var textVectors: TextVectors
    @Autowired lateinit var listIndexing: ListIndexing
    @Autowired lateinit var suggestions: FacetSuggestions
    @Autowired lateinit var universes: UniversesController
    @Autowired lateinit var universeEntries: UniverseEntriesController
    @Autowired lateinit var recommendations: Recommendations
    @Autowired lateinit var workPredictions: WorkPredictions
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
        captures.capturePage(CaptureRequest("https://$site.example/work/${body.substringBefore('|')}", body))

    /**
     * The texts' vectors made of the works the list holds, and what the background makes of the tags made now: their
     * works counted and kept ([TagRenaming.rename]), their vectors ([TagVectors]) — the screen of the tags reads them.
     */
    protected fun refresh(store: SourceStore) = inBackground {
        // The texts' vectors follow the documents of the list written
        listIndexing.writeWaiting(store.type)
        textVectors.encodeNow(store.type)
        tagService.scope()?.let { tagRenaming.rename(it, it.facet) }
        tagVectors.fill()
    }

    /** The offers of the tags' graph as worked out with the names of the tags, after the quiet — here at once. */
    protected fun offers(): List<HierarchyOffer> {
        inBackground { tagService.scope()?.let { tagRenaming.rename(it, it.facet) } }
        return tagCataloguesApi.listHierarchyOffers().body!!.offers
    }

    /**
     * [WORKS_OF_A_KIND] works of each of two kinds, [TOLD_OTHERWISE] more of each told in other words, one more of the
     * first kind the site gave a tag of the other by mistake, and one of the first kind it gave nothing — the works
     * told otherwise so many that the one tagged by mistake, the nearest to it by its words, is not all it is judged
     * by. The same works however often made: a test that needs a model makes it, whatever another test of its class
     * made before.
     */
    protected fun catalogue() {
        repeat(WORKS_OF_A_KIND) { k ->
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
        repeat(TOLD_OTHERWISE) { k ->
            capture("sp$k|Pilots $k|space|Pilots fly starships in orbit near planets $k|Pilot/Captain")
            capture("dk$k|Knights $k|dragons|A knight guards the princess in the castle $k|Knight/Princess")
        }
        capture(
            "w1|Wrong tag|space,ships,magic" +
                "|Starships cross the galaxy, pilots fight in orbit near distant planets|Pilot/Captain"
        )
        capture("x1|Lost fleet||Pilots of starships lost beyond the galaxy fight near planets|")
        usersWord()
        val store = stores.source(site)!!
        refresh(store)
        inBackground { suggestions.refresh(store) }
    }

    /**
     * The user's tags of the works of the [catalogue] but the first of each kind, all of them named: the model learns
     * of the user's word alone — what the texts say of the values the user gave and of those the user said the work has
     * not. Every work of its kind's first tag, two of three of its second.
     */
    private fun usersWord() {
        (1 until WORKS_OF_A_KIND).forEach { k ->
            tagged("s$k", if (k % 3 == 0) listOf("space") else listOf("space", "ships"))
            tagged("d$k", if (k % 3 == 0) listOf("dragons") else listOf("dragons", "magic"))
        }
    }

    /** The work [id] said by the user to have the [tags] and no other. */
    private fun tagged(id: String, tags: List<String>) {
        tags.forEach {
            corrections.correctFacet(site, id, FacetCorrection(facet = "tags", added = true, key = tag(it)))
        }
        corrections.completeFacet(site, id, "tags")
    }

    /** The facets of the work [id]. */
    protected fun facetsOf(id: String) = items.getItem(site, id).body!!.allFacets

    /** The value [key] of the [facet] of the work [id]; none when the work has it not. */
    protected fun valueOf(id: String, facet: String, key: String) =
        facetsOf(id).find { it.facet == facet }?.propertyValues?.find { it.key == key }

    /** The key of the normalized tag the site's tag [site] is bound to. */
    protected fun tag(name: String) =
        TagRepository.key(stores.type("books")!!.tags.bindings().getValue(SiteTagRef(site, name)))
}
