package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.item.SynonymsSet
import io.github.vlsergey.recommend4me.layer.LayerValues
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.WorkClusters
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(TagNormalization::class.java)

/**
 * WHAT THE TAGS ARE READ OVER: every source of every content type with a layer of tags learnt from its site's tags —
 * books and games alike — and the one dictionary of them ([tags]); [facet] the key of the layer, one of every type (the
 * standard tags).
 */
class TagScope(val sources: List<SourceStore>, val tags: TagRepository, val facet: String) {
    /** The content types the tags are of: told when the tags change, their models trained again. */
    val types: Set<String> = sources.map { it.type }.toSet()

    /** The name of the scope in the log. */
    val id = "tags"

    fun source(id: String): SourceStore? = sources.firstOrNull { it.id == id }

    /** The id of the tag [key]; null of a key that is no tag, or of a tag that is no more. */
    fun existing(key: String): Long? = TagRepository.idOf(key)?.takeIf(tags::exists)

    /** The works of several items of the sources, read for the operation. */
    fun works(): WorkClusters = WorkClusters.of(sources.first().works.groups(sources.map { it.id }).values.toList())
}

/**
 * EVERY SITE'S TAG IS BOUND TO A NORMALIZED TAG ([TagRepository]). A site's tag seen first is bound to the normalized
 * tag the site's tags of its name are bound to — "Магия" of one site and "магия" of another one tag — when there is
 * exactly one such; else to a new normalized tag of its own. A synonym the site names of one of its tags
 * ([ItemStore.setFacetValueSynonyms][io.github.vlsergey.recommend4me.source.ItemStore.setFacetValueSynonyms]) is a
 * site's tag of its own bound to the tag that one is bound to — once that one is bound, by a work that has it. From
 * then on the user binds it: merges tags, takes one out.
 */
@Component
class TagNormalization(private val stores: Stores) {

    /** The facet of the layer learnt from the sites' tags of a source; null for a source without one. */
    private fun layerOf(s: SourceStore): String? =
        s.schema.facets.firstOrNull { it.editable && it.examplesFrom != null }?.key

    /** Every source of every type with a layer of tags; null when none has one. */
    fun scope(): TagScope? {
        val sources = stores.types.flatMap { it.sources }.filter { layerOf(it) != null }
        val facet = sources.firstOrNull()?.let(::layerOf) ?: return null
        return TagScope(sources, sources.first().tags, facet)
    }

    /** The site's facet of tags of a source, the layer's [facet] learnt from it; null for a source without one. */
    fun siteFacetOf(s: SourceStore, facet: String): String? =
        s.schema.facets.firstOrNull { it.key == facet }?.examplesFrom

    /** Every site's tag not bound yet bound now; how many. */
    fun bindNew(): Int {
        val scope = scope() ?: return 0
        return bind(scope) { s, f -> s.items.valueUses(f).keys }
    }

