package io.github.vlsergey.recommend4me.source.aff

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jsoup.nodes.Document

/**
 * THE CATEGORIES OF THE ARCHIVES' TREES AND THEIR PAGES. A fandom is a node of its archive's tree, written on a work's
 * card by its name alone ([AffFields.located]); its page, "index.php?cat=3", is linked by the pages over it — the grid
 * of an archive's or a category's subcategories, the breadcrumbs of a category's page. Every such link a page shows is
 * kept here, in the plugin's own table, by its archive and its name: a fandom's page is the one of its name kept, when
 * exactly one is ("General" is a subcategory of every fandom).
 */
internal class AffCategories(private val db: DSLContext) {

    /** The categories the page [doc] links, kept where new. */
    fun learn(doc: Document) {
        val told =
            doc.select("div.categories-grid a[href], div.breadcrumb a[href]")
                .mapNotNull { a ->
                    val link = CATEGORY_PAGE.find(a.absUrl("href"))?.groupValues
                    val name = AffFields.nodeName(a.ownText())
                    val cat = link?.get(2)?.toIntOrNull()
                    if (link == null || cat == null || name.isEmpty()) null else Triple(link[1], name, cat)
                }
                .toSet()
        if (told.isEmpty()) return
        val kept =
            db.select(ARCHIVE, NAME, CAT).from(TABLE).where(NAME.`in`(told.map { it.second })).fetchSet {
                Triple(it.value1()!!, it.value2()!!, it.value3()!!)
            }
        (told - kept).forEach { (archive, name, cat) ->
            db.insertInto(TABLE, ARCHIVE, NAME, CAT).values(archive, name, cat).execute()
        }
    }

    /** The page's number of the category [name] of [archive]: the one of its name kept, null of none or several. */
    fun pageOf(archive: String, name: String): Int? =
        db.select(CAT).from(TABLE).where(ARCHIVE.eq(archive), NAME.eq(name)).fetch { it.value1()!! }.singleOrNull()

    companion object {
        /** The list of an archive's works, its categories over it. */
        fun archivePage(archive: String) = "https://$archive.${Aff.DOMAIN}/"

        /**
         * The page of the fandom [key], "hp/Harry Potter": its category's where a page linked it — of the categories
         * [kept], none before the application is up — else its archive's; null of a key of no archive.
         */
        fun fandomPage(key: String, kept: AffCategories?): String? {
            val archive = AffFields.archiveOfFandom(key) ?: return null
            val cat = kept?.pageOf(archive, key.substringAfter('/'))
            return if (cat == null) archivePage(archive) else "${archivePage(archive)}index.php?cat=$cat"
        }

        private val TABLE = DSL.table("AFF_CATEGORY")
        private val ARCHIVE = DSL.field("ARCHIVE", String::class.java)
        private val NAME = DSL.field("NAME", String::class.java)
        private val CAT = DSL.field("CAT", Int::class.javaObjectType)

        /** A category's page, "https://hp.adult-fanfiction.org/index.php?cat=3": its archive and its number. */
        private val CATEGORY_PAGE =
            Regex("^https://([a-z0-9]+)\\.adult-fanfiction\\.org/(?:index\\.php)?\\?(?:[^#]*&)?cat=(\\d+)(?:[&#]|$)")
    }
}
