package io.github.vlsergey.recommend4me.access

import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessSettingsTest {

    private fun environment(yaml: String) = StandardEnvironment().apply {
        YamlPropertySourceLoader().load("personal", ByteArrayResource(yaml.toByteArray())).forEach { propertySources.addFirst(it) }
    }

    @Test
    fun `the lists of the personal configuration are read as YAML writes them`() {
        val settings = AccessSettings(
            environment(
                """
                recommend4me:
                  access:
                    networks: [100.64.0.0/10, "fd7a:115c:a1b2::/48"]
                    hosts: [.ts.net]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("100.64.0.0/10", "fd7a:115c:a1b2::/48"), settings.networks)
        assertEquals(listOf(".ts.net"), settings.hosts)
        assertTrue(Guard(settings).ours("my-pc.tail1234.ts.net"))
    }

    @Test
    fun `without a personal configuration the application is open to nothing beyond itself`() {
        val settings = AccessSettings(environment("recommend4me:\n  access:\n    networks: []\n"))
        assertTrue(settings.networks.isEmpty() && settings.hosts.isEmpty())
    }
}
