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
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)

/**
 * Core Lightning (CLN) node configuration
 */
data class ClnConfig(
    val url: String,
    val rune: String,
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)

/**
 * Phoenixd node configuration
 */
data class PhoenixdConfig(
    val url: String,
    val password: String,
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)

/**
 * NWC (Nostr Wallet Connect) configuration
 */
data class NwcConfig(
    val nwcUri: String,
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)

/**
 * Strike API configuration
 */
data class StrikeConfig(
    val apiKey: String,
    val baseUrl: String? = "https://api.strike.me/v1",
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)

/**
 * Blink API configuration
 */
data class BlinkConfig(
    val apiKey: String,
    val baseUrl: String? = "https://api.blink.sv/graphql",
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)

/**
 * Speed API configuration
 */
data class SpeedConfig(
    val apiKey: String,
    val baseUrl: String? = "https://api.tryspeed.com/v1",
    val socks5Proxy: String? = null,
    val acceptInvalidCerts: Boolean? = true,
    val httpTimeout: Long? = 120L
)
