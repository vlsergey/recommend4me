package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.background.Background
import io.github.vlsergey.recommend4me.background.Rounds
import jakarta.annotation.PreDestroy
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(DatabaseCompaction::class.java)

/**
 * THE FILES COMPACTED WHILE THE APPLICATION RUNS: every [EVERY] each file much of which is unused is compacted for at
 * most [BUDGET_MS] ([Database.compact]) — a big one over several rounds, a little at a time, the writes waiting no
 * longer than that. An idle file is compacted at its closing anyway; this is for the files written all day, the model's
 * above all.
 */
@Component
class DatabaseCompaction(private val databases: Databases, private val background: Background) {

    @Volatile private var rounds: Rounds? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        rounds = background.every("compaction", FIRST, EVERY, ::compactAll)
    }

    // A BOUNDARY: whatever one file throws is logged and the next file compacted
    @Suppress("TooGenericExceptionCaught")
    private fun compactAll() {
        databases.all().forEach { db ->
            try {
                val started = System.currentTimeMillis()
                val (before, after) = db.compact(BUDGET_MS) ?: return@forEach
                val used = db.usage()
                log.info(
                    "{}: compacted from {} MB to {} MB in {} ms; of it used {} %, of its chunks {} %",
                    db.name,
                    before shr MEGABYTE_SHIFT,
                    after shr MEGABYTE_SHIFT,
                    System.currentTimeMillis() - started,
                    used?.second,
                    used?.third,
                )
            } catch (e: Exception) {
                log.warn("{}: not compacted: {}", db.name, e.message, e)
            }
        }
    }

    /** No round begun any more, the one under way waited for: before the files close. */
    @PreDestroy
    fun stop() {
        rounds?.stop(Duration.ofMillis(BUDGET_MS * 2L))
    }

    companion object {
        /** After the start's own work has settled. */
        private val FIRST: Duration = Duration.ofMinutes(5)
        private val EVERY: Duration = Duration.ofMinutes(15)

        /** The longest one file is compacted at a time: its writes wait meanwhile. */
        private const val BUDGET_MS = 20_000
    }
}
