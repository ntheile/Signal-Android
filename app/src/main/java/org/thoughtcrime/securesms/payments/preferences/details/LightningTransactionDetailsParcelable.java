package org.thoughtcrime.securesms.payments.preferences.details;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Parcelable for passing Lightning transaction details to the details fragment.
 */
public class LightningTransactionDetailsParcelable implements Parcelable {

  private final String paymentHash;
  private final long timestampMs;
  private final long amountSats;
  private final long feesPaidSats;
  private final String description;
  private final String preimage;
  private final boolean isIncoming;
  private final boolean isPaid;

  public LightningTransactionDetailsParcelable(@NonNull String paymentHash,
                                               long timestampMs,
                                               long amountSats,
                                               long feesPaidSats,
                                               @Nullable String description,
                                               @Nullable String preimage,
                                               boolean isIncoming,
                                               boolean isPaid) {
    this.paymentHash = paymentHash;
    this.timestampMs = timestampMs;
    this.amountSats = amountSats;
    this.feesPaidSats = feesPaidSats;
    this.description = description;
    this.preimage = preimage;
    this.isIncoming = isIncoming;
    this.isPaid = isPaid;
  }

  protected LightningTransactionDetailsParcelable(Parcel in) {
    paymentHash = in.readString();
    timestampMs = in.readLong();
    amountSats = in.readLong();
    feesPaidSats = in.readLong();
    description = in.readString();
    preimage = in.readString();
    isIncoming = in.readByte() != 0;
    isPaid = in.readByte() != 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(paymentHash);
    dest.writeLong(timestampMs);
    dest.writeLong(amountSats);
    dest.writeLong(feesPaidSats);
    dest.writeString(description);
    dest.writeString(preimage);
    dest.writeByte((byte) (isIncoming ? 1 : 0));
    dest.writeByte((byte) (isPaid ? 1 : 0));
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Creator<LightningTransactionDetailsParcelable> CREATOR = new Creator<LightningTransactionDetailsParcelable>() {
    @Override
    public LightningTransactionDetailsParcelable createFromParcel(Parcel in) {
      return new LightningTransactionDetailsParcelable(in);
    }

    @Override
    public LightningTransactionDetailsParcelable[] newArray(int size) {
      return new LightningTransactionDetailsParcelable[size];
    }
  };

  public @NonNull String getPaymentHash() { return paymentHash; }
  public long getTimestampMs() { return timestampMs; }
  public long getAmountSats() { return amountSats; }
  public long getFeesPaidSats() { return feesPaidSats; }
  public @Nullable String getDescription() { return description; }
  public @Nullable String getPreimage() { return preimage; }
  public boolean isIncoming() { return isIncoming; }
  public boolean isPaid() { return isPaid; }
}
