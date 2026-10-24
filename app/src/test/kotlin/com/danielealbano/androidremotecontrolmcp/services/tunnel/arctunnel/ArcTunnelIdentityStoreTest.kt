package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ArcTunnelIdentityStore")
class ArcTunnelIdentityStoreTest {
    @TempDir
    lateinit var root: File

    private val keys = FakeArcTunnelKeyStore()
    private val hosts = ArcTunnelTestServer.hosts
    private val net = ArcTunnelNet(null, null)
    private lateinit var store: ArcTunnelIdentityStore

    @BeforeEach
    fun setUp() {
        store = ArcTunnelIdentityStore(root, hosts, keys)
    }

    private fun file(): File = File(root, "${hosts.envKey}.json")

    @Test
    fun `load returns null without a file`() {
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips`() {
        val identity = ArcTunnelTestIdentity.create(keys, net)

        store.save(identity)
        val saved = requireNotNull(store.load())

        val state = identity.state()
        assertEquals(identity.name, saved.name)
        assertEquals(hosts.baseAddress, saved.baseAddress)
        assertEquals(state.identityAlias, saved.identityAlias)
        assertEquals(state.tlsAlias, saved.tlsAlias)
        assertEquals(state.identityChain, ArcTunnelPem.parseChain(saved.identityChain))
        assertEquals(state.publicChain, ArcTunnelPem.parseChain(saved.publicChain))
        assertEquals("letsencrypt", saved.ca)
        assertEquals(0, saved.generation)
    }

    @Test
    fun `mergeCtCheck keeps identity fields and merges`() {
        val identity = ArcTunnelTestIdentity.create(keys, net)
        store.save(identity)

        store.mergeCtCheck("10", listOf("a", "b"), "b", 100L)
        store.mergeCtCheck("11", listOf("b", "c"), null, 200L)
        store.save(identity)

        val saved = requireNotNull(store.load())
        assertEquals("11", saved.ctLastId)
        assertEquals(listOf("a", "b", "c"), saved.ctFlagged)
        assertEquals("b", saved.ctAlertPubkey)
        assertEquals(200L, saved.ctLastCheckMs)
        assertEquals(identity.name, saved.name)
    }

    @Test
    fun `mergeCtCheck keeps a cleared alert`() {
        store.save(ArcTunnelTestIdentity.create(keys, net))
        store.mergeCtCheck("1", listOf("a"), "a", 1L)

        store.clearCtAlert()
        store.mergeCtCheck("2", emptyList(), null, 2L)

        assertEquals("", requireNotNull(store.load()).ctAlertPubkey)
    }

    @Test
    fun `clearCtAlert clears only the alert`() {
        store.save(ArcTunnelTestIdentity.create(keys, net))
        store.mergeCtCheck("7", listOf("a"), "a", 70L)

        store.clearCtAlert()

        val saved = requireNotNull(store.load())
        assertEquals("", saved.ctAlertPubkey)
        assertEquals("7", saved.ctLastId)
        assertEquals(listOf("a"), saved.ctFlagged)
        assertEquals(70L, saved.ctLastCheckMs)
    }

    @Test
    fun `holds compares the name`() {
        val identity = ArcTunnelTestIdentity.create(keys, net)
        store.save(identity)
        val other = ArcTunnelIdentity(net, keys, "zzzzzzzzzz", hosts, identity.state())

        assertTrue(store.holds(identity))
        assertFalse(store.holds(other))
    }

    @Test
    fun `forget deletes the file and the three keys`() {
        val identity = ArcTunnelTestIdentity.create(keys, net)
        val state = identity.state()
        identity.install("${state.identityAlias}-next", state.identityChain, state.publicChain, "letsencrypt", 1)
        store.save(identity)

        assertTrue(store.forget())

        assertFalse(file().exists())
        val current = identity.state()
        assertTrue(keys.deleted.containsAll(listOf(current.identityAlias, current.prevIdentityAlias, current.tlsAlias)))
    }

    @Test
    fun `unreadable file loads as null and is forgotten`() {
        file().writeText("garbage")

        assertNull(store.load())
        assertTrue(store.forget())
        assertFalse(file().exists())
    }
}
