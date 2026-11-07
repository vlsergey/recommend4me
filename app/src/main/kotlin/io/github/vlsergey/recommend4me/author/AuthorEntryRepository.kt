package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_AUTHOR_ENTRY
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import java.time.Instant
import org.jooq.DSLContext

/** The entries of the catalogues of tags (Wikidata) the normalized authors are bound to, in the dictionaries. */
class AuthorEntryRepository(private val db: DSLContext) {

    /** Every author bound to entries of the catalogues: their id to the entries, the one bound first first. */
    fun all(): Map<Long, List<TagEntryRef>> {
        val e = NORMALIZED_AUTHOR_ENTRY
        return db.select(e.AUTHOR, e.CATALOGUE, e.ENTRY)
            .from(e)
            .orderBy(e.BOUND_AT, e.CATALOGUE, e.ENTRY)
            .fetch()
            .groupBy({ it.value1()!! }, { TagEntryRef(it.value2()!!, it.value3()!!) })
    }

    /** The author [id] bound to the entry [ref] besides the ones they are — or, [bound] false, no more to it. */
    fun bind(id: Long, ref: TagEntryRef, bound: Boolean, now: Instant) {
        val e = NORMALIZED_AUTHOR_ENTRY
        if (bound)
            db.insertInto(e)
                .set(e.AUTHOR, id)
                .set(e.CATALOGUE, ref.catalogue)
                .set(e.ENTRY, ref.entry)
                .set(e.BOUND_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        else db.deleteFrom(e).where(e.AUTHOR.eq(id), e.CATALOGUE.eq(ref.catalogue), e.ENTRY.eq(ref.entry)).execute()
    }
}
