/**
 * LND Node Implementation
 * 
 * This is a Kotlin implementation that mirrors the LNI Rust library's LndNode.
 * When uniffi bindings are built, this can be replaced with the native implementation.
 */
package lni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import org.json.JSONObject
import org.json.JSONArray

class LndNode(private val config: LndConfig) : LightningNodeInterface {
    
    private fun createConnection(endpoint: String): HttpsURLConnection {
        val url = URL("${config.url}$endpoint")
        val connection = url.openConnection() as HttpsURLConnection
        
        // Add macaroon header (hex encoded)
        connection.setRequestProperty("Grpc-Metadata-macaroon", config.macaroon)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = (config.httpTimeout ?: 120) * 1000
        connection.readTimeout = (config.httpTimeout ?: 120) * 1000
        
        // Accept invalid certs if configured (for self-signed certs)
        if (config.acceptInvalidCerts == true) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = { _, _ -> true }
        }
        
        return connection
    }
    
    override suspend fun getInfo(): Result<NodeInfo> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/v1/getinfo")
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            Result.success(NodeInfo(
                alias = json.optString("alias"),
                pubkey = json.optString("identity_pubkey"),
                network = json.optString("chains")?.let { 
                    try { JSONArray(it).optJSONObject(0)?.optString("network") } catch (e: Exception) { null }
                },
                blockHeight = json.optLong("block_height"),
                balanceSat = null, // Need to call /v1/balance/channels for this
                maxPayableSat = null,
                maxReceivableSat = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun createInvoice(params: CreateInvoiceParams): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/v1/invoices")
            connection.requestMethod = "POST"
            connection.doOutput = true
            
            val body = JSONObject().apply {
                params.amountMsats?.let { put("value_msat", it) }
                params.description?.let { put("memo", it) }
                params.expiry?.let { put("expiry", it) }
            }
            
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            Result.success(Transaction(
                paymentHash = json.optString("r_hash"),
                paymentRequest = json.optString("payment_request"),
                amountMsats = params.amountMsats,
                feeMsats = null,
                status = TransactionStatus.Pending,
                type = TransactionType.Incoming,
                createdAt = System.currentTimeMillis() / 1000,
                settledAt = null,
                description = params.description,
                preimage = null,
                bolt11 = json.optString("payment_request"),
                bolt12 = null,
                offer = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/v1/channels/transactions")
            connection.requestMethod = "POST"
            connection.doOutput = true
            
            val body = JSONObject().apply {
                put("payment_request", params.invoice)
                params.feeLimitPercentage?.let { 
                    put("fee_limit", JSONObject().apply {
                        put("percent", it.toInt())
                    })
                }
                params.amountMsats?.let { put("amt_msat", it) }
                params.allowSelfPayment?.let { put("allow_self_payment", it) }
            }
            
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray())
            }
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            val status = when (json.optString("status")) {
                "SUCCEEDED" -> TransactionStatus.Complete
                "FAILED" -> TransactionStatus.Failed
                else -> TransactionStatus.Pending
            }
            
            Result.success(PayInvoiceResponse(
                paymentHash = json.optString("payment_hash"),
                preimage = json.optString("payment_preimage"),
                feeMsats = json.optLong("fee_msat"),
                status = status
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun lookupInvoice(paymentHash: String): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/v1/invoice/$paymentHash")
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            
            val state = json.optString("state")
            val status = when (state) {
                "SETTLED" -> TransactionStatus.Complete
                "CANCELED" -> TransactionStatus.Failed
                else -> TransactionStatus.Pending
            }
            
            Result.success(Transaction(
                paymentHash = json.optString("r_hash"),
                paymentRequest = json.optString("payment_request"),
                amountMsats = json.optLong("value_msat"),
                feeMsats = null,
                status = status,
                type = TransactionType.Incoming,
                createdAt = json.optLong("creation_date"),
                settledAt = json.optLong("settle_date"),
                description = json.optString("memo"),
                preimage = json.optString("r_preimage"),
                bolt11 = json.optString("payment_request"),
                bolt12 = null,
                offer = null
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun listTransactions(params: ListTransactionsParams): Result<List<Transaction>> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/v1/invoices?num_max_invoices=${params.limit}&index_offset=${params.from}")
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val invoices = json.optJSONArray("invoices") ?: JSONArray()
            
            val transactions = (0 until invoices.length()).map { i ->
                val inv = invoices.getJSONObject(i)
                val state = inv.optString("state")
                val status = when (state) {
                    "SETTLED" -> TransactionStatus.Complete
                    "CANCELED" -> TransactionStatus.Failed
                    else -> TransactionStatus.Pending
                }
                
                Transaction(
                    paymentHash = inv.optString("r_hash"),
                    paymentRequest = inv.optString("payment_request"),
                    amountMsats = inv.optLong("value_msat"),
                    feeMsats = null,
                    status = status,
                    type = TransactionType.Incoming,
                    createdAt = inv.optLong("creation_date"),
                    settledAt = inv.optLong("settle_date"),
                    description = inv.optString("memo"),
                    preimage = inv.optString("r_preimage"),
                    bolt11 = inv.optString("payment_request"),
                    bolt12 = null,
                    offer = null
                )
            }
            
            Result.success(transactions)
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun decode(str: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val connection = createConnection("/v1/payreq/$str")
            connection.requestMethod = "GET"
            
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    // BOLT 12 methods - LND doesn't natively support BOLT 12 yet
    override suspend fun createOffer(params: CreateOfferParams): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by LND"))
    }
    
    override suspend fun getOffer(search: String?): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by LND"))
    }
    
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?): Result<PayInvoiceResponse> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by LND"))
    }
    
    override suspend fun listOffers(search: String?): Result<List<Offer>> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by LND"))
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
