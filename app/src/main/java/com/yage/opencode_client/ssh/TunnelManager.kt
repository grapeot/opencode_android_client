package com.yage.opencode_client.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.yage.opencode_client.data.model.SshTunnelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.ServerSocket
import java.security.Security
import javax.inject.Inject
import javax.inject.Singleton

interface TunnelManager {
    suspend fun ensureStarted(config: SshTunnelConfig): TunnelResult
    fun disconnect()
}

@Singleton
class JschTunnelManager @Inject constructor(
    private val keyManager: SSHKeyManager,
    private val knownHostStore: KnownHostStore
) : TunnelManager {
    private var active: ActiveTunnel? = null
    private val activeLock = Any()
    private var connectionGeneration = 0L

    override suspend fun ensureStarted(config: SshTunnelConfig): TunnelResult {
        val generation = synchronized(activeLock) { ++connectionGeneration }
        return withContext(Dispatchers.IO) {
            config.validationError?.let { return@withContext TunnelResult.Failure(ConnectionPhase.SSH_GATEWAY, it) }
            synchronized(activeLock) {
                if (generation != connectionGeneration) {
                    return@withContext TunnelResult.Failure(ConnectionPhase.SSH_GATEWAY, "SSH connection was superseded")
                }
                active?.takeIf { it.config == config && it.session.isConnected }?.let {
                    return@withContext TunnelResult.Success(it.localUrl)
                }
                active?.session?.disconnect()
                active = null
            }

            val privateKey = keyManager.privateKeyBytes() ?: run {
                keyManager.ensureKeyPair()
                keyManager.privateKeyBytes()
            } ?: return@withContext TunnelResult.Failure(ConnectionPhase.SSH_AUTH, "SSH private key is missing")

            val localPort = chooseLocalPort(preferred = 4096)
            val hostKeyRepository = TofuHostKeyRepository(config, knownHostStore)
            val jsch = JSch().apply {
                ensureBouncyCastleProvider()
                addIdentity("opencode-android", privateKey, null, null)
                this.hostKeyRepository = hostKeyRepository
            }

            val session = try {
                jsch.getSession(config.username.trim(), config.host.trim(), config.port).apply {
                    setConfig("StrictHostKeyChecking", "yes")
                    userInfo = NonInteractiveUserInfo
                    connect(15_000)
                }
            } catch (error: Exception) {
                hostKeyRepository.mismatch?.let { mismatch ->
                    return@withContext TunnelResult.Failure(
                        ConnectionPhase.SSH_HOST_KEY,
                        "SSH host key changed. Expected ${mismatch.expected}, got ${mismatch.actual}. Reset trusted host only if you recognize this server."
                    )
                }
                return@withContext TunnelResult.Failure(classifyConnectFailure(error), error.message ?: "SSH connection failed")
            }

            val tunnel = ActiveTunnel(config = config, localPort = localPort, session = session)
            synchronized(activeLock) {
                if (generation != connectionGeneration) {
                    session.disconnect()
                    return@withContext TunnelResult.Failure(ConnectionPhase.SSH_GATEWAY, "SSH connection was superseded")
                }
                try {
                    session.setPortForwardingL(localPort, "127.0.0.1", config.remotePort)
                } catch (error: Exception) {
                    session.disconnect()
                    return@withContext TunnelResult.Failure(
                        ConnectionPhase.LOCAL_TUNNEL,
                        error.message ?: "Local tunnel failed"
                    )
                }
                active = tunnel
            }
            TunnelResult.Success(tunnel.localUrl)
        }
    }

    override fun disconnect() {
        synchronized(activeLock) {
            connectionGeneration++
            active?.session?.disconnect()
            active = null
        }
    }

    private fun chooseLocalPort(preferred: Int): Int {
        if (isPortAvailable(preferred)) return preferred
        ServerSocket(0).use { return it.localPort }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
    }

    private fun classifyConnectFailure(error: Exception): ConnectionPhase {
        val message = error.message.orEmpty().lowercase()
        return when {
            "reject hostkey" in message || "hostkey" in message -> ConnectionPhase.SSH_HOST_KEY
            "auth" in message || "userauth" in message -> ConnectionPhase.SSH_AUTH
            else -> ConnectionPhase.SSH_GATEWAY
        }
    }

    private fun ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}

data class ActiveTunnel(
    val config: SshTunnelConfig,
    val localPort: Int,
    val session: Session
) {
    val localUrl: String = "http://127.0.0.1:$localPort"
}

sealed class TunnelResult {
    data class Success(val localUrl: String) : TunnelResult()
    data class Failure(val phase: ConnectionPhase, val message: String) : TunnelResult()
}

enum class ConnectionPhase {
    SSH_GATEWAY,
    SSH_HOST_KEY,
    SSH_AUTH,
    LOCAL_TUNNEL,
    HEALTH
}

private class TofuHostKeyRepository(
    private val config: SshTunnelConfig,
    private val knownHostStore: KnownHostStore
) : HostKeyRepository {
    var mismatch: KnownHostCheck.Mismatch? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.CHANGED
        return when (val check = knownHostStore.checkOrTrust(config.host, config.port, key)) {
            is KnownHostCheck.Match -> HostKeyRepository.OK
            is KnownHostCheck.TrustedFirstUse -> HostKeyRepository.OK
            is KnownHostCheck.Mismatch -> {
                mismatch = check
                HostKeyRepository.CHANGED
            }
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "opencode-android"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private object NonInteractiveUserInfo : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
}
