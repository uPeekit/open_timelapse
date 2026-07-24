package org.peekit.opentimelapse.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * A text field that owns what it shows while the user is typing in it.
 *
 * Binding `value` straight to the config loses keystrokes: each one is written to DataStore
 * and read back through a flow, so the next keystroke is typed against the previous value
 * and overwrites it. Measured on a real device at four characters a second -
 * "http://127.0.0.1:8099/on" arrived as ".nt:2.00:801o".
 *
 * So the field is the source of truth while focused, and follows the stored value the rest
 * of the time - config loads asynchronously, and edits made elsewhere (calibration raising
 * the interval, a reset) must still show up.
 */
@Composable
internal fun BoundTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    if (!focused && value != text) text = value

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
