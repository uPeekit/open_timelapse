package org.peekit.opentimelapse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Pink + lime, hand-picked rather than dynamic: the app should look the same on every
 * phone it is installed on, and the Start button's lime only reads as "go" against the
 * pink if neither is swapped out by the system palette.
 *
 * The surfaceContainer* slots are tinted pink deliberately - cards draw on those, not on
 * surface, so without them every card falls back to Material's neutral grey and the
 * palette only shows up in the buttons.
 */

/** The one deliberately loud colour: the Start button and switched-on toggles. */
val Lime = Color(0xFFB2E800)
val OnLime = Color(0xFF1F2D00)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF546B15),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E893),
    onSecondaryContainer = Color(0xFF171E00),
    tertiary = Color(0xFF7CB342),
    background = Color(0xFFFFF3F6),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF3F6),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF3DDE2),
    onSurfaceVariant = Color(0xFF514347),
    surfaceTint = Color(0xFFC2185B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFEF0F3),
    surfaceContainer = Color(0xFFFCEBEF),
    surfaceContainerHigh = Color(0xFFFAE4EA),
    surfaceContainerHighest = Color(0xFFF8DEE5),
    outline = Color(0xFF837377),
    outlineVariant = Color(0xFFE7C7D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C8),
    onPrimary = Color(0xFF650033),
    primaryContainer = Color(0xFF8E004A),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFBBD273),
    onSecondary = Color(0xFF2A3400),
    secondaryContainer = Color(0xFF3E4C00),
    onSecondaryContainer = Color(0xFFD6E893),
    tertiary = Color(0xFFAED581),
    background = Color(0xFF1B1115),
    onBackground = Color(0xFFEFDFE2),
    surface = Color(0xFF1B1115),
    onSurface = Color(0xFFEFDFE2),
    surfaceVariant = Color(0xFF514347),
    onSurfaceVariant = Color(0xFFD5C2C7),
    surfaceTint = Color(0xFFFFB1C8),
    surfaceContainerLowest = Color(0xFF150C0F),
    surfaceContainerLow = Color(0xFF241419),
    surfaceContainer = Color(0xFF2A181E),
    surfaceContainerHigh = Color(0xFF351F27),
    surfaceContainerHighest = Color(0xFF402630),
    outline = Color(0xFF9E8C91),
    outlineVariant = Color(0xFF514347),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/**
 * A Switch that turns lime when on. The default primary-coloured track reads as "pink =
 * on", which fights the Start button's "lime = go"; one green everywhere keeps the two
 * states unambiguous.
 */
@Composable
internal fun LimeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = OnLime,
            checkedTrackColor = Lime,
        ),
    )
}
