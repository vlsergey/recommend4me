package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR_DISTINCT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_AUTHOR
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** An author of a site: the source and the site's own key of them. */
data class SiteAuthorRef(val source: String, val author: String)

/**
 * The normalized authors, in the dictionaries: every author of every site bound to one, at first of
 * their own — never by the likeness of a name — the pairs the user told apart or said are partly
 * one, and the entries of the catalogues the user bound them to.
 */
class AuthorRepository(private val db: DSLContext) {

    /** The authors of the [source] and the normalized authors they are bound to: the site's key to the id. */
    fun bindingsOf(source: String): Map<String, Long> =
        db.select(SITE_AUTHOR.AUTHOR, SITE_AUTHOR.NORMALIZED).from(SITE_AUTHOR).where(SITE_AUTHOR.SOURCE.eq(source)).fetchMap({ it.value1()!! }, { it.value2()!! })

    /** The normalized author the author of a site is bound to now. */
    fun of(site: SiteAuthorRef): Long? =
        db.select(SITE_AUTHOR.NORMALIZED).from(SITE_AUTHOR).where(SITE_AUTHOR.SOURCE.eq(site.source), SITE_AUTHOR.AUTHOR.eq(site.author)).fetchOne()?.value1()

    /** Every author of every site of the normalized authors [ids]. */
    fun sitesOf(ids: Collection<Long>): Map<Long, List<SiteAuthorRef>> =
        ids.distinct().chunked(BATCH).flatMap { chunk ->
            db.select(SITE_AUTHOR.NORMALIZED, SITE_AUTHOR.SOURCE, SITE_AUTHOR.AUTHOR).from(SITE_AUTHOR).where(SITE_AUTHOR.NORMALIZED.`in`(chunk))
                .fetch { it.value1()!! to SiteAuthorRef(it.value2()!!, it.value3()!!) }
        }.groupBy({ it.first }, { it.second })

    /** Every normalized author of several authors of the sites, with them. */
    fun merged(): Map<Long, List<SiteAuthorRef>> {
        val ids = db.select(SITE_AUTHOR.NORMALIZED).from(SITE_AUTHOR).groupBy(SITE_AUTHOR.NORMALIZED).having(DSL.count().gt(1)).fetch { it.value1()!! }
        return sitesOf(ids)
    }

    /** The [authors] of the [source], none bound yet, bound to a normalized author each of their own, at once. */
    fun bindNew(source: String, authors: Collection<String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        authors.forEach { author ->
            val id = tx.insertInto(NORMALIZED_AUTHOR).defaultValues().returningResult(NORMALIZED_AUTHOR.ID).fetchOne()!!.value1()!!
            tx.insertInto(SITE_AUTHOR).set(SITE_AUTHOR.SOURCE, source).set(SITE_AUTHOR.AUTHOR, author).set(SITE_AUTHOR.NORMALIZED, id).set(SITE_AUTHOR.BOUND_AT, now).execute()
        }
    }

    /**
     * The normalized author [from] merged into [into]: every author of the sites of it bound to
     * [into], the pairs of it said apart or partly one moved to [into] — none of [into] with itself —
     * its entries of the catalogues besides those of [into], bound when they were, and [from] gone.
     */
    fun merge(from: Long, into: Long, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.update(SITE_AUTHOR).set(SITE_AUTHOR.NORMALIZED, into).set(SITE_AUTHOR.BOUND_AT, now).where(SITE_AUTHOR.NORMALIZED.eq(from)).execute()
        val d = NORMALIZED_AUTHOR_DISTINCT
        tx.select(d.FIRST_AUTHOR, d.OTHER_AUTHOR, d.PARTLY).from(d).where(d.FIRST_AUTHOR.eq(from).or(d.OTHER_AUTHOR.eq(from))).fetch().forEach { r ->
            val other = if (r.value1() == from) r.value2()!! else r.value1()!!
            if (other != into) {
                val (first, second) = pair(other, into)
                tx.insertInto(d).set(d.FIRST_AUTHOR, first).set(d.OTHER_AUTHOR, second).set(d.PARTLY, r.value3()).set(d.SAID_AT, now).onDuplicateKeyIgnore().execute()
            }
        }
        tx.deleteFrom(d).where(d.FIRST_AUTHOR.eq(from).or(d.OTHER_AUTHOR.eq(from))).execute()
        tx.deleteFrom(d).where(d.FIRST_AUTHOR.eq(into), d.OTHER_AUTHOR.eq(into)).execute()
        val e = NORMALIZED_AUTHOR_ENTRY
        tx.selectFrom(e).where(e.AUTHOR.eq(from)).fetch().forEach {
            tx.insertInto(e).set(e.AUTHOR, into).set(e.CATALOGUE, it.catalogue).set(e.ENTRY, it.entry).set(e.BOUND_AT, it.boundAt).onDuplicateKeyIgnore().execute()
        }
        tx.deleteFrom(NORMALIZED_AUTHOR).where(NORMALIZED_AUTHOR.ID.eq(from)).execute()
    }