    /**
     * The site's tags of the works [event] names bound at once, before anything reads the works: a work captured is
     * read with its tags.
     */
    @Order(FIRST)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val scope = scope() ?: return
        val source = scope.source(event.source)
        val f = source?.let { siteFacetOf(it, scope.facet) }
        if (source == null || f == null) return
        val keys = source.items.facetsOf(event.ids).values.flatMap { it[f].orEmpty() }.toSet()
        if (keys.isNotEmpty()) bind(scope) { s, _ -> if (s.id == source.id) keys else emptySet() }
    }

    /** The synonyms a site named bound at once: the tags' names and vectors made of them next. */
    @Order(FIRST)
    @EventListener(SynonymsSet::class)
    fun synonymsSet() {
        val scope = scope() ?: return
        bind(scope) { _, _ -> emptySet() }
    }

    /**
     * The site's tags [keysOf] gives of every source bound, the ones not bound yet — of whichever type: a name of a
     * site of games and of one of books one tag — and every synonym not bound yet of a site's tag bound; how many. One
     * at a time: two at once would make two tags of one name.
     */
    @Synchronized
    private fun bind(scope: TagScope, keysOf: (SourceStore, String) -> Set<String>): Int {
        val bound = scope.tags.bindings()
        val sites = scope.sources.mapNotNull { s -> siteFacetOf(s, scope.facet)?.let { s to it } }
        val fresh =
            sites
                .associate { (s, f) -> s.id to keysOf(s, f).filter { SiteTagRef(s.id, it) !in bound } }
                .filterValues { it.isNotEmpty() }
        val synonyms = sites.associate { (s, f) -> s.id to s.items.facetSynonyms(f) }
        // With no tag new, a synonym is bound only of a tag bound before and itself not yet: none such, nothing to do
        val synonymsLeft = synonyms.any { (source, of) ->
            of.any { (synonym, key) -> SiteTagRef(source, synonym) !in bound && SiteTagRef(source, key) in bound }
        }
        if (fresh.isEmpty() && !synonymsLeft) return 0
        // The names of the sites' tags read only for the tags new: a synonym is bound by its tag, not by a name
        val names = if (fresh.isEmpty()) emptyMap() else sites.associate { (s, f) -> s.id to s.names(f) }
        val binding = Binding(scope, bound, names, synonyms)
        val made = binding.fresh(fresh) + binding.synonyms()
        if (made > 0) log.info("{} tags of the sites bound to normalized tags", made)
        return made
    }

    /**
     * One binding of the sites' tags not bound yet: the tags [bound] before it, the names of the sites' tags of every
     * source ([namesOf]) and the synonyms every source names ([synonyms]: synonym to the site's tag).
     */
    private class Binding(
        private val scope: TagScope,
        private val bound: Map<SiteTagRef, Long>,
        private val namesOf: Map<String, Map<String, String>>,
        private val synonyms: Map<String, Map<String, String>>,
    ) {
        private val now = Instant.now()

        /** The normalized tags every name is bound to, of every site: made at the first tag new, of none else. */
        private val byName: HashMap<String, MutableSet<Long>> by lazy {
            HashMap<String, MutableSet<Long>>().also { out ->
                bound.forEach { (ref, id) ->
                    out.getOrPut(LayerValues.keyOf(namesOf[ref.source]?.get(ref.tag) ?: ref.tag)) { HashSet() } += id
                }
            }
        }
        private val boundNow = HashMap<SiteTagRef, Long>()

        private fun idOf(ref: SiteTagRef) = bound[ref] ?: boundNow[ref]

        private fun bind(ref: SiteTagRef, id: Long) {
            scope.tags.bind(ref, id, now)
            boundNow[ref] = id
        }

        /** The sites' tags [fresh] of every source bound; how many. */
        fun fresh(fresh: Map<String, List<String>>): Int {
            fresh.forEach { (source, keys) ->
                keys.forEach { key -> bind(SiteTagRef(source, key), tagOf(source, key)) }
            }
            return fresh.values.sumOf { it.size }
        }

        /**
         * The tag the site's tag [key] of [source] is bound to: a synonym of a tag bound already is that tag; else as
         * any tag, by its name — the one tag of its name, or a new one.
         */
        private fun tagOf(source: String, key: String): Long {
            val written = (namesOf.getValue(source)[key] ?: key).trim()
            val name = LayerValues.keyOf(written)
            return synonyms[source]?.get(key)?.let { idOf(SiteTagRef(source, it)) }
                ?: byName[name]?.singleOrNull()
                ?: scope.tags.create().also {
                    byName.getOrPut(name) { HashSet() } += it
                    scope.tags.stats.showAs(it, ShownName(written, NameRule.ALL))
                }
        }

        /**
         * The synonyms of the site's tags bound — of no work, maybe — bound with them; of a site's tag not bound yet,
         * none: bound once a work has it. How many.
         */
        fun synonyms(): Int {
            var made = 0
            synonyms.forEach { (source, of) ->
                of.forEach { (synonym, key) ->
                    val ref = SiteTagRef(source, synonym)
                    val id = idOf(SiteTagRef(source, key)).takeIf { idOf(ref) == null }
                    if (id != null) {
                        bind(ref, id)
                        made++
                    }
                }
            }
            return made
        }
    }

    /** Before anything reads the tags: every site's tag not bound yet bound. */
    @Order(BEFORE_READING)
    @EventListener(ApplicationReadyEvent::class)
    // The start of the application: whatever fails is told in the log, and the tags are bound as works come
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        try {
            bindNew()
        } catch (e: Exception) {
            log.error("The tags were not normalized", e)
        }
    }

    companion object {
        /** Before every other listener of the works: their sites' tags are bound before anything reads them. */
        const val FIRST = -100

        /** Of the start, before anything reads the tags ([TagRenaming.start] after it). */
        const val BEFORE_READING = -10

        /** Of the start, right after the tags are normalized ([start]). */
        const val AFTER_NORMALIZATION = -5
    }
}
