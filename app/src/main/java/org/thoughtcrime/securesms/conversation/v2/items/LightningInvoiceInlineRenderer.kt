package org.thoughtcrime.securesms.conversation.v2.items

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.StreamUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.colors.Colorizer
import org.thoughtcrime.securesms.conversation.ui.payment.LightningInvoiceMessageView
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.hasTextSlide
import org.thoughtcrime.securesms.util.requireTextSlide

typealias LightningTextOnlyBinding = V2ConversationItemTextOnlyBindingBridge

/**
 * Inline renderer for Lightning invoices (BOLT11) in chat messages.
 * 
 * Detects Lightning invoice strings (lnbc..., lntb..., lnbcrt...) and renders
 * a custom payment request UI instead of the raw invoice text.
 * 
 * - Sender sees: "You requested ⚡ X sats" with no action button
 * - Receiver sees: "Name requested ⚡ X sats" with a "Pay Invoice" button
 */
object LightningInvoiceInlineRenderer {
  private val TAG = Log.tag(LightningInvoiceInlineRenderer::class.java)

  // BOLT11 invoice prefixes: lnbc (mainnet), lntb (testnet), lnbcrt (regtest)
  private val INVOICE_PATTERN = Regex("(lnbc[a-zA-Z0-9]+|lntb[a-zA-Z0-9]+|lnbcrt[a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)

  private data class InvoiceMeta(
    val amountSats: Long?,
    val description: String?
  )

  private data class PillBinding(
    val view: LightningInvoiceMessageView,
    val meta: InvoiceMeta,
    val outgoing: Boolean,
    val recipient: Recipient,
    val invoice: String,
    val messageId: Long
  )

  private fun removeExistingPill(parent: ViewGroup) {
    for (i in parent.childCount - 1 downTo 0) {
      if (parent.getChildAt(i) is LightningInvoiceMessageView) {
        parent.removeViewAt(i)
      }
    }
  }

  /**
   * Extract a Lightning invoice from the text.
   */
  private fun extractInvoice(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    val match = INVOICE_PATTERN.find(text)
    return match?.value
  }

  private fun extractInvoiceFromTextSlideIfPresent(context: Context, conversationMessage: ConversationMessage): String? {
    return try {
      val record = conversationMessage.messageRecord
      if (!record.isMms || !record.hasTextSlide()) return null
      val textSlideUri = record.requireTextSlide().uri ?: return null
      PartAuthority.getAttachmentStream(context, textSlideUri).use { input ->
        val fullText = StreamUtil.readFullyAsString(input)
        extractInvoice(fullText)
      }
    } catch (_: Throwable) {
      null
    }
  }

  /**
   * Decode invoice metadata (amount, description) from BOLT11.
   * This is a simplified parser - for full parsing, use a proper BOLT11 library.
   */
  private fun decodeInvoiceMeta(invoice: String): InvoiceMeta {
    return try {
      // BOLT11 amount is encoded after the prefix (lnbc/lntb/lnbcrt)
      // Format: ln{network}{amount}{multiplier}...
      // Example: lnbc1000n... = 1000 * 1 sat (n = nano-BTC = 100 sats... actually 1n = 0.001 sat)
      // Multiplier: m = milli (0.001 BTC), u = micro (0.000001 BTC), n = nano, p = pico
      
      val amountSats = parseAmountFromBolt11(invoice)
      InvoiceMeta(amountSats, null)
    } catch (e: Throwable) {
      Log.w(TAG, "Failed to decode invoice", e)
      InvoiceMeta(null, null)
    }
  }

  /**
   * Parse amount in satoshis from BOLT11 invoice.
   * 
   * BOLT11 format: ln{bc|tb|bcrt}{amount}{multiplier}{separator}...
   * Multipliers: m = milli-BTC, u = micro-BTC, n = nano-BTC, p = pico-BTC
   */
  private fun parseAmountFromBolt11(invoice: String): Long? {
    val lower = invoice.lowercase()
    
    // Find where the amount starts (after lnbc/lntb/lnbcrt)
    val prefixEnd = when {
      lower.startsWith("lnbcrt") -> 6
      lower.startsWith("lnbc") -> 4
      lower.startsWith("lntb") -> 4
      else -> return null
    }
    
    // Extract amount portion (digits followed by optional multiplier)
    val remaining = invoice.substring(prefixEnd)
    val amountMatch = Regex("^(\\d+)([munp])?").find(remaining) ?: return null
    
    val amountStr = amountMatch.groupValues[1]
    val multiplier = amountMatch.groupValues.getOrNull(2)?.firstOrNull()
    
    val amountNum = amountStr.toLongOrNull() ?: return null
    
    // Convert to satoshis based on multiplier
    // 1 BTC = 100,000,000 sats
    // m = milli = 0.001 BTC = 100,000 sats
    // u = micro = 0.000001 BTC = 100 sats
    // n = nano = 0.000000001 BTC = 0.1 sats
    // p = pico = 0.000000000001 BTC = 0.0001 sats
    return when (multiplier) {
      'm' -> amountNum * 100_000  // milli-BTC to sats
      'u' -> amountNum * 100      // micro-BTC to sats
      'n' -> amountNum / 10       // nano-BTC to sats (1000n = 100 sats)
      'p' -> amountNum / 10_000   // pico-BTC to sats
      null -> amountNum * 100_000_000  // Full BTC to sats (unlikely)
      else -> null
    }
  }

  fun resetIfPresent(binding: LightningTextOnlyBinding) {
    removeExistingPill(binding.bodyWrapper)
    binding.body.visibility = View.VISIBLE
  }

  fun maybeAttachInvoiceUi(binding: LightningTextOnlyBinding, conversationMessage: ConversationMessage): Boolean {
    val parent = binding.bodyWrapper
    removeExistingPill(parent)

    val invoice = findInvoice(binding.body.text?.toString(), parent.context, conversationMessage) ?: return false

    val pillBinding = createPill(parent.context, invoice, conversationMessage)
    configurePayAction(pillBinding)
    addPill(parent, binding.body, pillBinding.view)

    binding.body.text = ""
    binding.body.visibility = View.GONE
    return true
  }

  fun resetIfPresent(parent: ViewGroup) {
    removeExistingPill(parent)
  }

  fun maybeAttachInvoiceUi(parent: ViewGroup, body: TextView, conversationMessage: ConversationMessage): Boolean {
    removeExistingPill(parent)

    val invoice = findInvoice(body.text?.toString(), parent.context, conversationMessage) ?: return false

    val pillBinding = createPill(parent.context, invoice, conversationMessage)
    configurePayAction(pillBinding)
    addPill(parent, body, pillBinding.view)

    body.text = ""
    body.visibility = View.GONE
    return true
  }

  private fun findInvoice(visibleText: String?, context: Context, conversationMessage: ConversationMessage): String? {
    // Check TextSlide for long messages
    val attachmentInvoice = extractInvoiceFromTextSlideIfPresent(context, conversationMessage)
    if (attachmentInvoice != null) return attachmentInvoice
    
    // Fall back to body
    val bodyInvoice = extractInvoice(conversationMessage.messageRecord.body)
    val visibleInvoice = extractInvoice(visibleText)
    
    return bodyInvoice ?: visibleInvoice
  }

  private fun createPill(context: Context, invoice: String, conversationMessage: ConversationMessage): PillBinding {
    val meta = decodeInvoiceMeta(invoice)
    val outgoing = conversationMessage.messageRecord.isOutgoing
    val recipient: Recipient = if (outgoing) {
      conversationMessage.messageRecord.toRecipient
    } else {
      conversationMessage.messageRecord.fromRecipient
    }

    val direction = if (outgoing) {
      context.getString(R.string.LightningInvoice_you_requested)
    } else {
      context.getString(R.string.LightningInvoice_s_requested, recipient.getShortDisplayName(context))
    }

    val amountText = if (meta.amountSats != null && meta.amountSats > 0) {
      val formatter = java.text.NumberFormat.getInstance()
      formatter.maximumFractionDigits = 0
      context.getString(R.string.LightningInvoice_sats_format, formatter.format(meta.amountSats))
    } else {
      context.getString(R.string.LightningInvoice_invoice)
    }

    val pill = LightningInvoiceMessageView(context)
    val colorizer = Colorizer()
    pill.bind(direction, amountText, outgoing, recipient, colorizer)

    return PillBinding(pill, meta, outgoing, recipient, invoice, conversationMessage.messageRecord.id)
  }

  private fun configurePayAction(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pillBinding.invoice

    // Configure open external button for all cases (visible for incoming unpaid)
    configureOpenExternalAction(pillBinding)

    // For outgoing messages (sender), show refresh button to check status
    if (pillBinding.outgoing) {
      pill.setPayButtonVisible(false)
      pill.setRefreshButtonVisible(true)
      pill.setOpenExternalButtonVisible(false)
      configureRefreshAction(pillBinding)
      // Auto-check status when displayed
      checkInvoiceStatus(pillBinding)
      return
    }

    // For incoming messages (receiver/payer), check if already paid
    if (SignalStore.payments.isInvoicePaid(invoice)) {
      pill.setPayButtonVisible(false)
      pill.setRefreshButtonVisible(false)
      pill.setOpenExternalButtonVisible(false)
      pill.setStatusText(pill.context.getString(R.string.LightningInvoice_paid))
      return
    }

    // Show pay button and external button for unpaid incoming invoices
    pill.setPayButtonVisible(true)
    pill.setRefreshButtonVisible(false)
    pill.setPayButtonEnabled(true)
    pill.setOpenExternalButtonVisible(true)

    val payButton = pill.getPayButton()
    val payContainer = pill.getPayContainer()
    
    val clickListener = View.OnClickListener {
      pill.setPayButtonEnabled(false)
      pill.showSpinner(true)

      CoroutineScope(Dispatchers.Main).launch {
        val ctx = pill.context
        
        try {
          val result = withContext(Dispatchers.IO) {
            // First try Lightning direct payment if configured
            if (LightningUiInteractor.isConfigured(ctx)) {
              LightningUiInteractor.payInvoiceBlocking(ctx, invoice, null)
            } else {
              // Fall back to Cashu melt
              val quote = org.thoughtcrime.securesms.payments.engine.CashuUiInteractor.requestMeltQuoteBlocking(
                AppDependencies.application, invoice
              )
              if (quote != null) {
                val success = org.thoughtcrime.securesms.payments.engine.CashuUiInteractor.meltBlocking(
                  AppDependencies.application, quote
                )
                if (success) {
                  // Create a fake result for UI consistency
                  org.thoughtcrime.securesms.payments.engine.lightning.LightningPaymentResult(
                    paymentHash = quote.id ?: "",
                    preimage = "",
                    feeSats = quote.feeSats
                  )
                } else null
              } else null
            }
          }

          if (result != null) {
            // Persist paid status for UI consistency
            SignalStore.payments.setInvoicePaid(invoice)
            pill.showSpinner(false)
            pill.setPayButtonVisible(false)
            pill.setStatusText(ctx.getString(R.string.LightningInvoice_paid))
            Toast.makeText(ctx, R.string.LightningPayment__payment_successful, Toast.LENGTH_SHORT).show()
            
            // Notify database observer to refresh message in conversation list
            Log.i(TAG, "Payment successful, notifying message update for messageId: ${pillBinding.messageId}")
            AppDependencies.databaseObserver.notifyMessageUpdateObservers(MessageId(pillBinding.messageId))
          } else {
            pill.showSpinner(false)
            pill.setPayButtonEnabled(true)
            pill.setPayButtonVisible(true)
            pill.setStatusText(ctx.getString(R.string.LightningInvoice_payment_failed))
            Toast.makeText(ctx, R.string.PaymentsPayInvoice__unable_to_pay, Toast.LENGTH_SHORT).show()
          }
        } catch (e: Throwable) {
          Log.e(TAG, "Payment failed", e)
          pill.showSpinner(false)
          pill.setPayButtonEnabled(true)
          pill.setPayButtonVisible(true)
          pill.setStatusText(ctx.getString(R.string.LightningInvoice_payment_failed))
          Toast.makeText(ctx, R.string.PaymentsPayInvoice__unable_to_pay, Toast.LENGTH_SHORT).show()
        }
      }
    }
    
    payContainer.setOnClickListener(clickListener)
    payButton.setOnClickListener(clickListener)
  }

  private fun configureRefreshAction(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val refreshButton = pill.getRefreshButton()

    refreshButton.setOnClickListener {
      checkInvoiceStatus(pillBinding)
    }
  }

  private fun configureOpenExternalAction(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pillBinding.invoice
    val openExternalButton = pill.getOpenExternalButton()

    openExternalButton.setOnClickListener {
      val ctx = pill.context
      try {
        val lightningUri = Uri.parse("lightning:$invoice")
        val intent = Intent(Intent.ACTION_VIEW, lightningUri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to open lightning invoice in external app", e)
        Toast.makeText(ctx, R.string.ConversationActivity_there_is_no_app_available_to_handle_this_link_on_your_device, Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun checkInvoiceStatus(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pillBinding.invoice
    val ctx = pill.context

    // First check persisted state
    if (SignalStore.payments.isInvoicePaid(invoice)) {
      pill.setStatusText(ctx.getString(R.string.LightningInvoice_paid))
      pill.setRefreshButtonVisible(false)
      return
    }

    pill.showRefreshSpinner(true)

    CoroutineScope(Dispatchers.Main).launch {
      try {
        val isPaid = withContext(Dispatchers.IO) {
          if (LightningUiInteractor.isConfigured(ctx)) {
            LightningUiInteractor.isInvoicePaidBlocking(ctx, invoice)
          } else {
            // No way to check without Lightning node configured
            null
          }
        }

        pill.showRefreshSpinner(false)

        when (isPaid) {
          true -> {
            // Persist the paid status
            SignalStore.payments.setInvoicePaid(invoice)
            pill.setStatusText(ctx.getString(R.string.LightningInvoice_paid))
            pill.setRefreshButtonVisible(false)
            
            // Notify database observer to refresh message in conversation list
            Log.i(TAG, "Invoice paid, notifying message update for messageId: ${pillBinding.messageId}")
            AppDependencies.databaseObserver.notifyMessageUpdateObservers(MessageId(pillBinding.messageId))
          }
          false -> {
            pill.setStatusText(ctx.getString(R.string.LightningInvoice_pending))
          }
          null -> {
            // Could not determine status
            pill.setStatusVisible(false)
          }
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to check invoice status", e)
        pill.showRefreshSpinner(false)
        pill.setStatusVisible(false)
      }
    }
  }

  private fun addPill(parent: ViewGroup, anchor: View, pill: View) {
    val index = parent.indexOfChild(anchor)
    val baseLp = anchor.layoutParams
    val newLp = when (baseLp) {
      is ConstraintLayout.LayoutParams -> ConstraintLayout.LayoutParams(
        ConstraintLayout.LayoutParams.MATCH_PARENT,
        ConstraintLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        leftToLeft = baseLp.leftToLeft
        rightToRight = baseLp.rightToRight
        topToTop = baseLp.topToTop
        bottomToBottom = baseLp.bottomToBottom
        setMargins(baseLp.leftMargin, baseLp.topMargin, baseLp.rightMargin, baseLp.bottomMargin)
      }
      is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        setMargins(baseLp.leftMargin, baseLp.topMargin, baseLp.rightMargin, baseLp.bottomMargin)
      }
      else -> ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      )
    }
    parent.addView(pill, if (index >= 0) index else parent.childCount, newLp)
  }
}
