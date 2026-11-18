package io.github.vlsergey.recommend4me.background

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

private val log = LoggerFactory.getLogger(Threads::class.java)

/**
 * THE BACKGROUND OF THE APPLICATION, ITS THREADS: what is started, a daemon thread of its own; what is begun later, by
 * a timer; the rounds, each a scheduler of its own. Not of the tests: they set `recommend4me.background` to `held` and
 * hold what is started till they run it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["recommend4me.background"], havingValue = "threads", matchIfMissing = true)
class BackgroundThreads {
    @Bean(destroyMethod = "close") fun background(): Background = Threads()
}

/** [Background] by threads: daemon threads, a timer of what is begun later, a scheduler a kind of rounds. */
class Threads : Background, AutoCloseable {
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "background-timer").apply { isDaemon = true }
    }

    override fun start(name: String, task: () -> Unit): Started {
        val thread = Thread(task, name).apply { isDaemon = true }
        thread.start()
        return object : Started {
            override fun await(timeout: Duration): Boolean {
                thread.join(timeout.toMillis().coerceAtLeast(1))
                return !thread.isAlive
            }

            override fun interrupt() = thread.interrupt()
        }
    }

    override fun later(name: String, delay: Duration, task: () -> Unit): Later {
        val begin = timer.schedule({ start(name, task) }, delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
        return Later { begin.cancel(false) }
    }

    override fun every(name: String, first: Duration, period: Duration, task: () -> Unit): Rounds {
        val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, name).apply { isDaemon = true } }
        scheduler.scheduleWithFixedDelay(
            { round(name, task) },
            first.toMillis(),
            period.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        return Rounds { wait ->
            scheduler.shutdownNow()
            scheduler.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    override fun endless(name: String, task: () -> Unit): Started = start(name, task)

    /**
     * A round of [task]. A BOUNDARY: a failure let out of a round of a scheduler cancels its every next round, so
     * whatever a round throws is told here and the next one done all the same.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun round(name: String, task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            log.warn("{}: a round failed: {}", name, e.message, e)
        }
    }

    override fun close() {
        timer.shutdownNow()
    }
}
