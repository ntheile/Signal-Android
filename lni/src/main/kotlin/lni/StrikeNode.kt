/**
 * Strike Node Implementation
 * 
 * This is a Kotlin implementation that mirrors the LNI Rust library's StrikeNode.
 * When uniffi bindings are built, this can be replaced with the native implementation.
 */
package lni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.json.JSONArray

class StrikeNode(private val config: StrikeConfig) : LightningNodeInterface {
    
    private val baseUrl = config.baseUrl ?: "https://api.strike.me/v1"
    
    private fun createConnection(endpoint: String): HttpsURLConnection {
        val url = URL("$baseUrl$endpoint")
        val connection = url.openConnection() as HttpsURLConnection
        
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = (config.httpTimeout ?: 120) * 1000
        connection.readTimeout = (config.httpTimeout ?: 120) * 1000
        
        return connection
    }
    
    override suspend fun getInfo(): Result<NodeInfo> = withContext(Dispatchers.IO) {
        try {
            // Get account balance
            val balanceConnection = createConnection("/balances")
            balanceConnection.requestMethod = "GET"
            
            val balanceResponse = balanceConnection.inputStream.bufferedReader().use { it.readText() }
            val balances = JSONArray(balanceResponse)
            
            var btcBalance: Long? = null
            for (i in 0 until balances.length()) {
                val balance = balances.getJSONObject(i)
                if (balance.optString("currency") == "BTC") {
                    val available = balance.optString("available")
                    btcBalance = (available.toDoubleOrNull()?.times(100_000_000))?.toLong()
                }
            }
            
            Result.success(NodeInfo(
                alias = "Strike",
                pubkey = null,
                network = "mainnet",
                blockHeight = null,
                balanceSat = btcBalance,
                maxPayableSat = btcBalance,
                maxReceivableSat = null // Strike doesn't have a receiving limit exposed
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun createInvoice(params: CreateInvoiceParams): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Create an invoice
            val connection = createConnection("/invoices")
            connection.requestMethod = "POST"
            connection.doOutput = true
            
            val amountSats = (params.amountMsats ?: 0) / 1000
            
            val body = JSONObject().apply {
                put("correlationId", java.util.UUID.randomUUID().toString())
                put("description", params.description ?: "Signal Payment")
                put("amount", JSONObject().apply {
                    put("amount", amountSats.toDouble() / 100_000_000) // Convert to BTC
                    put("currency", "BTC")
                })
            }
            
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val invoiceId = json.optString("invoiceId")
            
            // Step 2: Generate the quote to get the BOLT11 invoice
            val quoteConnection = createConnection("/invoices/$invoiceId/quote")
            quoteConnection.requestMethod = "POST"
            quoteConnection.doOutput = true
            quoteConnection.outputStream.use { os ->
                os.write("{}".toByteArray())
            }
            
            val quoteResponse = quoteConnection.inputStream.bufferedReader().use { it.readText() }
            val quoteJson = JSONObject(quoteResponse)
            val bolt11 = quoteJson.optString("lnInvoice")
            
            Result.success(Transaction(
                paymentHash = invoiceId, // Strike uses invoiceId instead of payment hash
                paymentRequest = bolt11,
                amountMsats = params.amountMsats,
                feeMsats = null,
                status = TransactionStatus.Pending,
                type = TransactionType.Incoming,
                createdAt = System.currentTimeMillis() / 1000,
                settledAt = null,
                description = params.description,
                preimage = null,
                bolt11 = bolt11,
                bolt12 = null,
                offer = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Create a payment quote
            val quoteConnection = createConnection("/payment-quotes/lightning")
            quoteConnection.requestMethod = "POST"
            quoteConnection.doOutput = true
            
            val quoteBody = JSONObject().apply {
                put("lnInvoice", params.invoice)
                put("sourceCurrency", "BTC")
            }
            
            quoteConnection.outputStream.use { os ->
                os.write(quoteBody.toString().toByteArray())
            }
            
            val quoteResponse = quoteConnection.inputStream.bufferedReader().use { it.readText() }
            val quoteJson = JSONObject(quoteResponse)
            val paymentQuoteId = quoteJson.optString("paymentQuoteId")
            
            // Step 2: Execute the payment
            val payConnection = createConnection("/payment-quotes/$paymentQuoteId/execute")
            payConnection.requestMethod = "PATCH"
            payConnection.doOutput = true
            payConnection.outputStream.use { os ->
                os.write("{}".toByteArray())
            }
            
            val payResponse = payConnection.inputStream.bufferedReader().use { it.readText() }
            val payJson = JSONObject(payResponse)
            
            val state = payJson.optString("state")
            val status = when (state) {
                "COMPLETED" -> TransactionStatus.Complete
                "FAILED" -> TransactionStatus.Failed
                else -> TransactionStatus.Pending
            }
            
            Result.success(PayInvoiceResponse(
                paymentHash = paymentQuoteId,
                preimage = payJson.optString("preimage"),
                feeMsats = null,
                status = status
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun lookupInvoice(paymentHash: String): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/invoices/$paymentHash")
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            val state = json.optString("state")
            val status = when (state) {
                "PAID" -> TransactionStatus.Complete
                "CANCELLED", "EXPIRED" -> TransactionStatus.Failed
                else -> TransactionStatus.Pending
            }
            
            val amount = json.optJSONObject("amount")
            val amountBtc = amount?.optDouble("amount") ?: 0.0
            val amountMsats = (amountBtc * 100_000_000 * 1000).toLong()
            
            Result.success(Transaction(
                paymentHash = paymentHash,
                paymentRequest = null,
                amountMsats = amountMsats,
                feeMsats = null,
                status = status,
                type = TransactionType.Incoming,
                createdAt = null,
                settledAt = null,
                description = json.optString("description"),
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
            val connection = createConnection("/invoices?take=${params.limit}&skip=${params.from}")
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val items = json.optJSONArray("items") ?: JSONArray()
            
            val transactions = (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                val state = item.optString("state")
                val status = when (state) {
                    "PAID" -> TransactionStatus.Complete
                    "CANCELLED", "EXPIRED" -> TransactionStatus.Failed
                    else -> TransactionStatus.Pending
                }
                
                val amount = item.optJSONObject("amount")
                val amountBtc = amount?.optDouble("amount") ?: 0.0
                val amountMsats = (amountBtc * 100_000_000 * 1000).toLong()
                
                Transaction(
                    paymentHash = item.optString("invoiceId"),
                    paymentRequest = null,
                    amountMsats = amountMsats,
                    feeMsats = null,
                    status = status,
                    type = TransactionType.Incoming,
                    createdAt = null,
                    settledAt = null,
                    description = item.optString("description"),
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
        return Result.failure(ApiError.Api("Decode not supported by Strike API"))
    }
    
    // BOLT 12 methods - Strike doesn't support BOLT 12
    override suspend fun createOffer(params: CreateOfferParams): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Strike"))
    }
    
    override suspend fun getOffer(search: String?): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Strike"))
    }
    
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?): Result<PayInvoiceResponse> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Strike"))
    }
    
    override suspend fun listOffers(search: String?): Result<List<Offer>> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by Strike"))
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
