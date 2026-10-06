package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class AuthorTodayAutoConfiguration {
    @Bean
    fun authorToday(): Source = AuthorToday()
}
