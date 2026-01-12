package org.thoughtcrime.securesms.payments.engine.lightning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

// Import native LNI library types directly from UniFFI bindings
import uniffi.lni.LightningNode
import uniffi.lni.CreateInvoiceParams as LniCreateInvoiceParams
import uniffi.lni.PayInvoiceParams as LniPayInvoiceParams
import uniffi.lni.InvoiceType as LniInvoiceType
import uniffi.lni.ListTransactionsParams as LniListTransactionsParams
import uniffi.lni.LookupInvoiceParams as LniLookupInvoiceParams

// Import factory functions for creating nodes (polymorphic via Arc<dyn LightningNode>)
import uniffi.lni.createStrikeNode
import uniffi.lni.createBlinkNode
import uniffi.lni.createNwcNode
import uniffi.lni.createLndNode
import uniffi.lni.createClnNode
import uniffi.lni.createPhoenixdNode
import uniffi.lni.createSpeedNode
import uniffi.lni.createSparkNode

// Import node configs
import uniffi.lni.StrikeConfig
import uniffi.lni.BlinkConfig
import uniffi.lni.NwcConfig
import uniffi.lni.LndConfig
import uniffi.lni.ClnConfig
import uniffi.lni.PhoenixdConfig
import uniffi.lni.SpeedConfig
import uniffi.lni.SparkConfig

/**
 * Result from creating a Lightning invoice, includes both the invoice string and payment hash.
 */
