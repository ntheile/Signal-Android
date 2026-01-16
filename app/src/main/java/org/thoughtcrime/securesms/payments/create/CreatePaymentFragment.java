package org.thoughtcrime.securesms.payments.create;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.transition.TransitionManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.thoughtcrime.securesms.LoggingFragment;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.EmojiTextView;
import org.thoughtcrime.securesms.payments.CreatePaymentDetails;
import org.thoughtcrime.securesms.payments.MoneyView;
import org.thoughtcrime.securesms.payments.preferences.RecipientHasNotEnabledPaymentsDialog;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.PlayStoreUtil;
import org.thoughtcrime.securesms.util.SpanUtil;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.navigation.SafeNavigation;
import org.whispersystems.signalservice.api.payments.FormatterOptions;
import org.whispersystems.signalservice.api.payments.Money;

import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CreatePaymentFragment extends LoggingFragment {

  private static final Map<Integer,AmountKeyboardGlyph> ID_TO_GLYPH = new HashMap<Integer, AmountKeyboardGlyph>() {{
    put(R.id.create_payment_fragment_keyboard_decimal, AmountKeyboardGlyph.DECIMAL);
    put(R.id.create_payment_fragment_keyboard_lt, AmountKeyboardGlyph.BACK);
    put(R.id.create_payment_fragment_keyboard_0, AmountKeyboardGlyph.ZERO);
    put(R.id.create_payment_fragment_keyboard_1, AmountKeyboardGlyph.ONE);
    put(R.id.create_payment_fragment_keyboard_2, AmountKeyboardGlyph.TWO);
    put(R.id.create_payment_fragment_keyboard_3, AmountKeyboardGlyph.THREE);
    put(R.id.create_payment_fragment_keyboard_4, AmountKeyboardGlyph.FOUR);
    put(R.id.create_payment_fragment_keyboard_5, AmountKeyboardGlyph.FIVE);
    put(R.id.create_payment_fragment_keyboard_6, AmountKeyboardGlyph.SIX);
    put(R.id.create_payment_fragment_keyboard_7, AmountKeyboardGlyph.SEVEN);
    put(R.id.create_payment_fragment_keyboard_8, AmountKeyboardGlyph.EIGHT);
    put(R.id.create_payment_fragment_keyboard_9, AmountKeyboardGlyph.NINE);
  }};

  private ConstraintLayout constraintLayout;
  private TextView         balance;
  private MoneyView        amount;
  private TextView         exchange;
  private View             pay;
  private View             request;
  private EmojiTextView    note;
  private View             addNote;
  private View             toggle;
  private Drawable         infoIcon;
  private Drawable         spacer;
  private CreatePaymentViewModel viewModel;

  private ConstraintSet cryptoConstraintSet;
  private ConstraintSet fiatConstraintSet;

  // Local UI state for Cashu: which label is primary
  private boolean cashuFiatPrimary = false;
  private InputState lastInputState = null;

  public CreatePaymentFragment() {
    super(R.layout.create_payment_fragment);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    Toolbar toolbar = view.findViewById(R.id.create_payment_fragment_toolbar);

    toolbar.setNavigationOnClickListener(this::goBack);

    CreatePaymentFragmentArgs      arguments = CreatePaymentFragmentArgs.fromBundle(requireArguments());
    CreatePaymentViewModel.Factory factory   = new CreatePaymentViewModel.Factory(arguments.getPayee(), arguments.getNote());
    viewModel = new ViewModelProvider(Navigation.findNavController(view).getViewModelStoreOwner(R.id.payments_create), factory).get(CreatePaymentViewModel.class);

    constraintLayout = view.findViewById(R.id.create_payment_fragment_amount_header);
    request          = view.findViewById(R.id.create_payment_fragment_request);
    amount           = view.findViewById(R.id.create_payment_fragment_amount);
    exchange         = view.findViewById(R.id.create_payment_fragment_exchange);
    pay              = view.findViewById(R.id.create_payment_fragment_pay);
    balance          = view.findViewById(R.id.create_payment_fragment_balance);
    note             = view.findViewById(R.id.create_payment_fragment_note);
    addNote          = view.findViewById(R.id.create_payment_fragment_add_note);
    toggle           = view.findViewById(R.id.create_payment_fragment_toggle);

    TextView decimal = view.findViewById(R.id.create_payment_fragment_keyboard_decimal);
    decimal.setText(String.valueOf(DecimalFormatSymbols.getInstance().getDecimalSeparator()));

    View infoTapTarget = view.findViewById(R.id.create_payment_fragment_info_tap_region);

    //noinspection CodeBlock2Expr
    infoTapTarget.setOnClickListener(v -> {
      new MaterialAlertDialogBuilder(requireContext())
          .setMessage(R.string.CreatePaymentFragment__conversions_are_just_estimates)
          .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
          .setNegativeButton(R.string.LearnMoreTextView_learn_more, (dialog, which) -> {
            dialog.dismiss();
            CommunicationActions.openBrowserLink(requireContext(), getString(R.string.CreatePaymentFragment__learn_more__conversions));
          })
          .show();
         });

    initializeInfoIcon();

    note.setOnClickListener(v -> SafeNavigation.safeNavigate(Navigation.findNavController(v), R.id.action_createPaymentFragment_to_editPaymentNoteFragment));
    addNote.setOnClickListener(v -> SafeNavigation.safeNavigate(Navigation.findNavController(v), R.id.action_createPaymentFragment_to_editPaymentNoteFragment));

    pay.setOnClickListener(v -> {
      // Check if Lightning is configured - use sigmo: protocol flow
      if (org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor.isConfigured(requireContext())) {
        sendLightningSigmoRequest();
        return;
      }
      if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
        // In Cashu mode, we defer token creation and sending to the confirmation dialog.
      }
      NavDirections directions = CreatePaymentFragmentDirections.actionCreatePaymentFragmentToConfirmPaymentFragment(viewModel.getCreatePaymentDetails())
                                                                .setFinishOnConfirm(arguments.getFinishOnConfirm());
      SafeNavigation.safeNavigate(Navigation.findNavController(v), directions);
    });

    // Toggle swaps primary/secondary. In Cashu, we manage it locally.
    toggle.setOnClickListener(v -> {
      if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
        cashuFiatPrimary = !cashuFiatPrimary;
        if (lastInputState != null) renderCashu(lastInputState);
      } else {
        viewModel.toggleMoneyInputTarget();
      }
    });

    initializeConstraintSets();
    initializeKeyboardButtons(view, viewModel);

    viewModel.getInputState().observe(getViewLifecycleOwner(), inputState -> {
      if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
        lastInputState = inputState;
        renderCashu(inputState);
      } else {
        updateAmount(inputState);
        updateExchange(inputState);
        updateMoneyInputTarget(inputState.getInputTarget());
      }
    });

    viewModel.getIsPaymentsSupportedByPayee().observe(getViewLifecycleOwner(), isSupported -> {
      if (!isSupported) RecipientHasNotEnabledPaymentsDialog.show(requireContext(), () -> goBack(requireView()));
    });

    viewModel.isValidAmount().observe(getViewLifecycleOwner(), this::updateRequestAmountButtons);
    viewModel.getNote().observe(getViewLifecycleOwner(), this::updateNote);
    viewModel.getSpendableBalance().observe(getViewLifecycleOwner(), mob -> {
      // Check for Lightning first - it takes priority
      if (org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor.isConfigured(requireContext())) {
        // Fetch Lightning balance on background thread
        new Thread(() -> {
          try {
            org.thoughtcrime.securesms.payments.engine.lightning.LightningNodeInfo nodeInfo = 
                org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor.getNodeInfoBlocking(requireContext());
            if (nodeInfo != null && getView() != null) {
              long sendBalance = nodeInfo.getSendBalanceSats();
              requireActivity().runOnUiThread(() -> {
                this.balance.setText("Available: " + formatSats(sendBalance) + " sats");
              });
            }
          } catch (Throwable t) {
            org.signal.core.util.logging.Log.w("CreatePaymentFragment", "Failed to get Lightning balance", t);
          }
        }).start();
      } else if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
        // Use LiveData to avoid blocking main thread
        org.thoughtcrime.securesms.payments.engine.CashuUiRepository repo = new org.thoughtcrime.securesms.payments.engine.CashuUiRepository(requireContext().getApplicationContext());
        repo.getSpendableSatsLiveData().observe(getViewLifecycleOwner(), satsAvailable -> {
          this.balance.setText("Available: " + formatSats(satsAvailable) + " sat");
        });
      } else {
        this.updateBalance(mob);
      }
    });
    viewModel.getCanSendPayment().observe(getViewLifecycleOwner(), this::updatePayAmountButtons);
    viewModel.getEnclaveFailure().observe(getViewLifecycleOwner(), failure -> {
      if (failure) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.PaymentsHomeFragment__update_required))
            .setMessage(getString(R.string.PaymentsHomeFragment__an_update_is_required))
            .setPositiveButton(R.string.PaymentsHomeFragment__update_now, (dialog, which) -> { PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext()); })
            .setNegativeButton(R.string.PaymentsHomeFragment__cancel, (dialog, which) -> {})
            .setCancelable(false)
            .show();
      }
    });
  }

  private String formatSats(long sats) {
    java.text.NumberFormat nf = java.text.NumberFormat.getInstance(java.util.Locale.getDefault());
    nf.setGroupingUsed(true);
    nf.setMaximumFractionDigits(0);
    return nf.format(sats);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    constraintLayout = null;
    addNote          = null;
    balance          = null;
    amount           = null;
    exchange         = null;
    request          = null;
    toggle           = null;
    note             = null;
    pay              = null;
  }

  private void goBack(View v) {
    if (!Navigation.findNavController(v).popBackStack()) {
      requireActivity().finish();
    }
  }

  private void initializeInfoIcon() {
    spacer   = Objects.requireNonNull(AppCompatResources.getDrawable(requireContext(), R.drawable.payment_info_pad));
    infoIcon = Objects.requireNonNull(AppCompatResources.getDrawable(requireContext(), R.drawable.symbol_info_compact_16));

    DrawableCompat.setTint(infoIcon, exchange.getCurrentTextColor());

    spacer.setBounds(0, 0, ViewUtil.dpToPx(8), ViewUtil.dpToPx(16));
    infoIcon.setBounds(0, 0, ViewUtil.dpToPx(16), ViewUtil.dpToPx(16));
  }

  private void updateNote(@Nullable CharSequence note) {
    boolean hasNote = !TextUtils.isEmpty(note);
    addNote.setVisibility(hasNote ? View.GONE : View.VISIBLE);
    this.note.setVisibility(hasNote ? View.VISIBLE : View.GONE);
    this.note.setText(note);
  }

  private void initializeKeyboardButtons(@NonNull View view, @NonNull CreatePaymentViewModel viewModel) {
    for (Map.Entry<Integer, AmountKeyboardGlyph> entry : ID_TO_GLYPH.entrySet()) {
      view.findViewById(entry.getKey()).setOnClickListener(v -> viewModel.updateAmount(requireContext(), entry.getValue()));
    }

    view.findViewById(R.id.create_payment_fragment_keyboard_lt).setOnLongClickListener(v -> {
      viewModel.clearAmount();
      return true;
    });
  }

  private void updateAmount(@NonNull InputState inputState) {
    switch (inputState.getInputTarget()) {
      case MONEY:
        if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
          long sats = org.thoughtcrime.securesms.payments.create.CashuAmountAccessor.getAmountSats(inputState.getMoneyAmount());
          amount.setText(formatSats(sats) + " sat");
        } else {
          amount.setMoney(inputState.getMoneyAmount(), inputState.getMoney().getCurrency());
        }
        break;
      case FIAT_MONEY:
        if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
          // In fiat layout, amount is the small label; it should show sats.
          long sats = org.thoughtcrime.securesms.payments.create.CashuAmountAccessor.getAmountSats(inputState.getMoneyAmount());
          amount.setText(formatSats(sats) + " sat");
        } else {
          amount.setMoney(inputState.getMoney(), false, inputState.getExchangeRate().get().getTimestamp());
          amount.append(SpanUtil.buildImageSpan(spacer));
          amount.append(SpanUtil.buildImageSpan(infoIcon));
        }
        break;
    }
  }

  private void updateExchange(@NonNull InputState inputState) {
    switch (inputState.getInputTarget()) {
      case MONEY:
        if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
          long sats = org.thoughtcrime.securesms.payments.create.CashuAmountAccessor.getAmountSats(inputState.getMoneyAmount());
          // Use cached value to avoid blocking - LiveData will update asynchronously
          org.thoughtcrime.securesms.payments.engine.CashuUiRepository repo = new org.thoughtcrime.securesms.payments.engine.CashuUiRepository(requireContext().getApplicationContext());
          String fiatText = repo.satsToFiatStringCached(sats);
          // Trigger async update in background
          repo.satsToFiatStringLiveData(sats).observe(getViewLifecycleOwner(), updatedFiat -> {
            exchange.setText(updatedFiat);
            exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
            exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));
          });
          exchange.setVisibility(View.VISIBLE);
          exchange.setText(fiatText);
          exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
          exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));
          toggle.setVisibility(View.VISIBLE);
          toggle.setEnabled(true);
        } else {
          if (inputState.getFiatMoney().isPresent()) {
            exchange.setVisibility(View.VISIBLE);
            exchange.setText(org.thoughtcrime.securesms.payments.FiatMoneyUtil.format(getResources(), inputState.getFiatMoney().get(), org.thoughtcrime.securesms.payments.FiatMoneyUtil.formatOptions().withDisplayTime(true)));
            exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
            exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));
            toggle.setVisibility(View.VISIBLE);
            toggle.setEnabled(true);
          } else {
            exchange.setVisibility(View.INVISIBLE);
            toggle.setVisibility(View.INVISIBLE);
            toggle.setEnabled(false);
          }
        }
        break;
      case FIAT_MONEY:
        if (org.thoughtcrime.securesms.keyvalue.SignalStore.payments().cashuEnabled()) {
          long sats = org.thoughtcrime.securesms.payments.create.CashuAmountAccessor.getAmountSats(inputState.getMoneyAmount());
          exchange.setVisibility(View.VISIBLE);
          exchange.setText(formatSats(sats) + " sat");
          toggle.setVisibility(View.VISIBLE);
          toggle.setEnabled(true);
        } else {
          java.util.Currency currency = inputState.getFiatMoney().get().getCurrency();
          exchange.setText(org.thoughtcrime.securesms.payments.FiatMoneyUtil.manualFormat(currency, inputState.getFiatAmount()));
        }
        break;
    }
  }

  private void updateRequestAmountButtons(boolean isValidAmount) {
    request.setEnabled(isValidAmount);
  }

  private void updatePayAmountButtons(boolean isValidAmount) {
    pay.setEnabled(isValidAmount);
  }

  private void updateBalance(@NonNull Money balance) {
    this.balance.setText(getString(R.string.CreatePaymentFragment__available_balance_s, balance.toString(FormatterOptions.defaults())));
  }

  private void initializeConstraintSets() {
    cryptoConstraintSet = new ConstraintSet();
    cryptoConstraintSet.clone(constraintLayout);

    fiatConstraintSet = new ConstraintSet();
    fiatConstraintSet.clone(getContext(), R.layout.create_payment_fragment_amount_toggle);
  }

  private void renderCashu(@NonNull InputState inputState) {
    long sats = org.thoughtcrime.securesms.payments.create.CashuAmountAccessor.getAmountSats(inputState.getMoneyAmount());
    org.thoughtcrime.securesms.payments.engine.CashuUiRepository repo = new org.thoughtcrime.securesms.payments.engine.CashuUiRepository(requireContext().getApplicationContext());
    String fiatText = repo.satsToFiatStringCached(sats);
    
    // Trigger async update in background
    repo.satsToFiatStringLiveData(sats).observe(getViewLifecycleOwner(), updatedFiat -> {
      // Update the exchange text when fiat value is fetched
      if (cashuFiatPrimary) {
        exchange.setText(updatedFiat);
        exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
        exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));
      } else {
        exchange.setText(updatedFiat);
        exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
        exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));
      }
    });
    
    // cashuFiatPrimary determines which label (amount/exchange) is large vs small
    if (cashuFiatPrimary) {
      // Primary is fiat, secondary is sats
      fiatConstraintSet.applyTo(constraintLayout);
      amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_secondary));
      exchange.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_primary));

      // Large label (exchange) shows fiat with info icon
      exchange.setVisibility(View.VISIBLE);
      exchange.setText(fiatText);
      exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
      exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));

      // Small label (amount) shows sats
      amount.setText(formatSats(sats) + " sat");

    } else {
      // Primary is sats, secondary is fiat
      cryptoConstraintSet.applyTo(constraintLayout);
      exchange.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_secondary));
      amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_primary));

      // Large label (amount) shows sats
      amount.setText(formatSats(sats) + " sat");

      // Small label (exchange) shows fiat with info icon
      exchange.setVisibility(View.VISIBLE);
      exchange.setText(fiatText);
      exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(spacer));
      exchange.append(org.thoughtcrime.securesms.util.SpanUtil.buildImageSpan(infoIcon));
    }

    // Ensure toggle is visible/enabled
    toggle.setVisibility(View.VISIBLE);
    toggle.setEnabled(true);
  }

  private void updateMoneyInputTarget(@NonNull InputTarget target) {
    TransitionManager.endTransitions(constraintLayout);
    TransitionManager.beginDelayedTransition(constraintLayout);

    switch (target) {
      case FIAT_MONEY:
        fiatConstraintSet.applyTo(constraintLayout);
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_secondary));
        exchange.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_primary));
        break;
      case MONEY:
        cryptoConstraintSet.applyTo(constraintLayout);
        exchange.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_secondary));
        amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_primary));
        break;
    }
  }

  /**
   * Send a sigmo: protocol message to request a Lightning invoice from the recipient.
   * This is used when Lightning is configured - it sends a message requesting an invoice,
   * and the recipient's client will auto-generate and send back the invoice for payment.
   */
  private void sendLightningSigmoRequest() {
    CreatePaymentDetails details = viewModel.getCreatePaymentDetails();
    org.thoughtcrime.securesms.payments.Payee payee = details.getPayee();
    
    if (!payee.hasRecipientId()) {
      android.widget.Toast.makeText(requireContext(), R.string.CreatePaymentFragment__invalid_recipient, android.widget.Toast.LENGTH_SHORT).show();
      return;
    }

    // Get the amount in sats
    long amountSats;
    try {
      amountSats = CashuAmountAccessor.getAmountSats(viewModel.getCurrentMoneyAmountForCashu());
    } catch (Exception e) {
      android.widget.Toast.makeText(requireContext(), R.string.CreatePaymentFragment__invalid_amount, android.widget.Toast.LENGTH_SHORT).show();
      return;
    }

    if (amountSats <= 0) {
      android.widget.Toast.makeText(requireContext(), R.string.CreatePaymentFragment__invalid_amount, android.widget.Toast.LENGTH_SHORT).show();
      return;
    }

    org.thoughtcrime.securesms.recipients.Recipient recipient = org.thoughtcrime.securesms.recipients.Recipient.resolved(payee.requireRecipientId());
    
    // Convert sats to millisats (1 sat = 1000 msats)
    long amountMsats = amountSats * 1000;
    
    // Show confirmation and send the sigmo request
    new MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.CreatePaymentFragment__send_lightning_payment)
      .setMessage(getString(R.string.CreatePaymentFragment__send_lightning_request_message, formatSats(amountSats), recipient.getShortDisplayName(requireContext())))
      .setPositiveButton(R.string.CreatePaymentFragment__send_request, (dialog, which) -> {
        sendSigmoRequest(recipient, amountMsats);
      })
      .setNegativeButton(android.R.string.cancel, null)
      .show();
  }

  /**
   * Actually send the sigmo: protocol message to the recipient.
   */
  private void sendSigmoRequest(org.thoughtcrime.securesms.recipients.Recipient recipient, long amountMsats) {
    new Thread(() -> {
      try {
        String username = recipient.getDisplayName(requireContext());
        // Generate a unique request ID for tracking this payment flow
        String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
        // Build the sigmo: URI with request_id for correlation
        String sigmoUri = "sigmo:lnurlp/" + username + "/callback?amount=" + amountMsats + "&request_id=" + requestId;

        // Get or create thread for recipient
        long threadId = org.thoughtcrime.securesms.database.SignalDatabase.threads().getOrCreateThreadIdFor(recipient);

        // Send the sigmo URI as a message
        org.thoughtcrime.securesms.mms.OutgoingMessage outgoingMessage = new org.thoughtcrime.securesms.mms.OutgoingMessage(
          recipient,                       // recipient
          sigmoUri,                        // body
          java.util.Collections.emptyList(), // attachments
          System.currentTimeMillis(),      // timestamp
          0L,                              // expiresIn
          1,                               // expireTimerVersion
          false,                           // viewOnce
          org.thoughtcrime.securesms.database.ThreadTable.DistributionTypes.DEFAULT, // distributionType
          org.thoughtcrime.securesms.database.model.StoryType.NONE, // storyType
          null,                            // parentStoryId
          false,                           // isStoryReaction
          null,                            // quote
          java.util.Collections.emptyList(), // contacts
          java.util.Collections.emptyList(), // previews
          java.util.Collections.emptyList(), // mentions
          java.util.Collections.emptySet(),  // networkFailures
          java.util.Collections.emptySet(),  // mismatches
          null,                            // giftBadge
          true,                            // isSecure
          null,                            // bodyRanges
          -1L,                             // scheduledDate
          0L                               // messageToEdit
        );

        long messageId = org.thoughtcrime.securesms.sms.MessageSender.send(
          org.thoughtcrime.securesms.dependencies.AppDependencies.getApplication(),
          outgoingMessage,
          threadId,
          org.thoughtcrime.securesms.sms.MessageSender.SendType.SIGNAL,
          null,
          null
        );

        // Store the pending payment request for tracking
        org.thoughtcrime.securesms.keyvalue.SignalStore.payments().storeSigmoRequest(
          requestId,
          recipient.getId().serialize(),
          amountMsats,
          threadId,
          messageId
        );

        requireActivity().runOnUiThread(() -> {
          android.widget.Toast.makeText(
            requireContext(),
            getString(R.string.CreatePaymentFragment__invoice_request_sent, recipient.getShortDisplayName(requireContext())),
            android.widget.Toast.LENGTH_SHORT
          ).show();
          // Navigate back to payments home
          androidx.navigation.Navigation.findNavController(requireView()).popBackStack(R.id.paymentsHome, false);
        });
      } catch (Throwable e) {
        org.signal.core.util.logging.Log.w("CreatePaymentFragment", "Failed to send sigmo request", e);
        requireActivity().runOnUiThread(() -> {
          android.widget.Toast.makeText(requireContext(), R.string.CreatePaymentFragment__failed_to_send_request, android.widget.Toast.LENGTH_SHORT).show();
        });
      }
    }).start();
  }
}
