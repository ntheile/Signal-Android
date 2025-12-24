package org.thoughtcrime.securesms.payments.engine.lightning

import android.content.Context

/**
 * Provider for the Lightning Engine singleton.
 * 
 * This provides easy access to the Lightning engine throughout the app.
 * The engine is lazily created and cached for the lifetime of the app.
 */
object LightningEngineProvider {

    @Volatile
    private var engine: LightningEngine? = null

    /**
     * Get the Lightning engine instance.
     */
    @JvmStatic
    fun get(context: Context): LightningEngine {
        engine?.let { return it }

        synchronized(this) {
            engine?.let { return it }
            val newEngine = LightningEngine(context.applicationContext)
            engine = newEngine
            return newEngine
        }
    }

    /**
     * Reset the engine (for testing or reconfiguration).
     */
    @JvmStatic
    fun reset() {
        synchronized(this) {
            engine = null
        }
    }
}
