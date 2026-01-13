package org.thoughtcrime.securesms.conversation.ui.payment

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.quotes.QuoteViewColorTheme
import org.thoughtcrime.securesms.conversation.colors.Colorizer
import org.thoughtcrime.securesms.databinding.LightningInvoiceMessageViewBinding
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.visible

/**
 * Custom view for displaying Lightning invoices inline in chat messages.
 * Shows a payment request UI with amount and a pay button for receivers,
 * or a "you requested" UI for senders.
 */
class LightningInvoiceMessageView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

  private val binding: LightningInvoiceMessageViewBinding = 
    LightningInvoiceMessageViewBinding.inflate(LayoutInflater.from(context), this, true)

  /**
   * Bind the view with invoice data.
   * 
   * @param directionText Text like "You requested" or "Alex requested"
   * @param amountText Formatted amount like "⚡ 1,000 sats"
   * @param outgoing Whether this is an outgoing message (sender side)
   * @param recipient The recipient for color theming
   * @param colorizer Colorizer for proper theming
   */
  fun bind(
    directionText: String,
    amountText: String,
    outgoing: Boolean,
    recipient: Recipient,
    colorizer: Colorizer
  ) {
    binding.lightningInvoiceDirection.apply {
      text = directionText
      setTextColor(
        if (outgoing) colorizer.getOutgoingFooterTextColor(context)
        else colorizer.getIncomingFooterTextColor(context, recipient.hasWallpaper)
      )
    }

    val theme = QuoteViewColorTheme.resolveTheme(outgoing, false, recipient.hasWallpaper)
    ViewCompat.setBackgroundTintList(
      binding.lightningInvoiceAmountLayout,
      ColorStateList.valueOf(theme.getBackgroundColor(context))
    )

    binding.lightningInvoiceAmount.text = amountText
    binding.lightningInvoiceAmount.setTextColor(theme.getForegroundColor(context))
    binding.lightningInvoiceIcon.imageTintList = ColorStateList.valueOf(theme.getForegroundColor(context))

    showProgress(false)
    showSpinner(false)
    setStatusVisible(false)
    
    // Show pay button only for incoming (receiver sees it)
    setPayButtonVisible(!outgoing)
    setPayButtonEnabled(true)
  }

  fun showProgress(show: Boolean) {
    binding.lightningInvoiceAmount.visible = !show
    binding.lightningInvoiceIcon.visible = !show
    binding.lightningInvoiceInprogress.visible = show
    if (show) {
      binding.lightningInvoiceInprogress.setImageDrawable(
        getInProgressDrawable(binding.lightningInvoiceAmount.currentTextColor)
      )
    } else {
      binding.lightningInvoiceInprogress.setImageDrawable(null)
    }
  }

  fun showSpinner(show: Boolean) {
    binding.lightningInvoiceSpinner.visible = show
    binding.lightningInvoicePayButton.visible = !show
  }

  fun setPayButtonEnabled(enabled: Boolean) {
    binding.lightningInvoiceActionContainer.isEnabled = enabled
    binding.lightningInvoicePayButton.isEnabled = enabled
  }

  fun setPayButtonVisible(visible: Boolean) {
    binding.lightningInvoiceActionContainer.visible = visible
    binding.lightningInvoiceActionContainer.isEnabled = visible
    binding.lightningInvoicePayButton.isEnabled = visible
    if (!visible) {
      binding.lightningInvoiceSpinner.visible = false
      binding.lightningInvoicePayButton.visible = false
    } else {
      val spinnerVisible = binding.lightningInvoiceSpinner.visibility == View.VISIBLE
      binding.lightningInvoicePayButton.visible = !spinnerVisible
    }
  }

  fun setStatusVisible(visible: Boolean) {
    binding.lightningInvoiceStatus.visible = visible
  }

  fun setStatusText(text: String) {
    binding.lightningInvoiceStatus.text = text
    binding.lightningInvoiceStatus.visible = true
  }

  fun updateAmountText(amountText: String) {
    binding.lightningInvoiceAmount.text = amountText
  }

  fun getPayButton(): View = binding.lightningInvoicePayButton

  fun getPayContainer(): View = binding.lightningInvoiceActionContainer

  fun getRefreshButton(): View = binding.lightningInvoiceRefreshButton

  fun getOpenExternalButton(): View = binding.lightningInvoiceOpenExternalButton

  fun setOpenExternalButtonVisible(visible: Boolean) {
    binding.lightningInvoiceOpenExternalButton.visible = visible
  }

  fun setRefreshButtonVisible(visible: Boolean) {
    binding.lightningInvoiceRefreshButton.visible = visible
  }

  fun showRefreshSpinner(show: Boolean) {
    binding.lightningInvoiceRefreshButton.isEnabled = !show
    if (show) {
      setStatusText(context.getString(R.string.LightningInvoice_checking))
    }
  }

  private fun getInProgressDrawable(color: Int): IndeterminateDrawable<CircularProgressIndicatorSpec> {
    val spec = CircularProgressIndicatorSpec(context, null).apply {
      indicatorSize = 20.dp
      indicatorInset = 0
      trackThickness = 2.dp
      indicatorColors = intArrayOf(color)
    }
    return IndeterminateDrawable.createCircularDrawable(context, spec)
  }
}
