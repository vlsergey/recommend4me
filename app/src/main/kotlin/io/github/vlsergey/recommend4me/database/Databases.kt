package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.folder.DataFolder
import io.github.vlsergey.recommend4me.preferences.CatalogueRenameRepository
import io.github.vlsergey.recommend4me.plugin.Plugins
import org.jooq.DSLContext
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/** The three files of a source: what the site says, the user's answers on the paragraphs of its texts, the user's marks of its pictures and reviews. */
class SourceDatabases(val source: Database, val corrections: Database, val ratings: Database)

/** The file of a content type: what is learnt. */
class TypeDatabases(val model: Database)

/**
 * Every database file of every source and content type, opened and migrated on start and held
 * open until the end: an open file is a pool of connections, not a copy of its data.
 */
@Component
class Databases(folder: DataFolder, plugins: Plugins) : DisposableBean {

    /**
     * The user's preferences, every content type's: the names the user gives the entities of a
     * catalogue, the user's word on what a search found. Opened first: the dictionaries give theirs
     * to it.
     */
    val preferences = Database("preferences", DataFolder.database(folder.root, "preferences"), "preferences")

    /**
     * The user's dictionaries, common to every content type: the tags of every site as the user
     * merges and names them, the universes, and the normalized works with what the user said of them.
     */
    val dictionaries = Database("dictionaries", DataFolder.database(folder.root, "dictionaries"), "dictionaries") { db ->
        CatalogueRenameRepository.moveFrom("dictionaries", db, preferences.dsl)
    }

    private val sources: Map<String, SourceDatabases> = plugins.sources.associate { s ->
        val dir = folder.source(s.id)
        s.id to SourceDatabases(
            source = Database("${s.id}/source", dir.resolve("source"), "source", s.migrations),
            corrections = Database("${s.id}/corrections", dir.resolve("corrections"), "corrections", placeholders = mapOf("source" to s.id)),
            ratings = Database("${s.id}/ratings", dir.resolve("ratings"), "ratings"),
        )
    }

    private val types: Map<String, TypeDatabases> = plugins.types.associate { t ->
        t.id to TypeDatabases(model = Database("${t.id}/model", folder.type(t.id).resolve("model"), "model"))
    }

    fun of(sourceId: String): SourceDatabases = sources[sourceId] ?: error("No such source: $sourceId")

    fun source(sourceId: String): DSLContext = of(sourceId).source.dsl
    fun ratings(sourceId: String): DSLContext = of(sourceId).ratings.dsl

    fun type(typeId: String): TypeDatabases = types[typeId] ?: error("No such content type: $typeId")

    fun model(typeId: String): DSLContext = type(typeId).model.dsl

    /** Every database file. */
    fun all(): List<Database> =
        sources.values.flatMap { listOf(it.source, it.corrections, it.ratings) } + types.values.map { it.model } + dictionaries + preferences

    override fun destroy() {
        sources.values.forEach { listOf(it.source, it.corrections, it.ratings).forEach(Database::close) }
        types.values.forEach { it.model.close() }
        dictionaries.close()
        preferences.close()
    }
}
