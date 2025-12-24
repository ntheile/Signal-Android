/**
 * Blink Node Implementation
 * 
 * This is a Kotlin implementation that mirrors the LNI Rust library's BlinkNode.
 * When uniffi bindings are built, this can be replaced with the native implementation.
 */
package lni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.json.JSONArray

class BlinkNode(private val config: BlinkConfig) : LightningNodeInterface {
    
    private val baseUrl = config.baseUrl ?: "https://api.blink.sv/graphql"
    
    private fun executeGraphQL(query: String, variables: JSONObject? = null): String {
        val url = URL(baseUrl)
        val connection = url.openConnection() as HttpsURLConnection
        
        connection.setRequestProperty("X-API-KEY", config.apiKey)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = (config.httpTimeout ?: 120) * 1000
        connection.readTimeout = (config.httpTimeout ?: 120) * 1000
        
        val body = JSONObject().apply {
            put("query", query)
            variables?.let { put("variables", it) }
        }
        
        connection.outputStream.use { os ->
            os.write(body.toString().toByteArray())
        }
        
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
    
    override suspend fun getInfo(): Result<NodeInfo> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query Me {
                    me {
                        defaultAccount {
                            wallets {
                                id
                                walletCurrency
                                balance
                            }
                        }
                    }
                }
            """.trimIndent()
            
            val response = executeGraphQL(query)
            val json = JSONObject(response)
            val data = json.optJSONObject("data")
            val me = data?.optJSONObject("me")
            val defaultAccount = me?.optJSONObject("defaultAccount")
            val wallets = defaultAccount?.optJSONArray("wallets") ?: JSONArray()
            
            var btcBalance: Long? = null
            for (i in 0 until wallets.length()) {
                val wallet = wallets.getJSONObject(i)
                if (wallet.optString("walletCurrency") == "BTC") {
                    btcBalance = wallet.optLong("balance")
                }
            }
            
            Result.success(NodeInfo(
                alias = "Blink",
                pubkey = null,
                network = "mainnet",
                blockHeight = null,
                balanceSat = btcBalance,
                maxPayableSat = btcBalance,
                maxReceivableSat = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun createInvoice(params: CreateInvoiceParams): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            // First get the BTC wallet ID
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
            
            val walletResponse = executeGraphQL(walletQuery)
            val walletJson = JSONObject(walletResponse)
            val wallets = walletJson.optJSONObject("data")
                ?.optJSONObject("me")
                ?.optJSONObject("defaultAccount")
                ?.optJSONArray("wallets") ?: JSONArray()
            
            var btcWalletId: String? = null
            for (i in 0 until wallets.length()) {
                val wallet = wallets.getJSONObject(i)
                if (wallet.optString("walletCurrency") == "BTC") {
                    btcWalletId = wallet.optString("id")
                    break
                }
            }
            
            if (btcWalletId == null) {
                return@withContext Result.failure(ApiError.Api("No BTC wallet found"))
            }
            
            val amountSats = (params.amountMsats ?: 0) / 1000
            
            val mutation = """
                mutation LnInvoiceCreate(${"$"}input: LnInvoiceCreateInput!) {
                    lnInvoiceCreate(input: ${"$"}input) {
                        invoice {
                            paymentHash
                            paymentRequest
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
                    put("walletId", btcWalletId)
                    put("amount", amountSats)
                    params.description?.let { put("memo", it) }
                })
            }
            
            val response = executeGraphQL(mutation, variables)
            val json = JSONObject(response)
            val invoice = json.optJSONObject("data")
                ?.optJSONObject("lnInvoiceCreate")
                ?.optJSONObject("invoice")
            
            if (invoice == null) {
                val errors = json.optJSONObject("data")
                    ?.optJSONObject("lnInvoiceCreate")
                    ?.optJSONArray("errors")
                val errorMsg = errors?.optJSONObject(0)?.optString("message") ?: "Unknown error"
                return@withContext Result.failure(ApiError.Api(errorMsg))
            }
            
            Result.success(Transaction(
                paymentHash = invoice.optString("paymentHash"),
                paymentRequest = invoice.optString("paymentRequest"),
                amountMsats = params.amountMsats,
                feeMsats = null,
                status = TransactionStatus.Pending,
                type = TransactionType.Incoming,
                createdAt = System.currentTimeMillis() / 1000,
                settledAt = null,
                description = params.description,
                preimage = null,
                bolt11 = invoice.optString("paymentRequest"),
                bolt12 = null,
                offer = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        try {
            // First get the BTC wallet ID
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
            
            val walletResponse = executeGraphQL(walletQuery)
            val walletJson = JSONObject(walletResponse)
            val wallets = walletJson.optJSONObject("data")
                ?.optJSONObject("me")
                ?.optJSONObject("defaultAccount")
                ?.optJSONArray("wallets") ?: JSONArray()
            
            var btcWalletId: String? = null
            for (i in 0 until wallets.length()) {
                val wallet = wallets.getJSONObject(i)
                if (wallet.optString("walletCurrency") == "BTC") {
                    btcWalletId = wallet.optString("id")
                    break
                }
            }
            
            if (btcWalletId == null) {
                return@withContext Result.failure(ApiError.Api("No BTC wallet found"))
            }
            
            val mutation = """
                mutation LnInvoicePaymentSend(${"$"}input: LnInvoicePaymentInput!) {
                    lnInvoicePaymentSend(input: ${"$"}input) {
                        status
                        errors {
                            message
                        }
                    }
                }
            """.trimIndent()
            
            val variables = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("walletId", btcWalletId)
                    put("paymentRequest", params.invoice)
                    params.amountMsats?.let { put("amount", it / 1000) }
                })
            }
            
            val response = executeGraphQL(mutation, variables)
            val json = JSONObject(response)
            val result = json.optJSONObject("data")?.optJSONObject("lnInvoicePaymentSend")
            
            val statusStr = result?.optString("status")
            val status = when (statusStr) {
                "SUCCESS" -> TransactionStatus.Complete
                "FAILURE" -> TransactionStatus.Failed
                else -> TransactionStatus.Pending
            }
            
            if (status == TransactionStatus.Failed) {
                val errors = result?.optJSONArray("errors")
                val errorMsg = errors?.optJSONObject(0)?.optString("message") ?: "Payment failed"
                return@withContext Result.failure(ApiError.Api(errorMsg))
            }
            
            Result.success(PayInvoiceResponse(
                paymentHash = "",
                preimage = null,
                feeMsats = null,
                status = status
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun lookupInvoice(paymentHash: String): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query LnInvoicePaymentStatus(${"$"}input: LnInvoicePaymentStatusInput!) {
                    lnInvoicePaymentStatus(input: ${"$"}input) {
                        status
                        errors {
                            message
                        }
                    }
                }
            """.trimIndent()
            
            val variables = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("paymentHash", paymentHash)
                })
            }
            
            val response = executeGraphQL(query, variables)
            val json = JSONObject(response)
            val result = json.optJSONObject("data")?.optJSONObject("lnInvoicePaymentStatus")
            
            val statusStr = result?.optString("status")
            val status = when (statusStr) {
                "PAID" -> TransactionStatus.Complete
                "EXPIRED" -> TransactionStatus.Failed
                else -> TransactionStatus.Pending
            }
            
            Result.success(Transaction(
                paymentHash = paymentHash,
                paymentRequest = null,
                amountMsats = null,
                feeMsats = null,
                status = status,
                type = TransactionType.Incoming,
                createdAt = null,
                settledAt = null,
                description = null,
                preimage = null,
                bolt11 = null,
                bolt12 = null,
                offer = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun listTransactions(params: ListTransactionsParams): Result<List<Transaction>> = withContext(Dispatchers.IO) {
        try {
            val query = """
                query Me {
                    me {
                        defaultAccount {
                            transactions(first: ${params.limit}) {
                                edges {
                                    node {
                                        id
                                        status
                                        direction
                                        memo
                                        settlementAmount
                                        settlementFee
                                        createdAt
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()
            
            val response = executeGraphQL(query)
            val json = JSONObject(response)
            val edges = json.optJSONObject("data")
                ?.optJSONObject("me")
                ?.optJSONObject("defaultAccount")
                ?.optJSONObject("transactions")
                ?.optJSONArray("edges") ?: JSONArray()
            
            val transactions = (0 until edges.length()).map { i ->
                val node = edges.getJSONObject(i).optJSONObject("node") ?: JSONObject()
                
                val statusStr = node.optString("status")
                val status = when (statusStr) {
                    "SUCCESS" -> TransactionStatus.Complete
                    "FAILURE" -> TransactionStatus.Failed
                    else -> TransactionStatus.Pending
                }
                
                val directionStr = node.optString("direction")
                val type = when (directionStr) {
                    "RECEIVE" -> TransactionType.Incoming
                    else -> TransactionType.Outgoing
                }
                
                Transaction(
                    paymentHash = node.optString("id"),
                    paymentRequest = null,
                    amountMsats = node.optLong("settlementAmount") * 1000,
                    feeMsats = node.optLong("settlementFee") * 1000,
                    status = status,
                    type = type,
                    createdAt = null,
                    settledAt = null,
                    description = node.optString("memo"),
                    preimage = null,
                    bolt11 = null,
                    bolt12 = null,
                    offer = null
                )
            }
            
            Result.success(transactions)
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun decode(str: String): Result<String> {
        return Result.failure(ApiError.Api("Decode not supported by Blink API"))
    }
    
    // BOLT 12 methods - Blink doesn't support BOLT 12
    override suspend fun createOffer(params: CreateOfferParams): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Blink"))
    }
    
    override suspend fun getOffer(search: String?): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Blink"))
    }
    
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?): Result<PayInvoiceResponse> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Blink"))
    }
    
    override suspend fun listOffers(search: String?): Result<List<Offer>> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Blink"))
    }
    
    override suspend fun onInvoiceEvents(params: OnInvoiceEventParams, callback: OnInvoiceEventCallback) {
        val startTime = System.currentTimeMillis() / 1000
        val maxTime = startTime + params.maxPollingSec
        
        while (System.currentTimeMillis() / 1000 < maxTime) {
            val result = lookupInvoice(params.paymentHash)
            result.onSuccess { tx ->
                when (tx.status) {
                    TransactionStatus.Complete -> {
                        callback.success(tx)
                        return
                    }
                    TransactionStatus.Failed -> {
                        callback.failure(tx)
                        return
                    }
                    TransactionStatus.Pending -> {
                        callback.pending(tx)
                    }
                }
            }
            result.onFailure {
                callback.failure(null)
                return
            }
            
            kotlinx.coroutines.delay(params.pollingDelaySec * 1000)
        }
        
        // Timeout
        callback.failure(null)
    }
}
