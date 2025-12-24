package org.thoughtcrime.securesms.payments.engine.lightning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.signal.core.util.logging.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * LND REST API implementation of LightningNode.
 * 
 * LND provides a REST API for Lightning node operations.
 * This implementation uses the LND REST API with macaroon authentication.
 * 
 * @see <a href="https://api.lightning.community/">LND API Documentation</a>
 */
class LndNode(
    private val baseUrl: String,
    private val macaroon: String
) : LightningNode {

    companion object {
        private val TAG = Log.tag(LndNode::class.java)
        private const val DEFAULT_TIMEOUT_MS = 30000
    }

    override suspend fun getInfo(): NodeInfo = withContext(Dispatchers.IO) {
        try {
            val response = makeRequest("GET", "/v1/getinfo", null)
            
            NodeInfo(
                alias = response.optString("alias", "LND Node"),
                pubkey = response.optString("identity_pubkey", ""),
                network = if (response.optBoolean("testnet", false)) "testnet" else "bitcoin",
                blockHeight = response.optLong("block_height", 0),
                sendBalanceMsat = 0, // Need to call channel balance separately
                receiveBalanceMsat = 0
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get LND info", e)
            throw e
        }
    }

    private suspend fun getBalance(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val response = makeRequest("GET", "/v1/balance/channels", null)
            val localBalance = response.optLong("local_balance", 0)
            val remoteBalance = response.optLong("remote_balance", 0)
            Pair(localBalance * 1000, remoteBalance * 1000) // Convert to msats
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get LND balance", e)
            Pair(0L, 0L)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun createInvoice(params: CreateInvoiceParams): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            val amountSats = (params.amountMsats ?: 0) / 1000
            
            val body = JSONObject().apply {
                put("value", amountSats)
                params.description?.let { put("memo", it) }
                params.expiry?.let { put("expiry", it) }
            }
            
            val response = makeRequest("POST", "/v1/invoices", body)
            val paymentRequest = response.getString("payment_request")
            val rHashBytes = Base64.decode(response.getString("r_hash"))
            val paymentHash = rHashBytes.joinToString("") { "%02x".format(it) }
            
            LightningTransaction(
                type = "incoming",
                invoice = paymentRequest,
                paymentHash = paymentHash,
                description = params.description ?: "",
                amountMsats = params.amountMsats ?: 0,
                createdAt = System.currentTimeMillis() / 1000,
                expiresAt = System.currentTimeMillis() / 1000 + (params.expiry ?: 3600)
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("payment_request", params.invoice)
                params.feeLimitMsat?.let { 
                    put("fee_limit", JSONObject().apply {
                        put("fixed_msat", it)
                    })
                }
            }
            
            val response = makeRequest("POST", "/v1/channels/transactions", body)
            
            val paymentError = response.optString("payment_error", "")
            if (paymentError.isNotEmpty()) {
                throw RuntimeException("LND payment error: $paymentError")
            }
            
            val preimageBytes = Base64.decode(response.optString("payment_preimage", ""))
            val preimage = preimageBytes.joinToString("") { "%02x".format(it) }
            
            val paymentHashBytes = Base64.decode(response.optString("payment_hash", ""))
            val paymentHash = paymentHashBytes.joinToString("") { "%02x".format(it) }
            
            PayInvoiceResponse(
                paymentHash = paymentHash,
                preimage = preimage,
                feeMsats = response.optLong("payment_route.total_fees_msat", 0)
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun lookupInvoice(paymentHash: String): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            // Convert hex payment hash to base64 for the URL
            val hashBytes = paymentHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val hashBase64 = Base64.UrlSafe.encode(hashBytes)
            
            val response = makeRequest("GET", "/v1/invoice/$hashBase64", null)
            
            LightningTransaction(
                type = "incoming",
                invoice = response.optString("payment_request", ""),
                paymentHash = paymentHash,
                description = response.optString("memo", ""),
                amountMsats = response.optLong("value_msat", 0),
                createdAt = response.optLong("creation_date", 0),
                settledAt = response.optLong("settle_date", 0)
            )
        }
    }

    override suspend fun listTransactions(from: Int, limit: Int): Result<List<LightningTransaction>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = makeRequest("GET", "/v1/invoices?num_max_invoices=$limit", null)
            val invoices = response.optJSONArray("invoices") ?: return@runCatching emptyList()
            
            (0 until invoices.length()).map { i ->
                val inv = invoices.getJSONObject(i)
                LightningTransaction(
                    type = "incoming",
                    invoice = inv.optString("payment_request", ""),
                    paymentHash = inv.optString("r_hash", ""),
                    description = inv.optString("memo", ""),
                    amountMsats = inv.optLong("value_msat", 0),
                    createdAt = inv.optLong("creation_date", 0),
                    settledAt = inv.optLong("settle_date", 0)
                )
            }
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            getInfo()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "LND node not available", e)
            false
        }
    }

    private fun makeRequest(method: String, path: String, body: JSONObject?): JSONObject {
        val urlStr = baseUrl.trimEnd('/') + path
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        
        connection.apply {
            requestMethod = method
            doOutput = body != null
            connectTimeout = DEFAULT_TIMEOUT_MS
            readTimeout = DEFAULT_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Grpc-Metadata-macaroon", macaroon)
        }
        
        try {
            if (body != null) {
                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray())
                }
            }
            
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                Log.w(TAG, "LND API error: $responseCode - $errorBody")
                throw RuntimeException("LND API error: $responseCode")
            }
            
            val responseBody = connection.inputStream.bufferedReader().readText()
            return if (responseBody.isNotEmpty()) JSONObject(responseBody) else JSONObject()
        } finally {
            connection.disconnect()
        }
    }
}
