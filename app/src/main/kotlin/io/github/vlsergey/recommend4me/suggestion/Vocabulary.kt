package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.search.NamesAsked
import io.github.vlsergey.recommend4me.search.SearchProvider
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import java.util.IdentityHashMap

/**
 * The names a work's own words are looked for by: [names] — value to the names it goes by, one map for every work of
 * the same universes — but those of the values [without], the work's own few left out ([NamesAsked.without]). One for
 * every work of the same.
 */
internal class Named(val names: Map<String, List<String>>, val without: Set<String>) {
    /** The names looked for, those left out gone: of one work alone. */
    val whole: Map<String, List<String>>
        get() = if (without.isEmpty()) names else names - without
}

/**
 * The values of a facet of the universes besides the items' own, with the text their names are encoded from, and the
 * values each item may be given; null for any other facet.
 */
internal class Vocabulary(
    val values: Map<String, String>,
    val allowed: ((String) -> Set<String>)?,
    /**
     * The names a work's own words are looked for by in the index ([SearchProvider.mentions]) ([Named]): null for a
     * work not counted. [valuesOf]: the values a name found is of. Of the candidates of one work and of the passages
     * naming a value alone: no model reads them.
     */
    val names: ((String) -> Named?)? = null,
    val valuesOf: (String) -> Collection<String> = { listOf(it) },
    /**
     * Of the pairings: the [names] are of characters, a value two of them named in one paragraph
     * ([SearchProvider.together]).
     */
    val paired: Boolean = false,
    /** The groupings of the values the task is made of, in its order; none when they fall into no known groups. */
    val groupings: (List<String>) -> List<ValueGrouping> = { emptyList() },
    /**
     * The values the site gives each item as examples of the facet ([FacetDef.examplesFrom]); null for a facet without.
     */
    val examples: ((String) -> List<String>)? = null,
    /** How much a value an item lacks counts as one it has not ([SuggestionTask.lackWeight]). */
    val lackWeight: Double = 1.0,
    /**
     * Every name each value goes by, each a text that names it ([NameHint]); none for a facet whose values are not
     * named in texts.
     */
    val hintNames: Map<String, List<String>> = emptyMap(),
    /**
     * The values a text naming a value is judged among: the value and its rivals — the characters of its universes. One
     * collection for every value judged among the same: it is turned into the task's indices once ([NameHint.among]).
     */
    val hintAmong: (String) -> Collection<String> = { emptyList() },
    /**
     * Of each item, the values of its [allowed] it may not have after all ([SuggestionTask.barred]): [allowed] one set
     * for every work of the same universes, this the work's own; null: none.
     */
    val barred: ((String) -> Set<String>)? = null,
) {
    /**
     * How often each item's own words name each value, by [names], of the index of the [search] — a call for every map
     * of names shared by works; of the pairings, in how many paragraphs two characters are named together. Null when
     * the values are not counted.
     */
    fun mentions(
        store: SourceStore,
        ids: Collection<String>?,
        typeId: String,
        search: SearchProvider?,
    ): ((String) -> Map<String, Int>?)? {
        val of = names
        if (of == null || search == null) return null
        val asked = asked(store, ids, of)
        val counted = if (paired) together(store, typeId, search, asked) else byName(store, typeId, search, asked)
        val within = ids?.toHashSet()
        // A work counted that names none has none, not an unknown count
        return { id -> if (within != null && id !in within || of(id) == null) null else counted[id].orEmpty() }
    }

    /**
     * Every map of names with its works ([of] of each of the works [ids], every work when null), asked of the index at
     * once: one reading of it for them all. Every work of the source when all of them are: the index read whole, not a
     * work at a time.
     */
    private fun asked(store: SourceStore, ids: Collection<String>?, of: (String) -> Named?): List<NamesAsked> {
        val wanted = ids ?: store.items.keys().map { it.id }
        val groups = IdentityHashMap<Named, MutableList<String>>()
        wanted.forEach { id -> of(id)?.let { groups.getOrPut(it) { ArrayList() } += id } }
        return groups.map { (named, works) ->
            NamesAsked(
                if (ids == null && groups.size == 1) null else works.map { ItemKey(store.id, it) },
                named.names,
                named.without,
            )
        }
    }

    /** Of the pairings: in how many paragraphs of each work of the [store] two of its characters are named together. */
    private fun together(
        store: SourceStore,
        typeId: String,
        search: SearchProvider,
        asked: List<NamesAsked>,
    ): Map<String, Map<String, Int>> {
        val counted = HashMap<String, Map<String, Int>>()
        search.together(typeId, asked).forEach { byItem ->
            byItem.forEach { (key, pairs) ->
                if (key.source == store.id)
                    counted[key.id] = pairs.mapKeys { (pair, _) -> UniverseFacet.pairing(pair.first, pair.second) }
            }
        }
        return counted
    }

    /**
     * How often each work of the [store] names each value: the counts of its names found, by the values they are of.
     */
    private fun byName(
        store: SourceStore,
        typeId: String,
        search: SearchProvider,
        asked: List<NamesAsked>,
    ): Map<String, Map<String, Int>> {
        val counted = HashMap<String, Map<String, Int>>()
        search.mentions(typeId, asked).forEach { byItem ->
            byItem.forEach { (key, found) ->
                if (key.source != store.id) return@forEach
                val byValue = HashMap<String, Int>()
                found.forEach { (name, n) -> valuesOf(name).forEach { byValue.merge(it, n, Int::plus) } }
                counted[key.id] = byValue
            }
        }
        return counted
    }
}
