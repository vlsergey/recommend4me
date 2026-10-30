package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_SEARCH
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/**
 * Two works of different sites that may be one — [first] the smaller key — with the cosines of the
 * vectors of their titles and of their descriptions (none when either has none), and what the pairs
 * are ordered by: the mean of the two, the title's alone without a description.
 */
class DuplicateCandidate(val first: ItemKey, val second: ItemKey, val title: Float, val description: Float?) {
    val similarity: Float get() = if (description == null) title else (title + description) / 2

    val pair: Pair<ItemKey, ItemKey> get() = first to second
}

/** When the candidates were last looked for, by which encoder of texts, of how many works. */
class DuplicateSearch(val madeAt: Instant, val encoder: String, val works: Int)

/** The candidates of the last search of the content type, in its model database: made anew whole by every search. */
class DuplicateRepository(private val db: DSLContext) {

    /** The candidates of a search in place of those before, and when it was made: one transaction. */
    fun replace(candidates: Collection<DuplicateCandidate>, search: DuplicateSearch) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(DUPLICATE_CANDIDATE).execute()
        candidates.chunked(BATCH).forEach { chunk ->
            tx.batch(chunk.map { d ->
                tx.insertInto(DUPLICATE_CANDIDATE)
                    .set(DUPLICATE_CANDIDATE.SOURCE, d.first.source).set(DUPLICATE_CANDIDATE.ITEM_ID, d.first.id)
                    .set(DUPLICATE_CANDIDATE.OTHER_SOURCE, d.second.source).set(DUPLICATE_CANDIDATE.OTHER_ITEM_ID, d.second.id)
                    .set(DUPLICATE_CANDIDATE.TITLE, d.title).set(DUPLICATE_CANDIDATE.DESCRIPTION, d.description)
                    .set(DUPLICATE_CANDIDATE.SIMILARITY, d.similarity)
            }).execute()
        }
        tx.deleteFrom(DUPLICATE_SEARCH).execute()
        tx.insertInto(DUPLICATE_SEARCH)
            .set(DUPLICATE_SEARCH.ID, 1).set(DUPLICATE_SEARCH.MADE_AT, search.madeAt)
            .set(DUPLICATE_SEARCH.ENCODER, search.encoder).set(DUPLICATE_SEARCH.WORKS, search.works)
            .execute()
    }

    /** The last search; null when there was none. */
    fun search(): DuplicateSearch? = db.selectFrom(DUPLICATE_SEARCH).fetchOne()?.let { DuplicateSearch(it.madeAt!!, it.encoder!!, it.works!!) }

    fun count(): Int = db.fetchCount(DUPLICATE_CANDIDATE)

    /** The [first] most alike candidates, the most alike first, till [action] says no more: read as it goes, not held whole. */
    fun forEachByLikeness(first: Int, action: (DuplicateCandidate) -> Boolean) {
        db.selectFrom(DUPLICATE_CANDIDATE).orderBy(DUPLICATE_CANDIDATE.SIMILARITY.desc()).limit(first)
            .fetchSize(FETCH).fetchLazy().use { cursor ->
                for (r in cursor) {
                    val d = DuplicateCandidate(ItemKey(r.source!!, r.itemId!!), ItemKey(r.otherSource!!, r.otherItemId!!), r.title!!, r.description)
                    if (!action(d)) return
                }
            }
    }

    /** The candidates among the [pairs] (the smaller key first), by pair. */
    fun of(pairs: Collection<Pair<ItemKey, ItemKey>>): Map<Pair<ItemKey, ItemKey>, DuplicateCandidate> =
        pairs.chunked(BATCH).flatMap { chunk ->
            db.selectFrom(DUPLICATE_CANDIDATE).where(DSL.or(chunk.map { (a, b) ->
                DUPLICATE_CANDIDATE.SOURCE.eq(a.source).and(DUPLICATE_CANDIDATE.ITEM_ID.eq(a.id))
                    .and(DUPLICATE_CANDIDATE.OTHER_SOURCE.eq(b.source)).and(DUPLICATE_CANDIDATE.OTHER_ITEM_ID.eq(b.id))
            })).fetch { r -> DuplicateCandidate(ItemKey(r.source!!, r.itemId!!), ItemKey(r.otherSource!!, r.otherItemId!!), r.title!!, r.description) }
        }.associateBy { it.pair }

    companion object {
        private const val BATCH = 1000
        private const val FETCH = 500
    }
}
