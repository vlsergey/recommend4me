package io.github.vlsergey.recommend4me.rekey

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * THE ONE-TIME MOVE OF EVERY STORE OF THE APPLICATION TO THE NUMBERS OF THE WORKS (2026-11-26): what named a work by
 * the site's id names it by the application's number of it (`item.internal_id` of its source's database) — the
 * databases of the corrections, the grades, the dictionaries, the preferences, the models and the queue, the folders of
 * the pictures, the stores of the parts, the indexes of the lists and of the search. Run once, the application stopped,
 * its data backed up, before the version that reads them starts: that version's migrations of the sources' databases
 * move the rest (V40-V42) and refuse a database this tool has not marked (`works_numbered_elsewhere`).
 *
 * `rekey <data folder> [--dry-run]`: of `--dry-run`, what would be done counted, nothing written.
 */
fun main(args: Array<String>) {
    val data = Path.of(args.firstOrNull() ?: error("rekey <data folder> [--dry-run]"))
    val dryRun = "--dry-run" in args
    val sources = sourcesOf(data)
    sources.forEach { dir ->
        connect(dir.resolve("source")).use { c ->
            val marked = c.metaData.getTables(null, "PUBLIC", "WORKS_NUMBERED_ELSEWHERE", null).use { it.next() }
            check(!marked) { "${dir.name}: its works are numbered elsewhere already: the tool has run" }
        }
    }
    val numbers = Numbers.read(data)
    sources.forEach { dir -> source(dir, numbers, dryRun) }
    shared(data, numbers, dryRun)
    Files.list(data.resolve("types")).use { types -> types.sorted().toList() }.forEach { type(it, numbers, dryRun) }
    if (dryRun) return
    sources.forEach { dir ->
        connect(dir.resolve("source")).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE works_numbered_elsewhere(at TIMESTAMP WITH TIME ZONE)")
                s.execute("INSERT INTO works_numbered_elsewhere VALUES (CURRENT_TIMESTAMP)")
            }
        }
    }
    log("done")
}

private fun sourcesOf(data: Path): List<Path> =
    Files.list(data.resolve("sources")).use { dirs ->
        dirs.filter { it.resolve("source.mv.db").exists() }.sorted().toList()
    }

/** Of one source: its corrections, its grades and marks, its pictures, its parts. */
private fun source(dir: Path, numbers: Numbers, dryRun: Boolean) {
    val source = dir.name
    if (dir.resolve("corrections.mv.db").exists())
        connect(dir.resolve("corrections")).use { c ->
            WorkColumns(c, numbers, source)
                .rekey("$source corrections", listOf(WorkColumn("tag_text_answer", "item_id")), dryRun)
        }
    if (dir.resolve("ratings.mv.db").exists())
        connect(dir.resolve("ratings")).use { c ->
            WorkColumns(c, numbers, source)
                .rekey(
                    "$source ratings",
                    listOf(
                        WorkColumn("picture_mark", "item_id"),
                        WorkColumn("review_mark", "item_id"),
                        WorkColumn("site_signal", "item_id"),
                        WorkColumn("match_feedback", "mark_item"),
                        WorkColumn("match_feedback", "match_item", "match_source"),
                    ),
                    dryRun,
                )
        }
    connect(dir.resolve("source")).use { c -> PictureFolders(dir, c, numbers.ofSource(source)).rekey(source, dryRun) }
    PartsByNumber(dir, numbers.ofSource(source)).rekey(source, dryRun)
}

/** The databases of every source together: the dictionaries, the preferences, the queue. */
private fun shared(data: Path, numbers: Numbers, dryRun: Boolean) {
    connect(data.resolve("dictionaries")).use { c ->
        WorkColumns(c, numbers, null)
            .rekey(
                "dictionaries",
                listOf(
                    WorkColumn("work_pair_journal", "item", "source"),
                    WorkColumn("work_pair_journal", "other_item", "other_source"),
                    WorkColumn("work_rating", "item", "source"),
                    WorkColumn("site_work", "item", "source"),
                ),
                dryRun,
            )
    }
    connect(data.resolve("preferences")).use { c ->
        WorkColumns(c, numbers, null)
            .rekey("preferences", listOf(WorkColumn("search_judgment", "item_id", "source")), dryRun)
    }
    connect(data.resolve("app")).use { c -> QueueWorks(c, numbers).rekey(dryRun) }
}

/** Of one content type: its models, the index of its list, the index of its search. */
private fun type(dir: Path, numbers: Numbers, dryRun: Boolean) {
    val type = dir.name
    if (dir.resolve("model.mv.db").exists())
        connect(dir.resolve("model")).use { c ->
            WorkColumns(c, numbers, null)
                .rekey(
                    "$type model",
                    listOf(
                            "held_out",
                            "mark_likeness_strength",
                            "mark_likeness_axes",
                            "held_out_explained",
                            "search_score",
                        )
                        .map { WorkColumn(it, "item_id", "source") },
                    dryRun,
                )
        }
    WorkListByNumber(dir, numbers).rekey(type, dryRun)
    SearchIndexByNumber(dir, numbers).rekey(type, dryRun)
}
