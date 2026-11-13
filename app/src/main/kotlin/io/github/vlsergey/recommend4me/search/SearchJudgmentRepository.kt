package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.database.preferences.tables.references.SEARCH_JUDGMENT
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.vector.Vectors
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Record

/**
 * The user's word on what a search showed, of one content type: how far a snippet of a work found answers a line
 * searched for — whole, in part, not at all ([SearchFit]) — of the snippet ([SearchSnippet]), never of the work whole.
 * The line as it is searched — trimmed, in lower case — so one line is one query however it was typed. Kept in the
 * user's preferences, the type's by the sources of its works.
 */
class SearchJudgmentRepository(
    private val db: DSLContext,
    /** The sources of the type. */
    private val sources: Collection<String>,
) {

    /** The word on the [snippet] of the work [key] shown for the line; null takes it back. */
    fun judge(query: String, key: ItemKey, snippet: SearchSnippet, fit: SearchFit?, now: Instant) {
        val q = normal(query)
        val hash = Vectors.keyOf(snippet.text)
        if (fit == null) {
            db.deleteFrom(SEARCH_JUDGMENT)
                .where(
                    SEARCH_JUDGMENT.QUERY.eq(q),
                    SEARCH_JUDGMENT.SOURCE.eq(key.source),
                    SEARCH_JUDGMENT.ITEM_ID.eq(key.id),
                    SEARCH_JUDGMENT.FIELD.eq(snippet.field),
                    SEARCH_JUDGMENT.SNIPPET_HASH.eq(hash),
                )
                .execute()
            return
        }
        db.insertInto(SEARCH_JUDGMENT)
            .set(SEARCH_JUDGMENT.QUERY, q)
            .set(SEARCH_JUDGMENT.SOURCE, key.source)
            .set(SEARCH_JUDGMENT.ITEM_ID, key.id)
            .set(SEARCH_JUDGMENT.FIELD, snippet.field)
            .set(SEARCH_JUDGMENT.SNIPPET_HASH, hash)
            .set(SEARCH_JUDGMENT.SNIPPET, snippet.text)
            .set(SEARCH_JUDGMENT.FIT, fit.name)
            .set(SEARCH_JUDGMENT.JUDGED_AT, now)
            .onDuplicateKeyUpdate()
            .set(SEARCH_JUDGMENT.FIT, fit.name)
            .set(SEARCH_JUDGMENT.JUDGED_AT, now)
            .execute()
    }

    /** The word on every snippet judged for the line, by its work. */
    fun of(query: String): Map<ItemKey, Map<SearchSnippet, SearchFit>> =
        db.select(SEARCH_JUDGMENT.SOURCE, SEARCH_JUDGMENT.ITEM_ID, SEARCH_JUDGMENT.FIELD, SEARCH_JUDGMENT.SNIPPET)
            .select(SEARCH_JUDGMENT.FIT)
            .from(SEARCH_JUDGMENT)
            .where(SEARCH_JUDGMENT.QUERY.eq(normal(query)), SEARCH_JUDGMENT.SOURCE.`in`(sources))
            .fetch()
            .groupBy({ key(it) }, { judged(it) })
            .mapValues { it.value.toMap() }

    /** Every line judged, with the word on every snippet judged for it, by its work. */
    fun all(): Map<String, Map<ItemKey, Map<SearchSnippet, SearchFit>>> =
        db.select(SEARCH_JUDGMENT.QUERY, SEARCH_JUDGMENT.SOURCE, SEARCH_JUDGMENT.ITEM_ID, SEARCH_JUDGMENT.FIELD)
            .select(SEARCH_JUDGMENT.SNIPPET, SEARCH_JUDGMENT.FIT)
            .from(SEARCH_JUDGMENT)
            .where(SEARCH_JUDGMENT.SOURCE.`in`(sources))
            .fetch()
            .groupBy { it[SEARCH_JUDGMENT.QUERY]!! }
            .mapValues { (_, rows) -> rows.groupBy({ key(it) }, { judged(it) }).mapValues { it.value.toMap() } }

    /** The judgments of a work removed from the library. */
    fun forget(key: ItemKey) {
        db.deleteFrom(SEARCH_JUDGMENT)
            .where(SEARCH_JUDGMENT.SOURCE.eq(key.source), SEARCH_JUDGMENT.ITEM_ID.eq(key.id))
            .execute()
    }

    private fun key(r: Record) = ItemKey(r[SEARCH_JUDGMENT.SOURCE]!!, r[SEARCH_JUDGMENT.ITEM_ID]!!)

    private fun judged(r: Record) =
        SearchSnippet(r[SEARCH_JUDGMENT.FIELD]!!, r[SEARCH_JUDGMENT.SNIPPET]!!) to
            SearchFit.valueOf(r[SEARCH_JUDGMENT.FIT]!!)

    companion object {
        /** A line as one query: trimmed, in lower case — as the search reads it. */
        fun normal(query: String) = query.trim().lowercase()
    }
}
