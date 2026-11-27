package io.github.vlsergey.recommend4me.task

import java.time.Duration
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/**
 * THE QUEUE OF THE COMPUTATIONS CLOSED FIRST AS THE APPLICATION STOPS ([Tasks.close]): in the stop of the application's
 * lifecycle, before any of its components is destroyed — every store open yet. A work waiting its turn then is given up
 * as its turn comes and stays in the queue for the next start; closed by the store that closed first, the index of the
 * list closed under the works waiting, each of them took its turn, failed on the file closed and was gone from the
 * queue (2026-11-27: the sweeps of the other works lost at three stops of five).
 */
@Component
class TasksStop(private val tasks: Tasks) : SmartLifecycle {
    @Volatile private var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        tasks.close(WAIT)
        running = false
    }

    override fun isRunning() = running

    /** The first of everything to stop. */
    override fun getPhase() = Int.MAX_VALUE

    companion object {
        /** How long the work running is waited for: never a file closed under a transaction of it. */
        val WAIT: Duration = Duration.ofSeconds(30)
    }
}
