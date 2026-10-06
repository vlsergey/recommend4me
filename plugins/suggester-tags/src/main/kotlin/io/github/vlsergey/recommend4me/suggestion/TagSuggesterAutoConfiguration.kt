package io.github.vlsergey.recommend4me.suggestion

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class TagSuggesterAutoConfiguration {
    @Bean
    fun tagSuggester(): FacetSuggester = TagSuggester()
}
