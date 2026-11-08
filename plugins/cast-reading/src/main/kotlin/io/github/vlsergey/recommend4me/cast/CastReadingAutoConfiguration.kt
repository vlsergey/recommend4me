package io.github.vlsergey.recommend4me.cast

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

/**
 * The ways the sites' lines of characters and pairings are given to the models, in the order they are offered: the line
 * whole — the default — first.
 */
@AutoConfiguration
class CastReadingAutoConfiguration {
    @Bean fun wholeLine(): CastReader = WholeLine()

    @Bean fun nameVectors(): CastReader = NameVectors()

    @Bean fun nameFeatures(): CastReader = NameFeatures()

    @Bean fun dictionaryNames(): CastReader = DictionaryNames()

    @Bean fun everyWay(): CastReader = EveryWay()
}
