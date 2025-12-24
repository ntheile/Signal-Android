package org.thoughtcrime.securesms.payments.engine.lightning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.util.logging.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Strike API implementation of LightningNode.
 * 
 * Strike provides a simple REST API for Lightning payments.
 * This implementation uses the Strike API to create invoices and pay invoices.
 * 
 * @see <a href="https://docs.strike.me/api/">Strike API Documentation</a>
 */
class StrikeNode(private val apiKey: String) : LightningNode {

    companion object {
        private val TAG = Log.tag(StrikeNode::class.java)
        private const val STRIKE_API_URL = "https://api.strike.me"
        private const val DEFAULT_TIMEOUT_MS = 30000
    }

    override suspend fun getInfo(): NodeInfo = withContext(Dispatchers.IO) {
        try {
            val response = makeRequest("GET", "/v1/accounts/me", null)
            val balances = response.optJSONArray("currencies") ?: JSONArray()
            var btcBalanceSats = 0L
            
            for (i in 0 until balances.length()) {
                val currency = balances.getJSONObject(i)
                if (currency.optString("currency") == "BTC") {
                    // Strike returns BTC balance, convert to sats
                    val btcAmount = currency.optDouble("amount", 0.0)
                    btcBalanceSats = (btcAmount * 100_000_000).toLong()
                    break
                }
            }
            
            NodeInfo(
                alias = response.optString("handle", "Strike Wallet"),
                pubkey = response.optString("id", ""),
                network = "bitcoin",
                blockHeight = 0,
                sendBalanceMsat = btcBalanceSats * 1000,
                receiveBalanceMsat = Long.MAX_VALUE // Strike can receive any amount
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get Strike info", e)
            throw e
        }
    }

    override suspend fun createInvoice(params: CreateInvoiceParams): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: Create an invoice
            val amountBtc = (params.amountMsats ?: 0) / 1000.0 / 100_000_000.0
            val invoiceRequest = JSONObject().apply {
                put("correlationId", UUID.randomUUID().toString())
                put("description", params.description ?: "Signal payment")
                put("amount", JSONObject().apply {
                    put("amount", String.format("%.8f", amountBtc))
                    put("currency", "BTC")
                })
            }
            
            val invoiceResponse = makeRequest("POST", "/v1/invoices", invoiceRequest)
            val invoiceId = invoiceResponse.getString("invoiceId")
            
            // Step 2: Get the Lightning invoice
            val quoteResponse = makeRequest("POST", "/v1/invoices/$invoiceId/quote", null)
            val lnInvoice = quoteResponse.optString("lnInvoice", "")
            
            if (lnInvoice.isEmpty()) {
                throw RuntimeException("Strike did not return a Lightning invoice")
            }
            
            LightningTransaction(
                type = "incoming",
                invoice = lnInvoice,
                paymentHash = invoiceId,
                description = params.description ?: "",
                amountMsats = params.amountMsats ?: 0,
                createdAt = System.currentTimeMillis() / 1000,
                expiresAt = System.currentTimeMillis() / 1000 + 3600
            )
        }
    }

    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            // Step 1: Create a payment quote
            val quoteRequest = JSONObject().apply {
                put("lnInvoice", params.invoice)
                put("sourceCurrency", "BTC")
            }
            
            val quoteResponse = makeRequest("POST", "/v1/payment-quotes/lightning", quoteRequest)
            val paymentQuoteId = quoteResponse.getString("paymentQuoteId")
            
            // Step 2: Execute the payment
            val payResponse = makeRequest("PATCH", "/v1/payment-quotes/$paymentQuoteId/execute", null)
            
            PayInvoiceResponse(
                paymentHash = payResponse.optString("paymentId", paymentQuoteId),
                preimage = payResponse.optString("preimage", ""),
                feeMsats = 0 // Strike absorbs fees
            )
        }
    }

    override suspend fun lookupInvoice(paymentHash: String): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            val response = makeRequest("GET", "/v1/invoices/$paymentHash", null)
            
            LightningTransaction(
                type = "incoming",
                invoice = "",
                paymentHash = paymentHash,
                description = response.optString("description", ""),
                amountMsats = 0,
                createdAt = 0,
                settledAt = if (response.optString("state") == "COMPLETED") System.currentTimeMillis() / 1000 else 0
            )
        }
    }

    override suspend fun listTransactions(from: Int, limit: Int): Result<List<LightningTransaction>> = withContext(Dispatchers.IO) {
        runCatching {
            // Strike doesn't have a direct list transactions endpoint for Lightning
            // Return empty list for now
            emptyList()
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            getInfo()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Strike node not available", e)
            false
        }
    }

    private fun makeRequest(method: String, path: String, body: JSONObject?): JSONObject {
        val url = URL("$STRIKE_API_URL$path")
        val connection = url.openConnection() as HttpURLConnection
        
        connection.apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") {
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            doOutput = body != null
            connectTimeout = DEFAULT_TIMEOUT_MS
            readTimeout = DEFAULT_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
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
                Log.w(TAG, "Strike API error: $responseCode - $errorBody")
                throw RuntimeException("Strike API error: $responseCode")
            }
            
            val responseBody = connection.inputStream.bufferedReader().readText()
            return if (responseBody.isNotEmpty()) JSONObject(responseBody) else JSONObject()
        } finally {
            connection.disconnect()
        }
    }
}
