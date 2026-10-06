package io.github.vlsergey.recommend4me.access

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * What the personal configuration opens the application to (`recommend4me.access`): the networks
 * the requests may come from, and the names this machine is reached by.
 *
 * BOUND, NOT READ BY @Value: a list written in YAML is a property per element ("networks[0]"),
 * and a @Value of the list's own name finds none of them.
 */
@Component
class AccessSettings(environment: Environment) {
    private val binder = Binder.get(environment)

    private fun list(name: String): List<String> =
        binder.bind("recommend4me.access.$name", Bindable.listOf(String::class.java)).orElse(emptyList()).orEmpty().filter { it.isNotBlank() }

    val networks: List<String> = list("networks")
    val hosts: List<String> = list("hosts")
}
