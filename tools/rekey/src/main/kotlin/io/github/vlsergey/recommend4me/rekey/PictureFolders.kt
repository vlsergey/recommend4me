package io.github.vlsergey.recommend4me.rekey

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.exists

/**
 * THE FOLDERS OF THE PICTURES OF A SOURCE'S WORKS NAMED BY THEIR NUMBERS (`images/<number>/`, `PictureFiles.folderOf`),
 * not by the sites' ids, and the files' paths the table `picture` keeps of them ("12/0-preview.png") with them. The
 * folders moved into a folder of their own first — a number may be the name of another work's folder of before — then
 * put in the place of the old one; the old one kept beside ("images-by-site") with what no picture named, to be looked
 * at and deleted.
 */
class PictureFolders(private val folder: Path, private val c: Connection, private val numbers: Map<String, Long>) {

    /** A picture's files: its work, its position, the files' paths. */
    private class PictureRow(val item: String, val position: Int, val preview: String?, val full: String?)

    fun rekey(name: String, dryRun: Boolean) {
        val images = folder.resolve("images")
        val rows = rows()
        // Every folder of before by the work's number: the folder is a path's first part
        val folders = HashMap<String, Long>()
        rows.forEach { r ->
            val number = numbers[r.item] ?: return@forEach
            listOfNotNull(r.preview, r.full).forEach { folders[it.substringBefore('/')] = number }
        }
        log(
            "$name pictures: ${rows.size} with files, ${folders.size} folders, " +
                "${rows.count { numbers[it.item] == null }} of works gone"
        )
        if (dryRun || !images.exists()) return
        val moved = folder.resolve("images-by-number")
        Files.createDirectories(moved)
        folders.forEach { (old, number) ->
            val from = images.resolve(old)
            if (from.exists()) Files.move(from, moved.resolve(number.toString()))
        }
        write(rows)
        Files.move(images, folder.resolve("images-by-site"))
        Files.move(moved, images)
        log("$name pictures: ${folders.size} folders moved")
    }

    private fun rows(): List<PictureRow> {
        val out = ArrayList<PictureRow>()
        c.createStatement().use { s ->
            s.executeQuery(
                    "SELECT item_id, position, preview_file, full_file FROM picture " +
                        "WHERE preview_file IS NOT NULL OR full_file IS NOT NULL"
                )
                .use { r ->
                    while (r.next()) out +=
                        PictureRow(
                            r.getString("item_id"),
                            r.getInt("position"),
                            r.getString("preview_file"),
                            r.getString("full_file"),
                        )
                }
        }
        return out
    }

    /** The paths of the files of every picture of a work kept, of its number; of a work gone as they were. */
    private fun write(rows: List<PictureRow>) {
        c.prepareStatement("UPDATE picture SET preview_file = ?, full_file = ? WHERE item_id = ? AND position = ?")
            .use { p ->
                rows.forEach { r ->
                    val number = numbers[r.item] ?: return@forEach
                    fun of(path: String?) = path?.let { "$number/${it.substringAfter('/')}" }
                    p.bound(of(r.preview), of(r.full), r.item, r.position).addBatch()
                }
                p.executeBatch()
            }
    }
}
