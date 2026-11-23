package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.database.DuckDatabase
import io.github.vlsergey.recommend4me.database.authorduplicates.tables.references.AUTHOR_CANDIDATE
import io.github.vlsergey.recommend4me.database.authorduplicates.tables.references.AUTHOR_SEARCH
import java.time.Instant
import org.duckdb.DuckDBConnection
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Two normalized authors who may be one — [first] the smaller id — as the search of the authors' duplicates found them
 * ([AuthorSearch]): whether they are bound to one entry of a catalogue ([oneEntry]), how many pairs of their works the
 * user said are one ([matching]), the chances of the rest summed ([expected]), the sites of their works ([sites]), and
 * the user's word on them ([partly]: none — not told apart; false — told apart; true — partly one).
 */
class AuthorCandidate(
    val first: Long,
    val second: Long,
    val oneEntry: Boolean,
    val matching: Int,
    val expected: Double,
    val sites: Set<String>,
    val partly: Boolean?,
) {
    val pair: Pair<Long, Long>
        get() = first to second
}

/** When the authors' duplicates were last looked for ([madeAt]), of how many works. */
class AuthorSearchMade(val madeAt: Instant, val works: Int)

/**
 * THE PAIRS OF AUTHORS OF THE LAST SEARCH OF THEIR DUPLICATES, in the store of the type's authors' duplicates
 * ([DuckDatabase], beside the works': owner, 2026-11-24): made anew whole by every search, appended in the order of
 * their key, no index kept; a page of them chosen in the store by its filter and its order (owner, 2026-11-20: "the tab
 * of the authors' duplicates reads a bounded amount of the database"), a word of the user's kept with its pair at once.
 */
class AuthorCandidateRepository(private val store: DuckDatabase) {
    private val db: DSLContext = store.dsl
    private val c = AUTHOR_CANDIDATE

    /**
     * The [pairs] of a search made [at] of [works] works, in place of those before: in one transaction, a reading in
     * between sees the last search whole.
     */
    fun replace(pairs: Collection<AuthorCandidate>, at: Instant, works: Int) {
        store.transaction { connection, tx ->
            tx.deleteFrom(AUTHOR_SEARCH).execute()
            tx.deleteFrom(c).execute()
            connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, c.name.lowercase()).use { a ->
                pairs.sortedWith(compareBy({ it.first }, { it.second })).forEach {
                    a.beginRow()
                    a.append(it.first)
                    a.append(it.second)
                    a.append(it.oneEntry)
                    a.append(it.matching)
                    a.append(it.expected)
                    a.append(sitesOf(it.sites))
                    val answer = answerOf(it.partly)
                    if (answer == null) a.appendNull() else a.append(answer)
                    a.endRow()
                }
            }
            tx.insertInto(AUTHOR_SEARCH)
                .set(AUTHOR_SEARCH.ID, 1)
                .set(AUTHOR_SEARCH.MADE_AT, at)
                .set(AUTHOR_SEARCH.WORKS, works)
                .execute()
        }
    }

    /** The last search; none of a type never searched. */
    fun search(): AuthorSearchMade? =
        db.selectFrom(AUTHOR_SEARCH).fetchOne()?.let { AuthorSearchMade(it.madeAt!!, it.works!!) }

    /**
     * The pairs of the [said] the [filter] lets from [offset], [limit] of them — two bound to one entry first, then the
     * most works matching, then the most expected — and how many it lets in all.
     */
    fun page(said: AuthorSaid, filter: OfferFilter, offset: Int, limit: Int): Pair<Int, List<AuthorCandidate>> {
        val where = condition(said, filter)
        val total = db.selectCount().from(c).where(where).fetchOne(0, Int::class.java) ?: 0
        val rows =
            db.selectFrom(c)
                .where(where)
                .orderBy(c.ONE_ENTRY.desc(), c.MATCHING.desc(), c.EXPECTED.desc(), c.FIRST_AUTHOR, c.OTHER_AUTHOR)
                .offset(offset)
                .limit(limit)
                .fetch { r ->
                    AuthorCandidate(
                        r.firstAuthor!!,
                        r.otherAuthor!!,
                        r.oneEntry!!,
                        r.matching!!,
                        r.expected!!,
                        r.sites!!.split(',').filter { it.isNotEmpty() }.toSet(),
                        r.answer?.let { it == PARTIAL },
                    )
                }
        return total to rows
    }

    /** The pairs of the [said] the [filter] lets: of an author, with a site's works or with none. */
    private fun condition(said: AuthorSaid, filter: OfferFilter): Condition =
        DSL.and(
            listOfNotNull(
                when (said) {
                    AuthorSaid.OFFERED -> c.ANSWER.isNull
                    AuthorSaid.DIFFERENT -> c.ANSWER.eq(DIFFERENT)
                    AuthorSaid.PARTIAL -> c.ANSWER.eq(PARTIAL)
                    AuthorSaid.ALL -> null
                },
                filter.author?.let { c.FIRST_AUTHOR.eq(it).or(c.OTHER_AUTHOR.eq(it)) },
            ) + filter.onSite.map { c.SITES.contains(",$it,") } + filter.offSite.map { c.SITES.contains(",$it,").not() }
        )

    /**
     * The user's word on the authors [a] and [b] kept with their pair at once — told apart, partly one ([partly]), or
     * none, taken back —: struck out of the open ones, or put back, till the next search.
     */
    fun said(a: Long, b: Long, partly: Boolean?) {
        val (first, second) = AuthorRepository.pair(a, b)
        db.update(c)
            .set(c.ANSWER, answerOf(partly))
            .where(c.FIRST_AUTHOR.eq(first), c.OTHER_AUTHOR.eq(second))
            .execute()
    }

    /** Every pair of the author [id], merged into another: no such author any more. */
    fun gone(id: Long) {
        db.deleteFrom(c).where(c.FIRST_AUTHOR.eq(id).or(c.OTHER_AUTHOR.eq(id))).execute()
    }

    private fun sitesOf(sites: Set<String>) = sites.sorted().joinToString(",", ",", ",")

    private fun answerOf(partly: Boolean?) =
        when (partly) {
            null -> null
            true -> PARTIAL
            false -> DIFFERENT
        }

    private companion object {
        const val DIFFERENT = "DIFFERENT"
        const val PARTIAL = "PARTIAL"
    }
}
