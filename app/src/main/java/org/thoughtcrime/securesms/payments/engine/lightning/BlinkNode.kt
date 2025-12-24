package org.thoughtcrime.securesms.payments.engine.lightning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.signal.core.util.logging.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blink (formerly Galoy) API implementation of LightningNode.
 * 
 * Blink provides a GraphQL API for Lightning payments.
 * This implementation uses the Blink GraphQL API.
 * 
 * @see <a href="https://dev.blink.sv/">Blink Developer Documentation</a>
 */
class BlinkNode(private val apiKey: String) : LightningNode {

    companion object {
        private val TAG = Log.tag(BlinkNode::class.java)
        private const val BLINK_API_URL = "https://api.blink.sv/graphql"
        private const val DEFAULT_TIMEOUT_MS = 30000
    }

    override suspend fun getInfo(): NodeInfo = withContext(Dispatchers.IO) {
        try {
            val query = """
                query Me {
                    me {
                        id
                        defaultAccount {
                            id
                            wallets {
                                id
                                walletCurrency
                                balance
                            }
                        }
                    }
                }
            """.trimIndent()
            
            val response = makeGraphQLRequest(query, null)
            val me = response.getJSONObject("data").getJSONObject("me")
            val wallets = me.getJSONObject("defaultAccount").getJSONArray("wallets")
            
            var btcBalanceSats = 0L
            for (i in 0 until wallets.length()) {
                val wallet = wallets.getJSONObject(i)
                if (wallet.optString("walletCurrency") == "BTC") {
                    btcBalanceSats = wallet.optLong("balance", 0)
                    break
                }
            }
            
            NodeInfo(
                alias = "Blink Wallet",
                pubkey = me.optString("id", ""),
                network = "bitcoin",
                blockHeight = 0,
                sendBalanceMsat = btcBalanceSats * 1000,
                receiveBalanceMsat = Long.MAX_VALUE
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get Blink info", e)
            throw e
        }
    }

    override suspend fun createInvoice(params: CreateInvoiceParams): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            val amountSats = (params.amountMsats ?: 0) / 1000
            
            // First get the wallet ID
            val walletQuery = """
                query Me {
                    me {
                        defaultAccount {
                            wallets {
                                id
                                walletCurrency
                            }
                        }
                    }
                }
            """.trimIndent()
            
            val walletResponse = makeGraphQLRequest(walletQuery, null)
            val wallets = walletResponse.getJSONObject("data")
                .getJSONObject("me")
                .getJSONObject("defaultAccount")
                .getJSONArray("wallets")
            
            var walletId = ""
            for (i in 0 until wallets.length()) {
                val wallet = wallets.getJSONObject(i)
                if (wallet.optString("walletCurrency") == "BTC") {
                    walletId = wallet.getString("id")
                    break
                }
            }
            
            if (walletId.isEmpty()) {
                throw RuntimeException("No BTC wallet found")
            }
            
            // Create the invoice
            val mutation = """
                mutation LnInvoiceCreate(${'$'}input: LnInvoiceCreateInput!) {
                    lnInvoiceCreate(input: ${'$'}input) {
                        invoice {
                            paymentRequest
                            paymentHash
                            satoshis
                        }
                        errors {
                            message
                        }
                    }
                }
            """.trimIndent()
            
            val variables = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("walletId", walletId)
                    put("amount", amountSats)
                    put("memo", params.description ?: "Signal payment")
                })
            }
            
            val response = makeGraphQLRequest(mutation, variables)
            val invoice = response.getJSONObject("data")
                .getJSONObject("lnInvoiceCreate")
                .getJSONObject("invoice")
            
            LightningTransaction(
                type = "incoming",
                invoice = invoice.getString("paymentRequest"),
                paymentHash = invoice.optString("paymentHash", ""),
                description = params.description ?: "",
                amountMsats = params.amountMsats ?: 0,
                createdAt = System.currentTimeMillis() / 1000,
                expiresAt = System.currentTimeMillis() / 1000 + 3600
            )
        }
    }

    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        runCatching {
            // First get the wallet ID
            val walletQuery = """
                query Me {
                    me {
                        defaultAccount {
                            wallets {
                                id
                                walletCurrency
                            }
                        }
                    }
                }
            """.trimIndent()
            
            val walletResponse = makeGraphQLRequest(walletQuery, null)
            val wallets = walletResponse.getJSONObject("data")
                .getJSONObject("me")
                .getJSONObject("defaultAccount")
                .getJSONArray("wallets")
            
            var walletId = ""
            for (i in 0 until wallets.length()) {
                val wallet = wallets.getJSONObject(i)
                if (wallet.optString("walletCurrency") == "BTC") {
                    walletId = wallet.getString("id")
                    break
                }
            }
            
            if (walletId.isEmpty()) {
                throw RuntimeException("No BTC wallet found")
            }
            
            // Pay the invoice
            val mutation = """
                mutation LnInvoicePaymentSend(${'$'}input: LnInvoicePaymentInput!) {
                    lnInvoicePaymentSend(input: ${'$'}input) {
                        status
                        errors {
                            message
                        }
                    }
                }
            """.trimIndent()
            
            val variables = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("walletId", walletId)
                    put("paymentRequest", params.invoice)
                })
            }
            
            val response = makeGraphQLRequest(mutation, variables)
            val result = response.getJSONObject("data").getJSONObject("lnInvoicePaymentSend")
            val status = result.optString("status", "")
            
            if (status != "SUCCESS" && status != "PENDING") {
                val errors = result.optJSONArray("errors")
                val errorMsg = if (errors != null && errors.length() > 0) {
                    errors.getJSONObject(0).optString("message", "Payment failed")
                } else {
                    "Payment failed: $status"
                }
                throw RuntimeException(errorMsg)
            }
            
            PayInvoiceResponse(
                paymentHash = "",
                preimage = "",
                feeMsats = 0
            )
        }
    }

    override suspend fun lookupInvoice(paymentHash: String): Result<LightningTransaction> = withContext(Dispatchers.IO) {
        runCatching {
            LightningTransaction(
                type = "incoming",
                paymentHash = paymentHash
            )
        }
    }

    override suspend fun listTransactions(from: Int, limit: Int): Result<List<LightningTransaction>> = withContext(Dispatchers.IO) {
        runCatching {
            emptyList()
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            getInfo()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Blink node not available", e)
            false
        }
    }

    private fun makeGraphQLRequest(query: String, variables: JSONObject?): JSONObject {
        val url = URL(BLINK_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        
        val body = JSONObject().apply {
            put("query", query)
            if (variables != null) {
                put("variables", variables)
            }
        }
        
        connection.apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = DEFAULT_TIMEOUT_MS
            readTimeout = DEFAULT_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-KEY", apiKey)
        }
        
        try {
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                Log.w(TAG, "Blink API error: $responseCode - $errorBody")
                throw RuntimeException("Blink API error: $responseCode")
            }
            
            val responseBody = connection.inputStream.bufferedReader().readText()
            val response = JSONObject(responseBody)
            
            if (response.has("errors")) {
                val errors = response.getJSONArray("errors")
                if (errors.length() > 0) {
                    throw RuntimeException(errors.getJSONObject(0).optString("message", "GraphQL error"))
                }
            }
            
            return response
        } finally {
            connection.disconnect()
        }
    }
}
