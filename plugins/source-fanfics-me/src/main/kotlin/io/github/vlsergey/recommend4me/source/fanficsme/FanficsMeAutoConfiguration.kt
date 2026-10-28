package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.Source
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class FanficsMeAutoConfiguration {
    @Bean
    fun fanficsMe(): Source = FanficsMe()
}
