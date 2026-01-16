package org.thoughtcrime.securesms.payments.preferences.lightning

import uniffi.lni.generateMnemonic

/**
 * Helper object to call LNI functions from Java code.
 * This is needed because some LNI functions use Kotlin inline classes (like UByte)
 * which cause name mangling and aren't easily callable from Java.
 */
object LniHelper {
    
    /**
     * Generate a new BIP39 mnemonic phrase.
     * @param wordCount Number of words: 12 (default) or 24. Use null for default (12).
     * @return A space-separated mnemonic phrase
     */
    @JvmStatic
    @Throws(uniffi.lni.ApiException::class)
    fun generateMnemonic(wordCount: Int? = null): String {
        val ubyte = wordCount?.toUByte()
        return uniffi.lni.generateMnemonic(ubyte)
    }
}
