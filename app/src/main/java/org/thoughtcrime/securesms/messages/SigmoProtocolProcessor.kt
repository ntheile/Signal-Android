package org.thoughtcrime.securesms.messages

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.payments.engine.lightning.LightningUiInteractor
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sms.MessageSender

/**
 * Processes incoming sigmo: protocol messages and auto-generates invoice responses.
 * 
 * sigmo: protocol format:
 * sigmo:lnurlp/{username}/callback?amount={amount_in_msats}&request_id={uuid}
 * 
 * When we receive this message, we:
 * 1. Parse the amount and request_id from the URI
 * 2. Create a Lightning invoice for that amount using LNI
 * 3. Auto-send the invoice back to the sender with the request_id for correlation
 */
object SigmoProtocolProcessor {
    private const val TAG = "SigmoProtocolProcessor"
    private const val SIGMO_PREFIX = "sigmo:"
    
    // Pattern for invoice request: sigmo:lnurlp/{username}/callback?amount={msats}&request_id={uuid}
    private val SIGMO_REQUEST_PATTERN = Regex(
        """sigmo:lnurlp/([^/]+)/callback\?amount=(\d+)(?:&request_id=([a-zA-Z0-9-]+))?""", 
        RegexOption.IGNORE_CASE
    )
    
    // Pattern for invoice response: sigmo:{invoice}&request_id={uuid}
    // Lightning invoices start with "lnbc" or "lntb" (testnet)
    private val SIGMO_INVOICE_PATTERN = Regex(
        """sigmo:(ln[a-zA-Z0-9]+)(?:&request_id=([a-zA-Z0-9-]+))?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parsed sigmo: request URI data.
     */
    data class SigmoParsedUri(
        val username: String,
        val amountMsats: Long,
        val requestId: String?
    )
    
    /**
     * Parsed sigmo: invoice response data.
     */
    data class SigmoInvoiceResponse(
        val invoice: String,
        val requestId: String?
    )

    /**
     * Check if a message body contains a sigmo: protocol URI.
     */
    fun isSigmoMessage(body: String?): Boolean {
        return body?.startsWith(SIGMO_PREFIX, ignoreCase = true) == true
    }

    /**
     * Process an incoming sigmo: message and auto-generate an invoice response.
     * 
     * @param context The application context
     * @param messageBody The body of the incoming message
     * @param senderRecipientId The recipient ID of the sender (who we'll send the invoice to)
     * @param threadId The thread ID for the conversation
     */
    fun processIncomingMessage(
        context: Context,
        messageBody: String,
        senderRecipientId: RecipientId,
        threadId: Long
    ) {
        if (!isSigmoMessage(messageBody)) {
            return
        }

        Log.i(TAG, "Processing sigmo: protocol message")
        
        // Parse the sigmo URI
        val parsedUri = parseSigmoUri(messageBody)
        if (parsedUri == null) {
            Log.w(TAG, "Failed to parse sigmo URI: $messageBody")
            return
        }
        
        // Convert millisats to sats (1 sat = 1000 msats)
        val amountSats = parsedUri.amountMsats / 1000
        
        if (amountSats <= 0) {
            Log.w(TAG, "Invalid amount in sigmo URI: ${parsedUri.amountMsats} msats")
            return
        }

        // Check if Lightning is configured
        val isConfigured = LightningUiInteractor.isConfigured(context)
        Log.i(TAG, "Lightning configuration check: isConfigured=$isConfigured")
        
        if (!isConfigured) {
            Log.w(TAG, "Lightning not configured, cannot auto-generate invoice")
            return
        }

        Log.i(TAG, "Starting invoice generation for $amountSats sats, requestId=${parsedUri.requestId}")
        
        // Auto-generate and send invoice in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                generateAndSendInvoice(context, senderRecipientId, threadId, amountSats, parsedUri.requestId)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to generate invoice for sigmo request: ${e.message}", e)
            }
        }
    }

    /**
     * Parse a sigmo: request URI and extract the username, amount, and optional request_id.
     */
    fun parseSigmoUri(uri: String): SigmoParsedUri? {
        val match = SIGMO_REQUEST_PATTERN.find(uri) ?: return null
        
        val username = match.groupValues[1]
        val amountMsats = match.groupValues[2].toLongOrNull() ?: return null
        val requestId = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
        
        return SigmoParsedUri(username, amountMsats, requestId)
    }
    
    /**
     * Parse a sigmo: invoice response and extract the invoice and optional request_id.
     */
    fun parseSigmoInvoice(uri: String): SigmoInvoiceResponse? {
        // Don't match requests
        if (SIGMO_REQUEST_PATTERN.matches(uri)) return null
        
        val match = SIGMO_INVOICE_PATTERN.find(uri) ?: return null
        
        val invoice = match.groupValues[1]
        val requestId = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
        
        return SigmoInvoiceResponse(invoice, requestId)
    }
    
    /**
     * Check if a sigmo: URI is an invoice request (vs invoice response).
     */
    fun isInvoiceRequest(uri: String): Boolean {
        return SIGMO_REQUEST_PATTERN.matches(uri)
    }
    
    /**
     * Check if a sigmo: URI is an invoice response.
     */
    fun isInvoiceResponse(uri: String): Boolean {
        return !SIGMO_REQUEST_PATTERN.matches(uri) && SIGMO_INVOICE_PATTERN.matches(uri)
    }

    /**
     * Generate a Lightning invoice and send it back to the requester.
     * The invoice message includes the request_id for correlation.
     */
    private suspend fun generateAndSendInvoice(
        context: Context,
        senderRecipientId: RecipientId,
        threadId: Long,
        amountSats: Long,
        requestId: String?
    ) {
        val sender = Recipient.resolved(senderRecipientId)
        val description = "Invoice requested by ${sender.getDisplayName(context)}"
        
        Log.i(TAG, "generateAndSendInvoice: Creating invoice for $amountSats sats, requestId=$requestId, sender=${sender.getDisplayName(context)}")
        
        // Use createInvoiceWithHash to get both invoice and payment hash for tracking
        Log.d(TAG, "generateAndSendInvoice: Calling LightningUiInteractor.createInvoiceWithHashBlocking...")
        val invoiceResult = LightningUiInteractor.createInvoiceWithHashBlocking(context, amountSats, description)
        
        if (invoiceResult == null) {
            Log.e(TAG, "generateAndSendInvoice: Failed to create invoice - LightningUiInteractor returned null")
            return
        }
        
        Log.i(TAG, "generateAndSendInvoice: Invoice created successfully! hash=${invoiceResult.paymentHash.take(16)}...")
        
        // Build message body with sigmo: prefix for client recognition and grouping
        // Format: sigmo:{invoice}[&request_id={uuid}]
        val messageBody = if (requestId != null) {
            "sigmo:${invoiceResult.paymentRequest}&request_id=$requestId"
        } else {
            "sigmo:${invoiceResult.paymentRequest}"
        }
        
        // Send the invoice back as a message
        val outgoingMessage = OutgoingMessage(
            threadRecipient = sender,
            body = messageBody,
            sentTimeMillis = System.currentTimeMillis(),
            isUrgent = true,
            isSecure = true
        )

        MessageSender.send(
            AppDependencies.application,
            outgoingMessage,
            threadId,
            MessageSender.SendType.SIGNAL,
            null,
            null
        )
        
        // Mark that we've responded to this request, storing both invoice and payment hash
        if (requestId != null) {
            SignalStore.payments.markSigmoRequestResponded(requestId, invoiceResult.paymentRequest)
            // Store the payment hash for this invoice so we can look it up efficiently
            SignalStore.payments.setInvoicePaymentHash(invoiceResult.paymentRequest, invoiceResult.paymentHash)
        }
        
        Log.i(TAG, "Invoice sent successfully")
    }
}
