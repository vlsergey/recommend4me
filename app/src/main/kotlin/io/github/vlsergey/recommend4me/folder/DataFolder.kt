package io.github.vlsergey.recommend4me.folder

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where everything of the application lies: see docs/architecture.md, "Storage".
 */
@Component
class DataFolder(@Value("\${recommend4me.data-dir}") dir: String) {

    val root: Path = Path.of(dir).toAbsolutePath().also { Files.createDirectories(it) }

    /** The application's own database: the settings. */
    val app: Path get() = root.resolve("app")

    fun source(sourceId: String): Path = root.resolve("sources").resolve(sourceId).also { Files.createDirectories(it) }

    fun type(typeId: String): Path = root.resolve("types").resolve(typeId).also { Files.createDirectories(it) }

    val models: Path get() = root.resolve("models")

    val backups: Path get() = root.resolve("backups")

    companion object {
        /** The name of the H2 file of a database without its `.mv.db`. */
        fun database(folder: Path, name: String): Path = folder.resolve(name)

        /** The files whose loss nothing could make good: they are backed up on every start. */
        val PERSONAL = listOf("corrections", "ratings")
    }
}
