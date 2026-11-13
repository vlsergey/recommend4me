package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.cast.CastHabit
import io.github.vlsergey.recommend4me.universe.CatalogueLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import java.time.Instant

/** How a source gets its items. */
enum class SourceMode {
    /** The whole catalogue, resumable: hours or days of requests to the site. */
    SCRAPE_ALL,

    /** What is new since the last time. */
    UPDATES,

    /**
     * The pages the user opens in the browser, sent by the extension: the application itself requests nothing from the
     * site.
     */
    BROWSER,

    /**
     * One work's page downloaded by the application when the user asks for it ([Source.refresh]): no catalogue, no job.
     */
    PAGE,
}

/** The modes that are jobs over the catalogue, run in the background. */
val SourceMode.job: Boolean
    get() = this == SourceMode.SCRAPE_ALL || this == SourceMode.UPDATES

/** Whether the application downloads a work's page itself: by [SourceMode.PAGE], or by a job's own requests. */
val Source.downloadsPages: Boolean
    get() = modes.any { it == SourceMode.PAGE || it.job }

/**
 * A site the works come from. Implemented by a plugin and declared as a Spring bean.
 *
 * Everything a source learns it writes through the [SourceContext] it is handed: the generic data of its items into the
 * application's tables, anything else into tables of its own ([migrations]) in the same source database. The pages the
 * browser sends it are its [BrowserTracking], how its pictures are downloaded its [SitePictures].
 *
 * HOW A SITE IS ADDED — everything a new source needs is told here and by the interfaces it names; no other source is a
 * pattern to read:
 * - **Its module**: `plugins/source-<name>`, listed in `settings.gradle.kts`, its build file applying the plugin
 *   `recommend4me.addon` and `testImplementation(testFixtures(project(":plugin-api")))`. The source is a bean of a
 *   Spring `@AutoConfiguration` of its own, named in its `META-INF/spring/
 *   org.springframework.boot.autoconfigure.AutoConfiguration.imports`; the application finds every plugin by them.
 * - **Its words**: `i18n/<id>.<language>.json` among its resources, every language of
 *   [I18nKeys.LANGUAGES][io.github.vlsergey.recommend4me.i18n.I18nKeys.LANGUAGES], with every key
 *   [I18nKeys.requiredKeys][io.github.vlsergey.recommend4me.i18n.I18nKeys.requiredKeys] names — its title and short
 *   title, its own facets, numbers, texts, settings, its reviews and parts; the test fixture `Translations.problems`
 *   checks them.
 * - **Its [schema]**: the content type's standard facets and numbers first — a site's field the standard one means
 *   (`Books.AUTHOR`, `Books.FANDOM`, `Books.STATUS`, `Books.DIRECTION`, `Books.PAIRINGS`, `Books.PARTS`,
 *   `StandardNumbers.VIEWS`, `StandardNumbers.LIKES`, `StandardLanguages.LANGUAGE`) declared by its `asFacet`/
 *   `asNumber`, its values mapped onto the standard's; the site's age rating by `AgeRating.SITE` and `AgeRating.write`.
 *   The site's own tags are one facet, `Books.TAG.asFacet(…)`: the tags bound to the application's normalized tags —
 *   and by the normalized tags the user forbids to import, the application keeps a work out as [SourceContext.items]
 *   writes its tags; a source keeps out nothing by content itself, and names no tag to keep out in its code. The kind
 *   of a work's universe (fan fiction or original) is `UniverseFacets.KIND`, told by the facet [SourceSchema.kindFrom]
 *   names; the site's line of characters and pairings a text [SourceSchema.universeLine] names, read by its
 *   [castHabit].
 * - **Its [modes]**: [SourceMode.BROWSER] — [capturePatterns], [capture], [itemIdOf] and a [pageDecor], every selector
 *   of it matching the pages; [SourceMode.PAGE] — [refresh], on the user's press alone: the site asked politely, one
 *   request at a time with a pause between, under a user agent that says what asks, a page gone (404) false, a site
 *   asking to wait (429) an error; [SourceMode.SCRAPE_ALL] and [SourceMode.UPDATES] — [scrape].
 * - **Its items**: an id stable, of the site's own number, fit to stand in an address's path (no "/"); written as
 *   [ItemStore] tells — a page says only what it shows. WHAT THE SCREEN DOES NOT SHOW IS NOT WRITTEN, NOR DOWNLOADED
 *   (user, 2026-11-13): no page fetched on its own, the one download [refresh] the user presses for; what the site's
 *   own words mean that the code knows — a legend of its codes — may name what a page shows. What a page reads
 *   differently raises [parserVersion]: the pages kept are read again.
 * - **Its tests**: the site's pages kept under `src/test/resources/pages`, every prose text of them — descriptions,
 *   chapters, notes, reviews — replaced by placeholder text (Lorem ipsum) and people's names neutral, scripts gone: the
 *   markup is the site's, the words are not; read into a `MemorySourceContext`. A test of the words (`Translations`),
 *   of every page read, and of the [pageDecor] against the pages kept.
 */
