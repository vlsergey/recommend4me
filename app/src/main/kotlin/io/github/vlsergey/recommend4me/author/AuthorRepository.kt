package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR_DISTINCT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_AUTHOR
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** An author of a site: the source and the site's own key of them. */
data class SiteAuthorRef(val source: String, val author: String)

/** Why an author of a site is with the others of their normalized author, merged into it. */
sealed interface MergedBy {
    /** The user merged them. */
    data object User : MergedBy

    /** The page of the author of a site [owner] lists theirs as a place of theirs ([AuthorPlaces]). */
    data class Place(val owner: SiteAuthorRef) : MergedBy
}

/**
 * An author of a site of a normalized author, and why they are with the others of it; null of the one it was made of.
 */
data class SiteAuthorBound(val ref: SiteAuthorRef, val mergedBy: MergedBy?)

/**
 * The normalized authors, in the dictionaries: every author of every site bound to one, at first of their own — never
 * by the likeness of a name — the pairs the user told apart or said are partly one, and the entries of the catalogues
 * the user bound them to.
 */
class AuthorRepository(
    private val db: DSLContext,
    /** Told of every merge of two authors and every author of a site taken out of one. */
    private val regrouped: () -> Unit,
) {

    /** The entries of the catalogues the authors are bound to. */
    val entries = AuthorEntryRepository(db)

    /** The places on the web the authors' pages list as theirs. */
    val places = AuthorPlaceRepository(db)

    /** The pairs told apart or said to be partly one. */
    val distinct = AuthorDistinctions(db)

    /** The authors of the [source] and the normalized authors they are bound to: the site's key to the id. */
    fun bindingsOf(source: String): Map<String, Long> =
        db.select(SITE_AUTHOR.AUTHOR, SITE_AUTHOR.NORMALIZED)
            .from(SITE_AUTHOR)
            .where(SITE_AUTHOR.SOURCE.eq(source))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** The normalized author the author of a site is bound to now. */
    fun of(site: SiteAuthorRef): Long? =
        db.select(SITE_AUTHOR.NORMALIZED)
            .from(SITE_AUTHOR)
            .where(SITE_AUTHOR.SOURCE.eq(site.source), SITE_AUTHOR.AUTHOR.eq(site.author))
            .fetchOne()
            ?.value1()

    /** Every author of every site of the normalized authors [ids]. */
    fun sitesOf(ids: Collection<Long>): Map<Long, List<SiteAuthorRef>> =
        ids.distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(SITE_AUTHOR.NORMALIZED, SITE_AUTHOR.SOURCE, SITE_AUTHOR.AUTHOR)
                    .from(SITE_AUTHOR)
                    .where(SITE_AUTHOR.NORMALIZED.`in`(chunk))
                    .fetch { it.value1()!! to SiteAuthorRef(it.value2()!!, it.value3()!!) }
            }
            .groupBy({ it.first }, { it.second })

    /** Every author of every site of the normalized authors [ids], with why each is with the others. */
    fun boundOf(ids: Collection<Long>): Map<Long, List<SiteAuthorBound>> {
        val a = SITE_AUTHOR
        return ids.distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(a.NORMALIZED, a.SOURCE, a.AUTHOR, a.MERGED_BY, a.MERGED_BY_SOURCE, a.MERGED_BY_AUTHOR)
                    .from(a)
                    .where(a.NORMALIZED.`in`(chunk))
                    .fetch { r ->
                        val by =
                            when (r.value4()) {
                                USER -> MergedBy.User
                                PLACE -> MergedBy.Place(SiteAuthorRef(r.value5()!!, r.value6()!!))
                                else -> null
                            }
                        r.value1()!! to SiteAuthorBound(SiteAuthorRef(r.value2()!!, r.value3()!!), by)
                    }
            }
            .groupBy({ it.first }, { it.second })
    }

    /** Every normalized author of several authors of the sites, with them. */
    fun merged(): Map<Long, List<SiteAuthorRef>> {
        val ids =
            db.select(SITE_AUTHOR.NORMALIZED)
                .from(SITE_AUTHOR)
                .groupBy(SITE_AUTHOR.NORMALIZED)
                .having(DSL.count().gt(1))
                .fetch { it.value1()!! }
        return sitesOf(ids)
    }

    /** The [authors] of the [source], none bound yet, bound to a normalized author each of their own, at once. */
    fun bindNew(source: String, authors: Collection<String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        authors.forEach { author ->
            val id =
                tx.insertInto(NORMALIZED_AUTHOR)
                    .defaultValues()
                    .returningResult(NORMALIZED_AUTHOR.ID)
                    .fetchOne()!!
                    .value1()!!
            tx.insertInto(SITE_AUTHOR)
                .set(SITE_AUTHOR.SOURCE, source)
                .set(SITE_AUTHOR.AUTHOR, author)
                .set(SITE_AUTHOR.NORMALIZED, id)
                .set(SITE_AUTHOR.BOUND_AT, now)
                .execute()
        }
    }

    /**
     * The normalized author [from] merged into [into], [by] the user or by a place an author's page lists: every author
     * of the sites of it bound to [into] — each not merged into it before marked as merged [by] it — the pairs of it
     * said apart or partly one moved to [into] — none of [into] with itself — its entries of the catalogues besides
     * those of [into], bound when they were, and [from] gone.
     */
    fun merge(from: Long, into: Long, now: Instant, by: MergedBy = MergedBy.User) {
        db.transaction { c ->
            val tx = DSL.using(c)
            val place = (by as? MergedBy.Place)?.owner
            tx.update(SITE_AUTHOR)
                .set(SITE_AUTHOR.MERGED_BY, if (place == null) USER else PLACE)
                .set(SITE_AUTHOR.MERGED_BY_SOURCE, place?.source)
                .set(SITE_AUTHOR.MERGED_BY_AUTHOR, place?.author)
                .where(SITE_AUTHOR.NORMALIZED.eq(from), SITE_AUTHOR.MERGED_BY.isNull)
                .execute()
            tx.update(SITE_AUTHOR)
                .set(SITE_AUTHOR.NORMALIZED, into)
                .set(SITE_AUTHOR.BOUND_AT, now)
                .where(SITE_AUTHOR.NORMALIZED.eq(from))
                .execute()
            val d = NORMALIZED_AUTHOR_DISTINCT
            tx.select(d.FIRST_AUTHOR, d.OTHER_AUTHOR, d.PARTLY)
                .from(d)
                .where(d.FIRST_AUTHOR.eq(from).or(d.OTHER_AUTHOR.eq(from)))
                .fetch()
                .forEach { r ->
                    val other = if (r.value1() == from) r.value2()!! else r.value1()!!
                    if (other != into) {
                        val (first, second) = pair(other, into)
                        tx.insertInto(d)
                            .set(d.FIRST_AUTHOR, first)
                            .set(d.OTHER_AUTHOR, second)
                            .set(d.PARTLY, r.value3())
                            .set(d.SAID_AT, now)
                            .onDuplicateKeyIgnore()
                            .execute()
                    }
                }
            tx.deleteFrom(d).where(d.FIRST_AUTHOR.eq(from).or(d.OTHER_AUTHOR.eq(from))).execute()
            tx.deleteFrom(d).where(d.FIRST_AUTHOR.eq(into), d.OTHER_AUTHOR.eq(into)).execute()
            val e = NORMALIZED_AUTHOR_ENTRY
            tx.selectFrom(e).where(e.AUTHOR.eq(from)).fetch().forEach {
                tx.insertInto(e)
                    .set(e.AUTHOR, into)
                    .set(e.CATALOGUE, it.catalogue)
                    .set(e.ENTRY, it.entry)
                    .set(e.BOUND_AT, it.boundAt)
                    .onDuplicateKeyIgnore()
                    .execute()
            }
            tx.deleteFrom(NORMALIZED_AUTHOR).where(NORMALIZED_AUTHOR.ID.eq(from)).execute()
        }
        regrouped()
    }

    /**
     * The author of a site taken out of the normalized author [from], to one of their own, and the two told apart: not
     * offered as one again; the new id.
     */
    fun takeOut(site: SiteAuthorRef, from: Long, now: Instant): Long =
        db.transactionResult { c ->
                val tx = DSL.using(c)
                val id =
                    tx.insertInto(NORMALIZED_AUTHOR)
                        .defaultValues()
                        .returningResult(NORMALIZED_AUTHOR.ID)
                        .fetchOne()!!
                        .value1()!!
                tx.update(SITE_AUTHOR)
                    .set(SITE_AUTHOR.NORMALIZED, id)
                    .set(SITE_AUTHOR.BOUND_AT, now)
                    .setNull(SITE_AUTHOR.MERGED_BY)
                    .setNull(SITE_AUTHOR.MERGED_BY_SOURCE)
                    .setNull(SITE_AUTHOR.MERGED_BY_AUTHOR)
                    .where(
                        SITE_AUTHOR.SOURCE.eq(site.source),
                        SITE_AUTHOR.AUTHOR.eq(site.author),
                        SITE_AUTHOR.NORMALIZED.eq(from),
                    )
                    .execute()
                val (first, second) = pair(id, from)
                tx.insertInto(NORMALIZED_AUTHOR_DISTINCT)
                    .set(NORMALIZED_AUTHOR_DISTINCT.FIRST_AUTHOR, first)
                    .set(NORMALIZED_AUTHOR_DISTINCT.OTHER_AUTHOR, second)
                    .set(NORMALIZED_AUTHOR_DISTINCT.SAID_AT, now)
                    .execute()
                id
            }
            .also { regrouped() }

    fun exists(id: Long): Boolean = db.fetchExists(NORMALIZED_AUTHOR, NORMALIZED_AUTHOR.ID.eq(id))

    companion object {
        /** The authors read by one query at most: a list of keys in a query, not a meaning. */
        private const val BATCH = 1000

        /** The value of a normalized author among the values of the works: its id. */
        fun key(id: Long): String = id.toString()

        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a

        /** The marks of why an author of a site was merged into their normalized author: the user's word, a place. */
        private const val USER = "USER"
        private const val PLACE = "PLACE"
    }
}
