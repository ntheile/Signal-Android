package org.thoughtcrime.securesms.payments.engine.lightning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

// Import LNI library types
import lni.LightningNodeInterface
import lni.CreateInvoiceParams as LniCreateInvoiceParams
import lni.PayInvoiceParams as LniPayInvoiceParams
import lni.InvoiceType as LniInvoiceType
import lni.NodeInfo
import lni.Transaction
import lni.PayInvoiceResponse
import lni.TransactionStatus

// Import LNI node implementations
import lni.LndNode as LniLndNode
import lni.LndConfig
import lni.StrikeNode as LniStrikeNode
import lni.StrikeConfig
import lni.BlinkNode as LniBlinkNode
import lni.BlinkConfig
import lni.NwcNode as LniNwcNode
import lni.NwcConfig

/**
 * Lightning Engine that provides a unified interface for Lightning payments.
 * 
 * This engine manages the connection to a Lightning node and provides
 * methods for creating invoices, paying invoices, and checking balances.
 * 
 * The implementation uses the LNI (Lightning Node Interface) library which
 * provides a standard interface to connect to multiple Lightning node
 * implementations.
 * 
 * Supported backends via LNI:
 * - LND
 * - CLN (Core Lightning)
 * - Phoenixd
 * - NWC (Nostr Wallet Connect)
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
    private var node: LightningNodeInterface? = null

    /**
     * Check if a Lightning node is configured.
     */
    fun isConfigured(): Boolean = configStore.isConfigured()

    /**
     * Check if the Lightning node is available and connected.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val n = getOrCreateNode() ?: return@withContext false
            n.getInfo().isSuccess
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
            val infoResult = n.getInfo()
            infoResult.fold(
                onSuccess = { info ->
                    LightningBalance(
                        sendBalanceSats = info.maxPayableSat ?: info.balanceSat ?: 0,
                        receiveBalanceSats = info.maxReceivableSat ?: 0
                    )
                },
                onFailure = {
                    Log.w(TAG, "Failed to get Lightning balance", it)
                    LightningBalance(0, 0)
                }
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
            val params = LniCreateInvoiceParams(
                invoiceType = LniInvoiceType.Bolt11,
                amountMsats = amountSats * 1000,
                description = description
            )
            val txResult = n.createInvoice(params)
            val tx = txResult.getOrThrow()
            tx.paymentRequest ?: throw IllegalStateException("Invoice creation failed: no payment request returned")
        }
    }

    /**
     * Pay a Lightning invoice.
     */
    suspend fun payInvoice(invoice: String, feeLimitSats: Long? = null): Result<LightningPaymentResult> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val params = LniPayInvoiceParams(
                invoice = invoice,
                feeLimitPercentage = feeLimitSats?.let { 1.0f } // Use 1% fee limit if sats limit specified
            )
            val responseResult = n.payInvoice(params)
            val response = responseResult.getOrThrow()
            LightningPaymentResult(
                paymentHash = response.paymentHash,
                preimage = response.preimage ?: "",
                feeSats = (response.feeMsats ?: 0) / 1000
            )
        }
    }

    /**
     * Look up the status of a payment by payment hash.
     */
    suspend fun lookupPayment(paymentHash: String): Result<LightningPaymentStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val txResult = n.lookupInvoice(paymentHash)
            val tx = txResult.getOrThrow()
            LightningPaymentStatus(
                paymentHash = tx.paymentHash,
                isPaid = tx.status == TransactionStatus.Complete,
                amountSats = (tx.amountMsats ?: 0) / 1000,
                feesPaidSats = (tx.feeMsats ?: 0) / 1000,
                settledAt = tx.settledAt?.let { it * 1000 }
            )
        }
    }

    /**
     * List recent Lightning transactions.
     */
    suspend fun listTransactions(limit: Int = 20): Result<List<LightningTx>> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val params = lni.ListTransactionsParams(
                from = 0,
                limit = limit
            )
            val transactionsResult = n.listTransactions(params)
            val transactions = transactionsResult.getOrThrow()
            transactions.map { tx ->
                LightningTx(
                    paymentHash = tx.paymentHash,
                    type = if (tx.type == lni.TransactionType.Incoming) LightningTxType.RECEIVE else LightningTxType.SEND,
                    amountSats = (tx.amountMsats ?: 0) / 1000,
                    feesPaidSats = (tx.feeMsats ?: 0) / 1000,
                    description = tx.description ?: "",
                    createdAt = (tx.createdAt ?: 0) * 1000,
                    settledAt = tx.settledAt?.let { it * 1000 },
                    isPaid = tx.status == TransactionStatus.Complete
                )
            }
        }
    }

    private fun getOrCreateNode(): LightningNodeInterface? {
        node?.let { return it }
        
        val config = configStore.getConfig() ?: return null
        
        val newNode: LightningNodeInterface? = when (config.type) {
            LightningNodeType.NWC -> {
                LniNwcNode(NwcConfig(uri = config.credential))
            }
            LightningNodeType.LND -> {
                LniLndNode(LndConfig(
                    url = config.url ?: throw IllegalStateException("LND requires URL"),
                    macaroon = config.credential
                ))
            }
            LightningNodeType.STRIKE -> {
                LniStrikeNode(StrikeConfig(apiKey = config.credential))
            }
            LightningNodeType.BLINK -> {
                LniBlinkNode(BlinkConfig(apiKey = config.credential))
            }
            // CLN, Phoenixd, Speed - can be added when LNI uniffi bindings are built
            LightningNodeType.CLN,
            LightningNodeType.PHOENIXD,
            LightningNodeType.SPEED -> {
                Log.w(TAG, "Lightning node type ${config.type} requires building LNI native bindings. Using placeholder.")
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