interface Source : BrowserTracking, SitePictures {
    /**
     * Stable: the name of the source's folder, of its databases and the first half of every item key; and the namespace
     * of its words, which the plugin carries in every language of the interface
     * ([I18nKeys][io.github.vlsergey.recommend4me.i18n.I18nKeys]) — its title among them.
     */
    val id: String

    /** The content type the source's works belong to: `games`, `books`. */
    val contentType: String

    val homepage: String

    val schema: SourceSchema

    val modes: Set<SourceMode>

    /**
     * Flyway locations of the source's own tables in its source database, e.g. `classpath:db/f95zone`. They are
     * migrated with a history table of their own.
     */
    val migrations: List<String>
        get() = emptyList()

    val settings: List<SettingDef>
        get() = emptyList()

    /** The page of the item on the site. */
    fun itemUrl(itemId: String): String

    /**
     * The page of a part of the item — a chapter — on the site, [partId] as the source keeps it ([PartData.id]): where
     * a passage of its text is read in place. The item's own page of a source whose parts have none of their own.
     */
    fun partUrl(itemId: String, partId: String): String = itemUrl(itemId)

    /**
     * The page of a value of the source's [facet] on the site, [key] and [name] as the source wrote them
     * ([FacetValue]): of an author, the author's own page, else the site's search of the works by them; of a series,
     * the series' own page; null when the site has none.
     */
    fun valueUrl(facet: String, key: String, name: String): String? = null

    /**
     * The item a page of the site is about — a work's page, a chapter of it, its reviews — or null when the address is
     * not one item's. The extension shows the item's panel on such a page, and a card linking to it gets the item's
     * prediction.
     */
    fun itemIdOf(url: String): String? = null

    /** The user's own actions on the site the source reads ([SiteSignals]). */
    val signals: List<SignalDef>
        get() = emptyList()

    /** The messages its jobs may leave the user ([Job.message]), by their keys: their words are among the source's. */
    val messages: Set<String>
        get() = emptySet()

    /**
     * The statements of the catalogues of universes the source wants of every character, by the catalogue's id:
     * `"wikidata" to setOf("P8419")` — a character's tag on Archive of Our Own. The characters of every universe are
     * asked for with them
     * ([UniverseCharacter.properties][io.github.vlsergey.recommend4me.universe.UniverseCharacter.properties]).
     */
    val catalogueProperties: Map<String, Set<String>>
        get() = emptyMap()

    /**
     * The values of the application's layer the source drafts of a work from its own values on the site ([facets]: the
     * site's facet to its values, keys and names as written) through the dictionary ([lookup]): the characters its tags
     * name, their pairings, its universes. Only what matches exactly one value of the dictionary; none of a source that
     * drafts nothing.
     */
    fun draft(facets: Map<String, List<FacetValue>>, lookup: CatalogueLookup): List<LayerDraft> = emptyList()

    /**
     * How the site's authors usually write its line of characters and pairings ([CastHabit]): the fan sites' habit —
     * the names of a pairing by "/" ([CastHabit.SLASH]) — unless the site says its own.
     */
    val castHabit: CastHabit
        get() = CastHabit.SLASH

    /** How the extension marks the site's pages up; null — it does not. */
    val pageDecor: PageDecor?
        get() = null

    // --- Scraping ---

    /** Runs a job of [mode] (not [SourceMode.BROWSER]) to its end, cancelled or failed. */
    fun scrape(mode: SourceMode, context: SourceContext, job: Job): Unit =
        throw UnsupportedOperationException("$id does not scrape")

    /**
     * Downloads the item's page again now — on the user's press of the button of a single item alone: never on its own,
     * a grade given or a page seen asks the site for nothing (user, 2026-11-13). Returns false when the source cannot.
     */
    fun refresh(itemId: String, context: SourceContext): Boolean = false

    /**
     * How far the whole catalogue is loaded ([SourceMode.SCRAPE_ALL]): what it is doing and how much of it is done,
     * across restarts; null — the source does not tell.
     */
    fun scrapeProgress(context: SourceContext): ScrapeProgress? = null

    // --- Background work of its own ---

    /**
     * Called once the application is up: a source may start background work of its own here (the reviews of f95zone
     * read without end), on threads of its own, until [stop].
     */
    fun start(context: SourceContext) {}

    /** Called when the application stops. */
    fun stop() {}
}

/**
 * How far a scrape of the whole catalogue has got: [stage] — what it does now or would do next ("feed", "details");
 * [done] of [total] in it; [complete] — the catalogue is loaded and only updates are left.
 */
class ScrapeProgress(val stage: String, val done: Int, val total: Int, val complete: Boolean)

/**
 * A request of the site the browser makes for a source ([Source.describing]): [url] asked, with [form] sent as the body
 * of a POST, or as a GET without one; what it answers is sent back as a captured page under [url] — its address, a
 * capture pattern of the source matches it.
 */
data class WantedPage(val url: String, val form: Map<String, String>? = null)

/** A page the browser sent: its address, the DOM as the user saw it, and when. */
class CapturedPage(
    val url: String,
    val html: String,
    val capturedAt: Instant,
)