data class InvoiceResult(
    val paymentRequest: String,
    val paymentHash: String
)

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
 * - Spark (Breez SDK)
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
            val n = getOrCreateNode() ?: return@withContext false
            n.getInfo()
            true
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
     * Get the current Lightning configuration.
     */
    fun getConfig(): LightningConfig? = configStore.getConfig()

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
                sendBalanceSats = (info.sendBalanceMsat ?: 0) / 1000,
                receiveBalanceSats = (info.receiveBalanceMsat ?: 0) / 1000
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get Lightning balance", e)
            LightningBalance(0, 0)
        }
    }

    /**
     * Get detailed information about the Lightning node including alias.
     */
    suspend fun getNodeInfo(): LightningNodeInfo? = withContext(Dispatchers.IO) {
        try {
            val n = getOrCreateNode() ?: return@withContext null
            val info = n.getInfo()
            LightningNodeInfo(
                alias = info.alias,
                pubkey = info.pubkey,
                network = info.network,
                sendBalanceSats = (info.sendBalanceMsat ?: 0) / 1000,
                receiveBalanceSats = (info.receiveBalanceMsat ?: 0) / 1000
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get Lightning node info", e)
            null
        }
    }

    /**
     * Create a Lightning invoice for receiving payments.
     * Returns just the invoice string for backward compatibility.
     */
    suspend fun createInvoice(amountSats: Long, description: String? = null): Result<String> = withContext(Dispatchers.IO) {
        createInvoiceWithHash(amountSats, description).map { it.paymentRequest }
    }
    
    /**
     * Create a Lightning invoice for receiving payments.
     * Returns both the invoice string and payment hash for tracking.
     */
    suspend fun createInvoiceWithHash(amountSats: Long, description: String? = null): Result<InvoiceResult> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val params = LniCreateInvoiceParams(
                invoiceType = LniInvoiceType.BOLT11,
                amountMsats = amountSats * 1000,
                description = description,
                offer = null,
                descriptionHash = null,
                expiry = null,
                rPreimage = null,
                isBlinded = false,
                isKeysend = false,
                isAmp = false,
                isPrivate = false
            )
            val tx = n.createInvoice(params)
            val paymentRequest = tx.invoice.ifEmpty { null } ?: throw IllegalStateException("Invoice creation failed: no payment request returned")
            InvoiceResult(
                paymentRequest = paymentRequest,
                paymentHash = tx.paymentHash
            )
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
                feeLimitMsat = null,
                feeLimitPercentage = feeLimitSats?.let { 1.0 }, // Use 1% fee limit if sats limit specified
                timeoutSeconds = null,
                amountMsats = null,
                maxParts = null,
                firstHopPubkey = null,
                lastHopPubkey = null,
                allowSelfPayment = false,
                isAmp = false
            )
            val response = n.payInvoice(params)
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
            val lookupParams = LniLookupInvoiceParams(paymentHash = paymentHash, search = null)
            val tx = n.lookupInvoice(lookupParams)
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
     * Look up the status of an invoice by the payment request (BOLT11 invoice string).
     * This is useful when you have the invoice but not the payment hash.
     * 
     * Uses decode to extract payment hash, then lookupInvoice for efficient lookup.
     */
    suspend fun lookupInvoiceByRequest(invoiceRequest: String): Result<LightningPaymentStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            
            // Decode the invoice to extract payment hash
            val decoded = try { n.decode(invoiceRequest) } catch (e: Throwable) { null }
            Log.d(TAG, "Decoded invoice result: $decoded")
            
            // Try to extract payment_hash from decoded JSON
            val paymentHash = decoded?.let { extractPaymentHashFromDecoded(it) }
            
            if (paymentHash != null) {
                // Use efficient lookupInvoice by payment hash
                val lookupParams = LniLookupInvoiceParams(paymentHash = paymentHash, search = null)
                val tx = n.lookupInvoice(lookupParams)
                return@runCatching LightningPaymentStatus(
                    paymentHash = tx.paymentHash,
                    isPaid = tx.settledAt > 0,
                    amountSats = tx.amountMsats / 1000,
                    feesPaidSats = tx.feesPaid / 1000,
                    settledAt = if (tx.settledAt > 0) tx.settledAt * 1000 else null
                )
            }
            
            // Fallback: list transactions and filter
            Log.d(TAG, "Falling back to list transactions for invoice lookup")
            val listParams = LniListTransactionsParams(from = 0, limit = 50, paymentHash = null, search = null)
            val transactions = n.listTransactions(listParams)
            val matchingTx = transactions.find { tx ->
                tx.invoice.equals(invoiceRequest, ignoreCase = true)
            } ?: throw IllegalStateException("Invoice not found")
            
            LightningPaymentStatus(
                paymentHash = matchingTx.paymentHash,
                isPaid = matchingTx.settledAt > 0,
                amountSats = matchingTx.amountMsats / 1000,
                feesPaidSats = matchingTx.feesPaid / 1000,
                settledAt = if (matchingTx.settledAt > 0) matchingTx.settledAt * 1000 else null
            )
        }
    }
    
    /**
     * Extract payment hash from decoded invoice JSON.
     * The decode function returns JSON with payment_hash field.
     */
    private fun extractPaymentHashFromDecoded(decoded: String): String? {
        return try {
            // Try common JSON patterns for payment hash
            val patterns = listOf(
                """"payment_hash"\s*:\s*"([a-fA-F0-9]+)"""".toRegex(),
                """"paymentHash"\s*:\s*"([a-fA-F0-9]+)"""".toRegex(),
                """"r_hash"\s*:\s*"([a-fA-F0-9]+)"""".toRegex()
            )
            for (pattern in patterns) {
                val match = pattern.find(decoded)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract payment hash from decoded invoice", e)
            null
        }
    }

    /**
     * List recent Lightning transactions.
     */
    suspend fun listTransactions(limit: Int = 20): Result<List<LightningTx>> = withContext(Dispatchers.IO) {
        runCatching {
            val n = getOrCreateNode() ?: throw IllegalStateException("Lightning node not configured")
            val params = LniListTransactionsParams(
                from = 0,
                limit = limit.toLong(),
                paymentHash = null,
                search = null
            )
            val transactions = n.listTransactions(params)
            transactions.map { tx ->
                LightningTx(
                    paymentHash = tx.paymentHash,
                    type = if (tx.type.lowercase() == "incoming") LightningTxType.RECEIVE else LightningTxType.SEND,
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

    private suspend fun getOrCreateNode(): LightningNode? {
        node?.let { return it }
        
        val config = configStore.getConfig()
        if (config == null) {
            Log.w(TAG, "getOrCreateNode: No config found")
            return null
        }
        
        Log.i(TAG, "getOrCreateNode: Creating node for type ${config.type}")
        
        val newNode: LightningNode? = try {
            when (config.type) {
                LightningNodeType.NWC -> {
                    Log.i(TAG, "Creating NwcNode")
                    createNwcNode(NwcConfig(
                        nwcUri = config.credential,
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.LND -> {
                    Log.i(TAG, "Creating LndNode with url=${config.url}")
                    createLndNode(LndConfig(
                        url = config.url ?: throw IllegalStateException("LND requires URL"),
                        macaroon = config.credential,
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.STRIKE -> {
                    Log.i(TAG, "Creating StrikeNode")
                    createStrikeNode(StrikeConfig(
                        apiKey = config.credential,
                        baseUrl = "https://api.strike.me/v1",
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.BLINK -> {
                    Log.i(TAG, "Creating BlinkNode")
                    createBlinkNode(BlinkConfig(
                        apiKey = config.credential,
                        baseUrl = "https://api.blink.sv/graphql",
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.CLN -> {
                    Log.i(TAG, "Creating ClnNode")
                    createClnNode(ClnConfig(
                        url = config.url ?: throw IllegalStateException("CLN requires URL"),
                        rune = config.credential,
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.PHOENIXD -> {
                    Log.i(TAG, "Creating PhoenixdNode")
                    createPhoenixdNode(PhoenixdConfig(
                        url = config.url ?: throw IllegalStateException("Phoenixd requires URL"),
                        password = config.credential,
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.SPEED -> {
                    Log.i(TAG, "Creating SpeedNode")
                    createSpeedNode(SpeedConfig(
                        apiKey = config.credential,
                        baseUrl = config.url ?: "https://api.tryspeed.com",
                        socks5Proxy = null,
                        acceptInvalidCerts = true,
                        httpTimeout = 120L
                    ))
                }
                LightningNodeType.SPARK -> {
                    Log.i(TAG, "Creating SparkNode")
                    val storageDir = config.storageDir 
                        ?: "${appContext.filesDir}/spark_wallet"
                    // Use user-provided API key, or fall back to embedded key from BuildConfig
                    val apiKey = config.secondaryCredential?.takeIf { it.isNotBlank() }
                        ?: org.thoughtcrime.securesms.BuildConfig.BREEZ_API_KEY.takeIf { it.isNotBlank() }
                    createSparkNode(SparkConfig(
                        mnemonic = config.credential,
                        passphrase = null,
                        apiKey = apiKey,
                        storageDir = storageDir,
                        network = "mainnet"
                    ))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create node for type ${config.type}", e)
            null
        }
        
        node = newNode
        Log.i(TAG, "getOrCreateNode: Created node = ${newNode?.javaClass?.simpleName}")
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

/**
 * Information about the connected Lightning node.
 */
data class LightningNodeInfo(
    val alias: String,
    val pubkey: String,
    val network: String,
    val sendBalanceSats: Long,
    val receiveBalanceSats: Long
)
