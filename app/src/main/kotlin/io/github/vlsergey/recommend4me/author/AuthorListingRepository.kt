package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorKind
import io.github.vlsergey.recommend4me.database.model.tables.references.AUTHOR_LISTING
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * A normalized author of works as the last search of the authors' duplicates read them ([AuthorSearch]): how many works
 * they have ([works]), whether several authors of the sites are merged into them ([merged]), whether they are bound to
 * an entry of a catalogue ([catalogued]), and their names on the sites and of their entries, lowercase ([names]).
 */
class AuthorListed(
    val author: Long,
    val works: Int,
    val merged: Boolean,
    val catalogued: Boolean,
    val names: String,
)

/**
 * THE AUTHORS OF WORKS OF THE LAST SEARCH OF THE AUTHORS' DUPLICATES, in the model database of the type: made anew
 * whole by every search, a page of them chosen in the database by its kind, a name, and the most works first (owner,
 * 2026-11-20: the tab of all the authors reads no more than its page); an author merged into another gone at once.
 */
class AuthorListingRepository(private val db: DSLContext) {
    private val a = AUTHOR_LISTING

    /** The [authors] of a search in place of those before. */
    fun replace(authors: Collection<AuthorListed>) {
        db.truncate(a).execute()
        val columns = listOf(a.AUTHOR, a.WORKS, a.MERGED, a.CATALOGUED, a.NAMES)
        authors
            .sortedBy { it.author }
            .chunked(BATCH)
            .forEach { chunk ->
                val batch = db.batch(db.insertInto(a).columns(columns).values(List(columns.size) { null }))
                chunk.forEach { batch.bind(it.author, it.works, it.merged, it.catalogued, it.names.take(NAMES)) }
                batch.execute()
            }
    }

    /**
     * The authors of the [kind] whose names hold [needle] (lowercase; none, every one) from [offset], [limit] of them,
     * the most works first; and how many in all.
     */
    fun page(kind: AuthorKind, needle: String?, offset: Int, limit: Int): Pair<Int, List<AuthorListed>> {
        val where =
            DSL.and(
                listOfNotNull(
                    when (kind) {
                        AuthorKind.ALL -> null
                        AuthorKind.MERGED -> a.MERGED.isTrue
                        AuthorKind.CATALOGUED -> a.CATALOGUED.isTrue
                    },
                    needle?.takeIf { it.isNotEmpty() }?.let { a.NAMES.contains(it) },
                )
            )
        val total = db.selectCount().from(a).where(where).fetchOne(0, Int::class.java) ?: 0
        val rows =
            db.selectFrom(a).where(where).orderBy(a.WORKS.desc(), a.AUTHOR).offset(offset).limit(limit).fetch {
                AuthorListed(it.author!!, it.works!!, it.merged!!, it.catalogued!!, it.names!!)
            }
        return total to rows
    }

    /** The author [from] merged into [into]: gone, and [into] of several authors of the sites. */
    fun merged(from: Long, into: Long) {
        db.deleteFrom(a).where(a.AUTHOR.eq(from)).execute()
        db.update(a).set(a.MERGED, true).where(a.AUTHOR.eq(into)).execute()
    }

    private companion object {
        const val BATCH = 1000

        /** The names kept of an author at most: as the column holds. */
        const val NAMES = 4000
    }
}
