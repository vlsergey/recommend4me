package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_TAG
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A tag of a site: its source and the site's own key of it. */
data class SiteTagRef(val source: String, val tag: String)

/** The widest name of a tag kept, the user's or one worked out: the width of its column. */
internal const val NAME_WIDTH = 1000

/** The widest key or name of a site's tag kept: the width of its columns. */
internal const val SITE_TAG_WIDTH = 500

/**
 * THE NORMALIZED TAGS OF EVERY CONTENT TYPE, in the user's dictionaries ([db]): every normalized tag — an id and the
 * name the user gave it, if any — and every site's tag bound to one of them. What the user said of two tags is kept by
 * [relations], the entries of the catalogues they are bound to by [entries], what is shown of them — the names worked
 * out of the sites', made again from the rest, and their works counted — by [stats], the vectors of their names by
 * [vectors].
 *
 * A tag is known to the application — as a value of the works' tags, in the user's answers — by its id, written as a
 * text ([key]).
 */
class TagRepository(private val db: DSLContext) {

    /** The graph of the tags, the pairs told apart and the merges: what the user said a tag is to another. */
    val relations = TagRelationRepository(db)

    /** The entries of the catalogues the tags are bound to, and what those entries are a kind of. */
    val entries = TagEntryRepository(db)

    /** The names the tags are shown by and their works as last counted. */
    val stats = TagStatRepository(db)

    /** The vectors of the tags' names. */
    val vectors = TagVectorRepository(db)

    /**
     * The writes of one action of the user on the tags — every one of them, or, failed, none: the repository [block] is
     * given writes in one transaction of the database of the user's word. A write of a repository's own transaction
     * inside it (a merge) is a part of it.
     */
    fun <T> transaction(block: (TagRepository) -> T): T = db.transactionResult { c ->
        block(TagRepository(DSL.using(c)))
    }

    // --- The normalized tags and the sites' tags bound to them ---

    /** Every site's tag and the normalized tag it is bound to. */
    fun bindings(): Map<SiteTagRef, Long> =
        db.select(SITE_TAG.SOURCE, SITE_TAG.TAG, SITE_TAG.NORMALIZED).from(SITE_TAG).fetch().associate {
            SiteTagRef(it.value1()!!, it.value2()!!) to it.value3()!!
        }

    /** The site's tags of one [source] and the normalized tags they are bound to: site's key to id. */
    fun bindingsOf(source: String): Map<String, Long> =
        db.select(SITE_TAG.TAG, SITE_TAG.NORMALIZED)
            .from(SITE_TAG)
            .where(SITE_TAG.SOURCE.eq(source))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** A new normalized tag, named by the user when [name] is given; its id. */
    fun create(name: String? = null): Long =
        db.insertInto(NORMALIZED_TAG)
            .set(NORMALIZED_TAG.NAME, name?.take(NAME_WIDTH))
            .returningResult(NORMALIZED_TAG.ID)
            .fetchOne()!!
            .value1()!!

    fun exists(id: Long): Boolean = db.fetchExists(NORMALIZED_TAG, NORMALIZED_TAG.ID.eq(id))

    /** Every normalized tag. */
    fun ids(): Set<Long> = db.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).fetchSet { it.value1()!! }

    /** [site] bound to the normalized tag [id], whatever it was bound to before. */
    fun bind(site: SiteTagRef, id: Long, now: Instant) {
        // The names of the tag it leaves and of the one it joins are others now: their vectors made anew
        val was =
            db.select(SITE_TAG.NORMALIZED)
                .from(SITE_TAG)
                .where(SITE_TAG.SOURCE.eq(site.source), SITE_TAG.TAG.eq(site.tag.take(SITE_TAG_WIDTH)))
                .fetchOne()
                ?.value1()
        vectors.forgetVectors(listOfNotNull(was, id))
        db.insertInto(SITE_TAG)
            .set(SITE_TAG.SOURCE, site.source)
            .set(SITE_TAG.TAG, site.tag.take(SITE_TAG_WIDTH))
            .set(SITE_TAG.NORMALIZED, id)
            .set(SITE_TAG.BOUND_AT, now)
            .onDuplicateKeyUpdate()
            .set(SITE_TAG.NORMALIZED, id)
            .set(SITE_TAG.BOUND_AT, now)
            .execute()
    }

    // --- The user's word on one tag ---

    /** The name the user gives the normalized tag [id]; null takes it back. */
    fun name(id: Long, name: String?) {
        db.update(NORMALIZED_TAG)
            .set(NORMALIZED_TAG.NAME, name?.take(NAME_WIDTH))
            .where(NORMALIZED_TAG.ID.eq(id))
            .execute()
    }

    /** Whether the user ignores the tag [id]: kept with its works, not read by the model of the grades. */
    fun ignore(id: Long, ignored: Boolean) {
        db.update(NORMALIZED_TAG).set(NORMALIZED_TAG.IGNORED, ignored).where(NORMALIZED_TAG.ID.eq(id)).execute()
    }

    /** The keys of the tags the user ignores. */
    fun ignored(): Set<String> =
        db.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).where(NORMALIZED_TAG.IGNORED.isTrue).fetchSet {
            it.value1()!!.toString()
        }

    companion object {
        /** A normalized tag as a value of the works' tags: its id written out. */
        fun key(id: Long): String = id.toString()

        /** The id of a value of the works' tags; null for one that is no normalized tag. */
        fun idOf(key: String): Long? = key.toLongOrNull()

        /** A pair of tags, the smaller id first. */
        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
