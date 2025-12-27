package org.thoughtcrime.securesms.conversation.v2.items

import android.content.Context
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
import org.thoughtcrime.securesms.conversation.ui.payment.SigmoRequestMessageView
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.keyvalue.PaymentsValues.SigmoFlowStatus
import org.thoughtcrime.securesms.messages.SigmoProtocolProcessor
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.hasTextSlide
import org.thoughtcrime.securesms.util.requireTextSlide

/**
 * Inline renderer for sigmo: protocol messages in chat.
 * 
 * Detects sigmo: URI strings and renders a custom payment request UI
 * instead of the raw URI text. Shows a consolidated view of the payment flow.
 * 
 * sigmo: protocol formats:
 * - Request: sigmo:lnurlp/{username}/callback?amount={amount_in_msats}&request_id={uuid}
 * - Response: sigmo:{invoice}&request_id={uuid}
 */
object SigmoRequestInlineRenderer {
  private val TAG = Log.tag(SigmoRequestInlineRenderer::class.java)

  private const val SIGMO_PREFIX = "sigmo:"

  enum class SigmoMessageType {
    REQUEST,   // Invoice request
    INVOICE    // Invoice response
  }

  private data class PillBinding(
    val view: SigmoRequestMessageView,
    val messageType: SigmoMessageType,
    val outgoing: Boolean,
    val recipient: Recipient,
    val requestId: String?
  )

  private fun removeExistingPill(parent: ViewGroup) {
    for (i in parent.childCount - 1 downTo 0) {
      if (parent.getChildAt(i) is SigmoRequestMessageView) {
        parent.removeViewAt(i)
      }
    }
  }

  /**
   * Extract a sigmo: URI from the text.
   */
  private fun extractSigmoUri(text: String?): String? {
    if (text.isNullOrEmpty()) return null
    if (!text.startsWith(SIGMO_PREFIX, ignoreCase = true)) return null
    // Return the full sigmo: line
    return text.lines().firstOrNull { it.startsWith(SIGMO_PREFIX, ignoreCase = true) }
  }

  private fun extractSigmoUriFromTextSlideIfPresent(context: Context, conversationMessage: ConversationMessage): String? {
    return try {
      val record = conversationMessage.messageRecord
      if (!record.isMms || !record.hasTextSlide()) return null
      val textSlideUri = record.requireTextSlide().uri ?: return null
      PartAuthority.getAttachmentStream(context, textSlideUri).use { input ->
        val fullText = StreamUtil.readFullyAsString(input)
        extractSigmoUri(fullText)
      }
    } catch (_: Throwable) {
      null
    }
  }

  fun resetIfPresent(binding: LightningTextOnlyBinding) {
    removeExistingPill(binding.bodyWrapper)
  }

  fun maybeAttachSigmoUi(binding: LightningTextOnlyBinding, conversationMessage: ConversationMessage): Boolean {
    val parent = binding.bodyWrapper
    removeExistingPill(parent)

    val sigmoUri = findSigmoUri(binding.body.text?.toString(), parent.context, conversationMessage) ?: return false
    
    // Check if this is an outgoing request that has a corresponding invoice response
    // If so, hide this message (we'll show the consolidated invoice instead)
    val parsedRequest = SigmoProtocolProcessor.parseSigmoUri(sigmoUri)
    if (parsedRequest != null && parsedRequest.requestId != null) {
      if (conversationMessage.messageRecord.isOutgoing) {
        // Outgoing request - hide if invoice has been received
        val flow = SignalStore.payments.getSigmoFlow(parsedRequest.requestId)
        if (flow?.status == SigmoFlowStatus.INVOICE_RECEIVED || flow?.status == SigmoFlowStatus.PAID) {
          binding.body.text = ""
          binding.body.visibility = View.GONE
          return true
        }
      } else {
        // Incoming request - hide if we've already responded with an invoice
        if (SignalStore.payments.hasSigmoRequestBeenResponded(parsedRequest.requestId)) {
          binding.body.text = ""
          binding.body.visibility = View.GONE
          return true
        }
      }
    }

    val pillBinding = createPill(parent.context, sigmoUri, conversationMessage)
    if (pillBinding == null) {
      // Could be a request we've already responded to - hide it
      binding.body.text = ""
      binding.body.visibility = View.GONE
      return true
    }
    
    // Configure actions based on message type
    if (pillBinding.messageType == SigmoMessageType.INVOICE) {
      if (pillBinding.outgoing) {
        // Outgoing invoice - configure refresh to check payment status
        configureRefreshAction(pillBinding)
        // Auto-check status when displayed
        checkInvoiceStatus(pillBinding)
      } else {
        // Incoming invoice - configure pay action only if not already paid
        val invoice = pillBinding.view.getInvoice()
        if (invoice != null && !SignalStore.payments.isInvoicePaid(invoice)) {
          configurePayAction(pillBinding)
        }
      }
    }
    
    addPill(parent, binding.body, pillBinding.view)

    binding.body.text = ""
    binding.body.visibility = View.GONE
    return true
  }

