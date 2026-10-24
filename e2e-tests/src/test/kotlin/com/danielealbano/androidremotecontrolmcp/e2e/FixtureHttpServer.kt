package com.danielealbano.androidremotecontrolmcp.e2e

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Minimal host-side HTTP server for download_from_url E2E tests.
 * Bound on 0.0.0.0:[FIXTURE_HTTP_PORT] (the port is exposed to the container
 * before it starts).
 *
 * Endpoints:
 * - GET /fixture.txt   -> 200, body [FIXTURE_CONTENT], Content-Type text/plain
 * - GET /missing.txt   -> 404
 * - GET /slow.txt      -> sleeps [SLOW_DELAY_MS], then 200 (exceeds the 10s test download timeout)
 * - GET /truncated.txt -> declares Content-Length 1000, writes 10 bytes, closes (mid-stream cut)
 */
class FixtureHttpServer {
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress("0.0.0.0", FIXTURE_HTTP_PORT), 0)
        s.createContext("/fixture.txt") { exchange ->
            val body = FIXTURE_CONTENT.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.createContext("/missing.txt") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        s.createContext("/slow.txt") { exchange ->
            Thread.sleep(SLOW_DELAY_MS)
            val body = "late".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.createContext("/truncated.txt") { exchange ->
            exchange.sendResponseHeaders(200, 1000L)
            exchange.responseBody.write(ByteArray(10))
            exchange.close()
        }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /**
     * Base URL as reachable FROM INSIDE the container.
     *
     * Verified mechanism on redroid 13 and 14 (plan empirical procedure 2): the primary
     * `host.testcontainers.internal` name is NOT resolvable inside redroid's Android
     * runtime (Android's resolver does not see the Testcontainers hosts entry), so the
     * container's default-gateway IP — the host on podman's bridge — is used instead.
     */
    fun containerReachableBaseUrl(): String = "http://${AndroidContainerSetup.containerGatewayIp()}:$FIXTURE_HTTP_PORT"

    companion object {
        /** Fixed host port, exposed to the container by SharedAndroidContainer before start. */
        const val FIXTURE_HTTP_PORT = 18923
        const val FIXTURE_CONTENT = "e2e-download-fixture-content"
        const val SLOW_DELAY_MS = 15_000L
    }
}
