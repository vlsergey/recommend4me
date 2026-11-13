package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.TwoSitesPlugins.Alpha
import io.github.vlsergey.recommend4me.TwoSitesPlugins.Beta
import io.github.vlsergey.recommend4me.api.model.JobRequest
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.ScorerChoice
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.SourceMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.FileSystemResource

/**
 * What a site the application scrapes itself has — jobs, settings, the page of a graded work loaded at once — and the
 * sources' own work: kept pages read again, the marks of reviews and pictures, the pictures downloaded, the model.
 */
class TwoSitesSourcesTest : TwoSitesTestBase() {

    @Test
    fun `a scraping job writes the catalogue, remembered running till its end, stopped, goes on after a restart`() {
        assertTrue(jobs.start("beta", SourceMode.SCRAPE_ALL))
        assertTrue(waitFor { jobs.status("beta").newItems == 3 })
        assertEquals("RUNNING", jobsApi.listJobs().body!!.single { it.source == "beta" }.state.name)
        // One job of a source at a time; no job of a source that has no such mode
        assertTrue(!jobs.start("beta", SourceMode.UPDATES))
        assertTrue(!jobs.start("alpha", SourceMode.UPDATES))
        // Remembered as running: a restart goes on with it
        assertEquals(SourceMode.SCRAPE_ALL, jobs.resumable("beta"))
        assertNotNull(items.getItem("beta", "s3").body)

        jobs.cancel("beta")
        assertTrue(waitFor { jobs.status("beta").state.name == "CANCELLED" })
        assertNull(jobs.resumable("beta"), "stopped by the user, it waits for the user")

        // Left running by the last stop of the application: started again on the start
        settings.set("job.beta.running", SourceMode.SCRAPE_ALL.name)
        jobs.resume()
        assertTrue(waitFor { jobs.status("beta").state.name == "RUNNING" })
        jobs.cancel("beta")
        assertTrue(waitFor { jobs.status("beta").state.name == "CANCELLED" })

        // A job of the updates runs to its end and is not remembered
        assertEquals(
            "RUNNING",
            jobsApi
                .startJob("beta", JobRequest(io.github.vlsergey.recommend4me.api.model.SourceMode.UPDATES))
                .body!!
                .state
                .name,
        )
        assertTrue(waitFor(120) { jobs.status("beta").state.name == "DONE" }, "state ${jobs.status("beta")}")
        assertEquals(1, jobs.status("beta").newItems)
        assertNull(jobs.resumable("beta"))
        assertNotNull(items.getItem("beta", "u1").body)
    }

    @Test
    fun `the page of a work graded is loaded at once, when the site has more of it`() {
        beta("b5|Deep|depth|Short")
        gradesController.rateItem("beta", "b5", RatingRequest(5))
        assertTrue(waitFor { "b5" in Beta.refreshed })
        assertTrue(waitFor { items.getItem("beta", "b5").body!!.texts.any { "loaded once graded" in it.content } })
        // A work the site has no more of is not asked for
        beta("x5|Shallow|depth|Short")
        gradesController.rateItem("beta", "x5", RatingRequest(3))
        Thread.sleep(500)
        assertTrue("x5" !in Beta.refreshed)
        gradesController.unrateItem("beta", "b5", null)
        gradesController.unrateItem("beta", "x5", null)
    }

    @Test
    fun `kept pages are read again when the parser changes`() {
        alpha("a7|Quiet harbour|harbour|Boats at rest||")
        assertEquals("Quiet harbour", items.getItem("alpha", "a7").body!!.summary.title)
        Alpha.version = 2
        try {
            captured.reparse()
            assertEquals("QUIET HARBOUR", items.getItem("alpha", "a7").body!!.summary.title)
        } finally {
            Alpha.version = 1
        }
    }

