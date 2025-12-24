package org.thoughtcrime.securesms.payments.engine.lightning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

/**
 * Lightning Engine that provides a unified interface for Lightning payments.
 * 
 * This engine manages the connection to a Lightning node and provides
 * methods for creating invoices, paying invoices, and checking balances.
 * 
 * The implementation supports multiple backends through the LightningNode interface,
 * modeled after the LNI (Lightning Node Interface) library.
 * 
 * Currently implemented backends:
 * - NWC (Nostr Wallet Connect) - Simple HTTP/WebSocket based protocol
 * 
 * Future backends (can be added via LNI library integration):
 * - LND
 * - CLN (Core Lightning)
 * - Phoenixd
 * - Strike
 * - Blink
 * - Speed
 * 
 * @see <a href="https://github.com/lightning-node-interface/lni">LNI Library</a>
 */
class LightningEngine(private val appContext: Context) {

    companion object {
        private val TAG = Log.tag(LightningEngine::class.java)
    }

    private val configStore by lazy { LightningConfigStore(appContext) }
    
    @Volatile
    private var node: LightningNode? = null

    /**
     * Check if a Lightning node is configured.
     */
    fun isConfigured(): Boolean = configStore.isConfigured()

    /**
     * Check if the Lightning node is available and connected.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            getOrCreateNode()?.isAvailable() ?: false
        } catch (e: Throwable) {
            Log.w(TAG, "Lightning node not available", e)
            false
        }
    }

    /**
     * Get the current Lightning node configuration type.
     */
    fun getConfiguredNodeType(): LightningNodeType? = configStore.getConfig()?.type

    /**
     * Configure a Lightning node connection.
     */
    fun configure(config: LightningConfig) {
        configStore.saveConfig(config)
        node = null // Reset cached node
        Log.i(TAG, "Lightning node configured: ${config.type}")
    }

    /**
     * Configure an NWC connection using a nostr+walletconnect:// URI.
     */
    fun configureNwc(nwcUri: String) {
        val config = LightningConfig(
            type = LightningNodeType.NWC,
            credential = nwcUri
        )
        configure(config)
    }

    /**
     * Clear the Lightning node configuration.
     */
    fun clearConfiguration() {
        configStore.clearConfig()
        node = null
        Log.i(TAG, "Lightning configuration cleared")
    }

    /**
     * Get the balance from the Lightning node in satoshis.
     */
    suspend fun getBalance(): LightningBalance = withContext(Dispatchers.IO) {
        try {
            val n = getOrCreateNode() ?: return@withContext LightningBalance(0, 0)
            val info = n.getInfo()
            LightningBalance(
                sendBalanceSats = info.sendBalanceMsat / 1000,
                receiveBalanceSats = info.receiveBalanceMsat / 1000
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get Lightning balance", e)
            LightningBalance(0, 0)
        }
    }

    /**
     * Create a Lightning invoice for receiving payments.
     */
    suspend fun createInvoice(amountSats: Long, description: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val params = CreateInvoiceParams(
                invoiceType = InvoiceType.BOLT11,
                amountMsats = amountSats * 1000,
                description = description
            )
            val tx = n.createInvoice(params).getOrThrow()
            tx.invoice
        }
    }

    /**
     * Pay a Lightning invoice.
     */
    suspend fun payInvoice(invoice: String, feeLimitSats: Long? = null): Result<LightningPaymentResult> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val params = PayInvoiceParams(
                invoice = invoice,
                feeLimitMsat = feeLimitSats?.let { it * 1000 }
            )
            val response = n.payInvoice(params).getOrThrow()
            LightningPaymentResult(
                paymentHash = response.paymentHash,
                preimage = response.preimage,
                feeSats = response.feeMsats / 1000
            )
        }
    }

    /**
     * Look up the status of a payment by payment hash.
     */
    suspend fun lookupPayment(paymentHash: String): Result<LightningPaymentStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val tx = n.lookupInvoice(paymentHash).getOrThrow()
            LightningPaymentStatus(
                paymentHash = tx.paymentHash,
                isPaid = tx.settledAt > 0,
                amountSats = tx.amountMsats / 1000,
                feesPaidSats = tx.feesPaid / 1000,
                settledAt = if (tx.settledAt > 0) tx.settledAt * 1000 else null
            )
        }
    }

    /**
     * List recent Lightning transactions.
     */
    suspend fun listTransactions(limit: Int = 20): Result<List<LightningTx>> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val transactions = n.listTransactions(0, limit).getOrThrow()
            transactions.map { tx ->
                LightningTx(
                    paymentHash = tx.paymentHash,
                    type = if (tx.type == "incoming") LightningTxType.RECEIVE else LightningTxType.SEND,
                    amountSats = tx.amountMsats / 1000,
                    feesPaidSats = tx.feesPaid / 1000,
                    description = tx.description,
                    createdAt = tx.createdAt * 1000,
                    settledAt = if (tx.settledAt > 0) tx.settledAt * 1000 else null,
                    isPaid = tx.settledAt > 0
                )
            }
        }
    }

    private fun getOrCreateNode(): LightningNode? {
        node?.let { return it }
        
        val config = configStore.getConfig() ?: return null
        
        val newNode = when (config.type) {
            LightningNodeType.NWC -> {
                val nwcConfig = NwcNode.NwcConfig.fromUri(config.credential)
                NwcNode(nwcConfig)
            }
            LightningNodeType.LND -> {
                LndNode(
                    baseUrl = config.url ?: throw IllegalStateException("LND requires URL"),
                    macaroon = config.credential
                )
            }
            LightningNodeType.STRIKE -> {
                StrikeNode(apiKey = config.credential)
            }
            LightningNodeType.BLINK -> {
                BlinkNode(apiKey = config.credential)
            }
            // CLN, Phoenixd, Speed not yet implemented
            LightningNodeType.CLN,
            LightningNodeType.PHOENIXD,
            LightningNodeType.SPEED -> {
                Log.w(TAG, "Lightning node type not yet fully implemented: ${config.type}. Using placeholder.")
                null
            }
        }
        
        node = newNode
        return newNode
    }
}

/**
 * Lightning balance in satoshis.
 */
data class LightningBalance(
    val sendBalanceSats: Long,
    val receiveBalanceSats: Long
)

/**
 * Result of a Lightning payment.
 */
data class LightningPaymentResult(
    val paymentHash: String,
    val preimage: String,
    val feeSats: Long
)

/**
 * Status of a Lightning payment.
 */
data class LightningPaymentStatus(
    val paymentHash: String,
    val isPaid: Boolean,
    val amountSats: Long,
    val feesPaidSats: Long,
    val settledAt: Long?
)

/**
 * A Lightning transaction.
 */
data class LightningTx(
    val paymentHash: String,
    val type: LightningTxType,
    val amountSats: Long,
    val feesPaidSats: Long,
    val description: String,
    val createdAt: Long,
    val settledAt: Long?,
    val isPaid: Boolean
)

/**
 * Type of Lightning transaction.
 */
enum class LightningTxType {
    SEND,
    RECEIVE
}
