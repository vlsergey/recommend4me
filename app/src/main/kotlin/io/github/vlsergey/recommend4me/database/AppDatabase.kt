package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.folder.DataFolder
import org.jooq.DSLContext
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * THE APPLICATION'S OWN DATABASE: its settings ([io.github.vlsergey.recommend4me.settings.Settings]) and the queue of
 * its computations with their last runs ([io.github.vlsergey.recommend4me.task.QueuedTasks]).
 */
@Component
class AppDatabase(folder: DataFolder) : DisposableBean {
    /** The file, for a query of it ([DatabaseQueries]). */
    val database = Database("app", DataFolder.database(folder.root, "app"), "app")

    val dsl: DSLContext = database.dsl

    override fun destroy() = database.close()
}
