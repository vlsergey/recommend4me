package io.github.vlsergey.recommend4me.capture

import org.jsoup.Jsoup

/**
 * What a page sent by the extension must not keep: its scripts and the tokens of the user's session
 * on the site — AO3's `<meta name="csrf-token">` and the `authenticity_token` of its forms, the
 * `__RequestVerificationToken` of author.today. A kept page lies in the source database and in
 * every backup of it; no parser reads any of these.
 */
object PageSecrets {

    private const val SECRETS = "script, meta[name~=(?i)csrf|token], input[name~=(?i)csrf|token]"

    /** The page without them; the page itself, unchanged, when it has none. */
    fun stripped(html: String): String {
        val doc = Jsoup.parse(html)
        val found = doc.select(SECRETS)
        if (found.isEmpty()) return html
        found.remove()
        // Written as it was: a chapter's text keeps its line breaks and spaces
        doc.outputSettings().prettyPrint(false)
        return doc.outerHtml()
    }
}
