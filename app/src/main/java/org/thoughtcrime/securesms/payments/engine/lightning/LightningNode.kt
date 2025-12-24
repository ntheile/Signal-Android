package org.thoughtcrime.securesms.payments.engine.lightning

/**
 * Lightning Node interface modeled after the LNI (Lightning Node Interface) library.
 * This provides a unified interface for connecting to various Lightning backends:
 * - LND
 * - CLN (Core Lightning)
 * - Phoenixd
 * - NWC (Nostr Wallet Connect)
 * - Strike
 * - Blink
 * - Speed
 *
 * For now, we implement NWC as the simplest option that works via HTTP/WebSocket.
 * This can be extended later to support native LNI bindings.
 *
 * @see <a href="https://github.com/lightning-node-interface/lni">LNI Library</a>
 */
interface LightningNode {
    /**
     * Get information about the connected node.
     */
    suspend fun getInfo(): NodeInfo

    /**
     * Create a Lightning invoice (BOLT11).
     */
    suspend fun createInvoice(params: CreateInvoiceParams): Result<LightningTransaction>

    /**
     * Pay a Lightning invoice.
     */
    suspend fun payInvoice(params: PayInvoiceParams): Result<PayInvoiceResponse>

    /**
     * Look up an invoice by payment hash.
     */
    suspend fun lookupInvoice(paymentHash: String): Result<LightningTransaction>

    /**
     * List recent transactions.
     */
    suspend fun listTransactions(from: Int, limit: Int): Result<List<LightningTransaction>>

    /**
     * Check if the node is connected and available.
     */
    suspend fun isAvailable(): Boolean
}

/**
 * Type of Lightning invoice.
 */
enum class InvoiceType {
    BOLT11,
    BOLT12
}

/**
 * Node information and balances.
 */
data class NodeInfo(
    val alias: String = "",
    val pubkey: String = "",
    val network: String = "",
    val blockHeight: Long = 0,
    val sendBalanceMsat: Long = 0,
    val receiveBalanceMsat: Long = 0
)

/**
 * A Lightning transaction (invoice).
 */
data class LightningTransaction(
    val type: String = "",
    val invoice: String = "",
    val description: String = "",
    val preimage: String = "",
    val paymentHash: String = "",
    val amountMsats: Long = 0,
    val feesPaid: Long = 0,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val settledAt: Long = 0,
    val payerNote: String? = null
)

/**
 * Parameters for creating an invoice.
 */
data class CreateInvoiceParams(
    val invoiceType: InvoiceType = InvoiceType.BOLT11,
    val amountMsats: Long? = null,
    val description: String? = null,
    val expiry: Long? = null
)

/**
 * Parameters for paying an invoice.
 */
data class PayInvoiceParams(
    val invoice: String,
    val feeLimitMsat: Long? = null,
    val feeLimitPercentage: Double? = null,
    val timeoutSeconds: Long? = 60,
    val amountMsats: Long? = null // For zero-amount invoices
)

/**
 * Response from paying an invoice.
 */
data class PayInvoiceResponse(
    val paymentHash: String = "",
    val preimage: String = "",
    val feeMsats: Long = 0
)
