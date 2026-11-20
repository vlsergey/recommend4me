package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE CANDIDATES OF THE LAST SEARCH READ: by their keys, by the keys of their works, the answered ones, or every one
 * streamed to a computation — never every one held as objects (owner, 2026-11-20). The few names of the sources and of
 * the facets' families one string each, not a copy a pair.
 */
class CandidateReads internal constructor(private val db: DSLContext) {

    /**
     * Every candidate, streamed to [action] one by one, none held: what a computation over every pair reads (owner,
     * 2026-11-20: no repository gives every row of a table as objects — two and a half million pairs read whole for a
     * request took 4 GB, and six such requests the whole memory).
     */
    fun forEach(action: (DuplicateCandidate) -> Unit) = stream(DSL.noCondition(), action)

    /**
     * Of every pair not answered, its two works and its chance of one work as kept with it ([PairScore]), streamed:
     * nothing else of it read — what the search of the authors' duplicates weighs their works by.
     */
    fun forEachChance(action: (ItemKey, ItemKey, Double) -> Unit) {
        val c = DUPLICATE_CANDIDATE
        val names = HashMap<String, String>()
        db.select(c.SOURCE, c.ITEM_ID, c.OTHER_SOURCE, c.OTHER_ITEM_ID, c.SAME_CHANCE)
            .from(c)
            .where(c.ANSWER.isNull)
            .fetchSize(FETCH)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach { r ->
                    action(
                        ItemKey(names.getOrPut(r.value1()!!) { r.value1()!! }, r.value2()!!),
                        ItemKey(names.getOrPut(r.value3()!!) { r.value3()!! }, r.value4()!!),
                        r.value5() ?: 0.0,
                    )
                }
            }
    }

    /** The candidates of the [pairs], the smaller key first, by the table's key. */
    fun ofPairs(pairs: Collection<Pair<ItemKey, ItemKey>>): List<DuplicateCandidate> {
        val c = DUPLICATE_CANDIDATE
        return pairs.chunked(BATCH).flatMap { chunk ->
            read(
                DSL.or(
                    chunk.map { (a, b) ->
                        c.SOURCE.eq(a.source)
                            .and(c.ITEM_ID.eq(a.id))
                            .and(c.OTHER_SOURCE.eq(b.source))
                            .and(c.OTHER_ITEM_ID.eq(b.id))
                    }
                )
            )
        }
    }

    /** The candidates of which either work is among the [items]: of one work's items, say. */
    fun ofItems(items: Collection<ItemKey>): List<DuplicateCandidate> {
        val c = DUPLICATE_CANDIDATE
        return items
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                read(
                    DSL.or(
                        chunk.map { k ->
                            c.SOURCE.eq(k.source)
                                .and(c.ITEM_ID.eq(k.id))
                                .or(c.OTHER_SOURCE.eq(k.source).and(c.OTHER_ITEM_ID.eq(k.id)))
                        }
                    )
                )
            }
            .distinctBy { it.pair }
    }

    /** The user's word on every pair of [pairs] as kept with it ([PairScore]): of a pair of none, none. */
    fun wordsOf(pairs: Collection<Pair<ItemKey, ItemKey>>): Said {
        val c = DUPLICATE_CANDIDATE
        val words = HashMap<Pair<ItemKey, ItemKey>, DuplicateAnswer>()
        val derived = HashSet<Pair<ItemKey, ItemKey>>()
        pairs.chunked(BATCH).forEach { chunk ->
            db.select(c.SOURCE, c.ITEM_ID, c.OTHER_SOURCE, c.OTHER_ITEM_ID, c.ANSWER, c.ANSWER_DERIVED)
                .from(c)
                .where(
                    c.ANSWER.isNotNull,
                    DSL.or(
                        chunk.map { (a, b) ->
                            c.SOURCE.eq(a.source)
                                .and(c.ITEM_ID.eq(a.id))
                                .and(c.OTHER_SOURCE.eq(b.source))
                                .and(c.OTHER_ITEM_ID.eq(b.id))
                        }
                    ),
                )
                .fetch { r ->
                    val pair = ItemKey(r.value1()!!, r.value2()!!) to ItemKey(r.value3()!!, r.value4()!!)
                    words[pair] = DuplicateAnswer.valueOf(r.value5()!!)
                    if (r.value6()!!) derived += pair
                }
        }
        return Said(words, derived)
    }

    /**
     * The candidates the user's word is on as the search or the last answer left it, with the word: what the models of
     * the answers are fitted on.
     */
    fun answered(): List<Pair<DuplicateCandidate, DuplicateAnswer>> {
        val c = DUPLICATE_CANDIDATE
        val words = HashMap<Pair<ItemKey, ItemKey>, DuplicateAnswer>()
        db.select(c.SOURCE, c.ITEM_ID, c.OTHER_SOURCE, c.OTHER_ITEM_ID, c.ANSWER)
            .from(c)
            .where(c.ANSWER.isNotNull)
            .fetch { r ->
                words[ItemKey(r.value1()!!, r.value2()!!) to ItemKey(r.value3()!!, r.value4()!!)] =
                    DuplicateAnswer.valueOf(r.value5()!!)
            }
        return read(c.ANSWER.isNotNull).mapNotNull { d -> words[d.pair]?.let { d to it } }
    }

    /**
     * The candidates both works of which are among the [items]: of the works of two authors, say; by the keys of the
     * works, either first or second.
     */
    fun among(items: Collection<ItemKey>): List<DuplicateCandidate> {
        val all = items.toHashSet()
        return ofItems(all).filter { it.first in all && it.second in all }
    }

    /** The candidates the [condition] picks. */
    private fun read(condition: Condition): List<DuplicateCandidate> =
        ArrayList<DuplicateCandidate>().also { out -> stream(condition) { out += it } }

    /** The candidates the [condition] picks, streamed to [action]. */
    private fun stream(condition: Condition, action: (DuplicateCandidate) -> Unit) {
        val names = HashMap<String, String>()
        val one = { s: String -> names.getOrPut(s) { s } }
        db.select(
                DUPLICATE_CANDIDATE.SOURCE,
                DUPLICATE_CANDIDATE.ITEM_ID,
                DUPLICATE_CANDIDATE.OTHER_SOURCE,
                DUPLICATE_CANDIDATE.OTHER_ITEM_ID,
                DUPLICATE_CANDIDATE.TITLE,
                DUPLICATE_CANDIDATE.DESCRIPTION,
                DUPLICATE_CANDIDATE.SIZE,
                DUPLICATE_CANDIDATE.FACETS,
                DUPLICATE_CANDIDATE.FOUND_FOR,
                DUPLICATE_CANDIDATE.SAME_AUTHOR,
                DUPLICATE_CANDIDATE.TITLE_WITHIN,
                DUPLICATE_CANDIDATE.TITLE_HOLDING,
                DUPLICATE_CANDIDATE.AUTHORS_ALIKE,
                DUPLICATE_CANDIDATE.AUTHORS_WITHIN,
                DUPLICATE_CANDIDATE.AUTHORS_HOLDING,
                DUPLICATE_CANDIDATE.TITLE_VECTOR,
                DUPLICATE_CANDIDATE.DESCRIPTION_LETTERS,
                DUPLICATE_CANDIDATE.FANDOM_LETTERS,
                DUPLICATE_CANDIDATE.CHARACTER_LETTERS,
                DUPLICATE_CANDIDATE.PARTLY_SAME_AUTHOR,
                DUPLICATE_CANDIDATE.WORDS,
                DUPLICATE_CANDIDATE.OTHER_WORDS,
            )
            .from(DUPLICATE_CANDIDATE)
            .where(condition)
            .fetchSize(FETCH)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach { r ->
                    action(
                        DuplicateCandidate(
                            ItemKey(one(r.value1()!!), r.value2()!!),
                            ItemKey(one(r.value3()!!), r.value4()!!),
                            r.value5()!!,
                            r.value6(),
                            r.value7(),
                            r.value8()?.let { numbers(it, one) }.orEmpty(),
                            r.value9()!!.toInt(),
                            r.value10()!!,
                            r.value11() ?: 0f,
                            r.value12() ?: 0f,
                            lexical(r.value13(), r.value14(), r.value15()),
                            r.value16(),
                            letters(r.value17(), r.value18(), r.value19()),
                            r.value20()!!,
                            r.value21(),
                            r.value22(),
                        )
                    )
                }
            }
    }

    private companion object {
        /** The keys read by one query at most: a list of keys in a query, not a meaning. */
        const val BATCH = 500

        const val FETCH = 5000
    }
}
