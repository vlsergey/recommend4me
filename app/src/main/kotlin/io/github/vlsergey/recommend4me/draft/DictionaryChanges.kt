package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.DICTIONARY_CHANGE
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** What a dictionary the drafts are made by holds: the universes, or the tags. */
enum class DictionaryKind {
    UNIVERSE,
    TAG,
}

/**
 * A change of a dictionary: of a value of the layer ([source] null) — a universe or a tag — or of a site's own value of
 * the [source] bound anew ([key] its key on the site).
 */
data class DictionaryChange(val kind: DictionaryKind, val source: String?, val key: String)

/**
 * THE CHANGES OF THE DICTIONARIES THE WORKS ARE DRAFTED BY, AS THEY ARE WRITTEN (2026-11-23): every write that changes
 * what a value means to its works names it here, dated — a universe or a tag bound, merged, put under another or taken
 * from under it, its entry's statements or characters read anew; a site's fandom or tag bound anew — and nothing of the
 * works. The index of the list writes anew the documents of the works having them, from where it stands among these
 * ([io.github.vlsergey.recommend4me.worklist.ListIndexing]); a change of a dictionary told as every work changed made
 * it take every item from the first at every merge, and the pass of three hours never ended.
 */
class DictionaryChanges(private val db: DSLContext) {

    /** The [changes] written, dated [at]. */
    fun changed(changes: Collection<DictionaryChange>, at: Instant) {
        if (changes.isEmpty()) return
        val c = DICTIONARY_CHANGE
        db.batch(
                changes.distinct().map { ch ->
                    db.insertInto(c)
                        .set(c.KIND, ch.kind.name)
                        .set(c.SOURCE, ch.source)
                        .set(c.VALUE_KEY, ch.key.take(KEY_WIDTH))
                        .set(c.CHANGED_AT, at)
                }
            )
            .execute()
    }

    /** The values of the layer of the [kind] [keys] changed [at]. */
    fun values(kind: DictionaryKind, keys: Collection<String>, at: Instant) =
        changed(keys.map { DictionaryChange(kind, null, it) }, at)

    /** The changes written after the change [after] up to the change [upTo], each once. */
    fun between(after: Long, upTo: Long): Set<DictionaryChange> {
        val c = DICTIONARY_CHANGE
        return db.select(c.KIND, c.SOURCE, c.VALUE_KEY)
            .from(c)
            .where(c.ID.gt(after), c.ID.le(upTo))
            .fetch { DictionaryChange(DictionaryKind.valueOf(it.value1()!!), it.value2(), it.value3()!!) }
            .toSet()
    }

    /** The last change written; nought of none. */
    fun last(): Long = db.select(DSL.max(DICTIONARY_CHANGE.ID)).from(DICTIONARY_CHANGE).fetchOne()?.value1() ?: 0L

    /** Whether anything changed after the change [after]. */
    fun any(after: Long): Boolean = db.fetchExists(DICTIONARY_CHANGE, DICTIONARY_CHANGE.ID.gt(after))

    private companion object {
        /** The width of the column of a value's key (VARCHAR(500)): a longer one is cut. */
        const val KEY_WIDTH = 500
    }
}
