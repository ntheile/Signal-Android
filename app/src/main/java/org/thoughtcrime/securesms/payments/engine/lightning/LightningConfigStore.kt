package org.thoughtcrime.securesms.payments.engine.lightning

import android.content.Context
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.KeyStoreHelper
import java.io.File

/**
 * Stores Lightning node connection configuration securely.
 * Supports various node backends from the LNI (Lightning Node Interface) library:
 * - NWC (Nostr Wallet Connect) - easiest to configure
 * - LND - requires URL + macaroon
 * - CLN (Core Lightning) - requires URL + rune
 * - Phoenixd - requires URL + password
 * - Strike/Blink/Speed - requires API key
 */
class LightningConfigStore(private val appContext: Context) {

    companion object {
        private val TAG = Log.tag(LightningConfigStore::class.java)
        private const val CONFIG_FILE = "lightning_config.json"
    }

    private val file = File(appContext.filesDir, CONFIG_FILE)

    /**
     * Get the current Lightning configuration, if any.
     */
    fun getConfig(): LightningConfig? {
        if (!file.exists()) return null
        return try {
            load()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load Lightning config", e)
            null
        }
    }

    /**
     * Save Lightning configuration (encrypted).
     */
    fun saveConfig(config: LightningConfig) {
        try {
            val json = config.toJson()
            val sealed = KeyStoreHelper.seal(json.toByteArray())
            val obj = JSONObject().put("config", sealed.serialize())
            file.writeText(obj.toString(), Charsets.UTF_8)
            Log.i(TAG, "Lightning config saved for type: ${config.type}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save Lightning config", e)
            throw e
        }
    }

    /**
     * Clear the Lightning configuration.
     */
    fun clearConfig() {
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Lightning config cleared")
        }
    }

    /**
     * Check if a Lightning node is configured.
     */
    fun isConfigured(): Boolean = file.exists()

    private fun load(): LightningConfig {
        val text = file.readText(Charsets.UTF_8)
        val obj = JSONObject(text)
        val sealedStr = obj.getString("config")
        val sealed = KeyStoreHelper.SealedData.fromString(sealedStr)
        val bytes = KeyStoreHelper.unseal(sealed)
        val json = bytes.toString(Charsets.UTF_8)
        return LightningConfig.fromJson(json)
    }
}

/**
 * Lightning node configuration.
 */
data class LightningConfig(
    val type: LightningNodeType,
    val url: String? = null,
    val credential: String, // macaroon, rune, password, API key, NWC URI, or mnemonic (for Spark)
    val secondaryCredential: String? = null, // API key for Spark
    val storageDir: String? = null, // Storage directory for Spark
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean = false
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name)
        obj.putOpt("url", url)
        obj.put("credential", credential)
        obj.putOpt("secondaryCredential", secondaryCredential)
        obj.putOpt("storageDir", storageDir)
        obj.putOpt("socks5Proxy", socks5Proxy)
        obj.put("acceptInvalidCerts", acceptInvalidCerts)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): LightningConfig {
            val obj = JSONObject(json)
            return LightningConfig(
                type = LightningNodeType.valueOf(obj.getString("type")),
                url = obj.optString("url", null),
                credential = obj.getString("credential"),
                secondaryCredential = obj.optString("secondaryCredential", null),
                storageDir = obj.optString("storageDir", null),
                socks5Proxy = obj.optString("socks5Proxy", null),
                acceptInvalidCerts = obj.optBoolean("acceptInvalidCerts", false)
            )
        }
    }
}

/**
 * Supported Lightning node types.
 * These correspond to the backends supported by the LNI library.
 */
enum class LightningNodeType {
    NWC,        // Nostr Wallet Connect - simplest, just needs NWC URI
    LND,        // LND - needs URL + macaroon
    CLN,        // Core Lightning - needs URL + rune
    PHOENIXD,   // Phoenixd - needs URL + password
    STRIKE,     // Strike - needs API key
    BLINK,      // Blink - needs API key
    SPEED,      // Speed - needs API key
    SPARK       // Spark (Breez SDK) - needs mnemonic + storage dir + optional API key
}
