package io.github.vlsergey.recommend4me.workload

import io.github.vlsergey.recommend4me.api.model.WorkKind
import io.github.vlsergey.recommend4me.database.AppDatabase
import io.github.vlsergey.recommend4me.folder.DataFolder
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** What the queue of the computations tells of the works done, running and planned. */
class WorkloadTest {
    @TempDir lateinit var folder: Path

    private val opened = ArrayList<AppDatabase>()

    /** The records of the queue made last. */
    private lateinit var records: QueuedWorks

    /** A queue kept in a database of its own, of the test's folder. */
    private fun workload(): Workload {
        val app = AppDatabase(DataFolder(folder.resolve("run${opened.size}").toString())).also { opened += it }
        records = QueuedWorks(app)
        return Workload(records = records)
    }

    @AfterEach fun close() = opened.forEach { it.destroy() }

    @Test
    fun `a planned work begins with the reasons it was planned for, and is planned no longer`() {
        val workload = workload()
        val train = Work(WorkKind.TRAIN, type = "books")
        val runs = AtomicInteger()
        workload.plan(train, Instant.now().plusSeconds(60), "grade") { runs.incrementAndGet() }
        workload.plan(train, null, "grade") { runs.incrementAndGet() }
        workload.plan(train, null, "answer") { runs.incrementAndGet() }
        assertEquals(1, workload.planned().size)
        workload.run(train) {
            assertEquals(mapOf("grade" to 2, "answer" to 1), workload.running()!!.reasons)
        }
        assertTrue(workload.planned().isEmpty())
        assertNull(workload.running())
        assertEquals(mapOf("grade" to 2, "answer" to 1), records.runs().single().reasons)
        // Run by its caller, the plan is not run again
        Thread.sleep(50)
        assertEquals(0, runs.get())
    }

    @Test
    fun `a work planned again and again before it is due runs once, the newer body, then what it told`() {
        val workload = workload()
        val sizes = Work(WorkKind.WORK_SIZES, type = "books")
        val ran = java.util.Collections.synchronizedList(ArrayList<String>())
        val told = CountDownLatch(1)
        repeat(3) { i ->
            workload.plan(
                sizes,
                Instant.now().plusMillis(100),
                "items",
                after = { what: String ->
                    ran += "told $what"
                    told.countDown()
                },
            ) {
                ran += "body $i"
                "body $i"
            }
        }
        assertTrue(told.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("body 2", "told body 2"), ran)
        assertEquals(mapOf("items" to 3), records.runs().single().reasons)
    }

