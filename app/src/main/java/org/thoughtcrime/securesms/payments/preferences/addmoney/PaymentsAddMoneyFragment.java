package org.thoughtcrime.securesms.payments.preferences.addmoney;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
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
import org.thoughtcrime.securesms.components.qr.QrView;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.payments.engine.MintWatcher;
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor;
import org.thoughtcrime.securesms.payments.engine.lightning.InvoiceResult;
import org.thoughtcrime.securesms.payments.preferences.cashu.CashuMintQuoteUiHelper;
import org.thoughtcrime.securesms.util.views.LearnMoreTextView;

public final class PaymentsAddMoneyFragment extends LoggingFragment {

  private static final String TAG = Log.tag(PaymentsAddMoneyFragment.class);

  public PaymentsAddMoneyFragment() {
    super(R.layout.payments_add_money_fragment);
  }

  @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    Toolbar           toolbar                  = view.findViewById(R.id.payments_add_money_toolbar);
    QrView            qrImageView              = view.findViewById(R.id.payments_add_money_qr_image);
    TextView          walletAddressAbbreviated = view.findViewById(R.id.payments_add_money_abbreviated_wallet_address);
    View              copyAddress              = view.findViewById(R.id.payments_add_money_copy_address_button);
    LearnMoreTextView info                     = view.findViewById(R.id.payments_add_money_info);
    View              qrBorder                 = view.findViewById(R.id.payments_add_money_qr_border);
    EditText          amountInput              = view.findViewById(R.id.cashu_amount_input);
    View              getInvoiceButton         = view.findViewById(R.id.cashu_get_invoice_button);
    View              lightningConfigButton    = view.findViewById(R.id.lightning_config_button);

    info.setLearnMoreVisible(true);
    info.setLink(getString(R.string.PaymentsAddMoneyFragment__learn_more__information));

    toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).popBackStack());

    // Check if Lightning is configured - use it exclusively, no Cashu fallback
    boolean lightningConfigured = LightningUiInteractor.isConfigured(AppDependencies.getApplication());
    Log.i(TAG, "PaymentsAddMoney: Lightning configured = " + lightningConfigured);
    
    // Lightning-only path when configured
    if (lightningConfigured) {
      qrBorder.setVisibility(View.GONE);
      if (lightningConfigButton != null) lightningConfigButton.setVisibility(View.GONE);
      
      // Update button text to indicate Lightning
      if (getInvoiceButton instanceof android.widget.Button) {
        ((android.widget.Button) getInvoiceButton).setText("⚡ Get Lightning Invoice");
      }
      info.setText("Create a Lightning invoice to receive funds directly to your connected node.");
      
      getInvoiceButton.setOnClickListener(v -> {
        String vtext = amountInput.getText() != null ? amountInput.getText().toString().trim() : "";
        final long sats;
        try { sats = Long.parseLong(vtext); } catch (Throwable t) {
          Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
          return;
        }
        if (sats <= 0L) {
          Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
          return;
        }
        
        // Hide keyboard
        InputMethodManager imm = (InputMethodManager) 
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
          imm.hideSoftInputFromWindow(amountInput.getWindowToken(), 0);
        }
        
        View progressBar = view.findViewById(R.id.cashu_invoice_progress);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        
        // Get references to the payment status views
        View invoicePaidContainer = view.findViewById(R.id.invoice_paid_container);
        View invoiceWaitingProgress = view.findViewById(R.id.invoice_waiting_progress);
        TextView invoiceWaitingText = view.findViewById(R.id.invoice_waiting_text);
        
        // Use Lightning exclusively - no Cashu fallback
        new Thread(() -> {
          InvoiceResult invoiceResult = null;
          String errorText = null;
          try {
            Log.i(TAG, "Creating Lightning invoice for " + sats + " sats");
            invoiceResult = LightningUiInteractor.createInvoiceWithHashBlocking(
                AppDependencies.getApplication(), sats, "Signal payment");
            if (invoiceResult == null || invoiceResult.getPaymentRequest().isEmpty()) {
              errorText = "lightning_invoice_failed";
              Log.e(TAG, "Lightning invoice creation returned null");
            } else {
              Log.i(TAG, "Lightning invoice created successfully, paymentHash=" + invoiceResult.getPaymentHash().substring(0, Math.min(16, invoiceResult.getPaymentHash().length())) + "...");
            }
          } catch (Throwable t) {
            Log.e(TAG, "Lightning invoice creation error", t);
            errorText = t.getMessage();
          }
          
          final InvoiceResult finalInvoiceResult = invoiceResult;
          final String finalErrorText = errorText;
          
          requireActivity().runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            
            if (finalErrorText != null) {
              Toast.makeText(requireContext(), "Failed to create invoice: " + finalErrorText, Toast.LENGTH_LONG).show();
              return;
            }
            
            View amountContainer = getView().findViewById(R.id.cashu_amount_container);
            if (amountContainer != null) amountContainer.setVisibility(View.GONE);
            qrBorder.setVisibility(View.VISIBLE);
            TextView walletLabel = getView().findViewById(R.id.payments_add_money_your_wallet_address);
            if (walletLabel != null) walletLabel.setText("⚡ Lightning Invoice");
            walletAddressAbbreviated.setText(finalInvoiceResult.getPaymentRequest());
            qrImageView.setQrText(finalInvoiceResult.getPaymentRequest());
            info.setText("Pay this Lightning invoice to add funds to your wallet.");
            
            // Show waiting indicator
            if (invoiceWaitingProgress != null) invoiceWaitingProgress.setVisibility(View.VISIBLE);
            if (invoiceWaitingText != null) invoiceWaitingText.setVisibility(View.VISIBLE);
            
            // Start watching for payment
            LightningUiInteractor.watchInvoice(
                AppDependencies.getApplication(),
                finalInvoiceResult.getPaymentHash(),
                3L, // poll every 3 seconds
                300L, // for up to 5 minutes
                (paymentHash, amountSats) -> {
                  // onSuccess - payment received!
                  requireActivity().runOnUiThread(() -> {
                    Log.i(TAG, "Invoice PAID! paymentHash=" + paymentHash + ", amount=" + amountSats + " sats");
                    
                    // Hide waiting indicator
                    if (invoiceWaitingProgress != null) invoiceWaitingProgress.setVisibility(View.GONE);
                    if (invoiceWaitingText != null) invoiceWaitingText.setVisibility(View.GONE);
                    
                    // Show success checkmark
                    if (invoicePaidContainer != null) invoicePaidContainer.setVisibility(View.VISIBLE);
                    
                    // Update info text
                    info.setText("✓ Payment received! " + amountSats + " sats added to your wallet.");
                    
                    // Hide QR code after short delay
                    qrImageView.postDelayed(() -> {
                      qrImageView.setVisibility(View.GONE);
                      walletAddressAbbreviated.setVisibility(View.GONE);
                    }, 2000);
                    
                    // Notify that history may have changed
                    getParentFragmentManager().setFragmentResult("cashu_history_changed", new Bundle());
                    
                    Toast.makeText(requireContext(), "Payment received! ⚡", Toast.LENGTH_LONG).show();
                  });
                  return kotlin.Unit.INSTANCE;
                },
                (paymentHash) -> {
                  // onPending - still waiting
                  Log.d(TAG, "Invoice still pending: " + paymentHash);
                  return kotlin.Unit.INSTANCE;
                },
                (paymentHash) -> {
                  // onFailure - timeout or error
                  requireActivity().runOnUiThread(() -> {
                    Log.w(TAG, "Invoice watch ended without payment: " + paymentHash);
                    if (invoiceWaitingProgress != null) invoiceWaitingProgress.setVisibility(View.GONE);
                    if (invoiceWaitingText != null) invoiceWaitingText.setText("Invoice expired or timed out");
                  });
                  return kotlin.Unit.INSTANCE;
                }
            );
          });
        }).start();
      });
      
      copyAddress.setOnClickListener(v -> copyAddressToClipboard(walletAddressAbbreviated.getText().toString()));
      return;
    }
    
    // Cashu path (only when Lightning is NOT configured)
    if (SignalStore.payments().cashuEnabled()) {
      qrBorder.setVisibility(View.GONE);
      
      // Show "Connect Lightning Node" button since Lightning is not configured
      if (lightningConfigButton != null) {
        lightningConfigButton.setVisibility(View.VISIBLE);
        lightningConfigButton.setOnClickListener(v -> {
          Navigation.findNavController(v).navigate(R.id.action_paymentsAddMoney_to_lightningConfig);
        });
      }
      
      getInvoiceButton.setOnClickListener(v -> {
        String vtext = amountInput.getText() != null ? amountInput.getText().toString().trim() : "";
        final long sats;
        try { sats = Long.parseLong(vtext); } catch (Throwable t) {
          Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
          return;
        }
        if (sats <= 0L) {
          Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
          return;
        }
        
        InputMethodManager imm = (InputMethodManager) 
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
          imm.hideSoftInputFromWindow(amountInput.getWindowToken(), 0);
        }
        
        View progressBar = view.findViewById(R.id.cashu_invoice_progress);
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        
        // Cashu mint quote flow - uses WalletInteractor for routing
        new Thread(() -> {
          String text;
          try {
            // Use unified routing: tries Lightning first if available, falls back to Cashu
            text = CashuMintQuoteUiHelper.getReceiveInvoice(requireContext(), sats, "Signal payment");
            Log.i(TAG, "Receive invoice created: " + (text.startsWith("lnbc") ? "Lightning" : "Cashu"));
          } catch (Throwable t) {
            Log.e(TAG, "Failed to create receive invoice", t);
            text = "invoice:error";
          }
          final String qrText = text;
          requireActivity().runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            
            if (qrText.startsWith("invoice:error") || qrText.startsWith("invoice:unavailable")) {
              Toast.makeText(requireContext(), "Failed to create invoice", Toast.LENGTH_LONG).show();
              return;
            }
            
            View amountContainer = getView().findViewById(R.id.cashu_amount_container);
            if (amountContainer != null) amountContainer.setVisibility(View.GONE);
            qrBorder.setVisibility(View.VISIBLE);
            TextView walletLabel = getView().findViewById(R.id.payments_add_money_your_wallet_address);
            if (walletLabel != null) walletLabel.setText("Your lightning invoice");
            walletAddressAbbreviated.setText(qrText);
            qrImageView.setQrText(qrText);
            info.setText("To add funds, pay this lightning invoice.");
            getParentFragmentManager().setFragmentResult("cashu_history_changed", new Bundle());
          });
        }).start();
      });
      copyAddress.setOnClickListener(v -> copyAddressToClipboard(walletAddressAbbreviated.getText().toString()));
      MintWatcher.INSTANCE.start(requireContext());
      return;
    }

    // Legacy MOB path - create ViewModel only for MobileCoin
    PaymentsAddMoneyViewModel viewModel = new ViewModelProvider(this, new PaymentsAddMoneyViewModel.Factory()).get(PaymentsAddMoneyViewModel.class);
    
    viewModel.getSelfAddressAbbreviated().observe(getViewLifecycleOwner(), walletAddressAbbreviated::setText);
    viewModel.getSelfAddressB58().observe(getViewLifecycleOwner(), base58 -> copyAddress.setOnClickListener(v -> copyAddressToClipboard(base58)));
    // Note we are choosing to put Base58 directly into QR here
    viewModel.getSelfAddressB58().observe(getViewLifecycleOwner(), qrImageView::setQrText);

    viewModel.getErrors().observe(getViewLifecycleOwner(), error -> {
      switch (error) {
        case PAYMENTS_NOT_ENABLED: throw new AssertionError("Payments are not enabled");
        default                  : throw new AssertionError();
      }
    });
  }

  private void copyAddressToClipboard(@NonNull String text) {
    Context          context   = requireContext();
    ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text));
    Toast.makeText(context, R.string.PaymentsAddMoneyFragment__copied_to_clipboard, Toast.LENGTH_SHORT).show();
  }

  private void showAmountPromptAndMintQuote(@NonNull QrView qrImageView, @NonNull TextView display) {
    final EditText input = new EditText(requireContext());
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    input.setHint("Amount in sats");

    new MaterialAlertDialogBuilder(requireContext())
        .setTitle("Add funds")
        .setMessage("Enter amount in sats")
        .setView(input)
        .setPositiveButton(android.R.string.ok, (d, w) -> {
          String v = input.getText().toString().trim();
          final long sats;
          try {
            sats = Long.parseLong(v);
          } catch (Throwable ignore) {
            Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
            return;
          }
          if (sats <= 0L) {
            Toast.makeText(requireContext(), "Invalid amount", Toast.LENGTH_SHORT).show();
            return;
          }
          // Show immediate feedback while fetching invoice off main thread
          display.setText("Loading invoice...");
          qrImageView.setQrText("");
          // Use unified routing: tries Lightning first, falls back to Cashu
          new Thread(() -> {
            String text;
            try {
              text = CashuMintQuoteUiHelper.getReceiveInvoice(requireContext(), sats, null);
            } catch (Throwable t) {
              text = "invoice:error";
            }
            final String qrText = text;
            requireActivity().runOnUiThread(() -> {
              if (qrText.startsWith("invoice:")) {
                display.setText("Failed to create invoice");
                Toast.makeText(requireContext(), "Invoice creation failed", Toast.LENGTH_SHORT).show();
              } else {
                display.setText(qrText);
                qrImageView.setQrText(qrText);
                Toast.makeText(requireContext(), "Invoice ready", Toast.LENGTH_SHORT).show();
              }
            });
          }).start();
        })
        .setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss())
        .show();
  }
}
