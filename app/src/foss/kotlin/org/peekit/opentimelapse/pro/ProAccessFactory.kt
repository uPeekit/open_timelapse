package org.peekit.opentimelapse.pro

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.peekit.opentimelapse.data.LogRepository

/** The open build has nothing to sell: everything is unlocked. */
@Suppress("UNUSED_PARAMETER")
fun createProAccess(context: Context, log: LogRepository): ProAccess = object : ProAccess {
    override val state: StateFlow<ProState> = MutableStateFlow(ProState(unlocked = true))
    override fun refresh() = Unit
    override fun purchase(activity: Activity) = Unit
}
