package org.thoughtcrime.securesms.payments.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.payments.engine.lightning.LightningEngineProvider
import org.thoughtcrime.securesms.payments.engine.lightning.LightningBalance as LniLightningBalance
import org.thoughtcrime.securesms.payments.engine.lightning.LightningPaymentResult

/**
 * Unified wallet interactor that routes between Cashu and Lightning
 * based on user configuration.
 * 
 * This provides a single entry point for wallet operations that:
 * 1. Tries Lightning first if configured
 * 2. Falls back to Cashu if Lightning fails or is unavailable
 * 3. Can use both for combined balance views
 * 
 * Routing strategy:
 * - Try Lightning first (direct, faster for larger amounts)
 * - Fall back to Cashu (via melt/mint) if Lightning fails or not configured
 * - Cashu tokens can be sent/received directly for ecash transfers
 */
object WalletInteractor {
    private const val TAG = "WalletInteractor"

    /**
     * Wallet mode based on current configuration.
     */
    enum class WalletMode {
        /** Only Cashu (ecash) available - use melt/mint for Lightning interop */
        CASHU_ONLY,
        /** Only Lightning node available */
        LIGHTNING_ONLY,
        /** Both available - try Lightning first, fall back to Cashu */
        BOTH,
        /** No wallet configured */
        NONE
    }

    /**
     * Combined balance from all available wallets.
     */
    data class CombinedBalance(
        val cashuSpendableSats: Long,
        val lightningSendableSats: Long,
        val lightningReceivableSats: Long,
        val totalSpendableSats: Long
    )

    /**
     * Result of a payment operation.
     */
    sealed class PaymentResult {
        data class Success(
            val paymentHash: String?,
            val preimage: String?,
            val feeSats: Long,
            val usedLightning: Boolean
        ) : PaymentResult()
        
        data class Failure(
            val reason: String,
            val exception: Throwable? = null
        ) : PaymentResult()
    }

    /**
     * Get current wallet mode based on configuration.
     */
    @JvmStatic
    fun getWalletMode(context: Context): WalletMode {
        val cashuEnabled = SignalStore.payments.cashuEnabled() || org.thoughtcrime.securesms.BuildConfig.DEBUG
        val lightningEnabled = SignalStore.payments.lightningEnabled()
        val lightningConfigured = try {
            LightningEngineProvider.get(context).isConfigured()
        } catch (e: Throwable) {
            false
        }
        
        Log.d(TAG, "getWalletMode: cashuEnabled=$cashuEnabled, lightningEnabled=$lightningEnabled, lightningConfigured=$lightningConfigured")

        val mode = when {
            !cashuEnabled && !lightningConfigured -> WalletMode.NONE
            cashuEnabled && lightningConfigured && lightningEnabled -> WalletMode.BOTH
            !cashuEnabled && lightningConfigured && lightningEnabled -> WalletMode.LIGHTNING_ONLY
            cashuEnabled -> WalletMode.CASHU_ONLY
            else -> WalletMode.NONE
        }
        Log.d(TAG, "getWalletMode: result=$mode")
        return mode
    }

    /**
     * Check if any wallet is available.
     */
    @JvmStatic
    fun isWalletAvailable(context: Context): Boolean {
        return getWalletMode(context) != WalletMode.NONE
    }

    /**
     * Check if Lightning is available and should be tried first.
     */
    @JvmStatic
    fun shouldUseLightning(context: Context): Boolean {
        val mode = getWalletMode(context)
        return mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.BOTH
    }

    /**
     * Get combined balance from all wallets (blocking version for Java).
     */
    @JvmStatic
    fun getCombinedBalanceBlocking(context: Context): CombinedBalance = runBlocking {
        getCombinedBalance(context)
    }

