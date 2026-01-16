package org.thoughtcrime.securesms.payments.preferences.model;

import android.content.Context;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningTx;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningTxType;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel;

import java.util.Locale;

/**
 * Model for displaying Lightning transaction activity items in the payments home list.
 */
public final class LightningActivityItem implements MappingModel<LightningActivityItem> {

  public enum State { SEND, RECEIVE, PENDING }

  private final String  paymentHash;
  private final long    timestampMs;
  private final long    amountSats;
  private final long    feesPaidSats;
  private final State   state;
  private final String  description;
  private final String  preimage;
  private final boolean isPaid;

  public LightningActivityItem(@NonNull String paymentHash,
                               long timestampMs,
                               long amountSats,
                               long feesPaidSats,
                               @NonNull State state,
                               String description,
                               String preimage,
                               boolean isPaid) {
    this.paymentHash = paymentHash;
    this.timestampMs = timestampMs;
    this.amountSats = amountSats;
    this.feesPaidSats = feesPaidSats;
    this.state = state;
    this.description = description;
    this.preimage = preimage;
    this.isPaid = isPaid;
  }

  /**
   * Create a LightningActivityItem from a LightningTx.
   */
  public static LightningActivityItem fromLightningTx(@NonNull LightningTx tx) {
    State state;
    if (!tx.isPaid()) {
      state = State.PENDING;
    } else if (tx.getType() == LightningTxType.RECEIVE) {
      state = State.RECEIVE;
    } else {
      state = State.SEND;
    }
    
    return new LightningActivityItem(
        tx.getPaymentHash(),
        tx.getCreatedAt(),
        tx.getAmountSats(),
        tx.getFeesPaidSats(),
        state,
        tx.getDescription(),
        tx.getPreimage(),
        tx.isPaid()
    );
  }

  public @NonNull String getPaymentHash() { return paymentHash; }
  public long getTimestampMs() { return timestampMs; }
  public long getAmountSats() { return amountSats; }
  public long getFeesPaidSats() { return feesPaidSats; }
  public @NonNull State getState() { return state; }
  public String getDescription() { return description; }
  public String getPreimage() { return preimage; }
  public boolean isPaid() { return isPaid; }

  public @NonNull String getTitle(@NonNull Context context) {
    if (description != null && !description.isEmpty()) {
      // Truncate long descriptions
      if (description.length() > 40) {
        return description.substring(0, 37) + "...";
      }
      return description;
    }
    
    switch (state) {
      case SEND:
        return context.getString(R.string.LightningActivity__sent_payment);
      case RECEIVE:
        return context.getString(R.string.LightningActivity__received_payment);
      case PENDING:
      default:
        return context.getString(R.string.LightningActivity__pending_payment);
    }
  }

  public @NonNull String getDate(@NonNull Context context) {
    return DateUtils.formatDateWithoutDayOfWeek(Locale.getDefault(), timestampMs);
  }

  public @NonNull String getAmountText() {
    String base = formatSats(amountSats) + " sat";
    if (state == State.SEND) return "-" + base;
    if (state == State.RECEIVE) return "+" + base;
    return base; // pending (no sign)
  }

  public @ColorRes int getAmountColor() {
    if (state == State.SEND) return R.color.signal_alert_primary; // red-ish for outgoing
    if (state == State.RECEIVE) return R.color.core_green; // green for incoming
    return R.color.signal_text_secondary; // pending grey
  }

  private static String formatSats(long sats) {
    java.text.NumberFormat nf = java.text.NumberFormat.getInstance(java.util.Locale.getDefault());
    nf.setGroupingUsed(true);
    nf.setMaximumFractionDigits(0);
    return nf.format(sats);
  }

  @Override
  public boolean areItemsTheSame(@NonNull LightningActivityItem newItem) {
    return paymentHash.equals(newItem.paymentHash);
  }

  @Override
  public boolean areContentsTheSame(@NonNull LightningActivityItem newItem) {
    return timestampMs == newItem.timestampMs && 
           amountSats == newItem.amountSats && 
           state == newItem.state && 
           isPaid == newItem.isPaid;
  }
}
