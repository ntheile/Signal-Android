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
 * based on user configuration and preferences.
 * 
 * This provides a single entry point for wallet operations that:
 * 1. Checks if Lightning is configured and preferred
 * 2. Falls back to Cashu if Lightning is unavailable
 * 3. Can use both for combined balance views
 * 
 * Usage:
 * - If user has configured Lightning node and set it as preferred:
 *   - Payments go directly via Lightning (faster, lower fees for larger amounts)
 * - If user only has Cashu enabled:
 *   - Payments use Cashu melt/mint for Lightning interop
 * - If both are available:
 *   - User can choose, or auto-route based on amount/fees
 */
object WalletInteractor {
    private const val TAG = "WalletInteractor"

    /**
     * Wallet mode based on current configuration.
     */
    enum class WalletMode {
        /** Only Cashu (ecash) available */
        CASHU_ONLY,
        /** Only Lightning node available (rare - usually Cashu is also enabled) */
        LIGHTNING_ONLY,
        /** Both available, user prefers Lightning for withdrawals */
        BOTH_PREFER_LIGHTNING,
        /** Both available, user prefers Cashu for privacy */
        BOTH_PREFER_CASHU,
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
        val lightningPreferred = SignalStore.payments.lightningPreferred()

        return when {
            !cashuEnabled && !lightningConfigured -> WalletMode.NONE
            cashuEnabled && !lightningConfigured -> WalletMode.CASHU_ONLY
            !cashuEnabled && lightningConfigured && lightningEnabled -> WalletMode.LIGHTNING_ONLY
            cashuEnabled && lightningConfigured && lightningEnabled && lightningPreferred -> WalletMode.BOTH_PREFER_LIGHTNING
            cashuEnabled && lightningConfigured && lightningEnabled -> WalletMode.BOTH_PREFER_CASHU
            cashuEnabled -> WalletMode.CASHU_ONLY
            else -> WalletMode.NONE
        }
    }

    /**
     * Check if any wallet is available.
     */
    @JvmStatic
    fun isWalletAvailable(context: Context): Boolean {
        return getWalletMode(context) != WalletMode.NONE
    }

    /**
     * Check if Lightning is available and should be used.
     */
    @JvmStatic
    fun shouldUseLightning(context: Context): Boolean {
        val mode = getWalletMode(context)
        return mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.BOTH_PREFER_LIGHTNING
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
        if (mode in listOf(WalletMode.CASHU_ONLY, WalletMode.BOTH_PREFER_CASHU, WalletMode.BOTH_PREFER_LIGHTNING)) {
            cashuSats = try {
                PaymentsEngineProvider.get(context).getBalance().spendableSats
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get Cashu balance", e)
                0L
            }
        }

        // Get Lightning balance if available
        if (mode in listOf(WalletMode.LIGHTNING_ONLY, WalletMode.BOTH_PREFER_CASHU, WalletMode.BOTH_PREFER_LIGHTNING)) {
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
     * Routes based on:
     * 1. If Lightning is preferred and available -> use direct Lightning
     * 2. Otherwise -> use Cashu melt
     */
    @JvmStatic
    fun payInvoiceBlocking(context: Context, invoice: String): PaymentResult = runBlocking {
        payInvoice(context, invoice)
    }

    /**
     * Pay a Lightning invoice using the best available method.
     */
    suspend fun payInvoice(context: Context, invoice: String): PaymentResult = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)

        // Try Lightning first if preferred
        if (mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.BOTH_PREFER_LIGHTNING) {
            val lightningResult = tryPayViaLightning(context, invoice)
            if (lightningResult is PaymentResult.Success) {
                return@withContext lightningResult
            }
            // If Lightning failed and we have Cashu, fall back
            if (mode == WalletMode.BOTH_PREFER_LIGHTNING) {
                Log.i(TAG, "Lightning payment failed, falling back to Cashu melt")
            } else {
                return@withContext lightningResult // No fallback available
            }
        }

        // Use Cashu melt
        if (mode in listOf(WalletMode.CASHU_ONLY, WalletMode.BOTH_PREFER_CASHU, WalletMode.BOTH_PREFER_LIGHTNING)) {
            return@withContext tryPayViaCashuMelt(context, invoice)
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
     * Routes based on:
     * 1. If Lightning is preferred and available -> create Lightning invoice
     * 2. Otherwise -> create Cashu mint quote (returns bolt11)
     */
    suspend fun createReceiveRequest(context: Context, amountSats: Long, memo: String? = null): String? = withContext(Dispatchers.IO) {
        val mode = getWalletMode(context)

        // Try Lightning first if preferred
        if (mode == WalletMode.LIGHTNING_ONLY || mode == WalletMode.BOTH_PREFER_LIGHTNING) {
            try {
                val invoice = LightningEngineProvider.get(context)
                    .createInvoice(amountSats, memo)
                    .getOrNull()
                if (invoice != null) return@withContext invoice
            } catch (e: Throwable) {
                Log.w(TAG, "Lightning invoice creation failed", e)
            }
            
            // If Lightning-only, return null on failure
            if (mode == WalletMode.LIGHTNING_ONLY) {
                return@withContext null
            }
        }

        // Use Cashu mint quote
        if (mode in listOf(WalletMode.CASHU_ONLY, WalletMode.BOTH_PREFER_CASHU, WalletMode.BOTH_PREFER_LIGHTNING)) {
            try {
                val quote = PaymentsEngineProvider.get(context)
                    .requestMintQuote(amountSats)
                    .getOrNull()
                return@withContext quote?.invoiceBolt11
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
     * This is Cashu-specific.
     */
    @JvmStatic
    fun importCashuTokenBlocking(context: Context, token: String): Boolean = runBlocking {
        importCashuToken(context, token)
    }

    /**
     * Import a Cashu token.
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
