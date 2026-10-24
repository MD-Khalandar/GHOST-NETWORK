package org.ghost.gateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.ghost.protocol.WireCodec

@Serializable
data class GatewayConfig(val serverUrl: String, val token: String, val expiresAtMs: Long? = null) {
    override fun toString() = "GatewayConfig([redacted])"
    companion object {
        fun validated(url: String, token: String, allowHttp: Boolean): GatewayConfig {
            val address = serverAddress(url, allowHttp)
            require(token.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "Enter the gateway access token supplied by your server operator." }
            return GatewayConfig(address, token)
        }
        fun serverAddress(url: String, allowHttp: Boolean): String {
            val uri = try { URI(url.trim()) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid server address.") }
            require(uri.scheme == "https" || (allowHttp && uri.scheme == "http")) { "Use an HTTPS server address. HTTP is allowed only in debug builds." }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in -1..65535 && uri.port != 0 && url.length <= 512) { "Use a server address without credentials, query or fragment." }
            return uri.toASCIIString().trimEnd('/')
        }
    }
}

interface GatewaySettings {
    val config: kotlinx.coroutines.flow.StateFlow<GatewayConfig?>
    suspend fun load()
    suspend fun saveAutomatic(config: GatewayConfig)
    suspend fun clear()
}

/** Only encrypted settings enter preferences; the encryption key stays in Android Keystore. */
class GatewayConfigStore(context: Context, private val allowHttp: Boolean) : GatewaySettings {
    private val preferences = context.getSharedPreferences("ghost_gateway", Context.MODE_PRIVATE)
    private val mutableConfig = MutableStateFlow<GatewayConfig?>(null)
    override val config = mutableConfig.asStateFlow()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun load() = withContext(Dispatchers.IO) {
        val saved = preferences.getString("settings", null) ?: return@withContext
        val parts = saved.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, WireCodec.unbase64(parts[0], 16)))
        val decoded = Json.decodeFromString<GatewayConfig>(cipher.doFinal(WireCodec.unbase64(parts[1], 4096)).decodeToString())
        mutableConfig.value = GatewayConfig.validated(decoded.serverUrl, decoded.token, allowHttp).copy(expiresAtMs = decoded.expiresAtMs)
    }
    suspend fun save(url: String, token: String) = withContext(Dispatchers.IO) {
        val existing = mutableConfig.value
        val value = GatewayConfig.validated(url, if (token.isEmpty() && url.trim().trimEnd('/') == existing?.serverUrl) existing.token else token, allowHttp)
        write(value)
    }
    override suspend fun saveAutomatic(config: GatewayConfig) = withContext(Dispatchers.IO) {
        require(config.expiresAtMs != null && config.expiresAtMs > 0)
        write(GatewayConfig.validated(config.serverUrl, config.token, allowHttp).copy(expiresAtMs = config.expiresAtMs))
    }
    private fun write(value: GatewayConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(Json.encodeToString(value).encodeToByteArray())
        check(preferences.edit().putString("settings", "${WireCodec.base64(cipher.iv)}.${WireCodec.base64(encrypted)}").commit())
        mutableConfig.value = value
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(preferences.edit().remove("settings").commit())
        mutableConfig.value = null
    }
    companion object { private const val ALIAS = "ghost.gateway.settings" }
}
