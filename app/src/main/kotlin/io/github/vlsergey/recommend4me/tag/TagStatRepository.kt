package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_STAT
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(TagStatRepository::class.java)

/** Which rule gave a tag its name ([TagNaming]). */
enum class NameRule {
    EXPLICIT,
    CATALOGUE,
    LIKED,
    GRADED,
    ALL,
    ALPHABET,
}

/** A tag's name worked out, with the rule that gave it. */
data class ShownName(val name: String, val rule: NameRule)

/** How many works have a tag, graded and liked among them. */
data class Counts(val works: Int, val graded: Int, val liked: Int)

/**
 * A site's tag bound to the tag [normalized]: its name on the site and its works as last counted; no name — no work has
 * it now.
 */
data class SiteTagCount(val site: SiteTagRef, val normalized: Long, val name: String?, val counts: Counts)

/** A tag's works as last counted, and the sources whose site's tags are bound to it. */
data class TagCount(val counts: Counts, val sources: List<String>)

/**
 * WHAT IS SHOWN OF THE TAGS, in the user's dictionaries ([db]): the names the user gave them and the names the
 * application worked out of the sites', and the works of every tag and of every site's tag as last counted — made again
 * in the background from the rest.
 */
class TagStatRepository(private val db: DSLContext) {

    /** The names the user gave, by id. */
    fun explicitNames(): Map<Long, String> =
        db.select(NORMALIZED_TAG.ID, NORMALIZED_TAG.NAME)
            .from(NORMALIZED_TAG)
            .where(NORMALIZED_TAG.NAME.isNotNull)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /**
     * What the tags of [keys] are shown by — the user's name, else the one worked out last — of every tag when null; by
     * key.
     */
    fun shown(keys: Collection<String>?): Map<String, String> {
        val ids = keys?.mapNotNull(TagRepository::idOf)
        if (ids != null && ids.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        fun worked(only: List<Long>?) =
            db.select(TAG_STAT.TAG, TAG_STAT.CALCULATED_NAME)
                .from(TAG_STAT)
                .where(TAG_STAT.CALCULATED_NAME.isNotNull, only?.let { TAG_STAT.TAG.`in`(it) } ?: DSL.noCondition())
                .fetch()
        fun given(only: List<Long>?) =
            db.select(NORMALIZED_TAG.ID, NORMALIZED_TAG.NAME)
                .from(NORMALIZED_TAG)
                .where(NORMALIZED_TAG.NAME.isNotNull, only?.let { NORMALIZED_TAG.ID.`in`(it) } ?: DSL.noCondition())
                .fetch()
        val chunks = ids?.distinct()?.chunked(BATCH) ?: listOf(null)
        chunks.forEach { chunk -> worked(chunk).forEach { out[TagRepository.key(it.value1()!!)] = it.value2()!! } }
        chunks.forEach { chunk -> given(chunk).forEach { out[TagRepository.key(it.value1()!!)] = it.value2()!! } }
        return out
    }

    /** The name a new tag is shown by until the names are worked out again: of the site's tag it is made for. */
    fun showAs(id: Long, name: ShownName) {
        db.insertInto(TAG_STAT)
            .set(TAG_STAT.TAG, id)
            .set(TAG_STAT.CALCULATED_NAME, name.name.take(NAME_WIDTH))
            .set(TAG_STAT.CALCULATED_RULE, name.rule.name)
            .onDuplicateKeyIgnore()
            .execute()
    }

    /**
     * The names the application works the tags out to replaced by [named], kept with their statistics: the label of an
     * entry, else of the sites' names; the user's own name is no part of them.
     */
    fun replaceCalculated(named: Map<Long, ShownName>) = againIfGone {
        db.transaction { c ->
            val tx = DSL.using(c)
            val alive = alive(tx)
            // Only what changed: a name or its rule changed, a tag new — not the whole table every few seconds
            val stored =
                tx.select(TAG_STAT.TAG, TAG_STAT.CALCULATED_NAME, TAG_STAT.CALCULATED_RULE)
                    .from(TAG_STAT)
                    .fetch()
                    .associate { it.value1()!! to (it.value2() to it.value3()) }
            named.entries
                .filter { (id, s) -> id in alive && stored[id] != (s.name.take(NAME_WIDTH) to s.rule.name) }
                .chunked(BATCH)
                .forEach { chunk ->
                    // One statement prepared and bound a row each; a tag's other columns kept as they are
                    val batch =
                        tx.batch(
                            tx.mergeInto(TAG_STAT, TAG_STAT.TAG, TAG_STAT.CALCULATED_NAME, TAG_STAT.CALCULATED_RULE)
                                .key(TAG_STAT.TAG)
                                .values(null as Long?, null as String?, null as String?)
                        )
                    chunk.forEach { (id, s) -> batch.bind(id, s.name.take(NAME_WIDTH), s.rule.name) }
                    batch.execute()
                }
        }
    }

    /** Every site's tag bound to a tag, with its name and its works as last counted ([keepStats]). */
    fun siteTags(): List<SiteTagCount> =
        db.select(
                SITE_TAG.SOURCE,
                SITE_TAG.TAG,
                SITE_TAG.NORMALIZED,
                SITE_TAG.NAME,
                SITE_TAG.WORKS,
                SITE_TAG.GRADED,
                SITE_TAG.LIKED,
            )
            .from(SITE_TAG)
            .fetch {
                SiteTagCount(
                    SiteTagRef(it.value1()!!, it.value2()!!),
                    it.value3()!!,
                    it.value4(),
                    Counts(it.value5()!!, it.value6()!!, it.value7()!!),
                )
            }

    /** Every tag's works and the sources of its sites' tags, as last counted ([keepStats]). */
    fun counts(): Map<Long, TagCount> =
        db.select(TAG_STAT.TAG, TAG_STAT.WORKS, TAG_STAT.GRADED, TAG_STAT.LIKED, TAG_STAT.SOURCES)
            .from(TAG_STAT)
            .fetchMap(
                { it.value1()!! },
                { TagCount(Counts(it.value2()!!, it.value3()!!, it.value4()!!), it.value5()!!.filterNotNull()) },
            )

    /**
     * The statistics counted now in place of those kept, the rows that changed alone: of every site's tag counted its
     * name and works — of one not counted, no name and none — and of every tag its works and the sources of its sites'
     * tags.
     */
    fun keepStats(sites: Map<SiteTagRef, Pair<String, Counts>>, tags: Map<Long, TagCount>) = againIfGone {
        db.transaction { c ->
            val tx = DSL.using(c)
            val st = SITE_TAG
            tx.select(st.SOURCE, st.TAG, st.NAME, st.WORKS, st.GRADED, st.LIKED).from(st).fetch().forEach { r ->
                val site = SiteTagRef(r.value1()!!, r.value2()!!)
                val (name, counts) = sites[site] ?: (null to Counts(0, 0, 0))
                if (r.value3() == name && Counts(r.value4()!!, r.value5()!!, r.value6()!!) == counts) return@forEach
                tx.update(st)
                    .set(st.NAME, name?.take(SITE_TAG_WIDTH))
                    .set(st.WORKS, counts.works)
                    .set(st.GRADED, counts.graded)
                    .set(st.LIKED, counts.liked)
                    .where(st.SOURCE.eq(site.source), st.TAG.eq(site.tag))
                    .execute()
            }
            val alive = alive(tx)
            val kept = TagStatRepository(tx).counts()
            val ts = TAG_STAT
            tags.forEach { (tag, count) ->
                if (tag !in alive || kept[tag] == count) return@forEach
                val sources = count.sources.toTypedArray<String?>()
                tx.insertInto(ts)
                    .set(ts.TAG, tag)
                    .set(ts.WORKS, count.counts.works)
                    .set(ts.GRADED, count.counts.graded)
                    .set(ts.LIKED, count.counts.liked)
                    .set(ts.SOURCES, sources)
                    .onDuplicateKeyUpdate()
                    .set(ts.WORKS, count.counts.works)
                    .set(ts.GRADED, count.counts.graded)
                    .set(ts.LIKED, count.counts.liked)
                    .set(ts.SOURCES, sources)
                    .execute()
            }
        }
    }

    /**
     * THE TAGS THAT STILL ARE: a pass of the background counts the works for seconds, and a tag merged or taken out
     * meanwhile is gone — its statistics are not written.
     */
    private fun alive(tx: DSLContext): Set<Long> =
        tx.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).fetchSet(NORMALIZED_TAG.ID).filterNotNull().toSet()

    /**
     * [write] done again once when a tag it writes of was taken out between its reading the tags that are ([alive]) and
     * its write — milliseconds; not locked, so a merge does not wait on it.
     */
    private fun <T> againIfGone(write: () -> T): T =
        try {
            write()
        } catch (e: IntegrityConstraintViolationException) {
            log.debug("A tag was taken out while the tags were written: written again", e)
            write()
        }

    private companion object {
        /** The tags read or written by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 1000
    }
}
