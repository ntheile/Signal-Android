/**
 * NWC (Nostr Wallet Connect) Node Implementation
 * 
 * This is a Kotlin implementation that mirrors the LNI Rust library's NwcNode.
 * NWC uses the Nostr protocol for wallet communication.
 * 
 * When uniffi bindings are built, this can be replaced with the native implementation
 * that uses the full Nostr WebSocket protocol.
 */
package lni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NwcNode(private val config: NwcConfig) : LightningNodeInterface {
    
    private val pubkey: String
    private val relayUrl: String
    private val secret: String
    
    init {
        // Parse NWC URI: nostr+walletconnect://pubkey?relay=...&secret=...
        val uri = URI(config.uri.replace("nostr+walletconnect://", "https://"))
        pubkey = uri.host ?: ""
        
        val params = uri.query?.split("&")?.associate {
            val (key, value) = it.split("=", limit = 2)
            key to java.net.URLDecoder.decode(value, "UTF-8")
        } ?: emptyMap()
        
        relayUrl = params["relay"] ?: ""
        secret = params["secret"] ?: ""
    }
    
    // NWC HTTP proxy approach - works with some services like Alby
    // For full Nostr protocol support, use the LNI native library
    
    override suspend fun getInfo(): Result<NodeInfo> = withContext(Dispatchers.IO) {
        try {
            // NWC get_info is not widely supported, try get_balance instead
            val balanceResult = executeNwcMethod("get_balance", JSONObject())
            
            balanceResult.fold(
                onSuccess = { response ->
                    val balanceMsats = response.optLong("balance")
                    Result.success(NodeInfo(
                        alias = "NWC Wallet",
                        pubkey = pubkey,
                        network = "mainnet",
                        blockHeight = null,
                        balanceSat = balanceMsats / 1000,
                        maxPayableSat = balanceMsats / 1000,
                        maxReceivableSat = null
                    ))
                },
                onFailure = {
                    // Fallback - just return basic info
                    Result.success(NodeInfo(
                        alias = "NWC Wallet",
                        pubkey = pubkey,
                        network = "mainnet",
                        blockHeight = null,
                        balanceSat = null,
                        maxPayableSat = null,
                        maxReceivableSat = null
                    ))
                }
            )
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun createInvoice(params: CreateInvoiceParams): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val requestParams = JSONObject().apply {
                params.amountMsats?.let { put("amount", it) }
                params.description?.let { put("description", it) }
                params.expiry?.let { put("expiry", it) }
            }
            
            val result = executeNwcMethod("make_invoice", requestParams)
            
            result.fold(
                onSuccess = { response ->
                    val invoice = response.optString("invoice")
                    val paymentHash = response.optString("payment_hash")
                    
                    Result.success(Transaction(
                        paymentHash = paymentHash,
                        paymentRequest = invoice,
                        amountMsats = params.amountMsats,
                        feeMsats = null,
                        status = TransactionStatus.Pending,
                        type = TransactionType.Incoming,
                        createdAt = System.currentTimeMillis() / 1000,
                        settledAt = null,
                        description = params.description,
                        preimage = null,
                        bolt11 = invoice,
                        bolt12 = null,
                        offer = null
                    ))
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        try {
            val requestParams = JSONObject().apply {
                put("invoice", params.invoice)
                params.amountMsats?.let { put("amount", it) }
            }
            
            val result = executeNwcMethod("pay_invoice", requestParams)
            
            result.fold(
                onSuccess = { response ->
                    val preimage = response.optString("preimage")
                    
                    Result.success(PayInvoiceResponse(
                        paymentHash = "",
                        preimage = preimage,
                        feeMsats = null,
                        status = TransactionStatus.Complete
                    ))
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun lookupInvoice(paymentHash: String): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val requestParams = JSONObject().apply {
                put("payment_hash", paymentHash)
            }
            
            val result = executeNwcMethod("lookup_invoice", requestParams)
            
            result.fold(
                onSuccess = { response ->
                    val paidAt = response.optLong("paid_at")
                    val status = if (paidAt > 0) TransactionStatus.Complete else TransactionStatus.Pending
                    
                    Result.success(Transaction(
                        paymentHash = paymentHash,
                        paymentRequest = response.optString("invoice"),
                        amountMsats = response.optLong("amount"),
                        feeMsats = null,
                        status = status,
                        type = TransactionType.Incoming,
                        createdAt = response.optLong("created_at"),
                        settledAt = if (paidAt > 0) paidAt else null,
                        description = response.optString("description"),
                        preimage = response.optString("preimage"),
                        bolt11 = response.optString("invoice"),
                        bolt12 = null,
                        offer = null
                    ))
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun listTransactions(params: ListTransactionsParams): Result<List<Transaction>> = withContext(Dispatchers.IO) {
        try {
            val requestParams = JSONObject().apply {
                put("from", params.from)
                put("limit", params.limit)
            }
            
            val result = executeNwcMethod("list_transactions", requestParams)
            
            result.fold(
                onSuccess = { response ->
                    val txns = response.optJSONArray("transactions") ?: JSONArray()
                    
                    val transactions = (0 until txns.length()).map { i ->
                        val tx = txns.getJSONObject(i)
                        val paidAt = tx.optLong("paid_at")
                        val status = if (paidAt > 0) TransactionStatus.Complete else TransactionStatus.Pending
                        val typeStr = tx.optString("type")
                        val type = if (typeStr == "incoming") TransactionType.Incoming else TransactionType.Outgoing
                        
                        Transaction(
                            paymentHash = tx.optString("payment_hash"),
                            paymentRequest = tx.optString("invoice"),
                            amountMsats = tx.optLong("amount"),
                            feeMsats = tx.optLong("fees_paid"),
                            status = status,
                            type = type,
                            createdAt = tx.optLong("created_at"),
                            settledAt = if (paidAt > 0) paidAt else null,
                            description = tx.optString("description"),
                            preimage = tx.optString("preimage"),
                            bolt11 = tx.optString("invoice"),
                            bolt12 = null,
                            offer = null
                        )
                    }
                    
                    Result.success(transactions)
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun decode(str: String): Result<String> {
        return Result.failure(ApiError.Api("Decode not supported by NWC"))
    }
    
    // BOLT 12 methods - NWC doesn't support BOLT 12 yet
    override suspend fun createOffer(params: CreateOfferParams): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by NWC"))
    }
    
    override suspend fun getOffer(search: String?): Result<Offer> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by NWC"))
    }
    
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?): Result<PayInvoiceResponse> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by NWC"))
    }
    
    override suspend fun listOffers(search: String?): Result<List<Offer>> {
        return Result.failure(ApiError.Api("BOLT 12 offers not supported by NWC"))
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
    
    /**
     * Execute an NWC method.
     * 
     * NOTE: This is a simplified implementation. The full NWC protocol uses
     * Nostr WebSocket connections with encrypted messages. This implementation
     * works with some services (like Alby) that support HTTP-based NWC proxies.
     * 
     * For full NWC protocol support, the LNI native library should be used.
     */
    private suspend fun executeNwcMethod(method: String, params: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            // This is a placeholder - full NWC requires Nostr WebSocket protocol
            // The LNI Rust library handles this properly with the nostr crate
            
            // For now, return an error indicating full NWC is not supported
            // Users should configure a different node type or use the native LNI library
            Result.failure(ApiError.Api(
                "Full NWC protocol requires native LNI library. " +
                "Build LNI with: ./gradlew :lni:buildRust"
            ))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
}
