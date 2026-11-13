package io.github.vlsergey.recommend4me.source

import java.nio.file.Path
import java.time.Instant
import org.jooq.DSLContext

/** What the application hands a source to work with. */
interface SourceContext {
    val sourceId: String

    /** The generic data of the source's items. */
    val items: ItemStore

    /** The source database, for the source's own tables. */
    val database: DSLContext

    val settings: SourceSettings

    /** The user's own actions seen on the site's pages. */
    val signals: SiteSignals

    /** The source's folder in the data folder, for whatever files it keeps. */
    val folder: Path

    /**
     * Keeps a page — a captured one, or one the source downloaded — so it can be read again when the parser changes
     * ([Source.parserVersion]); a page kept again under the same address replaces it.
     */
    fun keepPage(url: String, html: String, itemId: String?)

    /** The items the user has graded, any version: a scrape reads their pages first. */
    fun gradedItems(): Set<String>

    /** The items in the order the user wants them worked on: the graded, then by prediction, then the newest. */
    fun itemsInOrder(): List<String>

    /** A value the source's jobs keep across restarts ([Job.state]), read outside of a job. */
    fun jobState(key: String): String?
}

/** Settings of one source, kept by the application. */
interface SourceSettings {
    fun get(key: String): String?

    fun set(key: String, value: String?)
}

/**
 * The user's own actions on the site, as its pages show them — liked, in the library on a shelf, read to some chapter,
 * bookmarked. Kept with the user's grades, never with the scrape; shown, and never read by the model: they are the
 * user's verdict on the work ([SignalDef]).
 */
interface SiteSignals {
    /** Sets the [signal] of the item to [value]; null takes it away. */
    fun set(itemId: String, signal: String, value: String?)

    /** The items whose [signal] is set now. */
    fun withSignal(signal: String): Map<String, String>
}

/**
 * The generic data of a source's items, written by the source, read by the application.
 *
 * A PAGE SAYS ONLY WHAT IT SHOWS: a parser writes what it found, and an empty list, a null or a blank only for what the
 * page shows to be empty — a block the work's header has not. A block it could not find, a page that is not the work's
 * (an error page answered with 200, a changed markup), says nothing: nothing of it is written, as nothing is known to
 * be gone.
 *
 * Its items' facets are its [ItemFacetStore], their reviews its [ItemReviewStore], the parts of their text its
 * [ItemPartStore], the series they are parts of its [ItemSeriesStore].
 */
interface ItemStore : ItemFacetStore, ItemReviewStore, ItemPartStore, ItemSeriesStore {
    /** Creates or updates an item; true when it is new or its version changed. */
    fun upsert(item: ItemHead, now: Instant): Boolean

    fun find(itemId: String): StoredItem?

    /**
     * The item kept no more, with everything of it — its texts, parts and reviews, the user's grades and answers, what
     * the models made of it — as when the user removes a work: what the source will not keep. Seen again, it is a work
     * never seen. Nothing of an item not kept.
     */
    fun remove(itemId: String)

    fun count(): Int

    /** The newest [ItemHead.updatedAt] of the catalogue. */
    fun latestUpdate(): Instant?

    /** Sets numbers of the item; a null takes one away. Numbers not named stay. */
    fun setNumbers(itemId: String, numbers: Map<String, Double?>)

    /** Sets texts of the item; a null or blank takes one away. Texts not named stay. */
    fun setTexts(itemId: String, texts: Map<String, String?>)

    fun texts(itemId: String): Map<String, String>

    /**
     * The item's pictures as the site lists them now, position 0 the cover. A picture whose address changed is a new
     * picture: its files and vector are forgotten.
     */
    fun setPictures(itemId: String, urls: List<String?>)
}

/**
 * THE FACETS OF A SOURCE'S ITEMS — the values the site gives each, and what it says of the values themselves: their
 * names, descriptions and synonyms. A part of [ItemStore], written as a page says it.
 */
interface ItemFacetStore {
    /** Replaces the item's values of one facet. */
    fun setFacet(itemId: String, facet: String, values: List<FacetValue>)

    /** The names of facet values the items name by key; a name given before is replaced. */
    fun nameFacetValues(facet: String, names: Map<String, String>)

    /**
     * What the site says the facet's values mean — a tag's own page describing it — by key; a description given before
     * is replaced.
     */
    fun describeFacetValues(facet: String, descriptions: Map<String, String>)

    /**
     * What the site said the facet's values [keys] mean, by key — of those it was read of: a blank one the site's page
     * showing none.
     */
    fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String>

