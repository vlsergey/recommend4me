package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.api.model.WorkScope
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** What the queue of the computations tells of the works done, running and planned. */
class TasksTest {
    @TempDir lateinit var folder: Path

    private val opened = ArrayList<AppDatabase>()

    /** The records of the queue made last. */
    private lateinit var records: QueuedTasks

    /** A queue kept in a database of its own, of the test's folder. */
    private fun tasks(): Tasks {
        val app = AppDatabase(DataFolder(folder.resolve("run${opened.size}").toString())).also { opened += it }
        records = QueuedTasks(app)
        return Tasks(records = records)
    }

    @AfterEach fun close() = opened.forEach { it.destroy() }

    @Test
    fun `a planned work begins with the reasons it was planned for, and is planned no longer`() {
        val tasks = tasks()
        val train = Task(TaskKind.FIT_GRADE_MODELS, type = "books")
        val runs = AtomicInteger()
        tasks.plan(train, Instant.now().plusSeconds(60), "grade") { runs.incrementAndGet() }
        tasks.plan(train, null, "grade") { runs.incrementAndGet() }
        tasks.plan(train, null, "answer") { runs.incrementAndGet() }
        assertEquals(1, tasks.planned().size)
        tasks.run(train) {
            assertEquals(mapOf("grade" to 2, "answer" to 1), tasks.running()!!.reasons)
        }
        assertTrue(tasks.planned().isEmpty())
        assertNull(tasks.running())
        assertEquals(mapOf("grade" to 2, "answer" to 1), records.runs().single().reasons)
        // Run by its caller, the plan is not run again
        Thread.sleep(50)
        assertEquals(0, runs.get())
    }

