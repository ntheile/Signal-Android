package org.thoughtcrime.securesms.payments.engine

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Synchronous (blocking) helpers for Java callers to interact with PaymentsEngine for Cashu.
 * Do NOT call on main thread unless invoking on a background thread.
 * All methods handle errors gracefully and return null/false/empty on failure.
 */
object CashuUiInteractor {
  private const val TAG = "CashuUiInteractor"
  
  @JvmStatic
  fun requestMintQuoteBlocking(context: Context, amountSats: Long): MintQuote? = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).requestMintQuote(amountSats).getOrNull()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to request mint quote", throwable)
      null
    }
  }

  @JvmStatic
  fun requestMeltQuoteBlocking(context: Context, invoiceBolt11: String): MeltQuote? = runBlocking {
    org.signal.core.util.logging.Log.d(TAG, "requestMeltQuoteBlocking: invoice=${invoiceBolt11.take(30)}...")
    runCatching {
      val result = PaymentsEngineProvider.get(context).requestMeltQuote(invoiceBolt11)
      if (result.isFailure) {
        org.signal.core.util.logging.Log.w(TAG, "requestMeltQuote failed", result.exceptionOrNull())
      }
      result.getOrNull()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to request melt quote", throwable)
      null
    }
  }

  @JvmStatic
  fun meltBlocking(context: Context, quote: MeltQuote): Boolean = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).melt(quote).isSuccess
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to melt", throwable)
      false
    }
  }

  @JvmStatic
  fun createSendTokenBlocking(context: Context, amountSats: Long, memo: String? = null): String? = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).createSendToken(amountSats, memo).getOrNull()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to create send token", throwable)
      null
    }
  }

  @JvmStatic
  fun mintPaidQuoteBlocking(context: Context, secretKeyOrId: String): Boolean = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).mintPaidQuote(secretKeyOrId).isSuccess
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to mint paid quote", throwable)
      false
    }
  }

  @JvmStatic
  fun listHistoryBlocking(context: Context, offset: Int, limit: Int): List<Tx> = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).listHistory(offset, limit)
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w("CashuUiInteractor", "Failed to list history", throwable)
      emptyList()
    }
  }
  
  // Lightning integration methods
  
  /**
   * Check if direct Lightning node is available.
   */
  @JvmStatic
  fun lightningAvailableBlocking(context: Context): Boolean = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).lightningAvailable()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to check Lightning availability", throwable)
      false
    }
  }
  
  /**
   * Get Lightning node balance (separate from Cashu).
   */
  @JvmStatic
  fun getLightningBalanceBlocking(context: Context): LightningBalance? = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).getLightningBalance()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to get Lightning balance", throwable)
      null
    }
  }
  
  /**
   * Pay a Lightning invoice directly via the connected node.
   */
  @JvmStatic
  fun payLightningInvoiceBlocking(context: Context, invoice: String, feeLimitSats: Long? = null): LightningPayment? = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).payLightningInvoice(invoice, feeLimitSats).getOrNull()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to pay Lightning invoice", throwable)
      null
    }
  }
  
  /**
   * Create a Lightning invoice directly via the connected node.
   */
  @JvmStatic
  fun createLightningInvoiceBlocking(context: Context, amountSats: Long, description: String? = null): String? = runBlocking {
    runCatching {
      PaymentsEngineProvider.get(context).createLightningInvoice(amountSats, description).getOrNull()
    }.getOrElse { throwable ->
      org.signal.core.util.logging.Log.w(TAG, "Failed to create Lightning invoice", throwable)
      null
    }
  }
}

