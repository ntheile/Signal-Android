package org.thoughtcrime.securesms.payments.preferences.details;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.navigation.Navigation;

import org.thoughtcrime.securesms.LoggingFragment;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.DateUtils;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Fragment displaying details of a Lightning transaction.
 */
public final class LightningTransactionDetailsFragment extends LoggingFragment {

  public LightningTransactionDetailsFragment() {
    super(R.layout.lightning_transaction_details_fragment);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    Toolbar toolbar = view.findViewById(R.id.lightning_details_toolbar);
    toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).popBackStack());

    LightningTransactionDetailsParcelable details = LightningTransactionDetailsFragmentArgs.fromBundle(requireArguments()).getTransactionDetails();

    ImageView icon = view.findViewById(R.id.lightning_details_icon);
    TextView direction = view.findViewById(R.id.lightning_details_direction);
    TextView amount = view.findViewById(R.id.lightning_details_amount);
    TextView description = view.findViewById(R.id.lightning_details_description);
    TextView status = view.findViewById(R.id.lightning_details_status);
    TextView date = view.findViewById(R.id.lightning_details_date);
    TextView feeHeader = view.findViewById(R.id.lightning_details_fee_header);
    TextView fee = view.findViewById(R.id.lightning_details_fee);
    TextView hashValue = view.findViewById(R.id.lightning_details_hash);
    TextView preimageHeader = view.findViewById(R.id.lightning_details_preimage_header);
    TextView preimageValue = view.findViewById(R.id.lightning_details_preimage);

    // Direction text
    if (details.isIncoming()) {
      direction.setText(R.string.LightningActivity__received_payment);
    } else {
      direction.setText(R.string.LightningActivity__sent_payment);
    }

    // Amount with sign and color
    String amountText = formatSats(details.getAmountSats()) + " sat";
    if (details.isIncoming()) {
      amount.setText("+" + amountText);
      amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.core_green));
    } else {
      amount.setText("-" + amountText);
      amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_alert_primary));
    }

    // Description/memo
    String desc = details.getDescription();
    if (!TextUtils.isEmpty(desc)) {
      description.setText(desc);
      description.setVisibility(View.VISIBLE);
    } else {
      description.setVisibility(View.GONE);
    }

    // Status
    if (details.isPaid()) {
      status.setText(R.string.LightningDetailsFragment__completed);
      status.setTextColor(ContextCompat.getColor(requireContext(), R.color.core_green));
    } else {
      status.setText(R.string.LightningDetailsFragment__pending);
      status.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_secondary));
    }

    // Date and time
    String dateStr = DateUtils.formatDateWithoutDayOfWeek(Locale.getDefault(), details.getTimestampMs());
    String timeStr = DateUtils.getTimeString(requireContext(), Locale.getDefault(), details.getTimestampMs());
    date.setText(dateStr + " " + getString(R.string.LightningDetailsFragment__at) + " " + timeStr);

    // Fee (only for outgoing)
    if (!details.isIncoming() && details.getFeesPaidSats() > 0) {
      feeHeader.setVisibility(View.VISIBLE);
      fee.setVisibility(View.VISIBLE);
      fee.setText(formatSats(details.getFeesPaidSats()) + " sat");
    } else {
      feeHeader.setVisibility(View.GONE);
      fee.setVisibility(View.GONE);
    }

    // Payment hash (truncated for display)
    String hash = details.getPaymentHash();
    if (hash.length() > 32) {
      hashValue.setText(hash.substring(0, 16) + "..." + hash.substring(hash.length() - 16));
    } else {
      hashValue.setText(hash);
    }

    // Preimage (only shown if available)
    String preimage = details.getPreimage();
    if (!TextUtils.isEmpty(preimage)) {
      preimageHeader.setVisibility(View.VISIBLE);
      preimageValue.setVisibility(View.VISIBLE);
      if (preimage.length() > 32) {
        preimageValue.setText(preimage.substring(0, 16) + "..." + preimage.substring(preimage.length() - 16));
      } else {
        preimageValue.setText(preimage);
      }
    } else {
      preimageHeader.setVisibility(View.GONE);
      preimageValue.setVisibility(View.GONE);
    }
  }

  private static String formatSats(long sats) {
    NumberFormat nf = NumberFormat.getInstance(Locale.getDefault());
    nf.setGroupingUsed(true);
    nf.setMaximumFractionDigits(0);
    return nf.format(sats);
  }
}
