/**
 * NWC (Nostr Wallet Connect) Node Implementation
 * 
 * This is a wrapper around the native LNI Rust library's NwcNode.
 * It delegates to the UniFFI-generated bindings and converts between
 * the wrapper types and native types.
 */
package lni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Import native LNI types
import uniffi.lni.NwcNode as NativeNwcNode
import uniffi.lni.NwcConfig as NativeNwcConfig
import uniffi.lni.CreateInvoiceParams as NativeCreateInvoiceParams
import uniffi.lni.PayInvoiceParams as NativePayInvoiceParams
import uniffi.lni.ListTransactionsParams as NativeListTransactionsParams
import uniffi.lni.LookupInvoiceParams as NativeLookupInvoiceParams
import uniffi.lni.CreateOfferParams as NativeCreateOfferParams
import uniffi.lni.InvoiceType as NativeInvoiceType
import uniffi.lni.ApiException

class NwcNode(private val config: NwcConfig) : LightningNodeInterface {
    
    private val nativeNode: NativeNwcNode by lazy {
        NativeNwcNode(NativeNwcConfig(
            nwcUri = config.nwcUri,
            socks5Proxy = config.socks5Proxy ?: "",
            acceptInvalidCerts = config.acceptInvalidCerts,
            httpTimeout = config.httpTimeout
        ))
    }
    
    override suspend fun getInfo(): Result<NodeInfo> = withContext(Dispatchers.IO) {
        try {
            val info = nativeNode.getInfo()
            Result.success(NodeInfo(
                alias = info.alias,
                pubkey = info.pubkey,
                network = info.network,
                blockHeight = info.blockHeight,
                balanceSat = info.sendBalanceMsat?.let { it / 1000 },
                maxPayableSat = info.sendBalanceMsat?.let { it / 1000 },
                maxReceivableSat = info.receiveBalanceMsat?.let { it / 1000 }
            ))
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun createInvoice(params: CreateInvoiceParams): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val nativeParams = NativeCreateInvoiceParams(
                invoiceType = when (params.invoiceType) {
                    InvoiceType.Bolt11 -> NativeInvoiceType.BOLT11
                    InvoiceType.Bolt12 -> NativeInvoiceType.BOLT12
                },
                amountMsats = params.amountMsats,
                description = params.description,
                expiry = params.expiry,
                descriptionHash = params.descriptionHash,
                offer = null,
                rPreimage = null,
                isBlinded = false,
                isKeysend = false,
                isAmp = false,
                isPrivate = false
            )
            val tx = nativeNode.createInvoice(nativeParams)
            Result.success(tx.toWrapperTransaction())
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        try {
            val nativeParams = NativePayInvoiceParams(
                invoice = params.invoice,
                feeLimitMsat = null,
                feeLimitPercentage = params.feeLimitPercentage?.toDouble(),
                timeoutSeconds = null,
                amountMsats = params.amountMsats,
                maxParts = null,
                firstHopPubkey = null,
                lastHopPubkey = null,
                allowSelfPayment = params.allowSelfPayment ?: false,
                isAmp = false
            )
            val response = nativeNode.payInvoice(nativeParams)
            Result.success(PayInvoiceResponse(
                paymentHash = response.paymentHash,
                preimage = response.preimage,
                feeMsats = response.feeMsats,
                status = TransactionStatus.Complete
            ))
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun lookupInvoice(paymentHash: String): Result<Transaction> = withContext(Dispatchers.IO) {
        try {
            val nativeParams = NativeLookupInvoiceParams(
                paymentHash = paymentHash,
                search = null
            )
            val tx = nativeNode.lookupInvoice(nativeParams)
            Result.success(tx.toWrapperTransaction())
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun listTransactions(params: ListTransactionsParams): Result<List<Transaction>> = withContext(Dispatchers.IO) {
        try {
            val nativeParams = NativeListTransactionsParams(
                from = params.from.toLong(),
                limit = params.limit.toLong(),
                paymentHash = params.paymentHash,
                search = null
            )
            val transactions = nativeNode.listTransactions(nativeParams)
            Result.success(transactions.map { it.toWrapperTransaction() })
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun decode(str: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val decoded = nativeNode.decode(str)
            Result.success(decoded)
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    // BOLT 12 methods
    override suspend fun createOffer(params: CreateOfferParams): Result<Offer> = withContext(Dispatchers.IO) {
        try {
            val nativeParams = NativeCreateOfferParams(
                description = params.description,
                amountMsats = params.amountMsats
            )
            val offer = nativeNode.createOffer(nativeParams)
            Result.success(offer.toWrapperOffer())
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun getOffer(search: String?): Result<Offer> = withContext(Dispatchers.IO) {
        try {
            val offer = nativeNode.getOffer(search)
            Result.success(offer.toWrapperOffer())
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?): Result<PayInvoiceResponse> = withContext(Dispatchers.IO) {
        try {
            val response = nativeNode.payOffer(offer, amountMsats, payerNote)
            Result.success(PayInvoiceResponse(
                paymentHash = response.paymentHash,
                preimage = response.preimage,
                feeMsats = response.feeMsats,
                status = TransactionStatus.Complete
            ))
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
    }
    
    override suspend fun listOffers(search: String?): Result<List<Offer>> = withContext(Dispatchers.IO) {
        try {
            val offers = nativeNode.listOffers(search)
            Result.success(offers.map { it.toWrapperOffer() })
        } catch (e: ApiException) {
            Result.failure(ApiError.Api(e.message ?: "Unknown API error"))
        } catch (e: Exception) {
            Result.failure(ApiError.Http(e.message ?: "Unknown error"))
        }
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

// Extension functions to convert native types to wrapper types
private fun uniffi.lni.Transaction.toWrapperTransaction(): Transaction {
    // Infer status from settledAt: if > 0 then complete, otherwise pending
    val status = if (this.settledAt > 0) TransactionStatus.Complete else TransactionStatus.Pending
    
    // type is a String in native bindings ("incoming" or "outgoing")
    val txType = when (this.type.lowercase()) {
        "incoming" -> TransactionType.Incoming
        "outgoing" -> TransactionType.Outgoing
        else -> TransactionType.Incoming
    }
    
    return Transaction(
        paymentHash = this.paymentHash,
        paymentRequest = this.invoice,
        amountMsats = this.amountMsats,
        feeMsats = this.feesPaid,
        status = status,
        type = txType,
        createdAt = this.createdAt,
        settledAt = if (this.settledAt > 0) this.settledAt else null,
        description = this.description.ifEmpty { null },
        preimage = this.preimage.ifEmpty { null },
        bolt11 = this.invoice.ifEmpty { null },
        bolt12 = null,
        offer = null
    )
}

private fun uniffi.lni.Offer.toWrapperOffer(): Offer {
    return Offer(
        offerId = this.offerId,
        offer = this.bolt12,
        active = this.active ?: false,
        singleUse = this.singleUse ?: false,
        description = this.label,
        amountMsats = this.amountMsats
    )
}
