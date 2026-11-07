package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_DISTINCT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_MERGE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_PARENT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_OFFERS
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * WHAT THE USER SAID A TAG IS TO ANOTHER, in the user's dictionaries ([db]): one merged into another, one under another
 * — the graph of the tags — two told apart; and the offers of the graph as last worked out.
 */
class TagRelationRepository(private val db: DSLContext) {

    /**
     * The normalized tag [from] merged into [into]: every site's tag of it bound to [into], its links of the graph, its
     * pairs told apart and its entries of the catalogues moved to [into] — none of [into] with itself — and [from]
     * gone. The user's answers on it are in the sources' databases: the merge is kept as one whose answers are still to
     * be moved ([pendingMerges]) till the caller has moved them ([answersMoved]).
     */
    fun merge(from: Long, into: Long, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        val m = NORMALIZED_TAG_MERGE
        tx.insertInto(m)
            .set(m.FROM_TAG, from)
            .set(m.INTO_TAG, into)
            .set(m.MERGED_AT, now)
            .onDuplicateKeyUpdate()
            .set(m.INTO_TAG, into)
            .set(m.MERGED_AT, now)
            .execute()
        tx.update(SITE_TAG)
            .set(SITE_TAG.NORMALIZED, into)
            .set(SITE_TAG.BOUND_AT, now)
            .where(SITE_TAG.NORMALIZED.eq(from))
            .execute()
        // [into] has the names of both now
        TagVectorRepository(tx).forgetVectors(listOf(into))
        val links = linksMoved(tx, from, into)
        val told = pairsMoved(tx, from, into)
        // Its entries of the catalogues go to [into] besides its own, bound when they were: the user's word kept
        entriesMoved(tx, from, into)
        // [from]'s links and pairs go with it, as it goes; the ones moved to [into] come back
        tx.deleteFrom(NORMALIZED_TAG).where(NORMALIZED_TAG.ID.eq(from)).execute()
        val moved = TagRelationRepository(tx)
        links
            .filter { (child, parent) -> child != parent }
            .distinct()
            .forEach { (child, parent) -> moved.setParent(child, parent, true, now) }
        told.filter { (a, b) -> a != b }.distinct().forEach { (a, b) -> moved.setDistinct(a, b, true, now) }
    }

    /** The merges whose answers are still to be moved, the earliest first: tag merged, tag kept. */
    fun pendingMerges(): List<Pair<Long, Long>> {
        val m = NORMALIZED_TAG_MERGE
        return db.select(m.FROM_TAG, m.INTO_TAG).from(m).orderBy(m.MERGED_AT).fetch { it.value1()!! to it.value2()!! }
    }

    /** The answers on the tag [from] merged are moved in every source: its merge done. */
    fun answersMoved(from: Long) {
        db.deleteFrom(NORMALIZED_TAG_MERGE).where(NORMALIZED_TAG_MERGE.FROM_TAG.eq(from)).execute()
    }

    // --- The graph and the pairs told apart ---

    /** Every tag's parents: child to its parents, by key. */
    fun parents(): Map<String, Set<String>> =
        db.select(NORMALIZED_TAG_PARENT.CHILD, NORMALIZED_TAG_PARENT.PARENT)
            .from(NORMALIZED_TAG_PARENT)
            .fetch()
            .groupBy({ TagRepository.key(it.value1()!!) }, { TagRepository.key(it.value2()!!) })
            .mapValues { it.value.toSet() }

    /** [child] put under [parent] — or, [linked] false, taken from under it. */
    fun setParent(child: Long, parent: Long, linked: Boolean, now: Instant) {
        val p = NORMALIZED_TAG_PARENT
        if (linked)
            db.insertInto(p)
                .set(p.CHILD, child)
                .set(p.PARENT, parent)
                .set(p.LINKED_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        else db.deleteFrom(p).where(p.CHILD.eq(child), p.PARENT.eq(parent)).execute()
    }

    /** Every pair of tags the user said are different, the smaller id first. */
    fun distinct(): Set<Pair<Long, Long>> =
        db.select(NORMALIZED_TAG_DISTINCT.FIRST_TAG, NORMALIZED_TAG_DISTINCT.OTHER_TAG)
            .from(NORMALIZED_TAG_DISTINCT)
            .fetch { it.value1()!! to it.value2()!! }
            .toSet()

    /** The user's word that [a] and [b] are different tags — or, [different] false, its taking back. */
    fun setDistinct(a: Long, b: Long, different: Boolean, now: Instant) {
        val (x, y) = TagRepository.pair(a, b)
        val d = NORMALIZED_TAG_DISTINCT
        if (different)
            db.insertInto(d)
                .set(d.FIRST_TAG, x)
                .set(d.OTHER_TAG, y)
                .set(d.SAID_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        else db.deleteFrom(d).where(d.FIRST_TAG.eq(x), d.OTHER_TAG.eq(y)).execute()
    }

    // --- The offers of the graph ---

    /** The offers of the tags' graph as last worked out, as JSON; null before the first. */
    fun offers(): String? =
        db.select(TAG_OFFERS.OFFERS).from(TAG_OFFERS).where(TAG_OFFERS.ID.eq(OFFERS_ROW)).fetchOne(TAG_OFFERS.OFFERS)

    /** Keeps the offers of the tags' graph worked out [now], as JSON, over those before. */
    fun keepOffers(offers: String, now: Instant) {
        db.insertInto(TAG_OFFERS)
            .set(TAG_OFFERS.ID, OFFERS_ROW)
            .set(TAG_OFFERS.MADE_AT, now)
            .set(TAG_OFFERS.OFFERS, offers)
            .onDuplicateKeyUpdate()
            .set(TAG_OFFERS.MADE_AT, now)
            .set(TAG_OFFERS.OFFERS, offers)
            .execute()
    }

    private companion object {
        /** The one row of the offers of the tags' graph. */
        const val OFFERS_ROW = 1
    }
}

/** The links of the graph of the tag [from], read of [tx] as they will be of [into]: child to parent. */
private fun linksMoved(tx: DSLContext, from: Long, into: Long): List<Pair<Long, Long>> {
    val p = NORMALIZED_TAG_PARENT
    return tx.selectFrom(p).where(p.CHILD.eq(from).or(p.PARENT.eq(from))).fetch().map {
        (if (it.child == from) into else it.child!!) to (if (it.parent == from) into else it.parent!!)
    }
}

/** The pairs told apart of the tag [from], read of [tx] as they will be of [into]. */
private fun pairsMoved(tx: DSLContext, from: Long, into: Long): List<Pair<Long, Long>> {
    val d = NORMALIZED_TAG_DISTINCT
    return tx.selectFrom(d).where(d.FIRST_TAG.eq(from).or(d.OTHER_TAG.eq(from))).fetch().map {
        TagRepository.pair(
            if (it.firstTag == from) into else it.firstTag!!,
            if (it.otherTag == from) into else it.otherTag!!,
        )
    }
}

/** The entries of the catalogues [from] is bound to bound to [into] besides its own, when they were. */
private fun entriesMoved(tx: DSLContext, from: Long, into: Long) {
    val e = NORMALIZED_TAG_ENTRY
    tx.selectFrom(e).where(e.TAG.eq(from)).fetch().forEach {
        tx.insertInto(e)
            .set(e.TAG, into)
            .set(e.CATALOGUE, it.catalogue)
            .set(e.ENTRY, it.entry)
            .set(e.BOUND_AT, it.boundAt)
            .onDuplicateKeyIgnore()
            .execute()
    }
}
