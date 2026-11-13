package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class MolweAutoConfiguration {
    @Bean fun molwe(): Source = Molwe()
}
