package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Siglip2EncoderTest {

    // gradlew test -Pgpu checks the vectors made on the card
    private val siglip = Siglip2Encoder(Onnx(defaultModelsDir(), gpu = System.getProperty("recommend4me.gpu").toBoolean()))
    private val check = defaultModelsDir().resolve("siglip2_naflex_check")

    @Test
    fun `the size keeps the proportions within 256 patches`() {
        // A 1600×400 banner: 4:1 stays 4:1
        assertEquals(128 to 512, Siglip2Encoder.sizeFor(400, 1600))
        val (h, w) = Siglip2Encoder.sizeFor(360, 640)
        assertTrue((h / 16) * (w / 16) <= 256)
        assertEquals(640.0 / 360, w.toDouble() / h, 0.15)
    }

    @Test
    fun `vectors are those of the reference pipeline`() {
        assumeTrue(siglip.ready() && Files.isDirectory(check), "SigLIP2 NaFlex is not exported into the models folder")
        val reference = JsonMapper.builder().build().readTree(check.resolve("vectors.json").toFile())
        val names = listOf("wide.png", "tall.png")
        val prepared = names.map { siglip.prepare(ImageIO.read(check.resolve(it).toFile())) }
        // The patch grids are those the processor chose
        reference.path("spatial_shapes").values().forEachIndexed { i, shape ->
            assertEquals(shape.get(0).asInt() to shape.get(1).asInt(), prepared[i].rows to prepared[i].cols)
        }
        val vectors = siglip.embed(prepared)
        names.forEachIndexed { i, name ->
            val expected = reference.path(name).values().map { it.asDouble() }
            val cos = expected.indices.sumOf { expected[it] * vectors[i][it] }
            assertTrue(cos > 0.999, "$name: cosine $cos")
        }
    }
}
