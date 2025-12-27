package org.thoughtcrime.securesms.payments.engine.lightning

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log

/**
 * Synchronous (blocking) helpers for Java callers to interact with LightningEngine.
 * Do NOT call on main thread unless invoking on a background thread.
 * All methods handle errors gracefully and return null/false/empty on failure.
 * 
 * This is modeled after CashuUiInteractor for consistency.
 */
object LightningUiInteractor {
    private const val TAG = "LightningUiInteractor"

    /**
     * Check if a Lightning node is configured AND enabled.
     * This is the primary check to determine if Lightning should be used for payments.
     */
    @JvmStatic
    fun isConfigured(context: Context): Boolean {
        return try {
            // Check if Lightning is enabled globally AND has a configuration
            val lightningEnabled = org.thoughtcrime.securesms.keyvalue.SignalStore.payments.lightningEnabled()
            val hasConfig = LightningEngineProvider.get(context).isConfigured()
            Log.d(TAG, "Lightning check: enabled=$lightningEnabled, hasConfig=$hasConfig")
            lightningEnabled && hasConfig
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check Lightning configuration", e)
            false
        }
    }
    
    /**
     * Check if a Lightning node has a configuration file (ignores enabled flag).
     */
    @JvmStatic
    fun hasConfiguration(context: Context): Boolean {
        return try {
            LightningEngineProvider.get(context).isConfigured()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check Lightning configuration", e)
            false
        }
    }

    /**
     * Check if the Lightning node is available (connected).
     */
    @JvmStatic
    fun isAvailableBlocking(context: Context): Boolean = runBlocking {
        runCatching {
            LightningEngineProvider.get(context).isAvailable()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to check Lightning availability", throwable)
            false
        }
    }

    /**
     * Get the current Lightning balance in satoshis.
     */
    @JvmStatic
    fun getBalanceBlocking(context: Context): LightningBalance? = runBlocking {
        runCatching {
            LightningEngineProvider.get(context).getBalance()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to get Lightning balance", throwable)
            null
        }
    }

    /**
     * Create a Lightning invoice.
     */
    @JvmStatic
    fun createInvoiceBlocking(context: Context, amountSats: Long, description: String? = null): String? = runBlocking {
        try {
            Log.i(TAG, "createInvoiceBlocking: Creating invoice for $amountSats sats")
            val engine = LightningEngineProvider.get(context)
            Log.i(TAG, "createInvoiceBlocking: Engine type = ${engine.getConfiguredNodeType()}")
            
            val result = engine.createInvoice(amountSats, description)
            result.fold(
                onSuccess = { invoice ->
                    Log.i(TAG, "createInvoiceBlocking: Success! Invoice length = ${invoice.length}")
                    invoice
                },
                onFailure = { error ->
                    Log.e(TAG, "createInvoiceBlocking: Failed - ${error.message}", error)
                    null
                }
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "createInvoiceBlocking: Exception", throwable)
            null
        }
    }
    
    /**
     * Create a Lightning invoice and return both the invoice string and payment hash.
     */
    @JvmStatic
    fun createInvoiceWithHashBlocking(context: Context, amountSats: Long, description: String? = null): InvoiceResult? = runBlocking {
        try {
            Log.i(TAG, "createInvoiceWithHashBlocking: Creating invoice for $amountSats sats")
            val engine = LightningEngineProvider.get(context)
            
            val result = engine.createInvoiceWithHash(amountSats, description)
            result.fold(
                onSuccess = { invoiceResult ->
                    Log.i(TAG, "createInvoiceWithHashBlocking: Success! Invoice length = ${invoiceResult.paymentRequest.length}, hash = ${invoiceResult.paymentHash.take(16)}...")
                    invoiceResult
                },
                onFailure = { error ->
                    Log.e(TAG, "createInvoiceWithHashBlocking: Failed - ${error.message}", error)
                    null
                }
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "createInvoiceWithHashBlocking: Exception", throwable)
            null
        }
    }

    /**
     * Pay a Lightning invoice.
     */
    @JvmStatic
    fun payInvoiceBlocking(context: Context, invoice: String, feeLimitSats: Long? = null): LightningPaymentResult? = runBlocking {
        runCatching {
            LightningEngineProvider.get(context).payInvoice(invoice, feeLimitSats).getOrNull()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to pay Lightning invoice", throwable)
            null
        }
    }

    /**
     * Look up an invoice/payment status by payment hash.
     * Returns the payment status if found, null otherwise.
     */
    @JvmStatic
    fun lookupInvoiceBlocking(context: Context, paymentHash: String): LightningPaymentStatus? = runBlocking {
        runCatching {
            LightningEngineProvider.get(context).lookupPayment(paymentHash).getOrNull()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to lookup invoice", throwable)
            null
        }
    }

