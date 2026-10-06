package io.github.vlsergey.recommend4me.universe

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

@AutoConfiguration
class WikidataAutoConfiguration {
    @Bean
    fun wikidataCatalogue(json: JsonMapper): UniverseCatalogue = WikidataCatalogue(json)
}
