package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_TEXT
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VECTOR
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE TEXTS AND THE VECTORS THE INDEX OF THE LIST HOLDS ([ListTexts]), read of it by the names of their fields
 * ([ListTexts.textField]: a text's UTF-8, [ListTexts.vectorField]: a vector as packed): of every item of a source in
 * one pass of each table, or of the items asked for by their keys.
 */
object HeldTexts {

    /**
     * Of every item of the [source] the index holds, the value of each of the [fields] it has, by the field's place
     * among them, as committed — handed on at once.
     */
    fun forEvery(index: ListIndex, source: String, fields: List<String>, action: (String, Int, ByteArray) -> Unit) =
        index.searching { db ->
            val prefix = ItemKey(source, "").toString()
            read(db, fields, { key -> key.startsWith(prefix) }) { key, k, value ->
                action(key.substring(prefix.length), k, value)
            }
        }

    /** Of the items [keys], the value of each of the doc values [fields] they have, by the field: as committed. */
    fun of(index: ListIndex, keys: Collection<ItemKey>, fields: List<String>): Map<ItemKey, Map<String, ByteArray>> =
        index.searching {
            of(it, keys, fields)
        }

    /** Of the items [keys], the value of each of the [fields] they have, by the field, as the [db] has it. */
    fun of(db: DSLContext, keys: Collection<ItemKey>, fields: List<String>): Map<ItemKey, Map<String, ByteArray>> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, HashMap<String, ByteArray>>()
        keys.distinct().chunked(ListIndex.BATCH).forEach { chunk ->
            val asked = chunk.map { it.toString() }
            read(db, fields, { it.`in`(asked) }) { key, k, value ->
                out.getOrPut(ItemKey.parse(key)) { HashMap() }[fields[k]] = value
            }
        }
        return out
    }

    /**
     * Of the items the [items] condition picks of the column of their keys, the value of each of the [fields], with its
     * item's key and the field's place.
     */
    private fun read(
        db: DSLContext,
        fields: List<String>,
        items: (org.jooq.Field<String?>) -> Condition,
        action: (String, Int, ByteArray) -> Unit,
    ) {
        val texts = fields.withIndex().mapNotNull { (k, f) -> ListTexts.textKeyOf(f)?.let { it to k } }.toMap()
        val vectors = fields.withIndex().mapNotNull { (k, f) -> ListTexts.vectorOf(f)?.let { it to k } }.toMap()
        if (texts.isNotEmpty()) {
            val t = ITEM_TEXT
            db.select(t.ITEM_KEY, t.TEXT_KEY, t.CONTENT)
                .from(t)
                .where(items(t.ITEM_KEY), t.TEXT_KEY.`in`(texts.keys))
                .fetchSize(STREAMED)
                .fetchLazy()
                .use { cursor ->
                    cursor.forEach { r ->
                        action(r.value1()!!, texts.getValue(r.value2()!!), r.value3()!!.toByteArray())
                    }
                }
        }
        if (vectors.isNotEmpty()) {
            val v = ITEM_VECTOR
            val asked = DSL.or(vectors.keys.map { (encoder, key) -> v.ENCODER.eq(encoder).and(v.TEXT_KEY.eq(key)) })
            db.select(v.ITEM_KEY, v.ENCODER, v.TEXT_KEY, v.VEC)
                .from(v)
                .where(items(v.ITEM_KEY), asked)
                .fetchSize(STREAMED)
                .fetchLazy()
                .use { cursor ->
                    cursor.forEach { r ->
                        action(r.value1()!!, vectors.getValue(r.value2()!! to r.value3()!!), r.value4()!!)
                    }
                }
        }
    }

    /** The rows streamed at a time: memory, not meaning. */
    private const val STREAMED = 10_000
}
