package io.github.vlsergey.recommend4me.settings

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.database.app.tables.references.SETTING
import io.github.vlsergey.recommend4me.folder.DataFolder
import io.github.vlsergey.recommend4me.source.SourceSettings
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * Key-value settings kept in the application's own database: they survive restarts and are
 * editable from the interface. A source's are named `source.<id>.<key>`, a job's state
 * `job.<id>.<key>`.
 */
@Component
class Settings(folder: DataFolder) : DisposableBean {

    private val database = Database("app", DataFolder.database(folder.root, "app"), "app")
    private val db = database.dsl

    fun get(name: String): String? =
        db.select(SETTING.CONTENT).from(SETTING).where(SETTING.NAME.eq(name)).fetchOne(SETTING.CONTENT)

    fun set(name: String, value: String?) {
        if (value == null) {
            db.deleteFrom(SETTING).where(SETTING.NAME.eq(name)).execute()
        } else {
            db.insertInto(SETTING).set(SETTING.NAME, name).set(SETTING.CONTENT, value)
                .onDuplicateKeyUpdate().set(SETTING.CONTENT, value)
                .execute()
        }
    }

    /** The settings whose names begin with [prefix], by the rest of the name. */
    fun withPrefix(prefix: String): Map<String, String> =
        db.select(SETTING.NAME, SETTING.CONTENT).from(SETTING).where(SETTING.NAME.startsWith(prefix))
            .fetch().associate { it.value1()!!.removePrefix(prefix) to it.value2().orEmpty() }

    /** The settings of one source. */
    fun of(sourceId: String): SourceSettings = object : SourceSettings {
        override fun get(key: String): String? = this@Settings.get("source.$sourceId.$key")
        override fun set(key: String, value: String?) = this@Settings.set("source.$sourceId.$key", value)
    }

    override fun destroy() = database.close()
}