    /**
     * Check if an invoice (by payment request string) has been paid.
     * First checks for a stored payment hash for efficient lookup,
     * then falls back to lookupInvoiceByRequest.
     */
    @JvmStatic
    fun isInvoicePaidBlocking(context: Context, invoice: String): Boolean = runBlocking {
        try {
            // First, check if we have a stored payment hash for this invoice
            val storedPaymentHash = org.thoughtcrime.securesms.keyvalue.SignalStore.payments.getInvoicePaymentHash(invoice)
            
            if (storedPaymentHash != null) {
                Log.d(TAG, "Found stored payment hash for invoice, using direct lookup: ${storedPaymentHash.take(16)}...")
                // Use direct lookup by payment hash - much more efficient
                val status = LightningEngineProvider.get(context).lookupPayment(storedPaymentHash).getOrNull()
                Log.d(TAG, "Lookup by payment hash result: isPaid=${status?.isPaid}")
                return@runBlocking status?.isPaid == true
            }
            
            // Fallback: Use lookupInvoiceByRequest that decodes and searches
            Log.d(TAG, "No stored payment hash, falling back to lookupInvoiceByRequest")
            val status = LightningEngineProvider.get(context).lookupInvoiceByRequest(invoice).getOrNull()
            Log.d(TAG, "lookupInvoiceByRequest result: isPaid=${status?.isPaid}")
            status?.isPaid == true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check if invoice is paid", e)
            false
        }
    }

    /**
     * Configure an NWC (Nostr Wallet Connect) connection.
     */
    @JvmStatic
    fun configureNwc(context: Context, nwcUri: String): Boolean {
        return try {
            LightningEngineProvider.get(context).configureNwc(nwcUri)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure NWC", e)
            false
        }
    }

    /**
     * Configure an LND connection.
     */
    @JvmStatic
    fun configureLnd(context: Context, url: String, macaroon: String): Boolean {
        return try {
            val config = LightningConfig(
                type = LightningNodeType.LND,
                url = url,
                credential = macaroon
            )
            LightningEngineProvider.get(context).configure(config)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure LND", e)
            false
        }
    }

    /**
     * Configure a Core Lightning (CLN) connection.
     */
    @JvmStatic
    fun configureCln(context: Context, url: String, rune: String): Boolean {
        return try {
            val config = LightningConfig(
                type = LightningNodeType.CLN,
                url = url,
                credential = rune
            )
            LightningEngineProvider.get(context).configure(config)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure CLN", e)
            false
        }
    }

    /**
     * Configure a Phoenixd connection.
     */
    @JvmStatic
    fun configurePhoenixd(context: Context, url: String, password: String): Boolean {
        return try {
            val config = LightningConfig(
                type = LightningNodeType.PHOENIXD,
                url = url,
                credential = password
            )
            LightningEngineProvider.get(context).configure(config)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure Phoenixd", e)
            false
        }
    }

    /**
     * Configure a Strike connection.
     */
    @JvmStatic
    fun configureStrike(context: Context, apiKey: String): Boolean {
        return try {
            val config = LightningConfig(
                type = LightningNodeType.STRIKE,
                credential = apiKey
            )
            LightningEngineProvider.get(context).configure(config)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure Strike", e)
            false
        }
    }

    /**
     * Configure a Blink connection.
     */
    @JvmStatic
    fun configureBlink(context: Context, apiKey: String): Boolean {
        return try {
            val config = LightningConfig(
                type = LightningNodeType.BLINK,
                credential = apiKey
            )
            LightningEngineProvider.get(context).configure(config)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure Blink", e)
            false
        }
    }

    /**
     * Configure a Speed connection.
     */
    @JvmStatic
    fun configureSpeed(context: Context, apiKey: String): Boolean {
        return try {
            val config = LightningConfig(
                type = LightningNodeType.SPEED,
                credential = apiKey
            )
            LightningEngineProvider.get(context).configure(config)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to configure Speed", e)
            false
        }
    }

    /**
     * Clear the Lightning configuration.
     */
    @JvmStatic
    fun clearConfiguration(context: Context) {
        try {
            LightningEngineProvider.get(context).clearConfiguration()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear Lightning configuration", e)
        }
    }

    /**
     * Get the configured Lightning node type.
     */
    @JvmStatic
    fun getConfiguredNodeType(context: Context): LightningNodeType? {
        return try {
            LightningEngineProvider.get(context).getConfiguredNodeType()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get Lightning node type", e)
            null
        }
    }
}
