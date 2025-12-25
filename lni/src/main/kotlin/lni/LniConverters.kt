/**
 * Shared type converters between UniFFI native types and wrapper types.
 * 
 * This file contains extension functions to convert native LNI types
 * to wrapper types used by the Signal Android app.
 */
package lni

/**
 * Convert native Transaction to wrapper Transaction
 */
internal fun uniffi.lni.Transaction.toWrapper(): Transaction {
    // Infer status from settledAt: if > 0 then complete, otherwise pending
    val status = if (this.settledAt > 0) TransactionStatus.Complete else TransactionStatus.Pending
    
    // type is a String in native bindings ("incoming" or "outgoing")
    val txType = when (this.type.lowercase()) {
        "incoming" -> TransactionType.Incoming
        "outgoing" -> TransactionType.Outgoing
        else -> TransactionType.Incoming
    }
    
    return Transaction(
        paymentHash = this.paymentHash,
        paymentRequest = this.invoice,
        amountMsats = this.amountMsats,
        feeMsats = this.feesPaid,
        status = status,
        type = txType,
        createdAt = this.createdAt,
        settledAt = if (this.settledAt > 0) this.settledAt else null,
        description = this.description.ifEmpty { null },
        preimage = this.preimage.ifEmpty { null },
        bolt11 = this.invoice.ifEmpty { null },
        bolt12 = null,
        offer = null
    )
}

/**
 * Convert native Offer to wrapper Offer
 */
internal fun uniffi.lni.Offer.toWrapper(): Offer {
    return Offer(
        offerId = this.offerId,
        offer = this.bolt12,
        active = this.active ?: false,
        singleUse = this.singleUse ?: false,
        description = this.label,
        amountMsats = this.amountMsats
    )
}

/**
 * Convert native NodeInfo to wrapper NodeInfo
 */
internal fun uniffi.lni.NodeInfo.toWrapper(): NodeInfo {
    return NodeInfo(
        alias = this.alias,
        pubkey = this.pubkey,
        network = this.network,
        blockHeight = this.blockHeight,
        balanceSat = this.sendBalanceMsat?.let { it / 1000 },
        maxPayableSat = this.sendBalanceMsat?.let { it / 1000 },
        maxReceivableSat = this.receiveBalanceMsat?.let { it / 1000 }
    )
}

/**
 * Convert native PayInvoiceResponse to wrapper PayInvoiceResponse
 */
internal fun uniffi.lni.PayInvoiceResponse.toWrapper(): PayInvoiceResponse {
    return PayInvoiceResponse(
        paymentHash = this.paymentHash,
        preimage = this.preimage,
        feeMsats = this.feeMsats,
        status = TransactionStatus.Complete
    )
}

/**
 * Convert wrapper CreateInvoiceParams to native params
 */
internal fun CreateInvoiceParams.toNative(): uniffi.lni.CreateInvoiceParams {
    return uniffi.lni.CreateInvoiceParams(
        invoiceType = when (this.invoiceType) {
            InvoiceType.Bolt11 -> uniffi.lni.InvoiceType.BOLT11
            InvoiceType.Bolt12 -> uniffi.lni.InvoiceType.BOLT12
        },
        amountMsats = this.amountMsats,
        description = this.description,
        expiry = this.expiry,
        descriptionHash = this.descriptionHash,
        offer = null,
        rPreimage = null,
        isBlinded = false,
        isKeysend = false,
        isAmp = false,
        isPrivate = false
    )
}

/**
 * Convert wrapper PayInvoiceParams to native params
 */
internal fun PayInvoiceParams.toNative(): uniffi.lni.PayInvoiceParams {
    return uniffi.lni.PayInvoiceParams(
        invoice = this.invoice,
        feeLimitMsat = null,
        feeLimitPercentage = this.feeLimitPercentage?.toDouble(),
        timeoutSeconds = null,
        amountMsats = this.amountMsats,
        maxParts = null,
        firstHopPubkey = null,
        lastHopPubkey = null,
        allowSelfPayment = this.allowSelfPayment ?: false,
        isAmp = false
    )
}

/**
 * Convert wrapper CreateOfferParams to native params
 */
internal fun CreateOfferParams.toNative(): uniffi.lni.CreateOfferParams {
    return uniffi.lni.CreateOfferParams(
        description = this.description,
        amountMsats = this.amountMsats
    )
}

/**
 * Convert wrapper ListTransactionsParams to native params
 */
internal fun ListTransactionsParams.toNative(): uniffi.lni.ListTransactionsParams {
    return uniffi.lni.ListTransactionsParams(
        from = this.from.toLong(),
        limit = this.limit.toLong(),
        paymentHash = this.paymentHash,
        search = null
    )
}
