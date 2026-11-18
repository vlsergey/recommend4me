package io.github.vlsergey.recommend4me.workload

import io.github.vlsergey.recommend4me.background.Background
import io.github.vlsergey.recommend4me.background.Later
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Plans::class.java)

/**
 * THE WORKS PLANNED, of the [Workload]: each with when it is due, since when it is planned, why, whether the user asked
 * for it, and what it does — begun here when it is due, among the works waiting their turn ([Turns]). Planned again
 * before it is due, a work is planned once: its reasons summed, its due time the newer, what it does the newer; planned
 * while a work alike waits its turn, not begun, it is that one — it reads the data as they are when it begins. Of every
 * work begun, the thread that waits its turn and runs it is the [background]'s, for the time of the work. Every plan
 * kept in the [records] from its planning to its end, by its number: a plan of a work running made anew is another.
 */
internal class Plans(private val turns: Turns, private val background: Background, private val records: WorkRecords) {
    /** The number of the next plan. */
    private val nextNo = AtomicLong(records.firstPlan)

    /** The works planned, by the work; guarded by itself. */
    private val planned = HashMap<Work, Plan>()

    /**
     * A work planned, of the number [no]: [task] — what it does, given the place it waits its turn in — once [due];
     * [timer] its begin.
     */
    class Plan(
        val no: Long,
        val work: Work,
        val due: Instant,
        val since: Instant,
        val reasons: Map<String, Int>,
        val asked: Boolean,
        /** It goes after every other work of the background ([Waiter.last]). */
        val last: Boolean,
        val task: (Waiter) -> Unit,
    ) {
        @Volatile var timer: Later? = null
    }

    /**
     * The [work] planned for [due] — when null, as planned before, else at once — for the [reasons], ahead of the
     * background when [asked]; it does [task] when its turn comes.
     */
    fun plan(
        work: Work,
        due: Instant?,
        reasons: Map<String, Int>,
        asked: Boolean,
        last: Boolean,
        task: (Waiter) -> Unit,
    ) {
        if (turns.joined(work, reasons, asked)) return
        synchronized(planned) {
            val old = planned.remove(work)
            old?.timer?.cancel()
            val now = Instant.now()
            val plan =
                Plan(
                    old?.no ?: nextNo.getAndIncrement(),
                    work,
                    due ?: old?.due ?: now,
                    old?.since ?: now,
                    Turns.merged(old?.reasons.orEmpty(), reasons),
                    asked || old?.asked == true,
                    last && !asked && old?.asked != true,
                    task,
                )
            planned[work] = plan
            records.planned(work, plan.no, plan.due, plan.since, plan.reasons, plan.asked)
            val delay = Duration.between(now, plan.due).toMillis().coerceAtLeast(0)
            plan.timer = background.later("workload: $work", Duration.ofMillis(delay)) { begin(plan) }
        }
    }

    /** The [work] begins now, of its caller: no longer planned; its plan, none if it was not planned. */
    fun take(work: Work): Plan? = synchronized(planned) { planned.remove(work) }?.also { it.timer?.cancel() }

    /** The [plan] ended — done, failed or given up for newer data — but not by the stop: its record goes. */
    fun ended(plan: Plan) {
        if (!turns.closed) records.ended(plan.work, plan.no)
    }

    /** Whether the [work] is planned. */
    fun has(work: Work): Boolean = synchronized(planned) { work in planned }

    /** The works planned, the soonest due first. */
    fun list(): List<PlannedWork> =
        synchronized(planned) { planned.values.toList() }
            .sortedBy { it.due }
            .map { PlannedWork(it.work, it.due, it.since, it.reasons, it.asked) }

    /**
     * The [plan] due, on the thread the background begun it on: put among the works waiting their turn — or joined to a
     * work alike waiting — and run once its turn comes. Nothing of a plan planned again or taken meanwhile. Gone from
     * the plans and among the waiting at once: never told neither planned nor waiting between.
     */
    private fun begin(plan: Plan) {
        val (entry, own) =
            synchronized(planned) {
                if (planned[plan.work] !== plan) return
                planned.remove(plan.work)
                turns.enter(plan.work, plan.reasons, joinAlike = true, asked = plan.asked, last = plan.last)
            }
        if (own) run(plan, entry)
    }

    /**
     * The [plan] run as the work [entry]. A BOUNDARY of the background: the work runs whatever code its owner gave — a
     * plugin's among it —, and its failure is told here, where nobody else would hear of it.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun run(plan: Plan, entry: Waiter) {
        try {
            plan.task(entry)
        } catch (e: CancellationException) {
            log.info("{} given up for newer data, done again after them: {}", plan.work, e.message)
        } catch (e: Exception) {
            log.warn("{} failed: {}", plan.work, e.message, e)
        } finally {
            ended(plan)
        }
    }
}
