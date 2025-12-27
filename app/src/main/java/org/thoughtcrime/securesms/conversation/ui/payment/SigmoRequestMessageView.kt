package org.thoughtcrime.securesms.conversation.ui.payment

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.quotes.QuoteViewColorTheme
import org.thoughtcrime.securesms.conversation.colors.Colorizer
import org.thoughtcrime.securesms.databinding.SigmoRequestMessageViewBinding
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.visible

/**
 * Custom view for displaying sigmo: protocol payment requests inline in chat messages.
 * Shows a "Send ₿" request UI with the requested amount.
 */
class SigmoRequestMessageView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  private val binding: SigmoRequestMessageViewBinding = 
    SigmoRequestMessageViewBinding.inflate(LayoutInflater.from(context), this, true)
  
  private var invoice: String? = null
  private var onPayClickListener: ((String) -> Unit)? = null
  private var paymentInProgress: Boolean = false

  /**
   * Bind the view with sigmo request data.
   * 
   * @param directionText Text like "You requested" or "Alex wants to send you"
   * @param amountText Formatted amount like "⚡ 1,000 sats"
   * @param outgoing Whether this is an outgoing message (sender side)
   * @param recipient The recipient for color theming
   * @param colorizer Colorizer for proper theming
   * @param hideTitle Whether to hide the title text (for sigmo invoice flow)
   */
  fun bind(
    directionText: String,
    amountText: String,
    outgoing: Boolean,
    recipient: Recipient,
    colorizer: Colorizer,
    hideTitle: Boolean = false
  ) {
    binding.sigmoRequestDirection.apply {
      text = directionText
      setTextColor(
        if (outgoing) colorizer.getOutgoingFooterTextColor(context)
        else colorizer.getIncomingFooterTextColor(context, recipient.hasWallpaper)
      )
    }

    val theme = QuoteViewColorTheme.resolveTheme(outgoing, false, recipient.hasWallpaper)
    ViewCompat.setBackgroundTintList(
      binding.sigmoRequestContentLayout,
      ColorStateList.valueOf(theme.getBackgroundColor(context))
    )

    binding.sigmoRequestTitle.text = if (hideTitle) {
      ""
    } else if (outgoing) {
      context.getString(R.string.SigmoRequest_you_want_to_send)
    } else {
      context.getString(R.string.SigmoRequest_wants_to_send_you)
    }
    binding.sigmoRequestTitle.visible = !hideTitle
    binding.sigmoRequestTitle.setTextColor(theme.getForegroundColor(context))
    
    binding.sigmoRequestAmount.text = amountText
    binding.sigmoRequestAmount.setTextColor(theme.getForegroundColor(context))
    
    binding.sigmoRequestIcon.imageTintList = ColorStateList.valueOf(0xFFFF9500.toInt()) // Bitcoin orange

    setStatusVisible(false)
  }

  /**
   * Update the direction text (top line) dynamically.
   */
  fun setDirectionText(text: String) {
    binding.sigmoRequestDirection.text = text
  }

  fun setStatusVisible(visible: Boolean) {
    binding.sigmoRequestStatus.visible = visible
    binding.sigmoRequestStatusContainer.visible = visible
  }

  fun setStatusText(text: String) {
    binding.sigmoRequestStatus.text = text
    binding.sigmoRequestStatus.visible = true
    binding.sigmoRequestStatusContainer.visible = true
  }
  
  /**
   * Show/hide the refresh button for checking invoice status.
   */
  fun setRefreshButtonVisible(visible: Boolean) {
    binding.sigmoRequestRefresh.visible = visible
    binding.sigmoRequestStatusContainer.visible = visible || binding.sigmoRequestStatus.visible
  }
  
  /**
   * Set the click listener for the refresh button.
   */
  fun setOnRefreshClickListener(listener: () -> Unit) {
    binding.sigmoRequestRefresh.setOnClickListener { listener() }
  }
  
  /**
   * Show/hide the spinner for loading state.
   */
  fun showSpinner(show: Boolean) {
    binding.sigmoRequestSpinner.visible = show
  }
  
  /**
   * Set the invoice for this view. When set, clicking the view will trigger payment.
   */
  fun setInvoice(invoice: String) {
    this.invoice = invoice
  }
  
  /**
   * Set listener for when the pay action is triggered.
   */
  fun setOnPayClickListener(listener: (String) -> Unit) {
    this.onPayClickListener = listener
  }
  
  /**
   * Get the stored invoice.
   */
  fun getInvoice(): String? = invoice
  
  /**
   * Show the "Tap to Pay" button.
   */
  fun setPayButtonVisible(visible: Boolean) {
    binding.sigmoRequestPayButton.visible = visible
    binding.sigmoRequestStatusContainer.visible = visible || binding.sigmoRequestStatus.visible || binding.sigmoRequestRefresh.visible
  }
  
  /**
   * Configure the pay button with a click handler.
   * Prevents double-tap by disabling the button after first click.
   */
  fun configurePayButton(onPay: (String) -> Unit) {
    binding.sigmoRequestPayButton.setOnClickListener {
      if (paymentInProgress) return@setOnClickListener
      
      invoice?.let { inv ->
        paymentInProgress = true
        binding.sigmoRequestPayButton.isEnabled = false
        binding.sigmoRequestPayButton.text = context.getString(R.string.LightningInvoice_paying)
        onPay(inv)
      }
    }
  }
  
  /**
   * Update pay button state after payment attempt.
   */
  fun setPayButtonState(success: Boolean) {
    if (success) {
      binding.sigmoRequestPayButton.visible = false
      paymentInProgress = false
    } else {
      // Re-enable on failure
      paymentInProgress = false
      binding.sigmoRequestPayButton.isEnabled = true
      binding.sigmoRequestPayButton.text = context.getString(R.string.SigmoRequest_tap_to_pay)
    }
  }
  
  /**
   * Check if payment is currently in progress.
   */
  fun isPaymentInProgress(): Boolean = paymentInProgress
}
