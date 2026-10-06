package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class FicbookAutoConfiguration {
    @Bean
    fun ficbook(): Source = Ficbook()
}