  fun resetIfPresent(parent: ViewGroup) {
    removeExistingPill(parent)
  }

  fun maybeAttachSigmoUi(parent: ViewGroup, body: TextView, conversationMessage: ConversationMessage): Boolean {
    removeExistingPill(parent)

    val sigmoUri = findSigmoUri(body.text?.toString(), parent.context, conversationMessage) ?: return false
    
    // Check if this is a request that should be hidden
    val parsedRequest = SigmoProtocolProcessor.parseSigmoUri(sigmoUri)
    if (parsedRequest != null && parsedRequest.requestId != null) {
      if (conversationMessage.messageRecord.isOutgoing) {
        // Outgoing request - hide if invoice has been received
        val flow = SignalStore.payments.getSigmoFlow(parsedRequest.requestId)
        if (flow?.status == SigmoFlowStatus.INVOICE_RECEIVED || flow?.status == SigmoFlowStatus.PAID) {
          body.text = ""
          body.visibility = View.GONE
          return true
        }
      } else {
        // Incoming request - hide if we've already responded with an invoice
        if (SignalStore.payments.hasSigmoRequestBeenResponded(parsedRequest.requestId)) {
          body.text = ""
          body.visibility = View.GONE
          return true
        }
      }
    }

    val pillBinding = createPill(parent.context, sigmoUri, conversationMessage)
    if (pillBinding == null) {
      // Could be a request we've already responded to - hide it
      body.text = ""
      body.visibility = View.GONE
      return true
    }
    
    // Configure actions based on message type
    if (pillBinding.messageType == SigmoMessageType.INVOICE) {
      if (pillBinding.outgoing) {
        // Outgoing invoice - configure refresh to check payment status
        configureRefreshAction(pillBinding)
        // Auto-check status when displayed
        checkInvoiceStatus(pillBinding)
      } else {
        // Incoming invoice - configure pay action only if not already paid
        val invoice = pillBinding.view.getInvoice()
        if (invoice != null && !SignalStore.payments.isInvoicePaid(invoice)) {
          configurePayAction(pillBinding)
        }
      }
    }
    
    addPill(parent, body, pillBinding.view)

    body.text = ""
    body.visibility = View.GONE
    return true
  }

  private fun findSigmoUri(visibleText: String?, context: Context, conversationMessage: ConversationMessage): String? {
    // Check TextSlide for long messages
    val attachmentUri = extractSigmoUriFromTextSlideIfPresent(context, conversationMessage)
    if (attachmentUri != null) return attachmentUri
    
    // Fall back to body
    val bodyUri = extractSigmoUri(conversationMessage.messageRecord.body)
    val visibleUri = extractSigmoUri(visibleText)
    
    return bodyUri ?: visibleUri
  }