    /**
     * The other names the site gives facet values — the synonyms of a tag of ficbook — by the value's key: each synonym
     * (its key to its name) a value of its own, named, and the same thing as the value — a site's tag bound to the tag
     * the value is. The synonyms given before of the values given are replaced.
     */
    fun setFacetValueSynonyms(facet: String, synonyms: Map<String, Map<String, String>>)

    /** The keys of the item's values of every facet. */
    fun facets(itemId: String): Map<String, List<String>>

    /** Every item's keys of one facet, streamed. */
    fun forEachFacet(facet: String, action: (itemId: String, keys: List<String>) -> Unit)
}

/** THE REVIEWS OF A SOURCE'S ITEMS, as the site shows them ([ReviewData]). A part of [ItemStore]. */
interface ItemReviewStore {
    /** Stores reviews of the item, a known one updated; none is deleted. */
    fun saveReviews(itemId: String, reviews: List<ReviewData>)

    fun reviewIds(itemId: String): Set<String>

    /** The texts of the item's stored reviews, by id: a review read whole is not replaced by its excerpt. */
    fun reviewContents(itemId: String): Map<String, String>
}

/** THE PARTS OF THE TEXT OF A SOURCE'S ITEMS — its chapters ([PartData]). A part of [ItemStore]. */
interface ItemPartStore {
    /** Stores parts of the item's text, a known one updated; none is deleted. */
    fun saveParts(itemId: String, parts: List<PartData>)

    /** The parts of the item: id to whether its text is kept. */
    fun parts(itemId: String): Map<String, Boolean>
}

/**
 * THE SERIES OF A SOURCE'S ITEMS — a site's cycle of works, as its own page says it ([SeriesData]), and the place of a
 * work in it. A part of [ItemStore]. A work's series as the work's page or card names it is the value of its facet of
 * the role [FacetRole.SERIES], written as any facet: the application makes the work a work of that series too.
 */
interface ItemSeriesStore {
    /**
     * What a series' page — or a list of an author's series — says of it: a series known updated, a field the page does
     * not show kept as it was. Its works in the order listed, each in the series; a series listed whole
     * ([SeriesData.whole]) has no other: a work no longer listed is taken out of it.
     */
    fun keepSeries(series: SeriesData)

    /** The item's [position] in the series [key] — "#3", "Part 3 of" — as the work's own page tells it. */
    fun placeInSeries(itemId: String, key: String, position: Int)
}

/**
 * What a site says of one of its series: its [key] — the key of the value of the facet of the role [FacetRole.SERIES]
 * that names it — and its page. Of [title], [description], [status], [begunAt] and [updatedAt] a null is a field the
 * page does not show, kept as it was; a blank [description] is one the page shows to be none. [status]: the key of a
 * standard status of the works ([io.github.vlsergey.recommend4me.contenttype.Books.Status]): of a series of a frozen
 * work, frozen; else of an unfinished one, in progress; else finished.
 */
data class SeriesData(
    val key: String,
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val begunAt: Instant? = null,
    val updatedAt: Instant? = null,
    /** Its works in the order of the page. */
    val works: List<SeriesWork> = emptyList(),
    /** Whether [works] are every work of the series: a page of a list of several, or a list cut short, is not. */
    val whole: Boolean = false,
)

/**
 * A work of a series as its page lists it: the item, and its number in the series when the page tells it ("1.", "Part 3
 * of"); of none, its place in a list [SeriesData.whole] is its order there.
 */
data class SeriesWork(val itemId: String, val position: Int? = null)

/** What every item has. */
data class ItemHead(
    val id: String,
    val url: String,
    val title: String,
    /** The release the item is at; the same for an item without versions. */
    val version: String = "",
    /** When the site says the item was last updated; null — unknown, the first time it is seen. */
    val updatedAt: Instant? = null,
)

data class StoredItem(
    val id: String,
    val url: String,
    val title: String,
    val version: String,
    val updatedAt: Instant,
    val firstSeenAt: Instant,
)

/** A value of a facet: its stable key, and its name when the page shows one. */
data class FacetValue(val key: String, val name: String? = null)

/** An opinion of someone else about the item. */
data class ReviewData(
    /** Unique within the item. */
    val id: String,
    val author: String?,
    /** The reviewer's own grade, 1..5, when given. */
    val stars: Double?,
    val content: String,
    val postedAt: Instant?,
)

/** A part of the item's text: a chapter. */
data class PartData(
    /** Unique within the item. */
    val id: String,
    /** The order of the parts; negative when the page does not tell it — a known part keeps its own. */
    val position: Int,
    val title: String?,
    /** The text; null when only the table of contents names the part. */
    val content: String?,
    val publishedAt: Instant? = null,
)
