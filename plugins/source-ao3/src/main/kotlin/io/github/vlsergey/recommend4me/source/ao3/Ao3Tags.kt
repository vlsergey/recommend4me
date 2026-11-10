package io.github.vlsergey.recommend4me.source.ao3

import java.net.URLEncoder

/**
 * THE ADDRESS OF A TAG OF THE SITE BY ITS NAME, as the site writes it: the characters its addresses cannot hold made
 * its own marks — "/" "*s*", "&" "*a*", "." "*d*", "?" "*q*", "#" "*h*" — the rest encoded, a space "%20".
 */
internal object Ao3Tags {
    private val MARKS = listOf("/" to "*s*", "&" to "*a*", "." to "*d*", "?" to "*q*", "#" to "*h*")

    fun path(name: String): String =
        URLEncoder.encode(MARKS.fold(name) { s, (c, mark) -> s.replace(c, mark) }, Charsets.UTF_8).replace("+", "%20")
}
