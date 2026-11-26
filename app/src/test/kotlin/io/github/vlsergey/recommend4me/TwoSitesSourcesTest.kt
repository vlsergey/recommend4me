package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.TwoSitesPlugins.Alpha
import io.github.vlsergey.recommend4me.TwoSitesPlugins.Beta
import io.github.vlsergey.recommend4me.api.model.JobRequest
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.ScorerChoice
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.PageParser
import io.github.vlsergey.recommend4me.source.SourceMode
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.FileSystemResource

/**
 * What a site the application scrapes itself has — jobs, settings, nothing asked of the site for a grade — and the
 * sources' own work: kept pages read again, the marks of reviews and pictures, the pictures downloaded, the model.
 */
// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class TwoSitesSourcesTest : TwoSitesTestBase() {

    @Test
    fun `a scraping job writes the catalogue, remembered running till its end, stopped, goes on after a restart`() {
        // A job started runs in the background: here, when the test runs it
        assertTrue(jobs.start("beta", SourceMode.SCRAPE_ALL))
        assertEquals("RUNNING", jobsApi.listJobs().body!!.single { it.source == "beta" }.state.name)
        // One job of a source at a time; no job of a source that has no such mode
        assertTrue(!jobs.start("beta", SourceMode.UPDATES))
        assertTrue(!jobs.start("alpha", SourceMode.UPDATES))
        // Remembered as running: a restart goes on with it
        assertEquals(SourceMode.SCRAPE_ALL, jobs.resumable("beta"))

        // Stopped by the user: it writes what it read, and stops at its next look
        jobs.cancel("beta")
        background.run()
        assertEquals(3, jobs.status("beta").newItems)
        assertNotNull(items.getItem("beta", idOf("beta", "s3")).body)
        assertEquals("CANCELLED", jobs.status("beta").state.name)
        assertNull(jobs.resumable("beta"), "stopped by the user, it waits for the user")

        // Left running by the last stop of the application: started again on the start
        settings.set("job.beta.running", SourceMode.SCRAPE_ALL.name)
        jobs.resume()
        assertEquals("RUNNING", jobs.status("beta").state.name)
        jobs.cancel("beta")
        background.run()
        assertEquals("CANCELLED", jobs.status("beta").state.name)

        // A job of the updates runs to its end and is not remembered
        assertEquals(
            "RUNNING",
            jobsApi
                .startJob("beta", JobRequest(io.github.vlsergey.recommend4me.api.model.SourceMode.UPDATES))
                .body!!
                .state
                .name,
        )
        background.run()
        assertEquals("DONE", jobs.status("beta").state.name, "state ${jobs.status("beta")}")
        assertEquals(1, jobs.status("beta").newItems)
        assertNull(jobs.resumable("beta"))
        assertNotNull(items.getItem("beta", idOf("beta", "u1")).body)
    }

    @Test
    fun `a work graded asks the site for nothing — its page is loaded on the user's press alone`() {
        beta("b5|Deep|depth|Short")
        gradesController.rateItem("beta", idOf("beta", "b5"), RatingRequest(5))
        background.run()
        assertTrue(idOf("beta", "b5") !in Beta.refreshed)
        gradesController.unrateItem("beta", idOf("beta", "b5"), null)
    }

    @Test
    fun `kept pages are read again when the parser changes`() {
        alpha("a7|Quiet harbour|harbour|Boats at rest||")
        assertEquals("Quiet harbour", items.getItem("alpha", idOf("alpha", "a7")).body!!.summary.title)
        Alpha.version = 2
        try {
            captured.reparse()
            assertEquals("QUIET HARBOUR", items.getItem("alpha", idOf("alpha", "a7")).body!!.summary.title)
        } finally {
            Alpha.version = 1
        }
    }

    /**
     * The site changed the form of its pages: a page downloaded before it is read by the way of its day, and read again
     * only when that way is another than the one that read it — not when a way of later pages changes.
     */
    @Test
    fun `a kept page is read again by the way of reading of the day it was downloaded`() {
        alpha("a10|Calm bay|harbour|Boats at rest||")
        assertEquals("Calm bay", items.getItem("alpha", idOf("alpha", "a10")).body!!.summary.title)
        val downloaded = Instant.now()
        try {
            // A way of the pages downloaded from tomorrow: the page of today is not its own, read by the way of its day
            Alpha.ways = listOf(PageParser(null, 1), PageParser(downloaded.plus(Duration.ofDays(1)), 2))
            captured.reparse()
            assertEquals("Calm bay", items.getItem("alpha", idOf("alpha", "a10")).body!!.summary.title)
            // A way of the pages downloaded since yesterday: the page of today is its own, read by it again
            Alpha.ways = listOf(PageParser(null, 1), PageParser(downloaded.minus(Duration.ofDays(1)), 2))
            captured.reparse()
            assertEquals("CALM BAY", items.getItem("alpha", idOf("alpha", "a10")).body!!.summary.title)
        } finally {
            Alpha.ways = null
            captured.reparse()
        }
    }

    @Test
    fun `a review and a picture are marked, taken back, and only with a mark of +1 or -1`() {
        alpha("a8|Storm|storm|A storm at sea|cover.png|The storm scene is wonderful")
        assertEquals(204, marksController.markReview("alpha", idOf("alpha", "a8"), "r1", Mark(1)).statusCode.value())
        assertEquals(1, items.getItem("alpha", idOf("alpha", "a8")).body!!.reviews.single().mark)
        assertEquals(204, marksController.unmarkReview("alpha", idOf("alpha", "a8"), "r1").statusCode.value())
        assertNull(items.getItem("alpha", idOf("alpha", "a8")).body!!.reviews.single().mark)
        assertEquals(
            404,
            marksController.markReview("alpha", idOf("alpha", "a8"), "no-such", Mark(1)).statusCode.value(),
        )
        assertThrows<Refused> { marksController.markReview("alpha", idOf("alpha", "a8"), "r1", Mark(0)) }

        assertEquals(204, marksController.markPicture("alpha", idOf("alpha", "a8"), 0, Mark(-1)).statusCode.value())
        assertEquals(-1, items.getItem("alpha", idOf("alpha", "a8")).body!!.pictures.single().mark)
        marksController.unmarkPicture("alpha", idOf("alpha", "a8"), 0)
        assertNull(items.getItem("alpha", idOf("alpha", "a8")).body!!.pictures.single().mark)
        assertEquals(404, marksController.markPicture("alpha", idOf("alpha", "a8"), 7, Mark(1)).statusCode.value())
    }

    @Test
    fun `a picture is downloaded once and served from the file after, one the host has not is sent to the host`() {
        alpha("a9|Cover|cover|A work with a cover|cover.png|")
        pictureHits.clear()
        val first = items.getPicture("alpha", idOf("alpha", "a9"), 0, false)
        assertEquals(200, first.statusCode.value())
        assertEquals("image/png", first.headers.contentType.toString())
        assertTrue((first.body as FileSystemResource).file.readBytes().contentEquals(PNG))
        assertEquals(200, items.getPicture("alpha", idOf("alpha", "a9"), 0, false).statusCode.value())
        assertEquals(listOf("/cover.png"), pictureHits.toList())
        // The original not kept: the browser is sent to the site for it
        assertEquals(302, items.getPicture("alpha", idOf("alpha", "a9"), 0, true).statusCode.value())

        alpha("a10|Lost cover|cover|A cover the host lost|lost.png|")
        val lost = items.getPicture("alpha", idOf("alpha", "a10"), 0, false)
        assertEquals(302, lost.statusCode.value())
        assertEquals("$pictureBase/lost.png", lost.headers.location.toString())
        assertEquals(404, items.getPicture("alpha", idOf("alpha", "a10"), 3, false).statusCode.value())
    }

    @Test
    fun `a source's settings — a secret never sent back, a default shown, a blank removed, an unknown key refused`() {
        fun beta() =
            settingsApi.getSettings().body!!.single { it.source == "beta" }.propertyValues.associateBy { it.key }
        assertTrue(
            settingsApi.getSettings().body!!.none { it.source == "alpha" },
            "a source without settings is not listed",
        )
        assertEquals(false, beta().getValue("cookie").set)
        assertEquals("5", beta().getValue("delay").value)
        assertEquals("beta:setting.cookie", beta().getValue("cookie").labelKey)

        settingsApi.updateSourceSettings("beta", mapOf("cookie" to " session=1 ", "delay" to "7"))
        assertEquals(true, beta().getValue("cookie").set)
        assertNull(beta().getValue("cookie").value)
        assertEquals("session=1", settings.of("beta").get("cookie"))
        assertEquals("7", beta().getValue("delay").value)

        settingsApi.updateSourceSettings("beta", mapOf("delay" to "  "))
        assertEquals("5", beta().getValue("delay").value)
        val unknown = assertThrows<Refused> { settingsApi.updateSourceSettings("beta", mapOf("nope" to "1")) }
        assertEquals(Refusal.NO_SETTING, unknown.refusal)
        assertEquals(mapOf("setting" to "nope"), unknown.params)
        assertEquals(404, settingsApi.updateSourceSettings("nobody", emptyMap()).statusCode.value())
    }

    @Test
    fun `the model of a type — trained on demand, its scorer chosen, an unknown scorer refused`() {
        (1..8).forEach { k ->
            alpha(
                "m$k|Task $k|${if (k % 2 == 0) "sea" else "land"}" +
                    "|${if (k % 2 == 0) "Ships and storms at sea" else "Fields and farms on land"} $k||"
            )
            gradesController.rateItem("alpha", idOf("alpha", "m$k"), RatingRequest(if (k % 2 == 0) 5 else 1))
        }
        // Planned at once, not waited for: the request answers with the model as it is
        assertEquals(200, model.trainModel("books").statusCode.value())
        background.run()
        assertTrue(model.getModel("books").body!!.trained)
        val trained = model.getModel("books").body!!
        assertTrue(trained.scorers.any { it.id == "pairwise-logistic" })
        assertEquals("pairwise-logistic", model.chooseScorer("books", ScorerChoice("pairwise-logistic")).body!!.scorer)
        assertEquals(
            Refusal.NO_SCORER,
            assertThrows<Refused> { model.chooseScorer("books", ScorerChoice("nope")) }.refusal,
        )
        assertTrue(model.getModel("books").body!!.trained)
        assertEquals(404, model.getModel("nothing").statusCode.value())
        (1..8).forEach { gradesController.unrateItem("alpha", idOf("alpha", "m$it"), null) }
    }
}
