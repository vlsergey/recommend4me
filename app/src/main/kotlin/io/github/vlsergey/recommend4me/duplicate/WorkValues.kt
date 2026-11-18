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
    /**
     * Every value read by its family, then its key: its number. Of a million works' 28 million values a number each
     * occurrence, its name made once a value — not a string "<family>:<value>" and a set of them a work (2026-11-18: a
     * third of the reading of a search).
     */
    private val numbers = HashMap<String, HashMap<String, Int>>()

    /** The name of every value by its number: "<family>:<value>". */
    private val names = ArrayList<String>()

    /** Every work's values by their numbers, as read: one twice made one by [numbered]. */
    private val own = HashMap<String, Ints>()

    /** Of every work, the values the user took out of it, by their numbers. */
    private val removed = HashMap<String, MutableSet<Int>>()

    /** The values the user ignores, by their numbers: of no work. */
    private val ignored = HashSet<Int>()

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

    /** The values of the facets of the standard values, every work's: no answer is of them, none takes them out. */
    private val standard = HashMap<String, Ints>()

    /** The site's keys of every work's authors. */
    private val authorKeys = HashMap<String, MutableList<String>>()

    /** The names the user gave the values of the layer they added, "<facet>:<value>" to the name. */
    val given = HashMap<String, String>()

    /** The number of the value [key] of the [family], a new one given it first. */
    private fun numberOf(family: String, key: String): Int =
        numbers.getOrPut(family) { HashMap() }.getOrPut(key) { names.size.also { names += "$family:$key" } }

    private fun add(id: String, family: String, key: String) {
        own.getOrPut(id) { Ints() }.add(numberOf(family, key))
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
            standardOf[facet]?.let { standard.getOrPut(id) { Ints() }.add(numberOf(it, key)) }
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
        drafted()
        answered()
        // The ignored values numbered once, taken out of every work as it is numbered ([numbered])
        s.ignoredTags().forEach { (facet, keys) -> keys.forEach { ignored += numberOf(familyOf(facet), it) } }
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
                val family = familyOf(c.facet)
                val value = numberOf(family, c.key)
                if (c.added) {
                    own.getOrPut(c.itemId) { Ints() }.add(value)
                    removed[c.itemId]?.remove(value)
                    if (c.name != null) given["$family:${c.key}"] = c.name
                } else removed.getOrPut(c.itemId) { HashSet() } += value
            }
    }

    /** The name of every value by its number ([numbered]): "<family>:<value>". */
    fun names(): List<String> = names

    /**
     * The values of every work of [ids], by their places, by their numbers ascending, each once: the site's, the
     * drafts, the user's answers over them — what the user took out and what the user ignores left out —, the standard
     * values.
     */
    fun numbered(ids: List<String>): Array<IntArray> =
        Array(ids.size) { i ->
            val id = ids[i]
            val out = removed[id]
            val values = own[id]?.toArray()?.filter { it !in ignored && (out == null || it !in out) }.orEmpty()
            (values + (standard[id]?.toArray()?.asList() ?: emptyList())).distinct().sorted().toIntArray()
        }

    /** The values of every work of [ids], by their places, by their names. */
    fun named(ids: List<String>): Array<Collection<String>> =
        numbered(ids).map { own -> own.map { names[it] } }.toTypedArray()
}

/** A growing row of numbers: a work's values as they are read. */
internal class Ints {
    private var at = IntArray(INITIAL)
    private var size = 0

    fun add(value: Int) {
        if (size == at.size) at = at.copyOf(size * 2)
        at[size++] = value
    }

    fun toArray(): IntArray = at.copyOf(size)

    private companion object {
        const val INITIAL = 8
    }
}
