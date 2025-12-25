package org.thoughtcrime.securesms.payments.preferences.transfer;

import android.Manifest;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.LoggingFragment;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningPaymentResult;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.navigation.SafeNavigation;

public final class PaymentsTransferFragment extends LoggingFragment {

  private static final String TAG = Log.tag(PaymentsTransferFragment.class);

  private EditText address;

  public PaymentsTransferFragment() {
    super(R.layout.payments_transfer_fragment);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    Toolbar toolbar = view.findViewById(R.id.payments_transfer_toolbar);

    view.findViewById(R.id.payments_transfer_scan_qr).setOnClickListener(v -> scanQrCode());
    view.findViewById(R.id.payments_transfer_next).setOnClickListener(v -> next());

    address = view.findViewById(R.id.payments_transfer_to_address);
    address.setHint(R.string.PaymentsPayInvoice__paste_invoice);
    address.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_DONE) {
        return next();
      }
      return false;
    });

    // Only create MobileCoin ViewModel if not using Cashu
    if (!org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
      PaymentsTransferViewModel viewModel = new ViewModelProvider(Navigation.findNavController(view).getViewModelStoreOwner(R.id.payments_transfer), new PaymentsTransferViewModel.Factory()).get(PaymentsTransferViewModel.class);
      viewModel.getAddress().observe(getViewLifecycleOwner(), address::setText);
    }

    toolbar.setNavigationOnClickListener(v -> {
      ViewUtil.hideKeyboard(requireContext(), v);
      Navigation.findNavController(v).popBackStack();
    });
  }

  private boolean next() {
    String invoice = address.getText().toString().trim();
    if (invoice.isEmpty()) {
      new MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.PaymentsTransferFragment__invalid_address)
          .setMessage(R.string.PaymentsPayInvoice__paste_invoice)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return false;
    }

    Toast.makeText(requireContext(), R.string.PaymentsPayInvoice__requesting_quote, Toast.LENGTH_SHORT).show();

    // Background thread to process payment
    new Thread(() -> {
      try {
        // Check if Lightning node is configured - use it directly for payments
        boolean lightningConfigured = LightningUiInteractor.isConfigured(AppDependencies.getApplication());
        Log.i(TAG, "next: lightningConfigured=" + lightningConfigured);
        
        if (lightningConfigured) {
          Log.i(TAG, "next: attempting Lightning payment");
          // Pay directly via Lightning node
          LightningPaymentResult result = LightningUiInteractor.payInvoiceBlocking(
              AppDependencies.getApplication(), invoice, null);
          
          if (result != null) {
            Log.i(TAG, "next: Lightning payment succeeded");
            requireView().post(() -> {
              Toast.makeText(requireContext(), R.string.LightningPayment__payment_successful, Toast.LENGTH_SHORT).show();
              // Navigate back to payments home
              Navigation.findNavController(requireView()).popBackStack();
              // Notify home to refresh activity
              getParentFragmentManager().setFragmentResult("cashu_history_changed", new Bundle());
            });
          } else {
            // Lightning payment failed, fallback to Cashu melt
            Log.i(TAG, "next: Lightning payment failed, falling back to Cashu melt");
            payViaCashuMelt(invoice);
          }
        } else {
          // Use Cashu melt (original flow)
          Log.i(TAG, "next: Lightning not configured, using Cashu melt");
          payViaCashuMelt(invoice);
        }
      } catch (Throwable t) {
        Log.w(TAG, "Payment failed", t);
        requireView().post(() -> Toast.makeText(requireContext(), R.string.PaymentsPayInvoice__unable_to_pay, Toast.LENGTH_LONG).show());
      }
    }).start();

    return true;
  }
  
  private void payViaCashuMelt(String invoice) {
    Log.i(TAG, "payViaCashuMelt: starting melt flow for invoice=" + invoice.substring(0, Math.min(30, invoice.length())) + "...");
    try {
      org.thoughtcrime.securesms.payments.engine.MeltQuote quote = org.thoughtcrime.securesms.payments.engine.CashuUiInteractor.requestMeltQuoteBlocking(AppDependencies.getApplication(), invoice);
      if (quote == null) {
        Log.w(TAG, "payViaCashuMelt: quote is null");
        throw new RuntimeException("No quote");
      }
      Log.i(TAG, "payViaCashuMelt: got quote, amount=" + quote.getAmountSats() + " fee=" + quote.getFeeSats());
      Bundle args = PayInvoiceConfirmFragment.argsFromQuote(quote);
      requireView().post(() -> SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), R.id.action_paymentsTransfer_to_payInvoiceConfirm, args));
    } catch (Throwable t) {
      Log.w(TAG, "payViaCashuMelt: failed", t);
      requireView().post(() -> Toast.makeText(requireContext(), R.string.PaymentsPayInvoice__unable_to_pay, Toast.LENGTH_LONG).show());
    }
  }


  private void scanQrCode() {
    Permissions.with(this)
               .request(Manifest.permission.CAMERA)
               .ifNecessary()
               .withRationaleDialog(getString(R.string.CameraXFragment_allow_access_camera), getString(R.string.PaymentsTransferFragment__to_scan_a_qr_code_signal_needs), R.drawable.ic_camera_24)
               .withPermanentDenialDialog(getString(R.string.PaymentsTransferFragment__to_scan_a_qr_code_signal_needs_access_to_the_camera), null, R.string.CameraXFragment_allow_access_camera, R.string.CameraXFragment_to_scan_qr_codes, getParentFragmentManager())
               .onAllGranted(() -> SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), R.id.action_paymentsTransfer_to_paymentsScanQr))
               .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.PaymentsTransferFragment__to_scan_a_qr_code_signal_needs_access_to_the_camera, Toast.LENGTH_LONG).show())
               .execute();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }
}
