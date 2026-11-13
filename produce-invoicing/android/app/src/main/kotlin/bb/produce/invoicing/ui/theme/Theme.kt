package bb.produce.invoicing.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Colour tokens taken from the v4 prototype (reference/, "Produce Invoicing v4").
 * Fonts: v4 uses Public Sans (UI) and IBM Plex Mono (labels). Bundling those
 * OFL font files is deferred to Task 03; system sans/mono are used until then.
 */
object PiColors {
    val Forest = Color(0xFF2D5E3E)
    val ForestDark = Color(0xFF234C31)
    val ForestDeep = Color(0xFF1F4A2E)
    val ForestTint = Color(0xFFE7EFE6)
    val Amber = Color(0xFF7A4F0E)
    val AmberTint = Color(0xFFF6EDDA)
    val Red = Color(0xFFA3352A)
    val Ink = Color(0xFF1C1F1A)
    val InkMuted = Color(0xFF5D6158)
    val Border = Color(0xFFDDD8CC)
    val BorderStrong = Color(0xFFC9C3B5)
    val Background = Color(0xFFF5F2EA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFE7E3D9)
}

private val LightColors = lightColorScheme(
    primary = PiColors.Forest,
    onPrimary = Color.White,
    primaryContainer = PiColors.ForestTint,
    onPrimaryContainer = PiColors.ForestDeep,
    secondary = PiColors.ForestDark,
    tertiary = PiColors.Amber,
    tertiaryContainer = PiColors.AmberTint,
    error = PiColors.Red,
    background = PiColors.Background,
    onBackground = PiColors.Ink,
    surface = PiColors.Surface,
    onSurface = PiColors.Ink,
    surfaceVariant = PiColors.SurfaceVariant,
    onSurfaceVariant = PiColors.InkMuted,
    outline = PiColors.BorderStrong,
    outlineVariant = PiColors.Border,
)

private val PiShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
)

@Composable
fun ProduceInvoicingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, shapes = PiShapes, content = content)
}
