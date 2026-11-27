package io.github.vlsergey.recommend4me.onnx

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.opentest4j.AssertionFailedError

/** The check of the memory a plugin tells of a run against what was measured fails as soon as the measure is stale. */
class RunMemoryRecordTest {
    private val mb = 1L shl 20
    private val shape = RunShape(4, 100)

    /** 64 MB once, 100 KB a piece: 103 MB of [shape], the largest batch. */
    private val batching =
        Batching(
            cardRows = 4,
            cpuRows = 1,
            page = 100,
            memory = RunMemory(64 * mb, perPiece = 100L shl 10, perPair = 0),
        )

    private val record =
        RunMemoryRecord(
            runtime = "1.20.0",
            model = "model.onnx 1000 abcd",
            weights = 1000 * mb,
            measured = mapOf(shape to 80 * mb, RunShape(2, 100) to 50 * mb),
        )

    private fun check(
        record: RunMemoryRecord? = this.record,
        runtime: String = "1.20.0",
        model: String? = "model.onnx 1000 abcd",
        batching: Batching = this.batching,
        shapes: List<RunShape> = listOf(RunShape(2, 100)),
    ) = RunMemoryRecord.checkRecord("graph", record, runtime, model, batching, shapes)

    private fun fails(why: String, check: () -> Unit) =
        assertContains(assertFailsWith<AssertionFailedError> { check() }.message.orEmpty(), why)

    @Test
    fun `a measure of this runtime and model the memory told bounds passes`() {
        check()
        // Without the model, in CI, the runtime and the numbers are checked still
        check(model = null)
    }

    @Test
    fun `a measure is stale of another runtime, another model, a shape not measured`() {
        fails("never measured") { check(record = null) }
        fails("another ONNX Runtime") { check(runtime = "1.22.0") }
        fails("another model file") { check(model = "model.onnx 1000 ffff") }
        fails("not measured of the shapes [1x50]") { check(shapes = listOf(RunShape(1, 50))) }
        // A larger batch than was measured: the plugin's largest is measured of itself
        fails("not measured of the shapes [8x100]") { check(batching = Batching(8, 1, 100, batching.memory)) }
    }

    @Test
    fun `the memory told below a measure or far above it fails`() {
        val under = Batching(4, 1, 100, RunMemory(0, perPiece = 100L shl 10, perPair = 0))
        fails("takes 80 MB, told 39") { check(batching = under) }
        val over = Batching(4, 1, 100, RunMemory(400 * mb, perPiece = 100L shl 10, perPair = 0))
        fails("too many") { check(batching = over) }
    }

    @Test
    fun `a record is read as it is written, in megabytes rounded up`() {
        val read = RunMemoryRecord.parse(record.text())
        assertEquals(record.runtime, read.runtime)
        assertEquals(record.model, read.model)
        assertEquals(record.measured, read.measured)
        val odd = RunMemoryRecord(record.runtime, record.model, mb + 1, mapOf(shape to mb + 1))
        assertEquals(2 * mb, RunMemoryRecord.parse(odd.text()).measured.getValue(shape))
    }
}