    /**
     * Get combined balance from all wallets.
     */
    suspend fun getCombinedBalance(context: Context): CombinedBalance = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)
        
        var cashuSats = 0L
        var lightningSend = 0L
        var lightningReceive = 0L

        // Get Cashu balance if available
        if (mode in listOf(WalletMode.CASHU_ONLY, WalletMode.BOTH)) {
            cashuSats = try {
                PaymentsEngineProvider.get(context).getBalance().spendableSats
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get Cashu balance", e)
                0L
            }
        }

        // Get Lightning balance if available
        if (mode in listOf(WalletMode.LIGHTNING_ONLY, WalletMode.BOTH)) {
            try {
                val lnBalance = LightningEngineProvider.get(context).getBalance()
                lightningSend = lnBalance.sendBalanceSats
                lightningReceive = lnBalance.receiveBalanceSats
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get Lightning balance", e)
            }
        }

        CombinedBalance(
            cashuSpendableSats = cashuSats,
            lightningSendableSats = lightningSend,
            lightningReceivableSats = lightningReceive,
            totalSpendableSats = cashuSats + lightningSend
        )
    }

    /**
     * Pay a Lightning invoice using the best available method.
     * 
     * Strategy: Try Lightning first, fall back to Cashu melt.
     */
    @JvmStatic
    fun payInvoiceBlocking(context: Context, invoice: String): PaymentResult = runBlocking {
        payInvoice(context, invoice)
    }

    /**
     * Pay a Lightning invoice using the best available method.
     * 
     * Strategy:
     * 1. If Lightning is available -> try Lightning first
     * 2. If Lightning fails or unavailable -> fall back to Cashu melt
     */
    suspend fun payInvoice(context: Context, invoice: String): PaymentResult = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)
        Log.d(TAG, "payInvoice: mode=$mode")

        // Try Lightning first if available
        if (mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.BOTH) {
            val lightningResult = tryPayViaLightning(context, invoice)
            if (lightningResult is PaymentResult.Success) {
                Log.i(TAG, "Payment succeeded via Lightning")
                return@withContext lightningResult
            }
            // If Lightning-only, no fallback available
            if (mode == WalletMode.LIGHTNING_ONLY) {
                return@withContext lightningResult
            }
            // Fall back to Cashu
            Log.i(TAG, "Lightning payment failed, falling back to Cashu melt")
        }

        // Use Cashu melt (either as primary for CASHU_ONLY or fallback for BOTH)
        if (mode in listOf(WalletMode.CASHU_ONLY, WalletMode.BOTH)) {
            val cashuResult = tryPayViaCashuMelt(context, invoice)
            if (cashuResult is PaymentResult.Success) {
                Log.i(TAG, "Payment succeeded via Cashu melt")
            }
            return@withContext cashuResult
        }

        PaymentResult.Failure("No wallet configured")
    }

    private suspend fun tryPayViaLightning(context: Context, invoice: String): PaymentResult {
        return try {
            val engine = LightningEngineProvider.get(context)
            val result = engine.payInvoice(invoice, null).getOrThrow()
            PaymentResult.Success(
                paymentHash = result.paymentHash,
                preimage = result.preimage,
                feeSats = result.feeSats,
                usedLightning = true
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Lightning payment failed", e)
            PaymentResult.Failure("Lightning payment failed: ${e.message}", e)
        }
    }

    private suspend fun tryPayViaCashuMelt(context: Context, invoice: String): PaymentResult {
        return try {
            val engine = PaymentsEngineProvider.get(context)
            
            // Get melt quote
            val quote = engine.requestMeltQuote(invoice).getOrThrow()
            
            // Execute melt
            val txId = engine.melt(quote).getOrThrow()
            
            PaymentResult.Success(
                paymentHash = txId.id,
                preimage = null, // Cashu melt may not return preimage
                feeSats = quote.feeSats,
                usedLightning = false
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Cashu melt failed", e)
            PaymentResult.Failure("Cashu melt failed: ${e.message}", e)
        }
    }

    /**
     * Create an invoice/receive request using the best available method.
     */
    @JvmStatic
    fun createReceiveRequestBlocking(context: Context, amountSats: Long, memo: String? = null): String? = runBlocking {
        createReceiveRequest(context, amountSats, memo)
    }

    /**
     * Create an invoice/receive request using the best available method.
     * 
     * Strategy: Try Lightning first, fall back to Cashu mint quote.
     */
    suspend fun createReceiveRequest(context: Context, amountSats: Long, memo: String? = null): String? = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)
        Log.d(TAG, "createReceiveRequest: mode=$mode, amountSats=$amountSats")

        // Try Lightning first if available
        if (mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.BOTH) {
            Log.d(TAG, "Attempting Lightning invoice creation...")
            try {
                val result = LightningEngineProvider.get(context)
                    .createInvoice(amountSats, memo)
                Log.d(TAG, "Lightning createInvoice result: isSuccess=${result.isSuccess}, isFailure=${result.isFailure}")
                val invoice = result.getOrNull()
                if (invoice != null && invoice.isNotEmpty()) {
                    Log.i(TAG, "Created Lightning invoice successfully")
                    return@withContext invoice
                } else {
                    Log.w(TAG, "Lightning invoice returned null/empty, exception: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Lightning invoice creation threw exception", e)
            }
            
            // If Lightning-only, return null on failure
            if (mode == WalletMode.LIGHTNING_ONLY) {
                Log.w(TAG, "Lightning-only mode, no fallback available")
                return@withContext null
            }
            Log.i(TAG, "Lightning invoice failed, falling back to Cashu mint quote")
        }

        // Use Cashu mint quote (either as primary for CASHU_ONLY or fallback for BOTH)
        if (mode in listOf(WalletMode.CASHU_ONLY, WalletMode.BOTH)) {
            try {
                Log.d(TAG, "Attempting Cashu mint quote for $amountSats sats")
                val quoteResult = PaymentsEngineProvider.get(context)
                    .requestMintQuote(amountSats)
                
                if (quoteResult.isFailure) {
                    Log.w(TAG, "Cashu mint quote failed with exception", quoteResult.exceptionOrNull())
                }
                
                val quote = quoteResult.getOrNull()
                Log.d(TAG, "Cashu mint quote result: quote=$quote, invoice=${quote?.invoiceBolt11?.take(30)}...")
                if (quote != null && !quote.invoiceBolt11.isNullOrEmpty()) {
                    Log.i(TAG, "Created Cashu mint quote with invoice")
                    return@withContext quote.invoiceBolt11
                } else if (quote != null) {
                    Log.w(TAG, "Cashu mint quote returned but no invoice: id=${quote.id}, mintUrl=${quote.mintUrl}")
                } else {
                    Log.w(TAG, "Cashu mint quote returned null (Result was failure=${quoteResult.isFailure})")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Cashu mint quote failed", e)
            }
        }

        null
    }

    /**
     * Create a Cashu token for sending to another user.
     * This is Cashu-specific and not available with Lightning-only mode.
     */
    @JvmStatic
    fun createCashuTokenBlocking(context: Context, amountSats: Long, memo: String? = null): String? = runBlocking {
        createCashuToken(context, amountSats, memo)
    }

    /**
     * Create a Cashu token for sending.
     */
    suspend fun createCashuToken(context: Context, amountSats: Long, memo: String? = null): String? = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)
        
        if (mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.NONE) {
            Log.w(TAG, "Cashu tokens not available in mode: $mode")
            return@withContext null
        }

        try {
            PaymentsEngineProvider.get(context)
                .createSendToken(amountSats, memo)
                .getOrNull()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create Cashu token", e)
            null
        }
    }

    /**
     * Import a Cashu token.
     * This is Cashu-specific but available when both wallets are configured.
     */
    @JvmStatic
    fun importCashuTokenBlocking(context: Context, token: String): Boolean = runBlocking {
        importCashuToken(context, token)
    }

    /**
     * Import a Cashu token.
     * Available in CASHU_ONLY and BOTH modes.
     */
    suspend fun importCashuToken(context: Context, token: String): Boolean = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)
        
        if (mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.NONE) {
            Log.w(TAG, "Cashu import not available in mode: $mode")
            return@withContext false
        }

        try {
            PaymentsEngineProvider.get(context)
                .importToken(token)
                .isSuccess
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to import Cashu token", e)
            false
        }
    }
}
