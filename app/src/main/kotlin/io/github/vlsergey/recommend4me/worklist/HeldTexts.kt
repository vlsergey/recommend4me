package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_TEXT
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VECTOR
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType.BLOB

/**
 * THE TEXTS AND THE VECTORS THE INDEX OF THE LIST HOLDS ([ListTexts]), read of it by the names of their fields
 * ([ListTexts.textField]: a text's UTF-8, [ListTexts.vectorField]: a vector as packed): of every item of a source in
 * one pass of each table, or of the items asked for by their keys. The rows read of the JDBC result itself, no record
 * of jOOQ a row ([EveryItem]).
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
        ListAmong.of(db, keys.map { it.toString() }) {
            read(db, fields, ListAmong::keyIn) { key, k, value ->
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
            // The text as its UTF-8, made by the database: not a string the driver makes, turned back into bytes
            db.select(
                    t.ITEM_KEY,
                    DSL.case_(t.TEXT_KEY).mapValues(HashMap<String?, Int>(texts)),
                    DSL.function("encode", BLOB, t.CONTENT),
                )
                .from(t)
                .where(items(t.ITEM_KEY), t.TEXT_KEY.`in`(texts.keys))
                .fetchSize(STREAMED)
                .fetchResultSet()
                .use { rs -> while (rs.next()) action(rs.getString(1), rs.getInt(2), rs.getBytes(CONTENT_COLUMN)) }
        }
        if (vectors.isNotEmpty()) {
            val v = ITEM_VECTOR
            val asked = vectors.mapKeys { (key, _) -> v.ENCODER.eq(key.first).and(v.TEXT_KEY.eq(key.second)) }
            val field =
                asked.entries.drop(1).fold(DSL.case_().`when`(asked.keys.first(), asked.values.first())) { c, (q, k) ->
                    c.`when`(q, k)
                }
            db.select(v.ITEM_KEY, field, v.VEC)
                .from(v)
                .where(items(v.ITEM_KEY), DSL.or(asked.keys))
                .fetchSize(STREAMED)
                .fetchResultSet()
                .use { rs -> while (rs.next()) action(rs.getString(1), rs.getInt(2), rs.getBytes(CONTENT_COLUMN)) }
        }
    }

    /** The rows streamed at a time: memory, not meaning. */
    private const val STREAMED = 10_000

    /** The column of a text's UTF-8 or a vector in its row read: after its item's key and its field's place. */
    private const val CONTENT_COLUMN = 3
}
