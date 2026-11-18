package com.fitness.zwiftbike

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class TelemetryBroadcasterTest {

    @Test
    fun normalizeEndpointUrlHandlesBareDomainsAndRootUrls() {
        assertEquals(
            "https://richiesamliefitbike.vercel.app/api/telemetry",
            TelemetryBroadcaster.normalizeEndpointUrl("richiesamliefitbike.vercel.app")
        )
        assertEquals(
            "https://richiesamliefitbike.vercel.app/api/telemetry",
            TelemetryBroadcaster.normalizeEndpointUrl("  https://richiesamliefitbike.vercel.app/  ")
        )
        assertEquals(
            "https://richiesamliefitbike.vercel.app/api/telemetry",
            TelemetryBroadcaster.normalizeEndpointUrl("http://richiesamliefitbike.vercel.app")
        )
        assertEquals(
            "https://richiesamliefitbike.vercel.app/api/telemetry",
            TelemetryBroadcaster.normalizeEndpointUrl("https://richiesamliefitbike.vercel.app/api/telemetry/")
        )
    }

    @Test
    fun normalizeEndpointUrlHandlesLocalHostsCustomPathsAndInvalidInputs() {
        assertEquals(
            "http://localhost:3000/api/telemetry",
            TelemetryBroadcaster.normalizeEndpointUrl("localhost:3000")
        )
        assertEquals(
            "http://10.0.2.2:3000/api/telemetry",
            TelemetryBroadcaster.normalizeEndpointUrl("10.0.2.2:3000")
        )
        assertEquals(
            "http://192.168.1.50:8080/custom-relay",
            TelemetryBroadcaster.normalizeEndpointUrl("http://192.168.1.50:8080/custom-relay")
        )
        assertEquals("", TelemetryBroadcaster.normalizeEndpointUrl(""))
        assertEquals("", TelemetryBroadcaster.normalizeEndpointUrl("   "))
        assertEquals("", TelemetryBroadcaster.normalizeEndpointUrl("not a url"))
        assertEquals("", TelemetryBroadcaster.normalizeEndpointUrl("ftp://example.com"))
    }

    @Test
    fun resolveCheerEndpointMapsTelemetryPathCleanly() {
        assertEquals(
            "https://richiesamliefitbike.vercel.app/api/cheer",
            TelemetryBroadcaster.resolveCheerEndpoint("richiesamliefitbike.vercel.app")
        )
        assertEquals(
            "http://10.0.2.2:3000/api/cheer",
            TelemetryBroadcaster.resolveCheerEndpoint("http://10.0.2.2:3000/api/telemetry")
        )
        assertNull(
            TelemetryBroadcaster.resolveCheerEndpoint("http://192.168.1.50:8080/custom-relay")
        )
    }

    @Test
    fun broadcastWithDiagnosticsPostsToNormalizedTelemetryRouteAndHandlesErrors() {
        val receivedBody = AtomicReference<String>("")
        val receivedMethod = AtomicReference<String>("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/telemetry") { exchange ->
            receivedMethod.set(exchange.requestMethod)
            receivedBody.set(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val ok = """{"ok":true}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, ok.size.toLong())
            exchange.responseBody.use { it.write(ok) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/api/telemetry")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/blocked") { exchange ->
            val err = """Method Not Allowed""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(405, err.size.toLong())
            exchange.responseBody.use { it.write(err) }
        }
        server.start()

        try {
            val port = server.address.port
            val packet = SpectatorPacket(
                riderName = "Tester",
                powerWatts = 240,
                speedKmh = 35.5f
            )

            // Base host:port without /api/telemetry auto-resolves to /api/telemetry
            val baseResult = TelemetryBroadcaster.broadcastWithDiagnostics(
                spectatorEndpoint = "127.0.0.1:$port",
                packet = packet,
                bypassThrottle = true
            )
            assertTrue(baseResult.success)
            assertEquals(200, baseResult.httpCode)
            assertEquals("POST", receivedMethod.get())
            assertTrue(receivedBody.get().contains("\"powerWatts\":240"))

            // Redirect preserves POST to /api/telemetry
            val redirectResult = TelemetryBroadcaster.broadcastWithDiagnostics(
                spectatorEndpoint = "http://127.0.0.1:$port/redirect",
                packet = packet,
                bypassThrottle = true
            )
            assertTrue(redirectResult.success)
            assertEquals(200, redirectResult.httpCode)

            // Non-2xx returns structured error diagnostics
            val errorResult = TelemetryBroadcaster.broadcastWithDiagnostics(
                spectatorEndpoint = "http://127.0.0.1:$port/blocked",
                packet = packet,
                bypassThrottle = true
            )
            assertFalse(errorResult.success)
            assertEquals(405, errorResult.httpCode)
            assertTrue(errorResult.errorMessage?.contains("405") == true)
        } finally {
            server.stop(0)
        }
    }
}
