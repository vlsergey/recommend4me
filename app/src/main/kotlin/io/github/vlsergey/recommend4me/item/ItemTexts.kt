package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.source.SourceSchema
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** THE TEXTS THE SITE GIVES ITS ITEMS — by their keys, as the source database holds them. */
interface ItemTexts {
    /** The texts of the item written where changed, a blank one removed; the item told changed when any was. */
    fun setTexts(itemId: String, texts: Map<String, String?>)

    /** The texts of one item: key to text. */
    fun texts(itemId: String): Map<String, String>

    /** The texts of the items [ids] — of the [keys] alone when given: item to key to text. */
    fun textsOf(ids: Collection<String>, keys: Collection<String>? = null): Map<String, Map<String, String>>

    /** Every text of the [keys] of every item, streamed item by item. */
    fun forEachTexts(keys: Collection<String>, action: (itemId: String, texts: Map<String, String>) -> Unit)

    /** [n] texts of the [keys] of items drawn at random. */
    fun sampleTexts(n: Int, keys: Collection<String>): List<String>
}

/**
 * THE SITE'S TEXTS OF AN ITEM AS COLUMNS OF ITS ROW (V37-V39): a text read with the item's row, the row found by one
 * tree of the file — not a row of a table of texts and a LOB of H2 each, five reads of the disk a work. The columns are
 * the source's own: its migrations make one of every text its works have ([keysOf]), named by [columnOf].
 */
object SiteTexts {
    /** The keys of the texts the works of a source of the [schema] have: its texts, and the line of its characters. */
    fun keysOf(schema: SourceSchema): List<String> =
        (schema.texts.map { it.key } + listOfNotNull(schema.universeLine)).distinct()

    /**
     * The column of an item's row holding the site's text [key]: `SITE_TEXT_` and the key's words in capitals parted by
     * `_` — `pairingsLine` is `SITE_TEXT_PAIRINGS_LINE` — as a source's migration names it.
     */
    fun columnOf(key: String): String =
        "SITE_TEXT_" + key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase().replace(Regex("[^A-Z0-9]"), "_")
}

/**
 * The [ItemTexts] of the source database [db], of the texts of the [keys] its works have ([SiteTexts]): [changed] told
 * of every item whose texts were written. A key whose column the source's migrations did not make fails at once.
 */
internal class ItemTextRepository(
    private val db: DSLContext,
    private val changed: (Collection<String>) -> Unit,
    keys: Collection<String>,
) : ItemTexts {

    /** The column of every text the source's works have, by its key. */
    private val columns: Map<String, Field<String?>> = columnsOf(db, keys)

    override fun sampleTexts(n: Int, keys: Collection<String>): List<String> {
        val asked = fieldsOf(keys)
        if (asked.isEmpty()) return emptyList()
        return db.select(asked)
            .from(ITEM)
            .where(anyOf(asked))
            .orderBy(DSL.rand())
            .limit(n)
            .fetch()
            .flatMap { r -> asked.mapNotNull { r.get(it) } }
            .take(n)
    }

    override fun setTexts(itemId: String, texts: Map<String, String?>) {
        if (texts.isEmpty()) return
        val unknown = texts.keys - columns.keys
        require(unknown.isEmpty()) {
            "the texts $unknown of $itemId have no column: no migration of the source made one"
        }
        val before = texts(itemId)
        val written =
            texts.mapValues { it.value?.trim()?.takeIf { t -> t.isNotEmpty() } }.filter { before[it.key] != it.value }
        if (written.isEmpty()) return
        db.update(ITEM).set(written.mapKeys { columns.getValue(it.key) }).where(ITEM.SITE_ID.eq(itemId)).execute()
        changed(listOf(itemId))
    }

    override fun texts(itemId: String): Map<String, String> {
        if (columns.isEmpty()) return emptyMap()
        return db.select(columns.values).from(ITEM).where(ITEM.SITE_ID.eq(itemId)).fetchOne()?.let(::textsOf).orEmpty()
    }

    override fun textsOf(ids: Collection<String>, keys: Collection<String>?): Map<String, Map<String, String>> {
        val asked = keys?.let(::fieldsOf) ?: columns.values.toList()
        if (asked.isEmpty() || ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Map<String, String>>()
        ids.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(listOf(ITEM.SITE_ID) + asked).from(ITEM).where(ITEM.SITE_ID.`in`(chunk)).and(anyOf(asked)).fetch {
                out[it.get(ITEM.SITE_ID)!!] = textsOf(it)
            }
        }
        return out
    }

    /** In the order the rows are kept: every row of the file read once, one way along it. */
    override fun forEachTexts(keys: Collection<String>, action: (itemId: String, texts: Map<String, String>) -> Unit) {
        val asked = fieldsOf(keys)
        if (asked.isEmpty()) return
        db.select(listOf(ITEM.SITE_ID) + asked)
            .from(ITEM)
            .where(anyOf(asked))
            .orderBy(ITEM.INTERNAL_ID)
            .fetchSize(TEXTS_STREAMED)
            .fetchLazy()
            .use { cursor -> cursor.forEach { action(it.get(ITEM.SITE_ID)!!, textsOf(it)) } }
    }

    /** The columns of the [keys] the source's works have. */
    private fun fieldsOf(keys: Collection<String>): List<Field<String?>> = keys.distinct().mapNotNull { columns[it] }

    /** The texts a row read holds, by their keys. */
    private fun textsOf(r: org.jooq.Record): Map<String, String> =
        columns.entries
            .mapNotNull { (key, f) -> r.indexOf(f).takeIf { it >= 0 }?.let { r.get(it) as String? }?.let { key to it } }
            .toMap()

    private companion object {
        /** The rows of texts streamed at a time — long ones, fewer than of the rest: memory, not meaning. */
        const val TEXTS_STREAMED = 500

        /** Whether a row has any of the texts [fields]. */
        fun anyOf(fields: List<Field<String?>>): Condition = DSL.or(fields.map { it.isNotNull })

        /**
         * The column of every text of [keys] the item's row has; one the source's migrations did not make fails, as the
         * source's file is opened.
         */
        fun columnsOf(db: DSLContext, keys: Collection<String>): Map<String, Field<String?>> {
            val made =
                db.select(DSL.field(DSL.name("COLUMN_NAME"), String::class.java))
                    .from(DSL.table(DSL.name("INFORMATION_SCHEMA", "COLUMNS")))
                    .where(DSL.field(DSL.name("TABLE_NAME"), String::class.java).eq(ITEM.name.uppercase()))
                    .fetchSet { it.value1() }
            val missing = keys.filter { SiteTexts.columnOf(it) !in made }
            check(missing.isEmpty()) {
                "the source's texts $missing have no column ${missing.map(SiteTexts::columnOf)} of item: " +
                    "the source's migrations are to make one of every text its works have"
            }
            return keys.associateWith { DSL.field(DSL.name(SiteTexts.columnOf(it)), SQLDataType.VARCHAR) }
        }
    }
}
