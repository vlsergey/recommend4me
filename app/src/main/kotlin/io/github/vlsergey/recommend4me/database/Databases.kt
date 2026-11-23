package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.folder.DataFolder
import io.github.vlsergey.recommend4me.part.PartStore
import io.github.vlsergey.recommend4me.part.V19__parts_out_of_database
import io.github.vlsergey.recommend4me.part.V26__item_text_size_filled
import io.github.vlsergey.recommend4me.part.V28__item_text_classes_filled
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.preferences.CatalogueRenameRepository
import io.github.vlsergey.recommend4me.series.V34__different_in_series_taken_back
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Duration
import org.jooq.DSLContext
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * The three files of a source: what the site says, the user's answers on the paragraphs of its texts, the user's marks
 * of its pictures and reviews; and the store of the texts of its parts and their vectors, outside the database
 * ([PartStore]).
 */
class SourceDatabases(val source: Database, val corrections: Database, val ratings: Database, val parts: PartStore)

/**
 * The files of a content type: what is learnt, and the stores of the searches of its works' duplicates and of its
 * authors'.
 */
class TypeDatabases(val model: Database, val workDuplicates: DuckDatabase, val authorDuplicates: DuckDatabase)

/**
 * Every database file of every source and content type, opened and migrated on start and held open until the end: an
 * open file is a pool of connections, not a copy of its data.
 */
@Component
class Databases(folder: DataFolder, plugins: Plugins, private val tasks: Tasks) : DisposableBean {

    /**
     * The user's preferences, every content type's: the names the user gives the entities of a catalogue, the user's
     * word on what a search found. Opened first: the dictionaries give theirs to it.
     */
    val preferences = Database("preferences", DataFolder.database(folder.root, "preferences"), "preferences")

    /**
     * The user's dictionaries, common to every content type: the tags of every site as the user merges and names them,
     * the universes, and the normalized works with what the user said of them.
     */
    val dictionaries =
        Database(
            "dictionaries",
            DataFolder.database(folder.root, "dictionaries"),
            "dictionaries",
            // A migration done once and nothing now, kept by its number
            code = listOf(V34__different_in_series_taken_back()),
        ) { db ->
            CatalogueRenameRepository.moveFrom("dictionaries", db, preferences.dsl)
        }

    private val sources: Map<String, SourceDatabases> =
        plugins.sources.associate { s ->
            val dir = folder.source(s.id)
            // Opened before the source's file: one of its migrations moves the texts into it, another counts them
            val parts = PartStore(dir)
            s.id to
                SourceDatabases(
                    // The pages of a source's file H2 keeps: every pass reads its vectors whole
                    source =
                        Database(
                            "${s.id}/source",
                            dir.resolve("source"),
                            "source",
                            s.migrations,
                            cacheMegabytes = SOURCE_CACHE_MB,
                            code =
                                listOf(
                                    V19__parts_out_of_database(parts),
                                    V26__item_text_size_filled(parts),
                                    V28__item_text_classes_filled(parts),
                                ),
                        ),
                    corrections =
                        Database(
                            "${s.id}/corrections",
                            dir.resolve("corrections"),
                            "corrections",
                            placeholders = mapOf("source" to s.id),
                        ),
                    ratings = Database("${s.id}/ratings", dir.resolve("ratings"), "ratings"),
                    parts = parts,
                )
        }

    private val types: Map<String, TypeDatabases> =
        plugins.types.associate { t ->
            t.id to
                TypeDatabases(
                    model =
                        Database(
                            "${t.id}/model",
                            folder.type(t.id).resolve("model"),
                            "model",
                            cacheMegabytes = MODEL_CACHE_MB,
                        ),
                    workDuplicates =
                        DuckDatabase(
                            "${t.id}/work_duplicates",
                            folder.type(t.id).resolve("work_duplicates.duckdb"),
                            "duplicate/store.sql",
                            DUPLICATES_MEMORY,
                        ),
                    authorDuplicates =
                        DuckDatabase(
                            "${t.id}/authors_duplicates",
                            folder.type(t.id).resolve("authors_duplicates.duckdb"),
                            "author/store.sql",
                            AUTHOR_DUPLICATES_MEMORY,
                        ),
                )
        }

    fun of(sourceId: String): SourceDatabases = sources[sourceId] ?: error("No such source: $sourceId")

    fun source(sourceId: String): DSLContext = of(sourceId).source.dsl

    fun ratings(sourceId: String): DSLContext = of(sourceId).ratings.dsl

    fun type(typeId: String): TypeDatabases = types[typeId] ?: error("No such content type: $typeId")

    fun model(typeId: String): DSLContext = type(typeId).model.dsl

    /** Every database file. */
    fun all(): List<Database> =
        sources.values.flatMap { listOf(it.source, it.corrections, it.ratings) } +
            types.values.map { it.model } +
            dictionaries +
            preferences

    override fun destroy() {
        // No work begun any more, the one running waited for: never a file closed under a transaction of it
        tasks.close(CLOSE_WAIT)
        sources.values.forEach {
            listOf(it.source, it.corrections, it.ratings).forEach(Database::close)
            it.parts.close()
        }
        types.values.forEach {
            it.model.close()
            it.workDuplicates.close()
            it.authorDuplicates.close()
        }
        dictionaries.close()
        preferences.close()
    }

    private companion object {
        /** How long the stop waits for the work running before the files close. */
        val CLOSE_WAIT: Duration = Duration.ofSeconds(30)

        /**
         * The pages of a source's file H2 keeps read, in megabytes ([Database.cacheMegabytes]): of fanfics.me the
         * vectors of its texts alone are 416 MB; used only as pages are read.
         */
        const val SOURCE_CACHE_MB = 1024

        /**
         * The pages of a content type's model file H2 keeps read, in megabytes (owner, 2026-11-22: "make the database's
         * cache a gigabyte"): the indexes of 2.6 million candidates of the duplicates, when they were kept there, made
         * in 48 s with it, 85 s with H2's own.
         */
        const val MODEL_CACHE_MB = 1024

        /** The memory the store of the duplicates of a content type takes at most, as DuckDB names it. */
        const val DUPLICATES_MEMORY = "2GB"

        /** The memory the store of the authors' duplicates of a content type takes at most, as DuckDB names it. */
        const val AUTHOR_DUPLICATES_MEMORY = "1GB"
    }
}
