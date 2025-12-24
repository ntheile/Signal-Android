package org.thoughtcrime.securesms.payments.engine.lightning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Nostr Wallet Connect (NWC) implementation of LightningNode.
 * 
 * NWC is the simplest way to connect to a Lightning wallet remotely.
 * It uses Nostr relays to communicate with a wallet that supports NWC.
 * 
 * NWC URI format: nostr+walletconnect://<pubkey>?relay=<relay>&secret=<secret>
 * 
 * Supported NWC methods:
 * - pay_invoice: Pay a BOLT11 invoice
 * - make_invoice: Create a new invoice
 * - lookup_invoice: Look up invoice by payment hash
 * - get_balance: Get wallet balance
 * - get_info: Get wallet info
 * - list_transactions: List recent transactions
 * 
 * This is a simplified implementation focused on the core payment flows.
 * For production use, consider using the full LNI library with UniFFI bindings.
 * 
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/47.md">NIP-47: Nostr Wallet Connect</a>
 * @see <a href="https://github.com/lightning-node-interface/lni">LNI Library</a>
 */
class NwcNode(private val config: NwcConfig) : LightningNode {

    companion object {
        private val TAG = Log.tag(NwcNode::class.java)
        private const val DEFAULT_TIMEOUT_MS = 30000
    }

    data class NwcConfig(
        val walletPubkey: String,
        val relayUrl: String,
        val secret: String
    ) {
        companion object {
            /**
             * Parse NWC URI: nostr+walletconnect://<pubkey>?relay=<relay>&secret=<secret>
             */
            fun fromUri(uri: String): NwcConfig {
                val cleanUri = uri.removePrefix("nostr+walletconnect://")
                    .removePrefix("nostr://")
                
                val parts = cleanUri.split("?", limit = 2)
                val pubkey = parts[0]
                
                val params = if (parts.size > 1) {
                    parts[1].split("&").associate { param ->
                        val kv = param.split("=", limit = 2)
                        kv[0] to (if (kv.size > 1) kv[1] else "")
                    }
                } else {
                    emptyMap()
                }
                
                val relay = params["relay"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: throw IllegalArgumentException("Missing relay in NWC URI")
                val secret = params["secret"]
                    ?: throw IllegalArgumentException("Missing secret in NWC URI")
                
                return NwcConfig(
                    walletPubkey = pubkey,
                    relayUrl = relay,
                    secret = secret
                )
            }
        }
    }

    override suspend fun getInfo(): NodeInfo = withContext(Dispatchers.IO) {
        try {
            val response = sendNwcRequest("get_info", JSONObject())
            NodeInfo(
                alias = response.optString("alias", "NWC Wallet"),
                pubkey = config.walletPubkey,
                network = response.optString("network", "bitcoin"),
                blockHeight = response.optLong("block_height", 0),
                sendBalanceMsat = response.optLong("balance", 0),
                receiveBalanceMsat = 0
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get NWC info", e)
            NodeInfo(alias = "NWC Wallet", pubkey = config.walletPubkey)
        }
    }

    override suspend fun createInvoice(params: CreateInvoiceParams): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            val requestParams = JSONObject().apply {
                params.amountMsats?.let { put("amount", it) }
                params.description?.let { put("description", it) }
                params.expiry?.let { put("expiry", it) }
            }
            
            val response = sendNwcRequest("make_invoice", requestParams)
            
            LightningTransaction(
                type = "incoming",
                invoice = response.getString("invoice"),
                paymentHash = response.optString("payment_hash", ""),
                description = params.description ?: "",
                amountMsats = params.amountMsats ?: 0,
                createdAt = System.currentTimeMillis() / 1000,
                expiresAt = response.optLong("expires_at", 0)
            )
        }
    }

    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val requestParams = JSONObject().apply {
                put("invoice", params.invoice)
                params.amountMsats?.let { put("amount", it) }
            }
            
            val response = sendNwcRequest("pay_invoice", requestParams)
            
            PayInvoiceResponse(
                paymentHash = response.optString("payment_hash", ""),
                preimage = response.optString("preimage", ""),
                feeMsats = response.optLong("fees_paid", 0)
            )
        }
    }

    override suspend fun lookupInvoice(paymentHash: String): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            val requestParams = JSONObject().apply {
                put("payment_hash", paymentHash)
            }
            
            val response = sendNwcRequest("lookup_invoice", requestParams)
            
            LightningTransaction(
                type = response.optString("type", ""),
                invoice = response.optString("invoice", ""),
                paymentHash = paymentHash,
                description = response.optString("description", ""),
                preimage = response.optString("preimage", ""),
                amountMsats = response.optLong("amount", 0),
                feesPaid = response.optLong("fees_paid", 0),
                createdAt = response.optLong("created_at", 0),
                settledAt = response.optLong("settled_at", 0)
            )
        }
    }

    override suspend fun listTransactions(from: Int, limit: Int): Result<List<LightningTransaction>> = withContext(Dispatchers.IO) {
        runCatching {
            val requestParams = JSONObject().apply {
                put("from", from)
                put("limit", limit)
            }
            
            val response = sendNwcRequest("list_transactions", requestParams)
            val transactions = response.optJSONArray("transactions") ?: JSONArray()
            
            (0 until transactions.length()).map { i ->
                val tx = transactions.getJSONObject(i)
                LightningTransaction(
                    type = tx.optString("type", ""),
                    invoice = tx.optString("invoice", ""),
                    paymentHash = tx.optString("payment_hash", ""),
                    description = tx.optString("description", ""),
                    preimage = tx.optString("preimage", ""),
                    amountMsats = tx.optLong("amount", 0),
                    feesPaid = tx.optLong("fees_paid", 0),
                    createdAt = tx.optLong("created_at", 0),
                    settledAt = tx.optLong("settled_at", 0)
                )
            }
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            getInfo()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "NWC node not available", e)
            false
        }
    }

    /**
     * Send an NWC request to the wallet.
     * 
     * This is a simplified HTTP-based implementation.
     * A full implementation would use Nostr WebSocket connections.
     * 
     * For production, consider using the LNI library's NWC implementation
     * which handles the full Nostr protocol.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun sendNwcRequest(method: String, params: JSONObject): JSONObject {
        // Build NWC request
        val request = JSONObject().apply {
            put("method", method)
            put("params", params)
        }
        
        // For a simple HTTP-based NWC proxy, we can POST directly
        // Real NWC uses Nostr events with encryption
        val nwcProxyUrl = buildNwcProxyUrl(method)
        
        val connection = URL(nwcProxyUrl).openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = DEFAULT_TIMEOUT_MS
            readTimeout = DEFAULT_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.secret}")
        }
        
        try {
            connection.outputStream.use { os ->
                os.write(request.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException("NWC request failed: $responseCode - $errorBody")
            }
            
            val responseBody = connection.inputStream.bufferedReader().readText()
            val response = JSONObject(responseBody)
            
            if (response.has("error")) {
                val error = response.getJSONObject("error")
                throw RuntimeException("NWC error: ${error.optString("message", "Unknown error")}")
            }
            
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }
    
    private fun buildNwcProxyUrl(method: String): String {
        // Use the relay URL as the base, appending the NWC method
        // This works with NWC HTTP proxies like those from Alby
        val baseUrl = config.relayUrl.replace("wss://", "https://").replace("ws://", "http://")
        return "$baseUrl/v1/nwc/$method"
    }
}
