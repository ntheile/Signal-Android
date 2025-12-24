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
     * Check if a Lightning node is configured.
     */
    @JvmStatic
    fun isConfigured(context: Context): Boolean {
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
        runCatching {
            LightningEngineProvider.get(context).createInvoice(amountSats, description).getOrNull()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to create Lightning invoice", throwable)
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
