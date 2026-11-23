package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.draft.DictionaryChange
import io.github.vlsergey.recommend4me.draft.DictionaryChanges
import io.github.vlsergey.recommend4me.draft.DictionaryKind
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import org.jooq.DSLContext

/**
 * THE DOCUMENTS OF THE LIST A CHANGE OF THE DICTIONARIES REACHES, a portion at a time (2026-11-23): the changes written
 * after the last one the index took ([io.github.vlsergey.recommend4me.draft.DictionaryChanges]) — a universe or a tag
 * whose meaning to its works changed, a site's fandom or tag bound anew — and the items whose documents hold one of
 * them: of a value of the layer, as drafted, answered or given by a model — a universe holds every universe above it,
 * the documents of the universes under it are among its own —; of a site's value, as the site said it. Found in the
 * index itself, by the values it holds, not by the works of the databases; what [ListIndexing] writes anew of them is
 * what their drafts made otherwise, the rest compared and left. A change of a dictionary names no work, and a writing
 * of every work from the first is never begun by one: the changes come after those taken, the documents found after the
 * last written, the next changes taken once these are done.
 */
internal object ListDictionary {

    /** The items to be written, by source ([named]); whether more is left after them ([more]). */
    class Portion(val named: Map<String, Set<String>>, val more: Boolean)

    private val NONE = Portion(emptyMap(), false)

    /**
     * The next [limit] items of the [type] the changes of the dictionaries after those the [index] took reach, after
     * the last of the changes taken being written ([AT]); where the index stands among them moved after them, kept with
     * its next commit.
     */
    fun next(type: TypeStore, index: ListIndex, limit: Int): Portion {
        val changes = type.dictionaryChanges
        val stages = index.stages
        val (taken, upTo) = taking(changes, stages) ?: return NONE
        val values = valuesOf(type, changes.between(taken, upTo))
        val keys = if (values.isEmpty()) emptyList() else index.written { reached(it, values, stages.text(AT), limit) }
        if (keys.size < limit) {
            stages.changed(TAKEN, upTo)
            stages.forget { it == UP_TO || it == AT }
        } else stages.changed(AT, keys.last().toString())
        return Portion(
            keys.groupBy({ it.source }, { it.id }).mapValues { it.value.toSet() },
            keys.size >= limit || changes.any(upTo),
        )
    }

    /**
     * The changes being taken: after the last taken, up to the last of them, the last written when none were being
     * taken; none when nothing changed after those taken. An index never told of a change holds the dictionaries as
     * they were as it was written: every change before is in it.
     */
    private fun taking(changes: DictionaryChanges, stages: StageTimes): Pair<Long, Long>? {
        val taken =
            stages.since(TAKEN)
                ?: run {
                    stages.changed(TAKEN, changes.last())
                    return null
                }
        val upTo = stages.since(UP_TO) ?: changes.last().takeIf { it > taken }?.also { stages.changed(UP_TO, it) }
        return upTo?.let { taken to it }
    }

    /** Whether a change of the dictionaries is left for the [index] of the [type] to take. */
    fun any(type: TypeStore, index: ListIndex): Boolean {
        val taken = index.stages.since(TAKEN) ?: return false
        return index.stages.since(UP_TO) != null || type.dictionaryChanges.any(taken)
    }

    /** The values the [changes] are of, as the documents hold them: of every source they may be in, its facet's id. */
    private fun valuesOf(type: TypeStore, changes: Set<DictionaryChange>): Set<Pair<String, String>> =
        changes.flatMapTo(HashSet()) { change ->
            type.sources
                .filter { change.source == null || it.id == change.source }
                .flatMap { s ->
                    facetsOf(s, change.kind, site = change.source != null).map {
                        FeatureNames.facetId(s.source, it) to change.key
                    }
                }
        }

    /**
     * Of the source [s], the facets a value of the dictionary [kind] is held in: the layer's it is drafted into, or the
     * site's own it is drafted of when [site].
     */
    private fun facetsOf(s: SourceStore, kind: DictionaryKind, site: Boolean): List<FacetDef> {
        val schema = s.schema
        return when (kind) {
            DictionaryKind.UNIVERSE ->
                listOfNotNull(
                    if (site) UniverseFacet.fandomFacet(schema)?.let(schema::facet) else schema.facet(UniverseFacet.KEY)
                )
            DictionaryKind.TAG -> {
                val layer = schema.facets.filter { it.examplesFrom != null }
                if (site) layer.mapNotNull { def -> def.examplesFrom?.let(schema::facet) } else layer
            }
        }
    }

    /**
     * The next [limit] items, after the item [after], whose documents hold one of the [values] (facet's id, value), by
     * their keys: the values appended to a table of the writing's connection, temporary, and joined.
     */
    private fun reached(db: DSLContext, values: Set<Pair<String, String>>, after: String?, limit: Int): List<ItemKey> {
        db.execute("CREATE TEMPORARY TABLE IF NOT EXISTS $CHANGED (facet VARCHAR NOT NULL, value_key VARCHAR NOT NULL)")
        try {
            values.chunked(ListIndex.BATCH).forEach { chunk ->
                db.batch(chunk.map { (facet, key) -> db.query("INSERT INTO $CHANGED VALUES (?, ?)", facet, key) })
                    .execute()
            }
            return db.fetch(
                    "SELECT DISTINCT v.item_key FROM item_value v JOIN $CHANGED c " +
                        "ON v.facet = c.facet AND v.value_key = c.value_key " +
                        "WHERE v.item_key > ? ORDER BY v.item_key LIMIT ?",
                    after.orEmpty(),
                    limit,
                )
                .map { ItemKey.parse(it.get(0, String::class.java)) }
        } finally {
            db.execute("DELETE FROM $CHANGED")
        }
    }

    /** The temporary table of the values changed, of the writing's connection. */
    private const val CHANGED = "temp.main.changed_value"

    /** The last change of the dictionaries the index took. */
    private const val TAKEN = "list:dictionary"

    /** The last of the changes being taken: those after [TAKEN] up to it. */
    private const val UP_TO = "list:dictionaryUpTo"

    /** The last item written of those the changes being taken reach. */
    private const val AT = "list:dictionaryAt"
}
