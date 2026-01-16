package org.thoughtcrime.securesms.payments.preferences.cashu;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.payments.engine.CashuUiInteractor;
import org.thoughtcrime.securesms.payments.engine.MintQuote;
import org.thoughtcrime.securesms.payments.engine.WalletInteractor;

/**
 * Helper to build a display string/QR content for receiving payments.
 * Uses WalletInteractor to try Lightning first, then fall back to Cashu mint quote.
 */
public final class CashuMintQuoteUiHelper {
  private static final String TAG = Log.tag(CashuMintQuoteUiHelper.class);

  private CashuMintQuoteUiHelper() {}

  /**
   * Backwards-compatible default (10k sats). Prefer getReceiveInvoice.
   */
  public static @NonNull String getOrCreateMintQuoteQr(@NonNull Context context) {
    return getReceiveInvoice(context, 10_000L, null);
  }

  /**
   * Request a receive invoice using WalletInteractor routing:
   * 1. Try Lightning invoice first (if node configured)
   * 2. Fall back to Cashu mint quote
   * 
   * Returns a BOLT11 invoice string, or an error placeholder.
   */
  public static @NonNull String getReceiveInvoice(@NonNull Context context, long amountSats, @Nullable String memo) {
    try {
      String invoice = WalletInteractor.createReceiveRequestBlocking(context, amountSats, memo);
      if (invoice != null && !invoice.isEmpty()) {
        return invoice;
      }
      return "invoice:unavailable";
    } catch (Throwable t) {
      Log.w(TAG, "Failed to create receive invoice", t);
      return "invoice:error";
    }
  }

  /**
   * Legacy method for Cashu-only mint quote. Prefer getReceiveInvoice for unified routing.
   */
  public static @Nullable MintQuote requestMintQuote(@NonNull Context context, long amountSats) {
    try {
      return CashuUiInteractor.requestMintQuoteBlocking(context, amountSats);
    } catch (Throwable t) {
      Log.w(TAG, "mint quote failed", t);
      return null;
    }
  }

  /**
   * Legacy method. Prefer getReceiveInvoice for unified routing.
   */
  public static @NonNull String getMintQuoteQrForAmount(@NonNull Context context, long amountSats) {
    return getReceiveInvoice(context, amountSats, null);
  }
}
