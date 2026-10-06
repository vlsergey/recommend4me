package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.api.CaptureApi
import io.github.vlsergey.recommend4me.api.model.CapturePatterns
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.CaptureResult
import io.github.vlsergey.recommend4me.api.model.CapturedPageInfo
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

@RestController
class CaptureController(private val captures: Captures, private val stores: Stores) : CaptureApi {

    override fun getCapturePatterns(): ResponseEntity<List<CapturePatterns>> =
        ResponseEntity.ok(captures.patterns().map { (source, patterns) -> CapturePatterns(source, patterns) })

    override fun capturePage(captureRequest: CaptureRequest): ResponseEntity<CaptureResult> {
        val captured = captures.capture(captureRequest.url, captureRequest.html)
        val items = captured.items.mapNotNull { key ->
            stores.source(key.source)?.items?.find(key.id)?.let { LinkedItem(key.source, key.id, it.title, it.url) }
        }
        return ResponseEntity.ok(CaptureResult(items, captured.source))
    }

    override fun recentCaptures(): ResponseEntity<List<CapturedPageInfo>> =
        ResponseEntity.ok(captures.recent(RECENT).map { (source, page) ->
            CapturedPageInfo(
                source = source, url = page.url, capturedAt = page.capturedAt.atOffset(ZoneOffset.UTC), item = page.itemId,
                title = page.itemId?.let { stores.source(source)?.items?.find(it)?.title },
            )
        })

    companion object {
        private const val RECENT = 30
    }
}
