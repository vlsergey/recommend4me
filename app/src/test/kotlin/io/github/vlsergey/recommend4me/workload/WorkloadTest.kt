package io.github.vlsergey.recommend4me.workload

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
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** What the queue of the computations tells of the works done, running and planned. */
class WorkloadTest {

    @Test
    fun `a planned work begins with the reasons it was planned for, and is planned no longer`() {
        val workload = Workload()
        val train = Work("train", type = "books")
        workload.plan(train, Instant.now().plusSeconds(3), "grade")
        workload.plan(train, null, "grade")
        workload.plan(train, null, "answer")
        assertEquals(1, workload.planned().size)
        workload.run(train) {
            assertEquals(mapOf("grade" to 2, "answer" to 1), workload.running()!!.reasons)
        }
        assertTrue(workload.planned().isEmpty())
        assertNull(workload.running())
        assertEquals(mapOf("grade" to 2, "answer" to 1), workload.finished().single().reasons)
    }

    @Test
    fun `a request of the browser never waits for a computation, nor runs one, where a thread of the background does`() {
        val workload = Workload()
        val ran = AtomicInteger()
        RequestContextHolder.setRequestAttributes(
            ServletRequestAttributes(MockHttpServletRequest("GET", "/api/items/s/1/held-out"))
        )
        try {
            assertFailsWith<IllegalStateException> {
                workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
            }
            assertEquals(0, ran.get())
            assertTrue(workload.waiting().isEmpty())
            // The request's attributes are not passed to a thread it starts: the background runs it
            inBackground {
                workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
            }
            assertEquals(1, ran.get())
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
        workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
        assertEquals(2, ran.get())
    }

    @Test
    fun `the last five works done are told, the oldest first, a failed one marked`() {
        val workload = Workload()
        repeat(6) { i -> workload.run(Work("score", type = "t$i"), emptyMap()) {} }
        assertFailsWith<IllegalStateException> { workload.run(Work("score", type = "bad"), emptyMap()) { error("no") } }
        val done = workload.finished()
        assertEquals(listOf("t2", "t3", "t4", "t5", "bad"), done.map { it.work.type })
        assertEquals(listOf(false, false, false, false, true), done.map { it.failed })
    }

    @Test
    fun `a work given up for newer data is no failure, and leaves the estimate of its kind as it was`() {
        val workload = Workload()
        val suggester = Work("suggester", source = "s", facet = "universe")
        workload.run(suggester, emptyMap()) { Thread.sleep(30) }
        val estimate = workload.expected(suggester)
        assertFailsWith<CancellationException> {
            workload.run(suggester, emptyMap()) { throw CancellationException("newer changes") }
        }
        val last = workload.finished().last()
        assertEquals(false to true, last.failed to last.givenUp)
        assertEquals(estimate, workload.expected(suggester))
    }

    @Test
    fun `a work is expected to take as long as the last run of its kind, of any item`() {
        val workload = Workload()
        assertNull(workload.expected(Work("heldOut", source = "s", item = "1")))
        workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { Thread.sleep(30) }
        val expected = workload.expected(Work("heldOut", source = "s", item = "2"))!!
        assertTrue(expected >= 30, "expected $expected ms")
        assertNull(workload.expected(Work("heldOut", source = "other", item = "2")))
    }

    @Test
    fun `works alike asked while one waits are that one, its reasons summed`() {
        val workload = Workload()
        val held = Work("held", item = "1")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val blocker = thread {
            workload.run(Work("train", type = "t"), emptyMap()) {
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
    fun `a work the user asked for waits ahead of the background, the running one not stopped, each kind in the order asked`() {
        val workload = Workload()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val blocker = thread {
            workload.run(Work("train", type = "t"), emptyMap()) {
                started.countDown()
                release.await()
                order += "train"
            }
        }
        started.await()
        val callers = Executors.newFixedThreadPool(4)
        try {
            fun ask(what: String, asked: Boolean, waiting: Int): CompletableFuture<Unit> =
                CompletableFuture.supplyAsync(
                        { workload.run(Work(what), emptyMap(), asked = asked) { order += what } },
                        callers,
                    )
                    .also { while (workload.waiting().size < waiting) Thread.sleep(5) }
            val all =
                listOf(
                    ask("background1", false, 1),
                    ask("background2", false, 2),
                    ask("search", true, 3),
                    ask("heldOut", true, 4),
                )
            // The page of the queue tells the order they will run in, the asked ones marked
            assertEquals(
                listOf("search", "heldOut", "background1", "background2"),
                workload.waiting().map { it.work.what },
            )
            assertEquals(listOf(true, true, false, false), workload.waiting().map { it.asked })
            assertTrue(workload.running()!!.work.what == "train" && !workload.running()!!.asked)
            release.countDown()
            all.forEach { it.get(5, TimeUnit.SECONDS) }
            blocker.join()
            assertEquals(listOf("train", "search", "heldOut", "background1", "background2"), order)
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `a work alike the user asks for while it waits is put ahead with it`() {
        val workload = Workload()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val blocker = thread {
            workload.run(Work("train", type = "t"), emptyMap()) {
                started.countDown()
                release.await()
            }
        }
        started.await()
        val callers = Executors.newFixedThreadPool(4)
        try {
            val first =
                CompletableFuture.supplyAsync({ workload.run(Work("other"), emptyMap()) { order += "other" } }, callers)
            while (workload.waiting().size < 1) Thread.sleep(5)
            val duplicates = Work("duplicates", type = "t")
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
    fun `a work inside another goes on and is not told apart`() {
        val workload = Workload()
        workload.run(Work("train", type = "books"), emptyMap()) {
            workload.run(Work("score", type = "books"), emptyMap()) {}
        }
        assertEquals(listOf("train"), workload.finished().map { it.work.what })
    }
}
