package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.database.model.tables.references.LAYER_DRAFT
import io.github.vlsergey.recommend4me.universe.LayerDraft
import org.jooq.DSLContext

/**
 * The drafts of the layer of one source's works ([LayerDraft]), in the model database of its content type: made of the
 * site's values and the dictionary, made again whenever either changes — nothing of the user's.
 */
class DraftRepository(private val db: DSLContext, private val source: String) {

    /**
     * The drafts of the items [ids]: item to its drafts. By their ids up to a [BATCH] of them; of more, every draft of
     * the source read once and picked from: a query of a thousand ids walks them all anyway.
     */
    fun ofItems(ids: Collection<String>): Map<String, List<LayerDraft>> {
        val asked = ids.toHashSet()
        if (asked.size > BATCH) return all().filterKeys { it in asked }
        val out = HashMap<String, MutableList<LayerDraft>>()
        db.select(LAYER_DRAFT.ITEM_ID, LAYER_DRAFT.FACET, LAYER_DRAFT.VALUE_KEY, LAYER_DRAFT.SITE_VALUE)
            .from(LAYER_DRAFT)
            .where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.ITEM_ID.`in`(asked))
            .fetch {
                out.getOrPut(it.value1()!!) { ArrayList() } += LayerDraft(it.value2()!!, it.value3()!!, it.value4()!!)
            }
        return out
    }

    /** The drafts of every item of the [facets]: item to its drafts. */
    fun ofFacets(facets: Collection<String>): Map<String, List<LayerDraft>> {
        val out = HashMap<String, MutableList<LayerDraft>>()
        if (facets.isEmpty()) return out
        db.select(LAYER_DRAFT.ITEM_ID, LAYER_DRAFT.FACET, LAYER_DRAFT.VALUE_KEY, LAYER_DRAFT.SITE_VALUE)
            .from(LAYER_DRAFT)
            .where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.FACET.`in`(facets))
            .fetch {
                out.getOrPut(it.value1()!!) { ArrayList() } += LayerDraft(it.value2()!!, it.value3()!!, it.value4()!!)
            }
        return out
    }

    /** Every item's drafts: what a reading of every work is compared with. */
    fun all(): Map<String, List<LayerDraft>> {
        val out = HashMap<String, MutableList<LayerDraft>>()
        db.select(LAYER_DRAFT.ITEM_ID, LAYER_DRAFT.FACET, LAYER_DRAFT.VALUE_KEY, LAYER_DRAFT.SITE_VALUE)
            .from(LAYER_DRAFT)
            .where(LAYER_DRAFT.SOURCE.eq(source))
            .fetch {
                out.getOrPut(it.value1()!!) { ArrayList() } += LayerDraft(it.value2()!!, it.value3()!!, it.value4()!!)
            }
        return out
    }

    /** The drafts of the items [drafts] names replaced by these; an item with none is left with none. */
    fun replace(drafts: Map<String, List<LayerDraft>>) = db.transaction { c ->
        val t = c.dsl()
        drafts.keys.chunked(BATCH).forEach { chunk ->
            t.deleteFrom(LAYER_DRAFT).where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.ITEM_ID.`in`(chunk)).execute()
        }
        drafts
            .flatMap { (id, list) ->
                list.distinctBy { Triple(it.facet, it.key, it.from.take(SITE_VALUE_WIDTH)) }.map { id to it }
            }
            .chunked(BATCH)
            .forEach { chunk ->
                t.batch(
                        chunk.map { (id, d) ->
                            t.insertInto(LAYER_DRAFT)
                                .set(LAYER_DRAFT.SOURCE, source)
                                .set(LAYER_DRAFT.ITEM_ID, id)
                                .set(LAYER_DRAFT.FACET, d.facet)
                                .set(LAYER_DRAFT.VALUE_KEY, d.key.take(KEY_WIDTH))
                                .set(LAYER_DRAFT.SITE_VALUE, d.from.take(SITE_VALUE_WIDTH))
                        }
                    )
                    .execute()
            }
    }

    /** The drafts of the item removed from the library. */
    fun forget(itemId: String) {
        db.deleteFrom(LAYER_DRAFT).where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.ITEM_ID.eq(itemId)).execute()
    }

    companion object {
        private const val BATCH = 1000

        /** The width of the column of a draft's key (VARCHAR(500)): a longer one is cut. */
        private const val KEY_WIDTH = 500

        /** The width of the column of the site's value a draft is made of (VARCHAR(1000)): a longer one is cut. */
        private const val SITE_VALUE_WIDTH = 1000
    }
}
