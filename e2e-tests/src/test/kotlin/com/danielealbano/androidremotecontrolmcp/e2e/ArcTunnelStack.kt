package com.danielealbano.androidremotecontrolmcp.e2e

import com.github.dockerjava.api.model.Network.Ipam
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.InternetProtocol
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.PullPolicy
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/** [GenericContainer] with a UDP port bound to a fixed host port before start (`withExposedPorts` is TCP-only). */
class UdpPortContainer(
    image: DockerImageName,
) : GenericContainer<UdpPortContainer>(image) {
    fun withFixedUdpPort(
        hostPort: Int,
        containerPort: Int,
    ): UdpPortContainer = apply { addFixedExposedPort(hostPort, containerPort, InternetProtocol.UDP) }
}

/**
 * The free E2E tunnel server stack, replicated from the tunnel server repo's own e2e
 * (`internal/tunneltest/containers.go`, `e2e/e2e_test.go`): Valkey, MinIO, Pebble + challtestsrv (hermetic
 * ACME CA, `tls-alpn-01` validated against the server's own edge), and the server itself from the `latest`
 * ghcr image (NEVER pinned, always pulled, NEVER built locally). The server has a static IP on the stack
 * network so challtestsrv can answer Pebble's validation lookups with it before the server exists. The
 * server's edge (`:443`) is mapped to a random host port, which [ArcTunnelHostRelay] exposes on
 * `0.0.0.0:[EDGE_HOST_PORT]` for the phone (through its gateway) and for the JVM (`127.0.0.1`).
 */
