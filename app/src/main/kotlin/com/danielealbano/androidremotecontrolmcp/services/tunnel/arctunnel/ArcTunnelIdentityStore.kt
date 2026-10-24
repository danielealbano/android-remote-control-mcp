package com.danielealbano.androidremotecontrolmcp.services.tunnel.arctunnel

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
data class ArcTunnelSavedIdentity(
    val name: String,
    @SerialName("base_address") val baseAddress: String,
    @SerialName("identity_alias") val identityAlias: String,
    @SerialName("prev_identity_alias") val prevIdentityAlias: String = "",
    @SerialName("tls_alias") val tlsAlias: String,
    @SerialName("identity_chain") val identityChain: String,
    @SerialName("public_chain") val publicChain: String = "",
    val ca: String = "",
    val generation: Int = 0,
    @SerialName("ct_last_id") val ctLastId: String = "",
    @SerialName("ct_flagged") val ctFlagged: List<String> = emptyList(),
    @SerialName("ct_alert_pubkey") val ctAlertPubkey: String = "",
    @SerialName("ct_last_check_ms") val ctLastCheckMs: Long = 0,
)

class ArcTunnelIdentityStore(
    root: File,
    private val hosts: ArcTunnelHosts,
    private val keys: ArcTunnelKeyStore,
) {
    private val file = File(root, "${hosts.envKey}.json")

    @Synchronized
    fun save(identity: ArcTunnelIdentity) {
        val s = identity.state()
        val previous = load()
        write(
            ArcTunnelSavedIdentity(
                name = identity.name,
                baseAddress = hosts.baseAddress,
                identityAlias = s.identityAlias,
                prevIdentityAlias = s.prevIdentityAlias,
                tlsAlias = s.tlsAlias,
                identityChain = ArcTunnelPem.chainPem(s.identityChain),
                publicChain = ArcTunnelPem.chainPem(s.publicChain),
                ca = s.caId,
                generation = s.generation,
                ctLastId = previous?.ctLastId.orEmpty(),
                ctFlagged = previous?.ctFlagged.orEmpty(),
                ctAlertPubkey = previous?.ctAlertPubkey.orEmpty(),
                ctLastCheckMs = previous?.ctLastCheckMs ?: 0L,
            ),
        )
    }

    /**
     * Merges one CT check into the CURRENT record (a dismissal that landed meanwhile is kept): the cursor and
     * the check time replace, [newFlagged] keys add, a non-null [newAlertPubkey] replaces the alert.
     */
    @Synchronized
    fun mergeCtCheck(
        lastId: String,
        newFlagged: List<String>,
        newAlertPubkey: String?,
        checkedAtMs: Long,
    ) {
        val current = load() ?: return
        write(
            current.copy(
                ctLastId = lastId,
                ctFlagged = (current.ctFlagged + newFlagged).distinct(),
                ctAlertPubkey = newAlertPubkey ?: current.ctAlertPubkey,
                ctLastCheckMs = checkedAtMs,
            ),
        )
    }

    /** Clears the persisted undismissed alert (the user dismissed it). */
    @Synchronized
    fun clearCtAlert() {
        val current = load() ?: return
        write(current.copy(ctAlertPubkey = ""))
    }

    /** The saved identity, or null when there is none or the file cannot be read (logged). */
    @Synchronized
    fun load(): ArcTunnelSavedIdentity? {
        if (!file.exists()) return null
        return try {
            ArcTunnelJson.decodeFromString(ArcTunnelSavedIdentity.serializer(), file.readText())
        } catch (e: IOException) {
            Log.w(TAG, "unreadable identity file ${file.name}; treated as not enrolled", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "corrupt identity file ${file.name}; treated as not enrolled: ${e.javaClass.simpleName}")
            null
        }
    }

    /** True while [identity] is still the saved one (a forget in between makes a late renewal a no-op). */
    @Synchronized
    fun holds(identity: ArcTunnelIdentity): Boolean = load()?.name == identity.name

    /** Deletes the file and every key it references; returns whether anything was saved. */
    @Synchronized
    fun forget(): Boolean {
        val saved = load()
        if (saved == null && !file.exists()) return false
        listOf(saved?.identityAlias, saved?.prevIdentityAlias, saved?.tlsAlias)
            .filterNotNull()
            .filter { it.isNotEmpty() }
            .forEach { keys.deleteKey(it) }
        check(!file.exists() || file.delete()) { "could not delete ${file.path}" }
        return true
    }

    private fun write(saved: ArcTunnelSavedIdentity) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(ArcTunnelJson.encodeToString(ArcTunnelSavedIdentity.serializer(), saved))
        check(tmp.renameTo(file)) { "could not move ${tmp.path} into place" }
    }

    private companion object {
        const val TAG = "MCP:ArcTunnelStore"
    }
}