  private fun createPill(context: Context, sigmoUri: String, conversationMessage: ConversationMessage): PillBinding? {
    val outgoing = conversationMessage.messageRecord.isOutgoing
    val recipient: Recipient = if (outgoing) {
      conversationMessage.messageRecord.toRecipient
    } else {
      conversationMessage.messageRecord.fromRecipient
    }

    val formatter = java.text.NumberFormat.getInstance()
    formatter.maximumFractionDigits = 0

    val pill = SigmoRequestMessageView(context)
    val colorizer = Colorizer()
    
    // Check if this is an invoice request or invoice response
    val parsedRequest = SigmoProtocolProcessor.parseSigmoUri(sigmoUri)
    val parsedInvoice = SigmoProtocolProcessor.parseSigmoInvoice(sigmoUri)
    
    return when {
      parsedRequest != null -> {
        // This is an invoice REQUEST
        val amountSats = parsedRequest.amountMsats / 1000
        val amountText = context.getString(R.string.LightningInvoice_sats_format, formatter.format(amountSats))
        
        // Check payment flow status if we have a request_id
        if (outgoing && parsedRequest.requestId != null) {
          val flow = SignalStore.payments.getSigmoFlow(parsedRequest.requestId)
          when (flow?.status) {
            SigmoFlowStatus.PAID -> {
              // Payment complete - show "You sent to User" with no status
              val youSentText = context.getString(R.string.SigmoRequest_you_sent_to, recipient.getShortDisplayName(context))
              pill.bind(youSentText, amountText, outgoing, recipient, colorizer, hideTitle = true)
              pill.setStatusText("")
              pill.setStatusVisible(false)
            }
            SigmoFlowStatus.INVOICE_RECEIVED -> {
              // Invoice received but not yet paid - show "Pending sending User"
              val pendingText = context.getString(R.string.SigmoRequest_pending_sending, recipient.getShortDisplayName(context))
              pill.bind(pendingText, amountText, outgoing, recipient, colorizer, hideTitle = true)
              pill.setStatusText(context.getString(R.string.SigmoRequest_invoice_received))
            }
            SigmoFlowStatus.FAILED -> {
              val direction = context.getString(R.string.SigmoRequest_sending_to, recipient.getShortDisplayName(context))
              pill.bind(direction, amountText, outgoing, recipient, colorizer)
              pill.setStatusText(context.getString(R.string.LightningInvoice_payment_failed))
            }
            else -> {
              // PENDING or unknown - waiting for invoice
              val direction = context.getString(R.string.SigmoRequest_sending_to, recipient.getShortDisplayName(context))
              pill.bind(direction, amountText, outgoing, recipient, colorizer)
              pill.setStatusText(context.getString(R.string.SigmoRequest_waiting_for_invoice))
            }
          }
        } else if (outgoing) {
          // Outgoing request without request_id
          val direction = context.getString(R.string.SigmoRequest_sending_to, recipient.getShortDisplayName(context))
          pill.bind(direction, amountText, outgoing, recipient, colorizer)
          pill.setStatusText(context.getString(R.string.SigmoRequest_waiting_for_invoice))
        } else if (!outgoing && parsedRequest.requestId != null) {
          // Incoming request - check if we've already responded with an invoice
          if (SignalStore.payments.hasSigmoRequestBeenResponded(parsedRequest.requestId)) {
            // We've sent an invoice, hide this request - the invoice message will be shown instead
            return null
          }
          val direction = context.getString(R.string.SigmoRequest_from, recipient.getShortDisplayName(context))
          pill.bind(direction, amountText, outgoing, recipient, colorizer)
          pill.setStatusText(context.getString(R.string.SigmoRequest_generating_invoice))
        } else if (!outgoing) {
          val direction = context.getString(R.string.SigmoRequest_from, recipient.getShortDisplayName(context))
          pill.bind(direction, amountText, outgoing, recipient, colorizer)
          pill.setStatusText(context.getString(R.string.SigmoRequest_generating_invoice))
        }
        
        PillBinding(pill, SigmoMessageType.REQUEST, outgoing, recipient, parsedRequest.requestId)
      }
      
      parsedInvoice != null -> {
        // This is an invoice RESPONSE
        // Try to decode invoice amount using simple BOLT11 parsing
        val amountSats = parseAmountFromBolt11(parsedInvoice.invoice)
        val amountText = if (amountSats != null && amountSats > 0) {
          context.getString(R.string.LightningInvoice_sats_format, formatter.format(amountSats))
        } else {
          context.getString(R.string.SigmoRequest_invoice_ready)
        }
        
        // Store invoice for status tracking
        pill.setInvoice(parsedInvoice.invoice)
        
        if (outgoing) {
          // Outgoing invoice (we're the receiver waiting for payment)
          // Check if already paid
          if (SignalStore.payments.isInvoicePaid(parsedInvoice.invoice)) {
            // Payment received - show "Received from User"
            val receivedText = context.getString(R.string.SigmoRequest_received_from, recipient.getShortDisplayName(context))
            pill.bind(receivedText, amountText, outgoing, recipient, colorizer, hideTitle = true)
            pill.setStatusText("")
            pill.setStatusVisible(false)
            pill.setRefreshButtonVisible(false)
          } else {
            // Waiting for payment - show "Pending from User"
            val pendingText = context.getString(R.string.SigmoRequest_pending_from, recipient.getShortDisplayName(context))
            pill.bind(pendingText, amountText, outgoing, recipient, colorizer, hideTitle = true)
            pill.setStatusText(context.getString(R.string.SigmoRequest_waiting_for_payment))
            pill.setRefreshButtonVisible(true)
          }
        } else {
          // Incoming invoice (sender side - we need to pay)
          val isPaid = SignalStore.payments.isInvoicePaid(parsedInvoice.invoice)
          
          if (isPaid) {
            // Show "You sent to User" after payment is complete
            val youSentText = context.getString(R.string.SigmoRequest_you_sent_to, recipient.getShortDisplayName(context))
            pill.bind(youSentText, amountText, outgoing, recipient, colorizer, hideTitle = true)
            pill.setStatusText("")
            pill.setStatusVisible(false)
          } else {
            // Show "Pending sending User" with "Tap to Pay" button
            val pendingText = context.getString(R.string.SigmoRequest_pending_sending, recipient.getShortDisplayName(context))
            pill.bind(pendingText, amountText, outgoing, recipient, colorizer, hideTitle = true)
            pill.setStatusText(context.getString(R.string.SigmoRequest_tap_to_pay))
          }
        }
        
        PillBinding(pill, SigmoMessageType.INVOICE, outgoing, recipient, parsedInvoice.requestId)
      }
      
      else -> null
    }
  }