    @Test
    fun `a work planned again and again before it is due runs once, the newer body, then what it told`() {
        val tasks = tasks()
        val sizes = Task(TaskKind.MEASURE_WORK_SIZES, type = "books")
        val ran = java.util.Collections.synchronizedList(ArrayList<String>())
        val told = CountDownLatch(1)
        repeat(3) { i ->
            tasks.plan(
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
        val tasks = tasks()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val blocker = thread {
            tasks.run(Task(TaskKind.FIT_GRADE_MODELS, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
            }
        }
        started.await()
        val vectors = Task(TaskKind.ENCODE_TAG_NAMES)
        val runs = AtomicInteger()
        tasks.plan(vectors, null, "start") { runs.incrementAndGet() }
        while (tasks.waiting().isEmpty()) Thread.sleep(5)
        tasks.plan(vectors, null, "tags") { runs.incrementAndGet() }
        assertTrue(tasks.planned().isEmpty())
        assertEquals(mapOf("start" to 1, "tags" to 1), tasks.waiting().single().reasons)
        release.countDown()
        blocker.join()
        while (tasks.due(vectors)) Thread.sleep(5)
        assertEquals(1, runs.get())
    }

    @Test
    fun `a failure of a planned work is told, the next planned runs all the same`() {
        val tasks = tasks()
        val done = CountDownLatch(1)
        tasks.plan(Task(TaskKind.MAKE_WORK_MANNERS), null, "start") { error("no") }
        tasks.plan(Task(TaskKind.MEASURE_WORK_SIZES), Instant.now().plusMillis(50), "start") { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        while (records.runs().size < 2) Thread.sleep(5)
        assertEquals(listOf(true, false), records.runs().map { it.failed })
    }

    @Test
    fun `a request of the browser never waits for a computation, nor runs one, as a background thread does`() {
        val tasks = tasks()
        val ran = AtomicInteger()
        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(MockHttpServletRequest("GET", "/api/items/s/1/held-out"))
        )
        try {
            assertFailsWith<IllegalStateException> {
                tasks.run(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "s", item = 1), emptyMap()) {
                    ran.incrementAndGet()
                }
            }
            assertEquals(0, ran.get())
            assertTrue(tasks.waiting().isEmpty())
            // The request's attributes are not passed to a thread it starts: the background runs it
            inBackground {
                tasks.run(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "s", item = 1), emptyMap()) {
                    ran.incrementAndGet()
                }
            }
            assertEquals(1, ran.get())
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
        tasks.run(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "s", item = 1), emptyMap()) { ran.incrementAndGet() }
        assertEquals(2, ran.get())
    }

    @Test
    fun `the last five works done are told, the oldest first, a failed one marked`() {
        val tasks = tasks()
        repeat(6) { i -> tasks.run(Task(TaskKind.PREDICT_WORKS, type = "t$i"), emptyMap()) {} }
        assertFailsWith<IllegalStateException> {
            tasks.run(Task(TaskKind.PREDICT_WORKS, type = "bad"), emptyMap()) { error("no") }
        }
        val done = records.runs()
        assertEquals(listOf("t2", "t3", "t4", "t5", "bad"), done.map { it.work.type })
        assertEquals(listOf(false, false, false, false, true), done.map { it.failed })
    }

    @Test
    fun `a work given up for newer data is no failure, and leaves the estimate of its kind as it was`() {
        val tasks = tasks()
        val suggester = Task(TaskKind.FIT_FIELD_MODELS, source = "s", facet = "universe")
        tasks.run(suggester, emptyMap()) { Thread.sleep(30) }
        val estimate = records.expected(suggester)
        assertFailsWith<CancellationException> {
            tasks.run(suggester, emptyMap()) { throw CancellationException("newer changes") }
        }
        val last = records.runs().last()
        assertEquals(false to true, last.failed to last.givenUp)
        assertEquals(estimate, records.expected(suggester))
    }

    @Test
    fun `a work is expected to take as long as the last run of its kind, of any item`() {
        val tasks = tasks()
        assertNull(records.expected(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "s", item = 1)))
        tasks.run(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "s", item = 1), emptyMap()) { Thread.sleep(30) }
        val expected = records.expected(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "s", item = 2))!!
        assertTrue(expected >= 30, "expected $expected ms")
        assertNull(records.expected(Task(TaskKind.FIT_HELD_OUT_MODEL, source = "other", item = 2)))
    }

    @Test
    fun `works alike asked while one waits are that one, its reasons summed`() {
        val tasks = tasks()
        val held = Task(TaskKind.ENCODE_TAG_NAMES, item = 1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val blocker = thread {
            tasks.run(Task(TaskKind.FIT_GRADE_MODELS, type = "t"), emptyMap()) {
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
                            tasks.run(held, mapOf("page" to 1)) {
                                runs.incrementAndGet()
                                "answer"
                            }
                        },
                        callers,
                    )
                }
            while (tasks.waiting().singleOrNull()?.reasons?.get("page") != 3) Thread.sleep(5)
            // A batch of its own is not joined
            val own =
                CompletableFuture.supplyAsync(
                    {
                        tasks.run(held, mapOf("items" to 1), joinAlike = false) {
                            runs.incrementAndGet()
                            "own"
                        }
                    },
                    callers,
                )
            while (tasks.waiting().size < 2) Thread.sleep(5)
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
        val tasks = tasks()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val blocker = thread {
            tasks.run(Task(TaskKind.FIT_GRADE_MODELS, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
                order += "fitGradeModels"
            }
        }
        started.await()
        val callers = Executors.newFixedThreadPool(4)
        try {
            fun ask(what: TaskKind, asked: Boolean, waiting: Int): CompletableFuture<Unit> =
                CompletableFuture.supplyAsync(
                        {
                            tasks.run(Task(what), emptyMap(), priority = TaskPriority.ASKED.takeIf { asked }) {
                                order += what.value
                            }
                        },
                        callers,
                    )
                    .also { while (tasks.waiting().size < waiting) Thread.sleep(5) }
            val background =
                listOf(ask(TaskKind.MAKE_WORK_MANNERS, false, 1), ask(TaskKind.MEASURE_WORK_SIZES, false, 2))
            // Works of the background waiting: the one running is not asked to give way
            assertFalse(tasks.outranked)
            val all =
                background + listOf(ask(TaskKind.INDEX_WORK_TEXTS, true, 3), ask(TaskKind.FIT_HELD_OUT_MODEL, true, 4))
            // A work the user asked for waiting: the one running, of the background, is asked to give way — and gives
            // it only if it can stop (owner, 2026-11-20)
            assertTrue(tasks.outranked)
            // The page of the queue tells the order they will run in, the asked ones marked
            assertEquals(
                listOf("indexWorkTexts", "fitHeldOutModel", "makeWorkManners", "measureWorkSizes"),
                tasks.waiting().map { it.work.what.value },
            )
            assertEquals(listOf(true, true, false, false), tasks.waiting().map { it.priority == TaskPriority.ASKED })
            assertTrue(
                tasks.running()!!.work.what == TaskKind.FIT_GRADE_MODELS &&
                    tasks.running()!!.priority == TaskPriority.BACKGROUND
            )
            release.countDown()
            all.forEach { it.get(5, TimeUnit.SECONDS) }
            blocker.join()
            assertEquals(
                listOf("fitGradeModels", "indexWorkTexts", "fitHeldOutModel", "makeWorkManners", "measureWorkSizes"),
                order,
            )
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `a work alike the user asks for while it waits is put ahead with it`() {
        val tasks = tasks()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val blocker = thread {
            tasks.run(Task(TaskKind.FIT_GRADE_MODELS, type = "t"), emptyMap()) {
                started.countDown()
                release.await()
            }
        }
        started.await()
        val callers = Executors.newFixedThreadPool(4)
        try {
            val first =
                CompletableFuture.supplyAsync(
                    { tasks.run(Task(TaskKind.MAKE_SITE_ITEM_SET_VECTORS), emptyMap()) { order += "other" } },
                    callers,
                )
            while (tasks.waiting().size < 1) Thread.sleep(5)
            val duplicates = Task(TaskKind.SEARCH_DUPLICATES, type = "t")
            val background =
                CompletableFuture.supplyAsync(
                    { tasks.run(duplicates, mapOf("start" to 1)) { order += "searchDuplicates" } },
                    callers,
                )
            while (tasks.waiting().size < 2) Thread.sleep(5)
            val pressed =
                CompletableFuture.supplyAsync(
                    {
                        tasks.run(duplicates, mapOf("asked" to 1), priority = TaskPriority.ASKED) {
                            order += "searchDuplicates"
                        }
                    },
                    callers,
                )
            while (tasks.waiting().firstOrNull()?.work != duplicates) Thread.sleep(5)
            release.countDown()
            listOf(first, background, pressed).forEach { it.get(5, TimeUnit.SECONDS) }
            blocker.join()
            assertEquals(listOf("searchDuplicates", "other"), order)
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `the tasks go by their priorities alone, however long one of a lower one waited`() {
        val now = Instant.now()
        fun waiter(what: TaskKind, waited: Long, priority: TaskPriority, works: WorkScope? = null) =
            Waiter(Task(what, works = works), now.minusSeconds(waited), emptyMap(), true, priority)
        val other = waiter(TaskKind.PREDICT_WORKS, HOUR, TaskPriority.OTHER_WORKS, WorkScope.OTHER)
        val marked = waiter(TaskKind.PREDICT_WORKS, 4, TaskPriority.MARKED_WORKS, WorkScope.MARKED)
        val background = waiter(TaskKind.ENCODE_TAG_NAMES, HOUR, TaskPriority.BACKGROUND)
        val user = waiter(TaskKind.WRITE_LIST_INDEX, 2, TaskPriority.USER)
        val asked = waiter(TaskKind.FIT_GRADE_MODELS, 1, TaskPriority.ASKED)
        assertEquals(
            listOf(asked, user, background, marked, other),
            listOf(other, marked, background, user, asked).sortedWith(Turns.ORDER),
        )
    }

    @Test
    fun `a task planned by a running one is of its priority, unless it names one, any other of the background`() {
        val tasks = tasks()
        val done = CountDownLatch(1)
        val later = Instant.now().plusSeconds(60)
        tasks.plan(Task(TaskKind.MEASURE_WORK_SIZES), null, "start", priority = TaskPriority.USER) {
            tasks.plan(Task(TaskKind.MAKE_WORK_MANNERS), later, "items") {}
            tasks.plan(Task(TaskKind.ENCODE_TAG_NAMES), later, "tags", priority = TaskPriority.OTHER_WORKS) {}
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        tasks.plan(Task(TaskKind.COUNT_TAGS), later, "tags") {}
        assertEquals(
            mapOf(
                TaskKind.MAKE_WORK_MANNERS to TaskPriority.USER,
                TaskKind.ENCODE_TAG_NAMES to TaskPriority.OTHER_WORKS,
                TaskKind.COUNT_TAGS to TaskPriority.BACKGROUND,
            ),
            tasks.planned().associate { it.work.what to it.priority },
        )
    }

    @Test
    fun `a task over the works goes on from where it left, of its priority`() {
        val tasks = tasks()
        val scores = Task(TaskKind.PREDICT_WORKS, type = "books", works = WorkScope.OTHER)
        val seen = java.util.Collections.synchronizedList(ArrayList<Pair<TaskCursor?, TaskPriority>>())
        val done = CountDownLatch(2)
        tasks.plan(scores, null, "sweep", priority = TaskPriority.OTHER_WORKS, cursor = TaskCursor.FIRST) {
            val running = tasks.running()!!
            seen += running.cursor to running.priority
            if (seen.size == 1) tasks.again("sweep", cursor = TaskCursor(4.0, "w9"))
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(TaskCursor.FIRST to TaskPriority.OTHER_WORKS, TaskCursor(4.0, "w9") to TaskPriority.OTHER_WORKS),
            seen,
        )
    }

    @Test
    fun `of two cursors the earlier is the one of the higher grade, of one grade the smaller key`() {
        assertEquals(TaskCursor(4.5, "b"), TaskCursor.earlier(TaskCursor(4.0, "a"), TaskCursor(4.5, "b")))
        assertEquals(TaskCursor(4.0, "a"), TaskCursor.earlier(TaskCursor(4.0, "b"), TaskCursor(4.0, "a")))
        assertEquals(TaskCursor.FIRST, TaskCursor.earlier(TaskCursor(9.0, "a"), TaskCursor.FIRST))
        val none = TaskCursor(Double.NEGATIVE_INFINITY, "a")
        assertEquals(TaskCursor(1.0, "z"), TaskCursor.earlier(none, TaskCursor(1.0, "z")))
        assertEquals(none, TaskCursor.earlier(none, null))
    }

    @Test
    fun `a work inside another goes on and is not told apart`() {
        val tasks = tasks()
        tasks.run(Task(TaskKind.FIT_GRADE_MODELS, type = "books"), emptyMap()) {
            tasks.run(Task(TaskKind.PREDICT_WORKS, type = "books"), emptyMap()) {}
        }
        assertEquals(listOf(TaskKind.FIT_GRADE_MODELS), records.runs().map { it.work.what })
    }

    private companion object {
        /** A wait of an hour, in seconds: longer than any a task of a higher priority makes. */
        const val HOUR = 3600L
    }
}
