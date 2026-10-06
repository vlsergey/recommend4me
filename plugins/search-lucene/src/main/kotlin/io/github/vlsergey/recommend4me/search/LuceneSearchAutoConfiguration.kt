package io.github.vlsergey.recommend4me.search

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import java.nio.file.Path

@AutoConfiguration
class LuceneSearchAutoConfiguration {
    @Bean(destroyMethod = "close")
    fun luceneSearch(@Value("\${recommend4me.data-dir}") dataDir: String): SearchProvider = LuceneSearch(Path.of(dataDir))
}
