/**
 * LNI Node Configuration Types
 * 
 * These match the configuration structs from the LNI Rust library.
 */
package lni

/**
 * LND node configuration
 */
data class LndConfig(
    val url: String,
    val macaroon: String,
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = false,
    val httpTimeout: Int? = 120
)

/**
 * Core Lightning (CLN) node configuration
 */
data class ClnConfig(
    val url: String,
    val rune: String,
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = false,
    val httpTimeout: Int? = 120
)

/**
 * Phoenixd node configuration
 */
data class PhoenixdConfig(
    val url: String,
    val password: String,
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = false,
    val httpTimeout: Int? = 120
)

/**
 * NWC (Nostr Wallet Connect) configuration
 */
data class NwcConfig(
    val uri: String,
    val httpTimeout: Int? = 120
)

/**
 * Strike API configuration
 */
data class StrikeConfig(
    val apiKey: String,
    val baseUrl: String? = "https://api.strike.me/v1",
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = false,
    val httpTimeout: Int? = 120
)

/**
 * Blink API configuration
 */
data class BlinkConfig(
    val apiKey: String,
    val baseUrl: String? = "https://api.blink.sv/graphql",
    val httpTimeout: Int? = 120
)

/**
 * Speed API configuration
 */
data class SpeedConfig(
    val apiKey: String,
    val baseUrl: String? = "https://api.tryspeed.com/v1",
    val httpTimeout: Int? = 120
)
