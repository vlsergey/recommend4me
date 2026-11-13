package io.github.vlsergey.recommend4me.source.ffn

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class FfnAutoConfiguration {
    @Bean fun ffn(): Source = Ffn()
}
