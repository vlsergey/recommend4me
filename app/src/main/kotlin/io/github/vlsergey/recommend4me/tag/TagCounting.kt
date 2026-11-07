package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE TAGS OF THE LAYER [facet] COUNTED OVER EVERY WORK of every source of [scope]: every normalized tag with its
 * sites' tags and its works, graded and liked among them, and the graded works the user said have it not — one pass
 * over every work, seconds of a catalogue of games. A work is in the sets once, by the item that names it, whatever
 * items of it have the tag. Of one counting, kept nowhere.
 */
class TagCounting(
    private val scope: TagScope,
    private val facet: String,
    private val normalization: TagNormalization,
) {
    private val bindings = scope.tags.bindings()
    private val out = HashMap<String, TagStats>()
    private val rejections = HashMap<String, MutableSet<ItemKey>>()
    private val works = scope.works()

    /** The sites' tags counted of the works of several items: two items of a work, one use. */
    private val usedBy = HashSet<Pair<SiteTagRef, ItemKey>>()

    /** Every tag counted, by key. */
    fun counted(): Map<String, TagStats> {
        scope.sources.forEach { s ->
            val siteFacet = normalization.siteFacetOf(s, facet) ?: return@forEach
            OfSource(s, siteFacet).count()
        }
        // A tag of no work yet — one the user made above others — is a tag all the same
        scope.tags.ids().forEach { id -> out.getOrPut(TagRepository.key(id)) { TagStats(TagRepository.key(id)) } }
        rejections.forEach { (key, items) -> out[key]?.rejected?.addAll(items) }
        val sourcesOf = bindings.entries.groupBy({ TagRepository.key(it.value) }, { it.key.source })
        out.values.forEach { t ->
            t.counts = Counts(t.items.size, t.graded.size, t.liked.size)
            t.sources = sourcesOf[t.key].orEmpty().distinct().sorted()
        }
        return out
    }

    /** The key of the tag the site's tag [site] is bound to; null of one not bound. */
    private fun tagOf(site: SiteTagRef): String? = bindings[site]?.let(TagRepository::key)

    /** The works of the source [s] counted, of its site's facet of tags [siteFacet]. */
    private inner class OfSource(private val s: SourceStore, private val siteFacet: String) {
        private val names = s.names(siteFacet)

        /** The latest grade of every item, of any version: the work as the user graded it last. */
        private val grades = s.ratings.all().groupBy { it.itemId }.mapValues { (_, r) -> r.maxBy { it.ratedAt }.grade }

        /** The tags every item has by a site's tag of it. */
        private val fromSite = HashMap<String, MutableSet<String>>()

        fun count() {
            siteTags()
            synonyms()
            userWord()
        }

        /** A work of [tag]; through the site's tag [site] when it is the site's, not the user's word alone. */
        private fun count(id: String, tag: String, site: SiteTagRef?, written: String?) {
            val stats = out.getOrPut(tag) { TagStats(tag) }
            val key = ItemKey(s.id, id)
            val work = works.representative(key)
            val uses =
                site
                    ?.let { stats.variant(it, written!!).uses }
                    ?.takeIf { !works.linked(key) || usedBy.add(site to work) }
            uses?.let { it.works++ }
            stats.items += work
            val grade = grades[id] ?: return
            uses?.let { it.graded++ }
            stats.graded += work
            if (Tags.liked(grade)) {
                uses?.let { it.liked++ }
                stats.liked += work
            }
        }

        /**
         * Every site's tag of the work counted, two of one tag both: each is a variant of it with its works; the tag's
         * works are a set, the work one of them once.
         */
        private fun siteTags() =
            s.items.forEachFacet(siteFacet) { id, keys ->
                keys.distinct().forEach { k ->
                    val site = SiteTagRef(s.id, k)
                    val tag = tagOf(site) ?: return@forEach
                    fromSite.getOrPut(id) { HashSet() } += tag
                    count(id, tag, site, (names[k] ?: k).trim())
                }
            }

        /**
         * A synonym the site names of one of its tags — of no work, most often — is a variant of the tag all the same:
         * a name the tag is known by, found by it.
         */
        private fun synonyms() =
            s.items.facetSynonyms(siteFacet).keys.forEach { k ->
                val site = SiteTagRef(s.id, k)
                val tag = tagOf(site) ?: return@forEach
                out.getOrPut(tag) { TagStats(tag) }.variant(site, (names[k] ?: k).trim())
            }

        /** A tag the user gave a work the site does not; a graded work the user said has it not. */
        private fun userWord() =
            s.corrections
                .allFacets()
                .filter { it.facet == facet && TagRepository.idOf(it.key) != null }
                .forEach { c ->
                    if (!c.added) {
                        if (c.itemId in grades)
                            rejections.getOrPut(c.key) { HashSet() } += works.representative(ItemKey(s.id, c.itemId))
                    } else if (fromSite[c.itemId]?.contains(c.key) != true) count(c.itemId, c.key, null, null)
                }
    }
}