class ArcTunnelStack(
    /** Address the phone dials back to: the host gateway as seen from redroid, with [EDGE_HOST_PORT]. */
    private val dialBackAdvertise: String,
) : AutoCloseable {
    private val subnetOctets = "10.${Random.nextInt(100, 200)}.${Random.nextInt(0, 256)}"
    private val serverIp = "$subnetOctets.10"
    private val network: Network =
        Network
            .builder()
            .createNetworkCmdModifier { cmd ->
                val ipam = Ipam.Config().withSubnet("$subnetOctets.0/24").withGateway("$subnetOctets.1")
                cmd.withIpam(Ipam().withConfig(ipam))
            }.build()
    private val containers = ArrayDeque<GenericContainer<*>>()
    private var relay: ArcTunnelHostRelay? = null

    /** Pebble's per-run issuing root (PEM): the trust anchor the phone and the JVM use for tunnel certs. */
    lateinit var issuingRootPem: String
        private set

    fun start() {
        start(
            GenericContainer(DockerImageName.parse(VALKEY_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("valkey")
                .withExposedPorts(VALKEY_PORT)
                .waitingFor(Wait.forListeningPort()),
        )
        val minio =
            start(
                GenericContainer(DockerImageName.parse(MINIO_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("minio")
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET)
                    .withCommand("server", "/data")
                    .withExposedPorts(MINIO_PORT)
                    .waitingFor(Wait.forListeningPort()),
            )
        val minioUrl = "http://${minio.host}:${minio.getMappedPort(MINIO_PORT)}"
        ArcTunnelS3.ensureBucket(minioUrl, MINIO_ACCESS, MINIO_SECRET, BUCKET)

        // challtestsrv: the DNS Pebble consults; every name resolves to the server's static IP.
        val dnsHostPort = freeUdpPort()
        val chal =
            start(
                UdpPortContainer(DockerImageName.parse(CHALLTESTSRV_IMAGE))
                    .withFixedUdpPort(dnsHostPort, CHAL_DNS_PORT)
                    .withNetwork(network)
                    .withNetworkAliases("challtestsrv")
                    .withCommand("-defaultIPv6", "", "-defaultIPv4", serverIp)
                    .withExposedPorts(CHAL_MGMT_PORT)
                    // The image is FROM scratch (no shell for a port check) and the management API answers 400
                    // to a body-less probe: readiness = any HTTP answer on the management port plus a real DNS
                    // answer (waitForDns below), as the server e2e's dnsAnswers wait does.
                    .waitingFor(Wait.forHttp("/").forPort(CHAL_MGMT_PORT).forStatusCodeMatching { it in 200..499 }),
            )
        waitForDns(chal.host, dnsHostPort)

        // Pebble with host validation: tls-alpn-01 / http-01 go to the server's real edge ports.
        val pebble =
            start(
                GenericContainer(DockerImageName.parse(PEBBLE_IMAGE))
                    .withNetwork(network)
                    .withNetworkAliases("pebble")
                    .withCopyToContainer(Transferable.of(pebbleConfig()), PEBBLE_CONFIG_PATH)
                    .withCommand("-config", PEBBLE_CONFIG_PATH, "-dnsserver", "challtestsrv:$CHAL_DNS_PORT")
                    .withEnv("PEBBLE_VA_NOSLEEP", "1")
                    .withEnv("PEBBLE_WFE_NONCEREJECT", "0")
                    .withExposedPorts(PEBBLE_ACME_PORT, PEBBLE_MGMT_PORT)
                    .waitingFor(
                        Wait
                            .forHttp("/roots/0")
                            .forPort(PEBBLE_MGMT_PORT)
                            .usingTls()
                            .allowInsecure()
                            .forStatusCode(200),
                    ),
            )
        val minica = File.createTempFile("pebble-minica", ".pem").apply { deleteOnExit() }
        pebble.copyFileFromContainer("/test/certs/pebble.minica.pem", minica.absolutePath)
        issuingRootPem = httpsGet("https://${pebble.host}:${pebble.getMappedPort(PEBBLE_MGMT_PORT)}/roots/0")

        val ca = ArcTunnelTestCa.generate()
        val server =
            start(
                GenericContainer(DockerImageName.parse(SERVER_IMAGE))
                    .withImagePullPolicy(PullPolicy.alwaysPull())
                    .withNetwork(network)
                    .withNetworkAliases(SERVER_ALIAS)
                    .withCreateContainerCmdModifier { cmd ->
                        cmd.withIpv4Address(serverIp)
                        // distroless `nonroot` cannot write the copied-in dirs: root inside this throw-away container.
                        cmd.withUser("0:0")
                    }.withCopyToContainer(Transferable.of(ca.certPem), "/ca/ca.pem")
                    .withCopyToContainer(Transferable.of(ca.keyPem), "/ca/ca-key.pem")
                    .withCopyToContainer(Transferable.of(minica.readText()), "/ca/pebble-minica.pem")
                    .withCopyToContainer(Transferable.of(""), "/attest/signers.txt")
                    .withCopyToContainer(Transferable.of(""), "/acme/.keep")
                    .withCopyToContainer(Transferable.of(""), "/conn-logs/.keep")
                    .withEnv(serverEnv())
                    .withExposedPorts(EDGE_PORT, INTERNAL_PORT)
                    .waitingFor(Wait.forHttp("/healthz").forPort(INTERNAL_PORT).forStatusCode(200)),
            )
        relay = ArcTunnelHostRelay(EDGE_HOST_PORT, server.getMappedPort(EDGE_PORT)).also { it.start() }
        waitForReservedCert(ENROLL_HOST)
        waitForReservedCert(CONTROL_HOST)
    }

    /** Phone-side edge override (`host:port`), from the host gateway address redroid routes through. */
    fun edgeOverride(gatewayIp: String): String = "$gatewayIp:$EDGE_HOST_PORT"

    /** Opens a TLS connection to the edge (via the relay) with [sni] as server name, trusting the Pebble root. */
    fun connectTls(sni: String): SSLSocket {
        val raw = Socket("127.0.0.1", EDGE_HOST_PORT).apply { soTimeout = TLS_TIMEOUT_MS }
        val socket = pebbleSslContext().socketFactory.createSocket(raw, sni, EDGE_HOST_PORT, true) as SSLSocket
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        socket.startHandshake()
        return socket
    }

    fun pebbleTrustManager(): X509TrustManager {
        val factory = CertificateFactory.getInstance("X.509")
        val root = factory.generateCertificate(issuingRootPem.byteInputStream()) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("pebble-root", root)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun close() {
        relay?.close()
        while (containers.isNotEmpty()) containers.removeLast().stop()
        network.close()
    }

    private fun start(container: GenericContainer<*>): GenericContainer<*> {
        container.withStartupTimeout(Duration.ofSeconds(STARTUP_TIMEOUT_S))
        container.start()
        containers.addLast(container)
        return container
    }

    private fun pebbleSslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pebbleTrustManager()), null) }

    /** The reserved hosts serve an ACME cert only once the server issued it: wait for a verified handshake. */
    private fun waitForReservedCert(host: String) {
        val deadline = System.currentTimeMillis() + RESERVED_CERT_TIMEOUT_MS
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                connectTls(host).close()
                println("[ArcTunnelStack] $host serves a trusted certificate")
                return
            } catch (e: java.io.IOException) {
                last = "${e::class.simpleName}: ${e.message}"
            }
            Thread.sleep(POLL_MS)
        }
        throw IllegalStateException("$host never served a trusted certificate: $last")
    }

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    /** One A query to challtestsrv until it answers (what the server e2e's `dnsAnswers` wait does). */
    private fun waitForDns(
        host: String,
        port: Int,
    ) {
        val query =
            byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
                byteArrayOf(7) + "example".toByteArray() + byteArrayOf(4) + "test".toByteArray() +
                byteArrayOf(0, 0, 1, 0, 1)
        val deadline = System.currentTimeMillis() + RESERVED_CERT_TIMEOUT_MS
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
                val answer = DatagramPacket(ByteArray(DNS_BUFFER), DNS_BUFFER)
                try {
                    socket.receive(answer)
                    if (answer.length > DNS_HEADER && answer.data[0] == query[0] && answer.data[1] == query[1]) return
                } catch (_: java.net.SocketTimeoutException) {
                    // not up yet: retry
                }
            }
        }
        throw IllegalStateException("challtestsrv DNS never answered on $host:$port")
    }

    private fun httpsGet(url: String): String {
        val trustAll = arrayOf<TrustManager>(TrustAllX509TrustManager())
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val conn = java.net.URI(url).toURL().openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        try {
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun pebbleConfig(): String =
        """
        {"pebble": {
          "listenAddress": "0.0.0.0:$PEBBLE_ACME_PORT",
          "managementListenAddress": "0.0.0.0:$PEBBLE_MGMT_PORT",
          "certificate": "test/certs/localhost/cert.pem",
          "privateKey": "test/certs/localhost/key.pem",
          "httpPort": $ACME_HTTP_PORT,
          "tlsPort": $EDGE_PORT,
          "ocspResponderURL": "",
          "externalAccountBindingRequired": false,
          "retryAfter": {"authz": 3, "order": 5},
          "keyAlgorithm": "ecdsa",
          "profiles": {
            "default": {"description": "default", "validityPeriod": 7776000},
            "shortlived": {"description": "shortlived", "validityPeriod": 518400}
          }
        }}
        """.trimIndent()

    /**
     * `TUNNELD_*` env twins of the flags the server e2e sets (`runReplicaOnce` + `LooseACMEBudgets`), LE via
     * Pebble.
     */
    private fun serverEnv(): Map<String, String> =
        mapOf(
            "TUNNELD_LISTEN" to ":$EDGE_PORT",
            "TUNNELD_INTERNAL_LISTEN" to ":$INTERNAL_PORT",
            "TUNNELD_MESH_LISTEN" to ":$MESH_PORT",
            "TUNNELD_MESH_ADVERTISE" to "$SERVER_ALIAS:$MESH_PORT",
            "TUNNELD_TUNNEL_DOMAIN" to TUNNEL_DOMAIN,
            "TUNNELD_ENROLL_HOST" to ENROLL_HOST,
            "TUNNELD_CONTROL_HOST" to CONTROL_HOST,
            "TUNNELD_DIALBACK_ADVERTISE" to dialBackAdvertise,
            "TUNNELD_REDIS_URL" to "redis://valkey:$VALKEY_PORT/0",
            "TUNNELD_CA_CERT" to "/ca/ca.pem",
            "TUNNELD_CA_KEY" to "/ca/ca-key.pem",
            "TUNNELD_S3_ENDPOINT" to "http://minio:$MINIO_PORT",
            "TUNNELD_S3_BUCKET" to BUCKET,
            "TUNNELD_S3_ACCESS_KEY" to MINIO_ACCESS,
            "TUNNELD_S3_SECRET_KEY" to MINIO_SECRET,
            "TUNNELD_CONN_LOG_DIR" to "/conn-logs",
            "TUNNELD_ATTEST_SIGNER_DIGEST_FILE" to "/attest/signers.txt",
            "TUNNELD_ATTEST_ROOT_URL" to "http://127.0.0.1:1/root",
            "TUNNELD_ATTEST_STATUS_URL" to "http://127.0.0.1:1/status",
            "TUNNELD_ATTESTATION_OPTIONAL" to "true",
            "TUNNELD_ALLOW_ATTESTATION_OPTIONAL" to "1",
            "TUNNELD_ACME_DIR_LE" to "https://pebble:$PEBBLE_ACME_PORT/dir",
            "TUNNELD_ACME_EMAIL" to "ops@example.test",
            "TUNNELD_ACME_ACCOUNT_DIR" to "/acme",
            "TUNNELD_ACME_CHALLENGE_LE" to "tls-alpn-01",
            "TUNNELD_ACME_HTTP_LISTEN" to ":$ACME_HTTP_PORT",
            "TUNNELD_ACME_BUDGET_LE_DOMAIN" to LOOSE_BUDGET,
            "TUNNELD_ACME_BUDGET_LE_ORDERS" to LOOSE_BUDGET,
            "TUNNELD_ACME_BUDGET_LE_NAME" to LOOSE_BUDGET,
            "TUNNELD_ACME_ATTEMPT_TIMEOUT_LE" to "1m",
            "TUNNELD_ACME_POLL_FIRST_LE" to "100ms",
            "TUNNELD_ACME_POLL_INTERVAL" to "100ms",
            "TUNNELD_ACME_COOLDOWN_DEFAULT" to "1s",
            "TUNNELD_ACME_BACKOFF_INITIAL" to "1s",
            "TUNNELD_ACME_BACKOFF_MAX" to "5s",
            "TUNNELD_ISSUE_PER_WEEK" to "10",
            "TUNNELD_LIMIT_ENROLL_HOUR" to "1000",
            "TUNNELD_LIMIT_ENROLL_MINUTE" to "1000",
            "TUNNELD_LIMIT_ENROLL_BODY" to "64kb",
            "TUNNELD_MAX_CLIENTS" to "100",
            "TUNNELD_LIMIT_CONN_RATE" to "1000",
            "TUNNELD_LIMIT_CONCURRENT" to "8",
            "TUNNELD_LIMIT_PACKETS" to "100000",
            "TUNNELD_LIMIT_BANDWIDTH" to "10mbit",
            "TUNNELD_LIMIT_CONN_EVICT_IDLE" to "1s",
            "TUNNELD_LIMIT_CONN_MIN_RATE" to "1kb",
            "TUNNELD_LIMIT_CONN_PROTECT_RATE" to "1mb",
            "TUNNELD_HANDSHAKE_TIMEOUT" to "5s",
            "TUNNELD_BAN_POLL" to "1s",
            "TUNNELD_SHUTDOWN_GRACE" to "3s",
            "LEGO_CA_CERTIFICATES" to "/ca/pebble-minica.pem",
        )

    companion object {
        const val SERVER_IMAGE = "ghcr.io/danielealbano/android-remote-control-mcp-tunneld:latest"
        const val VALKEY_IMAGE = "valkey/valkey:9.1-alpine"
        const val MINIO_IMAGE = "pgsty/silo:RELEASE.2026-09-16T00-00-00Z"
        const val PEBBLE_IMAGE = "ghcr.io/letsencrypt/pebble:2.10.1"
        const val CHALLTESTSRV_IMAGE = "ghcr.io/letsencrypt/pebble-challtestsrv:2.10.1"
        const val BASE_ADDRESS = "example.test"
        const val TUNNEL_DOMAIN = "d.$BASE_ADDRESS"
        const val ENROLL_HOST = "enroll.$BASE_ADDRESS"
        const val CONTROL_HOST = "connect.$BASE_ADDRESS"

        /** Fixed host port of the relay (like `FixtureHttpServer.FIXTURE_HTTP_PORT`); the phone dials back to it. */
        const val EDGE_HOST_PORT = 18443
        private const val SERVER_ALIAS = "arc-tunnel-server"
        private const val BUCKET = "arc-tunnel-e2e"
        private const val MINIO_ACCESS = "test-access"
        private const val MINIO_SECRET = "test-secret-changeme"
        private const val VALKEY_PORT = 6379
        private const val MINIO_PORT = 9000
        private const val PEBBLE_ACME_PORT = 14000
        private const val PEBBLE_MGMT_PORT = 15000
        private const val PEBBLE_CONFIG_PATH = "/test/config/pebble-host-validation.json"
        private const val CHAL_DNS_PORT = 8053
        private const val CHAL_MGMT_PORT = 8055
        private const val EDGE_PORT = 443
        private const val ACME_HTTP_PORT = 80
        private const val INTERNAL_PORT = 9090
        private const val MESH_PORT = 9443
        private const val LOOSE_BUDGET = "100000/1m"
        private const val STARTUP_TIMEOUT_S = 120L
        private const val RESERVED_CERT_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 1_000L
        private const val TLS_TIMEOUT_MS = 15_000
        private const val DNS_TIMEOUT_MS = 2_000
        private const val DNS_BUFFER = 512
        private const val DNS_HEADER = 12
    }
}
