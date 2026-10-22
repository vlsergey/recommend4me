package db.source

import io.github.vlsergey.recommend4me.capture.CapturedPageRepository
import io.github.vlsergey.recommend4me.capture.PageSecrets
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * The pages kept before [PageSecrets] stripped every page as it came: the scripts and the session
 * tokens taken out of them too, one page at a time.
 */
@Suppress("ClassName")
class V10__page_secrets : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val connection = context.connection
        val urls = connection.createStatement().use { s ->
            s.executeQuery("SELECT url FROM captured_page").use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }
        val read = connection.prepareStatement("SELECT html FROM captured_page WHERE url = ?")
        val write = connection.prepareStatement("UPDATE captured_page SET html = ? WHERE url = ?")
        read.use {
            write.use {
                urls.forEach { url ->
                    read.setString(1, url)
                    val html = read.executeQuery().use { r -> if (r.next()) CapturedPageRepository.gunzip(r.getBytes(1)) else null } ?: return@forEach
                    val stripped = PageSecrets.stripped(html)
                    if (stripped === html) return@forEach
                    write.setBytes(1, CapturedPageRepository.gzip(stripped))
                    write.setString(2, url)
                    write.executeUpdate()
                }
            }
        }
    }
}