    @Test
    fun `a review and a picture are marked, taken back, and only with a mark of +1 or -1`() {
        alpha("a8|Storm|storm|A storm at sea|cover.png|The storm scene is wonderful")
        assertEquals(204, marksController.markReview("alpha", "a8", "r1", Mark(1)).statusCode.value())
        assertEquals(1, items.getItem("alpha", "a8").body!!.reviews.single().mark)
        assertEquals(204, marksController.unmarkReview("alpha", "a8", "r1").statusCode.value())
        assertNull(items.getItem("alpha", "a8").body!!.reviews.single().mark)
        assertEquals(404, marksController.markReview("alpha", "a8", "no-such", Mark(1)).statusCode.value())
        assertThrows<Refused> { marksController.markReview("alpha", "a8", "r1", Mark(0)) }

        assertEquals(204, marksController.markPicture("alpha", "a8", 0, Mark(-1)).statusCode.value())
        assertEquals(-1, items.getItem("alpha", "a8").body!!.pictures.single().mark)
        marksController.unmarkPicture("alpha", "a8", 0)
        assertNull(items.getItem("alpha", "a8").body!!.pictures.single().mark)
        assertEquals(404, marksController.markPicture("alpha", "a8", 7, Mark(1)).statusCode.value())
    }

    @Test
    fun `a picture is downloaded once and served from the file after, one the host has not is sent to the host`() {
        alpha("a9|Cover|cover|A work with a cover|cover.png|")
        pictureHits.clear()
        val first = items.getPicture("alpha", "a9", 0, false)
        assertEquals(200, first.statusCode.value())
        assertEquals("image/png", first.headers.contentType.toString())
        assertTrue((first.body as FileSystemResource).file.readBytes().contentEquals(PNG))
        assertEquals(200, items.getPicture("alpha", "a9", 0, false).statusCode.value())
        assertEquals(listOf("/cover.png"), pictureHits.toList())
        // The original not kept: the browser is sent to the site for it
        assertEquals(302, items.getPicture("alpha", "a9", 0, true).statusCode.value())

        alpha("a10|Lost cover|cover|A cover the host lost|lost.png|")
        val lost = items.getPicture("alpha", "a10", 0, false)
        assertEquals(302, lost.statusCode.value())
        assertEquals("$pictureBase/lost.png", lost.headers.location.toString())
        assertEquals(404, items.getPicture("alpha", "a10", 3, false).statusCode.value())
    }

    @Test
    fun `a source's settings — a secret never sent back, a default shown, a blank removed, an unknown key refused`() {
        fun beta() =
            settingsApi.getSettings().body!!.single { it.source == "beta" }.propertyValues.associateBy { it.key }
        // A source of no settings of its own has the application's one of a site with tags alone: its tags forbidden to
        // import, in the standard words
        val alpha = settingsApi.getSettings().body!!.single { it.source == "alpha" }.propertyValues
        assertEquals(
            listOf("standard:setting.keptOutTags"),
            alpha.map { it.labelKey },
            "a source of no settings of its own",
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
                "m$k|Work $k|${if (k % 2 == 0) "sea" else "land"}" +
                    "|${if (k % 2 == 0) "Ships and storms at sea" else "Fields and farms on land"} $k||"
            )
            gradesController.rateItem("alpha", "m$k", RatingRequest(if (k % 2 == 0) 5 else 1))
        }
        // Planned at once, not waited for: the request answers with the model as it is
        assertEquals(200, model.trainModel("books").statusCode.value())
        assertTrue(waitFor { model.getModel("books").body!!.trained })
        val trained = model.getModel("books").body!!
        assertTrue(trained.scorers.any { it.id == "pairwise-logistic" })
        assertEquals("pairwise-logistic", model.chooseScorer("books", ScorerChoice("pairwise-logistic")).body!!.scorer)
        assertEquals(
            Refusal.NO_SCORER,
            assertThrows<Refused> { model.chooseScorer("books", ScorerChoice("nope")) }.refusal,
        )
        assertTrue(model.getModel("books").body!!.trained)
        assertEquals(404, model.getModel("nothing").statusCode.value())
        (1..8).forEach { gradesController.unrateItem("alpha", "m$it", null) }
    }
}
