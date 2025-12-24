/**
 * LNI (Lightning Node Interface) Kotlin Bindings
 * 
 * This file contains Kotlin interfaces and types that mirror the LNI Rust library API.
 * When the actual uniffi bindings are generated from the Rust build, they will replace
 * these stubs and provide the actual native implementations.
 * 
 * The API follows the LNI library interface:
 * - https://github.com/lightning-node-interface/lni
 */
package lni

/**
 * Error types from the LNI library
 */
sealed class ApiError : Exception() {
    data class Http(val reason: String) : ApiError() {
        override val message: String = "HttpError: $reason"
    }
    data class Api(val reason: String) : ApiError() {
        override val message: String = "ApiError: $reason"
    }
    data class Json(val reason: String) : ApiError() {
        override val message: String = "JsonError: $reason"
    }
}

/**
 * Invoice type for BOLT specifications
 */
enum class InvoiceType {
    Bolt11,
    Bolt12
}

/**
 * Transaction status
 */
enum class TransactionStatus {
    Pending,
    Complete,
    Failed
}

/**
 * Transaction type
 */
enum class TransactionType {
    Incoming,
    Outgoing
}

/**
 * Node information returned by getInfo()
 */
data class NodeInfo(
    val alias: String?,
    val pubkey: String?,
    val network: String?,
    val blockHeight: Long?,
    val balanceSat: Long?,
    val maxPayableSat: Long?,
    val maxReceivableSat: Long?
)

/**
 * Transaction/Invoice representation
 */
data class Transaction(
    val paymentHash: String,
    val paymentRequest: String?,
    val amountMsats: Long?,
    val feeMsats: Long?,
    val status: TransactionStatus,
    val type: TransactionType,
    val createdAt: Long?,
    val settledAt: Long?,
    val description: String?,
    val preimage: String?,
    val bolt11: String?,
    val bolt12: String?,
    val offer: String?
)

/**
 * BOLT 12 Offer representation
 */
data class Offer(
    val offerId: String?,
    val offer: String,
    val active: Boolean,
    val singleUse: Boolean,
    val description: String?,
    val amountMsats: Long?
)

/**
 * Parameters for creating an invoice
 */
data class CreateInvoiceParams(
    val invoiceType: InvoiceType = InvoiceType.Bolt11,
    val amountMsats: Long? = null,
    val description: String? = null,
    val expiry: Long? = null,
    val descriptionHash: String? = null
)

/**
 * Parameters for paying an invoice
 */
data class PayInvoiceParams(
    val invoice: String,
    val feeLimitPercentage: Float? = null,
    val allowSelfPayment: Boolean? = null,
    val amountMsats: Long? = null
)

/**
 * Response from paying an invoice
 */
data class PayInvoiceResponse(
    val paymentHash: String,
    val preimage: String?,
    val feeMsats: Long?,
    val status: TransactionStatus
)

/**
 * Parameters for listing transactions
 */
data class ListTransactionsParams(
    val from: Int = 0,
    val limit: Int = 10,
    val paymentHash: String? = null
)

/**
 * Parameters for creating a BOLT 12 offer
 */
data class CreateOfferParams(
    val amountMsats: Long? = null,
    val description: String? = null,
    val singleUse: Boolean = false
)

/**
 * Parameters for invoice event polling
 */
data class OnInvoiceEventParams(
    val paymentHash: String,
    val pollingDelaySec: Long = 3,
    val maxPollingSec: Long = 60
)

/**
 * Callback interface for invoice events
 */
interface OnInvoiceEventCallback {
    fun success(transaction: Transaction?)
    fun pending(transaction: Transaction?)
    fun failure(transaction: Transaction?)
}

/**
 * Common interface for all Lightning node implementations
 */
interface LightningNodeInterface {
    suspend fun getInfo(): Result<NodeInfo>
    suspend fun createInvoice(params: CreateInvoiceParams): Result<Transaction>
    suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse>
    suspend fun lookupInvoice(paymentHash: String): Result<Transaction>
    suspend fun listTransactions(params: ListTransactionsParams): Result<List<Transaction>>
    suspend fun decode(str: String): Result<String>
    
    // BOLT 12 methods
    suspend fun createOffer(params: CreateOfferParams): Result<Offer>
    suspend fun getOffer(search: String?): Result<Offer>
    suspend fun payOffer(offer: String, amountMsats: Long, payerNote: String?): Result<PayInvoiceResponse>
    suspend fun listOffers(search: String?): Result<List<Offer>>
    
    // Event polling
    suspend fun onInvoiceEvents(params: OnInvoiceEventParams, callback: OnInvoiceEventCallback)
}
