package de.openspri.soap.selftest

import com.sun.net.httpserver.HttpServer
import de.openspri.soap.SigningCredentials
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfTestsTest {

    private fun TlsSelfTestResult.check(id: String) = checks.single { it.id == id }

    @Test
    fun `TLS self-test connects like a partner and finds a self-signed certificate with a short key`() {
        val credentials = SigningCredentials.generateSelfSigned("localhost", keyBits = 2048)
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tls", credentials.privateKey, "x".toCharArray(), arrayOf(credentials.certificate))
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance("PKIX").apply { init(keyStore, "x".toCharArray()) }.keyManagers, null, null)
        }
        (context.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
            val accept = thread { runCatching { (server.accept() as SSLSocket).use { it.startHandshake(); it.inputStream.read() } } }
            val result = TlsSelfTest().run(URI("https://127.0.0.1:${server.localPort}/"))
            accept.join(5000)

            assertTrue(result.protocol == "TLSv1.3" || result.protocol == "TLSv1.2", result.toString())
            assertTrue(result.check("protocol").ok)
            assertFalse(result.check("selfSigned").ok)
            assertFalse(result.check("trust").ok)
            assertFalse(result.check("key").ok, "RSA 2048 is below 3072")
            assertEquals("RSA", result.keyType)
            assertFalse(result.ok)
        }
    }

    @Test
    fun `TLS self-test reports an unreachable address as failed check`() {
        val result = TlsSelfTest(timeout = Duration.ofSeconds(2)).run(URI("https://127.0.0.1:1/"))
        assertEquals("connect", result.checks.single().id)
        assertFalse(result.ok)
    }

    @Test
    fun `validity warns before expiry, issuer is checked against the whitelist`() {
        val credentials = SigningCredentials.generateSelfSigned("spri.example.de", validity = Duration.ofDays(10))
        val now = Clock.fixed(Instant.now(), ZoneOffset.UTC)
        val chain = listOf(credentials.certificate)
        val noList = TlsSelfTest(clock = now).evaluate("https://spri.example.de/", "spri.example.de", "TLSv1.3", chain, trustedBySystem = true)
        assertTrue(noList.check("validity").warning)
        assertTrue(noList.check("validity").ok)
        assertTrue(noList.check("issuer").warning, "no whitelist configured")
        assertTrue(noList.check("key").ok, "RSA 3072")

        val whitelist = TlsSelfTest(trustedIssuers = listOf("Let's Encrypt"), clock = now)
        assertFalse(whitelist.evaluate("u", "spri.example.de", "TLSv1.2", chain, true).check("issuer").ok)
        assertFalse(whitelist.evaluate("u", "spri.example.de", "TLSv1.1", chain, true).check("protocol").ok)
    }

    @Test
    fun `outbound IP is compared with the configured addresses`() {
        val test = OutboundIpSelfTest(URI("https://echo.example/"), null)
        val ip = InetAddress.getByName("198.51.100.7")
        assertTrue(test.evaluate("u", ip, listOf("198.51.100.0/28")).ok)
        assertTrue(test.evaluate("u", ip, listOf("198.51.100.7")).ok)
        assertFalse(test.evaluate("u", ip, listOf("203.0.113.1")).ok)
        assertFalse(test.evaluate("u", ip, emptyList()).ok)
    }

    @Test
    fun `outbound IP self-test asks the echo service`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.remoteAddress.address.hostAddress.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val result = OutboundIpSelfTest(URI("http://127.0.0.1:${server.address.port}/"), null).run(listOf("127.0.0.1"))
            assertEquals("127.0.0.1", result.detected)
            assertTrue(result.ok, result.toString())
        } finally {
            server.stop(0)
        }
    }
}
