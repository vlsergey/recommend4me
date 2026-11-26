package io.github.vlsergey.recommend4me.source

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.jooq.DSLContext

/**
 * A [SourceContext] for the tests of a source: everything it writes is kept in maps the test can look at, by the site's
 * id of the work — what a test of a page asks of: what it wrote of the site's work "123". The source writes by the
 * numbers it is given ([ItemStore.upsert]): 1, 2, 3 in the order the works are first written. No database; [database]
 * is not to be used.
 */
class MemorySourceContext(override val sourceId: String = "test") : SourceContext {

    /** The number of every work written, by the site's id of it. */
    val ids = LinkedHashMap<String, Long>()

    /** The site's id of every work written, by its number. */
    private val sites = HashMap<Long, String>()

    /** The site's id of the work numbered [id]: a number never given fails the test. */
    fun siteOf(id: Long): String = sites[id] ?: error("No work numbered $id")

    /**
     * The sites' ids of the works numbered [ids], in their order: what a source answers it touched, as a test reads it.
     */
    fun sitesOf(ids: List<Long>): List<String> = ids.map(::siteOf)

    val heads = LinkedHashMap<String, StoredItem>()
    val facets = HashMap<String, MutableMap<String, List<String>>>()
    val facetNames = HashMap<String, MutableMap<String, String>>()
    val facetDescriptions = HashMap<String, MutableMap<String, String>>()
    /** Of every facet, the synonyms of its values: a synonym's key to the value's key. */
    val facetSynonyms = HashMap<String, MutableMap<String, String>>()
    /** Of every facet, the values its values are under: a value's key to the keys above it. */
    val facetParents = HashMap<String, MutableMap<String, Set<String>>>()
    val numbers = HashMap<String, MutableMap<String, Double>>()
    val texts = HashMap<String, MutableMap<String, String>>()
    val pictures = HashMap<String, List<String?>>()
    val reviews = HashMap<String, MutableMap<String, ReviewData>>()
    val parts = HashMap<String, MutableMap<String, PartData>>()
    /** Of every item, its parts a whole table of contents read last did not name: taken off the site. */
    val partsTakenOff = HashMap<String, MutableSet<String>>()
    /** Every series kept, by its key: the last word of its page. */
    val series = LinkedHashMap<String, SeriesData>()
    /** Of every series, the places of its works its works' own pages told: item to position. */
    val seriesPlaces = HashMap<String, MutableMap<String, Int>>()
    val signalValues = HashMap<Pair<String, String>, String>()
    val settingValues = HashMap<String, String>()
    /** The places on the web every author's page listed last: the author's key to the addresses. */
    val authorPlaces = HashMap<String, List<String>>()
    val pages = LinkedHashMap<String, String>()

    override val items: ItemStore =
        object : ItemStore {

            override fun upsert(item: ItemHead, now: Instant): Upserted {
                val id = ids.getOrPut(item.siteId) { ids.size + 1L }
                sites[id] = item.siteId
                val known = heads[item.siteId]
                heads[item.siteId] =
                    StoredItem(
                        id,
                        item.siteId,
                        item.url,
                        item.title,
                        item.version,
                        item.updatedAt ?: known?.updatedAt ?: now,
                        known?.firstSeenAt ?: now,
                    )
                return Upserted(id, known?.version != item.version)
            }

            override fun idOf(siteId: String): Long? = ids[siteId]

            override fun idsOf(siteIds: Collection<String>): Map<String, Long> =
                siteIds.mapNotNull { s -> this@MemorySourceContext.ids[s]?.let { s to it } }.toMap()

            override fun siteIdsOf(ids: Collection<Long>): Map<Long, String> =
                ids.mapNotNull { id -> sites[id]?.let { id to it } }.toMap()

            override fun find(itemId: Long) = sites[itemId]?.let(heads::get)

            override fun remove(itemId: Long) {
                val site = sites[itemId] ?: return
                listOf(heads, facets, numbers, texts, pictures, reviews, parts, partsTakenOff).forEach {
                    it.remove(site)
                }
                seriesPlaces.values.forEach { it.remove(site) }
                signalValues.keys.removeIf { it.first == site }
            }

            override fun count() = heads.size

            override fun latestUpdate() = heads.values.maxOfOrNull { it.updatedAt }

            override fun setFacet(itemId: Long, facet: String, values: List<FacetValue>) {
                facets.getOrPut(siteOf(itemId)) { HashMap() }[facet] = values.map { it.key }.distinct()
                nameFacetValues(facet, values.filter { it.name != null }.associate { it.key to it.name!! })
            }

            override fun nameFacetValues(facet: String, names: Map<String, String>) {
                facetNames.getOrPut(facet) { HashMap() } += names
            }

            override fun describeFacetValues(facet: String, descriptions: Map<String, String>) {
                facetDescriptions.getOrPut(facet) { HashMap() } += descriptions
            }

            override fun facetDescriptions(facet: String, keys: Collection<String>): Map<String, String> =
                this@MemorySourceContext.facetDescriptions[facet].orEmpty().filterKeys { it in keys }

