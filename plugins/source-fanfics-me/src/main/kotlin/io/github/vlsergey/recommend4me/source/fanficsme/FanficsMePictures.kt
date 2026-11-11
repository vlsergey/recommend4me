package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.SitePictures
import io.github.vlsergey.recommend4me.source.SourceContext

/** The covers of fanfics.me: of the larger first, downloaded saying what asks, as every request of the site is. */
internal object FanficsMePictures : SitePictures {

    /** A cover as the site lists it — "…_200_300.jpg" — and its original, the larger first. */
    override fun pictureAddresses(url: String): List<String> = listOf(url.replace(PREVIEW_SIZE, ""), url).distinct()

    override fun pictureHeaders(url: String, context: SourceContext): Map<String, String> =
        mapOf("User-Agent" to FanficsMeClient.USER_AGENT)

    /** The size a cover is listed by, after its name. */
    private val PREVIEW_SIZE = Regex("_\\d+_\\d+(?=\\.\\w+$)")
}
