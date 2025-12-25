/**
 * Lightning Node Implementations
 * 
 * Each node class is a thin wrapper around the native UniFFI implementations.
 * The common logic is extracted into shared converter functions in LniConverters.kt.
 */
package lni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.lni.ApiException

// ============================================================================
// Strike Node
// ============================================================================

/**
 * Strike Lightning node implementation backed by native LNI library.
 */
class StrikeNode(config: StrikeConfig) : LightningNodeInterface {
    
    private val native = uniffi.lni.StrikeNode(
        uniffi.lni.StrikeConfig(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            socks5Proxy = config.socks5Proxy ?: "",
            acceptInvalidCerts = config.acceptInvalidCerts,
            httpTimeout = config.httpTimeout
        )
    )
    
    override suspend fun getInfo() = runCatching { native.getInfo().toWrapper() }
    override suspend fun createInvoice(params: CreateInvoiceParams) = runCatching { native.createInvoice(params.toNative()).toWrapper() }
    override suspend fun payInvoice(params: PayInvoiceParams) = runCatching { native.payInvoice(params.toNative()).toWrapper() }
    override suspend fun lookupInvoice(paymentHash: String) = runCatching { 
        native.lookupInvoice(uniffi.lni.LookupInvoiceParams(paymentHash = paymentHash, search = null)).toWrapper() 
    }
    override suspend fun listTransactions(params: ListTransactionsParams) = runCatching { 
        native.listTransactions(params.toNative()).map { it.toWrapper() } 
    }
    override suspend fun decode(str: String) = runCatching { native.decode(str) }
    override suspend fun createOffer(params: CreateOfferParams) = runCatching { native.createOffer(params.toNative()).toWrapper() }
    override suspend fun getOffer(search: String?) = runCatching { native.getOffer(search).toWrapper() }
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?) = runCatching { 
        native.payOffer(offer, amountMsats, payerNote).toWrapper() 
    }
    override suspend fun listOffers(search: String?) = runCatching { native.listOffers(search).map { it.toWrapper() } }
    override suspend fun onInvoiceEvents(params: OnInvoiceEventParams, callback: OnInvoiceEventCallback) = 
        pollInvoiceEvents(params, callback) { lookupInvoice(it) }
}

// ============================================================================
// Blink Node
// ============================================================================

/**
 * Blink Lightning node implementation backed by native LNI library.
 */
class BlinkNode(config: BlinkConfig) : LightningNodeInterface {
    
    private val native = uniffi.lni.BlinkNode(
        uniffi.lni.BlinkConfig(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            socks5Proxy = config.socks5Proxy ?: "",
            acceptInvalidCerts = config.acceptInvalidCerts,
            httpTimeout = config.httpTimeout
        )
    )
    
    override suspend fun getInfo() = runCatching { native.getInfo().toWrapper() }
    override suspend fun createInvoice(params: CreateInvoiceParams) = runCatching { native.createInvoice(params.toNative()).toWrapper() }
    override suspend fun payInvoice(params: PayInvoiceParams) = runCatching { native.payInvoice(params.toNative()).toWrapper() }
    override suspend fun lookupInvoice(paymentHash: String) = runCatching { 
        native.lookupInvoice(uniffi.lni.LookupInvoiceParams(paymentHash = paymentHash, search = null)).toWrapper() 
    }
    override suspend fun listTransactions(params: ListTransactionsParams) = runCatching { 
        native.listTransactions(params.toNative()).map { it.toWrapper() } 
    }
    override suspend fun decode(str: String) = runCatching { native.decode(str) }
    override suspend fun createOffer(params: CreateOfferParams) = runCatching { native.createOffer(params.toNative()).toWrapper() }
    override suspend fun getOffer(search: String?) = runCatching { native.getOffer(search).toWrapper() }
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?) = runCatching { 
        native.payOffer(offer, amountMsats, payerNote).toWrapper() 
    }
    override suspend fun listOffers(search: String?) = runCatching { native.listOffers(search).map { it.toWrapper() } }
    override suspend fun onInvoiceEvents(params: OnInvoiceEventParams, callback: OnInvoiceEventCallback) = 
        pollInvoiceEvents(params, callback) { lookupInvoice(it) }
}

// ============================================================================
// NWC Node (Nostr Wallet Connect)
// ============================================================================

/**
 * NWC (Nostr Wallet Connect) Lightning node implementation backed by native LNI library.
 */
