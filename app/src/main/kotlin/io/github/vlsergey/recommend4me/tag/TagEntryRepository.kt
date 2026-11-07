package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY_ABOVE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY_TEXT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY_TOO_GENERAL
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_VECTOR
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** An entry of a catalogue a tag is bound to: "wikidata", "Q305561". */
data class TagEntryRef(val catalogue: String, val entry: String)

/**
 * THE ENTRIES OF THE CATALOGUES THE TAGS ARE BOUND TO, in the user's dictionaries ([db]): every binding of a tag to an
 * entry, the entries as their catalogues gave them, what each is a kind of, and the entries the user said are too
 * general to be a tag above others.
 */
class TagEntryRepository(private val db: DSLContext) {

    /** Every tag bound to entries of the catalogues: tag id to its entries, the one bound first first. */
    fun entriesOfTags(): Map<Long, List<TagEntryRef>> {
        val e = NORMALIZED_TAG_ENTRY
        return db.select(e.TAG, e.CATALOGUE, e.ENTRY)
            .from(e)
            .orderBy(e.BOUND_AT, e.CATALOGUE, e.ENTRY)
            .fetch()
            .groupBy({ it.value1()!! }, { TagEntryRef(it.value2()!!, it.value3()!!) })
    }

    /** The tag [id] bound to the entry [ref] besides the ones it is — or, [bound] false, no more to it. */
    fun bindEntry(id: Long, ref: TagEntryRef, bound: Boolean, now: Instant) {
        val e = NORMALIZED_TAG_ENTRY
        // The entry's names are the tag's, or no more
        TagVectorRepository(db).forgetVectors(listOf(id))
        if (bound) {
            db.insertInto(e)
                .set(e.TAG, id)
                .set(e.CATALOGUE, ref.catalogue)
                .set(e.ENTRY, ref.entry)
                .set(e.BOUND_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        } else {
            db.deleteFrom(e).where(e.TAG.eq(id), e.CATALOGUE.eq(ref.catalogue), e.ENTRY.eq(ref.entry)).execute()
        }
    }

    /** The entry of [catalogue] as the catalogue gave it now: its texts in place of those kept. */
    fun keepEntry(catalogue: String, entry: TagEntry, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.insertInto(TAG_ENTRY)
            .set(TAG_ENTRY.CATALOGUE, catalogue)
            .set(TAG_ENTRY.ENTRY, entry.id)
            .set(TAG_ENTRY.URL, entry.url.take(URL_WIDTH))
            .set(TAG_ENTRY.FETCHED_AT, now)
            .onDuplicateKeyUpdate()
            .set(TAG_ENTRY.URL, entry.url.take(URL_WIDTH))
            .set(TAG_ENTRY.FETCHED_AT, now)
            .execute()
        val t = TAG_ENTRY_TEXT
        tx.deleteFrom(t).where(t.CATALOGUE.eq(catalogue), t.ENTRY.eq(entry.id)).execute()
        fun put(language: String, kind: String, texts: List<String>) = texts.forEachIndexed { i, text ->
            tx.insertInto(t)
                .set(t.CATALOGUE, catalogue)
                .set(t.ENTRY, entry.id)
                .set(t.LANGUAGE, language)
                .set(t.KIND, kind)
                .set(t.POSITION, i)
                .set(t.TEXT, text.take(TEXT_WIDTH))
                .execute()
        }
        entry.labels.forEach { (language, text) -> put(language, LABEL, listOf(text)) }
        entry.aliases.forEach { (language, texts) -> put(language, ALIAS, texts) }
        entry.descriptions.forEach { (language, text) -> put(language, DESCRIPTION, listOf(text)) }
        // The names of the tags bound to it may be others now
        val e = NORMALIZED_TAG_ENTRY
        tx.deleteFrom(TAG_VECTOR)
            .where(
                TAG_VECTOR.TAG.`in`(DSL.select(e.TAG).from(e).where(e.CATALOGUE.eq(catalogue), e.ENTRY.eq(entry.id)))
            )
            .execute()
    }

    /** The entries kept — of [refs] alone, when given — with their texts: by reference. */
    fun keptEntries(refs: Collection<TagEntryRef>? = null): Map<TagEntryRef, TagEntry> {
        if (refs != null && refs.isEmpty()) return emptyMap()
        val wanted = refs?.toSet()
        val urls =
            db.select(TAG_ENTRY.CATALOGUE, TAG_ENTRY.ENTRY, TAG_ENTRY.URL)
                .from(TAG_ENTRY)
                .fetch()
                .map { TagEntryRef(it.value1()!!, it.value2()!!) to it.value3()!! }
                .filter { wanted == null || it.first in wanted }
                .toMap()
        val t = TAG_ENTRY_TEXT
        val texts =
            db.select(t.CATALOGUE, t.ENTRY, t.LANGUAGE, t.KIND, t.TEXT).from(t).orderBy(t.POSITION).fetch().groupBy {
                TagEntryRef(it.value1()!!, it.value2()!!)
            }
        return urls.mapValues { (ref, url) ->
            val own = texts[ref].orEmpty()
            fun of(kind: String) = own.filter { it.value4() == kind }.groupBy({ it.value3()!! }, { it.value5()!! })
            TagEntry(
                ref.entry,
                of(LABEL).mapValues { it.value.first() },
                of(ALIAS),
                of(DESCRIPTION).mapValues { it.value.first() },
                url,
            )
        }
    }

    // --- What the entries are a kind of ---

    /** What every entry read is a kind of: the entry to the ones right above it. */
    fun aboveLinks(): Map<TagEntryRef, List<TagEntryRef>> {
        val a = TAG_ENTRY_ABOVE
        return db.select(a.CATALOGUE, a.ENTRY, a.ABOVE)
            .from(a)
            .fetch()
            .groupBy({ TagEntryRef(it.value1()!!, it.value2()!!) }, { TagEntryRef(it.value1()!!, it.value3()!!) })
    }

    /** Of [refs], the entries whose kinds were read: the walk up goes on from what is kept of them. */
    fun aboveRead(refs: Collection<TagEntryRef>): Set<TagEntryRef> {
        if (refs.isEmpty()) return emptySet()
        val wanted = refs.toSet()
        return db.select(TAG_ENTRY.CATALOGUE, TAG_ENTRY.ENTRY)
            .from(TAG_ENTRY)
            .where(TAG_ENTRY.ABOVE_READ_AT.isNotNull)
            .fetch()
            .map { TagEntryRef(it.value1()!!, it.value2()!!) }
            .filter { it in wanted }
            .toSet()
    }

    /** What the kept entry [ref] is a kind of, as its catalogue says now: [above] in place of what was kept. */
    fun keepAbove(ref: TagEntryRef, above: List<String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        val a = TAG_ENTRY_ABOVE
        tx.deleteFrom(a).where(a.CATALOGUE.eq(ref.catalogue), a.ENTRY.eq(ref.entry)).execute()
        above.distinct().forEach {
            tx.insertInto(a).set(a.CATALOGUE, ref.catalogue).set(a.ENTRY, ref.entry).set(a.ABOVE, it).execute()
        }
        tx.update(TAG_ENTRY)
            .set(TAG_ENTRY.ABOVE_READ_AT, now)
            .where(TAG_ENTRY.CATALOGUE.eq(ref.catalogue), TAG_ENTRY.ENTRY.eq(ref.entry))
            .execute()
    }

    /** The entries the user said are too general to be a tag above others. */
    fun tooGeneral(): Set<TagEntryRef> {
        val g = TAG_ENTRY_TOO_GENERAL
        return db.select(g.CATALOGUE, g.ENTRY).from(g).fetch { TagEntryRef(it.value1()!!, it.value2()!!) }.toSet()
    }

    /**
     * The user's word that [entry] is too general to be a tag above others — or, [tooGeneral] false, its taking back.
     */
    fun setTooGeneral(entry: TagEntryRef, tooGeneral: Boolean, now: Instant) {
        val g = TAG_ENTRY_TOO_GENERAL
        if (tooGeneral)
            db.insertInto(g)
                .set(g.CATALOGUE, entry.catalogue)
                .set(g.ENTRY, entry.entry)
                .set(g.SAID_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        else db.deleteFrom(g).where(g.CATALOGUE.eq(entry.catalogue), g.ENTRY.eq(entry.entry)).execute()
    }

    private companion object {
        /** The widest address of an entry's page kept: the width of its column. */
        const val URL_WIDTH = 1000

        /** The widest text of an entry kept — a label, an alias, a description: the width of its column. */
        const val TEXT_WIDTH = 2000

        const val LABEL = "L"
        const val ALIAS = "A"
        const val DESCRIPTION = "D"
    }
}
