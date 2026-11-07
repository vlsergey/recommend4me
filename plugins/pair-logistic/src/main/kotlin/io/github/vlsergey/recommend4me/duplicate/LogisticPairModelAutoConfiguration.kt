package io.github.vlsergey.recommend4me.duplicate

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class LogisticPairModelAutoConfiguration {
    @Bean fun logisticPairModel(): PairModel = LogisticPairModel()
}
