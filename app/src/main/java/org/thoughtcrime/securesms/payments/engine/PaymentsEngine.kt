package org.thoughtcrime.securesms.payments.engine

/**
 * PaymentsEngine abstraction to support Cashu, Lightning, and allow phasing out MobileCoin.
 * 
 * This interface is the core payment abstraction for the app. It supports:
 * - Cashu (ecash) for privacy-preserving bearer tokens
 * - Lightning (via LNI library integration) for direct node payments
 * - Cashu melt/mint for Lightning deposits and withdrawals
 * 
 * @see <a href="https://github.com/lightning-node-interface/lni">LNI Library</a>
 */
interface PaymentsEngine {
  suspend fun isAvailable(): Boolean
  suspend fun getBalance(): Balance
  suspend fun createRequest(amountSats: Long?, memo: String?): String
  suspend fun send(toTokenRequest: String?, amountSats: Long, memo: String?): Result<TxId>
  suspend fun importToken(token: String): Result<ImportResult>
  suspend fun listHistory(offset: Int, limit: Int): List<Tx>
  suspend fun backupExport(): EncryptedBlob
  suspend fun backupImport(blob: EncryptedBlob): Result<Unit>

  // Cashu-specific additions
  suspend fun requestMintQuote(amountSats: Long): Result<MintQuote>
  suspend fun createSendToken(amountSats: Long, memo: String? = null): Result<String>
  /**
   * Mint a paid mint quote. Implementation may accept either the quote id or the
   * bolt11 invoice string, depending on the underlying wallet library.
   */
  suspend fun mintPaidQuote(secretKeyOrId: String): Result<Unit>

  // Lightning withdrawal (melt) via Cashu
  suspend fun requestMeltQuote(invoiceBolt11: String): Result<MeltQuote>
  suspend fun melt(quote: MeltQuote): Result<TxId>

  // Optionally check a quote status (if engine supports it) and/or record pending
  suspend fun recordPendingMint(quote: MintQuote) {}
  
  // Lightning-specific methods (optional, check lightningAvailable first)
  // These use the LNI (Lightning Node Interface) library for direct node connections
  
  /**
   * Check if direct Lightning node is configured and available.
   * If true, payLightningInvoice and createLightningInvoice can be used.
   */
  suspend fun lightningAvailable(): Boolean = false
  
  /**
   * Get Lightning node balance (separate from Cashu balance).
   */
  suspend fun getLightningBalance(): LightningBalance = LightningBalance(0, 0)
  
  /**
   * Pay a Lightning invoice directly via the connected Lightning node.
   * This bypasses Cashu melt and pays directly from the node.
   */
  suspend fun payLightningInvoice(invoice: String, feeLimitSats: Long? = null): Result<LightningPayment> =
    Result.failure(UnsupportedOperationException("Lightning not configured"))
  
  /**
   * Create a Lightning invoice directly via the connected Lightning node.
   * This bypasses Cashu mint and creates an invoice on the node.
   */
  suspend fun createLightningInvoice(amountSats: Long, description: String? = null): Result<String> =
    Result.failure(UnsupportedOperationException("Lightning not configured"))
}

data class Balance(
  val totalSats: Long,
  val spendableSats: Long
)

data class TxId(val id: String)

data class ImportResult(val addedSats: Long)

data class Tx(
  val id: String,
  val timestampMs: Long,
  val amountSats: Long, // positive incoming, negative outgoing
  val memo: String?
)

data class EncryptedBlob(val bytes: ByteArray)

// Cashu mint quote (simplified)
data class MintQuote(
  val mintUrl: String,
  val amountSats: Long,
  val feeSats: Long,
  val totalSats: Long,
  val expiresAtMs: Long,
  val invoiceBolt11: String?,
  val id: String?
)

// Lightning withdrawal quote (simplified)
data class MeltQuote(
  val amountSats: Long,
  val feeSats: Long,
  val totalSats: Long,
  val expiresAtMs: Long,
  val invoiceBolt11: String,
  val id: String?
)

// Lightning balance from directly connected node
data class LightningBalance(
  val sendBalanceSats: Long,
  val receiveBalanceSats: Long
)

// Result of a direct Lightning payment
data class LightningPayment(
  val paymentHash: String,
  val preimage: String,
  val feeSats: Long
)
