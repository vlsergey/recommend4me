package io.github.vlsergey.recommend4me.source.aff

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class AffAutoConfiguration {
    @Bean fun aff(): Source = Aff()
}
