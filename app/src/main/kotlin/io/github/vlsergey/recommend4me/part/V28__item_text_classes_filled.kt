package io.github.vlsergey.recommend4me.part

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V28__item_text_classes_filled::class.java)

/**
 * THE CHARACTERS OF THE TEXTS KEPT BEFORE BY THEIR CLASSES (V27): every text of the [store] read in one pass, in the
 * order of its keys, an item's parts one after another ([PartStore.forEachText]), their classes summed ([CharClasses])
 * and written to the item a portion at a time, each kept as it is done, how far told in the log. A migration of the
 * application's own code, given the store it reads ([io.github.vlsergey.recommend4me.database.Database]).
 */
@Suppress("ClassName")
class V28__item_text_classes_filled(private val store: PartStore) : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            val started = System.currentTimeMillis()
            var item: String? = null
            var sum = IntArray(CharClasses.NAMES.size)
            val portion = ArrayList<Pair<String, IntArray>>()
            var items = 0
            var bytes = 0L
            fun flush() {
                written(c, portion)
                items += portion.size
                portion.clear()
                if (items % (PORTION * TOLD_EVERY) == 0)
                    log.info(
                        "the classes of the characters of {} items' texts ({} MB) written in {} s",
                        items,
                        bytes / MEGABYTE,
                        (System.currentTimeMillis() - started) / MILLIS,
                    )
            }
            store.forEachText { id, text ->
                if (id != item) {
                    item?.let { portion += it to sum }
                    if (portion.size >= PORTION) flush()
                    item = id
                    sum = IntArray(CharClasses.NAMES.size)
                }
                val own = CharClasses.of(text)
                for (k in sum.indices) sum[k] += own[k]
                bytes += text.size
            }
            item?.let { portion += it to sum }
            flush()
            log.info(
                "the classes of the characters of {} items' texts ({} MB) written in {} ms",
                items,
                bytes / MEGABYTE,
                System.currentTimeMillis() - started,
            )
        } finally {
            c.autoCommit = autoCommit
        }
    }

    /** The classes of the items of [portion] written, and kept. */
    private fun written(c: Connection, portion: List<Pair<String, IntArray>>) {
        if (portion.isEmpty()) return
        c.prepareStatement(
                "UPDATE item SET text_letters_1 = ?, text_letters_2 = ?, text_letters_3 = ?, text_punctuation_1 = ?, " +
                    "text_punctuation_2 = ?, text_spaces = ?, text_breaks = ?, text_other = ? WHERE item_id = ?"
            )
            .use { u ->
                portion.forEach { (id, classes) ->
                    classes.forEachIndexed { k, v -> u.setInt(k + 1, v) }
                    u.setString(classes.size + 1, id)
                    u.addBatch()
                }
                u.executeBatch()
            }
        c.commit()
    }

    private companion object {
        /** The items written and committed at a time. */
        const val PORTION = 5000

        /** The portions between two lines of the log of how far the migration is. */
        const val TOLD_EVERY = 20

        const val MEGABYTE = 1 shl 20
        const val MILLIS = 1000
    }
}
