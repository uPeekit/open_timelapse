package org.peekit.opentimelapse.pro

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** [price] is the store's own formatted string, null until the store has answered. */
data class ProState(val unlocked: Boolean, val price: String? = null)

/**
 * Whether the paid features are available.
 *
 * Each store flavor supplies its own `createProAccess`: foss is always unlocked, play asks
 * Google Play Billing. Only the UI consults this - it decides which settings can be reached,
 * never what a running session does, so a billing hiccup cannot cost a frame.
 */
interface ProAccess {
    val state: StateFlow<ProState>

    /** Re-reads ownership from the store. Cheap; called whenever the app comes to the front. */
    fun refresh()

    /** Starts the purchase; the result arrives through [state]. */
    fun purchase(activity: Activity)
}
