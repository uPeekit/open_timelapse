package org.peekit.opentimelapse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.peekit.opentimelapse.pro.ProState

/** The Pro state and the way to change it, passed together to every section that gates on it. */
class ProGate(val state: ProState, val onUnlock: () -> Unit) {
    val unlocked: Boolean get() = state.unlocked

    /** Marks a card title as paid while it is locked. */
    fun title(title: String): String = if (unlocked) title else "$title · Pro"
}

/**
 * What a locked feature shows in place of its controls: what it would do, and the one button
 * that unlocks all of them. Shooting itself is never behind this.
 */
@Composable
internal fun ProUpsell(feature: String, pro: ProGate) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(feature, style = MaterialTheme.typography.bodySmall)
        FilledTonalButton(onClick = pro.onUnlock, modifier = Modifier.fillMaxWidth()) {
            Text(pro.state.price?.let { "Unlock Pro · $it" } ?: "Unlock Pro")
        }
        Text(
            "One purchase, no subscription. Unlocks control over wi-fi, smart-plug charging, " +
                "the custom render command and stopping at a set date and time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