            override fun setFacetValueSynonyms(
                facet: String,
                synonyms: Map<String, Map<String, String>>,
                whole: Boolean,
            ) {
                val own = facetSynonyms.getOrPut(facet) { HashMap() }
                if (whole) own.values.removeAll { it in synonyms.keys }
                synonyms.forEach { (key, of) -> of.keys.forEach { own[it] = key } }
                nameFacetValues(facet, synonyms.values.fold(emptyMap()) { all, names -> all + names })
            }

            override fun setFacetValueParents(
                facet: String,
                parents: Map<String, Collection<String>>,
                whole: Boolean,
            ) {
                val own = facetParents.getOrPut(facet) { HashMap() }
                parents.forEach { (key, above) ->
                    own[key] = above.filter { it != key }.toSet() + (if (whole) emptySet() else own[key].orEmpty())
                }
            }

            override fun facets(itemId: Long): Map<String, List<String>> = facets[siteOf(itemId)].orEmpty()

            override fun forEachFacet(facet: String, action: (itemId: Long, keys: List<String>) -> Unit) =
                facets.forEach { (site, f) ->
                    f[facet]?.let { action(ids.getValue(site), it) }
                }

            override fun setNumbers(itemId: Long, numbers: Map<String, Double?>) {
                val own = this@MemorySourceContext.numbers.getOrPut(siteOf(itemId)) { HashMap() }
                numbers.forEach { (k, v) -> if (v == null) own.remove(k) else own[k] = v }
            }

            override fun setTexts(itemId: Long, texts: Map<String, String?>) {
                val own = this@MemorySourceContext.texts.getOrPut(siteOf(itemId)) { HashMap() }
                texts.forEach { (k, v) -> if (v.isNullOrBlank()) own.remove(k) else own[k] = v.trim() }
            }

            override fun texts(itemId: Long): Map<String, String> = texts[siteOf(itemId)].orEmpty()

            override fun setPictures(itemId: Long, urls: List<String?>) {
                pictures[siteOf(itemId)] = urls
            }

            override fun saveReviews(itemId: Long, reviews: List<ReviewData>) {
                this@MemorySourceContext.reviews.getOrPut(siteOf(itemId)) { LinkedHashMap() } += reviews.associateBy {
                    it.id
                }
            }

            override fun reviewIds(itemId: Long): Set<String> = reviews[siteOf(itemId)].orEmpty().keys

            override fun reviewContents(itemId: Long): Map<String, String> =
                reviews[siteOf(itemId)].orEmpty().mapValues { it.value.content }

            override fun saveParts(itemId: Long, parts: List<PartData>, whole: Boolean) {
                val own = this@MemorySourceContext.parts.getOrPut(siteOf(itemId)) { LinkedHashMap() }
                val off = partsTakenOff.getOrPut(siteOf(itemId)) { HashSet() }
                off -= parts.map { it.id }.toSet()
                if (whole) off += own.keys - parts.map { it.id }.toSet()
                parts.forEach { p ->
                    val known = own[p.id]
                    own[p.id] =
                        p.copy(
                            position = if (p.position >= 0) p.position else known?.position ?: p.position,
                            title = p.title ?: known?.title,
                            content = p.content ?: known?.content,
                        )
                }
            }

            override fun parts(itemId: Long): Map<String, Boolean> =
                parts[siteOf(itemId)].orEmpty().mapValues { it.value.content != null }

            override fun keepSeries(series: SeriesData) {
                this@MemorySourceContext.series[series.key] = series
            }

            override fun placeInSeries(itemId: Long, key: String, position: Int) {
                seriesPlaces.getOrPut(key) { HashMap() }[siteOf(itemId)] = position
            }
        }

    override val database: DSLContext
        get() = error("A memory context has no database")

    override val settings: SourceSettings =
        object : SourceSettings {
            override fun get(key: String) = settingValues[key]

            override fun set(key: String, value: String?) {
                if (value == null) settingValues.remove(key) else settingValues[key] = value
            }
        }

    override val signals: SiteSignals =
        object : SiteSignals {
            override fun setSignal(itemId: Long, signal: String, value: String?) {
                val key = siteOf(itemId) to signal
                if (value == null) signalValues.remove(key) else signalValues[key] = value
            }

            override fun withSignal(signal: String): Map<Long, String> =
                signalValues.filterKeys { it.second == signal }.mapKeys { ids.getValue(it.key.first) }
        }

    override val folder: Path by lazy { Files.createTempDirectory("source-$sourceId") }

    override fun keepPage(url: String, html: String, itemId: Long?) {
        pages[url] = html
    }

    override fun authorPlaces(author: String, urls: List<String>) {
        authorPlaces[author] = urls
    }

    override fun gradedItems(): Set<Long> = emptySet()

    override fun itemsInOrder(): LongArray = heads.values.map { it.id }.toLongArray()

    val jobStates = HashMap<String, String>()

    override fun jobState(key: String): String? = jobStates[key]
}
