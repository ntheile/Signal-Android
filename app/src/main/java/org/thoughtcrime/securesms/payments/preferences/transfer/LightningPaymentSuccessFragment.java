package org.thoughtcrime.securesms.payments.preferences.transfer;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.navigation.Navigation;

import com.airbnb.lottie.LottieAnimationView;

import org.thoughtcrime.securesms.LoggingFragment;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.payments.preferences.details.LightningTransactionDetailsParcelable;
import org.thoughtcrime.securesms.util.DateUtils;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Fragment displaying a success animation and transaction details after a successful Lightning payment.
 */
public final class LightningPaymentSuccessFragment extends LoggingFragment {

  public LightningPaymentSuccessFragment() {
    super(R.layout.lightning_payment_success_fragment);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    Toolbar toolbar = view.findViewById(R.id.lightning_success_toolbar);
    toolbar.setNavigationOnClickListener(v -> navigateToHome(v));

    LightningTransactionDetailsParcelable details = LightningPaymentSuccessFragmentArgs.fromBundle(requireArguments()).getTransactionDetails();

    LottieAnimationView successAnimation = view.findViewById(R.id.lightning_success_animation);
    TextView amount = view.findViewById(R.id.lightning_success_amount);
    TextView date = view.findViewById(R.id.lightning_success_date);
    TextView feeHeader = view.findViewById(R.id.lightning_success_fee_header);
    TextView fee = view.findViewById(R.id.lightning_success_fee);
    TextView hashValue = view.findViewById(R.id.lightning_success_hash);
    TextView preimageHeader = view.findViewById(R.id.lightning_success_preimage_header);
    TextView preimageValue = view.findViewById(R.id.lightning_success_preimage);
    View doneButton = view.findViewById(R.id.lightning_success_done_button);

    // Start animation
    successAnimation.playAnimation();

    // Amount (outgoing is negative)
    String amountText = "-" + formatSats(details.getAmountSats()) + " sat";
    amount.setText(amountText);

    // Date and time
    String dateStr = DateUtils.formatDateWithoutDayOfWeek(Locale.getDefault(), details.getTimestampMs());
    String timeStr = DateUtils.getTimeString(requireContext(), Locale.getDefault(), details.getTimestampMs());
    date.setText(dateStr + " " + getString(R.string.LightningDetailsFragment__at) + " " + timeStr);

    // Fee
    if (details.getFeesPaidSats() > 0) {
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

    // Done button - navigate back to payments home
    doneButton.setOnClickListener(this::navigateToHome);
  }

  private void navigateToHome(View v) {
    // Pop back to payments home, clearing the transfer stack
    // Use popBackStack with inclusive=true to clear the entire transfer flow
    try {
      Navigation.findNavController(v).popBackStack(R.id.paymentsHome, false);
    } catch (Throwable e) {
      // Fallback: pop back stack until we can't anymore
      while (Navigation.findNavController(v).popBackStack()) {
        // Keep popping
      }
    }
    // Notify home to refresh activity
    getParentFragmentManager().setFragmentResult("cashu_history_changed", new Bundle());
  }

  private static String formatSats(long sats) {
    NumberFormat nf = NumberFormat.getInstance(Locale.getDefault());
    nf.setGroupingUsed(true);
    nf.setMaximumFractionDigits(0);
    return nf.format(sats);
  }
}
