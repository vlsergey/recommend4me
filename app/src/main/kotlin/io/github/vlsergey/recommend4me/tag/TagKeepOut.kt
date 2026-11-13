package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_TAG
import org.jooq.DSLContext

/**
 * THE TAGS THAT KEEP THEIR WORKS OUT OF THE LIBRARY, in the user's dictionaries ([db]): a work a site tags with one, or
 * with a tag under one, is not kept ([KeptOutWorks]).
 */
class TagKeepOut(private val db: DSLContext) {

    /** Whether the tag [id] keeps its works out of the library. */
    fun set(id: Long, keeps: Boolean) {
        db.update(NORMALIZED_TAG).set(NORMALIZED_TAG.KEEPS_OUT, keeps).where(NORMALIZED_TAG.ID.eq(id)).execute()
    }

    /** The keys of the tags that keep their works out. */
    fun tags(): Set<String> =
        db.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).where(NORMALIZED_TAG.KEEPS_OUT.isTrue).fetchSet {
            TagRepository.key(it.value1()!!)
        }

    /** The site's tags of the [source] bound to any of the normalized tags [keys]: the site's keys. */
    fun siteTags(source: String, keys: Collection<String>): Set<String> {
        val ids = keys.mapNotNull(TagRepository::idOf)
        return if (ids.isEmpty()) emptySet()
        else
            db.select(SITE_TAG.TAG)
                .from(SITE_TAG)
                .where(SITE_TAG.SOURCE.eq(source), SITE_TAG.NORMALIZED.`in`(ids))
                .fetchSet { it.value1()!! }
    }
}