class NwcNode(config: NwcConfig) : LightningNodeInterface {
    
    private val native = uniffi.lni.NwcNode(
        uniffi.lni.NwcConfig(
            nwcUri = config.nwcUri,
            socks5Proxy = config.socks5Proxy ?: "",
            acceptInvalidCerts = config.acceptInvalidCerts,
            httpTimeout = config.httpTimeout
        )
    )
    
    override suspend fun getInfo() = runCatching { native.getInfo().toWrapper() }
    override suspend fun createInvoice(params: CreateInvoiceParams) = runCatching { native.createInvoice(params.toNative()).toWrapper() }
    override suspend fun payInvoice(params: PayInvoiceParams) = runCatching { native.payInvoice(params.toNative()).toWrapper() }
    override suspend fun lookupInvoice(paymentHash: String) = runCatching { 
        native.lookupInvoice(uniffi.lni.LookupInvoiceParams(paymentHash = paymentHash, search = null)).toWrapper() 
    }
    override suspend fun listTransactions(params: ListTransactionsParams) = runCatching { 
        native.listTransactions(params.toNative()).map { it.toWrapper() } 
    }
    override suspend fun decode(str: String) = runCatching { native.decode(str) }
    override suspend fun createOffer(params: CreateOfferParams) = runCatching { native.createOffer(params.toNative()).toWrapper() }
    override suspend fun getOffer(search: String?) = runCatching { native.getOffer(search).toWrapper() }
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?) = runCatching { 
        native.payOffer(offer, amountMsats, payerNote).toWrapper() 
    }
    override suspend fun listOffers(search: String?) = runCatching { native.listOffers(search).map { it.toWrapper() } }
    override suspend fun onInvoiceEvents(params: OnInvoiceEventParams, callback: OnInvoiceEventCallback) = 
        pollInvoiceEvents(params, callback) { lookupInvoice(it) }
}

// ============================================================================
// LND Node
// ============================================================================

/**
 * LND (Lightning Network Daemon) node implementation backed by native LNI library.
 */
class LndNode(config: LndConfig) : LightningNodeInterface {
    
    private val native = uniffi.lni.LndNode(
        uniffi.lni.LndConfig(
            url = config.url,
            macaroon = config.macaroon,
            socks5Proxy = config.socks5Proxy ?: "",
            acceptInvalidCerts = config.acceptInvalidCerts,
            httpTimeout = config.httpTimeout
        )
    )
    
    override suspend fun getInfo() = runCatching { native.getInfo().toWrapper() }
    override suspend fun createInvoice(params: CreateInvoiceParams) = runCatching { native.createInvoice(params.toNative()).toWrapper() }
    override suspend fun payInvoice(params: PayInvoiceParams) = runCatching { native.payInvoice(params.toNative()).toWrapper() }
    override suspend fun lookupInvoice(paymentHash: String) = runCatching { 
        native.lookupInvoice(uniffi.lni.LookupInvoiceParams(paymentHash = paymentHash, search = null)).toWrapper() 
    }
    override suspend fun listTransactions(params: ListTransactionsParams) = runCatching { 
        native.listTransactions(params.toNative()).map { it.toWrapper() } 
    }
    override suspend fun decode(str: String) = runCatching { native.decode(str) }
    override suspend fun createOffer(params: CreateOfferParams) = runCatching { native.createOffer(params.toNative()).toWrapper() }
    override suspend fun getOffer(search: String?) = runCatching { native.getOffer(search).toWrapper() }
    override suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?) = runCatching { 
        native.payOffer(offer, amountMsats, payerNote).toWrapper() 
    }
    override suspend fun listOffers(search: String?) = runCatching { native.listOffers(search).map { it.toWrapper() } }
    override suspend fun onInvoiceEvents(params: OnInvoiceEventParams, callback: OnInvoiceEventCallback) = 
        pollInvoiceEvents(params, callback) { lookupInvoice(it) }
}

// ============================================================================
// Shared Helpers
// ============================================================================

/**
 * Shared polling logic for invoice events.
 */
private suspend fun pollInvoiceEvents(
    params: OnInvoiceEventParams, 
    callback: OnInvoiceEventCallback,
    lookup: suspend (String) -> Result<Transaction>
) {
    val startTime = System.currentTimeMillis() / 1000
    val maxTime = startTime + params.maxPollingSec
    
    while (System.currentTimeMillis() / 1000 < maxTime) {
        val result = lookup(params.paymentHash)
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