    /** The author of a site taken out of the normalized author [from], to one of their own, and the two told apart: not offered as one again; the new id. */
    fun takeOut(site: SiteAuthorRef, from: Long, now: Instant): Long = db.transactionResult { c ->
        val tx = DSL.using(c)
        val id = tx.insertInto(NORMALIZED_AUTHOR).defaultValues().returningResult(NORMALIZED_AUTHOR.ID).fetchOne()!!.value1()!!
        tx.update(SITE_AUTHOR).set(SITE_AUTHOR.NORMALIZED, id).set(SITE_AUTHOR.BOUND_AT, now)
            .where(SITE_AUTHOR.SOURCE.eq(site.source), SITE_AUTHOR.AUTHOR.eq(site.author), SITE_AUTHOR.NORMALIZED.eq(from)).execute()
        val (first, second) = pair(id, from)
        tx.insertInto(NORMALIZED_AUTHOR_DISTINCT).set(NORMALIZED_AUTHOR_DISTINCT.FIRST_AUTHOR, first).set(NORMALIZED_AUTHOR_DISTINCT.OTHER_AUTHOR, second)
            .set(NORMALIZED_AUTHOR_DISTINCT.SAID_AT, now).execute()
        id
    }

    /** The two told apart — or, [partly], a part of the works of one the other's — in place of the word said before. */
    fun tell(a: Long, b: Long, partly: Boolean, now: Instant) {
        val (first, second) = pair(a, b)
        val d = NORMALIZED_AUTHOR_DISTINCT
        db.insertInto(d).set(d.FIRST_AUTHOR, first).set(d.OTHER_AUTHOR, second).set(d.PARTLY, partly).set(d.SAID_AT, now)
            .onDuplicateKeyUpdate().set(d.PARTLY, partly).set(d.SAID_AT, now).execute()
    }

    /** The word said of the two taken back. */
    fun takeBack(a: Long, b: Long) {
        val (first, second) = pair(a, b)
        val d = NORMALIZED_AUTHOR_DISTINCT
        db.deleteFrom(d).where(d.FIRST_AUTHOR.eq(first), d.OTHER_AUTHOR.eq(second)).execute()
    }

    /** Every pair told apart or said to be partly one, the smaller id first, with whether it is the latter. */
    fun told(): Map<Pair<Long, Long>, Boolean> {
        val d = NORMALIZED_AUTHOR_DISTINCT
        return db.select(d.FIRST_AUTHOR, d.OTHER_AUTHOR, d.PARTLY).from(d).fetchMap({ it.value1()!! to it.value2()!! }, { it.value3()!! })
    }

    fun exists(id: Long): Boolean = db.fetchExists(NORMALIZED_AUTHOR, NORMALIZED_AUTHOR.ID.eq(id))

    // --- The entries of a catalogue the authors are bound to ---

    /** Every author bound to entries of the catalogues: their id to the entries, the one bound first first. */
    fun entries(): Map<Long, List<TagEntryRef>> {
        val e = NORMALIZED_AUTHOR_ENTRY
        return db.select(e.AUTHOR, e.CATALOGUE, e.ENTRY).from(e).orderBy(e.BOUND_AT, e.CATALOGUE, e.ENTRY).fetch()
            .groupBy({ it.value1()!! }, { TagEntryRef(it.value2()!!, it.value3()!!) })
    }

    /** The author [id] bound to the entry [ref] besides the ones they are — or, [bound] false, no more to it. */
    fun bindEntry(id: Long, ref: TagEntryRef, bound: Boolean, now: Instant) {
        val e = NORMALIZED_AUTHOR_ENTRY
        if (bound) db.insertInto(e).set(e.AUTHOR, id).set(e.CATALOGUE, ref.catalogue).set(e.ENTRY, ref.entry).set(e.BOUND_AT, now).onDuplicateKeyIgnore().execute()
        else db.deleteFrom(e).where(e.AUTHOR.eq(id), e.CATALOGUE.eq(ref.catalogue), e.ENTRY.eq(ref.entry)).execute()
    }

    companion object {
        private const val BATCH = 1000

        /** The value of a normalized author among the values of the works: its id. */
        fun key(id: Long): String = id.toString()

        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
