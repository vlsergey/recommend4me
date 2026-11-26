package io.github.vlsergey.recommend4me.rekey

import java.sql.Connection

/**
 * THE QUEUE OF THE COMPUTATIONS NAMING ITS WORKS BY THEIR NUMBERS, of the application's database: a task of a work
 * (`queued_task.item`: a site's id of the task's source, or a work's key "source/site" of one of none) of its number,
 * the source beside it, its key made anew as the application makes it (`QueuedTasks.key`); a key of the works a task is
 * to be done of ("source\tsite", "source/site") of the number; the runs kept of a work likewise. A task or a key of a
 * work no longer kept goes, a run keeps no work. Where a task over the works stood ([cursor]) is forgotten: the order
 * of the works' keys is another, the next sweep goes from the first.
 */
class QueueWorks(private val c: Connection, private val numbers: Numbers) {

    fun rekey(dryRun: Boolean) {
        val tasks = tasks()
        val keys = keys()
        log(
            "queue: ${tasks.size} tasks of a work, ${keys.size} keys of works, ${tasks.count { it.number == null }} " +
                "tasks and ${keys.count { it.second == null }} keys of works gone"
        )
        if (dryRun) return
        c.autoCommit = false
        tasks.forEach(::write)
        keys.forEach { (old, new) -> write(old, new) }
        runs()
        c.createStatement().use { it.executeUpdate("UPDATE queued_task SET cursor_grade = NULL, cursor_work = NULL") }
        c.commit()
        c.autoCommit = true
    }

    /** A task of a work: its key, its fields, the work's number; none of a work gone. */
    private class WorkTask(
        val key: String,
        val what: String,
        val type: String?,
        val source: String,
        val facet: String?,
        val works: String?,
        val number: Long?,
    ) {
        /**
         * The key the application gives it: what, type, source, facet and item, tab-separated, and over which works.
         */
        fun newKey() =
            (listOf(what, type, source, facet, number?.toString()).map { it.orEmpty() } + listOfNotNull(works))
                .joinToString("\t")
    }

    private fun tasks(): List<WorkTask> {
        val out = ArrayList<WorkTask>()
        c.createStatement().use { s ->
            s.executeQuery(
                    "SELECT task_key, what, type_id, source_id, facet, item, works FROM queued_task " +
                        "WHERE item IS NOT NULL"
                )
                .use { r ->
                    while (r.next()) {
                        // Of a source of none, the item a work's key: its source in it
                        val (source, site) = sourcedOf(r.getString("source_id"), r.getString("item"))
                        out +=
                            WorkTask(
                                r.getString("task_key"),
                                r.getString("what"),
                                r.getString("type_id"),
                                source,
                                r.getString("facet"),
                                r.getString("works"),
                                numbers.numberOf(source, site),
                            )
                    }
                }
        }
        return out
    }

    private fun sourcedOf(source: String?, item: String): Pair<String, String> =
        if (source != null) source to item else item.substringBefore('/') to item.substringAfter('/')

    private fun write(task: WorkTask) {
        val number = task.number
        if (number == null) {
            update("DELETE FROM queued_key WHERE task_key = ?", task.key)
            update("DELETE FROM queued_task WHERE task_key = ?", task.key)
            return
        }
        val key = task.newKey()
        update(
            "UPDATE queued_task SET task_key = ?, source_id = ?, item = ? WHERE task_key = ?",
            key,
            task.source,
            number.toString(),
            task.key,
        )
        update("UPDATE queued_key SET task_key = ? WHERE task_key = ?", key, task.key)
    }

    /** Every key of a work a task is to be done of, with the key of its number; none of a work gone. */
    private fun keys(): List<Pair<Pair<String, String>, String?>> {
        val out = ArrayList<Pair<Pair<String, String>, String?>>()
        c.createStatement().use { s ->
            s.executeQuery("SELECT task_key, item_key FROM queued_key").use { r ->
                while (r.next()) {
                    val task = r.getString("task_key")
                    val key = r.getString("item_key")
                    numbered(key)?.let { out += (task to key) to it.getOrNull() }
                }
            }
        }
        return out
    }

    /**
     * Of a key of a work — "source\tsite", "source/site" — the key of its number: failed of a work gone; none of a key
     * of no work.
     */
    private fun numbered(key: String): Result<String>? {
        val separator = listOf('\t', '/').firstOrNull { it in key }
        val source = separator?.let { key.substringBefore(it) }?.takeIf { it in numbers.sources } ?: return null
        val number = numbers.numberOf(source, key.substringAfter(separator))
        return if (number == null) Result.failure(NoSuchElementException(key))
        else Result.success("$source$separator$number")
    }

    private fun write(old: Pair<String, String>, new: String?) {
        if (new == null) update("DELETE FROM queued_key WHERE task_key = ? AND item_key = ?", old.first, old.second)
        else
            update("UPDATE queued_key SET item_key = ? WHERE task_key = ? AND item_key = ?", new, old.first, old.second)
    }

    /** The runs kept of a work: of its number, the source beside it; of a work gone, of none. */
    private fun runs() {
        val runs = ArrayList<Triple<Long, String, Long?>>()
        c.createStatement().use { s ->
            s.executeQuery("SELECT id, source_id, item FROM task_run WHERE item IS NOT NULL").use { r ->
                while (r.next()) {
                    val (source, site) = sourcedOf(r.getString("source_id"), r.getString("item"))
                    runs += Triple(r.getLong("id"), source, numbers.numberOf(source, site))
                }
            }
        }
        c.prepareStatement("UPDATE task_run SET source_id = ?, item = ? WHERE id = ?").use { p ->
            runs.forEach { (id, source, number) ->
                p.bound(source, number?.toString(), id).addBatch()
            }
            p.executeBatch()
        }
        log("queue: ${runs.size} runs of a work, ${runs.count { it.third == null }} of a work gone")
    }

    private fun update(sql: String, vararg values: String) =
        c.prepareStatement(sql).use { p ->
            values.forEachIndexed { i, v -> p.setString(i + 1, v) }
            p.executeUpdate()
        }
}