    @Test
    fun `a work planned while one alike waits its turn is that one, not run twice`() {
        val workload = workload()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val blocker = thread {
            workload.run(Work(WorkKind.TRAIN, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
            }
        }
        started.await()
        val vectors = Work(WorkKind.TAG_VECTORS)
        val runs = AtomicInteger()
        workload.plan(vectors, null, "start") { runs.incrementAndGet() }
        while (workload.waiting().isEmpty()) Thread.sleep(5)
        workload.plan(vectors, null, "tags") { runs.incrementAndGet() }
        assertTrue(workload.planned().isEmpty())
        assertEquals(mapOf("start" to 1, "tags" to 1), workload.waiting().single().reasons)
        release.countDown()
        blocker.join()
        while (workload.due(vectors)) Thread.sleep(5)
        assertEquals(1, runs.get())
    }

    @Test
    fun `a failure of a planned work is told, the next planned runs all the same`() {
        val workload = workload()
        val done = CountDownLatch(1)
        workload.plan(Work(WorkKind.MANNERS), null, "start") { error("no") }
        workload.plan(Work(WorkKind.WORK_SIZES), Instant.now().plusMillis(50), "start") { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        while (records.runs().size < 2) Thread.sleep(5)
        assertEquals(listOf(true, false), records.runs().map { it.failed })
    }

    @Test
    fun `a request of the browser never waits for a computation, nor runs one, as a background thread does`() {
        val workload = workload()
        val ran = AtomicInteger()
        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(MockHttpServletRequest("GET", "/api/items/s/1/held-out"))
        )
        try {
            assertFailsWith<IllegalStateException> {
                workload.run(Work(WorkKind.HELD_OUT, source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
            }
            assertEquals(0, ran.get())
            assertTrue(workload.waiting().isEmpty())
            // The request's attributes are not passed to a thread it starts: the background runs it
            inBackground {
                workload.run(Work(WorkKind.HELD_OUT, source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
            }
            assertEquals(1, ran.get())
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
        workload.run(Work(WorkKind.HELD_OUT, source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
        assertEquals(2, ran.get())
    }

    @Test
    fun `the last five works done are told, the oldest first, a failed one marked`() {
        val workload = workload()
        repeat(6) { i -> workload.run(Work(WorkKind.SCORE, type = "t$i"), emptyMap()) {} }
        assertFailsWith<IllegalStateException> {
            workload.run(Work(WorkKind.SCORE, type = "bad"), emptyMap()) { error("no") }
        }
        val done = records.runs()
        assertEquals(listOf("t2", "t3", "t4", "t5", "bad"), done.map { it.work.type })
        assertEquals(listOf(false, false, false, false, true), done.map { it.failed })
    }

    @Test
    fun `a work given up for newer data is no failure, and leaves the estimate of its kind as it was`() {
        val workload = workload()
        val suggester = Work(WorkKind.SUGGESTERS, source = "s", facet = "universe")
        workload.run(suggester, emptyMap()) { Thread.sleep(30) }
        val estimate = records.expected(suggester)
        assertFailsWith<CancellationException> {
            workload.run(suggester, emptyMap()) { throw CancellationException("newer changes") }
        }
        val last = records.runs().last()
        assertEquals(false to true, last.failed to last.givenUp)
        assertEquals(estimate, records.expected(suggester))
    }

    @Test
    fun `a work is expected to take as long as the last run of its kind, of any item`() {
        val workload = workload()
        assertNull(records.expected(Work(WorkKind.HELD_OUT, source = "s", item = "1")))
        workload.run(Work(WorkKind.HELD_OUT, source = "s", item = "1"), emptyMap()) { Thread.sleep(30) }
        val expected = records.expected(Work(WorkKind.HELD_OUT, source = "s", item = "2"))!!
        assertTrue(expected >= 30, "expected $expected ms")
        assertNull(records.expected(Work(WorkKind.HELD_OUT, source = "other", item = "2")))
    }

    @Test
    fun `works alike asked while one waits are that one, its reasons summed`() {
        val workload = workload()
        val held = Work(WorkKind.TAG_VECTORS, item = "1")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val blocker = thread {
            workload.run(Work(WorkKind.TRAIN, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
            }
        }
        started.await()
        val runs = AtomicInteger()
        // A thread of its own for every caller: of the common pool, a runner of two cores has one, and the
        // callers after the first, which waits there, never began — the test waited for them for ever
        val callers = Executors.newFixedThreadPool(4)
        try {
            val answers =
                (1..3).map {
                    CompletableFuture.supplyAsync(
                        {
                            workload.run(held, mapOf("page" to 1)) {
                                runs.incrementAndGet()
                                "answer"
                            }
                        },
                        callers,
                    )
                }
            while (workload.waiting().singleOrNull()?.reasons?.get("page") != 3) Thread.sleep(5)
            // A batch of its own is not joined
            val own =
                CompletableFuture.supplyAsync(
                    {
                        workload.run(held, mapOf("items" to 1), joinAlike = false) {
                            runs.incrementAndGet()
                            "own"
                        }
                    },
                    callers,
                )
            while (workload.waiting().size < 2) Thread.sleep(5)
            release.countDown()
            blocker.join()
            assertEquals(listOf("answer", "answer", "answer"), answers.map { it.get(5, TimeUnit.SECONDS) })
            assertEquals("own", own.get(5, TimeUnit.SECONDS))
            assertEquals(2, runs.get())
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `the user's work waits ahead of the background, the running one not stopped, each kind in the order asked`() {
        val workload = workload()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val blocker = thread {
            workload.run(Work(WorkKind.TRAIN, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
                order += "train"
            }
        }
        started.await()
        val callers = Executors.newFixedThreadPool(4)
        try {
            fun ask(what: WorkKind, asked: Boolean, waiting: Int): CompletableFuture<Unit> =
                CompletableFuture.supplyAsync(
                        { workload.run(Work(what), emptyMap(), asked = asked) { order += what.value } },
                        callers,
                    )
                    .also { while (workload.waiting().size < waiting) Thread.sleep(5) }
            val all =
                listOf(
                    ask(WorkKind.MANNERS, false, 1),
                    ask(WorkKind.WORK_SIZES, false, 2),
                    ask(WorkKind.SEARCH_UPDATE, true, 3),
                    ask(WorkKind.HELD_OUT, true, 4),
                )
            // The page of the queue tells the order they will run in, the asked ones marked
            assertEquals(
                listOf("searchUpdate", "heldOut", "manners", "workSizes"),
                workload.waiting().map { it.work.what.value },
            )
            assertEquals(listOf(true, true, false, false), workload.waiting().map { it.asked })
            assertTrue(workload.running()!!.work.what == WorkKind.TRAIN && !workload.running()!!.asked)
            release.countDown()
            all.forEach { it.get(5, TimeUnit.SECONDS) }
            blocker.join()
            assertEquals(listOf("train", "searchUpdate", "heldOut", "manners", "workSizes"), order)
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `a work alike the user asks for while it waits is put ahead with it`() {
        val workload = workload()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val blocker = thread {
            workload.run(Work(WorkKind.TRAIN, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
            }
        }
        started.await()
        val callers = Executors.newFixedThreadPool(4)
        try {
            val first =
                CompletableFuture.supplyAsync(
                    { workload.run(Work(WorkKind.SET_VECTORS), emptyMap()) { order += "other" } },
                    callers,
                )
            while (workload.waiting().size < 1) Thread.sleep(5)
            val duplicates = Work(WorkKind.DUPLICATES, type = "t")
            val background =
                CompletableFuture.supplyAsync(
                    { workload.run(duplicates, mapOf("start" to 1)) { order += "duplicates" } },
                    callers,
                )
            while (workload.waiting().size < 2) Thread.sleep(5)
            val pressed =
                CompletableFuture.supplyAsync(
                    { workload.run(duplicates, mapOf("asked" to 1), asked = true) { order += "duplicates" } },
                    callers,
                )
            while (workload.waiting().firstOrNull()?.work != duplicates) Thread.sleep(5)
            release.countDown()
            listOf(first, background, pressed).forEach { it.get(5, TimeUnit.SECONDS) }
            blocker.join()
            assertEquals(listOf("duplicates", "other"), order)
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `a work of the background waiting longer than the aging goes as one the user asked for`() {
        val now = Instant.now()
        val old = Waiter(Work(WorkKind.TEXT_VECTORS), now.minus(Turns.AGING).minusSeconds(1), emptyMap(), true, false)
        val asked = Waiter(Work(WorkKind.TRAIN), now.minusSeconds(1), emptyMap(), true, true)
        val young = Waiter(Work(WorkKind.TAG_VECTORS), now.minusSeconds(2), emptyMap(), true, false)
        assertEquals(listOf(old, asked, young), listOf(young, asked, old).sortedWith(Turns.order(now)))
    }

    @Test
    fun `a work that goes last waits after the whole background, however long it waited`() {
        val now = Instant.now()
        val last =
            Waiter(Work(WorkKind.LIST_VALUES), now.minus(Turns.AGING).minusSeconds(60), emptyMap(), true, false, true)
        val young = Waiter(Work(WorkKind.TAG_VECTORS), now.minusSeconds(1), emptyMap(), true, false)
        val asked = Waiter(Work(WorkKind.TRAIN), now, emptyMap(), true, true)
        assertEquals(listOf(asked, young, last), listOf(last, young, asked).sortedWith(Turns.order(now)))
    }

    @Test
    fun `a work inside another goes on and is not told apart`() {
        val workload = workload()
        workload.run(Work(WorkKind.TRAIN, type = "books"), emptyMap()) {
            workload.run(Work(WorkKind.SCORE, type = "books"), emptyMap()) {}
        }
        assertEquals(listOf(WorkKind.TRAIN), records.runs().map { it.work.what })
    }
}