  /**
   * Configure pay action for incoming invoice responses.
   * Uses the pay button instead of whole view being clickable to prevent accidental taps.
   */
  private fun configurePayAction(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pill.getInvoice() ?: return
    
    // Show the pay button
    pill.setPayButtonVisible(true)
    
    // Configure button with payment handler
    pill.configurePayButton { invoiceStr ->
      Log.i(TAG, "Pay action triggered for sigmo invoice")
      
      CoroutineScope(Dispatchers.Main).launch {
        val ctx = pill.context
        
        try {
          val result = withContext(Dispatchers.IO) {
            if (LightningUiInteractor.isConfigured(ctx)) {
              LightningUiInteractor.payInvoiceBlocking(ctx, invoiceStr, null)
            } else {
              // Fall back to Cashu melt
              val quote = org.thoughtcrime.securesms.payments.engine.CashuUiInteractor.requestMeltQuoteBlocking(
                AppDependencies.application, invoiceStr
              )
              if (quote != null) {
                val success = org.thoughtcrime.securesms.payments.engine.CashuUiInteractor.meltBlocking(
                  AppDependencies.application, quote
                )
                if (success) "paid" else null
              } else {
                null
              }
            }
          }
          
          if (result != null) {
            pill.setPayButtonState(true)
            pill.setPayButtonVisible(false)
            pill.setStatusText("")
            pill.setStatusVisible(false)
            SignalStore.payments.setInvoicePaid(invoiceStr)
            
            // Update direction text to show "You sent to User"
            val youSentText = ctx.getString(R.string.SigmoRequest_you_sent_to, pillBinding.recipient.getShortDisplayName(ctx))
            pill.setDirectionText(youSentText)
            
            // Update flow status if we have request_id
            pillBinding.requestId?.let { requestId ->
              SignalStore.payments.markSigmoFlowPaid(requestId)
            }
          } else {
            pill.setPayButtonState(false)
            Toast.makeText(ctx, R.string.LightningInvoice_payment_failed, Toast.LENGTH_SHORT).show()
          }
        } catch (e: Exception) {
          Log.e(TAG, "Payment failed", e)
          pill.setPayButtonState(false)
          Toast.makeText(ctx, R.string.LightningInvoice_payment_failed, Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  /**
   * Configure refresh action for outgoing invoices (receiver checking if paid).
   */
  private fun configureRefreshAction(pillBinding: PillBinding) {
    val pill = pillBinding.view
    pill.setOnRefreshClickListener {
      // Manual refresh - do a single check
      checkInvoiceStatusOnce(pillBinding)
    }
  }
  
  /**
   * Start polling for invoice payment status.
   * Polls every 5 seconds for up to 5 minutes (300 seconds).
   * Uses stored payment hash for efficient LNI lookupInvoice.
   */
  private fun startInvoicePolling(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pill.getInvoice() ?: return
    val ctx = pill.context
    
    // First check persisted state
    if (SignalStore.payments.isInvoicePaid(invoice)) {
      Log.d(TAG, "Invoice already marked as paid locally, updating UI")
      handlePaymentReceived(pillBinding)
      return
    }
    
    // Get stored payment hash for efficient lookup
    val paymentHash = SignalStore.payments.getInvoicePaymentHash(invoice)
    Log.d(TAG, "Starting invoice polling - invoice hash: ${invoice.hashCode()}, payment hash: ${paymentHash?.take(16) ?: "null"}")
    
    pill.showSpinner(true)
    pill.setStatusText(ctx.getString(R.string.SigmoRequest_waiting_for_payment))
    
    CoroutineScope(Dispatchers.Main).launch {
      val startTime = System.currentTimeMillis()
      val maxPollingMs = 5 * 60 * 1000L // 5 minutes
      val pollIntervalMs = 5000L // 5 seconds
      
      while (System.currentTimeMillis() - startTime < maxPollingMs) {
        try {
          val isPaid = withContext(Dispatchers.IO) {
            if (!LightningUiInteractor.isConfigured(ctx)) {
              Log.w(TAG, "Lightning not configured, cannot check invoice status")
              return@withContext null
            }
            
            // Use stored payment hash for direct lookup if available
            if (paymentHash != null) {
              Log.d(TAG, "Checking invoice status via payment hash: ${paymentHash.take(16)}...")
              val status = LightningUiInteractor.lookupInvoiceBlocking(ctx, paymentHash)
              Log.d(TAG, "LNI lookup result: isPaid=${status?.isPaid}, status=$status")
              status?.isPaid
            } else {
              Log.d(TAG, "No payment hash stored, using invoice lookup")
              LightningUiInteractor.isInvoicePaidBlocking(ctx, invoice)
            }
          }
          
          Log.d(TAG, "Poll result: isPaid=$isPaid")
          
          if (isPaid == true) {
            Log.d(TAG, "Payment detected! Updating UI...")
            pill.showSpinner(false)
            handlePaymentReceived(pillBinding)
            return@launch
          }
        } catch (e: Throwable) {
          Log.w(TAG, "Failed to check invoice status during polling", e)
        }
        
        // Wait before next poll
        kotlinx.coroutines.delay(pollIntervalMs)
      }
      
      // Polling timed out
      pill.showSpinner(false)
      Log.d(TAG, "Invoice polling timed out after 5 minutes")
    }
  }
  
  /**
   * Handle successful payment received.
   */
  private fun handlePaymentReceived(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pill.getInvoice() ?: return
    val ctx = pill.context
    
    Log.i(TAG, "Payment received! Marking invoice as paid locally")
    
    // Persist the paid status
    SignalStore.payments.setInvoicePaid(invoice)
    
    // Update UI to show "Received from User"
    val receivedText = ctx.getString(R.string.SigmoRequest_received_from, pillBinding.recipient.getShortDisplayName(ctx))
    pill.setDirectionText(receivedText)
    pill.setStatusText("")
    pill.setStatusVisible(false)
    pill.setRefreshButtonVisible(false)
    
    // Update flow status if we have request_id
    pillBinding.requestId?.let { requestId ->
      Log.i(TAG, "Marking SigmoFlow $requestId as paid")
      SignalStore.payments.markSigmoFlowPaid(requestId)
    }
  }

  /**
   * Check if an outgoing invoice has been paid (single check, not polling).
   * Uses stored payment hash for efficient LNI lookupInvoice.
   */
  private fun checkInvoiceStatusOnce(pillBinding: PillBinding) {
    val pill = pillBinding.view
    val invoice = pill.getInvoice() ?: return
    val ctx = pill.context

    // First check persisted state
    if (SignalStore.payments.isInvoicePaid(invoice)) {
      Log.d(TAG, "Invoice already marked as paid locally")
      handlePaymentReceived(pillBinding)
      return
    }

    // Get stored payment hash for efficient lookup
    val paymentHash = SignalStore.payments.getInvoicePaymentHash(invoice)
    Log.d(TAG, "Manual refresh - invoice hash: ${invoice.hashCode()}, payment hash: ${paymentHash?.take(16) ?: "null"}")
    
    pill.showSpinner(true)

    CoroutineScope(Dispatchers.Main).launch {
      try {
        val isPaid = withContext(Dispatchers.IO) {
          if (!LightningUiInteractor.isConfigured(ctx)) {
            Log.w(TAG, "Lightning not configured")
            return@withContext null
          }
          
          // Use stored payment hash for direct lookup if available
          if (paymentHash != null) {
            Log.d(TAG, "Looking up invoice via payment hash: ${paymentHash.take(16)}...")
            val status = LightningUiInteractor.lookupInvoiceBlocking(ctx, paymentHash)
            Log.d(TAG, "LNI lookup result: isPaid=${status?.isPaid}, status=$status")
            status?.isPaid
          } else {
            Log.d(TAG, "No payment hash stored, falling back to invoice lookup")
            LightningUiInteractor.isInvoicePaidBlocking(ctx, invoice)
          }
        }

        pill.showSpinner(false)
        Log.d(TAG, "Manual refresh result: isPaid=$isPaid")

        when (isPaid) {
          true -> {
            Log.d(TAG, "Payment detected on manual refresh!")
            handlePaymentReceived(pillBinding)
          }
          false -> pill.setStatusText(ctx.getString(R.string.SigmoRequest_waiting_for_payment))
          null -> pill.setStatusText(ctx.getString(R.string.SigmoRequest_waiting_for_payment))
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to check invoice status", e)
        pill.showSpinner(false)
        pill.setStatusText(ctx.getString(R.string.SigmoRequest_waiting_for_payment))
      }
    }
  }
  
  /**
   * Legacy method - redirects to polling.
   */
  private fun checkInvoiceStatus(pillBinding: PillBinding) {
    startInvoicePolling(pillBinding)
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
    pill.layoutParams = newLp
    parent.addView(pill, index + 1)
  }

  /**
   * Parse amount in satoshis from BOLT11 invoice.
   * 
   * BOLT11 format: ln{bc|tb|bcrt}{amount}{multiplier}{separator}...
   * Multipliers: m = milli-BTC, u = micro-BTC, n = nano-BTC, p = pico-BTC
   */
  private fun parseAmountFromBolt11(invoice: String): Long? {
    return try {
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
      when (multiplier) {
        'm' -> amountNum * 100_000  // milli-BTC to sats
        'u' -> amountNum * 100      // micro-BTC to sats
        'n' -> amountNum / 10       // nano-BTC to sats (1000n = 100 sats)
        'p' -> amountNum / 10_000   // pico-BTC to sats
        null -> amountNum * 100_000_000  // Full BTC to sats (unlikely)
        else -> null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse BOLT11 amount", e)
      null
    }
  }
}
