import org.gradle.api.GradleException
import java.io.File

/**
 * THE LENGTH OF A FILE OF THE SOURCES (AGENTS.md, "A file is of one thing, a few hundred lines"): no
 * file longer than [LIMIT] lines. Kotlin's own conventions keep a file to "a few hundred lines";
 * detekt's LargeClass begins at 600. A file longer is split by what its parts are about — never cut
 * into pieces by its length.
 *
 * The files longer already are named in `config/file-length.txt`, each with its length when it was
 * written there: such a file never grows, and leaves the list once split by meaning; a file not
 * named there never passes the limit.
 */
object FileLength {
    const val LIMIT = 600

    /** The list of the files longer already, relative to the [root], with the lines each may have. */
    const val KNOWN = "config/file-length.txt"

    /** Every one of the [files] checked; the build fails naming every file over its limit. */
    fun check(root: File, files: Collection<File>) {
        val known = File(root, KNOWN).takeIf { it.isFile }?.readLines().orEmpty()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line -> line.substringBeforeLast(' ').trim() to line.substringAfterLast(' ').toInt() }
        val over = files.mapNotNull { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val lines = file.useLines { it.count() }
            val allowed = known[path] ?: LIMIT
            if (lines > allowed) "$path: $lines lines, ${if (path in known) "$allowed when it was named in $KNOWN — it never grows" else "the limit is $LIMIT"}" else null
        }
        if (over.isNotEmpty()) throw GradleException(
            "Files longer than they may be (AGENTS.md: a file is of one thing, split by what its parts are about, never by its length):\n" +
                over.joinToString("\n") { "  $it" },
        )
    }
}
