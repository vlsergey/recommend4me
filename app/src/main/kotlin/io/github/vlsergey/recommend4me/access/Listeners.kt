package io.github.vlsergey.recommend4me.access

import org.apache.catalina.connector.Connector
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component
import java.net.InetAddress

private val log = LoggerFactory.getLogger(Listeners::class.java)

/**
 * THE SERVER LISTENS ON EXACTLY THESE ADDRESSES: the loopback of both families and this machine's
 * own addresses inside the networks the personal configuration opens it to — not on 0.0.0.0,
 * which would hand the application to every network the machine is on. The addresses are those of
 * the moment of the start.
 */
@Component
class Listeners(@Value("\${recommend4me.access.networks:}") networks: List<String>) :
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private val addresses = Addresses(networks.filter { it.isNotBlank() }.map(::Network))
    private val opened = networks.any { it.isNotBlank() }

    override fun customize(factory: TomcatServletWebServerFactory) {
        val own = addresses.own()
        if (opened && own.isEmpty()) log.warn("this machine has no address in the networks it is opened to: it is open to itself only")
        val all = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")) + own
        factory.setAddress(all.first())
        all.drop(1).forEach { address ->
            factory.addAdditionalConnectors(Connector("HTTP/1.1").apply {
                port = factory.port
                setProperty("address", address.hostAddress.substringBefore('%'))
            })
        }
        log.info("listening on {}, port {}", all.joinToString { it.hostAddress.substringBefore('%') }, factory.port)
    }
}
