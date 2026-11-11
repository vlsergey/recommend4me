package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.author.AuthorRepository
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagRepository

/**
 * Every work's values of every facet the sites share, as the site and the user give them — no model asked — each
 * "<facet>:<value>": of the facets of the application's layer (the kind, the universes, the characters — the main and
 * the secondary alike —, the pairings, the normalized tags, the languages, the age rating) the site's own, the drafts
 * of the layer, of the tags the site's tags by the normalized ones they are bound to, the tags the user ignores left
 * out, the user's answers over them all; of a site's facet of the standard values every site gives alike (the status,
 * the direction, the ratings the site allows) its values; the normalized authors. The site's own read in one pass
 * ([read]), then gathered in this order ([gather]), each step over the ones before it.
 *
 * Of every work of the site — what the search compares —, or of the works [only] — what a pair shows its works by, the
 * same values the search compared.
 */
internal class WorkValues(private val s: SourceStore, private val only: Collection<String>? = null) {
    private val own = HashMap<String, MutableSet<String>>()

    /** The facets of the application's layer. */
    private val layers = s.schema.facets.filter { it.editable }

    private val layerKeys = layers.map { it.key }.toSet()

    /** Of a site's facet of tags, the families of the layers it gives examples of. */
    private val examplesOf =
        layers.filter { it.examplesFrom != null }.groupBy({ it.examplesFrom!! }, { familyOf(it.key) })

    /** Of a site's facet of the standard values, the standard facet. */
    private val standardOf =
        s.schema.facets.filter { !it.editable && it.standard?.values != null }.associate { it.key to it.standard!!.key }

    private val authorFacet = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key

    /** The values of the facets of the standard values, every work's, laid on [own] after the user's answers. */
    private val standard = HashMap<String, MutableList<String>>()

    /** The site's keys of every work's authors. */
    private val authorKeys = HashMap<String, MutableList<String>>()

    /** The names the user gave the values of the layer they added, "<facet>:<value>" to the name. */
    val given = HashMap<String, String>()

    private fun add(id: String, prefix: String, key: String) {
        own.getOrPut(id) { HashSet() } += "$prefix:$key"
    }

    /**
     * Every value of every facet the search reads, of every work, in one pass over the site's values in the order they
     * are kept: a pass of each facet apart, sorted by the work and the site's order, took half the reading of a search
     * — ten passes of a site, each by an index and a sort of its own (2026-11-08). Of the works [only], theirs alone.
     */
    fun read() {
        val bound = s.tags.bindingsOf(s.id)
        val visit = { id: String, facet: String, key: String ->
            if (facet in layerKeys) add(id, familyOf(facet), key)
            examplesOf[facet]?.let { families ->
                bound[key]?.let { tag -> families.forEach { add(id, it, TagRepository.key(tag)) } }
            }
            standardOf[facet]?.let { standard.getOrPut(id) { ArrayList() } += "$it:$key" }
            if (facet == authorFacet) authorKeys.getOrPut(id) { ArrayList() } += key
        }
        if (only == null) s.items.forEachFacetValue(visit)
        else
            s.items.facetsOf(only).forEach { (id, facets) ->
                facets.forEach { (facet, keys) -> keys.forEach { visit(id, facet, it) } }
            }
    }

    /** The normalized authors of every work of [ids], by their places: of the site's authors [read]. */
    fun authorsOf(ids: List<String>): Array<LongArray> {
        if (authorKeys.isEmpty()) return Array(ids.size) { LongArray(0) }
        val authors = s.authors.bindingsOf(s.id)
        return Array(ids.size) { i ->
            authorKeys[ids[i]].orEmpty().mapNotNull { authors[it] }.distinct().toLongArray()
        }
    }

    /** Every value of every work of the source gathered, the normalized [authors] of the works of [ids] too. */
    fun gather(ids: List<String>, authors: Array<LongArray>) {
        val ignored = s.ignoredTags()
        drafted()
        answered()
        // The ignored values named once, each work's taken out of it in one pass
        val gone = ignored.flatMapTo(HashSet()) { (facet, keys) -> keys.map { "${familyOf(facet)}:$it" } }
        if (gone.isNotEmpty()) own.values.forEach { it.removeAll(gone) }
        // The standard values over the answers: no answer is of them
        standard.forEach { (id, values) -> own.getOrPut(id) { HashSet() } += values }
        // The authors as the normalized authors they are: one writer's accounts on two sites one value
        ids.forEachIndexed { i, id -> authors[i].forEach { add(id, AUTHOR_FAMILY, AuthorRepository.key(it)) } }
    }

    /** The drafts of the layer. */
    private fun drafted() {
        (only?.let(s.drafts::ofItems) ?: s.drafts.all()).forEach { (id, drafts) ->
            drafts.filter { it.facet in layerKeys }.forEach { add(id, familyOf(it.facet), it.key) }
        }
    }

    /** The user's answers on the facets of the layer, over every value before: added or taken out. */
    private fun answered() {
        (only?.let { s.corrections.facetsOf(it).values.flatten() } ?: s.corrections.allFacets())
            .filter { it.facet in layerKeys }
            .forEach { c ->
                val value = "${familyOf(c.facet)}:${c.key}"
                if (c.added) own.getOrPut(c.itemId) { HashSet() } += value else own[c.itemId]?.remove(value)
                if (c.added && c.name != null) given[value] = c.name
            }
    }

    /** The values of every work of [ids], by their places. */
    fun named(ids: List<String>): Array<Collection<String>> = Array(ids.size) { i -> own[ids[i]].orEmpty() }
}
